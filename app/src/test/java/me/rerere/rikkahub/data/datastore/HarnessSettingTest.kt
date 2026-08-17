/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessSettingTest {
    @Test
    fun defaultsKeepHarnessAliveWithoutClaimingItIsInstalled() {
        assertEquals(
            HarnessSetting(
                autoKeepRunning = true,
                manuallyStopped = false,
                installedVersion = "",
            ),
            HarnessSetting(),
        )
    }

    @Test
    fun settingSurvivesJsonRoundTrip() {
        val expected = HarnessSetting(
            autoKeepRunning = false,
            manuallyStopped = true,
            installedVersion = "0.1.0-rc.5",
        )

        val encoded = JsonInstant.encodeToString(expected)

        assertEquals(expected, JsonInstant.decodeFromString<HarnessSetting>(encoded))
    }
}
