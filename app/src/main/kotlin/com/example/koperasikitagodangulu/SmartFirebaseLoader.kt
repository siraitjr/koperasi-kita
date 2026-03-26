package com.example.koperasikitagodangulu.optimized

import android.content.Context
import android.util.Log
import com.example.koperasikitagodangulu.LocalStorage
import com.example.koperasikitagodangulu.Pelanggan
import com.example.koperasikitagodangulu.models.AdminSummary
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.koperasikitagodangulu.SubPembayaran
import com.example.koperasikitagodangulu.Pembayaran

/**
 * =========================================================================
 * SMART FIREBASE LOADER - VERSI DIPERBAIKI
 * =========================================================================
 *
 * PERBAIKAN DARI VERSI SEBELUMNYA:
 * ✅ DIHAPUS: Fallback calculateAdminSummaryFromPelanggan() yang load semua pelanggan
 * ✅ DIHAPUS: Fungsi loadAllPelangganForPimpinan() yang berbahaya
 * ✅ DITAMBAHKAN: Pembacaan field baru dari Cloud Functions
 *    - nasabahBaruHariIni
 *    - nasabahLunasHariIni
 *    - targetHariIni
 *    - pembayaranHariIni
 *    - nasabahMenunggu
 *
 * STRATEGI LOADING:
 * ┌─────────────────────────────────────────────────────────────┐
 * │  ADMIN LAPANGAN: Load detail pelanggan (ini memang perlu)   │
 * │  PIMPINAN: HANYA load summary + pending approvals           │
 * │  PENGAWAS: HANYA load global summary                        │
 * └─────────────────────────────────────────────────────────────┘
 *
 * PENTING:
 * Jika summary belum ada di Firebase, sistem akan return empty data
 * BUKAN load semua pelanggan. Pastikan Cloud Functions sudah di-deploy!
 */
