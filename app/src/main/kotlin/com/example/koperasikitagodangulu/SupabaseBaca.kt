package com.example.koperasikitagodangulu

import android.util.Log
import com.example.koperasikitagodangulu.models.AdminSummary
import com.example.koperasikitagodangulu.offline.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * =========================================================================
 * SUPABASE BACA — arah balik dari SupabaseMappers (FASE 1)
 * =========================================================================
 * `SupabaseMappers` memetakan RTDB → baris Postgres (arah TULIS). Berkas ini
 * memetakan baris Postgres → model `Pelanggan` (arah BACA), yang sampai
 * sekarang belum ada sama sekali: `SupabaseDataSource` hanya punya dua stub
 * yang mengembalikan JSON mentah.
 *
 * KENAPA SATU KUERI, BUKAN TIGA
 * -------------------------------------------------------------------------
 * PostgREST bisa menyematkan tabel anak lewat foreign key, jadi nasabah +
 * seluruh generasi pinjaman + seluruh pembayarannya diambil dalam SATU
 * perjalanan jaringan:
 *
 *     select=*,pinjaman(*,pembayaran(*))
 *
 * Bandingkan dengan RTDB yang perlu satu listener per admin lalu memarsing
 * pohon bersarang. Ini juga alasan `.limit()` tidak dipakai di sini: seorang
 * admin lapangan memegang puluhan nasabah, bukan ribuan.
 *
 * DUA JEBAKAN YANG SUDAH DITANGANI
 * -------------------------------------------------------------------------
 * 1. FORMAT TANGGAL. Postgres mengembalikan ISO (`2026-08-30`), sedangkan
 *    seluruh aplikasi — perhitungan target, tampilan, perbandingan string —
 *    memakai `"dd MMM yyyy"` berbahasa Indonesia. Konversinya memakai array
 *    bulan milik aplikasi sendiri (PelangganViewModel.kt:13293), BUKAN
 *    `SimpleDateFormat("dd MMM yyyy")`: formatter ICU menghasilkan "Aug" atau
 *    "Agt" tergantung perangkat, sementara aplikasi ini memakai "Agu".
 *    Persis jebakan yang dicatat di PelangganViewModel.kt:16824 dan 16892.
 *
 * 2. IDENTITAS. `Pelanggan.id` dipakai di ratusan tempat sebagai id RTDB, dan
 *    cache offline (Room) menyimpan id yang sama. Maka `id` diisi
 *    `legacy_pelanggan_id`, bukan uuid Supabase — supaya data yang dimuat
 *    lewat jalur ini tetap cocok dengan antrean offline dan layar yang sudah
 *    ada. Uuid Supabase-nya sengaja TIDAK ikut disimpan di model: ia bisa
 *    dihitung ulang kapan saja lewat `SupabaseIds.nasabah(adminUid, id)`
 *    (uuidv5 deterministik, dipakai juga oleh migrate.js dan
 *    SupabaseDataSource). Menambah field ke `Pelanggan` berarti field itu
 *    ikut tertulis ke RTDB oleh setiap `setValue(Pelanggan)` — perubahan
 *    bentuk data yang tidak dibutuhkan siapa pun.
 * =========================================================================
 */
object SupabaseBaca {

    private const val TAG = "SupabaseBaca"

    private val json = Json { ignoreUnknownKeys = true }

