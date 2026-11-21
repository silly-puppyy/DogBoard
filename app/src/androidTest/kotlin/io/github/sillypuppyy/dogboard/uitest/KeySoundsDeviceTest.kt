package io.github.sillypuppyy.dogboard.uitest

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sillypuppyy.dogboard.feel.KeySounds
import org.junit.Test
import org.junit.runner.RunWith

/** Every key sound style loads and plays each kind (the recorded packs from assets); logs its "key sounds" line. */
@RunWith(AndroidJUnit4::class)
class KeySoundsDeviceTest {
    @Test fun everyStyleLoadsAndPlays() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (style in KeySounds.styleIds) {
            KeySounds.prepare(context, style, 60)
            for (k in KeySounds.Kind.entries) { KeySounds.play(k, 0.01f); Thread.sleep(30) }
        }
    }
}
