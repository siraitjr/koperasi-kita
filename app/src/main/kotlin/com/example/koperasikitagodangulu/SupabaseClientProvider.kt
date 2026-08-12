package com.example.koperasikitagodangulu.offline

import android.util.Log
import com.example.koperasikitagodangulu.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

/**
 * =========================================================================
 * SUPABASE CLIENT — singleton, dibuat malas (lazy)
 * =========================================================================
 * Milestone 2. Lapisan ini BELUM di-wire ke SyncManager; jalur produksi
 * masih sepenuhnya lewat Firebase (SyncManager.kt, 29 pemanggilan RTDB SDK).
 *
 * CATATAN OPERASIONAL YANG MUDAH TERLEWAT
 * ---------------------------------------
 * Seluruh tabel berada di schema `koperasi`, BUKAN `public`
 * (001_schema_v2.sql baris pertama). PostgREST hanya mengekspos schema yang
 * terdaftar. Jadi sebelum satu pun query di sini berhasil:
 *
 *   Supabase Dashboard → Settings → API → Exposed schemas
 *   → tambahkan `koperasi`
 *
 * Tanpa itu setiap request balik 404 "relation does not exist", yang mudah
 * disalahartikan sebagai tabelnya belum dibuat.
 *
 * `defaultSchema` di bawah menghindari keharusan menulis nama schema di
 * setiap pemanggilan.
 * =========================================================================
 */
object SupabaseClientProvider {

    private const val TAG = "SupabaseClient"
    const val SCHEMA = "koperasi"

    /**
     * true bila endpoint sudah dikonfigurasi. Dipakai pemanggil untuk
     * memutuskan apakah jalur Supabase boleh dipakai sama sekali — supaya
     * build tanpa konfigurasi tidak crash, hanya menonaktifkan fitur.
     */
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
                BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    @Volatile
    private var INSTANCE: SupabaseClient? = null

    fun client(): SupabaseClient {
        check(isConfigured) {
            "SUPABASE_URL / SUPABASE_ANON_KEY belum diisi. " +
                "Set di ~/.gradle/gradle.properties (lihat app/build.gradle.kts)."
        }
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: buat().also {
                INSTANCE = it
                Log.d(TAG, "✅ SupabaseClient dibuat (schema=$SCHEMA)")
            }
        }
    }

    private fun buat(): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        // Auth wajib: seluruh tabel ber-RLS. Tanpa JWT pengguna, PostgREST
        // mengeksekusi query sebagai `anon` yang tidak punya policy apa pun
        // (002_rls_policies.sql §0) → hasil selalu kosong, bukan error jelas.
        install(Auth)
        install(Postgrest) {
            defaultSchema = SCHEMA
        }
        install(Storage)
        install(Realtime)
    }

    /** Untuk logout / ganti akun: paksa klien dibuat ulang. */
    fun reset() {
        synchronized(this) { INSTANCE = null }
        Log.d(TAG, "♻️ SupabaseClient di-reset")
    }
}
