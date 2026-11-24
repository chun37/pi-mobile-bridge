package com.chun.pibridge

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannedString
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.Table
import io.noties.markwon.ext.tables.TablePlugin
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.Document
import kotlin.math.max

/** Keep ordinary Markdown selectable and use real, selectable cells for top-level tables.
 * A table scrolls independently rather than making the entire response wider than the chat.
 */
internal class MarkdownMessageView(context: Context) : LinearLayout(context) {
    private val markwon = Markwon.builder(context).usePlugin(TablePlugin.create(context)).build()
    // TablePlugin replaces cell text with drawing spans; cells need the normal inline renderer.
    private val cellMarkwon by lazy { Markwon.create(context) }
    private var source: String? = null
    private var foreground: Int? = null
    init { orientation = VERTICAL; tag = "markdownMessage" }

    fun bind(markdown: String, color: Int) {
        if (source == markdown && foreground == color) return
        source = markdown; foreground = color
        val document = markwon.parse(markdown)
        var group = Document()
        var index = 0
        fun paragraph() {
            if (group.firstChild == null) return
            val existing = getChildAt(index)
            val view = if (existing is TextView) existing else textView().also {
                if (existing != null) removeViewAt(index)
                addView(it, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            view.setTextColor(color)
            markwon.setParsedMarkdown(view, markwon.render(group))
            index++; group = Document()
        }
        var node = document.firstChild
        while (node != null) {
            val next = node.next
            if (node is TableBlock) {
                paragraph()
                val table = Table.parse(cellMarkwon, node)
                if (table != null) {
                    val existing = getChildAt(index)
                    val view = if (existing is MarkdownTableView) existing else MarkdownTableView(context).also {
                        if (existing != null) removeViewAt(index)
                        addView(it, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    }
                    view.bind(cellMarkwon, table, color)
                    index++
                }
            } else group.appendChild(node)
            node = next
        }
        paragraph()
        if (childCount > index) removeViews(index, childCount - index)
        for (i in 0 until childCount) (getChildAt(i).layoutParams as LayoutParams).also {
            it.bottomMargin = if (i == childCount - 1) 0 else dip(8)
            getChildAt(i).layoutParams = it
        }
    }

    private fun textView() = TextView(context).apply {
        tag = "markdownText"; textSize = 15f; setTextIsSelectable(true); setLineSpacing(4f, 1.08f)
    }
    private fun dip(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

internal class MarkdownTableView(context: Context) : HorizontalScrollView(context) {
    private val grid = TableLayout(context)
    private var columns = 0
    init {
        tag = "markdownTable"
        isFillViewport = true
        addView(grid, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    fun bind(markwon: Markwon, table: Table, color: Int) {
        val rows = table.rows()
        val count = rows.firstOrNull()?.columns()?.size ?: 0
        if (count == 0) return
        if (columns != count || grid.childCount != rows.size) {
            columns = count; grid.removeAllViews()
            for (row in rows) {
                val view = TableRow(context)
                repeat(columns) { view.addView(TextView(context).apply {
                    textSize = 15f; setTextIsSelectable(true); setLineSpacing(4f, 1.08f)
                    setPadding(dip(8), dip(8), dip(8), dip(8))
                }, TableRow.LayoutParams(cellWidth(), ViewGroup.LayoutParams.MATCH_PARENT)) }
                grid.addView(view, TableLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        rows.forEachIndexed { r, row ->
            val nativeRow = grid.getChildAt(r) as TableRow
            for (c in 0 until columns) {
                val column = row.columns().getOrNull(c)
                val cell = nativeRow.getChildAt(c) as TextView
                cell.tag = "markdownCell:$r:$c"
                cell.setTextColor(color)
                cell.setTypeface(null, if (row.header()) Typeface.BOLD else Typeface.NORMAL)
                cell.gravity = Gravity.TOP or when (column?.alignment()) {
                    Table.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
                    Table.Alignment.RIGHT -> Gravity.RIGHT
                    else -> Gravity.LEFT
                }
                cell.background = GradientDrawable().apply {
                    setColor(if (row.header()) (color and 0xffffff) or (24 shl 24) else Color.TRANSPARENT)
                    setStroke(dip(1), (color and 0xffffff) or (80 shl 24))
                }
                markwon.setParsedMarkdown(cell, column?.content() ?: SpannedString(""))
            }
        }
    }
    private fun cellWidth(viewport: Int = width) = max(dip(112), if (columns > 0) viewport / columns else 0)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Resolve cell widths before measuring rows, not by requesting layout from onSizeChanged.
        val desired = cellWidth(MeasureSpec.getSize(widthMeasureSpec))
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as TableRow
            for (c in 0 until row.childCount) row.getChildAt(c).let { cell ->
                val params = cell.layoutParams as TableRow.LayoutParams
                if (params.width != desired) cell.layoutParams = params.apply { width = desired }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun dip(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
