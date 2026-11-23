package io.github.sillypuppyy.dogboard.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.css
import io.github.sillypuppyy.dogboard.design.settings.SetSectionRow
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetLinkRow
import io.github.sillypuppyy.dogboard.design.settings.SetRows
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import io.github.sillypuppyy.dogboard.design.settings.Segmented
import io.github.sillypuppyy.dogboard.design.settings.TitleSub
import io.github.sillypuppyy.dogboard.engine.AutocorrectLevel
import io.github.sillypuppyy.dogboard.engine.EmojiSuggestionMode
import kotlinx.coroutines.launch

/** A settings page of the tree v3: the title bar, then the groups, scrolling. */
@Composable
internal fun SetPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    FlorisScreenNg(title = title, previewFieldVisible = true) { contentPadding ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content,
        )
    }
}

/** A row that opens a page: title, line, chevron. */
@Composable
internal fun SetPageLink(id: String, title: String, sub: String, icon: String = "chevron_right", onClick: () -> Unit) {
    SetLinkRow(id, onClick) {
        TitleSub(title, sub, Modifier.weight(1f))
        // chevron: another page; content_copy / open_in_new: what a tap does instead (About, user 2026-11-22)
        Symbol(icon, if (icon == "chevron_right") 24.sp else 20.sp, SetColors.onSurfaceVariant)
    }
}

/**
 * Autocorrect and typing (settings tree v3, notes/design/settings-tree-v3.md): the hub, one row per
 * page, as "Autocorrect and typing › Smart punctuation › Smart dashes" (user, 2026-11-17). Your words
 * lives here too (user: "words should be under autocorrect").
 */
@Composable
fun TypingCorrectionScreen() {
    val prefs by FlorisPreferenceStore
    val nav = LocalNavController.current
    val autocorrectOn by prefs.correction.autocorrect.collectAsState()
    val level by prefs.correction.autocorrectLevel.collectAsState()
    val levelName = if (!autocorrectOn) "Off" else when (level) {
        AutocorrectLevel.entries.first() -> "Gentle"
        AutocorrectLevel.entries.last() -> "Strong"
        else -> "Standard"
    }
    SetPage("Autocorrect and typing") {
        // icon rows as on the home page (user, 2026-11-17: "maybe add icons to the autocorrect menu")
        val primary = SetColors.container(css("#d8e2ff"), css("#2a4678"))
        val secondary = SetColors.container(css("#dbe2f9"), css("#3f4759"))
        val tertiary = SetColors.container(css("#fbd7fc"), css("#583e5b"))
        SetRows(Modifier.fillMaxWidth()) {
            SetSectionRow("auto_fix_high", "Autocorrect", levelName, primary.first, primary.second) { nav.navigate(Routes.Settings.AutocorrectPage) }
            SetSectionRow("lightbulb", "Suggestions", "", secondary.first, secondary.second) { nav.navigate(Routes.Settings.SuggestionsPage) }
            SetSectionRow("match_case", "Capitals", "", tertiary.first, tertiary.second) { nav.navigate(Routes.Settings.CapitalsPage) }
            SetSectionRow("format_quote", "Smart punctuation", "", primary.first, primary.second) { nav.navigate(Routes.Settings.PunctuationPage) }
            SetSectionRow("menu_book", "Your words", "", secondary.first, secondary.second) { nav.navigate(Routes.Settings.Dictionary) }
        }
    }
}

