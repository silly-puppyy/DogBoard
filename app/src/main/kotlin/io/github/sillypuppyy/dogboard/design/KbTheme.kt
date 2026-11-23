package io.github.sillypuppyy.dogboard.design

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Keyboard theme tokens, ported 1:1 from Kb.dc.html:364-412 (`DEF`, `T`, the glass formula and
 * the `linkFg` fallback). Values stay as the design writes them (CSS colour and box-shadow
 * strings) and are parsed by [css] / [CssShadow], so each entry can be checked against the file.
 */
@androidx.compose.runtime.Immutable
data class KbTokens(
    val bg: Color, val key: Color, val keyFg: Color, val fn: Color, val fnFg: Color,
    val act: Color, val actFg: Color, val chip: Color, val chipFg: Color, val pop: Color,
    val popFg: Color, val sel: Color, val selFg: Color, val hint: Color, val div: Color,
    val barFg: Color, val barMuted: Color, val tile: Color, val tileFg: Color,
    val pressKey: Color, val pressFn: Color, val lock: Color, val lockFg: Color,
    // DEF (Kb:364)
    val radius: Float, val fw: Int, val fsScale: Float, val hintFs: Float, val hintFw: Int,
    val retIcon: String, val keyShadow: List<CssShadow>, val fnShadow: List<CssShadow>,
    /** Gboard's bottom row: ?123 / ABC and the action key as pills, comma and period in the function tone. */
    val gboardRow: Boolean = false,
    val actShadow: List<CssShadow>, val linkFg: Color,
    val actIsTransparent: Boolean,
    /** Kb:522-530: a gradient `bg` (Dusk, Lagoon, Peach); [bg] is then its first colour. */
    val bgGradient: CssGradient? = null,
    /**
     * Kb:550-551: gradient fills by token name (`keyG`, `fnG`, `actG`, `chipG`, and any colour token
     * written as a gradient: Gloss's `pressKey`, `pressFn`, `lock`, `segCell`); the colour field then
     * holds the gradient's first colour. Absent = the plain colour.
     */
    val grad: Map<String, CssGradient> = emptyMap(),
    // Kb:506 (Gloss): filled delete icon, a word on plain return, words on the action key, the space
    // bar's text and colour, the action key's label colour while unavailable
    val bkspFill: Int = 0, val retText: String = "", val forceWord: Boolean = false,
    val spaceText: String = "", val spaceFg: Color? = null, val actOffFg: Color? = null,
    // Kb:551: label text-shadows (Gloss; `none` elsewhere) and the shift glow with its label colour
    val keyTs: List<CssShadow> = emptyList(), val fnTs: List<CssShadow> = emptyList(), val actTs: List<CssShadow> = emptyList(),
    val glow: List<CssShadow> = emptyList(), val glowFg: Color? = null,
    /** Kb:507: the segmented suggestion bar (Gloss), its chip cell (`grad["segCell"]`) label colour. */
    val seg: Boolean = false, val segCellFg: Color? = null,
    /** Kb:516: Dalmatian's spots (seed, then the spotted keys' colours); null = no spots. */
    val spots: KbSpots? = null,
    /**
     * Keys coloured by what's under them (the Pride pack): each letter and function key takes the
     * gradient background's colour at its centre, mixed this far towards white, its label black or white to read on
     * it; 0 = off.
     */
    val keyUnder: Float = 0f,
    /** Android's icons under the keys (hide / switch keyboard): auto (by the colour there), light or dark. */
    val navIcons: String = "auto",
    /** Sculpted caps (the Mechanical theme): each key a top face over four slopes; null = flat keys. */
    val face: CapFace? = null,
    /** The space between caps (dp), where a theme sets its own (Mechanical: caps 3 px apart, as on a real board). */
    val gapH: Float? = null,
    val gapV: Float? = null,
)

/**
 * Canvas Kb "mech" face: the top face's fills (a dish lit from the back) for letter, function and accent caps, its
 * inset shading, where it sits on the cap (top / side / bottom, dp), how far a press sinks it, the slopes' colours
 * (back, right, front top, left, front bottom), the legend ink and the shift light's colours.
 */
data class CapFace(
    val key: CssGradient, val fn: CssGradient, val act: CssGradient,
    val sh: List<CssShadow>, val pressFace: List<CssShadow>, val pressShadow: List<CssShadow>,
    val top: Float, val side: Float, val bottom: Float, val travel: Float, val rad: Float,
    val bevel: Map<String, List<Color>>, val legend: Color,
    val ledOff: Color, val ledOn: Color, val ledOnGlow: List<CssShadow>, val ledLock: Color, val ledLockGlow: List<CssShadow>,
)

data class KbSpots(val seed: Int, val bg: Color, val fg: Color, val hint: Color, val press: Color)

object KbThemes {
    /** Kb:325 theme ids, in the design's order. */
    val ids = listOf("dogboard", "brand", "paper", "midnight", "ink", "borderless", "glass", "bold", "dog")

    /**
     * Kb:419, 509-521: more plain-token themes from the current canvas (Husky, Greyhound, Dalmatian
     * colours without its spots yet, Labrador / Chocolate Lab; Kb:522-530 the gradient Dusk, Lagoon and Peach), drawable in the gallery; not in the Look
     * list until the user decides the list (canvas 17 vs the designer's answer of nine).
     */
    val moreIds: List<String> get() = listOf("gloss", "mech", "husky", "greyhound", "dalmatian", "lab", "dusk", "lagoon", "peach") + PRIDE.map { it.first }

    /**
     * The Pride pack (canvas TEPride): eight flags made as the theme editor makes a gradient theme: the flag's colours
     * as a smooth OKLab gradient at 150 deg, so the stripes cross every row; tinted to match (keys the least white that
     * keeps dark labels readable on every stripe); the return key one flag colour. (id, name, colours, return key.)
     */
    val PRIDE: List<Triple<String, String, Pair<List<String>, String>>> = listOf(
        Triple("pride", "Rainbow", listOf("#e40303", "#ff8c00", "#ffed00", "#008026", "#24408e", "#732982") to "#732982"),
        Triple("trans", "Trans", listOf("#5bcefa", "#f5a9b8", "#ffffff", "#f5a9b8", "#5bcefa") to "#f5a9b8"),
        Triple("bi", "Bi", listOf("#d60270", "#9b4f96", "#0038a8") to "#d60270"),
        Triple("pan", "Pan", listOf("#ff218c", "#ffd800", "#21b1ff") to "#21b1ff"),
        Triple("lesbian", "Lesbian", listOf("#d52d00", "#ff9a56", "#ffffff", "#d362a4", "#a30262") to "#d362a4"),
        Triple("nonbinary", "Nonbinary", listOf("#fcf434", "#ffffff", "#9c59d1", "#2c2c2c") to "#9c59d1"),
        Triple("aroace", "Aroace", listOf("#e28c00", "#eccd00", "#ffffff", "#62aedc", "#203856") to "#e28c00"),
        Triple("mlm", "MLM", listOf("#078d70", "#26ceaa", "#98e8c1", "#ffffff", "#7bade2", "#5049cc", "#3d1a78") to "#26ceaa"),
    )

