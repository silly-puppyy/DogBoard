package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import io.github.sillypuppyy.dogboard.design.GradientSpec
import io.github.sillypuppyy.dogboard.design.KbProps
import io.github.sillypuppyy.dogboard.design.KbSpec
import io.github.sillypuppyy.dogboard.design.KbThemes
import io.github.sillypuppyy.dogboard.design.KbView
import io.github.sillypuppyy.dogboard.design.PhotoReadability
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.css
import io.github.sillypuppyy.dogboard.design.cssShadow
import kotlin.math.abs
import kotlin.math.roundToInt

/** The gradient creator (user 2026-11-19, "a creator like with gradients"): what it shows; the screen saves. */
class SetGradientState(
    val name: String,
    val colors: List<String>,
    /** Which colour the palette and sliders change. */
    val slot: Int,
    val angle: Int,
    /** "auto", "white" or "dark". */
    val labels: String,
    val editing: Boolean,
    /** "bright", "dark" or "both" (a day-and-night pair), and which version is being edited. */
    val versions: String,
    val tabDark: Boolean,
    val onName: (String) -> Unit,
    val onColors: (List<String>) -> Unit,
    val onSlot: (Int) -> Unit,
    val onAngle: (Int) -> Unit,
    val onLabels: (String) -> Unit,
    val onVersions: (String) -> Unit,
    val onTab: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onBack: () -> Unit,
) {
    /** Auto: the dark version prefers white labels, the bright one dark labels, when they read (4.5:1); else the better. */
    val white: Boolean get() = when (labels) { "white" -> true; "dark" -> false; else -> autoWhite }
    val autoWhite: Boolean get() {
        val pref = if (versions == "both") tabDark else versions == "dark"
        return if (GradientSpec.weakest(colors, pref) >= 4.5) pref else GradientSpec.autoWhite(colors)
    }
    val spec: GradientSpec get() = GradientSpec(colors, angle, white)
}

private val START = TextAlign.Start

/** Calm colours that sit well behind keys: deep ones for white labels, pale ones for dark labels. */
val GRADIENT_PALETTE = listOf(
    "#2b2350", "#6b2f6e", "#9e3b56", "#0b5563", "#23408e", "#1d3b2a", "#3f2a1d", "#1b1c2e", "#4a1f3d", "#123a5a",
    "#d9534f", "#f08a4b", "#f2c14e", "#7bc67e", "#3fb6a8", "#4a90d9", "#8e6fd8", "#d96fb0",
    "#ffe3d1", "#f4c4d8", "#e0d4f7", "#cfe3f7", "#d4f0e4", "#fdf1c7", "#f7d9c4", "#e8e8ec",
)

private val DIRECTIONS = listOf(180 to "Down", 135 to "Diagonal", 90 to "Across", 45 to "Rising")

