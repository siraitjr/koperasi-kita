package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.models.AdminSummary
import com.example.koperasikitagodangulu.models.PengawasCabangSummary
import com.example.koperasikitagodangulu.models.PembayaranHarianItem
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.*
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Send
import com.example.koperasikitagodangulu.models.BroadcastMessage
import androidx.compose.material3.FloatingActionButton
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.systemuicontroller.rememberSystemUiController

/**
 * =========================================================================
 * PENGAWAS DASHBOARD SCREEN - FIXED VERSION
 * =========================================================================
 *
 * PERBAIKAN:
 * 1. Menggunakan StateFlow reaktif (collectAsState) untuk derived state
 * 2. LaunchedEffect dengan key yang benar
 * 3. State initialization yang proper
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PengawasDashboardScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // =========================================================================
    // ✅ DARK MODE SUPPORT
    // =========================================================================
    val isDark by viewModel.isDarkMode
    val systemUiController = rememberSystemUiController()
    val bgColor = PengawasColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // =========================================================================
    // ✅ FIX #1: Collect StateFlow untuk data mentah
    // =========================================================================
    val selectedCabangId by viewModel.pengawasSelectedCabangId.collectAsState()
    val allCabangSummaries by viewModel.allCabangSummaries.collectAsState()
    val pembayaranHarian by viewModel.pengawasPembayaranHarian.collectAsState()
    val nasabahBaru by viewModel.pengawasNasabahBaru.collectAsState()
    val nasabahLunas by viewModel.pengawasNasabahLunas.collectAsState()
    val pengawasDataLoaded by viewModel.pengawasDataLoaded.collectAsState()

    // =========================================================================
    // ✅ FIX #2: Collect DERIVED StateFlow (bukan memanggil fungsi!)
    // Sekarang ini REAKTIF - otomatis update saat data/filter berubah
    // =========================================================================
    val cabangOptions by viewModel.pengawasCabangOptions.collectAsState()
    val currentSummary by viewModel.pengawasCurrentSummary.collectAsState()
    val filteredAdminSummaries by viewModel.pengawasFilteredAdminSummaries.collectAsState()
    // ✅ BARU: State untuk Broadcast
    val activeBroadcasts by viewModel.activeBroadcasts.collectAsState()
    var showBroadcastDialog by remember { mutableStateOf(false) }
    // ✅ BARU: Foto admin lapangan
    val adminPhotosMap by viewModel.adminPhotosMap.collectAsState()

    // Visibility state untuk animasi
    var isVisible by remember { mutableStateOf(false) }

    // =========================================================================
    // ✅ FIX #3: LaunchedEffect dengan pengawasDataLoaded sebagai trigger
    // =========================================================================
    LaunchedEffect(Unit) {
        try {
            if (!pengawasDataLoaded) {
                Log.d("PengawasDashboard", "🚀 Initial data load triggered")
                viewModel.loadPengawasAllCabangData()
            }
            // ✅ BARU: Load broadcast messages
            viewModel.loadActiveBroadcasts()
        } catch (e: Exception) {
            Log.e("PengawasDashboard", "Error loading data: ${e.message}")
        }
    }

    // ✅ FIX #4: isVisible berdasarkan data yang sudah loaded
    LaunchedEffect(pengawasDataLoaded, allCabangSummaries) {
        if (pengawasDataLoaded && allCabangSummaries.isNotEmpty()) {
            isVisible = true
        }
    }

    // ✅ BARU: Load foto admin saat filteredAdminSummaries tersedia
    LaunchedEffect(filteredAdminSummaries) {
        if (filteredAdminSummaries.isNotEmpty()) {
            val adminUids = filteredAdminSummaries.map { it.adminId }
            viewModel.loadAdminPhotosForCabang(adminUids)
        }
    }

    fun handleRefresh() {
        coroutineScope.launch {
            viewModel.refreshPengawasData()
        }
    }

    Scaffold(
        containerColor = PengawasColors.getBackground(isDark),
        topBar = {
            PengawasTopBar(
                title = "Dashboard",
                navController = navController,
                viewModel = viewModel,
                onRefresh = { handleRefresh() }
            )
        },
        bottomBar = {
            PengawasBottomNavigation(navController, "pengawas_dashboard", isDark = isDark)
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showBroadcastDialog = true },
                containerColor = PengawasColors.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Rounded.Campaign, contentDescription = "Kirim Broadcast")
            }
        }
    ) { innerPadding ->

        when {
            // Loading state
            isLoading && allCabangSummaries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = PengawasColors.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Memuat data...",
                            color = PengawasColors.getTextSecondary(isDark)
                        )
                    }
                }
            }

            // Empty state (data loaded tapi kosong)
            pengawasDataLoaded && allCabangSummaries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = PengawasColors.getTextMuted(isDark),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "Data tidak tersedia",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PengawasColors.getTextPrimary(isDark)
                        )
                        Text(
                            text = "Tekan refresh untuk memuat ulang",
                            color = PengawasColors.getTextSecondary(isDark)
                        )
                        Button(
                            onClick = { handleRefresh() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PengawasColors.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Refresh")
                        }
                    }
                }
            }

            // Content state
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // =========================================================
                    // ✅ BARU: BROADCAST BANNER
                    // =========================================================
                    if (activeBroadcasts.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { -20 })
                            ) {
                                PengawasBroadcastBanner(
                                    broadcasts = activeBroadcasts,
                                    isDark = isDark,
                                    onDelete = { broadcast ->
                                        viewModel.deleteBroadcast(
                                            messageId = broadcast.id,
                                            onSuccess = { /* Toast */ },
                                            onFailure = { /* Toast error */ }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // =========================================================
                    // CABANG FILTER CHIPS
                    // =========================================================
                    item {
                        AnimatedVisibility(
                            visible = isVisible,
                            enter = fadeIn(tween(300)) + slideInVertically(initialOffsetY = { -20 })
                        ) {
                            CabangFilterSection(
                                cabangOptions = cabangOptions,
                                selectedCabangId = selectedCabangId,
                                isDark = isDark,
                                onCabangSelected = { viewModel.setPengawasSelectedCabang(it) }
                            )
                        }
                    }

                    // =========================================================
                    // SUMMARY CARDS
                    // currentSummary sekarang REAKTIF - otomatis update!
                    // =========================================================
                    item {
                        AnimatedVisibility(
                            visible = isVisible,
                            enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(initialOffsetY = { -20 })
                        ) {
                            SummaryCardsSection(summary = currentSummary, isDark = isDark)
                        }
                    }

                    // =========================================================
                    // PERFORMANCE OVERVIEW
                    // =========================================================
                    item {
                        AnimatedVisibility(
                            visible = isVisible,
                            enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(initialOffsetY = { 20 })
                        ) {
                            PerformanceOverviewCard(summary = currentSummary, isDark = isDark)
                        }
                    }

                    // =========================================================
                    // CABANG PERFORMANCE (jika semua cabang dipilih)
                    // =========================================================
                    if (selectedCabangId == null && allCabangSummaries.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 300)) + slideInVertically(initialOffsetY = { 20 })
                            ) {
                                CabangPerformanceSection(
                                    cabangSummaries = allCabangSummaries.values.toList(),
                                    isDark = isDark
                                )
                            }
                        }
                    }

                    // =========================================================
                    // ADMIN PERFORMANCE (Top 10)
                    // filteredAdminSummaries sekarang REAKTIF!
                    // =========================================================
                    if (filteredAdminSummaries.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 400)) + slideInVertically(initialOffsetY = { 20 })
                            ) {
                                AdminPerformanceSection(
                                    adminSummaries = filteredAdminSummaries.take(10),
                                    onViewAll = { navController.navigate("pengawas_reports") },
                                    adminPhotosMap = adminPhotosMap,
                                    isDark = isDark
                                )
                            }
                        }
                    }

                    // =========================================================
                    // TODAY'S EVENTS
                    // =========================================================
                    item {
                        AnimatedVisibility(
                            visible = isVisible,
                            enter = fadeIn(tween(400, delayMillis = 500)) + slideInVertically(initialOffsetY = { 20 })
                        ) {
                            TodayEventsSection(
                                nasabahBaruCount = nasabahBaru.size,
                                nasabahLunasCount = nasabahLunas.size,
                                pembayaranCount = pembayaranHarian.filter { !it.isPencairan }.size,
                                pencairanCount = pembayaranHarian.filter { it.isPencairan }.size,
                                isDark = isDark
                            )
                        }
                    }

                    // =========================================================
                    // RECENT PAYMENTS
                    // =========================================================
                    if (pembayaranHarian.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(400, delayMillis = 600)) + slideInVertically(initialOffsetY = { 20 })
                            ) {
                                RecentPaymentsSection(
                                    payments = pembayaranHarian.take(5),
                                    onViewAll = { navController.navigate("pengawas_reports") },
                                    isDark = isDark
                                )
                            }
                        }
                    }

                    // =========================================================
                    // LACAK LOKASI BUTTON
                    // =========================================================
                    item {
                        Surface(
                            onClick = { navController.navigate("pengawas_tracking") },
                            color = PengawasColors.getCard(isDark),
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFE53935).copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.MyLocation,
                                        contentDescription = "Lacak Lokasi",
                                        tint = Color(0xFFE53935),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Lacak Lokasi",
                                        fontWeight = FontWeight.SemiBold,
                                        color = PengawasColors.getTextPrimary(isDark)
                                    )
                                    Text(
                                        "Lacak posisi admin, pimpinan, koordinator",
                                        fontSize = 12.sp,
                                        color = PengawasColors.getTextMuted(isDark)
                                    )
                                }
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = PengawasColors.getTextMuted(isDark)
                                )
                            }
                        }
                    }

                    // Spacer for bottom navigation
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
        // Dialog Kirim Broadcast
        if (showBroadcastDialog) {
            BroadcastDialog(
                onDismiss = { showBroadcastDialog = false },
                isDark = isDark,
                onSend = { title, message ->
                    viewModel.sendBroadcastMessage(
                        title = title,
                        message = message,
                        onSuccess = {
                            showBroadcastDialog = false
                            // Toast success
                        },
                        onFailure = { e ->
                            // Toast error
                        }
                    )
                }
            )
        }
    }
}

