package io.github.sillypuppyy.dogboard.uitest

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.random.Random

/**
 * Drives DogBoard on a device the way a person does (the instrumented twin of tools/test/type_test.py): selects
 * it as the keyboard, opens the debug test screen, and types by injecting touches at the keys' centres with real
 * timings (measured on the user's phone 2026-11-20: ~140 ms from key to key, ~60 ms down). Runs on emulators
 * and on Firebase Test Lab devices; screenshots go to the app's files dir (`uitest/`), which Test Lab can pull.
 */
class KeyboardDriver(
    /** ms between taps and how long each stays down. */
    private val gapMs: Long = 140,
    private val downMs: Long = 60,
    /** Touch noise in px (0 = key centres), seeded for repeatable runs. */
    private val noisePx: Float = 0f,
    seed: Int = 1,
) {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val context = instrumentation.targetContext
    private val rng = Random(seed)
    /** The keyboard under test: `-e keyboard gboard` runs the same cases on Gboard (the emulator's, for comparison). */
    val isGboard = InstrumentationRegistry.getArguments().getString("keyboard") == "gboard"
    private val imeId = if (isGboard) GBOARD else IME

    fun shell(cmd: String): String =
        ui.executeShellCommand(cmd).let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() } }

    /** Makes the keyboard under test (DogBoard unless `-e keyboard gboard`) the keyboard. */
    fun selectDogBoard() {
        // the neural model on, as on a phone in use (the emulator's 4 GB is under its RAM bar; debug builds read this
        // at load; a property is lost on reboot: 'going hom' went uncorrected after one, 2026-11-20)
        if (!isGboard) shell("setprop debug.dogboard.neural 1")
        shell("ime enable $imeId")
        shell("ime set $imeId")
    }

    /** Opens the test screen with a fresh field ("field": email, url, name, caps, number...), and waits for the keys. */
    fun open(field: String? = null): KeyArea {
        selectDogBoard()
        // not cleared: the keyboard logs its key area only when the bounds change, so the last line is still
        // current; an empty-text mark keeps an earlier test's text from being read
        shell("logcat -G 16M")
        shell("log -t DogBoardText text=[]")
        val shownBefore = shownCount()
        // the test screen runs in its own process (":imetest"), as an app apart from the keyboard; the test runs in
        // the keyboard's process, so it can reach the keyboard's state (LiveUi) but reads the field from the log
        // started by the shell, not context.startActivity: HyperOS blocks an app's background activity starts
        // ("MIUILOG- Permission Denied Activity", Test Lab Xiaomi 14, 2026-11-20); never -S, which force-stops the
        // package, the keyboard included
        shell("am start -W --activity-clear-task -n ${context.packageName}/io.github.sillypuppyy.dogboard.imetest.ImeTestActivity" +
            (field?.let { " --es field $it" } ?: ""))
        // this screen's keyboard, not the last one: a new "onWindowShown" first (a key area line from before would
        // be read at once, and the first taps lost: ' X' for 'teh x', 2026-11-20)
        if (isGboard) { SystemClock.sleep(3500); return gboardArea() }
        val end = SystemClock.uptimeMillis() + 15_000
        while (shownCount() <= shownBefore && SystemClock.uptimeMillis() < end) SystemClock.sleep(200)
        // a settle pause: on a slow emulator the first tap after the keyboard appears was dropped ('Nsplash',
        // ' Use Gboard', 'Eh-' for 'teh-', 2026-11-20, 2 vCPUs + the neural model)
        return (waitForKeyArea() ?: error("the keyboard never reported its key area (is DogBoard selected? ${shell("settings get secure default_input_method").trim()})")).also { SystemClock.sleep(SETTLE_MS) }
    }

    /**
     * Gboard 17.2's key area, measured from a screenshot on the dogboard-test emulator (1080 x 2424, 420 dpi,
     * 2026-11-20): first row's keys from y 1678, rows 154.7 px apart, 15 px gaps. Other screens: measure again.
     */
    private fun gboardArea(): KeyArea {
        val (w, h) = context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        check(w == 1080) { "Gboard's key area is measured for the 1080 px wide emulator only (this screen: $w x $h)" }
        return KeyArea(0f, 1663f, 1080f, 619f, 4, gboard = true)
    }

    private fun shownCount() = shell("logcat -d -s DogBoardPerf:I").lineSequence().count { "onWindowShown" in it }

    /** The key area's screen bounds from DogBoard's DogBoardGeom log line, once it stops moving (slide-in). */
    private fun waitForKeyArea(timeoutMs: Long = 15_000): KeyArea? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var previous: KeyArea? = null
        while (SystemClock.uptimeMillis() < end) {
            val line = shell("logcat -d -s DogBoardGeom:I").lineSequence().lastOrNull { "key area" in it }
            val m = line?.let { GEOM.find(it) }
            if (m != null) {
                val (x, y, w, h, rows) = m.destructured
                val area = KeyArea(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), rows.toInt())
                if (area.h > 0 && area == previous) return area
                previous = area
            }
            SystemClock.sleep(300)
        }
        return null
    }

    /**
     * Forgets what earlier runs taught the keyboard (Your words > Forget: learned words, rejections, personal model),
     * once per run, and waits for the reload. A case that fails leaves its typo uncorrected, the keyboard learns it,
     * and every later run fails the same way ("Thankyou" learned on the emulator, 2026-11-20).
     */
    fun resetLearningOnce(timeoutMs: Long = 60_000) {
        if (learningReset) return
        learningReset = true
        if (isGboard) {
            // Gboard's own learning and settings back to the defaults (the emulator's Gboard only); it restarts
            shell("pm clear $GBOARD_PACKAGE"); selectDogBoard(); SystemClock.sleep(3000)
            warmUp(); return
        }
        open(); waitForEngine()
        val readyBefore = readyCount()
        io.github.sillypuppyy.dogboard.engine.TypingEngine.resetDictionary()
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (readyCount() <= readyBefore && SystemClock.uptimeMillis() < end) SystemClock.sleep(300)
        warmUp()
    }

    /**
     * One word typed and thrown away after a reset: the first word after it went uncorrected on both keyboards
     * ('teh x' -> 'Teh x' as the run's first case only, 2026-11-20), so no case is the first.
     */
    private fun warmUp() {
        val area = open(); waitForEngine()
        type(area, "the dog ")
        SystemClock.sleep(1000)
    }

    private fun readyCount() = shell("logcat -d -s DogBoardPerf:I").lineSequence().count { "letter model attached" in it }

    /** Waits until the engine has loaded everything (its last stage, the letter model). */
    fun waitForEngine(timeoutMs: Long = 30_000) {
        if (isGboard) { SystemClock.sleep(2000); return }
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            // the last stage, as on a phone in use: the neural model and the letter model load after "engine ready",
            // and a word typed before decides without them ('thankyou' left as typed, 2026-11-20)
            if ("letter model attached" in shell("logcat -d -s DogBoardPerf:I")) return
            SystemClock.sleep(300)
        }
    }

    /** Types [text] ('{123}' = the mode key, '^' = shift, '<' = backspace) by tapping keys in [area]. */
    fun type(area: KeyArea, text: String) {
        for (token in TOKENS.findAll(text).map { it.value }) {
            val (ux, row) = area.keyCentre(token)
            tap(area.x + ux * area.unit + noise(), area.y + (row + 0.5f) * area.rowH + noise())
            SystemClock.sleep(gapMs)
        }
        SystemClock.sleep(600) // the last word's decode and any correction
        instrumentation.waitForIdleSync()
    }

    private fun noise() = if (noisePx == 0f) 0f else (rng.nextDouble() * 2 - 1).toFloat() * noisePx

    fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        SystemClock.sleep(downMs)
        inject(MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
    }

    /** A hardware key press (down, up) injected directly: no `input` process started in the emulator per key. */
    fun key(code: Int, meta: Int = 0) {
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
            ui.injectInputEvent(android.view.KeyEvent(t, t, action, code, 0, meta), true)
    }

    /** Empties the focused field (select all, delete). */
    fun clearField() {
        key(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON)
        key(android.view.KeyEvent.KEYCODE_DEL)
    }

    private fun inject(e: MotionEvent) {
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        ui.injectInputEvent(e, true)
        e.recycle()
    }

    /** The test field's text, from the test screen's DogBoardText log line ('text=...'). */
    fun text(): String =
        shell("logcat -d -s DogBoardText:I").lineSequence().lastOrNull { "text=[" in it }?.substringAfter("text=[")?.substringBeforeLast("]").orEmpty()

    /** Opens a keyboard panel ("clipboard", "emoji", "tools", "" = none) as the bar's buttons do. */
    fun panel(name: String) {
        instrumentation.runOnMainSync { io.github.sillypuppyy.dogboard.ime.LiveUi.panel.value = name }
        SystemClock.sleep(800)
    }

    /** A screenshot to files/uitest/[name].png (Test Lab: --directories-to-pull). */
    fun screenshot(name: String): File {
        val dir = File(context.getExternalFilesDir(null), "uitest").apply { mkdirs() }
        val f = File(dir, "$name.png")
        ui.takeScreenshot()?.let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }; b.recycle() }
        return f
    }

    /** Leaves the test screen (not a force-stop: the keyboard lives in the same package). */
    fun close() {
        panel("")
        shell("input keyevent 3")
    }

    /** The key area on screen; key positions in keyboard3 width units (10 per row), as gen_layouts.py lays them out. */
    data class KeyArea(val x: Float, val y: Float, val w: Float, val h: Float, val rows: Int, val gboard: Boolean = false) {
        val unit get() = w / 10f
        val rowH get() = h / rows

        fun keyCentre(token: String): Pair<Float, Int> = when {
            // Gboard's bottom row: ?123 , emoji space . enter (screenshot 2026-11-20)
            gboard && token == " " -> 5.49f to rows - 1
            gboard && token == "," -> 2.03f to rows - 1
            gboard && token == "." -> 7.96f to rows - 1
            gboard && token == "{123}" -> error("Gboard's symbols page is laid out differently: no {123} cases on Gboard")
            token == " " -> SPACE_CENTER_X to rows - 1
            token == "{123}" -> MODE_CENTER_X to rows - 1
            token == "<" -> BACKSPACE_CENTER_X to rows - 2
            token == "^" -> SHIFT_CENTER_X to rows - 2
            token in DIGITS -> DIGITS.indexOf(token) + 0.5f to rows - 4
            token in SYM_ROW2 -> SYM_ROW2.indexOf(token) + 0.5f to rows - 3
            token in WIDE_PUNCT -> 1.5f + WIDE_PUNCT.indexOf(token) * 1.4f + 0.7f to rows - 2 // #+= 1.5, five 1.4 keys
            else -> LETTER_ROWS.withIndex().firstNotNullOfOrNull { (r, letters) ->
                letters.indexOf(token.lowercase()).takeIf { it >= 0 }?.let { ROW_X0[r] + it + 0.5f to rows - 4 + r }
            } ?: error("no key for '$token'")
        }
    }

    companion object {
        /** resetLearningOnce ran in this process (one per instrumentation run). */
        private var learningReset = false
        private const val SETTLE_MS = 700L
        const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        const val GBOARD = "$GBOARD_PACKAGE/com.android.inputmethod.latin.LatinIME"
        const val IME = "io.github.sillypuppyy.dogboard.debug/dev.patrickgold.florisboard.FlorisImeService"
        private val GEOM = Regex("""key area (\d+) (\d+) (\d+) (\d+) rows (\d+)""")
        private val TOKENS = Regex("""\{123\}|.""")
        private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val ROW_X0 = listOf(0f, 0.5f, 1.5f) // gen_layouts.py: shift and delete 1.5 units (Gboard)
        private const val SPACE_CENTER_X = (46.5f + 47f + 188f / 2) / 37.5f
        private const val BACKSPACE_CENTER_X = 10 - 1.5f / 2
        private const val MODE_CENTER_X = 1.24f / 2
        private const val SHIFT_CENTER_X = 1.5f / 2
        private const val DIGITS = "1234567890"
        private const val SYM_ROW2 = "-/:;()$&@\""
        private const val WIDE_PUNCT = ".,?!'"
    }
}
