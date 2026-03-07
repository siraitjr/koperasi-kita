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
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// =========================================================================
// KOORDINATOR DAFTAR SEMUA NASABAH SCREEN
// =========================================================================
// Screen untuk Koordinator melihat seluruh nasabah dari semua cabang
// atau per cabang dengan filter yang sama seperti KoordinatorReportsScreen
// =========================================================================

// =========================================================================
// DATA CLASS - Reuse dari NasabahIndexItem
// =========================================================================
data class KorNasabahIndexItem(
    val id: String = "",
    val nama: String = "",
    val namaKtp: String = "",
    val status: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val wilayah: String = "",
    val cabangId: String = "",  // ✅ TAMBAHAN: untuk multi-cabang
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
// FILTER OPTIONS - Sama dengan PimpinanDaftarSemuaNasabahScreen
// =========================================================================
enum class KorNasabahFilter(val label: String, val icon: ImageVector) {
    SEMUA("Semua", Icons.Rounded.People),
    AKTIF("Aktif", Icons.Rounded.PlayCircle),
    LUNAS("Lunas", Icons.Rounded.CheckCircle),
    MENUNGGU_PENCAIRAN("Menunggu Pencairan", Icons.Rounded.HourglassTop)
}

enum class KorSortOption(val label: String) {
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
fun KoordinatorDaftarSemuaNasabahScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // Dark mode state
    val isDark by viewModel.isDarkMode
    val cardColor = KoordinatorColors.getCard(isDark)
    val txtColor = KoordinatorColors.getTextPrimary(isDark)
    val subtitleColor = KoordinatorColors.getTextSecondary(isDark)
    val mutedColor = KoordinatorColors.getTextMuted(isDark)
    val borderColor = KoordinatorColors.getBorder(isDark)
    val systemUiController = rememberSystemUiController()
    val bgColor = KoordinatorColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // =========================================================================
    // ✅ Gunakan filter cabang dari ViewModel (SAMA seperti KoordinatorReportsScreen)
    // =========================================================================
    val selectedCabangId by viewModel.pengawasSelectedCabangId.collectAsState()
    val cabangOptions by viewModel.pengawasCabangOptions.collectAsState()

    // Data states
    var nasabahList by remember { mutableStateOf<List<KorNasabahIndexItem>>(emptyList()) }
    var filteredList by remember { mutableStateOf<List<KorNasabahIndexItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    // Search & Filter states
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(KorNasabahFilter.SEMUA) }
    var selectedSort by remember { mutableStateOf(KorSortOption.NAMA_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }

    // ✅ BARU: State untuk filter PDL
    var showPdlDialog by remember { mutableStateOf(false) }
    var selectedPdlUid by remember { mutableStateOf<String?>(null) }
    var selectedPdlName by remember { mutableStateOf<String?>(null) }
    var pdlList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // Scroll state
    val listState = rememberLazyListState()

    // =========================================================================
    // HELPER: Parse snapshot ke data class (harus di atas loadData)
    // =========================================================================
    fun parseNasabahIndex(child: com.google.firebase.database.DataSnapshot, cabangIdParam: String): KorNasabahIndexItem {
        return KorNasabahIndexItem(
            id = child.key ?: "",
            nama = child.child("nama").getValue(String::class.java) ?: "",
            namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
            status = child.child("status").getValue(String::class.java) ?: "",
            adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
            adminName = child.child("adminName").getValue(String::class.java) ?: "",
            wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
            cabangId = cabangIdParam,
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
    }

    // =========================================================================
    // LOAD DATA FUNCTION - Load dari nasabahIndex
    // =========================================================================
    suspend fun loadData() {
        isLoading = true
        errorMessage = null

        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference

            val list = mutableListOf<KorNasabahIndexItem>()

            if (selectedCabangId == null) {
                // ✅ LOAD SEMUA CABANG - Loop per cabang untuk efisiensi
                Log.d("KorDaftarSemuaNasabah", "🔄 Loading ALL cabang...")

                // Ambil list cabang dari options (skip "Semua Cabang" yang null)
                val cabangIds = cabangOptions
                    .filter { it.first != null }
                    .map { it.first!! }

                for (cabangId in cabangIds) {
                    try {
                        val snapshot = database.child("summary")
                            .child("nasabahIndex")
                            .child(cabangId)
                            .get().await()

                        if (snapshot.exists()) {
                            snapshot.children.forEach { child ->
                                try {
                                    val item = parseNasabahIndex(child, cabangId)
                                    list.add(item)
                                } catch (e: Exception) {
                                    Log.e("KorDaftarSemuaNasabah", "Error parsing item: ${e.message}")
                                }
                            }
                            Log.d("KorDaftarSemuaNasabah", "✅ Loaded ${snapshot.childrenCount} from cabang: $cabangId")
                        }
                    } catch (e: Exception) {
                        Log.e("KorDaftarSemuaNasabah", "Error loading cabang $cabangId: ${e.message}")
                    }
                }

            } else {
                // ✅ LOAD SATU CABANG - Lebih hemat
                Log.d("KorDaftarSemuaNasabah", "🔄 Loading cabang: $selectedCabangId")

                val snapshot = database.child("summary")
                    .child("nasabahIndex")
                    .child(selectedCabangId!!)
                    .get().await()

                if (snapshot.exists()) {
                    snapshot.children.forEach { child ->
                        try {
                            val item = parseNasabahIndex(child, selectedCabangId!!)
                            list.add(item)
                        } catch (e: Exception) {
                            Log.e("KorDaftarSemuaNasabah", "Error parsing item: ${e.message}")
                        }
                    }
                }
            }

            nasabahList = list
            Log.d("KorDaftarSemuaNasabah", "✅ Total loaded: ${list.size} nasabah")

        } catch (e: Exception) {
            Log.e("KorDaftarSemuaNasabah", "❌ Error loading: ${e.message}")
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
        result = when (selectedFilter) {
            KorNasabahFilter.AKTIF -> result.filter { !it.isLunas && !it.isMenungguPencairan }
            KorNasabahFilter.LUNAS -> result.filter { it.isLunas }
            KorNasabahFilter.MENUNGGU_PENCAIRAN -> result.filter { it.isMenungguPencairan }
            KorNasabahFilter.SEMUA -> result
        }

        // ✅ BARU: Apply filter PDL
        if (selectedPdlUid != null) {
            result = result.filter { it.adminUid == selectedPdlUid }
        }

        // Apply sort
        result = when (selectedSort) {
            KorSortOption.NAMA_ASC -> result.sortedBy { it.nama.lowercase() }
            KorSortOption.NAMA_DESC -> result.sortedByDescending { it.nama.lowercase() }
            KorSortOption.PINJAMAN_DESC -> result.sortedByDescending { it.besarPinjaman }
            KorSortOption.PINJAMAN_ASC -> result.sortedBy { it.besarPinjaman }
            KorSortOption.SISA_HUTANG_DESC -> result.sortedByDescending { it.sisaHutang }
            KorSortOption.SISA_HUTANG_ASC -> result.sortedBy { it.sisaHutang }
            KorSortOption.TERBARU -> result.sortedByDescending { it.lastUpdated }
            KorSortOption.TERLAMA -> result.sortedBy { it.lastUpdated }
        }

        filteredList = result
    }

    // =========================================================================
    // OPEN REKENING KORAN
    // =========================================================================
    fun openRekeningKoran(item: KorNasabahIndexItem) {
        try {
            val url = RekeningKoranLinkHelper.generateLink(item.adminUid, item.id)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("KorDaftarSemuaNasabah", "Error opening RK: ${e.message}")
        }
    }

    // =========================================================================
    // EFFECTS
    // =========================================================================
    // Load data saat cabang berubah
    LaunchedEffect(selectedCabangId, cabangOptions) {
        if (cabangOptions.size > 1) { // Pastikan cabang sudah ter-load
            loadData()
        }
    }

    LaunchedEffect(searchQuery, selectedFilter, selectedSort, nasabahList, selectedPdlUid) {
        applyFilterAndSort()
    }

    // ✅ BARU: Extract PDL list dari nasabahList (unique PDL berdasarkan cabang yang dipilih)
    LaunchedEffect(nasabahList) {
        pdlList = nasabahList
            .filter { it.adminUid.isNotBlank() && it.adminName.isNotBlank() }
            .map { it.adminUid to it.adminName }
            .distinctBy { it.first }  // Unique by adminUid
            .sortedBy { it.second.lowercase() }
    }

    // =========================================================================
    // CALCULATE COUNTS
    // =========================================================================
    val countSemua = nasabahList.size
    val countAktif = nasabahList.count { !it.isLunas && !it.isMenungguPencairan }
    val countLunas = nasabahList.count { it.isLunas }
    val countMenungguPencairan = nasabahList.count { it.isMenungguPencairan }

    // =========================================================================
    // ✅ BARU: CALCULATE SUMMARY - Dari filteredList (yang sedang ditampilkan)
    // =========================================================================
    val totalNasabahDisplayed = filteredList.size
    val nasabahAktifDisplayed = filteredList.count { !it.isLunas && !it.isMenungguPencairan }
    val nasabahLunasDisplayed = filteredList.count { it.isLunas }
    val nasabahMenungguPencairanDisplayed = filteredList.count { it.isMenungguPencairan }

    // ✅ BARU: Hitung saldo berdasarkan filter yang dipilih
    val totalSaldoDisplayed = when (selectedFilter) {
        KorNasabahFilter.SEMUA -> 0L
        KorNasabahFilter.AKTIF -> filteredList
            .filter { !it.isLunas && !it.isMenungguPencairan }
            .sumOf { it.sisaHutang.toLong() }
        KorNasabahFilter.LUNAS -> 0L
        KorNasabahFilter.MENUNGGU_PENCAIRAN -> filteredList
            .filter { it.isMenungguPencairan }
            .sumOf { it.sisaHutang.toLong() }
    }

    // ✅ BARU: Total simpanan untuk filter Menunggu Pencairan
    val totalSimpananDisplayed = when (selectedFilter) {
        KorNasabahFilter.MENUNGGU_PENCAIRAN -> filteredList
            .filter { it.isMenungguPencairan }
            .sumOf { it.simpanan.toLong() }
        else -> 0L
    }

    // Cek apakah dalam mode tanpa filter
    val isUnfilteredMode = selectedFilter == KorNasabahFilter.SEMUA &&
            searchQuery.isBlank() &&
            selectedPdlUid == null

    // =========================================================================
    // UI
    // =========================================================================
    Scaffold(
        containerColor = bgColor,
        topBar = {
            KorDaftarNasabahTopBar(
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
            // =========================================================
            // CABANG FILTER BAR - Sama seperti KoordinatorReportsScreen
            // =========================================================
            KorNasabahCabangFilterBar(
                isDark = isDark,
                cabangOptions = cabangOptions,
                selectedCabangId = selectedCabangId,
                onCabangSelected = { viewModel.setPengawasSelectedCabang(it) }
            )

            // Search Bar
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(300)
                )
            ) {
                KorSearchBarSection(
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
                    onPdlClick = { showPdlDialog = true },  // ✅ BARU
                    selectedPdlName = selectedPdlName,       // ✅ BARU
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
                KorFilterChipsSection(
                    selectedFilter = selectedFilter,
                    onFilterSelected = { selectedFilter = it },
                    totalSemua = countSemua,
                    totalAktif = countAktif,
                    totalLunas = countLunas,
                    totalMenungguPencairan = countMenungguPencairan,
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
                KorSummaryCardSection(
                    totalNasabah = totalNasabahDisplayed,
                    nasabahAktif = if (isUnfilteredMode) countAktif else nasabahAktifDisplayed,
                    nasabahLunas = if (isUnfilteredMode) countLunas else nasabahLunasDisplayed,
                    nasabahMenungguPencairan = if (isUnfilteredMode) countMenungguPencairan else nasabahMenungguPencairanDisplayed,
                    totalSisaHutang = totalSaldoDisplayed,
                    totalSimpanan = totalSimpananDisplayed,
                    selectedFilter = selectedFilter,
                    isDark = isDark,
                    cardColor = cardColor
                )
            }

            // Content
            when {
                isLoading -> {
                    KorLoadingState(isDark = isDark)
                }
                errorMessage != null -> {
                    KorErrorState(
                        message = errorMessage!!,
                        onRetry = { coroutineScope.launch { loadData() } },
                        isDark = isDark
                    )
                }
                filteredList.isEmpty() -> {
                    KorEmptyState(
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
                            key = { "${it.cabangId}_${it.id}" }
                        ) { item ->
                            KorNasabahItemCard(
                                item = item,
                                onClick = { openRekeningKoran(item) },
                                isDark = isDark,
                                cardColor = cardColor,
                                txtColor = txtColor,
                                subtitleColor = subtitleColor
                            )
                        }
                    }
                }
            }
        }
    }
    // ✅ BARU: PDL Filter Dialog
    if (showPdlDialog) {
        KorPdlFilterDialog(
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

// =========================================================================
// CABANG FILTER BAR
// =========================================================================
@Composable
private fun KorNasabahCabangFilterBar(
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

// =========================================================================
// TOP BAR
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KorDaftarNasabahTopBar(
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
                    color = KoordinatorColors.getTextPrimary(isDark)
                )
                Text(
                    text = "$totalNasabah nasabah",
                    fontSize = 12.sp,
                    color = KoordinatorColors.getTextSecondary(isDark)
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Kembali",
                    tint = KoordinatorColors.getTextPrimary(isDark)
                )
            }
        },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = KoordinatorColors.primary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = KoordinatorColors.getCard(isDark)
        )
    )
}

// =========================================================================
// SEARCH BAR SECTION
// =========================================================================
@Composable
private fun KorSearchBarSection(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClear: () -> Unit,
    selectedSort: KorSortOption,
    showSortMenu: Boolean,
    onSortMenuToggle: (Boolean) -> Unit,
    onSortSelected: (KorSortOption) -> Unit,
    onPdlClick: () -> Unit,           // ✅ BARU
    selectedPdlName: String?,          // ✅ BARU
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "Cari nama, wilayah, PDL...",
                    color = subtitleColor
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = subtitleColor
                )
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Rounded.Clear,
                            contentDescription = "Clear",
                            tint = subtitleColor
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = KoordinatorColors.primary,
                unfocusedBorderColor = borderColor,
                focusedContainerColor = cardColor,
                unfocusedContainerColor = cardColor
            )
        )

        // Sort Button
        Box {
            IconButton(
                onClick = { onSortMenuToggle(true) },
                modifier = Modifier
                    .background(cardColor, RoundedCornerShape(12.dp))
            ) {
                Icon(
                    Icons.Rounded.Sort,
                    contentDescription = "Sort",
                    tint = KoordinatorColors.primary
                )
            }

            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { onSortMenuToggle(false) }
            ) {
                KorSortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                fontWeight = if (selectedSort == option) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = { onSortSelected(option) },
                        leadingIcon = if (selectedSort == option) {
                            {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = KoordinatorColors.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null
                    )
                }

                // ✅ BARU: Divider dan PDL Filter Option
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = KoordinatorColors.info,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (selectedPdlName != null) "PDL: $selectedPdlName" else "Filter PDL",
                                color = if (selectedPdlName != null) KoordinatorColors.info else Color.Unspecified
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
private fun KorFilterChipsSection(
    selectedFilter: KorNasabahFilter,
    onFilterSelected: (KorNasabahFilter) -> Unit,
    totalSemua: Int,
    totalAktif: Int,
    totalLunas: Int,
    totalMenungguPencairan: Int,
    isDark: Boolean
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            KorFilterChipWithCount(
                filter = KorNasabahFilter.SEMUA,
                count = totalSemua,
                isSelected = selectedFilter == KorNasabahFilter.SEMUA,
                onClick = { onFilterSelected(KorNasabahFilter.SEMUA) },
                isDark = isDark
            )
        }
        item {
            KorFilterChipWithCount(
                filter = KorNasabahFilter.AKTIF,
                count = totalAktif,
                isSelected = selectedFilter == KorNasabahFilter.AKTIF,
                onClick = { onFilterSelected(KorNasabahFilter.AKTIF) },
                isDark = isDark
            )
        }
        item {
            KorFilterChipWithCount(
                filter = KorNasabahFilter.LUNAS,
                count = totalLunas,
                isSelected = selectedFilter == KorNasabahFilter.LUNAS,
                onClick = { onFilterSelected(KorNasabahFilter.LUNAS) },
                isDark = isDark
            )
        }
        item {
            KorFilterChipWithCount(
                filter = KorNasabahFilter.MENUNGGU_PENCAIRAN,
                count = totalMenungguPencairan,
                isSelected = selectedFilter == KorNasabahFilter.MENUNGGU_PENCAIRAN,
                onClick = { onFilterSelected(KorNasabahFilter.MENUNGGU_PENCAIRAN) },
                isDark = isDark
            )
        }
    }
}

