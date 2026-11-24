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
    val noBackupPrefs by dev.patrickgold.florisboard.app.NoBackupPreferenceStore
    val nav = LocalNavController.current
    val barTap by prefs.typing.barTapWindowMs.collectAsState()
    val popup by prefs.keyboard.popupEnabled.collectAsState()
    val landscape by prefs.keyboard.landscapeInputUiMode.collectAsState()
    val neural by prefs.correction.neuralModel.collectAsState()
    val appIcon by prefs.other.showAppIcon.collectAsState()
    val devOn by prefs.other.devOptionsUnlocked.collectAsState()
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
            // a problem on the phone, no cable: versions, keyboards, packs, engine state and the keyboard's own log lines
            // to the clipboard (nothing typed; nothing sent)
            val context = androidx.compose.ui.platform.LocalContext.current
            SetPageLink("adv:report", "Copy a report", "For sending when something goes wrong", icon = "content_copy") {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Report.copy(context) }
                    android.widget.Toast.makeText(context, "Report copied. Paste it where you were asked.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // something stuck (user: autocorrect stopped and stayed off): the word engine and every loaded language
            // loaded again in the running keyboard (ending the process made Android switch to another keyboard)
            SetPageLink("adv:reload", "Reload the keyboard", "Loads your languages again if suggestions or corrections stop", icon = "restart_alt") {
                scope.launch {
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.sillypuppyy.dogboard.ime.EngineLoader.reloadAll(context)
                        io.github.sillypuppyy.dogboard.engine.TypingEngine.isReady
                    }
                    android.widget.Toast.makeText(context, if (ok) "Your languages are loaded again" else "Couldn’t load your languages. Copy a report to send.",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // (moved from About: redoing the setup is a settings task, not information about the app)
            SetPageLink("adv:setup", "Set up again", "Walk through the first-run setup once more") {
                scope.launch { noBackupPrefs.isImeSetUp.set(false) }
                nav.navigate(dev.patrickgold.florisboard.app.Routes.Setup.Screen()) { popUpTo(dev.patrickgold.florisboard.app.Routes.Settings.Home) { inclusive = true } }
            }
        }
        if (devOn || dev.patrickgold.florisboard.BuildConfig.DEBUG) SetGroup("Developer") {
            SetPageLink("adv:testing", "Testing", "Replay the setup, log your keystrokes in a test field") { nav.navigate(Routes.Settings.Testing) }
            SetPageLink("adv:devtools", "Developer options", "Touch areas, overlays and logs") { nav.navigate(Routes.Devtools.Home) }
        }
    }
}
