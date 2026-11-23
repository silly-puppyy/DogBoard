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
    )

    /** 3: short links followed first, platform oEmbed endpoints, TikTok's page data, PNG icons (2026-11-21: TikTok showed its generic page title). */
    private const val VERSION = 3

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
            val target = if (expand) resolve(url) else null
            val page = target ?: url
            var info = Info(url, target = target?.takeIf { it != url })
            if (previews) info = withPreview(info, page)
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
        fun fromOembed(o: String) = runCatching {
            val obj = json.parseToJsonElement(read(o, 64 * 1024) ?: return@runCatching).let { it as kotlinx.serialization.json.JsonObject }
            fun s(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            // a post (X, Bluesky) has no title: its text, from the embed's first paragraph, and its author
            val text = s("html")?.let(::postText)
            title = s("title") ?: text
            site = if (s("title") == null && text != null) s("author_name") ?: s("provider_name") else s("provider_name")
            image = s("thumbnail_url")
            duration = (obj["duration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
        }
        // the platforms' own oEmbed answers (title or post text, author, frame); the page for the rest
        oembedFor(page)?.let(::fromOembed)
        // 1 MB: video pages put the length late (YouTube's at ~650 KB, 2026-11-20)
        val html = read(page, 1024 * 1024, ua)
        // TikTok: its oEmbed now answers with the player only; the page's own data has the caption
        if (tiktok && html != null) tiktokItem(html)?.let { v ->
            title = v.caption ?: title; site = v.author ?: site; image = v.cover ?: image; duration = v.seconds ?: duration
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
        val key = Integer.toHexString(info.url.hashCode())
        val banner = image?.let { runCatching { URL(URL(page), it).toString() }.getOrNull() }?.let { saveImage(it, "$key.jpg", 480) }
        // the page's icons, PNG ones first (a .ico doesn't decode everywhere), then the real host's favicon.ico
        val icons = (html?.let { iconHrefs(it.substringBefore("</head>")) }.orEmpty().mapNotNull { runCatching { URL(URL(page), it).toString() }.getOrNull() } +
            "https://${URL(page).host}/favicon.ico").distinct()
        val icon = icons.asSequence().take(4).mapNotNull { saveImage(it, "$key-icon.png", 128) }.firstOrNull()
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
