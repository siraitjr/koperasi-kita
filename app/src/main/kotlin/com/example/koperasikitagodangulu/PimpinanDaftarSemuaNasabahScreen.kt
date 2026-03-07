package com.example.koperasikitagodangulu

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.utils.RekeningKoranLinkHelper
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// =========================================================================
// DATA CLASS - Index Nasabah (Data Minimal untuk List)
// =========================================================================
data class NasabahIndexItem(
    val id: String = "",
    val nama: String = "",
    val namaKtp: String = "",
    val status: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val wilayah: String = "",
    val besarPinjaman: Int = 0,
    val totalPelunasan: Int = 0,
    val sisaHutang: Int = 0,
    val simpanan: Int = 0,
    val isLunas: Boolean = false,
    val isMenungguPencairan: Boolean = false,
    val tanggalDaftar: String = "",
    val pinjamanKe: Int = 1,
    val lastUpdated: Long = 0
)

// =========================================================================
// FILTER OPTIONS
// =========================================================================
enum class NasabahFilter(val label: String, val icon: ImageVector) {
    SEMUA("Semua", Icons.Rounded.People),
    AKTIF("Aktif", Icons.Rounded.PlayCircle),
    LUNAS("Lunas", Icons.Rounded.CheckCircle),
    MENUNGGU_PENCAIRAN("Menunggu Pencairan", Icons.Rounded.HourglassTop)
}

enum class SortOption(val label: String) {
    NAMA_ASC("Nama A-Z"),
    NAMA_DESC("Nama Z-A"),
    PINJAMAN_DESC("Pinjaman Terbesar"),
    PINJAMAN_ASC("Pinjaman Terkecil"),
    SISA_HUTANG_DESC("Sisa Hutang Terbesar"),
    SISA_HUTANG_ASC("Sisa Hutang Terkecil"),
    TERBARU("Terbaru"),
    TERLAMA("Terlama")
}

// =========================================================================
// MAIN SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanDaftarSemuaNasabahScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // Dark mode state
    val isDark by viewModel.isDarkMode
