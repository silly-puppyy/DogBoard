package io.github.sillypuppyy.dogboard.ime

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType

/**
 * The recent-copy chip (designer, Panels:103 and answers 2026-11-14): for 60 s after a copy, while
 * nothing is being typed, the bar offers to paste it instead of predictions; a clip marked sensitive
 * (passwords) says "Paste password" without showing it; none in incognito. Once pasted (or dismissed)
 * that clip isn't offered again.
 */
object RecentClip {
    const val WINDOW_MS = 60_000L

    /** Creation time of the last clip pasted from the chip (clips have no stable id outside history). */
    @Volatile
    private var used: Long = -1

    fun offer(item: ClipboardItem?, now: Long, incognito: Boolean): ClipboardItem? {
        if (item == null || incognito || item.creationTimestampMs == used) return null
        if (now - item.creationTimestampMs !in 0 until WINDOW_MS) return null
        if (item.type == ItemType.TEXT && item.text.isNullOrBlank()) return null
        return item
    }

    fun markUsed(item: ClipboardItem) {
        used = item.creationTimestampMs
    }

    /** The chip's text. */
    fun label(item: ClipboardItem): String = when {
        item.isSensitive -> "Paste password"
        item.type == ItemType.IMAGE -> "Paste copied image"
        item.type == ItemType.VIDEO -> "Paste copied video"
        else -> item.text.orEmpty().replace(Regex("\\s+"), " ").trim().let { if (it.length > 28) it.take(27) + "…" else it }
    }
}
