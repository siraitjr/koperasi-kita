# 020 — B-5: Rekening Koran (endpoint publik tanpa login)

Menutup `014` §3.1 dan `014` §6.4. Batch terakhir Tahap B.

| Berkas | Isi | Status |
|---|---|---|
| `020a_rekening_koran_rpc.sql` | RPC sumber data, service_role saja | belum dijalankan |
| `supabase/functions/rekening-koran/index.ts` | Edge Function | belum di-deploy |
| `scripts/verification/test_rekening_koran.sh` | uji tanda tangan/kedaluwarsa/palsu | belum dijalankan |

Tidak ada perintah yang saya jalankan terhadap database maupun Supabase.
`app/`, `functions/`, dan `public/rk.html` tidak disentuh.

---

## 1. Kenapa B-5 tidak bisa jadi view

`rekeningKoranService.js` tidak memanggil `verifyIdToken` sama sekali.
Halaman dibuka nasabah yang **tidak punya akun**. RLS bekerja atas
`auth.uid()`; tanpa pengguna yang login tidak ada identitas untuk disaring.

Jadi di sini **HMAC menggantikan seluruh model izin.** Itu sebabnya batch ini
ditaruh terakhir dan dapat perlakuan berbeda dari B-1..B-4.

---

## 2. Keadaan yang diwarisi — dibaca utuh, bukan diringkas

Sumber: `functions/rekeningKoranService.js` (239 baris),
`app/src/main/kotlin/…/RekeningKoranHelper.kt` (200), `public/rk.html` (520).

### 2.1 Token dibuat di Android, bukan di server

`RekeningKoranHelper.kt:46-64`:

```
payload = "adminUid:pelangganId:timestamp"
sig     = HMAC-SHA256(payload, SECRET_KEY).hex.take(16)
token   = base64url("payload:sig")
URL     = https://koperasikitagodangulu.web.app/rk.html?t=<token>
```

Konsekuensi yang mengikat seluruh desain B-5: **server tidak bisa mengubah
format token tanpa rilis Android.** Kunci dan formatnya ada di dalam APK.

### 2.2 Lima temuan

| # | Temuan | Bukti |
|---|---|---|
| T-1 | **Tidak ada masa berlaku sama sekali** | `:59` — komentarnya sendiri: *"(tidak ada expiry agar link permanen)"* |
| T-2 | **Kunci ter-commit di dua tempat** | `rekeningKoranService.js:31`, `RekeningKoranHelper.kt:34`, sejak `e570701` (13 Apr 2026) |
| T-3 | **Kunci bawaan tetap hidup walau config diisi** | `:31` `functions.config().rk?.secret \|\| '<kunci>'` |
| T-4 | **Perbandingan tanda tangan tidak waktu-tetap** | `:56` `signature !== expectedSignature` |
| T-5 | **NIK & alamat dikirim ke halaman publik** | `:141` `nik`, `:143` `alamat` |

T-1 dan T-2 bersama berarti: tautan yang pernah bocor **berlaku selamanya**,
dan siapa pun yang bisa membaca repo dapat **menempa tautan untuk nasabah
mana pun**. Yang dilindungi tautan itu adalah nama, NIK, alamat, dan seluruh
riwayat pembayaran.

T-3 adalah pola yang membuat T-2 sulit disadari: karena selalu ada nilai
cadangan, konfigurasi yang tidak pernah diisi tidak pernah terlihat gagal.

T-4 terhubung ke panjang tanda tangan: hanya **16 hex = 64 bit**
(`RekeningKoranHelper.kt:77` `.take(16)`). Oracle waktu terhadap ruang
sekecil itu bukan lagi soal akademis.

### 2.3 Satu komentar yang keliru dan sebaiknya diluruskan

`RekeningKoranHelper.kt:28` berbunyi:

> *"Data sensitif (NIK) tidak ditampilkan di rekening koran"*

Itu tidak benar sejak `rekeningKoranService.js:141` menambahkan `nik` (ada
penanda `// ✅ TAMBAH NIK` di baris itu). Komentarnya usang, dan selama ini
menutupi kenyataan bahwa NIK memang terkirim ke halaman publik.

---

## 3. Keputusan desain: dua versi token

Syarat "wajib expiry" dan "Android tidak disentuh" tampak bertabrakan —
Android yang membuat token, dan token buatannya tidak punya `exp`.

**Tidak bertabrakan, karena token v1 sudah membawa `timestamp` yang selama
ini tidak pernah dipakai** (`:52` mem-parsingnya, lalu `:59` mengabaikannya).
Masa berlaku dihitung server-side dari situ.

