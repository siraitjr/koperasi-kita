package com.example.koperasikitagodangulu

import android.util.Log

data class KtpData(
    val nik: String? = null,
    val nama: String? = null,
    val alamat: String? = null,
    val error: String? = null
)

object KtpParser {
    private const val TAG = "KTP_PARSER"

    // ====== Regex & konstanta bantu ======
    private val ONLY_DIGITS = "^[0-9]+$".toRegex()
    private val DIGIT_RUN = "\\d{10,}".toRegex()
    private val NIK_REGEX = "\\b\\d{16}\\b".toRegex()
    private val RT_RW_REGEX = "\\d{1,3}/\\d{1,3}".toRegex()
    private val DATE_REGEX = "\\d{1,2}-\\d{1,2}-\\d{4}".toRegex()

    // Label yang sering salah terbaca - DIPERLUAS
    private val ALAMAT_VARIANTS = listOf(
        "ALAMAT", "Ala mat", "Alamat", "Ala-mat", "Alamal", "Alamar",
        "ALAMAL", "ALAMAR", "ALAMAI", "ALAMA1", "A1AMAT", "ALANAT"
    )

    private val NAMA_VARIANTS = listOf(
        "NAMA", "Nama", "Name", "Narna", "Nana", "NMA", "NANMA",
        "NAIVLA", "NAIMA", "N4MA", "NAM4", "MANA"
    )

    private val NIK_VARIANTS = listOf(
        "NIK", "N1K", "NlK", "NIK:", "N1K:", "NlK:"
    )

    // Prefix label KTP yang sering rusak oleh OCR — untuk filter nama/alamat
    private val KTP_LABEL_PREFIXES = listOf(
        "ALAMAT", "ALAMA", "ALAM",
        "JENISKELAMIN", "JENSKELAMN", "JENISKELAMN", "JENSKELAM",
        "TEMPATLA", "TERPAT", "TERPA", "LAHIR",
        "GOLDARAH", "GOLONGANDARAH",
        "STATUSPER", "STATUSPERK",
        "PEKERJA", "KEWARGA", "BERLAKU",
        "RTRW", "RTRWE",
        "KELDESA", "KELLDESA", "KEVDESA", "KEVDES", "KELDES",
        "KECAMAT", "KECAMATA",
        "PROVINS", "KABUPAT",
        "JAKARTAUT", "JAKARTAU",
        "LAKILLAKI", "LAKILAKI", "LAKHAK",
        "PELAJAR", "MAHASISWA", "WIRASWASTA"
    )

    // Stop tokens untuk alamat - DIPERLUAS
    private val ADDRESS_STOP_TOKENS = listOf(
        "STATUS", "PERKAWINAN", "PEKERJAAN", "KEWARGANEGARAAN",
        "BERLAKU", "HINGGA", "AGAMA", "ISLAM", "KRISTEN", "KATOLIK",
        "HINDU", "BUDDHA", "KONGHUCU", "Gol", "DARAH", "SEUMUR", "HIDUP",
        "JENIS", "KELAMIN", "LAKI", "PEREMPUAN"
    )

    // Keywords yang menandakan alamat - DIPERLUAS
    private val ADDRESS_KEYWORDS = listOf(
        "JL", "JALAN", "JLN", "GANG", "GG", "KAMPUNG", "KP",
        "DUSUN", "DS", "DESA", "KEL", "KELURAHAN", "KEC", "KECAMATAN",
        "RT", "RW", "BLOK", "NO", "PERUMAHAN", "PERUM", "KOMP", "KOMPLEKS",
        "SUNGAI", "PANDAHAN", "MANGGAR", "PASAR", "PETOK", "LORONG", "LR",
        "GODANG", "ULU", "HULU", "HILIR", "INDRAGIRI", "TEMBILAHAN",
        "SIMPANG", "KOTA", "KABUPATEN", "PROVINSI"
    )

