# Migrasi User Management — Cloud Functions → Edge Function

Milestone 4. **Belum di-deploy, belum dijalankan, belum dikompilasi.**

Berkas: `supabase/functions/user-management/index.ts`,
`app/.../UserManagementApi.kt`, dan 5 callsite di `PelangganViewModel.kt`.

---

## 1. Bentuk yang Dipilih: Satu Function, Lima Aksi

Lima callable Firebase (`functions/resetUserPassword.js`) menjadi **satu**
Edge Function dengan dispatcher `action`.

Alasannya bukan kerapian. Kelima callable berbagi dua hal yang paling mudah
salah kalau ditulis lima kali: pemeriksaan "pemanggil adalah Pengawas" dan
penulisan audit log. Satu salinan berarti satu tempat untuk diperiksa saat
audit, dan satu tempat yang harus benar.

| Callable lama | `action` | Baris asal |
|---|---|---|
| `resetUserPassword` | `resetUserPassword` | `resetUserPassword.js:22` |
| `getAllUsers` | `getAllUsers` | `:174` |
| `createNewUser` | `createNewUser` | `:343` |
| `deleteExistingUser` | `deleteExistingUser` | `:520` |
| `getAllCabang` | `getAllCabang` | `:668` |

---

## 2. Prasyarat SQL — tabel audit

`006` §8.3 mencatat `password_reset_logs` belum punya padanan. DDL-nya ada di
berkas tersendiri supaya bisa dijalankan langsung di SQL Editor seperti
`001`/`002`/`007`:

> **`009_password_reset_log.sql`**
> Urutan jalankan keseluruhan: `001` → `001a` → `002` → `007` → `009`

**Jalankan sebelum `supabase functions deploy user-management`.** Tanpa
tabelnya, Edge Function tetap berjalan tetapi audit log **gagal ditulis
diam-diam** — `catatAudit()` sengaja tidak menggagalkan operasi (paritas
dengan perilaku Cloud Function lama), jadi kegagalannya hanya muncul di log
Edge, bukan di layar Pengawas.

Dua hal dari isi berkas itu yang perlu diketahui saat membaca kode Edge
Function:

- `target_id` dan `reset_by` **nullable** — percobaan yang gagal karena target
  tidak ditemukan tetap wajib tercatat, dan pada saat itu belum ada id.
- **Tidak ada policy maupun GRANT tulis** untuk `authenticated`. Penulisan
  hanya lewat `service_role` di Edge Function. Bukti yang bisa diubah
  pelakunya bukan bukti.

---

## 3. Perilaku yang Dipertahankan 1:1

Ketiga hal yang Anda tandai wajib, beserta letaknya di kode baru:

| Perilaku | Asal | Implementasi baru |
|---|---|---|
| Pengawas tidak boleh mereset Pengawas lain | `:73-79` | `resetUserPassword()` — cek `target.role === 'pengawas'` → `permission-denied`, dan percobaannya **ikut tercatat** di audit log |
| Pemutusan sesi setelah reset | `:99` `revokeRefreshTokens` | `admin.auth.admin.signOut(target.id, 'global')` |
| Audit log | `:104, :117, :146` | `catatAudit()`, dipanggil pada jalur sukses **dan** gagal |

Satu hal yang **tidak** dibawa: penulisan `force_logout/{uid}` di RTDB
(`:103-107`). Lihat §6 — ini perbedaan perilaku nyata, bukan kelalaian.

### Perbedaan sumber wewenang

Cloud Function membaca `metadata/roles/pengawas/{uid}` dari RTDB (`:37`).
Edge Function membaca `koperasi.app_user.role`. Sumbernya berbeda karena RTDB
bukan lagi sumber kebenaran; **keputusannya identik**.

Konsekuensi praktis: seseorang yang perannya `pengawas` di RTDB tetapi belum
`pengawas` di `app_user` akan kehilangan akses. Periksa keduanya cocok
sebelum memindahkan sakelar:

```sql
select id, email, role from koperasi.app_user where role = 'pengawas';
```

---

## 4. Transport di Android

`UserManagementApi.panggil()` menggantikan `functions.getHttpsCallable(...)`
di lima tempat. Diff di `PelangganViewModel.kt`: **+8/−30**, seluruhnya
transport — tidak ada satu baris logika bisnis yang berubah.

Kuncinya, adapter mengembalikan `Map<String, Any>?` dengan bentuk **persis
sama** seperti `HttpsCallableResult.data`, jadi semua pembacaan di bawahnya
(`data?.get("success") == true`, `data["users"] as? List<Map<String, Any>>`,
pemetaan ke `UserInfo`/`CabangInfo`) berjalan tanpa perubahan.

### Auth context (tugas 3)

JWT sesi yang sedang login dilampirkan **otomatis** oleh SDK — lihat
`Functions.kt:63` di supabase-kt 2.2.3: *"The authorization token is
automatically added to the request"*. Edge Function memakainya untuk
`auth.getUser(jwt)` → id pemanggil → cek peran → isi kolom `reset_by` di
audit log. Tidak ada token yang dirakit manual.

