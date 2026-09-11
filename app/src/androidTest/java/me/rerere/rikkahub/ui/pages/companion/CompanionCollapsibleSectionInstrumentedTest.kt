/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionCollapsibleSectionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun arrowButtonExpandsAndCollapsesItsOwnSection() {
        var expanded by mutableStateOf(false)

        composeRule.setContent {
            MaterialTheme {
                CompanionCollapsibleSection(
                    title = "测试标签",
                    summary = "测试摘要",
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    Text("测试内容")
                }
            }
        }

        assertTrue(composeRule.onAllNodesWithText("测试内容").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("companion-section-toggle-测试标签").performClick()
        composeRule.onNodeWithText("测试内容").assertIsDisplayed()
        composeRule.onNodeWithTag("companion-section-toggle-测试标签").performClick()
        assertTrue(composeRule.onAllNodesWithText("测试内容").fetchSemanticsNodes().isEmpty())
    }
}
