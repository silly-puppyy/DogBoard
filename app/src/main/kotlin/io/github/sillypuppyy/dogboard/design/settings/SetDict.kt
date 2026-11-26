package io.github.sillypuppyy.dogboard.design.settings

import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
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
    /** Text replacements, the Shortcuts tab: the shortcut as the word, the phrase as why. */
    val shortcuts: List<SetDictRow>,
    /** The languages with learned words (tag, name), the shown one, and picking another: Learned and Undone are per language. */
    val languages: List<Pair<String, String>>,
    val language: String?,
    val onLanguage: (String) -> Unit,
    val onBack: () -> Unit,
    /** The row button: remove (learned, added, shortcuts) or restore (blocked, undone), by tab id. */
    val onRowAction: (tab: String, row: SetDictRow) -> Unit,
    /** The add button: a word, a blocked word, a rule or a shortcut, by tab id. */
    val onAdd: (tab: String) -> Unit,
    val onExport: () -> Unit,
    val onForgetAll: () -> Unit,
)

/** Each tab's id, label, blurb, add label, row icon, and whether its rows are alphabetical (letter headers). */
private data class DictTab(val id: String, val label: String, val blurb: String, val add: String, val icon: String, val letters: Boolean)

private val TABS = listOf(
    DictTab("learned", "Learned", "Words DogBoard picked up from your typing. Remove one and it goes back to being corrected.", "Add a word", "close", true),
    DictTab("added", "Added", "Words you added yourself. They are never corrected and can be suggested.", "Add a word", "close", true),
    DictTab("blocked", "Blocked", "Words you removed from suggestions with a long-press. They are never suggested again.", "Block a word", "undo", true),
    DictTab("rejected", "Undone", "Corrections you undid with delete. DogBoard won’t make these again.", "Add a rule", "undo", false),
    DictTab("shortcuts", "Shortcuts", "Type a shortcut and its phrase shows in the bar. Shortcuts beat autocorrect.", "Add a text replacement", "close", true),
)

