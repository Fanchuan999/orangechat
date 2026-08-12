package me.rerere.rikkahub.data.sync.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class OmbreBackupAuthTest {
    @Test
    fun authStatusWithNoPasswordSelectsFirstTimeSetup() {
        assertEquals(
            OmbreDashboardAuthAction.Setup,
            ombreDashboardAuthAction("{\"setup_needed\":true}"),
        )
    }

    @Test
    fun configuredOrLegacyStatusUsesExistingPasswordLogin() {
        assertEquals(
            OmbreDashboardAuthAction.Login,
            ombreDashboardAuthAction("{\"setup_needed\":false}"),
        )
        assertEquals(
            OmbreDashboardAuthAction.Login,
            ombreDashboardAuthAction("{}"),
        )
    }
}
