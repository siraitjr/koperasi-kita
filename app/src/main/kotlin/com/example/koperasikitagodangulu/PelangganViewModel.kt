package com.example.koperasikitagodangulu

import android.util.Log
import com.example.koperasikitagodangulu.models.TenorChangeRequest
import com.example.koperasikitagodangulu.models.TenorChangeRequestStatus
import com.example.koperasikitagodangulu.models.BroadcastMessage
import androidx.compose.runtime.mutableStateListOf
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.google.firebase.database.IgnoreExtraProperties
import androidx.annotation.Keep
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.UUID
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.koperasikitagodangulu.services.CicilanRecalculationService
import java.text.SimpleDateFormat
import java.util.Calendar
import com.example.koperasikitagodangulu.utils.HolidayUtils
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.google.firebase.database.PropertyName
import com.example.koperasikitagodangulu.models.AdminSummary
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.sync.Mutex
import com.google.firebase.functions.ktx.functions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.storage.ktx.storage
import com.google.firebase.storage.StorageMetadata
import java.io.ByteArrayOutputStream
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import com.example.koperasikitagodangulu.optimized.FirebaseCacheManager
import com.example.koperasikitagodangulu.optimized.SmartFirebaseLoader
import com.google.firebase.database.Query
import com.example.koperasikitagodangulu.services.NotificationHelper
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.database.ServerValue
import android.content.SharedPreferences
import com.example.koperasikitagodangulu.offline.OfflineRepository
import com.example.koperasikitagodangulu.offline.SyncWorker
import com.example.koperasikitagodangulu.offline.SyncStatus
import kotlinx.coroutines.flow.collectLatest
import com.example.koperasikitagodangulu.models.PengawasCabangSummary
import com.example.koperasikitagodangulu.models.CabangMetadata
import com.example.koperasikitagodangulu.models.AdminMetadata
import com.example.koperasikitagodangulu.models.PembayaranHarianItem
import com.example.koperasikitagodangulu.models.NasabahBaruItem
import com.example.koperasikitagodangulu.models.NasabahLunasItem
import com.example.koperasikitagodangulu.models.PelangganBermasalahItem
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import com.example.koperasikitagodangulu.models.DualApprovalInfo
import com.example.koperasikitagodangulu.models.IndividualApproval
import com.example.koperasikitagodangulu.models.ApprovalStatus
import com.example.koperasikitagodangulu.models.ApproverRole
import com.example.koperasikitagodangulu.models.DualApprovalThreshold
import com.example.koperasikitagodangulu.models.PengawasNotification
import com.example.koperasikitagodangulu.models.ApprovalPhase
import com.example.koperasikitagodangulu.models.PimpinanFinalNotification
import com.example.koperasikitagodangulu.models.DeletionRequest
import com.example.koperasikitagodangulu.models.DeletionRequestStatus
import com.example.koperasikitagodangulu.models.PaymentDeletionRequest
import com.example.koperasikitagodangulu.models.PaymentDeletionRequestStatus
import com.example.koperasikitagodangulu.models.PencairanSimpananRequest
import com.example.koperasikitagodangulu.models.PencairanSimpananRequestStatus
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import com.example.koperasikitagodangulu.models.BiayaAwalItem
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

data class Pembayaran(
    var jumlah: Int = 0,
    var tanggal: String = "",
    var subPembayaran: List<SubPembayaran> = emptyList()
) {
    constructor() : this(0, "", emptyList())
}

data class CabangSummary(
    val cabangId: String = "",
    val totalNasabah: Int = 0,
    val nasabahAktif: Int = 0,
    val totalPinjamanAktif: Long = 0L
)

data class GlobalSummary(
    val totalNasabah: Int = 0,
    val totalPinjamanAktif: Long = 0L,
    val totalTunggakan: Long = 0L,
    val pembayaranHariIni: Int = 0,
    val lastUpdated: Long = 0L
)

data class SubPembayaran(
    var jumlah: Int = 0,
    var tanggal: String = "",
    var keterangan: String = "Tambah Bayar"
) {
    constructor() : this(0, "", "Tambah Bayar")
}

data class SimulasiCicilan(
    val tanggal: String = "",
    val jumlah: Int = 0,

    @PropertyName("isHariKerja")
    val isHariKerja: Boolean = true,

    @PropertyName("isCompleted")
    val isCompleted: Boolean = false,

    val version: Int = 1,
    val lastUpdated: String = ""
) {
    constructor() : this("", 0, true, false, 1, "")
}

@Keep
@IgnoreExtraProperties
data class Pelanggan(
    // Identitas
    val id: String = "",
    val namaKtp: String = "",
    val nik: String = "",
    val namaPanggilan: String = "",
    val nomorAnggota: String = "",
    val lastUpdated: String = "",
    val namaKtpSuami: String = "",
    val namaKtpIstri: String = "",
    val nikSuami: String = "",
    val nikIstri: String = "",
    val namaPanggilanSuami: String = "",
    val namaPanggilanIstri: String = "",
    val tipePinjaman: String = "dibawah_3jt",
    val alamatKtp: String = "",
    val alamatRumah: String = "",
    val detailRumah: String = "",
    val wilayah: String = "",
    val wilayahNormalized: String = "",
    val noHp: String = "",
    val jenisUsaha: String = "",
    val pinjamanKe: Int = 1,
    val besarPinjaman: Int = 0,
    val jasaPinjaman: Int = 10,
    val admin: Int = 0,
    val simpanan: Int = 0,
    val totalDiterima: Int = 0,
    val totalPelunasan: Int = 0,
    val tenor: Int = 30,
    val tanggalPengajuan: String = "",
    val tanggalDaftar: String = "",
    val tanggalPelunasan: String = "",
    val status: String = "Menunggu Approval",
    val pembayaranList: List<Pembayaran> = emptyList(),
    val besarPinjamanDiajukan: Int = 0,
    val besarPinjamanDisetujui: Int = 0,
    val catatanPerubahanPinjaman: String = "",
    val isPinjamanDiubah: Boolean = false,
    val approvalPimpinan: Boolean = false,
    val approvalPengawas: Boolean = false,
    val ditolakOleh: String = "",
    val alasanPenolakan: String = "",
    val tanggalApprovalPimpinan: String = "",
    val tanggalApprovalPengawas: String = "",
    val adminEmail: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val cabangId: String = "",
    val catatanApproval: String = "",
    val tanggalApproval: String = "",
    val disetujuiOleh: String = "",
    val statusKhusus: String = "",
    val catatanStatusKhusus: String = "",
    val tanggalStatusKhusus: String = "",
    val diberiTandaOleh: String = "",
    val fotoKtpUrl: String = "",
    val fotoKtpSuamiUrl: String = "",
    val fotoKtpIstriUrl: String = "",
    val fotoNasabahUrl: String = "",
    val fotoSerahTerimaUrl: String = "",
    var hasilSimulasiCicilan: List<SimulasiCicilan> = emptyList(),
    val pendingFotoKtpUri: String = "",
    val pendingFotoKtpSuamiUri: String = "",
    val pendingFotoKtpIstriUri: String = "",
    val pendingFotoNasabahUri: String = "",
    val pendingFotoSerahTerimaUri: String = "",
    val statusSerahTerima: String = "",
    val tanggalSerahTerima: String = "",
    val sisaUtangLamaSebelumTopUp: Int = 0,
    val totalPelunasanLamaSebelumTopUp: Int = 0,
    val tarikTabungan: Int = 0,
    val statusPencairanSimpanan: String = "",
    val tanggalLunasCicilan: String = "",
    val tanggalPencairanSimpanan: String = "",
    val dicairkanOleh: String = "",
    val tanggalPencairan: String = "",
    val dualApprovalInfo: DualApprovalInfo? = null,
    val isSynced: Boolean = true
)

/** Safe accessor — filter null entries yang bisa muncul dari Firebase array gaps */
val Pelanggan.safePembayaranList: List<Pembayaran>
    get() = pembayaranList.mapNotNull { it }

data class PelangganDitolak(
    val pelanggan: Pelanggan = Pelanggan(),
    val alasanPenolakan: String = "",
    val tanggalPenolakan: String = "",
    val ditolakOleh: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    constructor() : this(Pelanggan(), "", "", "", 0L)
}

data class AdminNotification(
    val id: String = "",
    val type: String = "", // "REJECTION", "APPROVAL", etc.
    val title: String = "",
    val message: String = "",
    val pelangganId: String = "",
    val pelangganNama: String = "",
    val alasanPenolakan: String = "",
    val catatanPersetujuan: String = "",
    val catatanPengawas: String = "",
    val catatanPimpinan: String = "",

    val pinjamanDiajukan: Int = 0,
    val pinjamanDisetujui: Int = 0,
    val tenorDiajukan: Int = 0,
    val tenorDisetujui: Int = 0,
    val isPinjamanDiubah: Boolean = false,
    val catatanPerubahanPinjaman: String = "",
    val disetujuiOleh: String = "",

    val timestamp: Long = 0L,
    val read: Boolean = false
) {
    constructor() : this("", "", "", "", "", "", "", "", "", "", 0, 0, 0, 0, false, "", "", 0L, false)
}

data class DashboardData(
    val totalPinjaman: String = "Rp 0",
    val jumlahPelanggan: String = "0",
    val pembayaranHariIni: String = "Rp 0",
    val totalTunggakan: String = "Rp 0",
    val targetHarian: String = "Rp 0"
)

data class NotificationData(
    val id: String,
    val type: String,
    val title: String,
    val message: String,
    val priority: String,
    val timestamp: Long
)

data class AdminPelangganGroup(
    val adminId: String?,
    val adminName: String?,
    val pelangganList: List<Pelanggan>
)

data class AdminPaymentSummary(
    val adminId: String,
    val adminName: String,
    val adminEmail: String = "",
    val totalPayment: Int,
    val customerCount: Int
)

data class CustomerPayment(
    val customerId: String,
    val customerName: String,
    val paymentAmount: Int,
    val installmentDetails: List<InstallmentDetail>
)

data class InstallmentDetail(
    val date: String,
    val amount: Int
)

data class PinjamanCalculation(
    val admin: Int,
    val simpanan: Int,
    val jasaPinjaman: Int,
    val totalDiterima: Int,
    val totalPelunasan: Int
)

data class NikValidationResult(
    val isValid: Boolean,
    val isDuplicate: Boolean = false,
    val existingAdminName: String = "",
    val existingAdminUid: String = "",
    val existingPelangganNama: String = "",
    val existingStatus: String = "",
    val message: String = ""
)

enum class NikSearchStatus {
    NOT_FOUND,           // NIK tidak ditemukan - nasabah baru
    ACTIVE_OTHER_ADMIN,  // NIK aktif di admin lain - tidak bisa daftar
    ACTIVE_SELF,         // NIK aktif di admin sendiri - tidak bisa daftar
    LUNAS_SELF,          // NIK lunas di admin sendiri - bisa lanjut ke KelolaKredit
    LUNAS_OTHER          // NIK lunas di admin lain - bisa daftar ulang
}

data class NikSearchResult(
    val found: Boolean,
    val status: NikSearchStatus,
    val pelanggan: Pelanggan? = null,
    val adminName: String = "",
    val adminUid: String = "",
    val cabangName: String = "",
    val pelangganId: String = "",
    val message: String = ""
)

data class SerahTerimaNotification(
    val id: String = "",
    val type: String = "SERAH_TERIMA",
    val title: String = "",
    val message: String = "",
    val pelangganId: String = "",
    val pelangganNama: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val besarPinjaman: Int = 0,
    val tenor: Int = 0,
    val fotoSerahTerimaUrl: String = "",
    val tanggalSerahTerima: String = "",
    val timestamp: Long = 0L,
    val read: Boolean = false
) {
    constructor() : this("", "SERAH_TERIMA", "", "", "", "", "", "", 0, 0, "", "", 0L, false)
}

/**
 * Data class untuk informasi user (Admin Lapangan & Pimpinan)
 * Digunakan di User Management Screen oleh Pengawas
 */
data class UserInfo(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
    val role: String = "",      // "admin" atau "pimpinan"
    val cabang: String = "",    // cabangId
    val cabangName: String = "",
    val type: String = ""       // "admin" atau "pimpinan"
)

sealed class TakeoverStatus {
    object Idle : TakeoverStatus()
    object CheckingOnline : TakeoverStatus()
    object WaitingSync : TakeoverStatus()
    object Synced : TakeoverStatus()
    object SigningIn : TakeoverStatus()
    object Active : TakeoverStatus()
    object Restoring : TakeoverStatus()
    data class Error(val message: String) : TakeoverStatus()
}

class PelangganViewModel(application: Application) : AndroidViewModel(application) {
    private val recalculationService = CicilanRecalculationService()
    private var forceLogoutListener: ValueEventListener? = null
    private var forceLogoutRef: DatabaseReference? = null
    private var loginTimestamp: Long = 0L
    private val _takeoverStatus = MutableStateFlow<TakeoverStatus>(TakeoverStatus.Idle)
    val takeoverStatus: StateFlow<TakeoverStatus> = _takeoverStatus
    private val _isTakeoverMode = MutableStateFlow(false)
    val isTakeoverMode: StateFlow<Boolean> = _isTakeoverMode
    private val _takeoverAdminName = MutableStateFlow<String?>(null)
    val takeoverAdminName: StateFlow<String?> = _takeoverAdminName
    private var remoteTakeoverListener: ValueEventListener? = null
    private var remoteTakeoverRef: DatabaseReference? = null
    private val context = application.applicationContext
    val database =
        FirebaseDatabase.getInstance("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference
    private val _isDarkMode = mutableStateOf(false)
    val isDarkMode: State<Boolean> = _isDarkMode
    private val _isOnline = MutableStateFlow(isOnline())
    val isOnline: StateFlow<Boolean> = _isOnline
    val daftarPelanggan = mutableStateListOf<Pelanggan>()
    val dashboardData: MutableStateFlow<DashboardData> = MutableStateFlow(DashboardData())
    val notifications: MutableStateFlow<List<NotificationData>> = MutableStateFlow(emptyList())
    val adminNotifications: MutableStateFlow<List<AdminNotification>> =
        MutableStateFlow(emptyList())
    val pelangganPerAdmin = mutableStateListOf<AdminPelangganGroup>()

    val isLoading = MutableStateFlow(false)
    private val _adminSummary = MutableStateFlow<List<AdminSummary>>(emptyList())
    val adminSummary: StateFlow<List<AdminSummary>> = _adminSummary
    private val _currentUserRole = MutableStateFlow(UserRole.UNKNOWN)
    val currentUserRole: StateFlow<UserRole> = _currentUserRole
    private val _currentUserCabang = MutableStateFlow<String?>(null)
    val currentUserCabang: StateFlow<String?> = _currentUserCabang
    private val _adminPhotoUrl = MutableStateFlow<String?>(null)
    val adminPhotoUrl: StateFlow<String?> = _adminPhotoUrl
    private val _adminPhotosMap = MutableStateFlow<Map<String, String?>>(emptyMap())
    val adminPhotosMap: StateFlow<Map<String, String?>> = _adminPhotosMap
    private val _cabangSummary = MutableStateFlow<CabangSummary?>(null)
    val cabangSummary: StateFlow<CabangSummary?> = _cabangSummary
    private val _globalSummary = MutableStateFlow<GlobalSummary?>(null)
    val globalSummary: StateFlow<GlobalSummary?> = _globalSummary
    private val roleBasedListenerRefs = mutableListOf<Pair<Query, ValueEventListener>>()
    private var pengajuanApprovalListener: ValueEventListener? = null
    private val _pendingApprovals = mutableStateListOf<Pelanggan>()
    val pendingApprovals: List<Pelanggan> = _pendingApprovals
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore
    private val _hasMoreData = MutableStateFlow(true)
    val hasMoreData: StateFlow<Boolean> = _hasMoreData
    private var lastLoadedKey: String? = null
    private val pageSize = 50 // Load 50 nasabah per page
    private val storage = Firebase.storage
    private val cacheManager = FirebaseCacheManager.getInstance()

    // PENTING: SmartFirebaseLoader memerlukan context untuk LocalStorage
    private val smartLoader = SmartFirebaseLoader(database, context, cacheManager)

    // Flag untuk tracking status loading
    private val _isDataLoaded = MutableStateFlow(false)
    val isDataLoaded: StateFlow<Boolean> = _isDataLoaded

    // Flag untuk mencegah multiple simultaneous loads
    private val _isLoadingInProgress = MutableStateFlow(false)

    // Flag untuk tracking data offline yang belum sync
    private val _hasUnsyncedData = MutableStateFlow(false)
    val hasUnsyncedData: StateFlow<Boolean> = _hasUnsyncedData

    private var _isPendingApprovalListenerActive = false

    fun isPendingApprovalListenerActive(): Boolean = _isPendingApprovalListenerActive

    // Cache timestamp untuk data pimpinan (TTL 5 menit)
    private var _pimpinanCacheTimestamp: Long = 0L
    private val PIMPINAN_CACHE_TTL = 5 * 60 * 1000L

    private val functions: FirebaseFunctions = Firebase.functions

    private val sharedPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("koperasi_prefs", Context.MODE_PRIVATE)
    }

    private var _lastManualNomorAnggota = mutableStateOf("")
    val lastManualNomorAnggota: State<String> = _lastManualNomorAnggota

    // ✅ TAMBAHAN: OfflineRepository untuk offline-first sync
    private val offlineRepo = OfflineRepository.getInstance(application)

    // ✅ TAMBAHAN: LiveData untuk pending sync count (untuk UI)
    private val _pendingSyncCount = MutableStateFlow(0)
    val pendingSyncCount: StateFlow<Int> = _pendingSyncCount

    // ✅ TAMBAHAN: LiveData untuk sync status
    private val _offlineSyncStatus = MutableStateFlow(SyncStatus.IDLE)
    val offlineSyncStatus: StateFlow<SyncStatus> = _offlineSyncStatus

    private val _serahTerimaNotifications = MutableStateFlow<List<SerahTerimaNotification>>(emptyList())
    val serahTerimaNotifications: StateFlow<List<SerahTerimaNotification>> = _serahTerimaNotifications

    // Counter untuk badge notifikasi serah terima
    private val _unreadSerahTerimaCount = MutableStateFlow(0)
    val unreadSerahTerimaCount: StateFlow<Int> = _unreadSerahTerimaCount

    private val _nikValidationResult = MutableStateFlow<NikValidationResult?>(null)
    val nikValidationResult: StateFlow<NikValidationResult?> = _nikValidationResult

    private val _isValidatingNik = MutableStateFlow(false)
    val isValidatingNik: StateFlow<Boolean> = _isValidatingNik

    private val _pengawasSelectedCabangId = MutableStateFlow<String?>(null)
    val pengawasSelectedCabangId: StateFlow<String?> = _pengawasSelectedCabangId

    // Data mentah dari Firebase
    private val _allCabangList = MutableStateFlow<List<CabangMetadata>>(emptyList())
    val allCabangList: StateFlow<List<CabangMetadata>> = _allCabangList

    private val _allCabangSummaries = MutableStateFlow<Map<String, PengawasCabangSummary>>(emptyMap())
    val allCabangSummaries: StateFlow<Map<String, PengawasCabangSummary>> = _allCabangSummaries

    private val _allAdminSummaries = MutableStateFlow<Map<String, AdminSummary>>(emptyMap())
    val allAdminSummaries: StateFlow<Map<String, AdminSummary>> = _allAdminSummaries

    private val _pengawasPembayaranHarian = MutableStateFlow<List<PembayaranHarianItem>>(emptyList())
    val pengawasPembayaranHarian: StateFlow<List<PembayaranHarianItem>> = _pengawasPembayaranHarian

    private val _pengawasNasabahBaru = MutableStateFlow<List<NasabahBaruItem>>(emptyList())
    val pengawasNasabahBaru: StateFlow<List<NasabahBaruItem>> = _pengawasNasabahBaru

    private val _pengawasNasabahLunas = MutableStateFlow<List<NasabahLunasItem>>(emptyList())
    val pengawasNasabahLunas: StateFlow<List<NasabahLunasItem>> = _pengawasNasabahLunas

    private val _pengawasPelangganBermasalah = MutableStateFlow<List<PelangganBermasalahItem>>(emptyList())
    val pengawasPelangganBermasalah: StateFlow<List<PelangganBermasalahItem>> = _pengawasPelangganBermasalah

    // Flag untuk tracking initialization
    private val _pengawasDataLoaded = MutableStateFlow(false)
    val pengawasDataLoaded: StateFlow<Boolean> = _pengawasDataLoaded

    private val _pendingApprovalsPengawas = MutableStateFlow<List<Pelanggan>>(emptyList())
    val pendingApprovalsPengawas: StateFlow<List<Pelanggan>> = _pendingApprovalsPengawas

    // State untuk pengajuan yang menunggu finalisasi Pimpinan
    private val _pendingPimpinanFinal = MutableStateFlow<List<Pelanggan>>(emptyList())
    val pendingPimpinanFinal: StateFlow<List<Pelanggan>> = _pendingPimpinanFinal

    // Notifikasi finalisasi untuk Pimpinan
    private val _pimpinanFinalNotifications = MutableStateFlow<List<PimpinanFinalNotification>>(emptyList())
    val pimpinanFinalNotifications: StateFlow<List<PimpinanFinalNotification>> = _pimpinanFinalNotifications

    // Biaya Awal hari ini
    private val _biayaAwalHariIni = MutableStateFlow(0)
    val biayaAwalHariIni: StateFlow<Int> = _biayaAwalHariIni.asStateFlow()
    private val _kasirUangKasHariIni = MutableStateFlow<List<Triple<String, String, Int>>>(emptyList())
    val kasirUangKasHariIni: StateFlow<List<Triple<String, String, Int>>> = _kasirUangKasHariIni.asStateFlow()

    // =========================================================================
    // KOORDINATOR APPROVAL - Phase 2 & Phase 4
    // =========================================================================
    // State untuk pengajuan yang menunggu review Koordinator (Phase 2)
    private val _pendingApprovalsKoordinator = MutableStateFlow<List<Pelanggan>>(emptyList())
    val pendingApprovalsKoordinator: StateFlow<List<Pelanggan>> = _pendingApprovalsKoordinator

    // State untuk pengajuan yang menunggu finalisasi Koordinator (Phase 4)
    private val _pendingKoordinatorFinal = MutableStateFlow<List<Pelanggan>>(emptyList())
    val pendingKoordinatorFinal: StateFlow<List<Pelanggan>> = _pendingKoordinatorFinal

    // Notifikasi untuk pengawas
    private val _pengawasNotifications = MutableStateFlow<List<PengawasNotification>>(emptyList())
    val pengawasNotifications: StateFlow<List<PengawasNotification>> = _pengawasNotifications

    // Unread count untuk badge
    private val _unreadPengawasNotificationCount = MutableStateFlow(0)
    val unreadPengawasNotificationCount: StateFlow<Int> = _unreadPengawasNotificationCount

    private val _pengawasSerahTerimaNotifications = MutableStateFlow<List<SerahTerimaNotification>>(emptyList())
    val pengawasSerahTerimaNotifications: StateFlow<List<SerahTerimaNotification>> = _pengawasSerahTerimaNotifications

    private val _unreadPengawasSerahTerimaCount = MutableStateFlow(0)
    val unreadPengawasSerahTerimaCount: StateFlow<Int> = _unreadPengawasSerahTerimaCount

    // =========================================================================
    // DELETION REQUESTS - Request Penghapusan Nasabah
    // =========================================================================
    private val _pendingDeletionRequests = MutableStateFlow<List<DeletionRequest>>(emptyList())
    val pendingDeletionRequests: StateFlow<List<DeletionRequest>> = _pendingDeletionRequests

    private val _unreadDeletionRequestCount = MutableStateFlow(0)
    val unreadDeletionRequestCount: StateFlow<Int> = _unreadDeletionRequestCount

    // ✅ BARU: State untuk Pimpinan Deletion Requests (filtered by cabang)
    private val _pimpinanDeletionRequests = MutableStateFlow<List<DeletionRequest>>(emptyList())
    val pimpinanDeletionRequests: StateFlow<List<DeletionRequest>> = _pimpinanDeletionRequests

    // ✅ Payment Deletion Requests State
    private val _pimpinanPaymentDeletionRequests = MutableStateFlow<List<PaymentDeletionRequest>>(emptyList())
    val pimpinanPaymentDeletionRequests: StateFlow<List<PaymentDeletionRequest>> = _pimpinanPaymentDeletionRequests

    private val _unreadPimpinanPaymentDeletionCount = MutableStateFlow(0)
    val unreadPimpinanPaymentDeletionCount: StateFlow<Int> = _unreadPimpinanPaymentDeletionCount

    private val _unreadPimpinanDeletionCount = MutableStateFlow(0)
    val unreadPimpinanDeletionCount: StateFlow<Int> = _unreadPimpinanDeletionCount

    // =========================================================================
    // BROADCAST MESSAGES
    // =========================================================================
    private val _activeBroadcasts = MutableStateFlow<List<BroadcastMessage>>(emptyList())
    val activeBroadcasts: StateFlow<List<BroadcastMessage>> = _activeBroadcasts

    // =========================================================================
    // TENOR CHANGE REQUESTS
    // =========================================================================
    private val _tenorChangeRequests = MutableStateFlow<List<TenorChangeRequest>>(emptyList())
    val tenorChangeRequests: StateFlow<List<TenorChangeRequest>> = _tenorChangeRequests

    private val _unreadTenorChangeCount = MutableStateFlow(0)
    val unreadTenorChangeCount: StateFlow<Int> = _unreadTenorChangeCount

    // =========================================================================
    // PENCAIRAN SIMPANAN REQUESTS
    // =========================================================================
    private val _pendingPencairanSimpananRequests = MutableStateFlow<List<PencairanSimpananRequest>>(emptyList())
    val pendingPencairanSimpananRequests: StateFlow<List<PencairanSimpananRequest>> = _pendingPencairanSimpananRequests

    private val _unreadPencairanSimpananCount = MutableStateFlow(0)
    val unreadPencairanSimpananCount: StateFlow<Int> = _unreadPencairanSimpananCount

    // Biaya Awal untuk Pengawas/Koordinator
    private val _pengawasBiayaAwal = MutableStateFlow<List<BiayaAwalItem>>(emptyList())
    val pengawasBiayaAwal: StateFlow<List<BiayaAwalItem>> = _pengawasBiayaAwal
    private val _pengawasKasirUangKas = MutableStateFlow<List<BiayaAwalItem>>(emptyList())
    val pengawasKasirUangKas: StateFlow<List<BiayaAwalItem>> = _pengawasKasirUangKas

    /**
     * Cabang options untuk dropdown filter
     * REAKTIF: Otomatis update ketika _allCabangList berubah
     */
    val pengawasCabangOptions: StateFlow<List<Pair<String?, String>>> = _allCabangList
        .map { cabangList ->
            val options = mutableListOf<Pair<String?, String>>()
            options.add(null to "Semua Cabang")
            cabangList.forEach { cabang ->
                options.add(cabang.cabangId to cabang.name)
            }
            options.toList()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = listOf(null to "Semua Cabang")
        )

    /**
     * Current summary berdasarkan filter yang dipilih
     * REAKTIF: Otomatis update ketika filter atau data berubah
     */
    val pengawasCurrentSummary: StateFlow<PengawasCabangSummary> = combine(
        _pengawasSelectedCabangId,
        _allCabangSummaries
    ) { selectedId, summaries ->
        if (selectedId == null || selectedId == "all") {
            // Aggregate semua cabang
            PengawasCabangSummary.aggregate(summaries.values.toList())
        } else {
            // Return summary cabang yang dipilih
            summaries[selectedId] ?: PengawasCabangSummary.empty(selectedId)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PengawasCabangSummary.empty()
    )

    /**
     * Admin summaries yang sudah difilter
     * REAKTIF: Otomatis update ketika filter atau data berubah
     */
    val pengawasFilteredAdminSummaries: StateFlow<List<AdminSummary>> = combine(
        _pengawasSelectedCabangId,
        _allAdminSummaries
    ) { selectedId, summaries ->
        val filtered = if (selectedId == null || selectedId == "all") {
            summaries.values.toList()
        } else {
            summaries.values.filter { it.cabang == selectedId }
        }
        filtered.sortedByDescending { it.performancePercentage }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        startRoleDetectionAndInit()
        startNetworkMonitoring()
        startDevicePresence()
        checkTakeoverMode()

        viewModelScope.launch {
            offlineRepo.observePendingSyncCount().collectLatest { count ->
                _pendingSyncCount.value = count
                Log.d("OfflineSync", "📊 Pending sync count: $count")
            }
        }
    }

    companion object {
        private const val TAG = "PelangganVM"

        // Cache TTL yang lebih panjang untuk Pimpinan (data summary jarang berubah)
        private const val PIMPINAN_SUMMARY_CACHE_TTL = 10 * 60 * 1000L  // 10 menit
    }

    /** Sanitize Pelanggan — filter null elements dari pembayaranList & subPembayaran */
    @Suppress("UNCHECKED_CAST")
    private fun sanitizePelanggan(pelanggan: Pelanggan): Pelanggan {
        val rawList = pelanggan.pembayaranList as List<Pembayaran?>
        val safeList = rawList.filterNotNull().map { pembayaran ->
            val rawSub = pembayaran.subPembayaran as List<SubPembayaran?>
            pembayaran.copy(subPembayaran = rawSub.filterNotNull())
        }
        return if (safeList.size != pelanggan.pembayaranList.size) {
            pelanggan.copy(pembayaranList = safeList)
        } else {
            pelanggan
        }
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            try {
                Log.d("Dashboard", "Loading dashboard data...")

                when (_currentUserRole.value) {
                    UserRole.PIMPINAN -> {
                        // Pimpinan: Gunakan data dari summary
                        loadDashboardFromSummary()
                    }

                    UserRole.PENGAWAS, UserRole.KOORDINATOR -> {
                        // Pengawas: Gunakan global summary
                        loadDashboardFromGlobalSummary()
                    }

                    else -> {
                        // Admin: Gunakan daftarPelanggan seperti biasa
                        loadDashboardFromLocalData()
                    }
                }
            } catch (e: Exception) {
                Log.e("Dashboard", "Error: ${e.message}")
            }
        }
    }

    private fun loadDashboardFromGlobalSummary() {
        val global = _globalSummary.value

        if (global == null) {
            dashboardData.value = DashboardData()
            return
        }

        dashboardData.value = DashboardData(
            totalPinjaman = formatRupiah(global.totalPinjamanAktif.toInt()),
            jumlahPelanggan = global.totalNasabah.toString(),
            pembayaranHariIni = formatRupiah(global.pembayaranHariIni),
            totalTunggakan = formatRupiah(global.totalTunggakan.toInt()),
            targetHarian = "Rp 0"
        )

        Log.d("Dashboard", "✅ Pengawas dashboard loaded from global summary")
    }

    private fun loadDashboardFromLocalData() {
        // Implementasi yang sudah ada - tidak berubah
        val totalPinjaman = calculateTotalPinjaman()
        val jumlahPelanggan = calculateJumlahPelanggan()
        val pembayaranHariIni = calculatePembayaranHariIni()
        val totalTunggakan = calculateTotalTunggakan()
        val targetHarian = calculateTargetHarian()

        dashboardData.value = DashboardData(
            totalPinjaman = formatRupiah(totalPinjaman),
            jumlahPelanggan = jumlahPelanggan.toString(),
            pembayaranHariIni = formatRupiah(pembayaranHariIni),
            totalTunggakan = formatRupiah(totalTunggakan),
            targetHarian = formatRupiah(targetHarian)
        )

        Log.d("Dashboard", "✅ Admin dashboard loaded from local data")
    }

    private fun loadDashboardFromSummary() {
        val adminSummaries = _adminSummary.value

        if (adminSummaries.isEmpty()) {
            // Belum ada data - tampilkan 0
            dashboardData.value = DashboardData(
                totalPinjaman = "Rp 0",
                jumlahPelanggan = "0",
                pembayaranHariIni = "Rp 0",
                totalTunggakan = "Rp 0",
                targetHarian = "Rp 0"
            )
            return
        }

        val totalPinjaman = adminSummaries.sumOf { it.totalPinjamanAktif }
        val totalNasabah = adminSummaries.sumOf { it.nasabahAktif }
        val totalPiutang = adminSummaries.sumOf { it.totalPiutang }

        dashboardData.value = DashboardData(
            totalPinjaman = formatRupiah(totalPinjaman.toInt()),
            jumlahPelanggan = totalNasabah.toString(),
            pembayaranHariIni = "Rp 0", // Akan diupdate dari realtime listener
            totalTunggakan = formatRupiah(totalPiutang.toInt()),
            targetHarian = "Rp 0" // Target perlu perhitungan berbeda
        )

        Log.d("Dashboard", "✅ Pimpinan dashboard loaded from summary")
    }

    private fun calculateTotalPinjaman(): Int {
        Log.d("Dashboard", "🔍 Calculating total pinjaman")

        val activeCustomers = daftarPelanggan.filter { pelanggan ->
            val isActive = pelanggan.status == "Aktif" ||
                    pelanggan.status == "Active" ||
                    pelanggan.status.equals("aktif", ignoreCase = true)

            if (isActive) {
                Log.d(
                    "Dashboard",
                    "   ✅ Active: ${pelanggan.namaPanggilan} - Rp ${pelanggan.totalPelunasan}"
                )
            }

            isActive
        }

        Log.d("Dashboard", "👥 Active customers: ${activeCustomers.size}")
        val total = activeCustomers.sumOf { it.totalPelunasan }
        Log.d("Dashboard", "💰 Total pinjaman aktif: Rp $total")

        return total
    }

    fun calculateTargetHarian(): Int {
        Log.d("TargetHarian", "🔍 Menghitung target harian (konsisten RingkasanDashboard)")

        return try {
            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
            val today = dateFormat.format(Date())

            // Batas 3 bulan yang lalu dari tanggal 1 (SAMA dengan RingkasanDashboardScreen)
            val threeMonthsAgo = Calendar.getInstance().apply {
                add(Calendar.MONTH, -3)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

            val activeCustomers = daftarPelanggan.filter { pelanggan ->
                // 1. Status harus Aktif
                val isStatusAktif = pelanggan.status == "Aktif" ||
                        pelanggan.status.equals("aktif", ignoreCase = true) ||
                        pelanggan.status == "Active"
                if (!isStatusAktif) return@filter false

                // 2. Belum lunas
                val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
                    pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
                }
                val isBelumLunas = totalBayar < pelanggan.totalPelunasan.toLong()
                if (!isBelumLunas) return@filter false

                // 3. Bukan MENUNGGU_PENCAIRAN
                if (pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN") return@filter false

                // 4. Tidak lebih dari 3 bulan
                val tglAcuan = pelanggan.tanggalPencairan.ifBlank {
                    pelanggan.tanggalPengajuan.ifBlank { pelanggan.tanggalDaftar }
                }
                val isOverThreeMonths = try {
                    val acuanDate = dateFormat.parse(tglAcuan)
                    acuanDate != null && acuanDate.before(threeMonthsAgo)
                } catch (_: Exception) { false }
                if (isOverThreeMonths) return@filter false

                // 5. Bukan cair hari ini
                val isCairHariIni = pelanggan.tanggalPencairan.isNotBlank() && pelanggan.tanggalPencairan == today
                if (isCairHariIni) return@filter false

                true
            }

            Log.d("TargetHarian", "👥 Total nasabah aktif (filtered): ${activeCustomers.size}")

            // Target = besarPinjaman × 3% (flat, SAMA dengan RingkasanDashboardScreen)
            val totalTarget = activeCustomers.sumOf { pelanggan ->
                val targetHariIni = pelanggan.besarPinjaman * 3 / 100

                Log.d(
                    "TargetHarian",
                    "   📅 ${pelanggan.namaPanggilan}: Rp $targetHariIni (3% dari ${pelanggan.besarPinjaman})"
                )
                targetHariIni
            }

            Log.d("TargetHarian", "🎯 Total target harian: Rp $totalTarget")
            totalTarget
        } catch (e: Exception) {
            Log.e("TargetHarian", "❌ Error menghitung target harian: ${e.message}")
            0
        }
    }

    suspend fun getNextNomorAnggota(): String {
        return withContext(Dispatchers.IO) {
            try {
                val currentUid = Firebase.auth.currentUser?.uid ?: return@withContext "000001"

                // 1. Ambil nomor terbesar dari database lokal (daftarPelanggan)
                val maxFromLocal = daftarPelanggan
                    .mapNotNull { it.nomorAnggota }
                    .filter { it.matches(Regex("\\d+")) }
                    .mapNotNull { it.toIntOrNull() }
                    .maxOrNull() ?: 0

                // 2. Ambil nomor terbesar dari LocalStorage (untuk data offline yang belum di-load)
                val localStorageData = LocalStorage.ambilDataPelanggan(context, currentUid)
                val maxFromLocalStorage = localStorageData
                    .mapNotNull { it.nomorAnggota }
                    .filter { it.matches(Regex("\\d+")) }
                    .mapNotNull { it.toIntOrNull() }
                    .maxOrNull() ?: 0

                // 3. Ambil nomor terakhir dari SharedPreferences (yang diinput manual)
                val lastManualNomor =
                    sharedPrefs.getString("last_nomor_anggota_$currentUid", "0")?.toIntOrNull() ?: 0

                // 4. Ambil nomor terbesar dari Firebase HANYA jika online (dengan timeout)
                var maxFromFirebase = 0
                if (isOnline()) {
                    try {
                        // Gunakan withTimeout untuk mencegah stuck saat koneksi lambat
                        maxFromFirebase = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                            val snapshot = database.child("pelanggan").child(currentUid).get().await()
                            var maxNomor = 0
                            snapshot.children.forEach { child ->
                                val nomor = child.child("nomorAnggota").getValue(String::class.java)
                                if (nomor != null && nomor.matches(Regex("\\d+"))) {
                                    val nomorInt = nomor.toIntOrNull() ?: 0
                                    if (nomorInt > maxNomor) {
                                        maxNomor = nomorInt
                                    }
                                }
                            }
                            maxNomor
                        } ?: 0
                    } catch (e: Exception) {
                        Log.w("NomorAnggota", "⚠️ Gagal baca dari Firebase: ${e.message}")
                    }
                } else {
                    Log.d("NomorAnggota", "📵 Offline mode - skip Firebase query")
                }

                // 5. Ambil MAX dari semua sumber
                val maxNomor = maxOf(maxFromLocal, maxFromLocalStorage, maxFromFirebase, lastManualNomor)

                Log.d("NomorAnggota", "📊 Max from daftarPelanggan: $maxFromLocal")
                Log.d("NomorAnggota", "📊 Max from LocalStorage: $maxFromLocalStorage")
                Log.d("NomorAnggota", "📊 Max from Firebase: $maxFromFirebase")
                Log.d("NomorAnggota", "📊 Last manual: $lastManualNomor")
                Log.d("NomorAnggota", "📊 Final max: $maxNomor")

                // 6. Nomor berikutnya = max + 1
                val nextNomor = maxNomor + 1
                String.format("%06d", nextNomor)

            } catch (e: Exception) {
                Log.e("NomorAnggota", "❌ Error: ${e.message}")
                "000001"
            }
        }
    }

    fun saveManualNomorAnggota(nomorAnggota: String) {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        try {
            val nomorInt = nomorAnggota.filter { it.isDigit() }.toIntOrNull() ?: return

            // Simpan ke SharedPreferences
            sharedPrefs.edit().putString("last_nomor_anggota_$currentUid", nomorInt.toString())
                .apply()

            _lastManualNomorAnggota.value = nomorAnggota

            Log.d("NomorAnggota", "✅ Saved manual nomor anggota: $nomorAnggota (int: $nomorInt)")
        } catch (e: Exception) {
            Log.e("NomorAnggota", "❌ Error saving manual nomor: ${e.message}")
        }
    }

    fun validateNomorAnggota(nomor: String): Pair<Boolean, String> {
        // Hapus leading zeros untuk validasi
        val cleanNomor = nomor.filter { it.isDigit() }

        return when {
            cleanNomor.isEmpty() -> Pair(false, "Nomor anggota tidak boleh kosong")
            cleanNomor.length > 6 -> Pair(false, "Nomor anggota maksimal 6 digit")
            !cleanNomor.all { it.isDigit() } -> Pair(false, "Nomor anggota hanya boleh angka")
            else -> Pair(true, "")
        }
    }

    fun formatNomorAnggota(nomor: String): String {
        val cleanNomor = nomor.filter { it.isDigit() }
        val nomorInt = cleanNomor.toIntOrNull() ?: 0
        return String.format("%06d", nomorInt)
    }

    fun getDisplayName(pelanggan: Pelanggan): String {
        return if (pelanggan.besarPinjaman >= 3000000 && pelanggan.namaPanggilanSuami.isNotBlank() && pelanggan.namaPanggilanIstri.isNotBlank()) {
            "${pelanggan.namaPanggilanSuami} & ${pelanggan.namaPanggilanIstri}"
        } else {
            pelanggan.namaPanggilan
        }
    }

    fun getDisplayNik(pelanggan: Pelanggan): String {
        return if (pelanggan.besarPinjaman >= 3000000 && pelanggan.nikSuami.isNotBlank() && pelanggan.nikIstri.isNotBlank()) {
            "${pelanggan.nikSuami} & ${pelanggan.nikIstri}"
        } else {
            pelanggan.nik
        }
    }

    fun getDisplayNamaKtp(pelanggan: Pelanggan): String {
        return if (pelanggan.besarPinjaman >= 3000000 && pelanggan.namaKtpSuami.isNotBlank() && pelanggan.namaKtpIstri.isNotBlank()) {
            "${pelanggan.namaKtpSuami} & ${pelanggan.namaKtpIstri}"
        } else {
            pelanggan.namaKtp
        }
    }

    private fun getAdminNameById(adminId: String?): String? {
        return adminId ?: "Admin Tidak Diketahui"
    }

    fun loadPelangganGroupedByAdmin() {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        // Cegah multiple simultaneous loads
        if (_isLoadingInProgress.value) {
            Log.d("LoadPelanggan", "⏳ Load already in progress, skipping...")
            return
        }

        isLoading.value = true
        _isLoadingInProgress.value = true

        resolveRoleAndCabang(currentUid) { role, cabang, adminList ->
            viewModelScope.launch {
                try {
                    // OPTIMASI: Cek role sebelum load
                    when (role) {
                        "pengawas", "koordinator" -> {
                            // TIDAK load detail pelanggan
                            Log.d("LoadPelanggan", "Role $role: Loading summary only")
                            val result = smartLoader.loadDataForPengawas(forceRefresh = false)
                            if (result.success && result.globalSummary != null) {
                                _globalSummary.value = GlobalSummary(
                                    totalNasabah = result.globalSummary.totalNasabah,
                                    totalPinjamanAktif = result.globalSummary.totalPinjamanAktif,
                                    totalTunggakan = result.globalSummary.totalTunggakan,
                                    pembayaranHariIni = result.globalSummary.pembayaranHariIni,
                                    lastUpdated = System.currentTimeMillis()
                                )
                            }
                            return@launch
                        }

                        "pimpinan" -> {
                            // Load summary per admin, BUKAN detail
                            if (cabang != null) {
                                Log.d("LoadPelanggan", "Role pimpinan: Loading summaries for cabang $cabang")
                                val result = smartLoader.loadDataForPimpinan(cabang, forceRefresh = false)
                                if (result.success) {
                                    _adminSummary.value = result.adminSummaries
                                    _pendingApprovals.clear()
                                    _pendingApprovals.addAll(result.pendingApprovals)
                                }
                                // ✅ BARU: Load notifikasi serah terima untuk pimpinan
                                loadSerahTerimaNotifications()
                            }
                            return@launch
                        }
                    }

                    // Admin: Load dengan caching
                    Log.d("LoadPelanggan", "Role admin: Loading pelanggan with caching")

                    daftarPelanggan.clear()
                    pelangganPerAdmin.clear()

                    if (adminList.isEmpty()) {
                        Log.d("LoadPelanggan", "No admin list to load")
                        return@launch
                    }

                    val aggregated = mutableListOf<Pelanggan>()

                    // Parallel load dengan caching
                    val results = adminList.map { adminUid ->
                        async(Dispatchers.IO) {
                            val loadResult =
                                smartLoader.loadDataForAdmin(adminUid, forceRefresh = false)
                            when (loadResult) {
                                is SmartFirebaseLoader.LoadResult.Success -> Pair(
                                    adminUid,
                                    loadResult.data
                                )

                                is SmartFirebaseLoader.LoadResult.Error -> {
                                    Log.e(
                                        "LoadPelanggan",
                                        "Error for $adminUid: ${loadResult.message}"
                                    )
                                    Pair(adminUid, emptyList<Pelanggan>())
                                }
                            }
                        }
                    }.awaitAll()

                    // Process results
                    results.forEach { (adminUid, list) ->
                        if (list.isNotEmpty()) {
                            aggregated.addAll(list)
                            try {
                                val adminNameSnap = database.child("metadata").child("admins")
                                    .child(adminUid).get().await()
                                val adminName =
                                    adminNameSnap.child("name").getValue(String::class.java)
                                        ?: adminUid

                                pelangganPerAdmin.add(
                                    AdminPelangganGroup(
                                        adminId = adminUid,
                                        adminName = adminName,
                                        pelangganList = list
                                    )
                                )
                            } catch (e: Exception) {
                                Log.e("LoadPelanggan", "Error getting admin metadata: ${e.message}")
                            }
                        }
                    }

                    // Update state
                    daftarPelanggan.clear()
                    daftarPelanggan.addAll(aggregated.sortedBy { it.namaPanggilan })

                    val sortedGroups = pelangganPerAdmin.sortedBy { it.adminName ?: "" }
                    pelangganPerAdmin.clear()
                    pelangganPerAdmin.addAll(sortedGroups)

                    Log.d(
                        "LoadPelanggan",
                        "✅ Loaded ${daftarPelanggan.size} pelanggan across ${pelangganPerAdmin.size} admins"
                    )

                    loadDashboardData()
                    refreshAdminSummary()
                    _isDataLoaded.value = true

                } catch (e: Exception) {
                    Log.e("LoadPelanggan", "❌ Error: ${e.message}")
                } finally {
                    isLoading.value = false
                    _isLoadingInProgress.value = false
                }
            }
        }
    }

    fun loadAdminNotifications() {
        val adminUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                database.child("admin_notifications").child(adminUid)
                    .addValueEventListener(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            Log.d("Notification", "🔄 TOPBAR - REAL-TIME UPDATE")
                            Log.d(
                                "Notification",
                                "📊 Data received: ${snapshot.childrenCount} items"
                            )

                            val notifList = mutableListOf<AdminNotification>()

                            for (notifSnapshot in snapshot.children) {
                                try {
                                    // ✅ PERBAIKAN: Cek apakah data adalah Map (object) sebelum deserialize
                                    val rawValue = notifSnapshot.value

                                    // Skip jika bukan Map (bisa Boolean, String, Number, dll)
                                    if (rawValue !is Map<*, *>) {
                                        Log.w(
                                            "Notification",
                                            "⚠️ Skipping non-object data: ${notifSnapshot.key} = $rawValue"
                                        )
                                        continue
                                    }

                                    // Safe deserialize dengan try-catch
                                    val notification =
                                        notifSnapshot.getValue(AdminNotification::class.java)
                                    if (notification != null) {
                                        // Simpan key sebagai ID menggunakan copy() karena id adalah val
                                        val notifWithId = if (notification.id.isBlank()) {
                                            notification.copy(id = notifSnapshot.key ?: "")
                                        } else {
                                            notification
                                        }
                                        notifList.add(notifWithId)
                                    }
                                } catch (e: Exception) {
                                    // ✅ PERBAIKAN: Catch error per-item, tidak crash seluruh app
                                    Log.e(
                                        "Notification",
                                        "❌ Error parsing notification ${notifSnapshot.key}: ${e.message}"
                                    )
                                    // Continue ke notifikasi berikutnya
                                }
                            }

                            val sortedList = notifList.sortedByDescending { it.timestamp }
                            adminNotifications.value = sortedList

                            Log.d(
                                "Notification",
                                "✅ TopBar loaded ${sortedList.size} notifications"
                            )
                            Log.d("Notification", "   👁️ Unread: ${sortedList.count { !it.read }}")

                            // DEBUG: Log detail notifikasi unread
                            sortedList.forEach { notif ->
                                if (!notif.read) {
                                    Log.d(
                                        "Notification",
                                        "   🔴 UNREAD: ${notif.title} (${notif.type})"
                                    )
                                }
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e("Notification", "❌ TopBar error: ${error.message}")
                        }
                    })
            } catch (e: Exception) {
                Log.e("Notification", "❌ TopBar exception: ${e.message}")
            }
        }
    }

    fun markNotificationAsRead(notificationId: String) {
        val adminUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                // Hapus fallback ke 'isRead', gunakan hanya 'read'
                database.child("admin_notifications").child(adminUid)
                    .child(notificationId).child("read").setValue(true)
                    .addOnSuccessListener {
                        Log.d("Notification", "✅ Successfully marked as read: $notificationId")
                    }
            } catch (e: Exception) {
                Log.e("Notification", "❌ Error in markNotificationAsRead: ${e.message}")
            }
        }
    }

    fun debugNotificationStructure() {
        val adminUid = Firebase.auth.currentUser?.uid ?: return

        database.child("admin_notifications").child(adminUid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.d("Notification", "🔍 DEBUG Firebase Structure:")
                    snapshot.children.forEach { notifSnapshot ->
                        Log.d("Notification", "   Notifikasi ID: ${notifSnapshot.key}")
                        notifSnapshot.children.forEach { fieldSnapshot ->
                            Log.d(
                                "Notification",
                                "      Field: ${fieldSnapshot.key} = ${fieldSnapshot.value}"
                            )
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Notification", "❌ Debug error: ${error.message}")
                }
            })
    }

    fun deleteNotification(notificationId: String) {
        val adminUid = Firebase.auth.currentUser?.uid ?: return

        database.child("admin_notifications").child(adminUid)
            .child(notificationId).removeValue()
    }

    private fun calculateJumlahPelanggan(): Int {
        val count = daftarPelanggan.count {
            it.status == "Aktif" ||
                    it.status == "Active" ||
                    it.status.equals("aktif", ignoreCase = true)
        }
        Log.d("Dashboard", "📈 Jumlah pelanggan aktif: $count")
        return count
    }

    private fun calculatePembayaranHariIni(): Int {
        val today = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).format(Date())
        Log.d("Dashboard", "📅 Today: $today")

        val todayTotalPayments = daftarPelanggan.sumOf { pelanggan ->
            pelanggan.pembayaranList.sumOf { pembayaran ->
                var totalPembayaranHariIni = 0

                if (pembayaran.tanggal == today) {
                    totalPembayaranHariIni += pembayaran.jumlah
                    Log.d(
                        "Dashboard",
                        "   💵 Payment utama: ${pelanggan.namaPanggilan} - Rp ${pembayaran.jumlah}"
                    )
                }

                val subPembayaranHariIni = pembayaran.subPembayaran
                    .filter { it.tanggal == today }
                    .sumOf { sub ->
                        Log.d(
                            "Dashboard",
                            "   💵 Sub payment: ${pelanggan.namaPanggilan} - Rp ${sub.jumlah}"
                        )
                        sub.jumlah
                    }

                totalPembayaranHariIni + subPembayaranHariIni
            }
        }

        Log.d("Dashboard", "💳 Total pembayaran hari ini (termasuk sub): Rp $todayTotalPayments")

        return todayTotalPayments
    }

    private fun calculateTotalTunggakan(): Int {
        var totalTunggakan = 0

        // ✅ FILTER: Hanya nasabah dengan status Aktif saja
        daftarPelanggan.filter {
            it.status == "Aktif" ||
                    it.status.equals("aktif", ignoreCase = true) ||
                    it.status == "Active"
        }.forEach { pelanggan ->
            // Hitung total yang sudah dibayar (termasuk sub pembayaran)
            val totalDibayar = pelanggan.pembayaranList.sumOf { pembayaran ->
                pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
            }

            val sisaHutang = pelanggan.totalPelunasan - totalDibayar

            if (sisaHutang > 0) {
                Log.d(
                    "Dashboard",
                    "   ⚠️ Tunggakan Aktif: ${pelanggan.namaPanggilan} - Rp $sisaHutang"
                )
                totalTunggakan += sisaHutang
            }
        }

        Log.d("Dashboard", "📉 Total tunggakan nasabah AKTIF: Rp $totalTunggakan")
        return totalTunggakan
    }

    private fun formatRupiah(amount: Int): String {
        val formatter = NumberFormat.getNumberInstance(Locale("in", "ID"))
        return "Rp ${formatter.format(amount)}"
    }

    private fun loadNotifications() {
        viewModelScope.launch {
            try {
                val notifList = mutableListOf<NotificationData>()

                // Hitung pengajuan yang menunggu approval
                val pendingApprovals = daftarPelanggan.count { it.status == "Menunggu Approval" }
                if (pendingApprovals > 0) {
                    notifList.add(
                        NotificationData(
                            id = "approval_${System.currentTimeMillis()}",
                            type = "APPROVAL",
                            title = "$pendingApprovals Pengajuan Pinjaman Menunggu",
                            message = "Ada $pendingApprovals pengajuan pinjaman yang perlu persetujuan",
                            priority = "HIGH",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                // Hitung pelanggan menunggak
                val tunggakanCount = daftarPelanggan.count { pelanggan ->
                    val totalDibayar = pelanggan.pembayaranList.sumOf { it.jumlah }
                    val sisaHutang = pelanggan.totalPelunasan - totalDibayar
                    sisaHutang > 0 && pelanggan.status == "Aktif"
                }

                if (tunggakanCount > 0) {
                    notifList.add(
                        NotificationData(
                            id = "tunggakan_${System.currentTimeMillis()}",
                            type = "TUNGGAKAN",
                            title = "$tunggakanCount Nasabah Menunggak",
                            message = "Total tunggakan mencapai ${
                                formatRupiah(
                                    calculateTotalTunggakan()
                                )
                            }",
                            priority = "MEDIUM",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                notifications.value = notifList
            } catch (e: Exception) {
                Log.e("Dashboard", "Error loading notifications: ${e.message}")
            }
        }
    }

    fun setDarkMode(on: Boolean) {
        _isDarkMode.value = on
        viewModelScope.launch {
            LocalStorage.simpanTema(context, on)
        }
    }

    fun muatTema() {
        viewModelScope.launch {
            val dark = LocalStorage.ambilTema(context)
            _isDarkMode.value = dark
        }
    }

    fun updateStatusKhususPelanggan(
        pelangganId: String,
        statusKhusus: String,
        catatan: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val currentUid = Firebase.auth.currentUser?.uid ?: return
        val currentEmail = Firebase.auth.currentUser?.email ?: ""

        viewModelScope.launch {
            try {
                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                val tanggalSekarang = dateFormat.format(Date())

                // 1. Update di data pelanggan utama
                val pelangganRef = database.child("pelanggan").child(currentUid).child(pelangganId)
                val pelangganSnap = pelangganRef.get().await()
                val pelanggan = pelangganSnap.getValue(Pelanggan::class.java) ?: return@launch

                val updates = mapOf(
                    "statusKhusus" to statusKhusus,
                    "catatanStatusKhusus" to catatan,
                    "tanggalStatusKhusus" to tanggalSekarang,
                    "diberiTandaOleh" to run {
                        val snap = database.child("metadata/admins/$currentUid/name").get().await()
                        snap.getValue(String::class.java) ?: currentEmail
                    }
                )
                pelangganRef.updateChildren(updates).await()

                // 2. ✅ TAMBAHAN: Update/simpan ke node terpisah untuk Pimpinan
                val cabangId = _currentUserCabang.value ?: ""
                if (cabangId.isNotBlank()) {
                    val statusKhususRef = database.child("pelanggan_status_khusus")
                        .child(cabangId).child(pelangganId)

                    if (statusKhusus.isNotBlank()) {
                        // Hitung total piutang
                        val totalBayar =
                            pelanggan.pembayaranList.sumOf { it.jumlah + it.subPembayaran.sumOf { s -> s.jumlah } }
                        val sisaPiutang = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)

                        // Simpan data ringkasan ke node status khusus
                        val statusData = mapOf(
                            "namaPanggilan" to pelanggan.namaPanggilan,
                            "namaKtp" to pelanggan.namaKtp,
                            "statusKhusus" to statusKhusus,
                            "catatanStatusKhusus" to catatan,
                            "tanggalStatusKhusus" to tanggalSekarang,
                            "diberiTandaOleh" to currentEmail,
                            "adminUid" to currentUid,
                            "adminName" to run {
                                val snap = database.child("metadata/admins/$currentUid/name").get().await()
                                snap.getValue(String::class.java) ?: Firebase.auth.currentUser?.displayName ?: ""
                            },
                            "totalPiutang" to sisaPiutang,
                            "besarPinjaman" to pelanggan.besarPinjaman,
                            "wilayah" to pelanggan.wilayah,
                            "noHp" to pelanggan.noHp,
                            "timestamp" to ServerValue.TIMESTAMP
                        )
                        statusKhususRef.setValue(statusData).await()
                        Log.d("StatusKhusus", "✅ Saved to pelanggan_status_khusus")
                    } else {
                        // Jika status khusus dihapus (dikosongkan), hapus dari node
                        statusKhususRef.removeValue().await()
                        Log.d("StatusKhusus", "✅ Removed from pelanggan_status_khusus")
                    }

                    // 3. ✅ TAMBAHAN: Update counter di summary
                    updateStatusKhususCounter(currentUid, cabangId)
                }

                // Update local
                val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                if (index != -1) {
                    daftarPelanggan[index] = daftarPelanggan[index].copy(
                        statusKhusus = statusKhusus,
                        catatanStatusKhusus = catatan,
                        tanggalStatusKhusus = tanggalSekarang,
                        diberiTandaOleh = currentEmail
                    )
                }

                onSuccess()

            } catch (e: Exception) {
                Log.e("StatusKhusus", "Error: ${e.message}")
                onFailure(e)
            }
        }
    }

    // Helper function untuk update counter
    private suspend fun updateStatusKhususCounter(adminUid: String, cabangId: String) {
        try {
            // Hitung dari node status khusus untuk admin ini
            val statusKhususSnap = database.child("pelanggan_status_khusus")
                .child(cabangId)
                .orderByChild("adminUid")
                .equalTo(adminUid)
                .get().await()

            val count = statusKhususSnap.childrenCount.toInt()

            // Update admin summary
            database.child("summary/perAdmin/$adminUid/nasabahStatusKhusus").setValue(count).await()

            // Update cabang summary (total dari semua admin)
            val cabangCount = database.child("pelanggan_status_khusus")
                .child(cabangId)
                .get().await()
                .childrenCount.toInt()

            database.child("summary/perCabang/$cabangId/nasabahStatusKhusus").setValue(cabangCount)
                .await()

            Log.d("StatusKhusus", "✅ Counter updated: admin=$count, cabang=$cabangCount")

        } catch (e: Exception) {
            Log.e("StatusKhusus", "Error updating counter: ${e.message}")
        }
    }

    fun hapusStatusKhususPelanggan(
        pelangganId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        updateStatusKhususPelanggan(
            pelangganId = pelangganId,
            statusKhusus = "",
            catatan = "",
            onSuccess = { onSuccess?.invoke() },
            onFailure = { e -> onFailure?.invoke(e) }
        )
    }

    fun getDaftarStatusKhusus(): List<String> {
        return listOf(
            "MENINGGAL",
            "MELARIKAN_DIRI",
            "MENOLAK_BAYAR",
            "SAKIT",
            "MENUNGGU_PENCAIRAN",
            "LAINNYA"
        )
    }

    fun getColorForStatusKhusus(status: String): Color {
        return when (status) {
            "MENINGGAL" -> Color(0xFF757575) // Gray
            "MELARIKAN_DIRI" -> Color(0xFFF44336) // Red
            "MENOLAK_BAYAR" -> Color(0xFFFF9800) // Orange
            "SAKIT" -> Color(0xFF9C27B0) // Purple
            "MENUNGGU_PENCAIRAN" -> Color(0xFF7C3AED)
            else -> Color(0xFF607D8B) // Blue Gray
        }
    }

    fun getDisplayTextStatusKhusus(status: String): String {
        return when (status) {
            "MENINGGAL" -> "Meninggal"
            "MELARIKAN_DIRI" -> "Melarikan Diri"
            "MENOLAK_BAYAR" -> "Menolak Bayar"
            "SAKIT" -> "Sakit"
            "MENUNGGU_PENCAIRAN" -> "Menunggu Pencairan"
            "LAINNYA" -> "Lainnya"
            else -> ""
        }
    }

//    fun simpanPelangganKeFirebase(
//        pelanggan: Pelanggan,
//        onSuccess: (() -> Unit)? = null,
//        onFailure: ((Exception) -> Unit)? = null,
////        createPengajuanApproval: Boolean = false
//    ) {
//        val currentUid = Firebase.auth.currentUser?.uid
//        val targetAdminUid = if (pelanggan.adminUid.isNotBlank()) pelanggan.adminUid else currentUid
//
//        if (targetAdminUid.isNullOrBlank()) {
//            onFailure?.invoke(Exception("User not authenticated"))
//            return
//        }
//
//        val ref = database.child("pelanggan").child(targetAdminUid)
//
//        val id = if (pelanggan.id.isNotBlank() && !pelanggan.id.startsWith("local-"))
//            pelanggan.id
//        else
//            ref.push().key
//
//        if (id.isNullOrBlank()) {
//            onFailure?.invoke(Exception("Gagal generate ID"))
//            return
//        }
//
//        Log.d("FirebaseSave", "=== MENYIMPAN KE FIREBASE ===")
//        Log.d("FirebaseSave", "Nama: ${pelanggan.namaKtp}")
//        Log.d("FirebaseSave", "Simpanan: ${pelanggan.simpanan}")
//        Log.d("FirebaseSave", "Besar Pinjaman: ${pelanggan.besarPinjaman}")
//
//        val toSave = pelanggan.copy(id = id, adminUid = targetAdminUid, isSynced = true)
//
//        ref.child(id).setValue(toSave)
//            .addOnSuccessListener {
//                viewModelScope.launch {
//                    try {
//                        Log.d("Pengajuan", "📌 Status pelanggan: ${toSave.status}")
//
//                        // ✅ SIMPEL: Hanya cek status, langsung simpan tanpa query pengajuan_approval
//                        if (toSave.status == "Menunggu Approval") {
//                            val adminUid = toSave.adminUid
//                            Log.d("Pengajuan", "📌 AdminUid: $adminUid")
//
//                            val adminMeta = database.child("metadata").child("admins").child(adminUid).get().await()
//                            val cabangId = adminMeta.child("cabang").getValue(String::class.java) ?: ""
//
//                            Log.d("Pengajuan", "📌 CabangId dari metadata: '$cabangId'")
//
//                            if (cabangId.isNotBlank()) {
//                                // ✅ LANGSUNG SIMPAN - tanpa cek duplikat
//                                simpanKePengajuanApproval(toSave, cabangId)
//                                Log.d("Pengajuan", "✅ Pengajuan disimpan untuk: ${toSave.namaPanggilan}")
//                            } else {
//                                Log.e("Pengajuan", "❌ CABANG ID KOSONG!")
//                            }
//                        } else {
//                            Log.d("FirebaseSave", "⏭️ Skip pengajuan (status: ${toSave.status})")
//                        }
//                    } catch (e: Exception) {
//                        Log.e("Pengajuan", "❌ Error: ${e.message}")
//                        e.printStackTrace()
//                    }
//                    onSuccess?.invoke()
//                }
//            }
//            .addOnFailureListener { e -> onFailure?.invoke(e) }
//    }

    fun simpanPelangganKeFirebase(
        pelanggan: Pelanggan,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null,
    ) {
        val currentUid = Firebase.auth.currentUser?.uid
        val targetAdminUid = if (pelanggan.adminUid.isNotBlank()) pelanggan.adminUid else currentUid

        if (targetAdminUid.isNullOrBlank()) {
            onFailure?.invoke(Exception("User not authenticated"))
            return
        }

        val ref = database.child("pelanggan").child(targetAdminUid)

        val id = if (pelanggan.id.isNotBlank() && !pelanggan.id.startsWith("local-"))
            pelanggan.id
        else
            ref.push().key

        if (id.isNullOrBlank()) {
            onFailure?.invoke(Exception("Gagal generate ID"))
            return
        }

        Log.d("FirebaseSave", "=== MENYIMPAN KE FIREBASE (OFFLINE-FIRST) ===")
        Log.d("FirebaseSave", "Nama: ${pelanggan.namaKtp}")
        Log.d("FirebaseSave", "ID: $id")

        val toSave = if (pelanggan.status == "Menunggu Approval") {
            // Reset dualApprovalInfo agar pengajuan ulang mulai dari Phase 1
            pelanggan.copy(
                id = id,
                adminUid = targetAdminUid,
                isSynced = true,
                dualApprovalInfo = if (pelanggan.besarPinjaman >= DualApprovalThreshold.MINIMUM_AMOUNT) {
                    DualApprovalInfo(
                        requiresDualApproval = true,
                        approvalPhase = ApprovalPhase.AWAITING_PIMPINAN
                    )
                } else {
                    null
                }
            )
        } else {
            pelanggan.copy(id = id, adminUid = targetAdminUid, isSynced = true)
        }

        // ✅ PERUBAHAN: Gunakan OfflineRepository untuk offline-first
        viewModelScope.launch {
            try {
                _offlineSyncStatus.value = SyncStatus.SYNCING

                // Konversi Pelanggan ke Map untuk penyimpanan
                val pelangganMap = pelangganToMap(toSave)

                // Simpan via OfflineRepository (akan di-queue dan sync otomatis)
                offlineRepo.savePelanggan(
                    adminUid = targetAdminUid,
                    pelangganId = id,
                    pelanggan = pelangganMap
                )

                // Update local list
                val existingIndex =
                    daftarPelanggan.indexOfFirst { it.id == id || it.id == pelanggan.id }
                if (existingIndex != -1) {
                    daftarPelanggan[existingIndex] = toSave
                } else {
                    daftarPelanggan.add(toSave)
                }

                // Simpan ke local storage untuk offline read
                simpanKeLokal()

                _offlineSyncStatus.value = SyncStatus.SUCCESS
                Log.d("FirebaseSave", "✅ Data di-queue untuk sync: ${toSave.namaPanggilan}")

                // Handle pengajuan approval jika diperlukan
                if (toSave.status == "Menunggu Approval") {
                    var cabangId = toSave.cabangId // Prioritas 1: dari objek pelanggan

                    if (cabangId.isBlank()) {
                        cabangId = _currentUserCabang.value ?: "" // Prioritas 2: dari cache
                    }

                    if (cabangId.isBlank()) {
                        try {
                            val adminMeta = database.child("metadata").child("admins")
                                .child(targetAdminUid).get().await()
                            cabangId = adminMeta.child("cabang").getValue(String::class.java) ?: ""
                        } catch (e: Exception) {
                            Log.w("Pengajuan", "⚠️ Gagal ambil metadata: ${e.message}")
                        }
                    }

                    if (cabangId.isNotBlank()) {
                        // ✅ PERBAIKAN: Update cabangId di pelanggan jika sebelumnya kosong
                        if (toSave.cabangId.isBlank()) {
                            Log.d("Pengajuan", "📝 Menyimpan cabangId '$cabangId' ke pelanggan: ${toSave.namaPanggilan}")
                            database.child("pelanggan").child(targetAdminUid).child(id)
                                .child("cabangId").setValue(cabangId)
                        }
                        simpanKePengajuanApproval(toSave.copy(cabangId = cabangId), cabangId)
                        Log.d("Pengajuan", "✅ Pengajuan disimpan untuk: ${toSave.namaPanggilan}")
                    } else {
                        Log.e("Pengajuan", "❌ CABANG ID KOSONG - pengajuan_approval tidak bisa dibuat untuk: ${toSave.namaPanggilan}")
                        // ✅ PERBAIKAN: Simpan ke pending queue agar bisa di-retry saat cabangId tersedia
                        // Self-healing di loadPendingApprovalsOptimized akan menangani kasus ini
                        Log.d("Pengajuan", "🔧 Pengajuan akan ter-detect oleh self-healing saat Pimpinan buka halaman approval")
                    }

                }

                onSuccess?.invoke()

            } catch (e: Exception) {
                _offlineSyncStatus.value = SyncStatus.ERROR
                Log.e("FirebaseSave", "❌ Error: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    // ✅ TAMBAHAN: Helper function untuk konversi Pelanggan ke Map
    private fun pelangganToMap(pelanggan: Pelanggan): Map<String, Any?> {
        return mapOf(
            "id" to pelanggan.id,
            "namaKtp" to pelanggan.namaKtp,
            "nik" to pelanggan.nik,
            "namaPanggilan" to pelanggan.namaPanggilan,
            "nomorAnggota" to pelanggan.nomorAnggota,
            "lastUpdated" to pelanggan.lastUpdated,
            "namaKtpSuami" to pelanggan.namaKtpSuami,
            "namaKtpIstri" to pelanggan.namaKtpIstri,
            "nikSuami" to pelanggan.nikSuami,
            "nikIstri" to pelanggan.nikIstri,
            "namaPanggilanSuami" to pelanggan.namaPanggilanSuami,
            "namaPanggilanIstri" to pelanggan.namaPanggilanIstri,
            "tipePinjaman" to pelanggan.tipePinjaman,
            "alamatKtp" to pelanggan.alamatKtp,
            "alamatRumah" to pelanggan.alamatRumah,
            "detailRumah" to pelanggan.detailRumah,
            "wilayah" to pelanggan.wilayah,
            "wilayahNormalized" to pelanggan.wilayahNormalized,
            "noHp" to pelanggan.noHp,
            "jenisUsaha" to pelanggan.jenisUsaha,
            "pinjamanKe" to pelanggan.pinjamanKe,
            "besarPinjaman" to pelanggan.besarPinjaman,
            "jasaPinjaman" to pelanggan.jasaPinjaman,
            "admin" to pelanggan.admin,
            "simpanan" to pelanggan.simpanan,
            "totalDiterima" to pelanggan.totalDiterima,
            "totalPelunasan" to pelanggan.totalPelunasan,
            "tenor" to pelanggan.tenor,
            "tanggalPengajuan" to pelanggan.tanggalPengajuan,
            "tanggalDaftar" to pelanggan.tanggalDaftar,
            "tanggalPelunasan" to pelanggan.tanggalPelunasan,
            "status" to pelanggan.status,
            "pembayaranList" to pelanggan.pembayaranList.map { pembayaran ->
                mapOf(
                    "jumlah" to pembayaran.jumlah,
                    "tanggal" to pembayaran.tanggal,
                    "subPembayaran" to pembayaran.subPembayaran.map { sub ->
                        mapOf(
                            "jumlah" to sub.jumlah,
                            "tanggal" to sub.tanggal,
                            "keterangan" to sub.keterangan
                        )
                    }
                )
            },
            "besarPinjamanDiajukan" to pelanggan.besarPinjamanDiajukan,
            "besarPinjamanDisetujui" to pelanggan.besarPinjamanDisetujui,
            "catatanPerubahanPinjaman" to pelanggan.catatanPerubahanPinjaman,
            "isPinjamanDiubah" to pelanggan.isPinjamanDiubah,
            "approvalPimpinan" to pelanggan.approvalPimpinan,
            "approvalPengawas" to pelanggan.approvalPengawas,
            "ditolakOleh" to pelanggan.ditolakOleh,
            "alasanPenolakan" to pelanggan.alasanPenolakan,
            "tanggalApprovalPimpinan" to pelanggan.tanggalApprovalPimpinan,
            "tanggalApprovalPengawas" to pelanggan.tanggalApprovalPengawas,
            "adminEmail" to pelanggan.adminEmail,
            "adminUid" to pelanggan.adminUid,
            "adminName" to pelanggan.adminName,
            "cabangId" to pelanggan.cabangId,
            "catatanApproval" to pelanggan.catatanApproval,
            "tanggalApproval" to pelanggan.tanggalApproval,
            "disetujuiOleh" to pelanggan.disetujuiOleh,
            "statusKhusus" to pelanggan.statusKhusus,
            "catatanStatusKhusus" to pelanggan.catatanStatusKhusus,
            "tanggalStatusKhusus" to pelanggan.tanggalStatusKhusus,
            "diberiTandaOleh" to pelanggan.diberiTandaOleh,
            "fotoKtpUrl" to pelanggan.fotoKtpUrl,
            "fotoKtpSuamiUrl" to pelanggan.fotoKtpSuamiUrl,
            "fotoKtpIstriUrl" to pelanggan.fotoKtpIstriUrl,
            "hasilSimulasiCicilan" to pelanggan.hasilSimulasiCicilan.map { simulasi ->
                mapOf(
                    "tanggal" to simulasi.tanggal,
                    "jumlah" to simulasi.jumlah,
                    "isHariKerja" to simulasi.isHariKerja,
                    "isCompleted" to simulasi.isCompleted,
                    "version" to simulasi.version,
                    "lastUpdated" to simulasi.lastUpdated
                )
            },
            "pendingFotoKtpUri" to pelanggan.pendingFotoKtpUri,
            "pendingFotoKtpSuamiUri" to pelanggan.pendingFotoKtpSuamiUri,
            "pendingFotoKtpIstriUri" to pelanggan.pendingFotoKtpIstriUri,
            "fotoNasabahUrl" to pelanggan.fotoNasabahUrl,  // ✅ BARU
            "fotoSerahTerimaUrl" to pelanggan.fotoSerahTerimaUrl,
            "pendingFotoSerahTerimaUri" to pelanggan.pendingFotoSerahTerimaUri,
            "statusSerahTerima" to pelanggan.statusSerahTerima,
            "tanggalSerahTerima" to pelanggan.tanggalSerahTerima,
            "pendingFotoNasabahUri" to pelanggan.pendingFotoNasabahUri,
            "sisaUtangLamaSebelumTopUp" to pelanggan.sisaUtangLamaSebelumTopUp,
            "totalPelunasanLamaSebelumTopUp" to pelanggan.totalPelunasanLamaSebelumTopUp,
            "tanggalPencairan" to pelanggan.tanggalPencairan,
            "isSynced" to pelanggan.isSynced
        )
    }

    private suspend fun checkExistingPengajuan(cabangId: String, pelangganId: String): Boolean {
        return try {
            val snapshot = database.child("pengajuan_approval").child(cabangId)
                .orderByChild("pelangganId")
                .equalTo(pelangganId)
                .get().await()

            // Cek apakah ada yang masih "Menunggu Approval"
            val exists = snapshot.children.any { child ->
                val status = child.child("status").getValue(String::class.java)
                status == "Menunggu Approval"
            }

            if (exists) {
                Log.d("Pengajuan", "📋 Pengajuan sudah ada untuk pelanggan: $pelangganId")
            }

            exists
        } catch (e: Exception) {
            Log.e("Pengajuan", "Error checking existing pengajuan: ${e.message}")
            false
        }
    }

    fun simpanPelangganLengkap(
        pelangganInput: Pelanggan,
        fotoKtpUri: Uri? = null,
        fotoKtpSuamiUri: Uri? = null,
        fotoKtpIstriUri: Uri? = null,
        fotoNasabahUri: Uri? = null,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        val currentUid = Firebase.auth.currentUser?.uid
        if (currentUid.isNullOrBlank()) {
            onFailure?.invoke(Exception("User not authenticated"))
            return
        }
        viewModelScope.launch {
            try {
                // ✅ PERBAIKAN 1: Cek online status di AWAL
                val online = isOnline()
                Log.d("TambahPelanggan", "📱 Mode: ${if (online) "ONLINE" else "OFFLINE"}")

                // ✅ PERBAIKAN 2: Dapatkan cabangId dari cache jika offline
                val cabangId: String? = if (online) {
                    try {
                        val adminMeta = database.child("metadata").child("admins")
                            .child(currentUid).get().await()
                        adminMeta.child("cabang").getValue(String::class.java)
                    } catch (e: Exception) {
                        Log.w(
                            "TambahPelanggan",
                            "⚠️ Gagal ambil metadata, gunakan cache: ${e.message}"
                        )
                        _currentUserCabang.value // Fallback ke cache
                    }
                } else {
                    // Offline: gunakan cached cabangId
                    _currentUserCabang.value
                }

                Log.d("TambahPelanggan", "📌 CabangId: $cabangId")

                // Hitung nilai-nilai
                val besarPin = pelangganInput.besarPinjaman
                val adminVal = (besarPin * 5) / 100
                val totalDiterimaVal = besarPin - (adminVal + (besarPin * 5) / 100)
                val totalPelunasanVal = (besarPin * 120) / 100

                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                val tanggalDaftar = dateFormat.format(Date())

                // ✅ PERBAIKAN 3: Handle foto berdasarkan status online/offline
                var fotoKtpUrl = ""
                var fotoKtpSuamiUrl = ""
                var fotoKtpIstriUrl = ""

                // Simpan URI foto untuk upload nanti (offline mode)
                var pendingFotoKtpUri = ""
                var pendingFotoKtpSuamiUri = ""
                var pendingFotoKtpIstriUri = ""
                var pendingFotoNasabahUri = ""

                var fotoNasabahUrl = ""  // ✅ BARU

                if (online) {
                    // Online: Upload foto langsung
                    try {
                        val (ktpUrl, ktpSuamiUrl, ktpIstriUrl) = uploadKtpImages(
                            currentUid = currentUid,
                            fotoKtpUri = fotoKtpUri,
                            fotoKtpSuamiUri = fotoKtpSuamiUri,
                            fotoKtpIstriUri = fotoKtpIstriUri
                        )
                        fotoKtpUrl = ktpUrl
                        fotoKtpSuamiUrl = ktpSuamiUrl
                        fotoKtpIstriUrl = ktpIstriUrl

                        // ✅ BARU: Upload foto nasabah jika ada (gunakan fungsi yang sudah ada)
                        if (fotoNasabahUri != null) {
                            try {
                                val tempId = "temp_${System.currentTimeMillis()}"
                                fotoNasabahUrl = uploadFotoKtp(fotoNasabahUri, currentUid, tempId, "nasabah") ?: ""
                                Log.d("TambahPelanggan", "✅ Foto nasabah berhasil diupload")
                            } catch (e: Exception) {
                                Log.w("TambahPelanggan", "⚠️ Gagal upload foto nasabah: ${e.message}")
                                pendingFotoNasabahUri = fotoNasabahUri.toString()
                            }
                        }

                        Log.d("TambahPelanggan", "✅ Foto berhasil diupload")
                    } catch (e: Exception) {
                        Log.w("TambahPelanggan", "⚠️ Gagal upload foto: ${e.message}")
                        // Simpan URI untuk upload nanti
                        pendingFotoKtpUri = fotoKtpUri?.toString() ?: ""
                        pendingFotoKtpSuamiUri = fotoKtpSuamiUri?.toString() ?: ""
                        pendingFotoKtpIstriUri = fotoKtpIstriUri?.toString() ?: ""
                        pendingFotoNasabahUri = fotoNasabahUri?.toString() ?: ""  // ✅ BARU
                    }
                } else {
                    // Offline: Simpan URI untuk upload nanti
                    pendingFotoKtpUri = fotoKtpUri?.toString() ?: ""
                    pendingFotoKtpSuamiUri = fotoKtpSuamiUri?.toString() ?: ""
                    pendingFotoKtpIstriUri = fotoKtpIstriUri?.toString() ?: ""
                    pendingFotoNasabahUri = fotoNasabahUri?.toString() ?: ""  // ✅ BARU
                    Log.d("TambahPelanggan", "📱 Foto URI disimpan untuk upload nanti")
                }

                val base = pelangganInput.copy(
                    admin = adminVal,
                    totalDiterima = totalDiterimaVal,
                    totalPelunasan = totalPelunasanVal,
                    status = "Menunggu Approval",
                    tanggalDaftar = tanggalDaftar,
                    tanggalPengajuan = pelangganInput.tanggalPengajuan,
                    adminUid = currentUid,
                    cabangId = cabangId ?: "",
                    fotoKtpUrl = fotoKtpUrl,
                    fotoKtpSuamiUrl = fotoKtpSuamiUrl,
                    fotoKtpIstriUrl = fotoKtpIstriUrl,
                    fotoNasabahUrl = fotoNasabahUrl,  // ✅ BARU
                    pendingFotoKtpUri = pendingFotoKtpUri,
                    pendingFotoKtpSuamiUri = pendingFotoKtpSuamiUri,
                    pendingFotoKtpIstriUri = pendingFotoKtpIstriUri,
                    pendingFotoNasabahUri = pendingFotoNasabahUri,
                    lastUpdated = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date())
                )

                if (online) {
                    // ✅ PERBAIKAN 5: Online mode - sync dulu, lalu simpan ke Firebase
                    syncOfflineData {
                        simpanPelangganKeFirebase(
                            base,
                            onSuccess = {
                                Log.d("TambahPelanggan", "✅ Data berhasil disimpan ke Firebase")
                                // Panggil callback onSuccess dari parameter
                                onSuccess?.invoke()
                            },
//                            onFailure = { e ->
//                                // Jika gagal ke Firebase, simpan ke lokal sebagai fallback
//                                Log.w("TambahPelanggan", "⚠️ Gagal simpan ke Firebase, simpan lokal: ${e.message}")
//                                val tempId = "local-${UUID.randomUUID()}"
//                                val localP = base.copy(id = tempId, isSynced = false)
//                                daftarPelanggan.add(localP)
//                                simpanKeLokal()
//                                onSuccess?.invoke() // Tetap sukses karena tersimpan lokal
//                            }
                            onFailure = { e ->
                                // Jika gagal, data sudah tersimpan di Room DB oleh SyncManager
                                Log.w("TambahPelanggan", "⚠️ Firebase sync pending: ${e.message}")
                                Log.d(
                                    "TambahPelanggan",
                                    "✅ Data sudah di Room DB, akan retry otomatis"
                                )
                                onSuccess?.invoke()
                            }
                        )
                    }
//                } else {
//                    // ✅ PERBAIKAN 6: Offline mode - simpan ke lokal dan LANGSUNG panggil onSuccess
//                    val tempId = "local-${UUID.randomUUID()}"
//                    val localP = base.copy(
//                        id = tempId,
//                        isSynced = false,
//                        adminUid = currentUid,
//                        cabangId = cabangId ?: ""
//                    )
//                    daftarPelanggan.add(localP)
//                    simpanKeLokal()
//
//                    Log.d("TambahPelanggan", "📱 Data disimpan lokal (offline): $tempId")
//                    Log.d("TambahPelanggan", "📱 Akan otomatis sync saat online")
//
//                    // ✅ PERBAIKAN KRITIS: Panggil onSuccess agar screen navigate back!
//                    onSuccess?.invoke()
//                }
                } else {
                    // ✅ PERBAIKAN: Offline mode - TETAP gunakan simpanPelangganKeFirebase
                    // Ini akan menyimpan ke Room DB via SyncManager dan start Foreground Service
                    Log.d("TambahPelanggan", "📱 Mode OFFLINE - menyimpan via SyncManager")

                    simpanPelangganKeFirebase(
                        base,
                        onSuccess = {
                            Log.d(
                                "TambahPelanggan",
                                "✅ Data disimpan ke Room DB, akan sync saat online"
                            )
                            onSuccess?.invoke()
                        },
                        onFailure = { e ->
                            // Fallback: simpan ke lokal jika SyncManager juga gagal
                            Log.e(
                                "TambahPelanggan",
                                "❌ SyncManager error, fallback ke lokal: ${e.message}"
                            )
                            val tempId = "local-${UUID.randomUUID()}"
                            val localP = base.copy(
                                id = tempId,
                                isSynced = false,
                                adminUid = currentUid,
                                cabangId = cabangId ?: ""
                            )
                            daftarPelanggan.add(localP)
                            simpanKeLokal()
                            onSuccess?.invoke()
                        }
                    )
                }

//            } catch (e: Exception) {
//                Log.e("TambahPelanggan", "❌ Error: ${e.message}")
//                e.printStackTrace()
//
//                // ✅ PERBAIKAN 7: Bahkan jika ada error, coba simpan ke lokal
//                try {
//                    val tempId = "local-${UUID.randomUUID()}"
//                    val besarPin = pelangganInput.besarPinjaman
//                    val adminVal = (besarPin * 5) / 100
//                    val totalDiterimaVal = besarPin - (adminVal + (besarPin * 5) / 100)
//                    val totalPelunasanVal = (besarPin * 120) / 100
//                    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
//
//                    val emergencyLocal = pelangganInput.copy(
//                        id = tempId,
//                        isSynced = false,
//                        admin = adminVal,
//                        totalDiterima = totalDiterimaVal,
//                        totalPelunasan = totalPelunasanVal,
//                        status = "Menunggu Approval",
//                        tanggalDaftar = dateFormat.format(Date()),
//                        tanggalPengajuan = dateFormat.format(Date()),
//                        adminUid = currentUid,
//                        cabangId = _currentUserCabang.value ?: "",
//                        pendingFotoKtpUri = fotoKtpUri?.toString() ?: "",
//                        pendingFotoKtpSuamiUri = fotoKtpSuamiUri?.toString() ?: "",
//                        pendingFotoKtpIstriUri = fotoKtpIstriUri?.toString() ?: "",
//                        lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
//                    )
//                    daftarPelanggan.add(emergencyLocal)
//                    simpanKeLokal()
//
//                    Log.d("TambahPelanggan", "🆘 Emergency save ke lokal berhasil: $tempId")
//                    onSuccess?.invoke() // Tetap berhasil karena tersimpan lokal
//
//                } catch (localError: Exception) {
//                    Log.e("TambahPelanggan", "❌ Emergency save juga gagal: ${localError.message}")
//                    onFailure?.invoke(e) // Baru panggil onFailure jika benar-benar gagal total
            } catch (e: Exception) {
                Log.e("TambahPelanggan", "❌ Error: ${e.message}")
                e.printStackTrace()

                // Emergency save - gunakan simpanPelangganKeFirebase juga
                try {
                    val tempId = "local-${UUID.randomUUID()}"
                    val besarPin = pelangganInput.besarPinjaman
                    val adminVal = (besarPin * 5) / 100
                    val totalDiterimaVal = besarPin - (adminVal + (besarPin * 5) / 100)
                    val totalPelunasanVal = (besarPin * 120) / 100
                    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))

                    val emergencyLocal = pelangganInput.copy(
                        id = tempId,
                        isSynced = false,
                        admin = adminVal,
                        totalDiterima = totalDiterimaVal,
                        totalPelunasan = totalPelunasanVal,
                        status = "Menunggu Approval",
                        tanggalDaftar = dateFormat.format(Date()),
                        tanggalPengajuan = pelangganInput.tanggalPengajuan,
                        adminUid = currentUid,
                        cabangId = _currentUserCabang.value ?: "",
                        pendingFotoKtpUri = fotoKtpUri?.toString() ?: "",
                        pendingFotoKtpSuamiUri = fotoKtpSuamiUri?.toString() ?: "",
                        pendingFotoKtpIstriUri = fotoKtpIstriUri?.toString() ?: "",
                        lastUpdated = SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date())
                    )

                    // Gunakan SyncManager untuk emergency save juga
                    simpanPelangganKeFirebase(
                        emergencyLocal,
                        onSuccess = {
                            Log.d("TambahPelanggan", "🆘 Emergency save via SyncManager berhasil")
                            onSuccess?.invoke()
                        },
                        onFailure = { err ->
                            // Final fallback
                            Log.e("TambahPelanggan", "🆘 Final fallback ke lokal: ${err.message}")
                            daftarPelanggan.add(emergencyLocal)
                            simpanKeLokal()
                            onSuccess?.invoke()
                        }
                    )
                } catch (localError: Exception) {
                    Log.e("TambahPelanggan", "❌ Emergency save juga gagal: ${localError.message}")
                    onFailure?.invoke(e)
                }
            }
        }
    }

    private suspend fun uploadKtpImages(
        currentUid: String,
        fotoKtpUri: Uri?,
        fotoKtpSuamiUri: Uri?,
        fotoKtpIstriUri: Uri?
    ): Triple<String, String, String> {
        val tempId = "temp_${System.currentTimeMillis()}"

        // ✅ PERBAIKAN: Upload masing-masing foto secara independen
        // Jika satu gagal, yang lain tetap berhasil
        val ktpUrl = fotoKtpUri?.let {
            try {
                uploadFotoKtp(it, currentUid, tempId, "utama") ?: ""
            } catch (e: Exception) {
                Log.e("UploadKTP", "⚠️ Gagal upload foto KTP utama: ${e.message}")
                ""
            }
        } ?: ""

        val ktpSuamiUrl = fotoKtpSuamiUri?.let {
            try {
                uploadFotoKtp(it, currentUid, tempId, "suami") ?: ""
            } catch (e: Exception) {
                Log.e("UploadKTP", "⚠️ Gagal upload foto KTP suami: ${e.message}")
                ""
            }
        } ?: ""

        val ktpIstriUrl = fotoKtpIstriUri?.let {
            try {
                uploadFotoKtp(it, currentUid, tempId, "istri") ?: ""
            } catch (e: Exception) {
                Log.e("UploadKTP", "⚠️ Gagal upload foto KTP istri: ${e.message}")
                ""
            }
        } ?: ""

        return Triple(ktpUrl, ktpSuamiUrl, ktpIstriUrl)
    }

    fun updatePelanggan(pelanggan: Pelanggan) {
        val index = daftarPelanggan.indexOfFirst { it.id == pelanggan.id }
        if (index != -1) {
            daftarPelanggan[index] = pelanggan
            simpanPelangganKeFirebase(pelanggan)
            simpanKeLokal()
        }
    }

    fun tambahPembayaran(id: String, jumlah: Int, tanggal: String) {
        val index = daftarPelanggan.indexOfFirst { it.id == id }
        if (index != -1) {
            val pelanggan = daftarPelanggan[index]
            val pembayaran = Pembayaran(jumlah, tanggal)
            val updatedPembayaranList = pelanggan.pembayaranList.toMutableList().apply {
                add(pembayaran)
            }

            val totalDibayar = updatedPembayaranList.sumOf { p ->
                p.jumlah + p.subPembayaran.sumOf { it.jumlah }
            }
            val status = if (totalDibayar >= pelanggan.totalPelunasan) "Lunas" else "Aktif"

            // ✅ PERBAIKAN: Tandai isSynced = false jika offline agar data lokal diprioritaskan saat merge
            val updatedPelanggan = pelanggan.copy(
                pembayaranList = updatedPembayaranList,
                status = status,
                isSynced = isOnline(), // false jika offline, true jika online
                lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )

            // Update local list
            daftarPelanggan[index] = updatedPelanggan

            // ✅ PERBAIKAN: Simpan HANYA pembayaran baru ke path terpisah
            // Ini akan trigger Cloud Function onPembayaranAdded
            val pembayaranIndex = updatedPembayaranList.size - 1
            val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid } ?: return

            viewModelScope.launch {
                val pembayaranMap = mapOf(
                    "jumlah" to jumlah,
                    "tanggal" to tanggal,
                    "subPembayaran" to emptyList<Map<String, Any>>()
                )

                offlineRepo.addPembayaran(
                    adminUid = adminUid,
                    pelangganId = pelanggan.id,
                    pembayaranIndex = pembayaranIndex,
                    pembayaran = pembayaranMap
                )

                // Update status jika perlu
                if (status != pelanggan.status) {
                    offlineRepo.updatePelanggan(
                        adminUid = adminUid,
                        pelangganId = pelanggan.id,
                        updateData = mapOf("status" to status)
                    )
                }
            }

            simpanKeLokal()
            Log.d("Pembayaran", "✅ Pembayaran disimpan: Rp $jumlah (isSynced=${updatedPelanggan.isSynced})")

            if (status == "Lunas") {
                updateStatusLunasCicilan(id)
            }
        }
    }

    private var pelangganListener: ValueEventListener? = null

    fun listenPelangganRealtime() {
        // deprecated: use role detection based initialization
        startRoleDetectionAndInit()
    }

    fun refreshAdminSummary() {
        viewModelScope.launch {
            _adminSummary.value = calculateAdminSummary(daftarPelanggan)
            Log.d("PelangganVM", "🔄 Manual refresh admin summary: ${_adminSummary.value.size} items")
        }
    }

    private fun calculateAdminSummary(pelangganList: List<Pelanggan>): List<AdminSummary> {
        return try {
            Log.d("AdminSummary", "Memulai perhitungan summary admin v4...")
            Log.d("AdminSummary", "Total pelanggan: ${pelangganList.size}")

            val adminMap = mutableMapOf<String, MutableList<Pelanggan>>()

            // Kelompokkan pelanggan per admin
            pelangganList.forEach { pelanggan ->
                try {
                    if (pelanggan.adminUid.isNotBlank()) {
                        adminMap.getOrPut(pelanggan.adminUid) { mutableListOf() }.add(pelanggan)
                    }
                } catch (e: Exception) {
                    Log.e("AdminSummary", "Error processing pelanggan ${pelanggan.id}: ${e.message}")
                }
            }

            val result = adminMap.map { (adminId, pelangganList) ->
                try {
                    val firstPelanggan = pelangganList.firstOrNull()
                    val adminEmail = firstPelanggan?.adminEmail ?: ""
                    val adminName = firstPelanggan?.adminName ?: "Admin Tidak Diketahui"

                    val totalPelanggan = pelangganList.size

                    // ✅ v4: HITUNG nasabahAktif - SAMA PERSIS dengan RingkasanDashboardScreen.kt
                    // Nasabah aktif = status Aktif DAN belum lunas cicilan DAN bukan MENUNGGU_PENCAIRAN
                    val nasabahAktif = pelangganList.count { pelanggan ->
                        val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
                            pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
                        }
                        val isBelumLunas = totalBayar < pelanggan.totalPelunasan.toLong()
                        val isMenungguPencairan = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN"
                        val isStatusAktif = pelanggan.status == "Aktif" ||
                                pelanggan.status.equals("aktif", ignoreCase = true) ||
                                pelanggan.status == "Active"

                        isBelumLunas && !isMenungguPencairan && isStatusAktif
                    }

                    // ✅ v4: HITUNG nasabahLunas - SAMA PERSIS dengan RingkasanDashboardScreen.kt
                    // Nasabah lunas = statusPencairanSimpanan == "Dicairkan"
                    val nasabahLunas = pelangganList.count { pelanggan ->
                        pelanggan.statusPencairanSimpanan == "Dicairkan"
                    }

                    // ✅ v4: HITUNG nasabahMenungguPencairan
                    // Kondisi 1: statusKhusus == "MENUNGGU_PENCAIRAN" (ditandai manual)
                    // Kondisi 2: Sudah lunas cicilan tapi belum dicairkan
                    val nasabahMenungguPencairan = pelangganList.count { pelanggan ->
                        val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
                            pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
                        }
                        val isLunasCicilan = totalBayar >= pelanggan.totalPelunasan.toLong() && pelanggan.totalPelunasan > 0
                        val isMenungguPencairanManual = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN"
                        val isBelumDicairkan = pelanggan.statusPencairanSimpanan != "Dicairkan"

                        isMenungguPencairanManual || (isLunasCicilan && isBelumDicairkan)
                    }

                    // ✅ v4: HITUNG Pinjaman aktif HANYA dari nasabah aktif (exclude MENUNGGU_PENCAIRAN)
                    val totalPinjamanAktif = pelangganList
                        .filter { pelanggan ->
                            val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
                                pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
                            }
                            val isBelumLunas = totalBayar < pelanggan.totalPelunasan.toLong()
                            val isMenungguPencairan = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN"
                            val isStatusAktif = pelanggan.status == "Aktif" ||
                                    pelanggan.status.equals("aktif", ignoreCase = true) ||
                                    pelanggan.status == "Active"

                            isBelumLunas && !isMenungguPencairan && isStatusAktif
                        }
                        .sumOf { it.totalPelunasan.toLong() }

                    val totalPembayaranAktif = pelangganList
                        .filter { pelanggan ->
                            val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
                                pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
                            }
                            val isBelumLunas = totalBayar < pelanggan.totalPelunasan.toLong()
                            val isMenungguPencairan = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN"
                            val isStatusAktif = pelanggan.status == "Aktif" ||
                                    pelanggan.status.equals("aktif", ignoreCase = true) ||
                                    pelanggan.status == "Active"

                            isBelumLunas && !isMenungguPencairan && isStatusAktif
                        }
                        .sumOf { pelanggan ->
                            pelanggan.pembayaranList.sumOf { pembayaran ->
                                pembayaran.jumlah.toLong() +
                                        pembayaran.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
                            }
                        }

                    // Total piutang = total pinjaman aktif - total pembayaran
                    val totalPiutang = (totalPinjamanAktif - totalPembayaranAktif).coerceAtLeast(0)

                    AdminSummary(
                        adminId = adminId,
                        adminEmail = adminEmail,
                        adminName = adminName,
                        totalPelanggan = totalPelanggan,
                        totalPinjamanAktif = totalPinjamanAktif,
                        nasabahAktif = nasabahAktif,
                        nasabahLunas = nasabahLunas,
                        nasabahMenungguPencairan = nasabahMenungguPencairan, // ✅ v4: Tambah field baru
                        totalPiutang = totalPiutang
                    )
                } catch (e: Exception) {
                    Log.e("AdminSummary", "Error creating summary for admin $adminId: ${e.message}")
                    AdminSummary(
                        adminId = adminId,
                        adminEmail = "",
                        adminName = "Error Loading",
                        totalPelanggan = 0,
                        totalPinjamanAktif = 0L,
                        nasabahAktif = 0,
                        nasabahLunas = 0,
                        nasabahMenungguPencairan = 0,
                        totalPiutang = 0L
                    )
                }
            }.sortedByDescending { it.totalPelanggan }

            result
        } catch (e: Exception) {
            Log.e("AdminSummary", "Error calculating admin summary: ${e.message}")
            emptyList()
        }
    }

    fun debugFirebaseStructure() {
        val ref = database.child("pelanggan")

        Log.d("Firebase", "🏗️ Starting Firebase Structure Debug...")

        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d("Firebase", "🏗️ Firebase Structure Debug:")
                Log.d("Firebase", "   Root 'pelanggan' has ${snapshot.childrenCount} children")

                if (snapshot.childrenCount == 0L) {
                    Log.d("Firebase", "   ⚠️ No data found in 'pelanggan' root")
                    return
                }

                snapshot.children.forEach { adminSnapshot ->
                    Log.d("Firebase", "   👤 Admin UID: ${adminSnapshot.key}")
                    Log.d("Firebase", "      📊 Pelanggan count: ${adminSnapshot.childrenCount}")

                    if (adminSnapshot.childrenCount == 0L) {
                        Log.d("Firebase", "      ⚠️ No pelanggan for this admin")
                        return@forEach
                    }

                    adminSnapshot.children.forEachIndexed { index, pelangganSnapshot ->
                        if (index < 3) { // Batasi log untuk 3 item pertama saja
                            Log.d("Firebase", "      🔹 Pelanggan ID: ${pelangganSnapshot.key}")
                            // Coba parse untuk melihat field yang ada
                            val pelanggan = pelangganSnapshot.getValue(Pelanggan::class.java)
                            if (pelanggan != null) {
                                Log.d("Firebase", "         ✅ Nama: ${pelanggan.namaPanggilan}, Status: ${pelanggan.status}")
                            } else {
                                Log.d("Firebase", "         ❌ Failed to parse pelanggan")
                            }
                        }
                    }

                    if (adminSnapshot.childrenCount > 3) {
                        Log.d("Firebase", "      ... and ${adminSnapshot.childrenCount - 3} more")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "❌ Debug error: ${error.message}")
            }
        })
    }

    fun syncOfflineData(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            syncOfflineDataWithRetry()
            onComplete?.invoke()
        }
    }

    private suspend fun syncOfflineDataInternal() {
        if (!isOnline()) {
            Log.d("Sync", "📵 Tidak ada koneksi, skip sync")
            return
        }

        val adminUid = Firebase.auth.currentUser?.uid ?: run {
            Log.e("Sync", "❌ Admin UID tidak ditemukan")
            return
        }

        Log.d("Sync", "🔄 Memulai sinkronisasi data offline untuk admin: $adminUid")

        // Ambil data yang belum sync
        val unsynced = daftarPelanggan.filter {
            (!it.isSynced || it.id.startsWith("local-")) && it.adminUid == adminUid
        }

        if (unsynced.isEmpty()) {
            Log.d("Sync", "✅ Tidak ada data yang perlu disinkronisasi")
            return
        }

        Log.d("Sync", "📤 Menemukan ${unsynced.size} data belum tersinkronisasi")

        val ref = database.child("pelanggan").child(adminUid)
        var successCount = 0
        var failedCount = 0

        // Process each unsynced item
        unsynced.forEach { pel ->
            try {
                // ✅ PERBAIKAN #1: Hanya cek duplikat untuk data BARU (local-),
                // JANGAN untuk data yang sudah punya ID Firebase (artinya cuma diedit offline)
                if (pel.id.startsWith("local-")) {
                    val isDuplicate = checkForDuplicate(pel, adminUid)
                    if (isDuplicate) {
                        Log.d("Sync", "⚠️ Data BARU duplikat ditemukan, skip: ${pel.namaPanggilan}")
                        daftarPelanggan.remove(pel)
                        successCount++
                        return@forEach
                    }
                }

                val finalId = if (pel.id.startsWith("local-")) {
                    ref.push().key ?: UUID.randomUUID().toString().replace("-", "").substring(0, 20)
                } else {
                    pel.id
                }

                // ✅ PERBAIKAN: Upload foto yang pending
                var fotoKtpUrl = pel.fotoKtpUrl
                var fotoKtpSuamiUrl = pel.fotoKtpSuamiUrl
                var fotoKtpIstriUrl = pel.fotoKtpIstriUrl

                // Upload pending foto jika ada
                if (pel.pendingFotoKtpUri.isNotBlank() && fotoKtpUrl.isBlank()) {
                    try {
                        Log.d("Sync", "📷 Uploading pending foto KTP...")
                        fotoKtpUrl = uploadFotoKtp(
                            Uri.parse(pel.pendingFotoKtpUri),
                            adminUid,
                            finalId,
                            "utama"
                        ) ?: ""
                        Log.d("Sync", "✅ Foto KTP uploaded: $fotoKtpUrl")
                    } catch (e: Exception) {
                        Log.e("Sync", "⚠️ Gagal upload foto KTP: ${e.message}")
                    }
                }

                if (pel.pendingFotoKtpSuamiUri.isNotBlank() && fotoKtpSuamiUrl.isBlank()) {
                    try {
                        Log.d("Sync", "📷 Uploading pending foto KTP Suami...")
                        fotoKtpSuamiUrl = uploadFotoKtp(
                            Uri.parse(pel.pendingFotoKtpSuamiUri),
                            adminUid,
                            finalId,
                            "suami"
                        ) ?: ""
                        Log.d("Sync", "✅ Foto KTP Suami uploaded: $fotoKtpSuamiUrl")
                    } catch (e: Exception) {
                        Log.e("Sync", "⚠️ Gagal upload foto KTP Suami: ${e.message}")
                    }
                }

                if (pel.pendingFotoKtpIstriUri.isNotBlank() && fotoKtpIstriUrl.isBlank()) {
                    try {
                        Log.d("Sync", "📷 Uploading pending foto KTP Istri...")
                        fotoKtpIstriUrl = uploadFotoKtp(
                            Uri.parse(pel.pendingFotoKtpIstriUri),
                            adminUid,
                            finalId,
                            "istri"
                        ) ?: ""
                        Log.d("Sync", "✅ Foto KTP Istri uploaded: $fotoKtpIstriUrl")
                    } catch (e: Exception) {
                        Log.e("Sync", "⚠️ Gagal upload foto KTP Istri: ${e.message}")
                    }
                }

                // ✅ BARU: Upload pending foto nasabah
                var fotoNasabahUrl = pel.fotoNasabahUrl
                if (pel.pendingFotoNasabahUri.isNotBlank() && fotoNasabahUrl.isBlank()) {
                    try {
                        Log.d("Sync", "📷 Uploading pending foto Nasabah...")
                        fotoNasabahUrl = uploadFotoKtp(
                            Uri.parse(pel.pendingFotoNasabahUri),
                            adminUid,
                            finalId,
                            "nasabah"
                        ) ?: ""
                        Log.d("Sync", "✅ Foto Nasabah uploaded: $fotoNasabahUrl")
                    } catch (e: Exception) {
                        Log.e("Sync", "⚠️ Gagal upload foto Nasabah: ${e.message}")
                    }
                }

                val pelToUpload = pel.copy(
                    id = finalId,
                    isSynced = true,
                    adminUid = adminUid,
                    fotoKtpUrl = fotoKtpUrl,
                    fotoKtpSuamiUrl = fotoKtpSuamiUrl,
                    fotoKtpIstriUrl = fotoKtpIstriUrl,
                    fotoNasabahUrl = fotoNasabahUrl,
                    // ✅ Clear pending URI setelah upload
                    pendingFotoKtpUri = "",
                    pendingFotoKtpSuamiUri = "",
                    pendingFotoKtpIstriUri = "",
                    pendingFotoNasabahUri = if (fotoNasabahUrl.isNotBlank()) "" else pel.pendingFotoNasabahUri,
                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                // Upload ke Firebase
                ref.child(finalId).setValue(pelToUpload).await()

                // Update local data
                val idx = daftarPelanggan.indexOfFirst { it.id == pel.id }
                if (idx != -1) {
                    daftarPelanggan[idx] = pelToUpload
                }

                // ✅ TAMBAHAN: Buat pengajuan approval jika status "Menunggu Approval"
                if (pelToUpload.status == "Menunggu Approval" && pelToUpload.cabangId.isNotBlank()) {
                    try {
                        simpanKePengajuanApproval(pelToUpload, pelToUpload.cabangId)
                        Log.d("Sync", "✅ Pengajuan approval dibuat untuk: ${pelToUpload.namaPanggilan}")
                    } catch (e: Exception) {
                        Log.e("Sync", "⚠️ Gagal buat pengajuan approval: ${e.message}")
                    }
                }

                successCount++
                Log.d("Sync", "✅ Berhasil sync: ${pel.namaPanggilan} -> $finalId")

            } catch (e: Exception) {
                failedCount++
                Log.e("Sync", "❌ Gagal sync ${pel.namaPanggilan}: ${e.message}")

                // Tandai untuk retry later
                if (pel.id.startsWith("local-")) {
                    val idx = daftarPelanggan.indexOfFirst { it.id == pel.id }
                    if (idx != -1) {
                        daftarPelanggan[idx] = pel.copy(isSynced = false)
                    }
                }
            }
        }

        // Simpan perubahan ke lokal
        simpanKeLokal()

        Log.d("Sync", "🎉 Sinkronisasi selesai: $successCount berhasil, $failedCount gagal")

        // Jika ada yang gagal, schedule retry
        if (failedCount > 0) {
            scheduleRetrySync()
        }
    }

    private suspend fun checkForDuplicate(pelanggan: Pelanggan, adminUid: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Cek berdasarkan NIK (untuk pinjaman di bawah 3jt)
                if (pelanggan.nik.isNotBlank()) {
                    val snapshot = database.child("pelanggan").child(adminUid)
                        .orderByChild("nik").equalTo(pelanggan.nik).get().await()
                    if (snapshot.exists()) {
                        return@withContext true
                    }
                }

                // Cek berdasarkan NIK Suami & Istri (untuk pinjaman di atas 3jt)
                if (pelanggan.nikSuami.isNotBlank() && pelanggan.nikIstri.isNotBlank()) {
                    val snapshotSuami = database.child("pelanggan").child(adminUid)
                        .orderByChild("nikSuami").equalTo(pelanggan.nikSuami).get().await()
                    val snapshotIstri = database.child("pelanggan").child(adminUid)
                        .orderByChild("nikIstri").equalTo(pelanggan.nikIstri).get().await()

                    if (snapshotSuami.exists() || snapshotIstri.exists()) {
                        return@withContext true
                    }
                }

                // Cek berdasarkan nomor anggota
                if (pelanggan.nomorAnggota.isNotBlank()) {
                    val snapshot = database.child("pelanggan").child(adminUid)
                        .orderByChild("nomorAnggota").equalTo(pelanggan.nomorAnggota).get().await()
                    if (snapshot.exists()) {
                        return@withContext true
                    }
                }

                return@withContext false
            } catch (e: Exception) {
                Log.e("DuplicateCheck", "Error checking duplicate: ${e.message}")
                return@withContext false
            }
        }
    }

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val syncMutex = Mutex()

    private var networkCallbackVM: ConnectivityManager.NetworkCallback? = null

    fun startNetworkMonitoring() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Set initial state
        _isOnline.value = isOnline()

        // Gunakan callback dari ConnectivityManager (BUKAN polling)
        if (networkCallbackVM != null) return // Sudah terdaftar

        networkCallbackVM = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val wasOffline = !_isOnline.value
                _isOnline.value = true
                Log.d("Network", "🌐 Koneksi internet tersedia")

                viewModelScope.launch {
                    delay(2000) // Tunggu koneksi stabil

                    try {
                        // ✅ PERBAIKAN #2C: Jalankan BERURUTAN, bukan paralel
                        // Step 1: Upload data lokal ke Firebase (SEKARANG SUSPEND, ditunggu)
                        Log.d("Network", "📤 Step 1: Syncing offline data...")
                        syncOfflineDataWithRetry()
                        Log.d("Network", "✅ Step 1 complete")

                        // Step 2: Upload foto pending
                        Log.d("Network", "📷 Step 2: Uploading pending photos...")
                        uploadPendingPhotos()
                        Log.d("Network", "✅ Step 2 complete")

                        // Step 3: BARU setelah sync selesai, refresh dari Firebase
                        if (wasOffline) {
                            Log.d("Network", "📥 Step 3: Refreshing data from Firebase...")
                            refreshDataFromFirebase()
                            Log.d("Network", "✅ Step 3 complete")
                        }

                        // Step 4: Sync deleted items TERAKHIR
                        // (setelah data lokal sudah ter-upload semua)
                        val uid = Firebase.auth.currentUser?.uid
                        if (uid != null) {
                            Log.d("Network", "🗑️ Step 4: Syncing deleted items...")
                            syncDeletedItemsSafe(uid)
                            Log.d("Network", "✅ Step 4 complete")
                        }

                    } catch (e: Exception) {
                        Log.e("Network", "Error during network sync: ${e.message}")
                    }
                }
            }

            override fun onLost(network: android.net.Network) {
                _isOnline.value = false
                Log.d("Network", "📵 Koneksi terputus")
            }
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallbackVM!!)
            }
        } catch (e: Exception) {
            Log.e("Network", "Error registering network callback: ${e.message}")
        }
    }

    /**
     * Refresh data dari Firebase setelah kembali online
     * Dipanggil otomatis saat koneksi internet tersedia kembali
     */
    fun refreshDataFromFirebase() {
        viewModelScope.launch {
            try {
                val currentUid = Firebase.auth.currentUser?.uid ?: return@launch
                val role = _currentUserRole.value

                Log.d("RefreshData", "🔄 Refreshing data for role: $role")

                when (role) {
                    UserRole.ADMIN_LAPANGAN -> {
                        // Reload data pelanggan dengan forceRefresh = true
                        val result = smartLoader.loadDataForAdmin(currentUid, forceRefresh = true)

                        when (result) {
                            is SmartFirebaseLoader.LoadResult.Success -> {
                                // ✅ PENTING: Clear dan reload untuk menghindari duplikasi
                                daftarPelanggan.clear()
                                daftarPelanggan.addAll(result.data)

                                Log.d("RefreshData", "✅ Refreshed ${result.data.size} pelanggan from Firebase")

                                // Update dashboard dengan data terbaru
                                loadDashboardData()
                                _adminSummary.value = calculateAdminSummary(daftarPelanggan)
                            }
                            is SmartFirebaseLoader.LoadResult.Error -> {
                                Log.e("RefreshData", "❌ Failed to refresh: ${result.message}")
                            }
                        }
                    }
                    UserRole.PIMPINAN -> {
                        val cabangId = _currentUserCabang.value
                        if (!cabangId.isNullOrBlank()) {
                            val result = smartLoader.loadDataForPimpinan(cabangId, forceRefresh = true)
                            if (result.success) {
                                _adminSummary.value = result.adminSummaries
                                loadDashboardData()
                                Log.d("RefreshData", "✅ Pimpinan data refreshed")
                            }
                        }
                    }
                    UserRole.PENGAWAS, UserRole.KOORDINATOR -> {
                        val result = smartLoader.loadDataForPengawas(forceRefresh = true)
                        if (result.success && result.globalSummary != null) {
                            _globalSummary.value = GlobalSummary(
                                totalNasabah = result.globalSummary.totalNasabah,
                                totalPinjamanAktif = result.globalSummary.totalPinjamanAktif,
                                totalTunggakan = result.globalSummary.totalTunggakan,
                                pembayaranHariIni = result.globalSummary.pembayaranHariIni,
                                lastUpdated = System.currentTimeMillis()
                            )
                            loadDashboardData()
                        }
                        Log.d("RefreshData", "✅ Pengawas data refreshed")
                    }
                    else -> {
                        Log.w("RefreshData", "Unknown role: $role")
                    }
                }
            } catch (e: Exception) {
                Log.e("RefreshData", "❌ Error refreshing data: ${e.message}")
            }
        }
    }

    /**
     * Upload foto KTP yang pending setelah data ter-sync
     * Dipanggil setelah SyncForegroundService selesai sync data
     */
    fun uploadPendingPhotos() {
        viewModelScope.launch {
            if (!isOnline()) {
                Log.d("PhotoUpload", "📵 Offline, skip upload foto")
                return@launch
            }

            val adminUid = Firebase.auth.currentUser?.uid ?: return@launch

            // Cari pelanggan yang punya pending foto
            val pelangganWithPendingPhotos = daftarPelanggan.filter { pel ->
                (pel.pendingFotoKtpUri.isNotBlank() && pel.fotoKtpUrl.isBlank()) ||
                        (pel.pendingFotoKtpSuamiUri.isNotBlank() && pel.fotoKtpSuamiUrl.isBlank()) ||
                        (pel.pendingFotoKtpIstriUri.isNotBlank() && pel.fotoKtpIstriUrl.isBlank()) ||
                        (pel.pendingFotoNasabahUri.isNotBlank() && pel.fotoNasabahUrl.isBlank())
            }

            if (pelangganWithPendingPhotos.isEmpty()) {
                Log.d("PhotoUpload", "📷 Tidak ada foto pending untuk diupload")
                return@launch
            }

            Log.d("PhotoUpload", "📷 Uploading ${pelangganWithPendingPhotos.size} pending photos...")

            pelangganWithPendingPhotos.forEach { pel ->
                try {
                    var fotoKtpUrl = pel.fotoKtpUrl
                    var fotoKtpSuamiUrl = pel.fotoKtpSuamiUrl
                    var fotoKtpIstriUrl = pel.fotoKtpIstriUrl
                    var hasChanges = false

                    // Upload foto KTP utama
                    if (pel.pendingFotoKtpUri.isNotBlank() && fotoKtpUrl.isBlank()) {
                        try {
                            Log.d("PhotoUpload", "📷 Uploading foto KTP: ${pel.namaPanggilan}")
                            fotoKtpUrl = uploadFotoKtp(
                                Uri.parse(pel.pendingFotoKtpUri),
                                adminUid,
                                pel.id,
                                "utama"
                            ) ?: ""
                            if (fotoKtpUrl.isNotBlank()) {
                                hasChanges = true
                                Log.d("PhotoUpload", "✅ Foto KTP uploaded: $fotoKtpUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("PhotoUpload", "⚠️ Gagal upload foto KTP: ${e.message}")
                        }
                    }

                    // Upload foto KTP Suami
                    if (pel.pendingFotoKtpSuamiUri.isNotBlank() && fotoKtpSuamiUrl.isBlank()) {
                        try {
                            Log.d("PhotoUpload", "📷 Uploading foto KTP Suami: ${pel.namaPanggilan}")
                            fotoKtpSuamiUrl = uploadFotoKtp(
                                Uri.parse(pel.pendingFotoKtpSuamiUri),
                                adminUid,
                                pel.id,
                                "suami"
                            ) ?: ""
                            if (fotoKtpSuamiUrl.isNotBlank()) {
                                hasChanges = true
                                Log.d("PhotoUpload", "✅ Foto KTP Suami uploaded: $fotoKtpSuamiUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("PhotoUpload", "⚠️ Gagal upload foto KTP Suami: ${e.message}")
                        }
                    }

                    // Upload foto KTP Istri
                    if (pel.pendingFotoKtpIstriUri.isNotBlank() && fotoKtpIstriUrl.isBlank()) {
                        try {
                            Log.d("PhotoUpload", "📷 Uploading foto KTP Istri: ${pel.namaPanggilan}")
                            fotoKtpIstriUrl = uploadFotoKtp(
                                Uri.parse(pel.pendingFotoKtpIstriUri),
                                adminUid,
                                pel.id,
                                "istri"
                            ) ?: ""
                            if (fotoKtpIstriUrl.isNotBlank()) {
                                hasChanges = true
                                Log.d("PhotoUpload", "✅ Foto KTP Istri uploaded: $fotoKtpIstriUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("PhotoUpload", "⚠️ Gagal upload foto KTP Istri: ${e.message}")
                        }
                    }

                    // ✅ BARU: Upload foto Nasabah
                    var fotoNasabahUrl = pel.fotoNasabahUrl
                    if (pel.pendingFotoNasabahUri.isNotBlank() && fotoNasabahUrl.isBlank()) {
                        try {
                            Log.d("PhotoUpload", "📷 Uploading foto Nasabah: ${pel.namaPanggilan}")
                            fotoNasabahUrl = uploadFotoKtp(
                                Uri.parse(pel.pendingFotoNasabahUri),
                                adminUid,
                                pel.id,
                                "nasabah"
                            ) ?: ""
                            if (fotoNasabahUrl.isNotBlank()) {
                                hasChanges = true
                                Log.d("PhotoUpload", "✅ Foto Nasabah uploaded: $fotoNasabahUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("PhotoUpload", "⚠️ Gagal upload foto Nasabah: ${e.message}")
                        }
                    }

                    // Update Firebase dan local jika ada perubahan
                    if (hasChanges) {
                        val updatedPel = pel.copy(
                            fotoKtpUrl = fotoKtpUrl,
                            fotoKtpSuamiUrl = fotoKtpSuamiUrl,
                            fotoKtpIstriUrl = fotoKtpIstriUrl,
                            fotoNasabahUrl = fotoNasabahUrl,
                            pendingFotoKtpUri = if (fotoKtpUrl.isNotBlank()) "" else pel.pendingFotoKtpUri,
                            pendingFotoKtpSuamiUri = if (fotoKtpSuamiUrl.isNotBlank()) "" else pel.pendingFotoKtpSuamiUri,
                            pendingFotoKtpIstriUri = if (fotoKtpIstriUrl.isNotBlank()) "" else pel.pendingFotoKtpIstriUri,
                            pendingFotoNasabahUri = if (fotoNasabahUrl.isNotBlank()) "" else pel.pendingFotoNasabahUri
                        )

                        // Update Firebase dengan URL foto
                        val targetAdminUid = if (pel.adminUid.isNotBlank()) pel.adminUid else adminUid
                        database.child("pelanggan").child(targetAdminUid).child(pel.id)
                            .updateChildren(mapOf(
                                "fotoKtpUrl" to fotoKtpUrl,
                                "fotoKtpSuamiUrl" to fotoKtpSuamiUrl,
                                "fotoKtpIstriUrl" to fotoKtpIstriUrl,
                                "fotoNasabahUrl" to fotoNasabahUrl,
                                "pendingFotoKtpUri" to updatedPel.pendingFotoKtpUri,
                                "pendingFotoKtpSuamiUri" to updatedPel.pendingFotoKtpSuamiUri,
                                "pendingFotoKtpIstriUri" to updatedPel.pendingFotoKtpIstriUri,
                                "pendingFotoNasabahUri" to updatedPel.pendingFotoNasabahUri
                            )).await()

                        // Update local list
                        val idx = daftarPelanggan.indexOfFirst { it.id == pel.id }
                        if (idx != -1) {
                            daftarPelanggan[idx] = updatedPel
                        }

                        Log.d("PhotoUpload", "✅ Foto updated untuk: ${pel.namaPanggilan}")
                    }

                } catch (e: Exception) {
                    Log.e("PhotoUpload", "❌ Error upload foto ${pel.namaPanggilan}: ${e.message}")
                }
            }

            // Simpan perubahan ke local storage
            simpanKeLokal()
            Log.d("PhotoUpload", "✅ Upload foto pending selesai")
        }
    }

    private suspend fun syncOfflineDataWithRetry(maxRetries: Int = 3) {
        if (!syncMutex.tryLock()) {
            Log.d("Sync", "⏳ Sinkronisasi sudah berjalan, skip...")
            return
        }

        try {
            _isSyncing.value = true
            var retryCount = 0

            while (retryCount < maxRetries) {
                try {
                    if (!isOnline()) {
                        Log.d("Sync", "📵 Tidak ada koneksi, cancel sync")
                        break
                    }

                    Log.d("Sync", "🔄 Memulai sinkronisasi (attempt ${retryCount + 1}/$maxRetries)")
                    syncOfflineDataInternal()
                    Log.d("Sync", "✅ Sinkronisasi berhasil")
                    break
                } catch (e: Exception) {
                    retryCount++
                    Log.e("Sync", "❌ Sinkronisasi gagal (attempt $retryCount): ${e.message}")

                    if (retryCount < maxRetries) {
                        delay(5000L * retryCount)
                    } else {
                        Log.e("Sync", "❌ Sinkronisasi gagal setelah $maxRetries attempts")
                        scheduleRetrySync()
                    }
                }
            }
        } finally {
            syncMutex.unlock()
            _isSyncing.value = false
        }
    }

    // Fungsi untuk retry sync
    private fun scheduleRetrySync() {
        viewModelScope.launch {
            delay(30000) // Retry setelah 30 detik
            if (isOnline()) {
                Log.d("Sync", "🔄 Retry sinkronisasi...")
                syncOfflineDataWithRetry()
            }
        }
    }

    fun getUnsyncedCount(): Int {
        val adminUid = Firebase.auth.currentUser?.uid ?: return 0
        return daftarPelanggan.count {
            (!it.isSynced || it.id.startsWith("local-")) && it.adminUid == adminUid
        }
    }

    fun hapusPembayaran(pelangganId: String, index: Int) {
        val pelangganIndex = daftarPelanggan.indexOfFirst { it.id == pelangganId }
        if (pelangganIndex != -1) {
            val pelanggan = daftarPelanggan[pelangganIndex]
            val updatedPembayaranList = pelanggan.pembayaranList.toMutableList().apply {
                removeAt(index)
            }

            // Hitung total yang sudah dibayar setelah penghapusan
            val totalDibayar = updatedPembayaranList.sumOf { it.jumlah }
            val status = if (totalDibayar >= pelanggan.totalPelunasan) "Lunas" else "Aktif"

            val updatedPelanggan = pelanggan.copy(
                pembayaranList = updatedPembayaranList,
                status = status
            )

            daftarPelanggan[pelangganIndex] = updatedPelanggan
            simpanPelangganKeFirebase(updatedPelanggan)
            simpanKeLokal()
        }
    }

    fun editPembayaran(pelangganId: String, index: Int, jumlah: Int, tanggal: String) {
        val pelangganIndex = daftarPelanggan.indexOfFirst { it.id == pelangganId }
        if (pelangganIndex != -1) {
            val pelanggan = daftarPelanggan[pelangganIndex]
            val updatedPembayaranList = pelanggan.pembayaranList.toMutableList().apply {
                this[index] = Pembayaran(jumlah, tanggal)
            }

            // Hitung total yang sudah dibayar setelah edit
            val totalDibayar = updatedPembayaranList.sumOf { it.jumlah }
            val status = if (totalDibayar >= pelanggan.totalPelunasan) "Lunas" else "Aktif"

            val updatedPelanggan = pelanggan.copy(
                pembayaranList = updatedPembayaranList,
                status = status
            )

            daftarPelanggan[pelangganIndex] = updatedPelanggan
            simpanPelangganKeFirebase(updatedPelanggan)
            simpanKeLokal()
        }
    }

    fun processTopUpLoanWithApprovalAndPhotos(
        pelangganId: String,
        pinjamanBaru: Int,
        tenorBaru: Int,
        // Data KTP utama
        namaKtp: String,
        nik: String,
        alamatKtp: String,
        namaPanggilan: String,
        // Data suami-istri (untuk pinjaman >= 3jt)
        namaKtpSuami: String = "",
        namaKtpIstri: String = "",
        nikSuami: String = "",
        nikIstri: String = "",
        namaPanggilanSuami: String = "",
        namaPanggilanIstri: String = "",
        // Foto URI
        fotoKtpUri: Uri? = null,
        fotoKtpSuamiUri: Uri? = null,
        fotoKtpIstriUri: Uri? = null,
        fotoNasabahUri: Uri? = null,
        // Tipe pinjaman baru
        tipePinjamanBaru: String = "dibawah_3jt",
        // Callbacks
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                // ========== VALIDASI ==========
                if (pelangganId.isBlank()) {
                    onFailure?.invoke(Exception("ID Nasabah tidak valid"))
                    return@launch
                }
                if (pinjamanBaru <= 0) {
                    onFailure?.invoke(Exception("Jumlah pinjaman harus lebih dari 0"))
                    return@launch
                }
                if (tenorBaru !in 24..60) {
                    onFailure?.invoke(Exception("Tenor harus antara 24-60 hari"))
                    return@launch
                }

                val existingPelanggan = getPelangganById(pelangganId)
                if (existingPelanggan == null) {
                    onFailure?.invoke(Exception("Nasabah tidak ditemukan"))
                    return@launch
                }

                val dataSebelumTopUp = existingPelanggan.copy()
                val tipePinjamanLama = existingPelanggan.tipePinjaman
                val isUpgrade = tipePinjamanLama == "dibawah_3jt" && tipePinjamanBaru == "diatas_3jt"
                val isDowngrade = tipePinjamanLama == "diatas_3jt" && tipePinjamanBaru == "dibawah_3jt"

                Log.d("KelolaKredit", "📊 TOP-UP DENGAN FOTO:")
                Log.d("KelolaKredit", "   TipePinjamanLama: $tipePinjamanLama")
                Log.d("KelolaKredit", "   TipePinjamanBaru: $tipePinjamanBaru")
                Log.d("KelolaKredit", "   IsUpgrade: $isUpgrade, IsDowngrade: $isDowngrade")

                // ========== HITUNG SISA UTANG ==========
                // ✅ PERBAIKAN: Cek apakah nasabah dari "Menunggu Pencairan" (sudah lunas cicilan)
                val isFromMenungguPencairan = existingPelanggan.statusKhusus == "MENUNGGU_PENCAIRAN" ||
                        existingPelanggan.statusPencairanSimpanan == "Menunggu Pencairan"

                val totalBayarSebelumnya = existingPelanggan.pembayaranList.sumOf { pembayaran ->
                    pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
                }
                val totalPelunasanLama = existingPelanggan.totalPelunasan

                // ✅ PERBAIKAN: Jika dari "Menunggu Pencairan", sisaUtangLama = 0 (sudah lunas)
                val sisaUtangLama = if (isFromMenungguPencairan) {
                    0
                } else {
                    max(totalPelunasanLama - totalBayarSebelumnya, 0)
                }
                val sudahLunas = sisaUtangLama <= 0 || isFromMenungguPencairan
                val pinjamanKeBaru = existingPelanggan.pinjamanKe + 1

                // ========== UPLOAD FOTO ==========
                val currentUid = existingPelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid ?: "" }
                var newFotoKtpUrl = ""
                var newFotoKtpSuamiUrl = ""
                var newFotoKtpIstriUrl = ""
                var newFotoNasabahUrl = ""

                // Pending URI untuk offline mode
                var pendingFotoKtpUri = ""
                var pendingFotoKtpSuamiUri = ""
                var pendingFotoKtpIstriUri = ""
                var pendingFotoNasabahUri = ""

                if (isOnline()) {
                    Log.d("KelolaKredit", "📤 ONLINE - Uploading foto...")

                    try {
                        when (tipePinjamanBaru) {
                            "dibawah_3jt" -> {
                                // Upload foto KTP single
                                fotoKtpUri?.let {
                                    newFotoKtpUrl = uploadFotoKtp(it, currentUid, pelangganId, "ktp") ?: ""
                                    Log.d("KelolaKredit", "✅ Foto KTP uploaded: $newFotoKtpUrl")
                                }
                            }
                            "diatas_3jt" -> {
                                // Upload foto KTP suami
                                fotoKtpSuamiUri?.let {
                                    newFotoKtpSuamiUrl = uploadFotoKtp(it, currentUid, pelangganId, "ktp_suami") ?: ""
                                    Log.d("KelolaKredit", "✅ Foto KTP Suami uploaded: $newFotoKtpSuamiUrl")
                                }
                                // Upload foto KTP istri
                                fotoKtpIstriUri?.let {
                                    newFotoKtpIstriUrl = uploadFotoKtp(it, currentUid, pelangganId, "ktp_istri") ?: ""
                                    Log.d("KelolaKredit", "✅ Foto KTP Istri uploaded: $newFotoKtpIstriUrl")
                                }
                            }
                        }

                        // Upload foto nasabah (untuk semua tipe)
                        fotoNasabahUri?.let {
                            newFotoNasabahUrl = uploadFotoKtp(it, currentUid, pelangganId, "nasabah") ?: ""
                            Log.d("KelolaKredit", "✅ Foto Nasabah uploaded: $newFotoNasabahUrl")
                        }
                    } catch (e: Exception) {
                        Log.w("KelolaKredit", "⚠️ Gagal upload foto, simpan untuk nanti: ${e.message}")
                        // Simpan URI untuk upload nanti
                        pendingFotoKtpUri = fotoKtpUri?.toString() ?: ""
                        pendingFotoKtpSuamiUri = fotoKtpSuamiUri?.toString() ?: ""
                        pendingFotoKtpIstriUri = fotoKtpIstriUri?.toString() ?: ""
                        pendingFotoNasabahUri = fotoNasabahUri?.toString() ?: ""
                    }
                } else {
                    Log.d("KelolaKredit", "📱 OFFLINE - Simpan foto URI untuk nanti")
                    pendingFotoKtpUri = fotoKtpUri?.toString() ?: ""
                    pendingFotoKtpSuamiUri = fotoKtpSuamiUri?.toString() ?: ""
                    pendingFotoKtpIstriUri = fotoKtpIstriUri?.toString() ?: ""
                    pendingFotoNasabahUri = fotoNasabahUri?.toString() ?: ""
                }

                // ========== HITUNG NILAI PINJAMAN BARU ==========
                val calculation = calculatePinjamanValues(pinjamanBaru)
                val totalPelunasanBaru = calculation.totalPelunasan
                val totalSimpananLama = getTotalSimpananByNama(existingPelanggan.namaKtp)
                val totalSimpananBaru = if (existingPelanggan.statusPencairanSimpanan == "Dicairkan") {
                    calculation.simpanan
                } else {
                    totalSimpananLama + calculation.simpanan
                }
                val tanggalPengajuanBaru = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).format(Date())
                val cicilanBaru = generateCicilanKonsisten(tanggalPengajuanBaru, tenorBaru, totalPelunasanBaru)
                val totalDiterimaBaru = calculation.totalDiterima

                // ========== BUILD UPDATED PELANGGAN ==========
                val updatedPelanggan = existingPelanggan.copy(
                    // === Data KTP ===
                    namaKtp = namaKtp,
                    nik = nik,
                    alamatKtp = alamatKtp,
                    namaPanggilan = namaPanggilan,

                    // === Data Suami-Istri (untuk >= 3jt) ===
                    namaKtpSuami = if (tipePinjamanBaru == "diatas_3jt") namaKtpSuami else "",
                    namaKtpIstri = if (tipePinjamanBaru == "diatas_3jt") namaKtpIstri else "",
                    nikSuami = if (tipePinjamanBaru == "diatas_3jt") nikSuami else "",
                    nikIstri = if (tipePinjamanBaru == "diatas_3jt") nikIstri else "",
                    namaPanggilanSuami = if (tipePinjamanBaru == "diatas_3jt") namaPanggilanSuami else "",
                    namaPanggilanIstri = if (tipePinjamanBaru == "diatas_3jt") namaPanggilanIstri else "",

                    // === Tipe Pinjaman ===
                    tipePinjaman = tipePinjamanBaru,

                    // === Foto URL (yang berhasil diupload) ===
                    fotoKtpUrl = if (newFotoKtpUrl.isNotBlank()) newFotoKtpUrl else existingPelanggan.fotoKtpUrl,
                    fotoKtpSuamiUrl = if (newFotoKtpSuamiUrl.isNotBlank()) newFotoKtpSuamiUrl else existingPelanggan.fotoKtpSuamiUrl,
                    fotoKtpIstriUrl = if (newFotoKtpIstriUrl.isNotBlank()) newFotoKtpIstriUrl else existingPelanggan.fotoKtpIstriUrl,
                    fotoNasabahUrl = if (newFotoNasabahUrl.isNotBlank()) newFotoNasabahUrl else existingPelanggan.fotoNasabahUrl,

                    // === Pending Foto URI (untuk offline sync) ===
                    pendingFotoKtpUri = pendingFotoKtpUri,
                    pendingFotoKtpSuamiUri = pendingFotoKtpSuamiUri,
                    pendingFotoKtpIstriUri = pendingFotoKtpIstriUri,
                    pendingFotoNasabahUri = pendingFotoNasabahUri,

                    // === Reset foto serah terima (akan diisi setelah approval) ===
                    fotoSerahTerimaUrl = "",
                    pendingFotoSerahTerimaUri = "",
                    statusSerahTerima = if (tipePinjamanBaru == "diatas_3jt") "Pending" else "",
                    tanggalSerahTerima = "",

                    // === Data Pinjaman ===
                    pinjamanKe = pinjamanKeBaru,
                    besarPinjaman = pinjamanBaru,
                    besarPinjamanDiajukan = pinjamanBaru,
                    admin = calculation.admin,
                    simpanan = totalSimpananBaru,
                    jasaPinjaman = calculation.jasaPinjaman,
                    totalDiterima = totalDiterimaBaru,
                    totalPelunasan = totalPelunasanBaru,
                    tenor = tenorBaru,
                    tanggalPengajuan = tanggalPengajuanBaru,
                    status = "Menunggu Approval",
                    // === Reset Status Pencairan (pindah dari Menunggu Pencairan) ===
                    statusPencairanSimpanan = "",
                    tanggalLunasCicilan = "",
                    statusKhusus = "",
                    catatanStatusKhusus = "",
                    tanggalStatusKhusus = "",
                    diberiTandaOleh = "",
                    pembayaranList = emptyList(), // Selalu mulai fresh — riwayat lama disimpan di riwayatPembayaran/
                    hasilSimulasiCicilan = cicilanBaru,

                    // === Data Referensi ===
                    sisaUtangLamaSebelumTopUp = sisaUtangLama,
                    totalPelunasanLamaSebelumTopUp = totalPelunasanLama,

                    // === Catatan Perubahan ===
                    catatanPerubahanPinjaman = "DATA_SEBELUM_TOPUP:" +
                            "pinjamanKeLama=${dataSebelumTopUp.pinjamanKe}," +
                            "besarPinjamanLama=${dataSebelumTopUp.besarPinjaman}," +
                            "tipePinjamanLama=$tipePinjamanLama," +
                            "tipePinjamanBaru=$tipePinjamanBaru," +
                            "isUpgrade=$isUpgrade," +
                            "isDowngrade=$isDowngrade," +
                            "adminLama=${dataSebelumTopUp.admin}," +
                            "simpananLama=${dataSebelumTopUp.simpanan}," +
                            "totalDiterimaLama=${dataSebelumTopUp.totalDiterima}," +
                            "totalPelunasanLama=${dataSebelumTopUp.totalPelunasan}," +
                            "tenorLama=${dataSebelumTopUp.tenor}," +
                            "tanggalPengajuanLama=${dataSebelumTopUp.tanggalPengajuan}," +
                            "totalPembayaran=${totalBayarSebelumnya}," +
                            "sisaUtangLama=$sisaUtangLama," +
                            "isTopUpFromLunas=$sudahLunas",

                    // === Timestamp ===
                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                Log.d("KelolaKredit", "📝 DATA YANG AKAN DISIMPAN:")
                Log.d("KelolaKredit", "   PinjamanKe: ${updatedPelanggan.pinjamanKe}")
                Log.d("KelolaKredit", "   BesarPinjaman: ${updatedPelanggan.besarPinjaman}")
                Log.d("KelolaKredit", "   TipePinjaman: ${updatedPelanggan.tipePinjaman}")
                Log.d("KelolaKredit", "   FotoKtpUrl: ${updatedPelanggan.fotoKtpUrl}")
                Log.d("KelolaKredit", "   FotoKtpSuamiUrl: ${updatedPelanggan.fotoKtpSuamiUrl}")
                Log.d("KelolaKredit", "   FotoKtpIstriUrl: ${updatedPelanggan.fotoKtpIstriUrl}")
                Log.d("KelolaKredit", "   FotoNasabahUrl: ${updatedPelanggan.fotoNasabahUrl}")

                // ========== UPDATE LOCAL LIST ==========
                val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                if (index != -1) {
                    daftarPelanggan[index] = updatedPelanggan
                }

                // ========== SIMPAN RIWAYAT PEMBAYARAN LAMA (SEBELUM TOPUP) ==========
                // Tulis ke path terpisah agar tidak terbawa ke pinjaman baru,
                // tapi tetap tersimpan permanen untuk kebutuhan pembukuan.
                if (existingPelanggan.pembayaranList.isNotEmpty()) {
                    val pinjamanKeLama = existingPelanggan.pinjamanKe
                    val riwayatData = mapOf(
                        "pinjamanKe" to pinjamanKeLama,
                        "besarPinjaman" to existingPelanggan.besarPinjaman,
                        "totalPelunasan" to totalPelunasanLama,
                        "totalBayar" to totalBayarSebelumnya,
                        "sisaUtang" to sisaUtangLama,
                        "tanggalTopUp" to tanggalPengajuanBaru,
                        "pembayaranList" to existingPelanggan.pembayaranList.map { p ->
                            mapOf(
                                "jumlah" to p.jumlah,
                                "tanggal" to p.tanggal,
                                "subPembayaran" to p.subPembayaran.map { s ->
                                    mapOf("jumlah" to s.jumlah, "tanggal" to s.tanggal, "keterangan" to s.keterangan)
                                }
                            )
                        }
                    )
                    database.child("riwayatPembayaran")
                        .child(existingPelanggan.adminUid)
                        .child(pelangganId)
                        .child("pinjaman$pinjamanKeLama")
                        .setValue(riwayatData)
                    Log.d("KelolaKredit", "📚 Riwayat pembayaran pinjaman ke-$pinjamanKeLama disimpan (${existingPelanggan.pembayaranList.size} entri)")
                }

                // ========== SIMPAN KE FIREBASE ==========
                simpanPelangganKeFirebase(updatedPelanggan,
                    onSuccess = {
                        // Update simpanan di path terpisah juga
                        database.child("pelanggan")
                            .child(existingPelanggan.adminUid)
                            .child(pelangganId)
                            .child("simpanan")
                            .setValue(totalSimpananBaru)

                        // ✅ Hapus dari pelanggan_status_khusus jika ada
                        val cabangId = _currentUserCabang.value ?: ""
                        if (cabangId.isNotBlank()) {
                            database.child("pelanggan_status_khusus")
                                .child(cabangId)
                                .child(pelangganId)
                                .removeValue()
                        }

                        Log.d("KelolaKredit", "✅ Data berhasil disimpan ke Firebase")
                        onSuccess?.invoke()
                    },
                    onFailure = { exception ->
                        Log.e("KelolaKredit", "❌ Gagal simpan ke Firebase: ${exception.message}")
                        onFailure?.invoke(exception)
                    }
                )

            } catch (e: Exception) {
                Log.e("KelolaKredit", "❌ Error dalam processTopUpLoanWithApprovalAndPhotos: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    fun tambahSubPembayaran(
        pelangganId: String,
        pembayaranIndex: Int,
        jumlah: Int,
        tanggal: String,
        keterangan: String = "Tambah Bayar"
    ) {
        val pelangganIdx = daftarPelanggan.indexOfFirst { it.id == pelangganId }
        if (pelangganIdx == -1) return

        val pelanggan = daftarPelanggan[pelangganIdx]
        if (pembayaranIndex >= pelanggan.pembayaranList.size) return

        val subPembayaran = SubPembayaran(jumlah, tanggal, keterangan)

        // Update local list
        val updatedPembayaranList = pelanggan.pembayaranList.toMutableList()
        val pembayaran = updatedPembayaranList[pembayaranIndex]
        val updatedSubList = pembayaran.subPembayaran.toMutableList().apply {
            add(subPembayaran)
        }
        updatedPembayaranList[pembayaranIndex] = pembayaran.copy(subPembayaran = updatedSubList)

        // Hitung status baru
        val totalDibayar = updatedPembayaranList.sumOf { p ->
            p.jumlah + p.subPembayaran.sumOf { it.jumlah }
        }
        val status = if (totalDibayar >= pelanggan.totalPelunasan) "Lunas" else pelanggan.status

        val updatedPelanggan = pelanggan.copy(
            pembayaranList = updatedPembayaranList,
            status = status
        )

        daftarPelanggan[pelangganIdx] = updatedPelanggan

        // ✅ LANGSUNG tulis ke Firebase - Firebase Persistence akan handle offline!
        simpanPelangganKeFirebase(updatedPelanggan)

        // Simpan ke local storage sebagai backup
        simpanKeLokal()

        Log.d("SubPembayaran", "✅ Sub-pembayaran disimpan: Rp $jumlah")
        Log.d("SubPembayaran", "   Mode: ${if (isOnline()) "ONLINE" else "OFFLINE (queued by Firebase)"}")

        if (status == "Lunas") {
            updateStatusLunasCicilan(pelangganId)
        }
    }

    fun forceSyncNow() {
        viewModelScope.launch {
            try {
                _offlineSyncStatus.value = SyncStatus.SYNCING
                Log.d("OfflineSync", "🔄 Force syncing...")

                val result = offlineRepo.syncNow()

                if (result.allSuccess) {
                    _offlineSyncStatus.value = SyncStatus.SUCCESS
                    Log.d("OfflineSync", "✅ Sync complete: ${result.success}/${result.total}")
                } else {
                    _offlineSyncStatus.value = SyncStatus.PARTIAL
                    Log.w("OfflineSync", "⚠️ Partial sync: ${result.success}/${result.total}")
                }

            } catch (e: Exception) {
                _offlineSyncStatus.value = SyncStatus.ERROR
                Log.e("OfflineSync", "❌ Sync error: ${e.message}")
            }
        }
    }

    fun retryFailedSync() {
        viewModelScope.launch {
            offlineRepo.retryFailed()
            Log.d("OfflineSync", "🔄 Retrying failed operations...")
        }
    }

    // ✅ TAMBAHAN: Trigger background sync
    fun triggerBackgroundSync() {
        offlineRepo.triggerSync()
        Log.d("OfflineSync", "📤 Background sync triggered")
    }

    fun getRiwayatPembayaran(pelangganId: String): List<Pembayaran> {
        return daftarPelanggan.find { it.id == pelangganId }?.pembayaranList ?: emptyList()
    }

    fun approvePengajuan(
        pelangganId: String,
        catatan: String = "",
        besarPinjamanDisetujui: Int? = null,
        tenorDisetujui: Int? = null,
        tarikTabungan: Int = 0,
        catatanPerubahanPinjaman: String = "",
        cabangId: String? = null,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                // ✅ PERBAIKAN: Selalu baca FRESH dari Firebase, bukan dari cache lokal
                Log.d("Approval", "🔍 Membaca data fresh dari Firebase untuk ID: $pelangganId")

                val existing = findPelangganFromFirebaseFresh(pelangganId, cabangId)

                if (existing == null) {
                    val error = Exception("Pelanggan tidak ditemukan dengan ID: $pelangganId")
                    Log.e("Approval", "❌ ${error.message}")
                    onFailure?.invoke(error)
                    return@launch
                }

                Log.d("Approval", "📊 DATA DARI FIREBASE:")
                Log.d("Approval", "   PinjamanKe: ${existing.pinjamanKe}")
                Log.d("Approval", "   BesarPinjaman: ${existing.besarPinjaman}")
                Log.d("Approval", "   TotalPelunasan: ${existing.totalPelunasan}")
                Log.d("Approval", "   TotalDiterima: ${existing.totalDiterima}")
                Log.d("Approval", "   Status: ${existing.status}")

                val adminUid = if (existing.adminUid.isNotBlank()) existing.adminUid else Firebase.auth.currentUser?.uid
                if (adminUid.isNullOrBlank()) {
                    val error = Exception("Admin UID tidak valid untuk pelanggan: ${existing.namaPanggilan}")
                    Log.e("Approval", "❌ ${error.message}")
                    onFailure?.invoke(error)
                    return@launch
                }

                // ✅ PERBAIKAN: Cek apakah ini top-up (pinjamanKe > 1)
                val isTopUpApproval = existing.status == "Menunggu Approval" && existing.pinjamanKe > 1

                Log.d("Approval", "🔄 isTopUpApproval: $isTopUpApproval (pinjamanKe=${existing.pinjamanKe})")

                val finalTenor = tenorDisetujui ?: existing.tenor

                // ✅ PERBAIKAN: Reset pembayaranList untuk top-up
                val pembayaranListBaru = if (isTopUpApproval) {
                    Log.d("Approval", "🔄 TOP-UP: Reset riwayat pembayaran")
                    emptyList<Pembayaran>()
                } else {
                    existing.pembayaranList
                }

                // ✅ PERBAIKAN KUNCI: Cek apakah ada penyesuaian pinjaman
                val adaPenyesuaian = besarPinjamanDisetujui != null && besarPinjamanDisetujui != existing.besarPinjaman

                // Nilai final - gunakan data existing jika tidak ada penyesuaian
                val finalBesarPinjaman: Int
                val finalAdmin: Int
                val finalSimpanan: Int
                val finalJasaPinjaman: Int
                val finalTotalDiterima: Int
                val finalTotalPelunasan: Int
                val finalSimulasiCicilan: List<SimulasiCicilan>

                if (adaPenyesuaian) {
                    // Ada penyesuaian dari pimpinan - hitung ulang
                    Log.d("Approval", "📝 Ada penyesuaian pinjaman: ${existing.besarPinjaman} → $besarPinjamanDisetujui")

                    finalBesarPinjaman = besarPinjamanDisetujui!!
                    val calculation = calculatePinjamanValues(finalBesarPinjaman)

                    finalAdmin = calculation.admin
                    finalJasaPinjaman = calculation.jasaPinjaman
                    finalTotalPelunasan = calculation.totalPelunasan

                    val simpananLimaPersen = calculation.simpanan
                    val simpananDariPengajuan = existing.besarPinjaman * 5 / 100
                    val simpananLamaDariPinjamanSebelumnya = (existing.simpanan - simpananDariPengajuan).coerceAtLeast(0)
                    finalSimpanan = simpananLimaPersen + simpananLamaDariPinjamanSebelumnya + tarikTabungan

                    val sisaUtangLama = existing.sisaUtangLamaSebelumTopUp
                    // ✅ Kurangi tarikTabungan dari totalDiterima
                    finalTotalDiterima = finalBesarPinjaman - finalAdmin - simpananLimaPersen - tarikTabungan

                    Log.d("Approval", "📊 Perhitungan penyesuaian:")
                    Log.d("Approval", "   Pinjaman disetujui: $finalBesarPinjaman")
                    Log.d("Approval", "   Admin (5%): $finalAdmin")
                    Log.d("Approval", "   Simpanan 5% baru: $simpananLimaPersen")
                    Log.d("Approval", "   Simpanan lama: $simpananLamaDariPinjamanSebelumnya")
                    Log.d("Approval", "   Total Simpanan: $finalSimpanan")
                    Log.d("Approval", "   Sisa utang lama: $sisaUtangLama")
                    Log.d("Approval", "   Tarik Tabungan: $tarikTabungan")
                    Log.d("Approval", "   Total Diterima: $finalTotalDiterima")

                    finalSimulasiCicilan = generateCicilanKonsisten(
                        existing.tanggalPengajuan,
                        finalTenor,
                        finalTotalPelunasan
                    )
                } else {
                    // ✅ TIDAK ADA PENYESUAIAN - GUNAKAN DATA YANG SUDAH ADA!
                    Log.d("Approval", "✅ Tidak ada penyesuaian - gunakan data existing")

                    finalBesarPinjaman = existing.besarPinjaman
                    finalAdmin = existing.admin
                    finalSimpanan = existing.simpanan + tarikTabungan
                    finalJasaPinjaman = existing.jasaPinjaman
                    // ✅ Kurangi tarikTabungan dari totalDiterima jika ada
                    finalTotalDiterima = if (tarikTabungan > 0) {
                        existing.totalDiterima - tarikTabungan
                    } else {
                        existing.totalDiterima
                    }
                    finalTotalPelunasan = existing.totalPelunasan

                    if (tarikTabungan > 0) {
                        Log.d("Approval", "   Tarik Tabungan: $tarikTabungan")
                        Log.d("Approval", "   Total Diterima (setelah tarik): $finalTotalDiterima")
                    }

                    if (tenorDisetujui != null && tenorDisetujui != existing.tenor) {
                        finalSimulasiCicilan = generateCicilanKonsisten(
                            existing.tanggalPengajuan,
                            finalTenor,
                            finalTotalPelunasan
                        )
                    } else {
                        finalSimulasiCicilan = existing.hasilSimulasiCicilan
                    }
                }

                Log.d("Approval", "📋 DATA FINAL UNTUK APPROVAL:")
                Log.d("Approval", "   BesarPinjaman: $finalBesarPinjaman")
                Log.d("Approval", "   Admin: $finalAdmin")
                Log.d("Approval", "   Simpanan: $finalSimpanan")
                Log.d("Approval", "   TotalPelunasan: $finalTotalPelunasan")
                Log.d("Approval", "   TotalDiterima: $finalTotalDiterima")

                // Siapkan data approval
                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                val tanggalSekarang = dateFormat.format(Date())
                val pimpinanUid = Firebase.auth.currentUser?.uid ?: ""
                val pimpinanSnap = database.child("metadata/admins/$pimpinanUid/name").get().await()
                val pimpinanName = pimpinanSnap.getValue(String::class.java)
                    ?: Firebase.auth.currentUser?.displayName
                    ?: Firebase.auth.currentUser?.email
                    ?: "Pimpinan"
                val timestamp = System.currentTimeMillis()

                // ✅ CEK APAKAH INI DUAL APPROVAL
                val requiresDualApproval = DualApprovalThreshold.requiresDualApproval(existing.besarPinjaman)

                Log.d("Approval", "🔍 requiresDualApproval: $requiresDualApproval (besarPinjaman=${existing.besarPinjaman})")

                // Initialize atau update dual approval info
                val currentDualInfo = existing.dualApprovalInfo ?: DualApprovalInfo(
                    requiresDualApproval = requiresDualApproval
                )

                val updatedPimpinanApproval = IndividualApproval(
                    status = ApprovalStatus.APPROVED,
                    by = pimpinanName,
                    uid = pimpinanUid,
                    timestamp = timestamp,
                    note = catatan
                )

                // =========================================================================
                // ✅ PERBAIKAN UTAMA: LOGIKA DUAL APPROVAL
                // =========================================================================
                if (requiresDualApproval) {
                    // =========================================================================
                    // KASUS DUAL APPROVAL (>= 3jt) - SEQUENTIAL THREE-PHASE
                    // =========================================================================
                    Log.d("Approval", "🔄 SEQUENTIAL DUAL APPROVAL: Processing Pimpinan action...")

                    // ✅ BARU: Cek phase saat ini
                    val currentPhase = currentDualInfo.approvalPhase

                    Log.d("Approval", "📍 Current Phase: $currentPhase")

                    // =========================================================================
                    // PHASE 1: PIMPINAN REVIEW AWAL (AWAITING_PIMPINAN)
                    // =========================================================================
                    if (currentPhase == ApprovalPhase.AWAITING_PIMPINAN || currentPhase.isEmpty()) {
                        Log.d("Approval", "📍 Phase 1: Pimpinan initial review - APPROVE")

                        // Update dualApprovalInfo dan PINDAH KE PHASE 2 (KOORDINATOR)
                        val updatedDualInfo = currentDualInfo.copy(
                            requiresDualApproval = true,
                            approvalPhase = ApprovalPhase.AWAITING_KOORDINATOR, // ✅ PINDAH KE PHASE 2 (KOORDINATOR)
                            pimpinanApproval = updatedPimpinanApproval.copy(
                                adjustedAmount = finalBesarPinjaman,
                                adjustedTenor = finalTenor
                            )
                        )

                        val updatedPelanggan = existing.copy(
                            status = "Menunggu Approval", // Status TETAP menunggu (belum final)
                            dualApprovalInfo = updatedDualInfo,
                            catatanApproval = catatan,
                            tanggalApproval = tanggalSekarang,
                            disetujuiOleh = pimpinanName,
                            besarPinjaman = finalBesarPinjaman,
                            besarPinjamanDisetujui = finalBesarPinjaman,
                            besarPinjamanDiajukan = existing.besarPinjamanDiajukan.takeIf { it > 0 } ?: existing.besarPinjaman,
                            admin = finalAdmin,
                            simpanan = finalSimpanan,
                            jasaPinjaman = finalJasaPinjaman,
                            totalDiterima = finalTotalDiterima,
                            totalPelunasan = finalTotalPelunasan,
                            tarikTabungan = tarikTabungan,
                            catatanPerubahanPinjaman = if (adaPenyesuaian || tarikTabungan > 0) catatanPerubahanPinjaman else existing.catatanPerubahanPinjaman,
                            isPinjamanDiubah = adaPenyesuaian || tarikTabungan > 0,
                            pembayaranList = pembayaranListBaru,
                            tenor = finalTenor,
                            hasilSimulasiCicilan = finalSimulasiCicilan,
                            lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        )

                        val ref = database.child("pelanggan").child(adminUid).child(pelangganId)
                        ref.setValue(updatedPelanggan)
                            .addOnSuccessListener {
                                // ✅ Update pengajuan_approval - TRIGGER CLOUD FUNCTION
                                cabangId?.let { branchId ->
                                    database.child("pengajuan_approval").child(branchId)
                                        .orderByChild("pelangganId")
                                        .equalTo(pelangganId)
                                        .addListenerForSingleValueEvent(object : ValueEventListener {
                                            override fun onDataChange(snapshot: DataSnapshot) {
                                                snapshot.children.forEach { child ->
                                                    // ✅ Update dualApprovalInfo dengan phase baru
                                                    // Cloud function akan mendeteksi perubahan phase
                                                    // dan mengirim notifikasi ke Pengawas
                                                    child.ref.child("dualApprovalInfo").setValue(buildDualApprovalInfoMap(updatedDualInfo))
                                                        .addOnSuccessListener {
                                                            Log.d("Approval", "✅ Phase 1 complete - moved to Phase 2 (AWAITING_KOORDINATOR)")
                                                            Log.d("Approval", "✅ Cloud function akan kirim notifikasi ke Koordinator")
                                                            updateLocalAndRefresh(pelangganId, updatedPelanggan, cabangId)
                                                            onSuccess?.invoke()
                                                        }
                                                }
                                                if (!snapshot.hasChildren()) {
                                                    // ✅ PERBAIKAN: Jika pengajuan tidak ditemukan, buat baru lalu update
                                                    Log.w("Approval", "⚠️ Pengajuan tidak ditemukan di pengajuan_approval, membuat entry baru...")

                                                    // Buat entry baru di pengajuan_approval dengan dualApprovalInfo
                                                    val newPengajuanRef = database.child("pengajuan_approval").child(branchId).push()
                                                    val pengajuanData = mapOf(
                                                        "adminUid" to updatedPelanggan.adminUid,
                                                        "nama" to updatedPelanggan.namaKtp,
                                                        "namaPanggilan" to updatedPelanggan.namaPanggilan,
                                                        "tanggalPengajuan" to updatedPelanggan.tanggalPengajuan,
                                                        "status" to "Menunggu Approval",
                                                        "jenisPinjaman" to if (updatedPelanggan.besarPinjaman >= 3000000) "diatas_3jt" else "dibawah_3jt",
                                                        "besarPinjaman" to updatedPelanggan.besarPinjaman,
                                                        "tenor" to updatedPelanggan.tenor,
                                                        "pinjamanKe" to updatedPelanggan.pinjamanKe,
                                                        "pelangganId" to pelangganId,
                                                        "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP,
                                                        "dualApprovalInfo" to mapOf(
                                                            "requiresDualApproval" to true,
                                                            "approvalPhase" to ApprovalPhase.AWAITING_KOORDINATOR,
                                                            "pimpinanApproval" to mapOf(
                                                                "status" to updatedDualInfo.pimpinanApproval.status,
                                                                "by" to updatedDualInfo.pimpinanApproval.by,
                                                                "uid" to updatedDualInfo.pimpinanApproval.uid,
                                                                "timestamp" to updatedDualInfo.pimpinanApproval.timestamp,
                                                                "note" to updatedDualInfo.pimpinanApproval.note,
                                                                "adjustedAmount" to updatedDualInfo.pimpinanApproval.adjustedAmount,
                                                                "adjustedTenor" to updatedDualInfo.pimpinanApproval.adjustedTenor
                                                            ),
                                                            "koordinatorApproval" to mapOf(
                                                                "status" to "pending",
                                                                "by" to "",
                                                                "uid" to "",
                                                                "timestamp" to 0,
                                                                "note" to ""
                                                            ),
                                                            "pengawasApproval" to mapOf(
                                                                "status" to "pending",
                                                                "by" to "",
                                                                "uid" to "",
                                                                "timestamp" to 0,
                                                                "note" to ""
                                                            )
                                                        )
                                                    )

                                                    newPengajuanRef.setValue(pengajuanData)
                                                        .addOnSuccessListener {
                                                            Log.d("Approval", "✅ Entry pengajuan baru dibuat, cloud function akan trigger notifikasi ke Koordinator")
                                                            updateLocalAndRefresh(pelangganId, updatedPelanggan, cabangId)
                                                            onSuccess?.invoke()
                                                        }
                                                        .addOnFailureListener { e ->
                                                            Log.e("Approval", "❌ Gagal membuat entry pengajuan: ${e.message}")
                                                            updateLocalAndRefresh(pelangganId, updatedPelanggan, cabangId)
                                                            onSuccess?.invoke()
                                                        }
                                                }
                                            }
                                            override fun onCancelled(error: DatabaseError) {
                                                Log.e("Approval", "❌ Error: ${error.message}")
                                                onSuccess?.invoke()
                                            }
                                        })
                                } ?: onSuccess?.invoke()
                            }
                            .addOnFailureListener { e ->
                                Log.e("Approval", "❌ Gagal: ${e.message}")
                                onFailure?.invoke(e)
                            }

                        return@launch
                    }

                    // =========================================================================
                    // PHASE 3: PIMPINAN FINALISASI (AWAITING_PIMPINAN_FINAL)
                    // =========================================================================
                    if (currentPhase == ApprovalPhase.AWAITING_PIMPINAN_FINAL) {
                        Log.d("Approval", "📍 Phase 3: Pimpinan confirming Pengawas decision")

                        val pengawasStatus = currentDualInfo.pengawasApproval.status
                        val isPengawasApproved = pengawasStatus == ApprovalStatus.APPROVED
                        val isPengawasRejected = pengawasStatus == ApprovalStatus.REJECTED

                        // ✅ FIX: Gabungkan catatan Phase 1 dengan catatan finalisasi, jangan timpa
                        val existingPimpinanNote = currentDualInfo.pimpinanApproval.note
                        val finalPimpinanNote = if (catatan.isNotBlank()) {
                            if (existingPimpinanNote.isNotBlank() && existingPimpinanNote != catatan) {
                                "$existingPimpinanNote | Finalisasi: $catatan"
                            } else {
                                catatan
                            }
                        } else {
                            existingPimpinanNote
                        }

                        val updatedDualInfo = currentDualInfo.copy(
                            approvalPhase = ApprovalPhase.COMPLETED,
                            pimpinanFinalConfirmed = true,
                            pimpinanFinalTimestamp = timestamp,
                            pimpinanApproval = currentDualInfo.pimpinanApproval.copy(
                                note = finalPimpinanNote
                            ),
                            finalDecision = if (isPengawasApproved) "approved" else "rejected",
                            finalDecisionBy = ApproverRole.PENGAWAS,
                            finalDecisionTimestamp = timestamp,
                            rejectionReason = if (isPengawasRejected) currentDualInfo.pengawasApproval.note else ""
                        )

                        val updatedPelanggan: Pelanggan

                        if (isPengawasApproved) {
                            Log.d("Approval", "✅ Pengawas approved - Final: APPROVED")

                            val pengawasAdjustedAmount = currentDualInfo.pengawasApproval.adjustedAmount
                            val pengawasAdjustedTenor = currentDualInfo.pengawasApproval.adjustedTenor

                            val finalAmountPhase3 = if (pengawasAdjustedAmount > 0) pengawasAdjustedAmount else existing.besarPinjaman
                            val finalTenorPhase3 = if (pengawasAdjustedTenor > 0) pengawasAdjustedTenor else existing.tenor

                            val hasPengawasAdjustment = pengawasAdjustedAmount > 0 && pengawasAdjustedAmount != existing.besarPinjaman
                            val adjustedValues = if (hasPengawasAdjustment) {
                                calculatePinjamanValues(finalAmountPhase3)
                            } else null

                            updatedPelanggan = existing.copy(
                                status = "Disetujui",
                                statusPencairanSimpanan = "",
                                tanggalLunasCicilan = "",
                                statusKhusus = "",
                                catatanStatusKhusus = "",
                                tanggalStatusKhusus = "",
                                diberiTandaOleh = "",
                                dualApprovalInfo = updatedDualInfo,
                                catatanApproval = catatan.ifBlank { "Disetujui oleh Pengawas" },
                                tanggalApproval = tanggalSekarang,
                                disetujuiOleh = "Pimpinan & Pengawas",
                                besarPinjaman = finalAmountPhase3,
                                besarPinjamanDisetujui = finalAmountPhase3,
                                admin = adjustedValues?.admin ?: existing.admin,
                                simpanan = adjustedValues?.simpanan ?: existing.simpanan,
                                totalPelunasan = adjustedValues?.totalPelunasan ?: existing.totalPelunasan,
                                totalDiterima = adjustedValues?.totalDiterima ?: existing.totalDiterima,
                                tenor = finalTenorPhase3,
                                pembayaranList = pembayaranListBaru,
                                lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                            )
                        } else {
                            Log.d("Approval", "❌ Pengawas rejected - Final: REJECTED")

                            val isTopUp = existing.pinjamanKe > 1
                            val pengawasAlasan = currentDualInfo.pengawasApproval.note.ifBlank { "Ditolak oleh Pengawas" }

                            if (isTopUp) {
                                val dataSebelumTopUp = extractDataSebelumTopUp(existing.catatanPerubahanPinjaman)
                                if (dataSebelumTopUp != null) {
                                    updatedPelanggan = existing.copy(
                                        status = "Aktif",
                                        dualApprovalInfo = updatedDualInfo,
                                        pinjamanKe = dataSebelumTopUp.pinjamanKe,
                                        besarPinjaman = dataSebelumTopUp.besarPinjaman,
                                        admin = dataSebelumTopUp.admin,
                                        simpanan = dataSebelumTopUp.simpanan,
                                        totalDiterima = dataSebelumTopUp.totalDiterima,
                                        totalPelunasan = dataSebelumTopUp.totalPelunasan,
                                        tenor = dataSebelumTopUp.tenor,
                                        tanggalPengajuan = dataSebelumTopUp.tanggalPengajuan,
                                        hasilSimulasiCicilan = if (dataSebelumTopUp.tanggalPengajuan.isNotBlank() && dataSebelumTopUp.totalPelunasan > 0) {
                                            generateCicilanKonsisten(dataSebelumTopUp.tanggalPengajuan, dataSebelumTopUp.tenor, dataSebelumTopUp.totalPelunasan)
                                        } else existing.hasilSimulasiCicilan,
                                        catatanApproval = "Pengajuan top-up ditolak oleh Pengawas: $pengawasAlasan",
                                        tanggalApproval = tanggalSekarang,
                                        disetujuiOleh = currentDualInfo.pengawasApproval.by,
                                        sisaUtangLamaSebelumTopUp = 0,
                                        totalPelunasanLamaSebelumTopUp = 0,
                                        lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                    )
                                } else {
                                    updatedPelanggan = existing.copy(
                                        status = "Aktif",
                                        dualApprovalInfo = updatedDualInfo,
                                        pinjamanKe = existing.pinjamanKe - 1,
                                        catatanApproval = "Pengajuan top-up ditolak oleh Pengawas: $pengawasAlasan",
                                        tanggalApproval = tanggalSekarang,
                                        disetujuiOleh = currentDualInfo.pengawasApproval.by,
                                        lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                    )
                                }
                            } else {
                                updatedPelanggan = existing.copy(
                                    status = "Ditolak",
                                    dualApprovalInfo = updatedDualInfo,
                                    catatanApproval = "Pengajuan ditolak oleh Pengawas: $pengawasAlasan",
                                    tanggalApproval = tanggalSekarang,
                                    disetujuiOleh = currentDualInfo.pengawasApproval.by,
                                    alasanPenolakan = pengawasAlasan,
                                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                )
                            }
                        }

                        val ref = database.child("pelanggan").child(adminUid).child(pelangganId)
                        ref.setValue(updatedPelanggan)
                            .addOnSuccessListener {
                                cabangId?.let { branchId ->
                                    database.child("pengajuan_approval").child(branchId)
                                        .orderByChild("pelangganId")
                                        .equalTo(pelangganId)
                                        .addListenerForSingleValueEvent(object : ValueEventListener {
                                            override fun onDataChange(snapshot: DataSnapshot) {
                                                snapshot.children.forEach { child ->
                                                    child.ref.child("dualApprovalInfo").setValue(buildDualApprovalInfoMap(updatedDualInfo))
                                                        .addOnSuccessListener {
                                                            Log.d("Approval", "✅ Phase 3 complete - COMPLETED")

                                                            // ✅ PERBAIKAN: Kirim notifikasi ke Admin SEBELUM hapus data
                                                            val pengawasApprovalStatus = currentDualInfo.pengawasApproval.status
                                                            val isApprovedByPengawas = pengawasApprovalStatus == ApprovalStatus.APPROVED
                                                            val pengawasApproverName = currentDualInfo.pengawasApproval.by
                                                            val pengawasRejectionNote = currentDualInfo.pengawasApproval.note

                                                            // ✅ PERBAIKAN: Hitung nilai pinjaman yang diajukan dan disetujui
                                                            val pinjamanYangDiajukan = if (existing.besarPinjamanDiajukan > 0) existing.besarPinjamanDiajukan else existing.besarPinjaman
                                                            val pinjamanYangDisetujui = if (updatedPelanggan.besarPinjamanDisetujui > 0) updatedPelanggan.besarPinjamanDisetujui else updatedPelanggan.besarPinjaman
                                                            val tenorYangDiajukan = existing.tenor  // ✅ Pelanggan hanya punya 'tenor', tidak ada 'tenorDiajukan'
                                                            val tenorYangDisetujui = updatedPelanggan.tenor

                                                            val adaPenyesuaianPinjaman = pinjamanYangDiajukan != pinjamanYangDisetujui
                                                            val adaPenyesuaianTenor = tenorYangDiajukan != tenorYangDisetujui
                                                            val adaPenyesuaian = adaPenyesuaianPinjaman || adaPenyesuaianTenor

                                                            val catatanPenyesuaian = if (adaPenyesuaian) {
                                                                buildString {
                                                                    if (adaPenyesuaianPinjaman) {
                                                                        append("Pinjaman disesuaikan dari Rp ${formatRupiah(pinjamanYangDiajukan)} menjadi Rp ${formatRupiah(pinjamanYangDisetujui)}")
                                                                    }
                                                                    if (adaPenyesuaianTenor) {
                                                                        if (isNotEmpty()) append(". ")
                                                                        append("Tenor disesuaikan dari $tenorYangDiajukan hari menjadi $tenorYangDisetujui hari")
                                                                    }
                                                                }
                                                            } else ""

                                                            createAdminNotification(
                                                                adminUid = adminUid,
                                                                pelangganId = pelangganId,
                                                                pelangganNama = existing.namaPanggilan,
                                                                alasanPenolakan = if (isApprovedByPengawas) "" else pengawasRejectionNote,
                                                                pimpinanName = "Pimpinan & $pengawasApproverName",
                                                                type = if (isApprovedByPengawas) "DUAL_APPROVAL_APPROVED" else "DUAL_APPROVAL_REJECTED",
                                                                catatanPersetujuan = if (isApprovedByPengawas) catatan.ifBlank { "Disetujui oleh Pimpinan & Pengawas" } else "",
                                                                pinjamanDiajukan = pinjamanYangDiajukan,
                                                                pinjamanDisetujui = pinjamanYangDisetujui,
                                                                tenorDiajukan = tenorYangDiajukan,
                                                                tenorDisetujui = tenorYangDisetujui,
                                                                isPinjamanDiubah = adaPenyesuaian,
                                                                catatanPerubahanPinjaman = catatanPenyesuaian
                                                            )

                                                            Log.d("Approval", "✅ Notifikasi terkirim ke Admin: $adminUid")

                                                            // BARU hapus setelah notifikasi terkirim
                                                            child.ref.removeValue()

                                                            updateLocalAndRefresh(pelangganId, updatedPelanggan, cabangId)
                                                            onSuccess?.invoke()
                                                        }
                                                }
                                                if (!snapshot.hasChildren()) {
                                                    updateLocalAndRefresh(pelangganId, updatedPelanggan, cabangId)
                                                    onSuccess?.invoke()
                                                }
                                            }
                                            override fun onCancelled(error: DatabaseError) {
                                                Log.e("Approval", "❌ Error: ${error.message}")
                                                updateLocalAndRefresh(pelangganId, updatedPelanggan, cabangId)
                                                onSuccess?.invoke()
                                            }
                                        })
                                } ?: run {
                                    updateLocalAndRefresh(pelangganId, updatedPelanggan, null)
                                    onSuccess?.invoke()
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e("Approval", "❌ Gagal: ${e.message}")
                                onFailure?.invoke(e)
                            }

                        return@launch
                    }

                    // =========================================================================
                    // PHASE LAIN: Pengajuan sudah melewati tahap Pimpinan
                    // =========================================================================
                    val phaseMessage = when (currentPhase) {
                        ApprovalPhase.AWAITING_KOORDINATOR -> "Pengajuan ini sudah Anda setujui dan sedang menunggu review Koordinator (Tahap 2/5)"
                        ApprovalPhase.AWAITING_PENGAWAS -> "Pengajuan ini sedang menunggu review Pengawas (Tahap 3/5)"
                        ApprovalPhase.AWAITING_KOORDINATOR_FINAL -> "Pengajuan ini sedang menunggu konfirmasi Koordinator (Tahap 4/5)"
                        ApprovalPhase.COMPLETED -> "Pengajuan ini sudah selesai diproses"
                        else -> "Pengajuan ini sedang dalam proses approval tahap lain ($currentPhase)"
                    }
                    Log.w("Approval", "⚠️ Phase tidak sesuai untuk Pimpinan: $currentPhase")
                    onFailure?.invoke(Exception(phaseMessage))
                    return@launch
                } else {
                    // =========================================================================
                    // KASUS SINGLE APPROVAL (< 3jt)
                    // =========================================================================
                    Log.d("Approval", "✅ SINGLE APPROVAL: Langsung disetujui")

                    val updatedDualInfo = currentDualInfo.copy(
                        requiresDualApproval = false,
                        pimpinanApproval = updatedPimpinanApproval,
                        finalDecision = "approved",
                        finalDecisionTimestamp = timestamp
                    )

                    val updatedPelanggan = existing.copy(
                        status = "Disetujui",
                        statusPencairanSimpanan = "",
                        tanggalLunasCicilan = "",
                        statusKhusus = "",
                        catatanStatusKhusus = "",
                        tanggalStatusKhusus = "",
                        diberiTandaOleh = "",
                        dualApprovalInfo = updatedDualInfo,
                        catatanApproval = catatan,
                        tanggalApproval = tanggalSekarang,
                        disetujuiOleh = pimpinanName,
                        besarPinjaman = finalBesarPinjaman,
                        besarPinjamanDisetujui = finalBesarPinjaman,
                        besarPinjamanDiajukan = existing.besarPinjamanDiajukan.takeIf { it > 0 } ?: existing.besarPinjaman,
                        admin = finalAdmin,
                        simpanan = finalSimpanan,
                        jasaPinjaman = finalJasaPinjaman,
                        totalDiterima = finalTotalDiterima,
                        totalPelunasan = finalTotalPelunasan,
                        tarikTabungan = tarikTabungan,
                        catatanPerubahanPinjaman = if (adaPenyesuaian || tarikTabungan > 0) catatanPerubahanPinjaman else existing.catatanPerubahanPinjaman,
                        isPinjamanDiubah = adaPenyesuaian || tarikTabungan > 0,
                        pembayaranList = pembayaranListBaru,
                        tenor = finalTenor,
                        hasilSimulasiCicilan = finalSimulasiCicilan,
                        lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    )

                    Log.d("Approval", "📤 Menyimpan ke Firebase...")

                    val ref = database.child("pelanggan").child(adminUid).child(pelangganId)
                    ref.setValue(updatedPelanggan)
                        .addOnSuccessListener {
                            // ✅ Kirim notifikasi ke admin
                            createAdminNotification(
                                adminUid = adminUid,
                                pelangganId = pelangganId,
                                pelangganNama = existing.namaPanggilan,
                                alasanPenolakan = if (adaPenyesuaian) {
                                    "Pengajuan disetujui dengan penyesuaian: Rp ${formatRupiah(finalBesarPinjaman)}"
                                } else {
                                    "Pengajuan disetujui"
                                },
                                pimpinanName = pimpinanName,
                                type = "APPROVAL",
                                catatanPersetujuan = catatan,
                                pinjamanDiajukan = existing.besarPinjamanDiajukan.takeIf { it > 0 } ?: existing.besarPinjaman,
                                pinjamanDisetujui = finalBesarPinjaman,
                                tenorDiajukan = existing.tenor,
                                tenorDisetujui = finalTenor,
                                isPinjamanDiubah = adaPenyesuaian,
                                catatanPerubahanPinjaman = if (adaPenyesuaian) catatanPerubahanPinjaman else ""
                            )

                            // ✅ Hapus dari pengajuan_approval (single approval langsung selesai)
                            cabangId?.let { branchId ->
                                database.child("pengajuan_approval").child(branchId)
                                    .orderByChild("pelangganId")
                                    .equalTo(pelangganId)
                                    .addListenerForSingleValueEvent(object : ValueEventListener {
                                        override fun onDataChange(snapshot: DataSnapshot) {
                                            snapshot.children.forEach { child ->
                                                child.ref.removeValue()
                                                    .addOnSuccessListener {
                                                        Log.d("Approval", "✅ Single approval - pengajuan dihapus")

                                                        updateLocalAndRefresh(pelangganId, updatedPelanggan, cabangId)

                                                        Log.d("Approval", "✅ Pengajuan berhasil disetujui untuk: ${existing.namaPanggilan}")
                                                        markRelatedNotificationsAsRead(pelangganId)

                                                        database.child("data_updates")
                                                            .child(adminUid)
                                                            .child("lastUpdate")
                                                            .setValue(ServerValue.TIMESTAMP)

                                                        onSuccess?.invoke()
                                                    }
                                            }

                                            if (!snapshot.hasChildren()) {
                                                updateLocalAndRefresh(pelangganId, updatedPelanggan, cabangId)
                                                onSuccess?.invoke()
                                            }
                                        }

                                        override fun onCancelled(error: DatabaseError) {
                                            Log.e("Approval", "❌ Error: ${error.message}")
                                            onSuccess?.invoke()
                                        }
                                    })
                            } ?: run {
                                onSuccess?.invoke()
                            }
                        }
                        .addOnFailureListener { e ->
                            val error = Exception("Gagal update status: ${e.message}")
                            Log.e("Approval", "❌ ${error.message}")
                            onFailure?.invoke(error)
                        }
                }

            } catch (e: Exception) {
                Log.e("Approval", "❌ Error dalam proses approval: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    // =========================================================================
    // FUNGSI: Cairkan Pinjaman (Disetujui → Aktif)
    // =========================================================================
    fun cairkanPinjaman(
        pelangganId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                val pelanggan = daftarPelanggan.find { it.id == pelangganId }
                if (pelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }
                if (pelanggan.status != "Disetujui") {
                    onFailure?.invoke(Exception("Status bukan Disetujui"))
                    return@launch
                }

                val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid }
                if (adminUid.isNullOrBlank()) {
                    onFailure?.invoke(Exception("Admin UID tidak valid"))
                    return@launch
                }

                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                val tanggalPencairan = dateFormat.format(Date())

                // Regenerate cicilan berdasarkan tanggalPencairan (bukan tanggalPengajuan)
                val cicilanBaru = generateCicilanKonsisten(
                    tanggalPencairan,
                    pelanggan.tenor,
                    pelanggan.totalPelunasan
                )

                val updatedPelanggan = pelanggan.copy(
                    status = "Aktif",
                    tanggalPencairan = tanggalPencairan,
                    hasilSimulasiCicilan = cicilanBaru,
                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                database.child("pelanggan").child(adminUid).child(pelangganId)
                    .setValue(updatedPelanggan)
                    .addOnSuccessListener {
                        val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                        if (index != -1) {
                            daftarPelanggan[index] = updatedPelanggan
                        }
                        simpanKeLokal()
                        loadDashboardData()

                        // ✅ BARU: Kirim notifikasi ke atasan
                        sendCairkanBatalNotification(pelanggan, isCairkan = true)

                        Log.d("Pencairan", "✅ Pinjaman dicairkan: ${pelanggan.namaPanggilan}")
                        onSuccess?.invoke()
                    }
                    .addOnFailureListener { e ->
                        Log.e("Pencairan", "❌ Gagal cairkan: ${e.message}")
                        onFailure?.invoke(e)
                    }
            } catch (e: Exception) {
                Log.e("Pencairan", "❌ Error cairkan pinjaman: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    // =========================================================================
    // FUNGSI: Batalkan Pinjaman (Disetujui → Tidak Aktif)
    // =========================================================================
    fun batalkanPinjaman(
        pelangganId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                val pelanggan = daftarPelanggan.find { it.id == pelangganId }
                if (pelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }
                if (pelanggan.status != "Disetujui") {
                    onFailure?.invoke(Exception("Status bukan Disetujui"))
                    return@launch
                }

                val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid }
                if (adminUid.isNullOrBlank()) {
                    onFailure?.invoke(Exception("Admin UID tidak valid"))
                    return@launch
                }

                val updatedPelanggan = pelanggan.copy(
                    status = "Tidak Aktif",
                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                database.child("pelanggan").child(adminUid).child(pelangganId)
                    .setValue(updatedPelanggan)
                    .addOnSuccessListener {
                        val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                        if (index != -1) {
                            daftarPelanggan[index] = updatedPelanggan
                        }
                        simpanKeLokal()
                        loadDashboardData()

                        // ✅ BARU: Kirim notifikasi ke atasan
                        sendCairkanBatalNotification(pelanggan, isCairkan = false)

                        Log.d("Pencairan", "✅ Pinjaman dibatalkan: ${pelanggan.namaPanggilan}")
                        onSuccess?.invoke()
                    }
                    .addOnFailureListener { e ->
                        Log.e("Pencairan", "❌ Gagal batalkan: ${e.message}")
                        onFailure?.invoke(e)
                    }
            } catch (e: Exception) {
                Log.e("Pencairan", "❌ Error batalkan pinjaman: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    // =========================================================================
    // NOTIFIKASI CAIRKAN / BATAL PINJAMAN
    // =========================================================================

    /**
     * Kirim notifikasi pencairan/pembatalan pinjaman ke atasan
     * - Pinjaman < 3 juta: hanya pimpinan
     * - Pinjaman >= 3 juta: pimpinan + koordinator + pengawas
     */
    private fun sendCairkanBatalNotification(
        pelanggan: Pelanggan,
        isCairkan: Boolean // true = cairkan, false = batal
    ) {
        viewModelScope.launch {
            try {
                val besarPinjaman = pelanggan.besarPinjaman
                val namaAdmin = try {
                    val uid = Firebase.auth.currentUser?.uid ?: ""
                    val snap = database.child("metadata/admins/$uid/name").get().await()
                    snap.getValue(String::class.java) ?: "Admin"
                } catch (e: Exception) { "Admin" }

                // ============================================================
                // STEP 0: RESOLVE cabangId DENGAN MULTIPLE FALLBACK
                // ============================================================
                var cabangId = pelanggan.cabangId

                // Fallback 1: _currentUserCabang
                if (cabangId.isBlank()) {
                    cabangId = _currentUserCabang.value ?: ""
                    Log.d("CairkanBatalNotif", "📌 cabangId dari _currentUserCabang: '$cabangId'")
                }

                // Fallback 2: Baca langsung dari metadata/admins/{uid}/cabang
                if (cabangId.isBlank()) {
                    try {
                        val adminUid = Firebase.auth.currentUser?.uid ?: ""
                        val snap = database.child("metadata/admins/$adminUid/cabang").get().await()
                        cabangId = snap.getValue(String::class.java) ?: ""
                        Log.d("CairkanBatalNotif", "📌 cabangId dari metadata/admins: '$cabangId'")
                    } catch (e: Exception) {
                        Log.w("CairkanBatalNotif", "⚠️ Gagal baca cabang dari metadata: ${e.message}")
                    }
                }

                // Fallback 3: Cari dari adminUid pelanggan
                if (cabangId.isBlank() && pelanggan.adminUid.isNotBlank()) {
                    try {
                        val snap = database.child("metadata/admins/${pelanggan.adminUid}/cabang").get().await()
                        cabangId = snap.getValue(String::class.java) ?: ""
                        Log.d("CairkanBatalNotif", "📌 cabangId dari pelanggan.adminUid metadata: '$cabangId'")
                    } catch (e: Exception) {
                        Log.w("CairkanBatalNotif", "⚠️ Gagal baca cabang dari pelanggan admin: ${e.message}")
                    }
                }

                if (cabangId.isBlank()) {
                    Log.e("CairkanBatalNotif", "❌ cabangId kosong setelah semua fallback, tidak bisa kirim notifikasi")
                    return@launch
                }

                val title = if (isCairkan) "Pinjaman Dicairkan" else "Pinjaman Dibatalkan"
                val message = if (isCairkan) {
                    "Uang telah diberikan kepada nasabah atas nama ${pelanggan.namaPanggilan}"
                } else {
                    "Pinjaman nasabah atas nama ${pelanggan.namaPanggilan} dibatalkan"
                }
                val notifType = if (isCairkan) "PENCAIRAN" else "PEMBATALAN"

                Log.d("CairkanBatalNotif", "📢 Sending $notifType notification")
                Log.d("CairkanBatalNotif", "   Besar pinjaman: $besarPinjaman")
                Log.d("CairkanBatalNotif", "   CabangId: $cabangId")

                // ============================================================
                // STEP 1: Kirim ke PIMPINAN (SELALU, berapapun pinjamannya)
                // ============================================================
                sendNotifToPimpinan(cabangId, pelanggan.id, title, message, notifType, pelanggan.namaPanggilan)

                // ============================================================
                // STEP 2: Jika >= 3 juta, kirim juga ke KOORDINATOR & PENGAWAS
                // ============================================================
                if (besarPinjaman >= 3_000_000) {
                    Log.d("CairkanBatalNotif", "💰 Pinjaman >= 3 juta, kirim ke koordinator & pengawas juga")
                    sendNotifToKoordinator(pelanggan.id, title, message, notifType, pelanggan.namaPanggilan)
                    sendNotifToPengawas(pelanggan.id, title, message, notifType, pelanggan.namaPanggilan)
                }

            } catch (e: Exception) {
                Log.e("CairkanBatalNotif", "❌ Error sending notification: ${e.message}")
            }
        }
    }

    private suspend fun sendNotifToPimpinan(
        cabangId: String,
        pelangganId: String,
        title: String,
        message: String,
        notifType: String,
        pelangganNama: String
    ) {
        try {
            var pimpinanUid: String? = null

            // Path 1: metadata/roles/pimpinan/{cabangId} (exact match)
            try {
                val snap = database.child("metadata").child("roles").child("pimpinan").child(cabangId).get().await()
                pimpinanUid = snap.getValue(String::class.java)
                Log.d("CairkanBatalNotif", "📍 Path 1 (metadata/roles/pimpinan/$cabangId): $pimpinanUid")
            } catch (_: Exception) {}

            // Path 2: metadata/cabang/{cabangId}/pimpinanUid (SAMA DENGAN CLOUD FUNCTION)
            if (pimpinanUid.isNullOrBlank()) {
                try {
                    val snap = database.child("metadata").child("cabang").child(cabangId).child("pimpinanUid").get().await()
                    pimpinanUid = snap.getValue(String::class.java)
                    Log.d("CairkanBatalNotif", "📍 Path 2 (metadata/cabang/$cabangId/pimpinanUid): $pimpinanUid")
                } catch (_: Exception) {}
            }

            // Path 3: metadata/cabang/{cabangId}/pimpinan
            if (pimpinanUid.isNullOrBlank()) {
                try {
                    val snap = database.child("metadata").child("cabang").child(cabangId).child("pimpinan").get().await()
                    pimpinanUid = snap.getValue(String::class.java)
                    Log.d("CairkanBatalNotif", "📍 Path 3 (metadata/cabang/$cabangId/pimpinan): $pimpinanUid")
                } catch (_: Exception) {}
            }

            // Path 4: Iterasi metadata/roles/pimpinan/* (case-insensitive, SAMA DENGAN SERAH TERIMA)
            if (pimpinanUid.isNullOrBlank()) {
                try {
                    Log.d("CairkanBatalNotif", "🔄 Path 4: Iterasi semua pimpinan...")
                    val allPimpinanSnapshot = database.child("metadata").child("roles").child("pimpinan").get().await()
                    for (child in allPimpinanSnapshot.children) {
                        val key = child.key
                        val value = child.getValue(String::class.java)
                        Log.d("CairkanBatalNotif", "   Found: $key -> $value")
                        if (key?.lowercase() == cabangId.lowercase()) {
                            pimpinanUid = value
                            Log.d("CairkanBatalNotif", "✅ Case-insensitive match: $key = $pimpinanUid")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CairkanBatalNotif", "❌ Path 4 gagal: ${e.message}")
                }
            }

            if (pimpinanUid.isNullOrBlank()) {
                Log.e("CairkanBatalNotif", "❌ Pimpinan tidak ditemukan untuk cabang: $cabangId")
                Log.e("CairkanBatalNotif", "   Checked paths:")
                Log.e("CairkanBatalNotif", "   1. metadata/roles/pimpinan/$cabangId")
                Log.e("CairkanBatalNotif", "   2. metadata/cabang/$cabangId/pimpinanUid")
                Log.e("CairkanBatalNotif", "   3. metadata/cabang/$cabangId/pimpinan")
                Log.e("CairkanBatalNotif", "   4. Iterasi metadata/roles/pimpinan/* (case-insensitive)")
                return
            }

            val notificationId = "${notifType.lowercase()}_${pelangganId}_${System.currentTimeMillis()}"
            val notificationData = mapOf(
                "id" to notificationId,
                "type" to notifType,
                "title" to title,
                "message" to message,
                "pelangganId" to pelangganId,
                "pelangganNama" to pelangganNama,
                "timestamp" to System.currentTimeMillis(),
                "read" to false
            )

            database.child("admin_notifications").child(pimpinanUid).child(notificationId)
                .setValue(notificationData).await()

            Log.d("CairkanBatalNotif", "✅ Notifikasi dikirim ke pimpinan: $pimpinanUid")
        } catch (e: Exception) {
            Log.e("CairkanBatalNotif", "❌ Gagal kirim ke pimpinan: ${e.message}")
        }
    }

    private suspend fun sendNotifToKoordinator(
        pelangganId: String,
        title: String,
        message: String,
        notifType: String,
        pelangganNama: String
    ) {
        try {
            val koordinatorSnapshot = database.child("metadata/roles/koordinator").get().await()
            if (!koordinatorSnapshot.exists()) {
                Log.w("CairkanBatalNotif", "⚠️ Tidak ada koordinator terdaftar")
                return
            }

            val koordinatorUids = koordinatorSnapshot.children.mapNotNull { it.key }
            for (uid in koordinatorUids) {
                val notificationId = "${notifType.lowercase()}_koordinator_${pelangganId}_${System.currentTimeMillis()}"
                val notificationData = mapOf(
                    "id" to notificationId,
                    "type" to notifType,
                    "title" to title,
                    "message" to message,
                    "pelangganId" to pelangganId,
                    "pelangganNama" to pelangganNama,
                    "timestamp" to System.currentTimeMillis(),
                    "read" to false
                )
                database.child("admin_notifications/$uid/$notificationId")
                    .setValue(notificationData).await()
                Log.d("CairkanBatalNotif", "✅ Notifikasi dikirim ke koordinator: $uid")
            }
        } catch (e: Exception) {
            Log.e("CairkanBatalNotif", "❌ Gagal kirim ke koordinator: ${e.message}")
        }
    }

    private suspend fun sendNotifToPengawas(
        pelangganId: String,
        title: String,
        message: String,
        notifType: String,
        pelangganNama: String
    ) {
        try {
            val pengawasSnapshot = database.child("metadata/roles/pengawas").get().await()
            if (!pengawasSnapshot.exists()) {
                Log.w("CairkanBatalNotif", "⚠️ Tidak ada pengawas terdaftar")
                return
            }

            val pengawasUids = pengawasSnapshot.children.mapNotNull { it.key }
            for (uid in pengawasUids) {
                val notificationId = "${notifType.lowercase()}_pengawas_${pelangganId}_${System.currentTimeMillis()}"
                val notificationData = mapOf(
                    "id" to notificationId,
                    "type" to notifType,
                    "title" to title,
                    "message" to message,
                    "pelangganId" to pelangganId,
                    "pelangganNama" to pelangganNama,
                    "timestamp" to System.currentTimeMillis(),
                    "read" to false
                )
                database.child("admin_notifications/$uid/$notificationId")
                    .setValue(notificationData).await()
                Log.d("CairkanBatalNotif", "✅ Notifikasi dikirim ke pengawas: $uid")
            }
        } catch (e: Exception) {
            Log.e("CairkanBatalNotif", "❌ Gagal kirim ke pengawas: ${e.message}")
        }
    }

    private fun updateLocalAndRefresh(pelangganId: String, updatedPelanggan: Pelanggan, cabangId: String?) {
        val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
        if (index != -1) {
            daftarPelanggan[index] = updatedPelanggan
        } else {
            daftarPelanggan.add(updatedPelanggan)
        }

        simpanKeLokal()
        loadDashboardData()

        cabangId?.let {
            loadPendingApprovalsOptimized(it)
        }
    }


    private suspend fun findPelangganFromFirebaseFresh(
        pelangganId: String,
        cabangId: String?
    ): Pelanggan? {
        return try {
            Log.d("Approval", "🔍 findPelangganFromFirebaseFresh: $pelangganId")

            // =====================================================================
            // LANGKAH 1: Cari di pendingApprovals yang SUDAH di-load
            // =====================================================================
            val fromPending = pendingApprovals.find { it.id == pelangganId }

            if (fromPending != null) {
                Log.d("Approval", "✅ Ditemukan di pendingApprovals!")
                Log.d("Approval", "   Nama: ${fromPending.namaPanggilan}")
                Log.d("Approval", "   AdminUid: ${fromPending.adminUid}")
                Log.d("Approval", "   PinjamanKe: ${fromPending.pinjamanKe}")
                Log.d("Approval", "   BesarPinjaman: ${fromPending.besarPinjaman}")
                Log.d("Approval", "   TotalPelunasan: ${fromPending.totalPelunasan}")

                // =====================================================================
                // LANGKAH 2: Baca data FRESH dari Firebase menggunakan adminUid
                // =====================================================================
                if (fromPending.adminUid.isNotBlank()) {
                    try {
                        val freshSnap = database.child("pelanggan")
                            .child(fromPending.adminUid)
                            .child(pelangganId)
                            .get()
                            .await()

                        val freshData = freshSnap.getValue(Pelanggan::class.java)
                        if (freshData != null) {
                            Log.d("Approval", "✅ Data FRESH dari Firebase berhasil dibaca!")
                            Log.d("Approval", "   PinjamanKe: ${freshData.pinjamanKe}")
                            Log.d("Approval", "   BesarPinjaman: ${freshData.besarPinjaman}")
                            Log.d("Approval", "   TotalPelunasan: ${freshData.totalPelunasan}")
                            Log.d("Approval", "   TotalDiterima: ${freshData.totalDiterima}")
                            return freshData
                        }
                    } catch (e: Exception) {
                        Log.w("Approval", "⚠️ Gagal baca fresh dari Firebase: ${e.message}")
                    }
                }

                // Jika gagal baca fresh, gunakan data dari pendingApprovals
                Log.d("Approval", "🔄 Menggunakan data dari pendingApprovals")
                return fromPending
            }

            // =====================================================================
            // LANGKAH 3: Jika tidak ada di pendingApprovals, cari di daftarPelanggan
            // =====================================================================
            val fromDaftar = daftarPelanggan.find { it.id == pelangganId }
            if (fromDaftar != null) {
                Log.d("Approval", "✅ Ditemukan di daftarPelanggan: ${fromDaftar.namaPanggilan}")

                // Baca fresh dari Firebase
                if (fromDaftar.adminUid.isNotBlank()) {
                    try {
                        val freshSnap = database.child("pelanggan")
                            .child(fromDaftar.adminUid)
                            .child(pelangganId)
                            .get()
                            .await()

                        val freshData = freshSnap.getValue(Pelanggan::class.java)
                        if (freshData != null) {
                            Log.d("Approval", "✅ Data fresh dari Firebase")
                            return freshData
                        }
                    } catch (e: Exception) {
                        Log.w("Approval", "⚠️ Error: ${e.message}")
                    }
                }

                return fromDaftar
            }

            // =====================================================================
            // LANGKAH 4: Fallback - cari di semua admin (tanpa query index)
            // =====================================================================
            Log.d("Approval", "🔄 Fallback: mencari di semua admin...")

            val adminsSnap = database.child("pelanggan").get().await()

            for (adminSnap in adminsSnap.children) {
                val pelangganSnap = adminSnap.child(pelangganId)
                if (pelangganSnap.exists()) {
                    val pelanggan = pelangganSnap.getValue(Pelanggan::class.java)
                    if (pelanggan != null) {
                        Log.d("Approval", "✅ Ditemukan di admin: ${adminSnap.key}")
                        return pelanggan
                    }
                }
            }

            Log.e("Approval", "❌ Pelanggan tidak ditemukan di manapun: $pelangganId")
            null

        } catch (e: Exception) {
            Log.e("Approval", "❌ Error findPelangganFromFirebaseFresh: ${e.message}")

            // =====================================================================
            // FALLBACK TERAKHIR: Kembalikan data dari pendingApprovals jika ada
            // =====================================================================
            val fallback = pendingApprovals.find { it.id == pelangganId }
            if (fallback != null) {
                Log.d("Approval", "🔄 Fallback ke pendingApprovals: ${fallback.namaPanggilan}")
                return fallback
            }

            null
        }
    }

    fun rejectPengajuan(
        pelangganId: String,
        alasan: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                // Cari pelanggan
                var index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                var pelanggan: Pelanggan? = null

                if (index != -1) {
                    pelanggan = daftarPelanggan[index]
                    Log.d("Rejection", "✅ Pelanggan ditemukan di daftar lokal: ${pelanggan.namaPanggilan}")
                } else {
                    pelanggan = _pendingApprovals.find { it.id == pelangganId }
                    if (pelanggan != null) {
                        Log.d("Rejection", "✅ Pelanggan ditemukan di pendingApprovals: ${pelanggan.namaPanggilan}")
                        daftarPelanggan.add(pelanggan)
                        index = daftarPelanggan.size - 1
                    } else {
                        Log.d("Rejection", "🔍 Pelanggan tidak ditemukan di lokal, mencari dari Firebase...")
                        val cabangId = currentUserCabang.value
                        pelanggan = findPelangganFromFirebase(pelangganId, cabangId)
                        if (pelanggan != null) {
                            Log.d("Rejection", "✅ Pelanggan ditemukan di Firebase: ${pelanggan.namaPanggilan}")
                            daftarPelanggan.add(pelanggan)
                            index = daftarPelanggan.size - 1
                        }
                    }
                }

                if (pelanggan == null) {
                    val error = Exception("Pelanggan tidak ditemukan dengan ID: $pelangganId")
                    Log.e("Rejection", "❌ ${error.message}")
                    onFailure?.invoke(error)
                    return@launch
                }

                val pimpinanUid = Firebase.auth.currentUser?.uid ?: ""
                val pimpinanSnap = database.child("metadata/admins/$pimpinanUid/name").get().await()
                val pimpinanName = pimpinanSnap.getValue(String::class.java)
                    ?: Firebase.auth.currentUser?.displayName
                    ?: Firebase.auth.currentUser?.email
                    ?: "Pimpinan"
                val timestamp = System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                val tanggalSekarang = dateFormat.format(Date())

                // =========================================================================
                // ✅ CEK APAKAH INI DUAL APPROVAL (>= 3jt)
                // =========================================================================
                val requiresDualApproval = DualApprovalThreshold.requiresDualApproval(pelanggan.besarPinjaman)

                val currentDualInfo = pelanggan.dualApprovalInfo ?: DualApprovalInfo(
                    requiresDualApproval = requiresDualApproval
                )

                val updatedPimpinanApproval = IndividualApproval(
                    status = ApprovalStatus.REJECTED,
                    by = pimpinanName,
                    uid = pimpinanUid,
                    timestamp = timestamp,
                    note = alasan
                )

                Log.d("Rejection", "📊 Analisis penolakan Pimpinan:")
                Log.d("Rejection", "   requiresDualApproval: $requiresDualApproval")
                Log.d("Rejection", "   besarPinjaman: ${pelanggan.besarPinjaman}")
                Log.d("Rejection", "   pengawasStatus: ${currentDualInfo.pengawasApproval.status}")

                // =========================================================================
                // ✅ DUAL APPROVAL: CEK APAKAH PENGAWAS SUDAH MELAKUKAN AKSI
                // =========================================================================
                if (requiresDualApproval) {
                    // ✅ BARU: Cek phase saat ini
                    val currentPhase = currentDualInfo.approvalPhase

                    Log.d("Rejection", "📍 Current Phase: $currentPhase")

                    // =========================================================================
                    // PHASE 1: PIMPINAN REVIEW AWAL - REJECT
                    // =========================================================================
                    if (currentPhase == ApprovalPhase.AWAITING_PIMPINAN || currentPhase.isEmpty()) {
                        Log.d("Rejection", "📍 Phase 1: Pimpinan initial review - REJECT")

                        // Update dualApprovalInfo dan PINDAH KE PHASE 2 (KOORDINATOR)
                        // (Meskipun Pimpinan reject, Koordinator tetap harus review)
                        val updatedDualInfo = currentDualInfo.copy(
                            requiresDualApproval = true,
                            approvalPhase = ApprovalPhase.AWAITING_KOORDINATOR, // ✅ PINDAH KE PHASE 2 (KOORDINATOR)
                            pimpinanApproval = updatedPimpinanApproval
                        )

                        val updatedPelanggan = pelanggan.copy(
                            status = "Menunggu Approval", // Status TETAP menunggu
                            dualApprovalInfo = updatedDualInfo,
                            lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        )

                        database.child("pelanggan").child(pelanggan.adminUid)
                            .child(pelangganId).setValue(updatedPelanggan)
                            .addOnSuccessListener {
                                // Update pengajuan_approval untuk trigger cloud function
                                currentUserCabang.value?.let { cabangId ->
                                    database.child("pengajuan_approval").child(cabangId)
                                        .orderByChild("pelangganId")
                                        .equalTo(pelangganId)
                                        .addListenerForSingleValueEvent(object : ValueEventListener {
                                            override fun onDataChange(snapshot: DataSnapshot) {
                                                snapshot.children.forEach { child ->
                                                    child.ref.child("dualApprovalInfo").setValue(buildDualApprovalInfoMap(updatedDualInfo))
                                                        .addOnSuccessListener {
                                                            Log.d("Rejection", "✅ Phase 1 REJECT - moved to Phase 2 (AWAITING_KOORDINATOR)")
                                                            Log.d("Rejection", "✅ Koordinator akan review berikutnya")
                                                            if (index != -1) daftarPelanggan[index] = updatedPelanggan
                                                            simpanKeLokal()
                                                            loadPendingApprovalsOptimized(cabangId)
                                                            onSuccess?.invoke()
                                                        }
                                                }
                                                if (!snapshot.hasChildren()) {
                                                    if (index != -1) daftarPelanggan[index] = updatedPelanggan
                                                    simpanKeLokal()
                                                    onSuccess?.invoke()
                                                }
                                            }
                                            override fun onCancelled(error: DatabaseError) {
                                                onSuccess?.invoke()
                                            }
                                        })
                                } ?: onSuccess?.invoke()
                            }
                            .addOnFailureListener { e ->
                                onFailure?.invoke(e)
                            }

                        return@launch
                    }

                    // =========================================================================
                    // PHASE 3: PIMPINAN FINALISASI - REJECT (Konfirmasi penolakan Pengawas)
                    // =========================================================================
                    if (currentPhase == ApprovalPhase.AWAITING_PIMPINAN_FINAL) {
                        Log.d("Rejection", "📍 Phase 3: Pimpinan confirming Pengawas decision")

                        val pengawasStatus = currentDualInfo.pengawasApproval.status
                        val isPengawasApproved = pengawasStatus == ApprovalStatus.APPROVED
                        val isPengawasRejected = pengawasStatus == ApprovalStatus.REJECTED

                        // ✅ FIX: Gabungkan catatan Phase 1 dengan catatan finalisasi, jangan timpa
                        val existingPimpinanNote = currentDualInfo.pimpinanApproval.note
                        val finalPimpinanNote = if (alasan.isNotBlank()) {
                            if (existingPimpinanNote.isNotBlank() && existingPimpinanNote != alasan) {
                                "$existingPimpinanNote | Finalisasi: $alasan"
                            } else {
                                alasan
                            }
                        } else {
                            existingPimpinanNote
                        }

                        val updatedDualInfo = currentDualInfo.copy(
                            approvalPhase = ApprovalPhase.COMPLETED,
                            pimpinanFinalConfirmed = true,
                            pimpinanFinalTimestamp = timestamp,
                            pimpinanApproval = currentDualInfo.pimpinanApproval.copy(
                                note = finalPimpinanNote
                            ),
                            finalDecision = if (isPengawasApproved) "approved" else "rejected",
                            finalDecisionBy = ApproverRole.PENGAWAS,
                            finalDecisionTimestamp = timestamp,
                            rejectionReason = if (isPengawasRejected) currentDualInfo.pengawasApproval.note else ""
                        )

                        val updatedPelanggan: Pelanggan

                        if (isPengawasApproved) {
                            Log.d("Rejection", "✅ Pengawas approved - Final: APPROVED")

                            val pengawasAdjustedAmount = currentDualInfo.pengawasApproval.adjustedAmount
                            val pengawasAdjustedTenor = currentDualInfo.pengawasApproval.adjustedTenor

                            val finalAmountPhase3 = if (pengawasAdjustedAmount > 0) pengawasAdjustedAmount else pelanggan.besarPinjaman
                            val finalTenorPhase3 = if (pengawasAdjustedTenor > 0) pengawasAdjustedTenor else pelanggan.tenor

                            val hasPengawasAdjustment = pengawasAdjustedAmount > 0 && pengawasAdjustedAmount != pelanggan.besarPinjaman
                            val adjustedValues = if (hasPengawasAdjustment) {
                                calculatePinjamanValues(finalAmountPhase3)
                            } else null

                            updatedPelanggan = pelanggan.copy(
                                status = "Disetujui",
                                dualApprovalInfo = updatedDualInfo,
                                catatanApproval = "Disetujui oleh Pengawas",
                                tanggalApproval = tanggalSekarang,
                                disetujuiOleh = "Pimpinan & Pengawas",
                                besarPinjaman = finalAmountPhase3,
                                besarPinjamanDisetujui = finalAmountPhase3,
                                admin = adjustedValues?.admin ?: pelanggan.admin,
                                simpanan = adjustedValues?.simpanan ?: pelanggan.simpanan,
                                totalPelunasan = adjustedValues?.totalPelunasan ?: pelanggan.totalPelunasan,
                                totalDiterima = adjustedValues?.totalDiterima ?: pelanggan.totalDiterima,
                                tenor = finalTenorPhase3,
                                lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                                sisaUtangLamaSebelumTopUp = 0,
                                totalPelunasanLamaSebelumTopUp = 0
                            )
                        } else {
                            Log.d("Rejection", "❌ Pengawas rejected - Final: REJECTED")

                            val isTopUp = pelanggan.pinjamanKe > 1
                            val pengawasAlasan = currentDualInfo.pengawasApproval.note.ifBlank { alasan }

                            if (isTopUp) {
                                val dataSebelumTopUp = extractDataSebelumTopUp(pelanggan.catatanPerubahanPinjaman)
                                if (dataSebelumTopUp != null) {
                                    updatedPelanggan = pelanggan.copy(
                                        status = "Aktif",
                                        dualApprovalInfo = updatedDualInfo,
                                        pinjamanKe = dataSebelumTopUp.pinjamanKe,
                                        besarPinjaman = dataSebelumTopUp.besarPinjaman,
                                        admin = dataSebelumTopUp.admin,
                                        simpanan = dataSebelumTopUp.simpanan,
                                        totalDiterima = dataSebelumTopUp.totalDiterima,
                                        totalPelunasan = dataSebelumTopUp.totalPelunasan,
                                        tenor = dataSebelumTopUp.tenor,
                                        tanggalPengajuan = dataSebelumTopUp.tanggalPengajuan,
                                        hasilSimulasiCicilan = if (dataSebelumTopUp.tanggalPengajuan.isNotBlank() && dataSebelumTopUp.totalPelunasan > 0) {
                                            generateCicilanKonsisten(dataSebelumTopUp.tanggalPengajuan, dataSebelumTopUp.tenor, dataSebelumTopUp.totalPelunasan)
                                        } else pelanggan.hasilSimulasiCicilan,
                                        catatanApproval = "Pengajuan top-up ditolak oleh Pengawas: $pengawasAlasan",
                                        tanggalApproval = tanggalSekarang,
                                        disetujuiOleh = currentDualInfo.pengawasApproval.by,
                                        sisaUtangLamaSebelumTopUp = 0,
                                        totalPelunasanLamaSebelumTopUp = 0,
                                        lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                    )
                                } else {
                                    updatedPelanggan = pelanggan.copy(
                                        status = "Aktif",
                                        dualApprovalInfo = updatedDualInfo,
                                        pinjamanKe = pelanggan.pinjamanKe - 1,
                                        catatanApproval = "Pengajuan top-up ditolak oleh Pengawas: $pengawasAlasan",
                                        tanggalApproval = tanggalSekarang,
                                        disetujuiOleh = currentDualInfo.pengawasApproval.by,
                                        lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                    )
                                }
                            } else {
                                updatedPelanggan = pelanggan.copy(
                                    status = "Ditolak",
                                    dualApprovalInfo = updatedDualInfo,
                                    catatanApproval = "Pengajuan ditolak oleh Pengawas: $pengawasAlasan",
                                    tanggalApproval = tanggalSekarang,
                                    disetujuiOleh = currentDualInfo.pengawasApproval.by,
                                    alasanPenolakan = pengawasAlasan,
                                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                )
                            }
                        }

                        // Simpan ke Firebase
                        val adminUidToUse = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid ?: "" }
                        database.child("pelanggan").child(adminUidToUse).child(pelangganId)
                            .setValue(updatedPelanggan)
                            .addOnSuccessListener {
                                currentUserCabang.value?.let { branchId ->
                                    database.child("pengajuan_approval").child(branchId)
                                        .orderByChild("pelangganId")
                                        .equalTo(pelangganId)
                                        .addListenerForSingleValueEvent(object : ValueEventListener {
                                            override fun onDataChange(snapshot: DataSnapshot) {
                                                snapshot.children.forEach { child ->
                                                    child.ref.child("dualApprovalInfo").setValue(buildDualApprovalInfoMap(updatedDualInfo))
                                                        .addOnSuccessListener {
                                                            Log.d("Rejection", "✅ Phase 3 complete - COMPLETED")

                                                            // ✅ PERBAIKAN: Kirim notifikasi ke Admin SEBELUM hapus data
                                                            val pengawasApprovalStatus = currentDualInfo.pengawasApproval.status
                                                            val isApprovedByPengawas = pengawasApprovalStatus == ApprovalStatus.APPROVED
                                                            val pengawasApproverName = currentDualInfo.pengawasApproval.by
                                                            val pengawasRejectionNote = currentDualInfo.pengawasApproval.note.ifBlank { alasan }

                                                            val adminUidForNotif = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid ?: "" }

                                                            // ✅ PERBAIKAN: Hitung nilai pinjaman yang diajukan dan disetujui
                                                            val pinjamanYangDiajukan = if (pelanggan.besarPinjamanDiajukan > 0) pelanggan.besarPinjamanDiajukan else pelanggan.besarPinjaman
                                                            val pinjamanYangDisetujui = if (updatedPelanggan.besarPinjamanDisetujui > 0) updatedPelanggan.besarPinjamanDisetujui else updatedPelanggan.besarPinjaman
                                                            val tenorYangDiajukan = pelanggan.tenor  // ✅ Pelanggan hanya punya 'tenor', tidak ada 'tenorDiajukan'
                                                            val tenorYangDisetujui = updatedPelanggan.tenor

                                                            val adaPenyesuaianPinjaman = pinjamanYangDiajukan != pinjamanYangDisetujui
                                                            val adaPenyesuaianTenor = tenorYangDiajukan != tenorYangDisetujui
                                                            val adaPenyesuaian = adaPenyesuaianPinjaman || adaPenyesuaianTenor

                                                            val catatanPenyesuaian = if (adaPenyesuaian) {
                                                                buildString {
                                                                    if (adaPenyesuaianPinjaman) {
                                                                        append("Pinjaman disesuaikan dari Rp ${formatRupiah(pinjamanYangDiajukan)} menjadi Rp ${formatRupiah(pinjamanYangDisetujui)}")
                                                                    }
                                                                    if (adaPenyesuaianTenor) {
                                                                        if (isNotEmpty()) append(". ")
                                                                        append("Tenor disesuaikan dari $tenorYangDiajukan hari menjadi $tenorYangDisetujui hari")
                                                                    }
                                                                }
                                                            } else ""

                                                            createAdminNotification(
                                                                adminUid = adminUidForNotif,
                                                                pelangganId = pelangganId,
                                                                pelangganNama = pelanggan.namaPanggilan,
                                                                alasanPenolakan = if (isApprovedByPengawas) "" else pengawasRejectionNote,
                                                                pimpinanName = "Pimpinan & $pengawasApproverName",
                                                                type = if (isApprovedByPengawas) "DUAL_APPROVAL_APPROVED" else "DUAL_APPROVAL_REJECTED",
                                                                catatanPersetujuan = "",
                                                                pinjamanDiajukan = pinjamanYangDiajukan,
                                                                pinjamanDisetujui = pinjamanYangDisetujui,
                                                                tenorDiajukan = tenorYangDiajukan,
                                                                tenorDisetujui = tenorYangDisetujui,
                                                                isPinjamanDiubah = adaPenyesuaian,
                                                                catatanPerubahanPinjaman = catatanPenyesuaian
                                                            )

                                                            Log.d("Rejection", "✅ Notifikasi terkirim ke Admin: $adminUidForNotif")

                                                            // BARU hapus setelah notifikasi terkirim
                                                            child.ref.removeValue()

                                                            if (index != -1) {
                                                                daftarPelanggan[index] = updatedPelanggan
                                                            }
                                                            simpanKeLokal()
                                                            loadPendingApprovalsOptimized(branchId)
                                                            onSuccess?.invoke()
                                                        }
                                                }
                                                if (!snapshot.hasChildren()) {
                                                    if (index != -1) {
                                                        daftarPelanggan[index] = updatedPelanggan
                                                    }
                                                    simpanKeLokal()
                                                    onSuccess?.invoke()
                                                }
                                            }
                                            override fun onCancelled(error: DatabaseError) {
                                                Log.e("Rejection", "❌ Error: ${error.message}")
                                                onSuccess?.invoke()
                                            }
                                        })
                                } ?: run {
                                    if (index != -1) {
                                        daftarPelanggan[index] = updatedPelanggan
                                    }
                                    simpanKeLokal()
                                    onSuccess?.invoke()
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e("Rejection", "❌ Gagal: ${e.message}")
                                onFailure?.invoke(e)
                            }

                        return@launch
                    }

                    // Phase lain: Pengajuan sudah melewati tahap Pimpinan
                    val phaseMessage = when (currentPhase) {
                        ApprovalPhase.AWAITING_KOORDINATOR -> "Pengajuan ini sudah melewati tahap Pimpinan dan sedang menunggu review Koordinator (Tahap 2/5)"
                        ApprovalPhase.AWAITING_PENGAWAS -> "Pengajuan ini sedang menunggu review Pengawas (Tahap 3/5)"
                        ApprovalPhase.AWAITING_KOORDINATOR_FINAL -> "Pengajuan ini sedang menunggu konfirmasi Koordinator (Tahap 4/5)"
                        ApprovalPhase.COMPLETED -> "Pengajuan ini sudah selesai diproses"
                        else -> "Pengajuan ini sedang dalam proses approval tahap lain ($currentPhase)"
                    }
                    Log.w("Rejection", "⚠️ Phase tidak sesuai untuk Pimpinan: $currentPhase")
                    onFailure?.invoke(Exception(phaseMessage))
                    return@launch
                }

                // =========================================================================
                // PROSES PENOLAKAN FINAL
                // (Single approval < 3jt ATAU Dual approval yang sudah lengkap)
                // =========================================================================
                val updatedDualInfo = currentDualInfo.copy(
                    pimpinanApproval = updatedPimpinanApproval,
                    finalDecision = "rejected",
                    finalDecisionBy = ApproverRole.PIMPINAN,
                    finalDecisionTimestamp = timestamp,
                    rejectionReason = alasan
                )

                val isTopUpDitolak = pelanggan.pinjamanKe > 1
                val isPengajuanBaruDitolak = pelanggan.pinjamanKe <= 1

                Log.d("Rejection", "📊 Proses penolakan FINAL:")
                Log.d("Rejection", "   isTopUpDitolak: $isTopUpDitolak")
                Log.d("Rejection", "   isPengajuanBaruDitolak: $isPengajuanBaruDitolak")

                if (isTopUpDitolak) {
                    // ==========================================
                    // KASUS 1: TOP-UP DITOLAK (pinjamanKe > 1)
                    // ==========================================
                    Log.d("Rejection", "🔄 Processing top-up rejection for: ${pelanggan.namaPanggilan}")

                    val dataSebelumTopUp = extractDataSebelumTopUp(pelanggan.catatanPerubahanPinjaman)

                    val updatedPelanggan = if (dataSebelumTopUp != null) {
                        pelanggan.copy(
                            status = "Aktif",
                            dualApprovalInfo = updatedDualInfo,
                            pinjamanKe = dataSebelumTopUp.pinjamanKe,
                            besarPinjaman = dataSebelumTopUp.besarPinjaman,
                            admin = dataSebelumTopUp.admin,
                            simpanan = dataSebelumTopUp.simpanan,
                            totalDiterima = dataSebelumTopUp.totalDiterima,
                            totalPelunasan = dataSebelumTopUp.totalPelunasan,
                            tenor = dataSebelumTopUp.tenor,
                            tanggalPengajuan = dataSebelumTopUp.tanggalPengajuan,
                            pembayaranList = pelanggan.pembayaranList,
                            hasilSimulasiCicilan = if (dataSebelumTopUp.tanggalPengajuan.isNotBlank() && dataSebelumTopUp.totalPelunasan > 0) {
                                generateCicilanKonsisten(dataSebelumTopUp.tanggalPengajuan, dataSebelumTopUp.tenor, dataSebelumTopUp.totalPelunasan)
                            } else pelanggan.hasilSimulasiCicilan,
                            catatanApproval = "Pengajuan top-up ditolak: $alasan",
                            tanggalApproval = tanggalSekarang,
                            disetujuiOleh = pimpinanName,
                            sisaUtangLamaSebelumTopUp = 0,
                            totalPelunasanLamaSebelumTopUp = 0
                        )
                    } else {
                        pelanggan.copy(
                            status = "Aktif",
                            dualApprovalInfo = updatedDualInfo,
                            pinjamanKe = pelanggan.pinjamanKe - 1,
                            catatanApproval = "Pengajuan top-up ditolak: $alasan",
                            tanggalApproval = tanggalSekarang,
                            disetujuiOleh = pimpinanName,
                            sisaUtangLamaSebelumTopUp = 0,
                            totalPelunasanLamaSebelumTopUp = 0
                        )
                    }

                    database.child("pelanggan").child(pelanggan.adminUid)
                        .child(pelangganId).setValue(updatedPelanggan)
                        .addOnSuccessListener {
                            createAdminNotification(
                                adminUid = pelanggan.adminUid,
                                pelangganId = pelangganId,
                                pelangganNama = pelanggan.namaPanggilan,
                                alasanPenolakan = "Pengajuan top-up pinjaman ditolak. Nasabah kembali ke pinjaman sebelumnya. Alasan: $alasan",
                                pimpinanName = pimpinanName,
                                type = "REJECTION"
                            )

                            if (index != -1) {
                                daftarPelanggan[index] = updatedPelanggan
                            }
                            safeUpdatePelanggan(updatedPelanggan)
                            simpanKeLokal()
                            loadDashboardData()

                            currentUserCabang.value?.let { cabangId ->
                                loadPendingApprovalsOptimized(cabangId)
                            }

                            hapusDariPengajuanApproval(pelangganId)

                            Log.d("Rejection", "✅ Top-up ditolak (FINAL): ${pelanggan.namaPanggilan}")
                            markRelatedNotificationsAsRead(pelangganId)
                            onSuccess?.invoke()
                        }
                        .addOnFailureListener { e ->
                            Log.e("Rejection", "❌ Gagal: ${e.message}")
                            onFailure?.invoke(e)
                        }

                } else {
                    // ==========================================
                    // KASUS 2: PENGAJUAN BARU DITOLAK (pinjamanKe <= 1)
                    // ==========================================
                    val totalBayar = pelanggan.pembayaranList.sumOf { it.jumlah }
                    val sisaUtang = pelanggan.totalPelunasan - totalBayar
                    val masihAdaUtang = sisaUtang > 0
                    val sudahPernahBayar = totalBayar > 0

                    if (sudahPernahBayar && masihAdaUtang) {
                        val updatedPelanggan = pelanggan.copy(
                            status = "Aktif",
                            dualApprovalInfo = updatedDualInfo,
                            catatanApproval = "Pengajuan ditolak, nasabah tetap aktif. Alasan: $alasan",
                            tanggalApproval = tanggalSekarang,
                            disetujuiOleh = pimpinanName
                        )

                        database.child("pelanggan").child(pelanggan.adminUid)
                            .child(pelangganId).setValue(updatedPelanggan)
                            .addOnSuccessListener {
                                createAdminNotification(
                                    adminUid = pelanggan.adminUid,
                                    pelangganId = pelangganId,
                                    pelangganNama = pelanggan.namaPanggilan,
                                    alasanPenolakan = alasan,
                                    pimpinanName = pimpinanName,
                                    type = "REJECTION"
                                )

                                if (index != -1) {
                                    daftarPelanggan[index] = updatedPelanggan
                                }
                                simpanKeLokal()
                                loadDashboardData()

                                currentUserCabang.value?.let { cabangId ->
                                    loadPendingApprovalsOptimized(cabangId)
                                }

                                hapusDariPengajuanApproval(pelangganId)
                                Log.d("Rejection", "✅ Pengajuan ditolak (FINAL), tetap aktif: ${pelanggan.namaPanggilan}")
                                onSuccess?.invoke()
                            }
                            .addOnFailureListener { e ->
                                onFailure?.invoke(e)
                            }
                    } else {
                        processRejectionForNewCustomer(pelanggan, pelangganId, alasan, pimpinanUid, pimpinanName, index, onSuccess, onFailure)
                    }
                }
            } catch (e: Exception) {
                Log.e("Rejection", "❌ Gagal reject: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    private fun hapusDariPengajuanApproval(pelangganId: String, targetCabangId: String? = null) {
        val cabangId = targetCabangId ?: currentUserCabang.value ?: return

        database.child("pengajuan_approval").child(cabangId)
            .orderByChild("pelangganId")
            .equalTo(pelangganId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        snapshot.children.forEach { child ->
                            child.ref.removeValue()
                                .addOnSuccessListener {
                                    Log.d("Rejection", "✅ Pengajuan dihapus dari pengajuan_approval (Cabang: $cabangId)")
                                }
                                .addOnFailureListener { e ->
                                    Log.e("Rejection", "❌ Gagal hapus pengajuan: ${e.message}")
                                }
                        }
                    } else {
                        Log.d("Rejection", "⚠️ Node pengajuan tidak ditemukan untuk dihapus di cabang: $cabangId")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("Rejection", "❌ Error: ${error.message}")
                }
            })
    }

    private fun safeRemovePelanggan(pelangganId: String) {
        val iterator = daftarPelanggan.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().id == pelangganId) {
                iterator.remove()
                break
            }
        }
    }

    // ✅ FUNGSI BARU: Safe update pelanggan
    private fun safeUpdatePelanggan(updatedPelanggan: Pelanggan) {
        val index = daftarPelanggan.indexOfFirst { it.id == updatedPelanggan.id }
        if (index != -1) {
            daftarPelanggan[index] = updatedPelanggan
        }
    }

    private fun processRejectionForNewCustomer(
        pelanggan: Pelanggan,
        pelangganId: String,
        alasan: String,
        pimpinanUid: String,
        pimpinanName: String,
        index: Int,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        val rejectedData = PelangganDitolak(
            pelanggan = pelanggan.copy(status = "Ditolak"),
            alasanPenolakan = alasan,
            tanggalPenolakan = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).format(Date()),
            ditolakOleh = pimpinanUid
        )

        // Simpan ke collection rejected
        database.child("pelanggan_ditolak").child(pelanggan.adminUid)
            .child(pelangganId).setValue(rejectedData)
            .addOnSuccessListener {
                // Hapus dari collection aktif
                database.child("pelanggan").child(pelanggan.adminUid)
                    .child(pelangganId).removeValue()
                    .addOnSuccessListener {
                        createAdminNotification(
                            adminUid = pelanggan.adminUid,
                            pelangganId = pelangganId,
                            pelangganNama = pelanggan.namaPanggilan,
                            alasanPenolakan = alasan,
                            pimpinanName = pimpinanName,
                            type = "REJECTION"
                        )

                        // ✅ PERBAIKAN: Gunakan removeAll dengan predicate untuk menghindari index issues
                        daftarPelanggan.removeAll { it.id == pelangganId }
                        safeRemovePelanggan(pelangganId)
                        simpanKeLokal()
                        loadDashboardData()
                        currentUserCabang.value?.let { cabangId ->
                            loadPendingApprovalsOptimized(cabangId)
                        }

                        Log.d("Approval", "✅ Nasabah baru ditolak dan dipindahkan ke pelanggan_ditolak: ${pelanggan.namaPanggilan}")
                        onSuccess?.invoke()
                    }
                    .addOnFailureListener { e ->
                        Log.e("Approval", "❌ Gagal menghapus pelanggan dari Firebase: ${e.message}")
                        onFailure?.invoke(e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("Approval", "❌ Gagal menyimpan ke pelanggan_ditolak: ${e.message}")
                onFailure?.invoke(e)
            }
    }

    private fun extractDataSebelumTopUp(catatanPerubahanPinjaman: String): Pelanggan? {
        return try {
            if (catatanPerubahanPinjaman.contains("DATA_SEBELUM_TOPUP:")) {
                val dataMap = mutableMapOf<String, String>()

                // Parse semua data yang disimpan
                val pattern = """(\w+)=([^,]+)""".toRegex()
                val matches = pattern.findAll(catatanPerubahanPinjaman)

                matches.forEach { matchResult ->
                    val key = matchResult.groupValues[1]
                    val value = matchResult.groupValues[2]
                    dataMap[key] = value
                }

                Log.d("ExtractData", "🔍 Data yang di-extract: $dataMap")

                // Buat objek Pelanggan dengan data yang di-extract
                Pelanggan(
                    pinjamanKe = dataMap["pinjamanKeLama"]?.toIntOrNull() ?: dataMap["pinjamanKe"]?.toIntOrNull() ?: 1,
                    besarPinjaman = dataMap["besarPinjamanLama"]?.toIntOrNull() ?: dataMap["besarPinjaman"]?.toIntOrNull() ?: 0,
                    admin = dataMap["adminLama"]?.toIntOrNull() ?: dataMap["admin"]?.toIntOrNull() ?: 0,
                    simpanan = dataMap["simpananLama"]?.toIntOrNull() ?: dataMap["simpanan"]?.toIntOrNull() ?: 0,
                    totalDiterima = dataMap["totalDiterimaLama"]?.toIntOrNull() ?: dataMap["totalDiterima"]?.toIntOrNull() ?: 0,
                    totalPelunasan = dataMap["totalPelunasanLama"]?.toIntOrNull() ?: dataMap["totalPelunasan"]?.toIntOrNull() ?: 0,
                    tenor = dataMap["tenorLama"]?.toIntOrNull() ?: dataMap["tenor"]?.toIntOrNull() ?: 30,
                    tanggalPengajuan = dataMap["tanggalPengajuanLama"] ?: dataMap["tanggalPengajuan"] ?: ""
                )
            } else {
                Log.d("ExtractData", "❌ Format catatanPerubahanPinjaman tidak sesuai")
                null
            }
        } catch (e: Exception) {
            Log.e("ExtractData", "❌ Gagal extract data sebelum top-up: ${e.message}")
            null
        }
    }

    fun generateCicilanKonsisten(tanggalPengajuan: String, tenor: Int, totalPelunasan: Int): List<SimulasiCicilan> {
        val simulasi = mutableListOf<SimulasiCicilan>()
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        // LOGIKA SAMA DENGAN KALKULATOR
        val cicilanPerHari = totalPelunasan / tenor
        val sisa = totalPelunasan % tenor

        // Pembulatan ke ratusan
        val cicilanPerHariBulat = (cicilanPerHari / 100.0).roundToInt() * 100

        // Hitung total dari angsuran yang sudah dibulatkan
        val totalDariAngsuran = cicilanPerHariBulat * (tenor - 1)
        val cicilanTerakhir = totalPelunasan - totalDariAngsuran

        val calendar = HolidayUtils.parseTanggal(tanggalPengajuan)
        var currentDate = calendar.clone() as Calendar
        var hariCount = 0

        while (hariCount < tenor) {
            currentDate.add(Calendar.DAY_OF_YEAR, 1)

            val isHariIniKerja = HolidayUtils.isHariKerja(currentDate)
            val tanggal = dateFormat.format(currentDate.time)

            if (isHariIniKerja) {
                hariCount++

                val jumlahHariIni = if (hariCount == tenor) {
                    cicilanTerakhir
                } else {
                    cicilanPerHariBulat
                }

                simulasi.add(SimulasiCicilan(
                    tanggal = tanggal,
                    jumlah = jumlahHariIni,
                    isHariKerja = true,
                    isCompleted = false,
                    version = 2,
                    lastUpdated = currentTime
                ))
            }
        }

        // Validasi total
        val totalSimulasi = simulasi.sumOf { it.jumlah }
        if (totalSimulasi != totalPelunasan && simulasi.isNotEmpty()) {
            val selisih = totalPelunasan - totalSimulasi
            val lastIndex = simulasi.size - 1
            simulasi[lastIndex] = simulasi[lastIndex].copy(jumlah = simulasi[lastIndex].jumlah + selisih)
        }

        Log.d("CicilanCalculator", "✅ Generated ${simulasi.size} cicilan untuk tenor $tenor hari, Total: ${simulasi.sumOf { it.jumlah }}")
        return simulasi
    }

    private fun simpanKeLokal() {
        viewModelScope.launch {
            try {
                val adminUid = Firebase.auth.currentUser?.uid
                if (adminUid.isNullOrBlank()) return@launch
                // convert daftarPelanggan (SnapshotStateList) menjadi List<Pelanggan>
                val copyList = daftarPelanggan.toList()
                LocalStorage.simpanDataPelanggan(getApplication(), copyList, adminUid)
            } catch (e: Exception) {
                Log.e("PelangganVM", "Simpan lokal gagal: ${e.message}")
            }
        }
    }

    private fun createAdminNotification(
        adminUid: String,
        pelangganId: String,
        pelangganNama: String,
        alasanPenolakan: String,
        pimpinanName: String,
        type: String = "REJECTION",
        catatanPersetujuan: String = "",
        pinjamanDiajukan: Int = 0,
        pinjamanDisetujui: Int = 0,
        tenorDiajukan: Int = 0,
        tenorDisetujui: Int = 0,
        isPinjamanDiubah: Boolean = false,
        catatanPerubahanPinjaman: String = ""

    ) {
        try {
            val validType = if (type.isBlank()) "REJECTION" else type
            val notificationId = "${validType.lowercase()}_${pelangganId}_${System.currentTimeMillis()}"

            val title = when (validType) {
                "APPROVAL" -> if (isPinjamanDiubah) "Pengajuan Disetujui dengan Penyesuaian" else "Pengajuan Disetujui"
                "DUAL_APPROVAL_APPROVED" -> "Pengajuan Disetujui"
                "DUAL_APPROVAL_REJECTED" -> "Pengajuan Ditolak"
                "TOPUP_APPROVAL" -> "Pengajuan Top-Up Pinjaman"
                else -> "Pengajuan Ditolak"
            }

            val message = when (validType) {
                "APPROVAL" -> {
                    if (isPinjamanDiubah) {
                        "Pengajuan $pelangganNama disetujui dengan penyesuaian oleh $pimpinanName"
                    } else {
                        "Pengajuan $pelangganNama telah disetujui oleh $pimpinanName"
                    }
                }
                "DUAL_APPROVAL_APPROVED" -> {
                    if (isPinjamanDiubah) {
                        "Pengajuan $pelangganNama disetujui dengan penyesuaian oleh $pimpinanName. Silakan proses pencairan."
                    } else {
                        "Pengajuan $pelangganNama telah DISETUJUI oleh $pimpinanName. Silakan proses pencairan."
                    }
                }
                "DUAL_APPROVAL_REJECTED" -> "Pengajuan $pelangganNama DITOLAK. Alasan: $alasanPenolakan"
                "TOPUP_APPROVAL" -> "Pengajuan top-up pinjaman untuk $pelangganNama menunggu approval"
                else -> "Pengajuan $pelangganNama ditolak oleh $pimpinanName: $alasanPenolakan"
            }

            val notification = AdminNotification(
                id = notificationId,
                type = validType,
                title = title,
                message = message,
                pelangganId = pelangganId,
                pelangganNama = pelangganNama,
                alasanPenolakan = alasanPenolakan,
                catatanPersetujuan = catatanPersetujuan,
                pinjamanDiajukan = pinjamanDiajukan,
                pinjamanDisetujui = pinjamanDisetujui,
                tenorDiajukan = tenorDiajukan,
                tenorDisetujui = tenorDisetujui,
                isPinjamanDiubah = isPinjamanDiubah,
                catatanPerubahanPinjaman = catatanPerubahanPinjaman,
                disetujuiOleh = pimpinanName,
                timestamp = System.currentTimeMillis(),
                read = false
            )

            Log.d("Notification", "🔄 Mencoba menyimpan notifikasi ke: /admin_notifications/$adminUid/$notificationId")

            // Simpan ke Firebase
            database.child("admin_notifications").child(adminUid)
                .child(notificationId).setValue(notification)
                .addOnSuccessListener {
                    Log.d("Notification", "✅ Notifikasi $validType berhasil dikirim ke admin: $adminUid")
                    Log.d("Notification", "   📝 Judul: $title")
                    Log.d("Notification", "   📝 Pesan: $message")
                }
                .addOnFailureListener { e ->
                    Log.e("Notification", "❌ Gagal kirim notifikasi: ${e.message}")
                    Log.e("Notification", "❌ Path: /admin_notifications/$adminUid/$notificationId")

                    // Coba simpan ke lokal sebagai fallback
                    simpanNotifikasiLokal(notification, adminUid)
                }
        } catch (e: Exception) {
            Log.e("Notification", "❌ Error createAdminNotification: ${e.message}")
        }
    }

    private fun simpanNotifikasiLokal(notification: AdminNotification, adminUid: String) {
        // Fallback: simpan ke SharedPreferences atau database lokal
        Log.w("Notification", "⚠️ Mode fallback: Simpan notifikasi ke lokal")
        // Implementasi penyimpanan lokal bisa ditambahkan di sini
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // API 23 ke atas
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            // API 21–22
            @Suppress("DEPRECATION")
            val nwInfo = cm.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            nwInfo.isConnected
        }
    }

    // =========================================================================
    // DEVICE PRESENCE - untuk cek apakah HP admin online
    // =========================================================================
    fun startDevicePresence() {
        val uid = Firebase.auth.currentUser?.uid ?: return
        val presenceRef = database.child("device_presence").child(uid)
        val connectedRef = FirebaseDatabase.getInstance("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").getReference(".info/connected")

        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    presenceRef.child("online").setValue(true)
                    presenceRef.child("lastSeen").setValue(ServerValue.TIMESTAMP)
                    presenceRef.child("online").onDisconnect().setValue(false)
                    presenceRef.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("Presence", "Error: ${error.message}")
            }
        })
    }

    private suspend fun isAdminDeviceOnline(adminUid: String): Boolean {
        return try {
            val snap = withTimeoutOrNull(5000L) {
                database.child("device_presence").child(adminUid).get().await()
            } ?: return false
            val online = snap.child("online").getValue(Boolean::class.java) ?: false
            val lastSeen = snap.child("lastSeen").getValue(Long::class.java) ?: 0L
            // Dianggap online jika flag true DAN lastSeen dalam 2 menit terakhir
            online && (System.currentTimeMillis() - lastSeen < 120_000)
        } catch (e: Exception) {
            Log.e("Presence", "Error checking admin online: ${e.message}")
            false
        }
    }

    // =========================================================================
    // REMOTE TAKEOVER - SISI PIMPINAN (Initiator)
    // =========================================================================

    /**
     * Langkah 1: Pimpinan memulai takeover
     */
    fun initiateRemoteTakeover(
        targetAdminUid: String,
        targetAdminName: String,
        onTokenReady: (token: String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                // Cek pimpinan online
                if (!isOnline()) {
                    onError("Handphone Anda tidak terhubung ke internet.")
                    return@launch
                }

                _takeoverStatus.value = TakeoverStatus.CheckingOnline

                val pimpinanUid = Firebase.auth.currentUser?.uid ?: return@launch

                // Cek session lock oleh pimpinan LAIN
                val existingLock = withTimeoutOrNull(5000L) {
                    database.child("session_lock").child(targetAdminUid).get().await()
                }
                if (existingLock != null && existingLock.exists()) {
                    val lockedByUid = existingLock.child("lockedBy").getValue(String::class.java)
                    if (lockedByUid != pimpinanUid) {
                        // Di-lock oleh pimpinan lain → blokir
                        val lockedByName = existingLock.child("pimpinanName").getValue(String::class.java) ?: "Pimpinan lain"
                        _takeoverStatus.value = TakeoverStatus.Error("Akun admin sedang digunakan oleh $lockedByName")
                        onError("Akun admin sedang digunakan oleh $lockedByName")
                        return@launch
                    }
                    // Lock dari pimpinan ini sendiri → izinkan re-takeover
                    Log.d("Takeover", "🔄 Re-takeover: session lock milik pimpinan yang sama")
                }

                // Cek apakah admin online
                val adminOnline = isAdminDeviceOnline(targetAdminUid)

                if (adminOnline) {
                    // ✅ Admin ONLINE → sync data dulu sebelum takeover (alur normal)
                    _takeoverStatus.value = TakeoverStatus.WaitingSync

                    val pimpinanName = try {
                        val snap = database.child("metadata/admins/$pimpinanUid/name").get().await()
                        snap.getValue(String::class.java) ?: "Pimpinan"
                    } catch (_: Exception) { "Pimpinan" }

                    database.child("remote_takeover").child(targetAdminUid).setValue(
                        mapOf(
                            "pimpinanUid" to pimpinanUid,
                            "pimpinanName" to pimpinanName,
                            "status" to "pending",
                            "timestamp" to ServerValue.TIMESTAMP
                        )
                    ).await()

                    Log.d("Takeover", "📡 Sinyal takeover terkirim ke $targetAdminUid")

                    val synced = waitForTakeoverSync(targetAdminUid, timeoutMs = 60_000)
                    if (!synced) {
                        database.child("remote_takeover").child(targetAdminUid).removeValue()
                        _takeoverStatus.value = TakeoverStatus.Error("Timeout: Admin tidak merespons.")
                        onError("Timeout: Admin tidak merespons. Pastikan aplikasi admin aktif dan terhubung internet.")
                        return@launch
                    }
                } else {
                    // ✅ Admin OFFLINE → skip sync, langsung takeover
                    // Aman karena admin tidak sedang menggunakan app,
                    // jadi tidak ada data lokal baru yang belum ter-sync
                    Log.d("Takeover", "📡 Admin offline → skip sync, direct takeover")

                    // ✅ Force logout admin jika app masih berjalan di background.
                    // Memanfaatkan mekanisme force_logout yang sudah ada.
                    // - App di background → startForceLogoutListener aktif → auto logout
                    // - App mati lalu dibuka → checkForceLogoutOnStartup() → auto logout
                    database.child("force_logout").child(targetAdminUid).setValue(
                        mapOf("timestamp" to ServerValue.TIMESTAMP)
                    ).await()
                    Log.d("Takeover", "🔒 Force logout node written for offline admin")
                }

                _takeoverStatus.value = TakeoverStatus.SigningIn

                // Panggil Cloud Function untuk dapat token
                val functions = com.google.firebase.functions.FirebaseFunctions.getInstance("asia-southeast1")
                val result = functions
                    .getHttpsCallable("generateTakeoverToken")
                    .call(hashMapOf("targetAdminUid" to targetAdminUid))
                    .await()

                val resultData = result.data as? Map<*, *>
                val token = resultData?.get("token") as? String
                val adminName = resultData?.get("adminName") as? String ?: targetAdminName

                if (token.isNullOrBlank()) {
                    _takeoverStatus.value = TakeoverStatus.Error("Gagal mendapatkan token.")
                    onError("Gagal mendapatkan token dari server.")
                    return@launch
                }

                // Simpan info pimpinan untuk restore nanti
                saveTakeoverSession(pimpinanUid, targetAdminUid, adminName)
                _takeoverAdminName.value = adminName

                onTokenReady(token)

            } catch (e: Exception) {
                Log.e("Takeover", "Error: ${e.message}", e)
                _takeoverStatus.value = TakeoverStatus.Error("Gagal: ${e.message}")
                onError("Gagal memulai takeover: ${e.message}")
            }
        }
    }

    /**
     * Menunggu admin sync data (polling status di RTDB)
     */
    private suspend fun waitForTakeoverSync(adminUid: String, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val snap = database.child("remote_takeover").child(adminUid)
                    .child("status").get().await()
                val status = snap.getValue(String::class.java)
                if (status == "synced") return true
                if (status == "error") return false
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(2000) // Poll setiap 2 detik
        }
        return false
    }

    /**
     * Setelah dapat token, sign in sebagai admin
     */
    fun performTakeoverSignIn(
        token: String,
        navController: androidx.navigation.NavController,
        context: Context
    ) {
        viewModelScope.launch {
            try {
                // Bersihkan state pimpinan
                clearAllCaches()
                cleanupRoleListeners()
                stopForceLogoutListener()

                // Sign out pimpinan
                Firebase.auth.signOut()

                // Sign in sebagai admin dengan custom token
                Firebase.auth.signInWithCustomToken(token).await()

                val newUid = Firebase.auth.currentUser?.uid
                Log.d("Takeover", "✅ Signed in as admin: $newUid")

                // Set role ke ADMIN_LAPANGAN
                saveUserRole(context, UserRole.ADMIN_LAPANGAN)
                _currentUserRole.value = UserRole.ADMIN_LAPANGAN
                _isTakeoverMode.value = true
                _takeoverStatus.value = TakeoverStatus.Active

                // Reset init flag agar data admin bisa di-load
                isRoleInitStarted = false
                startRoleDetectionAndInit()

                // Simpan waktu login
                context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_login_timestamp", System.currentTimeMillis())
                    .apply()

                // Navigasi ke dashboard admin
                navController.navigate("dashboard") {
                    popUpTo(0) { inclusive = true }
                }

            } catch (e: Exception) {
                Log.e("Takeover", "Sign in error: ${e.message}", e)
                _takeoverStatus.value = TakeoverStatus.Error("Gagal masuk: ${e.message}")
            }
        }
    }

    /**
     * Simpan info takeover ke SharedPreferences
     */
    private fun saveTakeoverSession(pimpinanUid: String, adminUid: String, adminName: String) {
        context.getSharedPreferences("takeover_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("original_pimpinan_uid", pimpinanUid)
            .putString("takeover_admin_uid", adminUid)
            .putString("takeover_admin_name", adminName)
            .putBoolean("is_takeover_active", true)
            .apply()
    }

    /**
     * Cek apakah saat ini dalam mode takeover (dipanggil saat app start)
     */
    fun checkTakeoverMode() {
        val prefs = context.getSharedPreferences("takeover_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("is_takeover_active", false)
        _isTakeoverMode.value = isActive
        if (isActive) {
            _takeoverAdminName.value = prefs.getString("takeover_admin_name", null)
            _takeoverStatus.value = TakeoverStatus.Active
        }
    }

    /**
     * Pimpinan selesai, kembali ke akun sendiri
     */
    fun returnToPimpinanAccount(
        navController: androidx.navigation.NavController,
        context: Context,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                if (!isOnline()) {
                    onError("Tidak ada koneksi internet. Sync data dulu sebelum kembali.")
                    return@launch
                }

                _takeoverStatus.value = TakeoverStatus.Restoring

                val prefs = context.getSharedPreferences("takeover_prefs", Context.MODE_PRIVATE)
                val pimpinanUid = prefs.getString("original_pimpinan_uid", null)
                val adminUid = prefs.getString("takeover_admin_uid", null)

                if (pimpinanUid == null || adminUid == null) {
                    onError("Data sesi takeover tidak ditemukan.")
                    return@launch
                }

                // Sync data admin ke Firebase dulu
                Log.d("Takeover", "🔄 Syncing admin data before returning...")
                try {
                    val syncResult = offlineRepo.syncNow()
                    Log.d("Takeover", "Sync result: ${syncResult.success}/${syncResult.total}")
                } catch (e: Exception) {
                    Log.w("Takeover", "Sync warning: ${e.message}")
                }

                // Panggil Cloud Function untuk dapat token pimpinan
                val functions = com.google.firebase.functions.FirebaseFunctions.getInstance("asia-southeast1")
                val result = functions
                    .getHttpsCallable("restorePimpinanSession")
                    .call(hashMapOf(
                        "pimpinanUid" to pimpinanUid,
                        "adminUid" to adminUid
                    ))
                    .await()

                val resultData = result.data as? Map<*, *>
                val token = resultData?.get("token") as? String

                if (token.isNullOrBlank()) {
                    onError("Gagal mendapatkan token pemulihan.")
                    return@launch
                }

                // Bersihkan state admin
                clearAllCaches()
                cleanupRoleListeners()

                // Sign out dari admin
                Firebase.auth.signOut()

                // Sign in kembali sebagai pimpinan
                Firebase.auth.signInWithCustomToken(token).await()
                Log.d("Takeover", "✅ Kembali sebagai Pimpinan: ${Firebase.auth.currentUser?.uid}")

                // Restore role
                saveUserRole(context, UserRole.PIMPINAN)
                _currentUserRole.value = UserRole.PIMPINAN
                _isTakeoverMode.value = false
                _takeoverAdminName.value = null
                _takeoverStatus.value = TakeoverStatus.Idle

                // Hapus prefs takeover
                prefs.edit().clear().apply()

                // Reset init flag
                isRoleInitStarted = false
                startRoleDetectionAndInit()

                // Simpan waktu login
                context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_login_timestamp", System.currentTimeMillis())
                    .apply()

                // Navigasi ke pimpinan dashboard
                navController.navigate("pimpinan_dashboard") {
                    popUpTo(0) { inclusive = true }
                }

            } catch (e: Exception) {
                Log.e("Takeover", "Restore error: ${e.message}", e)
                _takeoverStatus.value = TakeoverStatus.Error("Gagal kembali: ${e.message}")
                onError("Gagal kembali ke akun pimpinan: ${e.message}")
            }
        }
    }

    // =========================================================================
    // REMOTE TAKEOVER - SISI ADMIN (Listener)
    // =========================================================================

    /**
     * Admin lapangan mendengarkan sinyal takeover.
     * Dipanggil saat login sebagai ADMIN_LAPANGAN.
     */
    fun startRemoteTakeoverListener(
        onForceLogout: () -> Unit
    ) {
        val uid = Firebase.auth.currentUser?.uid ?: return

        // Jangan pasang listener jika sedang dalam mode takeover
        if (_isTakeoverMode.value) return

        stopRemoteTakeoverListener()

        remoteTakeoverRef = database.child("remote_takeover").child(uid)
        remoteTakeoverListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val status = snapshot.child("status").getValue(String::class.java) ?: return

                if (status == "pending") {
                    Log.d("Takeover", "📡 Takeover signal received! Starting sync...")

                    // Update status ke syncing
                    snapshot.ref.child("status").setValue("syncing")

                    // Lakukan sync data ke Firebase
                    viewModelScope.launch {
                        try {
                            // Sync semua pending operations
                            val syncResult = offlineRepo.syncNow()
                            Log.d("Takeover", "✅ Sync done: ${syncResult.success}/${syncResult.total}")

                            // Juga sync via SmartFirebaseLoader jika ada data
                            try {
                                smartLoader.syncOfflineData(uid)
                            } catch (_: Exception) {}

                            // Update status ke synced
                            snapshot.ref.child("status").setValue("synced")
                            Log.d("Takeover", "✅ Status updated to 'synced'")

                            // Tunggu sebentar agar pimpinan bisa baca status
                            kotlinx.coroutines.delay(1500)

                            // Force logout admin
                            onForceLogout()

                        } catch (e: Exception) {
                            Log.e("Takeover", "❌ Sync error: ${e.message}")
                            snapshot.ref.child("status").setValue("error")
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Takeover", "Listener cancelled: ${error.message}")
            }
        }

        remoteTakeoverRef?.addValueEventListener(remoteTakeoverListener!!)
        Log.d("Takeover", "👀 Listening for remote takeover: $uid")
    }

    fun stopRemoteTakeoverListener() {
        remoteTakeoverListener?.let { listener ->
            remoteTakeoverRef?.removeEventListener(listener)
        }
        remoteTakeoverListener = null
        remoteTakeoverRef = null
    }

    // =========================================================================
    // SESSION LOCK CHECK (untuk AuthScreen)
    // =========================================================================

    /**
     * Cek apakah akun admin sedang di-lock oleh pimpinan
     * Dipanggil sebelum admin login
     */
    suspend fun checkSessionLock(adminUid: String): String? {
        return try {
            val snap = withTimeoutOrNull(5000L) {
                database.child("session_lock").child(adminUid).get().await()
            } ?: return null

            if (snap.exists()) {
                snap.child("pimpinanName").getValue(String::class.java) ?: "Pimpinan"
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SessionLock", "Error checking: ${e.message}")
            null
        }
    }

    fun getPelangganById(pelangganId: String): Pelanggan? {
        return daftarPelanggan.firstOrNull { it.id == pelangganId }
    }

    // Tambahkan di PelangganViewModel.kt
    fun getTanggalPelunasan(pelanggan: Pelanggan): String {
        val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
            pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
        }

        // Cek apakah sudah lunas
        if (totalBayar < pelanggan.totalPelunasan.toLong()) {
            return "Belum Lunas"
        }

        var tanggalTerakhir = ""

        pelanggan.pembayaranList.forEach { pembayaran ->
            // Cek pembayaran utama
            if (pembayaran.tanggal.isNotBlank()) {
                if (tanggalTerakhir.isBlank() || isTanggalLebihBaru(pembayaran.tanggal, tanggalTerakhir)) {
                    tanggalTerakhir = pembayaran.tanggal
                }
            }

            // Cek sub pembayaran
            pembayaran.subPembayaran.forEach { sub ->
                if (sub.tanggal.isNotBlank()) {
                    if (tanggalTerakhir.isBlank() || isTanggalLebihBaru(sub.tanggal, tanggalTerakhir)) {
                        tanggalTerakhir = sub.tanggal
                    }
                }
            }
        }

        return tanggalTerakhir.ifBlank { "Tanggal tidak tersedia" }
    }

    private fun isTanggalLebihBaru(tanggal1: String, tanggal2: String): Boolean {
        return try {
            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
            val date1 = dateFormat.parse(tanggal1)
            val date2 = dateFormat.parse(tanggal2)
            date1.after(date2)
        } catch (e: Exception) {
            false
        }
    }

    fun validatePelangganDataEdit(
        namaKtp: String,
        nik: String,
        nomorAnggota: String
    ): Boolean {
        return namaKtp.isNotBlank() &&
                nik.isNotBlank() &&
                nomorAnggota.isNotBlank() &&
                nomorAnggota.length == 6 // ✅ Validasi 6 digit
    }

    fun updatePelangganDataEdit(
        pelangganId: String,
        namaKtp: String,
        nik: String,
        namaPanggilan: String,
        nomorAnggota: String,
        alamatKtp: String,
        alamatRumah: String,
        detailRumah: String,
        wilayah: String,
        noHp: String,
        jenisUsaha: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                val existingPelanggan = getPelangganById(pelangganId)
                if (existingPelanggan == null) {
                    onFailure?.invoke(Exception("Nasabah tidak ditemukan"))
                    return@launch
                }

                // Validasi data - HANYA NAMA KTP DAN NIK
                if (!validatePelangganDataEdit(namaKtp, nik, nomorAnggota)) {
                    onFailure?.invoke(Exception("Nama KTP, NIK dan Nomor Anggota wajib diisi (6 digit)"))
                    return@launch
                }

                // ✅ PERBAIKAN: Hanya update data pribadi, tidak mengubah data pinjaman
                val updatedPelanggan = existingPelanggan.copy(
                    namaKtp = namaKtp,
                    nik = nik,
                    namaPanggilan = namaPanggilan,
                    nomorAnggota = nomorAnggota,
                    alamatKtp = alamatKtp,
                    alamatRumah = alamatRumah,
                    detailRumah = detailRumah,
                    wilayah = wilayah,
                    noHp = noHp,
                    jenisUsaha = jenisUsaha
                    // Data pinjaman (besarPinjaman, tenor, dll) tetap menggunakan nilai existing
                )

                // Update di Firebase dan lokal
                updatePelanggan(updatedPelanggan)

                Log.d("EditPelanggan", "✅ Berhasil update data pribadi pelanggan: ${updatedPelanggan.namaPanggilan}, Nomor Anggota: $nomorAnggota")
                onSuccess?.invoke()

            } catch (e: Exception) {
                Log.e("EditPelanggan", "❌ Gagal update data pribadi pelanggan: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    fun getPelangganMacet(): List<Pelanggan> {
        return daftarPelanggan.filter { pelanggan ->
            isPelangganMacet(pelanggan)
        }
    }

    fun getJumlahPelangganMacet(): Int {
        return getPelangganMacet().size
    }

    private fun isPelangganMacet(pelanggan: Pelanggan): Boolean {
        try {
            val totalDibayar = pelanggan.pembayaranList.sumOf { it.jumlah }
            if (totalDibayar >= pelanggan.totalPelunasan) {
                return false
            }

            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
            val sekarang = Calendar.getInstance()
            val tanggalPengajuan = dateFormat.parse(pelanggan.tanggalPengajuan)

            // Hitung berbagai metrik
            val bulanBerjalan = hitungSelisihBulan(tanggalPengajuan, sekarang.time)
            val mingguTanpaBayar = hitungMingguTanpaPembayaran(pelanggan)
            val polaBolong = deteksiPembayaranBolong(pelanggan)
            val tenorBulan = pelanggan.tenor / 30
            val progressPembayaran = totalDibayar.toFloat() / pelanggan.totalPelunasan

            Log.d("PelangganMacet", "Analisis ${pelanggan.namaPanggilan}: " +
                    "Bulan: $bulanBerjalan, " +
                    "Minggu tanpa bayar: $mingguTanpaBayar, " +
                    "Pola bolong: $polaBolong, " +
                    "Progress: ${(progressPembayaran * 100).toInt()}%")

            val macetBerdasarkanMinggu = mingguTanpaBayar in 1..4
            val macetBerdasarkanBulan = bulanBerjalan in 3..12 && progressPembayaran < 0.95f
            val macetPolaBolong = polaBolong
            val sudahMelewatiTenor = bulanBerjalan > (tenorBulan + 1)
            val progressSangatLambat = bulanBerjalan >= 4 && progressPembayaran < 0.4f
            val isMacet = macetBerdasarkanMinggu ||
                    macetBerdasarkanBulan ||
                    macetPolaBolong ||
                    (sudahMelewatiTenor && totalDibayar < pelanggan.totalPelunasan) ||
                    progressSangatLambat

            if (isMacet) {
                Log.d("PelangganMacet", "✅ MACET: ${pelanggan.namaPanggilan} - " +
                        "Minggu: $mingguTanpaBayar, " +
                        "Bulan: $bulanBerjalan, " +
                        "Pola: $polaBolong")
            }

            return isMacet

        } catch (e: Exception) {
            Log.e("PelangganMacet", "Error checking pelanggan macet: ${e.message}")
            return false
        }
    }

    fun getDetailStatusMacet(pelanggan: Pelanggan): String {
        return try {
            val totalDibayar = pelanggan.pembayaranList.sumOf { it.jumlah }
            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
            val sekarang = Calendar.getInstance()
            val tanggalPengajuan = dateFormat.parse(pelanggan.tanggalPengajuan)

            val bulanBerjalan = hitungSelisihBulan(tanggalPengajuan, sekarang.time)
            val mingguTanpaBayar = hitungMingguTanpaPembayaran(pelanggan)
            val polaBolong = deteksiPembayaranBolong(pelanggan)
            val progressPembayaran = totalDibayar.toFloat() / pelanggan.totalPelunasan

            // Prioritaskan berdasarkan urgency
            return when {
                mingguTanpaBayar >= 4 -> "Tidak bayar 4+ minggu"
                mingguTanpaBayar >= 3 -> "Tidak bayar 3 minggu"
                mingguTanpaBayar >= 2 -> "Tidak bayar 2 minggu"
                mingguTanpaBayar >= 1 -> "Tidak bayar 1 minggu"
                polaBolong -> "Pembayaran tidak konsisten"
                bulanBerjalan >= 12 && progressPembayaran < 0.8f -> "Belum lunas 12+ bulan"
                bulanBerjalan >= 9 && progressPembayaran < 0.7f -> "Belum lunas 9 bulan"
                bulanBerjalan >= 6 && progressPembayaran < 0.6f -> "Belum lunas 6 bulan"
                bulanBerjalan >= 4 && progressPembayaran < 0.5f -> "Belum lunas 4 bulan"
                bulanBerjalan >= 3 && progressPembayaran < 0.4f -> "Belum lunas 3 bulan"
                else -> "Dalam pantauan"
            }
        } catch (e: Exception) {
            "Error analisis"
        }
    }

    private fun hitungSelisihBulan(dari: Date, sampai: Date): Int {
        val calendarDari = Calendar.getInstance().apply { time = dari }
        val calendarSampai = Calendar.getInstance().apply { time = sampai }

        val tahunSelisih = calendarSampai.get(Calendar.YEAR) - calendarDari.get(Calendar.YEAR)
        val bulanSelisih = calendarSampai.get(Calendar.MONTH) - calendarDari.get(Calendar.MONTH)

        return (tahunSelisih * 12) + bulanSelisih
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH) &&
                cal1.get(Calendar.DAY_OF_MONTH) == cal2.get(Calendar.DAY_OF_MONTH)
    }

    fun normalizeWilayah(wilayah: String): String {
        return wilayah.trim().lowercase()
    }

    fun getWilayahOptions(pelangganList: List<Pelanggan>): List<String> {
        return listOf("Semua") +
                pelangganList.map { normalizeWilayah(it.wilayah) }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                    .map { formatWilayahDisplay(it) }
    }

    private fun formatWilayahDisplay(wilayah: String): String {
        return wilayah.split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else word
                }
            }
    }

    fun calculatePinjamanValues(pinjamanBaru: Int): PinjamanCalculation {
        val admin = (pinjamanBaru * 5) / 100
        val simpanan = (pinjamanBaru * 5) / 100
        val jasaPinjaman = (pinjamanBaru * 10) / 100
        val totalDiterima = pinjamanBaru - admin - simpanan
        val totalPelunasan = (pinjamanBaru * 120) / 100

        return PinjamanCalculation(
            admin = admin,
            simpanan = simpanan,
            jasaPinjaman = jasaPinjaman,
            totalDiterima = totalDiterima,
            totalPelunasan = totalPelunasan
        )
    }

    fun processTopUpLoanWithApproval(
        pelangganId: String,
        pinjamanBaru: Int,
        tenorBaru: Int,
        namaKtp: String,
        nik: String,
        alamatKtp: String,
        namaPanggilan: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                if (pelangganId.isBlank()) {
                    onFailure?.invoke(Exception("ID Nasabah tidak valid"))
                    return@launch
                }
                if (pinjamanBaru <= 0) {
                    onFailure?.invoke(Exception("Jumlah pinjaman harus lebih dari 0"))
                    return@launch
                }
                if (tenorBaru !in 24..60) {
                    onFailure?.invoke(Exception("Tenor harus antara 24-60 hari"))
                    return@launch
                }

                val existingPelanggan = getPelangganById(pelangganId)
                if (existingPelanggan == null) {
                    onFailure?.invoke(Exception("Nasabah tidak ditemukan"))
                    return@launch
                }

                val dataSebelumTopUp = existingPelanggan.copy()

                // ✅ Hitung sisa utang dengan data LAMA
                val totalBayarSebelumnya = existingPelanggan.pembayaranList.sumOf { pembayaran ->
                    pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
                }

                val totalPelunasanLama = existingPelanggan.totalPelunasan
                val sisaUtangLama = max(totalPelunasanLama - totalBayarSebelumnya, 0)
                val sudahLunas = sisaUtangLama <= 0

                // ✅ PERBAIKAN: Simpan pinjamanKe yang akan digunakan
                // pinjamanKe saat ini + 1 untuk pinjaman baru
                val pinjamanKeBaru = existingPelanggan.pinjamanKe + 1

                Log.d("KelolaKredit", "📊 PERHITUNGAN SEBELUM TOP-UP:")
                Log.d("KelolaKredit", "   PinjamanKe Lama: ${existingPelanggan.pinjamanKe}")
                Log.d("KelolaKredit", "   PinjamanKe Baru: $pinjamanKeBaru")
                Log.d("KelolaKredit", "   Total Pelunasan Lama: $totalPelunasanLama")
                Log.d("KelolaKredit", "   Total Bayar: $totalBayarSebelumnya")
                Log.d("KelolaKredit", "   Sisa Utang Lama: $sisaUtangLama")

                val calculation = calculatePinjamanValues(pinjamanBaru)
                val totalPelunasanBaru = calculation.totalPelunasan
                val totalSimpananLama = getTotalSimpananByNama(existingPelanggan.namaKtp)
                val totalSimpananBaru = if (existingPelanggan.statusPencairanSimpanan == "Dicairkan") {
                    calculation.simpanan
                } else {
                    totalSimpananLama + calculation.simpanan
                }
                val tanggalPengajuanBaru = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).format(Date())
                val cicilanBaru = generateCicilanKonsisten(tanggalPengajuanBaru, tenorBaru, totalPelunasanBaru)
                val totalDiterimaBaru = calculation.totalDiterima

                val updatedPelanggan = existingPelanggan.copy(
                    namaKtp = namaKtp,
                    nik = nik,
                    alamatKtp = alamatKtp,
                    namaPanggilan = namaPanggilan,

                    // ✅ PERBAIKAN KUNCI: Set pinjamanKe ke nilai BARU
                    // Ini akan membuat isTopUpApproval = true di approvePengajuan
                    pinjamanKe = pinjamanKeBaru,

                    besarPinjaman = pinjamanBaru,
                    besarPinjamanDiajukan = pinjamanBaru,
                    admin = calculation.admin,
                    simpanan = totalSimpananBaru,
                    jasaPinjaman = calculation.jasaPinjaman,
                    totalDiterima = totalDiterimaBaru,
                    totalPelunasan = totalPelunasanBaru,
                    tenor = tenorBaru,
                    tanggalPengajuan = tanggalPengajuanBaru,
                    status = "Menunggu Approval",
                    // === Reset Status Pencairan (pindah dari Menunggu Pencairan) ===
                    statusPencairanSimpanan = "",
                    tanggalLunasCicilan = "",
                    statusKhusus = "",
                    catatanStatusKhusus = "",
                    tanggalStatusKhusus = "",
                    diberiTandaOleh = "",
                    pembayaranList = emptyList(), // Selalu mulai fresh — riwayat lama disimpan di riwayatPembayaran/
                    hasilSimulasiCicilan = cicilanBaru,

                    // Simpan data untuk referensi
                    sisaUtangLamaSebelumTopUp = sisaUtangLama,
                    totalPelunasanLamaSebelumTopUp = totalPelunasanLama,

                    catatanPerubahanPinjaman = "DATA_SEBELUM_TOPUP:" +
                            "pinjamanKeLama=${dataSebelumTopUp.pinjamanKe}," +
                            "besarPinjamanLama=${dataSebelumTopUp.besarPinjaman}," +
                            "adminLama=${dataSebelumTopUp.admin}," +
                            "simpananLama=${dataSebelumTopUp.simpanan}," +
                            "totalDiterimaLama=${dataSebelumTopUp.totalDiterima}," +
                            "totalPelunasanLama=${dataSebelumTopUp.totalPelunasan}," +
                            "tenorLama=${dataSebelumTopUp.tenor}," +
                            "tanggalPengajuanLama=${dataSebelumTopUp.tanggalPengajuan}," +
                            "totalPembayaran=${totalBayarSebelumnya}," +
                            "sisaUtangLama=$sisaUtangLama," +
                            "isTopUpFromLunas=$sudahLunas"
                )

                Log.d("KelolaKredit", "📝 DATA YANG AKAN DISIMPAN:")
                Log.d("KelolaKredit", "   PinjamanKe: ${updatedPelanggan.pinjamanKe}")
                Log.d("KelolaKredit", "   BesarPinjaman: ${updatedPelanggan.besarPinjaman}")
                Log.d("KelolaKredit", "   TotalPelunasan: ${updatedPelanggan.totalPelunasan}")
                Log.d("KelolaKredit", "   TotalDiterima: ${updatedPelanggan.totalDiterima}")

                val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                if (index != -1) {
                    daftarPelanggan[index] = updatedPelanggan
                }

                // ========== SIMPAN RIWAYAT PEMBAYARAN LAMA (SEBELUM TOPUP) ==========
                if (existingPelanggan.pembayaranList.isNotEmpty()) {
                    val pinjamanKeLama = existingPelanggan.pinjamanKe
                    val riwayatData = mapOf(
                        "pinjamanKe" to pinjamanKeLama,
                        "besarPinjaman" to existingPelanggan.besarPinjaman,
                        "totalPelunasan" to totalPelunasanLama,
                        "totalBayar" to totalBayarSebelumnya,
                        "sisaUtang" to sisaUtangLama,
                        "tanggalTopUp" to tanggalPengajuanBaru,
                        "pembayaranList" to existingPelanggan.pembayaranList.map { p ->
                            mapOf(
                                "jumlah" to p.jumlah,
                                "tanggal" to p.tanggal,
                                "subPembayaran" to p.subPembayaran.map { s ->
                                    mapOf("jumlah" to s.jumlah, "tanggal" to s.tanggal, "keterangan" to s.keterangan)
                                }
                            )
                        }
                    )
                    database.child("riwayatPembayaran")
                        .child(existingPelanggan.adminUid)
                        .child(pelangganId)
                        .child("pinjaman$pinjamanKeLama")
                        .setValue(riwayatData)
                    Log.d("KelolaKredit", "📚 Riwayat pembayaran pinjaman ke-$pinjamanKeLama disimpan (${existingPelanggan.pembayaranList.size} entri)")
                }

                simpanPelangganKeFirebase(updatedPelanggan,
                    onSuccess = {
                        database.child("pelanggan")
                            .child(existingPelanggan.adminUid)
                            .child(pelangganId)
                            .child("simpanan")
                            .setValue(totalSimpananBaru)
                        onSuccess?.invoke()
                    },
                    onFailure = { exception ->
                        onFailure?.invoke(exception)
                    }
                )

            } catch (e: Exception) {
                onFailure?.invoke(e)
            }
        }
    }

    fun hapusPelanggan(
        pelangganId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                val pelanggan = getPelangganById(pelangganId)
                if (pelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }

                val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid }
                if (adminUid.isNullOrBlank()) {
                    onFailure?.invoke(Exception("Admin UID tidak valid"))
                    return@launch
                }

                // Hapus dari Firebase
                database.child("pelanggan").child(adminUid).child(pelangganId)
                    .removeValue()
                    .addOnSuccessListener {
                        // Hapus dari local list
                        val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                        if (index != -1) {
                            daftarPelanggan.removeAt(index)
                        }

                        // Update dashboard dan simpan perubahan lokal
                        loadDashboardData()
                        simpanKeLokal()

                        Log.d("PelangganVM", "✅ Berhasil hapus pelanggan: $pelangganId")
                        onSuccess?.invoke()
                    }
                    .addOnFailureListener { e ->
                        Log.e("PelangganVM", "❌ Gagal hapus pelanggan: ${e.message}")
                        onFailure?.invoke(e)
                    }

            } catch (e: Exception) {
                Log.e("PelangganVM", "❌ Error dalam hapusPelanggan: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    fun syncDeletedItems(adminUid: String) {
        if (!isOnline()) return

        viewModelScope.launch {
            try {
                // Ambil semua ID dari Firebase
                val firebaseSnapshot = database.child("pelanggan").child(adminUid).get().await()
                val firebaseIds = mutableSetOf<String>()

                firebaseSnapshot.children.forEach { snapshot ->
                    snapshot.key?.let { firebaseIds.add(it) }
                }

                // Hapus data lokal yang tidak ada di Firebase
                val localIdsToRemove = daftarPelanggan
                    .filter { it.adminUid == adminUid && it.id !in firebaseIds && !it.id.startsWith("local-") }
                    .map { it.id }

                if (localIdsToRemove.isNotEmpty()) {
                    daftarPelanggan.removeAll { it.id in localIdsToRemove }
                    simpanKeLokal()
                    loadDashboardData()
                    Log.d("Sync", "🗑️ Synced deletions: removed ${localIdsToRemove.size} items")
                }
            } catch (e: Exception) {
                Log.e("Sync", "❌ Error syncing deletions: ${e.message}")
            }
        }
    }

    /**
     * ✅ PERBAIKAN #3: Versi aman dari syncDeletedItems
     * - Suspend function agar bisa ditunggu
     * - Tidak menghapus data yang isSynced=false (belum selesai sync)
     * - Tidak menghapus data yang ada di Room pending operations
     */
    private suspend fun syncDeletedItemsSafe(adminUid: String) {
        if (!isOnline()) return

        try {
            val firebaseSnapshot = database.child("pelanggan").child(adminUid).get().await()
            val firebaseIds = mutableSetOf<String>()

            firebaseSnapshot.children.forEach { snapshot ->
                snapshot.key?.let { firebaseIds.add(it) }
            }

            // ✅ PERBAIKAN: Hanya hapus data yang:
            // 1. Bukan local- (belum pernah di-sync)
            // 2. Sudah isSynced = true (pernah berhasil sync sebelumnya)
            // 3. Tidak ada di Firebase (berarti dihapus dari server)
            val localIdsToRemove = daftarPelanggan
                .filter { pel ->
                    pel.adminUid == adminUid &&
                            pel.id !in firebaseIds &&
                            !pel.id.startsWith("local-") &&
                            pel.isSynced  // ← KUNCI: hanya hapus yang sudah pernah sync
                }
                .map { it.id }

            if (localIdsToRemove.isNotEmpty()) {
                Log.d("Sync", "🗑️ Safe delete: removing ${localIdsToRemove.size} items that were deleted on server")
                localIdsToRemove.forEach { id ->
                    val pel = daftarPelanggan.firstOrNull { it.id == id }
                    Log.d("Sync", "   🗑️ Removing: ${pel?.namaPanggilan} (id=$id)")
                }
                daftarPelanggan.removeAll { it.id in localIdsToRemove }
                simpanKeLokal()
                loadDashboardData()
            } else {
                Log.d("Sync", "✅ No server-side deletions to sync")
            }
        } catch (e: Exception) {
            Log.e("Sync", "❌ Error syncing deletions safely: ${e.message}")
        }
    }

    fun calculatePaymentSummaryWithEmail(pelangganList: List<Pelanggan>, date: String): List<AdminPaymentSummary> {
        val adminMap = mutableMapOf<String, MutableList<Pair<String, Int>>>()

        pelangganList.forEach { pelanggan ->
            // ✅ PERBAIKAN: Hitung SEMUA pembayaran (utama + sub) untuk tanggal tertentu
            val todayTotalPayments = pelanggan.pembayaranList.sumOf { pembayaran ->
                var totalPembayaranHariIni = 0

                // ✅ HITUNG: Pembayaran utama jika tanggalnya sesuai
                if (pembayaran.tanggal == date) {
                    totalPembayaranHariIni += pembayaran.jumlah
                }

                // ✅ HITUNG: Sub pembayaran jika tanggalnya sesuai
                val subPembayaranHariIni = pembayaran.subPembayaran
                    .filter { it.tanggal == date }
                    .sumOf { sub -> sub.jumlah }

                totalPembayaranHariIni + subPembayaranHariIni
            }

            if (todayTotalPayments > 0) {
                val adminId = pelanggan.adminUid
                adminMap.getOrPut(adminId) { mutableListOf() }.add(
                    pelanggan.namaPanggilan to todayTotalPayments
                )
            }
        }

        return adminMap.map { (adminId, payments) ->
            val totalPayment = payments.sumOf { it.second }
            val firstPelanggan = pelangganList.firstOrNull { it.adminUid == adminId }

            // Prioritaskan adminName, jika kosong gunakan adminEmail, jika masih kosong gunakan adminId
            val displayName = firstPelanggan?.adminName?.ifBlank {
                firstPelanggan.adminEmail.ifBlank { "Admin $adminId" }
            } ?: "Admin $adminId"

            AdminPaymentSummary(
                adminId = adminId,
                adminName = displayName,
                adminEmail = firstPelanggan?.adminEmail ?: "", // Tambahkan email
                totalPayment = totalPayment,
                customerCount = payments.size
            )
        }.sortedByDescending { it.totalPayment }
    }

    fun calculateCustomerPayments(pelangganList: List<Pelanggan>, date: String, adminId: String?): List<CustomerPayment> {
        if (adminId.isNullOrBlank()) return emptyList()

        return pelangganList
            .filter { it.adminUid == adminId }
            .mapNotNull { pelanggan ->
                // ✅ PERBAIKAN: Hitung SEMUA pembayaran (utama + sub) untuk tanggal tertentu
                val allPaymentsToday = pelanggan.pembayaranList.flatMap { pembayaran ->
                    val payments = mutableListOf<InstallmentDetail>()

                    // ✅ TAMBAHKAN: Pembayaran utama jika tanggalnya sesuai
                    if (pembayaran.tanggal == date) {
                        payments.add(InstallmentDetail(pembayaran.tanggal, pembayaran.jumlah))
                    }

                    // ✅ TAMBAHKAN: Sub pembayaran jika tanggalnya sesuai
                    val subPayments = pembayaran.subPembayaran
                        .filter { it.tanggal == date }
                        .map { sub -> InstallmentDetail(sub.tanggal, sub.jumlah) }

                    payments + subPayments
                }

                if (allPaymentsToday.isNotEmpty()) {
                    val totalPayment = allPaymentsToday.sumOf { it.amount }
                    CustomerPayment(
                        customerId = pelanggan.id,
                        customerName = pelanggan.namaPanggilan,
                        paymentAmount = totalPayment,
                        installmentDetails = allPaymentsToday
                    )
                } else {
                    null
                }
            }
            .sortedByDescending { it.paymentAmount }
    }


    fun getTotalSimpananByNama(namaKtp: String): Int {
        return daftarPelanggan
            .filter { it.namaKtp.equals(namaKtp, ignoreCase = true) }
            .sumOf { it.simpanan }
    }

    fun muatDariLokal(adminUid: String) {
        muatTema()

        viewModelScope.launch {
            try {
                val data = LocalStorage.ambilDataPelanggan(getApplication(), adminUid)

                // ✅ PERBAIKAN: Hapus HANYA data lokal dari admin ini, jangan semua data
                val dataToRemove = daftarPelanggan.filter {
                    it.adminUid == adminUid && (it.id.startsWith("local-") || !it.isSynced)
                }
                daftarPelanggan.removeAll(dataToRemove)

                // ✅ PERBAIKAN: Filter data lokal untuk menghindari duplikasi dengan data Firebase
                val existingIds = daftarPelanggan.map { it.id }.toSet()
                val newLocalData = data.filter { it.id !in existingIds }

                // Tambahkan data dari lokal yang belum ada di daftarPelanggan
                daftarPelanggan.addAll(newLocalData)

                Log.d("LocalStorage", "📂 Memuat ${newLocalData.size} data dari lokal untuk admin: $adminUid")
                Log.d("LocalStorage", "📊 Total data sekarang: ${daftarPelanggan.size} pelanggan")

                // Cek data yang belum sync
                val unsyncedCount = newLocalData.count { !it.isSynced || it.id.startsWith("local-") }
                if (unsyncedCount > 0) {
                    Log.d("LocalStorage", "📤 $unsyncedCount data belum tersinkronisasi")
                }

            } catch (e: Exception) {
                Log.e("LocalStorage", "❌ Gagal memuat data lokal: ${e.message}")
            }
        }
    }

    // Fungsi untuk menghitung minggu tanpa pembayaran
    private fun hitungMingguTanpaPembayaran(pelanggan: Pelanggan): Int {
        return try {
            if (pelanggan.pembayaranList.isEmpty()) {
                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                val tanggalPengajuan = dateFormat.parse(pelanggan.tanggalPengajuan)
                val calendarPengajuan = Calendar.getInstance().apply { time = tanggalPengajuan }
                val sekarang = Calendar.getInstance()

                val selisihMinggu = hitungSelisihMinggu(calendarPengajuan, sekarang)
                return selisihMinggu
            }

            val pembayaranTerakhir = pelanggan.pembayaranList.maxByOrNull {
                SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).parse(it.tanggal)?.time ?: 0L
            }

            if (pembayaranTerakhir != null) {
                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                val tanggalTerakhirBayar = dateFormat.parse(pembayaranTerakhir.tanggal)
                val calendarTerakhirBayar = Calendar.getInstance().apply { time = tanggalTerakhirBayar }
                val sekarang = Calendar.getInstance()

                hitungSelisihMinggu(calendarTerakhirBayar, sekarang)
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e("PelangganMacet", "Error hitung minggu tanpa pembayaran: ${e.message}")
            0
        }
    }

    // Fungsi untuk menghitung selisih minggu
    private fun hitungSelisihMinggu(dari: Calendar, sampai: Calendar): Int {
        val millisPerMinggu = 7 * 24 * 60 * 60 * 1000L
        val selisihMillis = sampai.timeInMillis - dari.timeInMillis
        return (selisihMillis / millisPerMinggu).toInt()
    }

    // Fungsi untuk mendeteksi pola pembayaran bolong-bolong
    private fun deteksiPembayaranBolong(pelanggan: Pelanggan): Boolean {
        return try {
            if (pelanggan.pembayaranList.size < 3) return false

            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
            val pembayaranSorted = pelanggan.pembayaranList.sortedBy {
                dateFormat.parse(it.tanggal)?.time ?: 0L
            }

            var inconsistencyCount = 0
            var totalIntervals = 0

            for (i in 1 until pembayaranSorted.size) {
                val currentDate = dateFormat.parse(pembayaranSorted[i].tanggal)
                val previousDate = dateFormat.parse(pembayaranSorted[i-1].tanggal)

                if (currentDate != null && previousDate != null) {
                    val selisihHari = hitungSelisihHari(previousDate, currentDate)
                    totalIntervals++

                    // Jika selisih hari tidak konsisten (terlalu pendek/panjang)
                    if (selisihHari !in 5..9) { // Normal: 7 hari ± 2 hari toleransi
                        inconsistencyCount++
                    }
                }
            }

            // Jika lebih dari 50% interval pembayaran tidak konsisten
            val rasioInkonsistensi = inconsistencyCount.toFloat() / totalIntervals
            rasioInkonsistensi > 0.5f

        } catch (e: Exception) {
            Log.e("PelangganMacet", "Error deteksi pola bolong: ${e.message}")
            false
        }
    }

    private fun hitungSelisihHari(dari: Date, sampai: Date): Int {
        val millisPerHari = 24 * 60 * 60 * 1000L
        val selisihMillis = sampai.time - dari.time
        return (selisihMillis / millisPerHari).toInt()
    }

    override fun onCleared() {
        super.onCleared()
        currentUserCabang.value?.let { cabangId ->
            pengajuanApprovalListener?.let { listener ->
                database.child("pengajuan_approval").child(cabangId).removeEventListener(listener)
            }
        }
        cleanupRoleListeners()

        // ✅ TAMBAHAN: Cleanup network callback
        try {
            networkCallbackVM?.let {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
            networkCallbackVM = null
        } catch (e: Exception) {
            Log.e("PelangganVM", "Error cleaning up network callback: ${e.message}")
        }

        Log.d("PelangganVM", "🧹 ViewModel cleared, listeners removed")
    }

    fun getPinjamanInfoForApproval(pelanggan: Pelanggan): PinjamanApprovalInfo {
        // ✅ PERBAIKAN: Gunakan sisaUtangLamaSebelumTopUp yang sudah disimpan
        // Jika field baru ada, gunakan itu. Jika tidak (data lama), hitung manual.

        val sisaUtangLama = if (pelanggan.sisaUtangLamaSebelumTopUp > 0) {
            // ✅ Gunakan nilai yang sudah disimpan saat pengajuan
            pelanggan.sisaUtangLamaSebelumTopUp
        } else if (pelanggan.totalPelunasanLamaSebelumTopUp > 0) {
            // Fallback: hitung dari total pelunasan lama
            val totalBayarSebelumnya = pelanggan.pembayaranList.sumOf { pembayaran ->
                pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
            }
            (pelanggan.totalPelunasanLamaSebelumTopUp - totalBayarSebelumnya).coerceAtLeast(0)
        } else {
            // Fallback untuk data lama (sebelum perbaikan): extract dari catatanPerubahanPinjaman
            extractSisaUtangFromCatatan(pelanggan.catatanPerubahanPinjaman)
        }

        val jasaPinjaman = (pelanggan.besarPinjaman * 10) / 100
        val jumlahDiberikan = pelanggan.totalDiterima

        Log.d("PinjamanInfo", "📊 getPinjamanInfoForApproval:")
        Log.d("PinjamanInfo", "   Sisa Utang Lama: $sisaUtangLama")
        Log.d("PinjamanInfo", "   Jasa Pinjaman: $jasaPinjaman")
        Log.d("PinjamanInfo", "   Jumlah Diberikan: $jumlahDiberikan")

        return PinjamanApprovalInfo(
            jasaPinjaman = jasaPinjaman,
            sisaUtangLama = sisaUtangLama,
            jumlahDiberikan = jumlahDiberikan,
            totalSimpanan = pelanggan.simpanan
        )
    }

    private fun extractSisaUtangFromCatatan(catatan: String): Int {
        return try {
            if (catatan.contains("sisaUtangLama=")) {
                val regex = "sisaUtangLama=(\\d+)".toRegex()
                val match = regex.find(catatan)
                match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else if (catatan.contains("totalPelunasan=") && catatan.contains("totalPembayaran=")) {
                // Fallback: hitung dari catatan
                val pelunasanRegex = "totalPelunasan=(\\d+)".toRegex()
                val bayarRegex = "totalPembayaran=(\\d+)".toRegex()
                val pelunasan = pelunasanRegex.find(catatan)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bayar = bayarRegex.find(catatan)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                (pelunasan - bayar).coerceAtLeast(0)
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e("ExtractSisaUtang", "Error: ${e.message}")
            0
        }
    }

    private var dataUpdateListener: ValueEventListener? = null

    // ✅ TAMBAHKAN variabel baru di atas fungsi (di area deklarasi variabel class,
//    misalnya dekat baris 439 di mana lastLoadedKey dideklarasikan):
    private var lastDataUpdateTimestamp: Long = 0L
    private var isRefreshingFromListener: Boolean = false

    // ✅ GANTI seluruh isi fungsi setupDataUpdateListener:
    fun setupDataUpdateListener(adminUid: String) {
        database.child("data_updates").child(adminUid).child("lastUpdate")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val timestamp = snapshot.getValue(Long::class.java) ?: return

                    // ✅ DEBOUNCE: Abaikan jika timestamp sama atau sudah sedang refresh
                    if (timestamp == lastDataUpdateTimestamp || isRefreshingFromListener) {
                        Log.d("DataUpdate", "⏭️ Skip duplicate/concurrent update: $timestamp")
                        return
                    }
                    lastDataUpdateTimestamp = timestamp

                    Log.d("DataUpdate", "🔄 Data update detected at: $timestamp")

                    viewModelScope.launch {
                        // ✅ Guard agar tidak refresh bersamaan
                        if (isRefreshingFromListener) return@launch
                        isRefreshingFromListener = true

                        try {
                            // ✅ Invalidate memory cache
                            cacheManager.invalidatePelangganCache(adminUid)

                            // ✅ FIX: Gunakan smartLoader yang load SEMUA data,
                            //    BUKAN loadPelangganPaginated yang hanya load 50!
                            val result = smartLoader.loadDataForAdmin(adminUid, forceRefresh = true)

                            when (result) {
                                is SmartFirebaseLoader.LoadResult.Success -> {
                                    daftarPelanggan.clear()
                                    daftarPelanggan.addAll(result.data.sortedBy { it.namaPanggilan })
                                    simpanKeLokal()

                                    // Update dashboard
                                    loadDashboardData()
                                    _adminSummary.value = calculateAdminSummary(daftarPelanggan)

                                    Log.d("DataUpdate", "✅ Full refresh: ${result.data.size} nasabah loaded")
                                }
                                is SmartFirebaseLoader.LoadResult.Error -> {
                                    Log.e("DataUpdate", "❌ Refresh failed: ${result.message}")
                                    // Tidak clear data jika gagal — data lama tetap aman
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("DataUpdate", "❌ Error: ${e.message}")
                        } finally {
                            isRefreshingFromListener = false
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("DataUpdate", "❌ Listener cancelled: ${error.message}")
                }
            })
    }

    data class PinjamanApprovalInfo(
        val jasaPinjaman: Int,
        val sisaUtangLama: Int,
        val jumlahDiberikan: Int,
        val totalSimpanan: Int
    )

    fun calculateNetCashFlowForAdmin(adminId: String): Int {
        return try {
            val today = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).format(Date())

            // Hitung total penerimaan hari ini untuk admin tertentu
            val totalPembayaran = daftarPelanggan
                .filter { it.adminUid == adminId }
                .sumOf { pelanggan ->
                    pelanggan.pembayaranList.sumOf { pembayaran ->
                        var totalPembayaranHariIni = 0

                        // Pembayaran utama hari ini
                        if (pembayaran.tanggal == today) {
                            totalPembayaranHariIni += pembayaran.jumlah
                        }

                        // Sub pembayaran hari ini
                        val subPembayaranHariIni = pembayaran.subPembayaran
                            .filter { it.tanggal == today }
                            .sumOf { sub -> sub.jumlah }

                        totalPembayaranHariIni + subPembayaranHariIni
                    }
                }

            // Hitung total pengeluaran hari ini untuk admin tertentu
            val totalPinjamanBaru = daftarPelanggan
                .filter { it.adminUid == adminId }
                .sumOf { pelanggan ->
                    if (pelanggan.tanggalPengajuan == today && pelanggan.besarPinjaman > 0) {
                        getJumlahYangDiberikan(pelanggan)
                    } else {
                        0
                    }
                }

            // Net Cash Flow = Penerimaan - Pengeluaran
            totalPembayaran - totalPinjamanBaru

        } catch (e: Exception) {
            Log.e("NetCashFlow", "Error calculating net cash flow for admin $adminId: ${e.message}")
            0
        }
    }

    // Pastikan fungsi getJumlahYangDiberikan ada di ViewModel (copy dari LaporanHarianScreen.kt)
    private fun getJumlahYangDiberikan(pelanggan: Pelanggan): Int {
        return when {
            // Prioritas 1: Gunakan totalDiterima jika ada dan valid
            pelanggan.totalDiterima > 0 -> pelanggan.totalDiterima

            // Prioritas 2: Hitung manual berdasarkan besarPinjaman - admin - simpanan
            pelanggan.admin > 0 && pelanggan.simpanan > 0 -> {
                pelanggan.besarPinjaman - pelanggan.admin - pelanggan.simpanan
            }

            // Prioritas 3: Hitung berdasarkan persentase default
            else -> {
                val admin = (pelanggan.besarPinjaman * 5) / 100
                val simpanan = (pelanggan.besarPinjaman * 5) / 100
                pelanggan.besarPinjaman - admin - simpanan
            }
        }.coerceAtLeast(0) // Pastikan tidak negatif
    }

    fun tambahMultiplePembayaran(id: String, totalJumlah: Int, tanggalAwal: String, jumlahPerCicilan: Int) {
        val index = daftarPelanggan.indexOfFirst { it.id == id }
        if (index != -1) {
            val pelanggan = daftarPelanggan[index]

            // Hitung jumlah cicilan yang akan dibuat
            val jumlahCicilan = (totalJumlah + jumlahPerCicilan - 1) / jumlahPerCicilan

            val updatedPembayaranList = pelanggan.pembayaranList.toMutableList()
            val startIndex = updatedPembayaranList.size // Index awal untuk pembayaran baru

            // Buat multiple pembayaran
            for (i in 0 until jumlahCicilan) {
                val jumlahCicilanIni = if (i == jumlahCicilan - 1) {
                    totalJumlah - (jumlahPerCicilan * (jumlahCicilan - 1))
                } else {
                    jumlahPerCicilan
                }
                val pembayaran = Pembayaran(jumlahCicilanIni, tanggalAwal)
                updatedPembayaranList.add(pembayaran)
            }

            // Hitung total yang sudah dibayar
            val totalDibayar = updatedPembayaranList.sumOf { p ->
                p.jumlah + p.subPembayaran.sumOf { it.jumlah }
            }
            val status = if (totalDibayar >= pelanggan.totalPelunasan) "Lunas" else "Aktif"

            // ✅ FIX: Update lastUpdated dan isSynced (seperti tambahPembayaran single)
            val updatedPelanggan = pelanggan.copy(
                pembayaranList = updatedPembayaranList,
                status = status,
                isSynced = isOnline(),
                lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )

            daftarPelanggan[index] = updatedPelanggan

            // ✅ FIX: Simpan setiap pembayaran baru via offlineRepo (seperti tambahPembayaran single)
            val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid } ?: return

            viewModelScope.launch {
                for (i in 0 until jumlahCicilan) {
                    val pembayaranIndex = startIndex + i
                    val jumlahCicilanIni = if (i == jumlahCicilan - 1) {
                        totalJumlah - (jumlahPerCicilan * (jumlahCicilan - 1))
                    } else {
                        jumlahPerCicilan
                    }

                    val pembayaranMap = mapOf(
                        "jumlah" to jumlahCicilanIni,
                        "tanggal" to tanggalAwal,
                        "subPembayaran" to emptyList<Map<String, Any>>()
                    )

                    offlineRepo.addPembayaran(
                        adminUid = adminUid,
                        pelangganId = pelanggan.id,
                        pembayaranIndex = pembayaranIndex,
                        pembayaran = pembayaranMap
                    )
                }

                // Update status jika perlu
                if (status != pelanggan.status) {
                    offlineRepo.updatePelanggan(
                        adminUid = adminUid,
                        pelangganId = pelanggan.id,
                        updateData = mapOf("status" to status)
                    )
                }
            }

            simpanKeLokal()
            Log.d("MultiplePembayaran", "✅ Berhasil membuat $jumlahCicilan cicilan (isSynced=${updatedPelanggan.isSynced})")

            if (status == "Lunas") {
                updateStatusLunasCicilan(id)
            }
        }
    }

    // Fungsi untuk mendapatkan daftar pelanggan dengan status khusus
    fun getPelangganDenganStatusKhusus(): List<Pelanggan> {
        return daftarPelanggan.filter { pelanggan ->
            pelanggan.statusKhusus.isNotBlank() &&
                    pelanggan.status == "Aktif" // Hanya nasabah aktif dengan status khusus
        }
    }

    // Fungsi untuk menghitung jumlah pelanggan dengan status khusus
    fun getJumlahPelangganStatusKhusus(): Int {
        return getPelangganDenganStatusKhusus().size
    }

    private suspend fun detectUserRoleFromMetadata(): UserRole {
        return try {
            val uid = Firebase.auth.currentUser?.uid ?: return UserRole.UNKNOWN
            val metaRef = database.child("metadata")

            // ✅ PERBAIKAN: Tambah timeout 10 detik untuk mencegah hang
            val rolesSnap = withTimeoutOrNull(10000L) {
                metaRef.child("roles").get().await()
            }

            // Jika timeout, return ADMIN_LAPANGAN sebagai default
            if (rolesSnap == null) {
                Log.w("RoleDetect", "⚠️ Timeout detecting role, using default ADMIN_LAPANGAN")
                return UserRole.ADMIN_LAPANGAN
            }

            if (rolesSnap.child("pengawas").child(uid).exists()) {
                return UserRole.PENGAWAS
            }
            if (rolesSnap.child("koordinator").child(uid).exists()) {
                return UserRole.KOORDINATOR
            }

            // Pimpinan mungkin disimpan sebagai pimpinan: { cabangId: uid }
            val pimpinanNode = rolesSnap.child("pimpinan")
            if (pimpinanNode.exists()) {
                for (cabang in pimpinanNode.children) {
                    val valUid = cabang.getValue(String::class.java)
                    if (valUid == uid) {
                        _currentUserCabang.value = cabang.key
                        return UserRole.PIMPINAN
                    }
                }
            }

            // ✅ TAMBAHAN: Cek juga di metadata/cabang/{cabangId}/pimpinanUid
            val cabangSnapshot = withTimeoutOrNull(5000L) {
                metaRef.child("cabang").get().await()
            }
            if (cabangSnapshot != null && cabangSnapshot.exists()) {
                for (cabang in cabangSnapshot.children) {
                    val pimpinanUid = cabang.child("pimpinanUid").getValue(String::class.java)
                    if (pimpinanUid == uid) {
                        _currentUserCabang.value = cabang.key
                        Log.d("RoleDetect", "✅ Pimpinan detected from metadata/cabang: ${cabang.key}")
                        return UserRole.PIMPINAN
                    }
                }
            }

            // Fallback: cek metadata/admins/{uid}.role
            val adminMeta = withTimeoutOrNull(5000L) {
                metaRef.child("admins").child(uid).get().await()
            }
            if (adminMeta == null) {
                Log.w("RoleDetect", "⚠️ Timeout getting admin metadata")
                return UserRole.ADMIN_LAPANGAN
            }
            if (adminMeta.exists()) {
                val role = adminMeta.child("role").getValue(String::class.java)
                val cabang = adminMeta.child("cabang").getValue(String::class.java)
                if (!cabang.isNullOrBlank()) _currentUserCabang.value = cabang
                return when (role) {
                    "pengawas" -> UserRole.PENGAWAS
                    "koordinator" -> UserRole.KOORDINATOR
                    "pimpinan" -> UserRole.PIMPINAN
                    else -> UserRole.ADMIN_LAPANGAN
                }
            }

            // Default: admin lapangan
            UserRole.ADMIN_LAPANGAN
        } catch (e: Exception) {
            Log.e("RoleDetect", "Error detect role: ${e.message}")
            UserRole.UNKNOWN
        }
    }

    private var isRoleInitStarted = false

    fun startRoleDetectionAndInit() {
        // Hanya blokir jika sudah running DAN role sudah terdeteksi (bukan UNKNOWN)
        // Jika role masih UNKNOWN, izinkan re-init (contoh: setelah login berhasil)
        if (isRoleInitStarted && _currentUserRole.value != UserRole.UNKNOWN) {
            Log.d("RoleDetect", "⚠️ startRoleDetectionAndInit already completed with role=${_currentUserRole.value}, skip")
            return
        }
        isRoleInitStarted = true
        viewModelScope.launch {
            val role = detectUserRoleFromMetadata()
            _currentUserRole.value = role
            Log.d("RoleDetect", "Detected role = $role")

            Firebase.auth.currentUser?.uid?.let { uid ->
                setupDataUpdateListener(uid)
            }

            try {
                NotificationHelper.fetchAndSaveToken()
                Log.d("FCM", "✅ FCM token saved for role: $role")
            } catch (e: Exception) {
                Log.e("FCM", "❌ Error saving FCM token: ${e.message}")
            }

            initializeRoleBasedListeners(role)
        }
    }

    private fun initializeRoleBasedListeners(role: UserRole) {
        cleanupRoleListeners()
        when (role) {
            UserRole.ADMIN_LAPANGAN -> {
                initAdminLapanganListeners()
            }
            UserRole.PIMPINAN -> {
                val cabang = _currentUserCabang.value
                if (!cabang.isNullOrBlank()) {
                    initPimpinanListeners(cabang)
                    // JANGAN panggil loadPendingApprovalsForPimpinan(cabang) atau setupRealtimePendingApprovals(cabang) di sini
                    // Biarkan PimpinanApprovalScreen yang mengatur timing-nya
                } else {
                    Log.w("InitRole", "Pimpinan detected but no cabang assigned")
                }
            }
            UserRole.PENGAWAS, UserRole.KOORDINATOR -> {
                initPengawasListeners()
            }
            else -> {
                Log.w("InitRole", "Unknown role, no listeners attached")
            }
        }
    }


    private fun cleanupRoleListeners() {
        for ((ref, listener) in roleBasedListenerRefs) {
            try {
                ref.removeEventListener(listener)
            } catch (e: Exception) {
                Log.w("Cleanup", "Error removing listener: ${e.message}")
            }
        }
        roleBasedListenerRefs.clear()

        // ✅ Reset pending approval listener flag
        _isPendingApprovalListenerActive = false

        pelangganListener?.let {
            try {
                val uid = Firebase.auth.currentUser?.uid
                if (!uid.isNullOrBlank()) {
                    database.child("pelanggan").child(uid).removeEventListener(it)
                }
            } catch (_: Exception) {}
            pelangganListener = null
        }

        Log.d("Cleanup", "Role-based listeners cleaned up")
    }

    private fun initAdminLapanganListeners() {
        val uid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                isLoading.value = true

                // OPTIMASI: Smart loader otomatis handle online/offline
                val result = smartLoader.loadDataForAdmin(uid, forceRefresh = false)

                when (result) {
                    is SmartFirebaseLoader.LoadResult.Success -> {
                        withContext(Dispatchers.Main) {
                            daftarPelanggan.clear()
                            daftarPelanggan.addAll(result.data)
                        }

                        // Log source data (Firebase/Cache/LocalStorage)
                        val sourceInfo = when (result.source) {
                            SmartFirebaseLoader.DataSource.FIREBASE -> "Firebase"
                            SmartFirebaseLoader.DataSource.MEMORY_CACHE -> "Memory Cache"
                            SmartFirebaseLoader.DataSource.LOCAL_STORAGE -> "LocalStorage (OFFLINE)"
                        }
                        Log.d("AdminListener", "✅ Loaded ${result.data.size} pelanggan from $sourceInfo")

                        // Track jika ada data yang belum sync
                        if (result.unsyncedCount > 0) {
                            Log.d("AdminListener", "⏳ ${result.unsyncedCount} data belum sync")
                            _hasUnsyncedData.value = true
                        }

                        loadDashboardData()
                        _adminSummary.value = calculateAdminSummary(daftarPelanggan)
                        _isDataLoaded.value = true
                    }
                    is SmartFirebaseLoader.LoadResult.Error -> {
                        Log.e("AdminListener", "❌ Error: ${result.message}")
                        // Fallback sudah ditangani di SmartFirebaseLoader
                    }
                }
            } catch (e: Exception) {
                Log.e("AdminListener", "❌ Exception: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }

        // Setup listener HANYA untuk notifikasi (jika online)
        if (smartLoader.isOnline()) {
            setupNotificationListenerOptimized(uid)
        }
    }

    private fun setupNotificationListenerOptimized(uid: String) {
        val notifRef = database.child("admin_notifications").child(uid)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                viewModelScope.launch {
                    val notifList = mutableListOf<AdminNotification>()

                    snapshot.children.forEach { child ->
                        // ✅ PERBAIKAN: Skip jika bukan object (mencegah crash dari Boolean)
                        if (!child.hasChildren()) {
                            Log.w("NotifListener", "⚠️ Skipping non-object entry: ${child.key} = ${child.value}")
                            return@forEach
                        }

                        try {
                            // ✅ PERBAIKAN: Wrap dalam try-catch
                            child.getValue(AdminNotification::class.java)?.let { notification ->
                                notifList.add(notification.copy(id = child.key ?: notification.id))

                                if (notification.type == "DELETION_APPROVED" && !notification.read) {
                                    // Hapus pelanggan dari local list
                                    val pelangganIdToRemove = notification.pelangganId
                                    if (pelangganIdToRemove.isNotBlank()) {
                                        val index = daftarPelanggan.indexOfFirst { it.id == pelangganIdToRemove }
                                        if (index != -1) {
                                            daftarPelanggan.removeAt(index)
                                            simpanKeLokal()
                                            Log.d("NotifListener", "🗑️ Pelanggan $pelangganIdToRemove dihapus dari local list")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Skip entry yang corrupt, JANGAN crash app
                            Log.e("NotifListener", "❌ Error parsing notification ${child.key}: ${e.message}")
                        }
                    }

                    // Sort by timestamp descending (terbaru di atas)
                    adminNotifications.value = notifList.sortedByDescending { it.timestamp }

                    Log.d("NotifListener", "✅ Loaded ${notifList.size} notifications")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("NotifListener", "❌ Cancelled: ${error.message}")
            }
        }

        notifRef.addValueEventListener(listener)
        roleBasedListenerRefs.add(notifRef to listener)
    }

    private fun initPimpinanListeners(cabangId: String) {
        Log.d("PimpinanInit", "🔄 Initializing pimpinan listeners for: $cabangId")

        cleanupRoleListeners()

        viewModelScope.launch {
            try {
                isLoading.value = true

                // OPTIMASI: Gunakan smart loader - hanya load summary
                val result = smartLoader.loadDataForPimpinan(cabangId, forceRefresh = false)

                if (result.success) {
                    // Update admin summaries
                    _adminSummary.value = result.adminSummaries

                    // Update pending approvals
                    _pendingApprovals.clear()
                    _pendingApprovals.addAll(result.pendingApprovals)

                    // Update cabang summary
                    result.cabangSummary?.let {
                        _cabangSummary.value = CabangSummary(
                            cabangId = it.cabangId,
                            totalNasabah = it.totalNasabah,
                            nasabahAktif = it.nasabahAktif,
                            totalPinjamanAktif = it.totalPinjamanAktif
                        )
                    }

                    _isDataLoaded.value = true
                    Log.d("PimpinanInit", "✅ Loaded ${result.adminSummaries.size} admin summaries, ${result.pendingApprovals.size} pending")
                } else {
                    Log.e("PimpinanInit", "❌ Error: ${result.error}")
                }

            } catch (e: Exception) {
                Log.e("PimpinanInit", "❌ Exception: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }

        // Setup listener ringan untuk pending approvals baru
        setupPendingApprovalListenerOptimized(cabangId)
    }

    private fun setupPendingApprovalListenerOptimized(cabangId: String) {
        val approvalRef = database.child("pengajuan_approval").child(cabangId)
            .orderByChild("status")
            .equalTo("Menunggu Approval")
            .limitToLast(1) // OPTIMASI: Hanya ambil yang terbaru

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    viewModelScope.launch {
                        val result = smartLoader.loadPendingApprovalsOptimized(cabangId, forceRefresh = true)
                        _pendingApprovals.clear()
                        _pendingApprovals.addAll(result)
                        Log.d("ApprovalListener", "🔄 Refreshed pending approvals: ${result.size}")
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("ApprovalListener", "Cancelled: ${error.message}")
            }
        }

        approvalRef.addValueEventListener(listener)
        roleBasedListenerRefs.add(approvalRef to listener)
    }

    private suspend fun loadAdminSummariesForCabang(cabangId: String) {
        try {
            // Dapatkan daftar admin dari cabang
            val cabangMeta = database.child("metadata").child("cabang").child(cabangId).get().await()
            val adminList = mutableListOf<String>()
            cabangMeta.child("adminList").children.forEach {
                it.value?.toString()?.let { uid -> adminList.add(uid) }
            }

            val adminSummaries = mutableListOf<AdminSummary>()

            // Gunakan concurrent loading untuk performance
            val adminJobs = adminList.map { adminUid ->
                viewModelScope.async {
                    try {
                        // Ambil summary dari Firebase (sumber kebenaran)
                        val snap = database.child("summary").child("perAdmin").child(adminUid).get().await()
                        if (snap.exists()) {
                            val totalNasabah = snap.child("totalNasabah").getValue(Int::class.java) ?: 0
                            val nasabahAktif = snap.child("nasabahAktif").getValue(Int::class.java) ?: 0
                            val totalPinjaman = snap.child("totalPinjamanAktif").getValue(Long::class.java) ?: 0L

                            // Ambil metadata admin
                            val adminMeta = database.child("metadata").child("admins").child(adminUid).get().await()
                            val adminName = adminMeta.child("nama").getValue(String::class.java) ?: "Admin $adminUid"
                            val adminEmail = adminMeta.child("email").getValue(String::class.java) ?: ""

                            AdminSummary(
                                adminId = adminUid,
                                adminName = adminName,
                                adminEmail = adminEmail,
                                cabang = cabangId,
                                totalPelanggan = totalNasabah,
                                nasabahAktif = nasabahAktif,
                                nasabahLunas = snap.child("nasabahLunas").getValue(Int::class.java) ?: 0,
                                // ✅ TAMBAHAN: Field yang sebelumnya TIDAK diambil
                                nasabahMenunggu = snap.child("nasabahMenunggu").getValue(Int::class.java) ?: 0,
                                nasabahBaruHariIni = snap.child("nasabahBaruHariIni").getValue(Int::class.java) ?: 0,
                                nasabahLunasHariIni = snap.child("nasabahLunasHariIni").getValue(Int::class.java) ?: 0,
                                targetHariIni = snap.child("targetHariIni").getValue(Long::class.java) ?: 0L,  // ✅ INI KUNCI!
                                // Field lama
                                totalPinjamanAktif = totalPinjaman,
                                totalPiutang = snap.child("totalPiutang").getValue(Long::class.java) ?: 0L,
                                pembayaranHariIni = snap.child("pembayaranHariIni").getValue(Long::class.java) ?: 0L  // ✅ INI JUGA!
                            )
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e("AdminSummary", "❌ Error loading admin $adminUid: ${e.message}")
                        null
                    }
                }
            }

            // Tunggu semua concurrent jobs selesai
            adminJobs.awaitAll().filterNotNull().let {
                adminSummaries.addAll(it)
            }

            _adminSummary.value = adminSummaries
            Log.d("PimpinanSummary", "✅ Loaded ${adminSummaries.size} admin summaries for $cabangId")

        } catch (e: Exception) {
            Log.e("PimpinanSummary", "❌ Error in loadAdminSummariesForCabang: ${e.message}")
        }
    }

    private fun initPengawasListeners() {
        viewModelScope.launch {
            try {
                isLoading.value = true

                // OPTIMASI: Load hanya global summary
                val result = smartLoader.loadDataForPengawas(forceRefresh = false)

                if (result.success && result.globalSummary != null) {
                    _globalSummary.value = GlobalSummary(
                        totalNasabah = result.globalSummary.totalNasabah,
                        totalPinjamanAktif = result.globalSummary.totalPinjamanAktif,
                        totalTunggakan = result.globalSummary.totalTunggakan,
                        pembayaranHariIni = result.globalSummary.pembayaranHariIni,
                        lastUpdated = System.currentTimeMillis()
                    )

                    loadDashboardData()
                    _isDataLoaded.value = true
                    Log.d("PengawasListener", "✅ Global summary loaded")
                } else {
                    Log.e("PengawasListener", "❌ Error: ${result.error}")
                }

            } catch (e: Exception) {
                Log.e("PengawasListener", "❌ Exception: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }

        // Setup listener ringan untuk update summary
        val globalRef = database.child("summary").child("global").child("lastUpdated")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lastUpdated = snapshot.getValue(Long::class.java) ?: 0L
                val currentLastUpdated = _globalSummary.value?.lastUpdated ?: 0L

                if (lastUpdated > currentLastUpdated) {
                    viewModelScope.launch {
                        val result = smartLoader.loadDataForPengawas(forceRefresh = true)
                        if (result.success && result.globalSummary != null) {
                            _globalSummary.value = GlobalSummary(
                                totalNasabah = result.globalSummary.totalNasabah,
                                totalPinjamanAktif = result.globalSummary.totalPinjamanAktif,
                                totalTunggakan = result.globalSummary.totalTunggakan,
                                pembayaranHariIni = result.globalSummary.pembayaranHariIni,
                                lastUpdated = lastUpdated
                            )
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        globalRef.addValueEventListener(listener)
        roleBasedListenerRefs.add(globalRef to listener)
    }

    fun loadAdminNasabahPaginated(adminUid: String, lastKey: String? = null, limit: Int = 50, onResult: ((List<Pelanggan>, String?) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val query = if (lastKey.isNullOrBlank()) {
                    database.child("pelanggan").child(adminUid).orderByKey().limitToFirst(limit)
                } else {
                    database.child("pelanggan").child(adminUid).orderByKey().startAfter(lastKey).limitToFirst(limit)
                }

                val snapshot = query.get().await()
                val nasabahList = mutableListOf<Pelanggan>()
                var newLastKey: String? = null

                for (child in snapshot.children) {
                    val p = child.getValue(Pelanggan::class.java)
                    if (p != null) {
                        nasabahList.add(p)
                        newLastKey = child.key
                    }
                }

                onResult?.invoke(nasabahList, newLastKey)
                Log.d("Pagination", "Loaded ${nasabahList.size} nasabah for admin $adminUid (lastKey=$newLastKey)")
            } catch (e: Exception) {
                Log.e("Pagination", "Error loading paginated nasabah: ${e.message}")
                onResult?.invoke(emptyList(), null)
            }
        }
    }

    fun triggerSummaryUpdateFromClient(onComplete: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                Log.d("CloudFunction", "🔄 Requesting summary update from pengawas...")

                val result = Firebase.functions
                    .getHttpsCallable("updateAllSummaries")
                    .call()
                    .await()

                val resultMessage = result.data?.toString() ?: "no-data"
                Log.d("CloudFunction", "updateAllSummaries result: $resultMessage")

                // ✅ PERBAIKAN: Beri feedback yang lebih informatif
                if (resultMessage.contains("success", ignoreCase = true)) {
                    onComplete?.invoke(true, "Summary update requested successfully")
                } else {
                    onComplete?.invoke(true, "Update request sent (executed by pengawas)")
                }
            } catch (e: Exception) {
                Log.e("CloudFunction", "❌ Error trigger updateAllSummaries: ${e.message}")

                // ✅ FALLBACK: Jika Cloud Function gagal, tetap lanjut dengan refresh lokal
                val errorMessage = when {
                    e.message?.contains("permission", ignoreCase = true) == true ->
                        "Only pengawas can execute summary update. Continuing with local refresh..."
                    e.message?.contains("unauthenticated", ignoreCase = true) == true ->
                        "Authentication required. Continuing with local refresh..."
                    else ->
                        "Cloud function failed: ${e.message}. Continuing with local refresh..."
                }

                onComplete?.invoke(false, errorMessage)
            }
        }
    }

    private fun resolveRoleAndCabang(uid: String, callback: (role: String, cabang: String?, adminList: List<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val adminSnap = database.child("metadata").child("admins").child(uid).get().await()
                val role = adminSnap.child("role").getValue(String::class.java) ?: run {
                    // fallback: cek roles nodes
                    val isPengawas = database.child("metadata").child("roles").child("pengawas").child(uid).get().await().exists()
                    val isKoord = database.child("metadata").child("roles").child("koordinator").child(uid).get().await().exists()
                    when {
                        isPengawas -> "pengawas"
                        isKoord -> "koordinator"
                        else -> "admin"
                    }
                }
                val cabang = adminSnap.child("cabang").getValue(String::class.java) // may be null for pengawas/koordinator

                if (role == "pimpinan" && cabang != null) {
                    val adminListSnap = database.child("metadata").child("cabang").child(cabang).child("adminList").get().await()
                    val list = mutableListOf<String>()
                    adminListSnap.children.forEach { list.add(it.getValue(String::class.java) ?: "") }
                    callback(role, cabang, list.filter { it.isNotBlank() })
                } else {
                    // admin -> only itself; pengawas/koordinator -> empty list (they don't load customers)
                    if (role == "admin") callback(role, cabang, listOf(uid))
                    else callback(role, cabang, emptyList())
                }
            } catch (e: Exception) {
                Log.e("RoleDetect", "Error resolving role/cabang: ${e.message}")
                // safe fallback: treat as admin on its own
                callback("admin", null, listOf(uid))
            }
        }
    }

    fun setupRealtimePendingApprovals(cabangId: String) {
        // ✅ OPTIMASI: Cegah setup duplikat
        if (_isPendingApprovalListenerActive) {
            Log.d("PendingApproval", "⚠️ Listener already active, skipping setup")
            return
        }

        Log.d("PendingApproval", "🔄 Setting up realtime listener for cabang: $cabangId")

        val approvalRef = database.child("pengajuan_approval").child(cabangId)
            .orderByChild("status")
            .equalTo("Menunggu Approval")

        pengajuanApprovalListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                viewModelScope.launch {
                    try {
                        val result = smartLoader.loadPendingApprovalsOptimized(cabangId, forceRefresh = true)
                        _pendingApprovals.clear()
                        _pendingApprovals.addAll(result)
                        Log.d("PendingApproval", "🔄 Realtime update: ${result.size} pending approvals")
                    } catch (e: Exception) {
                        Log.e("PendingApproval", "Error: ${e.message}")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("PendingApproval", "Listener cancelled: ${error.message}")
                _isPendingApprovalListenerActive = false
            }
        }

        approvalRef.addValueEventListener(pengajuanApprovalListener!!)
        roleBasedListenerRefs.add(approvalRef to pengajuanApprovalListener!!)

        // ✅ Mark listener as active
        _isPendingApprovalListenerActive = true
    }

    fun loadPimpinanDashboardData(cabangId: String) {
        viewModelScope.launch {
            try {
                isLoading.value = true
                Log.d("PimpinanDashboard", "🔄 Loading dashboard data for cabang: $cabangId")

                // 1. Load admin summaries dan pending approvals (ringan)
                val result = smartLoader.loadDataForPimpinan(cabangId, forceRefresh = false)
                if (result.success) {
                    _adminSummary.value = result.adminSummaries
                    _pendingApprovals.clear()
                    _pendingApprovals.addAll(result.pendingApprovals)

                    // Update cabang summary
                    result.cabangSummary?.let {
                        _cabangSummary.value = CabangSummary(
                            cabangId = it.cabangId,
                            totalNasabah = it.totalNasabah,
                            nasabahAktif = it.nasabahAktif,
                            totalPinjamanAktif = it.totalPinjamanAktif
                        )
                    }

                    Log.d("PimpinanDashboard", "✅ Loaded ${result.adminSummaries.size} admin summaries")
                }

                // 2. Load data pelanggan untuk perhitungan performa (dengan cache)
                loadPelangganForPimpinanWithCache(cabangId)

                // 3. Load dashboard data (hitung dari data yang sudah ada)
                loadDashboardData()

                // 4. Refresh admin summary dengan data terbaru
                refreshAdminSummary()

                // 5. Setup listener untuk pending approvals (hanya sekali)
                if (!_isPendingApprovalListenerActive) {
                    setupRealtimePendingApprovals(cabangId)
                }

                _isDataLoaded.value = true
                Log.d("PimpinanDashboard", "✅ Dashboard data loaded successfully")

            } catch (e: Exception) {
                Log.e("PimpinanDashboard", "❌ Error loading dashboard: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }
    }

    fun simpanKePengajuanApproval(pelanggan: Pelanggan, cabangId: String) {
        if (cabangId.isBlank()) return

        val approvalRef = database.child("pengajuan_approval").child(cabangId)

        // ✅ PERBAIKAN: Cek dulu apakah entry dengan pelangganId yang sama sudah ada
        approvalRef.orderByChild("pelangganId").equalTo(pelanggan.id)
            .get()
            .addOnSuccessListener { snapshot ->
                val pengajuanData = mutableMapOf<String, Any?>(
                    "adminUid" to pelanggan.adminUid,
                    "nama" to pelanggan.namaKtp,
                    "namaPanggilan" to pelanggan.namaPanggilan,
                    "tanggalPengajuan" to pelanggan.tanggalPengajuan,
                    "status" to "Menunggu Approval",
                    "jenisPinjaman" to if (pelanggan.besarPinjaman >= 3000000) "diatas_3jt" else "dibawah_3jt",
                    "besarPinjaman" to pelanggan.besarPinjaman,
                    "tenor" to pelanggan.tenor,
                    "pinjamanKe" to pelanggan.pinjamanKe,
                    "pelangganId" to pelanggan.id,
                    "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
                )

                if (pelanggan.besarPinjaman >= DualApprovalThreshold.MINIMUM_AMOUNT) {
                    val existingDualInfo = pelanggan.dualApprovalInfo
                    val existingPhase = existingDualInfo?.approvalPhase ?: ""
                    // Hanya reset ke Phase 1 jika belum ada proses approval yang berjalan.
                    // Jika sudah di phase lanjutan (pimpinan sudah aksi), jangan overwrite
                    // karena bisa menyebabkan Cloud Function membaca status "pending" yang salah.
                    val shouldReset = existingDualInfo == null ||
                        existingPhase.isEmpty() || existingPhase == ApprovalPhase.AWAITING_PIMPINAN
                    if (shouldReset) {
                        pengajuanData["dualApprovalInfo"] = mapOf(
                            "requiresDualApproval" to true,
                            "approvalPhase" to ApprovalPhase.AWAITING_PIMPINAN,
                            "pimpinanApproval" to mapOf(
                                "status" to "pending", "by" to "", "uid" to "",
                                "timestamp" to 0, "note" to "",
                                "adjustedAmount" to 0, "adjustedTenor" to 0
                            ),
                            "koordinatorApproval" to mapOf(
                                "status" to "pending", "by" to "", "uid" to "",
                                "timestamp" to 0, "note" to "",
                                "adjustedAmount" to 0, "adjustedTenor" to 0
                            ),
                            "pengawasApproval" to mapOf(
                                "status" to "pending", "by" to "", "uid" to "",
                                "timestamp" to 0, "note" to "",
                                "adjustedAmount" to 0, "adjustedTenor" to 0
                            ),
                            "finalDecision" to "",
                            "finalDecisionBy" to "",
                            "finalDecisionTimestamp" to 0,
                            "rejectionReason" to "",
                            "koordinatorFinalConfirmed" to false,
                            "koordinatorFinalTimestamp" to 0,
                            "pimpinanFinalConfirmed" to false,
                            "pimpinanFinalTimestamp" to 0
                        )
                    } else {
                        // Sudah di phase lanjutan - pertahankan state approval yang ada
                        pengajuanData["dualApprovalInfo"] = buildDualApprovalInfoMap(existingDualInfo!!)
                    }
                } else {
                    // Untuk < 3jt, hapus dualApprovalInfo lama jika ada
                    pengajuanData["dualApprovalInfo"] = null
                }

                if (snapshot.exists() && snapshot.childrenCount > 0) {
                    // ✅ Entry sudah ada → update entry pertama yang ditemukan, abaikan duplikat
                    val existingKey = snapshot.children.first().key
                    if (existingKey != null) {
                        approvalRef.child(existingKey).setValue(pengajuanData)
                            .addOnSuccessListener {
                                Log.d("Pengajuan", "✅ Updated existing pengajuan_approval/$cabangId/$existingKey")
                            }
                            .addOnFailureListener { e ->
                                Log.e("Pengajuan", "❌ Gagal update pengajuan_approval: ${e.message}")
                            }
                    }

                    // ✅ Bersihkan entry duplikat jika ada lebih dari 1
                    if (snapshot.childrenCount > 1) {
                        var isFirst = true
                        snapshot.children.forEach { child ->
                            if (isFirst) {
                                isFirst = false
                            } else {
                                child.ref.removeValue()
                                Log.d("Pengajuan", "🗑️ Hapus duplikat: ${child.key}")
                            }
                        }
                    }
                } else {
                    // ✅ Belum ada → buat baru dengan push()
                    val pengajuanRef = approvalRef.push()
                    val pengajuanId = pengajuanRef.key ?: return@addOnSuccessListener

                    pengajuanRef.setValue(pengajuanData)
                        .addOnSuccessListener {
                            Log.d("Pengajuan", "✅ Berhasil menyimpan ke pengajuan_approval/$cabangId/$pengajuanId")
                        }
                        .addOnFailureListener { e ->
                            Log.e("Pengajuan", "❌ Gagal menyimpan ke pengajuan_approval: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                // ✅ PERBAIKAN: Gunakan pelangganId sebagai key (bukan push) untuk mencegah duplikat
                Log.w("Pengajuan", "⚠️ Gagal cek duplikat, gunakan pelangganId sebagai key: ${e.message}")

                val pengajuanData = mutableMapOf<String, Any?>(
                    "adminUid" to pelanggan.adminUid,
                    "nama" to pelanggan.namaKtp,
                    "namaPanggilan" to pelanggan.namaPanggilan,
                    "tanggalPengajuan" to pelanggan.tanggalPengajuan,
                    "status" to "Menunggu Approval",
                    "jenisPinjaman" to if (pelanggan.besarPinjaman >= 3000000) "diatas_3jt" else "dibawah_3jt",
                    "besarPinjaman" to pelanggan.besarPinjaman,
                    "tenor" to pelanggan.tenor,
                    "pinjamanKe" to pelanggan.pinjamanKe,
                    "pelangganId" to pelanggan.id,
                    "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
                )

                if (pelanggan.besarPinjaman >= DualApprovalThreshold.MINIMUM_AMOUNT) {
                    val existingDualInfo2 = pelanggan.dualApprovalInfo
                    val existingPhase2 = existingDualInfo2?.approvalPhase ?: ""
                    val shouldReset2 = existingDualInfo2 == null ||
                        existingPhase2.isEmpty() || existingPhase2 == ApprovalPhase.AWAITING_PIMPINAN
                    if (shouldReset2) {
                        pengajuanData["dualApprovalInfo"] = mapOf(
                            "requiresDualApproval" to true,
                            "approvalPhase" to ApprovalPhase.AWAITING_PIMPINAN,
                            "pimpinanApproval" to mapOf(
                                "status" to "pending", "by" to "", "uid" to "",
                                "timestamp" to 0, "note" to "",
                                "adjustedAmount" to 0, "adjustedTenor" to 0
                            ),
                            "koordinatorApproval" to mapOf(
                                "status" to "pending", "by" to "", "uid" to "",
                                "timestamp" to 0, "note" to "",
                                "adjustedAmount" to 0, "adjustedTenor" to 0
                            ),
                            "pengawasApproval" to mapOf(
                                "status" to "pending", "by" to "", "uid" to "",
                                "timestamp" to 0, "note" to "",
                                "adjustedAmount" to 0, "adjustedTenor" to 0
                            ),
                            "finalDecision" to "",
                            "finalDecisionBy" to "",
                            "finalDecisionTimestamp" to 0,
                            "rejectionReason" to "",
                            "koordinatorFinalConfirmed" to false,
                            "koordinatorFinalTimestamp" to 0,
                            "pimpinanFinalConfirmed" to false,
                            "pimpinanFinalTimestamp" to 0
                        )
                    } else {
                        pengajuanData["dualApprovalInfo"] = buildDualApprovalInfoMap(existingDualInfo2!!)
                    }
                } else {
                    pengajuanData["dualApprovalInfo"] = null
                }

                // Gunakan pelangganId sebagai key — kalau sudah ada, di-overwrite (bukan duplikat baru)
                approvalRef.child(pelanggan.id).setValue(pengajuanData)
                    .addOnSuccessListener {
                        Log.d("Pengajuan", "✅ Fallback berhasil: pengajuan_approval/$cabangId/${pelanggan.id}")
                    }
                    .addOnFailureListener { e2 ->
                        Log.e("Pengajuan", "❌ Fallback gagal: ${e2.message}")
                    }
            }
    }

    fun loadPendingApprovalsOptimized(cabangId: String) {
        viewModelScope.launch {
            try {
                _pendingApprovals.clear()

                val snapshot = database.child("pengajuan_approval").child(cabangId)
                    .orderByChild("status")
                    .equalTo("Menunggu Approval")
                    .get().await()

                val pengajuanIds = mutableListOf<String>()
                val pengajuanMap = mutableMapOf<String, Map<String, Any?>>()

                for (child in snapshot.children) {
                    val data = child.value as? Map<String, Any?>
                    if (data != null) {
                        val pelangganId = data["pelangganId"] as? String
                        val timestamp = data["timestamp"] as? Long

                        // ✅ PERBAIKAN: Cek apakah Pimpinan sudah aksi
                        // ✅ PERBAIKAN: Cek apakah Pimpinan sudah aksi DAN approvalPhase masih Phase 1
                        val dualApprovalInfo = data["dualApprovalInfo"] as? Map<String, Any?>
                        val pimpinanApproval = dualApprovalInfo?.get("pimpinanApproval") as? Map<String, Any?>
                        val pimpinanStatus = pimpinanApproval?.get("status") as? String ?: ApprovalStatus.PENDING

                        // ✅ TAMBAHAN: Cek approvalPhase - hanya tampilkan jika masih Phase 1 (AWAITING_PIMPINAN)
                        val approvalPhase = dualApprovalInfo?.get("approvalPhase") as? String ?: ApprovalPhase.AWAITING_PIMPINAN

                        // Hanya tampilkan jika:
                        // 1. Pimpinan BELUM aksi (status pending), DAN
                        // 2. ApprovalPhase masih Phase 1 (AWAITING_PIMPINAN) atau kosong (single approval)
                        val isPhase1OrSingleApproval = approvalPhase == ApprovalPhase.AWAITING_PIMPINAN ||
                                approvalPhase.isEmpty() ||
                                dualApprovalInfo == null

                        if (pelangganId != null && timestamp != null && pimpinanStatus == ApprovalStatus.PENDING && isPhase1OrSingleApproval) {
                            if (!pengajuanIds.contains(pelangganId)) {
                                pengajuanIds.add(pelangganId)
                                pengajuanMap[pelangganId] = data
                            } else {
                                val existingData = pengajuanMap[pelangganId]
                                val existingTimestamp = existingData?.get("timestamp") as? Long
                                if (existingTimestamp == null || timestamp > existingTimestamp) {
                                    pengajuanMap[pelangganId] = data
                                }
                            }
                        }
                    }
                }

                if (pengajuanIds.isEmpty()) {
                    Log.d("Approval", "Tidak ada pengajuan menunggu approval di cabang $cabangId")
                    return@launch
                }

                val adminMap = mutableMapOf<String, MutableList<String>>()
                pengajuanIds.forEach { id ->
                    val adminId = pengajuanMap[id]?.get("adminUid") as? String
                    if (adminId != null) {
                        adminMap.getOrPut(adminId) { mutableListOf() }.add(id)
                    }
                }

                val jobs = adminMap.map { (adminId, ids) ->
                    async(Dispatchers.IO) {
                        try {
                            val pelangganList = mutableListOf<Pelanggan>()
                            ids.distinct().forEach { id ->
                                val snap = database.child("pelanggan").child(adminId).child(id).get().await()
                                val pelanggan = snap.getValue(Pelanggan::class.java)
                                if (pelanggan != null && pelanggan.status == "Menunggu Approval") {
                                    // ✅ PERBAIKAN: Double-check approval phase dari data pelanggan
                                    val phase = pelanggan.dualApprovalInfo?.approvalPhase ?: ""
                                    val isStillPhase1 = phase == ApprovalPhase.AWAITING_PIMPINAN ||
                                            phase.isEmpty() ||
                                            pelanggan.dualApprovalInfo == null
                                    if (isStillPhase1) {
                                        pelangganList.add(pelanggan)
                                    } else {
                                        Log.d("Approval", "⏭️ Skip ${pelanggan.namaPanggilan}: sudah di phase $phase")
                                    }
                                }
                            }
                            pelangganList
                        } catch (e: Exception) {
                            Log.e("Approval", "Error loading pelanggan: ${e.message}")
                            emptyList<Pelanggan>()
                        }
                    }
                }

                val results = jobs.awaitAll()
                _pendingApprovals.clear()
                val loadedIds = mutableSetOf<String>()
                results.forEach { list ->
                    list.distinctBy { it.id }.forEach {
                        _pendingApprovals.add(it)
                        loadedIds.add(it.id)
                    }
                }
                Log.d("Approval", "Loaded ${_pendingApprovals.size} pending approvals for cabang $cabangId")

                // ✅ SELF-HEALING: Cari pengajuan yang ada di pelanggan tapi tidak ada di pengajuan_approval
                // Ini menangani kasus dimana simpanKePengajuanApproval gagal (offline/cabangId kosong)
                try {
                    val adminListSnap = database.child("metadata").child("cabang")
                        .child(cabangId).child("adminList").get().await()
                    val adminList = adminListSnap.children.mapNotNull { it.getValue(String::class.java) }

                    for (adminUid in adminList) {
                        val pelangganSnap = database.child("pelanggan").child(adminUid)
                            .orderByChild("status").equalTo("Menunggu Approval")
                            .get().await()

                        pelangganSnap.children.forEach { child ->
                            val pelanggan = child.getValue(Pelanggan::class.java)
                            if (pelanggan != null && !loadedIds.contains(pelanggan.id)) {
                                // Cek apakah ini Phase 1 (menunggu pimpinan)
                                val phase = pelanggan.dualApprovalInfo?.approvalPhase ?: ""
                                val isPhase1 = phase == ApprovalPhase.AWAITING_PIMPINAN ||
                                        phase.isEmpty() || pelanggan.dualApprovalInfo == null

                                if (isPhase1) {
                                    Log.w("Approval", "🔧 SELF-HEALING: Ditemukan pengajuan orphan: ${pelanggan.namaPanggilan} (${pelanggan.id})")
                                    _pendingApprovals.add(pelanggan.copy(
                                        id = child.key ?: pelanggan.id,
                                        adminUid = adminUid,
                                        cabangId = cabangId
                                    ))
                                    loadedIds.add(pelanggan.id)

                                    // Auto-create pengajuan_approval entry yang hilang
                                    simpanKePengajuanApproval(pelanggan.copy(
                                        id = child.key ?: pelanggan.id,
                                        adminUid = adminUid,
                                        cabangId = cabangId
                                    ), cabangId)
                                    Log.d("Approval", "🔧 SELF-HEALING: Entry pengajuan_approval dibuat untuk: ${pelanggan.namaPanggilan}")
                                }
                            }
                        }
                    }

                    if (_pendingApprovals.size > results.flatten().size) {
                        Log.d("Approval", "🔧 SELF-HEALING: Recovered ${_pendingApprovals.size - results.flatten().size} orphaned pengajuan")
                    }
                } catch (e: Exception) {
                    Log.w("Approval", "⚠️ Self-healing check gagal (non-critical): ${e.message}")
                }
            } catch (e: Exception) {

                Log.e("Approval", "Error loading optimized approvals: ${e.message}")
            }
        }
    }

    private suspend fun findPelangganFromFirebase(pelangganId: String, cabangId: String?): Pelanggan? {
        if (cabangId == null) return null

        return try {
            // Dapatkan daftar admin di cabang
            val cabangMeta = database.child("metadata").child("cabang").child(cabangId).get().await()
            val adminList = mutableListOf<String>()
            cabangMeta.child("adminList").children.forEach {
                it.value?.toString()?.let { uid -> adminList.add(uid) }
            }

            // Cari pelanggan di setiap admin
            for (adminUid in adminList) {
                val snap = database.child("pelanggan").child(adminUid).child(pelangganId).get().await()
                if (snap.exists()) {
                    return snap.getValue(Pelanggan::class.java)
                }
            }

            // Fallback: cek di node pengajuan_approval
            val pengajuanSnap = database.child("pengajuan_approval").child(cabangId)
                .orderByChild("pelangganId")
                .equalTo(pelangganId)
                .limitToFirst(1)
                .get().await()

            if (pengajuanSnap.exists()) {
                val firstChild = pengajuanSnap.children.first()
                val data = firstChild.value as? Map<String, Any?>
                val adminUid = data?.get("adminUid") as? String

                if (adminUid != null) {
                    val pelangganSnap = database.child("pelanggan").child(adminUid).child(pelangganId).get().await()
                    if (pelangganSnap.exists()) {
                        return pelangganSnap.getValue(Pelanggan::class.java)
                    }
                }
            }

            Log.w("Approval", "🔍 Pelanggan $pelangganId tidak ditemukan di cabang $cabangId")
            null
        } catch (e: Exception) {
            Log.e("Approval", "❌ Error mencari pelanggan dari Firebase: ${e.message}")
            null
        }
    }

    fun loadPelangganPaginated(isInitialLoad: Boolean = false) {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        if (isInitialLoad) {
            isLoading.value = true
            lastLoadedKey = null
            _hasMoreData.value = true
        } else {
            if (_isLoadingMore.value || !_hasMoreData.value) return
            _isLoadingMore.value = true
        }

        resolveRoleAndCabang(currentUid) { role, cabang, adminList ->
            viewModelScope.launch {
                try {
                    if (isInitialLoad) {
                        // ✅ FIX: JANGAN clear daftarPelanggan langsung!
                        // Simpan snapshot data lokal untuk merge nanti
                        lastLoadedKey = null
                        _hasMoreData.value = true
                        pelangganPerAdmin.clear()
                    }

                    if (role == "pengawas" || role == "koordinator") {
                        Log.d("Pagination", "Role $role: hanya load summary")
                        isLoading.value = false
                        _hasMoreData.value = false
                        return@launch
                    }

                    if (adminList.isEmpty()) {
                        isLoading.value = false
                        _isLoadingMore.value = false
                        _hasMoreData.value = false
                        return@launch
                    }

                    val newData = mutableListOf<Pelanggan>()
                    var hasMore = false

                    for (adminUid in adminList) {
                        val query = if (lastLoadedKey == null) {
                            // Initial load
                            database.child("pelanggan").child(adminUid)
                                .orderByKey()
                                .limitToFirst(pageSize)
                        } else {
                            // Load more
                            database.child("pelanggan").child(adminUid)
                                .orderByKey()
                                .startAfter(lastLoadedKey)
                                .limitToFirst(pageSize)
                        }

                        val snapshot = query.get().await()
                        var count = 0

                        snapshot.children.forEach { child ->
                            try {
                                val pelanggan = child.getValue(Pelanggan::class.java)
                                if (pelanggan != null) {
                                    val safe = sanitizePelanggan(pelanggan)
                                    if (!daftarPelanggan.any { it.id == safe.id }) {
                                        newData.add(safe.copy())
                                    }
                                    lastLoadedKey = child.key
                                    count++
                                }
                            } catch (e: Exception) {
                                Log.e("Pagination", "Error parsing: ${e.message}")
                            }
                        }

                        if (count >= pageSize) {
                            hasMore = true
                        }
                    }

                    if (newData.isNotEmpty()) {
                        if (isInitialLoad) {
                            // ✅ FIX v2: Cek Room DB apakah masih ada pending pembayaran
                            val hasPendingSync = try {
                                offlineRepo.getPendingSyncCount() > 0
                            } catch (e: Exception) { false }

                            if (hasPendingSync) {
                                // Masih ada data yang belum sync ke Firebase
                                // JANGAN replace data lokal — hanya tambahkan data baru dari Firebase
                                val currentIds = daftarPelanggan.map { it.id }.toSet()
                                val trulyNewData = newData.filter { it.id !in currentIds }
                                if (trulyNewData.isNotEmpty()) {
                                    daftarPelanggan.addAll(trulyNewData.sortedBy { it.namaPanggilan })
                                }
                                Log.d("Pagination", "⚠️ Pending sync exists (${offlineRepo.getPendingSyncCount()}), keeping local data intact")
                            } else {
                                // Tidak ada pending sync — aman replace dengan data Firebase
                                // Tapi tetap pertahankan data lokal yang punya lebih banyak pembayaran
                                val localUnsyncedData = daftarPelanggan.filter { !it.isSynced }
                                daftarPelanggan.clear()
                                daftarPelanggan.addAll(newData.sortedBy { it.namaPanggilan })

                                localUnsyncedData.forEach { localPelanggan ->
                                    val existingIndex = daftarPelanggan.indexOfFirst { it.id == localPelanggan.id }
                                    if (existingIndex != -1) {
                                        val existingPaymentCount = daftarPelanggan[existingIndex].pembayaranList.sumOf { 1 + it.subPembayaran.size }
                                        val localPaymentCount = localPelanggan.pembayaranList.sumOf { 1 + it.subPembayaran.size }
                                        if (localPaymentCount > existingPaymentCount) {
                                            daftarPelanggan[existingIndex] = localPelanggan
                                            Log.d("Pagination", "📌 Kept LOCAL unsynced: ${localPelanggan.namaPanggilan}")
                                        }
                                    } else {
                                        daftarPelanggan.add(localPelanggan)
                                    }
                                }
                            }
                        } else {
                            daftarPelanggan.addAll(newData.sortedBy { it.namaPanggilan })
                        }
                    }

                    _hasMoreData.value = hasMore
                    Log.d("Pagination", "✅ Loaded ${newData.size} new nasabah. Total: ${daftarPelanggan.size}")

                } catch (e: Exception) {
                    Log.e("Pagination", "Error: ${e.message}")
                    _hasMoreData.value = false
                } finally {
                    isLoading.value = false
                    _isLoadingMore.value = false
                    simpanKeLokal() // ✅ FIX: Pastikan data ter-persist setelah reload
                }
            }
        }
    }

    fun loadMorePelanggan() {
        loadPelangganPaginated(isInitialLoad = false)
    }

    suspend fun uploadFotoKtp(
        imageUri: Uri,
        adminUid: String,
        pelangganId: String,
        jenisKtp: String = "utama"
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val storageRef = Firebase.storage.reference
                val ktpRef = storageRef.child("ktp_images/$adminUid/$pelangganId/ktp_$jenisKtp.jpg")

                // ✅ KOMPRESI ADVANCED
                val compressedImage = compressImageForKtp(imageUri)
                if (compressedImage.isEmpty()) {
                    Log.e("UploadKTP", "❌ Gagal kompresi gambar")
                    return@withContext null
                }

                // ✅ VALIDASI UKURAN SETELAH KOMPRESI
                if (compressedImage.size > 700 * 1024) { // 700KB max (dinaikkan dari 500KB)
                    Log.e("UploadKTP", "❌ Gambar masih terlalu besar: ${compressedImage.size / 1024}KB (max 700KB)")
                    return@withContext null
                }

                val metadata = StorageMetadata.Builder()
                    .setCustomMetadata("adminUid", adminUid)
                    .setCustomMetadata("pelangganId", pelangganId)
                    .setCustomMetadata("uploadedAt", System.currentTimeMillis().toString())
                    .setContentType("image/jpeg")
                    .build()

                val uploadTask = ktpRef.putBytes(compressedImage, metadata)
                val task = uploadTask.await()

                if (task.task.isSuccessful) {
                    val downloadUrl = ktpRef.downloadUrl.await()
                    Log.d("UploadKTP", "✅ Berhasil upload KTP: ${compressedImage.size / 1024}KB → $downloadUrl")
                    downloadUrl.toString()
                } else {
                    Log.e("UploadKTP", "❌ Upload gagal: ${task.task.exception?.message}")
                    null
                }
            } catch (e: Exception) {
                Log.e("UploadKTP", "❌ Exception upload KTP: ${e.message}")
                null
            }
        }
    }

    private fun compressImageForKtp(uri: Uri): ByteArray {
        return try {
            val context = getApplication<Application>().applicationContext
            var inputStream: InputStream? = null

            try {
                inputStream = context.contentResolver.openInputStream(uri)

                if (inputStream == null) {
                    Log.e("ImageCompression", "❌ Tidak bisa membuka input stream")
                    return ByteArray(0)
                }

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true // Hanya baca metadata dulu
                }

                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream.close()

                // STEP 2: Hitung sample size optimal untuk KTP
                val targetWidth = 1200 // Lebar maksimal untuk KTP (cukup untuk baca teks)
                val targetHeight = 800 // Tinggi maksimal
                options.inSampleSize = calculateOptimalSampleSize(
                    options.outWidth,
                    options.outHeight,
                    targetWidth,
                    targetHeight
                )

                // STEP 3: Dekode bitmap dengan orientasi yang benar
                options.inJustDecodeBounds = false
                inputStream = context.contentResolver.openInputStream(uri)

                // ✅ PERBAIKAN: Explicit null check
                if (inputStream == null) {
                    Log.e("ImageCompression", "❌ Tidak bisa membuka input stream kedua")
                    return ByteArray(0)
                }

                var bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                inputStream.close()

                if (bitmap == null) {
                    Log.e("ImageCompression", "❌ Gagal decode bitmap")
                    return ByteArray(0)
                }

                // STEP 4: Perbaiki orientasi gambar (jika rotated)
                bitmap = rotateBitmapIfRequired(bitmap, uri)

                // STEP 5: Kompresi dengan kualitas optimal untuk KTP
                val outputStream = ByteArrayOutputStream()

                // ✅ PERBAIKAN: Strategy kompresi bertahap dengan lebih banyak level
                var quality = 85 // Mulai dengan kualitas tinggi
                val targetSize = 500 * 1024 // Target maksimal 500KB (aman untuk upload)

                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

                // Turunkan kualitas secara bertahap sampai di bawah target
                val qualityLevels = listOf(75, 65, 55, 50, 45, 40)
                for (q in qualityLevels) {
                    if (outputStream.size() <= targetSize) break
                    outputStream.reset()
                    quality = q
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                    Log.d("ImageCompression", "📉 Trying quality $q%: ${outputStream.size() / 1024}KB")
                }

                val compressedBytes = outputStream.toByteArray()
                outputStream.close()

                // STEP 6: Cleanup
                bitmap.recycle()

                Log.d("ImageCompression", "✅ Kompresi berhasil: ${compressedBytes.size / 1024} KB (Quality: $quality%)")

                compressedBytes

            } finally {
                inputStream?.close()
            }
        } catch (e: Exception) {
            Log.e("ImageCompression", "❌ Error kompresi gambar: ${e.message}")
            ByteArray(0)
        }
    }

    private fun calculateOptimalSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        Log.d("ImageCompression", "📏 Original: ${width}x${height}, SampleSize: $inSampleSize")
        return inSampleSize
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, uri: Uri): Bitmap {
        return try {
            val context = getApplication<Application>().applicationContext
            val inputStream = context.contentResolver.openInputStream(uri)

            // ✅ PERBAIKAN: Gunakan safe call dengan let
            inputStream?.let { stream ->
                try {
                    val exifInterface = ExifInterface(stream)

                    val orientation = exifInterface.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )

                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        else -> return bitmap // Tidak perlu rotate
                    }

                    val rotatedBitmap = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )

                    bitmap.recycle()

                    rotatedBitmap
                } catch (e: Exception) {
                    Log.e("ImageCompression", "❌ Error rotate bitmap: ${e.message}")
                    bitmap // Return original jika error
                } finally {
                    stream.close()
                }
            } ?: bitmap // Return original jika inputStream null

        } catch (e: Exception) {
            Log.e("ImageCompression", "❌ Error membuka stream untuk rotate: ${e.message}")
            bitmap // Return original jika error
        }
    }

    fun getFilteredPelanggan(queryNama: String = "", filterType: String = "AKTIF"): List<Pelanggan> {
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))

        // Hitung batas 3 bulan terakhir
        val threeMonthsAgo = Calendar.getInstance().apply {
            add(Calendar.MONTH, -3)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        val filtered = daftarPelanggan.filter { pel ->
            // Filter dasar: nama harus ada, belum lunas
            val dasarOk = pel.namaKtp.isNotBlank() &&
                    !isPelangganBenarBenarLunas(pel) &&
                    (pel.statusKhusus != "MENUNGGU_PENCAIRAN" || pel.status == "Menunggu Approval" || pel.status == "Disetujui" || pel.status == "Tidak Aktif") &&
                    ((pel.status == "Aktif") ||
                            (pel.status == "Disetujui") ||
                            (pel.status == "Tidak Aktif") ||
                            (pel.status == "Menunggu Approval" && pel.pinjamanKe > 1))

            if (!dasarOk) return@filter false

            // Filter pencarian nama
            val namaOk = if (queryNama.isBlank()) true
            else pel.namaKtp.contains(queryNama, ignoreCase = true) ||
                    pel.namaPanggilan.contains(queryNama, ignoreCase = true)

            if (!namaOk) return@filter false

            // Filter berdasarkan tanggal acuan (hanya untuk status Aktif)
            if (pel.status == "Aktif") {
                val tglAcuanStr = pel.tanggalPencairan.ifBlank {
                    pel.tanggalPengajuan.ifBlank { pel.tanggalDaftar }
                }
                val isOverThreeMonths = try {
                    val acuanDate = dateFormat.parse(tglAcuanStr)
                    acuanDate != null && acuanDate.before(threeMonthsAgo)
                } catch (_: Exception) { false }

                when (filterType) {
                    "MACET_LAMA" -> isOverThreeMonths  // Hanya tampilkan yang > 3 bulan
                    else -> !isOverThreeMonths          // Default: hanya yang <= 3 bulan
                }
            } else {
                // Status selain Aktif (Disetujui, Tidak Aktif, Menunggu Approval) selalu tampil di AKTIF
                filterType != "MACET_LAMA"
            }
        }

        return filtered.sortedByDescending { pel ->
            try {
                dateFormat.parse(pel.tanggalPengajuan)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }

    private fun isPelangganBenarBenarLunas(pelanggan: Pelanggan): Boolean {
        if (pelanggan.status == "Disetujui" || pelanggan.status == "Tidak Aktif") return false
        @Suppress("UNCHECKED_CAST")
        val totalBayar = (pelanggan.pembayaranList as List<Pembayaran?>).filterNotNull().sumOf { pembayaran ->
            pembayaran.jumlah + (pembayaran.subPembayaran as List<SubPembayaran?>).filterNotNull().sumOf { sub -> sub.jumlah }
        }
        return totalBayar >= pelanggan.totalPelunasan && pelanggan.status != "Menunggu Approval"
    }

    fun ensurePimpinanDataLoaded(cabangId: String) {
        viewModelScope.launch {
            try {
                Log.d("PimpinanData", "🔄 Ensuring pimpinan data loaded for cabang: $cabangId")

                loadPendingApprovalsOptimized(cabangId)
                loadPelangganGroupedByAdmin()
                delay(1000)

                if (daftarPelanggan.isEmpty()) {
                    Log.d("CacheCheck", "🔄 Data Firebase kosong, tidak perlu clear cache")
                } else {
                    validateCacheWithFirebase(cabangId)
                }

                loadDashboardData()
                refreshAdminSummary()

                Log.d("PimpinanData", "✅ Pimpinan data load sequence completed")
            } catch (e: Exception) {
                Log.e("PimpinanData", "❌ Error ensuring pimpinan data: ${e.message}")
            }
        }
    }

    fun clearLocalData() {
        viewModelScope.launch {
            try {
                // Clear memory cache
                daftarPelanggan.clear()
                _adminSummary.value = emptyList()
                dashboardData.value = DashboardData()
                _pendingApprovals.clear()

                // Clear local file storage
                val adminUid = Firebase.auth.currentUser?.uid
                if (adminUid != null) {
                    LocalStorage.hapusDataPelanggan(getApplication(), adminUid)
                }

                Log.d("ClearData", "✅ Semua cache berhasil dibersihkan")
            } catch (e: Exception) {
                Log.e("ClearData", "❌ Gagal clear data: ${e.message}")
            }
        }
    }

    private suspend fun validateCacheWithFirebase(cabangId: String) {
        try {
            // Gunakan existing admin list dari cabang metadata (NO ADDITIONAL COST)
            val cabangMeta = database.child("metadata").child("cabang").child(cabangId).get().await()
            val adminList = mutableListOf<String>()
            cabangMeta.child("adminList").children.forEach {
                it.value?.toString()?.let { uid -> adminList.add(uid) }
            }

            // Cek hanya 1 admin sebagai sample (MINIMAL COST)
            if (adminList.isNotEmpty()) {
                val sampleAdmin = adminList.first()
                val sampleData = database.child("pelanggan").child(sampleAdmin).limitToFirst(1).get().await()

                if (!sampleData.exists()) {
                    // Firebase kosong, clear cache
                    Log.d("CacheValidation", "🔥 Firebase kosong, clearing local cache...")
                    clearLocalData()
                }
            }
        } catch (e: Exception) {
            Log.e("CacheValidation", "❌ Error validating cache: ${e.message}")
        }
    }

    fun refreshAdminData() {
        val uid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                isLoading.value = true

                // Jika online, sync dulu data yang pending
                if (smartLoader.isOnline() && _hasUnsyncedData.value) {
                    Log.d("Refresh", "🔄 Syncing offline data first...")
                    val syncResult = smartLoader.syncOfflineData(uid)
                    Log.d("Refresh", "📤 Sync result: ${syncResult.syncedCount} synced, ${syncResult.failedCount} failed")
                }

                cacheManager.invalidatePelangganCache(uid)

                val result = smartLoader.loadDataForAdmin(uid, forceRefresh = true)
                when (result) {
                    is SmartFirebaseLoader.LoadResult.Success -> {
                        daftarPelanggan.clear()
                        daftarPelanggan.addAll(result.data)
                        loadDashboardData()
                        refreshAdminSummary()

                        _hasUnsyncedData.value = result.unsyncedCount > 0

                        val sourceInfo = when (result.source) {
                            SmartFirebaseLoader.DataSource.FIREBASE -> "Firebase"
                            SmartFirebaseLoader.DataSource.MEMORY_CACHE -> "Cache"
                            SmartFirebaseLoader.DataSource.LOCAL_STORAGE -> "LocalStorage"
                        }
                        Log.d("Refresh", "✅ Admin data refreshed from $sourceInfo: ${result.data.size} items")
                    }
                    is SmartFirebaseLoader.LoadResult.Error -> {
                        Log.e("Refresh", "❌ Error: ${result.message}")
                    }
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    fun syncOfflineDataToFirebase() {
        val uid = Firebase.auth.currentUser?.uid ?: return

        if (!smartLoader.isOnline()) {
            Log.w("Sync", "⚠️ Cannot sync: Device is offline")
            return
        }

        viewModelScope.launch {
            try {
                isLoading.value = true
                Log.d("Sync", "🔄 Starting manual sync...")

                val result = smartLoader.syncOfflineData(uid)

                if (result.success) {
                    Log.d("Sync", "✅ ${result.message}")
                    _hasUnsyncedData.value = false

                    // Refresh data setelah sync
                    refreshAdminData()
                } else {
                    Log.e("Sync", "❌ ${result.message}")
                }
            } finally {
                isLoading.value = false
            }
        }
    }

    fun refreshPimpinanData() {
        val cabangId = _currentUserCabang.value ?: return

        if (!smartLoader.isOnline()) {
            Log.w("Refresh", "⚠️ Pimpinan refresh requires internet")
            return
        }

        viewModelScope.launch {
            try {
                isLoading.value = true

                // Invalidate cache
                invalidatePimpinanCache()

                // Load summary (BUKAN detail pelanggan)
                loadPelangganForPimpinanWithCache(cabangId)

                // Load pending approvals
                val result = smartLoader.loadPendingApprovalsOptimized(cabangId, forceRefresh = true)
                _pendingApprovals.clear()
                _pendingApprovals.addAll(result)

                // Load dashboard dari summary
                loadDashboardFromSummary()

                Log.d("Refresh", "✅ Pimpinan data refreshed")

            } catch (e: Exception) {
                Log.e("Refresh", "❌ Error: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }
    }

    fun smartRefresh() {
        when (_currentUserRole.value) {
            UserRole.ADMIN_LAPANGAN -> refreshAdminData()
            UserRole.PIMPINAN -> refreshPimpinanData()
            UserRole.PENGAWAS, UserRole.KOORDINATOR -> refreshPengawasData()
            else -> Log.w("Refresh", "Unknown role, skipping refresh")
        }
    }

    fun loadPelangganForAdminOnDemand(
        adminUid: String,
        onResult: (List<Pelanggan>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val result = smartLoader.loadDataForAdmin(adminUid, forceRefresh = false)
                when (result) {
                    is SmartFirebaseLoader.LoadResult.Success -> onResult(result.data)
                    is SmartFirebaseLoader.LoadResult.Error -> {
                        Log.e("OnDemand", "Error: ${result.message}")
                        onResult(emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.e("OnDemand", "Exception: ${e.message}")
                onResult(emptyList())
            }
        }
    }

    fun clearAllCaches() {
        viewModelScope.launch {
            // ✅ TAMBAHAN: Hapus FCM token saat logout/clear cache
            try {
                NotificationHelper.clearTokenOnLogout()
                Log.d("FCM", "✅ FCM token cleared")
            } catch (e: Exception) {
                Log.e("FCM", "❌ Error clearing FCM token: ${e.message}")
            }

            cacheManager.clearAllCache()
            daftarPelanggan.clear()
            pelangganPerAdmin.clear()
            _pendingApprovals.clear()
            _adminSummary.value = emptyList()
            _isDataLoaded.value = false
            Log.d("Cache", "✅ All caches cleared")
        }
    }

    fun isCurrentlyOnline(): Boolean = smartLoader.isOnline()

    private suspend fun loadPelangganForPimpinanWithCache(cabangId: String) {
        try {
            val cacheAge = System.currentTimeMillis() - _pimpinanCacheTimestamp
            if (_adminSummary.value.isNotEmpty() && cacheAge < PIMPINAN_SUMMARY_CACHE_TTL) {
                Log.d(TAG, "✅ Using cached summary (age: ${cacheAge / 1000}s)")
                return
            }

            Log.d(TAG, "📊 Loading summary for cabang: $cabangId")

            val cabangMeta = database.child("metadata").child("cabang")
                .child(cabangId).get().await()
            val adminList = cabangMeta.child("adminList").children.mapNotNull {
                it.value?.toString()
            }

            if (adminList.isEmpty()) {
                Log.w(TAG, "⚠️ No admins in cabang: $cabangId")
                _adminSummary.value = emptyList()
                return
            }

            val adminSummaries = mutableListOf<AdminSummary>()
            var needsCabangUpdate = false

            for (adminUid in adminList) {
                try {
                    val summarySnap = database.child("summary").child("perAdmin")
                        .child(adminUid).get().await()

                    val adminMeta = database.child("metadata").child("admins")
                        .child(adminUid).get().await()

                    if (summarySnap.exists()) {
                        val nasabahAktif = summarySnap.child("nasabahAktif").getValue(Int::class.java) ?: 0
                        val totalPiutang = summarySnap.child("totalPiutang").getValue(Long::class.java) ?: 0L

                        // ✅ FALLBACK: Jika summary ada tapi kosong, coba hitung dari raw data
                        if (nasabahAktif == 0 && totalPiutang == 0L) {
                            Log.w(TAG, "⚠️ Summary empty for $adminUid, checking raw data...")

                            val calculatedSummary = calculateAdminSummaryFromRawData(adminUid)
                            if (calculatedSummary != null && (calculatedSummary.nasabahAktif > 0 || calculatedSummary.totalPelanggan > 0)) {
                                Log.d(TAG, "✅ Using calculated summary for $adminUid (aktif: ${calculatedSummary.nasabahAktif}, total: ${calculatedSummary.totalPelanggan})")
                                adminSummaries.add(calculatedSummary)

                                // Update Firebase untuk next time
                                updateAdminSummaryInFirebase(adminUid, calculatedSummary)
                                needsCabangUpdate = true
                                continue
                            }
                        }

                        // Gunakan data dari summary
                        adminSummaries.add(
                            AdminSummary(
                                adminId = adminUid,
                                adminName = adminMeta.child("name").getValue(String::class.java) ?: "Admin",
                                adminEmail = adminMeta.child("email").getValue(String::class.java) ?: "",
                                cabang = cabangId,
                                totalPelanggan = summarySnap.child("totalNasabah").getValue(Int::class.java) ?: 0,
                                nasabahAktif = nasabahAktif,
                                nasabahLunas = summarySnap.child("nasabahLunas").getValue(Int::class.java) ?: 0,
                                nasabahMenunggu = summarySnap.child("nasabahMenunggu").getValue(Int::class.java) ?: 0,
                                nasabahBaruHariIni = summarySnap.child("nasabahBaruHariIni").getValue(Int::class.java) ?: 0,
                                nasabahLunasHariIni = summarySnap.child("nasabahLunasHariIni").getValue(Int::class.java) ?: 0,
                                targetHariIni = summarySnap.child("targetHariIni").getValue(Long::class.java) ?: 0L,
                                totalPinjamanAktif = summarySnap.child("totalPinjamanAktif").getValue(Long::class.java) ?: 0L,
                                totalPiutang = totalPiutang,
                                pembayaranHariIni = summarySnap.child("pembayaranHariIni").getValue(Long::class.java) ?: 0L
                            )
                        )
                    } else {
                        // ✅ Summary tidak ada sama sekali, hitung dari raw data
                        Log.w(TAG, "⚠️ No summary node for $adminUid, calculating from raw data...")

                        val calculatedSummary = calculateAdminSummaryFromRawData(adminUid)
                        if (calculatedSummary != null) {
                            Log.d(TAG, "✅ Created summary for $adminUid from raw data")
                            adminSummaries.add(calculatedSummary)

                            // Update Firebase untuk next time
                            updateAdminSummaryInFirebase(adminUid, calculatedSummary)
                            needsCabangUpdate = true
                        } else {
                            // Benar-benar tidak ada data
                            adminSummaries.add(
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
                                    totalPinjamanAktif = 0L,
                                    totalPiutang = 0L,
                                    pembayaranHariIni = 0L
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading summary for $adminUid: ${e.message}")
                }
            }

            _adminSummary.value = adminSummaries

            // ✅ Update cabang summary jika ada perubahan
            if (needsCabangUpdate) {
                updateCabangSummaryInFirebase(cabangId, adminSummaries)
            }

            // Load cabang summary
            val cabangSummarySnap = database.child("summary").child("perCabang")
                .child(cabangId).get().await()

            if (cabangSummarySnap.exists()) {
                _cabangSummary.value = CabangSummary(
                    cabangId = cabangId,
                    totalNasabah = cabangSummarySnap.child("totalNasabah").getValue(Int::class.java) ?: 0,
                    nasabahAktif = cabangSummarySnap.child("nasabahAktif").getValue(Int::class.java) ?: 0,
                    totalPinjamanAktif = cabangSummarySnap.child("totalPinjamanAktif").getValue(Long::class.java) ?: 0L
                )
            } else {
                // Fallback: hitung dari adminSummaries
                _cabangSummary.value = CabangSummary(
                    cabangId = cabangId,
                    totalNasabah = adminSummaries.sumOf { it.totalPelanggan },
                    nasabahAktif = adminSummaries.sumOf { it.nasabahAktif },
                    totalPinjamanAktif = adminSummaries.sumOf { it.totalPinjamanAktif }
                )
            }

            _pimpinanCacheTimestamp = System.currentTimeMillis()

            Log.d(TAG, "✅ Loaded summaries for ${adminSummaries.size} admins")
            Log.d(TAG, "   Total nasabah aktif: ${adminSummaries.sumOf { it.nasabahAktif }}")
            Log.d(TAG, "   Total piutang: ${adminSummaries.sumOf { it.totalPiutang }}")
            Log.d(TAG, "   Total pembayaran hari ini: ${adminSummaries.sumOf { it.pembayaranHariIni }}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error: ${e.message}")
        }
    }

    suspend fun loadNasabahForAdminOnDemand(
        adminUid: String,
        limit: Int = 50,
        startAfter: String? = null
    ): List<Pelanggan> {
        return try {
            var query = database.child("pelanggan")
                .child(adminUid)
                .orderByChild("namaPanggilan")
                .limitToFirst(limit)

            if (startAfter != null) {
                query = query.startAfter(startAfter)
            }

            val snapshot = query.get().await()

            snapshot.children.mapNotNull { child ->
                try {
                    child.getValue(Pelanggan::class.java)?.copy(id = child.key ?: "")
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading nasabah on-demand: ${e.message}")
            emptyList()
        }
    }

    suspend fun loadSingleNasabahDetail(
        adminUid: String,
        pelangganId: String
    ): Pelanggan? {
        return try {
            val snapshot = database.child("pelanggan")
                .child(adminUid)
                .child(pelangganId)
                .get().await()

            snapshot.getValue(Pelanggan::class.java)?.copy(id = pelangganId)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading single nasabah: ${e.message}")
            null
        }
    }

    suspend fun loadPelangganDetailOnDemand(
        adminUid: String,
        pelangganId: String
    ): Pelanggan? {
        return try {
            Log.d(TAG, "📥 Loading detail: $pelangganId")

            val snap = database.child("pelanggan")
                .child(adminUid)
                .child(pelangganId)
                .get().await()

            snap.getValue(Pelanggan::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error: ${e.message}")
            null
        }
    }

    suspend fun loadPelangganListForAdmin(
        adminUid: String,
        limit: Int = 50
    ): List<Pelanggan> {
        return try {
            Log.d(TAG, "📥 Loading pelanggan list for admin: $adminUid (limit: $limit)")

            val snap = database.child("pelanggan")
                .child(adminUid)
                .orderByChild("namaPanggilan")
                .limitToFirst(limit)
                .get().await()

            snap.children.mapNotNull { it.getValue(Pelanggan::class.java) }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error: ${e.message}")
            emptyList()
        }
    }

    private fun isPimpinanCacheValid(): Boolean {
        return System.currentTimeMillis() - _pimpinanCacheTimestamp < PIMPINAN_CACHE_TTL
    }

    fun invalidatePimpinanCache() {
        _pimpinanCacheTimestamp = 0L
        Log.d("PimpinanCache", "🗑️ Cache invalidated")
    }

    fun markRelatedNotificationsAsRead(pelangganId: String) {
        val pimpinanUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                Log.d("Notification", "🔔 Marking notifications for pelanggan: $pelangganId")

                // Cari semua notifikasi yang terkait dengan pelanggan ini
                database.child("admin_notifications").child(pimpinanUid)
                    .orderByChild("pelangganId")
                    .equalTo(pelangganId)
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            var markedCount = 0

                            snapshot.children.forEach { notifSnapshot ->
                                val notifId = notifSnapshot.key ?: return@forEach
                                val isRead = notifSnapshot.child("read").getValue(Boolean::class.java) ?: false

                                if (!isRead) {
                                    // Mark as read
                                    database.child("admin_notifications")
                                        .child(pimpinanUid)
                                        .child(notifId)
                                        .child("read")
                                        .setValue(true)
                                        .addOnSuccessListener {
                                            Log.d("Notification", "✅ Marked as read: $notifId")
                                        }
                                    markedCount++
                                }
                            }

                            Log.d("Notification", "✅ Marked $markedCount notifications as read for $pelangganId")

                            // Refresh notification count
                            loadAdminNotifications()
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e("Notification", "❌ Error marking notifications: ${error.message}")
                        }
                    })
            } catch (e: Exception) {
                Log.e("Notification", "❌ Error: ${e.message}")
            }
        }
    }

    fun markAllPengajuanNotificationsAsRead() {
        val pimpinanUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                Log.d("Notification", "🔔 Marking all NEW_PENGAJUAN notifications as read")

                database.child("admin_notifications").child(pimpinanUid)
                    .orderByChild("type")
                    .equalTo("NEW_PENGAJUAN")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val updates = mutableMapOf<String, Any>()

                            snapshot.children.forEach { notifSnapshot ->
                                val notifId = notifSnapshot.key ?: return@forEach
                                val isRead = notifSnapshot.child("read").getValue(Boolean::class.java) ?: false

                                if (!isRead) {
                                    updates["admin_notifications/$pimpinanUid/$notifId/read"] = true
                                }
                            }

                            if (updates.isNotEmpty()) {
                                database.updateChildren(updates)
                                    .addOnSuccessListener {
                                        Log.d("Notification", "✅ Marked ${updates.size} pengajuan notifications as read")
                                        loadAdminNotifications()
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e("Notification", "❌ Error batch update: ${e.message}")
                                    }
                            } else {
                                Log.d("Notification", "ℹ️ No unread pengajuan notifications")
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e("Notification", "❌ Error: ${error.message}")
                        }
                    })
            } catch (e: Exception) {
                Log.e("Notification", "❌ Error: ${e.message}")
            }
        }
    }

    fun triggerTargetRecalc(
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                Log.d("ViewModel", "🎯 Triggering target recalc...")

                functions.getHttpsCallable("triggerTargetRecalc")
                    .call()
                    .addOnSuccessListener { result ->
                        val data = result.data as? Map<*, *>
                        val message = data?.get("message")?.toString() ?: "Success"
                        val today = data?.get("today")?.toString() ?: ""
                        val isHoliday = data?.get("isHoliday") as? Boolean ?: false

                        Log.d("ViewModel", "✅ Target recalc success: $message")
                        Log.d("ViewModel", "📅 Today: $today, Holiday: $isHoliday")

                        // Refresh data setelah recalc
                        refreshPimpinanData()

                        onSuccess("$message\nTanggal: $today\nLibur: $isHoliday")
                    }
                    .addOnFailureListener { e ->
                        Log.e("ViewModel", "❌ Target recalc failed: ${e.message}")
                        onError(e.message ?: "Unknown error")
                    }

            } catch (e: Exception) {
                Log.e("ViewModel", "❌ Exception: ${e.message}")
                onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Trigger full recalculation (semua data)
     * Lebih lengkap tapi lebih lambat
     */
    fun triggerFullRecalc(
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                Log.d("ViewModel", "🔄 Triggering full recalc...")

                functions.getHttpsCallable("updateAllSummaries")
                    .call()
                    .addOnSuccessListener { result ->
                        val data = result.data as? Map<*, *>
                        val message = data?.get("message")?.toString() ?: "Success"

                        Log.d("ViewModel", "✅ Full recalc success: $message")

                        // Refresh data setelah recalc
                        refreshPimpinanData()

                        onSuccess(message)
                    }
                    .addOnFailureListener { e ->
                        Log.e("ViewModel", "❌ Full recalc failed: ${e.message}")
                        onError(e.message ?: "Unknown error")
                    }

            } catch (e: Exception) {
                Log.e("ViewModel", "❌ Exception: ${e.message}")
                onError(e.message ?: "Unknown error")
            }
        }
    }

    fun manualSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val currentUid = Firebase.auth.currentUser?.uid ?: return@launch
                val role = _currentUserRole.value

                Log.d("ForceRefresh", "🔄 Force full refresh started for role: $role")

                if (role == UserRole.ADMIN_LAPANGAN) {
                    // STEP 1: Sync pending operations lokal → Firebase
                    Log.d("ForceRefresh", "📤 Step 1: Syncing pending operations to Firebase...")
                    val pendingResult = offlineRepo.syncNow()
                    Log.d("ForceRefresh", "   Pending sync result: ${pendingResult.success}/${pendingResult.total}")

                    // STEP 2: Clear memory cache agar data tidak dibaca dari cache lama
                    Log.d("ForceRefresh", "🗑️ Step 2: Clearing memory cache...")
                    cacheManager.invalidatePelangganCache(currentUid)

                    // ✅ PERBAIKAN #4: JANGAN hapus local storage dulu!
                    // Download dulu, baru hapus jika berhasil
                    Log.d("ForceRefresh", "📥 Step 3: Downloading fresh data from Firebase...")
                    val result = smartLoader.loadDataForAdmin(currentUid, forceRefresh = true)

                    when (result) {
                        is SmartFirebaseLoader.LoadResult.Success -> {
                            // ✅ Download berhasil, SEKARANG aman hapus data lama
                            Log.d("ForceRefresh", "✅ Download berhasil, updating local data...")

                            // Update UI state
                            daftarPelanggan.clear()
                            daftarPelanggan.addAll(result.data.sortedBy { it.namaPanggilan })

                            // Simpan data fresh ke local storage (menimpa yang lama)
                            simpanKeLokal()

                            // Refresh dashboard
                            loadDashboardData()
                            _adminSummary.value = calculateAdminSummary(daftarPelanggan)

                            Log.d("ForceRefresh", "✅ Force refresh complete: ${result.data.size} pelanggan loaded from ${result.source}")
                        }
                        is SmartFirebaseLoader.LoadResult.Error -> {
                            // ✅ Download GAGAL, local storage TIDAK dihapus!
                            // Data tetap aman di local storage
                            Log.e("ForceRefresh", "❌ Force refresh failed: ${result.message}")
                            Log.d("ForceRefresh", "📱 Local data preserved as safety net")
                        }
                    }

                    // STEP 4: Refresh notifikasi juga
                    loadAdminNotifications()

                } else {
                    // Untuk role lain, panggil refreshDataFromFirebase() yang sudah ada
                    refreshDataFromFirebase()
                }

            } catch (e: Exception) {
                Log.e("ForceRefresh", "❌ Force refresh error: ${e.message}")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * =========================================================================
     * UPLOAD FOTO SERAH TERIMA UANG + KIRIM NOTIFIKASI KE PIMPINAN
     * =========================================================================
     */
    fun uploadFotoSerahTerima(
        pelangganId: String,
        fotoUri: Uri,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val currentUid = Firebase.auth.currentUser?.uid
        if (currentUid.isNullOrBlank()) {
            onFailure(Exception("User not authenticated"))
            return
        }

        viewModelScope.launch {
            try {
                Log.d("SerahTerima", "🚀 Starting upload foto serah terima for: $pelangganId")

                // =====================================================================
                // ✅ PERBAIKAN: Pencarian robust dari berbagai sumber
                // =====================================================================
                var pelanggan: Pelanggan? = null
                var foundAdminUid: String = currentUid

                // LANGKAH 1: Cari di daftarPelanggan lokal
                pelanggan = daftarPelanggan.firstOrNull { it.id == pelangganId }
                if (pelanggan != null) {
                    Log.d("SerahTerima", "✅ Ditemukan di daftarPelanggan lokal")
                    foundAdminUid = pelanggan.adminUid.ifBlank { currentUid }
                }

                // LANGKAH 2: Cari di pendingApprovals
                if (pelanggan == null) {
                    pelanggan = _pendingApprovals.find { it.id == pelangganId }
                    if (pelanggan != null) {
                        Log.d("SerahTerima", "✅ Ditemukan di pendingApprovals")
                        foundAdminUid = pelanggan.adminUid.ifBlank { currentUid }
                    }
                }

                // LANGKAH 3: Cari di adminNotifications (dari notifikasi approval)
                if (pelanggan == null) {
                    val notif = adminNotifications.value.find { it.pelangganId == pelangganId }
                    if (notif != null) {
                        Log.d("SerahTerima", "🔍 Ditemukan di adminNotifications: ${notif.pelangganNama}")
                        Log.d("SerahTerima", "   Mencari data lengkap dari Firebase...")
                        // AdminNotification tidak punya adminUid, jadi cari dengan currentUid dulu
                        pelanggan = findPelangganFromFirebaseByAdminUid(pelangganId, currentUid)
                        if (pelanggan != null) {
                            foundAdminUid = currentUid
                            Log.d("SerahTerima", "✅ Ditemukan di Firebase/currentUid")
                        }
                    }
                }

                // LANGKAH 4: Cari langsung di Firebase dengan currentUid
                if (pelanggan == null) {
                    Log.d("SerahTerima", "🔍 Mencari di Firebase dengan currentUid...")
                    pelanggan = findPelangganFromFirebaseByAdminUid(pelangganId, currentUid)
                    if (pelanggan != null) {
                        foundAdminUid = currentUid
                        Log.d("SerahTerima", "✅ Ditemukan di Firebase/currentUid")
                    }
                }

                // LANGKAH 5: Cari di semua admin dalam cabang yang sama
                if (pelanggan == null) {
                    Log.d("SerahTerima", "🔍 Mencari di semua admin dalam cabang...")
                    val cabangId = currentUserCabang.value
                    if (!cabangId.isNullOrBlank()) {
                        val result = findPelangganFromAllAdminsInCabang(pelangganId, cabangId)
                        pelanggan = result.first
                        foundAdminUid = result.second ?: currentUid
                        if (pelanggan != null) {
                            Log.d("SerahTerima", "✅ Ditemukan di admin: $foundAdminUid")
                        }
                    }
                }

                // LANGKAH 6: Fallback - cari di SEMUA node pelanggan
                if (pelanggan == null) {
                    Log.d("SerahTerima", "🔍 Fallback: mencari di semua node pelanggan...")
                    val result = findPelangganFromAllAdmins(pelangganId)
                    pelanggan = result.first
                    foundAdminUid = result.second ?: currentUid
                    if (pelanggan != null) {
                        Log.d("SerahTerima", "✅ Ditemukan di admin: $foundAdminUid")
                    }
                }

                // =====================================================================
                // Jika tidak ditemukan di manapun
                // =====================================================================
                if (pelanggan == null) {
                    Log.e("SerahTerima", "❌ Pelanggan tidak ditemukan di manapun: $pelangganId")
                    Log.e("SerahTerima", "   Checked: lokal, pendingApprovals, notifications, Firebase")
                    onFailure(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }

                Log.d("SerahTerima", "✅ Pelanggan ditemukan: ${pelanggan.namaPanggilan}")
                Log.d("SerahTerima", "   ID: ${pelanggan.id}")
                Log.d("SerahTerima", "   AdminUid: $foundAdminUid")

                val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("in", "ID"))
                val tanggalSerahTerima = dateFormat.format(Date())

                // Dapatkan nama admin
                val adminName = try {
                    val adminSnap = database.child("metadata/admins/$currentUid/name").get().await()
                    adminSnap.getValue(String::class.java) ?: "Admin"
                } catch (e: Exception) {
                    "Admin"
                }

                if (isOnline()) {
                    Log.d("SerahTerima", "📤 ONLINE - Uploading foto...")

                    // ✅ Gunakan foundAdminUid yang benar
                    val uploadAdminUid = if (pelanggan.adminUid.isNotBlank()) pelanggan.adminUid else foundAdminUid

                    val fotoUrl = uploadFotoKtp(
                        imageUri = fotoUri,
                        adminUid = uploadAdminUid,
                        pelangganId = pelangganId,
                        jenisKtp = "serah_terima"
                    )

                    if (fotoUrl != null) {
                        Log.d("SerahTerima", "✅ Foto uploaded: $fotoUrl")

                        val updatedPelanggan = pelanggan.copy(
                            id = pelangganId,
                            adminUid = uploadAdminUid,
                            fotoSerahTerimaUrl = fotoUrl,
                            statusSerahTerima = "Selesai",
                            tanggalSerahTerima = tanggalSerahTerima,
                            pendingFotoSerahTerimaUri = ""
                        )

                        // Update Firebase dengan adminUid yang benar
                        updatePelangganSerahTerimaWithAdminUid(updatedPelanggan, uploadAdminUid)

                        // Update local list
                        val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                        if (index != -1) {
                            daftarPelanggan[index] = updatedPelanggan
                        } else {
                            // Tambahkan ke lokal jika belum ada
                            daftarPelanggan.add(updatedPelanggan)
                        }

                        // ✅ KIRIM NOTIFIKASI KE PIMPINAN
                        sendSerahTerimaNotificationToPimpinan(
                            pelanggan = updatedPelanggan,
                            adminName = adminName,
                            fotoUrl = fotoUrl,
                            tanggalSerahTerima = tanggalSerahTerima
                        )

                        // ✅ BARU: KIRIM JUGA KE SEMUA PENGAWAS
                        sendSerahTerimaNotificationToPengawas(
                            pelanggan = updatedPelanggan,
                            adminName = adminName,
                            fotoUrl = fotoUrl,
                            tanggalSerahTerima = tanggalSerahTerima
                        )

                        // ✅ BARU: KIRIM JUGA KE SEMUA KOORDINATOR
                        sendSerahTerimaNotificationToKoordinator(
                            pelanggan = updatedPelanggan,
                            adminName = adminName,
                            fotoUrl = fotoUrl,
                            tanggalSerahTerima = tanggalSerahTerima
                        )

                        Log.d("SerahTerima", "✅ SUKSES!")
                        onSuccess()
                    } else {
                        onFailure(Exception("Gagal upload foto"))
                    }
                } else {
                    Log.d("SerahTerima", "📱 OFFLINE - Simpan untuk nanti")

                    val uploadAdminUid = if (pelanggan.adminUid.isNotBlank()) pelanggan.adminUid else foundAdminUid

                    val updatedPelanggan = pelanggan.copy(
                        id = pelangganId,
                        adminUid = uploadAdminUid,
                        pendingFotoSerahTerimaUri = fotoUri.toString(),
                        statusSerahTerima = "Pending",
                        tanggalSerahTerima = tanggalSerahTerima
                    )

                    updatePelangganSerahTerimaWithAdminUid(updatedPelanggan, uploadAdminUid)

                    val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                    if (index != -1) {
                        daftarPelanggan[index] = updatedPelanggan
                    }

                    offlineRepo.triggerSync()
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("SerahTerima", "❌ Error: ${e.message}", e)
                onFailure(e)
            }
        }
    }

    /**
     * ✅ FUNGSI BARU: Cari pelanggan dari Firebase dengan adminUid tertentu
     */
    private suspend fun findPelangganFromFirebaseByAdminUid(pelangganId: String, adminUid: String): Pelanggan? {
        return try {
            val snapshot = database.child("pelanggan")
                .child(adminUid)
                .child(pelangganId)
                .get()
                .await()

            if (snapshot.exists()) {
                val pelanggan = snapshot.getValue(Pelanggan::class.java)
                pelanggan?.copy(
                    id = pelangganId,
                    adminUid = adminUid
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SerahTerima", "Error finding from Firebase: ${e.message}")
            null
        }
    }

    /**
     * ✅ FUNGSI BARU: Cari pelanggan dari semua admin dalam cabang tertentu
     * Returns Pair<Pelanggan?, AdminUid?>
     */
    private suspend fun findPelangganFromAllAdminsInCabang(pelangganId: String, cabangId: String): Pair<Pelanggan?, String?> {
        return try {
            // Dapatkan daftar admin di cabang
            val cabangMeta = database.child("metadata").child("cabang").child(cabangId).get().await()
            val adminList = mutableListOf<String>()
            cabangMeta.child("adminList").children.forEach {
                it.value?.toString()?.let { uid -> adminList.add(uid) }
            }

            Log.d("SerahTerima", "📋 Mencari di ${adminList.size} admin dalam cabang $cabangId")

            // Cari pelanggan di setiap admin
            for (adminUid in adminList) {
                val snap = database.child("pelanggan").child(adminUid).child(pelangganId).get().await()
                if (snap.exists()) {
                    val pelanggan = snap.getValue(Pelanggan::class.java)
                    if (pelanggan != null) {
                        return Pair(
                            pelanggan.copy(id = pelangganId, adminUid = adminUid),
                            adminUid
                        )
                    }
                }
            }

            Pair(null, null)
        } catch (e: Exception) {
            Log.e("SerahTerima", "Error finding from cabang admins: ${e.message}")
            Pair(null, null)
        }
    }

    /**
     * ✅ FUNGSI BARU: Cari pelanggan dari SEMUA admin (fallback terakhir)
     * Returns Pair<Pelanggan?, AdminUid?>
     */
    private suspend fun findPelangganFromAllAdmins(pelangganId: String): Pair<Pelanggan?, String?> {
        return try {
            val adminsSnap = database.child("pelanggan").get().await()

            for (adminSnap in adminsSnap.children) {
                val adminUid = adminSnap.key ?: continue
                val pelangganSnap = adminSnap.child(pelangganId)

                if (pelangganSnap.exists()) {
                    val pelanggan = pelangganSnap.getValue(Pelanggan::class.java)
                    if (pelanggan != null) {
                        Log.d("SerahTerima", "✅ Ditemukan di admin: $adminUid")
                        return Pair(
                            pelanggan.copy(id = pelangganId, adminUid = adminUid),
                            adminUid
                        )
                    }
                }
            }

            Pair(null, null)
        } catch (e: Exception) {
            Log.e("SerahTerima", "Error finding from all admins: ${e.message}")
            Pair(null, null)
        }
    }

    /**
     * ✅ FUNGSI BARU: Update pelanggan serah terima dengan adminUid yang benar
     */
    private suspend fun updatePelangganSerahTerimaWithAdminUid(pelanggan: Pelanggan, adminUid: String) {
        try {
            val updates = mapOf(
                "fotoSerahTerimaUrl" to pelanggan.fotoSerahTerimaUrl,
                "pendingFotoSerahTerimaUri" to pelanggan.pendingFotoSerahTerimaUri,
                "statusSerahTerima" to pelanggan.statusSerahTerima,
                "tanggalSerahTerima" to pelanggan.tanggalSerahTerima,
                "lastUpdated" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )

            database.child("pelanggan")
                .child(adminUid)
                .child(pelanggan.id)
                .updateChildren(updates)
                .await()

            Log.d("SerahTerima", "✅ Firebase updated for admin: $adminUid")

        } catch (e: Exception) {
            Log.e("SerahTerima", "❌ Error updating Firebase: ${e.message}")
            throw e
        }
    }

    /**
     * Update data pelanggan untuk serah terima ke Firebase
     */
    private suspend fun updatePelangganSerahTerima(pelanggan: Pelanggan) {
        try {
            val pelangganRef = database.child("pelanggan")
                .child(pelanggan.adminUid)
                .child(pelanggan.id)

            val updates = mapOf(
                "fotoSerahTerimaUrl" to pelanggan.fotoSerahTerimaUrl,
                "pendingFotoSerahTerimaUri" to pelanggan.pendingFotoSerahTerimaUri,
                "statusSerahTerima" to pelanggan.statusSerahTerima,
                "tanggalSerahTerima" to pelanggan.tanggalSerahTerima,
                "lastUpdated" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            )

            pelangganRef.updateChildren(updates).await()
            Log.d("SerahTerima", "✅ Firebase updated")
        } catch (e: Exception) {
            Log.e("SerahTerima", "❌ Error: ${e.message}")
            throw e
        }
    }

    /**
     * Kirim notifikasi serah terima ke Pimpinan
     */
    private suspend fun sendSerahTerimaNotificationToPimpinan(
        pelanggan: Pelanggan,
        adminName: String,
        fotoUrl: String,
        tanggalSerahTerima: String
    ) {
        try {
            val cabangId = pelanggan.cabangId
            if (cabangId.isBlank()) {
                Log.e("SerahTerima", "❌ CabangId kosong, tidak bisa kirim notifikasi")
                return
            }

            Log.d("SerahTerima", "🔍 Mencari pimpinan untuk cabang: $cabangId")

            // ✅ PATH YANG BENAR: metadata/roles/pimpinan/{cabangId}
            // Struktur: metadata/roles/pimpinan/payakumbuh: "pimpinanUid123"
            var pimpinanUid: String? = null

            // Coba path utama: metadata/roles/pimpinan/{cabangId}
            try {
                val pimpinanSnapshot = database.child("metadata")
                    .child("roles")
                    .child("pimpinan")
                    .child(cabangId)
                    .get()
                    .await()

                pimpinanUid = pimpinanSnapshot.getValue(String::class.java)
                Log.d("SerahTerima", "📍 Path 1 (metadata/roles/pimpinan/$cabangId): $pimpinanUid")
            } catch (e: Exception) {
                Log.w("SerahTerima", "⚠️ Path 1 gagal: ${e.message}")
            }

            // Fallback 1: metadata/cabang/{cabangId}/pimpinan
            if (pimpinanUid.isNullOrBlank()) {
                try {
                    val fallback1 = database.child("metadata")
                        .child("cabang")
                        .child(cabangId)
                        .child("pimpinan")
                        .get()
                        .await()

                    pimpinanUid = fallback1.getValue(String::class.java)
                    Log.d("SerahTerima", "📍 Path 2 (metadata/cabang/$cabangId/pimpinan): $pimpinanUid")
                } catch (e: Exception) {
                    Log.w("SerahTerima", "⚠️ Path 2 gagal: ${e.message}")
                }
            }

            // Fallback 2: metadata/cabang/{cabangId}/pimpinanUid
            if (pimpinanUid.isNullOrBlank()) {
                try {
                    val fallback2 = database.child("metadata")
                        .child("cabang")
                        .child(cabangId)
                        .child("pimpinanUid")
                        .get()
                        .await()

                    pimpinanUid = fallback2.getValue(String::class.java)
                    Log.d("SerahTerima", "📍 Path 3 (metadata/cabang/$cabangId/pimpinanUid): $pimpinanUid")
                } catch (e: Exception) {
                    Log.w("SerahTerima", "⚠️ Path 3 gagal: ${e.message}")
                }
            }

            // Fallback 3: Iterasi metadata/roles/pimpinan untuk cari cabang yang cocok
            if (pimpinanUid.isNullOrBlank()) {
                try {
                    Log.d("SerahTerima", "🔄 Fallback 4: Iterasi semua pimpinan...")
                    val allPimpinanSnapshot = database.child("metadata")
                        .child("roles")
                        .child("pimpinan")
                        .get()
                        .await()

                    for (child in allPimpinanSnapshot.children) {
                        val key = child.key
                        val value = child.getValue(String::class.java)
                        Log.d("SerahTerima", "   Found: $key -> $value")

                        // Cek apakah key cocok dengan cabangId (case insensitive)
                        if (key?.lowercase() == cabangId.lowercase()) {
                            pimpinanUid = value
                            Log.d("SerahTerima", "✅ Match found: $key = $pimpinanUid")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SerahTerima", "❌ Fallback 4 gagal: ${e.message}")
                }
            }

            // Jika masih tidak ditemukan
            if (pimpinanUid.isNullOrBlank()) {
                Log.e("SerahTerima", "❌ Pimpinan tidak ditemukan untuk cabang: $cabangId")
                Log.e("SerahTerima", "   Checked paths:")
                Log.e("SerahTerima", "   1. metadata/roles/pimpinan/$cabangId")
                Log.e("SerahTerima", "   2. metadata/cabang/$cabangId/pimpinan")
                Log.e("SerahTerima", "   3. metadata/cabang/$cabangId/pimpinanUid")
                Log.e("SerahTerima", "   4. Iterasi metadata/roles/pimpinan/*")
                Log.e("SerahTerima", "")
                Log.e("SerahTerima", "   💡 SOLUSI: Pastikan ada data pimpinan di Firebase!")
                Log.e("SerahTerima", "   Contoh struktur yang benar:")
                Log.e("SerahTerima", "   metadata/roles/pimpinan/payakumbuh: \"uid_pimpinan_123\"")
                return
            }

            Log.d("SerahTerima", "✅ Pimpinan ditemukan: $pimpinanUid untuk cabang: $cabangId")
            Log.d("SerahTerima", "📨 Mengirim notifikasi ke pimpinan: $pimpinanUid")

            val notificationId = "serah_terima_${pelanggan.id}_${System.currentTimeMillis()}"

            val notification = SerahTerimaNotification(
                id = notificationId,
                type = "SERAH_TERIMA",
                title = "Bukti Serah Terima Uang",
                message = "Admin $adminName telah menyerahkan uang pinjaman kepada ${pelanggan.namaPanggilan}",
                pelangganId = pelanggan.id,
                pelangganNama = pelanggan.namaPanggilan,
                adminUid = pelanggan.adminUid,
                adminName = adminName,
                besarPinjaman = pelanggan.besarPinjaman,
                tenor = pelanggan.tenor,
                fotoSerahTerimaUrl = fotoUrl,
                tanggalSerahTerima = tanggalSerahTerima,
                timestamp = System.currentTimeMillis(),
                read = false
            )

            // Simpan ke Firebase
            database.child("serah_terima_notifications")
                .child(pimpinanUid)
                .child(notificationId)
                .setValue(notification)
                .await()

            Log.d("SerahTerima", "✅ Notifikasi berhasil dikirim ke pimpinan: $pimpinanUid")
            Log.d("SerahTerima", "   Path: serah_terima_notifications/$pimpinanUid/$notificationId")

        } catch (e: Exception) {
            Log.e("SerahTerima", "❌ Gagal kirim notifikasi: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Load notifikasi serah terima untuk Pimpinan
     */
    fun loadSerahTerimaNotifications() {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                Log.d("SerahTerima", "📥 Loading serah terima notifications (optimized)")

                // ✅ OPTIMASI 1: Limit hanya 50 notifikasi terbaru
                val snapshot = database.child("serah_terima_notifications")
                    .child(currentUid)
                    .orderByChild("timestamp")
                    .limitToLast(50)  // ← HEMAT: Hanya ambil 50 terbaru
                    .get()  // ← HEMAT: Single read, bukan listener
                    .await()

                val notifications = mutableListOf<SerahTerimaNotification>()
                var unreadCount = 0

                for (child in snapshot.children) {
                    try {
                        val notif = child.getValue(SerahTerimaNotification::class.java)
                        if (notif != null) {
                            notifications.add(notif)
                            if (!notif.read) unreadCount++
                        }
                    } catch (e: Exception) {
                        Log.e("SerahTerima", "Error parsing: ${e.message}")
                    }
                }

                // Sort descending (newest first)
                val sortedList = notifications.sortedByDescending { it.timestamp }

                _serahTerimaNotifications.value = sortedList
                _unreadSerahTerimaCount.value = unreadCount

                Log.d("SerahTerima", "✅ Loaded ${sortedList.size} notifications, $unreadCount unread")

                // ✅ OPTIMASI 2: Cleanup notifikasi lama (> 30 hari yang sudah dibaca)
                cleanupOldSerahTerimaNotifications(currentUid)

            } catch (e: Exception) {
                Log.e("SerahTerima", "❌ Error: ${e.message}")
            }
        }
    }

    private fun cleanupOldSerahTerimaNotifications(pimpinanUid: String) {
        viewModelScope.launch {
            try {
                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)

                val oldNotifications = database.child("serah_terima_notifications")
                    .child(pimpinanUid)
                    .orderByChild("timestamp")
                    .endAt(thirtyDaysAgo.toDouble())
                    .get()
                    .await()

                var deletedCount = 0
                for (child in oldNotifications.children) {
                    val isRead = child.child("read").getValue(Boolean::class.java) ?: false
                    if (isRead) {
                        // Hapus notifikasi yang sudah dibaca dan > 30 hari
                        child.ref.removeValue()
                        deletedCount++
                    }
                }

                if (deletedCount > 0) {
                    Log.d("SerahTerima", "🗑️ Cleaned up $deletedCount old notifications")
                }
            } catch (e: Exception) {
                Log.w("SerahTerima", "Cleanup failed: ${e.message}")
            }
        }
    }

    fun refreshSerahTerimaNotifications() {
        loadSerahTerimaNotifications()
    }

    private var serahTerimaListener: ValueEventListener? = null

    fun startSerahTerimaRealtimeListener() {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        // Remove existing listener
        stopSerahTerimaRealtimeListener()

        serahTerimaListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Hanya update count, tidak download semua data
                var unreadCount = 0
                for (child in snapshot.children) {
                    val isRead = child.child("read").getValue(Boolean::class.java) ?: false
                    if (!isRead) unreadCount++
                }
                _unreadSerahTerimaCount.value = unreadCount
                Log.d("SerahTerima", "🔔 Unread count updated: $unreadCount")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("SerahTerima", "Listener cancelled: ${error.message}")
            }
        }

        // ✅ HEMAT: Hanya listen untuk unread count, bukan semua data
        database.child("serah_terima_notifications")
            .child(currentUid)
            .orderByChild("read")
            .equalTo(false)
            .addValueEventListener(serahTerimaListener!!)
    }

    fun stopSerahTerimaRealtimeListener() {
        val currentUid = Firebase.auth.currentUser?.uid ?: return
        serahTerimaListener?.let {
            database.child("serah_terima_notifications")
                .child(currentUid)
                .removeEventListener(it)
        }
        serahTerimaListener = null
    }

    /**
     * Mark notifikasi serah terima sebagai sudah dibaca
     */
    fun markSerahTerimaNotificationAsRead(notificationId: String) {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                database.child("serah_terima_notifications")
                    .child(currentUid)
                    .child(notificationId)
                    .child("read")
                    .setValue(true)
                    .await()

                Log.d("SerahTerima", "✅ Notification marked as read: $notificationId")
            } catch (e: Exception) {
                Log.e("SerahTerima", "❌ Error marking as read: ${e.message}")
            }
        }
    }

    /**
     * Mark semua notifikasi serah terima sebagai sudah dibaca
     */
    fun markAllSerahTerimaAsRead() {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                val snapshot = database.child("serah_terima_notifications")
                    .child(currentUid)
                    .get()
                    .await()

                val updates = mutableMapOf<String, Any>()
                for (child in snapshot.children) {
                    val isRead = child.child("read").getValue(Boolean::class.java) ?: false
                    if (!isRead) {
                        updates["serah_terima_notifications/$currentUid/${child.key}/read"] = true
                    }
                }

                if (updates.isNotEmpty()) {
                    database.updateChildren(updates).await()
                    Log.d("SerahTerima", "✅ All notifications marked as read")
                }
            } catch (e: Exception) {
                Log.e("SerahTerima", "❌ Error: ${e.message}")
            }
        }
    }

    suspend fun validateNikAcrossCabang(
        nik: String = "",
        nikSuami: String = "",
        nikIstri: String = "",
        currentCabangId: String,
        excludeAdminUid: String? = null
    ): NikValidationResult {
        return withContext(Dispatchers.IO) {
            try {
                if (nik.isBlank() && nikSuami.isBlank() && nikIstri.isBlank()) {
                    return@withContext NikValidationResult(
                        isValid = false,
                        message = "NIK tidak boleh kosong"
                    )
                }

                if (currentCabangId.isBlank()) {
                    Log.w("NikValidation", "⚠️ CabangId kosong, skip validasi lintas admin")
                    return@withContext NikValidationResult(isValid = true)
                }

                Log.d("NikValidation", "🔍 Memulai validasi NIK di cabang: $currentCabangId")

                // STEP 1: Dapatkan semua admin dalam cabang yang sama
                val adminsInCabang = mutableListOf<String>()

                val adminsSnapshot = database.child("metadata").child("admins")
                    .orderByChild("cabang")
                    .equalTo(currentCabangId)
                    .get()
                    .await()

                for (adminChild in adminsSnapshot.children) {
                    val adminUid = adminChild.key ?: continue
                    if (excludeAdminUid != null && adminUid == excludeAdminUid) {
                        continue
                    }
                    adminsInCabang.add(adminUid)
                }

                if (adminsInCabang.isEmpty()) {
                    return@withContext NikValidationResult(isValid = true)
                }

                // STEP 2: Cek NIK di setiap admin dalam cabang
                for (adminUid in adminsInCabang) {

                    // Cek NIK utama (untuk pinjaman di bawah 3jt)
                    if (nik.isNotBlank()) {
                        val result = checkNikInAdmin(adminUid, "nik", nik)
                        if (result != null) return@withContext result
                    }

                    // Cek NIK Suami
                    if (nikSuami.isNotBlank()) {
                        val resultSuami = checkNikInAdmin(adminUid, "nikSuami", nikSuami)
                        if (resultSuami != null) {
                            return@withContext resultSuami.copy(
                                message = "NIK Suami $nikSuami masih terdaftar sebagai nasabah aktif pada ${resultSuami.existingAdminName}"
                            )
                        }
                        // Cek juga di field nik biasa
                        val resultNik = checkNikInAdmin(adminUid, "nik", nikSuami)
                        if (resultNik != null) {
                            return@withContext resultNik.copy(
                                message = "NIK Suami $nikSuami masih terdaftar sebagai nasabah aktif pada ${resultNik.existingAdminName}"
                            )
                        }
                    }

                    // Cek NIK Istri
                    if (nikIstri.isNotBlank()) {
                        val resultIstri = checkNikInAdmin(adminUid, "nikIstri", nikIstri)
                        if (resultIstri != null) {
                            return@withContext resultIstri.copy(
                                message = "NIK Istri $nikIstri masih terdaftar sebagai nasabah aktif pada ${resultIstri.existingAdminName}"
                            )
                        }
                        val resultNik = checkNikInAdmin(adminUid, "nik", nikIstri)
                        if (resultNik != null) {
                            return@withContext resultNik.copy(
                                message = "NIK Istri $nikIstri masih terdaftar sebagai nasabah aktif pada ${resultNik.existingAdminName}"
                            )
                        }
                    }
                }

                Log.d("NikValidation", "✅ NIK valid, tidak ditemukan duplikat aktif")
                return@withContext NikValidationResult(isValid = true)

            } catch (e: Exception) {
                Log.e("NikValidation", "❌ Error validasi NIK: ${e.message}")
                // Fail-open: tetap izinkan jika error
                return@withContext NikValidationResult(
                    isValid = true,
                    message = "Validasi tidak dapat dilakukan: ${e.message}"
                )
            }
        }
    }

    /**
     * Helper: Cek NIK di satu admin, return hasil jika AKTIF
     * ✅ DIPERBAIKI: Menambahkan validasi eksplisit untuk memastikan NIK benar-benar cocok
     */
    private suspend fun checkNikInAdmin(
        adminUid: String,
        nikField: String,
        nikValue: String
    ): NikValidationResult? {
        return withContext(Dispatchers.IO) {
            try {
                // Validasi input - skip jika NIK kosong atau tidak valid
                if (nikValue.isBlank() || nikValue.length != 16) {
                    Log.d("NikValidation", "⚠️ Skip: NIK tidak valid (kosong atau bukan 16 digit): '$nikValue'")
                    return@withContext null
                }

                Log.d("NikValidation", "🔍 Checking NIK in admin $adminUid, field=$nikField, value=$nikValue")

                val snapshot = database.child("pelanggan")
                    .child(adminUid)
                    .orderByChild(nikField)
                    .equalTo(nikValue)
                    .get()
                    .await()

                if (!snapshot.exists()) {
                    Log.d("NikValidation", "✅ No data found for NIK $nikValue in admin $adminUid")
                    return@withContext null
                }

                Log.d("NikValidation", "📋 Found ${snapshot.childrenCount} record(s) in admin $adminUid for NIK query")

                for (pelangganChild in snapshot.children) {
                    val pelanggan = pelangganChild.getValue(Pelanggan::class.java) ?: continue
                    val pelangganId = pelangganChild.key ?: ""

                    // ✅ PERBAIKAN KRITIS: Validasi eksplisit bahwa NIK benar-benar cocok
                    val pelangganNik = when (nikField) {
                        "nik" -> pelanggan.nik
                        "nikSuami" -> pelanggan.nikSuami
                        "nikIstri" -> pelanggan.nikIstri
                        else -> ""
                    }

                    // Skip jika NIK tidak cocok (defensive check)
                    if (pelangganNik != nikValue) {
                        Log.w("NikValidation", "⚠️ SKIP FALSE POSITIVE: NIK tidak cocok!")
                        Log.w("NikValidation", "   - Expected: '$nikValue'")
                        Log.w("NikValidation", "   - Found: '$pelangganNik'")
                        Log.w("NikValidation", "   - Pelanggan: ${pelanggan.namaPanggilan} (ID: $pelangganId)")
                        continue
                    }

                    val statusLower = pelanggan.status.lowercase()
                    val isActiveOrPending = statusLower == "aktif" ||
                            statusLower == "active" ||
                            statusLower == "menunggu approval" ||
                            statusLower == "pending" ||
                            statusLower == "disetujui"

                    // ✅ PERBAIKAN: Cek juga nasabah yang sedang menunggu pencairan
                    val isMenungguPencairan = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN" ||
                            (pelanggan.statusPencairanSimpanan == "Menunggu Pencairan")

                    if (isActiveOrPending) {
                        val adminName = getAdminNameByUid(adminUid)

                        Log.d("NikValidation", "🔴 NIK duplikat VALID ditemukan:")
                        Log.d("NikValidation", "   - NIK: $nikValue")
                        Log.d("NikValidation", "   - Admin: $adminUid ($adminName)")
                        Log.d("NikValidation", "   - Nasabah: ${pelanggan.namaPanggilan}")
                        Log.d("NikValidation", "   - Status: ${pelanggan.status}")

                        return@withContext NikValidationResult(
                            isValid = false,
                            isDuplicate = true,
                            existingAdminName = adminName,
                            existingAdminUid = adminUid,
                            existingPelangganNama = pelanggan.namaPanggilan,
                            existingStatus = pelanggan.status,
                            message = "NIK $nikValue masih terdaftar sebagai nasabah ${pelanggan.status} pada $adminName (${pelanggan.namaPanggilan})"
                        )
                    } else if (isMenungguPencairan) {
                        val adminName = getAdminNameByUid(adminUid)

                        Log.d("NikValidation", "🟡 NIK duplikat - Nasabah menunggu pencairan:")
                        Log.d("NikValidation", "   - NIK: $nikValue")
                        Log.d("NikValidation", "   - Nasabah: ${pelanggan.namaPanggilan}")

                        return@withContext NikValidationResult(
                            isValid = false,
                            isDuplicate = true,
                            existingAdminName = adminName,
                            existingAdminUid = adminUid,
                            existingPelangganNama = pelanggan.namaPanggilan,
                            existingStatus = "Menunggu Pencairan",
                            message = "NIK $nikValue terdaftar sebagai nasabah menunggu pencairan (${pelanggan.namaPanggilan}). Gunakan tombol 'Lanjut Pinjaman' di Daftar Menunggu Pencairan."
                        )
                    } else {
                        Log.d("NikValidation", "ℹ️ NIK $nikValue ditemukan tapi status='${pelanggan.status}' (bukan aktif/pending/menunggu pencairan)")
                    }
                }

                Log.d("NikValidation", "✅ NIK $nikValue tidak ditemukan sebagai duplikat aktif di admin $adminUid")
                return@withContext null

            } catch (e: Exception) {
                Log.e("NikValidation", "❌ Error cek NIK di admin $adminUid: ${e.message}")
                e.printStackTrace()
                return@withContext null
            }
        }
    }

    /**
     * Helper: Dapatkan nama admin dari UID
     */
    private suspend fun getAdminNameByUid(adminUid: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = database.child("metadata").child("admins")
                    .child(adminUid)
                    .child("displayName")
                    .get()
                    .await()

                snapshot.getValue(String::class.java) ?: "Resort $adminUid"
            } catch (e: Exception) {
                "Resort $adminUid"
            }
        }
    }

    /**
     * Public function untuk validasi NIK dari UI
     */
    fun validateNik(
        nik: String = "",
        nikSuami: String = "",
        nikIstri: String = "",
        onResult: (NikValidationResult) -> Unit
    ) {
        viewModelScope.launch {
            _isValidatingNik.value = true
            _nikValidationResult.value = null

            try {
                // ✅ PERBAIKAN: Cek online status dulu
                if (!isOnline()) {
                    Log.d("NikValidation", "📵 Offline mode - skip online NIK validation")
                    // Saat offline, lakukan validasi lokal saja
                    val localResult = validateNikOffline(nik, nikSuami, nikIstri)
                    _nikValidationResult.value = localResult
                    onResult(localResult)
                    return@launch
                }

                val cabangId = _currentUserCabang.value ?: ""

                // ✅ PERBAIKAN: Gunakan timeout untuk mencegah stuck
                val result = kotlinx.coroutines.withTimeoutOrNull(8000L) {
                    validateNikAcrossCabang(
                        nik = nik,
                        nikSuami = nikSuami,
                        nikIstri = nikIstri,
                        currentCabangId = cabangId,
                        excludeAdminUid = null
                    )
                } ?: NikValidationResult(isValid = true, message = "Validasi timeout, lanjutkan offline mode")

                _nikValidationResult.value = result
                onResult(result)

            } catch (e: Exception) {
                val errorResult = NikValidationResult(isValid = true, message = "Validasi gagal: ${e.message}")
                _nikValidationResult.value = errorResult
                onResult(errorResult)
            } finally {
                _isValidatingNik.value = false
            }
        }
    }

    /**
     * Validasi NIK secara offline - cek di data lokal saja
     */
    private fun validateNikOffline(
        nik: String = "",
        nikSuami: String = "",
        nikIstri: String = ""
    ): NikValidationResult {
        try {
            if (nik.isBlank() && nikSuami.isBlank() && nikIstri.isBlank()) {
                return NikValidationResult(
                    isValid = false,
                    message = "NIK tidak boleh kosong"
                )
            }

            // Cek di daftarPelanggan lokal
            for (pelanggan in daftarPelanggan) {
                val statusLower = pelanggan.status.lowercase()
                val isActiveOrPending = statusLower == "aktif" ||
                        statusLower == "active" ||
                        statusLower == "menunggu approval" ||
                        statusLower == "pending" ||
                        statusLower == "disetujui"

                // ✅ PERBAIKAN: Cek juga nasabah menunggu pencairan
                val isMenungguPencairan = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN" ||
                        (pelanggan.statusPencairanSimpanan == "Menunggu Pencairan")

                if (!isActiveOrPending && !isMenungguPencairan) continue

                // ✅ Jika nasabah menunggu pencairan, beri pesan khusus
                if (isMenungguPencairan && !isActiveOrPending) {
                    val nikCocok = (nik.isNotBlank() && (pelanggan.nik == nik || pelanggan.nikSuami == nik || pelanggan.nikIstri == nik)) ||
                            (nikSuami.isNotBlank() && (pelanggan.nik == nikSuami || pelanggan.nikSuami == nikSuami)) ||
                            (nikIstri.isNotBlank() && (pelanggan.nik == nikIstri || pelanggan.nikIstri == nikIstri))

                    if (nikCocok) {
                        return NikValidationResult(
                            isValid = false,
                            isDuplicate = true,
                            existingPelangganNama = pelanggan.namaPanggilan,
                            existingStatus = "Menunggu Pencairan",
                            message = "NIK sudah terdaftar sebagai nasabah menunggu pencairan (${pelanggan.namaPanggilan}). Gunakan tombol 'Lanjut Pinjaman' di Daftar Menunggu Pencairan."
                        )
                    }
                    continue
                }

                // Cek NIK utama
                if (nik.isNotBlank() && (pelanggan.nik == nik || pelanggan.nikSuami == nik || pelanggan.nikIstri == nik)) {
                    return NikValidationResult(
                        isValid = false,
                        isDuplicate = true,
                        existingPelangganNama = pelanggan.namaPanggilan,
                        existingStatus = pelanggan.status,
                        message = "NIK $nik sudah terdaftar pada nasabah ${pelanggan.namaPanggilan} (${pelanggan.status})"
                    )
                }

                // Cek NIK Suami
                if (nikSuami.isNotBlank() && (pelanggan.nik == nikSuami || pelanggan.nikSuami == nikSuami)) {
                    return NikValidationResult(
                        isValid = false,
                        isDuplicate = true,
                        existingPelangganNama = pelanggan.namaPanggilan,
                        existingStatus = pelanggan.status,
                        message = "NIK Suami $nikSuami sudah terdaftar pada nasabah ${pelanggan.namaPanggilan}"
                    )
                }

                // Cek NIK Istri
                if (nikIstri.isNotBlank() && (pelanggan.nik == nikIstri || pelanggan.nikIstri == nikIstri)) {
                    return NikValidationResult(
                        isValid = false,
                        isDuplicate = true,
                        existingPelangganNama = pelanggan.namaPanggilan,
                        existingStatus = pelanggan.status,
                        message = "NIK Istri $nikIstri sudah terdaftar pada nasabah ${pelanggan.namaPanggilan}"
                    )
                }
            }

            Log.d("NikValidation", "✅ NIK valid (offline validation)")
            return NikValidationResult(
                isValid = true,
                message = "NIK valid (mode offline - validasi online saat sync)"
            )

        } catch (e: Exception) {
            Log.e("NikValidation", "❌ Error offline validation: ${e.message}")
            return NikValidationResult(isValid = true, message = "Validasi offline gagal: ${e.message}")
        }
    }

    /**
     * Reset state validasi
     */
    fun clearNikValidation() {
        _nikValidationResult.value = null
        _isValidatingNik.value = false
    }

    fun searchNikGlobal(
        nik: String,
        onResult: (NikSearchResult) -> Unit
    ) {
        viewModelScope.launch {
            try {
                Log.d("NikSearch", "🔍 Searching NIK: $nik")

                // Validasi NIK
                if (nik.isBlank() || nik.length != 16) {
                    onResult(NikSearchResult(
                        found = false,
                        status = NikSearchStatus.NOT_FOUND,
                        message = "NIK tidak valid. Pastikan 16 digit."
                    ))
                    return@launch
                }

                val currentUserUid = Firebase.auth.currentUser?.uid
                if (currentUserUid.isNullOrBlank()) {
                    onResult(NikSearchResult(
                        found = false,
                        status = NikSearchStatus.NOT_FOUND,
                        message = "User tidak terautentikasi"
                    ))
                    return@launch
                }

                // Query ke nik_registry (1 READ saja!)
                val snapshot = withContext(Dispatchers.IO) {
                    database.child("nik_registry")
                        .child(nik)
                        .get()
                        .await()
                }

                if (!snapshot.exists()) {
                    // NIK tidak ditemukan - nasabah baru
                    Log.d("NikSearch", "✅ NIK not found - new customer")
                    onResult(NikSearchResult(
                        found = false,
                        status = NikSearchStatus.NOT_FOUND,
                        message = "Data calon nasabah ini belum pernah menjadi anggota di KSP Si Godang Ulu Jaya. Silakan lanjut untuk mendaftarkan nasabah baru."
                    ))
                    return@launch
                }

                // NIK ditemukan - parse data
                val adminUid = snapshot.child("adminUid").getValue(String::class.java) ?: ""
                val adminName = snapshot.child("adminName").getValue(String::class.java) ?: ""
                val cabangName = snapshot.child("cabangName").getValue(String::class.java) ?: ""
                val pelangganId = snapshot.child("pelangganId").getValue(String::class.java) ?: ""
                val nama = snapshot.child("nama").getValue(String::class.java) ?: ""
                val status = snapshot.child("status").getValue(String::class.java) ?: ""

                Log.d("NikSearch", "📍 NIK found:")
                Log.d("NikSearch", "   - Admin: $adminName")
                Log.d("NikSearch", "   - Status: $status")
                Log.d("NikSearch", "   - Cabang: $cabangName")

                val isCurrentAdmin = adminUid == currentUserUid
                val statusLower = status.lowercase()

                // Tentukan hasil berdasarkan status
                when {
                    // Status AKTIF - admin sendiri
                    (statusLower == "aktif" || statusLower == "active" ||
                            statusLower == "menunggu approval" || statusLower == "menunggu_approval") && isCurrentAdmin -> {

                        Log.d("NikSearch", "⚠️ Active at self")

                        // Ambil data pelanggan untuk detail
                        val pelanggan = getPelangganById(adminUid, pelangganId)

                        onResult(NikSearchResult(
                            found = true,
                            status = NikSearchStatus.ACTIVE_SELF,
                            pelanggan = pelanggan,
                            adminName = adminName,
                            adminUid = adminUid,
                            cabangName = cabangName,
                            pelangganId = pelangganId,
                            message = "NIK ini masih memiliki pinjaman aktif pada Anda ($adminName)."
                        ))
                    }

                    // Status AKTIF - admin lain
                    statusLower == "aktif" || statusLower == "active" ||
                            statusLower == "menunggu approval" || statusLower == "menunggu_approval" -> {

                        Log.d("NikSearch", "⚠️ Active at other admin: $adminName")

                        onResult(NikSearchResult(
                            found = true,
                            status = NikSearchStatus.ACTIVE_OTHER_ADMIN,
                            pelanggan = Pelanggan(
                                id = pelangganId,
                                namaPanggilan = nama,
                                namaKtp = nama,
                                nik = nik,
                                status = status
                            ),
                            adminName = adminName,
                            adminUid = adminUid,
                            cabangName = cabangName,
                            pelangganId = pelangganId,
                            message = "NIK ini masih memiliki pinjaman aktif pada $adminName (Cabang $cabangName). Nasabah tidak dapat mengajukan pinjaman baru."
                        ))
                    }

                    // Status LUNAS - admin sendiri
                    statusLower == "lunas" && isCurrentAdmin -> {

                        Log.d("NikSearch", "✅ Lunas at self")

                        val pelanggan = getPelangganById(adminUid, pelangganId)

                        onResult(NikSearchResult(
                            found = true,
                            status = NikSearchStatus.LUNAS_SELF,
                            pelanggan = pelanggan,
                            adminName = adminName,
                            adminUid = adminUid,
                            cabangName = cabangName,
                            pelangganId = pelangganId,
                            message = "Nasabah ini sudah LUNAS dan terdaftar di data Anda. Silakan lanjut untuk mengajukan pinjaman baru."
                        ))
                    }

                    // Status LUNAS - admin lain
                    statusLower == "lunas" -> {

                        Log.d("NikSearch", "ℹ️ Lunas at other admin: $adminName")

                        onResult(NikSearchResult(
                            found = true,
                            status = NikSearchStatus.LUNAS_OTHER,
                            pelanggan = Pelanggan(
                                id = pelangganId,
                                namaPanggilan = nama,
                                namaKtp = nama,
                                nik = nik,
                                status = status
                            ),
                            adminName = adminName,
                            adminUid = adminUid,
                            cabangName = cabangName,
                            pelangganId = pelangganId,
                            message = "Nasabah ini pernah terdaftar dan sudah LUNAS di $adminName (Cabang $cabangName). Anda dapat mendaftarkan ulang sebagai nasabah baru."
                        ))
                    }

                    // Status tidak dikenal
                    else -> {
                        Log.w("NikSearch", "⚠️ Unknown status: $status")
                        onResult(NikSearchResult(
                            found = false,
                            status = NikSearchStatus.NOT_FOUND,
                            message = "Data tidak valid. Silakan lanjut untuk mendaftarkan."
                        ))
                    }
                }

            } catch (e: Exception) {
                Log.e("NikSearch", "❌ Error: ${e.message}")
                e.printStackTrace()
                onResult(NikSearchResult(
                    found = false,
                    status = NikSearchStatus.NOT_FOUND,
                    message = "Terjadi kesalahan: ${e.message}. Silakan coba lagi."
                ))
            }
        }
    }

    private suspend fun searchNikInAdmin(nik: String, adminUid: String): Pelanggan? {
        return withContext(Dispatchers.IO) {
            try {
                // Cek di field "nik" (pinjaman < 3jt)
                var snapshot = database.child("pelanggan")
                    .child(adminUid)
                    .orderByChild("nik")
                    .equalTo(nik)
                    .get()
                    .await()

                if (snapshot.exists()) {
                    for (child in snapshot.children) {
                        val pelanggan = child.getValue(Pelanggan::class.java)
                        if (pelanggan != null) {
                            Log.d("NikSearch", "📍 Ditemukan di $adminUid (field: nik)")
                            return@withContext pelanggan.copy(id = child.key ?: "")
                        }
                    }
                }

                // Cek di field "nikSuami" (pinjaman >= 3jt)
                snapshot = database.child("pelanggan")
                    .child(adminUid)
                    .orderByChild("nikSuami")
                    .equalTo(nik)
                    .get()
                    .await()

                if (snapshot.exists()) {
                    for (child in snapshot.children) {
                        val pelanggan = child.getValue(Pelanggan::class.java)
                        if (pelanggan != null) {
                            Log.d("NikSearch", "📍 Ditemukan di $adminUid (field: nikSuami)")
                            return@withContext pelanggan.copy(id = child.key ?: "")
                        }
                    }
                }

                // Cek di field "nikIstri" (pinjaman >= 3jt)
                snapshot = database.child("pelanggan")
                    .child(adminUid)
                    .orderByChild("nikIstri")
                    .equalTo(nik)
                    .get()
                    .await()

                if (snapshot.exists()) {
                    for (child in snapshot.children) {
                        val pelanggan = child.getValue(Pelanggan::class.java)
                        if (pelanggan != null) {
                            Log.d("NikSearch", "📍 Ditemukan di $adminUid (field: nikIstri)")
                            return@withContext pelanggan.copy(id = child.key ?: "")
                        }
                    }
                }

                null
            } catch (e: Exception) {
                Log.e("NikSearch", "Error cek NIK di admin $adminUid: ${e.message}")
                null
            }
        }
    }

    /**
     * Helper: Cek apakah nasabah sudah lunas berdasarkan pembayaran
     */
    private fun isNasabahLunas(pelanggan: Pelanggan): Boolean {
        val totalBayar = pelanggan.pembayaranList.sumOf { pembayaran ->
            pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
        }
        return totalBayar >= pelanggan.totalPelunasan && pelanggan.totalPelunasan > 0
    }

    private suspend fun getPelangganById(adminUid: String, pelangganId: String): Pelanggan? {
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = database.child("pelanggan")
                    .child(adminUid)
                    .child(pelangganId)
                    .get()
                    .await()

                snapshot.getValue(Pelanggan::class.java)?.copy(id = pelangganId)
            } catch (e: Exception) {
                Log.e("NikSearch", "Error get pelanggan: ${e.message}")
                null
            }
        }
    }

    fun setPengawasSelectedCabang(cabangId: String?) {
        Log.d(TAG, "🔍 Pengawas filter changed to: ${cabangId ?: "Semua Cabang"}")
        _pengawasSelectedCabangId.value = cabangId

        // Reload daily data untuk cabang yang dipilih
        // Data summary TIDAK perlu reload karena sudah reaktif via combine()
        viewModelScope.launch {
            loadPengawasDailyData()
        }
    }

    private fun getPengawasTargetCabangIds(): List<String> {
        val selectedId = _pengawasSelectedCabangId.value
        return if (selectedId == null || selectedId == "all") {
            _allCabangList.value.map { it.cabangId }
        } else {
            listOf(selectedId)
        }
    }

    fun loadPengawasAllCabangData() {
        // Cegah multiple loading
        if (_pengawasDataLoaded.value && _allCabangSummaries.value.isNotEmpty()) {
            Log.d(TAG, "📊 Pengawas data already loaded, skipping...")
            return
        }

        viewModelScope.launch {
            try {
                isLoading.value = true
                Log.d(TAG, "📊 Loading all cabang data for Pengawas...")

                // 1. Load metadata cabang
                val cabangSnap = database.child("metadata/cabang").get().await()
                val cabangList = mutableListOf<CabangMetadata>()

                cabangSnap.children.forEach { child ->
                    val cabangId = child.key ?: return@forEach
                    val name = child.child("name").getValue(String::class.java) ?: cabangId
                    val pimpinanUid = child.child("pimpinanUid").getValue(String::class.java) ?: ""
                    val adminList = child.child("adminList").children.mapNotNull {
                        it.getValue(String::class.java)
                    }

                    cabangList.add(CabangMetadata(
                        cabangId = cabangId,
                        name = name,
                        pimpinanUid = pimpinanUid,
                        adminList = adminList
                    ))
                }

                _allCabangList.value = cabangList
                Log.d(TAG, "✅ Loaded ${cabangList.size} cabang")

                // 2. Load summary per cabang
                loadPengawasCabangSummaries(cabangList)

                // 3. Load admin summaries
                loadPengawasAdminSummaries(cabangList)

                // 4. Load daily data
                loadPengawasDailyData()

                // 5. Mark as loaded
                _pengawasDataLoaded.value = true

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading pengawas data: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }
    }
    private suspend fun loadPengawasCabangSummaries(cabangList: List<CabangMetadata>) {
        val summaries = mutableMapOf<String, PengawasCabangSummary>()

        for (cabang in cabangList) {  // ✅ FIXED: gunakan for loop bukan forEach
            try {
                val summarySnap = database.child("summary/perCabang/${cabang.cabangId}").get().await()

                if (summarySnap.exists()) {
                    val nasabahAktif = summarySnap.child("nasabahAktif").getValue(Int::class.java) ?: 0
                    val totalPiutang = summarySnap.child("totalPiutang").getValue(Long::class.java) ?: 0L

                    // ✅ FALLBACK: Jika cabang summary kosong, hitung dari admin summaries
                    if (nasabahAktif == 0 && totalPiutang == 0L) {
                        Log.w(TAG, "⚠️ [Pengawas] Cabang summary empty for ${cabang.cabangId}, calculating...")

                        // Hitung dari admin summaries yang sudah di-load
                        var calculatedNasabahAktif = 0
                        var calculatedTotalPiutang = 0L
                        var calculatedTotalNasabah = 0
                        var calculatedNasabahLunas = 0
                        var calculatedNasabahMenunggu = 0
                        var calculatedTotalPinjamanAktif = 0L
                        var calculatedPembayaranHariIni = 0L
                        var calculatedTargetHariIni = 0L
                        var calculatedNasabahBaruHariIni = 0
                        var calculatedNasabahLunasHariIni = 0

                        for (adminUid in cabang.adminList) {
                            val adminSummary = calculateAdminSummaryFromRawData(adminUid)
                            if (adminSummary != null) {
                                calculatedNasabahAktif += adminSummary.nasabahAktif
                                calculatedTotalPiutang += adminSummary.totalPiutang
                                calculatedTotalNasabah += adminSummary.totalPelanggan
                                calculatedNasabahLunas += adminSummary.nasabahLunas
                                calculatedNasabahMenunggu += adminSummary.nasabahMenunggu
                                calculatedTotalPinjamanAktif += adminSummary.totalPinjamanAktif
                                calculatedPembayaranHariIni += adminSummary.pembayaranHariIni
                                calculatedTargetHariIni += adminSummary.targetHariIni
                                calculatedNasabahBaruHariIni += adminSummary.nasabahBaruHariIni
                                calculatedNasabahLunasHariIni += adminSummary.nasabahLunasHariIni
                            }
                        }

                        if (calculatedNasabahAktif > 0 || calculatedTotalNasabah > 0) {
                            summaries[cabang.cabangId] = PengawasCabangSummary(
                                cabangId = cabang.cabangId,
                                cabangName = cabang.name,
                                totalNasabah = calculatedTotalNasabah,
                                nasabahAktif = calculatedNasabahAktif,
                                nasabahLunas = calculatedNasabahLunas,
                                nasabahMenunggu = calculatedNasabahMenunggu,
                                nasabahBaruHariIni = calculatedNasabahBaruHariIni,
                                nasabahLunasHariIni = calculatedNasabahLunasHariIni,
                                targetHariIni = calculatedTargetHariIni,
                                pembayaranHariIni = calculatedPembayaranHariIni,
                                totalPinjamanAktif = calculatedTotalPinjamanAktif,
                                totalPiutang = calculatedTotalPiutang,
                                adminCount = cabang.adminList.size,
                                lastUpdated = System.currentTimeMillis()
                            )

                            Log.d(TAG, "✅ [Pengawas] Calculated cabang summary for ${cabang.cabangId}")
                            continue  // ✅ Now works because we're in a for loop
                        }
                    }

                    summaries[cabang.cabangId] = PengawasCabangSummary(
                        cabangId = cabang.cabangId,
                        cabangName = cabang.name,
                        totalNasabah = summarySnap.child("totalNasabah").getValue(Int::class.java) ?: 0,
                        nasabahAktif = nasabahAktif,
                        nasabahLunas = summarySnap.child("nasabahLunas").getValue(Int::class.java) ?: 0,
                        nasabahMenunggu = summarySnap.child("nasabahMenunggu").getValue(Int::class.java) ?: 0,
                        nasabahBaruHariIni = summarySnap.child("nasabahBaruHariIni").getValue(Int::class.java) ?: 0,
                        nasabahLunasHariIni = summarySnap.child("nasabahLunasHariIni").getValue(Int::class.java) ?: 0,
                        targetHariIni = summarySnap.child("targetHariIni").getValue(Long::class.java) ?: 0L,
                        pembayaranHariIni = summarySnap.child("pembayaranHariIni").getValue(Long::class.java) ?: 0L,
                        totalPinjamanAktif = summarySnap.child("totalPinjamanAktif").getValue(Long::class.java) ?: 0L,
                        totalPiutang = totalPiutang,
                        adminCount = summarySnap.child("adminCount").getValue(Int::class.java) ?: 0,
                        lastUpdated = summarySnap.child("lastUpdated").getValue(Long::class.java) ?: 0L
                    )
                } else {
                    // ✅ Cabang summary tidak ada, hitung dari raw data
                    Log.w(TAG, "⚠️ [Pengawas] No cabang summary for ${cabang.cabangId}, calculating...")

                    var totalNasabah = 0
                    var nasabahAktif = 0
                    var nasabahLunas = 0
                    var nasabahMenunggu = 0
                    var totalPinjamanAktif = 0L
                    var totalPiutang = 0L
                    var pembayaranHariIni = 0L
                    var targetHariIni = 0L
                    var nasabahBaruHariIni = 0
                    var nasabahLunasHariIni = 0

                    for (adminUid in cabang.adminList) {
                        val adminSummary = calculateAdminSummaryFromRawData(adminUid)
                        if (adminSummary != null) {
                            totalNasabah += adminSummary.totalPelanggan
                            nasabahAktif += adminSummary.nasabahAktif
                            nasabahLunas += adminSummary.nasabahLunas
                            nasabahMenunggu += adminSummary.nasabahMenunggu
                            totalPinjamanAktif += adminSummary.totalPinjamanAktif
                            totalPiutang += adminSummary.totalPiutang
                            pembayaranHariIni += adminSummary.pembayaranHariIni
                            targetHariIni += adminSummary.targetHariIni
                            nasabahBaruHariIni += adminSummary.nasabahBaruHariIni
                            nasabahLunasHariIni += adminSummary.nasabahLunasHariIni
                        }
                    }

                    summaries[cabang.cabangId] = PengawasCabangSummary(
                        cabangId = cabang.cabangId,
                        cabangName = cabang.name,
                        totalNasabah = totalNasabah,
                        nasabahAktif = nasabahAktif,
                        nasabahLunas = nasabahLunas,
                        nasabahMenunggu = nasabahMenunggu,
                        nasabahBaruHariIni = nasabahBaruHariIni,
                        nasabahLunasHariIni = nasabahLunasHariIni,
                        targetHariIni = targetHariIni,
                        pembayaranHariIni = pembayaranHariIni,
                        totalPinjamanAktif = totalPinjamanAktif,
                        totalPiutang = totalPiutang,
                        adminCount = cabang.adminList.size,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading summary for ${cabang.cabangId}: ${e.message}")
            }
        }

        _allCabangSummaries.value = summaries
        Log.d(TAG, "✅ [Pengawas] Loaded ${summaries.size} cabang summaries")
    }

    private suspend fun loadPengawasAdminSummaries(cabangList: List<CabangMetadata>) {
        val summaries = mutableMapOf<String, AdminSummary>()
        var needsUpdate = false

        cabangList.forEach { cabang ->
            cabang.adminList.forEach { adminUid ->
                try {
                    val summarySnap = database.child("summary/perAdmin/$adminUid").get().await()
                    val adminMeta = database.child("metadata/admins/$adminUid").get().await()

                    if (summarySnap.exists()) {
                        val nasabahAktif = summarySnap.child("nasabahAktif").getValue(Int::class.java) ?: 0
                        val totalPiutang = summarySnap.child("totalPiutang").getValue(Long::class.java) ?: 0L

                        // ✅ FALLBACK: Jika summary ada tapi kosong
                        if (nasabahAktif == 0 && totalPiutang == 0L) {
                            Log.w(TAG, "⚠️ [Pengawas] Summary empty for $adminUid, checking raw data...")

                            val calculatedSummary = calculateAdminSummaryFromRawData(adminUid)
                            if (calculatedSummary != null && (calculatedSummary.nasabahAktif > 0 || calculatedSummary.totalPelanggan > 0)) {
                                Log.d(TAG, "✅ [Pengawas] Using calculated summary for $adminUid")
                                summaries[adminUid] = calculatedSummary

                                // Update Firebase
                                updateAdminSummaryInFirebase(adminUid, calculatedSummary)
                                needsUpdate = true
                                return@forEach  // ✅ FIXED: gunakan return@forEach bukan continue
                            }
                        }

                        summaries[adminUid] = AdminSummary(
                            adminId = adminUid,
                            adminName = adminMeta.child("name").getValue(String::class.java) ?: "Admin",
                            adminEmail = adminMeta.child("email").getValue(String::class.java) ?: "",
                            cabang = cabang.cabangId,
                            totalPelanggan = summarySnap.child("totalNasabah").getValue(Int::class.java) ?: 0,
                            nasabahAktif = nasabahAktif,
                            nasabahLunas = summarySnap.child("nasabahLunas").getValue(Int::class.java) ?: 0,
                            nasabahMenunggu = summarySnap.child("nasabahMenunggu").getValue(Int::class.java) ?: 0,
                            nasabahBaruHariIni = summarySnap.child("nasabahBaruHariIni").getValue(Int::class.java) ?: 0,
                            nasabahLunasHariIni = summarySnap.child("nasabahLunasHariIni").getValue(Int::class.java) ?: 0,
                            targetHariIni = summarySnap.child("targetHariIni").getValue(Long::class.java) ?: 0L,
                            totalPinjamanAktif = summarySnap.child("totalPinjamanAktif").getValue(Long::class.java) ?: 0L,
                            totalPiutang = totalPiutang,
                            pembayaranHariIni = summarySnap.child("pembayaranHariIni").getValue(Long::class.java) ?: 0L
                        )
                    } else {
                        // ✅ Summary tidak ada, hitung dari raw data
                        Log.w(TAG, "⚠️ [Pengawas] No summary for $adminUid, calculating...")

                        val calculatedSummary = calculateAdminSummaryFromRawData(adminUid)
                        if (calculatedSummary != null) {
                            summaries[adminUid] = calculatedSummary
                            updateAdminSummaryInFirebase(adminUid, calculatedSummary)
                            needsUpdate = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading admin summary for $adminUid: ${e.message}")
                }
            }
        }

        _allAdminSummaries.value = summaries
        Log.d(TAG, "✅ [Pengawas] Loaded ${summaries.size} admin summaries")

        // Update cabang summaries jika perlu
        if (needsUpdate) {
            cabangList.forEach { cabang ->
                val cabangAdminSummaries = summaries.values.filter { it.cabang == cabang.cabangId }
                if (cabangAdminSummaries.isNotEmpty()) {
                    updateCabangSummaryInFirebase(cabang.cabangId, cabangAdminSummaries)
                }
            }
        }
    }

    private fun loadPengawasDailyData() {
        val today = getTodayIndonesiaFormat() // Implementasi helper di bawah
        Log.d(TAG, "📅 Loading daily data for: $today")

        viewModelScope.launch {
            try {
                val cabangIds = getPengawasTargetCabangIds()

                // Load pembayaran harian
                val pembayaranList = mutableListOf<PembayaranHarianItem>()
                cabangIds.forEach { cabangId ->
                    val snap = database.child("pembayaran_harian/$cabangId/$today").get().await()
                    snap.children.forEach { child ->
                        pembayaranList.add(PembayaranHarianItem(
                            id = child.key ?: "",
                            pelangganId = child.child("pelangganId").getValue(String::class.java) ?: "",
                            namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                            namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                            adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                            adminName = child.child("adminName").getValue(String::class.java) ?: "",
                            adminEmail = child.child("adminEmail").getValue(String::class.java) ?: "",
                            cabangId = cabangId,
                            jumlah = child.child("jumlah").getValue(Long::class.java) ?: 0L,
                            jenis = child.child("jenis").getValue(String::class.java) ?: "",
                            tanggal = child.child("tanggal").getValue(String::class.java) ?: "",
                            timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                        ))
                    }
                }
                _pengawasPembayaranHarian.value = pembayaranList.sortedByDescending { it.timestamp }
                Log.d(TAG, "✅ Loaded ${pembayaranList.size} pembayaran")

                // Load nasabah baru
                val nasabahBaruList = mutableListOf<NasabahBaruItem>()
                cabangIds.forEach { cabangId ->
                    val snap = database.child("event_harian/$cabangId/$today/nasabah_baru").get().await()
                    snap.children.forEach { child ->
                        nasabahBaruList.add(NasabahBaruItem(
                            pelangganId = child.key ?: "",
                            namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                            namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                            adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                            adminName = child.child("adminName").getValue(String::class.java) ?: "",
                            cabangId = cabangId,
                            besarPinjaman = child.child("besarPinjaman").getValue(Long::class.java) ?: 0L,
                            totalDiterima = child.child("totalDiterima").getValue(Long::class.java) ?: 0L,
                            wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
                            tanggalDaftar = child.child("tanggalDaftar").getValue(String::class.java) ?: "",
                            timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L,
                            pinjamanKe = child.child("pinjamanKe").getValue(Int::class.java) ?: 1  // ✅ BARU
                        ))
                    }
                }
                _pengawasNasabahBaru.value = nasabahBaruList.sortedByDescending { it.timestamp }

                // Load nasabah lunas
                val nasabahLunasList = mutableListOf<NasabahLunasItem>()
                cabangIds.forEach { cabangId ->
                    val snap = database.child("event_harian/$cabangId/$today/nasabah_lunas").get().await()
                    snap.children.forEach { child ->
                        nasabahLunasList.add(NasabahLunasItem(
                            pelangganId = child.key ?: "",
                            namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                            namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                            adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                            adminName = child.child("adminName").getValue(String::class.java) ?: "",
                            cabangId = cabangId,
                            totalPinjaman = child.child("totalPinjaman").getValue(Long::class.java) ?: 0L,
                            totalDibayar = child.child("totalDibayar").getValue(Long::class.java) ?: 0L,
                            wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
                            tanggalLunas = child.child("tanggalLunas").getValue(String::class.java) ?: "",
                            timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                        ))
                    }
                }
                _pengawasNasabahLunas.value = nasabahLunasList.sortedByDescending { it.timestamp }

                // Load pelanggan bermasalah
                val bermasalahList = mutableListOf<PelangganBermasalahItem>()
                cabangIds.forEach { cabangId ->
                    val snap = database.child("pelanggan_bermasalah/$cabangId").get().await()
                    snap.children.forEach { child ->
                        bermasalahList.add(PelangganBermasalahItem(
                            pelangganId = child.key ?: "",
                            namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                            namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                            adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                            adminName = child.child("adminName").getValue(String::class.java) ?: "",
                            cabangId = cabangId,
                            kategori = child.child("kategori").getValue(String::class.java) ?: "",
                            hariTunggakan = child.child("hariTunggakan").getValue(Int::class.java) ?: 0,
                            jumlahTunggakan = child.child("jumlahTunggakan").getValue(Long::class.java) ?: 0L,
                            totalPiutang = child.child("totalPiutang").getValue(Long::class.java) ?: 0L,
                            wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
                            timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                        ))
                    }
                }
                _pengawasPelangganBermasalah.value = bermasalahList.sortedByDescending { it.hariTunggakan }

                // Load biaya awal dari semua admin di cabang yang dipilih
                val biayaAwalList = mutableListOf<BiayaAwalItem>()
                val today = getTodayIndonesiaFormat()  // Sudah ada, gunakan variabel ini
                val todayForBiayaAwal = SimpleDateFormat("yyyy-MM-dd", Locale("id")).format(Date())

// Ambil adminList dari semua cabang yang dipilih
                val targetCabangList = _allCabangList.value.filter {
                    cabangIds.contains(it.cabangId)
                }

                for (cabang in targetCabangList) {
                    for (adminUid in cabang.adminList) {
                        try {
                            val snap = database.child("biaya_awal/$adminUid/$todayForBiayaAwal").get().await()
                            if (snap.exists()) {
                                val jumlah = snap.child("jumlah").getValue(Long::class.java) ?: 0L
                                if (jumlah > 0) {
                                    // Ambil nama admin
                                    val adminSnap = database.child("metadata/admins/$adminUid").get().await()
                                    val adminName = adminSnap.child("name").getValue(String::class.java)
                                        ?: adminSnap.child("email").getValue(String::class.java)
                                        ?: adminUid

                                    biayaAwalList.add(BiayaAwalItem(
                                        adminUid = adminUid,
                                        adminName = adminName,
                                        cabangId = cabang.cabangId,
                                        jumlah = jumlah,
                                        tanggal = todayForBiayaAwal,
                                        timestamp = snap.child("timestamp").getValue(Long::class.java) ?: 0L
                                    ))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading biaya_awal for admin $adminUid: ${e.message}")
                        }
                    }
                }
                _pengawasBiayaAwal.value = biayaAwalList
                Log.d(TAG, "✅ Loaded ${biayaAwalList.size} biaya awal entries")
                // Load uang kas dari kasir untuk semua admin
                val kasirUangKasList = mutableListOf<BiayaAwalItem>()
                val bulanKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

                for (cabang in targetCabangList) {
                    try {
                        val kasirSnap = database.child("kasir_entries/${cabang.cabangId}/$bulanKey").get().await()
                        kasirSnap.children.forEach { entrySnap ->
                            val jenis = entrySnap.child("jenis").getValue(String::class.java) ?: ""
                            val arah = entrySnap.child("arah").getValue(String::class.java) ?: ""
                            val tanggal = entrySnap.child("tanggal").getValue(String::class.java) ?: ""
                            val targetUid = entrySnap.child("targetAdminUid").getValue(String::class.java) ?: ""

                            if (jenis == "uang_kas" && arah == "keluar" && tanggal == today && targetUid.isNotBlank()) {
                                val jumlah = entrySnap.child("jumlah").getValue(Long::class.java) ?: 0L
                                val targetName = entrySnap.child("targetAdminName").getValue(String::class.java) ?: targetUid

                                kasirUangKasList.add(BiayaAwalItem(
                                    adminUid = targetUid,
                                    adminName = targetName,
                                    cabangId = cabang.cabangId,
                                    jumlah = jumlah,
                                    tanggal = tanggal,
                                    timestamp = entrySnap.child("createdAt").getValue(Long::class.java) ?: 0L
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading kasir_entries for cabang ${cabang.cabangId}: ${e.message}")
                    }
                }
                _pengawasKasirUangKas.value = kasirUangKasList
                Log.d(TAG, "✅ Loaded ${kasirUangKasList.size} kasir uang kas entries")

                Log.d(TAG, "✅ Daily data loaded: ${nasabahBaruList.size} baru, ${nasabahLunasList.size} lunas, ${bermasalahList.size} bermasalah")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading daily data: ${e.message}")
            }
        }
    }

    private fun getTodayIndonesiaFormat(): String {
        val now = java.util.Date()
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
            "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta"))
        calendar.time = now

        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val month = months[calendar.get(java.util.Calendar.MONTH)]
        val year = calendar.get(java.util.Calendar.YEAR)

        return "$day $month $year"
    }

    fun refreshPengawasData() {
        if (!smartLoader.isOnline()) {
            Log.w("Refresh", "⚠️ Pengawas refresh requires internet")
            return
        }

        viewModelScope.launch {
            try {
                isLoading.value = true

                // Reset flag agar bisa reload
                _pengawasDataLoaded.value = false

                // Reload semua data
                val cabangList = _allCabangList.value.ifEmpty {
                    // Jika cabang list kosong, load dari Firebase
                    val cabangSnap = database.child("metadata/cabang").get().await()
                    val list = mutableListOf<CabangMetadata>()
                    cabangSnap.children.forEach { child ->
                        val cabangId = child.key ?: return@forEach
                        val name = child.child("name").getValue(String::class.java) ?: cabangId
                        val pimpinanUid = child.child("pimpinanUid").getValue(String::class.java) ?: ""
                        val adminList = child.child("adminList").children.mapNotNull {
                            it.getValue(String::class.java)
                        }
                        list.add(CabangMetadata(cabangId, name, pimpinanUid, adminList))
                    }
                    _allCabangList.value = list
                    list
                }

                // Reload summaries
                loadPengawasCabangSummaries(cabangList)
                loadPengawasAdminSummaries(cabangList)
                loadPengawasDailyData()

                _pengawasDataLoaded.value = true
                Log.d(TAG, "✅ Pengawas data refreshed")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error refreshing pengawas data: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }
    }

    fun loadPendingApprovalsForPengawas() {
        viewModelScope.launch {
            try {
                Log.d("PengawasApproval", "🔄 Loading pending approvals for Pengawas...")

                val allCabangSnap = database.child("metadata/cabang").get().await()
                val cabangIds = allCabangSnap.children.mapNotNull { it.key }

                val pendingList = mutableListOf<Pelanggan>()

                for (cabangId in cabangIds) {
                    val pengajuanSnap = database.child("pengajuan_approval/$cabangId").get().await()

                    pengajuanSnap.children.forEach { child ->
                        try {
                            val pelangganId = child.child("pelangganId").getValue(String::class.java) ?: return@forEach
                            val besarPinjaman = child.child("besarPinjaman").getValue(Int::class.java) ?: 0

                            // Filter: hanya >= 3jt
                            if (besarPinjaman >= DualApprovalThreshold.MINIMUM_AMOUNT) {
                                // ✅ BARU: Cek berdasarkan approvalPhase
                                val approvalPhase = child.child("dualApprovalInfo/approvalPhase")
                                    .getValue(String::class.java) ?: ApprovalPhase.AWAITING_PIMPINAN

                                Log.d("PengawasApproval", "📋 Pelanggan $pelangganId: phase=$approvalPhase")

                                // ✅ BARU: Hanya tampilkan yang sudah Phase 2 (setelah Pimpinan review)
                                if (approvalPhase == ApprovalPhase.AWAITING_PENGAWAS) {
                                    val adminUid = child.child("adminUid").getValue(String::class.java) ?: ""
                                    val pelangganSnap = database.child("pelanggan/$adminUid/$pelangganId").get().await()

                                    if (pelangganSnap.exists()) {
                                        val pelanggan = pelangganSnap.getValue(Pelanggan::class.java)
                                        if (pelanggan != null && pelanggan.status == "Menunggu Approval") {
                                            pendingList.add(pelanggan.copy(
                                                id = pelangganId,
                                                adminUid = adminUid,
                                                cabangId = cabangId
                                            ))
                                            Log.d("PengawasApproval", "✅ Added: ${pelanggan.namaPanggilan}")
                                        }
                                    }
                                } else {
                                    Log.d("PengawasApproval", "⏭️ Skipped (phase: $approvalPhase): $pelangganId")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("PengawasApproval", "Error parsing pengajuan: ${e.message}")
                        }
                    }
                }

                // Fallback: juga scan pelanggan nodes untuk record yang entri pengajuan_approval-nya hilang
                val foundPelangganIdsPengawas = pendingList.map { it.id }.toSet().toMutableSet()
                for (cabangId in cabangIds) {
                    try {
                        val adminListSnap = database.child("metadata/cabang/$cabangId/adminList").get().await()
                        val adminList = adminListSnap.children.mapNotNull { it.getValue(String::class.java) }
                        for (adminUid in adminList) {
                            val pelangganSnap = database.child("pelanggan/$adminUid")
                                .orderByChild("status")
                                .equalTo("Menunggu Approval")
                                .get().await()
                            pelangganSnap.children.forEach { child ->
                                val pelangganId = child.key ?: return@forEach
                                if (pelangganId in foundPelangganIdsPengawas) return@forEach
                                val pelanggan = child.getValue(Pelanggan::class.java) ?: return@forEach
                                val phase = pelanggan.dualApprovalInfo?.approvalPhase ?: return@forEach
                                val besar = pelanggan.besarPinjaman
                                if (besar >= DualApprovalThreshold.MINIMUM_AMOUNT && phase == ApprovalPhase.AWAITING_PENGAWAS) {
                                    pendingList.add(pelanggan.copy(id = pelangganId, adminUid = adminUid, cabangId = cabangId))
                                    foundPelangganIdsPengawas.add(pelangganId)
                                    Log.d("PengawasApproval", "✅ Fallback found: ${pelanggan.namaPanggilan}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PengawasApproval", "Error in fallback scan for $cabangId: ${e.message}")
                    }
                }

                _pendingApprovalsPengawas.value = pendingList.sortedByDescending { it.tanggalPengajuan }
                Log.d("PengawasApproval", "✅ Loaded ${pendingList.size} pending approvals for Pengawas")
            } catch (e: Exception) {
                Log.e("PengawasApproval", "❌ Error loading: ${e.message}")
            }
        }
    }

    /**
     * Load pengajuan yang menunggu review Koordinator (Phase 2: AWAITING_KOORDINATOR)
     */
    fun loadPendingApprovalsForKoordinator() {
        viewModelScope.launch {
            try {
                Log.d("KoordinatorApproval", "🔄 Loading pending approvals for Koordinator...")

                val allCabangSnap = database.child("metadata/cabang").get().await()
                val cabangIds = allCabangSnap.children.mapNotNull { it.key }

                val pendingList = mutableListOf<Pelanggan>()

                for (cabangId in cabangIds) {
                    val pengajuanSnap = database.child("pengajuan_approval/$cabangId").get().await()

                    pengajuanSnap.children.forEach { child ->
                        try {
                            val pelangganId = child.child("pelangganId").getValue(String::class.java) ?: return@forEach
                            val besarPinjaman = child.child("besarPinjaman").getValue(Int::class.java) ?: 0

                            if (besarPinjaman >= DualApprovalThreshold.MINIMUM_AMOUNT) {
                                val approvalPhase = child.child("dualApprovalInfo/approvalPhase")
                                    .getValue(String::class.java) ?: ApprovalPhase.AWAITING_PIMPINAN

                                // ✅ Filter: Phase 2 (AWAITING_KOORDINATOR)
                                if (approvalPhase == ApprovalPhase.AWAITING_KOORDINATOR) {
                                    val adminUid = child.child("adminUid").getValue(String::class.java) ?: ""
                                    val pelangganSnap = database.child("pelanggan/$adminUid/$pelangganId").get().await()

                                    if (pelangganSnap.exists()) {
                                        val pelanggan = pelangganSnap.getValue(Pelanggan::class.java)
                                        if (pelanggan != null && pelanggan.status == "Menunggu Approval") {
                                            pendingList.add(pelanggan.copy(
                                                id = pelangganId,
                                                adminUid = adminUid,
                                                cabangId = cabangId
                                            ))
                                            Log.d("KoordinatorApproval", "✅ Added: ${pelanggan.namaPanggilan}")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("KoordinatorApproval", "Error parsing pengajuan: ${e.message}")
                        }
                    }
                }

                // Fallback: juga scan pelanggan nodes untuk record yang entri pengajuan_approval-nya hilang
                val foundPelangganIdsKoordinator = pendingList.map { it.id }.toSet().toMutableSet()
                for (cabangId in cabangIds) {
                    try {
                        val adminListSnap = database.child("metadata/cabang/$cabangId/adminList").get().await()
                        val adminList = adminListSnap.children.mapNotNull { it.getValue(String::class.java) }
                        for (adminUid in adminList) {
                            val pelangganSnap = database.child("pelanggan/$adminUid")
                                .orderByChild("status")
                                .equalTo("Menunggu Approval")
                                .get().await()
                            pelangganSnap.children.forEach { child ->
                                val pelangganId = child.key ?: return@forEach
                                if (pelangganId in foundPelangganIdsKoordinator) return@forEach
                                val pelanggan = child.getValue(Pelanggan::class.java) ?: return@forEach
                                val phase = pelanggan.dualApprovalInfo?.approvalPhase ?: return@forEach
                                val besar = pelanggan.besarPinjaman
                                if (besar >= DualApprovalThreshold.MINIMUM_AMOUNT && phase == ApprovalPhase.AWAITING_KOORDINATOR) {
                                    pendingList.add(pelanggan.copy(id = pelangganId, adminUid = adminUid, cabangId = cabangId))
                                    foundPelangganIdsKoordinator.add(pelangganId)
                                    Log.d("KoordinatorApproval", "✅ Fallback found: ${pelanggan.namaPanggilan}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("KoordinatorApproval", "Error in fallback scan for $cabangId: ${e.message}")
                    }
                }

                _pendingApprovalsKoordinator.value = pendingList.sortedByDescending { it.tanggalPengajuan }
                Log.d("KoordinatorApproval", "✅ Loaded ${pendingList.size} pending approvals for Koordinator")
            } catch (e: Exception) {
                Log.e("KoordinatorApproval", "❌ Error loading: ${e.message}")
            }
        }
    }

    /**
     * Load pengajuan yang menunggu finalisasi Koordinator (Phase 4: AWAITING_KOORDINATOR_FINAL)
     */
    fun loadPendingKoordinatorFinal() {
        viewModelScope.launch {
            try {
                Log.d("KoordinatorFinal", "🔄 Loading pending final approvals for Koordinator...")

                val allCabangSnap = database.child("metadata/cabang").get().await()
                val cabangIds = allCabangSnap.children.mapNotNull { it.key }

                val pendingList = mutableListOf<Pelanggan>()
                val addedPelangganIds = mutableSetOf<String>()

                for (cabangId in cabangIds) {
                    val pengajuanSnap = database.child("pengajuan_approval/$cabangId").get().await()

                    pengajuanSnap.children.forEach { child ->
                        try {
                            val pelangganId = child.child("pelangganId").getValue(String::class.java) ?: return@forEach
                            val besarPinjaman = child.child("besarPinjaman").getValue(Int::class.java) ?: 0

                            if (besarPinjaman >= DualApprovalThreshold.MINIMUM_AMOUNT) {
                                val approvalPhase = child.child("dualApprovalInfo/approvalPhase")
                                    .getValue(String::class.java) ?: ApprovalPhase.AWAITING_PIMPINAN

                                // ✅ Filter: Phase 4 (AWAITING_KOORDINATOR_FINAL)
                                if (approvalPhase == ApprovalPhase.AWAITING_KOORDINATOR_FINAL) {
                                    val adminUid = child.child("adminUid").getValue(String::class.java) ?: ""
                                    val pelangganSnap = database.child("pelanggan/$adminUid/$pelangganId").get().await()

                                    if (pelangganSnap.exists()) {
                                        val pelanggan = pelangganSnap.getValue(Pelanggan::class.java)
                                        if (pelanggan != null && !addedPelangganIds.contains(pelangganId)) {
                                            addedPelangganIds.add(pelangganId)
                                            pendingList.add(pelanggan.copy(
                                                id = pelangganId,
                                                adminUid = adminUid,
                                                cabangId = cabangId
                                            ))
                                            Log.d("KoordinatorFinal", "✅ Added: ${pelanggan.namaPanggilan}")
                                        } else if (pelanggan != null) {
                                            Log.d("KoordinatorFinal", "⚠️ Duplikat dilewati: ${pelanggan.namaPanggilan} ($pelangganId)")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("KoordinatorFinal", "Error parsing: ${e.message}")
                        }
                    }
                }

                _pendingKoordinatorFinal.value = pendingList.sortedByDescending { it.tanggalPengajuan }
                Log.d("KoordinatorFinal", "✅ Loaded ${pendingList.size} pending final approvals for Koordinator")
            } catch (e: Exception) {
                Log.e("KoordinatorFinal", "❌ Error: ${e.message}")
            }
        }
    }

    /**
     * Koordinator approve pengajuan (Phase 2 → Phase 3)
     * Setelah Koordinator approve, pindah ke AWAITING_PENGAWAS
     */
    fun approvePengajuanAsKoordinator(
        pelangganId: String,
        catatan: String = "",
        besarPinjamanDisetujui: Int? = null,
        tenorDisetujui: Int? = null,
        catatanPerubahanPinjaman: String = "",
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                Log.d("KoordinatorApproval", "🔍 Processing approval as Koordinator for: $pelangganId")

                val cachedPelanggan = _pendingApprovalsKoordinator.value.find { it.id == pelangganId }

                if (cachedPelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan di pending list"))
                    return@launch
                }

                val freshSnap = database.child("pelanggan")
                    .child(cachedPelanggan.adminUid)
                    .child(pelangganId)
                    .get()
                    .await()

                val pelanggan = freshSnap.getValue(Pelanggan::class.java)?.copy(
                    id = pelangganId,
                    adminUid = cachedPelanggan.adminUid,
                    cabangId = cachedPelanggan.cabangId
                )

                if (pelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }

                val koordinatorUid = Firebase.auth.currentUser?.uid ?: ""
                val koordinatorSnap = database.child("metadata/admins/${koordinatorUid}/name").get().await()
                val koordinatorName = koordinatorSnap.getValue(String::class.java)
                    ?: Firebase.auth.currentUser?.displayName
                    ?: Firebase.auth.currentUser?.email
                    ?: "Koordinator"
                val timestamp = System.currentTimeMillis()

                val currentDualInfo = pelanggan.dualApprovalInfo ?: DualApprovalInfo(requiresDualApproval = true)

                val updatedKoordinatorApproval = IndividualApproval(
                    status = ApprovalStatus.APPROVED,
                    by = koordinatorName,
                    uid = koordinatorUid,
                    timestamp = timestamp,
                    note = catatan
                )

                val finalBesarPinjaman = besarPinjamanDisetujui ?: pelanggan.besarPinjaman
                val finalTenor = tenorDisetujui ?: pelanggan.tenor
                val adaPenyesuaian = besarPinjamanDisetujui != null && besarPinjamanDisetujui != pelanggan.besarPinjaman

                val finalValues = if (adaPenyesuaian) calculatePinjamanValues(finalBesarPinjaman) else null

                // ✅ PINDAH KE PHASE 3: AWAITING_PENGAWAS
                val updatedDualInfo = currentDualInfo.copy(
                    approvalPhase = ApprovalPhase.AWAITING_PENGAWAS,
                    koordinatorApproval = updatedKoordinatorApproval.copy(
                        adjustedAmount = if (adaPenyesuaian) finalBesarPinjaman else 0,
                        adjustedTenor = if (tenorDisetujui != null && tenorDisetujui != pelanggan.tenor) finalTenor else 0
                    )
                )

                val updatedPelanggan = pelanggan.copy(
                    status = "Menunggu Approval",
                    dualApprovalInfo = updatedDualInfo,
                    besarPinjaman = finalBesarPinjaman,
                    besarPinjamanDisetujui = if (adaPenyesuaian) finalBesarPinjaman else pelanggan.besarPinjamanDisetujui,
                    tenor = finalTenor,
                    admin = finalValues?.admin ?: pelanggan.admin,
                    simpanan = finalValues?.simpanan ?: pelanggan.simpanan,
                    totalPelunasan = finalValues?.totalPelunasan ?: pelanggan.totalPelunasan,
                    totalDiterima = finalValues?.totalDiterima ?: pelanggan.totalDiterima,
                    catatanPerubahanPinjaman = if (adaPenyesuaian) catatanPerubahanPinjaman else pelanggan.catatanPerubahanPinjaman,
                    isPinjamanDiubah = adaPenyesuaian,
                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid ?: "" }

                database.child("pelanggan").child(adminUid).child(pelangganId)
                    .setValue(updatedPelanggan)
                    .addOnSuccessListener {
                        updatePengajuanApprovalDualStatusWithCallback(pelangganId, updatedDualInfo) {
                            loadPendingApprovalsForKoordinator()
                            Log.d("KoordinatorApproval", "✅ Phase 2 complete - moved to Phase 3 (AWAITING_PENGAWAS)")
                            onSuccess?.invoke()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("KoordinatorApproval", "❌ Failed: ${e.message}")
                        onFailure?.invoke(e)
                    }

            } catch (e: Exception) {
                Log.e("KoordinatorApproval", "❌ Error: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    /**
     * Koordinator reject pengajuan (Phase 2 → Phase 3)
     * Setelah Koordinator reject, tetap pindah ke AWAITING_PENGAWAS agar Pengawas bisa review
     */
    fun rejectPengajuanAsKoordinator(
        pelangganId: String,
        alasan: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                Log.d("KoordinatorApproval", "🔍 Processing rejection as Koordinator for: $pelangganId")

                val cachedPelanggan = _pendingApprovalsKoordinator.value.find { it.id == pelangganId }

                if (cachedPelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan di pending list"))
                    return@launch
                }

                val freshSnap = database.child("pelanggan")
                    .child(cachedPelanggan.adminUid)
                    .child(pelangganId)
                    .get()
                    .await()

                val pelanggan = freshSnap.getValue(Pelanggan::class.java)?.copy(
                    id = pelangganId,
                    adminUid = cachedPelanggan.adminUid,
                    cabangId = cachedPelanggan.cabangId
                )

                if (pelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }

                val koordinatorUid = Firebase.auth.currentUser?.uid ?: ""
                val koordinatorSnap = database.child("metadata/admins/${koordinatorUid}/name").get().await()
                val koordinatorName = koordinatorSnap.getValue(String::class.java)
                    ?: Firebase.auth.currentUser?.displayName
                    ?: Firebase.auth.currentUser?.email
                    ?: "Koordinator"
                val timestamp = System.currentTimeMillis()

                val currentDualInfo = pelanggan.dualApprovalInfo ?: DualApprovalInfo(requiresDualApproval = true)

                val updatedKoordinatorApproval = IndividualApproval(
                    status = ApprovalStatus.REJECTED,
                    by = koordinatorName,
                    uid = koordinatorUid,
                    timestamp = timestamp,
                    note = alasan
                )

                // ✅ PINDAH KE PHASE 3: AWAITING_PENGAWAS (agar Pengawas bisa review juga)
                val updatedDualInfo = currentDualInfo.copy(
                    approvalPhase = ApprovalPhase.AWAITING_PENGAWAS,
                    koordinatorApproval = updatedKoordinatorApproval
                )

                val updatedPelanggan = pelanggan.copy(
                    status = "Menunggu Approval",
                    dualApprovalInfo = updatedDualInfo,
                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid ?: "" }

                database.child("pelanggan").child(adminUid).child(pelangganId)
                    .setValue(updatedPelanggan)
                    .addOnSuccessListener {
                        updatePengajuanApprovalDualStatusWithCallback(pelangganId, updatedDualInfo) {
                            loadPendingApprovalsForKoordinator()
                            Log.d("KoordinatorApproval", "✅ Phase 2 REJECT complete - moved to Phase 3")
                            onSuccess?.invoke()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("KoordinatorApproval", "❌ Failed: ${e.message}")
                        onFailure?.invoke(e)
                    }

            } catch (e: Exception) {
                Log.e("KoordinatorApproval", "❌ Error: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    /**
     * Koordinator finalisasi (Phase 4 → Phase 5)
     * Setelah Pengawas review, Koordinator konfirmasi dan pindah ke AWAITING_PIMPINAN_FINAL
     */
    fun finalizeKoordinatorApproval(
        pelangganId: String,
        catatan: String = "",
        tarikTabungan: Int = 0,  // ✅ TAMBAH PARAMETER
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                Log.d("KoordinatorFinal", "🔍 Processing finalization as Koordinator for: $pelangganId")
                Log.d("KoordinatorFinal", "   Tarik Tabungan: $tarikTabungan")

                val cachedPelanggan = _pendingKoordinatorFinal.value.find { it.id == pelangganId }

                if (cachedPelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan di pending list"))
                    return@launch
                }

                val freshSnap = database.child("pelanggan")
                    .child(cachedPelanggan.adminUid)
                    .child(pelangganId)
                    .get()
                    .await()

                val pelanggan = freshSnap.getValue(Pelanggan::class.java)?.copy(
                    id = pelangganId,
                    adminUid = cachedPelanggan.adminUid,
                    cabangId = cachedPelanggan.cabangId
                )

                if (pelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }

                val koordinatorUid = Firebase.auth.currentUser?.uid ?: ""
                val timestamp = System.currentTimeMillis()

                val currentDualInfo = pelanggan.dualApprovalInfo ?: DualApprovalInfo(requiresDualApproval = true)

                // ✅ PINDAH KE PHASE 5: AWAITING_PIMPINAN_FINAL
// ✅ FIX: Simpan catatan finalisasi Koordinator ke koordinatorApproval.note
                val existingKoordinatorNote = currentDualInfo.koordinatorApproval.note
                val finalKoordinatorNote = if (catatan.isNotBlank()) {
                    if (existingKoordinatorNote.isNotBlank() && existingKoordinatorNote != catatan) {
                        "$existingKoordinatorNote | Finalisasi: $catatan"
                    } else {
                        catatan
                    }
                } else {
                    existingKoordinatorNote
                }

                val updatedDualInfo = currentDualInfo.copy(
                    approvalPhase = ApprovalPhase.AWAITING_PIMPINAN_FINAL,
                    koordinatorFinalConfirmed = true,
                    koordinatorFinalTimestamp = timestamp,
                    koordinatorApproval = currentDualInfo.koordinatorApproval.copy(
                        note = finalKoordinatorNote
                    )
                )

                // ✅ HITUNG ULANG JIKA ADA TARIK TABUNGAN
                val finalSimpanan: Int
                val finalTotalDiterima: Int
                val catatanTarik: String

                if (tarikTabungan > 0) {
                    // Simpanan bertambah dari tarik tabungan
                    finalSimpanan = pelanggan.simpanan + tarikTabungan
                    // Total diterima berkurang karena tabungan ditarik
                    finalTotalDiterima = pelanggan.totalDiterima - tarikTabungan
                    catatanTarik = "$catatan. Tarik tabungan: Rp ${formatRupiahSimple(tarikTabungan)}"

                    Log.d("KoordinatorFinal", "📊 Tarik Tabungan:")
                    Log.d("KoordinatorFinal", "   Simpanan lama: ${pelanggan.simpanan}")
                    Log.d("KoordinatorFinal", "   Simpanan baru: $finalSimpanan")
                    Log.d("KoordinatorFinal", "   Total diterima lama: ${pelanggan.totalDiterima}")
                    Log.d("KoordinatorFinal", "   Total diterima baru: $finalTotalDiterima")
                } else {
                    finalSimpanan = pelanggan.simpanan
                    finalTotalDiterima = pelanggan.totalDiterima
                    catatanTarik = catatan
                }

                val updatedPelanggan = pelanggan.copy(
                    status = "Menunggu Approval",
                    dualApprovalInfo = updatedDualInfo,
                    simpanan = finalSimpanan,
                    totalDiterima = finalTotalDiterima,
                    tarikTabungan = if (tarikTabungan > 0) tarikTabungan else pelanggan.tarikTabungan,
                    catatanPerubahanPinjaman = if (tarikTabungan > 0) {
                        val existing = pelanggan.catatanPerubahanPinjaman
                        if (existing.isNotBlank()) "$existing. Koordinator tarik tabungan: Rp ${formatRupiahSimple(tarikTabungan)}"
                        else "Koordinator tarik tabungan: Rp ${formatRupiahSimple(tarikTabungan)}"
                    } else {
                        pelanggan.catatanPerubahanPinjaman
                    },
                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid ?: "" }

                database.child("pelanggan").child(adminUid).child(pelangganId)
                    .setValue(updatedPelanggan)
                    .addOnSuccessListener {
                        updatePengajuanApprovalDualStatusWithCallback(pelangganId, updatedDualInfo) {
                            loadPendingKoordinatorFinal()
                            Log.d("KoordinatorFinal", "✅ Phase 4 complete - moved to Phase 5 (AWAITING_PIMPINAN_FINAL)")
                            onSuccess?.invoke()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("KoordinatorFinal", "❌ Failed: ${e.message}")
                        onFailure?.invoke(e)
                    }

            } catch (e: Exception) {
                Log.e("KoordinatorFinal", "❌ Error: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    // Helper function untuk format rupiah sederhana
    private fun formatRupiahSimple(amount: Int): String {
        return java.text.NumberFormat.getNumberInstance(java.util.Locale("in", "ID")).format(amount)
    }

    fun approvePengajuanAsPengawas(
        pelangganId: String,
        catatan: String = "",
        besarPinjamanDisetujui: Int? = null,
        tenorDisetujui: Int? = null,
        catatanPerubahanPinjaman: String = "",
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                Log.d("PengawasApproval", "🔍 Processing approval as Pengawas for: $pelangganId")

                // Ambil data dasar dari cache untuk mendapatkan adminUid dan cabangId
                val cachedPelanggan = _pendingApprovalsPengawas.value.find { it.id == pelangganId }

                if (cachedPelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan di pending list"))
                    return@launch
                }

                // PENTING: Baca data FRESH dari Firebase untuk mendapatkan dualApprovalInfo terbaru
                val freshSnap = database.child("pelanggan")
                    .child(cachedPelanggan.adminUid)
                    .child(pelangganId)
                    .get()
                    .await()

                val pelanggan = freshSnap.getValue(Pelanggan::class.java)?.copy(
                    id = pelangganId,
                    adminUid = cachedPelanggan.adminUid,
                    cabangId = cachedPelanggan.cabangId
                )

                if (pelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }

                val pengawasUid = Firebase.auth.currentUser?.uid ?: ""
                val pengawasSnap = database.child("metadata/admins/$pengawasUid/name").get().await()
                val pengawasName = pengawasSnap.getValue(String::class.java)
                    ?: Firebase.auth.currentUser?.displayName
                    ?: Firebase.auth.currentUser?.email
                    ?: "Pengawas"
                val timestamp = System.currentTimeMillis()

                val currentDualInfo = pelanggan.dualApprovalInfo ?: DualApprovalInfo(
                    requiresDualApproval = true
                )

                val updatedPengawasApproval = IndividualApproval(
                    status = ApprovalStatus.APPROVED,
                    by = pengawasName,
                    uid = pengawasUid,
                    timestamp = timestamp,
                    note = catatan
                )

                val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid ?: "" }

                // =========================================================================
                // ✅ PERBAIKAN: SELALU PINDAH KE PHASE 3 (AWAITING_PIMPINAN_FINAL)
                // =========================================================================
                // Dalam alur sequential, setelah Pengawas aksi, HARUS ke Phase 3
                // agar Pimpinan mendapat notifikasi dan melakukan finalisasi.
                // Cloud function onPengawasReviewed akan mengirim notifikasi ke Pimpinan.
                // =========================================================================

                Log.d("PengawasApproval", "✅ Phase 2: Pengawas APPROVE - moving to Phase 3 (AWAITING_PIMPINAN_FINAL)")

                val finalBesarPinjaman = besarPinjamanDisetujui ?: pelanggan.besarPinjaman
                val finalTenor = tenorDisetujui ?: pelanggan.tenor
                val adaPenyesuaianPengawas = besarPinjamanDisetujui != null && besarPinjamanDisetujui != pelanggan.besarPinjaman

                val finalValues = if (adaPenyesuaianPengawas) {
                    calculatePinjamanValues(finalBesarPinjaman)
                } else {
                    null
                }

                val updatedDualInfo = currentDualInfo.copy(
                    approvalPhase = ApprovalPhase.AWAITING_KOORDINATOR_FINAL, // ✅ PINDAH KE PHASE 4 (KOORDINATOR FINAL)
                    pengawasApproval = updatedPengawasApproval.copy(
                        adjustedAmount = if (adaPenyesuaianPengawas) finalBesarPinjaman else 0,
                        adjustedTenor = if (tenorDisetujui != null && tenorDisetujui != pelanggan.tenor) finalTenor else 0
                    )
                )

                val updatedPelanggan = pelanggan.copy(
                    status = "Menunggu Approval", // Status TETAP menunggu (belum final)
                    dualApprovalInfo = updatedDualInfo,
                    besarPinjaman = finalBesarPinjaman,
                    besarPinjamanDisetujui = if (adaPenyesuaianPengawas) finalBesarPinjaman else pelanggan.besarPinjamanDisetujui,
                    tenor = finalTenor,
                    admin = finalValues?.admin ?: pelanggan.admin,
                    simpanan = finalValues?.simpanan ?: pelanggan.simpanan,
                    totalPelunasan = finalValues?.totalPelunasan ?: pelanggan.totalPelunasan,
                    totalDiterima = finalValues?.totalDiterima ?: pelanggan.totalDiterima,
                    catatanPerubahanPinjaman = if (adaPenyesuaianPengawas) catatanPerubahanPinjaman else pelanggan.catatanPerubahanPinjaman,
                    isPinjamanDiubah = adaPenyesuaianPengawas,
                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                database.child("pelanggan").child(adminUid).child(pelangganId)
                    .setValue(updatedPelanggan)
                    .addOnSuccessListener {
                        // ✅ Update pengajuan_approval untuk trigger cloud function
                        // Cloud function onPengawasReviewed akan mengirim notifikasi ke Pimpinan
                        updatePengajuanApprovalDualStatusWithCallback(pelangganId, updatedDualInfo) {
                            loadPendingApprovalsForPengawas()
                            Log.d("PengawasApproval", "✅ Phase 3 complete - moved to Phase 4 (AWAITING_KOORDINATOR_FINAL)")
                            Log.d("PengawasApproval", "✅ Cloud function akan kirim notifikasi ke Koordinator untuk konfirmasi")
                            onSuccess?.invoke()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("PengawasApproval", "❌ Failed: ${e.message}")
                        onFailure?.invoke(e)
                    }

            } catch (e: Exception) {
                Log.e("PengawasApproval", "❌ Error: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    fun rejectPengajuanAsPengawas(
        pelangganId: String,
        alasan: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                Log.d("PengawasApproval", "🔍 Processing rejection as Pengawas for: $pelangganId")

                // Ambil data dasar dari cache untuk mendapatkan adminUid dan cabangId
                val cachedPelanggan = _pendingApprovalsPengawas.value.find { it.id == pelangganId }

                if (cachedPelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan di pending list"))
                    return@launch
                }

                // PENTING: Baca data FRESH dari Firebase untuk mendapatkan dualApprovalInfo terbaru
                val freshSnap = database.child("pelanggan")
                    .child(cachedPelanggan.adminUid)
                    .child(pelangganId)
                    .get()
                    .await()

                val pelanggan = freshSnap.getValue(Pelanggan::class.java)?.copy(
                    id = pelangganId,
                    adminUid = cachedPelanggan.adminUid,
                    cabangId = cachedPelanggan.cabangId
                )

                if (pelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }

                val pengawasUid = Firebase.auth.currentUser?.uid ?: ""
                val pengawasSnap = database.child("metadata/admins/$pengawasUid/name").get().await()
                val pengawasName = pengawasSnap.getValue(String::class.java)
                    ?: Firebase.auth.currentUser?.displayName
                    ?: Firebase.auth.currentUser?.email
                    ?: "Pengawas"
                val timestamp = System.currentTimeMillis()

                // Get current dual approval info
                val currentDualInfo = pelanggan.dualApprovalInfo ?: DualApprovalInfo(
                    requiresDualApproval = true
                )

                // Update pengawas approval ke rejected
                val updatedPengawasApproval = IndividualApproval(
                    status = ApprovalStatus.REJECTED,
                    by = pengawasName,
                    uid = pengawasUid,
                    timestamp = timestamp,
                    note = alasan
                )

                Log.d("PengawasApproval", "📊 Pengawas REJECT - moving to Phase 3")

                // =========================================================================
                // ✅ PERBAIKAN: SELALU PINDAH KE PHASE 3 (AWAITING_PIMPINAN_FINAL)
                // =========================================================================
                // Dalam alur sequential, setelah Pengawas aksi, HARUS ke Phase 3
                // agar Pimpinan mendapat notifikasi dan melakukan finalisasi.
                // Cloud function onPengawasReviewed akan mengirim notifikasi ke Pimpinan.
                // =========================================================================

                val updatedDualInfo = currentDualInfo.copy(
                    approvalPhase = ApprovalPhase.AWAITING_KOORDINATOR_FINAL, // ✅ PINDAH KE PHASE 4 (KOORDINATOR FINAL)
                    pengawasApproval = updatedPengawasApproval
                )

                val updatedPelanggan = pelanggan.copy(
                    status = "Menunggu Approval", // Status TETAP menunggu (belum final)
                    dualApprovalInfo = updatedDualInfo,
                    lastUpdated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid ?: "" }
                database.child("pelanggan").child(adminUid).child(pelangganId)
                    .setValue(updatedPelanggan)
                    .addOnSuccessListener {
                        // ✅ Update pengajuan_approval untuk trigger cloud function
                        // Cloud function onPengawasReviewed akan mengirim notifikasi ke Pimpinan
                        updatePengajuanApprovalDualStatusWithCallback(pelangganId, updatedDualInfo) {
                            loadPendingApprovalsForPengawas()
                            Log.d("PengawasApproval", "✅ Phase 3 REJECT complete - moved to Phase 4 (AWAITING_KOORDINATOR_FINAL)")
                            Log.d("PengawasApproval", "✅ Cloud function akan kirim notifikasi ke Koordinator untuk konfirmasi")
                            onSuccess?.invoke()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("PengawasApproval", "❌ Failed: ${e.message}")
                        onFailure?.invoke(e)
                    }

            } catch (e: Exception) {
                Log.e("PengawasApproval", "❌ Error: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    private fun updatePengajuanApprovalDualStatus(
        pelangganId: String,
        dualApprovalInfo: DualApprovalInfo
    ) {
        // Versi tanpa callback - fire and forget
        updatePengajuanApprovalDualStatusWithCallback(pelangganId, dualApprovalInfo, null)
    }

    // Helper: konversi DualApprovalInfo ke Map untuk disimpan ke Firebase RTDB.
    // Digunakan agar tidak ada field yang hilang saat Firebase serialisasi Kotlin data class.
    private fun buildDualApprovalInfoMap(info: DualApprovalInfo): Map<String, Any?> {
        return mapOf(
            "requiresDualApproval" to info.requiresDualApproval,
            "approvalPhase" to info.approvalPhase,
            "pimpinanApproval" to mapOf(
                "status" to info.pimpinanApproval.status,
                "by" to info.pimpinanApproval.by,
                "uid" to info.pimpinanApproval.uid,
                "timestamp" to info.pimpinanApproval.timestamp,
                "note" to info.pimpinanApproval.note,
                "adjustedAmount" to info.pimpinanApproval.adjustedAmount,
                "adjustedTenor" to info.pimpinanApproval.adjustedTenor
            ),
            "koordinatorApproval" to mapOf(
                "status" to info.koordinatorApproval.status,
                "by" to info.koordinatorApproval.by,
                "uid" to info.koordinatorApproval.uid,
                "timestamp" to info.koordinatorApproval.timestamp,
                "note" to info.koordinatorApproval.note,
                "adjustedAmount" to info.koordinatorApproval.adjustedAmount,
                "adjustedTenor" to info.koordinatorApproval.adjustedTenor
            ),
            "pengawasApproval" to mapOf(
                "status" to info.pengawasApproval.status,
                "by" to info.pengawasApproval.by,
                "uid" to info.pengawasApproval.uid,
                "timestamp" to info.pengawasApproval.timestamp,
                "note" to info.pengawasApproval.note,
                "adjustedAmount" to info.pengawasApproval.adjustedAmount,
                "adjustedTenor" to info.pengawasApproval.adjustedTenor
            ),
            "finalDecision" to info.finalDecision,
            "finalDecisionBy" to info.finalDecisionBy,
            "finalDecisionTimestamp" to info.finalDecisionTimestamp,
            "rejectionReason" to info.rejectionReason,
            "koordinatorFinalConfirmed" to info.koordinatorFinalConfirmed,
            "koordinatorFinalTimestamp" to info.koordinatorFinalTimestamp,
            "pimpinanFinalConfirmed" to info.pimpinanFinalConfirmed,
            "pimpinanFinalTimestamp" to info.pimpinanFinalTimestamp
        )
    }

    // ✅ PERBAIKAN: Versi dengan callback untuk menunggu update selesai
    private fun updatePengajuanApprovalDualStatusWithCallback(
        pelangganId: String,
        dualApprovalInfo: DualApprovalInfo,
        onComplete: (() -> Unit)?
    ) {
        viewModelScope.launch {
            try {
                // Cari pengajuan di semua cabang
                val allCabangSnap = database.child("metadata/cabang").get().await()
                val cabangIds = allCabangSnap.children.mapNotNull { it.key }

                var found = false
                for (cabangId in cabangIds) {
                    val pengajuanSnap = database.child("pengajuan_approval/$cabangId")
                        .orderByChild("pelangganId")
                        .equalTo(pelangganId)
                        .get()
                        .await()

                    if (pengajuanSnap.exists()) {
                        found = true
                        val childCount = pengajuanSnap.childrenCount.toInt()
                        var updatedCount = 0

                        pengajuanSnap.children.forEach { child ->
                            child.ref.child("dualApprovalInfo").setValue(buildDualApprovalInfoMap(dualApprovalInfo))
                                .addOnSuccessListener {
                                    updatedCount++
                                    if (updatedCount >= childCount) {
                                        onComplete?.invoke()
                                    }
                                }
                                .addOnFailureListener {
                                    updatedCount++
                                    if (updatedCount >= childCount) {
                                        onComplete?.invoke()
                                    }
                                }
                        }
                        break
                    }
                }

                if (!found) {
                    // Tidak ditemukan di manapun, tetap panggil callback
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e("DualApproval", "Error updating dual approval status: ${e.message}")
                onComplete?.invoke()
            }
        }
    }

// =========================================================================
// FUNGSI HELPER: Notify Pimpinan tentang keputusan Pengawas
// =========================================================================

    private fun notifyPimpinanAboutPengawasDecision(
        pelanggan: Pelanggan,
        decision: String,
        reason: String = ""
    ) {
        viewModelScope.launch {
            try {
                // Cari pimpinan UID dari cabang pelanggan
                val cabangId = currentUserCabang.value ?: return@launch
                val cabangSnap = database.child("metadata/cabang/$cabangId").get().await()
                val pimpinanUid = cabangSnap.child("pimpinanUid").getValue(String::class.java) ?: return@launch

                val notificationData = mapOf(
                    "type" to "PENGAWAS_DECISION",
                    "title" to if (decision == "approved") "Pengawas Menyetujui Pengajuan" else "Pengawas Menolak Pengajuan",
                    "message" to if (decision == "approved") {
                        "Pengajuan ${pelanggan.namaPanggilan} telah disetujui oleh Pengawas"
                    } else {
                        "Pengajuan ${pelanggan.namaPanggilan} ditolak oleh Pengawas. Alasan: $reason"
                    },
                    "pelangganId" to pelanggan.id,
                    "pelangganNama" to pelanggan.namaPanggilan,
                    "decision" to decision,
                    "reason" to reason,
                    "timestamp" to ServerValue.TIMESTAMP,
                    "read" to false
                )

                database.child("admin_notifications/$pimpinanUid").push().setValue(notificationData)
                Log.d("DualApproval", "✅ Notified pimpinan about pengawas decision")

            } catch (e: Exception) {
                Log.e("DualApproval", "Error notifying pimpinan: ${e.message}")
            }
        }
    }

// =========================================================================
// FUNGSI HELPER: Mark Pengawas Notifications as Read
// =========================================================================

    fun markAllPengawasPengajuanNotificationsAsRead() {
        viewModelScope.launch {
            try {
                val pengawasUid = Firebase.auth.currentUser?.uid ?: return@launch

                val notifSnap = database.child("pengawas_notifications/$pengawasUid")
                    .orderByChild("read")
                    .equalTo(false)
                    .get()
                    .await()

                notifSnap.children.forEach { child ->
                    child.ref.child("read").setValue(true)
                }

                _unreadPengawasNotificationCount.value = 0
                Log.d("PengawasApproval", "✅ Marked all notifications as read")

            } catch (e: Exception) {
                Log.e("PengawasApproval", "Error marking notifications: ${e.message}")
            }
        }
    }

    private suspend fun sendSerahTerimaNotificationToPengawas(
        pelanggan: Pelanggan,
        adminName: String,
        fotoUrl: String,
        tanggalSerahTerima: String
    ) {
        try {
            Log.d("SerahTerimaPengawas", "🔍 Mencari semua pengawas...")

            // Dapatkan semua pengawas dari metadata/roles/pengawas
            val pengawasSnapshot = database.child("metadata/roles/pengawas").get().await()

            if (!pengawasSnapshot.exists()) {
                Log.w("SerahTerimaPengawas", "⚠️ Tidak ada pengawas terdaftar")
                return
            }

            val pengawasUids = pengawasSnapshot.children.mapNotNull { it.key }
            Log.d("SerahTerimaPengawas", "👥 Found ${pengawasUids.size} pengawas")

            for (pengawasUid in pengawasUids) {
                val notificationId = "serah_terima_pengawas_${pelanggan.id}_${System.currentTimeMillis()}"

                val notification = SerahTerimaNotification(
                    id = notificationId,
                    type = "SERAH_TERIMA",
                    title = "Bukti Serah Terima Uang",
                    message = "Admin $adminName telah menyerahkan uang pinjaman kepada ${pelanggan.namaPanggilan}",
                    pelangganId = pelanggan.id,
                    pelangganNama = pelanggan.namaPanggilan,
                    adminUid = pelanggan.adminUid,
                    adminName = adminName,
                    besarPinjaman = pelanggan.besarPinjaman,
                    tenor = pelanggan.tenor,
                    fotoSerahTerimaUrl = fotoUrl,
                    tanggalSerahTerima = tanggalSerahTerima,
                    timestamp = System.currentTimeMillis(),
                    read = false
                )

                // Simpan ke Firebase - path berbeda dari pimpinan!
                database.child("pengawas_serah_terima_notifications")
                    .child(pengawasUid)
                    .child(notificationId)
                    .setValue(notification)
                    .await()

                Log.d("SerahTerimaPengawas", "✅ Notifikasi dikirim ke pengawas: $pengawasUid")
            }

            Log.d("SerahTerimaPengawas", "✅ Semua pengawas sudah dinotifikasi")

        } catch (e: Exception) {
            Log.e("SerahTerimaPengawas", "❌ Gagal kirim notifikasi: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * =========================================================================
     * BARU: Kirim notifikasi serah terima ke KOORDINATOR
     * =========================================================================
     */
    private suspend fun sendSerahTerimaNotificationToKoordinator(
        pelanggan: Pelanggan,
        adminName: String,
        fotoUrl: String,
        tanggalSerahTerima: String
    ) {
        try {
            Log.d("SerahTerimaKoordinator", "🔍 Mencari semua koordinator...")

            // Dapatkan semua koordinator dari metadata/roles/koordinator
            val koordinatorSnapshot = database.child("metadata/roles/koordinator").get().await()

            if (!koordinatorSnapshot.exists()) {
                Log.w("SerahTerimaKoordinator", "⚠️ Tidak ada koordinator terdaftar")
                return
            }

            val koordinatorUids = koordinatorSnapshot.children.mapNotNull { it.key }
            Log.d("SerahTerimaKoordinator", "👥 Found ${koordinatorUids.size} koordinator")

            for (koordinatorUid in koordinatorUids) {
                val notificationId = "serah_terima_koordinator_${pelanggan.id}_${System.currentTimeMillis()}"

                val notification = SerahTerimaNotification(
                    id = notificationId,
                    type = "SERAH_TERIMA",
                    title = "Bukti Serah Terima Uang",
                    message = "Admin $adminName telah menyerahkan uang pinjaman kepada ${pelanggan.namaPanggilan}",
                    pelangganId = pelanggan.id,
                    pelangganNama = pelanggan.namaPanggilan,
                    adminUid = pelanggan.adminUid,
                    adminName = adminName,
                    besarPinjaman = pelanggan.besarPinjaman,
                    tenor = pelanggan.tenor,
                    fotoSerahTerimaUrl = fotoUrl,
                    tanggalSerahTerima = tanggalSerahTerima,
                    timestamp = System.currentTimeMillis(),
                    read = false
                )

                // Simpan ke Firebase - path sama dengan pengawas agar bisa share UI
                // Karena KoordinatorApprovalScreen sudah pakai pengawasSerahTerimaNotifications
                database.child("pengawas_serah_terima_notifications")
                    .child(koordinatorUid)
                    .child(notificationId)
                    .setValue(notification)
                    .await()

                Log.d("SerahTerimaKoordinator", "✅ Notifikasi dikirim ke koordinator: $koordinatorUid")
            }

            Log.d("SerahTerimaKoordinator", "✅ Semua koordinator sudah dinotifikasi")

        } catch (e: Exception) {
            Log.e("SerahTerimaKoordinator", "❌ Gagal kirim notifikasi: ${e.message}")
            e.printStackTrace()
        }
    }


// =========================================================================
// BAGIAN 3: TAMBAH FUNGSI LOAD SERAH TERIMA UNTUK PENGAWAS
// LOKASI: Setelah fungsi loadSerahTerimaNotifications (sekitar baris 7098)
// =========================================================================

    /**
     * Load notifikasi serah terima untuk PENGAWAS
     * Path: pengawas_serah_terima_notifications/{pengawasUid}
     */
    fun loadSerahTerimaNotificationsForPengawas() {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                Log.d("SerahTerimaPengawas", "📥 Loading serah terima notifications for Pengawas: $currentUid")

                val snapshot = database.child("pengawas_serah_terima_notifications")
                    .child(currentUid)
                    .orderByChild("timestamp")
                    .limitToLast(50)
                    .get()
                    .await()

                val notifications = mutableListOf<SerahTerimaNotification>()
                var unreadCount = 0

                for (child in snapshot.children) {
                    try {
                        val notif = child.getValue(SerahTerimaNotification::class.java)
                        if (notif != null) {
                            notifications.add(notif)
                            if (!notif.read) unreadCount++
                        }
                    } catch (e: Exception) {
                        Log.e("SerahTerimaPengawas", "Error parsing: ${e.message}")
                    }
                }

                val sortedList = notifications.sortedByDescending { it.timestamp }

                _pengawasSerahTerimaNotifications.value = sortedList
                _unreadPengawasSerahTerimaCount.value = unreadCount

                Log.d("SerahTerimaPengawas", "✅ Loaded ${sortedList.size} notifications, $unreadCount unread")

                // Cleanup notifikasi lama
                cleanupOldPengawasSerahTerimaNotifications(currentUid)

            } catch (e: Exception) {
                Log.e("SerahTerimaPengawas", "❌ Error: ${e.message}")
            }
        }
    }

    private fun cleanupOldPengawasSerahTerimaNotifications(pengawasUid: String) {
        viewModelScope.launch {
            try {
                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)

                val oldNotifications = database.child("pengawas_serah_terima_notifications")
                    .child(pengawasUid)
                    .orderByChild("timestamp")
                    .endAt(thirtyDaysAgo.toDouble())
                    .get()
                    .await()

                var deletedCount = 0
                for (child in oldNotifications.children) {
                    val isRead = child.child("read").getValue(Boolean::class.java) ?: false
                    if (isRead) {
                        child.ref.removeValue()
                        deletedCount++
                    }
                }

                if (deletedCount > 0) {
                    Log.d("SerahTerimaPengawas", "🗑️ Cleaned up $deletedCount old notifications")
                }
            } catch (e: Exception) {
                Log.w("SerahTerimaPengawas", "Cleanup failed: ${e.message}")
            }
        }
    }

    fun refreshPengawasSerahTerimaNotifications() {
        loadSerahTerimaNotificationsForPengawas()
    }

    fun markPengawasSerahTerimaNotificationAsRead(notificationId: String) {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                database.child("pengawas_serah_terima_notifications")
                    .child(currentUid)
                    .child(notificationId)
                    .child("read")
                    .setValue(true)
                    .await()

                // Update local state
                val updated = _pengawasSerahTerimaNotifications.value.map {
                    if (it.id == notificationId) it.copy(read = true) else it
                }
                _pengawasSerahTerimaNotifications.value = updated

                // Update count
                val newCount = updated.count { !it.read }
                _unreadPengawasSerahTerimaCount.value = newCount

                Log.d("SerahTerimaPengawas", "✅ Marked as read: $notificationId")
            } catch (e: Exception) {
                Log.e("SerahTerimaPengawas", "❌ Error marking as read: ${e.message}")
            }
        }
    }

    fun markAllPengawasSerahTerimaAsRead() {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                val unreadNotifications = _pengawasSerahTerimaNotifications.value.filter { !it.read }

                for (notif in unreadNotifications) {
                    database.child("pengawas_serah_terima_notifications")
                        .child(currentUid)
                        .child(notif.id)
                        .child("read")
                        .setValue(true)
                }

                // Update local state
                val updated = _pengawasSerahTerimaNotifications.value.map { it.copy(read = true) }
                _pengawasSerahTerimaNotifications.value = updated
                _unreadPengawasSerahTerimaCount.value = 0

                Log.d("SerahTerimaPengawas", "✅ All marked as read")
            } catch (e: Exception) {
                Log.e("SerahTerimaPengawas", "❌ Error: ${e.message}")
            }
        }
    }

    // =========================================================================
// FUNGSI: TRIGGER UPDATE ALL SUMMARIES (CLOUD FUNCTION)
// =========================================================================
    fun triggerUpdateAllSummaries(
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "🔄 Triggering updateAllSummaries Cloud Function...")
                isLoading.value = true

                val result = Firebase.functions
                    .getHttpsCallable("updateAllSummaries")
                    .call()
                    .await()

                @Suppress("UNCHECKED_CAST")
                val data = result.data as? Map<String, Any?>
                val success = data?.get("success") as? Boolean ?: false
                val message = data?.get("message") as? String ?: "Unknown"

                if (success) {
                    Log.d(TAG, "✅ updateAllSummaries completed: $message")

                    // Refresh data setelah update
                    when (_currentUserRole.value) {
                        UserRole.PIMPINAN -> {
                            invalidatePimpinanCache()
                            refreshPimpinanData()
                        }
                        UserRole.PENGAWAS, UserRole.KOORDINATOR -> {
                            _pengawasDataLoaded.value = false
                            refreshPengawasData()
                        }
                        else -> {}
                    }

                    onSuccess?.invoke()
                } else {
                    Log.e(TAG, "❌ updateAllSummaries failed: $message")
                    onFailure?.invoke(Exception(message))
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error calling updateAllSummaries: ${e.message}")
                onFailure?.invoke(e)
            } finally {
                isLoading.value = false
            }
        }
    }

    private suspend fun calculateAdminSummaryFromRawData(adminUid: String): AdminSummary? {
        return try {
            Log.d(TAG, "📊 Calculating summary from raw data for: $adminUid")

            val pelangganSnap = database.child("pelanggan/$adminUid").get().await()
            val adminMeta = database.child("metadata/admins/$adminUid").get().await()

            if (!pelangganSnap.exists()) {
                Log.w(TAG, "⚠️ No pelanggan data for admin: $adminUid")
                return null
            }

            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
            val today = dateFormat.format(Date())

            // Pre-compute batas 3 bulan (konsisten dengan calculateTargetHarian)
            val threeMonthsAgo = Calendar.getInstance().apply {
                add(Calendar.MONTH, -3)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

            var totalNasabah = 0
            var nasabahAktif = 0
            var nasabahLunas = 0
            var nasabahMenunggu = 0
            var nasabahBaruHariIni = 0
            var nasabahLunasHariIni = 0
            var targetHariIni = 0L
            var totalPinjamanAktif = 0L
            var totalPiutang = 0L
            var pembayaranHariIni = 0L

            pelangganSnap.children.forEach { child ->
                val pelanggan = child.getValue(Pelanggan::class.java) ?: return@forEach
                val status = pelanggan.status.lowercase()

                // Skip status ditolak
                if (status == "ditolak") return@forEach

                totalNasabah++

                // Hitung total dibayar
                val totalDibayar = pelanggan.pembayaranList.sumOf { pay ->
                    pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
                }
                val totalPelunasanValue = pelanggan.totalPelunasan.toLong()
                val isSudahLunas = totalPelunasanValue > 0 && totalDibayar >= totalPelunasanValue

                when {
                    status == "aktif" || status == "active" -> {
                        if (isSudahLunas) {
                            nasabahLunas++
                            // Cek lunas hari ini
                            val adaPembayaranHariIni = pelanggan.pembayaranList.any { pay ->
                                pay.tanggal == today || pay.subPembayaran.any { it.tanggal == today }
                            }
                            if (adaPembayaranHariIni) nasabahLunasHariIni++
                        } else {
                            nasabahAktif++
                            totalPinjamanAktif += totalPelunasanValue
                            totalPiutang += (totalPelunasanValue - totalDibayar).coerceAtLeast(0)

                            // Hitung target hari ini: flat 3%, konsisten dengan calculateTargetHarian
                            // Exclude nasabah cair hari ini dan > 3 bulan
                            val tglCair = pelanggan.tanggalPencairan.trim()
                            val isCairHariIni = tglCair.isNotBlank() && tglCair == today
                            if (!isCairHariIni) {
                                val tglAcuan = pelanggan.tanggalPencairan.ifBlank {
                                    pelanggan.tanggalPengajuan.ifBlank { pelanggan.tanggalDaftar }
                                }
                                val acuanDate = try { dateFormat.parse(tglAcuan) } catch (_: Exception) { null }
                                val isOverThreeMonths = acuanDate != null && acuanDate.before(threeMonthsAgo)
                                if (!isOverThreeMonths) {
                                    targetHariIni += pelanggan.besarPinjaman * 3L / 100L
                                }
                            }

                            // Cek nasabah baru hari ini
                            val tanggalDaftar = pelanggan.tanggalDaftar.ifBlank { pelanggan.tanggalPengajuan }
                            if (tanggalDaftar == today) nasabahBaruHariIni++
                        }
                    }
                    status == "lunas" -> nasabahLunas++
                    status == "menunggu approval" -> nasabahMenunggu++
                }

                // Hitung pembayaran hari ini
                pelanggan.pembayaranList.forEach { pay ->
                    if (pay.tanggal == today) pembayaranHariIni += pay.jumlah.toLong()
                    pay.subPembayaran.filter { it.tanggal == today }.forEach { sub ->
                        pembayaranHariIni += sub.jumlah.toLong()
                    }
                }
            }

            val cabangId = adminMeta.child("cabang").getValue(String::class.java) ?: ""

            AdminSummary(
                adminId = adminUid,
                adminName = adminMeta.child("name").getValue(String::class.java) ?: "Admin",
                adminEmail = adminMeta.child("email").getValue(String::class.java) ?: "",
                cabang = cabangId,
                totalPelanggan = totalNasabah,
                nasabahAktif = nasabahAktif,
                nasabahLunas = nasabahLunas,
                nasabahMenunggu = nasabahMenunggu,
                nasabahBaruHariIni = nasabahBaruHariIni,
                nasabahLunasHariIni = nasabahLunasHariIni,
                targetHariIni = targetHariIni,
                totalPinjamanAktif = totalPinjamanAktif,
                totalPiutang = totalPiutang,
                pembayaranHariIni = pembayaranHariIni
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error calculating summary: ${e.message}")
            null
        }
    }

    // =========================================================================
// FUNGSI 3: UPDATE SUMMARY DI FIREBASE (HELPER)
// =========================================================================
    private fun updateAdminSummaryInFirebase(adminUid: String, summary: AdminSummary) {
        viewModelScope.launch {
            try {
                database.child("summary/perAdmin/$adminUid").updateChildren(
                    mapOf(
                        "totalNasabah" to summary.totalPelanggan,
                        "nasabahAktif" to summary.nasabahAktif,
                        "nasabahLunas" to summary.nasabahLunas,
                        "nasabahMenunggu" to summary.nasabahMenunggu,
                        "nasabahBaruHariIni" to summary.nasabahBaruHariIni,
                        "nasabahLunasHariIni" to summary.nasabahLunasHariIni,
                        "targetHariIni" to summary.targetHariIni,
                        "totalPinjamanAktif" to summary.totalPinjamanAktif,
                        "totalPiutang" to summary.totalPiutang,
                        "pembayaranHariIni" to summary.pembayaranHariIni,
                        "lastUpdated" to com.google.firebase.database.ServerValue.TIMESTAMP
                    )
                ).await()
                Log.d(TAG, "✅ Updated summary in Firebase for $adminUid")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to update summary: ${e.message}")
            }
        }
    }

    // =========================================================================
// FUNGSI 4: UPDATE CABANG SUMMARY (HELPER)
// =========================================================================
    private fun updateCabangSummaryInFirebase(cabangId: String, adminSummaries: List<AdminSummary>) {
        viewModelScope.launch {
            try {
                val filteredSummaries = adminSummaries.filter { it.cabang == cabangId }

                database.child("summary/perCabang/$cabangId").updateChildren(
                    mapOf(
                        "totalNasabah" to filteredSummaries.sumOf { it.totalPelanggan },
                        "nasabahAktif" to filteredSummaries.sumOf { it.nasabahAktif },
                        "nasabahLunas" to filteredSummaries.sumOf { it.nasabahLunas },
                        "nasabahMenunggu" to filteredSummaries.sumOf { it.nasabahMenunggu },
                        "nasabahBaruHariIni" to filteredSummaries.sumOf { it.nasabahBaruHariIni },
                        "nasabahLunasHariIni" to filteredSummaries.sumOf { it.nasabahLunasHariIni },
                        "targetHariIni" to filteredSummaries.sumOf { it.targetHariIni },
                        "totalPinjamanAktif" to filteredSummaries.sumOf { it.totalPinjamanAktif },
                        "totalPiutang" to filteredSummaries.sumOf { it.totalPiutang },
                        "pembayaranHariIni" to filteredSummaries.sumOf { it.pembayaranHariIni },
                        "adminCount" to filteredSummaries.size,
                        "lastUpdated" to com.google.firebase.database.ServerValue.TIMESTAMP
                    )
                ).await()
                Log.d(TAG, "✅ Updated cabang summary in Firebase for $cabangId")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to update cabang summary: ${e.message}")
            }
        }
    }

    fun getPelangganMenungguPencairan(): List<Pelanggan> {
        return daftarPelanggan.filter { pelanggan ->
            // ✅ EXCLUDE: Nasabah yang sedang proses lanjut pinjaman (Menunggu Approval)
            if (pelanggan.status == "Menunggu Approval") return@filter false

            // KONDISI 1: Ditandai manual sebagai Menunggu Pencairan (belum lunas tapi tidak mau bayar)
            val isMenungguPencairanManual = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN" &&
                    pelanggan.statusPencairanSimpanan != "Dicairkan"

            // KONDISI 2: Lunas cicilan otomatis (sudah bayar semua)
            val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
                pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
            }
            val isLunasCicilan = totalBayar >= pelanggan.totalPelunasan.toLong() && pelanggan.totalPelunasan > 0
            val isLunasOtomatis = isLunasCicilan && pelanggan.statusPencairanSimpanan != "Dicairkan"

            // Masuk jika salah satu kondisi terpenuhi
            isMenungguPencairanManual || isLunasOtomatis
        }
    }

    fun getJumlahPelangganMenungguPencairan(): Int {
        return getPelangganMenungguPencairan().size
    }

    // =========================================================================
// FUNGSI: Cairkan Simpanan Nasabah
// =========================================================================
    fun cairkanSimpanan(
        pelangganId: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                val pelanggan = daftarPelanggan.find { it.id == pelangganId }
                if (pelanggan == null) {
                    onFailure?.invoke(Exception("Pelanggan tidak ditemukan"))
                    return@launch
                }

                val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid }
                if (adminUid.isNullOrBlank()) {
                    onFailure?.invoke(Exception("Admin UID tidak valid"))
                    return@launch
                }

                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                val tanggalSekarang = dateFormat.format(Date())
                val pencairUid = Firebase.auth.currentUser?.uid ?: ""
                val pencairSnap = database.child("metadata/admins/$pencairUid/name").get().await()
                val pencairOleh = pencairSnap.getValue(String::class.java)
                    ?: Firebase.auth.currentUser?.email
                    ?: "Admin"

                val updates = mapOf(
                    "statusPencairanSimpanan" to "Dicairkan",
                    "tanggalPencairanSimpanan" to tanggalSekarang,
                    "dicairkanOleh" to pencairOleh,
                    "status" to "Lunas",
                    "lastUpdated" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                    "statusKhusus" to "",
                    "catatanStatusKhusus" to "",
                    "tanggalStatusKhusus" to "",
                    "diberiTandaOleh" to ""
                )

                database.child("pelanggan").child(adminUid).child(pelangganId)
                    .updateChildren(updates)
                    .addOnSuccessListener {
                        // Update local list
                        val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                        if (index != -1) {
                            daftarPelanggan[index] = daftarPelanggan[index].copy(
                                statusPencairanSimpanan = "Dicairkan",
                                tanggalPencairanSimpanan = tanggalSekarang,
                                dicairkanOleh = pencairOleh,
                                status = "Lunas",
                                statusKhusus = "",
                                catatanStatusKhusus = "",
                                tanggalStatusKhusus = "",
                                diberiTandaOleh = ""
                            )
                        }
                        Log.d("Pencairan", "✅ Simpanan berhasil dicairkan untuk: ${pelanggan.namaPanggilan}")
                        viewModelScope.launch {
                            try {
                                val cabangId = _currentUserCabang.value ?: ""
                                if (cabangId.isNotBlank()) {
                                    database.child("pelanggan_status_khusus")
                                        .child(cabangId)
                                        .child(pelangganId)
                                        .removeValue()
                                        .await()

                                    updateStatusKhususCounter(adminUid, cabangId)
                                    Log.d("Pencairan", "✅ Removed from pelanggan_status_khusus")
                                }
                            } catch (e: Exception) {
                                Log.e("Pencairan", "Error removing from status_khusus: ${e.message}")
                            }
                        }
                        onSuccess?.invoke()
                    }
                    .addOnFailureListener { e ->
                        Log.e("Pencairan", "❌ Gagal mencairkan simpanan: ${e.message}")
                        onFailure?.invoke(e)
                    }

            } catch (e: Exception) {
                Log.e("Pencairan", "❌ Error: ${e.message}")
                onFailure?.invoke(e)
            }
        }
    }

    // =========================================================================
    // PENCAIRAN SIMPANAN REQUEST FUNCTIONS
    // =========================================================================

    /**
     * Admin lapangan membuat request pencairan simpanan (parsial atau penuh).
     * Request dikirim ke Pengawas untuk disetujui.
     * Path Firebase: pencairan_simpanan_requests/{requestId}
     */
    fun createPencairanSimpananRequest(
        pelanggan: Pelanggan,
        jumlahDicairkan: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                if (jumlahDicairkan <= 0) {
                    onFailure(Exception("Jumlah pencairan harus lebih dari 0"))
                    return@launch
                }
                if (jumlahDicairkan > pelanggan.simpanan) {
                    onFailure(Exception("Jumlah pencairan tidak boleh melebihi total simpanan"))
                    return@launch
                }

                val requestedByName = database.child("metadata/admins/${currentUser.uid}/name")
                    .get().await().getValue(String::class.java)
                    ?: currentUser.email ?: "Admin"

                val cabangId = _currentUserCabang.value ?: ""

                val requestId = database.child("pencairan_simpanan_requests").push().key
                    ?: throw Exception("Gagal membuat ID request")

                val request = PencairanSimpananRequest(
                    id = requestId,
                    pelangganId = pelanggan.id,
                    pelangganNama = pelanggan.namaKtp.ifBlank { pelanggan.namaPanggilan },
                    pelangganNik = pelanggan.nik,
                    pelangganWilayah = pelanggan.wilayah,
                    pinjamanKe = pelanggan.pinjamanKe,
                    besarPinjaman = pelanggan.besarPinjaman,
                    totalSimpanan = pelanggan.simpanan,
                    jumlahDicairkan = jumlahDicairkan,
                    adminUid = pelanggan.adminUid.ifBlank { currentUser.uid },
                    requestedByUid = currentUser.uid,
                    requestedByName = requestedByName,
                    requestedByEmail = currentUser.email ?: "",
                    cabangId = cabangId,
                    status = PencairanSimpananRequestStatus.PENDING,
                    createdAt = System.currentTimeMillis(),
                    read = false
                )

                database.child("pencairan_simpanan_requests").child(requestId)
                    .setValue(request)
                    .await()

                Log.d("PencairanSimpanan", "✅ Request pencairan dibuat: ${pelanggan.namaKtp}, jumlah=$jumlahDicairkan")
                onSuccess()

            } catch (e: Exception) {
                Log.e("PencairanSimpanan", "❌ Gagal membuat request: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Load semua pending pencairan simpanan requests (dipanggil oleh Pengawas).
     * Path Firebase: pencairan_simpanan_requests (filter status=pending, cabangId=current)
     */
    fun loadPendingPencairanSimpananRequests() {
        viewModelScope.launch {
            try {
                val cabangId = _currentUserCabang.value
                Log.d("PencairanSimpanan", "🔄 Loading pencairan simpanan requests, cabang=$cabangId")

                val snapshot = database.child("pencairan_simpanan_requests")
                    .orderByChild("status")
                    .equalTo(PencairanSimpananRequestStatus.PENDING)
                    .get()
                    .await()

                val requests = mutableListOf<PencairanSimpananRequest>()
                var unreadCount = 0

                for (child in snapshot.children) {
                    try {
                        val req = child.getValue(PencairanSimpananRequest::class.java)
                        if (req != null) {
                            // Filter by cabang jika ada
                            if (cabangId.isNullOrBlank() || req.cabangId == cabangId) {
                                requests.add(req)
                                if (!req.read) unreadCount++
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PencairanSimpanan", "Error parsing request: ${e.message}")
                    }
                }

                val sortedList = requests.sortedByDescending { it.createdAt }
                _pendingPencairanSimpananRequests.value = sortedList
                _unreadPencairanSimpananCount.value = unreadCount

                Log.d("PencairanSimpanan", "✅ Loaded ${sortedList.size} requests, $unreadCount unread")

            } catch (e: Exception) {
                Log.e("PencairanSimpanan", "❌ Error loading: ${e.message}")
            }
        }
    }

    /**
     * Pengawas menyetujui request pencairan simpanan.
     * - Mengurangi simpanan nasabah sebesar jumlahDicairkan
     * - Jika sisa simpanan <= 0: set statusPencairanSimpanan = "Dicairkan" (lunas sepenuhnya)
     * - Hapus request dari Firebase
     */
    fun approvePencairanSimpananRequest(
        requestId: String,
        catatanPengawas: String = "",
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                // Ambil request
                val reqSnap = database.child("pencairan_simpanan_requests/$requestId").get().await()
                val request = reqSnap.getValue(PencairanSimpananRequest::class.java)
                    ?: run { onFailure(Exception("Request tidak ditemukan")); return@launch }

                val reviewerName = database.child("metadata/admins/${currentUser.uid}/name")
                    .get().await().getValue(String::class.java)
                    ?: currentUser.email ?: "Pengawas"

                val adminUid = request.adminUid.ifBlank { request.requestedByUid }
                val pelangganId = request.pelangganId

                // Ambil data simpanan terkini dari Firebase (bukan cache)
                val simpananSnap = database.child("pelanggan/$adminUid/$pelangganId/simpanan")
                    .get().await()
                val simpananSaatIni = simpananSnap.getValue(Int::class.java) ?: request.totalSimpanan

                val sisaSimpanan = maxOf(simpananSaatIni - request.jumlahDicairkan, 0)
                val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                val tanggalSekarang = dateFormat.format(Date())

                // Update pelanggan
                val pelangganUpdates = mutableMapOf<String, Any>(
                    "simpanan" to sisaSimpanan,
                    "lastUpdated" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )

                if (sisaSimpanan <= 0) {
                    // Pencairan penuh → status Dicairkan sama seperti cairkanSimpanan()
                    pelangganUpdates["statusPencairanSimpanan"] = "Dicairkan"
                    pelangganUpdates["tanggalPencairanSimpanan"] = tanggalSekarang
                    pelangganUpdates["dicairkanOleh"] = reviewerName
                    pelangganUpdates["status"] = "Lunas"
                    pelangganUpdates["statusKhusus"] = ""
                    pelangganUpdates["catatanStatusKhusus"] = ""
                    pelangganUpdates["tanggalStatusKhusus"] = ""
                    pelangganUpdates["diberiTandaOleh"] = ""
                }

                database.child("pelanggan/$adminUid/$pelangganId")
                    .updateChildren(pelangganUpdates)
                    .await()

                // Update local list
                val idx = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                if (idx != -1) {
                    daftarPelanggan[idx] = daftarPelanggan[idx].copy(
                        simpanan = sisaSimpanan,
                        statusPencairanSimpanan = if (sisaSimpanan <= 0) "Dicairkan" else daftarPelanggan[idx].statusPencairanSimpanan,
                        tanggalPencairanSimpanan = if (sisaSimpanan <= 0) tanggalSekarang else daftarPelanggan[idx].tanggalPencairanSimpanan,
                        dicairkanOleh = if (sisaSimpanan <= 0) reviewerName else daftarPelanggan[idx].dicairkanOleh,
                        status = if (sisaSimpanan <= 0) "Lunas" else daftarPelanggan[idx].status,
                        statusKhusus = if (sisaSimpanan <= 0) "" else daftarPelanggan[idx].statusKhusus
                    )
                }

                // Jika pencairan penuh, hapus dari pelanggan_status_khusus
                if (sisaSimpanan <= 0) {
                    val cabangId = request.cabangId.ifBlank { _currentUserCabang.value ?: "" }
                    if (cabangId.isNotBlank()) {
                        try {
                            database.child("pelanggan_status_khusus/$cabangId/$pelangganId").removeValue().await()
                        } catch (e: Exception) {
                            Log.w("PencairanSimpanan", "Gagal hapus dari status_khusus: ${e.message}")
                        }
                    }
                }

                // Hapus request (selesai diproses)
                database.child("pencairan_simpanan_requests/$requestId").removeValue().await()

                // Kirim notifikasi ke Admin lapangan bahwa pencairan disetujui
                try {
                    val notificationId = UUID.randomUUID().toString()
                    val tipeLabel = if (sisaSimpanan <= 0) "penuh" else "parsial"
                    val adminNotification = mapOf(
                        "id" to notificationId,
                        "type" to "SIMPANAN_APPROVED",
                        "title" to "Pencairan Simpanan Disetujui",
                        "message" to "Pencairan simpanan ${request.pelangganNama} (${tipeLabel}, Rp ${formatRupiah(request.jumlahDicairkan)}) telah disetujui oleh $reviewerName",
                        "pelangganId" to pelangganId,
                        "pelangganNama" to request.pelangganNama,
                        "catatanPengawas" to catatanPengawas,
                        "catatanPersetujuan" to catatanPengawas,
                        "timestamp" to System.currentTimeMillis(),
                        "read" to false
                    )
                    database.child("admin_notifications")
                        .child(request.requestedByUid)
                        .child(notificationId)
                        .setValue(adminNotification)
                        .await()
                } catch (e: Exception) {
                    Log.w("PencairanSimpanan", "⚠️ Gagal kirim notifikasi approval: ${e.message}")
                }

                // Update state lokal
                _pendingPencairanSimpananRequests.value = _pendingPencairanSimpananRequests.value
                    .filter { it.id != requestId }

                val tipeLabel = if (sisaSimpanan <= 0) "penuh" else "parsial (sisa Rp ${formatRupiah(sisaSimpanan)})"
                Log.d("PencairanSimpanan", "✅ Request disetujui, pencairan $tipeLabel untuk ${request.pelangganNama}")
                onSuccess()

            } catch (e: Exception) {
                Log.e("PencairanSimpanan", "❌ Error approving: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Pengawas menolak request pencairan simpanan.
     * Simpanan nasabah tidak berubah, request dihapus.
     */
    fun rejectPencairanSimpananRequest(
        requestId: String,
        catatanPengawas: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                    ?: run { onFailure(Exception("User belum login")); return@launch }

                // Ambil data request sebelum dihapus
                val reqSnap = database.child("pencairan_simpanan_requests/$requestId").get().await()
                val request = reqSnap.getValue(PencairanSimpananRequest::class.java)

                val reviewerName = database.child("metadata/admins/${currentUser.uid}/name")
                    .get().await().getValue(String::class.java)
                    ?: currentUser.email ?: "Pengawas"

                // Hapus request (penolakan tidak perlu menyimpan arsip seperti approval)
                database.child("pencairan_simpanan_requests/$requestId").removeValue().await()

                // Kirim notifikasi ke Admin lapangan bahwa pencairan ditolak
                if (request != null) {
                    try {
                        val notificationId = UUID.randomUUID().toString()
                        val adminNotification = mapOf(
                            "id" to notificationId,
                            "type" to "SIMPANAN_REJECTED",
                            "title" to "Pencairan Simpanan Ditolak",
                            "message" to "Pencairan simpanan ${request.pelangganNama} ditolak oleh $reviewerName. Alasan: $catatanPengawas",
                            "pelangganId" to request.pelangganId,
                            "pelangganNama" to request.pelangganNama,
                            "alasanPenolakan" to catatanPengawas,
                            "catatanPengawas" to catatanPengawas,
                            "timestamp" to System.currentTimeMillis(),
                            "read" to false
                        )
                        database.child("admin_notifications")
                            .child(request.requestedByUid)
                            .child(notificationId)
                            .setValue(adminNotification)
                            .await()
                    } catch (e: Exception) {
                        Log.w("PencairanSimpanan", "⚠️ Gagal kirim notifikasi penolakan: ${e.message}")
                    }
                }

                // Update state lokal
                _pendingPencairanSimpananRequests.value = _pendingPencairanSimpananRequests.value
                    .filter { it.id != requestId }

                Log.d("PencairanSimpanan", "✅ Request pencairan ditolak")
                onSuccess()

            } catch (e: Exception) {
                Log.e("PencairanSimpanan", "❌ Error rejecting: ${e.message}")
                onFailure(e)
            }
        }
    }

    /** Mark all pencairan simpanan requests as read */
    fun markAllPencairanSimpananAsRead() {
        viewModelScope.launch {
            try {
                val unread = _pendingPencairanSimpananRequests.value.filter { !it.read }
                for (req in unread) {
                    database.child("pencairan_simpanan_requests/${req.id}/read")
                        .setValue(true).await()
                }
                _unreadPencairanSimpananCount.value = 0
            } catch (e: Exception) {
                Log.e("PencairanSimpanan", "Error marking as read: ${e.message}")
            }
        }
    }

    // =========================================================================
// FUNGSI: Update Status Saat Cicilan Lunas (Panggil saat pembayaran terakhir)
// =========================================================================
    fun updateStatusLunasCicilan(pelangganId: String) {
        viewModelScope.launch {
            try {
                val pelanggan = daftarPelanggan.find { it.id == pelangganId } ?: return@launch

                val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
                    pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
                }

                // Cek apakah sudah lunas
                if (totalBayar >= pelanggan.totalPelunasan.toLong() && pelanggan.statusPencairanSimpanan.isBlank()) {
                    val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid } ?: return@launch
                    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
                    val tanggalSekarang = dateFormat.format(Date())

                    val updates = mapOf(
                        "statusPencairanSimpanan" to "Menunggu Pencairan",
                        "tanggalLunasCicilan" to tanggalSekarang,
                        "lastUpdated" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    )

                    database.child("pelanggan").child(adminUid).child(pelangganId)
                        .updateChildren(updates)
                        .addOnSuccessListener {
                            val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                            if (index != -1) {
                                daftarPelanggan[index] = daftarPelanggan[index].copy(
                                    statusPencairanSimpanan = "Menunggu Pencairan",
                                    tanggalLunasCicilan = tanggalSekarang
                                )
                            }
                            Log.d("Pencairan", "✅ Status diupdate ke Menunggu Pencairan: ${pelanggan.namaPanggilan}")
                        }
                }
            } catch (e: Exception) {
                Log.e("Pencairan", "❌ Error update status: ${e.message}")
            }
        }
    }

    fun loadPendingPimpinanFinal() {
        viewModelScope.launch {
            try {
                val cabangId = currentUserCabang.value ?: return@launch
                Log.d("PimpinanFinal", "🔄 Loading pending final approvals for Pimpinan...")

                val pengajuanSnap = database.child("pengajuan_approval/$cabangId").get().await()
                val pendingList = mutableListOf<Pelanggan>()
                val addedPelangganIds = mutableSetOf<String>()

                pengajuanSnap.children.forEach { child ->
                    try {
                        val pelangganId = child.child("pelangganId").getValue(String::class.java) ?: return@forEach
                        val besarPinjaman = child.child("besarPinjaman").getValue(Int::class.java) ?: 0
                        val approvalPhase = child.child("dualApprovalInfo/approvalPhase")
                            .getValue(String::class.java) ?: ApprovalPhase.AWAITING_PIMPINAN

                        // Filter: hanya yang Phase 3 (AWAITING_PIMPINAN_FINAL)
                        if (besarPinjaman >= DualApprovalThreshold.MINIMUM_AMOUNT &&
                            approvalPhase == ApprovalPhase.AWAITING_PIMPINAN_FINAL) {

                            val adminUid = child.child("adminUid").getValue(String::class.java) ?: ""
                            val pelangganSnap = database.child("pelanggan/$adminUid/$pelangganId").get().await()

                            if (pelangganSnap.exists()) {
                                val pelanggan = pelangganSnap.getValue(Pelanggan::class.java)
                                if (pelanggan != null && !addedPelangganIds.contains(pelangganId)) {
                                    addedPelangganIds.add(pelangganId)
                                    pendingList.add(pelanggan.copy(
                                        id = pelangganId,
                                        adminUid = adminUid,
                                        cabangId = cabangId
                                    ))
                                    Log.d("PimpinanFinal", "✅ Added: ${pelanggan.namaPanggilan}")
                                } else if (pelanggan != null) {
                                    Log.d("PimpinanFinal", "⚠️ Duplikat dilewati: ${pelanggan.namaPanggilan} ($pelangganId)")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PimpinanFinal", "Error parsing: ${e.message}")
                    }
                }

                _pendingPimpinanFinal.value = pendingList.sortedByDescending { it.tanggalPengajuan }
                Log.d("PimpinanFinal", "✅ Loaded ${pendingList.size} pending final approvals")

            } catch (e: Exception) {
                Log.e("PimpinanFinal", "❌ Error: ${e.message}")
            }
        }
    }

    fun finalizePimpinanApproval(
        pelangganId: String,
        catatan: String = "",
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        // Ini sebenarnya memanggil approvePengajuan dengan deteksi phase
        // approvePengajuan sudah handle Phase 3
        approvePengajuan(
            pelangganId = pelangganId,
            catatan = catatan,
            cabangId = currentUserCabang.value,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    fun deleteSerahTerimaNotification(
        notificationId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                Log.d("SerahTerima", "🗑️ Menghapus notifikasi: $notificationId")

                database.child("serah_terima_notifications")
                    .child(currentUid)
                    .child(notificationId)
                    .removeValue()
                    .await()

                // Update state lokal
                _serahTerimaNotifications.value = _serahTerimaNotifications.value
                    .filter { it.id != notificationId }

                Log.d("SerahTerima", "✅ Notifikasi berhasil dihapus")
                onSuccess()

            } catch (e: Exception) {
                Log.e("SerahTerima", "❌ Gagal hapus: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Menghapus notifikasi serah terima untuk Pengawas
     * Path: pengawas_serah_terima_notifications/{pengawasUid}/{notificationId}
     */
    fun deletePengawasSerahTerimaNotification(
        notificationId: String,
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        val currentUid = Firebase.auth.currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                Log.d("SerahTerimaPengawas", "🗑️ Menghapus notifikasi: $notificationId")

                database.child("pengawas_serah_terima_notifications")
                    .child(currentUid)
                    .child(notificationId)
                    .removeValue()
                    .await()

                // Update state lokal
                _pengawasSerahTerimaNotifications.value = _pengawasSerahTerimaNotifications.value
                    .filter { it.id != notificationId }

                Log.d("SerahTerimaPengawas", "✅ Notifikasi berhasil dihapus")
                onSuccess()

            } catch (e: Exception) {
                Log.e("SerahTerimaPengawas", "❌ Gagal hapus: ${e.message}")
                onFailure(e)
            }
        }
    }

    // =========================================================================
    // DELETION REQUEST FUNCTIONS - Request Penghapusan Nasabah
    // =========================================================================

    /**
     * Membuat request penghapusan nasabah (dipanggil oleh Admin Lapangan)
     * Request akan dikirim ke Pengawas untuk approval
     *
     * Path: deletion_requests/{requestId}
     */
    fun createDeletionRequest(
        pelanggan: Pelanggan,
        alasanPenghapusan: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                // Hitung sisa utang
                val totalBayar = pelanggan.pembayaranList.sumOf { pembayaran ->
                    pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
                }
                val sisaUtang = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)

                // Tentukan status pelanggan
                val statusPelanggan = when {
                    sisaUtang <= 0 -> "Lunas"
                    pelanggan.status == "Menunggu Approval" -> "Menunggu Approval"
                    else -> "Aktif"
                }

                // Generate ID untuk request
                val requestId = UUID.randomUUID().toString()

                // Ambil info admin
                val adminSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val adminName = adminSnap.child("name").getValue(String::class.java) ?: currentUser.email ?: "Admin"
                val adminEmail = adminSnap.child("email").getValue(String::class.java) ?: currentUser.email ?: ""
                val cabangId = adminSnap.child("cabang").getValue(String::class.java) ?: pelanggan.cabangId

                // Buat deletion request
                val deletionRequest = DeletionRequest(
                    id = requestId,
                    pelangganId = pelanggan.id,
                    pelangganNama = pelanggan.namaKtp,
                    pelangganNik = pelanggan.nik,
                    pelangganAlamat = pelanggan.alamatRumah.ifBlank { pelanggan.alamatKtp },
                    pelangganWilayah = pelanggan.wilayah,
                    besarPinjaman = pelanggan.besarPinjaman,
                    totalPelunasan = pelanggan.totalPelunasan,
                    sisaUtang = sisaUtang,
                    pinjamanKe = pelanggan.pinjamanKe,
                    statusPelanggan = statusPelanggan,
                    adminUid = pelanggan.adminUid.ifBlank { currentUser.uid },
                    requestedByUid = currentUser.uid,
                    requestedByName = adminName,
                    requestedByEmail = adminEmail,
                    cabangId = cabangId,
                    alasanPenghapusan = alasanPenghapusan,
                    status = DeletionRequestStatus.PENDING,
                    createdAt = System.currentTimeMillis(),
                    read = false
                )

                // Simpan ke Firebase
                database.child("deletion_requests").child(requestId)
                    .setValue(deletionRequest)
                    .await()

                Log.d("DeletionRequest", "✅ Request penghapusan berhasil dibuat: ${pelanggan.namaKtp}")
                onSuccess()

            } catch (e: Exception) {
                Log.e("DeletionRequest", "❌ Gagal membuat request: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Load semua pending deletion requests (dipanggil oleh Pengawas)
     *
     * Path: deletion_requests
     */
    fun loadPendingDeletionRequests() {
        viewModelScope.launch {
            try {
                Log.d("DeletionRequest", "🔄 Loading pending deletion requests...")

                val snapshot = database.child("deletion_requests")
                    .orderByChild("status")
                    .equalTo(DeletionRequestStatus.PENDING)
                    .get()
                    .await()

                val requests = mutableListOf<DeletionRequest>()
                var unreadCount = 0

                for (child in snapshot.children) {
                    try {
                        val request = child.getValue(DeletionRequest::class.java)
                        if (request != null) {
                            requests.add(request)
                            if (!request.read) unreadCount++
                        }
                    } catch (e: Exception) {
                        Log.e("DeletionRequest", "Error parsing request: ${e.message}")
                    }
                }

                val sortedList = requests.sortedByDescending { it.createdAt }
                _pendingDeletionRequests.value = sortedList
                _unreadDeletionRequestCount.value = unreadCount

                Log.d("DeletionRequest", "✅ Loaded ${sortedList.size} pending deletion requests, $unreadCount unread")

            } catch (e: Exception) {
                Log.e("DeletionRequest", "❌ Error loading: ${e.message}")
            }
        }
    }

    /**
     * Load pending deletion requests untuk Pimpinan (filtered by cabang)
     * Dipanggil oleh Pimpinan dari PimpinanApprovalScreen
     */
    fun loadPimpinanDeletionRequests() {
        viewModelScope.launch {
            try {
                val cabangId = _currentUserCabang.value
                if (cabangId.isNullOrBlank()) {
                    Log.w("DeletionRequest", "⚠️ CabangId tidak ditemukan untuk Pimpinan")
                    return@launch
                }

                Log.d("DeletionRequest", "🔄 Loading deletion requests for cabang: $cabangId")

                val snapshot = database.child("deletion_requests")
                    .orderByChild("cabangId")
                    .equalTo(cabangId)
                    .get()
                    .await()

                val requests = mutableListOf<DeletionRequest>()
                var unreadCount = 0

                for (child in snapshot.children) {
                    try {
                        val request = child.getValue(DeletionRequest::class.java)
                        // Filter hanya yang PENDING
                        if (request != null && request.status == DeletionRequestStatus.PENDING) {
                            requests.add(request)
                            if (!request.read) unreadCount++
                        }
                    } catch (e: Exception) {
                        Log.e("DeletionRequest", "Error parsing request: ${e.message}")
                    }
                }

                val sortedList = requests.sortedByDescending { it.createdAt }
                _pimpinanDeletionRequests.value = sortedList
                _unreadPimpinanDeletionCount.value = unreadCount

                Log.d("DeletionRequest", "✅ Loaded ${sortedList.size} deletion requests for cabang $cabangId")

            } catch (e: Exception) {
                Log.e("DeletionRequest", "❌ Error loading pimpinan deletion requests: ${e.message}")
            }
        }
    }

    /**
     * Setujui deletion request dan hapus nasabah (dipanggil oleh Pengawas)
     */
    fun approveDeletionRequest(
        requestId: String,
        catatanPengawas: String = "",
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                // =====================================================
                // STEP 1: Ambil info deletion request
                // =====================================================
                val requestSnap = database.child("deletion_requests/$requestId").get().await()
                val request = requestSnap.getValue(DeletionRequest::class.java)

                if (request == null) {
                    onFailure(Exception("Request tidak ditemukan"))
                    return@launch
                }

                Log.d("DeletionRequest", "📋 Processing deletion for: ${request.pelangganNama}")
                Log.d("DeletionRequest", "   adminUid: ${request.adminUid}")
                Log.d("DeletionRequest", "   pelangganId: ${request.pelangganId}")
                Log.d("DeletionRequest", "   requestedByUid: ${request.requestedByUid}")

                // Tentukan adminUid yang benar
                val adminUid = request.adminUid.ifBlank { request.requestedByUid }
                val pelangganId = request.pelangganId

                if (adminUid.isBlank() || pelangganId.isBlank()) {
                    onFailure(Exception("AdminUid atau PelangganId kosong"))
                    return@launch
                }

                // =====================================================
                // STEP 2: Ambil data lengkap pelanggan untuk foto URLs
                // =====================================================
                val pelangganSnap = database.child("pelanggan/$adminUid/$pelangganId").get().await()

                if (!pelangganSnap.exists()) {
                    Log.w("DeletionRequest", "⚠️ Data pelanggan tidak ditemukan di RTDB, mungkin sudah dihapus")
                    // Lanjutkan untuk membersihkan request
                } else {
                    val pelanggan = pelangganSnap.getValue(Pelanggan::class.java)

                    if (pelanggan != null) {
                        // =====================================================
                        // STEP 3: Hapus foto dari Firebase Storage
                        // =====================================================
                        val fotoUrls = listOfNotNull(
                            pelanggan.fotoKtpUrl.takeIf { it.isNotBlank() },
                            pelanggan.fotoKtpSuamiUrl.takeIf { it.isNotBlank() },
                            pelanggan.fotoKtpIstriUrl.takeIf { it.isNotBlank() },
                            pelanggan.fotoNasabahUrl.takeIf { it.isNotBlank() },
                            pelanggan.fotoSerahTerimaUrl.takeIf { it.isNotBlank() }
                        )

                        Log.d("DeletionRequest", "🖼️ Menghapus ${fotoUrls.size} foto dari Storage...")

                        for (url in fotoUrls) {
                            try {
                                deletePhotoFromStorage(url)
                                Log.d("DeletionRequest", "   ✅ Foto dihapus: ${url.take(80)}...")
                            } catch (e: Exception) {
                                // Log tapi jangan gagalkan keseluruhan proses
                                Log.w("DeletionRequest", "   ⚠️ Gagal hapus foto: ${e.message}")
                            }
                        }

                        // =====================================================
                        // STEP 4: Hapus dari nik_registry (jika ada)
                        // =====================================================
                        val nikList = listOfNotNull(
                            pelanggan.nik.takeIf { it.isNotBlank() },
                            pelanggan.nikSuami.takeIf { it.isNotBlank() },
                            pelanggan.nikIstri.takeIf { it.isNotBlank() }
                        )

                        for (nik in nikList) {
                            try {
                                // Cek apakah NIK terdaftar dan milik pelanggan ini
                                val nikSnap = database.child("nik_registry/$nik").get().await()
                                if (nikSnap.exists()) {
                                    val registeredPelangganId = nikSnap.child("pelangganId").getValue(String::class.java)
                                    if (registeredPelangganId == pelangganId) {
                                        database.child("nik_registry/$nik").removeValue().await()
                                        Log.d("DeletionRequest", "   ✅ NIK dihapus dari registry: $nik")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("DeletionRequest", "   ⚠️ Gagal hapus NIK: ${e.message}")
                            }
                        }

                        // =====================================================
                        // STEP 5: Hapus data pelanggan dari RTDB
                        // =====================================================
                        database.child("pelanggan/$adminUid/$pelangganId").removeValue().await()
                        Log.d("DeletionRequest", "✅ Data pelanggan dihapus dari RTDB")
                    }
                }

                // =====================================================
                // STEP 6: Ambil info pengawas dan update request status
                // =====================================================
                val pengawasSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val pengawasName = pengawasSnap.child("name").getValue(String::class.java)
                    ?: currentUser.email ?: "Pengawas"

                // Update status request menjadi approved (untuk audit trail)
                val updates = mapOf(
                    "status" to DeletionRequestStatus.APPROVED,
                    "reviewedByUid" to currentUser.uid,
                    "reviewedByName" to pengawasName,
                    "reviewedAt" to System.currentTimeMillis(),
                    "catatanPengawas" to catatanPengawas
                )
                database.child("deletion_requests/$requestId").updateChildren(updates).await()

                // =====================================================
                // STEP 7: Hapus deletion request
                // =====================================================
                database.child("deletion_requests/$requestId").removeValue().await()
                Log.d("DeletionRequest", "✅ Deletion request dihapus")

                // =====================================================
                // STEP 7.5: Kirim notifikasi ke Admin bahwa deletion disetujui
                // =====================================================
                try {
                    val notificationId = UUID.randomUUID().toString()
                    val adminNotification = mapOf(
                        "id" to notificationId,
                        "type" to "DELETION_APPROVED",
                        "title" to "Penghapusan Nasabah Disetujui",
                        "message" to "Nasabah ${request.pelangganNama} telah dihapus dari sistem",
                        "pelangganId" to pelangganId,
                        "pelangganNama" to request.pelangganNama,
                        "catatanPengawas" to catatanPengawas,
                        "timestamp" to System.currentTimeMillis(),
                        "read" to false
                    )

                    database.child("admin_notifications")
                        .child(request.requestedByUid)
                        .child(notificationId)
                        .setValue(adminNotification)
                        .await()

                    Log.d("DeletionRequest", "📨 Notifikasi dikirim ke Admin: ${request.requestedByUid}")
                } catch (e: Exception) {
                    Log.w("DeletionRequest", "⚠️ Gagal kirim notifikasi ke admin: ${e.message}")
                    // Tidak perlu gagalkan proses utama
                }

                // =====================================================
                // STEP 8: Update state lokal
                // =====================================================
                _pendingDeletionRequests.value = _pendingDeletionRequests.value
                    .filter { it.id != requestId }

                // Hapus dari local list jika ada
                val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                if (index != -1) {
                    daftarPelanggan.removeAt(index)
                    simpanKeLokal()
                    loadDashboardData()
                }

                Log.d("DeletionRequest", "🎉 Deletion request approved: ${request.pelangganNama}")
                onSuccess()

            } catch (e: Exception) {
                Log.e("DeletionRequest", "❌ Gagal approve deletion: ${e.message}")
                e.printStackTrace()
                onFailure(e)
            }
        }
    }

    /**
     * Setujui deletion request sebagai Pimpinan
     * Sama seperti approveDeletionRequest tapi untuk Pimpinan
     */
    fun approveDeletionRequestAsPimpinan(
        requestId: String,
        catatanPimpinan: String = "",
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                // Ambil info deletion request
                val requestSnap = database.child("deletion_requests/$requestId").get().await()
                val request = requestSnap.getValue(DeletionRequest::class.java)

                if (request == null) {
                    onFailure(Exception("Request tidak ditemukan"))
                    return@launch
                }

                // Verifikasi Pimpinan hanya bisa approve request dari cabang-nya
                val pimpinanCabang = _currentUserCabang.value
                if (request.cabangId != pimpinanCabang) {
                    onFailure(Exception("Anda tidak berwenang untuk request ini"))
                    return@launch
                }

                Log.d("DeletionRequest", "📋 Pimpinan processing deletion for: ${request.pelangganNama}")

                val adminUid = request.adminUid.ifBlank { request.requestedByUid }
                val pelangganId = request.pelangganId

                if (adminUid.isBlank() || pelangganId.isBlank()) {
                    onFailure(Exception("AdminUid atau PelangganId kosong"))
                    return@launch
                }

                // Ambil data pelanggan
                val pelangganSnap = database.child("pelanggan/$adminUid/$pelangganId").get().await()

                if (pelangganSnap.exists()) {
                    val pelanggan = pelangganSnap.getValue(Pelanggan::class.java)

                    if (pelanggan != null) {
                        // Hapus foto dari Storage
                        val fotoUrls = listOfNotNull(
                            pelanggan.fotoKtpUrl.takeIf { it.isNotBlank() },
                            pelanggan.fotoKtpSuamiUrl.takeIf { it.isNotBlank() },
                            pelanggan.fotoKtpIstriUrl.takeIf { it.isNotBlank() },
                            pelanggan.fotoNasabahUrl.takeIf { it.isNotBlank() },
                            pelanggan.fotoSerahTerimaUrl.takeIf { it.isNotBlank() }
                        )

                        for (url in fotoUrls) {
                            try {
                                deletePhotoFromStorage(url)
                            } catch (e: Exception) {
                                Log.w("DeletionRequest", "⚠️ Gagal hapus foto: ${e.message}")
                            }
                        }

                        // Hapus dari nik_registry
                        val nikList = listOfNotNull(
                            pelanggan.nik.takeIf { it.isNotBlank() },
                            pelanggan.nikSuami.takeIf { it.isNotBlank() },
                            pelanggan.nikIstri.takeIf { it.isNotBlank() }
                        )

                        for (nik in nikList) {
                            try {
                                val nikSnap = database.child("nik_registry/$nik").get().await()
                                if (nikSnap.exists()) {
                                    val registeredPelangganId = nikSnap.child("pelangganId").getValue(String::class.java)
                                    if (registeredPelangganId == pelangganId) {
                                        database.child("nik_registry/$nik").removeValue().await()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("DeletionRequest", "⚠️ Gagal hapus NIK: ${e.message}")
                            }
                        }

                        // Hapus data pelanggan
                        database.child("pelanggan/$adminUid/$pelangganId").removeValue().await()
                        Log.d("DeletionRequest", "✅ Data pelanggan dihapus dari RTDB")
                    }
                }

                // Update dan hapus request
                val pimpinanSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val pimpinanName = pimpinanSnap.child("name").getValue(String::class.java)
                    ?: currentUser.email ?: "Pimpinan"

                database.child("deletion_requests/$requestId").removeValue().await()

                // Kirim notifikasi ke Admin
                try {
                    val notificationId = UUID.randomUUID().toString()
                    val adminNotification = mapOf(
                        "id" to notificationId,
                        "type" to "DELETION_APPROVED",
                        "title" to "Penghapusan Nasabah Disetujui",
                        "message" to "Nasabah ${request.pelangganNama} telah dihapus oleh Pimpinan",
                        "pelangganId" to pelangganId,
                        "pelangganNama" to request.pelangganNama,
                        "reviewedBy" to pimpinanName,
                        "catatanPimpinan" to catatanPimpinan,
                        "timestamp" to System.currentTimeMillis(),
                        "read" to false
                    )

                    database.child("admin_notifications")
                        .child(request.requestedByUid)
                        .child(notificationId)
                        .setValue(adminNotification)
                        .await()
                } catch (e: Exception) {
                    Log.w("DeletionRequest", "⚠️ Gagal kirim notifikasi: ${e.message}")
                }

                // Update state lokal
                _pimpinanDeletionRequests.value = _pimpinanDeletionRequests.value
                    .filter { it.id != requestId }

                onSuccess()

            } catch (e: Exception) {
                Log.e("DeletionRequest", "❌ Gagal approve: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Tolak deletion request sebagai Pimpinan
     */
    fun rejectDeletionRequestAsPimpinan(
        requestId: String,
        catatanPimpinan: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                // Verifikasi request dari cabang Pimpinan
                val requestSnap = database.child("deletion_requests/$requestId").get().await()
                val request = requestSnap.getValue(DeletionRequest::class.java)

                if (request == null) {
                    onFailure(Exception("Request tidak ditemukan"))
                    return@launch
                }

                val pimpinanCabang = _currentUserCabang.value
                if (request.cabangId != pimpinanCabang) {
                    onFailure(Exception("Anda tidak berwenang untuk request ini"))
                    return@launch
                }

                // Ambil info pimpinan
                val pimpinanSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val pimpinanName = pimpinanSnap.child("name").getValue(String::class.java)
                    ?: currentUser.email ?: "Pimpinan"

                // Hapus request
                database.child("deletion_requests/$requestId").removeValue().await()

                // Kirim notifikasi ke Admin bahwa ditolak
                try {
                    val notificationId = UUID.randomUUID().toString()
                    val adminNotification = mapOf(
                        "id" to notificationId,
                        "type" to "DELETION_REJECTED",
                        "title" to "Penghapusan Nasabah Ditolak",
                        "message" to "Pengajuan penghapusan ${request.pelangganNama} ditolak. Alasan: $catatanPimpinan",
                        "pelangganId" to request.pelangganId,
                        "pelangganNama" to request.pelangganNama,
                        "reviewedBy" to pimpinanName,
                        "alasanPenolakan" to catatanPimpinan,
                        "catatanPimpinan" to catatanPimpinan,
                        "timestamp" to System.currentTimeMillis(),
                        "read" to false
                    )

                    database.child("admin_notifications")
                        .child(request.requestedByUid)
                        .child(notificationId)
                        .setValue(adminNotification)
                        .await()
                } catch (e: Exception) {
                    Log.w("DeletionRequest", "⚠️ Gagal kirim notifikasi: ${e.message}")
                }

                // Update state lokal
                _pimpinanDeletionRequests.value = _pimpinanDeletionRequests.value
                    .filter { it.id != requestId }

                onSuccess()

            } catch (e: Exception) {
                Log.e("DeletionRequest", "❌ Gagal reject: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Mark all pimpinan deletion requests as read
     */
    fun markAllPimpinanDeletionAsRead() {
        viewModelScope.launch {
            try {
                val requests = _pimpinanDeletionRequests.value.filter { !it.read }
                for (request in requests) {
                    database.child("deletion_requests/${request.id}/read")
                        .setValue(true)
                        .await()
                }
                _unreadPimpinanDeletionCount.value = 0
            } catch (e: Exception) {
                Log.e("DeletionRequest", "Error marking all as read: ${e.message}")
            }
        }
    }

    /**
     * Helper function untuk menghapus foto dari Firebase Storage
     * Mengekstrak path dari URL dan menghapus file
     */
    private suspend fun deletePhotoFromStorage(url: String) {
        if (url.isBlank()) return

        try {
            // Firebase Storage URL format:
            // https://firebasestorage.googleapis.com/v0/b/{bucket}/o/{encoded_path}?alt=media&token=...

            if (url.contains("firebasestorage.googleapis.com")) {
                // Extract path dari URL
                val pathStart = url.indexOf("/o/") + 3
                val pathEnd = url.indexOf("?")

                if (pathStart > 3 && pathEnd > pathStart) {
                    val encodedPath = url.substring(pathStart, pathEnd)
                    // Decode URL encoding (misalnya %2F menjadi /)
                    val decodedPath = java.net.URLDecoder.decode(encodedPath, "UTF-8")

                    Log.d("DeletionRequest", "   Deleting storage path: $decodedPath")

                    // Hapus dari storage
                    storage.reference.child(decodedPath).delete().await()
                }
            } else {
                Log.w("DeletionRequest", "   URL bukan Firebase Storage URL: ${url.take(50)}...")
            }
        } catch (e: Exception) {
            // Throw exception agar bisa di-catch di caller
            throw e
        }
    }

    /**
     * Tolak deletion request (dipanggil oleh Pengawas)
     */
    fun rejectDeletionRequest(
        requestId: String,
        catatanPengawas: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                // Ambil data request sebelum dihapus (perlu requestedByUid untuk notifikasi)
                val requestSnap = database.child("deletion_requests/$requestId").get().await()
                val request = requestSnap.getValue(DeletionRequest::class.java)

                // Ambil info pengawas
                val pengawasSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val pengawasName = pengawasSnap.child("name").getValue(String::class.java)
                    ?: currentUser.email ?: "Pengawas"

                // Update status request menjadi rejected lalu hapus
                val updates = mapOf(
                    "status" to DeletionRequestStatus.REJECTED,
                    "reviewedByUid" to currentUser.uid,
                    "reviewedByName" to pengawasName,
                    "reviewedAt" to System.currentTimeMillis(),
                    "catatanPengawas" to catatanPengawas
                )
                database.child("deletion_requests/$requestId").updateChildren(updates).await()

                // Hapus request (nasabah tetap ada)
                database.child("deletion_requests/$requestId").removeValue().await()

                // Kirim notifikasi ke Admin bahwa penghapusan ditolak
                if (request != null) {
                    try {
                        val notificationId = UUID.randomUUID().toString()
                        val adminNotification = mapOf(
                            "id" to notificationId,
                            "type" to "DELETION_REJECTED",
                            "title" to "Penghapusan Nasabah Ditolak",
                            "message" to "Pengajuan penghapusan ${request.pelangganNama} ditolak oleh Pengawas. Alasan: $catatanPengawas",
                            "pelangganId" to request.pelangganId,
                            "pelangganNama" to request.pelangganNama,
                            "reviewedBy" to pengawasName,
                            "alasanPenolakan" to catatanPengawas,
                            "catatanPengawas" to catatanPengawas,
                            "timestamp" to System.currentTimeMillis(),
                            "read" to false
                        )
                        database.child("admin_notifications")
                            .child(request.requestedByUid)
                            .child(notificationId)
                            .setValue(adminNotification)
                            .await()
                    } catch (e: Exception) {
                        Log.w("DeletionRequest", "⚠️ Gagal kirim notifikasi penolakan: ${e.message}")
                    }
                }

                // Update state lokal
                _pendingDeletionRequests.value = _pendingDeletionRequests.value
                    .filter { it.id != requestId }

                Log.d("DeletionRequest", "✅ Deletion request rejected")
                onSuccess()

            } catch (e: Exception) {
                Log.e("DeletionRequest", "❌ Gagal reject: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Mark deletion request as read
     */
    fun markDeletionRequestAsRead(requestId: String) {
        viewModelScope.launch {
            try {
                database.child("deletion_requests/$requestId/read")
                    .setValue(true)
                    .await()

                // Update unread count
                val currentUnread = _unreadDeletionRequestCount.value
                if (currentUnread > 0) {
                    _unreadDeletionRequestCount.value = currentUnread - 1
                }
            } catch (e: Exception) {
                Log.e("DeletionRequest", "Error marking as read: ${e.message}")
            }
        }
    }

    /**
     * Mark all deletion requests as read
     */
    fun markAllDeletionRequestsAsRead() {
        viewModelScope.launch {
            try {
                val requests = _pendingDeletionRequests.value.filter { !it.read }
                for (request in requests) {
                    database.child("deletion_requests/${request.id}/read")
                        .setValue(true)
                        .await()
                }
                _unreadDeletionRequestCount.value = 0
            } catch (e: Exception) {
                Log.e("DeletionRequest", "Error marking all as read: ${e.message}")
            }
        }
    }

    // =========================================================================
    // USER MANAGEMENT (PENGAWAS ONLY)
    // =========================================================================

    // State untuk daftar user
    private val _allUsers = MutableStateFlow<List<UserInfo>>(emptyList())
    val allUsers: StateFlow<List<UserInfo>> = _allUsers

    private val _usersLoading = MutableStateFlow(false)
    val usersLoading: StateFlow<Boolean> = _usersLoading

    private val _usersError = MutableStateFlow<String?>(null)
    val usersError: StateFlow<String?> = _usersError

    /**
     * Memuat daftar semua user (Admin Lapangan & Pimpinan)
     * Hanya bisa dipanggil oleh Pengawas
     */
    fun loadAllUsers() {
        viewModelScope.launch {
            _usersLoading.value = true
            _usersError.value = null

            try {
                Log.d("UserManagement", "📋 Loading all users...")

                val result = functions.getHttpsCallable("getAllUsers")
                    .call()
                    .await()

                @Suppress("UNCHECKED_CAST")
                val data = result.data as? Map<String, Any>

                if (data?.get("success") == true) {
                    @Suppress("UNCHECKED_CAST")
                    val usersData = data["users"] as? List<Map<String, Any>> ?: emptyList()

                    val users = usersData.map { userData ->
                        UserInfo(
                            uid = userData["uid"] as? String ?: "",
                            email = userData["email"] as? String ?: "",
                            name = userData["name"] as? String ?: "",
                            role = userData["role"] as? String ?: "",
                            cabang = userData["cabang"] as? String ?: "",
                            cabangName = userData["cabangName"] as? String ?: "",
                            type = userData["type"] as? String ?: ""
                        )
                    }

                    _allUsers.value = users.sortedWith(
                        compareBy<UserInfo> {
                            when (it.role) {
                                "pimpinan" -> 0
                                "koordinator" -> 1
                                else -> 2
                            }
                        }
                            .thenBy { it.cabangName }
                            .thenBy { it.name }
                    )

                    Log.d("UserManagement", "✅ Loaded ${users.size} users")
                } else {
                    _usersError.value = "Gagal memuat daftar user"
                    Log.e("UserManagement", "❌ Failed to load users")
                }

            } catch (e: Exception) {
                Log.e("UserManagement", "❌ Error loading users: ${e.message}")
                _usersError.value = e.message ?: "Terjadi kesalahan"
            } finally {
                _usersLoading.value = false
            }
        }
    }

    // ✅ BARU: State online status per user
    private val _userOnlineStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val userOnlineStatus: StateFlow<Map<String, Boolean>> = _userOnlineStatus

    /**
     * Load device_presence untuk semua user (dipanggil setelah loadAllUsers)
     */
    fun loadUserPresenceStatus() {
        viewModelScope.launch {
            try {
                val snapshot = withTimeoutOrNull(8000L) {
                    database.child("device_presence").get().await()
                } ?: return@launch

                val statusMap = mutableMapOf<String, Boolean>()
                val now = System.currentTimeMillis()

                snapshot.children.forEach { child ->
                    val uid = child.key ?: return@forEach
                    val online = child.child("online").getValue(Boolean::class.java) ?: false
                    val lastSeen = child.child("lastSeen").getValue(Long::class.java) ?: 0L
                    // Dianggap online jika flag true DAN lastSeen dalam 5 menit terakhir
                    statusMap[uid] = online && (now - lastSeen < 300_000)
                }

                _userOnlineStatus.value = statusMap
                Log.d("Tracking", "✅ Loaded presence for ${statusMap.size} users, ${statusMap.count { it.value }} online")
            } catch (e: Exception) {
                Log.e("Tracking", "Error loading presence: ${e.message}")
            }
        }
    }

    // =========================================================================
    // LOCATION TRACKING (PENGAWAS ONLY)
    // =========================================================================

    // State untuk tracking
    private val _trackingTarget = MutableStateFlow<UserInfo?>(null)
    val trackingTarget: StateFlow<UserInfo?> = _trackingTarget

    private val _targetLocation = MutableStateFlow<Map<String, Any>?>(null)
    val targetLocation: StateFlow<Map<String, Any>?> = _targetLocation

    private val _isTrackingActive = MutableStateFlow(false)
    val isTrackingActive: StateFlow<Boolean> = _isTrackingActive

    private var locationListener: ValueEventListener? = null

    /**
     * Aktifkan tracking untuk target user
     */
    // ✅ BARU: State untuk status device target
    private val _trackingDeviceOnline = MutableStateFlow<Boolean?>(null) // null = belum dicek
    val trackingDeviceOnline: StateFlow<Boolean?> = _trackingDeviceOnline

    // ✅ BARU: Timestamp saat tracking dimulai (untuk timeout)
    private val _trackingStartTime = MutableStateFlow(0L)
    val trackingStartTime: StateFlow<Long> = _trackingStartTime

    fun activateTracking(target: UserInfo) {
        val pengawasUid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        viewModelScope.launch {
            try {
                // ✅ LANGKAH 1: Cek device_presence target SEBELUM aktifkan tracking
                _trackingDeviceOnline.value = null // Reset
                val isOnline = isTargetDeviceOnline(target.uid)
                _trackingDeviceOnline.value = isOnline

                if (!isOnline) {
                    Log.w("Tracking", "⚠️ Target device OFFLINE, tracking tetap diaktifkan tapi mungkin tidak respons")
                }

                // ✅ LANGKAH 2: Set flag di Firebase (tetap lakukan meskipun offline)
                val trackingData = mapOf(
                    "active" to true,
                    "requestedBy" to pengawasUid,
                    "requestedAt" to System.currentTimeMillis()
                )

                database.child("location_tracking").child(target.uid)
                    .setValue(trackingData)
                    .await()

                _trackingTarget.value = target
                _isTrackingActive.value = true
                _trackingStartTime.value = System.currentTimeMillis()

                // Mulai listen lokasi target
                startListeningLocation(target.uid)

                Log.d("Tracking", "✅ Tracking activated for ${target.name} (online=$isOnline)")
            } catch (e: Exception) {
                Log.e("Tracking", "❌ Error activating tracking: ${e.message}")
            }
        }
    }

    /**
     * Cek apakah device target online berdasarkan device_presence
     */
    private suspend fun isTargetDeviceOnline(targetUid: String): Boolean {
        return try {
            val snap = withTimeoutOrNull(5000L) {
                database.child("device_presence").child(targetUid).get().await()
            } ?: return false
            val online = snap.child("online").getValue(Boolean::class.java) ?: false
            val lastSeen = snap.child("lastSeen").getValue(Long::class.java) ?: 0L
            // Online jika flag true DAN lastSeen dalam 5 menit terakhir
            online && (System.currentTimeMillis() - lastSeen < 300_000)
        } catch (e: Exception) {
            Log.e("Tracking", "Error checking device online: ${e.message}")
            false
        }
    }

    /**
     * Matikan tracking
     */
    fun deactivateTracking() {
        val target = _trackingTarget.value ?: return

        // ✅ PENTING: Reset UI state LANGSUNG (agar tombol Stop responsif)
        stopListeningLocation(target.uid)
        _trackingTarget.value = null
        _targetLocation.value = null
        _isTrackingActive.value = false
        _trackingDeviceOnline.value = null
        _trackingStartTime.value = 0L

        // Cleanup Firebase di background (boleh gagal, tidak block UI)
        viewModelScope.launch {
            try {
                database.child("location_tracking").child(target.uid)
                    .child("active").setValue(false).await()

                database.child("user_locations").child(target.uid)
                    .removeValue().await()

                Log.d("Tracking", "🛑 Tracking deactivated & Firebase cleaned")
            } catch (e: Exception) {
                Log.e("Tracking", "⚠️ Firebase cleanup error (UI already reset): ${e.message}")
            }
        }
    }

    private fun startListeningLocation(targetUid: String) {
        stopListeningLocation(targetUid) // Pastikan tidak double-listen

        locationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val data = snapshot.value as? Map<String, Any>
                    _targetLocation.value = data
                    Log.d("Tracking", "📍 Location update received")
                } else {
                    _targetLocation.value = null
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Tracking", "❌ Location listener error: ${error.message}")
            }
        }

        database.child("user_locations").child(targetUid)
            .addValueEventListener(locationListener!!)
    }

    private fun stopListeningLocation(targetUid: String) {
        locationListener?.let {
            database.child("user_locations").child(targetUid)
                .removeEventListener(it)
        }
        locationListener = null
    }

    /**
     * Reset password user
     * Hanya bisa dipanggil oleh Pengawas
     *
     * @param targetEmail Email user yang akan direset passwordnya
     * @param newPassword Password baru (minimal 6 karakter)
     * @param onSuccess Callback jika berhasil, dengan pesan sukses
     * @param onFailure Callback jika gagal, dengan pesan error
     */
    fun resetUserPassword(
        targetEmail: String,
        newPassword: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                Log.d("UserManagement", "🔐 Resetting password for: $targetEmail")

                val data = hashMapOf(
                    "targetEmail" to targetEmail.trim().lowercase(),
                    "newPassword" to newPassword
                )

                val result = functions.getHttpsCallable("resetUserPassword")
                    .call(data)
                    .await()

                @Suppress("UNCHECKED_CAST")
                val response = result.data as? Map<String, Any>

                if (response?.get("success") == true) {
                    val message = response["message"] as? String ?: "Password berhasil diubah"
                    Log.d("UserManagement", "✅ Password reset successful: $message")
                    onSuccess(message)
                } else {
                    onFailure("Gagal mengubah password")
                }

            } catch (e: Exception) {
                Log.e("UserManagement", "❌ Error resetting password: ${e.message}")

                val errorMessage = when {
                    e.message?.contains("not-found") == true -> "Email tidak ditemukan"
                    e.message?.contains("permission-denied") == true -> "Tidak memiliki izin untuk mengubah password"
                    e.message?.contains("invalid-argument") == true -> "Password harus minimal 6 karakter"
                    else -> e.message ?: "Terjadi kesalahan"
                }

                onFailure(errorMessage)
            }
        }
    }

    // =========================================================================
    // FORCE LOGOUT LISTENER
    // =========================================================================

    /**
     * Start listening for force logout signal
     * HANYA trigger logout jika ONLINE dan node benar-benar ada di server
     */
    fun startForceLogoutListener(onForceLogout: () -> Unit) {
        val uid = Firebase.auth.currentUser?.uid ?: return

        // Simpan waktu login
        loginTimestamp = System.currentTimeMillis()

        // Remove listener lama jika ada
        stopForceLogoutListener()

        // Setup listener baru
        forceLogoutRef = database.child("force_logout").child(uid)
        forceLogoutListener = object : ValueEventListener {
            private var hasAttemptedCleanup = false

            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    hasAttemptedCleanup = false
                    return
                }

                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                if (timestamp > loginTimestamp) {
                    if (!isOnline()) {
                        Log.d("ForceLogout", "⏳ Force logout detected but OFFLINE, will check when online")
                        return
                    }

                    Log.w("ForceLogout", "⚠️ Password changed, forcing logout for $uid")

                    // Stop listener SEBELUM removeValue untuk mencegah loop
                    stopForceLogoutListener()

                    snapshot.ref.removeValue()
                    onForceLogout()
                } else {
                    // Node stale — coba hapus HANYA SEKALI
                    if (hasAttemptedCleanup) {
                        Log.d("ForceLogout", "⏭️ Stale node cleanup already attempted, ignoring")
                        return
                    }
                    hasAttemptedCleanup = true

                    Log.d("ForceLogout", "🧹 Stale force_logout node (ts=$timestamp <= login=$loginTimestamp), cleaning up")

                    snapshot.ref.removeValue().addOnFailureListener { e ->
                        Log.w("ForceLogout", "⚠️ Cannot remove stale node (Permission denied), stopping listener")
                        stopForceLogoutListener()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ForceLogout", "Error listening: ${error.message}")
            }
        }

        forceLogoutRef?.addValueEventListener(forceLogoutListener!!)
        Log.d("ForceLogout", "👀 Started listening for force logout: $uid")
    }

    /**
     * Stop force logout listener
     * Dipanggil saat logout
     */
    fun stopForceLogoutListener() {
        forceLogoutListener?.let { listener ->
            forceLogoutRef?.removeEventListener(listener)
        }
        forceLogoutListener = null
        forceLogoutRef = null
    }

    /**
     * Cek apakah perlu force logout saat app startup
     * Return true jika password sudah direset dan perlu logout
     * ✅ PERBAIKAN: HANYA cek jika ONLINE
     */
    suspend fun checkForceLogoutOnStartup(): Boolean {
        val currentUser = Firebase.auth.currentUser ?: return false

        // ✅ PERBAIKAN: Jangan cek jika offline (cache bisa unreliable)
        if (!isOnline()) {
            Log.d("ForceLogout", "⏳ Skipping force logout check - OFFLINE")
            return false
        }

        val uid = currentUser.uid

        return try {
            // ✅ PERBAIKAN: Tambah timeout 5 detik
            val snapshot = withTimeoutOrNull(5000L) {
                database.child("force_logout").child(uid).get().await()
            }

            // Jika timeout, skip force logout check
            if (snapshot == null) {
                Log.w("ForceLogout", "⚠️ Timeout checking force logout, skipping")
                return false
            }

            if (snapshot.exists()) {
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                // ✅ FIX: Bandingkan dengan waktu login terakhir yang tersimpan.
                // Jika force_logout lebih tua dari login terakhir, berarti node ini basi
                // (user sudah login ulang setelah password direset).
                val app = getApplication<Application>()
                val lastLogin = app.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    .getLong("last_login_timestamp", 0L)

                if (lastLogin > 0L && timestamp <= lastLogin) {
                    // Node basi — hapus tanpa logout
                    Log.d("ForceLogout", "🧹 Startup: stale force_logout (ts=$timestamp <= lastLogin=$lastLogin), cleaning up")
                    withTimeoutOrNull(3000L) {
                        database.child("force_logout").child(uid).removeValue().await()
                    }
                    false  // TIDAK perlu logout
                } else {
                    // Node valid — hapus lalu logout
                    withTimeoutOrNull(3000L) {
                        database.child("force_logout").child(uid).removeValue().await()
                    }
                    Log.w("ForceLogout", "⚠️ Force logout detected for $uid, timestamp: $timestamp")
                    true  // Perlu force logout
                }
            } else {
                false  // Tidak perlu force logout
            }
        } catch (e: Exception) {
            Log.e("ForceLogout", "Error checking force logout: ${e.message}")
            false
        }
    }

    // =========================================================================
// BIAYA AWAL FUNCTIONS
// =========================================================================

    fun simpanBiayaAwal(jumlah: Int, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val adminUid = Firebase.auth.currentUser?.uid ?: run {
                    onResult(false, "User tidak terautentikasi")
                    return@launch
                }

                val tanggalHariIni = SimpleDateFormat("yyyy-MM-dd", Locale("id")).format(Date())
                val timestamp = System.currentTimeMillis()

                val biayaAwalData = mapOf(
                    "jumlah" to jumlah,
                    "tanggal" to tanggalHariIni,
                    "timestamp" to timestamp,
                    "adminUid" to adminUid
                )

                database.child("biaya_awal")
                    .child(adminUid)
                    .child(tanggalHariIni)
                    .setValue(biayaAwalData)
                    .addOnSuccessListener {
                        _biayaAwalHariIni.value = jumlah
                        Log.d("BiayaAwal", "✅ Biaya awal berhasil disimpan: Rp $jumlah")
                        onResult(true, "Biaya awal berhasil disimpan")
                    }
                    .addOnFailureListener { e ->
                        Log.e("BiayaAwal", "❌ Gagal menyimpan biaya awal: ${e.message}")
                        onResult(false, "Gagal menyimpan: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e("BiayaAwal", "❌ Error: ${e.message}")
                onResult(false, "Error: ${e.message}")
            }
        }
    }

    fun loadBiayaAwalHariIni() {
        viewModelScope.launch {
            try {
                val adminUid = Firebase.auth.currentUser?.uid ?: return@launch
                val tanggalHariIni = SimpleDateFormat("yyyy-MM-dd", Locale("id")).format(Date())

                database.child("biaya_awal")
                    .child(adminUid)
                    .child(tanggalHariIni)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val jumlah = snapshot.child("jumlah").getValue(Int::class.java) ?: 0
                        _biayaAwalHariIni.value = jumlah
                        Log.d("BiayaAwal", "📥 Loaded biaya awal: Rp $jumlah")
                    }
                    .addOnFailureListener { e ->
                        Log.e("BiayaAwal", "❌ Gagal load biaya awal: ${e.message}")
                        _biayaAwalHariIni.value = 0
                    }
            } catch (e: Exception) {
                Log.e("BiayaAwal", "❌ Error load: ${e.message}")
            }
        }
    }

    fun loadKasirUangKasHariIni() {
        viewModelScope.launch {
            try {
                val adminUid = Firebase.auth.currentUser?.uid ?: return@launch
                val cabangId = _currentUserCabang.value ?: return@launch

                val bulanKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                val tanggalHariIni = SimpleDateFormat("dd MMM yyyy", Locale("id")).format(Date())

                database.child("kasir_entries")
                    .child(cabangId)
                    .child(bulanKey)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val result = mutableListOf<Triple<String, String, Int>>()
                        snapshot.children.forEach { entrySnap ->
                            val targetUid = entrySnap.child("targetAdminUid").getValue(String::class.java) ?: ""
                            val jenis = entrySnap.child("jenis").getValue(String::class.java) ?: ""
                            val tanggal = entrySnap.child("tanggal").getValue(String::class.java) ?: ""
                            val arah = entrySnap.child("arah").getValue(String::class.java) ?: ""

                            if (targetUid == adminUid && jenis == "uang_kas" && tanggal == tanggalHariIni && arah == "keluar") {
                                val jumlah = entrySnap.child("jumlah").getValue(Int::class.java) ?: 0
                                val createdByName = entrySnap.child("createdByName").getValue(String::class.java) ?: "Kasir"
                                val keterangan = entrySnap.child("keterangan").getValue(String::class.java) ?: ""
                                result.add(Triple(createdByName, keterangan, jumlah))
                            }
                        }
                        _kasirUangKasHariIni.value = result
                        Log.d("KasirUangKas", "📥 Loaded ${result.size} uang kas entries for today")
                    }
                    .addOnFailureListener { e ->
                        Log.e("KasirUangKas", "❌ Gagal load: ${e.message}")
                        _kasirUangKasHariIni.value = emptyList()
                    }
            } catch (e: Exception) {
                Log.e("KasirUangKas", "❌ Error: ${e.message}")
            }
        }
    }

    // Tambahkan fungsi baru di PelangganViewModel.kt
    fun refreshSinglePelanggan(pelangganId: String) {
        viewModelScope.launch {
            try {
                val currentAdminUid = Firebase.auth.currentUser?.uid ?: return@launch

                val snapshot = database.child("pelanggan")
                    .child(currentAdminUid)
                    .child(pelangganId)
                    .get()
                    .await()

                val freshPelanggan = snapshot.getValue(Pelanggan::class.java)?.copy(
                    id = pelangganId,
                    adminUid = currentAdminUid
                )

                if (freshPelanggan != null) {
                    // Update di daftarPelanggan
                    val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                    if (index >= 0) {
                        daftarPelanggan[index] = freshPelanggan
                    } else {
                        daftarPelanggan.add(freshPelanggan)
                    }
                    Log.d("RefreshPelanggan", "✅ Refreshed: ${freshPelanggan.namaPanggilan}")
                }
            } catch (e: Exception) {
                Log.e("RefreshPelanggan", "❌ Error: ${e.message}")
            }
        }
    }

    // =========================================================================
// BROADCAST MESSAGES FUNCTIONS
// =========================================================================

    /**
     * Kirim broadcast message ke semua karyawan (hanya Pengawas)
     */
    fun sendBroadcastMessage(
        title: String,
        message: String,
        expiresInHours: Int = 24, // Default expire dalam 24 jam
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                // Verifikasi user adalah Pengawas
                val isPengawas = database.child("metadata/roles/pengawas/${currentUser.uid}")
                    .get().await().getValue(Boolean::class.java) == true

                if (!isPengawas) {
                    onFailure(Exception("Hanya Pengawas yang bisa mengirim broadcast"))
                    return@launch
                }

                val adminSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val senderName = adminSnap.child("name").getValue(String::class.java)
                    ?: currentUser.email ?: "Pengawas"

                val messageId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val expiresAt = now + (expiresInHours * 60 * 60 * 1000L)

                val broadcast = mapOf(
                    "id" to messageId,
                    "title" to title,
                    "message" to message,
                    "senderUid" to currentUser.uid,
                    "senderName" to senderName,
                    "timestamp" to now,
                    "active" to true,
                    "expiresAt" to expiresAt
                )

                database.child("broadcast_messages/$messageId")
                    .setValue(broadcast)
                    .await()

                Log.d("Broadcast", "✅ Broadcast message sent: $title")
                loadActiveBroadcasts() // Refresh list
                onSuccess()

            } catch (e: Exception) {
                Log.e("Broadcast", "❌ Error sending broadcast: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Load active broadcast messages
     * Dipanggil oleh semua role untuk menampilkan broadcast
     */
    fun loadActiveBroadcasts() {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()

                val snapshot = database.child("broadcast_messages")
                    .orderByChild("active")
                    .equalTo(true)
                    .get()
                    .await()

                val broadcasts = mutableListOf<BroadcastMessage>()

                for (child in snapshot.children) {
                    try {
                        val broadcast = child.getValue(BroadcastMessage::class.java)
                        // Filter yang belum expire
                        if (broadcast != null && (broadcast.expiresAt == 0L || broadcast.expiresAt > now)) {
                            broadcasts.add(broadcast)
                        }
                    } catch (e: Exception) {
                        Log.e("Broadcast", "Error parsing broadcast: ${e.message}")
                    }
                }

                // Sort by timestamp descending (terbaru di atas)
                _activeBroadcasts.value = broadcasts.sortedByDescending { it.timestamp }

                Log.d("Broadcast", "✅ Loaded ${broadcasts.size} active broadcasts")

            } catch (e: Exception) {
                Log.e("Broadcast", "❌ Error loading broadcasts: ${e.message}")
            }
        }
    }

    /**
     * Nonaktifkan broadcast message (hanya Pengawas)
     */
    fun deactivateBroadcast(
        messageId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                database.child("broadcast_messages/$messageId/active")
                    .setValue(false)
                    .await()

                loadActiveBroadcasts()
                onSuccess()

            } catch (e: Exception) {
                Log.e("Broadcast", "❌ Error deactivating broadcast: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Hapus broadcast message (hanya Pengawas)
     */
    fun deleteBroadcast(
        messageId: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                database.child("broadcast_messages/$messageId")
                    .removeValue()
                    .await()

                loadActiveBroadcasts()
                onSuccess()

            } catch (e: Exception) {
                Log.e("Broadcast", "❌ Error deleting broadcast: ${e.message}")
                onFailure(e)
            }
        }
    }

    // =========================================================================
// TENOR CHANGE REQUEST FUNCTIONS
// =========================================================================

    /**
     * Admin mengajukan perubahan tenor
     */
    fun createTenorChangeRequest(
        pelanggan: Pelanggan,
        tenorBaru: Int,
        alasanPerubahan: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                val adminSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val adminName = adminSnap.child("name").getValue(String::class.java) ?: currentUser.email ?: "Admin"
                val cabangId = adminSnap.child("cabang").getValue(String::class.java) ?: pelanggan.cabangId

                val requestId = UUID.randomUUID().toString()

                val request = mapOf(
                    "id" to requestId,
                    "pelangganId" to pelanggan.id,
                    "pelangganNama" to pelanggan.namaKtp,
                    "adminUid" to pelanggan.adminUid.ifBlank { currentUser.uid },
                    "cabangId" to cabangId,
                    "tenorLama" to pelanggan.tenor,
                    "tenorBaru" to tenorBaru,
                    "alasanPerubahan" to alasanPerubahan,
                    "besarPinjaman" to pelanggan.besarPinjaman,  // ✅ TAMBAH INI
                    "pinjamanKe" to pelanggan.pinjamanKe,        // ✅ TAMBAH INI
                    "requestedByUid" to currentUser.uid,
                    "requestedByName" to adminName,
                    "status" to TenorChangeRequestStatus.PENDING,
                    "createdAt" to System.currentTimeMillis(),
                    "read" to false
                )

                database.child("tenor_change_requests/$cabangId/$requestId")
                    .setValue(request)
                    .await()

                Log.d("TenorChange", "✅ Request perubahan tenor berhasil dibuat")
                onSuccess()

            } catch (e: Exception) {
                Log.e("TenorChange", "❌ Gagal membuat request: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Pimpinan load tenor change requests untuk cabang-nya
     */
    fun loadTenorChangeRequests() {
        viewModelScope.launch {
            try {
                val cabangId = _currentUserCabang.value
                if (cabangId.isNullOrBlank()) {
                    Log.w("TenorChange", "⚠️ CabangId tidak ditemukan")
                    return@launch
                }

                val snapshot = database.child("tenor_change_requests/$cabangId")
                    .orderByChild("status")
                    .equalTo(TenorChangeRequestStatus.PENDING)
                    .get()
                    .await()

                val requests = mutableListOf<TenorChangeRequest>()
                var unreadCount = 0

                for (child in snapshot.children) {
                    try {
                        val request = child.getValue(TenorChangeRequest::class.java)
                        if (request != null) {
                            requests.add(request)
                            if (!request.read) unreadCount++
                        }
                    } catch (e: Exception) {
                        Log.e("TenorChange", "Error parsing: ${e.message}")
                    }
                }

                _tenorChangeRequests.value = requests.sortedByDescending { it.createdAt }
                _unreadTenorChangeCount.value = unreadCount

                Log.d("TenorChange", "✅ Loaded ${requests.size} tenor change requests")

            } catch (e: Exception) {
                Log.e("TenorChange", "❌ Error: ${e.message}")
            }
        }
    }

    /**
     * Pimpinan menyetujui perubahan tenor
     */
    fun approveTenorChange(
        request: TenorChangeRequest,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser ?: throw Exception("User belum login")

                // ✅ STEP 1: Ambil data pelanggan untuk mendapatkan totalPelunasan dan tanggalDaftar
                val pelangganSnapshot = database.child("pelanggan/${request.adminUid}/${request.pelangganId}")
                    .get()
                    .await()

                val totalPelunasan = pelangganSnapshot.child("totalPelunasan").getValue(Int::class.java) ?: 0
                val tanggalDaftar = pelangganSnapshot.child("tanggalDaftar").getValue(String::class.java) ?: ""

                // Fallback ke tanggalApproval jika tanggalDaftar kosong
                val tanggalAwal = if (tanggalDaftar.isNotBlank()) tanggalDaftar
                else pelangganSnapshot.child("tanggalApproval").getValue(String::class.java) ?: ""

                if (totalPelunasan == 0 || tanggalAwal.isBlank()) {
                    throw Exception("Data pelanggan tidak lengkap untuk regenerasi cicilan")
                }

                // ✅ STEP 2: Generate cicilan baru dengan tenor baru
                val cicilanBaru = generateCicilanKonsisten(tanggalAwal, request.tenorBaru, totalPelunasan)

                // ✅ STEP 3: Ambil cicilan lama untuk preserve status isCompleted
                val cicilanLamaList = mutableListOf<SimulasiCicilan>()
                pelangganSnapshot.child("hasilSimulasiCicilan").children.forEach { child ->
                    val cicilan = child.getValue(SimulasiCicilan::class.java)
                    if (cicilan != null) {
                        cicilanLamaList.add(cicilan)
                    }
                }

                // ✅ STEP 4: Preserve isCompleted dari cicilan yang sudah dibayar
                val completedDates = cicilanLamaList.filter { it.isCompleted }.map { it.tanggal }.toSet()
                val cicilanFinal = cicilanBaru.map { cicilan ->
                    if (completedDates.contains(cicilan.tanggal)) {
                        cicilan.copy(isCompleted = true)
                    } else {
                        cicilan
                    }
                }

                // ✅ STEP 5: Convert cicilan ke Map untuk Firebase
                val cicilanMap = cicilanFinal.mapIndexed { index, simulasi ->
                    index.toString() to mapOf(
                        "tanggal" to simulasi.tanggal,
                        "jumlah" to simulasi.jumlah,
                        "isHariKerja" to simulasi.isHariKerja,
                        "isCompleted" to simulasi.isCompleted,
                        "version" to simulasi.version,
                        "lastUpdated" to simulasi.lastUpdated
                    )
                }.toMap()

                // ✅ STEP 6: Update tenor DAN hasilSimulasiCicilan
                val updates = mapOf(
                    "tenor" to request.tenorBaru,
                    "hasilSimulasiCicilan" to cicilanMap,
                    "catatanPerubahanTenor" to "Tenor diubah dari ${request.tenorLama} hari menjadi ${request.tenorBaru} hari. Alasan: ${request.alasanPerubahan}"
                )

                database.child("pelanggan/${request.adminUid}/${request.pelangganId}")
                    .updateChildren(updates)
                    .await()

                Log.d("TenorChange", "✅ Tenor updated: ${request.tenorLama} → ${request.tenorBaru}, Cicilan regenerated: ${cicilanFinal.size} entries")

                // Hapus request
                database.child("tenor_change_requests/${request.cabangId}/${request.id}")
                    .removeValue()
                    .await()

                // Kirim notifikasi ke Admin
                val pimpinanSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val pimpinanName = pimpinanSnap.child("name").getValue(String::class.java) ?: "Pimpinan"

                val notificationId = UUID.randomUUID().toString()
                val notification = mapOf(
                    "id" to notificationId,
                    "type" to "TENOR_CHANGE_APPROVED",
                    "title" to "Perubahan Tenor Disetujui",
                    "message" to "Tenor nasabah ${request.pelangganNama} diubah dari ${request.tenorLama} hari menjadi ${request.tenorBaru} hari",
                    "pelangganId" to request.pelangganId,
                    "reviewedBy" to pimpinanName,
                    "timestamp" to System.currentTimeMillis(),
                    "read" to false
                )

                database.child("admin_notifications/${request.requestedByUid}/$notificationId")
                    .setValue(notification)
                    .await()

                loadTenorChangeRequests()
                onSuccess()

            } catch (e: Exception) {
                Log.e("TenorChange", "❌ Error: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Pimpinan menolak perubahan tenor
     */
    fun rejectTenorChange(
        request: TenorChangeRequest,
        alasanPenolakan: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser ?: throw Exception("User belum login")

                // Hapus request
                database.child("tenor_change_requests/${request.cabangId}/${request.id}")
                    .removeValue()
                    .await()

                // Kirim notifikasi ke Admin
                val pimpinanSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val pimpinanName = pimpinanSnap.child("name").getValue(String::class.java) ?: "Pimpinan"

                val notificationId = UUID.randomUUID().toString()
                val notification = mapOf(
                    "id" to notificationId,
                    "type" to "TENOR_CHANGE_REJECTED",
                    "title" to "Perubahan Tenor Ditolak",
                    "message" to "Pengajuan perubahan tenor ${request.pelangganNama} ditolak. Alasan: $alasanPenolakan",
                    "pelangganId" to request.pelangganId,
                    "pelangganNama" to request.pelangganNama,
                    "reviewedBy" to pimpinanName,
                    "alasanPenolakan" to alasanPenolakan,
                    "catatanPimpinan" to alasanPenolakan,
                    "timestamp" to System.currentTimeMillis(),
                    "read" to false
                )

                database.child("admin_notifications/${request.requestedByUid}/$notificationId")
                    .setValue(notification)
                    .await()

                loadTenorChangeRequests()
                onSuccess()

            } catch (e: Exception) {
                Log.e("TenorChange", "❌ Error: ${e.message}")
                onFailure(e)
            }
        }
    }

    fun markAllTenorChangeAsRead() {
        viewModelScope.launch {
            try {
                val cabangId = _currentUserCabang.value ?: return@launch
                val requests = _tenorChangeRequests.value.filter { !it.read }
                for (request in requests) {
                    database.child("tenor_change_requests/$cabangId/${request.id}/read")
                        .setValue(true)
                        .await()
                }
                _unreadTenorChangeCount.value = 0
            } catch (e: Exception) {
                Log.e("TenorChange", "Error: ${e.message}")
            }
        }
    }

    /**
     * Load foto profil admin dari cache lokal, fallback ke Firebase jika tidak ada
     * HEMAT RTDB: Prioritas cache lokal dulu
     */
    fun loadAdminPhotoUrl() {
        viewModelScope.launch {
            try {
                val uid = Firebase.auth.currentUser?.uid ?: return@launch
                val context = getApplication<Application>().applicationContext
                val prefs = context.getSharedPreferences("admin_profile", Context.MODE_PRIVATE)

                // 1. Cek cache lokal dulu (HEMAT RTDB)
                val cachedUrl = prefs.getString("photo_url_$uid", null)
                if (!cachedUrl.isNullOrBlank()) {
                    _adminPhotoUrl.value = cachedUrl
                    Log.d("AdminPhoto", "✅ Loaded from cache: $cachedUrl")
                    return@launch
                }

                // 2. Jika tidak ada cache, baca dari Firebase (hanya sekali)
                val snapshot = database.child("metadata/admins/$uid/photoUrl").get().await()
                val url = snapshot.getValue(String::class.java)
                if (!url.isNullOrBlank()) {
                    _adminPhotoUrl.value = url
                    // Simpan ke cache
                    prefs.edit().putString("photo_url_$uid", url).apply()
                    Log.d("AdminPhoto", "✅ Loaded from Firebase & cached: $url")
                }
            } catch (e: Exception) {
                Log.e("AdminPhoto", "❌ Error loading photo: ${e.message}")
            }
        }
    }

    /**
     * Upload foto profil admin ke Firebase Storage
     * Path: profile_photos/{uid}.jpg
     */
    fun uploadAdminPhoto(
        imageUri: Uri,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val uid = Firebase.auth.currentUser?.uid ?: throw Exception("User belum login")
                val context = getApplication<Application>().applicationContext

                withContext(Dispatchers.IO) {
                    val storageRef = Firebase.storage.reference
                    val photoRef = storageRef.child("profile_photos/$uid/profile.jpg")

                    // Kompresi gambar (reuse fungsi yang sudah ada tapi lebih kecil)
                    val compressedImage = compressProfilePhoto(imageUri)
                    if (compressedImage.isEmpty()) {
                        throw Exception("Gagal kompresi gambar")
                    }

                    // Upload ke Storage
                    val metadata = StorageMetadata.Builder()
                        .setContentType("image/jpeg")
                        .build()

                    photoRef.putBytes(compressedImage, metadata).await()
                    val downloadUrl = photoRef.downloadUrl.await().toString()

                    // Simpan URL ke RTDB (metadata/admins/{uid}/photoUrl)
                    database.child("metadata/admins/$uid/photoUrl").setValue(downloadUrl).await()

                    // Update cache lokal
                    val prefs = context.getSharedPreferences("admin_profile", Context.MODE_PRIVATE)
                    prefs.edit().putString("photo_url_$uid", downloadUrl).apply()

                    // Update state
                    _adminPhotoUrl.value = downloadUrl

                    Log.d("AdminPhoto", "✅ Upload berhasil: $downloadUrl")

                    withContext(Dispatchers.Main) {
                        onSuccess(downloadUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("AdminPhoto", "❌ Upload gagal: ${e.message}")
                withContext(Dispatchers.Main) {
                    onFailure(e.message ?: "Upload gagal")
                }
            }
        }
    }

    /**
     * Kompresi foto profil dengan mempertahankan aspect ratio
     * Menggunakan center crop untuk hasil yang proporsional di lingkaran
     */
    private fun compressProfilePhoto(uri: Uri): ByteArray {
        return try {
            val context = getApplication<Application>().applicationContext
            val inputStream = context.contentResolver.openInputStream(uri) ?: return ByteArray(0)

            // Step 1: Baca dimensi asli
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            // Step 2: Hitung sample size untuk efisiensi memori
            val targetSize = 512 // Target ukuran sisi terpanjang
            options.inSampleSize = calculateOptimalSampleSize(
                originalWidth, originalHeight, targetSize, targetSize
            )
            options.inJustDecodeBounds = false

            // Step 3: Decode bitmap
            val newInputStream = context.contentResolver.openInputStream(uri) ?: return ByteArray(0)
            val bitmap = BitmapFactory.decodeStream(newInputStream, null, options)
            newInputStream.close()

            if (bitmap == null) return ByteArray(0)

            // Step 4: Buat bitmap persegi dengan center crop (untuk tampilan bulat)
            val squareBitmap = createCenterCroppedSquareBitmap(bitmap)

            // Step 5: Scale ke ukuran final
            val finalSize = 256
            val finalBitmap = Bitmap.createScaledBitmap(squareBitmap, finalSize, finalSize, true)

            // Compress ke JPEG
            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)

            // Cleanup
            if (bitmap != squareBitmap) bitmap.recycle()
            if (squareBitmap != finalBitmap) squareBitmap.recycle()
            finalBitmap.recycle()

            Log.d("AdminPhoto", "✅ Compressed: ${outputStream.size() / 1024}KB")
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("AdminPhoto", "❌ Compress error: ${e.message}")
            ByteArray(0)
        }
    }

    /**
     * Membuat bitmap persegi dari tengah gambar (center crop)
     * Ini akan memastikan wajah tidak terdistorsi saat ditampilkan di lingkaran
     */
    private fun createCenterCroppedSquareBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height

        // Jika sudah persegi, langsung return
        if (width == height) return source

        // Tentukan sisi terpendek sebagai ukuran persegi
        val size = minOf(width, height)

        // Hitung offset untuk center crop
        val xOffset = (width - size) / 2
        val yOffset = (height - size) / 2

        return Bitmap.createBitmap(source, xOffset, yOffset, size, size)
    }

    /**
     * Clear cache foto saat logout
     */
    fun clearAdminPhotoCache() {
        viewModelScope.launch {
            try {
                val uid = Firebase.auth.currentUser?.uid ?: return@launch
                val context = getApplication<Application>().applicationContext
                val prefs = context.getSharedPreferences("admin_profile", Context.MODE_PRIVATE)
                prefs.edit().remove("photo_url_$uid").apply()
                _adminPhotoUrl.value = null
            } catch (e: Exception) {
                Log.e("AdminPhoto", "Error clearing cache: ${e.message}")
            }
        }
    }

    /**
     * Load foto profil semua admin di cabang untuk ditampilkan di dashboard pimpinan
     * HEMAT RTDB: Hanya dipanggil sekali saat load data pimpinan
     */
    fun loadAdminPhotosForCabang(adminUids: List<String>) {
        viewModelScope.launch {
            try {
                val photosMap = mutableMapOf<String, String?>()

                adminUids.forEach { uid ->
                    try {
                        val snapshot = database.child("metadata/admins/$uid/photoUrl").get().await()
                        val url = snapshot.getValue(String::class.java)
                        photosMap[uid] = url
                    } catch (e: Exception) {
                        Log.e("AdminPhotos", "Error loading photo for $uid: ${e.message}")
                        photosMap[uid] = null
                    }
                }

                _adminPhotosMap.value = photosMap
                Log.d("AdminPhotos", "✅ Loaded ${photosMap.filter { it.value != null }.size} admin photos")
            } catch (e: Exception) {
                Log.e("AdminPhotos", "❌ Error loading admin photos: ${e.message}")
            }
        }
    }

    /**
     * Ajukan penghapusan pembayaran ke Pimpinan
     * Path: payment_deletion_requests/{requestId}
     */
    fun createPaymentDeletionRequest(
        pelanggan: Pelanggan,
        pembayaranIndex: Int,
        pembayaran: Pembayaran,
        cicilanKe: Int,
        alasanPenghapusan: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                // Hitung total yang sudah dibayar
                val totalBayar = pelanggan.pembayaranList.sumOf { p ->
                    p.jumlah + p.subPembayaran.sumOf { sub -> sub.jumlah }
                }
                val sisaUtang = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)

                // Generate ID untuk request
                val requestId = UUID.randomUUID().toString()

                // Ambil info admin
                val adminSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val adminName = adminSnap.child("name").getValue(String::class.java) ?: currentUser.email ?: "Admin"
                val adminEmail = adminSnap.child("email").getValue(String::class.java) ?: currentUser.email ?: ""
                val cabangId = adminSnap.child("cabang").getValue(String::class.java) ?: pelanggan.cabangId

                // Buat payment deletion request
                val request = PaymentDeletionRequest(
                    id = requestId,
                    pelangganId = pelanggan.id,
                    pelangganNama = pelanggan.namaKtp,
                    pelangganNik = pelanggan.nik,
                    pelangganWilayah = pelanggan.wilayah,
                    pembayaranIndex = pembayaranIndex,
                    cicilanKe = cicilanKe,
                    jumlahPembayaran = pembayaran.jumlah + pembayaran.subPembayaran.sumOf { it.jumlah },
                    tanggalPembayaran = pembayaran.tanggal,
                    totalPelunasan = pelanggan.totalPelunasan,
                    sudahDibayar = totalBayar,
                    sisaUtang = sisaUtang,
                    adminUid = pelanggan.adminUid.ifBlank { currentUser.uid },
                    requestedByUid = currentUser.uid,
                    requestedByName = adminName,
                    requestedByEmail = adminEmail,
                    cabangId = cabangId,
                    alasanPenghapusan = alasanPenghapusan,
                    status = PaymentDeletionRequestStatus.PENDING,
                    createdAt = System.currentTimeMillis(),
                    read = false
                )

                // Simpan ke Firebase
                database.child("payment_deletion_requests").child(requestId)
                    .setValue(request)
                    .await()

                Log.d("PaymentDeletionRequest", "✅ Request penghapusan pembayaran berhasil dibuat")
                onSuccess()

            } catch (e: Exception) {
                Log.e("PaymentDeletionRequest", "❌ Gagal membuat request: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Load pending payment deletion requests untuk Pimpinan (filtered by cabang)
     */
    fun loadPimpinanPaymentDeletionRequests() {
        viewModelScope.launch {
            try {
                val cabangId = _currentUserCabang.value
                if (cabangId.isNullOrBlank()) {
                    Log.w("PaymentDeletionRequest", "⚠️ CabangId tidak ditemukan untuk Pimpinan")
                    return@launch
                }

                Log.d("PaymentDeletionRequest", "🔄 Loading payment deletion requests for cabang: $cabangId")

                val snapshot = database.child("payment_deletion_requests")
                    .orderByChild("cabangId")
                    .equalTo(cabangId)
                    .get()
                    .await()

                val requests = mutableListOf<PaymentDeletionRequest>()
                var unreadCount = 0

                for (child in snapshot.children) {
                    try {
                        val request = child.getValue(PaymentDeletionRequest::class.java)
                        if (request != null && request.status == PaymentDeletionRequestStatus.PENDING) {
                            requests.add(request)
                            if (!request.read) unreadCount++
                        }
                    } catch (e: Exception) {
                        Log.e("PaymentDeletionRequest", "Error parsing request: ${e.message}")
                    }
                }

                val sortedList = requests.sortedByDescending { it.createdAt }
                _pimpinanPaymentDeletionRequests.value = sortedList
                _unreadPimpinanPaymentDeletionCount.value = unreadCount

                Log.d("PaymentDeletionRequest", "✅ Loaded ${sortedList.size} pending payment deletion requests")

            } catch (e: Exception) {
                Log.e("PaymentDeletionRequest", "❌ Error loading: ${e.message}")
            }
        }
    }

    /**
     * Approve payment deletion request sebagai Pimpinan
     */
    fun approvePaymentDeletionRequest(
        requestId: String,
        catatanPimpinan: String = "",
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                // Ambil info request
                val requestSnap = database.child("payment_deletion_requests/$requestId").get().await()
                val request = requestSnap.getValue(PaymentDeletionRequest::class.java)

                if (request == null) {
                    onFailure(Exception("Request tidak ditemukan"))
                    return@launch
                }

                // Verifikasi Pimpinan hanya bisa approve request dari cabang-nya
                val pimpinanCabang = _currentUserCabang.value
                if (request.cabangId != pimpinanCabang) {
                    onFailure(Exception("Anda tidak berwenang untuk request ini"))
                    return@launch
                }

                Log.d("PaymentDeletionRequest", "📋 Processing payment deletion for: ${request.pelangganNama}")

                val adminUid = request.adminUid.ifBlank { request.requestedByUid }
                val pelangganId = request.pelangganId
                val pembayaranIndex = request.pembayaranIndex

                // Ambil data pelanggan terbaru
                val pelangganSnap = database.child("pelanggan/$adminUid/$pelangganId").get().await()

                if (!pelangganSnap.exists()) {
                    // Pelanggan tidak ditemukan, hapus request saja
                    database.child("payment_deletion_requests/$requestId").removeValue().await()
                    onFailure(Exception("Nasabah tidak ditemukan"))
                    return@launch
                }

                val pelanggan = pelangganSnap.getValue(Pelanggan::class.java)
                if (pelanggan == null) {
                    database.child("payment_deletion_requests/$requestId").removeValue().await()
                    onFailure(Exception("Gagal membaca data nasabah"))
                    return@launch
                }

                // Validasi index pembayaran
                if (pembayaranIndex < 0 || pembayaranIndex >= pelanggan.pembayaranList.size) {
                    database.child("payment_deletion_requests/$requestId").removeValue().await()
                    onFailure(Exception("Pembayaran tidak ditemukan (mungkin sudah dihapus)"))
                    return@launch
                }

                // Hapus pembayaran dari list
                val updatedPembayaranList = pelanggan.pembayaranList.toMutableList().apply {
                    removeAt(pembayaranIndex)
                }

                // Hitung ulang total yang sudah dibayar
                val totalDibayar = updatedPembayaranList.sumOf { p ->
                    p.jumlah + p.subPembayaran.sumOf { sub -> sub.jumlah }
                }
                val status = if (totalDibayar >= pelanggan.totalPelunasan) "Lunas" else "Aktif"

                // Update pelanggan di Firebase
                val updates = mapOf(
                    "pembayaranList" to updatedPembayaranList,
                    "status" to status
                )
                database.child("pelanggan/$adminUid/$pelangganId").updateChildren(updates).await()

                // Hapus request
                database.child("payment_deletion_requests/$requestId").removeValue().await()

                // Kirim notifikasi ke Admin
                val pimpinanSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val pimpinanName = pimpinanSnap.child("name").getValue(String::class.java)
                    ?: currentUser.email ?: "Pimpinan"

                try {
                    val notificationId = UUID.randomUUID().toString()
                    val adminNotification = mapOf(
                        "id" to notificationId,
                        "type" to "PAYMENT_DELETION_APPROVED",
                        "title" to "Penghapusan Pembayaran Disetujui",
                        "message" to "Pembayaran cicilan ke-${request.cicilanKe} (Rp ${request.jumlahPembayaran}) nasabah ${request.pelangganNama} telah dihapus",
                        "pelangganId" to pelangganId,
                        "pelangganNama" to request.pelangganNama,
                        "reviewedBy" to pimpinanName,
                        "timestamp" to System.currentTimeMillis(),
                        "read" to false
                    )

                    database.child("admin_notifications")
                        .child(request.requestedByUid)
                        .child(notificationId)
                        .setValue(adminNotification)
                        .await()
                } catch (e: Exception) {
                    Log.w("PaymentDeletionRequest", "⚠️ Gagal kirim notifikasi: ${e.message}")
                }

                // Update state lokal
                _pimpinanPaymentDeletionRequests.value = _pimpinanPaymentDeletionRequests.value
                    .filter { it.id != requestId }

                // Refresh data pelanggan lokal
                refreshPelangganData(pelangganId, adminUid)

                Log.d("PaymentDeletionRequest", "✅ Payment deletion approved successfully")
                onSuccess()

            } catch (e: Exception) {
                Log.e("PaymentDeletionRequest", "❌ Gagal approve: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * Helper untuk refresh data pelanggan lokal
     */
    private fun refreshPelangganData(pelangganId: String, adminUid: String) {
        viewModelScope.launch {
            try {
                val snapshot = database.child("pelanggan/$adminUid/$pelangganId").get().await()
                val updatedPelanggan = snapshot.getValue(Pelanggan::class.java)
                if (updatedPelanggan != null) {
                    val index = daftarPelanggan.indexOfFirst { it.id == pelangganId }
                    if (index != -1) {
                        daftarPelanggan[index] = updatedPelanggan
                    }
                }
            } catch (e: Exception) {
                Log.e("PaymentDeletionRequest", "Error refreshing pelanggan data: ${e.message}")
            }
        }
    }

    /**
     * Tolak payment deletion request sebagai Pimpinan
     */
    fun rejectPaymentDeletionRequest(
        requestId: String,
        catatanPimpinan: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentUser = Firebase.auth.currentUser
                if (currentUser == null) {
                    onFailure(Exception("User belum login"))
                    return@launch
                }

                val requestSnap = database.child("payment_deletion_requests/$requestId").get().await()
                val request = requestSnap.getValue(PaymentDeletionRequest::class.java)

                if (request == null) {
                    onFailure(Exception("Request tidak ditemukan"))
                    return@launch
                }

                val pimpinanCabang = _currentUserCabang.value
                if (request.cabangId != pimpinanCabang) {
                    onFailure(Exception("Anda tidak berwenang untuk request ini"))
                    return@launch
                }

                // Ambil info pimpinan
                val pimpinanSnap = database.child("metadata/admins/${currentUser.uid}").get().await()
                val pimpinanName = pimpinanSnap.child("name").getValue(String::class.java)
                    ?: currentUser.email ?: "Pimpinan"

                // Hapus request
                database.child("payment_deletion_requests/$requestId").removeValue().await()

                // Kirim notifikasi ke Admin
                try {
                    val notificationId = UUID.randomUUID().toString()
                    val adminNotification = mapOf(
                        "id" to notificationId,
                        "type" to "PAYMENT_DELETION_REJECTED",
                        "title" to "Penghapusan Pembayaran Ditolak",
                        "message" to "Pengajuan hapus cicilan ke-${request.cicilanKe} nasabah ${request.pelangganNama} ditolak. Alasan: $catatanPimpinan",
                        "pelangganId" to request.pelangganId,
                        "pelangganNama" to request.pelangganNama,
                        "reviewedBy" to pimpinanName,
                        "timestamp" to System.currentTimeMillis(),
                        "read" to false
                    )

                    database.child("admin_notifications")
                        .child(request.requestedByUid)
                        .child(notificationId)
                        .setValue(adminNotification)
                        .await()
                } catch (e: Exception) {
                    Log.w("PaymentDeletionRequest", "⚠️ Gagal kirim notifikasi: ${e.message}")
                }

                // Update state lokal
                _pimpinanPaymentDeletionRequests.value = _pimpinanPaymentDeletionRequests.value
                    .filter { it.id != requestId }

                Log.d("PaymentDeletionRequest", "✅ Payment deletion rejected successfully")
                onSuccess()

            } catch (e: Exception) {
                Log.e("PaymentDeletionRequest", "❌ Gagal reject: ${e.message}")
                onFailure(e)
            }
        }
    }

    /**
     * ONE-TIME CLEANUP: Bersihkan field lama nasabah yang sudah lanjut pinjaman
     * tapi masih punya statusKhusus/statusPencairanSimpanan dari pinjaman sebelumnya.
     * Panggil sekali saja, lalu hapus pemanggilannya.
     */
    fun cleanupStuckMenungguPencairan() {
        viewModelScope.launch {
            try {
                var count = 0
                for (pelanggan in daftarPelanggan.toList()) {
                    val sudahLanjut = pelanggan.status == "Menunggu Approval"

                    val masihKotor = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN" ||
                            pelanggan.statusPencairanSimpanan == "Menunggu Pencairan"

                    if (sudahLanjut && masihKotor) {
                        val adminUid = pelanggan.adminUid.ifBlank { Firebase.auth.currentUser?.uid ?: "" }
                        if (adminUid.isBlank()) continue

                        val updates = mapOf<String, Any?>(
                            "statusPencairanSimpanan" to "",
                            "tanggalLunasCicilan" to "",
                            "statusKhusus" to "",
                            "catatanStatusKhusus" to "",
                            "tanggalStatusKhusus" to "",
                            "diberiTandaOleh" to "",
                            "lastUpdated" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        )

                        database.child("pelanggan").child(adminUid).child(pelanggan.id)
                            .updateChildren(updates)

                        // Update lokal juga
                        val index = daftarPelanggan.indexOfFirst { it.id == pelanggan.id }
                        if (index != -1) {
                            daftarPelanggan[index] = daftarPelanggan[index].copy(
                                statusPencairanSimpanan = "",
                                tanggalLunasCicilan = "",
                                statusKhusus = "",
                                catatanStatusKhusus = "",
                                tanggalStatusKhusus = "",
                                diberiTandaOleh = ""
                            )
                        }

                        // Hapus dari pelanggan_status_khusus jika ada
                        val cabangId = _currentUserCabang.value ?: ""
                        if (cabangId.isNotBlank()) {
                            database.child("pelanggan_status_khusus")
                                .child(cabangId)
                                .child(pelanggan.id)
                                .removeValue()
                        }

                        count++
                        Log.d("Cleanup", "✅ Cleaned: ${pelanggan.namaPanggilan} (${pelanggan.id})")
                    }
                }
                Log.d("Cleanup", "🧹 Cleanup selesai: $count nasabah dibersihkan")
            } catch (e: Exception) {
                Log.e("Cleanup", "❌ Error cleanup: ${e.message}")
            }
        }
    }

    fun formatRupiahPublic(amount: Int): String = formatRupiah(amount)
}