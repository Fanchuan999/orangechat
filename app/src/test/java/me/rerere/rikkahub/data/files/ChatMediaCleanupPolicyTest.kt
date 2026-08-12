package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaCleanupPolicyTest {
    @Test
    fun safeModeOnlyKeepsUnreferencedUnprotectedImages() {
        val candidates = selectChatMediaCandidates(
            items = listOf(
                ChatMediaItem("upload/chat.png", "image/png", 10, 900L),
                ChatMediaItem("upload/avatar.png", "image/png", 11, 800L),
                ChatMediaItem("upload/doc.pdf", "application/pdf", 12, 700L),
            ),
            referencedChatPaths = setOf("upload/chat.png"),
            protectedPaths = setOf("upload/avatar.png"),
            mode = ChatMediaCleanupMode.SAFE,
            nowMillis = 1_000L,
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun permanentModeOffersReferencedChatImagesButNotProtectedFiles() {
        val candidates = selectChatMediaCandidates(
            items = listOf(
                ChatMediaItem("upload/chat.png", "image/png", 10, 900L),
                ChatMediaItem("upload/avatar.png", "image/png", 11, 800L),
            ),
            referencedChatPaths = setOf("upload/chat.png"),
            protectedPaths = setOf("upload/avatar.png"),
            mode = ChatMediaCleanupMode.PERMANENT,
            nowMillis = 1_000L,
        )

        assertEquals(listOf("upload/chat.png"), candidates.map { it.relativePath })
    }
}
