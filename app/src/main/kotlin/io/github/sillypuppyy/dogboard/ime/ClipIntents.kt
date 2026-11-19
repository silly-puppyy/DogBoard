package io.github.sillypuppyy.dogboard.ime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType

/**
 * The clipboard hold menu's outward actions (canvas HoldMenu, H:31-38): the clip's own action (Open link,
 * Send email, Call, Open in Maps) and Share. Started from the keyboard, so each is a new task.
 */
object ClipIntents {
    /** The clip's action, by what the text is; false when it is none of them. */
    fun open(context: Context, text: String): Boolean {
        val s = text.trim()
        val uri = when {
            android.util.Patterns.EMAIL_ADDRESS.matcher(s).matches() -> Uri.parse("mailto:$s")
            s.matches(Regex("\\+?[\\d ()./-]{7,}")) -> Uri.parse("tel:" + s.filter { it.isDigit() || it == '+' })
            android.util.Patterns.WEB_URL.matcher(s).matches() -> Uri.parse(if (s.contains("://")) s else "https://$s")
            else -> return false
        }
        val action = if (uri.scheme == "tel") Intent.ACTION_DIAL else Intent.ACTION_VIEW
        return start(context, Intent(action, uri))
    }

    /** The system share sheet with the clip's text, or its picture or video. */
    fun share(context: Context, item: ClipboardItem): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            if (item.type == ItemType.TEXT) {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, item.text.orEmpty())
            } else {
                type = item.mimeTypes.firstOrNull() ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return start(context, Intent.createChooser(send, null))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
