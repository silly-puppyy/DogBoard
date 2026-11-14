package io.github.sillypuppyy.dogboard.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/** Checks the port against values worked out by hand from Kb.dc.html's renderVals(). */
class KbSpecTest : FunSpec({
    fun keys(p: KbProps) = KbSpec.render(p).rows.flatMap { it.keys }.filter { !it.gap }
    fun key(p: KbProps, id: String) = keys(p).first { it.id == id }

    test("geometry: 375 x 216, 4 rows of 54, 10 units, caps 31.5 x 42 (Kb:437-451, 504-506)") {
        val r = KbSpec.render(KbProps())
        r.rowH shouldBe 54f
        r.rows[0].keys.size shouldBe 10
        (r.rows[0].keys[0].w - 2 * r.mh) shouldBe (31.5f plusOrMinus 0.001f)
        (r.rowH - 2 * r.mv) shouldBe 42f
        r.H shouldBe 296f // 48 bar + 216 keys + 32 nav (States:19)
    }
    test("DogBoard light colours (Kb:367)") {
        val t = KbSpec.render(KbProps()).t
        t.bg shouldBe css("#ededf4")
        t.key shouldBe css("#ffffff")
        t.fn shouldBe css("#dbe2f9")
        t.keyShadow shouldBe emptyList()
        t.linkFg shouldBe css("#435e91")
    }
    test("shift states (Kb:544-549)") {
        key(KbProps(shift = "off"), "shift").let { it.icon shouldBe "shift"; it.fill shouldBe 0; it.bg shouldBe css("#dbe2f9") }
        key(KbProps(shift = "on"), "shift").let { it.fill shouldBe 1; it.bg shouldBe css("#ffffff") }
        key(KbProps(shift = "auto"), "shift").let { it.fill shouldBe 1; it.bg shouldBe css("#dbe2f9") }
        key(KbProps(shift = "lock"), "shift").let { it.icon shouldBe "shift_lock"; it.bg shouldBe css("#435e91") }
    }
    test("return: words on primary 15/600, plain return icon on function tone, paw for Dog (Kb:553-555)") {
        key(KbProps(ret = "send"), "ret").let { it.label shouldBe "Send"; it.fs shouldBe 15f; it.fw shouldBe 600; it.bg shouldBe css("#435e91") }
        key(KbProps(), "ret").let { it.icon shouldBe "keyboard_return"; it.bg shouldBe css("#dbe2f9") }
        key(KbProps(theme = "dog"), "ret").let { it.icon shouldBe "pets"; it.fill shouldBe 1 }
    }
    test("action key: icon mode, and a word too wide for the cap shows the icon (Panels:56, 160-168)") {
        key(KbProps(ret = "done", actionStyle = "icon"), "ret").let { it.icon shouldBe "check"; it.label shouldBe ""; it.bg shouldBe css("#435e91") }
        key(KbProps(ret = "next", actionStyle = "icon"), "ret").icon shouldBe "keyboard_tab"
        key(KbProps(ret = "go", actionLabel = "Continue to checkout"), "ret").let { it.icon shouldBe "arrow_forward"; it.label shouldBe "" }
        key(KbProps(ret = "go", actionLabel = "Log in"), "ret").label shouldBe "Log in"
    }
    test("fn key is emoji by default, globe on request (Kb:552)") {
        key(KbProps(), "fn").icon shouldBe "mood"
        key(KbProps(fnKey = "globe"), "fn").icon shouldBe "language"
    }
    test("iOS style has no hints; Android style has number hints on the top row (Kb:534-537)") {
        keys(KbProps()).filter { it.hint.isNotEmpty() } shouldBe emptyList()
        KbSpec.render(KbProps(kstyle = "android")).rows[0].keys.map { it.hint } shouldContainExactly "1234567890".map { it.toString() }
        KbSpec.render(KbProps(kstyle = "android")).rows[1].keys.filter { !it.gap }.all { it.hint.isEmpty() } shouldBe true
        key(KbProps(hints = true), "e").hint shouldBe "è"
        key(KbProps(kstyle = "android", hints = true), "m").hint shouldBe "?"
    }
    test("Android-style bottom row (Kb:471) and labels") {
        KbSpec.render(KbProps(kstyle = "android")).rows[3].keys.map { it.id } shouldContainExactly listOf("mode-?123", ",", "fn", "space", ".", "ret")
        key(KbProps(kstyle = "android"), "space").label shouldBe ""
        key(KbProps(), "space").let { it.label shouldBe "space"; it.fs shouldBe 14f; it.fg shouldBe css("#1a1b20") }
    }
    test("url row and .com (Kb:475, 532)") {
        key(KbProps(field = "url"), ".com").let { it.fs shouldBe 16f; it.fw shouldBe 500 }
    }
    test("key preview geometry (Kb:574-575)") {
        val pv = KbSpec.render(KbProps(preview = "g")).pv!!
        pv.w shouldBe (31.5f + 18f plusOrMinus 0.001f)
        pv.y shouldBe (48f + 54f + 6f - 6f - 58f plusOrMinus 0.001f)
    }
    test("CSS shadow parsing") {
        CssShadow.parse("0 1px 3px rgba(0,0,0,0.16), 0 6px 18px rgba(0,0,0,0.14)").map { it.blur } shouldContainExactly listOf(3f, 18f)
        CssShadow.parse("inset 0 0 0 1.5px #8a8a8a").single().let { it.inset shouldBe true; it.spread shouldBe 1.5f }
    }
    test("theme swatches on the Themes page match the token table (Themes:97-106 vs Kb:365-394)") {
        // Ground, Keys, Function, Return, Chip
        val swatches = listOf(
            Triple("dogboard", false, "#ededf4 #ffffff #dbe2f9 #435e91 #d8e2ff"),
            Triple("dogboard", true, "#1a1b20 #33353a #3f4759 #adc7ff #2a4678"),
            Triple("brand", false, "#f0eee2 #ffffff #e2e5c2 #59631f #ddea96"),
            Triple("paper", false, "#e3e4e6 #fdfdfd #c9cbcf #2d2f33 #ffffff"),
            Triple("midnight", true, "#16171b #2c2e33 #212328 #9db8f2 #2a3a5c"),
            Triple("ink", true, "#000000 #1a1b1f #111216 #2a4678 #1d2940"),
            // Borderless: the page shows key = ground (#ededf4) and fn = its 1 dp outline (#c4c6d0)
            Triple("bold", true, "#000000 #1f1f1f #ffe14d #ffe14d #ffe14d"),
            Triple("dog", false, "#fbebe0 #ffffff #ffdcc0 #87521a #ffdcc0"),
            Triple("dog", true, "#221a14 #3c332c #59422d #feb877 #6a3b02"),
        )
        for ((id, dark, hex) in swatches) {
            val t = KbThemes.tokens(id, dark)
            listOf(t.bg, t.key, t.fn, t.act, t.chip) shouldContainExactly hex.split(" ").map { css(it) }
        }
        KbThemes.tokens("borderless", false).let { it.key shouldBe androidx.compose.ui.graphics.Color.Transparent; it.fnShadow.single().color shouldBe css("#c4c6d0") }
    }
    test("glass contrast rule (Themes:87-93): label colour and ratio") {
        // capL = kop + (1 - kop) * 0.3 * (1 - dim); white if 1.05/(capL+.05) >= (capL+.05)/(lumInk+.05)
        KbThemes.tokens("glass", false, dim = 0.35f, keyOpacity = 0.3f).keyFg shouldBe css("#1b1c1e")
        KbThemes.tokens("glass", false, dim = 0.8f, keyOpacity = 0.0f).keyFg shouldBe css("#ffffff")
    }
})
