# 021 — Evakuasi Penuh dari Firebase

> **CUTOFF: Selasa, 1 September 2026.** Hari ini Rabu, 26 Agustus 2026.
> **Sisa 6 hari kalender — 4 hari kerja.**
> Firebase Auth + RTDB + Functions + Hosting suspend. **Tidak ada rollback.**
> Yang belum pindah pada hari itu tidak akan hidup lagi.

Perencanaan + backend. Tidak ada kode web/Android yang saya ubah. Nomor baris
dari pembacaan repo.

---

## 0. Kalender, dan apa yang tidak muat

| | Tanggal | Hari | Isi |
|---|---|---|---|
| **D-6** | 26 Agu | Rabu | pastikan host · mulai ekspor · jalankan 022+023 |
| **D-5** | 27 Agu | Kamis | rk.html pindah host · klien+login Supabase |
| **D-4** | 28 Agu | Jumat | alihkan 13 fungsi · kasir · absensi semua peran |
| **D-3** | 29 Agu | **Sabtu** | Android v2 |
| **D-2** | 30 Agu | **Minggu** | sebar APK · password staf |
| **D-1** | 31 Agu | Senin | uji beku |
| **D-0** | 1 Sep | Selasa | **cutoff** |

**D-3 dan D-2 jatuh di akhir pekan.** Kalau tidak ada yang bekerja Sabtu–Minggu,
sisa waktu sesungguhnya **4 hari kerja**, dan Android v2 terdorong ke Senin —
menyisakan nol hari untuk uji beku. Ini yang harus diputuskan hari ini juga,
bukan Jumat sore.

### Yang saya nilai TIDAK MUAT, dan sarannya

**Ekspor Firebase Storage (foto KTP bertahun-tahun) tidak akan selesai dalam
6 hari** bila dikerjakan sambil mengerjakan yang lain. Ini tiang paling
panjang dan paling sering diremehkan.

Saran: **jalankan ekspornya sebagai proses terpisah mulai hari ini, jangan
jadikan syarat cutoff.** Foto KTP tidak menghalangi orang bekerja pada D-0 —
yang menghalangi adalah login, pencatatan, dan rekening koran. Kalau Storage
belum selesai pada D-0, yang hilang adalah arsip foto, bukan kemampuan
beroperasi. **Tetapi ia hilang permanen**, jadi mulai sekarang dan pantau
tiap hari.

Prioritas kalau waktu habis, berurutan:
1. Login (tanpa ini tidak ada yang bisa bekerja)
2. Ekspor RTDB penuh (tidak bisa diulang setelah suspend)
3. Pencatatan: operasional, absensi, kasir
4. Rekening koran
5. Storage
6. Android v2 — **kalau tidak selesai, staf lapangan pindah ke web sementara**

Poin 6 itu jalan keluar yang tersedia dan sebaiknya disiapkan sejak sekarang:
web berjalan di browser HP. Ia tidak menggantikan APK (tidak ada mode
offline, tidak ada scan KTP), tetapi ia menahan agar orang tidak berhenti
bekerja.

---

## 1. Yang mati, dan apa akibatnya

| Layanan | Dipakai untuk | Akibat |
|---|---|---|
| **Auth** | login web (`lib/firebase.js:26`) & Android | tidak ada yang bisa masuk |
| **RTDB** | data operasional Android; `operasional_harian` + `absensi` di web | Android buta; kasir tidak bisa mencatat |
| **Functions** | 13 fungsi `lib/api.js` | dashboard gagal memuat |
| **Hosting** | `public/rk.html` | **halaman rekening koran hilang** |

### 1.1 Hosting — divalidasi dari repo

**`rk.html` MEMANG di Firebase Hosting.** `firebase.json` →
`"hosting": {"public": "public"}`, dan `public/` hanya berisi `rk.html`.
Android menunjuknya di `RekeningKoranHelper.kt:37`:

```kotlin
private const val BASE_URL = "https://koperasikitagodangulu.web.app/rk.html"
```

Jadi benar: **halamannya sendiri harus pindah host; mengganti konstanta API
saja tidak cukup.**

Dan karena host itu **hardcoded di APK**, setelah Hosting mati seluruh tautan
buatan APK lama menunjuk alamat yang tidak ada — tidak bisa ditolong
redirect, karena redirect-nya pun harus tinggal di Hosting yang mati.
**Tautan rekening koran yang sudah beredar hilang permanen.** Itu sebabnya
pembuat tautan sisi-server (§3.3) tidak bisa ditunda.