//    val bgColor = PimpinanColors.getBackground(isDark)
    val cardColor = PimpinanColors.getCard(isDark)
    val txtColor = PimpinanColors.getTextPrimary(isDark)
    val subtitleColor = PimpinanColors.getTextSecondary(isDark)
    val mutedColor = PimpinanColors.getTextMuted(isDark)
    val borderColor = PimpinanColors.getBorder(isDark)

    // Cabang
    val cabangId by viewModel.currentUserCabang.collectAsState()

    // Ambil adminSummary untuk PDL list
    val adminSummary by viewModel.adminSummary.collectAsState()

    // Data states
    var nasabahList by remember { mutableStateOf<List<NasabahIndexItem>>(emptyList()) }
    var filteredList by remember { mutableStateOf<List<NasabahIndexItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    // Search & Filter states
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(NasabahFilter.SEMUA) }
    var selectedSort by remember { mutableStateOf(SortOption.NAMA_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }

    // State untuk filter PDL
    var showPdlDialog by remember { mutableStateOf(false) }
    var selectedPdlUid by remember { mutableStateOf<String?>(null) }
    var selectedPdlName by remember { mutableStateOf<String?>(null) }
    var pdlList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // Scroll state
    val listState = rememberLazyListState()

    // ✅ Set status bar & navigation bar sesuai tema dashboard
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // =========================================================================
    // LOAD DATA FUNCTION
    // =========================================================================
    suspend fun loadData() {
        if (cabangId == null) {
            errorMessage = "Cabang tidak ditemukan"
            isLoading = false
            return
        }

        isLoading = true
        errorMessage = null

        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference

            val snapshot = database.child("summary")
                .child("nasabahIndex")
                .child(cabangId!!)
                .get().await()

            if (!snapshot.exists()) {
                nasabahList = emptyList()
                filteredList = emptyList()
                Log.d("DaftarSemuaNasabah", "⚠️ No index data found for cabang: $cabangId")
            } else {
                val list = mutableListOf<NasabahIndexItem>()
                snapshot.children.forEach { child ->
                    try {
                        val item = NasabahIndexItem(
                            id = child.key ?: "",
                            nama = child.child("nama").getValue(String::class.java) ?: "",
                            namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                            status = child.child("status").getValue(String::class.java) ?: "",
                            adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                            adminName = child.child("adminName").getValue(String::class.java) ?: "",
                            wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
                            besarPinjaman = child.child("besarPinjaman").getValue(Int::class.java) ?: 0,
                            totalPelunasan = child.child("totalPelunasan").getValue(Int::class.java) ?: 0,
                            sisaHutang = child.child("sisaHutang").getValue(Int::class.java) ?: 0,
                            simpanan = child.child("simpanan").getValue(Int::class.java) ?: 0,
                            isLunas = child.child("isLunas").getValue(Boolean::class.java) ?: false,
                            isMenungguPencairan = child.child("isMenungguPencairan").getValue(Boolean::class.java) ?: false,
                            tanggalDaftar = child.child("tanggalDaftar").getValue(String::class.java) ?: "",
                            pinjamanKe = child.child("pinjamanKe").getValue(Int::class.java) ?: 1,
                            lastUpdated = child.child("lastUpdated").getValue(Long::class.java) ?: 0
                        )
                        list.add(item)
                    } catch (e: Exception) {
                        Log.e("DaftarSemuaNasabah", "Error parsing item: ${e.message}")
                    }
                }

                nasabahList = list
                Log.d("DaftarSemuaNasabah", "✅ Loaded ${list.size} nasabah from index")
            }

        } catch (e: Exception) {
            Log.e("DaftarSemuaNasabah", "❌ Error loading: ${e.message}")
            errorMessage = "Gagal memuat data: ${e.message}"
        }

        isLoading = false
        isVisible = true
    }

    // =========================================================================
    // APPLY FILTER & SORT FUNCTION
    // =========================================================================
    fun applyFilterAndSort() {
        var result = nasabahList

        // Apply search
        if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            result = result.filter { item ->
                item.nama.lowercase().contains(query) ||
                        item.namaKtp.lowercase().contains(query) ||
                        item.wilayah.lowercase().contains(query) ||
                        item.adminName.lowercase().contains(query)
            }
        }

        // Apply filter
        // ✅ PERBAIKAN: Nasabah Aktif = tidak lunas DAN tidak menunggu pencairan
        result = when (selectedFilter) {
            NasabahFilter.AKTIF -> result.filter { !it.isLunas && !it.isMenungguPencairan }
            NasabahFilter.LUNAS -> result.filter { it.isLunas }
            NasabahFilter.MENUNGGU_PENCAIRAN -> result.filter { it.isMenungguPencairan }
            NasabahFilter.SEMUA -> result
        }

        // Apply filter PDL
        if (selectedPdlUid != null) {
            result = result.filter { it.adminUid == selectedPdlUid }
        }

        // Apply sort
        result = when (selectedSort) {
            SortOption.NAMA_ASC -> result.sortedBy { it.nama.lowercase() }
            SortOption.NAMA_DESC -> result.sortedByDescending { it.nama.lowercase() }
            SortOption.PINJAMAN_DESC -> result.sortedByDescending { it.besarPinjaman }
            SortOption.PINJAMAN_ASC -> result.sortedBy { it.besarPinjaman }
            SortOption.SISA_HUTANG_DESC -> result.sortedByDescending { it.sisaHutang }
            SortOption.SISA_HUTANG_ASC -> result.sortedBy { it.sisaHutang }
            SortOption.TERBARU -> result.sortedByDescending { it.lastUpdated }
            SortOption.TERLAMA -> result.sortedBy { it.lastUpdated }
        }

        filteredList = result
    }

    // =========================================================================
    // OPEN REKENING KORAN
    // =========================================================================
    fun openRekeningKoran(item: NasabahIndexItem) {
        try {
            val url = RekeningKoranLinkHelper.generateLink(item.adminUid, item.id)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("DaftarSemuaNasabah", "Error opening RK: ${e.message}")
        }
    }

    // =========================================================================
    // EFFECTS
    // =========================================================================
    LaunchedEffect(cabangId) {
        loadData()
    }

    LaunchedEffect(searchQuery, selectedFilter, selectedSort, nasabahList, selectedPdlUid) {
        applyFilterAndSort()
    }

    // Extract PDL list dari adminSummary
    LaunchedEffect(adminSummary) {
        pdlList = adminSummary
            .filter { it.adminId.isNotBlank() && it.adminName.isNotBlank() }
            .map { it.adminId to it.adminName }
            .sortedBy { it.second.lowercase() }
    }

    // =========================================================================
    // CALCULATE COUNTS - Dari nasabahList (setelah backfill, data sudah akurat)
    // =========================================================================
    val countSemua = nasabahList.size
    val countAktif = nasabahList.count { !it.isLunas && !it.isMenungguPencairan }
    val countLunas = nasabahList.count { it.isLunas }
    val countMenungguPencairan = nasabahList.count { it.isMenungguPencairan }

    // =========================================================================
    // CALCULATE SUMMARY - Dari filteredList (yang sedang ditampilkan)
    // =========================================================================
    val totalNasabahDisplayed = filteredList.size
    val nasabahAktifDisplayed = filteredList.count { !it.isLunas && !it.isMenungguPencairan }
    val nasabahLunasDisplayed = filteredList.count { it.isLunas }
    val nasabahMenungguPencairanDisplayed = filteredList.count { it.isMenungguPencairan }

    // ✅ BARU: Hitung saldo berdasarkan filter yang dipilih
    val totalSaldoDisplayed = when (selectedFilter) {
        NasabahFilter.SEMUA -> 0L  // Tidak tampilkan saldo untuk filter Semua
        NasabahFilter.AKTIF -> filteredList
            .filter { !it.isLunas && !it.isMenungguPencairan }
            .sumOf { it.sisaHutang.toLong() }
        NasabahFilter.LUNAS -> 0L  // Nasabah lunas saldo = 0
        NasabahFilter.MENUNGGU_PENCAIRAN -> filteredList
            .filter { it.isMenungguPencairan }
            .sumOf { it.sisaHutang.toLong() }
    }

    // ✅ BARU: Total simpanan untuk filter Menunggu Pencairan
    val totalSimpananDisplayed = when (selectedFilter) {
        NasabahFilter.MENUNGGU_PENCAIRAN -> filteredList
            .filter { it.isMenungguPencairan }
            .sumOf { it.simpanan.toLong() }
        else -> 0L
    }

    // Cek apakah dalam mode tanpa filter
    val isUnfilteredMode = selectedFilter == NasabahFilter.SEMUA &&
            searchQuery.isBlank() &&
            selectedPdlUid == null

    // =========================================================================
    // UI
    // =========================================================================
    Scaffold(
        containerColor = bgColor,
        topBar = {
            DaftarNasabahTopBar(
                onBack = { navController.popBackStack() },
                onRefresh = { coroutineScope.launch { loadData() } },
                totalNasabah = nasabahList.size,
                isDark = isDark
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(300)
                )
            ) {
                SearchBarSection(
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    onClear = {
                        searchQuery = ""
                        focusManager.clearFocus()
                    },
                    selectedSort = selectedSort,
                    showSortMenu = showSortMenu,
                    onSortMenuToggle = { showSortMenu = it },
                    onSortSelected = {
                        selectedSort = it
                        showSortMenu = false
                    },
                    onPdlClick = { showPdlDialog = true },
                    selectedPdlName = selectedPdlName,
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            // Filter Chips
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { -10 },
                    animationSpec = tween(300, delayMillis = 100)
                )
            ) {
                FilterChipsSection(
                    selectedFilter = selectedFilter,
                    onFilterSelected = { selectedFilter = it },
                    totalSemua = countSemua,
                    totalAktif = countAktif,
                    totalLunas = countLunas,
                    totalMenungguPencairan = countMenungguPencairan,  // ✅ BARU
                    isDark = isDark
                )
            }

            // Summary Card
            AnimatedVisibility(
                visible = isVisible && !isLoading,
                enter = fadeIn(tween(300, delayMillis = 150)) + slideInVertically(
                    initialOffsetY = { -10 },
                    animationSpec = tween(300, delayMillis = 150)
                )
            ) {
                SummaryCardSection(
                    totalNasabah = totalNasabahDisplayed,
                    nasabahAktif = if (isUnfilteredMode) countAktif else nasabahAktifDisplayed,
                    nasabahLunas = if (isUnfilteredMode) countLunas else nasabahLunasDisplayed,
                    nasabahMenungguPencairan = if (isUnfilteredMode) countMenungguPencairan else nasabahMenungguPencairanDisplayed,
                    totalSisaHutang = totalSaldoDisplayed,
                    totalSimpanan = totalSimpananDisplayed,  // ✅ BARU
                    selectedFilter = selectedFilter,  // ✅ BARU: untuk kondisional tampilan
                    isDark = isDark,
                    cardColor = cardColor
                )
            }

            // Content
            when {
                isLoading -> {
                    LoadingState(isDark = isDark)
                }
                errorMessage != null -> {
                    ErrorState(
                        message = errorMessage!!,
                        onRetry = { coroutineScope.launch { loadData() } },
                        isDark = isDark
                    )
                }
                filteredList.isEmpty() -> {
                    EmptyState(
                        searchQuery = searchQuery,
                        selectedFilter = selectedFilter,
                        isDark = isDark
                    )
                }
                else -> {
                    // Nasabah List
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(
                            items = filteredList,
                            key = { it.id }
                        ) { item ->
                            NasabahItemCard(
                                item = item,
                                onClick = { openRekeningKoran(item) },
                                isDark = isDark,
                                cardColor = cardColor,
                                txtColor = txtColor,
                                subtitleColor = subtitleColor,
                                mutedColor = mutedColor
                            )
                        }
                    }
                }
            }
        }

        // PDL Dialog
        if (showPdlDialog) {
            PdlFilterDialog(
                pdlList = pdlList,
                selectedPdlUid = selectedPdlUid,
                onPdlSelected = { uid, name ->
                    selectedPdlUid = uid
                    selectedPdlName = name
                    showPdlDialog = false
                },
                onClearFilter = {
                    selectedPdlUid = null
                    selectedPdlName = null
                    showPdlDialog = false
                },
                onDismiss = { showPdlDialog = false },
                isDark = isDark,
                cardColor = cardColor,
                txtColor = txtColor
            )
        }
    }
}

