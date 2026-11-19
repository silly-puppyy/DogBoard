package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sillypuppyy.dogboard.design.CssShadow
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.GlassPhoto
import io.github.sillypuppyy.dogboard.design.KbProps
import io.github.sillypuppyy.dogboard.design.KbSpec
import io.github.sillypuppyy.dogboard.design.KbView
import io.github.sillypuppyy.dogboard.design.PhotoReadability
import io.github.sillypuppyy.dogboard.design.PhotoVersions
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.css
import io.github.sillypuppyy.dogboard.design.cssShadow
import kotlin.math.roundToInt

/** What the photo page shows and does; the screen stores each change. */
class SetPhotoState(
    /** The photo, and the dark version's own photo when it has one (with their analyses). */
    val photo: ImageBitmap?,
    val analysis: GlassPhoto.Analysis?,
    val darkPhoto: ImageBitmap?,
    val darkAnalysis: GlassPhoto.Analysis?,
    /** The theme's versions (user 2026-11-19: a bright and a dark version, or one of them). */
    val versions: PhotoVersions,
    /** The version being edited. */
    val tabDark: Boolean,
    val pos: String,
    val onVersions: (String) -> Unit,
    val onTab: (Boolean) -> Unit,
    /** The edited version's look. */
    val onLook: (PhotoReadability.Look) -> Unit,
    val onPos: (String) -> Unit,
    val onPickPhoto: () -> Unit,
    val onPickDarkPhoto: () -> Unit,
    /** The dark version back to the main photo. */
    val onDarkSamePhoto: () -> Unit,
    val onBack: () -> Unit,
) {
    val editingDark: Boolean get() = if (versions.hasBright && versions.hasDark) tabDark else versions.hasDark
    val ownPhoto: Boolean get() = editingDark && versions.darkPhoto && darkPhoto != null
    val shownPhoto: ImageBitmap? get() = if (ownPhoto) darkPhoto else photo
    val shownAnalysis: GlassPhoto.Analysis? get() = if (ownPhoto) darkAnalysis else analysis
    val look: PhotoReadability.Look get() = versions.look(editingDark)
}

private val START = TextAlign.Start