// =============================================================================
// CABANG FILTER SECTION
// =============================================================================
@Composable
private fun CabangFilterSection(
    cabangOptions: List<Pair<String?, String>>,
    selectedCabangId: String?,
    isDark: Boolean = false,
    onCabangSelected: (String?) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Filter Cabang",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = PengawasColors.getTextSecondary(isDark)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(cabangOptions) { (cabangId, cabangName) ->
                val isSelected = selectedCabangId == cabangId

                FilterChip(
                    selected = isSelected,
                    onClick = { onCabangSelected(cabangId) },
                    label = {
                        Text(
                            text = cabangName,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PengawasColors.primary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = PengawasColors.getBorder(isDark),
                        selectedBorderColor = PengawasColors.primary,
                        enabled = true,
                        selected = isSelected
                    )
                )
            }
        }
    }
}

// =============================================================================
// SUMMARY CARDS SECTION
// =============================================================================
@Composable
private fun SummaryCardsSection(summary: PengawasCabangSummary, isDark: Boolean = false) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Row 1: Nasabah Overview
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModernPengawasStatCard(
                title = "Total Saldo",
                value = formatRupiah(summary.totalPiutang.toInt()),
                icon = Icons.Rounded.AccountBalance,
                gradient = PengawasColors.infoGradient,
                modifier = Modifier.weight(1f),
                isDark = isDark
            )
            ModernPengawasStatCard(
                title = "Nasabah Aktif",
                value = formatNumber(summary.nasabahAktif),
                icon = Icons.Rounded.PersonPin,
                gradient = PengawasColors.successGradient,
                modifier = Modifier.weight(1f),
                isDark = isDark
            )
        }

        // Row 2: Today's Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModernPengawasStatCard(
                title = "Drop Hari Ini",
                value = formatNumber(summary.nasabahBaruHariIni),
                icon = Icons.Rounded.PersonAdd,
                gradient = PengawasColors.purpleGradient,
                modifier = Modifier.weight(1f),
                isDark = isDark
            )
            ModernPengawasStatCard(
                title = "Lunas Hari Ini",
                value = formatNumber(summary.nasabahLunasHariIni),
                icon = Icons.Rounded.CheckCircle,
                gradient = PengawasColors.orangeGradient,
                modifier = Modifier.weight(1f),
                isDark = isDark
            )
        }
    }
}

