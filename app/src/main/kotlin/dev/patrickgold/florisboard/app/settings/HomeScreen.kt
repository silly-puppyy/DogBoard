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

package dev.patrickgold.florisboard.app.settings

import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.components.ScrollableScreenColumn
import dev.patrickgold.florisboard.app.components.SegmentedListColumn
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.jetpref.datastore.ui.Preference
import org.florisboard.lib.compose.FlorisErrorCard
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.stringRes

@Composable
fun HomeScreen() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val isFlorisBoardEnabled by InputMethodUtils.observeIsFlorisboardEnabled()
    val isFlorisBoardSelected by InputMethodUtils.observeIsFlorisboardSelected()

    // DogBoard: the ported settings home (SetHome.dc.html); its rows lead to the existing screens
    // until those are ported too
    val prefs by dev.patrickgold.florisboard.app.FlorisPreferenceStore
    val dogboardDesign by prefs.keyboard.dogboardDesign.collectAsState()
    if (dogboardDesign) {
        // the design's home has its own "Try it here" dock: hide FlorisBoard's preview bar, which
        // otherwise stays as the previous screen left it
        val previewField = dev.patrickgold.florisboard.app.components.LocalPreviewFieldController.current
        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { // on resume: upstream #3277
            previewField.isVisible = false
            onPauseOrDispose { }
        }
        val autocorrectOn by prefs.correction.autocorrect.collectAsState()
        val level by prefs.correction.autocorrectLevel.collectAsState()
        val sound by prefs.inputFeedback.audioEnabled.collectAsState()
        val vibration by prefs.inputFeedback.hapticEnabled.collectAsState()
        val numberRow by prefs.keyboard.numberRow.collectAsState()
        val actionWords by prefs.keyboard.actionKeyWords.collectAsState()
        // the layout elements, followed through their flows, as the Layout style selector
        prefs.keyboard.numberRow.collectAsState().value; prefs.keyboard.numberRowLongPress.collectAsState().value
        prefs.keyboard.bottomRow.collectAsState().value; prefs.keyboard.hintKind.collectAsState().value
        prefs.keyboard.spaceLabel.collectAsState().value; prefs.keyboard.capsLabels.collectAsState().value
        prefs.keyboard.symbolPages.collectAsState().value; prefs.keyboard.pillKeys.collectAsState().value
        val layoutPreset = io.github.sillypuppyy.dogboard.settings.LayoutPrefs.presetIndex(io.github.sillypuppyy.dogboard.settings.LayoutPrefs.read(prefs))
        val keyboardHeight by prefs.keyboard.heightPercent.collectAsState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val L = io.github.sillypuppyy.dogboard.engine.AutocorrectLevel.entries
        io.github.sillypuppyy.dogboard.design.settings.SetHome(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            actions = io.github.sillypuppyy.dogboard.design.settings.SetHomeActions(
                autocorrect = if (!autocorrectOn) 0 else 1 + L.indexOf(level),
                onAutocorrect = { i ->
                    scope.launch {
                        prefs.correction.autocorrect.set(i > 0)
                        if (i > 0) prefs.correction.autocorrectLevel.set(L[i - 1])
                    }
                },
                onLanguages = { navController.navigate(Routes.Settings.Localization) },
                onLook = { navController.navigate(Routes.Settings.Themes) },
                onSearch = { navController.navigate(Routes.Settings.Search) },
                sound = sound,
                onSound = { on -> scope.launch { prefs.inputFeedback.audioEnabled.set(on) } },
                vibration = vibration,
                onVibration = { on -> scope.launch { prefs.inputFeedback.hapticEnabled.set(on) } },
                numberRow = numberRow,
                onNumberRow = { on -> scope.launch { prefs.keyboard.numberRow.set(on) } },
                actionWords = actionWords,
                layoutPreset = layoutPreset,
                onLayoutPreset = { i ->
                    when (i) {
                        0 -> scope.launch { io.github.sillypuppyy.dogboard.settings.LayoutPrefs.write(prefs, io.github.sillypuppyy.dogboard.design.settings.LayoutElements.SIMPLE) }
                        1 -> scope.launch { io.github.sillypuppyy.dogboard.settings.LayoutPrefs.write(prefs, io.github.sillypuppyy.dogboard.design.settings.LayoutElements.CLASSIC) }
                        else -> navController.navigate(Routes.Settings.Layout)
                    }
                },
                onActionWords = { on -> scope.launch { prefs.keyboard.actionKeyWords.set(on) } },
                keyboardHeight = keyboardHeight,
                onKeyboardHeight = { v -> scope.launch { prefs.keyboard.heightPercent.set(v) } },
                onSection = { title ->
                    navController.navigate(
                        when (title) {
                            "Languages and layouts" -> Routes.Settings.Localization
                            "Look" -> Routes.Settings.Theme
                            "Typing and correction" -> Routes.Settings.TypingCorrection
                            "Privacy" -> Routes.Settings.Privacy
                            "Your words" -> Routes.Settings.Dictionary
                            "Sound and vibration" -> Routes.Settings.Keyboard.InputFeedback
                            "Gestures" -> Routes.Settings.Gestures
                            "Clipboard and toolbar" -> Routes.Settings.Clipboard
                            "Advanced" -> Routes.Settings.Other
                            else -> Routes.Settings.About
                        }
                    )
                },
            ),
        )
        return
    }

    FlorisScreenNg(
        title = stringRes(R.string.settings__home__title),
        navigationIconVisible = false,
        previewFieldVisible = true,
    ) { contentPadding ->
        ScrollableScreenColumn(contentPadding) {
            if (!isFlorisBoardEnabled) {
                FlorisErrorCard(
                    showIcon = false,
                    text = stringRes(R.string.settings__home__ime_not_enabled),
                    onClick = { InputMethodUtils.showImeEnablerActivity(context) },
                )
            } else if (!isFlorisBoardSelected) {
                FlorisWarningCard(
                    showIcon = false,
                    text = stringRes(R.string.settings__home__ime_not_selected),
                    onClick = { InputMethodUtils.showImePicker(context) },
                )
            }

            SegmentedListColumn {
                val count = 4
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    icon = Icons.Default.Language,
                    title = stringRes(R.string.settings__localization__title),
                    onClick = { navController.navigate(Routes.Settings.Localization) },
                )
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    icon = Icons.Outlined.Palette,
                    title = stringRes(R.string.settings__theme__title),
                    onClick = { navController.navigate(Routes.Settings.Theme) },
                )
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    icon = Icons.Outlined.Keyboard,
                    title = stringRes(R.string.settings__keyboard__title),
                    onClick = { navController.navigate(Routes.Settings.Keyboard) },
                )
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(3, count),
                    icon = Icons.Default.SmartButton,
                    title = stringRes(R.string.settings__smartbar__title),
                    onClick = { navController.navigate(Routes.Settings.Smartbar) },
                )
            }

            SegmentedListColumn {
                val count = 4
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    icon = Icons.Default.Spellcheck,
                    title = stringRes(R.string.settings__typing__title),
                    onClick = { navController.navigate(Routes.Settings.Typing) },
                )
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    icon = Icons.Default.Gesture,
                    title = stringRes(R.string.settings__gestures__title),
                    onClick = { navController.navigate(Routes.Settings.Gestures) },
                )
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(2, count),
                    icon = Icons.AutoMirrored.Outlined.Assignment,
                    title = stringRes(R.string.settings__clipboard__title),
                    onClick = { navController.navigate(Routes.Settings.Clipboard) },
                )
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(3, count),
                    icon = Icons.Default.SentimentSatisfiedAlt,
                    title = stringRes(R.string.settings__media__title),
                    onClick = { navController.navigate(Routes.Settings.Media) },
                )
            }

            SegmentedListColumn {
                val count = 2
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    icon = Icons.Outlined.Build,
                    title = stringRes(R.string.settings__other__title),
                    onClick = { navController.navigate(Routes.Settings.Other) },
                )
                Preference(
                    shapes = ListItemDefaults.segmentedShapes(1, count),
                    icon = Icons.Outlined.Info,
                    title = stringRes(R.string.about__title),
                    onClick = { navController.navigate(Routes.Settings.About) },
                )
            }
        }
    }
}
