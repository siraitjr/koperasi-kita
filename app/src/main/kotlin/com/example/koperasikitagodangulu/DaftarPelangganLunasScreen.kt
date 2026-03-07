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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.koperasikitagodangulu.utils.formatRupiah
import android.content.Context
import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import androidx.core.content.ContextCompat
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// =========================================================================
// Modern Color Palette for Lunas Screen
// =========================================================================
private object LunasColors {
    // Gradients
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))

    // Solid colors
    val success = Color(0xFF10B981)
    val primary = Color(0xFF6366F1)
    val teal = Color(0xFF14B8A6)
    val warning = Color(0xFFF59E0B)

    // Background & Surface
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFE2E8F0)

    val darkBackground = Color(0xFF0F172A)
    val darkSurface = Color(0xFF1E293B)
    val darkCard = Color(0xFF334155)
    val darkBorder = Color(0xFF475569)

    // Text colors
    val textPrimary = Color(0xFF1E293B)
    val textSecondary = Color(0xFF64748B)
    val textMuted = Color(0xFF94A3B8)

    val textPrimaryDark = Color(0xFFF1F5F9)
    val textSecondaryDark = Color(0xFF94A3B8)
}

// =========================================================================
// DAFTAR PELANGGAN LUNAS SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaftarPelangganLunasScreen(
    navController: NavController,
    viewModel: PelangganViewModel
) {
    val isDark by viewModel.isDarkMode

    // Dynamic colors based on theme
    val cardColor = if (isDark) LunasColors.darkSurface else LunasColors.lightSurface
    val txtColor = if (isDark) LunasColors.textPrimaryDark else LunasColors.textPrimary
    val secondaryTextColor = if (isDark) LunasColors.textSecondaryDark else LunasColors.textSecondary
    val borderColor = if (isDark) LunasColors.darkBorder else LunasColors.lightBorder
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) LunasColors.darkBackground else LunasColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val daftarPelanggan = viewModel.daftarPelanggan
    val context = LocalContext.current

    // Filter hanya pelanggan yang lunas
    val pelangganLunas = remember(daftarPelanggan.size) {
        daftarPelanggan.filter { pelanggan ->
            val totalBayar = pelanggan.pembayaranList.sumOf { it.jumlah + it.subPembayaran.sumOf { sub -> sub.jumlah } }
            val sudahLunasCicilan = totalBayar >= pelanggan.totalPelunasan && pelanggan.totalPelunasan > 0

            // ✅ PERBAIKAN: Juga termasuk yang status = "Lunas" dan sudah dicairkan
            val isLunasManual = pelanggan.status.lowercase() == "lunas" && pelanggan.statusPencairanSimpanan == "Dicairkan"

            val isLunas = (sudahLunasCicilan && pelanggan.statusPencairanSimpanan == "Dicairkan") || isLunasManual

            isLunas && pelanggan.status != "Menunggu Approval"
        }
    }

    var queryNama by rememberSaveable { mutableStateOf("") }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    val daftarTampil = remember(queryNama, pelangganLunas) {
        pelangganLunas.filter { pel ->
            queryNama.isBlank() || pel.namaKtp.contains(queryNama, ignoreCase = true) ||
                    pel.namaPanggilan.contains(queryNama, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernLunasTopBar(
                jumlahNasabah = pelangganLunas.size,
                isDark = isDark,
                onBack = { navController.popBackStack() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Search Field
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400)
                )
            ) {
                ModernSearchField(
                    value = queryNama,
                    onValueChange = { queryNama = it },
                    placeholder = "Cari nama nasabah lunas...",
                    isDark = isDark,
                    cardColor = cardColor,
                    txtColor = txtColor,
                    secondaryTextColor = secondaryTextColor,
                    borderColor = borderColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Summary Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                ModernLunasSummaryCard(
                    totalNasabah = pelangganLunas.size,
                    filteredCount = daftarTampil.size,
                    isFiltered = queryNama.isNotBlank()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content
            if (daftarTampil.isEmpty()) {
                ModernEmptyLunasState(
                    isSearching = queryNama.isNotBlank()
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(daftarTampil, key = { it.id }) { pelanggan ->
                        ModernLunasItemCard(
                            pelanggan = pelanggan,
                            viewModel = viewModel,
                            navController = navController,
                            isDark = isDark,
                            cardColor = cardColor,
                            txtColor = txtColor,
                            secondaryTextColor = secondaryTextColor,
                            context = context
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// TOP BAR
// =========================================================================
@Composable
private fun ModernLunasTopBar(
    jumlahNasabah: Int,
    isDark: Boolean,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                ambientColor = LunasColors.success.copy(alpha = 0.3f)
            ),
        color = Color.Transparent,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(LunasColors.successGradient),
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

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Daftar Nasabah Lunas",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$jumlahNasabah nasabah telah lunas",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }

                // Success Icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = null,
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

// =========================================================================
// SEARCH FIELD
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    secondaryTextColor: Color,
    borderColor: Color
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                color = secondaryTextColor
            )
        },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        LunasColors.success.copy(alpha = 0.1f),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = LunasColors.success,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingIcon = if (value.isNotBlank()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = secondaryTextColor
                    )
                }
            }
        } else null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LunasColors.success,
            unfocusedBorderColor = borderColor,
            focusedContainerColor = cardColor,
            unfocusedContainerColor = cardColor,
            cursorColor = LunasColors.success,
            focusedTextColor = txtColor,
            unfocusedTextColor = txtColor
        )
    )
}

