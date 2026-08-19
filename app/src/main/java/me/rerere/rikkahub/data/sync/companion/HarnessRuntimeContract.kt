/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.sync.companion

import java.util.Locale
import me.rerere.rikkahub.data.datastore.HarnessInstallStage

internal object HarnessRuntimeContract {
    const val CONTAINER_NAME = "daddy-linux"
    const val DEBIAN_IMAGE = "debian:bookworm"
    const val DEBIAN_FALLBACK_IMAGE = "docker.m.daocloud.io/library/debian:bookworm"
    const val ARCHITECTURE = "aarch64"
    const val NODE_VERSION = "24.19.0"
    const val HARNESS_VERSION = "0.1.0-rc.7"
    const val LINUX_RUNTIME_MARKER = "daddy-linux-debian-v1"

    const val BASE = "\$HOME/daddy-linux"
    const val SERVICES = "$BASE/services/harness"
    const val DATA = "$BASE/data/harness"
    const val LEGACY_BASE = "\$HOME/daddy-harness"
}

internal fun parseHarnessInstallStage(value: String): HarnessInstallStage {
    val normalized = value.trim()
        .replace('-', '_')
        .uppercase(Locale.ROOT)
    return HarnessInstallStage.entries.firstOrNull { it.name == normalized }
        ?: HarnessInstallStage.UNKNOWN
}

internal fun isLinuxHarnessRuntime(marker: String): Boolean =
    marker.trim() == HarnessRuntimeContract.LINUX_RUNTIME_MARKER
