package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Money
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.utils.formatRupiahInput
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Schedule
import com.example.koperasikitagodangulu.models.TenorChangeRequest
import com.example.koperasikitagodangulu.models.TenorChangeRequestStatus
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.DeleteSweep
import com.example.koperasikitagodangulu.models.DeletionRequest
import com.example.koperasikitagodangulu.models.DeletionRequestStatus
import com.example.koperasikitagodangulu.models.PaymentDeletionRequest
import com.example.koperasikitagodangulu.models.PaymentDeletionRequestStatus
import androidx.compose.runtime.SideEffect
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanApprovalScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel,
    initialTab: Int = 0
) {
    var showApprovalDialog by remember { mutableStateOf(false) }
    var approvalNote by remember { mutableStateOf("") }
    var showApprovalWithAmountDialog by remember { mutableStateOf(false) }
    var selectedPelangganForAmount by remember { mutableStateOf<Pelanggan?>(null) }

    // ✅ Gunakan StateFlow yang reactive
    val pendingApprovals = remember { viewModel.pendingApprovals }

    val serahTerimaNotifications by viewModel.serahTerimaNotifications.collectAsState()
    val unreadSerahTerimaCount by viewModel.unreadSerahTerimaCount.collectAsState()

    var selectedPelanggan by remember { mutableStateOf<Pelanggan?>(null) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var rejectReason by remember { mutableStateOf("") }
    val bottomSheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    // State untuk loading dan feedback
    var isLoading by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }

    val pendingPimpinanFinal by viewModel.pendingPimpinanFinal.collectAsState()

    // ✅ BARU: State untuk Tenor Change Requests
    val tenorChangeRequests by viewModel.tenorChangeRequests.collectAsState()
    val unreadTenorChangeCount by viewModel.unreadTenorChangeCount.collectAsState()

    var selectedTabIndex by remember { mutableStateOf(initialTab) }  // ✅ Gunakan initialTab
    val tabTitles = listOf("Pengajuan", "Finalisasi", "Serah Terima", "Tenor", "Penghapusan") // ✅ Tambah "Tenor"

    // ✅ BARU: Deletion Requests State
    val pimpinanDeletionRequests by viewModel.pimpinanDeletionRequests.collectAsState()
    val unreadPimpinanDeletionCount by viewModel.unreadPimpinanDeletionCount.collectAsState()

    // =========================================================================
    // ✅ BARU: Dark Mode State
    // =========================================================================
    val isDark by viewModel.isDarkMode
    // ✅ Set status bar & navigation bar sesuai tema dashboard
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    LaunchedEffect(Unit) {
        try {
            viewModel.currentUserCabang.value?.let { cabangId ->
                Log.d("ApprovalScreen", "🔄 Loading pending approvals for cabang: $cabangId")

                // ✅ Load pending approvals (dengan cache)
                viewModel.loadPendingApprovalsOptimized(cabangId)

                // ✅ Setup listener hanya jika belum aktif
                if (!viewModel.isPendingApprovalListenerActive()) {
                    viewModel.setupRealtimePendingApprovals(cabangId)
                }
            }

            // ✅ TAMBAHAN: Mark semua notifikasi pengajuan sebagai sudah dibaca
            // Ini akan menghilangkan badge notifikasi saat user masuk ke halaman approval
            viewModel.markAllPengajuanNotificationsAsRead()

            viewModel.loadSerahTerimaNotifications()

            viewModel.loadPendingPimpinanFinal()

            // ✅ BARU: Load tenor change requests untuk Pimpinan
            viewModel.loadTenorChangeRequests()

            // ✅ BARU: Load deletion requests untuk Pimpinan
            viewModel.loadPimpinanDeletionRequests()
        } catch (e: Exception) {
            Log.e("ApprovalScreen", "Error loading data: ${e.message}")
        }
    }

    // Handle operation result
    LaunchedEffect(operationMessage) {
        operationMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.currentUserCabang.value?.let { cabangId ->
                viewModel.loadPendingApprovalsOptimized(cabangId)
            }
            operationMessage = null
        }
    }

    Scaffold(
        topBar = {
            PimpinanTopBar(
                title = "Approval",
                navController = navController,
                viewModel = viewModel
            )
        },
        bottomBar = {
            PimpinanBottomNavigation(navController, "pimpinan_approvals", viewModel) // ✅ UBAH: Tambah viewModel
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // ✅ ScrollableTabRow — teks tidak terpotong, titik merah sebagai indikator
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = PimpinanColors.getCard(isDark),
                edgePadding = 0.dp
            ) {
                // ── Tab 0: Pengajuan ──
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Pengajuan") },
                    icon = {
                        Box {
                            Icon(Icons.Default.Pending, contentDescription = null)
                            if (pendingApprovals.isNotEmpty()) {
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

                // ── Tab 1: Finalisasi ──
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        viewModel.loadPendingPimpinanFinal()
                    },
                    text = { Text("Finalisasi") },
                    icon = {
                        Box {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            if (pendingPimpinanFinal.isNotEmpty()) {
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

                // ── Tab 2: Serah Terima ──
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = {
                        selectedTabIndex = 2
                        viewModel.markAllSerahTerimaAsRead()
                    },
                    text = { Text("Serah Terima") },
                    icon = {
                        Box {
                            Icon(Icons.Default.Verified, contentDescription = null)
                            if (unreadSerahTerimaCount > 0) {
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

                // ── Tab 3: Tenor ──
                Tab(
                    selected = selectedTabIndex == 3,
                    onClick = {
                        selectedTabIndex = 3
                        viewModel.markAllTenorChangeAsRead()
                    },
                    text = { Text("Tenor") },
                    icon = {
                        Box {
                            Icon(Icons.Default.Schedule, contentDescription = null)
                            if (tenorChangeRequests.isNotEmpty()) {
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

                // ── Tab 4: Penghapusan ──
                Tab(
                    selected = selectedTabIndex == 4,
                    onClick = {
                        selectedTabIndex = 4
                        viewModel.markAllPimpinanDeletionAsRead()
                    },
                    text = { Text("Penghapusan") },
                    icon = {
                        Box {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            if (pimpinanDeletionRequests.isNotEmpty()) {
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

            when (selectedTabIndex) {
                0 -> {
                    // TAB PENGAJUAN (Phase 1 - Review Awal)
                    PengajuanTabContent(
                        isLoading = isLoading,
                        pendingApprovals = pendingApprovals,
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
                    // ✅ BARU: TAB FINALISASI (Phase 3 - Setelah Pengawas Review)
                    FinalisasiTabContent(
                        isLoading = isLoading,
                        pendingFinal = pendingPimpinanFinal,
                        onViewDetail = { pelanggan ->
                            selectedPelanggan = pelanggan
                            showDetailSheet = true
                        },
                        onFinalize = { pelanggan ->
                            // Konfirmasi finalisasi
                            isLoading = true
                            viewModel.finalizePimpinanApproval(
                                pelangganId = pelanggan.id,
                                catatan = "Dikonfirmasi oleh Pimpinan",
                                onSuccess = {
                                    isLoading = false
                                    operationMessage = "Pengajuan berhasil difinalisasi"
                                },
                                onFailure = { e ->
                                    isLoading = false
                                    operationMessage = "Gagal finalisasi: ${e.message}"
                                }
                            )
                        }
                    )
                }
                2 -> {
                    // Tab Serah Terima - dengan refresh
                    SerahTerimaTabContent(
                        notifications = serahTerimaNotifications,
                        onItemClick = { notification ->
                            navController.navigate("detail_serah_terima/${notification.id}")
                        },
                        onRefresh = {
                            viewModel.refreshSerahTerimaNotifications()
                        },
                        onMarkComplete = { notification ->
                            viewModel.deleteSerahTerimaNotification(
                                notificationId = notification.id,
                                onSuccess = {
                                    // Optional: tampilkan toast
                                }
                            )
                        }
                    )
                }
                3 -> {
                    // ✅ BARU: Tab Perubahan Tenor
                    TenorChangeTabContent(
                        tenorChangeRequests = tenorChangeRequests,
                        onApprove = { request ->
                            viewModel.approveTenorChange(
                                request = request,
                                onSuccess = {
                                    Toast.makeText(
                                        context,
                                        "Tenor ${request.pelangganNama} berhasil diubah menjadi ${request.tenorBaru} hari",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                onFailure = { e ->
                                    Toast.makeText(
                                        context,
                                        "Gagal: ${e.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        },
                        onReject = { request, alasan ->
                            viewModel.rejectTenorChange(
                                request = request,
                                alasanPenolakan = alasan,
                                onSuccess = {
                                    Toast.makeText(
                                        context,
                                        "Perubahan tenor ${request.pelangganNama} ditolak",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onFailure = { e ->
                                    Toast.makeText(
                                        context,
                                        "Gagal: ${e.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        },
                        onRefresh = {
                            viewModel.loadTenorChangeRequests()
                        }
                    )
                }
                4 -> {
                    // ✅ Tab Penghapusan dengan 2 section
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Section 1: Penghapusan Nasabah
                        item {
                            Text(
                                "Penghapusan Nasabah",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color.White else Color.Black
                            )
                        }

                        if (pimpinanDeletionRequests.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isDark) Color(0xFF1E293B) else Color.White
                                    )
                                ) {
                                    Text(
                                        "Tidak ada pengajuan penghapusan nasabah",
                                        modifier = Modifier.padding(16.dp),
                                        color = Color.Gray
                                    )
                                }
                            }
                        } else {
                            items(pimpinanDeletionRequests) { request ->
                                PimpinanDeletionRequestCard(
                                    request = request,
                                    onApprove = {
                                        viewModel.approveDeletionRequestAsPimpinan(
                                            requestId = request.id,
                                            onSuccess = {
                                                Toast.makeText(context, "Penghapusan nasabah ${request.pelangganNama} disetujui", Toast.LENGTH_SHORT).show()
                                                viewModel.loadPimpinanDeletionRequests()
                                            },
                                            onFailure = { e ->
                                                Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    },
                                    onReject = { alasan ->
                                        viewModel.rejectDeletionRequestAsPimpinan(
                                            requestId = request.id,
                                            catatanPimpinan = alasan,
                                            onSuccess = {
                                                Toast.makeText(context, "Penghapusan nasabah ${request.pelangganNama} ditolak", Toast.LENGTH_SHORT).show()
                                                viewModel.loadPimpinanDeletionRequests()
                                            },
                                            onFailure = { e ->
                                                Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                )
                            }
                        }

                    }
                }
            }
        }

        // Dialog-dialog existing (tidak berubah)
        if (showApprovalWithAmountDialog && selectedPelangganForAmount != null) {
            val pelangganForAmount = selectedPelangganForAmount ?: return@Scaffold
            ApprovalWithAmountDialog(
                pelanggan = pelangganForAmount,
                onConfirm = { disetujuiAmount, tenorDisetujui, catatan, tarikTabungan ->
                    isLoading = true
                    viewModel.approvePengajuan(
                        pelangganForAmount.id,
                        catatan = catatan,
                        besarPinjamanDisetujui = disetujuiAmount,
                        tenorDisetujui = tenorDisetujui,
                        tarikTabungan = tarikTabungan,
                        catatanPerubahanPinjaman = buildString {
                            append("Disetujui dengan penyesuaian jumlah dari ")
                            append("Rp ${formatRupiah(pelangganForAmount.besarPinjaman)} menjadi ")
                            append("Rp ${formatRupiah(disetujuiAmount)} dan tenor dari ${pelangganForAmount.tenor} hari menjadi ${tenorDisetujui} hari")
                            if (tarikTabungan > 0) {
                                append(". Tarik tabungan: Rp ${formatRupiah(tarikTabungan)}")
                            }
                        },
                        cabangId = viewModel.currentUserCabang.value,
                        onSuccess = {
                            isLoading = false
                            operationMessage = "Pengajuan berhasil disetujui dengan penyesuaian"
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

        if (showDetailSheet && selectedPelanggan != null) {
            ModalBottomSheet(
                onDismissRequest = { showDetailSheet = false },
                sheetState = bottomSheetState,
                modifier = Modifier.fillMaxSize()
            ) {
                selectedPelanggan?.let { pelanggan ->
                    DetailPengajuanSheet(
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

        if (showRejectDialog && selectedPelanggan != null) {
            RejectDialog(
                onConfirm = { reason ->
                    isLoading = true
                    selectedPelanggan?.let { pelanggan ->
                        viewModel.rejectPengajuan(
                            pelanggan.id,
                            reason,
                            onSuccess = {
                                isLoading = false
                                operationMessage = "Pengajuan berhasil ditolak"
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

        if (showApprovalDialog && selectedPelanggan != null) {
            val pelangganForApproval = selectedPelanggan ?: return@Scaffold
            ApprovalNoteDialog(
                onConfirm = { catatan ->
                    isLoading = true
                    viewModel.approvePengajuan(
                        pelangganForApproval.id,
                        catatan = catatan,
                        cabangId = viewModel.currentUserCabang.value,
                        onSuccess = {
                            isLoading = false
                            operationMessage = "Pengajuan berhasil disetujui"
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

@Composable
fun PengajuanTabContent(
    isLoading: Boolean,
    pendingApprovals: List<Pelanggan>,
    onViewDetail: (Pelanggan) -> Unit,
    onApprove: (Pelanggan) -> Unit,
    onReject: (Pelanggan) -> Unit,
    onAdjustAmount: (Pelanggan) -> Unit
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Memproses...")
            }
        }
    } else if (pendingApprovals.isEmpty()) {
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
                    contentDescription = "Tidak ada pengajuan",
                    tint = Color.Gray,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    "Tidak Ada Pengajuan Pinjaman",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Semua pengajuan pinjaman telah diproses",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            item {
                Text(
                    "Pengajuan Menunggu Approval",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    "${pendingApprovals.size} pengajuan perlu ditinjau",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            items(pendingApprovals) { pelanggan ->
                ApprovalCard(
                    pelanggan = pelanggan,
                    onViewDetail = { onViewDetail(pelanggan) },
                    onApprove = { onApprove(pelanggan) },
                    onReject = { onReject(pelanggan) },
                    onAdjustAmount = { onAdjustAmount(pelanggan) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}


// =============================================================================
// LANGKAH 4: TAMBAH FUNGSI BARU - SerahTerimaTabContent
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerahTerimaTabContent(
    notifications: List<SerahTerimaNotification>,
    onItemClick: (SerahTerimaNotification) -> Unit,
    onRefresh: () -> Unit,
    onMarkComplete: (SerahTerimaNotification) -> Unit  // ← TAMBAH PARAMETER BARU
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    // Handle refresh
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
            // Empty state...
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
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "Belum Ada Bukti Serah Terima",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Tarik ke bawah untuk refresh",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                item {
                    Text(
                        "Bukti Serah Terima Uang",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "${notifications.size} data (maks 50 terbaru)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                items(notifications) { notification ->
                    SerahTerimaNotificationCard(
                        notification = notification,
                        onClick = { onItemClick(notification) },
                        onMarkComplete = { onMarkComplete(notification) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

// =============================================================================
// LANGKAH 5: TAMBAH FUNGSI BARU - SerahTerimaNotificationCard
// =============================================================================

@Composable
fun SerahTerimaNotificationCard(
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
            containerColor = if (!notification.read) Color(0xFFE3F2FD) else Color.White
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
                                .background(Color(0xFF1976D2), CircleShape)
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
                    "Diserahkan oleh: ${notification.adminName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Text(
                    notification.tanggalSerahTerima,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
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
fun ApprovalCard(
    pelanggan: Pelanggan,
    onViewDetail: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onAdjustAmount: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewDetail),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        // Badge untuk status nasabah
                        Box(
                            modifier = Modifier
                                .background(
                                    // pinjamanKe sudah di-increment saat pengajuan
                                    color = if (pelanggan.pinjamanKe <= 1) Color(0xFF4CAF50) else Color(0xFF2196F3),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (pelanggan.pinjamanKe <= 1) "NASABAH BARU" else "PINJAMAN KE-${pelanggan.pinjamanKe}",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = pelanggan.adminName.ifBlank { pelanggan.adminEmail.ifBlank { "Admin" } },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
//                    Text(
//                        text = "Diajukan oleh: ${pelanggan.adminName ?: "Admin"}",
//                        style = MaterialTheme.typography.bodySmall,
//                        color = Color.Gray,
//                        modifier = Modifier.padding(bottom = 4.dp)
//                    )

                    Text(
                        pelanggan.namaPanggilan,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        pelanggan.namaKtp,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Text(
                        "Diajukan: Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2196F3),
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    Icons.Default.Pending,
                    contentDescription = "Menunggu Approval",
                    tint = Color(0xFFFF9800)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info Pinjaman
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem(
                    icon = Icons.Default.Money,
                    label = "Pinjaman",
                    value = formatRupiah(pelanggan.besarPinjaman)
                )
                InfoItem(
                    icon = Icons.Default.CalendarToday,
                    label = "Tenor",
                    value = "${pelanggan.tenor} hari"
                )
                InfoItem(
                    icon = Icons.Default.Business,
                    label = "Usaha",
                    value = pelanggan.jenisUsaha
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onViewDetail,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = "Detail",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Detail")
                }

                Button(
                    onClick = onAdjustAmount,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Setujui dengan Penyesuaian",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Penyesuaian")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Setujui",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Setujui")
                }

                Button(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF44336)
                    )
                ) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = "Tolak",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tolak")
                }
            }
        }
    }
}

@Composable
fun InfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
    }
}

@Composable
fun DetailPengajuanSheet(
    pelanggan: Pelanggan,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
    viewModel: PelangganViewModel
) {
    val scrollState = rememberScrollState()
    val pinjamanInfo = viewModel.getPinjamanInfoForApproval(pelanggan)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                // TAMBAHKAN: Status Nasabah di header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (pelanggan.pinjamanKe == 1) Color(0xFF4CAF50) else Color(
                                    0xFF2196F3
                                ),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (pelanggan.pinjamanKe == 1) "NASABAH BARU" else "PINJAMAN KE-${pelanggan.pinjamanKe}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Text(
                "Detail Pengajuan",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Cancel, contentDescription = "Tutup")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Foto KTP",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Tampilkan foto KTP berdasarkan tipe pinjaman
        when {
            // Untuk pinjaman di bawah 3jt (single KTP + Foto Nasabah)
            pelanggan.tipePinjaman == "dibawah_3jt" || pelanggan.besarPinjaman < 3000000 -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Foto KTP
                    if (pelanggan.fotoKtpUrl.isNotBlank()) {
                        KtpPhotoSection(
                            title = "Foto KTP",
                            imageUrl = pelanggan.fotoKtpUrl
                        )
                    } else {
                        PhotoPlaceholder("Foto KTP tidak tersedia")
                    }

                    // ✅ PERBAIKAN: Tampilkan Foto Nasabah juga untuk < 3jt
                    if (pelanggan.fotoNasabahUrl.isNotBlank()) {
                        KtpPhotoSection(
                            title = "Foto Nasabah",
                            imageUrl = pelanggan.fotoNasabahUrl
                        )
                    } else {
                        PhotoPlaceholder("Foto Nasabah tidak tersedia")
                    }
                }
            }
            // Untuk pinjaman di atas 3jt (KTP Suami & Istri + Foto Nasabah + Foto Serah Terima)
            pelanggan.tipePinjaman == "diatas_3jt" || pelanggan.besarPinjaman >= 3000000 -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Row untuk KTP Suami & Istri
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Foto KTP Suami
                        Column(modifier = Modifier.weight(1f)) {
                            if (pelanggan.fotoKtpSuamiUrl.isNotBlank()) {
                                KtpPhotoSection(
                                    title = "Foto KTP Suami",
                                    imageUrl = pelanggan.fotoKtpSuamiUrl
                                )
                            } else {
                                PhotoPlaceholder("Foto KTP Suami tidak tersedia")
                            }
                        }

                        // Foto KTP Istri
                        Column(modifier = Modifier.weight(1f)) {
                            if (pelanggan.fotoKtpIstriUrl.isNotBlank()) {
                                KtpPhotoSection(
                                    title = "Foto KTP Istri",
                                    imageUrl = pelanggan.fotoKtpIstriUrl
                                )
                            } else {
                                PhotoPlaceholder("Foto KTP Istri tidak tersedia")
                            }
                        }
                    }

                    // Foto Nasabah
                    if (pelanggan.fotoNasabahUrl.isNotBlank()) {
                        KtpPhotoSection(
                            title = "Foto Nasabah",
                            imageUrl = pelanggan.fotoNasabahUrl
                        )
                    } else {
                        PhotoPlaceholder("Foto Nasabah tidak tersedia")
                    }

                    // =====================================================
                    // ✅ BARU: FOTO SERAH TERIMA UANG
                    // =====================================================
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Status Serah Terima Uang",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    when {
                        // ✅ Sudah ada foto serah terima - HIJAU
                        pelanggan.fotoSerahTerimaUrl.isNotBlank() -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFE8F5E9)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                "✅ Serah Terima Selesai",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2E7D32)
                                            )
                                            if (pelanggan.tanggalSerahTerima.isNotBlank()) {
                                                Text(
                                                    "Diserahkan: ${pelanggan.tanggalSerahTerima}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color(0xFF388E3C)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Tampilkan foto serah terima
                            KtpPhotoSection(
                                title = "Foto Bukti Serah Terima Uang",
                                imageUrl = pelanggan.fotoSerahTerimaUrl
                            )
                        }

                        // ⏳ Pending upload (offline) - ORANGE
                        pelanggan.pendingFotoSerahTerimaUri.isNotBlank() -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFFFF3E0)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = Color(0xFFFF9800),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "⏳ Foto Menunggu Upload",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFE65100)
                                        )
                                        Text(
                                            "PDL sudah mengambil foto, menunggu koneksi internet untuk upload",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFFF9800)
                                        )
                                        if (pelanggan.tanggalSerahTerima.isNotBlank()) {
                                            Text(
                                                "Waktu pengambilan: ${pelanggan.tanggalSerahTerima}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFFF9800)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ⚠️ Belum ada foto serah terima (hanya jika sudah Aktif) - MERAH
                        pelanggan.status == "Aktif" -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFFFEBEE)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFF44336),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "⚠️ Belum Ada Bukti Serah Terima",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFC62828)
                                        )
                                        Text(
                                            "PDL belum mengupload foto serah terima uang kepada nasabah",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFE57373)
                                        )
                                    }
                                }
                            }
                        }

                        // Status Menunggu Approval - tidak perlu tampilkan status serah terima
                        else -> {
                            // Tidak tampilkan apa-apa karena pinjaman belum disetujui
                        }
                    }
                    // =====================================================
                    // END FOTO SERAH TERIMA
                    // =====================================================
                }
            }
            else -> {
                // Fallback - juga tampilkan foto nasabah
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (pelanggan.fotoKtpUrl.isNotBlank()) {
                        KtpPhotoSection(
                            title = "Foto KTP",
                            imageUrl = pelanggan.fotoKtpUrl
                        )
                    } else {
                        PhotoPlaceholder("Foto KTP tidak tersedia")
                    }

                    // ✅ PERBAIKAN: Tampilkan Foto Nasabah juga di fallback
                    if (pelanggan.fotoNasabahUrl.isNotBlank()) {
                        KtpPhotoSection(
                            title = "Foto Nasabah",
                            imageUrl = pelanggan.fotoNasabahUrl
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Section: Data Pribadi (existing code)
        Text(
            "Data Nasabah",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        DetailItem(Icons.Default.Person, "Nama Lengkap", pelanggan.namaKtp)
        DetailItem(Icons.Default.Person, "Nama Panggilan", pelanggan.namaPanggilan)
        DetailItem(Icons.Default.Assignment, "NIK", pelanggan.nik)
        DetailItem(Icons.Default.Phone, "No. HP", pelanggan.noHp)
        DetailItem(Icons.Default.Badge, "No. Anggota", pelanggan.nomorAnggota)

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Alamat
        Text(
            "Alamat",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        DetailItem(Icons.Default.Home, "Alamat KTP", pelanggan.alamatKtp)
        DetailItem(Icons.Default.Home, "Alamat Rumah", pelanggan.alamatRumah)
        DetailItem(Icons.Default.Business, "Wilayah", pelanggan.wilayah)

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Pinjaman
        Text(
            "Detail Pinjaman",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        DetailItem(
            icon = Icons.Default.Assignment,
            label = "Jenis Nasabah",
            // pinjamanKe sudah di-increment saat pengajuan, jadi > 1 berarti top-up
            value = if (pelanggan.pinjamanKe <= 1) "Nasabah Baru" else "Nasabah Lama (Pinjaman Ke-${pelanggan.pinjamanKe})"
        )
        DetailItem(Icons.Default.Money, "Jumlah Pinjaman", formatRupiah(pelanggan.besarPinjaman))
        DetailItem(Icons.Default.CalendarToday, "Tenor", "${pelanggan.tenor} hari")
        DetailItem(Icons.Default.Money, "Total Pelunasan", formatRupiah(pelanggan.totalPelunasan))
        DetailItem(Icons.Default.Business, "Jenis Usaha", pelanggan.jenisUsaha)
        DetailItem(Icons.Default.CalendarToday, "Tanggal Pengajuan", pelanggan.tanggalPengajuan)
        DetailItem(Icons.Default.AttachMoney, "Total Potongan (10%)", formatRupiah(pinjamanInfo.jasaPinjaman))
        if (pelanggan.pinjamanKe >= 2) {
            // Sisa Utang Lama — ditampilkan selalu untuk lanjut pinjaman
            // Hijau = 0 (nasabah sudah lunas sebelum lanjut pinjaman)
            // Orange = > 0 (nasabah masih punya sisa utang)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AccountBalance,
                    contentDescription = "Sisa Utang Lama",
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Sisa Utang Lama",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Text(
                        formatRupiah(pinjamanInfo.sisaUtangLama),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (pinjamanInfo.sisaUtangLama > 0) Color(0xFFE65100) else Color(0xFF4CAF50)
                    )
                    Text(
                        if (pinjamanInfo.sisaUtangLama > 0) "Nasabah masih memiliki sisa utang" else "Nasabah sudah lunas",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pinjamanInfo.sisaUtangLama > 0) Color(0xFFE65100) else Color(0xFF4CAF50)
                    )
                }
            }
            DetailItem(Icons.Default.Savings, "Total Simpanan", formatRupiah(pinjamanInfo.totalSimpanan))
        }
        DetailItem(Icons.Default.AccountBalanceWallet, "Jumlah Diberikan", formatRupiah(pelanggan.totalDiterima))

        Spacer(modifier = Modifier.height(24.dp))

        // Section: Info Approval (jika sudah ada)
        if (pelanggan.catatanApproval.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Info Approval",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
                color = Color(0xFF4CAF50)
            )
            DetailItem(Icons.Default.Note, "Catatan Approval", pelanggan.catatanApproval)
            if (pelanggan.tanggalApproval.isNotBlank()) {
                DetailItem(Icons.Default.DateRange, "Tanggal Approval", pelanggan.tanggalApproval)
            }
            if (pelanggan.disetujuiOleh.isNotBlank()) {
                DetailItem(Icons.Default.Person, "Disetujui Oleh", pelanggan.disetujuiOleh)
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
                    containerColor = Color(0xFFF44336)
                )
            ) {
                Text("Tolak Pengajuan")
            }

            Button(
                onClick = {
                    // Sekarang panggil onApprove yang akan trigger dialog
                    onApprove()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("Setujui Pinjaman")
            }
        }
    }
}

@Composable
fun DetailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun RejectDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    reason: String,
    onReasonChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    "Alasan Penolakan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Berikan alasan penolakan pengajuan pinjaman:",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = reason,
                    onValueChange = onReasonChange,
                    placeholder = { Text("Contoh: Data tidak lengkap, kemampuan bayar kurang, dll.") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray
                        )
                    ) {
                        Text("Batal")
                    }

                    Button(
                        onClick = {
                            onConfirm(reason)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF44336)
                        ),
                        enabled = reason.isNotBlank()
                    ) {
                        Text("Tolak")
                    }
                }
            }
        }
    }
}

@Composable
fun ApprovalNoteDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    note: String,
    onNoteChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    "Catatan Approval",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Berikan catatan tambahan untuk approval ini (opsional):",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = note,
                    onValueChange = onNoteChange,
                    placeholder = { Text("Contoh: Nasabah memiliki prospek usaha yang bagus, perlu monitoring intensif, dll.") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    singleLine = false,
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Gray
                        )
                    ) {
                        Text("Batal")
                    }

                    Button(
                        onClick = { onConfirm(note) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Text("Setujui")
                    }
                }
            }
        }
    }
}

@Composable
fun ApprovalWithAmountDialog(
    pelanggan: Pelanggan,
    onConfirm: (disetujuiAmount: Int, tenorDisetujui : Int, catatan: String, tarikTabungan: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var disetujuiAmount by remember {
        mutableStateOf(pelanggan.besarPinjaman.toString())
    }
    var tenorDisetujui by remember {
        mutableStateOf(pelanggan.tenor.toString()) // ✅ TAMBAH STATE UNTUK TENOR
    }
    var catatan by remember { mutableStateOf("") }

    // ✅ State untuk Tarik Tabungan
    var tarikTabunganEnabled by remember { mutableStateOf(false) }
    var tarikTabunganAmount by remember { mutableStateOf("") }

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Approval dengan Penyesuaian Pinjaman",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            // ✅ PERBAIKAN: imePadding() di PARENT COLUMN
            Column(
                modifier = Modifier
//                    .imePadding()
                    .verticalScroll(scrollState)
            ) {
                // Info pinjaman diajukan
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Pinjaman Diajukan:",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Text(
                            "Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                            color = Color(0xFF1976D2),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "Tenor Diajukan: ${pelanggan.tenor} hari",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Input jumlah disetujui
                OutlinedTextField(
                    value = formatRupiahInput(disetujuiAmount),
                    onValueChange = { newValue ->
                        // Filter hanya angka
                        disetujuiAmount = newValue.filter { char -> char.isDigit() }
                    },
                    label = { Text("Jumlah yang Disetujui") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        val amount = disetujuiAmount.toIntOrNull() ?: 0
                        if (amount > pelanggan.besarPinjaman) {
                            Text(
                                "Jumlah disetujui lebih besar dari yang diajukan",
                                color = Color(0xFFF44336)
                            )
                        } else if (amount < pelanggan.besarPinjaman) {
                            Text(
                                "Pinjaman akan dikurangi",
                                color = Color(0xFFFF9800)
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                var expanded by remember { mutableStateOf(false) }
                val tenorOptions = listOf(24, 28, 30, 36, 40)

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = "$tenorDisetujui hari",
                        onValueChange = { }, // Tidak boleh diubah manual
                        label = { Text("Tenor yang Disetujui") },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        trailingIcon = {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Pilih Tenor"
                            )
                        }
                    )

                    // Transparent clickable area untuk trigger dropdown
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0f)
                            .clickable { expanded = true }
                    )
                }

                // Dropdown Menu untuk Tenor
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tenorOptions.forEach { tenor ->
                        DropdownMenuItem(
                            text = { Text("$tenor hari") },
                            onClick = {
                                tenorDisetujui = tenor.toString()
                                expanded = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column {
                    Text(
                        "Catatan Penyesuaian:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = catatan,
                        onValueChange = { catatan = it },
                        label = { Text("Tulis catatan disini...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
//                            .imePadding(), // ✅ Tambahkan imePadding di sini
                        placeholder = {
                            Text("Contoh: Disetujui sebagian karena kondisi usaha...")
                        },
                        singleLine = false,
                        maxLines = 4
                    )
                }

                // ✅ FITUR BARU: Tarik Tabungan
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (tarikTabunganEnabled) Color(0xFFFFF3E0) else Color(0xFFF5F5F5)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Tarik Tabungan",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "Aktifkan jika ada penarikan tabungan dari pinjaman",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(
                                checked = tarikTabunganEnabled,
                                onCheckedChange = { enabled ->
                                    tarikTabunganEnabled = enabled
                                    if (!enabled) {
                                        tarikTabunganAmount = ""
                                    }
                                }
                            )
                        }

                        // Input nominal muncul ketika switch diaktifkan
                        if (tarikTabunganEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = formatRupiahInput(tarikTabunganAmount),
                                onValueChange = { newValue ->
                                    tarikTabunganAmount = newValue.filter { char -> char.isDigit() }
                                },
                                label = { Text("Nominal Tarik Tabungan") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                supportingText = {
                                    val tarikAmount = tarikTabunganAmount.toIntOrNull() ?: 0
                                    val pinjaman = disetujuiAmount.toIntOrNull() ?: 0
                                    val adminFee = pinjaman * 5 / 100
                                    val simpananFee = pinjaman * 5 / 100
                                    val maxTarik = pinjaman - adminFee - simpananFee

                                    if (tarikAmount > maxTarik) {
                                        Text(
                                            "Maksimal tarik tabungan: Rp ${formatRupiah(maxTarik)}",
                                            color = Color(0xFFF44336)
                                        )
                                    } else if (tarikAmount > 0) {
                                        val totalDiterima = pinjaman - adminFee - simpananFee - tarikAmount
                                        Text(
                                            "Nasabah akan menerima: Rp ${formatRupiah(totalDiterima)}",
                                            color = Color(0xFF4CAF50)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                // Tampilkan selisih
                val amountDisetujui = disetujuiAmount.toIntOrNull() ?: 0
                val selisih = pelanggan.besarPinjaman - amountDisetujui
                if (selisih != 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Selisih: Rp ${formatRupiah(selisih)}",
                        color = if (selisih > 0) Color(0xFFF57C00) else Color(0xFF4CAF50),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = disetujuiAmount.toIntOrNull() ?: 0
                    val tenor = tenorDisetujui.toIntOrNull() ?: pelanggan.tenor
                    val tarikTabungan = if (tarikTabunganEnabled) {
                        tarikTabunganAmount.toIntOrNull() ?: 0
                    } else {
                        0
                    }
                    if (amount > 0 && tenor > 0) {
                        // ✅ Tambahkan parameter tarikTabungan
                        onConfirm(amount, tenor, catatan, tarikTabungan)
                    } else {
                        Toast.makeText(context, "Jumlah tidak valid", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = disetujuiAmount.isNotBlank() &&
                        (disetujuiAmount.toIntOrNull() ?: 0) > 0 &&
                        tenorDisetujui.isNotBlank() &&
                        (tenorDisetujui.toIntOrNull() ?: 0) > 0 &&
                        // ✅ Validasi tarik tabungan jika diaktifkan
                        (!tarikTabunganEnabled || (tarikTabunganAmount.toIntOrNull() ?: 0) > 0)
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

@Composable
fun KtpPhotoSection(
    title: String,
    imageUrl: String
) {
    var showZoomDialog by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, rotationChange ->
        scale = (scale * zoomChange).coerceIn(1f, 5f) // Batas zoom 1x - 5x
        offset += panChange
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .clickable {
                    showZoomDialog = true
                    Log.d("KtpPhoto", "Foto diklik: $title")
                },
            elevation = CardDefaults.cardElevation(4.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Info ukuran gambar
        Text(
            text = "Klik untuk memperbesar",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (showZoomDialog) {
            AlertDialog(
                onDismissRequest = {
                    showZoomDialog = false
                    // Reset state ketika dialog ditutup
                    scale = 1f
                    offset = Offset.Zero
                },
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.6f)
                                .background(Color.Black) // Background hitam untuk kontras
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            // Double tap untuk reset zoom
                                            scale = 1f
                                            offset = Offset.Zero
                                        }
                                    )
                                }
                        ) {
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = "$title (Zoom)",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offset.x,
                                        translationY = offset.y
                                    )
                                    .transformable(state) // ✅ INI YANG DITAMBAHKAN
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Pinch untuk zoom • Double tap untuk reset • Geser untuk menggerakkan",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showZoomDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Tutup")
                    }
                }
            )
        }
    }
}

@Composable
fun PhotoPlaceholder(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Photo,
                    contentDescription = "No Image",
                    tint = Color.Gray,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun FinalisasiTabContent(
    isLoading: Boolean,
    pendingFinal: List<Pelanggan>,
    onViewDetail: (Pelanggan) -> Unit,
    onFinalize: (Pelanggan) -> Unit
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Memproses...")
            }
        }
    } else if (pendingFinal.isEmpty()) {
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
                    contentDescription = "Tidak ada finalisasi",
                    tint = Color.Gray,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    "Tidak Ada Pengajuan Menunggu Finalisasi",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Pengajuan yang sudah di-review Pengawas akan muncul di sini",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            item {
                // Header info
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3E0) // Light orange
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Menunggu Konfirmasi Final",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                "Pengajuan berikut sudah di-review oleh Pengawas. " +
                                        "Silakan konfirmasi untuk mengirim hasil ke PDL.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Text(
                    "${pendingFinal.size} pengajuan menunggu finalisasi",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            items(pendingFinal) { pelanggan ->
                FinalisasiCard(
                    pelanggan = pelanggan,
                    onViewDetail = { onViewDetail(pelanggan) },
                    onFinalize = { onFinalize(pelanggan) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun FinalisasiCard(
    pelanggan: Pelanggan,
    onViewDetail: () -> Unit,
    onFinalize: () -> Unit
) {
    val dualInfo = pelanggan.dualApprovalInfo
    val pengawasStatus = dualInfo?.pengawasApproval?.status ?: "pending"
    val pengawasBy = dualInfo?.pengawasApproval?.by ?: "Pengawas"
    val pengawasNote = dualInfo?.pengawasApproval?.note ?: ""
    val isApproved = pengawasStatus == "approved"

    val statusColor = if (isApproved) Color(0xFF4CAF50) else Color(0xFFF44336)
    val statusText = if (isApproved) "DISETUJUI" else "DITOLAK"
    val statusIcon = if (isApproved) Icons.Default.CheckCircle else Icons.Default.Cancel

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewDetail),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header: Nama dan Status Pengawas
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
                        color = Color(0xFF2196F3),
                        fontWeight = FontWeight.Medium
                    )
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = statusColor.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            statusIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            statusText,
                            color = statusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info Keputusan Pengawas
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF5F5F5)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = Color(0xFF7C3AED),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Keputusan Pengawas ($pengawasBy)",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }

                    if (pengawasNote.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Catatan: $pengawasNote",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    // ✅ TAMBAH: Catatan dari Pimpinan dan Koordinator
                    val pimpinanNote = dualInfo?.pimpinanApproval?.note ?: ""
                    val koordinatorNote = dualInfo?.koordinatorApproval?.note ?: ""

                    if (pimpinanNote.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Catatan Pimpinan (Awal): $pimpinanNote",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    if (koordinatorNote.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Catatan Koordinator: $koordinatorNote",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    // Tampilkan penyesuaian jika ada
                    val adjustedAmount = dualInfo?.pengawasApproval?.adjustedAmount ?: 0
                    if (adjustedAmount > 0 && adjustedAmount != pelanggan.besarPinjaman) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Penyesuaian: Rp ${formatRupiah(adjustedAmount)}",
                            fontSize = 12.sp,
                            color = Color(0xFFFF9800),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tombol Aksi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onViewDetail,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Detail")
                }

                Button(
                    onClick = onFinalize,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isApproved) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isApproved) "Konfirmasi" else "Proses")
                }
            }
        }
    }
}

// =========================================================================
// PIMPINAN DELETION TAB CONTENT
// =========================================================================

@Composable
private fun PimpinanDeletionTabContent(
    deletionRequests: List<DeletionRequest>,
    onApprove: (DeletionRequest) -> Unit,
    onReject: (DeletionRequest, String) -> Unit,
    onRefresh: () -> Unit
) {
    if (deletionRequests.isEmpty()) {
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
                    Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    "Tidak Ada Pengajuan Penghapusan",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Pengajuan penghapusan nasabah\ndari Admin Lapangan akan muncul di sini",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
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
                    "Pengajuan Penghapusan Nasabah",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "${deletionRequests.size} pengajuan menunggu persetujuan",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(deletionRequests) { request ->
                PimpinanDeletionRequestCard(
                    request = request,
                    onApprove = { onApprove(request) },
                    onReject = { alasan -> onReject(request, alasan) }
                )
            }
        }
    }
}

@Composable
private fun PimpinanDeletionRequestCard(
    request: DeletionRequest,
    onApprove: () -> Unit,
    onReject: (String) -> Unit
) {
    var showApproveDialog by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var rejectReason by remember { mutableStateOf("") }

    // Dialog Konfirmasi Setuju
    if (showApproveDialog) {
        AlertDialog(
            onDismissRequest = { showApproveDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFE8F5E9), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50)
                        )
                    }
                    Text("Setujui Penghapusan", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Apakah Anda yakin ingin menyetujui penghapusan nasabah ini?")

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Nama: ${request.pelangganNama}",
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Sisa Utang: Rp ${formatRupiah(request.sisaUtang)}",
                                color = if (request.sisaUtang > 0) Color(0xFFE65100) else Color(0xFF4CAF50)
                            )
                        }
                    }

                    if (request.sisaUtang > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFEBEE)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFC62828),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    "PERHATIAN: Nasabah masih memiliki sisa utang!",
                                    color = Color(0xFFC62828),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Text(
                        "Tindakan ini tidak dapat dibatalkan.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showApproveDialog = false
                        onApprove()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Ya, Setujui", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApproveDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Dialog Konfirmasi Tolak
    if (showRejectDialog) {
        AlertDialog(
            onDismissRequest = { showRejectDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFFFEBEE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = null,
                            tint = Color(0xFFC62828)
                        )
                    }
                    Text("Tolak Penghapusan", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Berikan alasan penolakan penghapusan nasabah:")

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Nama: ${request.pelangganNama}", fontWeight = FontWeight.Medium)
                        }
                    }

                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it },
                        label = { Text("Alasan Penolakan *") },
                        placeholder = { Text("Masukkan alasan penolakan...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rejectReason.isNotBlank()) {
                            showRejectDialog = false
                            onReject(rejectReason)
                            rejectReason = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(8.dp),
                    enabled = rejectReason.isNotBlank()
                ) {
                    Text("Tolak", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRejectDialog = false
                    rejectReason = ""
                }) {
                    Text("Batal")
                }
            }
        )
    }

    // Card utama
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header dengan nama dan status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        request.pelangganNama,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Diajukan oleh: ${request.requestedByName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                // Status badge
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFFF3E0)
                ) {
                    Text(
                        "Menunggu",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color(0xFFE65100),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info singkat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sisa Utang", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        "Rp ${formatRupiah(request.sisaUtang)}",
                        fontWeight = FontWeight.SemiBold,
                        color = if (request.sisaUtang > 0) Color(0xFFE65100) else Color(0xFF4CAF50)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Status Sebelum", color = Color.Gray, fontSize = 12.sp)
                    Text(request.statusPelanggan, fontWeight = FontWeight.Medium)
                }
            }

            // Alasan penghapusan
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFFF8E1),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Alasan Penghapusan:", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        request.alasanPenghapusan,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Detail expandable
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("NIK: ${request.pelangganNik}", fontSize = 12.sp, color = Color.Gray)
                    Text("Alamat: ${request.pelangganAlamat}", fontSize = 12.sp, color = Color.Gray)
                    Text("Wilayah: ${request.pelangganWilayah}", fontSize = 12.sp, color = Color.Gray)
                    Text("Pinjaman Ke-${request.pinjamanKe}", fontSize = 12.sp, color = Color.Gray)
                    Text("Besar Pinjaman: Rp ${formatRupiah(request.besarPinjaman)}", fontSize = 12.sp, color = Color.Gray)
                }
            }

            // Toggle expand
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (expanded) "Sembunyikan Detail" else "Lihat Detail")
                Icon(
                    if (expanded) Icons.Default.ArrowDropDown else Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            }

            // Action buttons
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showRejectDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFC62828)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFC62828)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tolak")
                }

                Button(
                    onClick = { showApproveDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Setujui")
                }
            }
        }
    }
}

// =========================================================================
// TENOR CHANGE TAB CONTENT
// =========================================================================

@Composable
private fun TenorChangeTabContent(
    tenorChangeRequests: List<TenorChangeRequest>,
    onApprove: (TenorChangeRequest) -> Unit,
    onReject: (TenorChangeRequest, String) -> Unit,
    onRefresh: () -> Unit
) {
    if (tenorChangeRequests.isEmpty()) {
        // Empty state
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
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    "Tidak Ada Pengajuan Perubahan Tenor",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Pengajuan perubahan tenor dari\nAdmin Lapangan akan muncul di sini",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
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
                    "Pengajuan Perubahan Tenor",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "${tenorChangeRequests.size} pengajuan menunggu persetujuan",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(tenorChangeRequests) { request ->
                TenorChangeRequestCard(
                    request = request,
                    onApprove = { onApprove(request) },
                    onReject = { alasan -> onReject(request, alasan) }
                )
            }
        }
    }
}

