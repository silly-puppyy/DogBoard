package io.github.sillypuppyy.dogboard.design

import com.materialkolor.contrast.Contrast
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The theme editor's model (design ThemeEditor.dc.html, lib(): notes/design/port-map-theme-editor.md), ported line by
 * line: a theme has a bright version, a dark one or both; each version is a background (solid, gradient or photo), a
 * "tint keys to match" switch and the colours the person set. [resolve] gives each key role its colour (yours, else
 * derived from the background when tinting, else the defaults); [toRoles] the keyboard's theme tokens (KbTheme).
 * Colours are CSS strings as in the design ("#rrggbb", "rgba(r,g,b,a)", "none").
 */
object ThemeModel {
    // ---- colour helpers (lib: P, H2, S, mix, over, lin, lum, cr, clamp)

    data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double)

    fun parse(c: String?): Rgba {
        if (c == null || c == "transparent" || c == "none") return Rgba(0.0, 0.0, 0.0, 0.0)
        Regex("""^rgba?\(([^)]+)\)$""").find(c)?.let { m ->
            val p = m.groupValues[1].split(',').map { it.trim().toDouble() }
            return Rgba(p[0], p[1], p[2], p.getOrElse(3) { 1.0 })
        }
        var h = c.removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        val n = h.substring(0, 6).toLong(16)
        val a = if (h.length >= 8) h.substring(6, 8).toLong(16) / 255.0 else 1.0
        return Rgba(((n shr 16) and 255).toDouble(), ((n shr 8) and 255).toDouble(), (n and 255).toDouble(), a)
    }

    private fun h2(n: Double) = jsRound(n).coerceIn(0, 255).toString(16).padStart(2, '0')

    /** JavaScript's Math.round (halves up, also below zero). */
    private fun jsRound(v: Double): Int = kotlin.math.floor(v + 0.5).toInt()

    private fun fmtAlpha(a: Double): String {
        val r = (a * 1000).let { kotlin.math.floor(it + 0.5) } / 1000
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    fun str(o: Rgba): String = if (o.a < 0.999) "rgba(${jsRound(o.r)},${jsRound(o.g)},${jsRound(o.b)},${fmtAlpha(o.a)})" else "#${h2(o.r)}${h2(o.g)}${h2(o.b)}"

    fun mix(a: String, b: String, t: Double): String {
        val x = parse(a); val y = parse(b)
        return str(Rgba(x.r + (y.r - x.r) * t, x.g + (y.g - x.g) * t, x.b + (y.b - x.b) * t, x.a + (y.a - x.a) * t))
    }

    fun over(fg: String, bg: String): String {
        val f = parse(fg); val b = parse(bg)
        return str(Rgba(f.r * f.a + b.r * (1 - f.a), f.g * f.a + b.g * (1 - f.a), f.b * f.a + b.b * (1 - f.a), 1.0))
    }

    private fun lin(v0: Double): Double { val v = v0 / 255; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    fun lum(c: String): Double { val o = parse(c); return 0.2126 * lin(o.r) + 0.7152 * lin(o.g) + 0.0722 * lin(o.b) }
    fun cr(a: String, b: String): Double { val x = lum(a); val y = lum(b); return (max(x, y) + 0.05) / (min(x, y) + 0.05) }
    private fun clamp(v: Double, a: Double, b: Double) = max(a, min(b, v))

    // ---- Material HCT (lib: hct, pal, at, onTone)

    private fun argb(hex: String): Int { val o = parse(hex); return (0xFF shl 24) or (jsRound(o.r) shl 16) or (jsRound(o.g) shl 8) or jsRound(o.b) }
    private fun hex(argb: Int): String = "#" + listOf(16, 8, 0).joinToString("") { ((argb shr it) and 255).toString(16).padStart(2, '0') }
    fun hct(c: String): Hct = Hct.fromInt(argb(over(c, "#808080")))

    /** A palette's colour at [tone] (lib: at(pal(h, ch), tone)): tone rounded to 0.1, as the design. */
    private fun at(h: Double, ch: Double, tone: Double): String = hex(Hct.from(h, ch, clamp((tone * 10).roundToInt() / 10.0, 0.0, 100.0)).toInt())

    private fun onTone(t: Double, wantDark: Boolean): Double {
        val d = Contrast.darker(t, 4.5); val l = Contrast.lighter(t, 4.5)
        return if (wantDark) (if (d >= 0) min(d, 10.0) else l) else (if (l >= 0) max(l, 90.0) else d)
    }

    // lib: warmth, shiftHue, tone
    private fun warmth(h: Double): Double { val d = abs(((h - 60 + 540) % 360) - 180); return if (d <= 30) 1.0 else if (d >= 60) 0.0 else (60 - d) / 30 }
    private fun shiftHue(h: Double, from: Double, to: Double) = (h + clamp(0.3 * (to - from), -15.0, 15.0) * warmth(h) + 360) % 360

    private data class Seed(val h: Double, val t: Double)

    private fun tone(seed: Seed, ch: Double, t: Double): String {
        var h = shiftHue(seed.h, seed.t, t)
        if (h in 90.0..111.0 && ch > 16 && t < 65) h = 86.0
        return hex(Hct.from(h, ch, clamp(t, 0.0, 100.0)).toInt())
    }

    // ---- the model

    val ROLES = listOf("key", "keyFg", "keyLine", "fn", "fnFg", "fnLine", "act", "actFg", "actLine", "barFg", "chip", "chipFg", "hint", "pop", "popFg", "barIcon")
    private val LABEL_OF = mapOf("keyFg" to "key", "fnFg" to "fn", "actFg" to "act", "chipFg" to "chip", "popFg" to "pop")

    val DEFAULTS = mapOf(
        "light" to mapOf("bg" to "#ededf4", "key" to "#ffffff", "keyFg" to "#1a1b20", "fn" to "#dbe2f9", "fnFg" to "#3f4759", "act" to "#435e91", "actFg" to "#ffffff", "chip" to "#d8e2ff", "chipFg" to "#2a4678", "pop" to "#ffffff", "popFg" to "#1a1b20", "hint" to "#6b6e76", "barFg" to "#1a1b20", "keyLine" to "none", "fnLine" to "none", "actLine" to "none"),
        "dark" to mapOf("bg" to "#1a1b20", "key" to "#33353a", "keyFg" to "#e2e2e9", "fn" to "#3f4759", "fnFg" to "#dbe2f9", "act" to "#adc7ff", "actFg" to "#0f2f60", "chip" to "#2a4678", "chipFg" to "#d8e2ff", "pop" to "#45474c", "popFg" to "#e2e2e9", "hint" to "#b4b7c0", "barFg" to "#e2e2e9", "keyLine" to "none", "fnLine" to "none", "actLine" to "none"),
    )

    @kotlinx.serialization.Serializable
    data class Stop(val c: String, val p: Int)

    /**
     * A version's background (lib: bgOf). For a photo: its main colours [photoCols] and accents [photoAcc], measured.
     * [hard]: a gradient's colours as bands with sharp edges (a flag) instead of blending.
     */
    @kotlinx.serialization.Serializable
    data class Bg(
        val type: String = "solid", val color: String = "#ededf4",
        val stops: List<Stop> = listOf(Stop("#ffe3d1", 0), Stop("#f4c4d8", 100)), val angle: Int = 180,
        val photo: String = "", val pos: String = "center", val dim: Double = 0.0,
        val photoCols: List<String> = emptyList(), val photoAcc: List<String> = emptyList(),
        val hard: Boolean = false,
    )

    @kotlinx.serialization.Serializable
    /** [nav]: the colour of Android's own icons under the keys (hide keyboard, switch keyboard): auto, light or dark. */
    data class Version(val bg: Bg = Bg(), val tint: Boolean = false, val ov: Map<String, String> = emptyMap(), val nav: String = "auto")

    /**
     * lib SHAPE0: corner radius (dp), label weight, label size (%), border width (dp). [keys]: how keys sit on the
     * background: auto (the colours as set), solid (no see-through), frosted (see-through white) or under (each key
     * the colour beneath it, lightened).
     */
    @kotlinx.serialization.Serializable
    data class Shape(val radius: Double = 10.0, val weight: Int = 400, val size: Int = 100, val line: Double = 1.5, val keys: String = "auto")

    private fun dimc(c: String, d: Double) = if (d != 0.0) mix(c, "#000000", d) else c

    fun bgCols(bg: Bg): List<String> = when (bg.type) {
        "solid" -> listOf(bg.color)
        "gradient" -> bg.stops.map { it.c }
        else -> bg.photoCols.map { dimc(it, bg.dim) }
    }

    private fun bgAcc(bg: Bg) = if (bg.type == "photo") bg.photoAcc else bgCols(bg)

    /** lib baseOf: light or dark starting colours, by which label tone reads better on all of the background. */
    fun baseOf(bg: Bg): String {
        val cols = bgCols(bg)
        val dk = cols.minOf { cr(it, "#1a1b20") }; val lt = cols.minOf { cr(it, "#e2e2e9") }
        return if (dk >= lt) "light" else "dark"
    }

    /** lib tintSolid: every key colour from one background colour (its family at Material's role tones). */
    fun tintSolid(col: String): Map<String, String> {
        val s = hct(col); val grey = s.chroma < 4
        val seed = Seed(if (grey) 265.0 else s.hue, s.tone); val c = if (grey) 0.0 else s.chroma; val T = s.tone
        fun k(m: Double, lo: Double, hi: Double) = clamp(c * m, lo, hi)
        val cA = if (grey) 36.0 else min(max(c * 2.8, 16.0), max(c, 48.0))
        fun pick(fill: String, want: String, alt: String) = if (cr(fill, want) >= 4.5) want else alt
        if (baseOf(Bg(type = "solid", color = col)) == "light") {
            val pale = T > 94; val vivid = c >= 20 && !pale
            val tk = if (pale) T - 5 else if (vivid) 97.0 else T + max(8.0, (100 - T) * 0.72)
            val tf = if (pale) T - 13 else if (vivid) (if (T <= 76) 88.0 else T - 8) else if (T >= 62) T - 6.5 else max(T + 8, tk - 10)
            val key = tone(seed, k(0.45, 2.0, 8.0), tk)
            val fn = tone(seed, if (vivid) k(0.6, 16.0, 28.0) else k(1.35, 6.0, 20.0), tf)
            val tfl = min(26.0, max(0.0, Contrast.darker(tf, 4.5)))
            val act = tone(seed, cA, min(42.5, T - 24))
            val tc = if (T > 92) T - 4 else if (T >= 80) T + 2 else 90.0
            var tb = 14.0
            while (tb > 0 && cr(tone(seed, k(0.9, 4.0, 10.0), tb), col) < 4.5) tb -= 2
            return mapOf(
                "key" to key, "keyFg" to tone(seed, k(0.9, 4.0, 10.0), 14.0), "fn" to fn, "fnFg" to tone(seed, k(1.4, 8.0, 16.0), tfl),
                "act" to act, "actFg" to pick(act, "#ffffff", tone(seed, k(1.0, 4.0, 12.0), 8.0)),
                "chip" to tone(seed, k(1.5, 8.0, 24.0), tc), "chipFg" to tone(seed, k(2.2, 12.0, 40.0), 30.0),
                "pop" to key, "popFg" to tone(seed, k(0.9, 4.0, 10.0), 14.0), "hint" to tone(seed, k(1.3, 4.0, 14.0), min(45.0, tk - 50)),
                "barFg" to tone(seed, k(0.9, 4.0, 10.0), tb),
            )
        }
        val up = T <= 22
        val tk = if (up) T + 11.5 else T - 11.5; val tf = if (up) T + 6 else T - 6; val tc = if (up) T + 17 else max(0.0, T - 17)
        val key = tone(seed, k(1.4, 4.0, 16.0), tk); val act = tone(seed, cA, max(62.0, T + 45))
        var tb = 92.0
        while (tb < 100 && cr(tone(seed, k(0.55, 3.0, 8.0), tb), col) < 4.5) tb += 2
        return mapOf(
            "key" to key, "keyFg" to tone(seed, k(0.55, 3.0, 8.0), 92.0), "fn" to tone(seed, k(1.2, 4.0, 14.0), tf), "fnFg" to tone(seed, k(1.0, 4.0, 12.0), 84.0),
            "act" to act, "actFg" to pick(act, tone(seed, k(1.0, 4.0, 12.0), 7.0), "#ffffff"),
            "chip" to tone(seed, k(1.8, 8.0, 24.0), tc), "chipFg" to tone(seed, k(1.2, 6.0, 16.0), 89.0),
            "pop" to tone(seed, k(1.5, 4.0, 16.0), min(tk + 6, 36.0)), "popFg" to tone(seed, k(0.55, 3.0, 8.0), 92.0),
            "hint" to tone(seed, k(1.1, 4.0, 12.0), max(74.0, Contrast.lighter(tk, 4.5))), "barFg" to tone(seed, k(0.55, 3.0, 8.0), min(100.0, tb)),
        )
    }

    /** lib tinted: a solid background as [tintSolid]; a gradient or photo varies, so keys are frosted and labels read on all of it. */
    fun tinted(bg: Bg): Map<String, String> {
        if (bg.type == "solid") return tintSolid(bg.color)
        val cols = bgCols(bg)
        val a0 = bgAcc(bg).map { it to hct(it) }.maxBy { it.second.chroma }.second
        val grey = a0.chroma < 4; val seed = Seed(if (grey) 265.0 else a0.hue, a0.tone); val c = if (grey) 0.0 else a0.chroma
        val cA = if (grey) 36.0 else min(max(c * 2.8, 16.0), max(c, 48.0))
        fun white(a: Double) = cols.minOf { cr("#ffffff", over("rgba(255,255,255,$a)", it)) }
        if (white(0.14) >= 4.5) {
            val act = tone(seed, min(cA, 28.0), 85.0)
            return mapOf(
                "key" to "rgba(255,255,255,0.14)", "keyFg" to "#ffffff", "fn" to "rgba(255,255,255,0.07)", "fnFg" to "#ffffff",
                "act" to act, "actFg" to tone(seed, clamp(c, 8.0, 24.0), 15.0), "chip" to "rgba(255,255,255,0.22)", "chipFg" to "#ffffff",
                "pop" to tone(seed, clamp(c * 0.6, 6.0, 20.0), 25.0), "popFg" to "#ffffff", "hint" to "rgba(255,255,255,0.72)", "barFg" to "#ffffff",
            )
        }
        val ink = tone(seed, clamp(c * 0.3, 2.0, 8.0), 10.0)
        var a = 0.4
        while (a < 0.9 && cols.minOf { cr(ink, over("rgba(255,255,255,$a)", it)) } < 4.5) a = jsRound((a + 0.05) * 100) / 100.0
        val act = tone(seed, cA, 40.0)
        return mapOf(
            "key" to "rgba(255,255,255,${fmtAlpha(a)})", "keyFg" to ink, "fn" to "rgba(255,255,255,${"%.2f".format(java.util.Locale.ROOT, max(0.2, a - 0.3))})", "fnFg" to ink,
            "act" to act, "actFg" to (if (cr(act, "#ffffff") >= 4.5) "#ffffff" else ink), "chip" to "rgba(255,255,255,0.85)", "chipFg" to ink,
            "pop" to "#ffffff", "popFg" to ink, "hint" to tone(seed, clamp(c * 0.5, 4.0, 12.0), 40.0), "barFg" to ink,
        )
    }

    /** lib otherVersion: the bright version from the dark one or the dark from the bright: the same family, moved over. */
    fun otherVersion(v: Version, want: String): Version {
        fun move(x: String): String { val s = hct(x); val seed = Seed(s.hue, s.tone); return if (want == "dark") tone(seed, min(s.chroma * 1.1, 24.0), min(s.tone, 12.0)) else tone(seed, min(s.chroma, 16.0), max(s.tone, 92.0)) }
        fun moveStop(x: String): String { val s = hct(x); val seed = Seed(s.hue, s.tone); return if (want == "dark") tone(seed, min(s.chroma, 40.0), min(s.tone, 24.0)) else tone(seed, min(s.chroma, 30.0), max(s.tone, 85.0)) }
        val bg = when (v.bg.type) {
            "solid" -> v.bg.copy(color = move(v.bg.color))
            "gradient" -> v.bg.copy(stops = v.bg.stops.map { Stop(moveStop(it.c), it.p) })
            else -> v.bg.copy(dim = if (want == "dark") 0.45 else 0.0)
        }
        return Version(bg, tint = true, ov = emptyMap())
    }

    /** Where a role's colour came from: yours, worked out from your fill (auto), tinted, or the defaults. */
    data class Resolved(val vals: Map<String, String>, val src: Map<String, String>, val base: String)

    /** lib resolve: each role's colour, and where it came from. */
    fun resolve(v: Version): Resolved {
        val base = baseOf(v.bg); val d = DEFAULTS.getValue(base)
        val tn = if (v.tint) tinted(v.bg) else emptyMap()
        val out = LinkedHashMap<String, String>(); val src = LinkedHashMap<String, String>()
        for (r in ROLES) {
            v.ov[r]?.let { out[r] = it; src[r] = "yours"; continue }
            val fillRole = LABEL_OF[r]
            val fill = fillRole?.let { v.ov[it] }
            if (fill != null) {
                // a label on a fill you set: the label palette's colour at a tone that reads on it
                val f = over(fill, bgCols(v.bg)[0]); val t = hct(f).tone
                val p0 = hct(tn["keyFg"] ?: d.getValue("keyFg"))
                out[r] = at(p0.hue, min(p0.chroma, 12.0), onTone(t, cr(f, "#000000") >= cr(f, "#ffffff"))); src[r] = "auto"; continue
            }
            if (r.endsWith("Line")) { out[r] = "none"; src[r] = "default"; continue }
            // the bar's icons (tools, mic) follow the bar's words unless set
            if (r == "barIcon") { out[r] = out.getValue("barFg"); src[r] = src.getValue("barFg"); continue }
            tn[r]?.let { out[r] = it; src[r] = "tinted"; continue }
            out[r] = d.getValue(r); src[r] = "default"
        }
        return Resolved(out, src, base)
    }

    /** lib toRoles: the keyboard's theme tokens (KbTheme names) for a version and the shared shape; bg is the CSS background. */
    fun toRoles(v: Version, shape: Shape, bgCss: String): Map<String, String> {
        val res = resolve(v); val x = HashMap(res.vals); val dark = res.base == "dark"
        val back = bgCols(v.bg)[0]
        when (shape.keys) {
            "solid" -> for (r in listOf("key", "fn")) if (r !in v.ov) x[r] = over(x.getValue(r), back)
            "frosted" -> {
                // white glass: labels white on a dark background, ink on a light one, unless set by hand
                if ("key" !in v.ov) x["key"] = if (dark) "rgba(255,255,255,0.16)" else "rgba(255,255,255,0.5)"
                if ("fn" !in v.ov) x["fn"] = if (dark) "rgba(255,255,255,0.08)" else "rgba(255,255,255,0.28)"
                val label = if (dark) "#ffffff" else DEFAULTS.getValue("light").getValue("keyFg")
                if ("keyFg" !in v.ov) x["keyFg"] = label
                if ("fnFg" !in v.ov) x["fnFg"] = label
            }
        }
        fun line(c: String?) = if (c != null && c != "none") "inset 0 0 0 ${num(shape.line)}px $c" else "none"
        fun up(c: String, t: String): String { val o = parse(c); return if (o.a < 0.999) str(o.copy(a = min(1.0, o.a + 0.12))) else mix(c, t, 0.12) }
        return mapOf(
            "bg" to bgCss, "key" to x.getValue("key"), "keyFg" to x.getValue("keyFg"), "fn" to x.getValue("fn"), "fnFg" to x.getValue("fnFg"),
            "act" to x.getValue("act"), "actFg" to x.getValue("actFg"), "chip" to x.getValue("chip"), "chipFg" to x.getValue("chipFg"),
            "pop" to x.getValue("pop"), "popFg" to x.getValue("popFg"), "sel" to x.getValue("act"), "selFg" to x.getValue("actFg"),
            "lock" to x.getValue("act"), "lockFg" to x.getValue("actFg"), "hint" to x.getValue("hint"), "barFg" to x.getValue("barFg"),
            "barMuted" to x.getValue("barIcon"), "navIcons" to v.nav, "div" to mix(x.getValue("barFg"), x.getValue("key"), 0.7), "tile" to x.getValue("key"), "tileFg" to x.getValue("keyFg"),
            "pressKey" to up(x.getValue("key"), x.getValue("keyFg")), "pressFn" to up(x.getValue("fn"), x.getValue("fnFg")),
            "radius" to num(shape.radius), "fw" to shape.weight.toString(), "fsScale" to num(shape.size / 100.0),
            "keyShadow" to line(x["keyLine"]), "fnShadow" to line(x["fnLine"]), "actShadow" to line(x["actLine"]), "linkFg" to x.getValue("act"),
        ) + (if (shape.keys == "under") mapOf("keyUnder" to "0.55") else emptyMap())
    }

    /**
     * A theme made in the editor (lib: theme()): a bright version, a dark one or both ([versions] "light", "dark",
     * "both"), the shape, sound and credits shared. Stored as its own theme id: "te:" + JSON, as gradients are.
     */
    @kotlinx.serialization.Serializable
    data class Theme(
        val name: String, val versions: String = "light", val light: Version? = null, val dark: Version? = null,
        val shape: Shape = Shape(), val sound: String? = null, val author: String = "", val desc: String = "",
        val who: String = "", val link: String = "", val licence: String = "",
    ) {
        /** The version for now: the dark one in dark mode when there is one, else the one there is. */
        fun version(darkMode: Boolean): Version = (if (darkMode) dark ?: light else light ?: dark) ?: Version()
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }
    const val PREFIX = "te:"

    fun isSpec(id: String) = id.startsWith(PREFIX)
    fun encode(t: Theme): String = PREFIX + json.encodeToString(Theme.serializer(), t)
    fun decode(id: String): Theme? = if (!isSpec(id)) null else runCatching { json.decodeFromString(Theme.serializer(), id.removePrefix(PREFIX)) }.getOrNull()

    /** The keyboard tokens of an editor theme for the mode (a photo background is drawn by the photo layer). */
    fun tokensOf(t: Theme, darkMode: Boolean): Map<String, String> { val v = t.version(darkMode); return toRoles(v, t.shape, bgCss(v.bg)) }

    // ---- the colour picker (lib: toHsv, fromHsv)

    data class Hsv(val h: Double, val s: Double, val v: Double, val a: Double = 1.0)

    fun toHsv(c: String?): Hsv {
        val o = parse(if (c != null && c != "none") c else "#888888")
        val r = o.r / 255; val g = o.g / 255; val b = o.b / 255
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        var h = 0.0
        if (d != 0.0) {
            h = when (mx) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
            h = (h * 60 + 360) % 360
        }
        return Hsv(h, if (mx != 0.0) d / mx else 0.0, mx, o.a)
    }

    fun fromHsv(k: Hsv): String {
        fun f(n: Int): Double { val q = (n + k.h / 60) % 6; return k.v - k.v * k.s * max(0.0, minOf(q, 4 - q, 1.0)) }
        return str(Rgba(f(5) * 255, f(3) * 255, f(1) * 255, k.a))
    }

    /**
     * A built-in theme as an editor theme (Copy and edit, TEHold): its bright and dark colours as yours on its
     * background, its corners and label weight kept.
     */
    fun fromBuiltIn(name: String, light: Map<String, String>?, dark: Map<String, String>?): Theme {
        fun ver(m: Map<String, String>?): Version? = m?.let { t ->
            val bg = t["bg"] ?: return@let null
            val g = CssGradient.parse(bg)
            val back = if (g != null) Bg(type = "gradient", stops = g.stops.mapIndexed { i, (c, p) -> Stop(cssHex(c), (p * 100).roundToInt()) }, angle = g.angleDeg.toInt())
                else Bg(color = bg)
            Version(back, tint = false, ov = ROLES.filter { !it.endsWith("Line") }.mapNotNull { r -> t[r]?.let { r to it } }.toMap())
        }
        val l = ver(light); val d = ver(dark)
        val any = light ?: dark ?: emptyMap()
        return Theme(
            name, versions = if (l != null && d != null) "both" else if (d != null) "dark" else "light", light = l, dark = d,
            shape = Shape(radius = any["radius"]?.toDoubleOrNull() ?: 10.0, weight = any["fw"]?.toIntOrNull() ?: 400,
                keys = if ((any["keyUnder"]?.toFloatOrNull() ?: 0f) > 0f) "under" else "auto"),
        )
    }

    private fun cssHex(c: androidx.compose.ui.graphics.Color): String {
        val a = c.alpha; val r = (c.red * 255); val g = (c.green * 255); val b = (c.blue * 255)
        return str(Rgba(r.toDouble(), g.toDouble(), b.toDouble(), a.toDouble()))
    }

    private fun num(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** lib bgCss for solid and gradient backgrounds (a photo is drawn by the photo theme's own layer). */
    /** Hard edges: each stop's colour fills its band, the edges halfway between neighbouring stops (a stop marks a band's middle). */
    private fun hardCss(bg: Bg): String {
        val s = bg.stops.sortedBy { it.p }
        val parts = s.indices.flatMap { i ->
            val from = if (i == 0) 0.0 else (s[i - 1].p + s[i].p) / 2.0
            val to = if (i == s.lastIndex) 100.0 else (s[i].p + s[i + 1].p) / 2.0
            listOf("${s[i].c} ${num(from)}%", "${s[i].c} ${num(to)}%")
        }
        return "linear-gradient(${bg.angle}deg, " + parts.joinToString(", ") + ")"
    }

    fun bgCss(bg: Bg): String = when (bg.type) {
        "solid" -> bg.color
        "gradient" -> if (bg.hard) hardCss(bg) else "linear-gradient(${bg.angle}deg, " + bg.stops.joinToString(", ") { "${it.c} ${it.p}%" } + ")"
        else -> "linear-gradient(rgba(0,0,0,${num(bg.dim)}), rgba(0,0,0,${num(bg.dim)}))"
    }
}