**`buku-pokok-web` kemungkinan besar BUKAN di Firebase Hosting:**

- `firebase.json` hanya punya satu target hosting, isinya `public/`;
- `next.config.js` tanpa `output: 'export'` → butuh runtime Node, yang
  Firebase Hosting statis tidak sediakan;
- origin-nya `https://www.koperasi-kita.com`
  (`functions/generateAutoLoginToken.js:17`) — domain kustom, bukan `*.web.app`;
- tidak ada `vercel.json`, `netlify.toml`, `Dockerfile`, atau CI di repo.

> **PASTIKAN HARI INI — bukti di atas tak-langsung.** Buka dasbor penyedia
> `www.koperasi-kita.com`. **Kalau ternyata Firebase Hosting, dashboard ikut
> mati** dan pemindahan hostnya naik ke prioritas tertinggi. Ini satu-satunya
> hal yang bisa membalik seluruh jadwal, dan repo tidak bisa menjawabnya.

---

## 2. Peta ketergantungan web

| # | Lokasi | Ketergantungan | Pengganti |
|---|---|---|---|
| W-1 | `lib/firebase.js:9-30` | Auth + Storage + RTDB | `lib/supabase.js` |
| W-2 | `lib/api.js:8` | `BASE_URL` Cloud Functions | PostgREST + RPC |
| W-3 | `lib/api.js:19,55` | `getIdToken()` | sesi Supabase |
| W-4 | `lib/api.js:81-171` | 13 fungsi | view + RPC Tahap B |
| W-5 | `kasir/page.js:3796` | tulis RTDB `operasional_harian` | `rpc_catat_operasional_harian` (022) |
| W-6 | `kasir/page.js:3702` | baca RTDB `operasional_harian` | `select` (policy 016a) |
| W-7 | `kasir/page.js:3758-3759` | tulis RTDB `absensi` ×2 | `rpc_catat_absensi` (023) |
| W-8 | `kasir/page.js:3682,3690` | baca RTDB `absensi` | `v_absensi_hari_ini` (023) |
| W-9 | `kasir/page.js:11` | Firebase Storage (nota) | Supabase Storage (003) |
| W-10 | `public/rk.html:392` | URL Cloud Function | Edge Function `rekening-koran` |
| W-11 | `public/rk.html` (host) | Firebase Hosting | **Supabase Storage** (§7) |

### 13 fungsi `lib/api.js`

| Fungsi | Baris | Pengganti |
|---|---|---|
| `getSummary` | 81 | view B-1/B-2 |
| `getBukuPokok` | 88 | `v_buku_pokok` |
| `getBukuPokokSummary` | — | `v_buku_pokok_summary` |
| `getPembayaranHariIni` | 98 | `v_pembayaran_hari_ini` |
| `getKasirSummary` | 109 | view kasir B-3.3 |
| `getKasirEntries` | 116 | `v_kasir_entry` |
| `addKasirEntry` | 123 | `rpc_tambah_kasir_entry` |
| `deleteKasirEntry` | 130 | `rpc_hapus_kasir_entry` |
| `syncOperasionalTransport` | 137 | `rpc_sync_operasional_transport` |
| `getJurnalTransaksi` | 148 | view jurnal B-3 |
| `backfillJurnalTransaksi` | 155 | **HAPUS TOMBOLNYA** (keputusan pemilik) |
| `getKoreksiStorting` | 162 | view koreksi B-3 |
| `setKoreksiStorting` | 169 | `rpc_set_koreksi_storting` |

`backfillJurnalTransaksi`: alat sesekali, bukan fitur harian. Tombolnya
dihapus dari web; kalau nanti dibutuhkan, dibuat RPC baru. **Catat di CHANGELOG
bahwa ia sengaja ditinggalkan**, supaya enam bulan lagi tidak dikira hilang
karena kelalaian migrasi.

---

## 3. Backend yang hilang — sudah selesai

| Berkas | Isi | Membuka |
|---|---|---|
| `022_operasional_tulis.sql` | `app_user.legacy_uid` + `rpc_catat_operasional_harian` | W-5 |
| `023_absensi.sql` | tabel `absensi`, `v_absensi_hari_ini`, `rpc_catat_absensi(p_cabang_id)` | W-7, W-8 |
| `supabase/functions/rekening-koran-link/` | pembuat tautan v2 sisi server | ketergantungan APK |

### 3.1 `022` — jembatan identitas

