package com.example.koperasikitagodangulu.utils

import java.text.NumberFormat
import java.text.DecimalFormat
import java.util.Locale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color

// Fungsi existing untuk Int
fun formatRupiah(nominal: Int): String {
    val formatter: NumberFormat = NumberFormat.getNumberInstance(Locale("in", "ID"))
    return formatter.format(nominal).replace(",", ".")
}

// ✅ TAMBAHKAN: Overload function untuk Long
fun formatRupiah(nominal: Long): String {
    val formatter: NumberFormat = NumberFormat.getNumberInstance(Locale("in", "ID"))
    return formatter.format(nominal).replace(",", ".")
}

// Untuk format input field (misalnya saat edit/tambah nominal)
fun formatRupiahInput(input: String): String {
    return input.filter { it.isDigit() }
        .toLongOrNull()
        ?.let {
            val formatter: NumberFormat = DecimalFormat("#,###")
            formatter.format(it).replace(",", ".")
        } ?: ""
}

fun String.capitalizeWords(): String {
    return this.split(" ").joinToString(" ") { word ->
        word.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }
}

// Untuk TextField dengan pemisah ribuan saat mengetik
val ThousandSeparatorTransformation = VisualTransformation { text ->
    val original = text.text
    val formatted = formatRupiahInput(original)
    TransformedText(
        text = AnnotatedString(formatted),
        offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = formatted.length
            override fun transformedToOriginal(offset: Int): Int = original.length
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