package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sillypuppyy.dogboard.design.CssShadow
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.Symbol
import io.github.sillypuppyy.dogboard.design.cssShadow

/** One row of a word list: the word, why it is there, and the payload for the row's button. */
data class SetDictRow(val word: String, val why: String, val key: Any = word)

/** What the live screen shows and does (SetDict.dc.html; the design's sample data in comments). */
class SetDictState(
    val learned: List<SetDictRow>,
    val added: List<SetDictRow>,
    val blocked: List<SetDictRow>,
    val undone: List<SetDictRow>,
    /** Text replacements: (shortcut, phrase). */
    val replacements: List<Pair<String, String>>,
    val onBack: () -> Unit,
    /** The row button: remove (learned, added) or restore (blocked, undone), by tab id. */
    val onRowAction: (tab: String, row: SetDictRow) -> Unit,
    /** The tab's add row ("Add a word", "Block a word", "Add a rule"). */
    val onAdd: (tab: String) -> Unit,
    val onManageReplacements: () -> Unit,
    val onExport: () -> Unit,
    val onForgetAll: () -> Unit,
)

/** SetDict.dc.html:77-84: each tab's id, label, blurb, add label and row icon. */
private data class DictTab(val id: String, val label: String, val blurb: String, val add: String, val icon: String)

private val TABS = listOf(
    DictTab("learned", "Learned", "Words DogBoard picked up from your typing. Remove one and it goes back to being corrected.", "Add a word", "close"),
    DictTab("added", "Added", "Words you added yourself. They are never corrected and can be suggested.", "Add a word", "close"),
    DictTab("blocked", "Blocked", "Words you removed from suggestions with a long-press. They are never suggested again.", "Block a word", "undo"),
    DictTab("rejected", "Undone", "Corrections you undid with delete. DogBoard won’t make these again.", "Add a rule", "undo"),
)

