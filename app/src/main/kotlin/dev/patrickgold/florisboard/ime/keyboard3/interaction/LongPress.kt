/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.keyboard3.interaction

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.util.fastForEachIndexed
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchPopupKey
import kotlinx.coroutines.Job
import org.k3lp.lib.text.K3String
import org.k3lp.lib.text.K3StringOrDescriptor

data class LongPress(
    val anchorBounds: Rect = Rect.Zero,
    val simpleBounds: Rect = Rect.Zero,
    val simpleLabel: K3StringOrDescriptor = K3String.Empty,
    val simpleIndicateExtended: Boolean = false,
    val extendedBounds: Rect = Rect.Zero,
    val extendedKeys: List<TouchPopupKey> = emptyList(),
    val extendedFocusedIndex: Int = 0,
    val extendedJob: Job? = null,
    /** DogBoard: the pressed key's cell (normalized) and the keyboard's width, for the one-row bar. */
    val keyBounds: Rect = Rect.Zero,
    val keyboardWidthDp: Float = 0f,
) {
    fun shouldShowSimplePopup(): Boolean {
        return !simpleBounds.isEmpty
    }

    fun shouldShowExtendedPopup(): Boolean {
        return !extendedBounds.isEmpty
    }

    /**
     * DogBoard: the bar is one row (iOS), so the item is chosen by the finger's x alone: the cell
     * under it, or the nearest end beyond the bar; moving up or down keeps the choice.
     */
    fun getNearestKeyIndex(position: Offset): Int {
        if (extendedKeys.isEmpty()) return extendedFocusedIndex
        // two rows (more than 8 variants): the finger's height picks the row, then x picks the cell
        val rowTops = extendedKeys.map { it.bounds.top }.distinct()
        val rowTop = if (rowTops.size < 2) null else rowTops.minBy { top ->
            val k = extendedKeys.first { it.bounds.top == top }.bounds
            if (position.y < k.top) k.top - position.y else if (position.y > k.bottom) position.y - k.bottom else 0f
        }
        var best = extendedFocusedIndex
        var bestDistance = Float.MAX_VALUE
        extendedKeys.fastForEachIndexed { index, extendedKey ->
            if (rowTop != null && extendedKey.bounds.top != rowTop) return@fastForEachIndexed
            val b = extendedKey.bounds
            val d = if (position.x < b.left) b.left - position.x else if (position.x > b.right) position.x - b.right else 0f
            if (d < bestDistance) { bestDistance = d; best = index }
        }
        return best
    }

    companion object {
        val None = LongPress()
    }
}

/**
 * DogBoard: the extended keys in one row, in list order, exactly where the live keyboard draws
 * its long-press bar ([io.github.sillypuppyy.dogboard.design.KbSpec.longPressBarX]): item 0 over
 * the key, the bar shifted to stay on screen. FlorisBoard's grid layout (below) is not used.
 */
/** DogBoard: what a long-press cell shows: the layout's display label ("New line"), else the text it types. */
fun TouchPopupKey.barLabel(): String =
    (display as? K3String)?.toText()?.takeIf { it.isNotBlank() } ?: (data.output as? K3String)?.toText().orEmpty()

fun layoutLongPressBarRow(extendedKeys: List<TouchPopupKey>, keyBounds: Rect, anchorBounds: Rect, keyboardWidthDp: Float): List<TouchPopupKey> {
    val spec = io.github.sillypuppyy.dogboard.design.KbSpec
    val w = keyboardWidthDp
    val capX = keyBounds.left * w + spec.KEY_MH
    val capW = keyBounds.width * w - 2 * spec.KEY_MH
    val cells = spec.longPressCells(capX, capW, w, extendedKeys.map { it.barLabel() })
    val rowH = anchorBounds.height
    return extendedKeys.mapIndexed { i, key ->
        val c = cells[i]
        val left = c.x / w
        val top = anchorBounds.top - c.row * rowH
        key.withNewBounds(Rect(left, top, left + c.w / w, top + rowH))
    }
}

fun layoutExtendedLongPressKeys(
    extendedKeys: List<TouchPopupKey>,
    anchorBounds: Rect,
): List<TouchPopupKey> {
    val n  = extendedKeys.size
    val nLeftMax = (anchorBounds.left / anchorBounds.width).toInt()
    val nRightMax = ((1f - anchorBounds.right) / anchorBounds.width).toInt()
    var nLeftUsed0 = 0
    var nRightUsed0 = 0
    var nLeftUsed1 = 0
    var nRightUsed1 = 0
    var nTopUsed1 = 0
    val isMultiLine = n > 5
    return extendedKeys.mapIndexed { index, popupKey ->
        if (index == 0) {
            // default popup key, always directly at the anchor
            popupKey.withNewBounds(anchorBounds)
        } else {
            // prioritizes distance to anchor (according to available space)
            // aka row 0 before row 1 && right before left
            val (xFactor, yFactor) = when {
                isMultiLine && nRightUsed0 == 0 && nRightMax != 0 -> {
                    ++nRightUsed0 to 0
                }
                isMultiLine && nLeftUsed0 == 0 && nLeftMax != 0 -> {
                    -(++nLeftUsed0) to 0
                }
                isMultiLine && nTopUsed1 == 0 -> {
                    0 to -(++nTopUsed1)
                }
                isMultiLine && nRightUsed1 < nRightUsed0 && nRightUsed1 < nRightMax -> {
                    ++nRightUsed1 to -1
                }
                isMultiLine && nLeftUsed1 < nLeftUsed0 && nLeftUsed1 < nLeftMax -> {
                    -(++nLeftUsed1) to -1
                }
                nRightUsed0 <= nLeftUsed0 && nRightUsed0 < nRightMax || nLeftUsed0 >= nLeftMax -> {
                    ++nRightUsed0 to 0
                }
                else -> {
                    -(++nLeftUsed0) to 0
                }
            }
            popupKey.withNewBounds(
                anchorBounds.translate(
                    translateX = xFactor * anchorBounds.width,
                    translateY = yFactor * anchorBounds.height,
                )
            )
        }
    }
}