@Composable
private fun ModernPengawasStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = gradient.first().copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    color = PengawasColors.getTextSecondary(isDark)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = value,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PengawasColors.getTextPrimary(isDark)
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        Brush.linearGradient(gradient),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// =============================================================================
// PERFORMANCE OVERVIEW CARD
// =============================================================================
@Composable
private fun PerformanceOverviewCard(summary: PengawasCabangSummary, isDark: Boolean = false) {
    val isHoliday = summary.isHoliday()
    val performance = summary.performancePercentage / 100f
    val performanceColor = getPengawasPerformanceColor(performance)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kinerja Hari Ini",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PengawasColors.getTextPrimary(isDark)
                )

                if (isHoliday) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PengawasColors.getTextMuted(isDark).copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = "LIBUR",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PengawasColors.getTextSecondary(isDark)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = performanceColor.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = "${summary.performancePercentage.toInt()}%",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = performanceColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { if (isHoliday) 0f else performance.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = if (isHoliday) PengawasColors.getTextMuted(isDark) else performanceColor,
                trackColor = PengawasColors.getBorder(isDark)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Terkumpul",
                        fontSize = 12.sp,
                        color = PengawasColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(summary.pembayaranHariIni.toInt()),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PengawasColors.success
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Target",
                        fontSize = 12.sp,
                        color = PengawasColors.getTextMuted(isDark)
                    )
                    Text(
                        text = if (isHoliday) "LIBUR" else formatRupiah(summary.targetHariIni.toInt()),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PengawasColors.getTextPrimary(isDark)
                    )
                }
            }
        }
    }
}

