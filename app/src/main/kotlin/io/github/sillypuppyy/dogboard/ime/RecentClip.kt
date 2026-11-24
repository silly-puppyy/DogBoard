package io.github.sillypuppyy.dogboard.ime

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType

/**
 * The recent-copy chip (designer, Panels:103; lifetime as Gboard, measured 2026-11-15): for 2 min after a copy, while
 * nothing is being typed, the bar offers to paste it instead of predictions; a clip marked sensitive
 * (passwords) says "Paste password" without showing it; none in incognito. Once pasted (or dismissed)
 * that clip isn't offered again.
 */
object RecentClip {
    const val WINDOW_MS = 120_000L

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
        if (text.isBlank()) return emptyList()
        val classified = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) emptyList() else runCatching {
            val classifier = context.getSystemService(android.view.textclassifier.TextClassificationManager::class.java)?.textClassifier
                ?: return@runCatching emptyList()
            val t = text.take(classifier.maxGenerateLinksTextLength.coerceAtMost(4000))
            val links = classifier.generateLinks(android.view.textclassifier.TextLinks.Request.Builder(t).build()).links
            links.mapNotNull { link ->
                val type = link.getEntity(0)
                val icon = ENTITY_ICONS[type] ?: return@mapNotNull null
                Triple(ENTITY_ORDER.indexOf(type), icon, t.substring(link.start, link.end).trim())
            }
        }.getOrDefault(emptyList())
        // Gboard runs both and merges them: the system classifier misses numbers in formats it has no model for
        return (classified + patternEntities(text)).filter { it.third.isNotEmpty() && it.third != text.trim() }
            .distinctBy { it.third.filter { c -> !c.isWhitespace() } }.sortedBy { it.first }.map { it.second to it.third }
    }

    // not inside a longer run of digits (card numbers, millisecond timestamps)
    private val PHONE = Regex("""(?<![\d+])(\+\d{1,2}\s?)?\(?\d{3,4}\)?[\s.-]?\d{3,4}[\s.-]?\d{4}(?!\d)""")
    private val EMAIL = Regex("""^[\w.+-]+@[\w-]+(\.[\w-]+)+$""")
    private val URL = Regex("""^(https?://)?([\w-]+\.)+[a-zA-Z]{2,}(/\S*)?$""")
    private val CODE = Regex("""^\d{4,8}$""")

    /** Generic top-level domains people type without a scheme; with a scheme or www. any domain is a link. */
    private val GTLDS = setOf(
        "com", "org", "net", "edu", "gov", "int", "mil", "info", "biz", "name", "pro", "app", "dev", "page", "blog", "shop",
        "store", "online", "site", "tech", "xyz", "art", "design", "news", "media", "live", "tv", "music", "cloud", "games",
        "social", "chat", "email", "link", "wiki", "museum", "travel", "jobs", "eco", "gay", "lgbt", "moe", "zone", "world",
    )

    /** Country domains: ISO 3166 codes, plus uk and eu ("main.kt" is no link: kt is no country). */
    private val CCTLDS: Set<String> by lazy { java.util.Locale.getISOCountries().map { it.lowercase() }.toSet() + setOf("uk", "eu") }

    /** A link, not a file name: "example.org/x" or "https://anything", not "index.html" or "e.g". */
    private fun isLink(word: String): Boolean {
        if (!URL.matches(word) || !word.contains('.') || word.contains('@')) return false
        if (word.startsWith("http://", true) || word.startsWith("https://", true) || word.startsWith("www.", true)) return true
        val tld = word.substringBefore('/').substringAfterLast('.').lowercase()
        return tld in CCTLDS || tld in GTLDS
    }

    /**
     * Gboard's second extractor (its clipboard entity regexes, notes/research/gboard-clipboard-entities.md): a phone
     * number anywhere, and whole words that are an email, a link or a 4-8 digit code; (order, icon, text) as entities().
     */
    fun patternEntities(text: String): List<Triple<Int, String, String>> {
        val out = ArrayList<Triple<Int, String, String>>()
        for (m in PHONE.findAll(text)) out.add(Triple(0, "call", m.value.trim()))
        for (w in text.split(Regex("""\s+"""))) {
            val word = w.trim('(', ')', ',', ';', ':', '!', '?', '"', '\'', '“', '”').trimEnd('.')
            when {
                EMAIL.matches(word) -> out.add(Triple(1, "mail", word))
                isLink(word) -> out.add(Triple(2, "link", word))
                CODE.matches(word) && out.none { it.second == "call" && word in it.third } -> out.add(Triple(6, "pin", word))
            }
        }
        return out
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
        // a copied link reads as its page's title once Link previews has read it (the chip showed the raw link)
        else -> item.text.orEmpty().replace(Regex("\\s+"), " ").trim().let { t -> LinkInfo.linkIn(t)?.takeIf { it == t }?.let { LinkInfo.get(it)?.title } ?: t }
            .let { if (it.length > 28) it.take(27) + "…" else it }
    }
}
