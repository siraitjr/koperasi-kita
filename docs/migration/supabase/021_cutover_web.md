# 021 — Rencana Cut-over Web (Frontend)

Perencanaan saja. **Tidak ada kode yang saya ubah.** Seluruh nomor baris di
bawah dari pembacaan berkas di repo ini.

Backend Tahap B sudah selesai dan teruji di production. Dokumen ini memetakan
sisi web, lalu menyusun langkahnya.

**Kesimpulan di depan:** dua sasaran yang Anda sebut **tidak setara
kesiapannya**. Rekening koran bisa dipindahkan minggu ini. Sinkronisasi
operasional kasir **belum bisa**, karena tiga prasyarat yang belum ada — dan
memindahkannya sekarang bukan sekadar gagal, melainkan **merusak data**
(§3.1). Rinciannya di bawah.

---

## 1. Peta panggilan endpoint lama

### 1.1 Berkas yang terlibat

| Berkas | Baris | Perannya |
|---|---|---|
| `buku-pokok-web/lib/api.js` | 171 | transport terpusat; `BASE_URL` Cloud Functions di `:8` |
| `buku-pokok-web/app/kasir/page.js` | ±3900 | satu-satunya halaman yang menyentuh RTDB langsung |
| `buku-pokok-web/app/pembukuan/page.js` | — | hanya lewat `lib/api.js` |
| `buku-pokok-web/app/buku-perkembangan/page.js` | — | hanya lewat `lib/api.js` |
| `public/rk.html` | 520 | halaman publik, berdiri sendiri, tidak memakai `lib/api.js` |

`lib/api.js:8`:

```js
const BASE_URL = 'https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net';
```

Seluruh 13 fungsi di berkas itu lewat `apiCall()`/`apiPost()`, yang keduanya
memasang `Authorization: Bearer <Firebase ID token>` (`:20`, `:55`).

### 1.2 Sasaran A — sinkronisasi operasional kasir

| # | Lokasi | Isi |
|---|---|---|
| A-1 | `lib/api.js:137-139` | `syncOperasionalTransport()` → `apiPost('syncOperasionalTransport', {})` |
| A-2 | `app/kasir/page.js:13` | impor fungsi itu |
| A-3 | `app/kasir/page.js:3801` | pemanggilan, di dalam `try/catch` non-blocking |
| **A-4** | **`app/kasir/page.js:3796`** | **`set(dbRefFn(db, 'operasional_harian/{cabang}/{today}/{uid}'), record)` — tulis LANGSUNG ke RTDB** |
| **A-5** | **`app/kasir/page.js:3702`** | **`get(dbRefFn(db, 'operasional_harian/{cabang}/{today}'))` — baca LANGSUNG dari RTDB** |

A-4 dan A-5 inilah yang membuat sasaran A bukan penggantian transport
satu baris. Lihat §3.1.

Konteks A-3 (`:3799-3805`) — sinkronnya sengaja tidak memblokir:

```js
await set(dbRefFn(db, `operasional_harian/${activeCabang.id}/${todayKey}/${uid}`), record);
setOperasionalMap(m => ({ ...m, [uid]: record }));
try { await syncOperasionalTransport(); }
catch (syncErr) { console.error('Sync operasional ke jurnal gagal:', syncErr); }
```

### 1.3 Sasaran B — rekening koran

| # | Lokasi | Isi |
|---|---|---|
| B-1 | `public/rk.html:392` | `const API = 'https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net/getRekeningKoran';` |
| B-2 | `public/rk.html:408` | `await fetch(\`${API}?t=${encodeURIComponent(token)}\`)` |

**Satu konstanta.** `rk.html` tidak memakai `lib/api.js`, tidak memakai
Firebase Auth, dan tidak menyentuh RTDB. Bentuk JSON respons Edge Function
sudah dirancang identik (`020` §4), jadi `render()` di `:422` tidak berubah.

### 1.4 Yang ikut terlihat saat memetakan — di luar lingkup, tetapi mengikat

`app/kasir/page.js` juga membaca/menulis RTDB untuk **absensi**, yang **tidak
pernah masuk lingkup migrasi mana pun**:

| Baris | Node RTDB |
|---|---|
| `:3682` | `absensi/{cabang}/{today}` (baca) |
| `:3690` | `user_absensi_today/{uid}` (baca) |
| `:3758` | `absensi/{cabang}/{today}/{uid}` (tulis) |
| `:3759` | `user_absensi_today/{uid}` (tulis) |

