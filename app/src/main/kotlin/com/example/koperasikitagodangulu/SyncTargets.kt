package com.example.koperasikitagodangulu.offline

/**
 * =========================================================================
 * SASARAN SINKRONISASI — satu sumber kebenaran untuk path antrean
 * =========================================================================
 * Kolom `firebasePath` di Room (PendingOperationDatabase.kt:25) menyimpan
 * string path RTDB. Selama transisi, string itu punya DUA pembaca:
 *
 *   1. SyncManager  → memakainya sebagai path RTDB (jalur produksi, masih hidup)
 *   2. SupabaseDataSource → Milestone 3 akan mem-parse-nya jadi
 *      (tabel, id) Postgres
 *
 * Sebelumnya string ini dirakit ad-hoc di lima tempat berbeda di dalam
 * OfflineRepository. Kalau salah satu berubah sedikit saja, operasi yang
 * SUDAH terlanjur mengantre di perangkat admin lapangan tidak akan cocok
 * lagi dengan parser-nya — dan itu berarti pembayaran yang belum ter-sync
 * gagal diam-diam. Dikumpulkan di sini supaya kedua pembaca melihat bentuk
 * yang sama.
 *
 * ⚠ FORMAT STRING DI BAWAH TIDAK BOLEH BERUBAH. Bukan karena estetika:
 *   antrean Room di perangkat yang sedang offline sudah berisi string versi
 *   lama, dan perangkat itu bisa baru tersambung berhari-hari kemudian.
 *   Menambah format baru boleh; mengubah yang lama tidak.
 * =========================================================================
 */
object SyncTargets {

    // --- Path RTDB (bentuk lama, WAJIB dipertahankan apa adanya) ----------

    fun pelanggan(adminUid: String, pelangganId: String): String =
        "pelanggan/$adminUid/$pelangganId"

    fun jurnalTransaksi(cabangId: String, yearMonth: String, pushKey: String): String =
        "jurnal_transaksi/$cabangId/$yearMonth/$pushKey"

    fun jurnalTransaksiParent(cabangId: String, yearMonth: String): String =
        "jurnal_transaksi/$cabangId/$yearMonth"

    fun riwayatPinjaman(adminUid: String, pelangganId: String, pinjamanKe: Int): String =
        "riwayat_pinjaman/$adminUid/$pelangganId/$pinjamanKe"

    fun pelangganStatusKhusus(cabangId: String, pelangganId: String): String =
        "pelanggan_status_khusus/$cabangId/$pelangganId"

    // --- Pembacaan balik: path RTDB → identitas entitas -------------------
    // Dipakai Milestone 3 untuk menerjemahkan baris antrean lama menjadi
    // operasi Postgres tanpa perlu mengubah skema Room.

    data class RefPelanggan(val adminUid: String, val pelangganId: String)

    /** "pelanggan/{adminUid}/{pelangganId}" → Ref, atau null bila bukan. */
    fun parsePelanggan(path: String): RefPelanggan? {
        val p = path.trim('/').split('/')
        return if (p.size >= 3 && p[0] == "pelanggan") RefPelanggan(p[1], p[2]) else null
    }

    data class RefRiwayat(val adminUid: String, val pelangganId: String, val pinjamanKe: Int)

    fun parseRiwayatPinjaman(path: String): RefRiwayat? {
        val p = path.trim('/').split('/')
        if (p.size < 4 || p[0] != "riwayat_pinjaman") return null
        val ke = p[3].toIntOrNull() ?: return null
        return RefRiwayat(p[1], p[2], ke)
    }

    data class RefJurnal(val cabangId: String, val yearMonth: String, val pushKey: String)

    /** "jurnal_transaksi/{cabangId}/{YYYY-MM}/{pushKey}" → Ref. */
    fun parseJurnal(path: String): RefJurnal? {
        val p = path.trim('/').split('/')
        return if (p.size >= 4 && p[0] == "jurnal_transaksi")
            RefJurnal(p[1], p[2], p[3]) else null
    }

    data class RefStatusKhusus(val cabangId: String, val pelangganId: String)

    fun parseStatusKhusus(path: String): RefStatusKhusus? {
        val p = path.trim('/').split('/')
        return if (p.size >= 3 && p[0] == "pelanggan_status_khusus")
            RefStatusKhusus(p[1], p[2]) else null
    }
}
