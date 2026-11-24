package io.github.sillypuppyy.dogboard.design.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
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
data class LangRow(
    val id: Long, val name: String, val native: String?, val layout: String, val swipe: Boolean, val active: Boolean,
    /** Its dictionary is on its way (PackDownloadQueue): until then it types with its layout, without suggestions. */
    val gettingDictionary: Boolean = false,
    /** The languages typed together with this one (all suggested and corrected at once), by name; empty: alone. */
    val together: List<String> = emptyList(),
    /** Their tags, in order. */
    val togetherTags: List<String> = emptyList(),
    /** What it can be typed together with: the other added languages with a dictionary in the same alphabet (tag, name). */
    val togetherChoices: List<Pair<String, String>> = emptyList(),
    /** How many languages can be typed at once, this one included. */
    val togetherMax: Int = 3,
    /** Its language code for the badge ("BG", "EN"), as the keyboard's language indicator shows it. */
    val code: String = "",
    /** Its first language's tag ("en-US"): the layouts its page offers. */
    val tag: String = "",
)

/**
 * A language that can be added: [name] in English, [native] in itself ("Italiano"); [dictionary] false for a language
 * with layouts only (tools/layouts/gen_layouts.py ports every Gboard / iOS layout; it types without suggestions);
 * [suggested]: one of the phone's own languages, listed first; [downloadBytes]: its dictionary comes from the download
 * host (0: on the phone).
 */
data class LangPack(
    val tag: String, val name: String, val native: String, val dictionary: Boolean,
    val suggested: Boolean = false, val downloadBytes: Long = 0,
)

/** One of a language's layouts: [rows] its letters (the preview); [added] already one of your languages. */
data class LayoutChoice(val id: String, val label: String, val rows: List<String>, val added: Boolean)

/**
 * Settings › Languages, in DogBoard's style (replacing FlorisBoard's subtypes page): your languages as
 * cards; Add a language as Gboard does it (design canvas "Add a language"): a full page (your phone's
 * languages first), then the language's layouts as previews, several can be ticked (each becomes its own language
 * entry), Done adds them at once and the dictionary arrives in the background.
 */
