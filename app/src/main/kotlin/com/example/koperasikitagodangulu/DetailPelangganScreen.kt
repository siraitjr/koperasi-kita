package com.example.koperasikitagodangulu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailPelangganScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel,
    pelangganId: String
) {
    val pelanggan = remember(pelangganId) {
        viewModel.daftarPelanggan.find { it.id == pelangganId }
    }

    val pelangganDenganStatus = remember(pelanggan) {
        pelanggan?.let { p ->
            val totalDibayar = p.pembayaranList.sumOf { pembayaran ->
                pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
            }
            val sudahLunas = totalDibayar >= p.totalPelunasan

            if (sudahLunas && p.status != "Lunas") {
                p.copy(status = "Lunas")
            } else {
                p
            }
        }
    }
    val isDark by viewModel.isDarkMode
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) Color(0xFF1A1A2E) else Color.White
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detail Nasabah") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { innerPadding ->
        // ✅ PERBAIKAN: Gunakan pelangganDenganStatus untuk null check
        if (pelangganDenganStatus == null) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Data nasabah tidak ditemukan")
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Informasi Identitas
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Informasi Identitas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // ✅ GUNAKAN pelangganDenganStatus (bukan pelanggan)
                        if (pelangganDenganStatus.nomorAnggota.isNotBlank()) {
                            InfoRow("Nomor Anggota", pelangganDenganStatus.nomorAnggota)
                        }
                        InfoRow("Nama KTP", pelangganDenganStatus.namaKtp)
                        InfoRow("Nama Panggilan", pelangganDenganStatus.namaPanggilan)
                        InfoRow("NIK", pelangganDenganStatus.nik)
                        InfoRow("No HP", pelangganDenganStatus.noHp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Informasi Alamat
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Informasi Alamat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Alamat KTP", pelangganDenganStatus.alamatKtp)
                        InfoRow("Alamat Rumah", pelangganDenganStatus.alamatRumah)
                        InfoRow("Wilayah", pelangganDenganStatus.wilayah)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Informasi Usaha
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Informasi Usaha",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Jenis Usaha", pelangganDenganStatus.jenisUsaha)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Informasi Pinjaman
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Informasi Pinjaman",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Status", pelangganDenganStatus.status)
                        InfoRow("Pinjaman Ke", pelangganDenganStatus.pinjamanKe.toString())
                        InfoRow("Besar Pinjaman", "Rp ${formatRupiah(pelangganDenganStatus.besarPinjaman)}")
                        InfoRow("Jasa Pinjaman (10%)", "Rp ${formatRupiah(pelangganDenganStatus.jasaPinjaman)}")
                        InfoRow("Admin (5%)", "Rp ${formatRupiah(pelangganDenganStatus.admin)}")
                        // ✅ GUNAKAN pelangganDenganStatus untuk semua perhitungan
                        val simpananLimaPersen = (pelangganDenganStatus.besarPinjaman * 5) / 100
                        InfoRow("Simpanan (5%)", "Rp ${formatRupiah(simpananLimaPersen)}")

                        if (pelangganDenganStatus.pinjamanKe >= 2) {
                            val totalSimpananSemua = viewModel.getTotalSimpananByNama(pelangganDenganStatus.namaKtp)
                            InfoRow(
                                "Total Simpanan (Akumulasi)",
                                "Rp ${formatRupiah(totalSimpananSemua)}"
                            )
                        }

                        InfoRow("Total Diterima", "Rp ${formatRupiah(pelangganDenganStatus.totalDiterima)}")
                        InfoRow("Total Pelunasan", "Rp ${formatRupiah(pelangganDenganStatus.totalPelunasan)}")

                        val totalDibayar = pelangganDenganStatus.pembayaranList.sumOf { pembayaran ->
                            pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
                        }
                        val sisaPelunasan = (pelangganDenganStatus.totalPelunasan - totalDibayar).coerceAtLeast(0)
                        val jumlahCicilanDibayar = pelangganDenganStatus.pembayaranList.size
                        val sisaTenor = if (pelangganDenganStatus.status == "Aktif") {
                            (pelangganDenganStatus.tenor - jumlahCicilanDibayar).coerceAtLeast(0)
                        } else {
                            0
                        }
                        InfoRow("Total Dibayar", "Rp ${formatRupiah(totalDibayar)}")
                        InfoRow("Sisa Pelunasan", "Rp ${formatRupiah(sisaPelunasan)}")

                        InfoRow("Tenor", "${pelangganDenganStatus.tenor} kali cicilan")
                        InfoRow("Cicilan Dibayar", "$jumlahCicilanDibayar kali")
                        InfoRow("Sisa Tenor", "$sisaTenor kali")
                        InfoRow("Tanggal Pengajuan", pelangganDenganStatus.tanggalPengajuan)

                        // Tampilkan jumlah pembayaran
                        InfoRow("Jumlah Pembayaran", "${pelangganDenganStatus.pembayaranList.size} kali")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Informasi Admin
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Admin Lapangan",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoRow("Nama Admin", pelangganDenganStatus.adminName)
                        InfoRow("Email Admin", pelangganDenganStatus.adminEmail)
                        InfoRow("UID Admin", pelangganDenganStatus.adminUid)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    if (value.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$label:", style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}