/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.harness

internal data class HarnessWebViewport(
    val useWideViewPort: Boolean,
    val loadWithOverviewMode: Boolean,
    val textZoomPercent: Int,
)

/**
 * The Harness web app is responsive in Chrome. Keep WebView's viewport and text
 * scaling aligned with the browser so its own scroll container receives the
 * full usable height on large-font Android devices.
 */
internal val harnessWebViewport = HarnessWebViewport(
    useWideViewPort = true,
    loadWithOverviewMode = false,
    textZoomPercent = 100,
)
