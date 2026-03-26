package com.example.koperasikitagodangulu

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * =========================================================================
 * PENGAWAS COLORS - Color Palette untuk Pengawas
 * =========================================================================
 */
object PengawasColors {
    val primaryGradient = listOf(Color(0xFF7C3AED), Color(0xFF9333EA))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))
    val purpleGradient = listOf(Color(0xFF8B5CF6), Color(0xFFA78BFA))
    val orangeGradient = listOf(Color(0xFFF97316), Color(0xFFFB923C))
    val grayGradient = listOf(Color(0xFF64748B), Color(0xFF94A3B8))

    val darkBackground = Color(0xFF0F172A)
    val darkSurface = Color(0xFF1E293B)
    val darkCard = Color(0xFF334155)
    val darkBorder = Color(0xFF475569)
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFE2E8F0)

    val primary = Color(0xFF7C3AED)
    val success = Color(0xFF10B981)
    val warning = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)
    val info = Color(0xFF3B82F6)
    val teal = Color(0xFF14B8A6)
    val purple = Color(0xFF8B5CF6)

    val textPrimary = Color(0xFF1E293B)
    val textSecondary = Color(0xFF64748B)
    val textMuted = Color(0xFF94A3B8)

    // =========================================================================
    // DARK MODE HELPER FUNCTIONS
    // =========================================================================
    fun getBackground(isDark: Boolean): Color = if (isDark) darkBackground else lightBackground
    fun getCard(isDark: Boolean): Color = if (isDark) darkCard else lightSurface
    fun getSurface(isDark: Boolean): Color = if (isDark) darkSurface else lightSurface
    fun getBorder(isDark: Boolean): Color = if (isDark) darkBorder else lightBorder
    fun getTextPrimary(isDark: Boolean): Color = if (isDark) Color.White else textPrimary
    fun getTextSecondary(isDark: Boolean): Color = if (isDark) Color(0xFFCBD5E1) else textSecondary
    fun getTextMuted(isDark: Boolean): Color = if (isDark) Color(0xFF94A3B8) else textMuted
}

