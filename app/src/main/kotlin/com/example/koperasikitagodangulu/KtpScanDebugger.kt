package com.example.koperasikitagodangulu.utils

import android.util.Log
import com.example.koperasikitagodangulu.KtpData

/**
 * Utility class untuk testing dan debugging hasil OCR KTP
 */
object KtpScanDebugger {

    private const val TAG = "KTP_SCAN_DEBUG"
    private const val SEPARATOR = "═══════════════════════════════════"

    /**
     * Print detailed debug info untuk hasil scan KTP
     */
    fun debugScanResult(
        rawOcrText: String,
        parsedData: KtpData,
        processingTimeMs: Long = 0
    ) {
        Log.d(TAG, SEPARATOR)
        Log.d(TAG, "🔍 KTP SCAN DEBUG REPORT")
        Log.d(TAG, SEPARATOR)

        // Raw OCR Text
        Log.d(TAG, "📝 RAW OCR TEXT (${rawOcrText.length} chars):")
        Log.d(TAG, "---")
        rawOcrText.lines().forEachIndexed { index, line ->
            Log.d(TAG, "Line ${index.toString().padStart(2, '0')}: [$line]")
        }
        Log.d(TAG, "---")

        // Parsed Results
        Log.d(TAG, "✅ PARSED RESULTS:")
        Log.d(TAG, "• NIK: ${parsedData.nik ?: "❌ NOT FOUND"}")
        Log.d(TAG, "• Nama: ${parsedData.nama ?: "❌ NOT FOUND"}")
        Log.d(TAG, "• Alamat: ${parsedData.alamat ?: "❌ NOT FOUND"}")
        Log.d(TAG, "• Error: ${parsedData.error ?: "✅ NONE"}")

        // Validation
        Log.d(TAG, "🔍 VALIDATION:")
        validateNik(parsedData.nik)
        validateNama(parsedData.nama)
        validateAlamat(parsedData.alamat)

        // Performance
        if (processingTimeMs > 0) {
            Log.d(TAG, "⏱️ PERFORMANCE:")
            Log.d(TAG, "• Processing Time: ${processingTimeMs}ms")
            Log.d(TAG, "• Performance: ${getPerformanceRating(processingTimeMs)}")
        }

        // Success Rate
        val successRate = calculateSuccessRate(parsedData)
        Log.d(TAG, "📊 SUCCESS RATE: $successRate%")
        Log.d(TAG, "• Rating: ${getRating(successRate)}")

        Log.d(TAG, SEPARATOR)
    }

    /**
     * Validate NIK format dan struktur
     */
    private fun validateNik(nik: String?) {
        if (nik == null) {
            Log.d(TAG, "  ❌ NIK is null")
            return
        }

        val validations = mutableListOf<String>()

        // Length check
        if (nik.length == 16) {
            validations.add("✅ Length: 16 digits")
        } else {
            validations.add("❌ Length: ${nik.length} (should be 16)")
        }

        // Digits only check
        if (nik.all { it.isDigit() }) {
            validations.add("✅ Format: All digits")
        } else {
            validations.add("❌ Format: Contains non-digits")
        }

        // Province code check (first 2 digits)
        val provinceCode = nik.take(2).toIntOrNull()
        if (provinceCode != null && isValidProvinceCode(provinceCode)) {
            validations.add("✅ Province Code: $provinceCode (valid)")
        } else {
            validations.add("⚠️ Province Code: $provinceCode (check validity)")
        }

        // Birth date check (digits 7-12)
        if (nik.length >= 12) {
            val birthDate = nik.substring(6, 12)
            val day = birthDate.substring(0, 2).toIntOrNull()
            val month = birthDate.substring(2, 4).toIntOrNull()
            val year = birthDate.substring(4, 6).toIntOrNull()

            val realDay = if (day != null && day > 40) day - 40 else day

            if (realDay in 1..31 && month in 1..12) {
                validations.add("✅ Birth Date: Valid (${realDay}/${month}/${year})")
            } else {
                validations.add("⚠️ Birth Date: Check validity (${day}/${month}/${year})")
            }
        }

        validations.forEach { Log.d(TAG, "  $it") }
    }

    /**
     * Validate nama format
     */
    private fun validateNama(nama: String?) {
        if (nama == null) {
            Log.d(TAG, "  ❌ Nama is null")
            return
        }

        val validations = mutableListOf<String>()

        // Length check
        if (nama.length >= 3) {
            validations.add("✅ Length: ${nama.length} chars (min 3)")
        } else {
            validations.add("❌ Length: ${nama.length} (too short)")
        }

        // Contains letters
        if (nama.any { it.isLetter() }) {
            validations.add("✅ Format: Contains letters")
        } else {
            validations.add("❌ Format: No letters found")
        }

        // No digits
        if (!nama.any { it.isDigit() }) {
            validations.add("✅ Format: No digits (correct)")
        } else {
            validations.add("⚠️ Format: Contains digits (unusual)")
        }

        // Word count
        val wordCount = nama.split(" ").filter { it.isNotBlank() }.size
        validations.add("📊 Word Count: $wordCount")

        validations.forEach { Log.d(TAG, "  $it") }
    }

