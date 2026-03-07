package com.example.koperasikitagodangulu.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

object DebugUtils {

    fun debugKtpScan(bitmap: Bitmap, context: Context) {
        val tempFile = File.createTempFile("ktp_debug_", ".jpg", context.cacheDir).apply {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, FileOutputStream(this))
        }
        Log.d("DEBUG_KTP", "Gambar disimpan di: ${tempFile.absolutePath}")

        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                Log.d("DEBUG_KTP", "=== HASIL OCR ===")
                Log.d("DEBUG_KTP", visionText.text)
            }
    }

    private fun logOcrResults(visionText: Text) {
        Log.d("DEBUG_KTP", "=== HASIL OCR MENTAH ===")
        Log.d("DEBUG_KTP", visionText.text)

        Log.d("DEBUG_KTP", "=== ANALISIS STRUKTUR ===")
        visionText.textBlocks.forEachIndexed { blockIndex, block ->
            Log.d("DEBUG_KTP", "Blok #$blockIndex: ${block.text}")
            block.lines.forEachIndexed { lineIndex, line ->
                Log.d("DEBUG_KTP", "  Baris #$lineIndex: ${line.text}")
                line.elements.forEachIndexed { elementIndex, element ->
                    Log.d("DEBUG_KTP", "    Elemen #$elementIndex: ${element.text}")
                }
            }
        }
    }

    fun saveOcrResultToFile(text: String, context: Context): String {
        return try {
            val file = File(context.cacheDir, "ktp_ocr_result_${System.currentTimeMillis()}.txt")
            file.writeText(text)
            file.absolutePath
        } catch (e: Exception) {
            Log.e("DEBUG_KTP", "Gagal menyimpan hasil OCR", e)
            ""
        }
    }
}