@Composable
fun SetLanguages(
    langs: List<LangRow>, packs: List<LangPack>, layoutsFor: (String) -> List<LayoutChoice>,
    onUse: (Long) -> Unit, onLayout: () -> Unit, onRemove: (Long) -> Unit, onAdd: (tag: String, layouts: List<String>) -> Unit,
    onBack: () -> Unit,
    /** Types the entry together with the languages [tags] (empty: alone). */
    onTogether: (id: Long, tags: List<String>) -> Unit = { _, _ -> },
    /** The languages that can join keyboard [id] (a dictionary, the same alphabet, room left). */
    combineChoices: (id: Long) -> List<LangPack> = { emptyList() },
    /** The keyboards a new language [tag] can join: (id, name). */
    combineTargets: (tag: String) -> List<Pair<Long, String>> = { emptyList() },
    /** The layouts keyboard [id] can take once [tag] joins it: every layout of its languages. */
    layoutsForAll: (id: Long, tag: String) -> List<LayoutChoice> = { _, _ -> emptyList() },
    /** [tag] joins keyboard [id], which takes [layout]. */
    onCombine: (id: Long, tag: String, layout: String) -> Unit = { _, _, _ -> },
    /** Keyboard [id]'s joined languages become keyboards of their own. */
    onSplit: (id: Long) -> Unit = {},
    /** A keyboard's page picked a layout for it. */
    onSetLayout: (id: Long, layout: String) -> Unit = { _, _ -> },
    /** The page to open on ("": Add a language). */
    startPage: String? = null,
    /**
     * Shown inside another page (the setup): only your languages and Add a language; the pages that need the whole
     * screen (adding, combining) open the Languages page at that page ([onOpenPage]).
     */
    embedded: Boolean = false,
    onOpenPage: (String) -> Unit = {},
) {
    // null: your languages; "": Add a language; a tag: that language's layouts; "combine:<id>": a language to join
    // keyboard id; "shared:<id>:<tag>": the layout the combined keyboard takes
    var page by remember { mutableStateOf(startPage) }
    // opened at a page from elsewhere (the setup): done or back there returns, instead of showing the whole list
    if (startPage != null && !embedded) androidx.compose.runtime.LaunchedEffect(page) { if (page == null) onBack() }
    if (embedded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SetGroup("Your languages") {
                for (l in langs) LanguageRow(l, langs.size > 1, onUse, { onOpenPage("kb:${l.id}") }, onRemove, canCombine = combineChoices(l.id).isNotEmpty(),
                    onCombine = { onOpenPage("combine:${l.id}") }, onSplit = { onSplit(l.id) })
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).clickable { onOpenPage("") }.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("add", 22.sp, SetColors.primary)
                DText("Add a language", style = setText(16f, 500, SetColors.primary).copy(textAlign = START))
            }
        }
        return
    }
    // a new language that can join a keyboard: asked first (tag, the keyboards it can join)
    var ask by remember { mutableStateOf<Pair<String, List<Pair<Long, String>>>?>(null) }
    val shared = page?.takeIf { it.startsWith("shared:") }?.split(':')
    val combineId = page?.takeIf { it.startsWith("combine:") }?.removePrefix("combine:")?.toLongOrNull()
    // "kb:<id>": a keyboard's own page (its layout, combining, removing), opened by tapping it
    val kb = page?.takeIf { it.startsWith("kb:") }?.removePrefix("kb:")?.toLongOrNull()?.let { id -> langs.firstOrNull { it.id == id } }
    val picked = page?.takeIf { it.isNotEmpty() && shared == null && combineId == null && !it.startsWith("kb:") }?.let { t -> packs.firstOrNull { it.tag == t } }
    fun pickNew(tag: String) {
        val targets = combineTargets(tag)
        if (targets.isEmpty()) page = tag else ask = tag to targets
    }
    ask?.let { (tag, targets) ->
        val name = packs.firstOrNull { it.tag == tag }?.native ?: tag
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ask = null },
            title = { androidx.compose.material3.Text("Combine $name?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.Text("Type $name and another language on one keyboard, without switching. You pick the layout they share.")
                    for ((id, kb) in targets) androidx.compose.material3.TextButton({ ask = null; page = "shared:$id:$tag" }) { androidx.compose.material3.Text("Combine with $kb") }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton({ ask = null; page = tag }) { androidx.compose.material3.Text("Keep separate") } },
        )
    }
    if (kb != null) {
        BackHandler { page = null }
        val p = packs.firstOrNull { it.tag == kb.tag } ?: LangPack(kb.tag, kb.name, kb.name, dictionary = true)
        PickLayoutPage(p, layoutsForAll(kb.id, kb.tag), single = true, title = kb.name, onBack = { page = null },
            onDone = { ids -> ids.firstOrNull()?.let { onSetLayout(kb.id, it) }; page = null }, doneLabel = "Save",
            subtitle = if (kb.together.isEmpty()) "Its layout" else "The layout its languages share") {
            val canJoin = combineChoices(kb.id).isNotEmpty()
            if (canJoin || kb.together.isNotEmpty() || langs.size > 1) SetRows {
                if (canJoin) KbAction("add", "Combine with another language", "Type both on this keyboard, without switching") { page = "combine:${kb.id}" }
                if (kb.together.isNotEmpty()) KbAction("content_cut", "Split up", "Each language back on its own keyboard") { onSplit(kb.id); page = null }
                if (langs.size > 1) KbAction("delete", "Remove", "Take this keyboard off your list") { onRemove(kb.id); page = null }
            }
        }
    } else if (shared != null && shared.size == 3) {
        val id = shared[1].toLong(); val tag = shared[2]
        BackHandler { page = null }
        val p = packs.firstOrNull { it.tag == tag } ?: LangPack(tag, tag, tag, dictionary = true)
        PickLayoutPage(p, layoutsForAll(id, tag), single = true, title = "Shared layout", onBack = { page = null }, onDone = { ids -> ids.firstOrNull()?.let { onCombine(id, tag, it) }; page = null })
    } else if (combineId != null) {
        BackHandler { page = null }
        AddLanguagePage(combineChoices(combineId), title = "Combine with…", onBack = { page = null }, onPick = { page = "shared:$combineId:$it" })
    } else if (picked != null) {
        BackHandler { page = "" }
        PickLayoutPage(picked, layoutsFor(picked.tag), onBack = { page = "" }, onDone = { ids -> onAdd(picked.tag, ids); page = null })
    } else if (page != null) {
        BackHandler { page = null }
        AddLanguagePage(packs, onBack = { page = null }, onPick = { pickNew(it) })
    } else Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TitleBar("Languages", onBack)
            SetGroup("Your languages", Modifier.padding(horizontal = 16.dp)) {
                for (l in langs) LanguageRow(l, langs.size > 1, onUse, { page = "kb:${l.id}" }, onRemove, canCombine = combineChoices(l.id).isNotEmpty(),
                    onCombine = { page = "combine:${l.id}" }, onSplit = { onSplit(l.id) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).clickable { page = "" }.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("add", 22.sp, SetColors.primary)
                DText("Add a language", style = setText(16f, 500, SetColors.primary).copy(textAlign = START))
            }
            if (langs.size > 1) DText("To switch languages, hold the space bar or tap the globe under the keyboard.",
                Modifier.padding(horizontal = 20.dp), style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 4)
        }
        StatusBarBand()
    }
}

