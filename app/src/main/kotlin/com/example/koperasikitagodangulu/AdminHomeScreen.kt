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
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import com.example.koperasikitagodangulu.services.LocationTrackingMonitor
import com.example.koperasikitagodangulu.services.LocationCheckWorker
import com.example.koperasikitagodangulu.offline.SyncStatusBlock
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

// =========================================================================
// ✅ REDESIGN BERANDA — referensi screenshot pimpinan (07 Jun 2026)
// -------------------------------------------------------------------------
// Soft off-white background, hero gradient biru→ungu, "Ringkasan Hari Ini"
// 4-kolom (kolom Setoran di-highlight pastel), grid menu 2 kolom dgn arrow,
// dan Material 3 NavigationBar (pill indigo pada item terpilih).
//
// DATA INTEGRITY (CLAUDE.md §10 + commit 4dcec3b): TIDAK ada DB hit baru,
// TIDAK ada perubahan ViewModel/listener. Semua metrik di-derive O(N) dari
// state ViewModel yang sudah ada lalu di-bind ke kartu baru.
// =========================================================================
private object HomeTheme {
    val screenBg = Color(0xFFF8F9FA)        // soft off-white
    val cardWhite = Color(0xFFFFFFFF)
    val cardBorder = Color(0xFFEEF1F5)
    val titleText = Color(0xFF1E293B)
    val subtitleText = Color(0xFF94A3B8)
    val indigo = Color(0xFF6366F1)
    val coralBg = Color(0xFFFFF1F2)         // pastel pink utk kolom Setoran
    val coral = Color(0xFFF43F5E)
    val heroStart = Color(0xFF6366F1)
    val heroMid = Color(0xFF8B5CF6)
    val heroEnd = Color(0xFFA855F7)
}

