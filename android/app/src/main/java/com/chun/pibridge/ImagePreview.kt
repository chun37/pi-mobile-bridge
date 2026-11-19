package com.chun.pibridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun ImagePreview(part: JSONObject) {
    val encoded = part.optString("data")
    val bitmap by
        produceState<Bitmap?>(null, encoded) {
            value =
                withContext(Dispatchers.Default) {
                    runCatching {
                            require(encoded.length <= 6 * 1024 * 1024)
                            val bytes = Base64.decode(encoded, Base64.DEFAULT)
                            val options =
                                BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            require(options.outWidth > 0 && options.outHeight > 0)
                            var sample = 1
                            while (
                                options.outWidth / sample > 768 || options.outHeight / sample > 768
                            ) sample *= 2
                            options.inJustDecodeBounds = false
                            options.inSampleSize = sample
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        }
                        .getOrNull()
                }
        }
    val image = bitmap
    if (image != null)
        Image(
            image.asImageBitmap(),
            contentDescription = "添付画像",
            modifier = Modifier.heightIn(max = 256.dp),
        )
    else Text("画像添付 (${part.optString("mimeType")})")
}
