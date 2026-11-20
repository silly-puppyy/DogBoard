package io.github.sillypuppyy.dogboard.design

import androidx.compose.runtime.getValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A smart chip's longest label before "…" (ours; Gboard's clip chips: 107 dp for the whole chip). */
private val CHIP_TEXT_MAX = 150.dp

private val PILL = RoundedCornerShape(50)
private val TILE = RoundedCornerShape(16.dp)

private fun text(size: Int, weight: Int, color: Color) = designTextStyle(size.sp, weight, color)

/** Kb:38 etc.: the 48 dp "back to keyboard" button of every panel header. */
@Composable
private fun BackButton(r: KbRender) {
    Box(Modifier.size(48.dp).tap(LocalKbActions.current?.onBack), contentAlignment = Alignment.Center) { Symbol("keyboard", 24.sp, r.t.barFg) }
}

/** Kb:36-72: the bar while a panel is open. */
@Composable
fun KbPanelHeader(r: KbRender) {
    val t = r.t; val x = r.x
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            x.pClip -> {
                // Kb:37-46
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackButton(r)
                    DText("Clipboard", Modifier.padding(end = 6.dp), style = text(16, 500, t.barFg))
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(0.dp)), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Kb:41-43: the picked filter filled, the others ringed (ours filters the list)
                        val actions = LocalKbActions.current
                        for ((i, label) in listOf("All", "Links", "Media").withIndex()) {
                            val on = i == x.clipFilter
                            Chip(label, if (on) t.chip else Color.Transparent, if (on) t.chipFg else t.barFg, if (on) null else t.div,
                                Modifier.tap(actions?.let { a -> { a.onClipFilter(i) } }))
                        }
                    }
                    Box(Modifier.size(40.dp, 48.dp), contentAlignment = Alignment.Center) { Symbol("delete_sweep", 22.sp, t.barFg) }
                }
            }
            x.pEmoji -> {
                // Kb:50-56
                Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackButton(r)
                    // searching (Gboard): the header is a title; the field sits above the keyboard
                    if (x.emojiSearch) {
                        DText("Search emoji", Modifier.weight(1f), style = text(16, 500, t.barFg).copy(textAlign = TextAlign.Start))
                        return@Row
                    }
                    val actions = LocalKbActions.current
                    Row(
                        Modifier.weight(1f).height(36.dp).clip(PILL).tap(actions?.onEmojiSearch).background(t.tile, PILL).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol("search", 20.sp, t.barMuted)
                        DText("Search emoji", style = text(15, 400, t.barMuted), overflow = TextOverflow.Visible)
                    }
                    // the canvas's skin-tone dot (a static circle) is left out: as Gboard, a tone is picked by
                    // long press on each emoji and stays with it (user 2026-11-18)
                }
            }
            x.pTools -> {
                // Kb:60-64
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackButton(r)
                    DText("Tools", Modifier.weight(1f), style = text(16, 500, t.barFg).copy(textAlign = TextAlign.Start))
                    Box(Modifier.height(40.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        DText("Edit", style = text(14, 600, t.linkFg))
                    }
                }
            }
            x.pEdit -> {
                // Kb:68-71
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackButton(r)
                    DText("Text editing", style = text(16, 500, t.barFg))
                }
            }
        }
    }
}

