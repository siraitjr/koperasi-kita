package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.koperasikitagodangulu.utils.formatRupiah
import android.widget.Toast
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// Modern Color Palette untuk Pencairan
private object PencairanColors {
    val primaryGradient = listOf(Color(0xFF7C3AED), Color(0xFFA78BFA))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))

    val darkBackground = Color(0xFF0F172A)
    val darkSurface = Color(0xFF1E293B)
    val darkCard = Color(0xFF334155)
    val darkBorder = Color(0xFF475569)
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFE2E8F0)

    val purple = Color(0xFF7C3AED)
    val success = Color(0xFF10B981)
    val warning = Color(0xFFF59E0B)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaftarMenungguPencairanScreen(
    navController: NavController,
    viewModel: PelangganViewModel
) {
    val pelangganMenunggu = viewModel.getPelangganMenungguPencairan()
    val isDark by viewModel.isDarkMode
    val context = LocalContext.current

    val cardColor = if (isDark) PencairanColors.darkCard else PencairanColors.lightSurface
    val borderColor = if (isDark) PencairanColors.darkBorder else PencairanColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) PencairanColors.darkBackground else PencairanColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // State untuk search
    var searchQuery by remember { mutableStateOf("") }
    val filteredPelanggan = remember(pelangganMenunggu, searchQuery) {
        if (searchQuery.isBlank()) {
            pelangganMenunggu
        } else {
            pelangganMenunggu.filter { pelanggan ->
                pelanggan.namaPanggilan.contains(searchQuery, ignoreCase = true) ||
                        pelanggan.nik.contains(searchQuery, ignoreCase = true) ||
                        pelanggan.wilayah.contains(searchQuery, ignoreCase = true) ||
                        pelanggan.namaKtp.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // State untuk dialog konfirmasi (Cairkan Semua - langsung)
    var showConfirmDialog by remember { mutableStateOf(false) }
    var selectedPelanggan by remember { mutableStateOf<Pelanggan?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    // State untuk dialog Cairkan Setengah (butuh persetujuan koordinator)
    var showSetengahDialog by remember { mutableStateOf(false) }
    var selectedPelangganSetengah by remember { mutableStateOf<Pelanggan?>(null) }
    var isSubmittingSetengah by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernPencairanTopBar(
                jumlahNasabah = pelangganMenunggu.size,
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
                .background(bgColor)
                .padding(padding)
        ) {
            // Modern Search Bar
            if (pelangganMenunggu.isNotEmpty()) {
                ModernSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            when {
                pelangganMenunggu.isEmpty() -> {
                    // Empty State
                    EmptyPencairanState(isDark = isDark)
                }
                filteredPelanggan.isEmpty() -> {
                    // Search Not Found State
                    SearchNotFoundState(searchQuery = searchQuery, isDark = isDark)
                }
                else -> {
                    // Daftar nasabah
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredPelanggan, key = { it.id }) { pelanggan ->
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(300)) + slideInVertically(
                                    initialOffsetY = { 30 },
                                    animationSpec = tween(300)
                                )
                            ) {
                                ModernPencairanCard(
                                    pelanggan = pelanggan,
                                    viewModel = viewModel,
                                    onCairkanClick = {
                                        selectedPelanggan = pelanggan
                                        showConfirmDialog = true
                                    },
                                    onCairkanSetengahClick = {
                                        selectedPelangganSetengah = pelanggan
                                        showSetengahDialog = true
                                    },
                                    onLanjutPinjamanClick = {
                                        navController.navigate("kelolaKredit/${pelanggan.id}")
                                    },
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
        }

        // Modern Dialog Konfirmasi Pencairan
        if (showConfirmDialog && selectedPelanggan != null) {
            ModernConfirmDialog(
                pelanggan = selectedPelanggan!!,
                viewModel = viewModel,
                isProcessing = isProcessing,
                onConfirm = {
                    isProcessing = true
                    viewModel.cairkanSimpanan(
                        pelangganId = selectedPelanggan!!.id,
                        onSuccess = {
                            isProcessing = false
                            showConfirmDialog = false
                            selectedPelanggan = null
                            Toast.makeText(context, "Simpanan berhasil dicairkan", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { e ->
                            isProcessing = false
                            Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                onDismiss = {
                    if (!isProcessing) {
                        showConfirmDialog = false
                        selectedPelanggan = null
                    }
                }
            )
        }

        // Dialog Konfirmasi Cairkan Setengah (butuh persetujuan koordinator)
        if (showSetengahDialog && selectedPelangganSetengah != null) {
            val pel = selectedPelangganSetengah!!
            val jumlahSetengah = pel.simpanan / 2
            AlertDialog(
                onDismissRequest = {
                    if (!isSubmittingSetengah) {
                        showSetengahDialog = false
                        selectedPelangganSetengah = null
                    }
                },
                icon = {
                    Icon(
                        Icons.Rounded.AccountBalanceWallet,
                        contentDescription = null,
                        tint = PencairanColors.warning,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "Cairkan Setengah Simpanan",
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = pel.namaPanggilan.ifBlank { pel.namaKtp },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Jumlah yang akan dicairkan:",
                            fontSize = 13.sp,
                            color = Color(0xFF64748B),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Rp ${formatRupiah(jumlahSetengah)} (50%)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = PencairanColors.warning,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = PencairanColors.warning.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "Pengajuan ini akan dikirim ke koordinator untuk disetujui terlebih dahulu.",
                                fontSize = 12.sp,
                                color = PencairanColors.warning,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isSubmittingSetengah = true
                            viewModel.ajukanPencairanSetengahSimpanan(
                                pelangganId = pel.id,
                                onSuccess = {
                                    isSubmittingSetengah = false
                                    showSetengahDialog = false
                                    selectedPelangganSetengah = null
                                    Toast.makeText(context, "Pengajuan cairkan setengah berhasil dikirim ke koordinator", Toast.LENGTH_LONG).show()
                                },
                                onFailure = { e ->
                                    isSubmittingSetengah = false
                                    Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        enabled = !isSubmittingSetengah,
                        colors = ButtonDefaults.buttonColors(containerColor = PencairanColors.warning),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isSubmittingSetengah) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Ya, Ajukan", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showSetengahDialog = false
                            selectedPelangganSetengah = null
                        },
                        enabled = !isSubmittingSetengah
                    ) {
                        Text("Batal")
                    }
                },
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun ModernPencairanTopBar(
    jumlahNasabah: Int,
    isDark: Boolean,
    txtColor: Color,
    subtitleColor: Color,
    onBackClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) PencairanColors.darkSurface else Color.White,
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
                    Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = txtColor
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Menunggu Pencairan",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = txtColor
                )
                Text(
                    text = "$jumlahNasabah nasabah menunggu",
                    fontSize = 13.sp,
                    color = subtitleColor
                )
            }

            // Badge jumlah
            Box(
                modifier = Modifier
                    .background(
                        Brush.linearGradient(PencairanColors.primaryGradient),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = jumlahNasabah.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun ModernSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = PencairanColors.purple.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = "Search",
                tint = subtitleColor,
                modifier = Modifier.padding(start = 12.dp)
            )

            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        "Cari nama, NIK, atau wilayah...",
                        color = subtitleColor
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = PencairanColors.purple,
                    focusedTextColor = txtColor,
                    unfocusedTextColor = txtColor
                )
            )

            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = subtitleColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernPencairanCard(
    pelanggan: Pelanggan,
    viewModel: PelangganViewModel,
    onCairkanClick: () -> Unit,
    onCairkanSetengahClick: () -> Unit,
    onLanjutPinjamanClick: () -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    val displayName = viewModel.getDisplayName(pelanggan)
    val displayNik = viewModel.getDisplayNik(pelanggan)

// ✅ TAMBAH: Hitung sisa hutang
    val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
        pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
    }
    val sisaHutang = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)
    val isLunasCicilan = sisaHutang <= 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = PencairanColors.purple.copy(alpha = 0.15f),
                spotColor = PencairanColors.purple.copy(alpha = 0.15f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header dengan gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                PencairanColors.purple.copy(alpha = 0.1f),
                                PencairanColors.purple.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .padding(16.dp)
            ) {
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
                                .size(48.dp)
                                .background(
                                    Brush.linearGradient(PencairanColors.primaryGradient),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }

                        Column {
                            Text(
                                text = displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = txtColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = pelanggan.wilayah,
                                fontSize = 13.sp,
                                color = subtitleColor
                            )
                        }
                    }

                    // Badge Status - Dinamis berdasarkan kondisi
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (sisaHutang > 0)
                            PencairanColors.warning.copy(alpha = 0.15f)
                        else
                            PencairanColors.success.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (sisaHutang > 0) Icons.Rounded.Warning else Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = if (sisaHutang > 0) PencairanColors.warning else PencairanColors.success,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (sisaHutang > 0) "Ada Sisa Hutang" else "Lunas",
                                color = if (sisaHutang > 0) PencairanColors.warning else PencairanColors.success,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Info Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Kolom Kiri
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InfoItem(
                            icon = Icons.Rounded.Badge,
                            label = "NIK",
                            value = displayNik,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )
                        InfoItem(
                            icon = Icons.Rounded.CalendarToday,
                            label = "Tgl Pengajuan",
                            value = pelanggan.tanggalPengajuan,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )
                        InfoItem(
                            icon = Icons.Rounded.Repeat,
                            label = "Pinjaman Ke",
                            value = "${pelanggan.pinjamanKe}",
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )
                    }

                    // Kolom Kanan
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InfoItem(
                            icon = Icons.Rounded.AttachMoney,
                            label = "Total Pinjaman",
                            value = "Rp ${formatRupiah(pelanggan.totalPelunasan)}",
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )
                        InfoItem(
                            icon = Icons.Rounded.EventAvailable,
                            label = if (isLunasCicilan) "Tgl Lunas" else "Status",
                            value = if (isLunasCicilan) {
                                pelanggan.tanggalLunasCicilan.ifBlank { "-" }
                            } else {
                                viewModel.getDisplayTextStatusKhusus(pelanggan.statusKhusus).ifBlank { "Menunggu" }
                            },
                            valueColor = if (!isLunasCicilan) PencairanColors.warning else null,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )
                        InfoItem(
                            icon = Icons.Rounded.Receipt,
                            label = "Sisa Hutang",
                            value = "Rp ${formatRupiah(sisaHutang.toInt())}",
                            valueColor = if (sisaHutang > 0) PencairanColors.warning else PencairanColors.success,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )
                    }
                }

                // Divider
                HorizontalDivider(
                    color = borderColor,
                    thickness = 1.dp
                )

                // ✅ TAMBAH: Card Info Sisa Hutang (jika ada)
                if (sisaHutang > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = PencairanColors.warning.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = PencairanColors.warning,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Nasabah masih memiliki sisa hutang",
                                    fontSize = 12.sp,
                                    color = subtitleColor
                                )
                                Text(
                                    text = "Simpanan akan dipotong untuk menutup hutang",
                                    fontSize = 11.sp,
                                    color = subtitleColor.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Simpanan Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = PencairanColors.success.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Savings,
                                contentDescription = null,
                                tint = PencairanColors.success,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Total Simpanan",
                                    fontSize = 12.sp,
                                    color = subtitleColor
                                )
                                Text(
                                    text = "Rp ${formatRupiah(pelanggan.simpanan)}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PencairanColors.success
                                )
                            }
                        }

                        // Info Tarik Tabungan jika ada
                        if (pelanggan.tarikTabungan > 0) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Termasuk Tarik Tab.",
                                    fontSize = 11.sp,
                                    color = subtitleColor
                                )
                                Text(
                                    text = "Rp ${formatRupiah(pelanggan.tarikTabungan)}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = PencairanColors.warning
                                )
                            }
                        }
                    }
                }

                // ✅ BARU: Tombol Lanjut Pinjaman (hanya tampil jika sudah lunas cicilan)
                if (isLunasCicilan) {
                    OutlinedButton(
                        onClick = onLanjutPinjamanClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(PencairanColors.infoGradient)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PencairanColors.infoGradient.first()
                        )
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.AddCard,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Lanjut Pinjaman",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Tombol Cairkan Setengah (butuh persetujuan koordinator)
                OutlinedButton(
                    onClick = onCairkanSetengahClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.linearGradient(
                            listOf(PencairanColors.warning, PencairanColors.warning)
                        )
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = PencairanColors.warning
                    )
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.CallSplit,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Cairkan Setengah",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tombol Cairkan Semua (langsung, tanpa persetujuan)
                Button(
                    onClick = onCairkanClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(PencairanColors.successGradient),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.AccountBalanceWallet,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Cairkan Semua",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    txtColor: Color,
    subtitleColor: Color,
    valueColor: Color? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = subtitleColor,
            modifier = Modifier.size(16.dp)
        )
        Column {
            Text(
                text = label,
                fontSize = 11.sp,
                color = subtitleColor
            )
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = valueColor ?: txtColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyPencairanState(isDark: Boolean) {
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    PencairanColors.success.copy(alpha = 0.1f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = PencairanColors.success,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Semua Simpanan Sudah Dicairkan",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = txtColor,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tidak ada nasabah yang menunggu pencairan simpanan saat ini",
            fontSize = 14.sp,
            color = subtitleColor,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SearchNotFoundState(searchQuery: String, isDark: Boolean) {
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    subtitleColor.copy(alpha = 0.1f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.SearchOff,
                contentDescription = null,
                tint = subtitleColor,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Nasabah Tidak Ditemukan",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = txtColor,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tidak ada hasil untuk \"$searchQuery\"",
            fontSize = 14.sp,
            color = subtitleColor,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ModernConfirmDialog(
    pelanggan: Pelanggan,
    viewModel: PelangganViewModel,
    isProcessing: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        Brush.linearGradient(PencairanColors.successGradient),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Savings,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        },
        title = {
            Text(
                text = "Konfirmasi Pencairan",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Anda akan mencairkan simpanan untuk:",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = PencairanColors.success.copy(alpha = 0.1f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = viewModel.getDisplayName(pelanggan),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )

                        HorizontalDivider(color = PencairanColors.success.copy(alpha = 0.2f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Total Simpanan",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Rp ${formatRupiah(pelanggan.simpanan)}",
                                fontWeight = FontWeight.Bold,
                                color = PencairanColors.success
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Tindakan ini tidak dapat dibatalkan",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PencairanColors.success
                )
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Memproses...")
                } else {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ya, Cairkan", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Batal")
            }
        }
    )
}