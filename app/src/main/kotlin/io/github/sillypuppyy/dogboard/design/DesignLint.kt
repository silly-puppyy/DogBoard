package io.github.sillypuppyy.dogboard.design

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs

/**
 * Layout lint for the ported design: catches text that doesn't fit, elements that leave their
 * container or the keyboard, overlapping caps and caps that are not where the spec puts them.
 * Off unless a [DesignLint] is provided through [LocalDesignLint] (the debug gallery does);
 * then every [lint] box and every [DText] reports its laid-out bounds, and [check] lists problems.
 */
class DesignLint {
    enum class Kind {
        BOX, TEXT, CAP, LABEL, FLOATING,
        /** Inside a container that scrolls or hides its overflow (CSS overflow: hidden): may leave the keyboard. */
        SCROLLED,
    }

    data class Node(
        val id: String,
        val parent: String?,
        val kind: Kind,
        val bounds: Rect, // dp, in root coordinates
        val text: String = "",
        val textWidth: Float = 0f, // dp, the text's own width on one line
        val boxWidth: Float = 0f, // dp, the width it was given
        val lines: Int = 1,
        val maxLines: Int = 1,
        val truncated: Boolean = false, // shortened with an ellipsis

        val expected: Rect? = null, // where the spec puts it (CAP)
    )

    private val nodes = LinkedHashMap<String, Node>()

    fun record(node: Node) = synchronized(nodes) { nodes[node.id] = node }

    fun clear() = synchronized(nodes) { nodes.clear() }

    /**
     * Problems, one line each. [rootId] is the keyboard root; [keyboardWidth] in dp.
     * TOL is half a dp: anything smaller is rounding.
     */
    fun check(rootId: String, keyboardWidth: Float): List<String> = synchronized(nodes) {
        val out = ArrayList<String>()
        val root = nodes[rootId]?.bounds ?: return listOf("root \"$rootId\" not laid out")
        fun Rect.str() = "[%.1f,%.1f %.1fx%.1f]".format(left - root.left, top - root.top, width, height)
        fun inScroller(n: Node): Boolean {
            var p: Node? = n
            while (p != null) { if (p.kind == Kind.SCROLLED) return true; p = p.parent?.let { nodes[it] } }
            return false
        }
        for (n in nodes.values) {
            val scrolled = inScroller(n)
            // 1. text wider than its box, or wrapped when it should be one line
            if (n.kind == Kind.TEXT || n.kind == Kind.LABEL) {
                if (n.textWidth > n.boxWidth + TOL) out += "OVERFLOW ${n.id} \"${n.text}\": text %.1f dp in a %.1f dp box".format(n.textWidth, n.boxWidth)
                if (n.lines > n.maxLines) out += "WRAP ${n.id} \"${n.text}\": ${n.lines} lines, max ${n.maxLines}"
                if (n.truncated) out += "TRUNCATED ${n.id} \"${n.text}\": shortened with an ellipsis in a %.1f dp box".format(n.boxWidth)
            }
            // 2. leaving its parent (floating elements may)
            val parent = n.parent?.let { nodes[it] }
            if (parent != null && n.kind != Kind.FLOATING && n.kind != Kind.SCROLLED && !parent.bounds.containsRect(n.bounds)) {
                out += "ESCAPE ${n.id} ${n.bounds.str()} outside ${parent.id} ${parent.bounds.str()}"
            }
            // 3. leaving the keyboard sideways (popups included: they must stay on screen)
            if (!scrolled && (n.bounds.left < root.left - TOL || n.bounds.right > root.left + keyboardWidth + TOL)) {
                out += "OFFSCREEN ${n.id} ${n.bounds.str()} beyond the keyboard's 0-%.1f dp".format(keyboardWidth)
            }
            // 4. caps where the spec does not put them (spacing)
            if (n.kind == Kind.CAP && n.expected != null) {
                val e = n.expected.translate(root.left, root.top)
                if (abs(e.left - n.bounds.left) > TOL || abs(e.top - n.bounds.top) > TOL || abs(e.width - n.bounds.width) > TOL || abs(e.height - n.bounds.height) > TOL) {
                    out += "SPACING ${n.id} at ${n.bounds.str()}, spec ${e.str()}"
                }
            }
            // 5. labels not centred in their cap
            if (n.kind == Kind.LABEL && parent != null) {
                val dx = n.bounds.center.x - parent.bounds.center.x
                if (abs(dx) > 1f) out += "OFFCENTRE ${n.id} \"${n.text}\" %.1f dp from the cap centre".format(dx)
            }
        }
        // 6. overlapping caps
        val caps = nodes.values.filter { it.kind == Kind.CAP }
        for (i in caps.indices) for (j in i + 1 until caps.size) {
            val a = caps[i].bounds; val b = caps[j].bounds
            val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (w > TOL && h > TOL) out += "OVERLAP ${caps[i].id} and ${caps[j].id} by %.1f x %.1f dp".format(w, h)
        }
        out
    }

