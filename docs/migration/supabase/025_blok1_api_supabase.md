# 025 — BLOK 1: `lib/api.js` → Supabase

D-4. Bagian dari `021` §2 / `024` urutan terbalik.

Tidak ada yang saya deploy, tidak ada halaman yang saya alihkan, dan
`lib/api.js` tidak disentuh.

---

## 1. Hal pertama yang harus Anda tahu

**Saya tidak bisa menghasilkan tabel perbandingan angkanya.** Perbandingan itu
menuntut memanggil Cloud Functions produksi dengan token Firebase yang sah
dan project Supabase Anda dengan sesi yang sah. Saya tidak punya keduanya, dan
tidak ada `npm run dev` di sisi saya.

Yang bisa saya buat — dan saya buat — adalah **alat yang menghasilkan tabel
itu**: `scripts/verification/bandingkan_api.mjs`. Anda menjalankannya, ia
mencetak tabelnya (§4).

Mengarang tabel perbandingan pada migrasi tanpa rollback adalah kegagalan
terburuk yang mungkin saya lakukan di sini, jadi kolomnya saya biarkan kosong
sampai Anda mengisinya dengan keluaran nyata.

---

## 2. Yang selesai

`buku-pokok-web/lib/apiSupabase.js` — nama fungsi dan **bentuk kembalian
identik** dengan `lib/api.js`, sehingga pengalihan halaman nanti cuma satu
baris impor:

```js
- import { getSummary, getBukuPokok, … } from '../../lib/api';
+ import { getSummary, … } from '../../lib/apiSupabase';
```

**Transport:** tidak ada lagi `getIdToken()`. Klien Supabase memasang access
token sesinya sendiri pada tiap permintaan PostgREST — tidak ada header yang
disusun tangan. Yang menjaga data adalah RLS (`002`, dipercepat `017`/`018`),
bukan gerbang peran di server aplikasi.

| `lib/api.js` | Pengganti di `apiSupabase.js` | Status |
|---|---|---|
| `getSummary` / `getBukuPokokSummary` | `cabang` + `app_user` + penyaringan peran | **selesai** |
| `getPembayaranHariIni` | `v_pembayaran_harian` | **selesai** |
| `getKasirSummary` | `v_kasir_entry` beragregat | **selesai** |
| `getKasirEntries` | `v_kasir_entry` | **selesai** |
| `addKasirEntry` | `rpc_tambah_kasir_entry` | **selesai** |
| `deleteKasirEntry` | `rpc_hapus_kasir_entry` | **selesai** |
| `syncOperasionalTransport` | `rpc_sync_operasional_transport` | **selesai** |
| `getJurnalTransaksi` | `v_jurnal_transaksi` | **selesai** |
| `getKoreksiStorting` | `v_koreksi_storting` | **selesai** |
| `setKoreksiStorting` | `rpc_set_koreksi_storting` | **selesai** |
| `backfillJurnalTransaksi` | — | **sengaja dibuang** (§5) |
| **`getBukuPokok`** | — | **BELUM — §3** |

Catatan pada `addKasirEntry`: `client_op_id` kini diterima sebagai parameter.
Ia **harus bertahan saat percobaan ulang** — itu yang membuat idempotensinya
bekerja. Membangkitkan ulang saat retry menggandakan entri kas.

---

## 3. `getBukuPokok` BELUM dipindahkan — dan kenapa saya berhenti

Ini fungsi terpenting sekaligus tersulit dari dua belas, dan **saya sengaja
tidak menebaknya.** Ia adalah buku besar; salah sedikit berarti angka
pembukuan salah, dan itu tepat kelas kegagalan yang paling mahal di sini —
"layar wajar dengan angka salah".

Bentuk kembaliannya (`bukuPokokApi.js:978-994`) memuat `nasabah[]` dengan
**±35 medan per nasabah** (`:637-680`), dan **tiga di antaranya tidak punya
sumber di Supabase**:

### G-1 · `rekapBeku` — TIDAK DIMIGRASIKAN, dan ini yang paling serius

`bukuPokokApi.js:460` membaca `rekap_harian_final/{adminUid}` — node RTDB
yang ditulis Cloud Function terjadwal tiap 23:59 WIB. Komentarnya sendiri
(`:951`) menyebutnya **"benteng anti-shrink historis"**: ia meng-OVERRIDE
kolom Target + Storting historis di Buku Rekap, dan tanpa entri itu
frontend jatuh ke kalkulasi live.

Node itu **tidak ada di skema Supabase mana pun** — saya cari di seluruh
`001`–`023`. Ia **mati 1 September** bersama RTDB.

Akibatnya, kalau `getBukuPokok` dipindahkan tanpa G-1 diselesaikan:
kolom historis Buku Rekap akan **berubah angkanya** dibanding cetakan
sebelumnya, tanpa satu pun galat muncul. Itu persis yang node ini dibuat
untuk mencegah.

