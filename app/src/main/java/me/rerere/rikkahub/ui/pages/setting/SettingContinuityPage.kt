/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.CompanionContinuityProfile
import me.rerere.rikkahub.data.datastore.DEFAULT_LIFE_LINE_MAX_ENTRIES
import me.rerere.rikkahub.data.datastore.DEFAULT_MAX_PROMPT_CHARACTERS
import me.rerere.rikkahub.data.datastore.LifeLineEntry
import me.rerere.rikkahub.data.datastore.MAX_LIFE_LINE_ENTRIES
import me.rerere.rikkahub.data.datastore.MAX_LIFE_LINE_ENTRY_CHARACTERS
import me.rerere.rikkahub.data.datastore.MAX_PROMPT_CHARACTERS
import me.rerere.rikkahub.data.datastore.MAX_STATE_CARD_CHARACTERS
import me.rerere.rikkahub.data.datastore.MIN_PROMPT_CHARACTERS
import me.rerere.rikkahub.data.datastore.continuityProfileFor
import me.rerere.rikkahub.data.datastore.withContinuityProfile
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingContinuityPage(vm: SettingVM = koinInject()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val assistant = settings.assistants.firstOrNull { it.id == settings.assistantId }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("生活线与状态卡") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        if (assistant == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("还没有可用的助手") },
                            supportingContent = { Text("先创建或选择一个助手，再回来设置它的生活线。") },
                        )
                    }
                }
            }
            return@Scaffold
        }

        val profile = settings.continuityProfileFor(assistant.id)
        var newLifeLine by remember(assistant.id) { mutableStateOf("") }
        val updateProfile: (CompanionContinuityProfile) -> Unit = { updated ->
            vm.updateSettings(settings.withContinuityProfile(updated))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("${assistant.name.ifBlank { "当前助手" }} 的连续生活档案") },
                        supportingContent = {
                            Text("生活线记录最近几天正在发生的事；状态卡记录当前这段关系或对话的轻量背景。两者均为本机可见、可编辑内容。")
                        },
                        trailingContent = {
                            Switch(
                                checked = profile.enabled,
                                onCheckedChange = { enabled ->
                                    updateProfile(profile.copy(enabled = enabled))
                                }
                            )
                        }
                    )

                    if (profile.enabled) {
                        item(
                            headlineContent = { Text("当前状态卡") },
                            supportingContent = {
                                Column {
                                    Text("例如：最近在准备考试；这两天想被温柔陪着；上次聊到周末的电影。它不是长期记忆。")
                                    OutlinedTextField(
                                        value = profile.stateCard,
                                        onValueChange = { value ->
                                            updateProfile(
                                                profile.copy(
                                                    stateCard = value.take(MAX_STATE_CARD_CHARACTERS)
                                                )
                                            )
                                        },
                                        placeholder = { Text("写一段 Daddy 应该接得住的当前背景") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        minLines = 3,
                                        maxLines = 5,
                                    )
                                    Text("${profile.stateCard.length}/$MAX_STATE_CARD_CHARACTERS 字")
                                }
                            }
                        )

                        item(
                            headlineContent = { Text("每次带入的生活线条数") },
                            supportingContent = {
                                OutlinedTextField(
                                    value = profile.lifeLineMaxEntries.toString(),
                                    onValueChange = { value ->
                                        value.toIntOrNull()?.let { count ->
                                            if (count in 1..MAX_LIFE_LINE_ENTRIES) {
                                                updateProfile(profile.copy(lifeLineMaxEntries = count))
                                            }
                                        }
                                    },
                                    placeholder = { Text(DEFAULT_LIFE_LINE_MAX_ENTRIES.toString()) },
                                    supportingText = { Text("只带最新的 1-$MAX_LIFE_LINE_ENTRIES 条；其余仍保留在本机。") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                )
                            }
                        )

                        item(
                            headlineContent = { Text("注入字数上限") },
                            supportingContent = {
                                OutlinedTextField(
                                    value = profile.maxPromptCharacters.toString(),
                                    onValueChange = { value ->
                                        value.toIntOrNull()?.let { count ->
                                            if (count in MIN_PROMPT_CHARACTERS..MAX_PROMPT_CHARACTERS) {
                                                updateProfile(profile.copy(maxPromptCharacters = count))
                                            }
                                        }
                                    },
                                    placeholder = { Text(DEFAULT_MAX_PROMPT_CHARACTERS.toString()) },
                                    supportingText = {
                                        Text("这是整张生活档案的字数上限，不是严格 token 数；600 字通常已经很够用。")
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                )
                            }
                        )
                    }
                }
            }

            if (profile.enabled) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text("新增生活线") },
                            supportingContent = {
                                Column {
                                    Text("只写值得在未来几天自然接续的近况；长期偏好、承诺和重要事件仍交给 Ombre。")
                                    OutlinedTextField(
                                        value = newLifeLine,
                                        onValueChange = { newLifeLine = it.take(MAX_LIFE_LINE_ENTRY_CHARACTERS) },
                                        placeholder = { Text("例如：下周要准备一场考试，最近有点紧张") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        minLines = 2,
                                        maxLines = 4,
                                    )
                                    TextButton(
                                        onClick = {
                                            val content = newLifeLine.trim()
                                            if (content.isNotBlank()) {
                                                updateProfile(
                                                    profile.copy(
                                                        lifeLine = profile.lifeLine + LifeLineEntry(content = content)
                                                    )
                                                )
                                                newLifeLine = ""
                                            }
                                        },
                                        enabled = newLifeLine.isNotBlank(),
                                    ) {
                                        Text("加入生活线")
                                    }
                                }
                            }
                        )

                        profile.lifeLine.asReversed().forEachIndexed { index, entry ->
                            item(
                                headlineContent = { Text("生活线 ${profile.lifeLine.size - index}") },
                                supportingContent = {
                                    OutlinedTextField(
                                        value = entry.content,
                                        onValueChange = { value ->
                                            updateProfile(
                                                profile.copy(
                                                    lifeLine = profile.lifeLine.map {
                                                        if (it.id == entry.id) {
                                                            it.copy(content = value.take(MAX_LIFE_LINE_ENTRY_CHARACTERS))
                                                        } else {
                                                            it
                                                        }
                                                    }
                                                )
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        minLines = 2,
                                        maxLines = 4,
                                    )
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            updateProfile(
                                                profile.copy(
                                                    lifeLine = profile.lifeLine.filterNot { it.id == entry.id }
                                                )
                                            )
                                        }
                                    ) {
                                        Text("删除")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
