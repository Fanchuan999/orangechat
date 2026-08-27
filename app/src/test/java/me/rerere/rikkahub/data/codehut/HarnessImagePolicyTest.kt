package me.rerere.rikkahub.data.codehut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HarnessImagePolicyTest {
    @Test
    fun acceptsHarnessSupportedImageBelowLimit() {
        assertEquals("image/jpeg", HarnessImagePolicy.validate("image/jpeg", 5 * 1024 * 1024).mediaType)
    }

    @Test
    fun rejectsGenericFilesAndOversizedImages() {
        assertThrows(IllegalArgumentException::class.java) { HarnessImagePolicy.validate("application/pdf", 1024) }
        assertThrows(IllegalArgumentException::class.java) { HarnessImagePolicy.validate("image/png", 5 * 1024 * 1024 + 1) }
    }
}
