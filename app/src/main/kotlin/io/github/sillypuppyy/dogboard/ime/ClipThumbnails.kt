package io.github.sillypuppyy.dogboard.ime

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType

/**
 * Previews for image and video clips in the clipboard panel (like Gboard): decoded off the main
 * thread, downsampled to about the tile size, and cached so reopening the panel is instant.
 */
object ClipThumbnails {
    private val cache = object : LruCache<Long, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }

    /** A preview for [item] about [sizePx] across, or null (not media, missing file, can't decode). Call off the main thread. */
    fun load(context: Context, item: ClipboardItem, sizePx: Int): ImageBitmap? {
        val uri = item.uri ?: return null
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        cache.get(id)?.let { return it }
        val file = ClipboardFileStorage.getFileForId(context, id)
        if (!file.exists()) return null
        val bitmap: Bitmap? = runCatching {
            when (item.type) {
                ItemType.IMAGE -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                }
                ItemType.VIDEO -> ThumbnailUtils.createVideoThumbnail(file, Size(sizePx, sizePx), null)
                else -> null
            }
        }.getOrNull()
        return bitmap?.asImageBitmap()?.also { cache.put(id, it) }
    }
}
