package com.example.koperasikitagodangulu.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.ktx.storage
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * =========================================================================
 * SYNC MANAGER V4 - WITH PHOTO UPLOAD SUPPORT!
 * =========================================================================
 *
 * PERUBAHAN DARI V3:
 *   - Ditambahkan kemampuan upload foto KTP saat sync
 *   - Foto yang pending akan otomatis di-upload saat online
 *   - Tidak bergantung pada ViewModel (bisa jalan di background service)
 *
 * ALUR:
 *   User Input → Room DB → Sync Data JSON → Upload Foto → Update Firebase
 * =========================================================================
 */
class SyncManager private constructor(private val context: Context) {

    private val db = PendingOperationDatabase.getInstance(context)
    private val dao = db.pendingOperationDao()
    private val firebase = FirebaseDatabase.getInstance("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val storage = Firebase.storage
    private val gson = Gson()

    companion object {
        private const val TAG = "SyncManager"
        private const val MAX_RETRY = 5

        @Volatile
        private var INSTANCE: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return INSTANCE ?: synchronized(this) {
                Log.d(TAG, "🔧 Creating new SyncManager instance")
                val instance = SyncManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    // =========================================================================
    // ROOM-FIRST WRITE OPERATIONS
    // =========================================================================

    suspend fun savePelangganDirect(
        adminUid: String,
        pelangganId: String,
        pelangganData: Map<String, Any?>
    ): SaveResult = withContext(Dispatchers.IO) {
        val path = "pelanggan/$adminUid/$pelangganId"

        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 savePelangganDirect() CALLED!")
        Log.d(TAG, "   adminUid: $adminUid")
        Log.d(TAG, "   pelangganId: $pelangganId")
        Log.d(TAG, "   dataSize: ${pelangganData.size} fields")
        Log.d(TAG, "========================================")

        try {
            // ✅ STEP 1: SELALU simpan ke Room DB DULU!
            Log.d(TAG, "💾 [STEP 1] Preparing to save to Room DB...")

            val operation = PendingOperation(
                operationType = "ADD_PELANGGAN",
                firebasePath = path,
                dataJson = gson.toJson(pelangganData),
                adminUid = adminUid,
                pelangganId = pelangganId,
                status = "PENDING"
            )

            Log.d(TAG, "💾 [STEP 1] Inserting to Room DB...")
            val operationId = dao.insert(operation)
            Log.d(TAG, "💾 [STEP 1] ✅ SAVED TO ROOM DB! opId=$operationId")

            // ✅ STEP 2: Coba sync ke Firebase (jika online)
            val online = isOnline()
            Log.d(TAG, "🌐 [STEP 2] Checking network: online=$online")

            if (online) {
                try {
                    Log.d(TAG, "🌐 [STEP 2] Online, syncing to Firebase...")
                    dao.updateStatus(operationId, "SYNCING")

                    // Upload foto dulu jika ada pending
                    val updatedData = uploadPendingPhotosForData(adminUid, pelangganId, pelangganData)

                    firebase.getReference(path).setValue(updatedData).await()

                    dao.updateStatus(operationId, "SUCCESS")
                    Log.d(TAG, "✅ [STEP 3] SYNCED TO FIREBASE!")

                    SaveResult.Success

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Firebase sync failed: ${e.message}")
                    dao.updateStatus(operationId, "PENDING", e.message)

                    Log.d(TAG, "🚀 Starting SyncForegroundService...")
                    SyncForegroundService.startSync(context)
                    SyncWorker.triggerImmediateSync(context)

                    SaveResult.Queued
                }
            } else {
                Log.d(TAG, "📵 OFFLINE! Data saved to Room DB.")
                Log.d(TAG, "🚀 Starting SyncForegroundService for later sync...")
                SyncForegroundService.startSync(context)

                // ✅ CRITICAL: Enqueue WorkManager SEKARANG!
                // WorkManager akan TETAP JALAN meskipun app di-swipe close
                // karena dikelola oleh sistem Android, bukan app
                Log.d(TAG, "📋 Enqueueing WorkManager for background sync...")
                SyncWorker.triggerImmediateSync(context)

                SaveResult.Queued
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ CRITICAL ERROR in savePelangganDirect: ${e.message}")
            e.printStackTrace()
            SaveResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun savePembayaranDirect(
        adminUid: String,
        pelangganId: String,
        pembayaranIndex: Int,
        pembayaranData: Map<String, Any?>
    ): SaveResult = withContext(Dispatchers.IO) {
        val path = "pelanggan/$adminUid/$pelangganId/pembayaranList/$pembayaranIndex"

        Log.d(TAG, "🚀 savePembayaranDirect() CALLED!")

        try {
            val operation = PendingOperation(
                operationType = "ADD_PEMBAYARAN",
                firebasePath = path,
                dataJson = gson.toJson(pembayaranData),
                adminUid = adminUid,
                pelangganId = pelangganId,
                status = "PENDING"
            )
            val operationId = dao.insert(operation)
            Log.d(TAG, "💰 [STEP 1] ✅ PEMBAYARAN SAVED TO ROOM DB! opId=$operationId")

            if (isOnline()) {
                try {
                    dao.updateStatus(operationId, "SYNCING")
                    firebase.getReference(path).setValue(pembayaranData).await()
                    dao.updateStatus(operationId, "SUCCESS")
                    Log.d(TAG, "✅ PEMBAYARAN SYNCED TO FIREBASE!")
                    SaveResult.Success
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Firebase sync failed: ${e.message}")
                    dao.updateStatus(operationId, "PENDING", e.message)
                    SyncForegroundService.startSync(context)
                    SyncWorker.triggerImmediateSync(context)
                    SaveResult.Queued
                }
            } else {
                Log.d(TAG, "📵 OFFLINE! Pembayaran saved to Room DB.")
                SyncForegroundService.startSync(context)

                // ✅ CRITICAL: Enqueue WorkManager untuk background sync
                Log.d(TAG, "📋 Enqueueing WorkManager for background sync...")
                SyncWorker.triggerImmediateSync(context)

                SaveResult.Queued
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ CRITICAL ERROR: ${e.message}")
            SaveResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun saveSubPembayaranDirect(
        adminUid: String,
        pelangganId: String,
        pembayaranIndex: Int,
        subIndex: Int,
        subPembayaranData: Map<String, Any?>
    ): SaveResult = withContext(Dispatchers.IO) {
        val path = "pelanggan/$adminUid/$pelangganId/pembayaranList/$pembayaranIndex/subPembayaran/$subIndex"

        Log.d(TAG, "🚀 saveSubPembayaranDirect() CALLED!")

        try {
            val operation = PendingOperation(
                operationType = "ADD_SUB_PEMBAYARAN",
                firebasePath = path,
                dataJson = gson.toJson(subPembayaranData),
                adminUid = adminUid,
                pelangganId = pelangganId,
                status = "PENDING"
            )
            val operationId = dao.insert(operation)
            Log.d(TAG, "💰 [STEP 1] ✅ SUB-PEMBAYARAN SAVED TO ROOM DB! opId=$operationId")

            if (isOnline()) {
                try {
                    dao.updateStatus(operationId, "SYNCING")
                    firebase.getReference(path).setValue(subPembayaranData).await()
                    dao.updateStatus(operationId, "SUCCESS")
                    Log.d(TAG, "✅ SUB-PEMBAYARAN SYNCED!")
                    SaveResult.Success
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Firebase sync failed: ${e.message}")
                    dao.updateStatus(operationId, "PENDING", e.message)
                    SyncForegroundService.startSync(context)
                    SyncWorker.triggerImmediateSync(context)
                    SaveResult.Queued
                }
            } else {
                Log.d(TAG, "📵 OFFLINE! Sub-pembayaran saved to Room DB.")
                SyncForegroundService.startSync(context)

                // ✅ CRITICAL: Enqueue WorkManager untuk background sync
                Log.d(TAG, "📋 Enqueueing WorkManager for background sync...")
                SyncWorker.triggerImmediateSync(context)

                SaveResult.Queued
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ CRITICAL ERROR: ${e.message}")
            SaveResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun updatePelangganDirect(
        adminUid: String,
        pelangganId: String,
        updateData: Map<String, Any?>
    ): SaveResult = withContext(Dispatchers.IO) {
        val path = "pelanggan/$adminUid/$pelangganId"

        Log.d(TAG, "🚀 updatePelangganDirect() CALLED!")

        try {
            val operation = PendingOperation(
                operationType = "UPDATE_PELANGGAN",
                firebasePath = path,
                dataJson = gson.toJson(updateData),
                adminUid = adminUid,
                pelangganId = pelangganId,
                status = "PENDING"
            )
            val operationId = dao.insert(operation)
            Log.d(TAG, "📝 [STEP 1] ✅ UPDATE SAVED TO ROOM DB! opId=$operationId")

            if (isOnline()) {
                try {
                    dao.updateStatus(operationId, "SYNCING")
                    firebase.getReference(path).updateChildren(updateData).await()
                    dao.updateStatus(operationId, "SUCCESS")
                    Log.d(TAG, "✅ UPDATE SYNCED!")
                    SaveResult.Success
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Firebase update failed: ${e.message}")
                    dao.updateStatus(operationId, "PENDING", e.message)
                    SyncForegroundService.startSync(context)
                    SaveResult.Queued
                }
            } else {
                Log.d(TAG, "📵 OFFLINE! Update saved to Room DB.")
                SyncForegroundService.startSync(context)
                SyncWorker.triggerImmediateSync(context)
                SaveResult.Queued
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ CRITICAL ERROR: ${e.message}")
            SaveResult.Error(e.message ?: "Unknown error")
        }
    }

    // =========================================================================
    // PHOTO UPLOAD FUNCTIONS
    // =========================================================================

    /**
     * Upload foto pending untuk data pelanggan
     * Dipanggil saat sync data ke Firebase
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun uploadPendingPhotosForData(
        adminUid: String,
        pelangganId: String,
        data: Map<String, Any?>
    ): Map<String, Any?> {
        val mutableData = data.toMutableMap()

        try {
            // Cek dan upload foto KTP utama
            val pendingKtpUri = data["pendingFotoKtpUri"] as? String ?: ""
            val currentKtpUrl = data["fotoKtpUrl"] as? String ?: ""

            if (pendingKtpUri.isNotBlank() && currentKtpUrl.isBlank()) {
                Log.d(TAG, "📷 Uploading pending foto KTP...")
                val uploadedUrl = uploadFotoKtp(Uri.parse(pendingKtpUri), adminUid, pelangganId, "utama")
                if (uploadedUrl != null) {
                    mutableData["fotoKtpUrl"] = uploadedUrl
                    mutableData["pendingFotoKtpUri"] = ""
                    Log.d(TAG, "✅ Foto KTP uploaded: $uploadedUrl")
                }
            }

            // Cek dan upload foto KTP Suami
            val pendingKtpSuamiUri = data["pendingFotoKtpSuamiUri"] as? String ?: ""
            val currentKtpSuamiUrl = data["fotoKtpSuamiUrl"] as? String ?: ""

            if (pendingKtpSuamiUri.isNotBlank() && currentKtpSuamiUrl.isBlank()) {
                Log.d(TAG, "📷 Uploading pending foto KTP Suami...")
                val uploadedUrl = uploadFotoKtp(Uri.parse(pendingKtpSuamiUri), adminUid, pelangganId, "suami")
                if (uploadedUrl != null) {
                    mutableData["fotoKtpSuamiUrl"] = uploadedUrl
                    mutableData["pendingFotoKtpSuamiUri"] = ""
                    Log.d(TAG, "✅ Foto KTP Suami uploaded: $uploadedUrl")
                }
            }

            // Cek dan upload foto KTP Istri
            val pendingKtpIstriUri = data["pendingFotoKtpIstriUri"] as? String ?: ""
            val currentKtpIstriUrl = data["fotoKtpIstriUrl"] as? String ?: ""

            if (pendingKtpIstriUri.isNotBlank() && currentKtpIstriUrl.isBlank()) {
                Log.d(TAG, "📷 Uploading pending foto KTP Istri...")
                val uploadedUrl = uploadFotoKtp(Uri.parse(pendingKtpIstriUri), adminUid, pelangganId, "istri")
                if (uploadedUrl != null) {
                    mutableData["fotoKtpIstriUrl"] = uploadedUrl
                    mutableData["pendingFotoKtpIstriUri"] = ""
                    Log.d(TAG, "✅ Foto KTP Istri uploaded: $uploadedUrl")
                }
            }

            // ✅ BARU: Cek dan upload foto Nasabah
            val pendingNasabahUri = data["pendingFotoNasabahUri"] as? String ?: ""
            val currentNasabahUrl = data["fotoNasabahUrl"] as? String ?: ""

            if (pendingNasabahUri.isNotBlank() && currentNasabahUrl.isBlank()) {
                Log.d(TAG, "📷 Uploading pending foto Nasabah...")
                val uploadedUrl = uploadFotoKtp(Uri.parse(pendingNasabahUri), adminUid, pelangganId, "nasabah")
                if (uploadedUrl != null) {
                    mutableData["fotoNasabahUrl"] = uploadedUrl
                    mutableData["pendingFotoNasabahUri"] = ""
                    Log.d(TAG, "✅ Foto Nasabah uploaded: $uploadedUrl")
                }
            }

            // ✅ BARU: Cek dan upload foto Serah Terima
            val pendingSerahTerimaUri = data["pendingFotoSerahTerimaUri"] as? String ?: ""
            val currentSerahTerimaUrl = data["fotoSerahTerimaUrl"] as? String ?: ""

            if (pendingSerahTerimaUri.isNotBlank() && currentSerahTerimaUrl.isBlank()) {
                Log.d(TAG, "📷 Uploading pending foto Serah Terima...")
                val uploadedUrl = uploadFotoKtp(Uri.parse(pendingSerahTerimaUri), adminUid, pelangganId, "serah_terima")
                if (uploadedUrl != null) {
                    mutableData["fotoSerahTerimaUrl"] = uploadedUrl
                    mutableData["pendingFotoSerahTerimaUri"] = ""
                    mutableData["statusSerahTerima"] = "Selesai"
                    Log.d(TAG, "✅ Foto Serah Terima uploaded: $uploadedUrl")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error uploading photos: ${e.message}")
        }

        return mutableData
    }

    /**
     * Upload single foto KTP ke Firebase Storage
     */
    private suspend fun uploadFotoKtp(
        imageUri: Uri,
        adminUid: String,
        pelangganId: String,
        jenisKtp: String = "utama"
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "📷 uploadFotoKtp: $jenisKtp for $pelangganId")

                val storageRef = storage.reference
                val ktpRef = storageRef.child("ktp_images/$adminUid/$pelangganId/ktp_$jenisKtp.jpg")

                // Kompresi gambar
                val compressedImage = compressImageForKtp(imageUri)
                if (compressedImage.isEmpty()) {
                    Log.e(TAG, "❌ Gagal kompresi gambar")
                    return@withContext null
                }

                // Validasi ukuran
                if (compressedImage.size > 700 * 1024) { // Dinaikkan dari 500KB ke 700KB
                    Log.e(TAG, "❌ Gambar terlalu besar: ${compressedImage.size / 1024}KB (max 700KB)")
                    return@withContext null
                }

                val metadata = StorageMetadata.Builder()
                    .setCustomMetadata("adminUid", adminUid)
                    .setCustomMetadata("pelangganId", pelangganId)
                    .setCustomMetadata("uploadedAt", System.currentTimeMillis().toString())
                    .setContentType("image/jpeg")
                    .build()

                val uploadTask = ktpRef.putBytes(compressedImage, metadata)
                val task = uploadTask.await()

                if (task.task.isSuccessful) {
                    val downloadUrl = ktpRef.downloadUrl.await()
                    Log.d(TAG, "✅ Foto KTP uploaded: ${compressedImage.size / 1024}KB → $downloadUrl")
                    downloadUrl.toString()
                } else {
                    Log.e(TAG, "❌ Upload gagal: ${task.task.exception?.message}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception upload foto KTP: ${e.message}")
                null
            }
        }
    }

    /**
     * Kompresi gambar untuk KTP
     */
    private fun compressImageForKtp(uri: Uri): ByteArray {
        return try {
            var inputStream: InputStream? = null

            try {
                inputStream = context.contentResolver.openInputStream(uri)

                if (inputStream == null) {
                    Log.e(TAG, "❌ Tidak bisa membuka input stream")
                    return ByteArray(0)
                }

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream.close()

                // Hitung sample size optimal
                val targetWidth = 1200
                val targetHeight = 800
                options.inSampleSize = calculateOptimalSampleSize(
                    options.outWidth,
                    options.outHeight,
                    targetWidth,
                    targetHeight
                )

                // Dekode bitmap
                options.inJustDecodeBounds = false
                inputStream = context.contentResolver.openInputStream(uri)

                if (inputStream == null) {
                    Log.e(TAG, "❌ Tidak bisa membuka input stream kedua")
                    return ByteArray(0)
                }

                var bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                inputStream.close()

                if (bitmap == null) {
                    Log.e(TAG, "❌ Gagal decode bitmap")
                    return ByteArray(0)
                }

                // Perbaiki orientasi
                bitmap = rotateBitmapIfRequired(bitmap, uri)

                // Kompresi
                val outputStream = ByteArrayOutputStream()
                var quality = 85
                val maxFileSize = 200 * 1024

                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

                if (outputStream.size() > maxFileSize) {
                    outputStream.reset()
                    quality = 75
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                }

                if (outputStream.size() > maxFileSize) {
                    outputStream.reset()
                    quality = 65
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                }

                val compressedBytes = outputStream.toByteArray()
                outputStream.close()
                bitmap.recycle()

                Log.d(TAG, "✅ Kompresi berhasil: ${compressedBytes.size / 1024} KB")
                compressedBytes

            } finally {
                inputStream?.close()
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error kompresi: ${e.message}")
            ByteArray(0)
        }
    }

    private fun calculateOptimalSampleSize(
        actualWidth: Int,
        actualHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sampleSize = 1
        if (actualWidth > targetWidth || actualHeight > targetHeight) {
            val widthRatio = actualWidth.toFloat() / targetWidth.toFloat()
            val heightRatio = actualHeight.toFloat() / targetHeight.toFloat()
            sampleSize = if (widthRatio > heightRatio) widthRatio.toInt() else heightRatio.toInt()
        }
        return if (sampleSize < 1) 1 else sampleSize
    }

    private fun rotateBitmapIfRequired(bitmap: Bitmap, uri: Uri): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()

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
            Log.e(TAG, "⚠️ Error rotate bitmap: ${e.message}")
            bitmap
        }
    }

    // =========================================================================
    // SYNC LOGIC
    // =========================================================================

    @Suppress("UNCHECKED_CAST")
    private suspend fun trySyncOperation(operation: PendingOperation): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🔄 Syncing operation: ${operation.operationType}")
                dao.updateStatus(operation.id, "SYNCING")

                val ref = firebase.getReference(operation.firebasePath)

                var data: Any = try {
                    gson.fromJson(operation.dataJson, Map::class.java)
                } catch (e: Exception) {
                    operation.dataJson
                }

                // ✅ TAMBAHAN: Upload foto pending untuk ADD_PELANGGAN
                if (operation.operationType == "ADD_PELANGGAN" && data is Map<*, *>) {
                    val dataMap = data as Map<String, Any?>
                    data = uploadPendingPhotosForData(
                        operation.adminUid,
                        operation.pelangganId ?: "",
                        dataMap
                    )
                }

                when (operation.operationType) {
                    "ADD_PELANGGAN", "ADD_PEMBAYARAN", "ADD_SUB_PEMBAYARAN" -> {
                        ref.setValue(data).await()
                    }
                    "UPDATE_PELANGGAN" -> {
                        ref.updateChildren(data as Map<String, Any?>).await()
                    }
                    else -> {
                        ref.setValue(data).await()
                    }
                }

                dao.updateStatus(operation.id, "SUCCESS")
                Log.d(TAG, "✅ Synced: ${operation.operationType}")
                true

            } catch (e: Exception) {
                Log.e(TAG, "❌ Sync failed: ${e.message}")

                val newRetryCount = operation.retryCount + 1
                if (newRetryCount >= MAX_RETRY) {
                    dao.updateStatus(operation.id, "FAILED", e.message)
                } else {
                    dao.updateStatus(operation.id, "PENDING", e.message)
                }
                false
            }
        }
    }

    suspend fun syncAllPending(): SyncResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "🔄 syncAllPending() called")

            // Recovery: reset SYNCING yang stuck (mis. proses sebelumnya crash) → PENDING
            val resetCount = dao.resetStuckSyncing()
            if (resetCount > 0) Log.w(TAG, "♻️ Reset $resetCount stuck SYNCING → PENDING")

            val pending = dao.getPendingOperations()
            Log.d(TAG, "📦 Found ${pending.size} pending operations")

            if (pending.isEmpty()) {
                Log.d(TAG, "📭 No pending operations to sync")
                return@withContext SyncResult(0, 0, 0)
            }

            var success = 0
            var failed = 0

            for (operation in pending) {
                if (!isOnline()) {
                    Log.d(TAG, "📵 Lost connection, stopping sync")
                    break
                }

                val synced = trySyncOperation(operation)
                if (synced) success++ else failed++
                delay(100)
            }

            Log.d(TAG, "✅ Sync complete: $success success, $failed failed")

            dao.deleteOldSuccessful(System.currentTimeMillis() - 24 * 60 * 60 * 1000)

            SyncResult(pending.size, success, failed)
        }
    }

    // =========================================================================
    // STATUS & UTILITIES
    // =========================================================================

    suspend fun getPendingCount(): Int {
        val count = dao.getPendingCount()
        Log.d(TAG, "📊 getPendingCount() = $count")
        return count
    }

    fun observePendingCount(): Flow<Int> = dao.getPendingCountFlow()

    suspend fun getAllOperations(): List<PendingOperation> = dao.getAllOperations()

    suspend fun cleanupSuccessful() {
        dao.deleteSuccessful()
    }

    suspend fun retryAllFailed() {
        val failed = dao.getPendingOperations().filter { it.status == "FAILED" }
        Log.d(TAG, "🔄 Retrying ${failed.size} failed operations")
        for (op in failed) {
            dao.updateStatus(op.id, "PENDING", null)
        }

        if (failed.isNotEmpty()) {
            SyncForegroundService.startSync(context)
        }
    }

    fun isOnline(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking network: ${e.message}")
            false
        }
    }
}

sealed class SaveResult {
    object Success : SaveResult()
    object Queued : SaveResult()
    data class Error(val message: String) : SaveResult()
}

data class SyncResult(
    val total: Int,
    val success: Int,
    val failed: Int
) {
    val allSuccess: Boolean get() = total == success
}