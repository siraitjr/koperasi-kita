package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.koperasikitagodangulu.utils.formatRupiah
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import coil.compose.AsyncImage
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.example.koperasikitagodangulu.models.ApprovalStatus
import com.example.koperasikitagodangulu.models.ApproverRole
import com.example.koperasikitagodangulu.models.DualApprovalThreshold
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.AccountBalanceWallet
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoordinatorApprovalScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel,
    initialTab: Int = 0
) {
    val context = LocalContext.current

    // =========================================================================
    // STATE MANAGEMENT
    // =========================================================================
    var showApprovalDialog by remember { mutableStateOf(false) }
    var approvalNote by remember { mutableStateOf("") }
    var showApprovalWithAmountDialog by remember { mutableStateOf(false) }
    var selectedPelangganForAmount by remember { mutableStateOf<Pelanggan?>(null) }

    // Pending approvals untuk koordinator Phase 2 (AWAITING_KOORDINATOR)
    val pendingApprovalsKoordinator by viewModel.pendingApprovalsKoordinator.collectAsState()

    // =========================================================================
    // ✅ DARK MODE SUPPORT
    // =========================================================================
    val isDark by viewModel.isDarkMode
    val systemUiController = rememberSystemUiController()
    val bgColor = KoordinatorColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Pending finalisasi untuk koordinator Phase 4 (AWAITING_KOORDINATOR_FINAL)
    val pendingKoordinatorFinal by viewModel.pendingKoordinatorFinal.collectAsState()

    // Pengajuan cairkan setengah simpanan
    val pendingCairanSimpanan by viewModel.pendingCairanSimpananKoordinator.collectAsState()
    var showApproveCairanSimpananDialog by remember { mutableStateOf(false) }
    var showRejectCairanSimpananDialog by remember { mutableStateOf(false) }
    var selectedCairanSimpananItem by remember { mutableStateOf<PengajuanCairanSimpananItem?>(null) }
    var cairanSimpananRejectNote by remember { mutableStateOf("") }

    var selectedPelanggan by remember { mutableStateOf<Pelanggan?>(null) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var rejectReason by remember { mutableStateOf("") }
    val bottomSheetState = rememberModalBottomSheetState()

    // Loading dan feedback state
    var isLoading by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }

    val koordinatorSerahTerimaNotifications by viewModel.pengawasSerahTerimaNotifications.collectAsState()
    val unreadKoordinatorSerahTerimaCount by viewModel.unreadPengawasSerahTerimaCount.collectAsState()

    // ✅ TAMBAHAN: Tab state
    var selectedTabIndex by remember { mutableStateOf(initialTab) }  // ✅ Gunakan initialTab

    // ✅ BARU: State untuk dialog tarik tabungan finalisasi
    var showFinalisasiTarikTabunganDialog by remember { mutableStateOf(false) }
    var selectedPelangganForFinalisasi by remember { mutableStateOf<Pelanggan?>(null) }

    // =========================================================================
    // INITIALIZATION
    // =========================================================================
    LaunchedEffect(Unit) {
        try {
            Log.d("KoordinatorApproval", "🔄 Loading pending approvals for Koordinator")
            viewModel.loadPendingApprovalsForKoordinator()  // ✅ GANTI
            viewModel.loadPendingKoordinatorFinal()          // ✅ TAMBAH
            viewModel.loadPendingCairanSimpananForKoordinator()

            viewModel.markAllPengawasPengajuanNotificationsAsRead()
            viewModel.loadSerahTerimaNotificationsForPengawas()
        } catch (e: Exception) {
            Log.e("KoordinatorApproval", "Error loading data: ${e.message}")
        }
    }

    // Handle operation result
    LaunchedEffect(operationMessage) {
        operationMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.loadPendingApprovalsForKoordinator()  // ✅ GANTI
            viewModel.loadPendingKoordinatorFinal()          // ✅ TAMBAH
            viewModel.loadPendingCairanSimpananForKoordinator()
            operationMessage = null
        }
    }

    // =========================================================================
    // UI
    // =========================================================================
    Scaffold(
        containerColor = KoordinatorColors.getBackground(isDark),
        topBar = {
            KoordinatorTopBar(
                title = "Approval Pinjaman",
                navController = navController,
                viewModel = viewModel,
                onRefresh = {
                    viewModel.loadPendingApprovalsForPengawas()
                    viewModel.loadSerahTerimaNotificationsForPengawas()
                }
            )
        },
        bottomBar = {
            KoordinatorBottomNavigation(navController, "koordinator_approvals", isDark = isDark)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Header Info - menjelaskan scope koordinator
//            KoordinatorApprovalHeader()
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = KoordinatorColors.getCard(isDark)
            ) {
                // Tab Pengajuan
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Pengajuan") },
                    icon = {
                        Box {
                            Icon(Icons.Default.Pending, contentDescription = null)
                            if (pendingApprovalsKoordinator.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.Red, CircleShape)
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                )

                // Tab Finalisasi (BARU)
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        viewModel.loadPendingKoordinatorFinal()
                    },
                    text = { Text("Finalisasi") },
                    icon = {
                        Box {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            if (pendingKoordinatorFinal.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.Red, CircleShape)
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                )

                // Tab Serah Terima
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = {
                        selectedTabIndex = 2
                        viewModel.markAllPengawasSerahTerimaAsRead()
                    },
                    text = { Text("Serah Terima") },
                    icon = {
                        Box {
                            Icon(Icons.Default.Verified, contentDescription = null)
                            if (unreadKoordinatorSerahTerimaCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.Red, CircleShape)
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                )

                // Tab Cairkan Simpanan
                Tab(
                    selected = selectedTabIndex == 3,
                    onClick = {
                        selectedTabIndex = 3
                        viewModel.loadPendingCairanSimpananForKoordinator()
                    },
                    text = { Text("Cairkan Simpanan") },
                    icon = {
                        Box {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null)
                            if (pendingCairanSimpanan.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.Red, CircleShape)
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                )
            }

            // ✅ KONTEN BERDASARKAN TAB
            when (selectedTabIndex) {
                0 -> {
                    // TAB PENGAJUAN (Phase 2 - AWAITING_KOORDINATOR)
                    KoordinatorApprovalContent(
                        isDark = isDark,
                        isLoading = isLoading,
                        pendingApprovals = pendingApprovalsKoordinator,
                        onViewDetail = { pelanggan ->
                            selectedPelanggan = pelanggan
                            showDetailSheet = true
                        },
                        onApprove = { pelanggan ->
                            selectedPelanggan = pelanggan
                            showApprovalDialog = true
                        },
                        onReject = { pelanggan ->
                            selectedPelanggan = pelanggan
                            showRejectDialog = true
                        },
                        onAdjustAmount = { pelanggan ->
                            selectedPelangganForAmount = pelanggan
                            showApprovalWithAmountDialog = true
                        }
                    )
                }
                1 -> {
                    // ✅ BARU: TAB FINALISASI (Phase 4 - AWAITING_KOORDINATOR_FINAL)
                    KoordinatorFinalisasiTabContent(
                        isDark = isDark,
                        isLoading = isLoading,
                        pendingFinal = pendingKoordinatorFinal,
                        onViewDetail = { pelanggan ->
                            selectedPelanggan = pelanggan
                            showDetailSheet = true
                        },
                        onFinalize = { pelanggan ->
                            // Konfirmasi langsung tanpa tarik tabungan
                            isLoading = true
                            viewModel.finalizeKoordinatorApproval(
                                pelangganId = pelanggan.id,
                                catatan = "Dikonfirmasi oleh Koordinator",
                                tarikTabungan = 0,  // ✅ Tanpa tarik tabungan
                                onSuccess = {
                                    isLoading = false
                                    operationMessage = "Pengajuan berhasil dikonfirmasi"
                                },
                                onFailure = { e ->
                                    isLoading = false
                                    operationMessage = "Gagal konfirmasi: ${e.message}"
                                }
                            )
                        },
                        onFinalizeWithTarikTabungan = { pelanggan ->
                            // ✅ BARU: Buka dialog tarik tabungan
                            selectedPelangganForFinalisasi = pelanggan
                            showFinalisasiTarikTabunganDialog = true
                        }
                    )
                }
                2 -> {
                    // TAB SERAH TERIMA (tidak berubah, hanya index berubah dari 1 ke 2)
                    KoordinatorSerahTerimaTabContent(
                        isDark = isDark,
                        notifications = koordinatorSerahTerimaNotifications,
                        onItemClick = { notification ->
                            navController.navigate("koordinator_detail_serah_terima/${notification.id}")
                        },
                        onRefresh = {
                            viewModel.refreshPengawasSerahTerimaNotifications()
                        },
                        onMarkComplete = { notification ->
                            viewModel.deletePengawasSerahTerimaNotification(
                                notificationId = notification.id,
                                onSuccess = {}
                            )
                        }
                    )
                }
                3 -> {
                    // TAB CAIRKAN SIMPANAN
                    KoordinatorCairanSimpananTabContent(
                        isDark = isDark,
                        pendingItems = pendingCairanSimpanan,
                        onApprove = { item ->
                            selectedCairanSimpananItem = item
                            showApproveCairanSimpananDialog = true
                        },
                        onReject = { item ->
                            selectedCairanSimpananItem = item
                            showRejectCairanSimpananDialog = true
                        }
                    )
                }
            }
        }

        // =========================================================================
        // DIALOGS
        // =========================================================================

        // ✅ BARU: Dialog Tarik Tabungan untuk Finalisasi
        if (showFinalisasiTarikTabunganDialog && selectedPelangganForFinalisasi != null) {
            val pelangganFinalisasi = selectedPelangganForFinalisasi ?: return@Scaffold
            KoordinatorFinalisasiTarikTabunganDialog(
                pelanggan = pelangganFinalisasi,
                onConfirm = { tarikTabungan, catatan ->
                    isLoading = true
                    viewModel.finalizeKoordinatorApproval(
                        pelangganId = pelangganFinalisasi.id,
                        catatan = catatan.ifBlank { "Dikonfirmasi dengan tarik tabungan oleh Koordinator" },
                        tarikTabungan = tarikTabungan,
                        onSuccess = {
                            isLoading = false
                            operationMessage = "Pengajuan berhasil dikonfirmasi dengan tarik tabungan Rp ${formatRupiah(tarikTabungan)}"
                            showFinalisasiTarikTabunganDialog = false
                            selectedPelangganForFinalisasi = null
                        },
                        onFailure = { e ->
                            isLoading = false
                            operationMessage = "Gagal konfirmasi: ${e.message}"
                            showFinalisasiTarikTabunganDialog = false
                        }
                    )
                },
                onDismiss = {
                    showFinalisasiTarikTabunganDialog = false
                    selectedPelangganForFinalisasi = null
                }
            )
        }

        // Dialog Setujui dengan Penyesuaian
        if (showApprovalWithAmountDialog && selectedPelangganForAmount != null) {
            val pelangganForAmount = selectedPelangganForAmount ?: return@Scaffold
            KoordinatorApprovalWithAmountDialog(
                pelanggan = pelangganForAmount,
                onConfirm = { disetujuiAmount, tenorDisetujui, catatan ->
                    isLoading = true
                    viewModel.approvePengajuanAsKoordinator(
                        pelangganId = pelangganForAmount.id,
                        catatan = catatan,
                        besarPinjamanDisetujui = disetujuiAmount,
                        tenorDisetujui = tenorDisetujui,
                        catatanPerubahanPinjaman = "Disetujui koordinator dengan penyesuaian jumlah dari " +
                                "Rp ${formatRupiah(pelangganForAmount.besarPinjaman)} menjadi " +
                                "Rp ${formatRupiah(disetujuiAmount)} dan tenor dari " +
                                "${pelangganForAmount.tenor} hari menjadi ${tenorDisetujui} hari",
                        onSuccess = {
                            isLoading = false
                            operationMessage = "Pengajuan berhasil disetujui oleh Koordinator"
                            showApprovalWithAmountDialog = false
                            selectedPelangganForAmount = null
                        },
                        onFailure = { e ->
                            isLoading = false
                            operationMessage = "Gagal menyetujui pengajuan: ${e.message}"
                            showApprovalWithAmountDialog = false
                        }
                    )
                },
                onDismiss = {
                    showApprovalWithAmountDialog = false
                    selectedPelangganForAmount = null
                }
            )
        }

        // Detail Sheet
        if (showDetailSheet && selectedPelanggan != null) {
            ModalBottomSheet(
                onDismissRequest = { showDetailSheet = false },
                sheetState = bottomSheetState,
                modifier = Modifier.fillMaxSize()
            ) {
                selectedPelanggan?.let { pelanggan ->
                    KoordinatorDetailPengajuanSheet(
                        pelanggan = pelanggan,
                        onApprove = {
                            showDetailSheet = false
                            showApprovalDialog = true
                        },
                        onReject = {
                            showDetailSheet = false
                            showRejectDialog = true
                        },
                        onClose = { showDetailSheet = false },
                        viewModel = viewModel
                    )
                }
            }
        }

        // Reject Dialog
        if (showRejectDialog && selectedPelanggan != null) {
            KoordinatorRejectDialog(
                onConfirm = { reason ->
                    isLoading = true
                    selectedPelanggan?.let { pelanggan ->
                        viewModel.rejectPengajuanAsKoordinator(
                            pelangganId = pelanggan.id,
                            alasan = reason,
                            onSuccess = {
                                isLoading = false
                                operationMessage = "Pengajuan ditolak oleh Koordinator"
                                showRejectDialog = false
                                rejectReason = ""
                            },
                            onFailure = { e ->
                                isLoading = false
                                operationMessage = "Gagal menolak pengajuan: ${e.message}"
                                showRejectDialog = false
                            }
                        )
                    }
                },
                onDismiss = {
                    showRejectDialog = false
                    rejectReason = ""
                },
                reason = rejectReason,
                onReasonChange = { rejectReason = it }
            )
        }

        // Dialog Setujui Cairkan Setengah Simpanan
        if (showApproveCairanSimpananDialog && selectedCairanSimpananItem != null) {
            val item = selectedCairanSimpananItem ?: return@Scaffold
            AlertDialog(
                onDismissRequest = {
                    showApproveCairanSimpananDialog = false
                    selectedCairanSimpananItem = null
                },
                title = { Text("Setujui Pencairan Simpanan", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nasabah: ${item.pelangganNama}")
                        Text("Jumlah dicairkan: Rp ${formatRupiah(item.jumlahDicairkan)}")
                        Text(
                            "Simpanan nasabah akan dikurangi dan status diubah menjadi Lunas.",
                            fontSize = 13.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isLoading = true
                            viewModel.approveCairanSimpananByKoordinator(
                                item = item,
                                onSuccess = {
                                    isLoading = false
                                    operationMessage = "Pencairan simpanan ${item.pelangganNama} disetujui"
                                    showApproveCairanSimpananDialog = false
                                    selectedCairanSimpananItem = null
                                },
                                onFailure = { e ->
                                    isLoading = false
                                    operationMessage = "Gagal menyetujui: ${e.message}"
                                    showApproveCairanSimpananDialog = false
                                    selectedCairanSimpananItem = null
                                }
                            )
                        }
                    ) {
                        Text("Setujui")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        showApproveCairanSimpananDialog = false
                        selectedCairanSimpananItem = null
                    }) {
                        Text("Batal")
                    }
                }
            )
        }

        // Dialog Tolak Cairkan Setengah Simpanan
        if (showRejectCairanSimpananDialog && selectedCairanSimpananItem != null) {
            val item = selectedCairanSimpananItem ?: return@Scaffold
            AlertDialog(
                onDismissRequest = {
                    showRejectCairanSimpananDialog = false
                    selectedCairanSimpananItem = null
                    cairanSimpananRejectNote = ""
                },
                title = { Text("Tolak Pencairan Simpanan", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nasabah: ${item.pelangganNama}")
                        OutlinedTextField(
                            value = cairanSimpananRejectNote,
                            onValueChange = { cairanSimpananRejectNote = it },
                            label = { Text("Alasan penolakan") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isLoading = true
                            viewModel.rejectCairanSimpananByKoordinator(
                                item = item,
                                catatan = cairanSimpananRejectNote.ifBlank { "Ditolak oleh Koordinator" },
                                onSuccess = {
                                    isLoading = false
                                    operationMessage = "Pencairan simpanan ${item.pelangganNama} ditolak"
                                    showRejectCairanSimpananDialog = false
                                    selectedCairanSimpananItem = null
                                    cairanSimpananRejectNote = ""
                                },
                                onFailure = { e ->
                                    isLoading = false
                                    operationMessage = "Gagal menolak: ${e.message}"
                                    showRejectCairanSimpananDialog = false
                                    selectedCairanSimpananItem = null
                                    cairanSimpananRejectNote = ""
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("Tolak")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        showRejectCairanSimpananDialog = false
                        selectedCairanSimpananItem = null
                        cairanSimpananRejectNote = ""
                    }) {
                        Text("Batal")
                    }
                }
            )
        }

        // Approval Note Dialog
        if (showApprovalDialog && selectedPelanggan != null) {
            val pelangganForApproval = selectedPelanggan ?: return@Scaffold
            KoordinatorApprovalNoteDialog(
                pelanggan = pelangganForApproval,
                onConfirm = { catatan ->
                    isLoading = true
                    viewModel.approvePengajuanAsKoordinator(
                        pelangganId = pelangganForApproval.id,
                        catatan = catatan,
                        onSuccess = {
                            isLoading = false
                            operationMessage = "Pengajuan berhasil disetujui oleh Koordinator"
                            showApprovalDialog = false
                            approvalNote = ""
                        },
                        onFailure = { e ->
                            isLoading = false
                            operationMessage = "Gagal menyetujui pengajuan: ${e.message}"
                            showApprovalDialog = false
                        }
                    )
                },
                onDismiss = {
                    showApprovalDialog = false
                    approvalNote = ""
                },
                note = approvalNote,
                onNoteChange = { approvalNote = it }
            )
        }
    }
}

// =============================================================================
// HEADER COMPONENT
// =============================================================================
@Composable
private fun KoordinatorApprovalHeader(isDark: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = KoordinatorColors.primary.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = KoordinatorColors.primary,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = "Review Pinjaman Besar (Tahap 2/3)",  // ✅ UPDATE
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = KoordinatorColors.getTextPrimary(isDark)
                )
                Text(
                    text = "Pengajuan ≥ Rp 3.000.000 yang sudah di-review Pimpinan. " +
                            "Keputusan Anda adalah FINAL.",  // ✅ UPDATE
                    fontSize = 12.sp,
                    color = KoordinatorColors.getTextSecondary(isDark)
                )
            }
        }
    }
}

