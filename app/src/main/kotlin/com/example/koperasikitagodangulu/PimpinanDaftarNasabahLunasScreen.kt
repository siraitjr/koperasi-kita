package com.example.koperasikitagodangulu

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.backgroundColor
import com.example.koperasikitagodangulu.utils.textColor
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// =========================================================================
// DATA CLASS
// =========================================================================
data class NasabahLunasItem(
    val pelangganId: String = "",
    val namaPanggilan: String = "",
    val namaKtp: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val totalPinjaman: Int = 0,
    val totalDibayar: Int = 0,
    val wilayah: String = "",
    val tanggalLunas: String = "",
    val timestamp: Long = 0
)

// =========================================================================
// PIMPINAN DAFTAR NASABAH LUNAS SCREEN
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanDaftarNasabahLunasScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val tanggalHariIni = SimpleDateFormat("dd MMM yyyy", Locale("id")).format(Date())
    val isDark by viewModel.isDarkMode
//    val bgColor = if (isDark) PimpinanColors.darkBackground else PimpinanColors.lightBackground
    val txtColor = if (isDark) Color.White else PimpinanColors.textPrimary
    // ✅ Set status bar & navigation bar sesuai tema dashboard
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val cabangId by viewModel.currentUserCabang.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // State untuk data
    var nasabahLunasList by remember { mutableStateOf<List<NasabahLunasItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    // Function untuk load data
    suspend fun loadData() {
        if (cabangId == null) return

        isLoading = true
        errorMessage = null

        try {
            val database = Firebase.database("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app").reference

            val eventSnapshot = database.child("event_harian")
                .child(cabangId!!)
                .child(tanggalHariIni)
                .child("nasabah_lunas")
                .get().await()

            val list = mutableListOf<NasabahLunasItem>()

            if (eventSnapshot.exists() && eventSnapshot.childrenCount > 0) {
                eventSnapshot.children.forEach { child ->
                    val item = NasabahLunasItem(
                        pelangganId = child.key ?: "",
                        namaPanggilan = child.child("namaPanggilan").getValue(String::class.java) ?: "",
                        namaKtp = child.child("namaKtp").getValue(String::class.java) ?: "",
                        adminUid = child.child("adminUid").getValue(String::class.java) ?: "",
                        adminName = child.child("adminName").getValue(String::class.java) ?: "",
                        totalPinjaman = child.child("totalPinjaman").getValue(Int::class.java) ?: 0,
                        totalDibayar = child.child("totalDibayar").getValue(Int::class.java) ?: 0,
                        wilayah = child.child("wilayah").getValue(String::class.java) ?: "",
                        tanggalLunas = child.child("tanggalLunas").getValue(String::class.java) ?: tanggalHariIni,
                        timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0
                    )
                    list.add(item)
                }
                Log.d("NasabahLunas", "✅ Loaded ${list.size} from event_harian")
            } else {
                Log.d("NasabahLunas", "ℹ️ No data in event_harian/nasabah_lunas")
            }

            nasabahLunasList = list.sortedByDescending { it.timestamp }

        } catch (e: Exception) {
            Log.e("NasabahLunas", "Error loading: ${e.message}")
            errorMessage = "Gagal memuat data: ${e.message}"
        }

        isLoading = false
        isVisible = true
    }

    LaunchedEffect(cabangId) {
        loadData()
    }

    val totalPinjaman = nasabahLunasList.sumOf { it.totalPinjaman }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernNasabahLunasTopBar(
                tanggal = tanggalHariIni,
                onBack = { navController.popBackStack() },
                onRefresh = { coroutineScope.launch { loadData() } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Summary Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400)
                )
            ) {
                ModernNasabahLunasSummaryCard(
                    jumlahNasabah = nasabahLunasList.size,
                    totalPinjamanSelesai = totalPinjaman
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Loading / Error / Content
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PimpinanColors.success)
                    }
                }
                errorMessage != null -> {
                    ModernLunasErrorState(
                        message = errorMessage!!,
                        onRetry = { coroutineScope.launch { loadData() } }
                    )
                }
                nasabahLunasList.isEmpty() -> {
                    ModernEmptyNasabahLunasState(isDark = isDark)
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(nasabahLunasList, key = { it.pelangganId }) { item ->
                            ModernNasabahLunasCard(item, isDark, txtColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernNasabahLunasTopBar(
    tanggal: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
            ),
        color = Color.Transparent,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(PimpinanColors.successGradient),
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                )
        ) {
            // Decorative circles
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = (-20).dp, y = (-20).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 15.dp, y = (-10).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = "Kembali",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = "Nasabah Lunas Hari Ini",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tanggal,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(onClick = onRefresh) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernNasabahLunasSummaryCard(
    jumlahNasabah: Int,
    totalPinjamanSelesai: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = PimpinanColors.success.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(PimpinanColors.successGradient))
        ) {
            // Decorative circle
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 30.dp, y = (-30).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Total Nasabah Lunas",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$jumlahNasabah orang",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Total Pinjaman Selesai",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatRupiah(totalPinjamanSelesai),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernNasabahLunasCard(
    item: NasabahLunasItem,
    isDark: Boolean,
    txtColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) PimpinanColors.darkCard else Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(PimpinanColors.successGradient),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.namaPanggilan.ifBlank { item.namaKtp },
                            fontWeight = FontWeight.Bold,
                            color = txtColor,
                            fontSize = 15.sp
                        )
                        if (item.wilayah.isNotBlank()) {
                            Text(
                                text = item.wilayah,
                                fontSize = 12.sp,
                                color = PimpinanColors.getTextSecondary(isDark)
                            )
                        }
                    }
                }

                // Badge LUNAS
                Surface(
                    color = PimpinanColors.success.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = null,
                            tint = PimpinanColors.success,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "LUNAS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PimpinanColors.success
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Total Pinjaman",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.totalPinjaman),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Total Dibayar",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextMuted(isDark)
                    )
                    Text(
                        text = formatRupiah(item.totalDibayar),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PimpinanColors.success
                    )
                }
            }

            if (item.adminName.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = PimpinanColors.getBorder(isDark))
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = PimpinanColors.getTextMuted(isDark),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "PDL: ${item.adminName}",
                        fontSize = 11.sp,
                        color = PimpinanColors.getTextSecondary(isDark)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernEmptyNasabahLunasState(
    isDark: Boolean = false
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        PimpinanColors.success.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = null,
                    tint = PimpinanColors.success,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Tidak ada nasabah lunas hari ini",
                color = PimpinanColors.getTextSecondary(isDark),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ModernLunasErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        PimpinanColors.danger.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = null,
                    tint = PimpinanColors.danger,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                color = PimpinanColors.danger,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PimpinanColors.danger
                )
            ) {
                Text("Coba Lagi")
            }
        }
    }
}