package io.github.sillypuppyy.dogboard.ime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Settings › Clipboard › "Strip trackers from links" (opt-in): the tracking tags in
 * the links of a text DogBoard pastes are removed. The rules are Brave's "Copy clean link" list
 * (assets/dogboard/clean-urls.json, MPL-2.0, tools/links/fetch_clean_urls.py; research in
 * notes/design/link-cleaning.md), plus DogBoard's own below for what it lacks. Only tags that
 * identify a click, a campaign or a share are removed; the order of the remaining parameters, the
 * fragment, and anything that isn't a link stay as they are.
 */
object LinkCleaner {
    /** A rule of Brave's list: where it applies (browser match patterns) and which tags it removes. */
    class Rule(val include: List<Regex>, val exclude: List<Regex>, val params: Set<String>)

    @Volatile private var brave: List<Rule> = emptyList()
    @Volatile private var loaded = false

    /** Reads Brave's list once (assets/dogboard/clean-urls.json). */
    fun ensureLoaded(context: android.content.Context) {
        if (loaded) return
        runCatching { context.assets.open("dogboard/clean-urls.json").bufferedReader().use { load(it.readText()) } }
        loaded = true
    }

    fun load(json: String) {
        brave = Json.parseToJsonElement(json).jsonArray.map { e ->
            val o = e.jsonObject
            fun list(key: String) = o[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            Rule(list("include").map(::matchPattern), list("exclude").map(::matchPattern), list("params").toSet())
        }
        loaded = true
    }

    /**
     * A browser match pattern from Brave's list as a regex over the whole URL: a star for the scheme
     * means http or https, a star-dot before the host means the host or any subdomain, and a star in
     * the path means anything (the path includes the query, as in the browser's matching).
     */
    fun matchPattern(p: String): Regex {
        val scheme = p.substringBefore("://")
        val rest = p.substringAfter("://")
        val host = rest.substringBefore('/')
        val path = "/" + rest.substringAfter('/', "")
        val s = if (scheme == "*") "https?" else Regex.escape(scheme)
        val h = when {
            host == "*" -> "[^/]*"
            host.startsWith("*.") -> "([^/]*\\.)?" + Regex.escape(host.substring(2))
            else -> Regex.escape(host)
        }
        val pa = path.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex("^$s://$h$pa$", RegexOption.IGNORE_CASE)
    }

    // DogBoard's own, for what Brave's list lacks or to be safe without it (tests run without assets)
    private val TRACKING = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
        "igshid", "igsh", "li_fat_id", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id",
        "oly_enc_id", "vero_id", "rb_clickid", "s_cid", "__s", "ref_src", "ref_url",
    )

    /** Share tags that only some sites add (on other sites the same name may matter, like YouTube's t). */
    private val PER_SITE = mapOf(
        "youtube.com" to setOf("si", "feature", "pp"),
        "youtu.be" to setOf("si", "feature"),
        "open.spotify.com" to setOf("si"),
        "x.com" to setOf("s", "t"),
        "twitter.com" to setOf("s", "t"),
        "wikipedia.org" to setOf("wprov"),
        "reddit.com" to setOf("share_id", "rdt"),
        "tiktok.com" to setOf("_r", "_t", "is_from_webapp", "sender_device"),
        // Pinterest's long links after a share (not in Brave's list)
        "pinterest.com" to setOf("invite_code", "sender", "sfo"),
    )
    // Short links (vm.tiktok.com/..., pin.it/..., a.co/...) carry the share in the code itself; only
    // asking the shortener gives the real link (ShortLinks, opt-in).

    /**
     * Pages that work without any query: all of it goes. Instagram posts and reels; TikTok videos and
     * photos, whose share links carry a long, changing set of tags (_r, _t, share_link_id, u_code,
     * user_id, sender_device...; "it expands to even more tracking parameters").
     * Other pages of these sites (a search's ?q=) keep their query.
     */
    private val NO_QUERY = mapOf(
        "instagram.com" to Regex("^/(p|reel|reels|tv)/"),
        "tiktok.com" to Regex("^/@[^/]+/(video|photo)/|^/v/"),
    )

    /** Amazon shops (amazon.de, amazon.co.uk, ...): the product page alone, without /ref=... or the query. */
    private val AMAZON = Regex("""^(.+\.)?amazon\.[a-z.]+$""")

    private val URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    /** [text] with the trackers removed from each of its links. */
    fun clean(text: String): String = URL.replace(text) { m -> cleanUrl(m.value) }

    fun cleanUrl(url: String): String {
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase().removePrefix("www.").removePrefix("m.")
        if (AMAZON.matches(host)) {
            val base = url.substringBefore('?').substringBefore('#')
            val path = base.substringAfter("://").substringAfter('/', "")
            val kept = path.split('/').filter { !it.startsWith("ref=") }.joinToString("/")
            return base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + (if (kept.isEmpty()) "" else "/$kept")
        }
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val path = "/" + url.substringAfter("://").substringAfter('/', "").substringBefore('?')
        if (NO_QUERY.any { (site, pages) -> (host == site || host.endsWith(".$site")) && pages.containsMatchIn(path) }) {
            return url.substring(0, q) + url.substring(hash)
        }
        val siteTags = PER_SITE.entries.filter { (site, _) -> host == site || host.endsWith(".$site") }.flatMap { it.value }.toSet()
        val braveTags = brave.filter { r -> r.include.any { it.matches(url) } && r.exclude.none { it.matches(url) } }.flatMap { it.params }.toSet()
        val kept = url.substring(q + 1, hash).split('&').filter { p ->
            val name = p.substringBefore('=')
            val lower = name.lowercase()
            p.isNotEmpty() && !lower.startsWith("utm_") && lower !in TRACKING && lower !in siteTags && name !in braveTags
        }
        return url.substring(0, q) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + url.substring(hash)
    }
}
