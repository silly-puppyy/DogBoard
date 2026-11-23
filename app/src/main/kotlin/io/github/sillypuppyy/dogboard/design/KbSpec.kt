package io.github.sillypuppyy.dogboard.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kb.dc.html, ported 1:1: the component's props (Kb:324-350) and `renderVals()` (Kb:352-708) as
 * plain Kotlin. Names and order follow the JS so the two can be read side by side; each block
 * cites its lines. Lengths are dp (the canvas is CSS px on a 375-wide phone), font sizes sp.
 * Panels, dev row and special bar states: [KbExtrasSpec] (KbPanels.kt).
 */
/**
 * One key of a layout's letter rows, as the touch layout defines it: [kind] char / gap / shift /
 * bksp, [base] and [shifted] the character on the base and shift layers, [w] its width in units.
 */
@androidx.compose.runtime.Immutable
data class KbLetterKey(val kind: String, val base: String = "", val shifted: String = "", val w: Float = 1f)

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
    /** The clipboard filter picked (0 All, 1 Links, 2 Media). */
    val clipFilter: Int = 0,
    /** The emoji panel is searching (header: back + "Search emoji", as Gboard). */
    val emojiSearch: Boolean = false,
    val skinPicker: Boolean = false,
    val devCtrl: String = "off", // off, armed, locked
    /** Kb:428, 730: the action key greyed, "unavailable until you type" (an empty field that can't be sent). */
    val actionOff: Boolean = false,
    /** The return key in the accent colour on plain fields too (Gboard), not only for an action (Send, Go: iPhone);
     *  the user's choice per theme, off unless set. */
    val retAlways: Boolean = false,
    val devAlt: String = "off",
    val dev: Boolean = false,
    val incognito: Boolean = false,
    /** bar "removed": the word long-pressed away (Kb:118; the canvas's sample is "keyboards"). */
    val removedWord: String = "keyboards",
    /** bar "clip": the recent-copy chip's text (Kb:102-105; the canvas's sample is "Paste copied image"). */
    val clipLabel: String = "Paste copied image",
    /** Smart-chip bars (Kb:840-848): the live chips (icon, label, sub); null shows the canvas's samples for [bar]. */
    val chips: List<KbChipData>? = null,
    /** The chip's thumbnail (images and videos; a text clip has none). */
    val clipMedia: Boolean = true,
    /** The copied image (or video frame) itself, when loaded; null draws the canvas's striped placeholder. */
    val clipThumb: androidx.compose.ui.graphics.ImageBitmap? = null,
    val trackpad: Boolean = false,
    /** Live trackpad: the finger's x in dp from the keyboard's left edge (the puck follows it). */
    val trackpadX: Float? = null,
    val oneHanded: String = "", // "", left, right
    val floating: Boolean = false,
    val landscape: Boolean = false,
    val width: Float? = null, // default 375 (800 landscape)
    val keyH: Float = 216f,
    val dim: Float = 0.35f,
    /** Photo themes over 100 % brightness: the white wash over the photo (PhotoReadability.Look.wash). */
    val wash: Float = 0f,
    /** Photo themes: the key background is a black tint (dark versions) rather than white. */
    val keyDark: Boolean = false,
    val blur: Float = 12f,
    val keyOpacity: Float = 0.3f,
    /** Glass: the photo's measured luminance (the design models it as 0.3). */
    val photoLuminance: Float = 0.3f,
    /** Kb:429-431 the photo theme's labels (auto / white / dark), their shadow, and the return key's colours. */
    /** The symbol pages: "iphone" (123 / #+=) or "android" (Gboard's ?123 / =\<), and ?123 / action keys as pills. */
    val symbols: String = "iphone",
    val pillKeys: Boolean = false,
    val glassFg: String = "auto",
    val halo: Boolean = true,
    val actColor: String? = null,
    val actFg: String? = null,
    /** Glass: draw the design's gradient stand-in; off when the live keyboard draws the real photo behind. */
    val backdrop: Boolean = true,
    val preview: String = "",
    /** The preview after the finger has lifted: drawn at this opacity, the key no longer pressed (Panels: fade). */
    val previewFade: Float? = null,
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
    /**
     * The active layout's three letter rows (the live keyboard passes its touch layout's, so what is
     * drawn is what is hit); null = English QWERTY ([KbSpec.ENGLISH_ROWS]).
     */
    val letterRows: List<List<KbLetterKey>>? = null,
    /** The layout's own marks on the symbol pages and the bottom row ([LiveKb.symbols]: Arabic "1" -> "١", "," -> "،"):
     *  drawn as the touch layout types them. */
    val symbolLabels: Map<String, String> = emptyMap(),
    /** Kb:432-436, 569-573 layout elements (Settings › Layout). numRow: off / on / hints (digits on long-press of the top row); null = Kb's default by key style (on if [numberRow]). */
    val numRow: String? = null,
    /** Kb:430: another Dalmatian coat (0 = the theme's own seed). */
    val spotSeed: Int = 0,
    /** off / accents / symbols; null = Kb's default (from [hints] and the key style). */
    val hintKind: String? = null,
    /** auto / lang / space / blank (Kb:757: auto shows the language name only with the globe key, i.e. 2+ languages). */
    val spaceLabel: String = "auto",
    /** The language name for the space bar (Kb's langName). */
    val langName: String = "English",
    /** shift / caps: letters follow shift, or always show capitals (Kb:606). */
    val caseMode: String = "shift",
    /**
     * The action key: "word" (the Kb canvas draws words) or "icon" (Panels: the live default); a word
     * that doesn't fit shows the icon (Panels:56).
     */
    val actionStyle: String = "word",
    /** The app's own label for its action ("Log in"), used in word mode when it fits. */
    val actionLabel: String? = null,
    val pressed: String = "",
    val langMenu: Boolean = false,
    val fnKey: String = "emoji", // emoji, globe
    val only: String = "", // "", keys, bar, live (the live keyboard's key area: no bar, no nav area)
    // DogBoard options (notes/research/suggestion-overflow.md), not in the design:
    /**
     * A word too long for its slot, one setting with two choices:
     * "squash" (default, like Gboard/LatinIME): narrow to 70%, then cut in the middle; a centre
     * word that still can't fit takes the whole slot area. "fade" (like iOS): the word keeps its
     * size and its end fades out.
     */
    val slotOverflow: String = "squash",
)

