package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    val fixedNight by prefs.keyboard.themeFixedNight.collectAsState()
    val appColours by prefs.other.settingsColours.collectAsState()
    val retPref by prefs.keyboard.retColour.collectAsState()
    val reroll by prefs.keyboard.spotReroll.collectAsState()
    val seed by prefs.keyboard.spotSeed.collectAsState()
    val hidden by prefs.keyboard.hiddenThemePacks.collectAsState()
    val choice = ThemeChoice.Choice(day, night, follow, fixedNight)
    // the user's saved themes, in their packs (UserThemes); reloaded after a change
    val context = androidx.compose.ui.platform.LocalContext.current
    val userTheme by prefs.keyboard.userTheme.collectAsState()
    val stamp by prefs.keyboard.glassPhotoStamp.collectAsState()
    // bumped after a pack or theme changes, so the page reads the packs again
    var version by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // the pack a new photo theme goes to (null: the default pack), and the open dialog: (kind, id, name)
    var targetPack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var managing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var dialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Triple<String, String, String>?>(null) }
    val userPacks by androidx.compose.runtime.produceState(emptyList<io.github.sillypuppyy.dogboard.design.UserThemes.Pack>(), stamp, userTheme, version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.sillypuppyy.dogboard.design.UserThemes.migrate(context, prefs)
            io.github.sillypuppyy.dogboard.design.UserThemes.installPhotos(context)
            // the Photo theme's mode (light, dark or a pair) from its versions, for picking and the tiles
            if (prefs.keyboard.glassPhotoStamp.get() != 0L) io.github.sillypuppyy.dogboard.design.PhotoSlot.versions(context, prefs)
            io.github.sillypuppyy.dogboard.design.UserThemes.packs(context)
        }
        // the Themes page's messages name a gradient by its saved name
        ThemeChoice.userNames = value.flatMap { it.themes }.mapNotNull { t -> t.spec?.let { it to t.name } }.toMap()
        // and the photo slot by the photo theme in it
        ThemeChoice.glassName = prefs.keyboard.userTheme.get().let { id -> value.flatMap { it.themes }.firstOrNull { it.id == id && it.kind == "photo" }?.name }
    }
    suspend fun store(c: ThemeChoice.Choice) {
        prefs.keyboard.themeDay.set(c.day)
        prefs.keyboard.themeNight.set(c.night)
        prefs.keyboard.themeFollow.set(c.follow)
        prefs.keyboard.themeFixedNight.set(c.fixedNight)
        // the Photo theme is the glass look (its photo and settings live on the photo page)
        prefs.keyboard.glassEnabled.set(c.day == "glass" || c.night == "glass")
    }
    // a shared .dogtheme pack: added as a new pack; a note says how it went
    val importPack = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { io.github.sillypuppyy.dogboard.design.UserThemes.importPack(context, it) } }.getOrNull()
            }
            android.widget.Toast.makeText(context, if (name != null) "Added the pack “$name”" else "That file isn't a DogBoard theme pack", android.widget.Toast.LENGTH_LONG).show()
            version++
        }
    }
    // "From a photo": a new saved theme from a picked photo, matched to it, then its editor
    val pickNewPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val made = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val lum = io.github.sillypuppyy.dogboard.design.GlassPhoto.save(context, uri) ?: return@withContext null
                prefs.keyboard.glassPhotoLuminance.set((lum * 1000).toInt())
                // a new photo theme starts with both versions matched to the photo; no dark photo of its own
                io.github.sillypuppyy.dogboard.design.GlassPhoto.file(context, true).delete()
                io.github.sillypuppyy.dogboard.design.GlassPhoto.analysisFile(context, true).delete()
                io.github.sillypuppyy.dogboard.design.GlassPhoto.analysis(context)?.let { a ->
                    prefs.keyboard.glassLooks.set(io.github.sillypuppyy.dogboard.design.PhotoVersions.auto(a).encode())
                }
                val n = io.github.sillypuppyy.dogboard.design.UserThemes.packs(context).sumOf { it.themes.size } + 1
                io.github.sillypuppyy.dogboard.design.UserThemes.createPhoto(context, prefs, "Photo $n", targetPack)
            } ?: return@launch
            io.github.sillypuppyy.dogboard.design.UserThemes.apply(context, prefs, made)
            store(ThemeChoice.pick(choice, "glass").first)
            navController.navigate(Routes.Settings.Photo)
        }
    }
    // a theme that comes with a sound offers it once picked: (theme name, sound style); the person's own sound stays
    // unless they say yes
    var soundOffer by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null) }
    fun offerSound(name: String, sound: String?) {
        val fb = prefs.inputFeedback
        if (sound != null && (sound != fb.audioStyle.get() || !fb.audioEnabled.get())) soundOffer = name to sound
    }
    val themesState = SetThemesState(
        choice = choice,
        reroll = reroll,
        seed = seed,
        hidden = hidden.split(',').filter { it.isNotBlank() }.toSet(),
        onPick = { id ->
            scope.launch { store(ThemeChoice.pick(choice, id).first) }
            offerSound(ThemeChoice.theme(id).name, io.github.sillypuppyy.dogboard.design.KbThemes.SOUND_OF[id])
        },
        // :212 turning following off keeps the day theme for all the time
        // follow the system, or always one slot; both slots are kept either way
        onFollow = { mode -> scope.launch { store(choice.copy(follow = mode == "system", fixedNight = mode == "night")) } },
        onReroll = { mode -> scope.launch { prefs.keyboard.spotReroll.set(mode) } },
        appColours = appColours,
        retPref = retPref,
        onRet = { id, always -> scope.launch { prefs.keyboard.retColour.set(io.github.sillypuppyy.dogboard.design.ThemeChoice.withRet(retPref, id, always)) } },
        onAppColours = { v -> scope.launch { prefs.other.settingsColours.set(v) } },
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
                // a gradient is drawn from its id; a photo goes through the photo slot
                val spec = userPacks.flatMap { it.themes }.firstOrNull { it.id == id }?.spec
                if (spec != null) {
                    store(ThemeChoice.pick(choice, spec).first); prefs.keyboard.userTheme.set(id)
                    io.github.sillypuppyy.dogboard.design.ThemeModel.decode(spec)?.let { offerSound(it.name, it.sound) }
                }
                else if (io.github.sillypuppyy.dogboard.design.UserThemes.apply(context, prefs, id)) store(ThemeChoice.pick(choice, "glass").first)
            }
        },
        onEditUser = { tid ->
            scope.launch {
                // a theme from a shared pack is read-only: editing it makes your own copy
                val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (io.github.sillypuppyy.dogboard.design.UserThemes.isShared(context, tid)) io.github.sillypuppyy.dogboard.design.UserThemes.copyToMine(context, tid) ?: tid else tid
                }
                val spec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.sillypuppyy.dogboard.design.UserThemes.theme(context, id)?.spec }
                if (id != tid) { version++; android.widget.Toast.makeText(context, "Copied to my themes to edit", android.widget.Toast.LENGTH_SHORT).show() }
                // a theme made in the theme editor opens there; an older gradient in the gradient editor
                if (spec != null && io.github.sillypuppyy.dogboard.design.ThemeModel.isSpec(spec)) navController.navigate(Routes.Settings.ThemeEditor(id = id))
                else if (spec != null) navController.navigate(Routes.Settings.Gradient(id = id))
                else { io.github.sillypuppyy.dogboard.design.UserThemes.apply(context, prefs, id); navController.navigate(Routes.Settings.Photo) }
            }
        },
        // new themes are made in the theme editor (ThemeEditor.dc.html: one editor for every background)
        onNewGradientIn = { packId -> navController.navigate(Routes.Settings.ThemeEditor(start = "gradient", pack = packId.orEmpty())) },
        onNewSolidIn = { packId -> navController.navigate(Routes.Settings.ThemeEditor(start = "solid", pack = packId.orEmpty())) },
        inUse = if (follow) setOf(day, night) else setOf(day),
        onNewPhotoIn = { packId ->
            targetPack = packId
            pickNewPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onNewPack = { dialog = Triple("newPack", "", "") },
        onImportPack = { importPack.launch(arrayOf("*/*")) },
        onPackAction = { id, a ->
            val name = userPacks.firstOrNull { it.id == id }?.name.orEmpty()
            when (a) {
                "rename" -> dialog = Triple("renamePack", id, name)
                "delete" -> dialog = Triple("deletePack", id, name)
                "share" -> scope.launch {
                    val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.sillypuppyy.dogboard.design.UserThemes.exportPack(context, id) } ?: return@launch
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider.file", f)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri).putExtra(android.content.Intent.EXTRA_SUBJECT, "$name (DogBoard themes)")
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(android.content.Intent.createChooser(send, "Share $name"))
                }
                "hide", "show" -> scope.launch(kotlinx.coroutines.Dispatchers.IO) { io.github.sillypuppyy.dogboard.design.UserThemes.setPackHidden(context, id, a == "hide"); version++ }
                "copy" -> scope.launch(kotlinx.coroutines.Dispatchers.IO) { io.github.sillypuppyy.dogboard.design.UserThemes.copyPack(context, id); version++ }
                else -> scope.launch(kotlinx.coroutines.Dispatchers.IO) { io.github.sillypuppyy.dogboard.design.UserThemes.movePack(context, id, if (a == "up") -1 else 1); version++ }
            }
        },
        onThemeAction = { id, a ->
            val name = userPacks.flatMap { it.themes }.firstOrNull { it.id == id }?.name.orEmpty()
            when {
                a == "rename" -> dialog = Triple("renameTheme", id, name)
                a == "delete" -> dialog = Triple("deleteTheme", id, name)
                a == "copy" -> scope.launch(kotlinx.coroutines.Dispatchers.IO) { io.github.sillypuppyy.dogboard.design.UserThemes.copyToMine(context, id); version++ }
                a.startsWith("move:") -> scope.launch(kotlinx.coroutines.Dispatchers.IO) { io.github.sillypuppyy.dogboard.design.UserThemes.moveTheme(context, id, a.removePrefix("move:")); version++ }
            }
        },
        onCopyEdit = { bid -> navController.navigate(Routes.Settings.ThemeEditor(start = "copy:$bid")) },
        onBack = { navController.popBackStack() },
        onManagePacks = { managing = true },
    )
    // Manage packs opens over this page: the same pack actions and dialogs; Back returns here
    if (managing) {
        androidx.activity.compose.BackHandler { managing = false }
        io.github.sillypuppyy.dogboard.design.settings.SetPacks(
            userPacks, hidden.split(',').filter { it.isNotBlank() }.toSet(),
            onPackAction = { id, a -> themesState.onPackAction(id, a) },
            onBuiltIn = { id, hide -> themesState.onHidePack(id, hide) },
            onNewPack = themesState.onNewPack, onImport = themesState.onImportPack, onBack = { managing = false },
        )
    } else SetThemes(themesState)
    soundOffer?.let { (name, sound) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { soundOffer = null },
            title = { androidx.compose.material3.Text("Use $name’s sound?") },
            text = { androidx.compose.material3.Text("$name comes with its own key sound. Your sound settings stay as they are unless you switch.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    scope.launch { prefs.inputFeedback.audioStyle.set(sound); prefs.inputFeedback.audioEnabled.set(true) }
                    soundOffer = null
                }) { androidx.compose.material3.Text("Use its sound") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { soundOffer = null }) { androidx.compose.material3.Text("Keep mine") } },
        )
    }
    dialog?.let { (kind, id, name) ->
        val close = { dialog = null }
        if (kind == "newPack" || kind.startsWith("rename")) {
            var text by androidx.compose.runtime.remember(kind, id) { androidx.compose.runtime.mutableStateOf(name) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = close,
                title = { androidx.compose.material3.Text(when (kind) { "newPack" -> "New pack"; "renamePack" -> "Rename pack"; else -> "Rename theme" }) },
                text = { androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true, label = { androidx.compose.material3.Text("Name") }) },
                confirmButton = {
                    androidx.compose.material3.TextButton({
                        val n = text.trim()
                        close()
                        if (n.isNotEmpty()) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            when (kind) {
                                "newPack" -> io.github.sillypuppyy.dogboard.design.UserThemes.newPack(context, n)
                                "renamePack" -> io.github.sillypuppyy.dogboard.design.UserThemes.renamePack(context, id, n)
                                else -> io.github.sillypuppyy.dogboard.design.UserThemes.renameTheme(context, id, n)
                            }
                            version++
                        }
                    }) { androidx.compose.material3.Text(if (kind == "newPack") "Create" else "Rename") }
                },
                dismissButton = { androidx.compose.material3.TextButton(close) { androidx.compose.material3.Text("Cancel") } },
            )
        } else {
            val pack = kind == "deletePack"
            androidx.compose.material3.AlertDialog(
                onDismissRequest = close,
                title = { androidx.compose.material3.Text(if (pack) "Delete the pack “$name”?" else "Delete “$name”?") },
                text = { androidx.compose.material3.Text(if (pack) "Its themes are deleted with it." else "The theme and its photo are deleted.") },
                confirmButton = {
                    androidx.compose.material3.TextButton({
                        close()
                        scope.launch {
                            val gone = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                if (pack) io.github.sillypuppyy.dogboard.design.UserThemes.deletePack(context, id)
                                else listOf(id).also { io.github.sillypuppyy.dogboard.design.UserThemes.deleteTheme(context, id) }
                            }
                            // a deleted theme in use: the photo slot keeps drawing it, but no theme is linked to it any more
                            if (userTheme in gone) prefs.keyboard.userTheme.set("")
                            version++
                        }
                    }) { androidx.compose.material3.Text("Delete") }
                },
                dismissButton = { androidx.compose.material3.TextButton(close) { androidx.compose.material3.Text("Cancel") } },
            )
        }
    }

}
