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

    /** Letter-key targets and touch parameters of the keyboard the taps were made on. */
    var keys: Map<Char, KeyTarget> = emptyMap()
        private set
    var params: GaussianTouch.Params? = null
        private set

    val text: String get() = letters.toString()
    val isEmpty: Boolean get() = letters.isEmpty()

    /**
     * Adds a typed letter. [tap] is where its key was tapped, or null if the letter came from
     * elsewhere; then the key's centre stands in when the letter is on the keyboard.
     */
    fun addLetter(letter: Char, tap: Tap?, keys: Map<Char, KeyTarget>, params: GaussianTouch.Params?) {
        if (keys.isNotEmpty()) this.keys = keys
        if (params != null) this.params = params
        letters.append(letter)
        val position = tap ?: this.keys[letter.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }
        if (position == null) decodable = false else taps.add(position)
    }

    fun removeLast() {
        if (letters.isEmpty()) return
        letters.setLength(letters.length - 1)
        if (taps.size > letters.length) taps.removeAt(taps.size - 1)
        if (letters.isEmpty()) clear()
    }

    fun clear() {
        letters.setLength(0)
        taps.clear()
        decodable = true
    }

    /** Taps for the decoder, or null if this word cannot be decoded. */
    fun tapsOrNull(): List<Tap>? =
        if (decodable && taps.size == letters.length && letters.isNotEmpty() && params != null) taps.toList() else null

    fun snapshot(): CurrentWord = CurrentWord().also {
        it.letters.append(letters)
        it.taps.addAll(taps)
        it.decodable = decodable
        it.keys = keys
        it.params = params
    }
}
