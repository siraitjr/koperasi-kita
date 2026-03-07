package com.example.koperasikitagodangulu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.utils.formatRupiahInput
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Warning
import com.example.koperasikitagodangulu.utils.formatRupiahInput
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPinjamanScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    pelangganId: String?
) {
    val context = LocalContext.current
    val isDark by viewModel.isDarkMode
    val txtColor = if (isDark) Color.White else Color.Black
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val iconColor = if (isDark) Color(0xFF4DB6AC) else Color(0xFF00796B)
    val secondaryTextColor = if (isDark) Color(0xFFB0BEC5) else Color(0xFF616161)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) Color.Black else Color(0xFFF5F5F5)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Cari pelanggan berdasarkan ID
    val pelanggan = viewModel.daftarPelanggan.find { it.id == pelangganId }

    // State untuk form editing pinjaman
    var besarPinjaman by remember { mutableStateOf(pelanggan?.besarPinjaman?.toString() ?: "") }
    var tenor by remember { mutableStateOf(pelanggan?.tenor?.toString() ?: "24") }
    var catatanPerubahan by remember { mutableStateOf("") }

    // State untuk loading dan error
    var isLoading by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // State untuk kalkulasi otomatis
    val calculation = remember(besarPinjaman) {
        val pinjaman = besarPinjaman.replace("[^\\d]".toRegex(), "").toIntOrNull() ?: 0
        if (pinjaman > 0) {
            viewModel.calculatePinjamanValues(pinjaman)
        } else {
            null
        }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Ajukan Perubahan Pinjaman",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = txtColor,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = txtColor)
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = if (isDark) Color.Black else Color.White,
                    titleContentColor = txtColor
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                color = if (isDark) Color.Black else Color.White,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = iconColor
                        )
                    ) {
                        Text("Batal")
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Button(
                        onClick = {
                            // Validasi input
                            val pinjamanValue = besarPinjaman.replace("[^\\d]".toRegex(), "").toIntOrNull() ?: 0
                            val tenorValue = tenor.toIntOrNull() ?: 0

                            if (pinjamanValue <= 0) {
                                showError = true
                                errorMessage = "Besar pinjaman harus lebih dari 0"
                                return@Button
                            }

                            if (tenorValue !in 24..60) {
                                showError = true
                                errorMessage = "Tenor harus antara 24-60 hari"
                                return@Button
                            }

                            if (pelangganId.isNullOrBlank()) {
                                showError = true
                                errorMessage = "ID nasabah tidak valid"
                                return@Button
                            }

                            isLoading = true
                            viewModel.processTopUpLoanWithApproval(
                                pelangganId = pelangganId,
                                pinjamanBaru = pinjamanValue,
                                tenorBaru = tenorValue,
                                namaKtp = pelanggan?.namaKtp ?: "",
                                nik = pelanggan?.nik ?: "",
                                alamatKtp = pelanggan?.alamatKtp ?: "",
                                namaPanggilan = pelanggan?.namaPanggilan ?: "",
                                onSuccess = {
                                    isLoading = false
                                    Toast.makeText(context, "Pengajuan perubahan pinjaman berhasil dikirim", Toast.LENGTH_SHORT).show()
                                    navController.popBackStack()
                                },
                                onFailure = { e ->
                                    isLoading = false
                                    showError = true
                                    errorMessage = e.message ?: "Gagal mengajukan perubahan pinjaman"
                                }
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = iconColor),
                        enabled = !isLoading && besarPinjaman.isNotBlank() && tenor.isNotBlank()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.AttachMoney,
                                contentDescription = "Ajukan",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ajukan Perubahan")
                    }
                }
            }
        }
    ) { innerPadding ->
        if (pelanggan == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("Nasabah tidak ditemukan", color = secondaryTextColor)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Informasi nasabah saat ini
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Informasi Nasabah Saat Ini",
                        color = txtColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LoanInfoRow("Nama", pelanggan.namaKtp, secondaryTextColor)
                    LoanInfoRow("Pinjaman Ke", pelanggan.pinjamanKe.toString(), secondaryTextColor)
                    LoanInfoRow("Pinjaman Saat Ini", "Rp ${formatRupiah(pelanggan.besarPinjaman)}", secondaryTextColor)
                    LoanInfoRow("Tenor Saat Ini", "${pelanggan.tenor} hari", secondaryTextColor)

                    val totalBayar = pelanggan.pembayaranList.sumOf { it.jumlah }
                    val sisa = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)
                    LoanInfoRow("Sisa Hutang", "Rp ${formatRupiah(sisa)}",
                        if (sisa > 0) Color(0xFFFF9800) else Color(0xFF4CAF50))
                }
            }

            // Warning jika masih ada hutang
            if (pelanggan.pembayaranList.sumOf { it.jumlah } < pelanggan.totalPelunasan) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = Color(0xFFFF9800)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Nasabah masih memiliki hutang",
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Perubahan pinjaman akan menggabungkan sisa hutang dengan pinjaman baru",
                                color = Color(0xFFE65100),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Form perubahan pinjaman
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Perubahan Pinjaman",
                        color = txtColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Besar Pinjaman Baru
                    OutlinedTextField(
                        value = formatRupiahInput(besarPinjaman),
                        onValueChange = {
                            besarPinjaman = formatRupiahInput(it)
                        },
                        label = { Text("Besar Pinjaman Baru", color = secondaryTextColor) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            containerColor = cardColor,
                            focusedBorderColor = iconColor,
                            unfocusedBorderColor = Color.Gray,
                            cursorColor = iconColor,
                            focusedTextColor = txtColor,
                            unfocusedTextColor = txtColor,
                            focusedLabelColor = iconColor,
                            unfocusedLabelColor = secondaryTextColor
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.AttachMoney, contentDescription = "Pinjaman")
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Tenor Baru
                    OutlinedTextField(
                        value = tenor,
                        onValueChange = {
                            tenor = it.filter { char -> char.isDigit() }
                        },
                        label = { Text("Tenor Baru (hari)", color = secondaryTextColor) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            containerColor = cardColor,
                            focusedBorderColor = iconColor,
                            unfocusedBorderColor = Color.Gray,
                            cursorColor = iconColor,
                            focusedTextColor = txtColor,
                            unfocusedTextColor = txtColor,
                            focusedLabelColor = iconColor,
                            unfocusedLabelColor = secondaryTextColor
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.CalendarToday, contentDescription = "Tenor")
                        },
                        supportingText = {
                            Text("Rentang: 24-60 hari")
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Kalkulasi Otomatis
                    if (calculation != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E8))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    "Kalkulasi Pinjaman Baru",
                                    color = Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                LoanInfoRowSmall("Admin (5%)", "Rp ${formatRupiah(calculation.admin)}", Color(0xFF757575))
                                LoanInfoRowSmall("Simpanan (5%)", "Rp ${formatRupiah(calculation.simpanan)}", Color(0xFF757575))
                                LoanInfoRowSmall("Jasa Pinjaman (10%)", "Rp ${formatRupiah(calculation.jasaPinjaman)}", Color(0xFF757575))
                                LoanInfoRowSmall("Total Diterima", "Rp ${formatRupiah(calculation.totalDiterima)}", Color(0xFF2E7D32))
                                LoanInfoRowSmall("Total Pelunasan", "Rp ${formatRupiah(calculation.totalPelunasan)}", Color(0xFFD32F2F))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Catatan Perubahan (Opsional)
                    OutlinedTextField(
                        value = catatanPerubahan,
                        onValueChange = { catatanPerubahan = it },
                        label = { Text("Catatan Perubahan (Opsional)", color = secondaryTextColor) },
                        singleLine = false,
                        maxLines = 3,
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            containerColor = cardColor,
                            focusedBorderColor = iconColor,
                            unfocusedBorderColor = Color.Gray,
                            cursorColor = iconColor,
                            focusedTextColor = txtColor,
                            unfocusedTextColor = txtColor,
                            focusedLabelColor = iconColor,
                            unfocusedLabelColor = secondaryTextColor
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Alasan perubahan pinjaman...") }
                    )
                }
            }

            // Informasi Proses
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "📋 Proses Approval",
                        color = Color(0xFF1976D2),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• Pengajuan perubahan akan dikirim ke pimpinan untuk approval",
                        color = Color(0xFF1976D2),
                        fontSize = 12.sp
                    )
                    Text(
                        "• Status nasabah akan berubah menjadi 'Menunggu Approval'",
                        color = Color(0xFF1976D2),
                        fontSize = 12.sp
                    )
                    Text(
                        "• Pimpinan dapat menyetujui atau menolak pengajuan",
                        color = Color(0xFF1976D2),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(100.dp))

            // Error message
            if (showError) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = Color(0xFFD32F2F)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = errorMessage,
                            color = Color(0xFFD32F2F),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoanInfoRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label:", color = color, fontSize = 14.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
    Spacer(modifier = Modifier.height(4.dp))
}

// ✅ GUANTI NAMA FUNGSI MENJADI LoanInfoRowSmall
@Composable
fun LoanInfoRowSmall(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label:", color = color, fontSize = 12.sp)
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
    Spacer(modifier = Modifier.height(2.dp))
}