// =============================================================================
// CABANG PERFORMANCE SECTION
// =============================================================================
@Composable
private fun CabangPerformanceSection(cabangSummaries: List<PengawasCabangSummary>, isDark: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Performa Per Cabang",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PengawasColors.getTextPrimary(isDark)
            )

            cabangSummaries.sortedByDescending { it.performancePercentage }.forEach { cabang ->
                CabangPerformanceItem(cabang, isDark = isDark)
            }
        }
    }
}

@Composable
private fun CabangPerformanceItem(cabang: PengawasCabangSummary, isDark: Boolean = false) {
    val isHoliday = cabang.isHoliday()
    val performance = cabang.performancePercentage / 100f
    val performanceColor = getPengawasPerformanceColor(performance)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PengawasColors.getBackground(isDark), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = cabang.cabangName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PengawasColors.getTextPrimary(isDark)
            )
            Text(
                text = if (isHoliday) "LIBUR" else "${cabang.performancePercentage.toInt()}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHoliday) PengawasColors.getTextMuted(isDark) else performanceColor
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { if (isHoliday) 0f else performance.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (isHoliday) PengawasColors.getTextMuted(isDark) else performanceColor,
            trackColor = PengawasColors.getBorder(isDark)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${cabang.nasabahAktif} aktif",
                fontSize = 11.sp,
                color = PengawasColors.getTextMuted(isDark)
            )
            Text(
                text = formatRupiah(cabang.pembayaranHariIni.toInt()),
                fontSize = 11.sp,
                color = PengawasColors.getTextMuted(isDark)
            )
        }
    }
}

// =============================================================================
// ADMIN PERFORMANCE SECTION
// =============================================================================
@Composable
private fun AdminPerformanceSection(
    adminSummaries: List<AdminSummary>,
    onViewAll: () -> Unit,
    adminPhotosMap: Map<String, String?> = emptyMap(),
    isDark: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Top PDL",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PengawasColors.getTextPrimary(isDark)
                )

                TextButton(onClick = onViewAll) {
                    Text(
                        text = "Lihat Semua",
                        color = PengawasColors.primary
                    )
                }
            }

            adminSummaries.forEachIndexed { index, admin ->
                AdminPerformanceItem(
                    rank = index + 1,
                    admin = admin,
                    isDark = isDark,
                    photoUrl = adminPhotosMap[admin.adminId]  // ✅ BARU
                )
            }
        }
    }
}

