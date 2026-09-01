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
        // ⚠ TIDAK ADA LAGI PILIHAN. Firebase Auth sudah dicabut (4b3a03c),
        // sehingga RTDB tidak bisa ditulis sama sekali — setiap setValue
        // dijawab "Permission denied". Mengembalikan FIREBASE di sini berarti
        // seluruh operasi mengantre selamanya di Room tanpa pernah sampai ke
        // mana pun.
        //
        // Versi sebelumnya masih membaca `BuildConfig.AUTH_SUPABASE`. Itu flag
        // yang sama yang membuat login gagal: APK yang dibangun tanpa
        // `-PAUTH_SUPABASE=true` jatuh ke cabang SharedPreferences di bawah,
        // yang berdefault FIREBASE. Jadi jalur tulis Supabase yang SUDAH ADA
        // sejak FASE 2 tidak pernah terpilih — bukan karena belum dibuat,
        // melainkan karena tidak pernah dipanggil.
        //
        // Sakelar SharedPreferences dan flag build sengaja tidak dibaca lagi:
        // keduanya hanya menyediakan cara untuk salah.
        val efektif = if (SupabaseClientProvider.isConfigured) {
            Tujuan.SUPABASE
        } else {
            // Endpoint kosong bukan alasan menulis ke RTDB (yang pasti gagal),
            // melainkan alasan berhenti. Antrean menahan operasinya.
            Log.e(TAG, "❌ SUPABASE_URL/ANON_KEY belum diisi — tidak ada tujuan tulis yang sah")
            Tujuan.FIREBASE
        }
        cache = efektif
        return efektif
    }

    /**
     * ⚠ TIDAK LAGI BERPENGARUH pada tujuan tulis. Dipertahankan supaya
     * pemanggil lama tetap terkompilasi, tetapi `aktif()` sudah tidak membaca
     * SharedPreferences. Dibiarkan diam-diam "berhasil" akan lebih buruk:
     * seseorang bisa mengira ia sudah memindahkan tujuan padahal tidak.
     */
    fun setAktif(context: Context, tujuan: Tujuan) {
        Log.w(TAG, "⚠️ setAktif($tujuan) diabaikan — tujuan tulis kini selalu Supabase")
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, tujuan.name).apply()
        cache = null // baca ulang, sekalian lewati validasi konfigurasi
        Log.w(TAG, "🔀 Tujuan sinkronisasi dipindah ke $tujuan")
    }

    fun pakaiSupabase(context: Context): Boolean = aktif(context) == Tujuan.SUPABASE
}
