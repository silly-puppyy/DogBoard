package io.github.sillypuppyy.dogboard.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class SettingsSearchTest : FunSpec({
    fun titles(q: String) = SettingsSearch.search(q).map { it.title }
    test("synonyms find settings (vibration finds Feel settings, quotes finds Punctuation)") {
        titles("vibration") shouldContain "Vibration style"
        titles("quotes") shouldContain "Smart quotes"
        titles("long press") shouldContain "Hold to show accents"
        titles("haptic") shouldContain "Vibration"
    }
    test("word starts match, all words must match, empty finds nothing") {
        titles("capit") shouldContain "Capitalize sentence starts"
        titles("smart dash").size shouldBe 1
        titles("   ").shouldBeEmpty()
        titles("zzzz").shouldBeEmpty()
    }
})
