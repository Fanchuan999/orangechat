/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

import java.net.URI

internal object HarnessUrlPolicy {
    fun isInternal(url: String): Boolean = parse(url)?.let { uri ->
        uri.scheme.equals("http", ignoreCase = true) &&
            uri.host == "127.0.0.1" &&
            uri.port == 3080
    } == true

    fun isExternalHttp(url: String): Boolean = parse(url)?.let { uri ->
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    } == true

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()
}
