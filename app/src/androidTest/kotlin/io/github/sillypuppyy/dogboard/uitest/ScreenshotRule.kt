package io.github.sillypuppyy.dogboard.uitest

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * A screenshot of the screen as every test left it (files/uitest/<Class>-<test>.png), renamed FAIL-... when the test
 * failed, so a Test Lab run shows what each device drew, not only the few fixed shots ("capture
 * screenshots properly"). Call [end] from @After before the keyboard closes: a rule's own hooks run after @After.
 */
class ScreenshotRule(private val kb: KeyboardDriver) : TestWatcher() {
    private var name = "test"
    private var last: File? = null

    override fun starting(description: Description) {
        name = "${description.testClass.simpleName}-${description.methodName}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        last = null
    }

    fun end() {
        last = runCatching { kb.screenshot(name) }.getOrNull()
    }

    override fun failed(e: Throwable, description: Description) {
        val f = last ?: return
        f.renameTo(File(f.parentFile, "FAIL-${f.name}"))
    }
}
