package io.github.sillypuppyy.dogboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetLinkRow
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import io.github.sillypuppyy.dogboard.design.settings.Segmented
import io.github.sillypuppyy.dogboard.design.settings.TitleSub
import io.github.sillypuppyy.dogboard.design.settings.setText
import io.github.sillypuppyy.dogboard.engine.AutocorrectLevel
import io.github.sillypuppyy.dogboard.engine.EmojiSuggestionMode
import kotlinx.coroutines.launch

/**
 * Typing and correction (settings layout v2, feature-inventory.md §3): Correction, Capitals and
 * Punctuation on one page. No canvas: built from the shared rows of the designed pages.
 */
@Composable
fun TypingCorrectionScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    val autocorrectOn by prefs.correction.autocorrect.collectAsState()
    val level by prefs.correction.autocorrectLevel.collectAsState()
    val adaptive by prefs.correction.adaptiveStrength.collectAsState()
    val undo by prefs.correction.undoOnBackspace.collectAsState()
    val suggestions by prefs.suggestion.enabled.collectAsState()
    val emojiMode by prefs.suggestion.emojiMode.collectAsState()
    val offensive by prefs.suggestion.blockPossiblyOffensive.collectAsState()
    val sentenceCaps by prefs.typing.autoCapitalization.collectAsState()
    val nameCaps by prefs.typing.dictionaryCapitals.collectAsState()
    val capsLock by prefs.typing.capsLockEnabled.collectAsState()
    val quotes by prefs.typing.smartQuotes.collectAsState()
    val dashes by prefs.typing.smartDashes.collectAsState()
    val doubleSpace by prefs.correction.doubleSpacePeriod.collectAsState()
    val L = AutocorrectLevel.entries

    FlorisScreenNg(title = "Typing and correction", previewFieldVisible = true) { contentPadding ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SetGroup("Correction") {
                Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TitleSub("Autocorrect", "How readily typos are fixed when you press space.")
                    Segmented("tc:level", listOf("Off", "Gentle", "Standard", "Strong"), if (!autocorrectOn) 0 else 1 + L.indexOf(level)) { i ->
                        scope.launch {
                            prefs.correction.autocorrect.set(i > 0)
                            if (i > 0) prefs.correction.autocorrectLevel.set(L[i - 1])
                        }
                    }
                }
                SetSwitchRow("tc:undo", "Undo with backspace", "Backspace right after a fix puts back the word you typed.", undo) { v ->
                    scope.launch { prefs.correction.undoOnBackspace.set(v) }
                }
                SetSwitchRow("tc:adapt", "Learn from your undos", "Autocorrect gets more careful when you often undo it, and bolder when you keep its fixes.", adaptive) { v ->
                    scope.launch { prefs.correction.adaptiveStrength.set(v) }
                }
                SetSwitchRow("tc:sugg", "Suggestions", "Three words above the keys as you type.", suggestions) { v ->
                    scope.launch { prefs.suggestion.enabled.set(v) }
                }
                Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TitleSub("Emoji in suggestions", "“After you use them”: once you’ve typed emoji, here and in the app you’re in.")
                    val modes = listOf(EmojiSuggestionMode.OFF, EmojiSuggestionMode.AFTER_USE, EmojiSuggestionMode.ALWAYS)
                    Segmented("tc:emoji", listOf("Off", "After you use them", "Always"), modes.indexOf(emojiMode), listOf(0.7f, 1.6f, 0.9f)) { i ->
                        scope.launch { prefs.suggestion.emojiMode.set(modes[i]) }
                    }
                }
                SetSwitchRow("tc:offensive", "Block offensive words", "Swear words aren’t offered as suggestions.", offensive) { v ->
                    scope.launch { prefs.suggestion.blockPossiblyOffensive.set(v) }
                }
            }
            SetGroup("Capitals") {
                SetSwitchRow("tc:sentence", "Sentence starts", "A capital after a full stop, question mark or new line.", sentenceCaps) { v ->
                    scope.launch { prefs.typing.autoCapitalization.set(v) }
                }
                SetSwitchRow("tc:names", "Names and “I”", "Words the dictionary writes with a capital get one, like “I”, “Paris” and “Mr”.", nameCaps) { v ->
                    scope.launch { prefs.typing.dictionaryCapitals.set(v) }
                }
                SetSwitchRow("tc:capslock", "Caps lock", "Double-tap shift to type in capitals until you tap it again.", capsLock) { v ->
                    scope.launch { prefs.typing.capsLockEnabled.set(v) }
                }
            }
            SetGroup("Punctuation") {
                SetSwitchRow("tc:quotes", "Smart quotes", "Curly quotes and apostrophes (“ ” ’) instead of straight ones.", quotes) { v ->
                    scope.launch { prefs.typing.smartQuotes.set(v) }
                }
                SetSwitchRow("tc:dashes", "Smart dashes", "Two hyphens become a dash (—) and three full stops an ellipsis (…).", dashes) { v ->
                    scope.launch { prefs.typing.smartDashes.set(v) }
                }
                SetSwitchRow("tc:dblspace", "Double-space full stop", "Two quick spaces type a full stop and a space.", doubleSpace) { v ->
                    scope.launch { prefs.correction.doubleSpacePeriod.set(v) }
                }
            }
            SetGroup("More") {
                SetLinkRow("tc:words", { nav.navigate(Routes.Settings.Dictionary) }) {
                    TitleSub("Your words", "Words DogBoard learned, ones you added, and ones it won’t suggest again", Modifier.weight(1f))
                }
                SetLinkRow("tc:fine", { nav.navigate(Routes.Settings.FineTuning) }) {
                    TitleSub("Fine-tuning", "Timings for caps lock, double space and the delete key", Modifier.weight(1f))
                }
            }
            DText(
                "Contact names and incognito typing are under Privacy.",
                Modifier.padding(horizontal = 16.dp), style = setText(13f, color = SetColors.onSurfaceVariant), maxLines = 3,
            )
        }
    }
}
