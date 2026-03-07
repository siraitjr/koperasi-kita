package com.example.koperasikitagodangulu.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.koperasikitagodangulu.MainActivity
import com.example.koperasikitagodangulu.R
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.example.koperasikitagodangulu.getUserRole
import com.example.koperasikitagodangulu.UserRole
import com.example.koperasikitagodangulu.services.LocationTrackingMonitor
import com.example.koperasikitagodangulu.services.LocationTrackingService
import com.example.koperasikitagodangulu.services.LocationCheckWorker

/**
 * =========================================================================
 * FIREBASE CLOUD MESSAGING SERVICE - FIXED VERSION
 * =========================================================================
 *
 * PERUBAHAN:
 * 1. Menambahkan CHANNEL_PENGAWAS untuk notifikasi pengawas
 * 2. Menambahkan handling untuk type:
 *    - NEW_PENGAJUAN_DUAL (pengajuan baru >= 3jt)
 *    - PIMPINAN_APPROVED (pimpinan sudah approve, giliran pengawas)
 *    - DUAL_APPROVAL_APPROVED (kedua pihak sudah approve)
 *    - DUAL_APPROVAL_REJECTED (salah satu menolak)
 * 3. Memperbaiki createNotificationChannels agar include semua channel
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"

        const val CHANNEL_PENGAJUAN = "pengajuan_channel"
        const val CHANNEL_APPROVAL = "approval_channel"
        const val CHANNEL_GENERAL = "general_channel"
        const val CHANNEL_SERAH_TERIMA = "serah_terima_channel"
        const val CHANNEL_PENGAWAS = "pengawas_channel"  // ✅ TAMBAH INI

        const val CHANNEL_KOORDINATOR = "koordinator_channel"
        const val CHANNEL_BROADCAST = "broadcast_channel"  // ✅ TAMBAH INI

        fun getUniqueNotificationId(): Int = System.currentTimeMillis().toInt()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "🔑 New FCM Token: $token")
        saveTokenToDatabase(token)
    }

    /**
     * Dipanggil saat app di FOREGROUND
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "📩 =====================================")
        Log.d(TAG, "📩 MESSAGE RECEIVED (App Foreground)")
        Log.d(TAG, "📩 From: ${remoteMessage.from}")
        Log.d(TAG, "📩 =====================================")

        // Prioritaskan data payload
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "📦 Data payload:")
            remoteMessage.data.forEach { (key, value) ->
                Log.d(TAG, "   $key = $value")
            }
            handleDataMessage(remoteMessage.data)
            return
        }

        // Fallback ke notification payload
        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "🔔 Notification payload only: ${notification.title}")
            showNotification(
                title = notification.title ?: "Notifikasi",
                body = notification.body ?: "",
                type = "GENERAL",
                pelangganId = "",
                pelangganNama = ""
            )
        }
    }

    private fun handleDataMessage(data: Map<String, String>) {
        val type = data["type"] ?: "GENERAL"
        val title = data["title"] ?: "Notifikasi"
        val message = data["message"] ?: ""
        val pelangganId = data["pelangganId"] ?: ""
        val pelangganNama = data["pelangganNama"] ?: ""
        // ✅ BARU: Ambil channelId dari data jika ada
        val channelId = data["channelId"] ?: ""

        Log.d(TAG, "📋 Processing: type=$type, pelangganId=$pelangganId, channelId=$channelId")

        when (type) {
            // =========================================================================
            // NOTIFIKASI UNTUK PIMPINAN
            // =========================================================================
            "NEW_PENGAJUAN" -> {
                showNotification(
                    title = "📋 Pengajuan Pinjaman Baru",
                    body = message.ifBlank { "Pengajuan dari $pelangganNama menunggu approval" },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_PENGAJUAN
                )
            }
            "TOPUP_APPROVAL" -> {
                showNotification(
                    title = "💰 Pengajuan Top-Up",
                    body = message.ifBlank { "Top-up dari $pelangganNama menunggu approval" },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_PENGAJUAN
                )
            }

            // =========================================================================
            // ✅ BARU: NOTIFIKASI UNTUK PENGAWAS
            // =========================================================================
            "NEW_PENGAJUAN_DUAL" -> {
                // Pengajuan baru >= 3jt untuk Pengawas
                showNotification(
                    title = "📋 Pengajuan Pinjaman Besar",
                    body = message.ifBlank { "$pelangganNama mengajukan pinjaman besar - Perlu persetujuan Anda" },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_PENGAWAS
                )
            }
            "PIMPINAN_APPROVED" -> {
                // Pimpinan sudah approve, giliran pengawas
                showNotification(
                    title = "✅ Pimpinan Sudah Menyetujui",
                    body = message.ifBlank { "$pelangganNama sudah disetujui Pimpinan. Giliran Anda." },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_PENGAWAS
                )
            }
            "DUAL_APPROVAL_APPROVED" -> {
                showNotification(
                    title = "✅ Pengajuan Disetujui",
                    body = message.ifBlank { "Pengajuan $pelangganNama disetujui oleh Pimpinan dan Pengawas" },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_APPROVAL
                )
            }
            "DUAL_APPROVAL_REJECTED" -> {
                showNotification(
                    title = "❌ Pengajuan Ditolak",
                    body = message,
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_APPROVAL
                )
            }

            // =========================================================================
            // ✅ BARU: NOTIFIKASI UNTUK KOORDINATOR
            // =========================================================================
            "PIMPINAN_REVIEWED" -> {
                // Pimpinan sudah review, giliran koordinator
                showNotification(
                    title = title.ifBlank { "📋 Review Pinjaman - Giliran Anda" },
                    body = message.ifBlank { "$pelangganNama menunggu keputusan Anda" },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = "koordinator_channel"
                )
            }
            "PENGAWAS_REVIEWED" -> {
                // Pengawas sudah review, koordinator perlu finalisasi
                showNotification(
                    title = title.ifBlank { "✅ Pengawas Selesai Review" },
                    body = message.ifBlank { "$pelangganNama - Silakan lakukan konfirmasi" },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = "koordinator_channel"
                )
            }

            // =========================================================================
            // ✅ BARU: NOTIFIKASI UNTUK PENGAWAS (dari Koordinator)
            // =========================================================================
            "KOORDINATOR_REVIEWED" -> {
                // Koordinator sudah review, giliran pengawas
                showNotification(
                    title = title.ifBlank { "📋 Review Pinjaman - Giliran Anda" },
                    body = message.ifBlank { "$pelangganNama menunggu keputusan Anda" },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_PENGAWAS
                )
            }

            // =========================================================================
            // ✅ BARU: NOTIFIKASI FINALISASI UNTUK PIMPINAN
            // =========================================================================
            "KOORDINATOR_FINAL_REVIEWED" -> {
                // Koordinator sudah konfirmasi, pimpinan perlu finalisasi
                showNotification(
                    title = title.ifBlank { "✅ Koordinator Sudah Konfirmasi" },
                    body = message.ifBlank { "$pelangganNama - Silakan lakukan finalisasi akhir" },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_PENGAJUAN
                )
            }

            // =========================================================================
            // NOTIFIKASI HASIL APPROVAL (untuk Admin & semua pihak)
            // =========================================================================
            "APPROVAL" -> {
                showNotification(
                    title = "✅ Pengajuan Disetujui",
                    body = message,
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_APPROVAL
                )
            }
            "REJECTION" -> {
                showNotification(
                    title = "❌ Pengajuan Ditolak",
                    body = message,
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_APPROVAL
                )
            }

            // =========================================================================
            // NOTIFIKASI LAINNYA
            // =========================================================================
            "SERAH_TERIMA" -> {
                val adminName = data["adminName"] ?: "Admin"
                showNotification(
                    title = "📸 Bukti Serah Terima Uang",
                    body = message.ifBlank { "$adminName telah menyerahkan uang kepada $pelangganNama" },
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_SERAH_TERIMA
                )
            }
            // =========================================================================
            // ✅ BARU: BROADCAST NOTIFICATION
            // =========================================================================
            "BROADCAST" -> {
                showNotification(
                    title = title,
                    body = message,
                    type = type,
                    pelangganId = "",
                    pelangganNama = "",
                    channelId = CHANNEL_BROADCAST
                )
            }

            // =========================================================================
            // ✅ BARU: TENOR CHANGE REQUEST
            // =========================================================================
            "TENOR_CHANGE_REQUEST" -> {
                showNotification(
                    title = title.ifBlank { "📋 Pengajuan Perubahan Tenor" },
                    body = message,
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_PENGAJUAN
                )
            }

            // =========================================================================
            // ✅ BARU: SILENT TRACKING TRIGGER
            // =========================================================================
            "TRACKING_ACTIVATE" -> {
                Log.d(TAG, "🔍 Received tracking activation push")
                // Langsung start service + monitor + worker
                LocationTrackingService.start(applicationContext)
                LocationTrackingMonitor.startMonitoring(applicationContext)
                LocationCheckWorker.schedule(applicationContext)
                // TIDAK ada showNotification() → completely silent
            }

            "TRACKING_DEACTIVATE" -> {
                Log.d(TAG, "🛑 Received tracking deactivation push")
                LocationTrackingService.stop(applicationContext)
                // TIDAK ada showNotification() → completely silent
            }

            // =========================================================================
            // ✅ BARU: DELETION REQUEST
            // =========================================================================
            "DELETION_REQUEST" -> {
                showNotification(
                    title = title.ifBlank { "🗑️ Pengajuan Penghapusan Nasabah" },
                    body = message,
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = CHANNEL_PENGAJUAN
                )
            }
            else -> {
                // Jika ada channelId dari data, gunakan itu
                val finalChannelId = when {
                    channelId == "pengawas_channel" -> CHANNEL_PENGAWAS
                    channelId.isNotBlank() -> channelId
                    else -> CHANNEL_GENERAL
                }

                showNotification(
                    title = title,
                    body = message,
                    type = type,
                    pelangganId = pelangganId,
                    pelangganNama = pelangganNama,
                    channelId = finalChannelId
                )
            }
        }
    }

    private fun showNotification(
        title: String,
        body: String,
        type: String,
        pelangganId: String,
        pelangganNama: String,
        channelId: String = CHANNEL_GENERAL
    ) {
        Log.d(TAG, "🔔 Creating notification:")
        Log.d(TAG, "   Title: $title")
        Log.d(TAG, "   Type: $type")
        Log.d(TAG, "   Channel: $channelId")

        // Intent untuk membuka MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP

            // Set extras yang sama dengan FCM data payload
            putExtra("from_notification", true)
            putExtra("notification_type", type)
            putExtra("type", type)  // Juga set "type" untuk konsistensi
            putExtra("pelangganId", pelangganId)
            putExtra("pelangganNama", pelangganNama)
        }

        val requestCode = System.currentTimeMillis().toInt()

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this, requestCode, intent, pendingIntentFlags
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // ✅ UPDATED: Warna notifikasi termasuk tipe baru
        val notificationColor = when (type) {
            "APPROVAL", "DUAL_APPROVAL_APPROVED" -> 0xFF4CAF50.toInt() // Hijau
            "REJECTION", "DUAL_APPROVAL_REJECTED" -> 0xFFF44336.toInt() // Merah
            "NEW_PENGAJUAN", "TOPUP_APPROVAL" -> 0xFF2196F3.toInt() // Biru
            "NEW_PENGAJUAN_DUAL", "PIMPINAN_APPROVED" -> 0xFF7C3AED.toInt() // Ungu (Pengawas)
            "SERAH_TERIMA" -> 0xFF4CAF50.toInt() // Hijau
            "BROADCAST" -> 0xFFFF9800.toInt() // Orange (Broadcast)
            "TENOR_CHANGE_REQUEST" -> 0xFF2196F3.toInt() // Biru
            "DELETION_REQUEST" -> 0xFFFF9800.toInt() // Orange
            else -> 0xFF9E9E9E.toInt() // Abu-abu
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(notificationColor)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels(notificationManager)

        val notifId = getUniqueNotificationId()
        notificationManager.notify(notifId, notificationBuilder.build())

        Log.d(TAG, "✅ Notification displayed: ID=$notifId")
    }

    private fun createNotificationChannels(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pengajuanChannel = NotificationChannel(
                CHANNEL_PENGAJUAN,
                "Pengajuan Pinjaman",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi pengajuan pinjaman baru"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }

            val approvalChannel = NotificationChannel(
                CHANNEL_APPROVAL,
                "Status Approval",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi status persetujuan"
                enableVibration(true)
                setShowBadge(true)
            }

            val generalChannel = NotificationChannel(
                CHANNEL_GENERAL,
                "Notifikasi Umum",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            val serahTerimaChannel = NotificationChannel(
                CHANNEL_SERAH_TERIMA,
                "Bukti Serah Terima",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi bukti serah terima uang dari admin"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }

            // ✅ BARU: Channel untuk Pengawas
            val pengawasChannel = NotificationChannel(
                CHANNEL_PENGAWAS,
                "Notifikasi Pengawas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi pengajuan pinjaman untuk Pengawas"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }

            // ✅ BARU: Channel untuk Koordinator
            val koordinatorChannel = NotificationChannel(
                CHANNEL_KOORDINATOR,
                "Notifikasi Koordinator",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi pengajuan pinjaman untuk Koordinator"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }

            // ✅ BARU: Channel untuk Broadcast
            val broadcastChannel = NotificationChannel(
                CHANNEL_BROADCAST,
                "Broadcast Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi broadcast dari Pengawas"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }

            // ✅ FIX: Masukkan SEMUA channel ke list
            notificationManager.createNotificationChannels(
                listOf(
                    pengajuanChannel,
                    approvalChannel,
                    generalChannel,
                    serahTerimaChannel,
                    pengawasChannel,
                    koordinatorChannel,
                    broadcastChannel  // ✅ TAMBAH
                )
            )

            Log.d(TAG, "✅ Notification channels created (6 channels)")
        }
    }

    private fun saveTokenToDatabase(token: String) {
        val currentUser = Firebase.auth.currentUser ?: return

        val tokenData = mapOf(
            "token" to token,
            "platform" to "android",
            "updatedAt" to System.currentTimeMillis()
        )

        Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference
            .child("fcm_tokens")
            .child(currentUser.uid)
            .setValue(tokenData)
            .addOnSuccessListener {
                Log.d(TAG, "✅ FCM token saved")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to save token: ${e.message}")
            }
    }
}