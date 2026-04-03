package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.example.koperasikitagodangulu.utils.formatRupiah
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.ClipboardManager
import android.content.ClipData
import android.widget.Toast
import android.util.Log
import coil.compose.AsyncImage
import com.example.koperasikitagodangulu.utils.RekeningKoranLinkHelper
import androidx.compose.foundation.lazy.LazyListState
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.AccountBalanceWallet

// Modern Color Palette
private object CustomerListColors {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaftarPelangganScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    modifier: Modifier = Modifier
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMoreData by viewModel.hasMoreData.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showFotoDialog by remember { mutableStateOf(false) }
    var fotoUrlToShow by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (viewModel.daftarPelanggan.isEmpty()) {
            viewModel.loadPelangganPaginated(isInitialLoad = true)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null) {
                    val totalItems = listState.layoutInfo.totalItemsCount
                    if (lastVisibleIndex >= totalItems - 15 && hasMoreData && !isLoadingMore) {
                        Log.d("Pagination", "🔽 Reached bottom, loading more...")
                        viewModel.loadMorePelanggan()
                    }
                }
            }
    }

    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) CustomerListColors.darkCard else CustomerListColors.lightSurface
    val borderColor = if (isDark) CustomerListColors.darkBorder else CustomerListColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) CustomerListColors.darkBackground else CustomerListColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    var showStatusDialog by remember { mutableStateOf(false) }
    var selectedPelanggan by remember { mutableStateOf<Pelanggan?>(null) }
    var showCatatanDialog by remember { mutableStateOf(false) }
    var catatanPelanggan by remember { mutableStateOf<Pelanggan?>(null) }

    val daftarPelanggan = viewModel.daftarPelanggan
    val context = LocalContext.current
    var queryNama by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf("AKTIF") }

    // Animation state
    var isVisible by rememberSaveable { mutableStateOf(true) }

    // ✅ FIX: Gunakan derivedStateOf agar otomatis recompute saat isi daftarPelanggan berubah
    // (termasuk penggantian elemen, bukan hanya perubahan ukuran list)
    val daftarTampil by remember(queryNama, selectedFilter) {
        derivedStateOf { viewModel.getFilteredPelanggan(queryNama, selectedFilter) }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            CustomerListTopBar(
                jumlahNasabah = daftarTampil.size,
                isDark = isDark,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Modern Search Field
            ModernSearchTextField(
                value = queryNama,
                onValueChange = { queryNama = it },
                isDark = isDark,
                cardColor = cardColor,
                borderColor = borderColor,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "AKTIF",
                    onClick = { selectedFilter = "AKTIF" },
                    label = { Text("Aktif", fontSize = 13.sp) },
                    leadingIcon = if (selectedFilter == "AKTIF") {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CustomerListColors.primary.copy(alpha = 0.15f),
                        selectedLabelColor = CustomerListColors.primary
                    )
                )
                FilterChip(
                    selected = selectedFilter == "MACET_LAMA",
                    onClick = { selectedFilter = "MACET_LAMA" },
                    label = { Text("Macet Lama", fontSize = 13.sp) },
                    leadingIcon = if (selectedFilter == "MACET_LAMA") {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CustomerListColors.danger.copy(alpha = 0.15f),
                        selectedLabelColor = CustomerListColors.danger
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (daftarTampil.isEmpty()) {
                // Empty State
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyCustomerState(isDark = isDark, queryNama = queryNama)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(items = daftarTampil, key = { it.id }) { pelanggan ->
                        var expanded by rememberSaveable(pelanggan.id) { mutableStateOf(false) }
                        var expandedMenu by remember { mutableStateOf(false) }

                        AnimatedVisibility(
                            visible = isVisible,
                            enter = fadeIn(tween(300)) + slideInVertically(
                                initialOffsetY = { 30 },
                                animationSpec = tween(300)
                            )
                        ) {
                            ModernCustomerCard(
                                pelanggan = pelanggan,
                                viewModel = viewModel,
                                expanded = expanded,
                                expandedMenu = expandedMenu,
                                onExpandToggle = { expanded = !expanded },
                                onExpandedMenuChange = { expandedMenu = it },
                                isDark = isDark,
                                cardColor = cardColor,
                                borderColor = borderColor,
                                txtColor = txtColor,
                                subtitleColor = subtitleColor,
                                context = context,
                                navController = navController,
                                onCardClick = {
                                    navController.navigate("riwayat/${pelanggan.id}")
                                },
                                onStatusClick = {
                                    selectedPelanggan = pelanggan
                                    showStatusDialog = true
                                },
                                onCatatanClick = {
                                    catatanPelanggan = pelanggan
                                    showCatatanDialog = true
                                },
                                onFotoClick = { url ->
                                    fotoUrlToShow = url
                                    showFotoDialog = true
                                }
                            )
                        }
                    }

                    // Loading indicator
                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = CustomerListColors.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }

                    // End of list indicator
                    if (!hasMoreData && daftarTampil.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = CustomerListColors.success.copy(alpha = 0.1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = CustomerListColors.success,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            "Semua data sudah dimuat",
                                            color = CustomerListColors.success,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
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

    // Status Dialog
    if (showStatusDialog && selectedPelanggan != null) {
        ModernStatusKhususDialog(
            pelanggan = selectedPelanggan!!,
            viewModel = viewModel,
            onDismiss = {
                showStatusDialog = false
                selectedPelanggan = null
            },
            onStatusSelected = { status, catatan ->
                showStatusDialog = false
                selectedPelanggan = null
            }
        )
    }

    // Catatan Dialog
    if (showCatatanDialog && catatanPelanggan != null) {
        ModernCatatanStatusDialog(
            pelanggan = catatanPelanggan!!,
            viewModel = viewModel,
            onDismiss = {
                showCatatanDialog = false
                catatanPelanggan = null
            }
        )
    }

    // Foto Dialog
    if (showFotoDialog && fotoUrlToShow != null) {
        Dialog(onDismissRequest = {
            showFotoDialog = false
            fotoUrlToShow = null
        }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                elevation = CardDefaults.cardElevation(16.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = fotoUrlToShow,
                        contentDescription = "Foto KTP",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    IconButton(
                        onClick = {
                            showFotoDialog = false
                            fotoUrlToShow = null
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Tutup",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerListTopBar(
    jumlahNasabah: Int,
    isDark: Boolean,
    txtColor: Color,
    subtitleColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) CustomerListColors.darkSurface else Color.White,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Daftar Nasabah",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = CustomerListColors.primary.copy(alpha = 0.1f)
            ) {
                Text(
                    text = "$jumlahNasabah Nasabah",
                    color = CustomerListColors.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernSearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                "Cari nama nasabah...",
                color = subtitleColor
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = CustomerListColors.primary
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Clear,
                        contentDescription = "Clear",
                        tint = subtitleColor
                    )
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = cardColor,
            unfocusedContainerColor = cardColor,
            focusedBorderColor = CustomerListColors.primary,
            unfocusedBorderColor = borderColor,
            cursorColor = CustomerListColors.primary,
            focusedTextColor = txtColor,
            unfocusedTextColor = txtColor
        )
    )
}

@Composable
private fun ModernCustomerCard(
    pelanggan: Pelanggan,
    viewModel: PelangganViewModel,
    expanded: Boolean,
    expandedMenu: Boolean,
    onExpandToggle: () -> Unit,
    onExpandedMenuChange: (Boolean) -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color,
    context: Context,
    navController: NavController,
    onCardClick: () -> Unit,
    onStatusClick: () -> Unit,
    onCatatanClick: () -> Unit,
    onFotoClick: (String) -> Unit
) {
    val displayName = viewModel.getDisplayName(pelanggan)
    val displayNik = viewModel.getDisplayNik(pelanggan)

    val totalBayar = pelanggan.pembayaranList.sumOf { pembayaran ->
        pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
    }
    val sisa = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)
    val sudahLunas = sisa <= 0
    val hasStatusKhusus = pelanggan.statusKhusus.isNotBlank()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = if (hasStatusKhusus)
                    viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.2f)
                else CustomerListColors.primary.copy(alpha = 0.1f)
            )
            .clickable { onCardClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column {
            // Status Khusus Banner
            if (hasStatusKhusus) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Flag,
                                contentDescription = null,
                                tint = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = viewModel.getDisplayTextStatusKhusus(pelanggan.statusKhusus),
                                color = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        if (pelanggan.catatanStatusKhusus.isNotBlank()) {
                            IconButton(
                                onClick = onCatatanClick,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = "Lihat Catatan",
                                    tint = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .then(
                                    if (hasStatusKhusus) {
                                        Modifier.border(
                                            2.dp,
                                            viewModel.getColorForStatusKhusus(pelanggan.statusKhusus),
                                            CircleShape
                                        )
                                    } else Modifier
                                )
                                .background(
                                    Brush.linearGradient(
                                        if (sudahLunas) CustomerListColors.successGradient
                                        else CustomerListColors.primaryGradient
                                    ),
                                    CircleShape
                                )
                                .clickable {
                                    val fotoUrl = when {
                                        pelanggan.fotoKtpUrl.isNotBlank() -> pelanggan.fotoKtpUrl
                                        pelanggan.fotoKtpSuamiUrl.isNotBlank() -> pelanggan.fotoKtpSuamiUrl
                                        pelanggan.fotoKtpIstriUrl.isNotBlank() -> pelanggan.fotoKtpIstriUrl
                                        else -> null
                                    }
                                    if (fotoUrl != null) {
                                        onFotoClick(fotoUrl)
                                    } else {
                                        Toast
                                            .makeText(context, "Tidak ada foto KTP tersedia", Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pelanggan.namaKtp.take(2).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            // Nomor Anggota Badge
                            if (pelanggan.nomorAnggota.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = CustomerListColors.primary.copy(alpha = 0.1f)
                                ) {
                                    Text(
                                        text = "No. ${pelanggan.nomorAnggota}",
                                        color = CustomerListColors.primary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            Text(
                                text = pelanggan.namaKtp,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = txtColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (displayName.isNotBlank()) {
                                Text(
                                    text = "($displayName)",
                                    fontSize = 13.sp,
                                    color = subtitleColor
                                )
                            }

                            Text(
                                text = pelanggan.wilayah,
                                fontSize = 13.sp,
                                color = subtitleColor
                            )
                        }

//                        // Status Badge
//                        Surface(
//                            shape = RoundedCornerShape(10.dp),
//                            color = if (sudahLunas) CustomerListColors.success.copy(alpha = 0.1f)
//                            else CustomerListColors.warning.copy(alpha = 0.1f)
//                        ) {
//                            Text(
//                                text = if (sudahLunas) "LUNAS" else "Aktif",
//                                color = if (sudahLunas) CustomerListColors.success else CustomerListColors.warning,
//                                fontSize = 11.sp,
//                                fontWeight = FontWeight.Bold,
//                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
//                            )
//                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Info Summary Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isDark) CustomerListColors.darkSurface else Color(0xFFF8FAFC),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        InfoColumn(
                            label = "Pinjaman Ke",
                            value = "${pelanggan.pinjamanKe}",
                            valueColor = txtColor,
                            labelColor = subtitleColor
                        )
                        InfoColumn(
                            label = "Total Pinjaman",
                            value = "Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                            valueColor = CustomerListColors.primary,
                            labelColor = subtitleColor
                        )
                        InfoColumn(
                            label = "Sisa",
                            value = "Rp ${formatRupiah(sisa)}",
                            valueColor = if (sudahLunas) CustomerListColors.success else CustomerListColors.warning,
                            labelColor = subtitleColor
                        )
                    }

                    // Expanded Details
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                        exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HorizontalDivider(color = borderColor)
                            Spacer(modifier = Modifier.height(4.dp))

                            DetailRow("NIK", displayNik, txtColor, subtitleColor)
                            DetailRow("Alamat KTP", pelanggan.alamatKtp, txtColor, subtitleColor)
                            DetailRow("Alamat Rumah", pelanggan.alamatRumah, txtColor, subtitleColor)
                            DetailRow("Detail Rumah", pelanggan.detailRumah, txtColor, subtitleColor)
                            DetailRow("No HP", pelanggan.noHp, txtColor, subtitleColor)
                            DetailRow("Jenis Usaha", pelanggan.jenisUsaha, txtColor, subtitleColor)
                            DetailRow("Admin (5%)", "Rp ${formatRupiah(pelanggan.admin)}", txtColor, subtitleColor)

                            val simpananLimaPersen = (pelanggan.besarPinjaman * 5) / 100
                            DetailRow("Simpanan (5%)", "Rp ${formatRupiah(simpananLimaPersen)}", txtColor, subtitleColor)

                            val simpananTambahan = pelanggan.simpanan - simpananLimaPersen - pelanggan.tarikTabungan
// ✅ Tampil jika ada simpananTambahan DAN (pinjamanKe >= 2 ATAU tarikTabungan > 0 ATAU simpanan > 5%)
                            if (simpananTambahan > 0 && (pelanggan.pinjamanKe >= 2 || pelanggan.tarikTabungan > 0 || pelanggan.simpanan > simpananLimaPersen)) {
                                DetailRow("Simpanan Tambahan", "Rp ${formatRupiah(simpananTambahan)}", txtColor, subtitleColor)
                            }

                            if (pelanggan.tarikTabungan > 0) {
                                DetailRow("Tarik Tabungan", "Rp ${formatRupiah(pelanggan.tarikTabungan)}", CustomerListColors.warning, subtitleColor)
                            }

// ✅ Tampil jika pinjamanKe >= 2 ATAU tarikTabungan > 0 ATAU simpanan > 5% (ada simpanan tambahan lama)
                            if (pelanggan.pinjamanKe >= 2 || pelanggan.tarikTabungan > 0 || pelanggan.simpanan > simpananLimaPersen) {
                                DetailRow("Total Simpanan", "Rp ${formatRupiah(pelanggan.simpanan)}", CustomerListColors.success, subtitleColor)
                            }

                            DetailRow("Total Potongan (10%)", "Rp ${formatRupiah(pelanggan.jasaPinjaman)}", txtColor, subtitleColor)
                            DetailRow("Total Pelunasan", "Rp ${formatRupiah(pelanggan.totalPelunasan)}", txtColor, subtitleColor)
                            DetailRow("Total Diterima", "Rp ${formatRupiah(pelanggan.totalDiterima)}", txtColor, subtitleColor)
                            DetailRow("Tenor", "${pelanggan.tenor} Hari", txtColor, subtitleColor)
                            DetailRow("Tanggal Pengajuan", pelanggan.tanggalPengajuan, txtColor, subtitleColor)
                            if (pelanggan.tanggalPencairan.isNotBlank()) {
                                DetailRow("Tanggal Pencairan", pelanggan.tanggalPencairan, CustomerListColors.success, subtitleColor)
                            }

                            // Info perubahan pinjaman
                            val isPinjamanDiubah = pelanggan.besarPinjamanDisetujui > 0 &&
                                    pelanggan.besarPinjamanDisetujui != pelanggan.besarPinjamanDiajukan

//                            if (isPinjamanDiubah) {
//                                Spacer(modifier = Modifier.height(4.dp))
//                                DetailRow("Pinjaman Diajukan", "Rp ${formatRupiah(pelanggan.besarPinjamanDiajukan)}", Color(0xFF757575), subtitleColor)
//                                DetailRow("Pinjaman Disetujui", "Rp ${formatRupiah(pelanggan.besarPinjamanDisetujui)}", CustomerListColors.success, subtitleColor)
////                                if (pelanggan.catatanPerubahanPinjaman.isNotBlank()) {
////                                    DetailRow("Catatan Pimpinan", pelanggan.catatanPerubahanPinjaman, CustomerListColors.primary, subtitleColor)
////                                }
//                            }

                            // Info status khusus
                            if (hasStatusKhusus) {
                                Spacer(modifier = Modifier.height(4.dp))
                                DetailRow("Status Khusus", viewModel.getDisplayTextStatusKhusus(pelanggan.statusKhusus), viewModel.getColorForStatusKhusus(pelanggan.statusKhusus), subtitleColor)
                                if (pelanggan.catatanStatusKhusus.isNotBlank()) {
                                    DetailRow("Catatan", pelanggan.catatanStatusKhusus, txtColor, subtitleColor)
                                }
                                if (pelanggan.tanggalStatusKhusus.isNotBlank()) {
                                    DetailRow("Ditandai Pada", pelanggan.tanggalStatusKhusus, txtColor, subtitleColor)
                                }
                                if (pelanggan.diberiTandaOleh.isNotBlank()) {
                                    DetailRow("Ditandai Oleh", pelanggan.diberiTandaOleh, txtColor, subtitleColor)
                                }
                            }

                            // Info approval
//                            if (pelanggan.catatanApproval.isNotBlank()) {
//                                Spacer(modifier = Modifier.height(4.dp))
//                                DetailRow("Catatan Approval", pelanggan.catatanApproval, CustomerListColors.success, subtitleColor)
//                            }
                            if (pelanggan.tanggalApproval.isNotBlank()) {
                                DetailRow("Disetujui Pada", pelanggan.tanggalApproval, CustomerListColors.success, subtitleColor)
                            }
                            if (pelanggan.disetujuiOleh.isNotBlank()) {
                                DetailRow("Disetujui Oleh", pelanggan.disetujuiOleh, CustomerListColors.success, subtitleColor)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Expand/Collapse Button
                        TextButton(onClick = onExpandToggle) {
                            Icon(
                                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = CustomerListColors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (expanded) "Tutup" else "Detail",
                                color = CustomerListColors.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

//                        // Status Button
//                        TextButton(onClick = onStatusClick) {
//                            Icon(
//                                imageVector = Icons.Rounded.Flag,
//                                contentDescription = null,
//                                tint = if (hasStatusKhusus)
//                                    viewModel.getColorForStatusKhusus(pelanggan.statusKhusus)
//                                else subtitleColor,
//                                modifier = Modifier.size(20.dp)
//                            )
//                            Spacer(modifier = Modifier.width(4.dp))
//                            Text(
//                                text = "Status",
//                                color = if (hasStatusKhusus)
//                                    viewModel.getColorForStatusKhusus(pelanggan.statusKhusus)
//                                else subtitleColor,
//                                fontSize = 13.sp,
//                                fontWeight = FontWeight.Medium
//                            )
//                        }
                        Text(
                            text = when {
                                sudahLunas -> "LUNAS"
                                pelanggan.status == "Disetujui" -> "Disetujui"
                                pelanggan.status == "Tidak Aktif" -> "Tidak Aktif"
                                else -> "Aktif"
                            },
                            color = when {
                                sudahLunas -> CustomerListColors.success
                                pelanggan.status == "Disetujui" -> Color(0xFF7C3AED)
                                pelanggan.status == "Tidak Aktif" -> Color(0xFF9E9E9E)
                                else -> CustomerListColors.warning
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    // === TOMBOL CAIRKAN & BATAL (di bawah baris Detail/Disetujui) ===
                    if (pelanggan.status == "Disetujui") {
                        Spacer(modifier = Modifier.height(8.dp))

                        var showCairkanDialog by remember { mutableStateOf(false) }
                        var showBatalDialog by remember { mutableStateOf(false) }
                        var isProcessing by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showBatalDialog = true },
                                modifier = Modifier.weight(1f),
                                enabled = !isProcessing,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFF44336)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFF44336))
                            ) {
                                Icon(
                                    Icons.Rounded.Cancel,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Batal", fontSize = 12.sp)
                            }

                            Button(
                                onClick = { showCairkanDialog = true },
                                modifier = Modifier.weight(1f),
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CustomerListColors.success
                                )
                            ) {
                                Icon(
                                    Icons.Rounded.AccountBalanceWallet,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Cairkan", fontSize = 12.sp, color = Color.White)
                            }
                        }

                        // Dialog Konfirmasi Cairkan
                        if (showCairkanDialog) {
                            AlertDialog(
                                onDismissRequest = { if (!isProcessing) showCairkanDialog = false },
                                title = { Text("Cairkan Pinjaman") },
                                text = {
                                    Text(
                                        "Apakah Anda yakin akan mencairkan pinjaman " +
                                                "Rp ${viewModel.formatRupiahPublic(pelanggan.besarPinjaman)} " +
                                                "kepada ${pelanggan.namaPanggilan}?\n\n" +
                                                "Jadwal cicilan akan dihitung mulai besok."
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            isProcessing = true
                                            viewModel.cairkanPinjaman(
                                                pelangganId = pelanggan.id,
                                                onSuccess = {
                                                    isProcessing = false
                                                    showCairkanDialog = false
                                                    Toast.makeText(context, "✅ Pinjaman berhasil dicairkan", Toast.LENGTH_SHORT).show()
                                                },
                                                onFailure = { e ->
                                                    isProcessing = false
                                                    showCairkanDialog = false
                                                    Toast.makeText(context, "❌ Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        },
                                        enabled = !isProcessing,
                                        colors = ButtonDefaults.buttonColors(containerColor = CustomerListColors.success)
                                    ) {
                                        if (isProcessing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text("Ya, Cairkan", color = Color.White)
                                        }
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { showCairkanDialog = false },
                                        enabled = !isProcessing
                                    ) {
                                        Text("Batal")
                                    }
                                }
                            )
                        }

                        // Dialog Konfirmasi Batal Pinjaman
                        if (showBatalDialog) {
                            AlertDialog(
                                onDismissRequest = { if (!isProcessing) showBatalDialog = false },
                                title = { Text("Batalkan Pinjaman") },
                                text = {
                                    Text(
                                        "Apakah Anda yakin akan MEMBATALKAN pinjaman " +
                                                "${pelanggan.namaPanggilan}?\n\n" +
                                                "Status akan berubah menjadi 'Tidak Aktif'."
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            isProcessing = true
                                            viewModel.batalkanPinjaman(
                                                pelangganId = pelanggan.id,
                                                onSuccess = {
                                                    isProcessing = false
                                                    showBatalDialog = false
                                                    Toast.makeText(context, "Pinjaman dibatalkan", Toast.LENGTH_SHORT).show()
                                                },
                                                onFailure = { e ->
                                                    isProcessing = false
                                                    showBatalDialog = false
                                                    Toast.makeText(context, "❌ Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        },
                                        enabled = !isProcessing,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                                    ) {
                                        if (isProcessing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text("Ya, Batalkan", color = Color.White)
                                        }
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { showBatalDialog = false },
                                        enabled = !isProcessing
                                    ) {
                                        Text("Tidak")
                                    }
                                }
                            )
                        }
                    }
                }

                // Menu kanan atas
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    IconButton(onClick = { onExpandedMenuChange(true) }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Menu",
                            tint = subtitleColor
                        )
                    }

                    ModernDropdownMenu(
                        expanded = expandedMenu,
                        onDismissRequest = { onExpandedMenuChange(false) },
                        pelanggan = pelanggan,
                        viewModel = viewModel,
                        navController = navController,
                        context = context,
                        onStatusClick = onStatusClick
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    pelanggan: Pelanggan,
    viewModel: PelangganViewModel,
    navController: NavController,
    context: Context,
    onStatusClick: () -> Unit
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var pelangganToDelete by remember { mutableStateOf<Pelanggan?>(null) }

    // ✅ BARU: State untuk Tenor Change
    var showTenorChangeDialog by remember { mutableStateOf(false) }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        if (pelanggan.status == "Tidak Aktif") {
            // === STATUS "TIDAK AKTIF": Hanya menu Ajukan Penghapusan ===
            DropdownMenuItem(
                text = { Text("Ajukan Penghapusan", color = CustomerListColors.warning) },
                onClick = {
                    onDismissRequest()
                    pelangganToDelete = pelanggan
                    showDeleteConfirmation = true
                },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = CustomerListColors.warning) }
            )
        } else if (pelanggan.status == "Disetujui") {
            // === STATUS "DISETUJUI": Menu terbatas ===
            DropdownMenuItem(
                text = { Text("Edit Nasabah") },
                onClick = {
                    onDismissRequest()
                    navController.navigate("edit/${pelanggan.id}")
                },
                leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = CustomerListColors.primary) }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Ajukan Penghapusan", color = CustomerListColors.warning) },
                onClick = {
                    onDismissRequest()
                    pelangganToDelete = pelanggan
                    showDeleteConfirmation = true
                },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = CustomerListColors.warning) }
            )
        } else {
            // === STATUS "AKTIF" atau LAINNYA: Menu lengkap (KODE YANG SUDAH ADA) ===
            DropdownMenuItem(
                text = { Text("Edit Nasabah") },
                onClick = {
                    onDismissRequest()
                    navController.navigate("edit/${pelanggan.id}")
                },
                leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = CustomerListColors.primary) }
            )
            DropdownMenuItem(
                text = { Text("Salin Link Rekening Koran") },
                onClick = {
                    onDismissRequest()
                    RekeningKoranLinkHelper.copyToClipboard(
                        context = context,
                        adminUid = pelanggan.adminUid,
                        pelangganId = pelanggan.id,
                        namaDisplay = pelanggan.namaPanggilan.ifBlank { pelanggan.namaKtp }
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = CustomerListColors.primary) }
            )
            DropdownMenuItem(
                text = { Text("Kelola Pinjaman") },
                onClick = {
                    onDismissRequest()
                    navController.navigate("kelolaKredit/${pelanggan.id}")
                },
                leadingIcon = { Icon(Icons.Rounded.Settings, null, tint = CustomerListColors.primary) }
            )

            if (pelanggan.status == "Aktif" || pelanggan.status == "Menunggu Approval") {
                DropdownMenuItem(
                    text = { Text("Ajukan Perubahan Tenor") },
                    onClick = {
                        onDismissRequest()
                        showTenorChangeDialog = true
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Schedule,
                            null,
                            tint = Color(0xFF2196F3)
                        )
                    }
                )
            }

            HorizontalDivider()

            DropdownMenuItem(
                text = {
                    Text(
                        if (pelanggan.statusKhusus.isBlank()) "Tandai Status Khusus"
                        else "Edit Status Khusus"
                    )
                },
                onClick = {
                    onDismissRequest()
                    onStatusClick()
                },
                leadingIcon = {
                    Icon(
                        if (pelanggan.statusKhusus.isBlank()) Icons.Rounded.Flag else Icons.Rounded.Edit,
                        null,
                        tint = CustomerListColors.warning
                    )
                }
            )

            if (pelanggan.statusKhusus.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Hapus Status Khusus") },
                    onClick = {
                        onDismissRequest()
                        viewModel.hapusStatusKhususPelanggan(pelanggan.id)
                    },
                    leadingIcon = { Icon(Icons.Rounded.Clear, null, tint = CustomerListColors.warning) }
                )
            }

            HorizontalDivider()

            DropdownMenuItem(
                text = { Text("Ajukan Penghapusan", color = CustomerListColors.warning) },
                onClick = {
                    onDismissRequest()
                    pelangganToDelete = pelanggan
                    showDeleteConfirmation = true
                },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = CustomerListColors.warning) }
            )
        }
    }

    // Delete Confirmation Dialog - Now creates a deletion request for Pengawas approval
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
                            .background(CustomerListColors.warning.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = CustomerListColors.warning
                        )
                    }
                    Text(
                        "Ajukan Penghapusan",
                        fontWeight = FontWeight.Bold,
                        color = CustomerListColors.warning
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
                            Toast.makeText(context, "Harap isi alasan penghapusan", Toast.LENGTH_SHORT).show()
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
                    colors = ButtonDefaults.buttonColors(containerColor = CustomerListColors.warning),
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
    // =========================================================================
    // ✅ BARU: Dialog Perubahan Tenor
    // =========================================================================
    if (showTenorChangeDialog) {
        var selectedTenor by remember { mutableStateOf(pelanggan.tenor) }
        var alasanPerubahan by remember { mutableStateOf("") }
        var isSubmitting by remember { mutableStateOf(false) }
        val tenorOptions = listOf(24, 28, 30, 36, 40) // Opsi tenor yang tersedia

        AlertDialog(
            onDismissRequest = {
                if (!isSubmitting) {
                    showTenorChangeDialog = false
                    alasanPerubahan = ""
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
                            .background(Color(0xFF2196F3).copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = Color(0xFF2196F3)
                        )
                    }
                    Text(
                        "Ajukan Perubahan Tenor",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3)
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Info nasabah
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF5F5F5)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                pelanggan.namaKtp,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                "Tenor saat ini: ${pelanggan.tenor} hari",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                            Text(
                                "Pinjaman: Rp ${formatRupiah(pelanggan.besarPinjaman)}",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    // Pilihan tenor baru
                    Text(
                        "Pilih tenor baru:",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        tenorOptions.forEach { tenor ->
                            val isSelected = selectedTenor == tenor
                            val isCurrent = pelanggan.tenor == tenor

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = !isCurrent && !isSubmitting) {
                                        selectedTenor = tenor
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    isCurrent -> Color(0xFFE0E0E0)
                                    isSelected -> Color(0xFF2196F3)
                                    else -> Color(0xFFF5F5F5)
                                },
                                border = if (isSelected && !isCurrent) {
                                    androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF2196F3))
                                } else null
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "$tenor",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            isCurrent -> Color.Gray
                                            isSelected -> Color.White
                                            else -> Color.Black
                                        }
                                    )
                                    Text(
                                        "hari",
                                        fontSize = 12.sp,
                                        color = when {
                                            isCurrent -> Color.Gray
                                            isSelected -> Color.White.copy(alpha = 0.8f)
                                            else -> Color.Gray
                                        }
                                    )
                                    if (isCurrent) {
                                        Text(
                                            "(Saat ini)",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Input alasan
                    OutlinedTextField(
                        value = alasanPerubahan,
                        onValueChange = { alasanPerubahan = it },
                        label = { Text("Alasan Perubahan Tenor *") },
                        placeholder = { Text("Contoh: Nasabah meminta perpanjangan karena...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2,
                        maxLines = 4,
                        enabled = !isSubmitting
                    )

                    // Info
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE3F2FD)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = Color(0xFF1976D2),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Pengajuan akan dikirim ke Pimpinan cabang untuk persetujuan.",
                                fontSize = 12.sp,
                                color = Color(0xFF1976D2)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (alasanPerubahan.isBlank()) {
                            Toast.makeText(context, "Harap isi alasan perubahan tenor", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (selectedTenor == pelanggan.tenor) {
                            Toast.makeText(context, "Pilih tenor yang berbeda", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        isSubmitting = true
                        viewModel.createTenorChangeRequest(
                            pelanggan = pelanggan,
                            tenorBaru = selectedTenor,
                            alasanPerubahan = alasanPerubahan,
                            onSuccess = {
                                isSubmitting = false
                                showTenorChangeDialog = false
                                Toast.makeText(
                                    context,
                                    "Pengajuan perubahan tenor berhasil dikirim ke Pimpinan",
                                    Toast.LENGTH_LONG
                                ).show()
                                alasanPerubahan = ""
                            },
                            onFailure = { exception ->
                                isSubmitting = false
                                Toast.makeText(
                                    context,
                                    "Gagal mengajukan: ${exception.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    },
                    enabled = alasanPerubahan.isNotBlank() && selectedTenor != pelanggan.tenor && !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
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
                        if (isSubmitting) "Mengirim..." else "Ajukan Perubahan",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTenorChangeDialog = false
                        alasanPerubahan = ""
                    },
                    enabled = !isSubmitting
                ) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun InfoColumn(
    label: String,
    value: String,
    valueColor: Color,
    labelColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = labelColor
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    txtColor: Color,
    labelColor: Color
) {
    if (value.isNotBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = labelColor,
                modifier = Modifier.weight(0.4f)
            )
            Text(
                text = value,
                fontSize = 13.sp,
                color = txtColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(0.6f),
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun EmptyCustomerState(isDark: Boolean, queryNama: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    Brush.linearGradient(CustomerListColors.primaryGradient.map { it.copy(alpha = 0.2f) }),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (queryNama.isNotEmpty()) Icons.Rounded.SearchOff else Icons.Rounded.Groups,
                contentDescription = null,
                tint = CustomerListColors.primary,
                modifier = Modifier.size(50.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (queryNama.isNotEmpty()) "Tidak Ditemukan" else "Belum Ada Nasabah",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color.White else Color(0xFF1E293B)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (queryNama.isNotEmpty())
                "Nasabah dengan nama \"$queryNama\" tidak ditemukan"
            else "Data nasabah akan muncul di sini",
            fontSize = 14.sp,
            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ModernStatusKhususDialog(
    pelanggan: Pelanggan,
    viewModel: PelangganViewModel,
    onDismiss: () -> Unit,
    onStatusSelected: (String, String) -> Unit
) {
    var selectedStatus by remember { mutableStateOf(pelanggan.statusKhusus) }
    var catatan by remember { mutableStateOf(pelanggan.catatanStatusKhusus) }
    val daftarStatus = viewModel.getDaftarStatusKhusus()

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
                        .size(44.dp)
                        .background(
                            Brush.linearGradient(CustomerListColors.primaryGradient),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Flag,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
                Column {
                    Text(
                        text = "Status Khusus",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = pelanggan.namaKtp,
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Pilih status untuk nasabah:",
                    fontSize = 14.sp,
                    color = Color.Gray
                )

                LazyColumn(
                    modifier = Modifier.height(200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(daftarStatus) { status ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedStatus = status },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedStatus == status)
                                viewModel.getColorForStatusKhusus(status).copy(alpha = 0.15f)
                            else Color.Transparent,
                            border = if (selectedStatus == status) null
                            else ButtonDefaults.outlinedButtonBorder
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(
                                    selected = selectedStatus == status,
                                    onClick = { selectedStatus = status },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = viewModel.getColorForStatusKhusus(status)
                                    )
                                )
                                Text(
                                    text = viewModel.getDisplayTextStatusKhusus(status),
                                    color = viewModel.getColorForStatusKhusus(status),
                                    fontWeight = if (selectedStatus == status) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = catatan,
                    onValueChange = { catatan = it },
                    label = { Text("Catatan (opsional)") },
                    placeholder = { Text("Deskripsi detail...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedStatus.isNotBlank()) {
                        viewModel.updateStatusKhususPelanggan(
                            pelangganId = pelanggan.id,
                            statusKhusus = selectedStatus,
                            catatan = catatan,
                            onSuccess = {
                                onStatusSelected(selectedStatus, catatan)
                                onDismiss()
                            }
                        )
                    }
                },
                enabled = selectedStatus.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CustomerListColors.primary
                )
            ) {
                Text(
                    if (pelanggan.statusKhusus.isBlank()) "Tandai" else "Update",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal")
            }
        }
    )
}

@Composable
fun ModernCatatanStatusDialog(
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
                        .size(44.dp)
                        .background(
                            viewModel.getColorForStatusKhusus(pelanggan.statusKhusus).copy(alpha = 0.15f),
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
                        text = "📅 Ditandai: ${pelanggan.tanggalStatusKhusus}",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }

                if (pelanggan.diberiTandaOleh.isNotBlank()) {
                    Text(
                        text = "👤 Oleh: ${pelanggan.diberiTandaOleh}",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }

                HorizontalDivider()

                Text(
                    text = "Catatan:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = pelanggan.catatanStatusKhusus.ifBlank { "Tidak ada catatan" },
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = viewModel.getColorForStatusKhusus(pelanggan.statusKhusus)
                )
            ) {
                Text("Tutup", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

// Helper functions yang dipertahankan dari file asli
@Composable
fun InfoText(label: String, value: String, color: Color) {
    if (value.isNotBlank()) {
        Text("$label: $value", color = color, fontSize = 14.sp)
    }
}

@Composable
fun AvatarCircle(nama: String, modifier: Modifier = Modifier) {
    val initials = nama
        .trim()
        .split(" ")
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
    val clip = ClipData.newPlainText("Copied Text", text)
    clipboard?.setPrimaryClip(clip)
    Toast.makeText(context, "Teks disalin", Toast.LENGTH_SHORT).show()
}

private fun isPelangganBenarBenarLunas(pelanggan: Pelanggan): Boolean {
    if (pelanggan.status == "Disetujui" || pelanggan.status == "Tidak Aktif") return false
    @Suppress("UNCHECKED_CAST")
    val totalBayar = (pelanggan.pembayaranList as List<Pembayaran?>).filterNotNull().sumOf { pembayaran ->
        pembayaran.jumlah + (pembayaran.subPembayaran as List<SubPembayaran?>).filterNotNull().sumOf { sub -> sub.jumlah }
    }
    return totalBayar >= pelanggan.totalPelunasan && pelanggan.status != "Menunggu Approval"
}