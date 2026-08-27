package me.rerere.rikkahub.ui.pages.codehut

import me.rerere.rikkahub.data.codehut.HarnessInboxTask
import me.rerere.rikkahub.data.codehut.HarnessInboxTaskState
import me.rerere.rikkahub.data.datastore.HarnessSnapshot
import me.rerere.rikkahub.data.datastore.HarnessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHutShellPolicyTest {
    @Test
    fun environmentOnlyShowsServiceAndPermission() {
        val presentation = codeHutEnvironmentPresentation(
            harnessSnapshot = HarnessSnapshot(status = HarnessStatus.RUNNING),
        )

        assertEquals(listOf("服务", "权限"), presentation.items.map { it.title })
        assertEquals("运行中", presentation.service.status)
        assertTrue(presentation.permissions.detail.contains("高风险操作"))
    }

    @Test
    fun completedAndFailedTasksAreAbsentFromBoard() {
        val active = listOf(
            HarnessInboxTask("daddy-1", "待执行", "", HarnessInboxTaskState.QUEUED),
            HarnessInboxTask("daddy-2", "执行中", "", HarnessInboxTaskState.RUNNING),
        )

        assertEquals(listOf("queued", "running"), active.map { it.state.wireName })
        assertFalse(active.any { it.title.contains("完成") || it.title.contains("失败") })
    }

    @Test
    fun workbenchAvailabilityMatchesHarnessRunningState() {
        HarnessStatus.entries.forEach { status ->
            assertEquals(status == HarnessStatus.RUNNING, codeHutWorkbenchPresentation(status).canOpen)
        }
    }

    @Test
    fun sharedRedactionKeepsSecretsOutOfNativeShellText() {
        val displayed = redactCodeHutShellText("Authorization: Bearer secret-token password=hunter2")

        assertTrue(displayed.contains("[REDACTED]"))
        assertFalse(displayed.contains("secret-token"))
        assertFalse(displayed.contains("hunter2"))
    }
}
