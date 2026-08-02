package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemToolsSettingTest {
    @Test
    fun healthAwarenessCueRequiresBothHealthToolAndCueToBeEnabled() {
        assertTrue(SystemToolsSetting(gadgetbridgeEnabled = true).gadgetbridgePromptContext().contains("sleep"))
        assertTrue(SystemToolsSetting(gadgetbridgeEnabled = true).gadgetbridgePromptContext().contains("睡眠"))
        assertFalse(SystemToolsSetting().gadgetbridgePromptContext().isNotEmpty())
        assertFalse(
            SystemToolsSetting(
                gadgetbridgeEnabled = true,
                gadgetbridgeHealthAwarenessEnabled = false,
            ).gadgetbridgePromptContext().isNotEmpty()
        )
    }
}
