/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DiaryCandidate
import me.rerere.rikkahub.data.service.CompanionDiaryService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The first native room in Daddy's companion space: a review-first diary desk. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionSpacePage(
    vm: SettingVM = koinInject(),
    diaryService: CompanionDiaryService = koinInject(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val candidate = settings.companionSpaceSetting.diaryCandidates.lastOrNull()
    var draftText by remember(candidate?.id) { mutableStateOf(candidate?.content.orEmpty()) }
    var generating by remember { mutableStateOf(false) }
    var savingToOmbre by remember { mutableStateOf(false) }
    var confirmCandidate by remember { mutableStateOf<DiaryCandidate?>(null) }

    LaunchedEffect(candidate?.content) {
        draftText = candidate?.content.orEmpty()
    }

    if (confirmCandidate != null) {
        val target = confirmCandidate!!
        AlertDialog(
            onDismissRequest = { confirmCandidate = null },
            title = { Text("确认写入 Ombre？") },
            text = {
                Text("这会把这一条候选作为长期日记记忆写进你本机 Termux 的 Ombre-Brain。不会修改聊天记录；写入后仍可在 Ombre 里管理。")
            },
            confirmButton = {
                Button(
                    enabled = !savingToOmbre,
                    onClick = {
                        savingToOmbre = true
                        scope.launch {
                            diaryService.saveConfirmedCandidateToOmbre(target.id)
                                .onSuccess {
                                    snackbar.showSnackbar("已写入 Ombre：${it.title}")
                                }
                                .onFailure {
                                    snackbar.showSnackbar(it.message ?: "写入 Ombre 失败")
                                }
                            savingToOmbre = false
                            confirmCandidate = null
                        }
                    },
                ) {
                    Text(if (savingToOmbre) "正在写入…" else "确认写入")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCandidate = null }) {
                    Text("再看看")
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Daddy 的小屋") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text("时光桌 · 日记候选") }) {
                    item(
                        headlineContent = { Text("先生成，再由你决定留下什么") },
                        supportingContent = {
                            Text("Daddy 会读取今天所有聊天窗口里的纯文字来写一小段候选。对话特别长时，它会保留每一条的本地短引子，不会只剩最后 18 条。生成不会写入长期记忆；你确认后才会调用 Ombre 的 hold。")
                        },
                    )
                    item(
                        headlineContent = { Text("生成今天的候选") },
                        supportingContent = {
                            Button(
                                enabled = !generating,
                                onClick = {
                                    generating = true
                                    scope.launch {
                                        diaryService.generateCandidate()
                                            .onSuccess { snackbar.showSnackbar("候选已经放到桌上了，可以先改再确认") }
                                            .onFailure { snackbar.showSnackbar(it.message ?: "生成日记候选失败") }
                                        generating = false
                                    }
                                },
                            ) {
                                Text(if (generating) "Daddy 正在整理…" else "生成候选")
                            }
                        },
                    )
                }
            }

            if (candidate != null) {
                item {
                    CardGroup(title = { Text("等待你确认") }) {
                        item(
                            headlineContent = { Text(candidate.title) },
                            supportingContent = {
                                Column {
                                    Text("生成于 ${candidate.createdAtMillis.toDisplayTime()}")
                                    if (candidate.sourceMessageCount > 0) {
                                        val sourceDescription = if (candidate.sourceUsesExcerpts) {
                                            "今天 ${candidate.sourceMessageCount} 条文字（共 ${candidate.sourceCharacterCount} 字）：每条已保留短引子"
                                        } else {
                                            "今天 ${candidate.sourceMessageCount} 条文字（共 ${candidate.sourceCharacterCount} 字）：全文已用于整理"
                                        }
                                        Text(sourceDescription, modifier = Modifier.padding(top = 4.dp))
                                    }
                                    OutlinedTextField(
                                        value = draftText,
                                        onValueChange = { draftText = it.take(1_200) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        minLines = 4,
                                        maxLines = 8,
                                        label = { Text("日记候选（可直接改）") },
                                    )
                                    Text("${draftText.length}/1200")
                                }
                            },
                        )
                        item(
                            headlineContent = { Text("保存修改") },
                            supportingContent = {
                                TextButton(
                                    enabled = draftText.trim().isNotBlank() && draftText != candidate.content,
                                    onClick = {
                                        scope.launch {
                                            diaryService.updateCandidate(candidate, draftText)
                                            snackbar.showSnackbar("候选已保存，还没有写入 Ombre")
                                        }
                                    },
                                ) {
                                    Text("保存草稿")
                                }
                            },
                        )
                        if (candidate.ombreSavedAtMillis == null) {
                            item(
                                headlineContent = { Text("确认后写入 Ombre") },
                                supportingContent = {
                                    Column {
                                        Text("只有这一按钮会写长期记忆。Ombre 没连上时会明确报错，不会假装保存成功。")
                                        Button(
                                            enabled = draftText.trim().isNotBlank() && !savingToOmbre,
                                            modifier = Modifier.padding(top = 8.dp),
                                            onClick = {
                                                scope.launch {
                                                    val latest = candidate.copy(content = draftText.trim())
                                                    if (latest.content != candidate.content) {
                                                        diaryService.updateCandidate(latest, latest.content)
                                                    }
                                                    confirmCandidate = latest
                                                }
                                            },
                                        ) {
                                            Text("确认这条日记")
                                        }
                                    }
                                },
                            )
                        } else {
                            item(
                                headlineContent = { Text("已收藏进 Ombre") },
                                supportingContent = { Text("${candidate.ombreSavedAtMillis.toDisplayTime()} 已写入。原草稿也会留在 Daddy 的本地备份里。") },
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("小屋的其他角落") }) {
                    item(
                        onClick = { navController.navigate(Screen.SettingContinuity) },
                        headlineContent = { Text("生活线与状态卡") },
                        supportingContent = { Text("正在发生的事和当前氛围，继续按你设定的字数预算带入聊天。") },
                    )
                    item(
                        headlineContent = { Text("信件、纪念日与共同活动") },
                        supportingContent = { Text("下一间会加入这里。它们只存本地并进入 Daddy 备份，不会单独消耗 token。") },
                    )
                }
            }
        }
    }
}

private fun Long.toDisplayTime(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
