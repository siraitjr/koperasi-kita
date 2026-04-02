package com.example.koperasikitagodangulu

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.example.koperasikitagodangulu.SubPembayaran

// Modern Color Palette
private object ReportColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))

    val darkBackground = Color(0xFF0F172A)
    val darkSurface = Color(0xFF1E293B)
    val darkCard = Color(0xFF334155)
    val darkBorder = Color(0xFF475569)
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFE2E8F0)

    val primary = Color(0xFF6366F1)
    val success = Color(0xFF10B981)
    val danger = Color(0xFFEF4444)
    val warning = Color(0xFFF59E0B)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaporanHarianScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val tanggalHariIni = SimpleDateFormat("dd MMM yyyy", Locale("id")).format(Date())
    val isDark by viewModel.isDarkMode

    val cardColor = if (isDark) ReportColors.darkCard else ReportColors.lightSurface
    val borderColor = if (isDark) ReportColors.darkBorder else ReportColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) ReportColors.darkBackground else ReportColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Data transaksi hari ini
    val pembayaranHariIni = remember { mutableStateListOf<TransaksiData>() }
    val pinjamanBaruHariIni = remember { mutableStateListOf<TransaksiData>() }

    // Biaya awal hari ini
    val biayaAwalHariIni by viewModel.biayaAwalHariIni.collectAsState()

    // Uang kas dari kasir hari ini
    val kasirUangKasHariIni by viewModel.kasirUangKasHariIni.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadBiayaAwalHariIni()
        viewModel.loadKasirUangKasHariIni()
    }

    LaunchedEffect(viewModel.daftarPelanggan.size, tanggalHariIni, biayaAwalHariIni, kasirUangKasHariIni) {
        pembayaranHariIni.clear()
        pinjamanBaruHariIni.clear()

        viewModel.daftarPelanggan.forEach { pelanggan ->
            @Suppress("UNCHECKED_CAST")
            val safePembayaranList = (pelanggan.pembayaranList as List<Pembayaran?>).filterNotNull()
            safePembayaranList.forEach { pembayaran ->
                if (pembayaran.tanggal == tanggalHariIni) {
                    pembayaranHariIni.add(TransaksiData(
                        nama = pelanggan.namaPanggilan,
                        jenisTransaksi = "Cicilan Pinjaman",
                        jumlah = pembayaran.jumlah
                    ))
                }

                @Suppress("UNCHECKED_CAST")
                val safeSubList = (pembayaran.subPembayaran as List<SubPembayaran?>).filterNotNull()
                safeSubList.forEach { subPembayaran ->
                    if (subPembayaran.tanggal == tanggalHariIni) {
                        pembayaranHariIni.add(TransaksiData(
                            nama = pelanggan.namaPanggilan,
                            jenisTransaksi = "Tambah Bayar",
                            jumlah = subPembayaran.jumlah
                        ))
                    }
                }
            }

            // ✅ PERBAIKAN: Hanya tampilkan nasabah yang SUDAH DISETUJUI (status Aktif)
            // dan tanggal pencairan adalah hari ini (tanggalApproval)
            if (pelanggan.status == "Aktif" &&
                pelanggan.tanggalApproval == tanggalHariIni &&
                pelanggan.besarPinjaman > 0) {
                val jumlahDiberikan = getJumlahYangDiberikan(pelanggan)
                pinjamanBaruHariIni.add(TransaksiData(
                    nama = pelanggan.namaPanggilan,
                    jenisTransaksi = "Pencairan Pinjaman Baru",
                    jumlah = jumlahDiberikan
                ))
            }
        }
        // Pelunasan sisa utang lama dari lanjut pinjaman (top-up) yang dicairkan hari ini
        // Sisa utang tidak masuk pembayaranList tapi tetap dicatat sebagai penerimaan
        viewModel.daftarPelanggan.forEach { pelanggan ->
            if (pelanggan.pinjamanKe > 1 &&
                pelanggan.sisaUtangLamaSebelumTopUp > 0 &&
                pelanggan.tanggalPencairan == tanggalHariIni &&
                pelanggan.status == "Aktif") {
                pembayaranHariIni.add(TransaksiData(
                    nama = pelanggan.namaPanggilan,
                    jenisTransaksi = "Pelunasan Sisa Utang Lama",
                    jumlah = pelanggan.sisaUtangLamaSebelumTopUp
                ))
            }
        }
        // Tambahkan biaya awal jika ada
        if (biayaAwalHariIni > 0) {
            pembayaranHariIni.add(0, TransaksiData(
                nama = "Biaya Awal",
                jenisTransaksi = "Uang Modal Hari Ini",
                jumlah = biayaAwalHariIni
            ))
        }
        // Tambahkan uang kas dari kasir
        kasirUangKasHariIni.forEach { (namaKasir, keterangan, jumlah) ->
            pembayaranHariIni.add(TransaksiData(
                nama = namaKasir,
                jenisTransaksi = if (keterangan.isNotBlank()) "Uang Kas: $keterangan" else "Uang Kas dari Kasir",
                jumlah = jumlah
            ))
        }
    }

    val totalPembayaran = pembayaranHariIni.sumOf { it.jumlah }
    val totalPinjamanBaru = pinjamanBaruHariIni.sumOf { it.jumlah }
    val netCashFlow = totalPembayaran - totalPinjamanBaru

    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernReportTopBar(
                title = "Laporan Harian",
                tanggal = tanggalHariIni,
                isDark = isDark,
                txtColor = txtColor,
                subtitleColor = subtitleColor,
                onBackClick = { navController.popBackStack() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Net Cash Flow Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -30 },
                    animationSpec = tween(400)
                )
            ) {
                ModernCashFlowCard(
                    netCashFlow = netCashFlow,
                    totalPembayaran = totalPembayaran,
                    totalPengeluaran = totalPinjamanBaru,
                    isDark = isDark,
                    cardColor = cardColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            // Modern Tab Row
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                ModernTabRow(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    isDark = isDark,
                    cardColor = cardColor
                )
            }

            // Tab Content
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 200)
                )
            ) {
                when (selectedTab) {
                    0 -> PenerimaanContent(
                        pembayaranHariIni = pembayaranHariIni,
                        totalPembayaran = totalPembayaran,
                        isDark = isDark,
                        cardColor = cardColor,
                        borderColor = borderColor,
                        txtColor = txtColor,
                        subtitleColor = subtitleColor
                    )
                    1 -> PengeluaranContent(
                        pinjamanBaruHariIni = pinjamanBaruHariIni,
                        totalPinjamanBaru = totalPinjamanBaru,
                        isDark = isDark,
                        cardColor = cardColor,
                        borderColor = borderColor,
                        txtColor = txtColor,
                        subtitleColor = subtitleColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernReportTopBar(
    title: String,
    tanggal: String,
    isDark: Boolean,
    txtColor: Color,
    subtitleColor: Color,
    onBackClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) ReportColors.darkSurface else Color.White,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Kembali",
                    tint = txtColor
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = txtColor
                )
                Text(
                    text = tanggal,
                    fontSize = 13.sp,
                    color = subtitleColor
                )
            }

            // Spacer for balance
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}

