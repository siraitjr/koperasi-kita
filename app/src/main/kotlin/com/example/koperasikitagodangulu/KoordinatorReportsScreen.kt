package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.animation.*
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import com.example.koperasikitagodangulu.models.NasabahBaruItem
import com.example.koperasikitagodangulu.models.NasabahLunasItem
import java.text.NumberFormat
import java.util.*
import com.example.koperasikitagodangulu.models.BiayaAwalItem
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * =========================================================================
 * KOORDINATOR REPORTS SCREEN - FIXED VERSION
 * =========================================================================
 *
 * PERBAIKAN:
 * 1. Menggunakan StateFlow reaktif (collectAsState) untuk derived state
 * 2. Data langsung update saat filter berubah
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoordinatorReportsScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    // =========================================================================
    // ✅ FIX: Collect StateFlow untuk data mentah
    // =========================================================================
    val selectedCabangId by viewModel.pengawasSelectedCabangId.collectAsState()

    // =========================================================================
    // ✅ DARK MODE SUPPORT
    // =========================================================================
    val isDark by viewModel.isDarkMode
    val pembayaranHarian by viewModel.pengawasPembayaranHarian.collectAsState()
    val nasabahBaru by viewModel.pengawasNasabahBaru.collectAsState()
    val nasabahLunas by viewModel.pengawasNasabahLunas.collectAsState()
    val biayaAwalList by viewModel.pengawasBiayaAwal.collectAsState()
    val kasirUangKasList by viewModel.pengawasKasirUangKas.collectAsState()
    val systemUiController = rememberSystemUiController()
    val bgColor = KoordinatorColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // =========================================================================
    // ✅ FIX: Collect DERIVED StateFlow (bukan memanggil fungsi!)
    // Sekarang ini REAKTIF - otomatis update saat data/filter berubah
    // =========================================================================
    val cabangOptions by viewModel.pengawasCabangOptions.collectAsState()
    val currentSummary by viewModel.pengawasCurrentSummary.collectAsState()
    val filteredAdminSummaries by viewModel.pengawasFilteredAdminSummaries.collectAsState()
    // ✅ BARU: Foto admin lapangan
    val adminPhotosMap by viewModel.adminPhotosMap.collectAsState()

    // Tab state
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        KoordinatorReportTab("PDL", Icons.Rounded.Person),
        KoordinatorReportTab("Pembayaran", Icons.Rounded.Payments),
        KoordinatorReportTab("Drop", Icons.Rounded.PersonAdd),
        KoordinatorReportTab("Kasbon & Titipan", Icons.Rounded.AccountBalance),
        KoordinatorReportTab("Lunas", Icons.Rounded.CheckCircle),
        KoordinatorReportTab("Status Khusus", Icons.Rounded.Flag),
        KoordinatorReportTab("Pencairan", Icons.Rounded.Savings),
        KoordinatorReportTab("Semua Nasabah", Icons.Rounded.People)
    )

    // ✅ BARU: Load foto admin saat filteredAdminSummaries tersedia
    LaunchedEffect(filteredAdminSummaries) {
        if (filteredAdminSummaries.isNotEmpty()) {
            val adminUids = filteredAdminSummaries.map { it.adminId }
            viewModel.loadAdminPhotosForCabang(adminUids)
        }
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        containerColor = KoordinatorColors.getBackground(isDark),
        topBar = {
            KoordinatorTopBar(
                title = "Laporan",
                navController = navController,
                viewModel = viewModel,
                onRefresh = { viewModel.refreshPengawasData() }
            )
        },
        bottomBar = {
            KoordinatorBottomNavigation(navController, "koordinator_reports", isDark = isDark)
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // =========================================================
            // PEMBUKUAN CARD
            // =========================================================
            PembukuanCard(isDark = isDark) {
                coroutineScope.launch {
                    val baseUrl = "https://www.koperasi-kita.com/pembukuan"
                    val currentUser = FirebaseAuth.getInstance().currentUser
                    val url = if (currentUser != null) {
                        try {
                            val idToken = currentUser.getIdToken(false).await().token
                            if (idToken != null) "$baseUrl?idToken=${Uri.encode(idToken)}"
                            else baseUrl
                        } catch (_: Exception) { baseUrl }
                    } else { baseUrl }
                    withContext(Dispatchers.Main) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            setPackage("com.android.chrome")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                }
            }

            // =========================================================
            // CABANG FILTER
            // =========================================================
            CabangFilterBar(
                isDark = isDark,
                cabangOptions = cabangOptions,
                selectedCabangId = selectedCabangId,
                onCabangSelected = { viewModel.setPengawasSelectedCabang(it) }
            )

            // =========================================================
            // TAB ROW
            // =========================================================
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = KoordinatorColors.getCard(isDark),
                contentColor = KoordinatorColors.primary,
                edgePadding = 16.dp,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        height = 3.dp,
                        color = KoordinatorColors.primary
                    )
                }
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = tab.title,
                                    fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        },
                        selectedContentColor = KoordinatorColors.primary,
                        unselectedContentColor = KoordinatorColors.getTextMuted(isDark)
                    )
                }
            }

            // =========================================================
            // TAB CONTENT - Sekarang data REAKTIF!
            // =========================================================
            when (selectedTab) {
                0 -> AdminPerformanceTab(
                    isDark = isDark,
                    adminSummaries = filteredAdminSummaries,
                    currentSummary = currentSummary,
                    adminPhotosMap = adminPhotosMap  // ✅ BARU
                )
                1 -> PembayaranTab(
                    isDark = isDark,
                    pembayaranList = pembayaranHarian,
                    currentSummary = currentSummary
                )
                2 -> NasabahBaruTab(
                    isDark = isDark,
                    nasabahBaruList = nasabahBaru,
                    currentSummary = currentSummary
                )
                3 -> KasbonTitipanMalamTab(
                    isDark = isDark,
                    biayaAwalList = biayaAwalList,
                    kasirUangKasList = kasirUangKasList,
                    pembayaranList = pembayaranHarian,
                    nasabahBaruList = nasabahBaru
                )
                4 -> NasabahLunasTab(
                    isDark = isDark,
                    nasabahLunasList = nasabahLunas,
                    currentSummary = currentSummary
                )
                5 -> StatusKhususNavigationCard(
                    isDark = isDark,
                    onClick = { navController.navigate("koordinatorDaftarStatusKhusus") }
                )
                6 -> MenungguPencairanNavigationCard(
                    isDark = isDark,
                    onClick = { navController.navigate("koordinatorDaftarMenungguPencairan") }
                )
                7 -> SemuaNasabahNavigationCard(
                    isDark = isDark,
                    onClick = { navController.navigate("koordinator_daftar_semua_nasabah") }
                )
            }
        }
    }
}

