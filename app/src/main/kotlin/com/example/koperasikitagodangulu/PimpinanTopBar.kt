package com.example.koperasikitagodangulu

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
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
import android.widget.Toast
import android.util.Log
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Logout
import com.example.koperasikitagodangulu.services.LocationTrackingMonitor
import com.example.koperasikitagodangulu.services.LocationCheckWorker
import com.google.accompanist.systemuicontroller.rememberSystemUiController

/**
 * =========================================================================
 * PIMPINAN TOP BAR - DENGAN CONNECTION INDICATOR & DARK MODE TOGGLE
 * =========================================================================
 *
 * PERBAIKAN:
 * ✅ BARU: Indikator koneksi internet kecil (dot indicator)
 * ✅ BARU: Toggle switch untuk dark mode
 * ✅ Tidak mengubah UI/fungsi lain yang sudah ada
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanTopBar(
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

    // State untuk notifikasi
    val notificationCount = remember { mutableStateOf(0) }

    // =========================================================================
    // ✅ BARU: Ambil status koneksi dari viewModel
    // =========================================================================
    val isOnline by viewModel.isOnline.collectAsState()

    // =========================================================================
    // ✅ BARU: Ambil status dark mode dari viewModel
    // =========================================================================
    val isDark by viewModel.isDarkMode

    // ✅ Set status bar & navigation bar sesuai tema dashboard
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // =========================================================================
    // ✅ BARU: State untuk foto profil
    // =========================================================================
    val adminPhotoUrl by viewModel.adminPhotoUrl.collectAsState()
    var showProfileOptionsDialog by remember { mutableStateOf(false) }
    var showFullPhotoDialog by remember { mutableStateOf(false) }
    var isUploadingPhoto by remember { mutableStateOf(false) }

    // Launcher untuk pick image
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isUploadingPhoto = true
            viewModel.uploadAdminPhoto(
                imageUri = it,
                onSuccess = { url ->
                    isUploadingPhoto = false
                    Toast.makeText(context, "Foto profil berhasil diupload", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    isUploadingPhoto = false
                    Toast.makeText(context, "Gagal upload: $error", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // TAMBAH INI:
    val adminNotifications by viewModel.adminNotifications.collectAsState()

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

    // Load notifikasi sekali saat TopBar dibuat
    LaunchedEffect(Unit) {
        Log.d("TopBar", "🔔 Loading notifications...")
        viewModel.loadAdminNotifications()
        viewModel.loadAdminPhotoUrl()
    }

    // Listen untuk perubahan di adminNotifications
    LaunchedEffect(adminNotifications) {
        val unreadNotifications = adminNotifications.filter { !it.read }
        val relevantNotifications = unreadNotifications.filter { notification ->
            notification.type == "NEW_PENGAJUAN" || notification.type == "TOPUP_APPROVAL"
        }
        notificationCount.value = relevantNotifications.size

        Log.d("TopBar", "📊 Total: ${adminNotifications.size}, Relevant: ${relevantNotifications.size}")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                ambientColor = PimpinanColors.primary.copy(alpha = 0.2f)
            ),
        color = Color.Transparent,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(PimpinanColors.primaryGradient),
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
                Column {
                    // =========================================================================
                    // ✅ BARIS 1: Badge PIMPINAN + Connection Indicator + Dark Mode Toggle
                    // =========================================================================
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Badge PIMPINAN
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.White.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "PIMPINAN",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Connection indicator
                        PimpinanConnectionIndicatorDot(isOnline = isOnline)

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

                    // =========================================================================
                    // ✅ BARIS 2: Title (Dashboard / Approval / Laporan)
                    // =========================================================================
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
//                    // =========================================================================
//                    // ✅ BARU: Dark Mode Toggle Switch
//                    // =========================================================================
//                    Row(
//                        verticalAlignment = Alignment.CenterVertically,
//                        horizontalArrangement = Arrangement.spacedBy(2.dp)
//                    ) {
//                        Icon(
//                            imageVector = if (isDark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
//                            contentDescription = if (isDark) "Dark Mode" else "Light Mode",
//                            tint = Color.White,
//                            modifier = Modifier.size(16.dp)
//                        )
//                        Switch(
//                            checked = isDark,
//                            onCheckedChange = { viewModel.setDarkMode(it) },
//                            modifier = Modifier.scale(0.7f),
//                            colors = SwitchDefaults.colors(
//                                checkedThumbColor = Color.White,
//                                checkedTrackColor = Color.White.copy(alpha = 0.3f),
//                                uncheckedThumbColor = Color.White,
//                                uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
//                            )
//                        )
//                    }

                    // Refresh Button
                    if (showRefresh) {
                        IconButton(
                            onClick = {
                                if (!isLoading.value) {
                                    isLoading.value = true
                                    coroutineScope.launch {
                                        try {
                                            viewModel.smartRefresh()
                                            onRefresh?.invoke()
                                            delay(500)
                                            Toast.makeText(context, "Data berhasil diperbarui", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Gagal refresh: ${e.message}", Toast.LENGTH_SHORT).show()
                                            Log.e("PimpinanTopBar", "Error refreshing data: ${e.message}")
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
                            navController.navigate("pimpinan_approvals")
                            Log.d("TopBar", "🔔 Notification clicked: ${notificationCount.value} unread")
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
                                if (notificationCount.value > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .align(Alignment.TopEnd)
                                            .offset(x = 2.dp, y = (-2).dp)
                                            .background(
                                                Brush.linearGradient(PimpinanColors.dangerGradient),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (notificationCount.value > 9) "9+"
                                            else notificationCount.value.toString(),
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Profile/Logout Button - DENGAN FOTO PROFIL
                    IconButton(onClick = {
                        showProfileOptionsDialog = true
                    }) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f))
                                .border(
                                    width = 1.5.dp,
                                    color = Color.White.copy(alpha = 0.5f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isUploadingPhoto) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else if (!adminPhotoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(adminPhotoUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Foto Profil",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.AccountCircle,
                                    contentDescription = "Profile",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // =========================================================================
    // ✅ BARU: Dialog Pilihan Profil
    // =========================================================================
    if (showProfileOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showProfileOptionsDialog = false },
            title = {
                Text(
                    text = "Profil",
                    fontWeight = FontWeight.Bold,
                    color = PimpinanColors.getTextPrimary(isDark)
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Jika sudah ada foto, tampilkan opsi Lihat Foto
                    if (!adminPhotoUrl.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = {
                                showProfileOptionsDialog = false
                                showFullPhotoDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Lihat Foto")
                        }
                    }

                    // Tombol Upload/Ubah Foto
                    Button(
                        onClick = {
                            showProfileOptionsDialog = false
                            photoPickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PimpinanColors.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (adminPhotoUrl.isNullOrBlank()) "Upload Foto" else "Ubah Foto")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Tombol Keluar
                    OutlinedButton(
                        onClick = {
                            showProfileOptionsDialog = false
                            viewModel.clearAllCaches()
                            viewModel.clearAdminPhotoCache()
                            LocationTrackingMonitor.stopMonitoring()
                            LocationCheckWorker.cancel(context)
                            Firebase.auth.signOut()
                            navController.navigate("auth") {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PimpinanColors.danger
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Keluar")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showProfileOptionsDialog = false }) {
                    Text("Batal")
                }
            },
            containerColor = PimpinanColors.getCard(isDark),
            titleContentColor = PimpinanColors.getTextPrimary(isDark),
            textContentColor = PimpinanColors.getTextPrimary(isDark)
        )
    }

    // =========================================================================
    // ✅ BARU: Dialog Lihat Foto Besar
    // =========================================================================
    if (showFullPhotoDialog && !adminPhotoUrl.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showFullPhotoDialog = false },
            title = {
                Text(
                    text = "Foto Profil",
                    fontWeight = FontWeight.Bold,
                    color = PimpinanColors.getTextPrimary(isDark)
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(adminPhotoUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Foto Profil",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFullPhotoDialog = false
                        photoPickerLauncher.launch("image/*")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PimpinanColors.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ubah Foto")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullPhotoDialog = false }) {
                    Text("Tutup")
                }
            },
            containerColor = PimpinanColors.getCard(isDark),
            titleContentColor = PimpinanColors.getTextPrimary(isDark),
            textContentColor = PimpinanColors.getTextPrimary(isDark)
        )
    }
}

/**
 * =========================================================================
 * ✅ BARU: Indikator koneksi kecil (dot indicator) untuk Pimpinan
 * Tampilan minimalis agar tidak mengganggu UI
 * =========================================================================
 */
@Composable
private fun PimpinanConnectionIndicatorDot(isOnline: Boolean) {
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