package io.github.sillypuppyy.dogboard.design

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The live keyboard's design theme: DogBoard's, or Glass over the user's photo (Kb:18-23, 400-406). */
data class LiveTheme(
    val glass: Boolean,
    /** The Themes page's choice resolved for now: the theme id and whether its dark version is drawn. */
    val themeId: String = "dogboard",
    val themeDark: Boolean = false,
    /** Dalmatian's coat for this keyboard (0: the theme's own). */
    val spotSeed: Int = 0,
    val dim: Float = 0.35f,
    val blur: Float = 12f,
    val keyOpacity: Float = 0.3f,
    val photoLuminance: Float = 0.3f,
    val photoStamp: Long = 0L,
    val glassFg: String = "auto",
    val halo: Boolean = true,
    val actColor: String? = null,
    val actFg: String? = null,
    /** "center top", "center" or "center bottom": which part of the photo shows. */
    val photoPos: String = "center",
) {
    /** [p] with the Glass tokens when Glass is on; the photo itself is drawn by [LiveGlassBackdrop]. */
    fun apply(p: KbProps): KbProps = if (!glass) p.copy(theme = themeId, dark = themeDark, spotSeed = spotSeed) else p.copy(
        theme = "glass", dim = dim, blur = blur, keyOpacity = keyOpacity, photoLuminance = photoLuminance, backdrop = false,
        glassFg = glassFg, halo = halo, actColor = actColor, actFg = actFg,
    )
}

@Composable
fun liveTheme(): LiveTheme {
    val prefs by FlorisPreferenceStore
    val enabled by prefs.keyboard.glassEnabled.collectAsState()
    val stamp by prefs.keyboard.glassPhotoStamp.collectAsState()
    val dim by prefs.keyboard.glassDim.collectAsState()
    val blur by prefs.keyboard.glassBlur.collectAsState()
    val kop by prefs.keyboard.glassKeyOpacity.collectAsState()
    val lum by prefs.keyboard.glassPhotoLuminance.collectAsState()
    val day by prefs.keyboard.themeDay.collectAsState()
    val night by prefs.keyboard.themeNight.collectAsState()
    val follow by prefs.keyboard.themeFollow.collectAsState()
    val reroll by prefs.keyboard.spotReroll.collectAsState()
    val keptSeed by prefs.keyboard.spotSeed.collectAsState()
    val (id, dark) = ThemeChoice.resolve(ThemeChoice.Choice(day, night, follow), liveDark())
    // Dalmatian "every time it opens": a new coat per keyboard view, else the kept one
    val openSeed = androidx.compose.runtime.remember { (1..96).random() }
    // the Photo theme draws the glass photo when one is set, else the default look
    val glass = (id == "glass" || enabled) && stamp != 0L
    val labels by prefs.keyboard.glassLabels.collectAsState()
    val halo by prefs.keyboard.glassHalo.collectAsState()
    val accent by prefs.keyboard.glassAccent.collectAsState()
    val pos by prefs.keyboard.glassPhotoPos.collectAsState()
    val context = LocalContext.current
    val analysis by produceState<GlassPhoto.Analysis?>(null, stamp) { value = withContext(Dispatchers.IO) { GlassPhoto.analysis(context) } }
    val acc = analysis?.accents?.let { it.getOrNull(accent) ?: it.firstOrNull() }
    return LiveTheme(glass, if (id == "glass" && stamp == 0L) "dogboard" else id, dark, if (reroll == "open") openSeed else keptSeed,
        dim / 100f, blur.toFloat(), kop / 100f, lum / 1000f, stamp, labels, halo, acc?.first, acc?.second, pos)
}

/**
 * Kb:18-23: the photo behind the whole keyboard (bar, keys and panels share one picture): cropped
 * to fill, blurred by the theme's blur, dimmed by its dim. Nothing is drawn when Glass is off.
 */
@Composable
fun LiveGlassBackdrop(theme: LiveTheme, modifier: Modifier = Modifier) {
    if (!theme.glass) return
    val context = LocalContext.current
    val photo by produceState<Bitmap?>(null, theme.photoStamp) {
        value = withContext(Dispatchers.IO) { GlassPhoto.load(context) }
    }
    Box(modifier.fillMaxSize().clipToBounds()) {
        photo?.let { b ->
            Image(
                b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                alignment = when (theme.photoPos) { "center top" -> androidx.compose.ui.Alignment.TopCenter; "center bottom" -> androidx.compose.ui.Alignment.BottomCenter; else -> androidx.compose.ui.Alignment.Center },
                modifier = Modifier.fillMaxSize().blur(theme.blur.dp, BlurredEdgeTreatment.Rectangle),
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0f, 0f, 0f, theme.dim)))
    }
}