data class KoordinatorReportTab(
    val title: String,
    val icon: ImageVector
)

// =============================================================================
// CABANG FILTER BAR
// =============================================================================
@Composable
private fun CabangFilterBar(
    isDark: Boolean = false,
    cabangOptions: List<Pair<String?, String>>,
    selectedCabangId: String?,
    onCabangSelected: (String?) -> Unit
) {
    Surface(
        color = KoordinatorColors.getCard(isDark),  // ✅ FIX
        shadowElevation = 2.dp
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = KoordinatorColors.primary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    )
                )
            }
        }
    }
}

// =============================================================================
// TAB 1: ADMIN PERFORMANCE
// =============================================================================
@Composable
private fun AdminPerformanceTab(
    isDark: Boolean = false,
    adminSummaries: List<AdminSummary>,
    currentSummary: PengawasCabangSummary,
    adminPhotosMap: Map<String, String?> = emptyMap()  // ✅ BARU
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Summary card
        item {
            AdminSummaryCard(
                isDark = isDark,
                totalAdmin = adminSummaries.size,
                totalNasabahAktif = adminSummaries.sumOf { it.nasabahAktif },
                totalPembayaran = adminSummaries.sumOf { it.pembayaranHariIni },
                totalTarget = adminSummaries.sumOf { it.targetHariIni }
            )
        }

        // Admin list
        if (adminSummaries.isEmpty()) {
            item {
                EmptyContent(
                    isDark = isDark,
                    icon = Icons.Rounded.Person,
                    message = "Tidak ada data PDL"
                )
            }
        } else {
            items(adminSummaries) { admin ->
                AdminDetailCard(
                    admin = admin,
                    isDark = isDark,
                    photoUrl = adminPhotosMap[admin.adminId]  // ✅ BARU
                )
            }
        }
    }
}

