package io.github.sillypuppyy.dogboard.settings

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
 * (user, 2026-11-17: no "show all" switch). Every row is read by the keyboard (ClipboardManager,
 * the recent-copy chip in TextInputLayout).
 */
@Composable
fun DogClipboardScreen() {
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val c = prefs.clipboard
    val history by c.historyEnabled.collectAsState()
    val smart by c.smartChips.collectAsState()
    val strip by c.stripTrackers.collectAsState()
    val expandShort by c.expandShortLinks.collectAsState()
    val suggest by c.suggestionEnabled.collectAsState()
    val cleanOld by c.historyAutoCleanOldEnabled.collectAsState()
    val oldAfter by c.historyAutoCleanOldAfter.collectAsState()
    val cleanPasswords by c.historyAutoCleanSensitiveEnabled.collectAsState()
    val passwordsAfter by c.historyAutoCleanSensitiveAfter.collectAsState()
    val limitOn by c.historySizeLimitEnabled.collectAsState()
    val limit by c.historySizeLimit.collectAsState()
    val hideOnPaste by c.historyHideOnPaste.collectAsState()
    val hideNextField by c.historyHideOnNextTextField.collectAsState()
    SetPage("Clipboard") {
        SetGroup("Clipboard") {
            SetSwitchRow("cb:suggest", "Suggest what you copied", "For 2 minutes after you copy something, the bar offers to paste it.", suggest) { v ->
                scope.launch { c.suggestionEnabled.set(v) }
            }
            SetSwitchRow("cb:smart", "Smart chips", "Phone numbers, links, emails and addresses in what you copied get chips of their own, to paste just that part.", smart) { v ->
                scope.launch { c.smartChips.set(v) }
            }
            SetSwitchRow("cb:strip", "Strip trackers from links", "Tracking tags like utm_source and fbclid are removed from links you paste with DogBoard (Brave’s list and DogBoard’s own).", strip) { v ->
                scope.launch { c.stripTrackers.set(v) }
            }
            SetSwitchRow(
                "cb:expand", "Expand short links",
                "Short links like vm.tiktok.com and pin.it carry a code that points back to your account. DogBoard asks the link service once where it leads and pastes that address instead. Needs the internet; only known link services are asked.",
                expandShort,
            ) { v -> scope.launch { c.expandShortLinks.set(v) } }
            SetSwitchRow("cb:history", "Clipboard history", "Keep what you copy, to paste again from the clipboard panel.", history) { v ->
                scope.launch { c.historyEnabled.set(v) }
            }
            // the clean-up works on the history's clips (and then clears the phone's clipboard too), so
            // it needs history on (ClipboardManager.enforceExpiryDate)
            if (history) SetSwitchRow("cb:passwords", "Passwords clear themselves", "Copied passwords are removed from the clipboard and the history.", cleanPasswords) { v ->
                scope.launch { c.historyAutoCleanSensitiveEnabled.set(v) }
            }
            if (history) SetSliderRow(
                "cb:pwafter", "Clear passwords after", "How long a copied password stays.",
                passwordsAfter.toFloat(), c.historyAutoCleanSensitiveAfter.default.toFloat(), 10f..600f, 10f, { v -> "${v.toInt()} s" },
                enabled = cleanPasswords,
            ) { v -> scope.launch { c.historyAutoCleanSensitiveAfter.set(v.toInt()) } }
        }
        if (history) {
            SetGroup("Keeping clips") {
                SetSwitchRow("cb:old", "Delete old clips", "Unpinned clips are deleted after a while.", cleanOld) { v ->
                    scope.launch { c.historyAutoCleanOldEnabled.set(v) }
                }
                SetSliderRow(
                    "cb:oldafter", "Delete after", "How long unpinned clips are kept.",
                    oldAfter.toFloat(), c.historyAutoCleanOldAfter.default.toFloat(), 5f..240f, 5f, { v -> "${v.toInt()} min" },
                    enabled = cleanOld,
                ) { v -> scope.launch { c.historyAutoCleanOldAfter.set(v.toInt()) } }
            }
            // the rarely changed ones, below the common settings
            SetGroup("History details") {
                SetSwitchRow("cb:limit", "Limit the history", "Keep only the most recent clips.", limitOn) { v ->
                    scope.launch { c.historySizeLimitEnabled.set(v) }
                }
                SetSliderRow(
                    "cb:limitn", "Clips to keep", "Older unpinned clips make room for new ones.",
                    limit.toFloat(), c.historySizeLimit.default.toFloat(), 5f..100f, 5f, { v -> "${v.toInt()}" },
                    enabled = limitOn,
                ) { v -> scope.launch { c.historySizeLimit.set(v.toInt()) } }
                SetSwitchRow("cb:hidepaste", "Close after pasting", "The clipboard panel closes once you paste a clip.", hideOnPaste) { v ->
                    scope.launch { c.historyHideOnPaste.set(v) }
                }
                SetSwitchRow("cb:hidenext", "Close in the next field", "The clipboard panel doesn’t stay open when you move to another field.", hideNextField) { v ->
                    scope.launch { c.historyHideOnNextTextField.set(v) }
                }
            }
        }
    }
}
