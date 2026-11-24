package com.chun.pibridge

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real taps in synthetic MessageCards; intercept external intents so no browser/network/user tabs are touched. */
@RunWith(AndroidJUnit4::class)
class MarkdownLinkUiTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var host: View
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun interceptExternalApps() {
        Intents.init()
        for (action in listOf(Intent.ACTION_VIEW,Intent.ACTION_SENDTO,Intent.ACTION_DIAL))
            Intents.intending(hasAction(action)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK,null))
    }
    @After fun releaseIntents() { Intents.release() }
    private fun show(source: String) {
        ui.setContent {
            host=LocalView.current
            MaterialTheme { Box(Modifier.padding(top=48.dp).width(360.dp)) {
                val entry=JSONObject().put("message",JSONObject().put("role","assistant").put("content",source))
                MessageCard(entry,listOf(entry))
            } }
        }
        ui.waitForIdle()
    }
    private fun descendants(view: View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun text(needle:String)=descendants(host).filterIsInstance<TextView>().first { it.text.contains(needle) }
    private fun point(needle:String):Pair<Int,Int> {
        device.waitForIdle(1000)
        var point=0 to 0
        ui.waitUntil(15000) { var ready=false;ui.runOnIdle {
            val view=text(needle)
            ready=view.layout!=null && view.width>0 && view.isShown && view.rootView.hasWindowFocus()
        };ready && device.currentPackageName==InstrumentationRegistry.getInstrumentation().targetContext.packageName }
        ui.runOnIdle {
            val view=text(needle);val offset=view.text.indexOf(needle)+needle.length/2
            val layout=view.layout;val line=layout.getLineForOffset(offset)
            val position=IntArray(2);view.getLocationOnScreen(position)
            point=(position[0]+view.totalPaddingLeft+layout.getPrimaryHorizontal(offset).toInt()-view.scrollX) to
                (position[1]+view.totalPaddingTop+(layout.getLineTop(line)+layout.getLineBottom(line))/2-view.scrollY)
        }
        return point
    }
    private fun external()=Intents.getIntents().filter { it.action in listOf(Intent.ACTION_VIEW,Intent.ACTION_SENDTO,Intent.ACTION_DIAL) }
    private fun click(needle:String, expected:Boolean=true) {
        val p=point(needle);assertTrue(device.click(p.first,p.second))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();ui.waitForIdle()
        if(expected) assertEquals("A short link tap must dispatch exactly one external intent",1,external().size)
    }
    @Test fun markdownLinkTapDispatchesTheExactBrowserIntent() {
        show("前文 [説明を開く](https://example.com/one?x=1#part) 後文")
        click("説明を開く")
        assertEquals("https://example.com/one?x=1#part",external().single().dataString)
        assertEquals(Intent.ACTION_VIEW,external().single().action)
    }
    @Test fun bareHttpsUrlIsLinkifiedAndOpensOnTap() {
        show("URL: https://example.com/plain")
        click("https://example.com/plain")
        assertEquals("https://example.com/plain",external().single().dataString)
    }
    @Test fun tableCellLinkOpensWithoutLosingSelectableText() {
        show("| 項目 | リンク |\n|---|---|\n| 資料 | [表の資料](https://example.com/table) |")
        click("表の資料")
        assertEquals("https://example.com/table",external().single().dataString)
        ui.runOnIdle { assertTrue(text("表の資料").isTextSelectable) }
    }
    @Test fun explicitDestinationWinsOverAnAutolinkInsideItsLabel() {
        show("[https://example.com/visible](https://example.com/destination)")
        ui.runOnIdle {
            val buffer=text("https://example.com/visible").text as Spanned
            assertEquals(1,buffer.getSpans(0,buffer.length,ClickableSpan::class.java).size)
        }
        click("https://example.com/visible")
        assertEquals("https://example.com/destination",external().single().dataString)
    }
    @Test fun anExistingTextSelectionDoesNotConsumeTheNextShortLinkTap() {
        show("[選択済みリンク](https://example.com/selected)")
        ui.runOnIdle {
            val buffer=text("選択済みリンク").text as android.text.Spannable
            android.text.Selection.setSelection(buffer,0,buffer.length)
        }
        click("選択済みリンク")
        assertEquals("https://example.com/selected",external().single().dataString)
    }
    @Test fun codeAndFencedUrlsAreNotTurnedIntoLinks() {
        show("`https://example.com/inline`\n\n```text\nhttps://example.com/fenced\n```")
        ui.runOnIdle {
            val view=text("example.com/inline");val buffer=view.text as Spanned
            assertEquals(0,buffer.getSpans(0,buffer.length,ClickableSpan::class.java).size)
        }
        click("example.com/inline",expected=false)
        assertTrue(external().isEmpty())
    }
    @Test fun tappingWhitespaceAfterALinkDoesNotOpenIt() {
        show("[短いリンク](https://example.com/blank)")
        val p=point("短いリンク")
        var right=0
        ui.runOnIdle { val view=text("短いリンク");val pos=IntArray(2);view.getLocationOnScreen(pos);right=pos[0]+view.width-4 }
        device.click(right,p.second);ui.waitForIdle()
        assertTrue(external().isEmpty())
    }
    @Test fun longPressStillSelectsTextAndDoesNotOpenTheLink() {
        show("[長押しで文字を選択するリンク](https://example.com/select)")
        val p=point("文字を選択")
        device.swipe(p.first,p.second,p.first,p.second,200);ui.waitForIdle()
        assertTrue(external().isEmpty())
        ui.runOnIdle { assertTrue(text("長押し").hasSelection()) }
    }
    @Test fun draggingAcrossATableLinkScrollsRatherThanLaunchingTheBrowser() {
        show("| A | B | C | D | E |\n|---|---|---|---|---|\n| [リンクをドラッグする](https://example.com/drag) | 2 | 3 | 4 | 5 |")
        val p=point("リンクを")
        device.swipe(p.first,p.second,8,p.second,30);ui.waitForIdle()
        assertTrue(external().isEmpty())
        ui.runOnIdle { assertTrue(descendants(host).filterIsInstance<MarkdownTableView>().single().scrollX>0) }
    }
    @Test fun unsafeSchemesAndInvalidUrlsNeverProduceLaunchIntents() {
        for(url in listOf("javascript:alert(1)","file:///etc/passwd","data:text/html,x","intent://anything", "content://secret","https://", "#section", "relative/file", "https://example.com/\nsecret"))
            assertNull(url,externalLinkIntent(url))
        assertEquals(Intent.ACTION_VIEW,externalLinkIntent("HTTPS://example.com")!!.action)
        assertEquals(Intent.ACTION_SENDTO,externalLinkIntent("mailto:fixture@example.com")!!.action)
        assertEquals(Intent.ACTION_DIAL,externalLinkIntent("tel:0000000000")!!.action)
    }
    @Test fun missingHandlersAndSecurityRejectionsAreHandledWithoutCrashing() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ui.runOnIdle {
            for(failure in listOf(ActivityNotFoundException(),SecurityException())) {
                val rejecting=object:ContextWrapper(context) { override fun startActivity(intent:Intent) { throw failure } }
                openMarkdownLink(rejecting,"https://example.com/not-opened")
            }
        }
        assertTrue(external().isEmpty())
    }
}
