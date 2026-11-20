package com.chun.pibridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatImagesTest {
    private fun message(vararg types: String) = JSONObject().put("content", JSONArray().apply {
        types.forEach { put(JSONObject().put("type", it).put("data", "fixture")) }
    })
    @Test fun extractsImagesWithoutDroppingTextFromOriginal() {
        val source = message("text", "image", "image")
        assertEquals(2, imageParts(source).size)
        assertEquals(3, source.getJSONArray("content").length())
    }
    @Test fun finalResultWinsOverLiveCopyWithoutDuplicatingImages() {
        val live = JSONObject().put("result", message("image"))
        assertEquals(1, toolImageParts(message("image"), live).size)
    }
    @Test fun partialAndEndEventsCanBothProvideImages() {
        assertEquals(1, toolImageParts(null, JSONObject().put("partialResult", message("image"))).size)
        assertEquals(1, toolImageParts(null, JSONObject().put("result", message("image"))).size)
    }
    @Test fun finalTextOnlyResultDoesNotRevivePartialImages() {
        assertTrue(toolImageParts(message("text"), JSONObject().put("partialResult", message("image"))).isEmpty())
    }
    @Test fun doesNotInterpretPathsOrUrlsAsImageRequests() {
        assertTrue(imageParts(JSONObject().put("content", "/private/file.png")).isEmpty())
        assertTrue(imageParts(message("text", "toolCall")).isEmpty())
        assertTrue(imageParts(null).isEmpty())
    }
}
