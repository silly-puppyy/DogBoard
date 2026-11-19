package io.github.sillypuppyy.dogboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.ime.landscapeinput.LandscapeInputUiMode
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetSliderRow
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import io.github.sillypuppyy.dogboard.design.settings.Segmented
import io.github.sillypuppyy.dogboard.design.settings.TitleSub
import kotlinx.coroutines.launch

/**
 * Advanced (settings tree v3; user OK 2026-11-17). Only what DogBoard's keyboard reads: FlorisBoard's
 * key spacing and label size only change its own key drawing, which DogBoard doesn't use, so they
 * aren't here. Developer options appear after tapping the version in About 7 times.
 */
@Composable
fun DogAdvancedScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    val barTap by prefs.typing.barTapWindowMs.collectAsState()
    val popup by prefs.keyboard.popupEnabled.collectAsState()
    val landscape by prefs.keyboard.landscapeInputUiMode.collectAsState()
    val neural by prefs.correction.neuralModel.collectAsState()
    val appIcon by prefs.other.showAppIcon.collectAsState()
    val dev by prefs.other.devOptionsUnlocked.collectAsState()
    SetPage("Advanced") {
        SetGroup("Typing") {
            SetSliderRow(
                "adv:bartap", "Taps on the suggestion bar", "A tap on the bar this soon after a key is read as the top-row key under it, as typing fast often lands there. 0 turns it off.",
                barTap.toFloat(), prefs.typing.barTapWindowMs.default.toFloat(), 0f..800f, 50f, { v -> "${v.toInt()} ms" },
            ) { v -> scope.launch { prefs.typing.barTapWindowMs.set(v.toInt()) } }
            SetSwitchRow("adv:popup", "Key preview", "The letter shows above your finger while you press a key.", popup) { v ->
                scope.launch { prefs.keyboard.popupEnabled.set(v) }
            }
        }
        SetGroup("Screen") {
            Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TitleSub("Full-screen typing in landscape", "In landscape the text box can take the whole screen above the keyboard. “Auto” does it when there isn’t room otherwise.")
                val modes = listOf(LandscapeInputUiMode.DYNAMICALLY_SHOW, LandscapeInputUiMode.ALWAYS_SHOW, LandscapeInputUiMode.NEVER_SHOW)
                Segmented("adv:landscape", listOf("Auto", "Always", "Never"), modes.indexOf(landscape)) { i ->
                    scope.launch { prefs.keyboard.landscapeInputUiMode.set(modes[i]) }
                }
            }
            SetPageLink("adv:physical", "Physical keyboard", "When a keyboard is connected") { nav.navigate(Routes.Settings.PhysicalKeyboard) }
        }
        SetGroup("Engine") {
            SetSwitchRow("adv:neural", "Neural model", "A small on-phone language model makes suggestions and corrections a little better. Turn it off to save battery and memory. Phones with less than about 5 GB of memory never use it.", neural) { v ->
                scope.launch { prefs.correction.neuralModel.set(v) }
            }
        }
        SetGroup("App") {
            SetSwitchRow("adv:icon", "Show the app icon", "DogBoard’s icon in your app list. Without it, open these settings from Android’s keyboard settings.", appIcon) { v ->
                scope.launch { prefs.other.showAppIcon.set(v) }
            }
            SetPageLink("adv:backup", "Back up settings and words", "Save them to a file") { nav.navigate(Routes.Settings.Backup) }
            SetPageLink("adv:restore", "Restore from a backup", "Load a saved file") { nav.navigate(Routes.Settings.Restore()) }
        }
        if (dev) SetGroup("Developer") {
            SetPageLink("adv:devtools", "Developer options", "Touch areas, overlays and logs") { nav.navigate(Routes.Devtools.Home) }
        }
    }
}
