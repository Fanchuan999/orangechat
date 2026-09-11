/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.datastore.CompanionPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoWallSectionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun savedPhotoRendersInStandalonePhotoWallAndCanBeRemoved() {
        var removed: CompanionPhoto? = null

        composeRule.setContent {
            MaterialTheme {
                PhotoWallSection(
                    photos = listOf(
                        CompanionPhoto(
                            uri = "file:///test-photo.jpg",
                            caption = "测试照片",
                        )
                    ),
                    onAddPhoto = {},
                    onEditCaption = {},
                    onRemovePhoto = { removed = it },
                )
            }
        }

        composeRule.onNodeWithTag("companion-section-toggle-照片墙").performClick()
        composeRule.onNodeWithText("测试照片").assertIsDisplayed()
        composeRule.onNodeWithText("取下").performClick()

        assertEquals("测试照片", removed?.caption)
    }

    @Test
    fun photoWallKeepsPhotosCollapsedUntilItsHeaderIsTapped() {
        composeRule.setContent {
            MaterialTheme {
                PhotoWallSection(
                    photos = listOf(
                        CompanionPhoto(
                            uri = "file:///test-photo.jpg",
                            caption = "测试照片",
                        )
                    ),
                    onAddPhoto = {},
                    onEditCaption = {},
                    onRemovePhoto = {},
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithText("测试照片").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("companion-section-toggle-照片墙").performClick()
        composeRule.onNodeWithText("测试照片").assertIsDisplayed()
        composeRule.onNodeWithTag("companion-section-toggle-照片墙").performClick()
        assertTrue(composeRule.onAllNodesWithText("测试照片").fetchSemanticsNodes().isEmpty())
    }
}
