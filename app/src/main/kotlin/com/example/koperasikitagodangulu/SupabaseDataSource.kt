package com.example.koperasikitagodangulu.offline

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.JsonObject

/**
 * =========================================================================
 * SUPABASE DATA SOURCE — pengganti operasi RTDB, satu operasi per op-type
 * =========================================================================
 * Milestone 2. BELUM DIPANGGIL SIAPA PUN di jalur produksi: seluruh 29
 * pemanggilan RTDB SDK masih hidup di SyncManager.kt, dan penggantiannya
 * adalah Milestone 3.
 *
 * KENAPA BENTUKNYA "SATU FUNGSI PER OP-TYPE"
 * ------------------------------------------
 * Antrean offline di Room menyimpan `operationType` + `firebasePath` +
 * `dataJson` (PendingOperationDatabase.kt:22-28). Kontrak itu TIDAK BOLEH
 * berubah (batasan Anda: Room & SyncWorker tetap). Jadi lapisan ini menerima
 * bentuk yang sama persis dengan yang sudah diantre, dan menerjemahkannya ke
 * PostgREST. Milestone 3 tinggal mengganti isi `when (operationType)` di
 * SyncManager dari pemanggilan RTDB menjadi pemanggilan di sini.
 *
 * IDEMPOTENSI
 * -----------
 * Semua tulis memakai upsert dengan primary key deterministik (SupabaseIds),
 * jadi replay antrean tidak menggandakan. Ini menggantikan seluruh mekanisme
 * guard `_guardPinjamanKe` + transaksi RTDB: di Postgres, generasi pinjaman
 * adalah baris tersendiri, sehingga operasi usang menunjuk baris yang sudah
 * tidak menerima tulisan (lihat 001 §3).
 *
 * ⚠ SINTAKS SDK BELUM TERVERIFIKASI. Environment tempat ini ditulis tidak
 *   bisa mengunduh artefak (dl.google.com & Maven diblokir, 403 CONNECT),
 *   jadi tidak ada satu pun baris di file ini yang pernah dikompilasi.
 *   Nama fungsi PostgREST berubah antar minor supabase-kt 2.x; sesuaikan
 *   bila versi yang resolve di mesin Anda berbeda.
 * =========================================================================
 */
class SupabaseDataSource private constructor() {

    companion object {
        private const val TAG = "SupabaseDataSource"

        @Volatile private var INSTANCE: SupabaseDataSource? = null

        fun getInstance(): SupabaseDataSource =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SupabaseDataSource().also { INSTANCE = it }
            }

