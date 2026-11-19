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
    val appFollows by prefs.other.settingsFollowKeyboard.collectAsState()
    val retPref by prefs.keyboard.retColour.collectAsState()
    val reroll by prefs.keyboard.spotReroll.collectAsState()
    val seed by prefs.keyboard.spotSeed.collectAsState()
    val hidden by prefs.keyboard.hiddenThemePacks.collectAsState()
    val choice = ThemeChoice.Choice(day, night, follow)
    // the user's saved themes, in their packs (UserThemes); reloaded after a change
    val context = androidx.compose.ui.platform.LocalContext.current
    val userTheme by prefs.keyboard.userTheme.collectAsState()
    val stamp by prefs.keyboard.glassPhotoStamp.collectAsState()
    val userPacks by androidx.compose.runtime.produceState(emptyList<io.github.sillypuppyy.dogboard.design.UserThemes.Pack>(), stamp, userTheme) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.sillypuppyy.dogboard.design.UserThemes.migrate(context, prefs)
            io.github.sillypuppyy.dogboard.design.UserThemes.packs(context)
        }
    }
    suspend fun store(c: ThemeChoice.Choice) {
        prefs.keyboard.themeDay.set(c.day)
        prefs.keyboard.themeNight.set(c.night)
        prefs.keyboard.themeFollow.set(c.follow)
        // the Photo theme is the glass look (its photo and settings live on the photo page)
        prefs.keyboard.glassEnabled.set(c.day == "glass" || (c.follow && c.night == "glass"))
    }
    // "From a photo": a new saved theme from a picked photo, matched to it, then its editor
    val pickNewPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val made = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val lum = io.github.sillypuppyy.dogboard.design.GlassPhoto.save(context, uri) ?: return@withContext null
                prefs.keyboard.glassPhotoLuminance.set((lum * 1000).toInt())
                io.github.sillypuppyy.dogboard.design.GlassPhoto.analysis(context)?.let { a ->
                    val l = io.github.sillypuppyy.dogboard.design.PhotoReadability.auto(a)
                    prefs.keyboard.glassDim.set((l.dim * 100).toInt()); prefs.keyboard.glassKeyOpacity.set((l.tint * 100).toInt())
                    prefs.keyboard.glassBlur.set(l.blur.toInt()); prefs.keyboard.glassLabels.set(l.labels)
                    prefs.keyboard.glassHalo.set(l.halo); prefs.keyboard.glassAccent.set(l.accent)
                }
                val n = io.github.sillypuppyy.dogboard.design.UserThemes.packs(context).sumOf { it.themes.size } + 1
                io.github.sillypuppyy.dogboard.design.UserThemes.createPhoto(context, prefs, "Photo $n")
            } ?: return@launch
            io.github.sillypuppyy.dogboard.design.UserThemes.apply(context, prefs, made)
            store(ThemeChoice.pick(choice, "glass").first)
            navController.navigate(Routes.Settings.Photo)
        }
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
        appFollows = appFollows,
        retPref = retPref,
        onRet = { id, always -> scope.launch { prefs.keyboard.retColour.set(io.github.sillypuppyy.dogboard.design.ThemeChoice.withRet(retPref, id, always)) } },
        onAppFollows = { on -> scope.launch { prefs.other.settingsFollowKeyboard.set(on) } },
        // :216 the canvas's next coat: (seed x 7 + 13) mod 97, never 0
        onShuffle = { scope.launch { prefs.keyboard.spotSeed.set(((seed * 7 + 13) % 97).takeIf { it != 0 } ?: 3) } },
        onHidePack = { id, hide ->
            val set = hidden.split(',').filter { it.isNotBlank() }.toMutableSet()
            if (hide) set.add(id) else set.remove(id)
            scope.launch { prefs.keyboard.hiddenThemePacks.set(set.sorted().joinToString(",")) }
        },
        onEditPhoto = { pickNewPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        userPacks = userPacks,
        userTheme = userTheme.takeIf { (day == "glass" || (follow && night == "glass")) },
        onPickUser = { id ->
            scope.launch {
                if (io.github.sillypuppyy.dogboard.design.UserThemes.apply(context, prefs, id)) store(ThemeChoice.pick(choice, "glass").first)
            }
        },
        onEditUser = { id -> scope.launch { io.github.sillypuppyy.dogboard.design.UserThemes.apply(context, prefs, id); navController.navigate(Routes.Settings.Photo) } },
        onBack = { navController.popBackStack() },
    ))
}
