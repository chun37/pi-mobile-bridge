package com.chun.pibridge

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AttachmentStrip(images: List<DraftImage>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    if (images.isEmpty()) return
    val state = rememberLazyListState()
    val ids = images.map { it.id }
    var previous by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(ids) {
        val added = ids.any { it !in previous }
        previous = ids.toSet()
        if (added) state.animateScrollToItem(images.lastIndex)
    }
    LazyRow(state = state, modifier = modifier.fillMaxWidth().testTag("attachmentStrip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(images, key = { _, image -> image.id }) { index, image ->
            AttachmentThumbnail(image, index, onRemove)
        }
    }
}

@Composable
private fun AttachmentThumbnail(image: DraftImage, index: Int, onRemove: (String) -> Unit) {
    val bitmap by produceState<Result<Bitmap>?>(null, image.id, image.image) {
        value = withContext(Dispatchers.Default) {
            runCatching { imageBitmap(decodeImageAsset(image.image), 384) }
        }
    }
    Box(Modifier.size(104.dp).testTag("attachment:${image.id}")) {
        Box(Modifier.fillMaxSize().padding(top = 8.dp, end = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val preview = bitmap?.getOrNull()
            when {
                preview != null -> Image(preview.asImageBitmap(), "添付画像 ${index + 1}",
                    modifier = Modifier.fillMaxSize().testTag("attachmentPreview:${image.id}"), contentScale = ContentScale.Crop)
                bitmap == null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Default.BrokenImage, "${index + 1}枚目のプレビューを表示できません",
                    Modifier.testTag("attachmentPreviewError:${image.id}"))
            }
        }
        IconButton(onClick = { onRemove(image.id) }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
            .testTag("attachmentRemove:${image.id}")) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface) {
                Icon(Icons.Default.Close, "${index + 1}枚目の画像の添付を解除", Modifier.padding(6.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}
