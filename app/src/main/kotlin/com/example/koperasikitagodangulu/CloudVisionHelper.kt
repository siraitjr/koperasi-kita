package com.example.koperasikitagodangulu

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helper untuk Google Cloud Vision API
 * Menggantikan ML Kit on-device untuk akurasi OCR yang jauh lebih tinggi
 *
 * SETUP:
 * 1. Enable Cloud Vision API di Google Cloud Console
 * 2. Buat API Key dan restrict ke package name + Cloud Vision API only
 * 3. Ganti API_KEY di bawah
 */
object CloudVisionHelper {

    private const val TAG = "CloudVision"

    // ============================================================
    // GANTI DENGAN API KEY ANDA DARI GOOGLE CLOUD CONSOLE
    // ============================================================
    private const val API_KEY = "AIzaSyClSAkOJh3PC-0wgell2dEBiLhY-HmHG6Q"

    private const val ENDPOINT =
        "https://vision.googleapis.com/v1/images:annotate?key=$API_KEY"

    /**
     * Kirim bitmap ke Cloud Vision API dan dapatkan teks OCR
     * HARUS dipanggil dari background thread (bukan main thread)
     *
     * @param bitmap Gambar KTP yang sudah di-crop
     * @return String hasil OCR, atau null jika gagal
     */
    fun recognizeText(bitmap: Bitmap): String? {
        return try {
            // Step 1: Convert bitmap ke Base64 (JPEG quality 90)
            val base64Image = bitmapToBase64(bitmap)
            Log.d(TAG, "Image encoded to base64, length: ${base64Image.length}")

            // Step 2: Buat request body JSON
            val requestBody = buildRequestBody(base64Image)

            // Step 3: Kirim HTTP POST request ke Cloud Vision API
            val response = sendRequest(requestBody)

            // Step 4: Parse response JSON dan ambil teks
            val text = parseResponse(response)
            Log.d(TAG, "=== CLOUD VISION OCR RESULT ===\n$text")

            text
        } catch (e: Exception) {
            Log.e(TAG, "Cloud Vision API error: ${e.message}", e)
            null
        }
    }

    /**
     * Convert Bitmap ke Base64 string
     * Menggunakan JPEG quality 90 untuk keseimbangan ukuran dan kualitas
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Buat JSON request body untuk Cloud Vision API
     *
     * Menggunakan DOCUMENT_TEXT_DETECTION (bukan TEXT_DETECTION) karena:
     * - Lebih akurat untuk dokumen terstruktur seperti KTP
     * - Mempertahankan layout dan urutan baris
     * - Mendukung bahasa Indonesia
     */
    private fun buildRequestBody(base64Image: String): String {
        val request = JSONObject()
        val requests = JSONArray()

        val item = JSONObject()

        // Image content (base64)
        val image = JSONObject()
        image.put("content", base64Image)
        item.put("image", image)

        // Feature: DOCUMENT_TEXT_DETECTION untuk akurasi terbaik pada dokumen
        val features = JSONArray()
        val feature = JSONObject()
        feature.put("type", "DOCUMENT_TEXT_DETECTION")
        feature.put("maxResults", 1)
        features.put(feature)
        item.put("features", features)

        // Hint bahasa: Indonesia + English
        val imageContext = JSONObject()
        val languageHints = JSONArray()
        languageHints.put("id")  // Bahasa Indonesia
        languageHints.put("en")  // English (untuk nama/istilah asing)
        imageContext.put("languageHints", languageHints)
        item.put("imageContext", imageContext)

        requests.put(item)
        request.put("requests", requests)

        return request.toString()
    }

    /**
     * Kirim HTTP POST request ke Cloud Vision API
     * Menggunakan java.net.HttpURLConnection (tidak perlu library tambahan)
     */
    private fun sendRequest(requestBody: String): String {
        val url = URL(ENDPOINT)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 15000  // 15 detik timeout
                readTimeout = 15000
            }

            // Kirim request body
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "HTTP Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "Unknown error"
                Log.e(TAG, "API Error ($responseCode): $errorBody")
                throw Exception("Cloud Vision API returned $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse JSON response dari Cloud Vision API
     *
     * Response structure:
     * {
     *   "responses": [{
     *     "fullTextAnnotation": { "text": "..." },
     *     "textAnnotations": [{ "description": "..." }, ...]
     *   }]
     * }
     */
    private fun parseResponse(responseJson: String): String? {
        val response = JSONObject(responseJson)
        val responses = response.getJSONArray("responses")

        if (responses.length() == 0) {
            Log.w(TAG, "Empty responses array")
            return null
        }

        val firstResponse = responses.getJSONObject(0)

        // Cek apakah ada error dari API
        if (firstResponse.has("error")) {
            val error = firstResponse.getJSONObject("error")
            Log.e(TAG, "Vision API returned error: ${error.optString("message")}")
            return null
        }

        // Prioritas 1: fullTextAnnotation — teks lengkap dengan layout
        if (firstResponse.has("fullTextAnnotation")) {
            val fullText = firstResponse
                .getJSONObject("fullTextAnnotation")
                .getString("text")
            Log.d(TAG, "Got fullTextAnnotation (${fullText.length} chars)")
            return fullText
        }

        // Prioritas 2: textAnnotations[0].description — fallback
        if (firstResponse.has("textAnnotations")) {
            val annotations = firstResponse.getJSONArray("textAnnotations")
            if (annotations.length() > 0) {
                val description = annotations.getJSONObject(0).getString("description")
                Log.d(TAG, "Got textAnnotations fallback (${description.length} chars)")
                return description
            }
        }

        Log.w(TAG, "No text found in response")
        return null
    }
}