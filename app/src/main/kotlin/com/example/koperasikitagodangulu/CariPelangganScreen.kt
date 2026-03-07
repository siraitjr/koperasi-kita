package com.example.koperasikitagodangulu

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.koperasikitagodangulu.utils.capitalizeWords
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

// Modern Color Palette
private object CariPelangganColors {
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
fun CariPelangganScreen(
    navController: NavController,
    viewModel: PelangganViewModel
) {
    val context = LocalContext.current
    val auth = Firebase.auth
    val isDark by viewModel.isDarkMode

    // Theme colors
    val cardColor = if (isDark) CariPelangganColors.darkCard else CariPelangganColors.lightSurface
    val borderColor = if (isDark) CariPelangganColors.darkBorder else CariPelangganColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) CariPelangganColors.darkBackground else CariPelangganColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // State variables
    var namaKtp by remember { mutableStateOf("") }
    var nik by remember { mutableStateOf("") }
    var alamatKtp by remember { mutableStateOf("") }
    var fotoKtpUri by remember { mutableStateOf<Uri?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var isProcessingScan by remember { mutableStateOf(false) }

    // Dialog states
    var showResultDialog by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<NikSearchResult?>(null) }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Validation
    val isFormValid = nik.length == 16 && namaKtp.length >= 3

    Scaffold(
        containerColor = bgColor,
        topBar = {
            CariPelangganTopBar(
                isDark = isDark,
                txtColor = txtColor,
                onBack = { navController.popBackStack() }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Info Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400)
                )
            ) {
                InfoHeaderCard(
                    isDark = isDark,
                    cardColor = cardColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            // Scan KTP Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                ScanKtpCard(
                    fotoKtpUri = fotoKtpUri,
                    onScanClick = { showScanner = true },
                    onClearImage = { fotoKtpUri = null },
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            // Form Input Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { -20 },
                    animationSpec = tween(400, delayMillis = 200)
                )
            ) {
                FormInputCard(
                    namaKtp = namaKtp,
                    onNamaKtpChange = { namaKtp = it },
                    nik = nik,
                    onNikChange = { if (it.length <= 16 && it.all { c -> c.isDigit() }) nik = it },
                    alamatKtp = alamatKtp,
                    onAlamatKtpChange = { alamatKtp = it },
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search Button
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 300)) + slideInVertically(
                    initialOffsetY = { 20 },
                    animationSpec = tween(400, delayMillis = 300)
                )
            ) {
                SearchButton(
                    enabled = isFormValid && !isSearching,
                    isSearching = isSearching,
                    onClick = {
                        isSearching = true
                        viewModel.searchNikGlobal(
                            nik = nik,
                            onResult = { result ->
                                isSearching = false
                                searchResult = result
                                showResultDialog = true
                            }
                        )
                    }
                )
            }
        }
    }

    // ==================== KTP SCANNER DIALOG (Inline seperti TambahPelangganScreen) ====================
    if (showScanner) {
        Dialog(
            onDismissRequest = {
                if (!isProcessingScan) {
                    showScanner = false
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Posisikan KTP di Dalam Bingkai",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "Pastikan cahaya cukup dan gambar tidak buram. Foto akan otomatis diambil dan dipindai.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // KtpScanner Component
                KtpScanner(
                    modifier = Modifier.fillMaxWidth(),
                    onScanResult = { completeResult: CompleteScanResult ->
                        val ktpData = completeResult.ktpData
                        val imageUri = completeResult.imageUri

                        Log.d("CariPelanggan", "Scan result received:")
                        Log.d("CariPelanggan", "- NIK: ${ktpData.nik}")
                        Log.d("CariPelanggan", "- Nama: ${ktpData.nama}")
                        Log.d("CariPelanggan", "- Alamat: ${ktpData.alamat}")

                        if (ktpData.error.isNullOrEmpty()) {
                            ktpData.nik?.let { nik = it }
                            ktpData.nama?.let { namaKtp = it.capitalizeWords() }
                            ktpData.alamat?.let { alamatKtp = it.capitalizeWords() }
                            imageUri?.let { fotoKtpUri = it }

                            Toast.makeText(
                                context,
                                "✅ Scan KTP Berhasil!\nNama: $namaKtp\nNIK: $nik",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                "❌ Scan gagal: ${ktpData.error}\nSilakan coba lagi atau input manual",
                                Toast.LENGTH_LONG
                            ).show()
                            // Tetap simpan foto meskipun OCR gagal
                            imageUri?.let { fotoKtpUri = it }
                        }

                        showScanner = false
                    },
                    onDismiss = {
                        if (!isProcessingScan) {
                            showScanner = false
                        }
                    }
                )

                if (!isProcessingScan) {
                    Button(
                        onClick = { showScanner = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CariPelangganColors.primary
                        )
                    ) {
                        Text("Input Manual")
                    }
                }
            }
        }
    }

    // Result Dialog
    if (showResultDialog && searchResult != null) {
        SearchResultDialog(
            result = searchResult!!,
            namaKtp = namaKtp,
            nik = nik,
            alamatKtp = alamatKtp,
            fotoKtpUri = fotoKtpUri,
            isDark = isDark,
            onDismiss = {
                showResultDialog = false
                searchResult = null
            },
            onNavigateToTambah = { nama, nikVal, alamat, foto ->
                showResultDialog = false
                // Navigate ke TambahPelangganScreen dengan data dari scan
                // Encode nama dan alamat untuk handle spasi dan karakter khusus
                val encodedNama = java.net.URLEncoder.encode(nama, "UTF-8")
                val encodedAlamat = java.net.URLEncoder.encode(alamat, "UTF-8")
                navController.navigate("tambahPelanggan?prefillNama=$encodedNama&prefillNik=$nikVal&prefillAlamat=$encodedAlamat")
            },
            onNavigateToKelola = { pelangganId ->
                showResultDialog = false
                navController.navigate("kelolaKredit/$pelangganId")
            },
            onClose = {
                showResultDialog = false
                navController.popBackStack()
            }
        )
    }
}

