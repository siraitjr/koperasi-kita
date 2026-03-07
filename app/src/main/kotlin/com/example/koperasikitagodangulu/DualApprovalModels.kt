package com.example.koperasikitagodangulu.models

/**
 * =========================================================================
 * DUAL APPROVAL MODELS - SEQUENTIAL THREE-PHASE APPROVAL
 * =========================================================================
 *
 * Model data untuk mendukung sistem persetujuan bertingkat (Sequential Approval)
 * untuk pinjaman dengan nominal >= Rp 3.000.000
 *
 * ALUR BARU (Sequential Three-Phase):
 * =========================================================================
 * Phase 1 - AWAITING_PIMPINAN:
 *   - Admin mengajukan pinjaman >= 3jt
 *   - HANYA Pimpinan yang menerima notifikasi
 *   - Pimpinan melakukan review dan approve/reject
 *
 * Phase 2 - AWAITING_PENGAWAS:
 *   - Setelah Pimpinan approve/reject
 *   - Pengawas menerima notifikasi
 *   - Pengawas melakukan review final (approve/reject/penyesuaian)
 *   - Keputusan Pengawas adalah FINAL
 *
 * Phase 3 - AWAITING_PIMPINAN_FINAL:
 *   - Setelah Pengawas aksi
 *   - Pimpinan menerima notifikasi hasil keputusan Pengawas
 *   - Pimpinan melakukan konfirmasi final
 *   - Admin lapangan menerima notifikasi hasil akhir
 * =========================================================================
 */

import com.google.firebase.database.PropertyName
import com.example.koperasikitagodangulu.models.BroadcastMessage

/**
 * Status persetujuan individual (untuk masing-masing approver)
 */
object ApprovalStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
}

/**
 * Role yang melakukan approval
 */
object ApproverRole {
    const val PIMPINAN = "pimpinan"
    const val KOORDINATOR = "koordinator"  // ✅ BARU
    const val PENGAWAS = "pengawas"
}

/**
 * =========================================================================
 * Phase/Tahap dalam alur approval sequential (5-PHASE dengan KOORDINATOR)
 * =========================================================================
 *
 * ALUR BARU:
 * Phase 1 (AWAITING_PIMPINAN): Admin mengajukan → Pimpinan review
 * Phase 2 (AWAITING_KOORDINATOR): Pimpinan approve/reject → Koordinator review
 * Phase 3 (AWAITING_PENGAWAS): Koordinator approve/reject → Pengawas review
 * Phase 4 (AWAITING_KOORDINATOR_FINAL): Pengawas approve/reject → Koordinator konfirmasi
 * Phase 5 (AWAITING_PIMPINAN_FINAL): Koordinator konfirmasi → Pimpinan finalisasi
 * COMPLETED: Pimpinan finalisasi → Admin menerima notifikasi final
 * =========================================================================
 */
object ApprovalPhase {
    const val AWAITING_PIMPINAN = "awaiting_pimpinan"                     // Tahap 1: Menunggu review Pimpinan
    const val AWAITING_KOORDINATOR = "awaiting_koordinator"              // Tahap 2: Menunggu review Koordinator (BARU)
    const val AWAITING_PENGAWAS = "awaiting_pengawas"                     // Tahap 3: Menunggu review Pengawas
    const val AWAITING_KOORDINATOR_FINAL = "awaiting_koordinator_final"  // Tahap 4: Menunggu konfirmasi Koordinator (BARU)
    const val AWAITING_PIMPINAN_FINAL = "awaiting_pimpinan_final"        // Tahap 5: Menunggu finalisasi Pimpinan
    const val COMPLETED = "completed"                                     // Selesai
}

/**
 * Data class untuk menyimpan informasi approval dari satu pihak
 */
data class IndividualApproval(
    val status: String = ApprovalStatus.PENDING,
    val by: String = "",           // Email atau nama approver
    val uid: String = "",          // UID approver
    val timestamp: Long = 0L,      // Waktu approval
    val note: String = "",         // Catatan approval
    // ✅ BARU: Field tambahan untuk penyesuaian
    val adjustedAmount: Int = 0,   // Jumlah pinjaman yang disetujui (jika ada penyesuaian)
    val adjustedTenor: Int = 0     // Tenor yang disetujui (jika ada penyesuaian)
) {
    constructor() : this(ApprovalStatus.PENDING, "", "", 0L, "", 0, 0)
}

