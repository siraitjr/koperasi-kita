package com.example.koperasikitagodangulu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.backgroundColor
import com.example.koperasikitagodangulu.utils.textColor
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.example.koperasikitagodangulu.utils.formatRupiah
import android.util.Log
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaftarPelangganMacetScreen(
    navController: NavController,
    viewModel: PelangganViewModel
) {
    val pelangganMacet = viewModel.getPelangganMacet()
    val isDark by viewModel.isDarkMode
    val txtColor = textColor(isDark)
    val systemUiController = rememberSystemUiController()
    val bgColor = backgroundColor(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // State untuk search
    var searchQuery by remember { mutableStateOf("") }
    val filteredPelangganMacet = remember(pelangganMacet, searchQuery) {
        if (searchQuery.isBlank()) {
            pelangganMacet
        } else {
            pelangganMacet.filter { pelanggan ->
                pelanggan.namaPanggilan.contains(searchQuery, ignoreCase = true) ||
                        pelanggan.nik.contains(searchQuery, ignoreCase = true) ||
                        pelanggan.wilayah.contains(searchQuery, ignoreCase = true) ||
                        pelanggan.namaKtp.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Nasabah Macet (${pelangganMacet.size})",
                        color = txtColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = txtColor
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = if (isDark) Color.Black else Color(0xFFFFFFFF),
                    titleContentColor = txtColor
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(padding)
        ) {
            // Search Bar
            if (pelangganMacet.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Cari nasabah macet...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                }
            }

            if (pelangganMacet.isEmpty()) {
                // Tampilan ketika tidak ada nasabah macet
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "No Data",
                        tint = Color.Green,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Tidak Ada Nasabah Macet",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = txtColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Semua nasabah dalam kondisi baik",
                        style = MaterialTheme.typography.bodyMedium,
                        color = txtColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            } else if (filteredPelangganMacet.isEmpty()) {
                // Tampilan ketika pencarian tidak ditemukan
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.SearchOff,
                        contentDescription = "Not Found",
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Nasabah Tidak Ditemukan",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = txtColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tidak ada nasabah yang sesuai dengan pencarian \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = txtColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Daftar nasabah macet
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bgColor)
                ) {
                    items(filteredPelangganMacet) { pelanggan ->
                        PelangganMacetItem(
                            pelanggan = pelanggan,
                            onItemClick = {
                                // Navigasi ke detail pelanggan
                                Log.d("PelangganMacet", "Klik pada: ${pelanggan.namaPanggilan}")
                            },
                            isDark = isDark,
                            viewModel = viewModel
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PelangganMacetItem(
    pelanggan: Pelanggan,
    onItemClick: () -> Unit,
    viewModel: PelangganViewModel,
    isDark: Boolean
) {

    val displayName = viewModel.getDisplayName(pelanggan)
    val displayNik = viewModel.getDisplayNik(pelanggan)

    val totalDibayar = pelanggan.pembayaranList.sumOf { it.jumlah }
    val sisaHutang = pelanggan.totalPelunasan - totalDibayar
    val progress = if (pelanggan.totalPelunasan > 0) {
        totalDibayar.toFloat() / pelanggan.totalPelunasan
    } else {
        0f
    }

    // Hitung keterlambatan
    val keterlambatan = hitungKeterlambatan(pelanggan, viewModel)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onItemClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0x44D32F2F) else Color(0xFFFFEBEE)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header dengan nama dan status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFFF5252) else Color(0xFFD32F2F)
                )

                // Badge keterlambatan
                Text(
                    text = keterlambatan,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(
                            color = Color(0xFFD32F2F),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Informasi dasar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "NIK: ${pelanggan.nik}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color.LightGray else Color.DarkGray
                    )
                    Text(
                        text = "Wilayah: ${pelanggan.wilayah}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color.LightGray else Color.DarkGray
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Pinjaman ke-${pelanggan.pinjamanKe}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color.LightGray else Color.DarkGray
                    )
                    Text(
                        text = "Tenor: ${pelanggan.tenor} hari",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color.LightGray else Color.DarkGray
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress pembayaran
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Progress Pembayaran",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Progress bar
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = if (progress < 0.3f) Color(0xFFD32F2F)
                    else if (progress < 0.7f) Color(0xFFFFA000)
                    else Color(0xFF4CAF50),
                    trackColor = if (isDark) Color.DarkGray else Color.LightGray
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Informasi pinjaman
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Total Pinjaman",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) Color.LightGray else Color.DarkGray
                    )
                    Text(
                        text = formatRupiah(pelanggan.totalPelunasan),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Sisa Hutang",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDark) Color.LightGray else Color.DarkGray
                    )
                    Text(
                        text = formatRupiah(sisaHutang),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD32F2F)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Informasi pembayaran terakhir
            val pembayaranTerakhir = pelanggan.pembayaranList.maxByOrNull {
                SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).parse(it.tanggal)?.time ?: 0L
            }

            if (pembayaranTerakhir != null) {
                Text(
                    text = "Pembayaran terakhir: ${pembayaranTerakhir.tanggal} - ${formatRupiah(pembayaranTerakhir.jumlah)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDark) Color.LightGray else Color.DarkGray
                )
            } else {
                Text(
                    text = "Belum ada pembayaran",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD32F2F)
                )
            }
        }
    }
}


private fun hitungSelisihBulan(dari: Calendar, sampai: Calendar): Int {
    val tahunSelisih = sampai.get(Calendar.YEAR) - dari.get(Calendar.YEAR)
    val bulanSelisih = sampai.get(Calendar.MONTH) - dari.get(Calendar.MONTH)
    return (tahunSelisih * 12) + bulanSelisih
}

private fun hitungKeterlambatan(pelanggan: Pelanggan, viewModel: PelangganViewModel): String {
    return viewModel.getDetailStatusMacet(pelanggan)
}