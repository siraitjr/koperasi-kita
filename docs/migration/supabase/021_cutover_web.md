# 021 — Evakuasi Penuh dari Firebase (revisi)

> **Revisi total.** Versi pertama dokumen ini menyusun cut-over bertahap
> dengan Firebase sebagai jaring pengaman. **Premis itu batal.** Tagihan
> Firebase tidak dibayar; Auth + RTDB + Functions + Hosting akan suspend.
> **Tidak ada rollback.** Yang belum pindah pada hari-H tidak akan hidup lagi.

**TANGGAL CUTOFF: `[ISI DI SINI]`** — seluruh tanggal di bawah relatif
terhadapnya (D-21, D-14, …). Isi satu kali di baris ini; jangan sebar
salinannya ke berkas lain.

Perencanaan + backend. **Tidak ada kode web/Android yang saya ubah.**
Nomor baris dari pembacaan repo.

---

## 1. Yang mati, dan apa akibatnya

| Layanan Firebase | Dipakai untuk | Akibat saat suspend |
|---|---|---|
| **Auth** | login web (`lib/firebase.js:26`), login Android | tidak ada yang bisa masuk, di mana pun |
| **RTDB** | seluruh data operasional Android; `operasional_harian` + `absensi` di web | Android buta total; kasir tidak bisa mencatat |
| **Functions** | 13 fungsi `lib/api.js` | seluruh dashboard web gagal memuat |
| **Hosting** | `public/rk.html` | **halaman rekening koran hilang** |

### 1.1 Temuan hosting — divalidasi dari repo

**`rk.html` ADA di Firebase Hosting.** `firebase.json` → `"hosting": {"public": "public"}`, dan `public/` hanya berisi `rk.html`. Android
menunjuknya di `RekeningKoranHelper.kt:37`:

```kotlin
private const val BASE_URL = "https://koperasikitagodangulu.web.app/rk.html"
```

Jadi benar: **`rk.html` mati bersama Hosting, dan halamannya sendiri harus
pindah host — mengganti konstanta API saja tidak cukup.** Rencana lama
(langkah 1: ubah satu konstanta) sudah tidak berlaku.

Lebih jauh: host itu **hardcoded di APK**. Setelah Hosting mati, seluruh
tautan buatan APK lama menunjuk ke alamat yang tidak ada — dan tidak bisa
ditolong redirect, karena redirect-nya pun harus tinggal di Hosting yang
mati. **Tautan rekening koran yang sudah beredar hilang permanen.** Itu
alasan pertama pembuat tautan sisi-server (§3.3) tidak bisa ditunda.

**`buku-pokok-web` kemungkinan besar TIDAK di Firebase Hosting.** Buktinya:

- `firebase.json` hanya punya satu target hosting, dan isinya `public/`.
- `next.config.js` **tidak** memakai `output: 'export'`, jadi butuh runtime
  Node — Firebase Hosting statis tidak bisa menyajikannya.
- Origin-nya `https://www.koperasi-kita.com`
  (`functions/generateAutoLoginToken.js:17`), domain kustom, bukan
  `*.web.app`.
- Tidak ada `vercel.json`, `netlify.toml`, `Dockerfile`, atau workflow CI di
  repo.

> **VERIFIKASI MANUAL D-21, JANGAN DIASUMSIKAN.** Repo tidak memuat
> konfigurasi deploy web sama sekali, jadi kesimpulan di atas berdasar bukti
> tak-langsung. Buka dasbor penyedia hosting `www.koperasi-kita.com` dan
> pastikan. **Kalau ternyata ia Firebase Hosting, dashboard ikut mati** dan
> pemindahan hostnya naik ke prioritas tertinggi bersama `rk.html`.

---

## 2. Peta ketergantungan Firebase di web

| # | Lokasi | Ketergantungan | Pengganti |
|---|---|---|---|
| W-1 | `lib/firebase.js:9-30` | Auth + Storage + RTDB | `lib/supabase.js` |
| W-2 | `lib/api.js:8` | `BASE_URL` Cloud Functions | PostgREST + RPC |
| W-3 | `lib/api.js:19,55` | `getIdToken()` Firebase | sesi Supabase |
| W-4 | `lib/api.js:81-171` | 13 fungsi | view + RPC Tahap B |
| W-5 | `kasir/page.js:3796` | tulis RTDB `operasional_harian` | **`rpc_catat_operasional_harian`** (022) |
| W-6 | `kasir/page.js:3702` | baca RTDB `operasional_harian` | `select` (policy 016a) |
| W-7 | `kasir/page.js:3758-3759` | tulis RTDB `absensi` + `user_absensi_today` | **`rpc_catat_absensi`** (023) |
| W-8 | `kasir/page.js:3682,3690` | baca RTDB `absensi` | `v_absensi_hari_ini` (023) |
| W-9 | `kasir/page.js:11` | Firebase Storage (nota kasir) | Supabase Storage (003) |
| W-10 | `public/rk.html:392` | URL Cloud Function | Edge Function `rekening-koran` |
| W-11 | `public/rk.html` (host) | Firebase Hosting | host baru |