@Composable
private fun KorFilterChipWithCount(
    filter: KorNasabahFilter,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    isDark: Boolean
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = filter.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = filter.label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    text = "($count)",
                    fontSize = 11.sp,
                    color = if (isSelected) Color.White.copy(alpha = 0.8f)
                    else KoordinatorColors.getTextMuted(isDark)
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = KoordinatorColors.primary,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White
        )
    )
}

// =========================================================================
// SUMMARY CARD SECTION - VERSI LENGKAP
// =========================================================================
@Composable
private fun KorSummaryCardSection(
    totalNasabah: Int,
    nasabahAktif: Int,
    nasabahLunas: Int,
    nasabahMenungguPencairan: Int,
    totalSisaHutang: Long,
    totalSimpanan: Long,
    selectedFilter: KorNasabahFilter,
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
            KorSummaryItem(
                label = "Ditampilkan",
                value = totalNasabah.toString(),
                color = KoordinatorColors.primary,
                isDark = isDark
            )

            // Selalu tampilkan: Aktif
            KorSummaryItem(
                label = "Aktif",
                value = nasabahAktif.toString(),
                color = KoordinatorColors.info,
                isDark = isDark
            )

            // Selalu tampilkan: Lunas
            KorSummaryItem(
                label = "Lunas",
                value = nasabahLunas.toString(),
                color = KoordinatorColors.success,
                isDark = isDark
            )

            // Selalu tampilkan: Pencairan Simpanan
            KorSummaryItem(
                label = "Pencairan Simpanan",
                value = nasabahMenungguPencairan.toString(),
                color = KoordinatorColors.warning,
                isDark = isDark
            )

            // KONDISIONAL: Total Saldo berdasarkan filter
            when (selectedFilter) {
                KorNasabahFilter.SEMUA -> {
                    // Tidak tampilkan Total Saldo untuk filter Semua
                }
                KorNasabahFilter.AKTIF -> {
                    KorSummaryItem(
                        label = "Total Saldo",
                        value = formatRupiah(totalSisaHutang.toInt()),
                        color = KoordinatorColors.danger,
                        isDark = isDark,
                        isLarge = true
                    )
                }
                KorNasabahFilter.LUNAS -> {
                    KorSummaryItem(
                        label = "Total Saldo",
                        value = "Rp 0",
                        color = KoordinatorColors.success,
                        isDark = isDark,
                        isLarge = true
                    )
                }
                KorNasabahFilter.MENUNGGU_PENCAIRAN -> {
                    KorSummaryItem(
                        label = "Total Saldo",
                        value = formatRupiah(totalSisaHutang.toInt()),
                        color = KoordinatorColors.danger,
                        isDark = isDark,
                        isLarge = true
                    )
                    KorSummaryItem(
                        label = "Total Simpanan",
                        value = formatRupiah(totalSimpanan.toInt()),
                        color = KoordinatorColors.warning,
                        isDark = isDark,
                        isLarge = true
                    )
                }
            }
        }
    }
}

