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
     * Portrait key area that keeps the design's key shape: 216 dp at 375 dp wide, scaled with the
     * screen width (wider phones get taller keys instead of squarer ones; user, 2026-11-14).
     */
    fun keyAreaHeightPortrait(screenWidth: Dp): Dp =
        KeyAreaHeightPortrait * (screenWidth / DesignWidth).coerceIn(1f, 1.2f)

    /**
     * Space between a key's visible cap and its touch cell, per side. Estimated: a 37.5 x 54 dp
     * cell gives a 31.5 x 42 dp cap. Verify against the real thing.
     */
    val KeyMarginHorizontal: Dp = 3.dp
    val KeyMarginVerticalPortrait: Dp = 6.dp
    val KeyMarginVerticalLandscape: Dp = 4.dp
}
