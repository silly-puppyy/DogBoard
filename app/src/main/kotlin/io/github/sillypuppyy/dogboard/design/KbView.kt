package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.node.invalidateMeasurement
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
    /** The recent-copy chip tapped: paste it (Panels:103). */
    val onRecentClip: () -> Unit = {},
    /** A smart chip tapped, by its position (Kb:136). */
    val onChip: (Int) -> Unit = {},
    val onLeft: () -> Unit = {},
    val onMic: () -> Unit = {},
    /** A tool tapped in the bar's tool row or the tools panel, by its icon name (Kb:663-668). */
    val onTool: (String) -> Unit = {},
    /** The panel header's back button (Kb:37-46). */
    val onBack: () -> Unit = {},
    /** A clipboard tile, by its position in the list. */
    val onClip: (Int) -> Unit = {},
    /** A clipboard filter chip (0 All, 1 Links, 2 Media). */
    val onClipFilter: (Int) -> Unit = {},
    /** The emoji header's Search pill. */
    val onEmojiSearch: () -> Unit = {},
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
            // no clip: popups overflow the root (Kb:16); the live keyboard's gradient is the window's, drawn once behind
            // bar and keys (each painting its own squeezed the whole gradient into its height)
            .then(if (r.windowBg) Modifier else Modifier.themeBg(t, RoundedCornerShape(r.rootRadius.dp)))
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
            if (r.showBar) Box(
                // Kb:34: the bar's own background and bevel (segmented bar only)
                Modifier.fillMaxWidth().height(48.dp).cssShadow(r.bv.shadow, androidx.compose.ui.graphics.RectangleShape)
                    .then(r.bv.bg?.let { Modifier.background(it.brush()) } ?: Modifier),
            ) { if (r.x.panel.isNotEmpty()) KbPanelHeader(r) else KbBar(r) }
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
                    // the caps get only what they draw with, compared by value: a press redraws one cap,
                    // not the keyboard (every press, release and preview-fade frame recomposed all of
                    // them: 5 ms of recomposition + 4 ms of layout a frame on the phone)
                    val frame = CapFrame(r.radius, r.rowTops, r.rowHs, r.rowH, r.floatH + r.barH + r.devH, r.mv, r.mh, r.capOp, r.labelOp)
                    for ((ri, row) in r.rows.withIndex()) {
                        for (k in row.keys) if (!k.gap) KbKeyCap(frame, t, k, ri)
                    }
                    // Kb:193-195 trackpad puck: drawn in the design gallery only; on the live keyboard the
                    // no blue circle under the finger: it clipped (2026-11-14)
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
/** What every cap shares from the render (geometry and opacities), compared by value. */
@androidx.compose.runtime.Immutable
private data class CapFrame(
    val radius: Float, val rowTops: List<Float>, val rowHs: List<Float>, val rowH: Float, val top: Float,
    val mv: Float, val mh: Float, val capOp: Float, val labelOp: Float,
)

@Composable
private fun KbKeyCap(r: CapFrame, t: KbTokens, k: KbKey, ri: Int) {
    io.github.sillypuppyy.dogboard.perf.JankWatch.capsComposed.incrementAndGet() // perf counters (debug logs only)
    val shape = if (k.pill) RoundedCornerShape(50) else RoundedCornerShape(r.radius.coerceAtMost(999f).dp)
    // Kb:558: where the spec puts this cap, for the lint's spacing check
    val rowTop = r.rowTops.getOrElse(ri) { ri * r.rowH }; val rowH = r.rowHs.getOrElse(ri) { r.rowH }
    val capTop = r.top + rowTop + r.mv
    val capId = "cap:$ri:${k.id}"
    val expected = Rect(k.x + r.mh, capTop, k.x + k.w - r.mh, capTop + rowH - 2 * r.mv)
    val inArea = Rect(k.x + r.mh, rowTop + r.mv, k.x + k.w - r.mh, rowTop + rowH - r.mv)
    k.face?.let { f -> SculptedCap(r, t, k, f, inArea, shape, capId, expected); return }
    // its own layer: a cap's drawing is recorded once and reused when something else in the keyboard changes (the
    // phone's slow frames redrew all 32 caps, text included, on every key press: 2026-11-21)
    Box(Modifier.placeAt(inArea).graphicsLayer()) {
        Box(
            Modifier.fillMaxSize().drawBehind { io.github.sillypuppyy.dogboard.perf.JankWatch.capsDrawn.incrementAndGet() }.alpha(r.capOp).cssShadow(k.shadow, shape)
                .then(k.bgGradient?.let { Modifier.background(it.brush(), shape) } ?: Modifier.background(k.bg, shape))
                .lint(capId, DesignLint.Kind.CAP, expected),
            contentAlignment = Alignment.Center,
        ) { LintParent(capId) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (k.icon == "pets") {
                    PawIcon(22.dp, 21.dp, k.fg, Modifier.alpha(r.labelOp))  // Kb:202
                } else if (k.icon.isNotEmpty()) {
                    TextShadows(k.ts) { sh, top -> Symbol(k.icon, k.iconSize.sp, k.fg, Modifier.alpha(r.labelOp), fill = k.fill, shadow = sh) }
                } else if (k.label.isNotEmpty()) {
                    TextShadows(k.ts) { sh, top ->
                        val style = designTextStyle(k.fs.sp, k.fw, k.fg).copy(shadow = sh)
                        if (top) DText(k.label, Modifier.alpha(r.labelOp), style = style, id = "$capId/label", kind = DesignLint.Kind.LABEL)
                        else DText(k.label, Modifier.alpha(r.labelOp), style = style)
                    }
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
                    style = designTextStyle(t.hintFs.sp, t.hintFw, k.hintFg),
                    overflow = TextOverflow.Visible,
                )
            }
        } }
    }
}

