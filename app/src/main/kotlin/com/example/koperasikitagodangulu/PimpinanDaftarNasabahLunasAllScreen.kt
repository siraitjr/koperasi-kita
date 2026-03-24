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
data class NasabahLunasAllItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val wilayah: String = "",
    val adminId: String = "",
    val adminName: String = "",
    val totalSimpanan: Int = 0,
    val besarPinjaman: Int = 0,
    val totalPelunasan: Int = 0,
    val totalDibayar: Long = 0
)

// =========================================================================
// PIMPINAN DAFTAR NASABAH LUNAS ALL SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanDaftarNasabahLunasAllScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val adminSummaryList by viewModel.adminSummary.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val isDark by viewModel.isDarkMode
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    var nasabahLunasList by remember { mutableStateOf<List<NasabahLunasAllItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Function untuk load data
    suspend fun loadData() {
        isLoading = true
        errorMessage = null

        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference
            val list = mutableListOf<NasabahLunasAllItem>()

            val adminIds = adminSummaryList.map { it.adminId }

            Log.d("NasabahLunasAll", "📋 Found ${adminIds.size} admins from adminSummary")

            if (adminIds.isEmpty()) {
                Log.w("NasabahLunasAll", "⚠️ No admins found in adminSummary")
                isLoading = false
                isVisible = true
                return
            }

            for (adminId in adminIds) {
                try {
                    val adminName = adminSummaryList.find { it.adminId == adminId }?.adminName ?: ""
                    val pelangganSnap = database.child("pelanggan/$adminId").get().await()

                    Log.d("NasabahLunasAll", "📂 Admin $adminId: ${pelangganSnap.childrenCount} pelanggan")

                    for (pelSnap in pelangganSnap.children) {
                        val pelangganId = pelSnap.key ?: continue

                        val status = pelSnap.child("status").getValue(String::class.java) ?: ""
                        if (status == "ditolak") continue
                        if (status == "Menunggu Approval") continue

                        val statusPencairanSimpanan = pelSnap.child("statusPencairanSimpanan").getValue(String::class.java) ?: ""
                        if (statusPencairanSimpanan == "Dicairkan") continue

                        val statusKhusus = (pelSnap.child("statusKhusus").getValue(String::class.java) ?: "").uppercase().replace(" ", "_")
                        if (statusKhusus == "MENUNGGU_PENCAIRAN") continue

                        // Hitung total dibayar
                        var totalDibayar = 0L
                        val pembayaranList = pelSnap.child("pembayaranList")

                        if (pembayaranList.exists()) {
                            for (paySnap in pembayaranList.children) {
                                totalDibayar += paySnap.child("jumlah").getValue(Long::class.java)
                                    ?: paySnap.child("jumlah").getValue(Int::class.java)?.toLong()
                                    ?: 0L

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

                        // Filter: lunas cicilan
                        val isLunasCicilan = totalPelunasan > 0 && totalDibayar >= totalPelunasan
                        if (!isLunasCicilan) continue

                        val simpanan = pelSnap.child("simpanan").getValue(Int::class.java) ?: 0
                        val besarPinjaman = pelSnap.child("besarPinjaman").getValue(Int::class.java) ?: 0
                        val namaPanggilan = pelSnap.child("namaPanggilan").getValue(String::class.java) ?: ""
                        val namaKtp = pelSnap.child("namaKtp").getValue(String::class.java) ?: ""
                        val wilayah = pelSnap.child("wilayah").getValue(String::class.java) ?: ""

                        list.add(NasabahLunasAllItem(
                            pelangganId = pelangganId,
                            namaPanggilan = namaPanggilan,
                            namaKtp = namaKtp,
                            wilayah = wilayah,
                            adminId = adminId,
                            adminName = adminName,
                            totalSimpanan = simpanan,
                            besarPinjaman = besarPinjaman,
                            totalPelunasan = totalPelunasan.toInt(),
                            totalDibayar = totalDibayar
                        ))

                        Log.d("NasabahLunasAll", "✅ Found: $namaPanggilan")
                    }
                } catch (e: Exception) {
                    Log.e("NasabahLunasAll", "Error loading admin $adminId: ${e.message}")
                }
            }

            nasabahLunasList = list.sortedBy { it.namaPanggilan.ifBlank { it.namaKtp } }
            Log.d("NasabahLunasAll", "✅ Total loaded: ${list.size} items")

        } catch (e: Exception) {
            Log.e("NasabahLunasAll", "Error loading: ${e.message}")
            errorMessage = "Gagal memuat data: ${e.message}"
        }

        isLoading = false
        isVisible = true
    }

    LaunchedEffect(adminSummaryList) {
        if (adminSummaryList.isNotEmpty()) {
            loadData()
        }
    }

    val totalSimpanan = remember(nasabahLunasList) {
        nasabahLunasList.sumOf { it.totalSimpanan }
    }

    Scaffold(
        containerColor = PimpinanColors.getBackground(isDark),
        topBar = {
            NasabahLunasAllTopBar(
                jumlahNasabah = nasabahLunasList.size,
                onBack = { navController.popBackStack() },
                onRefresh = { coroutineScope.launch { loadData() } },
                isDark = isDark
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
                NasabahLunasAllSummaryCard(
                    jumlahNasabah = nasabahLunasList.size,
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

            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF7C3AED))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Memuat data...",
                                color = PimpinanColors.getTextSecondary(isDark)
                            )
                        }
                    }
                }
                nasabahLunasList.isEmpty() -> {
                    NasabahLunasAllEmptyState(isDark = isDark)
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(nasabahLunasList, key = { "${it.adminId}_${it.pelangganId}" }) { item ->
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(300)) + slideInVertically(
                                    initialOffsetY = { 20 },
                                    animationSpec = tween(300)
                                )
                            ) {
                                NasabahLunasAllCard(item = item, isDark = isDark)
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
private fun NasabahLunasAllTopBar(
    jumlahNasabah: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    isDark: Boolean = false
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp),
        color = PimpinanColors.getCard(isDark)
    ) {
        Column {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Nasabah Lunas",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = PimpinanColors.getTextPrimary(isDark)
                        )
                        Text(
                            "$jumlahNasabah nasabah",
                            fontSize = 12.sp,
                            color = PimpinanColors.getTextSecondary(isDark)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            "Kembali",
                            tint = PimpinanColors.getTextPrimary(isDark)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Rounded.Refresh,
                            "Refresh",
                            tint = Color(0xFF7C3AED)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PimpinanColors.getCard(isDark)
                )
            )
        }
    }
}