// =========================================================================
// TOP BAR
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaftarNasabahTopBar(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    totalNasabah: Int,
    isDark: Boolean
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "Daftar Semua Nasabah",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = PimpinanColors.getTextPrimary(isDark)
                )
                Text(
                    text = "$totalNasabah nasabah terdaftar",
                    fontSize = 12.sp,
                    color = PimpinanColors.getTextSecondary(isDark)
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Kembali",
                    tint = PimpinanColors.getTextPrimary(isDark)
                )
            }
        },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = PimpinanColors.primary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = PimpinanColors.getBackground(isDark)
        )
    )
}

// =========================================================================
// SEARCH BAR SECTION
// =========================================================================
@Composable
private fun SearchBarSection(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClear: () -> Unit,
    selectedSort: SortOption,
    showSortMenu: Boolean,
    onSortMenuToggle: (Boolean) -> Unit,
    onSortSelected: (SortOption) -> Unit,
    onPdlClick: () -> Unit,
    selectedPdlName: String?,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = {
                Text(
                    "Cari nama, wilayah, PDL...",
                    color = subtitleColor
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = subtitleColor
                )
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear",
                            tint = subtitleColor
                        )
                    }
                }
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PimpinanColors.primary,
                unfocusedBorderColor = borderColor,
                focusedContainerColor = cardColor,
                unfocusedContainerColor = cardColor,
                cursorColor = PimpinanColors.primary,
                focusedTextColor = txtColor,
                unfocusedTextColor = txtColor
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onClear() })
        )

        // Sort/Filter Button
        Box {
            Surface(
                onClick = { onSortMenuToggle(true) },
                shape = RoundedCornerShape(12.dp),
                color = cardColor,
                border = ButtonDefaults.outlinedButtonBorder
            ) {
                Icon(
                    imageVector = Icons.Rounded.FilterList,
                    contentDescription = "Sort",
                    tint = PimpinanColors.primary,
                    modifier = Modifier.padding(12.dp)
                )
            }

            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { onSortMenuToggle(false) }
            ) {
                SortOption.values().forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (selectedSort == option) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = PimpinanColors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(18.dp))
                                }
                                Text(option.label)
                            }
                        },
                        onClick = { onSortSelected(option) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // PDL Filter Option
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = PimpinanColors.info,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (selectedPdlName != null) "PDL: $selectedPdlName" else "Filter PDL",
                                color = if (selectedPdlName != null) PimpinanColors.info else Color.Unspecified
                            )
                        }
                    },
                    onClick = {
                        onSortMenuToggle(false)
                        onPdlClick()
                    }
                )
            }
        }
    }
}

