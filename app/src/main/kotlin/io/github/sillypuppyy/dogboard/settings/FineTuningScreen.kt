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
 * (SetFeel:103-115), each with a reset. Only timings the keyboard reads are shown; the delete-key
 * and trackpad rows follow once those timings are settings.
 */
@Composable
fun FineTuningScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val holdUsesDefault by prefs.keyboard.longPressTimeoutUseSystem.collectAsState()
    val holdMs by prefs.keyboard.longPressTimeout.collectAsState()
    val capsMs by prefs.typing.capsLockDoubleTapMs.collectAsState()
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
            }
        }
    }
}
