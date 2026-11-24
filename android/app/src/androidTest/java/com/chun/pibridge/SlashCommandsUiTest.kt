package com.chun.pibridge

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlashCommandsUiTest {
    @get:Rule val ui = createComposeRule()
    private val catalog = JSONObject("""{"commands":[{"name":"model","source":"builtin","description":"モデル切替"},
        {"name":"compact","source":"builtin","description":"コンテキスト圧縮"},
        {"name":"skill:local","source":"skill","description":"ローカルスキル"}],
        "models":[{"value":"local/fixture","name":"Local fixture"}]}""")

    @Test fun slashOpensAboveInputAndSelectionOnlyEditsDraft() {
        var input by mutableStateOf(TextFieldValue(""))
        ui.setContent { MaterialTheme { Column(Modifier.padding(top = 48.dp)) {
            SlashSuggestions(input, catalog, false, "") { input = completeSlash(input, it) }
            OutlinedTextField(input, { input = it }, Modifier.testTag("input"))
        } } }
        ui.onNodeWithTag("slashSuggestions").assertDoesNotExist()
        ui.onNodeWithTag("input").performTextInput("/")
        ui.onNodeWithTag("slash:model").assertIsDisplayed()
        val candidates = ui.onNodeWithTag("slashSuggestions").fetchSemanticsNode().boundsInRoot
        val editor = ui.onNodeWithTag("input").fetchSemanticsNode().boundsInRoot
        assertTrue(candidates.bottom <= editor.top)
        ui.onNodeWithTag("slash:model").performClick()
        ui.runOnIdle { assertEquals("/model ", input.text) }
        ui.onNodeWithTag("slash:local/fixture").assertIsDisplayed().performClick()
        ui.runOnIdle { assertEquals("/model local/fixture ", input.text) }
    }
    @Test fun prefixFiltersAndOrdinaryTextHidesSuggestions() {
        var input by mutableStateOf(TextFieldValue(""))
        ui.setContent { MaterialTheme { Column(Modifier.padding(top = 48.dp)) {
            SlashSuggestions(input, catalog, false, "") { input = completeSlash(input, it) }
            OutlinedTextField(input, { input = it }, Modifier.testTag("input"))
        } } }
        ui.onNodeWithTag("input").performTextInput("/com")
        ui.onNodeWithTag("slash:compact").assertIsDisplayed()
        ui.onNodeWithTag("slash:model").assertDoesNotExist()
        ui.onNodeWithTag("input").performTextReplacement("質問 /model")
        ui.onNodeWithTag("slashSuggestions").assertDoesNotExist()
    }
    @Test fun longModelStillShowsPercentageAndUpdatesWithoutFakeZero() {
        var session by mutableStateOf(JSONObject().put("model", "long-provider-very-long-model-name".repeat(6))
            .put("contextUsage", JSONObject().put("percent", 23.456)))
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp).width(220.dp)) {
            ModelContextLabel(session)
        } } }
        ui.onNodeWithTag("contextUsage").assertIsDisplayed().assertTextEquals("23.5%")
        ui.runOnIdle { session = JSONObject(session.toString()).put("contextUsage", JSONObject().put("percent", JSONObject.NULL)) }
        ui.onNodeWithTag("contextUsage").assertTextEquals("--%")
        ui.runOnIdle { session = JSONObject(session.toString()).put("contextUsage", JSONObject().put("percent", 0)) }
        ui.onNodeWithTag("contextUsage").assertTextEquals("0.0%")
    }
    @Test fun modelThinkingLevelUpdatesAndDisappearsForLegacyMetadata() {
        var session by mutableStateOf(JSONObject().put("model", "test-model").put("thinkingLevel", "high"))
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) { ModelContextLabel(session) } } }
        ui.onNodeWithTag("modelThinkingLabel").assertIsDisplayed().assertTextEquals("test-model (high)")
        ui.runOnIdle { session = JSONObject(session.toString()).put("thinkingLevel", "off") }
        ui.onNodeWithTag("modelThinkingLabel").assertTextEquals("test-model (off)")
        ui.runOnIdle { session = JSONObject(session.toString()).put("model", "other-model").put("thinkingLevel", "low") }
        ui.onNodeWithTag("modelThinkingLabel").assertTextEquals("other-model (low)")
        ui.runOnIdle { session = JSONObject().put("model", "legacy-model") }
        ui.onNodeWithTag("modelThinkingLabel").assertTextEquals("legacy-model")
        ui.onNodeWithTag("contextUsage").assertTextEquals("--%")
    }
    @Test fun refreshButtonRecoversFromErrorAndDisplaysNewGlmWithoutProviderPrefix() {
        var current by mutableStateOf(catalog)
        var error by mutableStateOf("取得失敗")
        var refreshes = 0
        val input = TextFieldValue("/model glm", androidx.compose.ui.text.TextRange(10))
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            SlashSuggestions(input, current, false, error, onRefresh = {
                refreshes++; error = ""
                current = JSONObject("""{"commands":[{"name":"model","source":"builtin"}],"models":[{"value":"zai/glm-fixture","name":"GLM Fixture"}]}""")
            }) {}
        } } }
        ui.onNodeWithText("取得失敗").assertIsDisplayed()
        ui.onNodeWithTag("slashRefresh").performClick()
        ui.onNodeWithTag("slash:zai/glm-fixture").assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, refreshes) }
    }
    @Test fun refreshIsDisabledDuringLoadAndPartialWarningDoesNotHideCandidates() {
        var loading by mutableStateOf(true)
        val partial = JSONObject(catalog.toString()).put("modelRefreshWarning", "一部だけ取得できました")
        val input = TextFieldValue("/model ", androidx.compose.ui.text.TextRange(7))
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            SlashSuggestions(input, partial, loading, "", onRefresh = { fail("disabled") }) {}
        } } }
        ui.onNodeWithTag("slashRefresh").assertIsNotEnabled()
        ui.runOnIdle { loading = false }
        ui.onNodeWithText("一部だけ取得できました").assertIsDisplayed()
        ui.onNodeWithTag("slash:local/fixture").assertIsDisplayed()
    }
    @Test fun loadingAndOldExtensionHaveExplicitMessages() {
        var loading by mutableStateOf(true)
        var error by mutableStateOf("")
        ui.setContent { MaterialTheme { Box(Modifier.padding(top = 48.dp)) {
            SlashSuggestions(TextFieldValue("/", androidx.compose.ui.text.TextRange(1)), null, loading, error) {}
        } } }
        ui.onNodeWithText("コマンド候補を取得中…").assertIsDisplayed()
        ui.runOnIdle { loading = false; error = "PCのこのPiで /reload を1回実行してください" }
        ui.onNodeWithText("PCのこのPiで /reload を1回実行してください").assertIsDisplayed()
    }
}