| | v1 (warisan) | v2 (baru) |
|---|---|---|
| Bentuk | `b64url(admin:pelanggan:ts:sig16)` | `b64url("v2":admin:pelanggan:exp:sig64)` |
| Pembuat | Android yang beredar | Android rilis mendatang |
| Kunci | `REKENING_KORAN_V1_KEY` | `REKENING_KORAN_HMAC_KEY` |
| Tanda tangan | 16 hex (64 bit) | 64 hex (256 bit) |
| Masa berlaku | dihitung dari `ts`, TTL env | `exp` **ikut ditandatangani** |
| Nasib | pensiun pada `REKENING_KORAN_V1_UNTIL` | tetap |

Beda penting: pada v2 `exp` **berada di dalam yang ditandatangani**, jadi
tidak bisa diperpanjang tanpa kunci. Pada v1, TTL adalah kebijakan server
atas `ts` — dan `ts` bisa dikarang siapa pun yang punya kuncinya.

### ⚠ Yang tidak boleh disalahpahami tentang v1

Kunci v1 ada di riwayat git. **TTL pada v1 tidak menghentikan pemalsuan** —
penempa cukup memakai timestamp hari ini. TTL hanya mematikan tautan lama
yang sudah bocor.

Satu-satunya penutup sesungguhnya adalah **memensiunkan v1**. Karena itu
`REKENING_KORAN_V1_UNTIL` wajib diisi, dan Edge Function menolak seluruh v1
bila env itu kosong atau tanggalnya lewat — bukan menerimanya "sementara".

Menerima v1 adalah **utang yang sudah ada hari ini**, bukan utang baru yang
dibuat B-5: endpoint lama menerimanya tanpa batas waktu sama sekali.

---

## 4. Apa yang berubah dari perilaku lama

| Perilaku | Lama | B-5 |
|---|---|---|
| Tautan tanpa batas waktu | ya | **tidak** — TTL v1, `exp` v2 |
| Kunci bawaan di kode | ya | **tidak** — env saja, tanpa `\|\|` |
| Kunci kosong | jatuh ke bawaan | **503, gagal-tutup** |
| Banding tanda tangan | `!==` | waktu-tetap |
| `alamat`, `hari`, `status` di respons | ya | **dihapus** |
| `nik` di respons | utuh | **4 digit terakhir** |
| Galat database | pesan asli | ditelan, dicatat di log |
| `Cache-Control` | tidak ada | `no-store` |

**Perubahan yang akan terasa pengguna: tautan lama berhenti bekerja setelah
TTL.** Ini yang harus diumumkan ke admin lapangan sebelum cut-over — tautan
yang sudah disebar ke nasabah akan mati, dan admin perlu tahu bahwa
membuat ulang tautannya normal, bukan kerusakan.

Angka TTL-nya keputusan Anda (`REKENING_KORAN_V1_TTL_DAYS`, bawaan 30 hari).
30 hari cukup panjang untuk satu siklus tagih dan cukup pendek untuk
membatasi tautan bocor.

### Yang TIDAK berubah

Bentuk JSON responsnya sama persis, jadi `public/rk.html` tidak perlu
disentuh. Dipastikan dengan memeriksa field apa saja yang benar-benar dibaca
halaman itu:

```
d.nama d.nik d.nomorAnggota d.wilayah d.pinjamanKe d.tanggalDaftar d.tenor
d.besarPinjaman d.totalPelunasan d.totalDibayar d.sisaHutang d.sisaTenor
d.simpanan d.isLunas d.riwayatPembayaran d.referensiCicilan d.generatedAt
```

`alamat`, `hari`, dan `status` **tidak ada di daftar itu** — dikirim endpoint
lama, tidak pernah ditampilkan. Karena itu menghapusnya aman; itu bukan
pengetatan yang mempertaruhkan tampilan, melainkan membuang kiriman sia-sia.

---

## 5. Kunci dan rahasia

```bash
# Kunci v2 — BARU, jangan pernah menyalin kunci lama
openssl rand -base64 48

supabase secrets set \
  REKENING_KORAN_HMAC_KEY='<kunci-baru>' \
  REKENING_KORAN_V1_KEY='<kunci-lama-yang-ada-di-repo>' \
  REKENING_KORAN_V1_UNTIL='2026-12-31T23:59:59Z' \
  REKENING_KORAN_V1_TTL_DAYS='30' \
  REKENING_KORAN_MASK_NIK='true'
```

