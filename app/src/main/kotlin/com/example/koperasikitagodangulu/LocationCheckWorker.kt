package com.example.koperasikitagodangulu.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.koperasikitagodangulu.R
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import androidx.work.OutOfQuotaPolicy

/**
 * =========================================================================
 * LOCATION CHECK WORKER (FIXED for Android 12+)
 * =========================================================================
 *
 * WorkManager periodic worker yang berjalan setiap 15 menit.
 * Mengecek apakah Pengawas mengaktifkan tracking.
 * Jika aktif → promote ke foreground → start LocationTrackingService.
 *
 * FIX: Pada Android 12+, start foreground service dari background DILARANG.
 * Solusi: Panggil setForeground() untuk promote worker ke foreground
 * SEBELUM memanggil LocationTrackingService.start().
 * =========================================================================
 */
class LocationCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LocationCheckWorker"
        private const val UNIQUE_WORK_NAME = "location_check_periodic"
        private const val UNIQUE_WORK_IMMEDIATE = "location_check_immediate"
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID_WORKER = 1003

        /**
         * Schedule periodic check setiap 15 menit
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<LocationCheckWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "✅ Location check worker scheduled (every 15 min)")
        }

        /**
         * Schedule immediate one-time check (dipanggil saat app start)
         * Ini memastikan tracking dicek SEGERA, tidak tunggu 15 menit
         */
        fun scheduleImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<LocationCheckWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                request
            )

            Log.d(TAG, "✅ Immediate EXPEDITED location check scheduled")
        }

        /**
         * Cancel worker (saat logout atau role = PENGAWAS)
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_IMMEDIATE)
            Log.d(TAG, "🛑 Location check worker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val uid = Firebase.auth.currentUser?.uid
            if (uid == null) {
                Log.d(TAG, "⚠️ Not authenticated, skip")
                return Result.success()
            }

            val database = Firebase.database(
                "https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app"
            ).reference

            // Cek flag tracking di Firebase
            val snapshot = database
                .child("location_tracking")
                .child(uid)
                .child("active")
                .get()
                .await()

            val isActive = snapshot.getValue(Boolean::class.java) ?: false

            Log.d(TAG, "🔍 Tracking flag check: active=$isActive for uid=$uid")

            if (isActive) {
                // ✅ FIX: Promote worker ke foreground SEBELUM start service
                // Ini WAJIB pada Android 12+ agar boleh start foreground service
                try {
                    setForeground(createForegroundInfo())
                    Log.d(TAG, "✅ Worker promoted to foreground")
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ setForeground failed: ${e.message}")
                }

                // Sekarang boleh start foreground service
                LocationTrackingService.start(applicationContext)
                // Pastikan realtime listener juga aktif
                LocationTrackingMonitor.startMonitoring(applicationContext)
            } else {
                // ✅ TAMBAHAN: Jika flag false, pastikan service juga berhenti
                LocationTrackingService.stop(applicationContext)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Worker error: ${e.message}")
            Result.retry()
        }
    }

    /**
     * WAJIB: Dibutuhkan oleh setForeground() untuk menampilkan notifikasi
     * saat worker dipromosikan ke foreground.
     * Notifikasi stealth: tampil sebagai "Sinkronisasi Data"
     */
    private fun createForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sinkronisasi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Sinkronisasi data"
                setShowBadge(false)
            }
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Sinkronisasi Data")
            .setContentText("Sinkronisasi data berjalan...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID_WORKER,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID_WORKER, notification)
        }
    }

    /**
     * WAJIB untuk setExpedited() pada Android 12+
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo()
    }
}