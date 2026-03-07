package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

// =========================================================================
// DATA CLASS
// =========================================================================
data class KoordinatorStatusKhususItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val statusKhusus: String = "",
    val catatanStatusKhusus: String = "",
    val tanggalStatusKhusus: String = "",
    val diberiTandaOleh: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val cabangId: String = "",
    val totalPiutang: Long = 0,
    val besarPinjaman: Long = 0,
    val wilayah: String = "",
    val noHp: String = "",
    val timestamp: Long = 0
)

// =========================================================================
// KOORDINATOR DAFTAR STATUS KHUSUS SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoordinatorDaftarStatusKhususScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val selectedCabangId by viewModel.pengawasSelectedCabangId.collectAsState()

    // =========================================================================
    // ✅ DARK MODE SUPPORT
    // =========================================================================
    val isDark by viewModel.isDarkMode
    val cabangOptions by viewModel.pengawasCabangOptions.collectAsState()
    val systemUiController = rememberSystemUiController()
    val bgColor = KoordinatorColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val coroutineScope = rememberCoroutineScope()

    var statusKhususList by remember { mutableStateOf<List<KoordinatorStatusKhususItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedFilter by remember { mutableStateOf("Semua") }

    val filterOptions = listOf(
        "Semua",
        "Menunggu Pencairan",
        "Meninggal",
        "Melarikan Diri",
        "Menolak Bayar",
        "Sakit"
    )

    // =====================================================================
    // ✅ HEMAT: Baca dari node aggregate pelanggan_status_khusus
    // Node ini sudah ada dan di-maintain oleh Android saat admin set status
    // =====================================================================
    suspend fun loadData() {
        isLoading = true
        errorMessage = null

        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference
            val list = mutableListOf<KoordinatorStatusKhususItem>()

            // Tentukan cabang yang akan di-query
            val cabangIds = if (selectedCabangId == null) {
                // Semua cabang
                cabangOptions.mapNotNull { it.first }
            } else {
                listOf(selectedCabangId!!)
            }

            Log.d("KoordinatorStatusKhusus", "📋 Loading from ${cabangIds.size} cabang")

            // ✅ HEMAT: Query dari node aggregate (BUKAN pelanggan)
            for (cabangId in cabangIds) {
                try {
                    val snap = database.child("pelanggan_status_khusus/$cabangId").get().await()

                    Log.d("KoordinatorStatusKhusus", "📂 Cabang $cabangId: ${snap.childrenCount} items")

                    for (child in snap.children) {
                        list.add(KoordinatorStatusKhususItem(
                            pelangganId = child.key ?: "",
                            namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                            namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                            statusKhusus = child.child("statusKhusus").getValue(String::class.java) ?: "",
                            catatanStatusKhusus = child.child("catatanStatusKhusus").getValue(String::class.java) ?: "",
                            tanggalStatusKhusus = child.child("tanggalStatusKhusus").getValue(String::class.java) ?: "",
                            diberiTandaOleh = child.child("diberiTandaOleh").getValue(String::class.java) ?: "",
                            adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                            adminName = child.child("adminName").getValue(String::class.java) ?: "",
                            cabangId = cabangId,
                            totalPiutang = child.child("totalPiutang").getValue(Long::class.java)
                                ?: child.child("totalPiutang").getValue(Int::class.java)?.toLong()
                                ?: 0L,
                            besarPinjaman = child.child("besarPinjaman").getValue(Long::class.java)
                                ?: child.child("besarPinjaman").getValue(Int::class.java)?.toLong()
                                ?: 0L,
                            wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
                            noHp = child.child("noHp").getValue(String::class.java) ?: "",
                            timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L
                        ))
                    }
                } catch (e: Exception) {
                    Log.e("KoordinatorStatusKhusus", "Error loading cabang $cabangId: ${e.message}")
                }
            }

            statusKhususList = list.sortedByDescending { it.timestamp }
            Log.d("KoordinatorStatusKhusus", "✅ Total loaded: ${list.size} items")

        } catch (e: Exception) {
            Log.e("KoordinatorStatusKhusus", "Error loading: ${e.message}")
            errorMessage = "Gagal memuat data: ${e.message}"
        }

        isLoading = false
        isVisible = true
    }

    // Load data saat cabang berubah
    LaunchedEffect(selectedCabangId) {
        loadData()
    }

    // Filter list
    val filteredList = remember(statusKhususList, selectedFilter) {
        if (selectedFilter == "Semua") {
            statusKhususList
        } else {
            statusKhususList.filter {
                it.statusKhusus.replace("_", " ").equals(selectedFilter, ignoreCase = true)
            }
        }
    }

    // Hitung per kategori
    val countPerKategori = remember(statusKhususList) {
        statusKhususList.groupingBy {
            it.statusKhusus.replace("_", " ").lowercase().replaceFirstChar { c -> c.uppercase() }
        }.eachCount()
    }

    val totalPiutang = remember(filteredList) {
        filteredList.sumOf { it.totalPiutang }
    }

    Scaffold(
        containerColor = KoordinatorColors.getBackground(isDark),
        topBar = {
            KoordinatorStatusKhususTopBar(
                isDark = isDark,
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
        ) {
            // Cabang Filter
            KoordinatorStatusKhususCabangFilter(
                isDark = isDark,
                cabangOptions = cabangOptions,
                selectedCabangId = selectedCabangId,
                onCabangSelected = { viewModel.setPengawasSelectedCabang(it) }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Summary Card
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400)) + slideInVertically(
                        initialOffsetY = { -20 },
                        animationSpec = tween(400)
                    )
                ) {
                    KoordinatorStatusKhususSummaryCard(
                        isDark = isDark,
                        jumlahNasabah = filteredList.size,
                        totalPiutang = totalPiutang,
                        countPerKategori = countPerKategori
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filterOptions) { filter ->
                        val count = if (filter == "Semua") {
                            statusKhususList.size
                        } else {
                            countPerKategori[filter] ?: 0
                        }

                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = {
                                Text(
                                    text = "$filter ($count)",
                                    fontSize = 12.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = getStatusColor(filter),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Error message
                if (errorMessage != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                    ) {
                        Text(
                            text = errorMessage!!,
                            modifier = Modifier.padding(16.dp),
                            color = Color(0xFFC62828)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Loading atau List
                when {
                    isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = KoordinatorColors.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Memuat data...",
                                    color = KoordinatorColors.getTextSecondary(isDark)
                                )
                            }
                        }
                    }
                    filteredList.isEmpty() -> {
                        KoordinatorEmptyStatusKhususState(selectedFilter, isDark = isDark)
                    }
                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(filteredList, key = { "${it.cabangId}_${it.pelangganId}" }) { item ->
                                AnimatedVisibility(
                                    visible = isVisible,
                                    enter = fadeIn(tween(300)) + slideInVertically(
                                        initialOffsetY = { 20 },
                                        animationSpec = tween(300)
                                    )
                                ) {
                                    KoordinatorStatusKhususCard(item = item, isDark = isDark)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// HELPER FUNCTIONS
// =========================================================================

private fun getStatusColor(status: String): Color {
    return when (status.lowercase().replace("_", " ")) {
        "menunggu pencairan" -> Color(0xFF10B981)  // Green
        "meninggal" -> Color(0xFF6B7280)           // Gray
        "melarikan diri" -> Color(0xFFEF4444)      // Red
        "menolak bayar" -> Color(0xFFF59E0B)       // Orange
        "sakit" -> Color(0xFF3B82F6)               // Blue
        else -> KoordinatorColors.primary
    }
}

// =========================================================================
// COMPONENTS
// =========================================================================

@Composable
private fun KoordinatorStatusKhususCabangFilter(
    isDark: Boolean = false,
    cabangOptions: List<Pair<String?, String>>,
    selectedCabangId: String?,
    onCabangSelected: (String?) -> Unit
) {
    Surface(
        color = KoordinatorColors.getCard(isDark),
        shadowElevation = 2.dp
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(cabangOptions) { (cabangId, cabangName) ->
                val isSelected = selectedCabangId == cabangId

                FilterChip(
                    selected = isSelected,
                    onClick = { onCabangSelected(cabangId) },
                    label = {
                        Text(
                            text = cabangName,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
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
                        selectedContainerColor = KoordinatorColors.primary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KoordinatorStatusKhususTopBar(
    isDark: Boolean = false,
    jumlahNasabah: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp),
        color = KoordinatorColors.getCard(isDark)
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        "Status Khusus",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        "$jumlahNasabah nasabah",
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, "Kembali")
                }
            },
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Rounded.Refresh,
                        "Refresh",
                        tint = KoordinatorColors.primary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.White
            )
        )
    }
}

@Composable
private fun KoordinatorStatusKhususSummaryCard(
    isDark: Boolean = false,
    jumlahNasabah: Int,
    totalPiutang: Long,
    countPerKategori: Map<String, Int>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF7C3AED), Color(0xFFA78BFA))
                    )
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Total Saldo Berisiko",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    Text(
                        text = formatRupiah(totalPiutang.toInt()),
                        color = KoordinatorColors.getCard(isDark),
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$jumlahNasabah nasabah dengan status khusus",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Flag,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun KoordinatorStatusKhususCard(item: KoordinatorStatusKhususItem, isDark: Boolean = false) {
    val statusColor = getStatusColor(item.statusKhusus)
    val statusText = item.statusKhusus.replace("_", " ").lowercase()
        .replaceFirstChar { it.uppercase() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = KoordinatorColors.getCard(isDark))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Nama + Badge Status
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
                            .size(44.dp)
                            .background(statusColor.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.namaPanggilan.firstOrNull()?.uppercase() ?: "?",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = statusColor
                        )
                    }

                    Column {
                        Text(
                            text = item.namaPanggilan.ifBlank { item.namaKtp },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = KoordinatorColors.getTextPrimary(isDark)
                        )
                        Text(
                            text = item.wilayah,
                            fontSize = 12.sp,
                            color = KoordinatorColors.getTextSecondary(isDark)
                        )
                    }
                }

                // Badge Status
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

            // Catatan jika ada
            if (item.catatanStatusKhusus.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = Color(0xFFF3F4F6),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "\"${item.catatanStatusKhusus}\"",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 12.sp,
                        color = KoordinatorColors.getTextSecondary(isDark),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Info Piutang
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Total Saldo",
                        fontSize = 11.sp,
                        color = KoordinatorColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.totalPiutang.toInt()),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = KoordinatorColors.danger
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Pinjaman Awal",
                        fontSize = 11.sp,
                        color = KoordinatorColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.besarPinjaman.toInt()),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = KoordinatorColors.getTextPrimary(isDark)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = KoordinatorColors.getBorder(isDark))
            Spacer(Modifier.height(8.dp))

            // Footer: Admin, Cabang, Tanggal
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
                        tint = KoordinatorColors.getTextMuted(isDark),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = item.adminName.ifBlank { item.diberiTandaOleh },
                        fontSize = 11.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarToday,
                        contentDescription = null,
                        tint = KoordinatorColors.getTextMuted(isDark),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = item.tanggalStatusKhusus,
                        fontSize = 11.sp,
                        color = KoordinatorColors.getTextSecondary(isDark)
                    )
                }
            }
        }
    }
}

@Composable
private fun KoordinatorEmptyStatusKhususState(filter: String, isDark: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        KoordinatorColors.primary.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = KoordinatorColors.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (filter == "Semua")
                    "Tidak ada nasabah dengan status khusus"
                else
                    "Tidak ada nasabah dengan status \"$filter\"",
                color = KoordinatorColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )
        }
    }
}