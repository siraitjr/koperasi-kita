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
import com.example.koperasikitagodangulu.models.DeletionRequest
import com.example.koperasikitagodangulu.models.DeletionRequestStatus
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.delay
import com.example.koperasikitagodangulu.utils.formatRupiahInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PengawasApprovalScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel,
    initialTab: Int = 0
) {
    val context = LocalContext.current

    // =========================================================================
    // ✅ DARK MODE SUPPORT
    // =========================================================================
    val isDark by viewModel.isDarkMode

    // =========================================================================
    // STATE MANAGEMENT
    // =========================================================================
    var showApprovalDialog by remember { mutableStateOf(false) }
    var approvalNote by remember { mutableStateOf("") }
    var showApprovalWithAmountDialog by remember { mutableStateOf(false) }
    var selectedPelangganForAmount by remember { mutableStateOf<Pelanggan?>(null) }

    // Pending approvals untuk pengawas (hanya >= 3jt)
    val pendingApprovalsPengawas by viewModel.pendingApprovalsPengawas.collectAsState()

    var selectedPelanggan by remember { mutableStateOf<Pelanggan?>(null) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var rejectReason by remember { mutableStateOf("") }
    val bottomSheetState = rememberModalBottomSheetState()

    // Loading dan feedback state
    var isLoading by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }

    val pengawasSerahTerimaNotifications by viewModel.pengawasSerahTerimaNotifications.collectAsState()
    val unreadPengawasSerahTerimaCount by viewModel.unreadPengawasSerahTerimaCount.collectAsState()

    // ✅ TAMBAHAN: Tab state
    var selectedTabIndex by remember { mutableStateOf(initialTab) }  // ✅ Gunakan initialTab

    // =========================================================================
    // INITIALIZATION
    // =========================================================================
    LaunchedEffect(Unit) {
        Log.d("PengawasApproval", "🔄 Loading pending approvals for Pengawas")
        viewModel.loadPendingApprovalsForPengawas()

        // Mark semua notifikasi pengajuan sebagai sudah dibaca
        viewModel.markAllPengawasPengajuanNotificationsAsRead()

        viewModel.loadSerahTerimaNotificationsForPengawas()
    }

    // Handle operation result
    LaunchedEffect(operationMessage) {
        operationMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.loadPendingApprovalsForPengawas()
            operationMessage = null
        }
    }

    // =========================================================================
    // UI
    // =========================================================================
    Scaffold(
        containerColor = PengawasColors.getBackground(isDark),
        topBar = {
            PengawasTopBar(
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
            PengawasBottomNavigation(navController, "pengawas_approvals", isDark = isDark)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Header Info - menjelaskan scope pengawas
//            PengawasApprovalHeader()
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = PengawasColors.getCard(isDark)
            ) {
                // Tab Pengajuan
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Pengajuan") },
                    icon = {
                        Box {
                            Icon(Icons.Default.Pending, contentDescription = null)
                            if (pendingApprovalsPengawas.isNotEmpty()) {
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
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        viewModel.markAllPengawasSerahTerimaAsRead()
                    },
                    text = { Text("Serah Terima") },
                    icon = {
                        Box {
                            Icon(Icons.Default.Verified, contentDescription = null)
                            if (unreadPengawasSerahTerimaCount > 0) {
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
                    // TAB PENGAJUAN (kode existing - TIDAK DIUBAH)
//                        PengawasApprovalHeader()
                    PengawasApprovalContent(
                        isLoading = isLoading,
                        pendingApprovals = pendingApprovalsPengawas,
                        isDark = isDark,
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
                    // ✅ TAMBAHAN: TAB SERAH TERIMA
                    PengawasSerahTerimaTabContent(
                        notifications = pengawasSerahTerimaNotifications,
                        isDark = isDark,
                        onItemClick = { notification ->
                            navController.navigate("pengawas_detail_serah_terima/${notification.id}")
                        },
                        onRefresh = {
                            viewModel.refreshPengawasSerahTerimaNotifications()
                        },
                        onMarkComplete = { notification ->
                            viewModel.deletePengawasSerahTerimaNotification(
                                notificationId = notification.id,
                                onSuccess = {
                                    // Optional: tampilkan toast
                                }
                            )
                        }
                    )
                }
            }
        }

        // =========================================================================
        // DIALOGS
        // =========================================================================

        // Dialog Setujui dengan Penyesuaian
        if (showApprovalWithAmountDialog && selectedPelangganForAmount != null) {
            PengawasApprovalWithAmountDialog(
                pelanggan = selectedPelangganForAmount!!,
                onConfirm = { disetujuiAmount, tenorDisetujui, catatan ->
                    isLoading = true
                    viewModel.approvePengajuanAsPengawas(
                        pelangganId = selectedPelangganForAmount!!.id,
                        catatan = catatan,
                        besarPinjamanDisetujui = disetujuiAmount,
                        tenorDisetujui = tenorDisetujui,
                        catatanPerubahanPinjaman = "Disetujui pengawas dengan penyesuaian jumlah dari " +
                                "Rp ${formatRupiah(selectedPelangganForAmount!!.besarPinjaman)} menjadi " +
                                "Rp ${formatRupiah(disetujuiAmount)} dan tenor dari " +
                                "${selectedPelangganForAmount!!.tenor} hari menjadi ${tenorDisetujui} hari",
                        onSuccess = {
                            isLoading = false
                            operationMessage = "Pengajuan berhasil disetujui oleh Pengawas"
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
                    PengawasDetailPengajuanSheet(
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
            PengawasRejectDialog(
                onConfirm = { reason ->
                    isLoading = true
                    selectedPelanggan?.let { pelanggan ->
                        viewModel.rejectPengajuanAsPengawas(
                            pelangganId = pelanggan.id,
                            alasan = reason,
                            onSuccess = {
                                isLoading = false
                                operationMessage = "Pengajuan ditolak oleh Pengawas"
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

        // Approval Note Dialog
        if (showApprovalDialog && selectedPelanggan != null) {
            PengawasApprovalNoteDialog(
                pelanggan = selectedPelanggan!!,
                onConfirm = { catatan ->
                    isLoading = true
                    viewModel.approvePengajuanAsPengawas(
                        pelangganId = selectedPelanggan!!.id,
                        catatan = catatan,
                        onSuccess = {
                            isLoading = false
                            operationMessage = "Pengajuan berhasil disetujui oleh Pengawas"
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
private fun PengawasApprovalHeader(isDark: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PengawasColors.primary.copy(alpha = 0.1f)
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
                tint = PengawasColors.primary,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = "Review Pinjaman Besar (Tahap 3/5)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = PengawasColors.getTextPrimary(isDark)
                )
                Text(
                    text = "Pengajuan ≥ Rp 3.000.000 yang sudah di-review Pimpinan. " +
                            "Keputusan Anda adalah FINAL.",  // ✅ UPDATE
                    fontSize = 12.sp,
                    color = PengawasColors.getTextSecondary(isDark)
                )
            }
        }
    }
}

// =============================================================================
// CONTENT COMPONENT
// =============================================================================
@Composable
private fun PengawasApprovalContent(
    isLoading: Boolean,
    pendingApprovals: List<Pelanggan>,
    onViewDetail: (Pelanggan) -> Unit,
    onApprove: (Pelanggan) -> Unit,
    onReject: (Pelanggan) -> Unit,
    onAdjustAmount: (Pelanggan) -> Unit,
    isDark: Boolean = false
) {
    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PengawasColors.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Memproses...", color = PengawasColors.getTextSecondary(isDark))
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
                        tint = PengawasColors.getTextMuted(isDark),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "Tidak Ada Pengajuan",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PengawasColors.getTextSecondary(isDark),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Semua pengajuan pinjaman ≥ Rp 3.000.000 telah diproses",
                        fontSize = 14.sp,
                        color = PengawasColors.getTextMuted(isDark),
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
                        color = PengawasColors.getTextPrimary(isDark)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        "${pendingApprovals.size} pengajuan perlu ditinjau",
                        fontSize = 14.sp,
                        color = PengawasColors.getTextSecondary(isDark)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                items(pendingApprovals) { pelanggan ->
                    PengawasApprovalCard(
                        pelanggan = pelanggan,
                        onViewDetail = { onViewDetail(pelanggan) },
                        onApprove = { onApprove(pelanggan) },
                        onReject = { onReject(pelanggan) },
                        onAdjustAmount = { onAdjustAmount(pelanggan) },
                        isDark = isDark
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
private fun PengawasApprovalCard(
    pelanggan: Pelanggan,
    onViewDetail: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onAdjustAmount: () -> Unit,
    isDark: Boolean = false
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
        colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
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
                        color = PengawasColors.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Phase indicator
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFF9800).copy(alpha = 0.1f)
                ) {
                    Text(
                        "Tahap 3/5",
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
                            color = Color.Gray
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

            Spacer(modifier = Modifier.height(8.dp))

            // ✅ BARU: Info Review Koordinator (5-Phase)
            val koordinatorApprovalCard = dualInfo?.koordinatorApproval
            val isKoordinatorApprovedCard = koordinatorApprovalCard?.status == "approved"
            val isKoordinatorRejectedCard = koordinatorApprovalCard?.status == "rejected"
            val koordinatorByCard = koordinatorApprovalCard?.by ?: "Koordinator"
            val koordinatorNoteCard = koordinatorApprovalCard?.note ?: ""
            val koordinatorStatusTextCard = when {
                isKoordinatorApprovedCard -> "SETUJU"
                isKoordinatorRejectedCard -> "TOLAK"
                else -> "SETUJU"
            }
            val koordinatorStatusColorCard = when {
                isKoordinatorApprovedCard -> Color(0xFF4CAF50)
                isKoordinatorRejectedCard -> Color(0xFFF44336)
                else -> Color(0xFF4CAF50)
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = koordinatorStatusColorCard.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isKoordinatorApprovedCard) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = koordinatorStatusColorCard,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Koordinator ($koordinatorByCard): $koordinatorStatusTextCard",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = koordinatorStatusColorCard
                        )
                    }

                    if (koordinatorNoteCard.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Catatan: $koordinatorNoteCard",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    // Tampilkan penyesuaian Koordinator jika ada
                    val koordinatorAdjusted = koordinatorApprovalCard?.adjustedAmount ?: 0
                    if (koordinatorAdjusted > 0 && koordinatorAdjusted != pelanggan.besarPinjamanDiajukan) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Penyesuaian Koordinator: Rp ${formatRupiah(koordinatorAdjusted)}",
                            fontSize = 12.sp,
                            color = Color(0xFF10B981),
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
                    icon = Icons.Default.Person,
                    label = pelanggan.adminName.ifBlank { "PDL" }
                )
                InfoChip(
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
                        containerColor = PengawasColors.primary
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

            // ✅ BARU: Reminder bahwa keputusan Pengawas adalah FINAL
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "⚠️ Keputusan Anda adalah FINAL dan akan diteruskan ke Pimpinan untuk konfirmasi.",
                fontSize = 11.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InfoChip(
    icon: ImageVector,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun PengawasInfoItem(
    icon: ImageVector,
    label: String,
    value: String,
    isDark: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PengawasColors.getTextMuted(isDark),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = PengawasColors.getTextMuted(isDark)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = PengawasColors.getTextPrimary(isDark),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =============================================================================
// DETAIL SHEET - FIXED VERSION WITH PHOTOS
// =============================================================================
@Composable
private fun PengawasDetailPengajuanSheet(
    pelanggan: Pelanggan,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
    viewModel: PelangganViewModel,
    isDark: Boolean = false
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
                color = PengawasColors.warning.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "💰 PINJAMAN BESAR",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = PengawasColors.warning
                )
            }
        }

        // Status Dual Approval Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = PengawasColors.primary.copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Status Persetujuan Ganda",
                    fontWeight = FontWeight.Bold,
                    color = PengawasColors.primary
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
                            tint = if (isPimpinanApproved) PengawasColors.success else PengawasColors.warning,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            "Pimpinan",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (isPimpinanApproved) "Disetujui" else "Menunggu",
                            fontSize = 10.sp,
                            color = if (isPimpinanApproved) PengawasColors.success else PengawasColors.warning
                        )
                        if (isPimpinanApproved && pimpinanApproval != null) {
                            Text(
                                pimpinanApproval.by.take(15),
                                fontSize = 9.sp,
                                color = PengawasColors.getTextMuted(isDark)
                            )
                        }
                    }

                    // Divider 1
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(55.dp)
                            .background(PengawasColors.getBorder(isDark))
                    )

                    // Status Koordinator (BARU - 5 Phase)
                    val koordinatorApproval = pelanggan.dualApprovalInfo?.koordinatorApproval
                    val isKoordinatorApproved = koordinatorApproval?.status == "approved"
                    val isKoordinatorRejected = koordinatorApproval?.status == "rejected"
                    val isKoordinatorReviewed = isKoordinatorApproved || isKoordinatorRejected

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = when {
                                isKoordinatorApproved -> Icons.Default.CheckCircle
                                isKoordinatorRejected -> Icons.Default.Cancel
                                else -> Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            tint = when {
                                isKoordinatorApproved -> PengawasColors.success
                                isKoordinatorRejected -> Color(0xFFF44336)
                                else -> PengawasColors.success
                            },
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            "Koordinator",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            when {
                                isKoordinatorApproved -> "Disetujui"
                                isKoordinatorRejected -> "Ditolak"
                                else -> "Disetujui"
                            },
                            fontSize = 10.sp,
                            color = when {
                                isKoordinatorApproved -> PengawasColors.success
                                isKoordinatorRejected -> Color(0xFFF44336)
                                else -> PengawasColors.success
                            }
                        )
                        if (isKoordinatorReviewed && koordinatorApproval != null) {
                            Text(
                                koordinatorApproval.by.take(15),
                                fontSize = 9.sp,
                                color = PengawasColors.getTextMuted(isDark)
                            )
                        }
                    }

                    // Divider 2
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(55.dp)
                            .background(PengawasColors.getBorder(isDark))
                    )

                    // Status Pengawas (selalu pending di screen ini)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = PengawasColors.warning,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            "Pengawas",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Menunggu Anda",
                            fontSize = 10.sp,
                            color = PengawasColors.warning
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Data Nasabah
        DetailSection(title = "Data Nasabah") {
            DetailRow(label = "Nama Panggilan", value = pelanggan.namaPanggilan)
            DetailRow(label = "Nama KTP", value = pelanggan.namaKtp)
            DetailRow(label = "NIK", value = pelanggan.nik)
            DetailRow(label = "No. HP", value = pelanggan.noHp)
            DetailRow(label = "Alamat Rumah", value = pelanggan.alamatRumah)
            DetailRow(label = "Alamat KTP", value = pelanggan.alamatKtp)
            DetailRow(label = "Wilayah", value = pelanggan.wilayah)
            DetailRow(label = "No. Anggota", pelanggan.nomorAnggota)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Data Pinjaman
        DetailSection(title = "Data Pinjaman") {
            DetailRow(
                label = "Pinjaman Ke",
                value = if (pelanggan.pinjamanKe <= 1) "Pertama (Nasabah Baru)" else "Ke-${pelanggan.pinjamanKe}",
                valueColor = if (pelanggan.pinjamanKe <= 1) Color(0xFF4CAF50) else Color(0xFF2196F3)
            )
            DetailRow(
                label = "Besar Pinjaman",
                value = "Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                valueColor = PengawasColors.primary
            )
            DetailRow(label = "Tenor", value = "${pelanggan.tenor} hari")
            DetailRow(label = "Total Potongan", value = "Rp ${formatRupiah(pelanggan.jasaPinjaman)}")
//            DetailRow(label = "Admin", value = "Rp ${formatRupiah(pelanggan.admin)}")
//            DetailRow(label = "Simpanan", value = "Rp ${formatRupiah(pelanggan.simpanan)}")
            DetailRow(label = "Total Pelunasan", value = "Rp ${formatRupiah(pelanggan.totalPelunasan)}")
            DetailRow(label = "Total Diterima", value = "Rp ${formatRupiah(pelanggan.totalDiterima)}")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Data Usaha
        DetailSection(title = "Data Usaha") {
            DetailRow(label = "Jenis Usaha", value = pelanggan.jenisUsaha)
            DetailRow(label = "Tanggal Pengajuan", value = pelanggan.tanggalPengajuan)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // =========================================================================
        // ✅ PERBAIKAN: SECTION FOTO LENGKAP
        // =========================================================================
        DetailSection(title = "Dokumen Foto") {
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
                            color = PengawasColors.getTextSecondary(isDark),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        if (pelanggan.fotoKtpSuamiUrl.isNotBlank()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.5f)
                                    .clickable {
                                        zoomImageUrl = pelanggan.fotoKtpSuamiUrl
                                        zoomImageTitle = "Foto KTP Suami"
                                        showZoomDialog = true
                                    },
                                shape = RoundedCornerShape(8.dp),
                                elevation = CardDefaults.cardElevation(2.dp)
                            ) {
                                AsyncImage(
                                    model = pelanggan.fotoKtpSuamiUrl,
                                    contentDescription = "Foto KTP Suami",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else {
                            PhotoPlaceholderPengawas("Tidak tersedia")
                        }
                    }

                    // Foto KTP Istri
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Foto KTP Istri",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = PengawasColors.getTextSecondary(isDark),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        if (pelanggan.fotoKtpIstriUrl.isNotBlank()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.5f)
                                    .clickable {
                                        zoomImageUrl = pelanggan.fotoKtpIstriUrl
                                        zoomImageTitle = "Foto KTP Istri"
                                        showZoomDialog = true
                                    },
                                shape = RoundedCornerShape(8.dp),
                                elevation = CardDefaults.cardElevation(2.dp)
                            ) {
                                AsyncImage(
                                    model = pelanggan.fotoKtpIstriUrl,
                                    contentDescription = "Foto KTP Istri",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else {
                            PhotoPlaceholderPengawas("Tidak tersedia")
                        }
                    }
                }

                // Foto Nasabah
                Text(
                    "Foto Nasabah",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = PengawasColors.getTextSecondary(isDark),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (pelanggan.fotoNasabahUrl.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.6f)
                            .clickable {
                                zoomImageUrl = pelanggan.fotoNasabahUrl
                                zoomImageTitle = "Foto Nasabah"
                                showZoomDialog = true
                            },
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        AsyncImage(
                            model = pelanggan.fotoNasabahUrl,
                            contentDescription = "Foto Nasabah",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    PhotoPlaceholderPengawas("Foto Nasabah tidak tersedia")
                }

                // Foto KTP (fallback jika tidak ada KTP Suami/Istri)
                if (pelanggan.fotoKtpUrl.isNotBlank() &&
                    pelanggan.fotoKtpSuamiUrl.isBlank() &&
                    pelanggan.fotoKtpIstriUrl.isBlank()) {
                    Text(
                        "Foto KTP",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = PengawasColors.getTextSecondary(isDark),
                        modifier = Modifier.padding(bottom = 4.dp, top = 8.dp)
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.6f)
                            .clickable {
                                zoomImageUrl = pelanggan.fotoKtpUrl
                                zoomImageTitle = "Foto KTP"
                                showZoomDialog = true
                            },
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        AsyncImage(
                            model = pelanggan.fotoKtpUrl,
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
                    containerColor = PengawasColors.danger
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
                    containerColor = PengawasColors.success
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
        ZoomableImageDialogPengawas(
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
private fun PhotoPlaceholderPengawas(message: String, isDark: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = PengawasColors.getBackground(isDark)
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
                    tint = PengawasColors.getTextMuted(isDark),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    message,
                    fontSize = 11.sp,
                    color = PengawasColors.getTextMuted(isDark),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ZoomableImageDialogPengawas(
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
    title: String,
    isDark: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark)),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = PengawasColors.getTextPrimary(isDark)
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
    valueColor: Color = PengawasColors.getTextPrimary(isDark)
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
            color = PengawasColors.getTextSecondary(isDark)
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
private fun PengawasApprovalNoteDialog(
    pelanggan: Pelanggan,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    isDark: Boolean = false
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
                    color = PengawasColors.getTextSecondary(isDark)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = PengawasColors.getBackground(isDark)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            pelanggan.namaPanggilan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                            color = PengawasColors.primary,
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
                    "⚠️ Persetujuan ini akan dicatat atas nama Anda sebagai Pengawas",
                    fontSize = 12.sp,
                    color = PengawasColors.warning
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(note) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = PengawasColors.success
                )
            ) {
                Text("Setujui sebagai Pengawas")
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
private fun PengawasRejectDialog(
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
                    "⚠️ PERHATIAN: Penolakan dari Pengawas akan langsung menolak pengajuan ini meskipun Pimpinan sudah menyetujui.",
                    color = PengawasColors.danger,
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
                        color = PengawasColors.danger,
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
                    containerColor = PengawasColors.danger
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
private fun PengawasApprovalWithAmountDialog(
    pelanggan: Pelanggan,
    isDark: Boolean = false,
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
                        containerColor = PengawasColors.getBackground(isDark)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Pengajuan Awal:", fontSize = 12.sp, color = PengawasColors.getTextMuted(isDark))
                        Text(
                            pelanggan.namaPanggilan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Rp ${formatRupiah(pelanggan.besarPinjaman)} • ${pelanggan.tenor} hari",
                            color = PengawasColors.getTextSecondary(isDark)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Input Jumlah Disetujui
                OutlinedTextField(
                    value = formatRupiahInput(disetujuiAmount),
                    onValueChange = { newValue ->
                        disetujuiAmount = newValue.filter { c -> c.isDigit() }
                    },
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
                            containerColor = PengawasColors.warning.copy(alpha = 0.1f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Perubahan:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PengawasColors.warning
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
                    containerColor = PengawasColors.success
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
private fun PengawasSerahTerimaTabContent(
    notifications: List<SerahTerimaNotification>,
    isDark: Boolean = false,
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
                        tint = PengawasColors.getTextMuted(isDark),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "Belum Ada Bukti Serah Terima",
                        style = MaterialTheme.typography.titleLarge,
                        color = PengawasColors.getTextSecondary(isDark),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Foto serah terima uang akan muncul di sini\nsetelah PDL upload",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PengawasColors.getTextMuted(isDark),
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
                        color = PengawasColors.getTextPrimary(isDark),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "${notifications.size} data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PengawasColors.getTextSecondary(isDark),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(notifications) { notification ->
                    PengawasSerahTerimaCard(
                        notification = notification,
                        isDark = isDark,
                        onClick = { onItemClick(notification) },
                        onMarkComplete = { onMarkComplete(notification) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PengawasSerahTerimaCard(
    notification: SerahTerimaNotification,
    isDark: Boolean = false,
    onClick: () -> Unit,
    onMarkComplete: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    // Dialog konfirmasi
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            containerColor = PengawasColors.getCard(isDark),
            title = { Text("Konfirmasi Selesai", color = PengawasColors.getTextPrimary(isDark)) },
            text = {
                Text(
                    "Tandai bukti serah terima \"${notification.pelangganNama}\" sebagai selesai?\n\nData akan dihapus dari daftar.",
                    color = PengawasColors.getTextSecondary(isDark)
                )
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
                if (isDark) Color(0xFF2D1F3D) else Color(0xFFF3E5F5)
            } else {
                PengawasColors.getCard(isDark)
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
                    .background(
                        if (isDark) Color(0xFF1B3D2F) else Color(0xFFE8F5E9),
                        RoundedCornerShape(8.dp)
                    ),
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
                        fontWeight = FontWeight.Bold,
                        color = PengawasColors.getTextPrimary(isDark)
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
                    color = PengawasColors.getTextSecondary(isDark)
                )

                Text(
                    notification.tanggalSerahTerima,
                    style = MaterialTheme.typography.bodySmall,
                    color = PengawasColors.getTextMuted(isDark)
                )
            }

            // Tombol Selesai
            IconButton(
                onClick = { showConfirmDialog = true },
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isDark) Color(0xFF1B3D2F) else Color(0xFFE8F5E9),
                        CircleShape
                    )
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
private fun DetailRowDeletion(label: String, value: String) {
    if (value.isNotBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.Gray, fontSize = 13.sp)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}