package com.example.koperasikitagodangulu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.collectAsState
import java.text.SimpleDateFormat
import java.util.*

// =========================================================================
// ABSENSI SCREEN
// Digunakan oleh: Admin Lapangan, Pimpinan, Koordinator
// Koordinator dapat memilih cabang tempat mereka bekerja hari ini
// =========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbsensiScreen(
    navController: NavController,
    viewModel: PelangganViewModel
) {
    val isDark by viewModel.isDarkMode
    val currentRole by viewModel.currentUserRole.collectAsState()
    val currentCabang by viewModel.currentUserCabang.collectAsState()
    val absensiSendiri by viewModel.absensiSendiri.collectAsState()
    val isLoadingAbsensi by viewModel.isLoadingAbsensi.collectAsState()
    val daftarCabang by viewModel.daftarCabangUntukAbsensi.collectAsState()

    val isKoordinator = currentRole == UserRole.KOORDINATOR

    // Cabang yang dipilih untuk absensi hari ini
    var selectedCabang by remember { mutableStateOf<CabangInfo?>(null) }
    var showCabangDropdown by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var successMsg by remember { mutableStateOf("") }

    val todayDisplay = remember {
        SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("in", "ID")).format(Date())
    }

    val roleLabel = when (currentRole) {
        UserRole.ADMIN_LAPANGAN -> "PDL"
        UserRole.KOORDINATOR -> "Koordinator"
        UserRole.PIMPINAN -> "Pimpinan"
        else -> "Karyawan"
    }

    // Colors
    val bgColor = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val cardColor = if (isDark) Color(0xFF1E293B) else Color.White
    val borderColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val textPrimary = if (isDark) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val textMuted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val primaryColor = Color(0xFF6366F1)
    val successColor = Color(0xFF10B981)

    // Load data saat screen dibuka
    LaunchedEffect(Unit) {
        viewModel.loadAbsensiSendiri()
        if (isKoordinator) {
            viewModel.loadDaftarCabangUntukAbsensi()
        }
    }

    // Untuk non-Koordinator, gunakan cabangId dari currentUserCabang
    // Untuk Koordinator, gunakan selectedCabang yang dipilih user
    val absenCabangId = if (isKoordinator) selectedCabang?.id ?: "" else currentCabang ?: ""
    val absenCabangNama = if (isKoordinator) selectedCabang?.name ?: "" else {
        // Coba ambil nama dari daftarCabang jika tersedia, fallback ke id
        daftarCabang.firstOrNull { it.id == currentCabang }?.name ?: currentCabang ?: ""
    }

    val sudahAbsen = absensiSendiri != null

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Absensi Harian",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            todayDisplay,
                            fontSize = 12.sp,
                            color = textMuted
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Kembali",
                            tint = textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {

            // ---- Info karyawan ----
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    border = CardDefaults.outlinedCardBorder().copy(
                        width = 1.dp
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(Color.White.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Column {
                                Text(roleLabel, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                                Text(
                                    if (isKoordinator) "Pilih cabang untuk absen hari ini"
                                    else "Cabang: ${absenCabangNama.ifBlank { absenCabangId }}",
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // ---- Koordinator: pilih cabang ----
            if (isKoordinator) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Pilih Cabang Hari Ini",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary
                        )
                        if (daftarCabang.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = primaryColor)
                                Text("Memuat daftar cabang...", fontSize = 13.sp, color = textMuted)
                            }
                        } else {
                            ExposedDropdownMenuBox(
                                expanded = showCabangDropdown,
                                onExpandedChange = { showCabangDropdown = it }
                            ) {
                                OutlinedTextField(
                                    value = selectedCabang?.name ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    placeholder = { Text("Pilih cabang...", color = textMuted) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCabangDropdown)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = primaryColor,
                                        unfocusedBorderColor = borderColor,
                                        focusedTextColor = textPrimary,
                                        unfocusedTextColor = textPrimary,
                                        focusedContainerColor = cardColor,
                                        unfocusedContainerColor = cardColor
                                    )
                                )
                                ExposedDropdownMenu(
                                    expanded = showCabangDropdown,
                                    onDismissRequest = { showCabangDropdown = false },
                                    modifier = Modifier.background(cardColor)
                                ) {
                                    daftarCabang.forEach { cabang ->
                                        DropdownMenuItem(
                                            text = { Text(cabang.name, color = textPrimary) },
                                            onClick = {
                                                selectedCabang = cabang
                                                showCabangDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- Status absensi & tombol ----
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "Status Hari Ini",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary
                        )

                        if (isLoadingAbsensi) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = primaryColor)
                                Text("Memeriksa status absensi...", fontSize = 14.sp, color = textMuted)
                            }
                        } else if (sudahAbsen) {
                            // Sudah absen
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Color(0xFFDCFCE7), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = successColor,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        "Sudah Absen",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = successColor
                                    )
                                    Text(
                                        "Pukul ${absensiSendiri?.jam ?: "-"} · ${absensiSendiri?.cabangNama ?: absenCabangNama}",
                                        fontSize = 13.sp,
                                        color = textMuted
                                    )
                                }
                            }
                        } else {
                            // Belum absen
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Color(0xFFFEF9C3), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        tint = Color(0xFFCA8A04),
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        "Belum Absen",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFCA8A04)
                                    )
                                    Text(
                                        "Tekan tombol di bawah untuk mencatat kehadiran",
                                        fontSize = 13.sp,
                                        color = textMuted
                                    )
                                }
                            }

                            // Validasi: Koordinator harus pilih cabang dulu
                            val canAbsen = if (isKoordinator) selectedCabang != null else absenCabangId.isNotBlank()

                            if (!canAbsen && isKoordinator) {
                                Text(
                                    "Pilih cabang terlebih dahulu",
                                    fontSize = 12.sp,
                                    color = Color(0xFFEF4444)
                                )
                            }

                            Button(
                                onClick = {
                                    if (!canAbsen) return@Button
                                    isSubmitting = true
                                    errorMsg = ""
                                    viewModel.submitAbsensi(
                                        cabangId = absenCabangId,
                                        cabangNama = absenCabangNama,
                                        onSuccess = {
                                            isSubmitting = false
                                            successMsg = "Absensi berhasil dicatat!"
                                        },
                                        onFailure = { err ->
                                            isSubmitting = false
                                            errorMsg = err
                                        }
                                    )
                                },
                                enabled = canAbsen && !isSubmitting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                            ) {
                                if (isSubmitting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Menyimpan...", color = Color.White, fontWeight = FontWeight.SemiBold)
                                } else {
                                    Icon(Icons.Rounded.HowToReg, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Absen Sekarang", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ---- Error / Success messages ----
            if (errorMsg.isNotBlank()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Error, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                            Text(errorMsg, fontSize = 13.sp, color = Color(0xFFEF4444))
                        }
                    }
                }
            }

            if (successMsg.isNotBlank()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = successColor, modifier = Modifier.size(20.dp))
                            Text(successMsg, fontSize = 13.sp, color = Color(0xFF16A34A))
                        }
                    }
                }
            }

            // ---- Info tambahan ----
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Info, contentDescription = null, tint = primaryColor, modifier = Modifier.size(18.dp))
                            Text("Informasi Absensi", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        }
                        Text("• Absensi hanya dapat dilakukan sekali per hari", fontSize = 12.sp, color = textMuted)
                        Text("• Absensi digunakan sebagai dasar pemberian uang operasional", fontSize = 12.sp, color = textMuted)
                        if (isKoordinator) {
                            Text("• Pilih cabang tempat Anda bekerja hari ini", fontSize = 12.sp, color = textMuted)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