@Composable
private fun KorSummaryItem(
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
            color = KoordinatorColors.getTextMuted(isDark)
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
private fun KorNasabahItemCard(
    item: KorNasabahIndexItem,
    onClick: () -> Unit,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        when {
                            item.isLunas -> KoordinatorColors.success.copy(alpha = 0.1f)
                            item.isMenungguPencairan -> KoordinatorColors.warning.copy(alpha = 0.1f)
                            else -> KoordinatorColors.primary.copy(alpha = 0.1f)
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.nama.take(2).uppercase(),
                    color = when {
                        item.isLunas -> KoordinatorColors.success
                        item.isMenungguPencairan -> KoordinatorColors.warning
                        else -> KoordinatorColors.primary
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.nama,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = txtColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.adminName} • ${item.wilayah}",
                    fontSize = 12.sp,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Status badge
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    when {
                        item.isLunas -> {
                            KorStatusBadge("Lunas", KoordinatorColors.success)
                        }
                        item.isMenungguPencairan -> {
                            KorStatusBadge("Menunggu Pencairan", KoordinatorColors.warning)
                        }
                        else -> {
                            KorStatusBadge("Aktif", KoordinatorColors.primary)
                        }
                    }
                }
            }

            // Sisa Hutang / Simpanan
            Column(horizontalAlignment = Alignment.End) {
                if (!item.isLunas) {
                    Text(
                        text = "Sisa",
                        fontSize = 10.sp,
                        color = subtitleColor
                    )
                    Text(
                        text = formatRupiah(item.sisaHutang),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = KoordinatorColors.danger
                    )
                } else {
                    Text(
                        text = "Simpanan",
                        fontSize = 10.sp,
                        color = subtitleColor
                    )
                    Text(
                        text = formatRupiah(item.simpanan),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = KoordinatorColors.success
                    )
                }
            }

            // Arrow
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = subtitleColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun KorStatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

// =========================================================================
// LOADING STATE
// =========================================================================
@Composable
private fun KorLoadingState(isDark: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = KoordinatorColors.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Memuat data nasabah...",
                color = KoordinatorColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )
        }
    }
}