    // Kata-kata yang BUKAN nama
    private val NON_NAME_WORDS = setOf(
        "SEUMUR", "HIDUP", "KAWIN", "BELUM", "ISLAM", "KRISTEN", "KATOLIK",
        "HINDU", "BUDDHA", "KONGHUCU", "WNI", "LAKI", "LAKI-LAKI", "PEREMPUAN",
        "PELAJAR", "MAHASISWA", "WIRASWASTA", "PETANI", "PEKEBUN", "STATUS",
        "PERKAWINAN", "PEKERJAAN", "KEWARGANEGARAAN", "BERLAKU", "HINGGA",
        "PROVINSI", "KABUPATEN", "KOTA", "JAWA", "SUMATERA", "KALIMANTAN",
        "SULAWESI", "BARAT", "TIMUR", "TENGAH", "SELATAN", "UTARA", "RIAU",
        "JAKARTA", "ALAMAT", "RTRW"
    )

    // Nama wilayah yang sering tertukar jadi nama orang
    private val AREA_NAMES = setOf(
        "LAGOA", "KOJA", "TANJUNG", "PRIOK", "PENJARINGAN", "PADEMANGAN",
        "CILINCING", "KELAPA", "GADING", "SUNTER", "KEMAYORAN",
        "CEMPAKA", "PUTIH", "JOHAR", "BARU", "TANAH", "ABANG",
        "GAMBIR", "SAWAH", "BESAR", "MANGGA", "DUA"
    )

    fun parse(rawText: String): KtpData {
        Log.d(TAG, "=== PARSING KTP START ===")
        Log.d(TAG, "Raw OCR Text:\n$rawText")

        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        Log.d(TAG, "Processing ${lines.size} lines")

        // Extract NIK first (biasanya paling reliable)
        val nik = extractNik(rawText, lines)
        Log.d(TAG, "✅ NIK: ${nik ?: "NOT FOUND"}")

        // Extract nama
        val nama = extractNama(lines)
        Log.d(TAG, "✅ Nama: ${nama ?: "NOT FOUND"}")

        // Extract alamat, skip lines yang sudah digunakan untuk nama
        val namaLineIndex = findNamaLineIndex(lines, nama)
        val alamat = extractAlamat(lines, namaLineIndex)
        Log.d(TAG, "✅ Alamat: ${alamat ?: "NOT FOUND"}")

        Log.d(TAG, "=== PARSING COMPLETE ===")

        return if (nik.isNullOrBlank() || nama.isNullOrBlank() || alamat.isNullOrBlank()) {
            KtpData(
                nik = nik,
                nama = nama,
                alamat = alamat,
                error = buildString {
                    append("Data tidak lengkap - ")
                    append("NIK: ${if (nik.isNullOrBlank()) "❌" else "✅"}, ")
                    append("Nama: ${if (nama.isNullOrBlank()) "❌" else "✅"}, ")
                    append("Alamat: ${if (alamat.isNullOrBlank()) "❌" else "✅"}")
                }
            )
        } else {
            KtpData(nik = nik, nama = nama, alamat = alamat)
        }
    }

