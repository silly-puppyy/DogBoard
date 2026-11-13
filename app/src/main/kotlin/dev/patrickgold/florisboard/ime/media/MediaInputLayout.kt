/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.media

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.ui.ImeKeyButton
import dev.patrickgold.florisboard.ime.media.emoji.EmojiData
import org.k3lp.lib.text.asK3String
import kotlinx.coroutines.launch
import io.github.sillypuppyy.dogboard.design.LiveEmojiSection
import dev.patrickgold.florisboard.ime.media.emoji.EmojiCategory
import dev.patrickgold.florisboard.ime.media.emoji.EmojiPaletteView
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSearchLayout
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

@SuppressLint("MutableCollectionMutableState")
@Composable
fun MediaInputLayout(
    modifier: Modifier = Modifier,
) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current

    var emojiLayoutDataMap by remember { mutableStateOf(EmojiData.Fallback) }
    LaunchedEffect(Unit) {
        emojiLayoutDataMap = EmojiData.get(context, "ime/media/emoji/root.txt")
    }

    var isEmojiSearch by remember { mutableStateOf(false) }
    // DogBoard: stage 2 of the design port: the design's emoji panel with the real emoji
    val dogboardDesign by prefs.keyboard.dogboardDesign.collectAsState()
    if (dogboardDesign) {
        DogBoardEmojiPanel(emojiLayoutDataMap, modifier)
        return
    }
    if (isEmojiSearch) {
        EmojiSearchLayout()
    } else {
        SnyggColumn(
            elementName = FlorisImeUi.Media.elementName,
            modifier = modifier
                .fillMaxWidth()
                .height(FlorisImeSizing.imeUiHeight()),
        ) {
            EmojiPaletteView(
                modifier = Modifier.weight(1f),
                fullEmojiMappings = emojiLayoutDataMap,
            )
            SnyggRow(
                elementName = FlorisImeUi.MediaBottomRow.elementName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FlorisImeSizing.keyboardRowBaseHeight * 0.8f),
            ) {
                ImeKeyButton(
                    elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                    output = ImeActions.ShowTextPanel,
                    modifier = Modifier.fillMaxHeight(),
                )
                Spacer(modifier = Modifier.weight(1f))
                // TODO no feature gate once emoji search PR is implemented!
                val experimentalEmojiSearch by prefs.emoji.experimentalEmojiSearchEnabled.collectAsState()
                if (experimentalEmojiSearch) {
                    SnyggButton(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .align(Alignment.CenterVertically),
                        onClick = { isEmojiSearch = true },
                    ) {
                        SnyggIcon(
                            imageVector = Icons.Default.Search,
                        )
                        SnyggText(
                            text = "Search",
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                ImeKeyButton(
                    elementName = FlorisImeUi.MediaBottomRowButton.elementName,
                    output = ImeActions.Backspace,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

/** DogBoard: the design's emoji panel (header with back, live grid, bottom row) in media mode. */
@Composable
private fun DogBoardEmojiPanel(data: EmojiData, modifier: Modifier) {
    val prefs by FlorisPreferenceStore
    val imeController = dev.patrickgold.florisboard.ime.keyboard3.LocalImeController.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val skinTone by prefs.emoji.preferredSkinTone.collectAsState()
    val history by prefs.emoji.historyData.collectAsState()
    val paint = remember { android.graphics.Paint().apply { typeface = android.graphics.Typeface.DEFAULT } }
    val sections = remember(data, history) {
        fun sets(vararg cats: EmojiCategory) =
            cats.flatMap { data.byCategory[it].orEmpty() }.filter { set -> paint.hasGlyph(set.emojis[0].value) }
        val recents = (history.pinned + history.recent.sortedByDescending { io.github.sillypuppyy.dogboard.engine.TypingEngine.emojiScore(it.value) })
            .distinct().map { dev.patrickgold.florisboard.ime.media.emoji.EmojiSet(listOf(it)) }
        listOfNotNull(
            if (recents.isNotEmpty()) LiveEmojiSection("Recently used", 0, recents) else null,
            LiveEmojiSection("Smileys and people", 1, sets(EmojiCategory.SMILEYS_EMOTION, EmojiCategory.PEOPLE_BODY)),
            LiveEmojiSection("Animals and nature", 2, sets(EmojiCategory.ANIMALS_NATURE)),
            LiveEmojiSection("Food and drink", 3, sets(EmojiCategory.FOOD_DRINK)),
            LiveEmojiSection("Activities", 4, sets(EmojiCategory.ACTIVITIES)),
            LiveEmojiSection("Travel and places", 5, sets(EmojiCategory.TRAVEL_PLACES)),
            LiveEmojiSection("Objects", 6, sets(EmojiCategory.OBJECTS)),
            LiveEmojiSection("Symbols", 7, sets(EmojiCategory.SYMBOLS)),
            LiveEmojiSection("Flags", 8, sets(EmojiCategory.FLAGS)),
        ).filter { it.sets.isNotEmpty() }
    }
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().height(FlorisImeSizing.imeUiHeight())) {
        io.github.sillypuppyy.dogboard.design.LiveKbBar(
            strip = emptyList(), onPick = {}, onLeft = {}, panel = "emoji",
            onBack = { imeController.updateStateBlocking { emitDescriptor(ImeActions.ShowTextPanel) } },
        )
        io.github.sillypuppyy.dogboard.design.LiveEmojiPanel(
            sections = sections,
            skinTone = skinTone,
            onEmoji = { emoji ->
                scope.launch {
                    imeController.updateState { emitText(emoji.value.asK3String()) }
                    dev.patrickgold.florisboard.ime.media.emoji.EmojiHistoryHelper.markEmojiUsed(prefs, emoji)
                }
            },
            onAbc = { imeController.updateStateBlocking { emitDescriptor(ImeActions.ShowTextPanel) } },
            onDelete = { imeController.updateStateBlocking { emitDescriptor(ImeActions.Backspace) } },
            modifier = Modifier.weight(1f),
        )
    }
}