/**
 * Data class untuk menyimpan status dual approval dengan phase tracking
 * Akan disimpan di dalam Pelanggan sebagai field baru
 */
data class DualApprovalInfo(
    @PropertyName("requiresDualApproval")
    val requiresDualApproval: Boolean = false,  // true jika >= 3jt

    @PropertyName("approvalPhase")
    val approvalPhase: String = ApprovalPhase.AWAITING_PIMPINAN,  // Phase saat ini

    @PropertyName("pimpinanApproval")
    val pimpinanApproval: IndividualApproval = IndividualApproval(),

    // ✅ BARU: Koordinator approval (Phase 2 & 4)
    @PropertyName("koordinatorApproval")
    val koordinatorApproval: IndividualApproval = IndividualApproval(),

    @PropertyName("pengawasApproval")
    val pengawasApproval: IndividualApproval = IndividualApproval(),

    @PropertyName("finalDecision")
    val finalDecision: String = "",  // "approved" / "rejected" / ""

    @PropertyName("finalDecisionBy")
    val finalDecisionBy: String = "", // Role yang membuat keputusan final

    @PropertyName("finalDecisionTimestamp")
    val finalDecisionTimestamp: Long = 0L,

    @PropertyName("rejectionReason")
    val rejectionReason: String = "",

    // ✅ BARU: Tracking untuk finalisasi Koordinator
    @PropertyName("koordinatorFinalConfirmed")
    val koordinatorFinalConfirmed: Boolean = false,

    @PropertyName("koordinatorFinalTimestamp")
    val koordinatorFinalTimestamp: Long = 0L,

    // Tracking untuk finalisasi Pimpinan
    @PropertyName("pimpinanFinalConfirmed")
    val pimpinanFinalConfirmed: Boolean = false,

    @PropertyName("pimpinanFinalTimestamp")
    val pimpinanFinalTimestamp: Long = 0L
) {
    constructor() : this(false, ApprovalPhase.AWAITING_PIMPINAN, IndividualApproval(), IndividualApproval(), IndividualApproval(), "", "", 0L, "", false, 0L, false, 0L)

    /**
     * Cek apakah sudah mendapat persetujuan penuh DAN sudah difinalisasi
     */
    fun isFullyApproved(): Boolean {
        if (!requiresDualApproval) {
            return pimpinanApproval.status == ApprovalStatus.APPROVED
        }
        // Untuk dual approval, perlu pengawas approve DAN pimpinan sudah finalisasi
        return pengawasApproval.status == ApprovalStatus.APPROVED &&
                pimpinanFinalConfirmed &&
                approvalPhase == ApprovalPhase.COMPLETED
    }

    /**
     * Cek apakah sudah ditolak (keputusan final dari pengawas)
     */
    fun isRejected(): Boolean {
        // Dalam alur baru, keputusan final ada di pengawas
        return pengawasApproval.status == ApprovalStatus.REJECTED &&
                pimpinanFinalConfirmed &&
                approvalPhase == ApprovalPhase.COMPLETED
    }

    /**
     * Cek apakah masih dalam proses approval
     */
    fun isPending(): Boolean {
        return approvalPhase != ApprovalPhase.COMPLETED
    }

    /**
     * Cek apakah menunggu review Pimpinan (Phase 1)
     */
    fun isAwaitingPimpinan(): Boolean {
        return approvalPhase == ApprovalPhase.AWAITING_PIMPINAN
    }

    /**
     * Cek apakah menunggu review Pengawas (Phase 3)
     */
    fun isAwaitingPengawas(): Boolean {
        return approvalPhase == ApprovalPhase.AWAITING_PENGAWAS
    }

    /**
     * Cek apakah menunggu review Koordinator (Phase 2)
     */
    fun isAwaitingKoordinator(): Boolean {
        return approvalPhase == ApprovalPhase.AWAITING_KOORDINATOR
    }

    /**
     * Cek apakah menunggu finalisasi Koordinator (Phase 4)
     */
    fun isAwaitingKoordinatorFinal(): Boolean {
        return approvalPhase == ApprovalPhase.AWAITING_KOORDINATOR_FINAL
    }

    /**
     * Cek apakah menunggu finalisasi Pimpinan (Phase 5)
     */
    fun isAwaitingPimpinanFinal(): Boolean {
        return approvalPhase == ApprovalPhase.AWAITING_PIMPINAN_FINAL
    }

    /**
     * Dapatkan siapa yang sudah approve
     */
    fun getApprovedBy(): List<String> {
        val list = mutableListOf<String>()
        if (pimpinanApproval.status == ApprovalStatus.APPROVED) {
            list.add(ApproverRole.PIMPINAN)
        }
        if (pengawasApproval.status == ApprovalStatus.APPROVED) {
            list.add(ApproverRole.PENGAWAS)
        }
        return list
    }

    /**
     * Dapatkan status display untuk UI
     */
    fun getDisplayStatus(): String {
        return when (approvalPhase) {
            ApprovalPhase.AWAITING_PIMPINAN -> "Menunggu Review Pimpinan (1/5)"
            ApprovalPhase.AWAITING_KOORDINATOR -> {
                val pimpinanAction = if (pimpinanApproval.status == ApprovalStatus.APPROVED) "Disetujui" else "Ditolak"
                "Pimpinan: $pimpinanAction • Menunggu Koordinator (2/5)"
            }
            ApprovalPhase.AWAITING_PENGAWAS -> {
                val koordinatorAction = if (koordinatorApproval.status == ApprovalStatus.APPROVED) "Disetujui" else "Ditolak"
                "Koordinator: $koordinatorAction • Menunggu Pengawas (3/5)"
            }
            ApprovalPhase.AWAITING_KOORDINATOR_FINAL -> {
                val pengawasAction = when (pengawasApproval.status) {
                    ApprovalStatus.APPROVED -> "Disetujui"
                    ApprovalStatus.REJECTED -> "Ditolak"
                    else -> "Pending"
                }
                "Pengawas: $pengawasAction • Menunggu Konfirmasi Koordinator (4/5)"
            }
            ApprovalPhase.AWAITING_PIMPINAN_FINAL -> {
                "Koordinator Konfirmasi • Menunggu Finalisasi Pimpinan (5/5)"
            }
            ApprovalPhase.COMPLETED -> {
                when {
                    pengawasApproval.status == ApprovalStatus.REJECTED ->
                        "Ditolak oleh Pengawas"
                    pengawasApproval.status == ApprovalStatus.APPROVED ->
                        "Disetujui"
                    else -> "Selesai"
                }
            }
            else -> "Menunggu Persetujuan"
        }
    }

    /**
     * Dapatkan status singkat untuk card UI
     */
    fun getShortStatus(): String {
        return when (approvalPhase) {
            ApprovalPhase.AWAITING_PIMPINAN -> "Tahap 1/5"
            ApprovalPhase.AWAITING_KOORDINATOR -> "Tahap 2/5"
            ApprovalPhase.AWAITING_PENGAWAS -> "Tahap 3/5"
            ApprovalPhase.AWAITING_KOORDINATOR_FINAL -> "Tahap 4/5"
            ApprovalPhase.AWAITING_PIMPINAN_FINAL -> "Tahap 5/5"
            ApprovalPhase.COMPLETED -> "Selesai"
            else -> "-"
        }
    }
}