    /**
     * Extract NIK dengan multiple strategies
     * NIK Indonesia: 16 digit, format: PPKKCC-DDMMYY-XXXX
     */
    private fun extractNik(rawText: String, lines: List<String>): String? {
        Log.d(TAG, "=== Extracting NIK ===")

        // Strategy 1: Cari NIK per baris (BUKAN gabung seluruh teks)
        // Ini lebih akurat karena tidak mencampur digit dari baris berbeda
        var bestNik: String? = null
        var bestScore = -1

        for (line in lines) {
            val normalizedLine = normalizeNikDigits(line)
            val digits = normalizedLine.filter { it.isDigit() }

            if (digits.length < 16) continue

            for (j in 0..(digits.length - 16)) {
                val candidate = digits.substring(j, j + 16)
                val score = scoreNik(candidate)

                if (score >= 5) {
                    Log.d(TAG, "High confidence NIK found: $candidate (score: $score)")
                    return candidate
                }

                if (score > bestScore) {
                    bestScore = score
                    bestNik = candidate
                }
            }
        }

        if (bestScore >= 2 && bestNik != null) {
            Log.d(TAG, "Best NIK found with score $bestScore: $bestNik")
            return bestNik
        }

        // Strategy 2: Cari per baris yang mengandung label NIK
        for (i in lines.indices) {
            val line = lines[i]
            val upperLine = line.uppercase()

            // Cek apakah baris ini mengandung label NIK
            val hasNikLabel = NIK_VARIANTS.any { upperLine.contains(it) }

            if (hasNikLabel) {
                Log.d(TAG, "Found NIK label at line $i: $line")

                // Extract digits dari baris ini
                val normalizedLine = normalizeNikDigits(line)
                val digits = normalizedLine.filter { it.isDigit() }

                if (digits.length >= 16) {
                    for (j in 0..(digits.length - 16)) {
                        val candidate = digits.substring(j, j + 16)
                        if (scoreNik(candidate) >= 0) {
                            Log.d(TAG, "NIK found in label line: $candidate")
                            return candidate
                        }
                    }
                }

                // Cek baris berikutnya
                if (i + 1 < lines.size) {
                    val nextLine = normalizeNikDigits(lines[i + 1])
                    val nextDigits = nextLine.filter { it.isDigit() }

                    if (nextDigits.length >= 16) {
                        val candidate = nextDigits.take(16)
                        if (scoreNik(candidate) >= 0) {
                            Log.d(TAG, "NIK found in next line: $candidate")
                            return candidate
                        }
                    }
                }
            }
        }

        // Strategy 3: Fallback - cari exact 16 digit pattern di seluruh text
        for (line in lines) {
            val normalizedLine = normalizeNikDigits(line)
            val nikMatch = NIK_REGEX.find(normalizedLine)
            if (nikMatch != null) {
                val nik = nikMatch.value
                if (scoreNik(nik) >= 0) {
                    Log.d(TAG, "NIK found via regex: $nik")
                    return nik
                }
            }
        }

        Log.d(TAG, "NIK not found with any strategy")
        return null
    }

