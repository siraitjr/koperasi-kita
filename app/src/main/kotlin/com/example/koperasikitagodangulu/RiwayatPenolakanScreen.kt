package com.example.koperasikitagodangulu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiwayatPenolakanScreen(
    navController: NavController,
    viewModel: PelangganViewModel
) {
    val adminNotifications by viewModel.adminNotifications.collectAsState()
    val rejectionNotifications = adminNotifications.filter {
        it.type == "REJECTION" || it.type == "DUAL_APPROVAL_REJECTED"
    }
    val approvalNotifications = adminNotifications.filter {
        it.type == "APPROVAL" || it.type == "DUAL_APPROVAL_APPROVED"
    }
    val isDark by viewModel.isDarkMode
    val txtColor = if (isDark) Color.White else Color.Black
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Semua", "Penolakan", "Persetujuan")
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) Color.Black else Color(0xFFF5F5F5)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // ✅ FIX: Tambahkan coroutineScope di sini
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Notifikasi", // Ganti nama menjadi lebih umum
                        style = MaterialTheme.typography.titleMedium,
                        color = txtColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = txtColor)
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = if (isDark) Color.Black else Color.White
                )
            )
        },
        containerColor = bgColor
    ) { innerPadding ->

        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    text = {
                        Text(title, color = txtColor)
                    },
                    selected = selectedTab == index,
                    onClick = { selectedTab = index }
                )
            }
        }

        // CONTENT BERDASARKAN TAB
        val notificationsToShow = when (selectedTab) {
            0 -> adminNotifications
            1 -> rejectionNotifications
            2 -> approvalNotifications
            else -> adminNotifications
        }

        if (notificationsToShow.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Tidak ada notifikasi",
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        when (selectedTab) {
                            1 -> "Tidak Ada Riwayat Penolakan"
                            2 -> "Tidak Ada Riwayat Persetujuan"
                            else -> "Tidak Ada Notifikasi"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        when (selectedTab) {
                            1 -> "Semua pengajuan Anda telah disetujui"
                            2 -> "Belum ada pengajuan yang disetujui"
                            else -> "Tidak ada notifikasi saat ini"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
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
                items(notificationsToShow) { notification ->
                    NotificationCard(
                        notification = notification,
                        onDelete = {
                            viewModel.deleteNotification(notification.id)
                        },
                        onMarkAsRead = {
                            if (!notification.read) {
                                viewModel.markNotificationAsRead(notification.id)
                            }
                        },
                        onDetailClick = {
                            // Navigasi ke detail notifikasi
                            navController.navigate("detailNotifikasi/${notification.id}")
                        },
                        cardColor = cardColor,
                        txtColor = txtColor,
                        isDark = isDark,
                        coroutineScope = coroutineScope
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun NotificationCard(
    notification: AdminNotification,
    onDelete: () -> Unit,
    onMarkAsRead: () -> Unit,
    onDetailClick: () -> Unit,
    cardColor: Color,
    txtColor: Color,
    isDark: Boolean,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {

    fun formatRupiahLocal(amount: Int): String {
        val formatter = NumberFormat.getNumberInstance(Locale("in", "ID"))
        return "Rp ${formatter.format(amount)}"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!notification.read) {
                    onMarkAsRead()
                }
                coroutineScope.launch {
                    delay(150)
                    onDetailClick()
                }
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.read) cardColor else
                when (notification.type) {
                    "APPROVAL", "DUAL_APPROVAL_APPROVED" -> Color(0xFFE8F5E8) // Hijau muda
                    "REJECTION", "DUAL_APPROVAL_REJECTED" -> Color(0xFFFFEBEE) // Merah muda
                    else -> cardColor
                }
        ),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Badge jenis notifikasi
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            notification.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = txtColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = when (notification.type) {
                                        "REJECTION", "DUAL_APPROVAL_REJECTED" -> Color(0xFFFF5252) // Merah
                                        "APPROVAL", "DUAL_APPROVAL_APPROVED" -> Color(0xFF4CAF50) // Hijau
                                        else -> Color(0xFF2196F3) // Biru (default)
                                    },
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                when (notification.type) {
                                    "REJECTION", "DUAL_APPROVAL_REJECTED" -> "Ditolak"
                                    "APPROVAL", "DUAL_APPROVAL_APPROVED" -> "Disetujui"
                                    else -> "Info"
                                },
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // ✅ TAMBAH: Tampilkan status baca
                    if (!notification.read) {
                        Text(
                            "Baru",
                            color = Color.Red,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (!notification.read) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color.Red, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Content
            Text(
                notification.message,
                style = MaterialTheme.typography.bodyMedium,
                color = txtColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // ✅ TAMBAH: Tampilkan preview catatan persetujuan jika ada
            if ((notification.type == "APPROVAL" || notification.type == "DUAL_APPROVAL_APPROVED") && notification.isPinjamanDiubah) {
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    // Informasi perubahan jumlah pinjaman
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "💰 ",
                            color = if (isDark) Color(0xFFFFCC80) else Color(0xFFE65100),
                            fontSize = 14.sp
                        )
                        Text(
                            "${formatRupiahLocal(notification.pinjamanDiajukan)} → ${formatRupiahLocal(notification.pinjamanDisetujui)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDark) Color(0xFFFFCC80) else Color(0xFFE65100),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Tampilkan selisih
                    val selisih = notification.pinjamanDisetujui - notification.pinjamanDiajukan
                    if (selisih != 0) {
                        Text(
                            if (selisih < 0) "🔻 Pengurangan: ${formatRupiahLocal(-selisih)}"
                            else "🔺 Penambahan: ${formatRupiahLocal(selisih)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selisih < 0) Color(0xFFF44336) else Color(0xFF4CAF50),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Tampilkan preview catatan persetujuan jika ada
            if ((notification.type == "APPROVAL" || notification.type == "DUAL_APPROVAL_APPROVED") && notification.catatanPersetujuan.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "📝 ${notification.catatanPersetujuan}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color(0xFFB0BEC5) else Color(0xFF455A64),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Tampilkan preview alasan penolakan jika ada
            if ((notification.type == "REJECTION" || notification.type == "DUAL_APPROVAL_REJECTED") && notification.alasanPenolakan.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "❌ ${notification.alasanPenolakan}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color(0xFFEF9A9A) else Color(0xFFC62828),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatTimestamp(notification.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Hapus",
                        tint = Color.Gray
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("in", "ID"))
    return format.format(date)
}