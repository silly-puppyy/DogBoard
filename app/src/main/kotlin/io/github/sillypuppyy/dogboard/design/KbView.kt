package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Taps on the live keyboard's bar (stage 2). Null in the design gallery, where nothing is tappable. */
class KbActions(
    val onSlot: (Int) -> Unit,
    /** A long-press on a slot: remove that suggestion (Panels:102). */
    val onSlotLong: (Int) -> Unit = {},
    /** Undo on the removed bar (Kb:119). */
    val onUndo: () -> Unit = {},
    val onLeft: () -> Unit = {},
    val onMic: () -> Unit = {},
    /** A tool tapped in the bar's tool row or the tools panel, by its icon name (Kb:663-668). */
    val onTool: (String) -> Unit = {},
    /** The panel header's back button (Kb:37-46). */
    val onBack: () -> Unit = {},
    /** A clipboard tile, by its position in the list. */
    val onClip: (Int) -> Unit = {},
)

val LocalKbActions = androidx.compose.runtime.staticCompositionLocalOf<KbActions?> { null }

internal fun Modifier.tap(action: (() -> Unit)?): Modifier =
    if (action == null) this else this.then(Modifier.clickable(onClick = action))

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.tap(action: (() -> Unit)?, onLong: (() -> Unit)?): Modifier =
    if (action == null) this else this.then(Modifier.combinedClickable(onClick = action, onLongClick = onLong))

/**
 * The Kb.dc.html template (Kb:16-322) drawn from a [KbRender]. Panels, the dev row and the special
 * bar states are not ported yet (see notes/design/port-map.md).
 */
@Composable
fun KbView(r: KbRender, modifier: Modifier = Modifier) {
    val t = r.t
    // Kb:16 root
    Box(
        modifier
            .width(r.W.dp)
            .height(r.H.dp)
            .cssShadow(r.rootShadow, RoundedCornerShape(r.rootRadius.dp))
            .background(t.bg, RoundedCornerShape(r.rootRadius.dp)) // no clip: popups overflow the root (Kb:16)
            .lint("kb"),
    ) { LintParent("kb") {
        if (r.glass) GlassBackdrop(r)
        Column(Modifier.fillMaxSize()) {
            // Kb:27-31 floating handle
            if (r.floatH > 0f) {
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp, 4.dp).alpha(0.6f).background(t.barMuted, RoundedCornerShape(2.dp)))
                }
            }
            // Kb:33-144 suggestion bar (panel header while a panel is open)
            if (r.showBar) { if (r.x.panel.isNotEmpty()) KbPanelHeader(r) else KbBar(r) }
            // Kb:146-156 dev row
            if (r.x.dev) KbDevRow(r)
            // Kb:199-282 panels
            if (r.x.showPanel && r.only != "bar") KbPanelBody(r) // a bar-only render (the live bar) has no body
            // Kb:158-197 keys
            if (r.showKeys) {
                Box(Modifier.fillMaxWidth().height(r.keyAreaH.dp)) {
                    if (r.stripL.show) Box(Modifier.align(Alignment.CenterStart)) { KbSideStrip(r, r.stripL) }
                    if (r.stripR.show) Box(Modifier.align(Alignment.CenterEnd)) { KbSideStrip(r, r.stripR) }
                    // Each cap at its spec rectangle (Kb:558), edges snapped to pixels one by one: a row
                    // of fixed widths would add up each key's rounding (1.5 dp by the end of a row).
                    for ((ri, row) in r.rows.withIndex()) {
                        for (k in row.keys) if (!k.gap) KbKeyCap(r, k, ri)
                    }
                    // Kb:193-195 trackpad puck: drawn in the design gallery only; on the live keyboard the
                    // user asked for it to go (2026-11-14: "remove the blue circle, it clips")
                    if (r.trackpad && r.only != "live") {
                        Box(
                            Modifier.offset(r.tpX.dp, r.tpY.dp).size(56.dp).alpha(0.85f)
                                .cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 2f, t.linkFg)), RoundedCornerShape(50))
                                .background(t.chip, RoundedCornerShape(50)),
                        )
                    }
                }
            }
            // Kb:284-292 nav area (handle and corner icons)
            if (r.showNav) {
                Box(Modifier.fillMaxWidth().height(r.navH.dp)) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp).size(108.dp, 4.dp).alpha(0.5f)
                            .background(t.barFg, RoundedCornerShape(2.dp)),
                    )
                    if (r.navIcons) {
                        Symbol("keyboard_arrow_down", 20.sp, t.barMuted, Modifier.offset(28.dp, 2.dp).alpha(0.8f))
                        Symbol("keyboard", 20.sp, t.barMuted, Modifier.align(Alignment.TopEnd).offset((-28).dp, 2.dp).alpha(0.8f))
                    }
                }
            }
        }
        KbPopups(r)
    } }
}

