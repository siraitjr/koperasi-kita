package com.example.koperasikitagodangulu

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.key

@Composable
fun CameraScreen(onTextFound: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED)
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "Izin kamera diperlukan", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Izin kamera diperlukan")
        }
        return
    }

    val previewView = remember { PreviewView(context) }
    var isFrontCamera by remember { mutableStateOf(false) }

    key(isFrontCamera) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        ) {
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also { preview ->
                preview.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector =
                if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageCapture
                )
            } catch (e: Exception) {
                Log.e("CameraScreen", "Gagal mengikat kamera", e)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Button(
            onClick = {
                val photoFile = File(context.cacheDir, "ktp.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                            recognizeTextFromImage(bitmap) { text ->
                                onTextFound(text)
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Toast.makeText(context, "Gagal ambil foto: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            },
            modifier = Modifier.padding(24.dp)
        ) {
            Text("Scan KTP")
        }
    }
}

// NOTE: Fungsi preprocessImageForOCR sudah dipindahkan ke ImagePreprocessing_Enhanced.kt
// untuk menghindari duplicate definition

fun recognizeTextFromImage(bitmap: Bitmap, onResult: (String) -> Unit) {
    // Menggunakan preprocessImageForOCR dari ImagePreprocessing_Enhanced.kt
    val processedBitmap = preprocessImageForOCR(bitmap)
    val image = InputImage.fromBitmap(processedBitmap, 0)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    recognizer.process(image)
        .addOnSuccessListener { visionText ->
            Log.d("OCR", "OCR Result: ${visionText.text}")

            // Gunakan KtpParser untuk mem-parsing teks
            val parsedData = KtpParser.parse(visionText.text)

            // Format hasil untuk ditampilkan
            val displayText = if (parsedData.error.isNullOrEmpty()) {
                "NIK: ${parsedData.nik}\nNama: ${parsedData.nama}\nAlamat: ${parsedData.alamat}"
            } else {
                "Gagal mengenali KTP:\n${parsedData.error}"
            }

            onResult(displayText)
        }
        .addOnFailureListener { e ->
            Log.e("OCR", "Gagal mengenali teks: ${e.message}")
            onResult("Gagal mengenali teks: ${e.localizedMessage}")
        }
}

// Fungsi lama untuk kompatibilitas (jika masih digunakan di tempat lain)
fun parseKtpDataToText(text: String): String {
    val lines = text.lines()
    var nik = ""
    var nama = ""

    for (line in lines) {
        when {
            line.contains("NIK", ignoreCase = true) ->
                nik = line.replace(Regex(".*?NIK\\s*[:]?\\s*"), "").trim()
            line.contains("Nama", ignoreCase = true) ->
                nama = line.replace(Regex(".*?Nama\\s*[:]?\\s*"), "").trim()
        }
    }

    return if (nik.isNotEmpty() && nama.isNotEmpty()) {
        "NIK: $nik\nNama: $nama"
    } else {
        "Data KTP tidak ditemukan\n$text"
    }
}