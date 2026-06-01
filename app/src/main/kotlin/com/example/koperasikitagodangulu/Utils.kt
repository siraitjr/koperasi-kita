package com.example.koperasikitagodangulu.utils

import java.text.NumberFormat
import java.text.DecimalFormat
import java.util.Locale
// EXPLICIT: file ini saat ini tidak import androidx.compose.runtime.*, jadi
// ThreadLocal resolve ke java.lang.ThreadLocal lewat auto-import. Tetap
// di-import eksplisit sebagai jaring pengaman: jika di masa depan ada yang
// menambahkan `import androidx.compose.runtime.*`, Compose's multiplatform
// ThreadLocal (internal, beda API — tidak punya withInitial) tidak akan
// menutupi yang dari java.lang. (Sama dengan pola di TambahSubPembayaranScreen.kt.)
import java.lang.ThreadLocal
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color

// ============================================================================
// HOISTED FORMATTERS — dialokasikan SEKALI per thread, bukan per keystroke.
//
// NumberFormat / DecimalFormat secara dokumentasi tidak thread-safe; pakai
// ThreadLocal agar satu instance per thread → safe untuk dipanggil dari Main
// (UI), Default (recomposition formatter), atau IO sekaligus tanpa lock.
//
// Sebelumnya: `val formatter = NumberFormat.getNumberInstance(...)` /
// `val formatter = DecimalFormat("#,###")` dipanggil di dalam body fungsi
// → alokasi per call. Untuk TextField dengan ThousandSeparatorTransformation,
// itu = alokasi per keystroke → kontribusi ke jank UI offline yang admin
// lapangan keluhkan ("type 20.000 jadi 200.000" karena InputDispatcher
// buffer flush). Hoisting eliminate per-keystroke alokasi.
// ============================================================================
private val NUMBER_FORMAT_ID: ThreadLocal<NumberFormat> =
    ThreadLocal.withInitial { NumberFormat.getNumberInstance(Locale("in", "ID")) }

private val DECIMAL_THOUSAND_FORMAT: ThreadLocal<DecimalFormat> =
    ThreadLocal.withInitial { DecimalFormat("#,###") }

// Fungsi existing untuk Int
fun formatRupiah(nominal: Int): String {
    return NUMBER_FORMAT_ID.get().format(nominal).replace(",", ".")
}

// ✅ TAMBAHKAN: Overload function untuk Long
fun formatRupiah(nominal: Long): String {
    return NUMBER_FORMAT_ID.get().format(nominal).replace(",", ".")
}

// Untuk format input field (misalnya saat edit/tambah nominal)
fun formatRupiahInput(input: String): String {
    return input.filter { it.isDigit() }
        .toLongOrNull()
        ?.let { DECIMAL_THOUSAND_FORMAT.get().format(it).replace(",", ".") } ?: ""
}

fun String.capitalizeWords(): String {
    return this.split(" ").joinToString(" ") { word ->
        word.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }
}

// ============================================================================
// THOUSAND SEPARATOR TRANSFORMATION — VisualTransformation untuk amount TextField.
//
// FIX: OffsetMapping sebelumnya KELIRU — `originalToTransformed` selalu balas
// `formatted.length` dan `transformedToOriginal` selalu balas `original.length`.
// Itu artinya cursor TextField kehilangan posisi per keystroke: tanpa peduli
// dimana user click/edit, cursor lompat ke akhir. Saat UI thread sibuk &
// InputDispatcher flush keystroke buffer, cursor lompat-lompat + digit
// re-flow ke kolom yang salah → 20.000 jadi 200.000.
//
// SEKARANG: mapping akurat 1-to-1.
//   originalToTransformed(o): offset di "20000" → offset di "20.000".
//     Setiap titik yang berada SEBELUM digit ke-o ditambahkan ke offset.
//     Titik ada di boundary i (1..n) bila (n - i) > 0 dan (n - i) % 3 == 0.
//   transformedToOriginal(o): offset di "20.000" → offset di "20000".
//     Hitung jumlah digit dari index 0 sampai o-1 — non-digit (titik) di-skip.
//
// Semua hasil di-coerceIn untuk safety bila offset di luar range (mis. saat
// transformasi flicker karena recomposition di tengah pengetikan cepat).
// ============================================================================
val ThousandSeparatorTransformation = VisualTransformation { text ->
    val original = text.text
    val formatted = formatRupiahInput(original)
    val originalLen = original.length
    val formattedLen = formatted.length
    TransformedText(
        text = AnnotatedString(formatted),
        offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val safeOffset = offset.coerceIn(0, originalLen)
                if (safeOffset == 0) return 0
                var dotsBefore = 0
                // Hitung berapa titik berada sebelum digit ke-safeOffset.
                // Titik berada pada boundary i (artinya antara digit ke-(i-1)
                // dan ke-i) bila sisa digit di kanan (originalLen - i) > 0
                // & habis dibagi 3.
                for (i in 1..safeOffset) {
                    if ((originalLen - i) > 0 && (originalLen - i) % 3 == 0) {
                        dotsBefore++
                    }
                }
                return (safeOffset + dotsBefore).coerceIn(0, formattedLen)
            }

            override fun transformedToOriginal(offset: Int): Int {
                val safeOffset = offset.coerceIn(0, formattedLen)
                if (safeOffset == 0) return 0
                var digitCount = 0
                for (i in 0 until safeOffset) {
                    if (formatted[i].isDigit()) digitCount++
                }
                return digitCount.coerceIn(0, originalLen)
            }
        }
    )
}


fun surfaceColor(isDark: Boolean): Color =
    if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)

fun backgroundColor(isDark: Boolean): Color {
    return if (isDark) {
        Color.Black
    } else {
        Color.White
    }
}

fun textColor(isDark: Boolean): Color {
    return if (isDark) {
        Color.White
    } else {
        Color.Black
    }
}