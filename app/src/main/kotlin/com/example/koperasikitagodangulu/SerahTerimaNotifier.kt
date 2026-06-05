package com.example.koperasikitagodangulu.offline

import android.util.Log
import com.example.koperasikitagodangulu.SerahTerimaNotification
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * =========================================================================
 * SERAH TERIMA NOTIFIER — shared & background-safe
 * =========================================================================
 *
 * KENAPA ADA FILE INI:
 *   Jalur ONLINE men-dispatch notifikasi "SERAH_TERIMA" (berisi
 *   fotoSerahTerimaUrl) ke Pimpinan/Pengawas/Koordinator lewat
 *   PelangganViewModel (sendSerahTerimaNotificationToPimpinan / ToPengawas /
 *   ToKoordinator). Jalur OFFLINE sebelumnya TIDAK pernah meng-upload foto
 *   serah terima maupun mengirim notifikasi saat reconnect — atasan tidak
 *   pernah diberi tahu (Audit Point #3).
 *
 *   Class ini me-REPLIKA PERSIS logika & payload notifikasi tersebut (path
 *   RTDB, isi SerahTerimaNotification, teks judul/pesan) supaya SyncManager
 *   bisa memicunya dari background sync — TANPA menyentuh/merefaktor jalur
 *   online yang sudah teruji (CLAUDE.md: jangan ubah logic yang sudah ada).
 *   Dengan begitu notifikasi yang diterima atasan identik, baik dari jalur
 *   online maupun dari hasil sync offline.
 *
 * BACKGROUND-SAFE:
 *   Tidak bergantung pada ViewModel/Context UI — hanya FirebaseDatabase —
 *   sehingga aman dipanggil dari SyncWorker / SyncForegroundService.
 *
 * IDEMPOTEN:
 *   notificationId dibuat DETERMINISTIK dari (pelangganId + tanggalSerahTerima),
 *   jadi kalau operasi sync ter-replay (retry), notifikasi tidak menjadi
 *   ganda — node yang sama hanya ter-overwrite. Ini sekaligus jaminan
 *   "no duplicate" terhadap jalur online (yang mutually-exclusive per
 *   pencairan: admin lapangan klik saat online ATAU offline, tidak keduanya).
 * =========================================================================
 */
class SerahTerimaNotifier(firebase: FirebaseDatabase) {

    private val database = firebase.reference

    companion object {
        private const val TAG = "SerahTerimaNotifier"
        private const val TITLE = "Bukti Serah Terima Uang"
    }

    /**
     * Dispatch ke SEMUA atasan (Pimpinan + Pengawas + Koordinator).
     *
     * Tidak melempar exception ke caller: tiap sub-dispatch menelan errornya
     * sendiri (mengikuti perilaku jalur online yang juga try/catch per fungsi),
     * supaya kegagalan kirim notifikasi tidak menggagalkan operasi sync foto.
     */
    suspend fun dispatchAll(
        adminUid: String,
        pelangganId: String,
        cabangId: String,
        namaPanggilan: String,
        adminName: String,
        besarPinjaman: Int,
        tenor: Int,
        fotoUrl: String,
        tanggalSerahTerima: String
    ) {
        val dedupe = sanitize("${pelangganId}_$tanggalSerahTerima")
        val message = "Admin $adminName telah menyerahkan uang pinjaman kepada $namaPanggilan"

        dispatchToPimpinan(
            cabangId, adminUid, pelangganId, namaPanggilan, adminName,
            besarPinjaman, tenor, fotoUrl, tanggalSerahTerima, message, dedupe
        )
        dispatchToRole(
            roleNode = "pengawas",
            targetNode = "pengawas_serah_terima_notifications",
            idPrefix = "serah_terima_pengawas",
            adminUid, pelangganId, namaPanggilan, adminName,
            besarPinjaman, tenor, fotoUrl, tanggalSerahTerima, message, dedupe
        )
        // Koordinator memakai node notifikasi yang sama dengan pengawas
        // (KoordinatorApprovalScreen membaca pengawas_serah_terima_notifications),
        // persis seperti jalur online (PelangganViewModel.sendSerahTerimaNotificationToKoordinator).
        dispatchToRole(
            roleNode = "koordinator",
            targetNode = "pengawas_serah_terima_notifications",
            idPrefix = "serah_terima_koordinator",
            adminUid, pelangganId, namaPanggilan, adminName,
            besarPinjaman, tenor, fotoUrl, tanggalSerahTerima, message, dedupe
        )
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9]"), "_")

    private fun buildNotification(
        id: String,
        pelangganId: String,
        namaPanggilan: String,
        adminUid: String,
        adminName: String,
        besarPinjaman: Int,
        tenor: Int,
        fotoUrl: String,
        tanggalSerahTerima: String,
        message: String
    ) = SerahTerimaNotification(
        id = id,
        type = "SERAH_TERIMA",
        title = TITLE,
        message = message,
        pelangganId = pelangganId,
        pelangganNama = namaPanggilan,
        adminUid = adminUid,
        adminName = adminName,
        besarPinjaman = besarPinjaman,
        tenor = tenor,
        fotoSerahTerimaUrl = fotoUrl,
        tanggalSerahTerima = tanggalSerahTerima,
        timestamp = System.currentTimeMillis(),
        read = false
    )

    /**
     * Resolusi pimpinanUid dengan 4 fallback path — replika
     * PelangganViewModel.sendSerahTerimaNotificationToPimpinan.
     */
    private suspend fun dispatchToPimpinan(
        cabangId: String,
        adminUid: String,
        pelangganId: String,
        namaPanggilan: String,
        adminName: String,
        besarPinjaman: Int,
        tenor: Int,
        fotoUrl: String,
        tanggalSerahTerima: String,
        message: String,
        dedupe: String
    ) {
        try {
            if (cabangId.isBlank()) {
                Log.e(TAG, "❌ CabangId kosong, tidak bisa kirim notifikasi pimpinan")
                return
            }

            var pimpinanUid: String? = null

            // Path 1: metadata/roles/pimpinan/{cabangId}
            try {
                pimpinanUid = database.child("metadata").child("roles").child("pimpinan")
                    .child(cabangId).get().await().getValue(String::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Path 1 gagal: ${e.message}")
            }

            // Path 2: metadata/cabang/{cabangId}/pimpinan
            if (pimpinanUid.isNullOrBlank()) {
                try {
                    pimpinanUid = database.child("metadata").child("cabang").child(cabangId)
                        .child("pimpinan").get().await().getValue(String::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Path 2 gagal: ${e.message}")
                }
            }

            // Path 3: metadata/cabang/{cabangId}/pimpinanUid
            if (pimpinanUid.isNullOrBlank()) {
                try {
                    pimpinanUid = database.child("metadata").child("cabang").child(cabangId)
                        .child("pimpinanUid").get().await().getValue(String::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Path 3 gagal: ${e.message}")
                }
            }

            // Path 4: iterasi metadata/roles/pimpinan (cocokkan key case-insensitive)
            if (pimpinanUid.isNullOrBlank()) {
                try {
                    val all = database.child("metadata").child("roles").child("pimpinan").get().await()
                    for (child in all.children) {
                        if (child.key?.lowercase() == cabangId.lowercase()) {
                            pimpinanUid = child.getValue(String::class.java)
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Path 4 gagal: ${e.message}")
                }
            }

            if (pimpinanUid.isNullOrBlank()) {
                Log.e(TAG, "❌ Pimpinan tidak ditemukan untuk cabang: $cabangId")
                return
            }

            val notificationId = "serah_terima_$dedupe"
            val notification = buildNotification(
                notificationId, pelangganId, namaPanggilan, adminUid, adminName,
                besarPinjaman, tenor, fotoUrl, tanggalSerahTerima, message
            )
            database.child("serah_terima_notifications").child(pimpinanUid)
                .child(notificationId).setValue(notification).await()
            Log.d(TAG, "✅ Notifikasi serah terima dikirim ke pimpinan: $pimpinanUid")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Gagal kirim notifikasi pimpinan: ${e.message}")
        }
    }

    /**
     * Dispatch ke semua UID pada metadata/roles/{roleNode} — replika
     * sendSerahTerimaNotificationToPengawas / ToKoordinator.
     */
    private suspend fun dispatchToRole(
        roleNode: String,
        targetNode: String,
        idPrefix: String,
        adminUid: String,
        pelangganId: String,
        namaPanggilan: String,
        adminName: String,
        besarPinjaman: Int,
        tenor: Int,
        fotoUrl: String,
        tanggalSerahTerima: String,
        message: String,
        dedupe: String
    ) {
        try {
            val snapshot = database.child("metadata").child("roles").child(roleNode).get().await()
            if (!snapshot.exists()) {
                Log.w(TAG, "⚠️ Tidak ada $roleNode terdaftar")
                return
            }

            val uids = snapshot.children.mapNotNull { it.key }
            for (uid in uids) {
                val notificationId = "${idPrefix}_$dedupe"
                val notification = buildNotification(
                    notificationId, pelangganId, namaPanggilan, adminUid, adminName,
                    besarPinjaman, tenor, fotoUrl, tanggalSerahTerima, message
                )
                database.child(targetNode).child(uid).child(notificationId)
                    .setValue(notification).await()
                Log.d(TAG, "✅ Notifikasi serah terima dikirim ke $roleNode: $uid")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Gagal kirim notifikasi $roleNode: ${e.message}")
        }
    }
}
