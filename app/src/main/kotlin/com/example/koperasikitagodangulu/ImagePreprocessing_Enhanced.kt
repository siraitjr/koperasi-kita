package com.example.koperasikitagodangulu

// Enhanced Image Preprocessing untuk KtpScanner.kt
// Optimized untuk KTP Indonesia

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import kotlin.math.max
import kotlin.math.min

fun preprocessImageForOCR(source: Bitmap): Bitmap {
    Log.d("ImagePreprocess", "Starting image preprocessing...")
    Log.d("ImagePreprocess", "Input size: ${source.width}x${source.height}")

    // Step 1: Resize ke ukuran optimal untuk ML Kit OCR
    val resized = resizeForOCR(source, targetWidth = 1600)
    Log.d("ImagePreprocess", "After resize: ${resized.width}x${resized.height}")

    // Step 2: Enhance contrast dan convert ke grayscale
    val enhanced = enhanceForOCR(resized)

    // Step 3: Binarization DIHAPUS — ML Kit sudah punya preprocessing internal
    // Binarisasi justru merusak kualitas OCR pada KTP berwarna
    Log.d("ImagePreprocess", "Preprocessing complete (grayscale + contrast only)")

    return enhanced
}

/**
 * Resize gambar ke ukuran optimal untuk OCR
 * Menjaga aspect ratio
 */
private fun resizeForOCR(source: Bitmap, targetWidth: Int): Bitmap {
    val width = source.width
    val height = source.height

    // Jika sudah ukuran yang optimal, tidak perlu resize
    if (width in 1200..2000) {
        Log.d("ImagePreprocess", "Size already optimal, skipping resize")
        return source
    }

    val scaleFactor = targetWidth.toFloat() / width.toFloat()
    val newWidth = (width * scaleFactor).toInt()
    val newHeight = (height * scaleFactor).toInt()

    Log.d("ImagePreprocess", "Resizing from ${width}x${height} to ${newWidth}x${newHeight}")

    return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
}

/**
 * Enhance gambar untuk OCR
 * Menggunakan kombinasi grayscale + contrast boost yang optimal untuk KTP
 */
private fun enhanceForOCR(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Analisis brightness rata-rata untuk adaptive processing
    val avgBrightness = analyzeAverageBrightness(source)
    Log.d("ImagePreprocess", "Average brightness: $avgBrightness")

    // Adaptive contrast dan brightness berdasarkan kondisi gambar
    val contrast: Float
    val brightness: Float

    when {
        avgBrightness < 80 -> {
            // Gambar gelap - boost brightness moderat
            contrast = 1.3f
            brightness = 30f
            Log.d("ImagePreprocess", "Dark image detected, boosting brightness")
        }
        avgBrightness > 180 -> {
            // Gambar terlalu terang - kurangi brightness sedikit
            contrast = 1.4f
            brightness = -10f
            Log.d("ImagePreprocess", "Bright image detected, reducing brightness")
        }
        else -> {
            // Kondisi normal - enhance ringan saja
            contrast = 1.2f
            brightness = 10f
            Log.d("ImagePreprocess", "Normal lighting detected")
        }
    }

    // ColorMatrix untuk grayscale dengan contrast enhancement
    // Formula: output = (input - 128) * contrast + 128 + brightness
    // Untuk grayscale, kita kombinasikan dengan luminance weights (0.299, 0.587, 0.114)
    val cm = ColorMatrix(floatArrayOf(
        0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, brightness + (1 - contrast) * 128 * 0.299f,
        0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, brightness + (1 - contrast) * 128 * 0.587f,
        0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, brightness + (1 - contrast) * 128 * 0.114f,
        0f, 0f, 0f, 1f, 0f
    ))

    paint.colorFilter = ColorMatrixColorFilter(cm)
    canvas.drawBitmap(source, 0f, 0f, paint)

    return result
}

/**
 * Analisis brightness rata-rata dari gambar
 * Digunakan untuk adaptive preprocessing
 */
private fun analyzeAverageBrightness(source: Bitmap): Float {
    val width = source.width
    val height = source.height

    // Sample pixels untuk efisiensi (setiap 10 pixel)
    var totalBrightness = 0L
    var sampleCount = 0

    for (y in 0 until height step 10) {
        for (x in 0 until width step 10) {
            val pixel = source.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Luminance formula
            val brightness = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            totalBrightness += brightness
            sampleCount++
        }
    }

    return if (sampleCount > 0) totalBrightness.toFloat() / sampleCount else 128f
}

/**
 * DEPRECATED: Crop function dipindahkan ke KtpScanner.kt
 * Fungsi ini tetap ada untuk backward compatibility tapi tidak digunakan
 */
