package me.rerere.rikkahub.data.codehut

data class HarnessImageAttachment(val mediaType: String, val bytes: ByteArray)

/** Matches the currently installed Harness image-message protocol. */
object HarnessImagePolicy {
    private val supportedMediaTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    const val MAX_BYTES = 5 * 1024 * 1024

    fun validate(mediaType: String?, size: Int): HarnessImageAttachmentInfo {
        val safeType = mediaType.orEmpty().lowercase()
        require(safeType in supportedMediaTypes) { "Harness 目前只支持 PNG、JPEG、WebP 或 GIF 图片。" }
        require(size in 1..MAX_BYTES) { "单张图片不能超过 5 MB。" }
        return HarnessImageAttachmentInfo(safeType)
    }
}

data class HarnessImageAttachmentInfo(val mediaType: String)
