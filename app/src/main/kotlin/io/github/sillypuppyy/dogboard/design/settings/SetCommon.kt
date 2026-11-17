package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.sillypuppyy.dogboard.design.CssShadow
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.LintParent
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.css
import io.github.sillypuppyy.dogboard.design.cssShadow
import io.github.sillypuppyy.dogboard.design.designTextStyle
import io.github.sillypuppyy.dogboard.design.lint

/**
 * Building blocks shared by the settings pages of the design canvas (SetHome, SetLook, SetFeel,
 * SetDict), ported from their HTML. Colours are the pages' literal values (light scheme only in
 * the canvas); each comment names the Material role the value belongs to.
 */
object SetColors {
    /**
     * The app's Material scheme (set by FlorisAppTheme), seeded from the keyboard theme (user
     * 2026-11-17: "change the app to match the keyboard theme"); null in previews: the canvas's
     * light colours. Dark uses the palette's own dark roles (user, 2026-11-14: "isn't there
     * already a factory default dark? why not just use it").
     */
    var scheme: androidx.compose.material3.ColorScheme? by androidx.compose.runtime.mutableStateOf(null)
    var schemeDark: Boolean by androidx.compose.runtime.mutableStateOf(false)

    /** [light]: the canvas's colour, [lightRole] the scheme role it is (the canvas is the #435e91 seed's 2021 palette). */
    private inline fun role(light: Color, lightRole: (androidx.compose.material3.ColorScheme) -> Color, dark: (androidx.compose.material3.ColorScheme) -> Color): Color =
        scheme?.let { if (schemeDark) dark(it) else lightRole(it) } ?: light

    private val lScreen = css("#f3f3fa")
    private val lOnSurface = css("#1a1b20")
    private val lOnSurfaceVariant = css("#44474f")
    private val lPrimary = css("#435e91")
    private val lOutline = css("#74777f")
    private val lOutlineVariant = css("#c4c6d0")
    private val lSurfaceContainerHighest = css("#e2e2e9")
    private val lRow = css("#ffffff")
    private val lSecondaryContainer = css("#dbe2f9")
    private val lOnSecondaryContainer = css("#3f4759")
    private val lErrorContainer = css("#ffdad6")
    private val lOnErrorContainer = css("#93000a")

    val screen get() = role(lScreen, { it.surfaceContainerLow }) { it.surface } // page background
    val onSurface get() = role(lOnSurface, { it.onSurface }) { it.onSurface }
    val onSurfaceVariant get() = role(lOnSurfaceVariant, { it.onSurfaceVariant }) { it.onSurfaceVariant }
    val primary get() = role(lPrimary, { it.primary }) { it.primary }
    val outline get() = role(lOutline, { it.outline }) { it.outline }
    val outlineVariant get() = role(lOutlineVariant, { it.outlineVariant }) { it.outlineVariant }
    val surfaceContainerHighest get() = role(lSurfaceContainerHighest, { it.surfaceContainerHighest }) { it.surfaceContainerHighest }
    val row get() = role(lRow, { it.surfaceContainerLowest }) { it.surfaceContainer } // rows sit a step above the page
    val secondaryContainer get() = role(lSecondaryContainer, { it.secondaryContainer }) { it.secondaryContainer }
    val onSecondaryContainer get() = role(lOnSecondaryContainer, { it.onSecondaryContainer }) { it.onSecondaryContainer }
    val errorContainer get() = role(lErrorContainer, { it.errorContainer }) { it.errorContainer } // SetDict:67
    val onErrorContainer get() = role(lOnErrorContainer, { it.onErrorContainer }) { it.onErrorContainer }
    /**
     * A tonal container pair (SetHome's section icons): the scheme's primary / secondary / tertiary
     * container roles for the canvas's pairs (the canvas's own colours in previews).
     */
    fun container(lightBg: Color, lightFg: Color): Pair<Color, Color> {
        val s = scheme ?: return lightBg to lightFg
        return when (lightBg) {
            css("#d8e2ff") -> s.primaryContainer to s.onPrimaryContainer
            css("#fbd7fc") -> s.tertiaryContainer to s.onTertiaryContainer
            else -> s.secondaryContainer to s.onSecondaryContainer
        }
    }