    private fun Rect.containsRect(o: Rect) =
        o.left >= left - TOL && o.top >= top - TOL && o.right <= right + TOL && o.bottom <= bottom + TOL

    companion object {
        const val TOL = 0.5f
    }
}

val LocalDesignLint = staticCompositionLocalOf<DesignLint?> { null }

/** The lint id of the enclosing [lint] box, so children know their parent. */
val LocalLintParent = compositionLocalOf<String?> { null }

/** Records this element's bounds under [id]; [expected] (dp, root-relative) for caps. */
@Composable
fun Modifier.lint(id: String, kind: DesignLint.Kind = DesignLint.Kind.BOX, expected: Rect? = null): Modifier {
    val lint = LocalDesignLint.current ?: return this
    val parent = LocalLintParent.current
    val density = LocalDensity.current.density
    return this.onGloballyPositioned { c ->
        lint.record(DesignLint.Node(id, parent, kind, c.unclippedDp(density), expected = expected))
    }
}

/** Runs [content] with [id] as the lint parent. */
@Composable
fun LintParent(id: String, content: @Composable () -> Unit) {
    if (LocalDesignLint.current == null) content() else CompositionLocalProvider(LocalLintParent provides id, content = content)
}

/**
 * Design text (BasicText) that reports whether it fits. [kind] LABEL also checks centring.
 * Overflow is visible as in CSS; the lint is what flags it.
 */
@Composable
fun DText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    id: String? = null,
    kind: DesignLint.Kind = DesignLint.Kind.TEXT,
    maxLines: Int = 1,
    softWrap: Boolean = maxLines > 1,
    overflow: TextOverflow = TextOverflow.Visible,
    squash: Boolean = false,
) {
    if (squash) {
        SquashedText(text, modifier, style, id, kind)
        return
    }
    val lint = LocalDesignLint.current
    if (lint == null) {
        BasicText(text, modifier, style = style, maxLines = maxLines, softWrap = softWrap, overflow = overflow)
        return
    }
    val parent = LocalLintParent.current
    val density = LocalDensity.current.density
    val key = id ?: "${parent ?: "root"}/text:$text"
    var layout: TextLayoutResult? = null
    BasicText(
        text,
        modifier.onGloballyPositioned { c ->
            val l = layout ?: return@onGloballyPositioned
            lint.record(
                DesignLint.Node(
                    key, parent, kind, c.unclippedDp(density), text = text,
                    // the widest line as laid out, against the width the text was allowed
                    textWidth = (0 until l.lineCount).maxOf { l.getLineRight(it) - l.getLineLeft(it) } / density,
                    boxWidth = l.layoutInput.constraints.maxWidth.let { if (it == Int.MAX_VALUE) Float.MAX_VALUE else it / density },
                    lines = l.lineCount, maxLines = maxLines,
                    truncated = (0 until l.lineCount).any { l.isLineEllipsized(it) },
                ),
            )
        },
        style = style, maxLines = maxLines, softWrap = softWrap, overflow = overflow,
        onTextLayout = { layout = it },
    )
}

/** Bounds in root dp without clipping by scrolling ancestors (boundsInRoot clips to the viewport). */
private fun LayoutCoordinates.unclippedDp(density: Float): Rect {
    val p = positionInRoot()
    return Rect(p.x / density, p.y / density, (p.x + size.width) / density, (p.y + size.height) / density)
}

/**
 * DogBoard rule for suggestion slots (user, 2026-11-13, replaces the design's end ellipsis,
 * Kb:87), as Gboard does it: a word that doesn't fit is first narrowed (horizontal scale down to
 * [SQUASH_MIN_SCALE]), and only then shortened in the middle ("On… way!"), so the start and the
 * end of the word stay readable.
 */
@Composable
private fun SquashedText(text: String, modifier: Modifier, style: TextStyle, id: String?, kind: DesignLint.Kind) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val natural = remember(text, style) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width }
        val available = constraints.maxWidth
        val scale = if (natural <= available || available <= 0) 1f else (available.toFloat() / natural).coerceAtLeast(SQUASH_MIN_SCALE)
        DText(
            text, Modifier, style.copy(textGeometricTransform = TextGeometricTransform(scaleX = scale)),
            id = id, kind = kind, maxLines = 1, softWrap = false, overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/** Narrowest horizontal scale for squashed text; below this, letters get hard to tell apart. */
const val SQUASH_MIN_SCALE = 0.7f