/** Kb:41-43: 32 dp pill chip, filled or outlined (inset 1 px ring). */
@Composable
private fun Chip(label: String, bg: Color, fg: Color, ring: Color?, modifier: Modifier = Modifier) {
    Box(
        modifier.height(32.dp)
            .cssShadow(if (ring != null) listOf(CssShadow(true, 0f, 0f, 0f, 1f, ring)) else emptyList(), PILL)
            .background(bg, PILL).lint("chip:$label").padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { LintParent("chip:$label") { DText(label, style = text(13, 500, fg)) } }
}

/** Kb:92-132: the slot area's special modes. */
@Composable
fun RowScope.KbBarSpecial(r: KbRender) {
    val t = r.t; val x = r.x
    when {
        x.mTools -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            val actions = LocalKbActions.current
            val bv = r.bv // Kb:112: the segmented bar shares the row out in bevelled cells
            for (b in x.toolRow) Box(
                (if (bv.toolFlex) Modifier.weight(1f).height(48.dp) else Modifier.size(44.dp, 48.dp))
                    .cssShadow(bv.toolSh, androidx.compose.ui.graphics.RectangleShape).tap(actions?.let { a -> { a.onTool(b.icon) } }),
                contentAlignment = Alignment.Center,
            ) { TextShadows(bv.ts) { sh, _ -> Symbol(b.icon, 24.sp, bv.iconFg ?: t.barFg, shadow = sh) } }
        }
        // Kb:134-140: chips 36 dp, 999 px radius, 12/10 padding, 18 dp icon, 14/500 label, 12/400 sub at 0.75
        // Kb clips the row; ours scrolls sideways and caps a chip's text, as Gboard's clip chips (measured
        // 2026-11-15: 107 dp, "…"), so a long clip doesn't push its pieces off the bar
        x.mChips -> Row(
            Modifier.weight(1f).padding(horizontal = 4.dp).clipToBounds().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            val actions = LocalKbActions.current
            for ((i, c) in x.chips.withIndex()) {
                val first = i == 0
                val fg = if (first) t.chipFg else t.barFg
                Row(
                    Modifier.height(36.dp)
                        .then(if (first) Modifier.background(t.chip, PILL) else Modifier.cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, t.div)), PILL))
                        .clip(PILL).tap(actions?.let { a -> { a.onChip(i) } }).padding(start = 10.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    // a colour clip: its swatch with a hairline, so white and black still show (Clipboard page)
                    if (c.swatch != null) Box(Modifier.size(18.dp).background(Color(c.swatch), PILL).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, Color(0x33000000))), PILL))
                    else if (c.icon.isNotEmpty()) Symbol(c.icon, 18.sp, fg)
                    DText(c.label, Modifier.widthIn(max = CHIP_TEXT_MAX), style = text(14, 500, fg), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (c.sub.isNotEmpty()) DText(c.sub, Modifier.alpha(0.75f), style = text(12, 400, fg))
                }
            }
        }
        x.mClip -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Kb:102-105
            Row(
                Modifier.height(36.dp).tap(LocalKbActions.current?.onRecentClip).background(t.chip, PILL).padding(start = 4.dp, end = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                // the copied image itself (user 2026-11-21: the chip showed an empty preview); the placeholder until it loads
                if (x.clipMedia && x.clipThumb != null) androidx.compose.foundation.Image(x.clipThumb, null, Modifier.size(28.dp).clip(PILL),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else if (x.clipMedia) Box(Modifier.size(28.dp).clip(PILL).stripes(t.fn, t.key, 4f).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, t.div)), PILL))
                else Box(Modifier.size(6.dp))
                DText(x.clipLabel, style = text(15, 500, t.chipFg), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        x.mAutofill -> Row(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            // Kb:111-112
            Row(
                Modifier.height(36.dp).background(t.chip, PILL).padding(start = 10.dp, end = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("key", 20.sp, t.chipFg)
                DText("alex@example.com", style = text(14, 500, t.chipFg))
            }
            Box(
                Modifier.height(36.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, t.div)), PILL).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { DText("More", style = text(14, 500, t.barFg)) }
        }
        x.mRemoved -> Row(Modifier.weight(1f).padding(start = 8.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // Kb:117-120
            val actions = LocalKbActions.current
            DText("“${x.removedWord}” won’t be suggested again", style = text(14, 400, t.barMuted))
            Box(Modifier.height(40.dp).tap(actions?.onUndo).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { DText("Undo", style = text(14, 600, t.linkFg)) }
        }
        x.mVoice -> Row(Modifier.weight(1f).padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Kb:124-127
            Row(Modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                for (h in listOf(8, 18, 12, 22, 10)) Box(Modifier.size(3.dp, h.dp).background(t.linkFg, RoundedCornerShape(2.dp)))
            }
            DText("Listening…", style = text(15, 400, t.barFg))
            DText("English (US), on device", style = text(13, 400, t.barMuted))
        }
        x.mIncog -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Kb:131
            DText("Incognito: nothing is learned or saved", style = text(14, 400, t.barMuted))
        }
        else -> Spacer(Modifier.weight(1f))
    }
}

