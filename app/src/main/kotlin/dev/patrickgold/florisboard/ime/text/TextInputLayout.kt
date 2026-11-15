/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import dev.patrickgold.florisboard.clipboardManager
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.keyboard3.ui.ImeKeyboardBox
import dev.patrickgold.florisboard.ime.smartbar.IncognitoDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.InlineSuggestionsStyleCache
import dev.patrickgold.florisboard.ime.smartbar.Smartbar
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionsOverflowPanel
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.snygg.ui.SnyggIcon

@Composable
fun TextInputLayout(
    modifier: Modifier = Modifier,
) {
    val prefs by FlorisPreferenceStore
    val imeController = LocalImeController.current

    val imeState by imeController.activeState.collectAsState()
    val isActionsOverflowVisible by remember {
        derivedStateOf { imeState.flags.isActionsOverflowVisible }
    }
    val isIncognitoMode by remember {
        derivedStateOf { imeState.flags.isIncognitoMode }
    }

    InlineSuggestionsStyleCache()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
    ) {
        // DogBoard: stage 2 of the design port: the ported bar instead of FlorisBoard's Smartbar
        val dogboardDesign by prefs.keyboard.dogboardDesign.collectAsState()
        if (dogboardDesign) {
            val strip by io.github.sillypuppyy.dogboard.ime.LiveStrip.strip.collectAsState()
            val ui = io.github.sillypuppyy.dogboard.ime.LiveUi
            val toolRow by ui.toolRow.collectAsState()
            val panel by ui.panel.collectAsState()
            val removed by io.github.sillypuppyy.dogboard.ime.LiveStrip.removed.collectAsState()
            val refresh = { imeController.updateStateBlocking { refreshSuggestions() } }
            // the recent-copy chip: the current clip for 2 min after it was copied (RecentClip)
            val context = androidx.compose.ui.platform.LocalContext.current
            val clipboardManager = androidx.compose.runtime.remember(context) { context.clipboardManager().value }
            val primaryClip by clipboardManager.primaryClipFlow.collectAsState()
            var clipShown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem?>(null) }
            // a text clip's chips: the whole text, then what is inside it (Gboard: phone, email, link, address, date)
            var clipChips by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<io.github.sillypuppyy.dogboard.design.KbChipData>>(emptyList()) }
            androidx.compose.runtime.LaunchedEffect(primaryClip, isIncognitoMode) {
                val offer = io.github.sillypuppyy.dogboard.ime.RecentClip.offer(primaryClip, System.currentTimeMillis(), isIncognitoMode)
                clipShown = offer
                clipChips = if (offer == null || offer.isSensitive || offer.type != dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.TEXT) emptyList()
                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val whole = io.github.sillypuppyy.dogboard.design.KbChipData(io.github.sillypuppyy.dogboard.ime.RecentClip.icon(context, offer), io.github.sillypuppyy.dogboard.ime.RecentClip.label(offer))
                    listOf(whole) + io.github.sillypuppyy.dogboard.ime.RecentClip.entities(context, offer.text.orEmpty()).take(3)
                        .map { (icon, text) -> io.github.sillypuppyy.dogboard.design.KbChipData(icon, text) }
                }
                if (offer != null) {
                    kotlinx.coroutines.delay(io.github.sillypuppyy.dogboard.ime.RecentClip.WINDOW_MS - (System.currentTimeMillis() - offer.creationTimestampMs))
                    clipShown = null
                }
            }
            // the autofill service's chips (passwords, addresses, cards: Google's or any other), drawn
            // by the service itself, over the bar's slot area between the tools button and the mic
            val inlineAutofill by dev.patrickgold.florisboard.ime.nlp.NlpInlineAutofill.suggestions.collectAsState()
            val showAutofill = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && inlineAutofill.isNotEmpty() && !toolRow && panel.isEmpty()
            Box {
            val contactChips by io.github.sillypuppyy.dogboard.ime.LiveStrip.chips.collectAsState()
            val undoShown by io.github.sillypuppyy.dogboard.ime.LiveStrip.undoShown.collectAsState()
            // contact chips while a name is typed; else, with nothing typed, a text clip's chips
            val typing = strip.any { it.kind != io.github.sillypuppyy.dogboard.engine.Suggestion.Kind.PREDICTION }
            val clipChipsShown = contactChips.isEmpty() && !typing && clipShown != null && clipChips.isNotEmpty()
            // Gboard: after a paste from the chip, or a held / swipe delete, the bar is one Undo chip
            // (it goes as soon as anything else changes the text or the cursor, so it can take the bar)
            val undoChip = undoShown
            val chips = when {
                undoChip -> listOf(io.github.sillypuppyy.dogboard.design.KbChipData("undo", "Undo"))
                contactChips.isNotEmpty() -> contactChips
                clipChipsShown -> clipChips
                else -> emptyList()
            }
            io.github.sillypuppyy.dogboard.design.LiveKbBar(
                strip = if (showAutofill) emptyList() else strip,
                chips = if (showAutofill) emptyList() else chips,
                onChip = { i ->
                    if (undoChip) imeController.updateStateBlocking { undoLastEdit() }
                    else if (clipChipsShown) {
                        val item = clipShown
                        if (item != null) {
                            io.github.sillypuppyy.dogboard.ime.RecentClip.markUsed(item)
                            clipShown = null
                            // the whole clip pastes as itself; a piece of it as text
                            if (i == 0) imeController.updateStateBlocking { emitClipboardItem(item, undoable = true) }
                            else clipChips.getOrNull(i)?.let { c -> imeController.updateStateBlocking { emitChip(c.label, undoable = true) } }
                        }
                    } else chips.getOrNull(i)?.let { c -> imeController.updateStateBlocking { emitChip(c.insert, space = c.insert == c.label) } }
                },
                clipLabel = if (clipChipsShown || undoChip) null else clipShown?.let { io.github.sillypuppyy.dogboard.ime.RecentClip.label(it) },
                clipMedia = clipShown?.type.let { it == dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.IMAGE || it == dev.patrickgold.florisboard.ime.clipboard.provider.ItemType.VIDEO },
                onClip = {
                    clipShown?.let { item ->
                        io.github.sillypuppyy.dogboard.ime.RecentClip.markUsed(item)
                        clipShown = null
                        imeController.updateStateBlocking { emitClipboardItem(item, undoable = true) }
                    }
                },
                // a long-press removes a suggestion for good, with Undo for 4 s (design Panels:102)
                onLongPick = { s -> io.github.sillypuppyy.dogboard.ime.LiveStrip.remove(s, refresh) },
                removed = removed,
                onUndo = { io.github.sillypuppyy.dogboard.ime.LiveStrip.undo(refresh) },
                onPick = { s ->
                    imeController.updateStateBlocking { emitCandidate(io.github.sillypuppyy.dogboard.ime.DogBoardCandidate(s)) }
                },
                // the left button swaps the suggestions for the tool row (Kb:668)
                onLeft = { ui.toolRow.value = !ui.toolRow.value },
                toolRow = toolRow,
                panel = panel,
                onTool = { icon -> ui.run(icon, imeController) },
                onBack = { ui.closePanel() },
                // voice typing hands over to the system's voice keyboard (the design's own
                // "Listening…" state needs in-keyboard speech recognition: later)
                incognito = isIncognitoMode && !showAutofill, // the note gives way to autofill chips
                onMic = {
                    imeController.updateStateBlocking { emitDescriptor(dev.patrickgold.florisboard.ime.keyboard3.ImeActions.ExternalVoiceInput) }
                },
            )
            if (showAutofill) {
                androidx.compose.foundation.layout.Box(
                    Modifier.matchParentSize().padding(horizontal = 48.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { dev.patrickgold.florisboard.ime.smartbar.InlineSuggestionsUi(inlineAutofill) }
            }
            }
        } else {
            Smartbar()
        }
        if (isActionsOverflowVisible) {
            QuickActionsOverflowPanel()
        } else {
            Box {
                val incognitoDisplayMode by prefs.keyboard.incognitoDisplayMode.collectAsState()
                val showIncognitoIcon = isIncognitoMode &&
                    incognitoDisplayMode == IncognitoDisplayMode.DISPLAY_BEHIND_KEYBOARD
                if (showIncognitoIcon) {
                    SnyggIcon(
                        FlorisImeUi.IncognitoModeIndicator.elementName,
                        modifier = Modifier
                            .matchParentSize()
                            .align(Alignment.Center),
                        painter = painterResource(R.drawable.ic_incognito),
                    )
                }
                // DogBoard: phone landscape: the keys are a centred block and the side areas (11 %
                // each) hold undo / redo and the cursor arrows (designer, States board)
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val landscape = dogboardDesign && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    configuration.smallestScreenWidthDp < 600
                if (landscape) {
                    val side = (configuration.screenWidthDp * 0.11f).dp
                    Box {
                        ImeKeyboardBox(Modifier.padding(horizontal = side))
                        androidx.compose.foundation.layout.Row(Modifier.matchParentSize()) {
                            val onAction = { a: String ->
                                imeController.updateStateBlocking {
                                    emit(when (a) {
                                        "undo" -> dev.patrickgold.florisboard.ime.keyboard3.ImeActions.Undo
                                        "redo" -> dev.patrickgold.florisboard.ime.keyboard3.ImeActions.Redo
                                        "left" -> dev.patrickgold.florisboard.ime.keyboard3.ImeActions.ArrowLeft
                                        else -> dev.patrickgold.florisboard.ime.keyboard3.ImeActions.ArrowRight
                                    })
                                }
                            }
                            io.github.sillypuppyy.dogboard.design.LiveSideStrip(true, onAction, Modifier.width(side).fillMaxHeight())
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            io.github.sillypuppyy.dogboard.design.LiveSideStrip(false, onAction, Modifier.width(side).fillMaxHeight())
                        }
                    }
                } else {
                    ImeKeyboardBox()
                }
            }
        }
    }
}
