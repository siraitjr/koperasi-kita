package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.utils.backgroundColor
import com.example.koperasikitagodangulu.utils.textColor
import androidx.compose.foundation.BorderStroke
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaftarNasabahBaruHariIniScreen(
    navController: NavController,
    viewModel: PelangganViewModel
) {
    val daftarPelanggan = viewModel.daftarPelanggan
    val isDark by viewModel.isDarkMode
    val txtColor = if (isDark) Color.White else PimpinanColors.textPrimary
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) PimpinanColors.darkBackground else PimpinanColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Hitung tanggal hari ini
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
    val today = dateFormat.format(Calendar.getInstance().time)

    // Filter nasabah baru hari ini
    val nasabahBaruHariIni = daftarPelanggan.filter { pelanggan ->
        if (pelanggan.tanggalPencairan.isNotBlank()) {
            pelanggan.tanggalPencairan == today && pelanggan.status == "Aktif"
        } else {
            pelanggan.tanggalDaftar == today || pelanggan.tanggalPengajuan == today
        }
    }
    // Pisahkan menjadi drop baru dan drop lama
    val dropBaru = nasabahBaruHariIni.filter { it.pinjamanKe <= 1 }
    val dropLama = nasabahBaruHariIni.filter { it.pinjamanKe > 1 }

    // Hitung total pinjaman masing-masing
    val totalPinjamanBaru = dropBaru.sumOf { it.besarPinjaman }
    val totalPinjamanLama = dropLama.sumOf { it.besarPinjaman }

    // State untuk animasi
    var isVisible by remember { mutableStateOf(false) }
    // State untuk kategori yang sedang ditampilkan: "baru", "lama", atau "semua"
    var selectedCategory by remember { mutableStateOf("semua") }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    // Hitung total pinjaman
    val totalPinjaman = nasabahBaruHariIni.sumOf { it.besarPinjaman }

    // List yang akan ditampilkan berdasarkan kategori
    val displayedList = when (selectedCategory) {
        "baru" -> dropBaru
        "lama" -> dropLama
        else -> nasabahBaruHariIni // "semua"
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernNasabahBaruTopBarAdmin(
                tanggal = today,
                onBack = { navController.popBackStack() }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Summary Card dengan animasi
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400)
                )
            ) {
                ModernNasabahBaruSummaryCardAdmin(
                    jumlahDropBaru = dropBaru.size,
                    totalPinjamanBaru = totalPinjamanBaru,
                    jumlahDropLama = dropLama.size,
                    totalPinjamanLama = totalPinjamanLama,
                    selectedCategory = selectedCategory,
                    onCategorySelected = { category -> selectedCategory = category }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content
            when {
                displayedList.isEmpty() -> {
                    ModernEmptyNasabahBaruStateAdmin(
                        category = selectedCategory
                    )
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(displayedList, key = { it.id }) { pelanggan ->
                            ModernNasabahBaruCardAdmin(
                                pelanggan = pelanggan,
                                onClick = {
                                    navController.navigate("detail_pelanggan/${pelanggan.id}")
                                },
                                viewModel = viewModel,
                                isDark = isDark,
                                txtColor = txtColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernNasabahBaruTopBarAdmin(
    tanggal: String,
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
        }
    }
}

@Composable
private fun ModernNasabahBaruSummaryCardAdmin(
    jumlahDropBaru: Int,
    totalPinjamanBaru: Int,
    jumlahDropLama: Int,
    totalPinjamanLama: Int,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    // Total keseluruhan
    val totalSemuaNasabah = jumlahDropBaru + jumlahDropLama
    val totalSemuaPinjaman = totalPinjamanBaru + totalPinjamanLama

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
            // ✅ Header: Total Drop Hari Ini + Total Pinjaman (seperti sebelumnya)
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
                            text = "Total Pinjaman",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatRupiah(totalSemuaPinjaman),
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
                            text = formatRupiah(totalPinjamanBaru),
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
                            text = formatRupiah(totalPinjamanLama),
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
private fun ModernNasabahBaruCardAdmin(
    pelanggan: Pelanggan,
    onClick: () -> Unit,
    viewModel: PelangganViewModel,
    isDark: Boolean,
    txtColor: Color
) {
    val displayName = viewModel.getDisplayName(pelanggan)
    val displayNik = viewModel.getDisplayNik(pelanggan)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
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
                    // Avatar dengan gradient
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
                            text = displayName.take(2).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            fontWeight = FontWeight.Bold,
                            color = txtColor,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "NIK: $displayNik",
                            fontSize = 12.sp,
                            color = PimpinanColors.textSecondary
                        )
                        if (pelanggan.wilayah.isNotBlank()) {
                            Text(
                                text = pelanggan.wilayah,
                                fontSize = 12.sp,
                                color = PimpinanColors.textSecondary
                            )
                        }
                    }
                }

                // Badge dinamis: BARU atau Pinjaman ke X
                val isNasabahBaru = pelanggan.pinjamanKe <= 1
                val badgeColor = if (isNasabahBaru) PimpinanColors.teal else PimpinanColors.purple
                val badgeText = if (isNasabahBaru) "BARU" else "Pinjaman ke ${pelanggan.pinjamanKe}"
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
                        color = PimpinanColors.textMuted
                    )
                    Text(
                        text = formatRupiah(pelanggan.besarPinjaman),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Status",
                        fontSize = 11.sp,
                        color = PimpinanColors.textMuted
                    )
                    Text(
                        text = pelanggan.status,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when (pelanggan.status) {
                            "Aktif" -> PimpinanColors.success
                            "Menunggu Approval" -> PimpinanColors.warning
                            else -> PimpinanColors.textSecondary
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernEmptyNasabahBaruStateAdmin(
    category: String = "semua"
) {
    val message = when (category) {
        "baru" -> "Tidak ada drop baru hari ini"
        "lama" -> "Tidak ada drop lama hari ini"
        else -> "Tidak ada drop hari ini"
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
                color = PimpinanColors.textSecondary,
                fontSize = 14.sp
            )
        }
    }
}