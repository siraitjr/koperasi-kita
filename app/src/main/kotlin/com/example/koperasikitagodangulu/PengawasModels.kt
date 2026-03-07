package com.example.koperasikitagodangulu.models

import com.google.firebase.database.PropertyName

/**
 * =========================================================================
 * PENGAWAS MODELS - Data Classes untuk Multi-Cabang
 * =========================================================================
 *
 * Data classes untuk mendukung fitur Pengawas yang dapat melihat
 * data dari SEMUA CABANG dengan filter.
 *
 * STRATEGI EFISIEN:
 * - Baca dari summary/perCabang (BUKAN dari 6000 pelanggan)
 * - Filter di client berdasarkan cabang yang dipilih
 * - Aggregate untuk "Semua Cabang"
 */

/**
 * Summary per Cabang - dibaca dari summary/perCabang/{cabangId}
 */
data class PengawasCabangSummary(
    val cabangId: String = "",
    val cabangName: String = "",

    // Statistik Nasabah
    @PropertyName("totalNasabah")
    val totalNasabah: Int = 0,

    @PropertyName("nasabahAktif")
    val nasabahAktif: Int = 0,

    @PropertyName("nasabahLunas")
    val nasabahLunas: Int = 0,

    @PropertyName("nasabahMenunggu")
    val nasabahMenunggu: Int = 0,

    // Event Harian
    @PropertyName("nasabahBaruHariIni")
    val nasabahBaruHariIni: Int = 0,

    @PropertyName("nasabahLunasHariIni")
    val nasabahLunasHariIni: Int = 0,

    // Target & Pembayaran
    @PropertyName("targetHariIni")
    val targetHariIni: Long = 0L,

    @PropertyName("pembayaranHariIni")
    val pembayaranHariIni: Long = 0L,

    // Finansial
    @PropertyName("totalPinjamanAktif")
    val totalPinjamanAktif: Long = 0L,

    @PropertyName("totalPiutang")
    val totalPiutang: Long = 0L,

    // Admin
    @PropertyName("adminCount")
    val adminCount: Int = 0,

    // Timestamp
    @PropertyName("lastUpdated")
    val lastUpdated: Long = 0L
) {
    /**
     * Persentase performa hari ini
     */
    val performancePercentage: Float
        get() = if (targetHariIni > 0) {
            ((pembayaranHariIni.toFloat() / targetHariIni) * 100).coerceIn(0f, 200f)
        } else {
            0f
        }

    /**
     * Cek apakah hari libur (ada nasabah aktif tapi target = 0)
     */
    fun isHoliday(): Boolean = nasabahAktif > 0 && targetHariIni == 0L

    /**
     * Gap pembayaran vs target
     */
    val paymentGap: Long
        get() = pembayaranHariIni - targetHariIni

    companion object {
        fun empty(cabangId: String = "", cabangName: String = "") = PengawasCabangSummary(
            cabangId = cabangId,
            cabangName = cabangName
        )

        /**
         * Aggregate multiple cabang summaries into one
         */
        fun aggregate(summaries: List<PengawasCabangSummary>): PengawasCabangSummary {
            if (summaries.isEmpty()) return empty("all", "Semua Cabang")

            return PengawasCabangSummary(
                cabangId = "all",
                cabangName = "Semua Cabang",
                totalNasabah = summaries.sumOf { it.totalNasabah },
                nasabahAktif = summaries.sumOf { it.nasabahAktif },
                nasabahLunas = summaries.sumOf { it.nasabahLunas },
                nasabahMenunggu = summaries.sumOf { it.nasabahMenunggu },
                nasabahBaruHariIni = summaries.sumOf { it.nasabahBaruHariIni },
                nasabahLunasHariIni = summaries.sumOf { it.nasabahLunasHariIni },
                targetHariIni = summaries.sumOf { it.targetHariIni },
                pembayaranHariIni = summaries.sumOf { it.pembayaranHariIni },
                totalPinjamanAktif = summaries.sumOf { it.totalPinjamanAktif },
                totalPiutang = summaries.sumOf { it.totalPiutang },
                adminCount = summaries.sumOf { it.adminCount },
                lastUpdated = summaries.maxOfOrNull { it.lastUpdated } ?: 0L
            )
        }
    }
}

/**
 * Info Cabang dari Metadata - dibaca dari metadata/cabang/{cabangId}
 */
data class CabangMetadata(
    val cabangId: String = "",
    val name: String = "",
    val pimpinanUid: String = "",
    val adminList: List<String> = emptyList()
)

/**
 * Info Admin dari Metadata - dibaca dari metadata/admins/{adminUid}
 */
data class AdminMetadata(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val cabang: String = "",
    val role: String = ""
)

/**
 * Pembayaran Harian - dibaca dari pembayaran_harian/{cabangId}/{tanggal}
 */
data class PembayaranHarianItem(
    val id: String = "",
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val adminEmail: String = "",
    val cabangId: String = "",
    val jumlah: Long = 0L,
    val jenis: String = "", // "cicilan", "tambah_bayar", "pencairan"
    val tanggal: String = "",
    val timestamp: Long = 0L
) {
    val jenisDisplay: String
        get() = when (jenis) {
            "cicilan" -> "Cicilan"
            "tambah_bayar" -> "Tambah Bayar"
            "pencairan" -> "Pencairan"
            else -> jenis.replaceFirstChar { it.uppercase() }
        }

    val isPencairan: Boolean
        get() = jenis == "pencairan"
}

/**
 * Nasabah Baru Hari Ini - dibaca dari event_harian/{cabangId}/{tanggal}/nasabah_baru
 */
data class NasabahBaruItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val cabangId: String = "",
    val besarPinjaman: Long = 0L,
    val totalDiterima: Long = 0L,
    val wilayah: String = "",
    val tanggalDaftar: String = "",
    val timestamp: Long = 0L,
    val pinjamanKe: Int = 1
)

/**
 * Nasabah Lunas Hari Ini - dibaca dari event_harian/{cabangId}/{tanggal}/nasabah_lunas
 */
data class NasabahLunasItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val cabangId: String = "",
    val totalPinjaman: Long = 0L,
    val totalDibayar: Long = 0L,
    val wilayah: String = "",
    val tanggalLunas: String = "",
    val timestamp: Long = 0L
)

/**
 * Pelanggan Bermasalah - dibaca dari pelanggan_bermasalah/{cabangId}
 */
data class PelangganBermasalahItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val cabangId: String = "",
    val kategori: String = "", // "ringan", "sedang", "berat"
    val hariTunggakan: Int = 0,
    val jumlahTunggakan: Long = 0L,
    val totalPiutang: Long = 0L,
    val wilayah: String = "",
    val timestamp: Long = 0L
) {
    val kategoriDisplay: String
        get() = when (kategori.lowercase()) {
            "ringan" -> "Ringan (1-7 hari)"
            "sedang" -> "Sedang (8-14 hari)"
            "berat" -> "Berat (>14 hari)"
            else -> kategori.replaceFirstChar { it.uppercase() }
        }
}

/**
 * Biaya Awal Item - dibaca dari biaya_awal/{adminUid}/{tanggal}
 */
data class BiayaAwalItem(
    val adminUid: String = "",
    val adminName: String = "",
    val cabangId: String = "",
    val jumlah: Long = 0L,
    val tanggal: String = "",
    val timestamp: Long = 0L
)