package com.chun.pibridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

class ImageAsset(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int) {
    val filename: String = "pi-bridge-" +
        MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) } +
        "." + when (mimeType) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
}

/** Bound encoded input and decoded dimensions. No URL fetching or local-path access. */
fun decodeImageAsset(part: JSONObject): ImageAsset {
    val encoded = part.optString("data")
    require(encoded.isNotEmpty() && encoded.length <= 6 * 1024 * 1024) { "画像データが空か、上限を超えています" }
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val allowed = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    val mime = bounds.outMimeType.orEmpty()
    require(mime in allowed) { "対応していない画像形式です" }
    val declared = part.optString("mimeType").replace("image/jpg", "image/jpeg")
    require(declared.isEmpty() || declared == mime) { "画像形式が一致しません" }
    require(bounds.outWidth in 1..100000 && bounds.outHeight in 1..100000 &&
        bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "画像サイズが上限を超えています" }
    return ImageAsset(bytes, mime, bounds.outWidth, bounds.outHeight)
}

fun imageBitmap(asset: ImageAsset, maxSide: Int): Bitmap {
    var sample = 1
    while (asset.width / sample > maxSide || asset.height / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return requireNotNull(BitmapFactory.decodeByteArray(asset.bytes, 0, asset.bytes.size, options))
}

private data class LoadedImage(val asset: ImageAsset, val bitmap: Bitmap)

@Composable
fun ImagePreview(part: JSONObject) {
    key(part.optString("data"), part.optString("mimeType")) { InlineImagePreview(part) }
}

@Composable
private fun InlineImagePreview(part: JSONObject) {
    val encoded = part.optString("data")
    val mime = part.optString("mimeType")
    val loaded by produceState<Result<LoadedImage>?>(null, encoded, mime) {
        value = withContext(Dispatchers.Default) {
            runCatching { val asset = decodeImageAsset(part); LoadedImage(asset, imageBitmap(asset, 768)) }
        }
    }
    var expanded by remember(encoded, mime) { mutableStateOf(false) }
    val download = LocalImageDownload.current
    val result = loaded
    if (result == null) {
        Text("画像を読み込み中…")
        return
    }
    val image = result.getOrNull()
    if (image == null) {
        Text("画像を表示できません（${result.exceptionOrNull()?.message ?: "読込エラー"}）",
            color = MaterialTheme.colorScheme.error)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(image.bitmap.asImageBitmap(), contentDescription = "画像をプレビュー",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 256.dp)
                .testTag("imageThumbnail").clickable { expanded = true })
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = true }) { Text("拡大") }
            TextButton(onClick = { download?.invoke(image.asset) }, enabled = download != null,
                modifier = Modifier.testTag("saveImage")) { Text("保存") }
            Text("${image.asset.width} × ${image.asset.height}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (expanded) ImageDialog(image.asset, image.bitmap, { expanded = false }, download)
}

@Composable
private fun ImageDialog(asset: ImageAsset, thumbnail: Bitmap, close: () -> Unit,
    download: ((ImageAsset) -> Unit)?) {
    val full by produceState(thumbnail, asset) {
        value = withContext(Dispatchers.Default) { runCatching { imageBitmap(asset, 2048) }.getOrDefault(thumbnail) }
    }
    var zoom by remember(asset) { mutableFloatStateOf(1f) }
    var offset by remember(asset) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun transform(factor: Float, pan: Offset = Offset.Zero) {
        zoom = (zoom * factor).coerceIn(1f, 8f)
        val next = offset + pan
        val maxX = viewport.width * (zoom - 1) / 2
        val maxY = viewport.height * (zoom - 1) / 2
        offset = Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Default.Close, "プレビューを閉じる") }
                    Text("画像プレビュー", Modifier.weight(1f))
                    TextButton(onClick = { download?.invoke(asset) }, enabled = download != null,
                        modifier = Modifier.testTag("saveFullImage")) { Text("原画像を保存") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("imageCanvas")
                    .onSizeChanged { viewport = it }
                    .pointerInput(asset) { detectTransformGestures { _, pan, factor, _ -> transform(factor, pan) } }) {
                    Image(full.asImageBitmap(), "拡大画像", contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                        })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { transform(0.5f) }, modifier = Modifier.testTag("zoomOut")) { Text("−") }
                    Text("${(zoom * 100).roundToInt()}%", Modifier.testTag("imageZoom"))
                    TextButton(onClick = { transform(2f) }, modifier = Modifier.testTag("zoomIn")) { Text("＋") }
                    TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("リセット") }
                }
            }
        }
    }
}