@Composable
private fun TitleBar(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { onBack() }, contentAlignment = Alignment.Center) { Symbol("arrow_back", 24.sp, SetColors.onSurface) }
        Column(Modifier.weight(1f)) {
            DText(title, style = setText(22f).copy(textAlign = START), maxLines = 1)
            if (subtitle != null) DText(subtitle, style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 1)
        }
    }
}

@Composable
private fun LanguageRow(
    l: LangRow, removable: Boolean, onUse: (Long) -> Unit, onLayout: () -> Unit, onRemove: (Long) -> Unit,
    canCombine: Boolean, onCombine: () -> Unit, onSplit: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).background(SetColors.row).clickable { onLayout() }
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        // the language's code in a tonal circle; a combined keyboard's codes stacked (EN over FR); which one is in use
        // isn't marked (switching happens on the keyboard)
        val codes = l.code.ifEmpty { l.name.take(2) }.split('+').filter { it.isNotEmpty() }
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(SetColors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val size = when (codes.size) { 1 -> 14f; 2 -> 11f; else -> 9f }
                for (c in codes) DText(c, style = setText(size, 500, SetColors.onSurfaceVariant).copy(lineHeight = (size + 1).sp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DText(l.name + (l.native?.takeIf { it != l.name }?.let { " ($it)" } ?: ""), style = setText(16f).copy(textAlign = START), maxLines = 2)
            // the layout as a line, not tags
            DText(listOfNotNull(l.layout, "swipe typing".takeIf { l.swipe }, "getting dictionary…".takeIf { l.gettingDictionary }).joinToString(", "),
                style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 2)
        }
        if (removable || canCombine || l.together.isNotEmpty()) Box {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { menu = true }, contentAlignment = Alignment.Center) { Symbol("more_vert", 22.sp, SetColors.onSurfaceVariant) }
            // combining: languages typed on one keyboard without switching (up to three), with a layout they share
            SetMenu(menu, { menu = false }, listOfNotNull(
                "Layout and options" to onLayout,
                ("Combine with…" to onCombine).takeIf { canCombine },
                ("Split up" to onSplit).takeIf { l.together.isNotEmpty() },
                ("Remove" to { onRemove(l.id) }).takeIf { removable },
            ))
        }
    }
}

