package io.github.sillypuppyy.dogboard.design.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
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
    val screen = css("#f3f3fa") // page background
    val onSurface = css("#1a1b20")
    val onSurfaceVariant = css("#44474f")
    val primary = css("#435e91")
    val outline = css("#74777f")
    val outlineVariant = css("#c4c6d0")
    val surfaceContainerHighest = css("#e2e2e9")
    val row = css("#ffffff")
    val secondaryContainer = css("#dbe2f9")
    val onSecondaryContainer = css("#3f4759")
    val errorContainer = css("#ffdad6") // SetDict:67
    val onErrorContainer = css("#93000a")
    val brandGreen = css("#c8e03c")
    val brandInk = css("#1b1c15")
}

/** Settings text: Roboto Flex, start-aligned (the keyboard's design text is centred). */
fun setText(size: Float, weight: Int = 400, color: Color = SetColors.onSurface): TextStyle =
    designTextStyle(size.sp, weight, color).copy(textAlign = TextAlign.Start, lineHeight = (size * 1.2f).sp)

/** SetHome:31-33: a labelled group of rows, 20 dp corners, 2 dp between rows. */
@Composable
fun SetGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DText(label, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), style = setText(14f, 500, SetColors.primary))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
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

/** SetHome:20: the simplified pup logo (viewBox 108), drawn at [sizeDp]. */
@Composable
fun PupLogo(sizeDp: Float = 36f) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val k = size.width / 108f
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color) =
            drawRoundRect(c, Offset(x * k, y * k), Size(w * k, h * k), CornerRadius(r * k, r * k))
        rr(0f, 0f, 108f, 108f, 54f, SetColors.brandGreen)
        rotate(18f, Offset(30f * k, 34f * k)) { rr(22f, 30f, 16f, 34f, 8f, SetColors.brandInk) }
        rotate(-18f, Offset(78f * k, 34f * k)) { rr(70f, 30f, 16f, 34f, 8f, SetColors.brandInk) }
        rr(32f, 32f, 44f, 44f, 12f, SetColors.brandInk)
        drawCircle(SetColors.brandGreen, 4f * k, Offset(45f * k, 50f * k))
        drawCircle(SetColors.brandGreen, 4f * k, Offset(63f * k, 50f * k))
        rr(44f, 61f, 20f, 6f, 3f, SetColors.brandGreen)
    }
}

/** SetHome:21: the app name, 30 / 400, letter-spacing -0.01em. */
fun appNameStyle() = setText(30f).copy(letterSpacing = (-0.01).em)
