package com.example.koperasikitagodangulu

import android.content.Context
import android.util.Log
import com.example.koperasikitagodangulu.offline.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * =========================================================================
 * SESI AKTIF — lapisan tunggal identitas pengguna (Android v2, A-1)
 * =========================================================================
 * Menggantikan `FirebaseAuth` sebagai GERBANG LOGIN. Firebase TIDAK dihapus:
 * lihat `AUTH_SUPABASE` di bawah.
 *
 * KENAPA ADA LAPISAN INI, BUKAN LANGSUNG MEMANGGIL supabase.auth
 * -------------------------------------------------------------------------
 * Ada 141 pemanggilan `auth.currentUser?.uid` (hitungan per 30 Agu 2026), dan
 * hampir semuanya membangun PATH RTDB (`pelanggan/{adminUid}/…`) atau kunci
 * warisan. Nilai yang mereka butuhkan adalah **UID Firebase**, bukan uuid
 * Supabase — dua ruang id yang berbeda.
 *
 * Sebarannya TIDAK merata, dan ini kabar baik untuk sapuannya:
 *
 *     PelangganViewModel.kt           120   (85%)
 *     TambahPelangganScreen.kt          3
 *     MainActivity.kt                   3
 *     LocationTrackingService.kt        3
 *     AuthScreen.kt                     3   (jalur cadangan Firebase)
 *     NotificationHelper.kt             2
 *     LocationTrackingMonitor.kt        2
 *     MyFirebaseMessagingService.kt     1
 *     LocationCheckWorker.kt            1
 *     HolidayUpdateWorker.kt            1
 *     FirebaseConnectionKeeperService.kt 1
 *     BootReceiver.kt                   1
 *
 * Artinya sapuan itu pada dasarnya adalah menyentuh SATU berkas, bukan 27.
 *
 * `SesiAktif.uid()` mengembalikan UID Firebase itu, diambil dari
 * `app_user.legacy_uid` (kolom yang ditambahkan 022 justru untuk jembatan
 * ini). Jadi pemanggil lama bisa berpindah ke sini satu baris per tempat,
 * tanpa mengubah satu pun path.
 *
 * `uidSupabase()` mengembalikan uuid untuk jalur Supabase (RPC, RLS).
 *
 * ⚠ BELUM SELESAI, DAN INI HARUS DISADARI SEBELUM MENYALAKAN FLAG DI PRODUKSI:
 *   berkas-berkas di atas MASIH memanggil `auth.currentUser?.uid` langsung.
 *   Dengan AUTH_SUPABASE menyala, Firebase tidak pernah di-sign-in, jadi
 *   panggilan itu mengembalikan null.
 *
 *   Yang membuatnya berbahaya: hampir semuanya berpola `?: return` — mis.
 *   PelangganViewModel.kt:16581 (startSingleDeviceSession), :16495
 *   (startForceLogoutListener), :7730 (startRemoteTakeoverListener). Mereka
 *   TIDAK melempar galat; mereka diam saja dan tidak melakukan apa-apa. Jadi
 *   gejalanya adalah layar kosong dan sesi single-device yang tidak aktif,
 *   BUKAN pesan kesalahan yang menunjuk sebabnya.
 *
 *   Sapuannya sengaja dipisah ke commit tersendiri supaya bisa ditinjau dan
 *   diuji terpisah dari perubahan login. Sampai itu selesai, flag ini hanya
 *   untuk build uji.
 * =========================================================================
 */
object SesiAktif {

    private const val TAG = "SesiAktif"
    // `adb logcat -s KELUAR` untuk menelusuri alur logout langkah demi langkah.
    private const val TAG_KELUAR = "KELUAR"
    private const val PREFS = "user_prefs"

    // Kunci "ingat saya" — disimpan di SharedPreferences yang sama dengan
    // `saveUserRole` (UserRole.kt:15) supaya seluruh keadaan sesi berada di
    // satu tempat, bukan tersebar.
    private const val K_INGAT = "sesi_ingat_saya"
    private const val K_EMAIL = "sesi_email"
    private const val K_UID_LEGACY = "sesi_uid_legacy"
    private const val K_UID_SB = "sesi_uid_supabase"
    private const val K_CABANG = "sesi_cabang_id"
    private const val K_NAMA = "sesi_nama"

