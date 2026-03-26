package com.example.koperasikitagodangulu

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.models.DualApprovalInfo
import com.example.koperasikitagodangulu.models.ApprovalStatus
import com.example.koperasikitagodangulu.models.ApproverRole
import androidx.compose.material.icons.filled.Cancel
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailNotifikasiScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    notificationId: String?
) {
    val adminNotifications by viewModel.adminNotifications.collectAsState()
    val context = LocalContext.current

    val currentNotification = remember(notificationId) {
        adminNotifications.find { it.id == notificationId }
    }

    val isDark by viewModel.isDarkMode
    val txtColor = if (isDark) Color.White else Color.Black
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) Color.Black else Color(0xFFF5F5F5)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // ✅ State untuk foto serah terima
    var tempSerahTerimaUri by remember { mutableStateOf<Uri?>(null) }
    var isUploadingSerahTerima by remember { mutableStateOf(false) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }

    // ✅ Ambil data pelanggan dari notifikasi (dengan re-fetch saat daftarPelanggan berubah)
    // ✅ FIX: Hapus .size dari key — derivedStateOf sudah otomatis melacak perubahan daftarPelanggan
    val pelanggan by remember(notificationId) {
        derivedStateOf {
            currentNotification?.pelangganId?.let { viewModel.getPelangganById(it) }
        }
    }

    fun formatRupiahLocal(amount: Int): String {
        val formatter = NumberFormat.getNumberInstance(Locale("in", "ID"))
        return "Rp ${formatter.format(amount)}"
    }

    // ✅ Camera launcher untuk foto serah terima
    val serahTerimaCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempSerahTerimaUri != null && currentNotification != null) {
            isUploadingSerahTerima = true
            viewModel.uploadFotoSerahTerima(
                pelangganId = currentNotification.pelangganId,
                fotoUri = tempSerahTerimaUri!!,
                onSuccess = {
                    isUploadingSerahTerima = false
                    Toast.makeText(context, "✅ Foto serah terima berhasil diupload", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    isUploadingSerahTerima = false
                    Toast.makeText(context, "❌ Gagal: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    // ✅ PERMISSION LAUNCHER - Request camera permission
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, buka kamera
            try {
                val photoFile = File(
                    context.cacheDir,
                    "foto_serah_terima_${System.currentTimeMillis()}.jpg"
                )
                tempSerahTerimaUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    photoFile
                )
                serahTerimaCameraLauncher.launch(tempSerahTerimaUri)
            } catch (e: Exception) {
                Log.e("DetailNotifikasi", "Error opening camera: ${e.message}")
                Toast.makeText(context, "Gagal membuka kamera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            // Permission denied
            showPermissionDeniedDialog = true
        }
    }

    // ✅ FUNGSI UNTUK MEMBUKA KAMERA (dengan permission check)
    fun openSerahTerimaCamera() {
        when {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission sudah ada, langsung buka kamera
                try {
                    val photoFile = File(
                        context.cacheDir,
                        "foto_serah_terima_${System.currentTimeMillis()}.jpg"
                    )
                    tempSerahTerimaUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        photoFile
                    )
                    serahTerimaCameraLauncher.launch(tempSerahTerimaUri)
                } catch (e: Exception) {
                    Log.e("DetailNotifikasi", "Error opening camera: ${e.message}")
                    Toast.makeText(context, "Gagal membuka kamera: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            else -> {
                // Request permission
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // ✅ TAMBAH: Refresh pelanggan data saat screen dibuka
    LaunchedEffect(notificationId) {
        currentNotification?.pelangganId?.let { pelangganId ->
            // Force refresh data pelanggan untuk mendapatkan dualApprovalInfo terbaru
            viewModel.refreshSinglePelanggan(pelangganId)
        }
    }

    // ✅ FIX: LaunchedEffect dengan proper null check
    LaunchedEffect(notificationId) {
        if (notificationId != null && currentNotification?.read == false) {
            Log.d("Notification", "🔄 Marking notification as read: $notificationId")
            viewModel.markNotificationAsRead(notificationId)

            // ✅ TAMBAH: Force refresh setelah marking
            delay(800)
            viewModel.loadAdminNotifications()
        }
    }

    // ✅ FIX: Refresh saat kembali ke screen ini
    DisposableEffect(navController) {
        onDispose {
            viewModel.loadAdminNotifications()
        }
    }

    // ✅ DIALOG PERMISSION DENIED
    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text("Izin Kamera Diperlukan") },
            text = {
                Text("Untuk mengambil foto serah terima, aplikasi memerlukan izin akses kamera. Silakan izinkan di pengaturan aplikasi.")
            },
            confirmButton = {
                TextButton(onClick = { showPermissionDeniedDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Detail Notifikasi",
                        style = MaterialTheme.typography.titleMedium,
                        color = txtColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.loadAdminNotifications()
                        navController.popBackStack()
                    }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Kembali",
                            tint = txtColor
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = if (isDark) Color.Black else Color.White
                )
            )
        },
        containerColor = bgColor
    ) { innerPadding ->
        if (currentNotification == null) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Notifikasi tidak ditemukan", color = txtColor)
            }
        } else {
            val notification = currentNotification

            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header dengan indicator read status
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                notification.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = txtColor
                            )

                            if (!notification.read) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(Color.Red, CircleShape)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val isRejection = notification.type.contains("REJECTION", ignoreCase = true)
                        val isApproval = notification.type.contains("APPROVAL", ignoreCase = true) && !isRejection

                        Box(
                            modifier = Modifier
                                .background(
                                    color = when {
                                        isRejection -> Color(0xFFFF5252)  // Merah untuk semua penolakan
                                        isApproval -> Color(0xFF4CAF50)   // Hijau untuk persetujuan
                                        else -> Color(0xFF2196F3)         // Biru untuk lainnya
                                    },
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                when {
                                    isRejection -> "Ditolak"
                                    isApproval -> "Disetujui"
                                    else -> "Notifikasi"
                                },
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                if (notification.type == "APPROVAL" || notification.type == "DUAL_APPROVAL_APPROVED") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = cardColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                "Informasi Persetujuan",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = txtColor,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            DetailItem("Disetujui Oleh", notification.disetujuiOleh, txtColor)

                            if (!notification.isPinjamanDiubah) {
                                DetailItem(
                                    "Pinjaman yang diajukan",
                                    formatRupiahLocal(notification.pinjamanDiajukan),
                                    txtColor
                                )
                                DetailItem(
                                    "Tenor yang diajukan",
                                    "${notification.tenorDiajukan} hari",
                                    txtColor
                                )
                            } else if (notification.isPinjamanDiubah &&
                                notification.pinjamanDiajukan != notification.pinjamanDisetujui &&
                                notification.tenorDiajukan == notification.tenorDisetujui
                            ) {
                                DetailItem(
                                    "Pinjaman yang diajukan",
                                    formatRupiahLocal(notification.pinjamanDiajukan),
                                    txtColor
                                )
                                DetailItem(
                                    "Pinjaman yang disetujui",
                                    formatRupiahLocal(notification.pinjamanDisetujui),
                                    txtColor
                                )
                                DetailItem(
                                    "Tenor",
                                    "${notification.tenorDisetujui} hari disetujui",
                                    txtColor
                                )
                            } else if (notification.isPinjamanDiubah &&
                                notification.pinjamanDiajukan == notification.pinjamanDisetujui &&
                                notification.tenorDiajukan != notification.tenorDisetujui
                            ) {
                                DetailItem(
                                    "Tenor yang diajukan",
                                    "${notification.tenorDiajukan} hari",
                                    txtColor
                                )
                                DetailItem(
                                    "Tenor yang disetujui",
                                    "${notification.tenorDisetujui} hari",
                                    txtColor
                                )
                                DetailItem(
                                    "Pinjaman",
                                    "${formatRupiahLocal(notification.pinjamanDisetujui)} disetujui",
                                    txtColor
                                )
                            } else if (notification.isPinjamanDiubah &&
                                notification.pinjamanDiajukan != notification.pinjamanDisetujui &&
                                notification.tenorDiajukan != notification.tenorDisetujui
                            ) {
                                DetailItem(
                                    "Pinjaman yang diajukan",
                                    formatRupiahLocal(notification.pinjamanDiajukan),
                                    txtColor
                                )
                                DetailItem(
                                    "Tenor yang diajukan",
                                    "${notification.tenorDiajukan} hari",
                                    txtColor
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                DetailItem(
                                    "Pinjaman yang disetujui",
                                    formatRupiahLocal(notification.pinjamanDisetujui),
                                    txtColor
                                )
                                DetailItem(
                                    "Tenor yang disetujui",
                                    "${notification.tenorDisetujui} hari",
                                    txtColor
                                )
                            }

                            if (notification.catatanPersetujuan.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Catatan: ${notification.catatanPersetujuan}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = txtColor.copy(alpha = 0.8f),
                                    fontStyle = FontStyle.Italic
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Detail Informasi
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "Detail Informasi",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = txtColor,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        DetailItem("Nama Nasabah", notification.pelangganNama, txtColor)
                        DetailItem("ID Nasabah", notification.pelangganId, txtColor)

                        if ((notification.type == "APPROVAL" || notification.type == "DUAL_APPROVAL_APPROVED") && notification.isPinjamanDiubah) {
                            DetailItem(
                                "Pinjaman Diajukan",
                                formatRupiahLocal(notification.pinjamanDiajukan),
                                txtColor
                            )
                            DetailItem(
                                "Pinjaman Disetujui",
                                formatRupiahLocal(notification.pinjamanDisetujui),
                                txtColor
                            )
                            DetailItem(
                                "Tenor Diajukan",
                                "${notification.tenorDiajukan} hari",
                                txtColor
                            )
                            DetailItem(
                                "Tenor Disetujui",
                                "${notification.tenorDisetujui} hari",
                                txtColor
                            )

                            val selisih =
                                notification.pinjamanDisetujui - notification.pinjamanDiajukan
                            DetailItem(
                                "Perubahan",
                                if (selisih < 0) "Pengurangan ${formatRupiahLocal(-selisih)}"
                                else "Penambahan ${formatRupiahLocal(selisih)}",
                                if (selisih < 0) Color(0xFFF44336) else Color(0xFF4CAF50)
                            )

                            val selisihTenor =
                                notification.tenorDisetujui - notification.tenorDiajukan
                            if (selisihTenor != 0) {
                                DetailItem(
                                    "Perubahan Tenor",
                                    if (selisihTenor < 0) "Dikurangi ${-selisihTenor} hari"
                                    else "Ditambah ${selisihTenor} hari",
                                    if (selisihTenor < 0) Color(0xFFF44336) else Color(0xFF4CAF50)
                                )
                            }

                            if (notification.catatanPerubahanPinjaman.isNotBlank()) {
                                DetailItem(
                                    "Alasan Penyesuaian",
                                    notification.catatanPerubahanPinjaman,
                                    txtColor
                                )
                            }
                        } else if (notification.type == "APPROVAL" || notification.type == "DUAL_APPROVAL_APPROVED") {
                            DetailItem(
                                "Pinjaman Disetujui",
                                formatRupiahLocal(notification.pinjamanDisetujui),
                                txtColor
                            )
                            DetailItem(
                                "Tenor Disetujui",
                                "${notification.tenorDisetujui} hari",
                                txtColor
                            )
                        }

                        if ((notification.type == "APPROVAL" || notification.type == "DUAL_APPROVAL_APPROVED") && notification.catatanPersetujuan.isNotBlank()) {
                            DetailItem(
                                "Catatan Persetujuan",
                                notification.catatanPersetujuan,
                                txtColor
                            )
                        }

                        if ((notification.type == "REJECTION" || notification.type == "DUAL_APPROVAL_REJECTED") && notification.alasanPenolakan.isNotBlank()) {
                            DetailItem("Alasan Penolakan", notification.alasanPenolakan, txtColor)
                        }

                        DetailItem(
                            "Status",
                            when (notification.type) {
                                "REJECTION", "DUAL_APPROVAL_REJECTED" -> "Ditolak"
                                "APPROVAL", "DUAL_APPROVAL_APPROVED" -> "Disetujui"
                                else -> "Diproses"
                            },
                            txtColor
                        )

                        DetailItem(
                            "Waktu",
                            formatDetailedTimestamp(notification.timestamp),
                            txtColor
                        )
                    }
                }

                // ✅ TAMBAHKAN INI: Section Dual Approval Result
                // Menampilkan hasil persetujuan ganda (Pimpinan + Pengawas)
                // untuk pinjaman >= 3.000.000
                DualApprovalResultSection(
                    notification = notification,
                    pelanggan = pelanggan
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Pesan Notifikasi
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "Pesan",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = txtColor,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Text(
                            notification.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = txtColor,
                            lineHeight = 20.sp
                        )
                    }
                }

                // ✅ BARU: Card Foto Serah Terima Uang (hanya untuk APPROVAL pinjaman 3jt ke atas)
                if ((notification.type == "APPROVAL" || notification.type == "DUAL_APPROVAL_APPROVED") &&
                    (pelanggan?.tipePinjaman == "diatas_3jt" || notification.pinjamanDisetujui >= 3000000)
                ) {

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                pelanggan?.fotoSerahTerimaUrl?.isNotBlank() == true -> Color(0xFFE8F5E9)
                                else -> cardColor
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (pelanggan?.fotoSerahTerimaUrl?.isNotBlank() == true)
                                        Icons.Default.CheckCircle
                                    else
                                        Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = if (pelanggan?.fotoSerahTerimaUrl?.isNotBlank() == true)
                                        Color(0xFF4CAF50)
                                    else
                                        Color(0xFFFF9800),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Foto Serah Terima Uang",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = txtColor
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            when {
                                // Sudah ada foto serah terima
                                pelanggan?.fotoSerahTerimaUrl?.isNotBlank() == true -> {
                                    Text(
                                        "✅ Foto serah terima sudah diupload",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.Medium
                                    )

                                    if (pelanggan?.tanggalSerahTerima?.isNotBlank() == true) {
                                        Text(
                                            "Diserahkan: ${pelanggan?.tanggalSerahTerima}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = txtColor.copy(alpha = 0.7f)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Preview foto
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        AsyncImage(
                                            model = pelanggan?.fotoSerahTerimaUrl,
                                            contentDescription = "Foto Serah Terima",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                // Pending upload (offline)
                                pelanggan?.pendingFotoSerahTerimaUri?.isNotBlank() == true -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = Color(0xFFFF9800),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "⏳ Foto akan diupload saat online",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color(0xFFFF9800)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Preview foto pending
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        AsyncImage(
                                            model = Uri.parse(pelanggan?.pendingFotoSerahTerimaUri),
                                            contentDescription = "Foto Serah Terima (Pending)",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                // Belum ada foto - tampilkan tombol upload
                                else -> {
                                    Text(
                                        "Setelah menyerahkan uang kepada nasabah, ambil foto sebagai bukti serah terima.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = txtColor.copy(alpha = 0.8f)
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = { openSerahTerimaCamera() },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF4CAF50)
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        enabled = !isUploadingSerahTerima
                                    ) {
                                        if (isUploadingSerahTerima) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Mengupload...")
                                        } else {
                                            Icon(
                                                Icons.Default.CameraAlt,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "Ambil Foto Serah Terima",
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        "💡 Pastikan foto menampilkan nasabah menerima uang dengan jelas",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = txtColor.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String, textColor: Color) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.7f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatDetailedTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("dd MMMM yyyy, HH:mm:ss", Locale("in", "ID"))
    return format.format(date)
}

@Composable
fun DualApprovalResultSection(
    notification: AdminNotification,
    pelanggan: Pelanggan?
) {
    val dualInfo = pelanggan?.dualApprovalInfo

    // Hanya tampilkan jika ini adalah notifikasi terkait dual approval
    if (notification.type !in listOf(
            "DUAL_APPROVAL_APPROVED",
            "DUAL_APPROVAL_REJECTED",
            "APPROVAL_PENGAWAS",
            "REJECTION_PENGAWAS",
            "APPROVAL",
            "REJECTION"
        )) {
        return
    }

    val isApproved = notification.type.contains("APPROVED") || notification.type == "APPROVAL"
    val isPengawasDecision = notification.type.contains("PENGAWAS")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isApproved)
                Color(0xFFE8F5E9) // Light green
            else
                Color(0xFFFFEBEE) // Light red
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isApproved) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (isApproved) Color(0xFF4CAF50) else Color(0xFFF44336),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = if (isApproved) "PENGAJUAN DISETUJUI" else "PENGAJUAN DITOLAK",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (isApproved) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info Keputusan
            if (dualInfo != null && dualInfo.requiresDualApproval) {
                // Dual Approval Case

                // ✅ PERBAIKAN: Tampilkan RINGKASAN KEPUTUSAN yang jelas
                val pimpinanStatus = dualInfo.pimpinanApproval.status
                val pengawasStatus = dualInfo.pengawasApproval.status
                val isPimpinanApproved = pimpinanStatus == "approved"
                val isPimpinanRejected = pimpinanStatus == "rejected"
                val isPengawasApproved = pengawasStatus == "approved"
                val isPengawasRejected = pengawasStatus == "rejected"

                // Ringkasan Keputusan
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isApproved) Color(0xFFC8E6C9) else Color(0xFFFFCDD2)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "📋 Ringkasan Keputusan:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isApproved) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Tampilkan ringkasan berdasarkan kombinasi status
                        val summaryText = when {
                            // Kedua setuju
                            isPimpinanApproved && isPengawasApproved -> {
                                "✅ Disetujui oleh Pimpinan dan Pengawas"
                            }
                            // Kedua menolak
                            isPimpinanRejected && isPengawasRejected -> {
                                "❌ Ditolak oleh Pimpinan dan Pengawas"
                            }
                            // Pimpinan setuju, Pengawas menolak
                            isPimpinanApproved && isPengawasRejected -> {
                                "❌ Ditolak oleh Pengawas\n✅ Pimpinan menyetujui"
                            }
                            // Pengawas setuju, Pimpinan menolak
                            isPengawasApproved && isPimpinanRejected -> {
                                "❌ Ditolak oleh Pimpinan\n✅ Pengawas menyetujui"
                            }
                            // Fallback
                            else -> {
                                if (isApproved) "✅ Pengajuan Disetujui" else "❌ Pengajuan Ditolak"
                            }
                        }

                        Text(
                            text = summaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isApproved) Color(0xFF2E7D32) else Color(0xFFC62828),
                            lineHeight = 20.sp
                        )
                    }
                }

                Text(
                    "Detail Status Persetujuan:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Status Pimpinan
                ApprovalStatusRow(
                    role = "Pimpinan",
                    status = dualInfo.pimpinanApproval.status,
                    by = dualInfo.pimpinanApproval.by,
                    timestamp = dualInfo.pimpinanApproval.timestamp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ✅ TAMBAH: Status Koordinator
                ApprovalStatusRow(
                    role = "Koordinator",
                    status = dualInfo.koordinatorApproval.status,
                    by = dualInfo.koordinatorApproval.by,
                    timestamp = dualInfo.koordinatorApproval.timestamp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Status Pengawas
                ApprovalStatusRow(
                    role = "Pengawas",
                    status = dualInfo.pengawasApproval.status,
                    by = dualInfo.pengawasApproval.by,
                    timestamp = dualInfo.pengawasApproval.timestamp
                )

                // ✅ TAMBAH: Tampilkan semua catatan dari setiap approver
                val pimpinanNote = dualInfo.pimpinanApproval.note
                val koordinatorNote = dualInfo.koordinatorApproval.note
                val pengawasNote = dualInfo.pengawasApproval.note

                if (pimpinanNote.isNotBlank() || koordinatorNote.isNotBlank() || pengawasNote.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF5F5F5)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "📝 Catatan Persetujuan:",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF424242)
                            )

                            if (pimpinanNote.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Pimpinan: $pimpinanNote",
                                    fontSize = 13.sp,
                                    color = Color(0xFF5D4037)
                                )
                            }

                            if (koordinatorNote.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Koordinator: $koordinatorNote",
                                    fontSize = 13.sp,
                                    color = Color(0xFF5D4037)
                                )
                            }

                            if (pengawasNote.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Pengawas: $pengawasNote",
                                    fontSize = 13.sp,
                                    color = Color(0xFF5D4037)
                                )
                            }
                        }
                    }
                }

                // Jika ditolak, tampilkan alasan penolakan
                if (!isApproved && dualInfo.rejectionReason.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3E0)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "📝 Alasan Penolakan:",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                dualInfo.rejectionReason,
                                fontSize = 13.sp,
                                color = Color(0xFF5D4037)
                            )
                        }
                    }
                }
            } else {
                // Single Approval Case (< 3jt)
                Text(
                    "Diputuskan oleh: ${pelanggan?.disetujuiOleh ?: "Pimpinan"}",
                    fontSize = 14.sp
                )

                if (!isApproved) {
                    Spacer(modifier = Modifier.height(8.dp))

                    val alasan = pelanggan?.alasanPenolakan
                        ?: pelanggan?.catatanApproval?.substringAfter("Alasan: ")?.trim()
                        ?: "-"

                    if (alasan.isNotBlank() && alasan != "-") {
                        Text(
                            "Alasan penolakan:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color(0xFFC62828)
                        )
                        Text(
                            alasan,
                            fontSize = 13.sp,
                            color = Color(0xFF5D4037)
                        )
                    }
                }
            }

            // Tanggal keputusan
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Tanggal: ${pelanggan?.tanggalApproval ?: "-"}",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun ApprovalStatusRow(
    role: String,
    status: String,
    by: String,
    timestamp: Long
) {
    val statusText = when (status) {
        "approved" -> "✅ Disetujui"
        "rejected" -> "❌ Ditolak"
        else -> "⏳ Menunggu"
    }

    val statusColor = when (status) {
        "approved" -> Color(0xFF4CAF50)
        "rejected" -> Color(0xFFF44336)
        else -> Color(0xFFFF9800)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                role,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
            if (by.isNotBlank()) {
                Text(
                    by,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        Text(
            statusText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = statusColor
        )
    }
}