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
    test("email by its form; phone, address and code only from the system classifier") {
        text("sam@example.com").leadIcon shouldBe "mail"
        fun typed(t: String, type: String?) = clipRow(KbClipData(media = false, icon = "", label = "", text = t, pinned = false, systemType = type))
        typed("+44 20 7946 0958", "phone").leadIcon shouldBe "call"
        typed("221 Baker Street, London", "address").action shouldBe "Open in Maps"
        typed("482 913", "otp").sub shouldBe "One-time code"
        // numbers alone are never guessed to be a code or a phone number
        text("482913").leadIcon shouldBe ""
        text("+44 20 7946 0958").leadIcon shouldBe ""
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

class ClipTypesFormTest : FunSpec({
    test("a phone number needs a phone's form, a bare number never is one") {
        io.github.sillypuppyy.dogboard.ime.ClipTypes.looksLikePhone("482913") shouldBe false
        io.github.sillypuppyy.dogboard.ime.ClipTypes.looksLikePhone("6502530000") shouldBe false
        io.github.sillypuppyy.dogboard.ime.ClipTypes.looksLikePhone("+1 650-253-0000") shouldBe true
        io.github.sillypuppyy.dogboard.ime.ClipTypes.looksLikePhone("(650) 253 0000") shouldBe true
        io.github.sillypuppyy.dogboard.ime.ClipTypes.looksLikePhone("12-34") shouldBe false
    }
})