    /**
     * SAKELAR CUT-OVER. Sengaja `false` — build produksi TIDAK berubah
     * perilakunya sampai ini dinyalakan dengan sadar.
     *
     * Nyalakan lewat gradle.properties / env `AUTH_SUPABASE=true`, atau ubah
     * baris ini untuk build uji. Setelah cutover terbukti di perangkat nyata
     * DAN sapuan 27 berkas selesai, nilainya dibalik jadi true dan jalur
     * Firebase di AuthScreen boleh dihapus.
     */
    val pakaiSupabase: Boolean
        get() = BuildConfig.AUTH_SUPABASE && SupabaseClientProvider.isConfigured

    // ---------------------------------------------------------------------
    // Keadaan dalam memori. Diisi saat masuk / pulihkan, dibaca 141 tempat.
    // ---------------------------------------------------------------------
    @Volatile private var profil: Profil? = null

    /**
     * Menandai bahwa logout sedang berjalan tetapi `signOut()` di jaringan
     * BELUM selesai.
     *
     * KENAPA PERLU (logout nyangkut di dasbor kosong)
     * ---------------------------------------------------------------------
     * `keluarSerentak()` membersihkan keadaan memori secara sinkron, lalu
     * memanggil `auth.signOut()` di latar. Pemanggilnya langsung
     * `navigate("auth")`. AuthScreen kemudian menjalankan `pulihkan()` —
     * dan pada saat itu sesi Supabase MASIH ADA di penyimpanan, karena
     * signOut-nya belum selesai. Jadi `pulihkan()` mengembalikan true dan
     * layar login memantulkan pengguna KEMBALI ke dasbor.
     *
     * Dasbornya kosong karena keadaan ViewModel sudah dibersihkan. Itulah
     * gejala "logout tidak langsung ke login screen", dan itu pula sebabnya
     * swipe-close menyembuhkannya: saat aplikasi dibuka lagi, signOut sudah
     * lama selesai.
     *
     * Batas waktu 4 detik di `logoutWithCleanup` tidak menyentuh ini sama
     * sekali — balapannya terjadi SESUDAH callback itu berjalan.
     */
    @Volatile private var sedangKeluar = false

    data class Profil(
        val uidSupabase: String,
        val uidLegacy: String,
        val email: String,
        val nama: String,
        val peran: UserRole,
        val cabangId: String?,
    )

    // Sengaja TIDAK memakai `@Serializable` + `decodeSingleOrNull()`:
    // seluruh berkas repo ini membaca `.data` lalu memarsingnya sendiri
    // (SupabaseDataSource.kt:239-243), dan tidak ada satu pun kelas
    // @Serializable yang sudah terbukti ikut terkompilasi. Memakai jalur yang
    // sudah terbukti berarti berkas ini tidak menjadi tempat pertama sebuah
    // API dicoba — apalagi lingkungan ini tidak bisa mengompilasi untuk
    // membuktikannya (dl.google.com diblokir).
    private val json = Json { ignoreUnknownKeys = true }

