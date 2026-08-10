package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.CompanionDesireState
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeCoordinatorTest {
    @Test
    fun `night watch always requests contact once phone conditions are met`() {
        val decision = WakeCoordinator.decide(
            WakeInput(
                source = WakeSource.NightWatch,
                desire = CompanionDesireState(longing = 0f, fatigue = 1f),
            )
        )

        assertEquals(WakeDecision.Contact, decision)
    }

    @Test
    fun `aggressive device event keeps its model judgement path`() {
        val decision = WakeCoordinator.decide(
            WakeInput(
                source = WakeSource.Aggressive,
                desire = CompanionDesireState(longing = 0f, fatigue = 1f),
            )
        )

        assertEquals(WakeDecision.Contact, decision)
    }

    @Test
    fun `ordinary wake remains quiet when desire is low`() {
        val decision = WakeCoordinator.decide(
            WakeInput(
                source = WakeSource.Scheduled,
                desire = CompanionDesireState(
                    longing = 0.05f,
                    closeness = 0.05f,
                    curiosity = 0.05f,
                    expression = 0.05f,
                    care = 0.05f,
                    wander = 0.05f,
                    agency = 0.05f,
                    fatigue = 0.05f,
                ),
            )
        )

        assertEquals(WakeDecision.Quiet, decision)
    }

    @Test
    fun `high longing chooses contact and high exploration chooses activity`() {
        val contact = WakeCoordinator.decide(
            WakeInput(
                source = WakeSource.Scheduled,
                desire = CompanionDesireState(longing = 0.95f, expression = 0.8f, care = 0.8f),
            )
        )
        val activity = WakeCoordinator.decide(
            WakeInput(
                source = WakeSource.Scheduled,
                desire = CompanionDesireState(
                    longing = 0.05f,
                    expression = 0.05f,
                    care = 0.05f,
                    curiosity = 0.9f,
                    wander = 0.9f,
                    agency = 0.9f,
                ),
            )
        )

        assertEquals(WakeDecision.Contact, contact)
        assertEquals(WakeDecision.FindActivity, activity)
    }

    @Test
    fun `busy conversation suppresses every automatic wake`() {
        val decision = WakeCoordinator.decide(
            WakeInput(
                source = WakeSource.NightWatch,
                desire = CompanionDesireState(longing = 1f),
                conversationBusy = true,
            )
        )

        assertEquals(WakeDecision.Quiet, decision)
    }
}