/**
 * =========================================================================
 * PENGAWAS TOP BAR - DENGAN NOTIFICATION BADGE & CONNECTION INDICATOR
 * =========================================================================
 *
 * PERBAIKAN:
 * ✅ Menambahkan tombol notifikasi dengan badge
 * ✅ Badge menampilkan jumlah pengajuan pending (dari pendingApprovalsPengawas)
 * ✅ Klik notifikasi navigasi ke pengawas_approvals
 * ✅ BARU: Indikator koneksi internet kecil (dot indicator)
 *
 * TIDAK PERLU:
 * - PengawasNotification.kt (model baru)
 * - Fungsi baru di PelangganViewModel
 *
 * Menggunakan pendingApprovalsPengawas yang SUDAH ADA di viewModel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PengawasTopBar(
    title: String,
    navController: NavHostController,
    viewModel: PelangganViewModel,
    showRefresh: Boolean = true,
    onRefresh: (() -> Unit)? = null,
    showNotifications: Boolean = true
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isLoading = remember { mutableStateOf(false) }

    // ✅ SIMPEL: Gunakan pendingApprovalsPengawas yang SUDAH ADA
    // Ini adalah jumlah pengajuan >= 3jt yang menunggu approval pengawas
    val pendingApprovals by viewModel.pendingApprovalsPengawas.collectAsState()
    val notificationCount = pendingApprovals.size

    // =========================================================================
    // ✅ BARU: Ambil status koneksi dari viewModel
    // =========================================================================
    val isOnline by viewModel.isOnline.collectAsState()

    // Dark Mode State
    val isDark by viewModel.isDarkMode
    var showLogoutAbsensiDialog by remember { mutableStateOf(false) }

    // Animation untuk refresh icon
    val infiniteTransition = rememberInfiniteTransition(label = "refresh")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Load data saat TopBar dibuat
    LaunchedEffect(Unit) {
        Log.d("PengawasTopBar", "🔔 Loading pending approvals...")
        viewModel.loadPendingApprovalsForPengawas()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                ambientColor = PengawasColors.primary.copy(alpha = 0.2f)
            ),
        color = Color.Transparent,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(PengawasColors.primaryGradient),
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                )
        ) {
            // Decorative circles
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .offset(x = (-30).dp, y = (-30).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 20.dp, y = (-10).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .statusBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Title with role badge
                Column {
                    // =========================================================================
                    // ✅ BARU: Row dengan badge PENGAWAS dan indikator koneksi
                    // =========================================================================
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "PENGAWAS",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        // =========================================================================
                        // ✅ BARU: Indikator koneksi kecil (dot indicator)
                        // =========================================================================
                        ConnectionIndicatorDot(isOnline = isOnline)

                        // Dark Mode Toggle
                        IconButton(
                            onClick = { viewModel.setDarkMode(!isDark) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = if (isDark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                                contentDescription = "Toggle Theme",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Refresh Button
                    if (showRefresh) {
                        IconButton(
                            onClick = {
                                if (!isLoading.value) {
                                    isLoading.value = true
                                    coroutineScope.launch {
                                        try {
                                            viewModel.smartRefresh()
                                            viewModel.loadPendingApprovalsForPengawas()
                                            onRefresh?.invoke()
                                            delay(500)
                                            Toast.makeText(context, "Data berhasil diperbarui", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Gagal refresh: ${e.message}", Toast.LENGTH_SHORT).show()
                                            Log.e("PengawasTopBar", "Error refreshing data: ${e.message}")
                                        } finally {
                                            isLoading.value = false
                                        }
                                    }
                                }
                            },
                            enabled = !isLoading.value
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color.White.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading.value) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = "Refreshing",
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .rotate(rotationAngle)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = "Refresh Data",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }

                    // =========================================================================
                    // ✅ NOTIFICATIONS BUTTON DENGAN BADGE
                    // =========================================================================
                    if (showNotifications) {
                        IconButton(onClick = {
                            navController.navigate("pengawas_approvals")
                            Log.d("PengawasTopBar", "🔔 Notification clicked: $notificationCount pending")
                        }) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color.White.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Notifications,
                                    contentDescription = "Notifikasi",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )

                                // Badge - tampilkan jumlah pengajuan pending
                                if (notificationCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .align(Alignment.TopEnd)
                                            .offset(x = 2.dp, y = (-2).dp)
                                            .background(
                                                Brush.linearGradient(PengawasColors.dangerGradient),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (notificationCount > 9) "9+"
                                            else notificationCount.toString(),
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Profile/Logout Button
                    IconButton(onClick = {
                        showLogoutAbsensiDialog = true
                    }) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Logout,
                                contentDescription = "Logout",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog pilihan Absen / Logout
    if (showLogoutAbsensiDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutAbsensiDialog = false },
            title = { Text("Keluar", fontWeight = FontWeight.Bold) },
            text = {
                Text("Apakah Anda ingin absen terlebih dahulu sebelum keluar?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutAbsensiDialog = false
                        navController.navigate("absensi")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PengawasColors.primaryGradient[0]
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Absen Dulu")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showLogoutAbsensiDialog = false
                        viewModel.clearAllCaches()
                        Firebase.auth.signOut()
                        navController.navigate("auth") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Langsung Keluar")
                }
            },
            containerColor = PengawasColors.getCard(isDark),
            titleContentColor = PengawasColors.getTextPrimary(isDark),
            textContentColor = PengawasColors.getTextPrimary(isDark)
        )
    }
}

/**
 * =========================================================================
 * ✅ BARU: Indikator koneksi kecil (dot indicator)
 * Tampilan minimalis agar tidak mengganggu UI
 * =========================================================================
 */
@Composable
private fun ConnectionIndicatorDot(isOnline: Boolean) {
    val dotColor = if (isOnline) {
        Color(0xFF10B981) // Green - Online
    } else {
        Color(0xFFEF4444) // Red - Offline
    }

    val icon = if (isOnline) {
        Icons.Rounded.Cloud
    } else {
        Icons.Rounded.CloudOff
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Dot indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )

        // Small icon
        Icon(
            imageVector = icon,
            contentDescription = if (isOnline) "Online" else "Offline",
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(14.dp)
        )
    }
}