    private val BULAN = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
        "Jul", "Agu", "Sep", "Okt", "Nov", "Des"
    )

    private val db get() = SupabaseClientProvider.client().postgrest

    // Satu tempat untuk bentuk kueri, supaya baca-daftar dan baca-satu tidak
    // bisa berbeda diam-diam.
    private const val KOLOM = "*,pinjaman(*,pembayaran(*))"

    // ---------------------------------------------------------------------
    // Konversi nilai
    // ---------------------------------------------------------------------

    private fun JsonObject.teks(k: String): String =
        (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.angka(k: String): Int =
        ((this[k] as? JsonPrimitive)?.longOrNull
            ?: (this[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: 0L).toInt()

    private fun JsonObject.benar(k: String): Boolean =
        (this[k] as? JsonPrimitive)?.contentOrNull?.equals("true", true) == true

    private fun JsonObject.angkaPanjang(k: String): Long =
        (this[k] as? JsonPrimitive)?.longOrNull
            ?: (this[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: 0L

    /**
     * ISO `2026-08-30` → `"30 Agu 2026"`. String kosong bila null/tak terbaca.
     *
     * Sengaja memotong string, bukan mem-parsing ke Date lalu memformat ulang:
     * tanggal bisnis di sini tidak punya jam dan tidak punya zona waktu, jadi
     * mengubahnya jadi Date hanya menambah peluang bergeser satu hari.
     */
    internal fun tanggalTampil(iso: String?): String {
        val s = iso?.trim().orEmpty()
        if (s.length < 10) return ""
        val th = s.substring(0, 4).toIntOrNull() ?: return ""
        val bl = s.substring(5, 7).toIntOrNull() ?: return ""
        val hr = s.substring(8, 10).toIntOrNull() ?: return ""
        if (bl !in 1..12) return ""
        return "%02d %s %d".format(hr, BULAN[bl - 1], th)
    }

    // ---------------------------------------------------------------------
    // Kueri
    // ---------------------------------------------------------------------

    /**
     * Seluruh nasabah milik satu admin, lengkap dengan generasi pinjaman
     * berjalan dan pembayarannya.
     *
     * `adminIdSupabase` boleh null: RLS sudah membatasi baris yang terlihat
     * per peran (002 §2), jadi filter di sini murni optimasi jaringan — bukan
     * pengaman. Untuk Pimpinan/Koordinator/Pengawas justru TIDAK boleh
     * difilter, karena mereka memang harus melihat lintas admin.
     */
    suspend fun muatDaftarPelanggan(adminIdSupabase: String?): Result<List<Pelanggan>> =
        runCatching {
            val mentah = db.from("nasabah").select(Columns.raw(KOLOM)) {
                if (!adminIdSupabase.isNullOrBlank()) {
                    filter { eq("admin_id", adminIdSupabase) }
                }
            }.data

            val arr = json.parseToJsonElement(mentah) as? JsonArray
                ?: throw IllegalStateException("Balasan bukan array JSON")

            val hasil = arr.mapNotNull { el ->
                runCatching { Peta.pelanggan(el.jsonObject) }
                    .onFailure { Log.e(TAG, "gagal memetakan satu nasabah: ${it.message}") }
                    .getOrNull()
            }
            Log.d(TAG, "✅ ${hasil.size} nasabah dimuat dari Supabase")
            hasil
        }.onFailure { Log.e(TAG, "❌ muatDaftarPelanggan gagal: ${it.message}") }

    // =====================================================================
    // FASE 3 — RINGKASAN PERAN PENGAWAS / PIMPINAN / KOORDINATOR
    // =====================================================================
    // Sengaja TIDAK memakai `muatDaftarPelanggan()` untuk peran-peran ini.
    // Seorang admin memegang puluhan nasabah, tetapi Pengawas melihat seluruh
    // koperasi — dan kueri FASE 1 menyertakan seluruh pembayaran tiap nasabah.
    // Untuk lingkup global itu berarti puluhan ribu baris hanya demi menampilkan
    // lima angka.
    //
    // `v_buku_pokok_summary` dan `v_pembayaran_hari_ini` (015) sudah
    // mengagregasi di server dan sudah `security_invoker = on`, jadi RLS yang
    // menentukan cabang mana yang terlihat — Pimpinan otomatis hanya mendapat
    // cabangnya, Pengawas mendapat semuanya. Tidak ada penyaringan di klien
    // yang bisa salah.

    data class RingkasanCabang(
        val cabangId: String,
        val nasabahAktif: Int,
        val nasabahLunas: Int,
        val totalPinjamanAktif: Long,
        val totalPiutang: Long,
        val totalDibayar: Long,
    )

    suspend fun muatRingkasanCabang(): Result<List<RingkasanCabang>> = runCatching {
        val mentah = db.from("v_buku_pokok_summary").select().data
        val arr = json.parseToJsonElement(mentah) as? JsonArray
            ?: throw IllegalStateException("v_buku_pokok_summary: balasan bukan array")
        val hasil = arr.map { el ->
            val o = el.jsonObject
            RingkasanCabang(
                cabangId = o.teks("cabang_id"),
                nasabahAktif = o.angka("nasabah_aktif"),
                nasabahLunas = o.angka("nasabah_lunas"),
                totalPinjamanAktif = o.angkaPanjang("total_pinjaman_aktif"),
                totalPiutang = o.angkaPanjang("total_piutang"),
                totalDibayar = o.angkaPanjang("total_dibayar"),
            )
        }
        Log.d(TAG, "✅ ringkasan ${hasil.size} cabang dimuat")
        hasil
    }.onFailure { Log.e(TAG, "❌ muatRingkasanCabang gagal: ${it.message}") }

    /**
     * Total pembayaran hari ini, dijumlahkan atas seluruh cabang yang terlihat.
     *
     * Tanggalnya ditentukan SERVER dengan zona Asia/Jakarta di dalam view —
     * bukan jam perangkat. Perangkat yang zonanya meleset (atau disetel
     * manual) karena itu tidak bisa menggeser angka hari ini.
     */
    suspend fun muatPembayaranHariIni(): Result<Long> = runCatching {
        val mentah = db.from("v_pembayaran_hari_ini").select().data
        val arr = json.parseToJsonElement(mentah) as? JsonArray
            ?: throw IllegalStateException("v_pembayaran_hari_ini: balasan bukan array")
        val total = arr.sumOf { it.jsonObject.angkaPanjang("total") }
        Log.d(TAG, "✅ pembayaran hari ini (${arr.size} baris): $total")
        total
    }.onFailure { Log.e(TAG, "❌ muatPembayaranHariIni gagal: ${it.message}") }

    /**
     * Ringkasan PER ADMIN — inilah yang sebenarnya dirender layar Pimpinan.
     *
     * KENAPA INI PERLU, PADAHAL SUDAH ADA `muatRingkasanCabang()`
     * ---------------------------------------------------------------------
     * `dashboardData` bukan pengikat utama layar itu. PimpinanDashboardScreen
     * merender kartu per admin dari `adminSummary`, menghitung totalnya
     * sendiri dari daftar itu (:408-411), dan menentukan keadaan kosong dengan
     * `adminSummary.isEmpty()` (:368). Jadi selama `_adminSummary` kosong,
     * layarnya tetap kosong betapapun benarnya angka ringkasan cabang.
     *
     * Agregasinya di klien, bukan di server, karena `v_buku_pokok_summary`
     * mengelompokkan per CABANG sementara yang dibutuhkan per ADMIN. Kolom
     * yang ditarik dibatasi seperlunya, dan baris historis dibuang di server
     * lewat filter — jadi yang datang satu baris per nasabah aktif, bukan
     * seluruh riwayat pembayarannya.
     */
    suspend fun muatRingkasanAdmin(): Result<List<AdminSummary>> = runCatching {
        val kolom = "admin_id,admin_nama,cabang_id,is_aktif,is_lunas," +
            "besar_pinjaman,sisa_utang,total_dibayar"

        val mentah = db.from("v_buku_pokok").select(Columns.raw(kolom)) {
            filter { eq("is_historis", false) }
        }.data
        val arr = json.parseToJsonElement(mentah) as? JsonArray
            ?: throw IllegalStateException("v_buku_pokok: balasan bukan array")

        // Pembayaran hari ini per admin — dari view yang zona waktunya
        // ditentukan server (Asia/Jakarta), bukan jam perangkat.
        val bayarPerAdmin = runCatching {
            val m = db.from("v_pembayaran_hari_ini").select(Columns.raw("admin_id,total")).data
            (json.parseToJsonElement(m) as? JsonArray).orEmpty()
                .groupBy { it.jsonObject.teks("admin_id") }
                .mapValues { (_, v) -> v.sumOf { it.jsonObject.angkaPanjang("total") } }
        }.getOrElse {
            Log.e(TAG, "pembayaran hari ini per admin gagal: ${it.message}")
            emptyMap()
        }

        val hasil = arr.map { it.jsonObject }
            .groupBy { it.teks("admin_id") }
            .map { (adminId, baris) ->
                val aktif = baris.filter { it.benar("is_aktif") }
                AdminSummary(
                    adminId = adminId,
                    adminName = baris.firstOrNull { it.teks("admin_nama").isNotEmpty() }
                        ?.teks("admin_nama").orEmpty().ifBlank { "(tanpa nama)" },
                    cabang = baris.firstOrNull()?.teks("cabang_id").orEmpty(),
                    totalPelanggan = baris.size,
                    nasabahAktif = aktif.size,
                    nasabahLunas = baris.count { it.benar("is_lunas") },
                    totalPinjamanAktif = aktif.sumOf { it.angkaPanjang("besar_pinjaman") },
                    totalPiutang = aktif.sumOf { it.angkaPanjang("sisa_utang") },
                    pembayaranHariIni = bayarPerAdmin[adminId] ?: 0L,
                    // Target harian butuh simulasi cicilan per nasabah (aturan
                    // H+1) dan tidak bisa diturunkan dari kolom mana pun di
                    // view ini. Dibiarkan 0 — lihat catatan di commit FASE 3.
                    targetHariIni = 0L,
                    lastUpdated = System.currentTimeMillis(),
                )
            }
            .sortedByDescending { it.totalPinjamanAktif }

        Log.d(TAG, "✅ ringkasan ${hasil.size} admin dimuat (${arr.size} baris nasabah)")
        hasil
    }.onFailure { Log.e(TAG, "❌ muatRingkasanAdmin gagal: ${it.message}") }

    // ---------------------------------------------------------------------
    // Pemetaan baris → model
    // ---------------------------------------------------------------------

    private object Peta {

        fun pelanggan(n: JsonObject): Pelanggan {
            // Generasi berjalan = pinjaman_ke terbesar. Generasi lama tetap
            // ada barisnya (dipakai riwayat), tetapi kartu nasabah di layar
            // admin selalu menampilkan yang berjalan.
            val semuaPinjaman = (n["pinjaman"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
            val p = semuaPinjaman.maxByOrNull { it.angka("pinjaman_ke") }

            val bayar = (p?.get("pembayaran") as? JsonArray)
                ?.map { it.jsonObject }
                ?.sortedBy { it.teks("tanggal") }
                ?.map {
                    Pembayaran(
                        jumlah = it.angka("jumlah"),
                        tanggal = tanggalTampil(it.teks("tanggal")),
                        keterangan = it.teks("keterangan"),
                        clientOpId = it.teks("client_op_id"),
                    )
                }
                .orEmpty()

            return Pelanggan(
                // ── identitas ────────────────────────────────────────────
                // legacy_pelanggan_id lebih dulu: lihat "JEBAKAN 2" di kepala
                // berkas. Uuid hanya dipakai bila nasabahnya lahir setelah
                // migrasi (belum punya id warisan).
                id = n.teks("legacy_pelanggan_id").ifBlank { n.teks("id") },
                adminUid = n.teks("legacy_admin_uid"),
                cabangId = n.teks("cabang_id"),

                namaKtp = n.teks("nama_ktp"),
                nik = n.teks("nik"),
                namaPanggilan = n.teks("nama_panggilan"),
                nomorAnggota = n.teks("nomor_anggota"),
                namaKtpSuami = n.teks("nama_ktp_suami"),
                namaKtpIstri = n.teks("nama_ktp_istri"),
                nikSuami = n.teks("nik_suami"),
                nikIstri = n.teks("nik_istri"),
                namaPanggilanSuami = n.teks("nama_panggilan_suami"),
                namaPanggilanIstri = n.teks("nama_panggilan_istri"),

                alamatKtp = n.teks("alamat_ktp"),
                alamatRumah = n.teks("alamat_rumah"),
                detailRumah = n.teks("detail_rumah"),
                wilayah = n.teks("wilayah"),
                wilayahNormalized = n.teks("wilayah_normalized"),
                noHp = n.teks("no_hp"),
                jenisUsaha = n.teks("jenis_usaha"),

                statusKhusus = n.teks("status_khusus"),
                catatanStatusKhusus = n.teks("catatan_status_khusus"),
                tanggalStatusKhusus = tanggalTampil(n.teks("tanggal_status_khusus")),
                diberiTandaOleh = n.teks("diberi_tanda_oleh"),

                // ── generasi pinjaman berjalan ───────────────────────────
                // Tanpa baris pinjaman, seluruh field ini memakai default
                // model — nasabah tetap tampil, tidak hilang dari daftar.
                pinjamanKe = p?.angka("pinjaman_ke") ?: 1,
                status = p?.teks("status").orEmpty().ifBlank { "Menunggu Approval" },
                tipePinjaman = p?.teks("tipe_pinjaman").orEmpty().ifBlank { "dibawah_3jt" },

                besarPinjaman = p?.angka("besar_pinjaman") ?: 0,
                besarPinjamanDiajukan = p?.angka("besar_pinjaman_diajukan") ?: 0,
                besarPinjamanDisetujui = p?.angka("besar_pinjaman_disetujui") ?: 0,
                jasaPinjaman = p?.angka("jasa_pinjaman") ?: 10,
                admin = p?.angka("biaya_admin") ?: 0,
                simpanan = p?.angka("simpanan_awal") ?: 0,
                totalDiterima = p?.angka("total_diterima") ?: 0,
                totalPelunasan = p?.angka("total_pelunasan") ?: 0,
                tenor = p?.angka("tenor") ?: 30,

                tanggalPengajuan = tanggalTampil(p?.teks("tanggal_pengajuan")),
                tanggalDaftar = tanggalTampil(p?.teks("tanggal_daftar")),
                tanggalPencairan = tanggalTampil(p?.teks("tanggal_pencairan")),
                tanggalPelunasan = tanggalTampil(p?.teks("tanggal_pelunasan")),
                tanggalLunasCicilan = tanggalTampil(p?.teks("tanggal_lunas_cicilan")),

                catatanApproval = p?.teks("catatan_approval").orEmpty(),
                tanggalApproval = tanggalTampil(p?.teks("tanggal_approval")),
                disetujuiOleh = p?.teks("disetujui_oleh").orEmpty(),
                ditolakOleh = p?.teks("ditolak_oleh").orEmpty(),
                alasanPenolakan = p?.teks("alasan_penolakan").orEmpty(),

                statusSerahTerima = p?.teks("status_serah_terima").orEmpty(),
                tanggalSerahTerima = tanggalTampil(p?.teks("tanggal_serah_terima")),

                tarikTabungan = p?.angka("tarik_tabungan") ?: 0,
                statusPencairanSimpanan = p?.teks("status_pencairan_simpanan").orEmpty(),
                tanggalPencairanSimpanan = tanggalTampil(p?.teks("tanggal_pencairan_simpanan")),
                dicairkanOleh = p?.teks("dicairkan_oleh").orEmpty(),

                besarPinjamanLamaSebelumTopUp = p?.angka("besar_pinjaman_lama_sebelum_top_up") ?: 0,
                sisaUtangLamaSebelumTopUp = p?.angka("sisa_utang_lama_sebelum_top_up") ?: 0,
                totalPelunasanLamaSebelumTopUp = p?.angka("total_pelunasan_lama_sebelum_top_up") ?: 0,

                pembayaranList = bayar,

                // Data yang datang dari server menurut definisinya sudah
                // tersinkron; menandainya false akan membuat SyncManager
                // mengantre ulang seluruh daftar tanpa alasan.
                isSynced = true,
            )
        }
    }
}
