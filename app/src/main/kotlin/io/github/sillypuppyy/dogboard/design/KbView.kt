package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            .background(t.bg, RoundedCornerShape(r.rootRadius.dp)), // no clip: popups overflow the root (Kb:16)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Kb:27-31 floating handle
            if (r.floatH > 0f) {
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp, 4.dp).alpha(0.6f).background(t.barMuted, RoundedCornerShape(2.dp)))
                }
            }
            // Kb:33-144 suggestion bar
            if (r.showBar) KbBar(r)
            // Kb:158-197 keys
            if (r.showKeys) {
                Box(Modifier.fillMaxWidth().height(r.keyAreaH.dp)) {
                    Row(Modifier.fillMaxSize()) {
                        if (r.stripL.show) KbSideStrip(r, r.stripL)
                        Column(Modifier.width(r.kbW.dp)) {
                            for (row in r.rows) {
                                Row(Modifier.height(r.rowH.dp)) {
                                    for (k in row.keys) {
                                        if (k.gap) Spacer(Modifier.width(k.w.dp)) else KbKeyCap(r, k)
                                    }
                                }
                            }
                        }
                        if (r.stripR.show) KbSideStrip(r, r.stripR)
                    }
                    // Kb:193-195 trackpad puck
                    if (r.trackpad) {
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
    }
}

/** Kb:173-180: one key; the cap is inset by mv/mh inside its touch cell. */
@Composable
private fun KbKeyCap(r: KbRender, k: KbKey) {
    val t = r.t
    val shape = RoundedCornerShape(r.radius.coerceAtMost(999f).dp)
    Box(Modifier.width(k.w.dp).height(r.rowH.dp).padding(vertical = r.mv.dp, horizontal = r.mh.dp)) {
        Box(
            Modifier.fillMaxSize().alpha(r.capOp).cssShadow(k.shadow, shape).background(k.bg, shape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (k.icon.isNotEmpty()) {
                    Symbol(k.icon, k.iconSize.sp, k.fg, Modifier.alpha(r.labelOp), fill = k.fill)
                } else if (k.label.isNotEmpty()) {
                    BasicText(k.label, Modifier.alpha(r.labelOp), style = designTextStyle(k.fs.sp, k.fw, k.fg), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                }
                if (k.sub.isNotEmpty()) {
                    // Kb:177
                    BasicText(
                        k.sub, Modifier.padding(top = 3.dp).alpha(r.labelOp),
                        style = designTextStyle(10.sp, 600, t.hint).copy(letterSpacing = 1.sp),
                        overflow = TextOverflow.Visible,
                    )
                }
            }
            if (k.hint.isNotEmpty()) {
                // Kb:178
                BasicText(
                    k.hint, Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 4.dp).alpha(r.labelOp),
                    style = designTextStyle(t.hintFs.sp, t.hintFw, t.hint),
                    overflow = TextOverflow.Visible,
                )
            }
        }
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
    Row(Modifier.fillMaxWidth().height(48.dp).alpha(r.barOp), verticalAlignment = Alignment.CenterVertically) {
        // Kb:76-80
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp).background(r.leftBg, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                Symbol(r.leftIcon, 22.sp, r.leftFg, fill = r.leftFill)
            }
        }
        // Kb:81-90
        Row(Modifier.weight(1f).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.mSlots) {
                for (s in r.slots) {
                    if (s.showDiv) Box(Modifier.size(1.dp, 22.dp).background(t.div))
                    Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                        BasicText(
                            s.text,
                            Modifier.cssShadow(s.ring, RoundedCornerShape(50))
                                .background(s.bg, RoundedCornerShape(50))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            style = designTextStyle(s.fs.sp, s.wt, s.fg).copy(lineHeight = 20.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Kb:135-139
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
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
            Modifier.offset(pv.x.dp, pv.y.dp).size(pv.w.dp, 58.dp).cssShadow(POPUP_SHADOW, popShape).background(t.pop, popShape),
            contentAlignment = Alignment.Center,
        ) { BasicText(pv.label, style = designTextStyle(30.sp, pv.fw, t.popFg), overflow = TextOverflow.Visible) }
    }
    r.lp?.let { lp ->
        Row(Modifier.offset(lp.x.dp, lp.y.dp).cssShadow(POPUP_SHADOW, popShape).background(t.pop, popShape).padding(4.dp)) {
            for ((c, bg, fg) in lp.items) {
                Box(Modifier.size(34.dp, 46.dp).background(bg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    // CSS lets a long variant (".org") overflow its 34 dp cell on one line
                    BasicText(c, Modifier.wrapContentWidth(unbounded = true), style = designTextStyle(24.sp, 400, fg), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                }
            }
        }
    }
    r.lm?.let { lm ->
        Column(
            Modifier.offset(lm.x.dp, lm.y.dp).width(240.dp).cssShadow(MENU_SHADOW, popShape).background(t.pop, popShape)
                .padding(vertical = 8.dp),
        ) {
            for (m in lm.items) {
                Row(Modifier.fillMaxWidth().height(44.dp).background(m.bg).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(20.dp)) { if (m.icon.isNotEmpty()) Symbol(m.icon, 20.sp, t.linkFg) }
                    Spacer(Modifier.width(12.dp))
                    BasicText(m.label, Modifier.weight(1f), style = designTextStyle(15.sp, 400, t.popFg).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Start), overflow = TextOverflow.Visible)
                    BasicText(m.sub, Modifier.alpha(0.7f), style = designTextStyle(12.sp, 400, t.popFg), overflow = TextOverflow.Visible)
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(t.div))
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(20.dp)) { Symbol("settings", 20.sp, t.popFg) }
                Spacer(Modifier.width(12.dp))
                BasicText("Languages and layouts", style = designTextStyle(15.sp, 400, t.popFg), overflow = TextOverflow.Visible)
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
