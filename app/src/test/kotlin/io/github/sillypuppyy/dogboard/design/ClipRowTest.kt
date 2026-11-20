package io.github.sillypuppyy.dogboard.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The clipboard rows' kinds for live clips (catalogue, canvas "Clipboard: columns vs rows"). */
class ClipRowTest : FunSpec({
    fun text(t: String, pinned: Boolean = false) = clipRow(KbClipData(media = false, icon = "", label = "", text = t, pinned = pinned))

    test("a link: the site's initial, a page name read from the address, the site below") {
        val r = text("https://news.example.com/night-trains-are-coming-back?utm_source=x")
        r.leadLetter shouldBe "n"
        r.title shouldBe "Night trains are coming back"
        r.sub shouldBe "news.example.com"
        r.action shouldBe "Open link"
    }
    test("a link without a readable page name shows its address") {
        text("https://example.com/a1b2c3").title shouldBe "example.com/a1b2c3"
        pageName("/watch?v=abc") shouldBe null
        pageName("/recipes/lemon_drizzle-cake.html") shouldBe "Lemon drizzle cake"
    }
    test("email, phone and one-time code get their icons") {
        text("sam@example.com").leadIcon shouldBe "mail"
        text("+44 20 7946 0958").leadIcon shouldBe "call"
        text("482 913").leadIcon shouldBe "pin"
        text("482913").sub shouldBe "One-time code"
    }
    test("a colour: swatch and formats, the copied one picked") {
        val r = text("rgb(61, 123, 245)")
        r.formats shouldBe listOf("HEX", "RGB", "HSL")
        r.format shouldBe 1
        r.sub shouldBe "Colour"
    }
    test("plain text: up to two lines, runs of spaces folded, pin kept") {
        val r = text("See  you\nat 7", pinned = true)
        r.title shouldBe "See you at 7"
        r.titleLines shouldBe 2
        r.pinned shouldBe true
        r.leadIcon shouldBe ""
    }
    test("the phone's own photos and videos fill the row") {
        clipRow(KbClipData(media = true, icon = "image", label = "Image", text = "", pinned = false)).window shouldBe true
        clipRow(KbClipData(media = true, icon = "play_circle", label = "Video", text = "", pinned = false)).video shouldBe true
    }
})
