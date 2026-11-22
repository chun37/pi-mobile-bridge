package com.chun.pibridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DraftImagesTest {
    private fun image(data: String) = JSONObject().put("mimeType", "image/jpeg").put("data", data)
    @Test fun removesOnlyTheRequestedMiddleImageAndKeepsOrder() {
        val images = listOf(DraftImage(image("a")), DraftImage(image("b")), DraftImage(image("c")))
        assertEquals(listOf(images[0], images[2]), removeDraftImage(images, images[1].id))
        assertEquals(3, images.size)
    }
    @Test fun identicalPhotosHaveIndependentRemoveButtons() {
        val part = image("same")
        val a = DraftImage(part); val b = DraftImage(part)
        assertNotEquals(a.id, b.id)
        assertEquals(listOf(b), removeDraftImage(listOf(a, b), a.id))
    }
    @Test fun payloadContainsOriginalObjectsWithoutUiIdsOrRecompression() {
        val images = listOf(DraftImage(image("original-a")), DraftImage(image("original-b")))
        val payload = draftImagePayload(images)
        assertSame(images[0].image, payload[0]); assertSame(images[1].image, payload[1])
        assertEquals("original-a", payload[0].getString("data")); assertFalse(payload[0].has("id"))
    }
    @Test fun lastRemovalProducesAnEmptyPayload() {
        val image = DraftImage(image("a"))
        assertTrue(draftImagePayload(removeDraftImage(listOf(image), image.id)).isEmpty())
    }
    @Test fun staleRemovalNeverRemovesAnotherPhoto() {
        val images = listOf(DraftImage(image("a")), DraftImage(image("b")))
        assertEquals(images, removeDraftImage(images, "removed-already"))
    }
    @Test fun removingAndAddingRetainsTheOrderOfRemainingImages() {
        val a = DraftImage(image("a")); val b = DraftImage(image("b")); val c = DraftImage(image("c"))
        assertEquals(listOf(b, c), removeDraftImage(listOf(a,b),a.id) + c)
    }
}
