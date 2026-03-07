package com.example.koperasikitagodangulu.models

import com.google.firebase.database.PropertyName

/**
 * =========================================================================
 * ADMIN SUMMARY - VERSI DIPERBAIKI v4
 * =========================================================================
 *
 * PERBAIKAN v4:
 * ✅ Ditambahkan nasabahMenungguPencairan
 * ✅ nasabahLunas sekarang hanya yang statusPencairanSimpanan == "Dicairkan"
 * ✅ nasabahAktif exclude yang statusKhusus == "MENUNGGU_PENCAIRAN"
 *
 * PENTING:
 * Data ini dihitung oleh Cloud Functions, BUKAN di client!
 * Pastikan Cloud Functions sudah di-deploy.
 */
data class AdminSummary(
    // Identitas Admin
    val adminId: String = "",
    val adminName: String = "",
    val adminEmail: String = "",
    val cabang: String = "",

    // Statistik Nasabah
    val totalPelanggan: Int = 0,
    val nasabahAktif: Int = 0,
    val nasabahLunas: Int = 0,

    @PropertyName("nasabahMenunggu")
    val nasabahMenunggu: Int = 0,

    // ✅ v4: TAMBAHAN - Nasabah yang menunggu pencairan simpanan
    @PropertyName("nasabahMenungguPencairan")
    val nasabahMenungguPencairan: Int = 0,

    // Field dari Cloud Functions
    @PropertyName("nasabahBaruHariIni")
    val nasabahBaruHariIni: Int = 0,

    @PropertyName("nasabahLunasHariIni")
    val nasabahLunasHariIni: Int = 0,

    // targetHariIni dari Cloud Functions
    // Dihitung dari hasilSimulasiCicilan, BUKAN totalPiutang / 30
    @PropertyName("targetHariIni")
    val targetHariIni: Long = 0L,

    // Finansial
    val totalPinjamanAktif: Long = 0L,
    val totalPiutang: Long = 0L,

    // Pembayaran Hari Ini (dari Cloud Functions - direset setiap tengah malam)
    @PropertyName("pembayaranHariIni")
    val pembayaranHariIni: Long = 0L,

    // Timestamp
    val lastUpdated: Long = 0L
) {

    /**
     * Persentase performa hari ini
     *
     * Menggunakan targetHariIni dari Cloud Functions
     * yang dihitung dari hasilSimulasiCicilan (akurat)
     */
    val performancePercentage: Float
        get() = if (targetHariIni > 0) {
            ((pembayaranHariIni.toFloat() / targetHariIni) * 100).coerceIn(0f, 200f)
        } else {
            0f
        }

    val netCashFlow: Long
        get() = pembayaranHariIni

    // Alias untuk backward compatibility
    val tagihanHariIni: Long
        get() = pembayaranHariIni

    // =========================================================================
    // HELPER FUNCTIONS
    // =========================================================================

    fun hasActiveCustomers(): Boolean = nasabahAktif > 0
    fun hasPaymentToday(): Boolean = pembayaranHariIni > 0

    /**
     * Cek apakah hari ini adalah hari libur
     *
     * Logika: Jika ada nasabah aktif tapi target = 0, berarti hari libur
     * Cloud Functions sudah set targetHariIni = 0 untuk hari Minggu/tanggal merah
     */
    fun isHoliday(): Boolean = nasabahAktif > 0 && targetHariIni == 0L

    /**
     * Cek apakah ada nasabah baru hari ini
     */
    fun hasNewCustomersToday(): Boolean = nasabahBaruHariIni > 0

    /**
     * Cek apakah ada nasabah lunas hari ini
     */
    fun hasCompletedLoansToday(): Boolean = nasabahLunasHariIni > 0

    /**
     * ✅ v4: Cek apakah ada nasabah menunggu pencairan
     */
    fun hasCustomersWaitingDisbursement(): Boolean = nasabahMenungguPencairan > 0

    fun getPerformanceLevel(): PerformanceLevel {
        // Jika hari libur, return NEUTRAL
        if (isHoliday()) return PerformanceLevel.NEUTRAL

        return when {
            performancePercentage >= 80f -> PerformanceLevel.EXCELLENT
            performancePercentage >= 60f -> PerformanceLevel.GOOD
            performancePercentage >= 40f -> PerformanceLevel.WARNING
            else -> PerformanceLevel.POOR
        }
    }

    companion object {
        fun empty(adminId: String, adminName: String = "", adminEmail: String = "") = AdminSummary(
            adminId = adminId, adminName = adminName, adminEmail = adminEmail
        )
    }
}

enum class PerformanceLevel {
    EXCELLENT,  // >= 80%
    GOOD,       // >= 60%
    WARNING,    // >= 40%
    POOR,       // < 40%
    NEUTRAL     // Hari libur
}