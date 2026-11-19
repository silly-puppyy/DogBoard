package io.github.sillypuppyy.dogboard.design

import kotlin.math.pow
import kotlin.math.round

/**
 * The photo theme's readability rules (SetPhoto.dc.html:128-171): how readable white or dark labels
 * are on the photo's brightest and darkest spots for a given dimming, key background (tint) and
 * blur; the automatic settings for a photo; and "Fix it".
 */
object PhotoReadability {
    /**
     * One version of a photo theme; [labels] auto / white / dark, [accent] = which of the photo's colours.
     * [bright]: the photo's brightness, 1 as shot, 0 black, 2 the brightest (user 2026-11-19: "0% - 200%"): below 1
     * a black veil ([dim]), above it a white wash ([wash], at most [WASH] at 2) so highlights don't burn out.
     */
    data class Look(
        val bright: Double = 1.0, val tint: Double = 0.0, val blur: Float = 0f,
        val labels: String = "auto", val halo: Boolean = true, val accent: Int = 0,
        /** Saturation (0 grey, 1 as shot, 2 vivid) and contrast (0.5..1.5, 1 as shot) of the photo (user 2026-11-19). */
        val sat: Double = 1.0, val contrast: Double = 1.0,
        /** A return-key colour the user picked (#rrggbb), in place of the photo's colour [accent]. */
        val actColor: String? = null,
        /** The key background [tint] is black rather than white (dark versions; user 2026-11-19). */
        val keyDark: Boolean = false,
    ) {
        val dim: Double get() = (1 - bright).coerceIn(0.0, 1.0)
        val wash: Double get() = ((bright - 1) * WASH).coerceIn(0.0, WASH)
    }

    /** The return key's colour and its label colour: the user's pick, else the photo's colour [Look.accent]. */
    fun accent(l: Look, accents: List<Triple<String, String, String>>?): Pair<String, String>? {
        l.actColor?.let { c -> return c to (if (KbThemes.contrast("#ffffff", c) >= KbThemes.contrast("#1b1c1e", c)) "#ffffff" else "#1b1c1e") }
        val a = accents?.getOrNull(l.accent) ?: accents?.firstOrNull() ?: return null
        return a.first to a.second
    }

    /** The white wash at 200 % brightness. */
    const val WASH = 0.6

    /** An sRGB-encoded level [v] after brightness [b] (the veil or the wash over it). */
    fun lit(v: Double, b: Double): Double = if (b <= 1) v * b.coerceAtLeast(0.0) else v * (1 - (b - 1) * WASH) + (b - 1) * WASH

    /** :133-135 contrast of white and of dark labels, the label colour used, and its ratio. */
    data class Result(val white: Double, val dark: Double, val fg: String) {
        val ratio: Double get() = if (fg == "white") white else dark
        val readable: Boolean get() = ratio >= 4.5
    }

    private fun toLin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    /** :128-136 */
    fun measure(a: GlassPhoto.Analysis, s: Look): Result {
        val (v5, v95) = a.at(s.blur)
        val k = s.tint
        fun con(v: Double) = ((v - 0.5) * s.contrast + 0.5).coerceIn(0.0, 1.0)
        // the key's tint mixes the photo toward white, or toward black with dark keys
        val kw = if (s.keyDark) 0.0 else k
        val hi = toLin(kw + (1 - k) * lit(con(v95), s.bright))
        val lo = toLin(kw + (1 - k) * lit(con(v5), s.bright))
        val white = 1.05 / (hi + 0.05)
        val dark = (lo + 0.05) / (0.0118 + 0.05)
        val fg = if (s.labels == "auto") (if (white >= dark) "white" else "dark") else s.labels
        return Result(white, dark, fg)
    }

    private fun r2(x: Double) = round(x * 100) / 100

    /**
     * :137-142 the automatic look, now per version (user 2026-11-19: a bright and a dark version of a photo):
     * dark, the least darkening that makes white labels readable (the design's rule); bright, dark labels, the photo
     * brightened up to 150 %, then milky keys as needed.
     */
    fun auto(a: GlassPhoto.Analysis, dark: Boolean = true): Look {
        if (dark) {
            // dark keys (a 20 % black tint) under white labels, then the least darkening that reads
            var b = 1.0
            while (b >= 0.1999) { val s = Look(bright = r2(b), tint = 0.2, labels = "white", keyDark = true); if (measure(a, s).white >= 4.5) return s; b -= 0.05 }
            return Look(bright = 0.2, tint = 0.2, labels = "white", keyDark = true)
        }
        var b = 1.0
        while (b <= 1.5001) { val s = Look(bright = r2(b), labels = "dark"); if (measure(a, s).dark >= 4.5) return s; b += 0.05 }
        var k = 0.1
        while (k <= 0.9001) { val s = Look(bright = 1.5, tint = r2(k), labels = "dark"); if (measure(a, s).dark >= 4.5) return s; k += 0.05 }
        return Look(bright = 1.5, tint = 0.9, labels = "dark")
    }

    /** :165-171 Fix it: darker for white labels; brighter, then more key background, for dark ones. */
    fun fix(a: GlassPhoto.Analysis, s: Look): Look {
        var x = s
        if (measure(a, x).fg == "white") {
            while (x.bright > 0.2 && measure(a, x).white < 4.5) x = x.copy(bright = r2(x.bright - 0.05))
        } else {
            while (x.bright < 1.5 && measure(a, x).dark < 4.5) x = x.copy(bright = r2(x.bright + 0.05))
            if (measure(a, x).dark < 4.5 && x.tint < 0.1) x = x.copy(tint = 0.1)
            while (x.tint < 0.9 && measure(a, x).dark < 4.5) x = x.copy(tint = r2(x.tint + 0.05))
        }
        return x
    }

