package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.plus
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.files.ChatMediaCleanupMode
import me.rerere.rikkahub.data.files.ChatMediaTimeRange
import me.rerere.rikkahub.data.service.ChatMediaStorageEntry
import me.rerere.rikkahub.data.service.ChatMediaStorageService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

@Composable
fun SettingFilesPage(
    mediaStorage: ChatMediaStorageService = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var mode by remember { mutableStateOf(ChatMediaCleanupMode.SAFE) }
    var range by remember { mutableStateOf<ChatMediaTimeRange?>(null) }
    var items by remember { mutableStateOf<List<ChatMediaStorageEntry>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var showPermanentConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            items = mediaStorage.scan(mode)
            selectedIds = emptySet()
            loading = false
        }
    }
    LaunchedEffect(mode) { refresh() }
    val visibleItems = items.filter { range?.includes(it.createdAtMillis, System.currentTimeMillis()) ?: true }
    val selectedBytes = visibleItems.filter { it.id in selectedIds }.sumOf { it.sizeBytes }

    fun executeDelete() {
        scope.launch {
            val result = mediaStorage.delete(selectedIds, mode)
            toaster.show("已清理 ${result.deletedCount} 张图片，释放 ${formatBytes(result.deletedBytes)}" + if (result.failures > 0) "；${result.failures} 项未成功" else "")
            showPermanentConfirm = false
            refresh()
        }
    }

    if (showPermanentConfirm) {
        AlertDialog(
            onDismissRequest = { showPermanentConfirm = false },
            title = { Text("确认彻底删除聊天图片？") },
            text = { Text("将删除 ${selectedIds.size} 张原图，预计释放 ${formatBytes(selectedBytes)}。聊天文字会保留，但原图会显示“图片已清理”，无法恢复。") },
            confirmButton = { Button(onClick = ::executeDelete) { Text("彻底删除") } },
            dismissButton = { TextButton(onClick = { showPermanentConfirm = false }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = { LargeFlexibleTopAppBar(title = { Text("聊天记录存储") }, navigationIcon = { BackButton() }, scrollBehavior = scrollBehavior) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(), contentPadding = padding + PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("只管理聊天消息使用的本地图片；头像、背景、小屋照片、插件与共读素材不会出现在这里。", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("彻底删除聊天图片")
                        Text(if (mode == ChatMediaCleanupMode.SAFE) "当前为安全清理：只显示聊天不再引用的孤儿图片。" else "危险模式：会把聊天里的图片替换成“图片已清理”。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = mode == ChatMediaCleanupMode.PERMANENT, onCheckedChange = { mode = if (it) ChatMediaCleanupMode.PERMANENT else ChatMediaCleanupMode.SAFE })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        selectedIds = if (selectedIds.containsAll(visibleItems.map { it.id })) emptySet() else visibleItems.map { it.id }.toSet()
                    }) { Text(if (visibleItems.isNotEmpty() && selectedIds.containsAll(visibleItems.map { it.id })) "取消全选" else "全选文件") }
                    OutlinedButton(onClick = ::refresh) { Text("刷新") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = range == null, onClick = { range = null }, label = { Text("全部") })
                    ChatMediaTimeRange.entries.forEach { option ->
                        FilterChip(selected = range == option, onClick = { range = option }, label = { Text(option.label) })
                    }
                }
            }
            item { Text("已选 ${selectedIds.size} 项 · ${formatBytes(selectedBytes)}", style = MaterialTheme.typography.bodyMedium) }
            if (loading) {
                item { Text("正在核对聊天引用…") }
            } else if (visibleItems.isEmpty()) {
                item { Text(if (mode == ChatMediaCleanupMode.SAFE) "没有可安全清理的聊天图片。" else "没有可彻底删除的聊天图片。") }
            } else {
                items(visibleItems, key = { it.id }) { entry ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = entry.id in selectedIds, onCheckedChange = { checked -> selectedIds = if (checked) selectedIds + entry.id else selectedIds - entry.id })
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(entry.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${formatBytes(entry.sizeBytes)} · ${formatDate(entry.createdAtMillis)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(), enabled = selectedIds.isNotEmpty() && !loading,
                    onClick = { if (mode == ChatMediaCleanupMode.PERMANENT) showPermanentConfirm = true else executeDelete() },
                ) { Text(if (mode == ChatMediaCleanupMode.SAFE) "清理可安全删除的缓存" else "彻底删除选中聊天图片") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
    else -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
}

private fun formatDate(value: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(value))
