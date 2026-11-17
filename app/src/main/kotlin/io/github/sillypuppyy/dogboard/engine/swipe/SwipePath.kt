package io.github.sillypuppyy.dogboard.engine.swipe

import kotlin.math.hypot

/**
 * A swipe as the swipe model reads it (tools/swipe/prepare_futo.py, same numbers): points in key
 * units of a QWERTY letter area (x: 0..10 key widths, y: 0..3 rows), resampled every [SPACING] along
 * the path, each with the step to the next point: (x, y, dx, dy).
 */
object SwipePath {
    const val SPACING = 0.3f
    const val MAX_POINTS = 160

    /** Points every [spacing] along the path (xs[i], ys[i]); first and last kept; at most [MAX_POINTS]. */
    fun resample(xs: FloatArray, ys: FloatArray, spacing: Float = SPACING): Pair<FloatArray, FloatArray> {
        val ox = ArrayList<Float>(); val oy = ArrayList<Float>()
        if (xs.isEmpty()) return FloatArray(0) to FloatArray(0)
        ox.add(xs[0]); oy.add(ys[0])
        var carry = 0f
        for (i in 0 until xs.size - 1) {
            val x0 = xs[i]; val y0 = ys[i]; val x1 = xs[i + 1]; val y1 = ys[i + 1]
            val seg = hypot(x1 - x0, y1 - y0)
            if (seg == 0f) continue
            var d = spacing - carry
            while (d <= seg) {
                val t = d / seg
                ox.add(x0 + t * (x1 - x0)); oy.add(y0 + t * (y1 - y0))
                d += spacing
            }
            carry = seg - (d - spacing)
        }
        if (ox.last() != xs.last() || oy.last() != ys.last()) { ox.add(xs.last()); oy.add(ys.last()) }
        if (ox.size == 1) { ox.add(ox[0]); oy.add(oy[0]) }
        val n = minOf(ox.size, MAX_POINTS)
        return FloatArray(n) { ox[it] } to FloatArray(n) { oy[it] }
    }

    /** Features (x, y, dx, dy) per resampled point, flat: [n * 4]. */
    fun features(xs: FloatArray, ys: FloatArray): FloatArray {
        val (rx, ry) = resample(xs, ys)
        val n = rx.size
        return FloatArray(n * 4).also { f ->
            for (i in 0 until n) {
                val j = minOf(i + 1, n - 1)
                f[4 * i] = rx[i]; f[4 * i + 1] = ry[i]
                f[4 * i + 2] = rx[j] - rx[i]; f[4 * i + 3] = ry[j] - ry[i]
            }
        }
    }

    /**
     * Pixels to key units: an affine map fitted (least squares, x and y separately) from where the
     * keyboard draws the letter keys to where the model's layout has them ([KEY_UNITS]), so any key
     * size, gap or row offset works. [centres]: letter -> (x px, y px).
     */
    class ToKeyUnits(centres: Map<Char, Pair<Float, Float>>) {
        private val ax: Float; private val bx: Float; private val ay: Float; private val by: Float

        init {
            val letters = centres.keys.filter { it in KEY_UNITS }
            require(letters.size >= 3) { "need letter keys" }
            fun fit(px: List<Double>, u: List<Double>): Pair<Float, Float> {
                val n = px.size; val mx = px.average(); val mu = u.average()
                var sxx = 0.0; var sxu = 0.0
                for (i in 0 until n) { sxx += (px[i] - mx) * (px[i] - mx); sxu += (px[i] - mx) * (u[i] - mu) }
                val a = if (sxx == 0.0) 0.0 else sxu / sxx
                return a.toFloat() to (mu - a * mx).toFloat()
            }
            val (a1, b1) = fit(letters.map { centres[it]!!.first.toDouble() }, letters.map { KEY_UNITS[it]!!.first.toDouble() })
            val (a2, b2) = fit(letters.map { centres[it]!!.second.toDouble() }, letters.map { KEY_UNITS[it]!!.second.toDouble() })
            ax = a1; bx = b1; ay = a2; by = b2
        }

        fun x(px: Float) = ax * px + bx
        fun y(py: Float) = ay * py + by
    }

    /** The model's key centres (FUTO's qwerty.json x 10 and x 3: rows offset as on a phone). */
    val KEY_UNITS: Map<Char, Pair<Float, Float>> = buildMap {
        val rows = listOf("qwertyuiop" to 0.5f, "asdfghjkl" to 1.0047f, "zxcvbnm" to 2.0047f)
        rows.forEachIndexed { r, (keys, first) -> keys.forEachIndexed { i, c -> put(c, (first + i) to (r + 0.5f)) } }
    }
}
