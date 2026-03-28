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
import com.example.koperasikitagodangulu.services.LocationTrackingMonitor
import com.example.koperasikitagodangulu.services.LocationCheckWorker

/**
 * =========================================================================
 * KOORDINATOR TOP BAR - DENGAN NOTIFICATION BADGE & CONNECTION INDICATOR
 * =========================================================================
 *
 * TopBar untuk Koordinator dengan fitur:
 * ✅ Badge KOORDINATOR (bukan PENGAWAS)
 * ✅ Tombol notifikasi dengan badge pending approvals
 * ✅ Indikator koneksi internet
 * ✅ Warna biru (berbeda dengan Pengawas yang ungu)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoordinatorTopBar(
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

    // Gunakan pendingApprovalsPengawas yang sudah ada (shared dengan Pengawas)
    val pendingApprovals by viewModel.pendingApprovalsPengawas.collectAsState()
    val notificationCount = pendingApprovals.size

    // Status koneksi
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
        Log.d("KoordinatorTopBar", "🔔 Loading pending approvals...")
        viewModel.loadPendingApprovalsForPengawas()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                ambientColor = KoordinatorColors.primary.copy(alpha = 0.2f)
            ),
        color = Color.Transparent,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    // Gradient BIRU untuk Koordinator (bukan ungu seperti Pengawas)
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Text(
                                // KOORDINATOR badge (bukan PENGAWAS)
                                text = "KOORDINATOR",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Connection indicator
                        KoordinatorConnectionIndicatorDot(isOnline = isOnline)

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
                                            Log.e("KoordinatorTopBar", "Error refreshing data: ${e.message}")
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

                    // Notifications Button
                    if (showNotifications) {
                        IconButton(onClick = {
                            try {
                                navController.navigate("koordinator_approvals") {
                                    launchSingleTop = true
                                }
                            } catch (e: Exception) {
                                Log.e("KoordinatorTopBar", "Navigation error: ${e.message}")
                            }
                            Log.d("KoordinatorTopBar", "🔔 Notification clicked: $notificationCount pending")
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

                                // Badge
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
                        try {
                            navController.navigate("absensi") {
                                launchSingleTop = true
                            }
                        } catch (e: Exception) {
                            Log.e("KoordinatorTopBar", "Navigation error: ${e.message}")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KoordinatorColors.primary
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
                        LocationTrackingMonitor.stopMonitoring()
                        LocationCheckWorker.cancel(context)
                        Firebase.auth.signOut()
                        try {
                            navController.navigate("auth") {
                                popUpTo(0) { inclusive = true }
                            }
                        } catch (e: Exception) {
                            Log.e("KoordinatorTopBar", "Navigation error: ${e.message}")
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
            containerColor = KoordinatorColors.getCard(isDark),
            titleContentColor = KoordinatorColors.getTextPrimary(isDark),
            textContentColor = KoordinatorColors.getTextPrimary(isDark)
        )
    }
}

/**
 * Connection indicator untuk Koordinator
 */
@Composable
private fun KoordinatorConnectionIndicatorDot(isOnline: Boolean) {
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
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
        Icon(
            imageVector = icon,
            contentDescription = if (isOnline) "Online" else "Offline",
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(14.dp)
        )
    }
}