Artinya **halaman kasir tidak bisa lepas penuh dari Firebase** dalam cut-over
ini, apa pun yang kita lakukan pada sasaran A. Itu bukan alasan menunda
sasaran A — hanya koreksi terhadap harapan "halaman kasir pindah ke Supabase".

---

## 2. Prasyarat lintas-halaman: web belum punya sesi Supabase

Ini mengenai **setiap** panggilan berautentikasi, bukan hanya sasaran A.

- `buku-pokok-web/package.json:14` hanya memuat `"firebase": "^10.12.0"`.
  **`@supabase/supabase-js` tidak terpasang sama sekali.**
- `lib/api.js:19` dan `:55` mengambil `user.getIdToken()` — token **Firebase**.
- RPC Supabase menuntut JWT **Supabase**; `auth.uid()` di dalam
  `rpc_sync_operasional_transport` dibaca dari sana, lalu dicocokkan ke
  `koperasi.app_user` untuk gerbang `kasir_unit` (`015` B-4).

Token Firebase tidak akan pernah lolos. Jadi sebelum satu pun RPC dipanggil
dari web, harus ada:

1. `npm i @supabase/supabase-js` + `lib/supabase.js`;
2. jalur login Supabase. Edge Function `session-management` sudah punya
   `autoLogin` (Tahap A, `012`) untuk SSO Android→Web; untuk login web
   langsung, `signInWithPassword` ke akun `@godangulu.com` hasil migrasi.

**Sasaran B tidak terkena prasyarat ini** — `rk.html` publik, tanpa login.
Itulah sebabnya B bisa lebih dulu.

---

## 3. Kenapa sasaran A belum bisa dipindahkan

### 3.1 Penghalang utama: tidak ada jalur TULIS `operasional_harian` di Supabase

Alur sekarang: web **menulis** ke RTDB (A-4), lalu memanggil sync.
`rpc_sync_operasional_transport` membaca `koperasi.operasional_harian`.

Kalau A-3 diarahkan ke RPC sementara A-4 masih menulis ke RTDB, RPC membaca
tabel yang **tidak pernah menerima entri hari ini**.

Akibatnya bukan sekadar angka meleset:

> Untuk hari yang belum ada barisnya di Supabase, total = 0. Cabang
> "total 0 dengan entri lama" pada RPC (`015` B-4, cermin
> `kasirApi.js:662`) akan **menghapus lunak entri kasir hari itu.**

Jadi mengarahkan A-3 lebih dulu **menghapus entri kasir yang benar**, setiap
kali kasir menyimpan operasional. Ini kegagalan yang merusak, bukan yang
gagal-tutup.

**Dan jalur tulisnya belum ada.** `016a` sengaja memberi klien **SELECT
saja** ("Penulisan hanya lewat service_role (skrip migrasi) atau RPC"), dan
sampai hari ini **tidak ada RPC tulis** untuk tabel itu — satu-satunya fungsi
yang menyentuhnya adalah `rpc_sync_operasional_transport`, yang hanya
membaca.

> **Ini pekerjaan backend yang tersisa, walau Tahap B dinyatakan 100%.**
> Tahap B memindahkan 14 endpoint **baca** + 4 RPC tulis dari `014`.
> Penulisan `operasional_harian` tidak pernah termasuk, karena di sistem lama
> ia bukan endpoint — ia tulisan RTDB langsung dari browser. Yang hilang dari
> daftar justru karena bentuknya berbeda.

### 3.2 Yang dibutuhkan sebelum A bisa dijadwalkan

| # | Kebutuhan | Di mana |
|---|---|---|
| A-P1 | Klien + sesi Supabase di web | §2 |
| A-P2 | **RPC baru** `rpc_catat_operasional_harian` (gerbang `kasir_unit`, cabang dari profil, idempoten per `(cabang,tanggal,legacy_uid)`) | belum ada |
| A-P3 | Jalur **baca** pengganti A-5 (`select` dari `koperasi.operasional_harian`, sudah diizinkan policy `016a`) | siap |
| A-P4 | Keputusan: tulis-ganda (RTDB **dan** Supabase) selama transisi, atau pindah sekaligus | belum diputuskan |

A-P4 penting karena **Android juga menulis node yang sama**. Selama APK lama
masih menulis ke RTDB, Supabase tidak akan lengkap kecuali web ikut menulis
ke keduanya. Tulis-ganda menjaga kedua sisi utuh dan membuat rollback
sasaran A tetap murah.

---

## 4. Rencana cut-over

