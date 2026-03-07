package com.example.koperasikitagodangulu

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.koperasikitagodangulu.offline.NetworkChangeWorker
import com.example.koperasikitagodangulu.offline.SyncForegroundService
import com.example.koperasikitagodangulu.offline.SyncWorker
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.example.koperasikitagodangulu.services.LocationCheckWorker

/**
 * =========================================================================
 * MyApp - Application Class (VERSI DIPERBAIKI)
 * =========================================================================
 *
 * PERUBAHAN DARI VERSI SEBELUMNYA:
 *
 * ❌ DIHAPUS: WorkManager periodic sync (tidak reliable karena battery optimization)
 * ✅ DITAMBAH: Firebase Persistence (auto-sync saat online)
 * ✅ DITAMBAH: Check pending data saat app start
 * ✅ DITAMBAH: NetworkChangeWorker untuk sync otomatis saat online
 * ✅ DITAMBAH: SyncWorker periodic untuk backup sync
 *
 * CARA KERJA BARU:
 * 1. Firebase Persistence di-enable → auto-queue offline writes
 * 2. Saat app start, check apakah ada pending data di Room DB (backup)
 * 3. Jika ada, start Foreground Service untuk sync
 * 4. Foreground Service DIJAMIN berjalan (tidak di-kill sistem)
 * 5. NetworkChangeWorker akan trigger sync saat network available
 * =========================================================================
 */
class MyApp : Application() {

    companion object {
        private const val TAG = "MyApp"
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // ✅ TAMBAHAN: Global crash handler untuk mencegah silent crash
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "💥 UNCAUGHT EXCEPTION on thread ${thread.name}: ${throwable.message}")
            Log.e(TAG, "💥 Stack trace:", throwable)
            // Tetap forward ke default handler agar sistem bisa menangani
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 1. Enable Firebase Persistence (PALING PENTING!)
        initFirebasePersistence()

        // 2. Setup WorkManager untuk background sync
        setupBackgroundSync()

        // 3. Check apakah ada pending data yang perlu di-sync
        checkPendingData()
    }

    private fun initFirebasePersistence() {
        try {
            // =========================================================
            // PENTING: Firebase Persistence HARUS di-enable SEBELUM
            // operasi database lainnya!
            //
            // Dengan persistence enabled:
            // - Saat ONLINE: data langsung masuk ke server
            // - Saat OFFLINE: data di-queue di local cache
            // - Saat KEMBALI ONLINE: Firebase AUTO-SYNC dari cache
            // =========================================================
            FirebaseDatabase.getInstance("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").setPersistenceEnabled(true)

            Log.d(TAG, "✅ Firebase Persistence ENABLED")
            Log.d(TAG, "   → Offline writes akan otomatis di-queue")
            Log.d(TAG, "   → Auto-sync saat kembali online")

        } catch (e: Exception) {
            // Exception terjadi jika sudah di-set sebelumnya (multi-process)
            // Ini aman diabaikan
            Log.w(TAG, "Firebase persistence: ${e.message}")
        }
    }

    private fun setupBackgroundSync() {
        try {
            // ✅ TAMBAHAN: Setup NetworkChangeWorker untuk trigger sync saat online
            NetworkChangeWorker.setup(this)
            Log.d(TAG, "✅ NetworkChangeWorker setup complete")

            // ✅ TAMBAHAN: Schedule periodic sync sebagai backup (setiap 15 menit)
            SyncWorker.schedulePeriodicSync(this)
            Log.d(TAG, "✅ SyncWorker periodic sync scheduled")

        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error setting up background sync: ${e.message}")
        }
        // ✅ TAMBAHAN: Schedule location check worker untuk tracking
        try {
            val sharedPref = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val roleName = sharedPref.getString("user_role", "ADMIN_LAPANGAN")
            if (roleName != "PENGAWAS") {
                LocationCheckWorker.schedule(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error setting up location check worker: ${e.message}")
        }
    }

    private fun checkPendingData() {
        // Check apakah ada data di Room DB yang perlu di-sync
        // Room DB digunakan sebagai BACKUP jika Firebase gagal
        applicationScope.launch {
            try {
                // Start foreground service jika ada pending data
                SyncForegroundService.startIfNeeded(this@MyApp)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking pending data: ${e.message}")
            }
        }
    }
}