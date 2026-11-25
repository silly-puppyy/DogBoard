package io.github.sillypuppyy.dogboard.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * What DogBoard learns about a copied link ("request a page, if it redirects use that, then
 * remove trackers; pull the SEO off the website"): where it really leads (redirects followed, Settings ›
 * Clipboard › Expand links) and, with Settings › Clipboard › Link previews, its title, site name, banner,
 * site icon and a video's length. One fetch per link, no cookies, nothing sent but the request; kept in
 * files (`dogboard/links/`) so the clipboard rows keep their previews.
 */
object LinkInfo {
    @Serializable
    data class Info(
        val url: String,
        /** Where the link leads after redirects, when that differs. */
        val target: String? = null,
        val title: String? = null,
        val site: String? = null,
        /** Seconds, for a video. */
        val duration: Int? = null,
        /** File names in [dir] of the banner (or video frame) and the site's icon. */
        val image: String? = null,
        val icon: String? = null,
        val previews: Boolean = false,
        /** [VERSION] of the code that read the preview: older ones are read again. */
        val version: Int = 0,
        /** When the page couldn't be read at all (no network, a timeout): asked again after [RETRY_MS]. */
        val failedAt: Long = 0,
    )

    /** A failed preview is asked again this long after (on copy or when the clipboard opens), not on every open. */
    private const val RETRY_MS = 10 * 60 * 1000L

    /** 3: short links followed first, platform oEmbed endpoints, TikTok's page data, PNG icons (2026-11-21: TikTok showed its generic page title). */
    private const val VERSION = 5 // one "platform, author" line; GitHub, Wikipedia, Steam (2026-11-24)

    private val json = Json { ignoreUnknownKeys = true }
    private val infos = ConcurrentHashMap<String, Info>()
    /** Bumped whenever a link's info arrives, so the clipboard panel redraws its rows. */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    private var dir: File? = null
    private val URL_RE = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    /** TikTok gives its video data (caption, author, cover, length) only to a desktop browser (2026-11-21). */
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"