@Composable
private fun AdminPerformanceItem(
    rank: Int,
    admin: AdminSummary,
    isDark: Boolean = false,
    photoUrl: String? = null  // ✅ BARU
) {
    val context = LocalContext.current  // ✅ BARU
    val isHoliday = admin.isHoliday()
    val performance = admin.performancePercentage / 100f
    val performanceColor = getPengawasPerformanceColor(performance)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KoordinatorColors.getBackground(isDark), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rank badge
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    when (rank) {
                        1 -> Color(0xFFFFD700)
                        2 -> Color(0xFFC0C0C0)
                        3 -> Color(0xFFCD7F32)
                        else -> KoordinatorColors.getBorder(isDark)
                    },
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rank.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) Color.White else KoordinatorColors.getTextPrimary(isDark)
            )
        }

        // ✅ BARU: Avatar dengan foto profil
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(KoordinatorColors.primaryGradient),
                    CircleShape
                )
                .border(
                    width = 1.5.dp,
                    color = KoordinatorColors.primary.copy(alpha = 0.3f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!photoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(photoUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Foto Admin",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = admin.adminName.take(2).uppercase().ifEmpty { "AD" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = admin.adminName.ifBlank { admin.adminEmail },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = KoordinatorColors.getTextPrimary(isDark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${admin.nasabahAktif} nasabah",
                fontSize = 11.sp,
                color = KoordinatorColors.getTextMuted(isDark)
            )
        }

        Text(
            text = if (isHoliday) "LIBUR" else "${admin.performancePercentage.toInt()}%",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isHoliday) KoordinatorColors.getTextMuted(isDark) else performanceColor
        )
    }
}

// =============================================================================
// TODAY'S EVENTS SECTION
// =============================================================================
@Composable
private fun TodayEventsSection(
    nasabahBaruCount: Int,
    nasabahLunasCount: Int,
    pembayaranCount: Int,
    pencairanCount: Int,
    isDark: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Event Hari Ini",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PengawasColors.getTextPrimary(isDark)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EventStatItem(
                    icon = Icons.Rounded.PersonAdd,
                    value = nasabahBaruCount.toString(),
                    label = "Drop",
                    color = PengawasColors.teal,
                    isDark = isDark
                )
                EventStatItem(
                    icon = Icons.Rounded.CheckCircle,
                    value = nasabahLunasCount.toString(),
                    label = "Lunas",
                    color = PengawasColors.success,
                    isDark = isDark
                )
                EventStatItem(
                    icon = Icons.Rounded.Payments,
                    value = pembayaranCount.toString(),
                    label = "Bayar",
                    color = PengawasColors.info,
                    isDark = isDark
                )
                EventStatItem(
                    icon = Icons.Rounded.MonetizationOn,
                    value = pencairanCount.toString(),
                    label = "Pinjaman",
                    color = PengawasColors.warning,
                    isDark = isDark
                )
            }
        }
    }
}

@Composable
private fun EventStatItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color,
    isDark: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(color.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = PengawasColors.getTextPrimary(isDark)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = PengawasColors.getTextMuted(isDark)
        )
    }
}

// =============================================================================
// RECENT PAYMENTS SECTION
// =============================================================================
@Composable
private fun RecentPaymentsSection(
    payments: List<PembayaranHarianItem>,
    onViewAll: () -> Unit,
    isDark: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pembayaran Terbaru",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PengawasColors.getTextPrimary(isDark)
                )

                TextButton(onClick = onViewAll) {
                    Text(
                        text = "Lihat Semua",
                        color = PengawasColors.primary
                    )
                }
            }

            payments.forEach { payment ->
                PaymentItem(payment = payment, isDark = isDark)
            }
        }
    }
}

@Composable
private fun PaymentItem(payment: PembayaranHarianItem, isDark: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PengawasColors.getBackground(isDark), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        when (payment.jenis) {
                            "pencairan" -> PengawasColors.warning.copy(alpha = 0.1f)
                            "tambah_bayar" -> PengawasColors.purple.copy(alpha = 0.1f)
                            else -> PengawasColors.success.copy(alpha = 0.1f)
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (payment.jenis) {
                        "pencairan" -> Icons.Rounded.MonetizationOn
                        "tambah_bayar" -> Icons.Rounded.Add
                        else -> Icons.Rounded.Payments
                    },
                    contentDescription = null,
                    tint = when (payment.jenis) {
                        "pencairan" -> PengawasColors.warning
                        "tambah_bayar" -> PengawasColors.purple
                        else -> PengawasColors.success
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Text(
                    text = payment.namaPanggilan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PengawasColors.getTextPrimary(isDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${payment.jenisDisplay} • ${payment.adminName}",
                    fontSize = 12.sp,
                    color = PengawasColors.getTextMuted(isDark)
                )
            }
        }

        Text(
            text = if (payment.isPencairan) "-${formatRupiah(payment.jumlah.toInt())}"
            else "+${formatRupiah(payment.jumlah.toInt())}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (payment.isPencairan) PengawasColors.danger else PengawasColors.success
        )
    }
}