/**
 * Canvas Kb mech: a sculpted cap. The cap's sides fill the key (its skirt gradient and contact shadow); four slopes run
 * from the face's corners to the cap's (light from the back: back slope brightest, front in shade); the face sits on
 * them, dished (its own gradient and inset shading), with the legends printed on it. A press sinks the cap.
 */
@Composable
private fun SculptedCap(r: CapFrame, t: KbTokens, k: KbKey, f: KeyFace, inArea: Rect, shape: androidx.compose.ui.graphics.Shape, capId: String, expected: Rect) {
    val faceShape = RoundedCornerShape(f.rad.dp)
    Box(Modifier.placeAt(inArea).graphicsLayer { translationY = f.dy.dp.toPx() }) {
        Box(
            Modifier.fillMaxSize().alpha(r.capOp).cssShadow(k.shadow, shape)
                .then(androidx.compose.ui.Modifier.clip(shape))
                .then(k.bgGradient?.let { Modifier.background(it.brush()) } ?: Modifier.background(k.bg))
                .drawBehind {
                    val w = size.width; val h = size.height
                    drawRect(GRAIN)
                    val L = f.l.dp.toPx(); val R = w - f.r.dp.toPx(); val T = f.t.dp.toPx(); val B = h - f.b.dp.toPx()
                    fun poly(vararg p: Float) = androidx.compose.ui.graphics.Path().apply { moveTo(p[0], p[1]); var i = 2; while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }; close() }
                    drawPath(poly(0f, 0f, w, 0f, R, T, L, T), f.bevel[0])
                    drawPath(poly(w, 0f, w, h, R, B, R, T), f.bevel[1])
                    drawPath(poly(0f, 0f, L, T, L, B, 0f, h), f.bevel[3])
                    drawPath(poly(L, B, R, B, w, h, 0f, h), androidx.compose.ui.graphics.Brush.verticalGradient(listOf(f.bevel[2], f.bevel[4]), startY = B, endY = h))
                }
                .lint(capId, DesignLint.Kind.CAP, expected),
        ) { LintParent(capId) {
            Box(
                Modifier.padding(start = f.l.dp, end = f.r.dp, top = f.t.dp, bottom = f.b.dp).fillMaxSize()
                    .cssShadow(f.sh.filter { !it.inset }, faceShape).clip(faceShape).background(f.bg.brush())
                    .then(if (f.sheen > 0f) Modifier.background(Color.White.copy(alpha = f.sheen)) else Modifier)
                    .background(GRAIN)
                    .cssShadow(f.sh.filter { it.inset }, faceShape).alpha(r.labelOp),
            ) {
                val ink = Modifier.alpha(1f)
                if (f.tl.isNotEmpty()) DText(f.tl, ink.align(Alignment.TopStart).padding(start = 5.dp, top = 4.dp),
                    style = designTextStyle(f.tlFs.sp, 500, k.fg).copy(shadow = androidx.compose.ui.graphics.Shadow(f.ink, blurRadius = 1f)), overflow = TextOverflow.Visible)
                if (f.word.isNotEmpty()) DText(f.word, ink.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 4.dp),
                    style = designTextStyle(f.wordFs.sp, 500, k.fg), maxLines = 1, overflow = TextOverflow.Visible)
                if (f.icon.isNotEmpty()) Symbol(f.icon, f.icFs.sp, k.fg, ink.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 3.dp))
                if (f.led != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 5.dp).size(3.dp).cssShadow(f.ledGlow, CircleShape).background(f.led, CircleShape))
                if (k.hint.isNotEmpty()) DText(k.hint, Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 4.dp),
                    style = designTextStyle(10.sp, 500, k.hintFg), overflow = TextOverflow.Visible)
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
        val bv = r.bv
        Box(
            Modifier.size(48.dp).tap(actions?.onLeft).then(bv.leftCell?.let { Modifier.background(it.brush()) } ?: Modifier)
                .cssShadow(bv.leftSh, androidx.compose.ui.graphics.RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(36.dp).background(r.leftBg, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                TextShadows(bv.ts) { sh, _ -> Symbol(r.leftIcon, 22.sp, r.leftFg, fill = r.leftFill, shadow = sh) }
            }
        }
        // Kb:81-90
        Row(Modifier.weight(1f).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!r.mSlots) KbBarSpecial(r)
            if (r.mSlots) KbSlots(r)
        }
        // Kb:135-139
        Box(Modifier.size(48.dp).tap(actions?.onMic).cssShadow(bv.micSh, androidx.compose.ui.graphics.RectangleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp).background(r.micBg, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                TextShadows(bv.ts) { sh, _ -> Symbol(r.micIcon, 22.sp, r.micFg, fill = r.micFill, shadow = sh) }
            }
        }
    }
}

