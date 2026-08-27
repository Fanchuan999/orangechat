package me.rerere.rikkahub.data.pcbridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcBridgePairingManualTest {
    @Test
    fun `first pairing manual uses the pinned TypeScript CLI for every setup command`() {
        val manual = File("../docs/testing/2026-08-26-pc-bridge-pairing-manual.md").readText()
        val firstPairing = manual.substringAfter("## 第一次配对").substringBefore("## 严禁粘贴到手机的内容")

        assertTrue(firstPairing.contains("""tools\daddy-pc-bridge\src\cli\main.ts"""))
        assertEquals(
            3,
            Regex("""node --experimental-strip-types \${'$'}BridgeCli (prepare|configure-relay|pair-pc)""")
                .findAll(firstPairing)
                .count(),
        )
    }
}
