package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import kotlinx.coroutines.launch

/**
 * Gestures (settings tree v3). Only what DogBoard's keyboard does: FlorisBoard's swipe settings
 * (key swipes, space-bar swipes, swipe on delete, glide typing) belong to its old keyboard view,
 * which DogBoard doesn't use, so they did nothing and aren't shown (found 2026-11-17). Swipe typing
 * here is DogBoard's own (engine/swipe, on by default with its trail, user 2026-11-17).
 */
@Composable
fun DogGesturesScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    val trackpad by prefs.typing.trackpadEnabled.collectAsState()
    val swipe by prefs.typing.swipeEnabled.collectAsState()
    val trail by prefs.typing.swipeTrail.collectAsState()
    SetPage("Gestures") {
        SetGroup("Swipe typing") {
            SetSwitchRow("gs:swipe", "Swipe to type", "Slide across the letters of a word without lifting your finger.", swipe) { v ->
                scope.launch { prefs.typing.swipeEnabled.set(v) }
            }
            SetSwitchRow("gs:trail", "Show the trail", "A line follows your finger while you swipe.", trail) { v ->
                scope.launch { prefs.typing.swipeTrail.set(v) }
            }
        }
        SetGroup("Space bar") {
            SetSwitchRow("gs:trackpad", "Move the cursor", "Hold the space bar, then slide to move the cursor, as on an iPhone.", trackpad) { v ->
                scope.launch { prefs.typing.trackpadEnabled.set(v) }
            }
        }
        SetGroup("More") {
            SetPageLink("gs:timings", "Timings", "How long to hold, how far to slide, and the delete key’s speed") { nav.navigate(Routes.Settings.FineTuning) }
        }
    }
}