class SmartFirebaseLoader(
    private val database: DatabaseReference,
    private val context: Context,
    private val cacheManager: FirebaseCacheManager = FirebaseCacheManager.getInstance()
) {
    companion object {
        private const val TAG = "SmartFirebaseLoader"
    }

    // =========================================================================
    // KONEKSI CHECK
    // =========================================================================

    /**
     * Cek apakah device sedang online
     */
    fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val nw = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(nw) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val nwInfo = cm.activeNetworkInfo ?: return false
                @Suppress("DEPRECATION")
                nwInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking connectivity: ${e.message}")
            false
        }
    }

    // =========================================================================
    // ADMIN LAPANGAN LOADING - DENGAN OFFLINE SUPPORT PENUH
    // =========================================================================

    /**
     * Load data untuk Admin Lapangan dengan dukungan offline
     *
     * CATATAN: Admin Lapangan MEMANG PERLU load detail pelanggan
     * karena mereka bekerja langsung dengan data nasabah
     */
    suspend fun loadDataForAdmin(
        adminUid: String,
        forceRefresh: Boolean = false
    ): LoadResult<List<Pelanggan>> = withContext(Dispatchers.IO) {
        try {
            val online = isOnline()
            Log.d(TAG, "📥 Loading data for admin: $adminUid")
            Log.d(TAG, "   📶 Online: $online | ForceRefresh: $forceRefresh")

            // STEP 1: Cek memory cache (kecuali force refresh)
            if (!forceRefresh) {
                val cached = cacheManager.getPelangganFromCache(adminUid)
                if (cached != null && cached.isNotEmpty()) {
                    Log.d(TAG, "✅ MEMORY CACHE HIT: ${cached.size} pelanggan")
                    return@withContext LoadResult.Success(
                        data = cached,
                        source = DataSource.MEMORY_CACHE
                    )
                }
            }

            // STEP 2: Cek koneksi dan load accordingly
            if (online) {
                // ONLINE: Load dari Firebase + simpan ke local
                return@withContext loadFromFirebaseWithLocalBackup(adminUid)
            } else {
                // OFFLINE: Load dari local storage
                Log.d(TAG, "📱 OFFLINE MODE: Loading from LocalStorage...")
                return@withContext loadFromLocalStorage(adminUid)
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading data: ${e.message}")

            // FALLBACK: Selalu coba local storage jika ada error
            Log.d(TAG, "🔄 Fallback to LocalStorage...")
            return@withContext loadFromLocalStorage(adminUid)
        }
    }

    /**
     * Load dari Firebase dan simpan ke local storage sebagai backup
     */
    private suspend fun loadFromFirebaseWithLocalBackup(
        adminUid: String
    ): LoadResult<List<Pelanggan>> {
        try {
            Log.d(TAG, "🌐 Loading from Firebase for admin: $adminUid")

            val snapshot = database.child("pelanggan").child(adminUid).get().await()

            val pelangganList = mutableListOf<Pelanggan>()
            snapshot.children.forEach { child ->
                try {
                    val pelanggan = child.getValue(Pelanggan::class.java)
                    if (pelanggan != null) {
                        // Data dari Firebase = sudah sync + sanitize null elements
                        @Suppress("UNCHECKED_CAST")
                        val rawList = pelanggan.pembayaranList as List<Pembayaran?>
                        val safeList = rawList.filterNotNull().map { p ->
                            @Suppress("UNCHECKED_CAST")
                            val rawSub = p.subPembayaran as List<SubPembayaran?>
                            p.copy(subPembayaran = rawSub.filterNotNull())
                        }
                        pelangganList.add(pelanggan.copy(isSynced = true, pembayaranList = safeList))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing pelanggan ${child.key}: ${e.message}")
                }
            }

            // PENTING: Merge dengan data lokal - SMART MERGE!
            val localData = try {
                LocalStorage.ambilDataPelanggan(context, adminUid)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading local data, clearing corrupt cache: ${e.message}")
                LocalStorage.hapusDataPelanggan(context, adminUid)
                emptyList()
            }

            // ✅ PERBAIKAN: Gabungkan data dengan logika yang lebih cerdas
            val mergedList = mutableListOf<Pelanggan>()
            val firebaseMap = pelangganList.associateBy { it.id }.toMutableMap()

            // ✅ PERBAIKAN #5: Merge logic yang lebih aman
            localData.forEach { localPelanggan ->
                val firebasePelanggan = firebaseMap[localPelanggan.id]

                when {
                    // Kasus 1: Data lokal belum sync (baru ditambahkan/diubah offline)
                    // → SELALU prioritaskan lokal
                    !localPelanggan.isSynced || localPelanggan.id.startsWith("local-") -> {
                        mergedList.add(localPelanggan)
                        firebaseMap.remove(localPelanggan.id)
                        Log.d(TAG, "📌 Using LOCAL (unsynced): ${localPelanggan.namaPanggilan}")
                    }

                    // Kasus 2: Data ada di Firebase dan lokal, keduanya sudah synced
                    firebasePelanggan != null -> {
                        // ✅ FIX: Safety check — jangan sampai kehilangan pembayaran!
                        // Jika lokal punya lebih banyak pembayaran, prioritaskan lokal
                        // (bisa terjadi jika sync pembayaran belum selesai tapi isSynced=true)
                        val localPayCount = localPelanggan.pembayaranList.sumOf { 1 + it.subPembayaran.size }
                        val firebasePayCount = firebasePelanggan.pembayaranList.sumOf { 1 + it.subPembayaran.size }
                        if (localPayCount > firebasePayCount) {
                            mergedList.add(localPelanggan)
                            firebaseMap.remove(localPelanggan.id)
                            Log.d(TAG, "⚠️ Using LOCAL (more payments: local=$localPayCount > firebase=$firebasePayCount): ${localPelanggan.namaPanggilan}")
                        } else {
                            mergedList.add(firebasePelanggan)
                            firebaseMap.remove(firebasePelanggan.id)
                            Log.d(TAG, "📌 Using FIREBASE (synced, server is truth): ${firebasePelanggan.namaPanggilan}")
                        }
                    }

                    // Kasus 3: Data hanya ada di lokal dan sudah synced, tapi TIDAK ada di Firebase
                    localPelanggan.isSynced -> {
                        // ✅ SAFETY CHECK: Hanya anggap "dihapus di server" jika Firebase
                        //    berhasil load data (pelangganList tidak kosong).
                        //    Jika Firebase kosong/gagal, PERTAHANKAN data lokal!
                        if (pelangganList.isNotEmpty()) {
                            Log.d(TAG, "🗑️ Skipping LOCAL (synced but deleted on server): ${localPelanggan.namaPanggilan}")
                            // Tidak ditambahkan ke mergedList
                        } else {
                            // Firebase mungkin gagal/timeout — pertahankan data lokal
                            mergedList.add(localPelanggan)
                            Log.d(TAG, "⚠️ Keeping LOCAL (Firebase may be incomplete): ${localPelanggan.namaPanggilan}")
                        }
                    }

                    // Kasus 4: Data hanya di lokal dan belum synced (catch-all safety)
                    else -> {
                        mergedList.add(localPelanggan)
                        Log.d(TAG, "📌 Keeping LOCAL (safety net): ${localPelanggan.namaPanggilan}")
                    }
                }
            }

            // Tambahkan sisa data dari Firebase yang tidak ada di lokal
            firebaseMap.values.forEach { firebasePelanggan ->
                mergedList.add(firebasePelanggan)
                Log.d(TAG, "📌 Adding from FIREBASE (new on server): ${firebasePelanggan.namaPanggilan}")
            }

            // Simpan ke memory cache
            cacheManager.cachePelanggan(adminUid, mergedList)

            // PENTING: Simpan ke local storage untuk offline access
            LocalStorage.simpanDataPelanggan(context, mergedList, adminUid)

            val unsyncedCount = mergedList.count { !it.isSynced || it.id.startsWith("local-") }

            Log.d(TAG, "✅ Firebase loaded: ${pelangganList.size} from server")
            Log.d(TAG, "   📌 Total merged: ${mergedList.size} ($unsyncedCount unsynced)")

            return LoadResult.Success(
                data = mergedList,
                source = DataSource.FIREBASE,
                unsyncedCount = unsyncedCount
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Firebase load failed: ${e.message}")
            Log.d(TAG, "🔄 Falling back to LocalStorage...")

            // Fallback ke local jika Firebase gagal
            return loadFromLocalStorage(adminUid)
        }
    }

    /**
     * Parse lastUpdated string ke timestamp untuk perbandingan
     */
    private fun parseLastUpdated(lastUpdated: String): Long {
        return try {
            if (lastUpdated.isBlank()) return 0L
            val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            format.parse(lastUpdated)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Load dari local storage (untuk offline mode)
     */
    private suspend fun loadFromLocalStorage(
        adminUid: String
    ): LoadResult<List<Pelanggan>> {
        try {
            Log.d(TAG, "📱 Loading from LocalStorage for admin: $adminUid")

            val localData = LocalStorage.ambilDataPelanggan(context, adminUid)

            if (localData.isEmpty()) {
                Log.w(TAG, "⚠️ No local data found")
                return LoadResult.Success(
                    data = emptyList(),
                    source = DataSource.LOCAL_STORAGE,
                    isOfflineData = true
                )
            }

            // Simpan ke memory cache untuk akses cepat
            cacheManager.cachePelanggan(adminUid, localData)

            // Hitung data yang belum sync
            val unsyncedCount = localData.count { !it.isSynced || it.id.startsWith("local-") }

            Log.d(TAG, "✅ LocalStorage loaded: ${localData.size} pelanggan")
            Log.d(TAG, "   ⏳ Unsynced: $unsyncedCount items")

            return LoadResult.Success(
                data = localData,
                source = DataSource.LOCAL_STORAGE,
                isOfflineData = true,
                unsyncedCount = unsyncedCount
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ LocalStorage load failed: ${e.message}")
            return LoadResult.Error("Gagal memuat data lokal: ${e.message}")
        }
    }

    // =========================================================================
    // SYNC OPERATIONS
    // =========================================================================

    /**
     * Sync data offline ke Firebase saat kembali online
     */
    suspend fun syncOfflineData(adminUid: String): SyncResult = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            Log.w(TAG, "⚠️ Cannot sync: Device is offline")
            return@withContext SyncResult(success = false, message = "Device offline")
        }

        try {
            Log.d(TAG, "🔄 Starting sync for admin: $adminUid")

            // Load data lokal
            val localData = LocalStorage.ambilDataPelanggan(context, adminUid)

            // Filter data yang belum sync
            val unsyncedData = localData.filter {
                !it.isSynced || it.id.startsWith("local-")
            }

            if (unsyncedData.isEmpty()) {
                Log.d(TAG, "✅ No data to sync")
                return@withContext SyncResult(success = true, syncedCount = 0)
            }

            Log.d(TAG, "📤 Syncing ${unsyncedData.size} items...")

            var syncedCount = 0
            val errors = mutableListOf<String>()
            val syncedPelangganList = mutableListOf<Pelanggan>()

            unsyncedData.forEach { pelanggan ->
                try {
                    // Generate ID baru jika masih local-
                    val finalId = if (pelanggan.id.startsWith("local-")) {
                        database.child("pelanggan").child(adminUid).push().key ?: pelanggan.id
                    } else {
                        pelanggan.id
                    }

                    // Simpan ke Firebase dengan isSynced = true
                    val syncedPelanggan = pelanggan.copy(
                        id = finalId,
                        isSynced = true
                    )

                    database.child("pelanggan")
                        .child(adminUid)
                        .child(finalId)
                        .setValue(syncedPelanggan)
                        .await()

                    syncedPelangganList.add(syncedPelanggan)
                    syncedCount++
                    Log.d(TAG, "✅ Synced: ${pelanggan.namaPanggilan} (${pelanggan.id} → $finalId)")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to sync ${pelanggan.id}: ${e.message}")
                    errors.add(pelanggan.namaPanggilan)
                }
            }

            // Update local storage dengan data yang sudah sync
            if (syncedCount > 0) {
                // Reload dari Firebase untuk mendapat state terbaru
                loadFromFirebaseWithLocalBackup(adminUid)
            }

            Log.d(TAG, "✅ Sync completed: $syncedCount/${unsyncedData.size} items")

            SyncResult(
                success = errors.isEmpty(),
                syncedCount = syncedCount,
                failedCount = errors.size,
                failedItems = errors,
                message = if (errors.isEmpty()) {
                    "Semua data berhasil disinkronkan"
                } else {
                    "Beberapa data gagal sync: ${errors.joinToString(", ")}"
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Sync failed: ${e.message}")
            SyncResult(success = false, message = "Sync gagal: ${e.message}")
        }
    }

    /**
     * Cek apakah ada data yang perlu di-sync
     */
    suspend fun hasUnsyncedData(adminUid: String): Boolean {
        val localData = LocalStorage.ambilDataPelanggan(context, adminUid)
        return localData.any { !it.isSynced || it.id.startsWith("local-") }
    }

    /**
     * Hitung jumlah data yang belum sync
     */
    suspend fun getUnsyncedCount(adminUid: String): Int {
        val localData = LocalStorage.ambilDataPelanggan(context, adminUid)
        return localData.count { !it.isSynced || it.id.startsWith("local-") }
    }

    // =========================================================================
    // PIMPINAN LOADING - HANYA SUMMARY, TIDAK LOAD DETAIL PELANGGAN
    // =========================================================================

    /**
     * Load data untuk Pimpinan Cabang
     *
     * PENTING: Fungsi ini HANYA load summary, TIDAK load detail pelanggan!
     * Ini menghemat bandwidth 99%+ dibanding load semua pelanggan.
     */
    suspend fun loadDataForPimpinan(
        cabangId: String,
        forceRefresh: Boolean = false
    ): PimpinanLoadResult = withContext(Dispatchers.IO) {

        if (!isOnline()) {
            Log.w(TAG, "⚠️ Pimpinan mode requires internet")
            return@withContext PimpinanLoadResult(
                adminSummaries = emptyList(),
                pendingApprovals = emptyList(),
                cabangSummary = null,
                success = false,
                error = "Tidak ada koneksi internet. Fitur Pimpinan memerlukan koneksi untuk melihat data terkini."
            )
        }

        try {
            Log.d(TAG, "📥 Loading data for pimpinan cabang: $cabangId")

            // Load admin list
            val adminList = loadAdminListForCabang(cabangId)

            // ✅ Load summary per admin (BUKAN detail pelanggan!)
            val adminSummaries = loadAdminSummariesForCabang(cabangId, adminList, forceRefresh)

            // Load pending approvals (hanya yang menunggu, bukan semua)
            val pendingApprovals = loadPendingApprovalsOptimized(cabangId, forceRefresh)

            // Hitung cabang summary
            val cabangSummary = calculateCabangSummary(cabangId, adminSummaries)

            Log.d(TAG, "✅ Pimpinan loaded: ${adminSummaries.size} admins, ${pendingApprovals.size} pending")

            PimpinanLoadResult(
                adminSummaries = adminSummaries,
                pendingApprovals = pendingApprovals,
                cabangSummary = cabangSummary,
                success = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading pimpinan data: ${e.message}")
            PimpinanLoadResult(
                adminSummaries = emptyList(),
                pendingApprovals = emptyList(),
                cabangSummary = null,
                success = false,
                error = e.message
            )
        }
    }

    private suspend fun loadAdminListForCabang(cabangId: String): List<String> {
        val snapshot = database.child("metadata").child("cabang")
            .child(cabangId).child("adminList").get().await()
        return snapshot.children.mapNotNull { it.getValue(String::class.java) }
    }

    private suspend fun loadAdminSummariesForCabang(
        cabangId: String,
        adminList: List<String>,
        forceRefresh: Boolean
    ): List<AdminSummary> = coroutineScope {

        if (!forceRefresh) {
            val cached = cacheManager.getAllAdminSummariesFromCache()
            if (cached != null && cached.isNotEmpty()) {
                val filtered = cached.filter { it.cabang == cabangId }
                if (filtered.size == adminList.size) {
                    return@coroutineScope filtered
                }
            }
        }

        val jobs = adminList.map { adminUid ->
            async { loadSingleAdminSummary(adminUid, cabangId) }
        }

        val summaries = jobs.awaitAll().filterNotNull()
        cacheManager.cacheMultipleAdminSummaries(summaries)
        summaries
    }

    /**
     * Load summary untuk satu admin
     *
     * ✅ PERBAIKAN: Tidak ada fallback ke calculateAdminSummaryFromPelanggan()
     * Jika summary tidak ada, return empty AdminSummary dengan warning log
     *
     * PENTING: Pastikan Cloud Functions sudah di-deploy agar summary tersedia!
     */
    private suspend fun loadSingleAdminSummary(adminUid: String, cabangId: String): AdminSummary? {
        return try {
            val summarySnap = database.child("summary").child("perAdmin")
                .child(adminUid).get().await()

            val adminMeta = database.child("metadata").child("admins")
                .child(adminUid).get().await()

            if (summarySnap.exists()) {
                // ✅ BERHASIL: Summary ada di Firebase
                AdminSummary(
                    adminId = adminUid,
                    adminName = adminMeta.child("name").getValue(String::class.java)
                        ?: adminMeta.child("nama").getValue(String::class.java)
                        ?: "Admin",
                    adminEmail = adminMeta.child("email").getValue(String::class.java) ?: "",
                    cabang = cabangId,
                    // Field standar
                    totalPelanggan = summarySnap.child("totalNasabah").getValue(Int::class.java) ?: 0,
                    nasabahAktif = summarySnap.child("nasabahAktif").getValue(Int::class.java) ?: 0,
                    nasabahLunas = summarySnap.child("nasabahLunas").getValue(Int::class.java) ?: 0,
                    nasabahMenunggu = summarySnap.child("nasabahMenunggu").getValue(Int::class.java) ?: 0,
                    // ✅ TAMBAHAN: Field baru dari Cloud Functions
                    nasabahBaruHariIni = summarySnap.child("nasabahBaruHariIni").getValue(Int::class.java) ?: 0,
                    nasabahLunasHariIni = summarySnap.child("nasabahLunasHariIni").getValue(Int::class.java) ?: 0,
                    targetHariIni = summarySnap.child("targetHariIni").getValue(Long::class.java) ?: 0L,
                    pembayaranHariIni = summarySnap.child("pembayaranHariIni").getValue(Long::class.java) ?: 0L,
                    // Field finansial
                    totalPinjamanAktif = summarySnap.child("totalPinjamanAktif").getValue(Long::class.java) ?: 0L,
                    totalPiutang = summarySnap.child("totalPiutang").getValue(Long::class.java) ?: 0L
                )
            } else {
                // ⚠️ PERBAIKAN: TIDAK fallback ke load semua pelanggan!
                // Return empty AdminSummary sebagai gantinya
                Log.w(TAG, "⚠️ Summary tidak ada untuk admin $adminUid")
                Log.w(TAG, "   → Pastikan Cloud Functions sudah di-deploy!")
                Log.w(TAG, "   → Jalankan weeklyFullRecalc untuk generate summary")

                AdminSummary(
                    adminId = adminUid,
                    adminName = adminMeta.child("name").getValue(String::class.java)
                        ?: adminMeta.child("nama").getValue(String::class.java)
                        ?: "Admin",
                    adminEmail = adminMeta.child("email").getValue(String::class.java) ?: "",
                    cabang = cabangId,
                    totalPelanggan = 0,
                    nasabahAktif = 0,
                    nasabahLunas = 0,
                    nasabahMenunggu = 0,
                    nasabahBaruHariIni = 0,
                    nasabahLunasHariIni = 0,
                    targetHariIni = 0L,
                    pembayaranHariIni = 0L,
                    totalPinjamanAktif = 0L,
                    totalPiutang = 0L
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading summary for $adminUid: ${e.message}")
            null
        }
    }

    // ❌ DIHAPUS: calculateAdminSummaryFromPelanggan()
    // Fungsi ini BERBAHAYA karena load semua pelanggan!
    // Jika summary tidak ada, sekarang return empty data dengan warning

    private fun calculateCabangSummary(cabangId: String, summaries: List<AdminSummary>): FirebaseCacheManager.CabangSummaryData {
        return FirebaseCacheManager.CabangSummaryData(
            cabangId = cabangId,
            totalNasabah = summaries.sumOf { it.totalPelanggan },
            nasabahAktif = summaries.sumOf { it.nasabahAktif },
            totalPinjamanAktif = summaries.sumOf { it.totalPinjamanAktif }
        )
    }

    // =========================================================================
    // PENDING APPROVALS - Load hanya yang menunggu approval
    // =========================================================================

    /**
     * Load pending approvals secara optimal
     *
     * Fungsi ini load detail pelanggan HANYA untuk yang menunggu approval,
     * bukan semua pelanggan. Jumlahnya biasanya sedikit (0-10 orang).
     */
    suspend fun loadPendingApprovalsOptimized(cabangId: String, forceRefresh: Boolean = false): List<Pelanggan> = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext emptyList()

        try {
            if (!forceRefresh) {
                cacheManager.getPendingApprovalsFromCache(cabangId)?.let { return@withContext it }
            }

            val snap = database.child("pengajuan_approval").child(cabangId)
                .orderByChild("status").equalTo("Menunggu Approval").get().await()

            val map = mutableMapOf<String, MutableList<String>>()

            // ✅ PERBAIKAN: Filter berdasarkan dualApprovalInfo
            snap.children.forEach { c ->
                val data = c.value as? Map<*, *>
                val admin = data?.get("adminUid") as? String
                val id = data?.get("pelangganId") as? String

                // ✅ TAMBAH: Cek dualApprovalInfo
                val dualApprovalInfo = data?.get("dualApprovalInfo") as? Map<*, *>
                val pimpinanApproval = dualApprovalInfo?.get("pimpinanApproval") as? Map<*, *>
                val pimpinanStatus = pimpinanApproval?.get("status") as? String ?: "pending"
                val approvalPhase = dualApprovalInfo?.get("approvalPhase") as? String ?: "awaiting_pimpinan"

                // ✅ Hanya tampilkan jika:
                // 1. Pimpinan BELUM aksi (status pending), DAN
                // 2. Phase masih awaiting_pimpinan atau single approval (tanpa dualApprovalInfo)
                val isPhase1OrSingleApproval = approvalPhase == "awaiting_pimpinan" ||
                        approvalPhase.isEmpty() ||
                        dualApprovalInfo == null

                if (admin != null && id != null && pimpinanStatus == "pending" && isPhase1OrSingleApproval) {
                    map.getOrPut(admin) { mutableListOf() }.add(id)
                }
            }

            // Load detail hanya untuk pelanggan yang memenuhi kriteria
            val results = coroutineScope {
                map.map { (admin, ids) ->
                    async {
                        ids.mapNotNull { id ->
                            try {
                                database.child("pelanggan").child(admin).child(id).get().await()
                                    .getValue(Pelanggan::class.java)
                            } catch (e: Exception) { null }
                        }
                    }
                }.awaitAll().flatten()
            }

            // ✅ PERBAIKAN: Double-check approval phase dari data pelanggan aktual
            val pending = results.filter { pelanggan ->
                if (pelanggan.status != "Menunggu Approval") return@filter false
                val phase = pelanggan.dualApprovalInfo?.approvalPhase ?: ""
                phase == "awaiting_pimpinan" || phase.isEmpty() || pelanggan.dualApprovalInfo == null
            }.toMutableList()

            // ✅ SELF-HEALING: Cari pengajuan yang ada di pelanggan tapi tidak ada di pengajuan_approval
            val loadedIds = pending.map { it.id }.toSet()
            try {
                val adminListSnap = database.child("metadata").child("cabang")
                    .child(cabangId).child("adminList").get().await()
                val adminList = adminListSnap.children.mapNotNull { it.getValue(String::class.java) }

                coroutineScope {
                    adminList.map { adminUid ->
                        async {
                            try {
                                val pelSnap = database.child("pelanggan").child(adminUid)
                                    .orderByChild("status").equalTo("Menunggu Approval")
                                    .get().await()

                                pelSnap.children.mapNotNull { child ->
                                    val pel = child.getValue(Pelanggan::class.java)
                                    if (pel != null && !loadedIds.contains(pel.id)) {
                                        val phase = pel.dualApprovalInfo?.approvalPhase ?: ""
                                        val isPhase1 = phase == "awaiting_pimpinan" || phase.isEmpty() || pel.dualApprovalInfo == null
                                        if (isPhase1) {
                                            Log.w(TAG, "🔧 SELF-HEALING: Orphan ditemukan: ${pel.namaPanggilan}")
                                            pel.copy(id = child.key ?: pel.id, adminUid = adminUid, cabangId = cabangId)
                                        } else null
                                    } else null
                                }
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }.also { orphans ->
                    if (orphans.isNotEmpty()) {
                        pending.addAll(orphans)
                        Log.d(TAG, "🔧 SELF-HEALING: Recovered ${orphans.size} orphaned pengajuan")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Self-healing check gagal (non-critical): ${e.message}")
            }

            cacheManager.cachePendingApprovals(cabangId, pending)


            Log.d(TAG, "✅ Loaded ${pending.size} pending approvals (filtered by phase)")
            pending
        } catch (e: Exception) {
            Log.e(TAG, "Error loading approvals: ${e.message}")
            emptyList()
        }
    }

    // ❌ DIHAPUS: loadAllPelangganForPimpinan()
    // Fungsi ini BERBAHAYA karena load SEMUA pelanggan untuk Pimpinan
    // Pimpinan tidak perlu data detail, cukup summary saja

    // =========================================================================
    // PENGAWAS LOADING - HANYA GLOBAL SUMMARY
    // =========================================================================

    /**
     * Load data untuk Pengawas
     * Hanya load global summary, sangat ringan
     */
    suspend fun loadDataForPengawas(forceRefresh: Boolean = false): PengawasLoadResult = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            return@withContext PengawasLoadResult(null, false, error = "Memerlukan koneksi internet")
        }

        try {
            if (!forceRefresh) {
                cacheManager.getGlobalSummaryFromCache()?.let {
                    return@withContext PengawasLoadResult(it, true, fromCache = true)
                }
            }

            val snap = database.child("summary").child("global").get().await()
            val summary = FirebaseCacheManager.GlobalSummaryData(
                totalNasabah = snap.child("totalNasabah").getValue(Int::class.java) ?: 0,
                totalPinjamanAktif = snap.child("totalPinjamanAktif").getValue(Long::class.java) ?: 0L,
                totalTunggakan = snap.child("totalTunggakan").getValue(Long::class.java) ?: 0L,
                pembayaranHariIni = snap.child("pembayaranHariIni").getValue(Int::class.java) ?: 0
            )

            cacheManager.cacheGlobalSummary(summary)
            PengawasLoadResult(summary, true)
        } catch (e: Exception) {
            PengawasLoadResult(null, false, error = e.message)
        }
    }

    // =========================================================================
    // RESULT CLASSES
    // =========================================================================

    enum class DataSource { FIREBASE, MEMORY_CACHE, LOCAL_STORAGE }

    sealed class LoadResult<T> {
        data class Success<T>(
            val data: T,
            val source: DataSource,
            val isOfflineData: Boolean = false,
            val unsyncedCount: Int = 0
        ) : LoadResult<T>()

        data class Error<T>(val message: String) : LoadResult<T>()
    }

    data class SyncResult(
        val success: Boolean,
        val syncedCount: Int = 0,
        val failedCount: Int = 0,
        val failedItems: List<String> = emptyList(),
        val message: String = ""
    )

    data class PimpinanLoadResult(
        val adminSummaries: List<AdminSummary>,
        val pendingApprovals: List<Pelanggan>,
        val cabangSummary: FirebaseCacheManager.CabangSummaryData?,
        val success: Boolean,
        val error: String? = null
    )

    data class PengawasLoadResult(
        val globalSummary: FirebaseCacheManager.GlobalSummaryData?,
        val success: Boolean,
        val fromCache: Boolean = false,
        val error: String? = null
    )
}
