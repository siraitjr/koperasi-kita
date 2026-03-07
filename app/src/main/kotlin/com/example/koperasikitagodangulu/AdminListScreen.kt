package com.example.koperasikitagodangulu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.koperasikitagodangulu.utils.formatRupiah
import androidx.compose.foundation.clickable
import androidx.compose.runtime.SideEffect
import com.example.koperasikitagodangulu.models.AdminSummary
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminListScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    // ✅ PERBAIKAN: Gunakan collectAsState() dengan initial value
    val adminSummary by viewModel.adminSummary.collectAsState(initial = emptyList())
    val isDark by viewModel.isDarkMode
    // ✅ PERBAIKAN: Gunakan state untuk loading
    val isLoading = remember { mutableStateOf(false) }
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) AdminColors.darkSurface else AdminColors.lightSurface
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Load data saat screen pertama kali dibuka
    LaunchedEffect(Unit) {
        isLoading.value = true
        viewModel.refreshAdminSummary()
        isLoading.value = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Daftar Admin Lapangan")
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { innerPadding ->
        when {
            isLoading.value -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Memuat data admin...")
                    }
                }
            }
            adminSummary.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Data Kosong",
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Tidak ada data admin yang tersedia",
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Pastikan sudah ada admin yang terdaftar",
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // ✅ PERBAIKAN: Gunakan items dengan parameter yang benar
                    items(
                        items = adminSummary,
                        key = { it.adminId } // Gunakan adminId sebagai key
                    ) { admin ->
                        AdminListItem(
                            adminSummary = admin,
                            onClick = {
                                // ✅ PERBAIKAN: Pastikan adminId tersedia
                                navController.navigate("admin_pelanggan_detail/${admin.adminId}")
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AdminListItem(
    adminSummary: AdminSummary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Admin
            Icon(
                Icons.Default.Person,
                contentDescription = "Admin",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Informasi Admin
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    // ✅ PERBAIKAN: Handle null/empty values dengan safe calls
                    adminSummary.adminEmail.ifBlank { adminSummary.adminName ?: "Unknown Admin" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Statistik
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Pelanggan",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            // ✅ PERBAIKAN: Konversi ke String untuk menghindari error tipe
                            "${adminSummary.totalPelanggan} orang",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Column {
                        Text(
                            // ✅ UBAH: "Pinjaman Aktif" menjadi "Total Piutang"
                            "Total Piutang",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            // ✅ UBAH: Gunakan totalPiutang sebagai ganti totalPinjamanAktif
                            formatRupiah(adminSummary.totalPiutang.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (adminSummary.totalPiutang > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }
    }
}