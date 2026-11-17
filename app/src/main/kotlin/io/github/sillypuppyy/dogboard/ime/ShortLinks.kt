package io.github.sillypuppyy.dogboard.ime

/**
 * Settings › Clipboard › "Expand short links" (opt-in, user 2026-11-17): a short link (vm.tiktok.com,
 * pin.it, a.co...) carries the share in its code, which ties it to the sharer's account; only the
 * shortener knows where it leads. When a copied text holds one, DogBoard asks that shortener once for
 * the address it points to (HEAD, redirects not followed into the site, no cookies), so the paste can
 * be the long link, cleaned by [LinkCleaner]. Done on the sharer's own phone, the request tells the
 * service nothing new; the person it's sent to gets a link that no longer points back to the sharer.
 * Only the hosts below are ever asked (notes/design/link-cleaning.md).
 */
object ShortLinks {
    /** Link shorteners whose codes identify a share or a sharer. */
    val HOSTS = setOf(
        "vm.tiktok.com", "vt.tiktok.com", "pin.it", "a.co", "amzn.to", "amzn.eu", "amzn.asia",
        "spoti.fi", "bit.ly", "t.co", "lnkd.in", "fb.me", "tinyurl.com", "ow.ly", "buff.ly", "is.gd",
        "rb.gy", "cutt.ly", "shorturl.at", "dlvr.it",
    )

    private val URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    private fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase().removePrefix("www.")

    fun isShort(url: String) = host(url) in HOSTS

    /** Short link -> where it points; filled by [expandAll] (memory only, this session). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [text] with each short link that has been expanded replaced by its target (no network). */
    fun withExpanded(text: String): String = URL.replace(text) { m -> cache[m.value] ?: m.value }

    /**
     * Asks the shorteners for the short links in [text] (off the main thread; at most 3 hops, each a
     * request to a host in [HOSTS]); the results go to the cache for [withExpanded].
     */
    fun expandAll(text: String) {
        for (m in URL.findAll(text)) {
            val short = m.value
            if (!isShort(short) || cache.containsKey(short)) continue
            var url = short
            repeat(3) {
                if (!isShort(url)) return@repeat
                url = locationOf(url) ?: return@repeat
            }
            if (url != short && !isShort(url)) cache[short] = url
        }
    }

    private fun locationOf(url: String): String? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.requestMethod = "HEAD"
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DogBoard")
            if (c.responseCode !in 300..399) return@runCatching null
            c.getHeaderField("Location")?.let { java.net.URL(java.net.URL(url), it).toString() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