    /** A flag as the editor would make it (ThemeEditor:707 flag()). */
    private fun prideTheme(cols: List<String>, act: String): Map<String, Map<String, String>> {
        val bg = ThemeModel.Bg(type = "gradient", stops = cols.mapIndexed { i, c -> ThemeModel.Stop(c, Math.round(i * 100.0 / (cols.size - 1)).toInt()) }, angle = 150)
        val v = ThemeModel.Version(bg, tint = true, ov = mapOf("act" to act))
        return mapOf("light" to m(*ThemeModel.toRoles(v, ThemeModel.Shape(), ThemeModel.bgCss(bg)).toList().toTypedArray()))
    }

    // Kb:364
    private val DEF = mapOf(
        "radius" to "6.5", "fw" to "400", "fsScale" to "1", "hintFs" to "10", "hintFw" to "500",
        "retIcon" to "keyboard_return", "keyShadow" to "none", "fnShadow" to "none", "actShadow" to "none",
    )

    private fun m(vararg p: Pair<String, String>) = mapOf(*p)

    // Kb:365-394, verbatim
    private val T: Map<String, Map<String, Map<String, String>>> = PRIDE.associate { (id, _, f) -> id to prideTheme(f.first, f.second) } + mapOf(
        "dogboard" to mapOf(
            "light" to m("bg" to "#ededf4", "key" to "#ffffff", "keyFg" to "#1a1b20", "fn" to "#dbe2f9", "fnFg" to "#3f4759", "act" to "#435e91", "actFg" to "#ffffff", "chip" to "#d8e2ff", "chipFg" to "#2a4678", "pop" to "#ffffff", "popFg" to "#1a1b20", "sel" to "#435e91", "selFg" to "#ffffff", "hint" to "#6b6e76", "div" to "#c4c6d0", "barFg" to "#1a1b20", "barMuted" to "#44474f", "tile" to "#ffffff", "tileFg" to "#1a1b20", "pressKey" to "#e2e2e9", "pressFn" to "#c4cbe2", "lock" to "#435e91", "lockFg" to "#ffffff"),
            "dark" to m("bg" to "#1a1b20", "key" to "#33353a", "keyFg" to "#e2e2e9", "fn" to "#3f4759", "fnFg" to "#dbe2f9", "act" to "#adc7ff", "actFg" to "#0f2f60", "chip" to "#2a4678", "chipFg" to "#d8e2ff", "pop" to "#45474c", "popFg" to "#e2e2e9", "sel" to "#adc7ff", "selFg" to "#0f2f60", "hint" to "#b4b7c0", "div" to "#44474f", "barFg" to "#e2e2e9", "barMuted" to "#c4c6d0", "tile" to "#282a2f", "tileFg" to "#e2e2e9", "pressKey" to "#4a4c52", "pressFn" to "#545d72", "lock" to "#adc7ff", "lockFg" to "#0f2f60"),
        ),
        // Kb:475-478 (current canvas): the warm Fur palette, both modes
        "brand" to mapOf(
            "light" to m("bg" to "#fbebe1", "key" to "#ffffff", "keyFg" to "#221a14", "fn" to "#efe0d6", "fnFg" to "#51443b", "act" to "#88521c", "actFg" to "#ffffff", "chip" to "#ffdcc2", "chipFg" to "#6b3b05", "pop" to "#ffffff", "popFg" to "#221a14", "sel" to "#88521c", "selFg" to "#ffffff", "hint" to "#6f6157", "div" to "#d6c3b6", "barFg" to "#221a14", "barMuted" to "#51443b", "tile" to "#ffffff", "tileFg" to "#221a14", "pressKey" to "#f5e5db", "pressFn" to "#e2d0c4", "lock" to "#88521c", "lockFg" to "#ffffff"),
            "dark" to m("bg" to "#221a14", "key" to "#3c332c", "keyFg" to "#efe0d6", "fn" to "#2f2620", "fnFg" to "#d6c3b6", "act" to "#ffb77a", "actFg" to "#4c2700", "chip" to "#6b3b05", "chipFg" to "#ffdcc2", "pop" to "#4a4038", "popFg" to "#efe0d6", "sel" to "#ffb77a", "selFg" to "#4c2700", "hint" to "#c3b2a5", "div" to "#51443b", "barFg" to "#efe0d6", "barMuted" to "#d6c3b6", "tile" to "#312822", "tileFg" to "#efe0d6", "pressKey" to "#4a4038", "pressFn" to "#40352d", "lock" to "#ffb77a", "lockFg" to "#4c2700"),
        ),
        // Kb:509
        "husky" to mapOf(
            "light" to m("bg" to "#dfe5ea", "key" to "#ffffff", "keyFg" to "#1b2128", "fn" to "#5c6670", "fnFg" to "#ffffff", "act" to "#2b6ea8", "actFg" to "#ffffff", "chip" to "#cfe2f3", "chipFg" to "#174a75", "pop" to "#ffffff", "popFg" to "#1b2128", "sel" to "#2b6ea8", "selFg" to "#ffffff", "hint" to "#5f6a74", "div" to "#b7c1ca", "barFg" to "#1b2128", "barMuted" to "#3d4650", "tile" to "#ffffff", "tileFg" to "#1b2128", "pressKey" to "#e6ebf0", "pressFn" to "#4a535c", "lock" to "#2b6ea8", "lockFg" to "#ffffff", "radius" to "10"),
        ),
        // Kb:512
        "greyhound" to mapOf(
            "dark" to m("bg" to "#3a3e45", "key" to "#575c64", "keyFg" to "#f2f1ee", "fn" to "#484c53", "fnFg" to "#dfe1e5", "act" to "#d6b088", "actFg" to "#2a1d10", "chip" to "#5d5348", "chipFg" to "#f3e2cc", "pop" to "#62676f", "popFg" to "#f2f1ee", "sel" to "#d6b088", "selFg" to "#2a1d10", "hint" to "#d2d5da", "div" to "#5a5f67", "barFg" to "#f2f1ee", "barMuted" to "#c9ccd2", "tile" to "#454950", "tileFg" to "#f2f1ee", "pressKey" to "#676c74", "pressFn" to "#575c64", "lock" to "#d6b088", "lockFg" to "#2a1d10", "linkFg" to "#d6b088", "radius" to "6", "fw" to "300"),
        ),
        // Kb:515
        "dalmatian" to mapOf(
            "light" to m("bg" to "#e6e3dc", "key" to "#ffffff", "keyFg" to "#1b1b1b", "fn" to "#cdc7bc", "fnFg" to "#1b1b1b", "act" to "#1b1b1b", "actFg" to "#ffffff", "chip" to "#1b1b1b", "chipFg" to "#ffffff", "pop" to "#ffffff", "popFg" to "#1b1b1b", "sel" to "#1b1b1b", "selFg" to "#ffffff", "hint" to "#66635c", "div" to "#c4c0b6", "barFg" to "#1b1b1b", "barMuted" to "#45433e", "tile" to "#ffffff", "tileFg" to "#1b1b1b", "pressKey" to "#ecebe6", "pressFn" to "#bab3a7", "lock" to "#1b1b1b", "lockFg" to "#ffffff", "radius" to "12", "spotSeed" to "3", "spotBg" to "#1b1b1b", "spotFg" to "#ffffff", "spotHint" to "#a9a59c", "spotPress" to "#3a3a3a"),
        ),
        // Kb:518
        "lab" to mapOf(
            "light" to m("bg" to "#e8dcc8", "key" to "#faf5ec", "keyFg" to "#2b2218", "fn" to "#d9c9ae", "fnFg" to "#4a3b28", "act" to "#7d5f36", "actFg" to "#ffffff", "chip" to "#eadcc2", "chipFg" to "#5a4321", "pop" to "#faf5ec", "popFg" to "#2b2218", "sel" to "#7d5f36", "selFg" to "#ffffff", "hint" to "#75664f", "div" to "#cdbd9f", "barFg" to "#2b2218", "barMuted" to "#54452f", "tile" to "#faf5ec", "tileFg" to "#2b2218", "pressKey" to "#efe6d6", "pressFn" to "#c9b795", "lock" to "#7d5f36", "lockFg" to "#ffffff", "radius" to "12"),
            "dark" to m("bg" to "#2b1d16", "key" to "#4a3427", "keyFg" to "#f3e6dc", "fn" to "#3a281e", "fnFg" to "#e3cdbd", "act" to "#c08b5c", "actFg" to "#1f130c", "chip" to "#5c3f2c", "chipFg" to "#f6dcc6", "pop" to "#5a4134", "popFg" to "#f3e6dc", "sel" to "#c08b5c", "selFg" to "#1f130c", "hint" to "#c9b2a2", "div" to "#5a4134", "barFg" to "#f3e6dc", "barMuted" to "#d4bfb0", "tile" to "#3a281e", "tileFg" to "#f3e6dc", "pressKey" to "#5c4334", "pressFn" to "#4a3427", "lock" to "#c08b5c", "lockFg" to "#1f130c", "linkFg" to "#e2b58c", "radius" to "12"),
        ),
        // Kb:499-508 (light only)
        "gloss" to mapOf(
            "light" to m(
                "bg" to "linear-gradient(#a2a8b4, #6b7181)",
                "key" to "#f1f1f4",
                "keyG" to "linear-gradient(#ffffff, #f1f2f4 45%, #d6d9df)",
                "keyFg" to "#26282d",
                "fn" to "#6c7381",
                "fnG" to "linear-gradient(#7a8290, #5d6473 55%, #4f5664)",
                "fnFg" to "#ffffff",
                "act" to "#3d74d4",
                "actG" to "linear-gradient(#5d95ec, #3a72d6 50%, #2558c0)",
                "actFg" to "#ffffff",
                "chip" to "#ffffff",
                "chipFg" to "#16181d",
                "pop" to "#ffffff",
                "popFg" to "#16181d",
                "sel" to "#2655b3",
                "selFg" to "#ffffff",
                "hint" to "#5b606b",
                "div" to "#8c929e",
                "barFg" to "#1d1f24",
                "barMuted" to "#3a3e46",
                "tile" to "#f1f1f4",
                "tileFg" to "#16181d",
                "pressKey" to "linear-gradient(#c9ccd3, #e4e6ea)",
                "pressFn" to "linear-gradient(#4a505d, #5d6473)",
                "lock" to "linear-gradient(#5d95ec, #3a72d6 50%, #2558c0)",
                "lockFg" to "#ffffff",
                "radius" to "6",
                "fw" to "600",
                "keyShadow" to "0 2px 1px -0.5px rgba(0,0,0,0.7), 0 3px 3px -1px rgba(0,0,0,0.4), inset 0 1px 0 #ffffff, inset 0 -1px 1px rgba(0,0,0,0.14)",
                "fnShadow" to "0 2px 1px -0.5px rgba(0,0,0,0.7), 0 3px 3px -1px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.4), inset 0 -1px 1px rgba(0,0,0,0.25)",
                "actShadow" to "0 2px 1px -0.5px rgba(0,0,0,0.7), 0 3px 3px -1px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.55), inset 0 -1px 1px rgba(0,0,0,0.25)",
                "keyTs" to "0 1px 0 rgba(255,255,255,0.95), 0 1.5px 2px rgba(255,255,255,0.7), 0 -0.5px 1.5px rgba(0,0,0,0.12)",
                "fnTs" to "0 -1px 1px rgba(0,0,0,0.55), 0 -1px 3px rgba(0,0,0,0.3), 0 0 1px rgba(0,0,0,0.25)",
                "actTs" to "0 -1px 1px rgba(0,0,0,0.45), 0 -1px 3px rgba(0,0,0,0.25)",
                "glow" to "0 0 3px #ffffff, 0 0 7px rgba(255,255,255,0.95), 0 0 14px rgba(210,230,255,0.8)",
                "glowFg" to "#ffffff",
                "bkspFill" to "1",
                "retText" to "return",
                "forceWord" to "true",
                "spaceText" to "space",
                "spaceFg" to "#4f545e",
                "actOffFg" to "rgba(255,255,255,0.45)",
                "linkFg" to "#2655b3",
                "seg" to "true",
                "segCell" to "linear-gradient(#6aa2f2, #3b74d8 50%, #2a5cc4)",
                "segCellFg" to "#ffffff",
            ),
        ),
        // Kb:522
        "dusk" to mapOf(
            "dark" to m("bg" to "linear-gradient(160deg, #2b2350 0%, #6b2f6e 55%, #9e3b56 100%)", "key" to "rgba(255,255,255,0.14)", "keyFg" to "#ffffff", "fn" to "rgba(255,255,255,0.07)", "fnFg" to "#f4e8f2", "act" to "#ffd1a8", "actFg" to "#3d1830", "chip" to "rgba(255,255,255,0.22)", "chipFg" to "#ffffff", "pop" to "#3a2b5c", "popFg" to "#ffffff", "sel" to "#ffd1a8", "selFg" to "#3d1830", "hint" to "rgba(255,255,255,0.72)", "div" to "rgba(255,255,255,0.22)", "barFg" to "#ffffff", "barMuted" to "rgba(255,255,255,0.8)", "tile" to "rgba(255,255,255,0.12)", "tileFg" to "#ffffff", "pressKey" to "rgba(255,255,255,0.26)", "pressFn" to "rgba(255,255,255,0.18)", "lock" to "#ffffff", "lockFg" to "#3d1830", "linkFg" to "#ffd1a8"),
        ),
        // Kb:525
        "lagoon" to mapOf(
            "dark" to m("bg" to "linear-gradient(135deg, #0b5563, #23408e)", "key" to "rgba(255,255,255,0.14)", "keyFg" to "#ffffff", "fn" to "rgba(255,255,255,0.07)", "fnFg" to "#e3f1f4", "act" to "#8ee3d0", "actFg" to "#00382f", "chip" to "rgba(255,255,255,0.2)", "chipFg" to "#ffffff", "pop" to "#173f6a", "popFg" to "#ffffff", "sel" to "#8ee3d0", "selFg" to "#00382f", "hint" to "rgba(255,255,255,0.72)", "div" to "rgba(255,255,255,0.22)", "barFg" to "#ffffff", "barMuted" to "rgba(255,255,255,0.8)", "tile" to "rgba(255,255,255,0.12)", "tileFg" to "#ffffff", "pressKey" to "rgba(255,255,255,0.26)", "pressFn" to "rgba(255,255,255,0.18)", "lock" to "#ffffff", "lockFg" to "#0b3a4a", "linkFg" to "#8ee3d0"),
        ),
        // Kb:528
        "peach" to mapOf(
            "light" to m("bg" to "linear-gradient(160deg, #ffe3d1, #f4c4d8)", "key" to "rgba(255,255,255,0.75)", "keyFg" to "#3b2430", "fn" to "rgba(255,255,255,0.4)", "fnFg" to "#3b2430", "act" to "#8a3d62", "actFg" to "#ffffff", "chip" to "rgba(255,255,255,0.85)", "chipFg" to "#6b2c4b", "pop" to "#ffffff", "popFg" to "#3b2430", "sel" to "#8a3d62", "selFg" to "#ffffff", "hint" to "#7a5565", "div" to "rgba(59,36,48,0.18)", "barFg" to "#3b2430", "barMuted" to "#5e3d4c", "tile" to "rgba(255,255,255,0.7)", "tileFg" to "#3b2430", "pressKey" to "rgba(255,255,255,0.5)", "pressFn" to "rgba(255,255,255,0.65)", "lock" to "#8a3d62", "lockFg" to "#ffffff"),
        ),
        "paper" to mapOf(
            "light" to m("bg" to "#e3e4e6", "key" to "#fdfdfd", "keyFg" to "#1b1c1e", "fn" to "#c9cbcf", "fnFg" to "#232427", "act" to "#2d2f33", "actFg" to "#ffffff", "chip" to "#ffffff", "chipFg" to "#1b1c1e", "pop" to "#ffffff", "popFg" to "#1b1c1e", "sel" to "#2d2f33", "selFg" to "#ffffff", "hint" to "#66686d", "div" to "#b9bbbf", "barFg" to "#1b1c1e", "barMuted" to "#46484c", "tile" to "#fdfdfd", "tileFg" to "#1b1c1e", "pressKey" to "#d9dbde", "pressFn" to "#b5b8bd", "lock" to "#2d2f33", "lockFg" to "#ffffff", "radius" to "8", "keyShadow" to "0 1px 0 #a9abb0", "fnShadow" to "0 1px 0 #a9abb0", "linkFg" to "#1b1c1e"),
        ),
        "midnight" to mapOf(
            "dark" to m("bg" to "#16171b", "key" to "#2c2e33", "keyFg" to "#ecedf1", "fn" to "#212328", "fnFg" to "#c9ccd4", "act" to "#9db8f2", "actFg" to "#0b1d42", "chip" to "#2a3a5c", "chipFg" to "#dbe5ff", "pop" to "#3d3f45", "popFg" to "#ecedf1", "sel" to "#9db8f2", "selFg" to "#0b1d42", "hint" to "#a3a6ae", "div" to "#3a3c42", "barFg" to "#ecedf1", "barMuted" to "#b8bbc3", "tile" to "#24262b", "tileFg" to "#ecedf1", "pressKey" to "#3d4046", "pressFn" to "#30333a", "lock" to "#9db8f2", "lockFg" to "#0b1d42"),
        ),
        "ink" to mapOf(
            "dark" to m("bg" to "#000000", "key" to "#1a1b1f", "keyFg" to "#d9dadf", "fn" to "#111216", "fnFg" to "#b9bcc6", "act" to "#2a4678", "actFg" to "#d8e2ff", "chip" to "#1d2940", "chipFg" to "#c9d6f5", "pop" to "#23252b", "popFg" to "#d9dadf", "sel" to "#2a4678", "selFg" to "#d8e2ff", "hint" to "#9a9da6", "div" to "#2a2c31", "barFg" to "#d9dadf", "barMuted" to "#9a9da6", "tile" to "#121317", "tileFg" to "#d9dadf", "pressKey" to "#2a2c31", "pressFn" to "#1f2025", "lock" to "#2a4678", "lockFg" to "#d8e2ff", "linkFg" to "#adc7ff"),
        ),
        "borderless" to mapOf(
            "light" to m("bg" to "#ededf4", "key" to "transparent", "keyFg" to "#1a1b20", "fn" to "transparent", "fnFg" to "#3f4759", "act" to "transparent", "actFg" to "#2a4678", "chip" to "#d8e2ff", "chipFg" to "#2a4678", "pop" to "#ffffff", "popFg" to "#1a1b20", "sel" to "#435e91", "selFg" to "#ffffff", "hint" to "#6b6e76", "div" to "#c4c6d0", "barFg" to "#1a1b20", "barMuted" to "#44474f", "tile" to "#f8f8fe", "tileFg" to "#1a1b20", "pressKey" to "#dcdce4", "pressFn" to "#dcdce4", "lock" to "#d8e2ff", "lockFg" to "#2a4678", "fnShadow" to "inset 0 0 0 1px #c4c6d0", "actShadow" to "inset 0 0 0 1.5px #435e91", "linkFg" to "#435e91"),
            "dark" to m("bg" to "#1a1b20", "key" to "transparent", "keyFg" to "#e2e2e9", "fn" to "transparent", "fnFg" to "#dbe2f9", "act" to "transparent", "actFg" to "#adc7ff", "chip" to "#2a4678", "chipFg" to "#d8e2ff", "pop" to "#45474c", "popFg" to "#e2e2e9", "sel" to "#adc7ff", "selFg" to "#0f2f60", "hint" to "#b4b7c0", "div" to "#44474f", "barFg" to "#e2e2e9", "barMuted" to "#c4c6d0", "tile" to "#282a2f", "tileFg" to "#e2e2e9", "pressKey" to "#2c2e33", "pressFn" to "#2c2e33", "lock" to "#2a4678", "lockFg" to "#d8e2ff", "fnShadow" to "inset 0 0 0 1px #44474f", "actShadow" to "inset 0 0 0 1.5px #adc7ff", "linkFg" to "#adc7ff"),
        ),
        "bold" to mapOf(
            "dark" to m("bg" to "#000000", "key" to "#1f1f1f", "keyFg" to "#ffffff", "fn" to "#ffe14d", "fnFg" to "#000000", "act" to "#ffe14d", "actFg" to "#000000", "chip" to "#ffe14d", "chipFg" to "#000000", "pop" to "#ffffff", "popFg" to "#000000", "sel" to "#ffe14d", "selFg" to "#000000", "hint" to "#ffe14d", "div" to "#7a7a7a", "barFg" to "#ffffff", "barMuted" to "#ffffff", "tile" to "#1f1f1f", "tileFg" to "#ffffff", "pressKey" to "#3d3d3d", "pressFn" to "#e0c200", "lock" to "#ffffff", "lockFg" to "#000000", "fw" to "600", "fsScale" to "1.08", "hintFs" to "12", "hintFw" to "700", "keyShadow" to "inset 0 0 0 1.5px #8a8a8a", "linkFg" to "#ffe14d"),
        ),
        // Tactile, canvas Kb "mech": dark sculpted caps on a matte plate, an orange accent cap; the cap shapes are FACES
        "mech" to mapOf(
            "dark" to m("bg" to "linear-gradient(#18181a, #0f0f10)", "key" to "#2f2f32", "keyFg" to "#cdc9bf", "fn" to "#252528", "fnFg" to "#b5b1a8",
                "act" to "#c65d2e", "actFg" to "#fff3ea", "chip" to "#2e2e31", "chipFg" to "#e6e2d8", "pop" to "#38383c", "popFg" to "#e6e2d8",
                "sel" to "#c65d2e", "selFg" to "#fff3ea", "hint" to "#8a867e", "div" to "#2c2c2f", "barFg" to "#d9d5cb", "barMuted" to "#9a968d",
                "tile" to "#2a2a2d", "tileFg" to "#d9d5cb", "pressKey" to "#2a2a2d", "pressFn" to "#202023", "lock" to "#c65d2e", "lockFg" to "#fff3ea",
                "keyG" to "linear-gradient(#2c2c2f, #17171a)", "fnG" to "linear-gradient(#242427, #121214)", "actG" to "linear-gradient(#b4522b, #7c3415)",
                "keyShadow" to CAP_SH, "fnShadow" to CAP_SH, "actShadow" to CAP_SH, "actOffFg" to "rgba(190,186,177,0.45)",
                "retText" to "return", "forceWord" to "true", "radius" to "6", "fw" to "500", "gapH" to "1.5", "gapV" to "3.5"),
        ),
        "dog" to mapOf(
            "light" to m("bg" to "#fbebe0", "key" to "#ffffff", "keyFg" to "#221a14", "fn" to "#ffdcc0", "fnFg" to "#59422d", "act" to "#87521a", "actFg" to "#ffffff", "chip" to "#ffdcc0", "chipFg" to "#6a3b02", "pop" to "#ffffff", "popFg" to "#221a14", "sel" to "#87521a", "selFg" to "#ffffff", "hint" to "#7a6658", "div" to "#d5c3b6", "barFg" to "#221a14", "barMuted" to "#51443a", "tile" to "#fff8f5", "tileFg" to "#221a14", "pressKey" to "#f5e5db", "pressFn" to "#f0c8a4", "lock" to "#87521a", "lockFg" to "#ffffff", "radius" to "14", "retIcon" to "pets"),
            "dark" to m("bg" to "#221a14", "key" to "#3c332c", "keyFg" to "#efe0d5", "fn" to "#59422d", "fnFg" to "#ffdcc0", "act" to "#feb877", "actFg" to "#4b2800", "chip" to "#6a3b02", "chipFg" to "#ffdcc0", "pop" to "#4a4038", "popFg" to "#efe0d5", "sel" to "#feb877", "selFg" to "#4b2800", "hint" to "#c8b6a8", "div" to "#51443a", "barFg" to "#efe0d5", "barMuted" to "#d5c3b6", "tile" to "#312822", "tileFg" to "#efe0d5", "pressKey" to "#4a4038", "pressFn" to "#6b5139", "lock" to "#feb877", "lockFg" to "#4b2800", "radius" to "14", "retIcon" to "pets"),
        ),
    )

