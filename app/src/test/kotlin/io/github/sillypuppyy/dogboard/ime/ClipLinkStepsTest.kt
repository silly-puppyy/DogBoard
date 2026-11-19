package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipLinkStepsTest : FunSpec({
    val link = "https://news.example.com/night-trains?utm_source=share&utm_medium=x"

    test("removing trackers shows a mark and changes the paste") {
        val s = ClipLinkSteps.of(link, expand = true, strip = true)
        s.strips shouldBe true
        s.expands shouldBe false // no short link
        s.pasted shouldBe "https://news.example.com/night-trains"
    }

    test("a clip that keeps its trackers still shows the (undone) mark and pastes as copied") {
        val s = ClipLinkSteps.of(link, expand = true, strip = false)
        s.strips shouldBe true
        s.pasted shouldBe link
    }

    test("plain text has no marks") {
        val s = ClipLinkSteps.of("See you at 7", expand = true, strip = true)
        s.strips shouldBe false
        s.expands shouldBe false
        s.pasted shouldBe "See you at 7"
    }
})
