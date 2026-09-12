package com.inkqilin.ledger.util

import android.content.Context
import com.inkqilin.ledger.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 个人账本云备份：导出 Room 库 zip → COS 私有桶 */
object CloudBackupManager {
    private const val DB_NAME = "ledger_database"

    private fun prefixOf(config: CosConfig): String =
        config.prefix.trim().trimEnd('/').ifBlank { "backups/v1" }

    data class LocalExport(val bytes: ByteArray, val fileName: String, val size: Long) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LocalExport) return false
            return fileName == other.fileName && size == other.size && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + size.hashCode()
            return result
        }
    }

    /** checkpoint WAL 后打包数据库文件 */
    suspend fun exportDatabaseZip(context: Context): LocalExport = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        // 将 WAL 合入主库，避免备份不完整
        runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        val wal = context.getDatabasePath("$DB_NAME-wal")
        val shm = context.getDatabasePath("$DB_NAME-shm")
        if (!dbFile.exists()) error("找不到本地数据库")

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ledger_$ts.zip"

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            fun putFile(f: File, entryName: String) {
                if (!f.exists() || f.length() == 0L) return
                zos.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            putFile(dbFile, DB_NAME)
            putFile(wal, "$DB_NAME-wal")
            putFile(shm, "$DB_NAME-shm")
        }
        val bytes = bos.toByteArray()
        LocalExport(bytes, fileName, bytes.size.toLong())
    }

    /** 上传备份到 COS */
    suspend fun uploadBackup(context: Context, config: CosConfig): CosObjectMeta {
        val export = exportDatabaseZip(context)
        val key = "${prefixOf(config)}/${export.fileName}"
        CosClient.putObject(
            config = config,
            key = key,
            bytes = export.bytes,
            contentType = "application/zip"
        )
        return CosObjectMeta(
            key = key,
            size = export.size,
            lastModified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        )
    }

    /** 列出云端备份 */
    suspend fun listBackups(config: CosConfig): List<CosObjectMeta> {
        return CosClient.listObjects(config, "${prefixOf(config)}/")
            .filter { it.key.endsWith(".zip") }
    }

    /**
     * 下载并恢复数据库。
     * 调用后应提示用户重启应用以完全加载。
     */
    suspend fun downloadAndRestore(context: Context, config: CosConfig, key: String): Unit =
        withContext(Dispatchers.IO) {
            val zipBytes = CosClient.getObject(config, key)
            restoreZipBytes(context, zipBytes)
        }

    fun restoreZipBytes(context: Context, zipBytes: ByteArray) {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    files[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        val mainDb = files[DB_NAME] ?: error("备份包中缺少 $DB_NAME")

        // 先关闭 Room 并丢弃单例，再覆盖文件
        AppDatabase.closeAndClear()

        val dbFile = context.getDatabasePath(DB_NAME)
        val wal = context.getDatabasePath("$DB_NAME-wal")
        val shm = context.getDatabasePath("$DB_NAME-shm")

        dbFile.parentFile?.mkdirs()
        // 覆盖前清掉旧 WAL，避免混库
        wal.delete()
        shm.delete()

        dbFile.outputStream().use { it.write(mainDb) }
        files["$DB_NAME-wal"]?.let { wal.outputStream().use { out -> out.write(it) } }
        files["$DB_NAME-shm"]?.let { shm.outputStream().use { out -> out.write(it) } }
    }

    suspend fun deleteBackup(config: CosConfig, key: String) {
        CosClient.deleteObject(config, key)
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}