/** Kb:173-180: one key; the cap is inset by mv/mh inside its touch cell. */
@Composable
private fun KbKeyCap(r: KbRender, k: KbKey, ri: Int) {
    val t = r.t
    val shape = RoundedCornerShape(r.radius.coerceAtMost(999f).dp)
    // Kb:558: where the spec puts this cap, for the lint's spacing check
    val capTop = r.floatH + r.barH + r.devH + ri * r.rowH + r.mv
    val capId = "cap:$ri:${k.id}"
    val expected = Rect(k.x + r.mh, capTop, k.x + k.w - r.mh, capTop + r.rowH - 2 * r.mv)
    val inArea = Rect(k.x + r.mh, ri * r.rowH + r.mv, k.x + k.w - r.mh, (ri + 1) * r.rowH - r.mv)
    Box(Modifier.placeAt(inArea)) {
        Box(
            Modifier.fillMaxSize().alpha(r.capOp).cssShadow(k.shadow, shape).background(k.bg, shape)
                .lint(capId, DesignLint.Kind.CAP, expected),
            contentAlignment = Alignment.Center,
        ) { LintParent(capId) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (k.icon.isNotEmpty()) {
                    Symbol(k.icon, k.iconSize.sp, k.fg, Modifier.alpha(r.labelOp), fill = k.fill)
                } else if (k.label.isNotEmpty()) {
                    DText(k.label, Modifier.alpha(r.labelOp), style = designTextStyle(k.fs.sp, k.fw, k.fg), id = "$capId/label", kind = DesignLint.Kind.LABEL)
                }
                if (k.sub.isNotEmpty()) {
                    // Kb:177
                    DText(
                        k.sub, Modifier.padding(top = 3.dp).alpha(r.labelOp),
                        style = designTextStyle(10.sp, 600, t.hint).copy(letterSpacing = 1.sp),
                        overflow = TextOverflow.Visible,
                    )
                }
            }
            if (k.hint.isNotEmpty()) {
                // Kb:178
                DText(
                    k.hint, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 4.dp).alpha(r.labelOp),
                    style = designTextStyle(t.hintFs.sp, t.hintFw, t.hint),
                    overflow = TextOverflow.Visible,
                )
            }
        } }
    }
}

/** Kb:160-166 / 186-192: landscape and one-handed side strips. */
@Composable
private fun KbSideStrip(r: KbRender, s: KbStrip) {
    Column(
        Modifier.width(s.w.dp).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        for ((icon, _) in s.icons) {
            Box(Modifier.size(48.dp).background(r.t.fn, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                Symbol(icon, 22.sp, r.t.fnFg)
            }
        }
    }
}

/** Kb:74-140: the normal bar: tools button, three slots, mic. */
@Composable
private fun KbBar(r: KbRender) {
    val t = r.t
    val actions = LocalKbActions.current
    Row(Modifier.fillMaxWidth().height(48.dp).alpha(r.barOp), verticalAlignment = Alignment.CenterVertically) {
        // Kb:76-80
        Box(Modifier.size(48.dp).tap(actions?.onLeft), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp).background(r.leftBg, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Symbol(r.leftIcon, 22.sp, r.leftFg, fill = r.leftFill)
            }
        }
        // Kb:81-90
        Row(Modifier.weight(1f).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!r.mSlots) KbBarSpecial(r)
            if (r.mSlots) KbSlots(r)
        }
        // Kb:135-139
        Box(Modifier.size(48.dp).tap(actions?.onMic), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp).background(r.micBg, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Symbol(r.micIcon, 22.sp, r.micFg, fill = r.micFill)
            }
        }
    }
}

private val POPUP_SHADOW = CssShadow.parse("0 1px 3px rgba(0,0,0,0.16), 0 6px 18px rgba(0,0,0,0.14)")
private val MENU_SHADOW = CssShadow.parse("0 1px 3px rgba(0,0,0,0.16), 0 8px 24px rgba(0,0,0,0.16)")