@Composable
private fun NasabahLunasAllSummaryCard(
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
                    Brush.linearGradient(PimpinanColors.purpleGradient)
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
                        text = "$jumlahNasabah nasabah lunas",
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
                        Icons.Rounded.CheckCircle,
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
private fun NasabahLunasAllCard(
    item: NasabahLunasAllItem,
    isDark: Boolean = false
) {
    val badgeColor = Color(0xFF7C3AED)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark))
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
                            color = PimpinanColors.getTextPrimary(isDark)
                        )
                        Text(
                            text = item.wilayah,
                            fontSize = 12.sp,
                            color = PimpinanColors.getTextSecondary(isDark)
                        )
                    }
                }

                // Badge Lunas
                Surface(
                    color = badgeColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Lunas",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Info Simpanan & Besar Pinjaman
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Total Simpanan",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.totalSimpanan),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = badgeColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Besar Pinjaman",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.besarPinjaman),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = PimpinanColors.getTextPrimary(isDark)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = PimpinanColors.getBorder(isDark))
            Spacer(Modifier.height(8.dp))

            // Footer: Admin
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = PimpinanColors.getTextMuted(isDark),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "PDL: ${item.adminName}",
                    fontSize = 11.sp,
                    color = PimpinanColors.getTextSecondary(isDark)
                )
            }
        }
    }
}

@Composable
private fun NasabahLunasAllEmptyState(
    isDark: Boolean = false
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
                        Color(0xFF7C3AED).copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF7C3AED),
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Tidak ada nasabah lunas",
                color = PimpinanColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )
        }
    }
}