    private fun extractNama(lines: List<String>): String? {
        Log.d(TAG, "=== Extracting Nama ===")

        // Strategy 1: Cari label "Nama" dan ambil value-nya
        for (i in lines.indices) {
            val line = lines[i]
            val upperLine = line.uppercase()

            val isNamaLabel = NAMA_VARIANTS.any { variant ->
                upperLine.contains(variant)
            }

            if (isNamaLabel) {
                Log.d(TAG, "Found nama label at line $i: $line")

                // Extract nama dari baris yang sama (setelah :)
                val namaFromSameLine = extractValueAfterColon(line)
                if (namaFromSameLine != null && isValidName(namaFromSameLine)) {
                    return cleanName(namaFromSameLine)
                }

                // Cari di baris berikutnya
                if (i + 1 < lines.size) {
                    val nextLine = lines[i + 1]
                    if (!isLabel(nextLine) && isValidName(nextLine)) {
                        return cleanName(nextLine)
                    }

                    val namaFromNextLine = extractValueAfterColon(nextLine)
                    if (namaFromNextLine != null && isValidName(namaFromNextLine)) {
                        return cleanName(namaFromNextLine)
                    }
                }
            }
        }

        // Strategy 2: Cari nama setelah NIK (Cloud Vision sering format ":VALUE")
        val nikIndex = lines.indexOfFirst { line ->
            val normalized = normalizeNikDigits(line)
            normalized.filter { it.isDigit() }.length >= 16
        }

        if (nikIndex != -1) {
            for (i in (nikIndex + 1) until minOf(nikIndex + 6, lines.size)) {
                val line = lines[i]

                // Cloud Vision sering mengeluarkan ":NAMA ORANG" — ambil setelah ":"
                val valueAfterColon = extractValueAfterColon(line)

                // Juga coba bersihkan leading ":" secara manual
                val cleanedLine = line.trimStart(':', ' ', '.')

                // Kandidat: prioritaskan value setelah colon, lalu line yang dibersihkan
                val candidates = listOfNotNull(valueAfterColon, cleanedLine)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()

                for (candidate in candidates) {
                    // Skip label, stop token, dan baris yang bukan nama
                    if (isLabel(candidate) || isStopToken(candidate)) continue
                    if (containsDate(candidate) || isTempatTglLahirLine(candidate)) continue
                    if (!isValidName(candidate)) continue

                    // Skip jika ini nama wilayah (bukan nama orang)
                    val upperCandidate = candidate.uppercase().trim()
                    if (AREA_NAMES.contains(upperCandidate)) continue

                    Log.d(TAG, "Found nama after NIK at line $i: $candidate")
                    return cleanName(candidate)
                }
            }
        }

        // Strategy 3: Cari baris yang paling mirip nama orang
        for (i in lines.indices) {
            val rawLine = lines[i]
            // Bersihkan leading ":" untuk Cloud Vision
            val line = rawLine.trimStart(':', ' ', '.').trim()

            if (line.isBlank()) continue
            if (!isValidName(line)) continue
            if (isLabel(line) || isStopToken(line)) continue
            if (containsDate(line) || isTempatTglLahirLine(line)) continue
            if (containsNonNameWords(line) || isPotentialAddress(line)) continue

            // Skip nama wilayah (1 kata saja)
            if (AREA_NAMES.contains(line.uppercase())) continue

            val words = line.trim().split("\\s+".toRegex())
            // Nama orang biasanya 2-5 kata
            if (words.size in 2..5 && words.all { it.length >= 1 }) {
                Log.d(TAG, "Found potential nama at line $i: $line")
                return cleanName(line)
            }
        }

        // Strategy 4: Terakhir, coba nama 1 kata yang valid
        for (i in lines.indices) {
            val rawLine = lines[i]
            val line = rawLine.trimStart(':', ' ', '.').trim()

            if (line.isBlank()) continue
            if (!isValidName(line)) continue
            if (isLabel(line) || isStopToken(line)) continue
            if (containsDate(line) || isTempatTglLahirLine(line)) continue
            if (isPotentialAddress(line)) continue
            if (AREA_NAMES.contains(line.uppercase())) continue

            val words = line.trim().split("\\s+".toRegex())
            if (words.size == 1 && words[0].length >= 3) {
                Log.d(TAG, "Found single-word nama at line $i: $line")
                return cleanName(line)
            }
        }
        return null
    }

