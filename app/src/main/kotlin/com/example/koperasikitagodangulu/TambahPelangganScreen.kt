package com.example.koperasikitagodangulu

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Cameraswitch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.example.koperasikitagodangulu.utils.ThousandSeparatorTransformation
import com.example.koperasikitagodangulu.utils.formatRupiahInput
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.utils.capitalizeWords
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log
import com.example.koperasikitagodangulu.utils.backgroundColor
import com.example.koperasikitagodangulu.utils.textColor
import androidx.compose.material3.IconButton
import com.example.koperasikitagodangulu.utils.HolidayUtils
import kotlin.math.roundToInt
import android.net.Uri
import coil.compose.AsyncImage
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.result.PickVisualMediaRequest
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableIntStateOf

// Modern Color Palette untuk TambahPelanggan
private object TambahPelangganColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))
    val purpleGradient = listOf(Color(0xFF8B5CF6), Color(0xFFA78BFA))

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

private fun validateKtpData(nik: String, nama: String, alamat: String): Boolean {
    val isNikValid = nik.length == 16 && nik.all { it.isDigit() }
    val isNamaValid = nama.length >= 3 &&
            nama.any { it.isLetter() } &&
            nama.matches(Regex("[a-zA-Z .]+"))
    val isAlamatValid = alamat.length >= 5
    return isNikValid && isNamaValid && isAlamatValid
}

data class ScanResultState(
    val nama: String = "",
    val nik: String = "",
    val alamat: String = "",
    val showError: Boolean = false,
    val error: String = ""
)