`operasional_harian` ber-PK `(cabang_id, tanggal, legacy_uid)`, dan
`legacy_uid` itu **UID Firebase**. Sesudah Firebase mati klien hanya punya
uuid Supabase — dan `app_user` **tidak punya kolom** yang memetakan keduanya.
Tanpa jembatan itu, baris pasca-evakuasi memakai identitas berbeda dari baris
warisan untuk orang yang sama, dan riwayat satu staf terbelah dua.

`022` menambah `app_user.legacy_uid` + backfill dari dua sumber yang sudah
memuat pasangannya (`operasional_harian.user_id`+`legacy_uid`,
`nasabah.admin_id`+`legacy_admin_uid`); uuidv5 tidak bisa dibalik, jadi ini
satu-satunya jalan.

### 3.2 `023` — dan satu cacat yang ditemukan keputusan Anda

Keputusan "koordinator harus bisa absen di web" **membongkar versi pertama
`rpc_catat_absensi`**, dan cacatnya nyata:

`001a:127` membolehkan `cabang_id` NULL untuk `pengawas`, `koordinator`, dan
`sekretaris`. Versi pertama menolak siapa pun tanpa cabang dengan
`23514 'User tidak memiliki cabang'` — jadi **koordinator tidak akan bisa
absen sama sekali**, dan itu baru ketahuan saat orangnya mencoba, kemungkinan
besar pada D-0.

Sudah diperbaiki: `rpc_catat_absensi(p_cabang_id text default null)`. Peran
tanpa cabang **wajib menyebut** di cabang mana ia hadir, dibatasi
`cabang_terlihat_arr()`. Peran bercabang tetap tidak bisa memindahkan diri ke
cabang lain.

> Ujilah dengan **JWT koordinator sungguhan**. Menguji hanya dengan akun
> kasir akan lulus dan tetap menyembunyikan cacat ini.

### 3.3 `rekening-koran-link`

Staf minta, server yang menandatangani. Kunci tidak pernah masuk bundel web;
host tautan dari `REKENING_KORAN_BASE_URL`, bisa diganti tanpa rilis. Ada
pemeriksaan wewenang atas nasabahnya — tanpa itu, kebocoran hanya pindah dari
"menempa token" ke "meminta token".

---

## 4. Checklist D-6 → D-0

### D-6 · Rabu 26 Agu — pastikan & selamatkan

- [ ] **Pastikan host `www.koperasi-kita.com`** (§1.1). Paling dulu; ia bisa
      membalik seluruh jadwal.
- [ ] **Mulai ekspor Firebase Storage** sebagai proses latar. Jangan tunggu
      selesai.
- [ ] **Ekspor RTDB penuh**, simpan di dua tempat. Termasuk node yang belum
      tersentuh migrasi: `absensi`, `user_absensi_today`, `operasional_harian`
      terbaru, `fcm_tokens`, `broadcast_messages`, `location_tracking`,
      `user_locations`, `device_presence`.
- [ ] Jalankan `022`. Periksa hasil backfill:
      `select count(*) filter (where legacy_uid is null) from koperasi.app_user where aktif;`
- [ ] Jalankan `023`. Uji `rpc_catat_absensi` dengan JWT **kasir, admin,
      pimpinan, DAN koordinator** (§3.2).
- [ ] Impor riwayat `absensi` (pola `migrate_operasional_harian.js`).
- [ ] Uji rantai penuh operasional: `rpc_catat_operasional_harian` →
      `rpc_sync_operasional_transport` → periksa entri kasir (`022` §VERIFIKASI).
- [ ] Daftar seluruh akun staf yang harus bisa login (§5).

### D-5 · Kamis 27 Agu — rk.html & fondasi web

- [ ] Buat bucket publik Supabase, unggah `rk.html` (§7).
- [ ] Ubah `rk.html:392` → URL Edge Function `rekening-koran`.
- [ ] Deploy `rekening-koran-link`; set `REKENING_KORAN_BASE_URL` = URL
      bucket.
- [ ] Uji tautan v2 ujung-ke-ujung dari HP di jaringan seluler.
- [ ] `npm i @supabase/supabase-js`; `lib/supabase.js` (URL + anon key dari
      `NEXT_PUBLIC_*`, **bukan literal** — `014` §6.4).
- [ ] Ganti login ke `signInWithPassword` (§5). Firebase Auth masih terpasang
      hari ini; jangan cabut dulu.
- [ ] Pengawas bangkitkan password awal untuk seluruh staf.

### D-4 · Jumat 28 Agu — alihkan web

Hari kerja terakhir sebelum akhir pekan. Selesaikan yang menghalangi orang
bekerja.