@Deprecated("Use cropBitmapToKtpArea in KtpScanner.kt instead")
fun cropToKtpFrame(source: Bitmap): Bitmap {
    val sourceWidth = source.width
    val sourceHeight = source.height

    // Frame dimensions matching KtpScanner overlay
    val frameWidthRatio = 0.85f
    val ktpAspectRatio = 1.586f
    val frameTopRatio = 0.30f

    val frameWidth = (sourceWidth * frameWidthRatio).toInt()
    val frameHeight = (frameWidth / ktpAspectRatio).toInt()
    val frameLeft = ((sourceWidth - frameWidth) / 2f).toInt()
    val frameTop = (sourceHeight * frameTopRatio).toInt()

    // Safety bounds check
    val safeLeft = frameLeft.coerceIn(0, sourceWidth - 1)
    val safeTop = frameTop.coerceIn(0, sourceHeight - 1)
    val safeWidth = frameWidth.coerceAtMost(sourceWidth - safeLeft)
    val safeHeight = frameHeight.coerceAtMost(sourceHeight - safeTop)

    Log.d("ImagePreprocess", "Cropping to KTP frame: ${safeLeft},${safeTop} size ${safeWidth}x${safeHeight}")

    return try {
        Bitmap.createBitmap(source, safeLeft, safeTop, safeWidth, safeHeight)
    } catch (e: Exception) {
        Log.e("ImagePreprocess", "Crop failed, returning original", e)
        source
    }
}

/**
 * Alternative preprocessing dengan sharpening
 * Gunakan jika hasil OCR masih kurang tajam
 */
fun preprocessImageForOCRWithSharpening(source: Bitmap): Bitmap {
    // First, do standard preprocessing
    val preprocessed = preprocessImageForOCR(source)

    // Then apply sharpening
    return applySharpeningFilter(preprocessed)
}

/**
 * Apply sharpening filter menggunakan ColorMatrix approximation
 */
private fun applySharpeningFilter(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Sharpening menggunakan contrast boost (approximation)
    val sharpness = 1.15f
    val offset = -15f

    val cm = ColorMatrix(floatArrayOf(
        sharpness, 0f, 0f, 0f, offset,
        0f, sharpness, 0f, 0f, offset,
        0f, 0f, sharpness, 0f, offset,
        0f, 0f, 0f, 1f, 0f
    ))

    paint.colorFilter = ColorMatrixColorFilter(cm)
    canvas.drawBitmap(source, 0f, 0f, paint)

    return result
}

/**
 * Simple preprocessing untuk fallback
 * Digunakan jika preprocessing utama terlalu lambat atau gagal
 */
fun preprocessImageForOCRSimple(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val processedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(processedBitmap)
    val paint = Paint()

    // Simple high contrast with brightness adjustment
    val contrast = 1.6f
    val brightness = 20f

    val cm = ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, brightness,
        0f, contrast, 0f, 0f, brightness,
        0f, 0f, contrast, 0f, brightness,
        0f, 0f, 0f, 1f, 0f
    ))

    // Add grayscale
    val grayscaleMatrix = ColorMatrix()
    grayscaleMatrix.setSaturation(0f)
    cm.postConcat(grayscaleMatrix)

    paint.colorFilter = ColorMatrixColorFilter(cm)
    canvas.drawBitmap(source, 0f, 0f, paint)

    return processedBitmap
}

/**
 * Adaptive binarization untuk KTP
 * Mengubah gambar menjadi hitam-putih murni berdasarkan threshold lokal
 * Ini sangat meningkatkan akurasi OCR untuk teks KTP
 */
private fun adaptiveBinarize(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    val grayValues = IntArray(pixels.size)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        grayValues[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    // Block size untuk adaptive threshold (harus ganjil)
    val blockSize = 31
    val halfBlock = blockSize / 2
    val offset = 12 // Offset threshold — angka lebih besar = lebih banyak jadi putih

    val outPixels = IntArray(pixels.size)

    for (y in 0 until height) {
        for (x in 0 until width) {
            // Hitung rata-rata area sekitar pixel
            var sum = 0
            var count = 0
            val yStart = max(0, y - halfBlock)
            val yEnd = min(height - 1, y + halfBlock)
            val xStart = max(0, x - halfBlock)
            val xEnd = min(width - 1, x + halfBlock)

            // Sampling setiap 2 pixel untuk kecepatan
            var sy = yStart
            while (sy <= yEnd) {
                var sx = xStart
                while (sx <= xEnd) {
                    sum += grayValues[sy * width + sx]
                    count++
                    sx += 2
                }
                sy += 2
            }

            val localMean = sum / count
            val pixelValue = grayValues[y * width + x]

            // Jika pixel lebih gelap dari rata-rata lokal - offset → hitam, else putih
            outPixels[y * width + x] = if (pixelValue < localMean - offset) {
                0xFF000000.toInt() // Hitam (teks)
            } else {
                0xFFFFFFFF.toInt() // Putih (background)
            }
        }
    }

    result.setPixels(outPixels, 0, width, 0, 0, width, height)
    return result
}