@Composable
private fun AdminSummaryCard(
    isDark: Boolean = false,
    totalAdmin: Int,
    totalNasabahAktif: Int,
    totalPembayaran: Long,
    totalTarget: Long
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = KoordinatorColors.getCard(isDark)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Ringkasan PDL",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = KoordinatorColors.getTextPrimary(isDark)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MiniStatItem(
                    value = totalAdmin.toString(),
                    label = "PDL",
                    color = KoordinatorColors.primary
                )
                MiniStatItem(
                    value = formatNumber(totalNasabahAktif),
                    label = "Nasabah",
                    color = KoordinatorColors.info
                )
                MiniStatItem(
                    value = formatRupiah(totalPembayaran. toInt()),
                    label = "Terkumpul",
                    color = KoordinatorColors.success
                )
                MiniStatItem(
                    value = formatRupiah(totalTarget.toInt()),
                    label = "Target",
                    color = KoordinatorColors.warning
                )
            }
        }
    }
}

@Composable
private fun AdminDetailCard(
    admin: AdminSummary,
    isDark: Boolean = false,
    photoUrl: String? = null  // ✅ BARU
) {
    val isHoliday = admin.isHoliday()
    val performance = admin.performancePercentage / 100f
    val performanceColor = getReportPerformanceColor(performance)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val context = LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
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
                                fontSize = 14.sp
                            )
                        }
                    }

                    Column {
                        Text(
                            text = admin.adminName.ifBlank { admin.adminName },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = KoordinatorColors.getTextPrimary(isDark),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${admin.nasabahAktif} nasabah aktif",
                            fontSize = 12.sp,
                            color = KoordinatorColors.getTextSecondary(isDark)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isHoliday) KoordinatorColors.getTextMuted(isDark).copy(alpha = 0.1f)
                    else performanceColor.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = if (isHoliday) "LIBUR" else "${admin.performancePercentage.toInt()}%",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isHoliday) KoordinatorColors.getTextMuted(isDark) else performanceColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { if (isHoliday) 0f else performance.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isHoliday) KoordinatorColors.getTextMuted(isDark) else performanceColor,
                trackColor = KoordinatorColors.getBorder(isDark)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniStatItem(
                    value = formatRupiah(admin.totalPiutang. toInt()),
                    label = "Saldo",
                    color = KoordinatorColors.danger
                )
                MiniStatItem(
                    value = admin.nasabahBaruHariIni.toString(),
                    label = "Drop",
                    color = PengawasColors.teal
                )
                MiniStatItem(
                    value = admin.nasabahLunasHariIni.toString(),
                    label = "Lunas",
                    color = KoordinatorColors.success
                )
                MiniStatItem(
                    value = admin.nasabahLunas.toString(),
                    label = "Total Lunas",
                    color = PengawasColors.purple
                )
            }
        }
    }
}

