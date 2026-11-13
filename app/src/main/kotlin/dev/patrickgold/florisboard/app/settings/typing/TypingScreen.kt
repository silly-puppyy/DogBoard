/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.app.settings.typing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.components.ScrollableScreenColumn
import dev.patrickgold.florisboard.app.components.SegmentedListColumn
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard3.touch.ShiftKeyBehavior
import dev.patrickgold.florisboard.ime.nlp.SpellingLanguageMode
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import io.github.sillypuppyy.dogboard.engine.AutocorrectLevel
import io.github.sillypuppyy.dogboard.engine.EmojiSuggestionMode
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.compose.stringRes

@Composable
fun TypingScreen() {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current

    FlorisScreenNg(
        title = stringRes(R.string.settings__typing__title),
        previewFieldVisible = true,
    ) { contentPadding ->
        ScrollableScreenColumn(contentPadding) {
            SegmentedListColumn(title = stringRes(R.string.pref__typing_caps_group__title)) {
                val count = 4
                SwitchPreference(
                    prefs.typing.autoCapitalization,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    title = stringRes(R.string.pref__correction__auto_capitalization__label),
                    summary = stringRes(R.string.pref__correction__auto_capitalization__summary),
                )
                SwitchPreference(
                    prefs.typing.rememberCapsLockState,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    title = stringRes(R.string.pref__correction__remember_caps_lock_state__label),
                    summary = stringRes(R.string.pref__correction__remember_caps_lock_state__summary),
                )
                // DogBoard: iOS "Enable Caps Lock"
                SwitchPreference(
                    prefs.typing.capsLockEnabled,
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    title = stringRes(R.string.pref__typing__caps_lock_enabled__label),
                    summary = stringRes(R.string.pref__typing__caps_lock_enabled__summary),
                )
                ListPreference(
                    prefs.typing.shiftKeyBehavior,
                    shapes = ListItemDefaults.segmentedShapes(3, count),
                    title = stringRes(R.string.pref__typing__shift_key_behavior__label),
                    entries = enumDisplayEntriesOf(ShiftKeyBehavior::class),
                    enabledIf = { prefs.typing.capsLockEnabled isEqualTo true },
                )
            }

            SegmentedListColumn(title = stringRes(R.string.pref__suggestion__title)) {
                val countOffset = if (AndroidVersion.ATLEAST_API30_R) 1 else 0
                val count = 4 + countOffset
                SwitchPreference(
                    prefs.suggestion.enabled,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    title = stringRes(R.string.pref__suggestion__enabled__label),
                    summary = stringRes(R.string.pref__suggestion__enabled__summary),
                )
                SwitchPreference(
                    prefs.suggestion.blockPossiblyOffensive,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    title = stringRes(R.string.pref__suggestion__block_possibly_offensive__label),
                    summary = stringRes(R.string.pref__suggestion__block_possibly_offensive__summary),
                    enabledIf = { prefs.suggestion.enabled isEqualTo true },
                )
                if (AndroidVersion.ATLEAST_API30_R) {
                    SwitchPreference(
                        prefs.suggestion.api30InlineSuggestionsEnabled,
                        shapes = ListItemDefaults.segmentedShapes(2, count),
                        title = stringRes(R.string.pref__suggestion__api30_inline_suggestions_enabled__label),
                        summary = stringRes(R.string.pref__suggestion__api30_inline_suggestions_enabled__summary),
                    )
                }
                // DogBoard: when the bar offers emoji (settings-model §1)
                ListPreference(
                    prefs.suggestion.emojiMode,
                    shapes = ListItemDefaults.segmentedShapes(2 + countOffset, count),
                    title = stringRes(R.string.pref__suggestion__emoji_mode__label),
                    entries = listPrefEntries {
                        entry(EmojiSuggestionMode.OFF, stringRes(R.string.enum__emoji_suggestion_mode__off))
                        entry(EmojiSuggestionMode.AFTER_USE, stringRes(R.string.enum__emoji_suggestion_mode__after_use))
                        entry(EmojiSuggestionMode.ALWAYS, stringRes(R.string.enum__emoji_suggestion_mode__always))
                    },
                    enabledIf = { prefs.suggestion.enabled isEqualTo true },
                )
                ListPreference(
                    prefs.suggestion.incognitoMode,
                    shapes = ListItemDefaults.segmentedShapes(3 + countOffset, count),
                    icon = ImageVector.vectorResource(id = R.drawable.ic_incognito),
                    title = stringRes(R.string.pref__suggestion__incognito_mode__label),
                    entries = enumDisplayEntriesOf(IncognitoMode::class),
                )
            }

            SegmentedListColumn(title = stringRes(R.string.pref__correction__title)) {
                val count = 5
                // DogBoard
                SwitchPreference(
                    prefs.correction.autocorrect,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    title = stringRes(R.string.pref__correction__autocorrect__label),
                    summary = stringRes(R.string.pref__correction__autocorrect__summary),
                )
                ListPreference(
                    prefs.correction.autocorrectLevel,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    title = stringRes(R.string.pref__correction__autocorrect_level__label),
                    entries = listPrefEntries {
                        entry(AutocorrectLevel.GENTLE, stringRes(R.string.enum__autocorrect_level__gentle))
                        entry(AutocorrectLevel.STANDARD, stringRes(R.string.enum__autocorrect_level__standard))
                        entry(AutocorrectLevel.STRONG, stringRes(R.string.enum__autocorrect_level__strong))
                    },
                    enabledIf = { prefs.correction.autocorrect isEqualTo true },
                )
                SwitchPreference(
                    prefs.correction.doubleSpacePeriod,
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    title = stringRes(R.string.pref__correction__double_space_period__label),
                    summary = stringRes(R.string.pref__correction__double_space_period__summary),
                )
                // DogBoard: iOS Smart Punctuation, two switches
                SwitchPreference(
                    prefs.typing.smartQuotes,
                    shapes = ListItemDefaults.segmentedShapes(3, count),
                    title = stringRes(R.string.pref__typing__smart_quotes__label),
                    summary = stringRes(R.string.pref__typing__smart_quotes__summary),
                )
                SwitchPreference(
                    prefs.typing.smartDashes,
                    shapes = ListItemDefaults.segmentedShapes(4, count),
                    title = stringRes(R.string.pref__typing__smart_dashes__label),
                    summary = stringRes(R.string.pref__typing__smart_dashes__summary),
                )
            }

            SegmentedListColumn(title = stringRes(R.string.pref__spelling__title)) {
                val count = 2
                val florisSpellCheckerEnabled = remember { mutableStateOf(false) }
                SpellCheckerServiceSelector(florisSpellCheckerEnabled)
                ListPreference(
                    prefs.spelling.languageMode,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    icon = Icons.Default.Language,
                    title = stringRes(R.string.pref__spelling__language_mode__label),
                    entries = enumDisplayEntriesOf(SpellingLanguageMode::class),
                    enabledIf = { florisSpellCheckerEnabled.value },
                )
                /*
                SwitchPreference(
                    prefs.spelling.useContacts,
                    icon = Icons.Default.Contacts,
                    title = stringRes(R.string.pref__spelling__use_contacts__label),
                    summary = stringRes(R.string.pref__spelling__use_contacts__summary),
                    enabledIf = { florisSpellCheckerEnabled.value },
                )
                SwitchPreference(
                    prefs.spelling.useUdmEntries,
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                    title = stringRes(R.string.pref__spelling__use_udm_entries__label),
                    summary = stringRes(R.string.pref__spelling__use_udm_entries__summary),
                    enabledIf = { florisSpellCheckerEnabled.value },
                )
                */
            }

            /*
            SegmentedListColumn(title = stringRes(R.string.settings__dictionary__title)) {
                val count = 1
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                    title = stringRes(R.string.settings__dictionary__title),
                    onClick = { navController.navigate(Routes.Settings.Dictionary) },
                    enabledIf = { false },
                )
            }
            */
        }
    }
}