    /**
     * One-off themes the editor can't remake (Gloss: gradient keys, glows, text shadows; Dalmatian: its spot pattern).
     * A copy is meant to be editable in every part, so these offer no Copy and edit.
     */
    val NOT_COPYABLE = setOf("gloss", "dalmatian", "mech")

    /** The sound a built-in theme suggests when it's picked (the person's own sound stays unless they say yes). */
    val SOUND_OF = mapOf("mech" to "mechkeys")

    /** Kb: caps sit on the plate with a tight contact shadow at the base, no drop shadow. */
    private const val CAP_SH = "0 0 0 0.5px rgba(0,0,0,0.9), 0 1px 1px rgba(0,0,0,0.6)"

    /** Sculpted caps by theme (canvas Kb mech face). */
    val FACES: Map<String, CapFace> by lazy {
        mapOf("mech" to CapFace(
            key = CssGradient.parse("linear-gradient(#29292c, #2f2f33 45%, #38383c)")!!,
            fn = CssGradient.parse("linear-gradient(#202023, #26262a 45%, #2e2e32)")!!,
            act = CssGradient.parse("linear-gradient(#b9562b, #c9602f 45%, #db6f3a)")!!,
            sh = CssShadow.parse("inset 0 1.5px 2px rgba(0,0,0,0.38), inset 0 -0.5px 0 rgba(255,255,255,0.07), 0 -0.5px 0 rgba(255,255,255,0.10)"),
            pressFace = CssShadow.parse("inset 0 1.5px 2.5px rgba(0,0,0,0.45), inset 0 -0.5px 0 rgba(255,255,255,0.05), 0 -0.5px 0 rgba(255,255,255,0.07)"),
            pressShadow = CssShadow.parse("0 0 0 0.5px rgba(0,0,0,0.9), 0 0.5px 0.5px rgba(0,0,0,0.7)"),
            top = 3f, side = 4.5f, bottom = 7f, travel = 1.5f, rad = 4f,
            bevel = mapOf(
                "key" to listOf("#3d3d41", "#28282b", "#1f1f22", "#28282b", "#151517").map(::css),
                "fn" to listOf("#343437", "#212124", "#19191c", "#212124", "#121214").map(::css),
                "act" to listOf("#e88a5a", "#b3542b", "#9c4520", "#b3542b", "#7a3416").map(::css),
            ),
            legend = css("rgba(207,202,192,0.3)"),
            ledOff = css("#3a3a3d"), ledOn = css("#f4f7ff"), ledOnGlow = CssShadow.parse("0 0 2px #ffffff, 0 0 5px rgba(220,235,255,0.7)"),
            ledLock = css("#8ff07e"), ledLockGlow = CssShadow.parse("0 0 2px #b8ffae, 0 0 6px rgba(120,240,100,0.7)"),
        ))
    }

