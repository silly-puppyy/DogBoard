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
    val dim: Float = 0.35f,
    val blur: Float = 12f,
    val keyOpacity: Float = 0.3f,
    val photoLuminance: Float = 0.3f,
    val photoStamp: Long = 0L,
) {
    /** [p] with the Glass tokens when Glass is on; the photo itself is drawn by [LiveGlassBackdrop]. */
    fun apply(p: KbProps): KbProps = if (!glass) p else p.copy(
        theme = "glass", dim = dim, blur = blur, keyOpacity = keyOpacity, photoLuminance = photoLuminance, backdrop = false,
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
    return LiveTheme(enabled && stamp != 0L, dim / 100f, blur.toFloat(), kop / 100f, lum / 1000f, stamp)
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
                modifier = Modifier.fillMaxSize().blur(theme.blur.dp, BlurredEdgeTreatment.Rectangle),
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0f, 0f, 0f, theme.dim)))
    }
}