13 fungsi di W-4, seluruhnya sudah punya pengganti teruji:

| `lib/api.js` | Pengganti | |
|---|---|---|
| `getSummary` `:81` | view Tahap B | B-1/B-2 |
| `getBukuPokok` `:88` | `v_buku_pokok` | B-1 |
| `getPembayaranHariIni` `:98` | `v_pembayaran_hari_ini` | B-2 |
| `getKasirSummary` `:109` | view kasir | B-3.3 |
| `getKasirEntries` `:116` | `v_kasir_entry` | B-3 |
| `addKasirEntry` `:123` | `rpc_tambah_kasir_entry` | B-4 |
| `deleteKasirEntry` `:130` | `rpc_hapus_kasir_entry` | B-4 |
| `syncOperasionalTransport` `:137` | `rpc_sync_operasional_transport` | B-4 |
| `getJurnalTransaksi` `:148` | view jurnal | B-3 |
| `backfillJurnalTransaksi` `:155` | — **lihat §6** | — |
| `getKoreksiStorting` `:162` | view koreksi | B-3 |
| `setKoreksiStorting` `:169` | `rpc_set_koreksi_storting` | B-4 |
| `getBukuPokokSummary` | `v_buku_pokok_summary` | B-2 |

---

## 3. Backend yang hilang — sudah dikerjakan dalam commit ini

| Berkas | Isi | Membuka |
|---|---|---|
| `022_operasional_tulis.sql` | `app_user.legacy_uid` + `rpc_catat_operasional_harian` | W-5 |
| `023_absensi.sql` | tabel `absensi`, `v_absensi_hari_ini`, `rpc_catat_absensi` | W-7, W-8 |
| `supabase/functions/rekening-koran-link/` | pembuat tautan v2 sisi server | ketergantungan APK |

### 3.1 `022` — jembatan identitas yang selama ini tidak ada

`operasional_harian` ber-PK `(cabang_id, tanggal, legacy_uid)`, dan
`legacy_uid` itu **UID Firebase**. Sesudah Firebase mati klien hanya punya
uuid Supabase — dan `app_user` **tidak punya kolom** yang memetakan keduanya.

Tanpa jembatan itu, baris pasca-evakuasi memakai identitas berbeda dari
baris warisan **untuk orang yang sama**, dan riwayat satu staf terbelah dua.
`022` menambah `app_user.legacy_uid` dan mem-backfill-nya dari dua sumber
yang sudah memuat pasangannya (`operasional_harian.user_id`+`legacy_uid`,
dan `nasabah.admin_id`+`legacy_admin_uid`) — uuidv5 tidak bisa dibalik, jadi
ini satu-satunya jalan.

### 3.2 `023` — absensi

Brief masih memuat penanda kosong `[MIGRASIKAN_SEKARANG / TANGGUHKAN]`. Saya
kerjakan dengan asumsi **MIGRASIKAN**, karena pada premis baru "tangguhkan"
bukan lagi "kerjakan nanti":

> Absensi hanya hidup di RTDB. Menangguhkan = fitur **berhenti** pada
> cutoff, dan seluruh riwayatnya **hilang permanen**.

Kalau ternyata dipilih tangguhkan, berkas itu tinggal tidak dijalankan —
tanpa ongkos. Yang tidak bisa diperbaiki belakangan adalah kebalikannya.
**Ekspor `absensi/` tetap wajib sebelum cutoff apa pun keputusannya.**

### 3.3 `rekening-koran-link` — memutus ketergantungan APK

Staf web meminta tautan; server yang menandatangani. Kunci tidak pernah
masuk bundel web. Host tautan dari env `REKENING_KORAN_BASE_URL`, jadi bisa
diganti tanpa rilis apa pun. Ini yang membuat rekening koran tetap jalan
walau seluruh APK di lapangan masih versi lama.

---

## 4. Jadwal evakuasi

Urutan ditentukan **ketergantungan**, bukan risiko — tidak ada lagi
kemewahan memilih yang paling aman dulu.

### D-21 — pastikan dan selamatkan

- [ ] **Pastikan host `www.koperasi-kita.com`** (§1.1). Ini menentukan
      apakah ada satu pemindahan host atau dua.
- [ ] **Ekspor penuh RTDB** dan simpan di dua tempat berbeda. Termasuk node
      yang belum tersentuh migrasi: `absensi`, `user_absensi_today`,
      `operasional_harian` terbaru, `fcm_tokens`, `broadcast_messages`,
      `location_tracking`, `user_locations`, `device_presence`.
      **Setelah suspend, ekspor tidak mungkin lagi.**
