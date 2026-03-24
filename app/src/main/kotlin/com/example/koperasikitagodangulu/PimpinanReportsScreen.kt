package com.example.koperasikitagodangulu

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.koperasikitagodangulu.models.AdminSummary
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanReportsScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val adminSummary by viewModel.adminSummary.collectAsState()
    val cabangSummary by viewModel.cabangSummary.collectAsState()

    var isVisible by remember { mutableStateOf(false) }

    val isDark by viewModel.isDarkMode
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Set status bar & navigation bar sesuai tema
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }
    val adminPhotosMap by viewModel.adminPhotosMap.collectAsState()

    LaunchedEffect(Unit) {
        Log.d("ReportsScreen", "🔄 Initializing reports screen")
        viewModel.loadAdminNotifications()
        viewModel.currentUserCabang.value?.let { cabangId ->
            if (!viewModel.isPendingApprovalListenerActive()) {
                viewModel.setupRealtimePendingApprovals(cabangId)
            }
        }
        isVisible = true
    }

    // ✅ BARU: Load foto admin saat adminSummary tersedia
    LaunchedEffect(adminSummary) {
        if (adminSummary.isNotEmpty()) {
            val adminUids = adminSummary.map { it.adminId }
            viewModel.loadAdminPhotosForCabang(adminUids)
        }
    }

    Scaffold(
        containerColor = PimpinanColors.getBackground(isDark), // ✅ UBAH: Dinamis
        topBar = { PimpinanTopBar("Laporan & Analytics", navController, viewModel) },
        bottomBar = { PimpinanBottomNavigation(navController, "pimpinan_reports", viewModel) } // ✅ UBAH: Tambah viewModel
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // SECTION 0: HERO SUMMARY
            item {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(350)) + slideInVertically(
                        initialOffsetY = { -40 },
                        animationSpec = tween(350)
                    )
                ) {
                    PimpinanHeroSummaryCard(
                        totalAktif = adminSummary.sumOf { it.nasabahAktif },
                        totalPiutang = adminSummary.sumOf { it.totalPiutang },
                        jumlahPdl = adminSummary.size,
                        isDark = isDark
                    )
                }
            }

            // SECTION AKSES: PEMBUKUAN
            item {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                        initialOffsetY = { 30 },
                        animationSpec = tween(400, delayMillis = 100)
                    )
                ) {
                    Column {
                        ModernReportSectionHeader(
                            title = "Akses Pembukuan",
                            icon = Icons.Rounded.MenuBook,
                            isDark = isDark
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PembukuanCardClickable(
                            onClick = {
                                coroutineScope.launch {
                                    val baseUrl = "https://www.koperasi-kita.com/pembukuan"
                                    val currentUser = FirebaseAuth.getInstance().currentUser
                                    val url = if (currentUser != null) {
                                        try {
                                            val idToken = currentUser.getIdToken(false).await().token
                                            if (idToken != null) "$baseUrl?idToken=${Uri.encode(idToken)}"
                                            else baseUrl
                                        } catch (_: Exception) { baseUrl }
                                    } else { baseUrl }
                                    withContext(Dispatchers.Main) {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                            setPackage("com.android.chrome")
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: ActivityNotFoundException) {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // SECTION 1: RINGKASAN CABANG
            item {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 150)) + slideInVertically(
                        initialOffsetY = { -30 },
                        animationSpec = tween(400, delayMillis = 150)
                    )
                ) {
                    Column {
                        ModernReportSectionHeader(
                            title = "Ringkasan Cabang",
                            icon = Icons.Rounded.Business,
                            isDark = isDark
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val nasabahLunas = adminSummary.sumOf { it.nasabahLunas }
                        val nasabahBaruHariIni = adminSummary.sumOf { it.nasabahBaruHariIni }
                        val nasabahLunasHariIni = adminSummary.sumOf { it.nasabahLunasHariIni }

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Row 1: Status Khusus & Bermasalah
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                                ReportStatCardClickable(
                                    title = "Status Khusus",
                                    value = "Lihat",
                                    icon = Icons.Rounded.Flag,
                                    backgroundColor = PimpinanColors.purple,
                                    modifier = Modifier.weight(1f),
                                    onClick = { navController.navigate("daftarPimpinanPelangganStatusKhususSemuaAdmin") },
                                    isDark = isDark // ✅ UBAH: Tambah isDark
                                )
                                ReportStatCardClickable(
                                    title = "Bermasalah",
                                    value = "Lihat",
                                    icon = Icons.Rounded.Warning,
                                    backgroundColor = PimpinanColors.danger,
                                    modifier = Modifier.weight(1f),
                                    onClick = { navController.navigate("daftarPimpinanPelangganBermasalah") },
                                    isDark = isDark // ✅ UBAH: Tambah isDark
                                )
                            }

                            // ✅ BARU: Card Menunggu Pencairan (full width, clickable)
                            MenungguPencairanCardClickable(
                                onClick = { navController.navigate("daftarPimpinanMenungguPencairan") }
                            )

                            // ✅ BARU: Card Daftar Semua Nasabah
                            DaftarSemuaNasabahCardClickable(
                                onClick = { navController.navigate("pimpinan_daftar_semua_nasabah") }
                            )

                            // Row 2: Nasabah Lunas (Total)
                            ModernReportStatCardFull(
                                title = "Nasabah Lunas (Total)",
                                value = nasabahLunas.toString(),
                                icon = Icons.Rounded.CheckCircle,
                                gradient = PimpinanColors.purpleGradient
                            )

                            // Row 3: Baru Hari Ini & Lunas Hari Ini
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                                ReportStatCardClickable(
                                    title = "Drop Hari Ini",
                                    value = nasabahBaruHariIni.toString(),
                                    icon = Icons.Rounded.PersonAdd,
                                    backgroundColor = PimpinanColors.teal,
                                    modifier = Modifier.weight(1f),
                                    onClick = { navController.navigate("daftarPimpinanNasabahBaruHariIni") },
                                    isDark = isDark // ✅ UBAH: Tambah isDark
                                )
                                ReportStatCardClickable(
                                    title = "Lunas Hari Ini",
                                    value = nasabahLunasHariIni.toString(),
                                    icon = Icons.Rounded.Verified,
                                    backgroundColor = PimpinanColors.success,
                                    modifier = Modifier.weight(1f),
                                    onClick = { navController.navigate("daftarPimpinanNasabahLunasHariIni") },
                                    isDark = isDark // ✅ UBAH: Tambah isDark
                                )
                            }
                        }
                    }
                }
            }

            // SECTION 2: RINGKASAN PER PDL
            item {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 300)) + slideInVertically(
                        initialOffsetY = { 30 },
                        animationSpec = tween(400, delayMillis = 300)
                    )
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))

                        ModernReportSectionHeader(
                            title = "Ringkasan Per PDL (Resort)",
                            icon = Icons.Rounded.Groups,
                            isDark = isDark // ✅ UBAH: Tambah isDark
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (adminSummary.isEmpty()) {
                            ModernEmptyStateReport(isDark = isDark) // ✅ UBAH: Tambah isDark
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                adminSummary.sortedByDescending { it.nasabahAktif }.forEach { admin ->
                                    AdminReportCardNonClickable(
                                        admin = admin,
                                        isDark = isDark,
                                        photoUrl = adminPhotosMap[admin.adminId]
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

@Composable
private fun ModernReportSectionHeader(
    title: String,
    icon: ImageVector,
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false
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

// =========================================================================
// KOMPONEN: Report Stat Card (Non-Clickable)
// =========================================================================
@Composable
fun ReportStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = backgroundColor.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark)) // ✅ UBAH: Dinamis
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(backgroundColor.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = backgroundColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = backgroundColor
            )

            Text(
                text = title,
                fontSize = 12.sp,
                color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
            )
        }
    }
}

