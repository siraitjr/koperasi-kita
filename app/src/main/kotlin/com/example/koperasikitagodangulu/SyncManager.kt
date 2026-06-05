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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.ktx.storage
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    // Dispatcher notifikasi serah terima (background-safe). Dipakai saat operasi
    // SERAH_TERIMA tersinkron supaya atasan tetap dinotifikasi walau pencairan
    // dilakukan saat offline — logic & payload identik dengan jalur online.
    private val serahTerimaNotifier by lazy { SerahTerimaNotifier(firebase) }

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
        // pembayaranIndex tetap disimpan di Room untuk kebutuhan audit trail,
        // tapi tidak dipakai sebagai target path saat sync — transaction append-only
        // pakai panjang list server agar tidak overwrite entry lain (fix collision index).
        val auditPath = "pelanggan/$adminUid/$pelangganId/pembayaranList/$pembayaranIndex"
        val parentPath = "pelanggan/$adminUid/$pelangganId/pembayaranList"

        // clientOpId unik per operasi: dipakai sebagai kunci idempotency di
        // appendToArrayTransactional. UUID membedakan split-entry yang secara
        // bisnis (jumlah+tanggal+keterangan) identik tapi memang 2 transaksi
        // yang berbeda — sekaligus tetap men-dedup retry operasi yang sama
        // (payload tersimpan di Room, clientOpId ikut di dataJson).
        val enrichedData = if (pembayaranData.containsKey("clientOpId")) {
            pembayaranData
        } else {
            pembayaranData + ("clientOpId" to UUID.randomUUID().toString())
        }

        Log.d(TAG, "🚀 savePembayaranDirect() CALLED!")

        try {
            val operation = PendingOperation(
                operationType = "ADD_PEMBAYARAN",
                firebasePath = auditPath,
                dataJson = gson.toJson(enrichedData),
                adminUid = adminUid,
                pelangganId = pelangganId,
                status = "PENDING"
            )
            val operationId = dao.insert(operation)
            Log.d(TAG, "💰 [STEP 1] ✅ PEMBAYARAN SAVED TO ROOM DB! opId=$operationId")

            if (isOnline()) {
                try {
                    dao.updateStatus(operationId, "SYNCING")
                    appendToArrayTransactional(parentPath, enrichedData)
                    dao.updateStatus(operationId, "SUCCESS")
                    Log.d(TAG, "✅ PEMBAYARAN SYNCED TO FIREBASE (transactional append)!")
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
        // subIndex disimpan di Room untuk audit, tapi sync pakai transaction append-only
        // pada parent `subPembayaran` agar tidak overwrite sub lain (fix collision index).
        val auditPath = "pelanggan/$adminUid/$pelangganId/pembayaranList/$pembayaranIndex/subPembayaran/$subIndex"
        val parentPath = "pelanggan/$adminUid/$pelangganId/pembayaranList/$pembayaranIndex/subPembayaran"

        val enrichedData = if (subPembayaranData.containsKey("clientOpId")) {
            subPembayaranData
        } else {
            subPembayaranData + ("clientOpId" to UUID.randomUUID().toString())
        }

        Log.d(TAG, "🚀 saveSubPembayaranDirect() CALLED!")

        try {
            val operation = PendingOperation(
                operationType = "ADD_SUB_PEMBAYARAN",
                firebasePath = auditPath,
                dataJson = gson.toJson(enrichedData),
                adminUid = adminUid,
                pelangganId = pelangganId,
                status = "PENDING"
            )
            val operationId = dao.insert(operation)
            Log.d(TAG, "💰 [STEP 1] ✅ SUB-PEMBAYARAN SAVED TO ROOM DB! opId=$operationId")

            if (isOnline()) {
                try {
                    dao.updateStatus(operationId, "SYNCING")
                    appendToArrayTransactional(parentPath, enrichedData)
                    dao.updateStatus(operationId, "SUCCESS")
                    Log.d(TAG, "✅ SUB-PEMBAYARAN SYNCED (transactional append)!")
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

    suspend fun removeStatusKhususDirect(
        cabangId: String,
        pelangganId: String,
        adminUid: String
    ): SaveResult = withContext(Dispatchers.IO) {
        val path = "pelanggan_status_khusus/$cabangId/$pelangganId"

        Log.d(TAG, "🚀 removeStatusKhususDirect() CALLED!")

        try {
            val operation = PendingOperation(
                operationType = "REMOVE_STATUS_KHUSUS",
                firebasePath = path,
                dataJson = "{}",
                adminUid = adminUid,
                pelangganId = pelangganId,
                status = "PENDING"
            )
            val operationId = dao.insert(operation)
            Log.d(TAG, "🗑️ [STEP 1] ✅ REMOVE STATUS_KHUSUS SAVED TO ROOM DB! opId=$operationId")

            if (isOnline()) {
                try {
                    dao.updateStatus(operationId, "SYNCING")
                    firebase.getReference(path).removeValue().await()
                    dao.updateStatus(operationId, "SUCCESS")
                    Log.d(TAG, "✅ REMOVE STATUS_KHUSUS SYNCED!")
                    SaveResult.Success
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Firebase remove failed: ${e.message}")
                    dao.updateStatus(operationId, "PENDING", e.message)
                    SyncForegroundService.startSync(context)
                    SyncWorker.triggerImmediateSync(context)
                    SaveResult.Queued
                }
            } else {
                Log.d(TAG, "📵 OFFLINE! Remove saved to Room DB.")
                SyncForegroundService.startSync(context)
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

        // Top-up (pinjamanKe > 1): upload ke folder pending dan tulis URL ke
        // pendingFoto*Url — bukan fotoKtpUrl — agar foto pinjaman aktif lama
        // tidak tertimpa sebelum approval final.
        // New loan (pinjamanKe == 1): perilaku lama, tulis langsung ke fotoKtpUrl.
        val pinjamanKeRaw = data["pinjamanKe"]
        val pinjamanKe = (pinjamanKeRaw as? Long)?.toInt()
            ?: (pinjamanKeRaw as? Int)
            ?: (pinjamanKeRaw as? Number)?.toInt()
            ?: 1
        val isTopUp = pinjamanKe > 1

        try {
            uploadKtpIfPending(mutableData, adminUid, pelangganId, isTopUp,
                uriKey = "pendingFotoKtpUri",
                pendingUrlKey = "pendingFotoKtpUrl",
                permanentUrlKey = "fotoKtpUrl",
                jenisKtp = "utama")

            uploadKtpIfPending(mutableData, adminUid, pelangganId, isTopUp,
                uriKey = "pendingFotoKtpSuamiUri",
                pendingUrlKey = "pendingFotoKtpSuamiUrl",
                permanentUrlKey = "fotoKtpSuamiUrl",
                jenisKtp = "suami")

            uploadKtpIfPending(mutableData, adminUid, pelangganId, isTopUp,
                uriKey = "pendingFotoKtpIstriUri",
                pendingUrlKey = "pendingFotoKtpIstriUrl",
                permanentUrlKey = "fotoKtpIstriUrl",
                jenisKtp = "istri")

            uploadKtpIfPending(mutableData, adminUid, pelangganId, isTopUp,
                uriKey = "pendingFotoNasabahUri",
                pendingUrlKey = "pendingFotoNasabahUrl",
                permanentUrlKey = "fotoNasabahUrl",
                jenisKtp = "nasabah")

            // Foto serah terima tidak ikut alur top-up; selalu ke permanent.
            val pendingSerahTerimaUri = mutableData["pendingFotoSerahTerimaUri"] as? String ?: ""
            val currentSerahTerimaUrl = mutableData["fotoSerahTerimaUrl"] as? String ?: ""
            if (pendingSerahTerimaUri.isNotBlank() && currentSerahTerimaUrl.isBlank()) {
                Log.d(TAG, "📷 Uploading pending foto Serah Terima...")
                val uploadedUrl = uploadFotoKtp(
                    Uri.parse(pendingSerahTerimaUri), adminUid, pelangganId, "serah_terima"
                )
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
     * Upload satu foto KTP dari URI pending → URL, idempotent-safe untuk retry.
     * - isTopUp=true   → tulis URL ke pendingFoto*Url, folder Storage pending.
     * - isTopUp=false  → tulis URL ke fotoKtpUrl (permanent), folder Storage permanent.
     */
    private suspend fun uploadKtpIfPending(
        mutableData: MutableMap<String, Any?>,
        adminUid: String,
        pelangganId: String,
        isTopUp: Boolean,
        uriKey: String,
        pendingUrlKey: String,
        permanentUrlKey: String,
        jenisKtp: String
    ) {
        val pendingUri = mutableData[uriKey] as? String ?: ""
        if (pendingUri.isBlank()) return

        val targetUrlKey = if (isTopUp) pendingUrlKey else permanentUrlKey
        val currentTargetUrl = mutableData[targetUrlKey] as? String ?: ""
        // Idempotent: URL target sudah terisi (retry dari Room queue) → skip upload.
        if (currentTargetUrl.isNotBlank()) {
            mutableData[uriKey] = ""
            return
        }

        Log.d(TAG, "📷 Uploading $jenisKtp ${if (isTopUp) "[pending]" else "[permanent]"}...")
        val uploadedUrl = uploadFotoKtp(
            Uri.parse(pendingUri), adminUid, pelangganId, jenisKtp, isPending = isTopUp
        )
        if (uploadedUrl != null) {
            mutableData[targetUrlKey] = uploadedUrl
            mutableData[uriKey] = ""
            Log.d(TAG, "✅ $jenisKtp uploaded → $uploadedUrl")
        }
    }

    /**
     * Upload single foto KTP ke Firebase Storage.
     * isPending=true menulis ke folder `ktp_images_pending/` agar foto pinjaman
     * aktif lama tidak tertimpa sebelum approval selesai (jalur top-up), konsisten
     * dengan PelangganViewModel.uploadFotoKtp.
     */
    private suspend fun uploadFotoKtp(
        imageUri: Uri,
        adminUid: String,
        pelangganId: String,
        jenisKtp: String = "utama",
        isPending: Boolean = false
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "📷 uploadFotoKtp: $jenisKtp for $pelangganId${if (isPending) " [pending]" else ""}")

                val storageRef = storage.reference
                val folder = if (isPending) "ktp_images_pending" else "ktp_images"
                val ktpRef = storageRef.child("$folder/$adminUid/$pelangganId/ktp_$jenisKtp.jpg")

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
                // Timeout 60s agar SyncWorker tidak terkunci selamanya pada upload macet.
                val task = withTimeoutOrNull(60_000L) { uploadTask.await() }
                if (task == null) {
                    Log.e(TAG, "❌ Upload KTP timeout 60s (SyncManager)")
                    try { uploadTask.cancel() } catch (_: Exception) {}
                    return@withContext null
                }

                if (task.task.isSuccessful) {
                    // Timeout 15s untuk ambil downloadUrl (hanya query metadata Storage).
                    val downloadUrl = withTimeoutOrNull(15_000L) { ktpRef.downloadUrl.await() }
                    if (downloadUrl == null) {
                        Log.e(TAG, "❌ downloadUrl timeout 15s (SyncManager)")
                        return@withContext null
                    }
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
                    "ADD_PELANGGAN" -> {
                        ref.setValue(data).await()
                    }
                    "ADD_PEMBAYARAN", "ADD_SUB_PEMBAYARAN" -> {
                        // Replay via transaction append-only ke parent node agar tidak
                        // overwrite entry lain jika index lokal sudah tidak sinkron dengan server.
                        // Idempotency (jumlah+tanggal+keterangan) mencegah duplikat saat
                        // replay berulang atau sync konkuren dari device lain.
                        val parentPath = operation.firebasePath.substringBeforeLast("/")
                        @Suppress("UNCHECKED_CAST")
                        val payload = data as? Map<String, Any?>
                            ?: throw IllegalStateException("Payload bukan Map untuk ${operation.operationType}")
                        appendToArrayTransactional(parentPath, payload)
                    }
                    "UPDATE_PELANGGAN" -> {
                        ref.updateChildren(data as Map<String, Any?>).await()
                    }
                    "REMOVE_STATUS_KHUSUS",
                    "REMOVE_PELANGGAN",
                    "REMOVE_PELANGGAN_STATUS_KHUSUS" -> {
                        // removeValue() bersifat idempoten — replay berulang aman.
                        // REMOVE_PELANGGAN & REMOVE_PELANGGAN_STATUS_KHUSUS dipakai
                        // alur cairkanSimpanan agar pelanggan + status_khusus hilang
                        // saat sync (sebelumnya direct write yang gagal offline).
                        ref.removeValue().await()
                    }
                    "SERAH_TERIMA" -> {
                        // Foto serah terima yang diambil saat OFFLINE: upload foto ke
                        // Storage → tulis fotoSerahTerimaUrl ke RTDB → dispatch notifikasi
                        // ke Pimpinan/Pengawas/Koordinator. Tanpa branch ini, foto offline
                        // tidak pernah ter-upload & atasan tidak pernah dinotifikasi.
                        handleSerahTerimaSync(operation)
                    }
                    else -> {
                        // Default setValue — dipakai oleh:
                        // - ADD_JURNAL_TRANSAKSI (firebasePath sudah berisi push key
                        //   yang di-generate client-side → idempoten saat retry)
                        // - ADD_RIWAYAT_PINJAMAN (path: riwayat_pinjaman/{adminUid}/
                        //   {pelangganId}/{pinjamanKe} → idempoten karena overwrite
                        //   ke key yang sama)
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

    /**
     * Proses sinkronisasi operasi SERAH_TERIMA (foto serah terima yang diambil
     * saat OFFLINE pada aksi "Cairkan").
     *
     * Langkah:
     *   1. Idempotent guard: kalau fotoSerahTerimaUrl SUDAH terisi di RTDB,
     *      operasi sudah pernah sukses → skip (mencegah upload & notifikasi ganda).
     *   2. Upload foto pending (content:// URI lokal device) ke Storage. Kalau
     *      gagal → throw supaya di-retry oleh syncAllPending (URL belum tertulis,
     *      jadi retry aman).
     *   3. Tulis fotoSerahTerimaUrl + statusSerahTerima="Selesai" ke RTDB, serta
     *      bersihkan pendingFotoSerahTerimaUri.
     *   4. Dispatch notifikasi SERAH_TERIMA (berisi fotoSerahTerimaUrl) ke
     *      Pimpinan/Pengawas/Koordinator — identik dengan jalur online. Dispatch
     *      menelan error internalnya sendiri sehingga kegagalan kirim notifikasi
     *      tidak menggagalkan operasi sync (foto sudah aman tertulis).
     *
     * Payload (dataJson) dibawa dari OfflineRepository.queueSerahTerima agar
     * notifikasi tidak perlu membaca ulang node pelanggan (hemat RTDB).
     */
    private suspend fun handleSerahTerimaSync(operation: PendingOperation) {
        val adminUid = operation.adminUid
        val pelangganId = operation.pelangganId ?: ""
        if (adminUid.isBlank() || pelangganId.isBlank()) {
            Log.e(TAG, "❌ SERAH_TERIMA: adminUid/pelangganId kosong, skip")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val payload: Map<String, Any?> = try {
            gson.fromJson(operation.dataJson, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            emptyMap()
        }

        val pendingUri = payload["pendingUri"] as? String ?: ""
        val cabangId = payload["cabangId"] as? String ?: ""
        val namaPanggilan = payload["namaPanggilan"] as? String ?: ""
        val adminName = payload["adminName"] as? String ?: "Admin"
        val besarPinjaman = (payload["besarPinjaman"] as? Number)?.toInt() ?: 0
        val tenor = (payload["tenor"] as? Number)?.toInt() ?: 0
        val tanggalSerahTerima = payload["tanggalSerahTerima"] as? String ?: ""

        val pelangganRef = firebase.getReference("pelanggan/$adminUid/$pelangganId")

        // (1) Idempotent guard.
        val existingUrl = try {
            pelangganRef.child("fotoSerahTerimaUrl").get().await().getValue(String::class.java) ?: ""
        } catch (e: Exception) {
            ""
        }
        if (existingUrl.isNotBlank()) {
            Log.d(TAG, "ℹ️ SERAH_TERIMA: fotoSerahTerimaUrl sudah ada, skip (idempotent)")
            return
        }

        if (pendingUri.isBlank()) {
            // Tidak ada yang bisa di-upload — jangan retry selamanya.
            Log.e(TAG, "❌ SERAH_TERIMA: pendingUri kosong, tidak ada foto untuk diupload")
            return
        }

        // (2) Upload foto → throw kalau gagal (akan di-retry).
        Log.d(TAG, "📷 SERAH_TERIMA: uploading foto pending...")
        val uploadedUrl = uploadFotoKtp(Uri.parse(pendingUri), adminUid, pelangganId, "serah_terima")
            ?: throw IllegalStateException("Upload foto serah terima gagal (akan retry)")

        // (3) Tulis URL final + status + bersihkan pending.
        pelangganRef.updateChildren(
            mapOf(
                "fotoSerahTerimaUrl" to uploadedUrl,
                "statusSerahTerima" to "Selesai",
                "pendingFotoSerahTerimaUri" to ""
            )
        ).await()
        Log.d(TAG, "✅ SERAH_TERIMA: foto uploaded & RTDB updated → $uploadedUrl")

        // (4) Dispatch notifikasi (menelan error internal — tidak menggagalkan op).
        serahTerimaNotifier.dispatchAll(
            adminUid = adminUid,
            pelangganId = pelangganId,
            cabangId = cabangId,
            namaPanggilan = namaPanggilan,
            adminName = adminName,
            besarPinjaman = besarPinjaman,
            tenor = tenor,
            fotoUrl = uploadedUrl,
            tanggalSerahTerima = tanggalSerahTerima
        )
        Log.d(TAG, "✅ SERAH_TERIMA: notifikasi atasan ter-dispatch")
    }

    /**
     * Append `data` ke array node di `parentPath` secara transactional & idempotent.
     *
     * Kenapa transaction: menulis langsung ke `parentPath/$index` dengan `setValue` akan
     * menimpa entry yang sudah ada di index itu jika list lokal stale (mis. Cloud Function
     * onPembayaranAdded/koreksiStorting menambah entry, device lain input duluan, atau
     * memory cache belum invalidate). Transaction membaca panjang server terkini dan
     * selalu append di belakang — tidak pernah menimpa entry lain.
     *
     * Idempotency (`jumlah`+`tanggal`+`keterangan`): kalau sync di-retry atau dua sumber
     * meng-queue operasi identik, entry duplikat di-skip. Menerima trade-off kecil: dua
     * pembayaran legit back-to-back dengan nominal+tanggal+keterangan persis sama akan
     * dianggap duplikat (rare).
     *
     * Return: true kalau committed (atau skip karena sudah ada = idempotent success).
     * Throw: DatabaseError exception kalau transaction gagal total (network/rule) —
     * caller tangkap & re-queue.
     */
    private suspend fun appendToArrayTransactional(
        parentPath: String,
        data: Map<String, Any?>
    ): Boolean = suspendCancellableCoroutine { cont ->
        val ref = firebase.getReference(parentPath)
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val raw = currentData.value
                val existing: MutableList<Any?> = when (raw) {
                    is List<*> -> raw.toMutableList()
                    is Map<*, *> -> {
                        // RTDB bisa return sparse map kalau list pernah punya hole di tengah
                        raw.entries
                            .mapNotNull { (k, v) ->
                                val idx = k?.toString()?.toIntOrNull() ?: return@mapNotNull null
                                idx to v
                            }
                            .sortedBy { it.first }
                            .map { it.second }
                            .toMutableList()
                    }
                    null -> mutableListOf()
                    else -> return Transaction.abort()
                }

                // Idempotency by clientOpId: UUID unik per operasi di Room.
                // - Split payment (2 entry {20rb,"22 Apr"} dari satu aksi user) punya
                //   clientOpId berbeda → keduanya ter-append. Tidak lagi false-positive
                //   dedup seperti cek jumlah+tanggal+keterangan sebelumnya.
                // - Replay operasi yang sama (mis. retry setelah ACK hilang) pakai
                //   clientOpId identik → tetap ter-dedup dengan benar.
                // - Payload tanpa clientOpId (legacy/external write) → tidak di-dedup,
                //   append langsung (aman, kompat dengan data lama).
                val newOpId = data["clientOpId"]?.toString()
                val isDuplicate = if (newOpId.isNullOrBlank()) {
                    false
                } else {
                    existing.any { item ->
                        val map = item as? Map<*, *> ?: return@any false
                        val existingOpId = map["clientOpId"]?.toString()
                        !existingOpId.isNullOrBlank() && existingOpId == newOpId
                    }
                }

                if (!isDuplicate) {
                    existing.add(data)
                    currentData.value = existing
                }
                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                snapshot: DataSnapshot?
            ) {
                if (error != null) {
                    cont.resumeWithException(error.toException())
                } else {
                    cont.resume(committed)
                }
            }
        })
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

    // Hitungan terpisah PENDING/SYNCING vs FAILED — dipakai SyncStatusUI untuk
    // memisahkan badge "menunggu sinkronisasi" dari "gagal & butuh perhatian".
    suspend fun getPendingOnlyCount(): Int = dao.getPendingOnlyCount()
    fun observePendingOnlyCount(): Flow<Int> = dao.getPendingOnlyCountFlow()
    suspend fun getFailedCount(): Int = dao.getFailedCount()
    fun observeFailedCount(): Flow<Int> = dao.getFailedCountFlow()
    suspend fun getFailedOperations(): List<PendingOperation> = dao.getFailedOperations()

    suspend fun getAllOperations(): List<PendingOperation> = dao.getAllOperations()

    suspend fun cleanupSuccessful() {
        dao.deleteSuccessful()
    }

    // Retry manual untuk FAILED: reset retryCount ke 0 + clear errorMessage
    // (lewat resetFailedToRetry()) sebelum trigger sync. Versi lama hanya
    // updateStatus(..., "PENDING", null) yang LALU MENINGKATKAN retryCount
    // via SQL CASE-WHEN, sehingga entry dengan retryCount=5 langsung jatuh ke
    // FAILED lagi pada attempt berikutnya.
    suspend fun retryAllFailed(): Int {
        val resetCount = dao.resetFailedToRetry()
        Log.d(TAG, "🔄 Reset $resetCount FAILED → PENDING (retryCount=0)")
        if (resetCount > 0) {
            SyncForegroundService.startSync(context)
        }
        return resetCount
    }

    /**
     * Antri operasi generik ke Room. Dipakai alur cairkanSimpanan (jurnal,
     * arsip riwayat_pinjaman, removeValue pelanggan + status_khusus) agar
     * semua langkah offline-first. `firebasePath` & `dataJson` harus sudah
     * siap dipakai langsung oleh trySyncOperation().
     */
    suspend fun queueOperation(
        operationType: String,
        firebasePath: String,
        dataJson: String,
        adminUid: String,
        pelangganId: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val operation = PendingOperation(
            operationType = operationType,
            firebasePath = firebasePath,
            dataJson = dataJson,
            adminUid = adminUid,
            pelangganId = pelangganId,
            status = "PENDING"
        )
        val id = dao.insert(operation)
        Log.d(TAG, "📥 queueOperation: $operationType → $firebasePath (opId=$id)")
        SyncWorker.triggerImmediateSync(context)
        id
    }

    /**
     * Generate push key client-side (sama formula dengan DatabaseReference.push()).
     * Disimpan ke firebasePath sebelum di-queue → retry replay menggunakan key
     * yang sama (idempoten); tanpa ini, retry .push().setValue() akan
     * menghasilkan key baru = duplikasi entry.
     */
    fun generatePushKey(parentPath: String): String {
        return firebase.getReference(parentPath).push().key
            ?: error("Firebase gagal generate push key untuk $parentPath")
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