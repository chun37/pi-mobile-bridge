package com.chun.pibridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import android.widget.Toast
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.CoreProps
import org.commonmark.node.Link
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.linkify.LinkifyPlugin
import java.util.Locale
import kotlin.math.abs

internal fun markdownBuilder(context: Context): Markwon.Builder = Markwon.builder(context)
    .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS, true))
    .usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            builder.linkResolver { view, link -> openMarkdownLink(view.context, link) }
        }
        override fun configureVisitor(builder: MarkwonVisitor.Builder) {
            builder.on(Link::class.java) { visitor, link ->
                val start = visitor.length()
                visitor.visitChildren(link)
                // Linkify also visits explicit link labels. Remove those inner URL spans so
                // one destination owns the range; keep bold/code and other inline spans.
                val label = SpannableStringBuilder(visitor.builder().removeFromEnd(start))
                label.getSpans(0, label.length, URLSpan::class.java).forEach { label.removeSpan(it) }
                visitor.builder().append(label)
                CoreProps.LINK_DESTINATION.set(visitor.renderProps(), link.destination)
                visitor.setSpansForNodeOptional(link, start)
            }
        }
    })

internal fun externalLinkIntent(link: String): Intent? {
    if (link.isBlank() || link.any { it.isISOControl() }) return null
    val uri = Uri.parse(link)
    val action = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "http", "https" -> if (uri.host.isNullOrBlank()) return null else Intent.ACTION_VIEW
        "mailto" -> if (uri.schemeSpecificPart.isNullOrBlank()) return null else Intent.ACTION_SENDTO
        "tel" -> if (uri.schemeSpecificPart.isNullOrBlank()) return null else Intent.ACTION_DIAL
        else -> return null
    }
    return Intent(action, uri.normalizeScheme())
}

internal fun openMarkdownLink(context: Context, link: String) {
    val intent = externalLinkIntent(link)
    if (intent == null) {
        Toast.makeText(context, "この形式のリンクは開けません", Toast.LENGTH_SHORT).show()
        return
    }
    var base = context
    while (base is ContextWrapper && base !is Activity && base.baseContext !== base) base = base.baseContext
    if (base !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) }
    catch (_: ActivityNotFoundException) { Toast.makeText(context, "リンクを開けるアプリがありません", Toast.LENGTH_SHORT).show() }
    catch (_: SecurityException) { Toast.makeText(context, "このリンクを開けませんでした", Toast.LENGTH_SHORT).show() }
}

/** Retain native text selection. Only a short, unmoved tap may open a link. */
internal class MarkdownLinkMovement : ArrowKeyMovementMethod() {
    private var target: Any? = null
    private var x = 0f
    private var y = 0f
    private var downTime = 0L
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(widget.context).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = event.x; y = event.y; downTime = event.eventTime
                target = targetAt(widget, buffer, event)
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - x) > slop || abs(event.y - y) > slop) target = null
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> target = null
            MotionEvent.ACTION_UP -> {
                val candidate = target
                target = null
                val handled = super.onTouchEvent(widget, buffer, event)
                if (candidate != null && candidate === targetAt(widget, buffer, event) &&
                    event.eventTime - downTime < ViewConfiguration.getLongPressTimeout() &&
                    abs(event.x - x) <= slop && abs(event.y - y) <= slop) {
                    if (candidate is ClickableSpan) candidate.onClick(widget)
                    else return TableAwareMovementMethod.handleTableRowTouchEvent(widget, buffer, event) || handled
                    return true
                }
                return handled
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }
    private fun targetAt(widget: TextView, buffer: Spannable, event: MotionEvent): Any? {
        val layout = widget.layout ?: return null
        val px = event.x - widget.totalPaddingLeft + widget.scrollX
        val py = event.y - widget.totalPaddingTop + widget.scrollY
        if (py < 0 || py >= layout.height) return null
        val line = layout.getLineForVertical(py.toInt())
        if (px < layout.getLineLeft(line) || px >= layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, px)
        // Match Android's first-span precedence: the explicit Markdown link wins over autolinks in its label.
        return buffer.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull {
            offset >= buffer.getSpanStart(it) && offset < buffer.getSpanEnd(it)
        } ?: buffer.getSpans(offset, offset, TableRowSpan::class.java).firstOrNull()
    }
}
