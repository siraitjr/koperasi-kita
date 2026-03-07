package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import com.example.koperasikitagodangulu.models.BroadcastMessage
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import android.util.Log
import android.widget.Toast
import com.example.koperasikitagodangulu.utils.backgroundColor
import com.example.koperasikitagodangulu.utils.textColor
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import java.text.NumberFormat
import java.util.Locale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import com.example.koperasikitagodangulu.services.LocationTrackingMonitor
import com.example.koperasikitagodangulu.services.LocationCheckWorker
import androidx.compose.material.icons.rounded.SwapHoriz
import com.example.koperasikitagodangulu.TakeoverStatus

// Modern Color Palette
object AdminColors {
    // Gradient Colors
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val purpleGradient = listOf(Color(0xFF8B5CF6), Color(0xFFA78BFA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))
    val roseGradient = listOf(Color(0xFFF43F5E), Color(0xFFFB7185))
    val orangeGradient = listOf(Color(0xFFF97316), Color(0xFFFB923C))

    // Dark Mode Colors
    val darkSurface = Color(0xFF1E1E2E)
    val darkCard = Color(0xFF2D2D3F)
    val darkBorder = Color(0xFF3D3D4F)

    // Light Mode Colors
    val lightSurface = Color(0xFFF8FAFC)
    val lightCard = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFE2E8F0)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun AdminHomeScreen(navController: NavController, viewModel: PelangganViewModel) {
//    val systemUiController = rememberSystemUiController()
    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) AdminColors.darkCard else AdminColors.lightCard
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF64748B)

    val adminNotifications by viewModel.adminNotifications.collectAsState()
    val unreadNotifications = adminNotifications.filter { !it.read }
    val auth = Firebase.auth
    val isTakeoverMode by viewModel.isTakeoverMode.collectAsState()
    val takeoverAdminName by viewModel.takeoverAdminName.collectAsState()
    val takeoverStatus by viewModel.takeoverStatus.collectAsState()
    val email = auth.currentUser?.email ?: "Tidak ada email"
    val context = LocalContext.current
    var isScreenFocused by remember { mutableStateOf(true) }

    val isOnline by viewModel.isOnline.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val unsyncedCount = viewModel.getUnsyncedCount()
    val activeBroadcasts by viewModel.activeBroadcasts.collectAsState()
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

    // Animation states
    var isVisible by remember { mutableStateOf(false) }

    // State untuk dialog biaya awal
    var showBiayaAwalDialog by remember { mutableStateOf(false) }
    var biayaAwalInput by remember { mutableStateOf("") }

    // State untuk foto profil
    val adminPhotoUrl by viewModel.adminPhotoUrl.collectAsState()
    var showPhotoUploadDialog by remember { mutableStateOf(false) }
    var isUploadingPhoto by remember { mutableStateOf(false) }
    var showPhotoOptionsDialog by remember { mutableStateOf(false) }
    var showFullPhotoDialog by remember { mutableStateOf(false) }
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) AdminColors.darkSurface else AdminColors.lightSurface
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

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

    LaunchedEffect(Unit) {
        isVisible = true
        viewModel.loadAdminPhotoUrl()
        viewModel.loadActiveBroadcasts()
        viewModel.cleanupStuckMenungguPencairan() // ← Tambah di sini
    }

    LaunchedEffect(isScreenFocused) {
        if (isScreenFocused) {
            Log.d("Notification", "🔄 AdminHomeScreen focused, refreshing notifications")
            viewModel.loadAdminNotifications()
            delay(1000)
            viewModel.loadAdminNotifications()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startNetworkMonitoring()

        // ✅ FIX: Start remote takeover listener agar admin bisa merespons sinyal pimpinan.
        // Listener ini HARUS dipasang di sini karena auto-login tidak melewati proceedWithLogin().
        // Guard di dalam fungsi sudah mencegah duplikasi dan mode takeover.
        viewModel.startRemoteTakeoverListener {
            LocationTrackingMonitor.stopMonitoring()
            LocationCheckWorker.cancel(context)
            auth.signOut()
            Toast.makeText(
                context,
                "Pimpinan mengambil alih akun Anda. Anda akan logout otomatis.",
                Toast.LENGTH_LONG
            ).show()
            navController.navigate("auth") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    LaunchedEffect(adminNotifications) {
        Log.d("Notification", "📢 Total notifikasi: ${adminNotifications.size}")
        Log.d("Notification", "📢 Unread notifikasi: ${unreadNotifications.size}")
        adminNotifications.forEach { notif ->
            Log.d("Notification", "   - ${notif.type}: ${notif.title} (read: ${notif.read})")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.debugNotificationStructure()
        viewModel.loadAdminNotifications()
    }

//    SideEffect {
//        systemUiController.setSystemBarsColor(
//            color = bgColor,
//            darkIcons = !isDark
//        )
//        systemUiController.setStatusBarColor(
//            color = Color.Transparent,
//            darkIcons = !isDark
//        )
//    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = systemBarsPadding.calculateTopPadding())
        ) {
            // Modern Header
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500)) + slideInVertically(
                    initialOffsetY = { -50 },
                    animationSpec = tween(500)
                )
            ) {
                ModernHeader(
                    email = email,
                    isDark = isDark,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor,
                    cardColor = cardColor,
                    unreadCount = unreadNotifications.size,
                    isOnline = isOnline,
                    isSyncing = isSyncing,
                    unsyncedCount = unsyncedCount,
                    adminPhotoUrl = adminPhotoUrl,
                    isUploadingPhoto = isUploadingPhoto,
                    onDarkModeToggle = { viewModel.setDarkMode(it) },
                    onNotificationClick = { navController.navigate("notifikasi") },
                    onLogoutClick = {
                        if (isTakeoverMode) {
                            // Dalam mode takeover, kembali ke akun pimpinan
                            viewModel.returnToPimpinanAccount(
                                navController = navController,
                                context = context,
                                onError = { msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            )
                        } else {
                            LocationTrackingMonitor.stopMonitoring()
                            LocationCheckWorker.cancel(context)
                            auth.signOut()
                            Toast.makeText(context, "Logout sukses", Toast.LENGTH_SHORT).show()
                            navController.navigate("auth") {
                                popUpTo("dashboard") { inclusive = true }
                            }
                        }
                    },
                    onManualSync = { viewModel.manualSync() },
                    onWelcomeCardClick = { showBiayaAwalDialog = true },
                    onAvatarClick = {
                        if (!adminPhotoUrl.isNullOrBlank()) {
                            // Jika sudah ada foto, tampilkan dialog pilihan
                            showPhotoOptionsDialog = true
                        } else {
                            // Jika belum ada foto, langsung upload
                            photoPickerLauncher.launch("image/*")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ✅ BANNER TAKEOVER MODE
            if (isTakeoverMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFFEF3C7)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Mode Pimpinan — ${takeoverAdminName ?: "Admin"}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF92400E),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                viewModel.returnToPimpinanAccount(
                                    navController = navController,
                                    context = context,
                                    onError = { msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF59E0B)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            enabled = takeoverStatus !is TakeoverStatus.Restoring
                        ) {
                            if (takeoverStatus is TakeoverStatus.Restoring) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Kembali", fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ✅ BARU: Broadcast Banner
            if (activeBroadcasts.isNotEmpty()) {
                BroadcastBannerSection(
                    broadcasts = activeBroadcasts,
                    isDark = isDark
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Menu Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = systemBarsPadding.calculateBottomPadding() + 16.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                val menuItems = listOf(
                    ModernMenuItem(
                        title = "Tambah Nasabah",
                        subtitle = "Ajukan pinjaman nasabah",
                        icon = Icons.Rounded.PersonAdd,
                        gradient = AdminColors.infoGradient
                    ) { navController.navigate("tambahPelanggan") },
                    ModernMenuItem(
                        title = "Input Pembayaran",
                        subtitle = "Catat pembayaran cepat",
                        icon = Icons.Rounded.Payment,
                        gradient = AdminColors.successGradient
                    ) { navController.navigate("inputPembayaran") },
                    ModernMenuItem(
                        title = "Semua Nasabah",
                        subtitle = "Lihat daftar lengkap",
                        icon = Icons.Rounded.Groups,
                        gradient = AdminColors.tealGradient
                    ) { navController.navigate("daftarPelanggan") },
                    ModernMenuItem(
                        title = "Kunjungan Hari Ini",
                        subtitle = "Jadwal kutip nasabah",
                        icon = Icons.Rounded.Today,
                        gradient = AdminColors.warningGradient
                    ) { navController.navigate("pelangganKutip") },
                    ModernMenuItem(
                        title = "Ringkasan",
                        subtitle = "Dashboard analitik",
                        icon = Icons.Rounded.Analytics,
                        gradient = AdminColors.purpleGradient
                    ) { navController.navigate("ringkasan") },
                    ModernMenuItem(
                        title = "Laporan Harian",
                        subtitle = "Rekap transaksi",
                        icon = Icons.Rounded.Assessment,
                        gradient = AdminColors.roseGradient
                    ) { navController.navigate("laporanHarian") },
                    ModernMenuItem(
                        title = "Kalkulator",
                        subtitle = "Hitung pinjaman",
                        icon = Icons.Rounded.Calculate,
                        gradient = AdminColors.orangeGradient
                    ) { navController.navigate("kalkulatorPinjaman") },
                    ModernMenuItem(
                        title = "Cari Nasabah",
                        subtitle = "Cari Nasabah Berdasarkan KTP",
                        icon = Icons.Rounded.Search,
                        gradient = AdminColors.primaryGradient
                    ) { navController.navigate("cari_pelanggan") }
                )

                itemsIndexed(menuItems) { index, item ->
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(
                            animationSpec = tween(
                                durationMillis = 400,
                                delayMillis = 100 + (index * 50)
                            )
                        ) + scaleIn(
                            initialScale = 0.8f,
                            animationSpec = tween(
                                durationMillis = 400,
                                delayMillis = 100 + (index * 50)
                            )
                        )
                    ) {
                        ModernMenuCard(item = item, isDark = isDark)
                    }
                }
            }
            // Dialog Input Biaya Awal
            if (showBiayaAwalDialog) {
                AlertDialog(
                    onDismissRequest = { showBiayaAwalDialog = false },
                    title = {
                        Text(
                            text = "Masukkan Biaya Awal",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = "Masukkan jumlah biaya awal hari ini",
                                color = subtitleColor,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = biayaAwalInput,
                                onValueChange = { input ->
                                    val clean = input.filter { it.isDigit() }
                                    biayaAwalInput = if (clean.isNotEmpty()) {
                                        NumberFormat.getNumberInstance(Locale("id", "ID"))
                                            .format(clean.toLongOrNull() ?: 0)
                                    } else ""
                                },
                                label = { Text("Jumlah (Rp)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val jumlah = biayaAwalInput.replace("[^\\d]".toRegex(), "").toIntOrNull() ?: 0
                                if (jumlah > 0) {
                                    viewModel.simpanBiayaAwal(jumlah) { success, message ->
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        if (success) {
                                            showBiayaAwalDialog = false
                                            biayaAwalInput = ""
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, "Masukkan jumlah yang valid", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6366F1)
                            )
                        ) {
                            Text("OK")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showBiayaAwalDialog = false
                            biayaAwalInput = ""
                        }) {
                            Text("Batal")
                        }
                    },
                    containerColor = cardColor,
                    titleContentColor = txtColor,
                    textContentColor = txtColor
                )
            }
            // Dialog Pilihan Foto (Lihat / Ubah)
            if (showPhotoOptionsDialog) {
                AlertDialog(
                    onDismissRequest = { showPhotoOptionsDialog = false },
                    title = {
                        Text(
                            text = "Foto Profil",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Tombol Lihat Foto
                            OutlinedButton(
                                onClick = {
                                    showPhotoOptionsDialog = false
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

                            // Tombol Ubah Foto
                            Button(
                                onClick = {
                                    showPhotoOptionsDialog = false
                                    photoPickerLauncher.launch("image/*")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF6366F1)
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Ubah Foto")
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showPhotoOptionsDialog = false }) {
                            Text("Batal")
                        }
                    },
                    containerColor = cardColor,
                    titleContentColor = txtColor,
                    textContentColor = txtColor
                )
            }

            // Dialog Lihat Foto Besar
            if (showFullPhotoDialog && !adminPhotoUrl.isNullOrBlank()) {
                AlertDialog(
                    onDismissRequest = { showFullPhotoDialog = false },
                    title = {
                        Text(
                            text = "Foto Profil",
                            fontWeight = FontWeight.Bold
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
                                model = ImageRequest.Builder(LocalContext.current)
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
                                containerColor = Color(0xFF6366F1)
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
                    containerColor = cardColor,
                    titleContentColor = txtColor,
                    textContentColor = txtColor
                )
            }
        }
    }
}

@Composable
private fun ModernHeader(
    email: String,
    isDark: Boolean,
    txtColor: Color,
    subtitleColor: Color,
    cardColor: Color,
    unreadCount: Int,
    isOnline: Boolean,
    isSyncing: Boolean,
    unsyncedCount: Int,
    adminPhotoUrl: String?,                    // <-- TAMBAH
    isUploadingPhoto: Boolean,                 // <-- TAMBAH
    onDarkModeToggle: (Boolean) -> Unit,
    onNotificationClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onManualSync: () -> Unit,
    onWelcomeCardClick: () -> Unit,
    onAvatarClick: () -> Unit                  // <-- TAMBAH
) {
    val borderColor = if (isDark) AdminColors.darkBorder else AdminColors.lightBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Top Actions Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dark Mode Toggle with label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isDark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                    contentDescription = null,
                    tint = if (isDark) Color(0xFFFBBF24) else Color(0xFFF59E0B),
                    modifier = Modifier.size(20.dp)
                )
                Switch(
                    checked = isDark,
                    onCheckedChange = onDarkModeToggle,
                    modifier = Modifier.scale(0.8f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF8B5CF6),
                        checkedTrackColor = Color(0xFF8B5CF6).copy(alpha = 0.5f),
                        uncheckedThumbColor = Color(0xFF6366F1),
                        uncheckedTrackColor = Color(0xFF6366F1).copy(alpha = 0.3f)
                    )
                )
            }

            // Right Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sync Status
                ModernSyncStatusIndicator(
                    isOnline = isOnline,
                    isSyncing = isSyncing,
                    unsyncedCount = unsyncedCount,
                    onManualSync = onManualSync,
                    isDark = isDark
                )

                // Notification Button
                IconButton(onClick = onNotificationClick) {
                    Box {
                        Icon(
                            imageVector = Icons.Rounded.Notifications,
                            contentDescription = "Notifikasi",
                            tint = txtColor,
                            modifier = Modifier.size(26.dp)
                        )
                        if (unreadCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(AdminColors.roseGradient)
                                    )
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                            ) {
                                Text(
                                    text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                // Logout Button
                IconButton(onClick = onLogoutClick) {
                    Icon(
                        imageVector = Icons.Rounded.Logout,
                        contentDescription = "Logout",
                        tint = Color(0xFFF43F5E),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onWelcomeCardClick() }
                .shadow(
                    elevation = 20.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Color(0xFF6366F1).copy(alpha = 0.3f),
                    spotColor = Color(0xFF6366F1).copy(alpha = 0.3f)
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF6366F1),
                                Color(0xFF8B5CF6),
                                Color(0xFFA855F7)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                        )
                    )
            ) {
                // Decorative circles
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .offset(x = (-30).dp, y = (-30).dp)
                        .background(
                            Color.White.copy(alpha = 0.1f),
                            CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 20.dp, y = 20.dp)
                        .background(
                            Color.White.copy(alpha = 0.1f),
                            CircleShape
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Avatar - DENGAN FOTO PROFIL
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f))
                                .border(
                                    width = 2.dp,
                                    color = Color.White.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                                .clickable { onAvatarClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isUploadingPhoto) {
                                // Loading indicator saat upload
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else if (!adminPhotoUrl.isNullOrBlank()) {
                                // Tampilkan foto profil
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
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
                                // Default icon jika tidak ada foto
                                Icon(
                                    imageVector = Icons.Rounded.AdminPanelSettings,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // Badge kamera kecil di pojok
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(18.dp)
                                    .background(Color.White, CircleShape)
                                    .padding(2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CameraAlt,
                                    contentDescription = "Ubah foto",
                                    tint = Color(0xFF6366F1),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = "Selamat Datang! 👋",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Dashboard PDL",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Info Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Email,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = email,
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Kelola nasabah, pembayaran, dan laporan koperasi",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

data class ModernMenuItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val gradient: List<Color>,
    val onClick: () -> Unit
)

@Composable
private fun ModernMenuCard(item: ModernMenuItem, isDark: Boolean) {
    val coroutineScope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    val cardBgColor = if (isDark) AdminColors.darkCard else Color.White
    val borderColor = if (isDark) AdminColors.darkBorder else Color(0xFFE2E8F0)
    val titleColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF64748B)

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .scale(scale)
            .shadow(
                elevation = if (pressed) 4.dp else 12.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = item.gradient[0].copy(alpha = 0.2f),
                spotColor = item.gradient[0].copy(alpha = 0.2f)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                coroutineScope.launch {
                    pressed = true
                    delay(100)
                    item.onClick()
                    pressed = false
                }
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                colors = listOf(borderColor, borderColor)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Icon with gradient background
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        Brush.linearGradient(item.gradient),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            Column {
                Text(
                    text = item.title,
                    color = titleColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.subtitle,
                    color = subtitleColor,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ModernSyncStatusIndicator(
    isOnline: Boolean,
    isSyncing: Boolean,
    unsyncedCount: Int,
    onManualSync: () -> Unit,
    isDark: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sync")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val (bgColor, iconColor, icon) = when {
        !isOnline -> Triple(
            Color(0xFFFEE2E2),
            Color(0xFFEF4444),
            Icons.Rounded.CloudOff
        )
        isSyncing -> Triple(
            Color(0xFFDBEAFE),
            Color(0xFF3B82F6),
            Icons.Rounded.Sync
        )
        unsyncedCount > 0 -> Triple(
            Color(0xFFFEF3C7),
            Color(0xFFF59E0B),
            Icons.Rounded.CloudSync
        )
        else -> Triple(
            Color(0xFFD1FAE5),
            Color(0xFF10B981),
            Icons.Rounded.CloudDone
        )
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDark) bgColor.copy(alpha = 0.2f) else bgColor)
            .clickable(
                enabled = !isSyncing && (unsyncedCount > 0 || !isOnline),
                onClick = onManualSync
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier
                    .size(18.dp)
                    .then(
                        if (isSyncing) Modifier.graphicsLayer { rotationZ = rotation }
                        else Modifier
                    )
            )

            if (unsyncedCount > 0 && !isSyncing) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(
                            Brush.linearGradient(AdminColors.roseGradient),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (unsyncedCount > 9) "9+" else unsyncedCount.toString(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// =========================================================================
// BROADCAST BANNER SECTION
// =========================================================================

@Composable
private fun BroadcastBannerSection(
    broadcasts: List<BroadcastMessage>,
    isDark: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        broadcasts.forEach { broadcast ->
            BroadcastBannerCard(
                broadcast = broadcast,
                isDark = isDark
            )
        }
    }
}

@Composable
private fun BroadcastBannerCard(
    broadcast: BroadcastMessage,
    isDark: Boolean
) {
    val cardColor = if (isDark) Color(0xFF3D3D4F) else Color(0xFFFFF8E1)
    val iconBgColor = if (isDark) Color(0xFFFF9800).copy(alpha = 0.2f) else Color(0xFFFFE0B2)
    val titleColor = if (isDark) Color.White else Color(0xFF1E293B)
    val messageColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF424242)
    val senderColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF757575)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(iconBgColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Campaign,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = broadcast.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = titleColor
                )
                Text(
                    text = broadcast.message,
                    fontSize = 13.sp,
                    color = messageColor,
                    lineHeight = 18.sp
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = senderColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = broadcast.senderName,
                        fontSize = 11.sp,
                        color = senderColor
                    )
                }
            }
        }
    }
}

// Keep the old SyncStatusIndicator for backward compatibility
@Composable
fun SyncStatusIndicator(
    isOnline: Boolean,
    isSyncing: Boolean,
    unsyncedCount: Int,
    onManualSync: () -> Unit,
    textColor: Color
) {
    ModernSyncStatusIndicator(
        isOnline = isOnline,
        isSyncing = isSyncing,
        unsyncedCount = unsyncedCount,
        onManualSync = onManualSync,
        isDark = textColor == Color.White
    )
}