    /** A built-in theme's own light or dark tokens over the defaults (the theme editor's Copy and edit); null if it has none. */
    fun raw(themeId: String, dark: Boolean): Map<String, String>? = T[themeId]?.get(if (dark) "dark" else "light")?.let { DEF + it }

    /** A built-in theme's name on the Themes page. */
    fun nameOf(themeId: String): String = ThemeChoice.THEMES.firstOrNull { it.id == themeId }?.name ?: themeId

    /**
     * Kb:399-412: the theme's light or dark set (falling back to the one it has), merged over
     * DEF; `linkFg` defaults to `act`, or `actFg` when `act` is transparent. Glass: [glass].
     */
    fun tokens(
        themeId: String, dark: Boolean, dim: Float = 0.35f, keyOpacity: Float = 0.3f, photoLuminance: Float = 0.3f,
        glassFg: String = "auto", halo: Boolean = true, actColor: String? = null, actFg: String? = null, wash: Float = 0f,
        keyDark: Boolean = false,
    ): KbTokens {
        val raw = if (themeId == "glass") glass(dim, keyOpacity, photoLuminance, glassFg, halo, actColor, actFg, wash, keyDark)
        else if (GradientSpec.isSpec(themeId)) GradientSpec.parse(themeId)?.let(::gradient) ?: T.getValue("dogboard").getValue("light")
        // a theme made in the theme editor (ThemeModel): its version for now, as tokens
        else if (ThemeModel.isSpec(themeId)) ThemeModel.decode(themeId)?.let { ThemeModel.tokensOf(it, dark) } ?: T.getValue("dogboard").getValue("light")
        // a gradient with a bright and a dark version: the one for now
        else if (GradientSpec.isPair(themeId)) GradientSpec.parsePair(themeId)?.let { gradient(if (dark) it.second else it.first) } ?: T.getValue("dogboard").getValue("light") else {
            val th = T[themeId] ?: T.getValue("dogboard")
            // the default theme in the system's Material You colours, when the live keyboard set them
            MaterialYouTheme.override?.takeIf { themeId == "dogboard" || T[themeId] == null }?.let { (l, d) -> if (dark) d else l }
                ?: (if (dark) th["dark"] else th["light"]) ?: th["light"] ?: th.getValue("dark")
        }
        val t = DEF + raw
        fun c(k: String) = t.getValue(k).let { v -> CssGradient.parse(v)?.stops?.first()?.first ?: css(v) }
        val linkFg = t["linkFg"]?.let { css(it) } ?: if (t["act"] == "transparent") c("actFg") else c("act")
        return KbTokens(
            bg = c("bg"), key = c("key"), keyFg = c("keyFg"), fn = c("fn"), fnFg = c("fnFg"),
            act = c("act"), actFg = c("actFg"), chip = c("chip"), chipFg = c("chipFg"), pop = c("pop"),
            popFg = c("popFg"), sel = c("sel"), selFg = c("selFg"), hint = c("hint"), div = c("div"),
            barFg = c("barFg"), barMuted = c("barMuted"), tile = c("tile"), tileFg = c("tileFg"),
            pressKey = c("pressKey"), pressFn = c("pressFn"), lock = c("lock"), lockFg = c("lockFg"),
            radius = t.getValue("radius").toFloat(), fw = t.getValue("fw").toInt(),
            fsScale = t.getValue("fsScale").toFloat(), hintFs = t.getValue("hintFs").toFloat(),
            hintFw = t.getValue("hintFw").toInt(), retIcon = t.getValue("retIcon"), gboardRow = t["gboardRow"] == "1",
            keyShadow = CssShadow.parse(t.getValue("keyShadow")), fnShadow = CssShadow.parse(t.getValue("fnShadow")),
            actShadow = CssShadow.parse(t.getValue("actShadow")), linkFg = linkFg,
            actIsTransparent = t["act"] == "transparent",
            bgGradient = CssGradient.parse(t.getValue("bg")),
            keyUnder = t["keyUnder"]?.toFloatOrNull() ?: 0f,
            navIcons = t["navIcons"] ?: "auto",
            grad = t.mapNotNull { (k, v) -> CssGradient.parse(v)?.let { k to it } }.toMap(),
            bkspFill = t["bkspFill"]?.toInt() ?: 0, retText = t["retText"].orEmpty(), forceWord = t["forceWord"] == "true",
            spaceText = t["spaceText"].orEmpty(), spaceFg = t["spaceFg"]?.let(::css), actOffFg = t["actOffFg"]?.let(::css),
            keyTs = CssShadow.parse(t["keyTs"] ?: "none"), fnTs = CssShadow.parse(t["fnTs"] ?: "none"), actTs = CssShadow.parse(t["actTs"] ?: "none"),
            glow = CssShadow.parse(t["glow"] ?: "none"), glowFg = t["glowFg"]?.let(::css),
            seg = t["seg"] == "true", segCellFg = t["segCellFg"]?.let(::css),
            spots = t["spotSeed"]?.let { KbSpots(it.toInt(), c("spotBg"), c("spotFg"), c("spotHint"), c("spotPress")) },
            face = FACES[themeId], gapH = t["gapH"]?.toFloatOrNull(), gapV = t["gapV"]?.toFloatOrNull(),
        )
    }

