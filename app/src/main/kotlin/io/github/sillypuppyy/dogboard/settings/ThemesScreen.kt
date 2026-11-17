package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.ThemeChoice
import io.github.sillypuppyy.dogboard.design.settings.SetThemes
import io.github.sillypuppyy.dogboard.design.settings.SetThemesState
import kotlinx.coroutines.launch

/** Settings › Look › Themes, live (design SetThemes.dc.html): the day and night themes, saved as they change. */
@Composable
fun ThemesScreen() {
    val prefs by FlorisPreferenceStore
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember() // previews in the default look
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val day by prefs.keyboard.themeDay.collectAsState()
    val night by prefs.keyboard.themeNight.collectAsState()
    val follow by prefs.keyboard.themeFollow.collectAsState()
    val reroll by prefs.keyboard.spotReroll.collectAsState()
    val seed by prefs.keyboard.spotSeed.collectAsState()
    val hidden by prefs.keyboard.hiddenThemePacks.collectAsState()
    val choice = ThemeChoice.Choice(day, night, follow)
    suspend fun store(c: ThemeChoice.Choice) {
        prefs.keyboard.themeDay.set(c.day)
        prefs.keyboard.themeNight.set(c.night)
        prefs.keyboard.themeFollow.set(c.follow)
        // the Photo theme is the glass look (its photo and settings live on the photo page)
        prefs.keyboard.glassEnabled.set(c.day == "glass" || (c.follow && c.night == "glass"))
    }
    SetThemes(SetThemesState(
        choice = choice,
        reroll = reroll,
        seed = seed,
        hidden = hidden.split(',').filter { it.isNotBlank() }.toSet(),
        onPick = { id -> scope.launch { store(ThemeChoice.pick(choice, id).first) } },
        // :212 turning following off keeps the day theme for all the time
        onFollow = { on -> scope.launch { store(choice.copy(follow = on, night = choice.day)) } },
        onReroll = { mode -> scope.launch { prefs.keyboard.spotReroll.set(mode) } },
        // :216 the canvas's next coat: (seed x 7 + 13) mod 97, never 0
        onShuffle = { scope.launch { prefs.keyboard.spotSeed.set(((seed * 7 + 13) % 97).takeIf { it != 0 } ?: 3) } },
        onHidePack = { id, hide ->
            val set = hidden.split(',').filter { it.isNotBlank() }.toMutableSet()
            if (hide) set.add(id) else set.remove(id)
            scope.launch { prefs.keyboard.hiddenThemePacks.set(set.sorted().joinToString(",")) }
        },
        onEditPhoto = { navController.navigate(Routes.Settings.Theme) },
        onBack = { navController.popBackStack() },
    ))
}
