package me.rerere.rikkahub.data.gadgetbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiBand5HealthMapperTest {
    @Test
    fun `maps Mi Band seconds to milliseconds without mislabeling intensity`() {
        val sample = MiBand5HealthMapper.mapActivitySample(
            timestampSeconds = 1_722_345_678L,
            heartRate = 72,
            steps = 16,
            rawIntensity = 4,
        )

        assertEquals(1_722_345_678_000L, sample.timestamp)
        assertEquals(72, sample.heartRate)
        assertEquals(16, sample.steps)
        assertEquals(4, sample.rawIntensity)
        assertNull(sample.stress)
        assertNull(sample.spo2)
    }
}
