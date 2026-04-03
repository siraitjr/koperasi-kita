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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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

    // Filter: nasabah lunas cicilan tapi masih punya tabungan (belum dicairkan)
    // derivedStateOf agar reaktif terhadap perubahan ISI list, bukan hanya SIZE
    val pelangganLunas by remember {
        derivedStateOf {
            daftarPelanggan.filter { pelanggan ->
                // FIX: Exclude entry "Bunga..." agar konsisten dengan Cloud Functions & bukuPokokApi
                val totalBayar = pelanggan.pembayaranList
                    .filter { !it.tanggal.startsWith("Bunga") }
                    .sumOf { it.jumlah + it.subPembayaran.sumOf { sub -> sub.jumlah } }
                val sudahLunasCicilan = totalBayar >= pelanggan.totalPelunasan && pelanggan.totalPelunasan > 0

                // ✅ Nasabah lunas cicilan, masih punya tabungan, belum ditandai manual Sisa Tabungan
                sudahLunasCicilan &&
                        pelanggan.statusPencairanSimpanan != "Dicairkan" &&
                        pelanggan.statusKhusus != "MENUNGGU_PENCAIRAN" &&
                        pelanggan.status != "Menunggu Approval"
            }
        }
    }

    var queryNama by rememberSaveable { mutableStateOf("") }
    var isVisible by remember { mutableStateOf(false) }

    // State untuk dialog Cairkan Semua
    var showConfirmDialog by remember { mutableStateOf(false) }
    var selectedPelanggan by remember { mutableStateOf<Pelanggan?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    // State untuk dialog Cairkan Setengah
    var showSetengahDialog by remember { mutableStateOf(false) }
    var selectedPelangganSetengah by remember { mutableStateOf<Pelanggan?>(null) }
    var isSubmittingSetengah by remember { mutableStateOf(false) }
    var nominalCairanInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    val daftarTampil by remember(queryNama) {
        derivedStateOf {
            pelangganLunas.filter { pel ->
                queryNama.isBlank() || pel.namaKtp.contains(queryNama, ignoreCase = true) ||
                        pel.namaPanggilan.contains(queryNama, ignoreCase = true)
            }
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
                            borderColor = borderColor,
                            txtColor = txtColor,
                            secondaryTextColor = secondaryTextColor,
                            context = context,
                            onLanjutPinjamanClick = {
                                navController.navigate("kelolaKredit/${pelanggan.id}")
                            },
                            onCairkanSetengahClick = {
                                selectedPelangganSetengah = pelanggan
                                nominalCairanInput = ""
                                showSetengahDialog = true
                            },
                            onCairkanClick = {
                                selectedPelanggan = pelanggan
                                showConfirmDialog = true
                            }
                        )
                    }
                }
            }
        }

        // Dialog Konfirmasi Cairkan Semua
        if (showConfirmDialog && selectedPelanggan != null) {
            val pel = selectedPelanggan!!
            AlertDialog(
                onDismissRequest = { if (!isProcessing) { showConfirmDialog = false; selectedPelanggan = null } },
                shape = RoundedCornerShape(24.dp),
                icon = {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(Brush.linearGradient(LunasColors.successGradient), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Savings, null, tint = Color.White, modifier = Modifier.size(40.dp))
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
                        Text("Anda akan mencairkan simpanan untuk:", textAlign = TextAlign.Center)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = LunasColors.success.copy(alpha = 0.1f))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(viewModel.getDisplayName(pel), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                HorizontalDivider(color = LunasColors.success.copy(alpha = 0.2f))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Total Simpanan")
                                    Text("Rp ${formatRupiah(pel.simpanan)}", fontWeight = FontWeight.Bold, color = LunasColors.success)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Text("Tindakan ini tidak dapat dibatalkan", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isProcessing = true
                            viewModel.cairkanSimpanan(
                                pelangganId = pel.id,
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
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = LunasColors.success),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isProcessing) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Ya, Cairkan", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false; selectedPelanggan = null }, enabled = !isProcessing) {
                        Text("Batal")
                    }
                }
            )
        }

        // Dialog Cairkan Setengah (Sebagian)
        if (showSetengahDialog && selectedPelangganSetengah != null) {
            val pel = selectedPelangganSetengah!!
            val nominalInt = nominalCairanInput.toIntOrNull() ?: 0
            val isNominalValid = nominalInt in 1..pel.simpanan
            AlertDialog(
                onDismissRequest = {
                    if (!isSubmittingSetengah) {
                        showSetengahDialog = false
                        selectedPelangganSetengah = null
                        nominalCairanInput = ""
                    }
                },
                icon = {
                    Icon(Icons.Rounded.AccountBalanceWallet, null, tint = LunasColors.warning, modifier = Modifier.size(36.dp))
                },
                title = {
                    Text("Cairkan Sebagian Simpanan", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pel.namaPanggilan.ifBlank { pel.namaKtp }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
                        Text("Total simpanan: Rp ${formatRupiah(pel.simpanan)}", fontSize = 12.sp, color = Color(0xFF64748B), textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = nominalCairanInput,
                            onValueChange = { nominalCairanInput = it.filter { c -> c.isDigit() } },
                            label = { Text("Jumlah yang dicairkan (Rp)") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = nominalCairanInput.isNotEmpty() && !isNominalValid,
                            supportingText = {
                                when {
                                    nominalCairanInput.isEmpty() -> Text("Kosongkan untuk default 50% (Rp ${formatRupiah(pel.simpanan / 2)})", fontSize = 11.sp, color = Color(0xFF64748B))
                                    nominalInt > pel.simpanan -> Text("Melebihi total simpanan", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                    nominalInt <= 0 -> Text("Nominal harus lebih dari 0", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                    else -> Text("${(nominalInt * 100.0 / pel.simpanan).toInt()}% dari total simpanan", fontSize = 11.sp, color = LunasColors.warning)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LunasColors.warning, focusedLabelColor = LunasColors.warning),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Surface(color = LunasColors.warning.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp)) {
                            Text(
                                "Pengajuan ini akan dikirim ke koordinator untuk disetujui terlebih dahulu.",
                                fontSize = 12.sp, color = LunasColors.warning, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val jumlahFinal = if (nominalCairanInput.isEmpty()) pel.simpanan / 2 else nominalInt
                            isSubmittingSetengah = true
                            viewModel.ajukanPencairanSetengahSimpanan(
                                pelangganId = pel.id,
                                jumlahDicairkan = jumlahFinal,
                                onSuccess = {
                                    isSubmittingSetengah = false
                                    showSetengahDialog = false
                                    selectedPelangganSetengah = null
                                    nominalCairanInput = ""
                                    Toast.makeText(context, "Pengajuan berhasil dikirim ke koordinator", Toast.LENGTH_LONG).show()
                                },
                                onFailure = { e ->
                                    isSubmittingSetengah = false
                                    Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        enabled = !isSubmittingSetengah && (nominalCairanInput.isEmpty() || isNominalValid),
                        colors = ButtonDefaults.buttonColors(containerColor = LunasColors.warning),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isSubmittingSetengah) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Ya, Ajukan", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showSetengahDialog = false; selectedPelangganSetengah = null; nominalCairanInput = "" },
                        enabled = !isSubmittingSetengah
                    ) { Text("Batal") }
                },
                shape = RoundedCornerShape(16.dp)
            )
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
    borderColor: Color,
    txtColor: Color,
    secondaryTextColor: Color,
    context: Context,
    onLanjutPinjamanClick: () -> Unit,
    onCairkanSetengahClick: () -> Unit,
    onCairkanClick: () -> Unit
) {
    val displayName = viewModel.getDisplayName(pelanggan)
    val displayNik = viewModel.getDisplayNik(pelanggan)
    val totalSimpanan = pelanggan.simpanan
    val tanggalLunas = viewModel.getTanggalPelunasan(pelanggan)

    var expandedMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var pelangganToDelete by remember { mutableStateOf<Pelanggan?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = LunasColors.success.copy(alpha = 0.15f),
                spotColor = LunasColors.success.copy(alpha = 0.15f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // ── Header dengan gradient hijau ──────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                LunasColors.success.copy(alpha = 0.1f),
                                LunasColors.success.copy(alpha = 0.05f)
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Brush.linearGradient(LunasColors.successGradient), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = txtColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(text = pelanggan.wilayah, fontSize = 13.sp, color = secondaryTextColor)
                        }
                    }

                    // Badge Lunas + Menu
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(shape = RoundedCornerShape(20.dp), color = LunasColors.success.copy(alpha = 0.15f)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = LunasColors.success, modifier = Modifier.size(14.dp))
                                Text("Lunas", color = LunasColors.success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Box {
                            IconButton(onClick = { expandedMenu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.MoreVert, "Menu", tint = secondaryTextColor, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = expandedMenu, onDismissRequest = { expandedMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Riwayat Pembayaran") },
                                    onClick = { expandedMenu = false; navController.navigate("riwayat/${pelanggan.id}") },
                                    leadingIcon = { Icon(Icons.Rounded.History, null, tint = LunasColors.primary) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Ajukan Penghapusan", color = LunasColors.warning) },
                                    onClick = { expandedMenu = false; pelangganToDelete = pelanggan; showDeleteConfirmation = true },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = LunasColors.warning) }
                                )
                            }
                        }
                    }
                }
            }

            // ── Konten ────────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Info Grid (sama struktur dengan card Sisa Tabungan)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Kolom Kiri
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LunasInfoItem(icon = Icons.Rounded.Badge, label = "NIK", value = displayNik, txtColor = txtColor, subtitleColor = secondaryTextColor)
                        LunasInfoItem(icon = Icons.Rounded.CalendarToday, label = "Tgl Pengajuan", value = pelanggan.tanggalPengajuan, txtColor = txtColor, subtitleColor = secondaryTextColor)
                        LunasInfoItem(icon = Icons.Rounded.Repeat, label = "Pinjaman Ke", value = "${pelanggan.pinjamanKe}", txtColor = txtColor, subtitleColor = secondaryTextColor)
                    }
                    // Kolom Kanan
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LunasInfoItem(icon = Icons.Rounded.AttachMoney, label = "Total Pinjaman", value = "Rp ${formatRupiah(pelanggan.totalPelunasan)}", txtColor = txtColor, subtitleColor = secondaryTextColor)
                        LunasInfoItem(icon = Icons.Rounded.EventAvailable, label = "Tgl Lunas", value = tanggalLunas.ifBlank { "-" }, valueColor = LunasColors.success, txtColor = txtColor, subtitleColor = secondaryTextColor)
                        LunasInfoItem(icon = Icons.Rounded.LocationOn, label = "Wilayah", value = pelanggan.wilayah, txtColor = txtColor, subtitleColor = secondaryTextColor)
                    }
                }

                HorizontalDivider(color = borderColor, thickness = 1.dp)

                // Card Total Simpanan
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = LunasColors.success.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Savings, null, tint = LunasColors.success, modifier = Modifier.size(24.dp))
                            Column {
                                Text("Total Simpanan", fontSize = 12.sp, color = secondaryTextColor)
                                Text(
                                    "Rp ${formatRupiah(totalSimpanan)}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LunasColors.success
                                )
                            }
                        }
                    }
                }

                // Tombol Lanjut Pinjaman (full width)
                OutlinedButton(
                    onClick = onLanjutPinjamanClick,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.linearGradient(LunasColors.primaryGradient)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LunasColors.primary)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AddCard, null, modifier = Modifier.size(18.dp))
                        Text("Lanjut Pinjaman", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                // Tombol Setengah | Cairkan Semua berdampingan
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onCairkanSetengahClick,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(listOf(LunasColors.warning, LunasColors.warning))
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LunasColors.warning)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CallSplit, null, modifier = Modifier.size(18.dp))
                            Text("Setengah", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    Button(
                        onClick = onCairkanClick,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Brush.linearGradient(LunasColors.successGradient), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AccountBalanceWallet, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Text("Cairkan Semua", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
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

// =========================================================================
// HELPER COMPONENTS
// =========================================================================
@Composable
private fun LunasInfoItem(
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
        Icon(icon, contentDescription = null, tint = subtitleColor, modifier = Modifier.size(16.dp))
        Column {
            Text(text = label, fontSize = 11.sp, color = subtitleColor)
            Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = valueColor ?: txtColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

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