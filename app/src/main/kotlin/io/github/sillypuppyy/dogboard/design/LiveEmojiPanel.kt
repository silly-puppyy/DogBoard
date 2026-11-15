package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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

    Column(modifier.fillMaxSize().themeBg(t)) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
            LazyVerticalGrid(GridCells.Fixed(8), state = grid, modifier = Modifier.fillMaxSize()) {
                for (s in sections) {
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        Box(Modifier.height(20.dp).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
                            DText(s.title, style = designTextStyle(12.sp, 500, t.barMuted))
                        }
                    }
                    items(s.sets.size, contentType = { "emoji" }) { i ->
                        val set = s.sets[i]
                        val emoji = set.base(skinTone)
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
                                .clickable { skinPicker = null; onEmoji(e) },
                            contentAlignment = Alignment.Center,
                        ) { DText(e.value, style = designTextStyle(26.sp, 400, Color.Black)) }
                    }
                }
            }
        }
        // Kb:241-247 bottom row: ABC, the nine categories, delete
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.height(36.dp).background(t.fn, shape).clickable(onClick = onAbc).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
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
            Box(Modifier.size(44.dp, 36.dp).background(t.fn, shape).clickable(onClick = onDelete), contentAlignment = Alignment.Center) {
                Symbol("backspace", 20.sp, t.fnFg)
            }
        }
    }
}

/**
 * Emoji search, as Gboard (measured 2026-11-15; user: "we listen to Gboard"): a rounded card with two
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
    Column(
        modifier.fillMaxWidth().themeBg(t).padding(horizontal = 8.dp, vertical = 4.dp)
            .background(t.tile, RoundedCornerShape(16.dp)).padding(vertical = 4.dp),
    ) {
        // two rows, filled column by column so both rows scroll together
        androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), Modifier.fillMaxWidth().height(104.dp),
        ) {
            items(shown.size) { i ->
                val e = shown[i]
                Box(Modifier.size(48.dp, 52.dp).clickable { onEmoji(e) }, contentAlignment = Alignment.Center) {
                    DText(e.value, style = designTextStyle(28.sp, 400, Color.Black))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("search", 22.sp, t.barMuted)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (query.isEmpty()) DText("Search emoji", style = designTextStyle(16.sp, 400, t.barMuted))
                else DText(query, style = designTextStyle(16.sp, 400, t.tileFg))
                Box(Modifier.padding(start = 1.dp).size(2.dp, 20.dp).background(t.linkFg))
            }
            if (query.isNotEmpty()) Box(Modifier.size(36.dp).clickable(onClick = onClear), contentAlignment = Alignment.Center) {
                Symbol("cancel", 22.sp, t.barMuted, fill = 1)
            }
        }
    }
}
