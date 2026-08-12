package com.example.koperasikitagodangulu.offline

import android.content.Context
import android.util.Log

/**
 * =========================================================================
 * SAKELAR TUJUAN SINKRONISASI — Firebase RTDB atau Supabase
 * =========================================================================
 * Milestone 3. Batasan Anda: "biarkan kode Firebase lama ada dulu, jangan
 * hapus total sampai sync baru stabil." Jadi penggantian dilakukan sebagai
 * PERCABANGAN, bukan penghapusan: seluruh jalur RTDB di SyncManager tetap
 * utuh baris demi baris, dan jalur Supabase berjalan di sebelahnya.
 *
 * Default: FIREBASE. Aplikasi yang dipasang admin lapangan hari ini
 * berperilaku persis seperti sekarang sampai sakelar ini dipindahkan dengan
 * sadar.
 *
 * KENAPA SharedPreferences, BUKAN BuildConfig
 * -------------------------------------------
 * Cutover perlu bisa DIBATALKAN tanpa merilis APK baru. Kalau sakelarnya
 * konstanta build, rollback berarti menunggu seluruh perangkat memasang
 * versi lama — padahal saat itu antrean berisi pembayaran yang belum
 * tersinkron (rollback_plan.md §3.4).
 *
 * ⚠ SATU ARAH DALAM SATU SESI ANTREAN. Memindahkan sakelar saat antrean
 *   Room masih berisi operasi berarti operasi tersebut diputar ke tujuan
 *   yang berbeda dari saat ia dibuat. Itu aman untuk operasi idempoten
 *   (semua sudah idempoten di kedua sisi), TETAPI tetap: kosongkan antrean
 *   dulu sebelum memindahkan sakelar. Cek lewat `getPendingCount()`.
 * =========================================================================
 */
object SyncBackend {

    private const val TAG = "SyncBackend"
    private const val PREFS = "sync_backend_prefs"
    private const val KEY = "backend"

    enum class Tujuan { FIREBASE, SUPABASE }

    @Volatile
    private var cache: Tujuan? = null

    fun aktif(context: Context): Tujuan {
        cache?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nama = prefs.getString(KEY, Tujuan.FIREBASE.name) ?: Tujuan.FIREBASE.name
        val hasil = runCatching { Tujuan.valueOf(nama) }.getOrDefault(Tujuan.FIREBASE)

        // Konfigurasi belum diisi → paksa Firebase. Lebih baik tetap di jalur
        // lama daripada gagal total karena SUPABASE_URL kosong.
        val efektif = if (hasil == Tujuan.SUPABASE && !SupabaseClientProvider.isConfigured) {
            Log.w(TAG, "⚠️ Sakelar=SUPABASE tapi endpoint belum dikonfigurasi → jatuh ke FIREBASE")
            Tujuan.FIREBASE
        } else hasil

        cache = efektif
        return efektif
    }

    fun setAktif(context: Context, tujuan: Tujuan) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, tujuan.name).apply()
        cache = null // baca ulang, sekalian lewati validasi konfigurasi
        Log.w(TAG, "🔀 Tujuan sinkronisasi dipindah ke $tujuan")
    }

    fun pakaiSupabase(context: Context): Boolean = aktif(context) == Tujuan.SUPABASE
}
