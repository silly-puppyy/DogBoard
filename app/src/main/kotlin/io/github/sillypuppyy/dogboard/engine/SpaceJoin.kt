package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.github.sillypuppyy.dogboard.touch.KeyTarget
import kotlin.math.ln

/**
 * iOS retrocorrection across a plain space (decompile 2026-11-16): the space tap between two words
 * may have been meant as a letter ("goi g" -> "going", "to ight" -> "tonight") or have been an extra
 * tap ("hom e" -> "home"). The previous word's taps, the space tap and the current word's taps are
 * decoded as one word: the decoder reads the space tap as a nearby letter at its touch cost, or as
 * an extra tap (the iOS cost: -3.2 + time term - 1.105). The one word replaces the two only when it
 * beats them by the retrocorrection ratio (iOS 1.65x).
 */
object SpaceJoin {
    val RATIO = ln(1.65)

    /** The one-word readings of [prevTaps] + [spaceTap] + [curTaps], best first (the first is the join tried). */
    fun readings(
        decoder: Decoder, prevTaps: List<Tap>, spaceTap: Tap, curTaps: List<Tap>,
        keys: Map<Char, KeyTarget>, params: GaussianTouch.Params, previous: Int, previous2: Int = -1,
    ): List<Candidate> = decoder.decode(
        prevTaps + spaceTap + curTaps, keys, params, 4, includeCompletions = false, previous = previous, previous2 = previous2,
    ).filter { it.words == 1 && !it.isTypedString }

    /**
     * A join's word must be among the [minRank] most frequent words: a stray space is rejoined into a word people
     * use, not a rare one the web counts hold (user 2026-11-19: "going hom e" joined to "bombe", 832 uses, and
     * "Gombe"). Int.MAX_VALUE: no floor.
     */
    @Volatile
    var minRank: Int = Int.MAX_VALUE

    fun commonEnough(lexicon: Lexicon, word: String): Boolean {
        if (minRank >= lexicon.size) return true
        val node = lexicon.find(word)
        return node != Lexicon.NONE && lexicon.isWord(node) && lexicon.logProb(node) >= lexicon.logProb(lexicon.nodeOfWord(minRank))
    }

    /** [join] replaces the two words, whose readings scored [twoWords] together. */
    fun wins(join: Candidate, twoWords: Double): Boolean = join.score - twoWords >= RATIO
}
