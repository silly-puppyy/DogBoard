package io.github.sillypuppyy.dogboard.uitest

import android.app.Instrumentation
import android.content.Intent
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

    fun shell(cmd: String): String =
        ui.executeShellCommand(cmd).let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() } }

    /** Makes DogBoard the keyboard (the package is installed by the test run). */
    fun selectDogBoard() {
        shell("ime enable $IME")
        shell("ime set $IME")
    }

    /** Opens the test screen with a fresh field ("field": email, url, name, caps, number...), and waits for the keys. */
    fun open(field: String? = null): KeyArea {
        selectDogBoard()
        // not cleared: the keyboard logs its key area only when the bounds change, so the last line is still
        // current; an empty-text mark keeps an earlier test's text from being read
        shell("logcat -G 16M")
        shell("log -t DogBoardText text=[]")
        // the test screen runs in its own process (":imetest"), as an app apart from the keyboard; the test runs in
        // the keyboard's process, so it can reach the keyboard's state (LiveUi) but reads the field from the log
        val intent = Intent().setClassName(context, "io.github.sillypuppyy.dogboard.imetest.ImeTestActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (field != null) intent.putExtra("field", field)
        context.startActivity(intent)
        return waitForKeyArea() ?: error("the keyboard never reported its key area (is DogBoard selected? ${shell("settings get secure default_input_method").trim()})")
    }

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

    /** Waits until the engine has its word data ("engine ready"), else typing is not corrected. */
    fun waitForEngine(timeoutMs: Long = 30_000) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if ("engine ready" in shell("logcat -d -s DogBoardPerf:I")) return
            SystemClock.sleep(300)
        }
    }

    /** Types [text] ('{123}' = the mode key, '<' = backspace) by tapping keys in [area]. */
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
    data class KeyArea(val x: Float, val y: Float, val w: Float, val h: Float, val rows: Int) {
        val unit get() = w / 10f
        val rowH get() = h / rows

        fun keyCentre(token: String): Pair<Float, Int> = when {
            token == " " -> SPACE_CENTER_X to rows - 1
            token == "{123}" -> MODE_CENTER_X to rows - 1
            token == "<" -> BACKSPACE_CENTER_X to rows - 2
            token in DIGITS -> DIGITS.indexOf(token) + 0.5f to rows - 4
            token in SYM_ROW2 -> SYM_ROW2.indexOf(token) + 0.5f to rows - 3
            token in WIDE_PUNCT -> 1.28f + 0.2133f + WIDE_PUNCT.indexOf(token) * 1.4f + 0.7f to rows - 2
            else -> LETTER_ROWS.withIndex().firstNotNullOfOrNull { (r, letters) ->
                letters.indexOf(token.lowercase()).takeIf { it >= 0 }?.let { ROW_X0[r] + it + 0.5f to rows - 4 + r }
            } ?: error("no key for '$token'")
        }
    }

    companion object {
        const val IME = "io.github.sillypuppyy.dogboard.debug/dev.patrickgold.florisboard.FlorisImeService"
        private val GEOM = Regex("""key area (\d+) (\d+) (\d+) (\d+) rows (\d+)""")
        private val TOKENS = Regex("""\{123\}|.""")
        private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val ROW_X0 = listOf(0f, 0.5f, 48f / 37.5f + 8f / 37.5f)
        private const val SPACE_CENTER_X = (46.5f + 47f + 188f / 2) / 37.5f
        private const val BACKSPACE_CENTER_X = 10 - 1.28f / 2
        private const val MODE_CENTER_X = 1.24f / 2
        private const val DIGITS = "1234567890"
        private const val SYM_ROW2 = "-/:;()$&@\""
        private const val WIDE_PUNCT = ".,?!'"
    }
}