// =========================================================================
// KOMPONEN: Report Stat Card (Clickable)
// =========================================================================
@Composable
fun ReportStatCardClickable(
    title: String,
    value: String,
    icon: ImageVector,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = backgroundColor.copy(alpha = 0.2f)
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark)) // ✅ UBAH: Dinamis
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(backgroundColor.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = backgroundColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = backgroundColor
            )

            Text(
                text = title,
                fontSize = 12.sp,
                color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
            )
        }
    }
}

// =========================================================================
// KOMPONEN: Modern Report Stat Card Full Width
// =========================================================================
@Composable
fun ModernReportStatCardFull(
    title: String,
    value: String,
    icon: ImageVector,
    gradient: List<Color>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = gradient.first().copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradient))
        ) {
            // Decorative circles
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = (-20).dp, y = (-20).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = value,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

// =========================================================================
// KOMPONEN: Quick Link Card
// =========================================================================
@Composable
fun QuickLinkCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark)) // ✅ UBAH: Dinamis
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(iconTint.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = PimpinanColors.getTextPrimary(isDark) // ✅ UBAH: Dinamis
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "Navigate",
                tint = PimpinanColors.getTextMuted(isDark) // ✅ UBAH: Dinamis
            )
        }
    }
}

// =========================================================================
// KOMPONEN: Admin Report Card (NON-Clickable)
// =========================================================================
@Composable
fun AdminReportCardNonClickable(
    admin: AdminSummary,
    isDark: Boolean = false,
    photoUrl: String? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = PimpinanColors.primary.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark)) // ✅ UBAH: Dinamis
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // ✅ BARU: Avatar dengan foto profil
                    Box(
                        modifier = Modifier
                            .size(44.dp)
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
                                model = ImageRequest.Builder(LocalContext.current)
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
                                fontSize = 14.sp
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = admin.adminName.ifBlank { admin.adminEmail.ifBlank { "Admin" } },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = PimpinanColors.getTextPrimary(isDark) // ✅ UBAH: Dinamis
                        )
                        Text(
                            text = "${admin.nasabahAktif} nasabah aktif",
                            fontSize = 12.sp,
                            color = PimpinanColors.getTextSecondary(isDark) // ✅ UBAH: Dinamis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ModernMiniStatItem(
                    label = "Saldo",
                    value = formatRupiah(admin.totalPiutang.toInt()),
                    color = PimpinanColors.danger,
                    isDark = isDark // ✅ UBAH: Tambah isDark
                )
                ModernMiniStatItem(
                    label = "Drop",
                    value = admin.nasabahBaruHariIni.toString(),
                    color = PimpinanColors.teal,
                    isDark = isDark // ✅ UBAH: Tambah isDark
                )
                ModernMiniStatItem(
                    label = "Lunas",
                    value = admin.nasabahLunasHariIni.toString(),
                    color = PimpinanColors.success,
                    isDark = isDark // ✅ UBAH: Tambah isDark
                )
                ModernMiniStatItem(
                    label = "Total Lunas",
                    value = admin.nasabahLunas.toString(),
                    color = PimpinanColors.purple,
                    isDark = isDark // ✅ UBAH: Tambah isDark
                )
            }
        }
    }
}