@Composable
private fun Chip(text: String) {
    Box(Modifier.height(24.dp).clip(RoundedCornerShape(8.dp)).background(SetColors.secondaryContainer).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        DText(text, style = setText(12f, 500, SetColors.onSecondaryContainer))
    }
}

@Composable
private fun OutlineChip(text: String) {
    Box(Modifier.height(24.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, SetColors.outlineVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        DText(text, style = setText(12f, 400, SetColors.onSurfaceVariant))
    }
}

/** Add a language: one search, the phone's languages first, then every language A to Z in its own name. */
@Composable
private fun AddLanguagePage(packs: List<LangPack>, title: String = "Add a language", onBack: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val query = q.trim()
    val matches = if (query.isEmpty()) packs else packs.filter {
        it.name.contains(query, ignoreCase = true) || it.native.contains(query, ignoreCase = true) || it.tag.equals(query, ignoreCase = true)
    }
    val suggested = if (query.isEmpty()) matches.filter { it.suggested } else emptyList()
    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { TitleBar(title, onBack) }
            item {
                androidx.compose.material3.OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    placeholder = { androidx.compose.material3.Text("Search languages") }, leadingIcon = { Symbol("search", 22.sp, SetColors.onSurfaceVariant) })
            }
            if (suggested.isNotEmpty()) {
                item { SectionLabel("On your phone") }
                items(suggested, key = { "s-" + it.tag }) { p -> LanguageChoiceRow(p, onPick) }
            }
            item { SectionLabel(if (query.isEmpty()) "All languages" else "Results") }
            if (matches.isEmpty()) item {
                DText("No language matches “$query” yet.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = START))
            }
            items(matches, key = { "a-" + it.tag }) { p -> LanguageChoiceRow(p, onPick) }
            item { Box(Modifier.height(32.dp).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)) }
        }
        StatusBarBand()
    }
}

@Composable
private fun SectionLabel(text: String) {
    DText(text, Modifier.padding(start = 28.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), style = setText(14f, 500, SetColors.primary).copy(textAlign = START))
}

@Composable
private fun LanguageChoiceRow(p: LangPack, onPick: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(4.dp)).background(SetColors.row)
            .clickable { onPick(p.tag) }.padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText(p.native, style = setText(16f).copy(textAlign = START))
            // a quiet line, not a chip: most of the 300 languages have layouts only, and a chip on each was noise
            val sub = listOfNotNull(p.name.takeIf { it != p.native }, "layouts only".takeIf { !p.dictionary })
            if (sub.isNotEmpty()) DText(sub.joinToString(", ").replaceFirstChar { it.uppercase() }, style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START))
        }
    }
}