/** SetDict.dc.html:16-70: Settings › Your words. */
@Composable
fun SetDict(state: SetDictState, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf("learned") }
    var query by remember { mutableStateOf("") }
    val cur = TABS.first { it.id == tab }
    val rows = remember(tab, query, state) {
        when (tab) {
            "learned" -> state.learned
            "added" -> state.added
            "blocked" -> state.blocked
            else -> state.undone
        }.filter { query.isBlank() || it.word.contains(query.trim(), ignoreCase = true) }.distinctBy { it.word }
    }

    // SetDict:16: 390 wide (the screen), bg, padding 40 0 32, gap 16
    Box(Modifier.fillMaxSize()) {
    // a lazy list: only the rows on screen are built (a plain scrolling column built all of them: with 500 learned
    // words, 34 % of scroll frames late and 40-50 ms per search keystroke, debug build, 2026-11-26)
    LazyColumn(
        modifier.fillMaxSize().background(SetColors.screen),
        contentPadding = PaddingValues(top = 40.dp, bottom = 32.dp),
    ) {
        item(key = "head") {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // SetDict:18-22 top app bar: 64 tall, padding 0 4, gap 4
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton48("arrow_back", 24, SetColors.onSurface, state.onBack)
            DText("Your words", Modifier.weight(1f), style = setText(22f))
            IconButton48("more_vert", 24, SetColors.onSurface, state.onExport) // "Import or export"
        }
        // SetDict:24-27 search: margin 0 16, 56 tall, radius 28, gap 12, padding 0 18
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp).background(SetColors.surfaceContainerHighest, RoundedCornerShape(28.dp)).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("search", 24.sp, SetColors.onSurfaceVariant)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) DText("Search your words", style = setText(16f, color = SetColors.onSurfaceVariant))
                BasicTextField(query, { query = it }, singleLine = true, textStyle = setText(16f), cursorBrush = SolidColor(SetColors.primary), modifier = Modifier.fillMaxWidth())
            }
        }
        // SetDict:29-36 tabs: padding 0 16, a 1 px divider under them
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .cssShadow(listOf(CssShadow(true, 0f, -1f, 0f, 0f, SetColors.outlineVariant)), RoundedCornerShape(0.dp)),
        ) {
            for (t in TABS) {
                val on = t.id == tab
                Box(Modifier.weight(1f).height(48.dp).clickable { tab = t.id }, contentAlignment = Alignment.Center) {
                    DText(t.label, style = setText(14f, 500, if (on) SetColors.primary else SetColors.onSurfaceVariant))
                    // the indicator: bottom 0, left/right 20%, 3 tall, top corners rounded
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(3.dp)
                            .background(if (on) SetColors.primary else Color.Transparent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                    )
                }
            }
        }
        // SetDict:38 blurb: margin 0 32, 14 / 1.5
        DText(cur.blurb, Modifier.padding(horizontal = 32.dp), style = setText(14f, color = SetColors.onSurfaceVariant).copy(lineHeight = 21.sp), maxLines = 8)
        }
        }
        // SetDict:40-48 the list: margin 0 16, radius 20, rows 2 apart (SetRows' corners: 20 at the group's ends, 4 between)
        itemsIndexed(rows, key = { _, w -> "$tab/${w.word}" }) { i, w ->
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(if (i == 0) 20.dp else 4.dp, if (i == 0) 20.dp else 4.dp, 4.dp, 4.dp))
                        .heightIn(min = 64.dp).background(SetColors.row).padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DText(w.word, style = setText(16f))
                        DText(w.why, style = setText(13f, color = SetColors.onSurfaceVariant))
                    }
                    IconButton48(cur.icon, 22, SetColors.onSurfaceVariant) { state.onRowAction(tab, w) }
                }
            }
        item(key = "add") {
            val top = if (rows.isEmpty()) 20.dp else 4.dp
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(top, top, 20.dp, 20.dp))
                    .heightIn(min = 56.dp).background(SetColors.row).clickable { state.onAdd(tab) }.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("add", 22.sp, SetColors.primary)
                DText(cur.add, style = setText(15f, 500, SetColors.primary))
            }
        }
        item(key = "tail") {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // SetDict:50-63 text replacements: padding 0 16, gap 8
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DText("Text replacements", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = setText(14f, 500, SetColors.primary))
            SetRows(Modifier.fillMaxWidth()) {
                for ((shortcut, phrase) in state.replacements) {
                    Row(
                        Modifier.fillMaxWidth().background(SetColors.row).padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DText(shortcut, Modifier.background(SetColors.secondaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp), style = setText(15f, 600, SetColors.onSecondaryContainer))
                        Symbol("arrow_forward", 18.sp, SetColors.onSurfaceVariant)
                        DText(phrase, Modifier.weight(1f), style = setText(16f))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).background(SetColors.row).clickable(onClick = state.onManageReplacements).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    DText("Shortcuts beat autocorrect and show in the bar.", Modifier.weight(1f), style = setText(15f, color = SetColors.onSurfaceVariant), maxLines = 8)
                    DText("Manage", style = setText(14f, 500, SetColors.primary))
                }
            }
        }
        // SetDict:65-69 buttons: padding 8 32 0, gap 10
        Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp)).clickable(onClick = state.onExport),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("ios_share", 20.sp, SetColors.primary)
                DText("Export your words", style = setText(14f, 500, SetColors.primary))
            }
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(SetColors.errorContainer).clickable(onClick = state.onForgetAll),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                Symbol("delete_forever", 20.sp, SetColors.onErrorContainer)
                DText("Forget everything DogBoard learned", style = setText(14f, 600, SetColors.onErrorContainer))
            }
            DText(
                "Your words stay on this phone. Nothing is sent anywhere.", Modifier.fillMaxWidth(),
                style = setText(13f, color = SetColors.onSurfaceVariant).copy(lineHeight = (13 * 1.45).sp, textAlign = TextAlign.Center),
            )
        }
        }
        }
    }
    StatusBarBand() // a scrolled page doesn't run under the clock
    }
}

/** SetDict:19, 21, 44: a 48 dp round icon button. */
@Composable
private fun IconButton48(icon: String, size: Int, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Symbol(icon, size.sp, color)
    }
}
