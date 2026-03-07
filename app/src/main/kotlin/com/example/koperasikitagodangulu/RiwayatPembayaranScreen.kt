package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.koperasikitagodangulu.utils.formatRupiah
import kotlin.math.max
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// Modern Color Palette
private object HistoryColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
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
    val warning = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)
    val info = Color(0xFF3B82F6)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiwayatPembayaranScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    pelangganId: String
) {
    val context = LocalContext.current
    val pelanggan = viewModel.daftarPelanggan.find { it.id == pelangganId }

    if (pelanggan == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PersonOff,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Data nasabah tidak ditemukan",
                    color = Color(0xFF64748B),
                    fontSize = 16.sp
                )
            }
        }
        return
    }

    val pembayaranListState = remember { mutableStateListOf<Pembayaran>().apply { addAll(pelanggan.pembayaranList) } }

    LaunchedEffect(pelanggan.pembayaranList) {
        pembayaranListState.clear()
        pembayaranListState.addAll(pelanggan.pembayaranList)
    }

    var showDialogIndex by remember { mutableStateOf<Int?>(null) }
    var showSubDeleteDialog by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showHapusStatusDialog by remember { mutableStateOf(false) }
    var expandedReferensi by remember { mutableStateOf(false) }

    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) HistoryColors.darkCard else HistoryColors.lightSurface
    val borderColor = if (isDark) HistoryColors.darkBorder else HistoryColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) HistoryColors.darkBackground else HistoryColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    val totalBayar = pembayaranListState.sumOf {
        it.jumlah + it.subPembayaran.sumOf { sub -> sub.jumlah }
    }
    val sisa = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)
    val sudahLunas = sisa <= 0

    val tenorAwal = try { pelanggan.tenor.toInt() } catch (e: NumberFormatException) { 0 }
    val jumlahBayar = pembayaranListState.size
    val sisaTenor = max(tenorAwal - jumlahBayar, 0)

    val progressPercentage = if (pelanggan.totalPelunasan > 0) {
        (totalBayar.toFloat() / pelanggan.totalPelunasan).coerceIn(0f, 1f)
    } else 0f

    Scaffold(
        containerColor = bgColor,
        topBar = {
            HistoryTopBar(
                title = "Riwayat Pembayaran",
                isDark = isDark,
                txtColor = txtColor
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDark) HistoryColors.darkSurface else Color.White,
                shadowElevation = 8.dp
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    HistoryGradientButton(
                        text = if (pelanggan.statusKhusus.isNotBlank()) "Hapus Status Dulu"
                        else if (sudahLunas) "Sudah Lunas ✓"
                        else "Bayar Sekarang",
                        onClick = {
                            if (pelanggan.statusKhusus.isNotBlank()) {
                                showHapusStatusDialog = true
                            } else {
                                navController.navigate("inputPembayaran/$pelangganId")
                            }
                        },
                        enabled = pelanggan.statusKhusus != "MENINGGAL" && !sudahLunas,
                        gradient = if (pelanggan.statusKhusus.isNotBlank())
                            listOf(
                                viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                                viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.8f)
                            )
                        else if (sudahLunas) HistoryColors.tealGradient
                        else HistoryColors.successGradient,
                        icon = if (sudahLunas) Icons.Rounded.CheckCircle else Icons.Rounded.Payments
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Customer Summary Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -30 },
                    animationSpec = tween(400)
                )
            ) {
                CustomerSummaryCard(
                    pelanggan = pelanggan,
                    totalBayar = totalBayar,
                    sisa = sisa,
                    sudahLunas = sudahLunas,
                    progressPercentage = progressPercentage,
                    sisaTenor = sisaTenor,
                    isDark = isDark,
                    cardColor = cardColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            // Referensi Cicilan Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                ReferensiCicilanCard(
                    pelanggan = pelanggan,
                    expanded = expandedReferensi,
                    onExpandToggle = { expandedReferensi = !expandedReferensi },
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            // Payment History Section
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 200)
                )
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Riwayat Pembayaran",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = txtColor
                        )
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = HistoryColors.primary.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = "${pembayaranListState.size} Transaksi",
                                color = HistoryColors.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (pembayaranListState.isEmpty()) {
                        EmptyPaymentState(isDark = isDark)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val reversedList = pembayaranListState.reversed()
                            reversedList.forEachIndexed { reversedIndex, pembayaran ->
                                val originalIndex = pembayaranListState.size - 1 - reversedIndex
                                val nomor = pembayaranListState.size - reversedIndex

                                PaymentHistoryCard(
                                    pembayaran = pembayaran,
                                    nomor = nomor,
                                    originalIndex = originalIndex,
                                    reversedIndex = reversedIndex,
                                    showDialogIndex = showDialogIndex,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor,
                                    onAddSubPayment = {
                                        navController.navigate("tambahSubPembayaran/$pelangganId/$originalIndex")
                                    },
                                    onDelete = {
                                        showDialogIndex = reversedIndex
                                    },
                                    onDismissDialog = { showDialogIndex = null },
                                    onRequestPaymentDeletion = { alasan ->
                                        viewModel.createPaymentDeletionRequest(
                                            pelanggan = pelanggan,
                                            pembayaranIndex = originalIndex,
                                            pembayaran = pembayaran,
                                            cicilanKe = nomor,
                                            alasanPenghapusan = alasan,
                                            onSuccess = {
                                                showDialogIndex = null
                                                // Tampilkan toast sukses (tambahkan context jika belum ada)
                                            },
                                            onFailure = { exception ->
                                                showDialogIndex = null
                                                // Tampilkan toast error
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Bottom spacer for FAB
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    // Hapus Status Dialog
    if (showHapusStatusDialog) {
        AlertDialog(
            onDismissRequest = { showHapusStatusDialog = false },
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
                                viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.15f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus)
                        )
                    }
                    Text("Hapus Status", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Nasabah ini memiliki status khusus:")
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = viewModel.getDisplayTextStatusKhusus(pelanggan.statusKhusus),
                            color = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                    Text(
                        "Status akan dihapus sebelum melakukan pembayaran.",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.hapusStatusKhususPelanggan(
                            pelangganId = pelangganId,
                            onSuccess = {
                                showHapusStatusDialog = false
                                navController.navigate("inputPembayaran/$pelangganId")
                            },
                            onFailure = { showHapusStatusDialog = false }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Hapus & Bayar", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showHapusStatusDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun HistoryTopBar(
    title: String,
    isDark: Boolean,
    txtColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) HistoryColors.darkSurface else Color.White,
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor
            )
        }
    }
}

@Composable
private fun CustomerSummaryCard(
    pelanggan: Pelanggan,
    totalBayar: Int,
    sisa: Int,
    sudahLunas: Boolean,
    progressPercentage: Float,
    sisaTenor: Int,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progressPercentage,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "progress"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = HistoryColors.primary.copy(alpha = 0.15f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        if (sudahLunas) HistoryColors.successGradient else HistoryColors.primaryGradient
                    )
                )
        ) {
            // Decorative circles
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
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pelanggan.namaKtp,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = pelanggan.wilayah,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    }

                    if (sudahLunas) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
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

                Spacer(modifier = Modifier.height(20.dp))

                // Progress Bar
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Progress Pembayaran",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "${(progressPercentage * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress)
                                .fillMaxHeight()
                                .background(Color.White, RoundedCornerShape(4.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stats Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SummaryStatItem(
                        label = "Total Pelunasan",
                        value = "Rp ${formatRupiah(pelanggan.totalPelunasan)}"
                    )
                    SummaryStatItem(
                        label = "Sudah Dibayar",
                        value = "Rp ${formatRupiah(totalBayar)}"
                    )
                    SummaryStatItem(
                        label = "Sisa",
                        value = "Rp ${formatRupiah(sisa)}"
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReferensiCicilanCard(
    pelanggan: Pelanggan,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onExpandToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                Brush.linearGradient(HistoryColors.infoGradient),
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CalendarMonth,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Referensi Cicilan",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = txtColor
                        )
                        Text(
                            text = "${pelanggan.hasilSimulasiCicilan.size} hari cicilan",
                            fontSize = 12.sp,
                            color = subtitleColor
                        )
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = subtitleColor
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
            ) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Divider(color = borderColor)
                    Spacer(modifier = Modifier.height(12.dp))

                    pelanggan.hasilSimulasiCicilan.take(10).forEachIndexed { index, cicilan ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Hari ${index + 1}",
                                fontSize = 13.sp,
                                color = subtitleColor
                            )
                            Text(
                                text = cicilan.tanggal,
                                fontSize = 13.sp,
                                color = txtColor
                            )
                            Text(
                                text = "Rp ${formatRupiah(cicilan.jumlah)}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = HistoryColors.primary
                            )
                        }
                    }

                    if (pelanggan.hasilSimulasiCicilan.size > 10) {
                        Text(
                            text = "... dan ${pelanggan.hasilSimulasiCicilan.size - 10} hari lagi",
                            fontSize = 12.sp,
                            color = subtitleColor,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentHistoryCard(
    pembayaran: Pembayaran,
    nomor: Int,
    originalIndex: Int,
    reversedIndex: Int,
    showDialogIndex: Int?,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color,
    onAddSubPayment: () -> Unit,
    onDelete: () -> Unit,
    onDismissDialog: () -> Unit,
    onRequestPaymentDeletion: (String) -> Unit  // ✅ Ganti dari onConfirmDelete
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Number Badge
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                Brush.linearGradient(HistoryColors.warningGradient),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$nomor",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Column {
                        Text(
                            text = "Rp ${formatRupiah(pembayaran.jumlah)}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = HistoryColors.warning
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CalendarToday,
                                contentDescription = null,
                                tint = HistoryColors.info,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = pembayaran.tanggal,
                                fontSize = 13.sp,
                                color = subtitleColor
                            )
                        }
                    }
                }
            }

            // Sub Payments
            if (pembayaran.subPembayaran.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                pembayaran.subPembayaran.forEach { sub ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = HistoryColors.success,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Rp ${formatRupiah(sub.jumlah)}",
                                fontSize = 14.sp,
                                color = HistoryColors.success,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            text = sub.tanggal,
                            fontSize = 12.sp,
                            color = subtitleColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onAddSubPayment,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddCircle,
                        contentDescription = "Tambah",
                        tint = HistoryColors.success,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Hapus",
                        tint = HistoryColors.danger,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    // Delete Confirmation Dialog - Dengan Approval ke Pimpinan
    if (showDialogIndex == reversedIndex) {
        var alasanPenghapusan by remember { mutableStateOf("") }
        var isSubmitting by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isSubmitting) onDismissDialog() },
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(HistoryColors.warning.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = HistoryColors.warning
                        )
                    }
                    Text("Ajukan Penghapusan", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Pengajuan penghapusan pembayaran Rp ${formatRupiah(pembayaran.jumlah)} akan dikirim ke Pimpinan cabang.",
                        fontSize = 14.sp
                    )

                    OutlinedTextField(
                        value = alasanPenghapusan,
                        onValueChange = { alasanPenghapusan = it },
                        label = { Text("Alasan Penghapusan *") },
                        placeholder = { Text("Contoh: Salah input, double entry, dll...") },
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
                                "Pembayaran tidak langsung terhapus. Pimpinan cabang akan mereview pengajuan ini.",
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
                        onRequestPaymentDeletion(alasanPenghapusan)
                    },
                    enabled = alasanPenghapusan.isNotBlank() && !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = HistoryColors.warning),
                    shape = RoundedCornerShape(10.dp)
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
                    onClick = onDismissDialog,
                    enabled = !isSubmitting
                ) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun EmptyPaymentState(isDark: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    Brush.linearGradient(HistoryColors.primaryGradient.map { it.copy(alpha = 0.2f) }),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Receipt,
                contentDescription = null,
                tint = HistoryColors.primary,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Belum Ada Pembayaran",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color.White else Color(0xFF1E293B)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Riwayat pembayaran akan muncul di sini",
            fontSize = 14.sp,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HistoryGradientButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    gradient: List<Color>,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(
                elevation = if (enabled) 8.dp else 0.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = gradient.first().copy(alpha = 0.3f)
            ),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color(0xFF94A3B8)
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (enabled) Brush.linearGradient(gradient)
                    else Brush.linearGradient(listOf(Color(0xFF94A3B8), Color(0xFFCBD5E1)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}