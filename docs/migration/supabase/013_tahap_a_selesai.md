# Tahap A Selesai — Sesi & Impersonasi Berjalan di Supabase

Tanggal: 13 Agustus 2026
Lingkup: `010` §4 Tahap A — tiga fungsi sesi/impersonasi.

Seluruh hasil uji di dokumen ini **dilaporkan pemilik** dari project Supabase
sungguhan. Saya tidak menjalankan satu pun perintah terhadap project itu;
yang saya verifikasi sendiri hanya isi kode (dikutip dengan nomor baris).

---

## 1. Ringkasan

| Komponen | Status |
|---|---|
| `011_session_takeover.sql` — `session_lock`, `force_logout`, `takeover_log` | **Terpasang**, RLS ketat (tanpa hak tulis untuk klien) |
| Edge Function `session-management` | **Live** di Supabase |
| `autoLogin` | **Berhasil** |
| `takeover` | **Berhasil** |
| `restore` | **Berhasil** |
| Efek samping — `session_lock`, `force_logout`, `takeover_log` | **Terverifikasi** di database |
| Uji penolakan (a) admin biasa mencoba takeover | **403** — lulus |
| Uji penolakan (c) tanpa `Authorization` | **401** — lulus |

Ini penggantian pertama yang benar-benar berjalan end-to-end di Supabase:
tiga Cloud Function tergantikan, dan **`createCustomToken` yang tidak ada
padanannya** berhasil disiasati lewat `generateLink` → `verifyOtp`
(`012` §1). Ketiga tabel pendukungnya berdiri dengan aturan akses yang
lebih ketat daripada aslinya di RTDB.

Yang perlu diingat: **produksi belum berpindah.** `app/` belum diubah dan
Cloud Functions lama masih hidup, jadi Android tetap memakai jalur Firebase.
Yang tercapai adalah jalur penggantinya sudah terbukti bekerja.

---

## 2. Dua Selisih Kode Status — dan kenapa saya belum menyebutnya bug

Anda mencatat dua penyimpangan dari spesifikasi `012` §5.4:

| Uji | Diharapkan | Diperoleh |
|---|---|---|
| (b) pimpinan → admin **cabang lain** | 403 `permission-denied` | **404** |
| (d) takeover kedua oleh **pimpinan lain** | 409 `already-exists` | **403** |

Keduanya **menolak operasi tidak sah**, jadi tidak ada dampak keamanan. Tetapi
sebelum dicatat sebagai bug yang harus diperbaiki, ada penjelasan lain yang
lebih cocok dengan kodenya — dan kalau penjelasan itu benar, "memperbaiki"
justru akan merusak perilaku yang sudah benar.

Urutan pemeriksaan di `takeover` (`index.ts`, penanda `(1)`–`(5)`):

```
(1) pemanggil bukan pimpinan          → 403
(2) target tidak ada di app_user      → 404   ← uji (b) berhenti di sini
(3) target bukan role 'admin'         → 403
(4) cabang tidak cocok                → 403   ← uji (d) berhenti di sini
(5) sudah dikunci pimpinan lain       → 409
```

**Uji (b) → 404.** Kode hanya mengembalikan 404 di langkah (2), yaitu ketika
`targetAdminUid` **tidak ditemukan di `koperasi.app_user`**. Kalau yang
dipakai benar-benar uuid admin dari cabang lain, alurnya akan lewat (2) dan
(3) lalu berhenti di (4) dengan 403. Jadi 404 menunjukkan uuid yang diuji
tidak ada di tabel — kemungkinan besar UID Firebase, bukan uuid hasil
migrasi.

**Uji (d) → 403.** Pemeriksaan cabang (4) memang berjalan **sebelum**
pemeriksaan kunci (5). Kalau pimpinan kedua berasal dari cabang yang berbeda
dengan admin target, ia ditolak karena cabang — dan itu **benar**: pimpinan
cabang lain tidak boleh mengambil alih admin tersebut, terkunci atau tidak.
Untuk benar-benar sampai ke 409, **kedua pimpinan harus satu cabang dengan
admin target**.

### Cara memastikan

```sql
-- (b) apakah uuid target benar-benar ada?
select id, nama, role, cabang_id from koperasi.app_user
 where id = '<targetAdminUid yang diuji>';
-- kosong → 404 memang benar; ada → barulah ini bug

-- (d) cari dua pimpinan pada SATU cabang yang sama dengan admin target
select u.id, u.nama, u.role, u.cabang_id
  from koperasi.app_user u
 where u.cabang_id = '<cabang admin target>'
   and u.role in ('pimpinan','admin')
 order by u.role;
```

