package io.github.sillypuppyy.dogboard.ime

import io.github.sillypuppyy.dogboard.contacts.ContactCard
import io.github.sillypuppyy.dogboard.design.KbChipData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The designer's contact chips: one contact → name, number, birthday; several → up to three names. */
class ContactChipsTest : FunSpec({
    test("date chips: Today, Tomorrow, the day after by name, each typing the short date") {
        val chips = LiveStrip.dateChips(java.time.LocalDate.of(2026, 11, 14), java.util.Locale.US)
        chips.map { it.label } shouldBe listOf("Today", "Tomorrow", "Mon 16 Nov")
        chips.map { it.insert } shouldBe listOf("11/14/26", "11/15/26", "11/16/26")
    }

    test("one contact: full name, number and birthday; several: their names; nothing for other words") {
        LiveStrip.contactCards = mapOf(
            "sam" to listOf(ContactCard("Sam Lee", "+1 555-0142", "3 December")),
            "alex" to listOf(ContactCard("Alex Kim", null, null), ContactCard("Alex Roe", "+44 20 7946 0000", null)),
        )
        try {
            LiveStrip.contactChips("Sam") shouldBe listOf(KbChipData("person", "Sam Lee"), KbChipData("call", "+1 555-0142"), KbChipData("cake", "3 December"))
            LiveStrip.contactChips("alex") shouldBe listOf(KbChipData("person", "Alex Kim"), KbChipData("person", "Alex Roe"))
            LiveStrip.contactChips("sa") shouldBe emptyList()
            LiveStrip.contactChips("s") shouldBe emptyList()
        } finally {
            LiveStrip.contactCards = emptyMap()
        }
    }
})
