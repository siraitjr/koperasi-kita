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

    // ✅ M3: pemutar antrean ke Supabase. Dibuat malas agar aplikasi yang
    // masih memakai Firebase tidak pernah menyentuh SupabaseClient sama
    // sekali (dan tidak crash bila SUPABASE_URL belum diisi).
    private val supabaseHandler by lazy { SupabaseSyncHandler() }

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

        // =====================================================================
        // ✅ FIX §3 (audit god-tier): ADD_PELANGGAN adalah whole-node setValue.
        // Tanpa guard, replay antrean LAMA bisa MENIMPA state server yang sudah
        // sah (mis. sudah "Disetujui"/"Aktif" + ada cicilan) kembali ke snapshot
        // "Menunggu Approval" — pembayaranList ikut ter-reset. Rules tidak bisa
        // menolaknya karena payload-nya sah secara sintaks.
        //
        // Stempel generasi + status disimpan di dataJson Room (BUKAN ke RTDB —
        // selalu di-strip sebelum write). Saat replay/commit, transaksi
        // membandingkan dengan kondisi server:
        //   guardPk  > serverPk  → op MEMAJUKAN generasi (top-up baru) → tulis
        //   guardPk  < serverPk  → server sudah generasi lebih baru   → SKIP
        //   guardPk == serverPk  → tulis HANYA bila status server belum maju
        //                          melewati status yang di-queue.
        // Idempotency whole-node: setValue by nature idempoten; kunci anti-
        // regresi di sini adalah guard generasi+status, bukan dedup. clientOpId
        // disimpan di Room untuk korelasi audit/log antar percobaan.
        // =====================================================================
        val guardPkAdd = (pelangganData["pinjamanKe"] as? Number)?.toInt() ?: 1
        val guardStatusAdd = pelangganData["status"]?.toString() ?: ""
        val enrichedPelanggan = pelangganData + mapOf(
            "_guardPinjamanKe" to guardPkAdd,
            "_guardStatus" to guardStatusAdd,
            "clientOpId" to UUID.randomUUID().toString()
        )

        try {
            // ✅ STEP 1: SELALU simpan ke Room DB DULU!
            Log.d(TAG, "💾 [STEP 1] Preparing to save to Room DB...")

            val operation = PendingOperation(
                operationType = "ADD_PELANGGAN",
                firebasePath = path,
                dataJson = gson.toJson(enrichedPelanggan),
                adminUid = adminUid,
                pelangganId = pelangganId,
                status = "PENDING"
            )

            Log.d(TAG, "💾 [STEP 1] Inserting to Room DB...")
            val operationId = dao.insert(operation)
            Log.d(TAG, "💾 [STEP 1] ✅ SAVED TO ROOM DB! opId=$operationId")

            // ✅ STEP 2: Coba sync ke Firebase (jika online)
            // ✅ M3: di bawah sakelar Supabase, jalur tulis-langsung Firebase
            // TIDAK dipakai. Operasi sudah tersimpan di Room di atas, jadi
            // cukup dibiarkan diputar antrean → SupabaseSyncHandler. Kode
            // Firebase di bawah sengaja dibiarkan utuh untuk rollback.
            val online = isOnline() && !SyncBackend.pakaiSupabase(context)
            Log.d(TAG, "🌐 [STEP 2] Checking network: online=$online")

            if (online) {
                try {
                    Log.d(TAG, "🌐 [STEP 2] Online, syncing to Firebase...")
                    dao.updateStatus(operationId, "SYNCING")

                    // Upload foto dulu jika ada pending
                    val updatedData = uploadPendingPhotosForData(adminUid, pelangganId, pelangganData)

                    // ✅ FIX §3: TRANSAKSI guarded, bukan setValue biasa.
                    // Dua manfaat: (a) guard generasi+status dievaluasi atomik di
                    // server, (b) transaksi TIDAK di-persist SDK saat disconnect →
                    // ghost-write (salinan tanpa guard yang terkirim saat reconnect)
                    // tidak bisa lahir dari jalur ini. SaveResult tetap Success saat
                    // commit, jadi semantik isSynced pemanggil TIDAK berubah.
                    when (val txn = guardedAddPelangganWrite(path, updatedData, guardPkAdd, guardStatusAdd)) {
                        is GuardTxn.Applied -> {
                            dao.updateStatus(operationId, "SUCCESS")
                            Log.d(TAG, "✅ [STEP 3] SYNCED TO FIREBASE (transaksi guarded)!")
                        }
                        is GuardTxn.SkippedStale, is GuardTxn.SkippedMissing -> {
                            // Server sudah lebih baru → op ini memang tidak relevan lagi.
                            // Ditandai SUCCESS agar tidak menyumbat antrean.
                            dao.updateStatus(operationId, "SUCCESS", "SKIPPED_SERVER_LEBIH_BARU")
                            Log.w(TAG, "⏭️ ADD_PELANGGAN dilewati: state server lebih baru dari payload")
                        }
                        is GuardTxn.Retry -> throw Exception("Transaksi ADD_PELANGGAN perlu retry: ${txn.msg}")
                    }

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

            // ✅ M3: di bawah sakelar Supabase, jalur tulis-langsung Firebase
            // TIDAK dipakai. Operasi sudah tersimpan di Room di atas, jadi
            // cukup dibiarkan diputar antrean → SupabaseSyncHandler. Kode
            // Firebase di bawah sengaja dibiarkan utuh untuk rollback.
            if (isOnline() && !SyncBackend.pakaiSupabase(context)) {
                try {
                    dao.updateStatus(operationId, "SYNCING")
                    // ✅ FIX A: strip _guardPinjamanKe sebelum write (kunci guard hanya
                    // untuk replay-check; tidak boleh bocor ke RTDB). Jalur online-
                    // immediate tidak perlu cek generasi — pelanggan di memori = generasi
                    // saat ini by construction. dataJson di Room TETAP menyimpan kunci
                    // agar replay (bila jalur ini gagal) tetap ter-guard.
                    val (cleanPembayaranData, _) = stripGuardPinjamanKe(enrichedData)
                    appendToArrayTransactional(parentPath, cleanPembayaranData)
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

            // ✅ M3: di bawah sakelar Supabase, jalur tulis-langsung Firebase
            // TIDAK dipakai. Operasi sudah tersimpan di Room di atas, jadi
            // cukup dibiarkan diputar antrean → SupabaseSyncHandler. Kode
            // Firebase di bawah sengaja dibiarkan utuh untuk rollback.
            if (isOnline() && !SyncBackend.pakaiSupabase(context)) {
                try {
                    dao.updateStatus(operationId, "SYNCING")
                    // ✅ FIX A: strip _guardPinjamanKe sebelum write (lihat catatan
                    // savePembayaranDirect — kontrak sama).
                    val (cleanSubData, _) = stripGuardPinjamanKe(enrichedData)
                    appendToArrayTransactional(parentPath, cleanSubData)
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

            // ✅ M3: di bawah sakelar Supabase, jalur tulis-langsung Firebase
            // TIDAK dipakai. Operasi sudah tersimpan di Room di atas, jadi
            // cukup dibiarkan diputar antrean → SupabaseSyncHandler. Kode
            // Firebase di bawah sengaja dibiarkan utuh untuk rollback.
            if (isOnline() && !SyncBackend.pakaiSupabase(context)) {
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

            // ✅ M3: di bawah sakelar Supabase, jalur tulis-langsung Firebase
            // TIDAK dipakai. Operasi sudah tersimpan di Room di atas, jadi
            // cukup dibiarkan diputar antrean → SupabaseSyncHandler. Kode
            // Firebase di bawah sengaja dibiarkan utuh untuk rollback.
            if (isOnline() && !SyncBackend.pakaiSupabase(context)) {
                try {
                    dao.updateStatus(operationId, "SYNCING")
                    // ✅ FIX REGRESI PILOT (25 Jul 2026).
                    // Versi sebelumnya meng-AMPUTASI fast-path untuk op ber-guard
                    // (langsung SaveResult.Queued) demi menutup ghost-write. Itu
                    // MENIMBULKAN DUA REGRESI di perangkat pilot:
                    //   (a) setiap auto-lunas/statusKhusus jadi queue-only → antrean
                    //       menumpuk & bergantung penuh pada background worker;
                    //   (b) `status` di server tetap "Aktif" sampai replay jalan →
                    //       layar Nasabah Lunas KOSONG (filternya mengecualikan
                    //       status "Aktif"/"Disetujui"), padahal count di Ringkasan
                    //       memakai predikat berbasis pembayaran → tetap benar.
                    // Perbaikan: jalur langsung DIKEMBALIKAN, tapi memakai TRANSAKSI
                    // guarded — sama seperti savePelangganDirect/ADD_PELANGGAN.
                    // Transaksi TIDAK di-persist SDK saat disconnect → ghost-write
                    // tetap mustahil, sementara latensi & semantik SaveResult.Success
                    // (dipakai isSynced pemanggil) pulih seperti semula.
                    val (cleanUpdateData, guardPk) = stripGuardPinjamanKe(updateData)
                    if (guardPk != null) {
                        val txn = guardedPelangganTransaction(path, guardPk) { m ->
                            cleanUpdateData.forEach { (k, v) ->
                                if (v == null) m.remove(k) else m[k] = v
                            }
                            true
                        }
                        when (txn) {
                            is GuardTxn.Applied -> {
                                dao.updateStatus(operationId, "SUCCESS")
                                Log.d(TAG, "✅ UPDATE SYNCED (transaksi guarded)!")
                            }
                            is GuardTxn.SkippedStale, is GuardTxn.SkippedMissing -> {
                                dao.updateStatus(operationId, "SUCCESS", "SKIPPED_STALE_PINJAMAN_GENERATION")
                                Log.w(TAG, "⏭️ UPDATE dilewati: generasi/nasabah server sudah berbeda")
                            }
                            is GuardTxn.Retry -> throw Exception("Transaksi UPDATE_PELANGGAN perlu retry: ${txn.msg}")
                        }
                    } else {
                        firebase.getReference(path).updateChildren(cleanUpdateData).await()
                        dao.updateStatus(operationId, "SUCCESS")
                        Log.d(TAG, "✅ UPDATE SYNCED!")
                    }
                    SaveResult.Success
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Firebase update failed: ${e.message}")
                    dao.updateStatus(operationId, "PENDING", e.message)
                    // Dua pemicu sekaligus (pola sama dgn cabang offline): Worker saja
                    // bisa tertahan constraint/battery-optimization di sebagian device.
                    SyncForegroundService.startSync(context)
                    SyncWorker.triggerImmediateSync(context)
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
                when (val outcome = uploadFotoKtp(
                    Uri.parse(pendingSerahTerimaUri), adminUid, pelangganId, "serah_terima"
                )) {
                    is FotoUploadOutcome.Success -> {
                        mutableData["fotoSerahTerimaUrl"] = outcome.url
                        mutableData["pendingFotoSerahTerimaUri"] = ""
                        mutableData["statusSerahTerima"] = "Selesai"
                        // ✅ Aturan pimpinan 06 Jun 2026: Data Cleanse + mapping
                        // wajah → fotoNasabahUrl. Identik dengan jalur
                        // PelangganViewModel.buildCairkanCleansePayload.
                        applyCairkanCleanseTo(mutableData, outcome.url)
                        Log.d(TAG, "✅ Foto Serah Terima uploaded + data cleanse: ${outcome.url}")
                    }
                    // ✅ Audit pimpinan: URI mati (cacheDir di-evict OS / file
                    // di-hapus user) → bersihkan pendingFotoSerahTerimaUri agar
                    // sync TIDAK retry tak terhingga membaca sumber yang sudah
                    // tidak ada. Foto fisik memang hilang; ini menghentikan
                    // perdarahan diam (silent loss → silent + tercatat).
                    is FotoUploadOutcome.DeadUri -> {
                        mutableData["pendingFotoSerahTerimaUri"] = ""
                        Log.w(TAG, "⚠️ Foto Serah Terima URI mati ($pendingSerahTerimaUri) — di-skip permanen")
                    }
                    // Kegagalan jaringan/Storage: BIARKAN pendingUri agar
                    // sync berikutnya mencoba lagi (perilaku lama, aman).
                    is FotoUploadOutcome.TransientFailure -> {
                        Log.w(TAG, "⏳ Foto Serah Terima gagal transient — retry pada sync berikutnya")
                    }
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
        when (val outcome = uploadFotoKtp(
            Uri.parse(pendingUri), adminUid, pelangganId, jenisKtp, isPending = isTopUp
        )) {
            is FotoUploadOutcome.Success -> {
                mutableData[targetUrlKey] = outcome.url
                mutableData[uriKey] = ""
                Log.d(TAG, "✅ $jenisKtp uploaded → ${outcome.url}")
            }
            // ✅ Audit pimpinan: URI mati → bersihkan uriKey untuk hentikan
            // infinite-retry. Data nasabah lainnya tetap ter-sync; hanya foto
            // KTP/Nasabah ini yang hilang permanen (di-evict OS / di-delete).
            is FotoUploadOutcome.DeadUri -> {
                mutableData[uriKey] = ""
                Log.w(TAG, "⚠️ $jenisKtp URI mati ($pendingUri) — di-skip permanen")
            }
            is FotoUploadOutcome.TransientFailure -> {
                Log.w(TAG, "⏳ $jenisKtp gagal transient — retry pada sync berikutnya")
            }
        }
    }

    // =====================================================================
    // ✅ Discriminated outcome untuk uploadFotoKtp (audit pimpinan 06 Jun 2026).
    // ---------------------------------------------------------------------
    // Sebelumnya uploadFotoKtp mengembalikan String? (null = "entah kenapa").
    // Caller hanya bisa "skip" pada null → pendingFoto*Uri tidak pernah
    // dibersihkan saat URI cacheDir sudah dievict OS → sync mengulang baca
    // URI mati selamanya (silent loss + retry tak terhingga).
    //
    // Discriminator ini membedakan:
    //   - Success(url)        → write URL + clear pendingUri (perilaku lama).
    //   - DeadUri             → CLEAR pendingUri agar berhenti retry. Sinyal
    //                           ini muncul HANYA dari kegagalan baca/dekode
    //                           LOKAL di compressImageForKtp (tidak ada I/O
    //                           jaringan di langkah itu) — jadi aman dipakai
    //                           sebagai "file fisik tak terbaca lagi".
    //   - TransientFailure    → JANGAN clear pendingUri (jaringan/Storage).
    //                           Retry pada sync berikutnya seperti sebelumnya.
    //
    // Risiko false-positive (clear yang seharusnya transient): rendah —
    // compressImageForKtp 100% lokal; bila gambar terlalu besar (>700KB)
    // juga di-treat DeadUri karena tidak akan sembuh dengan retry sumber
    // yang sama.
    // =====================================================================
    private sealed class FotoUploadOutcome {
        data class Success(val url: String) : FotoUploadOutcome()
        object DeadUri : FotoUploadOutcome()
        object TransientFailure : FotoUploadOutcome()
    }

    // =====================================================================
    // ✅ Aturan pimpinan 06 Jun 2026 — Data Cleanse di momen Cairkan.
    // ---------------------------------------------------------------------
    // Saat Foto Serah Terima berhasil ter-upload pada proses Cairkan,
    // sistem mengganti data legacy (NIK + foto KTP + foto Nasabah + URI/URL
    // pending) dengan standar baru "No KTP, No NIK" — dan memetakan
    // fotoSerahTerimaUrl → fotoNasabahUrl agar Web "Buku Pokok" yang membaca
    // fotoNasabahUrl/fotoKtpUrl langsung memakai Foto Serah Terima sebagai
    // wajah utama TANPA perubahan kode Web.
    //
    // Implementasi identik dengan PelangganViewModel.buildCairkanCleansePayload
    // (jalur ONLINE). Dipanggil dari TIGA call site SyncManager:
    //   1. uploadPendingPhotosForData (serah-terima sub-block, jalur bulk
    //      transaction yang merge ke mutableData).
    //   2. handleSerahTerimaSync (jalur post-upload reconnect, atomic
    //      updateChildren langsung ke RTDB).
    //   3. (tidak ada call site lain — pencairan top-up cuma melewati dua
    //      titik di atas.)
    //
    // Integritas anti-duplikat NIK: pembersihan nik_registry/{NIK_lama}
    // di-handle oleh Cloud Function trigger
    // onPelanggan{Nik,NikSuami,NikIstri}ClearedRemoveRegistry — Android
    // client TIDAK menulis nik_registry langsung (taat CLAUDE.md §5.3).
    // =====================================================================
    private fun applyCairkanCleanseTo(target: MutableMap<String, Any?>, serahTerimaUrl: String) {
        // NIK legacy → kosong (memicu trigger Cloud Function hapus nik_registry).
        target["nik"] = ""
        target["nikSuami"] = ""
        target["nikIstri"] = ""
        // Foto KTP legacy → kosong.
        target["fotoKtpUrl"] = ""
        target["fotoKtpSuamiUrl"] = ""
        target["fotoKtpIstriUrl"] = ""
        // Mapping wajah utama: Foto Serah Terima jadi fotoNasabahUrl untuk Web.
        target["fotoNasabahUrl"] = serahTerimaUrl
        // Pending URI/URL legacy → kosong, agar offline queue TIDAK menghidupkan
        // kembali data yang sudah di-cleanse pada gelombang sync berikutnya.
        target["pendingFotoKtpUri"] = ""
        target["pendingFotoKtpSuamiUri"] = ""
        target["pendingFotoKtpIstriUri"] = ""
        target["pendingFotoNasabahUri"] = ""
        target["pendingFotoKtpUrl"] = ""
        target["pendingFotoKtpSuamiUrl"] = ""
        target["pendingFotoKtpIstriUrl"] = ""
        target["pendingFotoNasabahUrl"] = ""
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
    ): FotoUploadOutcome {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "📷 uploadFotoKtp: $jenisKtp for $pelangganId${if (isPending) " [pending]" else ""}")

                val storageRef = storage.reference
                val folder = if (isPending) "ktp_images_pending" else "ktp_images"
                val ktpRef = storageRef.child("$folder/$adminUid/$pelangganId/ktp_$jenisKtp.jpg")

                // Kompresi gambar — 100% lokal (tanpa I/O jaringan).
                // Gagal di sini = URI fisik tidak terbaca → DeadUri (retry
                // sumber yang sama tidak akan sembuh; clear di caller).
                val compressedImage = compressImageForKtp(imageUri)
                if (compressedImage.isEmpty()) {
                    Log.e(TAG, "❌ Gagal kompresi gambar — URI dianggap mati")
                    return@withContext FotoUploadOutcome.DeadUri
                }

                // Validasi ukuran — juga DeadUri (sumber tidak akan menyusut
                // dengan retry; user perlu ambil foto ulang).
                if (compressedImage.size > 700 * 1024) {
                    Log.e(TAG, "❌ Gambar terlalu besar: ${compressedImage.size / 1024}KB (max 700KB) — di-skip permanen")
                    return@withContext FotoUploadOutcome.DeadUri
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
                    Log.e(TAG, "❌ Upload KTP timeout 60s (SyncManager) — transient")
                    try { uploadTask.cancel() } catch (_: Exception) {}
                    return@withContext FotoUploadOutcome.TransientFailure
                }

                if (task.task.isSuccessful) {
                    // Timeout 15s untuk ambil downloadUrl (hanya query metadata Storage).
                    val downloadUrl = withTimeoutOrNull(15_000L) { ktpRef.downloadUrl.await() }
                    if (downloadUrl == null) {
                        Log.e(TAG, "❌ downloadUrl timeout 15s (SyncManager) — transient")
                        return@withContext FotoUploadOutcome.TransientFailure
                    }
                    Log.d(TAG, "✅ Foto KTP uploaded: ${compressedImage.size / 1024}KB → $downloadUrl")
                    FotoUploadOutcome.Success(downloadUrl.toString())
                } else {
                    Log.e(TAG, "❌ Upload gagal: ${task.task.exception?.message} — transient")
                    FotoUploadOutcome.TransientFailure
                }
            } catch (e: Exception) {
                // Sampai di sini = exception SETELAH compressImageForKtp lolos
                // (compressImageForKtp punya try/catch sendiri yg return empty
                // bytes). Berarti ini sisi jaringan/Storage → TransientFailure.
                Log.e(TAG, "❌ Exception upload foto KTP: ${e.message} — transient")
                FotoUploadOutcome.TransientFailure
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

                // =========================================================
                // ✅ M3: percabangan tujuan sinkronisasi.
                // Bila sakelar menunjuk Supabase, operasi diputar lewat
                // SupabaseSyncHandler dan seluruh blok RTDB di bawah
                // DILEWATI — bukan dihapus. Default tetap FIREBASE, jadi
                // perangkat yang sudah terpasang berperilaku persis sama
                // sampai sakelarnya dipindahkan dengan sadar (SyncBackend).
                // =========================================================
                if (SyncBackend.pakaiSupabase(context)) {
                    return@withContext syncKeSupabase(operation)
                }

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
                        // ✅ FIX §3: jalur replay INILAH lubang terlama (antrean bisa
                        // bertahan berhari-hari, jauh lebih lebar dari window ghost SDK).
                        // Payload di-strip dari kunci guard, lalu ditulis via transaksi
                        // yang menolak menimpa state server yang sudah lebih maju.
                        @Suppress("UNCHECKED_CAST")
                        val rawAdd = data as? Map<String, Any?>
                            ?: throw IllegalStateException("Payload bukan Map untuk ADD_PELANGGAN")
                        val (cleanAdd, guardPkAdd, guardStatusAdd) = stripAddPelangganGuards(rawAdd)
                        if (guardPkAdd == null) {
                            // Op warisan APK lama (tanpa stempel) — perilaku lama
                            // dipertahankan agar tidak ada regresi pada antrean existing.
                            ref.setValue(cleanAdd).await()
                        } else {
                            val txn = guardedAddPelangganWrite(
                                operation.firebasePath, cleanAdd, guardPkAdd, guardStatusAdd ?: ""
                            )
                            when (txn) {
                                is GuardTxn.Applied -> { /* sukses */ }
                                is GuardTxn.SkippedStale, is GuardTxn.SkippedMissing -> {
                                    Log.w(TAG, "⏭️ SKIP ADD_PELANGGAN basi (guard gen=$guardPkAdd status='$guardStatusAdd'): ${operation.firebasePath}")
                                    dao.updateStatus(operation.id, "SUCCESS", "SKIPPED_SERVER_LEBIH_BARU")
                                    return@withContext true
                                }
                                is GuardTxn.Retry -> throw Exception("Transaksi ADD_PELANGGAN perlu retry: ${txn.msg}")
                            }
                        }
                    }
                    "ADD_PEMBAYARAN", "ADD_SUB_PEMBAYARAN" -> {
                        // Replay via transaction append-only ke parent node agar tidak
                        // overwrite entry lain jika index lokal sudah tidak sinkron dengan server.
                        // Idempotency (jumlah+tanggal+keterangan) mencegah duplikat saat
                        // replay berulang atau sync konkuren dari device lain.
                        val parentPath = operation.firebasePath.substringBeforeLast("/")
                        @Suppress("UNCHECKED_CAST")
                        val rawPayload = data as? Map<String, Any?>
                            ?: throw IllegalStateException("Payload bukan Map untuk ${operation.operationType}")
                        // ✅ FIX A: guard generasi pinjaman. Bila op distempel
                        // _guardPinjamanKe dan server sudah pindah generasi (top-up
                        // terjadi antara queue & replay) → SKIP, jangan append cicilan
                        // pinjaman LAMA ke list pinjaman BARU (insiden Fitri/Witri).
                        val (payload, guardPk) = stripGuardPinjamanKe(rawPayload)
                        if (guardPk != null) {
                            // ✅ FIX B: append + cek generasi ATOMIK dalam SATU transaksi
                            // whole-node pelanggan (menutup juga TOCTOU get-then-append
                            // yang dulu didokumentasikan sbg residual risk). Dedup
                            // clientOpId direplikasi (parity appendToArrayTransactional).
                            val pelangganPath = parentPath.substringBefore("/pembayaranList")
                            val isSub = operation.operationType == "ADD_SUB_PEMBAYARAN"
                            val parentIdx = if (isSub) {
                                operation.firebasePath.substringAfter("/pembayaranList/")
                                    .substringBefore("/").toIntOrNull()
                                    ?: throw IllegalStateException("Index induk sub tidak valid: ${operation.firebasePath}")
                            } else -1
                            val opIdStr = payload["clientOpId"]?.toString()
                            val txn = guardedPelangganTransaction(pelangganPath, guardPk) { m ->
                                val list = normalizeRtdbList(m["pembayaranList"]) ?: return@guardedPelangganTransaction false
                                if (!isSub) {
                                    if (!containsClientOpId(list, opIdStr)) list.add(payload)
                                } else {
                                    // Parity struktur legacy: slot induk yang belum ada dibuat
                                    // (list-level txn lama juga membentuk path sparse yang sama).
                                    while (list.size <= parentIdx) list.add(mutableMapOf<String, Any?>())
                                    @Suppress("UNCHECKED_CAST")
                                    val pay = (list[parentIdx] as? Map<String, Any?>)?.toMutableMap()
                                        ?: mutableMapOf()
                                    val subs = normalizeRtdbList(pay["subPembayaran"]) ?: return@guardedPelangganTransaction false
                                    if (!containsClientOpId(subs, opIdStr)) subs.add(payload)
                                    pay["subPembayaran"] = subs
                                    list[parentIdx] = pay
                                }
                                m["pembayaranList"] = list
                                true
                            }
                            when (txn) {
                                is GuardTxn.Applied -> { /* sukses */ }
                                is GuardTxn.SkippedStale, is GuardTxn.SkippedMissing -> {
                                    Log.w(TAG, "⏭️ SKIP ${operation.operationType} basi (guard=$guardPk, ${if (txn is GuardTxn.SkippedMissing) "node hilang" else "generasi beda"}): ${operation.firebasePath}")
                                    dao.updateStatus(operation.id, "SUCCESS", "SKIPPED_STALE_PINJAMAN_GENERATION")
                                    return@withContext true
                                }
                                is GuardTxn.Retry -> throw Exception("Guarded txn perlu retry: ${txn.msg}")
                            }
                        } else {
                            appendToArrayTransactional(parentPath, payload)
                        }
                    }
                    "UPDATE_PELANGGAN" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rawUpdate = data as Map<String, Any?>
                        val (updatePayload, guardPkUpd) = stripGuardPinjamanKe(rawUpdate)
                        if (guardPkUpd != null) {
                            // ✅ FIX B: guard dicek DI DALAM transaksi (bukan get()-then-
                            // write yang bisa di-bypass ghost-write SDK saat flapping).
                            val txn = guardedPelangganTransaction(operation.firebasePath, guardPkUpd) { m ->
                                updatePayload.forEach { (k, v) ->
                                    if (v == null) m.remove(k) else m[k] = v
                                }
                                true
                            }
                            when (txn) {
                                is GuardTxn.Applied -> { /* sukses — lanjut ke penandaan SUCCESS di bawah */ }
                                is GuardTxn.SkippedStale, is GuardTxn.SkippedMissing -> {
                                    Log.w(TAG, "⏭️ SKIP UPDATE_PELANGGAN basi (guard=$guardPkUpd, ${if (txn is GuardTxn.SkippedMissing) "node hilang" else "generasi beda"}): ${operation.firebasePath}")
                                    dao.updateStatus(operation.id, "SUCCESS", "SKIPPED_STALE_PINJAMAN_GENERATION")
                                    return@withContext true
                                }
                                is GuardTxn.Retry -> throw Exception("Guarded txn perlu retry: ${txn.msg}")
                            }
                        } else {
                            ref.updateChildren(updatePayload).await()
                        }
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

                // =========================================================
                // ✅ FIX C (25 Jul 2026): pisahkan gagal PERMANEN vs TRANSIENT.
                // Setelah rules Layer-3 aktif, op warisan APK lama yang menulis
                // status "Lunas" TANPA marker statusLunasUntukPinjamanKe ditolak
                // server selamanya → retry buta 21x (keluhan lapangan).
                //   1) Coba REPAIR SEMANTIK: putuskan dari KEBENARAN SERVER,
                //      bukan dari intent basi di antrean.
                //   2) Kalau tidak bisa direpair → REJECTED (terminal), supaya
                //      "Coba Lagi" tidak menghidupkannya lagi & UI bisa jelas.
                // =========================================================
                if (isPermissionDenied(e)) {
                    val repaired = tryRepairRejectedOperation(operation)
                    if (repaired) {
                        dao.updateStatus(operation.id, "SUCCESS", "REPAIRED_LEGACY_LUNAS_MARKER")
                        Log.d(TAG, "🔧 Op diperbaiki & tersinkron (marker generasi ditambahkan dari data server)")
                        return@withContext true
                    }
                    dao.updateStatus(operation.id, "REJECTED", "Ditolak server: ${e.message}")
                    Log.e(TAG, "🚫 REJECTED permanen (tidak di-retry lagi): ${operation.operationType} ${operation.firebasePath}")
                    return@withContext false
                }

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

    // =====================================================================
    // ✅ M3: pemutaran satu operasi antrean ke SUPABASE.
    // ---------------------------------------------------------------------
    // Memakai status Room yang PERSIS SAMA dengan jalur Firebase, sehingga
    // seluruh UI status sinkronisasi (SyncStatusUI: menunggu / gagal /
    // ditolak-buang) bekerja tanpa perubahan apa pun:
    //
    //   Sukses / Dilewati → SUCCESS   (Dilewati = server sudah lebih maju)
    //   Ditolak           → REJECTED  (terminal; "Coba Lagi" tidak menyentuhnya)
    //   GagalSementara    → PENDING / FAILED sesuai budget retry
    //
    // Pemisahan Ditolak vs GagalSementara inilah yang mencegah antrean
    // berputar buta — pelajaran dari insiden "Permission denied, retry 21x"
    // di jalur Firebase (lihat FIX C pada catch di trySyncOperation).
    // =====================================================================
    private suspend fun syncKeSupabase(operation: PendingOperation): Boolean {
        return try {
            when (val hasil = supabaseHandler.putar(operation)) {
                is SupabaseSyncHandler.Hasil.Sukses -> {
                    dao.updateStatus(operation.id, "SUCCESS")
                    Log.d(TAG, "✅ [Supabase] Synced: ${operation.operationType}")
                    true
                }
                is SupabaseSyncHandler.Hasil.Dilewati -> {
                    dao.updateStatus(operation.id, "SUCCESS", hasil.alasan)
                    Log.w(TAG, "⏭️ [Supabase] Dilewati: ${operation.operationType} — ${hasil.alasan}")
                    true
                }
                is SupabaseSyncHandler.Hasil.Ditolak -> {
                    dao.updateStatus(operation.id, "REJECTED", "Ditolak server: ${hasil.pesan}")
                    Log.e(TAG, "🚫 [Supabase] REJECTED: ${operation.operationType} — ${hasil.pesan}")
                    false
                }
                is SupabaseSyncHandler.Hasil.GagalSementara -> {
                    val next = operation.retryCount + 1
                    dao.updateStatus(
                        operation.id,
                        if (next >= MAX_RETRY) "FAILED" else "PENDING",
                        hasil.pesan
                    )
                    Log.w(TAG, "⏳ [Supabase] Gagal sementara (retry $next): ${hasil.pesan}")
                    false
                }
            }
        } catch (e: Exception) {
            // Jaring pengaman: apa pun yang lolos dari handler diperlakukan
            // sebagai transient, supaya tidak ada operasi yang hilang diam-diam.
            Log.e(TAG, "❌ [Supabase] Error tak terduga: ${e.message}")
            val next = operation.retryCount + 1
            dao.updateStatus(
                operation.id,
                if (next >= MAX_RETRY) "FAILED" else "PENDING",
                e.message
            )
            false
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
        // ✅ FIX SPLIT-STATE (Issue 2): transisi cairkan yang harus ikut ditulis.
        val tanggalPencairan = payload["tanggalPencairan"] as? String ?: ""
        val hasilSimulasiCicilanJson = payload["hasilSimulasiCicilanJson"] as? String ?: ""

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

        // (2) Upload foto → discriminated outcome (audit pimpinan 06 Jun 2026):
        //   - Success: lanjut tulis URL + dispatch notifikasi.
        //   - DeadUri: jangan throw (retry sumber mati = sia-sia). Bersihkan
        //     pendingFotoSerahTerimaUri di RTDB, mark status agar tidak terus
        //     muncul sebagai "ada pending foto", lalu RETURN tanpa dispatch
        //     notifikasi (tidak ada foto yang bisa dilampirkan).
        //   - TransientFailure: throw seperti sebelumnya → operasi di-retry
        //     oleh syncAllPending pada gelombang berikutnya (jaringan kembali).
        Log.d(TAG, "📷 SERAH_TERIMA: uploading foto pending...")
        val uploadedUrl = when (val outcome = uploadFotoKtp(
            Uri.parse(pendingUri), adminUid, pelangganId, "serah_terima"
        )) {
            is FotoUploadOutcome.Success -> outcome.url
            is FotoUploadOutcome.DeadUri -> {
                Log.w(TAG, "⚠️ SERAH_TERIMA: URI foto mati ($pendingUri) — bersihkan pending, skip notifikasi")
                try {
                    pelangganRef.updateChildren(
                        mapOf("pendingFotoSerahTerimaUri" to "")
                    ).await()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ SERAH_TERIMA: gagal bersihkan pendingFotoSerahTerimaUri: ${e.message}")
                }
                return
            }
            is FotoUploadOutcome.TransientFailure ->
                throw IllegalStateException("Upload foto serah terima gagal transient (akan retry)")
        }

        // (3) Tulis URL final + status + bersihkan pending + DATA CLEANSE.
        //     Atomic multi-path: foto URL, status, pendingUri, cleanse legacy
        //     (NIK/foto KTP/pending URI), dan mapping fotoSerahTerimaUrl →
        //     fotoNasabahUrl untuk Web Buku Pokok. Lihat
        //     PelangganViewModel.buildCairkanCleansePayload untuk rasionalitas.
        val finalUpdates: MutableMap<String, Any?> = mutableMapOf(
            "fotoSerahTerimaUrl" to uploadedUrl,
            "statusSerahTerima" to "Selesai",
            "pendingFotoSerahTerimaUri" to ""
        )
        applyCairkanCleanseTo(finalUpdates, uploadedUrl)

        // ✅ FIX SPLIT-STATE (Issue 2): kopel transisi cairkan ke worker robust.
        // Sebelumnya status→Aktif + tanggalPencairan + jadwal cicilan HANYA ditulis
        // via setValue native-persistence di ViewModel — yang tertahan saat app
        // di-kill, sehingga foto ter-upload tapi status macet di "Disetujui" dan
        // tombol "Cairkan" muncul lagi. Kini ditulis ATOMIK bersama foto.
        // Guard: hanya bila payload membawa data cairkan DAN status server masih
        // "Disetujui" (jangan menimpa pembatalan/Tidak Aktif yang terjadi belakangan).
        if (tanggalPencairan.isNotBlank()) {
            val currentStatus = try {
                pelangganRef.child("status").get().await().getValue(String::class.java) ?: ""
            } catch (e: Exception) { "" }
            if (currentStatus == "Disetujui") {
                finalUpdates["status"] = "Aktif"
                finalUpdates["tanggalPencairan"] = tanggalPencairan
                if (hasilSimulasiCicilanJson.isNotBlank()) {
                    try {
                        val cicilan = gson.fromJson(
                            hasilSimulasiCicilanJson,
                            Array<com.example.koperasikitagodangulu.SimulasiCicilan>::class.java
                        ).toList()
                        finalUpdates["hasilSimulasiCicilan"] = cicilan
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ SERAH_TERIMA: gagal parse cicilan: ${e.message}")
                    }
                }
                finalUpdates["lastUpdated"] = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                ).format(java.util.Date())
                Log.d(TAG, "✅ SERAH_TERIMA: coupling cairkan diterapkan (Disetujui→Aktif)")
            } else {
                Log.d(TAG, "ℹ️ SERAH_TERIMA: status server='$currentStatus' (bukan Disetujui) — skip flip status")
            }
        }

        pelangganRef.updateChildren(finalUpdates).await()
        Log.d(TAG, "✅ SERAH_TERIMA: foto uploaded + data cleanse & RTDB updated → $uploadedUrl")

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
    // =========================================================================
    // ✅ FIX A (03 Jul 2026): Guard generasi pinjaman untuk operasi offline.
    // -------------------------------------------------------------------------
    // Kunci reservasi "_guardPinjamanKe" distempel PelangganViewModel pada
    // payment-map & update auto-lunas (tambahPembayaran / tambahSubPembayaran /
    // tambahMultiplePembayaran). Kontrak:
    //   - Kunci ini TIDAK BOLEH pernah tertulis ke RTDB → selalu strip di semua
    //     jalur write (online-immediate di *Direct maupun replay trySyncOperation).
    //   - Saat REPLAY: bila server pinjamanKe != nilai stempel → op basi (top-up
    //     terjadi di antara queue & flush) → SKIP (op dikonsumsi, tidak retry).
    //     Ini mencegah: (a) cicilan pinjaman lama ter-append ke list pinjaman
    //     baru yang sudah di-reset, (b) status "Lunas" pinjaman lama meng-
    //     clobber "Disetujui"/"Aktif" pinjaman baru (insiden Fitri/Witri 02 Jul).
    //   - Payload TANPA kunci (op lama di antrean / APK lama) → perilaku 100%
    //     identik sebelumnya (backward compatible, tanpa migrasi Room).
    // =========================================================================
    private fun stripGuardPinjamanKe(data: Map<String, Any?>): Pair<Map<String, Any?>, Int?> {
        if (!data.containsKey("_guardPinjamanKe")) return data to null
        val guard = (data["_guardPinjamanKe"] as? Number)?.toInt()
        return data.filterKeys { it != "_guardPinjamanKe" } to guard
    }

    // =========================================================================
    // ✅ FIX B (04 Jul 2026): Guarded write = TRANSAKSI server-authoritative.
    // -------------------------------------------------------------------------
    // Pelajaran insiden network-flapping: write biasa (updateChildren/setValue)
    // yang await()-nya GAGAL tetap sudah ter-queue di persistence internal SDK
    // dan AKAN terkirim saat socket tersambung lagi — sebagai copy TANPA guard
    // (ghost-write). Pola check-then-write karenanya bisa ter-bypass.
    // TRANSAKSI RTDB tidak punya kelemahan itu: compare-and-set atomik yang
    // re-run terhadap data server, dan TIDAK dipersist SDK saat disconnect.
    // Semua op ber-stempel _guardPinjamanKe kini ditulis via transaksi ini.
    // =========================================================================
    private sealed class GuardTxn {
        object Applied : GuardTxn()
        object SkippedStale : GuardTxn()
        object SkippedMissing : GuardTxn()
        data class Retry(val msg: String) : GuardTxn()
    }

    /**
     * Jalankan mutasi pada node pelanggan HANYA bila pinjamanKe server ==
     * guardPk — dicek DI DALAM transaksi (atomik, server-authoritative).
     * mutate mengembalikan false bila struktur data tidak bisa dimutasi
     * (malformed) → abort → Retry (op tetap PENDING, tidak hilang senyap).
     */
    private suspend fun guardedPelangganTransaction(
        pelangganPath: String,
        guardPk: Int,
        mutate: (MutableMap<String, Any?>) -> Boolean
    ): GuardTxn = suspendCancellableCoroutine { cont ->
        var abortReason = ""
        val ref = firebase.getReference(pelangganPath)
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val raw = currentData.value
                    // Pola kanonik RTDB: run pertama bisa null (cache kosong) —
                    // success tanpa modifikasi; SDK re-run dgn data server asli.
                    ?: return Transaction.success(currentData)
                @Suppress("UNCHECKED_CAST")
                val map = (raw as? Map<String, Any?>)?.toMutableMap()
                    ?: run { abortReason = "MALFORMED"; return Transaction.abort() }
                val serverPk = (map["pinjamanKe"] as? Number)?.toInt() ?: 1
                if (serverPk != guardPk) {
                    abortReason = "STALE"
                    return Transaction.abort()
                }
                if (!mutate(map)) {
                    abortReason = "MUTATE_FAILED"
                    return Transaction.abort()
                }
                currentData.value = map
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                val result = when {
                    error != null -> GuardTxn.Retry(error.message)
                    !committed && abortReason == "STALE" -> GuardTxn.SkippedStale
                    !committed -> GuardTxn.Retry("aborted: ${abortReason.ifBlank { "unknown" }}")
                    // committed tapi node null = pelanggan sudah tidak ada di server
                    // (mis. cairkanSimpanan menghapusnya) → op basi, konsumsi.
                    snapshot?.value == null -> GuardTxn.SkippedMissing
                    else -> GuardTxn.Applied
                }
                if (cont.isActive) cont.resume(result)
            }
        })
    }

    // =========================================================================
    // ✅ FIX §3 — guard whole-node ADD_PELANGGAN.
    // =========================================================================

    /**
     * Peringkat kemajuan siklus pinjaman. Dipakai HANYA untuk menjawab
     * "apakah state server sudah lebih maju dari payload yang di-queue?".
     * Bukan mesin state — tidak mengatur transisi apa pun.
     */
    private fun statusRank(s: String?): Int = when (s?.trim()) {
        "Menunggu Approval" -> 0
        "Disetujui" -> 1
        "Aktif" -> 2
        else -> 3 // Lunas / Ditolak / Tidak Aktif / lainnya = terminal
    }

    /** Buang kunci guard internal agar TIDAK pernah tertulis ke RTDB. */
    private fun stripAddPelangganGuards(data: Map<String, Any?>): Triple<Map<String, Any?>, Int?, String?> {
        val pk = (data["_guardPinjamanKe"] as? Number)?.toInt()
        val st = data["_guardStatus"]?.toString()
        val clean = data.filterKeys {
            it != "_guardPinjamanKe" && it != "_guardStatus" && it != "clientOpId"
        }
        return Triple(clean, pk, st)
    }

    /**
     * Tulis whole-node pelanggan HANYA bila server belum bergerak melewati
     * payload yang di-queue. Semua keputusan diambil DI DALAM transaksi
     * (atomik, server-authoritative, tidak di-persist SDK saat disconnect).
     */
    private suspend fun guardedAddPelangganWrite(
        path: String,
        payload: Map<String, Any?>,
        guardPk: Int,
        guardStatus: String
    ): GuardTxn = suspendCancellableCoroutine { cont ->
        var abortReason = ""
        firebase.getReference(path).runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val raw = currentData.value
                if (raw == null) {
                    // Node belum ada → pembuatan nasabah baru / sync pertama.
                    // Pola kanonik "create if absent": tulis; bila ternyata data
                    // memang ada, SDK me-run ulang dgn data server & guard berlaku.
                    currentData.value = payload
                    return Transaction.success(currentData)
                }
                @Suppress("UNCHECKED_CAST")
                val server = raw as? Map<String, Any?>
                    ?: run { abortReason = "MALFORMED"; return Transaction.abort() }

                val serverPk = (server["pinjamanKe"] as? Number)?.toInt() ?: 1
                val serverStatus = server["status"]?.toString()

                // Server sudah di generasi lebih baru → payload ini basi.
                if (guardPk < serverPk) { abortReason = "STALE_GEN"; return Transaction.abort() }
                // Generasi sama, tapi siklus server sudah maju (Disetujui/Aktif/
                // Lunas) → jangan tarik mundur ke "Menunggu Approval".
                if (guardPk == serverPk && statusRank(serverStatus) > statusRank(guardStatus)) {
                    abortReason = "STALE_STATUS"; return Transaction.abort()
                }
                // guardPk > serverPk = op ini MEMAJUKAN generasi (top-up sah) → tulis.
                currentData.value = payload
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                val result = when {
                    error != null -> GuardTxn.Retry(error.message)
                    !committed && (abortReason == "STALE_GEN" || abortReason == "STALE_STATUS") ->
                        GuardTxn.SkippedStale
                    !committed -> GuardTxn.Retry("aborted: ${abortReason.ifBlank { "unknown" }}")
                    else -> GuardTxn.Applied
                }
                if (cont.isActive) cont.resume(result)
            }
        })
    }

    // Normalisasi list RTDB (List / sparse-Map / null) → MutableList.
    // Parity dgn normalisasi di appendToArrayTransactional.
    private fun normalizeRtdbList(raw: Any?): MutableList<Any?>? = when (raw) {
        is List<*> -> raw.toMutableList()
        is Map<*, *> -> raw.entries
            .mapNotNull { (k, v) -> (k?.toString()?.toIntOrNull() ?: return@mapNotNull null) to v }
            .sortedBy { it.first }.map { it.second }.toMutableList()
        null -> mutableListOf()
        else -> null
    }

    // Dedup idempotency by clientOpId — parity dgn appendToArrayTransactional.
    private fun containsClientOpId(list: List<Any?>, opId: String?): Boolean {
        if (opId.isNullOrBlank()) return false
        return list.any { item ->
            val m = item as? Map<*, *> ?: return@any false
            val existing = m["clientOpId"]?.toString()
            !existing.isNullOrBlank() && existing == opId
        }
    }

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

    // =========================================================================
    // ✅ FIX C (25 Jul 2026) — penanganan op DITOLAK PERMANEN oleh server.
    // =========================================================================

    /** Deteksi penolakan permanen server (rules `.write`/`.validate`). */
    private fun isPermissionDenied(e: Exception): Boolean {
        val msg = (e.message ?: "").lowercase()
        return msg.contains("permission denied") || msg.contains("permission_denied")
    }

    suspend fun getRejectedOperations(): List<PendingOperation> = dao.getRejectedOperations()
    fun getRejectedCountFlow(): Flow<Int> = dao.getRejectedCountFlow()
    // Alias penamaan konsisten dgn observePendingCount()/observeFailedCount()
    // yang sudah dipakai OfflineRepository & SyncStatusBlock.
    fun observeRejectedCount(): Flow<Int> = dao.getRejectedCountFlow()
    suspend fun getRejectedCount(): Int = dao.getRejectedCount()

    /** Buang permanen op REJECTED (aksi sadar user setelah diberi penjelasan). */
    suspend fun discardRejectedOperations(): Int = withContext(Dispatchers.IO) {
        val n = dao.discardRejected()
        Log.d(TAG, "🗑️ Buang $n op REJECTED dari antrean")
        n
    }

    /**
     * Kembalikan seluruh op REJECTED ke antrean untuk dicoba lagi.
     *
     * Dipakai setelah sebab penolakannya diperbaiki di aplikasi — mis.
     * kesalahan klasifikasi yang menandai token kedaluwarsa sebagai penolakan
     * permanen. Aman diulang: setiap operasi idempoten (`client_op_id` UNIQUE
     * untuk pembayaran, `onConflict=id` untuk nasabah), jadi yang terlanjur
     * masuk tidak akan tercatat dua kali.
     */
    suspend fun requeueRejectedOperations(): Int = withContext(Dispatchers.IO) {
        val n = dao.requeueRejected()
        Log.w(TAG, "♻️ $n op REJECTED dikembalikan ke antrean")
        if (n > 0) syncPendingOperations()
        n
    }

    /**
     * REPAIR SEMANTIK untuk op warisan APK lama yang ditolak rules.
     *
     * Kasus yang ditangani: UPDATE_PELANGGAN dgn `status="Lunas"` TANPA marker
     * `statusLunasUntukPinjamanKe` (ditulis APK pra-FIX B). Rules menolaknya
     * karena tidak bisa dibuktikan milik generasi pinjaman mana.
     *
     * Kita TIDAK menambal marker secara buta (itu = membuka lagi lubang
     * ghost-write). Sebaliknya kita PUTUSKAN DARI DATA SERVER:
     *   - baca node pelanggan apa adanya di server,
     *   - hitung ulang totalDibayar (exclude entri "Bunga...", konsisten CF),
     *   - hanya bila nasabah MEMANG sudah lunas pada generasi yang SEKARANG
     *     aktif → tulis "Lunas" + marker generasi server yang benar.
     *   - bila belum lunas → op itu memang ghost basi → return false → REJECTED.
     *
     * Return true bila berhasil direpair & tertulis ke server.
     */
    private suspend fun tryRepairRejectedOperation(operation: PendingOperation): Boolean {
        if (operation.operationType != "UPDATE_PELANGGAN") return false
        return try {
            @Suppress("UNCHECKED_CAST")
            val payload = (gson.fromJson(operation.dataJson, Map::class.java) as? Map<String, Any?>)
                ?: return false
            if (payload["status"]?.toString() != "Lunas") return false
            if (payload.containsKey("statusLunasUntukPinjamanKe")) return false // sudah ber-marker → penolakan bukan krn ini

            val snap = firebase.getReference(operation.firebasePath).get().await()
            if (!snap.exists()) {
                Log.w(TAG, "🔧 Repair batal: node pelanggan tidak ada lagi (${operation.firebasePath})")
                return false
            }
            val serverPinjamanKe = snap.child("pinjamanKe").getValue(Int::class.java) ?: 1
            val totalPelunasan = snap.child("totalPelunasan").getValue(Long::class.java) ?: 0L
            if (totalPelunasan <= 0L) return false

            var totalDibayar = 0L
            snap.child("pembayaranList").children.forEach { p ->
                val tgl = p.child("tanggal").getValue(String::class.java) ?: ""
                if (tgl.startsWith("Bunga")) return@forEach
                totalDibayar += p.child("jumlah").getValue(Long::class.java) ?: 0L
                p.child("subPembayaran").children.forEach { s ->
                    totalDibayar += s.child("jumlah").getValue(Long::class.java) ?: 0L
                }
            }

            if (totalDibayar < totalPelunasan) {
                Log.w(TAG, "🚫 Repair ditolak: server BELUM lunas (dibayar=$totalDibayar < pelunasan=$totalPelunasan) → op basi/ghost")
                return false
            }

            // Benar-benar lunas pada generasi server saat ini → tulis dgn marker sah.
            val repaired = payload.filterKeys { it != "_guardPinjamanKe" } +
                mapOf("statusLunasUntukPinjamanKe" to serverPinjamanKe)
            firebase.getReference(operation.firebasePath).updateChildren(repaired).await()
            Log.d(TAG, "🔧 Repair sukses: Lunas sah utk pinjamanKe=$serverPinjamanKe (${operation.firebasePath})")
            true
        } catch (ex: Exception) {
            Log.e(TAG, "🔧 Repair gagal: ${ex.message}")
            false
        }
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