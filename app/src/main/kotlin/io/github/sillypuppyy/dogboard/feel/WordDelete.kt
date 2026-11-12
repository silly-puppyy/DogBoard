package io.github.sillypuppyy.dogboard.feel

/**
 * Decides how much text one "delete word" step removes.
 */
object WordDelete {
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '_'

    /**
     * Returns how many UTF-16 chars at the end of [textBefore] (the text before the cursor) one
     * word-delete step removes: any trailing whitespace plus the word before it, or, when the
     * text ends in punctuation, that run of punctuation.
     */
    fun charsToDelete(textBefore: CharSequence): Int {
        var i = textBefore.length
        while (i > 0 && textBefore[i - 1].isWhitespace()) i--
        if (i == 0) return textBefore.length
        if (isWordChar(textBefore[i - 1])) {
            while (i > 0 && isWordChar(textBefore[i - 1])) i--
        } else {
            while (i > 0 && !isWordChar(textBefore[i - 1]) && !textBefore[i - 1].isWhitespace()) i--
        }
        return textBefore.length - i
    }
}
