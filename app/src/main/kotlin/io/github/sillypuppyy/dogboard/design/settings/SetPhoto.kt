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
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.css
import io.github.sillypuppyy.dogboard.design.cssShadow
import kotlin.math.roundToInt

/** What the photo page shows and does; the screen stores each change. */
class SetPhotoState(
    val photo: ImageBitmap?,
    val analysis: GlassPhoto.Analysis?,
    val look: PhotoReadability.Look,
    val pos: String,
    /** "day", "night" or "both": which of the Themes page's slots the photo theme fills. */
    val use: String,
    val onLook: (PhotoReadability.Look) -> Unit,
    val onPos: (String) -> Unit,
    val onUse: (String) -> Unit,
    val onPickPhoto: () -> Unit,
    val onBack: () -> Unit,
)

private val START = TextAlign.Start

/** Settings › Look › Themes › Photo theme (SetPhoto.dc.html, 390 wide). */
@Composable
fun SetPhoto(state: SetPhotoState) {
    val a = state.analysis
    val st = state.look
    val auto = remember(a) { a?.let { PhotoReadability.auto(it) } ?: PhotoReadability.Look() }
    val m = a?.let { PhotoReadability.measure(it, st) }
    val accents = a?.accents ?: emptyList()
    val acc = accents.getOrNull(st.accent) ?: accents.firstOrNull()
    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 40.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // :18-22 header: back, title, Save (changes are kept as they are made; Save returns)
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { state.onBack() }, contentAlignment = Alignment.Center) { Symbol("arrow_back", 24.sp, SetColors.onSurface) }
                DText("Photo theme", Modifier.weight(1f), style = setText(22f).copy(textAlign = START))
                Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.primary).clickable { state.onBack() }.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    DText("Save", style = setText(14f, 500, Color.White))
                }
            }

            // :24-26 the keyboard on the photo, as set
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(SetColors.row).padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
                val props = KbProps(
                    theme = "glass", bar = "bal", ret = "send", width = 358f, dim = st.dim.toFloat(), blur = st.blur, keyOpacity = st.tint.toFloat(),
                    glassFg = m?.fg ?: st.labels, halo = st.halo, actColor = acc?.first, actFg = acc?.second, backdrop = false,
                )
                val render = remember(props) { KbSpec.render(props) }
                Box(Modifier.width(358.dp).height(render.H.dp).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))) {
                    state.photo?.let { p ->
                        Image(p, null, Modifier.fillMaxSize().then(if (st.blur > 0) Modifier.blur(st.blur.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle) else Modifier), contentScale = ContentScale.Crop,
                            alignment = when (state.pos) { "center top" -> Alignment.TopCenter; "center bottom" -> Alignment.BottomCenter; else -> Alignment.Center })
                    }
                    Box(Modifier.fillMaxSize().background(Color(0f, 0f, 0f, st.dim.toFloat())))
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
                        (if (m.fg == "white") "White" else "Dark") + " labels, " + "%.1f".format(m.ratio) + ":1 on the brightest spots. " +
                            if (pass) "Easy to read." else "Letters there may be hard to read.",
                        Modifier.weight(1f), style = setText(14f, color = fg).copy(textAlign = START, lineHeight = (14 * 1.4f).sp), maxLines = 3,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.photo?.let { p ->
                            Image(p, "Your photo", Modifier.size(56.dp).cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 3f, Color.White), CssShadow(false, 0f, 0f, 0f, 5f, SetColors.primary)), RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                        }
                        Box(
                            Modifier.size(56.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1.5f, SetColors.outline)), RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).clickable { state.onPickPhoto() },
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
                            style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 4)
                    }
                    if (anyChanged) Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable { state.onLook(auto) }.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) { Symbol("restart_alt", 18.sp, SetColors.primary); DText("Auto", style = setText(14f, 500, SetColors.primary)) }
                }
                SetRows {
                    // :155-157 return key colour from the photo
                    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TitleSub("Return key colour", "Picked from the photo")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            accents.forEachIndexed { i, c ->
                                val sel = i == st.accent
                                Box(
                                    Modifier.size(48.dp)
                                        .cssShadow(if (sel) listOf(CssShadow(false, 0f, 0f, 0f, 3f, Color.White), CssShadow(false, 0f, 0f, 0f, 5f, SetColors.primary)) else listOf(CssShadow(true, 0f, 0f, 0f, 1f, Color(0f, 0f, 0f, 0.12f))), RoundedCornerShape(24.dp))
                                        .clip(RoundedCornerShape(24.dp)).background(css(c.first)).clickable { state.onLook(st.copy(accent = i)) },
                                    contentAlignment = Alignment.Center,
                                ) { Symbol(if (sel) "check" else "send", 20.sp, css(c.second)) }
                            }
                            DText(if (st.accent == 0) "The photo’s strongest colour, made bright enough for the key." else "Another colour from the photo.",
                                Modifier.weight(1f), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START, lineHeight = (13 * 1.4f).sp), maxLines = 3)
                        }
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
                    SetSliderRow("photo:dim", "Dim the photo", "", st.dim.toFloat(), auto.dim.toFloat(), 0f..0.8f, 0.05f, ::pc) { state.onLook(st.copy(dim = it.toDouble())) }
                    SetSliderRow("photo:tint", "Key background", if (st.tint == 0.0) "Clear keys with a hairline edge" else "White behind each key; more helps dark labels",
                        st.tint.toFloat(), auto.tint.toFloat(), 0f..0.9f, 0.05f, { if (it == 0f) "Clear" else pc(it) }) { state.onLook(st.copy(tint = it.toDouble())) }
                    SetSliderRow("photo:blur", "Blur", "Off keeps your photo sharp", st.blur, auto.blur, 0f..25f, 1f, { if (it == 0f) "Off" else it.roundToInt().toString() }) { state.onLook(st.copy(blur = it)) }
                    // :163 label shadow
                    SetSwitchRow("photo:halo", "Label shadow", "A soft shadow behind letters, for the brightest spots", st.halo) { state.onLook(st.copy(halo = it)) }
                }
            }

            // :97-106 which slot the photo theme fills
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DText("Use it for", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = setText(14f, 500, SetColors.primary))
                Box(Modifier.fillMaxWidth().background(SetColors.row, RoundedCornerShape(20.dp)).padding(16.dp)) {
                    val opts = listOf("day" to "Day", "night" to "Night", "both" to "Day and night")
                    Segmented("photo:use", opts.map { it.second }, opts.indexOfFirst { it.first == state.use }.coerceAtLeast(0)) { state.onUse(opts[it].first) }
                }
            }
        }
    }
}