@Composable
private fun ModernCashFlowCard(
    netCashFlow: Int,
    totalPembayaran: Int,
    totalPengeluaran: Int,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    val netColor = when {
        netCashFlow > 0 -> ReportColors.success
        netCashFlow < 0 -> ReportColors.danger
        else -> txtColor
    }

    val netGradient = when {
        netCashFlow > 0 -> ReportColors.successGradient
        netCashFlow < 0 -> ReportColors.dangerGradient
        else -> ReportColors.primaryGradient
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = netColor.copy(alpha = 0.2f),
                spotColor = netColor.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(netGradient))
        ) {
            // Decorative elements
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .offset(x = (-30).dp, y = (-30).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 20.dp, y = 20.dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                netCashFlow > 0 -> Icons.Rounded.TrendingUp
                                netCashFlow < 0 -> Icons.Rounded.TrendingDown
                                else -> Icons.Rounded.Remove
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Kasbon & Titipan",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${if (netCashFlow >= 0) "+" else ""}Rp ${formatRupiah(netCashFlow)}",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Penerimaan
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Penerimaan",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "Rp ${formatRupiah(totalPembayaran)}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Pengeluaran
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Pengeluaran",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "Rp ${formatRupiah(totalPengeluaran)}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
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
    isDark: Boolean,
    cardColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Penerimaan Tab
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (selectedTab == 0) ReportColors.success else Color.Transparent,
                onClick = { onTabSelected(0) }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = null,
                        tint = if (selectedTab == 0) Color.White else ReportColors.success,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Penerimaan",
                        color = if (selectedTab == 0) Color.White
                        else if (isDark) Color.White else Color(0xFF1E293B),
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }

            // Pengeluaran Tab
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (selectedTab == 1) ReportColors.danger else Color.Transparent,
                onClick = { onTabSelected(1) }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = null,
                        tint = if (selectedTab == 1) Color.White else ReportColors.danger,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Pengeluaran",
                        color = if (selectedTab == 1) Color.White
                        else if (isDark) Color.White else Color(0xFF1E293B),
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PenerimaanContent(
    pembayaranHariIni: List<TransaksiData>,
    totalPembayaran: Int,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    if (pembayaranHariIni.isEmpty()) {
        ModernEmptyState(
            icon = Icons.Rounded.AccountBalanceWallet,
            title = "Belum Ada Penerimaan",
            message = "Tidak ada penerimaan hari ini",
            color = ReportColors.success,
            isDark = isDark
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pembayaranHariIni) { transaksi ->
                ModernTransactionCard(
                    nama = transaksi.nama,
                    deskripsi = transaksi.jenisTransaksi,
                    jumlah = transaksi.jumlah,
                    isDark = isDark,
                    cardColor = cardColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor,
                    amountColor = ReportColors.success,
                    icon = Icons.Rounded.ArrowDownward
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                ModernTotalCard(
                    label = "Total Penerimaan",
                    total = totalPembayaran,
                    color = ReportColors.success,
                    gradient = ReportColors.successGradient
                )
            }
        }
    }
}

@Composable
private fun PengeluaranContent(
    pinjamanBaruHariIni: List<TransaksiData>,
    totalPinjamanBaru: Int,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    if (pinjamanBaruHariIni.isEmpty()) {
        ModernEmptyState(
            icon = Icons.Rounded.CreditCard,
            title = "Belum Ada Pengeluaran",
            message = "Tidak ada pengeluaran hari ini",
            color = ReportColors.danger,
            isDark = isDark
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pinjamanBaruHariIni) { transaksi ->
                ModernTransactionCard(
                    nama = transaksi.nama,
                    deskripsi = transaksi.jenisTransaksi,
                    jumlah = transaksi.jumlah,
                    isDark = isDark,
                    cardColor = cardColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor,
                    amountColor = ReportColors.danger,
                    icon = Icons.Rounded.ArrowUpward
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                ModernTotalCard(
                    label = "Total Pengeluaran",
                    total = totalPinjamanBaru,
                    color = ReportColors.danger,
                    gradient = ReportColors.dangerGradient
                )
            }
        }
    }
}

@Composable
private fun ModernTransactionCard(
    nama: String,
    deskripsi: String,
    jumlah: Int,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color,
    amountColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = amountColor.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(amountColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = amountColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = nama,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor,
                        fontSize = 15.sp
                    )
                    Text(
                        text = deskripsi,
                        fontSize = 12.sp,
                        color = subtitleColor
                    )
                }
            }

            Text(
                text = "Rp ${formatRupiah(jumlah)}",
                fontWeight = FontWeight.Bold,
                color = amountColor,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun ModernTotalCard(
    label: String,
    total: Int,
    color: Color,
    gradient: List<Color>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = color.copy(alpha = 0.2f)
            ),
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
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Text(
                    text = "Rp ${formatRupiah(total)}",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ModernEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    color: Color,
    isDark: Boolean
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(color.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color.White else Color(0xFF1E293B),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                fontSize = 14.sp,
                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                textAlign = TextAlign.Center
            )
        }
    }
}

// Data class untuk transaksi (sudah ada)
data class TransaksiData(
    val nama: String,
    val jenisTransaksi: String,
    val jumlah: Int
)

private fun getJumlahYangDiberikan(pelanggan: Pelanggan): Int {
    return when {
        pelanggan.totalDiterima > 0 -> pelanggan.totalDiterima
        pelanggan.admin > 0 && pelanggan.simpanan > 0 -> {
            pelanggan.besarPinjaman - pelanggan.admin - pelanggan.simpanan
        }
        else -> {
            val admin = (pelanggan.besarPinjaman * 5) / 100
            val simpanan = (pelanggan.besarPinjaman * 5) / 100
            pelanggan.besarPinjaman - admin - simpanan
        }
    }.coerceAtLeast(0)
}