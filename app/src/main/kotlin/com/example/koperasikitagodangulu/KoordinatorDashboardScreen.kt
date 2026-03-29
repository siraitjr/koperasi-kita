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
import com.example.koperasikitagodangulu.models.BroadcastMessage
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.systemuicontroller.rememberSystemUiController

/**
 * =========================================================================
 * KOORDINATOR DASHBOARD SCREEN - FIXED VERSION
 * =========================================================================
 *
 * PERBAIKAN:
 * 1. Menggunakan StateFlow reaktif (collectAsState) untuk derived state
 * 2. LaunchedEffect dengan key yang benar
 * 3. State initialization yang proper
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoordinatorDashboardScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val isLoading by viewModel.isLoading.collectAsState()

    // =========================================================================
    // ✅ DARK MODE SUPPORT
    // =========================================================================
    val isDark by viewModel.isDarkMode
    val coroutineScope = rememberCoroutineScope()
    val systemUiController = rememberSystemUiController()
    val bgColor = KoordinatorColors.getBackground(isDark)
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
    val koordinatorDataLoaded by viewModel.pengawasDataLoaded.collectAsState()

    // =========================================================================
    // ✅ FIX #2: Collect DERIVED StateFlow (bukan memanggil fungsi!)
    // Sekarang ini REAKTIF - otomatis update saat data/filter berubah
    // =========================================================================
    val cabangOptions by viewModel.pengawasCabangOptions.collectAsState()
    val currentSummary by viewModel.pengawasCurrentSummary.collectAsState()
    val filteredAdminSummaries by viewModel.pengawasFilteredAdminSummaries.collectAsState()
    val activeBroadcasts by viewModel.activeBroadcasts.collectAsState()
    // ✅ BARU: Foto admin lapangan
    val adminPhotosMap by viewModel.adminPhotosMap.collectAsState()

    // =========================================================================
    // ✅ FIX #3: LaunchedEffect dengan koordinatorDataLoaded sebagai trigger
    // =========================================================================
    LaunchedEffect(Unit) {
        try {
            if (!koordinatorDataLoaded) {
                Log.d("KoordinatorDashboard", "🚀 Initial data load triggered")
                viewModel.loadPengawasAllCabangData()
            }
            viewModel.loadActiveBroadcasts()
        } catch (e: Exception) {
            Log.e("KoordinatorDashboard", "Error loading data: ${e.message}")
        }
    }

    // Visibility state untuk animasi
    var isVisible by remember { mutableStateOf(false) }

    // ✅ FIX #4: isVisible berdasarkan data yang sudah loaded
    LaunchedEffect(koordinatorDataLoaded, allCabangSummaries) {
        if (koordinatorDataLoaded && allCabangSummaries.isNotEmpty()) {
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
        containerColor = KoordinatorColors.getBackground(isDark),
        topBar = {
            KoordinatorTopBar(
                title = "Dashboard",
                navController = navController,
                viewModel = viewModel,
                onRefresh = { handleRefresh() }
            )
        },
        bottomBar = {
            KoordinatorBottomNavigation(navController, "koordinator_dashboard", isDark = isDark)
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
                            color = KoordinatorColors.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Memuat data...",
                            color = KoordinatorColors.getTextSecondary(isDark)
                        )
                    }
                }
            }

            // Empty state (data loaded tapi kosong)
            koordinatorDataLoaded && allCabangSummaries.isEmpty() -> {
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
                            tint = KoordinatorColors.getTextMuted(isDark),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "Data tidak tersedia",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = KoordinatorColors.getTextPrimary(isDark)
                        )
                        Text(
                            text = "Tekan refresh untuk memuat ulang",
                            color = KoordinatorColors.getTextSecondary(isDark)
                        )
                        Button(
                            onClick = { handleRefresh() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KoordinatorColors.primary
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
                                KoordinatorBroadcastBanner(broadcasts = activeBroadcasts, isDark = isDark)
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
                                isDark = isDark,  // ✅ TAMBAHKAN INI
                                cabangOptions = cabangOptions,
                                selectedCabangId = selectedCabangId,
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
                                    isDark = isDark,
                                    cabangSummaries = allCabangSummaries.values.toList()
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
                                    isDark = isDark,
                                    adminSummaries = filteredAdminSummaries.take(10),
                                    adminPhotosMap = adminPhotosMap,  // ✅ BARU
                                    onViewAll = { navController.navigate("koordinator_reports") }
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
                                isDark = isDark,
                                nasabahBaruCount = nasabahBaru.size,
                                nasabahLunasCount = nasabahLunas.size,
                                pembayaranCount = pembayaranHarian.filter { !it.isPencairan }.size,
                                pencairanCount = pembayaranHarian.filter { it.isPencairan }.size
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
                                    isDark = isDark,
                                    payments = pembayaranHarian.take(5),
                                    onViewAll = { navController.navigate("koordinator_reports") }
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
    }
}

// =============================================================================
// CABANG FILTER SECTION
// =============================================================================
@Composable
private fun CabangFilterSection(
    isDark: Boolean = false,
    cabangOptions: List<Pair<String?, String>>,
    selectedCabangId: String?,
    onCabangSelected: (String?) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Filter Cabang",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = KoordinatorColors.getTextSecondary(isDark)
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
                        selectedContainerColor = KoordinatorColors.primary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = KoordinatorColors.getBorder(isDark),
                        selectedBorderColor = KoordinatorColors.primary,
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
            ModernKoordinatorStatCard(
                isDark = isDark,
                title = "Total Saldo",
                value = formatRupiah(summary.totalPiutang.toInt()),
                icon = Icons.Rounded.AccountBalance,
                gradient = PengawasColors.infoGradient,
                modifier = Modifier.weight(1f)
            )
            ModernKoordinatorStatCard(
                isDark = isDark,
                title = "Nasabah Aktif",
                value = formatNumber(summary.nasabahAktif),
                icon = Icons.Rounded.PersonPin,
                gradient = PengawasColors.successGradient,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Today's Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModernKoordinatorStatCard(
                isDark = isDark,
                title = "Drop Hari Ini",
                value = formatNumber(summary.nasabahBaruHariIni),
                icon = Icons.Rounded.PersonAdd,
                gradient = PengawasColors.purpleGradient,
                modifier = Modifier.weight(1f)
            )
            ModernKoordinatorStatCard(
                isDark = isDark,
                title = "Lunas Hari Ini",
                value = formatNumber(summary.nasabahLunasHariIni),
                icon = Icons.Rounded.CheckCircle,
                gradient = PengawasColors.orangeGradient,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModernKoordinatorStatCard(
    isDark: Boolean = false,
    title: String,
    value: String,
    icon: ImageVector,
    gradient: List<Color>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = gradient.first().copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
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
                    color = KoordinatorColors.getTextSecondary(isDark)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = value,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = KoordinatorColors.getTextPrimary(isDark)
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
    val performanceColor = getKoordinatorPerformanceColor(performance)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
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
                    color = KoordinatorColors.getTextPrimary(isDark)
                )

                if (isHoliday) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = KoordinatorColors.getTextMuted(isDark).copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = "LIBUR",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = KoordinatorColors.getTextSecondary(isDark)
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
                color = if (isHoliday) KoordinatorColors.getTextMuted(isDark) else performanceColor,
                trackColor = KoordinatorColors.getBorder(isDark)
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
                        color = KoordinatorColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(summary.pembayaranHariIni.toInt()),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KoordinatorColors.success
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Target",
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextMuted(isDark)
                    )
                    Text(
                        text = if (isHoliday) "LIBUR" else formatRupiah(summary.targetHariIni.toInt()),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KoordinatorColors.getTextPrimary(isDark)
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
private fun CabangPerformanceSection(isDark: Boolean = false, cabangSummaries: List<PengawasCabangSummary>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Performa Per Cabang",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = KoordinatorColors.getTextPrimary(isDark)
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
    val performanceColor = getKoordinatorPerformanceColor(performance)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KoordinatorColors.getBackground(isDark), RoundedCornerShape(12.dp))
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
                color = KoordinatorColors.getTextPrimary(isDark)
            )
            Text(
                text = if (isHoliday) "LIBUR" else "${cabang.performancePercentage.toInt()}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHoliday) KoordinatorColors.getTextMuted(isDark) else performanceColor
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { if (isHoliday) 0f else performance.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (isHoliday) KoordinatorColors.getTextMuted(isDark) else performanceColor,
            trackColor = KoordinatorColors.getBorder(isDark)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${cabang.nasabahAktif} aktif",
                fontSize = 11.sp,
                color = KoordinatorColors.getTextMuted(isDark)
            )
            Text(
                text = formatRupiah(cabang.pembayaranHariIni.toInt()),
                fontSize = 11.sp,
                color = KoordinatorColors.getTextMuted(isDark)
            )
        }
    }
}

// =============================================================================
// ADMIN PERFORMANCE SECTION
// =============================================================================
@Composable
private fun AdminPerformanceSection(
    isDark: Boolean = false,
    adminSummaries: List<AdminSummary>,
    adminPhotosMap: Map<String, String?> = emptyMap(),  // ✅ BARU
    onViewAll: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
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
                    color = KoordinatorColors.getTextPrimary(isDark)
                )

                TextButton(onClick = onViewAll) {
                    Text(
                        text = "Lihat Semua",
                        color = KoordinatorColors.primary
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
    val performanceColor = getKoordinatorPerformanceColor(performance)

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
                    Brush.linearGradient(PengawasColors.primaryGradient),
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
                text = admin.adminName.ifBlank { admin.adminName },
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
    isDark: Boolean = false,
    nasabahBaruCount: Int,
    nasabahLunasCount: Int,
    pembayaranCount: Int,
    pencairanCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "Event Hari Ini",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = KoordinatorColors.getTextPrimary(isDark)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EventStatItem(
                    isDark = isDark,
                    icon = Icons.Rounded.PersonAdd,
                    value = nasabahBaruCount.toString(),
                    label = "Drop",
                    color = PengawasColors.teal
                )
                EventStatItem(
                    isDark = isDark,
                    icon = Icons.Rounded.CheckCircle,
                    value = nasabahLunasCount.toString(),
                    label = "Lunas",
                    color = KoordinatorColors.success
                )
                EventStatItem(
                    isDark = isDark,
                    icon = Icons.Rounded.Payments,
                    value = pembayaranCount.toString(),
                    label = "Bayar",
                    color = KoordinatorColors.info
                )
                EventStatItem(
                    isDark = isDark,
                    icon = Icons.Rounded.MonetizationOn,
                    value = pencairanCount.toString(),
                    label = "Pinjaman",
                    color = KoordinatorColors.warning
                )
            }
        }
    }
}

@Composable
private fun EventStatItem(
    isDark: Boolean = false,
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
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
            color = KoordinatorColors.getTextPrimary(isDark)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = KoordinatorColors.getTextMuted(isDark)
        )
    }
}

// =============================================================================
// RECENT PAYMENTS SECTION
// =============================================================================
@Composable
private fun RecentPaymentsSection(
    isDark: Boolean = false,
    payments: List<PembayaranHarianItem>,
    onViewAll: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
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
                    color = KoordinatorColors.getTextPrimary(isDark)
                )

                TextButton(onClick = onViewAll) {
                    Text(
                        text = "Lihat Semua",
                        color = KoordinatorColors.primary
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
            .background(KoordinatorColors.getBackground(isDark), RoundedCornerShape(12.dp))
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
                            "pencairan" -> KoordinatorColors.warning.copy(alpha = 0.1f)
                            "tambah_bayar" -> PengawasColors.purple.copy(alpha = 0.1f)
                            else -> KoordinatorColors.success.copy(alpha = 0.1f)
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
                        "pencairan" -> KoordinatorColors.warning
                        "tambah_bayar" -> PengawasColors.purple
                        else -> KoordinatorColors.success
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Text(
                    text = payment.namaPanggilan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = KoordinatorColors.getTextPrimary(isDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${payment.jenisDisplay} • ${payment.adminName}",
                    fontSize = 12.sp,
                    color = KoordinatorColors.getTextMuted(isDark)
                )
            }
        }

        Text(
            text = if (payment.isPencairan) "-${formatRupiah(payment.jumlah.toInt())}"
            else "+${formatRupiah(payment.jumlah.toInt())}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (payment.isPencairan) KoordinatorColors.danger else KoordinatorColors.success
        )
    }
}

// =============================================================================
// HELPER FUNCTIONS
// =============================================================================
private fun getKoordinatorPerformanceColor(performance: Float): Color {
    return when {
        performance >= 0.8f -> KoordinatorColors.success
        performance >= 0.6f -> KoordinatorColors.warning
        performance >= 0.4f -> Color(0xFFF97316)
        else -> KoordinatorColors.danger
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
// BROADCAST BANNER FOR KOORDINATOR
// =========================================================================

@Composable
private fun KoordinatorBroadcastBanner(broadcasts: List<BroadcastMessage>, isDark: Boolean = false) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                            .background(if (isDark) Color(0xFF5C4400) else Color(0xFFFFE0B2), CircleShape),
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
                            color = Color(0xFF1E293B)
                        )
                        Text(
                            text = broadcast.message,
                            fontSize = 13.sp,
                            color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF424242),
                            lineHeight = 18.sp
                        )
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF757575),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = broadcast.senderName,
                                fontSize = 11.sp,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF757575)
                            )
                        }
                    }
                }
            }
        }
    }
}