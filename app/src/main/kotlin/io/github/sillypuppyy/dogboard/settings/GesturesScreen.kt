package io.github.sillypuppyy.dogboard.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import io.github.sillypuppyy.dogboard.design.settings.setText
import kotlinx.coroutines.launch

/**
 * Gestures (settings tree v3): what a finger sliding across the keyboard does, instead of tapping.
 * Only what DogBoard's keyboard does: FlorisBoard's swipe settings (key swipes, space-bar swipes, swipe
 * on delete, glide typing) belong to its old keyboard view, which DogBoard doesn't use, so they did
 * nothing and aren't shown (found 2026-11-17). Swipe typing here is DogBoard's own (engine/swipe, on by
 * default with its trail, user 2026-11-17); the delete-key slide and the space bar's quick swipe are
 * Gboard's (user 2026-11-19). How long to hold and how fast things go: Timing and feel.
 */
@Composable
fun DogGesturesScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val trackpad by prefs.typing.trackpadEnabled.collectAsState()
    val swipe by prefs.typing.swipeEnabled.collectAsState()
    val trail by prefs.typing.swipeTrail.collectAsState()
    val deleteSlide by prefs.typing.deleteSlide.collectAsState()
    val quickDrag by prefs.typing.spaceQuickDrag.collectAsState()
    SetPage("Gestures") {
        // user 2026-11-19: "What's gestures for?"
        DText(
            "What happens when you slide your finger across the keyboard instead of tapping.",
            Modifier.padding(horizontal = 16.dp), style = setText(14f, 400, SetColors.onSurfaceVariant), maxLines = 3,
        )
        SetGroup("Swipe typing") {
            SetSwitchRow("gs:swipe", "Swipe to type", "Slide across the letters of a word without lifting your finger.", swipe) { v ->
                scope.launch { prefs.typing.swipeEnabled.set(v) }
            }
            SetSwitchRow("gs:trail", "Show the trail", "A line follows your finger while you swipe.", trail) { v ->
                scope.launch { prefs.typing.swipeTrail.set(v) }
            }
        }
        SetGroup("Delete key") {
            SetSwitchRow("gs:delslide", "Slide to delete words", "Slide left from delete to select words, then lift to delete them.", deleteSlide) { v ->
                scope.launch { prefs.typing.deleteSlide.set(v) }
            }
        }
        SetGroup("Space bar") {
            SetSwitchRow("gs:quickdrag", "Swipe to move the cursor", "Swipe sideways along the space bar to move the cursor.", quickDrag) { v ->
                scope.launch { prefs.typing.spaceQuickDrag.set(v) }
            }
            SetSwitchRow("gs:trackpad", "Hold to move the cursor", "Hold the space bar, then slide anywhere to move the cursor, as on an iPhone.", trackpad) { v ->
                scope.launch { prefs.typing.trackpadEnabled.set(v) }
            }
        }
    }
}
