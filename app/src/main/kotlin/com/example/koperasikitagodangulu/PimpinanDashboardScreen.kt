package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.models.AdminSummary
import kotlinx.coroutines.launch
import android.util.Log
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.example.koperasikitagodangulu.models.BroadcastMessage
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import androidx.compose.material.icons.rounded.SwapHoriz

// =========================================================================
// HELPER FUNCTIONS
// =========================================================================
internal fun getNetCashFlowColor(netCashFlow: Int): Color {
    return when {
        netCashFlow > 0 -> PimpinanColors.success
        netCashFlow < 0 -> PimpinanColors.danger
        else -> PimpinanColors.textSecondary
    }
}

internal fun getPerformanceColor(performance: Float): Color {
    return when {
        performance >= 0.8f -> PimpinanColors.success
        performance >= 0.6f -> PimpinanColors.warning
        performance >= 0.4f -> Color(0xFFF97316)
        else -> PimpinanColors.danger
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanDashboardScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val isLoading by viewModel.isLoading.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val adminSummary by viewModel.adminSummary.collectAsState()
    val cabangSummary by viewModel.cabangSummary.collectAsState()
    val currentUserCabang by viewModel.currentUserCabang.collectAsState()
    val isDataLoaded by viewModel.isDataLoaded.collectAsState()

    val isLoadingRefresh = remember { mutableStateOf(false) }
    //val isInitialized = remember { mutableStateOf(false) }
    val activeBroadcasts by viewModel.activeBroadcasts.collectAsState()
    var isVisible by remember { mutableStateOf(false) }
    val isDark by viewModel.isDarkMode
    val adminPhotosMap by viewModel.adminPhotosMap.collectAsState()
    // Remote Takeover State
    val takeoverStatus by viewModel.takeoverStatus.collectAsState()
    var showTakeoverDialog by remember { mutableStateOf(false) }
    var selectedAdminForTakeover by remember { mutableStateOf<AdminSummary?>(null) }
    val context = LocalContext.current
    // ✅ Set status bar & navigation bar sesuai tema dashboard
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }
    LaunchedEffect(Unit) {
        val shouldLogout = viewModel.checkForceLogoutOnStartup()
        if (shouldLogout) {
            Firebase.auth.signOut()
            navController.navigate("auth") {
                popUpTo(0) { inclusive = true }
            }
            return@LaunchedEffect
        }
    }

    // ✅ FIX: Load broadcasts langsung tanpa menunggu currentUserCabang
    LaunchedEffect(Unit) {
        viewModel.loadActiveBroadcasts()
    }

    LaunchedEffect(currentUserCabang, isDataLoaded) {
        if (currentUserCabang != null && !isDataLoaded && adminSummary.isEmpty()) {
            Log.d("PimpinanDashboard", "🔄 Fallback: loading pimpinan data")
            viewModel.refreshPimpinanData()
        }
    }

    // ✅ FIX: Tampilkan data segera setelah tersedia (dari initPimpinanListeners di ViewModel init)
    LaunchedEffect(isDataLoaded, adminSummary) {
        if (isDataLoaded || adminSummary.isNotEmpty()) {
            isVisible = true
        }
    }

    // ✅ BARU: Load foto admin saat adminSummary tersedia
    LaunchedEffect(adminSummary) {
        if (adminSummary.isNotEmpty()) {
            val adminUids = adminSummary.map { it.adminId }
            viewModel.loadAdminPhotosForCabang(adminUids)
        }
    }

    fun handleRefresh() {
        if (isLoadingRefresh.value) return
        isLoadingRefresh.value = true
        coroutineScope.launch {
            try {
                viewModel.refreshPimpinanData()
                Log.d("PimpinanDashboard", "✅ Refresh completed")
            } catch (e: Exception) {
                Log.e("PimpinanDashboard", "❌ Refresh error: ${e.message}")
            } finally {
                isLoadingRefresh.value = false
            }
        }
    }

    // =========================================================================
    // DIALOG: Remote Takeover
    // =========================================================================
    if (showTakeoverDialog && selectedAdminForTakeover != null) {
        val admin = selectedAdminForTakeover!!
        AlertDialog(
            onDismissRequest = {
                if (takeoverStatus !is TakeoverStatus.WaitingSync &&
                    takeoverStatus !is TakeoverStatus.SigningIn) {
                    showTakeoverDialog = false
                    selectedAdminForTakeover = null
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.SwapHoriz,
                    contentDescription = null,
                    tint = PimpinanColors.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    when (takeoverStatus) {
                        is TakeoverStatus.Idle, is TakeoverStatus.Error -> "Ambil Alih Akun"
                        is TakeoverStatus.CheckingOnline -> "Memeriksa Koneksi..."
                        is TakeoverStatus.WaitingSync -> "Menunggu Sinkronisasi..."
                        is TakeoverStatus.SigningIn -> "Masuk ke Akun Admin..."
                        else -> "Ambil Alih Akun"
                    },
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    when (takeoverStatus) {
                        is TakeoverStatus.Idle -> {
                            Text("Anda akan mengambil alih akun ${admin.adminName}.")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Sistem akan otomatis menyinkronkan data di HP admin dan me-logout admin tersebut.",
                                color = PimpinanColors.textSecondary,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "⚠ Kedua HP harus terhubung internet dan aplikasi admin harus aktif.",
                                color = PimpinanColors.warning,
                                fontSize = 12.sp
                            )
                        }
                        is TakeoverStatus.CheckingOnline -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Memeriksa koneksi HP admin...")
                            }
                        }
                        is TakeoverStatus.WaitingSync -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Menyinkronkan data admin ke server...")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Jangan tutup aplikasi. Menunggu HP admin menyelesaikan sinkronisasi...",
                                fontSize = 12.sp,
                                color = PimpinanColors.textMuted
                            )
                        }
                        is TakeoverStatus.SigningIn -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Masuk ke akun ${admin.adminName}...")
                            }
                        }
                        is TakeoverStatus.Error -> {
                            val errorMsg = (takeoverStatus as TakeoverStatus.Error).message
                            Text(
                                "❌ $errorMsg",
                                color = PimpinanColors.danger
                            )
                        }
                        else -> {}
                    }
                }
            },
            confirmButton = {
                when (takeoverStatus) {
                    is TakeoverStatus.Idle -> {
                        Button(
                            onClick = {
                                viewModel.initiateRemoteTakeover(
                                    targetAdminUid = admin.adminId,
                                    targetAdminName = admin.adminName,
                                    onTokenReady = { token ->
                                        showTakeoverDialog = false
                                        viewModel.performTakeoverSignIn(
                                            token = token,
                                            navController = navController,
                                            context = context
                                        )
                                    },
                                    onError = { /* Error sudah di-handle via takeoverStatus */ }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PimpinanColors.primary
                            )
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Ambil Alih")
                        }
                    }
                    is TakeoverStatus.Error -> {
                        Button(
                            onClick = {
                                showTakeoverDialog = false
                                selectedAdminForTakeover = null
                            }
                        ) {
                            Text("Tutup")
                        }
                    }
                    else -> { /* Tidak ada tombol saat loading */ }
                }
            },
            dismissButton = {
                if (takeoverStatus is TakeoverStatus.Idle || takeoverStatus is TakeoverStatus.Error) {
                    TextButton(onClick = {
                        showTakeoverDialog = false
                        selectedAdminForTakeover = null
                    }) {
                        Text("Batal")
                    }
                }
            }
        )
    }

    Scaffold(
        containerColor = PimpinanColors.getBackground(isDark), // ✅ UBAH: Dinamis
        topBar = {
            PimpinanTopBar(
                title = "Dashboard",
                navController = navController,
                viewModel = viewModel,
                onRefresh = { handleRefresh() }
            )
        },
        bottomBar = {
            PimpinanBottomNavigation(navController, "pimpinan_dashboard", viewModel) // ✅ UBAH: Tambah viewModel
        }
    ) { innerPadding ->
        if ((isLoading && adminSummary.isEmpty() && cabangSummary == null) || isLoadingRefresh.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = PimpinanColors.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (isLoadingRefresh.value) "Memperbarui data..." else "Memuat dashboard...",
                        color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val shouldShowEmptyState = adminSummary.isEmpty() && cabangSummary == null

                if (shouldShowEmptyState) {
                    item {
                        ModernEmptyStateDashboard(onRefresh = { handleRefresh() }, isDark = isDark) // ✅ UBAH: Tambah isDark
                    }
                } else {
                    // ✅ BARU: Broadcast Banner
                    if (activeBroadcasts.isNotEmpty()) {
                        item {
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(tween(300)) + slideInVertically(
                                    initialOffsetY = { -20 },
                                    animationSpec = tween(300)
                                )
                            ) {
                                PimpinanBroadcastBanner(broadcasts = activeBroadcasts, isDark = isDark) // ✅ UBAH: Tambah isDark
                            }
                        }
                    }

                    // Tombol Absensi Harian
                    item {
                        AnimatedVisibility(
                            visible = isVisible,
                            enter = fadeIn(tween(300)) + slideInVertically(initialOffsetY = { -20 }, animationSpec = tween(300))
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate("absensi") },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(
                                                Brush.linearGradient(PimpinanColors.tealGradient),
                                                RoundedCornerShape(12.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.HowToReg, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Absensi Harian", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PimpinanColors.getTextPrimary(isDark))
                                        Text("Catat kehadiran hari ini", fontSize = 12.sp, color = PimpinanColors.getTextSecondary(isDark))
                                    }
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = PimpinanColors.getTextMuted(isDark))
                                }
                            }
                        }
                    }

                    // Ringkasan Kinerja
                    item {
                        AnimatedVisibility(
                            visible = isVisible,
                            enter = fadeIn(tween(400)) + slideInVertically(
                                initialOffsetY = { -30 },
                                animationSpec = tween(400)
                            )
                        ) {
                            Column {
                                ModernSectionTitle(
                                    title = "Ringkasan Kinerja",
                                    icon = Icons.Rounded.Analytics,
                                    isDark = isDark // ✅ UBAH: Tambah isDark
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                val totalPiutang = adminSummary.sumOf { it.totalPiutang }
                                val totalNasabah = adminSummary.sumOf { it.nasabahAktif }
                                val totalPembayaranHariIni = adminSummary.sumOf { it.pembayaranHariIni }
                                val totalTargetHariIni = adminSummary.sumOf { it.targetHariIni }
                                val isHariLibur = totalNasabah > 0 && totalTargetHariIni == 0L

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    PimpinanModernStatCard(
                                        title = "Total Saldo",
                                        value = formatRupiah(totalPiutang.toInt()),
                                        icon = Icons.Rounded.AccountBalanceWallet,
                                        gradient = PimpinanColors.dangerGradient,
                                        modifier = Modifier.weight(1f),
                                        isDark = isDark // ✅ UBAH: Tambah isDark
                                    )
                                    PimpinanModernStatCard(
                                        title = "Nasabah Aktif",
                                        value = totalNasabah.toString(),
                                        icon = Icons.Rounded.Groups,
                                        gradient = PimpinanColors.infoGradient,
                                        modifier = Modifier.weight(1f),
                                        isDark = isDark // ✅ UBAH: Tambah isDark
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    PimpinanModernStatCard(
                                        title = "Pembayaran Hari Ini",
                                        value = formatRupiah(totalPembayaranHariIni.toInt()),
                                        icon = Icons.Rounded.Payments,
                                        gradient = PimpinanColors.warningGradient,
                                        modifier = Modifier.weight(1f),
                                        isDark = isDark // ✅ UBAH: Tambah isDark
                                    )

                                    if (isHariLibur) {
                                        PimpinanModernStatCard(
                                            title = "Target Hari Ini",
                                            value = "LIBUR",
                                            icon = Icons.Rounded.EventBusy,
                                            gradient = PimpinanColors.grayGradient,
                                            modifier = Modifier.weight(1f),
                                            isDark = isDark // ✅ UBAH: Tambah isDark
                                        )
                                    } else {
                                        PimpinanModernStatCard(
                                            title = "Target Hari Ini",
                                            value = formatRupiah(totalTargetHariIni.toInt()),
                                            icon = Icons.Rounded.TrendingUp,
                                            gradient = PimpinanColors.successGradient,
                                            modifier = Modifier.weight(1f),
                                            isDark = isDark // ✅ UBAH: Tambah isDark
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Performa PDL
                    item {
                        AnimatedVisibility(
                            visible = isVisible,
                            enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                                initialOffsetY = { 30 },
                                animationSpec = tween(400, delayMillis = 200)
                            )
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(8.dp))

                                ModernSectionTitle(
                                    title = "Performa PDL (Resort)",
                                    icon = Icons.Rounded.Leaderboard,
                                    isDark = isDark // ✅ UBAH: Tambah isDark
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                if (adminSummary.isNotEmpty()) {
                                    PerformanceBarChartOptimized(
                                        adminSummary, Modifier.fillMaxWidth(), navController, isDark, adminPhotosMap,
                                        onTakeoverClick = { admin ->
                                            selectedAdminForTakeover = admin
                                            showTakeoverDialog = true
                                        }
                                    )
                                } else {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark)) // ✅ UBAH: Dinamis
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(120.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "Data performa kolektor akan muncul di sini",
                                                color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernSectionTitle(
    title: String,
    icon: ImageVector,
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false untuk backward compatibility
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    Brush.linearGradient(PimpinanColors.primaryGradient),
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = PimpinanColors.getTextPrimary(isDark) // ✅ UBAH: Dinamis
        )
    }
}

@Composable
fun PimpinanModernStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false untuk backward compatibility
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = gradient.first().copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark)) // ✅ UBAH: Dinamis
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        Brush.linearGradient(gradient),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PimpinanColors.getTextPrimary(isDark) // ✅ UBAH: Dinamis
            )

            Text(
                text = title,
                fontSize = 12.sp,
                color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
            )
        }
    }
}

