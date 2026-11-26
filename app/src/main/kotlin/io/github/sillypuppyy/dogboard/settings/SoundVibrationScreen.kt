package io.github.sillypuppyy.dogboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.components.FlorisScreenNg
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.UiText
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetSliderRow
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import io.github.sillypuppyy.dogboard.design.settings.Segmented
import io.github.sillypuppyy.dogboard.design.settings.SetMenu
import io.github.sillypuppyy.dogboard.design.settings.TitleSub
import io.github.sillypuppyy.dogboard.feel.HapticStyle
import kotlinx.coroutines.launch

/**
 * Sound and vibration (settings layout v2, feature-inventory.md §4): the switches, the vibration
 * style and the sound volume, then which events give feedback. No canvas for this page (the
 * designer's SetFeel is the advanced vibration editor, a separate page).
 */
@Composable
fun SoundVibrationScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val fb = prefs.inputFeedback
    val vibration by fb.hapticEnabled.collectAsState()
    val style by fb.hapticStyle.collectAsState()
    val sound by fb.audioEnabled.collectAsState()
    val volume by fb.audioVolume.collectAsState()
    val soundStyle by fb.audioStyle.collectAsState()
    val bypassSilent by fb.audioBypassSilent.collectAsState()
    val variation by fb.audioVariation.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val hPress by fb.hapticFeatKeyPress.collectAsState()
    val hLong by fb.hapticFeatKeyLongPress.collectAsState()
    val hRepeat by fb.hapticFeatKeyRepeatedAction.collectAsState()
    val hDrag by fb.hapticFeatGestureMovingSwipe.collectAsState()
    val sPress by fb.audioFeatKeyPress.collectAsState()
    val sRepeat by fb.audioFeatKeyRepeatedAction.collectAsState()

    FlorisScreenNg(title = "Sound and vibration", previewFieldVisible = true) { contentPadding ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SetGroup("Vibration") {
                SetSwitchRow("sv:vib", "Vibration", "A tap on each key.", vibration) { v -> scope.launch { fb.hapticEnabled.set(v) } }
                if (vibration) {
                    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TitleSub(
                            "Vibration style",
                            when (style) {
                                HapticStyle.CRISP -> "Short, sharp clicks, a little different for letters, space and delete."
                                HapticStyle.PHONE -> "Your phone’s own key click, the same for every key. Its strength follows Android’s touch feedback setting."
                                HapticStyle.SOFT -> "A softer, rounder tap."
                            },
                        )
                        val styles = listOf(HapticStyle.CRISP, HapticStyle.PHONE, HapticStyle.SOFT)
                        Segmented("sv:style", listOf("Crisp", "Phone", "Soft"), styles.indexOf(style)) { i -> scope.launch { fb.hapticStyle.set(styles[i]) } }
                    }
                }
            }
            SetGroup("Sound") {
                SetSwitchRow("sv:sound", "Sound", "A click on each key.", sound) { v -> scope.launch { fb.audioEnabled.set(v) } }
                if (sound) {
                    // a dropdown: six sounds were cramped as a segmented row; names only (descriptions dropped as noise)
                    val names = listOf(
                        "system" to "Phone",
                        "tick" to "Tick",
                        "drop" to "Drop",
                        "glass" to "Glass",
                        "thock" to "Thock",
                        "classic" to "Classic",
                        "tock" to "Tock",
                        "clack" to "Clack",
                        "pen" to "Pen",
                        "mouse" to "Mouse",
                        "mechkeys" to "Mech keys",
                    )
                    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var variationPreview by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }
                    val current = names.firstOrNull { it.first == soundStyle } ?: names.first()
                    fun preview(id: String, variationPercent: Int = variation) = scope.launch {
                        fb.audioStyle.set(id)
                        if (id != "system") kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            io.github.sillypuppyy.dogboard.feel.KeySounds.prepare(context, id, variationPercent)
                            // a short preview: three keys, a delete, a shift (SoundPool loads asynchronously)
                            kotlinx.coroutines.delay(150)
                            val v = volume / 100f
                            for (k in listOf(io.github.sillypuppyy.dogboard.feel.KeySounds.Kind.KEY, io.github.sillypuppyy.dogboard.feel.KeySounds.Kind.KEY,
                                io.github.sillypuppyy.dogboard.feel.KeySounds.Kind.KEY, io.github.sillypuppyy.dogboard.feel.KeySounds.Kind.DELETE,
                                io.github.sillypuppyy.dogboard.feel.KeySounds.Kind.MODIFIER)) { io.github.sillypuppyy.dogboard.feel.KeySounds.play(k, v, preview = true); kotlinx.coroutines.delay(180) }
                        }
                    }
                    androidx.compose.foundation.layout.Box {
                        Column(Modifier.fillMaxWidth().background(SetColors.row).clickable { open = true }.padding(16.dp)) {
                            TitleSub("Key sound", "${UiText.tr(current.second)}. ${UiText.tr("Picking one plays it, even on silent.")}") // translated in parts
                        }
                        SetMenu(open, { open = false }, names.map { (id, name) -> name to { preview(id) } })
                    }
                    // how much each press differs from the last (DogBoard's sounds only; Android's click is one fixed sound)
                    if (soundStyle != "system") SetSliderRow(
                        "sv:variation", "Sound variation", "How much each press differs, like a real keyboard where no two clicks sound alike.",
                        variation.toFloat(), fb.audioVariation.default.toFloat(), 0f..100f, 10f, { v -> "${v.toInt()} %" },
                    ) { v ->
                        // the slider reports every move: one preview per new value, the last one cut short
                        if (v.toInt() != variation) {
                            scope.launch { fb.audioVariation.set(v.toInt()) }
                            variationPreview?.cancel(); variationPreview = preview(soundStyle, v.toInt())
                        }
                    }
                    // Android's own click always follows silent mode; DogBoard's sounds can play on the media stream
                    if (soundStyle != "system") SetSwitchRow("sv:bypass", "Play on silent", "Key sounds even when your phone is on silent or vibrate, at the media volume.", bypassSilent) { v -> scope.launch { fb.audioBypassSilent.set(v) } }
                }
                SetSliderRow(
                    "sv:volume", "Volume", "How loud the key click is.",
                    volume.toFloat(), fb.audioVolume.default.toFloat(), 1f..100f, 1f, { v -> "${v.toInt()} %" },
                    enabled = sound,
                ) { v -> scope.launch { fb.audioVolume.set(v.toInt()) } }
            }
            if (vibration || sound) SetGroup("When") {
                if (vibration) {
                    SetSwitchRow("sv:hpress", "Vibrate on key presses", "Every key you tap.", hPress) { v -> scope.launch { fb.hapticFeatKeyPress.set(v) } }
                    SetSwitchRow("sv:hrepeat", "Vibrate while delete repeats", "Each letter removed while you hold delete.", hRepeat) { v -> scope.launch { fb.hapticFeatKeyRepeatedAction.set(v) } }
                    SetSwitchRow("sv:hlong", "Vibrate on long press", "When a key’s accents or symbols open.", hLong) { v -> scope.launch { fb.hapticFeatKeyLongPress.set(v) } }
                    SetSwitchRow("sv:hdrag", "Vibrate while sliding", "Each step as you slide along the space bar or the delete key.", hDrag) { v -> scope.launch { fb.hapticFeatGestureMovingSwipe.set(v) } }
                }
                if (sound) {
                    SetSwitchRow("sv:spress", "Click on key presses", "Every key you tap.", sPress) { v -> scope.launch { fb.audioFeatKeyPress.set(v) } }
                    SetSwitchRow("sv:srepeat", "Click while delete repeats", "Each letter removed while you hold delete.", sRepeat) { v -> scope.launch { fb.audioFeatKeyRepeatedAction.set(v) } }
                }
            }
        }
    }
}
