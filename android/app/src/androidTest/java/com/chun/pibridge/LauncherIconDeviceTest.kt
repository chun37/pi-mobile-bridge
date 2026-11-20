package com.chun.pibridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconDeviceTest {
    private fun markerCount(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it == 0xfff7fafc.toInt() || it == 0xff79e2c4.toInt() }
    }

    @Test fun installedIconIsAdaptiveAndSoftMarkFitsRoundAndRoundedSquareMasks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        val adaptive = icon as AdaptiveIconDrawable
        val rendered = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        adaptive.setBounds(0, 0, 512, 512)
        adaptive.draw(Canvas(rendered))
        assertTrue(markerCount(rendered) > 1000)
        File(context.filesDir, "soft-launcher-installed.png").outputStream().use {
            rendered.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // AdaptiveIconDrawable expands its layers beyond the visible icon mask.
        val foreground = adaptive.foreground.constantState!!.newDrawable().mutate()
        foreground.setBounds(-128, -128, 640, 640)
        val original = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        foreground.draw(Canvas(original))
        val expected = markerCount(original)
        assertTrue(expected > 1000)
        for (circle in listOf(true, false)) {
            val result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val path = Path().apply {
                if (circle) addCircle(256f, 256f, 256f, Path.Direction.CW)
                else addRoundRect(RectF(0f, 0f, 512f, 512f), 128f, 128f, Path.Direction.CW)
            }
            canvas.clipPath(path)
            foreground.draw(canvas)
            assertEquals("The SOFT mark must not be clipped by the mask", expected, markerCount(result))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            assertNotNull(adaptive.monochrome)
        }
    }
}
