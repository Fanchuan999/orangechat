/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.companion

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.CompanionLetter
import me.rerere.rikkahub.data.datastore.CompanionPhoto
import me.rerere.rikkahub.data.datastore.CompanionSharedTask
import me.rerere.rikkahub.data.datastore.CompanionCourse
import me.rerere.rikkahub.data.datastore.CompanionDeadline
import me.rerere.rikkahub.data.datastore.DeadlineStatus
import me.rerere.rikkahub.data.datastore.DeadlineStep
import me.rerere.rikkahub.data.datastore.DiaryCandidate
import me.rerere.rikkahub.data.datastore.currentSummary
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.service.CompanionDiaryService
import me.rerere.rikkahub.data.service.CompanionCourseImporter
import me.rerere.rikkahub.data.service.CompanionCourseImportResult
import me.rerere.rikkahub.data.service.CompanionSpaceService
import me.rerere.rikkahub.data.service.DeadlineReminderScheduler
import me.rerere.rikkahub.data.service.TodoistDeadlineDrafts
import me.rerere.rikkahub.data.service.TodoistDeadlineSyncService
import me.rerere.rikkahub.data.service.TodoistSyncResult
import me.rerere.rikkahub.data.service.TodoistWriteErrorCategory
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.widget.DaddyWidgetProvider
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale

