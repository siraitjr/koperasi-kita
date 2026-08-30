package com.example.koperasikitagodangulu

import android.content.Context
import android.util.Log
import com.example.koperasikitagodangulu.offline.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    // Tag terpisah supaya jembatan bisa disaring sendiri: `adb logcat -s BRIDGE`
    private const val TAG_JEMBATAN = "BRIDGE"
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

        // Jembatan RTDB — lihat penjelasan panjang di `jembatanRtdb()`.
        jembatanRtdb(email, sandi)

        return p
    }

    // =====================================================================
    // JEMBATAN RTDB — kenapa login Supabase saja membuat dasbor kosong
    // =====================================================================
    /**
     * Status upaya masuk Firebase pendamping. Dibaca layar login untuk
     * memberi tahu penguji apa yang sebenarnya terjadi.
     */
    enum class StatusJembatan {
        BELUM, BERHASIL, GAGAL_SANDI, GAGAL_LAIN, TIDAK_PERLU,
        /** Sesi Supabase ada, sesi Firebase tidak — harus login ulang sekali. */
        PERLU_LOGIN_ULANG,
    }

    @Volatile var statusJembatan: StatusJembatan = StatusJembatan.BELUM
        private set

    /**
     * Masuk ke Firebase Auth SEKALIGUS, memakai kredensial yang sama.
     *
     * KENAPA INI ADA — DAN KENAPA MENGGANTI 141 PEMANGGILAN UID SAJA TIDAK
     * AKAN PERNAH MEMPERBAIKI DASBOR KOSONG:
     *
     * Lapisan data Android masih sepenuhnya RTDB — 360 pemanggilan RTDB di
     * PelangganViewModel.kt saja, dan `SupabaseDataSource` belum tersambung
     * ke ViewModel mana pun. Setiap aturan RTDB diawali `auth != null`
     * (81 kemunculan di data/rulesfirebase.txt), dan aturan `pelanggan`
     * bahkan menuntut `auth.uid === $adminUid` (baris 5).
     *
     * `auth` di sana adalah token FIREBASE. Tanpa sesi Firebase, `auth`
     * bernilai null dan SETIAP pembacaan ditolak — berapa pun benarnya string
     * UID yang dikirim kode Kotlin. Jadi dasbor kosong itu bukan gejala UID
     * null; UID null hanyalah gejala kedua yang kebetulan muncul bersamaan.
     * Sapuan UID tetap perlu dan sudah dikerjakan, tetapi ia memperbaiki
     * PATH-nya, bukan IZIN-nya.
     *
     * Selama Firebase masih hidup (sampai 1 Sep 2026), masuk ke keduanya
     * adalah satu-satunya cara membuat dasbor bekerja tanpa memindahkan
     * seluruh lapisan data lebih dulu.
     *
     * ⚠ INI JEMBATAN SEMENTARA, BUKAN RANCANGAN AKHIR. Ia mati sendiri
     *   pada 1 Sep 2026 bersama RTDB. Sesudah itu dasbor hanya bisa hidup
     *   kalau lapisan bacanya sudah pindah ke Supabase.
     *
     * Kegagalannya TIDAK menggagalkan login: sesi Supabase tetap sah, dan
     * layar login melaporkan apa adanya lewat `statusJembatan` supaya
     * hasilnya terbaca, bukan jadi layar kosong tanpa sebab.
     */
    private suspend fun jembatanRtdb(email: String, sandi: String) {
        if (!pakaiSupabase) { statusJembatan = StatusJembatan.TIDAK_PERLU; return }

        Log.d(TAG_JEMBATAN, "── Memulai jembatan RTDB ──")
        Log.d(TAG_JEMBATAN, "   UID Supabase : ${profil?.uidSupabase}")
        Log.d(TAG_JEMBATAN, "   UID legacy   : ${profil?.uidLegacy}  ← yang dipakai path RTDB")
        Log.d(TAG_JEMBATAN, "   Email        : ${email.trim()}")

        // Firebase SDK harus benar-benar terinisialisasi sebelum ini dipanggil.
        // Diperiksa eksplisit supaya kegagalan inisialisasi tidak menyamar
        // sebagai kegagalan kata sandi.
        val app = runCatching { com.google.firebase.FirebaseApp.getInstance() }.getOrNull()
        if (app == null) {
            Log.e(TAG_JEMBATAN, "❌ FirebaseApp belum terinisialisasi — jembatan dibatalkan.")
            statusJembatan = StatusJembatan.GAGAL_LAIN
            return
        }
        Log.d(TAG_JEMBATAN, "   FirebaseApp  : ${app.name} ✓")

        // CATATAN: signInAnonymously() TIDAK bisa dipakai di sini. Aturan RTDB
        // membandingkan `auth.uid === $adminUid` (rulesfirebase.txt:5) dan
        // memeriksa `metadata/roles/{peran}/{auth.uid}`. UID anonim itu acak,
        // jadi ia lolos `auth != null` tetapi tetap ditolak di node yang
        // penting — sambil meninggalkan akun anonim sampah di project. Yang
        // dibutuhkan adalah sesi Firebase milik AKUN YANG SAMA.
        Log.d(TAG_JEMBATAN, "   Mencoba signInWithEmailAndPassword ke Firebase…")

        statusJembatan = try {
            val hasil = com.google.firebase.auth.FirebaseAuth.getInstance()
                .signInWithEmailAndPassword(email.trim(), sandi)
                .await()
            val uidFb = hasil.user?.uid
            Log.d(TAG_JEMBATAN, "✅ Firebase signIn BERHASIL — uid=$uidFb")

            // Kalau UID Firebase tidak sama dengan legacy_uid, seluruh path
            // RTDB yang dibangun dari legacy_uid menunjuk ke milik orang lain
            // dan aturan akan menolaknya. Lebih baik ketahuan di sini.
            val legacy = profil?.uidLegacy
            if (!legacy.isNullOrBlank() && uidFb != null && legacy != uidFb) {
                Log.e(TAG_JEMBATAN, "❌ TIDAK COCOK: legacy_uid=$legacy tetapi uid Firebase=$uidFb")
                Log.e(TAG_JEMBATAN, "   app_user.legacy_uid untuk akun ini salah isi (lihat 022).")
            }
            StatusJembatan.BERHASIL
        } catch (e: Exception) {
            val pesan = e.message.orEmpty()
            Log.e(TAG_JEMBATAN, "❌ Firebase signIn GAGAL: ${e.javaClass.simpleName}: $pesan")
            // Kata sandi Firebase dan Supabase bisa berbeda — kata sandi
            // Supabase diseragamkan saat migrasi, kata sandi Firebase tidak.
            // Dibedakan supaya pesannya bisa menyebut sebab yang benar.
            if (pesan.contains("password", true) ||
                pesan.contains("credential", true) ||
                pesan.contains("no user record", true)
            ) {
                Log.e(TAG_JEMBATAN, "   → Sebab: kata sandi Firebase berbeda dari yang diketik.")
                StatusJembatan.GAGAL_SANDI
            } else {
                StatusJembatan.GAGAL_LAIN
            }
        }
        Log.d(TAG_JEMBATAN, "── Jembatan selesai: $statusJembatan ──")
    }

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

                // =========================================================
                // GERBANG JEMBATAN — kenapa di sini `false`, bukan `true`
                // =========================================================
                // Sesi Firebase bertahan sendiri lintas pembukaan aplikasi,
                // jadi normalnya masih ada di sini. Kalau hilang, RTDB akan
                // menolak SEMUA pembacaan (`auth != null` di 81 aturan), dan
                // dasbor pasti kosong.
                //
                // Versi sebelumnya mendeteksi keadaan ini, mencatat peringatan,
                // lalu tetap mengembalikan `true` — mengantar pengguna ke
                // dasbor yang sudah dipastikan tidak bisa memuat apa pun.
                // Itulah yang terlihat di logcat 20:32:39: peringatan saya
                // sendiri, disusul belasan "Permission denied".
                //
                // Jembatan TIDAK BISA dijalankan ulang di sini: ia butuh kata
                // sandi, dan kata sandi sengaja tidak pernah disimpan (lihat
                // bagian "Ingat saya"). Menyimpannya demi kemudahan ini berarti
                // menaruh sandi seluruh staf dalam teks biasa di perangkat yang
                // bisa hilang atau dipinjam — harga yang jauh lebih mahal
                // daripada satu kali mengetik ulang sandi.
                //
                // Maka gerbangnya ditutup: kembalikan `false` supaya layar
                // login muncul. Sekali pengguna masuk, `masuk()` menjalankan
                // jembatan, Firebase menyimpan sesinya sendiri ke disk, dan
                // pembukaan-pembukaan berikutnya lolos lewat cabang BERHASIL
                // di atas tanpa perlu mengetik lagi.
                if (firebaseUser == null) {
                    Log.w(TAG_JEMBATAN, "❌ Sesi Supabase pulih, TETAPI sesi Firebase tidak ada.")
                    Log.w(TAG_JEMBATAN, "   RTDB menolak tanpa token Firebase → dasbor pasti kosong.")
                    Log.w(TAG_JEMBATAN, "   Jembatan tidak bisa diulang tanpa kata sandi (tidak disimpan).")
                    Log.w(TAG_JEMBATAN, "   → Meminta login ulang sekali untuk membangun KEDUA sesi.")
                    statusJembatan = StatusJembatan.PERLU_LOGIN_ULANG
                    profil = null
                    // Sesi Supabase ikut dibuang supaya tidak tertinggal
                    // keadaan setengah masuk yang membingungkan di percobaan
                    // berikutnya. `masuk()` akan membangun keduanya dari nol.
                    runCatching { klien.auth.signOut() }
                    bersihkan(context)
                    return false
                }

                Log.d(TAG_JEMBATAN, "✅ Sesi Supabase & Firebase dua-duanya ada (uid=${firebaseUser?.uid})")
                statusJembatan = StatusJembatan.BERHASIL
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
        runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().signOut() }
            .onFailure { Log.e(TAG, "signOut Firebase gagal: ${it.message}") }

        if (!pakaiSupabase) return

        // Keadaan dalam memori dibersihkan SEKARANG (sinkron), supaya tidak ada
        // celah waktu di mana layar sudah pindah ke login tetapi `uid()` masih
        // mengembalikan nilai. Panggilan jaringan menyusul di latar.
        profil = null
        bersihkan(context)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { SupabaseClientProvider.client().auth.signOut() }
                .onFailure { Log.e(TAG, "signOut Supabase gagal: ${it.message}") }
            SupabaseClientProvider.reset()
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