- [ ] Alihkan 13 fungsi `lib/api.js` (§2), satu per satu, **bandingkan
      angkanya terhadap Cloud Functions selagi keduanya masih hidup** —
      setelah D-0 tidak ada pembanding lagi.
- [ ] Hapus tombol `backfillJurnalTransaksi`.
- [ ] Alihkan W-5, W-6 (operasional) — **sekaligus, tanpa tulis-ganda**.
- [ ] Alihkan W-7, W-8 (absensi).
- [ ] **Tambah UI absensi untuk admin/pimpinan/koordinator** — bukan hanya
      halaman kasir. Koordinator butuh pemilih cabang (§3.2).
- [ ] Tambah tombol "Salin Tautan Rekening Koran".
- [ ] Alihkan W-9 (nota kasir) ke Supabase Storage.
- [ ] Uji dengan **tiga peran**; bandingkan jumlah baris dengan
      `Prefer: count=exact` supaya tidak tertipu paginasi (`018`).

### D-3 · Sabtu 29 Agu — Android v2

- [ ] Bangun APK v2 (§8). **Kalau tidak ada yang bekerja Sabtu, geser ke
      Senin dan terima bahwa uji beku hilang** — putuskan hari ini.
- [ ] Uji internal di satu perangkat.

### D-2 · Minggu 30 Agu — sebar

- [ ] Sebar APK lewat WhatsApp/USB. **Jangan andalkan Play Store review.**
- [ ] Lacak adopsi **per orang**, bukan per rilis.
- [ ] Serahkan password awal ke tiap staf; pastikan mereka berhasil login
      **sebelum** D-0, bukan pada D-0.

### D-1 · Senin 31 Agu — uji beku

- [ ] **Matikan Firebase secara sengaja** di satu perangkat uji dan di satu
      sesi web (cabut config). Lihat apa yang patah **selagi masih bisa
      dihidupkan lagi.** Ini satu-satunya latihan yang mungkin.
- [ ] Satu hari kerja penuh di Supabase saja: absen, catat operasional, sync,
      entri kasir, buka buku pokok, buat tautan rekening koran.
- [ ] Bekukan perubahan non-darurat.
- [ ] Ekspor RTDB **sekali lagi** — menangkap perubahan sejak D-6.

### D-0 · Selasa 1 Sep — cutoff

- [ ] Pastikan ekspor RTDB & Storage tersimpan aman.
- [ ] Hapus `firebase` dari `buku-pokok-web/package.json`; hapus
      `lib/firebase.js`; `grep -rn "firebase" buku-pokok-web/` harus bersih
      kecuali komentar.
- [ ] Arsipkan `functions/` dan `public/` di repo — **jangan dihapus**
      (aturan repo; keduanya rujukan perilaku lama).
- [ ] Siapkan nomor kontak untuk staf yang terkendala hari itu.

---

## 5. Login web & password awal

`signInWithPassword` ke akun `@godangulu.com` hasil migrasi.

**Domain `@godangulu.com` fiktif** — tidak ada kotak surat, jadi setiap alur
"kirim tautan reset" mustahil (`008` §0). Password awal:

1. **Pengawas** membangkitkan password acak per staf lewat Edge Function
   `user-management` — ditampilkan **di layar**, dicatat, diserahkan langsung.
2. Staf login, lalu **wajib ganti password** di pemakaian pertama.
3. Lupa password: pengawas membangkitkan ulang. Tidak ada pemulihan mandiri —
   konsekuensi domain fiktif.

**Jangan satu password sama untuk semua staf**, sekalipun sementara. `013` §4
memakai satu password bersama untuk tiga akun uji; itu dapat diterima untuk
pengujian, **tidak** untuk seluruh staf produksi tanpa jaring pengaman.

> Password tidak ditulis di repo, termasuk di berkas ini — yang ter-commit
> tetap ada di riwayat git selamanya (`006` §3.6).

**Uji login tiap staf pada D-2, bukan D-0.** Akun yang gagal login pada hari
cutoff tidak punya jalan keluar.

---

## 6. `rk.html` → Supabase Storage (Opsi A, Rp 0)

`rk.html` satu berkas statis tanpa build.

```bash
# bucket publik
supabase storage create rk-public --public          # atau lewat dasbor
supabase storage cp public/rk.html ss:///rk-public/rk.html \
  --content-type "text/html; charset=utf-8"
```

URL-nya:
`https://<ref>.supabase.co/storage/v1/object/public/rk-public/rk.html`
→ masuk ke `REKENING_KORAN_BASE_URL`.