// =========================================================================
// ERROR STATE
// =========================================================================
@Composable
private fun KorErrorState(
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
                        KoordinatorColors.danger.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = null,
                    tint = KoordinatorColors.danger,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = KoordinatorColors.danger,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoordinatorColors.danger
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
private fun KorEmptyState(
    searchQuery: String,
    selectedFilter: KorNasabahFilter,
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
                        KoordinatorColors.info.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (searchQuery.isNotBlank()) Icons.Rounded.SearchOff
                    else Icons.Rounded.PeopleOutline,
                    contentDescription = null,
                    tint = KoordinatorColors.info,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when {
                    searchQuery.isNotBlank() -> "Tidak ditemukan nasabah dengan kata kunci \"$searchQuery\""
                    selectedFilter != KorNasabahFilter.SEMUA -> "Tidak ada nasabah ${selectedFilter.label.lowercase()}"
                    else -> "Belum ada data nasabah"
                },
                color = KoordinatorColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )

            if (searchQuery.isNotBlank() || selectedFilter != KorNasabahFilter.SEMUA) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Coba ubah filter atau kata kunci pencarian",
                    color = KoordinatorColors.getTextMuted(isDark),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// =========================================================================
// PDL FILTER DIALOG
// =========================================================================
@Composable
private fun KorPdlFilterDialog(
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
                        color = KoordinatorColors.danger.copy(alpha = 0.1f),
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
                                tint = KoordinatorColors.danger,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Hapus Filter PDL",
                                color = KoordinatorColors.danger,
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
                            color = if (isSelected) KoordinatorColors.primary.copy(alpha = 0.1f)
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
                                            if (isSelected) KoordinatorColors.primary
                                            else KoordinatorColors.primary.copy(alpha = 0.1f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = adminName.take(2).uppercase(),
                                        color = if (isSelected) Color.White else KoordinatorColors.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }

                                Text(
                                    text = adminName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected)
                                        KoordinatorColors.primary
                                    else
                                        txtColor,
                                    modifier = Modifier.weight(1f)
                                )

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = KoordinatorColors.primary,
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
                Text("Tutup", color = KoordinatorColors.primary)
            }
        },
        containerColor = cardColor
    )
}