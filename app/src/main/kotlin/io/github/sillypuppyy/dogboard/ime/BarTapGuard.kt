package io.github.sillypuppyy.dogboard.ime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * A tap on the suggestion bar right after a typed key is read as the top-row key under it, as Gboard
 * does (served flags max_time_interval_for_tapped_key_on_suggestion_bar = 500 and
 * min_lm_score_for_tapped_key_on_suggestion_bar = 0.2; the code defaults are off): typing fast, a
 * tap aimed at the top row lands just above it, and picking a suggestion there adds a word and a
 * space ("bed" -> "b d"). Only when the language gives that letter at least [MIN_LETTER_PROB].
 */
object BarTapGuard {
    const val WINDOW_MS = 500L

    /** The window in effect: [WINDOW_MS] unless changed in Advanced > Timings (0 turns the guard off). */
    @Volatile var windowMs = WINDOW_MS
    const val MIN_LETTER_PROB = 0.2

    /** When a letter or the space bar was last pressed (uptime ms). */
    @Volatile var lastKeyDownUptime = Long.MIN_VALUE

    /**
     * Set by the key area: takes a bar pointer change at window x [windowX] (down: decides whether
     * the tap is a key's; up: ends a tap it took); returns true if the tap is the key area's.
     */
    @Volatile var keyArea: ((change: PointerInputChange, windowX: Float, down: Boolean) -> Boolean)? = null
}

/** The bar's side of [BarTapGuard]: taps the key area takes never reach the bar's buttons. */
fun Modifier.barTapGuard(): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(Unit) {
            val taken = HashSet<PointerId>()
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    for (change in event.changes) {
                        val windowX = origin.x + change.position.x
                        if (change.changedToDown()) {
                            if (BarTapGuard.keyArea?.invoke(change, windowX, true) == true) {
                                taken.add(change.id)
                                change.consume()
                            }
                        } else if (change.id in taken) {
                            if (change.changedToUp()) {
                                BarTapGuard.keyArea?.invoke(change, windowX, false)
                                taken.remove(change.id)
                            }
                            change.consume()
                        }
                    }
                }
            }
        }
}