    // `contentOrNull` sudah mengembalikan null untuk JsonNull, jadi kolom
    // kosong dan kolom NULL ditangani sama. `trim()` bukan hiasan: data
    // warisan di kolom-kolom ini kerap membawa spasi di belakang, dan
    // legacy_uid berspasi menghasilkan path RTDB yang tidak pernah cocok.
    private fun JsonObject.teks(kunci: String): String? =
        (this[kunci] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------------
    // Pembacaan — inilah yang akan dipakai berkas-berkas di atas
    // ---------------------------------------------------------------------

    /**
     * UID **Firebase** (legacy). Ini yang dibutuhkan path RTDB dan kunci
     * warisan — BUKAN uuid Supabase. Null bila belum masuk.
     */
    // `takeIf { isNotBlank() }`: legacy_uid kosong dikembalikan sebagai NULL,
    // bukan "". Pemanggil di seluruh aplikasi berpola `?: return`, jadi null
    // menghentikan mereka dengan benar — sedangkan "" lolos pemeriksaan itu
    // lalu membangun path `pelanggan//…` yang tampak sah tetapi menunjuk ke
    // seluruh node, bukan ke satu admin.
    fun uid(): String? = profil?.uidLegacy?.takeIf { it.isNotBlank() }

    // =====================================================================
    // AKSESOR NETRAL-BACKEND — pengganti `Firebase.auth.currentUser?.…`
    // =====================================================================
    // Inilah yang dipanggil ratusan tempat setelah sapuan. Kontraknya:
    //
    //   flag MATI  → persis `Firebase.auth.currentUser?.uid`, tanpa selisih
    //   flag NYALA → UID legacy dari app_user.legacy_uid
    //
    // Karena cabang "flag mati" mengembalikan ekspresi yang sama persis
    // dengan yang digantikannya, sapuan ini TIDAK mengubah perilaku build
    // produksi sama sekali. Itu yang membuatnya aman untuk di-merge sebelum
    // cut-over benar-benar dinyalakan.

    private val firebaseUser get() = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser

    fun uidAktif(): String? = if (pakaiSupabase) uid() else firebaseUser?.uid

    fun emailAktif(): String? =
        if (pakaiSupabase) profil?.email?.takeIf { it.isNotBlank() } else firebaseUser?.email

    fun namaAktif(): String? =
        if (pakaiSupabase) profil?.nama?.takeIf { it.isNotBlank() } else firebaseUser?.displayName

    /** Ada sesi yang sah? Pengganti `Firebase.auth.currentUser != null`. */
    fun adaSesi(): Boolean = if (pakaiSupabase) uid() != null else firebaseUser != null

    /**
     * Pengganti OBJEK `Firebase.auth.currentUser`, untuk tempat-tempat yang
     * menyimpannya ke variabel lokal lalu memakai `.uid` / `.email`.
     *
     * Nama propertinya sengaja `uid` dan `email` — sama persis dengan
     * `FirebaseUser`. Dengan begitu deklarasinya saja yang berubah:
     *
     *     val currentUser = Firebase.auth.currentUser      // sebelum
     *     val currentUser = SesiAktif.penggunaAktif()      // sesudah
     *
     * dan seluruh badan fungsi (`currentUser.uid`, `currentUser.email`, serta
     * penjagaan `if (currentUser == null)`) tetap utuh tanpa disentuh. Untuk
     * 35 pemakaian di PelangganViewModel.kt, tidak menyentuh badan fungsi jauh
     * lebih aman daripada mengganti nama variabel di tiap tempat.
     */
    data class PenggunaAktif(val uid: String, val email: String?)

    fun penggunaAktif(): PenggunaAktif? =
        uidAktif()?.let { PenggunaAktif(it, emailAktif()) }

    /** uuid Supabase, untuk RPC dan apa pun yang tunduk RLS. */
    fun uidSupabase(): String? = profil?.uidSupabase

    fun peran(): UserRole = profil?.peran ?: UserRole.UNKNOWN
    fun cabangId(): String? = profil?.cabangId
    fun nama(): String = profil?.nama ?: ""
    fun email(): String = profil?.email ?: ""
    fun sudahMasuk(): Boolean = profil != null

    // ---------------------------------------------------------------------
    // Masuk
    // ---------------------------------------------------------------------

    /**
     * Masuk dengan email + kata sandi Supabase.
     *
     * Melempar Exception berpesan Indonesia agar bisa langsung ditampilkan.
     */
    suspend fun masuk(context: Context, email: String, sandi: String, ingatSaya: Boolean): Profil {
        // Login baru mengakhiri logout mana pun yang masih tercatat berjalan.
        sedangKeluar = false
        val klien = SupabaseClientProvider.client()

        try {
            klien.auth.signInWith(Email) {
                this.email = email.trim().lowercase()
                this.password = sandi
            }
        } catch (e: Exception) {
            val pesan = e.message.orEmpty()
            throw Exception(
                when {
                    // GoTrue menyamakan "email tidak terdaftar" dan "sandi
                    // salah" secara sengaja, supaya tidak membocorkan email
                    // mana yang ada. Jangan dipecah jadi dua pesan.
                    pesan.contains("Invalid login", true) ||
                        pesan.contains("invalid_credentials", true) ->
                        "Email atau kata sandi salah."
                    // Domain @godangulu.com fiktif (008 §0) — tidak ada surel
                    // konfirmasi yang bisa sampai. Kalau ini muncul, akunnya
                    // dibuat tanpa email_confirm dan harus dibetulkan Pengawas.
                    pesan.contains("not confirmed", true) ->
                        "Akun belum aktif. Hubungi Pengawas."
                    pesan.contains("network", true) || pesan.contains("timeout", true) ->
                        "Tidak ada koneksi. Periksa jaringan lalu coba lagi."
                    else -> "Gagal masuk: $pesan"
                }
            )
        }

        val p = muatProfil()
            ?: run {
                // Sesi terbentuk tetapi tidak ada barisnya di app_user, atau
                // akunnya nonaktif. Sesi dibuang supaya tidak tersangkut di
                // keadaan setengah masuk — dan supaya galatnya jelas alih-alih
                // layar kosong.
                runCatching { klien.auth.signOut() }
                throw Exception("Akun ini belum terdaftar sebagai staf, atau sudah dinonaktifkan. Hubungi Pengawas.")
            }

        profil = p
        simpan(context, p, ingatSaya)
        saveUserRole(context, p.peran)          // kompatibilitas: UserRole.kt
        Log.d(TAG, "✅ masuk sebagai ${p.email} (${p.peran})")

        return p
    }

    // =====================================================================
    // JEMBATAN RTDB — DIHAPUS (bukan ditunda)
    // =====================================================================
    // Sempat ada di sini: masuk ke Firebase Auth sekaligus, memakai kredensial
    // yang sama, supaya RTDB tetap terbaca selama lapisan data belum pindah.
    //
    // Jembatan itu TIDAK MUNGKIN bekerja, dan bukan karena bug:
    //   - surel staf (@godangulu.com) fiktif → tidak ada tautan reset yang
    //     bisa sampai;
    //   - Firebase Console project ini tidak menyediakan penyetelan sandi;
    //   - jadi kata sandi Firebase tidak akan pernah bisa disamakan dengan
    //     kata sandi Supabase.
    //
    // UAT membuktikannya: "Firebase signIn GAGAL: password mismatch".
    //
    // Konsekuensinya bukan cuma jembatannya yang dibuang, tetapi juga GERBANG
    // di `pulihkan()` yang dulu menuntut adanya sesi Firebase. Gerbang itu
    // menuntut syarat yang mustahil dipenuhi, sehingga setiap pembukaan
    // aplikasi membuang sesi Supabase yang sebenarnya sehat dan memaksa login
    // ulang — persis Masalah 1 di UAT. Ini menutup butir "FASE 6.1: hapus
    // jembatanRtdb()" lebih awal, karena menahannya berarti menahan bug.
    //
    // RTDB kini memang tidak bisa diakses sama sekali dari aplikasi. Itulah
    // alasan FASE 1-5 ada: setiap jalur data harus pindah ke Supabase.

    /**
     * Baca profil dari `koperasi.app_user` untuk pengguna yang sedang masuk.
     *
     * ⚠ PERAN DIAMBIL DARI DATABASE, bukan dari email dan bukan dari klaim
     *   JWT. `determineUserRole()` lama (AuthScreen.kt:583) menebaknya dari
     *   ISI STRING EMAIL — `email.contains("pengawas")`. Itu berarti siapa
     *   pun yang emailnya memuat kata itu mendapat peran Pengawas di sisi
     *   klien. Wewenang sungguhan memang tetap dijaga RLS di server, tetapi
     *   menebak peran dari email tidak punya alasan untuk dipertahankan.
     */
    private suspend fun muatProfil(): Profil? {
        val klien = SupabaseClientProvider.client()
        val pengguna = klien.auth.currentUserOrNull() ?: return null

        val mentah = try {
            klien.postgrest.from("app_user").select {
                filter { eq("id", pengguna.id) }
            }.data
        } catch (e: Exception) {
            Log.e(TAG, "gagal membaca app_user: ${e.message}")
            return null
        }

        val baris = try {
            json.parseToJsonElement(mentah).jsonArray.firstOrNull()?.jsonObject
        } catch (e: Exception) {
            Log.e(TAG, "app_user bukan JSON yang bisa dibaca: ${e.message}")
            return null
        } ?: return null

        // `aktif` boleh tidak ada; yang ditolak hanya bila TEGAS false.
        val aktif = (baris["aktif"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (!aktif) return null

        return Profil(
            uidSupabase = baris.teks("id") ?: pengguna.id,
            // Tanpa legacy_uid, seluruh path RTDB dan kunci warisan tidak
            // punya nilai yang benar. Dikosongkan alih-alih diisi uuid —
            // uuid di posisi itu akan membuat path yang tampak sah tetapi
            // menunjuk ke tempat yang tidak ada.
            uidLegacy = baris.teks("legacy_uid").orEmpty(),
            email = baris.teks("email").orEmpty(),
            nama = baris.teks("nama").orEmpty(),
            peran = keUserRole(baris.teks("role")),
            cabangId = baris.teks("cabang_id"),
        )
    }

    /** Enum `koperasi.user_role` → `UserRole` Android. */
    private fun keUserRole(role: String?): UserRole = when (role?.trim()?.lowercase()) {
        "pengawas" -> UserRole.PENGAWAS
        "koordinator" -> UserRole.KOORDINATOR
        "pimpinan" -> UserRole.PIMPINAN
        "admin" -> UserRole.ADMIN_LAPANGAN
        // kasir_unit, kasir_wilayah, sekretaris belum punya layar Android
        // sendiri. Dipetakan ke UNKNOWN, BUKAN ke ADMIN_LAPANGAN: memberi
        // mereka layar admin lapangan berarti memberi wewenang yang tidak
        // mereka punya, dan RLS akan menolak setiap tindakannya — pengguna
        // melihat layar yang tidak bisa dipakai tanpa tahu sebabnya.
        else -> UserRole.UNKNOWN
    }

    // ---------------------------------------------------------------------
    // Pulihkan & keluar
    // ---------------------------------------------------------------------

    /**
     * Dipanggil saat aplikasi dibuka. Mengembalikan true bila sesi Supabase
     * masih sah dan profilnya berhasil dimuat.
     *
     * supabase-kt memuat ulang sesi dari penyimpanannya sendiri; yang perlu
     * dilakukan di sini hanya memastikan tokennya masih hidup lalu mengisi
     * ulang profil dari database — sengaja TIDAK dari cache, supaya peran
     * yang dicabut Pengawas langsung berlaku di pembukaan berikutnya alih-alih
     * bertahan sampai kadaluwarsa token.
     */
    suspend fun pulihkan(context: Context): Boolean {
        if (!pakaiSupabase) return false

        // Logout sedang berjalan: jangan pulihkan apa pun, walau sesinya masih
        // terbaca di penyimpanan. Tanpa ini, layar login memantulkan pengguna
        // kembali ke dasbor yang baru saja ditinggalkannya.
        if (sedangKeluar) {
            Log.d(TAG_KELUAR, "↩︎ pulihkan() ditolak: logout masih berjalan")
            profil = null
            return false
        }

        return try {
            val klien = SupabaseClientProvider.client()

            // ⚠ BARIS PALING PENTING DI BERKAS INI.
            //
            // Pemuatan sesi dari penyimpanan berjalan ASINKRON. Tepat setelah
            // klien dibuat, `sessionStatus` masih `SessionStatus.LoadingFromStorage`
            // dan `currentUserOrNull()` mengembalikan null — bukan karena tidak
            // ada sesi, tetapi karena sesinya BELUM SELESAI DIBACA.
            //
            // Tanpa penantian ini, `pulihkan()` di pembukaan aplikasi selalu
            // membaca null, mengembalikan false, dan layar login muncul padahal
            // sesinya tersimpan rapi di SharedPreferences. Itulah Bug 2.
            //
            // Diverifikasi dari gotrue-kt-android-2.2.3.aar:
            //   io.github.jan.supabase.gotrue.Auth.awaitInitialization(...)
            //   io.github.jan.supabase.gotrue.SessionStatus$LoadingFromStorage
            klien.auth.awaitInitialization()

            if (klien.auth.currentUserOrNull() == null) {
                profil = null
                return false
            }
            val p = muatProfil()
            if (p == null) {
                runCatching { klien.auth.signOut() }
                bersihkan(context)
                false
            } else {
                profil = p
                simpan(context, p, ingatSaya(context))
                saveUserRole(context, p.peran)

                // Sesi Supabase yang sah SUDAH CUKUP untuk dianggap masuk.
                //
                // Di sini pernah berdiri gerbang yang menuntut adanya sesi
                // Firebase dan membuang sesi Supabase bila tidak ada. Karena
                // kata sandi Firebase tidak akan pernah bisa disamakan (surel
                // staf fiktif, Console tanpa penyetelan sandi), syarat itu
                // mustahil dipenuhi — jadi gerbangnya memaksa login ulang di
                // SETIAP pembukaan aplikasi sambil membuang sesi yang sehat.
                // Itu Masalah 1 di UAT, dan sebabnya kode ini sendiri.
                Log.d(TAG, "✅ sesi Supabase pulih: ${p.email} (${p.peran})")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "pulihkan gagal: ${e.message}")
            false
        }
    }

    suspend fun keluar(context: Context) {
        runCatching { SupabaseClientProvider.client().auth.signOut() }
            .onFailure { Log.e(TAG, "signOut gagal: ${it.message}") }
        profil = null
        bersihkan(context)
        SupabaseClientProvider.reset()
    }

    /**
     * Keluar dari KEDUA sistem auth, dari konteks non-suspend.
     *
     * Dipakai menggantikan `Firebase.auth.signOut()` di titik-titik tombol
     * Logout. Alasannya konkret: bila hanya Firebase yang di-sign-out sementara
     * flag menyala, sesi Supabase tetap hidup di penyimpanan supabase-kt —
     * pengguna menekan "Keluar", lalu `pulihkan()` memasukkannya kembali pada
     * pembukaan berikutnya. Logout yang tidak me-logout.
     *
     * Firebase TETAP di-sign-out tanpa syarat, apa pun keadaan flag-nya:
     * dengan flag mati inilah satu-satunya yang terjadi, jadi perilaku build
     * produksi persis sama seperti sebelum commit ini.
     */
    fun keluarSerentak(context: Context) {
        Log.d(TAG_KELUAR, "1) keluarSerentak dipanggil (pakaiSupabase=$pakaiSupabase)")
        runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().signOut() }
            .onFailure { Log.e(TAG, "signOut Firebase gagal: ${it.message}") }

        if (!pakaiSupabase) return

        // Ditandai SEBELUM apa pun yang asinkron, dan sebelum pemanggil
        // sempat bernavigasi. Inilah yang menutup balapan.
        sedangKeluar = true

        // Keadaan dalam memori dibersihkan SEKARANG (sinkron), supaya tidak ada
        // celah waktu di mana layar sudah pindah ke login tetapi `uid()` masih
        // mengembalikan nilai. Panggilan jaringan menyusul di latar.
        profil = null
        bersihkan(context)
        Log.d(TAG_KELUAR, "2) keadaan memori dibersihkan, signOut Supabase dimulai di latar")

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                runCatching { SupabaseClientProvider.client().auth.signOut() }
                    .onFailure { Log.e(TAG, "signOut Supabase gagal: ${it.message}") }
                SupabaseClientProvider.reset()
                Log.d(TAG_KELUAR, "3) signOut Supabase selesai, klien di-reset")
            } finally {
                // `finally`: kalau signOut gagal, penanda ini TETAP dilepas.
                // Membiarkannya menyala akan membuat pengguna tidak pernah bisa
                // memulihkan sesi lagi sampai aplikasi dimatikan — menukar satu
                // bug dengan bug yang lebih buruk.
                sedangKeluar = false
                Log.d(TAG_KELUAR, "4) penanda sedangKeluar dilepas")
            }
        }
    }

