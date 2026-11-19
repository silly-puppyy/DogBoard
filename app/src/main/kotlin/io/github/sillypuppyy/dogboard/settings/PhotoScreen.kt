package io.github.sillypuppyy.dogboard.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.GlassPhoto
import io.github.sillypuppyy.dogboard.design.PhotoReadability
import io.github.sillypuppyy.dogboard.design.settings.SetPhoto
import io.github.sillypuppyy.dogboard.design.settings.SetPhotoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Look › Themes › Photo theme, live (design SetPhoto.dc.html): saved as it changes. */
@Composable
fun PhotoScreen() {
    val prefs by FlorisPreferenceStore
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    io.github.sillypuppyy.dogboard.design.MaterialYouTheme.Remember()
    val stamp by prefs.keyboard.glassPhotoStamp.collectAsState()
    val dim by prefs.keyboard.glassDim.collectAsState()
    val blur by prefs.keyboard.glassBlur.collectAsState()
    val kop by prefs.keyboard.glassKeyOpacity.collectAsState()
    val labels by prefs.keyboard.glassLabels.collectAsState()
    val halo by prefs.keyboard.glassHalo.collectAsState()
    val accent by prefs.keyboard.glassAccent.collectAsState()
    val pos by prefs.keyboard.glassPhotoPos.collectAsState()
    val day by prefs.keyboard.themeDay.collectAsState()
    val night by prefs.keyboard.themeNight.collectAsState()
    val photo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, stamp) { value = withContext(Dispatchers.IO) { GlassPhoto.load(context)?.asImageBitmap() } }
    val analysis by produceState<GlassPhoto.Analysis?>(null, stamp) { value = withContext(Dispatchers.IO) { GlassPhoto.analysis(context) } }

    suspend fun store(l: PhotoReadability.Look) {
        prefs.keyboard.glassDim.set((l.dim * 100).toInt())
        prefs.keyboard.glassKeyOpacity.set((l.tint * 100).toInt())
        prefs.keyboard.glassBlur.set(l.blur.toInt())
        prefs.keyboard.glassLabels.set(l.labels)
        prefs.keyboard.glassHalo.set(l.halo)
        prefs.keyboard.glassAccent.set(l.accent)
        // the saved theme being edited keeps the change (UserThemes)
        withContext(Dispatchers.IO) { io.github.sillypuppyy.dogboard.design.UserThemes.saveBack(context, prefs) }
    }
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val lum = GlassPhoto.save(context, uri) ?: return@launch
            prefs.keyboard.glassPhotoLuminance.set((lum * 1000).toInt())
            // :181 a new photo starts from the settings matched to it
            GlassPhoto.analysis(context)?.let { store(PhotoReadability.auto(it)) }
            prefs.keyboard.glassPhotoStamp.set(System.currentTimeMillis())
            prefs.keyboard.glassEnabled.set(true)
            // a new photo for the theme being edited, or a first saved theme
            if (prefs.keyboard.userTheme.get().isEmpty()) {
                io.github.sillypuppyy.dogboard.design.UserThemes.createPhoto(context, prefs, "Photo")?.let { prefs.keyboard.userTheme.set(it) }
            } else io.github.sillypuppyy.dogboard.design.UserThemes.saveBack(context, prefs, photoToo = true)
        }
    }
    SetPhoto(SetPhotoState(
        photo = photo,
        analysis = analysis,
        look = PhotoReadability.Look(dim / 100.0, kop / 100.0, blur.toFloat(), labels, halo, accent),
        pos = pos,
        use = when { day == "glass" && night == "glass" -> "both"; night == "glass" -> "night"; day == "glass" -> "day"; else -> "both" },
        onLook = { l -> scope.launch { store(l) } },
        onPos = { p -> scope.launch { prefs.keyboard.glassPhotoPos.set(p); withContext(Dispatchers.IO) { io.github.sillypuppyy.dogboard.design.UserThemes.saveBack(context, prefs) } } },
        onUse = { u ->
            scope.launch {
                if (u != "night") prefs.keyboard.themeDay.set("glass") else if (day == "glass") prefs.keyboard.themeDay.set("dogboard")
                if (u != "day") prefs.keyboard.themeNight.set("glass") else if (night == "glass") prefs.keyboard.themeNight.set("dogboard")
                if (u != "both") prefs.keyboard.themeFollow.set(true)
                prefs.keyboard.glassEnabled.set(true)
            }
        },
        onPickPhoto = {
            pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onBack = { navController.popBackStack() },
    ))
}
