package io.github.sillypuppyy.dogboard.feel

import kotlin.math.abs
import kotlin.math.sqrt

/** Space-bar trackpad: hold space, then slide to move the cursor (iOS one-finger rule). */
object Trackpad {
    /** Hold time on space before it becomes a trackpad. */
    const val HOLD_MS = 375L

    /** Movement allowed during the hold; moving farther first cancels it (the tap stays a space). */
    const val ALLOWABLE_MOVEMENT_DP = 16f

    /** Finger movement per one-character cursor step, before acceleration (not from iOS; tune by feel). */
    const val STEP_DP = 8f

    /*
     * iOS acceleration (keyboardTrackpadCurve): gains
     * linear 0, parabolic 0.2 (cubic, quartic 0), a tangent line from speed 2 and a square-root
     * tangent from speed 5 (_UITextSelectionSettings gain / linear / parabolic). Each movement of
     * d points adds d * speed(|d|) on top of d.
     */
    private const val GAIN = 0.2
    private const val LINE_FROM = 2.0
    private const val SQRT_FROM = 5.0
    private const val LINE_SLOPE = 2 * GAIN * GAIN * LINE_FROM // the parabola's slope at LINE_FROM
    private const val LINE_AT_LINE_FROM = GAIN * GAIN * LINE_FROM * LINE_FROM
    private const val LINE_AT_SQRT_FROM = LINE_AT_LINE_FROM + LINE_SLOPE * (SQRT_FROM - LINE_FROM)

    /** The extra factor for a movement of [d] points in one step (piecewiseCubicAcceleratedSpeed). */
    fun speed(d: Double): Double = when {
        d <= LINE_FROM -> (GAIN * d) * (GAIN * d)
        d <= SQRT_FROM -> LINE_AT_LINE_FROM + LINE_SLOPE * (d - LINE_FROM)
        else -> sqrt(LINE_AT_SQRT_FROM * LINE_AT_SQRT_FROM + 2 * LINE_AT_SQRT_FROM * LINE_SLOPE * (d - SQRT_FROM))
    }

    /** One finger movement of [dxDp] (dp, about iOS points) with the acceleration added. */
    fun accelerated(dxDp: Float): Float = (dxDp * (1 + speed(abs(dxDp).toDouble()))).toFloat()
}
