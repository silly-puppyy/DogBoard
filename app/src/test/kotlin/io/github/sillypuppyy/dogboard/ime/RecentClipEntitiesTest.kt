package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** Gboard's regex extractor for clipboard chips, beside the system classifier. */
class RecentClipEntitiesTest : FunSpec({
    fun of(t: String) = RecentClip.patternEntities(t).map { it.second to it.third }
    test("a phone number inside text") {
        of("call samantha 0483994029") shouldContain ("call" to "0483994029")
        of("ring +1 415 555 0142 later") shouldContain ("call" to "+1 415 555 0142")
    }
    test("emails and links as whole words") {
        of("mail me at sam@example.org, thanks") shouldContain ("mail" to "sam@example.org")
        of("see example.org/recipes.") shouldContain ("link" to "example.org/recipes")
        of("https://dogboard.dev is up") shouldContain ("link" to "https://dogboard.dev")
    }
    test("a code is a 4-8 digit word, not part of a phone number") {
        of("your code is 482913") shouldContain ("pin" to "482913")
        of("call samantha 0483994029").count { it.first == "pin" } shouldBe 0
        of("just words here").shouldBeEmpty()
    }
    test("no call chip inside a longer run of digits (card numbers, millisecond timestamps)") {
        of("4111111111111111").none { it.first == "call" } shouldBe true
        of("1732451234567").none { it.first == "call" } shouldBe true
        of("call 555-123-4567 now") shouldContain ("call" to "555-123-4567")
    }
    test("a file name or an abbreviation is no link; a domain or a scheme is") {
        of("open index.html and main.kt").none { it.first == "link" } shouldBe true
        of("e.g. this").none { it.first == "link" } shouldBe true
        of("try dogboard.dev today") shouldContain ("link" to "dogboard.dev")
        of("see bbc.co.uk") shouldContain ("link" to "bbc.co.uk")
        of("https://intranet.local/x") shouldContain ("link" to "https://intranet.local/x")
    }
})
