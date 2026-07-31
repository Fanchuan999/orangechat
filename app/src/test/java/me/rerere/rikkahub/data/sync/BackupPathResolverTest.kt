package me.rerere.rikkahub.data.sync

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupPathResolverTest {
    @Test
    fun `allows an archive entry below its intended directory`() {
        val root = Files.createTempDirectory("backup-root").toFile()

        val resolved = BackupPathResolver.resolveWithin(root, "plugin-id/assets/icon.png")

        assertEquals(root.resolve("plugin-id/assets/icon.png").canonicalPath, resolved?.canonicalPath)
    }

    @Test
    fun `rejects a zip slip path`() {
        val root = Files.createTempDirectory("backup-root").toFile()

        assertNull(BackupPathResolver.resolveWithin(root, "../outside.txt"))
    }
}