// =============================================================================
// TAB 2: PEMBAYARAN
// =============================================================================
@Composable
private fun PembayaranTab(
    isDark: Boolean = false,
    pembayaranList: List<PembayaranHarianItem>,
    currentSummary: PengawasCabangSummary
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Summary card
        item {
            PembayaranSummaryCard(
                isDark = isDark,
                pembayaranList = pembayaranList,
                totalTarget = currentSummary.targetHariIni
            )
        }

        // Filter: Hilangkan pencairan dari list tampilan
        val pembayaranTanpaPencairan = pembayaranList.filter { it.jenis != "pencairan" }

        // Pembayaran list
        if (pembayaranTanpaPencairan.isEmpty()) {
            item {
                EmptyContent(
                    isDark = isDark,
                    icon = Icons.Rounded.Payments,
                    message = "Belum ada pembayaran hari ini"
                )
            }
        } else {
            items(pembayaranTanpaPencairan) { pembayaran ->
                PembayaranDetailCard(pembayaran, isDark = isDark)
            }
        }
    }
}

@Composable
private fun PembayaranSummaryCard(
    isDark: Boolean = false,
    pembayaranList: List<PembayaranHarianItem>,
    totalTarget: Long
) {
    val cicilan = pembayaranList.filter { it.jenis == "cicilan" }
    val tambahBayar = pembayaranList.filter { it.jenis == "tambah_bayar" }
    val totalCicilan = cicilan.sumOf { it.jumlah }
    val totalTambahBayar = tambahBayar.sumOf { it.jumlah }
    val totalMasuk = totalCicilan + totalTambahBayar

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Ringkasan Pembayaran",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = KoordinatorColors.getTextPrimary(isDark)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MiniStatItem(
                    value = cicilan.size.toString(),
                    label = "Cicilan",
                    color = PengawasColors.success
                )
                MiniStatItem(
                    value = tambahBayar.size.toString(),
                    label = "Tambah Bayar",
                    color = PengawasColors.purple
                )
                MiniStatItem(
                    value = formatRupiah(totalMasuk.toInt()),
                    label = "Total Masuk",
                    color = PengawasColors.info
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            HorizontalDivider(color = KoordinatorColors.getBorder(isDark))

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Cicilan", fontSize = 12.sp, color = KoordinatorColors.getTextMuted(isDark))
                    Text(
                        formatRupiah(totalCicilan.toInt()),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = KoordinatorColors.success
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Tambah Bayar", fontSize = 12.sp, color = KoordinatorColors.getTextMuted(isDark))
                    Text(
                        formatRupiah(totalTambahBayar.toInt()),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = KoordinatorColors.danger
                    )
                }
            }
        }
    }
}

@Composable
private fun PembayaranDetailCard(pembayaran: PembayaranHarianItem, isDark: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                        .size(44.dp)
                        .background(
                            when (pembayaran.jenis) {
                                "pencairan" -> KoordinatorColors.warning.copy(alpha = 0.1f)
                                "tambah_bayar" -> PengawasColors.purple.copy(alpha = 0.1f)
                                else -> KoordinatorColors.success.copy(alpha = 0.1f)
                            },
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (pembayaran.jenis) {
                            "pencairan" -> Icons.Rounded.MonetizationOn
                            "tambah_bayar" -> Icons.Rounded.Add
                            else -> Icons.Rounded.Payments
                        },
                        contentDescription = null,
                        tint = when (pembayaran.jenis) {
                            "pencairan" -> KoordinatorColors.warning
                            "tambah_bayar" -> PengawasColors.purple
                            else -> KoordinatorColors.success
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = pembayaran.namaPanggilan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KoordinatorColors.getTextPrimary(isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pembayaran.adminName,
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (pembayaran.isPencairan) "-${formatRupiah(pembayaran.jumlah.toInt())}"
                    else "+${formatRupiah(pembayaran.jumlah.toInt())}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (pembayaran.isPencairan) KoordinatorColors.danger else KoordinatorColors.success
                )
                Text(
                    text = pembayaran.jenisDisplay,
                    fontSize = 11.sp,
                    color = KoordinatorColors.getTextMuted(isDark)
                )
            }
        }
    }
}

