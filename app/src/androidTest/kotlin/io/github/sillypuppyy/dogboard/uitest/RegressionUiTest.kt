package io.github.sillypuppyy.dogboard.uitest

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every typing bug the user reported, typed on the real keyboard (shift, symbols page, punctuation included): each
 * must keep passing. Add a case with each new report. '|' separates acceptable results (a typo iOS leaves alone may
 * also be fixed). Typed: '^' shift, '{123}' the symbols page; the field autocapitalises its start.
 */
@RunWith(Parameterized::class)
class RegressionUiTest(private val typed: String, private val expected: String, @Suppress("unused") private val report: String) {
    private val kb = KeyboardDriver()

    @Before fun setUp() {
        kb.selectDogBoard()
        kb.resetLearningOnce()
    }
    @After fun tearDown() = kb.close()

    @Test fun typesAsExpected() {
        assumeFalse("Gboard: no symbols-page cases", kb.isGboard && "{123}" in typed)
        val area = kb.open()
        kb.waitForEngine()
        kb.type(area, typed)
        val got = kb.text()
        assertTrue("'$typed' -> '$got', expected '$expected'", got in expected.split('|'))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{2}")
        fun cases() = listOf(
            arrayOf("x ^di^t y", "X DiT y", "DiT kept (11-20, DiT -> Dit -> Fit)"),
            arrayOf("thankyou x", "Thank you x", "thankyou split (11-20 iPhone check)"),
            arrayOf("teh{123}-", "The-", "teh before a hyphen (11-20 iPhone check)"),
            arrayOf("nonbnglish x", "Nonbnglish x|Non-English x", "no garbage split (11-20, bomb flush)"),
            arrayOf("hm x", "Hm x", "Hm not HM (11-19)"),
            // the report's fix: "hm" reaches H&M (decoder steps over '&'); a typed '&' ends the word, as iOS's word-ending
            // characters do (all but @#$%^*_([]\|), so the guard is only that nothing worse than "H&m" comes out
            arrayOf("h{123}&{123}m x", "H&m x|H&M x", "H&M typed with the ampersand (11-19)"),
            arrayOf("unsplash x", "Unsplash x", "Unsplash not u splash (11-19)"),
            arrayOf("i use gboard x", "I use Gboard x", "gboard not board (11-19)"),
            arrayOf("going hom x", "Going home x", "hom -> home in context (11-19)"),
            arrayOf("later andnwaited x", "Later and waited x", "andnwaited, later kept (11-19)"),
            arrayOf("the {123}1{123}sr x", "The 1st x", "1sr -> 1st (11-20)"),
            arrayOf("ofthe x", "Of the x", "two-word correction (11-19)"),
        )
    }
}
