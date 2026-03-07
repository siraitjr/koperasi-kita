package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.utils.HolidayUtils
import android.util.Log
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// Modern Color Palette
private object VisitColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))
    val orangeGradient = listOf(Color(0xFFF97316), Color(0xFFFB923C))

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PelangganYangHarusDikunjungiScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    var showCatatanDialog by remember { mutableStateOf(false) }
    var catatanPelanggan by remember { mutableStateOf<Pelanggan?>(null) }
    var showHapusStatusDialog by remember { mutableStateOf(false) }
    var pelangganUntukHapusStatus by remember { mutableStateOf<Pelanggan?>(null) }

    // ========== STATE UNTUK SWIPE-TO-DISMISS ==========
    // Menyimpan ID nasabah yang di-dismiss (sementara untuk hari ini saja)
    var dismissedIds by rememberSaveable { mutableStateOf(setOf<String>()) }

    // State untuk dialog konfirmasi dismiss
    var showDismissDialog by remember { mutableStateOf(false) }
    var pelangganUntukDismiss by remember { mutableStateOf<Pelanggan?>(null) }

    val daftarPelanggan = viewModel.daftarPelanggan
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id"))
    val calendarNow = Calendar.getInstance()

    val hariIni = calendarNow.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale("id")) ?: ""
    val tanggalIni = dateFormat.format(calendarNow.time)
    val isHariKerja = HolidayUtils.isHariKerja(calendarNow)

    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) VisitColors.darkCard else VisitColors.lightSurface
    val borderColor = if (isDark) VisitColors.darkBorder else VisitColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) VisitColors.darkBackground else VisitColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Filter logic - pelanggan yang harus dikunjungi
    val pelangganKunjungi = daftarPelanggan.filter { p ->
        if (p.status != "Aktif" && p.status != "Active" && !p.status.equals("aktif", ignoreCase = true)) {
            return@filter false
        }

        // ✅ TAMBAH: Exclude nasabah dengan status Menunggu Pencairan
        if (p.statusKhusus == "MENUNGGU_PENCAIRAN") {
            return@filter false
        }
        if (!HolidayUtils.isHariKerja(calendarNow)) {
            return@filter false
        }

        val totalBayar = p.pembayaranList.sumOf { pay ->
            pay.jumlah + pay.subPembayaran.sumOf { sub -> sub.jumlah }
        }
        val sisa = (p.totalPelunasan - totalBayar).coerceAtLeast(0)
        if (sisa <= 0) return@filter false
        val sudahBayarHariIni = p.pembayaranList.any { it.tanggal == tanggalIni }
        if (sudahBayarHariIni) return@filter false

        try {
            val tanggalDaftar = dateFormat.parse(p.tanggalDaftar)
            val calendarDaftar = Calendar.getInstance().apply {
                time = tanggalDaftar
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val calendarHariIni = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (calendarDaftar.timeInMillis == calendarHariIni.timeInMillis) {
                return@filter false
            }
            if (p.hasilSimulasiCicilan.isNotEmpty()) {
                val cicilanPertama = p.hasilSimulasiCicilan.first()
                val tanggalCicilanPertama = dateFormat.parse(cicilanPertama.tanggal)
                val calendarCicilanPertama = Calendar.getInstance().apply {
                    time = tanggalCicilanPertama
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (calendarHariIni.before(calendarCicilanPertama)) {
                    return@filter false
                }
            }
        } catch (e: Exception) {
            Log.e("Filter", "Error parsing tanggal: ${e.message}")
        }
        true
    }

    // ========== FILTER WILAYAH ==========
    val wilayahOptions by remember(pelangganKunjungi) {
        mutableStateOf(viewModel.getWilayahOptions(pelangganKunjungi))
    }

    var selectedWilayah by rememberSaveable { mutableStateOf("Semua") }

    val pelangganFilterWilayah = remember(pelangganKunjungi, selectedWilayah, dismissedIds) {
        val filtered = if (selectedWilayah == "Semua") pelangganKunjungi
        else pelangganKunjungi.filter {
            viewModel.normalizeWilayah(it.wilayah) == viewModel.normalizeWilayah(selectedWilayah)
        }
        // Filter out dismissed items
        filtered.filter { it.id !in dismissedIds }
    }

    // ✅ Target Harian = SAMA dengan RingkasanDashboardScreen (besarPinjaman × 3%)
    val threeMonthsAgo = Calendar.getInstance().apply {
        add(Calendar.MONTH, -3)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    val targetHarian = daftarPelanggan.filter { pelanggan ->
        if (pelanggan.status != "Aktif" && !pelanggan.status.equals("aktif", ignoreCase = true) && pelanggan.status != "Active") return@filter false

        val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
            pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
        }
        val isBelumLunas = totalBayar < pelanggan.totalPelunasan.toLong()
        val isMenungguPencairan = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN"

        val tglAcuan = pelanggan.tanggalPencairan.ifBlank {
            pelanggan.tanggalPengajuan.ifBlank { pelanggan.tanggalDaftar }
        }
        val isOverThreeMonths = try {
            val acuanDate = dateFormat.parse(tglAcuan)
            acuanDate != null && acuanDate.before(threeMonthsAgo)
        } catch (_: Exception) { false }

        val isCairHariIni = pelanggan.tanggalPencairan.isNotBlank() && pelanggan.tanggalPencairan == tanggalIni

        isBelumLunas && !isMenungguPencairan && !isOverThreeMonths && !isCairHariIni
    }.sumOf { it.besarPinjaman * 3L / 100L }.toInt()

    val totalCicilanFiltered = targetHarian

    Scaffold(
        containerColor = bgColor,
        topBar = {
            VisitTopBar(
                hariIni = hariIni,
                tanggalIni = tanggalIni,
                isHariKerja = isHariKerja,
                jumlahNasabah = pelangganFilterWilayah.size,
                totalCicilan = totalCicilanFiltered,
                isDark = isDark,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )
        }
    ) { paddingValues ->
        if (!isHariKerja) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                HolidayEmptyState(isDark = isDark)
            }
        } else if (pelangganKunjungi.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                EmptyVisitState(isDark = isDark)
            }
        } else {
            // ========== STRUKTUR BARU: Column dengan Filter STICKY di atas ==========
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // ========== FILTER WILAYAH - STICKY (tidak ikut scroll) ==========
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp, bottom = 8.dp)
                ) {
                    WilayahFilterDropdown(
                        selectedWilayah = selectedWilayah,
                        wilayahOptions = wilayahOptions,
                        onWilayahSelected = { selectedWilayah = it },
                        isDark = isDark,
                        cardColor = cardColor,
                        borderColor = borderColor,
                        txtColor = txtColor,
                        subtitleColor = subtitleColor
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Info jumlah nasabah + tombol restore jika ada yang di-dismiss
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedWilayah == "Semua")
                                "Semua Wilayah"
                            else
                                "Wilayah: $selectedWilayah",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = txtColor
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tombol restore jika ada yang di-dismiss
                            if (dismissedIds.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = VisitColors.warning.copy(alpha = 0.1f),
                                    modifier = Modifier.clickable { dismissedIds = emptySet() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Restore,
                                            contentDescription = "Kembalikan",
                                            tint = VisitColors.warning,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "Kembalikan (${dismissedIds.size})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = VisitColors.warning
                                        )
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = VisitColors.primary.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = "${pelangganFilterWilayah.size} nasabah",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = VisitColors.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // ========== DAFTAR NASABAH - SCROLLABLE ==========
                if (pelangganFilterWilayah.isEmpty() && selectedWilayah != "Semua") {
                    // Empty state untuk filter
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SearchOff,
                                contentDescription = null,
                                tint = subtitleColor,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Tidak ada nasabah di wilayah $selectedWilayah",
                                color = subtitleColor,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { selectedWilayah = "Semua" }
                            ) {
                                Text("Tampilkan Semua")
                            }
                        }
                    }
                } else if (pelangganFilterWilayah.isEmpty() && dismissedIds.isNotEmpty()) {
                    // Empty state karena semua di-dismiss
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .background(
                                        Brush.linearGradient(VisitColors.warningGradient.map { it.copy(alpha = 0.2f) }),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VisibilityOff,
                                    contentDescription = null,
                                    tint = VisitColors.warning,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Semua Nasabah Disembunyikan",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = txtColor
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "${dismissedIds.size} nasabah telah disembunyikan.\nTekan tombol di bawah untuk mengembalikan.",
                                fontSize = 14.sp,
                                color = subtitleColor,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { dismissedIds = emptySet() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VisitColors.warning
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Restore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Kembalikan Semua", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        itemsIndexed(
                            items = pelangganFilterWilayah,
                            key = { _, item -> item.id }
                        ) { index, pelanggan ->
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(300, delayMillis = index * 50)) +
                                        slideInVertically(
                                            initialOffsetY = { 30 },
                                            animationSpec = tween(300, delayMillis = index * 50)
                                        )
                            ) {
                                // ========== SWIPEABLE VISIT CARD ==========
                                SwipeableVisitCard(
                                    pelanggan = pelanggan,
                                    index = index + 1,
                                    tanggalIni = tanggalIni,
                                    viewModel = viewModel,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor,
                                    onPayClick = {
                                        if (pelanggan.statusKhusus.isNotBlank()) {
                                            pelangganUntukHapusStatus = pelanggan
                                            showHapusStatusDialog = true
                                        } else {
                                            navController.navigate("inputPembayaran/${pelanggan.id}")
                                        }
                                    },
                                    onDetailClick = {
                                        navController.navigate("riwayat/${pelanggan.id}")
                                    },
                                    onCatatanClick = {
                                        catatanPelanggan = pelanggan
                                        showCatatanDialog = true
                                    },
                                    onDismiss = {
                                        pelangganUntukDismiss = pelanggan
                                        showDismissDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Catatan Dialog
    if (showCatatanDialog && catatanPelanggan != null) {
        ModernCatatanDialog(
            pelanggan = catatanPelanggan!!,
            viewModel = viewModel,
            onDismiss = {
                showCatatanDialog = false
                catatanPelanggan = null
            }
        )
    }

    // Hapus Status Dialog
    if (showHapusStatusDialog && pelangganUntukHapusStatus != null) {
        ModernHapusStatusDialog(
            pelanggan = pelangganUntukHapusStatus!!,
            viewModel = viewModel,
            onDismiss = {
                showHapusStatusDialog = false
                pelangganUntukHapusStatus = null
            },
            onConfirm = {
                viewModel.hapusStatusKhususPelanggan(
                    pelangganId = pelangganUntukHapusStatus!!.id,
                    onSuccess = {
                        navController.navigate("inputPembayaran/${pelangganUntukHapusStatus!!.id}")
                        showHapusStatusDialog = false
                        pelangganUntukHapusStatus = null
                    },
                    onFailure = {
                        showHapusStatusDialog = false
                        pelangganUntukHapusStatus = null
                    }
                )
            }
        )
    }

    // ========== DIALOG KONFIRMASI DISMISS ==========
    if (showDismissDialog && pelangganUntukDismiss != null) {
        DismissConfirmationDialog(
            pelanggan = pelangganUntukDismiss!!,
            isDark = isDark,
            onDismiss = {
                showDismissDialog = false
                pelangganUntukDismiss = null
            },
            onConfirm = {
                dismissedIds = dismissedIds + pelangganUntukDismiss!!.id
                showDismissDialog = false
                pelangganUntukDismiss = null
            }
        )
    }
}

// ========== KOMPONEN SWIPEABLE VISIT CARD ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableVisitCard(
    pelanggan: Pelanggan,
    index: Int,
    tanggalIni: String,
    viewModel: PelangganViewModel,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color,
    onPayClick: () -> Unit,
    onDetailClick: () -> Unit,
    onCatatanClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.StartToEnd,
                SwipeToDismissBoxValue.EndToStart -> {
                    onDismiss()
                    false // Return false to prevent auto-dismiss, we handle it via dialog
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { it * 0.4f } // 40% threshold untuk trigger
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            SwipeBackground(
                dismissDirection = dismissState.dismissDirection,
                isDark = isDark
            )
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true
    ) {
        VisitCard(
            pelanggan = pelanggan,
            index = index,
            tanggalIni = tanggalIni,
            viewModel = viewModel,
            isDark = isDark,
            cardColor = cardColor,
            borderColor = borderColor,
            txtColor = txtColor,
            subtitleColor = subtitleColor,
            onPayClick = onPayClick,
            onDetailClick = onDetailClick,
            onCatatanClick = onCatatanClick
        )
    }
}

// ========== BACKGROUND SAAT SWIPE ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(
    dismissDirection: SwipeToDismissBoxValue,
    isDark: Boolean
) {
    val color = when (dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> VisitColors.warning
        SwipeToDismissBoxValue.EndToStart -> VisitColors.warning
        SwipeToDismissBoxValue.Settled -> Color.Transparent
    }

    val alignment = when (dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> Alignment.Center
    }

    val icon = Icons.Rounded.VisibilityOff

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 24.dp),
        contentAlignment = alignment
    ) {
        if (dismissDirection != SwipeToDismissBoxValue.Settled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Text(
                        text = "Sembunyikan",
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "Sembunyikan",
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Text(
                        text = "Sembunyikan",
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// ========== DIALOG KONFIRMASI DISMISS ==========
@Composable
private fun DismissConfirmationDialog(
    pelanggan: Pelanggan,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = if (isDark) VisitColors.darkCard else Color.White,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            VisitColors.warning.copy(alpha = 0.1f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                        tint = VisitColors.warning
                    )
                }
                Text(
                    text = "Sembunyikan Nasabah?",
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.White else Color(0xFF1E293B)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Nasabah \"${pelanggan.namaPanggilan.ifBlank { pelanggan.namaKtp }}\" akan disembunyikan dari daftar kunjungan hari ini.",
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = VisitColors.warning.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = VisitColors.warning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Nasabah akan muncul kembali besok jika belum membayar. Anda juga bisa mengembalikan dengan menekan tombol \"Kembalikan\".",
                            fontSize = 12.sp,
                            color = VisitColors.warning
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VisitColors.warning
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sembunyikan", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "Batal",
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                )
            }
        }
    )
}

