package io.github.sillypuppyy.dogboard.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.NoBackupPreferenceStore
import dev.patrickgold.florisboard.app.Routes
import io.github.sillypuppyy.dogboard.testing.KeyLog
import io.github.sillypuppyy.dogboard.design.DText
import io.github.sillypuppyy.dogboard.design.settings.SetColors
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.setText
import kotlinx.coroutines.launch

/**
 * Settings › Advanced › Testing ("a debug menu ... replay the onboarding ... a dedicated test typing
 * field where you can log all my keystrokes without any privacy issues"). Shown in debug builds and with developer
 * options. The key log covers only the field on this page (KeyLog): each tap's position, each suggestion picked, and
 * every change to the field's text (typed, corrected, deleted), in memory until cleared.
 */
@Composable
fun TestingScreen() {
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val noBackupPrefs by NoBackupPreferenceStore
    val events by KeyLog.events.collectAsState()
    SetPage("Testing") {
        SetGroup("Setup") {
            SetPageLink("t:onboarding", "Replay the setup", "The first-run setup, from its welcome page") {
                scope.launch { noBackupPrefs.isImeSetUp.set(false) }
                nav.navigate(Routes.Setup.Screen()) { popUpTo(Routes.Settings.Home) { inclusive = true } }
            }
            SetPageLink("t:devtools", "Developer options", "Touch areas, overlays and logs") { nav.navigate(Routes.Devtools.Home) }
        }
        SetGroup("Key log") {
            Column(Modifier.fillMaxWidth().background(SetColors.row).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DText(
                    "Type in this field: every tap, picked suggestion and change to its text is listed below. Only this field is logged, in memory, until you clear it or the app stops. Nothing is saved or sent.",
                    style = setText(14f, color = SetColors.onSurfaceVariant), maxLines = 8,
                )
                AndroidView(
                    factory = { ctx -> logField(ctx) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(12.dp)).background(SetColors.screen),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SetPageLink("t:copy", "Copy the log", "${events.size} lines", icon = "content_copy") {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("DogBoard key log", events.joinToString("\n")))
                        Toast.makeText(context, "Key log copied", Toast.LENGTH_SHORT).show()
                    }
                }
                SetPageLink("t:clear", "Clear the log", "Empties the list", icon = "delete") { KeyLog.clear() }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SetColors.screen).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (events.isEmpty()) DText("Nothing logged yet.", style = setText(13f, color = SetColors.onSurfaceVariant))
                    for (line in events.takeLast(200).reversed()) DText(line, style = setText(12f).copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp), maxLines = 3)
                }
            }
        }
    }
}

/** The logged field: marked for the keyboard (KeyLog.MARK), and its own text changes logged as typed, deleted or replaced. */
private fun logField(ctx: Context): EditText = EditText(ctx).apply {
    privateImeOptions = KeyLog.MARK
    hint = "Type here to log it"
    minLines = 3
    setPadding(32, 24, 32, 24)
    background = null
    addTextChangedListener(object : TextWatcher {
        private var before = ""
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { before = s?.toString().orEmpty() }
        override fun onTextChanged(s: CharSequence?, start: Int, b: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val now = s?.toString().orEmpty()
            if (now == before) return
            // the changed middle: what went and what came
            var p = 0
            while (p < before.length && p < now.length && before[p] == now[p]) p++
            var q = 0
            while (q < before.length - p && q < now.length - p && before[before.length - 1 - q] == now[now.length - 1 - q]) q++
            val gone = before.substring(p, before.length - q); val came = now.substring(p, now.length - q)
            fun show(x: String) = "“" + x.replace("\n", "⏎") + "”"
            when {
                gone.isEmpty() -> KeyLog.add("type", show(came))
                came.isEmpty() -> KeyLog.add("delete", show(gone))
                else -> KeyLog.add("replace", "${show(gone)} → ${show(came)}")
            }
        }
    })
}