    private fun extractAlamat(lines: List<String>, skipIndex: Int?): String? {
        Log.d(TAG, "=== Extracting Alamat ===")

        val skipIndices = mutableSetOf<Int>()
        skipIndex?.let { skipIndices.add(it) }

        // Strategy 1: Cari alamat dimulai dari baris yang mengandung address keywords
        // Ini lebih robust daripada mencari label "Alamat" (yang sering terpisah dari value-nya)
        for (i in lines.indices) {
            if (i in skipIndices) continue

            val line = lines[i]
            val upperLine = normalizeToAscii(line).uppercase()

            // Cari baris pertama yang mengandung keyword alamat (JL, MANGGAR, BLOK, dll)
            val hasAddressKeyword = ADDRESS_KEYWORDS.any { kw ->
                upperLine.split("[^A-Za-z]+".toRegex()).any { it == kw }
            }

            if (hasAddressKeyword && !isStopToken(line) && !isLabel(line)) {
                Log.d(TAG, "Found potential address at line $i: $line")

                val alamatParts = mutableListOf<String>()
                alamatParts.add(extractValueAfterColon(line) ?: line)

                // Kumpulkan baris-baris berikutnya yang merupakan bagian alamat
                var j = i + 1
                while (j < lines.size && alamatParts.size < 6) {
                    if (j in skipIndices) { j++; continue }

                    val nextLine = lines[j]
                    val nextUpper = normalizeToAscii(nextLine).uppercase().replace("\\s+".toRegex(), "")

                    // Stop jika menemukan token penghenti utama
                    if (nextUpper.startsWith("STATUS") ||
                        nextUpper.startsWith("PEKERJA") ||
                        nextUpper.startsWith("KEWARGA") ||
                        nextUpper.startsWith("BERLAKU") ||
                        nextUpper.startsWith("SEUMUR")) {
                        break
                    }

                    // Skip label-label yang berdiri sendiri (tanpa value)
                    val isStandaloneLabel = nextUpper in listOf(
                        "RTRW", "RT/RW", "KELDES", "KELDESA", "KEL/DESA",
                        "AGAMA", "GOLDARAH", "GOL.DARAH"
                    )
                    if (isStandaloneLabel) { j++; continue }

                    // Skip baris yang jelas bukan alamat
                    if (isTempatTglLahirLine(nextLine) || containsDate(nextLine)) {
                        j++; continue
                    }

                    val value = extractValueAfterColon(nextLine) ?: nextLine

                    // Ambil baris jika:
                    // - Berisi angka yang mirip RT/RW (006/008), nomor rumah (11/22)
                    // - Berisi nama tempat (LAGOA, KOJA, dll)
                    // - Panjang > 2 karakter
                    if (value.length > 1 && !isStopToken(value)) {
                        alamatParts.add(value.trim())
                    }

                    j++
                }

                if (alamatParts.isNotEmpty()) {
                    val fullAlamat = alamatParts.joinToString(" ").trim()
                    Log.d(TAG, "Alamat assembled: $fullAlamat")
                    return cleanAlamat(fullAlamat)
                }
            }
        }

        // Strategy 2: Fallback — cari label "Alamat" lalu ambil baris setelahnya
        for (i in lines.indices) {
            if (i in skipIndices) continue

            val line = lines[i]
            val isAlamatLabel = ALAMAT_VARIANTS.any { variant ->
                line.uppercase().contains(variant.uppercase())
            }

            if (isAlamatLabel) {
                Log.d(TAG, "Found alamat label at line $i: $line")

                // Cari baris alamat setelah label
                for (j in (i + 1) until minOf(i + 10, lines.size)) {
                    if (j in skipIndices) continue
                    val nextLine = lines[j]
                    if (ADDRESS_KEYWORDS.any { nextLine.uppercase().contains(it) } ||
                        RT_RW_REGEX.containsMatchIn(nextLine)) {
                        // Delegasikan ke Strategy 1 dari baris ini
                        val subLines = lines.subList(j, lines.size)
                        val result = assembleAddress(subLines, skipIndices, j)
                        if (result != null) return result
                    }
                }
            }
        }

        return null
    }

    /** Helper: Kumpulkan baris-baris alamat mulai dari startLine */
    private fun assembleAddress(lines: List<String>, skipIndices: Set<Int>, globalOffset: Int): String? {
        val parts = mutableListOf<String>()

        for (i in lines.indices) {
            if ((i + globalOffset) in skipIndices) continue
            val line = lines[i]
            val upper = normalizeToAscii(line).uppercase().replace("\\s+".toRegex(), "")

            // Stop conditions
            if (upper.startsWith("STATUS") || upper.startsWith("PEKERJA") ||
                upper.startsWith("KEWARGA") || upper.startsWith("BERLAKU") ||
                upper.startsWith("SEUMUR")) break

            // Skip standalone labels
            if (upper in listOf("RTRW", "RT/RW", "KELDES", "KELDESA", "KEL/DESA",
                    "AGAMA", "GOLDARAH", "GOL.DARAH")) continue

            if (isTempatTglLahirLine(line) || containsDate(line)) continue

            val value = extractValueAfterColon(line) ?: line
            if (value.length > 1 && !isStopToken(value)) {
                parts.add(value.trim())
            }

            if (parts.size >= 6) break
        }

        return if (parts.isNotEmpty()) cleanAlamat(parts.joinToString(" ")) else null
    }

    // ====== HELPER FUNCTIONS ======

    private fun extractValueAfterColon(line: String): String? {
        val colonIndex = line.indexOfAny(charArrayOf(':', '；', '︰'))
        return if (colonIndex != -1 && colonIndex < line.length - 1) {
            val value = line.substring(colonIndex + 1).trim()
            if (value.isNotBlank()) value else null
        } else {
            null
        }
    }

