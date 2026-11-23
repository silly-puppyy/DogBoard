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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.Symbol

private val START = TextAlign.Start

/** A keyboard language: its name (in its own language too), layout, whether swipe typing reads it, in use. */
data class LangRow(val id: Long, val name: String, val native: String?, val layout: String, val swipe: Boolean, val active: Boolean)

/**
 * A language that can be added: [usable] when it has a dictionary or layouts; [dictionary] false for a language with
 * layouts only (tools/layouts/gen_layouts.py ports every Gboard / iOS layout; it types without suggestions).
 */
data class LangPack(val tag: String, val name: String, val usable: Boolean, val added: Boolean, val dictionary: Boolean = true,
    /** Bytes to download first (a pack the download host offers that the phone doesn't have yet); 0: on the phone. */
    val downloadBytes: Long = 0)

/**
 * Settings › Languages, in DogBoard's style (user 2026-11-19, replacing FlorisBoard's subtypes page and its pink
 * warning): your languages as cards, Add a language (a searchable sheet of the language packs), and what a pack
 * brings. Tapping the language in use opens its layout; tapping another switches to it.
 */
@Composable
fun SetLanguages(
    langs: List<LangRow>, packs: List<LangPack>,
    onUse: (Long) -> Unit, onLayout: () -> Unit, onRemove: (Long) -> Unit, onAdd: (String) -> Unit, onBack: () -> Unit,
    /** A download in progress or its failure ("Downloading Español, 42 %"), shown under Add. */
    status: String? = null,
) {
    var adding by remember { mutableStateOf(false) }
    if (adding) AddLanguageSheet(packs, { adding = false }) { adding = false; onAdd(it) }
    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { onBack() }, contentAlignment = Alignment.Center) { Symbol("arrow_back", 24.sp, SetColors.onSurface) }
                DText("Languages", Modifier.weight(1f), style = setText(22f).copy(textAlign = START))
            }

            SetGroup("Your languages", Modifier.padding(horizontal = 16.dp)) {
                for (l in langs) LanguageRow(l, langs.size > 1, onUse, onLayout, onRemove)
            }
            if (langs.size > 1) DText("Switch languages with the globe key on the keyboard.",
                Modifier.padding(horizontal = 20.dp), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 4)

            SetGroup("Add", Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SetColors.row).clickable { adding = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.secondaryContainer), contentAlignment = Alignment.Center) { Symbol("add", 22.sp, SetColors.onSecondaryContainer) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        DText("Add a language", style = setText(16f).copy(textAlign = START))
                        DText(status ?: "Type in more than one language", style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 3)
                    }
                }
            }
            DText("A language brings its dictionary for autocorrect and suggestions, its punctuation and its layouts. Languages without a dictionary yet type with their layouts, without suggestions.",
                Modifier.padding(horizontal = 20.dp), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START, lineHeight = (13 * 1.4f).sp), maxLines = 8)
        }
        StatusBarBand()
    }
}

@Composable
private fun LanguageRow(l: LangRow, removable: Boolean, onUse: (Long) -> Unit, onLayout: () -> Unit, onRemove: (Long) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 80.dp).background(SetColors.row).clickable { if (l.active) onLayout() else onUse(l.id) }
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        // the language's first letters in a tonal circle; ticked when in use
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(if (l.active) SetColors.primary else SetColors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            if (l.active) Symbol("check", 22.sp, Color.White)
            else DText(l.name.take(2), style = setText(15f, 500, SetColors.onSurfaceVariant))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DText(l.name + (l.native?.takeIf { it != l.name }?.let { " ($it)" } ?: ""), style = setText(16f).copy(textAlign = START), maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(l.layout)
                Chip(if (l.swipe) "Swipe typing" else "Taps only")
            }
        }
        if (l.active) Symbol("chevron_right", 22.sp, SetColors.onSurfaceVariant, Modifier.padding(end = 12.dp))
        if (removable) Box {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { menu = true }, contentAlignment = Alignment.Center) { Symbol("more_vert", 22.sp, SetColors.onSurfaceVariant) }
            SetMenu(menu, { menu = false }, listOfNotNull(("Use this language" to { onUse(l.id) }).takeIf { !l.active }, "Layout" to onLayout, "Remove" to { onRemove(l.id) }))
        }
    }
}

@Composable
private fun Chip(text: String) {
    Box(Modifier.height(24.dp).clip(RoundedCornerShape(8.dp)).background(SetColors.secondaryContainer).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        DText(text, style = setText(12f, 500, SetColors.onSecondaryContainer))
    }
}

/** Add a language: the packs, searchable; one without a dictionary yet is greyed out. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddLanguageSheet(packs: List<LangPack>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SetColors.screen) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DText("Add a language", Modifier.padding(start = 8.dp), style = setText(22f))
            androidx.compose.material3.OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { androidx.compose.material3.Text("Search languages") }, leadingIcon = { Symbol("search", 22.sp, SetColors.onSurfaceVariant) })
            // the languages with a dictionary; a search finds the 300 with layouts only too (rare ones below common ones)
            val shown = if (q.isBlank()) packs.filter { it.dictionary }
                else packs.filter { it.name.contains(q.trim(), ignoreCase = true) || it.tag.equals(q.trim(), ignoreCase = true) }.take(40)
            SetRows {
                if (q.isBlank()) Box(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp)) {
                    DText("Search to find more: 300 more languages have layouts, without a dictionary yet.", style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START))
                }
                if (shown.isEmpty()) Box(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp)) {
                    DText("No language matches “$q” yet.", style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = START))
                }
                for (p in shown) {
                    val enabled = p.usable && !p.added
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).background(SetColors.row).then(if (enabled) Modifier.clickable { onPick(p.tag) } else Modifier)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).then(if (p.usable) Modifier else Modifier.alpha(0.5f)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            DText(p.name, style = setText(16f).copy(textAlign = START))
                            DText(if (p.added) "Added" else if (!p.dictionary) "Layouts only, no dictionary yet"
                                else if (p.downloadBytes > 0) "Dictionary, punctuation, layouts (download %d MB)".format((p.downloadBytes + 500_000) / 1_000_000)
                                else if (p.usable) "Dictionary, punctuation, layouts" else "Coming in a later update",
                                style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START))
                        }
                        if (p.added) Symbol("check", 20.sp, SetColors.primary)
                    }
                }
            }
        }
    }
}
