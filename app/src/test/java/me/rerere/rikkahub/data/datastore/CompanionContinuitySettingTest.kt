package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CompanionContinuitySettingTest {
    @Test
    fun promptUsesOnlyTheNewestConfiguredLifeLineEntries() {
        val profile = CompanionContinuityProfile(
            assistantId = Uuid.random(),
            stateCard = "正在继续昨天的话题",
            lifeLine = listOf(
                LifeLineEntry(content = "很久以前的近况"),
                LifeLineEntry(content = "昨天开始复习"),
                LifeLineEntry(content = "今天想早点休息"),
            ),
            lifeLineMaxEntries = 2,
            maxPromptCharacters = 800,
        )

        val prompt = profile.promptContext()

        assertFalse(prompt.contains("很久以前的近况"))
        assertTrue(prompt.contains("昨天开始复习"))
        assertTrue(prompt.contains("今天想早点休息"))
        assertTrue(prompt.contains("正在继续昨天的话题"))
    }

    @Test
    fun disabledProfileDoesNotInjectAnything() {
        val prompt = CompanionContinuityProfile(
            assistantId = Uuid.random(),
            enabled = false,
            stateCard = "这段文字不应被带入聊天",
        ).promptContext()

        assertTrue(prompt.isEmpty())
    }

    @Test
    fun promptRespectsTheConfiguredCharacterBudget() {
        val prompt = CompanionContinuityProfile(
            assistantId = Uuid.random(),
            stateCard = "很长的状态。".repeat(200),
            maxPromptCharacters = MIN_PROMPT_CHARACTERS,
        ).promptContext()

        assertTrue(prompt.length <= MIN_PROMPT_CHARACTERS)
    }
}
