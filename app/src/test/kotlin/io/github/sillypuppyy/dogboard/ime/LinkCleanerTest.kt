package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LinkCleanerTest : FunSpec({
    test("campaign and click tags go, the page's own parameters stay in order") {
        LinkCleaner.cleanUrl("https://example.com/a?id=7&utm_source=news&utm_medium=email&page=2&fbclid=AbC") shouldBe
            "https://example.com/a?id=7&page=2"
        LinkCleaner.cleanUrl("https://shop.example/p?gclid=x") shouldBe "https://shop.example/p"
    }

    test("the fragment stays") {
        LinkCleaner.cleanUrl("https://example.com/doc?utm_campaign=x#section-2") shouldBe "https://example.com/doc#section-2"
    }

    test("share tags only on the sites that add them") {
        LinkCleaner.cleanUrl("https://youtu.be/dQw4w9WgXcQ?si=abc123&t=42") shouldBe "https://youtu.be/dQw4w9WgXcQ?t=42"
        LinkCleaner.cleanUrl("https://x.com/user/status/1?s=20&t=Zz") shouldBe "https://x.com/user/status/1"
        // elsewhere s and si are the page's own
        LinkCleaner.cleanUrl("https://example.com/search?s=dogs&si=1") shouldBe "https://example.com/search?s=dogs&si=1"
    }

    // shapes of real share links (made-up IDs and values; real ones identify the sharer)
    test("Wikipedia, Amazon and Instagram share links") {
        LinkCleaner.cleanUrl("https://en.wikipedia.org/wiki/Dog?wprov=abcd1") shouldBe "https://en.wikipedia.org/wiki/Dog"
        LinkCleaner.cleanUrl(
            "https://www.amazon.de/Some-Product-Name/dp/B000000000/ref=mp_s_a_1_1_sspa?crid=XXXX&dib=YYYY&dib_tag=se&keywords=dog&qid=1&sprefix=dog&sr=8-1-spons&aref=ZZZZ&sp_csd=QQQQ&psc=1"
        ) shouldBe "https://www.amazon.de/Some-Product-Name/dp/B000000000"
        LinkCleaner.cleanUrl("https://www.instagram.com/reel/AAAAAAAAAAA/?cplk=XXXXXXXX") shouldBe "https://www.instagram.com/reel/AAAAAAAAAAA/"
        // short links: the code is the share; nothing to strip offline
        LinkCleaner.clean("https://vm.tiktok.com/AAAAAAAAA/") shouldBe "https://vm.tiktok.com/AAAAAAAAA/"
        LinkCleaner.clean("Take a look! https://pin.it/AAAAAAAAA") shouldBe "Take a look! https://pin.it/AAAAAAAAA"
    }

    test("links inside text; other text and links without a query are untouched") {
        LinkCleaner.clean("Look: https://www.youtube.com/watch?v=abc&si=zz and https://example.com/plain, ok?") shouldBe
            "Look: https://www.youtube.com/watch?v=abc and https://example.com/plain, ok?"
        LinkCleaner.clean("no links here?utm_source=x") shouldBe "no links here?utm_source=x"
    }
})
