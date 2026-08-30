package com.example.koperasikitagodangulu.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.koperasikitagodangulu.R
import com.example.koperasikitagodangulu.SesiAktif
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * =========================================================================
 * NOTIFICATION HELPER
 * =========================================================================
 *
 * Helper class untuk:
 * 1. Inisialisasi FCM dan notification channels
 * 2. Request permission untuk Android 13+
 * 3. Simpan FCM token ke database
 * 4. Utility functions untuk notifikasi
 * =========================================================================
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"

    /**
     * Inisialisasi notifikasi - panggil di Application atau MainActivity
     */
    fun initialize(context: Context) {
        Log.d(TAG, "🔧 Initializing notifications...")

        // Buat notification channels
        createNotificationChannels(context)

        // Dapatkan dan simpan FCM token
        fetchAndSaveToken()
    }

    /**
     * Buat notification channels (wajib untuk Android 8+)
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel untuk pengajuan baru (HIGH importance = popup)
            val pengajuanChannel = NotificationChannel(
                MyFirebaseMessagingService.CHANNEL_PENGAJUAN,
                "Pengajuan Pinjaman",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi untuk pengajuan pinjaman baru"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }

            // Channel untuk approval/rejection
            val approvalChannel = NotificationChannel(
                MyFirebaseMessagingService.CHANNEL_APPROVAL,
                "Status Approval",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi untuk status persetujuan pinjaman"
                enableVibration(true)
                setShowBadge(true)
            }

            // Channel untuk notifikasi umum
            val generalChannel = NotificationChannel(
                MyFirebaseMessagingService.CHANNEL_GENERAL,
                "Notifikasi Umum",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifikasi umum aplikasi"
            }

            // ✅ TAMBAH CHANNEL BARU INI
            val serahTerimaChannel = NotificationChannel(
                MyFirebaseMessagingService.CHANNEL_SERAH_TERIMA,
                "Bukti Serah Terima",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi bukti serah terima uang dari admin lapangan"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannels(
                listOf(pengajuanChannel, approvalChannel, generalChannel, serahTerimaChannel)
            )

            Log.d(TAG, "✅ Notification channels created")
        }
    }

    /**
     * Dapatkan FCM token dan simpan ke database
     */
    fun fetchAndSaveToken(retry: Int = 1) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Log.d(TAG, "🔑 FCM Token: $token")
                saveTokenToDatabase(token)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to get FCM token: ${e.message}")
                // ✅ Fix 3C: deleteToken() saat logout sebelumnya bisa masih in-flight,
                // membuat getToken() transient-fail. Retry sekali setelah jeda agar
                // user yang baru login tetap dapat token segar (cegah notifikasi mati).
                if (retry > 0) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "🔁 Retry fetchAndSaveToken (sisa: ${retry - 1})")
                        fetchAndSaveToken(retry - 1)
                    }, 3000)
                }
            }
    }

    /**
     * Simpan FCM token ke Firebase Database
     */
    fun saveTokenToDatabase(token: String) {
        val currentUser = SesiAktif.penggunaAktif()
        if (currentUser != null) {
            val uid = currentUser.uid
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference

            val tokenData = mapOf(
                "token" to token,
                "platform" to "android",
                "updatedAt" to System.currentTimeMillis()
            )

            database.child("fcm_tokens").child(uid).setValue(tokenData)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ FCM token saved for user: $uid")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to save FCM token: ${e.message}")
                }
        } else {
            Log.w(TAG, "⚠️ User not logged in, token not saved")
        }
    }

    /**
     * Hapus token saat logout.
     *
     * ✅ Fix 3A: SUSPEND + AWAIT. Sebelumnya removeValue() & deleteToken()
     * di-fire-and-forget, sehingga saat user berikutnya login, getToken()
     * bisa resolve SEBELUM deleteToken() selesai → mengembalikan token lama
     * yang segera di-invalidate FCM → notifikasi mati (terutama saat
     * switch akun / takeover). Dengan await, urutan dijamin: token lama
     * benar-benar dihapus dulu sebelum login berikutnya fetch token baru.
     */
    suspend fun clearTokenOnLogout() {
        val currentUser = SesiAktif.penggunaAktif()
        if (currentUser != null) {
            val uid = currentUser.uid
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference
            try {
                database.child("fcm_tokens").child(uid).removeValue().await()
                Log.d(TAG, "✅ FCM token cleared for user: $uid")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Gagal hapus token DB untuk $uid: ${e.message}")
            }
        }

        // Juga hapus instance ID untuk generate token baru saat login berikutnya.
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
            Log.d(TAG, "✅ FCM token deleted (instance id)")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Gagal deleteToken instance: ${e.message}")
        }
    }

    /**
     * Cek apakah permission notifikasi sudah granted (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Tidak perlu permission untuk Android < 13
        }
    }

    /**
     * Cek apakah notifikasi enabled di settings
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Subscribe ke topic (untuk broadcast notification)
     */
    fun subscribeToTopic(topic: String) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Subscribed to topic: $topic")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to subscribe to topic: ${e.message}")
            }
    }

    /**
     * Unsubscribe dari topic
     */
    fun unsubscribeFromTopic(topic: String) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Unsubscribed from topic: $topic")
            }
    }

    /**
     * Tampilkan notifikasi lokal (untuk testing atau in-app notification)
     */
    fun showLocalNotification(
        context: Context,
        title: String,
        body: String,
        channelId: String = MyFirebaseMessagingService.CHANNEL_GENERAL
    ) {
        // Cek permission untuk Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "⚠️ Notification permission not granted")
                return
            }
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(
            MyFirebaseMessagingService.getUniqueNotificationId(),
            notification
        )
    }
}
