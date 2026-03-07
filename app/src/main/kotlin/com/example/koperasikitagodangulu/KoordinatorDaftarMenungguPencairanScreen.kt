package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.models.AdminSummary
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

// =========================================================================
// DATA CLASS
// =========================================================================
data class KorMenungguPencairanItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val wilayah: String = "",
    val adminId: String = "",
    val adminName: String = "",
    val cabangId: String = "",
    val totalSimpanan: Int = 0,
    val sisaHutang: Int = 0,
    val besarPinjaman: Int = 0,
    val totalPelunasan: Int = 0,
    val totalDibayar: Long = 0,
    val jenisPencairan: String = "", // "OTOMATIS" atau "MANUAL"
    val statusKhusus: String = "",
    val tanggalStatusKhusus: String = ""
)

// =========================================================================
// KOORDINATOR DAFTAR MENUNGGU PENCAIRAN SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoordinatorDaftarMenungguPencairanScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    // =========================================================================
    // ✅ Gunakan data yang sudah ada di ViewModel (HEMAT!)
    // =========================================================================
    val selectedCabangId by viewModel.pengawasSelectedCabangId.collectAsState()

    // =========================================================================
    // ✅ DARK MODE SUPPORT
    // =========================================================================
    val isDark by viewModel.isDarkMode
    val cabangOptions by viewModel.pengawasCabangOptions.collectAsState()
    val filteredAdminSummaries by viewModel.pengawasFilteredAdminSummaries.collectAsState()
    val systemUiController = rememberSystemUiController()
    val bgColor = KoordinatorColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val coroutineScope = rememberCoroutineScope()

    var menungguPencairanList by remember { mutableStateOf<List<KorMenungguPencairanItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasLoadedOnce by remember { mutableStateOf(false) }

    // Function untuk load data berdasarkan cabang yang dipilih
    suspend fun loadData() {
        // ✅ HEMAT: Hanya query jika ada cabang yang dipilih
        if (filteredAdminSummaries.isEmpty()) {
            Log.w("PwsMenungguPencairan", "⚠️ No admins in selected cabang")
            menungguPencairanList = emptyList()
            isLoading = false
            isVisible = true
            return
        }

        isLoading = true
        errorMessage = null

        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference
            val list = mutableListOf<KorMenungguPencairanItem>()

            // ✅ HEMAT: Hanya query admin di cabang yang dipilih (bukan semua 6000 nasabah!)
            val adminIds = filteredAdminSummaries.map { it.adminId }
            val currentCabang = selectedCabangId ?: "all"

            Log.d("PwsMenungguPencairan", "📋 Loading for cabang: $currentCabang, ${adminIds.size} admins")

            // Loop setiap admin di cabang yang dipilih
            for (adminId in adminIds) {
                try {
                    val adminName = filteredAdminSummaries.find { it.adminId == adminId }?.adminName ?: ""
                    val adminCabang = filteredAdminSummaries.find { it.adminId == adminId }?.cabang ?: ""

                    // Query pelanggan admin ini
                    val pelangganSnap = database.child("pelanggan/$adminId").get().await()

                    Log.d("PwsMenungguPencairan", "📂 Admin $adminId: ${pelangganSnap.childrenCount} pelanggan")

                    for (pelSnap in pelangganSnap.children) {
                        val pelangganId = pelSnap.key ?: continue

                        // Ambil data pelanggan
                        val status = (pelSnap.child("status").getValue(String::class.java) ?: "").lowercase()
                        if (status == "ditolak") continue

                        val statusPencairanSimpanan = pelSnap.child("statusPencairanSimpanan").getValue(String::class.java) ?: ""
                        val statusKhusus = (pelSnap.child("statusKhusus").getValue(String::class.java) ?: "").uppercase().replace(" ", "_")

                        // Hitung total dibayar
                        var totalDibayar = 0L
                        val pembayaranList = pelSnap.child("pembayaranList")

                        if (pembayaranList.exists()) {
                            for (paySnap in pembayaranList.children) {
                                totalDibayar += paySnap.child("jumlah").getValue(Long::class.java)
                                    ?: paySnap.child("jumlah").getValue(Int::class.java)?.toLong()
                                            ?: 0L

                                // Sub pembayaran
                                val subPembayaran = paySnap.child("subPembayaran")
                                if (subPembayaran.exists()) {
                                    for (subSnap in subPembayaran.children) {
                                        totalDibayar += subSnap.child("jumlah").getValue(Long::class.java)
                                            ?: subSnap.child("jumlah").getValue(Int::class.java)?.toLong()
                                                    ?: 0L
                                    }
                                }
                            }
                        }

                        val totalPelunasan = pelSnap.child("totalPelunasan").getValue(Long::class.java)
                            ?: pelSnap.child("totalPelunasan").getValue(Int::class.java)?.toLong()
                            ?: 0L

                        val isSudahLunasCicilan = totalPelunasan > 0 && totalDibayar >= totalPelunasan

                        // =====================================================================
                        // LOGIKA SAMA DENGAN PIMPINAN & CLOUD FUNCTIONS
                        // =====================================================================
                        val isMenungguPencairanManual = statusKhusus == "MENUNGGU_PENCAIRAN" &&
                                statusPencairanSimpanan != "Dicairkan"

                        val isLunasOtomatis = isSudahLunasCicilan && statusPencairanSimpanan != "Dicairkan"

                        if (isMenungguPencairanManual || isLunasOtomatis) {
                            val simpanan = pelSnap.child("simpanan").getValue(Int::class.java) ?: 0
                            val besarPinjaman = pelSnap.child("besarPinjaman").getValue(Int::class.java) ?: 0
                            val sisaHutang = (totalPelunasan - totalDibayar).coerceAtLeast(0).toInt()
                            val namaPanggilan = pelSnap.child("namaPanggilan").getValue(String::class.java) ?: ""
                            val namaKtp = pelSnap.child("namaKtp").getValue(String::class.java) ?: ""
                            val wilayah = pelSnap.child("wilayah").getValue(String::class.java) ?: ""
                            val tanggalStatusKhusus = pelSnap.child("tanggalStatusKhusus").getValue(String::class.java) ?: ""

                            list.add(KorMenungguPencairanItem(
                                pelangganId = pelangganId,
                                namaPanggilan = namaPanggilan,
                                namaKtp = namaKtp,
                                wilayah = wilayah,
                                adminId = adminId,
                                adminName = adminName,
                                cabangId = adminCabang,
                                totalSimpanan = simpanan,
                                sisaHutang = sisaHutang,
                                besarPinjaman = besarPinjaman,
                                totalPelunasan = totalPelunasan.toInt(),
                                totalDibayar = totalDibayar,
                                jenisPencairan = if (isMenungguPencairanManual) "MANUAL" else "OTOMATIS",
                                statusKhusus = statusKhusus,
                                tanggalStatusKhusus = tanggalStatusKhusus
                            ))

                            Log.d("PwsMenungguPencairan", "✅ Found: $namaPanggilan (${if (isMenungguPencairanManual) "MANUAL" else "OTOMATIS"})")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PwsMenungguPencairan", "Error loading admin $adminId: ${e.message}")
                }
            }

            menungguPencairanList = list.sortedByDescending { it.totalSimpanan }
            Log.d("PwsMenungguPencairan", "✅ Total loaded: ${list.size} items for cabang $currentCabang")
            hasLoadedOnce = true

        } catch (e: Exception) {
            Log.e("PwsMenungguPencairan", "Error loading: ${e.message}")
            errorMessage = "Gagal memuat data: ${e.message}"
        }

        isLoading = false
        isVisible = true
    }

    // Load data saat cabang berubah atau pertama kali
    LaunchedEffect(selectedCabangId, filteredAdminSummaries) {
        if (filteredAdminSummaries.isNotEmpty()) {
            loadData()
        }
    }

    // Hitung total simpanan
    val totalSimpanan = remember(menungguPencairanList) {
        menungguPencairanList.sumOf { it.totalSimpanan }
    }

    Scaffold(
        containerColor = KoordinatorColors.getBackground(isDark),
        topBar = {
            PwsMenungguPencairanTopBar(
                isDark = isDark,
                jumlahNasabah = menungguPencairanList.size,
                onBack = { navController.popBackStack() },
                onRefresh = { coroutineScope.launch { loadData() } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // =========================================================
            // CABANG FILTER - Sama seperti KoordinatorReportsScreen
            // =========================================================
            KoordinatorCabangFilterBar(
                isDark = isDark,
                cabangOptions = cabangOptions,
                selectedCabangId = selectedCabangId,
                onCabangSelected = { viewModel.setPengawasSelectedCabang(it) }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Summary Card
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400)) + slideInVertically(
                        initialOffsetY = { -20 },
                        animationSpec = tween(400)
                    )
                ) {
                    PwsMenungguPencairanSummaryCard(
                        isDark = isDark,
                        jumlahNasabah = menungguPencairanList.size,
                        totalSimpanan = totalSimpanan,
                        cabangName = cabangOptions.find { it.first == selectedCabangId }?.second ?: "Semua Cabang"
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Error message
                if (errorMessage != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Text(
                            text = errorMessage!!,
                            modifier = Modifier.padding(16.dp),
                            color = Color(0xFFC62828)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Loading atau List
                when {
                    isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = KoordinatorColors.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Memuat data...",
                                    color = KoordinatorColors.getTextSecondary(isDark)
                                )
                            }
                        }
                    }
                    menungguPencairanList.isEmpty() && hasLoadedOnce -> {
                        KoordinatorEmptyMenungguPencairanState()
                    }
                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(menungguPencairanList, key = { "${it.adminId}_${it.pelangganId}" }) { item ->
                                AnimatedVisibility(
                                    visible = isVisible,
                                    enter = fadeIn(tween(300)) + slideInVertically(
                                        initialOffsetY = { 20 },
                                        animationSpec = tween(300)
                                    )
                                ) {
                                    PwsMenungguPencairanCard(item = item, isDark = isDark)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// COMPONENTS
// =========================================================================

@Composable
private fun KoordinatorCabangFilterBar(
    isDark: Boolean = false,
    cabangOptions: List<Pair<String?, String>>,
    selectedCabangId: String?,
    onCabangSelected: (String?) -> Unit
) {
    Surface(
        color = KoordinatorColors.getCard(isDark),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PwsMenungguPencairanTopBar(
    isDark: Boolean = false,
    jumlahNasabah: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp),
        color = KoordinatorColors.getCard(isDark)
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        "Menunggu Pencairan",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = KoordinatorColors.getTextPrimary(isDark)
                    )
                    Text(
                        "$jumlahNasabah nasabah",
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, "Kembali")
                }
            },
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Rounded.Refresh,
                        "Refresh",
                        tint = KoordinatorColors.primary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = KoordinatorColors.getCard(isDark)
            )
        )
    }
}

@Composable
private fun PwsMenungguPencairanSummaryCard(
    isDark: Boolean = false,
    jumlahNasabah: Int,
    totalSimpanan: Int,
    cabangName: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        PengawasColors.primaryGradient
                    )
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = cabangName,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Total Simpanan",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    Text(
                        text = formatRupiah(totalSimpanan),
                        color = KoordinatorColors.getCard(isDark),
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$jumlahNasabah nasabah menunggu pencairan",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Savings,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PwsMenungguPencairanCard(item: KorMenungguPencairanItem, isDark: Boolean = false) {
    val isOtomatis = item.jenisPencairan == "OTOMATIS"
    val badgeColor = if (isOtomatis) Color(0xFF10B981) else Color(0xFF7C3AED)
    val badgeText = if (isOtomatis) "Lunas" else "Manual"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Nama + Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Avatar
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(badgeColor.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.namaPanggilan.firstOrNull()?.uppercase() ?: "?",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = badgeColor
                        )
                    }

                    Column {
                        Text(
                            text = item.namaPanggilan.ifBlank { item.namaKtp },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = KoordinatorColors.getTextPrimary(isDark)
                        )
                        Text(
                            text = item.wilayah,
                            fontSize = 12.sp,
                            color = KoordinatorColors.getTextSecondary(isDark)
                        )
                    }
                }

                // Badge Jenis Pencairan
                Surface(
                    color = badgeColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = badgeText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Info Simpanan & Sisa Hutang
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Total Simpanan",
                        fontSize = 11.sp,
                        color = KoordinatorColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.totalSimpanan),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF10B981)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Sisa Hutang",
                        fontSize = 11.sp,
                        color = KoordinatorColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.sisaHutang),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = if (item.sisaHutang > 0) KoordinatorColors.danger else KoordinatorColors.success
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = KoordinatorColors.getBorder(isDark))
            Spacer(Modifier.height(8.dp))

            // Footer: Admin & Cabang
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = KoordinatorColors.getTextMuted(isDark),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = item.adminName,
                        fontSize = 11.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Business,
                        contentDescription = null,
                        tint = KoordinatorColors.getTextMuted(isDark),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = item.cabangId.replaceFirstChar { it.uppercase() },
                        fontSize = 11.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }
            }
        }
    }
}

@Composable
private fun KoordinatorEmptyMenungguPencairanState(isDark: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        KoordinatorColors.primary.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = KoordinatorColors.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Tidak ada nasabah menunggu pencairan",
                color = KoordinatorColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )
            Text(
                text = "di cabang yang dipilih",
                color = KoordinatorColors.getTextMuted(isDark),
                fontSize = 12.sp
            )
        }
    }
}