> **Keputusan yang dibutuhkan hari ini:** ekspor `rekap_harian_final/` dan
> impor ke tabel Supabase baru (setengah hari, pola `016a`+skrip), atau
> terima bahwa kolom historis dihitung ulang. Kalau pilihannya yang kedua,
> itu harus keputusan sadar dan diumumkan — bukan ditemukan bulan depan saat
> angkanya tidak cocok dengan laporan lama.

### G-2 · Foto (`fotoKtpUrl`, `fotoNasabahUrl`, `fotoSerahTerimaUrl`, …)

Bergantung migrasi Firebase Storage → Supabase Storage, yang di `021` §0 saya
nilai tidak akan selesai dalam 6 hari. Tabel `dokumen` (`001` §10) sudah ada
wadahnya; isinya belum.

### G-3 · `riwayatPinjaman`, `sisaUtangLamaSebelumTopUp`, `besarPinjamanLamaSebelumTopUp`

Perlu dipetakan ke `pinjaman_history` (`001:950`). Bisa dikerjakan, tetapi
menuntut pembacaan cermat logika top-up di `bukuPokokApi.js` — bukan pekerjaan
yang layak diburu-buru.

**Yang MUDAH dan sudah siap:** inti barisnya. `v_buku_pokok` (`015` B-1) sudah
satu baris per generasi pinjaman dengan `total_dibayar`, `sisa_utang`,
`is_historis`, `is_lunas`, `is_aktif` — justru bagian yang di Cloud Function
memakan ±850 baris siasat terhadap bentuk RTDB. `tanggalList` juga sepele:
`generateHariKerja` (`:285`) hanya melewati hari Minggu, 60 hari ke belakang.

Jadi sisanya benar-benar tinggal G-1..G-3.

---

## 4. Menjalankan perbandingan

```bash
export CF_BASE='https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net'
export FB_ID_TOKEN='<await auth.currentUser.getIdToken() di konsol>'
export SUPABASE_URL='https://rgfemuqxxxiyrnkoerzw.supabase.co'
export SUPABASE_ANON_KEY='<anon key>'
export SB_ACCESS_TOKEN='<access_token dari localStorage "koperasi-kita-auth">'
export CABANG='panti'
export BULAN='2026-08'

node scripts/verification/bandingkan_api.mjs
```

Skrip **hanya membaca**; ia tidak menulis ke Firebase maupun Supabase.

Jumlah baris dibaca lewat `Prefer: count=exact` + header `Content-Range`,
bukan `array.length` — array selalu terpotong batas halaman PostgREST (1000),
dan itu yang membuat pemeriksaan pengawas di `018` §4b tidak tuntas.

| Keluaran | Artinya |
|---|---|
| `SAMA` | angkanya identik |
| `BEDA` | **berhenti.** Jangan alihkan halaman sebelum tiap barisnya dijelaskan |
| `LEWAT` | salah satu sisi tidak menyediakannya (mis. `getBukuPokok`) |

**Jalankan untuk tiga peran** (§6) dan **minimal dua cabang**. Sekali jalan
dengan satu akun tidak membuktikan RLS.

> Skrip ini keluar dengan kode 1 bila ada `BEDA`. `✓` di akhirnya berarti
> "tidak ada selisih pada medan yang diperiksa" — **bukan** "seluruh datanya
> sama". Medan yang tidak diperiksa tetap tidak diperiksa.

---

## 5. `backfillJurnalTransaksi` — dibuang dengan sengaja

Keputusan pemilik (`021` §2): alat sesekali, bukan fitur harian. Tidak ada
padanannya di `apiSupabase.js`, dan tombolnya dihapus dari web saat halaman
dialihkan.

**Untuk CHANGELOG rilis:**

```
- Tombol "Backfill Jurnal Transaksi" dihapus dari web pada migrasi Supabase
  (D-4, 1 Sep 2026). Ini alat perbaikan sesekali, bukan fitur harian, dan
  sengaja TIDAK dipindahkan — bukan hilang karena kelalaian migrasi.
  Kalau dibutuhkan lagi, buat RPC baru; jangan hidupkan jalur Cloud Function.
```

---

## 6. Sesi uji tiga peran di localhost

Pengawas membangkitkannya lewat Edge Function `user-management`, aksi
`resetUserPassword` (`index.ts:341`). Sandinya **ditampilkan di respons**,
tidak dikirim surel — domain `@godangulu.com` fiktif (`008` §0).

```bash
curl -X POST "$SUPA_URL/functions/v1/user-management" \
  -H "Authorization: Bearer $JWT_PENGAWAS" \
  -H "Content-Type: application/json" \
  -d '{"action":"resetUserPassword","targetUserId":"<uuid staf>"}'
```

