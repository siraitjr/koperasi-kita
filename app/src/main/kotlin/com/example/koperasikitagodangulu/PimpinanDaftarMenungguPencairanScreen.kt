package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// =========================================================================
// DATA CLASS
// =========================================================================
data class MenungguPencairanItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val wilayah: String = "",
    val adminId: String = "",
    val adminName: String = "",
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
// PIMPINAN DAFTAR MENUNGGU PENCAIRAN SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanDaftarMenungguPencairanScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    // ✅ Gunakan adminSummary dari ViewModel untuk mendapatkan daftar admin
    val adminSummaryList by viewModel.adminSummary.collectAsState()
    val coroutineScope = rememberCoroutineScope()

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

    var menungguPencairanList by remember { mutableStateOf<List<MenungguPencairanItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Function untuk load data
    suspend fun loadData() {
        isLoading = true
        errorMessage = null

        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference
            val list = mutableListOf<MenungguPencairanItem>()

            // ✅ Gunakan adminSummary yang sudah ada untuk mendapatkan daftar admin
            val adminIds = adminSummaryList.map { it.adminId }

            Log.d("MenungguPencairan", "📋 Found ${adminIds.size} admins from adminSummary")

            if (adminIds.isEmpty()) {
                Log.w("MenungguPencairan", "⚠️ No admins found in adminSummary")
                isLoading = false
                isVisible = true
                return
            }

            // Loop setiap admin dan cari nasabah menunggu pencairan
            for (adminId in adminIds) {
                try {
                    // Ambil nama admin dari adminSummary
                    val adminName = adminSummaryList.find { it.adminId == adminId }?.adminName ?: ""

                    // Query pelanggan admin ini
                    val pelangganSnap = database.child("pelanggan/$adminId").get().await()

                    Log.d("MenungguPencairan", "📂 Admin $adminId: ${pelangganSnap.childrenCount} pelanggan")

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
                        // LOGIKA SAMA DENGAN ANDROID getPelangganMenungguPencairan()
                        // =====================================================================
                        // Kondisi 1: Ditandai manual sebagai MENUNGGU_PENCAIRAN
                        val isMenungguPencairanManual = statusKhusus == "MENUNGGU_PENCAIRAN" &&
                                statusPencairanSimpanan != "Dicairkan"

                        // Kondisi 2: Lunas cicilan otomatis tapi belum dicairkan
                        val isLunasOtomatis = isSudahLunasCicilan && statusPencairanSimpanan != "Dicairkan"

                        // Masuk jika salah satu kondisi terpenuhi
                        if (isMenungguPencairanManual || isLunasOtomatis) {
                            val simpanan = pelSnap.child("simpanan").getValue(Int::class.java) ?: 0
                            val besarPinjaman = pelSnap.child("besarPinjaman").getValue(Int::class.java) ?: 0
                            val sisaHutang = (totalPelunasan - totalDibayar).coerceAtLeast(0).toInt()
                            val namaPanggilan = pelSnap.child("namaPanggilan").getValue(String::class.java) ?: ""
                            val namaKtp = pelSnap.child("namaKtp").getValue(String::class.java) ?: ""
                            val wilayah = pelSnap.child("wilayah").getValue(String::class.java) ?: ""
                            val tanggalStatusKhusus = pelSnap.child("tanggalStatusKhusus").getValue(String::class.java) ?: ""

                            list.add(MenungguPencairanItem(
                                pelangganId = pelangganId,
                                namaPanggilan = namaPanggilan,
                                namaKtp = namaKtp,
                                wilayah = wilayah,
                                adminId = adminId,
                                adminName = adminName,
                                totalSimpanan = simpanan,
                                sisaHutang = sisaHutang,
                                besarPinjaman = besarPinjaman,
                                totalPelunasan = totalPelunasan.toInt(),
                                totalDibayar = totalDibayar,
                                jenisPencairan = if (isMenungguPencairanManual) "MANUAL" else "OTOMATIS",
                                statusKhusus = statusKhusus,
                                tanggalStatusKhusus = tanggalStatusKhusus
                            ))

                            Log.d("MenungguPencairan", "✅ Found: $namaPanggilan (${if (isMenungguPencairanManual) "MANUAL" else "OTOMATIS"})")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MenungguPencairan", "Error loading admin $adminId: ${e.message}")
                }
            }

            menungguPencairanList = list.sortedByDescending { it.totalSimpanan }
            Log.d("MenungguPencairan", "✅ Total loaded: ${list.size} items")

        } catch (e: Exception) {
            Log.e("MenungguPencairan", "Error loading: ${e.message}")
            errorMessage = "Gagal memuat data: ${e.message}"
        }

        isLoading = false
        isVisible = true
    }

    // Load data saat adminSummary berubah
    LaunchedEffect(adminSummaryList) {
        if (adminSummaryList.isNotEmpty()) {
            loadData()
        }
    }

    // Hitung total simpanan
    val totalSimpanan = remember(menungguPencairanList) {
        menungguPencairanList.sumOf { it.totalSimpanan }
    }

    Scaffold(
        containerColor = PimpinanColors.getBackground(isDark), // ✅ UBAH: Dinamis berdasarkan tema
        topBar = {
            ModernMenungguPencairanTopBar(
                jumlahNasabah = menungguPencairanList.size,
                onBack = { navController.popBackStack() },
                onRefresh = { coroutineScope.launch { loadData() } },
                isDark = isDark // ✅ UBAH: Tambah parameter isDark
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
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
                ModernMenungguPencairanSummaryCard(
                    jumlahNasabah = menungguPencairanList.size,
                    totalSimpanan = totalSimpanan
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
                            CircularProgressIndicator(color = Color(0xFF10B981))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Memuat data...",
                                color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                            )
                        }
                    }
                }
                menungguPencairanList.isEmpty() -> {
                    ModernEmptyMenungguPencairanState(isDark = isDark) // ✅ UBAH: Tambah isDark
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
                                ModernMenungguPencairanCard(item = item, isDark = isDark) // ✅ UBAH: Tambah isDark
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernMenungguPencairanTopBar(
    jumlahNasabah: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    isDark: Boolean = false // ✅ BARU: Parameter isDark
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp),
        color = PimpinanColors.getCard(isDark) // ✅ UBAH: Dinamis
    ) {
        Column {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Menunggu Pencairan",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = PimpinanColors.getTextPrimary(isDark) // ✅ UBAH: Dinamis
                        )
                        Text(
                            "$jumlahNasabah nasabah",
                            fontSize = 12.sp,
                            color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            "Kembali",
                            tint = PimpinanColors.getTextPrimary(isDark) // ✅ UBAH: Dinamis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Rounded.Refresh,
                            "Refresh",
                            tint = Color(0xFF10B981)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PimpinanColors.getCard(isDark) // ✅ UBAH: Dinamis
                )
            )
        }
    }
}