    /**
     * Kb:400-406: glass keys over a blurred image; labels pick white or ink by contrast. The design
     * models the image under the keys as luminance 0.3 (Themes.dc.html:87-93); DogBoard passes the
     * chosen photo's measured luminance instead, so readability follows the real picture.
     */
    private fun glass(
        dim: Float, kop: Float, photoLuminance: Float = 0.3f,
        glassFg: String = "auto", halo: Boolean = true, actColor: String? = null, actFg: String? = null, wash: Float = 0f,
        keyDark: Boolean = false,
    ): Map<String, String> {
        // Kb:538, and the white wash of a brightened photo (sRGB-encoded level washed toward white)
        val under = (photoLuminance.toDouble().pow(1 / 2.2) * (1.0 - dim) * (1 - wash) + wash).pow(2.2)
        val capL = (if (keyDark) 0.0 else kop * 1.0) + (1 - kop) * under
        val cW = 1.05 / (capL + 0.05)
        val cB = (capL + 0.05) / (lumHex("#1b1c1e") + 0.05)
        // Kb:541 labels: white or dark as asked, else whichever reads better
        val light = when (glassFg) { "white" -> true; "dark" -> false; else -> cW >= cB }
        val fgc = if (light) "#ffffff" else "#1b1c1e"
        // the key background: a white tint, or a black one for dark keys (dark versions)
        fun a(x: Double) = if (keyDark) "rgba(0,0,0,$x)" else "rgba(255,255,255,$x)"
        return mapOf(
            "bg" to "transparent", "key" to a(kop.toDouble()), "keyFg" to fgc, "fn" to a(String.format(java.util.Locale.ROOT, "%.3f", kop * 0.55).toDouble()), "fnFg" to fgc,
            "act" to (actColor ?: "#f0b46a"), "actFg" to (actFg ?: "#3a2200"), "chip" to "rgba(255,255,255,0.88)", "chipFg" to "#2b2b2b", "pop" to "#ffffff", "popFg" to "#1b1c1e",
            "sel" to "#f0b46a", "selFg" to "#3a2200", "hint" to if (light) "rgba(255,255,255,0.8)" else "rgba(27,28,30,0.75)",
            "div" to if (light) "rgba(255,255,255,0.35)" else "rgba(0,0,0,0.25)", "barFg" to fgc, "barMuted" to fgc,
            "tile" to a(max(0.2, kop.toDouble())), "tileFg" to fgc, "pressKey" to a(min(1.0, kop + 0.25)), "pressFn" to a(min(1.0, kop + 0.15)),
            "lock" to "#ffffff", "lockFg" to "#1b1c1e", "linkFg" to if (light) "#ffffff" else "#1b1c1e",
        ) + (if (halo) {  // Kb:544 the label shadow (a dark one under white labels, a light glow round dark ones)
            val h = if (light) "0 1px 2px rgba(0,0,0,0.6), 0 0 6px rgba(0,0,0,0.35)" else "0 0 3px rgba(255,255,255,0.75), 0 0 8px rgba(255,255,255,0.4)"
            mapOf("keyTs" to h, "fnTs" to h)
        } else emptyMap()) + if (kop < 0.12f) {  // Kb:545: nearly clear keys keep a hairline ring
            val ring = "inset 0 0 0 1px " + if (light) "rgba(255,255,255,0.45)" else "rgba(0,0,0,0.3)"
            mapOf("keyShadow" to ring, "fnShadow" to ring)
        } else emptyMap()
    }

