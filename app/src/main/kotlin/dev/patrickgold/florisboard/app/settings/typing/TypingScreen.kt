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

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
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
            // DogBoard: the letter layout (design pass phase 1; languages come with the i18n pass)
            SegmentedListColumn(title = stringRes(R.string.pref__keyboard__layout_group__title)) {
                ListPreference(
                    prefs.keyboard.layoutId,
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    title = stringRes(R.string.pref__keyboard__layout__label),
                    entries = listPrefEntries {
                        entry("qwerty-en", "English (QWERTY)")
                        entry("qwertz-de", "Deutsch (QWERTZ)")
                        entry("azerty-fr", "Français (AZERTY)")
                    },
                )
            }
            androidx.compose.material3.Text(
                stringRes(R.string.pref__keyboard__layout__summary),
                modifier = androidx.compose.ui.Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentedListColumn(title = stringRes(R.string.pref__typing_caps_group__title)) {
                val count = 5
                SwitchPreference(
                    prefs.typing.autoCapitalization,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    title = stringRes(R.string.pref__correction__auto_capitalization__label),
                    summary = stringRes(R.string.pref__correction__auto_capitalization__summary),
                )
                // DogBoard: names and "I", separate from sentence starts
                SwitchPreference(
                    prefs.typing.dictionaryCapitals,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    title = stringRes(R.string.pref__typing__dictionary_capitals__label),
                    summary = stringRes(R.string.pref__typing__dictionary_capitals__summary),
                )
                SwitchPreference(
                    prefs.typing.rememberCapsLockState,
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    title = stringRes(R.string.pref__correction__remember_caps_lock_state__label),
                    summary = stringRes(R.string.pref__correction__remember_caps_lock_state__summary),
                )
                // DogBoard: iOS "Enable Caps Lock"
                SwitchPreference(
                    prefs.typing.capsLockEnabled,
                    shapes = ListItemDefaults.segmentedShapes(3, count),
                    title = stringRes(R.string.pref__typing__caps_lock_enabled__label),
                    summary = stringRes(R.string.pref__typing__caps_lock_enabled__summary),
                )
                ListPreference(
                    prefs.typing.shiftKeyBehavior,
                    shapes = ListItemDefaults.segmentedShapes(4, count),
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

            // DogBoard: the adaptive-autocorrect notification needs POST_NOTIFICATIONS (Android 13+): asked
            // here (a keyboard cannot ask): once while both switches are on, and again when the user turns
            // 'Tell me when it adapts' on
            val notifyContext = androidx.compose.ui.platform.LocalContext.current
            val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { }
            val adaptiveOn by prefs.correction.adaptiveStrength.collectAsState()
            val notifyOn by prefs.correction.adaptiveNotify.collectAsState()
            val notifyWas = remember { mutableStateOf(notifyOn) }
            androidx.compose.runtime.LaunchedEffect(adaptiveOn, notifyOn) {
                val turnedOn = notifyOn && !notifyWas.value
                notifyWas.value = notifyOn
                if (adaptiveOn && notifyOn && (turnedOn || !prefs.correction.adaptiveNotifyAsked.get()) &&
                    android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(notifyContext, android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.correction.adaptiveNotifyAsked.set(true)
                    askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
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
                // DogBoard: autocorrect that adapts to the user, and the notification when it does
                SwitchPreference(
                    prefs.correction.adaptiveStrength,
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    title = stringRes(R.string.pref__correction__adaptive_strength__label),
                    summary = stringRes(R.string.pref__correction__adaptive_strength__summary),
                    enabledIf = { prefs.correction.autocorrect isEqualTo true },
                )
                SwitchPreference(
                    prefs.correction.adaptiveNotify,
                    shapes = ListItemDefaults.segmentedShapes(3, count),
                    title = stringRes(R.string.pref__correction__adaptive_notify__label),
                    summary = stringRes(R.string.pref__correction__adaptive_notify__summary),
                    enabledIf = { prefs.correction.autocorrect isEqualTo true && prefs.correction.adaptiveStrength isEqualTo true },
                )
                SwitchPreference(
                    prefs.correction.neuralRetro,
                    shapes = ListItemDefaults.segmentedShapes(4, count),
                    title = stringRes(R.string.pref__correction__neural_retro__label),
                    summary = stringRes(R.string.pref__correction__neural_retro__summary),
                    enabledIf = { prefs.correction.autocorrect isEqualTo true },
                )
            }

            // DogBoard: punctuation helpers in one place (user, 2026-11-14)
            SegmentedListColumn(title = stringRes(R.string.pref__typing__punctuation_group__title)) {
                val count = 3
                SwitchPreference(
                    prefs.correction.doubleSpacePeriod,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    title = stringRes(R.string.pref__correction__double_space_period__label),
                    summary = stringRes(R.string.pref__correction__double_space_period__summary),
                )
                // DogBoard: iOS Smart Punctuation, two switches
                SwitchPreference(
                    prefs.typing.smartQuotes,
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    title = stringRes(R.string.pref__typing__smart_quotes__label),
                    summary = stringRes(R.string.pref__typing__smart_quotes__summary),
                )
                SwitchPreference(
                    prefs.typing.smartDashes,
                    shapes = ListItemDefaults.segmentedShapes(2, count),
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
