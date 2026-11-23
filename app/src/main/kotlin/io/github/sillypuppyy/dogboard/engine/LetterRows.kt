package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.KeyTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A language's letter keys as rows, with each row's start in key widths (i18n step 4: one source for the
 * engine's own key geometry; the pack's rules.json letter_rows). Used where no live keyboard is at hand: the
 * fallback key targets (QwertyGeometry), the slipped-key neighbours of the look-back and of DigitWords. The
 * default is DogBoard's English QWERTY (tools/layouts/gen_qwerty_en.py: home row half a key in, bottom letter row
 * after shift 1.28 and a 0.2133 gap). The swipe model's own key units (SwipePath.KEY_UNITS) belong to the model.
 */
@Serializable
data class LetterRows(
    @SerialName("rows") val rows: List<String> = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
    @SerialName("offsets") val offsets: List<Float> = listOf(0f, 0.5f, 1.28f + 0.2133f),
) {
    /** Each letter's key centre in key widths (x across, y = row index + 0.5). */
    @kotlinx.serialization.Transient
    val centres: Map<Char, Pair<Float, Float>> = buildMap {
        for ((r, letters) in rows.withIndex()) {
            // a layout's marks (AZERTY's ', Colemak's ;) hold their place but aren't letters
            for ((i, c) in letters.withIndex()) if (c.isLetter()) put(c, (offsets.getOrElse(r) { 0f } + i + 0.5f) to (r + 0.5f))
        }
    }

    /** The letters whose keys touch [c]'s (centres within one key across and one row). */
    fun neighbours(c: Char): List<Char> {
        val (x, y) = centres[c] ?: return emptyList()
        return centres.filter { (k, p) -> k != c && kotlin.math.abs(p.second - y) <= 1.01f && kotlin.math.abs(p.first - x) <= 1.01f }
            .keys.toList()
    }

    /** The same key, or one close by: centres within 1.6 key widths and at most 1.25 apart across. */
    fun near(a: Char, b: Char): Boolean {
        if (a == b) return true
        val (xa, ya) = centres[a] ?: return false
        val (xb, yb) = centres[b] ?: return false
        val dx = (xa - xb).toDouble(); val dy = (ya - yb).toDouble()
        return dx * dx + dy * dy <= 1.6 * 1.6 && kotlin.math.abs(dx) <= 1.25
    }

    /** Key targets for a key area [widthDp] x [heightDp] with [rowCount] equal rows, ten key widths across. */
    companion object {
        /** A layout's letter rows (GeneratedLayouts), each centred under the longest, as drawn. */
        fun ofLayout(rows: List<String>): LetterRows {
            val widest = rows.maxOf { it.length }
            return LetterRows(rows, rows.map { (widest - it.length) / 2f })
        }
    }

    fun keyTargets(widthDp: Float, heightDp: Float, rowCount: Int): Map<Char, KeyTarget> {
        val unit = widthDp / 10f
        val rowHeight = heightDp / rowCount
        return centres.mapValues { (_, p) -> KeyTarget(p.first * unit, p.second * rowHeight, unit / 2, rowHeight / 2) }
    }
}
