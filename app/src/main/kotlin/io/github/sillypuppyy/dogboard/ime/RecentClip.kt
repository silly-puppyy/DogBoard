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

    /**
     * Gboard's smart clipboard (measured 2026-11-15): a copied text also offers the things inside it as
     * their own chips, after the whole text: phone, email, link, address, date. Found by Android's
     * on-device text classifier (no network); (icon, text) pairs; call off the main thread.
     */
    fun entities(context: android.content.Context, text: String): List<Pair<String, String>> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P || text.isBlank()) return emptyList()
        return runCatching {
            val classifier = context.getSystemService(android.view.textclassifier.TextClassificationManager::class.java)?.textClassifier
                ?: return emptyList()
            val t = text.take(classifier.maxGenerateLinksTextLength.coerceAtMost(4000))
            val links = classifier.generateLinks(android.view.textclassifier.TextLinks.Request.Builder(t).build()).links
            links.mapNotNull { link ->
                val type = link.getEntity(0)
                val icon = ENTITY_ICONS[type] ?: return@mapNotNull null
                Triple(ENTITY_ORDER.indexOf(type), icon, t.substring(link.start, link.end).trim())
            }.filter { it.third.isNotEmpty() && it.third != text.trim() }.distinctBy { it.third }.sortedBy { it.first }.map { it.second to it.third }
        }.getOrDefault(emptyList())
    }

    /** The whole clip's icon: its entity's when the clip is just that (a link, an email), else the clipboard. */
    fun icon(context: android.content.Context, item: ClipboardItem): String = when {
        item.isSensitive -> "key"
        item.type == ItemType.IMAGE -> "image"
        item.type == ItemType.VIDEO -> "image"
        else -> {
            val text = item.text.orEmpty().trim()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && text.isNotEmpty()) runCatching {
                val classifier = context.getSystemService(android.view.textclassifier.TextClassificationManager::class.java)?.textClassifier
                val links = classifier?.generateLinks(android.view.textclassifier.TextLinks.Request.Builder(text).build())?.links.orEmpty()
                links.singleOrNull()?.takeIf { it.start == 0 && it.end == text.length }?.let { ENTITY_ICONS[it.getEntity(0)] }
            }.getOrNull() ?: "content_paste" else "content_paste"
        }
    }

    private val ENTITY_ORDER = listOf(
        android.view.textclassifier.TextClassifier.TYPE_PHONE, android.view.textclassifier.TextClassifier.TYPE_EMAIL,
        android.view.textclassifier.TextClassifier.TYPE_URL, android.view.textclassifier.TextClassifier.TYPE_ADDRESS,
        android.view.textclassifier.TextClassifier.TYPE_DATE, android.view.textclassifier.TextClassifier.TYPE_DATE_TIME,
    )
    private val ENTITY_ICONS = mapOf(
        android.view.textclassifier.TextClassifier.TYPE_PHONE to "call", android.view.textclassifier.TextClassifier.TYPE_EMAIL to "mail",
        android.view.textclassifier.TextClassifier.TYPE_URL to "link", android.view.textclassifier.TextClassifier.TYPE_ADDRESS to "location_on",
        android.view.textclassifier.TextClassifier.TYPE_DATE to "event", android.view.textclassifier.TextClassifier.TYPE_DATE_TIME to "event",
    )

    /** The chip's text. */
    fun label(item: ClipboardItem): String = when {
        item.isSensitive -> "Paste password"
        item.type == ItemType.IMAGE -> "Paste copied image"
        item.type == ItemType.VIDEO -> "Paste copied video"
        else -> item.text.orEmpty().replace(Regex("\\s+"), " ").trim().let { if (it.length > 28) it.take(27) + "…" else it }
    }
}
