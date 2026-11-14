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
     * Portrait key area: a fixed height, only the key widths stretch (designer, 2026-11-14: Compact
     * 192 / Default 216 / Tall 240 dp; Default is 226 dp on phones 414 dp or wider; the 375 dp
     * canvas was a drawing size, not a ratio to keep). [heightScale] is the user's height setting.
     */
    fun keyAreaHeightPortrait(screenWidth: Dp): Dp =
        (if (screenWidth >= WideWidth) KeyAreaHeightPortraitWide else KeyAreaHeightPortrait) * heightScale

    /** Phones at least this wide get [KeyAreaHeightPortraitWide] (designer). */
    val WideWidth: Dp = 414.dp
    val KeyAreaHeightPortraitWide: Dp = 226.dp

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
