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
import androidx.compose.runtime.collectAsState
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
    /** The white wash of a photo brightened past 100 %. */
    val wash: Float = 0f,
    /** The photo's saturation and contrast (PhotoReadability.Look). */
    val photoSat: Float = 1f,
    val photoContrast: Float = 1f,
    /** The dark version draws its own photo (glass-dark.jpg). */
    val photoDark: Boolean = false,
    val photoStamp: Long = 0L,
    val glassFg: String = "auto",
    val halo: Boolean = true,
    val actColor: String? = null,
    val actFg: String? = null,
    /** "center top", "center" or "center bottom": which part of the photo shows. */
    val photoPos: String = "center",
    /** Settings › Themes › Return key: the accent colour on plain fields too. */
    val retAlways: Boolean = false,
) {
    /** [p] with the Glass tokens when Glass is on; the photo itself is drawn by [LiveGlassBackdrop]. */
    fun apply(p: KbProps): KbProps = if (!glass) p.copy(theme = themeId, dark = themeDark, spotSeed = spotSeed, retAlways = retAlways) else p.copy(retAlways = retAlways, 
        theme = "glass", dim = dim, wash = wash, blur = blur, keyOpacity = keyOpacity, photoLuminance = photoLuminance, backdrop = false,
        glassFg = glassFg, halo = halo, actColor = actColor, actFg = actFg,
    )
}

@Composable
fun liveTheme(): LiveTheme {
    val prefs by FlorisPreferenceStore
    val enabled by prefs.keyboard.glassEnabled.collectAsState()
    val stamp by prefs.keyboard.glassPhotoStamp.collectAsState()
    val looks by prefs.keyboard.glassLooks.collectAsState()
    val lum by prefs.keyboard.glassPhotoLuminance.collectAsState()
    val day by prefs.keyboard.themeDay.collectAsState()
    val night by prefs.keyboard.themeNight.collectAsState()
    val follow by prefs.keyboard.themeFollow.collectAsState()
    val reroll by prefs.keyboard.spotReroll.collectAsState()
    val keptSeed by prefs.keyboard.spotSeed.collectAsState()
    val retPref by prefs.keyboard.retColour.collectAsState()
    val context = LocalContext.current
    // the photo theme's versions (bright, dark or both); they also set its day / night mode for resolve()
    val versions by produceState<PhotoVersions?>(null, stamp, looks) { value = withContext(Dispatchers.IO) { PhotoSlot.versions(context, prefs) } }
    val (id, dark) = ThemeChoice.resolve(ThemeChoice.Choice(day, night, follow), liveDark())
    // Dalmatian "every time it opens": a new coat each time the keyboard is shown, else the kept one (the view
    // lives on between showings, so remembering per view gave one coat for good; user 2026-11-19)
    val shown by io.github.sillypuppyy.dogboard.ime.LiveUi.shown.collectAsState()
    val openSeed = androidx.compose.runtime.remember(shown) { (1..96).random() }
    // the Photo theme draws the glass photo when one is set, else the default look
    val glass = (id == "glass" || enabled) && stamp != 0L
    val pos by prefs.keyboard.glassPhotoPos.collectAsState()
    val v = versions ?: PhotoVersions()
    // which version: both follow the day / night choice, one version is what it is
    val useDark = if (v.hasBright && v.hasDark) dark else v.hasDark
    val look = v.look(useDark)
    val photoDark = useDark && v.darkPhoto
    val analysis by produceState<GlassPhoto.Analysis?>(null, stamp, photoDark) { value = withContext(Dispatchers.IO) { GlassPhoto.analysis(context, photoDark) } }
    val acc = PhotoReadability.accent(look, analysis?.accents)
    return LiveTheme(glass, if (id == "glass" && stamp == 0L) "dogboard" else id, dark, if (reroll == "open") openSeed else keptSeed,
        look.dim.toFloat(), look.blur, look.tint.toFloat(), (if (photoDark) v.darkLuminance.toFloat() else lum / 1000f),
        wash = look.wash.toFloat(), photoSat = look.sat.toFloat(), photoContrast = look.contrast.toFloat(), photoDark = photoDark, photoStamp = stamp, glassFg = look.labels, halo = look.halo,
        actColor = acc?.first, actFg = acc?.second, photoPos = pos,
        retAlways = ThemeChoice.retAlways(retPref, if (glass) "glass" else id))
}

/**
 * Kb:18-23: the photo behind the whole keyboard (bar, keys and panels share one picture): cropped
 * to fill, blurred by the theme's blur, dimmed by its dim. Nothing is drawn when Glass is off.
 */
@Composable
fun LiveGlassBackdrop(theme: LiveTheme, modifier: Modifier = Modifier) {
    if (!theme.glass) return
    val context = LocalContext.current
    val photo by produceState<Bitmap?>(null, theme.photoStamp, theme.photoDark) {
        value = withContext(Dispatchers.IO) { GlassPhoto.load(context, theme.photoDark) }
    }
    Box(modifier.fillMaxSize().clipToBounds()) {
        photo?.let { b ->
            Image(
                b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                alignment = when (theme.photoPos) { "center top" -> androidx.compose.ui.Alignment.TopCenter; "center bottom" -> androidx.compose.ui.Alignment.BottomCenter; else -> androidx.compose.ui.Alignment.Center },
                modifier = Modifier.fillMaxSize().blur(theme.blur.dp, BlurredEdgeTreatment.Rectangle),
                colorFilter = photoFilter(theme.photoSat, theme.photoContrast),
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0f, 0f, 0f, theme.dim)))
        if (theme.wash > 0f) Box(Modifier.fillMaxSize().background(Color(1f, 1f, 1f, theme.wash)))
    }
}

/** The photo's saturation and contrast as a colour filter (null when both are as shot). */
fun photoFilter(sat: Float, contrast: Float): androidx.compose.ui.graphics.ColorFilter? {
    if (sat == 1f && contrast == 1f) return null
    val m = androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(sat) }
    val t = 0.5f * 255 * (1 - contrast)
    val c = androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f))
    m.timesAssign(c)
    return androidx.compose.ui.graphics.ColorFilter.colorMatrix(m)
}
