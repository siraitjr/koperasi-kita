package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// Modern Color Palette
private object DashboardColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val purpleGradient = listOf(Color(0xFF8B5CF6), Color(0xFFA78BFA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))
    val roseGradient = listOf(Color(0xFFF43F5E), Color(0xFFFB7185))

    val darkBackground = Color(0xFF0F172A)
    val darkSurface = Color(0xFF1E293B)
    val darkCard = Color(0xFF334155)
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingkasanDashboardScreen(
    navController: NavController,
    viewModel: PelangganViewModel = viewModel()
) {
    val daftarPelanggan = viewModel.daftarPelanggan
    val isDark by viewModel.isDarkMode
    val context = LocalContext.current

    val cardColor = if (isDark) DashboardColors.darkCard else DashboardColors.lightSurface
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) DashboardColors.darkBackground else DashboardColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Calculations
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
    val today = dateFormat.format(Calendar.getInstance().time)

    val threeMonthsAgo = Calendar.getInstance().apply {
        add(Calendar.MONTH, -3)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time
    val dateFormatParser = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))

    val nasabahAktif = daftarPelanggan.filter { pelanggan ->
        // Exclude status non-aktif
        if (pelanggan.status == "Disetujui" || pelanggan.status == "Tidak Aktif") return@filter false

        val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
            pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
        }

        val isBelumLunas = totalBayar < pelanggan.totalPelunasan.toLong()

        // ✅ PERBAIKAN: Exclude nasabah menunggu pencairan dari hitungan aktif
        val isMenungguPencairan = pelanggan.statusKhusus == "MENUNGGU_PENCAIRAN"

        // ✅ PERBAIKAN: Exclude nasabah > 3 bulan dari tanggal pencairan/pengajuan
        val tglAcuan = pelanggan.tanggalPencairan.ifBlank {
            pelanggan.tanggalPengajuan.ifBlank { pelanggan.tanggalDaftar }
        }
        val isOverThreeMonths = try {
            val acuanDate = dateFormatParser.parse(tglAcuan)
            acuanDate != null && acuanDate.before(threeMonthsAgo)
        } catch (_: Exception) { false }

        // ✅ PERBAIKAN: Nasabah yang dicairkan hari ini belum dihitung, baru mulai besok
        val isCairHariIni = pelanggan.tanggalPencairan.isNotBlank() && pelanggan.tanggalPencairan == today

        isBelumLunas && !isMenungguPencairan && !isOverThreeMonths && !isCairHariIni && (
                pelanggan.status == "Aktif" ||
                        pelanggan.status.equals("aktif", ignoreCase = true) ||
                        pelanggan.status == "Active"
                )
    }

    val totalTagihanDariCicilan: Long = daftarPelanggan
        .flatMap { pelanggan ->
            pelanggan.pembayaranList.flatMap { pembayaran ->
                val pembayaranUtama = if (pembayaran.tanggal == today) {
                    listOf(pembayaran.jumlah.toLong())
                } else {
                    emptyList()
                }
                val subPembayaran = pembayaran.subPembayaran
                    .filter { sub -> sub.tanggal == today }
                    .map { sub -> sub.jumlah.toLong() }
                pembayaranUtama + subPembayaran
            }
        }
        .sum()

    // Sisa utang lanjut pinjaman sudah tercatat di pembayaranList via Cloud Function
    // sehingga sudah terhitung di totalTagihanDariCicilan
    val totalTagihanHariIni: Long = totalTagihanDariCicilan

    // ✅ Target harian = besarPinjaman × 3% (nasabah > 3 bulan sudah difilter di nasabahAktif)
    val targetHarian: Long = nasabahAktif.sumOf { pelanggan ->
        (pelanggan.besarPinjaman * 3L / 100L)
    }

    val nasabahBaruHariIni = daftarPelanggan.count { pelanggan ->
        if (pelanggan.tanggalPencairan.isNotBlank()) {
            // Nasabah baru (flow pencairan): hitung berdasarkan tanggal pencairan
            pelanggan.tanggalPencairan == today && pelanggan.status == "Aktif"
        } else {
            // Backward compatibility: nasabah lama tanpa tanggalPencairan
            pelanggan.tanggalDaftar == today || pelanggan.tanggalPengajuan == today
        }
    }

    val nasabahLunasHariIni = daftarPelanggan.count { pelanggan ->
        val totalBayar = pelanggan.pembayaranList.sumOf { pay ->
            pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
        }
        val isLunas = totalBayar >= pelanggan.totalPelunasan.toLong()
        val adaPembayaranHariIni = pelanggan.pembayaranList.any { pay ->
            pay.tanggal == today || pay.subPembayaran.any { sub -> sub.tanggal == today }
        }
        isLunas && adaPembayaranHariIni
    }

    val totalPiutang: Long = nasabahAktif.sumOf { pel ->
        val totalBayar: Long = pel.pembayaranList.sumOf { pay ->
            pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
        }
        (pel.totalPelunasan - totalBayar).coerceAtLeast(0L)
    }

    val totalPelanggan = daftarPelanggan.size

    val pelangganLunas = daftarPelanggan.count { p ->
        val totalBayar = p.pembayaranList.sumOf { pay ->
            pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
        }
        val isLunasCicilan = totalBayar >= p.totalPelunasan.toLong() && p.totalPelunasan > 0

        // ✅ Nasabah lunas cicilan tapi masih memiliki tabungan (belum dicairkan)
        // Exclude nasabah yang ditandai manual sebagai Sisa Tabungan (tampil di card tersendiri)
        // Exclude nasabah yang sedang proses lanjut pinjaman
        isLunasCicilan &&
                p.statusPencairanSimpanan != "Dicairkan" &&
                p.statusKhusus != "MENUNGGU_PENCAIRAN" &&
                p.status != "Menunggu Approval"
    }

    val persentaseTarget: Float = if (targetHarian > 0) {
        totalTagihanHariIni.toFloat() / targetHarian
    } else {
        0f
    }

    val pelangganMenungguPencairan = viewModel.getJumlahPelangganMenungguPencairan()

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernDashboardTopBar(
                title = "Ringkasan Dashboard",
                subtitle = today,
                isDark = isDark,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated Progress Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500)) + slideInVertically(
                    initialOffsetY = { -50 },
                    animationSpec = tween(500)
                )
            ) {
                ModernProgressCard(
                    persentaseTarget = persentaseTarget,
                    totalTagihan = totalTagihanHariIni,
                    targetHarian = targetHarian,
                    isDark = isDark,
                    onClick = { navController.navigate("laporanHarian") }
                )
            }

            // Stats Grid
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 50 },
                    animationSpec = tween(500, delayMillis = 100)
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Row 1: Total Piutang & Target Harian
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ModernStatCard(
                            title = "Total Saldo",
                            value = "Rp ${formatRupiah(totalPiutang.toInt())}",
                            icon = Icons.Rounded.AccountBalance,
                            gradient = DashboardColors.dangerGradient,
                            modifier = Modifier.weight(1f)
                        )
                        ModernStatCard(
                            title = "Target Harian",
                            value = "Rp ${formatRupiah(targetHarian.toInt())}",
                            icon = Icons.Rounded.TrendingUp,
                            gradient = DashboardColors.infoGradient,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Row 2: Nasabah Baru & Lunas Hari Ini
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ModernStatCard(
                            title = "Drop Hari Ini",
                            value = nasabahBaruHariIni.toString(),
                            icon = Icons.Rounded.PersonAdd,
                            gradient = DashboardColors.primaryGradient,
                            modifier = Modifier.weight(1f),
                            onClick = { navController.navigate("daftarNasabahBaruHariIni") }
                        )
                        ModernStatCard(
                            title = "Lunas Hari Ini",
                            value = nasabahLunasHariIni.toString(),
                            icon = Icons.Rounded.Verified,
                            gradient = DashboardColors.successGradient,
                            modifier = Modifier.weight(1f),
                            onClick = { navController.navigate("daftarNasabahLunasHariIni") }
                        )
                    }

                    // Row 3: Nasabah Lunas & Sisa Tabungan
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ModernStatCard(
                            title = "Nasabah Lunas",
                            value = pelangganLunas.toString(),
                            icon = Icons.Rounded.CheckCircle,
                            gradient = DashboardColors.tealGradient,
                            modifier = Modifier.weight(1f),
                            onClick = { navController.navigate("daftarPelangganLunas") }
                        )
                        ModernStatCard(
                            title = "Sisa Tabungan",
                            value = pelangganMenungguPencairan.toString(),
                            icon = Icons.Rounded.Savings,
                            gradient = DashboardColors.purpleGradient,
                            modifier = Modifier.weight(1f),
                            onClick = { navController.navigate("daftarMenungguPencairan") }
                        )
                    }

                    // Buku Pokok: buka di Chrome
                    ModernStatCard(
                        title = "BUKU POKOK",
                        value = "Buka Pembukuan",
                        icon = Icons.Rounded.MenuBook,
                        gradient = DashboardColors.roseGradient,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val url = "https://www.koperasi-kita.com"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                setPackage("com.android.chrome")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                // Chrome tidak tersedia, buka di browser default
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernDashboardTopBar(
    title: String,
    subtitle: String,
    isDark: Boolean,
    txtColor: Color,
    subtitleColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) DashboardColors.darkSurface else Color.White,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = subtitleColor
            )
        }
    }
}