/**
 * Settings › Your words, laid out like Gboard's personal dictionary for long lists: search, language and tabs pinned at
 * the top, the words under letter headers, adding by a floating button, export and forgetting in the ⋮ menu (the design,
 * SetDict.dc.html, had them under the list: 500 rows down with many learned words). Rows keep the design's look.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetDict(state: SetDictState, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf("learned") }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    val cur = TABS.first { it.id == tab }
    fun listOf(id: String) = when (id) {
        "learned" -> state.learned
        "added" -> state.added
        "blocked" -> state.blocked
        "rejected" -> state.undone
        else -> state.shortcuts
    }
    val rows = remember(tab, query, state) {
        listOf(tab).filter { query.isBlank() || it.word.contains(query.trim(), ignoreCase = true) || it.why.contains(query.trim(), ignoreCase = true) }
            .distinctBy { it.word }
    }
    // runs of rows under one letter (alphabetical tabs), each drawn as its own rounded group
    val groups = remember(rows, cur) {
        if (!cur.letters) listOf("" to rows)
        else rows.groupBy { r -> r.word.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#" }.toList()
    }

    Box(modifier.fillMaxSize().background(SetColors.screen)) {
    // a lazy list: only the rows on screen are built (a plain scrolling column built all of them: with 500 learned
    // words, switching back to Learned froze 400 ms, debug build, 2026-11-26)
    // the 40 dp under the clock outside the list, so the pinned header stops below it
    LazyColumn(Modifier.fillMaxSize().padding(top = 40.dp), contentPadding = PaddingValues(bottom = 112.dp)) {
        item(key = "bar") {
            // top app bar: 64 tall, padding 0 4, gap 4
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton48("arrow_back", 24, SetColors.onSurface, state.onBack)
                DText("Your words", Modifier.weight(1f), style = setText(22f))
                Box {
                    IconButton48("more_vert", 24, SetColors.onSurface) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ DText("Export your words", style = setText(15f)) }, { menu = false; state.onExport() })
                        DropdownMenuItem({ DText("Forget everything DogBoard learned", style = setText(15f, color = SetColors.onErrorContainer)) }, { menu = false; state.onForgetAll() })
                    }
                }
            }
        }
        stickyHeader(key = "pinned") {
            Column(Modifier.fillMaxWidth().background(SetColors.screen).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // search: margin 0 16, 56 tall, radius 28, gap 12, padding 0 18
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp).background(SetColors.surfaceContainerHighest, RoundedCornerShape(28.dp)).padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("search", 24.sp, SetColors.onSurfaceVariant)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) DText("Search your words", style = setText(16f, color = SetColors.onSurfaceVariant))
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = setText(16f), cursorBrush = SolidColor(SetColors.primary), modifier = Modifier.fillMaxWidth())
                    }
                    if (query.isNotEmpty()) Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable { query = "" }, contentAlignment = Alignment.Center) {
                        Symbol("close", 20.sp, SetColors.onSurfaceVariant)
                    }
                }
                // the language whose learned words show: a chip (filter chip: 32 tall, radius 8, outlined)
                val name = state.languages.firstOrNull { it.first == state.language }?.second
                if (name != null) Box(Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        Modifier.height(32.dp).cssShadow(listOf(CssShadow(true, 0f, 0f, 0f, 1f, SetColors.outline)), RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp)).clickable(enabled = state.languages.size > 1) { languageMenu = true }.padding(start = 12.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol("language", 18.sp, SetColors.onSurfaceVariant)
                        DText(name, style = setText(14f, 500))
                        if (state.languages.size > 1) Symbol("arrow_drop_down", 18.sp, SetColors.onSurfaceVariant)
                    }
                    DropdownMenu(languageMenu, { languageMenu = false }) {
                        for ((t, n) in state.languages) DropdownMenuItem({ DText(n, style = setText(15f, if (t == state.language) 600 else 400)) }, { languageMenu = false; state.onLanguage(t) })
                    }
                }
                // tabs, scrollable (five with their counts): a 1 px divider under them
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)
                        .cssShadow(listOf(CssShadow(true, 0f, -1f, 0f, 0f, SetColors.outlineVariant)), RoundedCornerShape(0.dp)),
                ) {
                    for (t in TABS) {
                        val on = t.id == tab
                        val n = listOf(t.id).size
                        Box(Modifier.height(48.dp).clickable { tab = t.id }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                DText(t.label, style = setText(14f, 500, if (on) SetColors.primary else SetColors.onSurfaceVariant))
                                if (n > 0) DText("$n", style = setText(12f, 500, if (on) SetColors.primary else SetColors.onSurfaceVariant))
                            }
                            // the indicator: bottom 0, as wide as the label, 3 tall, top corners rounded
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                                    .background(if (on) SetColors.primary else Color.Transparent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                            )
                        }
                    }
                }
            }
        }
        item(key = "blurb") {
            // blurb: margin 0 32, 14 / 1.5
            DText(cur.blurb, Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 8.dp), style = setText(14f, color = SetColors.onSurfaceVariant).copy(lineHeight = 21.sp), maxLines = 8)
        }
        if (rows.isEmpty()) item(key = "empty") {
            DText(
                if (query.isBlank()) "Nothing here yet." else "No matches.", Modifier.fillMaxWidth().padding(32.dp),
                style = setText(15f, color = SetColors.onSurfaceVariant).copy(textAlign = TextAlign.Center),
            )
        }
        for ((letter, group) in groups) {
            if (letter.isNotEmpty()) item(key = "$tab/letter/$letter") {
                DText(letter, Modifier.padding(start = 32.dp, top = 12.dp, bottom = 6.dp), style = setText(14f, 500, SetColors.primary))
            }
            // rows 2 apart, margin 0 16; SetRows' corners: 20 at the group's ends, 4 between
            itemsIndexed(group, key = { _, w -> "$tab/${w.word}" }) { i, w ->
                val top = if (i == 0) 20.dp else 4.dp
                val bottom = if (i == group.lastIndex) 20.dp else 4.dp
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp).fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom))
                        .heightIn(min = 64.dp).background(SetColors.row).padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DText(w.word, style = setText(16f))
                        DText(w.why, style = setText(13f, color = SetColors.onSurfaceVariant), maxLines = 2)
                    }
                    IconButton48(cur.icon, 22, SetColors.onSurfaceVariant) { state.onRowAction(tab, w) }
                }
            }
        }
        item(key = "note") {
            DText(
                "Your words stay on this phone. Nothing is sent anywhere.", Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 24.dp),
                style = setText(13f, color = SetColors.onSurfaceVariant).copy(lineHeight = (13 * 1.45).sp, textAlign = TextAlign.Center),
            )
        }
    }
    // the add button (M3 extended FAB: 56 tall, radius 16, the secondary container), above the navigation bar
    Row(
        Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp).height(56.dp)
            .cssShadow(listOf(CssShadow(false, 0f, 2f, 6f, 0f, Color.Black.copy(alpha = 0.25f))), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)).background(SetColors.secondaryContainer).clickable { state.onAdd(tab) }.padding(start = 16.dp, end = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("add", 24.sp, SetColors.onSecondaryContainer)
        DText(cur.add, style = setText(15f, 500, SetColors.onSecondaryContainer))
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
