package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.github.sillypuppyy.dogboard.touch.KeyTarget

/**
 * The word being typed: its letters and, for each letter, where the key was tapped. Taps are
 * what the decoder needs; a letter that did not come from a tap on a letter key (an accent from
 * a long press, a pasted letter) makes the word undecodable.
 */
class CurrentWord {
    private val letters = StringBuilder()
    private val taps = ArrayList<Tap>()
    private var decodable = true

    /** Per letter: how shift was set when it was typed ([CaseAdapt.SHIFTED], [CaseAdapt.AUTOSHIFTED]). */
    private val shiftFlags = ArrayList<Int>()

    /** The shift context when the word started (iOS shiftContextForShiftState). */
    var caseContext: CaseAdapt.Context = CaseAdapt.Context.NONE
        private set

    /** Letter-key targets and touch parameters of the keyboard the taps were made on. */
    var keys: Map<Char, KeyTarget> = emptyMap()
        private set
    var params: GaussianTouch.Params? = null
        private set

    val text: String get() = letters.toString()
    val flags: List<Int> get() = shiftFlags.toList()
    val isEmpty: Boolean get() = letters.isEmpty()

    /**
     * Adds a typed letter. [tap] is where its key was tapped, or null if the letter came from
     * elsewhere; then the key's centre stands in when the letter is on the keyboard.
     */
    fun addLetter(
        letter: Char,
        tap: Tap?,
        keys: Map<Char, KeyTarget>,
        params: GaussianTouch.Params?,
        shiftFlag: Int = if (letter.isUpperCase()) CaseAdapt.SHIFTED else 0,
        context: CaseAdapt.Context? = null,
    ) {
        // one set of targets and touch parameters per word (the first tap's), kept as the same
        // objects, so the decoder can continue its search from the previous keystroke
        if (keys.isNotEmpty() && keys != this.keys) this.keys = keys
        if (params != null && this.params == null) this.params = params
        if (letters.isEmpty()) caseContext = context ?: if (shiftFlag != 0) CaseAdapt.Context.SHIFT else CaseAdapt.Context.NONE
        letters.append(letter)
        shiftFlags.add(shiftFlag)
        val position = tap ?: this.keys[letter.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }
        if (position == null) decodable = false else taps.add(position)
    }

    fun removeLast() {
        if (letters.isEmpty()) return
        letters.setLength(letters.length - 1)
        if (shiftFlags.size > letters.length) shiftFlags.removeAt(shiftFlags.size - 1)
        if (taps.size > letters.length) taps.removeAt(taps.size - 1)
        if (letters.isEmpty()) clear()
    }

    fun clear() {
        letters.setLength(0)
        taps.clear()
        shiftFlags.clear()
        caseContext = CaseAdapt.Context.NONE
        decodable = true
        params = null // the next word takes its own first tap's
    }

    /** Taps for the decoder, or null if this word cannot be decoded. */
    fun tapsOrNull(): List<Tap>? =
        if (decodable && taps.size == letters.length && letters.isNotEmpty() && params != null) taps.toList() else null

    /**
     * Starts tracking the word the cursor is touching in [textBefore] (typing or deleting at the
     * end of an existing word), with key centres standing in for the unknown taps.
     */
    fun seedFrom(textBefore: CharSequence, keys: Map<Char, KeyTarget>, params: GaussianTouch.Params?) {
        clear()
        for (c in trailingWord(textBefore)) addLetter(c, null, keys, params)
    }

    fun snapshot(): CurrentWord = CurrentWord().also {
        it.letters.append(letters)
        it.taps.addAll(taps)
        it.shiftFlags.addAll(shiftFlags)
        it.caseContext = caseContext
        it.decodable = decodable
        it.keys = keys
        it.params = params
    }
}

/** True for characters that belong to a word: letters and apostrophes. */
fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '’'

/** The word chars at the end of [textBefore], at most [maxLength] of them ("" if it ends otherwise). */
fun trailingWord(textBefore: CharSequence, maxLength: Int = 48): String {
    var i = textBefore.length
    while (i > 0 && textBefore.length - i < maxLength && isWordChar(textBefore[i - 1])) i--
    if (i > 0 && isWordChar(textBefore[i - 1])) return "" // longer than maxLength: not a word we track
    return textBefore.substring(i)
}

/**
 * The context for the word that starts at the end of [textBefore]: the previous word in lowercase,
 * [Bigrams.START] at the start of the text or of a sentence, or null when the word follows
 * something else (digits, symbols).
 */
fun previousContext(textBefore: CharSequence): String? {
    var i = textBefore.length
    while (i > 0 && textBefore[i - 1].isWhitespace()) i--
    if (i == 0) return Bigrams.START
    val last = textBefore[i - 1]
    if (last == '.' || last == '!' || last == '?') return Bigrams.START
    val word = trailingWord(textBefore.subSequence(0, i))
    return word.takeIf { it.isNotEmpty() }?.lowercase()?.replace('’', '\'')
}