/** Kb:296-320: key preview, long-press bar, language menu. */
@Composable
private fun KbPopups(r: KbRender) {
    val t = r.t
    val popShape = RoundedCornerShape(16.dp)
    r.pv?.let { pv ->
        Box(
            Modifier.offset(pv.x.dp, pv.y.dp).size(pv.w.dp, 58.dp).cssShadow(POPUP_SHADOW, popShape).background(t.pop, popShape)
                .lint("preview", DesignLint.Kind.FLOATING),
            contentAlignment = Alignment.Center,
        ) { LintParent("preview") { DText(pv.label, style = designTextStyle(30.sp, pv.fw, t.popFg), overflow = TextOverflow.Visible) } }
    }
    r.lp?.let { lp ->
        Row(Modifier.offset(lp.x.dp, lp.y.dp).cssShadow(POPUP_SHADOW, popShape).background(t.pop, popShape).lint("longpress", DesignLint.Kind.FLOATING).padding(4.dp)) { LintParent("longpress") {
            for ((c, bg, fg) in lp.items) {
                Box(Modifier.size(34.dp, 46.dp).background(bg, RoundedCornerShape(12.dp)).lint("longpress:$c"), contentAlignment = Alignment.Center) { LintParent("longpress:$c") {
                    // CSS lets a long variant (".org") overflow its 34 dp cell on one line
                    DText(c, Modifier.wrapContentWidth(unbounded = true), style = designTextStyle(24.sp, 400, fg), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                } }
            }
        } }
    }
    r.lm?.let { lm ->
        Column(
            Modifier.offset(lm.x.dp, lm.y.dp).width(240.dp).cssShadow(MENU_SHADOW, popShape).background(t.pop, popShape)
                .lint("langmenu", DesignLint.Kind.FLOATING).padding(vertical = 8.dp),
        ) {
            for (m in lm.items) {
                Row(Modifier.fillMaxWidth().height(44.dp).background(m.bg).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(20.dp)) { if (m.icon.isNotEmpty()) Symbol(m.icon, 20.sp, t.linkFg) }
                    Spacer(Modifier.width(12.dp))
                    DText(m.label, Modifier.weight(1f), style = designTextStyle(15.sp, 400, t.popFg).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), overflow = TextOverflow.Visible)
                    DText(m.sub, Modifier.alpha(0.7f), style = designTextStyle(12.sp, 400, t.popFg), overflow = TextOverflow.Visible)
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(t.div))
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(20.dp)) { Symbol("settings", 20.sp, t.popFg) }
                Spacer(Modifier.width(12.dp))
                DText("Languages and layouts", style = designTextStyle(15.sp, 400, t.popFg), overflow = TextOverflow.Visible)
            }
        }
    }
}

/** CSS box-shadow layers: outer layers as drop shadows, `inset` layers as inner shadows. */
fun Modifier.cssShadow(layers: List<CssShadow>, shape: Shape): Modifier {
    var m = this
    for (l in layers.filter { !it.inset }.reversed()) {
        m = m.dropShadow(shape, Shadow(radius = l.blur.dp, spread = l.spread.dp, color = l.color, offset = DpOffset(l.x.dp, l.y.dp)))
    }
    for (l in layers.filter { it.inset }) {
        m = m.innerShadow(shape, Shadow(radius = l.blur.dp, spread = l.spread.dp, color = l.color, offset = DpOffset(l.x.dp, l.y.dp)))
    }
    return m
}

/** Lays this element out at [rect] (dp, parent-relative), each edge rounded to a whole pixel. */
fun Modifier.placeAt(rect: Rect): Modifier = layout { measurable, _ ->
    val l = (rect.left * density).roundToInt()
    val t = (rect.top * density).roundToInt()
    val w = ((rect.right * density).roundToInt() - l).coerceAtLeast(0) // a size too small for the margins draws nothing
    val h = ((rect.bottom * density).roundToInt() - t).coerceAtLeast(0)
    val p = measurable.measure(Constraints.fixed(w, h))
    layout(w, h) { p.place(l, t) }
}

/**
 * Kb:83-89 slots, with DogBoard's setting for words that don't fit, [KbRender.slotOverflow]
 * (user, 2026-11-13): "squash" narrows, then cuts in the middle, and, like LatinIME, lets the
 * centre (best) suggestion take the whole slot area when it can't fit even at [SQUASH_MIN_SCALE];
 * "fade" clips with a fading end.
 */
@Composable
private fun RowScope.KbSlots(r: KbRender) {
    val t = r.t
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.weight(1f).height(48.dp)) {
        val centre = r.slots.getOrNull(1)
        val centreAlone = r.slotOverflow == "squash" && centre != null && r.slots.size == 3 && run {
            val dividers = r.slots.count { it.showDiv }
            val slotText = (maxWidth - dividers.dp) / r.slots.size - SLOT_PADDING_H * 2
            val natural = measurer.measure(centre.text, slotStyle(centre), maxLines = 1, softWrap = false).size.width
            natural * SQUASH_MIN_SCALE > with(density) { slotText.toPx() }
        }
        val actions = LocalKbActions.current
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            for ((i, s) in (if (centreAlone) listOf(1 to centre!!) else r.slots.withIndex().map { it.index to it.value })) {
                if (s.showDiv && !centreAlone) Box(Modifier.size(1.dp, 22.dp).background(t.div))
                Box(
                    Modifier.weight(1f).height(48.dp).lint("slot:${s.text}").tap(actions?.let { a -> { a.onSlot(i) } }, actions?.let { a -> { a.onSlotLong(i) } }),
                    contentAlignment = Alignment.Center,
                ) {
                    LintParent("slot:${s.text}") { SlotText(r, s) }
                }
            }
        }
    }
}

