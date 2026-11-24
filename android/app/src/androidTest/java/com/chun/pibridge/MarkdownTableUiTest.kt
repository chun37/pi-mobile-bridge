package com.chun.pibridge

import android.graphics.Bitmap
import android.text.Spanned
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic assistant messages only: no SSH, model prompts, user history, or user photos. */
@RunWith(AndroidJUnit4::class)
class MarkdownTableUiTest {
    @get:Rule val ui = createComposeRule()
    private var source by mutableStateOf("")
    private var width by mutableStateOf(360.dp)
    private var color by mutableStateOf(Color(0xffe4eaf2))
    private lateinit var host: View
    private fun show(text: String, assistant: Boolean = true) {
        source = text
        ui.setContent {
            host = LocalView.current
            MaterialTheme { Box(Modifier.padding(top = 48.dp).width(width).testTag("markdownFixture")) {
                if (assistant) {
                    val entry = JSONObject().put("message", JSONObject().put("role", "assistant").put("content", source))
                    MessageCard(entry, listOf(entry))
                } else Markdown(source, color)
            } }
        }
        ui.waitForIdle()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun root() = descendants(host).filterIsInstance<MarkdownMessageView>().single()
    private fun tables() = descendants(root()).filterIsInstance<MarkdownTableView>()
    private fun cell(table: MarkdownTableView, row: Int, column: Int) = table.findViewWithTag<TextView>("markdownCell:$row:$column")!!

    @Test fun actualAssistantTableHasHeadersJapaneseCellsInlineFormattingAndAlignment() {
        show("前の説明。\n\n| 項目 | 状態 | 数量 |\n| :--- | :---: | ---: |\n| **日本語** | `稼働中` | 12 |\n| 長い説明もセル内で折り返して表示します。 | 完了 | 3 |\n\n後の説明。")
        ui.runOnIdle {
            assertEquals(1,tables().size)
            val table = tables().single()
            assertEquals("項目",cell(table,0,0).text.toString())
            assertEquals("日本語",cell(table,1,0).text.toString())
            assertEquals("稼働中",cell(table,1,1).text.toString().trim())
            assertEquals(Gravity.CENTER_HORIZONTAL,cell(table,1,1).gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            assertEquals(Gravity.RIGHT,cell(table,1,2).gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            val bold = cell(table,1,0).text as Spanned
            assertTrue(bold.getSpans(0,bold.length,android.text.style.MetricAffectingSpan::class.java).isNotEmpty())
            assertTrue(cell(table,2,0).layout.lineCount > 1)
            assertTrue(cell(table,1,0).isTextSelectable)
            assertTrue((root().getChildAt(0) as TextView).text.contains("前の説明"))
            assertTrue((root().getChildAt(2) as TextView).text.contains("後の説明"))
        }
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"markdown-table-proof.png")
        ui.onNodeWithTag("markdownFixture").captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun wideTableScrollsWithTouchWithoutWideningTheResponse() {
        width=240.dp
        show("| A | B | C | D | E | F |\n|---|---|---|---|---|---|\n| 1 | 2 | 3 | 4 | 5 | 6 |")
        ui.waitUntil(5000) { var ready=false;ui.runOnIdle {
            val table=tables().single();ready=table.width > 0 && table.getChildAt(0).width > table.width
        };ready }
        ui.runOnIdle { assertEquals(root().width,tables().single().width) }
        val touches=mutableListOf<String>()
        ui.runOnIdle { descendants(tables().single()).filterIsInstance<TextView>().forEach { text ->
            text.setOnTouchListener { _, event -> touches.add("${event.action}:${event.x}:${event.y}"); false }
        } }
        val bounds=ui.onNodeWithTag("markdownFixture").fetchSemanticsNode().boundsInRoot
        val position=IntArray(2)
        var tableWidth=0;var rowHeight=0
        ui.runOnIdle {
            val table=tables().single();table.getLocationOnScreen(position)
            tableWidth=table.width;rowHeight=table.height/4
        }
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).swipe(
            position[0]+tableWidth*8/10,position[1]+rowHeight,
            position[0]+tableWidth*2/10,position[1]+rowHeight,20)
        ui.runOnIdle {
            val table=tables().single()
            val visible=android.graphics.Rect();table.getGlobalVisibleRect(visible)
            assertTrue("fixture=$bounds table=$visible touches=$touches",table.scrollX > 0)
        }
    }
    @Test fun streamingCompletionAndRowChangesKeepTheSameTableAndScrollPosition() {
        width=240.dp
        show("| A | B | C |\n")
        ui.runOnIdle { assertTrue(tables().isEmpty()); source += "|---|---|---|\n| 1 | 2 | 3 |" }
        ui.waitForIdle()
        lateinit var original: MarkdownTableView
        ui.runOnIdle { original=tables().single(); original.scrollTo(30,0); source += "\n| 4 | 5 | 6 |" }
        ui.waitForIdle()
        ui.runOnIdle {
            assertSame(original,tables().single())
            assertEquals("6",cell(original,2,2).text.toString())
            assertEquals(30,original.scrollX)
        }
    }
    @Test fun missingCellsAreClearedAndEscapedPipesRemainInTheirOwnCell() {
        show("| A | B |\n|---|---|\n| left\\|right | `x()` |")
        ui.runOnIdle {
            assertEquals("left|right",cell(tables().single(),1,0).text.toString())
            assertEquals("x()",cell(tables().single(),1,1).text.toString().trim())
            source="| A | B |\n|---|---|\n| only |"
        }
        ui.waitForIdle()
        ui.runOnIdle { assertEquals("",cell(tables().single(),1,1).text.toString()) }
    }
    @Test fun fencedCodeAndOrdinaryMarkdownAreNotMistakenForTables() {
        show("# 見出し\n\n- 箇条書き\n\n```text\n| A | B |\n|---|---|\n| 1 | 2 |\n```\n\n**強調**と[リンク](https://example.invalid)")
        ui.runOnIdle {
            assertTrue(tables().isEmpty())
            val text=root().getChildAt(0) as TextView
            assertTrue(text.text.contains("| A | B |")); assertTrue(text.text.contains("箇条書き"))
            assertTrue(text.isTextSelectable)
        }
    }
    @Test fun multipleTablesKeepSurroundingParagraphsAndIndependentCells() {
        show("前\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n中央\n\n| C | D |\n|---|---|\n| 3 | 4 |\n\n後")
        ui.runOnIdle {
            assertEquals(5,root().childCount);assertEquals(2,tables().size)
            assertEquals("2",cell(tables()[0],1,1).text.toString())
            assertEquals("4",cell(tables()[1],1,1).text.toString())
        }
    }
    @Test fun resizingAndColorUpdatesKeepCellsReadableWithoutRecreatingTheTable() {
        width=240.dp
        show("| A | B |\n|---|---|\n| 日本語 | 2 |",assistant=false)
        lateinit var original: MarkdownTableView
        var oldWidth=0
        ui.runOnIdle { original=tables().single();oldWidth=cell(original,1,0).width;width=360.dp;color=Color.Red }
        ui.waitForIdle()
        ui.runOnIdle {
            assertSame(original,tables().single())
            assertTrue(cell(original,1,0).width > oldWidth)
            assertEquals(android.graphics.Color.RED,cell(original,1,0).currentTextColor)
        }
    }
    @Test fun emptyMalformedAndUnfinishedInputCanBeReplacedByAValidTable() {
        show("")
        for (partial in listOf("|","| A | B |","| A | B |\n|---|","ordinary text")) {
            ui.runOnIdle { source=partial };ui.waitForIdle()
        }
        ui.runOnIdle { source="| A | B |\n|---|---|\n| 1 | 2 |" };ui.waitForIdle()
        ui.runOnIdle { assertEquals("2",cell(tables().single(),1,1).text.toString()) }
    }
}