/**
 * Threshold untuk dual approval (dalam rupiah)
 */
object DualApprovalThreshold {
    const val MINIMUM_AMOUNT = 3_000_000 // Rp 3.000.000

    /**
     * Cek apakah nominal pinjaman membutuhkan dual approval
     */
    fun requiresDualApproval(besarPinjaman: Int): Boolean {
        return besarPinjaman >= MINIMUM_AMOUNT
    }
}

/**
 * Data class untuk notifikasi pengawas
 * Mirip dengan admin_notifications tapi untuk pengawas
 */
data class PengawasNotification(
    val id: String = "",
    val type: String = "",  // NEW_PENGAJUAN_DUAL, PIMPINAN_REVIEWED, etc.
    val title: String = "",
    val message: String = "",
    val pelangganId: String = "",
    val pelangganNama: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val besarPinjaman: Int = 0,
    val cabangId: String = "",
    val pengajuanId: String = "",
    val pimpinanApprovalStatus: String = "", // Status approval pimpinan
    val pimpinanNote: String = "",           // Catatan dari pimpinan
    val timestamp: Long = 0L,
    val read: Boolean = false
) {
    constructor() : this("", "", "", "", "", "", "", "", 0, "", "", "", "", 0L, false)
}

/**
 * =========================================================================
 * BARU: Data class untuk notifikasi ke Pimpinan setelah Pengawas aksi
 * =========================================================================
 */