    /** :174 what Auto picks, in words. */
    fun describe(a: GlassPhoto.Analysis, auto: Look): String {
        val fg = measure(a, auto).fg
        fun pc(v: Double) = "${round(v * 100).toInt()}%"
        return "$fg labels, " + (if (auto.bright == 1.0) "photo as shot" else "brightness ${pc(auto.bright)}") +
            (if (auto.tint > 0) ", keys tinted ${pc(auto.tint)}" else "") +
            ", no blur, return key from the photo"
    }
}

/**
 * A photo theme's versions (user 2026-11-19): "bright", "dark" or "both", each its own look; the dark version may
 * have its own photo ([darkPhoto], its brightness [darkLuminance], 0..1 linear). Stored as JSON (the keyboard's
 * photo slot pref, and a saved theme's params).
 */
data class PhotoVersions(
    val versions: String = "both",
    val bright: PhotoReadability.Look = PhotoReadability.Look(),
    val dark: PhotoReadability.Look = PhotoReadability.Look(),
    val darkPhoto: Boolean = false,
    val darkLuminance: Double = 0.3,
) {
    val hasBright: Boolean get() = versions != "dark"
    val hasDark: Boolean get() = versions != "bright"
    /** The Themes page's mode: a light, a dark or a day-and-night theme. */
    val mode: String get() = when (versions) { "bright" -> "light"; "dark" -> "dark"; else -> "pair" }

    fun look(dark: Boolean) = if (dark) this.dark else bright
    fun with(dark: Boolean, l: PhotoReadability.Look) = if (dark) copy(dark = l) else copy(bright = l)

    fun encode(): String = org.json.JSONObject().put("versions", versions).put("darkPhoto", darkPhoto).put("darkLum", (darkLuminance * 1000).toInt())
        .put("bright", enc(bright)).put("dark", enc(dark)).toString()

    companion object {
        private fun enc(l: PhotoReadability.Look) = org.json.JSONObject().put("b", (l.bright * 100).toInt()).put("k", (l.tint * 100).toInt())
            .put("blur", l.blur.toInt()).put("labels", l.labels).put("halo", l.halo).put("accent", l.accent)
            .put("s", (l.sat * 100).toInt()).put("c", (l.contrast * 100).toInt()).apply { l.actColor?.let { put("ac", it) } }.put("kd", l.keyDark)
        private fun dec(j: org.json.JSONObject?) = if (j == null) null else PhotoReadability.Look(j.optInt("b", 100) / 100.0, j.optInt("k", 0) / 100.0,
            j.optInt("blur", 0).toFloat(), j.optString("labels", "auto"), j.optBoolean("halo", true), j.optInt("accent", 0),
            j.optInt("s", 100) / 100.0, j.optInt("c", 100) / 100.0, j.optString("ac").takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) }, j.optBoolean("kd", false))

        /**
         * From [json]; a theme saved before versions ([old] its one look, [a] its photo's analysis) keeps that look
         * as the version its labels suit (white: dark) and gets the other one matched.
         */
        fun decode(json: String?, old: PhotoReadability.Look? = null, a: GlassPhoto.Analysis? = null): PhotoVersions {
            val j = json?.takeIf { it.isNotBlank() }?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            if (j != null && j.has("versions")) return PhotoVersions(j.optString("versions", "both"), dec(j.optJSONObject("bright")) ?: PhotoReadability.Look(),
                dec(j.optJSONObject("dark")) ?: PhotoReadability.Look(), j.optBoolean("darkPhoto", false), j.optInt("darkLum", 300) / 1000.0)
            if (a == null) return PhotoVersions(bright = PhotoReadability.Look(bright = 1.3, labels = "dark"), dark = PhotoReadability.Look(bright = 0.65, labels = "white"))
            val o = old ?: return auto(a)
            val oldIsDark = PhotoReadability.measure(a, o).fg == "white"
            return if (oldIsDark) PhotoVersions(bright = PhotoReadability.auto(a, false), dark = o) else PhotoVersions(bright = o, dark = PhotoReadability.auto(a, true))
        }

        /** Both versions matched to a photo. */
        fun auto(a: GlassPhoto.Analysis, darkA: GlassPhoto.Analysis? = null) =
            PhotoVersions(bright = PhotoReadability.auto(a, false), dark = PhotoReadability.auto(darkA ?: a, true))
    }
}

/** The keyboard's photo slot: its versions, from the versions pref or (a photo set before versions) the old prefs. */
object PhotoSlot {
    fun oldLook(prefs: dev.patrickgold.florisboard.app.FlorisPreferenceModel) = PhotoReadability.Look(
        1 - prefs.keyboard.glassDim.get() / 100.0, prefs.keyboard.glassKeyOpacity.get() / 100.0, prefs.keyboard.glassBlur.get().toFloat(),
        prefs.keyboard.glassLabels.get(), prefs.keyboard.glassHalo.get(), prefs.keyboard.glassAccent.get())

    /** Reads files: call off the main thread. */
    fun versions(context: android.content.Context, prefs: dev.patrickgold.florisboard.app.FlorisPreferenceModel): PhotoVersions =
        PhotoVersions.decode(prefs.keyboard.glassLooks.get(), oldLook(prefs), GlassPhoto.analysis(context)).also { ThemeChoice.glassMode = it.mode }
}
