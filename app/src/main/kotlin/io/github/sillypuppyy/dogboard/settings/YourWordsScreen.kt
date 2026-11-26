package io.github.sillypuppyy.dogboard.settings

import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import io.github.sillypuppyy.dogboard.design.settings.SetDict
import io.github.sillypuppyy.dogboard.design.settings.SetDictRow
import io.github.sillypuppyy.dogboard.design.settings.SetDictState
import io.github.sillypuppyy.dogboard.engine.TypingEngine
import io.github.sillypuppyy.dogboard.ime.EngineLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Your words, live (design SetDict.dc.html): the engine's word lists and actions. */
@Composable
fun YourWordsScreen() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) } // bumped after every change
    var lists by remember { mutableStateOf<TypingEngine.DictionaryLists?>(null) }
    var replacements by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var languages by remember { mutableStateOf(emptyList<String>()) }
    // the language whose learned words show: null, the loaded one (its engine's lists); another is read from its files
    var language by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version, language) {
        withContext(Dispatchers.IO) {
            EngineLoader.ensureLoaded(context)
            val own = TypingEngine.dictionaryLists()
            val other = language?.takeIf { it != EngineLoader.pack?.tag }
            lists = if (other == null) own else {
                val (learned, undone) = EngineLoader.languageLists(context, other, own.added.map { it.lowercase() }.toSet())
                own.copy(
                    learned = learned.map { (w, uses) -> w to if (uses > 0) "You used it $uses ${if (uses == 1) "time" else "times"}" else "You kept it as typed" },
                    undone = undone,
                )
            }
            replacements = TypingEngine.textReplacements()
            languages = EngineLoader.learnedLanguages(context)
        }
    }
    fun change(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        version++
    }
    var dialog by remember { mutableStateOf<String?>(null) } // add:<tab>, replacements, forget

    val shown = language ?: EngineLoader.pack?.tag
    val other = shown?.takeIf { it != EngineLoader.pack?.tag }
    val l = lists ?: TypingEngine.DictionaryLists(emptyList(), emptyList(), emptyList(), emptyList())
    SetDict(
        SetDictState(
            learned = l.learned.map { (w, why) -> SetDictRow(w, why) },
            added = l.added.map { SetDictRow(it, "Added by you") },
            blocked = l.blocked.map { SetDictRow(it, "Removed from the bar") },
            undone = l.undone.map { (typed, corr, n) ->
                SetDictRow("$typed → $corr", if (n == 1) "Undone once" else "Undone $n times", typed to corr)
            },
            shortcuts = replacements.sortedBy { it.first.lowercase() }.map { (s, p) -> SetDictRow(s, p) },
            languages = languages.map { t ->
                val loc = java.util.Locale.forLanguageTag(t)
                t to loc.getDisplayName(loc).replaceFirstChar { it.titlecase(loc) }
            },
            language = shown,
            onLanguage = { language = it },
            onBack = { navController.popBackStack() },
            onRowAction = { tab, row ->
                change {
                    when {
                        tab == "shortcuts" -> TypingEngine.removeTextReplacement(row.word)
                        tab == "blocked" -> TypingEngine.unblock(row.word)
                        tab == "rejected" -> (row.key as? Pair<*, *>)?.let { (t, c) ->
                            if (other == null) TypingEngine.restoreCorrection(t as String, c as String)
                            else EngineLoader.restoreCorrectionIn(context, other, t as String, c as String)
                        }
                        tab == "learned" && other != null -> EngineLoader.forgetWordIn(context, other, row.word)
                        else -> TypingEngine.forgetWord(row.word)
                    }
                }
            },
            onAdd = { tab -> dialog = if (tab == "shortcuts") "replacements" else "add:$tab" },
            onExport = {
                val text = buildString {
                    appendLine("DogBoard: your words")
                    (l.added + l.learned.map { it.first }).forEach { appendLine(it) }
                    if (replacements.isNotEmpty()) {
                        appendLine()
                        appendLine("Text replacements")
                        replacements.forEach { (s, p) -> appendLine("$s\t$p") }
                    }
                }
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Export your words")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onForgetAll = { dialog = "forget" },
        ),
    )

    when (val d = dialog) {
        null -> {}
        "forget" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Forget everything DogBoard learned?") },
            text = { Text("Learned words, your typing history and undone corrections are deleted. Text replacements and blocked words stay.") },
            confirmButton = { TextButton({ dialog = null; change { io.github.sillypuppyy.dogboard.ime.EngineLoader.forgetLearnedEverywhere(context); TypingEngine.resetDictionary() } }) { Text("Forget") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
        )
        "replacements" -> TwoFieldDialog(
            title = "Add a text replacement", first = "Shortcut", second = "Phrase",
            onDone = { s, p -> dialog = null; if (s.isNotBlank() && p.isNotBlank()) change { TypingEngine.addTextReplacement(s.trim(), p.trim()) } },
            onCancel = { dialog = null },
        )
        "add:rejected" -> TwoFieldDialog(
            title = "Add a rule", first = "Typed", second = "Never correct it to",
            onDone = { t, c ->
                dialog = null
                // into the shown language's rules (learned words and rules are per language)
                if (t.isNotBlank() && c.isNotBlank()) change {
                    if (other == null) TypingEngine.neverCorrect(t.trim(), c.trim()) else EngineLoader.neverCorrectIn(context, other, t.trim(), c.trim())
                }
            },
            onCancel = { dialog = null },
        )
        else -> {
            val block = d == "add:blocked"
            var word by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (block) "Block a word" else "Add a word") },
                text = { OutlinedTextField(word, { word = it }, singleLine = true, label = { Text("Word") }) },
                confirmButton = {
                    TextButton({
                        dialog = null
                        val w = word.trim()
                        // Learned with another language shown: that language's words (Added and Blocked are shared)
                        if (w.isNotEmpty()) change {
                            when {
                                block -> TypingEngine.block(w)
                                d == "add:learned" && other != null -> EngineLoader.learnWordIn(context, other, w)
                                else -> TypingEngine.addWord(w)
                            }
                        }
                    }) { Text(if (block) "Block" else "Add") }
                },
                dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun TwoFieldDialog(title: String, first: String, second: String, onDone: (String, String) -> Unit, onCancel: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(a, { a = it }, singleLine = true, label = { Text(first) })
                OutlinedTextField(b, { b = it }, singleLine = true, label = { Text(second) })
            }
        },
        confirmButton = { TextButton({ onDone(a, b) }) { Text("Add") } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}
