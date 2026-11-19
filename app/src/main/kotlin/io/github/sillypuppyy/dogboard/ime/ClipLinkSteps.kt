package io.github.sillypuppyy.dogboard.ime

/**
 * What pasting a copied text does to its links, step by step: short links expanded ([ShortLinks], once the
 * link service has answered), then trackers removed ([LinkCleaner]) from the result. The clipboard rows show
 * a mark for each step that changes the text, and a clip can undo either (design/clipboard-rows.md): keeping
 * the short link keeps its trackers too, as the strip runs on the expanded link.
 */
data class ClipLinkSteps(
    /** Expanding changes this text (whether or not the clip keeps its short link). */
    val expands: Boolean,
    /** Removing trackers changes the text as it is pasted (after expanding, unless the short link is kept). */
    val strips: Boolean,
    val pasted: String,
) {
    companion object {
        fun of(text: String, expand: Boolean, strip: Boolean): ClipLinkSteps {
            val expanded = ShortLinks.withExpanded(text)
            val base = if (expand) expanded else text
            val cleaned = LinkCleaner.clean(base)
            return ClipLinkSteps(expanded != text, cleaned != base, if (strip) cleaned else base)
        }
    }
}
