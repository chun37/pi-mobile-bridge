package com.chun.pibridge

import org.json.JSONObject

/** Only inline image blocks; never interpret tool paths or text as a file/URL request. */
fun imageParts(message: JSONObject?): List<JSONObject> =
    message?.optJSONArray("content").objects().filter { it.optString("type") == "image" }

fun toolImageParts(result: JSONObject?, live: JSONObject?): List<JSONObject> =
    imageParts(result ?: live?.optJSONObject("result") ?: live?.optJSONObject("partialResult"))