data class CompleteScanResult(
    val ktpData: KtpData,
    val imageUri: Uri?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TambahPelangganScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    prefillNama: String = "",      // PARAMETER BARU
    prefillNik: String = "",       // PARAMETER BARU
    prefillAlamat: String = ""     // PARAMETER BARU
) {
    val context = LocalContext.current
    val auth = Firebase.auth
    val isDark by viewModel.isDarkMode
    val cardColor =
        if (isDark) TambahPelangganColors.darkCard else TambahPelangganColors.lightSurface
    val borderColor =
        if (isDark) TambahPelangganColors.darkBorder else TambahPelangganColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor =
        if (isDark) TambahPelangganColors.darkBackground else TambahPelangganColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val currentUser = auth.currentUser
    var tipePinjaman by remember { mutableStateOf("dibawah_3jt") }
    var namaKtpSuami by remember { mutableStateOf("") }
    var namaKtpIstri by remember { mutableStateOf("") }
    var nikSuami by remember { mutableStateOf("") }
    var nikIstri by remember { mutableStateOf("") }
    var namaPanggilanSuami by remember { mutableStateOf("") }
    var namaPanggilanIstri by remember { mutableStateOf("") }
    var nomorAnggota by remember { mutableStateOf("") }
    var isLoadingNomorAnggota by remember { mutableStateOf(false) }
    var showValidationError by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf("") }
    var showPinjamanValidationError by remember { mutableStateOf(false) }
    var pinjamanValidationMessage by remember { mutableStateOf("") }
    var completeScanResult by remember { mutableStateOf<CompleteScanResult?>(null) }
    var scanResultTrigger by remember { mutableStateOf(0) } // counter untuk force trigger
    var isProcessingScan by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")) }
    var selectedYear by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var selectedDay by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) }
    var tanggalPengajuan by remember {
        mutableStateOf(dateFormat.format(Calendar.getInstance().time))
    }
    var namaKtp by remember { mutableStateOf(prefillNama) }
    var nik by remember { mutableStateOf(prefillNik) }
    var namaPanggilan by remember {
        mutableStateOf(
            if (prefillNama.isNotBlank()) {
                prefillNama.split(" ").firstOrNull()?.capitalizeWords() ?: ""
            } else ""
        )
    }
    var alamatKtp by remember { mutableStateOf(prefillAlamat) }
    var alamatRumah by remember { mutableStateOf("") }
    var detailRumah by remember { mutableStateOf("") }
    var noHp by remember { mutableStateOf("") }
    var jenisUsaha by remember { mutableStateOf("") }
    var pinjamanKe by remember { mutableStateOf("1") }
    var besarPinjaman by remember { mutableStateOf("") }
    var jasaPinjaman by remember { mutableStateOf("10") }
    var tenorValue by remember { mutableStateOf(24) }
    var expandedTenor by remember { mutableStateOf(false) }
    val pilihanTenor = listOf(24, 28, 30, 36, 40)
    var totalPelunasan by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var ktpDataState by remember { mutableStateOf(KtpData()) }
    var scanResult by remember { mutableStateOf<KtpData?>(null) }
    var admin by remember { mutableStateOf("") }
    var simpanan by remember { mutableStateOf("") }
    var isSimpananManual by remember { mutableStateOf(false) }
    var simpananTambahan by remember { mutableStateOf("") }
    var jasa by remember { mutableStateOf("") }
    var totalDiterima by remember { mutableStateOf("") }
    var wilayah by remember { mutableStateOf("") }
    // === URUTAN FINAL (setelah baris 208 / setelah var wilayah) ===
    val nullableUriSaver = Saver<Uri?, String>(
        save = { it?.toString() ?: "" },
        restore = { if (it.isEmpty()) null else Uri.parse(it) }
    )
    var fotoKtpUri by rememberSaveable(stateSaver = nullableUriSaver) { mutableStateOf<Uri?>(null) }
    var fotoKtpSuamiUri by rememberSaveable(stateSaver = nullableUriSaver) { mutableStateOf<Uri?>(null) }
    var fotoKtpIstriUri by rememberSaveable(stateSaver = nullableUriSaver) { mutableStateOf<Uri?>(null) }
    var fotoNasabahUri by rememberSaveable(stateSaver = nullableUriSaver) { mutableStateOf<Uri?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableStateOf("suami") }
    var tempNasabahUri by rememberSaveable(stateSaver = nullableUriSaver) { mutableStateOf<Uri?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var showNikDuplicateDialog by remember { mutableStateOf(false) }
    var nikDuplicateMessage by remember { mutableStateOf("") }
    var nikDuplicateAdminName by remember { mutableStateOf("") }
    var nikDuplicatePelangganNama by remember { mutableStateOf("") }
    var isValidatingNik by remember { mutableStateOf(false) }
    // === STATE UNTUK DIALOG KONFIRMASI KTP ===
    var showKtpConfirmDialog by remember { mutableStateOf(false) }
    var pendingKtpResult by remember { mutableStateOf<CompleteScanResult?>(null) }
    var confirmNama by remember { mutableStateOf("") }
    var confirmNik by remember { mutableStateOf("") }
    var confirmAlamat by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val nikValidationResult by viewModel.nikValidationResult.collectAsState()
    val isValidatingFromVm by viewModel.isValidatingNik.collectAsState()

    var showNasabahCamera by remember { mutableStateOf(false) }


    val nasabahCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempNasabahUri != null) {
            fotoNasabahUri = tempNasabahUri
            Log.d("TambahPelanggan", "✅ Foto nasabah diambil: $tempNasabahUri")
        } else if (!success && tempNasabahUri != null) {
            // Cleanup MediaStore entry jika user batal/gagal
            try {
                context.contentResolver.delete(tempNasabahUri!!, null, null)
            } catch (e: Exception) {
                Log.w("TambahPelanggan", "Cleanup URI gagal: ${e.message}")
            }
        }
    }

    fun createCameraUri(): Uri? {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "foto_nasabah_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KoperasiKita")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        } catch (e: Exception) {
            Log.e("TambahPelanggan", "MediaStore URI gagal, fallback ke FileProvider: ${e.message}")
            // Fallback ke FileProvider untuk device yang tidak support MediaStore
            val photoFile = File(context.cacheDir, "foto_nasabah_${System.currentTimeMillis()}.jpg")
            FileProvider.getUriForFile(context, "${context.packageName}.provider", photoFile)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            showNasabahCamera = true
        } else {
            Toast.makeText(
                context,
                "Izin kamera diperlukan untuk mengambil foto",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun openNasabahCamera() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            showNasabahCamera = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Gallery picker untuk foto KTP - Pinjaman < 3jt
    val ktpGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            fotoKtpUri = it
            Log.d("TambahPelanggan", "✅ Foto KTP dipilih dari galeri: $it")
            Toast.makeText(context, "✅ Foto KTP berhasil dipilih", Toast.LENGTH_SHORT).show()
        }
    }

    // Gallery picker untuk foto KTP Suami - Pinjaman >= 3jt
    val ktpSuamiGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            fotoKtpSuamiUri = it
            Log.d("TambahPelanggan", "✅ Foto KTP Suami dipilih dari galeri: $it")
            Toast.makeText(context, "✅ Foto KTP Suami berhasil dipilih", Toast.LENGTH_SHORT).show()
        }
    }

    // Gallery picker untuk foto KTP Istri - Pinjaman >= 3jt
    val ktpIstriGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            fotoKtpIstriUri = it
            Log.d("TambahPelanggan", "✅ Foto KTP Istri dipilih dari galeri: $it")
            Toast.makeText(context, "✅ Foto KTP Istri berhasil dipilih", Toast.LENGTH_SHORT).show()
        }
    }

    // Animation states
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    LaunchedEffect(Unit) {
        if (currentUser == null) {
            // Jangan langsung redirect - coba tunggu token refresh dulu
            kotlinx.coroutines.delay(2000)
            // Re-check setelah delay
            if (com.google.firebase.ktx.Firebase.auth.currentUser == null) {
                navController.popBackStack() // Kembali ke screen sebelumnya, JANGAN ke auth
            }
        } else {
            isLoadingNomorAnggota = true
            try {
                // ✅ PERBAIKAN: Tambah timeout dan error handling
                nomorAnggota = kotlinx.coroutines.withTimeoutOrNull(10000L) {
                    viewModel.getNextNomorAnggota()
                } ?: run {
                    Log.w("TambahPelanggan", "⚠️ Timeout generating nomor anggota, using fallback")
                    viewModel.formatNomorAnggota("1") // Fallback ke 000001
                }
            } catch (e: Exception) {
                Log.e("TambahPelanggan", "❌ Error generating nomor anggota: ${e.message}")
                nomorAnggota = viewModel.formatNomorAnggota("1") // Fallback ke 000001
            } finally {
                isLoadingNomorAnggota = false
            }
        }
    }

    LaunchedEffect(prefillNik) {
        if (prefillNik.isNotBlank()) {
            Toast.makeText(
                context,
                "📝 Data dari scan KTP sudah terisi otomatis",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(completeScanResult) {
        completeScanResult?.let { result ->
            val ktpData = result.ktpData
            val imageUri = result.imageUri

            Log.d("TambahPelanggan", "Received complete scan result:")
            Log.d("TambahPelanggan", "- NIK: ${ktpData.nik}")
            Log.d("TambahPelanggan", "- Nama: ${ktpData.nama}")
            Log.d("TambahPelanggan", "- Alamat: ${ktpData.alamat}")
            Log.d("TambahPelanggan", "- Image URI: $imageUri")

            if (ktpData.error.isNullOrEmpty()) {
                when (tipePinjaman) {
                    "dibawah_3jt" -> {
                        ktpData.nik?.let {
                            nik = it
                            Log.d("Scan", "NIK diisi: $it")
                        }
                        ktpData.nama?.let {
                            namaKtp = it.capitalizeWords()
                            Log.d("Scan", "Nama KTP diisi: $namaKtp")
                        }

                        ktpData.alamat?.let {
                            alamatKtp = it.capitalizeWords()
                            Log.d("Scan", "✅ Alamat KTP diisi: $alamatKtp")
                        }

                        if (namaPanggilan.isEmpty()) {
                            ktpData.nama?.split(" ")?.firstOrNull()?.let {
                                namaPanggilan = it.capitalizeWords()
                                Log.d("Scan", "Nama Panggilan diisi: $namaPanggilan")
                            }
                        }

                        imageUri?.let {
                            fotoKtpUri = it
                            Log.d("Scan", "Foto KTP disimpan: $it")
                        }

                        val message = buildString {
                            append("✅ Scan KTP Berhasil!\n\n")
                            append("Nama: ${namaKtp}\n")
                            append("NIK: ${nik}\n")
                            append("Alamat: ${alamatKtp}")
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }

                    "diatas_3jt" -> {
                        when (scanTarget) {
                            "suami" -> {
                                ktpData.nik?.let {
                                    nikSuami = it
                                    Log.d("Scan", "NIK Suami diisi: $it")
                                }
                                ktpData.nama?.let {
                                    namaKtpSuami = it.capitalizeWords()
                                    Log.d("Scan", "Nama KTP Suami diisi: $namaKtpSuami")
                                }

                                ktpData.alamat?.let {
                                    alamatKtp = it.capitalizeWords()
                                    Log.d("Scan", "✅ Alamat KTP diisi: $alamatKtp")
                                }

                                if (namaPanggilanSuami.isEmpty()) {
                                    ktpData.nama?.split(" ")?.firstOrNull()?.let {
                                        namaPanggilanSuami = it.capitalizeWords()
                                        Log.d(
                                            "Scan",
                                            "Nama Panggilan Suami diisi: $namaPanggilanSuami"
                                        )
                                    }
                                }

                                imageUri?.let {
                                    fotoKtpSuamiUri = it
                                    Log.d("Scan", "Foto KTP Suami disimpan: $it")
                                }

                                val message = buildString {
                                    append("✅ Scan KTP Suami Berhasil!\n\n")
                                    append("Nama: ${namaKtpSuami}\n")
                                    append("NIK: ${nikSuami}\n")
                                    append("Alamat: ${alamatKtp}")
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }

                            "istri" -> {
                                ktpData.nik?.let {
                                    nikIstri = it
                                    Log.d("Scan", "NIK Istri diisi: $it")
                                }
                                ktpData.nama?.let {
                                    namaKtpIstri = it.capitalizeWords()
                                    Log.d("Scan", "Nama KTP Istri diisi: $namaKtpIstri")
                                }

                                if (alamatKtp.isEmpty()) {
                                    ktpData.alamat?.let {
                                        alamatKtp = it.capitalizeWords()
                                        Log.d("Scan", "✅ Alamat KTP (dari istri) diisi: $alamatKtp")
                                    }
                                }

                                if (namaPanggilanIstri.isEmpty()) {
                                    ktpData.nama?.split(" ")?.firstOrNull()?.let {
                                        namaPanggilanIstri = it.capitalizeWords()
                                        Log.d(
                                            "Scan",
                                            "Nama Panggilan Istri diisi: $namaPanggilanIstri"
                                        )
                                    }
                                }

                                imageUri?.let {
                                    fotoKtpIstriUri = it
                                    Log.d("Scan", "Foto KTP Istri disimpan: $it")
                                }

                                val message = buildString {
                                    append("✅ Scan KTP Istri Berhasil!\n\n")
                                    append("Nama: ${namaKtpIstri}\n")
                                    append("NIK: ${nikIstri}")
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                showScanner = false
            } else {
                val errorMessage = buildString {
                    append("❌ Scan KTP Gagal\n\n")
                    append(ktpData.error ?: "Error tidak diketahui")
                    append("\n\nTips:\n")
                    append("• Pastikan pencahayaan cukup\n")
                    append("• KTP tidak terlipat/rusak\n")
                    append("• Posisikan KTP sejajar dengan kamera")
                }
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()

                completeScanResult?.imageUri?.let { uri ->
                    when (tipePinjaman) {
                        "dibawah_3jt" -> fotoKtpUri = uri
                        "diatas_3jt" -> {
                            when (scanTarget) {
                                "suami" -> fotoKtpSuamiUri = uri
                                "istri" -> fotoKtpIstriUri = uri
                            }
                        }
                    }
                    Log.d("Scan", "Foto tetap disimpan meskipun OCR gagal")
                }
            }

            isProcessingScan = false
        }
    }

    LaunchedEffect(besarPinjaman) {
        if (!isSimpananManual) {
            val pinjaman = besarPinjaman.replace("[^\\d]".toRegex(), "").toLongOrNull() ?: 0L
            val adminVal = (pinjaman * 5) / 100
            val simpananVal = (pinjaman * 5) / 100
            val jasaVal = (pinjaman * 10) / 100
            val totalPelunasanVal = (pinjaman * 120) / 100
            val totalDiterimaVal = pinjaman - (adminVal + simpananVal)

            admin = adminVal.toString()
            simpanan = simpananVal.toString()
            jasa = jasaVal.toString()
            totalPelunasan = totalPelunasanVal.toString()
            totalDiterima = totalDiterimaVal.toString()
        }
    }

    LaunchedEffect(scanResult) {
        scanResult?.let { result ->
            Log.d("TambahPelanggan", "Received scan result: $result")

            if (result.error.isNullOrEmpty()) {
                when (tipePinjaman) {
                    "dibawah_3jt" -> {
                        result.nik?.let { nik = it }
                        result.nama?.let { namaKtp = it }
                        if (namaPanggilan.isEmpty()) {
                            result.nama?.split(" ")?.firstOrNull()?.let {
                                namaPanggilan = it
                            }
                        }
                    }

                    "diatas_3jt" -> {
                        when (scanTarget) {
                            "suami" -> {
                                result.nik?.let { nikSuami = it }
                                result.nama?.let { namaKtpSuami = it }
                                if (namaPanggilanSuami.isEmpty()) {
                                    result.nama?.split(" ")?.firstOrNull()?.let {
                                        namaPanggilanSuami = it
                                    }
                                }
                            }

                            "istri" -> {
                                result.nik?.let { nikIstri = it }
                                result.nama?.let { namaKtpIstri = it }
                                if (namaPanggilanIstri.isEmpty()) {
                                    result.nama?.split(" ")?.firstOrNull()?.let {
                                        namaPanggilanIstri = it
                                    }
                                }
                            }
                        }
                    }
                }
                result.alamat?.let { alamatKtp = it }
                showScanner = false
            } else {
                result.error?.let {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                }
            }
            scanResult = null
        }
    }

    val isFormValid = when (tipePinjaman) {
        "dibawah_3jt" -> remember(
            namaKtp,
            nik,
            alamatKtp,
            alamatRumah,
            detailRumah,
            wilayah,
            jenisUsaha,
            besarPinjaman,
            tenorValue,
            fotoKtpUri,
            fotoNasabahUri
        ) {
            val pinjamanNumerik = besarPinjaman.replace("[^\\d]".toRegex(), "").toLongOrNull() ?: 0L
            val isPinjamanValid = pinjamanNumerik >= 50000

            namaKtp.isNotBlank() &&
                    nik.isNotBlank() && nik.length == 16 &&
                    alamatKtp.isNotBlank() &&
                    alamatRumah.isNotBlank() &&
                    detailRumah.isNotBlank() &&
                    wilayah.isNotBlank() &&
                    jenisUsaha.isNotBlank() &&
                    besarPinjaman.isNotBlank() &&
                    fotoKtpUri != null &&
                    fotoNasabahUri != null &&
                    isPinjamanValid &&
                    tenorValue > 0
        }

        "diatas_3jt" -> remember(
            namaKtpSuami,
            nikSuami,
            namaKtpIstri,
            nikIstri,
            alamatKtp,
            alamatRumah,
            detailRumah,
            wilayah,
            jenisUsaha,
            besarPinjaman,
            tenorValue,
            fotoKtpSuamiUri,
            fotoKtpIstriUri,
            fotoNasabahUri
        ) {
            val pinjamanNumerik = besarPinjaman.replace("[^\\d]".toRegex(), "").toLongOrNull() ?: 0L
            val isPinjamanValid = pinjamanNumerik >= 50000

            namaKtpSuami.isNotBlank() &&
                    nikSuami.isNotBlank() && nikSuami.length == 16 &&
                    namaKtpIstri.isNotBlank() &&
                    nikIstri.isNotBlank() && nikIstri.length == 16 &&
                    alamatKtp.isNotBlank() &&
                    alamatRumah.isNotBlank() &&
                    detailRumah.isNotBlank() &&
                    wilayah.isNotBlank() &&
                    jenisUsaha.isNotBlank() &&
                    besarPinjaman.isNotBlank() &&
                    fotoKtpSuamiUri != null &&
                    fotoKtpIstriUri != null &&
                    fotoNasabahUri != null &&
                    isPinjamanValid &&
                    tenorValue > 0
        }

        else -> false
    }

    val datePickerDialog = remember {
        DatePickerDialog(
            context,
            null,  // listener akan di-set ulang di bawah
            selectedYear,
            selectedMonth,
            selectedDay
        )
    }

// Update listener setiap recomposition agar tidak stale
    datePickerDialog.setOnDateSetListener { _, year, month, dayOfMonth ->
        selectedYear = year
        selectedMonth = month
        selectedDay = dayOfMonth
        val cal = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
        tanggalPengajuan = dateFormat.format(cal.time)
    }

// Update tanggal awal dialog setiap kali state berubah
    LaunchedEffect(selectedYear, selectedMonth, selectedDay) {
        datePickerDialog.updateDate(selectedYear, selectedMonth, selectedDay)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = bgColor,
            topBar = {
                ModernTopBar(
                    title = "Tambah Nasabah",
                    isDark = isDark,
                    txtColor = txtColor
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Main Content Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Tipe Pinjaman Card
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(tween(400)) + slideInVertically(
                            initialOffsetY = { -30 },
                            animationSpec = tween(400)
                        )
                    ) {
                        ModernCardContainer(
                            isDark = isDark,
                            cardColor = cardColor
                        ) {
                            Column {
                                Text(
                                    text = "Tipe Pinjaman",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = txtColor,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Radio Button 3jt ke bawah
                                    ModernRadioOption(
                                        text = "3.000.000 kebawah",
                                        selected = tipePinjaman == "dibawah_3jt",
                                        onClick = {
                                            tipePinjaman = "dibawah_3jt"
                                            // Reset jika nilai saat ini >= 3jt
                                            val current =
                                                besarPinjaman.replace("[^\\d]".toRegex(), "")
                                                    .toLongOrNull() ?: 0L
                                            if (current >= 3000000) {
                                                besarPinjaman = ""
                                                showPinjamanValidationError = false
                                                pinjamanValidationMessage = ""
                                            }
                                        },
                                        txtColor = txtColor,
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Radio Button 3jt ke atas
                                    ModernRadioOption(
                                        text = "3.000.000 keatas",
                                        selected = tipePinjaman == "diatas_3jt",
                                        onClick = {
                                            tipePinjaman = "diatas_3jt"
                                            // Validasi ulang jika nilai < 3jt
                                            val current =
                                                besarPinjaman.replace("[^\\d]".toRegex(), "")
                                                    .toLongOrNull() ?: 0L
                                            if (current > 0 && current < 3000000) {
                                                showPinjamanValidationError = true
                                                pinjamanValidationMessage =
                                                    "Pinjaman 3jt keatas minimal Rp 3.000.000"
                                            }
                                        },
                                        txtColor = txtColor,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Nomor Anggota Card
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                            initialOffsetY = { 30 },
                            animationSpec = tween(400, delayMillis = 100)
                        )
                    ) {
                        ModernCardContainer(
                            isDark = isDark,
                            cardColor = cardColor
                        ) {
                            Column {
                                OutlinedTextField(
                                    value = if (isLoadingNomorAnggota) "Loading..." else nomorAnggota,
                                    onValueChange = { newValue ->
                                        // Hanya terima angka, maksimal 6 digit
                                        val filtered = newValue.filter { it.isDigit() }.take(6)
                                        nomorAnggota = filtered

                                        // Simpan ke ViewModel untuk auto-increment berikutnya
                                        if (filtered.isNotEmpty()) {
                                            viewModel.saveManualNomorAnggota(filtered)
                                        }
                                    },
                                    label = {
                                        Text(
                                            "Nomor Anggota",
                                            color = subtitleColor
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    readOnly = false,  // ✅ UBAH dari true ke false
                                    enabled = !isLoadingNomorAnggota,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Next
                                    ),
                                    supportingText = {
                                        if (isLoadingNomorAnggota) {
                                            Text("Sedang generate nomor anggota...")
                                        } else {
                                            Text(
                                                "Isi manual atau gunakan otomatis. Nasabah berikutnya akan +1",
                                                color = subtitleColor,
                                                fontSize = 11.sp
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = cardColor,
                                        unfocusedContainerColor = cardColor,
                                        focusedBorderColor = TambahPelangganColors.primary,
                                        unfocusedBorderColor = borderColor,
                                        focusedLabelColor = TambahPelangganColors.primary,
                                        unfocusedLabelColor = subtitleColor,
                                        cursorColor = TambahPelangganColors.primary,
                                        focusedTextColor = txtColor,
                                        unfocusedTextColor = txtColor
                                    ),
                                    trailingIcon = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isLoadingNomorAnggota) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    color = TambahPelangganColors.primary,
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                // Tombol refresh untuk generate ulang
                                                IconButton(
                                                    onClick = {
                                                        isLoadingNomorAnggota = true
                                                        viewModel.viewModelScope.launch {
                                                            nomorAnggota =
                                                                viewModel.getNextNomorAnggota()
                                                            isLoadingNomorAnggota = false
                                                        }
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Refresh,
                                                        "Refresh Nomor",
                                                        tint = TambahPelangganColors.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    Icons.Rounded.Badge,
                                                    "Nomor Anggota",
                                                    tint = TambahPelangganColors.primary
                                                )
                                            }
                                        }
                                    }
                                )

                                // Info tambahan
                                if (!isLoadingNomorAnggota && nomorAnggota.isNotEmpty()) {
                                    val formatted = viewModel.formatNomorAnggota(nomorAnggota)
                                    if (formatted != nomorAnggota) {
                                        Text(
                                            "Format tersimpan: $formatted",
                                            color = TambahPelangganColors.info,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Form Data Berdasarkan Tipe Pinjaman
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                            initialOffsetY = { 30 },
                            animationSpec = tween(400, delayMillis = 200)
                        )
                    ) {
                        if (tipePinjaman == "dibawah_3jt") {
                            FormDibawah3JT(
                                namaKtp = namaKtp,
                                onNamaKtpChange = { namaKtp = it.capitalizeWords() },
                                nik = nik,
                                onNikChange = { nik = it.take(16).filter { c -> c.isDigit() } },
                                namaPanggilan = namaPanggilan,
                                onNamaPanggilanChange = { namaPanggilan = it.capitalizeWords() },
                                onScanKTP = {
                                    scanTarget = "single"
                                    showScanner = true
                                },
                                onPickFromGallery = {
                                    ktpGalleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                fotoKtpUri = fotoKtpUri,
                                onFotoKtpChange = { fotoKtpUri = it },
                                // ✅ BARU: Foto Nasabah untuk dibawah_3jt
                                fotoNasabahUri = fotoNasabahUri,
                                onFotoNasabahChange = { fotoNasabahUri = it },
                                onTakeFotoNasabah = { openNasabahCamera() },
                                isDark = isDark,
                                txtColor = txtColor,
                                subtitleColor = subtitleColor,
                                cardColor = cardColor,
                                borderColor = borderColor
                            )
                        } else {
                            FormDiatas3JT(
                                namaKtpSuami = namaKtpSuami,
                                onNamaKtpSuamiChange = { namaKtpSuami = it.capitalizeWords() },
                                nikSuami = nikSuami,
                                onNikSuamiChange = {
                                    nikSuami = it.take(16).filter { c -> c.isDigit() }
                                },
                                namaPanggilanSuami = namaPanggilanSuami,
                                onNamaPanggilanSuamiChange = {
                                    namaPanggilanSuami = it.capitalizeWords()
                                },
                                namaKtpIstri = namaKtpIstri,
                                onNamaKtpIstriChange = { namaKtpIstri = it.capitalizeWords() },
                                nikIstri = nikIstri,
                                onNikIstriChange = {
                                    nikIstri = it.take(16).filter { c -> c.isDigit() }
                                },
                                namaPanggilanIstri = namaPanggilanIstri,
                                onNamaPanggilanIstriChange = {
                                    namaPanggilanIstri = it.capitalizeWords()
                                },
                                onScanKTPSuami = {
                                    scanTarget = "suami"
                                    showScanner = true
                                },
                                onScanKTPIstri = {
                                    scanTarget = "istri"
                                    showScanner = true
                                },
                                onPickFromGallerySuami = {
                                    ktpSuamiGalleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onPickFromGalleryIstri = {
                                    ktpIstriGalleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                fotoKtpSuamiUri = fotoKtpSuamiUri,
                                onFotoKtpSuamiChange = { fotoKtpSuamiUri = it },
                                fotoKtpIstriUri = fotoKtpIstriUri,
                                onFotoKtpIstriChange = { fotoKtpIstriUri = it },
                                // ✅ BARU: Foto Nasabah
                                fotoNasabahUri = fotoNasabahUri,
                                onFotoNasabahChange = { fotoNasabahUri = it },
                                onTakeFotoNasabah = { openNasabahCamera() },
                                isDark = isDark,
                                txtColor = txtColor,
                                subtitleColor = subtitleColor,
                                cardColor = cardColor,
                                borderColor = borderColor
                            )
                        }
                    }

                    // Field Umum Card
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(tween(400, delayMillis = 300)) + slideInVertically(
                            initialOffsetY = { 30 },
                            animationSpec = tween(400, delayMillis = 300)
                        )
                    ) {
                        ModernCardContainer(
                            isDark = isDark,
                            cardColor = cardColor
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ModernTextField(
                                    value = alamatKtp,
                                    onValueChange = { alamatKtp = it.capitalizeWords() },
                                    label = "Alamat KTP",
                                    isError = alamatKtp.isBlank(),
                                    supportingText = {
                                        if (alamatKtp.isBlank()) {
                                            Text("Alamat KTP wajib diisi")
                                        }
                                    },
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                ModernTextField(
                                    value = alamatRumah,
                                    onValueChange = { alamatRumah = it.capitalizeWords() },
                                    label = "Alamat Rumah",
                                    isError = alamatRumah.isBlank(),
                                    supportingText = {
                                        if (alamatRumah.isBlank()) {
                                            Text("Alamat rumah wajib diisi")
                                        }
                                    },
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                ModernTextField(
                                    value = detailRumah,
                                    onValueChange = { detailRumah = it.capitalizeWords() },
                                    label = "Detail Rumah",
                                    isError = detailRumah.isBlank(),
                                    supportingText = {
                                        if (detailRumah.isBlank()) {
                                            Text("Detail rumah wajib diisi")
                                        } else {
                                            Text("Contoh: Disamping SD 01, cat rumah warna putih")
                                        }
                                    },
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                ModernTextField(
                                    value = wilayah,
                                    onValueChange = { wilayah = it.capitalizeWords() },
                                    label = "Wilayah",
                                    isError = wilayah.isBlank(),
                                    supportingText = {
                                        if (wilayah.isBlank()) {
                                            Text("Wilayah wajib diisi")
                                        }
                                    },
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                ModernTextField(
                                    value = noHp,
                                    onValueChange = { noHp = it.filter { ch -> ch.isDigit() } },
                                    label = "No HP",
                                    keyboardType = KeyboardType.Phone,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                ModernTextField(
                                    value = jenisUsaha,
                                    onValueChange = { jenisUsaha = it.capitalizeWords() },
                                    label = "Jenis Usaha",
                                    isError = jenisUsaha.isBlank(),
                                    supportingText = {
                                        if (jenisUsaha.isBlank()) {
                                            Text("Jenis Usaha wajib diisi")
                                        }
                                    },
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                ModernTextField(
                                    value = pinjamanKe,
                                    onValueChange = {
                                        pinjamanKe = it.filter { ch -> ch.isDigit() }
                                    },
                                    label = "Pinjaman Ke",
                                    keyboardType = KeyboardType.Number,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                // Tanggal Pengajuan
                                ModernDateField(
                                    tanggal = tanggalPengajuan,
                                    onDateClick = { datePickerDialog.show() },
                                    label = "Tanggal Pengajuan",
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )
                            }
                        }
                    }

                    // Field Pinjaman Card
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(tween(400, delayMillis = 400)) + slideInVertically(
                            initialOffsetY = { 30 },
                            animationSpec = tween(400, delayMillis = 400)
                        )
                    ) {
                        ModernCardContainer(
                            isDark = isDark,
                            cardColor = cardColor
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ModernTextField(
                                    value = formatRupiahInput(besarPinjaman),
                                    onValueChange = { input ->
                                        val newValue = formatRupiahInput(input)
                                        val pinjamanNumerik =
                                            newValue.replace("[^\\d]".toRegex(), "").toLongOrNull()
                                                ?: 0L

                                        when (tipePinjaman) {
                                            "dibawah_3jt" -> {
                                                // ✅ BATASI: Tidak bisa input >= 3.000.000
                                                if (pinjamanNumerik < 3000000) {
                                                    besarPinjaman = newValue
                                                    // Validasi minimal 50.000
                                                    if (pinjamanNumerik > 0 && pinjamanNumerik < 50000) {
                                                        showPinjamanValidationError = true
                                                        pinjamanValidationMessage =
                                                            "Besar pinjaman minimal Rp 50.000"
                                                    } else {
                                                        showPinjamanValidationError = false
                                                        pinjamanValidationMessage = ""
                                                    }
                                                }
                                                // Jika >= 3jt, TIDAK update besarPinjaman (input ditolak)
                                            }

                                            "diatas_3jt" -> {
                                                // ✅ IZINKAN input apapun, tapi validasi minimum 3jt
                                                besarPinjaman = newValue
                                                if (pinjamanNumerik > 0 && pinjamanNumerik < 3000000) {
                                                    showPinjamanValidationError = true
                                                    pinjamanValidationMessage =
                                                        "Pinjaman 3jt keatas minimal Rp 3.000.000"
                                                } else if (pinjamanNumerik > 0 && pinjamanNumerik < 50000) {
                                                    showPinjamanValidationError = true
                                                    pinjamanValidationMessage =
                                                        "Besar pinjaman minimal Rp 50.000"
                                                } else {
                                                    showPinjamanValidationError = false
                                                    pinjamanValidationMessage = ""
                                                }
                                            }

                                            else -> {
                                                besarPinjaman = newValue
                                                showPinjamanValidationError = false
                                                pinjamanValidationMessage = ""
                                            }
                                        }
                                    },
                                    label = "Besar Pinjaman",
                                    isError = showPinjamanValidationError,
                                    supportingText = {
                                        val pinjamanNumerik =
                                            besarPinjaman.replace("[^\\d]".toRegex(), "")
                                                .toLongOrNull() ?: 0L
                                        when {
                                            showPinjamanValidationError -> {
                                                Text(
                                                    text = pinjamanValidationMessage.ifBlank {
                                                        if (tipePinjaman == "diatas_3jt") "Minimal Rp 3.000.000" else "Minimal Rp 50.000"
                                                    },
                                                    color = TambahPelangganColors.danger
                                                )
                                            }

                                            pinjamanNumerik > 0 -> {
                                                val nominalTerformat = formatRupiah(pinjamanNumerik)
                                                val maxInfo =
                                                    if (tipePinjaman == "dibawah_3jt") " (Maks: Rp 2.999.999)" else ""
                                                Text("Nominal: $nominalTerformat$maxInfo")
                                            }

                                            else -> {
                                                val hint = if (tipePinjaman == "dibawah_3jt")
                                                    "Masukkan nominal (Rp 50.000 - Rp 2.999.999)"
                                                else
                                                    "Masukkan nominal (Minimal Rp 3.000.000)"
                                                Text(hint)
                                            }
                                        }
                                    },
                                    keyboardType = KeyboardType.Number,
                                    visualTransformation = ThousandSeparatorTransformation,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )
                                ModernTextField(
                                    value = formatRupiahInput(admin),
                                    onValueChange = {},
                                    label = "Admin 5%",
                                    readOnly = true,
                                    visualTransformation = ThousandSeparatorTransformation,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                // Simpanan Field
                                OutlinedTextField(
                                    value = formatRupiahInput(simpanan),
                                    onValueChange = {
                                        isSimpananManual = true
                                        simpanan = formatRupiahInput(it)
                                    },
                                    label = {
                                        Text(
                                            "Simpanan ${if (isSimpananManual) "" else "(5%)"}",
                                            color = subtitleColor
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = cardColor,
                                        unfocusedContainerColor = cardColor,
                                        focusedBorderColor = TambahPelangganColors.primary,
                                        unfocusedBorderColor = borderColor,
                                        focusedLabelColor = TambahPelangganColors.primary,
                                        unfocusedLabelColor = subtitleColor,
                                        cursorColor = TambahPelangganColors.primary,
                                        focusedTextColor = txtColor,
                                        unfocusedTextColor = txtColor
                                    ),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Next
                                    ),
                                    trailingIcon = {
                                        if (isSimpananManual) {
                                            IconButton(
                                                onClick = {
                                                    isSimpananManual = false
                                                    val pinjaman = besarPinjaman.replace(
                                                        "[^\\d]".toRegex(),
                                                        ""
                                                    ).toLongOrNull() ?: 0L
                                                    val simpananVal = (pinjaman * 5) / 100
                                                    simpanan = simpananVal.toString()
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Refresh,
                                                    "Reset ke 5%",
                                                    tint = TambahPelangganColors.primary
                                                )
                                            }
                                        }
                                    },
                                    supportingText = {
                                        if (isSimpananManual) {
                                            val pinjaman =
                                                besarPinjaman.replace("[^\\d]".toRegex(), "")
                                                    .toLongOrNull() ?: 0L
                                            val nilaiLimaPersen = (pinjaman * 5) / 100
                                            Text(
                                                "Manual input - nilai 5%: ${
                                                    formatRupiahInput(
                                                        nilaiLimaPersen.toString()
                                                    )
                                                }"
                                            )
                                        } else {
                                            Text("5% dari besar pinjaman")
                                        }
                                    },
                                    visualTransformation = ThousandSeparatorTransformation
                                )

                                ModernTextField(
                                    value = formatRupiahInput(simpananTambahan),
                                    onValueChange = { simpananTambahan = formatRupiahInput(it) },
                                    label = "Simpanan Tambahan (Lama)",
                                    supportingText = {
                                        Text("Simpanan dari pinjaman sebelumnya - tidak mengurangi pencairan")
                                    },
                                    keyboardType = KeyboardType.Number,
                                    visualTransformation = ThousandSeparatorTransformation,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                ModernTextField(
                                    value = formatRupiahInput(jasa),
                                    onValueChange = {},
                                    label = "Total Potongan 10%",
                                    readOnly = true,
                                    visualTransformation = ThousandSeparatorTransformation,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                ModernTextField(
                                    value = formatRupiahInput(totalDiterima),
                                    onValueChange = {},
                                    label = "Total yang Diterima",
                                    readOnly = true,
                                    visualTransformation = ThousandSeparatorTransformation,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                // Tenor Dropdown
                                ModernDropdownField(
                                    value = if (tenorValue > 0) "$tenorValue Hari" else "",
                                    onExpandedChange = { expandedTenor = !expandedTenor },
                                    expanded = expandedTenor,
                                    label = "Tenor (Hari)",
                                    items = pilihanTenor,
                                    onItemSelected = { tenorValue = it; expandedTenor = false },
                                    itemToString = { "$it Hari" },
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )

                                ModernTextField(
                                    value = formatRupiahInput(totalPelunasan),
                                    onValueChange = {},
                                    label = "Total Pelunasan",
                                    readOnly = true,
                                    visualTransformation = ThousandSeparatorTransformation,
                                    isDark = isDark,
                                    cardColor = cardColor,
                                    borderColor = borderColor,
                                    txtColor = txtColor,
                                    subtitleColor = subtitleColor
                                )
                            }
                        }
                    }

                    // Tombol Simpan
                    AnimatedVisibility(
                        visible = isVisible,
                        enter = fadeIn(tween(400, delayMillis = 500)) + slideInVertically(
                            initialOffsetY = { 50 },
                            animationSpec = tween(400, delayMillis = 500)
                        )
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ModernGradientButton(
                                text = if (isValidatingNik || isUploading) "Memproses..." else "Simpan Data",
                                onClick = {
                                    // [Logika validasi dan simpan tetap sama...]
                                    if (alamatKtp.isBlank()) {
                                        showValidationError = true
                                        validationMessage = "Alamat KTP tidak boleh kosong"
                                        return@ModernGradientButton
                                    }

                                    if (alamatRumah.isBlank()) {
                                        showValidationError = true
                                        validationMessage = "Alamat rumah tidak boleh kosong"
                                        return@ModernGradientButton
                                    }

                                    if (detailRumah.isBlank()) {
                                        showValidationError = true
                                        validationMessage = "Detail rumah tidak boleh kosong"
                                        return@ModernGradientButton
                                    }
                                    if (wilayah.isBlank()) {
                                        showValidationError = true
                                        validationMessage = "Wilayah tidak boleh kosong"
                                        return@ModernGradientButton
                                    }
                                    if (jenisUsaha.isBlank()) {
                                        showValidationError = true
                                        validationMessage = "Jenis Usaha tidak boleh kosong"
                                        return@ModernGradientButton
                                    }
//                                val pinjamanNumerik = besarPinjaman.replace("[^\\d]".toRegex(), "").toLongOrNull() ?: 0L
//                                if (pinjamanNumerik < 50000) {
//                                    showPinjamanValidationError = true
//                                    pinjamanValidationMessage = "Besar pinjaman minimal Rp 50.000"
//                                    return@ModernGradientButton
//                                }
                                    when (tipePinjaman) {
                                        "dibawah_3jt" -> {
                                            if (namaKtp.isBlank() || nik.length != 16 || namaPanggilan.isBlank() ||
                                                alamatKtp.isBlank() || alamatRumah.isBlank() || detailRumah.isBlank() ||
                                                wilayah.isBlank() || jenisUsaha.isBlank() || besarPinjaman.isBlank()
                                            ) {
                                                Toast.makeText(
                                                    context,
                                                    "Harap lengkapi semua data",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@ModernGradientButton
                                            }
                                        }

                                        "diatas_3jt" -> {
                                            if (namaKtpSuami.isBlank() || nikSuami.length != 16 ||
                                                namaKtpIstri.isBlank() || nikIstri.length != 16 ||
                                                namaPanggilanSuami.isBlank() || namaPanggilanIstri.isBlank() ||
                                                alamatKtp.isBlank() || alamatRumah.isBlank() || detailRumah.isBlank() ||
                                                wilayah.isBlank() || jenisUsaha.isBlank() || besarPinjaman.isBlank()
                                            ) {
                                                Toast.makeText(
                                                    context,
                                                    "Harap lengkapi semua data Suami & Istri",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@ModernGradientButton
                                            }
                                        }
                                    }

                                    val pinjamanNumerik =
                                        besarPinjaman.replace("[^\\d]".toRegex(), "").toLongOrNull()
                                            ?: 0L
                                    when (tipePinjaman) {
                                        "dibawah_3jt" -> {
                                            if (pinjamanNumerik < 50000 || pinjamanNumerik >= 3000000) {
                                                showPinjamanValidationError = true
                                                pinjamanValidationMessage =
                                                    "Besar pinjaman harus antara Rp 50.000 - Rp 2.999.999"
                                                return@ModernGradientButton
                                            }
                                        }

                                        "diatas_3jt" -> {
                                            if (pinjamanNumerik < 3000000) {
                                                showPinjamanValidationError = true
                                                pinjamanValidationMessage = "Minimal Rp 3.000.000"
                                                return@ModernGradientButton
                                            }
                                        }
                                    }

                                    isValidatingNik = true

                                    viewModel.validateNik(
                                        nik = if (tipePinjaman == "dibawah_3jt") nik else "",
                                        nikSuami = if (tipePinjaman == "diatas_3jt") nikSuami else "",
                                        nikIstri = if (tipePinjaman == "diatas_3jt") nikIstri else "",
                                        onResult = { result ->
                                            isValidatingNik = false

                                            if (!result.isValid && result.isDuplicate) {
                                                // NIK duplikat ditemukan - tampilkan dialog
                                                nikDuplicateMessage = result.message
                                                nikDuplicateAdminName = result.existingAdminName
                                                nikDuplicatePelangganNama =
                                                    result.existingPelangganNama
                                                showNikDuplicateDialog = true
                                                return@validateNik
                                            }

                                            // ========== NIK VALID - LANJUTKAN PROSES SIMPAN ==========
                                            isUploading = true

                                            val currentUid = Firebase.auth.currentUser?.uid
                                            if (currentUid.isNullOrBlank()) {
                                                Toast.makeText(
                                                    context,
                                                    "Harap login dahulu",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                isUploading = false
                                                return@validateNik
                                            }

                                            val besarPin =
                                                besarPinjaman.replace("[^\\d]".toRegex(), "")
                                                    .toLongOrNull() ?: 0L
                                            val adminLong =
                                                admin.replace("[^\\d]".toRegex(), "").toLongOrNull()
                                                    ?: 0L
                                            val simpananPinjamanIni = if (isSimpananManual) {
                                                simpanan.replace("[^\\d]".toRegex(), "")
                                                    .toLongOrNull() ?: 0L
                                            } else {
                                                (besarPin * 5) / 100
                                            }
                                            val simpananTambahanLong =
                                                simpananTambahan.replace("[^\\d]".toRegex(), "")
                                                    .toLongOrNull() ?: 0L
                                            val totalSimpanan =
                                                simpananPinjamanIni + simpananTambahanLong
                                            val jasaLong =
                                                jasa.replace("[^\\d]".toRegex(), "").toLongOrNull()
                                                    ?: 0L
                                            val totalDiterimaLong =
                                                besarPin - (adminLong + simpananPinjamanIni)
                                            val tenorInt = tenorValue
                                            val totalPelunasanLong = (besarPin * 120) / 100

                                            val simulasiCicilan = hitungSimulasiCicilanHarian(
                                                besarPinjaman = besarPin,
                                                tenorHari = tenorInt,
                                                totalPelunasan = totalPelunasanLong
                                            )

                                            val pelanggan = when (tipePinjaman) {
                                                "dibawah_3jt" -> Pelanggan(
                                                    id = "",
                                                    nomorAnggota = viewModel.formatNomorAnggota(
                                                        nomorAnggota
                                                    ),
                                                    namaKtp = namaKtp,
                                                    nik = nik,
                                                    namaPanggilan = namaPanggilan,
                                                    alamatKtp = alamatKtp,
                                                    alamatRumah = alamatRumah,
                                                    detailRumah = detailRumah,
                                                    wilayah = wilayah,
                                                    noHp = noHp,
                                                    jenisUsaha = jenisUsaha,
                                                    pinjamanKe = pinjamanKe.toIntOrNull() ?: 1,
                                                    besarPinjaman = besarPin.toInt(),
                                                    jasaPinjaman = jasaLong.toInt(),
                                                    admin = adminLong.toInt(),
                                                    simpanan = totalSimpanan.toInt(),
                                                    totalDiterima = totalDiterimaLong.toInt(),
                                                    totalPelunasan = totalPelunasanLong.toInt(),
                                                    status = "Menunggu Approval",
                                                    tenor = tenorInt,
                                                    besarPinjamanDiajukan = besarPin.toInt(),
                                                    besarPinjamanDisetujui = 0,
                                                    isPinjamanDiubah = false,
                                                    tanggalPengajuan = tanggalPengajuan,
                                                    hasilSimulasiCicilan = simulasiCicilan,
                                                    adminEmail = auth.currentUser?.email ?: "",
                                                    adminUid = auth.currentUser?.uid ?: "",
                                                    adminName = auth.currentUser?.displayName ?: "",
                                                    isSynced = false
                                                )

                                                "diatas_3jt" -> Pelanggan(
                                                    id = "",
                                                    nomorAnggota = nomorAnggota,
                                                    namaKtp = "$namaKtpSuami & $namaKtpIstri",
                                                    nik = "$nikSuami & $nikIstri",
                                                    namaPanggilan = "$namaPanggilanSuami & $namaPanggilanIstri",
                                                    namaKtpSuami = namaKtpSuami,
                                                    namaKtpIstri = namaKtpIstri,
                                                    nikSuami = nikSuami,
                                                    nikIstri = nikIstri,
                                                    namaPanggilanSuami = namaPanggilanSuami,
                                                    namaPanggilanIstri = namaPanggilanIstri,
                                                    tipePinjaman = "diatas_3jt",
                                                    alamatKtp = alamatKtp,
                                                    alamatRumah = alamatRumah,
                                                    detailRumah = detailRumah,
                                                    wilayah = wilayah,
                                                    noHp = noHp,
                                                    jenisUsaha = jenisUsaha,
                                                    pinjamanKe = pinjamanKe.toIntOrNull() ?: 1,
                                                    besarPinjaman = besarPin.toInt(),
                                                    besarPinjamanDiajukan = besarPin.toInt(),
                                                    jasaPinjaman = jasaLong.toInt(),
                                                    admin = adminLong.toInt(),
                                                    simpanan = totalSimpanan.toInt(),
                                                    totalDiterima = totalDiterimaLong.toInt(),
                                                    totalPelunasan = totalPelunasanLong.toInt(),
                                                    status = "Menunggu Approval",
                                                    tenor = tenorInt,
                                                    besarPinjamanDisetujui = 0,
                                                    isPinjamanDiubah = false,
                                                    tanggalPengajuan = tanggalPengajuan,
                                                    hasilSimulasiCicilan = simulasiCicilan,
                                                    adminEmail = auth.currentUser?.email ?: "",
                                                    adminUid = auth.currentUser?.uid ?: "",
                                                    adminName = auth.currentUser?.displayName ?: "",
                                                    isSynced = false
                                                )

                                                else -> return@validateNik
                                            }

                                            viewModel.simpanPelangganLengkap(
                                                pelangganInput = pelanggan,
                                                fotoKtpUri = if (tipePinjaman == "dibawah_3jt") fotoKtpUri else null,
                                                fotoKtpSuamiUri = if (tipePinjaman == "diatas_3jt") fotoKtpSuamiUri else null,
                                                fotoKtpIstriUri = if (tipePinjaman == "diatas_3jt") fotoKtpIstriUri else null,
                                                fotoNasabahUri = fotoNasabahUri, // ✅ DIUBAH: Foto nasabah wajib untuk semua tipe pinjaman
                                                onSuccess = {
                                                    isUploading = false
                                                    Toast.makeText(
                                                        context,
                                                        "Nasabah berhasil ditambahkan",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    viewModel.syncOfflineData()
                                                    navController.popBackStack()
                                                },
                                                onFailure = { e ->
                                                    isUploading = false
                                                    Toast.makeText(
                                                        context,
                                                        "Gagal simpan: ${e.message}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            )
                                        }
                                    )
                                },
                                enabled = isFormValid && !isUploading && !isValidatingNik,
                                gradient = TambahPelangganColors.successGradient,
                                icon = if (isValidatingNik) Icons.Rounded.Refresh else Icons.Rounded.Save,
                                modifier = Modifier.fillMaxWidth()
                            )

                            ModernOutlinedButton(
                                text = "Batal",
                                onClick = { navController.popBackStack() },
                                isDark = isDark,
                                borderColor = borderColor,
                                txtColor = subtitleColor,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
        // Fullscreen Scanner KTP
        if (showScanner) {
            Dialog(
                onDismissRequest = {
                    if (!isProcessingScan) {
                        showScanner = false
                    }
                },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    KtpScanner(
                        modifier = Modifier.fillMaxSize(),
                        onScanResult = { completeResult ->
                            pendingKtpResult = completeResult
                            confirmNama = completeResult.ktpData.nama ?: ""
                            confirmNik = completeResult.ktpData.nik ?: ""
                            confirmAlamat = completeResult.ktpData.alamat ?: ""
                            showScanner = false
                            showKtpConfirmDialog = true
                        },
                        onDismiss = {
                            if (!isProcessingScan) {
                                showScanner = false
                            }
                        }
                    )
                }
            }
        }
    }

    // Dialog NIK Duplikat
    if (showNikDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { showNikDuplicateDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = "Warning",
                    tint = TambahPelangganColors.warning,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    text = "NIK Sudah Terdaftar!",
                    fontWeight = FontWeight.Bold,
                    color = TambahPelangganColors.danger
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = nikDuplicateMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (nikDuplicateAdminName.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = TambahPelangganColors.warning.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = TambahPelangganColors.warning
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Admin: $nikDuplicateAdminName",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                }
                                if (nikDuplicatePelangganNama.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.AccountCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = TambahPelangganColors.warning
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Nasabah: $nikDuplicatePelangganNama",
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "Nasabah harus melunasi pinjaman terlebih dahulu sebelum dapat mendaftar kembali.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showNikDuplicateDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TambahPelangganColors.primary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Mengerti")
                }
            },
            containerColor = if (isDark) TambahPelangganColors.darkCard else Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Dialog Scanner KTP (tetap sama)
    // Fullscreen Scanner KTP (seperti gambar referensi)
    if (showScanner) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            KtpScanner(
                modifier = Modifier.fillMaxSize(),
                onScanResult = { completeResult ->
                    pendingKtpResult = completeResult
                    confirmNama = completeResult.ktpData.nama ?: ""
                    confirmNik = completeResult.ktpData.nik ?: ""
                    confirmAlamat = completeResult.ktpData.alamat ?: ""
                    showScanner = false
                    showKtpConfirmDialog = true
                },
                onDismiss = {
                    if (!isProcessingScan) {
                        showScanner = false
                    }
                }
            )
        }
    }
    // === DIALOG KONFIRMASI HASIL SCAN KTP ===
    if (showKtpConfirmDialog) {
        Dialog(
            onDismissRequest = { showKtpConfirmDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(24.dp),
                color = if (isDark) TambahPelangganColors.darkSurface else Color.White,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    // ── Header ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(TambahPelangganColors.successGradient)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.CreditCard,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Hasil Scan KTP",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = txtColor
                            )
                            Text(
                                text = "Periksa dan koreksi jika ada yang salah",
                                fontSize = 12.sp,
                                color = subtitleColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Error indicator
                    if (pendingKtpResult?.ktpData?.error != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFFF8E1)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = Color(0xFFF57F17),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Beberapa data mungkin kurang akurat. Silakan periksa dan koreksi.",
                                    fontSize = 12.sp,
                                    color = Color(0xFF856404)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // ── Field Nama ──
                    Text(
                        text = "Nama Sesuai KTP",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = confirmNama,
                        onValueChange = { confirmNama = it.uppercase() },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Masukkan nama sesuai KTP", fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TambahPelangganColors.primary,
                            unfocusedBorderColor = borderColor
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // ── Field NIK ──
                    Text(
                        text = "NIK",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = confirmNik,
                        onValueChange = { confirmNik = it.take(16).filter { c -> c.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("16 digit NIK", fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TambahPelangganColors.primary,
                            unfocusedBorderColor = borderColor
                        ),
                        supportingText = {
                            Text(
                                text = "${confirmNik.length}/16",
                                fontSize = 11.sp,
                                color = if (confirmNik.length == 16) TambahPelangganColors.success else subtitleColor
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // ── Field Alamat ──
                    Text(
                        text = "Alamat Sesuai KTP",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = confirmAlamat,
                        onValueChange = { confirmAlamat = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        placeholder = { Text("Masukkan alamat sesuai KTP", fontSize = 13.sp) },
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TambahPelangganColors.primary,
                            unfocusedBorderColor = borderColor
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // ── Tombol Aksi ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Tombol Batal
                        OutlinedButton(
                            onClick = {
                                showKtpConfirmDialog = false
                                pendingKtpResult = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, borderColor
                            )
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Batal", fontSize = 13.sp)
                        }

                        // Tombol Scan Ulang
                        OutlinedButton(
                            onClick = {
                                showKtpConfirmDialog = false
                                pendingKtpResult = null
                                showScanner = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TambahPelangganColors.warning
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, TambahPelangganColors.warning
                            )
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ulangi", fontSize = 13.sp)
                        }

                        // Tombol Konfirmasi
                        Button(
                            onClick = {
                                if (confirmNama.isBlank() || confirmNik.length != 16 || confirmAlamat.isBlank()) {
                                    Toast.makeText(
                                        context,
                                        "Pastikan Nama, NIK (16 digit), dan Alamat sudah terisi",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@Button
                                }

                                val correctedKtpData = KtpData(
                                    nik = confirmNik,
                                    nama = confirmNama,
                                    alamat = confirmAlamat,
                                    error = null
                                )
                                val correctedResult = CompleteScanResult(
                                    ktpData = correctedKtpData,
                                    imageUri = pendingKtpResult?.imageUri
                                )

                                completeScanResult = correctedResult
                                isProcessingScan = true
                                scanResultTrigger++
                                showKtpConfirmDialog = false
                                pendingKtpResult = null

                                Log.d("KtpConfirm", "✅ Data KTP dikonfirmasi:")
                                Log.d("KtpConfirm", "  Nama: $confirmNama")
                                Log.d("KtpConfirm", "  NIK: $confirmNik")
                                Log.d("KtpConfirm", "  Alamat: $confirmAlamat")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TambahPelangganColors.success
                            )
                        ) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Simpan",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
    if (showNasabahCamera) {
        InAppCameraCapture(
            onPhotoCaptured = { uri ->
                fotoNasabahUri = uri
                showNasabahCamera = false
                Log.d("TambahPelanggan", "✅ Foto nasabah diambil: $uri")
            },
            onDismiss = {
                showNasabahCamera = false
            }
        )
    }
}

// ==================== COMPONENTS ====================

@Composable
private fun ModernTopBar(
    title: String,
    isDark: Boolean,
    txtColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) TambahPelangganColors.darkSurface else Color.White,
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor
            )
        }
    }
}

@Composable
private fun ModernCardContainer(
    isDark: Boolean,
    cardColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = TambahPelangganColors.primary.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}

@Composable
private fun ModernRadioOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    txtColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = TambahPelangganColors.primary,
                unselectedColor = txtColor.copy(alpha = 0.6f)
            )
        )
        Text(
            text = text,
            color = txtColor,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    readOnly: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                label,
                color = subtitleColor
            )
        },
        modifier = Modifier.fillMaxWidth(),
        isError = isError,
        supportingText = supportingText,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = cardColor,
            unfocusedContainerColor = cardColor,
            focusedBorderColor = if (isError) TambahPelangganColors.danger else TambahPelangganColors.primary,
            unfocusedBorderColor = if (isError) TambahPelangganColors.danger else borderColor,
            focusedLabelColor = if (isError) TambahPelangganColors.danger else TambahPelangganColors.primary,
            unfocusedLabelColor = subtitleColor,
            cursorColor = TambahPelangganColors.primary,
            focusedTextColor = txtColor,
            unfocusedTextColor = txtColor,
            errorTextColor = TambahPelangganColors.danger,
            errorBorderColor = TambahPelangganColors.danger,
            errorLabelColor = TambahPelangganColors.danger
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next
        ),
        readOnly = readOnly,
        visualTransformation = visualTransformation
    )
}

@Composable
private fun ModernDateField(
    tanggal: String,
    onDateClick: () -> Unit,
    label: String,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDateClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(borderColor, borderColor))
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            Brush.linearGradient(TambahPelangganColors.infoGradient),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = subtitleColor
                    )
                    Text(
                        text = tanggal,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor
                    )
                }
            }

            Icon(
                Icons.Rounded.Edit,
                contentDescription = "Pilih Tanggal",
                tint = TambahPelangganColors.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernDropdownField(
    value: String,
    onExpandedChange: (Boolean) -> Unit,
    expanded: Boolean,
    label: String,
    items: List<Int>,
    onItemSelected: (Int) -> Unit,
    itemToString: (Int) -> String,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { },
            label = {
                Text(
                    label,
                    color = subtitleColor
                )
            },
            readOnly = true,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = cardColor,
                unfocusedContainerColor = cardColor,
                focusedBorderColor = TambahPelangganColors.primary,
                unfocusedBorderColor = borderColor,
                focusedLabelColor = TambahPelangganColors.primary,
                unfocusedLabelColor = subtitleColor,
                focusedTextColor = txtColor,
                unfocusedTextColor = txtColor
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            itemToString(item),
                            color = txtColor
                        )
                    },
                    onClick = { onItemSelected(item) }
                )
            }
        }
    }
}

@Composable
private fun ModernGradientButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    gradient: List<Color>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(56.dp)
            .shadow(
                elevation = if (enabled) 8.dp else 0.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = gradient.first().copy(alpha = 0.3f)
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
                    if (enabled) Brush.linearGradient(gradient)
                    else Brush.linearGradient(listOf(Color(0xFF94A3B8), Color(0xFFCBD5E1)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ModernOutlinedButton(
    text: String,
    onClick: () -> Unit,
    isDark: Boolean,
    borderColor: Color,
    txtColor: Color,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.linearGradient(listOf(borderColor, borderColor))
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isDark) TambahPelangganColors.darkSurface else Color.White
        )
    ) {
        Text(
            text = text,
            color = txtColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ==================== FORM COMPONENTS ====================

@Composable
fun FormDibawah3JT(
    namaKtp: String,
    onNamaKtpChange: (String) -> Unit,
    nik: String,
    onNikChange: (String) -> Unit,
    namaPanggilan: String,
    onNamaPanggilanChange: (String) -> Unit,
    onScanKTP: () -> Unit,
    onPickFromGallery: () -> Unit,
    fotoKtpUri: Uri?,
    onFotoKtpChange: (Uri?) -> Unit,
    // ✅ BARU: Foto Nasabah untuk dibawah_3jt
    fotoNasabahUri: Uri?,
    onFotoNasabahChange: (Uri?) -> Unit,
    onTakeFotoNasabah: () -> Unit,
    isDark: Boolean,
    txtColor: Color,
    subtitleColor: Color,
    cardColor: Color,
    borderColor: Color
) {
    ModernCardContainer(
        isDark = isDark,
        cardColor = cardColor
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Data Nasabah",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = txtColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            ModernTextField(
                value = namaKtp,
                onValueChange = onNamaKtpChange,
                label = "Nama Sesuai KTP",
                isError = namaKtp.length < 3,
                supportingText = {
                    if (namaKtp.length < 3) {
                        Text("Nama harus minimal 3 karakter")
                    }
                },
                isDark = isDark,
                cardColor = cardColor,
                borderColor = borderColor,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )

            ModernTextField(
                value = nik,
                onValueChange = onNikChange,
                label = "NIK",
                isError = nik.length != 16,
                supportingText = {
                    if (nik.length != 16) {
                        Text("NIK harus 16 digit angka")
                    }
                },
                keyboardType = KeyboardType.Number,
                isDark = isDark,
                cardColor = cardColor,
                borderColor = borderColor,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )

            ModernTextField(
                value = namaPanggilan,
                onValueChange = onNamaPanggilanChange,
                label = "Nama Panggilan",
                isDark = isDark,
                cardColor = cardColor,
                borderColor = borderColor,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )

            ModernKtpUploadSection(
                title = "Foto KTP",
                imageUri = fotoKtpUri,
                onImageChange = onFotoKtpChange,
                onScanKTP = onScanKTP,
                onPickFromGallery = onPickFromGallery,
                isDark = isDark,
                borderColor = borderColor
            )

            // ✅ BARU: Section Foto Nasabah untuk dibawah_3jt
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Foto Nasabah",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = txtColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Text(
                text = "Ambil foto nasabah menggunakan kamera",
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor
            )

            // Tombol Ambil Foto
            Button(
                onClick = onTakeFotoNasabah,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TambahPelangganColors.info
                )
            ) {
                Icon(
                    Icons.Rounded.CameraAlt,
                    contentDescription = "Ambil Foto",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ambil Foto Nasabah", fontSize = 14.sp)
            }

            // Preview gambar
            if (fotoNasabahUri != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                        .background(Color.LightGray)
                ) {
                    AsyncImage(
                        model = fotoNasabahUri,
                        contentDescription = "Preview Foto Nasabah",
                        modifier = Modifier.fillMaxSize()
                    )

                    // Tombol hapus
                    IconButton(
                        onClick = { onFotoNasabahChange(null) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(32.dp)
                            .background(Color.Red, CircleShape)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            "Hapus",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                        .background(Color.LightGray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = "No Image",
                            tint = Color.Gray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Belum ada foto nasabah",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Text(
                            "Klik tombol di atas untuk mengambil foto",
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Text(
                text = "Pastikan wajah nasabah terlihat jelas",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun FormDiatas3JT(
    namaKtpSuami: String,
    onNamaKtpSuamiChange: (String) -> Unit,
    nikSuami: String,
    onNikSuamiChange: (String) -> Unit,
    namaPanggilanSuami: String,
    onNamaPanggilanSuamiChange: (String) -> Unit,
    namaKtpIstri: String,
    onNamaKtpIstriChange: (String) -> Unit,
    nikIstri: String,
    onNikIstriChange: (String) -> Unit,
    namaPanggilanIstri: String,
    onNamaPanggilanIstriChange: (String) -> Unit,
    onScanKTPSuami: () -> Unit,
    onScanKTPIstri: () -> Unit,
    onPickFromGallerySuami: () -> Unit,
    onPickFromGalleryIstri: () -> Unit,
    fotoKtpSuamiUri: Uri?,
    onFotoKtpSuamiChange: (Uri?) -> Unit,
    fotoKtpIstriUri: Uri?,
    onFotoKtpIstriChange: (Uri?) -> Unit,
    // ✅ BARU: Foto Nasabah
    fotoNasabahUri: Uri?,
    onFotoNasabahChange: (Uri?) -> Unit,
    onTakeFotoNasabah: () -> Unit,
    isDark: Boolean,
    txtColor: Color,
    subtitleColor: Color,
    cardColor: Color,
    borderColor: Color
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Data Suami
        ModernCardContainer(
            isDark = isDark,
            cardColor = cardColor
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Data Suami",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = txtColor,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                ModernTextField(
                    value = namaKtpSuami,
                    onValueChange = onNamaKtpSuamiChange,
                    label = "Nama KTP Suami",
                    isError = namaKtpSuami.length < 3,
                    supportingText = {
                        if (namaKtpSuami.length < 3) {
                            Text("Nama harus minimal 3 karakter")
                        }
                    },
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )

                ModernTextField(
                    value = nikSuami,
                    onValueChange = onNikSuamiChange,
                    label = "NIK Suami",
                    isError = nikSuami.length != 16,
                    supportingText = {
                        if (nikSuami.length != 16) {
                            Text("NIK harus 16 digit angka")
                        }
                    },
                    keyboardType = KeyboardType.Number,
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )

                ModernTextField(
                    value = namaPanggilanSuami,
                    onValueChange = onNamaPanggilanSuamiChange,
                    label = "Nama Panggilan Suami",
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )

                ModernKtpUploadSection(
                    title = "Foto KTP Suami",
                    imageUri = fotoKtpSuamiUri,
                    onImageChange = onFotoKtpSuamiChange,
                    onScanKTP = onScanKTPSuami,
                    onPickFromGallery = onPickFromGallerySuami,
                    isDark = isDark,
                    borderColor = borderColor
                )
            }
        }

        // Data Istri
        ModernCardContainer(
            isDark = isDark,
            cardColor = cardColor
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Data Istri",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = txtColor,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                ModernTextField(
                    value = namaKtpIstri,
                    onValueChange = onNamaKtpIstriChange,
                    label = "Nama KTP Istri",
                    isError = namaKtpIstri.length < 3,
                    supportingText = {
                        if (namaKtpIstri.length < 3) {
                            Text("Nama harus minimal 3 karakter")
                        }
                    },
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )

                ModernTextField(
                    value = nikIstri,
                    onValueChange = onNikIstriChange,
                    label = "NIK Istri",
                    isError = nikIstri.length != 16,
                    supportingText = {
                        if (nikIstri.length != 16) {
                            Text("NIK harus 16 digit angka")
                        }
                    },
                    keyboardType = KeyboardType.Number,
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )

                ModernTextField(
                    value = namaPanggilanIstri,
                    onValueChange = onNamaPanggilanIstriChange,
                    label = "Nama Panggilan Istri",
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )

                ModernKtpUploadSection(
                    title = "Foto KTP Istri",
                    imageUri = fotoKtpIstriUri,
                    onImageChange = onFotoKtpIstriChange,
                    onScanKTP = onScanKTPIstri,
                    onPickFromGallery = onPickFromGalleryIstri,
                    isDark = isDark,
                    borderColor = borderColor
                )
            }
        }

        // ✅ BARU: Card Foto Nasabah
        ModernCardContainer(
            isDark = isDark,
            cardColor = cardColor
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Foto Nasabah",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = txtColor,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Text(
                    text = "Ambil foto nasabah menggunakan kamera",
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor
                )

                // Tombol Ambil Foto
                Button(
                    onClick = onTakeFotoNasabah,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TambahPelangganColors.info
                    )
                ) {
                    Icon(
                        Icons.Rounded.CameraAlt,
                        contentDescription = "Ambil Foto",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ambil Foto Nasabah", fontSize = 14.sp)
                }

                // Preview gambar
                if (fotoNasabahUri != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                            .background(Color.LightGray)
                    ) {
                        AsyncImage(
                            model = fotoNasabahUri,
                            contentDescription = "Preview Foto Nasabah",
                            modifier = Modifier.fillMaxSize()
                        )

                        // Tombol hapus
                        IconButton(
                            onClick = { onFotoNasabahChange(null) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .background(Color.Red, CircleShape)
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                "Hapus",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                            .background(Color.LightGray.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = "No Image",
                                tint = Color.Gray,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Belum ada foto nasabah",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Text(
                                "Klik tombol di atas untuk mengambil foto",
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Text(
                    text = "Pastikan wajah nasabah terlihat jelas",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun ModernKtpUploadSection(
    title: String,
    imageUri: Uri?,
    onImageChange: (Uri?) -> Unit,
    onScanKTP: () -> Unit,
    onPickFromGallery: () -> Unit,
    isDark: Boolean,
    borderColor: Color
) {
    val backgroundColor = if (isDark) Color(0xFF1E1E1E) else Color.White

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(listOf(borderColor, borderColor))
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Row untuk 2 tombol: Scan KTP dan Pilih dari Galeri
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tombol Scan KTP (existing)
                Button(
                    onClick = onScanKTP,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TambahPelangganColors.primary
                    )
                ) {
                    Icon(
                        Icons.Rounded.CameraAlt,
                        contentDescription = "Scan KTP",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan KTP", fontSize = 12.sp)
                }

                // Tombol Pilih dari Galeri (NEW)
                OutlinedButton(
                    onClick = onPickFromGallery,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TambahPelangganColors.info
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        TambahPelangganColors.info
                    )
                ) {
                    Icon(
                        Icons.Rounded.PhotoLibrary,
                        contentDescription = "Pilih dari Galeri",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Galeri", fontSize = 12.sp)
                }
            }

            // Preview gambar
            if (imageUri != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                        .background(Color.LightGray)
                ) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = "Preview KTP",
                        modifier = Modifier.fillMaxSize()
                    )

                    // Tombol hapus
                    IconButton(
                        onClick = { onImageChange(null) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .background(Color.Red, CircleShape)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            "Hapus",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                        .background(Color.LightGray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.Photo,
                            contentDescription = "No Image",
                            tint = Color.Gray,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Belum ada foto KTP",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Text(
                            "Scan KTP atau pilih dari Galeri",
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Text(
                text = "Scan KTP: ekstrak data otomatis + simpan foto. Galeri: pilih foto KTP yang sudah ada.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ==================== UTILITY FUNCTIONS ====================

private fun formatName(nama: String): String {
    return nama.lowercase()
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        .replace(Regex("[^a-zA-Z. ]"), "")
}

private fun formatAddress(address: String): String {
    return address.replace(Regex("\\s+"), " ")
        .replace(Regex("[^a-zA-Z0-9 /.,-]"), "")
        .trim()
}

private fun hitungSimulasiCicilanHarian(
    besarPinjaman: Long,
    tenorHari: Int,
    totalPelunasan: Long
): List<SimulasiCicilan> {
    val simulasi = mutableListOf<SimulasiCicilan>()
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("in", "ID"))
    val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    val cicilanPerHari = totalPelunasan / tenorHari
    val cicilanPerHariBulat = ((cicilanPerHari.toDouble() / 100.0).roundToInt() * 100).toInt()
    val totalDariAngsuran = cicilanPerHariBulat.toLong() * (tenorHari - 1)
    val cicilanTerakhir = (totalPelunasan - totalDariAngsuran).toInt()

    val startDate = Calendar.getInstance()
    var currentDate = startDate.clone() as Calendar
    var hariCount = 0

    while (hariCount < tenorHari) {
        currentDate.add(Calendar.DAY_OF_YEAR, 1)
        val isHariIniKerja = HolidayUtils.isHariKerja(currentDate)
        val tanggal = dateFormat.format(currentDate.time)

        if (isHariIniKerja) {
            hariCount++
            val jumlahHariIni: Int = if (hariCount == tenorHari) {
                cicilanTerakhir
            } else {
                cicilanPerHariBulat
            }

            simulasi.add(SimulasiCicilan(
                tanggal = tanggal,
                jumlah = jumlahHariIni,
                isHariKerja = true,
                isCompleted = false,
                version = 2,
                lastUpdated = currentTime
            ))
        }
    }

    val totalSimulasi = simulasi.sumOf { it.jumlah.toLong() }
    if (totalSimulasi != totalPelunasan) {
        Log.w("CicilanCalculator", "⚠️ Total simulasi ($totalSimulasi) tidak sama dengan total pelunasan ($totalPelunasan)")
        if (simulasi.isNotEmpty()) {
            val selisih = (totalPelunasan - totalSimulasi).toInt()
            val lastIndex = simulasi.size - 1
            simulasi[lastIndex] = simulasi[lastIndex].copy(jumlah = simulasi[lastIndex].jumlah + selisih)
        }
    }

    Log.d("CicilanCalculator", "✅ Generated ${simulasi.size} cicilan, Total: ${simulasi.sumOf { it.jumlah }}")
    return simulasi
}

@Composable
fun InAppCameraCapture(
    onPhotoCaptured: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(android.view.Surface.ROTATION_0)
            .build()
    }

    var isCapturing by remember { mutableStateOf(false) }
    var isFrontCamera by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(isFrontCamera) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        try {
            cameraProvider.unbindAll()
            val cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            Log.e("InAppCamera", "Gagal bind kamera: ${e.message}")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Tombol Switch Kamera (kanan atas)
        Button(
            onClick = {
                if (!isCapturing) {
                    isFrontCamera = !isFrontCamera
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.7f))
        ) {
            Text("🔄 Rubah", color = Color.Black)
        }

        // Tombol Ambil Foto (bawah tengah)
        Button(
            onClick = {
                if (isCapturing) return@Button
                isCapturing = true

                val photoFile = File(
                    context.cacheDir,
                    "foto_nasabah_${System.currentTimeMillis()}.jpg"
                )
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                photoFile
                            )
                            Log.d("InAppCamera", "✅ Foto tersimpan: $uri")
                            onPhotoCaptured(uri)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("InAppCamera", "❌ Gagal: ${exception.message}")
                            Toast.makeText(context, "Gagal ambil foto", Toast.LENGTH_SHORT).show()
                            isCapturing = false
                        }
                    }
                )
            },
            enabled = !isCapturing,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .height(56.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text(
                if (isCapturing) "Menyimpan..." else "📷 Ambil Foto",
                fontSize = 16.sp
            )
        }
    }
}