// =============================================================================
// CONTENT COMPONENT
// =============================================================================
@Composable
private fun KoordinatorApprovalContent(
    isDark: Boolean = false,
    isLoading: Boolean,
    pendingApprovals: List<Pelanggan>,
    onViewDetail: (Pelanggan) -> Unit,
    onApprove: (Pelanggan) -> Unit,
    onReject: (Pelanggan) -> Unit,
    onAdjustAmount: (Pelanggan) -> Unit
) {
    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = KoordinatorColors.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Memproses...", color = KoordinatorColors.getTextSecondary(isDark))
                }
            }
        }

        pendingApprovals.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = KoordinatorColors.getTextMuted(isDark),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "Tidak Ada Pengajuan",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KoordinatorColors.getTextSecondary(isDark),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Semua pengajuan pinjaman ≥ Rp 3.000.000 telah diproses",
                        fontSize = 14.sp,
                        color = KoordinatorColors.getTextMuted(isDark),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Pengajuan Menunggu Persetujuan",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = KoordinatorColors.getTextPrimary(isDark)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        "${pendingApprovals.size} pengajuan perlu ditinjau",
                        fontSize = 14.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                items(pendingApprovals) { pelanggan ->
                    KoordinatorApprovalCard(
                        isDark = isDark,
                        pelanggan = pelanggan,
                        onViewDetail = { onViewDetail(pelanggan) },
                        onApprove = { onApprove(pelanggan) },
                        onReject = { onReject(pelanggan) },
                        onAdjustAmount = { onAdjustAmount(pelanggan) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

// =============================================================================
// APPROVAL CARD
// =============================================================================
@Composable
private fun KoordinatorApprovalCard(
    isDark: Boolean = false,
    pelanggan: Pelanggan,
    onViewDetail: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onAdjustAmount: () -> Unit
) {
    val dualInfo = pelanggan.dualApprovalInfo
    val pimpinanStatus = dualInfo?.pimpinanApproval?.status ?: "pending"
    val pimpinanBy = dualInfo?.pimpinanApproval?.by ?: "Pimpinan"
    val pimpinanNote = dualInfo?.pimpinanApproval?.note ?: ""

    val isPimpinanApproved = pimpinanStatus == "approved"
    val pimpinanStatusColor = if (isPimpinanApproved) Color(0xFF4CAF50) else Color(0xFFF44336)
    val pimpinanStatusText = if (isPimpinanApproved) "SETUJU" else "TOLAK"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewDetail),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pelanggan.namaPanggilan,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = KoordinatorColors.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Phase indicator
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFF9800).copy(alpha = 0.1f)
                ) {
                    Text(
                        "Tahap 2/3",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color(0xFFFF9800),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ✅ BARU: Info Review Pimpinan
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = pimpinanStatusColor.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isPimpinanApproved) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = pimpinanStatusColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Pimpinan ($pimpinanBy): $pimpinanStatusText",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = pimpinanStatusColor
                        )
                    }

                    if (pimpinanNote.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Catatan: $pimpinanNote",
                            fontSize = 12.sp,
                            color = KoordinatorColors.getTextMuted(isDark)
                        )
                    }

                    // Tampilkan penyesuaian Pimpinan jika ada
                    val pimpinanAdjusted = dualInfo?.pimpinanApproval?.adjustedAmount ?: 0
                    if (pimpinanAdjusted > 0 && pimpinanAdjusted != pelanggan.besarPinjamanDiajukan) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Penyesuaian Pimpinan: Rp ${formatRupiah(pimpinanAdjusted)}",
                            fontSize = 12.sp,
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info tambahan
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoChip(
                    isDark = isDark,  // ✅ TAMBAHKAN
                    icon = Icons.Default.Person,
                    label = pelanggan.adminName.ifBlank { "Admin" }
                )
                InfoChip(
                    isDark = isDark,  // ✅ TAMBAHKAN
                    icon = Icons.Default.DateRange,
                    label = "${pelanggan.tenor} hari"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tombol Aksi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tombol Tolak
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFF44336)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFF44336))
                ) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tolak")
                }

                // Tombol Penyesuaian
                OutlinedButton(
                    onClick = onAdjustAmount,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF9800)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFFF9800))
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ubah")
                }

                // Tombol Setujui
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KoordinatorColors.primary
                    )
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Setuju")
                }
            }

            // ✅ BARU: Reminder bahwa keputusan Koordinator adalah FINAL
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "⚠️ Keputusan Anda adalah FINAL dan akan diteruskan ke Pimpinan untuk konfirmasi.",
                fontSize = 11.sp,
                color = KoordinatorColors.getTextMuted(isDark),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InfoChip(
    isDark: Boolean = false,
    icon: ImageVector,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = KoordinatorColors.getTextMuted(isDark),  // ✅ FIX
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = KoordinatorColors.getTextMuted(isDark)  // ✅ FIX
        )
    }
}

