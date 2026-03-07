package com.example.koperasikitagodangulu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.koperasikitagodangulu.utils.formatRupiah

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PelangganPerAdminScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val pelangganPerAdmin = remember { viewModel.pelangganPerAdmin }
    val isLoading = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading.value = true
        viewModel.loadPelangganGroupedByAdmin()
        isLoading.value = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nasabah per Admin Lapangan") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading.value) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                if (pelangganPerAdmin.isEmpty()) {
                    item {
                        Text(
                            "Tidak ada data nasabah",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    items(pelangganPerAdmin.toList()) { adminGroup ->
                        AdminPelangganGroup(
                            adminGroup = adminGroup,
                            navController = navController
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AdminPelangganGroup(
    adminGroup: AdminPelangganGroup,
    navController: NavHostController
) {
    // ✅ AMBIL EMAIL DARI PELANGGAN PERTAMA (jika tersedia)
    val adminEmail = adminGroup.pelangganList.firstOrNull()?.adminEmail

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Admin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        adminGroup.adminName ?: "Admin Tidak Diketahui",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Jumlah Nasabah: ${adminGroup.pelangganList.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // ✅ TAMPILKAN EMAIL ADMIN
                    if (!adminEmail.isNullOrEmpty()) {
                        Text(
                            "Email: $adminEmail",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Fallback ke ID jika email tidak tersedia
                        adminGroup.adminId?.let { adminId ->
                            Text(
                                "ID: $adminId",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Admin",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Daftar Pelanggan
            Column {
                adminGroup.pelangganList.forEachIndexed { index, pelanggan ->
                    PelangganItem(
                        pelanggan = pelanggan,
                        isLast = index == adminGroup.pelangganList.size - 1,
                        navController = navController
                    )
                }
            }
        }
    }
}

@Composable
fun PelangganItem(
    pelanggan: Pelanggan,
    isLast: Boolean,
    navController: NavHostController
) {
    // Hitung total dibayar dan jumlah cicilan
    val totalDibayar = pelanggan.pembayaranList.sumOf { it.jumlah }
    val jumlahCicilanDibayar = pelanggan.pembayaranList.size

    // Hitung sisa tenor (jumlah cicilan tersisa)
    val sisaTenor = if (pelanggan.status == "Aktif") {
        (pelanggan.tenor - jumlahCicilanDibayar).coerceAtLeast(0)
    } else {
        0
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = {
                // Navigasi ke detail pelanggan
                navController.navigate("detail_pelanggan/${pelanggan.id}")
            }),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Pelanggan
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        pelanggan.namaKtp.ifEmpty { "Nama Tidak Tersedia" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        pelanggan.namaPanggilan.ifEmpty { "Tidak ada nama panggilan" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status Pinjaman
                Text(
                    text = when (pelanggan.status) {
                        "Aktif" -> "🟢 Aktif"
                        "Lunas" -> "🔵 Lunas"
                        "Menunggu Approval" -> "🟡 Menunggu"
                        "Ditolak" -> "🔴 Ditolak"
                        else -> pelanggan.status
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Informasi Pinjaman
            Column {
                // Total Pinjaman
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Pinjaman:", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Rp ${formatRupiah(pelanggan.totalPelunasan)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Sisa Pelunasan
                val sisaPelunasan = (pelanggan.totalPelunasan - totalDibayar).coerceAtLeast(0)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sisa Pelunasan:", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Rp ${formatRupiah(sisaPelunasan)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sisaPelunasan > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Tenor & Cicilan
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tenor:", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${pelanggan.tenor} kali",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Cicilan Dibayar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Cicilan Dibayar:", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "$jumlahCicilanDibayar kali",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Sisa Tenor (Cicilan)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sisa Tenor:", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "$sisaTenor kali",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sisaTenor > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Informasi Tambahan
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Wilayah:", style = MaterialTheme.typography.bodySmall)
                    Text(
                        pelanggan.wilayah.ifEmpty { "Tidak ada" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Progress Bar (untuk visualisasi progress cicilan)
            if (pelanggan.status == "Aktif") {
                Spacer(modifier = Modifier.height(8.dp))
                val progressCicilan = if (pelanggan.tenor > 0) {
                    (jumlahCicilanDibayar.toFloat() / pelanggan.tenor.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Column {
                    // Progress bar cicilan
                    LinearProgressIndicator(
                        progress = progressCicilan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Label progress
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Progress Cicilan:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${jumlahCicilanDibayar}/${pelanggan.tenor}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (!isLast) {
        Spacer(modifier = Modifier.height(8.dp))
    }
}