/** Autocorrect and typing › Autocorrect. */
@Composable
fun AutocorrectScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val adaptive by prefs.correction.adaptiveStrength.collectAsState()
    val notify by prefs.correction.adaptiveNotify.collectAsState()
    val fixPrevious by prefs.correction.fixPreviousWord.collectAsState()
    val joinSplit by prefs.correction.joinSplitWords.collectAsState()
    val neuralRetro by prefs.correction.neuralRetro.collectAsState()
    SetPage("Autocorrect") {
        AutocorrectGroup()
        SetGroup("Learning") {
            SetSwitchRow("ac:adapt", "Learn from your undos", "Autocorrect gets more careful when you often undo it, and bolder when you keep its fixes.", adaptive) { v ->
                scope.launch { prefs.correction.adaptiveStrength.set(v) }
            }
            if (adaptive) SetSwitchRow("ac:notify", "Tell me when it changes", "A notification each time autocorrect gets more careful or bolder.", notify) { v ->
                scope.launch { prefs.correction.adaptiveNotify.set(v) }
            }
        }
        // the rarely changed settings sit below the common ones, no switch to reveal them
        // (user, 2026-11-17: "show the complicated settings just below the common ones")
        SetGroup("Looking back") {
            SetSwitchRow("ac:previous", "Fix the previous word", "When the next word shows what you meant, the one before it changes too, as on an iPhone.", fixPrevious) { v ->
                scope.launch { prefs.correction.fixPreviousWord.set(v) }
            }
            SetSwitchRow("ac:join", "Rejoin split words", "A space typed by mistake inside a word is taken back: “meeti g” becomes “meeting”.", joinSplit) { v ->
                scope.launch { prefs.correction.joinSplitWords.set(v) }
            }
            SetSwitchRow("ac:neural", "Neural look-back (experimental)", "The neural model also judges the previous word. Slower, and not clearly better yet.", neuralRetro) { v ->
                scope.launch { prefs.correction.neuralRetro.set(v) }
            }
        }
    }
}

/**
 * The Autocorrect group of Autocorrect and typing › Autocorrect, shared with the setup's typing page (user 2026-11-23:
 * the setup shows the settings themselves); [lookBack] adds "Fix the previous word".
 */
@Composable
internal fun AutocorrectGroup(title: String = "Autocorrect", lookBack: Boolean = false) {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val autocorrectOn by prefs.correction.autocorrect.collectAsState()
    val level by prefs.correction.autocorrectLevel.collectAsState()
    val undo by prefs.correction.undoOnBackspace.collectAsState()
    val fixPrevious by prefs.correction.fixPreviousWord.collectAsState()
    val L = AutocorrectLevel.entries
    SetGroup(title) {
        Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TitleSub("How readily", "How readily typos are fixed when you press space. Off: only suggestions, nothing changed as you type.")
            Segmented("ac:level", listOf("Off", "Gentle", "Standard", "Strong"), if (!autocorrectOn) 0 else 1 + L.indexOf(level)) { i ->
                scope.launch {
                    prefs.correction.autocorrect.set(i > 0)
                    if (i > 0) prefs.correction.autocorrectLevel.set(L[i - 1])
                }
            }
        }
        SetSwitchRow("ac:undo", "Undo with backspace", "Backspace right after a fix puts back the word you typed.", undo) { v ->
            scope.launch { prefs.correction.undoOnBackspace.set(v) }
        }
        if (lookBack) SetSwitchRow("ac:previous", "Fix the previous word", "When the next word shows what you meant, the one before it changes too, as on an iPhone.", fixPrevious) { v ->
            scope.launch { prefs.correction.fixPreviousWord.set(v) }
        }
    }
}

/** Autocorrect and typing › Suggestions. */
@Composable
fun SuggestionsScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val suggestions by prefs.suggestion.enabled.collectAsState()
    val emojiMode by prefs.suggestion.emojiMode.collectAsState()
    val offensive by prefs.suggestion.blockPossiblyOffensive.collectAsState()
    val beforeTyping by prefs.suggestion.beforeTyping.collectAsState()
    SetPage("Suggestions") {
        SetGroup("Suggestion bar") {
            SetSwitchRow("sg:on", "Suggestions", "Three words above the keys as you type.", suggestions) { v ->
                scope.launch { prefs.suggestion.enabled.set(v) }
            }
            Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TitleSub("Emoji in suggestions", "“After you use them”: once you’ve typed emoji, here and in the app you’re in.")
                val modes = listOf(EmojiSuggestionMode.OFF, EmojiSuggestionMode.AFTER_USE, EmojiSuggestionMode.ALWAYS)
                Segmented("sg:emoji", listOf("Off", "After you use them", "Always"), modes.indexOf(emojiMode), listOf(0.7f, 1.6f, 0.9f)) { i ->
                    scope.launch { prefs.suggestion.emojiMode.set(modes[i]) }
                }
            }
            // user 2026-11-19: "an option to show the tools vs first word suggestions by default"
            Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TitleSub("Before you type", "In an empty text box, show likely first words, or your tools (undo, clipboard, selection and more), as Gboard does.")
                val choices = listOf("suggestions", "tools")
                Segmented("sg:before", listOf("Words", "Tools"), choices.indexOf(beforeTyping).coerceAtLeast(0), listOf(1f, 1f)) { i ->
                    scope.launch { prefs.suggestion.beforeTyping.set(choices[i]) }
                }
            }
        }
        SetGroup("What’s offered") {
            SetSwitchRow("sg:offensive", "Block offensive words", "Swear words aren’t offered as suggestions.", offensive) { v ->
                scope.launch { prefs.suggestion.blockPossiblyOffensive.set(v) }
            }
            ContactNamesRow("sg:contacts")
        }
    }
}

