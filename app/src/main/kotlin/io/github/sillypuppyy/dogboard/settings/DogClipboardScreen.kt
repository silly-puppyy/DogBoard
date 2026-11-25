package io.github.sillypuppyy.dogboard.settings

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import io.github.sillypuppyy.dogboard.design.settings.SetGroup
import io.github.sillypuppyy.dogboard.design.settings.SetSliderRow
import io.github.sillypuppyy.dogboard.design.settings.SetSwitchRow
import kotlinx.coroutines.launch

/**
 * Clipboard (settings tree v3): the common settings first, the rarely needed ones below them
 * (no "show all" switch). Every row is read by the keyboard (ClipboardManager,
 * the recent-copy chip in TextInputLayout).
 */
@Composable
fun DogClipboardScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val c = prefs.clipboard
    val history by c.historyEnabled.collectAsState()
    val merge by c.mergeRepeats.collectAsState()
    val smart by c.smartChips.collectAsState()
    val strip by c.stripTrackers.collectAsState()
    val expandShort by c.expandShortLinks.collectAsState()
    val previews by c.linkPreviews.collectAsState()
    val suggest by c.suggestionEnabled.collectAsState()
    val cleanOld by c.historyAutoCleanOldEnabled.collectAsState()
    val oldAfter by c.historyAutoCleanOldAfter.collectAsState()
    val cleanPasswords by c.historyAutoCleanSensitiveEnabled.collectAsState()
    val passwordsAfter by c.historyAutoCleanSensitiveAfter.collectAsState()
    val limitOn by c.historySizeLimitEnabled.collectAsState()
    val limit by c.historySizeLimit.collectAsState()
    val hideOnPaste by c.historyHideOnPaste.collectAsState()
    val hideNextField by c.historyHideOnNextTextField.collectAsState()
    val pasteFile by c.pasteAsFile.collectAsState()
    val shareFile by c.shareAsFile.collectAsState()
    SetPage("Clipboard") {
        SetGroup("Clipboard") {
            SetSwitchRow("cb:suggest", "Suggest what you copied", "For 2 minutes after you copy something, the bar offers to paste it.", suggest) { v ->
                scope.launch { c.suggestionEnabled.set(v) }
            }
            SetSwitchRow("cb:smart", "Link and address chips", "Phone numbers, links, emails and addresses in what you copied get chips of their own, to paste just that part.", smart) { v ->
                scope.launch { c.smartChips.set(v) }
            }
            SetSwitchRow("cb:strip", "Strip trackers from links", "Tracking tags like utm_source and fbclid are removed from links you paste with DogBoard (Brave’s list and DogBoard’s own).", strip) { v ->
                scope.launch { c.stripTrackers.set(v) }
            }
            SetSwitchRow(
                "cb:expand", "Expand links",
                "Short links like vm.tiktok.com and pin.it carry a code that points back to your account. DogBoard opens each copied link once, without cookies, and pastes where it leads instead. Needs the internet.",
                expandShort,
            ) { v -> scope.launch { c.expandShortLinks.set(v) } }
            SetSwitchRow(
                "cb:previews", "Link previews",
                "Copied links show their page title, site, picture and a video's length in the clipboard. DogBoard reads the page once, without cookies; the site sees the request. Needs the internet.",
                previews,
            ) { v -> scope.launch { c.linkPreviews.set(v); if (!v) io.github.sillypuppyy.dogboard.ime.LinkInfo.clear() } }
            SetSwitchRow("cb:history", "Clipboard history", "Keep what you copy, to paste again from the clipboard panel.", history) { v ->
                scope.launch { c.historyEnabled.set(v) }
            }
            // the clean-up works on the history's clips (and then clears the phone's clipboard too), so
            // it needs history on (ClipboardManager.enforceExpiryDate)
            if (history) SetSwitchRow("cb:merge", "Bring repeats to the top", "Copying something already in the history moves it to the top instead of adding it again.", merge) { v ->
                scope.launch { c.mergeRepeats.set(v) }
            }
            // one choice for the switch and its time (was "Passwords clear themselves" + "Clear passwords after")
            if (history) {
                val seconds = listOf(30, 60, 300)
                val at = if (!cleanPasswords) 0 else 1 + seconds.indexOfFirst { passwordsAfter <= it }.let { if (it < 0) 2 else it }
                ChoiceRow("cb:pwafter", "Clear passwords after", "How long a copied password stays in the clipboard and the history.",
                    listOf("Never", "30 s", "1 min", "5 min"), at) { i ->
                    scope.launch {
                        c.historyAutoCleanSensitiveEnabled.set(i > 0)
                        if (i > 0) c.historyAutoCleanSensitiveAfter.set(seconds[i - 1])
                    }
                }
            }
        }
        if (history) {
            SetGroup("Keeping clips") {
                // one choice for the switch and its time (was "Delete old clips" + "Keep clips for"); the setup's times
                val minutes = listOf(60, 1440, 10080)
                val at = if (!cleanOld) 0 else 1 + minutes.indexOfFirst { oldAfter <= it }.let { if (it < 0) 2 else it }
                ChoiceRow("cb:oldafter", "Delete clips after", "Unpinned clips are deleted after this long.",
                    listOf("Never", "1 hour", "1 day", "1 week"), at) { i ->
                    scope.launch {
                        c.historyAutoCleanOldEnabled.set(i > 0)
                        if (i > 0) c.historyAutoCleanOldAfter.set(minutes[i - 1])
                    }
                }
            }
            // the rarely changed ones, below the common settings
            SetGroup("History details") {
                // one choice for the switch and its count (was "Limit the history" + "Clips to keep")
                val counts = listOf(10, 20, 50)
                val at = if (!limitOn) 3 else counts.indexOfFirst { limit <= it }.let { if (it < 0) 2 else it }
                ChoiceRow("cb:limitn", "Clips to keep", "Older unpinned clips make room for new ones.",
                    listOf("10", "20", "50", "No limit"), at) { i ->
                    scope.launch {
                        c.historySizeLimitEnabled.set(i < 3)
                        if (i < 3) c.historySizeLimit.set(counts[i])
                    }
                }
                SetSwitchRow("cb:hidepaste", "Close after pasting", "The clipboard panel closes once you paste a clip.", hideOnPaste) { v ->
                    scope.launch { c.historyHideOnPaste.set(v) }
                }
                SetSwitchRow("cb:hidenext", "Close in the next field", "The clipboard panel doesn’t stay open when you move to another field.", hideNextField) { v ->
                    scope.launch { c.historyHideOnNextTextField.set(v) }
                }
                SetSwitchRow("cb:pastefile", "Paste as file", "Hold a clip to paste it as a file (text as .txt, a colour as a .png) where the app takes that kind of file.", pasteFile) { v ->
                    scope.launch { c.pasteAsFile.set(v) }
                }
                SetSwitchRow("cb:sharefile", "Share as file", "Hold a clip to send it as a file through the share sheet, to any app.", shareFile) { v ->
                    scope.launch { c.shareAsFile.set(v) }
                }
            }
        }
    }
}

/** A setting with a few named choices: title and line above, the choices below (as the setup's). */
@androidx.compose.runtime.Composable
private fun ChoiceRow(id: String, title: String, sub: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxWidth().background(io.github.sillypuppyy.dogboard.design.settings.SetColors.row).padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        io.github.sillypuppyy.dogboard.design.settings.TitleSub(title, sub)
        io.github.sillypuppyy.dogboard.design.settings.Segmented(id, options, selected, onSelect = onPick)
    }
}
