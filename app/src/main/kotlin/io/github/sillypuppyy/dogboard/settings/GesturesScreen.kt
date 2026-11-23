package io.github.sillypuppyy.dogboard.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import io.github.sillypuppyy.dogboard.design.settings.SetMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.sillypuppyy.dogboard.design.settings.setText
import kotlinx.coroutines.launch

/**
 * Gestures (settings tree v3): what a finger sliding across the keyboard does, instead of tapping.
 * Only what DogBoard's keyboard does: FlorisBoard's swipe settings (key swipes, space-bar swipes, swipe
 * on delete, glide typing) belong to its old keyboard view, which DogBoard doesn't use, so they did
 * nothing and aren't shown (found 2026-11-17). Swipe typing here is DogBoard's own (engine/swipe, on by
 * default with its trail); the delete-key slide and the space bar's quick swipe are
 * Gboard's. How long to hold and how fast things go: Timing and feel.
 */
@Composable
fun DogGesturesScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val swipe by prefs.typing.swipeEnabled.collectAsState()
    val trail by prefs.typing.swipeTrail.collectAsState()
    val deleteSlide by prefs.typing.deleteSlide.collectAsState()
    val quickDrag by prefs.typing.spaceQuickDrag.collectAsState()
    val spaceHold by prefs.typing.spaceHold.collectAsState()
    var holdMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    SetPage("Gestures") {
        // "What's gestures for?"
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
            // holding space can move the cursor (iPhone), show your languages (Gboard), or do nothing
            val holdChoices = listOf(
                Triple("cursor", "Move the cursor", "Hold, then slide anywhere to move the cursor, as on an iPhone."),
                Triple("language", "Change language", "Hold to pick one of your languages."),
                Triple("nothing", "Nothing", "Holding types a space, like a tap."),
            )
            val holdNow = holdChoices.firstOrNull { it.first == spaceHold } ?: holdChoices.first()
            androidx.compose.foundation.layout.Box {
                androidx.compose.foundation.layout.Column(
                    Modifier.fillMaxWidth().background(SetColors.row).clickable { holdMenu = true }.padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
                ) {
                    DText("Hold the space bar: ${holdNow.second.lowercase()}", style = setText(16f).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                    DText(holdNow.third, style = setText(14f, 400, SetColors.onSurfaceVariant).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), maxLines = 3)
                }
                SetMenu(holdMenu, { holdMenu = false }, holdChoices.map { (id, name, _) -> name to { scope.launch { prefs.typing.spaceHold.set(id); if (id == "cursor") prefs.typing.trackpadEnabled.set(true) }; holdMenu = false } })
            }
        }
    }
}