| Env | Wajib | Guna |
|---|---|---|
| `REKENING_KORAN_HMAC_KEY` | ya | kunci v2 aktif |
| `REKENING_KORAN_HMAC_KEY_OLD` | tidak | kunci v2 sebelumnya, **hanya selama rotasi** |
| `REKENING_KORAN_V1_KEY` | tidak | kunci warisan; kosongkan untuk mematikan v1 seketika |
| `REKENING_KORAN_V1_UNTIL` | ya bila `V1_KEY` diisi | tanggal pensiun keras v1 |
| `REKENING_KORAN_V1_TTL_DAYS` | tidak (30) | umur maksimum token v1 |
| `REKENING_KORAN_MASK_NIK` | tidak (true) | penyamaran NIK |

`REKENING_KORAN_V1_KEY` memang berisi kunci yang bocor. Itu disengaja dan
sifatnya sementara: ia menjaga APK yang beredar tetap jalan sampai pensiun.
Menaruhnya di secret (bukan di kode) tidak membuatnya rahasia lagi — ia
sudah tidak rahasia — tetapi membuat **mematikannya cukup satu perintah**,
tanpa deploy ulang.

**Kunci lama tidak boleh dipakai untuk v2.** Bukan sekadar higienis: kalau
kunci v2 sama dengan kunci v1, memensiunkan v1 tidak menutup apa pun.

---

## 6. Rotasi kunci terkoordinasi dengan rilis Android

Kunci ada di dalam APK, jadi rotasi = rilis. Masa tenggang dua kunci
mencegah tautan mati di tengah jalan.

```
Hari 0    set REKENING_KORAN_HMAC_KEY = K_baru
          set REKENING_KORAN_HMAC_KEY_OLD = K_lama      ← dua kunci hidup
          deploy Edge Function
          ┌ token bertanda K_lama : SAH
          └ token bertanda K_baru : SAH

Hari 0    rilis Android yang menandatangani dengan K_baru
          (di luar lingkup B-5; app/ tidak disentuh di sini)

Hari 0-N  masa tenggang. Panjangnya = waktu APK tersebar merata,
          MINIMAL selama TTL token terpanjang yang masih beredar.
          Memotongnya lebih pendek dari TTL mematikan tautan yang
          masih dalam masa berlakunya.

Hari N    pantau log: sudah tidak ada lagi yang lolos lewat K_lama.

Hari N+1  hapus REKENING_KORAN_HMAC_KEY_OLD
          ┌ token bertanda K_lama : DITOLAK
          └ token bertanda K_baru : SAH
```

Cara memantau kapan aman menutup: Edge Function mencatat
`rekening-koran ok versi=v1|v2` per permintaan sah. Selama masih ada
`versi=v1` di log, masih ada APK lama yang dipakai.

> Untuk memisahkan K_lama dari K_baru di log, tambahkan penanda kunci pada
> baris log itu sebelum memulai rotasi. Sekarang log hanya membedakan versi
> token, bukan kunci — cukup untuk pensiun v1, belum cukup untuk rotasi v2.
> Saya tidak menambahkannya sekarang karena rotasi v2 baru relevan setelah
> Android v2 ada.

### Urutan pensiun v1 (berbeda dari rotasi v2, jangan dicampur)

1. Rilis Android v2 tersebar.
2. Log tidak lagi menunjukkan `versi=v1` selama satu siklus penuh.
3. Majukan `REKENING_KORAN_V1_UNTIL` ke hari ini → seluruh v1 ditolak.
4. Setelah tenang, hapus `REKENING_KORAN_V1_KEY`.

Langkah 3 sengaja mendahului 4 dan bisa dibalik dalam hitungan detik kalau
ternyata masih ada yang memakai.

---

## 7. Urutan cut-over

```
020a_rekening_koran_rpc.sql
  → supabase secrets set (§5)
    → supabase functions deploy rekening-koran
      → scripts/verification/test_rekening_koran.sh          ← §8
        → bandingkan angka dengan endpoint lama (020a §VERIFIKASI no. 4)
          → arahkan public/rk.html ke URL baru
            → pantau; endpoint lama TETAP HIDUP untuk rollback
```

**Endpoint lama tidak dimatikan.** `functions/rekeningKoranService.js` tetap
ter-deploy, dan rollback = mengembalikan satu URL di `public/rk.html`. Jangan
hapus fungsi lamanya sampai B-5 berjalan tenang beberapa minggu.

---

## 8. Verifikasi berlapis

**Lapis 1 — hak akses RPC** (`020a` §VERIFIKASI no. 1). `anon` dan
`authenticated` harus `false`. Kalau `anon` bisa memanggil RPC lewat
PostgREST, seluruh guna HMAC lenyap.