    // ---------------------------------------------------------------------
    // "Ingat saya"
    // ---------------------------------------------------------------------
    // Yang disimpan HANYA email dan penanda — tidak pernah kata sandi.
    // Sesi sendiri dipegang supabase-kt (refresh token di penyimpanannya).
    // Menyimpan sandi di SharedPreferences berarti sandi seluruh staf ada
    // dalam teks biasa di perangkat yang bisa hilang atau dipinjam.

    fun ingatSaya(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_INGAT, false)

    fun emailTersimpan(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_EMAIL, "").orEmpty()

    private fun simpan(context: Context, p: Profil, ingatSaya: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean(K_INGAT, ingatSaya)
            putString(K_EMAIL, if (ingatSaya) p.email else "")
            putString(K_UID_LEGACY, p.uidLegacy)
            putString(K_UID_SB, p.uidSupabase)
            putString(K_CABANG, p.cabangId.orEmpty())
            putString(K_NAMA, p.nama)
            apply()
        }
    }

    private fun bersihkan(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            remove(K_UID_LEGACY); remove(K_UID_SB); remove(K_CABANG); remove(K_NAMA)
            // K_INGAT & K_EMAIL sengaja DIBIARKAN: "ingat saya" adalah
            // pilihan pengguna atas perangkatnya, bukan bagian dari sesi.
            // Logout tidak seharusnya memaksanya mengetik ulang emailnya.
            apply()
        }
    }
}
