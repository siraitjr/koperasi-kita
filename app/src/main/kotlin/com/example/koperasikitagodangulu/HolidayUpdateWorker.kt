package com.example.koperasikitagodangulu.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.koperasikitagodangulu.services.CicilanRecalculationService
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.example.koperasikitagodangulu.SimulasiCicilan
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Calendar
import com.example.koperasikitagodangulu.utils.HolidayUtils

class HolidayUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) { // ⭐⭐ GANTI ke CoroutineWorker ⭐⭐

    private val recalculationService = CicilanRecalculationService()
    private val database by lazy {
        Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference
    }

    override suspend fun doWork(): Result { // ⭐⭐ GANTI ke suspend function ⭐⭐
        return try {
            Log.d("HolidayUpdateWorker", "Memulai update jadwal cicilan...")

            val adminUid = Firebase.auth.currentUser?.uid
            if (adminUid.isNullOrBlank()) {
                Log.w("HolidayUpdateWorker", "Admin UID tidak ditemukan")
                return Result.success()
            }

            val success = updateAllPelangganCicilan(adminUid)

            if (success) {
                Log.d("HolidayUpdateWorker", "✅ Update semua cicilan berhasil")
                // ⭐⭐ KIRIM NOTIFIKASI/BROADCAST JIKA PERLU ⭐⭐
                sendUpdateNotification(applicationContext)
                Result.success()
            } else {
                Log.w("HolidayUpdateWorker", "⚠️ Beberapa update gagal")
                Result.retry()
            }

        } catch (e: Exception) {
            Log.e("HolidayUpdateWorker", "❌ Error dalam doWork: ${e.message}", e)
            Result.retry()
        }
    }

    private fun sendUpdateNotification(context: Context) {
        // Anda bisa tambahkan notifikasi di sini
        Toast.makeText(context, "Update jadwal cicilan selesai", Toast.LENGTH_SHORT).show()
    }

    // ⭐⭐ FUNGSI UNTUK UPDATE SEMUA PELANGGAN ⭐⭐
    private suspend fun updateAllPelangganCicilan(adminUid: String): Boolean {
        return try {
            val ref = database.child("pelanggan").child(adminUid)
            val snapshot = ref.get().await()

            var successCount = 0
            var totalCount = 0

            for (child in snapshot.children) {
                totalCount++
                val pelanggan = child.getValue<com.example.koperasikitagodangulu.Pelanggan>()
                if (pelanggan != null &&
                    pelanggan.namaPanggilan.isNotBlank() &&
                    pelanggan.status.isNotBlank() &&
                    pelanggan.status.lowercase() == "aktif") {

                    val currentCicilanCount = pelanggan.hasilSimulasiCicilan?.size ?: 0
                    val shouldHaveCount = pelanggan.tenor

                    if (currentCicilanCount != shouldHaveCount) {
                        // ⭐⭐ GENERATE ULANG DENGAN TENOR YANG BENAR ⭐⭐
                        val repairedCicilan = generateInitialCicilan(
                            pelanggan.tanggalPengajuan,
                            pelanggan.tenor,
                            pelanggan.totalPelunasan
                        )

                        try {
                            child.ref.child("hasilSimulasiCicilan").setValue(repairedCicilan).await()
                            successCount++
                            Log.d("HolidayUpdateWorker", "✅ Berhasil repair cicilan untuk: ${pelanggan.namaPanggilan}")
                        } catch (e: Exception) {
                            Log.e("HolidayUpdateWorker", "❌ Gagal repair cicilan: ${e.message}")
                        }
                    } else {
                        // Proses normal untuk data yang sudah benar
                        val updatedCicilan = recalculationService.recalculateCicilan(pelanggan.hasilSimulasiCicilan)

                        try {
                            child.ref.child("hasilSimulasiCicilan").setValue(updatedCicilan).await()
                            successCount++
                            Log.d("HolidayUpdateWorker", "✅ Berhasil update cicilan untuk: ${pelanggan.namaPanggilan}")
                        } catch (e: Exception) {
                            Log.e("HolidayUpdateWorker", "❌ Gagal update cicilan: ${e.message}")
                        }
                    }
                }
            }

            Log.d("HolidayUpdateWorker", "Result: $successCount/$totalCount pelanggan berhasil diupdate")
            successCount > 0

        } catch (e: Exception) {
            Log.e("HolidayUpdateWorker", "Error: ${e.message}")
            false
        }
    }

    private fun generateInitialCicilan(tanggalPengajuan: String, tenor: Int, totalPelunasan: Int): List<SimulasiCicilan> {
        val cicilanList = mutableListOf<SimulasiCicilan>()
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
        val calendar = HolidayUtils.parseTanggal(tanggalPengajuan)
        val jumlahCicilanPerHari = totalPelunasan / tenor

        var currentCalendar = calendar.clone() as Calendar
        var cicilanCount = 0

        while (cicilanCount < tenor) {
            if (!HolidayUtils.isHariKerja(currentCalendar)) {
                currentCalendar = HolidayUtils.getNextBusinessDay(currentCalendar)
                continue
            }

            val tanggalCicilan = dateFormat.format(currentCalendar.time)

            val cicilan = SimulasiCicilan(
                tanggal = tanggalCicilan,
                jumlah = jumlahCicilanPerHari,
                isHariKerja = true,
                isCompleted = false,
                version = 1,
                lastUpdated = ""
            )

            cicilanList.add(cicilan)
            cicilanCount++
            currentCalendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return cicilanList
    }
}