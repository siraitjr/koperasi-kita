package com.example.koperasikitagodangulu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.foundation.clickable
import com.example.koperasikitagodangulu.utils.formatRupiah
import kotlinx.coroutines.delay
import com.example.koperasikitagodangulu.models.AdminSummary
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPelangganDetailScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel,
    adminId: String?
) {
    // --- State untuk paging daftar nasabah ---
    val adminNasabah = remember { mutableStateListOf<Pelanggan>() }
    var lastKey by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    val searchQuery = remember { mutableStateOf("") }
    val adminInfo = remember { mutableStateOf<AdminSummary?>(null) }
    val isDark by viewModel.isDarkMode
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) AdminColors.darkSurface else AdminColors.lightSurface
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Initial LOAD: load paginated pelanggan admin
    LaunchedEffect(adminId) {
        if (adminId != null) {
            isLoading = true
            adminNasabah.clear()
            lastKey = null
            hasMore = true
            viewModel.loadAdminNasabahPaginated(
                adminUid = adminId,
                lastKey = null,
                limit = 50
            ) { items, newLastKey ->
                adminNasabah.addAll(items)
                lastKey = newLastKey
                isLoading = false
                hasMore = items.size == 50 && newLastKey != null
            }
            // Ambil juga summary admin (tidak diubah)
            val summary = viewModel.adminSummary.value.firstOrNull { it.adminId == adminId }
            adminInfo.value = summary
        }
    }

    // Fungsi untuk load more (paging)
    fun loadMoreNasabah() {
        if (!isLoading && hasMore && adminId != null) {
            isLoading = true
            viewModel.loadAdminNasabahPaginated(
                adminUid = adminId,
                lastKey = lastKey,
                limit = 50
            ) { items, newLastKey ->
                adminNasabah.addAll(items)
                lastKey = newLastKey
                isLoading = false
                hasMore = items.size == 50 && newLastKey != null
            }
        }
    }

    // Filtering/cari di hasil paging saja
    val filteredNasabah = remember(adminNasabah, searchQuery.value) {
        if (searchQuery.value.isBlank()) adminNasabah
        else adminNasabah.filter { pelanggan ->
            pelanggan.namaPanggilan.contains(searchQuery.value, ignoreCase = true) ||
                    pelanggan.namaKtp.contains(searchQuery.value, ignoreCase = true) ||
                    pelanggan.noHp.contains(searchQuery.value, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Nasabah - ${adminInfo.value?.adminEmail ?: adminInfo.value?.adminName ?: "Admin"}"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading && adminNasabah.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CustomLoadingIndicator(message = "Memuat data nasabah...")
            }
        } else if (adminNasabah.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Tidak ada nasabah untuk admin ini")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Admin ID: $adminId",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Info Admin
                item {
                    if (adminInfo.value != null) {
                        val admin = adminInfo.value!!
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = "Admin",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            admin.adminEmail.ifBlank { admin.adminName },
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "ID: ${admin.adminId.take(8)}...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            "Nasabah Aktif",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            admin.nasabahAktif.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // Nasabah Lunas
                                    Column {
                                        Text(
                                            "Nasabah Lunas",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            admin.nasabahLunas.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // Total Piutang
                                    Column {
                                        Text(
                                            "Total Piutang",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            formatRupiah(admin.totalPiutang.toInt()),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (admin.totalPiutang > 0) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // Search Bar
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        elevation = CardDefaults.cardElevation(2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Cari nasabah",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            TextField(
                                value = searchQuery.value,
                                onValueChange = { searchQuery.value = it },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                        "Cari nasabah...",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                },
                                colors = TextFieldDefaults.textFieldColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    focusedIndicatorColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    unfocusedIndicatorColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                                    cursorColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                singleLine = true
                            )
                        }
                    }
                    // Info jumlah hasil pencarian
                    if (searchQuery.value.isNotBlank()) {
                        Text(
                            text = "Ditemukan ${filteredNasabah.size} nasabah",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // List nasabah
                items(filteredNasabah) { pelanggan ->
                    SimplePelangganItem(
                        pelanggan = pelanggan,
                        navController = navController
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Tombol load more data (paging)
                if (hasMore) {
                    item {
                        Button(
                            onClick = { loadMoreNasabah() },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            else Text("Muat Lebih Banyak")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

// Komponen sederhana untuk menampilkan pelanggan (tetap sama)
@Composable
fun SimplePelangganItem(
    pelanggan: Pelanggan,
    navController: NavHostController
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                navController.navigate("detail_pelanggan/${pelanggan.id}")
            },
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pelanggan.namaPanggilan.ifEmpty { pelanggan.namaKtp },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        pelanggan.noHp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    when (pelanggan.status) {
                        "Aktif" -> "🟢 Aktif"
                        "Lunas" -> "🔵 Lunas"
                        "Menunggu Approval" -> "🟡 Menunggu"
                        "Ditolak" -> "🔴 Ditolak"
                        else -> pelanggan.status
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "Total Pelunasan: ${formatRupiah(pelanggan.totalPelunasan)}",
                style = MaterialTheme.typography.bodySmall
            )

            // Hitung total yang sudah dibayar
            val totalDibayar = pelanggan.pembayaranList.sumOf { pembayaran ->
                pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
            }
            val sisaHutang = pelanggan.totalPelunasan - totalDibayar

            Text(
                "Dibayar: ${formatRupiah(totalDibayar)} | Sisa: ${formatRupiah(sisaHutang)}",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "Tenor: ${pelanggan.tenor} hari | Cicilan: ${pelanggan.pembayaranList.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Custom loading indicator (tidak diubah dari kode Anda)
@Composable
fun CustomLoadingIndicator(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(message)
    }
}