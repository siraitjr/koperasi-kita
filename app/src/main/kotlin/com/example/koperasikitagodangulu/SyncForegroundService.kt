package com.example.koperasikitagodangulu.offline

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.koperasikitagodangulu.MainActivity
import kotlinx.coroutines.*

/**
 * =========================================================================
 * SYNC FOREGROUND SERVICE - AUTO RESTART VERSION
 * =========================================================================
 *
 * PERUBAHAN PENTING:
 * - Service akan RESTART OTOMATIS saat app di-swipe close
 * - Notifikasi akan TETAP ADA selama ada data pending
 * - Sync otomatis saat internet tersedia
 *
 * CARA KERJA:
 * 1. Saat ada data pending, service dimulai dengan notification
 * 2. Jika app di-close, service restart otomatis via AlarmManager
 * 3. Service mendengarkan perubahan koneksi internet
 * 4. Saat online, langsung sync data ke Firebase
 * 5. Setelah semua data sync, service berhenti
 * =========================================================================
 */
class SyncForegroundService : Service() {

    companion object {
        private const val TAG = "SyncForegroundService"
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RESTART_DELAY_MS = 1000L // Restart setelah 1 detik

        /**
         * Start service untuk sync data
         */
        fun startSync(context: Context) {
            try {
                val intent = Intent(context, SyncForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "🚀 Sync service started")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting service: ${e.message}")
            }
        }

        /**
         * Stop service
         */
        fun stopSync(context: Context) {
            try {
                val intent = Intent(context, SyncForegroundService::class.java)
                context.stopService(intent)
                Log.d(TAG, "🛑 Sync service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error stopping service: ${e.message}")
            }
        }

        /**
         * Check apakah perlu start service
         */
        suspend fun startIfNeeded(context: Context) {
            try {
                val syncManager = SyncManager.getInstance(context)
                val pendingCount = syncManager.getPendingCount()

                if (pendingCount > 0) {
                    Log.d(TAG, "📦 Found $pendingCount pending operations, starting service...")
                    startSync(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in startIfNeeded: ${e.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var syncManager: SyncManager
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "📱 Service onCreate")

        syncManager = SyncManager.getInstance(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📱 Service onStartCommand")

        // Start as foreground service
        val notification = createNotification("Menyiapkan sinkronisasi...")
        startForeground(NOTIFICATION_ID, notification)

        // Start sync process
        serviceScope.launch {
            startSyncProcess()
        }

        // START_STICKY: Sistem akan restart service jika di-kill
        return START_STICKY
    }

    private suspend fun startSyncProcess() {
        // Check pending count
        val pendingCount = syncManager.getPendingCount()

        if (pendingCount == 0) {
            Log.d(TAG, "📭 No pending data, stopping service")
            stopSelf()
            return
        }

        Log.d(TAG, "📦 Found $pendingCount pending operations")
        updateNotification("$pendingCount data menunggu sinkronisasi")

        // Check if online
        if (isOnline()) {
            Log.d(TAG, "🌐 Online! Starting sync...")
            performSync()
        } else {
            Log.d(TAG, "📵 Offline, waiting for connection...")
            updateNotification("Menunggu koneksi internet...")
            registerNetworkCallback()
        }
    }

    private suspend fun performSync() {
        try {
            updateNotification("Menyinkronkan data...")

            val result = syncManager.syncAllPending()

            if (result.allSuccess) {
                Log.d(TAG, "✅ All data synced successfully!")
                updateNotification("✅ Sinkronisasi selesai!")

                // Delay sedikit agar user lihat notifikasi sukses
                delay(2000)
                stopSelf()

            } else {
                Log.w(TAG, "⚠️ Partial sync: ${result.success}/${result.total}")

                if (result.failed > 0 && result.success == 0) {
                    // Semua gagal, mungkin offline
                    updateNotification("Gagal sync, menunggu koneksi...")
                    registerNetworkCallback()
                } else {
                    // Sebagian berhasil
                    updateNotification("${result.success} berhasil, ${result.failed} gagal")
                    delay(3000)

                    // Coba lagi yang gagal
                    if (syncManager.getPendingCount() > 0) {
                        delay(5000)
                        performSync()
                    } else {
                        stopSelf()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Sync error: ${e.message}")
            updateNotification("Error: ${e.message}")

            // Retry after delay
            delay(10000)
            if (isOnline()) {
                performSync()
            } else {
                registerNetworkCallback()
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "🌐 Network available!")
                serviceScope.launch {
                    delay(2000) // Wait for stable connection
                    if (isOnline()) {
                        performSync()
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "📵 Network lost")
                serviceScope.launch {
                    updateNotification("Menunggu koneksi internet...")
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            Log.d(TAG, "📡 Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(TAG, "📡 Network callback unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering callback: ${e.message}")
            }
        }
        networkCallback = null
    }

    private fun isOnline(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sinkronisasi Data",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi saat ada data yang perlu disinkronkan"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sinkronisasi Data")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        try {
            val notification = createNotification(message)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating notification: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * =========================================================================
     * CRITICAL: Dipanggil saat user swipe close app dari recent apps
     * =========================================================================
     * Di sini kita JADWALKAN RESTART service agar notifikasi tetap muncul
     * dan sync bisa berjalan saat internet tersedia
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "📱 Task removed (user swipe close)")

        // Cek apakah masih ada pending data
        val hasPendingData = runBlocking {
            try {
                syncManager.getPendingCount() > 0
            } catch (e: Exception) {
                Log.e(TAG, "Error checking pending count: ${e.message}")
                false
            }
        }

        if (hasPendingData) {
            Log.d(TAG, "⚠️ Still have pending data, scheduling restart...")
            scheduleServiceRestart()
        } else {
            Log.d(TAG, "✅ No pending data, no need to restart")
        }
    }

    /**
     * Jadwalkan restart service menggunakan AlarmManager
     * Ini akan membuat service restart dalam 1 detik setelah app di-close
     */
    private fun scheduleServiceRestart() {
        try {
            val restartIntent = Intent(applicationContext, SyncForegroundService::class.java).apply {
                setPackage(packageName)
            }

            val pendingIntent = PendingIntent.getService(
                applicationContext,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Gunakan setExactAndAllowWhileIdle untuk Android 6+ (Doze mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                    pendingIntent
                )
            }

            Log.d(TAG, "⏰ Service restart scheduled in ${RESTART_DELAY_MS}ms")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling restart: ${e.message}")
            // Fallback: delegate ke WorkManager
            try {
                SyncWorker.triggerImmediateSync(applicationContext)
                NetworkChangeWorker.setup(applicationContext)
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Fallback to WorkManager also failed: ${e2.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "📱 Service onDestroy")

        unregisterNetworkCallback()
        serviceScope.cancel()
    }
}