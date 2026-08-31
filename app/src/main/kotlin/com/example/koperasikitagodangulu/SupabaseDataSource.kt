package com.example.koperasikitagodangulu.offline

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    // ADD_JURNAL_TRANSAKSI → RPC (Milestone 3)
    // =====================================================================
    // `jurnal_transaksi` tidak punya GRANT INSERT untuk `authenticated`
    // (002 §10): audit trail hanya boleh ditulis kode server. Jalannya lewat
    // koperasi.rpc_catat_jurnal (007), SECURITY DEFINER yang memvalidasi
    // pemanggil sendiri — bukan dengan melonggarkan RLS.
    //
    // Idempotensi ada di sisi server: RPC mengembalikan id lama bila
    // client_op_id sudah pernah tercatat, jadi replay antrean menerima sukses
    // alih-alih duplicate-key error.
    suspend fun catatJurnal(baris: JsonObject): Hasil =
        jalankan("catatJurnal") {
            SupabaseClientProvider.client().postgrest.rpc("rpc_catat_jurnal", baris)
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
     * Padanan RTDB `removeValue()` pada node pelanggan (alur cairkanSimpanan:
     * nasabah lunas total) → SOFT DELETE lewat koperasi.rpc_arsipkan_nasabah.
     *
     * Barisnya sengaja TIDAK dihapus: `pembayaran` dan `jurnal_transaksi`
     * menunjuk ke sana, dan 002 §5 bahkan tidak memberi policy DELETE pada
     * `pinjaman` — riwayat keuangan tidak boleh lenyap. Yang berubah hanya
     * penanda `arsip_at`.
     *
     * RPC menolak mengarsipkan nasabah yang masih punya pinjaman hidup —
     * pengaman yang di RTDB tidak ada sama sekali (removeValue() menghapus
     * apa pun keadaannya). Idempoten: memutar ulang operasi ini tidak
     * menggeser stempel waktu arsip aslinya.
     */
    suspend fun arsipkanNasabah(
        adminUid: String,
        pelangganId: String,
        alasan: String = "Pencairan simpanan — nasabah lunas total"
    ): Hasil = jalankan("arsipkanNasabah $pelangganId") {
        val params = buildJsonObject {
            put("p_nasabah_id", SupabaseIds.nasabah(adminUid, pelangganId))
            put("p_alasan", alasan)
        }
        SupabaseClientProvider.client().postgrest.rpc("rpc_arsipkan_nasabah", params)
        Hasil.Sukses
    }

    /**
     * Generasi pinjaman lama (ADD_RIWAYAT_PINJAMAN). Di RTDB ini menulis ke
     * `riwayat_pinjaman/{admin}/{pid}/{N}`; di Postgres generasi lama BUKAN
     * arsip terpisah melainkan baris `pinjaman` biasa dengan pinjaman_ke = N
     * (001 §3), jadi cukup upsert ke tabel yang sama.
     */
    suspend fun upsertGenerasiPinjaman(
        adminUid: String,
        pelangganId: String,
        pinjamanKe: Int,
        data: Map<String, Any?>
    ): Hasil = jalankan("upsertGenerasi $pelangganId/$pinjamanKe") {
        val baris = SupabaseMappers.barisPinjaman(
            adminUid, pelangganId, data, pinjamanKeOverride = pinjamanKe
        ) ?: return@jalankan Hasil.Ditolak(
            "status arsip tidak dikenal: ${data["status"]}"
        )
        db.from(T_PINJAMAN).upsert(baris, onConflict = "id")
        Hasil.Sukses
    }

    /**
     * Generasi pinjaman terkini milik satu nasabah. Dipakai bila operasi
     * antrean tidak membawa penanda generasi (op warisan APK lama).
     * Mengembalikan null bila nasabahnya belum ada di server.
     */
    suspend fun pinjamanKeTerkini(adminUid: String, pelangganId: String): Int? = try {
        val json = db.from(T_PINJAMAN).select(Columns.raw("pinjaman_ke")) {
            filter { eq("nasabah_id", SupabaseIds.nasabah(adminUid, pelangganId)) }
        }.data
        Regex("\"pinjaman_ke\"\\s*:\\s*(\\d+)").findAll(json)
            .mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ pinjamanKeTerkini gagal: ${e.message}")
        null
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
    /**
     * Klasifikasi galat: PERMANEN (jangan diulang) vs SEMENTARA (ulangi).
     *
     * ⚠ SEJARAH BUG — DIBACA DULU SEBELUM MENGUBAH DAFTAR DI BAWAH.
     *
     * Versi sebelumnya memasukkan `"PGRST"` ke daftar permanen. `PGRST` adalah
     * awalan SELURUH kode galat PostgREST, termasuk yang justru sementara:
     *
     *   PGRST301 — JWT kedaluwarsa
     *   PGRST000 — PostgREST tidak bisa menghubungi database
     *
     * Akibatnya, operasi yang dibuat saat luring lalu disinkronkan setelah
     * jaringan kembali akan menemui token yang sudah basi (aplikasi lama
     * ditutup, token lewat masa berlakunya), menerima PGRST301, dan ditandai
     * DITOLAK PERMANEN — tidak pernah diulang, padahal cukup menunggu token
     * disegarkan. Pembayaran yang sudah dicatat admin di lapangan hilang, dan
     * pesannya menyalahkan "versi aplikasi lama".
     *
     * ATURAN SEKARANG: yang sementara diperiksa LEBIH DULU, dan yang permanen
     * dieja satu per satu. Bila sebuah galat tidak dikenali, ia dianggap
     * SEMENTARA — antrean yang mengulang terlalu sering hanya boros; antrean
     * yang membuang terlalu cepat menghilangkan uang.
     */
    private fun klasifikasi(pesan: String): Hasil {
        val sementara = listOf(
            "PGRST301", "PGRST000", "PGRST002",          // JWT basi / DB tak terjangkau
            "jwt", "expired", "token",                    // varian pesan auth
            "401", "503", "504", "502",
            "timeout", "timed out", "network", "unreachable",
            "connection", "host", "unresolved", "socket",
        ).any { pesan.contains(it, ignoreCase = true) }
        if (sementara) return Hasil.GagalSementara(pesan)

        // Pelanggaran UNIQUE = operasi ini SUDAH pernah masuk. Untuk antrean
        // yang idempoten lewat client_op_id, itu keberhasilan yang datang
        // terlambat — bukan penolakan. Menandainya gagal membuat operasi
        // menumpuk selamanya dan membuat admin mengira uangnya belum tercatat.
        if (pesan.contains("23505", true) || pesan.contains("duplicate key", true)) {
            Log.w(TAG, "↩︎ sudah pernah tercatat (unique) — dianggap sukses")
            return Hasil.Sukses
        }

        val permanen = listOf(
            "row-level security", "permission denied", "42501",  // hak akses
            "23503",   // FK tidak ada
            "23514",   // check constraint
            "22P02",   // sintaks nilai salah (mis. tanggal tidak sah)
            "PGRST204", // kolom tidak ada di skema
            "PGRST202", // fungsi RPC tidak ada
        ).any { pesan.contains(it, ignoreCase = true) }

        return if (permanen) Hasil.Ditolak(pesan) else Hasil.GagalSementara(pesan)
    }

    private suspend inline fun jalankan(label: String, blok: () -> Hasil): Hasil = try {
        blok()
    } catch (e: Exception) {
        val pesan = e.message.orEmpty()
        val hasil = klasifikasi(pesan)
        when (hasil) {
            is Hasil.Ditolak -> Log.e(TAG, "⛔ $label ditolak permanen: $pesan")
            is Hasil.Sukses -> Log.w(TAG, "✅ $label: $pesan (idempoten)")
            is Hasil.GagalSementara -> Log.w(TAG, "⏳ $label gagal sementara: $pesan")
        }
        hasil
    }
}
