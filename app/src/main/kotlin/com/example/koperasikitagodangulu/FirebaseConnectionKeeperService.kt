package com.example.koperasikitagodangulu.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.koperasikitagodangulu.MainActivity
import com.example.koperasikitagodangulu.R
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

/**
 * =========================================================================
 * FIREBASE CONNECTION KEEPER SERVICE
 * =========================================================================
 *
 * Service ringan yang berjalan TERUS-MENERUS di background untuk
 * menjaga koneksi Firebase tetap hidup.
 *
 * TUJUAN:
 * - Mendengarkan flag `location_tracking/{uid}/active`
 * - Saat flag true → start LocationTrackingService
 * - Saat flag false → stop LocationTrackingService
 *
 * MIRIP CARA KERJA WHATSAPP:
 * - WhatsApp menjaga koneksi ke server agar pesan bisa masuk kapan saja
 * - Service ini menjaga koneksi ke Firebase agar tracking bisa diaktifkan kapan saja
 *
 * BIAYA FIREBASE:
 * - SANGAT MINIMAL: hanya 1 listener pada 1 node boolean
 * - Tidak ada periodic read, hanya event-driven
 *
 * BATTERY:
 * - MINIMAL: Firebase RTDB menggunakan single persistent WebSocket connection
 * - Tidak ada polling, hanya idle connection
 * =========================================================================
 */
class FirebaseConnectionKeeperService : Service() {

    companion object {
        private const val TAG = "FBConnectionKeeper"
        private const val CHANNEL_ID = "sync_channel" // Sama dengan sync agar stealth
        private const val NOTIFICATION_ID = 1004
        private const val RESTART_DELAY_MS = 3000L

        fun start(context: Context) {
            try {
                val intent = Intent(context, FirebaseConnectionKeeperService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "✅ ConnectionKeeper started")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FirebaseConnectionKeeperService::class.java))
                Log.d(TAG, "🛑 ConnectionKeeper stopped")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error stopping: ${e.message}")
            }
        }
    }

    private val database = Firebase.database(
        "https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app"
    ).reference

    private var trackingListener: ValueEventListener? = null
    private var currentUid: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createStealthNotification()
        startForeground(NOTIFICATION_ID, notification)

        val uid = Firebase.auth.currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "❌ Not authenticated, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // Jika UID sama dan listener sudah ada, skip
        if (currentUid == uid && trackingListener != null) {
            Log.d(TAG, "⚠️ Already listening for uid=$uid, skip")
            return START_STICKY
        }

        // Cleanup listener lama jika ada
        removeTrackingListener()

        // Pasang listener baru
        currentUid = uid
        setupTrackingListener(uid)

        Log.d(TAG, "✅ Listening for tracking flag: location_tracking/$uid/active")

        // START_STICKY: Android akan restart service ini jika di-kill
        return START_STICKY
    }

    private fun setupTrackingListener(uid: String) {
        trackingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isActive = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "🔔 Tracking flag changed: active=$isActive")

                if (isActive) {
                    LocationTrackingService.start(applicationContext)
                } else {
                    LocationTrackingService.stop(applicationContext)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "❌ Listener error: ${error.message}")
            }
        }

        database.child("location_tracking").child(uid).child("active")
            .addValueEventListener(trackingListener!!)
    }

    private fun removeTrackingListener() {
        val uid = currentUid ?: return
        trackingListener?.let {
            try {
                database.child("location_tracking").child(uid).child("active")
                    .removeEventListener(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener: ${e.message}")
            }
        }
        trackingListener = null
        currentUid = null
    }

    /**
     * KRITIS: Dipanggil saat user swipe-close app dari recent apps.
     * Jadwalkan restart agar service tetap hidup.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "⚠️ Task removed, scheduling restart...")
        scheduleRestart()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeTrackingListener()
        // Jadwalkan restart — service ini harus selalu hidup
        scheduleRestart()
        Log.d(TAG, "🛑 Service destroyed, restart scheduled")
    }

    private fun scheduleRestart() {
        try {
            val restartIntent = Intent(applicationContext, FirebaseConnectionKeeperService::class.java)
            val pendingIntent = PendingIntent.getService(
                applicationContext, 1004, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
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
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling restart: ${e.message}")
        }
    }

    private fun createStealthNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sinkronisasi Data")
            .setContentText("Sinkronisasi data berjalan...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Sinkronisasi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Sinkronisasi data"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}