        // Nama tabel — cermin 001_schema_v2.sql
        const val T_NASABAH = "nasabah"
        const val T_PINJAMAN = "pinjaman"
        const val T_PEMBAYARAN = "pembayaran"
        const val T_JURNAL = "jurnal_transaksi"
        const val T_STATUS_KHUSUS = "pelanggan_status_khusus"
    }

    private val db get() = SupabaseClientProvider.client().postgrest

    /** Hasil operasi, sengaja meniru SaveResult agar pemanggil M3 mudah. */
    sealed class Hasil {
        object Sukses : Hasil()
        /** Ditolak permanen (RLS / constraint) — percuma di-retry. */
        data class Ditolak(val pesan: String) : Hasil()
        /** Gagal sementara (jaringan) — layak di-retry. */
        data class GagalSementara(val pesan: String) : Hasil()
    }

    // =====================================================================
    // ADD_PELANGGAN — satu node RTDB → dua baris (nasabah + pinjaman)
    // =====================================================================
    suspend fun upsertPelanggan(
        adminUid: String,
        pelangganId: String,
        data: Map<String, Any?>
    ): Hasil = jalankan("upsertPelanggan $pelangganId") {
        val nasabah = SupabaseMappers.barisNasabah(adminUid, pelangganId, data)
        val pinjaman = SupabaseMappers.barisPinjaman(adminUid, pelangganId, data)
            ?: return@jalankan Hasil.Ditolak(
                "status tidak dikenal: ${data["status"]} — operasi ditolak, bukan ditebak"
            )

        // Urutan wajib: nasabah dulu (induk), baru pinjaman (FK).
        // Catatan API: di supabase-kt 2.x `onConflict` adalah PARAMETER
        // bernama, bukan properti di dalam lambda (lambda-nya adalah
        // PostgrestRequestBuilder untuk filter/select).
        db.from(T_NASABAH).upsert(nasabah, onConflict = "id")
        db.from(T_PINJAMAN).upsert(pinjaman, onConflict = "id")
        Hasil.Sukses
    }

    // =====================================================================
    // UPDATE_PELANGGAN — patch parsial, dipecah ke dua tabel
    // =====================================================================
    suspend fun updatePelanggan(
        adminUid: String,
        pelangganId: String,
        pinjamanKe: Int,
        updateData: Map<String, Any?>
    ): Hasil = jalankan("updatePelanggan $pelangganId") {
        val (patchNasabah, patchPinjaman) = SupabaseMappers.patchTerpisah(updateData)

        if (patchNasabah.isNotEmpty()) {
            db.from(T_NASABAH).update(patchNasabah) {
                filter { eq("id", SupabaseIds.nasabah(adminUid, pelangganId)) }
            }
        }
        if (patchPinjaman.isNotEmpty()) {
            /* Tidak perlu guard generasi di sini: id-nya sudah mengandung
             * pinjamanKe, jadi patch dari generasi lama menyasar baris lama
             * dan TIDAK menyentuh generasi berjalan. Penurunan status pun
             * ditolak trigger koperasi.tg_pinjaman_no_downgrade (001 §3.2). */
            db.from(T_PINJAMAN).update(patchPinjaman) {
                filter { eq("id", SupabaseIds.pinjaman(adminUid, pelangganId, pinjamanKe)) }
            }
        }
        if (patchNasabah.isEmpty() && patchPinjaman.isEmpty()) {
            Log.w(TAG, "⚠️ updatePelanggan: tidak ada kolom dikenal dari ${updateData.keys}")
        }
        Hasil.Sukses
    }

    // =====================================================================
    // ADD_PEMBAYARAN / ADD_SUB_PEMBAYARAN
    // =====================================================================
    suspend fun insertPembayaran(
        adminUid: String,
        pelangganId: String,
        pinjamanKe: Int,
        pembayaran: Map<String, Any?>,
        isSub: Boolean
    ): Hasil = jalankan("insertPembayaran $pelangganId") {
        val baris = SupabaseMappers.barisPembayaran(
            adminUid, pelangganId, pinjamanKe, pembayaran,
            jenis = if (isSub) "tambah_bayar" else "cicilan"
        ) ?: return@jalankan Hasil.Ditolak(
            "pembayaran tidak valid (jumlah/tanggal/clientOpId kosong)"
        )

        /* ignoreDuplicates: replay antrean atas pembayaran yang sudah masuk
         * BUKAN kegagalan — client_op_id UNIQUE (001 §4) sudah menjamin uang
         * tidak dobel. Tanpa ini, retry akan dilaporkan sebagai error dan
         * operasi menumpuk di antrean selamanya. */
        db.from(T_PEMBAYARAN).upsert(
            baris,
            onConflict = "client_op_id",
            ignoreDuplicates = true
        )
        Hasil.Sukses
    }

    // =====================================================================
    // ADD_JURNAL_TRANSAKSI
    // =====================================================================
    // Catatan: 002_rls_policies.sql §9 TIDAK memberi GRANT INSERT pada
    // jurnal_transaksi untuk `authenticated` — audit trail hanya boleh ditulis
    // kode server. Jadi pemanggilan ini akan DITOLAK sampai ada RPC
    // SECURITY DEFINER pendampingnya. Sengaja dibiarkan gagal-keras daripada
    // diam-diam melonggarkan RLS.
    suspend fun insertJurnal(baris: JsonObject): Hasil =
        jalankan("insertJurnal") {
            db.from(T_JURNAL).insert(baris)
            Hasil.Sukses
        }

    // =====================================================================
    // REMOVE_* — penghapusan
    // =====================================================================
    suspend fun hapusStatusKhusus(cabangId: String, pelangganId: String): Hasil =
        jalankan("hapusStatusKhusus $pelangganId") {
            db.from(T_STATUS_KHUSUS).delete {
                filter { eq("id", SupabaseIds.statusKhusus(cabangId, pelangganId)) }
            }
            Hasil.Sukses
        }

    /**
     * RTDB `removeValue()` pada node pelanggan tidak punya padanan langsung:
     * 002 §5 sengaja TIDAK memberi policy DELETE pada `pinjaman` — riwayat
     * kredit tidak boleh lenyap. Penghapusan nasabah pun hanya untuk Pengawas
     * dan alurnya lewat tabel `permintaan`.
     *
     * Karena itu fungsi ini TIDAK menghapus apa pun. Ia sengaja mengembalikan
     * Ditolak agar Milestone 3 memutuskan padanan yang benar secara sadar
     * (kemungkinan besar: buat baris `permintaan` bertipe hapus_nasabah).
     */
    suspend fun hapusPelanggan(adminUid: String, pelangganId: String): Hasil {
        Log.w(TAG, "⛔ hapusPelanggan($pelangganId) tidak didukung — lihat 002 §5")
        return Hasil.Ditolak(
            "DELETE pelanggan tidak diizinkan skema; gunakan alur permintaan " +
                "hapus_nasabah (Pengawas). Diputuskan di Milestone 3."
        )
    }

    // =====================================================================
    // BACA — pengganti listener RTDB
    // =====================================================================
    /**
     * Baca seluruh nasabah milik satu admin, beserta pinjaman & pembayarannya.
     *
     * Satu request bersarang menggantikan pola RTDB lama yang menarik seluruh
     * subtree `pelanggan/{adminUid}`. RLS-lah yang menyaring baris (002 §4),
     * jadi filter admin_id di sini hanyalah optimasi, bukan pengaman.
     */
    suspend fun muatNasabahMilikAdmin(adminUid: String): Result<String> = runCatching {
        db.from(T_NASABAH).select(
            Columns.raw("*, pinjaman(*, pembayaran(*), jadwal_cicilan(*))")
        ) {
            filter { eq("admin_id", SupabaseIds.user(adminUid)) }
        }.data
    }

    /** Baca satu nasabah + relasinya. */
    suspend fun muatNasabah(adminUid: String, pelangganId: String): Result<String> = runCatching {
        db.from(T_NASABAH).select(
            Columns.raw("*, pinjaman(*, pembayaran(*), jadwal_cicilan(*))")
        ) {
            filter { eq("id", SupabaseIds.nasabah(adminUid, pelangganId)) }
        }.data
    }

    // =====================================================================
    // Pembungkus error
    // =====================================================================
    /**
     * Memisahkan gagal SEMENTARA (jaringan — layak retry) dari gagal PERMANEN
     * (RLS / constraint — retry tidak akan pernah berhasil).
     *
     * Pemisahan ini yang membuat antrean tidak berputar buta. Di jalur
     * Firebase, kelas masalah yang sama ditangani status REJECTED di Room
     * (PendingOperationDatabase.kt:110-121); Milestone 3 memetakan
     * Hasil.Ditolak ke status yang sama.
     */
    private suspend inline fun jalankan(label: String, blok: () -> Hasil): Hasil = try {
        blok()
    } catch (e: Exception) {
        val pesan = e.message.orEmpty()
        val permanen = listOf(
            "row-level security", "violates", "duplicate key", "permission denied",
            "42501", "23503", "23514", "PGRST"
        ).any { pesan.contains(it, ignoreCase = true) }
        if (permanen) {
            Log.e(TAG, "⛔ $label ditolak permanen: $pesan")
            Hasil.Ditolak(pesan)
        } else {
            Log.w(TAG, "⏳ $label gagal sementara: $pesan")
            Hasil.GagalSementara(pesan)
        }
    }
}
