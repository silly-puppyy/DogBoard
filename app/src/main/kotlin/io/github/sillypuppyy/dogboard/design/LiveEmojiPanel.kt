package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ime.media.emoji.Emoji
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSet
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import kotlinx.coroutines.launch

/** One section of the live emoji panel: its header, the design's category (0-8), its emoji. */
data class LiveEmojiSection(val title: String, val category: Int, val sets: List<EmojiSet>)

/**
 * Stage 2: the design's emoji panel body and bottom row (Kb:224-248) with the real emoji: a
 * scrolling 8-column grid (32 dp rows, 24 sp) with section headers (12 sp / 500, barMuted), the
 * category bar jumping to and following the sections, and the skin picker (Kb:236-238) on long
 * press. The design draws a static two-section sample; sizes and colours are the design's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveEmojiPanel(
    sections: List<LiveEmojiSection>,
    skinTone: EmojiSkinTone,
    onEmoji: (Emoji) -> Unit,
    onAbc: () -> Unit,
    /** Variants picked by long press before, by the emoji in the default tone (shown instead of [skinTone]'s). */
    toneChoices: Map<String, String> = emptyMap(),
    /** A variant picked by long press: (the emoji in the default tone, the picked variant). */
    onToneChoice: (String, String) -> Unit = { _, _ -> },
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = liveDark()
    val theme = liveTheme()
    val r = remember(dark, theme) { KbSpec.render(theme.apply(KbProps(dark = dark, panel = "emoji", only = "live"))) }
    val t = r.t
    val shape = RoundedCornerShape(r.radius.coerceAtMost(999f).dp)
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // the grid index of each section's header
    val headerIndex = remember(sections) {
        var i = 0
        sections.map { s -> i.also { i += 1 + s.sets.size } }
    }
    val activeCategory by remember(sections) {
        derivedStateOf {
            val first = grid.firstVisibleItemIndex
            sections.getOrNull(headerIndex.indexOfLast { it <= first })?.category ?: 0
        }
    }
    var skinPicker by remember { mutableStateOf<EmojiSet?>(null) }

    Column(modifier.fillMaxSize().then(if (t.seg) Modifier.background(GLOSS_EMOJI_BG.brush()) else Modifier.themeBg(t))) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            LazyVerticalGrid(GridCells.Fixed(8), state = grid, modifier = Modifier.fillMaxSize()) {
                for (s in sections) {
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        Box(Modifier.height(20.dp).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
                            // SkinPanels: Gloss bold with the era's white emboss (.g-sec), Mechanical silkscreen capitals (.silk)
                            when {
                                t.seg -> DText(s.title, style = designTextStyle(13.sp, 700, Color(0xFF2D3038))
                                    .copy(shadow = androidx.compose.ui.graphics.Shadow(Color(0x8CFFFFFF), androidx.compose.ui.geometry.Offset(0f, 1f), 0f)))
                                t.face != null -> DText(s.title.uppercase(), style = designTextStyle(10.5.sp, 600, Color(0xFF8A867E)).copy(letterSpacing = 1.5.sp))
                                else -> DText(s.title, style = designTextStyle(12.sp, 500, t.barMuted))
                            }
                        }
                    }
                    items(s.sets.size, contentType = { "emoji" }) { i ->
                        val set = s.sets[i]
                        val emoji = toneChoices[set.base(EmojiSkinTone.DEFAULT).value]?.let { v -> set.emojis.firstOrNull { it.value == v } } ?: set.base(skinTone)
                        Box(
                            Modifier.height(32.dp).fillMaxWidth().combinedClickable(
                                onClick = { onEmoji(emoji) },
                                onLongClick = { if (set.emojis.size > 1) skinPicker = set else onEmoji(emoji) },
                            ),
                            contentAlignment = Alignment.Center,
                        ) { DText(emoji.value, style = designTextStyle(24.sp, 400, Color.Black)) }
                    }
                }
            }
            // Kb:236-238 skin picker, shown over the grid's top edge
            skinPicker?.let { set ->
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
                        .cssShadow(CssShadow.parse("0 2px 6px rgba(0,0,0,0.18), 0 8px 24px rgba(0,0,0,0.14)"), RoundedCornerShape(16.dp))
                        .background(t.pop, RoundedCornerShape(16.dp)).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (e in set.emojis) {
                        Box(
                            Modifier.size(38.dp, 44.dp)
                                .background(if (e.skinTone == skinTone) t.sel else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { skinPicker = null; onToneChoice(set.base(EmojiSkinTone.DEFAULT).value, e.value); onEmoji(e) },
                            contentAlignment = Alignment.Center,
                        ) { DText(e.value, style = designTextStyle(26.sp, 400, Color.Black)) }
                    }
                }
            }
        }
        if (t.seg) { GlossEmojiTabs(r, activeCategory, onAbc, onDelete) { ci ->
            val target = sections.indexOfFirst { it.category == ci }
            if (target >= 0) scope.launch { grid.scrollToItem(headerIndex[target]) }
        }; return@Column }
        // Kb:241-247 bottom row: ABC, the nine categories, delete
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.height(36.dp).themedSurface(t, "fn", shape, t.fn).clickable(onClick = onAbc).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                DText("ABC", style = designTextStyle(14.sp, 500, t.fnFg))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceAround) {
                for ((ci, c) in r.x.emojiCats.withIndex()) {
                    val active = ci == activeCategory
                    Box(
                        Modifier.size(28.dp, 36.dp).clickable {
                            val target = sections.indexOfFirst { it.category == ci }
                            if (target >= 0) scope.launch { grid.scrollToItem(headerIndex[target]) }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        val fg = if (active) t.linkFg else t.barMuted
                        if (c.icon == "pets") PawIcon(18.dp, 17.dp, fg) else Symbol(c.icon, 20.sp, fg, fill = if (active) 1 else 0)  // Kb:274
                    }
                }
            }
            Box(Modifier.size(44.dp, 36.dp).themedSurface(t, "fn", shape, t.fn).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                Symbol("backspace", 20.sp, t.fnFg)
            }
        }
    }
}

