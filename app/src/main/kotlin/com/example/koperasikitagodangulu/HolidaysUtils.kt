package com.example.koperasikitagodangulu.utils

import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

object HolidayUtils {

    // Fungsi untuk mengecek apakah suatu tanggal adalah hari Minggu
    fun isMinggu(calendar: Calendar): Boolean {
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
    }

    // Fungsi untuk mengecek apakah suatu tanggal adalah hari libur nasional
    fun isTanggalMerah(calendar: Calendar): Boolean {
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val year = calendar.get(Calendar.YEAR)

        val liburNasional = listOf(
            Pair(1, 1),
            Pair(16, 1),
            Pair(17, 2),
            Pair(19, 3),
            Pair(21, 3),
            Pair(3, 4),
            Pair(1, 5),
            Pair(14, 5),
            Pair(16, 6),
            Pair(17, 8),
            Pair(25, 8),
            Pair(25, 12),
        )

        return liburNasional.any { it.first == day && it.second == month }
    }

    // Fungsi untuk mengecek apakah suatu tanggal adalah hari kerja
    fun isHariKerja(calendar: Calendar): Boolean {
        return !isMinggu(calendar) && !isTanggalMerah(calendar)
    }

    // Fungsi untuk mendapatkan hari kerja berikutnya
    fun getNextBusinessDay(startDate: Calendar): Calendar {
        val nextDate = startDate.clone() as Calendar
        do {
            nextDate.add(Calendar.DAY_OF_YEAR, 1)
        } while (!isHariKerja(nextDate))
        return nextDate
    }

    // Helper untuk parsing tanggal
    fun parseTanggal(tanggalString: String): Calendar {
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
        val date = dateFormat.parse(tanggalString)
        val calendar = Calendar.getInstance()
        date?.let { calendar.time = it }
        return calendar
    }
}