Kalau (b) ternyata memang memakai uuid yang ada, dan (d) memakai dua pimpinan
secabang tetapi tetap 403 — barulah keduanya bug, dan perbaikannya masuk
bersama `user-management` di tahap berikutnya. Sampai itu terbukti, saya
mencatatnya sebagai **belum terkonfirmasi**, bukan known issue.

---

## 3. Konfigurasi Infrastruktur yang Diaktifkan

Ketiganya diperlukan agar PostgREST — dan karenanya Edge Function — bisa
membaca schema `koperasi`:

1. **Settings → API → Exposed schemas**: `koperasi` dicentang.
2. **GRANT USAGE** pada schema untuk `anon`, `authenticated`, `service_role`.
3. `notify pgrst, 'reload schema'` dijalankan.

Inilah yang menyelesaikan 401 menyesatkan pada deploy pertama (`012` §6b):
JWT-nya selalu sah, yang gagal adalah pembacaan `koperasi.app_user`.

> **Untuk instalasi berikutnya:** ketiga langkah ini **tidak ada** di `001`
> maupun `002`. Siapa pun yang memasang dari nol akan menabrak dinding yang
> sama. Sebaiknya ditambahkan ke `002` sebagai langkah pertama — belum saya
> lakukan karena akan mengubah berkas yang sudah Anda jalankan.

---

## 4. Status 3 Akun Uji

| Akun | Keterangan |
|---|---|
| `pimpinan1@godangulu.com` | password sementara |
| `pimpinan2@godangulu.com` | password sementara |
| `anggun@godangulu.com` | password sementara |

Ketiganya masih memakai **satu password sementara yang sama**, dipakai khusus
untuk pengujian Tahap A. Akan direset saat aplikasi Android sudah dimigrasi.

Password literalnya **sengaja tidak ditulis di berkas ini.** Repo ini ada di
GitHub, dan kredensial yang ter-commit tetap ada di riwayat git selamanya
walau berkasnya dihapus — alasan yang sama seperti pada password Pengawas
(`006` §3.6) dan temuan `SWEEP_SECRET` di baseline checklist §2A. Simpan di
pengelola kata sandi, bukan di repo.

Dua hal yang menyertainya:
- Ketiganya kini bisa login **sungguhan** ke Supabase. Selama password
  sementara masih berlaku, siapa pun yang mengetahuinya bisa memakai akun
  Pimpinan — termasuk memanggil `takeover`.
- Karena itu jangan tunda resetnya sampai migrasi `app/` selesai kalau
  jaraknya panjang; reset lebih awal tidak mengganggu apa pun.

---

## 5. Langkah Berikutnya

**Tahap B — 14 endpoint web → view + RLS.**
Paling ringan dari semua yang tersisa: mayoritas hanya SELECT beragregat,
jadi tidak perlu Edge Function sama sekali — cukup view + RLS, tanpa deploy.
Web memanggilnya lewat `lib/api.js`, satu berkas, sehingga transportnya
terpusat. Gagal pun tidak merusak data karena semuanya baca-saja.

Urutan yang disarankan di dalamnya: `getBukuPokok` dan `getBukuPokokSummary`
lebih dulu (paling sering dipakai, paling cepat terlihat kalau angkanya
meleset), baru Kasir dan Jurnal.

**Sebelum itu — satu langkah yang saya sarankan didahulukan:** bandingkan
angka view pengganti `summary` dengan angka RTDB hari ini. Itu Tahap A
nomor 2 di rencana asli (`010` §4) dan belum dikerjakan. Kalau angkanya tidak
cocok, seluruh Tahap B berdiri di atas fondasi yang salah, dan itu akan jauh
lebih mahal diketahui setelah web dipindahkan.

**Yang menyusul:** konfirmasi dua selisih kode status di §2, lalu
perbaikannya bersama `user-management` bila terbukti bug.

---

## 6. Yang Belum Berubah

- `app/` belum disentuh sama sekali; Android masih memanggil
  `getHttpsCallable("generateTakeoverToken")` dan kawan-kawannya.
- Cloud Functions lama masih ter-deploy dan masih melayani produksi.
- Sisi klien `verifyOtp` (pengganti `signInWithCustomToken`) belum ditulis —
  itu pekerjaan `app/` di tahap mendatang.
- Empat pertanyaan terbuka di `010` §5 masih berlaku, dikurangi satu yang
  sudah Anda putuskan (tombol "Update Summary" dihapus).
