/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import okhttp3.OkHttpClient

/**
 * Copies only the safe OkHttp base configuration. The bridge must never inherit
 * application request or network interceptors because it sends real provider keys.
 */
internal fun buildCodeHutUpstreamClient(source: OkHttpClient): OkHttpClient = source.newBuilder()
    .apply {
        interceptors().clear()
        networkInterceptors().clear()
    }
    .build()