/** One key cap (Kb:561-565) or a gap (Kb:526). x/w are dp from the keyboard's left edge. */
/** Compared by value, so a key cap whose spec didn't change skips recomposition (one key redraws per press). */
@androidx.compose.runtime.Immutable
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
    /** Kb:741: the cap's gradient fill (Gloss), drawn instead of [bg]. */
    val bgGradient: CssGradient? = null,
    /** Kb:200 `text-shadow`, first layer on top (Gloss labels, the shift glow). */
    val ts: List<CssShadow> = emptyList(),
    val iconSize: Float = 22f,
    val label: String = "",
    val aria: String = "",
    val hint: String = "",
    /** Kb:786: the corner hint's colour (a Dalmatian spot's own, else the theme's). */
    val hintFg: Color = Color.Transparent,
    val sub: String = "",
    val base: String = "",
    /** Drawn as a pill (Gboard's ?123 and action keys) instead of the theme's corner radius. */
    val pill: Boolean = false,
)

data class KbRow(val keys: List<KbKey>)

/** Kb:559: where a key's cap sits, for popups. */
data class KbCapPos(val capX: Float, val capW: Float, val capTop: Float, val label: String, val base: String)

data class KbPreview(val x: Float, val y: Float, val w: Float, val label: String, val fw: Int, val alpha: Float = 1f)
/** A smart chip (Kb:840-851): Material Symbols icon, label, optional sub line. */
/** [swatch]: a colour clip's ARGB, drawn as a round swatch in place of the icon. */
data class KbChipData(val icon: String, val label: String, val sub: String = "", val insert: String = label, val swatch: Long? = null)

data class KbLongPress(
    val x: Float, val y: Float, val items: List<Triple<String, Color, Color>>,
    /** Each item's cell, in keyboard dp: [KbSpec.longPressCells]. */
    val cells: List<KbSpec.LpCell> = emptyList(),
    val w: Float = 0f, val h: Float = 0f,
)
data class KbLangItem(val label: String, val sub: String, val icon: String, val bg: Color)
data class KbLangMenu(val x: Float, val y: Float, val items: List<KbLangItem>)

/** Kb:620-631. */
data class KbSlot(
    val text: String, val kind: String, val aria: String, val bg: Color, val fg: Color,
    val wt: Int, val fs: Float, val ring: List<CssShadow>, val showDiv: Boolean,
    // Kb:860-866: the segmented bar's cells and full-height dividers (Gloss); defaults = the plain bar
    val ts: List<CssShadow> = emptyList(), val cellBg: CssGradient? = null, val cellSh: List<CssShadow> = emptyList(),
    val divH: Float = 22f, val divBg: Color = Color.Transparent, val divSh: List<CssShadow> = emptyList(),
)

/** Kb:966-967 `bv`: the bar's own look; only the segmented (Gloss) bar differs from plain. */
data class KbBarView(
    val bg: CssGradient? = null, val shadow: List<CssShadow> = emptyList(),
    val leftCell: CssGradient? = null, val leftSh: List<CssShadow> = emptyList(), val micSh: List<CssShadow> = emptyList(),
    val ts: List<CssShadow> = emptyList(), val toolSh: List<CssShadow> = emptyList(), val toolFlex: Boolean = false, val iconFg: Color? = null,
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
    /** Each key row's top and height in the key area (Gboard's shorter rows with the number row). */
    val rowTops: List<Float> = emptyList(), val rowHs: List<Float> = emptyList(),
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
    val bv: KbBarView = KbBarView(),
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
    /** For [KbSpec.overlays]: the letters show upper case on the base plane (long-press variants follow), and the
     *  trackpad's resting x (dp, the key area's middle). */
    val upperBase: Boolean = false,
    val tpRestX: Float = 0f,
    val x: KbExtras,
    val slotOverflow: String,
    /** Kb:18-23 glass theme: blurred picture behind the keys, dimmed. */
    val glass: Boolean,
    val blurPx: Float,
    val dimA: Float,
)


/** A letter key's label (Latin and Latin Extended), compiled once: layout runs on every keystroke (phone JankWatch 2026-11-20). */
private val LETTER_RE = Regex("[a-zA-ZÀ-ɏ]")
object KbSpec {
    /** Kb:577: long-press bar cell width and the key caps' side margin (dp). */
    const val LP_CELL = 34f
    const val KEY_MH = 3f

    /**
     * Kb:578-581: the long-press bar's left edge for a key cap at [capX] wide [capW] on a
     * keyboard [w] wide with [n] items (cell i spans x + 4 + i·[LP_CELL]); shared with the touch
     * targets (LongPress.kt) so the drawn cells are the ones that select.
     */
    /** One long-press cell: left edge, row (0 = the row next to the key, 1 = the row above), width (dp). */
    data class LpCell(val x: Float, val row: Int, val w: Float)

    /** Long-press cell height and the bar's padding (Kb:577-581). */
    const val LP_CELL_H = 46f
    const val LP_PAD = 4f
    /** Designer answers 2026-11-14 §6: at most this many variants per row; the rest go in a row above. */
    const val LP_PER_ROW = 8