// =========================================================================
// SUMMARY CARD
// =========================================================================
@Composable
private fun ModernLunasSummaryCard(
    totalNasabah: Int,
    filteredCount: Int,
    isFiltered: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = LunasColors.success.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(LunasColors.successGradient))
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column {
                        Text(
                            text = if (isFiltered) "Ditemukan" else "Total Nasabah Lunas",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (isFiltered) "$filteredCount dari $totalNasabah" else "$totalNasabah",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Badge
                Surface(
                    color = Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "LUNAS",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// ITEM CARD
// =========================================================================
@Composable
private fun ModernLunasItemCard(
    pelanggan: Pelanggan,
    viewModel: PelangganViewModel,
    navController: NavController,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    secondaryTextColor: Color,
    context: Context
) {
    val displayName = viewModel.getDisplayName(pelanggan)
    val displayNik = viewModel.getDisplayNik(pelanggan)
    val displayNamaKtp = viewModel.getDisplayNamaKtp(pelanggan)

    var expandedMenu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(pelanggan.id) { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var pelangganToDelete by remember { mutableStateOf<Pelanggan?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = LunasColors.success.copy(alpha = 0.1f)
            )
            .clickable { navController.navigate("riwayat/${pelanggan.id}") },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header Row
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Avatar with badge
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(
                                    Brush.linearGradient(LunasColors.successGradient),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pelanggan.namaKtp.take(2).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        // Lunas badge
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color.White, CircleShape)
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(LunasColors.success, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        // Nomor Anggota
                        if (pelanggan.nomorAnggota.isNotBlank()) {
                            Surface(
                                color = LunasColors.teal.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "No. ${pelanggan.nomorAnggota}",
                                    color = LunasColors.teal,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // Nama
                        Text(
                            text = displayNamaKtp,
                            color = txtColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // NIK
                        Text(
                            text = "NIK: $displayNik",
                            color = secondaryTextColor,
                            fontSize = 12.sp
                        )
                    }

                    // Menu Button
                    Box {
                        IconButton(
                            onClick = { expandedMenu = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Menu",
                                tint = secondaryTextColor
                            )
                        }
                        DropdownMenu(
                            expanded = expandedMenu,
                            onDismissRequest = { expandedMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Kelola Pinjaman") },
                                onClick = {
                                    expandedMenu = false
                                    navController.navigate("kelolaKredit/${pelanggan.id}")
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Settings,
                                        null,
                                        tint = LunasColors.primary
                                    )
                                }
                            )

                            HorizontalDivider()

                            DropdownMenuItem(
                                text = { Text("Ajukan Penghapusan", color = LunasColors.warning) },
                                onClick = {
                                    expandedMenu = false
                                    pelangganToDelete = pelanggan
                                    showDeleteConfirmation = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Delete, null, tint = LunasColors.warning)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = if (isDark) LunasColors.darkBorder else LunasColors.lightBorder)
                Spacer(modifier = Modifier.height(12.dp))

                // Basic Info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ModernInfoColumn(
                        label = "Wilayah",
                        value = pelanggan.wilayah,
                        secondaryTextColor = secondaryTextColor,
                        txtColor = txtColor
                    )
                    ModernInfoColumn(
                        label = "Pinjaman Ke",
                        value = pelanggan.pinjamanKe.toString(),
                        secondaryTextColor = secondaryTextColor,
                        txtColor = txtColor,
                        alignment = Alignment.End
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ModernInfoColumn(
                        label = "Besar Pinjaman",
                        value = formatRupiah(pelanggan.besarPinjaman),
                        secondaryTextColor = secondaryTextColor,
                        txtColor = txtColor
                    )
                    ModernInfoColumn(
                        label = "Total Pelunasan",
                        value = formatRupiah(pelanggan.totalPelunasan),
                        secondaryTextColor = secondaryTextColor,
                        txtColor = LunasColors.success,
                        alignment = Alignment.End
                    )
                }

                // Expanded Details
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        HorizontalDivider(color = if (isDark) LunasColors.darkBorder else LunasColors.lightBorder)
                        Spacer(modifier = Modifier.height(12.dp))

                        ModernDetailRow("Alamat KTP", pelanggan.alamatKtp, secondaryTextColor, txtColor)
                        ModernDetailRow("Alamat Rumah", pelanggan.alamatRumah, secondaryTextColor, txtColor)
                        ModernDetailRow("Detail Rumah", pelanggan.detailRumah, secondaryTextColor, txtColor)
                        ModernDetailRow("No HP", pelanggan.noHp, secondaryTextColor, txtColor)
                        ModernDetailRow("Jenis Usaha", pelanggan.jenisUsaha, secondaryTextColor, txtColor)
                        ModernDetailRow("Admin (5%)", formatRupiah(pelanggan.admin), secondaryTextColor, txtColor)

                        val simpananLimaPersen = (pelanggan.besarPinjaman * 5) / 100
                        ModernDetailRow("Simpanan (5%)", formatRupiah(simpananLimaPersen), secondaryTextColor, txtColor)

                        val simpananTambahan = pelanggan.simpanan - simpananLimaPersen
                        if (simpananTambahan > 0 && pelanggan.pinjamanKe >= 2) {
                            ModernDetailRow("Simpanan Tambahan", formatRupiah(simpananTambahan), secondaryTextColor, txtColor)
                        }

                        if (pelanggan.pinjamanKe >= 2) {
                            val totalSimpananSemua = viewModel.getTotalSimpananByNama(pelanggan.namaKtp)
                            ModernDetailRow(
                                "Total Simpanan (Akumulasi)",
                                formatRupiah(totalSimpananSemua),
                                secondaryTextColor,
                                LunasColors.teal
                            )
                        }

                        ModernDetailRow("Jasa Pinjaman (10%)", formatRupiah(pelanggan.jasaPinjaman), secondaryTextColor, txtColor)
                        ModernDetailRow("Total Diterima", formatRupiah(pelanggan.totalDiterima), secondaryTextColor, txtColor)
                        ModernDetailRow("Tenor", "${pelanggan.tenor} Hari", secondaryTextColor, txtColor)
                        ModernDetailRow("Tanggal Pengajuan", pelanggan.tanggalPengajuan, secondaryTextColor, txtColor)
                        ModernDetailRow("Tanggal Pelunasan", viewModel.getTanggalPelunasan(pelanggan), secondaryTextColor, LunasColors.success)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Status Badge & Expand Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Lunas Badge
                    Surface(
                        color = LunasColors.success.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = LunasColors.success,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "LUNAS",
                                color = LunasColors.success,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    // Expand Button
                    TextButton(
                        onClick = { expanded = !expanded }
                    ) {
                        Text(
                            text = if (expanded) "Sembunyikan" else "Lihat Detail",
                            color = LunasColors.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = LunasColors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            // Delete Confirmation Dialog
            if (showDeleteConfirmation && pelangganToDelete != null) {
                var alasanPenghapusan by remember { mutableStateOf("") }
                var isSubmitting by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = {
                        if (!isSubmitting) {
                            showDeleteConfirmation = false
                            pelangganToDelete = null
                            alasanPenghapusan = ""
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        LunasColors.warning.copy(alpha = 0.1f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = LunasColors.warning
                                )
                            }
                            Text(
                                "Ajukan Penghapusan",
                                fontWeight = FontWeight.Bold,
                                color = LunasColors.warning
                            )
                        }
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Pengajuan penghapusan nasabah \"${pelangganToDelete!!.namaKtp}\" akan dikirim ke Pimpinan cabang untuk persetujuan.",
                                fontSize = 14.sp
                            )

                            OutlinedTextField(
                                value = alasanPenghapusan,
                                onValueChange = { alasanPenghapusan = it },
                                label = { Text("Alasan Penghapusan *") },
                                placeholder = { Text("Contoh: Data duplikat, Salah input, dll...") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                minLines = 2,
                                maxLines = 4,
                                enabled = !isSubmitting
                            )

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFFF3E0)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Info,
                                        contentDescription = null,
                                        tint = Color(0xFFE65100),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "Nasabah tidak akan langsung terhapus. Pimpinan cabang akan mereview dan memutuskan.",
                                        fontSize = 12.sp,
                                        color = Color(0xFFE65100)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (alasanPenghapusan.isBlank()) {
                                    Toast.makeText(
                                        context,
                                        "Harap isi alasan penghapusan",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@Button
                                }

                                isSubmitting = true
                                viewModel.createDeletionRequest(
                                    pelanggan = pelangganToDelete!!,
                                    alasanPenghapusan = alasanPenghapusan,
                                    onSuccess = {
                                        isSubmitting = false
                                        showDeleteConfirmation = false
                                        Toast.makeText(
                                            context,
                                            "Pengajuan penghapusan berhasil dikirim ke Pimpinan",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        pelangganToDelete = null
                                        alasanPenghapusan = ""
                                    },
                                    onFailure = { exception ->
                                        isSubmitting = false
                                        Toast.makeText(
                                            context,
                                            "Gagal mengajukan penghapusan: ${exception.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            },
                            enabled = alasanPenghapusan.isNotBlank() && !isSubmitting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LunasColors.warning
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                if (isSubmitting) "Mengirim..." else "Ajukan Penghapusan",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirmation = false
                                pelangganToDelete = null
                                alasanPenghapusan = ""
                            },
                            enabled = !isSubmitting
                        ) {
                            Text("Batal")
                        }
                    }
                )
            }
        }
    }
}

// =========================================================================
// HELPER COMPONENTS
// =========================================================================
@Composable
private fun ModernInfoColumn(
    label: String,
    value: String,
    secondaryTextColor: Color,
    txtColor: Color,
    alignment: Alignment.Horizontal = Alignment.Start
) {
    Column(horizontalAlignment = alignment) {
        Text(
            text = label,
            color = secondaryTextColor,
            fontSize = 11.sp
        )
        Text(
            text = value,
            color = txtColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ModernDetailRow(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

// =========================================================================
// EMPTY STATE
// =========================================================================
@Composable
private fun ModernEmptyLunasState(
    isSearching: Boolean
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
                        LunasColors.success.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSearching) Icons.Rounded.SearchOff else Icons.Rounded.PersonSearch,
                    contentDescription = null,
                    tint = LunasColors.success,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isSearching) "Nasabah tidak ditemukan" else "Tidak ada nasabah lunas",
                color = LunasColors.textSecondary,
                fontSize = 14.sp
            )
            if (!isSearching) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Belum ada nasabah yang telah melunasi pinjaman",
                    color = LunasColors.textMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// =========================================================================
// HELPER FUNCTION
// =========================================================================
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
    val clip = ClipData.newPlainText("Copied Text", text)
    clipboard?.setPrimaryClip(clip)
    Toast.makeText(context, "Teks disalin", Toast.LENGTH_SHORT).show()
}