    // Brand.dc.html: Fur and Ink (brand colours, the same in light and dark)
    val brandFur = css("#d9893d")
    val brandInk = css("#221a14")
}

/** Settings text: Roboto Flex, start-aligned (the keyboard's design text is centred). */
fun setText(size: Float, weight: Int = 400, color: Color = SetColors.onSurface): TextStyle =
    designTextStyle(size.sp, weight, color).copy(textAlign = TextAlign.Start, lineHeight = (size * 1.2f).sp)

/** The page colour behind the status bar, so a scrolled page doesn't run under the clock (last child of the page's Box). */
@Composable
fun StatusBarBand() {
    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars).background(SetColors.screen))
}

/** SetHome:31-33: a labelled group of rows, 20 dp corners, 2 dp between rows. */
@Composable
fun SetGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DText(label, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), style = setText(14f, 500, SetColors.primary))
        SetRows(Modifier.fillMaxWidth(), content)
    }
}

/**
 * Rows stacked 2 dp apart, as Android's own Settings groups them (measured on Android 17,
 * 2026-11-17): 20 dp corners on the group's outside, 4 dp corners where rows meet.
 */
@Composable
fun SetRows(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, c ->
        val gap = 2.dp.roundToPx()
        val ps = measurables.map { it.measure(c.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)) }
        val w = ps.maxOfOrNull { it.width }?.coerceAtLeast(c.minWidth) ?: c.minWidth
        val h = ps.sumOf { it.height } + gap * (ps.size - 1).coerceAtLeast(0)
        layout(w, h) {
            var y = 0
            ps.forEachIndexed { i, p ->
                val top = if (i == 0) 20.dp else 4.dp
                val bottom = if (i == ps.lastIndex) 20.dp else 4.dp
                p.placeWithLayer(0, y) { shape = RoundedCornerShape(top, top, bottom, bottom); clip = true }
                y += p.height + gap
            }
        }
    }
}

/** SetHome:36: a row's title (16) and sub (14, onSurfaceVariant). */
@Composable
fun TitleSub(title: String, sub: String, modifier: Modifier = Modifier, subLines: Int = 3) { // CSS: subs wrap
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DText(title, style = setText(16f))
        DText(sub, style = setText(14f, color = SetColors.onSurfaceVariant), maxLines = subLines)
    }
}

/** SetHome:35-41: a row with a title, a sub and a control under them. */
@Composable
fun SetBlockRow(id: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(SetColors.row).lint(id).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { LintParent(id) { content() } }
}

/**
 * SetFeel:63-66: a switch row: title 16, sub 14 onSurfaceVariant, min height 72, padding
 * 12/8/12/16, and the M3 switch (52 x 32 track; on: primary track, 24 dp white thumb at 4/24; off:
 * surfaceContainerHighest track with a 2 dp outline ring, 16 dp outline thumb at 8/8).
 */
@Composable
fun SetSwitchRow(id: String, title: String, sub: String, checked: Boolean, onChange: ((Boolean) -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SetColors.row)
            .then(if (onChange != null) Modifier.clickable { onChange(!checked) } else Modifier).lint(id)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        LintParent(id) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DText(title, style = setText(16f))
                DText(sub, style = setText(14f, color = SetColors.onSurfaceVariant), maxLines = 3)
            }
            val track = if (checked) SetColors.primary else SetColors.surfaceContainerHighest
            Box(
                Modifier.size(52.dp, 32.dp)
                    .then(if (checked) Modifier else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 2f, SetColors.outline)), RoundedCornerShape(16.dp)))
                    .background(track, RoundedCornerShape(16.dp)),
            ) {
                val size = if (checked) 24 else 16
                val left = if (checked) 24 else 8
                val top = if (checked) 4 else 8
                Box(
                    Modifier.padding(start = left.dp, top = top.dp).size(size.dp)
                        .background(if (checked) Color.White else SetColors.outline, CircleShape),
                )
            }
        }
    }
}

/**
 * SetFeel:33-41: a slider row: title 16 and the value 16/600 (onPrimaryContainer #2a4678 when
 * changed, else onSurfaceVariant), a 44 dp reset button (secondaryContainer, restart_alt 20)
 * when changed, the explanation 14 / 1.4, then the slider. The canvas's HTML range input is a web
 * stand-in: the app uses the Material 3 slider in the design's primary colour.
 */