    /**
     * A variant's cell width (designer answers §6): 34 dp, or its text plus 8 dp a side when longer
     * (multi-character variants at 20 sp, about 11 dp a character).
     */
    fun longPressCellWidth(label: String): Float {
        val n = label.codePointCount(0, label.length)
        return if (n <= 1) LP_CELL else max(LP_CELL, n * 11f + 16f)
    }

    /**
     * Where each variant sits (shared with the touch targets in LongPress.kt, so the drawn cells are
     * the ones that select): rows of [LP_PER_ROW] in list order, the first row next to the key with
     * item 0 over it, the bar shifted to stay on a keyboard [w] wide.
     */
    fun longPressCells(capX: Float, capW: Float, w: Float, labels: List<String>): List<LpCell> {
        val rows = labels.chunked(LP_PER_ROW)
        val out = ArrayList<LpCell>(labels.size)
        for ((r, row) in rows.withIndex()) {
            val widths = row.map(::longPressCellWidth)
            val rowW = widths.sum() + 2 * LP_PAD
            var x = capX + capW / 2 - widths.first() / 2 - LP_PAD
            if (x + rowW > w - 2) x = capX + capW / 2 + widths.first() / 2 + LP_PAD - rowW
            x = max(2f, min(w - rowW - 2, x))
            var cx = x + LP_PAD
            for (cw in widths) { out.add(LpCell(cx, r, cw)); cx += cw }
        }
        return out
    }

    fun longPressBarX(capX: Float, capW: Float, w: Float, n: Int): Float {
        val cw = LP_CELL; val pw = n * cw + 8
        var x = capX + capW / 2 - cw / 2 - 4
        if (x + pw > w - 2) x = capX + capW / 2 + cw / 2 + 4 - pw
        return max(2f, min(w - pw - 2, x))
    }

    // parsed once: a gradient parsed per render made a new brush (and native shader) every frame
    private val CLASSIC_BAR_BG = CssGradient.parse("linear-gradient(#f8f9fb, #e4e7ec 50%, #d0d4db)")
    private val CLASSIC_TOOLS_CELL = CssGradient.parse("linear-gradient(#bcc1ca, #dde0e5)")

    /** Kb:454 long-press sets; `.com` has a list. */
    const val URL_DOT = ".url"

    val LP: Map<String, List<String>> = mapOf(
        "w" to "ŵ", "e" to "èéêëěẽēėę", "r" to "ř", "t" to "țťþ", "y" to "ýŷÿ", "u" to "ùúûüǔũūűůų",
        "i" to "ìíîïǐĩīıį", "o" to "òóôöǒœøõōő", "a" to "àáâäǎæãåāăą", "s" to "ßşșśš", "d" to "ďð",
        "g" to "ğġ", "h" to "ħ", "k" to "ķ", "l" to "łļľ", "z" to "źžż", "c" to "çćčċ", "n" to "ñńņň",
        "0" to "°", "-" to "–—•", "/" to "\\", "$" to "€£¥₩₽", "&" to "§", "\"" to "„“”«»", "." to "…",
        "?" to "¿", "!" to "¡", "'" to "’‘`", "%" to "‰", "=" to "≠≈",
    ).mapValues { (_, v) -> v.codePoints().toArray().map { String(Character.toChars(it)) } } +
        // the URL row's '.': domains on a long press (qwerty-en.xml period-url), no corner hint
        (URL_DOT to listOf(".com", ".org", ".net", ".edu"))

