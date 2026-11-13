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
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            EngineLoader.ensureLoaded(context)
            lists = TypingEngine.dictionaryLists()
            replacements = TypingEngine.textReplacements()
        }
    }
    fun change(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        version++
    }
    var dialog by remember { mutableStateOf<String?>(null) } // add:<tab>, replacements, forget

    val l = lists ?: TypingEngine.DictionaryLists(emptyList(), emptyList(), emptyList(), emptyList())
    SetDict(
        SetDictState(
            learned = l.learned.map { (w, why) -> SetDictRow(w, why) },
            added = l.added.map { SetDictRow(it, "Added by you") },
            blocked = l.blocked.map { SetDictRow(it, "Removed from the bar") },
            undone = l.undone.map { (typed, corr, n) ->
                SetDictRow("$typed → $corr", if (n == 1) "Undone once" else "Undone $n times", typed to corr)
            },
            replacements = replacements,
            onBack = { navController.popBackStack() },
            onRowAction = { tab, row ->
                change {
                    when (tab) {
                        "learned", "added" -> TypingEngine.forgetWord(row.word)
                        "blocked" -> TypingEngine.unblock(row.word)
                        else -> (row.key as? Pair<*, *>)?.let { (t, c) -> TypingEngine.restoreCorrection(t as String, c as String) }
                    }
                }
            },
            onAdd = { tab -> dialog = "add:$tab" },
            onManageReplacements = { dialog = "replacements" },
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
            confirmButton = { TextButton({ dialog = null; change { TypingEngine.resetDictionary() } }) { Text("Forget") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
        )
        "replacements" -> TwoFieldDialog(
            title = "Add a text replacement", first = "Shortcut", second = "Phrase",
            onDone = { s, p -> dialog = null; if (s.isNotBlank() && p.isNotBlank()) change { TypingEngine.addTextReplacement(s.trim(), p.trim()) } },
            onCancel = { dialog = null },
        )
        "add:rejected" -> TwoFieldDialog(
            title = "Add a rule", first = "Typed", second = "Never correct it to",
            onDone = { t, c -> dialog = null; if (t.isNotBlank() && c.isNotBlank()) change { TypingEngine.reject(t.trim(), c.trim()) } },
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
                        if (w.isNotEmpty()) change { if (block) TypingEngine.block(w) else TypingEngine.addWord(w) }
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
