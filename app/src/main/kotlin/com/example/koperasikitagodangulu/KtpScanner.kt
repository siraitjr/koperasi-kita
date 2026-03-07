package com.example.koperasikitagodangulu

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun KtpScanner(
    modifier: Modifier = Modifier,
    onScanResult: (CompleteScanResult) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isCapturing by remember { mutableStateOf(false) }
    var isStable by remember { mutableStateOf(false) }
    var stabilityCounter by remember { mutableStateOf(0) }
    var lastBrightness by remember { mutableStateOf(0f) }
    var autoCapturing by remember { mutableStateOf(false) }
    val stabilityThreshold = 10 // Dinaikkan ke 15 frame untuk lebih akurat
    var hasMinimumBrightness by remember { mutableStateOf(false) } // Validasi brightness
    var ktpDetected by remember { mutableStateOf(false) }
    var isFrontCamera by remember { mutableStateOf(false) }
    var isAnalyzingOcr by remember { mutableStateOf(false) }
    var ktpKeywordCount by remember { mutableIntStateOf(0) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val textRecognizer = remember {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasCameraPermission = isGranted
            if (!isGranted) {
                onScanResult(CompleteScanResult(KtpData(error = "Izin kamera tidak diberikan"), null))
            }
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(isStable) {
        if (isStable && !isCapturing && !autoCapturing) {
            autoCapturing = true
            Log.d("KtpScanner", ">>> Auto-capture STARTED, waiting for focus...")

            // Delay untuk fokus kamera - JANGAN cek isStable lagi setelah ini
            delay(600)

            // Langsung capture tanpa cek isStable lagi (sudah di-lock oleh autoCapturing)
            if (!isCapturing) {
                Log.d("KtpScanner", ">>> CAPTURING NOW!")
                isCapturing = true
                performCapture(
                    context = context,
                    imageCapture = imageCapture,
                    onSuccess = { result ->
                        onScanResult(result)
                        isCapturing = false
                        autoCapturing = false
                    },
                    onError = { error ->
                        onScanResult(error)
                        isCapturing = false
                        autoCapturing = false
                        stabilityCounter = 0
                        isStable = false
                    }
                )
            } else {
                autoCapturing = false
            }
        }
    }

    if (hasCameraPermission) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Camera Preview (full screen)
            key(isFrontCamera) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        val previewView = PreviewView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder().build().also { preview ->
                                preview.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            // ImageAnalysis untuk deteksi KTP sesungguhnya
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                                        // PENTING: Jika sudah dalam proses capture, skip
                                        if (autoCapturing || isCapturing) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }

                                        val (brightness, variance) = analyzeImageBrightness(
                                            imageProxy
                                        )
                                        val brightnessDiff =
                                            kotlin.math.abs(brightness - lastBrightness)

                                        val isBrightnessValid = brightness in 45f..210f
                                        val hasContent = variance > 500f
                                        val isMovementStable = brightnessDiff < 4f

                                        lastBrightness = brightness

                                        if (isMovementStable && isBrightnessValid && hasContent) {
                                            stabilityCounter++

                                            // Setelah stabil 5 frame, jalankan OCR untuk deteksi KTP
                                            if (stabilityCounter >= 5 && !ktpDetected && !isAnalyzingOcr) {
                                                isAnalyzingOcr = true
                                                val bitmap = imageProxyToBitmap(imageProxy)
                                                if (bitmap != null) {
                                                    val inputImage = InputImage.fromBitmap(
                                                        bitmap,
                                                        imageProxy.imageInfo.rotationDegrees
                                                    )
                                                    textRecognizer.process(inputImage)
                                                        .addOnSuccessListener { visionText ->
                                                            val text = visionText.text.uppercase()
                                                            val keywords = listOf(
                                                                "NIK", "NAMA", "ALAMAT",
                                                                "PROVINSI", "KABUPATEN", "KOTA",
                                                                "KECAMATAN", "KELURAHAN", "AGAMA",
                                                                "PEKERJAAN", "KEWARGANEGARAAN"
                                                            )
                                                            val matchCount = keywords.count { kw ->
                                                                text.contains(kw)
                                                            }
                                                            ktpKeywordCount = matchCount

                                                            if (matchCount >= 2) {
                                                                ktpDetected = true
                                                                Log.d(
                                                                    "KtpScanner",
                                                                    "✓ KTP DETECTED! Keywords found: $matchCount"
                                                                )
                                                            } else {
                                                                Log.d(
                                                                    "KtpScanner",
                                                                    "✗ Bukan KTP. Keywords: $matchCount"
                                                                )
                                                            }
                                                            isAnalyzingOcr = false
                                                            imageProxy.close()
                                                        }
                                                        .addOnFailureListener { e ->
                                                            Log.e(
                                                                "KtpScanner",
                                                                "OCR preview gagal: ${e.message}"
                                                            )
                                                            isAnalyzingOcr = false
                                                            imageProxy.close()
                                                        }
                                                } else {
                                                    isAnalyzingOcr = false
                                                    imageProxy.close()
                                                }
                                            } else {
                                                // Auto-capture hanya trigger jika KTP terdeteksi + cukup stabil
                                                if (stabilityCounter >= stabilityThreshold && ktpDetected) {
                                                    isStable = true
                                                    Log.d(
                                                        "KtpScanner",
                                                        "✓ READY! KTP detected + stable"
                                                    )
                                                }
                                                imageProxy.close()
                                            }
                                        } else {
                                            if (stabilityCounter > 0) {
                                                Log.d(
                                                    "KtpScanner",
                                                    "Reset: bright=$brightness, var=$variance, diff=$brightnessDiff"
                                                )
                                            }
                                            stabilityCounter = 0
                                            isStable = false
                                            ktpDetected = false
                                            ktpKeywordCount = 0
                                            imageProxy.close()
                                        }
                                    }
                                }

                            val cameraSelector =
                                if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageCapture,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e("KtpScanner", "Gagal mengikat kamera use cases", e)
                            }
                        }, ContextCompat.getMainExecutor(context))

                        previewView
                    }
                )
            }

            // Dark overlay with KTP frame cutout
            Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.99f }) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // KTP frame dimensions (rasio KTP = 85.6mm x 53.98mm ≈ 1.586:1)
                val frameWidth = canvasWidth * 0.85f
                val frameHeight = frameWidth / 1.586f
                val frameLeft = (canvasWidth - frameWidth) / 2f
                val frameTop = canvasHeight * 0.3f

                // Draw semi-transparent overlay
                drawRect(
                    color = Color.Black.copy(alpha = 0.6f),
                    size = size
                )

                // Cut out the KTP frame area (transparent)
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(frameLeft, frameTop),
                    size = Size(frameWidth, frameHeight),
                    cornerRadius = CornerRadius(16f, 16f),
                    blendMode = BlendMode.Clear
                )

                // Draw border around frame - berubah warna saat stabil
                val borderColor = when {
                    isStable -> Color(0xFF4CAF50)
                    ktpDetected -> Color(0xFFFFC107) // Kuning = KTP terdeteksi, tunggu stabil
                    else -> Color.White.copy(alpha = 0.7f)
                }
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(frameLeft, frameTop),
                    size = Size(frameWidth, frameHeight),
                    cornerRadius = CornerRadius(16f, 16f),
                    style = Stroke(width = if (isStable) 6f else 3f)
                )

                // Draw corner accents
                val cornerLength = 40f
                val cornerStroke = 8f
                val frameColor = when {
                    isStable -> Color(0xFF4CAF50)
                    ktpDetected -> Color(0xFFFFC107)
                    else -> Color.White
                }

                // Top-left corner
                drawLine(frameColor, Offset(frameLeft, frameTop + cornerLength), Offset(frameLeft, frameTop), strokeWidth = cornerStroke)
                drawLine(frameColor, Offset(frameLeft, frameTop), Offset(frameLeft + cornerLength, frameTop), strokeWidth = cornerStroke)

                // Top-right corner
                drawLine(frameColor, Offset(frameLeft + frameWidth - cornerLength, frameTop), Offset(frameLeft + frameWidth, frameTop), strokeWidth = cornerStroke)
                drawLine(frameColor, Offset(frameLeft + frameWidth, frameTop), Offset(frameLeft + frameWidth, frameTop + cornerLength), strokeWidth = cornerStroke)

                // Bottom-left corner
                drawLine(frameColor, Offset(frameLeft, frameTop + frameHeight - cornerLength), Offset(frameLeft, frameTop + frameHeight), strokeWidth = cornerStroke)
                drawLine(frameColor, Offset(frameLeft, frameTop + frameHeight), Offset(frameLeft + cornerLength, frameTop + frameHeight), strokeWidth = cornerStroke)

                // Bottom-right corner
                drawLine(frameColor, Offset(frameLeft + frameWidth, frameTop + frameHeight - cornerLength), Offset(frameLeft + frameWidth, frameTop + frameHeight), strokeWidth = cornerStroke)
                drawLine(frameColor, Offset(frameLeft + frameWidth - cornerLength, frameTop + frameHeight), Offset(frameLeft + frameWidth, frameTop + frameHeight), strokeWidth = cornerStroke)
            }

            // Back button (top-left)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Kembali",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Switch Camera button (top-right)
            IconButton(
                onClick = {
                    if (!isCapturing && !autoCapturing) {
                        isFrontCamera = !isFrontCamera
                        // Reset detection state saat switch kamera
                        stabilityCounter = 0
                        isStable = false
                        ktpDetected = false
                        ktpKeywordCount = 0
                        isAnalyzingOcr = false
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    Icons.Default.Cameraswitch,
                    contentDescription = "Ganti Kamera",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                text = when {
                    isCapturing -> "📸 Memproses KTP..."
                    isStable -> "✓ Tahan... mengambil foto"
                    ktpDetected && stabilityCounter > 5 -> "✓ KTP terdeteksi! Tahan posisi..."
                    isAnalyzingOcr -> "Memeriksa KTP..."
                    stabilityCounter > 5 -> "Mencari teks KTP..."
                    stabilityCounter > 0 -> "Mendeteksi... tahan posisi"
                    else -> "Arahkan KTP ke dalam bingkai\nPastikan pencahayaan cukup"
                },
                color = if (isStable) Color(0xFF4CAF50) else Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (isStable) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 200.dp)
            )

            // Processing indicator overlay
            if (isCapturing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Memproses KTP...",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Aplikasi memerlukan izin kamera untuk melakukan scan.")
        }
    }
}