Urutannya dari risiko terendah. Setiap langkah berdiri sendiri dan punya
rollback sendiri — endpoint Firebase lama tetap hidup, jadi tidak ada
langkah yang mengunci langkah berikutnya.

### LANGKAH 1 — Rekening koran (`public/rk.html`) · risiko rendah

Publik, baca-saja, satu konstanta, tanpa sesi. Kalau gagal, yang terpengaruh
hanya halaman rekening koran, dan pulih dengan mengembalikan satu baris.

- [ ] 1.1 Pastikan `020a` + Edge Function `rekening-koran` sudah live, dan
      `scripts/verification/test_rekening_koran.sh` **lulus** (`020` §8).
- [ ] 1.2 Ambil satu nasabah nyata. Buka tautan v1 yang **sama** ke endpoint
      lama dan ke Edge Function baru. Bandingkan **angka**, bukan sekadar
      "halaman muncul": `besarPinjaman`, `totalPelunasan`, `totalDibayar`,
      `sisaHutang`, `sisaTenor`, jumlah baris riwayat, jumlah sub-pembayaran.
- [ ] 1.3 Ubah **`public/rk.html:392`** saja:
      `const API = 'https://<ref>.supabase.co/functions/v1/rekening-koran';`
      Baris `:408` tidak berubah.
- [ ] 1.4 `firebase deploy --only hosting` (Hosting saja — **jangan**
      `--only functions`; fungsi lama harus tetap hidup).
- [ ] 1.5 Uji dari **HP di jaringan seluler**, bukan hanya desktop.
      Tautan ini dibuka nasabah lewat WhatsApp; cache dan proxy operator
      berperilaku lain.
- [ ] 1.6 Umumkan ke admin lapangan: **tautan lama akan mati setelah TTL**
      (bawaan 30 hari, `020` §4). Membuat ulang tautan itu normal, bukan
      kerusakan. Lakukan **sebelum** 1.4, bukan sesudah.
- [ ] 1.7 Pantau log Edge Function beberapa hari. Selama masih ada
      `versi=v1`, APK lama masih dipakai — jangan majukan
      `REKENING_KORAN_V1_UNTIL`.

**Rollback:** kembalikan `:392` ke URL lama, deploy hosting. < 5 menit.

### LANGKAH 2 — Fondasi Supabase di web · tanpa perubahan perilaku

Menyiapkan saja; tidak ada panggilan yang dialihkan.

- [ ] 2.1 `npm i @supabase/supabase-js` di `buku-pokok-web/`.
- [ ] 2.2 `lib/supabase.js` — URL + anon key dari env (`NEXT_PUBLIC_*`),
      **bukan** literal di kode (pelajaran `014` §6.4).
- [ ] 2.3 Login Supabase berdampingan dengan Firebase. Selama transisi
      **dua sesi hidup bersamaan**; jangan cabut Firebase Auth, karena
      `lib/api.js` masih memerlukannya.
- [ ] 2.4 Verifikasi: sesi Supabase terbentuk dan `koperasi.app_user`
      pemakainya terbaca dengan peran yang benar. Belum ada RPC dipanggil.

**Rollback:** hapus impor; tidak ada perilaku yang berubah.

### LANGKAH 3 — Halaman baca (pembukuan, buku-perkembangan) · risiko sedang

Baca-saja; salah baca terlihat, tidak merusak.

- [ ] 3.1 Alihkan `lib/api.js` fungsi **baca** ke view Tahap B, satu per
      satu: `getSummary` → `getBukuPokok` → `getBukuPokokSummary` →
      `getPembayaranHariIni` → jurnal → koreksi storting.
- [ ] 3.2 Setiap fungsi: bandingkan keluarannya dengan endpoint lama untuk
      cabang & bulan yang sama **sebelum** dipakai halaman.
- [ ] 3.3 Uji dengan **tiga peran** (admin, pimpinan, pengawas). Jumlah baris
      per peran harus sama dengan sebelum pindah — ini yang menangkap RLS
      yang meleset. Bandingkan dengan `Prefer: count=exact` supaya tidak
      tertipu paginasi PostgREST (`018` §catatan).

**Rollback:** per fungsi, kembalikan ke `apiCall(...)`. Karena terpusat di
`lib/api.js`, ini satu berkas.

### LANGKAH 4 — Tulis kasir (`addKasirEntry`, `deleteKasirEntry`, koreksi storting)

RPC-nya sudah ada dan teruji (`015` B-4).

- [ ] 4.1 Alihkan satu per satu ke `rpc_tambah_kasir_entry`,
      `rpc_hapus_kasir_entry`, `rpc_set_koreksi_storting`.
