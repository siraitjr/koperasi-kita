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

    /**
     * Hapus entry pelanggan_status_khusus (offline-first)
     */
    suspend fun removeStatusKhusus(
        cabangId: String,
        pelangganId: String,
        adminUid: String
    ): SaveResult {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 removeStatusKhusus() CALLED!")
        Log.d(TAG, "   cabangId: $cabangId")
        Log.d(TAG, "   pelangganId: $pelangganId")
        Log.d(TAG, "   adminUid: $adminUid")
        Log.d(TAG, "========================================")

        return syncManager.removeStatusKhususDirect(
            cabangId = cabangId,
            pelangganId = pelangganId,
            adminUid = adminUid
        )
    }

    // =========================================================================
    // OPERASI ANTRIAN KHUSUS UNTUK cairkanSimpanan (offline-first end-to-end)
    // =========================================================================
    // Sebelumnya cairkanSimpanan menulis jurnal_transaksi, riwayat_pinjaman,
    // dan removeValue pelanggan + status_khusus secara DIRECT — gagal offline.
    // Sekarang semua di-queue ke Room → SyncWorker memutar saat online.
    // =========================================================================

    /**
     * Antri entry jurnal_transaksi. Push key di-generate client-side agar
     * retry replay idempoten (kalau pakai .push() di sync time, retry akan
     * menulis key baru = duplikasi).
     *
     * Return: pushKey yang dipakai (untuk caller yang butuh referensi).
     */
    suspend fun addJurnalTransaksi(
        cabangId: String,
        yearMonth: String,
        adminUid: String,
        jurnalData: Map<String, Any?>
    ): String {
        val parentPath = "jurnal_transaksi/$cabangId/$yearMonth"
        val pushKey = syncManager.generatePushKey(parentPath)
        val firebasePath = "$parentPath/$pushKey"
        Log.d(TAG, "🚀 addJurnalTransaksi() → $firebasePath")
        syncManager.queueOperation(
            operationType = "ADD_JURNAL_TRANSAKSI",
            firebasePath = firebasePath,
            dataJson = com.google.gson.Gson().toJson(jurnalData),
            adminUid = adminUid
        )
        return pushKey
    }

    /**
     * Antri arsip riwayat_pinjaman. Path mengandung pinjamanKe → idempoten
     * (replay menulis ke key yang sama).
     */
    suspend fun addRiwayatPinjaman(
        adminUid: String,
        pelangganId: String,
        pinjamanKe: Int,
        arsipData: Map<String, Any?>
    ) {
        val firebasePath = "riwayat_pinjaman/$adminUid/$pelangganId/$pinjamanKe"
        Log.d(TAG, "🚀 addRiwayatPinjaman() → $firebasePath")
        syncManager.queueOperation(
            operationType = "ADD_RIWAYAT_PINJAMAN",
            firebasePath = firebasePath,
            dataJson = com.google.gson.Gson().toJson(arsipData),
            adminUid = adminUid,
            pelangganId = pelangganId
        )
    }

    /**
     * Antri penghapusan node pelanggan (cairkanSimpanan: nasabah lunas total).
     * removeValue() idempoten — retry aman.
     */
    suspend fun removePelanggan(adminUid: String, pelangganId: String) {
        val firebasePath = "pelanggan/$adminUid/$pelangganId"
        Log.d(TAG, "🚀 removePelanggan() → $firebasePath")
        syncManager.queueOperation(
            operationType = "REMOVE_PELANGGAN",
            firebasePath = firebasePath,
            dataJson = "{}",
            adminUid = adminUid,
            pelangganId = pelangganId
        )
    }

    /**
     * Antri penghapusan entry pelanggan_status_khusus untuk cairkanSimpanan
     * (versi removeStatusKhusus di atas pakai jalur lama; ini adalah versi
     * queue-only yang tidak mencoba tulis langsung).
     */
    suspend fun removePelangganStatusKhusus(cabangId: String, pelangganId: String, adminUid: String) {
        val firebasePath = "pelanggan_status_khusus/$cabangId/$pelangganId"
        Log.d(TAG, "🚀 removePelangganStatusKhusus() → $firebasePath")
        syncManager.queueOperation(
            operationType = "REMOVE_PELANGGAN_STATUS_KHUSUS",
            firebasePath = firebasePath,
            dataJson = "{}",
            adminUid = adminUid,
            pelangganId = pelangganId
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

    // Hitungan PENDING/SYNCING saja (tanpa FAILED) untuk badge "menunggu sinkronisasi".
    suspend fun getPendingOnlyCount(): Int = syncManager.getPendingOnlyCount()
    fun observePendingOnlyCount(): Flow<Int> = syncManager.observePendingOnlyCount()

    // Hitungan FAILED saja untuk badge "gagal & butuh perhatian".
    suspend fun getFailedCount(): Int = syncManager.getFailedCount()
    fun observeFailedCount(): Flow<Int> = syncManager.observeFailedCount()
    suspend fun getFailedOperations(): List<PendingOperation> = syncManager.getFailedOperations()

    suspend fun syncNow(): SyncResult {
        Log.d(TAG, "🔄 syncNow() called")
        return syncManager.syncAllPending()
    }

    suspend fun retryFailed(): Int {
        Log.d(TAG, "🔄 retryFailed() called")
        return syncManager.retryAllFailed()
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