package io.github.sillypuppyy.dogboard.design

import kotlin.math.pow
import kotlin.math.round

/**
 * The photo theme's readability rules (SetPhoto.dc.html:128-171): how readable white or dark labels
 * are on the photo's brightest and darkest spots for a given dimming, key background (tint) and
 * blur; the automatic settings for a photo; and "Fix it".
 */
object PhotoReadability {
    /** The photo theme's settings; [labels] auto / white / dark, [accent] = which of the photo's colours. */
    data class Look(
        val dim: Double = 0.0, val tint: Double = 0.0, val blur: Float = 0f,
        val labels: String = "auto", val halo: Boolean = true, val accent: Int = 0,
    )

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
        val hi = toLin(k + (1 - k) * v95 * (1 - s.dim))
        val lo = toLin(k + (1 - k) * v5 * (1 - s.dim))
        val white = 1.05 / (hi + 0.05)
        val dark = (lo + 0.05) / (0.0118 + 0.05)
        val fg = if (s.labels == "auto") (if (white >= dark) "white" else "dark") else s.labels
        return Result(white, dark, fg)
    }

    private fun r2(x: Double) = round(x * 100) / 100

    /** :137-142 the least dimming that makes white labels readable, else the least key background for dark ones. */
    fun auto(a: GlassPhoto.Analysis): Look {
        var d = 0.0
        while (d <= 0.6001) { val s = Look(dim = r2(d)); if (measure(a, s).white >= 4.5) return s; d += 0.05 }
        var k = 0.1
        while (k <= 0.9001) { val s = Look(tint = r2(k)); if (measure(a, s).dark >= 4.5) return s; k += 0.05 }
        return Look(dim = 0.8)
    }

    /** :165-171 Fix it: more dimming for white labels, more key background for dark ones, until readable. */
    fun fix(a: GlassPhoto.Analysis, s: Look): Look {
        var x = s
        if (measure(a, x).fg == "white") {
            while (x.dim < 0.8 && measure(a, x).white < 4.5) x = x.copy(dim = r2(x.dim + 0.05))
        } else {
            if (x.tint < 0.1) x = x.copy(tint = 0.1)
            while (x.tint < 0.9 && measure(a, x).dark < 4.5) x = x.copy(tint = r2(x.tint + 0.05))
        }
        return x
    }

    /** :174 what Auto picks, in words. */
    fun describe(a: GlassPhoto.Analysis, auto: Look): String {
        val fg = measure(a, auto).fg
        fun pc(v: Double) = "${round(v * 100).toInt()}%"
        return (if (auto.tint == 0.0) "$fg labels, " + (if (auto.dim > 0) "photo dimmed ${pc(auto.dim)}" else "no dimming") else "dark labels on keys tinted ${pc(auto.tint)}") +
            ", no blur, return key from the photo"
    }
}
