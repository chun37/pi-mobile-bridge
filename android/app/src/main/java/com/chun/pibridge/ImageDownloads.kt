package com.chun.pibridge

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalImageDownload = staticCompositionLocalOf<((ImageAsset) -> Unit)?> { null }

/** Owned by the Activity, not a chat row: survives configuration changes and app re-locking. */
class ImageDownloads(application: Application) : AndroidViewModel(application) {
    private var pending: ImageAsset? = null
    @Volatile internal var saveState: String = "idle"
        private set
    @Volatile internal var lastSavedUri: Uri? = null
        private set

    fun begin(asset: ImageAsset): Boolean {
        if (pending != null) return false
        pending = asset
        lastSavedUri = null
        saveState = "pending"
        return true
    }

    fun complete(uri: Uri?) {
        val asset = pending
        pending = null
        if (uri == null) { saveState = "cancelled"; return }
        if (uri.scheme != "content") {
            saveState = "unsupported"
            message("この保存先には対応していません")
            return
        }
        if (asset == null) {
            saveState = "expired"
            message("画像をもう一度選んで保存してください")
            return
        }
        saveState = "writing"
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(asset.bytes) } ?: error("Cannot open destination")
                }.isSuccess
            }
            if (success) lastSavedUri = uri
            saveState = if (success) "saved" else "failed"
            message(if (success) "画像を保存しました" else "画像を保存できませんでした")
        }
    }

    fun launchFailed() {
        pending = null
        saveState = "launch_failed"
        message("保存先を開けませんでした")
    }

    private fun message(text: String) =
        Toast.makeText(getApplication(), text, Toast.LENGTH_LONG).show()
}