@Composable
private fun ModernMiniStatItem(
    label: String,
    value: String,
    color: Color,
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = PimpinanColors.getTextMuted(isDark) // ✅ UBAH: Dinamis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun ModernEmptyStateReport(
    isDark: Boolean = false // ✅ BARU: Parameter isDark dengan default false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PimpinanColors.getCard(isDark)) // ✅ UBAH: Dinamis
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            PimpinanColors.getTextMuted(isDark).copy(alpha = 0.1f), // ✅ UBAH: Dinamis
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOff,
                        contentDescription = null,
                        tint = PimpinanColors.getTextMuted(isDark), // ✅ UBAH: Dinamis
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Tidak ada data pdl",
                    color = PimpinanColors.getTextSecondary(isDark), // ✅ UBAH: Dinamis
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun MenungguPencairanCardClickable(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color(0xFF10B981).copy(alpha = 0.2f)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF10B981), Color(0xFF34D399))
                    )
                )
        ) {
            // Decorative circles
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = (-20).dp, y = (-20).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Menunggu Pencairan Simpanan",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Lihat Daftar →",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Savings,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DaftarSemuaNasabahCardClickable(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = PimpinanColors.primary.copy(alpha = 0.2f)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(PimpinanColors.primaryGradient)
                )
        ) {
            // Decorative circles
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = (-20).dp, y = (-20).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Lihat Seluruh Nasabah",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Daftar Lengkap →",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.People,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

// =========================================================================
// KOMPONEN: Pimpinan Hero Summary Card
// =========================================================================
@Composable
private fun PimpinanHeroSummaryCard(
    totalAktif: Int,
    totalPiutang: Double,
    jumlahPdl: Int,
    isDark: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = PimpinanColors.primary.copy(alpha = 0.25f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF4338CA))
                    )
                )
        ) {
            // Dekorasi lingkaran di sudut kanan atas
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .offset(x = 260.dp, y = (-40).dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .offset(x = 300.dp, y = 20.dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text(
                    text = "Ringkasan Cabang",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.65f),
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatRupiah(totalPiutang.toInt()),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Total Saldo Berjalan",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.55f)
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HeroStatItem(
                        label = "Nasabah Aktif",
                        value = totalAktif.toString(),
                        icon = Icons.Rounded.People
                    )
                    // Divider vertikal
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(40.dp)
                            .background(Color.White.copy(alpha = 0.2f))
                    )
                    HeroStatItem(
                        label = "Jumlah PDL",
                        value = jumlahPdl.toString(),
                        icon = Icons.Rounded.Groups
                    )
                    // Divider vertikal
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(40.dp)
                            .background(Color.White.copy(alpha = 0.2f))
                    )
                    HeroStatItem(
                        label = "Resmi",
                        value = "Aktif",
                        icon = Icons.Rounded.Verified
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStatItem(
    label: String,
    value: String,
    icon: ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

// =========================================================================
// KOMPONEN: Card Pembukuan (buka di Chrome dengan Firebase auto-login)
// =========================================================================
@Composable
private fun PembukuanCardClickable(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color(0xFFF43F5E).copy(alpha = 0.3f)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFF43F5E), Color(0xFFFB7185))
                    )
                )
        ) {
            // Dekorasi lingkaran
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .offset(x = (-22).dp, y = (-22).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .offset(x = (-5).dp, y = 30.dp)
                    .background(Color.White.copy(alpha = 0.07f), CircleShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Buku Pokok",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Buka Pembukuan",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInNew,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "koperasi-kita.com/pembukuan",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MenuBook,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

// =========================================================================
// KOMPONEN LAMA (untuk backward compatibility jika diperlukan)
// =========================================================================
@Composable
fun AdminReportCardSimple(admin: AdminSummary, navController: NavHostController) {
    AdminReportCardNonClickable(admin = admin, photoUrl = null)
}