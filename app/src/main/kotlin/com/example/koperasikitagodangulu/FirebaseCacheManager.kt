package com.example.koperasikitagodangulu.optimized

import android.content.Context
import android.util.Log
import com.example.koperasikitagodangulu.Pelanggan
import com.example.koperasikitagodangulu.models.AdminSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * =========================================================================
 * FIREBASE CACHE MANAGER
 * =========================================================================
 *
 * Manager untuk mengelola cache data Firebase.
 * Tujuan: Mengurangi reads ke Firebase dengan menyimpan data yang sudah di-load.
 *
 * STRATEGI CACHE:
 * 1. Memory Cache: Data yang baru saja diakses (LRU)
 * 2. Timestamp-based invalidation: Data expired setelah X menit
 * 3. Manual invalidation: Saat ada update dari user
 */
class FirebaseCacheManager private constructor() {

    companion object {
        private const val TAG = "FirebaseCacheManager"

        @Volatile
        private var instance: FirebaseCacheManager? = null

        fun getInstance(): FirebaseCacheManager {
            return instance ?: synchronized(this) {
                instance ?: FirebaseCacheManager().also { instance = it }
            }
        }
    }

    // =========================================================================
    // MEMORY CACHES
    // =========================================================================

    /** Cache untuk data pelanggan per admin */
    private val pelangganCache = ConcurrentHashMap<String, CachedData<List<Pelanggan>>>()

    /** Cache untuk admin summary */
    private val adminSummaryCache = ConcurrentHashMap<String, CachedData<AdminSummary>>()

    /** Cache untuk cabang summary */
    private val cabangSummaryCache = ConcurrentHashMap<String, CachedData<CabangSummaryData>>()

    /** Cache untuk global summary */
    private var globalSummaryCache: CachedData<GlobalSummaryData>? = null

    /** Cache untuk pending approvals */
    private val pendingApprovalsCache = ConcurrentHashMap<String, CachedData<List<Pelanggan>>>()

    /** Cache untuk pimpinan pelanggan (semua pelanggan di cabang) */
    private val pimpinanPelangganCache = ConcurrentHashMap<String, CachedData<List<Pelanggan>>>()

    /** Mutex untuk thread-safe operations */
    private val cacheMutex = Mutex()

    // =========================================================================
    // DATA CLASSES
    // =========================================================================

