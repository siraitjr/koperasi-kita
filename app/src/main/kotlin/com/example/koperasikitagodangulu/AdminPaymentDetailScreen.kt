// File: AdminPaymentDetailScreen.kt
package com.example.koperasikitagodangulu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPaymentDetailScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel,
    adminId: String?
) {
    val today = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).format(Date())

    // Gunakan fungsi dari viewModel dengan benar
    val customerPayments = remember(viewModel.daftarPelanggan.size, today, adminId) {
        viewModel.calculateCustomerPayments(viewModel.daftarPelanggan, today, adminId)
    }

    val adminName = remember(adminId) {
        viewModel.daftarPelanggan
            .firstOrNull { it.adminUid == adminId }?.adminName ?: "Admin Tidak Diketahui"
    }
    val isDark by viewModel.isDarkMode
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) AdminColors.darkSurface else AdminColors.lightSurface
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detail Pembayaran - $adminName") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (customerPayments.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Tidak ada pembayaran hari ini untuk admin ini",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                items(items = customerPayments) { customerPayment ->
                    CustomerPaymentCard(customerPayment = customerPayment)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    val totalAllCustomers = customerPayments.sumOf { it.paymentAmount }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "TOTAL ADMIN",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                formatRupiah(totalAllCustomers),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1976D2)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerPaymentCard(customerPayment: CustomerPayment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    customerPayment.customerName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    formatRupiah(customerPayment.paymentAmount),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
            }

            // Tampilkan detail cicilan jika ada
            if (customerPayment.installmentDetails.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Detail Cicilan:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray
                )
                customerPayment.installmentDetails.forEach { detail ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            detail.date,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            formatRupiah(detail.amount),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}