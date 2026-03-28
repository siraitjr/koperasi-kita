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
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaftarNasabahLunasHariIniScreen(
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

    // Filter nasabah lunas hari ini
    val nasabahLunasHariIni = daftarPelanggan.filter { pelanggan ->
        // FIX: Exclude entry "Bunga..." agar konsisten dengan Cloud Functions & bukuPokokApi
        val totalBayar = pelanggan.pembayaranList
            .filter { !it.tanggal.startsWith("Bunga") }
            .sumOf { pay ->
                pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
            }
        val isLunas = totalBayar >= pelanggan.totalPelunasan.toLong() && pelanggan.totalPelunasan > 0
        val adaPembayaranHariIni = pelanggan.pembayaranList.any { pay ->
            pay.tanggal == today || pay.subPembayaran.any { sub -> sub.tanggal == today }
        }
        val menjadiLunasHariIni = isLunas && adaPembayaranHariIni
        menjadiLunasHariIni
    }

    // State untuk animasi
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    // Hitung total pinjaman yang lunas
    val totalPinjamanLunas = nasabahLunasHariIni.sumOf { it.totalPelunasan }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernNasabahLunasTopBarAdmin(
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
                ModernNasabahLunasSummaryCardAdmin(
                    jumlahNasabah = nasabahLunasHariIni.size,
                    totalPinjamanSelesai = totalPinjamanLunas
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content
            when {
                nasabahLunasHariIni.isEmpty() -> {
                    ModernEmptyNasabahLunasStateAdmin()
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(nasabahLunasHariIni, key = { it.id }) { pelanggan ->
                            ModernNasabahLunasCardAdmin(
                                pelanggan = pelanggan,
                                today = today,
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
private fun ModernNasabahLunasTopBarAdmin(
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
                    Brush.linearGradient(PimpinanColors.successGradient),
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
                        text = "Nasabah Lunas Hari Ini",
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
private fun ModernNasabahLunasSummaryCardAdmin(
    jumlahNasabah: Int,
    totalPinjamanSelesai: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = PimpinanColors.success.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(PimpinanColors.successGradient))
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
                        text = "Total Nasabah Lunas",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$jumlahNasabah orang",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Total Pinjaman Selesai",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatRupiah(totalPinjamanSelesai),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernNasabahLunasCardAdmin(
    pelanggan: Pelanggan,
    today: String,
    onClick: () -> Unit,
    viewModel: PelangganViewModel,
    isDark: Boolean,
    txtColor: Color
) {
    val displayName = viewModel.getDisplayName(pelanggan)
    val displayNik = viewModel.getDisplayNik(pelanggan)

    val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
        pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
    }
    val pembayaranHariIni = pelanggan.pembayaranList.filter { pay ->
        pay.tanggal == today || pay.subPembayaran.any { sub -> sub.tanggal == today }
    }.sumOf { pay ->
        pay.jumlah + pay.subPembayaran.filter { it.tanggal == today }.sumOf { it.jumlah }
    }

    // Dapatkan tanggal pelunasan
    val tanggalPelunasan = viewModel.getTanggalPelunasan(pelanggan)

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
                    // Avatar dengan gradient dan icon checkmark
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(PimpinanColors.successGradient),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
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
                        if (pelanggan.wilayah.isNotBlank()) {
                            Text(
                                text = pelanggan.wilayah,
                                fontSize = 12.sp,
                                color = PimpinanColors.textSecondary
                            )
                        }
                    }
                }

                // Badge LUNAS
                Surface(
                    color = PimpinanColors.success.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = null,
                            tint = PimpinanColors.success,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "LUNAS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PimpinanColors.success
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
                        text = "Total Pinjaman",
                        fontSize = 11.sp,
                        color = PimpinanColors.textMuted
                    )
                    Text(
                        text = formatRupiah(pelanggan.totalPelunasan),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Total Dibayar",
                        fontSize = 11.sp,
                        color = PimpinanColors.textMuted
                    )
                    Text(
                        text = formatRupiah(totalBayar.toInt()),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PimpinanColors.success
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = if (isDark) Color.White.copy(alpha = 0.1f) else PimpinanColors.lightBorder)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Today,
                        contentDescription = null,
                        tint = PimpinanColors.teal,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Bayar Hari Ini: ${formatRupiah(pembayaranHariIni)}",
                        fontSize = 11.sp,
                        color = PimpinanColors.teal,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Event,
                    contentDescription = null,
                    tint = Color(0xFF7B1FA2),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Tanggal Pelunasan: $tanggalPelunasan",
                    fontSize = 11.sp,
                    color = Color(0xFF7B1FA2),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ModernEmptyNasabahLunasStateAdmin() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        PimpinanColors.success.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = null,
                    tint = PimpinanColors.success,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Tidak ada nasabah yang lunas hari ini",
                color = PimpinanColors.textSecondary,
                fontSize = 14.sp
            )
        }
    }
}