package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import com.example.koperasikitagodangulu.utils.formatRupiah
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import java.text.NumberFormat
import android.util.Log
import com.example.koperasikitagodangulu.utils.capitalizeWords
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.net.Uri
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// ⚠️ PREREQUISITE:
// Pastikan CompleteScanResult sudah ada di file terpisah (lihat Models.kt)
// atau sudah bisa diakses dari package yang sama

// Modern Color Palette
private object LoanManageColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))

    val darkBackground = Color(0xFF0F172A)
    val darkSurface = Color(0xFF1E293B)
    val darkCard = Color(0xFF334155)
    val darkBorder = Color(0xFF475569)
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFE2E8F0)

    val primary = Color(0xFF6366F1)
    val success = Color(0xFF10B981)
    val warning = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)
    val info = Color(0xFF3B82F6)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KelolaKreditScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    pelangganId: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        Log.d("KelolaKredit", "🚀 SCREEN DIMULAI - pelangganId: $pelangganId")
    }

    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) LoanManageColors.darkCard else LoanManageColors.lightSurface
    val borderColor = if (isDark) LoanManageColors.darkBorder else LoanManageColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) LoanManageColors.darkBackground else LoanManageColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // State variables - Data Pinjaman
    var pinjamanBaru by remember { mutableStateOf("") }
    var tenorBaru by remember { mutableStateOf("24") }
    var showConfirmation by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }

    // State variables - Data KTP (untuk pinjaman < 3jt atau downgrade)
    var namaKtp by remember { mutableStateOf("") }
    var nik by remember { mutableStateOf("") }
    var alamatKtp by remember { mutableStateOf("") }
    var namaPanggilanInput by remember { mutableStateOf("") }

    // ✅ BARU: State untuk data suami-istri (untuk pinjaman >= 3jt atau upgrade)
    var namaKtpSuami by remember { mutableStateOf("") }
    var namaKtpIstri by remember { mutableStateOf("") }
    var nikSuami by remember { mutableStateOf("") }
    var nikIstri by remember { mutableStateOf("") }
    var namaPanggilanSuami by remember { mutableStateOf("") }
    var namaPanggilanIstri by remember { mutableStateOf("") }

    // ✅ BARU: State untuk foto
    var fotoKtpUri by remember { mutableStateOf<Uri?>(null) }
    var fotoKtpSuamiUri by remember { mutableStateOf<Uri?>(null) }
    var fotoKtpIstriUri by remember { mutableStateOf<Uri?>(null) }
    var fotoNasabahUri by remember { mutableStateOf<Uri?>(null) }

    // ✅ BARU: State untuk scanner dan camera
    var showScanner by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableStateOf("nasabah") } // "nasabah", "suami", "istri"
    var completeScanResult by remember { mutableStateOf<CompleteScanResult?>(null) }
    var isProcessingScan by remember { mutableStateOf(false) }
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var currentPhotoType by remember { mutableStateOf("") } // "ktp", "ktp_suami", "ktp_istri", "nasabah"

    // ✅ BARU: State untuk upload/processing
    var isUploading by remember { mutableStateOf(false) }

    val pelanggan = remember(pelangganId) {
        viewModel.daftarPelanggan.find { it.id == pelangganId }
    }

    // ✅ BARU: Hitung tipe pinjaman
    val pinjamanBaruInt = remember(pinjamanBaru) {
        if (pinjamanBaru.isNotEmpty()) {
            try {
                pinjamanBaru.replace(".", "").replace(",", "").toIntOrNull() ?: 0
            } catch (e: Exception) {
                0
            }
        } else {
            0
        }
    }

    // ✅ BARU: Tentukan tipe pinjaman lama dan baru
    val tipePinjamanLama = pelanggan?.tipePinjaman ?: "dibawah_3jt"
    val tipePinjamanBaru = if (pinjamanBaruInt >= 3_000_000) "diatas_3jt" else "dibawah_3jt"
    val isUpgrade = tipePinjamanLama == "dibawah_3jt" && tipePinjamanBaru == "diatas_3jt"
    val isDowngrade = tipePinjamanLama == "diatas_3jt" && tipePinjamanBaru == "dibawah_3jt"

    // ✅ BARU: Camera launchers
    val ktpCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoUri != null) {
            when (currentPhotoType) {
                "ktp" -> fotoKtpUri = tempPhotoUri
                "ktp_suami" -> fotoKtpSuamiUri = tempPhotoUri
                "ktp_istri" -> fotoKtpIstriUri = tempPhotoUri
                "nasabah" -> fotoNasabahUri = tempPhotoUri
            }
            Log.d("KelolaKredit", "✅ Foto $currentPhotoType diambil: $tempPhotoUri")
        }
    }

    // ✅ BARU: Function untuk membuka kamera
    fun openCamera(photoType: String) {
        currentPhotoType = photoType
        val photoFile = File(
            context.cacheDir,
            "foto_${photoType}_${System.currentTimeMillis()}.jpg"
        )
        tempPhotoUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            photoFile
        )
        ktpCameraLauncher.launch(tempPhotoUri)
    }

    LaunchedEffect(pelanggan) {
        Log.d("KelolaKredit", "📊 PELANGGAN DITEMUKAN - ID: ${pelanggan?.id}, Nama: ${pelanggan?.namaPanggilan}, PinjamanKe: ${pelanggan?.pinjamanKe}, TotalPelunasan: ${pelanggan?.totalPelunasan}")

        pelanggan?.let {
            // Data untuk pinjaman < 3jt atau single
            namaKtp = viewModel.getDisplayNamaKtp(it)
            nik = viewModel.getDisplayNik(it)
            alamatKtp = it.alamatKtp
            namaPanggilanInput = viewModel.getDisplayName(it)

            // ✅ BARU: Data untuk pinjaman >= 3jt (suami-istri)
            if (it.tipePinjaman == "diatas_3jt") {
                namaKtpSuami = it.namaKtpSuami
                namaKtpIstri = it.namaKtpIstri
                nikSuami = it.nikSuami
                nikIstri = it.nikIstri
                namaPanggilanSuami = it.namaPanggilanSuami
                namaPanggilanIstri = it.namaPanggilanIstri
            }
        }
    }

    // Handle scan result
    LaunchedEffect(completeScanResult) {
        completeScanResult?.let { result ->
            val ktpData = result.ktpData
            val imageUri = result.imageUri

            if (ktpData.error.isNullOrEmpty()) {
                when (scanTarget) {
                    "nasabah" -> {
                        ktpData.nik?.let { nik = it }
                        ktpData.nama?.let { namaKtp = it }
                        ktpData.alamat?.let { alamatKtp = it }
                        if (namaPanggilanInput.isEmpty()) {
                            ktpData.nama?.split(" ")?.firstOrNull()?.let {
                                namaPanggilanInput = it
                            }
                        }
                        // Simpan foto KTP dari scanner
                        imageUri?.let { fotoKtpUri = it }
                    }
                    "suami" -> {
                        ktpData.nik?.let { nikSuami = it }
                        ktpData.nama?.let { namaKtpSuami = it }
                        ktpData.alamat?.let { alamatKtp = it }
                        if (namaPanggilanSuami.isEmpty()) {
                            ktpData.nama?.split(" ")?.firstOrNull()?.let {
                                namaPanggilanSuami = it
                            }
                        }
                        // Simpan foto KTP Suami dari scanner
                        imageUri?.let { fotoKtpSuamiUri = it }
                    }
                    "istri" -> {
                        ktpData.nik?.let { nikIstri = it }
                        ktpData.nama?.let { namaKtpIstri = it }
                        if (namaPanggilanIstri.isEmpty()) {
                            ktpData.nama?.split(" ")?.firstOrNull()?.let {
                                namaPanggilanIstri = it
                            }
                        }
                        // Simpan foto KTP Istri dari scanner
                        imageUri?.let { fotoKtpIstriUri = it }
                    }
                }
                showScanner = false
            } else {
                // Jika OCR gagal tapi foto berhasil, tetap simpan foto
                imageUri?.let { uri ->
                    when (scanTarget) {
                        "nasabah" -> fotoKtpUri = uri
                        "suami" -> fotoKtpSuamiUri = uri
                        "istri" -> fotoKtpIstriUri = uri
                    }
                }
                showScanner = false  // ← TAMBAHKAN BARIS INI
            }
            isProcessingScan = false
            completeScanResult = null
        }
    }

    // Calculate current debt status
    val totalBayar = pelanggan?.pembayaranList?.sumOf { pembayaran ->
        pembayaran.jumlah + pembayaran.subPembayaran.sumOf { sub -> sub.jumlah }
    } ?: 0
    val totalPelunasanLama = pelanggan?.totalPelunasan ?: 0
    val sisaUtangLama = totalPelunasanLama - totalBayar
    val sudahLunas = sisaUtangLama <= 0
    val namaPanggilanDisplay = pelanggan?.namaPanggilan ?: ""

    LaunchedEffect(totalBayar, totalPelunasanLama, sisaUtangLama, sudahLunas) {
        Log.d("KelolaKredit", "🧮 PERHITUNGAN UTANG - TotalBayar: $totalBayar, TotalPelunasanLama: $totalPelunasanLama, SisaUtangLama: $sisaUtangLama, SudahLunas: $sudahLunas")
    }

    val tenorBaruInt = tenorBaru.toIntOrNull() ?: 30

    val calculation = remember(pinjamanBaruInt) {
        if (pinjamanBaruInt > 0) {
            viewModel.calculatePinjamanValues(pinjamanBaruInt)
        } else {
            null
        }
    }

    val totalUtangBaru = (calculation?.totalPelunasan ?: 0) + max(sisaUtangLama, 0)

    LaunchedEffect(pinjamanBaru, pinjamanBaruInt, calculation) {
        Log.d("KelolaKredit", "pinjamanBaru: '$pinjamanBaru'")
        Log.d("KelolaKredit", "pinjamanBaruInt: $pinjamanBaruInt")
        Log.d("KelolaKredit", "calculation: $calculation")
        Log.d("KelolaKredit", "tipePinjamanBaru: $tipePinjamanBaru, isUpgrade: $isUpgrade, isDowngrade: $isDowngrade")
    }

    // ✅ BARU: Validasi foto berdasarkan tipe pinjaman
    val isFotoValid = when (tipePinjamanBaru) {
        "dibawah_3jt" -> {
            fotoKtpUri != null && fotoNasabahUri != null
        }
        "diatas_3jt" -> {
            fotoKtpSuamiUri != null && fotoKtpIstriUri != null && fotoNasabahUri != null
        }
        else -> false
    }

    // ✅ BARU: Validasi data berdasarkan tipe pinjaman
    val isDataValid = when (tipePinjamanBaru) {
        "dibawah_3jt" -> {
            namaKtp.isNotBlank() && nik.length == 16 && alamatKtp.isNotBlank() && namaPanggilanInput.isNotBlank()
        }
        "diatas_3jt" -> {
            if (isUpgrade) {
                // Upgrade: perlu data suami istri lengkap
                namaKtpSuami.isNotBlank() && nikSuami.length == 16 &&
                        namaKtpIstri.isNotBlank() && nikIstri.length == 16 &&
                        namaPanggilanSuami.isNotBlank() && namaPanggilanIstri.isNotBlank() &&
                        alamatKtp.isNotBlank()
            } else {
                // Sudah punya data suami istri
                alamatKtp.isNotBlank()
            }
        }
        else -> false
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDark) LoanManageColors.darkSurface else Color.White,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Kembali",
                            tint = txtColor
                        )
                    }

                    Text(
                        text = "Kelola Pinjaman",
                        modifier = Modifier.weight(1f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = txtColor
                    )

                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        }
    ) { innerPadding ->
        if (pelanggan == null) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                ModernEmptyStateLoan(
                    icon = Icons.Rounded.PersonOff,
                    title = "Nasabah Tidak Ditemukan",
                    message = "Data nasabah tidak tersedia",
                    color = LoanManageColors.warning
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Current Debt Status Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -30 },
                    animationSpec = tween(400)
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = if (sudahLunas) LoanManageColors.success.copy(alpha = 0.2f)
                            else LoanManageColors.warning.copy(alpha = 0.2f)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    if (sudahLunas) LoanManageColors.successGradient
                                    else LoanManageColors.warningGradient
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

                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (sudahLunas) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Status Utang Saat Ini",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = if (sudahLunas) "LUNAS" else "BELUM LUNAS",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                            }

                            ModernInfoRowLoan("Nama Nasabah", namaPanggilanDisplay, Color.White)
                            ModernInfoRowLoan("Pinjaman Ke", pelanggan.pinjamanKe.toString(), Color.White)
                            ModernInfoRowLoan("Tipe Pinjaman", if (tipePinjamanLama == "diatas_3jt") "≥ 3 Juta" else "< 3 Juta", Color.White)
                            ModernInfoRowLoan("Total Pelunasan", "Rp ${formatRupiah(totalPelunasanLama)}", Color.White)
                            ModernInfoRowLoan("Total Dibayar", "Rp ${formatRupiah(totalBayar)}", Color.White)
                            ModernInfoRowLoan("Sisa Utang", "Rp ${formatRupiah(max(sisaUtangLama, 0))}", Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // New Loan Input Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = LoanManageColors.primary.copy(alpha = 0.1f)
                        ),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(LoanManageColors.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AddCard,
                                    contentDescription = null,
                                    tint = LoanManageColors.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                text = "Tambah Pinjaman Baru",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = txtColor
                            )
                        }

                        // New Loan Amount
                        OutlinedTextField(
                            value = pinjamanBaru,
                            onValueChange = { newValue ->
                                val cleanValue = newValue.replace(".", "").replace(",", "")
                                if (cleanValue.all { it.isDigit() } || cleanValue.isEmpty()) {
                                    val numericValue = if (cleanValue.isNotEmpty()) cleanValue.toLong() else 0L
                                    val formatted = if (numericValue > 0) {
                                        NumberFormat.getNumberInstance(Locale("id", "ID")).format(numericValue)
                                    } else ""
                                    pinjamanBaru = formatted
                                    errorMessage = ""
                                }
                            },
                            placeholder = { Text("Masukkan jumlah pinjaman baru", color = subtitleColor) },
                            label = { Text("Jumlah Pinjaman Baru", color = subtitleColor) },
                            leadingIcon = {
                                ModernFieldIcon(Icons.Rounded.Payments, LoanManageColors.warning)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = cardColor,
                                unfocusedContainerColor = cardColor,
                                focusedBorderColor = LoanManageColors.primary,
                                unfocusedBorderColor = borderColor,
                                cursorColor = LoanManageColors.primary,
                                focusedTextColor = txtColor,
                                unfocusedTextColor = txtColor,
                                focusedLabelColor = LoanManageColors.primary,
                                unfocusedLabelColor = subtitleColor
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (pinjamanBaru.isNotEmpty()) {
                            Text(
                                text = "Nilai: ${formatRupiah(pinjamanBaruInt)}",
                                color = subtitleColor,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )

                            // ✅ BARU: Info tipe pinjaman
                            if (pinjamanBaruInt > 0) {
                                val tipeBadgeColor = if (tipePinjamanBaru == "diatas_3jt")
                                    LoanManageColors.warning else LoanManageColors.info
                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = tipeBadgeColor.copy(alpha = 0.1f)
                                    ) {
                                        Text(
                                            text = if (tipePinjamanBaru == "diatas_3jt") "Pinjaman ≥ 3 Juta" else "Pinjaman < 3 Juta",
                                            color = tipeBadgeColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }

                                    if (isUpgrade) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = LoanManageColors.success.copy(alpha = 0.1f)
                                        ) {
                                            Text(
                                                text = "⬆️ Upgrade",
                                                color = LoanManageColors.success,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    } else if (isDowngrade) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = LoanManageColors.info.copy(alpha = 0.1f)
                                        ) {
                                            Text(
                                                text = "⬇️ Downgrade",
                                                color = LoanManageColors.info,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Tenor
                        OutlinedTextField(
                            value = tenorBaru,
                            onValueChange = {
                                if (it.all { char -> char.isDigit() } || it.isEmpty()) {
                                    tenorBaru = it
                                }
                            },
                            placeholder = { Text("Masukkan tenor (hari)", color = subtitleColor) },
                            label = { Text("Tenor (hari)", color = subtitleColor) },
                            leadingIcon = {
                                ModernFieldIcon(Icons.Rounded.CalendarMonth, LoanManageColors.info)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = cardColor,
                                unfocusedContainerColor = cardColor,
                                focusedBorderColor = LoanManageColors.primary,
                                unfocusedBorderColor = borderColor,
                                cursorColor = LoanManageColors.primary,
                                focusedTextColor = txtColor,
                                unfocusedTextColor = txtColor,
                                focusedLabelColor = LoanManageColors.primary,
                                unfocusedLabelColor = subtitleColor
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        val isTenorValid = tenorBaruInt in 24..60
                        if (tenorBaru.isNotEmpty() && !isTenorValid) {
                            Text(
                                text = "Tenor harus antara 24-60 hari",
                                color = LoanManageColors.danger,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }

                        // Rincian Pinjaman Baru
                        AnimatedVisibility(
                            visible = pinjamanBaruInt > 0 && calculation != null,
                            enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                            exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
                        ) {
                            if (calculation != null) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = LoanManageColors.primary.copy(alpha = 0.05f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = "Rincian Pinjaman Baru",
                                            color = txtColor,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 12.dp)
                                        )
                                        ModernInfoRowLoanDetail("Pinjaman Ke", (pelanggan.pinjamanKe + 1).toString(), subtitleColor)
                                        ModernInfoRowLoanDetail("Besar Pinjaman", "Rp ${formatRupiah(pinjamanBaruInt)}", subtitleColor)
                                        ModernInfoRowLoanDetail("Admin (5%)", "Rp ${formatRupiah(calculation.admin)}", subtitleColor)
                                        ModernInfoRowLoanDetail("Simpanan (5%)", "Rp ${formatRupiah(calculation.simpanan)}", subtitleColor)
                                        ModernInfoRowLoanDetail("Total Potongan (10%)", "Rp ${formatRupiah(calculation.jasaPinjaman)}", subtitleColor)

                                        val totalSimpananAkumulasi = viewModel.getTotalSimpananByNama(pelanggan.namaKtp)
                                        val totalSimpananBaru = if (pelanggan.statusPencairanSimpanan == "Dicairkan") {
                                            calculation.simpanan
                                        } else {
                                            totalSimpananAkumulasi + calculation.simpanan
                                        }
                                        ModernInfoRowLoanDetail("Total Simpanan", "Rp ${formatRupiah(totalSimpananBaru)}", subtitleColor)

                                        ModernInfoRowLoanDetail("Total Diterima", "Rp ${formatRupiah(calculation.totalDiterima)}", subtitleColor)

                                        if (sisaUtangLama > 0) {
                                            ModernInfoRowLoanDetail("Sisa Utang Lama (otomatis terbayar)", "- Rp ${formatRupiah(sisaUtangLama)}", LoanManageColors.warning, FontWeight.Medium)
                                            val uangDiserahkan = calculation.totalDiterima - sisaUtangLama
                                            ModernInfoRowLoanDetail("Uang Diserahkan ke Nasabah", "Rp ${formatRupiah(uangDiserahkan)}", LoanManageColors.success, FontWeight.Bold)
                                        }

                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 12.dp),
                                            color = borderColor
                                        )

                                        ModernInfoRowLoanDetail(
                                            "Total Pelunasan Baru",
                                            "Rp ${formatRupiah(calculation.totalPelunasan)}",
                                            LoanManageColors.primary,
                                            FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ✅ BARU: Data KTP Card - Dinamis berdasarkan tipe pinjaman
            AnimatedVisibility(
                visible = isVisible && pinjamanBaruInt > 0,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 200)
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = LoanManageColors.info.copy(alpha = 0.1f)
                        ),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(LoanManageColors.info.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Badge,
                                    contentDescription = null,
                                    tint = LoanManageColors.info,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Data KTP Nasabah",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = txtColor
                                )
                                Text(
                                    text = if (tipePinjamanBaru == "diatas_3jt") "Data Suami & Istri" else "Data Perorangan",
                                    fontSize = 12.sp,
                                    color = subtitleColor
                                )
                            }
                        }

                        // ✅ INFO BANNER untuk upgrade/downgrade
                        if (isUpgrade) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = LoanManageColors.warning.copy(alpha = 0.1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.Info,
                                        contentDescription = null,
                                        tint = LoanManageColors.warning,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Pinjaman ≥ 3jt memerlukan data & foto KTP Suami dan Istri",
                                        color = LoanManageColors.warning,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        } else if (isDowngrade) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = LoanManageColors.info.copy(alpha = 0.1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.Info,
                                        contentDescription = null,
                                        tint = LoanManageColors.info,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Pinjaman < 3jt hanya memerlukan data & foto KTP satu orang",
                                        color = LoanManageColors.info,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        // ✅ FORM INPUT BERDASARKAN TIPE PINJAMAN
                        when (tipePinjamanBaru) {
                            "dibawah_3jt" -> {
                                // Form untuk pinjaman < 3jt (single person)
                                KtpInputForm(
                                    namaKtp = namaKtp,
                                    onNamaKtpChange = { namaKtp = it.capitalizeWords() },
                                    nik = nik,
                                    onNikChange = { nik = it.take(16).filter { c -> c.isDigit() } },
                                    namaPanggilan = namaPanggilanInput,
                                    onNamaPanggilanChange = { namaPanggilanInput = it.capitalizeWords() },
                                    onScanKtp = {
                                        scanTarget = "nasabah"
                                        showScanner = true
                                    },
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )
                            }
                            "diatas_3jt" -> {
                                // Form untuk pinjaman >= 3jt (suami & istri)
                                // === DATA SUAMI ===
                                Text(
                                    text = "📝 Data Suami",
                                    fontWeight = FontWeight.Bold,
                                    color = txtColor,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                KtpInputForm(
                                    namaKtp = namaKtpSuami,
                                    onNamaKtpChange = { namaKtpSuami = it.capitalizeWords() },
                                    nik = nikSuami,
                                    onNikChange = { nikSuami = it.take(16).filter { c -> c.isDigit() } },
                                    namaPanggilan = namaPanggilanSuami,
                                    onNamaPanggilanChange = { namaPanggilanSuami = it.capitalizeWords() },
                                    onScanKtp = {
                                        scanTarget = "suami"
                                        showScanner = true
                                    },
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 16.dp),
                                    color = borderColor
                                )

                                // === DATA ISTRI ===
                                Text(
                                    text = "📝 Data Istri",
                                    fontWeight = FontWeight.Bold,
                                    color = txtColor,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                KtpInputForm(
                                    namaKtp = namaKtpIstri,
                                    onNamaKtpChange = { namaKtpIstri = it.capitalizeWords() },
                                    nik = nikIstri,
                                    onNikChange = { nikIstri = it.take(16).filter { c -> c.isDigit() } },
                                    namaPanggilan = namaPanggilanIstri,
                                    onNamaPanggilanChange = { namaPanggilanIstri = it.capitalizeWords() },
                                    onScanKtp = {
                                        scanTarget = "istri"
                                        showScanner = true
                                    },
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Alamat KTP (untuk semua tipe)
                        OutlinedTextField(
                            value = alamatKtp,
                            onValueChange = { alamatKtp = it.capitalizeWords() },
                            label = { Text("Alamat KTP", color = subtitleColor) },
                            leadingIcon = {
                                ModernFieldIcon(Icons.Rounded.LocationOn, LoanManageColors.primary)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = cardColor,
                                unfocusedContainerColor = cardColor,
                                focusedBorderColor = LoanManageColors.primary,
                                unfocusedBorderColor = borderColor,
                                cursorColor = LoanManageColors.primary,
                                focusedTextColor = txtColor,
                                unfocusedTextColor = txtColor,
                                focusedLabelColor = LoanManageColors.primary,
                                unfocusedLabelColor = subtitleColor
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ✅ BARU: Foto Section Card
            AnimatedVisibility(
                visible = isVisible && pinjamanBaruInt > 0,
                enter = fadeIn(tween(400, delayMillis = 300)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 300)
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = LoanManageColors.tealGradient.first().copy(alpha = 0.1f)
                        ),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        Brush.linearGradient(LoanManageColors.tealGradient),
                                        RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PhotoCamera,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Foto Dokumen",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = txtColor
                                )
                                Text(
                                    text = "⚠️ Foto wajib diambil ulang",
                                    fontSize = 12.sp,
                                    color = LoanManageColors.danger
                                )
                            }
                        }

                        when (tipePinjamanBaru) {
                            "dibawah_3jt" -> {
                                // Foto KTP Nasabah
                                PhotoUploadSection(
                                    title = "Foto KTP Nasabah *",
                                    imageUri = fotoKtpUri,
                                    onTakePhoto = { openCamera("ktp") },
                                    onClear = { fotoKtpUri = null },
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                Spacer(Modifier.height(16.dp))

                                // Foto Nasabah
                                PhotoUploadSection(
                                    title = "Foto Nasabah *",
                                    imageUri = fotoNasabahUri,
                                    onTakePhoto = { openCamera("nasabah") },
                                    onClear = { fotoNasabahUri = null },
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )
                            }
                            "diatas_3jt" -> {
                                // Foto KTP Suami
                                PhotoUploadSection(
                                    title = "Foto KTP Suami *",
                                    imageUri = fotoKtpSuamiUri,
                                    onTakePhoto = { openCamera("ktp_suami") },
                                    onClear = { fotoKtpSuamiUri = null },
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                Spacer(Modifier.height(16.dp))

                                // Foto KTP Istri
                                PhotoUploadSection(
                                    title = "Foto KTP Istri *",
                                    imageUri = fotoKtpIstriUri,
                                    onTakePhoto = { openCamera("ktp_istri") },
                                    onClear = { fotoKtpIstriUri = null },
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                Spacer(Modifier.height(16.dp))

                                // Foto Nasabah
                                PhotoUploadSection(
                                    title = "Foto Nasabah *",
                                    imageUri = fotoNasabahUri,
                                    onTakePhoto = { openCamera("nasabah") },
                                    onClear = { fotoNasabahUri = null },
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                // Info foto serah terima
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = LoanManageColors.info.copy(alpha = 0.1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.Handshake,
                                            contentDescription = null,
                                            tint = LoanManageColors.info,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "📸 Foto serah terima uang akan diambil setelah pinjaman disetujui atasan",
                                            color = LoanManageColors.info,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Messages
            if (errorMessage.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = LoanManageColors.danger.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.Error, null, tint = LoanManageColors.danger)
                        Text(errorMessage, color = LoanManageColors.danger, fontSize = 14.sp)
                    }
                }
            }

            if (successMessage.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = LoanManageColors.success.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = LoanManageColors.success)
                        Text(successMessage, color = LoanManageColors.success, fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ✅ UPDATED: Action Buttons dengan validasi foto
            val isFormValid = pinjamanBaruInt > 0 &&
                    tenorBaruInt in 24..60 &&
                    isDataValid &&
                    isFotoValid

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = subtitleColor
                    )
                ) {
                    Text("Batal", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        if (isFormValid) {
                            Log.d("KelolaKredit", "🎯 TOMBOL TAMBAH PINJAMAN DITEKAN")
                            showConfirmation = true
                        } else {
                            errorMessage = when {
                                pinjamanBaruInt <= 0 -> "Jumlah pinjaman harus lebih dari 0"
                                tenorBaruInt !in 24..60 -> "Tenor harus antara 24-60 hari"
                                !isDataValid -> "Lengkapi semua data KTP"
                                !isFotoValid -> "Lengkapi semua foto yang diperlukan"
                                else -> "Data tidak valid"
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    enabled = !isUploading
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (isFormValid && !isUploading)
                                    Brush.linearGradient(LoanManageColors.primaryGradient)
                                else
                                    Brush.linearGradient(listOf(borderColor, borderColor))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Tambah Pinjaman",
                                fontWeight = FontWeight.Bold,
                                color = if (isFormValid) Color.White else subtitleColor
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Scanner Dialog
    if (showScanner) {
        Dialog(
            onDismissRequest = { showScanner = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Scan KTP ${when(scanTarget) {
                            "suami" -> "Suami"
                            "istri" -> "Istri"
                            else -> "Nasabah"
                        }}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = txtColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "Posisikan KTP di dalam bingkai. Pastikan cahaya cukup dan gambar tidak buram.",
                        fontSize = 14.sp,
                        color = subtitleColor,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    KtpScanner(
                        modifier = Modifier.fillMaxWidth(),
                        onScanResult = { completeResult ->
                            isProcessingScan = true
                            completeScanResult = completeResult
                        },
                        onDismiss = {
                            if (!isProcessingScan) {
                                showScanner = false
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showScanner = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LoanManageColors.primary)
                    ) {
                        Text("Input Manual", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // Confirmation Dialog
    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            shape = RoundedCornerShape(24.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(LoanManageColors.primaryGradient),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.HelpOutline, null, tint = Color.White)
                    }
                    Text("Konfirmasi Tambah Pinjaman", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Apakah Anda yakin ingin menambah pinjaman untuk $namaPanggilanDisplay?")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Pinjaman Baru: Rp ${formatRupiah(pinjamanBaruInt)}", fontWeight = FontWeight.Medium)
                    Text("Tipe Pinjaman: ${if (tipePinjamanBaru == "diatas_3jt") "≥ 3 Juta" else "< 3 Juta"}")
                    Text("Pinjaman Ke: ${(pelanggan?.pinjamanKe ?: 0) + 1}")
                    Text("Admin (5%): Rp ${formatRupiah(calculation?.admin ?: 0)}")
                    Text("Simpanan (5%): Rp ${formatRupiah(calculation?.simpanan ?: 0)}")
                    Text("Jasa Pinjaman (10%): Rp ${formatRupiah(calculation?.jasaPinjaman ?: 0)}")

                    val totalSimpananAkumulasiKonfirmasi = viewModel.getTotalSimpananByNama(pelanggan?.namaKtp ?: "")
                    val totalSimpananKonfirmasi = if (pelanggan?.statusPencairanSimpanan == "Dicairkan") {
                        calculation?.simpanan ?: 0
                    } else {
                        totalSimpananAkumulasiKonfirmasi + (calculation?.simpanan ?: 0)
                    }
                    Text("Total Simpanan: Rp ${formatRupiah(totalSimpananKonfirmasi)}")
                    Text("Tenor: $tenorBaruInt hari")
                    Text("Total Pelunasan Baru: Rp ${formatRupiah(calculation?.totalPelunasan ?: 0)}", fontWeight = FontWeight.Bold, color = LoanManageColors.primary)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("📷 Foto yang akan diupload:", fontWeight = FontWeight.Bold)
                    when (tipePinjamanBaru) {
                        "dibawah_3jt" -> {
                            Text("✅ Foto KTP Nasabah")
                            Text("✅ Foto Nasabah")
                        }
                        "diatas_3jt" -> {
                            Text("✅ Foto KTP Suami")
                            Text("✅ Foto KTP Istri")
                            Text("✅ Foto Nasabah")
                            Text("📌 Foto serah terima akan diambil setelah disetujui", color = LoanManageColors.info, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        Log.d("KelolaKredit", "✅ KONFIRMASI AJUKAN")
                        showConfirmation = false
                        isUploading = true

                        // ✅ PANGGIL FUNCTION DENGAN PARAMETER FOTO
                        viewModel.processTopUpLoanWithApprovalAndPhotos(
                            pelangganId = pelangganId ?: "",
                            pinjamanBaru = pinjamanBaruInt,
                            tenorBaru = tenorBaruInt,
                            // Data KTP berdasarkan tipe pinjaman
                            namaKtp = if (tipePinjamanBaru == "dibawah_3jt") namaKtp else "$namaKtpSuami & $namaKtpIstri",
                            nik = if (tipePinjamanBaru == "dibawah_3jt") nik else "$nikSuami & $nikIstri",
                            alamatKtp = alamatKtp,
                            namaPanggilan = if (tipePinjamanBaru == "dibawah_3jt") namaPanggilanInput else "$namaPanggilanSuami & $namaPanggilanIstri",
                            // Data suami-istri (untuk upgrade)
                            namaKtpSuami = namaKtpSuami,
                            namaKtpIstri = namaKtpIstri,
                            nikSuami = nikSuami,
                            nikIstri = nikIstri,
                            namaPanggilanSuami = namaPanggilanSuami,
                            namaPanggilanIstri = namaPanggilanIstri,
                            // Foto
                            fotoKtpUri = fotoKtpUri,
                            fotoKtpSuamiUri = fotoKtpSuamiUri,
                            fotoKtpIstriUri = fotoKtpIstriUri,
                            fotoNasabahUri = fotoNasabahUri,
                            // Tipe pinjaman baru
                            tipePinjamanBaru = tipePinjamanBaru,
                            onSuccess = {
                                Log.d("KelolaKredit", "🎉 PROSES SUKSES")
                                isUploading = false
                                successMessage = "Pengajuan pinjaman berhasil diajukan! Menunggu approval atasan."
                                errorMessage = ""
                                pinjamanBaru = ""
                                tenorBaru = "30"
                                // Reset foto
                                fotoKtpUri = null
                                fotoKtpSuamiUri = null
                                fotoKtpIstriUri = null
                                fotoNasabahUri = null
                            },
                            onFailure = { exception ->
                                Log.e("KelolaKredit", "❌ PROSES GAGAL: ${exception.message}")
                                isUploading = false
                                errorMessage = "Gagal mengajukan pinjaman: ${exception.message}"
                                successMessage = ""
                            }
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LoanManageColors.primary)
                ) {
                    Text("Ya, Ajukan Pinjaman", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showConfirmation = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Batal")
                }
            }
        )
    }
}

// ==================== HELPER COMPOSABLES ====================

@Composable
private fun KtpInputForm(
    namaKtp: String,
    onNamaKtpChange: (String) -> Unit,
    nik: String,
    onNikChange: (String) -> Unit,
    namaPanggilan: String,
    onNamaPanggilanChange: (String) -> Unit,
    onScanKtp: () -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Nama KTP
        OutlinedTextField(
            value = namaKtp,
            onValueChange = onNamaKtpChange,
            label = { Text("Nama Sesuai KTP", color = subtitleColor) },
            leadingIcon = {
                ModernFieldIcon(Icons.Rounded.Person, LoanManageColors.primary)
            },
            trailingIcon = {
                IconButton(onClick = onScanKtp) {
                    Icon(
                        Icons.Rounded.CameraAlt,
                        "Scan KTP",
                        tint = LoanManageColors.primary
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = cardColor,
                unfocusedContainerColor = cardColor,
                focusedBorderColor = LoanManageColors.primary,
                unfocusedBorderColor = borderColor,
                cursorColor = LoanManageColors.primary,
                focusedTextColor = txtColor,
                unfocusedTextColor = txtColor,
                focusedLabelColor = LoanManageColors.primary,
                unfocusedLabelColor = subtitleColor
            )
        )

        // NIK
        OutlinedTextField(
            value = nik,
            onValueChange = onNikChange,
            label = { Text("NIK", color = subtitleColor) },
            leadingIcon = {
                ModernFieldIcon(Icons.Rounded.CreditCard, LoanManageColors.primary)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            isError = nik.isNotEmpty() && nik.length != 16,
            supportingText = {
                if (nik.isNotEmpty() && nik.length != 16) {
                    Text("NIK harus 16 digit angka", color = LoanManageColors.danger, fontSize = 12.sp)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = cardColor,
                unfocusedContainerColor = cardColor,
                focusedBorderColor = LoanManageColors.primary,
                unfocusedBorderColor = borderColor,
                cursorColor = LoanManageColors.primary,
                focusedTextColor = txtColor,
                unfocusedTextColor = txtColor,
                focusedLabelColor = LoanManageColors.primary,
                unfocusedLabelColor = subtitleColor
            )
        )

        // Nama Panggilan
        OutlinedTextField(
            value = namaPanggilan,
            onValueChange = onNamaPanggilanChange,
            label = { Text("Nama Panggilan", color = subtitleColor) },
            leadingIcon = {
                ModernFieldIcon(Icons.Rounded.Face, LoanManageColors.primary)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = cardColor,
                unfocusedContainerColor = cardColor,
                focusedBorderColor = LoanManageColors.primary,
                unfocusedBorderColor = borderColor,
                cursorColor = LoanManageColors.primary,
                focusedTextColor = txtColor,
                unfocusedTextColor = txtColor,
                focusedLabelColor = LoanManageColors.primary,
                unfocusedLabelColor = subtitleColor
            )
        )
    }
}

@Composable
private fun PhotoUploadSection(
    title: String,
    imageUri: Uri?,
    onTakePhoto: () -> Unit,
    onClear: () -> Unit,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Column {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            color = txtColor,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (imageUri != null) {
            // Preview foto
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, LoanManageColors.success, RoundedCornerShape(12.dp))
            ) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize()
                )

                // Badge sukses
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = LoanManageColors.success
                ) {
                    Text(
                        "✓ Tersimpan",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Tombol hapus
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                        .background(LoanManageColors.danger, CircleShape)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        "Hapus",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Tombol foto ulang
            TextButton(
                onClick = onTakePhoto,
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Foto Ulang", fontSize = 12.sp)
            }
        } else {
            // Placeholder
            Button(
                onClick = onTakePhoto,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LoanManageColors.info.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Rounded.AddAPhoto,
                        contentDescription = null,
                        tint = LoanManageColors.info,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ambil Foto",
                        color = LoanManageColors.info,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Klik untuk membuka kamera",
                        color = subtitleColor,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModernFieldIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ModernInfoRowLoan(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label:", color = color.copy(alpha = 0.8f), fontSize = 14.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ModernInfoRowLoanDetail(label: String, value: String, color: Color, fontWeight: FontWeight = FontWeight.Normal) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label:", color = color.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = fontWeight)
        Text(value, color = color, fontSize = 13.sp, fontWeight = fontWeight)
    }
}

@Composable
private fun ModernEmptyStateLoan(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(color.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            fontSize = 14.sp,
            color = Color.Gray
        )
    }
}

private fun validateInput(pinjamanBaru: Int, tenorBaru: Int): Boolean {
    return pinjamanBaru > 0 && tenorBaru in 24..60
}