    data class CachedData<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis(),
        val source: CacheSource = CacheSource.MEMORY
    ) {
        fun isValid(maxAgeMs: Long = FirebaseOptimizationConfig.CACHE_DURATION_MS): Boolean {
            return System.currentTimeMillis() - timestamp < maxAgeMs
        }
    }

    data class CabangSummaryData(
        val cabangId: String,
        val totalNasabah: Int,
        val nasabahAktif: Int,
        val totalPinjamanAktif: Long
    )

    data class GlobalSummaryData(
        val totalNasabah: Int,
        val nasabahAktif: Int,
        val totalPinjamanAktif: Long,
        val totalTunggakan: Long,
        val pembayaranHariIni: Long,
        val targetHariIni: Long
    )

    // =========================================================================
    // PELANGGAN CACHE OPERATIONS
    // =========================================================================

    /**
     * Simpan data pelanggan ke cache
     * @param adminUid UID admin pemilik data
     * @param pelangganList List pelanggan yang akan di-cache
     */
    suspend fun cachePelanggan(adminUid: String, pelangganList: List<Pelanggan>) {
        cacheMutex.withLock {
            pelangganCache[adminUid] = CachedData(pelangganList)
            Log.d(TAG, "✅ Cached ${pelangganList.size} pelanggan for admin $adminUid")

            // Enforce cache size limit
            enforceCacheLimit()
        }
    }

    /**
     * Ambil data pelanggan dari cache
     * @param adminUid UID admin
     * @return List pelanggan jika cache valid, null jika expired/tidak ada
     */
    fun getPelangganFromCache(adminUid: String): List<Pelanggan>? {
        val cached = pelangganCache[adminUid]
        return if (cached != null && cached.isValid()) {
            Log.d(TAG, "📦 Cache HIT for admin $adminUid (${cached.data.size} items)")
            cached.data
        } else {
            Log.d(TAG, "📦 Cache MISS for admin $adminUid")
            null
        }
    }

    /**
     * Invalidate cache pelanggan untuk admin tertentu
     */
    fun invalidatePelangganCache(adminUid: String) {
        pelangganCache.remove(adminUid)
        Log.d(TAG, "🗑️ Invalidated pelanggan cache for admin $adminUid")
    }

    /**
     * Update single pelanggan di cache tanpa reload semua
     */
    suspend fun updatePelangganInCache(adminUid: String, updatedPelanggan: Pelanggan) {
        cacheMutex.withLock {
            val cached = pelangganCache[adminUid]
            if (cached != null) {
                val updatedList = cached.data.map {
                    if (it.id == updatedPelanggan.id) updatedPelanggan else it
                }
                pelangganCache[adminUid] = CachedData(updatedList)
                Log.d(TAG, "✏️ Updated pelanggan ${updatedPelanggan.id} in cache")
            }
        }
    }

    /**
     * Tambah pelanggan baru ke cache
     */
    suspend fun addPelangganToCache(adminUid: String, newPelanggan: Pelanggan) {
        cacheMutex.withLock {
            val cached = pelangganCache[adminUid]
            if (cached != null) {
                val updatedList = cached.data + newPelanggan
                pelangganCache[adminUid] = CachedData(updatedList)
                Log.d(TAG, "➕ Added pelanggan ${newPelanggan.id} to cache")
            }
        }
    }

    // =========================================================================
    // PIMPINAN PELANGGAN CACHE OPERATIONS (UNTUK SELURUH CABANG)
    // =========================================================================

    /**
     * Simpan data pelanggan pimpinan (semua pelanggan di cabang) ke cache
     * @param cabangId ID cabang
     * @param pelangganList List semua pelanggan di cabang
     */
    suspend fun cachePimpinanPelanggan(cabangId: String, pelangganList: List<Pelanggan>) {
        cacheMutex.withLock {
            pimpinanPelangganCache[cabangId] = CachedData(
                data = pelangganList,
                timestamp = System.currentTimeMillis()
            )
            Log.d(TAG, "✅ Cached ${pelangganList.size} pelanggan for pimpinan cabang $cabangId")
        }
    }

    /**
     * Ambil data pelanggan pimpinan dari cache
     * Cache TTL lebih pendek (5 menit) karena data ini besar
     * @param cabangId ID cabang
     * @return List pelanggan jika cache valid, null jika expired/tidak ada
     */
    fun getPimpinanPelangganCache(cabangId: String): List<Pelanggan>? {
        val cached = pimpinanPelangganCache[cabangId]
        // Pimpinan cache TTL = 5 menit
        val pimpinanCacheTTL = 5 * 60 * 1000L
        return if (cached != null && cached.isValid(pimpinanCacheTTL)) {
            Log.d(TAG, "📦 Pimpinan cache HIT for cabang $cabangId (${cached.data.size} items)")
            cached.data
        } else {
            Log.d(TAG, "📦 Pimpinan cache MISS for cabang $cabangId")
            null
        }
    }

    /**
     * Invalidate cache pimpinan untuk cabang tertentu
     */
    fun invalidatePimpinanPelangganCache(cabangId: String) {
        pimpinanPelangganCache.remove(cabangId)
        Log.d(TAG, "🗑️ Invalidated pimpinan pelanggan cache for cabang $cabangId")
    }

    // =========================================================================
    // ADMIN SUMMARY CACHE OPERATIONS
    // =========================================================================

    suspend fun cacheAdminSummary(adminUid: String, summary: AdminSummary) {
        cacheMutex.withLock {
            adminSummaryCache[adminUid] = CachedData(summary)
        }
    }

    fun getAdminSummaryFromCache(adminUid: String): AdminSummary? {
        val cached = adminSummaryCache[adminUid]
        return if (cached != null && cached.isValid(FirebaseOptimizationConfig.SUMMARY_CACHE_DURATION_MS)) {
            cached.data
        } else null
    }

    suspend fun cacheMultipleAdminSummaries(summaries: List<AdminSummary>) {
        cacheMutex.withLock {
            summaries.forEach { summary ->
                adminSummaryCache[summary.adminId] = CachedData(summary)
            }
            Log.d(TAG, "✅ Cached ${summaries.size} admin summaries")
        }
    }

    fun getAllAdminSummariesFromCache(): List<AdminSummary>? {
        val validSummaries = adminSummaryCache.values
            .filter { it.isValid(FirebaseOptimizationConfig.SUMMARY_CACHE_DURATION_MS) }
            .map { it.data }

        return if (validSummaries.isNotEmpty()) validSummaries else null
    }

    // =========================================================================
    // CABANG SUMMARY CACHE OPERATIONS
    // =========================================================================

    suspend fun cacheCabangSummary(cabangId: String, summary: CabangSummaryData) {
        cacheMutex.withLock {
            cabangSummaryCache[cabangId] = CachedData(summary)
        }
    }

    fun getCabangSummaryFromCache(cabangId: String): CabangSummaryData? {
        val cached = cabangSummaryCache[cabangId]
        return if (cached != null && cached.isValid(FirebaseOptimizationConfig.SUMMARY_CACHE_DURATION_MS)) {
            cached.data
        } else null
    }

    // =========================================================================
    // GLOBAL SUMMARY CACHE OPERATIONS
    // =========================================================================

    suspend fun cacheGlobalSummary(summary: GlobalSummaryData) {
        cacheMutex.withLock {
            globalSummaryCache = CachedData(summary)
        }
    }

    fun getGlobalSummaryFromCache(): GlobalSummaryData? {
        val cached = globalSummaryCache
        return if (cached != null && cached.isValid(FirebaseOptimizationConfig.SUMMARY_CACHE_DURATION_MS)) {
            cached.data
        } else null
    }

    // =========================================================================
    // PENDING APPROVALS CACHE
    // =========================================================================

    suspend fun cachePendingApprovals(cabangId: String, approvals: List<Pelanggan>) {
        cacheMutex.withLock {
            pendingApprovalsCache[cabangId] = CachedData(
                data = approvals,
                // Pending approvals cache lebih pendek (1 menit) karena lebih kritis
                timestamp = System.currentTimeMillis()
            )
        }
    }

    fun getPendingApprovalsFromCache(cabangId: String): List<Pelanggan>? {
        val cached = pendingApprovalsCache[cabangId]
        // Pending approvals hanya cache 1 menit
        return if (cached != null && cached.isValid(60 * 1000L)) {
            cached.data
        } else null
    }

    fun invalidatePendingApprovalsCache(cabangId: String) {
        pendingApprovalsCache.remove(cabangId)
    }

    // =========================================================================
    // CACHE MANAGEMENT
    // =========================================================================

    /**
     * Enforce cache size limit dengan menghapus entries terlama
     */
    private fun enforceCacheLimit() {
        val maxSize = FirebaseOptimizationConfig.MAX_MEMORY_CACHE_SIZE

        if (pelangganCache.size > maxSize) {
            // Sort by timestamp dan hapus yang terlama
            val sortedEntries = pelangganCache.entries
                .sortedBy { it.value.timestamp }
                .take(pelangganCache.size - maxSize)

            sortedEntries.forEach { entry ->
                pelangganCache.remove(entry.key)
            }

            Log.d(TAG, "🗑️ Evicted ${sortedEntries.size} old cache entries")
        }
    }

    /**
     * Clear semua cache
     */
    suspend fun clearAllCache() {
        cacheMutex.withLock {
            pelangganCache.clear()
            adminSummaryCache.clear()
            cabangSummaryCache.clear()
            globalSummaryCache = null
            pendingApprovalsCache.clear()
            pimpinanPelangganCache.clear()
            Log.d(TAG, "🗑️ Cleared all caches")
        }
    }

    /**
     * Clear cache untuk cabang tertentu
     */
    suspend fun clearCabangCache(cabangId: String) {
        cacheMutex.withLock {
            // Clear pelanggan cache untuk semua admin di cabang ini
            // Note: Ini memerlukan mapping admin -> cabang
            cabangSummaryCache.remove(cabangId)
            pendingApprovalsCache.remove(cabangId)
            pimpinanPelangganCache.remove(cabangId)
            Log.d(TAG, "🗑️ Cleared cache for cabang $cabangId")
        }
    }

    /**
     * Get cache statistics untuk debugging
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "pelangganCacheSize" to pelangganCache.size,
            "adminSummaryCacheSize" to adminSummaryCache.size,
            "cabangSummaryCacheSize" to cabangSummaryCache.size,
            "hasGlobalSummary" to (globalSummaryCache != null),
            "pendingApprovalsCacheSize" to pendingApprovalsCache.size,
            "pimpinanPelangganCacheSize" to pimpinanPelangganCache.size
        )
    }
}