private val SLOT_PADDING_H = 12.dp

/** Fade length for "fade" overflow. Provisional: Apple's mask length is not measured yet. */
private val FADE_LENGTH = 16.dp

private fun slotStyle(s: KbSlot) = designTextStyle(s.fs.sp, s.wt, s.fg).copy(lineHeight = 20.sp)

@Composable
private fun SlotText(r: KbRender, s: KbSlot) {
    val chip = Modifier.cssShadow(s.ring, RoundedCornerShape(50))
        .background(s.bg, RoundedCornerShape(50))
        .padding(horizontal = SLOT_PADDING_H, vertical = 6.dp)
    if (r.slotOverflow != "fade") {
        DText(s.text, chip, style = slotStyle(s), maxLines = 1, squash = true)
        return
    }
    // "fade": the word keeps its size; if it is wider than the slot, it starts at the left edge
    // and its end fades out
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(chip) {
        val natural = measurer.measure(s.text, slotStyle(s), maxLines = 1, softWrap = false).size.width
        if (natural <= constraints.maxWidth) {
            DText(s.text, style = slotStyle(s))
        } else {
            Box(
                Modifier.fillMaxWidth().clipToBounds().fadeEnd(FADE_LENGTH).lint("fade:${s.text}", DesignLint.Kind.SCROLLED),
            ) {
                LintParent("fade:${s.text}") {
                    DText(s.text, Modifier.wrapContentWidth(Alignment.Start, unbounded = true), style = slotStyle(s).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
                }
            }
        }
    }
}

/** Fades the last [length] of this element to transparent (a DstIn gradient mask, like a scroll view's edge mask). */
fun Modifier.fadeEnd(length: androidx.compose.ui.unit.Dp): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val start = (1f - length.toPx() / size.width).coerceIn(0f, 1f)
        drawRect(Brush.horizontalGradient(0f to Color.Black, start to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

/**
 * Kb:18-23: the glass theme's stand-in photo: five CSS gradients on a box 48 dp larger than the
 * keyboard on every side, blurred by blurPx, under rgba(0,0,0,dim), clipped to the root shape.
 */
@Composable
private fun GlassBackdrop(r: KbRender) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(r.rootRadius.dp))) {
        Box(
            Modifier.matchParentSize().layout { m, c ->
                val grow = 48.dp.roundToPx()
                val p = m.measure(Constraints.fixed(c.maxWidth + 2 * grow, c.maxHeight + 2 * grow))
                layout(c.maxWidth, c.maxHeight) { p.place(-grow, -grow) }
            }.blur(r.blurPx.dp, BlurredEdgeTreatment.Unbounded).drawBehind {
                // linear-gradient(170deg, #b88a5e, #4f6670): CSS angles run clockwise from "to top"
                drawRect(cssLinearGradient(170f, size, css("#b88a5e"), css("#4f6670")))
                // later CSS layers draw first: listed top layer first, so paint in reverse
                for ((cx, cy, color, stop) in listOf(
                    Quad(0.12f, 0.92f, "#5f7f5a", 0.38f), Quad(0.58f, 0.78f, "#7a5536", 0.42f),
                    Quad(0.70f, 0.30f, "#8fb3c9", 0.40f), Quad(0.22f, 0.38f, "#f3c78f", 0.34f),
                )) {
                    val center = Offset(size.width * cx, size.height * cy)
                    // circle, farthest-corner: the 100% radius reaches the farthest corner
                    val far = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
                        .maxOf { (it - center).getDistance() }
                    val c = css(color)
                    drawRect(Brush.radialGradient(0f to c, stop to c.copy(alpha = 0f), center = center, radius = far))
                }
            },
        )
        Box(Modifier.matchParentSize().background(Color(0f, 0f, 0f, r.dimA)))
    }
}

private data class Quad(val cx: Float, val cy: Float, val color: String, val stop: Float)

/** CSS linear-gradient(angle, a, b) over a box of [size]: the line runs through the centre at [deg]. */
private fun cssLinearGradient(deg: Float, size: androidx.compose.ui.geometry.Size, a: Color, b: Color): Brush {
    val rad = Math.toRadians(deg.toDouble())
    val dx = kotlin.math.sin(rad).toFloat(); val dy = -kotlin.math.cos(rad).toFloat()
    // gradient length so the corners get the end colours (CSS spec)
    val half = (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2
    val c = Offset(size.width / 2, size.height / 2)
    return Brush.linearGradient(listOf(a, b), start = c - Offset(dx, dy) * half, end = c + Offset(dx, dy) * half)
}
