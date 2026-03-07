package com.example.koperasikitagodangulu.services

import com.example.koperasikitagodangulu.SimulasiCicilan
import com.example.koperasikitagodangulu.utils.HolidayUtils
import java.text.SimpleDateFormat
import java.util.*

class CicilanRecalculationService {

    fun recalculateCicilan(existingCicilan: List<SimulasiCicilan>): List<SimulasiCicilan> {
        val updatedCicilan = mutableListOf<SimulasiCicilan>()
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        // Pisahkan cicilan yang sudah dibayar dan belum
        val completedCicilan = existingCicilan.filter { it.isCompleted }
        val pendingCicilan = existingCicilan.filter { !it.isCompleted }

        // ⭐⭐ PERTAHANKAN CICILAN YANG SUDAH DIBAYAR ⭐⭐
        updatedCicilan.addAll(completedCicilan)

        // ⭐⭐ GENERATE ULANG CICILAN YANG BELUM DIBAYAR DENGAN TENOR YANG TEPAT ⭐⭐
        if (pendingCicilan.isNotEmpty()) {
            val firstPending = pendingCicilan.first()
            val calendar = HolidayUtils.parseTanggal(firstPending.tanggal)
            val jumlahCicilanPerHari = firstPending.jumlah

            // ⭐⭐ GUNAKAN JUMLAH PENDING CICILAN YANG SEHARUSNYA ⭐⭐
            val sisaTenor = pendingCicilan.size
            var currentCalendar = calendar.clone() as Calendar
            var cicilanCount = 0

            while (cicilanCount < sisaTenor) {
                if (!HolidayUtils.isHariKerja(currentCalendar)) {
                    currentCalendar = HolidayUtils.getNextBusinessDay(currentCalendar)
                    continue
                }

                val tanggalCicilan = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                    .format(currentCalendar.time)

                val updatedCicilanItem = SimulasiCicilan(
                    tanggal = tanggalCicilan,
                    jumlah = jumlahCicilanPerHari,
                    isHariKerja = true,
                    isCompleted = false,
                    // ✅ PERBAIKAN: Batasi version maksimal
                    version = minOf(firstPending.version + 1, 10),
                    lastUpdated = currentTime
                )

                updatedCicilan.add(updatedCicilanItem)
                cicilanCount++
                currentCalendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        return updatedCicilan
    }
}