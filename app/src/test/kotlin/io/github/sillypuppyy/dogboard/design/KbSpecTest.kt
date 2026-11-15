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
    test("layout elements (Kb:569-573, 606, 747-751): letter case, hint kinds, digits on long-press") {
        key(KbProps(caseMode = "caps"), "q").label shouldBe "Q"
        key(KbProps(hintKind = "symbols"), "a").hint shouldBe "@"
        key(KbProps(hintKind = "accents"), "a").hint shouldBe "à"
        key(KbProps(numRow = "hints"), "q").hint shouldBe "1"
        KbSpec.render(KbProps(numRow = "on")).rows[0].keys.map { it.label } shouldContainExactly "1234567890".map { it.toString() }
    }
    test("re-sync batch A: action off (Kb:772), Ctrl hints (Kb:604, 754), Brand = Fur (Kb:475-478)") {
        key(KbProps(ret = "send", actionOff = true), "ret").let { it.bg shouldBe css("#dbe2f9"); it.aria shouldBe "Send, unavailable until you type" }
        key(KbProps(dev = true, devCtrl = "armed"), "c").sub shouldBe "copy"
        key(KbProps(dev = true, devCtrl = "off"), "c").sub shouldBe ""
        KbThemes.tokens("brand", false).bg shouldBe css("#fbebe1")
        KbThemes.tokens("brand", true).act shouldBe css("#ffb77a")
    }
    test("re-sync batch B: the plain-token themes resolve in both modes (Kb:509-521)") {
        for (id in KbThemes.moreIds) for (dark in listOf(false, true)) KbThemes.tokens(id, dark)
        KbThemes.tokens("husky", false).fn shouldBe css("#5c6670")
        KbThemes.tokens("lab", true).act shouldBe css("#c08b5c")
    }
    test("re-sync batch G: gradient backgrounds (Kb:522-530)") {
        CssGradient.parse("linear-gradient(160deg, #2b2350 0%, #6b2f6e 55%, #9e3b56 100%)")!!.let {
            it.angleDeg shouldBe 160f
            it.stops shouldContainExactly listOf(css("#2b2350") to 0f, css("#6b2f6e") to 0.55f, css("#9e3b56") to 1f)
        }
        CssGradient.parse("linear-gradient(135deg, #0b5563, #23408e)")!!.stops.map { it.second } shouldContainExactly listOf(0f, 1f)
        CssGradient.parse("linear-gradient(90deg, #000000, #808080, #ffffff)")!!.stops.map { it.second } shouldContainExactly listOf(0f, 0.5f, 1f)
        CssGradient.parse("#ffffff") shouldBe null
        KbThemes.tokens("peach", false).let { it.bgGradient!!.stops.size shouldBe 2; it.bg shouldBe css("#ffe3d1"); it.key shouldBe css("rgba(255,255,255,0.75)") }
        KbThemes.tokens("dusk", false).keyFg shouldBe css("#ffffff") // dark-only theme, also when light is asked
        KbThemes.tokens("paper", false).bgGradient shouldBe null
    }
    test("key preview: held, then fading after release without the key looking pressed") {
        val held = KbSpec.render(KbProps(preview = "g"))
        held.pv!!.alpha shouldBe 1f
        val fading = KbSpec.render(KbProps(preview = "g", previewFade = 0.4f))
        fading.pv!!.alpha shouldBe 0.4f
        fading.pv!!.label shouldBe held.pv!!.label
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
        // Kb:757 (current canvas): Auto is blank with one language, the language name with the globe key
        key(KbProps(), "space").let { it.label shouldBe ""; it.fs shouldBe 14f; it.fg shouldBe KbThemes.tokens("dogboard", false).hint } // Kb:757: spaceFg, else hint
        key(KbProps(spaceLabel = "space"), "space").label shouldBe "space"
        key(KbProps(spaceLabel = "lang", langName = "Deutsch"), "space").label shouldBe "Deutsch"
        key(KbProps(fnKey = "globe", langName = "English (US)"), "space").label shouldBe "English (US)"
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
            Triple("brand", false, "#fbebe1 #ffffff #efe0d6 #88521c #ffdcc2"), // Kb:476 (Fur)
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
        // capL = kop + (1 - kop) * 0.3 * (1 - dim)^2.2 (Kb:538); white if 1.05/(capL+.05) >= (capL+.05)/(lumInk+.05)
        KbThemes.tokens("glass", false, dim = 0.35f, keyOpacity = 0.3f).keyFg shouldBe css("#1b1c1e")
        KbThemes.tokens("glass", false, dim = 0.8f, keyOpacity = 0.0f).keyFg shouldBe css("#ffffff")
        // dim 0.2, clear keys: under = 0.184 with the exponent (white), 0.24 without it (ink)
        KbThemes.tokens("glass", false, dim = 0.2f, keyOpacity = 0.0f).keyFg shouldBe css("#ffffff")
    }
    test("glass ring (Kb:545): keys under 0.12 opacity get a hairline, others none") {
        KbThemes.tokens("glass", false, dim = 0.8f, keyOpacity = 0.05f).let {
            it.keyShadow.single().let { s -> s.inset shouldBe true; s.spread shouldBe 1f; s.color shouldBe css("rgba(255,255,255,0.45)") }
            it.fnShadow shouldBe it.keyShadow
        }
        KbThemes.tokens("glass", false, dim = 0.35f, keyOpacity = 0.3f).keyShadow shouldBe emptyList()
    }
    test("typed clip tiles (Kb:892-906): what a live clip turns into") {
        val fn = css("#c9cbcf"); val fg = css("#232427")
        fun lead(text: String, media: Boolean = false, icon: String = "", sensitive: Boolean = false) =
            clipLead(KbClipData(media, icon, "", text, false, sensitive = sensitive), fn, fg)
        lead("#4b6eaf")!!.let { it.title shouldBe "#4B6EAF"; it.sub shouldBe "rgb 75 110 175"; it.round shouldBe true; it.mono shouldBe true; it.bg shouldBe css("#4B6EAF") }
        lead("https://www.example.org/recipes/dog-biscuits/")!!.let { it.letter shouldBe "E"; it.title shouldBe "example.org"; it.sub shouldBe "/recipes/dog-biscuits" }
        lead("github.com")!!.let { it.title shouldBe "github.com"; it.sub shouldBe "" }
        lead("••••", sensitive = true)!!.let { it.icon shouldBe "lock"; it.title shouldBe "••••••••" }
        lead("", media = true, icon = "image")!!.let { it.title shouldBe "Photo"; it.bg shouldBe null }
        lead("", media = true, icon = "play_circle")!!.title shouldBe "Video"
        lead("Meet at the dog park at 6?") shouldBe null
        lead("e.g. this") shouldBe null
        lead("4B6EAF1") shouldBe null
    }
    test("re-sync batch H: Gloss keys (Kb:499-508, 740-776)") {
        val g = KbProps(theme = "gloss")
        key(g, "q").let { it.bgGradient!!.stops.map { s -> s.second } shouldContainExactly listOf(0f, 0.45f, 1f); it.bgGradient!!.angleDeg shouldBe 180f; it.bg shouldBe css("#f1f1f4") }
        key(g, "bksp").let { it.fill shouldBe 1; it.bgGradient!!.stops.first().first shouldBe css("#7a8290") }
        key(g, "ret").let { it.label shouldBe "return"; it.icon shouldBe ""; it.fw shouldBe 600 }
        key(KbProps(theme = "gloss", ret = "send", actionStyle = "icon"), "ret").let { it.label shouldBe "Send"; it.bgGradient!!.stops.last().first shouldBe css("#2558c0") }
        key(KbProps(theme = "gloss", ret = "send", actionOff = true), "ret").fg shouldBe css("rgba(255,255,255,0.45)")
        key(KbProps(theme = "gloss", pressed = "q"), "q").bgGradient!!.stops.first().first shouldBe css("#c9ccd3")
        key(g, "space").let { it.label shouldBe "space"; it.fg shouldBe css("#4f545e"); it.fs shouldBe 15f }
        KbThemes.tokens("gloss", false).keyShadow.first().spread shouldBe -0.5f
        key(KbProps(), "q").bgGradient shouldBe null
        // Kb:551, 763-765: label shadows; shift on lights the label (glow) on the fn tone
        key(g, "q").ts.size shouldBe 3
        key(KbProps(theme = "gloss", shift = "on"), "shift").let { it.fg shouldBe css("#ffffff"); it.ts.size shouldBe 3; it.bgGradient!!.stops.first().first shouldBe css("#7a8290") }
        key(KbProps(theme = "gloss", shift = "lock"), "shift").let { it.ts.first().blur shouldBe 3f; it.shadow.size shouldBe 4 }
        key(KbProps(shift = "on"), "shift").let { it.ts shouldBe emptyList(); it.bg shouldBe KbThemes.tokens("dogboard", false).key }
    }
    test("re-sync batch H: Gloss segmented bar (Kb:860-866, 966-969)") {
        KbSpec.render(KbProps(theme = "gloss", bar = "teh")).let { r ->
            r.slots.map { it.showDiv } shouldContainExactly listOf(false, true, true)
            r.slots[1].let { it.kind shouldBe "ac"; it.cellBg!!.stops.first().first shouldBe css("#6aa2f2"); it.fg shouldBe css("#ffffff"); it.fs shouldBe 17f; it.divH shouldBe 48f }
            r.slots[0].let { it.cellBg shouldBe null; it.wt shouldBe 500; it.ts.size shouldBe 2 }
            r.bv.bg!!.stops.size shouldBe 3; r.bv.toolFlex shouldBe true
        }
        KbSpec.render(KbProps(theme = "gloss", bar = "tools")).let { it.leftBg shouldBe androidx.compose.ui.graphics.Color.Transparent; it.bv.leftCell!!.stops.size shouldBe 2 }
        KbSpec.render(KbProps(bar = "teh")).let { it.bv shouldBe KbBarView(); it.slots[1].cellBg shouldBe null; it.slots[2].divH shouldBe 22f }
    }
    test("re-sync batch I: Dalmatian spots, the canvas's coat key for key (Kb:675-729, 779-786)") {
        // expected = the canvas's own JS run in node on the same layout (seeds 1-30, iOS and Android rows, all equal)
        val r = KbSpec.render(KbProps(theme = "dalmatian"))
        val spotted = r.rows.flatMapIndexed { ri, row -> row.keys.mapIndexedNotNull { ci, k -> "$ri:$ci".takeIf { !k.gap && k.bg == css("#1b1b1b") && k.kind != "ret" } } }
        spotted.toSet() shouldBe setOf("2:6", "2:5", "2:3", "1:3", "1:2", "2:8", "1:8", "1:7", "0:7", "3:0", "2:0")
        val spot = r.rows[2].keys[6]
        spot.fg shouldBe css("#ffffff")
        spot.shadow.size shouldBe 0
        spot.hintFg shouldBe css("#a9a59c")
        r.rows[0].keys[0].hintFg shouldBe css("#66635c")
        KbSpec.render(KbProps(theme = "dalmatian", pressed = "i")).rows[0].keys[7].let { it.id shouldBe "i"; it.bg shouldBe css("#3a3a3a") }
        DalmatianSpots.v8Sort(mutableListOf(0, 1, 2, 3)) { a, b -> (a - b).toDouble() } shouldContainExactly listOf(0, 1, 2, 3)
        KbThemes.tokens("dalmatian", false).let { it.bg shouldBe css("#e6e3dc"); it.radius shouldBe 12f }
        KbThemes.tokens("greyhound", true).let { it.radius shouldBe 6f; it.fw shouldBe 300 }
    }
    test("number row: Gboard's row heights (49.9 / 53.7 / 59 of 58.9 dp per plain row)") {
        val w = io.github.sillypuppyy.dogboard.feel.Geometry.rowWeights(5, true)
        (w[0] * 58.9f) shouldBe (49.9f plusOrMinus 0.01f)
        (w[1] * 58.9f) shouldBe (53.7f plusOrMinus 0.01f)
        w[4] shouldBe 1f
        w.sum() shouldBe (4.58f plusOrMinus 0.01f) // the keyboard grows by 0.58 of a row, not 1
        io.github.sillypuppyy.dogboard.feel.Geometry.rowWeights(4, false).toList() shouldContainExactly listOf(1f, 1f, 1f, 1f)
        KbSpec.render(KbProps(numRow = "on")).let { r ->
            r.rowHs.size shouldBe 5
            (r.rowHs[0] / r.rowHs[4]) shouldBe (49.9f / 58.9f plusOrMinus 0.001f)
            r.rowTops[1] shouldBe (r.rowHs[0] plusOrMinus 0.001f)
            (r.rowTops.last() + r.rowHs.last()) shouldBe (r.keyAreaH plusOrMinus 0.01f)
        }
        KbSpec.render(KbProps()).rowHs.distinct().size shouldBe 1
    }
    test("emoji search grid: results read row by row in pages of 8 (Gboard)") {
        // 10 results: top row 0-7, bottom row 8-9 then empty; the grid fills column by column
        searchGridOrder(10).take(6) shouldContainExactly listOf(0, 8, 1, 9, 2, 10)
        // 20 results: page 2 (16-19) all on its top row, the bottom row empty (indices past the end)
        searchGridOrder(20).drop(16).take(4) shouldContainExactly listOf(16, 20, 17, 21)
        searchGridOrder(0) shouldBe emptyList<Int>()
    }
})
