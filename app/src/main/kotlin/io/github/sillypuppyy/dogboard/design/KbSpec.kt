package io.github.sillypuppyy.dogboard.design

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kb.dc.html, ported 1:1: the component's props (Kb:324-350) and `renderVals()` (Kb:352-708) as
 * plain Kotlin. Names and order follow the JS so the two can be read side by side; each block
 * cites its lines. Lengths are dp (the canvas is CSS px on a 375-wide phone), font sizes sp.
 * Panels, dev row and special bar states: [KbExtrasSpec] (KbPanels.kt).
 */
data class KbProps(
    val theme: String = "dogboard",
    val dark: Boolean = false,
    val plane: String = "base", // base, symbol-1, symbol-2, numeric, phone, decimal
    val field: String = "text", // text, email, url
    val shift: String = "off", // off, on, auto, lock
    val ret: String = "return", // return, send, search, go, next, done, join, route, continue
    val kstyle: String = "ios", // ios, android
    val keyShape: String = "rounded", // rounded, pill, square
    val hints: Boolean = false,
    val bar: String = "empty",
    val slots: List<Pair<String, String>>? = null, // (text, kind) from the live strip
    val panel: String = "", // "", clipboard, emoji, tools, edit
    val panelVariant: String = "default", // default, select, locked, empty
    /** The live clipboard (stage 2); null shows the design's sample clips. */
    val clips: List<KbClipData>? = null,
    val skinPicker: Boolean = false,
    val devCtrl: String = "off", // off, armed, locked
    val devAlt: String = "off",
    val dev: Boolean = false,
    val incognito: Boolean = false,
    val trackpad: Boolean = false,
    /** Live trackpad: the finger's x in dp from the keyboard's left edge (the puck follows it). */
    val trackpadX: Float? = null,
    val oneHanded: String = "", // "", left, right
    val floating: Boolean = false,
    val landscape: Boolean = false,
    val width: Float? = null, // default 375 (800 landscape)
    val keyH: Float = 216f,
    val dim: Float = 0.35f,
    val blur: Float = 12f,
    val keyOpacity: Float = 0.3f,
    /** Glass: the photo's measured luminance (the design models it as 0.3). */
    val photoLuminance: Float = 0.3f,
    /** Glass: draw the design's gradient stand-in; off when the live keyboard draws the real photo behind. */
    val backdrop: Boolean = true,
    val preview: String = "",
    val longpress: String = "",
    val lpSel: Int = 0,
    /** The live keyboard's long-press list (FlorisBoard's extended keys, in order); empty: the [KbSpec.LP] sample. */
    val lpItems: List<String> = emptyList(),
    /**
     * The number row above the letters (letter planes only), drawn as FlorisBoard's touch model
     * lays it out: one more equal-height row. Design (Main:233): 44 dp with the key area growing
     * instead; follow-up once the touch model supports a fixed row height.
     */
    val numberRow: Boolean = false,
    val pressed: String = "",
    val langMenu: Boolean = false,
    val fnKey: String = "emoji", // emoji, globe
    val only: String = "", // "", keys, bar, live (the live keyboard's key area: no bar, no nav area)
    // DogBoard options (user, 2026-11-13; notes/research/suggestion-overflow.md), not in the design:
    /**
     * A word too long for its slot, one setting with two choices:
     * "squash" (default, like Gboard/LatinIME): narrow to 70%, then cut in the middle; a centre
     * word that still can't fit takes the whole slot area. "fade" (like iOS): the word keeps its
     * size and its end fades out.
     */
    val slotOverflow: String = "squash",
)

/** One key cap (Kb:561-565) or a gap (Kb:526). x/w are dp from the keyboard's left edge. */
data class KbKey(
    val gap: Boolean,
    val id: String = "",
    val kind: String = "",
    val x: Float = 0f,
    val w: Float,
    val bg: Color = Color.Transparent,
    val fg: Color = Color.Transparent,
    val shadow: List<CssShadow> = emptyList(),
    val fs: Float = 0f,
    val fw: Int = 400,
    val icon: String = "",
    val fill: Int = 0,
    val iconSize: Float = 22f,
    val label: String = "",
    val aria: String = "",
    val hint: String = "",
    val sub: String = "",
    val base: String = "",
)

