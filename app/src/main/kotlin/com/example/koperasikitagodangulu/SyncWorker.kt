package com.example.koperasikitagodangulu.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * =========================================================================
 * SYNC WORKER
 * =========================================================================
 * Worker yang berjalan di background untuk sync data ke Firebase
 *
 * KEUNGGULAN WorkManager:
 * - Berjalan meskipun aplikasi tidak dibuka
 * - Berjalan meskipun HP di-restart
 * - Cerdas menunggu koneksi internet tersedia
 * - Retry otomatis jika gagal
 * - Battery-efficient
 * =========================================================================
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME_PERIODIC = "firebase_sync_periodic"
        const val WORK_NAME_IMMEDIATE = "firebase_sync_immediate"
        // Kanal yang sama dengan SyncForegroundService, supaya notifikasinya
        // tidak muncul sebagai dua kanal berbeda di setelan pengguna.
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID_SYNC = 4321

        /**
         * Schedule periodic sync setiap 15 menit
         * Hanya berjalan saat ada koneksi internet
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // Hanya saat online
                .build()

            val periodicWork = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES, // Interval minimal 15 menit
                5, TimeUnit.MINUTES   // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .addTag("firebase_sync")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME_PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP, // Jangan replace jika sudah ada
                    periodicWork
                )

            Log.d(TAG, "✅ Periodic sync scheduled (every 15 min)")
        }

        /**
         * Trigger sync segera
         * Berguna saat koneksi baru tersedia atau setelah input data
         */
        fun triggerImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediateWork = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                // Log lama sudah menulis "(EXPEDITED)" padahal setExpedited()
                // tidak pernah dipanggil. Sekarang benar-benar dipanggil.
                //
                // RUN_AS_NON_EXPEDITED_WORK_REQUEST: bila kuota expedited
                // perangkat habis, pekerjaan TETAP berjalan sebagai pekerjaan
                // biasa alih-alih ditolak. Untuk antrean berisi pembayaran,
                // "terlambat" jauh lebih baik daripada "tidak pernah".
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    30, TimeUnit.SECONDS
                )
                .addTag("firebase_sync_immediate")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_IMMEDIATE,
                    ExistingWorkPolicy.REPLACE,
                    immediateWork
                )

            Log.d(TAG, "🚀 Immediate sync triggered (EXPEDITED)")
        }

        /**
         * Cancel semua sync work
         */
        fun cancelAllSync(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("firebase_sync")
            WorkManager.getInstance(context).cancelAllWorkByTag("firebase_sync_immediate")
            Log.d(TAG, "❌ All sync work cancelled")
        }
    }

    /**
     * WAJIB ada begitu `setExpedited()` dipakai.
     *
     * Di Android 11 ke bawah, pekerjaan expedited dijalankan sebagai layanan
     * latar depan; bila worker tidak menyediakan ForegroundInfo, WorkManager
     * melempar IllegalStateException dan pekerjaannya gagal — bukan tertunda.
     * Karena tujuan seluruh perubahan ini justru agar antrean TIDAK berhenti,
     * kelalaian di sini akan membalik hasilnya.
     *
     * Kanal notifikasi dibuat di sini juga: kanal milik SyncForegroundService
     * baru ada bila service itu pernah dijalankan, sedangkan worker bisa
     * dibangunkan sistem lebih dulu di proses yang baru.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Sinkronisasi Data",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Menyinkronkan data")
            .setContentText("Mengirim data yang tertunda ke server")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 mewajibkan tipe layanan disebutkan. `dataSync` sudah
            // dideklarasikan di manifest untuk SystemForegroundService, dan
            // izin FOREGROUND_SERVICE_DATA_SYNC sudah diminta (baris 22).
            ForegroundInfo(NOTIFICATION_ID_SYNC, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID_SYNC, notif)
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🔄 SyncWorker starting...")

                val syncManager = SyncManager.getInstance(applicationContext)

                // Kembalikan op DITOLAK ke antrean SEBELUM menghitung.
                // `getPendingCount()` menghitung PENDING/FAILED/SYNCING dan
                // TIDAK menghitung REJECTED — jadi bila seluruh antrean
                // berstatus REJECTED, worker akan pulang lebih awal dengan
                // "tidak ada yang perlu disinkronkan" dan operasi itu tidak
                // pernah pulih selama aplikasi tidak dibuka. Justru keadaan
                // aplikasi-tertutup inilah yang paling butuh pemulihan
                // otomatis. `picuSync=false`: worker menyinkronkan sendiri di
                // bawah, dan menyalakan foreground service dari latar dilarang
                // sejak Android 12.
                val dipulihkan = try {
                    syncManager.requeueRejectedOperations(picuSync = false)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Gagal mengembalikan op REJECTED: ${e.message}"); 0
                }
                if (dipulihkan > 0) Log.w(TAG, "♻️ $dipulihkan op REJECTED dikembalikan ke antrean")

                val pendingCount = syncManager.getPendingCount()

                if (pendingCount == 0) {
                    Log.d(TAG, "📭 No pending operations")
                    return@withContext Result.success()
                }

                Log.d(TAG, "📦 Found $pendingCount pending operations")

                val result = syncManager.syncAllPending()

                // Set output data untuk debugging
                val outputData = workDataOf(
                    "total" to result.total,
                    "success" to result.success,
                    "failed" to result.failed
                )

                if (result.allSuccess) {
                    Log.d(TAG, "✅ All ${result.total} operations synced successfully")
                    Result.success(outputData)
                } else if (result.failed > 0 && result.success == 0) {
                    Log.e(TAG, "❌ All operations failed")
                    Result.retry() // Retry nanti
                } else {
                    Log.w(TAG, "⚠️ Partial sync: ${result.success}/${result.total}")
                    Result.success(outputData) // Sebagian berhasil, anggap success
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ SyncWorker error: ${e.message}", e)
                Result.retry()
            }
        }
    }
}

/**
 * =========================================================================
 * NETWORK CHANGE WORKER
 * =========================================================================
 * Worker yang trigger sync saat koneksi internet berubah
 * =========================================================================
 */
class NetworkChangeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "NetworkChangeWorker"
        const val WORK_NAME = "network_change_sync"

        /**
         * Setup listener untuk network change
         * Akan trigger sync saat koneksi tersedia
         */
        fun setup(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val networkWork = OneTimeWorkRequestBuilder<NetworkChangeWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    networkWork
                )

            Log.d(TAG, "✅ Network change listener setup")
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "📶 Network available! Triggering sync...")

            // Trigger immediate sync
            SyncWorker.triggerImmediateSync(applicationContext)

            // Re-setup listener untuk network change berikutnya
//            setup(applicationContext)

            Result.success()
        }
    }
}