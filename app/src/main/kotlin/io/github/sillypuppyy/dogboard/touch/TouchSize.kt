package io.github.sillypuppyy.dogboard.touch

/**
 * The typing finger's contact size (iOS ForTouch radius term, GaussianTouch.params touchRadius): half the touch
 * major axis in dp, smoothed over recent taps (one tap's size is noisy; a finger's is stable). 0 until a screen reports
 * one (emulators and some screens report 0 or 1 px: those taps are skipped).
 */
object TouchSize {
    @Volatile var radiusDp: Double = 0.0
        private set
    private var taps = 0

    /** A finger going down with [majorDp] (MotionEvent.getTouchMajor in dp). */
    fun note(majorDp: Float) {
        if (majorDp < 2f || majorDp > 60f) return // not reported, or a palm
        val r = majorDp / 2.0
        radiusDp = if (radiusDp == 0.0) r else radiusDp + SMOOTHING * (r - radiusDp)
        if (++taps % 50 == 0) android.util.Log.i("DogBoardTouch", "contact radius %.1f dp (this tap %.1f)".format(radiusDp, r))
    }

    private const val SMOOTHING = 0.2
}