@Composable
private fun TenorChangeRequestCard(
    request: TenorChangeRequest,
    onApprove: () -> Unit,
    onReject: (String) -> Unit
) {
    var showApproveDialog by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var rejectReason by remember { mutableStateOf("") }

    // Dialog Konfirmasi Setuju
    if (showApproveDialog) {
        AlertDialog(
            onDismissRequest = { showApproveDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFE8F5E9), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50)
                        )
                    }
                    Text("Setujui Perubahan Tenor", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Apakah Anda yakin ingin menyetujui perubahan tenor ini?")

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE3F2FD)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Nasabah: ${request.pelangganNama}",
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Tenor Lama", fontSize = 12.sp, color = Color.Gray)
                                    Text(
                                        "${request.tenorLama} hari",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF757575)
                                    )
                                }
                                Icon(
                                    Icons.Default.ArrowForward,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50)
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Tenor Baru", fontSize = 12.sp, color = Color.Gray)
                                    Text(
                                        "${request.tenorBaru} hari",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        "Tenor akan langsung berubah setelah disetujui.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showApproveDialog = false
                        onApprove()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Ya, Setujui", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApproveDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Dialog Konfirmasi Tolak
    if (showRejectDialog) {
        AlertDialog(
            onDismissRequest = { showRejectDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFFFEBEE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = null,
                            tint = Color(0xFFC62828)
                        )
                    }
                    Text("Tolak Perubahan Tenor", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Berikan alasan penolakan perubahan tenor:")

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF5F5F5)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Nasabah: ${request.pelangganNama}", fontWeight = FontWeight.Medium)
                            Text(
                                "Perubahan: ${request.tenorLama} → ${request.tenorBaru} hari",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it },
                        label = { Text("Alasan Penolakan *") },
                        placeholder = { Text("Masukkan alasan penolakan...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rejectReason.isNotBlank()) {
                            showRejectDialog = false
                            onReject(rejectReason)
                            rejectReason = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(8.dp),
                    enabled = rejectReason.isNotBlank()
                ) {
                    Text("Tolak", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRejectDialog = false
                    rejectReason = ""
                }) {
                    Text("Batal")
                }
            }
        )
    }

    // Card utama
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header dengan nama
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        request.pelangganNama,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Diajukan oleh: ${request.requestedByName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                // Badge perubahan tenor
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFE3F2FD)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "${request.tenorLama}",
                            color = Color(0xFF757575),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "${request.tenorBaru}",
                            color = Color(0xFF2196F3),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            "hari",
                            color = Color(0xFF2196F3),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info pinjaman
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Besar Pinjaman", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        "Rp ${formatRupiah(request.besarPinjaman)}",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Pinjaman Ke", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        "${request.pinjamanKe}",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Alasan perubahan
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFFF8E1),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Alasan Perubahan:", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        request.alasanPerubahan,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Action buttons
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showRejectDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFC62828)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC62828)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tolak")
                }

                Button(
                    onClick = { showApproveDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Setujui")
                }
            }
        }
    }
}

