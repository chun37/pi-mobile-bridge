package com.chun.pibridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

private val BridgeColors =
    darkColorScheme(
        primary = Color(0xff6cdec8),
        onPrimary = Color(0xff002c26),
        primaryContainer = Color(0xff17463f),
        onPrimaryContainer = Color(0xffd2fff4),
        background = Color(0xff0e141b),
        surface = Color(0xff0e141b),
        surfaceVariant = Color(0xff1c2632),
        onSurface = Color(0xffe4eaf2),
        onSurfaceVariant = Color(0xff9eacbd),
        outline = Color(0xff394657),
        error = Color(0xffffb4b4),
    )


class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)
    private val imageDownloads by lazy {
        androidx.lifecycle.ViewModelProvider(this)[ImageDownloads::class.java]
    }
    private val imageSaveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        imageDownloads.complete(if (it.resultCode == RESULT_OK) it.data?.data else null)
    }

    internal fun saveImage(asset: ImageAsset) {
        if (!imageDownloads.begin(asset)) return
        try {
            imageSaveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(asset.mimeType)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"))
                .putExtra(Intent.EXTRA_TITLE, asset.filename))
        } catch (_: Exception) { imageDownloads.launchFailed() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CompositionLocalProvider(LocalImageDownload provides ::saveImage) {
                MaterialTheme(colorScheme = BridgeColors) {
                    Surface(Modifier.fillMaxSize()) {
                        if (unlocked) BridgeApp()
                        else
                            Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("Pi Bridge", style = MaterialTheme.typography.headlineMedium)
                                Text("生体認証でPCへの接続を許可してください", Modifier.padding(vertical = 16.dp))
                                Button(onClick = { authenticate() }) { Text("生体認証で開く") }
                            }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!Profiles(this).biometric) unlocked = true else authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (Profiles(this).biometric) unlocked = false
    }

    private fun authenticate() {
        BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        unlocked = true
                    }
                },
            )
            .authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Pi Bridge を開く")
                    .setSubtitle("PCへの接続を許可")
                    .setNegativeButtonText("キャンセル")
                    .build()
            )
    }
}
