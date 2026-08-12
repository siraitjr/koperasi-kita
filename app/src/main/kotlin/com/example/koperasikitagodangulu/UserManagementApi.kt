package com.example.koperasikitagodangulu.offline

import android.content.Context
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.gson.Gson
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * =========================================================================
 * TRANSPORT USER MANAGEMENT — Cloud Functions ⇄ Edge Function (Milestone 4)
 * =========================================================================
 * HANYA lapisan transport. Lima fungsi bisnis di PelangganViewModel
 * (loadAllUsers, resetUserPassword, createNewUser, deleteExistingUser,
 * loadAllCabang) TIDAK berubah logikanya — yang diganti hanya cara
 * permintaan dikirim dan bentuk hasilnya dikembalikan.
 *
 * KONTRAK YANG DIPERTAHANKAN
 * --------------------------
 * Mengembalikan `Map<String, Any>?` dengan bentuk PERSIS SAMA seperti
 * `HttpsCallableResult.data`, sehingga seluruh kode pembaca di ViewModel
 * (`data?.get("success") == true`, `data["users"] as? List<Map<String, Any>>`,
 * dst.) tetap berjalan tanpa satu baris pun diubah.
 *
 * PENANGANAN GALAT
 * ----------------
 * Jalur Firebase melempar `FirebaseFunctionsException` yang pesannya memuat
 * kode seperti `permission-denied` / `not-found` / `invalid-argument`, dan
 * ViewModel MENCOCOKKAN string itu untuk memilih pesan Indonesia
 * (PelangganViewModel.kt:16358-16363, :16414-16419, :16460-16464).
 * Jalur Supabase karena itu juga MELEMPAR exception dengan pesan
 * `"<kode>: <pesan>"` — bukan mengembalikan null — supaya cabang `catch` di
 * ViewModel berperilaku sama persis.
 *
 * ⚠ BELUM PERNAH DIJALANKAN. Edge Function-nya juga belum di-deploy.
 * =========================================================================
 */
object UserManagementApi {

    private const val TAG = "UserMgmtApi"
    private const val EDGE_FUNCTION = "user-management"
    private val gson = Gson()

    /** Kode galat yang dikenali ViewModel; dipakai untuk menormalkan pesan. */
    private val KODE_DIKENAL = listOf(
        "permission-denied", "not-found", "invalid-argument",
        "already-exists", "unauthenticated", "internal"
    )

    /**
     * @param aksi nama callable lama: getAllUsers | resetUserPassword |
     *             createNewUser | deleteExistingUser | getAllCabang
     * @param payload argumen; kosong untuk aksi tanpa argumen
     * @throws Exception dengan pesan `"<kode>: <pesan>"` bila ditolak server
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun panggil(
        context: Context,
        firebaseFunctions: FirebaseFunctions,
        aksi: String,
        payload: Map<String, Any?> = emptyMap()
    ): Map<String, Any>? {
        return if (SyncBackend.pakaiSupabase(context)) {
            lewatEdgeFunction(aksi, payload)
        } else {
            lewatCloudFunction(firebaseFunctions, aksi, payload)
        }
    }

    // ------------------------------------------------------------ FIREBASE
    /**
     * Jalur lama, dipertahankan apa adanya untuk rollback. Instance
     * FirebaseFunctions sengaja DITERIMA dari pemanggil, bukan dibuat di
     * sini — ViewModel memakai `Firebase.functions` (region default), dan
     * membuat instance baru dengan region berbeda diam-diam akan mengubah
     * perilaku yang sedang berjalan.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun lewatCloudFunction(
        firebaseFunctions: FirebaseFunctions,
        aksi: String,
        payload: Map<String, Any?>
    ): Map<String, Any>? {
        val callable = firebaseFunctions.getHttpsCallable(aksi)
        val result = if (payload.isEmpty()) {
            callable.call().await()
        } else {
            callable.call(HashMap(payload)).await()
        }
        return result.data as? Map<String, Any>
    }

    // ------------------------------------------------------------ SUPABASE
    @Suppress("UNCHECKED_CAST")
    private suspend fun lewatEdgeFunction(
        aksi: String,
        payload: Map<String, Any?>
    ): Map<String, Any>? {
        val body = buildJsonObject {
            put("action", JsonPrimitive(aksi))
            payload.forEach { (k, v) ->
                when (v) {
                    null -> {}
                    is Number -> put(k, JsonPrimitive(v))
                    is Boolean -> put(k, JsonPrimitive(v))
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }

        val teks = try {
            // JWT sesi yang sedang login dilampirkan OTOMATIS oleh SDK
            // ("The authorization token is automatically added to the
            // request" — Functions.kt:63). Itulah yang dipakai Edge Function
            // untuk memverifikasi wewenang Pengawas dan mengisi audit log.
            SupabaseClientProvider.client().functions(
                function = EDGE_FUNCTION,
                body = body,
                headers = Headers.build {
                    append(HttpHeaders.ContentType, "application/json")
                }
            ).bodyAsText()
        } catch (e: Exception) {
            // supabase-kt melempar pada status non-2xx. Pesannya BELUM TENTU
            // memuat badan respons, jadi kode dinormalkan sebisanya; kalau
            // gagal, pesan asli diteruskan (ViewModel punya cabang `else ->
            // e.message` untuk itu).
            throw Exception(normalkanPesan(e.message ?: "Kesalahan jaringan"))
        }

        val map = try {
            gson.fromJson(teks, Map::class.java) as? Map<String, Any>
        } catch (e: Exception) {
            Log.e(TAG, "❌ respons bukan JSON: ${teks.take(200)}")
            throw Exception("internal: respons server tidak dapat dibaca")
        }

        // Kegagalan bisnis dilaporkan sebagai exception, bukan sebagai map
        // dengan success=false — supaya pesan spesifik dari server sampai ke
        // pengguna lewat cabang catch, persis seperti jalur Firebase.
        if (map != null && map["success"] != true) {
            val kode = map["code"] as? String ?: "internal"
            val pesan = map["message"] as? String ?: "Permintaan ditolak"
            Log.w(TAG, "⚠️ $aksi ditolak: $kode — $pesan")
            throw Exception("$kode: $pesan")
        }

        return map
    }

    /** Menyisipkan kode yang dikenali ViewModel bila terlihat di pesan mentah. */
    private fun normalkanPesan(pesan: String): String {
        val kode = KODE_DIKENAL.firstOrNull { pesan.contains(it, ignoreCase = true) }
        return if (kode != null) pesan else "internal: $pesan"
    }
}