    /**
     * Validate alamat format
     */
    private fun validateAlamat(alamat: String?) {
        if (alamat == null) {
            Log.d(TAG, "  ❌ Alamat is null")
            return
        }

        val validations = mutableListOf<String>()

        // Length check
        if (alamat.length >= 10) {
            validations.add("✅ Length: ${alamat.length} chars (min 10)")
        } else {
            validations.add("❌ Length: ${alamat.length} (too short)")
        }

        // Common keywords
        val keywords = listOf("JL", "JALAN", "RT", "RW", "KEL", "KEC", "DESA", "NO", "BLOK")
        val foundKeywords = keywords.filter { alamat.contains(it, ignoreCase = true) }
        if (foundKeywords.isNotEmpty()) {
            validations.add("✅ Keywords Found: ${foundKeywords.joinToString(", ")}")
        } else {
            validations.add("⚠️ No address keywords found")
        }

        // RT/RW pattern
        val rtRwPattern = "\\d{1,3}[/\\s]+\\d{1,3}".toRegex()
        if (rtRwPattern.containsMatchIn(alamat)) {
            validations.add("✅ RT/RW Pattern: Found")
        } else {
            validations.add("ℹ️ RT/RW Pattern: Not found")
        }

        validations.forEach { Log.d(TAG, "  $it") }
    }

    /**
     * Check if province code is valid for Indonesia
     */
    private fun isValidProvinceCode(code: Int): Boolean {
        return code in listOf(
            11, 12, 13, 14, 15, 16, 17, 18, 19, 21, // Sumatera
            31, 32, 33, 34, 35, 36, // Jawa
            51, 52, 53, // Bali, NTB, NTT
            61, 62, 63, 64, 65, // Kalimantan
            71, 72, 73, 74, 75, 76, // Sulawesi
            81, 82, // Maluku
            91, 92, 94 // Papua
        )
    }

    /**
     * Calculate success rate
     */
    private fun calculateSuccessRate(data: KtpData): Int {
        var score = 0
        val maxScore = 3

        if (!data.nik.isNullOrBlank() && data.nik.length == 16) score++
        if (!data.nama.isNullOrBlank() && data.nama.length >= 3) score++
        if (!data.alamat.isNullOrBlank() && data.alamat.length >= 10) score++

        return (score * 100) / maxScore
    }

    /**
     * Get rating based on success rate
     */
    private fun getRating(successRate: Int): String {
        return when (successRate) {
            100 -> "⭐⭐⭐⭐⭐ PERFECT!"
            in 67..99 -> "⭐⭐⭐⭐ GOOD"
            in 34..66 -> "⭐⭐⭐ PARTIAL"
            in 1..33 -> "⭐⭐ POOR"
            else -> "⭐ FAILED"
        }
    }

    /**
     * Get performance rating based on processing time
     */
    private fun getPerformanceRating(timeMs: Long): String {
        return when {
            timeMs < 500 -> "⚡ EXCELLENT"
            timeMs < 1000 -> "✅ GOOD"
            timeMs < 2000 -> "⚠️ ACCEPTABLE"
            else -> "🐌 SLOW"
        }
    }

    /**
     * Generate test report untuk multiple scans
     */
    fun generateTestReport(results: List<Pair<String, KtpData>>) {
        Log.d(TAG, SEPARATOR)
        Log.d(TAG, "📊 TEST REPORT SUMMARY")
        Log.d(TAG, SEPARATOR)

        val totalTests = results.size
        val successfulNik = results.count { !it.second.nik.isNullOrBlank() }
        val successfulNama = results.count { !it.second.nama.isNullOrBlank() }
        val successfulAlamat = results.count { !it.second.alamat.isNullOrBlank() }
        val completeSuccess = results.count {
            !it.second.nik.isNullOrBlank() &&
                    !it.second.nama.isNullOrBlank() &&
                    !it.second.alamat.isNullOrBlank()
        }

        Log.d(TAG, "Total Tests: $totalTests")
        Log.d(TAG, "NIK Success: $successfulNik/$totalTests (${(successfulNik * 100) / totalTests}%)")
        Log.d(TAG, "Nama Success: $successfulNama/$totalTests (${(successfulNama * 100) / totalTests}%)")
        Log.d(TAG, "Alamat Success: $successfulAlamat/$totalTests (${(successfulAlamat * 100) / totalTests}%)")
        Log.d(TAG, "Complete Success: $completeSuccess/$totalTests (${(completeSuccess * 100) / totalTests}%)")

        // Detail per test
        Log.d(TAG, "---")
        results.forEachIndexed { index, (testName, data) ->
            val status = if (data.error.isNullOrEmpty()) "✅" else "❌"
            Log.d(TAG, "Test ${index + 1} [$testName]: $status")
            if (!data.error.isNullOrEmpty()) {
                Log.d(TAG, "  Error: ${data.error}")
            }
        }

        Log.d(TAG, SEPARATOR)
    }

    /**
     * Sample test data untuk debugging
     */
    fun getSampleOcrTexts(): List<Pair<String, String>> {
        return listOf(
            "Sample 1 - Good Quality" to """
                PROVINSI JAWA BARAT
                KABUPATEN BOGOR
                NIK : 3271012345678901
                Nama : JOHN DOE SMITH
                Tempat/Tgl Lahir : JAKARTA, 01-01-1990
                Jenis Kelamin : LAKI-LAKI
                Alamat : JL MERDEKA NO 123
                RT/RW : 001/002
                Kel/Desa : SUKA MAJU
                Kecamatan : BOGOR BARAT
            """.trimIndent(),

            "Sample 2 - OCR Errors" to """
                PR0VINSI JAWA BARAT
                KABU9ATEN B0G0R
                N1K : 327I0I2345678901
                Nama : J0HN D0E SM1TH
                Alamat : JL MERDEKA N0 I23
                RT/RW : 00I/002
            """.trimIndent(),

            "Sample 3 - Minimal" to """
                NIK 3271012345678901
                JOHN DOE
                SUNGAI PANDAHAN
            """.trimIndent()
        )
    }
}

/**
 * Extension function untuk easy debugging
 */
fun KtpData.debug(rawText: String = "", timeMs: Long = 0) {
    KtpScanDebugger.debugScanResult(rawText, this, timeMs)
}