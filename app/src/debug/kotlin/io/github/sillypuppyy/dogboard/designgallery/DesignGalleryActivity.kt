package io.github.sillypuppyy.dogboard.designgallery

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sillypuppyy.dogboard.design.DesignLint
import io.github.sillypuppyy.dogboard.design.KbProps
import io.github.sillypuppyy.dogboard.design.LocalDesignLint
import io.github.sillypuppyy.dogboard.design.KbSpec
import io.github.sillypuppyy.dogboard.design.KbView

/**
 * Debug-only gallery of the ported design: the frames of States.dc.html (States:47-84) with their
 * exact props, drawn by the Kotlin port, for side-by-side checks against the canvas.
 * Extra "frame" (index) shows a single frame; "dark" true flips the page background.
 */
class DesignGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val only = intent.getIntExtra("frame", -1)
        val all = STATES + PANELS
        val frames = if (only in all.indices) listOf(only to all[only]) else all.withIndex().map { it.index to it.value }
        setContent {
            LazyColumn(
                Modifier.fillMaxSize().background(Color(0xFFF7F7F2)),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(frames) { (index, f) ->
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        BasicText(f.title, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                        BasicText(f.note, Modifier.padding(bottom = 8.dp), style = TextStyle(fontSize = 14.sp, color = Color(0xFF47483B)))
                        val render = remember(f) { KbSpec.render(f.props) }
                        val lint = remember(f) { DesignLint() }
                        Box(Modifier.padding(top = f.top.dp, bottom = f.bottom.dp).horizontalScroll(rememberScrollState())) {
                            CompositionLocalProvider(LocalDesignLint provides lint) { KbView(render) }
                        }
                        // lint after the frame is laid out (two frames: layout, then positions)
                        LaunchedEffect(f) {
                            withFrameNanos { }
                            withFrameNanos { }
                            val problems = lint.check("kb", render.W)
                            if (problems.isEmpty()) Log.i(TAG, "frame $index OK: ${f.title}")
                            for (p in problems) Log.w(TAG, "frame $index FAIL: ${f.title}: $p")
                            Log.i(TAG, "frame $index done")
                        }
                    }
                }
            }
        }
    }

    data class Frame(val title: String, val note: String, val props: KbProps, val top: Int = 0, val bottom: Int = 0)

    companion object {
        const val TAG = "DesignLint"

        // Panels.dc.html:100-108 (bar cards, only = bar) and 128-145 (panel and mode cells, defaults
        // Panels:86: bar empty, ret send)
        val PANELS = listOf(
            Frame("Bar: Text replacement", "Your shortcut wins over autocorrect and shows as the chip.", KbProps(only = "bar", bar = "omw")),
            Frame("Bar: Undone correction", "Delete after a correction restores the word, ringed. teh → the is never auto-applied again.", KbProps(only = "bar", bar = "undone")),
            Frame("Bar: Removed suggestion", "Long-press any suggestion to remove it for good. Undo stays 4 s.", KbProps(only = "bar", bar = "removed")),
            Frame("Bar: Copied an image", "Recent-copy chip replaces suggestions for 60 s. Tap to paste.", KbProps(only = "bar", bar = "clip")),
            Frame("Bar: Passwords", "System autofill chips sit in the slot area, never over the keys.", KbProps(only = "bar", bar = "autofill")),
            Frame("Bar: Incognito", "Mask in place of tools. Nothing is learned; predictions are off.", KbProps(only = "bar", bar = "empty", incognito = true)),
            Frame("Bar: Tools row open", "The tools button fills; up to six pinned tools; the last opens the full Tools panel.", KbProps(only = "bar", bar = "tools")),
            Frame("Bar: Dictation", "On-device voice (future). The mic becomes Stop on a primary circle.", KbProps(only = "bar", bar = "voice")),
            Frame("Bar: Dark", "Same layout, dark roles.", KbProps(only = "bar", bar = "teh", dark = true)),
            Frame("Tools row in the bar", "One tap on the left button. Pinned tools only; the last button opens everything.", KbProps(bar = "tools", ret = "send")),
            Frame("Tools panel", "4 × 3 tiles, 16 dp corners, 20 dp icons. Drag to reorder, long-press to pin. Edit opens the actions editor.", KbProps(panel = "tools", ret = "send")),
            Frame("Clipboard", "Two-column grid, newest first. Images and video as previews; pin keeps a clip past the 1-hour limit.", KbProps(panel = "clipboard", ret = "send")),
            Frame("Clipboard · long-press a clip", "The clip is ringed and offers Pin and Delete. Clear (header) removes unpinned clips only.", KbProps(panel = "clipboard", panelVariant = "select", ret = "send")),
            Frame("Clipboard · locked", "Before unlock, nothing is shown, pinned clips included.", KbProps(panel = "clipboard", panelVariant = "locked", ret = "send")),
            Frame("Clipboard · history off", "Off by default, with one clear reason to turn it on.", KbProps(panel = "clipboard", panelVariant = "empty", ret = "send")),
            Frame("Emoji + skin tone", "Search on top, recents first, categories at the bottom. Long-press opens tones; the chosen one becomes the default.", KbProps(panel = "emoji", skinPicker = true, ret = "send"), top = 40),
            Frame("Text editing", "Cursor pad with a Select toggle; Select all, Copy, Cut, Paste, Home, End.", KbProps(panel = "edit", ret = "send")),
            Frame("One-handed · right", "Keys at 84% width. The strip moves it to the other side or back to full width.", KbProps(oneHanded = "right", bar = "teh", ret = "send")),
            Frame("One-handed · left", "For left-handed use. Remembered per orientation.", KbProps(oneHanded = "left", bar = "hi", ret = "send")),
            Frame("Floating", "80% width, 28 dp corners, drag handle on top. Resize from the corners (60–100%).", KbProps(floating = true, width = 300f, ret = "send"), top = 28, bottom = 28),
            Frame("Developer row", "Off by default; per-app. Ctrl armed for one key (tonal + dot), Alt locked by double-tap (primary + bar).", KbProps(dev = true, devCtrl = "armed", devAlt = "locked", bar = "empty", ret = "return")),
            Frame("Incognito", "Automatic in private fields, or from Tools. Mask stays visible the whole time.", KbProps(incognito = true, ret = "send")),
            Frame("Dictation (future)", "Listening state in the bar; words stream into the field; punctuation spoken or added by the model.", KbProps(bar = "voice", ret = "send")),
            Frame("Clipboard, dark", "Tiles on surfaceContainerHigh.", KbProps(panel = "clipboard", dark = true, ret = "send")),
            Frame("Tools, Dog theme", "Panels inherit every theme role.", KbProps(panel = "tools", theme = "dog", ret = "send")),
        ) + listOf("return", "send", "search", "go", "next", "done", "join", "route", "continue").map {
            // Panels:111-119: every return word in the cap
            Frame("Return: $it", "Panels.dc.html:111-119", KbProps(ret = it))
        }

        // States.dc.html:52-84, props as overrides of States:47 defaults (= KbProps defaults)
        val STATES = listOf(
            Frame("Shift off", "Outline glyph on a function-tone key. Bar shows next-word predictions after “Hi ”.", KbProps(bar = "hi", ret = "send")),
            Frame("Shift on (you tapped it)", "The key flips to the letter tone and the glyph fills. Off again after one letter.", KbProps(shift = "on", ret = "send")),
            Frame("Auto-capital", "The glyph fills but the key keeps its function tone, so you can tell the keyboard did it, not you.", KbProps(shift = "auto", ret = "send")),
            Frame("Caps lock", "Double-tap within 300 ms. Primary-coloured key with the lock glyph; tap once to release.", KbProps(shift = "lock", ret = "send", bar = "teh")),
            Frame("Key preview: “lift” tile", "Detached tile, 16 dp corners, 6 dp above the cap, 30 sp label. Appears at touch-down, fades 70 ms after release.", KbProps(preview = "g", bar = "teh"), top = 24),
            Frame("Long-press variants", "Opens at 375 ms. The highlighted variant follows the finger; lift to type it. The first variant sits over the key.", KbProps(longpress = "e", lpSel = 1, pressed = "e"), top = 24),
            Frame("Long-press hints on", "Optional. The first variant in the top-right corner, 10 sp, onSurfaceVariant. Off by default in iOS-style.", KbProps(hints = true, bar = "keyb")),
            Frame("Space-bar trackpad", "Hold space 375 ms. Labels fade, caps dim to 55%, a ring follows the finger. Haptic tick every 8 dp of cursor travel.", KbProps(trackpad = true, bar = "dont")),
            Frame("123", "Numbers, then - / : ; ( ) \$ & @ \", then . , ? ! ’ at 1.4 units each.", KbProps(plane = "symbol-1", bar = "hi", ret = "send")),
            Frame("#+=", "[ ] { } # % ^ * + = and _ \\ | ~ < > € £ ¥ •. Long-press \$ for € £ ¥ ₩ ₽.", KbProps(plane = "symbol-2", ret = "send", longpress = "%", lpSel = 0), top = 24),
            Frame("Email field", "space 2.51 · @ 1.25 · . 1.25. Return reads “Next” because the form has another field.", KbProps(field = "email", ret = "next")),
            Frame("URL field", ". 1.68 · / 1.65 · .com 1.68. Long-press .com for .org, .edu, .net. Return reads “Go”.", KbProps(field = "url", ret = "go", longpress = ".com", lpSel = 0), top = 24),
            Frame("Numeric", "Digits on letter-tone keys, operators on function tone. Return reads “Done”.", KbProps(plane = "numeric", ret = "done")),
            Frame("Phone pad", "Letters under digits, 10 sp tracking 0.1 em. “+ * #” bottom left.", KbProps(plane = "phone", ret = "done")),
            Frame("Decimal pad", "The decimal separator follows the region (. or ,).", KbProps(plane = "decimal", ret = "done")),
            Frame("Globe long-press", "With 2+ languages the fn slot is a globe. Long-press opens this menu; slide and lift to pick.", KbProps(fnKey = "globe", langMenu = true)),
            Frame("Letters, dark", "surfaceContainerHighest keys on surfaceContainerLow. Autocorrect chip in primaryContainer.", KbProps(dark = true, bar = "teh", ret = "send")),
            Frame("Long-press, dark", "Shift on, uppercase variants.", KbProps(dark = true, shift = "on", longpress = "a", lpSel = 2, pressed = "a"), top = 24),
            Frame("Ink · AMOLED", "Pure black ground, dim caps (#1A1B1F), labels at #D9DADF to avoid glare. Return is primaryContainer, not primary.", KbProps(theme = "ink", bar = "dont", ret = "send")),
            Frame("Dog, dark", "Warm palette from the brand fur tone. A plain return shows the paw.", KbProps(theme = "dog", dark = true, bar = "emoji", shift = "lock")),
            Frame("Android-style", "Comma and period beside space, an emoji key, number hints on the top row. “?123” replaces “123”.", KbProps(kstyle = "android", bar = "teh", ret = "send")),
            Frame("Android-style + hints", "Long-press symbol hints on every letter.", KbProps(kstyle = "android", hints = true, bar = "empty", ret = "send")),
            Frame("Landscape", "162 dp key area, 4 dp vertical margins. Side areas (11% each) hold undo / redo and cursor arrows instead of stretched keys.", KbProps(landscape = true, width = 818f, bar = "teh", ret = "search")),
        )
    }
}
