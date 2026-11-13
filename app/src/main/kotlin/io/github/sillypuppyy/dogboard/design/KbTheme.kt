package io.github.sillypuppyy.dogboard.design

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Keyboard theme tokens, ported 1:1 from Kb.dc.html:364-412 (`DEF`, `T`, the glass formula and
 * the `linkFg` fallback). Values stay as the design writes them (CSS colour and box-shadow
 * strings) and are parsed by [css] / [CssShadow], so each entry can be checked against the file.
 */
data class KbTokens(
    val bg: Color, val key: Color, val keyFg: Color, val fn: Color, val fnFg: Color,
    val act: Color, val actFg: Color, val chip: Color, val chipFg: Color, val pop: Color,
    val popFg: Color, val sel: Color, val selFg: Color, val hint: Color, val div: Color,
    val barFg: Color, val barMuted: Color, val tile: Color, val tileFg: Color,
    val pressKey: Color, val pressFn: Color, val lock: Color, val lockFg: Color,
    // DEF (Kb:364)
    val radius: Float, val fw: Int, val fsScale: Float, val hintFs: Float, val hintFw: Int,
    val retIcon: String, val keyShadow: List<CssShadow>, val fnShadow: List<CssShadow>,
    val actShadow: List<CssShadow>, val linkFg: Color,
    val actIsTransparent: Boolean,
)

object KbThemes {
    /** Kb:325 theme ids, in the design's order. */
    val ids = listOf("dogboard", "brand", "paper", "midnight", "ink", "borderless", "glass", "bold", "dog")

    // Kb:364
    private val DEF = mapOf(
        "radius" to "10", "fw" to "400", "fsScale" to "1", "hintFs" to "10", "hintFw" to "500",
        "retIcon" to "keyboard_return", "keyShadow" to "none", "fnShadow" to "none", "actShadow" to "none",
    )

    private fun m(vararg p: Pair<String, String>) = mapOf(*p)