// =========================================================================
// FILTER CHIPS SECTION
// =========================================================================
@Composable
private fun FilterChipsSection(
    selectedFilter: NasabahFilter,
    onFilterSelected: (NasabahFilter) -> Unit,
    totalSemua: Int,
    totalAktif: Int,
    totalLunas: Int,
    totalMenungguPencairan: Int,
    isDark: Boolean
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(NasabahFilter.values().toList()) { filter ->
            val count = when (filter) {
                NasabahFilter.SEMUA -> totalSemua
                NasabahFilter.AKTIF -> totalAktif
                NasabahFilter.LUNAS -> totalLunas
                NasabahFilter.MENUNGGU_PENCAIRAN -> totalMenungguPencairan  // ✅ BARU
            }
            val isSelected = selectedFilter == filter

            val chipColor = when {
                !isSelected -> PimpinanColors.getCard(isDark)
                filter == NasabahFilter.AKTIF -> PimpinanColors.info
                filter == NasabahFilter.LUNAS -> PimpinanColors.success
                filter == NasabahFilter.MENUNGGU_PENCAIRAN -> PimpinanColors.warning  // ✅ BARU
                else -> PimpinanColors.primary
            }

            val textColor = when {
                !isSelected -> PimpinanColors.getTextSecondary(isDark)
                else -> Color.White
            }

            Surface(
                onClick = { onFilterSelected(filter) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) chipColor else chipColor.copy(alpha = 0.1f),
                border = if (!isSelected) ButtonDefaults.outlinedButtonBorder else null
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = filter.icon,
                        contentDescription = null,
                        tint = if (isSelected) Color.White else chipColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${filter.label} ($count)",
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor
                    )
                }
            }
        }
    }
}