    /**
     * A user's gradient theme (the gradient creator): Dusk's and Lagoon's recipe for white labels (translucent white
     * keys, a pale action key from the lightest colour), Peach's for dark ones (milky keys, labels and action key a
     * deep shade of the darkest colour). Colours the user picks, so contrast is checked rather than assumed.
     */
    private fun gradient(g: GradientSpec): Map<String, String> {
        val cols = g.colors
        val bg = if (cols.size == 1) cols[0] else "linear-gradient(${g.angle}deg, ${cols.joinToString(", ")})"
        val lightest = cols.maxBy { lumHex(it) }
        val darkest = cols.minBy { lumHex(it) }
        fun a(x: Double) = "rgba(255,255,255,$x)"
        return if (g.white) {
            val act = mix(lightest, "#ffffff", 0.65)
            val actFg = mix(darkest, "#000000", 0.55).let { if (contrast(it, act) >= 4.5) it else "#1b1c1e" }
            mapOf(
                "bg" to bg, "key" to a(0.14), "keyFg" to "#ffffff", "fn" to a(0.07), "fnFg" to mix(lightest, "#ffffff", 0.88),
                "act" to act, "actFg" to actFg, "chip" to a(0.22), "chipFg" to "#ffffff", "pop" to mix(darkest, "#ffffff", 0.12), "popFg" to "#ffffff",
                "sel" to act, "selFg" to actFg, "hint" to a(0.72), "div" to a(0.22), "barFg" to "#ffffff", "barMuted" to a(0.8),
                "tile" to a(0.12), "tileFg" to "#ffffff", "pressKey" to a(0.26), "pressFn" to a(0.18), "lock" to "#ffffff", "lockFg" to actFg, "linkFg" to act,
            )
        } else {
            val ink = mix(darkest, "#000000", 0.75).let { if (contrast(it, mix(lightest, "#ffffff", 0.75)) >= 4.5) it else "#1b1c1e" }
            val act = mix(darkest, "#000000", 0.45).let { if (contrast("#ffffff", it) >= 4.5) it else mix(darkest, "#000000", 0.7) }
            mapOf(
                "bg" to bg, "key" to "rgba(255,255,255,0.75)", "keyFg" to ink, "fn" to "rgba(255,255,255,0.4)", "fnFg" to ink,
                "act" to act, "actFg" to "#ffffff", "chip" to "rgba(255,255,255,0.85)", "chipFg" to act, "pop" to "#ffffff", "popFg" to ink,
                "sel" to act, "selFg" to "#ffffff", "hint" to mix(ink, "#ffffff", 0.3), "div" to mix(ink, lightest, 0.82), "barFg" to ink,
                "barMuted" to mix(ink, "#ffffff", 0.2), "tile" to "rgba(255,255,255,0.7)", "tileFg" to ink, "pressKey" to "rgba(255,255,255,0.5)",
                "pressFn" to "rgba(255,255,255,0.65)", "lock" to act, "lockFg" to "#ffffff",
            )
        }
    }

