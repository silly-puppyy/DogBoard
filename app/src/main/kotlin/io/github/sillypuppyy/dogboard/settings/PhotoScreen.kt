package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.GlassPhoto
import io.github.sillypuppyy.dogboard.design.PhotoReadability
import io.github.sillypuppyy.dogboard.design.PhotoSlot
import io.github.sillypuppyy.dogboard.design.PhotoVersions
import io.github.sillypuppyy.dogboard.design.ThemeChoice
import io.github.sillypuppyy.dogboard.design.UserThemes
import io.github.sillypuppyy.dogboard.design.settings.SetPhoto
import io.github.sillypuppyy.dogboard.design.settings.SetPhotoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Look › Themes › Photo theme, live (design SetPhoto.dc.html): saved as it changes. A photo theme has a
 * bright version, a dark one, or both, each its own look; the dark one may have its own photo.
 */
@Composable
fun PhotoScreen() {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember()
    val stamp by prefs.keyboard.glassPhotoStamp.collectAsState()
    val looks by prefs.keyboard.glassLooks.collectAsState()
    val pos by prefs.keyboard.glassPhotoPos.collectAsState()
    var tabDark by remember { mutableStateOf(false) }
    val photo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, stamp) { value = withContext(Dispatchers.IO) { GlassPhoto.load(context)?.asImageBitmap() } }
    val analysis by produceState<GlassPhoto.Analysis?>(null, stamp) { value = withContext(Dispatchers.IO) { GlassPhoto.analysis(context) } }
    val darkPhoto by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, stamp) { value = withContext(Dispatchers.IO) { GlassPhoto.load(context, true)?.asImageBitmap() } }
    val darkAnalysis by produceState<GlassPhoto.Analysis?>(null, stamp) { value = withContext(Dispatchers.IO) { GlassPhoto.analysis(context, true) } }
    val versions by produceState<PhotoVersions?>(null, stamp, looks) { value = withContext(Dispatchers.IO) { PhotoSlot.versions(context, prefs) } }

    /** The versions into the slot and the saved theme being edited. */
    suspend fun store(v: PhotoVersions, photoToo: Boolean = false) {
        ThemeChoice.glassMode = v.mode
        prefs.keyboard.glassLooks.set(v.encode())
        withContext(Dispatchers.IO) { UserThemes.saveBack(context, prefs, photoToo) }
    }

    /** The Photo theme back in the day / night slots its versions allow (bright only: day; dark only: night; both: both). */
    suspend fun reslot(v: PhotoVersions) {
        val c = ThemeChoice.Choice(prefs.keyboard.themeDay.get(), prefs.keyboard.themeNight.get(), prefs.keyboard.themeFollow.get())
        if (c.day != "glass" && c.night != "glass") return
        val cleared = c.copy(day = if (c.day == "glass") "dogboard" else c.day, night = if (c.night == "glass") "dogboard" else c.night)
        ThemeChoice.glassMode = v.mode
        val next = ThemeChoice.pick(cleared, "glass").first
        prefs.keyboard.themeDay.set(next.day); prefs.keyboard.themeNight.set(next.night)
        prefs.keyboard.glassEnabled.set(true)
    }

    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val lum = GlassPhoto.save(context, uri) ?: return@launch
            prefs.keyboard.glassPhotoLuminance.set((lum * 1000).toInt())
            // :181 a new photo starts from the settings matched to it, both versions
            val old = versions ?: PhotoVersions()
            GlassPhoto.analysis(context)?.let { a ->
                val m = PhotoVersions.auto(a, if (old.darkPhoto) GlassPhoto.analysis(context, true) else null)
                prefs.keyboard.glassLooks.set(m.copy(versions = old.versions, darkPhoto = old.darkPhoto, darkLuminance = old.darkLuminance).encode())
            }
            prefs.keyboard.glassPhotoStamp.set(System.currentTimeMillis())
            prefs.keyboard.glassEnabled.set(true)
            // a new photo for the theme being edited, or a first saved theme
            if (prefs.keyboard.userTheme.get().isEmpty()) {
                UserThemes.createPhoto(context, prefs, "Photo")?.let { prefs.keyboard.userTheme.set(it) }
            } else UserThemes.saveBack(context, prefs, photoToo = true)
        }
    }
    // the dark version's own photo
    val pickDarkPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val lum = GlassPhoto.save(context, uri, dark = true) ?: return@launch
            val a = GlassPhoto.analysis(context, true) ?: return@launch
            val v = (versions ?: PhotoVersions()).copy(darkPhoto = true, darkLuminance = lum, dark = PhotoReadability.auto(a, true))
            store(v, photoToo = true)
            prefs.keyboard.glassPhotoStamp.set(System.currentTimeMillis())
        }
    }
    val v = versions ?: return
    SetPhoto(SetPhotoState(
        photo = photo, analysis = analysis, darkPhoto = darkPhoto, darkAnalysis = darkAnalysis,
        versions = v, tabDark = tabDark, pos = pos,
        onVersions = { which ->
            scope.launch {
                // a version added is matched to its photo; one dropped keeps its settings for coming back
                val nv = v.copy(versions = which)
                store(nv); reslot(nv)
                tabDark = which == "dark"
            }
        },
        onTab = { tabDark = it },
        onLook = { l -> scope.launch { store(v.with(if (v.hasBright && v.hasDark) tabDark else v.hasDark, l)) } },
        onPos = { p -> scope.launch { prefs.keyboard.glassPhotoPos.set(p); withContext(Dispatchers.IO) { UserThemes.saveBack(context, prefs) } } },
        onPickPhoto = {
            pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onPickDarkPhoto = {
            pickDarkPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onDarkSamePhoto = {
            scope.launch(Dispatchers.IO) {
                GlassPhoto.file(context, true).delete(); GlassPhoto.analysisFile(context, true).delete()
                val nv = v.copy(darkPhoto = false, dark = analysis?.let { PhotoReadability.auto(it, true) } ?: v.dark)
                store(nv, photoToo = true)
                prefs.keyboard.glassPhotoStamp.set(System.currentTimeMillis())
            }
        },
        onBack = { navController.popBackStack() },
    ))
}