@Composable
private fun ModernProgressCard(
    persentaseTarget: Float,
    totalTagihan: Long,
    targetHarian: Long,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = persentaseTarget.coerceIn(0f, 1f),
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "progress"
    )

    val progressColor = when {
        persentaseTarget >= 1f -> DashboardColors.successGradient
        persentaseTarget >= 0.7f -> DashboardColors.warningGradient
        else -> DashboardColors.dangerGradient
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = DashboardColors.primaryGradient.first().copy(alpha = 0.2f),
                spotColor = DashboardColors.primaryGradient.first().copy(alpha = 0.2f)
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1E293B),
                            Color(0xFF334155)
                        )
                    )
                )
                .padding(24.dp)
        ) {
            // Decorative circles
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .offset(x = (-30).dp, y = (-30).dp)
                    .background(Color.White.copy(alpha = 0.05f), CircleShape)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Storting Hari Ini",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Rp ${formatRupiah(totalTagihan.toInt())}",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "dari target Rp ${formatRupiah(targetHarian.toInt())}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Progress indicator text
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(
                                    Brush.linearGradient(progressColor),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${(persentaseTarget * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = if (persentaseTarget >= 1f) "Target Tercapai! 🎉" else "dari target",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }

                // Circular Progress
                Box(
                    modifier = Modifier.size(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 12.dp.toPx()
                        val diameter = size.minDimension - strokeWidth
                        val radius = diameter / 2f

                        // Background circle
                        drawCircle(
                            color = Color.White.copy(alpha = 0.1f),
                            radius = radius,
                            style = Stroke(width = strokeWidth)
                        )

                        // Progress arc
                        drawArc(
                            brush = Brush.sweepGradient(progressColor),
                            startAngle = -90f,
                            sweepAngle = 360f * animatedProgress,
                            useCenter = false,
                            topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                            size = Size(diameter, diameter),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${(persentaseTarget * 100).toInt()}",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "%",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = gradient.first().copy(alpha = 0.2f),
                spotColor = gradient.first().copy(alpha = 0.2f)
            )
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradient))
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = title,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = value,
                    color = Color.White,
                    fontSize = if (value.length > 15) 16.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (onClick != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Lihat Detail",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowForward,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}