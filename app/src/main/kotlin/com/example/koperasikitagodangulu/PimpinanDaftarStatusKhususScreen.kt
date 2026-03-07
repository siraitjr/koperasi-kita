package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// =========================================================================
// DATA CLASS
// =========================================================================
data class StatusKhususItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val statusKhusus: String = "",
    val catatanStatusKhusus: String = "",
    val tanggalStatusKhusus: String = "",
    val diberiTandaOleh: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val totalPiutang: Int = 0,
    val besarPinjaman: Int = 0,
    val wilayah: String = "",
    val noHp: String = ""
)

// =========================================================================
// PIMPINAN DAFTAR STATUS KHUSUS SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanDaftarStatusKhususScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val cabangId by viewModel.currentUserCabang.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // =========================================================================
    // ✅ Dark Mode State
    // =========================================================================
    val isDark by viewModel.isDarkMode
//    val bgColor = if (isDark) PimpinanColors.darkBackground else PimpinanColors.lightBackground
    val txtColor = if (isDark) Color.White else PimpinanColors.textPrimary

    var statusKhususList by remember { mutableStateOf<List<StatusKhususItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedFilter by remember { mutableStateOf("Semua") }
    var isVisible by remember { mutableStateOf(false) }
    // ✅ Set status bar & navigation bar sesuai tema dashboard
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val filterOptions = listOf("Semua", "Meninggal", "Melarikan Diri", "Menolak Bayar", "Sakit", "Lainnya")

    // Function untuk load data
    suspend fun loadData() {
        if (cabangId == null) return

        isLoading = true
        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference
            val snapshot = database.child("pelanggan_status_khusus")
                .child(cabangId!!)
                .get().await()

            val list = mutableListOf<StatusKhususItem>()
            snapshot.children.forEach { child ->
                val item = StatusKhususItem(
                    pelangganId = child.key ?: "",
                    namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                    namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                    statusKhusus = child.child("statusKhusus").getValue(String::class.java) ?: "",
                    catatanStatusKhusus = child.child("catatanStatusKhusus").getValue(String::class.java) ?: "",
                    tanggalStatusKhusus = child.child("tanggalStatusKhusus").getValue(String::class.java) ?: "",
                    diberiTandaOleh = child.child("diberiTandaOleh").getValue(String::class.java) ?: "",
                    adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                    adminName = child.child("adminName").getValue(String::class.java) ?: "",
                    totalPiutang = child.child("totalPiutang").getValue(Int::class.java) ?: 0,
                    besarPinjaman = child.child("besarPinjaman").getValue(Int::class.java) ?: 0,
                    wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
                    noHp = child.child("noHp").getValue(String::class.java) ?: ""
                )
                list.add(item)
            }

            statusKhususList = list.sortedByDescending { it.totalPiutang }
            Log.d("StatusKhusus", "✅ Loaded ${list.size} items")

        } catch (e: Exception) {
            Log.e("StatusKhusus", "Error loading: ${e.message}")
        }
        isLoading = false
        isVisible = true
    }

    // Load data saat pertama kali
    LaunchedEffect(cabangId) {
        loadData()
    }

    // Filter list berdasarkan pilihan
    val filteredList = remember(statusKhususList, selectedFilter) {
        if (selectedFilter == "Semua") {
            statusKhususList
        } else {
            val filterKey = when (selectedFilter) {
                "Meninggal" -> "MENINGGAL"
                "Melarikan Diri" -> "MELARIKAN_DIRI"
                "Menolak Bayar" -> "MENOLAK_BAYAR"
                "Sakit" -> "SAKIT"
                else -> "LAINNYA"
            }
            statusKhususList.filter { it.statusKhusus.uppercase() == filterKey }
        }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernStatusKhususTopBar(
                jumlahNasabah = filteredList.size,
                onBack = { navController.popBackStack() },
                onRefresh = { coroutineScope.launch { loadData() } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Filter Chips
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400)
                )
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filterOptions) { filter ->
                        ModernStatusFilterChip(
                            label = filter,
                            isSelected = selectedFilter == filter,
                            color = getStatusColorForFilter(filter),
                            onClick = { selectedFilter = filter }
                        )
                    }
                }
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
                ModernStatusKhususSummaryCard(
                    jumlahNasabah = filteredList.size,
                    totalPiutang = filteredList.sumOf { it.totalPiutang }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Loading atau List
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PimpinanColors.warning)
                    }
                }
                filteredList.isEmpty() -> {
                    ModernEmptyStatusKhususState(isDark = isDark)
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredList, key = { it.pelangganId }) { item ->
                            ModernStatusKhususItemCard(item, viewModel, isDark, txtColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernStatusKhususTopBar(
    jumlahNasabah: Int,
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
                    Brush.linearGradient(PimpinanColors.purpleGradient),
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
                            text = "Nasabah Status Khusus",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$jumlahNasabah nasabah",
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
private fun ModernStatusKhususSummaryCard(
    jumlahNasabah: Int,
    totalPiutang: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = PimpinanColors.warning.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(PimpinanColors.warningGradient))
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
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Flag,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Total: $jumlahNasabah nasabah",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Total Saldo: ${formatRupiah(totalPiutang)}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernStatusFilterChip(
    label: String,
    isSelected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp
            )
        },
        leadingIcon = if (isSelected) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.15f),
            selectedLabelColor = color,
            selectedLeadingIconColor = color
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun ModernStatusKhususItemCard(
    item: StatusKhususItem,
    viewModel: PelangganViewModel,
    isDark: Boolean = false,
    txtColor: Color = PimpinanColors.textPrimary
) {
    val statusColor = when (item.statusKhusus.uppercase()) {
        "MENINGGAL" -> Color(0xFF424242)
        "MELARIKAN_DIRI" -> PimpinanColors.danger
        "MENOLAK_BAYAR" -> Color(0xFFF97316)
        "SAKIT" -> PimpinanColors.info
        else -> PimpinanColors.getTextMuted(isDark)
    }

    val statusIcon = when (item.statusKhusus.uppercase()) {
        "MENINGGAL" -> Icons.Rounded.PersonOff
        "MELARIKAN_DIRI" -> Icons.Rounded.DirectionsRun
        "MENOLAK_BAYAR" -> Icons.Rounded.DoNotDisturb
        "SAKIT" -> Icons.Rounded.LocalHospital
        else -> Icons.Rounded.Help
    }

    val statusText = viewModel.getDisplayTextStatusKhusus(item.statusKhusus.uppercase())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
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
                            .background(statusColor.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.namaPanggilan.ifBlank { item.namaKtp },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = txtColor
                        )
                        Text(
                            text = item.wilayah,
                            fontSize = 12.sp,
                            color = PimpinanColors.getTextSecondary(isDark)
                        )
                    }
                }

                // Status Badge
                Surface(
                    color = statusColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Info Piutang
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Sisa Saldo",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.totalPiutang),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = PimpinanColors.danger
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Pinjaman Awal",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.besarPinjaman),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = txtColor
                    )
                }
            }

            // Catatan (jika ada)
            if (item.catatanStatusKhusus.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = PimpinanColors.getBackground(isDark)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Notes,
                            contentDescription = null,
                            tint = PimpinanColors.getTextSecondary(isDark),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = item.catatanStatusKhusus,
                            fontSize = 13.sp,
                            color = PimpinanColors.getTextSecondary(isDark),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = PimpinanColors.getBorder(isDark))
            Spacer(Modifier.height(8.dp))

            // Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                        text = "PDL: ${item.adminName.ifBlank { item.diberiTandaOleh }}",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextSecondary(isDark)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarToday,
                        contentDescription = null,
                        tint = PimpinanColors.getTextMuted(isDark),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = item.tanggalStatusKhusus,
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextSecondary(isDark)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernEmptyStatusKhususState(
    isDark: Boolean = false
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
                        PimpinanColors.success.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = PimpinanColors.success,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Tidak ada nasabah dengan status khusus",
                color = PimpinanColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )
        }
    }
}

private fun getStatusColorForFilter(status: String): Color {
    return when (status) {
        "Meninggal" -> Color(0xFF424242)
        "Melarikan Diri" -> PimpinanColors.danger
        "Menolak Bayar" -> Color(0xFFF97316)
        "Sakit" -> PimpinanColors.info
        "Lainnya" -> PimpinanColors.textMuted
        else -> PimpinanColors.info
    }
}