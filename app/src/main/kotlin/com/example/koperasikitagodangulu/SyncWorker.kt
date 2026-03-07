package com.example.koperasikitagodangulu.offline

import android.content.Context
import android.util.Log
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

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🔄 SyncWorker starting...")

                val syncManager = SyncManager.getInstance(applicationContext)
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