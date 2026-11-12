package io.github.sillypuppyy.dogboard.touch

import kotlin.math.abs
import kotlin.math.pow

/** A key as a touch target, in dp, relative to the keyboard's top-left corner. */
data class KeyTarget(val centerX: Float, val centerY: Float, val halfWidth: Float, val halfHeight: Float)

/**
 * Smoothed time between presses, used to widen the touch spread when typing fast.
 */
class TypingSpeed {
    /** Smoothed interval between presses, in seconds. */
    var interval: Double = DEFAULT_INTERVAL_S
        private set
    private var lastPressUptimeMs: Long? = null

    fun onPress(uptimeMs: Long) {
        val dt = lastPressUptimeMs?.let { (uptimeMs - it) / 1000.0 } ?: Double.POSITIVE_INFINITY
        lastPressUptimeMs = uptimeMs
        val clamped = minOf(dt, MAX_INTERVAL_S)
        interval = SMOOTHING * clamped + (1 - SMOOTHING) * interval
    }

    companion object {
        const val DEFAULT_INTERVAL_S = 0.35
        const val MAX_INTERVAL_S = 0.5
        const val SMOOTHING = 0.2
    }
}

/**
 * Gaussian key-likelihood model. Each key is a 2D Gaussian around its centre whose spread depends
 * on key size, typing speed and finger contact size; a tap goes to the key with the highest
 * likelihood. Distances are in dp; spreads are derived from millimetres at [POINTS_PER_MM].
 */
object GaussianTouch {
    const val POINTS_PER_MM = 6.3

    /** Reference half width of a letter key, in dp. */
    private const val REFERENCE_HALF_WIDTH = 15.0

    data class Params(
        /** Likelihood coefficient for horizontal distance (negative). */
        val cx: Double,
        /** Likelihood coefficient for vertical distance (negative). */
        val cy: Double,
        /** Keys wider than twice this get a flat central region (no penalty inside it). */
        val plateauMargin: Double,
        /** Keys farther than this from the touch are not considered. */
        val pruneRadius: Double,
    ) {
        val pruneThreshold: Double get() = pruneRadius * pruneRadius * cx + 0.5 * pruneRadius * pruneRadius * cy
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f

    /**
     * @param medianKeyHalfWidth median half width of the letter keys, in dp
     * @param typingInterval smoothed time between presses in seconds ([TypingSpeed.interval])
     * @param touchRadius finger contact radius in dp, 0 if unknown
     */
    fun params(medianKeyHalfWidth: Double, typingInterval: Double, touchRadius: Double = 0.0): Params {
        val s = (medianKeyHalfWidth / REFERENCE_HALF_WIDTH).coerceIn(1.0, 4.0)
        val p = s.pow(0.66)
        val radiusFactor = when {
            touchRadius <= 6.5 -> 0.0
            touchRadius > 12.0 -> 1.0
            else -> (touchRadius - 6.5) / 5.5
        }
        val speedFactor = when {
            typingInterval < 0.15 -> 1.0
            typingInterval >= 0.35 -> 0.0
            else -> (0.35 - typingInterval) / 0.2
        }
        val f = minOf(1.0, 0.3 * radiusFactor + (0.7 * radiusFactor + 1.0) * speedFactor)
        val sigmaX = lerp(p * 13.86, p * 20.79, f)   // 2.2 mm slow .. 3.3 mm fast
        val sigmaY = lerp(p * 20.16, p * 28.35, f)   // 3.2 mm slow .. 4.5 mm fast
        return Params(
            cx = -1.0 / (4 * sigmaX * sigmaX),
            cy = -1.0 / (4 * sigmaY * sigmaY),
            plateauMargin = s * 18.9,
            pruneRadius = lerp(p * 64.575, p * 69.3, speedFactor),
        )
    }

    /** Log-likelihood (up to a constant) of a touch at ([x], [y]) meaning [key]. */
    fun logLikelihood(key: KeyTarget, x: Float, y: Float, params: Params): Double {
        var dx = abs((x - key.centerX).toDouble())
        if (key.halfWidth > params.plateauMargin) {
            dx = maxOf(0.0, dx - (key.halfWidth - params.plateauMargin))
        }
        val dy = (y - key.centerY).toDouble()
        return params.cx * dx * dx + params.cy * dy * dy
    }

    /**
     * Index of the most likely key for a touch at ([x], [y]), or null if every key is beyond the
     * prune radius. [prior] adds a log-prior per key (e.g. from a language model), default 0.
     */
    fun pick(
        keys: List<KeyTarget>,
        x: Float,
        y: Float,
        params: Params,
        prior: (Int) -> Double = { 0.0 },
    ): Int? {
        var best: Int? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val threshold = params.pruneThreshold
        for ((i, key) in keys.withIndex()) {
            val ll = logLikelihood(key, x, y, params)
            if (ll < threshold) continue
            val score = ll + prior(i)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }
}
