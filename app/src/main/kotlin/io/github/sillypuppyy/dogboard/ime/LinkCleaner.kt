package io.github.sillypuppyy.dogboard.ime

/**
 * Settings › Clipboard › "Strip trackers from links" (opt-in, user 2026-11-17): the tracking tags in
 * the links of a text DogBoard pastes are removed. Only tags that identify a click or a campaign,
 * never ones a page needs: the widely known ones (utm_*, Facebook's fbclid, Google's gclid and its
 * kin, Microsoft's msclkid, Mailchimp, HubSpot, Marketo and others), and share tags on the sites that
 * add them (si on YouTube and Spotify, s and t on X). Links stay as they are otherwise: the order of
 * the remaining parameters, the fragment, and anything that isn't a link.
 */
object LinkCleaner {
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
        "instagram.com" to setOf("img_index"),
        "reddit.com" to setOf("share_id", "rdt"),
        "tiktok.com" to setOf("_r", "_t", "is_from_webapp", "sender_device"),
    )

    private val URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    /** [text] with the trackers removed from each of its links. */
    fun clean(text: String): String = URL.replace(text) { m -> cleanUrl(m.value) }

    fun cleanUrl(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#', q).let { if (it < 0) url.length else it }
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase().removePrefix("www.").removePrefix("m.")
        val siteTags = PER_SITE.entries.firstOrNull { (site, _) -> host == site || host.endsWith(".$site") }?.value.orEmpty()
        val kept = url.substring(q + 1, hash).split('&').filter { p ->
            val name = p.substringBefore('=').lowercase()
            p.isNotEmpty() && !name.startsWith("utm_") && name !in TRACKING && name !in siteTags
        }
        return url.substring(0, q) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + url.substring(hash)
    }
}