private fun performCapture(
    context: android.content.Context,
    imageCapture: ImageCapture,
    onSuccess: (CompleteScanResult) -> Unit,
    onError: (CompleteScanResult) -> Unit
) {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val photoFile = File(context.cacheDir, "KTP_SCAN_${timestamp}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    val executor: Executor = ContextCompat.getMainExecutor(context)

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                Log.d("KtpScanner", "Gambar berhasil disimpan: ${photoFile.absolutePath}")

                try {
                    // Load bitmap dengan rotasi yang benar
                    val originalBitmap = loadBitmapWithCorrectRotation(photoFile.absolutePath)
                    Log.d("KtpScanner", "Original bitmap: ${originalBitmap.width}x${originalBitmap.height}")

                    // Crop ke area KTP
                    val croppedBitmap = cropBitmapToKtpArea(originalBitmap)
                    Log.d("KtpScanner", "Cropped bitmap: ${croppedBitmap.width}x${croppedBitmap.height}")

                    // Kirim ke Cloud Vision API di background thread
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val ocrText = CloudVisionHelper.recognizeText(croppedBitmap)

                            withContext(Dispatchers.Main) {
                                if (ocrText != null) {
                                    Log.d("KtpScanner", "=== CLOUD VISION RAW TEXT ===\n$ocrText")
                                    val parsedData = KtpParser.parse(ocrText)
                                    val imageUri = android.net.Uri.fromFile(photoFile)
                                    Log.d("KtpScanner", "Parsed: NIK=${parsedData.nik}, Nama=${parsedData.nama}, Alamat=${parsedData.alamat}")
                                    onSuccess(CompleteScanResult(parsedData, imageUri))
                                } else {
                                    // Cloud Vision gagal, fallback ke ML Kit on-device
                                    Log.w("KtpScanner", "Cloud Vision failed, falling back to ML Kit...")
                                    fallbackToMlKit(croppedBitmap, photoFile, onSuccess, onError)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("KtpScanner", "Cloud Vision error: ${e.message}", e)
                            withContext(Dispatchers.Main) {
                                // Fallback ke ML Kit jika Cloud Vision error (misal: tidak ada internet)
                                fallbackToMlKit(croppedBitmap, photoFile, onSuccess, onError)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("KtpScanner", "Error processing image: ${e.message}", e)
                    val errorData = KtpData(error = "Gagal memproses gambar: ${e.message}")
                    onError(CompleteScanResult(errorData, null))
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("KtpScanner", "Gagal mengambil gambar: ${exception.message}", exception)
                val errorData = KtpData(error = "Gagal mengambil gambar: ${exception.message}")
                onError(CompleteScanResult(errorData, null))
            }
        }
    )
}

/**
 * Fallback ke ML Kit on-device jika Cloud Vision tidak tersedia
 * (tidak ada internet, API error, dll)
 */
private fun fallbackToMlKit(
    croppedBitmap: Bitmap,
    photoFile: File,
    onSuccess: (CompleteScanResult) -> Unit,
    onError: (CompleteScanResult) -> Unit
) {
    Log.d("KtpScanner", "Using ML Kit fallback...")
    val image = InputImage.fromBitmap(croppedBitmap, 0)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    recognizer.process(image)
        .addOnSuccessListener { visionText ->
            Log.d("KtpScanner", "=== ML KIT FALLBACK RAW TEXT ===\n${visionText.text}")
            val parsedData = KtpParser.parse(visionText.text)
            val imageUri = android.net.Uri.fromFile(photoFile)
            Log.d("KtpScanner", "Fallback Parsed: NIK=${parsedData.nik}, Nama=${parsedData.nama}, Alamat=${parsedData.alamat}")
            onSuccess(CompleteScanResult(parsedData, imageUri))
        }
        .addOnFailureListener { e ->
            Log.e("KtpScanner", "ML Kit fallback juga gagal: ${e.message}")
            val errorData = KtpData(error = "Gagal mengenali teks: ${e.localizedMessage}")
            val imageUri = android.net.Uri.fromFile(photoFile)
            onError(CompleteScanResult(errorData, imageUri))
        }
}

/** Pilih NIK terbaik dari dua kandidat */
private fun pickBestNik(a: String?, b: String?): String? {
    val scoreA = scoreNikQuality(a)
    val scoreB = scoreNikQuality(b)
    return when {
        scoreA >= scoreB && scoreA > 0 -> a
        scoreB > 0 -> b
        a != null -> a
        else -> b
    }
}

private fun scoreNikQuality(nik: String?): Int {
    if (nik.isNullOrBlank()) return 0
    var score = 0
    if (nik.length == 16) score += 3
    if (nik.all { it.isDigit() }) score += 2
    return score
}

/** Pilih Nama terbaik: tolak yang mirip label KTP */
private fun pickBestNama(a: String?, b: String?): String? {
    val scoreA = scoreNamaQuality(a)
    val scoreB = scoreNamaQuality(b)
    return when {
        scoreA >= scoreB && scoreA > 0 -> a
        scoreB > 0 -> b
        a != null -> a
        else -> b
    }
}

private fun scoreNamaQuality(nama: String?): Int {
    if (nama.isNullOrBlank()) return 0
    val upper = nama.uppercase()
    // Skor rendah jika mirip label KTP
    val labelPrefixes = listOf(
        "ALAM", "PROVINS", "JAKART", "KECAM", "RTRW", "JENS", "JENIS",
        "KELAM", "AGAM", "PEKERJ", "STATUS", "BERLAK", "KEWARG", "KELDES", "KEVDES"
    )
    if (labelPrefixes.any { upper.startsWith(it) }) return 0
    var score = 1
    if (nama.length >= 3) score += 1
    // Nama biasanya 2+ kata
    if (nama.trim().split("\\s+".toRegex()).size >= 2) score += 2
    // Nama tidak mengandung angka
    if (!nama.any { it.isDigit() }) score += 1
    return score
}

/** Pilih Alamat terbaik: tolak yang mirip label/tanggal */
private fun pickBestAlamat(a: String?, b: String?): String? {
    val scoreA = scoreAlamatQuality(a)
    val scoreB = scoreAlamatQuality(b)
    return when {
        scoreA >= scoreB && scoreA > 0 -> a
        scoreB > 0 -> b
        a != null -> a
        else -> b
    }
}

private fun scoreAlamatQuality(alamat: String?): Int {
    if (alamat.isNullOrBlank()) return 0
    val upper = alamat.uppercase()
    // Skor 0 jika mirip label KTP atau tanggal
    val labelPrefixes = listOf(
        "JENS", "JENIS", "KELAM", "AGAM", "STATUS", "PEKERJ",
        "BERLAK", "KEWARG", "PROVINS", "RTRWE", "KEVDES", "KELDES"
    )
    if (labelPrefixes.any { upper.startsWith(it) }) return 0
    // Skor 0 jika hanya tanggal
    if (upper.matches("^-?\\d{1,2}-\\d{1,4}.*".toRegex())) return 0

    var score = 1
    if (alamat.length >= 10) score += 1
    // Alamat biasanya mengandung keyword
    val addrKeywords = listOf("JL", "JALAN", "GG", "GANG", "BLOK", "MANGGAR", "RT", "RW", "NO")
    if (addrKeywords.any { upper.contains(it) }) score += 3
    return score
}

/**
 * Load bitmap dan rotate sesuai EXIF orientation
 */
private fun loadBitmapWithCorrectRotation(filePath: String): Bitmap {
    val bitmap = BitmapFactory.decodeFile(filePath)

    return try {
        val exif = ExifInterface(filePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (rotationDegrees != 0f) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    } catch (e: Exception) {
        Log.e("KtpScanner", "Error reading EXIF: ${e.message}")
        bitmap
    }
}

/**
 * Crop bitmap ke area KTP berdasarkan rasio bingkai
 * Menggunakan pendekatan berbasis RASIO, bukan koordinat absolut
 */
private fun cropBitmapToKtpArea(source: Bitmap): Bitmap {
    val sourceWidth = source.width
    val sourceHeight = source.height

    Log.d("KtpScanner", "Cropping from source: ${sourceWidth}x${sourceHeight}")

    // Rasio bingkai sama dengan yang ditampilkan di overlay
    // Frame width = 85% dari lebar layar
    // Frame height = frameWidth / 1.586 (rasio KTP)
    // Frame top = 30% dari tinggi layar

    val frameWidthRatio = 0.85f
    val ktpAspectRatio = 1.586f
    val frameTopRatio = 0.30f

    // Hitung dimensi crop berdasarkan orientasi gambar
    // Gambar dari kamera biasanya landscape, tapi setelah rotasi bisa portrait
    val isPortrait = sourceHeight > sourceWidth

    val cropWidth: Int
    val cropHeight: Int
    val cropLeft: Int
    val cropTop: Int

    if (isPortrait) {
        // Gambar portrait (seperti yang dilihat user di preview)
        cropWidth = (sourceWidth * frameWidthRatio).toInt()
        cropHeight = (cropWidth / ktpAspectRatio).toInt()
        cropLeft = ((sourceWidth - cropWidth) / 2f).toInt()
        cropTop = (sourceHeight * frameTopRatio).toInt()
    } else {
        // Gambar landscape - perlu adjustment
        // Preview menggunakan FILL_CENTER, jadi ada cropping
        // Kita perlu menghitung ulang posisi relatif

        // Asumsikan preview menampilkan bagian tengah dari gambar landscape
        val previewAspectRatio = 9f / 16f // Rasio layar HP portrait
        val imageAspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()

        if (imageAspectRatio > 1f / previewAspectRatio) {
            // Gambar lebih lebar dari preview, ada horizontal crop di preview
            val visibleWidth = sourceHeight / previewAspectRatio
            val horizontalCrop = (sourceWidth - visibleWidth) / 2f

            cropWidth = (visibleWidth * frameWidthRatio).toInt()
            cropHeight = (cropWidth / ktpAspectRatio).toInt()
            cropLeft = (horizontalCrop + (visibleWidth - cropWidth) / 2f).toInt()
            cropTop = (sourceHeight * frameTopRatio).toInt()
        } else {
            // Gambar lebih tinggi dari preview
            cropWidth = (sourceWidth * frameWidthRatio).toInt()
            cropHeight = (cropWidth / ktpAspectRatio).toInt()
            cropLeft = ((sourceWidth - cropWidth) / 2f).toInt()
            cropTop = (sourceHeight * frameTopRatio).toInt()
        }
    }

    // Safety bounds dengan margin ekstra untuk toleransi
    val margin = 20 // pixel margin untuk toleransi
    val safeLeft = (cropLeft - margin).coerceIn(0, sourceWidth - 1)
    val safeTop = (cropTop - margin).coerceIn(0, sourceHeight - 1)
    val safeWidth = (cropWidth + margin * 2).coerceAtMost(sourceWidth - safeLeft)
    val safeHeight = (cropHeight + margin * 2).coerceAtMost(sourceHeight - safeTop)

    Log.d("KtpScanner", "Crop area: left=$safeLeft, top=$safeTop, width=$safeWidth, height=$safeHeight")

    return try {
        if (safeWidth > 100 && safeHeight > 100) {
            Bitmap.createBitmap(source, safeLeft, safeTop, safeWidth, safeHeight)
        } else {
            Log.w("KtpScanner", "Crop area too small, using center crop fallback")
            // Fallback: ambil bagian tengah gambar dengan rasio KTP
            centerCropToKtpRatio(source)
        }
    } catch (e: Exception) {
        Log.e("KtpScanner", "Crop failed: ${e.message}, using fallback")
        centerCropToKtpRatio(source)
    }
}

/**
 * Fallback: Center crop dengan rasio KTP
 */
private fun centerCropToKtpRatio(source: Bitmap): Bitmap {
    val sourceWidth = source.width
    val sourceHeight = source.height
    val ktpAspectRatio = 1.586f

    // Hitung dimensi terbesar yang muat dengan rasio KTP
    val targetWidth: Int
    val targetHeight: Int

    if (sourceWidth.toFloat() / sourceHeight > ktpAspectRatio) {
        // Source lebih lebar, batasi berdasarkan height
        targetHeight = (sourceHeight * 0.6f).toInt() // 60% dari height
        targetWidth = (targetHeight * ktpAspectRatio).toInt()
    } else {
        // Source lebih tinggi, batasi berdasarkan width
        targetWidth = (sourceWidth * 0.85f).toInt()
        targetHeight = (targetWidth / ktpAspectRatio).toInt()
    }

    val left = (sourceWidth - targetWidth) / 2
    val top = (sourceHeight - targetHeight) / 2

    return Bitmap.createBitmap(source, left, top, targetWidth, targetHeight)
}

/**
 * Hitung brightness rata-rata dan variance dari ImageProxy
 * Return: Pair(averageBrightness, variance)
 */
private fun analyzeImageBrightness(imageProxy: androidx.camera.core.ImageProxy): Pair<Float, Float> {
    val buffer = imageProxy.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    // Hitung mean
    var sum = 0L
    val sampleStep = 15
    var count = 0
    for (i in bytes.indices step sampleStep) {
        sum += (bytes[i].toInt() and 0xFF)
        count++
    }
    val mean = sum.toFloat() / count

    // Hitung variance
    buffer.rewind()
    buffer.get(bytes)
    var varianceSum = 0.0
    for (i in bytes.indices step sampleStep) {
        val value = (bytes[i].toInt() and 0xFF).toFloat()
        varianceSum += (value - mean) * (value - mean)
    }
    val variance = (varianceSum / count).toFloat()

    return Pair(mean, variance)
}

/**
 * Hitung variance brightness untuk deteksi apakah ada konten/objek
 * Variance rendah = gambar solid (ditutup/kosong)
 * Variance tinggi = ada objek dengan detail (seperti KTP)
 */
private fun calculateBrightnessVariance(imageProxy: androidx.camera.core.ImageProxy): Float {
    val buffer = imageProxy.planes[0].buffer.duplicate() // duplicate agar tidak consume buffer asli
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    // Hitung mean dulu
    var sum = 0L
    val sampleStep = 20 // Sample lebih jarang untuk performa
    var count = 0
    for (i in bytes.indices step sampleStep) {
        sum += (bytes[i].toInt() and 0xFF)
        count++
    }
    val mean = sum.toFloat() / count

    // Hitung variance
    var varianceSum = 0.0
    for (i in bytes.indices step sampleStep) {
        val value = (bytes[i].toInt() and 0xFF).toFloat()
        varianceSum += (value - mean) * (value - mean)
    }

    return (varianceSum / count).toFloat()
}

/**
 * Convert ImageProxy ke grayscale Bitmap menggunakan Y plane
 * TIDAK menggunakan imageProxy.image (menghindari ExperimentalGetImage)
 *
 * PENTING: buffer.rewind() WAJIB dipanggil karena analyzeImageBrightness()
 * sudah membaca buffer sebelum fungsi ini dipanggil, sehingga posisi buffer
 * sudah di akhir (remaining = 0).
 */
private fun imageProxyToBitmap(imageProxy: androidx.camera.core.ImageProxy): Bitmap? {
    return try {
        val plane = imageProxy.planes[0]
        val yBuffer = plane.buffer
        yBuffer.rewind() // KRITIS: reset posisi buffer ke awal

        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride

        val yBytes = ByteArray(yBuffer.remaining())
        yBuffer.get(yBytes)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val y = yBytes[row * rowStride + col].toInt() and 0xFF
                pixels[row * width + col] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap
    } catch (e: Exception) {
        Log.e("KtpScanner", "Error converting ImageProxy to Bitmap: ${e.message}")
        null
    }
}