@Composable
fun PimpinanStatCard(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    PimpinanModernStatCard(
        title = title,
        value = value,
        icon = icon,
        gradient = listOf(color, color.copy(alpha = 0.8f)),
        modifier = modifier
    )
}

@Composable
fun PerformanceBarChartOptimized(
    adminSummary: List<AdminSummary>,
    modifier: Modifier = Modifier,
    navController: NavHostController,
    isDark: Boolean = false,
    adminPhotosMap: Map<String, String?> = emptyMap(),
    onTakeoverClick: ((AdminSummary) -> Unit)? = null  // TAMBAH
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        adminSummary.sortedByDescending { it.performancePercentage }.forEach { admin ->
            AdminPerformanceItemOptimized(
                admin = admin,
                navController = navController,
                isDark = isDark,
                photoUrl = adminPhotosMap[admin.adminId],  // ✅ TAMBAH photoUrl
                onTakeoverClick = onTakeoverClick
            )
        }
    }
}

@Composable
fun AdminPerformanceItemOptimized(
    admin: AdminSummary,
    navController: NavHostController,
    isDark: Boolean = false,
    photoUrl: String? = null,
    onTakeoverClick: ((AdminSummary) -> Unit)? = null  // TAMBAH
) {
    val context = LocalContext.current
    val isHariLibur = admin.isHoliday()
    val performance = admin.performancePercentage / 100f
    val performanceColor = getPerformanceColor(performance)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = performanceColor.copy(alpha = 0.1f)
            )
            .clickable {
                navController.navigate("laporan_harian_admin/${admin.adminId}")
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ✅ BARU: Row dengan foto profil dan email
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Foto profil admin
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(PimpinanColors.primaryGradient),
                            CircleShape
                        )
                        .border(
                            width = 1.5.dp,
                            color = PimpinanColors.primary.copy(alpha = 0.3f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (!photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(photoUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Foto Admin",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // Default: Inisial
                        Text(
                            text = admin.adminName.take(2).uppercase().ifEmpty { "AD" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                // Name admin
                if (admin.adminName.isNotBlank()) {
                    Text(
                        text = admin.adminName,
                        fontSize = 12.sp,
                        color = PimpinanColors.getTextSecondary(isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)  // TAMBAH weight
                    )
                }

                // Tombol Ambil Alih
                if (onTakeoverClick != null) {
                    IconButton(
                        onClick = { onTakeoverClick(admin) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = "Ambil Alih",
                            tint = PimpinanColors.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${admin.nasabahAktif} nasabah aktif",
                    fontSize = 13.sp,
                    color = PimpinanColors.getTextSecondary(isDark)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Payments,
                        contentDescription = null,
                        tint = PimpinanColors.success,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = formatRupiah(admin.pembayaranHariIni.toInt()),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PimpinanColors.success
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isHariLibur) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                PimpinanColors.getTextSecondary(isDark).copy(alpha = 0.1f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "LIBUR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PimpinanColors.getTextSecondary(isDark)
                        )
                    }

                    LinearProgressIndicator(
                        progress = { 0f },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .padding(start = 12.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = PimpinanColors.getTextSecondary(isDark),
                        trackColor = PimpinanColors.getBorder(isDark)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                performanceColor.copy(alpha = 0.1f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${admin.performancePercentage.toInt()}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = performanceColor
                        )
                    }

                    LinearProgressIndicator(
                        progress = { performance.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .padding(start = 12.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = performanceColor,
                        trackColor = PimpinanColors.getBorder(isDark)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Terkumpul: ${formatRupiah(admin.pembayaranHariIni.toInt())}",
                    fontSize = 11.sp,
                    color = PimpinanColors.getTextSecondary(isDark)
                )
                if (isHariLibur) {
                    Text(
                        text = "Target: LIBUR",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextSecondary(isDark)
                    )
                } else {
                    Text(
                        text = "Target: ${formatRupiah(admin.targetHariIni.toInt())}",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextSecondary(isDark)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernEmptyStateDashboard(
    onRefresh: () -> Unit,
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false untuk backward compatibility
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = PimpinanColors.primary.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark)) // ✅ UBAH: Dinamis
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        PimpinanColors.primary.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = PimpinanColors.primary,
                    modifier = Modifier.size(40.dp)
                )
            }

            Text(
                text = "Data belum tersedia",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PimpinanColors.getTextPrimary(isDark) // ✅ UBAH: Dinamis
            )

            Text(
                text = "Tekan tombol di bawah untuk memuat data",
                fontSize = 14.sp,
                color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
            )

            Button(
                onClick = onRefresh,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues()
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            Brush.linearGradient(PimpinanColors.primaryGradient),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Text(
                            text = "Refresh",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// BROADCAST BANNER FOR PIMPINAN
// =========================================================================

@Composable
private fun PimpinanBroadcastBanner(
    broadcasts: List<BroadcastMessage>,
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false untuk backward compatibility
) {
    // Warna untuk broadcast banner (tetap kuning/amber untuk visibility)
    val cardColor = if (isDark) Color(0xFF3D3D4F) else Color(0xFFFFF8E1)
    val iconBgColor = if (isDark) Color(0xFFFF9800).copy(alpha = 0.2f) else Color(0xFFFFE0B2)
    val titleColor = if (isDark) Color.White else Color(0xFF1E293B)
    val messageColor = if (isDark) Color(0xFFE0E0E0) else Color(0xFF424242)
    val senderColor = if (isDark) Color(0xFFA1A1AA) else Color(0xFF757575)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        broadcasts.forEach { broadcast ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor), // ✅ UBAH: Dinamis
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
                            .background(iconBgColor, CircleShape), // ✅ UBAH: Dinamis
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
                            color = titleColor // ✅ UBAH: Dinamis
                        )
                        Text(
                            text = broadcast.message,
                            fontSize = 13.sp,
                            color = messageColor, // ✅ UBAH: Dinamis
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
                                tint = senderColor, // ✅ UBAH: Dinamis
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = broadcast.senderName,
                                fontSize = 11.sp,
                                color = senderColor // ✅ UBAH: Dinamis
                            )
                        }
                    }
                }
            }
        }
    }
}