private val rupiahFormatter: NumberFormat = NumberFormat.getNumberInstance(Locale("in", "ID"))
private fun formatRupiahHome(value: Long): String = "Rp " + rupiahFormatter.format(value)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun AdminHomeScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    // ✅ Hoisted (pimpinan 07 Jun 2026): bottom bar + dialog profil tinggal di
    // AppNavigation. AdminHomeScreen menerima callback klik avatar & flag upload
    // dari parent supaya hero card & dialog konsisten.
    onAvatarClick: () -> Unit = {},
    isUploadingPhoto: Boolean = false
) {
    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) AdminColors.darkCard else HomeTheme.cardWhite
    val txtColor = if (isDark) Color.White else HomeTheme.titleText
    val subtitleColor = if (isDark) Color(0xFFA1A1AA) else HomeTheme.subtitleText

    val adminNotifications by viewModel.adminNotifications.collectAsState()
    val unreadNotifications = adminNotifications.filter { !it.read }
    val auth = Firebase.auth
    val isTakeoverMode by viewModel.isTakeoverMode.collectAsState()
    val takeoverAdminName by viewModel.takeoverAdminName.collectAsState()
    val takeoverStatus by viewModel.takeoverStatus.collectAsState()
    val email = auth.currentUser?.email ?: "Tidak ada email"
    val context = LocalContext.current

    val isOnline by viewModel.isOnline.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    var unsyncedCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        unsyncedCount = viewModel.getUnsyncedCount()
    }
    val activeBroadcasts by viewModel.activeBroadcasts.collectAsState()
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()

    // =========================================================================
    // ✅ KONSOLIDASI METRIK PIMPINAN 07 JUN 2026 (UI-only, tidak menyentuh VM)
    // -------------------------------------------------------------------------
    // Metrik dari layar lain, di-derive di sini sebagai pure UI consumption dari
    // state ViewModel yang sudah ada (daftarPelanggan + pelunasanEksternal).
    // Formula 1:1 dengan layar sumber, sehingga angka SELALU sama:
    //   - Storting Hari Ini  → RingkasanDashboardScreen.kt:160-187 (totalTagihanHariIni).
    //   - Drop Hari Ini      → RingkasanDashboardScreen.kt:197-205 (nasabahBaruHariIni).
    //   - Total Nasabah Aktif→ DaftarPelangganScreen.kt:148 (getFilteredPelanggan AKTIF).
    //   - Pembayaran Hari Ini→ count entri pembayaran (utama+sub) bertanggal hari ini.
    // SEMUA O(N) atas SnapshotStateList in-memory — TANPA DB hit baru (commit 4dcec3b).
    // =========================================================================
    val daftarPelangganList = viewModel.daftarPelanggan
    val pelunasanEksternal by viewModel.pelunasanEksternalHariIni.collectAsState()
    val todayStr = remember { todayIndo() }

    val stortingHariIni: Long = remember(daftarPelangganList.toList(), pelunasanEksternal, todayStr) {
        val tagihanCicilan = daftarPelangganList.flatMap { pelanggan ->
            pelanggan.pembayaranList.flatMap { pem ->
                val utama = if (pem.tanggal == todayStr) listOf(pem.jumlah.toLong()) else emptyList()
                val sub = pem.subPembayaran.filter { it.tanggal == todayStr }.map { it.jumlah.toLong() }
                utama + sub
            }
        }.sum()
        val pelunasanSisaUtang = daftarPelangganList
            .filter { it.pinjamanKe > 1 && it.sisaUtangLamaSebelumTopUp > 0
                && it.tanggalPencairan == todayStr && it.status == "Aktif" }
            .sumOf { it.sisaUtangLamaSebelumTopUp.toLong() }
        val pelunasanEks = pelunasanEksternal.sumOf { it.jumlah }
        tagihanCicilan + pelunasanSisaUtang + pelunasanEks
    }
    val dropHariIni: Int = remember(daftarPelangganList.toList(), todayStr) {
        daftarPelangganList.count { p ->
            if (p.tanggalPencairan.isNotBlank()) {
                p.tanggalPencairan == todayStr && p.status == "Aktif"
            } else {
                p.tanggalDaftar == todayStr || p.tanggalPengajuan == todayStr
            }
        }
    }
    val totalNasabahAktif: Int = remember(daftarPelangganList.toList()) {
        viewModel.getFilteredPelanggan("", "AKTIF").size
    }
    // ✅ #1 (pimpinan 07 Jun 2026): jumlah UNIK nasabah yang bayar hari ini —
    // bukan total entri pembayaran. Cek per-nasabah ada/tidak storting > 0
    // hari ini (utama atau sub). Count distinct via `.count { ... }` pada list
    // nasabah → tiap nasabah dihitung maksimal 1.
    val nasabahBayarHariIni: Int = remember(daftarPelangganList.toList(), todayStr) {
        daftarPelangganList.count { p ->
            val utamaHariIni = p.pembayaranList.any { it.tanggal == todayStr && it.jumlah > 0 }
            val subHariIni = p.pembayaranList.any { pem ->
                pem.subPembayaran.any { it.tanggal == todayStr && it.jumlah > 0 }
            }
            utamaHariIni || subHariIni
        }
    }
    // ✅ #3 pimpinan 07 Jun 2026: Target Hari Ini untuk hero card.
    // Pakai single source of truth calculateTargetContribution
    // (TargetHarianHelper.kt — cermin 1:1 Web lib/target.js & CF summaryHelpers).
    // Dengan begitu nilai di hero card SAMA persis dengan kolom Storting/target
    // di Ringkasan, Buku Pokok web, dan dashboard pimpinan. NOL DB hit baru.
    val targetHariIni: Long = remember(daftarPelangganList.toList(), todayStr) {
        daftarPelangganList.sumOf { calculateTargetContribution(it, todayStr) }
    }

    // Animation states
    var isVisible by remember { mutableStateOf(false) }

    // State foto profil: avatar URL tetap dibaca lokal (untuk hero card display).
    // Dialog profil dipindah ke AppNavigation (di-hoist). Lihat onAvatarClick
    // parameter — dipanggil saat user tap avatar di hero card.
    val adminPhotoUrl by viewModel.adminPhotoUrl.collectAsState()
    var showLogoutAbsensiDialog by remember { mutableStateOf(false) }

    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) AdminColors.darkSurface else HomeTheme.screenBg
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    LaunchedEffect(Unit) {
        try {
            isVisible = true
            viewModel.loadAdminPhotoUrl()
            viewModel.loadActiveBroadcasts()
            viewModel.cleanupStuckMenungguPencairan()
            viewModel.loadAdminNotifications()
            viewModel.startNetworkMonitoring()
            viewModel.startRemoteTakeoverListener {
                LocationTrackingMonitor.stopMonitoring()
                LocationCheckWorker.cancel(context)
                // ✅ Fix 3B: bersihkan FCM token akun admin (await) sebelum signOut,
                // supaya token lama tak menyangkut saat pimpinan login balik ke akunnya.
                viewModel.logoutWithCleanup {
                    SesiAktif.keluarSerentak(context)
                    Toast.makeText(
                        context,
                        "Pimpinan mengambil alih akun Anda. Anda akan logout otomatis.",
                        Toast.LENGTH_LONG
                    ).show()
                    try {
                        navController.navigate("auth") {
                            popUpTo(0) { inclusive = true }
                        }
                    } catch (e: Exception) {
                        Log.e("AdminHome", "Navigation error on takeover: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AdminHome", "Error initializing: ${e.message}")
        }
    }

    // ✅ #4 pimpinan 07 Jun 2026: STICKY HEADER + scrollable grid only.
    // Outer Column NON-SCROLL. Header (TopActionsRow, Hero, SyncStatus, banner,
    // Ringkasan, "Menu Utama" judul) fixed di atas; LazyVerticalGrid di bawah
    // dgn Modifier.weight(1f) → mengisi sisa space & handle scroll-nya sendiri.
    // Bottom inset bottom-nav sudah diberikan outer Scaffold di AppNavigation.
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
            // ── (2) Header Actions Row ──────────────────────────────────────
            TopActionsRow(
                isDark = isDark,
                txtColor = txtColor,
                isOnline = isOnline,
                isSyncing = isSyncing,
                unsyncedCount = unsyncedCount,
                unreadCount = unreadNotifications.size,
                onDarkModeToggle = { viewModel.setDarkMode(it) },
                onManualSync = { viewModel.manualSync() },
                onNotificationClick = { navController.navigate("notifikasi") },
                onLogoutClick = {
                    if (isTakeoverMode) {
                        viewModel.returnToPimpinanAccount(
                            navController = navController,
                            context = context,
                            onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                        )
                    } else {
                        showLogoutAbsensiDialog = true
                    }
                }
            )

            // ── (3) Profile Hero Card ───────────────────────────────────────
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500)) + slideInVertically(
                    initialOffsetY = { -40 }, animationSpec = tween(500)
                )
            ) {
                ProfileHeroCard(
                    email = email,
                    adminPhotoUrl = adminPhotoUrl,
                    isUploadingPhoto = isUploadingPhoto,
                    targetHariIni = targetHariIni,
                    onAvatarClick = onAvatarClick
                )
            }

            // Indikator sinkronisasi offline — auto-hide bila tidak ada pending/failed.
            SyncStatusBlock()

            // ✅ BANNER TAKEOVER MODE
            if (isTakeoverMode) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
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
                                    onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
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
            }

            // ✅ Broadcast Banner
            if (activeBroadcasts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                BroadcastBannerSection(broadcasts = activeBroadcasts, isDark = isDark)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── (4) Ringkasan Hari Ini ──────────────────────────────────────
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(550)) + slideInVertically(
                    initialOffsetY = { 40 }, animationSpec = tween(550)
                )
            ) {
                // ✅ #3 pimpinan 07 Jun 2026: kartu Ringkasan jadi DISPLAY-ONLY,
                // tanpa onClick / ripple. Akses Ringkasan tetap tersedia via grid
                // menu "Ringkasan" + tab bottom-nav nanti bila ditambah.
                RingkasanHariIniCard(
                    tanggal = todayStr,
                    nasabahAktif = totalNasabahAktif,
                    nasabahBayar = nasabahBayarHariIni,
                    dropHariIni = dropHariIni,
                    storting = stortingHariIni,
                    isDark = isDark
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── (5) Menu Utama ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "Menu Utama",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = txtColor
                )
                Text(
                    text = "Kelola data dengan cepat",
                    fontSize = 12.sp,
                    color = subtitleColor
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ✅ Grid 4 kartu (pimpinan 07 Jun 2026, revisi swap):
            //    "Kunjungan Hari Ini" → "Kalkulator" (swap dgn tab bottom-nav;
            //    kunjungan sekarang ada di tab bottom-nav "Kunjungan").
            val menuItems = listOf(
                ModernMenuItem("Tambah Nasabah", "Ajukan pinjaman nasabah baru",
                    Icons.Rounded.PersonAdd, AdminColors.infoGradient) { navController.navigate("tambahPelanggan") },
                ModernMenuItem("Input Pembayaran", "Catat pembayaran nasabah",
                    Icons.Rounded.Payment, AdminColors.successGradient) { navController.navigate("inputPembayaran") },
                ModernMenuItem("Kalkulator", "Hitung simulasi pinjaman",
                    Icons.Rounded.Calculate, AdminColors.warningGradient) { navController.navigate("kalkulatorPinjaman") },
                ModernMenuItem("Ringkasan", "Lihat ringkasan dan performa",
                    Icons.Rounded.Analytics, AdminColors.purpleGradient) { navController.navigate("ringkasan") }
            )

            // ✅ #4 pimpinan: LazyVerticalGrid mengisi sisa space (weight 1f) dan
            // SATU-SATUNYA komponen scrollable di layar Beranda. Header di atas
            // tetap fixed. Padding bottom = 16.dp (bottom-nav inset sudah dipasok
            // outer Scaffold di AppNavigation via Modifier.padding(innerPadding)).
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(menuItems) { item ->
                    MenuActionCard(item = item, isDark = isDark)
                }
            }
        }
    }

    // ===================== DIALOGS (logic dipertahankan 1:1) =====================
    // Dialog Pilihan Foto (Lihat / Ubah) + Dialog Lihat Foto Besar di-HOIST
    // ke AppNavigation supaya bisa dipicu dari tab "Akun" di bottom bar global
    // maupun avatar di hero card. Lihat AppNavigation.kt.

    // Dialog pilihan Absen / Logout
    if (showLogoutAbsensiDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutAbsensiDialog = false },
            title = { Text("Keluar", fontWeight = FontWeight.Bold) },
            text = { Text("Apakah Anda ingin absen terlebih dahulu sebelum keluar?") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutAbsensiDialog = false
                        navController.navigate("absensi")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AdminColors.primaryGradient[0])
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Absen Dulu")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showLogoutAbsensiDialog = false
                        LocationTrackingMonitor.stopMonitoring()
                        LocationCheckWorker.cancel(context)
                        // ✅ Fix 3B: hapus FCM token (await) sebelum signOut.
                        viewModel.logoutWithCleanup {
                            SesiAktif.keluarSerentak(context)
                            Toast.makeText(context, "Logout sukses", Toast.LENGTH_SHORT).show()
                            navController.navigate("auth") {
                                popUpTo("dashboard") { inclusive = true }
                            }
                        }
                    }
                ) {
                    Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Langsung Keluar")
                }
            },
            containerColor = cardColor,
            titleContentColor = txtColor,
            textContentColor = txtColor
        )
    }
}