    /** [a] moved [t] of the way to [b], both #rrggbb. */
    fun mix(a: String, b: String, t: Double): String {
        val x = a.substring(1).toInt(16); val y = b.substring(1).toInt(16)
        fun ch(s: Int) = (((x shr s) and 255) * (1 - t) + ((y shr s) and 255) * t).roundToInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(ch(16), ch(8), ch(0))
    }

    /** WCAG contrast of two #rrggbb colours. */
    fun contrast(a: String, b: String): Double {
        val la = lumHex(a); val lb = lumHex(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    // Kb:396-397
    private fun lin(c: Double): Double { val x = c / 255; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
    fun lumHex(h: String): Double {
        val n = h.substring(1).toInt(16)
        return 0.2126 * lin(((n shr 16) and 255).toDouble()) + 0.7152 * lin(((n shr 8) and 255).toDouble()) + 0.0722 * lin((n and 255).toDouble())
    }
}

/** A CSS colour as the design writes it: `#rrggbb`, `transparent` or `rgba(r,g,b,a)`. */
fun css(value: String): Color {
    val v = value.trim()
    return when {
        v == "transparent" -> Color.Transparent
        v.startsWith("#") && v.length == 7 -> Color(0xFF000000 or v.substring(1).toLong(16))
        v.startsWith("rgba(") -> {
            val p = v.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim().toFloat() }
            Color(p[0] / 255f, p[1] / 255f, p[2] / 255f, p[3])
        }
        else -> error("unsupported CSS colour: $value")
    }
}

/**
 * A CSS `linear-gradient([<angle>deg,] <colour> [<pos>%], ...)` as the design writes it; stops without
 * a position are spread evenly between their neighbours, as CSS does.
 */
@androidx.compose.runtime.Immutable
data class CssGradient(val angleDeg: Float, val stops: List<Pair<Color, Float>>) {
    /**
     * CSS geometry: the line runs through the centre at the angle (0 = up, clockwise), long enough that the corners get the end colours.
     * One brush per gradient, its shaders kept per size: a ShaderBrush remembers only the last size,
     * and keys of different widths share a gradient, so each key drawn made a new native shader per
     * frame (native-allocation garbage collections that stall typing).
     */
    fun brush(): androidx.compose.ui.graphics.Brush = cachedBrush

    private val cachedBrush: androidx.compose.ui.graphics.Brush by lazy { shaderBrush() }

    private val shaders = HashMap<androidx.compose.ui.geometry.Size, androidx.compose.ui.graphics.Shader>()

    private fun shaderBrush(): androidx.compose.ui.graphics.Brush = object : androidx.compose.ui.graphics.ShaderBrush() {
        override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader = synchronized(shaders) {
            shaders[size] ?: makeShader(size).also { if (shaders.size >= MAX_SHADERS) shaders.clear(); shaders[size] = it }
        }
    }

    /** The colour drawn at ([x], [y]) on a [w] x [h] area: the same line and stops as [makeShader] (keys coloured by what's under them). */
    fun colorAt(x: Float, y: Float, w: Float, h: Float): Color {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = kotlin.math.sin(a).toFloat(); val dy = -kotlin.math.cos(a).toFloat()
        val half = (kotlin.math.abs(w * dx) + kotlin.math.abs(h * dy)) / 2
        if (half == 0f) return stops.first().first
        val t = (((x - w / 2) * dx + (y - h / 2) * dy) / (2 * half) + 0.5f).coerceIn(0f, 1f)
        val i = stops.indexOfFirst { it.second >= t }
        if (i <= 0) return stops[maxOf(i, 0)].first
        val (c0, p0) = stops[i - 1]; val (c1, p1) = stops[i]
        return if (p1 <= p0) c1 else androidx.compose.ui.graphics.lerp(c0, c1, (t - p0) / (p1 - p0))
    }

    private fun makeShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader {
            val a = Math.toRadians(angleDeg.toDouble())
            val dx = kotlin.math.sin(a).toFloat(); val dy = -kotlin.math.cos(a).toFloat()
            val half = (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2
            val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            return androidx.compose.ui.graphics.LinearGradientShader(
                androidx.compose.ui.geometry.Offset(c.x - dx * half, c.y - dy * half),
                androidx.compose.ui.geometry.Offset(c.x + dx * half, c.y + dy * half),
                stops.map { it.first }, stops.map { it.second },
            )
    }

    companion object {
        /** Key sizes per gradient kept (a keyboard has a handful; a resize starts over). */
        private const val MAX_SHADERS = 32

        /** Parsed once per string: theme tokens are built in frames (2026-11-20 JankWatch samples: the regex compiles). */
        private val parsed = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val NOT_A_GRADIENT = Any()

        fun parse(value: String): CssGradient? {
            if (parsed.size > 256) parsed.clear()
            return parsed.getOrPut(value) { parseNow(value) ?: NOT_A_GRADIENT } as? CssGradient
        }

        private fun parseNow(value: String): CssGradient? {
            val v = value.trim()
            if (!v.startsWith("linear-gradient(")) return null
            var parts = splitTopLevel(v.removePrefix("linear-gradient(").removeSuffix(")")).map { it.trim() }
            // "in oklab [angle]": blended by how bright and colourful things look (the theme editor's gradients)
            val oklab = parts[0].startsWith("in oklab")
            if (oklab) parts = listOf(parts[0].removePrefix("in oklab").trim()).filter { it.isNotEmpty() } + parts.drop(1)
            val angle = parts[0].takeIf { it.endsWith("deg") }?.removeSuffix("deg")?.toFloat() // none = CSS's "to bottom"
            val raw = parts.drop(if (angle != null) 1 else 0).map { p ->
                val m = Regex("""^(.*?)(?:\s+([\d.]+)%)?$""").matchEntire(p)!!
                css(m.groupValues[1]) to m.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toFloat() / 100 }
            }
            val pos = raw.map { it.second }.toMutableList()
            if (pos.first() == null) pos[0] = 0f
            if (pos.last() == null) pos[pos.lastIndex] = 1f
            var i = 0
            while (i < pos.size) {
                if (pos[i] == null) {
                    val j = (i until pos.size).first { pos[it] != null }
                    val a = pos[i - 1]!!; val b = pos[j]!!
                    for (k in i until j) pos[k] = a + (b - a) * (k - i + 1) / (j - i + 1)
                    i = j
                }
                i++
            }
            val stops = raw.indices.map { raw[it].first to pos[it]!! }
            return CssGradient(angle ?: 180f, if (oklab) Oklab.expand(stops) else stops)
        }
    }
}

/** One layer of a CSS box-shadow: `[inset] x y [blur [spread]] colour`, lengths in dp. */
@androidx.compose.runtime.Immutable
data class CssShadow(val inset: Boolean, val x: Float, val y: Float, val blur: Float, val spread: Float, val color: Color) {
    companion object {
        private val parsed = java.util.concurrent.ConcurrentHashMap<String, List<CssShadow>>()

        /** Parsed once per string, as [CssGradient.parse]. */
        fun parse(value: String): List<CssShadow> {
            if (parsed.size > 256) parsed.clear()
            return parsed.getOrPut(value) { parseNow(value) }
        }

        private fun parseNow(value: String): List<CssShadow> {
            if (value.trim() == "none") return emptyList()
            return splitTopLevel(value).map { layer ->
                val tokens = Regex("""rgba\([^)]*\)|\S+""").findAll(layer.trim()).map { it.value }.toMutableList()
                val inset = tokens.remove("inset")
                val color = css(tokens.removeAt(tokens.indexOfFirst { it.startsWith("#") || it.startsWith("rgba") || it == "transparent" }))
                val n = tokens.map { it.removeSuffix("px").toFloat() }
                CssShadow(inset, n[0], n[1], n.getOrElse(2) { 0f }, n.getOrElse(3) { 0f }, color)
            }
        }
    }
}

/** Splits at commas outside parentheses. */
private fun splitTopLevel(s: String): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var start = 0
    for ((i, ch) in s.withIndex()) {
        when (ch) {
            '(' -> depth++
            ')' -> depth--
            ',' -> if (depth == 0) { out.add(s.substring(start, i)); start = i + 1 }
        }
    }
    out.add(s.substring(start))
    return out
}

/**
 * A gradient theme as its theme id, so the keyboard needs no file to draw it and every tile previews its own:
 * `grad_<rrggbb>_<rrggbb>[_<rrggbb>]_<angle>_<w|d>` (w: white labels). Underscores only, as theme ids also sit
 * in prefs joined by ',', ';' and '='.
 */
data class GradientSpec(val colors: List<String>, val angle: Int, val white: Boolean) {
    val id: String get() = "grad_" + colors.joinToString("_") { it.removePrefix("#").lowercase() } + "_${angle}_" + if (white) "w" else "d"

