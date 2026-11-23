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

package dev.patrickgold.florisboard.ime.keyboard3.touch

import dev.patrickgold.florisboard.ime.text.key.KeyVariation

import dev.patrickgold.florisboard.ime.keyboard3.hint.FlickKeyHintPlacement
import dev.patrickgold.florisboard.ime.keyboard3.hint.LongPressKeyHintPlacement

data class TouchModelOptions(
    val showNumberRow: Boolean,
    val fnKeyEnabled: Boolean,
    val fnKeyArrangement: FnKeyArrangement,
    val longPressKeyHintEnabled: Boolean,
    val longPressKeyHintPlacement: LongPressKeyHintPlacement,
    val multiTapHighlightEnabled: Boolean,
    val flickKeyHintEnabled: Boolean,
    val flickKeyHintPlacement: FlickKeyHintPlacement,
    // DogBoard: field type, selects the email/URL bottom-row variants of the letter layers
    val keyVariation: KeyVariation = KeyVariation.NORMAL,
    // DogBoard: number row "on long-press" (designer's Layout element): q-p offer their digit first
    val numberRowLongPress: Boolean = false,
    // DogBoard: long-press hints "Symbols": each letter's hinted symbol (KbSpec.positionalHint, by place) comes first
    val symbolLongPress: Boolean = false,
    // DogBoard: the Classic bottom row (?123, comma, emoji, space, period, action): "-classic" layers
    val bottomRowClassic: Boolean = false,
    // DogBoard: Gboard's ?123 / =\< symbol pages instead of iOS's 123 / #+= (Settings > Layout > Symbols)
    val symbolsAndroid: Boolean = false,
    /** DogBoard: letters a language typed together with this one needs (ExtraLetters): host letter -> long-press letters. */
    val extraLetters: Map<String, List<String>> = emptyMap(),
) {
    companion object {
        val Default = TouchModelOptions(
            showNumberRow = false,
            fnKeyEnabled = true,
            fnKeyArrangement = FnKeyArrangement.Default,
            longPressKeyHintEnabled = true,
            longPressKeyHintPlacement = LongPressKeyHintPlacement.TOP_END,
            multiTapHighlightEnabled = true,
            flickKeyHintEnabled = false,
            flickKeyHintPlacement = FlickKeyHintPlacement.CARDINAL,
        )
    }
}