data class PimpinanFinalNotification(
    val id: String = "",
    val type: String = "",  // PENGAWAS_REVIEWED
    val title: String = "",
    val message: String = "",
    val pelangganId: String = "",
    val pelangganNama: String = "",
    val adminUid: String = "",
    val besarPinjaman: Int = 0,
    val besarPinjamanDisetujui: Int = 0, // Jika ada penyesuaian dari pengawas
    val cabangId: String = "",
    val pengajuanId: String = "",
    val pengawasApprovalStatus: String = "", // approved/rejected
    val pengawasNote: String = "",
    val timestamp: Long = 0L,
    val read: Boolean = false
) {
    constructor() : this("", "", "", "", "", "", "", 0, 0, "", "", "", "", 0L, false)
}

/**
 * =========================================================================
 * DELETION REQUEST - Request Penghapusan Nasabah
 * =========================================================================
 *
 * Data class untuk menyimpan request penghapusan nasabah dari Admin Lapangan
 * yang memerlukan persetujuan Pengawas sebelum dihapus.
 *
 * ALUR:
 * 1. Admin Lapangan klik "Hapus Nasabah"
 * 2. Request dibuat dan disimpan di deletion_requests/{requestId}
 * 3. Pengawas melihat di tab "Penghapusan" pada PengawasApprovalScreen
 * 4. Pengawas review dan approve/reject
 * 5. Jika approve: Nasabah dihapus dari database
 * 6. Jika reject: Request dihapus, nasabah tetap ada
 *
 * Path Firebase: deletion_requests/{requestId}
 */
object DeletionRequestStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
}

data class DeletionRequest(
    val id: String = "",

    // Informasi Nasabah yang akan dihapus
    val pelangganId: String = "",
    val pelangganNama: String = "",
    val pelangganNik: String = "",
    val pelangganAlamat: String = "",
    val pelangganWilayah: String = "",
    val besarPinjaman: Int = 0,
    val totalPelunasan: Int = 0,
    val sisaUtang: Int = 0,
    val pinjamanKe: Int = 1,
    val statusPelanggan: String = "", // Status sebelum dihapus (Aktif/Lunas/dll)

    // Informasi Admin yang mengajukan penghapusan
    val adminUid: String = "",
    val requestedByUid: String = "",
    val requestedByName: String = "",
    val requestedByEmail: String = "",
    val cabangId: String = "",

    // Alasan penghapusan dari Admin
    val alasanPenghapusan: String = "",

    // Status request
    val status: String = DeletionRequestStatus.PENDING,

    // Informasi approval/rejection oleh Pengawas
    val reviewedByUid: String = "",
    val reviewedByName: String = "",
    val reviewedAt: Long = 0L,
    val catatanPengawas: String = "",

    // Timestamp
    val createdAt: Long = 0L,
    val read: Boolean = false
) {
    constructor() : this(
        "", "", "", "", "", "", 0, 0, 0, 1, "",
        "", "", "", "", "", "", DeletionRequestStatus.PENDING,
        "", "", 0L, "", 0L, false
    )

    /**
     * Cek apakah request masih pending
     */
    fun isPending(): Boolean = status == DeletionRequestStatus.PENDING

    /**
     * Cek apakah sudah disetujui
     */
    fun isApproved(): Boolean = status == DeletionRequestStatus.APPROVED

    /**
     * Cek apakah sudah ditolak
     */
    fun isRejected(): Boolean = status == DeletionRequestStatus.REJECTED

    /**
     * Dapatkan display status
     */
    fun getDisplayStatus(): String = when (status) {
        DeletionRequestStatus.PENDING -> "Menunggu Persetujuan"
        DeletionRequestStatus.APPROVED -> "Disetujui"
        DeletionRequestStatus.REJECTED -> "Ditolak"
        else -> status
    }
}

