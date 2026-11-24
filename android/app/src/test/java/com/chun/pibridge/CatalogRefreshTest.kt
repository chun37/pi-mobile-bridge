package com.chun.pibridge

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogRefreshTest {
    private fun target(client: Any = this, key: String = "pane", instance: String = "i1", generation: Long = 1) =
        CatalogTarget(client,"profile",generation,key,"session",instance)
    @Test fun modelPickerReopeningChangesScopeButPrefixTypingDoesNot() {
        assertNull(slashCatalogScope("hello")); assertEquals("commands",slashCatalogScope("/"))
        for (text in listOf("/model","/model ","/model glm","/model glm-5")) assertEquals("model",slashCatalogScope(text))
        assertNull(slashCatalogScope("")); assertEquals("commands",slashCatalogScope("/thinking high"))
    }
    @Test fun repeatedRequestsCoalesceIntoExactlyOneFreshFollowup() {
        val gate = CatalogRefreshGate(); val target = target(); val first = gate.begin(target)!!
        repeat(10) { assertNull(gate.begin(target)) }
        assertEquals(CatalogRefreshGate.Finish.FOLLOW_UP,gate.finish(first))
        assertEquals(CatalogRefreshGate.Finish.DONE,gate.finish(gate.begin(target)!!))
    }
    @Test fun aNewSessionStartsImmediatelyAndOldResponseCannotOwnTheSpinner() {
        val gate=CatalogRefreshGate();val old=gate.begin(target())!!;val next=gate.begin(target(key="other"))!!
        assertFalse(gate.owns(old));assertTrue(gate.owns(next))
        assertEquals(CatalogRefreshGate.Finish.STALE,gate.finish(old))
        assertTrue(gate.owns(next));assertEquals(CatalogRefreshGate.Finish.DONE,gate.finish(next))
    }
    @Test fun instanceConnectionAndGenerationChangesDiscardOldReplies() {
        for (next in listOf(target(instance="i2"),target(client=Any()),target(generation=2))) {
            val gate=CatalogRefreshGate();val old=gate.begin(target())!!;val current=gate.begin(next)!!
            assertEquals(CatalogRefreshGate.Finish.STALE,gate.finish(old));assertTrue(gate.owns(current))
        }
    }
    @Test fun aStaleQueuedRefreshDoesNotRepeatInAnotherSession() {
        val gate=CatalogRefreshGate();val old=gate.begin(target())!!;gate.begin(target())
        val next=gate.begin(target(key="other"))!!;gate.finish(old)
        assertEquals(CatalogRefreshGate.Finish.DONE,gate.finish(next))
    }
    @Test fun modelSearchMatchesGlmWithoutKnowingTheProviderPrefix() {
        val catalog=JSONObject("""{"commands":[{"name":"model","source":"builtin"}],"models":[{"value":"zai/glm-fixture","name":"GLM Fixture"},{"value":"other/unrelated","name":"Unrelated"}]}""")
        for (query in listOf("glm","GLM","zai/","Fixture")) {
            val text="/model $query"
            assertEquals(listOf("zai/glm-fixture"),slashOptions(TextFieldValue(text,TextRange(text.length)),catalog).map { it.value })
        }
    }
}
