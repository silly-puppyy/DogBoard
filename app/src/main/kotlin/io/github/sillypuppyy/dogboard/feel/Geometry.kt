package io.github.sillypuppyy.dogboard.feel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keyboard geometry for phones. Heights are for the four key rows only; the suggestion bar is
 * added on top. Key widths live in the layout files (see tools/layouts/gen_qwerty_en.py).
 */
object Geometry {
    /** Screens at least this wide use the taller portrait key area. */
    val WidePhoneMinWidth: Dp = 414.dp

    /** Four key rows on portrait phones narrower than [WidePhoneMinWidth] (54 dp per row). */
    val KeyAreaHeightPortrait: Dp = 216.dp

    /** Four key rows on portrait phones at least [WidePhoneMinWidth] wide (56.5 dp per row). */
    val KeyAreaHeightPortraitWide: Dp = 226.dp

    /** Four key rows in landscape (40.5 dp per row). */
    val KeyAreaHeightLandscape: Dp = 162.dp

    fun keyAreaHeightPortrait(screenWidth: Dp): Dp =
        if (screenWidth >= WidePhoneMinWidth) KeyAreaHeightPortraitWide else KeyAreaHeightPortrait

    /**
     * Space between a key's visible cap and its touch cell, per side. Estimated: a 37.5 x 54 dp
     * cell gives a 31.5 x 42 dp cap. Verify against the real thing.
     */
    val KeyMarginHorizontal: Dp = 3.dp
    val KeyMarginVerticalPortrait: Dp = 6.dp
    val KeyMarginVerticalLandscape: Dp = 4.dp
}