// =============================================================================
// TAB 3: NASABAH BARU
// =============================================================================
@Composable
private fun NasabahBaruTab(
    isDark: Boolean = false,
    nasabahBaruList: List<NasabahBaruItem>,
    currentSummary: PengawasCabangSummary
) {
    // ✅ BARU: State untuk kategori yang sedang ditampilkan
    var selectedCategory by remember { mutableStateOf("semua") }

    // ✅ BARU: Pisahkan menjadi drop baru dan drop lama
    val dropBaru = nasabahBaruList.filter { it.pinjamanKe <= 1 }
    val dropLama = nasabahBaruList.filter { it.pinjamanKe > 1 }

    // ✅ BARU: List yang akan ditampilkan berdasarkan kategori
    val displayedList = when (selectedCategory) {
        "baru" -> dropBaru
        "lama" -> dropLama
        else -> nasabahBaruList
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Summary card
        item {
            NasabahBaruSummaryCard(
                isDark = isDark,
                jumlahDropBaru = dropBaru.size,
                totalDiterimaBaru = dropBaru.sumOf { it.totalDiterima },
                jumlahDropLama = dropLama.size,
                totalDiterimaLama = dropLama.sumOf { it.totalDiterima },
                totalDiterimaSemua = nasabahBaruList.sumOf { it.totalDiterima },
                selectedCategory = selectedCategory,
                onCategorySelected = { category -> selectedCategory = category }
            )
        }

        if (displayedList.isEmpty()) {
            item {
                val message = when (selectedCategory) {
                    "baru" -> "Tidak ada drop baru hari ini"
                    "lama" -> "Tidak ada drop lama hari ini"
                    else -> "Tidak ada drop hari ini"
                }
                EmptyContent(
                    isDark = isDark,
                    icon = Icons.Rounded.PersonAdd,
                    message = message
                )
            }
        } else {
            items(displayedList) { nasabah ->
                NasabahBaruDetailCard(nasabah, isDark = isDark)
            }
        }
    }
}