/** Kb:146-155: the developer row. */
@Composable
fun KbDevRow(r: KbRender) {
    val shape = RoundedCornerShape(r.radius.coerceAtMost(999f).dp)
    Row(
        // Kb:147-149: keys keep their size (flex: none) and the row hides what doesn't fit; here it scrolls
        Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (d in r.x.devKeys) {
            Box(
                Modifier.widthIn(min = 38.dp).height(36.dp).background(d.bg, shape).lint("dev:${d.label}${d.icon}", DesignLint.Kind.SCROLLED).padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) { LintParent("dev:${d.label}${d.icon}") {
                if (d.isIcon) Symbol(d.icon, 20.sp, d.fg) else DText(d.label, style = text(14, 500, d.fg))
                if (d.ind) {
                    // Kb:152: indicator centred, 4 px above the bottom
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(d.indW.dp, 2.dp).background(d.fg, RoundedCornerShape(1.dp)))
                }
            } }
        }
    }
}

/** Kb:199-281: panel bodies, in place of the keys. */
@Composable
fun KbPanelBody(r: KbRender) {
    val t = r.t; val x = r.x
    Box(Modifier.fillMaxWidth().height(r.keyAreaH.dp)) {
        if (x.clipList) {
            // the clipboard as rows (KbClipRows.kt; canvas "Clipboard: columns vs rows", user 2026-11-20); a tap
            // pastes through the panel's actions when the live keyboard gives no row actions of its own
            val kb = LocalKbActions.current
            val rowActions = LocalKbClipActions.current ?: androidx.compose.runtime.remember(kb) { KbClipActions(onPaste = { i -> kb?.onClip?.invoke(i) }) }
            androidx.compose.runtime.CompositionLocalProvider(LocalKbClipActions provides rowActions) {
                KbClipList(t, x.clipRows, initialHeld = x.clipHeld)
            }
        }
        if (x.clipMsg) {
            // Kb:216-221
            Column(
                Modifier.fillMaxSize().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Symbol(x.clipMsgIcon, 32.sp, t.barMuted)
                DText(x.clipMsgTitle, style = text(16, 500, t.barFg))
                DText(x.clipMsgBody, style = text(13, 400, t.barMuted).copy(lineHeight = (13 * 1.4).sp), maxLines = 4)
                if (x.clipMsgBtn) {
                    Box(
                        Modifier.padding(top = 4.dp).height(40.dp).background(t.act, PILL)
                            .tap(LocalKbActions.current?.let { a -> { a.onTool("history_on") } }).padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        DText("Turn on history", style = text(14, 600, t.actFg))
                    }
                }
            }
        }
        if (x.pEmoji) EmojiPanel(r)
        if (x.pTools) {
            // Kb:252-263
            Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
                val actions = LocalKbActions.current
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in x.tools.chunked(4)) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (o in row) {
                                Box(Modifier.weight(1f).fillMaxHeight().background(t.tile, TILE).lint("tool:${o.label}").tap(actions?.let { a -> { a.onTool(o.icon) } })) { LintParent("tool:${o.label}") {
                                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Symbol(o.icon, 20.sp, t.tileFg)
                                        DText(o.label, style = text(11, 500, t.tileFg), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                                    }
                                    if (o.pinned) Symbol("push_pin", 13.sp, t.linkFg, Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 6.dp), fill = 1)
                                } }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                    DText("Drag to reorder. Pinned tools show in the bar.", style = text(12, 400, t.barMuted))
                }
            }
        }
        if (x.pEdit) {
            // Kb:267-278: 3:2 columns
            val actions = LocalKbActions.current
            Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in x.pad.chunked(3)) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in row) Box(
                            Modifier.weight(1f).fillMaxHeight().background(p.bg, TILE)
                                .tap(actions?.takeIf { p.icon.isNotEmpty() || p.text.isNotEmpty() }?.let { a -> { a.onTool(p.icon.ifEmpty { p.text }) } }),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (p.icon.isNotEmpty()) Symbol(p.icon, 24.sp, p.fg) else if (p.text.isNotEmpty()) DText(p.text, style = text(13, 500, p.fg))
                        }
                    }
                }
                Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in x.editOps.chunked(2)) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (o in row) Column(
                            Modifier.weight(1f).fillMaxHeight().background(t.tile, TILE).lint("edit:${o.label}")
                                .tap(actions?.let { a -> { a.onTool(o.icon) } }),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            Symbol(o.icon, 20.sp, t.tileFg)
                            LintParent("edit:${o.label}") { DText(o.label, style = text(11, 500, t.tileFg)) }
                        }
                    }
                }
            }
        }
    }
}

