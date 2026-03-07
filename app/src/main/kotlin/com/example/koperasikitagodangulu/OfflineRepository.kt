package com.example.koperasikitagodangulu.offline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow

/**
 * =========================================================================
 * OFFLINE REPOSITORY - WITH DEBUG LOGS!
 * =========================================================================
 */
class OfflineRepository private constructor(context: Context) {

    private val syncManager = SyncManager.getInstance(context)
    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "OfflineRepository"

        @Volatile
        private var INSTANCE: OfflineRepository? = null

        fun getInstance(context: Context): OfflineRepository {
            Log.d(TAG, "🔧 getInstance() called")
            return INSTANCE ?: synchronized(this) {
                Log.d(TAG, "🔧 Creating new OfflineRepository instance")
                val instance = OfflineRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    init {
        Log.d(TAG, "🔧 OfflineRepository initialized")
    }

    // =========================================================================
    // OPERATIONS
    // =========================================================================

    /**
     * Simpan pelanggan
     */
    suspend fun savePelanggan(
        adminUid: String,
        pelangganId: String,
        pelanggan: Any
    ): SaveResult {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 savePelanggan() CALLED!")
        Log.d(TAG, "   adminUid: $adminUid")
        Log.d(TAG, "   pelangganId: $pelangganId")
        Log.d(TAG, "   pelanggan type: ${pelanggan::class.java.simpleName}")
        Log.d(TAG, "========================================")

        @Suppress("UNCHECKED_CAST")
        val pelangganMap = pelanggan as? Map<String, Any?>

        if (pelangganMap == null) {
            Log.e(TAG, "❌ pelanggan is NOT a Map! Type: ${pelanggan::class.java}")
            return SaveResult.Error("Invalid data type")
        }

        Log.d(TAG, "✅ pelangganMap has ${pelangganMap.size} fields")
        Log.d(TAG, "   Calling syncManager.savePelangganDirect()...")

        return syncManager.savePelangganDirect(
            adminUid = adminUid,
            pelangganId = pelangganId,
            pelangganData = pelangganMap
        )
    }

    /**
     * Update pelanggan
     */
    suspend fun updatePelanggan(
        adminUid: String,
        pelangganId: String,
        updateData: Map<String, Any?>
    ): SaveResult {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 updatePelanggan() CALLED!")
        Log.d(TAG, "   adminUid: $adminUid")
        Log.d(TAG, "   pelangganId: $pelangganId")
        Log.d(TAG, "========================================")

        return syncManager.updatePelangganDirect(
            adminUid = adminUid,
            pelangganId = pelangganId,
            updateData = updateData
        )
    }

    /**
     * Tambah pembayaran
     */
    suspend fun addPembayaran(
        adminUid: String,
        pelangganId: String,
        pembayaranIndex: Int,
        pembayaran: Any
    ): SaveResult {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 addPembayaran() CALLED!")
        Log.d(TAG, "   adminUid: $adminUid")
        Log.d(TAG, "   pelangganId: $pelangganId")
        Log.d(TAG, "   pembayaranIndex: $pembayaranIndex")
        Log.d(TAG, "========================================")

        @Suppress("UNCHECKED_CAST")
        val pembayaranMap = pembayaran as? Map<String, Any?>

        if (pembayaranMap == null) {
            Log.e(TAG, "❌ pembayaran is NOT a Map!")
            return SaveResult.Error("Invalid data type")
        }

        Log.d(TAG, "✅ pembayaranMap has ${pembayaranMap.size} fields")

        return syncManager.savePembayaranDirect(
            adminUid = adminUid,
            pelangganId = pelangganId,
            pembayaranIndex = pembayaranIndex,
            pembayaranData = pembayaranMap
        )
    }

    /**
     * Tambah sub-pembayaran
     */
    suspend fun addSubPembayaran(
        adminUid: String,
        pelangganId: String,
        pembayaranIndex: Int,
        subIndex: Int,
        subPembayaran: Any
    ): SaveResult {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 addSubPembayaran() CALLED!")
        Log.d(TAG, "========================================")

        @Suppress("UNCHECKED_CAST")
        val subMap = subPembayaran as? Map<String, Any?>

        if (subMap == null) {
            Log.e(TAG, "❌ subPembayaran is NOT a Map!")
            return SaveResult.Error("Invalid data type")
        }

        return syncManager.saveSubPembayaranDirect(
            adminUid = adminUid,
            pelangganId = pelangganId,
            pembayaranIndex = pembayaranIndex,
            subIndex = subIndex,
            subPembayaranData = subMap
        )
    }

    // =========================================================================
    // STATUS & SYNC
    // =========================================================================

    suspend fun getPendingSyncCount(): Int {
        val count = syncManager.getPendingCount()
        Log.d(TAG, "📊 getPendingSyncCount() = $count")
        return count
    }

    fun observePendingSyncCount(): Flow<Int> {
        return syncManager.observePendingCount()
    }

    suspend fun syncNow(): SyncResult {
        Log.d(TAG, "🔄 syncNow() called")
        return syncManager.syncAllPending()
    }

    suspend fun retryFailed() {
        Log.d(TAG, "🔄 retryFailed() called")
        syncManager.retryAllFailed()
    }

    fun triggerSync() {
        Log.d(TAG, "🚀 triggerSync() called")
        SyncForegroundService.startSync(appContext)
    }

    fun isOnline(): Boolean {
        val online = syncManager.isOnline()
        Log.d(TAG, "🌐 isOnline() = $online")
        return online
    }
}