/** Settings › Themes › New gradient: the keyboard as it will look, its colours, direction, labels and name. */
@Composable
fun SetGradient(state: SetGradientState) {
    val g = state.spec
    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 40.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // header as the Photo page: back, title, Save
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { state.onBack() }, contentAlignment = Alignment.Center) { Symbol("arrow_back", 24.sp, SetColors.onSurface) }
                DText(if (state.editing) "Gradient theme" else "New gradient", Modifier.weight(1f), style = setText(22f).copy(textAlign = START))
                Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.primary).clickable { state.onSave() }.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    DText("Save", style = setText(14f, 500, Color.White))
                }
            }

            // versions as on the Photo page: bright, dark or both; with both, which one is being edited
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val vOpts = listOf("bright" to "Bright", "dark" to "Dark", "both" to "Bright and dark")
                Segmented("gradient:versions", vOpts.map { it.second }, vOpts.indexOfFirst { it.first == state.versions }.coerceAtLeast(0)) { state.onVersions(vOpts[it].first) }
                if (state.versions == "both") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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

            // the keyboard, inset 16 dp with concentric corners as on the Photo page
            BoxWithConstraints(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(SetColors.row).padding(16.dp), contentAlignment = Alignment.TopCenter) {
                val w = maxWidth.value
                val render = remember(g.id, w) { KbSpec.render(KbProps(theme = g.id, width = w, bar = "bal", ret = "send")) }
                Box(Modifier.width(w.dp).height(render.H.dp).clip(RoundedCornerShape(8.dp))) { KbView(render) }
            }

            // the readability line: labels on the key over each colour, the weakest
            val ratio = remember(g.id) { GradientSpec.weakest(g.colors, g.white) }
            val pass = ratio >= 4.5
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
                    .background(css(if (pass) "#e2efc8" else "#fff1d6")).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = css(if (pass) "#2c3a00" else "#4a3200")
                Symbol(if (pass) "check_circle" else "info", 20.sp, fg)
                DText(
                    (if (g.white) "White" else "Dark") + " labels, " + "%.1f".format(ratio) + ":1 at the weakest spot. " +
                        if (pass) "Easy to read." else if (state.labels == "auto") "Try lighter or darker colours." else "Try the other label colour.",
                    Modifier.weight(1f), style = setText(14f, color = fg).copy(textAlign = START, lineHeight = (14 * 1.4f).sp), maxLines = 8,
                )
            }

            // colours: 2 or 3 swatches, the chosen one edited by the palette and sliders
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DText("Colours", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = setText(14f, 500, SetColors.primary))
                SetRows {
                    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            state.colors.forEachIndexed { i, c -> Swatch(c, 56, i == state.slot) { state.onSlot(i) } }
                            if (state.colors.size < 3) Box(
                                Modifier.size(56.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1.5f, SetColors.outline)), RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                                    .clickable { state.onColors(state.colors + KbThemes.mix(state.colors.last(), "#ffffff", 0.35)); state.onSlot(state.colors.size) },
                                contentAlignment = Alignment.Center,
                            ) { Symbol("add", 24.sp, SetColors.primary) }
                            else Box(
                                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable { state.onColors(state.colors.filterIndexed { i, _ -> i != state.slot }); state.onSlot(0) }.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) { DText("Remove", style = setText(14f, 500, SetColors.primary)) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in GRADIENT_PALETTE) Swatch(c, 36, c == state.colors[state.slot]) { state.onColors(state.colors.mapIndexed { i, o -> if (i == state.slot) c else o }) }
                        }
                    }
                    // fine-tuning the chosen colour
                    val hsl = remember(state.colors[state.slot]) { toHsl(state.colors[state.slot]) }
                    fun set(h: Float = hsl[0], s: Float = hsl[1], l: Float = hsl[2]) =
                        state.onColors(state.colors.mapIndexed { i, o -> if (i == state.slot) fromHsl(h, s, l) else o })
                    SetSliderRow("gradient:hue", "Hue", "", hsl[0], hsl[0], 0f..359f, 1f, { "${it.roundToInt()}°" }) { set(h = it) }
                    SetSliderRow("gradient:sat", "Saturation", "", hsl[1], hsl[1], 0f..1f, 0.01f, { "${(it * 100).roundToInt()}%" }) { set(s = it) }
                    SetSliderRow("gradient:light", "Lightness", "", hsl[2], hsl[2], 0f..1f, 0.01f, { "${(it * 100).roundToInt()}%" }) { set(l = it) }
                }
            }

            // direction and labels
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DText("Look", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = setText(14f, 500, SetColors.primary))
                SetRows {
                    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DText("Direction", style = setText(16f).copy(textAlign = START))
                        Segmented("gradient:angle", DIRECTIONS.map { it.second }, DIRECTIONS.indexOfFirst { it.first == state.angle }.coerceAtLeast(0)) { state.onAngle(DIRECTIONS[it].first) }
                    }
                    Column(Modifier.fillMaxWidth().background(SetColors.row).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DText("Labels", style = setText(16f).copy(textAlign = START))
                        val autoFg = if (state.autoWhite) "white" else "dark"
                        val opts = listOf("auto" to "Auto ($autoFg)", "white" to "White", "dark" to "Dark")
                        Segmented("gradient:labels", opts.map { it.second }, opts.indexOfFirst { it.first == state.labels }.coerceAtLeast(0)) { state.onLabels(opts[it].first) }
                    }
                }
            }

            // name
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DText("Name", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = setText(14f, 500, SetColors.primary))
                Box(Modifier.fillMaxWidth().background(SetColors.row, RoundedCornerShape(20.dp)).padding(16.dp)) {
                    androidx.compose.material3.OutlinedTextField(state.name, { state.onName(it.take(40)) }, Modifier.fillMaxWidth(), singleLine = true)
                }
            }
        }
        StatusBarBand()
    }
}

/** A round colour swatch, ringed when chosen (the ring grows with the circle; KbView.ring). */
@Composable
internal fun Swatch(c: String, size: Int, sel: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp)
            .cssShadow(if (sel) listOf(CssShadow(false, 0f, 0f, 0f, 3f, SetColors.row), CssShadow(false, 0f, 0f, 0f, 5f, SetColors.primary)) else listOf(CssShadow(true, 0f, 0f, 0f, 1f, Color(0f, 0f, 0f, 0.12f))), RoundedCornerShape((size / 2).dp))
            .clip(RoundedCornerShape((size / 2).dp)).background(css(c)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (sel) Symbol("check", (size * 0.4f).sp, css(if (GradientSpec.autoWhite(listOf(c))) "#ffffff" else "#1b1c1e")) }
}

/** #rrggbb as hue (degrees), saturation and lightness (0..1). */
internal fun toHsl(hex: String): FloatArray {
    val n = hex.substring(1).toInt(16)
    val r = ((n shr 16) and 255) / 255f; val gg = ((n shr 8) and 255) / 255f; val b = (n and 255) / 255f
    val mx = maxOf(r, gg, b); val mn = minOf(r, gg, b); val l = (mx + mn) / 2; val d = mx - mn
    if (d == 0f) return floatArrayOf(0f, 0f, l)
    val s = d / (1 - abs(2 * l - 1))
    val h = when (mx) { r -> 60 * (((gg - b) / d) % 6); gg -> 60 * ((b - r) / d + 2); else -> 60 * ((r - gg) / d + 4) }
    return floatArrayOf((h + 360) % 360, s.coerceIn(0f, 1f), l)
}

internal fun fromHsl(h: Float, s: Float, l: Float): String {
    val c = (1 - abs(2 * l - 1)) * s; val x = c * (1 - abs((h / 60) % 2 - 1)); val m = l - c / 2
    val (r, g, b) = when ((h / 60).toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
    fun ch(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(ch(r), ch(g), ch(b))
}