    private fun findNamaLineIndex(lines: List<String>, nama: String?): Int? {
        if (nama == null) return null

        return lines.indexOfFirst { line ->
            cleanName(line) == nama ||
                    extractValueAfterColon(line)?.let { cleanName(it) == nama } == true
        }.takeIf { it != -1 }
    }

    private fun isPotentialAddress(text: String): Boolean {
        val cleanText = normalizeToAscii(text).uppercase()
        val noSpaces = cleanText.replace("\\s+".toRegex(), "")

        // PRIORITAS: Jika mirip label KTP, ini BUKAN alamat
        if (isLabel(text)) return false

        if (containsNonNameWords(cleanText) || isTempatTglLahirLine(cleanText)) {
            return false
        }

        // Tolak jika mirip label yang sering rusak
        if (KTP_LABEL_PREFIXES.any { prefix -> noSpaces.startsWith(prefix) }) {
            return false
        }

        return ADDRESS_KEYWORDS.any { cleanText.contains(it, ignoreCase = true) } ||
                RT_RW_REGEX.containsMatchIn(cleanText)
    }

    private fun containsNonNameWords(text: String): Boolean {
        val normalized = normalizeToAscii(text).uppercase()
        val words = normalized.split("\\s+".toRegex())
        return words.any { it in NON_NAME_WORDS }
    }

    private fun containsDate(text: String): Boolean {
        // Pattern standar: DD-MM-YYYY
        if (DATE_REGEX.containsMatchIn(text)) return true
        // Pattern parsial: -MM-YYYY atau MM-YYYY (OCR sering kehilangan digit depan)
        if ("\\d{1,2}-\\d{4}".toRegex().containsMatchIn(text)) return true
        // Pattern: DDMMYYYY atau angka 6-8 digit yang mungkin tanggal
        return false
    }

    private fun isTempatTglLahirLine(text: String): Boolean {
        val upperText = normalizeToAscii(text).uppercase()
        return upperText.contains("TEMPAT") ||
                upperText.contains("TERPA") ||
                upperText.contains("TGL") ||
                upperText.contains("LAHIR") ||
                upperText.contains("PRAPAT") || // OCR sering baca PRAPAT dari KTP
                (containsDate(text) && text.length < 50)
    }

    private fun isValidName(str: String): Boolean {
        val cleaned = str.trimStart(':', ' ', '.').trim()
        val normalized = normalizeToAscii(cleaned)
            .uppercase()
            .replace("[^A-Z]".toRegex(), "")

        if (cleaned.length < 3) return false
        if (cleaned.any { it.isDigit() }) return false
        if (!cleaned.any { it.isLetter() }) return false
        if (isLabel(cleaned) || isStopToken(cleaned)) return false
        if (containsDate(cleaned) || isTempatTglLahirLine(cleaned)) return false
        if (containsNonNameWords(normalized)) return false
        if (KTP_LABEL_PREFIXES.any { prefix -> normalized.startsWith(prefix) }) return false

        // Tolak jika hanya 1 kata DAN itu nama wilayah
        val words = cleaned.trim().split("\\s+".toRegex())
        if (words.size == 1 && AREA_NAMES.contains(cleaned.uppercase())) return false

        // Tolak jika mengandung address keyword sebagai kata utuh
        if (ADDRESS_KEYWORDS.any { kw ->
                cleaned.uppercase().split("[^A-Za-z]+".toRegex()).any { it == kw }
            }) return false

        return true
    }

