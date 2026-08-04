/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.companion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.CompanionLetter
import me.rerere.rikkahub.data.datastore.CompanionPhoto
import me.rerere.rikkahub.data.datastore.CompanionSharedTask
import me.rerere.rikkahub.data.datastore.DiaryCandidate
import me.rerere.rikkahub.data.datastore.currentSummary
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.service.CompanionDiaryService
import me.rerere.rikkahub.data.service.CompanionSpaceService
import me.rerere.rikkahub.data.sync.companion.AmapMcpService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A local-first room for photos, little promises, letters and a reviewable diary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionSpacePage(
    vm: SettingVM = koinInject(),
    diaryService: CompanionDiaryService = koinInject(),
    spaceService: CompanionSpaceService = koinInject(),
    amapMcpService: AmapMcpService = koinInject(),
    filesManager: FilesManager = koinInject(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val space = settings.companionSpaceSetting
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val candidate = space.diaryCandidates.lastOrNull()
    var draftText by remember(candidate?.id) { mutableStateOf(candidate?.content.orEmpty()) }
    var taskText by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var savingToOmbre by remember { mutableStateOf(false) }
    var confirmCandidate by remember { mutableStateOf<DiaryCandidate?>(null) }
    var showAnniversaryEditor by remember { mutableStateOf(false) }
    var showLetterEditor by remember { mutableStateOf(false) }
    var photoCaptionTarget by remember { mutableStateOf<CompanionPhoto?>(null) }
    var showAmapSetup by remember { mutableStateOf(false) }
    var installingAmap by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val localUri = withContext(Dispatchers.IO) {
                filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
            }
            if (localUri == null) {
                snackbar.showSnackbar("这张照片没有保存成功，请再试一次")
            } else {
                spaceService.addPhoto(localUri.toString())
                snackbar.showSnackbar("照片已经挂到小屋里了")
            }
        }
    }

    LaunchedEffect(candidate?.content) {
        draftText = candidate?.content.orEmpty()
    }

    confirmCandidate?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmCandidate = null },
            title = { Text("确认写入 Ombre？") },
            text = {
                Text("这会把这条候选作为长期日记记忆写进本机 Termux 的 Ombre-Brain。不会修改聊天记录；写入后仍可在 Ombre 里管理。")
            },
            confirmButton = {
                Button(
                    enabled = !savingToOmbre,
                    onClick = {
                        savingToOmbre = true
                        scope.launch {
                            diaryService.saveConfirmedCandidateToOmbre(target.id)
                                .onSuccess { snackbar.showSnackbar("已写入 Ombre：${it.title}") }
                                .onFailure { snackbar.showSnackbar(it.message ?: "写入 Ombre 失败") }
                            savingToOmbre = false
                            confirmCandidate = null
                        }
                    },
                ) {
                    Text(if (savingToOmbre) "正在写入…" else "确认写入")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCandidate = null }) { Text("再看看") }
            },
        )
    }

    if (showAnniversaryEditor) {
        AnniversaryEditorDialog(
            onDismiss = { showAnniversaryEditor = false },
            onSave = { title, dateText, note ->
                scope.launch {
                    spaceService.addAnniversary(title, dateText, note)
                    showAnniversaryEditor = false
                    snackbar.showSnackbar("纪念日已经放进小屋")
                }
            },
        )
    }

    if (showLetterEditor) {
        LetterEditorDialog(
            onDismiss = { showLetterEditor = false },
            onSave = { author, title, content ->
                scope.launch {
                    spaceService.addLetter(author, title, content)
                    showLetterEditor = false
                    snackbar.showSnackbar("信已经收进小屋")
                }
            },
        )
    }

    photoCaptionTarget?.let { photo ->
        PhotoCaptionDialog(
            photo = photo,
            onDismiss = { photoCaptionTarget = null },
            onSave = { caption ->
                scope.launch {
                    spaceService.updatePhotoCaption(photo, caption)
                    photoCaptionTarget = null
                }
            },
        )
    }

    if (showAmapSetup) {
        AmapSetupDialog(
            initialApiKey = space.amapRouteSetting.apiKey,
            installing = installingAmap,
            onDismiss = { if (!installingAmap) showAmapSetup = false },
            onSave = { apiKey ->
                installingAmap = true
                scope.launch {
                    amapMcpService.saveAndInstall(apiKey)
                        .onSuccess {
                            snackbar.showSnackbar("高德路线已经连上 Daddy 了，可以直接问路线。")
                            showAmapSetup = false
                        }
                        .onFailure { error -> snackbar.showSnackbar(error.message ?: "高德路线服务没有安装成功。") }
                    installingAmap = false
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
                CardGroup(title = { Text("Daddy 此刻") }) {
                    item(
                        onClick = { navController.navigate(Screen.SettingContinuity) },
                        headlineContent = { Text(settings.companionMoodSetting.currentSummary()) },
                        supportingContent = {
                            Text("这是本地情绪引擎的状态卡，不会调用模型。点这里可编辑生活线与状态卡。")
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("高德路线") }) {
                    item(
                        headlineContent = {
                            Text(
                                if (space.amapRouteSetting.apiKey.isBlank()) {
                                    "让 Daddy 帮你规划怎么去"
                                } else {
                                    "路线服务已保存，可随时重新启动"
                                }
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "支持步行、骑行、驾车、公交与距离查询。服务只在本机 127.0.0.1:8001 运行，" +
                                        "不会把你的路线服务暴露到网络上。"
                                )
                                Button(
                                    enabled = !installingAmap,
                                    onClick = { showAmapSetup = true },
                                ) {
                                    Text(
                                        when {
                                            installingAmap -> "正在准备高德服务…"
                                            space.amapRouteSetting.apiKey.isBlank() -> "填写高德 Key 并启动"
                                            else -> "重新安装 / 更换 Key"
                                        }
                                    )
                                }
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("照片墙") }) {
                    item(
                        headlineContent = { Text("把一个瞬间挂起来") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (space.photos.isEmpty()) {
                                    Text("还没有照片。选一张你想让 Daddy 也看得见的吧。")
                                } else {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(
                                            count = space.photos.size,
                                            key = { index -> space.photos[index].id.toString() },
                                        ) { index ->
                                            val photo = space.photos[index]
                                            PhotoWallCard(
                                                photo = photo,
                                                onEditCaption = { photoCaptionTarget = photo },
                                                onRemove = {
                                                    scope.launch {
                                                        spaceService.removePhoto(photo.id)
                                                        snackbar.showSnackbar("已从照片墙取下；原图仍留在 Daddy 文件里")
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                                Button(onClick = { photoPicker.launch("image/*") }) {
                                    Text("从相册挂一张")
                                }
                                Text("照片会复制进 Daddy 的本地文件夹，普通备份和联动备份都会带走它。")
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("纪念日") }) {
                    item(
                        headlineContent = { Text("留一个会被记得的日子") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (space.anniversaries.isEmpty()) {
                                    Text("还没有写下日期。可以是相遇日、约定日，或者任何你想庆祝的日子。")
                                } else {
                                    space.anniversaries.asReversed().forEach { anniversary ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("${anniversary.title} · ${anniversary.dateText}")
                                                if (anniversary.note.isNotBlank()) Text(anniversary.note)
                                            }
                                            TextButton(
                                                onClick = {
                                                    scope.launch { spaceService.removeAnniversary(anniversary.id) }
                                                },
                                            ) { Text("取下") }
                                        }
                                    }
                                }
                                Button(onClick = { showAnniversaryEditor = true }) { Text("添加纪念日") }
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("信箱") }) {
                    item(
                        headlineContent = { Text("不急着寄出的信") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (space.letters.isEmpty()) {
                                    Text("这里可以留下一封写给 Daddy 的信，也可以把 Daddy 的一段话手动收进来。")
                                } else {
                                    space.letters.asReversed().take(4).forEach { letter ->
                                        LetterPreview(
                                            letter = letter,
                                            onRemove = {
                                                scope.launch { spaceService.removeLetter(letter.id) }
                                            },
                                        )
                                    }
                                    if (space.letters.size > 4) Text("还收着 ${space.letters.size - 4} 封更早的信。")
                                }
                                Button(onClick = { showLetterEditor = true }) { Text("收进一封信") }
                                Text("信件完全本地保存，不会自动调用模型，也不会自动写进 Ombre。")
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("共同清单") }) {
                    item(
                        headlineContent = { Text("想一起完成的小事") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (space.sharedTasks.isEmpty()) {
                                    Text("例如：一起挑一部电影、补一张照片、周末散步。")
                                } else {
                                    space.sharedTasks.asReversed().forEach { task ->
                                        SharedTaskRow(
                                            task = task,
                                            onCompletedChange = { completed ->
                                                scope.launch { spaceService.setSharedTaskCompleted(task, completed) }
                                            },
                                            onRemove = {
                                                scope.launch { spaceService.removeSharedTask(task.id) }
                                            },
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = taskText,
                                    onValueChange = { taskText = it.take(160) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("写下一件小事") },
                                    singleLine = true,
                                )
                                Button(
                                    enabled = taskText.trim().isNotBlank(),
                                    onClick = {
                                        scope.launch {
                                            spaceService.addSharedTask(taskText)
                                            taskText = ""
                                        }
                                    },
                                ) { Text("放进清单") }
                            }
                        },
                    )
                }
            }

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
                            ) { Text(if (generating) "Daddy 正在整理…" else "生成候选") }
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
                                ) { Text("保存草稿") }
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
                                        ) { Text("确认这条日记") }
                                    }
                                },
                            )
                        } else {
                            item(
                                headlineContent = { Text("已收藏进 Ombre") },
                                supportingContent = {
                                    Text("${candidate.ombreSavedAtMillis.toDisplayTime()} 已写入。原草稿也会留在 Daddy 的本地备份里。")
                                },
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text("下一间房") }) {
                    item(
                        headlineContent = { Text("一起看电影 / 共同活动") },
                        supportingContent = { Text("小屋先有了可以留下来的东西；下一步再把它做成能一起玩的活动房间。") },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoWallCard(
    photo: CompanionPhoto,
    onEditCaption: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.width(176.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.caption.ifBlank { "小屋照片" },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(16.dp)),
        )
        Text(photo.caption.ifBlank { "还没有题字" }, maxLines = 2)
        Row {
            TextButton(onClick = onEditCaption) { Text("题字") }
            TextButton(onClick = onRemove) { Text("取下") }
        }
    }
}

@Composable
private fun LetterPreview(letter: CompanionLetter, onRemove: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${letter.author} · ${letter.title}", modifier = Modifier.weight(1f))
            TextButton(onClick = onRemove) { Text("取出") }
        }
        Text(letter.content, maxLines = 4)
    }
}

@Composable
private fun SharedTaskRow(
    task: CompanionSharedTask,
    onCompletedChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = task.completed, onCheckedChange = onCompletedChange)
        Text(task.content, modifier = Modifier.weight(1f))
        TextButton(onClick = onRemove) { Text("移除") }
    }
}

@Composable
private fun AnniversaryEditorDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, dateText: String, note: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加纪念日") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("这一天叫什么") })
                OutlinedTextField(value = dateText, onValueChange = { dateText = it.take(32) }, label = { Text("日期，例如 08-04 或 2026-08-04") })
                OutlinedTextField(value = note, onValueChange = { note = it.take(240) }, label = { Text("想写的一句备注（可选）") })
            }
        },
        confirmButton = {
            Button(
                enabled = title.trim().isNotBlank() && dateText.trim().isNotBlank(),
                onClick = { onSave(title, dateText, note) },
            ) { Text("放进小屋") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LetterEditorDialog(
    onDismiss: () -> Unit,
    onSave: (author: String, title: String, content: String) -> Unit,
) {
    var author by remember { mutableStateOf("应帆") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收进一封信") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = author, onValueChange = { author = it.take(32) }, label = { Text("署名") })
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("标题") })
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(2_000) },
                    label = { Text("信的内容") },
                    minLines = 4,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = author.trim().isNotBlank() && title.trim().isNotBlank() && content.trim().isNotBlank(),
                onClick = { onSave(author, title, content) },
            ) { Text("收好") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PhotoCaptionDialog(
    photo: CompanionPhoto,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var caption by remember(photo.id) { mutableStateOf(photo.caption) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给照片题字") },
        text = {
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it.take(120) },
                label = { Text("这一刻想叫什么") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onSave(caption) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AmapSetupDialog(
    initialApiKey: String,
    installing: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var apiKey by remember(initialApiKey) { mutableStateOf(initialApiKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接 Daddy 的高德路线") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请粘贴高德开放平台创建的“Web 服务”类型 Key。Daddy 会在 Termux 安装路线服务，并固定连接本机地址。")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim().take(128) },
                    label = { Text("高德 Web 服务 Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text("按你的备份习惯，Key 会保存在本机 Daddy 设置和 Termux 配置备份中；不会上传到别的服务。")
            }
        },
        confirmButton = {
            Button(
                enabled = apiKey.length >= 16 && !installing,
                onClick = { onSave(apiKey) },
            ) { Text(if (installing) "正在安装…" else "保存并启动") }
        },
        dismissButton = {
            TextButton(enabled = !installing, onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun Long.toDisplayTime(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