- [ ] 4.2 Pastikan `client_op_id` dibangkitkan klien (`crypto.randomUUID()`)
      dan **dipertahankan saat percobaan ulang** — itu yang membuat
      idempotensinya bekerja. Membangkitkan ulang saat retry menggandakan
      entri kas.
- [ ] 4.3 Uji: entri masuk, tampil di rekap, penghapusan menyembunyikannya
      dari `v_kasir_entry` tetapi barisnya tetap ada (`dihapus_at` terisi).

**Rollback:** per fungsi di `lib/api.js`.

### LANGKAH 5 — Sinkronisasi operasional kasir · **TERBLOKIR**

Jangan jadwalkan sebelum §3.2 selesai. Urutan di dalamnya mengikat:

- [ ] 5.1 **Backend:** buat `rpc_catat_operasional_harian` (A-P2). Dokumen
      + SQL + uji terpisah, pola sama seperti `016`/`019`.
- [ ] 5.2 Alihkan **TULIS** (A-4) lebih dulu — tulis-ganda: RTDB **dan**
      Supabase. Belum menyentuh A-3.
- [ ] 5.3 Jalankan berdampingan beberapa hari. Setiap hari bandingkan
      `operasional_harian` RTDB vs `koperasi.operasional_harian`: jumlah
      baris dan total nominal per (cabang, tanggal) harus sama.
- [ ] 5.4 Baru setelah 5.3 bersih, alihkan **BACA** (A-5).
- [ ] 5.5 **Terakhir**, alihkan sync (A-3) ke `rpc_sync_operasional_transport`.
- [ ] 5.6 Uji hari yang sudah punya entri warisan: RPC harus mengembalikan
      `id` baris warisan, **tidak menambah baris** (`019` §4.4).
- [ ] 5.7 Hentikan tulis RTDB hanya setelah Android juga pindah.

> **Kenapa tulis mendahului sync, dan tidak boleh dibalik:** membalik urutan
> berarti RPC membaca tabel kosong untuk hari berjalan → total 0 → **entri
> kasir hari itu terhapus lunak** (§3.1).

---

## 5. Aturan yang berlaku sepanjang cut-over

1. **Jangan `firebase deploy --only functions`.** Cloud Functions lama adalah
   rollback untuk SETIAP langkah. Hosting boleh; functions tidak.
2. **Satu langkah per rilis.** Jangan gabungkan langkah 1 dan 3 dalam satu
   deploy — kalau ada yang meleset, penyebabnya jadi ambigu.
3. **Bandingkan angka, bukan "halaman muncul".** Kegagalan yang paling mahal
   di migrasi ini bukan layar kosong, melainkan layar yang tampak wajar
   dengan angka yang salah.
4. **Rilis di awal hari kerja**, bukan Jumat sore. Kasir dan admin lapangan
   memakai ini setiap hari; butuh orang yang bisa dihubungi saat ada yang
   aneh.
5. **Kunci dan anon key dari env**, tidak pernah literal di kode
   (`014` §6.4).

---

## 6. Ringkasan kesiapan

| Sasaran | Backend | Web | Bisa dijadwalkan? |
|---|---|---|---|
| Rekening koran (`rk.html`) | siap & teruji | 1 konstanta | **ya, sekarang** |
| Halaman baca | siap & teruji | butuh langkah 2 | ya, setelah langkah 2 |
| Tulis kasir | siap & teruji | butuh langkah 2 | ya, setelah langkah 3 |
| **Sync operasional** | **kurang 1 RPC tulis** | butuh 2 + 5.1 | **belum** |

---

## 7. Yang saya sarankan diputuskan lebih dulu

1. **A-P4 — tulis-ganda atau pindah sekaligus?** Saya sarankan tulis-ganda:
   Android masih menulis node yang sama, jadi tanpa itu salah satu sisi
   selalu tidak lengkap.
2. **TTL rekening koran** (`REKENING_KORAN_V1_TTL_DAYS`, bawaan 30). Ini
   menentukan isi pengumuman di 1.6, jadi harus final sebelum langkah 1.
3. **Login web Supabase**: `signInWithPassword`, atau ikut jalur `autoLogin`
   dari Android? Menentukan bentuk langkah 2.3.
4. **Nasib absensi** (§1.4). Tidak pernah masuk lingkup migrasi. Selama belum
   diputuskan, halaman kasir tetap butuh Firebase Auth + RTDB, dan itu perlu
   disebut apa adanya alih-alih ditemukan belakangan.
