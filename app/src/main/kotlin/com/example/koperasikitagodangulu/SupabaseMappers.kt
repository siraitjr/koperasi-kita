package com.example.koperasikitagodangulu.offline

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * =========================================================================
 * PEMETAAN PAYLOAD RTDB → BARIS POSTGRES
 * =========================================================================
 * Cermin sisi-klien dari transformasi di scripts/migration/migrate.js.
 * Aturannya sama, dan HARUS tetap sama: satu node `pelanggan` RTDB pecah
 * menjadi baris `nasabah` (identitas orang) + baris `pinjaman` (kontrak per
 * generasi).
 *
 * Fungsi di sini murni — tidak menyentuh jaringan, tidak menyentuh Room.
 * =========================================================================
 */
object SupabaseMappers {

    // ---------------------------------------------------------------- utils

    /**
     * Bulan singkat BAHASA INDONESIA. Disalin dari migrate.js, yang
     * menyalinnya dari functions/jurnalTransaksi.js:41-44.
     *
     * Mei/Agu/Okt/Des BERBEDA dari bahasa Inggris. Memakai SimpleDateFormat
     * ber-locale default akan diam-diam gagal untuk empat bulan itu pada
     * perangkat ber-locale en — dan gagalnya tidak kelihatan, hanya jadi
     * tanggal null.
     */
    private val BULAN = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mei" to 5, "jun" to 6,
        "jul" to 7, "agu" to 8, "sep" to 9, "okt" to 10, "nov" to 11, "des" to 12,
        // toleransi data lama yang tertulis dengan locale Inggris
        "may" to 5, "aug" to 8, "oct" to 10, "dec" to 12
    )

    private val RE_ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")
    private val RE_LOKAL = Regex("^(\\d{1,2})\\s+([A-Za-z]{3,})\\s+(\\d{4})$")

    /** "12 Nov 2025" / "2025-11-12" → "2025-11-12". null bila tak terbaca. */
    fun tanggal(v: Any?): String? {
        val s = v?.toString()?.trim().orEmpty()
        if (s.isEmpty()) return null
        RE_ISO.find(s)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        RE_LOKAL.find(s)?.let { m ->
            val bln = BULAN[m.groupValues[2].take(3).lowercase(Locale.ROOT)] ?: return null
            val hari = m.groupValues[1].toIntOrNull() ?: return null
            return String.format(Locale.US, "%s-%02d-%02d", m.groupValues[3], bln, hari)
        }
        return null
    }

    /** Uang → Long rupiah bulat. Kolom Postgres bigint (001 §1). */
    fun rupiah(v: Any?): Long = when (v) {
        null -> 0L
        is Number -> v.toLong()
        else -> v.toString().filter { it.isDigit() || it == '-' }.toLongOrNull() ?: 0L
    }

    fun int(v: Any?, default: Int = 0): Int = when (v) {
        null -> default
        is Number -> v.toInt()
        else -> v.toString().toIntOrNull() ?: default
    }

    fun str(v: Any?): String = v?.toString() ?: ""

    private val RE_NIK = Regex("^\\d{16}$")
    fun nikAtauNull(v: Any?): String? = str(v).trim().takeIf { RE_NIK.matches(it) }

    /**
     * Domain status pinjaman. Nilai RTDB berhuruf besar ("Aktif"), sedangkan
     * nik_registry memakai huruf kecil ("aktif") — dua konvensi dalam satu
     * basis data. Dinormalkan seperti di migrate.js.
     * null = tidak dikenal → pemanggil WAJIB menolak, bukan menebak.
     */
    fun status(v: Any?): String? = when (str(v).trim().lowercase(Locale.ROOT)) {
        "" -> "Menunggu Approval"
        "menunggu approval" -> "Menunggu Approval"
        "disetujui" -> "Disetujui"
        "aktif" -> "Aktif"
        "lunas" -> "Lunas"
        "ditolak" -> "Ditolak"
        "tidak aktif" -> "Tidak Aktif"
        else -> null
    }

    /** Peringkat status — cermin SyncManager.statusRank & koperasi.status_rank(). */
    fun statusRank(s: String?): Int = when (s?.trim()) {
        "Menunggu Approval" -> 0
        "Disetujui" -> 1
        "Aktif" -> 2
        else -> 3
    }

    // ------------------------------------------------- kolom per tabel

    /**
     * Kunci RTDB yang menjadi kolom tabel `nasabah` (identitas orang —
     * tidak berubah antar generasi pinjaman).
     */
    private val KOLOM_NASABAH = mapOf(
        "namaKtp" to "nama_ktp",
        "namaPanggilan" to "nama_panggilan",
        "nomorAnggota" to "nomor_anggota",
        "namaKtpSuami" to "nama_ktp_suami",
        "namaKtpIstri" to "nama_ktp_istri",
        "namaPanggilanSuami" to "nama_panggilan_suami",
        "namaPanggilanIstri" to "nama_panggilan_istri",
        "alamatKtp" to "alamat_ktp",
        "alamatRumah" to "alamat_rumah",
        "detailRumah" to "detail_rumah",
        "wilayah" to "wilayah",
        "wilayahNormalized" to "wilayah_normalized",
        "noHp" to "no_hp",
        "jenisUsaha" to "jenis_usaha",
        "statusKhusus" to "status_khusus",
        "catatanStatusKhusus" to "catatan_status_khusus"
    )

    /** Kunci RTDB → kolom `pinjaman`, dengan tipe nilainya. */
    private val UANG_PINJAMAN = mapOf(
        "besarPinjaman" to "besar_pinjaman",
        "besarPinjamanDiajukan" to "besar_pinjaman_diajukan",
        "besarPinjamanDisetujui" to "besar_pinjaman_disetujui",
        "admin" to "biaya_admin",          // RTDB `admin` → biaya_admin
        "simpanan" to "simpanan_awal",     // skalar, BUKAN ledger
        "totalDiterima" to "total_diterima",
        "totalPelunasan" to "total_pelunasan",
        "tarikTabungan" to "tarik_tabungan",
        "sisaUtangLamaSebelumTopUp" to "sisa_utang_lama_sebelum_top_up",
        "totalPelunasanLamaSebelumTopUp" to "total_pelunasan_lama_sebelum_top_up",
        "besarPinjamanLamaSebelumTopUp" to "besar_pinjaman_lama_sebelum_top_up"
    )

    private val TANGGAL_PINJAMAN = mapOf(
        "tanggalPengajuan" to "tanggal_pengajuan",
        "tanggalDaftar" to "tanggal_daftar",
        "tanggalPencairan" to "tanggal_pencairan",
        "tanggalPelunasan" to "tanggal_pelunasan",
        "tanggalLunasCicilan" to "tanggal_lunas_cicilan",
        "tanggalApproval" to "tanggal_approval",
        "tanggalSerahTerima" to "tanggal_serah_terima",
        "tanggalPencairanSimpanan" to "tanggal_pencairan_simpanan"
    )

    private val TEKS_PINJAMAN = mapOf(
        "tipePinjaman" to "tipe_pinjaman",
        "catatanApproval" to "catatan_approval",
        "alasanPenolakan" to "alasan_penolakan",
        "catatan" to "catatan_admin"
    )

    /**
     * Kunci RTDB yang SENGAJA tidak dipetakan ke kolom mana pun.
     * Lihat 004_firebase_to_postgres_mapping.md §3 untuk alasan tiap kelompok.
     */
    val DIABAIKAN = setOf(
        // penanda antrean lokal & kunci guard internal — tidak pernah ke server
        "isSynced", "_guardPinjamanKe", "_guardStatus", "clientOpId",
        // marker generasi: tidak perlu lagi, satu baris per generasi
        "statusLunasUntukPinjamanKe",
        // snapshot generasi sebelumnya: generasi lama tetap ada sebagai baris
        "backupSebelumTopUp",
        // URI file lokal Android, tidak bermakna di server
        "pendingFotoKtpUri", "pendingFotoKtpSuamiUri", "pendingFotoKtpIstriUri",
        "pendingFotoNasabahUri", "pendingFotoSerahTerimaUri",
        // array → tabel tersendiri
        "pembayaranList", "hasilSimulasiCicilan",
        // turunan / jalur approval lama
        "isPinjamanDiubah", "approvalPimpinan", "approvalPengawas",
        // identitas yang jadi kolom relasional, ditangani terpisah
        "id", "adminUid", "adminEmail", "adminName", "cabangId", "pinjamanKe", "nik"
    )

    /**
     * Kunci guard internal antrean offline. HANYA ini yang di-strip untuk
     * payload non-pelanggan (pembayaran, arsip): berbeda dari DIABAIKAN yang
     * juga membuang field khusus node pelanggan.
     *
     * Kunci ini TIDAK PERNAH boleh sampai ke server — di RTDB ia bahkan
     * ditolak Rules (`_guardPinjamanKe` punya `.validate: false`), dan di
     * Postgres ia akan membuat PostgREST menolak seluruh request karena
     * kolomnya tidak ada.
     */
    val DIABAIKAN_GUARD = setOf("_guardPinjamanKe", "_guardStatus")

    // ------------------------------------------------------------ builder

    /**
     * Baris `jurnal_transaksi` dari operasi antrean ADD_JURNAL_TRANSAKSI.
     *
     * Path RTDB-nya `jurnal_transaksi/{cabangId}/{YYYY-MM}/{pushKey}`, dengan
     * pushKey yang di-generate KLIEN (OfflineRepository.addJurnalTransaksi)
     * supaya replay idempoten. Push key itulah yang dipakai menurunkan
     * `client_op_id` — dengan rumus yang sama seperti ID.jurnal di
     * migrate.js, sehingga entri hasil migrasi dan entri baru tidak pernah
     * bertabrakan maupun terduplikasi.
     */
    fun barisJurnalDariRtdb(firebasePath: String, p: Map<String, Any?>): JsonObject? {
        val ref = SyncTargets.parseJurnal(firebasePath) ?: return null
        val tipe = str(p["tipe"]).ifBlank { return null }
        val adminUid = str(p["adminUid"])
        val pelangganId = str(p["pelangganId"])
        val ke = int(p["pinjamanKe"], 1)
        val opId = SupabaseIds.jurnal(ref.cabangId, ref.yearMonth, ref.pushKey)

        return buildJsonObject {
            put("id", opId)
            put("client_op_id", opId)
            put("cabang_id", ref.cabangId)
            put("tipe", tipe)
            if (adminUid.isNotBlank() && pelangganId.isNotBlank()) {
                put("nasabah_id", SupabaseIds.nasabah(adminUid, pelangganId))
                put("pinjaman_id", SupabaseIds.pinjaman(adminUid, pelangganId, ke))
            }
            if (adminUid.isNotBlank()) put("admin_id", SupabaseIds.user(adminUid))
            put("admin_name", str(p["adminName"]))
            put("nama_pelanggan", str(p["namaPelanggan"]))
            put("nama_ktp", str(p["namaKtp"]))
            put("jumlah", rupiah(p["jumlah"]))
            put("tanggal", tanggal(p["tanggal"]) ?: tanggal(p["createdAt"]) ?: "${ref.yearMonth}-01")
            put("pinjaman_ke", ke)
            p["sisaUtangSetelah"]?.let { put("sisa_utang_setelah", rupiah(it)) }
            p["totalPelunasan"]?.let { put("total_pelunasan", rupiah(it)) }
            p["totalDibayar"]?.let { put("total_dibayar", rupiah(it)) }
            put("keterangan", str(p["keterangan"]))
        }
    }

    /** Baris tabel `nasabah` dari payload RTDB. */
    fun barisNasabah(adminUid: String, pelangganId: String, p: Map<String, Any?>): JsonObject =
        buildJsonObject {
            put("id", SupabaseIds.nasabah(adminUid, pelangganId))
            put("legacy_pelanggan_id", pelangganId)
            put("legacy_admin_uid", adminUid)
            put("admin_id", SupabaseIds.user(adminUid))
            SupabaseIds.normalisasiCabang(str(p["cabangId"])).takeIf { it.isNotEmpty() }
                ?.let { put("cabang_id", it) }
            nikAtauNull(p["nik"])?.let { put("nik", it) }
            nikAtauNull(p["nikSuami"])?.let { put("nik_suami", it) }
            nikAtauNull(p["nikIstri"])?.let { put("nik_istri", it) }
            for ((rtdb, kolom) in KOLOM_NASABAH) {
                if (p.containsKey(rtdb)) put(kolom, str(p[rtdb]))
            }
            tanggal(p["tanggalStatusKhusus"])?.let { put("tanggal_status_khusus", it) }
        }

    /**
     * Baris tabel `pinjaman`. Mengembalikan null bila status tidak dikenal —
     * lebih baik operasi ditolak dengan jelas daripada menulis status tebakan
     * ke data keuangan.
     */
    fun barisPinjaman(
        adminUid: String,
        pelangganId: String,
        p: Map<String, Any?>,
        /**
         * Generasi yang dipaksa dari luar. Dipakai ADD_RIWAYAT_PINJAMAN, yang
         * generasinya ada di PATH antrean (`riwayat_pinjaman/{admin}/{pid}/{N}`)
         * dan belum tentu sama dengan `pinjamanKe` di dalam payload snapshot.
         * null = ambil dari payload seperti biasa.
         */
        pinjamanKeOverride: Int? = null
    ): JsonObject? {
        val st = status(p["status"]) ?: return null
        val ke = pinjamanKeOverride ?: int(p["pinjamanKe"], 1)
        return buildJsonObject {
            put("id", SupabaseIds.pinjaman(adminUid, pelangganId, ke))
            put("nasabah_id", SupabaseIds.nasabah(adminUid, pelangganId))
            put("pinjaman_ke", ke)
            put("status", st)
            put("jasa_pinjaman", int(p["jasaPinjaman"], 10))
            put("tenor", maxOf(1, int(p["tenor"], 30)))
            for ((rtdb, kolom) in UANG_PINJAMAN) if (p.containsKey(rtdb)) put(kolom, rupiah(p[rtdb]))
            for ((rtdb, kolom) in TEKS_PINJAMAN) if (p.containsKey(rtdb)) put(kolom, str(p[rtdb]))
            for ((rtdb, kolom) in TANGGAL_PINJAMAN) {
                if (p.containsKey(rtdb)) tanggal(p[rtdb])?.let { put(kolom, it) }
            }
            str(p["statusSerahTerima"]).takeIf { it == "Pending" || it == "Selesai" }
                ?.let { put("status_serah_terima", it) }
            str(p["statusPencairanSimpanan"])
                .takeIf { it == "Menunggu Pencairan" || it == "Dicairkan" }
                ?.let { put("status_pencairan_simpanan", it) }
        }
    }

    /**
     * Pecah payload UPDATE parsial menjadi dua patch: kolom `nasabah` dan
     * kolom `pinjaman`. Kunci yang tidak dikenal DIBUANG dengan sengaja —
     * PostgREST menolak seluruh request bila ada satu kolom tak dikenal, jadi
     * membiarkannya lewat akan menggagalkan operasi yang sebenarnya sah.
     */
    fun patchTerpisah(update: Map<String, Any?>): Pair<JsonObject, JsonObject> {
        val nasabah = buildJsonObject {
            for ((rtdb, kolom) in KOLOM_NASABAH) if (update.containsKey(rtdb)) put(kolom, str(update[rtdb]))
            // `put(key, String?)` dari kotlinx-serialization sudah memetakan
            // null → JsonNull, jadi elvis ke JsonPrimitive tidak diperlukan.
            // Bentuk lama (`nikAtauNull(...) ?: JsonPrimitive(null)`) mencampur
            // String dan JsonPrimitive, sehingga Kotlin melebarkan tipe hasilnya
            // ke Any dan memilih overload put(String, JsonElement) → tidak cocok.
            //
            // Semantiknya tetap sama dan memang disengaja: NIK yang kosong atau
            // tidak 16 digit menulis NULL, bukan string kosong. Itu yang membuat
            // unique index parsial `where nik is not null` (001 §2) tetap benar
            // saat alur cairkan-serah terima mengosongkan NIK
            // (buildCairkanCleansePayload menulis "nik" to "").
            if (update.containsKey("nik")) put("nik", nikAtauNull(update["nik"]))
            if (update.containsKey("tanggalStatusKhusus")) {
                tanggal(update["tanggalStatusKhusus"])?.let { put("tanggal_status_khusus", it) }
            }
        }
        val pinjaman = buildJsonObject {
            if (update.containsKey("status")) status(update["status"])?.let { put("status", it) }
            if (update.containsKey("tenor")) put("tenor", maxOf(1, int(update["tenor"], 30)))
            if (update.containsKey("jasaPinjaman")) put("jasa_pinjaman", int(update["jasaPinjaman"], 10))
            for ((rtdb, kolom) in UANG_PINJAMAN) if (update.containsKey(rtdb)) put(kolom, rupiah(update[rtdb]))
            for ((rtdb, kolom) in TEKS_PINJAMAN) if (update.containsKey(rtdb)) put(kolom, str(update[rtdb]))
            for ((rtdb, kolom) in TANGGAL_PINJAMAN) {
                if (update.containsKey(rtdb)) tanggal(update[rtdb])?.let { put(kolom, it) }
            }
        }
        return nasabah to pinjaman
    }

    /** Baris tabel `pembayaran`. clientOpId = kunci idempotensi (UNIQUE). */
    fun barisPembayaran(
        adminUid: String,
        pelangganId: String,
        pinjamanKe: Int,
        bayar: Map<String, Any?>,
        jenis: String
    ): JsonObject? {
        val jml = rupiah(bayar["jumlah"])
        val tgl = tanggal(bayar["tanggal"]) ?: return null
        if (jml <= 0L) return null
        val opId = str(bayar["clientOpId"]).ifBlank { return null }
        return buildJsonObject {
            put("id", SupabaseIds.pembayaranDariOpId(opId))
            put("client_op_id", SupabaseIds.pembayaranDariOpId(opId))
            put("pinjaman_id", SupabaseIds.pinjaman(adminUid, pelangganId, pinjamanKe))
            put("jenis", jenis)
            put("jumlah", jml)
            put("tanggal", tgl)
            put("keterangan", str(bayar["keterangan"]))
            put("dicatat_oleh", SupabaseIds.user(adminUid))
        }
    }
}