// ==================== TOP BAR ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CariPelangganTopBar(
    isDark: Boolean,
    txtColor: Color,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) CariPelangganColors.darkSurface else Color.White,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Kembali",
                    tint = txtColor
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cari Calon Nasabah",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = txtColor
                )
                Text(
                    text = "Verifikasi NIK sebelum pengajuan",
                    fontSize = 13.sp,
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        Brush.linearGradient(CariPelangganColors.primaryGradient),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PersonSearch,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

// ==================== INFO HEADER CARD ====================

// ==================== INFO HEADER CARD (UPDATED MODERN VERSION) ====================

@Composable
private fun InfoHeaderCard(
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = CariPelangganColors.primary.copy(alpha = 0.3f),
                spotColor = CariPelangganColors.primary.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF0F172A) else Color.White
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.3f to CariPelangganColors.primary.copy(alpha = 0.05f),
                        1.0f to Color.Transparent
                    )
                )
        ) {
            // Decorative elements
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = (-30).dp, y = (-30).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                CariPelangganColors.primary.copy(alpha = 0.1f),
                                Color.Transparent
                            ),
                            radius = 80f
                        ),
                        CircleShape
                    )
            )

            Box(
                modifier = Modifier
                    .size(60.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 20.dp, y = 20.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                CariPelangganColors.tealGradient[0].copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            radius = 60f
                        ),
                        CircleShape
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header with icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    // Modern icon container with gradient border
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                Brush.linearGradient(
                                    colors = CariPelangganColors.primaryGradient
                                ),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(3.dp)
                            .background(
                                if (isDark) CariPelangganColors.darkBackground else Color.White,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = null,
                            tint = CariPelangganColors.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = "Verifikasi Calon Nasabah",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else Color(0xFF1E293B),
                            letterSpacing = (-0.2).sp
                        )
                        Text(
                            text = "Cek Status NIK Global",
                            fontSize = 13.sp,
                            color = CariPelangganColors.primary.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Modern chip for status - PERBAIKAN DI SINI
                Surface(
                    modifier = Modifier
                        .padding(bottom = 20.dp)
                        .border(
                            width = 1.dp,
                            color = CariPelangganColors.primary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(10.dp)
                        ),
                    shape = RoundedCornerShape(10.dp),
                    color = CariPelangganColors.primary.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = CariPelangganColors.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Proses Aman & Terverifikasi",
                            fontSize = 12.sp,
                            color = CariPelangganColors.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Content with modern typography
                Text(
                    text = "Scan KTP atau input manual untuk mengecek status NIK di seluruh cabang KSP Si Godang Ulu Jaya",
                    fontSize = 14.sp,
                    color = if (isDark) Color(0xCCFFFFFF) else Color(0xFF475569),
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Additional info in modern layout
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = CariPelangganColors.warning,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hasil pencarian real-time dari semua cabang",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ==================== SCAN KTP CARD ====================

@Composable
private fun ScanKtpCard(
    fotoKtpUri: Uri?,
    onScanClick: () -> Unit,
    onClearImage: () -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = CariPelangganColors.primary.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            Brush.linearGradient(CariPelangganColors.primaryGradient),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Scan KTP Calon Nasabah",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = txtColor
                )
            }

            // Scan Button
            Button(
                onClick = onScanClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CariPelangganColors.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.DocumentScanner,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Scan & Ambil Foto KTP",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Preview Foto KTP
            if (fotoKtpUri != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                ) {
                    AsyncImage(
                        model = fotoKtpUri,
                        contentDescription = "Preview KTP",
                        modifier = Modifier.fillMaxSize()
                    )

                    // Tombol hapus
                    IconButton(
                        onClick = onClearImage,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(28.dp)
                            .background(Color.Red.copy(alpha = 0.9f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Hapus",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Badge success
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = CariPelangganColors.success.copy(alpha = 0.9f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Foto tersimpan",
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            } else {
                // Placeholder
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                        .border(
                            2.dp,
                            borderColor.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Badge,
                            contentDescription = null,
                            tint = subtitleColor,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Belum ada foto KTP",
                            fontSize = 13.sp,
                            color = subtitleColor
                        )
                    }
                }
            }

            Text(
                text = "Data akan otomatis terisi dari hasil scan",
                fontSize = 12.sp,
                color = subtitleColor,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ==================== FORM INPUT CARD ====================

@Composable
private fun FormInputCard(
    namaKtp: String,
    onNamaKtpChange: (String) -> Unit,
    nik: String,
    onNikChange: (String) -> Unit,
    alamatKtp: String,
    onAlamatKtpChange: (String) -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = CariPelangganColors.primary.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            Brush.linearGradient(CariPelangganColors.tealGradient),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Data Calon Nasabah",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor
                    )
                    Text(
                        text = "Lengkapi atau edit data hasil scan",
                        fontSize = 12.sp,
                        color = subtitleColor
                    )
                }
            }

            // Nama Sesuai KTP
            CariTextField(
                value = namaKtp,
                onValueChange = onNamaKtpChange,
                label = "Nama Sesuai KTP",
                placeholder = "Masukkan nama lengkap sesuai KTP",
                leadingIcon = Icons.Rounded.Person,
                isError = namaKtp.isNotEmpty() && namaKtp.length < 3,
                errorText = if (namaKtp.isNotEmpty() && namaKtp.length < 3) "Minimal 3 karakter" else null,
                isDark = isDark,
                cardColor = cardColor,
                borderColor = borderColor,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )

            // NIK
            CariTextField(
                value = nik,
                onValueChange = onNikChange,
                label = "NIK (Nomor Induk Kependudukan)",
                placeholder = "Masukkan 16 digit NIK",
                leadingIcon = Icons.Rounded.Badge,
                isError = nik.isNotEmpty() && nik.length != 16,
                errorText = if (nik.isNotEmpty() && nik.length != 16) "NIK harus 16 digit (${nik.length}/16)" else null,
                keyboardType = KeyboardType.Number,
                isDark = isDark,
                cardColor = cardColor,
                borderColor = borderColor,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )

            // Alamat KTP
            CariTextField(
                value = alamatKtp,
                onValueChange = onAlamatKtpChange,
                label = "Alamat Sesuai KTP",
                placeholder = "Masukkan alamat lengkap sesuai KTP",
                leadingIcon = Icons.Rounded.Home,
                singleLine = false,
                maxLines = 3,
                isDark = isDark,
                cardColor = cardColor,
                borderColor = borderColor,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )
        }
    }
}

// ==================== CUSTOM TEXT FIELD ====================

@Composable
private fun CariTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    leadingIcon: ImageVector,
    isError: Boolean = false,
    errorText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, color = subtitleColor) },
            placeholder = { Text(placeholder, color = subtitleColor.copy(alpha = 0.6f)) },
            leadingIcon = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (isError) CariPelangganColors.danger
                    else if (value.isNotEmpty()) CariPelangganColors.primary
                    else subtitleColor
                )
            },
            isError = isError,
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (isDark) CariPelangganColors.darkSurface else Color.White,
                unfocusedContainerColor = if (isDark) CariPelangganColors.darkSurface else Color.White,
                focusedBorderColor = if (isError) CariPelangganColors.danger else CariPelangganColors.primary,
                unfocusedBorderColor = if (isError) CariPelangganColors.danger else borderColor,
                focusedLabelColor = CariPelangganColors.primary,
                unfocusedLabelColor = subtitleColor,
                focusedTextColor = txtColor,
                unfocusedTextColor = txtColor,
                errorBorderColor = CariPelangganColors.danger,
                errorLabelColor = CariPelangganColors.danger
            )
        )

        if (errorText != null) {
            Text(
                text = errorText,
                fontSize = 12.sp,
                color = CariPelangganColors.danger,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

// ==================== SEARCH BUTTON ====================

@Composable
private fun SearchButton(
    enabled: Boolean,
    isSearching: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                elevation = if (enabled) 8.dp else 0.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = CariPelangganColors.primary.copy(alpha = 0.3f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color(0xFF94A3B8)
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (enabled) Brush.linearGradient(CariPelangganColors.successGradient)
                    else Brush.linearGradient(listOf(Color(0xFF94A3B8), Color(0xFFCBD5E1)))
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSearching) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Mencari di seluruh cabang...",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Cari NIK di Seluruh Cabang",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ==================== SEARCH RESULT DIALOG ====================

@Composable
private fun SearchResultDialog(
    result: NikSearchResult,
    namaKtp: String,
    nik: String,
    alamatKtp: String,
    fotoKtpUri: Uri?,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onNavigateToTambah: (String, String, String, Uri?) -> Unit,
    onNavigateToKelola: (String) -> Unit,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDark) CariPelangganColors.darkCard else Color.White
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon berdasarkan status
                val (icon, gradient, title) = when (result.status) {
                    NikSearchStatus.NOT_FOUND -> Triple(
                        Icons.Rounded.PersonAdd,
                        CariPelangganColors.successGradient,
                        "Nasabah Baru"
                    )
                    NikSearchStatus.ACTIVE_OTHER_ADMIN, NikSearchStatus.ACTIVE_SELF -> Triple(
                        Icons.Rounded.Warning,
                        CariPelangganColors.dangerGradient,
                        "Nasabah Masih Aktif"
                    )
                    NikSearchStatus.LUNAS_SELF -> Triple(
                        Icons.Rounded.CheckCircle,
                        CariPelangganColors.infoGradient,
                        "Nasabah Lunas"
                    )
                    NikSearchStatus.LUNAS_OTHER -> Triple(
                        Icons.Rounded.Info,
                        CariPelangganColors.warningGradient,
                        "Nasabah Lunas di Cabang Lain"
                    )
                }

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            Brush.linearGradient(gradient),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.White else Color(0xFF1E293B)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = result.message,
                    fontSize = 14.sp,
                    color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                // Info tambahan jika ditemukan
                if (result.found && result.pelanggan != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDark) CariPelangganColors.darkSurface else Color(0xFFF8FAFC)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            SearchResultInfoRow(
                                label = "Nama",
                                value = result.pelanggan.namaPanggilan.ifBlank { result.pelanggan.namaKtp }
                            )
                            SearchResultInfoRow(
                                label = "NIK",
                                value = result.pelanggan.nik.ifBlank {
                                    result.pelanggan.nikSuami.ifBlank { result.pelanggan.nikIstri }
                                }
                            )
                            SearchResultInfoRow(label = "Admin", value = result.adminName)
                            SearchResultInfoRow(label = "Status", value = result.pelanggan.status)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Buttons berdasarkan status
                when (result.status) {
                    NikSearchStatus.NOT_FOUND -> {
                        Button(
                            onClick = { onNavigateToTambah(namaKtp, nik, alamatKtp, fotoKtpUri) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CariPelangganColors.success
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Lanjut Daftarkan Nasabah",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isDark) Color(0xFF475569) else Color(0xFFE2E8F0)
                            )
                        ) {
                            Text(
                                "Batal",
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }
                    }
                    NikSearchStatus.LUNAS_SELF -> {
                        Button(
                            onClick = {
                                result.pelanggan?.id?.let { onNavigateToKelola(it) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CariPelangganColors.info
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Lanjut ke Kelola Kredit",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isDark) Color(0xFF475569) else Color(0xFFE2E8F0)
                            )
                        ) {
                            Text(
                                "Batal",
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }
                    }
                    NikSearchStatus.LUNAS_OTHER -> {
                        // Nasabah lunas di admin lain - bisa daftar ulang
                        Button(
                            onClick = { onNavigateToTambah(namaKtp, nik, alamatKtp, fotoKtpUri) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CariPelangganColors.success
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Lanjut Daftarkan Nasabah",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    NikSearchStatus.ACTIVE_OTHER_ADMIN -> {
                        // Nasabah aktif di admin lain - bisa daftar ulang dengan tombol Lanjut
                        Button(
                            onClick = { onNavigateToTambah(namaKtp, nik, alamatKtp, fotoKtpUri) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CariPelangganColors.success
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Lanjut Daftarkan Nasabah",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = onClose,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isDark) Color(0xFF475569) else Color(0xFFE2E8F0)
                            )
                        ) {
                            Text(
                                "Mengerti",
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }
                    }
                    NikSearchStatus.ACTIVE_SELF -> {
                        Button(
                            onClick = onClose,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF64748B)
                            )
                        ) {
                            Text(
                                "Mengerti",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color(0xFF64748B)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1E293B)
        )
    }
}