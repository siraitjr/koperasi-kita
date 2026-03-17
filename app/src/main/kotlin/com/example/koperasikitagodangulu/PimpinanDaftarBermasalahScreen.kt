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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.backgroundColor
import com.example.koperasikitagodangulu.utils.textColor
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// =========================================================================
// DATA CLASS
// =========================================================================
data class NasabahBermasalahItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val totalPiutang: Int = 0,
    val totalPinjaman: Int = 0,
    val hariTunggakan: Int = 0,
    val kategori: String = "", // "ringan", "sedang", "berat", "macet"
    val wilayah: String = "",
    val noHp: String = "",
    val lastPaymentDate: String = "",
    val timestamp: Long = 0
)

// =========================================================================
// PIMPINAN DAFTAR BERMASALAH SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanDaftarBermasalahScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val tanggalHariIni = SimpleDateFormat("dd MMM yyyy", Locale("id")).format(Date())
    val isDark by viewModel.isDarkMode
//    val bgColor = if (isDark) PimpinanColors.darkBackground else PimpinanColors.lightBackground
    val txtColor = if (isDark) Color.White else PimpinanColors.textPrimary
    // ✅ Set status bar & navigation bar sesuai tema dashboard
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val cabangId by viewModel.currentUserCabang.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // State untuk data
    var nasabahBermasalahList by remember { mutableStateOf<List<NasabahBermasalahItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedFilter by remember { mutableStateOf("Semua") }
    var isVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filterOptions = listOf("Semua", "Ringan", "Sedang", "Berat", "Macet")

    // Function untuk load data
    suspend fun loadData() {
        if (cabangId == null) return

        isLoading = true
        errorMessage = null

        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference

            val snapshot = database.child("pelanggan_bermasalah")
                .child(cabangId!!)
                .get().await()

            val list = mutableListOf<NasabahBermasalahItem>()

            if (snapshot.exists() && snapshot.childrenCount > 0) {
                snapshot.children.forEach { child ->
                    val item = NasabahBermasalahItem(
                        pelangganId = child.key ?: "",
                        namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                        namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                        adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                        adminName = child.child("adminName").getValue(String::class.java) ?: "",
                        totalPiutang = child.child("totalPiutang").getValue(Int::class.java) ?: 0,
                        totalPinjaman = child.child("totalPinjaman").getValue(Int::class.java) ?: 0,
                        hariTunggakan = child.child("hariTunggakan").getValue(Int::class.java) ?: 0,
                        kategori = child.child("kategori").getValue(String::class.java) ?: "",
                        wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
                        noHp = child.child("noHp").getValue(String::class.java) ?: "",
                        lastPaymentDate = child.child("lastPaymentDate").getValue(String::class.java) ?: "",
                        timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0
                    )
                    list.add(item)
                }
                Log.d("NasabahBermasalah", "✅ Loaded ${list.size} from pelanggan_bermasalah")
            } else {
                Log.d("NasabahBermasalah", "ℹ️ No data in pelanggan_bermasalah")
            }

            nasabahBermasalahList = list.sortedByDescending { it.hariTunggakan }

        } catch (e: Exception) {
            Log.e("NasabahBermasalah", "Error loading: ${e.message}")
            errorMessage = "Gagal memuat data: ${e.message}"
        }

        isLoading = false
        isVisible = true
    }

    LaunchedEffect(cabangId) {
        loadData()
    }

    // Filter list
    val filteredList = remember(nasabahBermasalahList, selectedFilter, searchQuery) {
        var result = if (selectedFilter == "Semua") {
            nasabahBermasalahList
        } else {
            nasabahBermasalahList.filter {
                it.kategori.equals(selectedFilter, ignoreCase = true)
            }
        }
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase().trim()
            result = result.filter {
                it.namaPanggilan.lowercase().contains(query) ||
                    it.namaKtp.lowercase().contains(query)
            }
        }
        result
    }

    val totalPiutang = filteredList.sumOf { it.totalPiutang }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernBermasalahTopBar(
                jumlahNasabah = filteredList.size,
                onBack = { navController.popBackStack() },
                onRefresh = { coroutineScope.launch { loadData() } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                ModernBermasalahSummaryCard(
                    jumlahNasabah = filteredList.size,
                    totalPiutang = totalPiutang
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Filter Chips
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 20 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filterOptions) { filter ->
                        ModernFilterChip(
                            label = filter,
                            isSelected = selectedFilter == filter,
                            color = getKategoriColor(filter),
                            onClick = { selectedFilter = filter }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "Cari nama panggilan atau nama KTP...",
                        color = PimpinanColors.getTextSecondary(isDark)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = PimpinanColors.getTextSecondary(isDark)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Hapus pencarian",
                                tint = PimpinanColors.getTextSecondary(isDark)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PimpinanColors.danger,
                    unfocusedBorderColor = PimpinanColors.getBorder(isDark),
                    focusedContainerColor = if (isDark) PimpinanColors.darkCard else Color.White,
                    unfocusedContainerColor = if (isDark) PimpinanColors.darkCard else Color.White,
                    cursorColor = PimpinanColors.danger,
                    focusedTextColor = txtColor,
                    unfocusedTextColor = txtColor
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { })
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Loading / Error / Content
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PimpinanColors.danger)
                    }
                }
                errorMessage != null -> {
                    ModernBermasalahErrorState(
                        message = errorMessage!!,
                        onRetry = { coroutineScope.launch { loadData() } }
                    )
                }
                filteredList.isEmpty() -> {
                    ModernEmptyBermasalahState(
                        message = when {
                            searchQuery.isNotBlank() ->
                                "Tidak ditemukan nasabah dengan kata kunci \"$searchQuery\""
                            selectedFilter != "Semua" ->
                                "Tidak ada nasabah dengan kategori $selectedFilter"
                            else ->
                                "Tidak ada nasabah bermasalah"
                        },
                        isDark = isDark
                    )
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredList, key = { it.pelangganId }) { item ->
                            ModernNasabahBermasalahCard(item, isDark, txtColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernBermasalahTopBar(
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
                    Brush.linearGradient(PimpinanColors.dangerGradient),
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
                            text = "Nasabah Bermasalah",
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
private fun ModernBermasalahSummaryCard(
    jumlahNasabah: Int,
    totalPiutang: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = PimpinanColors.danger.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(PimpinanColors.dangerGradient))
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
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Total Piutang Bermasalah",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatRupiah(totalPiutang),
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernFilterChip(
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
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.15f),
            selectedLabelColor = color
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun ModernNasabahBermasalahCard(
    item: NasabahBermasalahItem,
    isDark: Boolean,
    txtColor: Color
) {
    val kategoriColor = getKategoriColor(item.kategori)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            ),
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
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                kategoriColor.copy(alpha = 0.1f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = kategoriColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.namaPanggilan.ifBlank { item.namaKtp },
                            fontWeight = FontWeight.Bold,
                            color = txtColor,
                            fontSize = 15.sp
                        )
                        if (item.wilayah.isNotBlank()) {
                            Text(
                                text = item.wilayah,
                                fontSize = 12.sp,
                                color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                            )
                        }
                    }
                }

                // Badge Kategori
                Surface(
                    color = kategoriColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = item.kategori.uppercase(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = kategoriColor
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Info tunggakan
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Piutang",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark) // ✅ UBAH: Dinamis
                    )
                    Text(
                        text = formatRupiah(item.totalPiutang),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PimpinanColors.danger
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Hari Tunggakan",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark) // ✅ UBAH: Dinamis
                    )
                    Text(
                        text = "${item.hariTunggakan} hari",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = kategoriColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Total Pinjaman",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark) // ✅ UBAH: Dinamis
                    )
                    Text(
                        text = formatRupiah(item.totalPinjaman),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor
                    )
                }
            }

            // Info tambahan
            if (item.lastPaymentDate.isNotBlank() || item.adminName.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = PimpinanColors.getBorder(isDark)) // ✅ UBAH: Dinamis
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (item.lastPaymentDate.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CalendarToday,
                                contentDescription = null,
                                tint = PimpinanColors.getTextMuted(isDark), // ✅ UBAH: Dinamis
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Bayar terakhir: ${item.lastPaymentDate}",
                                fontSize = 11.sp,
                                color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                            )
                        }
                    }
                    if (item.adminName.isNotBlank()) {
                        Text(
                            text = item.adminName,
                            fontSize = 11.sp,
                            color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernEmptyBermasalahState(
    message: String,
    isDark: Boolean = false // ✅ BARU: Parameter isDark
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
                text = message,
                color = PimpinanColors.getTextSecondary(isDark), // ✅ UBAH: Dinamis
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ModernBermasalahErrorState(
    message: String,
    onRetry: () -> Unit
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
                        PimpinanColors.danger.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = null,
                    tint = PimpinanColors.danger,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = PimpinanColors.danger,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PimpinanColors.danger
                )
            ) {
                Text("Coba Lagi")
            }
        }
    }
}

private fun getKategoriColor(kategori: String): Color {
    return when (kategori.lowercase()) {
        "ringan" -> Color(0xFFF59E0B) // Amber/Warning
        "sedang" -> Color(0xFFF97316) // Orange
        "berat" -> Color(0xFFEF4444)  // Red
        "macet" -> Color(0xFF991B1B)  // Dark Red
        "semua" -> PimpinanColors.danger
        else -> PimpinanColors.danger
    }
}