    companion object {
        // one colour (a solid colour theme) to three
        private val RE = Regex("^grad_((?:[0-9a-f]{6}_){1,3})(\\d{1,3})_([wd])$")
        fun isSpec(id: String) = id.startsWith("grad_")
        /** A gradient theme with a bright and a dark version: `gpair_<bright spec>__<dark spec>`. */
        fun isPair(id: String) = id.startsWith("gpair_")
        fun pairId(bright: GradientSpec, dark: GradientSpec) = "gpair_" + bright.id + "__" + dark.id
        fun parsePair(id: String): Pair<GradientSpec, GradientSpec>? {
            if (!isPair(id)) return null
            val parts = id.removePrefix("gpair_").split("__")
            if (parts.size != 2) return null
            return (parse(parts[0]) ?: return null) to (parse(parts[1]) ?: return null)
        }
        /** Any gradient theme id, one version or a pair. */
        fun valid(id: String) = parse(id) != null || parsePair(id) != null

        /** A version's colours from the other's: brighter (toward white) or deeper (toward black). */
        fun derive(colors: List<String>, toDark: Boolean): List<String> =
            colors.map { c -> if (toDark) KbThemes.mix(c, "#000000", if (KbThemes.lumHex(c) > 0.3) 0.6 else 0.35) else KbThemes.mix(c, "#ffffff", if (KbThemes.lumHex(c) < 0.1) 0.7 else 0.45) }
        fun parse(id: String): GradientSpec? {
            val m = RE.matchEntire(id) ?: return null
            return GradientSpec(m.groupValues[1].trimEnd('_').split('_').map { "#$it" }, m.groupValues[2].toInt() % 360, m.groupValues[3] == "w")
        }

        /**
         * White labels when their weakest spot reads better than dark labels' weakest (an average picked white over
         * navy-to-orange, 2.2:1 on the orange end; 2026-11-19).
         */
        fun autoWhite(colors: List<String>): Boolean = weakest(colors, true) >= weakest(colors, false)

        /** The label's contrast against its key over each colour, the lowest (keys tinted as KbThemes.gradient draws them). */
        fun weakest(colors: List<String>, white: Boolean): Double = colors.minOf { c ->
            if (white) KbThemes.contrast("#ffffff", KbThemes.mix(c, "#ffffff", 0.14))
            else KbThemes.contrast(KbThemes.mix(colors.minBy { KbThemes.lumHex(it) }, "#000000", 0.75), KbThemes.mix(c, "#ffffff", 0.75))
        }
    }
}

/**
 * OKLab (Björn Ottosson, 2020): blending by how bright and colourful colours look, so a gradient's middle doesn't go
 * dark and grey as sRGB mixing does. Android's gradient shader blends in sRGB, so [expand] puts [STEPS] stops between
 * each pair, computed here in OKLab; between stops that close, sRGB and OKLab look the same (canvas TEPride).
 */
object Oklab {
    const val STEPS = 16

    private fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    private fun gam(c: Float) = (if (c <= 0.0031308f) 12.92f * c else (1.055 * Math.pow(c.toDouble(), 1 / 2.4) - 0.055).toFloat()).coerceIn(0f, 1f)

    fun of(c: Color): FloatArray {
        val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
        val l = Math.cbrt((0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)).toFloat()
        val m = Math.cbrt((0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)).toFloat()
        val s = Math.cbrt((0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)).toFloat()
        return floatArrayOf(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
            c.alpha,
        )
    }

    fun color(o: FloatArray): Color {
        val l = o[0] + 0.3963377774f * o[1] + 0.2158037573f * o[2]
        val m = o[0] - 0.1055613458f * o[1] - 0.0638541728f * o[2]
        val s = o[0] - 0.0894841775f * o[1] - 1.2914855480f * o[2]
        val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
        return Color(
            gam(4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3),
            gam(-1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3),
            gam(-0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3),
            o[3].coerceIn(0f, 1f),
        )
    }

    /** The stops with [STEPS] OKLab steps between each pair (a hard edge, two stops at one place, stays hard). */
    fun expand(stops: List<Pair<Color, Float>>): List<Pair<Color, Float>> {
        if (stops.size < 2) return stops
        val out = ArrayList<Pair<Color, Float>>()
        for (k in 0 until stops.size - 1) {
            val (c0, p0) = stops[k]; val (c1, p1) = stops[k + 1]
            out.add(c0 to p0)
            if (p1 <= p0 || c0 == c1) continue
            val a = of(c0); val b = of(c1)
            for (n in 1 until STEPS) {
                val t = n.toFloat() / STEPS
                out.add(color(FloatArray(4) { a[it] + (b[it] - a[it]) * t }) to p0 + (p1 - p0) * t)
            }
        }
        out.add(stops.last())
        return out
    }
}