// =========================================================================
// SUMMARY CARD SECTION
// =========================================================================
@Composable
private fun SummaryCardSection(
    totalNasabah: Int,
    nasabahAktif: Int,
    nasabahLunas: Int,
    nasabahMenungguPencairan: Int,
    totalSisaHutang: Long,
    totalSimpanan: Long,  // ✅ BARU
    selectedFilter: NasabahFilter,  // ✅ BARU
    isDark: Boolean,
    cardColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Selalu tampilkan: Ditampilkan
            SummaryItem(
                label = "Ditampilkan",
                value = totalNasabah.toString(),
                color = PimpinanColors.primary,
                isDark = isDark
            )

            // Selalu tampilkan: Aktif
            SummaryItem(
                label = "Aktif",
                value = nasabahAktif.toString(),
                color = PimpinanColors.info,
                isDark = isDark
            )

            // Selalu tampilkan: Lunas
            SummaryItem(
                label = "Lunas",
                value = nasabahLunas.toString(),
                color = PimpinanColors.success,
                isDark = isDark
            )

            // Selalu tampilkan: Pencairan Simpanan
            SummaryItem(
                label = "Pencairan Simpanan",
                value = nasabahMenungguPencairan.toString(),
                color = PimpinanColors.warning,
                isDark = isDark
            )

            // ✅ KONDISIONAL: Total Saldo berdasarkan filter
            when (selectedFilter) {
                NasabahFilter.SEMUA -> {
                    // Tidak tampilkan Total Saldo untuk filter Semua
                }
                NasabahFilter.AKTIF -> {
                    SummaryItem(
                        label = "Total Saldo",
                        value = formatRupiah(totalSisaHutang.toInt()),
                        color = PimpinanColors.danger,
                        isDark = isDark,
                        isLarge = true
                    )
                }
                NasabahFilter.LUNAS -> {
                    SummaryItem(
                        label = "Total Saldo",
                        value = "Rp 0",  // Lunas selalu 0
                        color = PimpinanColors.success,
                        isDark = isDark,
                        isLarge = true
                    )
                }
                NasabahFilter.MENUNGGU_PENCAIRAN -> {
                    // Tampilkan Total Saldo dan Total Simpanan
                    SummaryItem(
                        label = "Total Saldo",
                        value = formatRupiah(totalSisaHutang.toInt()),
                        color = PimpinanColors.danger,
                        isDark = isDark,
                        isLarge = true
                    )
                    SummaryItem(
                        label = "Total Simpanan",
                        value = formatRupiah(totalSimpanan.toInt()),
                        color = PimpinanColors.warning,
                        isDark = isDark,
                        isLarge = true
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryItem(
    label: String,
    value: String,
    color: Color,
    isDark: Boolean,
    isLarge: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = PimpinanColors.getTextMuted(isDark)
        )
        Text(
            text = value,
            fontSize = if (isLarge) 11.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// =========================================================================
// NASABAH ITEM CARD
// =========================================================================
@Composable
private fun NasabahItemCard(
    item: NasabahIndexItem,
    onClick: () -> Unit,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color,
    mutedColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = PimpinanColors.primary.copy(alpha = 0.1f)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row
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
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(
                                    when {
                                        item.isLunas -> PimpinanColors.successGradient
                                        item.isMenungguPencairan -> PimpinanColors.warningGradient
                                        else -> PimpinanColors.primaryGradient
                                    }
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.nama.take(1).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }

                    Column {
                        Text(
                            text = item.nama.uppercase(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = txtColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LocationOn,
                                contentDescription = null,
                                tint = subtitleColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = item.wilayah.ifBlank { "-" },
                                fontSize = 12.sp,
                                color = subtitleColor
                            )
                        }
                    }
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        item.isLunas -> PimpinanColors.success.copy(alpha = 0.1f)
                        item.isMenungguPencairan -> PimpinanColors.warning.copy(alpha = 0.1f)
                        item.pinjamanKe > 1 -> PimpinanColors.info.copy(alpha = 0.1f)
                        else -> PimpinanColors.primary.copy(alpha = 0.1f)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                item.isLunas -> Icons.Rounded.CheckCircle
                                item.isMenungguPencairan -> Icons.Rounded.Schedule
                                item.pinjamanKe > 1 -> Icons.Rounded.Autorenew
                                else -> Icons.Rounded.PlayCircle
                            },
                            contentDescription = null,
                            tint = when {
                                item.isLunas -> PimpinanColors.success
                                item.isMenungguPencairan -> PimpinanColors.warning
                                item.pinjamanKe > 1 -> PimpinanColors.info
                                else -> PimpinanColors.primary
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = when {
                                item.isLunas -> "LUNAS"
                                item.isMenungguPencairan -> "PENCAIRAN"
                                item.pinjamanKe > 1 -> "Pinjaman ke-${item.pinjamanKe}"
                                else -> "AKTIF"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                item.isLunas -> PimpinanColors.success
                                item.isMenungguPencairan -> PimpinanColors.warning
                                item.pinjamanKe > 1 -> PimpinanColors.info
                                else -> PimpinanColors.primary
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Pinjaman",
                        fontSize = 10.sp,
                        color = mutedColor
                    )
                    Text(
                        text = formatRupiah(item.besarPinjaman),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Sisa Hutang",
                        fontSize = 10.sp,
                        color = mutedColor
                    )
                    Text(
                        text = formatRupiah(item.sisaHutang),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.sisaHutang > 0) PimpinanColors.danger else PimpinanColors.success
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = PimpinanColors.getBorder(isDark)
            )

            // Footer Row
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
                        tint = mutedColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "PDL: ${item.adminName.ifBlank { "-" }}",
                        fontSize = 11.sp,
                        color = mutedColor
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Lihat Detail",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = PimpinanColors.primary
                    )
                    Icon(
                        imageVector = Icons.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = PimpinanColors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// =========================================================================
// PDL FILTER DIALOG
// =========================================================================
@Composable
private fun PdlFilterDialog(
    pdlList: List<Pair<String, String>>,
    selectedPdlUid: String?,
    onPdlSelected: (String, String) -> Unit,
    onClearFilter: () -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Filter berdasarkan PDL",
                fontWeight = FontWeight.Bold,
                color = txtColor
            )
        },
        text = {
            Column {
                // Clear Filter Option
                if (selectedPdlUid != null) {
                    Surface(
                        onClick = onClearFilter,
                        shape = RoundedCornerShape(12.dp),
                        color = PimpinanColors.danger.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                tint = PimpinanColors.danger,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Hapus Filter PDL",
                                color = PimpinanColors.danger,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // PDL List
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(pdlList) { (adminUid, adminName) ->
                        val isSelected = selectedPdlUid == adminUid

                        Surface(
                            onClick = { onPdlSelected(adminUid, adminName) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) PimpinanColors.primary.copy(alpha = 0.1f)
                            else Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Avatar
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (isSelected) PimpinanColors.primary
                                            else PimpinanColors.primary.copy(alpha = 0.1f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = adminName.take(2).uppercase(),
                                        color = if (isSelected) Color.White else PimpinanColors.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }

                                Text(
                                    text = adminName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected)
                                        PimpinanColors.primary
                                    else
                                        txtColor,
                                    modifier = Modifier.weight(1f)
                                )

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = PimpinanColors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup", color = PimpinanColors.primary)
            }
        },
        containerColor = cardColor
    )
}

// =========================================================================
// LOADING STATE
// =========================================================================
@Composable
private fun LoadingState(isDark: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = PimpinanColors.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Memuat data nasabah...",
                color = PimpinanColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )
        }
    }
}

// =========================================================================
// ERROR STATE
// =========================================================================
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
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
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = PimpinanColors.danger,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PimpinanColors.danger
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Coba Lagi")
            }
        }
    }
}

// =========================================================================
// EMPTY STATE
// =========================================================================
@Composable
private fun EmptyState(
    searchQuery: String,
    selectedFilter: NasabahFilter,
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
                    .background(
                        PimpinanColors.info.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (searchQuery.isNotBlank()) Icons.Rounded.SearchOff
                    else Icons.Rounded.PeopleOutline,
                    contentDescription = null,
                    tint = PimpinanColors.info,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when {
                    searchQuery.isNotBlank() -> "Tidak ditemukan nasabah dengan kata kunci \"$searchQuery\""
                    selectedFilter != NasabahFilter.SEMUA -> "Tidak ada nasabah ${selectedFilter.label.lowercase()}"
                    else -> "Belum ada data nasabah"
                },
                color = PimpinanColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )

            if (searchQuery.isNotBlank() || selectedFilter != NasabahFilter.SEMUA) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Coba ubah filter atau kata kunci pencarian",
                    color = PimpinanColors.getTextMuted(isDark),
                    fontSize = 12.sp
                )
            }
        }
    }
}