/**
 * Model untuk Broadcast Message dari Pengawas ke semua karyawan
 */
data class BroadcastMessage(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val timestamp: Long = 0L,
    val active: Boolean = true,
    val expiresAt: Long = 0L // Optional: pesan bisa expire
) {
    constructor() : this("", "", "", "", "", 0L, true, 0L)
}

/**
 * Status untuk Tenor Change Request
 */
object TenorChangeRequestStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
}

/**
 * Model untuk Pengajuan Perubahan Tenor
 */
data class TenorChangeRequest(
    val id: String = "",
    val pelangganId: String = "",
    val pelangganNama: String = "",
    val adminUid: String = "",
    val cabangId: String = "",

    // Tenor info
    val tenorLama: Int = 0,
    val tenorBaru: Int = 0,
    val alasanPerubahan: String = "",
    val besarPinjaman: Int = 0,      // ✅ PASTIKAN ADA INI
    val pinjamanKe: Int = 1,         // ✅ PASTIKAN ADA INI

    // Request info
    val requestedByUid: String = "",
    val requestedByName: String = "",
    val status: String = TenorChangeRequestStatus.PENDING,
    val createdAt: Long = 0L,

    // Review info
    val reviewedByUid: String = "",
    val reviewedByName: String = "",
    val reviewedAt: Long = 0L,
    val catatanPimpinan: String = "",

    val read: Boolean = false
) {
    constructor() : this(
        "", "", "", "", "", 0, 0, "", 0, 1, "", "", TenorChangeRequestStatus.PENDING, 0L, "", "", 0L, "", false
    )
}

// =========================================================================
// PAYMENT DELETION REQUEST - Pengajuan Hapus Pembayaran dengan Approval
// =========================================================================
object PaymentDeletionRequestStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
}

data class PaymentDeletionRequest(
    val id: String = "",

    // Informasi Nasabah
    val pelangganId: String = "",
    val pelangganNama: String = "",
    val pelangganNik: String = "",
    val pelangganWilayah: String = "",

    // Informasi Pembayaran yang akan dihapus
    val pembayaranIndex: Int = 0,           // Index pembayaran di list
    val cicilanKe: Int = 0,                 // Nomor cicilan (untuk tampilan)
    val jumlahPembayaran: Int = 0,          // Jumlah pembayaran yang akan dihapus
    val tanggalPembayaran: String = "",     // Tanggal pembayaran

    // Informasi Keuangan Nasabah
    val totalPelunasan: Int = 0,
    val sudahDibayar: Int = 0,              // Total yang sudah dibayar sebelum hapus
    val sisaUtang: Int = 0,                 // Sisa utang sebelum hapus

    // Informasi Admin yang mengajukan
    val adminUid: String = "",
    val requestedByUid: String = "",
    val requestedByName: String = "",
    val requestedByEmail: String = "",
    val cabangId: String = "",

    // Alasan penghapusan dari Admin
    val alasanPenghapusan: String = "",

    // Status request
    val status: String = PaymentDeletionRequestStatus.PENDING,

    // Informasi approval/rejection oleh Pimpinan
    val reviewedByUid: String = "",
    val reviewedByName: String = "",
    val reviewedAt: Long = 0L,
    val catatanPimpinan: String = "",

    // Timestamp
    val createdAt: Long = 0L,
    val read: Boolean = false
) {
    constructor() : this(
        "", "", "", "", "", 0, 0, 0, "", 0, 0, 0,
        "", "", "", "", "", "", PaymentDeletionRequestStatus.PENDING,
        "", "", 0L, "", 0L, false
    )

    fun isPending(): Boolean = status == PaymentDeletionRequestStatus.PENDING
    fun isApproved(): Boolean = status == PaymentDeletionRequestStatus.APPROVED
    fun isRejected(): Boolean = status == PaymentDeletionRequestStatus.REJECTED

    fun getDisplayStatus(): String = when (status) {
        PaymentDeletionRequestStatus.PENDING -> "Menunggu Persetujuan"
        PaymentDeletionRequestStatus.APPROVED -> "Disetujui"
        PaymentDeletionRequestStatus.REJECTED -> "Ditolak"
        else -> status
    }
}