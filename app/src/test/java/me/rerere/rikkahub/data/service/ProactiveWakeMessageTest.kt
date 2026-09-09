package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveWakeMessageTest {
    private val service = ProactiveMessageTriggerService()

    @Test
    fun `regular proactive wake is a system event not a user message`() {
        val message = service.buildProactiveWakeMessage(
            isNightWatchTrigger = false,
            isIdleExploreTrigger = false,
            isFromDeviceEvent = false,
        )
        val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

        assertEquals(MessageRole.SYSTEM, message.role)
        assertTrue(text.contains("后台tick"))
        assertTrue(text.contains("不要复盘上一轮"))
        assertTrue(text.contains("[PASS]"))
        assertTrue(text.length <= 140)
        assertFalse(text.contains("本轮她没有发送文字"))
        assertFalse(text.contains("空白消息"))
    }

    @Test
    fun `device event wake forbids treating the tick as user text`() {
        val message = service.buildProactiveWakeMessage(
            isNightWatchTrigger = false,
            isIdleExploreTrigger = false,
            isFromDeviceEvent = true,
        )
        val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

        assertEquals(MessageRole.SYSTEM, message.role)
        assertTrue(text.contains("设备事件"))
        assertTrue(text.contains("不要脑补她说话"))
        assertTrue(text.contains("不要查时间"))
        assertTrue(text.length <= 140)
        assertTrue(text.contains("[PASS]"))
    }

    @Test
    fun `idle wake identifies a background activity choice without treating it as user input`() {
        val message = service.buildProactiveWakeMessage(
            isNightWatchTrigger = false,
            isIdleExploreTrigger = true,
            isFromDeviceEvent = false,
        )
        val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

        assertEquals(MessageRole.SYSTEM, message.role)
        assertTrue(text.contains("空闲探索"))
        assertTrue(text.contains("[PASS]"))
        assertFalse(text.contains("用户的新消息"))
    }
}