@Composable
fun SetSliderRow(
    id: String, title: String, explain: String, value: Float, default: Float, range: ClosedFloatingPointRange<Float>, step: Float,
    format: (Float) -> String, onChange: (Float) -> Unit,
) {
    val changed = value != default
    Column(
        Modifier.fillMaxWidth().background(SetColors.row).lint(id).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LintParent(id) {
            Row(Modifier.heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DText(title, Modifier.weight(1f), style = setText(16f))
                DText(format(value), style = setText(16f, 600, if (changed) SetColors.primary else SetColors.onSurfaceVariant))
                if (changed) {
                    Box(
                        Modifier.size(44.dp).background(SetColors.secondaryContainer, CircleShape).clickable { onChange(default) },
                        contentAlignment = Alignment.Center,
                    ) { Symbol("restart_alt", 20.sp, SetColors.primary) }
                } else Box(Modifier.size(44.dp))
            }
            DText(explain, Modifier.padding(end = 8.dp), style = setText(14f, color = SetColors.onSurfaceVariant).copy(lineHeight = (14 * 1.4).sp), maxLines = 4)
            val steps = ((range.endInclusive - range.start) / step).toInt() - 1
            androidx.compose.material3.Slider(
                value = value, onValueChange = { v -> onChange((Math.round((v - range.start) / step) * step + range.start)) },
                valueRange = range, steps = steps.coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp).height(36.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = SetColors.primary, activeTrackColor = SetColors.primary,
                    inactiveTrackColor = SetColors.secondaryContainer,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}

/** SetHome:44-57: a link row (title/sub with something before and after). */
@Composable
fun SetLinkRow(id: String, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(SetColors.row)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).lint(id).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) { LintParent(id) { content() } }
}

/**
 * SetHome:37-40: M3 segmented button, 40 dp, outline ring, 1 dp separators; the selected segment
 * is secondaryContainer with a check. [weights]: the design's flex values per option.
 */
@Composable
fun Segmented(
    id: String, options: List<String>, selected: Int, weights: List<Float> = options.map { 1f }, checkGap: Int = 6,
    onSelect: ((Int) -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().height(40.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), shape).clip(shape).lint(id),
    ) {
        LintParent(id) {
            for ((i, o) in options.withIndex()) {
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(SetColors.outline))
                val on = i == selected
                Row(
                    Modifier.weight(weights[i]).fillMaxHeight().background(if (on) SetColors.secondaryContainer else Color.Transparent)
                        .then(if (onSelect != null) Modifier.clickable { onSelect(i) } else Modifier).lint("$id:$o"),
                    horizontalArrangement = Arrangement.spacedBy(checkGap.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    LintParent("$id:$o") {
                        val fg = if (on) SetColors.onSecondaryContainer else SetColors.onSurface
                        if (on) Symbol("check", 18.sp, fg)
                        DText(o, style = setText(14f, 500, fg))
                    }
                }
            }
        }
    }
}

/** SetHome:87-90: a section row: 40 dp tonal disc with a 22 dp icon, title, one-line sub. */
@Composable
fun SetSectionRow(icon: String, title: String, sub: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SetColors.row)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .lint("section:$title").padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        LintParent("section:$title") {
            Box(Modifier.size(40.dp).background(bg, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Symbol(icon, 22.sp, fg) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DText(title, style = setText(16f))
                // white-space: nowrap; text-overflow: ellipsis (the lint reports a cut)
                DText(sub, style = setText(14f, color = SetColors.onSurfaceVariant), overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** SetHome:95-100: the Try-it dock pinned to the bottom of every page. */
@Composable
fun TryItDock(modifier: Modifier = Modifier, live: Boolean = false) {
    Box(
        modifier.fillMaxWidth().cssShadow(listOf(CssShadow(false, 0f, -1f, 0f, 0f, SetColors.surfaceContainerHighest)), RoundedCornerShape(0.dp))
            .background(SetColors.screen).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outlineVariant)), RoundedCornerShape(26.dp))
                .background(SetColors.row, RoundedCornerShape(26.dp)).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            // SetHome:109: an input with the placeholder "Try it here", text 16 / onSurface
            if (live) {
                var text by remember { mutableStateOf("") }
                BasicTextField(
                    text, { text = it }, Modifier.weight(1f), singleLine = true,
                    textStyle = setText(16f), cursorBrush = SolidColor(SetColors.primary),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) DText("Try it here", style = setText(16f, color = SetColors.onSurfaceVariant))
                            inner()
                        }
                    },
                )
            } else DText("Try it here", Modifier.weight(1f), style = setText(16f, color = SetColors.onSurfaceVariant))
            Symbol("keyboard", 22.sp, SetColors.onSurfaceVariant)
        }
    }
}

