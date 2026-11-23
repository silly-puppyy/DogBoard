package io.github.sillypuppyy.dogboard.ime

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.inputmethod.EditorInfo
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import java.io.File

/**
 * A clip as a file, for the hold menu's "Paste as file" and "Share as file": text as a .txt
 * (as it would be pasted, links cleaned per the clip's settings), a colour as a .png swatch, a picture or
 * video as the file it already is. Made files go to the cache folder the file provider serves (`shared/`).
 */
object ClipFiles {
    data class ClipFile(val uri: Uri, val mime: String, val name: String)

    /** [pasted] is the text as DogBoard would paste it (ImeController.cleanedPaste). */
    fun of(context: Context, item: ClipboardItem, pasted: String?): ClipFile? {
        if (item.type != ItemType.TEXT) {
            val uri = item.uri ?: return null
            return ClipFile(uri, item.mimeTypes.firstOrNull() ?: "*/*", if (item.type == ItemType.VIDEO) "Video" else "Picture")
        }
        val text = pasted ?: item.text ?: return null
        if (text.isEmpty()) return null
        ClipKinds.color(text.trim())?.let { c ->
            val f = file(context, c.hex.removePrefix("#") + ".png")
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor((c.argb or 0xFF000000).toInt()) }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            return ClipFile(uri(context, f), "image/png", f.name)
        }
        val f = file(context, nameFor(text) + ".txt")
        f.writeText(text)
        return ClipFile(uri(context, f), "text/plain", f.name)
    }

    /** Whether the field takes this kind of file through the keyboard (EditorInfo content MIME types). */
    fun accepts(info: EditorInfo, mime: String): Boolean =
        EditorInfoCompat.getContentMimeTypes(info).any { ClipDescription.compareMimeTypes(mime, it) }

    /** The first words, as a file name ("Meeting moved to Thursday"), or "Clip". */
    internal fun nameFor(text: String): String =
        text.split(Regex("\\s+")).asSequence().map { w -> w.filter { it.isLetterOrDigit() || it == '-' } }
            .filter { it.isNotEmpty() }.take(5).joinToString(" ").take(40).trim().ifEmpty { "Clip" }

    private fun file(context: Context, name: String): File =
        File(context.cacheDir, "shared/clips").apply { mkdirs() }.let { File(it, name) }

    private fun uri(context: Context, f: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".provider.file", f)
}
