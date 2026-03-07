package com.example.koperasikitagodangulu.services

import android.content.Context
import android.util.Log
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

/**
 * =========================================================================
 * LOCATION TRACKING MONITOR (FIXED)
 * =========================================================================
 *
 * Monitor ini berjalan di device Admin/Pimpinan/Koordinator.
 * Tugasnya: Mendengarkan flag dari Pengawas di Firebase.
 *
 * Saat flag `location_tracking/{uid}/active` berubah menjadi true,
 * otomatis start LocationTrackingService.
 * Saat false, stop service dan hapus data lokasi.
 *
 * PERBAIKAN:
 * - Track currentUid agar tidak bentrok saat re-login
 * - Handle onCancelled agar isMonitoring di-reset
 * - Cleanup listener lama sebelum buat baru
 *
 * DIPANGGIL DARI:
 * - MainActivity.kt (saat login berhasil)
 * - BootReceiver.kt (saat HP restart)
 * =========================================================================
 */
object LocationTrackingMonitor {

    private const val TAG = "LocationTrackMonitor"
    private var listener: ValueEventListener? = null
    private var isMonitoring = false
    private var currentUid: String? = null

    /**
     * Mulai monitoring flag tracking dari Pengawas.
     * HANYA dipanggil untuk role: ADMIN_LAPANGAN, PIMPINAN, KOORDINATOR
     */
    fun startMonitoring(context: Context) {
        val uid = Firebase.auth.currentUser?.uid ?: return

        // Jika sudah monitoring UID yang sama DAN listener masih ada, skip
        if (isMonitoring && currentUid == uid && listener != null) {
            Log.d(TAG, "⚠️ Already monitoring uid=$uid, skip")
            return
        }

        // Cleanup listener lama jika ada (beda UID atau stale)
        if (listener != null) {
            stopMonitoring()
        }

        val database = Firebase.database(
            "https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app"
        ).reference

        val trackingRef = database.child("location_tracking").child(uid).child("active")

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isActive = snapshot.getValue(Boolean::class.java) ?: false

                Log.d(TAG, "🔔 Tracking flag changed: active=$isActive")

                if (isActive) {
                    LocationTrackingService.start(context)
                } else {
                    LocationTrackingService.stop(context)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "❌ Listener cancelled: ${error.message}")
                // Reset state agar bisa re-register nanti
                isMonitoring = false
                currentUid = null
            }
        }

        trackingRef.addValueEventListener(listener!!)
        isMonitoring = true
        currentUid = uid

        Log.d(TAG, "✅ Started monitoring tracking flag for uid=$uid")
    }

    /**
     * Hentikan monitoring (saat logout)
     */
    fun stopMonitoring() {
        val uid = currentUid ?: Firebase.auth.currentUser?.uid
        if (uid != null && listener != null) {
            try {
                val database = Firebase.database(
                    "https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app"
                ).reference
                database.child("location_tracking").child(uid).child("active")
                    .removeEventListener(listener!!)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing listener: ${e.message}")
            }
        }
        listener = null
        isMonitoring = false
        currentUid = null
        Log.d(TAG, "🛑 Stopped monitoring")
    }
}