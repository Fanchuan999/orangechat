/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import java.io.File
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

object DaddyWidgetRenderer {
    fun render(context: Context, snapshot: DaddyWidgetSnapshot, size: DaddyWidgetSize): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_daddy_status)
        val state = snapshot.state
        val enabled = snapshot.enabled

        views.setTextViewText(R.id.widget_title, "Daddy")
        views.setTextViewText(R.id.widget_mood, if (enabled) state.moodLabel else "小组件已关闭")
        views.setTextViewText(R.id.widget_focus, if (enabled) state.focusLabel else "去小屋重新打开")
        views.setTextViewText(R.id.widget_line, if (enabled) state.shortLine else "我先安静待机。")
        views.setTextViewText(R.id.widget_recent_reply, if (enabled) state.recentReply else "")
        views.setTextViewText(R.id.widget_time, state.timeText)
        views.setTextViewText(R.id.widget_date, state.dateText)
        views.setTextViewText(R.id.widget_weather, state.weatherText)
        views.setTextViewText(R.id.widget_steps, state.stepsText)

        val showLarge = size == DaddyWidgetSize.Large
        val showRecent = size != DaddyWidgetSize.Compact
        val showSideText = size != DaddyWidgetSize.Compact
        val showArchivePanel = size != DaddyWidgetSize.Compact
        views.setViewVisibility(R.id.widget_recent_reply, if (showRecent) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_large_grid, if (showLarge) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_side_text, if (showSideText) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_archive_panel, if (showArchivePanel) View.VISIBLE else View.GONE)

        resolveBackground(context, snapshot.backgroundImageUri)?.let {
            views.setImageViewBitmap(R.id.widget_background_image, it)
            views.setViewVisibility(R.id.widget_background_image, View.VISIBLE)
        } ?: views.setViewVisibility(R.id.widget_background_image, View.GONE)

        views.setOnClickPendingIntent(R.id.widget_root, launchPendingIntent(context))
        return views
    }

    private fun launchPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            9401,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun resolveBackground(context: Context, uriText: String): Bitmap? {
        if (uriText.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(uriText)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.openWidgetBackground(uri) { BitmapFactory.decodeStream(it, null, options) }
            val maxSize = 720
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxSize || options.outHeight / sampleSize > maxSize) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            context.openWidgetBackground(uri) {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            }
        }.getOrNull()
    }

    private inline fun <T> Context.openWidgetBackground(uri: Uri, block: (java.io.InputStream) -> T): T? {
        return if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).inputStream().use(block)
        } else {
            contentResolver.openInputStream(uri)?.use(block)
        }
    }
}
