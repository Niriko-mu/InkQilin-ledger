package com.inkqilin.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkqilin.ledger.ui.TransactionViewModel
import com.inkqilin.ledger.util.CloudBackupManager
import com.inkqilin.ledger.util.CosConfig
import com.inkqilin.ledger.util.CosObjectMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed class BackupUiState {
    data object Idle : BackupUiState()
    data object Working : BackupUiState()
    data class Error(val message: String) : BackupUiState()
    data class Success(val message: String) : BackupUiState()
}

/**
 * 云备份二级页。标题与返回由 [MainScreen] 顶栏提供，
 * 右上角 COS 设置由顶栏齿轮通过 [openSettings] / [onOpenSettingsConsumed] 驱动。
 */
@Composable
fun CloudBackupScreen(
    viewModel: TransactionViewModel,
    openSettings: Boolean,
    onOpenSettingsConsumed: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cosConfig by viewModel.cosConfig.collectAsState()

    var backups by remember { mutableStateOf<List<CosObjectMeta>>(emptyList()) }
    var uiState by remember { mutableStateOf<BackupUiState>(BackupUiState.Idle) }
    var isLoadingList by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<CosObjectMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<CosObjectMeta?>(null) }
    var lastBackupInfo by remember { mutableStateOf<String?>(null) }

    fun refreshList() {
        if (!cosConfig.isConfigured) {
            backups = emptyList()
            return
        }
        isLoadingList = true
        uiState = BackupUiState.Idle
        scope.launch {
            try {
                backups = withContext(Dispatchers.IO) { CloudBackupManager.listBackups(cosConfig) }
                uiState = BackupUiState.Idle
            } catch (e: Exception) {
                uiState = BackupUiState.Error(e.message ?: "加载备份列表失败")
            } finally {
                isLoadingList = false
            }
        }
    }

    LaunchedEffect(cosConfig.isConfigured) {
        if (cosConfig.isConfigured) refreshList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
    ) {
            // 状态卡
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("存储状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (cosConfig.isConfigured) {
                            "腾讯云 COS · ${cosConfig.host}"
                        } else {
                            "未配置 COS（点右上角齿轮填写密钥与存储桶 URL）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (lastBackupInfo != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = lastBackupInfo!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (uiState is BackupUiState.Working) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("处理中…", style = MaterialTheme.typography.labelSmall)
                    }
                    when (val s = uiState) {
                        is BackupUiState.Error -> {
                            Spacer(Modifier.height(8.dp))
                            Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        is BackupUiState.Success -> {
                            Spacer(Modifier.height(8.dp))
                            Text(s.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                        else -> Unit
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!cosConfig.isConfigured) {
                                    uiState = BackupUiState.Error("请先点右上角齿轮配置 COS")
                                    return@Button
                                }
                                uiState = BackupUiState.Working
                                scope.launch {
                                    try {
                                        val meta = withContext(Dispatchers.IO) {
                                            CloudBackupManager.uploadBackup(context, cosConfig)
                                        }
                                        uiState = BackupUiState.Success("备份成功：${meta.key.substringAfterLast('/')}")
                                        lastBackupInfo = "上次备份：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())} · ${CloudBackupManager.formatSize(meta.size)}"
                                        refreshList()
                                    } catch (e: Exception) {
                                        uiState = BackupUiState.Error(e.message ?: "备份失败")
                                    }
                                }
                            },
                            enabled = uiState !is BackupUiState.Working
                        ) { Text("立即备份") }
                        OutlinedButton(
                            onClick = { refreshList() },
                            enabled = !isLoadingList && uiState !is BackupUiState.Working
                        ) {
                            if (isLoadingList) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("刷新列表")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("备份历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            if (!cosConfig.isConfigured) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("尚未配置对象存储", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "请点右上角设置，填写 SecretId / SecretKey / 存储桶 URL。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (backups.isEmpty() && !isLoadingList) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        "还没有云备份，点「立即备份」创建第一份。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(backups, key = { it.key }) { item ->
                            ListItem(
                                headlineContent = {
                                    Text(item.key.substringAfterLast('/'), maxLines = 1, fontSize = 14.sp)
                                },
                                supportingContent = {
                                    Text(
                                        "${CloudBackupManager.formatSize(item.size)} · ${item.lastModified.take(19).replace('T', ' ')}",
                                        fontSize = 12.sp
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { restoreTarget = item }) { Text("恢复") }
                                        IconButton(onClick = { deleteTarget = item }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "删除",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

    if (openSettings) {
        CosSettingsDialog(
            initial = cosConfig,
            onDismiss = { onOpenSettingsConsumed() },
            onSave = { config ->
                viewModel.setCosConfig(config)
                onOpenSettingsConsumed()
                uiState = BackupUiState.Success("COS 配置已保存")
            }
        )
    }

    restoreTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("恢复将覆盖当前账本") },
            text = {
                Text("将下载 ${target.key.substringAfterLast('/')} 并替换本地数据库。建议先确认云端备份正确。恢复后请强制退出并重新打开应用。")
            },
            confirmButton = {
                Button(onClick = {
                    restoreTarget = null
                    uiState = BackupUiState.Working
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                CloudBackupManager.downloadAndRestore(context, cosConfig, target.key)
                            }
                            uiState = BackupUiState.Success("恢复完成，请强制停止应用后重新打开")
                        } catch (e: Exception) {
                            uiState = BackupUiState.Error(e.message ?: "恢复失败")
                        }
                    }
                }) { Text("我明白，恢复") }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text("取消") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除云端备份") },
            text = { Text("确定删除 ${target.key.substringAfterLast('/')}？此操作不可撤销。") },
            confirmButton = {
                Button(onClick = {
                    val key = target.key
                    deleteTarget = null
                    uiState = BackupUiState.Working
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { CloudBackupManager.deleteBackup(cosConfig, key) }
                            uiState = BackupUiState.Success("已删除")
                            refreshList()
                        } catch (e: Exception) {
                            uiState = BackupUiState.Error(e.message ?: "删除失败")
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CosSettingsDialog(
    initial: CosConfig,
    onDismiss: () -> Unit,
    onSave: (CosConfig) -> Unit
) {
    var secretId by remember { mutableStateOf(initial.secretId) }
    var secretKey by remember { mutableStateOf(initial.secretKey) }
    var bucketUrl by remember { mutableStateOf(initial.bucketUrl) }
    var prefix by remember { mutableStateOf(initial.prefix) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("COS 设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "私有读写需要腾讯云 API 密钥。密钥仅保存在本机，请使用子账号并仅授权该存储桶。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = secretId,
                    onValueChange = { secretId = it },
                    label = { Text("SecretId") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secretKey,
                    onValueChange = { secretKey = it },
                    label = { Text("SecretKey") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bucketUrl,
                    onValueChange = { bucketUrl = it },
                    label = { Text("存储桶 URL") },
                    placeholder = { Text("https://xxx-1250000000.cos.ap-guangzhou.myqcloud.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "从 COS 控制台「存储桶 → 基础配置」复制默认访问域名即可，无需再单独填地域。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("对象前缀") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val rawUrl = bucketUrl.trim().let {
                    if (it.isBlank()) "" else if (it.startsWith("http")) it else "https://$it"
                }
                onSave(
                    CosConfig(
                        secretId = secretId.trim(),
                        secretKey = secretKey.trim(),
                        bucketUrl = rawUrl,
                        prefix = prefix.trim().ifBlank { "backups/v1" }
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