Tiga hal yang perlu diperiksa saat mengunggah:

1. **`Content-Type` harus `text/html`.** Kalau terunggah sebagai
   `application/octet-stream`, browser mengunduhnya alih-alih menampilkan.
2. **Query string harus lolos.** `rk.html:400` membaca `?t=`; pastikan URL
   bucket menerimanya (seharusnya ya — Storage mengabaikan query tak dikenal).
3. **Uji dari HP di jaringan seluler**, bukan hanya desktop. Tautan ini
   dibuka nasabah lewat WhatsApp.

Kalau §1.1 ternyata menunjukkan web **bukan** di Firebase, alternatif yang
lebih rapi: taruh `rk.html` di `buku-pokok-web/public/rk.html` → tersaji di
`/rk.html` pada domain yang sudah dipakai. Nol vendor baru, nol URL baru.
Keduanya sama-sama gratis; pilih setelah §1.1 terjawab.

---

## 7. Lingkup minimal Android v2

Hanya yang membuat APK **tetap berfungsi**. Bukan kesempatan merapikan hal
lain — `PelangganViewModel.kt` (16k baris) tidak disentuh selain jalur
transportnya.

| # | Lingkup | Berkas | Kenapa wajib |
|---|---|---|---|
| A-1 | **Auth Supabase** ganti Firebase Auth | `SupabaseClientProvider.kt` (ada), layar login | tanpa ini tidak ada yang masuk |
| A-2 | **Tulis operasional** → `rpc_catat_operasional_harian` | layar operasional | RTDB mati |
| A-3 | **Absensi** → `rpc_catat_absensi`, `v_absensi_hari_ini` | `AbsensiScreen.kt` | RTDB mati |
| A-4 | **Tautan rekening koran** → panggil `rekening-koran-link` | `RekeningKoranHelper.kt` | host lama mati; kunci tidak boleh lagi di APK |
| A-5 | **Sisa jalur sync** → Supabase | `SyncManager.kt`, `SupabaseSyncHandler.kt` (ada) | disiapkan Milestone 3; tinggal alihkan sakelar |

Pada A-4, `RekeningKoranHelper.kt` berubah dari **pembuat** tanda tangan
menjadi **peminta** tautan: `SECRET_KEY` (`:34`) dan `BASE_URL` (`:37`)
**dihapus dari APK**. Itu menutup `014` §6.4 secara permanen — kunci tidak
lagi ada di berkas yang bisa dibaca siapa pun.

**Boleh ditinggalkan di v2:** FCM, tracking GPS, mode offline penuh. Penting,
tetapi tidak menghalangi orang bekerja pada D-0.

**Kalau v2 tidak selesai:** staf lapangan pakai web di browser HP sementara
(§0). Siapkan instruksinya sekarang, jangan disusun pada D-0.

---

## 8. Risiko terbesar

1. **Waktu.** 4 hari kerja. Satu keterlambatan tidak punya penyangga.
   Putuskan hari ini apakah akhir pekan dipakai.
2. **Tidak ada rollback.** Karena itu D-1 (uji beku dengan Firebase sengaja
   dimatikan) bukan formalitas.
3. **Adopsi APK.** Staf yang tidak memperbarui buta total. Lacak per orang.
4. **Storage tidak akan selesai** (§0). Jalankan sebagai proses terpisah;
   jangan biarkan ia menyandera cutoff, tetapi jangan pula dilupakan — ia
   hilang permanen.
5. **Host web belum dipastikan** (§1.1). Satu-satunya yang bisa membalik
   jadwal. Jawab hari ini.
6. **Password.** Kalau pembagiannya baru dilakukan D-0, hari itu habis untuk
   melayani orang yang tidak bisa masuk, bukan untuk bekerja.

---

## 9. Catatan kejujuran

- `022` dan `023` belum pernah dijalankan; tidak ada PostgreSQL di sisi saya.
- `rekening-koran-link/index.ts` belum pernah dijalankan Deno, belum di-deploy.
- Kesimpulan bahwa `buku-pokok-web` bukan di Firebase Hosting berdasar bukti
  **tak-langsung** (§1.1) — repo tidak memuat konfigurasi deploy web sama
  sekali. Pastikan manual.
- Penilaian "Storage tidak akan muat 6 hari" adalah **perkiraan saya**, bukan
  pengukuran: saya tidak tahu volume bucketnya. Ukur hari ini; kalau ternyata
  kecil, ia naik prioritas.