@Composable
private fun NasabahBaruSummaryCard(
    isDark: Boolean = false,
    jumlahDropBaru: Int,
    totalDiterimaBaru: Long,
    jumlahDropLama: Int,
    totalDiterimaLama: Long,
    totalDiterimaSemua: Long,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    val totalSemuaNasabah = jumlahDropBaru + jumlahDropLama

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ✅ Header: Total keseluruhan
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Jumlah", fontSize = 12.sp, color = KoordinatorColors.getTextMuted(isDark))
                    Text(
                        totalSemuaNasabah.toString(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = PengawasColors.teal
                    )
                    Text("drop hari ini", fontSize = 12.sp, color = KoordinatorColors.getTextSecondary(isDark))
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Total Drop", fontSize = 12.sp, color = KoordinatorColors.getTextMuted(isDark))
                    Text(
                        formatRupiah(totalDiterimaSemua.toInt()),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = KoordinatorColors.info
                    )
                    Text("diterima nasabah", fontSize = 12.sp, color = KoordinatorColors.getTextSecondary(isDark))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = KoordinatorColors.getBorder(isDark))
            Spacer(modifier = Modifier.height(16.dp))

            // ✅ BARU: Filter cards untuk Drop Baru dan Drop Lama
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Card Drop Baru
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onCategorySelected(if (selectedCategory == "baru") "semua" else "baru")
                        },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedCategory == "baru")
                            PengawasColors.teal else PengawasColors.teal.copy(alpha = 0.1f)
                    ),
                    border = if (selectedCategory == "baru") null
                    else BorderStroke(1.dp, PengawasColors.teal.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FiberNew,
                                contentDescription = null,
                                tint = if (selectedCategory == "baru") Color.White
                                else PengawasColors.teal,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Drop Baru",
                                color = if (selectedCategory == "baru") Color.White
                                else PengawasColors.teal,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "$jumlahDropBaru orang",
                            color = if (selectedCategory == "baru") Color.White
                            else KoordinatorColors.getTextPrimary(isDark),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatRupiah(totalDiterimaBaru.toInt()),
                            color = if (selectedCategory == "baru") Color.White.copy(alpha = 0.9f)
                            else KoordinatorColors.getTextSecondary(isDark),
                            fontSize = 11.sp
                        )
                    }
                }

                // Card Drop Lama
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onCategorySelected(if (selectedCategory == "lama") "semua" else "lama")
                        },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedCategory == "lama")
                            PengawasColors.purple else PengawasColors.purple.copy(alpha = 0.1f)
                    ),
                    border = if (selectedCategory == "lama") null
                    else BorderStroke(1.dp, PengawasColors.purple.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = if (selectedCategory == "lama") Color.White
                                else PengawasColors.purple,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Drop Lama",
                                color = if (selectedCategory == "lama") Color.White
                                else PengawasColors.purple,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "$jumlahDropLama orang",
                            color = if (selectedCategory == "lama") Color.White
                            else KoordinatorColors.getTextPrimary(isDark),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatRupiah(totalDiterimaLama.toInt()),
                            color = if (selectedCategory == "lama") Color.White.copy(alpha = 0.9f)
                            else KoordinatorColors.getTextSecondary(isDark),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NasabahBaruDetailCard(nasabah: NasabahBaruItem, isDark: Boolean = false) {
    // ✅ BARU: Tentukan apakah drop baru atau lama
    val isDropBaru = nasabah.pinjamanKe <= 1
    val badgeColor = if (isDropBaru) PengawasColors.teal else PengawasColors.purple
    val badgeText = if (isDropBaru) "BARU" else "Pinjaman ke ${nasabah.pinjamanKe}"
    val badgeIcon = if (isDropBaru) Icons.Rounded.FiberNew else Icons.Rounded.Refresh

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nasabah.namaPanggilan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KoordinatorColors.getTextPrimary(isDark)
                    )
                    Text(
                        text = nasabah.adminName,
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }

                // ✅ BARU: Badge drop baru / drop lama
                Surface(
                    color = badgeColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = badgeIcon,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = badgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatRupiah(nasabah.totalDiterima.toInt()),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = KoordinatorColors.info
                )
                Text(
                    text = nasabah.wilayah.ifBlank { "-" },
                    fontSize = 11.sp,
                    color = KoordinatorColors.getTextMuted(isDark)
                )
            }
        }
    }
}

// =============================================================================
// TAB 4: NASABAH LUNAS
// =============================================================================
@Composable
private fun NasabahLunasTab(
    isDark: Boolean = false,
    nasabahLunasList: List<NasabahLunasItem>,
    currentSummary: PengawasCabangSummary
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Summary card
        item {
            NasabahLunasSummaryCard(
                isDark = isDark,
                count = nasabahLunasList.size,
                totalDibayar = nasabahLunasList.sumOf { it.totalDibayar }
            )
        }

        if (nasabahLunasList.isEmpty()) {
            item {
                EmptyContent(
                    isDark = isDark,
                    icon = Icons.Rounded.CheckCircle,
                    message = "Tidak ada nasabah lunas hari ini"
                )
            }
        } else {
            items(nasabahLunasList) { nasabah ->
                NasabahLunasDetailCard(nasabah, isDark = isDark)
            }
        }
    }
}

@Composable
private fun NasabahLunasSummaryCard(isDark: Boolean = false, count: Int, totalDibayar: Long) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Jumlah", fontSize = 12.sp, color = KoordinatorColors.getTextMuted(isDark))
                Text(
                    count.toString(),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = KoordinatorColors.success
                )
                Text("nasabah lunas", fontSize = 12.sp, color = KoordinatorColors.getTextSecondary(isDark))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Total Dibayar", fontSize = 12.sp, color = KoordinatorColors.getTextMuted(isDark))
                Text(
                    formatRupiah(totalDibayar. toInt()),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = KoordinatorColors.success
                )
                Text("pelunasan", fontSize = 12.sp, color = KoordinatorColors.getTextSecondary(isDark))
            }
        }
    }
}

