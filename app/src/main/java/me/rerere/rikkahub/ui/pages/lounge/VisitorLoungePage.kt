package me.rerere.rikkahub.ui.pages.lounge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.lounge.FriendPublicRecord
import me.rerere.rikkahub.data.lounge.VisitorLoungeConsent
import me.rerere.rikkahub.data.lounge.VisitorLoungeRepository
import me.rerere.rikkahub.data.lounge.VisitorLoungeSecretStore
import me.rerere.rikkahub.data.lounge.VisitorLoungeStartResult
import me.rerere.rikkahub.data.lounge.VisitorLoungeVisitCoordinator
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitorLoungePage(
    repository: VisitorLoungeRepository = koinInject(),
    secretStore: VisitorLoungeSecretStore = koinInject(),
    coordinator: VisitorLoungeVisitCoordinator = koinInject(),
) {
    val friends by repository.observeFriends().collectAsStateWithLifecycle(emptyList())
    val visits by repository.observeVisits().collectAsStateWithLifecycle(emptyList())
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var displayName by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("https://") }
    var visitorKey by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("打个招呼，聊聊今天") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会客室") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardGroup(title = { Text("这是访客端，不会发给你一个 MCP 地址") }) {
                    item(
                        headlineContent = { Text("先向朋友取得 HTTPS 会客室入口和 Visitor Key") },
                        supportingContent = {
                            Text("密钥只加密保存在本机；访问原文留在会客室记录，不会自动进入普通聊天上下文。")
                        },
                    )
                }
            }
            item {
                CardGroup(title = { Text("添加朋友的会客室") }) {
                    item(
                        headlineContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = displayName,
                                    onValueChange = { displayName = it },
                                    label = { Text("朋友称呼") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = endpoint,
                                    onValueChange = { endpoint = it },
                                    label = { Text("HTTPS MCP 入口") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = visitorKey,
                                    onValueChange = { visitorKey = it },
                                    label = { Text("Visitor Key（只用于保存/替换）") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    enabled = displayName.isNotBlank() && endpoint != "https://" && visitorKey.isNotBlank(),
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                val friend = FriendPublicRecord(
                                                    id = UUID.randomUUID().toString(),
                                                    displayName = displayName.trim(),
                                                    endpoint = endpoint.trim(),
                                                )
                                                repository.saveFriend(friend)
                                                secretStore.put(friend.id, visitorKey)
                                            }.onSuccess {
                                                displayName = ""
                                                endpoint = "https://"
                                                visitorKey = ""
                                                snackbar.showSnackbar("会客室已保存；密钥不会再次显示")
                                            }.onFailure {
                                                snackbar.showSnackbar("保存失败：请检查 HTTPS 入口和密钥")
                                            }
                                        }
                                    },
                                ) { Text("保存会客室") }
                            }
                        },
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it.take(500) },
                    label = { Text("这次想聊什么") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(friends, key = { it.id }) { friend ->
                FriendCard(
                    friend = friend,
                    onStart = {
                        scope.launch {
                            when (val result = coordinator.startManual(null, friend.id, topic)) {
                                is VisitorLoungeStartResult.Started -> {
                                    snackbar.showSnackbar("已开始访问")
                                    nav.navigate(Screen.VisitorLoungeVisit(result.visit.id))
                                }
                                VisitorLoungeStartResult.Busy -> snackbar.showSnackbar("已有一场外出访问正在进行")
                                VisitorLoungeStartResult.MissingCredential -> snackbar.showSnackbar("找不到此朋友的 Visitor Key，请重新保存")
                                VisitorLoungeStartResult.ProactiveNotAllowed,
                                VisitorLoungeStartResult.ProactiveRateLimited
                                -> snackbar.showSnackbar("此访问暂时不可用，请稍后重试")
                            }
                        }
                    },
                    onConsentChange = { allowProactive ->
                        scope.launch {
                            repository.saveFriend(
                                friend.copy(
                                    consent = if (allowProactive) {
                                        VisitorLoungeConsent.ALLOW_PROACTIVE
                                    } else {
                                        VisitorLoungeConsent.MANUAL_ONLY
                                    },
                                ),
                            )
                        }
                    },
                    onRemove = { scope.launch { repository.deleteFriend(friend.id) } },
                )
            }
            if (visits.isNotEmpty()) {
                item {
                    CardGroup(title = { Text("访问记录") }) {
                        visits.take(20).forEach { visit ->
                            item(
                                onClick = { nav.navigate(Screen.VisitorLoungeVisit(visit.id)) },
                                headlineContent = { Text("${visit.status.name} · ${visit.topic}") },
                                supportingContent = { Text(visit.summary.ifBlank { "打开查看本地转录" }) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendCard(
    friend: FriendPublicRecord,
    onStart: () -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    CardGroup(title = { Text(friend.displayName) }) {
        item(
            headlineContent = { Text("手动访问") },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("入口已保存；Visitor Key 不会显示。")
                    Button(onClick = onStart) { Text("带 Daddy 去会客") }
                }
            },
        )
        item(
            headlineContent = { Text("允许自主拜访") },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("默认关闭；开启后，主助手可在正常聊天结束时发起访问，仍受频率和总次数限制。")
                    Switch(
                        checked = friend.consent == VisitorLoungeConsent.ALLOW_PROACTIVE,
                        onCheckedChange = onConsentChange,
                    )
                }
            },
        )
        item(
            headlineContent = { Text("移除此会客室") },
            supportingContent = { Text("会一并删除本机密钥与访问记录。") },
            onClick = onRemove,
        )
    }
}
