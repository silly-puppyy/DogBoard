package io.github.sillypuppyy.dogboard.ime

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a copied text is, for the designer's Clipboard page (colours and one-time codes; user
 * 2026-11-17: no sums, "false positives are worse than false negatives"). Only exact matches count:
 * the whole clip must be the thing, else it stays plain text.
 */
object ClipKinds {
    /** A colour: its ARGB value and the formats it can be pasted as (the copied one first). */
    data class ClipColor(val argb: Long, val copied: String) {
        val hex: String get() = "#%06X".format(argb and 0xFFFFFF)
        val rgb: String get() = "rgb(${r} ${g} ${b})"
        val hsl: String get() {
            val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
            val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf); val d = max - min
            val l = (max + min) / 2
            val s = if (d == 0.0) 0.0 else d / (1 - abs(2 * l - 1))
            val h = when {
                d == 0.0 -> 0.0
                max == rf -> 60 * (((gf - bf) / d).mod(6.0))
                max == gf -> 60 * ((bf - rf) / d + 2)
                else -> 60 * ((rf - gf) / d + 4)
            }
            return "hsl(${h.roundToInt() % 360} ${(s * 100).roundToInt()}% ${(l * 100).roundToInt()}%)"
        }
        private val r get() = ((argb shr 16) and 255).toInt()
        private val g get() = ((argb shr 8) and 255).toInt()
        private val b get() = (argb and 255).toInt()

        /** The other formats to offer, without the one that was copied. */
        fun others(): List<Pair<String, String>> {
            val c = copied.trim().lowercase()
            return listOf("HEX" to hex, "RGB" to rgb, "HSL" to hsl).filter { (name, _) ->
                !(name == "HEX" && c.startsWith("#")) && !(name == "RGB" && c.startsWith("rgb")) && !(name == "HSL" && c.startsWith("hsl"))
            }
        }
    }

    private val HEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
    private val RGB = Regex("^rgba?\\(\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})\\s*\\)$", RegexOption.IGNORE_CASE)
    private val HSL = Regex("^hsla?\\(\\s*(\\d{1,3})(?:deg)?\\s*[, ]\\s*(\\d{1,3})%\\s*[, ]\\s*(\\d{1,3})%\\s*\\)$", RegexOption.IGNORE_CASE)

    /** The clip as a colour: #RGB, #RRGGBB, rgb(r g b) or rgb(r, g, b), hsl(h s% l%); else null. */
    fun color(text: String): ClipColor? {
        val t = text.trim()
        HEX.matchEntire(t)?.let { m ->
            val h = m.groupValues[1].let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
            return ClipColor(0xFF000000 or h.toLong(16), t)
        }
        RGB.matchEntire(t)?.let { m ->
            val (r, g, b) = m.destructured.toList().map { it.toInt() }
            if (r > 255 || g > 255 || b > 255) return null
            return ClipColor(0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong(), t)
        }
        HSL.matchEntire(t)?.let { m ->
            val (h, s, l) = m.destructured.toList().map { it.toInt() }
            if (h > 360 || s > 100 || l > 100) return null
            val sf = s / 100.0; val lf = l / 100.0
            val c = (1 - abs(2 * lf - 1)) * sf
            val x = c * (1 - abs(((h / 60.0) % 2) - 1))
            val m0 = lf - c / 2
            val (r1, g1, b1) = when ((h % 360) / 60) {
                0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
                3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
            }
            fun ch(v: Double) = ((v + m0) * 255).roundToInt().coerceIn(0, 255).toLong()
            return ClipColor(0xFF000000 or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1), t)
        }
        return null
    }

    private val CODE = Regex("^(\\d{3,4})[ -]?(\\d{3,4})$|^\\d{4,8}$")

    /**
     * A one-time code, as digits, when the clip is only 4-8 digits (one space or dash between two
     * groups allowed: 482 913, 482-913) AND the field asks for a number: elsewhere a copied number is
     * just a number (a price, a year), so it stays plain text.
     */
    fun code(text: String, numberField: Boolean): String? {
        if (!numberField) return null
        val t = text.trim()
        if (!CODE.matches(t)) return null
        return t.filter { it.isDigit() }.takeIf { it.length in 4..8 }
    }
}