@Composable
private fun NasabahLunasDetailCard(nasabah: NasabahLunasItem, isDark: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nasabah.namaPanggilan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KoordinatorColors.getTextPrimary(isDark)
                    )
                    Text(
                        text = nasabah.adminName,
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatRupiah(nasabah.totalDibayar.toInt()),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = KoordinatorColors.success
                    )
                    Text(
                        text = "LUNAS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = KoordinatorColors.success
                    )
                }
            }
        }
    }
}

// =============================================================================
// COMMON COMPONENTS
// =============================================================================
@Composable
private fun MiniStatItem(value: String, label: String, color: Color, isDark: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = KoordinatorColors.getTextMuted(isDark)
        )
    }
}

@Composable
private fun EmptyContent(isDark: Boolean = false, icon: ImageVector, message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = KoordinatorColors.getTextMuted(isDark),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = message,
                fontSize = 16.sp,
                color = KoordinatorColors.getTextMuted(isDark),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StatusKhususNavigationCard(onClick: () -> Unit, isDark: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .shadow(8.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF7C3AED), Color(0xFFA78BFA))
                        )
                    )
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Nasabah Status Khusus",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Meninggal, Melarikan Diri, Menolak Bayar, dll",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Tap untuk lihat daftar →",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Flag,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenungguPencairanNavigationCard(onClick: () -> Unit, isDark: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .shadow(8.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF10B981), Color(0xFF34D399))
                        )
                    )
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Menunggu Pencairan",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Nasabah lunas yang simpanannya belum dicairkan",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Tap untuk lihat daftar →",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Savings,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SemuaNasabahNavigationCard(onClick: () -> Unit, isDark: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .shadow(8.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
                        )
                    )
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Lihat Seluruh Nasabah",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Daftar lengkap nasabah per cabang atau seluruh cabang",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Tap untuk lihat daftar →",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.People,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// HELPER FUNCTIONS
// =============================================================================
private fun getReportPerformanceColor(performance: Float): Color {
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

// =============================================================================
// TAB KASBON & TITIPAN MALAM
// =============================================================================
@Composable
private fun KasbonTitipanMalamTab(
    isDark: Boolean = false,
    biayaAwalList: List<BiayaAwalItem>,
    kasirUangKasList: List<BiayaAwalItem> = emptyList(),
    pembayaranList: List<PembayaranHarianItem>,
    nasabahBaruList: List<NasabahBaruItem>
) {
    // Hitung total kasbon (biaya awal + uang kas dari kasir)
    val totalBiayaAwal = biayaAwalList.sumOf { it.jumlah }
    val totalKasirUangKas = kasirUangKasList.sumOf { it.jumlah }
    val totalKasbon = totalBiayaAwal + totalKasirUangKas

    // Hitung total masuk (cicilan + tambah_bayar, TANPA pencairan)
    val totalMasuk = pembayaranList
        .filter { it.jenis == "cicilan" || it.jenis == "tambah_bayar" }
        .sumOf { it.jumlah }

    // Hitung total keluar (drop/pencairan ke nasabah baru)
    val totalKeluar = nasabahBaruList.sumOf { it.totalDiterima }

    // Hitung titipan malam
    val titipanMalam = totalKasbon + totalMasuk - totalKeluar

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Summary Card
        item {
            KasbonTitipanSummaryCard(
                isDark = isDark,
                totalKasbon = totalKasbon,
                totalMasuk = totalMasuk,
                totalKeluar = totalKeluar,
                titipanMalam = titipanMalam
            )
        }

        // Detail Kasbon per Admin
        if (biayaAwalList.isEmpty() && kasirUangKasList.isEmpty()) {
            item {
                EmptyContent(
                    isDark = isDark,
                    icon = Icons.Rounded.AccountBalance,
                    message = "Tidak ada kasbon hari ini"
                )
            }
        } else {
            item {
                Text(
                    text = "Detail Kasbon per Admin",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = KoordinatorColors.getTextPrimary(isDark),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(biayaAwalList) { biayaAwal ->
                BiayaAwalDetailCard(biayaAwal, isDark = isDark)
            }

            // Detail Uang Kas dari Kasir
            if (kasirUangKasList.isNotEmpty()) {
                item {
                    Text(
                        text = "Uang Kas dari Kasir",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = KoordinatorColors.getTextPrimary(isDark),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                items(kasirUangKasList) { kasirEntry ->
                    BiayaAwalDetailCard(
                        biayaAwal = kasirEntry,
                        isDark = isDark
                    )
                }
            }
        }
    }
}

@Composable
private fun KasbonTitipanSummaryCard(
    isDark: Boolean = false,
    totalKasbon: Long,
    totalMasuk: Long,
    totalKeluar: Long,
    titipanMalam: Long
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Ringkasan Kasbon & Titipan Malam",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = KoordinatorColors.getTextPrimary(isDark)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Row 1: Kasbon dan Total Masuk
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MiniStatItem(
                    value = formatRupiah(totalKasbon.toInt()),
                    label = "Kasbon",
                    color = PengawasColors.info
                )
                MiniStatItem(
                    value = formatRupiah(totalMasuk.toInt()),
                    label = "Total Masuk",
                    color = PengawasColors.success
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 2: Total Keluar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                MiniStatItem(
                    value = formatRupiah(totalKeluar.toInt()),
                    label = "Total Keluar (Drop)",
                    color = PengawasColors.danger
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = KoordinatorColors.getBorder(isDark))
            Spacer(modifier = Modifier.height(12.dp))

            // Titipan Malam (Highlighted)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (titipanMalam >= 0)
                        PengawasColors.success.copy(alpha = 0.1f)
                    else
                        PengawasColors.danger.copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Titipan Malam",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = KoordinatorColors.getTextSecondary(isDark)
                        )
                        Text(
                            text = "(Kasbon + Masuk - Keluar)",
                            fontSize = 11.sp,
                            color = KoordinatorColors.getTextMuted(isDark)
                        )
                    }
                    Text(
                        text = "Rp ${formatRupiah(titipanMalam.toInt())}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (titipanMalam >= 0) PengawasColors.success else PengawasColors.danger
                    )
                }
            }
        }
    }
}

@Composable
private fun BiayaAwalDetailCard(biayaAwal: BiayaAwalItem, isDark: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                        .size(44.dp)
                        .background(PengawasColors.info.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccountBalance,
                        contentDescription = null,
                        tint = PengawasColors.info,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = biayaAwal.adminName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = KoordinatorColors.getTextPrimary(isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Kasbon Hari Ini",
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }
            }

            Text(
                text = "Rp ${formatRupiah(biayaAwal.jumlah.toInt())}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = PengawasColors.info
            )
        }
    }
}

// =========================================================================
// KOMPONEN: Card Pembukuan (Koordinator)
// =========================================================================
@Composable
private fun PembukuanCard(
    isDark: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color(0xFFF43F5E).copy(alpha = 0.15f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        Color(0xFFF43F5E).copy(alpha = 0.12f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MenuBook,
                    contentDescription = null,
                    tint = Color(0xFFF43F5E),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pembukuan",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = KoordinatorColors.getTextPrimary(isDark)
                )
                Text(
                    text = "Buka sistem pembukuan keuangan",
                    fontSize = 12.sp,
                    color = KoordinatorColors.getTextSecondary(isDark)
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = KoordinatorColors.getTextMuted(isDark),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}