@Composable
private fun PimpinanPaymentDeletionRequestCard(
    request: PaymentDeletionRequest,
    onApprove: () -> Unit,
    onReject: (String) -> Unit
) {
    var showApproveDialog by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var rejectReason by remember { mutableStateOf("") }

    // Dialog Konfirmasi Setuju
    if (showApproveDialog) {
        AlertDialog(
            onDismissRequest = { showApproveDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFE8F5E9), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50)
                        )
                    }
                    Text("Setujui Penghapusan Pembayaran", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Apakah Anda yakin ingin menyetujui penghapusan pembayaran ini?")

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Nasabah: ${request.pelangganNama}", fontWeight = FontWeight.Medium)
                            Text("Cicilan Ke: ${request.cicilanKe}")
                            Text(
                                "Jumlah: Rp ${formatRupiah(request.jumlahPembayaran)}",
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.Bold
                            )
                            Text("Tanggal: ${request.tanggalPembayaran}")
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFEBEE)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFC62828),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Sisa utang nasabah akan bertambah Rp ${formatRupiah(request.jumlahPembayaran)}",
                                color = Color(0xFFC62828),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showApproveDialog = false
                        onApprove()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Ya, Setujui", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApproveDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }

    // Dialog Konfirmasi Tolak
    if (showRejectDialog) {
        AlertDialog(
            onDismissRequest = { showRejectDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFFFEBEE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = null,
                            tint = Color(0xFFC62828)
                        )
                    }
                    Text("Tolak Penghapusan", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Berikan alasan penolakan:")

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Nasabah: ${request.pelangganNama}", fontWeight = FontWeight.Medium)
                            Text("Cicilan Ke: ${request.cicilanKe}")
                            Text("Jumlah: Rp ${formatRupiah(request.jumlahPembayaran)}")
                        }
                    }

                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it },
                        label = { Text("Alasan Penolakan *") },
                        placeholder = { Text("Masukkan alasan penolakan...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rejectReason.isNotBlank()) {
                            showRejectDialog = false
                            onReject(rejectReason)
                            rejectReason = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(8.dp),
                    enabled = rejectReason.isNotBlank()
                ) {
                    Text("Tolak", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRejectDialog = false
                    rejectReason = ""
                }) {
                    Text("Batal")
                }
            }
        )
    }

    // Card utama
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                        request.pelangganNama,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Diajukan oleh: ${request.requestedByName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                // Badge cicilan
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFFF3E0)
                ) {
                    Text(
                        "Cicilan ke-${request.cicilanKe}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color(0xFFE65100),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info pembayaran
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Jumlah Pembayaran", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        "Rp ${formatRupiah(request.jumlahPembayaran)}",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tanggal", color = Color.Gray, fontSize = 12.sp)
                    Text(request.tanggalPembayaran, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Info sisa utang
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sudah Dibayar", color = Color.Gray, fontSize = 12.sp)
                    Text("Rp ${formatRupiah(request.sudahDibayar)}", fontWeight = FontWeight.SemiBold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sisa Utang", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        "Rp ${formatRupiah(request.sisaUtang)}",
                        fontWeight = FontWeight.SemiBold,
                        color = if (request.sisaUtang > 0) Color(0xFFC62828) else Color(0xFF4CAF50)
                    )
                }
            }

            // Alasan penghapusan
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFFF8E1),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Alasan Penghapusan:", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        request.alasanPenghapusan,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Action buttons
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showRejectDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFC62828)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFC62828)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tolak")
                }

                Button(
                    onClick = { showApproveDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Setujui")
                }
            }
        }
    }
}