**Lapis 2 — bentuk & isi keluaran** (`020a` §VERIFIKASI no. 2-4). NIK
tersamar, tidak ada `alamat`/`hari`/`status`, dan angkanya sama dengan
endpoint lama untuk nasabah yang sama.

**Lapis 3 — tanda tangan & masa berlaku:**

```bash
export RK_URL='https://<ref>.supabase.co/functions/v1/rekening-koran'
export RK_KEY='<REKENING_KORAN_HMAC_KEY>'
export RK_ADMIN_UID='<legacy_admin_uid>'
export RK_PELANGGAN_ID='<legacy_pelanggan_id>'
export RK_V1_KEY='<REKENING_KORAN_V1_KEY>'     # opsional
./scripts/verification/test_rekening_koran.sh
```

| Kasus | Harapan |
|---|---|
| v2 sah, belum kedaluwarsa | 200 |
| v2 sudah kedaluwarsa | 410 |
| v2 tanda tangan palsu | 401 |
| v2 palsu **dan** kedaluwarsa | **401**, bukan 410 |
| `exp` diperpanjang, tanda tangan lama | 401 |
| `pelangganId` ditukar, tanda tangan lama | 401 |
| bukan base64 / isi ngawur | 400 |
| v1 sah dalam TTL | 200 |
| v1 lewat TTL | 410 |
| v1 bertanggal masa depan | 410 |

Dua baris yang paling mudah terlewat, dan keduanya sengaja diuji:

- **v2 palsu + kedaluwarsa harus 401, bukan 410.** Edge Function memeriksa
  tanda tangan **sebelum** `exp`. Kalau urutannya dibalik, menjawab 410
  untuk token palsu memberi tahu penempa bahwa tanda tangannya sudah benar
  dan tinggal memperbarui tanggal.
- **`exp` diperpanjang / id ditukar harus 401.** Ini yang membuktikan `exp`
  dan id benar-benar ikut ditandatangani, bukan sekadar menempel di token.

**Lapis 4 — halaman sungguhan.** Buka `public/rk.html` dengan tautan v2 sah
dan pastikan seluruh bagian tampil: identitas, kartu angka, riwayat
pembayaran beserta sub-pembayarannya, referensi cicilan.

**Lapis 5 — hari pensiun v1.** Jalankan ulang skrip §8 pada hari
`REKENING_KORAN_V1_UNTIL` lewat. Ketiga kasus v1 harus jadi 410.

---

## 9. Yang belum dikerjakan, dan sengaja

- **Android v2 belum ditulis.** `app/` tidak disentuh sesuai instruksi. Tanpa
  itu, v1 belum bisa dipensiunkan — dan selama v1 hidup, pemalsuan masih
  mungkin (§3). Ini pekerjaan berikutnya yang paling berdampak dari seluruh
  sisa migrasi, dan bobotnya keamanan, bukan fitur.
- **Pembatasan laju (rate limit).** Endpoint tanpa login sebaiknya dibatasi
  per IP untuk menyulitkan penyisiran token. Tidak ditambahkan karena
  butuh keputusan tersendiri (angkanya, dan di mana state-nya disimpan).
  Dicatat sebagai celah yang diketahui, bukan yang terlewat.
- **Pencatatan akses.** Tidak ada tabel audit siapa membuka rekening koran
  siapa dan kapan. Endpoint lama juga tidak punya, jadi ini bukan kemunduran
  — tetapi untuk data sepribadi ini, layak dipertimbangkan.
- **`kasir_baca` (015 B-3.1)** masih memakai pola RLS per-baris pra-018.
  Tabelnya kecil; tidak disentuh karena sudah berjalan.

---

## 10. Catatan kejujuran

Tidak ada satu pun berkas di paket ini yang pernah dijalankan. Tidak ada
PostgreSQL, Deno, maupun project Supabase di sisi saya:

- `020a` belum divalidasi sintaks oleh server PostgreSQL mana pun.
- `index.ts` belum pernah dijalankan Deno; belum dikompilasi, belum di-deploy.
- `test_rekening_koran.sh` **sudah** saya periksa dengan `bash -n`, dan
  pembuatan token v2-nya saya uji lokal: HMAC menghasilkan 64 hex dan
  base64url-nya bolak-balik utuh. Yang belum diuji adalah bagian yang
  memanggil endpoint, karena endpointnya belum ada.
- Seluruh nomor baris yang dikutip di §2 berasal dari pembacaan berkas di
  repo ini, bukan dari ingatan.