data class KbRow(val keys: List<KbKey>)

/** Kb:559: where a key's cap sits, for popups. */
data class KbCapPos(val capX: Float, val capW: Float, val capTop: Float, val label: String, val base: String)

data class KbPreview(val x: Float, val y: Float, val w: Float, val label: String, val fw: Int)
data class KbLongPress(val x: Float, val y: Float, val items: List<Triple<String, Color, Color>>)
data class KbLangItem(val label: String, val sub: String, val icon: String, val bg: Color)
data class KbLangMenu(val x: Float, val y: Float, val items: List<KbLangItem>)

/** Kb:620-631. */
data class KbSlot(
    val text: String, val kind: String, val aria: String, val bg: Color, val fg: Color,
    val wt: Int, val fs: Float, val ring: List<CssShadow>, val showDiv: Boolean,
)

data class KbStrip(val show: Boolean, val w: Float, val icons: List<Pair<String, String>>)

data class KbRender(
    /** KbProps.only: "" (whole keyboard), keys, bar, live. */
    val only: String,
    val t: KbTokens,
    val W: Float,
    val H: Float,
    val radius: Float, // dp; 999 = pill
    val kbW: Float,
    val rowH: Float,
    val mv: Float,
    val mh: Float,
    val keyAreaH: Float,
    val navH: Float,
    val rows: List<KbRow>,
    val stripL: KbStrip,
    val stripR: KbStrip,
    val showBar: Boolean,
    val barH: Float,
    val devH: Float,
    val floatH: Float,
    val showKeys: Boolean,
    val showNav: Boolean,
    val navIcons: Boolean,
    val labelOp: Float,
    val capOp: Float,
    val pos: Map<String, KbCapPos>,
    val pv: KbPreview?,
    val lp: KbLongPress?,
    val lm: KbLangMenu?,
    val mSlots: Boolean,
    val slots: List<KbSlot>,
    val barOp: Float,
    val leftIcon: String,
    val leftAria: String,
    val leftBg: Color,
    val leftFg: Color,
    val leftFill: Int,
    val micIcon: String,
    val micAria: String,
    val micBg: Color,
    val micFg: Color,
    val micFill: Int,
    val trackpad: Boolean,
    val tpX: Float,
    val tpY: Float,
    val rootRadius: Float,
    val rootShadow: List<CssShadow>,
    val x: KbExtras,
    val slotOverflow: String,
    /** Kb:18-23 glass theme: blurred picture behind the keys, dimmed. */
    val glass: Boolean,
    val blurPx: Float,
    val dimA: Float,
)

object KbSpec {
    /** Kb:577: long-press bar cell width and the key caps' side margin (dp). */
    const val LP_CELL = 34f
    const val KEY_MH = 3f

    /**
     * Kb:578-581: the long-press bar's left edge for a key cap at [capX] wide [capW] on a
     * keyboard [w] wide with [n] items (cell i spans x + 4 + i·[LP_CELL]); shared with the touch
     * targets (LongPress.kt) so the drawn cells are the ones that select.
     */
    fun longPressBarX(capX: Float, capW: Float, w: Float, n: Int): Float {
        val cw = LP_CELL; val pw = n * cw + 8
        var x = capX + capW / 2 - cw / 2 - 4
        if (x + pw > w - 2) x = capX + capW / 2 + cw / 2 + 4 - pw
        return max(2f, min(w - pw - 2, x))
    }

    /** Kb:454 long-press sets; `.com` has a list. */
    val LP: Map<String, List<String>> = mapOf(
        "w" to "ŵ", "e" to "èéêëěẽēėę", "r" to "ř", "t" to "țťþ", "y" to "ýŷÿ", "u" to "ùúûüǔũūűůų",
        "i" to "ìíîïǐĩīıį", "o" to "òóôöǒœøõōő", "a" to "àáâäǎæãåāăą", "s" to "ßşșśš", "d" to "ďð",
        "g" to "ğġ", "h" to "ħ", "k" to "ķ", "l" to "łļľ", "z" to "źžż", "c" to "çćčċ", "n" to "ñńņň",
        "0" to "°", "-" to "–—•", "/" to "\\", "$" to "€£¥₩₽", "&" to "§", "\"" to "„“”«»", "." to "…",
        "?" to "¿", "!" to "¡", "'" to "’‘`", "%" to "‰", "=" to "≠≈",
    ).mapValues { (_, v) -> v.codePoints().toArray().map { String(Character.toChars(it)) } } +
        (".com" to listOf(".org", ".edu", ".net"))

