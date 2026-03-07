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
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// =========================================================================
// DATA CLASS
// =========================================================================
data class NasabahBaruItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val besarPinjaman: Int = 0,
    val totalDiterima: Int = 0,
    val wilayah: String = "",
    val tanggalDaftar: String = "",
    val pinjamanKe: Int = 1,
    val timestamp: Long = 0
)

// =========================================================================
// PIMPINAN DAFTAR NASABAH BARU SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanDaftarNasabahBaruScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val tanggalHariIni = SimpleDateFormat("dd MMM yyyy", Locale("id")).format(Date())
    val isDark by viewModel.isDarkMode
//    val bgColor = if (isDark) PimpinanColors.darkBackground else PimpinanColors.lightBackground
    val txtColor = if (isDark) Color.White else PimpinanColors.textPrimary
    // ✅ Set status bar & navigation bar sesuai tema dashboard
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val cabangId by viewModel.currentUserCabang.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // State untuk data
    var nasabahBaruList by remember { mutableStateOf<List<NasabahBaruItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    // ✅ BARU: State untuk kategori yang sedang ditampilkan
    var selectedCategory by remember { mutableStateOf("semua") }

    // Function untuk load data
    suspend fun loadData() {
        if (cabangId == null) return

        isLoading = true
        errorMessage = null

        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference

            val eventSnapshot = database.child("event_harian")
                .child(cabangId!!)
                .child(tanggalHariIni)
                .child("nasabah_baru")
                .get().await()

            if (eventSnapshot.exists() && eventSnapshot.childrenCount > 0) {
                val list = mutableListOf<NasabahBaruItem>()
                eventSnapshot.children.forEach { child ->
                    val item = NasabahBaruItem(
                        pelangganId = child.key ?: "",
                        namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                        namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                        adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                        adminName = child.child("adminName").getValue(String::class.java) ?: "",
                        besarPinjaman = child.child("besarPinjaman").getValue(Int::class.java) ?: 0,
                        totalDiterima = child.child("totalDiterima").getValue(Int::class.java) ?: 0,
                        wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
                        tanggalDaftar = child.child("tanggalDaftar").getValue(String::class.java) ?: tanggalHariIni,
                        pinjamanKe = child.child("pinjamanKe").getValue(Int::class.java) ?: 1,  // ✅ TAMBAH
                        timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0
                    )
                    list.add(item)
                }
                nasabahBaruList = list.sortedByDescending { it.timestamp }
                Log.d("NasabahBaru", "✅ Loaded ${list.size} from event_harian")
            } else {
                val pencairanSnapshot = database.child("pembayaran_harian")
                    .child(cabangId!!)
                    .child(tanggalHariIni)
                    .get().await()

                val list = mutableListOf<NasabahBaruItem>()
                pencairanSnapshot.children.forEach { child ->
                    val jenis = child.child("jenis").getValue(String::class.java) ?: ""
                    if (jenis == "pencairan") {
                        val item = NasabahBaruItem(
                            pelangganId = child.child("pelangganId").getValue(String::class.java) ?: "",
                            namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                            namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                            adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                            adminName = child.child("adminName").getValue(String::class.java) ?: "",
                            besarPinjaman = child.child("jumlah").getValue(Int::class.java) ?: 0,
                            totalDiterima = child.child("jumlah").getValue(Int::class.java) ?: 0,
                            tanggalDaftar = tanggalHariIni,
                            timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0
                        )
                        list.add(item)
                    }
                }
                nasabahBaruList = list.sortedByDescending { it.timestamp }
                Log.d("NasabahBaru", "✅ Loaded ${list.size} from pembayaran_harian (pencairan)")
            }

        } catch (e: Exception) {
            Log.e("NasabahBaru", "Error loading: ${e.message}")
            errorMessage = "Gagal memuat data: ${e.message}"
        }

        isLoading = false
        isVisible = true
    }

    LaunchedEffect(cabangId) {
        loadData()
    }

    val totalPinjaman = nasabahBaruList.sumOf { it.besarPinjaman }
    val totalDicairkan = nasabahBaruList.sumOf { it.totalDiterima }
    // ✅ BARU: Pisahkan menjadi drop baru dan drop lama
    val dropBaru = nasabahBaruList.filter { it.pinjamanKe <= 1 }
    val dropLama = nasabahBaruList.filter { it.pinjamanKe > 1 }

    // ✅ BARU: Hitung total pencairan masing-masing
    val totalPencairanBaru = dropBaru.sumOf { it.totalDiterima }
    val totalPencairanLama = dropLama.sumOf { it.totalDiterima }

    // ✅ BARU: List yang akan ditampilkan berdasarkan kategori
    val displayedList = when (selectedCategory) {
        "baru" -> dropBaru
        "lama" -> dropLama
        else -> nasabahBaruList
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernNasabahBaruTopBar(
                tanggal = tanggalHariIni,
                onBack = { navController.popBackStack() },
                onRefresh = { coroutineScope.launch { loadData() } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                ModernNasabahBaruSummaryCard(
                    jumlahDropBaru = dropBaru.size,
                    totalPencairanBaru = totalPencairanBaru,
                    jumlahDropLama = dropLama.size,
                    totalPencairanLama = totalPencairanLama,
                    totalPencairanSemua = totalDicairkan,
                    selectedCategory = selectedCategory,
                    onCategorySelected = { category -> selectedCategory = category }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Loading / Error / Content
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PimpinanColors.teal)
                    }
                }
                errorMessage != null -> {
                    ModernErrorState(
                        message = errorMessage!!,
                        onRetry = { coroutineScope.launch { loadData() } }
                    )
                }
                displayedList.isEmpty() -> {
                    ModernEmptyNasabahBaruState(
                        isDark = isDark,
                        category = selectedCategory
                    )
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(displayedList, key = { it.pelangganId }) { item ->
                            ModernNasabahBaruCard(item, isDark, txtColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernNasabahBaruTopBar(
    tanggal: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit
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
                    Brush.linearGradient(PimpinanColors.tealGradient),
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                )
        ) {
            // Decorative circles
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = (-20).dp, y = (-20).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 15.dp, y = (-10).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            text = "Drop Hari Ini",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tanggal,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(onClick = onRefresh) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernNasabahBaruSummaryCard(
    jumlahDropBaru: Int,
    totalPencairanBaru: Int,
    jumlahDropLama: Int,
    totalPencairanLama: Int,
    totalPencairanSemua: Int,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    // Total keseluruhan
    val totalSemuaNasabah = jumlahDropBaru + jumlahDropLama

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = PimpinanColors.teal.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E293B), Color(0xFF334155))
                    )
                )
        ) {
            // ✅ Header: Total Drop Hari Ini + Total Pencairan (seperti sebelumnya)
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Decorative circle
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = 30.dp, y = (-30).dp)
                        .background(Color.White.copy(alpha = 0.1f), CircleShape)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Drop Hari Ini",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$totalSemuaNasabah orang",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Total Pencairan",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatRupiah(totalPencairanSemua),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ✅ BARU: Dua card clickable untuk filter: Drop Baru dan Drop Lama
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
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
                            PimpinanColors.teal else Color.White.copy(alpha = 0.1f)
                    ),
                    border = if (selectedCategory == "baru") null
                    else BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FiberNew,
                                contentDescription = null,
                                tint = if (selectedCategory == "baru") Color.White
                                else PimpinanColors.teal,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Drop Baru",
                                color = if (selectedCategory == "baru") Color.White
                                else Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "$jumlahDropBaru orang",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatRupiah(totalPencairanBaru),
                            color = if (selectedCategory == "baru") Color.White.copy(alpha = 0.9f)
                            else Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
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
                            PimpinanColors.purple else Color.White.copy(alpha = 0.1f)
                    ),
                    border = if (selectedCategory == "lama") null
                    else BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = if (selectedCategory == "lama") Color.White
                                else PimpinanColors.purple,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Drop Lama",
                                color = if (selectedCategory == "lama") Color.White
                                else Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "$jumlahDropLama orang",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatRupiah(totalPencairanLama),
                            color = if (selectedCategory == "lama") Color.White.copy(alpha = 0.9f)
                            else Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernNasabahBaruCard(
    item: NasabahBaruItem,
    isDark: Boolean,
    txtColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) PimpinanColors.darkCard else Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(PimpinanColors.tealGradient),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (item.namaPanggilan.ifBlank { item.namaKtp }).take(2).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.namaPanggilan.ifBlank { item.namaKtp },
                            fontWeight = FontWeight.Bold,
                            color = txtColor,
                            fontSize = 15.sp
                        )
                        if (item.wilayah.isNotBlank()) {
                            Text(
                                text = item.wilayah,
                                fontSize = 12.sp,
                                color = PimpinanColors.getTextSecondary(isDark)
                            )
                        }
                    }
                }

                // Badge dinamis: BARU atau Pinjaman ke X
                val isNasabahBaru = item.pinjamanKe <= 1
                val badgeColor = if (isNasabahBaru) PimpinanColors.teal else PimpinanColors.purple
                val badgeText = if (isNasabahBaru) "BARU" else "Pinjaman ke ${item.pinjamanKe}"
                val badgeIcon = if (isNasabahBaru) Icons.Rounded.FiberNew else Icons.Rounded.Refresh

                Surface(
                    color = badgeColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = badgeIcon,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(14.dp)
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

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Pinjaman",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.besarPinjaman),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Diterima",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.totalDiterima),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PimpinanColors.success
                    )
                }
            }

            if (item.adminName.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = PimpinanColors.getBorder(isDark))
                Spacer(Modifier.height(8.dp))
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
}

@Composable
private fun ModernEmptyNasabahBaruState(
    isDark: Boolean = false,
    category: String = "semua"
) {
    val message = when (category) {
        "baru" -> "Tidak ada drop baru hari ini"
        "lama" -> "Tidak ada drop lama hari ini"
        else -> "Tidak ada nasabah baru hari ini"
    }

    val icon = when (category) {
        "baru" -> Icons.Rounded.FiberNew
        "lama" -> Icons.Rounded.Refresh
        else -> Icons.Rounded.PersonAdd
    }

    val iconColor = when (category) {
        "baru" -> PimpinanColors.teal
        "lama" -> PimpinanColors.purple
        else -> PimpinanColors.teal
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        iconColor.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = PimpinanColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ModernErrorState(
    message: String,
    onRetry: () -> Unit
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
                        PimpinanColors.danger.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = null,
                    tint = PimpinanColors.danger,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = PimpinanColors.danger,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PimpinanColors.danger
                )
            ) {
                Text("Coba Lagi")
            }
        }
    }
}