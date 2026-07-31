/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun CompanionBackupTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit,
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()
    var ombreUrl by remember(settings.companionBackupConfig.ombreBaseUrl) {
        mutableStateOf(settings.companionBackupConfig.ombreBaseUrl)
    }
    var ombrePassword by remember { mutableStateOf("") }
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isExporting = true
            runCatching {
                val result = vm.exportCompanionBackup(ombreUrl, ombrePassword.toCharArray())
                try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(result.file).use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法写入选择的备份位置。")
                } finally {
                    result.file.delete()
                }
                toaster.show(
                    "联动备份完成：已包含橘瓣、Ombre、Termux 配置和 ${result.supabaseTableCount} 张 Supabase 表。",
                    type = ToastType.Success,
                )
            }.onFailure { error ->
                toaster.show("联动备份失败：${error.message.orEmpty()}", type = ToastType.Error)
            }
            isExporting = false
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            isRestoring = true
            val temporary = File(context.cacheDir, "companion_restore_${System.currentTimeMillis()}.zip")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取选择的备份文件。")
                vm.restoreCompanionBackup(temporary, ombrePassword.toCharArray())
            }.onSuccess { result ->
                val skipped = result.supabaseReport.skippedTables
                val suffix = if (skipped.isEmpty()) "" else " Supabase 跳过：${skipped.joinToString()}。"
                val mcpSuffix = result.externalMcpStatuses
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = "；", prefix = " 本机 MCP：") { status ->
                        "${status.name}${if (status.isReachable) "已连通" else "未就绪（打开/安装对应 APK 后重试）"}"
                    }
                    .orEmpty()
                toaster.show(
                    "联动恢复完成：恢复 ${result.supabaseReport.restoredRows} 条 Supabase 记录。$mcpSuffix$suffix",
                    type = ToastType.Success,
                )
                onShowRestartDialog()
            }.onFailure { error ->
                toaster.show("联动恢复失败：${error.message.orEmpty()}", type = ToastType.Error)
            }
            temporary.delete()
            isRestoring = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        stickyHeader {
            StickyHeader { Text("Ombre 与 Termux 联动备份") }
        }

        item {
            Text("密码仅在本次导出或恢复时用于登录 Ombre，不会保存到橘瓣。")
        }

        item {
            OutlinedTextField(
                value = ombreUrl,
                onValueChange = { ombreUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ombre 地址") },
                singleLine = true,
            )
        }

        item {
            OutlinedTextField(
                value = ombrePassword,
                onValueChange = { ombrePassword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ombre Dashboard 密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting && !isRestoring) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("orangechat_companion_$timestamp.zip")
                        }
                    } else {
                        null
                    },
                    headlineContent = { Text("导出联动备份包") },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                "正在备份 Ombre、Termux 和 Supabase…"
                            } else {
                                "包括橘瓣、Ombre、Termux Bridge、.env 与全部个人配置；请仅保存到可信位置。"
                            },
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )
                item(
                    onClick = if (!isExporting && !isRestoring) {
                        { openDocumentLauncher.launch(arrayOf("application/zip")) }
                    } else {
                        null
                    },
                    headlineContent = { Text("恢复联动备份包") },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                "正在验证并恢复全部数据…"
                            } else {
                                "会先校验文件完整性，再恢复；Supabase 只合并带稳定 id 的记录。"
                            },
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        item {
            Text(
                "会优先使用已安装的 termux-bridge（127.0.0.1:8080）。仅在它不可用时，才需要在 Termux 的 " +
                    "~/.termux/termux.properties 添加 allow-external-apps=true 并重启 Termux。" +
                    "此个人完整备份会包含 .env、Supabase/模型配置和 Bridge 脚本；不要分享、上传网盘或发送聊天软件。" +
                        "软链接仍会被拒绝，防止导入时写入到意外位置。",
            )
        }
    }
}
