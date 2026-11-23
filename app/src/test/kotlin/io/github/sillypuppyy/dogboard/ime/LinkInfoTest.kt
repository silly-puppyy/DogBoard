package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class LinkInfoTest : FunSpec({
    val head = """
        <head><title>Fallback &amp; title</title>
        <meta property="og:title" content="Night trains are coming back &#8211; across Europe">
        <meta content="News Example" property="og:site_name" />
        <meta name="twitter:image" content="/img/banner.jpg">
        <link rel="icon" href="/favicon-32.png" sizes="32x32">
    """.trimIndent()

    test("Open Graph tags in either attribute order, entities decoded") {
        LinkInfo.meta(head, "og:title") shouldBe "Night trains are coming back – across Europe"
        LinkInfo.meta(head, "og:site_name") shouldBe "News Example"
        LinkInfo.meta(head, "twitter:image") shouldBe "/img/banner.jpg"
        LinkInfo.meta(head, "og:image") shouldBe null
    }
    test("the site's icon links, PNG ones first") {
        LinkInfo.iconHrefs(head) shouldBe listOf("/favicon-32.png")
        LinkInfo.iconHrefs("""<link href="/a.ico" rel="shortcut icon">""") shouldBe listOf("/a.ico")
        LinkInfo.iconHrefs("""<link rel="icon" href="https://www.tiktok.com/favicon.ico"><link rel="apple-touch-icon" href="/touch.png">""") shouldBe
            listOf("/touch.png", "https://www.tiktok.com/favicon.ico")
    }
    test("each platform's public oEmbed endpoint, from the full page address") {
        LinkInfo.oembedFor("https://www.tiktok.com/@someone/video/123")!! shouldStartWith "https://www.tiktok.com/oembed?url="
        LinkInfo.oembedFor("https://x.com/someone/status/1")!! shouldStartWith "https://publish.twitter.com/oembed?"
        LinkInfo.oembedFor("https://mobile.twitter.com/someone/status/1")!! shouldStartWith "https://publish.twitter.com/oembed?"
        LinkInfo.oembedFor("https://m.youtube.com/watch?v=abc")!! shouldStartWith "https://www.youtube.com/oembed?"
        LinkInfo.oembedFor("https://old.reddit.com/r/x/comments/1/")!! shouldStartWith "https://www.reddit.com/oembed?"
        LinkInfo.oembedFor("https://open.spotify.com/track/1")!! shouldStartWith "https://open.spotify.com/oembed?"
        LinkInfo.oembedFor("https://bsky.app/profile/a/post/1")!! shouldStartWith "https://embed.bsky.app/oembed?"
        LinkInfo.oembedFor("https://www.pinterest.co.uk/pin/1/")!! shouldStartWith "https://www.pinterest.com/oembed.json?"
        LinkInfo.oembedFor("https://example.com/") shouldBe null
    }
    test("an oEmbed endpoint the page advertises") {
        LinkInfo.oembedLink("""<link rel="alternate" type="application/json+oembed" href="https://site.example/oembed?url=a&amp;format=json">""") shouldBe
            "https://site.example/oembed?url=a&format=json"
        LinkInfo.oembedLink(head) shouldBe null
    }
    test("a TikTok page's own video data") {
        val page = """<html><head><title>TikTok - Make Your Day</title></head><body><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">""" +
            """{"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"desc":"Scramble up ur name #fyp","author":{"nickname":"Scout"},""" +
            """"video":{"cover":"https://p16.example/cover.jpg","duration":10}}},"statusCode":0}}}</script></body></html>"""
        LinkInfo.tiktokItem(page) shouldBe LinkInfo.TikTokItem("Scramble up ur name #fyp", "Scout", "https://p16.example/cover.jpg", 10)
        LinkInfo.tiktokItem("<html></html>") shouldBe null
        LinkInfo.isTikTok("https://vm.tiktok.com/ZM1/") shouldBe true
        LinkInfo.isTikTok("https://example.com/tiktok.com") shouldBe false
    }
    test("a post's text from its embed") {
        LinkInfo.postText("""<blockquote class="twitter-tweet"><p lang="en" dir="ltr">Happy 50th anniversary<br>to the <a href="x">Wilderness Act</a>! Here&#39;s</p>&mdash; Someone</blockquote>""") shouldBe
            "Happy 50th anniversary to the Wilderness Act! Here's"
        LinkInfo.postText("<iframe src=\"x\"></iframe>") shouldBe null
    }
    test("a video's ISO 8601 length") {
        LinkInfo.isoSeconds("PT4M13S") shouldBe 253
        LinkInfo.isoSeconds("PT1H2M") shouldBe 3720
        LinkInfo.isoSeconds("P0D") shouldBe null
    }
    test("the first link in a text") {
        LinkInfo.linkIn("look https://vm.tiktok.com/ZM123/ ok") shouldBe "https://vm.tiktok.com/ZM123/"
    }
    test("Discord invite codes") {
        LinkInfo.discordInviteCode("https://discord.gg/discord-developers") shouldBe "discord-developers"
        LinkInfo.discordInviteCode("https://discord.com/invite/AbC123?event=1") shouldBe "AbC123"
        LinkInfo.discordInviteCode("https://discord.com/channels/1/2") shouldBe null
    }
    test("platforms named the same way, a Reddit link's community") {
        LinkInfo.platformOf("https://www.reddit.com/r/Android/comments/abc/") shouldBe "Reddit"
        LinkInfo.platformOf("https://youtu.be/xyz") shouldBe "YouTube"
        LinkInfo.platformOf("https://de.m.wikipedia.org/wiki/Hund".replace("de.m.", "de.")) shouldBe "Wikipedia"
        LinkInfo.platformOf("https://example.com/") shouldBe null
        LinkInfo.subredditOf("https://www.reddit.com/r/Android/comments/abc/") shouldBe "r/Android"
        LinkInfo.subredditOf("https://youtube.com/r/x") shouldBe null
    }
})
