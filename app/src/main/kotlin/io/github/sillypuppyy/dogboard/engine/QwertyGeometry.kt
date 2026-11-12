package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.KeyTarget

/**
 * Letter-key targets of DogBoard's English QWERTY layout (tools/layouts/gen_qwerty_en.py) for a
 * given key-area size: 10 units per row, 4 equal rows, home row offset half a key, bottom letter
 * row after shift (1.28 units) and a 0.2133 unit gap.
 */
object QwertyGeometry {
    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val ROW_START_UNITS = floatArrayOf(0f, 0.5f, 1.28f + 0.2133f)
    private const val ROW_COUNT = 4

    fun letterKeys(widthDp: Float, heightDp: Float): Map<Char, KeyTarget> {
        val unit = widthDp / 10f
        val rowHeight = heightDp / ROW_COUNT
        return buildMap {
            for ((r, letters) in ROWS.withIndex()) {
                for ((i, c) in letters.withIndex()) {
                    val cx = (ROW_START_UNITS[r] + i + 0.5f) * unit
                    put(c, KeyTarget(cx, (r + 0.5f) * rowHeight, unit / 2, rowHeight / 2))
                }
            }
        }
    }
}