private val POPUP_SHADOW = CssShadow.parse("0 1px 3px rgba(0,0,0,0.16), 0 6px 18px rgba(0,0,0,0.14)")
private val MENU_SHADOW = CssShadow.parse("0 1px 3px rgba(0,0,0,0.16), 0 8px 24px rgba(0,0,0,0.16)")

/** Kb:296-320: key preview, long-press bar, language menu. */
/**
 * Kb mech: a faint matte grain for sculpted caps (the canvas's SVG fractal noise at 7 % white): one small tile of
 * random specks made once, repeated by a shader.
 */
private val GRAIN: Brush by lazy {
    val n = 128; val rnd = java.util.Random(7)
    val px = IntArray(n * n) { ((rnd.nextFloat() * 0.07f * 255).toInt() shl 24) or 0xFFFFFF }
    val bmp = android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(bmp, androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated))
}

/** A cap lifted off the plate (Mechanical's long-press choices): its slopes, face and [label], casting a shadow. */
@Composable
private fun RaisedCap(t: KbTokens, face: CapFace, label: String, w: Float, h: Float, chosen: Boolean, fg: Color) {
    val capShape = RoundedCornerShape(7.dp); val faceShape = RoundedCornerShape(4.dp)
    // the chosen one wholly in the accent (the same size as its neighbours: one cell each; an orange top alone looked
    // worse, 2026-11-24)
    val bv = face.bevel.getValue(if (chosen) "act" else "key")
    val skirt = t.grad[if (chosen) "actG" else "keyG"]
    Box(
        Modifier.size(w.dp, h.dp).cssShadow(RAISED_CAP_SHADOW, capShape).clip(capShape)
            .then(skirt?.let { Modifier.background(it.brush()) } ?: Modifier.background(if (chosen) t.act else t.key))
            .drawBehind {
                val sw = size.width; val sh = size.height
                val L = 4.dp.toPx(); val R = sw - 4.dp.toPx(); val T = 2.5f * density; val B = sh - 6.dp.toPx()
                fun poly(vararg q: Float) = androidx.compose.ui.graphics.Path().apply { moveTo(q[0], q[1]); var i = 2; while (i < q.size) { lineTo(q[i], q[i + 1]); i += 2 }; close() }
                drawPath(poly(0f, 0f, sw, 0f, R, T, L, T), bv[0]); drawPath(poly(sw, 0f, sw, sh, R, B, R, T), bv[1])
                drawPath(poly(0f, 0f, L, T, L, B, 0f, sh), bv[3])
                drawPath(poly(L, B, R, B, sw, sh, 0f, sh), Brush.verticalGradient(listOf(bv[2], bv[4]), startY = B, endY = sh))
            },
    ) {
        Box(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.5.dp, bottom = 6.dp).fillMaxSize().clip(faceShape)
            .background((if (chosen) face.act else face.key).brush()).cssShadow(face.sh.filter { it.inset }, faceShape),
            contentAlignment = Alignment.Center) {
            val single = label.codePointCount(0, label.length) <= 1
            DText(label, style = designTextStyle(if (single) 20.sp else 16.sp, 500, fg), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
        }
    }
}

