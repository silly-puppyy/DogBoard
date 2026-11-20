package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
    test("the site's icon link") {
        LinkInfo.iconHref(head) shouldBe "/favicon-32.png"
        LinkInfo.iconHref("""<link href="/a.ico" rel="shortcut icon">""") shouldBe "/a.ico"
    }
    test("a video's ISO 8601 length") {
        LinkInfo.isoSeconds("PT4M13S") shouldBe 253
        LinkInfo.isoSeconds("PT1H2M") shouldBe 3720
        LinkInfo.isoSeconds("P0D") shouldBe null
    }
    test("the first link in a text") {
        LinkInfo.linkIn("look https://vm.tiktok.com/ZM123/ ok") shouldBe "https://vm.tiktok.com/ZM123/"
    }
})
