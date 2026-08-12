package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionAffectDetectorTest {
    @Test
    fun detectsOnlyClearUserExpressions() {
        val events = detectCompanionAffectEvents("我今天好累，也有点难过", nowMillis = 1_000L)

        assertEquals(listOf(CompanionAffectKind.FATIGUE, CompanionAffectKind.LOW_MOOD), events.map { it.kind })
        assertTrue(events.all { it.expiresAtMillis > 1_000L })
    }

    @Test
    fun skipsNegationsQuestionsAndWordExplanations() {
        assertTrue(detectCompanionAffectEvents("我不难过", nowMillis = 1_000L).isEmpty())
        assertTrue(detectCompanionAffectEvents("你难过吗？", nowMillis = 1_000L).isEmpty())
        assertTrue(detectCompanionAffectEvents("难过这个词怎么读", nowMillis = 1_000L).isEmpty())
    }

    @Test
    fun sameKindRefreshesInsteadOfGrowingUnbounded() {
        val current = listOf(
            CompanionAffectEvent(
                kind = CompanionAffectKind.FATIGUE,
                intensity = 0.55f,
                recordedAtMillis = 1_000L,
                expiresAtMillis = 2_000L,
            )
        )

        val refreshed = current.withDetectedAffectEvents(
            detectCompanionAffectEvents("累死了", nowMillis = 3_000L),
            nowMillis = 3_000L,
        )

        assertEquals(1, refreshed.size)
        assertEquals(CompanionAffectKind.FATIGUE, refreshed.single().kind)
        assertTrue(refreshed.single().expiresAtMillis > 3_000L)
    }
}
