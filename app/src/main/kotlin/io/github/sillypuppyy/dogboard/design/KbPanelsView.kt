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
                        Chip("All", t.chip, t.chipFg, null)
                        Chip("Text", Color.Transparent, t.barFg, t.div)
                        Chip("Images", Color.Transparent, t.barFg, t.div)
                    }
                    Box(Modifier.size(40.dp, 48.dp), contentAlignment = Alignment.Center) { Symbol("delete_sweep", 22.sp, t.barFg) }
                }
            }
            x.pEmoji -> {
                // Kb:50-56
                Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackButton(r)
                    Row(
                        Modifier.weight(1f).height(36.dp).background(t.tile, PILL).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol("search", 20.sp, t.barMuted)
                        DText("Search emoji", style = text(15, 400, t.barMuted), overflow = TextOverflow.Visible)
                    }
                    Box(Modifier.size(44.dp, 48.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(22.dp)
                                .cssShadow(listOf(CssShadow(false, 0f, 0f, 0f, 3f, t.div), CssShadow(false, 0f, 0f, 0f, 2f, t.bg)).reversed(), PILL)
                                .background(css("#e0b48c"), PILL),
                        )
                    }
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
private fun Chip(label: String, bg: Color, fg: Color, ring: Color?) {
    Box(
        Modifier.height(32.dp)
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
            for (b in x.toolRow) Box(
                Modifier.size(44.dp, 48.dp).tap(actions?.let { a -> { a.onTool(b.icon) } }),
                contentAlignment = Alignment.Center,
            ) { Symbol(b.icon, 24.sp, t.barFg) }
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
                    Symbol(c.icon, 18.sp, fg)
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
                if (x.clipMedia) Box(Modifier.size(28.dp).clip(PILL).stripes(t.fn, t.key, 4f).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, t.div)), PILL))
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
            // Kb:203-212: 2 columns, rows share the height
            // up to the design's six clips the rows share the height; a longer live history scrolls
            // with rows of the same height
            val scrolls = x.clips.size > 6
            val rows = maxOf((x.clips.size + 1) / 2, 3) // the design's three rows, also when there are fewer clips
            val rowH = ((r.keyAreaH - 4f - 8f - 2 * 8f) / 3f).dp
            Column(
                Modifier.fillMaxSize().then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in x.clips.withIndex().chunked(2)) {
                    Row(if (scrolls) Modifier.height(rowH) else Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((i, c) in row) ClipTile(r, c, i)
                        if (row.size == 1) Spacer(Modifier.weight(1f)) // a lone clip keeps its column width
                    }
                }
                if (!scrolls) repeat(rows - (x.clips.size + 1) / 2) { Spacer(Modifier.weight(1f)) } // ...and its row height
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
                    DText("Drag to reorder · Pinned tools show in the bar", style = text(12, 400, t.barMuted))
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

/** Kb:205-209: one clipboard tile. */
@Composable
private fun RowScope.ClipTile(r: KbRender, c: KbClip, index: Int) {
    val t = r.t
    val actions = LocalKbActions.current
    // DogBoard: media clips show a preview, like Gboard (the design's stripes until it loads)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, c.thumbnail) {
        value = c.thumbnail?.let { load -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load(with(density) { 180.dp.roundToPx() }) } }
    }
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(TILE).tap(actions?.let { a -> { a.onClip(index) } })
            .then(if (c.stripes != null) Modifier.stripes(c.stripes.first, c.stripes.second, 6f) else Modifier.background(c.bg))
            .cssShadow(c.ring, TILE)
            .lint("clip:${c.text}${c.label}"),
    ) { LintParent("clip:${c.text}${c.label}") {
        thumb?.let { b ->
            androidx.compose.foundation.Image(
                b, contentDescription = c.label, modifier = Modifier.matchParentSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            if (c.icon == "play_circle") Symbol("play_circle", 32.sp, Color.White, Modifier.align(Alignment.Center), fill = 1)
        }
        Box(Modifier.matchParentSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (thumb == null) Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.media) {
                Symbol(c.icon, 26.sp, t.fnFg)
                DText(c.label, style = text(12, 500, t.fnFg))
            } else {
                DText(
                    c.text, Modifier.padding(end = 14.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = text(14, 400, t.tileFg).copy(lineHeight = (14 * 1.3).sp, textAlign = TextAlign.Start),
                )
            }
        }
        if (c.pinned) Symbol("push_pin", 16.sp, t.linkFg, Modifier.align(Alignment.TopEnd).offset(6.dp, (-4).dp), fill = 1)
        if (c.selected) {
            // Kb:209: pin / delete pill
            Row(
                Modifier.align(Alignment.BottomEnd).offset(6.dp, 4.dp)
                    .cssShadow(CssShadow.parse("0 1px 3px rgba(0,0,0,0.2)"), PILL).background(t.pop, PILL).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (icon in listOf("push_pin", "delete")) Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Symbol(icon, 18.sp, t.popFg) }
            }
        }
        }
    } }
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
                for (c in x.emojiCats) Box(Modifier.size(28.dp, 36.dp), contentAlignment = Alignment.Center) { Symbol(c.icon, 20.sp, c.fg, fill = c.fill) }
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