/** Contact names, opt-in: asks for contacts access when turned on (Suggestions and Privacy share it). */
@Composable
internal fun ContactNamesRow(id: String) {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contacts by prefs.spelling.useContacts.collectAsState()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { prefs.spelling.useContacts.set(granted) }
    }
    SetSwitchRow(id, "Contact names", "Suggest the names in your contacts. They stay on this phone.", contacts) { v ->
        if (!v) scope.launch { prefs.spelling.useContacts.set(false) }
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            scope.launch { prefs.spelling.useContacts.set(true) }
        } else ask.launch(Manifest.permission.READ_CONTACTS)
    }
}

/** Autocorrect and typing › Capitals. */
@Composable
fun CapitalsScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val capsLock by prefs.typing.capsLockEnabled.collectAsState()
    SetPage("Capitals") {
        CapitalsGroup()
        SetGroup("Shift key") {
            SetSwitchRow("cp:capslock", "Caps lock", "Double-tap shift to type in capitals until you tap it again.", capsLock) { v ->
                scope.launch { prefs.typing.capsLockEnabled.set(v) }
            }
        }
    }
}

/** The automatic capitals group of Autocorrect and typing › Capitals, shared with the setup's typing page. */
@Composable
internal fun CapitalsGroup(title: String = "Automatic capitals") {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val sentenceCaps by prefs.typing.autoCapitalization.collectAsState()
    val nameCaps by prefs.typing.dictionaryCapitals.collectAsState()
    SetGroup(title) {
        SetSwitchRow("cp:sentence", "Sentence starts", "A capital after a full stop, question mark or new line.", sentenceCaps) { v ->
            scope.launch { prefs.typing.autoCapitalization.set(v) }
        }
        SetSwitchRow("cp:names", "Names and “I”", "Words the dictionary writes with a capital get one, like “I”, “Paris” and “Mr”.", nameCaps) { v ->
            scope.launch { prefs.typing.dictionaryCapitals.set(v) }
        }
    }
}

/** Autocorrect and typing › Smart punctuation. */
@Composable
fun PunctuationScreen() {
    SetPage("Smart punctuation") { PunctuationGroup() }
}

/** The punctuation group of Autocorrect and typing › Smart punctuation, shared with the setup's typing page. */
@Composable
internal fun PunctuationGroup(title: String = "As you type") {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val quotes by prefs.typing.smartQuotes.collectAsState()
    val dashes by prefs.typing.smartDashes.collectAsState()
    val doubleSpace by prefs.correction.doubleSpacePeriod.collectAsState()
    SetGroup(title) {
        SetSwitchRow("pn:quotes", "Smart quotes", "Curly quotes and apostrophes (“ ” ’) instead of straight ones.", quotes) { v ->
            scope.launch { prefs.typing.smartQuotes.set(v) }
        }
        SetSwitchRow("pn:dashes", "Smart dashes", "Two hyphens become a dash (—) and three full stops an ellipsis (…).", dashes) { v ->
            scope.launch { prefs.typing.smartDashes.set(v) }
        }
        SetSwitchRow("pn:dblspace", "Double-space full stop", "Two quick spaces type a full stop and a space.", doubleSpace) { v ->
            scope.launch { prefs.correction.doubleSpacePeriod.set(v) }
        }
    }
}
