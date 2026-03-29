package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import java.text.SimpleDateFormat
import java.util.*

/**
 * =========================================================================
 * PENGAWAS TRACKING SCREEN
 * =========================================================================
 *
 * Screen untuk Pengawas melacak lokasi Admin/Pimpinan/Koordinator.
 *
 * Fitur:
 * - Pilih user dari daftar
 * - Toggle tracking ON/OFF
 * - Tampilkan lokasi di Google Maps
 * - Info akurasi dan waktu terakhir update
 * =========================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PengawasTrackingScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val isDark by viewModel.isDarkMode
    val users by viewModel.allUsers.collectAsState()
    val userOnlineStatus by viewModel.userOnlineStatus.collectAsState()
    val trackingTarget by viewModel.trackingTarget.collectAsState()
    val targetLocation by viewModel.targetLocation.collectAsState()
    val isTrackingActive by viewModel.isTrackingActive.collectAsState()
    val trackingDeviceOnline by viewModel.trackingDeviceOnline.collectAsState()
    val trackingStartTime by viewModel.trackingStartTime.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // ✅ Timeout: hitung detik sejak tracking dimulai
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(trackingStartTime, targetLocation) {
        elapsedSeconds = 0L
        // Hanya hitung jika tracking aktif DAN belum dapat lokasi
        if (trackingStartTime > 0L && targetLocation == null) {
            while (true) {
                elapsedSeconds = (System.currentTimeMillis() - trackingStartTime) / 1000
                kotlinx.coroutines.delay(1000L)
            }
        }
    }

    var selectedUser by remember { mutableStateOf<UserInfo?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    val systemUiController = rememberSystemUiController()
    val bgColor = PengawasColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Load users saat pertama kali
    LaunchedEffect(Unit) {
        if (users.isEmpty()) {
            viewModel.loadAllUsers()
        }
        viewModel.loadUserPresenceStatus()
    }

    Scaffold(
        containerColor = PengawasColors.getBackground(isDark),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Lacak Lokasi",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // Matikan tracking saat keluar (opsional)
                        navController.popBackStack()
                    }) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Kembali",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PengawasColors.primary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ===== STATUS TRACKING =====
            if (isTrackingActive && trackingTarget != null) {
                // Panel status tracking aktif
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    color = Color(0xFF1B5E20).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF4CAF50))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Animated pulsing dot
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sedang Melacak: ${trackingTarget?.name ?: ""}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = PengawasColors.getTextPrimary(isDark),
                                modifier = Modifier.weight(1f)
                            )
                            // Tombol Stop
                            FilledTonalButton(
                                onClick = { viewModel.deactivateTracking() },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFFEF5350)
                                )
                            ) {
                                Icon(
                                    Icons.Rounded.Stop,
                                    contentDescription = "Stop",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Info role & cabang
                        Text(
                            text = "Role: ${trackingTarget?.role?.replaceFirstChar { it.uppercase() } ?: ""} • Cabang: ${trackingTarget?.cabangName ?: ""}",
                            fontSize = 13.sp,
                            color = PengawasColors.getTextMuted(isDark)
                        )

                        // Info lokasi
                        if (targetLocation != null) {
                            val loc = targetLocation ?: emptyMap()
                            Spacer(modifier = Modifier.height(4.dp))
                            val lat = (loc["latitude"] as? Number)?.toDouble() ?: 0.0
                            val lng = (loc["longitude"] as? Number)?.toDouble() ?: 0.0
                            val accuracy = (loc["accuracy"] as? Number)?.toFloat() ?: 0f
                            val lastUpdate = (loc["lastUpdated"] as? Number)?.toLong() ?: 0L

                            Text(
                                text = "📍 ${"%.6f".format(lat)}, ${"%.6f".format(lng)} (±${"%.0f".format(accuracy)}m)",
                                fontSize = 12.sp,
                                color = PengawasColors.getTextMuted(isDark)
                            )

                            if (lastUpdate > 0) {
                                val sdf = SimpleDateFormat("HH:mm:ss", Locale("id"))
                                Text(
                                    text = "🕐 Update terakhir: ${sdf.format(Date(lastUpdate))}",
                                    fontSize = 12.sp,
                                    color = PengawasColors.getTextMuted(isDark)
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(4.dp))
                            if (elapsedSeconds < 45) {
                                // Masih menunggu (normal)
                                Text(
                                    text = "⏳ Menunggu data lokasi... (${elapsedSeconds}s)",
                                    fontSize = 12.sp,
                                    color = Color(0xFFFFA000)
                                )
                                // Tampilkan warning jika device offline
                                if (trackingDeviceOnline == false) {
                                    Text(
                                        text = "⚠️ Device target terdeteksi offline",
                                        fontSize = 11.sp,
                                        color = Color(0xFFE65100)
                                    )
                                }
                            } else {
                                // TIMEOUT - device tidak merespons
                                Text(
                                    text = "❌ Device target tidak merespons",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD32F2F)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Kemungkinan aplikasi tidak aktif di HP target. Minta user untuk membuka aplikasi KoperasiKita terlebih dahulu, lalu coba lacak kembali.",
                                    fontSize = 11.sp,
                                    color = Color(0xFFD32F2F).copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                // ===== GOOGLE MAPS =====
                if (targetLocation != null) {
                    val mapLoc = targetLocation ?: emptyMap()
                    val lat = (mapLoc["latitude"] as? Number)?.toDouble() ?: 0.0
                    val lng = (mapLoc["longitude"] as? Number)?.toDouble() ?: 0.0
                    val targetName = trackingTarget?.name ?: ""

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 4.dp
                    ) {
                        GoogleMapView(
                            latitude = lat,
                            longitude = lng,
                            markerTitle = targetName,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    // Placeholder saat menunggu lokasi
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = PengawasColors.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Menunggu lokasi dari device target...",
                                color = PengawasColors.getTextMuted(isDark)
                            )
                            Text(
                                "Pastikan aplikasi masih terinstall",
                                fontSize = 12.sp,
                                color = PengawasColors.getTextMuted(isDark)
                            )
                        }
                    }
                }

            } else {
                // ===== DAFTAR USER UNTUK DILACAK =====
                Text(
                    text = "Pilih user yang ingin dilacak:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = PengawasColors.getTextPrimary(isDark),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PengawasColors.primary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(users) { user ->
                            TrackingUserCard(
                                user = user,
                                isDark = isDark,
                                isOnline = userOnlineStatus[user.uid] ?: false,
                                onTrackClick = {
                                    selectedUser = user
                                    showConfirmDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog konfirmasi
    if (showConfirmDialog && selectedUser != null) {
        val userToTrack = selectedUser ?: return
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Konfirmasi Lacak") },
            text = {
                Text("Aktifkan pelacakan lokasi untuk ${userToTrack.name} (${userToTrack.role})?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.activateTracking(userToTrack)
                        showConfirmDialog = false
                    }
                ) {
                    Text("Lacak", color = PengawasColors.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
fun TrackingUserCard(
    user: UserInfo,
    isDark: Boolean,
    isOnline: Boolean = false,
    onTrackClick: () -> Unit
) {
    val roleColor = when (user.role) {
        "pimpinan" -> Color(0xFF1565C0)
        "koordinator" -> Color(0xFF6A1B9A)
        else -> Color(0xFF2E7D32)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PengawasColors.getCard(isDark),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            // Avatar dengan online indicator
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(roleColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = user.name.take(1).uppercase(),
                        color = roleColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                // ✅ Online/Offline dot indicator
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = PengawasColors.getTextPrimary(isDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${user.role.replaceFirstChar { it.uppercase() }} • ${user.cabangName}",
                    fontSize = 12.sp,
                    color = PengawasColors.getTextMuted(isDark)
                )
                Text(
                    text = if (isOnline) "● Online" else "● Offline",
                    fontSize = 11.sp,
                    color = if (isOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD)
                )
            }

            // Tombol Lacak
            FilledTonalButton(
                onClick = onTrackClick,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = PengawasColors.primary.copy(alpha = 0.1f)
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Rounded.MyLocation,
                    contentDescription = "Lacak",
                    tint = PengawasColors.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Lacak",
                    color = PengawasColors.primary,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Google Map Composable menggunakan AndroidView
 */
@Composable
fun GoogleMapView(
    latitude: Double,
    longitude: Double,
    markerTitle: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }

    // Update marker saat lokasi berubah
    LaunchedEffect(latitude, longitude) {
        googleMap?.let { map ->
            map.clear()
            val position = LatLng(latitude, longitude)
            map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title(markerTitle)
            )
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(position, 17f)
            )
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                onCreate(null)
                onResume()
                getMapAsync { map ->
                    googleMap = map
                    map.uiSettings.isZoomControlsEnabled = true
                    map.uiSettings.isMyLocationButtonEnabled = false

                    val position = LatLng(latitude, longitude)
                    map.addMarker(
                        MarkerOptions()
                            .position(position)
                            .title(markerTitle)
                    )
                    map.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(position, 17f)
                    )
                }
            }
        },
        modifier = modifier
    )
}