package io.github.sillypuppyy.dogboard.feel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keyboard geometry for phones. Heights are for the four key rows only; the suggestion bar is
 * added on top. Key widths live in the layout files (see tools/layouts/gen_qwerty_en.py).
 */
object Geometry {
    /** The design's phone width (Kb.dc.html draws a 375 dp keyboard). */
    val DesignWidth: Dp = 375.dp

    /** Four key rows at [DesignWidth] (54 dp per row, the design's keyH). */
    val KeyAreaHeightPortrait: Dp = 216.dp

    /** Four key rows in landscape (40.5 dp per row). */
    val KeyAreaHeightLandscape: Dp = 162.dp

    /**
     * Portrait key area: four rows at Gboard's row height for the screen width (user, 2026-11-14: "fit to
     * Gboard"; measured on the emulator with tools/test/keyboard_sizes.py: 48.0 dp below 350 dp,
     * 50.3 dp up to 400 dp, 59.0 dp from 400 dp, whatever the screen height). Replaces the designer's
     * fixed 216 / 226 dp (iOS's). [heightScale] is the user's height setting (100 % = Gboard's).
     */
    fun keyAreaHeightPortrait(screenWidth: Dp): Dp = rowHeightPortrait(screenWidth) * 4 * heightScale

    /** One key row in portrait, from Gboard's size steps (thresholds: between 340 and 350, and 400). */
    fun rowHeightPortrait(screenWidth: Dp): Dp = when {
        screenWidth >= 400.dp -> 59.0.dp
        screenWidth >= 350.dp -> 50.3.dp
        else -> 48.0.dp
    }

    /** The user's keyboard height setting (Settings home, 80-130 %), as a factor on the key area. */
    @Volatile
    var heightScale: Float = 1f

    /**
     * Space between a key's visible cap and its touch cell, per side. Estimated: a 37.5 x 54 dp
     * cell gives a 31.5 x 42 dp cap. Verify against the real thing.
     */
    val KeyMarginHorizontal: Dp = 3.dp
    val KeyMarginVerticalPortrait: Dp = 6.dp
    val KeyMarginVerticalLandscape: Dp = 4.dp
}