Sandi dari respons dipakai untuk masuk di `http://localhost:3000/login-supabase`,
lalu `access_token`-nya diambil dari `localStorage` kunci `koperasi-kita-auth`
untuk `SB_ACCESS_TOKEN` di §4.

| Peran | Untuk menguji |
|---|---|
| `kasir_unit` (mis. `kaspan@`) | seluruh alur kasir, operasional, absensi |
| `admin` | melihat **hanya nasabahnya sendiri** |
| `pimpinan` | melihat **hanya cabangnya** |

Tambahkan **`koordinator`** bila absensi (`023`) sudah dipasang: ia satu-satunya
peran yang butuh pemilih cabang, dan cacat `p_cabang_id` di `023` §3.2 hanya
muncul dengan akun koordinator sungguhan.

> **Sandi tidak ditulis di repo, dokumen, maupun pesan commit** — yang
> ter-commit tetap ada di riwayat git selamanya (`006` §3.6). Simpan di
> pengelola kata sandi.

---

## 7. Checklist deploy production (Vercel) — JANGAN dijalankan sekarang

`vercel --prod` hanya **setelah** seluruh alur hijau di localhost.

- [ ] **Environment Variables di dasbor Vercel** (Project → Settings →
      Environment Variables), untuk **Production, Preview, dan Development**:
      - `NEXT_PUBLIC_SUPABASE_URL`
      - `NEXT_PUBLIC_SUPABASE_ANON_KEY`
- [ ] Keduanya **`NEXT_PUBLIC_`** — dibutuhkan di browser. Jangan pernah
      menaruh `SERVICE_ROLE_KEY` di sini; ia mem-bypass RLS sepenuhnya.
- [ ] `.env.local` **tidak** ikut ter-deploy dan bukan pengganti langkah di
      atas. Ia hanya untuk mesin Anda.
- [ ] Setelah menambah env, **deploy ulang**. Next.js menanam nilai
      `NEXT_PUBLIC_*` saat build; menambahnya tanpa build ulang tidak
      berpengaruh apa-apa.
- [ ] `@supabase/supabase-js` ada di `package.json` — ✅ `5b9a4c4` (`^2.112.4`).
- [ ] Uji di URL **Preview** lebih dulu, bukan langsung Production.
- [ ] Origin Vercel ada di `ALLOWED_ORIGINS` Edge Function
      `rekening-koran-link` (`https://www.koperasi-kita.com`,
      `https://koperasi-kita.com`). **URL preview `*.vercel.app` TIDAK ada di
      daftar itu** — tombol tautan rekening koran akan ditolak CORS saat diuji
      di preview. Tambahkan sementara, atau uji fitur itu di production saja.

---

## 8. Status blok lain

| Blok | Status |
|---|---|
| 1 — 13 fungsi | **sebagian**: 10 selesai, `getBukuPokok` tertahan G-1..G-3 (§3) |
| 2 — W-5/W-6 operasional | belum |
| 3 — W-7/W-8 absensi semua peran | belum |
| 4 — tombol tautan rekening koran | belum |
| 5 — W-9 nota ke Supabase Storage | belum |
| 6 — pembalikan login (`024` §4) | belum — syaratnya Blok 1-5 hijau |

Blok 2-5 masing-masing suntingan terbatas di `app/kasir/page.js` (3.999
baris). Saya belum mengerjakannya di putaran ini karena **`getBukuPokok`
memblokir Blok 6**, dan mengalihkan halaman kasir sebelum buku pokok
tersedia akan menghasilkan keadaan setengah jadi — persis yang `024` §1
putuskan untuk dihindari.

**Urutan yang saya sarankan:** putuskan G-1 lebih dulu (§3), karena ia
menentukan apakah `getBukuPokok` bisa selesai hari ini. Blok 2-5 mengikut
sesudahnya dan tidak saling bergantung.

---

## 9. Catatan kejujuran

- `apiSupabase.js` dan `bandingkan_api.mjs` **belum pernah dijalankan**.
  Yang saya periksa: sintaksnya (`node --check`, keduanya lulus), dan bahwa
  belum ada halaman yang mengimpornya — jadi `npm run dev` tidak terganggu.
- Bentuk kembalian saya sesuaikan dengan membaca respons Cloud Function baris
  per baris (`bukuPokokApi.js:1074`, `:978`, `kasirApi.js:240`, `:343`,
  `bukuPokokApi.js:1159`), bukan dari ingatan. Tetapi **kesamaannya belum
  diuji** — itu tugas §4.
- Nama view `v_jurnal_transaksi` dan `v_koreksi_storting` saya pakai sesuai
  `015` B-3. Kalau nama sebenarnya di server berbeda, `getJurnalTransaksi`
  dan `getKoreksiStorting` gagal — dan §4 akan memperlihatkannya sebagai
  `(gagal)`.