    private fun cleanName(name: String): String {
        return name
            .trimStart(':', ' ', '.', ',', '-')   // Hapus karakter sampah di awal
            .trimEnd('.', ',', ':', '-', ' ')      // Hapus karakter sampah di akhir
            .trim()
            .replace("\\s+".toRegex(), " ")
            .uppercase()
            // Koreksi OCR error umum pada NAMA (bukan digit context)
            .replace('0', 'O')  // nol → O (MUHAMM0D → MUHAMMAD)
            .replace('1', 'I')  // satu → I (RAH1M → RAHIM)
            .replace('5', 'S')  // lima → S (5UPARJO → SUPARJO)
            .replace('8', 'B')  // delapan → B (A8DUL → ABDUL)
            .replace('6', 'G')  // enam → G (AN6GA → ANGGA)
            .replace("\\d".toRegex(), "")  // Hapus sisa digit
            .split(" ")
            // Pertahankan huruf tunggal (inisial nama: S., H., M.)
            .filter { it.isNotEmpty() && (it.length > 1 || it[0].isUpperCase()) }
            .filter { !NON_NAME_WORDS.contains(it) }
            .joinToString(" ")
            .trimStart(':', ' ', '.', ',')  // Bersihkan lagi setelah processing
            .trimEnd('.', ',', ':', ' ')
            .trim()
    }

    private fun cleanAlamat(alamat: String): String {
        return alamat
            .trimStart(':', ' ', '.', ',', '-')   // Hapus karakter sampah di awal
            .trimEnd('.', ',', ':', '-', ' ')      // Hapus karakter sampah di akhir
            .trim()
            .replace("\\s+".toRegex(), " ")
            .uppercase()
            .replace("::", "")       // Hapus double colon
            .replace(". .", " ")     // Hapus dot-space-dot
            .trim()
    }

    private fun isLabel(str: String): Boolean {
        val labels = listOf(
            "NIK", "NAMA", "ALAMAT", "TEMPAT", "TGL", "LAHIR",
            "JENIS", "KELAMIN", "GOL", "DARAH", "AGAMA",
            "STATUS", "PERKAWINAN", "PEKERJAAN", "KEWARGANEGARAAN",
            "BERLAKU", "HINGGA", "PROVINSI", "KABUPATEN", "KOTA",
            "RTRW", "RTRWE", "KELDES", "KEVDES", "KEVDESA", "KELDESA",
            "KECAMATAN", "KECAMATAT", "KECAMATA"
        )

        // Normalize: hapus non-ASCII, hapus spasi/tanda baca
        val normalized = normalizeToAscii(str)
            .uppercase()
            .replace("[^A-Z0-9]".toRegex(), "")

        // Cek exact match dan prefix match dengan label standar
        if (labels.any { label -> normalized.startsWith(label) || normalized == label }) {
            return true
        }

        // Cek prefix match dengan label KTP yang sering rusak
        if (KTP_LABEL_PREFIXES.any { prefix -> normalized.startsWith(prefix) }) {
            return true
        }

        return false
    }

    private fun isStopToken(str: String): Boolean {
        return ADDRESS_STOP_TOKENS.any { token ->
            str.contains(token, ignoreCase = true)
        }
    }

    private fun normalizeNikDigits(s: String): String {
        // Hitung rasio digit dalam string
        val digitCount = s.count { it.isDigit() }
        val letterCount = s.count { it.isLetter() }

        // Jika string dominan huruf (bukan baris NIK), lakukan normalisasi minimal saja
        if (letterCount > digitCount) {
            return s
                .replace('|', '1')
                .replace('!', '1')
        }

        // Jika string dominan digit, bersihkan dan normalize
        val sb = StringBuilder(s.length)
        for (c in s) {
            val replacement = when (c) {
                'O', 'o', 'Q', 'D' -> '0'
                'I', 'l', 'L', '|', '!' -> '1'
                'Z' -> '2'
                'S', 's' -> '5'
                'B' -> '8'
                'd' -> '0' // OCR sering baca 0 sebagai d pada konteks digit
                // Karakter sampah yang sering muncul — hapus (jangan masukkan ke result)
                '*', ')', '(', '[', ']', '{', '}', '#', '@', '~',
                '\\', '/', '?', '<', '>', '+', '=', '^', '`' -> continue
                // Spasi, titik, koma, tanda hubung — hapus juga dalam konteks NIK
                ' ', '.', ',', '-', ':' -> continue
                else -> c
            }
            sb.append(replacement)
        }
        return sb.toString()
    }