- [ ] **Ekspor Firebase Storage** (foto KTP, foto profil, nota kasir) ke
      Supabase Storage (`003`). Volumenya paling besar dan paling lambat —
      mulai paling awal.
- [ ] Jalankan `022`, lalu `023` bila diputuskan migrasi.
- [ ] Impor riwayat `absensi` (pola `migrate_operasional_harian.js`).
- [ ] Daftar seluruh akun staf yang harus bisa login (§5).

### D-14 — web berdiri di atas Supabase

- [ ] **Pindahkan host `rk.html`.** Opsi termurah di §7.
- [ ] `rk.html:392` → URL Edge Function `rekening-koran`.
- [ ] Deploy `rekening-koran-link`; set `REKENING_KORAN_BASE_URL` ke host
      baru `rk.html`.
- [ ] `npm i @supabase/supabase-js`; buat `lib/supabase.js` (URL + anon key
      dari `NEXT_PUBLIC_*`, **bukan** literal — pelajaran `014` §6.4).
- [ ] Ganti login ke `signInWithPassword` (§5).
- [ ] Alihkan 13 fungsi `lib/api.js` (§2), satu per satu, bandingkan angka
      terhadap Cloud Functions **selagi keduanya masih hidup** — sesudah
      cutoff tidak ada pembanding lagi.
- [ ] Alihkan W-5..W-9 di `kasir/page.js`.
- [ ] Tambah tombol "Salin Tautan Rekening Koran" yang memanggil
      `rekening-koran-link`.

### D-7 — Android v2 (§8)

- [ ] Rilis APK v2. Sebarkan lewat saluran langsung (WhatsApp/USB), **jangan
      andalkan Play Store review** yang bisa memakan berhari-hari.
- [ ] Pantau adopsi. Staf yang belum memperbarui akan buta total pada D-0.

### D-3 — uji beku

- [ ] Satu hari kerja penuh, seluruh alur di Supabase saja.
- [ ] **Matikan Firebase lebih dulu secara sengaja** — cabut config web,
      matikan APK lama di satu perangkat uji — dan lihat apa yang patah
      **selagi masih bisa dihidupkan lagi**. Ini satu-satunya kesempatan
      menemukan ketergantungan yang terlewat.
- [ ] Bekukan perubahan non-darurat.

### D-0 — cutoff

- [ ] Hapus `firebase` dari `buku-pokok-web/package.json`; hapus
      `lib/firebase.js`; pastikan `grep -rn "firebase" buku-pokok-web/`
      bersih kecuali komentar.
- [ ] Arsipkan `functions/` dan `public/` di repo (**jangan dihapus** —
      aturan repo, dan keduanya jadi rujukan perilaku lama).

---

## 5. Login web + password awal

`signInWithPassword` ke akun `@godangulu.com` hasil migrasi.

**Kendala yang mengikat:** domain `@godangulu.com` **fiktif** — tidak ada
kotak surat. Setiap alur "kirim tautan reset" mustahil. Aturan ini sudah
berlaku sejak `008` §0 dan tetap berlaku di sini.

Karena itu password awal **tidak boleh** lewat email:

1. **Pengawas** membangkitkan password awal per staf lewat Edge Function
   `user-management` (Milestone 4) — password acak ditampilkan **di layar**,
   dicatat pengawas, diserahkan langsung.
2. Staf login, lalu **wajib ganti password** di pemakaian pertama.
3. Pemulihan lupa-password menempuh jalur yang sama: pengawas
   membangkitkan ulang. Tidak ada pemulihan mandiri, dan itu memang
   konsekuensi domain fiktif.

**Jangan pakai satu password sama untuk semua staf**, sekalipun sementara.
`013` §4 mencatat tiga akun uji berbagi satu password sementara — itu dapat
diterima untuk pengujian, **tidak** untuk seluruh staf di produksi tanpa
jaring pengaman Firebase.

> Password apa pun **tidak ditulis di repo**, termasuk di berkas ini —
> alasan yang sama seperti `006` §3.6: yang ter-commit tetap ada di riwayat
> git selamanya.

---

## 6. `backfillJurnalTransaksi` — satu-satunya tanpa pengganti

`lib/api.js:155`. Tidak ada padanannya di Tahap B, dan `010` mengklasifikasi
fungsi backfill sebagai perawatan sekali-jalan, bukan alur harian.

**Keputusan yang dibutuhkan sebelum D-14:** dipakai rutin, atau alat
perbaikan sesekali? Kalau rutin, ia butuh RPC sendiri dan harus masuk
jadwal. Kalau tidak, hapus tombolnya dari web dan catat sebagai fitur yang
sengaja ditinggalkan. Menemukannya hilang pada D-0 adalah kemungkinan
terburuk.