/**
 * Gloss (SkinPanels .i6-tabs): the bottom row as the era's tab strip, glossy cells edge to edge, ABC and delete the
 * darker ends, the shown category lit blue with a glowing icon.
 */
@Composable
private fun GlossEmojiTabs(r: KbRender, active: Int, onAbc: () -> Unit, onDelete: () -> Unit, onCategory: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(46.dp).drawBehind { drawRect(GLOSS_TAB_EDGE, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }) {
        @Composable fun RowScope.tab(weight: Float, kind: Int, onTap: () -> Unit, content: @Composable () -> Unit) {
            val (bg, sh) = when (kind) { 1 -> GLOSS_TAB_DARK to GLOSS_TAB_DARK_SH; 2 -> GLOSS_TAB_ON to GLOSS_TAB_ON_SH; else -> GLOSS_TAB to GLOSS_TAB_SH }
            Box(
                Modifier.weight(weight).fillMaxHeight().background(bg.brush()).cssShadow(sh, androidx.compose.ui.graphics.RectangleShape).clickable(onClick = onTap),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
        tab(1.5f, 1, onAbc) { DText("ABC", style = designTextStyle(14.sp, 700, Color.White).copy(shadow = GLOSS_DARK_TEXT_SH)) }
        for ((ci, c) in r.x.emojiCats.withIndex()) {
            val on = ci == active
            tab(1f, if (on) 2 else 0, { onCategory(ci) }) {
                val fg = if (on) Color.White else GLOSS_TAB_FG
                Box(if (on) Modifier.cssShadow(GLOSS_GLOW, RoundedCornerShape(50)) else Modifier) {
                    if (c.icon == "pets") PawIcon(18.dp, 17.dp, fg) else Symbol(c.icon, 21.sp, fg, fill = if (on) 1 else 0, weight = if (on) 400 else 300)
                }
            }
        }
        tab(1.5f, 1, onDelete) { Symbol("backspace", 22.sp, Color.White, fill = 1) }
    }
}

private val GLOSS_CARD = CssGradient.parse("linear-gradient(#ffffff, #f2f3f5)")!!
private val GLOSS_CARD_SH = CssShadow.parse("0 1px 1px rgba(0,0,0,0.55), 0 2px 4px rgba(0,0,0,0.22)")
private val GLOSS_FIELD_SH = CssShadow.parse("inset 0 1px 2px rgba(0,0,0,0.55), 0 1px 0 rgba(255,255,255,0.9)")
/**
 * Mechanical's search slot cut into the plate (SkinPanels .m-slot): near black on the dark plate; on the light version
 * (no canvas spec) a shallow recess a step darker than the plate, a black one read as a hole.
 */
/** A field set into its surface: [sh]'s outer shadows under the [fill], its inset ones over it. */
internal fun Modifier.sunken(sh: List<CssShadow>, fill: Color, shape: androidx.compose.ui.graphics.Shape): Modifier =
    cssShadow(sh.filter { !it.inset }, shape).background(fill, shape).cssShadow(sh.filter { it.inset }, shape)

internal fun mechSlot(t: KbTokens): Color = if (t.bg.luminance() < 0.3f) Color(0xFF0B0B0C) else Color(0xFFC2BEB6)
internal fun mechSlotSh(t: KbTokens): List<CssShadow> = if (t.bg.luminance() < 0.3f) MECH_SLOT_SH else MECH_SLOT_LIGHT_SH
internal val MECH_SLOT_HINT = Color(0xFF77736C)
private val MECH_SLOT_SH = CssShadow.parse("inset 0 2px 3px rgba(0,0,0,0.85), 0 0.5px 0 rgba(255,255,255,0.07)")
private val MECH_SLOT_LIGHT_SH = CssShadow.parse("inset 0 1.5px 2.5px rgba(0,0,0,0.3), 0 0.5px 0 rgba(255,255,255,0.8)")
private val GLOSS_EMOJI_BG = CssGradient.parse("linear-gradient(#f5f6f8, #e4e6ea)")!!
private val GLOSS_TAB_EDGE = Color(0xFF8F949C)
private val GLOSS_TAB_FG = Color(0xFF62666F)
private val GLOSS_TAB = CssGradient.parse("linear-gradient(#fbfbfc, #e6e7ea 45%, #c8cbd1)")!!
private val GLOSS_TAB_SH = CssShadow.parse("inset 1px 0 0 #ffffff, inset -1px 0 0 #9da2ab")
private val GLOSS_TAB_DARK = CssGradient.parse("linear-gradient(#a7acb5, #868c97 50%, #7a808b)")!!
private val GLOSS_TAB_DARK_SH = CssShadow.parse("inset 1px 0 0 rgba(255,255,255,0.35), inset -1px 0 0 #5d626c")
private val GLOSS_TAB_ON = CssGradient.parse("linear-gradient(#4b8ff2, #1d63d6 55%, #1350bf)")!!
private val GLOSS_TAB_ON_SH = CssShadow.parse("inset 0 2px 5px rgba(0,0,0,0.35)")
private val GLOSS_GLOW = CssShadow.parse("0 0 7px rgba(210,230,255,0.6)")
private val GLOSS_DARK_TEXT_SH = androidx.compose.ui.graphics.Shadow(Color(0x73000000), androidx.compose.ui.geometry.Offset(0f, -1f), 0f)

/**
 * Emoji search, as Gboard (measured 2026-11-15): a rounded card with two
 * rows of results that scroll sideways and, at its bottom, the search field (magnifier, the query
 * with a caret, clear once something is typed); the full keyboard below types into the field.
 * Drawn with the theme's tile tokens; an empty query shows [recents].
 */
@Composable
fun LiveEmojiSearchCard(
    query: String,
    results: List<Emoji>,
    recents: List<Emoji>,
    onEmoji: (Emoji) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = liveDark()
    val theme = liveTheme()
    val r = remember(dark, theme) { KbSpec.render(theme.apply(KbProps(dark = dark, panel = "emoji", only = "live"))) }
    val t = r.t
    val shown = if (query.isBlank()) recents else results
    val card = RoundedCornerShape(if (t.seg) 10.dp else 16.dp)
    Column(
        modifier.fillMaxWidth().themeBg(t).padding(horizontal = 8.dp, vertical = 4.dp)
            // Gloss: the era's white card (SkinPanels .g-card)
            .then(if (t.seg) Modifier.cssShadow(GLOSS_CARD_SH, card).background(GLOSS_CARD.brush(), card) else Modifier.background(t.tile, card))
            .padding(vertical = 4.dp),
    ) {
        // two rows that scroll sideways together, read as Gboard: the best 8 on the top row, the next
        // 8 below, then the next page of 16 to the right (the grid itself fills column by column)
        val cells = remember(shown) { searchGridOrder(shown.size).map { shown.getOrNull(it) } }
        if (query.isNotBlank() && results.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
                DText("No emoji found", style = designTextStyle(14.sp, 400, t.barMuted))
            }
        } else androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), Modifier.fillMaxWidth().height(104.dp),
        ) {
            items(cells.size) { i ->
                val e = cells[i]
                Box(Modifier.size(48.dp, 52.dp).then(if (e != null) Modifier.clickable { onEmoji(e) } else Modifier), contentAlignment = Alignment.Center) {
                    if (e != null) DText(e.value, style = designTextStyle(28.sp, 400, Color.Black))
                }
            }
        }
        // the field: Gloss sunken white (.g-search), Mechanical a dark slot (.m-slot)
        val fieldShape = RoundedCornerShape(if (t.seg) 18.dp else 8.dp)
        val styled = t.seg || t.face != null
        val hint = when { t.seg -> Color(0xFF8E939C); t.face != null -> Color(0xFF77736C); else -> t.barMuted }
        Row(
            Modifier.fillMaxWidth().height(44.dp)
                .then(if (styled) Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    .sunken(if (t.seg) GLOSS_FIELD_SH else mechSlotSh(t), if (t.seg) Color.White else mechSlot(t), fieldShape)
                    else Modifier)
                .padding(horizontal = if (styled) 10.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("search", if (styled) 19.sp else 22.sp, hint)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (query.isEmpty()) DText("Search emoji", style = designTextStyle(if (styled) 15.sp else 16.sp, 400, hint))
                else DText(query, style = designTextStyle(if (styled) 15.sp else 16.sp, 400, when { t.seg -> Color(0xFF1D1F24); t.face != null -> if (t.bg.luminance() < 0.3f) Color(0xFFCDC9BF) else Color(0xFF2B2A27); else -> t.tileFg }))
                Box(Modifier.padding(start = 1.dp).size(2.dp, 20.dp).background(t.linkFg))
            }
            if (query.isNotEmpty()) Box(Modifier.size(36.dp).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                Symbol("cancel", 22.sp, hint, fill = 1)
            }
        }
    }
}

/**
 * The emoji search grid's cell order (column by column, two rows) for results read row by row in
 * pages of [perRow]: cell i shows result [searchGridOrder]`[i]` (past the end: an empty cell).
 */
fun searchGridOrder(count: Int, perRow: Int = 8): List<Int> {
    if (count == 0) return emptyList()
    val pages = (count + 2 * perRow - 1) / (2 * perRow)
    val lastPage = count - (pages - 1) * 2 * perRow
    return buildList {
        for (p in 0 until pages) {
            // the last page is as wide as it needs to be, its top row filled first
            val cols = if (p < pages - 1) perRow else minOf(perRow, lastPage)
            for (c in 0 until cols) for (row in 0..1) add(p * 2 * perRow + row * cols + c)
        }
    }
}

