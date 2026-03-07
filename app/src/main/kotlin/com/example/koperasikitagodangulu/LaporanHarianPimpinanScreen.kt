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
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.backgroundColor
import com.example.koperasikitagodangulu.utils.textColor
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// =========================================================================
// DATA CLASS
// =========================================================================
data class PembayaranHarianItem(
    val id: String = "",
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val jumlah: Int = 0,
    val jenis: String = "", // "cicilan", "tambah_bayar", "pencairan"
    val tanggal: String = "",
    val timestamp: Long = 0
)

// =========================================================================
// LAPORAN HARIAN PIMPINAN SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaporanHarianPimpinanScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel,
    targetAdminId: String
) {
    val tanggalHariIni = SimpleDateFormat("dd MMM yyyy", Locale("id")).format(Date())
    val isDark by viewModel.isDarkMode
    val txtColor = if (isDark) Color.White else PimpinanColors.textPrimary
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) PimpinanColors.darkBackground else PimpinanColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val cabangId by viewModel.currentUserCabang.collectAsState()

    // State untuk data
    var pembayaranList by remember { mutableStateOf<List<PembayaranHarianItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var adminName by remember { mutableStateOf("Admin") }
    var isVisible by remember { mutableStateOf(false) }

    var biayaAwalHariIni by remember { mutableStateOf(0) }
    var kasirUangKasList by remember { mutableStateOf<List<PembayaranHarianItem>>(emptyList()) }

    // Load data dari node pembayaran_harian
    LaunchedEffect(cabangId, targetAdminId) {
        if (cabangId != null) {
            isLoading = true
            try {
                val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference

                val snapshot = database.child("pembayaran_harian")
                    .child(cabangId!!)
                    .child(tanggalHariIni)
                    .get().await()

                val list = mutableListOf<PembayaranHarianItem>()
                snapshot.children.forEach { child ->
                    val item = PembayaranHarianItem(
                        id = child.key ?: "",
                        pelangganId = child.child("pelangganId").getValue(String::class.java) ?: "",
                        namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                        namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                        adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                        adminName = child.child("adminName").getValue(String::class.java) ?: "",
                        jumlah = child.child("jumlah").getValue(Int::class.java) ?: 0,
                        jenis = child.child("jenis").getValue(String::class.java) ?: "",
                        tanggal = child.child("tanggal").getValue(String::class.java) ?: "",
                        timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0
                    )

                    if (item.adminUid == targetAdminId) {
                        list.add(item)
                        if (adminName == "Admin" && item.adminName.isNotBlank()) {
                            adminName = item.adminName
                        }
                    }
                }

                pembayaranList = list.sortedByDescending { it.timestamp }

                // Load biaya awal untuk admin ini
                try {
                    val tanggalFormatted = SimpleDateFormat("yyyy-MM-dd", Locale("id")).format(Date())
                    val biayaAwalSnapshot = database.child("biaya_awal")
                        .child(targetAdminId)
                        .child(tanggalFormatted)
                        .get().await()

                    biayaAwalHariIni = biayaAwalSnapshot.child("jumlah").getValue(Int::class.java) ?: 0
                    Log.d("LaporanHarian", "✅ Biaya awal: $biayaAwalHariIni")
                } catch (e: Exception) {
                    Log.e("LaporanHarian", "Error load biaya awal: ${e.message}")
                    biayaAwalHariIni = 0
                }

                // Load uang kas dari kasir untuk admin ini
                try {
                    val bulanKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
                    val kasirSnap = database.child("kasir_entries")
                        .child(cabangId!!)
                        .child(bulanKey)
                        .get().await()

                    val kasirItems = mutableListOf<PembayaranHarianItem>()
                    kasirSnap.children.forEach { entrySnap ->
                        val jenis = entrySnap.child("jenis").getValue(String::class.java) ?: ""
                        val arah = entrySnap.child("arah").getValue(String::class.java) ?: ""
                        val tanggal = entrySnap.child("tanggal").getValue(String::class.java) ?: ""
                        val targetUid = entrySnap.child("targetAdminUid").getValue(String::class.java) ?: ""

                        if (jenis == "uang_kas" && arah == "keluar" && tanggal == tanggalHariIni && targetUid == targetAdminId) {
                            val jumlah = entrySnap.child("jumlah").getValue(Int::class.java) ?: 0
                            val createdByName = entrySnap.child("createdByName").getValue(String::class.java) ?: "Kasir"
                            val keterangan = entrySnap.child("keterangan").getValue(String::class.java) ?: ""

                            kasirItems.add(PembayaranHarianItem(
                                id = entrySnap.key ?: "",
                                namaPanggilan = createdByName,
                                namaKtp = if (keterangan.isNotBlank()) "Uang Kas: $keterangan" else "Uang Kas dari Kasir",
                                jumlah = jumlah,
                                jenis = "uang_kas_kasir",
                                tanggal = tanggal,
                                timestamp = entrySnap.child("createdAt").getValue(Long::class.java) ?: 0
                            ))
                        }
                    }
                    kasirUangKasList = kasirItems
                    Log.d("LaporanHarian", "✅ Kasir uang kas: ${kasirItems.size} entries")
                } catch (e: Exception) {
                    Log.e("LaporanHarian", "Error load kasir uang kas: ${e.message}")
                    kasirUangKasList = emptyList()
                }

                if (adminName == "Admin") {
                    val adminSummary = viewModel.adminSummary.value.find { it.adminId == targetAdminId }
                    adminName = adminSummary?.adminName ?: adminSummary?.adminEmail ?: "Admin"
                }

                Log.d("LaporanHarian", "✅ Loaded ${list.size} transactions for admin $targetAdminId")

            } catch (e: Exception) {
                Log.e("LaporanHarian", "Error loading: ${e.message}")
            }
            isLoading = false
            isVisible = true
        }
    }

    val penerimaan = pembayaranList.filter { it.jenis == "cicilan" || it.jenis == "tambah_bayar" }
    val pengeluaran = pembayaranList.filter { it.jenis == "pencairan" }

    val totalPenerimaanDariNasabah = penerimaan.sumOf { it.jumlah }
    val totalKasirUangKas = kasirUangKasList.sumOf { it.jumlah }
    val totalPenerimaan = totalPenerimaanDariNasabah + biayaAwalHariIni + totalKasirUangKas
    val totalPengeluaran = pengeluaran.sumOf { it.jumlah }
    val netCashFlow = totalPenerimaan - totalPengeluaran

    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernLaporanTopBar(
                title = "Laporan Harian",
                subtitle = adminName,
                onBack = { navController.popBackStack() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Header Date
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400)
                )
            ) {
                Text(
                    text = "Tanggal: $tanggalHariIni",
                    color = PimpinanColors.getTextSecondary(isDark),
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Net Cash Flow Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                ModernNetCashFlowCard(
                    netCashFlow = netCashFlow,
                    totalPenerimaan = totalPenerimaan,
                    totalPengeluaran = totalPengeluaran
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tab Row
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 20 },
                    animationSpec = tween(400, delayMillis = 200)
                )
            ) {
                ModernTabRow(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    penerimaanCount = penerimaan.size,
                    pengeluaranCount = pengeluaran.size,
                    isDark = isDark
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Loading
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PimpinanColors.primary)
                }
            } else {
                // Tab Content
                when (selectedTab) {
                    0 -> { // Penerimaan
                        if (penerimaan.isEmpty()) {
                            ModernEmptyTransactionState(
                                message = "Tidak ada penerimaan hari ini",
                                icon = Icons.Rounded.CallReceived,
                                isDark = isDark
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Tampilkan biaya awal di paling atas jika ada
                                if (biayaAwalHariIni > 0) {
                                    item {
                                        ModernPembayaranItemCard(
                                            item = PembayaranHarianItem(
                                                id = "biaya_awal",
                                                namaPanggilan = "Biaya Awal",
                                                namaKtp = "Uang Modal Hari Ini",
                                                jumlah = biayaAwalHariIni,
                                                jenis = "biaya_awal"
                                            ),
                                            isDark = isDark,
                                            isIncome = true
                                        )
                                    }
                                }
                                // Tampilkan uang kas dari kasir
                                if (kasirUangKasList.isNotEmpty()) {
                                    items(kasirUangKasList, key = { it.id }) { item ->
                                        ModernPembayaranItemCard(
                                            item = item,
                                            isDark = isDark,
                                            isIncome = true
                                        )
                                    }
                                }

                                items(penerimaan, key = { it.id }) { item ->
                                    ModernPembayaranItemCard(
                                        item = item,
                                        isDark = isDark,
                                        isIncome = true
                                    )
                                }

                                item {
                                    ModernTotalCard(
                                        label = "Total Penerimaan",
                                        amount = totalPenerimaan,
                                        isIncome = true
                                    )
                                }
                            }
                        }
                    }

                    1 -> { // Pengeluaran
                        if (pengeluaran.isEmpty()) {
                            ModernEmptyTransactionState(
                                message = "Tidak ada pengeluaran hari ini",
                                icon = Icons.Rounded.CallMade,
                                isDark = isDark
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(pengeluaran, key = { it.id }) { item ->
                                    ModernPembayaranItemCard(
                                        item = item,
                                        isDark = isDark,
                                        isIncome = false
                                    )
                                }

                                item {
                                    ModernTotalCard(
                                        label = "Total Pengeluaran",
                                        amount = totalPengeluaran,
                                        isIncome = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernLaporanTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
            ),
        color = Color.Transparent,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(PimpinanColors.primaryGradient),
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                )
        ) {
            // Decorative circle
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = (-20).dp, y = (-20).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Kembali",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernNetCashFlowCard(
    netCashFlow: Int,
    totalPenerimaan: Int,
    totalPengeluaran: Int
) {
    val isPositive = netCashFlow >= 0
    val gradient = if (isPositive) PimpinanColors.successGradient else PimpinanColors.dangerGradient

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = gradient.first().copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradient))
        ) {
            // Decorative circle
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 30.dp, y = (-30).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isPositive) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Net Cash Flow",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = formatRupiah(netCashFlow),
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Penerimaan",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        Text(
                            text = formatRupiah(totalPenerimaan),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Pengeluaran",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        Text(
                            text = formatRupiah(totalPengeluaran),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernTabRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    penerimaanCount: Int,
    pengeluaranCount: Int,
    isDark: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Penerimaan Tab
            Surface(
                onClick = { onTabSelected(0) },
                shape = RoundedCornerShape(12.dp),
                color = if (selectedTab == 0)
                    PimpinanColors.success.copy(alpha = 0.1f)
                else
                    Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallReceived,
                        contentDescription = null,
                        tint = if (selectedTab == 0) PimpinanColors.success else PimpinanColors.getTextMuted(isDark),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Penerimaan ($penerimaanCount)",
                        fontSize = 13.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedTab == 0) PimpinanColors.success else PimpinanColors.getTextSecondary(isDark)
                    )
                }
            }

            // Pengeluaran Tab
            Surface(
                onClick = { onTabSelected(1) },
                shape = RoundedCornerShape(12.dp),
                color = if (selectedTab == 1)
                    PimpinanColors.danger.copy(alpha = 0.1f)
                else
                    Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallMade,
                        contentDescription = null,
                        tint = if (selectedTab == 1) PimpinanColors.danger else PimpinanColors.getTextMuted(isDark),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pengeluaran ($pengeluaranCount)",
                        fontSize = 13.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedTab == 1) PimpinanColors.danger else PimpinanColors.getTextSecondary(isDark)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernPembayaranItemCard(
    item: PembayaranHarianItem,
    isDark: Boolean,
    isIncome: Boolean
) {
    val jenisText = when (item.jenis) {
        "cicilan" -> "Cicilan Pinjaman"
        "tambah_bayar" -> "Tambah Bayar"
        "pencairan" -> "Pencairan Pinjaman"
        "biaya_awal" -> "Uang Modal Hari Ini"
        "uang_kas_kasir" -> item.namaKtp
        else -> item.jenis
    }

    val amountColor = if (isIncome) PimpinanColors.success else PimpinanColors.danger

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) PimpinanColors.darkCard else Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            amountColor.copy(alpha = 0.1f),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isIncome) Icons.Rounded.CallReceived else Icons.Rounded.CallMade,
                        contentDescription = null,
                        tint = amountColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = item.namaPanggilan.ifBlank { item.namaKtp },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = if (isDark) Color.White else PimpinanColors.textPrimary
                    )
                    Text(
                        text = jenisText,
                        fontSize = 12.sp,
                        color = PimpinanColors.getTextSecondary(isDark)
                    )
                }
            }

            Text(
                text = formatRupiah(item.jumlah),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = amountColor
            )
        }
    }
}

@Composable
private fun ModernTotalCard(
    label: String,
    amount: Int,
    isIncome: Boolean
) {
    val gradient = if (isIncome) PimpinanColors.successGradient else PimpinanColors.dangerGradient

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradient))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = formatRupiah(amount),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ModernEmptyTransactionState(
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                        PimpinanColors.getTextMuted(isDark).copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PimpinanColors.getTextMuted(isDark),
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = PimpinanColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )
        }
    }
}