/** Kb:224-248: emoji panel. */
@Composable
private fun EmojiPanel(r: KbRender) {
    val t = r.t; val x = r.x
    val shape = RoundedCornerShape(r.radius.coerceAtMost(999f).dp)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            Column {
                for ((title, list) in listOf("Recently used" to x.emojiA, "Smileys and people" to x.emojiB)) {
                    Box(Modifier.height(20.dp).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) { DText(title, style = text(12, 500, t.barMuted)) }
                    for (row in list.chunked(8)) Row(Modifier.height(32.dp)) {
                        for (e in row) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { DText(e, style = designTextStyle(24.sp, 400, Color.Black)) }
                    }
                }
            }
            if (x.skinPicker) {
                // Kb:236-238
                Row(
                    Modifier.offset(x.skinX.dp, (-36).dp)
                        .cssShadow(CssShadow.parse("0 2px 6px rgba(0,0,0,0.18), 0 8px 24px rgba(0,0,0,0.14)"), TILE)
                        .background(t.pop, TILE).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for ((e, bg) in x.skins) Box(Modifier.size(38.dp, 44.dp).background(bg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        DText(e, style = designTextStyle(26.sp, 400, Color.Black))
                    }
                }
            }
        }
        // Kb:241-247
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.height(36.dp).background(t.fn, shape).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { DText("ABC", style = text(14, 500, t.fnFg)) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceAround) {
                for (c in x.emojiCats) Box(Modifier.size(28.dp, 36.dp), contentAlignment = Alignment.Center) { if (c.icon == "pets") PawIcon(18.dp, 17.dp, c.fg) else Symbol(c.icon, 20.sp, c.fg, fill = c.fill) }
            }
            Box(Modifier.size(44.dp, 36.dp).background(t.fn, shape), contentAlignment = Alignment.Center) { Symbol("backspace", 20.sp, t.fnFg) }
        }
    }
}

/** CSS repeating-linear-gradient(135deg, a 0 [band]px, b [band]px 2[band]px), drawn as rotated bands. */
fun Modifier.stripes(a: Color, b: Color, band: Float): Modifier = drawBehind {
    drawRect(a)
    val step = band.dp.toPx()
    val diag = size.width + size.height
    rotate(45f) {
        var x = -diag + step
        while (x < diag) {
            drawRect(b, topLeft = androidx.compose.ui.geometry.Offset(x, -diag), size = androidx.compose.ui.geometry.Size(step, diag * 3))
            x += step * 2
        }
    }
}
