package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * =========================================================================
 * KOORDINATOR DETAIL SERAH TERIMA SCREEN
 * =========================================================================
 *
 * Screen untuk menampilkan detail foto serah terima uang
 * Khusus untuk Koordinator - membaca dari koordinatorSerahTerimaNotifications
 *
 * Path Firebase: koordinator_serah_terima_notifications/{koordinatorUid}/{notificationId}
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoordinatorDetailSerahTerimaScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    notificationId: String?
) {
    // ✅ Menggunakan StateFlow khusus koordinator
    val koordinatorSerahTerimaNotifications by viewModel.pengawasSerahTerimaNotifications.collectAsState()

    val currentNotification = remember(notificationId, koordinatorSerahTerimaNotifications) {
        koordinatorSerahTerimaNotifications.find { it.id == notificationId }
    }

    val isDark by viewModel.isDarkMode
    val txtColor = if (isDark) Color.White else Color.Black
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val systemUiController = rememberSystemUiController()
    val bgColor = KoordinatorColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Purple theme untuk Koordinator
    val primaryColor = Color(0xFF7C3AED)

    fun formatRupiahLocal(amount: Int): String {
        val formatter = NumberFormat.getNumberInstance(Locale("in", "ID"))
        return "Rp ${formatter.format(amount)}"
    }

    // Mark as read saat dibuka
    LaunchedEffect(notificationId) {
        if (notificationId != null && currentNotification?.read == false) {
            Log.d("KoordinatorSerahTerima", "🔄 Marking notification as read: $notificationId")
            viewModel.markPengawasSerahTerimaNotificationAsRead(notificationId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Bukti Serah Terima",
                        style = MaterialTheme.typography.titleMedium,
                        color = txtColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.popBackStack()
                    }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Kembali",
                            tint = txtColor
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = if (isDark) Color.Black else Color.White
                )
            )
        },
        containerColor = bgColor
    ) { innerPadding ->
        if (currentNotification == null) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.SearchOff,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Notifikasi tidak ditemukan",
                        color = txtColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Coba refresh halaman sebelumnya",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            val notification = currentNotification

            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // ==========================================
                // HEADER: Status Serah Terima
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) Color(0xFF2D1F4B) else Color(0xFFEDE9FE)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "Serah Terima Selesai",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )

                        Text(
                            notification.tanggalSerahTerima,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDark) Color(0xFFD8B4FE) else Color(0xFF6B21A8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ==========================================
                // INFO NASABAH
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Informasi Nasabah",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = txtColor
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        KoordinatorSerahTerimaDetailItem(
                            label = "Nama Nasabah",
                            value = notification.pelangganNama,
                            txtColor = txtColor
                        )

                        KoordinatorSerahTerimaDetailItem(
                            label = "Besar Pinjaman",
                            value = formatRupiahLocal(notification.besarPinjaman),
                            txtColor = txtColor,
                            valueColor = primaryColor
                        )

                        KoordinatorSerahTerimaDetailItem(
                            label = "Tenor",
                            value = "${notification.tenor} hari",
                            txtColor = txtColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ==========================================
                // INFO ADMIN
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Badge,
                                contentDescription = null,
                                tint = Color(0xFFFF9800),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Diserahkan Oleh",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = txtColor
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        KoordinatorSerahTerimaDetailItem(
                            label = "PDL",
                            value = notification.adminName,
                            txtColor = txtColor
                        )

                        KoordinatorSerahTerimaDetailItem(
                            label = "Waktu Serah Terima",
                            value = notification.tanggalSerahTerima,
                            txtColor = txtColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ==========================================
                // FOTO SERAH TERIMA
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Photo,
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Foto Bukti Serah Terima Uang",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = txtColor
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (notification.fotoSerahTerimaUrl.isNotBlank()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp),
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(4.dp)
                            ) {
                                AsyncImage(
                                    model = notification.fotoSerahTerimaUrl,
                                    contentDescription = "Foto Serah Terima",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                "Klik foto untuk memperbesar",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Default.BrokenImage,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Foto tidak tersedia",
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ==========================================
                // TOMBOL KEMBALI
                // ==========================================
                Button(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Selesai",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun KoordinatorSerahTerimaDetailItem(
    label: String,
    value: String,
    txtColor: Color,
    valueColor: Color = txtColor
) {
    Column(
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = txtColor.copy(alpha = 0.6f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}