@Composable
private fun BroadcastDialog(
    onDismiss: () -> Unit,
    isDark: Boolean = false,
    onSend: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PengawasColors.getCard(isDark),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Rounded.Campaign,
                    contentDescription = null,
                    tint = PengawasColors.primary
                )
                Text(
                    "Kirim Broadcast",
                    fontWeight = FontWeight.Bold,
                    color = PengawasColors.getTextPrimary(isDark)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Pesan akan dikirim ke semua Koordinator, Pimpinan, dan Admin Lapangan",
                    style = MaterialTheme.typography.bodySmall,
                    color = PengawasColors.getTextSecondary(isDark)
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Judul *") },
                    placeholder = { Text("Contoh: Pengumuman Penting") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Isi Pesan *") },
                    placeholder = { Text("Contoh: Hati-hati di perjalanan karena cuaca kurang baik...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(title, message) },
                enabled = title.isNotBlank() && message.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = PengawasColors.primary)
            ) {
                Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Kirim")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

// =============================================================================
// HELPER FUNCTIONS
// =============================================================================
private fun getPengawasPerformanceColor(performance: Float): Color {
    return when {
        performance >= 0.8f -> PengawasColors.success
        performance >= 0.6f -> PengawasColors.warning
        performance >= 0.4f -> Color(0xFFF97316)
        else -> PengawasColors.danger
    }
}

private fun formatNumber(number: Int): String {
    return NumberFormat.getNumberInstance(Locale("id", "ID")).format(number)
}

private fun formatRupiahShort(amount: Long): String {
    return when {
        amount >= 1_000_000_000 -> String.format("%.1fM", amount / 1_000_000_000.0)
        amount >= 1_000_000 -> String.format("%.1fjt", amount / 1_000_000.0)
        amount >= 1_000 -> String.format("%.0frb", amount / 1_000.0)
        else -> amount.toString()
    }
}

// =========================================================================
// BROADCAST COMPONENTS FOR PENGAWAS
// =========================================================================

@Composable
private fun PengawasBroadcastBanner(
    broadcasts: List<BroadcastMessage>,
    isDark: Boolean = false,
    onDelete: (BroadcastMessage) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Broadcast Aktif",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = PengawasColors.getTextPrimary(isDark)
            )
            Text(
                text = "${broadcasts.size} pesan",
                fontSize = 12.sp,
                color = PengawasColors.getTextSecondary(isDark)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        broadcasts.forEach { broadcast ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF3D3200) else Color(0xFFFFF8E1)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Icon
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(if (isDark) Color(0xFF5C4A00) else Color(0xFFFFE0B2), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Campaign,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Content
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = broadcast.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = PengawasColors.getTextPrimary(isDark)
                        )
                        Text(
                            text = broadcast.message,
                            fontSize = 13.sp,
                            color = PengawasColors.getTextSecondary(isDark),
                            lineHeight = 18.sp
                        )
                    }

                    // Delete button (hanya untuk Pengawas)
                    IconButton(
                        onClick = { onDelete(broadcast) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Hapus",
                            tint = PengawasColors.getTextMuted(isDark),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BroadcastSendDialog(
    onDismiss: () -> Unit,
    onSend: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFFFFE0B2), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Campaign,
                        contentDescription = null,
                        tint = Color(0xFFFF9800)
                    )
                }
                Text(
                    "Kirim Broadcast",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Pesan akan dikirim ke semua Koordinator, Pimpinan, dan Admin Lapangan",
                    fontSize = 13.sp,
                    color = Color.Gray
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Judul Broadcast *") },
                    placeholder = { Text("Contoh: Pengumuman Penting") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isSending
                )

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Isi Pesan *") },
                    placeholder = { Text("Contoh: Hati-hati di perjalanan karena cuaca kurang baik...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isSending
                )

                // Info
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE3F2FD)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = Color(0xFF1976D2),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "Broadcast akan otomatis hilang setelah 24 jam",
                            fontSize = 12.sp,
                            color = Color(0xFF1976D2)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSending = true
                    onSend(title, message)
                },
                enabled = title.isNotBlank() && message.isNotBlank() && !isSending,
                colors = ButtonDefaults.buttonColors(containerColor = PengawasColors.primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.Rounded.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isSending) "Mengirim..." else "Kirim")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSending
            ) {
                Text("Batal")
            }
        }
    )
}