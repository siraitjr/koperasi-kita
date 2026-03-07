package com.example.koperasikitagodangulu.optimized

/**
 * =========================================================================
 * KONSTANTA OPTIMASI FIREBASE
 * =========================================================================
 *
 * File ini berisi konstanta-konstanta yang digunakan untuk mengontrol
 * perilaku optimasi Firebase di seluruh aplikasi.
 */

object FirebaseOptimizationConfig {

    // =========================================================================
    // PAGINATION CONFIG
    // =========================================================================

    /** Jumlah nasabah per halaman untuk pagination */
    const val PAGE_SIZE_NASABAH = 50

    /** Jumlah nasabah minimal sebelum pagination aktif */
    const val PAGINATION_THRESHOLD = 100

    // =========================================================================
    // CACHE CONFIG
    // =========================================================================

    /** Durasi cache valid dalam milidetik (5 menit) */
    const val CACHE_DURATION_MS = 5 * 60 * 1000L

    /** Durasi cache summary dalam milidetik (2 menit) */
    const val SUMMARY_CACHE_DURATION_MS = 2 * 60 * 1000L

    /** Maksimum item di memory cache */
    const val MAX_MEMORY_CACHE_SIZE = 500

    // =========================================================================
    // LISTENER CONFIG
    // =========================================================================

    /**
     * Gunakan realtime listener hanya untuk node-node ini:
     * - admin_notifications (perlu realtime untuk notifikasi)
     * - pengajuan_approval (pimpinan perlu tahu langsung ada pengajuan baru)
     */
    val REALTIME_NODES = setOf(
        "admin_notifications",
        "pengajuan_approval"
    )

    /**
     * Node yang TIDAK perlu realtime listener:
     * - pelanggan (load on-demand)
     * - summary (load berkala)
     */
    val ON_DEMAND_NODES = setOf(
        "pelanggan",
        "summary"
    )

    // =========================================================================
    // DEBOUNCE CONFIG
    // =========================================================================

    /** Delay debounce untuk refresh dalam milidetik */
    const val REFRESH_DEBOUNCE_MS = 1000L

    /** Delay debounce untuk search dalam milidetik */
    const val SEARCH_DEBOUNCE_MS = 300L

    // =========================================================================
    // ROLE-BASED LOADING CONFIG
    // =========================================================================

    /**
     * Konfigurasi loading berdasarkan role:
     * - ADMIN: Load detail nasabah milik sendiri
     * - PIMPINAN: Load summary per admin + pending approvals
     * - PENGAWAS: Load global summary saja
     */
    object RoleConfig {
        /** Admin load semua nasabah miliknya */
        const val ADMIN_LOAD_FULL_DATA = true

        /** Pimpinan TIDAK load detail nasabah, hanya summary */
        const val PIMPINAN_LOAD_FULL_DATA = false

        /** Pengawas hanya load global summary */
        const val PENGAWAS_LOAD_FULL_DATA = false
    }
}

/**
 * Data class untuk menyimpan cache metadata
 */
data class CacheMetadata(
    val lastUpdated: Long = 0L,
    val isValid: Boolean = false,
    val source: CacheSource = CacheSource.NONE
)

enum class CacheSource {
    NONE,
    MEMORY,
    LOCAL_STORAGE,
    FIREBASE
}

/**
 * Data class untuk lightweight pelanggan (hanya field yang sering dipakai)
 * Digunakan untuk list view, BUKAN untuk detail view
 */
data class PelangganLight(
    val id: String = "",
    val namaKtp: String = "",
    val namaPanggilan: String = "",
    val noHp: String = "",
    val status: String = "",
    val totalPelunasan: Int = 0,
    val adminUid: String = "",
    val cabangId: String = "",
    val pinjamanKe: Int = 1,
    // Computed fields (tidak disimpan, dihitung saat load)
    val totalDibayar: Int = 0,
    val sisaHutang: Int = 0
)

/**
 * Extension function untuk convert Pelanggan ke PelangganLight
 */
fun com.example.koperasikitagodangulu.Pelanggan.toLight(): PelangganLight {
    val totalDibayar = this.pembayaranList.sumOf { pembayaran ->
        pembayaran.jumlah + pembayaran.subPembayaran.sumOf { it.jumlah }
    }
    return PelangganLight(
        id = this.id,
        namaKtp = this.namaKtp,
        namaPanggilan = this.namaPanggilan,
        noHp = this.noHp,
        status = this.status,
        totalPelunasan = this.totalPelunasan,
        adminUid = this.adminUid,
        cabangId = this.cabangId,
        pinjamanKe = this.pinjamanKe,
        totalDibayar = totalDibayar,
        sisaHutang = this.totalPelunasan - totalDibayar
    )
}