---

## 7. Pemindahan host — opsi termurah

`rk.html` adalah satu berkas HTML statis tanpa proses build.

| Opsi | Ongkos | Catatan |
|---|---|---|
| **Supabase Storage bucket publik** | **Rp 0**, sudah berlangganan | Taruh `rk.html` di bucket publik; URL-nya langsung dipakai `REKENING_KORAN_BASE_URL`. **Tidak menambah vendor, tidak menambah tagihan** — itulah kenapa saya sarankan ini. |
| Cloudflare Pages / Netlify / GitHub Pages | gratis | Perlu akun & domain baru; satu vendor lagi untuk diurus |
| Ikut host `buku-pokok-web` | gratis | Taruh di `buku-pokok-web/public/rk.html` → tersaji di `/rk.html`. **Paling rapi bila web memang bukan di Firebase.** Bergantung hasil verifikasi §1.1 |

Rekomendasi: **verifikasi §1.1 dulu.** Kalau web di host non-Firebase, pakai
opsi ketiga — nol vendor baru, nol URL baru untuk diurus. Kalau tidak, pakai
Supabase Storage.

Apa pun pilihannya, host itu masuk ke `REKENING_KORAN_BASE_URL` dan bisa
diganti belakangan tanpa rilis.

---

## 8. Lingkup minimal Android v2

Hanya yang membuat APK **tetap berfungsi** setelah Firebase mati. Bukan
kesempatan merapikan hal lain — `PelangganViewModel.kt` (16k baris) tidak
disentuh selain jalur transportnya.

| # | Lingkup | Berkas | Kenapa wajib |
|---|---|---|---|
| A-1 | **Auth Supabase** menggantikan Firebase Auth | `SupabaseClientProvider.kt` (ada), layar login | Tanpa ini tidak ada yang bisa masuk |
| A-2 | **Tulis operasional** → `rpc_catat_operasional_harian` | layar kasir/operasional | RTDB mati |
| A-3 | **Tulis + baca absensi** → `rpc_catat_absensi`, `v_absensi_hari_ini` | `AbsensiScreen.kt` | RTDB mati (bila §3.2 migrasi) |
| A-4 | **Tautan rekening koran** → panggil `rekening-koran-link` | `RekeningKoranHelper.kt` | Host lama mati; kunci tidak boleh lagi di APK |
| A-5 | **Sisa jalur sync** → Supabase | `SyncManager.kt`, `SupabaseSyncHandler.kt` (ada) | Sudah disiapkan Milestone 3; tinggal alihkan sakelar |

Pada A-4, `RekeningKoranHelper.kt` berubah dari *pembuat* tanda tangan
menjadi *peminta* tautan: `SECRET_KEY` (`:34`) dan `BASE_URL` (`:37`)
**dihapus dari APK**. Itu sekaligus menutup temuan `014` §6.4 secara
permanen — kunci tidak lagi ada di berkas yang bisa dibaca siapa pun.

**Yang boleh ditinggalkan di v2:** FCM, tracking GPS, mode offline penuh.
Ketiganya penting, tetapi tidak menghalangi orang bekerja pada D-0.

---

## 9. Risiko terbesar, disebut apa adanya

1. **Tidak ada rollback.** Setiap langkah yang gagal pada D-0 gagal permanen.
   Karena itu D-3 (uji beku dengan Firebase sengaja dimatikan) bukan
   formalitas — itu satu-satunya latihan yang mungkin.
2. **Adopsi APK.** Staf yang tidak memperbarui akan buta total. Mulai D-7 dan
   lacak per orang, bukan per rilis.
3. **Storage paling lambat.** Foto KTP bertahun-tahun. Mulai D-21; kalau
   belum selesai di D-3, itu sinyal untuk menambah tangan, bukan menunggu.
4. **Host web belum dipastikan** (§1.1). Satu-satunya yang bisa membalik
   seluruh jadwal ini. Pastikan hari ini.
5. **Absensi belum diputuskan** (§3.2). Setiap hari tertunda mengurangi
   peluang riwayatnya terselamatkan.

---

## 10. Catatan kejujuran

- `022` dan `023` belum pernah dijalankan; tidak ada PostgreSQL di sisi saya.
- `rekening-koran-link/index.ts` belum pernah dijalankan Deno, belum
  di-deploy.
- Kesimpulan bahwa `buku-pokok-web` bukan di Firebase Hosting berdasar
  **bukti tak-langsung** (§1.1) — repo tidak memuat konfigurasi deploy web
  sama sekali. Harus dipastikan manual, bukan dipercaya dari dokumen ini.
- `[ISI DI SINI]` untuk tanggal cutoff sengaja saya biarkan kosong: menebak
  tanggalnya akan membuat seluruh jadwal tampak pasti padahal tidak.