/** Settings › Look › Themes › Photo theme (SetPhoto.dc.html, 390 wide). */
@Composable
fun SetPhoto(state: SetPhotoState) {
    val a = state.shownAnalysis
    val st = state.look
    val auto = remember(a, state.editingDark) { a?.let { PhotoReadability.auto(it, state.editingDark) } ?: PhotoReadability.Look() }
    val m = a?.let { PhotoReadability.measure(it, st) }
    val accents = a?.accents ?: emptyList()
    // the return-key colour picker, open while choosing your own colour
    var picking by remember { androidx.compose.runtime.mutableStateOf(false) }
    val accPair = PhotoReadability.accent(st, accents)
    val acc = accPair?.let { Triple(it.first, it.second, it.first) }
    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 40.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // :18-22 header: back and title (no Save: changes are kept as they are made)
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { state.onBack() }, contentAlignment = Alignment.Center) { Symbol("arrow_back", 24.sp, SetColors.onSurface) }
                DText("Photo theme", Modifier.weight(1f), style = setText(22f).copy(textAlign = START), maxLines = 2)
                // no Save button: every change is kept as it is made (user 2026-11-19: autosave)
            }

            // versions: bright, dark or both (a day-and-night pair); with both, which one is being edited
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val vOpts = listOf("bright" to "Bright", "dark" to "Dark", "both" to "Bright and dark")
                Segmented("photo:versions", vOpts.map { it.second }, vOpts.indexOfFirst { it.first == state.versions.versions }.coerceAtLeast(0)) { state.onVersions(vOpts[it].first) }
                if (state.versions.hasBright && state.versions.hasDark) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DText("Editing", Modifier.padding(start = 4.dp, end = 4.dp), style = setText(14f, 500, SetColors.onSurfaceVariant))
                        for ((dark, label, icon) in listOf(Triple(false, "Bright", "light_mode"), Triple(true, "Dark", "dark_mode"))) {
                            val on = state.tabDark == dark
                            Row(
                                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) SetColors.primary else SetColors.row).clickable { state.onTab(dark) }.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Symbol(icon, 18.sp, if (on) Color.White else SetColors.onSurfaceVariant)
                                DText(label, style = setText(14f, 500, if (on) Color.White else SetColors.onSurface))
                            }
                        }
                    }
                }
            }

            // :24-26 the keyboard on the photo, as set
            // the keyboard inset 16 dp on every side, its corners 24 - 16 = 8 dp so the two curves are concentric (the
            // keyboard ran flush to the bottom with 16 dp top corners, cut by the card's 24 dp ones; user 2026-11-19)
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(SetColors.row).padding(16.dp), contentAlignment = Alignment.TopCenter) {
                val kbWidth = maxWidth.value
                val props = KbProps(
                    theme = "glass", bar = "bal", ret = "send", width = kbWidth, dim = st.dim.toFloat(), wash = st.wash.toFloat(), keyDark = st.keyDark, blur = st.blur, keyOpacity = st.tint.toFloat(),
                    glassFg = m?.fg ?: st.labels, halo = st.halo, actColor = acc?.first, actFg = acc?.second, backdrop = false,
                )
                val render = remember(props) { KbSpec.render(props) }
                Box(Modifier.width(kbWidth.dp).height(render.H.dp).clip(RoundedCornerShape(8.dp))) {
                    state.shownPhoto?.let { p ->
                        Image(p, null, Modifier.fillMaxSize().then(if (st.blur > 0) Modifier.blur(st.blur.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle) else Modifier), contentScale = ContentScale.Crop,
                            colorFilter = io.github.sillypuppyy.dogboard.design.photoFilter(st.sat.toFloat(), st.contrast.toFloat()),
                            alignment = when (state.pos) { "center top" -> Alignment.TopCenter; "center bottom" -> Alignment.BottomCenter; else -> Alignment.Center })
                    }
                    Box(Modifier.fillMaxSize().background(Color(0f, 0f, 0f, st.dim.toFloat())))
                    if (st.wash > 0) Box(Modifier.fillMaxSize().background(Color(1f, 1f, 1f, st.wash.toFloat())))
                    KbView(render)
                }
            }

            // :28-32 the readability line: ratio on the brightest spots, Fix it when too low
            if (m != null) {
                val pass = m.readable
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
                        .background(css(if (pass) "#e2efc8" else "#fff1d6")).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fg = css(if (pass) "#2c3a00" else "#4a3200")
                    Symbol(if (pass) "check_circle" else "info", 20.sp, fg)
                    DText(
                        (if (m.fg == "white") "White" else "Dark") + " labels, " + "%.1f".format(m.ratio) + ":1 on the " + (if (m.fg == "white") "brightest" else "darkest") + " spots. " +
                            if (pass) "Easy to read." else "Letters there may be hard to read.",
                        Modifier.weight(1f), style = setText(14f, color = fg).copy(textAlign = START, lineHeight = (14 * 1.4f).sp), maxLines = 8,
                    )
                    if (!pass) Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable { state.onLook(PhotoReadability.fix(a, st)) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        DText("Fix it", style = setText(14f, 600, fg))
                    }
                }
            }

            // :34-52 the photo and its position
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DText("Photo", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = setText(14f, 500, SetColors.primary))
                Column(Modifier.fillMaxWidth().background(SetColors.row, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // the dark version of a two-version theme may have its own photo (user 2026-11-19)
                    val darkTab = state.editingDark && state.versions.hasBright
                    if (darkTab) {
                        val own = state.versions.darkPhoto
                        Segmented("photo:darkphoto", listOf("Same photo", "Its own photo"), if (own) 1 else 0) { if (it == 1) state.onPickDarkPhoto() else state.onDarkSamePhoto() }
                    }
                    if (!darkTab || state.versions.darkPhoto) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.shownPhoto?.let { p ->
                            Image(p, "Your photo", Modifier.size(56.dp).cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 3f, SetColors.row), CssShadow(false, 0f, 0f, 0f, 5f, SetColors.primary)), RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                        }
                        Box(
                            Modifier.size(56.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1.5f, SetColors.outline)), RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).clickable { if (darkTab) state.onPickDarkPhoto() else state.onPickPhoto() },
                            contentAlignment = Alignment.Center,
                        ) { Symbol("add_photo_alternate", 24.sp, SetColors.primary) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DText("Position", style = setText(16f).copy(textAlign = START))
                        val posOpts = listOf("center top" to "Top", "center" to "Centre", "center bottom" to "Bottom")
                        Segmented("photo:pos", posOpts.map { it.second }, posOpts.indexOfFirst { it.first == state.pos }.coerceAtLeast(0)) { state.onPos(posOpts[it].first) }
                    }
                }
            }

            // :54-95 readability: return key colour, labels, dimming, key background, blur, label shadow
            if (a != null) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val anyChanged = st != auto.copy(accent = st.accent) || st.accent != 0
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DText("Readability", style = setText(14f, 500, SetColors.primary).copy(textAlign = START))
                        val desc = PhotoReadability.describe(a, auto)
                        DText(if (anyChanged) "Changed by you. Auto would use $desc." else "Matched to this photo: $desc.",
                            style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 8)
                    }
                    if (anyChanged) Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable { state.onLook(auto) }.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) { Symbol("restart_alt", 18.sp, SetColors.primary); DText("Auto", style = setText(14f, 500, SetColors.primary)) }
                }
                SetRows {
                    // :155-157 return key colour from the photo
                    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TitleSub("Return key colour", if (st.actColor != null) "Your colour" else "Picked from the photo")
                        // a colour of your own (user 2026-11-19): the gradient creator's palette and sliders
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            accents.forEachIndexed { i, c ->
                                val sel = i == st.accent && st.actColor == null
                                Box(
                                    Modifier.size(48.dp)
                                        .cssShadow(if (sel) listOf(CssShadow(false, 0f, 0f, 0f, 3f, SetColors.row), CssShadow(false, 0f, 0f, 0f, 5f, SetColors.primary)) else listOf(CssShadow(true, 0f, 0f, 0f, 1f, Color(0f, 0f, 0f, 0.12f))), RoundedCornerShape(24.dp))
                                        .clip(RoundedCornerShape(24.dp)).background(css(c.first)).clickable { picking = false; state.onLook(st.copy(accent = i, actColor = null)) },
                                    contentAlignment = Alignment.Center,
                                ) { Symbol(if (sel) "check" else "send", 20.sp, css(c.second)) }
                            }
                            // your own colour: shows it once picked, else a palette icon
                            val own = st.actColor
                            Box(
                                Modifier.size(48.dp)
                                    .cssShadow(if (own != null) listOf(CssShadow(false, 0f, 0f, 0f, 3f, SetColors.row), CssShadow(false, 0f, 0f, 0f, 5f, SetColors.primary)) else listOf(CssShadow(true, 0f, 0f, 0f, 1.5f, SetColors.outline)), RoundedCornerShape(24.dp))
                                    .clip(RoundedCornerShape(24.dp)).background(if (own != null) css(own) else Color.Transparent)
                                    .clickable { picking = !picking; if (own == null) state.onLook(st.copy(actColor = accPair?.first ?: "#f0b46a")) },
                                contentAlignment = Alignment.Center,
                            ) { Symbol(if (own != null) "check" else "palette", 20.sp, if (own != null) css(accPair?.second ?: "#ffffff") else SetColors.primary) }
                            DText(if (st.actColor != null) "Your colour. Tap it to change it." else if (st.accent == 0) "The photo’s strongest colour, made bright enough for the key." else "Another colour from the photo.",
                                Modifier.weight(1f), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START, lineHeight = (13 * 1.4f).sp), maxLines = 8)
                        }
                    }
                    if (picking && st.actColor != null) Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in GRADIENT_PALETTE) Swatch(c, 36, c == st.actColor) { state.onLook(st.copy(actColor = c)) }
                        }
                    }
                    if (picking && st.actColor != null) {
                        val hsl = remember(st.actColor) { toHsl(st.actColor) }
                        fun set(h: Float = hsl[0], sa: Float = hsl[1], l: Float = hsl[2]) = state.onLook(st.copy(actColor = fromHsl(h, sa, l)))
                        SetSliderRow("photo:acthue", "Hue", "", hsl[0], hsl[0], 0f..359f, 1f, { "${it.roundToInt()}°" }) { set(h = it) }
                        SetSliderRow("photo:actsat", "Saturation", "", hsl[1], hsl[1], 0f..1f, 0.01f, { "${(it * 100).roundToInt()}%" }) { set(sa = it) }
                        SetSliderRow("photo:actlight", "Lightness", "", hsl[2], hsl[2], 0f..1f, 0.01f, { "${(it * 100).roundToInt()}%" }) { set(l = it) }
                    }
                    // :158 labels
                    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DText("Labels", style = setText(16f).copy(textAlign = START))
                        val autoFg = PhotoReadability.measure(a, st.copy(labels = "auto")).fg
                        val opts = listOf("auto" to "Auto ($autoFg)", "white" to "White", "dark" to "Dark")
                        Segmented("photo:labels", opts.map { it.second }, opts.indexOfFirst { it.first == st.labels }.coerceAtLeast(0)) { state.onLook(st.copy(labels = opts[it].first)) }
                    }
                    // :159-162 dimming, key background, blur
                    fun pc(v: Float) = "${(v * 100).roundToInt()}%"
                    // brightness, 0-200 % (user 2026-11-19): below 100 % darker, above it a soft white wash
                    SetSliderRow("photo:bright", "Brightness", if (st.bright > 1.0) "Brightened with a soft white wash" else if (st.bright < 1.0) "Darkened" else "As shot",
                        st.bright.toFloat(), auto.bright.toFloat(), 0f..2f, 0.05f, ::pc) { state.onLook(st.copy(bright = it.toDouble())) }
                    SetSliderRow("photo:contrast", "Contrast", "", st.contrast.toFloat(), 1f, 0.5f..1.5f, 0.05f, ::pc) { state.onLook(st.copy(contrast = it.toDouble())) }
                    SetSliderRow("photo:sat", "Saturation", if (st.sat == 0.0) "Black and white" else "", st.sat.toFloat(), 1f, 0f..2f, 0.05f, ::pc) { state.onLook(st.copy(sat = it.toDouble())) }
                    // light or dark keys (a white or a black tint behind each key)
                    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DText("Keys", style = setText(16f).copy(textAlign = START))
                        Segmented("photo:keydark", listOf("Light", "Dark"), if (st.keyDark) 1 else 0) { state.onLook(st.copy(keyDark = it == 1, tint = if (st.tint == 0.0) 0.2 else st.tint)) }
                    }
                    SetSliderRow("photo:tint", "Key background", if (st.tint == 0.0) "Clear keys with a hairline edge" else (if (st.keyDark) "Black behind each key; more helps white labels" else "White behind each key; more helps dark labels"),
                        st.tint.toFloat(), auto.tint.toFloat(), 0f..0.9f, 0.05f, { if (it == 0f) "Clear" else pc(it) }) { state.onLook(st.copy(tint = it.toDouble())) }
                    SetSliderRow("photo:blur", "Blur", "Off keeps your photo sharp", st.blur, auto.blur, 0f..25f, 1f, { if (it == 0f) "Off" else it.roundToInt().toString() }) { state.onLook(st.copy(blur = it)) }
                    // :163 label shadow
                    SetSwitchRow("photo:halo", "Label shadow", "A soft shadow behind letters, for the brightest spots", st.halo) { state.onLook(st.copy(halo = it)) }
                }
            }

        }
        StatusBarBand()
    }
}