    /** Kb:455 Android-style corner hints. */
    val AND_HINT: Map<String, String> = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5", "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "@", "s" to "#", "d" to "$", "f" to "_", "g" to "&", "h" to "-", "j" to "+", "k" to "(", "l" to ")",
        "z" to "*", "x" to "\"", "c" to "'", "v" to ":", "b" to ";", "n" to "!", "m" to "?",
    )

    /** Kb:508 return-key words. */
    val RET: Map<String, String> = mapOf(
        "go" to "Go", "search" to "Search", "send" to "Send", "next" to "Next", "done" to "Done",
        "join" to "Join", "route" to "Route", "continue" to "Continue",
    )

    /** Kb:602-612 sample bars. */
    val BARS: Map<String, List<Pair<String, String>>> = mapOf(
        "empty" to listOf("I" to "p", "The" to "p", "He" to "p"),
        "hi" to listOf("to" to "p", "my" to "p", "I'm" to "p"),
        "teh" to listOf("“Teh”" to "lit", "The" to "ac", "Try" to "w"),
        "dont" to listOf("“dont”" to "lit", "don't" to "ac", "done" to "w"),
        "keyb" to listOf("“keyb”" to "lit", "keyboard" to "w", "keyboards" to "w"),
        "tehteh" to listOf("“Tehteh”" to "lit", "Truth" to "w", "There" to "w"),
        "emoji" to listOf("“dog”" to "lit", "dogs" to "w", "🐕" to "emo"),
        "omw" to listOf("“omw”" to "lit", "On my way!" to "ac", "own" to "w"),
        "undone" to listOf("“teh”" to "kept", "the" to "w", "ten" to "w"),
    )

    /** Key spec before styling (Kb:458-466). */
    private data class K(val id: String, val kind: String, val w: Float, val label: String = "", val base: String = "", val sub: String = "")

    private fun up(c: String) = if (c == "ß") "ẞ" else c.uppercase()

    fun render(P: KbProps): KbRender {
        val t = KbThemes.tokens(P.theme, P.dark, P.dim, P.keyOpacity, P.photoLuminance)

        // Kb:414-415
        val radius = when (P.keyShape) { "pill" -> 999f; "square" -> 4f; else -> t.radius }

        // Kb:417-434
        val only = P.only
        val landscape = P.landscape
        val floating = P.floating
        val oneHanded = P.oneHanded
        val W = P.width ?: if (landscape) 800f else 375f
        val plane = P.plane
        val field = P.field
        val shift = P.shift
        val kstyle = P.kstyle
        val ret = P.ret
        val hints = P.hints
        val panel = P.panel
        val dev = P.dev && panel.isEmpty()
        val incognito = P.incognito
        val trackpad = P.trackpad
        val fnKeyKind = P.fnKey
        val barless = plane == "phone" || plane == "decimal"

        // Kb:436-451 geometry
        val keyAreaH = if (landscape) 162f else P.keyH
        var stripL = KbStrip(false, 0f, emptyList())
        var stripR = KbStrip(false, 0f, emptyList())
        var kbW = W
        if (landscape) {
            val side = (W * 0.11f).roundToInt().toFloat()
            kbW = W - side * 2
            stripL = KbStrip(true, side, listOf("undo" to "Undo", "redo" to "Redo"))
            stripR = KbStrip(true, side, listOf("arrow_back" to "Cursor left", "arrow_forward" to "Cursor right"))
        } else if (oneHanded.isNotEmpty()) {
            val strip = (W * 0.16f).roundToInt().toFloat()
            kbW = W - strip
            val s = KbStrip(true, strip, listOf(
                (if (oneHanded == "right") "chevron_left" else "chevron_right") to "Move keyboard to the other side",
                "open_in_full" to "Full width",
            ))
            if (oneHanded == "right") stripL = s else stripR = s
        }
        val mv = if (landscape) 4f else 6f
        val mh = KEY_MH

        // Kb:453-503 layouts
        val upper = shift != "off"
        fun ch(c: String, w: Float = 1f) = K(c, "char", w, label = c)
        fun letters(s: String) = s.map { c -> c.toString() }.map { c -> K(c, "char", 1f, label = if (upper) up(c) else c, base = c) }
        fun gap(w: Float) = K("", "gap", w)
        val shiftK = K("shift", "shift", 1.28f)
        fun bksp(w: Float = 1.28f) = K("bksp", "bksp", w)
        fun mode(label: String, w: Float) = K("mode-$label", "mode", w, label = label)
        fun fnK(w: Float = 1.2533f) = K("fn", "fn", w)
        fun space(w: Float) = K("space", "space", w)
        fun retK(w: Float = 2.4933f) = K("ret", "ret", w)

        val firstMode = if (plane == "base") (if (kstyle == "android") "?123" else "123") else "ABC"
        val bottom = when {
            kstyle == "android" && field == "text" -> listOf(mode(firstMode, 1.24f), ch(",", 1f), fnK(1f), space(3.99f), ch(".", 1f), retK(1.77f))
            field == "email" -> listOf(mode(firstMode, 1.24f), fnK(), space(2.5133f), ch("@", 1.25f), ch(".", 1.25f), retK())
            field == "url" -> listOf(mode(firstMode, 1.24f), fnK(), ch(".", 1.68f), ch("/", 1.6533f), ch(".com", 1.68f), retK())
            else -> listOf(mode(firstMode, 1.24f), fnK(), space(5.0133f), retK())
        }
        fun symRow3(m: String) = listOf(mode(m, 1.28f), gap(0.2133f), ch(".", 1.4f), ch(",", 1.4f), ch("?", 1.4f), ch("!", 1.4f), ch("'", 1.4f), gap(0.2267f), bksp())
        val spec: List<List<K>> = when (plane) {
            "symbol-1" -> listOf("1234567890".map { ch(it.toString()) }, "-/:;()$&@\"".map { ch(it.toString()) }, symRow3("#+="), bottom)
            "symbol-2" -> listOf("[]{}#%^*+=".map { ch(it.toString()) }, listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { ch(it) }, symRow3("123"), bottom)
            "numeric" -> {
                fun f(l: String, w: Float) = K(l, "fnchar", w, label = l)
                listOf(
                    listOf(f("+", 1.8f), ch("1", 3f), ch("2", 3f), ch("3", 3f), f("%", 1.8f)),
                    listOf(f("(", 1.8f), ch("4", 3f), ch("5", 3f), ch("6", 3f), K("space", "spaceicon", 1.8f)),
                    listOf(f(")", 1.8f), ch("7", 3f), ch("8", 3f), ch("9", 3f), bksp(1.8f)),
                    listOf(mode("ABC", 1.8f), f(",", 1.1f), f("#+=", 1.9f), ch("0", 3f), f("=", 1.9f), f(".", 1.1f), retK(1.8f)),
                )
            }
            // FlorisBoard's number and phone pads, as its touch layers define them (4 x 4, with
            // return); the design's 3 x 4 pads have no return key: question for the designer
            "numpad" -> listOf(
                listOf(ch("1", 2.5f), ch("2", 2.5f), ch("3", 2.5f), ch("-", 2.5f)),
                listOf(ch("4", 2.5f), ch("5", 2.5f), ch("6", 2.5f), K("space", "spaceicon", 2.5f)),
                listOf(ch("7", 2.5f), ch("8", 2.5f), ch("9", 2.5f), bksp(2.5f)),
                listOf(ch(",", 2.5f), ch("0", 2.5f), ch(".", 2.5f), retK(2.5f)),
            )
            "telpad" -> listOf(
                listOf(ch("1", 2.5f), ch("2", 2.5f), ch("3", 2.5f), ch("-", 2.5f)),
                listOf(ch("4", 2.5f), ch("5", 2.5f), ch("6", 2.5f), K("space", "spaceicon", 2.5f)),
                listOf(ch("7", 2.5f), ch("8", 2.5f), ch("9", 2.5f), bksp(2.5f)),
                listOf(K("mode-telpad2", "mode", 2.5f, label = "+ * #"), ch("0", 2.5f), ch(".", 2.5f), retK(2.5f)),
            )
            "telpad2" -> listOf(
                listOf(ch("(", 2.5f), ch("/", 2.5f), ch(")", 2.5f), ch("-", 2.5f)),
                listOf(ch("N", 2.5f), K("pause", "fnchar", 2.5f, label = "Pause"), ch(",", 2.5f), K("space", "spaceicon", 2.5f)),
                listOf(ch("*", 2.5f), K("wait", "fnchar", 2.5f, label = "Wait"), ch("#", 2.5f), bksp(2.5f)),
                listOf(K("mode-telpad", "mode", 2.5f, label = "123"), ch("+", 2.5f), ch(".", 2.5f), retK(2.5f)),
            )
            "phone", "decimal" -> {
                fun d(n: String, sub: String) = K(n, "pad", 10f / 3, label = n, sub = if (plane == "phone") sub else "")
                listOf(
                    listOf(d("1", ""), d("2", "ABC"), d("3", "DEF")),
                    listOf(d("4", "GHI"), d("5", "JKL"), d("6", "MNO")),
                    listOf(d("7", "PQRS"), d("8", "TUV"), d("9", "WXYZ")),
                    listOf(
                        if (plane == "phone") K("sym", "fnchar", 10f / 3, label = "+ * #") else K(".", "fnchar", 10f / 3, label = "."),
                        d("0", if (plane == "phone") "+" else ""), bksp(10f / 3),
                    ),
                )
            }
            else -> (if (P.numberRow) listOf("1234567890".map { ch(it.toString()) }) else emptyList()) + listOf(
                letters("qwertyuiop"),
                listOf(gap(0.5f)) + letters("asdfghjkl") + listOf(gap(0.5f)),
                listOf(shiftK, gap(0.2133f)) + letters("zxcvbnm") + listOf(gap(0.2267f), bksp()),
                bottom,
            )
        }
        val units = spec[0].sumOf { it.w.toDouble() }.toFloat()
        val u = kbW / units
        val rowH = keyAreaH / spec.size

        // Kb:509-512
        val pressed = P.pressed.ifEmpty { P.preview }
        val scale = t.fsScale
        // the design is drawn 375 dp wide; on wider phones the key area grows with the width
        // (feel/Geometry.kt), so labels and icons grow with it to keep the design's proportions
        val sizeScale = if (landscape) 1f else (W / 375f).coerceIn(1f, 1.2f)
        val labelOp = if (trackpad) 0f else 1f
        val capOp = if (trackpad) 0.55f else 1f

        // Kb:514-518
        val hFloat = if (floating) 24f else 0f
        val showBar = !barless && only != "keys" && only != "live"
        val barH = if (showBar) 48f else 0f
        val devH = if (dev) 44f else 0f
        val keyTop0 = hFloat + barH + devH
        val pos = HashMap<String, KbCapPos>()

        // Kb:521-568 keys
        val letterRe = Regex("[a-zA-ZÀ-ɏ]")
        val rows = spec.mapIndexed { ri, row ->
            var x = stripL.w
            KbRow(row.map { k ->
                val w = k.w * u
                val kx = x
                x += w
                if (k.kind == "gap") return@map KbKey(gap = true, x = kx, w = w)
                var bg = t.key; var fg = t.keyFg; var shadow = t.keyShadow; var fs = 23 * scale; var fw = t.fw
                var icon = ""; var fill = 0; var label = k.label; val iconSize = 22f; var hint = ""
                var aria = k.label.ifEmpty { k.id }; val sub = k.sub
                val isFnTone = k.kind in listOf("shift", "bksp", "mode", "fn", "fnchar", "spaceicon")
                if (isFnTone) { bg = t.fn; fg = t.fnFg; shadow = t.fnShadow }
                if (k.kind == "char") {
                    fs = (if (letterRe.matches(label.take(1)) && label.length == 1) 23 else 22) * scale
                    if (label == ".com") { fs = 16f; fw = 500 }
                    val base = k.base.ifEmpty { k.id }
                    if (plane == "base" && hints) {
                        if (kstyle == "android") hint = AND_HINT[base] ?: ""
                        else if (base in LP && base != ".com") hint = LP.getValue(base)[0].let { if (upper) up(it) else it }
                    } else if (plane == "base" && kstyle == "android" && ri == 0) hint = AND_HINT[base] ?: ""
                }
                if (k.kind == "pad") { fs = 26f; fw = 400 }
                if (k.kind == "fnchar") { fs = if (k.label.length > 1) 15f else 22f; fw = if (k.label.length > 1) 500 else 400 }
                if (k.kind == "mode") { fs = 15f; fw = 500 }
                if (k.kind == "space") { label = if (landscape || kstyle == "android") "" else "space"; fs = 14f; fw = 400; aria = "Space" }
                if (k.kind == "spaceicon") { icon = "space_bar"; label = ""; aria = "Space" }
                if (k.kind == "shift") {
                    aria = when (shift) { "lock" -> "Caps lock on"; "off" -> "Shift"; "auto" -> "Shift, auto-capital"; else -> "Shift on" }
                    icon = if (shift == "lock") "shift_lock" else "shift"
                    fill = if (shift == "off") 0 else 1
                    if (shift == "on") { bg = t.key; fg = t.keyFg; shadow = t.keyShadow }
                    if (shift == "lock") { bg = t.lock; fg = t.lockFg; shadow = emptyList() }
                }
                if (k.kind == "bksp") { icon = "backspace"; aria = "Delete" }
                if (k.kind == "fn") { icon = if (fnKeyKind == "globe") "language" else "mood"; aria = if (fnKeyKind == "globe") "Next language" else "Emoji" }
                if (k.kind == "ret") {
                    val word = RET[ret]
                    if (word != null) { label = word; bg = t.act; fg = t.actFg; shadow = t.actShadow; fs = 15f; fw = 600; aria = word }
                    else { icon = t.retIcon; fill = if (t.retIcon == "pets") 1 else 0; bg = t.fn; fg = t.fnFg; shadow = t.fnShadow; aria = "Return" }
                }
                if (k.id == pressed && !(k.kind == "ret" && RET[ret] != null)) bg = if (isFnTone || k.kind == "ret") t.pressFn else t.pressKey
                val capX = kx + mh; val capW = w - mh * 2; val capTop = keyTop0 + ri * rowH + mv
                pos[k.id] = KbCapPos(capX, capW, capTop, label, k.base.ifEmpty { k.id })
                if (isFnTone && icon.isEmpty() && k.kind != "mode" && k.kind != "fnchar") fs = 15f
                KbKey(
                    gap = false, id = k.id, kind = k.kind, x = kx, w = w, bg = bg, fg = fg, shadow = shadow, fs = fs * sizeScale, fw = fw,
                    icon = icon, fill = fill, iconSize = iconSize * sizeScale, label = label, aria = aria, hint = hint, sub = sub,
                    base = k.base.ifEmpty { k.id },
                )
            })
        }

        // Kb:571-576 key preview
        val pv = P.preview.takeIf { it.isNotEmpty() && it in pos && !trackpad }?.let { id ->
            val p = pos.getValue(id); val w = p.capW + 18
            KbPreview(x = max(2f, min(W - w - 2, p.capX + p.capW / 2 - w / 2)), y = p.capTop - 6 - 58, w = w, label = p.label, fw = t.fw)
        }
        // Kb:577-590 long-press bar
        val lp = P.longpress.takeIf { it.isNotEmpty() && it in pos }?.let { id ->
            val p = pos.getValue(id)
            var vars = P.lpItems.ifEmpty { LP[p.base] ?: emptyList() }
            if (P.lpItems.isEmpty() && upper && plane == "base") vars = vars.map(::up)
            val sel = P.lpSel
            val x = longPressBarX(p.capX, p.capW, W, vars.size)
            KbLongPress(x, p.capTop - 8 - 54, vars.mapIndexed { i, c -> Triple(c, if (i == sel) t.sel else Color.Transparent, if (i == sel) t.selFg else t.popFg) })
        }?.takeIf { it.items.isNotEmpty() }
        // Kb:591-599 language menu
        val lm = if (P.langMenu) pos["fn"]?.let { p ->
            KbLangMenu(max(4f, p.capX), p.capTop - 8 - 202, listOf(
                KbLangItem("English (US)", "QWERTY", "check", if (t.chip == Color.Transparent) Color.Transparent else Color(127, 127, 127, (0.12f * 255).roundToInt())),
                KbLangItem("Deutsch", "QWERTZ", "", Color.Transparent),
                KbLangItem("Español", "QWERTY", "", Color.Transparent),
            ))
        } else null

        // Kb:601-635 suggestion bar
        val special = P.bar in listOf("tools", "clip", "autofill", "removed", "voice") && P.slots == null
        val slotSrc = P.slots ?: BARS[P.bar]
        val mIncog = incognito && !special && (P.bar == "empty" || P.bar == "hi") && P.slots == null
        val mSlots = !special && !mIncog && slotSrc != null
        val slots = (slotSrc ?: emptyList()).mapIndexed { i, (text, kind) ->
            val isChip = kind == "ac"
            val prevChip = i > 0 && slotSrc!![i - 1].second == "ac"
            KbSlot(
                text = text, kind = kind, aria = if (kind == "lit") "Keep $text" else text,
                bg = if (isChip) t.chip else Color.Transparent, fg = if (isChip) t.chipFg else t.barFg,
                wt = if (isChip) 600 else 400, fs = if (kind == "emo") 22f else 16f,
                ring = if (kind == "kept") listOf(CssShadow(true, 0f, 0f, 0f, 1.5f, t.div)) else emptyList(),
                showDiv = i > 0 && !isChip && !prevChip,
            )
        }
        val toolsOpen = special && P.bar == "tools"
        val mVoice = special && P.bar == "voice"

        // Kb:682-686
        val showPanel = panel.isNotEmpty() && !barless
        val showKeys = !showPanel && only != "bar"
        val navH = if (floating) 8f else if (landscape) 20f else 32f
        val showNav = only != "bar" && only != "live" && !floating
        val H = hFloat + barH + devH + (if (only == "bar") 0f else keyAreaH) + (if (showNav) navH else 0f) + (if (floating) 8f else 0f)

        return KbRender(
            only = only, t = t, W = W, H = H, radius = radius, kbW = kbW, rowH = rowH, mv = mv, mh = mh, keyAreaH = keyAreaH, navH = navH,
            rows = rows, stripL = stripL, stripR = stripR, showBar = showBar, barH = barH, devH = devH, floatH = hFloat,
            showKeys = showKeys, showNav = showNav, navIcons = !landscape, labelOp = labelOp, capOp = capOp, pos = pos, pv = pv, lp = lp, lm = lm,
            mSlots = mSlots, slots = slots, barOp = if (trackpad) 0.35f else 1f,
            leftIcon = if (incognito) "domino_mask" else "widgets",
            leftAria = if (incognito) "Incognito is on" else if (toolsOpen) "Close tools" else "Tools",
            leftBg = if (toolsOpen) t.fn else Color.Transparent, leftFg = if (toolsOpen) t.fnFg else t.barMuted,
            leftFill = if (toolsOpen || incognito) 1 else 0,
            micIcon = if (mVoice) "stop" else "mic", micAria = if (mVoice) "Stop dictation" else "Voice input",
            micBg = if (mVoice) t.act else Color.Transparent, micFg = if (mVoice) t.actFg else t.barMuted, micFill = if (mVoice) 1 else 0,
            trackpad = trackpad, tpX = (P.trackpadX ?: (kbW / 2 + stripL.w)) - 28, tpY = keyAreaH - rowH / 2 - 28,
            rootRadius = if (floating) 28f else if (only == "bar") 14f else 0f,
            x = KbExtrasSpec.extras(P, t, W),
            slotOverflow = P.slotOverflow, glass = P.theme == "glass" && P.backdrop, blurPx = P.blur, dimA = P.dim,
            rootShadow = if (floating) CssShadow.parse("0 2px 6px rgba(0,0,0,0.15), 0 12px 32px rgba(0,0,0,0.18)") else emptyList(),
        )
    }
}