@Composable
private fun KoordinatorInfoItem(
    isDark: Boolean = false,
    icon: ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = KoordinatorColors.getTextMuted(isDark),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = KoordinatorColors.getTextMuted(isDark)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = KoordinatorColors.getTextPrimary(isDark),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =============================================================================
// DETAIL SHEET - FIXED VERSION WITH PHOTOS
// =============================================================================
@Composable
private fun KoordinatorDetailPengajuanSheet(
    isDark: Boolean = false,
    pelanggan: Pelanggan,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
    viewModel: PelangganViewModel
) {
    val scrollState = rememberScrollState()

    // Cek status approval pimpinan
    val pimpinanApproval = pelanggan.dualApprovalInfo?.pimpinanApproval
    val isPimpinanApproved = pimpinanApproval?.status == ApprovalStatus.APPROVED

    // ✅ BARU: State untuk zoom foto
    var showZoomDialog by remember { mutableStateOf(false) }
    var zoomImageUrl by remember { mutableStateOf("") }
    var zoomImageTitle by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Detail Pengajuan",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Tutup")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ✅ BARU: Badge Nasabah Baru / Pinjaman Ke
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            // Badge pinjaman ke
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (pelanggan.pinjamanKe <= 1)
                    Color(0xFF4CAF50).copy(alpha = 0.15f)
                else Color(0xFF2196F3).copy(alpha = 0.15f)
            ) {
                Text(
                    text = if (pelanggan.pinjamanKe <= 1) "🆕 NASABAH BARU" else "🔄 PINJAMAN KE-${pelanggan.pinjamanKe}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (pelanggan.pinjamanKe <= 1)
                        Color(0xFF2E7D32) else Color(0xFF1565C0)
                )
            }

            // Badge pinjaman besar
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = KoordinatorColors.warning.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "💰 PINJAMAN BESAR",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = KoordinatorColors.warning
                )
            }
        }

        // Status Dual Approval Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = KoordinatorColors.primary.copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Status Persetujuan Ganda",
                    fontWeight = FontWeight.Bold,
                    color = KoordinatorColors.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Status Pimpinan
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (isPimpinanApproved) Icons.Default.CheckCircle else Icons.Default.Schedule,
                            contentDescription = null,
                            tint = if (isPimpinanApproved) KoordinatorColors.success else KoordinatorColors.warning,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "Pimpinan",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (isPimpinanApproved) "Disetujui" else "Menunggu",
                            fontSize = 11.sp,
                            color = if (isPimpinanApproved) KoordinatorColors.success else KoordinatorColors.warning
                        )
                        if (isPimpinanApproved && pimpinanApproval != null) {
                            Text(
                                pimpinanApproval.by.take(20),
                                fontSize = 10.sp,
                                color = KoordinatorColors.getTextMuted(isDark)
                            )
                        }
                    }

                    // Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(60.dp)
                            .background(KoordinatorColors.getBorder(isDark))
                    )

                    // Status Koordinator (selalu pending di screen ini)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = KoordinatorColors.warning,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "Koordinator",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Menunggu Anda",
                            fontSize = 11.sp,
                            color = KoordinatorColors.warning
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Data Nasabah
        DetailSection(isDark = isDark, title = "Data Nasabah") {
            DetailRow(isDark = isDark, label = "Nama Panggilan", value = pelanggan.namaPanggilan)
            DetailRow(isDark = isDark, label = "Nama KTP", value = pelanggan.namaKtp)
            DetailRow(isDark = isDark, label = "NIK", value = pelanggan.nik)
            DetailRow(isDark = isDark, label = "No. HP", value = pelanggan.noHp)
            DetailRow(isDark = isDark, label = "Alamat Rumah", value = pelanggan.alamatRumah)
            DetailRow(isDark = isDark, label = "Alamat KTP", value = pelanggan.alamatKtp)
            DetailRow(isDark = isDark, label = "Wilayah", value = pelanggan.wilayah)
            DetailRow(isDark = isDark, label = "No. Anggota", value = pelanggan.nomorAnggota)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Data Pinjaman
        DetailSection(isDark = isDark, title = "Data Pinjaman") {
            DetailRow(
                isDark = isDark,
                label = "Pinjaman Ke",
                value = if (pelanggan.pinjamanKe <= 1) "Pertama (Nasabah Baru)" else "Ke-${pelanggan.pinjamanKe}",
                valueColor = if (pelanggan.pinjamanKe <= 1) Color(0xFF4CAF50) else Color(0xFF2196F3)
            )
            DetailRow(
                isDark = isDark,
                label = "Besar Pinjaman",
                value = "Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                valueColor = KoordinatorColors.primary
            )
            DetailRow(isDark = isDark, label = "Tenor", value = "${pelanggan.tenor} hari")
            DetailRow(isDark = isDark, label = "Total Potongan", value = "Rp ${formatRupiah(pelanggan.jasaPinjaman)}")
//            DetailRow(label = "Admin", value = "Rp ${formatRupiah(pelanggan.admin)}")
//            DetailRow(label = "Simpanan", value = "Rp ${formatRupiah(pelanggan.simpanan)}")
            DetailRow(isDark = isDark, label = "Total Pelunasan", value = "Rp ${formatRupiah(pelanggan.totalPelunasan)}")
            DetailRow(isDark = isDark, label = "Total Diterima", value = "Rp ${formatRupiah(pelanggan.totalDiterima)}")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Data Usaha
        DetailSection(isDark = isDark, title = "Data Usaha") {
            DetailRow(isDark = isDark, label = "Jenis Usaha", value = pelanggan.jenisUsaha)
            DetailRow(isDark = isDark, label = "Tanggal Pengajuan", value = pelanggan.tanggalPengajuan)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // ✅ PERBAIKAN: SECTION FOTO LENGKAP
        // =========================================================================
        DetailSection(isDark = isDark, title = "Dokumen Foto") {
            // URL foto: prioritaskan pending (foto BARU top-up) di atas permanent.
            val fotoKtpResolved = pelanggan.pendingFotoKtpUrl.ifBlank { pelanggan.fotoKtpUrl }
            val fotoKtpSuamiResolved = pelanggan.pendingFotoKtpSuamiUrl.ifBlank { pelanggan.fotoKtpSuamiUrl }
            val fotoKtpIstriResolved = pelanggan.pendingFotoKtpIstriUrl.ifBlank { pelanggan.fotoKtpIstriUrl }
            val fotoNasabahResolved = pelanggan.pendingFotoNasabahUrl.ifBlank { pelanggan.fotoNasabahUrl }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // Row untuk KTP Suami & Istri
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Foto KTP Suami
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Foto KTP Suami",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = KoordinatorColors.getTextSecondary(isDark),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        if (fotoKtpSuamiResolved.isNotBlank()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.5f)
                                    .clickable {
                                        zoomImageUrl = fotoKtpSuamiResolved
                                        zoomImageTitle = "Foto KTP Suami"
                                        showZoomDialog = true
                                    },
                                shape = RoundedCornerShape(8.dp),
                                elevation = CardDefaults.cardElevation(2.dp)
                            ) {
                                AsyncImage(
                                    model = fotoKtpSuamiResolved,
                                    contentDescription = "Foto KTP Suami",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else {
                            PhotoPlaceholderKoordinator("Tidak tersedia", isDark = isDark)
                        }
                    }

                    // Foto KTP Istri
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Foto KTP Istri",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = KoordinatorColors.getTextSecondary(isDark),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        if (fotoKtpIstriResolved.isNotBlank()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.5f)
                                    .clickable {
                                        zoomImageUrl = fotoKtpIstriResolved
                                        zoomImageTitle = "Foto KTP Istri"
                                        showZoomDialog = true
                                    },
                                shape = RoundedCornerShape(8.dp),
                                elevation = CardDefaults.cardElevation(2.dp)
                            ) {
                                AsyncImage(
                                    model = fotoKtpIstriResolved,
                                    contentDescription = "Foto KTP Istri",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else {
                            PhotoPlaceholderKoordinator("Tidak tersedia", isDark = isDark)
                        }
                    }
                }

                // Foto Nasabah
                Text(
                    "Foto Nasabah",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = KoordinatorColors.getTextSecondary(isDark),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (fotoNasabahResolved.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.6f)
                            .clickable {
                                zoomImageUrl = fotoNasabahResolved
                                zoomImageTitle = "Foto Nasabah"
                                showZoomDialog = true
                            },
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        AsyncImage(
                            model = fotoNasabahResolved,
                            contentDescription = "Foto Nasabah",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    PhotoPlaceholderKoordinator("Foto Nasabah tidak tersedia", isDark = isDark)
                }

                // Foto KTP (fallback jika tidak ada KTP Suami/Istri)
                if (fotoKtpResolved.isNotBlank() &&
                    fotoKtpSuamiResolved.isBlank() &&
                    fotoKtpIstriResolved.isBlank()) {
                    Text(
                        "Foto KTP",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = KoordinatorColors.getTextSecondary(isDark),
                        modifier = Modifier.padding(bottom = 4.dp, top = 8.dp)
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.6f)
                            .clickable {
                                zoomImageUrl = fotoKtpResolved
                                zoomImageTitle = "Foto KTP"
                                showZoomDialog = true
                            },
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        AsyncImage(
                            model = fotoKtpResolved,
                            contentDescription = "Foto KTP",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onReject,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoordinatorColors.danger
                )
            ) {
                Icon(Icons.Default.Cancel, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Tolak")
            }

            Button(
                onClick = onApprove,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoordinatorColors.success
                )
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Setujui")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // ✅ BARU: Dialog untuk zoom foto
    if (showZoomDialog) {
        ZoomableImageDialogKoordinator(
            imageUrl = zoomImageUrl,
            title = zoomImageTitle,
            onDismiss = { showZoomDialog = false }
        )
    }
}

// =========================================================================
// ✅ FUNGSI HELPER BARU
// =========================================================================

@Composable
private fun PhotoPlaceholderKoordinator(message: String, isDark: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = KoordinatorColors.getBackground(isDark)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ImageNotSupported,
                    contentDescription = null,
                    tint = KoordinatorColors.getTextMuted(isDark),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    message,
                    fontSize = 11.sp,
                    color = KoordinatorColors.getTextMuted(isDark),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ZoomableImageDialogKoordinator(
    isDark: Boolean = false,
    imageUrl: String,
    title: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += panChange
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup")
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .transformable(state = state)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                offset = Offset.Zero
                            }
                        )
                    }
            ) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentScale = ContentScale.Fit
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup")
            }
        }
    )
}