@Composable
private fun ModernMenungguPencairanSummaryCard(
    jumlahNasabah: Int,
    totalSimpanan: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF10B981), Color(0xFF34D399))
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
                        text = "Total Simpanan",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    Text(
                        text = formatRupiah(totalSimpanan),
                        color = Color.White,
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
private fun ModernMenungguPencairanCard(
    item: MenungguPencairanItem,
    isDark: Boolean = false // ✅ BARU: Parameter isDark
) {
    val isOtomatis = item.jenisPencairan == "OTOMATIS"
    val badgeColor = if (isOtomatis) Color(0xFF10B981) else Color(0xFF7C3AED)
    val badgeText = if (isOtomatis) "Lunas" else "Manual"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark)) // ✅ UBAH: Dinamis
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
                            color = PimpinanColors.getTextPrimary(isDark) // ✅ UBAH: Dinamis
                        )
                        Text(
                            text = item.wilayah,
                            fontSize = 12.sp,
                            color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
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
                        color = PimpinanColors.getTextMuted(isDark) // ✅ UBAH: Dinamis
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
                        color = PimpinanColors.getTextMuted(isDark) // ✅ UBAH: Dinamis
                    )
                    Text(
                        text = formatRupiah(item.sisaHutang),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = if (item.sisaHutang > 0) PimpinanColors.danger else PimpinanColors.success
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = PimpinanColors.getBorder(isDark)) // ✅ UBAH: Dinamis
            Spacer(Modifier.height(8.dp))

            // Footer: Admin
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
                        tint = PimpinanColors.getTextMuted(isDark), // ✅ UBAH: Dinamis
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "PDL: ${item.adminName}",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                    )
                }

                if (item.tanggalStatusKhusus.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CalendarToday,
                            contentDescription = null,
                            tint = PimpinanColors.getTextMuted(isDark), // ✅ UBAH: Dinamis
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = item.tanggalStatusKhusus,
                            fontSize = 11.sp,
                            color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernEmptyMenungguPencairanState(
    isDark: Boolean = false // ✅ BARU: Parameter isDark
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        Color(0xFF10B981).copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Tidak ada nasabah menunggu pencairan",
                color = PimpinanColors.getTextSecondary(isDark), // ✅ UBAH: Dinamis
                fontSize = 14.sp
            )
        }
    }
}