    // Kb:365-394, verbatim
    private val T: Map<String, Map<String, Map<String, String>>> = mapOf(
        "dogboard" to mapOf(
            "light" to m("bg" to "#ededf4", "key" to "#ffffff", "keyFg" to "#1a1b20", "fn" to "#dbe2f9", "fnFg" to "#3f4759", "act" to "#435e91", "actFg" to "#ffffff", "chip" to "#d8e2ff", "chipFg" to "#2a4678", "pop" to "#ffffff", "popFg" to "#1a1b20", "sel" to "#435e91", "selFg" to "#ffffff", "hint" to "#6b6e76", "div" to "#c4c6d0", "barFg" to "#1a1b20", "barMuted" to "#44474f", "tile" to "#ffffff", "tileFg" to "#1a1b20", "pressKey" to "#e2e2e9", "pressFn" to "#c4cbe2", "lock" to "#435e91", "lockFg" to "#ffffff"),
            "dark" to m("bg" to "#1a1b20", "key" to "#33353a", "keyFg" to "#e2e2e9", "fn" to "#3f4759", "fnFg" to "#dbe2f9", "act" to "#adc7ff", "actFg" to "#0f2f60", "chip" to "#2a4678", "chipFg" to "#d8e2ff", "pop" to "#45474c", "popFg" to "#e2e2e9", "sel" to "#adc7ff", "selFg" to "#0f2f60", "hint" to "#b4b7c0", "div" to "#44474f", "barFg" to "#e2e2e9", "barMuted" to "#c4c6d0", "tile" to "#282a2f", "tileFg" to "#e2e2e9", "pressKey" to "#4a4c52", "pressFn" to "#545d72", "lock" to "#adc7ff", "lockFg" to "#0f2f60"),
        ),
        "brand" to mapOf(
            "light" to m("bg" to "#f0eee2", "key" to "#ffffff", "keyFg" to "#1b1c15", "fn" to "#e2e5c2", "fnFg" to "#45492f", "act" to "#59631f", "actFg" to "#ffffff", "chip" to "#ddea96", "chipFg" to "#424b07", "pop" to "#ffffff", "popFg" to "#1b1c15", "sel" to "#59631f", "selFg" to "#ffffff", "hint" to "#66675a", "div" to "#c8c7b7", "barFg" to "#1b1c15", "barMuted" to "#47483b", "tile" to "#ffffff", "tileFg" to "#1b1c15", "pressKey" to "#e4e3d6", "pressFn" to "#cfd2ab", "lock" to "#59631f", "lockFg" to "#ffffff"),
            "dark" to m("bg" to "#1b1c15", "key" to "#35352d", "keyFg" to "#e4e3d6", "fn" to "#45492f", "fnFg" to "#e2e5c2", "act" to "#c1cd7d", "actFg" to "#2c3400", "chip" to "#424b07", "chipFg" to "#ddea96", "pop" to "#46473e", "popFg" to "#e4e3d6", "sel" to "#c1cd7d", "selFg" to "#2c3400", "hint" to "#b8b8a8", "div" to "#47483b", "barFg" to "#e4e3d6", "barMuted" to "#c8c7b7", "tile" to "#2a2b22", "tileFg" to "#e4e3d6", "pressKey" to "#46473e", "pressFn" to "#575c3c", "lock" to "#c1cd7d", "lockFg" to "#2c3400"),
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
        "dog" to mapOf(
            "light" to m("bg" to "#fbebe0", "key" to "#ffffff", "keyFg" to "#221a14", "fn" to "#ffdcc0", "fnFg" to "#59422d", "act" to "#87521a", "actFg" to "#ffffff", "chip" to "#ffdcc0", "chipFg" to "#6a3b02", "pop" to "#ffffff", "popFg" to "#221a14", "sel" to "#87521a", "selFg" to "#ffffff", "hint" to "#7a6658", "div" to "#d5c3b6", "barFg" to "#221a14", "barMuted" to "#51443a", "tile" to "#fff8f5", "tileFg" to "#221a14", "pressKey" to "#f5e5db", "pressFn" to "#f0c8a4", "lock" to "#87521a", "lockFg" to "#ffffff", "radius" to "14", "retIcon" to "pets"),
            "dark" to m("bg" to "#221a14", "key" to "#3c332c", "keyFg" to "#efe0d5", "fn" to "#59422d", "fnFg" to "#ffdcc0", "act" to "#feb877", "actFg" to "#4b2800", "chip" to "#6a3b02", "chipFg" to "#ffdcc0", "pop" to "#4a4038", "popFg" to "#efe0d5", "sel" to "#feb877", "selFg" to "#4b2800", "hint" to "#c8b6a8", "div" to "#51443a", "barFg" to "#efe0d5", "barMuted" to "#d5c3b6", "tile" to "#312822", "tileFg" to "#efe0d5", "pressKey" to "#4a4038", "pressFn" to "#6b5139", "lock" to "#feb877", "lockFg" to "#4b2800", "radius" to "14", "retIcon" to "pets"),
        ),
    )

    /**
     * Kb:399-412: the theme's light or dark set (falling back to the one it has), merged over
     * DEF; `linkFg` defaults to `act`, or `actFg` when `act` is transparent. Glass: [glass].
     */
    fun tokens(themeId: String, dark: Boolean, dim: Float = 0.35f, keyOpacity: Float = 0.3f, photoLuminance: Float = 0.3f): KbTokens {
        val raw = if (themeId == "glass") glass(dim, keyOpacity, photoLuminance) else {
            val th = T[themeId] ?: T.getValue("dogboard")
            (if (dark) th["dark"] else th["light"]) ?: th["light"] ?: th.getValue("dark")
        }
        val t = DEF + raw
        fun c(k: String) = css(t.getValue(k))
        val linkFg = t["linkFg"]?.let { css(it) } ?: if (t["act"] == "transparent") c("actFg") else c("act")
        return KbTokens(
            bg = c("bg"), key = c("key"), keyFg = c("keyFg"), fn = c("fn"), fnFg = c("fnFg"),
            act = c("act"), actFg = c("actFg"), chip = c("chip"), chipFg = c("chipFg"), pop = c("pop"),
            popFg = c("popFg"), sel = c("sel"), selFg = c("selFg"), hint = c("hint"), div = c("div"),
            barFg = c("barFg"), barMuted = c("barMuted"), tile = c("tile"), tileFg = c("tileFg"),
            pressKey = c("pressKey"), pressFn = c("pressFn"), lock = c("lock"), lockFg = c("lockFg"),
            radius = t.getValue("radius").toFloat(), fw = t.getValue("fw").toInt(),
            fsScale = t.getValue("fsScale").toFloat(), hintFs = t.getValue("hintFs").toFloat(),
            hintFw = t.getValue("hintFw").toInt(), retIcon = t.getValue("retIcon"),
            keyShadow = CssShadow.parse(t.getValue("keyShadow")), fnShadow = CssShadow.parse(t.getValue("fnShadow")),
            actShadow = CssShadow.parse(t.getValue("actShadow")), linkFg = linkFg,
            actIsTransparent = t["act"] == "transparent",
        )
    }

    /**
     * Kb:400-406: glass keys over a blurred image; labels pick white or ink by contrast. The design
     * models the image under the keys as luminance 0.3 (Themes.dc.html:87-93); DogBoard passes the
     * chosen photo's measured luminance instead, so readability follows the real picture.
     */
    private fun glass(dim: Float, kop: Float, photoLuminance: Float = 0.3f): Map<String, String> {
        val under = photoLuminance * (1 - dim)
        val capL = kop * 1 + (1 - kop) * under
        val cW = 1.05 / (capL + 0.05)
        val cB = (capL + 0.05) / (lumHex("#1b1c1e") + 0.05)
        val light = cW >= cB
        val fgc = if (light) "#ffffff" else "#1b1c1e"
        fun a(x: Double) = "rgba(255,255,255,$x)"
        return mapOf(
            "bg" to "transparent", "key" to a(kop.toDouble()), "keyFg" to fgc, "fn" to a(String.format(java.util.Locale.ROOT, "%.3f", kop * 0.55).toDouble()), "fnFg" to fgc,
            "act" to "#f0b46a", "actFg" to "#3a2200", "chip" to "rgba(255,255,255,0.88)", "chipFg" to "#2b2b2b", "pop" to "#ffffff", "popFg" to "#1b1c1e",
            "sel" to "#f0b46a", "selFg" to "#3a2200", "hint" to if (light) "rgba(255,255,255,0.8)" else "rgba(27,28,30,0.75)",
            "div" to if (light) "rgba(255,255,255,0.35)" else "rgba(0,0,0,0.25)", "barFg" to fgc, "barMuted" to fgc,
            "tile" to a(max(0.2, kop.toDouble())), "tileFg" to fgc, "pressKey" to a(min(1.0, kop + 0.25)), "pressFn" to a(min(1.0, kop + 0.15)),
            "lock" to "#ffffff", "lockFg" to "#1b1c1e", "linkFg" to if (light) "#ffffff" else "#1b1c1e",
        )
    }

    // Kb:396-397
    private fun lin(c: Double): Double { val x = c / 255; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
    private fun lumHex(h: String): Double {
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

/** One layer of a CSS box-shadow: `[inset] x y [blur [spread]] colour`, lengths in dp. */
data class CssShadow(val inset: Boolean, val x: Float, val y: Float, val blur: Float, val spread: Float, val color: Color) {
    companion object {
        fun parse(value: String): List<CssShadow> {
            if (value.trim() == "none") return emptyList()
            return splitTopLevel(value).map { layer ->
                val tokens = Regex("""rgba\([^)]*\)|\S+""").findAll(layer.trim()).map { it.value }.toMutableList()
                val inset = tokens.remove("inset")
                val color = css(tokens.removeAt(tokens.indexOfFirst { it.startsWith("#") || it.startsWith("rgba") || it == "transparent" }))
                val n = tokens.map { it.removeSuffix("px").toFloat() }
                CssShadow(inset, n[0], n[1], n.getOrElse(2) { 0f }, n.getOrElse(3) { 0f }, color)
            }
        }

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
    }
}