/** A language's layouts as previews; several can be ticked (each becomes its own entry); Done adds them. */
@Composable
private fun PickLayoutPage(
    p: LangPack, layouts: List<LayoutChoice>, single: Boolean = false, title: String? = null, onBack: () -> Unit, onDone: (List<String>) -> Unit,
    doneLabel: String = "Done", subtitle: String? = null, extra: (@Composable () -> Unit)? = null,
) {
    var chosen by remember(p.tag) { mutableStateOf(layouts.firstOrNull { !it.added }?.let { setOf(it.id) } ?: emptySet()) }
    Box(Modifier.fillMaxSize().background(SetColors.screen)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(top = 8.dp)) {
            TitleBar(title ?: p.native, onBack, subtitle = subtitle ?: if (title != null) "The layout all its languages type with" else p.name.takeIf { it != p.native })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DText("Layout", Modifier.padding(start = 12.dp), style = setText(14f, 500, SetColors.primary).copy(textAlign = START))
                for (pair in layouts.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (l in pair) LayoutCard(l, l.id in chosen, Modifier.weight(1f)) {
                        // a combined keyboard has one layout: picking one replaces the other
                        chosen = if (single) setOf(l.id) else if (l.id in chosen) chosen - l.id else chosen + l.id
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
                val note = when {
                    !p.dictionary -> "${p.native} has no dictionary yet: it types with its layout, without suggestions or autocorrect."
                    p.downloadBytes > 0 -> "You can type in ${p.native} right away. Its dictionary (%d MB) downloads in the background, then suggestions and autocorrect switch on."
                        .format((p.downloadBytes + 500_000) / 1_000_000)
                    else -> null
                }
                extra?.invoke()
                if (note != null) Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // bundled icons only (tools/fonts/symbols.txt): "download" isn't, and took the page down
                    Symbol(if (p.dictionary) "spellcheck" else "info", 20.sp, SetColors.onSurfaceVariant)
                    DText(note, Modifier.weight(1f), style = setText(14f, color = SetColors.onSurfaceVariant).copy(textAlign = START, lineHeight = (14 * 1.45f).sp), maxLines = 6)
                }
            }
            Row(Modifier.fillMaxWidth().background(SetColors.screen).padding(16.dp).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DText(if (chosen.isEmpty()) "" else "${chosen.size} selected", Modifier.weight(1f), style = setText(15f, color = SetColors.onSurfaceVariant).copy(textAlign = START))
                Box(Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, SetColors.outline, RoundedCornerShape(24.dp)).clickable { onBack() }.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    DText("Cancel", style = setText(15f, 500, SetColors.primary))
                }
                Box(Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (chosen.isEmpty()) SetColors.surfaceContainerHighest else SetColors.primary)
                    .then(if (chosen.isEmpty()) Modifier else Modifier.clickable { onDone(layouts.filter { it.id in chosen }.map { it.id }) }).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                    DText(doneLabel, style = setText(15f, 500, if (chosen.isEmpty()) SetColors.onSurfaceVariant else Color.White))
                }
            }
        }
        StatusBarBand()
    }
}

/** An action row on a keyboard's page: icon, title, a line under it. */
@Composable
private fun KbAction(icon: String, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).background(SetColors.row).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Symbol(icon, 22.sp, SetColors.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DText(title, style = setText(16f).copy(textAlign = START))
            DText(sub, style = setText(13f, color = SetColors.onSurfaceVariant).copy(textAlign = START), maxLines = 2)
        }
    }
}

@Composable
private fun LayoutCard(l: LayoutChoice, chosen: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).background(SetColors.row)
            .border(if (chosen) 2.dp else 0.dp, if (chosen) SetColors.primary else Color.Transparent, shape)
            .then(if (l.added) Modifier.alpha(0.55f) else Modifier.clickable { onToggle() }).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // the layout's letters, drawn small
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SetColors.surfaceContainerHighest).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val widest = l.rows.maxOfOrNull { it.length } ?: 10
            for (r in l.rows.take(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
                if (r.length < widest) Box(Modifier.weight((widest - r.length) / 2f + 0.001f))
                for (c in r) Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(3.dp)).background(SetColors.row), contentAlignment = Alignment.Center) {
                    DText(c.toString(), style = setText(8f))
                }
                if (r.length < widest) Box(Modifier.weight((widest - r.length) / 2f + 0.001f))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(12.dp).clip(RoundedCornerShape(3.dp)).background(SetColors.row))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(if (chosen) SetColors.primary else Color.Transparent)
                .border(2.dp, if (chosen || l.added) SetColors.primary else SetColors.outline, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                if (chosen) Symbol("check", 14.sp, Color.White)
            }
            DText(if (l.added) "${l.label} (added)" else l.label, Modifier.weight(1f), style = setText(14f, 500).copy(textAlign = START), maxLines = 2)
        }
    }
}