/** Brand.dc.html: the paw mark (one path, viewBox 108) on a Fur disc, drawn at [sizeDp]. */
@Composable
fun PawLogo(sizeDp: Float = 36f) {
    val paw = remember { PathParser().parsePathString(PAW_PATH).toPath() }
    Canvas(Modifier.size(sizeDp.dp)) {
        val k = size.width / 108f
        drawCircle(SetColors.brandFur, 54f * k, Offset(54f * k, 54f * k))
        // transform="translate(54 54) scale(0.56) translate(-51.3 -48.75)"
        withTransform({
            scale(k, k, Offset.Zero)
            translate(54f, 54f)
            scale(0.56f, 0.56f, Offset.Zero)
            translate(-51.3f, -48.75f)
        }) { drawPath(paw, SetColors.brandInk) }
    }
}

/** Brand.dc.html: the paw mark's single path (also in res/drawable/ic_app_icon_foreground.xml). */
private const val PAW_PATH = "M34.7 97.0 C35.7 96.9 38.6 96.4 41.2 96.0 C49.8 94.5 51.7 94.5 60.4 96.0 C67.4 97.3 71.0 97.5 73.9 97.0 C81.1 95.7 86.1 91.2 87.7 84.7 C88.1 83.1 88.0 79.1 87.5 77.4 C86.3 72.9 83.8 69.2 79.8 66.1 C72.5 60.4 71.0 58.9 67.0 53.0 C64.3 49.1 62.4 46.9 60.2 45.5 C53.2 40.8 44.5 41.6 38.6 47.5 C37.1 49.0 36.3 50.0 34.7 52.3 C34.2 53.0 33.4 54.1 32.9 54.9 C30.1 58.8 28.0 60.9 22.3 65.4 C17.4 69.2 15.0 72.5 13.8 77.1 C11.3 86.5 17.3 95.4 27.3 97.0 C29.8 97.4 31.6 97.4 34.7 97.0Z M18.2 62.2 C24.3 60.6 27.2 52.2 24.4 44.0 C23.5 41.3 20.5 35.5 18.8 33.1 C14.9 27.8 10.5 27.3 6.2 31.6 C1.9 35.9 0.0 43.1 1.7 49.0 C2.1 50.4 3.5 53.4 4.5 54.8 C8.3 60.5 13.7 63.4 18.2 62.2Z M88.5 62.2 C94.2 60.7 99.9 53.8 101.0 47.0 C102.6 37.5 95.1 26.8 88.5 29.0 C85.7 29.9 83.1 33.0 80.1 39.1 C77.6 44.0 77.0 46.0 76.9 49.6 C76.8 54.1 77.9 57.5 80.3 59.9 C82.6 62.2 85.4 62.9 88.5 62.2Z M37.9 42.1 C44.2 40.5 48.9 32.7 48.9 23.8 C48.9 16.0 47.5 9.4 45.1 6.3 C40.4 0.0 29.0 4.9 24.2 15.1 C22.3 19.0 21.9 23.3 23.0 28.4 C24.9 37.9 31.3 43.8 37.9 42.1Z M69.3 42.0 C77.2 39.7 82.2 26.8 78.9 16.9 C76.0 8.2 66.5 1.6 60.2 4.0 C56.1 5.5 53.9 11.5 53.5 22.2 C53.4 25.9 53.7 28.6 54.8 31.6 C57.3 39.4 63.4 43.7 69.3 42.0Z"

/** SetHome:21: the app name, 30 / 400, letter-spacing -0.01em. */
fun appNameStyle() = setText(30f).copy(letterSpacing = (-0.01).em)