@Composable
private fun DetailSection(
    isDark: Boolean = false,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark)),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = KoordinatorColors.getTextPrimary(isDark)
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    isDark: Boolean = false,
    valueColor: Color = KoordinatorColors.getTextPrimary(isDark)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = KoordinatorColors.getTextSecondary(isDark)
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

// =============================================================================
// DIALOGS
// =============================================================================

@Composable
private fun KoordinatorApprovalNoteDialog(
    isDark: Boolean = false,
    pelanggan: Pelanggan,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    note: String,
    onNoteChange: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Setujui Pengajuan", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Anda akan menyetujui pengajuan pinjaman:",
                    color = KoordinatorColors.getTextSecondary(isDark)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = KoordinatorColors.getBackground(isDark)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            pelanggan.namaPanggilan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                            color = KoordinatorColors.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    label = { Text("Catatan (opsional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "⚠️ Persetujuan ini akan dicatat atas nama Anda sebagai Koordinator",
                    fontSize = 12.sp,
                    color = KoordinatorColors.warning
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(note) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoordinatorColors.success
                )
            ) {
                Text("Setujui sebagai Koordinator")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
private fun KoordinatorRejectDialog(
    isDark: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    reason: String,
    onReasonChange: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Tolak Pengajuan", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "⚠️ PERHATIAN: Penolakan dari Koordinator akan langsung menolak pengajuan ini meskipun Pimpinan sudah menyetujui.",
                    color = KoordinatorColors.danger,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    label = { Text("Alasan Penolakan *") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    isError = reason.isBlank()
                )

                if (reason.isBlank()) {
                    Text(
                        "Alasan penolakan wajib diisi",
                        color = KoordinatorColors.danger,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (reason.isNotBlank()) onConfirm(reason) },
                enabled = reason.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoordinatorColors.danger
                )
            ) {
                Text("Tolak Pengajuan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
private fun KoordinatorApprovalWithAmountDialog(
    isDark: Boolean = false,
    pelanggan: Pelanggan,
    onConfirm: (disetujuiAmount: Int, tenorDisetujui: Int, catatan: String) -> Unit,
    onDismiss: () -> Unit
) {
    var disetujuiAmount by remember { mutableStateOf(pelanggan.besarPinjaman.toString()) }
    var tenorDisetujui by remember { mutableStateOf(pelanggan.tenor.toString()) }
    var catatan by remember { mutableStateOf("") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Setujui dengan Penyesuaian", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Info pengajuan original
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = KoordinatorColors.getBackground(isDark)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Pengajuan Awal:", fontSize = 12.sp, color = KoordinatorColors.getTextMuted(isDark))
                        Text(
                            pelanggan.namaPanggilan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Rp ${formatRupiah(pelanggan.besarPinjaman)} • ${pelanggan.tenor} hari",
                            color = KoordinatorColors.getTextSecondary(isDark)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Input Jumlah Disetujui
                OutlinedTextField(
                    value = disetujuiAmount,
                    onValueChange = { disetujuiAmount = it.filter { c -> c.isDigit() } },
                    label = { Text("Jumlah Disetujui (Rp)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Input Tenor Disetujui
                OutlinedTextField(
                    value = tenorDisetujui,
                    onValueChange = { tenorDisetujui = it.filter { c -> c.isDigit() } },
                    label = { Text("Tenor Disetujui (hari)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Input Catatan
                OutlinedTextField(
                    value = catatan,
                    onValueChange = { catatan = it },
                    label = { Text("Catatan (opsional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                // Tampilkan selisih jika ada perubahan
                val amountInt = disetujuiAmount.toIntOrNull() ?: 0
                val tenorInt = tenorDisetujui.toIntOrNull() ?: 0

                if (amountInt != pelanggan.besarPinjaman || tenorInt != pelanggan.tenor) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = KoordinatorColors.warning.copy(alpha = 0.1f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Perubahan:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = KoordinatorColors.warning
                            )
                            if (amountInt != pelanggan.besarPinjaman) {
                                Text(
                                    "Jumlah: Rp ${formatRupiah(pelanggan.besarPinjaman)} → Rp ${formatRupiah(amountInt)}",
                                    fontSize = 12.sp
                                )
                            }
                            if (tenorInt != pelanggan.tenor) {
                                Text(
                                    "Tenor: ${pelanggan.tenor} hari → $tenorInt hari",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = disetujuiAmount.toIntOrNull() ?: 0
                    val tenor = tenorDisetujui.toIntOrNull() ?: pelanggan.tenor
                    if (amount > 0 && tenor > 0) {
                        onConfirm(amount, tenor, catatan)
                    } else {
                        Toast.makeText(context, "Jumlah tidak valid", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = disetujuiAmount.isNotBlank() &&
                        (disetujuiAmount.toIntOrNull() ?: 0) > 0 &&
                        tenorDisetujui.isNotBlank() &&
                        (tenorDisetujui.toIntOrNull() ?: 0) > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoordinatorColors.success
                )
            ) {
                Text("Setujui dengan Penyesuaian")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KoordinatorSerahTerimaTabContent(
    isDark: Boolean = false,
    notifications: List<SerahTerimaNotification>,
    onItemClick: (SerahTerimaNotification) -> Unit,
    onRefresh: () -> Unit,
    onMarkComplete: (SerahTerimaNotification) -> Unit  // ← TAMBAH PARAMETER BARU
) {
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            onRefresh()
            delay(1000)
            pullRefreshState.endRefresh()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection)
    ) {
        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = null,
                        tint = KoordinatorColors.getTextMuted(isDark),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "Belum Ada Bukti Serah Terima",
                        style = MaterialTheme.typography.titleLarge,
                        color = KoordinatorColors.getTextMuted(isDark),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Foto serah terima uang akan muncul di sini\nsetelah pdl lapangan upload",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KoordinatorColors.getTextMuted(isDark),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Bukti Serah Terima Uang",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "${notifications.size} data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KoordinatorColors.getTextMuted(isDark),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(notifications) { notification ->
                    KoordinatorSerahTerimaCard(
                        isDark = isDark,  // ✅ TAMBAHKAN
                        notification = notification,
                        onClick = { onItemClick(notification) },
                        onMarkComplete = { onMarkComplete(notification) }
                    )
                }
            }
        }
    }
}

@Composable
private fun KoordinatorSerahTerimaCard(
    isDark: Boolean = false,
    notification: SerahTerimaNotification,
    onClick: () -> Unit,
    onMarkComplete: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    // Dialog konfirmasi
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Konfirmasi Selesai") },
            text = {
                Text("Tandai bukti serah terima \"${notification.pelangganNama}\" sebagai selesai?\n\nData akan dihapus dari daftar.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onMarkComplete()
                    }
                ) {
                    Text("Ya, Selesai", color = Color(0xFF4CAF50))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!notification.read) {
                if (isDark) Color(0xFF3D2451) else Color(0xFFF3E5F5)
            } else {
                KoordinatorColors.getCard(isDark)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Foto thumbnail
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFFE8F5E9), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (notification.fotoSerahTerimaUrl.isNotBlank()) {
                    AsyncImage(
                        model = notification.fotoSerahTerimaUrl,
                        contentDescription = "Foto Serah Terima",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notification.pelangganNama,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!notification.read) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF7C3AED), CircleShape)
                        )
                    }
                }

                Text(
                    "Pinjaman: Rp ${formatRupiah(notification.besarPinjaman)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium
                )

                Text(
                    "Diserahkan: ${notification.adminName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoordinatorColors.getTextMuted(isDark)
                )

                Text(
                    notification.tanggalSerahTerima,
                    style = MaterialTheme.typography.bodySmall,
                    color = KoordinatorColors.getTextMuted(isDark)
                )
            }

            // Tombol Selesai
            IconButton(
                onClick = { showConfirmDialog = true },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFE8F5E9), CircleShape)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Tandai Selesai",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun KoordinatorFinalisasiTabContent(
    isDark: Boolean = false,
    isLoading: Boolean,
    pendingFinal: List<Pelanggan>,
    onViewDetail: (Pelanggan) -> Unit,
    onFinalize: (Pelanggan) -> Unit,
    onFinalizeWithTarikTabungan: (Pelanggan) -> Unit  // ✅ TAMBAH
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (pendingFinal.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = KoordinatorColors.getTextMuted(isDark)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Tidak ada pengajuan yang perlu dikonfirmasi",
                    color = KoordinatorColors.getTextMuted(isDark),
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pendingFinal) { pelanggan ->
                KoordinatorFinalisasiCard(
                    pelanggan = pelanggan,
                    onViewDetail = { onViewDetail(pelanggan) },
                    onFinalize = { onFinalize(pelanggan) },
                    onFinalizeWithTarikTabungan = { onFinalizeWithTarikTabungan(pelanggan) }  // ✅ TAMBAH
                )
            }
        }
    }
}

@Composable
fun KoordinatorFinalisasiCard(
    isDark: Boolean = false,
    pelanggan: Pelanggan,
    onViewDetail: () -> Unit,
    onFinalize: () -> Unit,
    onFinalizeWithTarikTabungan: () -> Unit  // ✅ TAMBAH
) {
    val dualInfo = pelanggan.dualApprovalInfo
    val pengawasStatus = dualInfo?.pengawasApproval?.status ?: "pending"
    val isPengawasApproved = pengawasStatus == ApprovalStatus.APPROVED

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPengawasApproved) {
                if (isDark) Color(0xFF1E3A1E) else Color(0xFFE8F5E9)
            } else {
                if (isDark) Color(0xFF3A1E1E) else Color(0xFFFFEBEE)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pelanggan.namaPanggilan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isPengawasApproved) Color(0xFF4CAF50) else Color(0xFFF44336)
                ) {
                    Text(
                        text = if (isPengawasApproved) "Pengawas: Setuju" else "Pengawas: Tolak",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1976D2)
            )

            if (dualInfo?.pengawasApproval?.note?.isNotBlank() == true) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Catatan Pengawas: ${dualInfo.pengawasApproval.note}",
                    fontSize = 12.sp,
                    color = KoordinatorColors.getTextMuted(isDark)
                )
            }

            // ✅ TAMBAH: Catatan dari Pimpinan dan Koordinator juga perlu ditampilkan
            if (dualInfo?.pimpinanApproval?.note?.isNotBlank() == true) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Catatan Pimpinan: ${dualInfo.pimpinanApproval.note}",
                    fontSize = 12.sp,
                    color = KoordinatorColors.getTextMuted(isDark)
                )
            }

            if (dualInfo?.koordinatorApproval?.note?.isNotBlank() == true) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Catatan Koordinator: ${dualInfo.koordinatorApproval.note}",
                    fontSize = 12.sp,
                    color = KoordinatorColors.getTextMuted(isDark)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ✅ BARU: 3 Tombol - Detail, Tarik Tabungan, Konfirmasi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tombol Detail
                OutlinedButton(
                    onClick = onViewDetail,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Detail", fontSize = 12.sp)
                }

                // ✅ BARU: Tombol Tarik Tabungan (hanya muncul jika pengawas setuju)
                if (isPengawasApproved) {
                    OutlinedButton(
                        onClick = onFinalizeWithTarikTabungan,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF9800)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFFF9800))
                    ) {
                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tarik Tab", fontSize = 12.sp)
                    }
                }

                // Tombol Konfirmasi
                Button(
                    onClick = onFinalize,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981)
                    )
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Konfirmasi", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * =========================================================================
 * BARU: Dialog untuk Tarik Tabungan saat Finalisasi Koordinator
 * =========================================================================
 */