/** Kb mech: the raised cap's shadow (it floats above the keys). */
private val RAISED_CAP_SHADOW = CssShadow.parse("0 0 0 0.5px rgba(0,0,0,0.9), 0 6px 10px rgba(0,0,0,0.55), 0 2px 3px rgba(0,0,0,0.5)")

@Composable
private fun KbPopups(r: KbRender) {
    val t = r.t
    val popShape = RoundedCornerShape(16.dp)
    // canvas Kb mech: on sculpted caps the pop-up is a raised copy of the cap (same slopes and face, lifted)
    val face = t.face
    if (face != null) r.pv?.let { pv ->
        val capShape = RoundedCornerShape(9.dp); val faceShape = RoundedCornerShape(5.dp)
        val bv = face.bevel.getValue("key")
        Box(
            Modifier.offset(pv.x.dp, pv.y.dp).size(pv.w.dp, 58.dp).graphicsLayer { alpha = pv.alpha }.cssShadow(RAISED_CAP_SHADOW, capShape)
                .clip(capShape).then(t.grad["keyG"]?.let { Modifier.background(it.brush()) } ?: Modifier.background(t.key))
                .drawBehind {
                    val w = size.width; val h = size.height
                    val L = 5.dp.toPx(); val R = w - 5.dp.toPx(); val T = 3.dp.toPx(); val B = h - 8.dp.toPx()
                    fun poly(vararg q: Float) = androidx.compose.ui.graphics.Path().apply { moveTo(q[0], q[1]); var i = 2; while (i < q.size) { lineTo(q[i], q[i + 1]); i += 2 }; close() }
                    drawPath(poly(0f, 0f, w, 0f, R, T, L, T), bv[0]); drawPath(poly(w, 0f, w, h, R, B, R, T), bv[1])
                    drawPath(poly(0f, 0f, L, T, L, B, 0f, h), bv[3])
                    drawPath(poly(L, B, R, B, w, h, 0f, h), Brush.verticalGradient(listOf(bv[2], bv[4]), startY = B, endY = h))
                }
                .lint("preview", DesignLint.Kind.FLOATING),
        ) { LintParent("preview") {
            Box(Modifier.padding(start = 5.dp, end = 5.dp, top = 3.dp, bottom = 8.dp).fillMaxSize().clip(faceShape).background(face.key.brush())
                .cssShadow(face.sh.filter { it.inset }, faceShape)) {
                DText(pv.label, Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 6.dp), style = designTextStyle(22.sp, 500, t.keyFg), overflow = TextOverflow.Visible)
            }
        } }
    }
    if (face == null) r.pv?.let { pv ->
        Box(
            Modifier.offset(pv.x.dp, pv.y.dp).size(pv.w.dp, 58.dp).graphicsLayer { alpha = pv.alpha }.cssShadow(POPUP_SHADOW, popShape).background(t.pop, popShape)
                .lint("preview", DesignLint.Kind.FLOATING),
            contentAlignment = Alignment.Center,
        ) { LintParent("preview") { DText(pv.label, style = designTextStyle(30.sp, pv.fw, t.popFg), overflow = TextOverflow.Visible) } }
    }
    if (face != null) r.lp?.let { lp ->
        // sculpted caps: each choice a raised copy of a cap floating over the keys, the chosen one in the accent's
        // colours (the canvas's raised cap was its tap preview; here the cap sinks on a tap and floats up on a hold)
        val rows = (lp.cells.maxOfOrNull { it.row } ?: 0) + 1
        Box(Modifier.offset(lp.x.dp, lp.y.dp).size(lp.w.dp, lp.h.dp).lint("longpress", DesignLint.Kind.FLOATING)) { LintParent("longpress") {
            for ((i, item) in lp.items.withIndex()) {
                val c = item.first; val chosen = item.second != Color.Transparent
                val cell = lp.cells.getOrNull(i) ?: continue
                val top = KbSpec.LP_PAD + (rows - 1 - cell.row) * KbSpec.LP_CELL_H
                Box(Modifier.offset((cell.x - lp.x + 1).dp, (top + 1).dp).lint("longpress:$c")) {
                    RaisedCap(t, face, c, (cell.w - 2), KbSpec.LP_CELL_H - 2, chosen, if (chosen) t.actFg else t.keyFg)
                }
            }
        } }
    }
    if (face == null) r.lp?.let { lp ->
        // cells sized to their text, rows of 8, the second row above (designer answers §6); the
        // cells come from KbSpec.longPressCells, the same geometry the finger selects with
        val rows = (lp.cells.maxOfOrNull { it.row } ?: 0) + 1
        Box(Modifier.offset(lp.x.dp, lp.y.dp).size(lp.w.dp, lp.h.dp).cssShadow(POPUP_SHADOW, popShape).background(t.pop, popShape).lint("longpress", DesignLint.Kind.FLOATING)) { LintParent("longpress") {
            for ((i, item) in lp.items.withIndex()) {
                val (c, bg, fg) = item
                val cell = lp.cells.getOrNull(i) ?: continue
                val top = KbSpec.LP_PAD + (rows - 1 - cell.row) * KbSpec.LP_CELL_H
                Box(
                    Modifier.offset((cell.x - lp.x).dp, top.dp).size(cell.w.dp, KbSpec.LP_CELL_H.dp).background(bg, RoundedCornerShape(12.dp)).lint("longpress:$c"),
                    contentAlignment = Alignment.Center,
                ) { LintParent("longpress:$c") {
                    val single = c.codePointCount(0, c.length) <= 1
                    DText(c, style = designTextStyle(if (single) 24.sp else 20.sp, 400, fg), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
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
        // a hard outer ring (`0 0 0 3px`, selection rings): the shape grown by the spread with its corners grown too,
        // as CSS draws it; the drop shadow kept the corner radius, so a ring around a circle came out squarish
        // ("rounded corners look weird on the photo theme page")
        m = if (l.blur == 0f && l.x == 0f && l.y == 0f && l.spread > 0f && shape is androidx.compose.foundation.shape.CornerBasedShape) m.ring(shape, l.spread.dp, l.color)
            else m.dropShadow(shape, Shadow(radius = l.blur.dp, spread = l.spread.dp, color = l.color, offset = DpOffset(l.x.dp, l.y.dp)))
    }
    for (l in layers.filter { it.inset }) {
        // a hard ring (`inset 0 0 0 1px`, the design's outline) is a border: Compose's inner shadow keeps the full
        // corner radius on its inner edge where CSS shrinks it by the spread, so the ring grew thick at rounded
        // corners ("the border seems thicker at the rounded portion")
        m = if (l.blur == 0f && l.x == 0f && l.y == 0f && l.spread > 0f) m.border(l.spread.dp, l.color, shape)
            else m.innerShadow(shape, Shadow(radius = l.blur.dp, spread = l.spread.dp, color = l.color, offset = DpOffset(l.x.dp, l.y.dp)))
    }
    return m
}

/** A ring [width] wide outside a rounded [shape], its corners [shape]'s grown by [width] (CSS box-shadow spread). */
private fun Modifier.ring(shape: androidx.compose.foundation.shape.CornerBasedShape, width: androidx.compose.ui.unit.Dp, color: Color): Modifier =
    drawBehind {
        val w = width.toPx()
        fun corner(c: androidx.compose.foundation.shape.CornerSize) = c.toPx(size, this) + w
        val outer = androidx.compose.foundation.shape.RoundedCornerShape(
            corner(shape.topStart).toDp(), corner(shape.topEnd).toDp(), corner(shape.bottomEnd).toDp(), corner(shape.bottomStart).toDp(),
        ).createOutline(androidx.compose.ui.geometry.Size(size.width + 2 * w, size.height + 2 * w), layoutDirection, this)
        val inner = shape.createOutline(size, layoutDirection, this)
        val innerPath = androidx.compose.ui.graphics.Path().apply { addOutline(inner) }
        clipPath(innerPath, androidx.compose.ui.graphics.ClipOp.Difference) {
            translate(-w, -w) { drawOutline(outer, color) }
        }
    }

/** Lays this element out at [rect] (dp, parent-relative), each edge rounded to a whole pixel. */
fun Modifier.placeAt(rect: Rect): Modifier = this then PlaceAt(rect)

/**
 * [placeAt] as a node element compared by its rectangle: a `layout {}` lambda is a new modifier on every
 * render, so every key was measured again on each keystroke (2026-11-20 JankWatch samples: a quarter of
 * the slow typing frames); an unchanged rectangle now keeps the node and its measurement.
 */
private data class PlaceAt(val rect: Rect) : androidx.compose.ui.node.ModifierNodeElement<PlaceAtNode>() {
    override fun create() = PlaceAtNode(rect)
    override fun update(node: PlaceAtNode) {
        if (node.rect != rect) { node.rect = rect; node.invalidateMeasurement() }
    }
}

private class PlaceAtNode(var rect: Rect) : Modifier.Node(), androidx.compose.ui.node.LayoutModifierNode {
    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurable: androidx.compose.ui.layout.Measurable, constraints: Constraints,
    ): androidx.compose.ui.layout.MeasureResult {
        val l = (rect.left * density).roundToInt()
        val t = (rect.top * density).roundToInt()
        val w = ((rect.right * density).roundToInt() - l).coerceAtLeast(0) // a size too small for the margins draws nothing
        val h = ((rect.bottom * density).roundToInt() - t).coerceAtLeast(0)
        val p = measurable.measure(Constraints.fixed(w, h))
        return layout(w, h) { p.place(l, t) }
    }
}

/**
 * Kb:83-89 slots, with DogBoard's setting for words that don't fit, [KbRender.slotOverflow]
 *: "squash" narrows, then cuts in the middle, and, like LatinIME, lets the
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
            // kept per text: measured on every recomposition it was the suggestion bar's largest cost (phone stall samples 2026-11-21)
            val style = slotStyle(centre)
            val quick = quickTextWidth(centre.text, style)
            val natural = if (quick >= 0f) quick else androidx.compose.runtime.remember(centre.text, style) { measurer.measure(centre.text, style, maxLines = 1, softWrap = false).size.width.toFloat() }
            natural * SQUASH_MIN_SCALE > with(density) { slotText.toPx() }
        }
        val actions = LocalKbActions.current
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            for ((i, s) in (if (centreAlone) listOf(1 to centre!!) else r.slots.withIndex().map { it.index to it.value })) {
                // Kb:102: divider (22 dp, or the segmented bar's full-height groove)
                if (s.showDiv && !centreAlone) Box(Modifier.size(1.dp, s.divH.dp).cssShadow(s.divSh, androidx.compose.ui.graphics.RectangleShape).background(s.divBg))
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .then(s.cellBg?.let { Modifier.background(it.brush()) } ?: Modifier).cssShadow(s.cellSh, androidx.compose.ui.graphics.RectangleShape)
                        .lint("slot:${s.text}").tap(actions?.let { a -> { a.onSlot(i) } }, actions?.let { a -> { a.onSlotLong(i) } }),
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
    // outer shadows behind the chip, inset ones over its fill (Mechanical's well)
    val chip = Modifier.cssShadow(s.ring.filter { !it.inset }, RoundedCornerShape(50))
        .then(s.bgG?.let { Modifier.background(it.brush(), RoundedCornerShape(50)) } ?: Modifier.background(s.bg, RoundedCornerShape(50)))
        .cssShadow(s.ring.filter { it.inset }, RoundedCornerShape(50))
        .padding(horizontal = SLOT_PADDING_H, vertical = 6.dp)
    if (r.slotOverflow != "fade") {
        if (s.ts.isEmpty()) DText(s.text, chip, style = slotStyle(s), maxLines = 1, squash = true)
        else Box(chip) { TextShadows(s.ts) { sh, _ -> DText(s.text, style = slotStyle(s).copy(shadow = sh), maxLines = 1, squash = true) } }
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

/** Kb:16 `background: t.bg`: the theme's colour, or its gradient (Kb:522-530). */
fun Modifier.themeBg(t: KbTokens, shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape): Modifier =
    t.bgGradient?.let { background(it.brush(), shape) } ?: background(t.bg, shape)

/**
 * CSS `text-shadow` with several layers: a Compose text style takes one shadow, so each layer is a
 * copy of the label under the next, the first (top) layer last, then the label itself on top.
 */
@Composable
fun TextShadows(layers: List<CssShadow>, label: @Composable (shadow: androidx.compose.ui.graphics.Shadow?, top: Boolean) -> Unit) {
    if (layers.isEmpty()) { label(null, true); return }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Box(contentAlignment = Alignment.Center) {
        for (l in layers.reversed()) label(l.textShadow(density), false)
        label(null, true)
    }
}

/** CSS blur b is a Gaussian of sigma b / 2; Android's shadow radius r gives sigma 0.57735 r + 0.5. */
fun CssShadow.textShadow(density: Float) = androidx.compose.ui.graphics.Shadow(
    color, androidx.compose.ui.geometry.Offset(x * density, y * density),
    (((blur * density / 2) - 0.5f) / 0.57735f).coerceAtLeast(0f),
)