// ========== KOMPONEN FILTER WILAYAH ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WilayahFilterDropdown(
    selectedWilayah: String,
    wilayahOptions: List<String>,
    onWilayahSelected: (String) -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = VisitColors.primary.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            Brush.linearGradient(VisitColors.primaryGradient),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FilterList,
                        contentDescription = "Filter",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Filter Wilayah",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = txtColor
                )
            }

            // Dropdown
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedWilayah,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VisitColors.primary,
                        unfocusedBorderColor = borderColor,
                        focusedContainerColor = cardColor,
                        unfocusedContainerColor = cardColor
                    )
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    wilayahOptions.forEach { wilayah ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = wilayah,
                                    fontWeight = if (wilayah == selectedWilayah) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onWilayahSelected(wilayah)
                                expanded = false
                            },
                            leadingIcon = {
                                if (wilayah == selectedWilayah) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = VisitColors.primary
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisitTopBar(
    hariIni: String,
    tanggalIni: String,
    isHariKerja: Boolean,
    jumlahNasabah: Int,
    totalCicilan: Int,
    isDark: Boolean,
    txtColor: Color,
    subtitleColor: Color
) {
    Surface(
        color = if (isDark) VisitColors.darkSurface else VisitColors.lightSurface,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Kunjungan Hari Ini",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = txtColor
                    )
                    Text(
                        text = "$hariIni, $tanggalIni",
                        fontSize = 14.sp,
                        color = subtitleColor
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isHariKerja) VisitColors.success.copy(alpha = 0.1f)
                    else VisitColors.danger.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = if (isHariKerja) "Hari Kerja" else "Libur",
                        color = if (isHariKerja) VisitColors.success else VisitColors.danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            if (isHariKerja && jumlahNasabah > 0) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = VisitColors.primary.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Groups,
                                contentDescription = null,
                                tint = VisitColors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "$jumlahNasabah",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VisitColors.primary
                                )
                                Text(
                                    text = "Nasabah",
                                    fontSize = 11.sp,
                                    color = subtitleColor
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = VisitColors.success.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Payments,
                                contentDescription = null,
                                tint = VisitColors.success,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Rp ${formatRupiah(totalCicilan)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VisitColors.success,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Target",
                                    fontSize = 11.sp,
                                    color = subtitleColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisitCard(
    pelanggan: Pelanggan,
    index: Int,
    tanggalIni: String,
    viewModel: PelangganViewModel,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color,
    onPayClick: () -> Unit,
    onDetailClick: () -> Unit,
    onCatatanClick: () -> Unit
) {
    val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
        pay.jumlah + pay.subPembayaran.sumOf { sub -> sub.jumlah }
    }
    val sisa = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)
    val cicilanHariIni = pelanggan.hasilSimulasiCicilan.find { it.tanggal == tanggalIni }?.jumlah
        ?: (pelanggan.besarPinjaman * 5 / 100)
    val hasStatusKhusus = pelanggan.statusKhusus.isNotBlank()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = if (hasStatusKhusus)
                    viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.2f)
                else VisitColors.primary.copy(alpha = 0.1f)
            )
            .clickable { onDetailClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column {
            if (hasStatusKhusus) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCatatanClick() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Flag,
                                contentDescription = null,
                                tint = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = viewModel.getDisplayTextStatusKhusus(pelanggan.statusKhusus),
                                color = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = "Lihat catatan",
                            tint = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    Brush.linearGradient(VisitColors.primaryGradient),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$index",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Column {
                            Text(
                                text = pelanggan.namaPanggilan.ifBlank { pelanggan.namaKtp },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = txtColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LocationOn,
                                    contentDescription = null,
                                    tint = subtitleColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = pelanggan.wilayah.ifBlank { "Tidak ada wilayah" },
                                    fontSize = 13.sp,
                                    color = subtitleColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = VisitColors.success.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = "Rp ${formatRupiah(cicilanHariIni)}",
                            color = VisitColors.success,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Alamat",
                            fontSize = 11.sp,
                            color = subtitleColor
                        )
                        Text(
                            text = pelanggan.alamatRumah.ifBlank { "-" },
                            fontSize = 13.sp,
                            color = txtColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Sisa Utang",
                            fontSize = 11.sp,
                            color = subtitleColor
                        )
                        Text(
                            text = "Rp ${formatRupiah(sisa)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = VisitColors.danger
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onPayClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasStatusKhusus)
                            viewModel.getColorForStatusKhusus(pelanggan.statusKhusus)
                        else VisitColors.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Payments,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasStatusKhusus) "Hapus Status & Bayar" else "Input Pembayaran",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun HolidayEmptyState(isDark: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    Brush.linearGradient(VisitColors.warningGradient.map { it.copy(alpha = 0.2f) }),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.WbSunny,
                contentDescription = null,
                tint = VisitColors.warning,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Hari Libur",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color.White else Color(0xFF1E293B)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tidak ada kunjungan pada hari libur.\nIstirahat dan nikmati waktu Anda!",
            fontSize = 14.sp,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyVisitState(isDark: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    Brush.linearGradient(VisitColors.successGradient.map { it.copy(alpha = 0.2f) }),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = VisitColors.success,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Semua Selesai!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color.White else Color(0xFF1E293B)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Semua nasabah sudah dikunjungi\natau melakukan pembayaran hari ini.",
            fontSize = 14.sp,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ModernCatatanDialog(
    pelanggan: Pelanggan,
    viewModel: PelangganViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.1f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus)
                    )
                }
                Text(
                    text = "Catatan Status",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.1f)
                ) {
                    Text(
                        text = viewModel.getDisplayTextStatusKhusus(pelanggan.statusKhusus),
                        color = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                if (pelanggan.tanggalStatusKhusus.isNotBlank()) {
                    Text(
                        text = "Ditandai: ${pelanggan.tanggalStatusKhusus}",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }

                Text(
                    text = pelanggan.catatanStatusKhusus.ifBlank { "Tidak ada catatan" },
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
private fun ModernHapusStatusDialog(
    pelanggan: Pelanggan,
    viewModel: PelangganViewModel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.1f),
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
                Text(
                    text = "Hapus Status",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nasabah ${pelanggan.namaKtp} memiliki status:")

                Surface(
                    shape = RoundedCornerShape(12.dp),
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
                    text = "Status akan dihapus sebelum melakukan pembayaran.",
                    fontSize = 13.sp,
                    color = Color.Gray
                )

                if (pelanggan.catatanStatusKhusus.isNotBlank()) {
                    Text(
                        text = "Catatan: ${pelanggan.catatanStatusKhusus}",
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color.Gray
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus)
                )
            ) {
                Text("Hapus & Bayar", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}