package io.github.sillypuppyy.dogboard.settings

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
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetSliderRow
import io.github.sillypuppyy.dogboard.feel.Timings
import kotlinx.coroutines.launch

/**
 * Advanced › Fine-tuning (settings layout v2): the timings from the design's Feel page
 * (SetFeel:103-115), each with a reset; every row is read by the keyboard (FeelSettings).
 */
@Composable
fun FineTuningScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val holdUsesDefault by prefs.keyboard.longPressTimeoutUseSystem.collectAsState()
    val holdMs by prefs.keyboard.longPressTimeout.collectAsState()
    val capsMs by prefs.typing.capsLockDoubleTapMs.collectAsState()
    val dbl by prefs.typing.doubleSpaceMs.collectAsState()
    val first by prefs.typing.deleteFirstMs.collectAsState()
    val rep by prefs.typing.deleteCharMs.collectAsState()
    val words by prefs.typing.deleteWordsAfter.collectAsState()
    val wrep by prefs.typing.deleteWordMs.collectAsState()
    val tpHold by prefs.typing.trackpadHoldMs.collectAsState()
    val tpStep by prefs.typing.trackpadStepDp.collectAsState()
    val holdDefault = Timings.LongPress.inWholeMilliseconds.toFloat()
    val capsDefault = Timings.CapsLockDoubleTap.inWholeMilliseconds.toFloat()
    val ms = { v: Float -> "${v.toInt()} ms" }

    FlorisScreenNg(title = "Fine-tuning", previewFieldVisible = true) { contentPadding ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SetGroup("Timing") {
                SetSliderRow(
                    "ft:hold", "Hold to show accents", "How long to hold a key before its other letters appear.",
                    if (holdUsesDefault) holdDefault else holdMs.toFloat(), holdDefault, 200f..800f, 5f, ms,
                ) { v ->
                    scope.launch {
                        // the default is the keyboard's own timing (iOS 0.375 s), kept as "use default"
                        if (v == holdDefault) prefs.keyboard.longPressTimeoutUseSystem.set(true)
                        else { prefs.keyboard.longPressTimeout.set(v.toInt()); prefs.keyboard.longPressTimeoutUseSystem.set(false) }
                    }
                }
                SetSliderRow(
                    "ft:caps", "Caps lock double-tap", "Two taps on shift within this time turn on caps lock.",
                    capsMs.toFloat(), capsDefault, 150f..600f, 10f, ms,
                ) { v -> scope.launch { prefs.typing.capsLockDoubleTapMs.set(v.toInt()) } }
                SetSliderRow(
                    "ft:dbl", "Double-space for a full stop", "Two spaces within this time type “. ”.",
                    dbl / 1000f, prefs.typing.doubleSpaceMs.default / 1000f, 0.4f..2.0f, 0.1f, { v -> "%.1f s".format(v) },
                ) { v -> scope.launch { prefs.typing.doubleSpaceMs.set(Math.round(v * 1000)) } }
            }
            SetGroup("Delete key") {
                SetSliderRow(
                    "ft:first", "Start repeating after", "Hold delete this long before it starts repeating.",
                    first.toFloat(), prefs.typing.deleteFirstMs.default.toFloat(), 200f..1000f, 10f, ms,
                ) { v -> scope.launch { prefs.typing.deleteFirstMs.set(v.toInt()) } }
                SetSliderRow(
                    "ft:rep", "Repeat speed", "Time between deleted letters while you hold.",
                    rep.toFloat(), prefs.typing.deleteCharMs.default.toFloat(), 40f..200f, 5f, ms,
                ) { v -> scope.launch { prefs.typing.deleteCharMs.set(v.toInt()) } }
                SetSliderRow(
                    "ft:words", "Switch to whole words after",
                    "Switch to deleting whole words after holding delete for this many characters. 0 means never.",
                    words.toFloat(), prefs.typing.deleteWordsAfter.default.toFloat(), 0f..50f, 1f,
                    { v -> if (v.toInt() == 0) "Never" else "${v.toInt()} letters" },
                ) { v -> scope.launch { prefs.typing.deleteWordsAfter.set(v.toInt()) } }
                SetSliderRow(
                    "ft:wrep", "Word repeat speed", "Time between deleted words once it has switched.",
                    wrep.toFloat(), prefs.typing.deleteWordMs.default.toFloat(), 150f..800f, 10f, ms,
                ) { v -> scope.launch { prefs.typing.deleteWordMs.set(v.toInt()) } }
            }
            SetGroup("Space-bar trackpad") {
                SetSliderRow(
                    "ft:tphold", "Hold time", "Hold space this long to move the cursor instead.",
                    tpHold.toFloat(), prefs.typing.trackpadHoldMs.default.toFloat(), 200f..800f, 5f, ms,
                ) { v -> scope.launch { prefs.typing.trackpadHoldMs.set(v.toInt()) } }
                SetSliderRow(
                    "ft:tpspeed", "Cursor speed", "How far your finger moves for each character.",
                    tpStep.toFloat(), prefs.typing.trackpadStepDp.default.toFloat(), 4f..16f, 1f, { v -> "${v.toInt()} dp" },
                ) { v -> scope.launch { prefs.typing.trackpadStepDp.set(v.toInt()) } }
            }
        }
    }
}