### Kenapa galat dilempar, bukan dikembalikan

ViewModel memilih pesan Indonesia dengan **mencocokkan string kode** pada
`e.message` (`:16358-16363`, `:16414-16419`, `:16460-16464`). Kalau kegagalan
dikembalikan sebagai `success=false`, alur jatuh ke cabang generik
("Gagal mengubah password") dan pesan spesifik dari server hilang.

Karena itu Edge Function memakai kosakata kode yang sama persis
(`permission-denied`, `not-found`, `invalid-argument`, `already-exists`), dan
adapter melempar `Exception("<kode>: <pesan>")`. Pengalaman pengguna di layar
Pengawas tidak berubah.

---

## 5. Urutan Deploy

```bash
# 1. Tabel audit (§2) di SQL Editor.

# 2. Deploy Edge Function.
supabase functions deploy user-management

# 3. Uji dengan JWT pengawas ASLI — jangan service_role.
curl -X POST "https://<ref>.supabase.co/functions/v1/user-management" \
  -H "Authorization: Bearer <JWT_PENGAWAS>" \
  -H "Content-Type: application/json" \
  -d '{"action":"getAllUsers"}'

# 4. Uji penolakan: JWT admin biasa harus dapat 403 permission-denied.

# 5. Baru pindahkan sakelar di aplikasi (SyncBackend → SUPABASE).
```

Langkah 4 bukan formalitas. Fungsi ini berjalan dengan `service_role` yang
**mem-bypass RLS sepenuhnya** — satu-satunya yang menahan admin biasa adalah
pemeriksaan peran di `wewenangPengawas()`. Kalau pemeriksaan itu salah, tidak
ada lapisan kedua.

`SyncBackend` mengendalikan transport ini juga, jadi sinkronisasi data dan
user management berpindah bersamaan. Itu disengaja: dua sakelar terpisah
berarti keadaan setengah-pindah yang sulit dijelaskan saat ada masalah.

---

## 6. Perbedaan Perilaku yang Harus Diketahui

**6.1 `force_logout` RTDB tidak ditulis.**
Cloud Function lama menulis `force_logout/{uid}` (`:103-107`), dan Android
mendengarkan node itu (`PelangganViewModel.kt:733` `forceLogoutListener`)
untuk memaksa user keluar dari layar. Edge Function memutus sesi di sisi
Supabase (`signOut global`) — itu efek keamanan yang sebenarnya — tetapi
**tidak** menyentuh RTDB.

Akibatnya, sampai listener force-logout ikut dimigrasikan: user yang
passwordnya direset tidak akan langsung terlempar keluar dari aplikasi yang
sedang terbuka. Ia baru tertolak saat token Supabase-nya dipakai lagi.
Secara keamanan tetap tertutup; secara pengalaman berbeda. Perlu diputuskan
apakah listener itu masuk milestone berikutnya.

**6.2 `deleteExistingUser` menonaktifkan, bukan menghapus baris.**
`nasabah.admin_id`, `pembayaran.dicatat_oleh`, dan `jurnal_transaksi`
menunjuk ke `app_user`. Menghapus barisnya memutus atribusi seluruh riwayat
keuangan yang pernah dicatat orang itu. Jadi: `aktif = false` + akun Auth
dihapus. Efek praktisnya sama (tidak bisa login, hilang dari daftar aktif),
riwayatnya utuh.

**6.3 `createNewUser` menolak role `pengawas`.**
Layar User Management tidak pernah membuat pengawas, dan menambah pengawas
berarti menambah orang yang bisa mereset seluruh staf. Ditutup eksplisit.

**6.4 Empat callable lain belum dipindahkan.**
`generateTakeoverToken`, `restorePimpinanSession`, `triggerTargetRecalc`,
`updateAllSummaries` masih memakai `getHttpsCallable` dan tetap berjalan di
Firebase. Tidak disentuh milestone ini.

---

## 7. Rollback

Cloud Functions lama **tidak dihapus** dan tetap ter-deploy. Rollback =
kembalikan `SyncBackend` ke `FIREBASE`; `UserManagementApi` otomatis memakai
kembali `getHttpsCallable` dengan instance `FirebaseFunctions` yang sama
seperti sebelumnya. Tidak ada rilis APK yang diperlukan.

---

## 8. Batas Jujur

- Edge Function **belum pernah di-deploy maupun dijalankan**; tidak ada Deno
  atau instance Supabase di environment tempat ini ditulis.
- Kode Kotlin **belum dikompilasi** (Gradle masih terblokir di sini). Titik
  paling mungkin bermasalah: pemanggilan `functions(function, body, headers)`
  — overload-nya sudah diverifikasi dari sources jar 2.2.3, tetapi belum
  pernah dilewati compiler.
- Bentuk `users[]` dari `getAllUsers` kini berasal dari `app_user`, bukan
  gabungan `metadata/admins` + `metadata/cabang` + `metadata/roles` seperti
  versi lama. Jumlah dan urutan baris bisa berbeda bila ketiga sumber RTDB
  itu tidak konsisten satu sama lain — bandingkan hasil kedua jalur sebelum
  memindahkan sakelar.
