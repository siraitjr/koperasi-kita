package com.example.koperasikitagodangulu.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.koperasikitagodangulu.services.LocationTrackingMonitor
import com.google.firebase.auth.FirebaseAuth
import com.example.koperasikitagodangulu.services.LocationCheckWorker
import com.example.koperasikitagodangulu.services.FirebaseConnectionKeeperService

/**
 * =========================================================================
 * BOOT RECEIVER
 * =========================================================================
 *
 * Receiver ini dijalankan saat HP restart.
 * Tugasnya: Check apakah ada pending data yang perlu di-sync.
 *
 * Jika ada pending data, akan start SyncForegroundService.
 * =========================================================================
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "📱 Device booted, setting up sync...")

            // ✅ Setup WorkManager terlebih dahulu (lebih reliable)
            try {
                NetworkChangeWorker.setup(context)
                SyncWorker.schedulePeriodicSync(context)
                Log.d(TAG, "✅ WorkManager setup complete after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up WorkManager: ${e.message}")
            }

            // Check dan sync jika ada pending data
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    SyncForegroundService.startIfNeeded(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking pending sync: ${e.message}")
                }
                // ✅ Re-start location monitoring setelah boot
                try {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid
                    if (uid != null) {
                        val sharedPref = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        val roleName = sharedPref.getString("user_role", "ADMIN_LAPANGAN")
                        if (roleName != "PENGAWAS") {
                            // ✅ UTAMA: Start persistent service
                            FirebaseConnectionKeeperService.start(context)
                            // Backup layers
                            LocationTrackingMonitor.startMonitoring(context)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting location monitor: ${e.message}")
                }
            }
            // ✅ Schedule location check worker setelah reboot
            try {
                val sharedPref = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val roleName = sharedPref.getString("user_role", "ADMIN_LAPANGAN")
                if (roleName != "PENGAWAS") {
                    LocationCheckWorker.schedule(context)
                    LocationCheckWorker.scheduleImmediate(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling location worker: ${e.message}")
            }
        }
    }
}