    /**
     * Score NIK berdasarkan validitas format Indonesia
     * NIK format: PPKKCC-DDMMYY-XXXX
     * PP = kode provinsi, KK = kode kota/kab, CC = kode kecamatan
     * DD = tanggal lahir (wanita +40), MM = bulan, YY = tahun
     */
    private fun scoreNik(nik: String): Int {
        if (nik.length != 16 || !nik.matches(ONLY_DIGITS)) return -1

        var score = 0

        try {
            val provinsi = nik.substring(0, 2).toIntOrNull() ?: return score

            // Valid province codes untuk Indonesia (11-94)
            val validProvinsi = provinsi in listOf(
                11, 12, 13, 14, 15, 16, 17, 18, 19, 21,
                31, 32, 33, 34, 35, 36,
                51, 52, 53,
                61, 62, 63, 64, 65,
                71, 72, 73, 74, 75, 76,
                81, 82,
                91, 92, 94
            )

            if (validProvinsi) {
                score += 3
                Log.d(TAG, "Valid provinsi code: $provinsi (+3)")
            }

            // Validate tanggal lahir
            val tanggal = nik.substring(6, 8).toIntOrNull() ?: return score
            val bulan = nik.substring(8, 10).toIntOrNull() ?: return score

            // Tanggal: 01-31 untuk pria, 41-71 untuk wanita
            val realDay = if (tanggal in 41..71) tanggal - 40 else tanggal
            if (realDay in 1..31 && bulan in 1..12) {
                score += 2
                Log.d(TAG, "Valid date: day=$realDay month=$bulan (+2)")
            }

            // Validate tahun lahir (00-99)
            val tahun = nik.substring(10, 12).toIntOrNull()
            if (tahun != null && tahun in 0..99) {
                score += 1
                Log.d(TAG, "Valid year format (+1)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error scoring NIK: ${e.message}")
        }

        return score
    }
}

/**
 * Normalize karakter non-ASCII ke ASCII terdekat
 * Mengatasi OCR yang sering menghasilkan karakter aneh seperti Ẽ, İ, Ç, dll
 */
private fun normalizeToAscii(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        val replacement = when (c) {
            'Ẽ', 'È', 'É', 'Ê', 'Ë', 'Ę', 'Ė' -> 'E'
            'ẽ', 'è', 'é', 'ê', 'ë', 'ę', 'ė' -> 'e'
            'À', 'Á', 'Â', 'Ã', 'Ä', 'Å', 'Ă', 'Ą' -> 'A'
            'à', 'á', 'â', 'ã', 'ä', 'å', 'ă', 'ą' -> 'a'
            'İ', 'Ì', 'Í', 'Î', 'Ï', 'Į' -> 'I'
            'ì', 'í', 'î', 'ï', 'ı', 'į' -> 'i'
            'Ò', 'Ó', 'Ô', 'Õ', 'Ö', 'Ø', 'Ő' -> 'O'
            'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ő' -> 'o'
            'Ù', 'Ú', 'Û', 'Ü', 'Ű', 'Ų' -> 'U'
            'ù', 'ú', 'û', 'ü', 'ű', 'ų' -> 'u'
            'Ç', 'Ć', 'Č' -> 'C'
            'ç', 'ć', 'č' -> 'c'
            'Ñ', 'Ń', 'Ň' -> 'N'
            'ñ', 'ń', 'ň' -> 'n'
            'Ś', 'Š', 'Ş' -> 'S'
            'ś', 'š', 'ş' -> 's'
            'Ž', 'Ź', 'Ż' -> 'Z'
            'ž', 'ź', 'ż' -> 'z'
            'Ř' -> 'R'
            'ř' -> 'r'
            'Ť' -> 'T'
            'ť' -> 't'
            'Ď' -> 'D'
            'ď' -> 'd'
            'Ý' -> 'Y'
            'ý' -> 'y'
            'Ł' -> 'L'
            'ł' -> 'l'
            else -> c
        }
        sb.append(replacement)
    }
    return sb.toString()
}