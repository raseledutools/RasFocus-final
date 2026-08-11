package com.rasel.RasFocus.filemanager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

object MagicProFilter {

    /**
     * Applies a "Magic Pro" (CamScanner-like Magic Color) filter to a bitmap.
     * It significantly increases contrast, brightness, and saturation to make text pop
     * and the background white.
     */
    fun applyMagicProFilter(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, src.config ?: Bitmap.Config.ARGB_8888)

        val canvas = Canvas(dest)
        val paint = Paint()

        val colorMatrix = ColorMatrix()

        // 1. Extreme Contrast (1.8x) and Brightness (+30)
        val contrast = 1.25f
        val brightness = 15f
        
        val contrastMatrix = floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        )
        colorMatrix.set(contrastMatrix)

        // 2. Increase Saturation (1.4x) to make colors pop
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.15f)
        colorMatrix.postConcat(satMatrix)

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)

        return dest
    }
}
