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
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetSliderRow
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import io.github.sillypuppyy.dogboard.design.settings.Segmented
import io.github.sillypuppyy.dogboard.design.settings.TitleSub
import kotlinx.coroutines.launch

/**
 * Emoji and GIFs (settings tree v3): what DogBoard's emoji panel reads (MediaInputLayout's
 * DogBoardEmojiPanel: the skin tone, and the recent emoji through EmojiHistoryHelper). GIFs come
 * later (provider still to pick).
 */
@Composable
fun DogEmojiScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    val e = prefs.emoji
    val tone by e.preferredSkinTone.collectAsState()
    val recents by e.historyEnabled.collectAsState()
    val recentMax by e.historyRecentMaxSize.collectAsState()
    SetPage("Emoji and GIFs") {
        SetGroup("Emoji") {
            Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TitleSub("Skin tone", "For emoji that come in skin tones.")
                val tones = EmojiSkinTone.entries
                Segmented("em:tone", tones.map { if (it == EmojiSkinTone.DEFAULT) "👋" else "👋" + String(Character.toChars(it.id)) }, tones.indexOf(tone)) { i ->
                    scope.launch { e.preferredSkinTone.set(tones[i]) }
                }
            }
            SetSwitchRow("em:recents", "Recent emoji", "The emoji you used lately come first in the panel.", recents) { v ->
                scope.launch { e.historyEnabled.set(v) }
            }
            SetPageLink("em:suggest", "Emoji in suggestions", "When the bar offers emoji as you type") { nav.navigate(Routes.Settings.SuggestionsPage) }
        }
        // the rarely changed one, below the common settings
        SetGroup("Recent emoji") {
            SetSliderRow(
                "em:recentmax", "How many to keep", "The panel’s recent row keeps this many emoji.",
                recentMax.toFloat(), e.historyRecentMaxSize.default.toFloat(), 10f..200f, 10f, { v -> "${v.toInt()}" },
                enabled = recents,
            ) { v -> scope.launch { e.historyRecentMaxSize.set(v.toInt()) } }
        }
    }
}