// =========================================================================
// (2) TOP ACTIONS ROW — sun toggle (kiri), sync+bell(badge)+logout (kanan)
// =========================================================================
@Composable
private fun TopActionsRow(
    isDark: Boolean,
    txtColor: Color,
    isOnline: Boolean,
    isSyncing: Boolean,
    unsyncedCount: Int,
    unreadCount: Int,
    onDarkModeToggle: (Boolean) -> Unit,
    onManualSync: () -> Unit,
    onNotificationClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sun/theme toggle (kiri)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF6366F1)
                )
            )
        }

        // Right actions
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModernSyncStatusIndicator(
                isOnline = isOnline,
                isSyncing = isSyncing,
                unsyncedCount = unsyncedCount,
                onManualSync = onManualSync,
                isDark = isDark
            )
            // Notification bell + red badge
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
                                .background(Color(0xFFEF4444))
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            // Logout/exit
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
}

// =========================================================================
// (3) PROFILE HERO CARD — gradient biru→ungu, avatar, welcome, email pill
// =========================================================================
@Composable
private fun ProfileHeroCard(
    email: String,
    adminPhotoUrl: String?,
    isUploadingPhoto: Boolean,
    targetHariIni: Long,
    onAvatarClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = HomeTheme.indigo.copy(alpha = 0.25f),
                spotColor = HomeTheme.indigo.copy(alpha = 0.30f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(HomeTheme.heroStart, HomeTheme.heroMid, HomeTheme.heroEnd),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                )
        ) {
            // Decorative ambient circles
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 40.dp, y = (-40).dp)
                    .background(Color.White.copy(alpha = 0.10f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 24.dp, y = 24.dp)
                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
            )

            Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Avatar with white border
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                            .clickable { onAvatarClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isUploadingPhoto -> CircularProgressIndicator(
                                modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp
                            )
                            !adminPhotoUrl.isNullOrBlank() -> AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(adminPhotoUrl).crossfade(true).build(),
                                contentDescription = "Foto Profil",
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            else -> Icon(
                                imageVector = Icons.Rounded.AdminPanelSettings,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        // Camera badge
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
                                tint = HomeTheme.indigo,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
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

                // ✅ #3 pimpinan 07 Jun 2026: email pill (kiri) + Target Hari Ini
                // (kanan) dalam satu Row SpaceBetween. Email weight(1f, fill=false)
                // supaya pill tidak menelan ruang Target di kanan; Target
                // wrapContentSize → tidak terpotong. Email truncate dgn ellipsis.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Email pill badge (kiri)
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Email,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = email,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Target Hari Ini (kanan, right-aligned column)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Target Hari Ini",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatRupiahHome(targetHariIni),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Kelola nasabah, pembayaran, dan laporan koperasi dengan mudah",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

// =========================================================================
// (4) RINGKASAN HARI INI — white card, 4 kolom; kolom Setoran di-highlight
// -------------------------------------------------------------------------
// ✅ #3 pimpinan 07 Jun 2026: DISPLAY-ONLY. Tanpa onClick, tanpa ripple,
// tanpa Card(onClick=…) eksperimental → @OptIn ExperimentalMaterial3Api
// TIDAK lagi dibutuhkan untuk composable ini.
// =========================================================================
@Composable
private fun RingkasanHariIniCard(
    tanggal: String,
    nasabahAktif: Int,
    nasabahBayar: Int,
    dropHariIni: Int,
    storting: Long,
    isDark: Boolean
) {
    val cardBg = if (isDark) AdminColors.darkCard else HomeTheme.cardWhite
    val borderColor = if (isDark) AdminColors.darkBorder else HomeTheme.cardBorder
    val titleColor = if (isDark) Color.White else HomeTheme.titleText
    val labelColor = if (isDark) Color(0xFFA1A1AA) else HomeTheme.subtitleText

    // Non-interaktif: tanpa onClick → tidak ada ripple/state selection apapun.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.06f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // Header: judul + tanggal (kalender)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ringkasan Hari Ini",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarMonth,
                        contentDescription = null,
                        tint = labelColor,
                        modifier = Modifier.size(15.dp)
                    )
                    // ✅ #5 pimpinan 07 Jun 2026: indikator LIBUR di samping tanggal.
                    // Pakai com.example.koperasikitagodangulu.utils.HolidayUtils yang
                    // sudah ada (Minggu OR tanggal merah nasional). Jika libur →
                    // "DD MMM YYYY / LIBUR" dgn "/ LIBUR" tint merah & bold;
                    // hari kerja → tanggal biasa.
                    val isLibur = remember(tanggal) {
                        try {
                            val cal = com.example.koperasikitagodangulu.utils.HolidayUtils.parseTanggal(tanggal)
                            com.example.koperasikitagodangulu.utils.HolidayUtils.isMinggu(cal) ||
                                com.example.koperasikitagodangulu.utils.HolidayUtils.isTanggalMerah(cal)
                        } catch (_: Exception) { false }
                    }
                    if (isLibur) {
                        Text(
                            text = tanggal,
                            fontSize = 12.sp,
                            color = labelColor,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = " / LIBUR",
                            fontSize = 12.sp,
                            color = HomeTheme.coral,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = tanggal,
                            fontSize = 12.sp,
                            color = labelColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4 kolom
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatColumn(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Groups,
                    iconTint = Color(0xFF6366F1),
                    iconBg = Color(0xFFEEF0FE),
                    value = nasabahAktif.toString(),
                    label = "Nasabah Aktif",
                    labelColor = labelColor,
                    valueColor = titleColor
                )
                StatColumn(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.CreditCard,
                    iconTint = Color(0xFF10B981),
                    iconBg = Color(0xFFE7F8F1),
                    value = nasabahBayar.toString(),
                    // ✅ #1 pimpinan 07 Jun 2026: label "Pembayaran" → "Nasabah Bayar"
                    // dengan metrik count UNIK nasabah yang bayar hari ini.
                    label = "Nasabah Bayar",
                    labelColor = labelColor,
                    valueColor = titleColor
                )
                StatColumn(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Today,
                    iconTint = Color(0xFFF59E0B),
                    iconBg = Color(0xFFFEF4E2),
                    value = dropHariIni.toString(),
                    label = "Drop Hari Ini",
                    labelColor = labelColor,
                    valueColor = titleColor
                )
                // Kolom Setoran — highlighted pastel coral
                StatColumn(
                    modifier = Modifier.weight(1.15f),
                    icon = Icons.Rounded.BarChart,
                    iconTint = HomeTheme.coral,
                    iconBg = Color.White,
                    value = formatRupiahHome(storting),
                    // ✅ #2 pimpinan 07 Jun 2026: label "Total Setoran" → "Storting Hari Ini".
                    // Nilai TETAP stortingHariIni (sinkron dgn RingkasanDashboardScreen).
                    label = "Storting Hari Ini",
                    labelColor = HomeTheme.coral.copy(alpha = 0.85f),
                    valueColor = HomeTheme.coral,
                    highlightBg = if (isDark) HomeTheme.coral.copy(alpha = 0.12f) else HomeTheme.coralBg,
                    valueFontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun StatColumn(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    value: String,
    label: String,
    labelColor: Color,
    valueColor: Color,
    highlightBg: Color? = null,
    valueFontSize: androidx.compose.ui.unit.TextUnit = 16.sp
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .then(if (highlightBg != null) Modifier.background(highlightBg) else Modifier)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Text(
            text = value,
            fontSize = valueFontSize,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

// =========================================================================
// (5) MENU ACTION CARD — white, colored icon box, title, desc, arrow ↘
// =========================================================================
data class ModernMenuItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val gradient: List<Color>,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuActionCard(item: ModernMenuItem, isDark: Boolean) {
    val cardBg = if (isDark) AdminColors.darkCard else HomeTheme.cardWhite
    val borderColor = if (isDark) AdminColors.darkBorder else HomeTheme.cardBorder
    val titleColor = if (isDark) Color.White else HomeTheme.titleText
    val subtitleColor = if (isDark) Color(0xFFA1A1AA) else HomeTheme.subtitleText

    Card(
        onClick = item.onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .shadow(
                elevation = 5.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.05f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Colored icon box
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(item.gradient)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = item.subtitle,
                    fontSize = 11.sp,
                    color = subtitleColor,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Arrow bottom-right
            Icon(
                imageVector = Icons.Rounded.ArrowForward,
                contentDescription = null,
                tint = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
            )
        }
    }
}

// =========================================================================
// SYNC STATUS INDICATOR (dipertahankan dari versi sebelumnya — dipakai header)
// =========================================================================
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
        !isOnline -> Triple(Color(0xFFFEE2E2), Color(0xFFEF4444), Icons.Rounded.CloudOff)
        isSyncing -> Triple(Color(0xFFDBEAFE), Color(0xFF3B82F6), Icons.Rounded.Sync)
        unsyncedCount > 0 -> Triple(Color(0xFFFEF3C7), Color(0xFFF59E0B), Icons.Rounded.CloudSync)
        else -> Triple(Color(0xFFD1FAE5), Color(0xFF10B981), Icons.Rounded.CloudDone)
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
                    .then(if (isSyncing) Modifier.graphicsLayer { rotationZ = rotation } else Modifier)
            )
            if (unsyncedCount > 0 && !isSyncing) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(Brush.linearGradient(AdminColors.roseGradient), CircleShape),
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
// BROADCAST BANNER SECTION (dipertahankan)
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
            BroadcastBannerCard(broadcast = broadcast, isDark = isDark)
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
            Box(
                modifier = Modifier.size(40.dp).background(iconBgColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Campaign,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = broadcast.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = titleColor)
                Text(text = broadcast.message, fontSize = 13.sp, color = messageColor, lineHeight = 18.sp)
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
                    Text(text = broadcast.senderName, fontSize = 11.sp, color = senderColor)
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
