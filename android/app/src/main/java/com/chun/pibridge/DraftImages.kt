package com.chun.pibridge

import java.util.UUID
import org.json.JSONObject

/** UI identity is separate from image bytes: attaching the same photo twice is valid. */
data class DraftImage(val image: JSONObject, val id: String = UUID.randomUUID().toString())

fun removeDraftImage(images: List<DraftImage>, id: String): List<DraftImage> = images.filterNot { it.id == id }

/** Thumbnails never replace or recompress the payload sent to Pi. */
fun draftImagePayload(images: List<DraftImage>): List<JSONObject> = images.map { it.image }
