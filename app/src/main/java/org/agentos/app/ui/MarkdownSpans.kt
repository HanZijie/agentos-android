package org.agentos.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.ArrowKeyMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.agentos.app.ui.Markdown.Kind

/**
 * [Markdown.Rendered] → Spannable with platform spans only (no dependency). Links are [LinkSpan]s that
 * call back instead of opening anything: the screen asks the user first. Use [LinkTapMovement] on a
 * selectable TextView so a tap on a link reaches the span.
 */
object MarkdownSpans {
    class Style(
        val codeBackground: Int,
        val quoteStripe: Int,
        val secondaryText: Int,
        val rule: Int,
        val link: Int,
        val indentPx: Int,
        val stripePx: Int,
        val gapPx: Int,
        val codePadPx: Int,
    )

    private val HEADING_SIZE = floatArrayOf(1.3f, 1.2f, 1.1f, 1.05f, 1f, 1f)

    /** [tail] (the streaming cursor) is appended after the rendered text, outside every span but paragraph ones. */
    fun build(
        r: Markdown.Rendered,
        tail: String,
        paint: TextPaint,
        style: Style,
        onLink: (url: String, label: String) -> Unit,
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder(r.text).append(tail)
        for (s in r.spans) {
            val start = s.start
            val end = s.end
            // paragraph spans must end after the line's '\n' or at the very end
            val paraEnd = if (end < r.text.length) (if (sb[end] == '\n') end + 1 else end) else sb.length
            fun char(what: Any) = sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            fun para(what: Any) = sb.setSpan(what, start, paraEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when (s.kind) {
                Kind.BOLD -> char(StyleSpan(Typeface.BOLD))
                Kind.ITALIC -> char(StyleSpan(Typeface.ITALIC))
                Kind.STRIKE -> char(StrikethroughSpan())
                Kind.CODE -> {
                    char(TypefaceSpan("monospace"))
                    char(RelativeSizeSpan(0.92f))
                    char(BackgroundColorSpan(style.codeBackground))
                }
                Kind.CODE_BLOCK -> {
                    char(TypefaceSpan("monospace"))
                    char(RelativeSizeSpan(0.9f))
                    para(LineBackgroundSpan.Standard(style.codeBackground))
                    para(LeadingMarginSpan.Standard(style.codePadPx))
                }
                Kind.HEADING -> {
                    char(StyleSpan(Typeface.BOLD))
                    char(RelativeSizeSpan(HEADING_SIZE[(s.level - 1).coerceIn(0, 5)]))
                }
                Kind.LIST -> {
                    val markerWidth = paint.measureText(s.marker ?: "• ").toInt()
                    val base = style.indentPx * s.level
                    para(if (s.hang) LeadingMarginSpan.Standard(base, base + markerWidth) else LeadingMarginSpan.Standard(base + markerWidth))
                }
                Kind.QUOTE -> {
                    para(QuoteSpan(style.quoteStripe, style.stripePx, style.gapPx))
                    char(ForegroundColorSpan(style.secondaryText))
                }
                Kind.RULE -> para(RuleSpan(style.rule, style.stripePx / 2f))
                Kind.LINK -> char(LinkSpan(s.url ?: continue, r.textOf(s), style.link, onLink))
            }
        }
        return sb
    }

    /** A tappable http(s) link; [onLink] decides what happens (the conversation screen asks first). */
    class LinkSpan(
        val url: String,
        private val label: String,
        private val color: Int,
        private val onLink: (String, String) -> Unit,
    ) : ClickableSpan() {
        override fun onClick(widget: View) = onLink(url, label)

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = true
        }
    }

    /** A thematic break: a thin line across the text width. */
    private class RuleSpan(private val color: Int, private val thickness: Float) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lineNumber: Int,
        ) {
            val old = paint.color
            val oldStyle = paint.style
            paint.color = color
            paint.style = Paint.Style.FILL
            val y = (top + bottom) / 2f
            canvas.drawRect(left.toFloat(), y - thickness / 2, right.toFloat(), y + thickness / 2, paint)
            paint.color = old
            paint.style = oldStyle
        }
    }

    /**
     * Movement for a selectable TextView (text can still be long-pressed and copied) whose links are
     * tapped: a plain tap on a [ClickableSpan] clicks it; everything else is the default selection behaviour.
     * (A selectable TextView uses ArrowKeyMovementMethod, which ignores ClickableSpans.)
     */
    object LinkTapMovement : ArrowKeyMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP && !widget.hasSelection()) {
                val link = linkAt(widget, buffer, event)
                if (link != null) {
                    Selection.removeSelection(buffer)
                    link.onClick(widget)
                    return true
                }
            }
            return super.onTouchEvent(widget, buffer, event)
        }

        private fun linkAt(widget: TextView, buffer: Spannable, event: MotionEvent): ClickableSpan? {
            val layout: Layout = widget.layout ?: return null
            val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
            val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
            val line = layout.getLineForVertical(y)
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
            val offset = layout.getOffsetForHorizontal(line, x.toFloat())
            return buffer.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull {
                offset >= buffer.getSpanStart(it) && offset < buffer.getSpanEnd(it)
            }
        }
    }
}
