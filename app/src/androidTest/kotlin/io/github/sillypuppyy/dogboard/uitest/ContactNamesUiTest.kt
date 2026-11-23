package io.github.sillypuppyy.dogboard.uitest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contact names reach the keyboard ("add contacts to our emulator and see if the keyboard can use
 * them"): with contacts access granted and Settings › Suggestions › Contact names on, a name that only a contact holds
 * is corrected to and capitalised. Needs the contact Saoirse Quinlan on the device (added to dogboard-test by adb).
 */
@RunWith(AndroidJUnit4::class)
class ContactNamesUiTest {
    private val kb = KeyboardDriver()
    private val prefs by FlorisPreferenceStore

    @Before fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, android.Manifest.permission.READ_CONTACTS)
        kb.selectDogBoard()
        runBlocking { prefs.spelling.useContacts.set(true) }
        android.os.SystemClock.sleep(1500) // the names load on the preference change
    }
    @After fun tearDown() {
        runBlocking { prefs.spelling.useContacts.set(false) }
        kb.close()
    }

    @Test fun contactNameIsCorrectedTo() {
        val names = io.github.sillypuppyy.dogboard.contacts.ContactNames.read(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeTrue("the test contact is on this device", names.any { it.equals("Saoirse", ignoreCase = true) })
        for ((typed, want) in listOf("hi saoirse x" to "Hi Saoirse x", "hi saoitse x" to "Hi Saoirse x", "ask dmitri x" to "Ask Dmitri x")) {
            val area = kb.open()
            kb.waitForEngine()
            kb.type(area, typed)
            assertEquals(typed, want, kb.text())
        }
    }
}