    @Synchronized
    fun load(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "dogboard/links").apply { mkdirs() }
        dir = d
        File(d, "links.json").takeIf { it.exists() }?.let { f ->
            runCatching { json.decodeFromString<List<Info>>(f.readText()) }.getOrNull()?.forEach { infos[it.url] = it }
        }
    }

    fun get(url: String): Info? = infos[url]

    /** The first link in [text], if any. */
    fun linkIn(text: String): String? = URL_RE.find(text)?.value

    fun imageFile(name: String?): File? = name?.let { n -> dir?.let { File(it, n) } }?.takeIf { it.exists() }

    /**
     * Learns about the links in [text] (off the main thread): their targets when [expand], their previews
     * when [previews]; a link already known at that level is not asked again.
     */
    fun fetchAll(context: Context, text: String, expand: Boolean, previews: Boolean) {
        if (!expand && !previews) return
        load(context)
        for (m in URL_RE.findAll(text)) {
            val url = m.value
            val known = infos[url]
            if (known != null && ((known.previews && known.version >= VERSION) || !previews)) continue
            if (known != null && known.failedAt > System.currentTimeMillis() - RETRY_MS) continue
            val target = if (expand) resolve(url) else null
            val page = target ?: url
            var info = Info(url, target = target?.takeIf { it != url })
            if (previews) {
                // the expanded (and so cleaned) link is shown as soon as it is known, the preview when it arrives: the
                // page, its oEmbed and its pictures took seconds, and the expanded link waited for them all
                if (expand && known?.target == null) { infos[url] = info; changes.value++ }
                info = withPreview(info, page)
            }
            infos[url] = info
            save()
            changes.value++
        }
    }

    /** Follows redirects (at most 5, each a GET without cookies); the last address, or null if it doesn't move. */
    internal fun resolve(url: String, ua: String = UA): String? {
        var u = url
        repeat(5) {
            val next = runCatching {
                open(u, ua).let { c ->
                    try {
                        if (c.responseCode in 300..399) c.getHeaderField("Location")?.let { URL(URL(u), it).toString() } else null
                    } finally { c.disconnect() }
                }
            }.getOrNull() ?: return u.takeIf { it != url }
            u = next
        }
        return u.takeIf { it != url }
    }

    private fun open(u: String, ua: String = UA): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        useCaches = false
        connectTimeout = 5000
        readTimeout = 5000
        setRequestProperty("User-Agent", ua)
        setRequestProperty("Accept-Language", "en")
    }

    private fun withPreview(info: Info, link: String): Info {
        // a short link first leads to the real page: the platforms' oEmbed endpoints refuse short links
        // ("vm.tiktok.com/..." gave TikTok's generic "Make Your Day" page) and the icon lives on the real host
        val tiktok = isTikTok(link) || info.target?.let(::isTikTok) == true
        val ua = if (tiktok) DESKTOP_UA else UA
        val page = info.target ?: resolve(link, ua) ?: link
        val host = runCatching { URL(page).host.removePrefix("www.").lowercase() }.getOrNull() ?: return info.copy(previews = true, version = VERSION)
        var title: String? = null; var site: String? = null; var image: String? = null; var duration: Int? = null
        // who made it (channel, account) and what else is worth a word (members, stars, price): the line under the title
        // reads "<platform>, <that>" the same way for every platform
        var author: String? = null; var extra: String? = null
        fun fromOembed(o: String) = runCatching {
            val obj = json.parseToJsonElement(read(o, 64 * 1024) ?: return@runCatching).let { it as kotlinx.serialization.json.JsonObject }
            fun s(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            // a post (X, Bluesky) has no title: its text, from the embed's first paragraph, and its author
            val text = s("html")?.let(::postText)
            title = s("title") ?: text
            site = s("provider_name")
            author = s("author_name")
            image = s("thumbnail_url")
            duration = (obj["duration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
        }
        // the platforms' own oEmbed answers (title or post text, author, frame); the page for the rest
        oembedFor(page)?.let(::fromOembed)
        // a Discord invite: the server's name, size, banner and icon from Discord's public invite endpoint
        var serverIcon: String? = null
        val invite = discordInviteCode(link) ?: discordInviteCode(page)
        if (invite != null) runCatching {
            val obj = json.parseToJsonElement(read("https://discord.com/api/v10/invites/$invite?with_counts=true", 64 * 1024) ?: return@runCatching) as kotlinx.serialization.json.JsonObject
            fun str(o: kotlinx.serialization.json.JsonElement?, k: String) = ((o as? kotlinx.serialization.json.JsonObject)?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            val g = obj["guild"]; val id = str(g, "id") ?: return@runCatching
            title = str(g, "name")
            val members = (obj["approximate_member_count"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            extra = members?.let { java.text.NumberFormat.getIntegerInstance().format(it) + " members" }
            image = str(g, "banner")?.let { "https://cdn.discordapp.com/banners/$id/$it.png?size=480" } ?: str(g, "splash")?.let { "https://cdn.discordapp.com/splashes/$id/$it.png?size=480" }
            serverIcon = str(g, "icon")?.let { "https://cdn.discordapp.com/icons/$id/$it.png?size=128" }
        }
        // public endpoints with more than the page says: a repository's stars, an article's summary, a game's price
        targeted(page)?.let { t -> title = t.title ?: title; image = t.image ?: image; extra = t.extra ?: extra; if (t.icon != null) serverIcon = t.icon }
        // 1 MB: video pages put the length late (YouTube's at ~650 KB, 2026-11-20); a Discord invite needs no page
        val html = if (invite != null && title != null) null else read(page, 1024 * 1024, ua)
        // TikTok: its oEmbed now answers with the player only; the page's own data has the caption
        if (tiktok && html != null) tiktokItem(html)?.let { v ->
            title = v.caption ?: title; author = v.author ?: author; image = v.cover ?: image; duration = v.seconds ?: duration
        }
        if (html != null) {
            val head = html.substringBefore("</head>")
            // any other site that offers oEmbed says so in its head
            if (title == null) oembedLink(head)?.let { runCatching { URL(URL(page), it).toString() }.getOrNull() }?.let(::fromOembed)
            title = title ?: meta(head, "og:title") ?: meta(head, "twitter:title") ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(head)?.groupValues?.get(1)?.let(::unescape)?.trim()
            site = site ?: meta(head, "og:site_name")
            image = image ?: meta(head, "og:image") ?: meta(head, "twitter:image")
            duration = duration ?: meta(head, "og:video:duration")?.toIntOrNull() ?: meta(head, "video:duration")?.toIntOrNull()
                ?: Regex("""itemprop=["']duration["'][^>]*content=["']([^"']+)""").find(html)?.groupValues?.get(1)?.let(::isoSeconds)
                ?: Regex(""""lengthSeconds":"(\d+)"""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        }
        platformOf(page)?.let { p -> site = listOfNotNull(p, extra ?: subredditOf(page) ?: author).joinToString(", ") }
        val key = Integer.toHexString(info.url.hashCode())
        val banner = image?.let { runCatching { URL(URL(page), it).toString() }.getOrNull() }?.let { saveImage(it, "$key.jpg", 480) }
        // the page's icons, PNG ones first (a .ico doesn't decode everywhere), then the real host's favicon.ico
        val icons = (listOfNotNull(serverIcon) + html?.let { iconHrefs(it.substringBefore("</head>")) }.orEmpty().mapNotNull { runCatching { URL(URL(page), it).toString() }.getOrNull() } +
            "https://${URL(page).host}/favicon.ico").distinct()
        val icon = icons.asSequence().take(4).mapNotNull { saveImage(it, "$key-icon.png", 128) }.firstOrNull()
        // nothing read at all (no network, a timeout, the site down): not a preview, asked again later; a page that answered
        // without a title keeps what it gave (a failed fetch was kept as done, so its link never got a preview, 2026-11-25)
        if (html == null && title == null && banner == null) return info.copy(failedAt = System.currentTimeMillis())
        return info.copy(title = title?.take(200), site = site?.take(80), duration = duration, image = banner, icon = icon, previews = true, version = VERSION)
    }

    private fun read(u: String, max: Int, ua: String = UA): String? = runCatching {
        var url = u
        repeat(5) {
            val c = open(url, ua)
            try {
                when (c.responseCode) {
                    in 300..399 -> url = c.getHeaderField("Location")?.let { URL(URL(url), it).toString() } ?: return@runCatching null
                    in 200..299 -> {
                        val bytes = c.inputStream.use { s -> readAtMost(s, max) }
                        val cs = c.contentType?.substringAfter("charset=", "")?.trim()?.takeIf { it.isNotEmpty() } ?: "UTF-8"
                        return@runCatching String(bytes, runCatching { charset(cs) }.getOrDefault(Charsets.UTF_8))
                    }
                    else -> return@runCatching null
                }
            } finally { c.disconnect() }
        }
        null
    }.getOrNull()

    /** Downloads an image (at most 3 MB), scaled to at most [maxPx] wide, into [dir]; its file name or null. */
    private fun saveImage(u: String, name: String, maxPx: Int): String? = runCatching {
        val d = dir ?: return null
        val c = open(u)
        val bytes = try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { readAtMost(it, 3 * 1024 * 1024) }
        } finally { c.disconnect() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        File(d, name).outputStream().use { out ->
            bmp.compress(if (name.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, out)
        }
        bmp.recycle()
        name
    }.getOrNull()

    /** Up to [max] bytes of [s] (InputStream.readNBytes is Android 13+). */
    private fun readAtMost(s: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Synchronized
    private fun save() {
        val d = dir ?: return
        // the most recent 300 links
        val list = infos.values.toList().takeLast(300)
        runCatching { File(d, "links.json").writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Info.serializer()), list)) }
    }

    /** Forgets every link and its images (Settings › Clipboard, or turning previews off). */
    @Synchronized
    fun clear() {
        infos.clear()
        dir?.listFiles()?.forEach { it.delete() }
        changes.value++
    }

    internal fun meta(head: String, key: String): String? {
        val k = Regex.escape(key)
        return (Regex("""(?is)<meta[^>]+(?:property|name)=["']$k["'][^>]*content=["']([^"']*)""").find(head)
            ?: Regex("""(?is)<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']$k["']""").find(head))
            ?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The page's icon links, apple-touch-icon (PNG) and .png icons first. */
    internal fun iconHrefs(head: String): List<String> =
        Regex("""(?is)<link\b[^>]*>""").findAll(head).map { it.value }
            .filter { Regex("""(?i)rel=["'](?:shortcut icon|icon|apple-touch-icon(?:-precomposed)?)["']""").containsMatchIn(it) }
            .mapNotNull { Regex("""(?i)href=["']([^"']+)""").find(it)?.groupValues?.get(1)?.let(::unescape) }
            .sortedBy { if ("apple-touch" in it || it.substringBefore('?').endsWith(".png", ignoreCase = true)) 0 else 1 }
            .toList()

    /** The platform's public oEmbed endpoint for [page] (no key needed), or null. */
    /** The code of a Discord invite link (discord.gg/abc, discord.com/invite/abc), or null. */
    internal fun discordInviteCode(url: String): String? {
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        val host = u.host.lowercase().removePrefix("www.")
        val path = u.path.trim('/').split('/')
        val code = when {
            host == "discord.gg" -> path.firstOrNull()
            (host == "discord.com" || host == "discordapp.com") && path.firstOrNull() == "invite" -> path.getOrNull(1)
            else -> null
        }
        return code?.takeIf { Regex("[A-Za-z0-9-]{2,40}").matches(it) }
    }

    /** The platforms named the same way everywhere (their oEmbed names differ: "reddit", "YouTube"). */
    private val PLATFORMS = mapOf(
        "youtube.com" to "YouTube", "youtu.be" to "YouTube", "music.youtube.com" to "YouTube Music", "tiktok.com" to "TikTok",
        "x.com" to "X", "twitter.com" to "X", "reddit.com" to "Reddit", "old.reddit.com" to "Reddit", "new.reddit.com" to "Reddit",
        "vimeo.com" to "Vimeo", "open.spotify.com" to "Spotify", "soundcloud.com" to "SoundCloud", "bsky.app" to "Bluesky",
        "discord.gg" to "Discord", "discord.com" to "Discord", "github.com" to "GitHub", "store.steampowered.com" to "Steam",
        "twitch.tv" to "Twitch", "instagram.com" to "Instagram",
    )

    internal fun platformOf(url: String): String? = runCatching {
        val h = URL(url).host.lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        PLATFORMS[h] ?: if (h.startsWith("pinterest.")) "Pinterest" else if (h.endsWith(".wikipedia.org")) "Wikipedia" else null
    }.getOrNull()

    /** A Reddit link's community ("r/Android"), shown instead of the poster. */
    internal fun subredditOf(url: String): String? {
        if (platformOf(url) != "Reddit") return null
        val path = runCatching { URL(url).path }.getOrNull() ?: return null
        return Regex("^/r/([A-Za-z0-9_]+)").find(path)?.groupValues?.get(1)?.let { "r/$it" }
    }

    data class Targeted(val title: String?, val extra: String?, val image: String?, val icon: String? = null)

    /** GitHub repositories, Wikipedia articles and Steam games, from their public endpoints. */
    private fun targeted(page: String): Targeted? = runCatching {
        val u = URL(page); val host = u.host.lowercase().removePrefix("www.").removePrefix("m.")
        val path = u.path.trim('/').split('/').filter { it.isNotEmpty() }
        fun obj(url: String): kotlinx.serialization.json.JsonObject? = read(url, 256 * 1024)?.let { json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }
        fun str(o: kotlinx.serialization.json.JsonElement?, k: String) = ((o as? kotlinx.serialization.json.JsonObject)?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        fun num(o: kotlinx.serialization.json.JsonElement?, k: String) = ((o as? kotlinx.serialization.json.JsonObject)?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        val fmt = java.text.NumberFormat.getIntegerInstance()
        when {
            // the repository's own page only: an issue, pull request or file keeps its page's title
            host == "github.com" && path.size == 2 && path[0] !in setOf("orgs", "settings", "topics", "search", "marketplace") -> {
                val r = obj("https://api.github.com/repos/${path[0]}/${path[1]}") ?: return null
                val stars = num(r, "stargazers_count")
                Targeted(str(r, "full_name")?.let { n -> str(r, "description")?.let { "$n: $it" } ?: n }, stars?.let { fmt.format(it) + " stars" }, null,
                    str(r["owner"], "avatar_url"))
            }
            host.endsWith(".wikipedia.org") && path.size >= 2 && path[0] == "wiki" -> {
                val w = obj("https://$host/api/rest_v1/page/summary/${path[1]}") ?: return null
                Targeted(str(w, "title"), str(w, "description"), str(w["thumbnail"], "source"))
            }
            host == "store.steampowered.com" && path.size >= 2 && path[0] == "app" -> {
                val id = path[1].takeIf { it.all(Char::isDigit) } ?: return null
                val d = (obj("https://store.steampowered.com/api/appdetails?appids=$id&filters=basic,price_overview")?.get(id) as? kotlinx.serialization.json.JsonObject)?.get("data")
                val free = ((d as? kotlinx.serialization.json.JsonObject)?.get("is_free") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                Targeted(str(d, "name"), if (free) "free" else str((d as? kotlinx.serialization.json.JsonObject)?.get("price_overview"), "final_formatted"), str(d, "header_image"))
            }
            else -> null
        }
    }.getOrNull()

    internal fun oembedFor(page: String): String? {
        val host = runCatching { URL(page).host.lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.") }.getOrNull() ?: return null
        val u = java.net.URLEncoder.encode(page, "UTF-8")
        return when {
            host == "youtube.com" || host == "youtu.be" || host == "music.youtube.com" -> "https://www.youtube.com/oembed?format=json&url=$u"
            host == "tiktok.com" -> "https://www.tiktok.com/oembed?url=$u"
            host == "vimeo.com" -> "https://vimeo.com/api/oembed.json?url=$u"
            host == "x.com" || host == "twitter.com" -> "https://publish.twitter.com/oembed?omit_script=1&dnt=true&url=$u"
            host == "reddit.com" || host == "old.reddit.com" || host == "new.reddit.com" -> "https://www.reddit.com/oembed?url=$u"
            host == "open.spotify.com" -> "https://open.spotify.com/oembed?url=$u"
            host == "soundcloud.com" -> "https://soundcloud.com/oembed?format=json&url=$u"
            host == "pinterest.com" || host.startsWith("pinterest.") -> "https://www.pinterest.com/oembed.json?url=$u"
            host == "bsky.app" -> "https://embed.bsky.app/oembed?url=$u"
            else -> null
        }
    }

    internal fun isTikTok(url: String): Boolean =
        runCatching { URL(url).host.lowercase().let { it == "tiktok.com" || it.endsWith(".tiktok.com") } }.getOrDefault(false)

    data class TikTokItem(val caption: String?, val author: String?, val cover: String?, val seconds: Int?)

    /** A TikTok video page's own data (script __UNIVERSAL_DATA_FOR_REHYDRATION__, webapp.video-detail). */
    internal fun tiktokItem(html: String): TikTokItem? = runCatching {
        val data = Regex("""(?s)<script[^>]+id=["']__UNIVERSAL_DATA_FOR_REHYDRATION__["'][^>]*>(.*?)</script>""").find(html)?.groupValues?.get(1) ?: return null
        fun obj(e: kotlinx.serialization.json.JsonElement?, k: String) = (e as? kotlinx.serialization.json.JsonObject)?.get(k)
        fun str(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val item = listOf("__DEFAULT_SCOPE__", "webapp.video-detail", "itemInfo", "itemStruct").fold(json.parseToJsonElement(data) as kotlinx.serialization.json.JsonElement?) { e, k -> obj(e, k) } ?: return null
        val video = obj(item, "video")
        TikTokItem(str(obj(item, "desc")), str(obj(obj(item, "author"), "nickname")), str(obj(video, "cover")),
            (obj(video, "duration") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 })
    }.getOrNull()

    /** A JSON oEmbed endpoint the page advertises in its head. */
    internal fun oembedLink(head: String): String? =
        Regex("""(?is)<link\b[^>]*>""").findAll(head).map { it.value }
            .firstOrNull { Regex("""(?i)type=["']application/json\+oembed["']""").containsMatchIn(it) }
            ?.let { Regex("""(?i)href=["']([^"']+)""").find(it)?.groupValues?.get(1) }?.let(::unescape)

    /** The text of a post's embed (its first paragraph), tags removed. */
    internal fun postText(html: String): String? =
        Regex("""(?is)<p\b[^>]*>(.*?)</p>""").find(html)?.groupValues?.get(1)
            ?.replace(Regex("""(?i)<br\s*/?>"""), " ")?.replace(Regex("<[^>]+>"), "")?.let(::unescape)
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** "PT4M13S" -> 253. */
    internal fun isoSeconds(s: String): Int? {
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(s) ?: return null
        val (d, h, mi, se) = m.destructured
        return ((d.toIntOrNull() ?: 0) * 86400 + (h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (se.toIntOrNull() ?: 0)).takeIf { it > 0 }
    }

    internal fun unescape(s: String): String = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
}