/** A local-first room for photos, little promises, letters and a reviewable diary. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionSpacePage(
    vm: SettingVM = koinInject(),
    diaryService: CompanionDiaryService = koinInject(),
    spaceService: CompanionSpaceService = koinInject(),
    filesManager: FilesManager = koinInject(),
    todoistSyncService: TodoistDeadlineSyncService = koinInject(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val space = settings.companionSpaceSetting
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val candidate = space.diaryCandidates.lastOrNull()
    var draftText by remember(candidate?.id) { mutableStateOf(candidate?.content.orEmpty()) }
    var widgetShortLine by remember(space.widgetSetting.shortLine) { mutableStateOf(space.widgetSetting.shortLine) }
    var taskText by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var savingToOmbre by remember { mutableStateOf(false) }
    var confirmCandidate by remember { mutableStateOf<DiaryCandidate?>(null) }
    var showAnniversaryEditor by remember { mutableStateOf(false) }
    var showLetterEditor by remember { mutableStateOf(false) }
    var photoCaptionTarget by remember { mutableStateOf<CompanionPhoto?>(null) }
    var showCourseEditor by remember { mutableStateOf(false) }
    var courseEditorTarget by remember { mutableStateOf<CompanionCourse?>(null) }
    var courseImportPreview by remember { mutableStateOf<CompanionCourseImportResult?>(null) }
    var showDeadlineEditor by remember { mutableStateOf(false) }
    var selectedDeadlineForSteps by remember { mutableStateOf<CompanionDeadline?>(null) }
    var selectedDeadlineForTodoist by remember { mutableStateOf<CompanionDeadline?>(null) }
    var deadlineTypeFilter by rememberSaveable { mutableStateOf(DEADLINE_FILTER_ALL) }
    var coursesExpanded by rememberSaveable { mutableStateOf(false) }
    var deadlinesExpanded by rememberSaveable { mutableStateOf(false) }
    var widgetExpanded by rememberSaveable { mutableStateOf(false) }
    var anniversariesExpanded by rememberSaveable { mutableStateOf(false) }
    var lettersExpanded by rememberSaveable { mutableStateOf(false) }
    var sharedTasksExpanded by rememberSaveable { mutableStateOf(false) }
    var diaryExpanded by rememberSaveable { mutableStateOf(false) }
    var candidateExpanded by rememberSaveable(candidate?.id) {
        mutableStateOf(candidate?.let { it.ombreSavedAtMillis == null } == true)
    }

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

    val widgetBackgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val localUri = withContext(Dispatchers.IO) {
                filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
            }
            if (localUri == null) {
                snackbar.showSnackbar("小组件背景没有保存成功，请再试一次")
            } else {
                vm.updateSettings(
                    settings.copy(
                        companionSpaceSetting = space.copy(
                            widgetSetting = space.widgetSetting.copy(backgroundImageUri = localUri.toString())
                        )
                    )
                )
                delay(300)
                DaddyWidgetProvider.refreshAll(context)
                snackbar.showSnackbar("小组件背景已经换好了")
            }
        }
    }

    val courseImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { CompanionCourseImporter.parse(it.readText()) }
            }
            if (imported == null || imported.courses.isEmpty()) {
                snackbar.showSnackbar("没有识别到课程；请导入 CSV 或日历 .ics 文件")
            } else {
                courseImportPreview = imported
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

    if (showCourseEditor) {
        CourseEditorDialog(
            course = courseEditorTarget,
            onDismiss = {
                showCourseEditor = false
                courseEditorTarget = null
            },
            onSave = { course ->
                scope.launch {
                    if (courseEditorTarget == null) {
                        spaceService.addCourse(course)
                    } else {
                        spaceService.updateCourse(course)
                    }
                    showCourseEditor = false
                    courseEditorTarget = null
                    snackbar.showSnackbar("课程表已保存")
                }
            },
        )
    }

    courseImportPreview?.let { preview ->
        CourseImportPreviewDialog(
            preview = preview,
            onDismiss = { courseImportPreview = null },
            onConfirm = {
                scope.launch {
                    val count = spaceService.importCourses(preview.courses)
                    courseImportPreview = null
                    snackbar.showSnackbar("已导入 $count 门课程；已有课程保持原样，可继续编辑")
                }
            },
        )
    }

    if (showDeadlineEditor) {
        DeadlineEditorDialog(
            onDismiss = { showDeadlineEditor = false },
            onSave = { deadline ->
                scope.launch {
                    spaceService.addDeadline(deadline)
                    showDeadlineEditor = false
                    DeadlineReminderScheduler.sync(context, space.copy(deadlines = space.deadlines + deadline))
                    snackbar.showSnackbar("截止日期已经记下了")
                }
            },
        )
    }

    selectedDeadlineForSteps?.let { deadline ->
        DeadlineStepsDialog(
            deadline = deadline,
            onDismiss = { selectedDeadlineForSteps = null },
            onSave = { steps ->
                scope.launch {
                    spaceService.updateDeadlineSteps(deadline, steps)
                    selectedDeadlineForSteps = null
                    snackbar.showSnackbar("拆解草案已保存，旧的 Todoist 确认已失效")
                }
            },
        )
    }

    selectedDeadlineForTodoist?.let { deadline ->
        val draft = TodoistDeadlineDrafts.create(deadline)
        TodoistDraftConfirmationDialog(
            deadline = deadline,
            draft = draft,
            onDismiss = { selectedDeadlineForTodoist = null },
            onConfirm = {
                scope.launch {
                    spaceService.confirmTodoistDraft(deadline)
                    when (val result = todoistSyncService.sync(deadline.id, draft.revision, draft.previewHash)) {
                        is TodoistSyncResult.Synced -> snackbar.showSnackbar("Todoist 已同步：${result.taskId}")
                        is TodoistSyncResult.Failed -> {
                            val message = when (result.category) {
                                TodoistWriteErrorCategory.TOOL_SCHEMA_UNVERIFIED ->
                                    "Todoist 工具尚未通过写入安全校验，草案仅保留在本地，未同步"
                                TodoistWriteErrorCategory.NOT_CONNECTED ->
                                    "Todoist 当前没有连接；请在 MCP 设置中重新连接后再试"
                                TodoistWriteErrorCategory.REMOTE_REJECTED ->
                                    "Todoist 没有接收这条任务，草案未同步；可在同一版本上再试"
                                TodoistWriteErrorCategory.RESULT_UNVERIFIABLE ->
                                    "Todoist 已响应但未返回任务编号；为防重复已暂停重试，请先去 Todoist 核对"
                                else -> "Todoist 写入失败，可在同一草案版本上再次确认"
                            }
                            snackbar.showSnackbar(message)
                        }
                        TodoistSyncResult.NotConfirmed -> snackbar.showSnackbar("确认版本已变化，请重新生成草案")
                        TodoistSyncResult.StaleDraft -> snackbar.showSnackbar("草案已变化，请重新打开预览")
                    }
                    selectedDeadlineForTodoist = null
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
                CompanionCollapsibleSection(
                    title = "桌面小组件",
                    summary = if (space.widgetSetting.enabled) "正在显示 Daddy 的状态卡" else "把 Daddy 放到手机桌面",
                    expanded = widgetExpanded,
                    onExpandedChange = { widgetExpanded = it },
                ) {
                    Text("把 Daddy 放到手机桌面", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("显示状态卡")
                            Text("关闭后，小组件仍在桌面，但只显示待机文案。")
                        }
                        Switch(
                            checked = space.widgetSetting.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    vm.updateSettings(
                                        settings.copy(
                                            companionSpaceSetting = space.copy(
                                                widgetSetting = space.widgetSetting.copy(enabled = enabled)
                                            )
                                        )
                                    )
                                    delay(300)
                                    DaddyWidgetProvider.refreshAll(context)
                                }
                            },
                        )
                    }
                    OutlinedTextField(
                        value = widgetShortLine,
                        onValueChange = { widgetShortLine = it.take(48) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("桌面短句") },
                        placeholder = { Text("今天也在你身边。") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = widgetShortLine != space.widgetSetting.shortLine,
                            onClick = {
                                scope.launch {
                                    vm.updateSettings(
                                        settings.copy(
                                            companionSpaceSetting = space.copy(
                                                widgetSetting = space.widgetSetting.copy(
                                                    shortLine = widgetShortLine.trim()
                                                )
                                            )
                                        )
                                    )
                                    delay(300)
                                    DaddyWidgetProvider.refreshAll(context)
                                }
                            },
                        ) { Text("保存短句") }
                        TextButton(onClick = { widgetBackgroundPicker.launch("image/*") }) {
                            Text("换背景图")
                        }
                    }
                    if (space.widgetSetting.backgroundImageUri.isNotBlank()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    vm.updateSettings(
                                        settings.copy(
                                            companionSpaceSetting = space.copy(
                                                widgetSetting = space.widgetSetting.copy(backgroundImageUri = "")
                                            )
                                        )
                                    )
                                    delay(300)
                                    DaddyWidgetProvider.refreshAll(context)
                                }
                            },
                        ) { Text("清除小组件背景图") }
                    }
                    Text("桌面可添加 2×2、2×4、4×4 三种尺寸；刷新时只读本地状态，不会调用模型。")
                }
            }

            item {
                CompanionCollapsibleSection(
                    title = "课程表",
                    summary = if (space.courses.isEmpty()) "还没有课程安排" else "已记录 ${space.courses.size} 门课程",
                    expanded = coursesExpanded,
                    onExpandedChange = { coursesExpanded = it },
                ) {
                    Text("按星期和时间记下固定课程", style = MaterialTheme.typography.titleMedium)
                    if (space.courses.isEmpty()) {
                        Text("可导入学校导出的 CSV 或日历 .ics，课程只保存在本机。")
                    } else {
                        space.courses.forEach { course ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${weekdayLabel(course.weekday)} ${course.name}")
                                    Text("${course.startMinutes.toClockText()}–${course.endMinutes.toClockText()}${course.location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}")
                                    if (course.teacher.isNotBlank()) Text(course.teacher)
                                }
                                TextButton(onClick = {
                                    courseEditorTarget = course
                                    showCourseEditor = true
                                }) { Text("编辑") }
                                TextButton(onClick = { scope.launch { spaceService.removeCourse(course.id) } }) { Text("移除") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { courseImportPicker.launch(arrayOf("text/*", "text/calendar", "application/octet-stream")) }) {
                            Text("一键导入课程表")
                        }
                        TextButton(onClick = {
                            courseEditorTarget = null
                            showCourseEditor = true
                        }) { Text("手动添加") }
                    }
                }
            }

            item {
                CompanionCollapsibleSection(
                    title = "截止日期与提醒",
                    summary = if (space.deadlines.isEmpty()) "还没有截止事项" else "${space.deadlines.count { it.status != DeadlineStatus.DONE && it.status != DeadlineStatus.CANCELLED }} 件未完成",
                    expanded = deadlinesExpanded,
                    onExpandedChange = { deadlinesExpanded = it },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("7 / 3 / 1 天提醒")
                            Text("提醒使用本机 WorkManager，不会新增系统闹钟。")
                        }
                        Switch(
                            checked = space.deadlineRemindersEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    vm.updateSettings(settings.copy(companionSpaceSetting = space.copy(deadlineRemindersEnabled = enabled)))
                                    DeadlineReminderScheduler.sync(context, space.copy(deadlineRemindersEnabled = enabled))
                                }
                            },
                        )
                    }
                    val deadlineTypes = listOf(DEADLINE_FILTER_ALL) +
                        (DEFAULT_DEADLINE_TYPES + space.deadlines.map { it.type.trim() }).filter { it.isNotBlank() }.distinct()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(deadlineTypes, key = { it }) { type ->
                            FilterChip(
                                selected = deadlineTypeFilter == type,
                                onClick = { deadlineTypeFilter = type },
                                label = { Text(type) },
                            )
                        }
                    }
                    val visibleDeadlines = space.deadlines.filter { deadline ->
                        deadlineTypeFilter == DEADLINE_FILTER_ALL || deadline.type.trim() == deadlineTypeFilter
                    }
                    if (visibleDeadlines.isEmpty()) {
                        Text("可以先记录作业、考试或申请材料，再补充拆解步骤。")
                    } else {
                        visibleDeadlines.forEach { deadline ->
                            DeadlineRow(
                                deadline = deadline,
                                onRemove = {
                                    scope.launch {
                                        spaceService.removeDeadline(deadline.id)
                                        DeadlineReminderScheduler.sync(context, space.copy(deadlines = space.deadlines.filterNot { it.id == deadline.id }))
                                    }
                                },
                                onSteps = { selectedDeadlineForSteps = deadline },
                                onTodoist = { selectedDeadlineForTodoist = deadline },
                                onReminderChange = { offset ->
                                    val disabled = if (offset in deadline.disabledReminderOffsets) deadline.disabledReminderOffsets - offset else deadline.disabledReminderOffsets + offset
                                    scope.launch {
                                        spaceService.updateDeadline(deadline.copy(disabledReminderOffsets = disabled))
                                        DeadlineReminderScheduler.sync(context, space.copy(deadlines = space.deadlines.map { if (it.id == deadline.id) deadline.copy(disabledReminderOffsets = disabled) else it }))
                                    }
                                },
                            )
                        }
                    }
                    Button(onClick = { showDeadlineEditor = true }) { Text("添加截止事项") }
                }
            }

            item {
                PhotoWallSection(
                    photos = space.photos,
                    onAddPhoto = { photoPicker.launch("image/*") },
                    onEditCaption = { photoCaptionTarget = it },
                    onRemovePhoto = { photo ->
                        scope.launch {
                            spaceService.removePhoto(photo.id)
                            snackbar.showSnackbar("已从照片墙取下；原图仍留在 Daddy 文件里")
                        }
                    },
                )
            }

            item {
                CompanionCollapsibleSection(
                    title = "纪念日",
                    summary = if (space.anniversaries.isEmpty()) "还没有写下日期" else "已收着 ${space.anniversaries.size} 个日子",
                    expanded = anniversariesExpanded,
                    onExpandedChange = { anniversariesExpanded = it },
                ) {
                    Text("留一个会被记得的日子", style = MaterialTheme.typography.titleMedium)
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
            }

            item {
                CompanionCollapsibleSection(
                    title = "信箱",
                    summary = if (space.letters.isEmpty()) "还没有收进信件" else "已收着 ${space.letters.size} 封信",
                    expanded = lettersExpanded,
                    onExpandedChange = { lettersExpanded = it },
                ) {
                    Text("不急着寄出的信", style = MaterialTheme.typography.titleMedium)
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
            }

            item {
                CompanionCollapsibleSection(
                    title = "共同清单",
                    summary = if (space.sharedTasks.isEmpty()) "还没有写下共同的小事" else "${space.sharedTasks.count { !it.completed }} 件正在一起完成",
                    expanded = sharedTasksExpanded,
                    onExpandedChange = { sharedTasksExpanded = it },
                ) {
                    Text("想一起完成的小事", style = MaterialTheme.typography.titleMedium)
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
            }

            item {
                CompanionCollapsibleSection(
                    title = "时光桌 · 日记候选",
                    summary = if (candidate == null) "先生成，再由你决定留下什么" else "有一条日记正等你确认",
                    expanded = diaryExpanded,
                    onExpandedChange = { diaryExpanded = it },
                ) {
                    Text("先生成，再由你决定留下什么", style = MaterialTheme.typography.titleMedium)
                    Text("Daddy 会读取今天所有聊天窗口里的纯文字来写一小段候选。对话特别长时，它会保留每一条的本地短引子，不会只剩最后 18 条。生成不会写入长期记忆；你确认后才会调用 Ombre 的 hold。")
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
                }
            }

            if (candidate != null) {
                item {
                    CompanionCollapsibleSection(
                        title = "等待你确认",
                        summary = candidate.title,
                        expanded = candidateExpanded,
                        onExpandedChange = { candidateExpanded = it },
                    ) {
                        Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                        Text("生成于 ${candidate.createdAtMillis.toDisplayTime()}")
                        if (candidate.sourceMessageCount > 0) {
                            val sourceDescription = if (candidate.sourceUsesExcerpts) {
                                "今天 ${candidate.sourceMessageCount} 条文字（共 ${candidate.sourceCharacterCount} 字）：每条已保留短引子"
                            } else {
                                "今天 ${candidate.sourceMessageCount} 条文字（共 ${candidate.sourceCharacterCount} 字）：全文已用于整理"
                            }
                            Text(sourceDescription)
                        }
                        OutlinedTextField(
                            value = draftText,
                            onValueChange = { draftText = it.take(1_200) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            maxLines = 8,
                            label = { Text("日记候选（可直接改）") },
                        )
                        Text("${draftText.length}/1200")
                        TextButton(
                            enabled = draftText.trim().isNotBlank() && draftText != candidate.content,
                            onClick = {
                                scope.launch {
                                    diaryService.updateCandidate(candidate, draftText)
                                    snackbar.showSnackbar("候选已保存，还没有写入 Ombre")
                                }
                            },
                        ) { Text("保存草稿") }
                        if (candidate.ombreSavedAtMillis == null) {
                            Text("只有这一按钮会写长期记忆。Ombre 没连上时会明确报错，不会假装保存成功。")
                            Button(
                                enabled = draftText.trim().isNotBlank() && !savingToOmbre,
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
                        } else {
                            Text("${candidate.ombreSavedAtMillis.toDisplayTime()} 已写入。原草稿也会留在 Daddy 的本地备份里。")
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

private fun Long.toDisplayTime(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))

@Composable
private fun CourseEditorDialog(
    course: CompanionCourse?,
    onDismiss: () -> Unit,
    onSave: (CompanionCourse) -> Unit,
) {
    var name by remember(course?.id) { mutableStateOf(course?.name.orEmpty()) }
    var teacher by remember(course?.id) { mutableStateOf(course?.teacher.orEmpty()) }
    var location by remember(course?.id) { mutableStateOf(course?.location.orEmpty()) }
    var weekday by remember(course?.id) { mutableStateOf(course?.weekday?.toString() ?: "1") }
    var start by remember(course?.id) { mutableStateOf(course?.startMinutes?.toClockText() ?: "09:00") }
    var end by remember(course?.id) { mutableStateOf(course?.endMinutes?.toClockText() ?: "10:00") }
    val parsed = parseCourseTime(start)?.let { a -> parseCourseTime(end)?.let { b -> a to b } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (course == null) "添加课程" else "编辑课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("课程名称") })
                OutlinedTextField(value = weekday, onValueChange = { weekday = it.filter(Char::isDigit).take(1) }, label = { Text("星期（1=周一，7=周日）") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = start, onValueChange = { start = it.take(5) }, label = { Text("开始 HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = end, onValueChange = { end = it.take(5) }, label = { Text("结束 HH:mm") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(value = teacher, onValueChange = { teacher = it.take(48) }, label = { Text("老师（可选）") })
                OutlinedTextField(value = location, onValueChange = { location = it.take(80) }, label = { Text("地点（可选）") })
            }
        },
        confirmButton = {
            Button(enabled = name.trim().isNotBlank() && weekday.toIntOrNull() in 1..7 && parsed != null && parsed.first < parsed.second, onClick = {
                val (from, to) = parsed!!
                onSave(
                    (course ?: CompanionCourse(
                        name = name,
                        weekday = weekday.toInt(),
                        startMinutes = from,
                        endMinutes = to,
                    )).copy(
                        name = name,
                        teacher = teacher,
                        location = location,
                        weekday = weekday.toInt(),
                        startMinutes = from,
                        endMinutes = to,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CourseImportPreviewDialog(
    preview: CompanionCourseImportResult,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入 ${preview.courses.size} 门课程？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("现有同一课程时段会保留你的手动修改，不会重复覆盖。")
                preview.courses.take(8).forEach { course ->
                    Text("${weekdayLabel(course.weekday)} ${course.startMinutes.toClockText()} ${course.name}")
                }
                if (preview.courses.size > 8) Text("还有 ${preview.courses.size - 8} 门课程")
                if (preview.skippedRows > 0) Text("已跳过 ${preview.skippedRows} 行无法识别的内容")
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("确认导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeadlineEditorDialog(
    onDismiss: () -> Unit,
    onSave: (CompanionDeadline) -> Unit,
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf("作业") }
    var title by remember { mutableStateOf("") }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加截止事项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = type, onValueChange = { type = it.take(32) }, label = { Text("类型") }, singleLine = true)
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("标题") }, singleLine = true)
                Text(if (dueAt == null) "还没有选择截止日期和时间" else "截止：${dueAt!!.toDisplayTime()}")
                Button(onClick = {
                    showDateAndTimePicker(
                        context = context,
                        initialEpochMillis = dueAt ?: System.currentTimeMillis(),
                        onSelected = { dueAt = it },
                    )
                }) { Text("选择日期与时间") }
                OutlinedTextField(value = note, onValueChange = { note = it.take(240) }, label = { Text("备注（可选）") }, minLines = 2)
                Text("保存后会按本地时区安排 7、3、1 天提醒。")
            }
        },
        confirmButton = {
            Button(
                enabled = type.trim().isNotBlank() && title.trim().isNotBlank() && dueAt != null,
                onClick = { onSave(CompanionDeadline(type = type, title = title, dueAtEpochMillis = dueAt!!, note = note)) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeadlineStepsDialog(
    deadline: CompanionDeadline,
    onDismiss: () -> Unit,
    onSave: (List<DeadlineStep>) -> Unit,
) {
    var text by remember(deadline.id, deadline.draftRevision) { mutableStateOf(deadline.steps.sortedBy { it.order }.joinToString("\n") { it.title }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("拆解：${deadline.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("一行一个步骤；保存会生成新的本地草案版本。")
                OutlinedTextField(value = text, onValueChange = { text = it.take(1_000) }, modifier = Modifier.fillMaxWidth(), minLines = 5, label = { Text("步骤") })
            }
        },
        confirmButton = { Button(onClick = {
            onSave(text.lines().mapNotNull { it.trim().takeIf(String::isNotBlank) }.take(20).mapIndexed { index, value -> DeadlineStep(title = value.take(160), order = index) })
        }) { Text("保存拆解") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TodoistDraftConfirmationDialog(
    deadline: CompanionDeadline,
    draft: me.rerere.rikkahub.data.service.TodoistDeadlineDraft,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认写入 Todoist？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只有你确认，且已连接的 Todoist 工具通过写入安全校验后，才会同步。")
                Text("任务：${draft.content}")
                Text(draft.description)
                Text("草案版本：${draft.revision}")
                if (deadline.todoistSyncState == me.rerere.rikkahub.data.datastore.TodoistSyncState.SYNC_FAILED) Text("上次写入失败；再次确认会在同一草案版本上显式恢复。")
                if (deadline.todoistSyncState == me.rerere.rikkahub.data.datastore.TodoistSyncState.SYNC_UNKNOWN) Text("上次请求已得到响应，但没有任务编号。为避免重复，请先在 Todoist 核对是否已创建。")
            }
        },
        confirmButton = {
            if (deadline.todoistSyncState != me.rerere.rikkahub.data.datastore.TodoistSyncState.SYNC_UNKNOWN) {
                Button(onClick = onConfirm) { Text("确认写入") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
private fun DeadlineRow(
    deadline: CompanionDeadline,
    onRemove: () -> Unit,
    onSteps: () -> Unit,
    onTodoist: () -> Unit,
    onReminderChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${deadline.type} · ${deadline.title}", style = MaterialTheme.typography.titleSmall)
                Text("截止 ${deadline.dueAtEpochMillis.toDisplayTime()}")
                Text("状态：${deadline.status.displayName()} · 草案 v${deadline.draftRevision}")
            }
            TextButton(onClick = onRemove) { Text("移除") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(7, 3, 1).forEach { offset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = offset !in deadline.disabledReminderOffsets, onCheckedChange = { onReminderChange(offset) })
                    Text("${offset}天")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSteps) { Text("拆解（${deadline.steps.size}）") }
            TextButton(onClick = onTodoist) {
                Text(
                    when {
                        deadline.todoistTaskId != null -> "已同步 Todoist"
                        deadline.todoistSyncState == me.rerere.rikkahub.data.datastore.TodoistSyncState.SYNC_UNKNOWN -> "核对 Todoist"
                        else -> "Todoist 草案"
                    },
                )
            }
        }
    }
}

private fun parseCourseTime(value: String): Int? {
    val parts = value.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

private fun showDateAndTimePicker(
    context: Context,
    initialEpochMillis: Long,
    onSelected: (Long) -> Unit,
) {
    val initial = Calendar.getInstance().apply { timeInMillis = initialEpochMillis }
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onSelected(
                        Calendar.getInstance().apply {
                            set(year, month, dayOfMonth, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis,
                    )
                },
                initial.get(Calendar.HOUR_OF_DAY),
                initial.get(Calendar.MINUTE),
                true,
            ).show()
        },
        initial.get(Calendar.YEAR),
        initial.get(Calendar.MONTH),
        initial.get(Calendar.DAY_OF_MONTH),
    ).show()
}

private fun Int.toClockText(): String = "%02d:%02d".format(this / 60, this % 60)

private fun weekdayLabel(value: Int): String = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(value) { "星期$value" }

private fun DeadlineStatus.displayName(): String = when (this) {
    DeadlineStatus.OPEN -> "待开始"
    DeadlineStatus.IN_PROGRESS -> "进行中"
    DeadlineStatus.DONE -> "已完成"
    DeadlineStatus.CANCELLED -> "已取消"
}

private const val DEADLINE_FILTER_ALL = "全部"
private val DEFAULT_DEADLINE_TYPES = listOf("作业", "报告", "考试")
