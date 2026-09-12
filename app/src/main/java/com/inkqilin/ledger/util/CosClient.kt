package com.inkqilin.ledger.util

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 从存储桶 URL 解析 Host（bucket-appid.cos.region.myqcloud.com） */
fun parseCosHost(bucketUrl: String): String {
    var s = bucketUrl.trim().trimEnd('/')
    if (s.isBlank()) return ""
    s = s.removePrefix("https://").removePrefix("http://")
    // 允许带路径，只取主机部分
    return s.substringBefore("/").substringBefore("?").substringBefore("#")
}

/** 腾讯云 COS 私有读写配置（密钥仅存本机 DataStore） */
data class CosConfig(
    val secretId: String = "",
    val secretKey: String = "",
    /** 完整存储桶访问域名，如 https://xxx-1250000000.cos.ap-guangzhou.myqcloud.com */
    val bucketUrl: String = "",
    val prefix: String = "backups/v1"
) {
    val host: String get() = parseCosHost(bucketUrl)

    val isConfigured: Boolean
        get() = secretId.isNotBlank() && secretKey.isNotBlank() &&
            host.contains(".cos.") && host.endsWith(".myqcloud.com")

    /** 用于状态展示：尽量短的桶标识 */
    val bucketLabel: String
        get() = host.substringBefore(".cos.").ifBlank { host }
}

data class CosObjectMeta(
    val key: String,
    val size: Long,
    val lastModified: String
)

/**
 * 轻量腾讯云 COS 客户端（私有读写，Signature V1/q-sign）。
 * 仅实现备份所需的 PUT / GET / GET Bucket / DELETE。
 */
object CosClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun putObject(
        config: CosConfig,
        key: String,
        bytes: ByteArray,
        contentType: String = "application/octet-stream"
    ): Unit = withContext(Dispatchers.IO) {
        require(config.isConfigured) { "COS 未配置" }
        val body = bytes.toRequestBody(contentType.toMediaType())
        val request = Request.Builder()
            .url("https://${config.host}/${encodeKey(key)}")
            .put(body)
            .header("Host", config.host)
            .header("Content-Type", contentType)
            .apply { addAuth(config, "PUT", key, headersToSign = mapOf("host" to config.host, "content-type" to contentType)) }
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                val err = it.body?.string().orEmpty()
                error("上传失败 HTTP ${it.code}: ${err.take(300)}")
            }
        }
    }

    suspend fun getObject(config: CosConfig, key: String): ByteArray = withContext(Dispatchers.IO) {
        require(config.isConfigured) { "COS 未配置" }
        val request = Request.Builder()
            .url("https://${config.host}/${encodeKey(key)}")
            .get()
            .header("Host", config.host)
            .apply { addAuth(config, "GET", key, headersToSign = mapOf("host" to config.host)) }
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                val err = it.body?.string().orEmpty()
                error("下载失败 HTTP ${it.code}: ${err.take(300)}")
            }
            it.body?.bytes() ?: error("空响应")
        }
    }

    suspend fun deleteObject(config: CosConfig, key: String): Unit = withContext(Dispatchers.IO) {
        require(config.isConfigured) { "COS 未配置" }
        val request = Request.Builder()
            .url("https://${config.host}/${encodeKey(key)}")
            .delete()
            .header("Host", config.host)
            .apply { addAuth(config, "DELETE", key, headersToSign = mapOf("host" to config.host)) }
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful && it.code != 204) {
                val err = it.body?.string().orEmpty()
                error("删除失败 HTTP ${it.code}: ${err.take(300)}")
            }
        }
    }

    suspend fun listObjects(config: CosConfig, prefix: String): List<CosObjectMeta> =
        withContext(Dispatchers.IO) {
            require(config.isConfigured) { "COS 未配置" }
            val encodedPrefix = URLEncoder.encode(prefix, "UTF-8")
            val request = Request.Builder()
                .url("https://${config.host}/?prefix=$encodedPrefix")
                .get()
                .header("Host", config.host)
                .apply {
                    addAuth(
                        config,
                        "GET",
                        key = "",
                        query = mapOf("prefix" to prefix),
                        headersToSign = mapOf("host" to config.host)
                    )
                }
                .build()
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    val err = it.body?.string().orEmpty()
                    error("列表失败 HTTP ${it.code}: ${err.take(300)}")
                }
                parseListXml(it.body?.string().orEmpty())
            }
        }

    private fun parseListXml(xml: String): List<CosObjectMeta> {
        if (xml.isBlank()) return emptyList()
        val result = mutableListOf<CosObjectMeta>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        var key: String? = null
        var size = 0L
        var lastModified = ""
        var inContents = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "Contents" -> {
                            inContents = true
                            key = null
                            size = 0L
                            lastModified = ""
                        }
                        "Key" -> if (inContents) key = parser.nextText()
                        "Size" -> if (inContents) size = parser.nextText().toLongOrNull() ?: 0L
                        "LastModified" -> if (inContents) lastModified = parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "Contents" && inContents) {
                        val k = key
                        if (!k.isNullOrBlank()) {
                            result += CosObjectMeta(k, size, lastModified)
                        }
                        inContents = false
                    }
                }
            }
            event = parser.next()
        }
        return result.sortedByDescending { it.lastModified }
    }

    /**
     * 腾讯云 COS 请求签名（q-sign-algorithm=sha1）。
     * @see https://cloud.tencent.com/document/product/436/7778
     */
    private fun Request.Builder.addAuth(
        config: CosConfig,
        method: String,
        key: String,
        query: Map<String, String> = emptyMap(),
        headersToSign: Map<String, String>
    ): Request.Builder {
        val now = System.currentTimeMillis() / 1000
        val keyTime = "${now - 60};${now + 600}"
        val signKey = hmacSha1Hex(config.secretKey, keyTime)

        val uriPath = "/" + encodeKey(key).let { if (it.isEmpty()) "" else it }
        val paramList = query.keys.sorted().joinToString(";") { it.lowercase() }
        val headerList = headersToSign.keys.sorted().joinToString(";") { it.lowercase() }

        val paramString = query.toSortedMap()
            .map { (k, v) -> "${k.lowercase()}=${v}" }
            .joinToString("&")
        val headerString = headersToSign.toSortedMap()
            .map { (k, v) -> "${k.lowercase()}=${v.trim()}" }
            .joinToString("&")

        val httpString = buildString {
            append(method.lowercase()).append('\n')
            append(uriPath).append('\n')
            append(paramString).append('\n')
            append(headerString).append('\n')
        }
        val httpStringSha1 = sha1Hex(httpString)
        val stringToSign = "sha1\n$keyTime\n$httpStringSha1\n"
        val signature = hmacSha1Hex(signKey, stringToSign)

        val authorization =
            "q-sign-algorithm=sha1&q-ak=${config.secretId}&q-sign-time=$keyTime" +
                "&q-key-time=$keyTime&q-header-list=$headerList&q-url-param-list=$paramList" +
                "&q-signature=$signature"

        header("Authorization", authorization)
        return this
    }

    /** COS 对象 key 的 URL 编码（保留 /） */
    private fun encodeKey(key: String): String {
        if (key.isEmpty()) return ""
        return key.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    private fun sha1Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha1Hex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