@Composable
fun KoordinatorFinalisasiTarikTabunganDialog(
    isDark: Boolean = false,
    pelanggan: Pelanggan,
    onConfirm: (tarikTabungan: Int, catatan: String) -> Unit,
    onDismiss: () -> Unit
) {
    var tarikTabunganAmount by remember { mutableStateOf("") }
    var catatan by remember { mutableStateOf("") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Tarik Tabungan - Finalisasi",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Info Nasabah
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            pelanggan.namaPanggilan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "Pinjaman: Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                            color = Color(0xFF1976D2),
                            fontSize = 14.sp
                        )
                        Text(
                            "Simpanan saat ini: Rp ${formatRupiah(pelanggan.simpanan)}",
                            fontSize = 13.sp,
                            color = KoordinatorColors.getTextMuted(isDark)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Catatan Pengawas (jika ada)
                val pengawasNote = pelanggan.dualApprovalInfo?.pengawasApproval?.note ?: ""
                if (pengawasNote.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "📝 Catatan Pengawas:",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = Color(0xFFE65100)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                pengawasNote,
                                fontSize = 13.sp,
                                color = Color(0xFF5D4037)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Input Nominal Tarik Tabungan
                OutlinedTextField(
                    value = if (tarikTabunganAmount.isNotBlank()) {
                        java.text.NumberFormat.getNumberInstance(java.util.Locale("in", "ID")).format(tarikTabunganAmount.toLongOrNull() ?: 0)
                    } else "",
                    onValueChange = { newValue ->
                        tarikTabunganAmount = newValue.filter { char -> char.isDigit() }
                    },
                    label = { Text("Nominal Tarik Tabungan") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        val tarikAmount = tarikTabunganAmount.toIntOrNull() ?: 0
                        val pinjaman = pelanggan.besarPinjaman
                        val adminFee = pinjaman * 5 / 100
                        val simpananFee = pinjaman * 5 / 100
                        val maxTarik = pinjaman - adminFee - simpananFee

                        when {
                            tarikAmount > maxTarik -> {
                                Text(
                                    "Maksimal tarik tabungan: Rp ${formatRupiah(maxTarik)}",
                                    color = Color(0xFFF44336)
                                )
                            }
                            tarikAmount > 0 -> {
                                val newTotalDiterima = pelanggan.totalDiterima - tarikAmount
                                Text(
                                    "Nasabah akan menerima: Rp ${formatRupiah(newTotalDiterima)}",
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Input Catatan
                OutlinedTextField(
                    value = catatan,
                    onValueChange = { catatan = it },
                    label = { Text("Catatan (opsional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    placeholder = {
                        Text("Contoh: Tarik tabungan sesuai permintaan pengawas...")
                    },
                    singleLine = false,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tarikAmount = tarikTabunganAmount.toIntOrNull() ?: 0
                    if (tarikAmount > 0) {
                        onConfirm(tarikAmount, catatan)
                    } else {
                        Toast.makeText(context, "Nominal tarik tabungan harus lebih dari 0", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = tarikTabunganAmount.isNotBlank() && (tarikTabunganAmount.toIntOrNull() ?: 0) > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9800)
                )
            ) {
                Text("Konfirmasi dengan Tarik Tabungan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

// =========================================================================
// CAIRKAN SIMPANAN TAB CONTENT
// =========================================================================

@Composable
private fun KoordinatorCairanSimpananTabContent(
    isDark: Boolean,
    pendingItems: List<PengajuanCairanSimpananItem>,
    onApprove: (PengajuanCairanSimpananItem) -> Unit,
    onReject: (PengajuanCairanSimpananItem) -> Unit
) {
    if (pendingItems.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = KoordinatorColors.getTextMuted(isDark)
                )
                Text(
                    "Tidak ada pengajuan pencairan simpanan",
                    fontSize = 16.sp,
                    color = KoordinatorColors.getTextMuted(isDark),
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(pendingItems) { item ->
                CairanSimpananItemCard(
                    item = item,
                    isDark = isDark,
                    onApprove = { onApprove(item) },
                    onReject = { onReject(item) }
                )
            }
        }
    }
}

@Composable
private fun CairanSimpananItemCard(
    item: PengajuanCairanSimpananItem,
    isDark: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = KoordinatorColors.getCard(isDark)
        ),
        border = BorderStroke(1.dp, KoordinatorColors.getBorder(isDark))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.pelangganNama,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = KoordinatorColors.getTextPrimary(isDark),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "MENUNGGU",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF59E0B),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Admin
            Text(
                text = "Diajukan oleh: ${item.adminName}",
                fontSize = 13.sp,
                color = KoordinatorColors.getTextMuted(isDark)
            )

            // Tanggal
            if (item.requestDate.isNotBlank()) {
                Text(
                    text = "Tanggal: ${item.requestDate}",
                    fontSize = 12.sp,
                    color = KoordinatorColors.getTextMuted(isDark)
                )
            }

            HorizontalDivider(color = KoordinatorColors.getBorder(isDark))

            // Jumlah
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Total Simpanan",
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextMuted(isDark)
                    )
                    Text(
                        "Rp ${formatRupiah(item.simpananTotal)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = KoordinatorColors.getTextPrimary(isDark)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Dicairkan (\u00bd)",
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextMuted(isDark)
                    )
                    Text(
                        "Rp ${formatRupiah(item.jumlahDicairkan)}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                }
            }

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF4444)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("Tolak", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Setujui", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}