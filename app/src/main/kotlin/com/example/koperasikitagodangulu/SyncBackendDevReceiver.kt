package com.example.koperasikitagodangulu.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.koperasikitagodangulu.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * =========================================================================
 * SAKELAR BACKEND UNTUK PERANGKAT TEST (developer switch)
 * =========================================================================
 * Memindahkan tujuan sinkronisasi antara Firebase dan Supabase pada SATU
 * perangkat, tanpa merilis APK dan tanpa menyentuh UI produksi.
 *
 * KENAPA BUKAN MENU TERSEMBUNYI DI DALAM APLIKASI
 * -----------------------------------------------
 * Aplikasi ini dipakai admin lapangan setiap hari. Gerakan rahasia (mis.
 * tap 7x pada suatu label) bisa TIDAK SENGAJA teraktivasi, dan akibatnya
 * bukan sekadar tampilan aneh: seluruh tulisan admin tersebut akan menuju
 * database Supabase yang masih kosong, sementara ia mengira datanya tersimpan.
 * Kerusakannya baru ketahuan berhari-hari kemudian.
 *
 * Karena itu sakelarnya butuh akses fisik/USB, dan komponennya DIDAFTARKAN
 * HANYA DI `app/src/debug/AndroidManifest.xml` — pada APK release, receiver
 * ini tidak ada di manifest sama sekali sehingga tidak bisa dipanggil siapa
 * pun. Penjaga `BuildConfig.DEBUG` di bawah adalah lapis kedua.
 *
 * PEMAKAIAN (perangkat tersambung ADB, pasang APK debug)
 * ------------------------------------------------------
 *   # Lihat keadaan sekarang + sisa antrean
 *   adb shell am broadcast \
 *     -a com.example.koperasikitagodangulu.DEV_SYNC_BACKEND \
 *     --es cmd status \
 *     -n com.example.koperasikitagodangulu/.offline.SyncBackendDevReceiver
 *
 *   # Pindah ke Supabase
 *   adb shell am broadcast \
 *     -a com.example.koperasikitagodangulu.DEV_SYNC_BACKEND \
 *     --es cmd set --es backend SUPABASE \
 *     -n com.example.koperasikitagodangulu/.offline.SyncBackendDevReceiver
 *
 *   # Kembali ke Firebase (rollback)
 *   adb shell am broadcast \
 *     -a com.example.koperasikitagodangulu.DEV_SYNC_BACKEND \
 *     --es cmd set --es backend FIREBASE \
 *     -n com.example.koperasikitagodangulu/.offline.SyncBackendDevReceiver
 *
 * Hasilnya dibaca di logcat:  adb logcat -s DevSyncBackend
 * =========================================================================
 */
class SyncBackendDevReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DevSyncBackend"
        const val ACTION = "com.example.koperasikitagodangulu.DEV_SYNC_BACKEND"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Lapis kedua. Manifest debug sudah menjadi lapis pertama.
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "⛔ Ditolak: hanya tersedia pada build debug.")
            return
        }
        if (intent.action != ACTION) return

        val app = context.applicationContext
        val cmd = intent.getStringExtra("cmd")?.lowercase() ?: "status"

        when (cmd) {
            "status" -> laporkan(app, "STATUS")

            "set" -> {
                val diminta = intent.getStringExtra("backend")?.uppercase()
                val tujuan = runCatching { SyncBackend.Tujuan.valueOf(diminta ?: "") }.getOrNull()
                if (tujuan == null) {
                    Log.e(TAG, "❌ backend tidak dikenal: '$diminta' (pakai FIREBASE atau SUPABASE)")
                    return
                }

                /* Antrean WAJIB kosong sebelum berpindah.
                 * Operasi yang dibuat saat Firebase lalu diputar ke Supabase
                 * memang idempoten di kedua sisi, tetapi tujuannya berbeda dari
                 * saat ia dibuat — dan khusus SERAH_TERIMA belum didukung jalur
                 * Supabase sehingga akan langsung ditandai REJECTED.
                 * Diperiksa di sini supaya kesalahan itu tidak mungkin terjadi
                 * karena lupa. Paksa dengan --ez force true bila memang sengaja. */
                val paksa = intent.getBooleanExtra("force", false)
                CoroutineScope(Dispatchers.IO).launch {
                    val sisa = runCatching {
                        SyncManager.getInstance(app).getPendingCount()
                    }.getOrDefault(-1)

                    if (sisa > 0 && !paksa) {
                        Log.e(TAG, "⛔ Batal: masih ada $sisa operasi di antrean.")
                        Log.e(TAG, "   Sinkronkan dulu sampai 0, atau tambahkan --ez force true.")
                        laporkan(app, "TIDAK BERUBAH")
                        return@launch
                    }

                    SyncBackend.setAktif(app, tujuan)
                    Log.w(TAG, "🔀 Tujuan sinkronisasi dipindah → $tujuan (antrean=$sisa)")
                    laporkan(app, "SESUDAH")
                }
            }

            else -> Log.e(TAG, "❌ cmd tidak dikenal: '$cmd' (pakai status atau set)")
        }
    }

    private fun laporkan(app: Context, label: String) {
        val aktif = SyncBackend.aktif(app)
        Log.w(TAG, "=== $label ===")
        Log.w(TAG, "  backend aktif   : $aktif")
        Log.w(TAG, "  supabase siap   : ${SupabaseClientProvider.isConfigured}")
        CoroutineScope(Dispatchers.IO).launch {
            val sisa = runCatching { SyncManager.getInstance(app).getPendingCount() }.getOrDefault(-1)
            Log.w(TAG, "  antrean tersisa : $sisa")
        }
    }
}
