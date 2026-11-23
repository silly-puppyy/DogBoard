package io.github.sillypuppyy.dogboard.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The Themes page's Day / Night rules (SetThemes.dc.html:169-183) and what the keyboard draws. */
class ThemeChoiceTest : FunSpec({
    val start = ThemeChoice.Choice()

    test("a light theme sets Day, a dark one Night, a pair both (following the system)") {
        val (paper, m1) = ThemeChoice.pick(start, "paper")
        paper shouldBe ThemeChoice.Choice("paper", "dogboard", true)
        m1 shouldBe "Paper is a light theme, so it’s your day theme. Night stays DogBoard."
        val (ink, m2) = ThemeChoice.pick(paper, "ink")
        ink shouldBe ThemeChoice.Choice("paper", "ink", true)
        m2 shouldBe "Ink is a dark theme, so it’s your night theme. Day stays Paper."
        val (lab, m3) = ThemeChoice.pick(ink, "lab")
        lab shouldBe ThemeChoice.Choice("lab", "lab", true)
        m3 shouldBe "Labrador by day, Chocolate Lab at night."
    }

    // not following the system, one slot is used all the time; a pick changes only that slot
    test("always day: a pick sets the day slot, the night slot is kept") {
        val (c, _) = ThemeChoice.pick(start.copy(follow = false), "ink")
        c shouldBe start.copy(follow = false, day = "ink")
        ThemeChoice.resolve(c, systemDark = true).first shouldBe "ink"
    }

    test("always night: a pick sets the night slot and it is used even when the system is light") {
        val fixed = start.copy(follow = false, fixedNight = true)
        val (c, _) = ThemeChoice.pick(fixed, "ink")
        c shouldBe fixed.copy(night = "ink")
        ThemeChoice.resolve(c, systemDark = false).first shouldBe "ink"
    }

    test("tiles say where a theme is used") {
        val c = ThemeChoice.Choice("paper", "ink", true)
        ThemeChoice.where(c, "paper") shouldBe "Day"
        ThemeChoice.where(c, "ink") shouldBe "Night"
        ThemeChoice.where(c, "lab") shouldBe ""
        ThemeChoice.where(ThemeChoice.Choice("lab", "lab", true), "lab") shouldBe "Day and night"
        ThemeChoice.where(ThemeChoice.Choice("ink", "ink", false), "ink") shouldBe "In use"
    }

    test("the keyboard draws the night theme at night, each in its own version") {
        val c = ThemeChoice.Choice("paper", "ink", true)
        ThemeChoice.resolve(c, systemDark = false) shouldBe ("paper" to false)
        ThemeChoice.resolve(c, systemDark = true) shouldBe ("ink" to true)
        // a pair draws its night version at night
        ThemeChoice.resolve(ThemeChoice.Choice("dogboard", "dogboard", true), systemDark = true) shouldBe ("dogboard" to true)
        // one theme all the time: a pair stays in its day version
        ThemeChoice.resolve(ThemeChoice.Choice("dogboard", "dogboard", false), systemDark = true) shouldBe ("dogboard" to false)
    }

    test("filters") {
        ThemeChoice.keep("light", "dark") shouldBe false
        ThemeChoice.keep("light", "pair") shouldBe true
        ThemeChoice.keep("dark", "both") shouldBe true
        ThemeChoice.keep("pair", "both") shouldBe false
    }

    test("every catalogue theme has tokens") {
        for (t in ThemeChoice.THEMES) if (t.id != "glass") KbThemes.tokens(t.id, false) // throws if missing
    }
})