    /**
     * Kb:455 Android-style corner hints, by position as Gboard places them: the n-th letter of the
     * n-th letter row (q -> 1, a -> @, z -> * on QWERTY; the same places on any layout).
     */
    val HINT_ROWS: List<List<String>> = listOf(
        "1234567890".map { it.toString() },
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")"),
        listOf("*", "\"", "'", ":", ";", "!", "?"),
    )

    /** The hint for a letter key at [row] (0-2) and [index] among that row's letters, or null. */
    fun positionalHint(row: Int, index: Int): String? = HINT_ROWS.getOrNull(row)?.getOrNull(index)

    /** Kb:508 return-key words. */
    val RET: Map<String, String> = mapOf(
        "go" to "Go", "search" to "Search", "send" to "Send", "next" to "Next", "previous" to "Previous", "done" to "Done",
        "join" to "Join", "route" to "Route", "continue" to "Continue",
    )

    /** Panels:160-168 action icons (Material Symbols), for icon mode and words that don't fit. */
    val RET_ICON: Map<String, String> = mapOf(
        "go" to "arrow_forward", "search" to "search", "send" to "send", "next" to "keyboard_tab",
        "previous" to "keyboard_tab_rtl", "done" to "check", "join" to "arrow_forward", "route" to "arrow_forward",
        "continue" to "arrow_forward",
    )

    /** Kb:840-848 smart-chip bars: (icon, label, sub) per bar id. */
    val CHIPS: Map<String, List<KbChipData>> = mapOf(
        "otp" to listOf(KbChipData("sms", "482 913", "from Messages")),
        "email" to listOf(KbChipData("person", "alex@example.com"), KbChipData("work", "alex@work.example")),
        "phone" to listOf(KbChipData("call", "+1 555-0142", "yours")),
        "address" to listOf(KbChipData("home", "12 Park Lane, Springfield")),
        "birthday" to listOf(KbChipData("cake", "14 March 1994", "your birthday")),
        "date" to listOf(KbChipData("today", "Today"), KbChipData("event", "Tomorrow"), KbChipData("date_range", "Fri 20 Nov")),
        "contact" to listOf(KbChipData("person", "Sam Lee"), KbChipData("call", "+1 555-0142"), KbChipData("cake", "3 December")),
    )

    /** Kb:604: what Ctrl + letter does, shown on the letters while Ctrl is armed. */
    val CTRLH: Map<String, String> = mapOf("a" to "all", "c" to "copy", "v" to "paste", "x" to "cut", "z" to "undo", "y" to "redo", "s" to "save", "f" to "find")

    /** Kb:602-612 sample bars. */
    // Kb:862-864: the segmented bar's label shadows (chip cell / plain), chip cell bevel, divider
    private val SEG_CHIP_TS = CssShadow.parse("0 -1px 1px rgba(0,0,0,0.45), 0 -1px 3px rgba(0,0,0,0.2)")
    private val SEG_TS = CssShadow.parse("0 1px 0 rgba(255,255,255,0.95), 0 1.5px 2px rgba(255,255,255,0.6)")
    private val SEG_CELL_SH = CssShadow.parse("inset 0 1px 0 rgba(255,255,255,0.45), inset 0 -1px 0 rgba(0,0,0,0.3), inset 0 0 0 1px rgba(20,50,120,0.35)")
    private val SEG_DIV_SH = CssShadow.parse("1px 0 0 #ffffff")

    val BARS: Map<String, List<Pair<String, String>>> = mapOf(
        "empty" to listOf("I" to "p", "The" to "p", "He" to "p"),
        "hi" to listOf("to" to "p", "my" to "p", "I'm" to "p"),
        "teh" to listOf("“Teh”" to "lit", "The" to "ac", "Try" to "w"),
        "dont" to listOf("“dont”" to "lit", "don't" to "ac", "done" to "w"),
        "keyb" to listOf("“keyb”" to "lit", "keyboard" to "w", "keyboards" to "w"),
        "tehteh" to listOf("“Tehteh”" to "lit", "Truth" to "w", "There" to "w"),
        "emoji" to listOf("“dog”" to "lit", "dogs" to "w", "🐕" to "emo"),
        "omw" to listOf("“omw”" to "lit", "On my way!" to "ac", "own" to "w"),
        "bal" to listOf("“bal”" to "lit", "ball" to "ac", "balance" to "w"), // Kb:835
        "undone" to listOf("“bal”" to "kept", "ball" to "w", "balance" to "w"), // Kb:836
    )

    /** Key spec before styling (Kb:458-466). */
    private data class K(val id: String, val kind: String, val w: Float, val label: String = "", val base: String = "", val sub: String = "")

    private fun up(c: String) = if (c == "ß") "ẞ" else c.uppercase()

    /** English QWERTY's letter rows (the gallery and previews; the live keyboard passes its layout's). */
    val ENGLISH_ROWS: List<List<KbLetterKey>> = listOf(
        "qwertyuiop".map { KbLetterKey("char", it.toString()) },
        listOf(KbLetterKey("gap", w = 0.5f)) + "asdfghjkl".map { KbLetterKey("char", it.toString()) } + listOf(KbLetterKey("gap", w = 0.5f)),
        listOf(KbLetterKey("shift", w = 1.5f)) + "zxcvbnm".map { KbLetterKey("char", it.toString()) } + listOf(KbLetterKey("bksp", w = 1.5f)),
    )

    fun render(P: KbProps): KbRender = overlays(layout(P), P)

    /**
     * The press visuals over a [layout]: the key preview, the long-press bar and the trackpad's x. Cheap, so
     * the live keyboard keeps one layout per keyboard state and pressed key, and lays these over it on every
     * frame of a preview fade or a long-press slide (perf 2026-11-18: the whole layout was rebuilt per frame).
     */
    fun overlays(r: KbRender, P: KbProps): KbRender {
        if (P.preview.isEmpty() && P.longpress.isEmpty() && P.trackpadX == null) return r
        val pos = r.pos; val W = r.W; val t = r.t; val trackpad = r.trackpad
        // Kb:571-576 key preview
        val pv = P.preview.takeIf { it.isNotEmpty() && it in pos && !trackpad }?.let { id ->
            val p = pos.getValue(id); val w = p.capW + 18
            KbPreview(x = max(2f, min(W - w - 2, p.capX + p.capW / 2 - w / 2)), y = p.capTop - 6 - 58, w = w, label = p.label, fw = t.fw, alpha = P.previewFade ?: 1f)
        }
        // Kb:577-590 long-press bar
        val lp = P.longpress.takeIf { it.isNotEmpty() && it in pos }?.let { id ->
            val p = pos.getValue(id)
            var vars = P.lpItems.ifEmpty { LP[p.base] ?: emptyList() }
            if (P.lpItems.isEmpty() && r.upperBase) vars = vars.map(::up)
            val sel = P.lpSel
            val cells = longPressCells(p.capX, p.capW, W, vars)
            val rows = (cells.maxOfOrNull { it.row } ?: 0) + 1
            val x = (cells.minOfOrNull { it.x } ?: p.capX) - LP_PAD
            val right = (cells.maxOfOrNull { it.x + it.w } ?: p.capX) + LP_PAD
            val h = rows * LP_CELL_H + 2 * LP_PAD
            KbLongPress(
                x, p.capTop - 8 - h, vars.mapIndexed { i, c -> Triple(c, if (i == sel) t.sel else Color.Transparent, if (i == sel) t.selFg else t.popFg) },
                cells, right - x, h,
            )
        }?.takeIf { it.items.isNotEmpty() }
        return r.copy(pv = pv, lp = lp, tpX = (P.trackpadX ?: r.tpRestX) - 28)
    }

    /** The keyboard without press visuals ([overlays] adds them). */
    fun layout(P: KbProps): KbRender {
        val t = KbThemes.tokens(P.theme, P.dark, P.dim, P.keyOpacity, P.photoLuminance, P.glassFg, P.halo, P.actColor, P.actFg, P.wash, P.keyDark)

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
        // Kb:569-573
        val numRow = P.numRow ?: if (P.numberRow) "on" else if (kstyle == "android") "hints" else "off"
        val hintKind = P.hintKind ?: if (hints) (if (kstyle == "android") "symbols" else "accents") else "off"
        val panel = P.panel
        val dev = P.dev && panel.isEmpty()
        // Kb:604-605: with Ctrl armed, letters show what Ctrl + letter does
        val ctrlOn = dev && P.devCtrl != "off"
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
        val upper = shift != "off" || P.caseMode == "caps" // Kb:606
        fun ch(c: String, w: Float = 1f) = K(c, "char", w, label = c)
        // a symbol-page key drawn as the layout types it (the number row keeps its digits: shared numrow keys)
        fun sym(c: String, w: Float = 1f) = (P.symbolLabels[c] ?: c).let { K(it, "char", w, label = it) }
        fun letters(s: String) = s.map { c -> c.toString() }.map { c -> K(c, "char", 1f, label = if (upper) up(c) else c, base = c) }
        val letterRows = P.letterRows ?: ENGLISH_ROWS
        // the top letter row's letters, in order: the number row's digits as hints on long-press
        val topRow = letterRows[0].filter { it.kind == "char" }.map { it.base }
        // each letter's place (row, index among the row's letters): the corner hints go by place
        val letterPlace = buildMap { letterRows.forEachIndexed { r, row -> row.filter { it.kind == "char" }.forEachIndexed { j, k -> put(k.base, r to j) } } }
        fun gap(w: Float) = K("", "gap", w)
        // Gboard's row 3: shift and delete take what the letters leave (1.5 units), no gaps
        val shiftK = K("shift", "shift", 1.5f)
        fun bksp(w: Float = 1.5f) = K("bksp", "bksp", w)
        fun mode(label: String, w: Float) = K("mode-$label", "mode", w, label = label)
        fun fnK(w: Float = 1.2533f) = K("fn", "fn", w)
        fun space(w: Float) = K("space", "space", w)
        fun retK(w: Float = 2.4933f) = K("ret", "ret", w)

        val firstMode = if (plane == "base") (if (kstyle == "android") "?123" else "123") else "ABC"
        val bottom = when {
            // Kb:620-622: comma slot (@ for email, / for URLs), 1.5 · 1 · 1 · 4 · 1 · 1.5
            kstyle == "android" -> listOf(mode(firstMode, 1.5f), when (field) { "email" -> ch("@", 1f); "url" -> ch("/", 1f); else -> sym(",", 1f) }, fnK(1f), space(4f), sym(".", 1f), retK(1.5f))
            // iOS's bottom row keeps iOS's widths
            field == "email" -> listOf(mode(firstMode, 1.24f), fnK(), space(2.5133f), ch("@", 1.25f), ch(".", 1.25f), retK())
            // a space bar in URL fields too (address bars take searches; Gboard keeps one), .com etc. on a long press of .
            field == "url" -> listOf(mode(firstMode, 1.24f), fnK(), space(2.5133f), ch("/", 1.25f), K(".", "char", 1.25f, label = ".", base = URL_DOT), retK())
            else -> listOf(mode(firstMode, 1.24f), fnK(), space(5.0133f), retK())
        }
        // the first page has the layout's own . , ? (Arabic ، ؟), the second the Latin ones, as iOS
        fun symRow3(m: String, native: Boolean) = listOf(mode(m, 1.5f)) +
            listOf(".", ",", "?").map { if (native) sym(it, 1.4f) else ch(it, 1.4f) } + listOf(ch("!", 1.4f), ch("'", 1.4f), bksp())
        val spec: List<List<K>> = when (plane) {
            // Gboard's ?123 / =\< pages (Settings > Layout > Symbols: Android-style; photographed 2026-11-17)
            "symbol-1" -> if (P.symbols == "android") listOf("1234567890".map { sym(it.toString()) }, "@#\$_&-+()/".map { ch(it.toString()) },
                listOf(mode("=\\<", 1.5f)) + "*\"':;!?".map { sym(it.toString()) } + listOf(bksp()), bottom)
            else listOf("1234567890".map { sym(it.toString()) }, "-/:;()$&@\"".map { sym(it.toString()) }, symRow3("#+=", true), bottom)
            "symbol-2" -> if (P.symbols == "android") listOf(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "Δ").map { ch(it) },
                listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { ch(it) },
                listOf(mode("?123", 1.5f)) + listOf("%", "©", "®", "™", "✓", "[", "]").map { ch(it) } + listOf(bksp()), bottom)
            else listOf("[]{}#%^*+=".map { ch(it.toString()) }, listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { ch(it) }, symRow3("123", false), bottom)
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
            // Kb:655: the number row (ours is a full-height row, the user's rule, not Kb's 44 dp)
            else -> (if (numRow == "on" && plane == "base" && !landscape) listOf("1234567890".map { ch(it.toString()) }) else emptyList()) +
                letterRows.map { row ->
                    row.map { k ->
                        when (k.kind) {
                            "gap" -> gap(k.w)
                            "shift" -> K("shift", "shift", k.w)
                            "bksp" -> bksp(k.w)
                            else -> K(k.base, "char", k.w, label = if (upper) k.shifted.ifEmpty { up(k.base) } else k.base, base = k.base)
                        }
                    }
                } + listOf(bottom)
        }
        val units = spec[0].sumOf { it.w.toDouble() }.toFloat()
        val u = kbW / units
        val rowH = keyAreaH / spec.size
        // with the number row, Gboard's row heights (Geometry.rowWeights); else equal rows. The number row is there when
        // there are more rows than letter rows + the bottom row (spec.size > 4 took Pashto's four letter rows for one)
        val weights = io.github.sillypuppyy.dogboard.feel.Geometry.rowWeights(spec.size, numRow == "on" && plane == "base" && !landscape && spec.size > letterRows.size + 1)
        val rowHs = weights.map { it / weights.sum() * keyAreaH }
        val rowTops = rowHs.runningFold(0f) { acc, h -> acc + h }.dropLast(1)

        // Kb:509-512
        val pressed = P.pressed.ifEmpty { if (P.previewFade != null) "" else P.preview }
        val scale = t.fsScale
        // labels and icons keep their size on every phone width (designer, 2026-11-14: only the
        // key widths stretch; the key area height is fixed per height setting)
        val sizeScale = 1f
        val labelOp = if (trackpad) 0f else 1f
        val capOp = if (trackpad) 0.55f else 1f

        // Kb:514-518
        val hFloat = if (floating) 24f else 0f
        val showBar = !barless && only != "keys" && only != "live"
        // Gboard's strip is 44 dp at this width (traced); the canvas's 48
        val barH = if (!showBar) 0f else if (t.gboardRow) 44f else 48f
        val devH = if (dev) 44f else 0f
        val keyTop0 = hFloat + barH + devH
        val pos = HashMap<String, KbCapPos>()

        // Kb:675-729 Dalmatian spots
        val spotOf = t.spots?.let { sp ->
            DalmatianSpots.of(spec.map { row -> row.map { DalmatianSpots.Key(it.kind, it.w.toDouble()) } }, kbW.toDouble(), keyAreaH.toDouble(), P.spotSeed.takeIf { it != 0 } ?: sp.seed)
        } ?: emptyMap()

        // Kb:521-568 keys
        val rows = spec.mapIndexed { ri, row ->
            var x = stripL.w
            KbRow(row.mapIndexed { ci, k ->
                val w = k.w * u
                val kx = x
                x += w
                if (k.kind == "gap") return@mapIndexed KbKey(gap = true, x = kx, w = w)
                var bg = t.key; var fg = t.keyFg; var shadow = t.keyShadow; var fs = 25 * scale; var fw = t.fw
                var bgG = t.grad["keyG"]; var ts = t.keyTs // Kb:740: the G tokens, with their colour as the fallback
                // designer 2026-11-14: letters 25 sp, numbers and symbols 24 sp; every function icon a
                // 24 dp Material Symbols glyph (at 22 it drew ~16 dp)
                // Gboard's key icons are ~30 % larger (measured: shift 21, delete 26, check 19 dp wide against our 16 / 20 / 16)
                var icon = ""; var fill = 0; var label = k.label; val iconSize = if (t.gboardRow) 31f else 24f; var hint = ""
                var aria = k.label.ifEmpty { k.id }; var sub = k.sub
                val isFnTone = k.kind in listOf("shift", "bksp", "mode", "fn", "fnchar", "spaceicon")
                if (isFnTone) { bg = t.fn; bgG = t.grad["fnG"]; fg = t.fnFg; shadow = t.fnShadow; ts = t.fnTs }
                if (k.kind == "char") {
                    fs = (if (LETTER_RE.matches(label.take(1)) && label.length == 1) 25 else 24) * scale
                    if (label == ".com") { fs = 16f; fw = 500 }
                    val base = k.base.ifEmpty { k.id }
                    // Kb:747-751
                    if (plane == "base" && hintKind != "off") {
                        if (hintKind == "symbols") hint = letterPlace[base]?.let { (r, j) -> positionalHint(r, j) } ?: ""
                        else if (base in LP && base != URL_DOT) hint = LP.getValue(base)[0].let { if (upper) up(it) else it }
                    }
                    if (plane == "base" && numRow == "hints") topRow.indexOf(base).takeIf { it in 0..9 }?.let { hint = "1234567890"[it].toString() }
                }
                // Kb:754
                if (k.kind == "char" && ctrlOn && plane == "base") CTRLH[k.base.ifEmpty { k.id }]?.let { sub = it; fs = 20f * scale; hint = "" }
                if (k.kind == "pad") { fs = 26f; fw = 400 }
                if (k.kind == "fnchar") { fs = if (k.label.length > 1) 15f else 24f; fw = if (k.label.length > 1) 500 else 400 } // Kb:755
                if (k.kind == "mode") { fs = 15f; fw = 500 }
                // Kb:757
                if (k.kind == "space") {
                    label = when {
                        P.spaceLabel == "space" -> "space"
                        P.spaceLabel == "lang" || (P.spaceLabel == "auto" && fnKeyKind == "globe") -> P.langName
                        // Auto with one language: the word "space"
                        P.spaceLabel == "auto" && t.spaceText.isEmpty() -> "space"
                        P.spaceLabel == "auto" && t.spaceText.isNotEmpty() -> t.spaceText
                        else -> ""
                    }
                    fs = if (t.spaceText.isNotEmpty()) 15f else 14f; fw = if (t.spaceText.isNotEmpty()) t.fw else 400
                    fg = t.spaceFg ?: t.hint; aria = "Space"
                }
                if (k.kind == "spaceicon") { icon = "space_bar"; label = ""; aria = "Space" }
                if (k.kind == "shift") {
                    aria = when (shift) { "lock" -> "Caps lock on"; "off" -> "Shift"; "auto" -> "Shift, auto-capital"; else -> "Shift on" }
                    icon = if (shift == "lock") "shift_lock" else "shift"
                    fill = if (shift == "off") 0 else 1
                    // Kb:763-765: glow themes light the label on the fn tone instead of turning the key white
                    val glowy = t.glow.isNotEmpty()
                    if (shift == "on") { if (glowy) { fg = t.glowFg ?: fg; ts = t.glow } else { bg = t.key; bgG = t.grad["keyG"]; fg = t.keyFg; shadow = t.keyShadow; ts = t.keyTs } }
                    if (shift == "lock") { bg = t.lock; bgG = t.grad["lock"]; fg = t.lockFg; shadow = if (glowy) t.actShadow else emptyList(); ts = if (glowy) t.glow else emptyList() }
                }
                if (k.kind == "bksp") { icon = "backspace"; fill = t.bkspFill; aria = "Delete" }
                if (k.kind == "fn") { icon = if (fnKeyKind == "globe") "language" else "mood"; aria = if (fnKeyKind == "globe") "Next language" else "Emoji" }
                if (k.kind == "ret") {
                    val word = RET[ret]?.let { P.actionLabel?.takeIf { l -> l.isNotBlank() } ?: it }
                    if (word != null) {
                        bg = t.act; bgG = t.grad["actG"]; fg = t.actFg; shadow = t.actShadow; ts = t.actTs; aria = word
                        // Kb:772
                        if (P.actionOff) { bg = t.fn; bgG = t.grad["fnG"]; fg = t.actOffFg ?: t.fnFg; shadow = t.fnShadow; ts = t.fnTs; aria = "$word, unavailable until you type" }
                        // Kb:774 (Gloss forces words); Panels:176 fits(): a word wider than the cap minus 8 dp a side shows the icon
                        if ((P.actionStyle == "word" || t.forceWord) && word.length * 8.6f <= w - mh * 2 - 16f) { label = word; fs = 15f; fw = 600 }
                        else icon = RET_ICON[ret] ?: "arrow_forward"
                    } else if (t.retText.isNotEmpty()) { // Kb:776
                        label = t.retText; fs = 15f; fw = t.fw; bg = t.fn; bgG = t.grad["fnG"]; fg = t.fnFg; shadow = t.fnShadow; ts = t.fnTs; aria = "Return"
                    } else if (P.retAlways) { icon = t.retIcon; fill = if (t.retIcon == "pets") 1 else 0; bg = t.act; bgG = t.grad["actG"]; fg = t.actFg; shadow = t.actShadow; ts = t.actTs; aria = "Return" }
                    else { icon = t.retIcon; fill = if (t.retIcon == "pets") 1 else 0; bg = t.fn; bgG = t.grad["fnG"]; fg = t.fnFg; shadow = t.fnShadow; ts = t.fnTs; aria = "Return" }
                }
                // Kb:779-781
                val spot = t.spots?.takeIf { "$ri:$ci" in spotOf }
                if (spot != null) { bg = spot.bg; bgG = null; fg = spot.fg; shadow = emptyList(); ts = emptyList() }
                // keys coloured by what's under them (the Pride pack): the background at the key's centre, lightened; the
                // label black or white to read on it (the return key keeps its own colour)
                val under = t.bgGradient
                if (t.keyUnder > 0f && under != null && spot == null && (k.kind != "ret" || bg != t.act)) {
                    val c = under.colorAt(kx + w / 2, keyTop0 + rowTops[ri] + rowHs[ri] / 2, kbW, keyTop0 + keyAreaH)
                    // on a near-white stripe a lighter key would vanish: a touch darker instead
                    bg = if (c.luminance() > 0.8f) androidx.compose.ui.graphics.lerp(c, Color(0xFF1A1B20), 0.1f)
                        else androidx.compose.ui.graphics.lerp(c, Color.White, t.keyUnder)
                    bgG = null
                    // the label that reads better on it (a brightness cut-off left grey and salmon keys hard to read)
                    val l = bg.luminance()
                    fg = if ((l + 0.05f) / (0.0135f + 0.05f) >= (1.05f) / (l + 0.05f)) Color(0xFF1A1B20) else Color.White
                }
                if (k.id == pressed && !(k.kind == "ret" && RET[ret] != null)) {
                    val fnPress = isFnTone || k.kind == "ret"
                    bg = if (spot != null) spot.press else if (fnPress) t.pressFn else t.pressKey
                    bgG = if (spot != null) null else t.grad[if (fnPress) "pressFn" else "pressKey"]
                }
                // the bottom row: only function keys take the accent; comma and period keep the letters' colours
                val bottomRow = ri == spec.size - 1 && plane != "numeric" && plane != "numpad" && plane != "telpad" && plane != "telpad2"
                // the emoji / globe key stays a function key (was in the letter tone after Gboard 17.2,
                // iOS draws it as a function key)
                val capX = kx + mh; val capW = w - mh * 2; val capTop = keyTop0 + rowTops[ri] + mv
                pos[k.id] = KbCapPos(capX, capW, capTop, label, k.base.ifEmpty { k.id })
                if (isFnTone && icon.isEmpty() && k.kind != "mode" && k.kind != "fnchar") fs = 15f
                // Gboard's label sizes (traced, sw >= 400): letters 27 dp, ?123 / ABC 18 dp medium
                if (t.gboardRow) {
                    if (k.kind == "char" && label.length == 1 && label[0].isLetter()) fs = 27f
                    if (k.kind == "mode") { fs = 18f; fw = 500 }
                }
                KbKey(
                    gap = false, id = k.id, kind = k.kind, x = kx, w = w, bg = bg, bgGradient = bgG, ts = ts, fg = fg, shadow = shadow, fs = fs * sizeScale, fw = fw,
                    icon = icon, fill = fill, iconSize = iconSize * sizeScale, label = label, aria = aria, hint = hint, hintFg = spot?.hint ?: t.hint, sub = sub,
                    base = k.base.ifEmpty { k.id },
                    pill = P.pillKeys && bottomRow && (k.kind == "mode" || k.kind == "ret"),
                )
            })
        }

        // Kb:591-599 language menu
        val lm = if (P.langMenu) pos["fn"]?.let { p ->
            KbLangMenu(max(4f, p.capX), p.capTop - 8 - 202, listOf(
                KbLangItem("English (US)", "QWERTY", "check", if (t.chip == Color.Transparent) Color.Transparent else Color(127, 127, 127, (0.12f * 255).roundToInt())),
                KbLangItem("Deutsch", "QWERTZ", "", Color.Transparent),
                KbLangItem("Español", "QWERTY", "", Color.Transparent),
            ))
        } else null

        // Kb:601-635 suggestion bar
        val special = (P.bar in listOf("tools", "clip", "autofill", "removed", "voice") || P.bar in KbSpec.CHIPS) && P.slots == null
        val slotSrc = P.slots ?: BARS[P.bar]
        val mIncog = incognito && !special && (P.bar == "empty" || P.bar == "hi") && P.slots == null
        val mSlots = !special && !mIncog && slotSrc != null
        val slots = (slotSrc ?: emptyList()).mapIndexed { i, (text, kind) ->
            val isChip = kind == "ac"
            val prevChip = i > 0 && slotSrc!![i - 1].second == "ac"
            // Kb:860-866
            if (t.seg) return@mapIndexed KbSlot(
                text = text, kind = kind, aria = if (kind == "lit") "Keep $text" else text,
                bg = Color.Transparent, fg = if (isChip) t.segCellFg ?: t.chipFg else t.barFg,
                wt = if (kind == "lit") 500 else 600, fs = if (kind == "emo") 22f else 17f,
                ring = if (kind == "kept") listOf(CssShadow(true, 0f, 0f, 0f, 1.5f, t.div)) else emptyList(),
                showDiv = i > 0,
                ts = if (isChip) SEG_CHIP_TS else SEG_TS,
                cellBg = if (isChip) t.grad["segCell"] else null, cellSh = if (isChip) SEG_CELL_SH else emptyList(),
                divH = 48f, divBg = css("#9aa0aa"), divSh = SEG_DIV_SH,
            )
            KbSlot(
                text = text, kind = kind, aria = if (kind == "lit") "Keep $text" else text,
                bg = if (isChip) t.chip else Color.Transparent, fg = if (isChip) t.chipFg else t.barFg,
                // Gboard's candidates are 18 dp (traced), ours 16 in the canvas
                wt = if (isChip) 600 else 400, fs = if (kind == "emo") 22f else if (t.gboardRow) 18f else 16f,
                ring = if (kind == "kept") listOf(CssShadow(true, 0f, 0f, 0f, 1.5f, t.div)) else emptyList(),
                showDiv = i > 0 && !isChip && !prevChip, divBg = t.div, ts = if (isChip) emptyList() else t.keyTs,
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
            only = only, t = t, W = W, H = H, radius = radius, kbW = kbW, rowH = rowH, rowTops = rowTops, rowHs = rowHs, mv = mv, mh = mh, keyAreaH = keyAreaH, navH = navH,
            rows = rows, stripL = stripL, stripR = stripR, showBar = showBar, barH = barH, devH = devH, floatH = hFloat,
            showKeys = showKeys, showNav = showNav, navIcons = !landscape, labelOp = labelOp, capOp = capOp, pos = pos, pv = null, lp = null, lm = lm,
            mSlots = mSlots, slots = slots, barOp = if (trackpad) 0.35f else 1f,
            leftIcon = if (incognito) "domino_mask" else "widgets",
            leftAria = if (incognito) "Incognito is on" else if (toolsOpen) "Close tools" else "Tools",
            leftBg = if (toolsOpen && !t.seg) t.fn else Color.Transparent, leftFg = if (toolsOpen && !t.seg) t.fnFg else t.barMuted,
            // Kb:966-967
            // the bar's icons and words take the keys' label shadow (photo themes: the halo, so they read on a busy
            // photo; "a bit of background shadow on the ... icons in the top bar"); none elsewhere
            bv = if (!t.seg) KbBarView(ts = t.keyTs) else KbBarView(
                bg = CLASSIC_BAR_BG,
                shadow = CssShadow.parse("inset 0 1px 0 #ffffff, inset 0 -1px 0 #737985, 0 1px 2px rgba(0,0,0,0.35)"),
                leftCell = if (toolsOpen) CLASSIC_TOOLS_CELL else null,
                leftSh = CssShadow.parse((if (toolsOpen) "inset 0 2px 4px rgba(0,0,0,0.4), " else "") + "inset -1px 0 0 #ffffff, inset -2px 0 0 #9aa0aa"),
                micSh = CssShadow.parse("inset 1px 0 0 #ffffff, inset 2px 0 0 #9aa0aa"), ts = CssShadow.parse("0 1px 0 #ffffff"),
                toolSh = CssShadow.parse("inset -1px 0 0 #9aa0aa, inset -2px 0 0 #ffffff"), toolFlex = true, iconFg = css("#3a3e46"),
            ),
            leftFill = if (toolsOpen || incognito) 1 else 0,
            micIcon = if (mVoice) "stop" else "mic", micAria = if (mVoice) "Stop dictation" else "Voice input",
            micBg = if (mVoice) t.act else Color.Transparent, micFg = if (mVoice) t.actFg else t.barMuted, micFill = if (mVoice) 1 else 0,
            trackpad = trackpad, tpX = (P.trackpadX ?: (kbW / 2 + stripL.w)) - 28, tpY = keyAreaH - rowHs.last() / 2 - 28,
            upperBase = upper && plane == "base", tpRestX = kbW / 2 + stripL.w,
            rootRadius = if (floating) 28f else if (only == "bar") 14f else 0f,
            x = KbExtrasSpec.extras(P, t, W),
            slotOverflow = P.slotOverflow, glass = P.theme == "glass" && P.backdrop, blurPx = P.blur, dimA = P.dim,
            rootShadow = if (floating) CssShadow.parse("0 2px 6px rgba(0,0,0,0.15), 0 12px 32px rgba(0,0,0,0.18)") else emptyList(),
        )
    }
}
