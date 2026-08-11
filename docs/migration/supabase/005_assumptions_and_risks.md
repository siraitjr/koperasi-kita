# Asumsi & Risiko — Rancangan Supabase

Fase 1. Menyertai `001`–`004`.

Dokumen ini memuat hal-hal yang **tidak saya ketahui** dan hal-hal yang **bisa
merusak** kalau rancangan ini diadopsi apa adanya. Rancangan tanpa daftar ini
tidak bisa dinilai.

---

## 0. Status Verifikasi — Baca Ini Dulu

| Klaim | Status |
|---|---|
| SQL di `001`/`002` sintaksnya benar | **Belum diverifikasi.** Tidak ada PostgreSQL/`psql`/Supabase di environment ini. Belum pernah dieksekusi. |
| Policy RLS memberi izin yang benar | **Belum diuji.** Nol pengujian dijalankan. |
| Pemetaan field lengkap | Diturunkan dari `data class Pelanggan` (`PelangganViewModel.kt:160-271`) dan `pelangganToMap` (`:2241`). Field yang hanya muncul di data lapangan lama tapi tak ada di model **tidak akan terlihat** oleh saya. |
| Volume data & performa | **Tidak diketahui.** Tidak ada satu pun angka jumlah baris. |

Tidak ada bagian dari rancangan ini yang boleh disebut "aman" saat ini.
Yang bisa saya dukung dengan bukti hanyalah kutipan `file:line`-nya.

---

## 1. Asumsi

| # | Asumsi | Dasar | Kalau salah |
|---|---|---|---|
| A-01 | `status` pinjaman hanya 6 nilai di `pinjaman_status` | Sensus literal Android: `"Aktif"` 75×, `"Menunggu Approval"` 60×, `"Lunas"` 49×, `"Disetujui"` 42×, `"Ditolak"` 17×, `"Tidak Aktif"` 13× | Nilai tak terduga di data lama → impor gagal di cast enum. **Mitigasi: `select distinct status` atas export RTDB penuh sebelum impor.** |
| A-02 | Satu nasabah = satu pinjaman hidup pada satu waktu | `pinjamanKe` di-*increment*, bukan bercabang (`PelangganViewModel.kt:182`); alur top-up melunasi yang lama | Index parsial `pinjaman_satu_aktif_per_nasabah` akan menolak data sah. Perlu dicek: adakah nasabah dengan 2 pinjaman aktif bersamaan? |
| A-03 | `pinjamanKe` selalu berurutan tanpa lompatan | Selalu naik 1 saat top-up | `tg_pinjaman_generasi_berurutan` menolak impor. Kalau ada lompatan (akibat bug rollback), butuh baris "generasi hantu" bertipe `Tidak Aktif`. |
| A-04 | `clientOpId` unik lintas seluruh sistem | UUID acak (`SyncManager.kt:116`) | Bukan risiko tabrakan, melainkan **nilai kosong** pada data lama — lihat R-05. |
| A-05 | UID Firebase dapat dipetakan 1:1 ke `auth.users.id` | Migrasi user Supabase lazim | Kalau user harus dibuat ulang, semua FK `admin_id`/`approver_id` bergantung pada tabel pemetaan yang benar. Salah petak = salah atribusi keuangan. |
| A-06 | Zona waktu tunggal Asia/Jakarta | CLAUDE.md §9.1; `getTodayIndonesia()` | Cabang di zona lain akan bergeser tanggal potong buku. |
| A-07 | Nominal muat di `bigint` | `Int` Kotlin saat ini justru lebih sempit | Aman satu arah; `bigint` selalu lebih longgar. |
| A-08 | Jumlah cabang & nasabah dalam skala yang muat satu Postgres | — | **Tidak berdasar apa pun.** Saya tidak punya angka. |

---

## 2. Risiko

### R-01 — Aplikasi Android tidak menulis ke Postgres. Sama sekali. `TINGGI`

Seluruh jalur tulis Android menuju RTDB: `SyncManager` (`ADD_PELANGGAN`,
`ADD_PEMBAYARAN`, `ADD_SUB_PEMBAYARAN`, `UPDATE_PELANGGAN`, `SERAH_TERIMA`,
`REMOVE_*`, `ADD_RIWAYAT_PINJAMAN`), antrean Room, dan `OfflineRepository`.
Skema ini tidak punya klien.

Fase 1 memang hanya rancangan — tetapi implikasinya perlu tercatat sekarang:
migrasi ini **tidak bisa dilakukan bertahap per-tabel**. `pelanggan` pecah jadi
5 tabel; tidak ada titik tengah di mana sebagian tulisan ke RTDB dan sebagian
ke Postgres tetap konsisten. Perlu keputusan Anda di Fase 2: *dual-write* di
belakang antarmuka repository, atau *cutover* sekali jalan dengan jendela baca-saja.

### R-02 — CLAUDE.md keliru soal nilai `status` `RENDAH`

CLAUDE.md §5.2 menulis `status` (`aktif` | `lunas` | `menunggu_pencairan` |
`ditolak`). Data sebenarnya: huruf kapital (`"Aktif"`, bukan `"aktif"`), dan
`grep 'status = "Menunggu Pencairan"'` → **0 hasil**. String itu milik
`statusPencairanSimpanan` (`KelolaKreditScreen.kt:1382`) dan label filter UI
(`PimpinanDaftarSemuaNasabahScreen.kt:75`).
Enum di `001` mengikuti **kode**, bukan CLAUDE.md. Kalau ada skrip yang
mengandalkan dokumentasi itu, skripnya salah.

### R-03 — Role `sekretaris` dan "Kasir Wilayah" tidak ada di kode `SEDANG`

CLAUDE.md §8.1 mencantumkan **Kasir Wilayah** (scope "Wilayah = kumpulan
cabang") dan **Sekretaris**. Di kode: hanya `kasir_unit` yang ada (1 literal,
`rulesfirebase.txt:423`, scope **cabang tunggal** bukan wilayah). `sekretaris`
= 0 literal di Android maupun rules.

Artinya salah satu: (a) fitur belum dibangun, atau (b) dokumentasi menggambarkan
rencana. Rancangan memasukkan keduanya sebagai enum placeholder read-only, dan
**tidak** memodelkan "wilayah" sama sekali. Kalau Kasir Wilayah nyata dipakai,
`002` memberi mereka hak yang salah.

### R-04 — Pembersihan NIK saat pencairan/serah terima `SEDANG`

Alur cairkan–serah terima sengaja **mengosongkan NIK** (`"nik" to ""`,
`"nikSuami"`, `"nikIstri"`) dan URL foto KTP — lihat
`PelangganViewModel.buildCairkanCleansePayload`, yang kembarannya
`applyCairkanCleanseTo` dipanggil di `SyncManager.kt:537` dan `:1211`.
Komentar di sana menyebut Cloud Function menyusul menghapus
`nik_registry/{oldNik}`. Akibatnya:
- `nasabah.nik` tidak bisa `not null`;
- unique index parsial `where nik is not null` sudah menanganinya;
- tetapi nasabah yang **kembali meminjam** setelah dibersihkan akan tampak
  sebagai orang baru — pencegahan duplikat lewat NIK tidak berlaku baginya.

Ini perilaku existing yang dibawa apa adanya, bukan yang saya perkenalkan.
Perlu keputusan: apakah cleanse harus menyimpan hash NIK agar deduplikasi tetap
jalan tanpa menyimpan NIK-nya.

### R-05 — `clientOpId` kosong pada data lama memblokir `unique not null` `SEDANG`

`Pembayaran.clientOpId` default `""` (`PelangganViewModel.kt:112`); field ini
ditambahkan belakangan, jadi seluruh pembayaran sebelum rilis itu punya nilai
kosong. `pembayaran.client_op_id uuid not null unique` akan menolaknya.

Rencana di `004` §4: UUID v5 deterministik dari
`(legacy_pelanggan_id, pinjaman_ke, index, tanggal, jumlah)`.
**Kelemahan yang harus diakui:** dua pembayaran sah dengan tanggal dan jumlah
identik pada pinjaman yang sama (misalnya dua setoran Rp 25.000 di hari yang
sama) akan menghasilkan UUID yang sama dan salah satunya hilang. `index` array
memang membedakannya — tetapi indeks array RTDB **bergeser** kalau ada
penghapusan di tengah (sumber "array gaps"), sehingga tidak stabil antar
re-run. Ini **belum terpecahkan**; jangan anggap sudah.

### R-06 — RLS mempersempit hak Koordinator `SEDANG`

`rulesfirebase.txt:8` dan `:12` memberi koordinator akses baca **dan tulis**
global ke `pelanggan/{adminUid}` mana pun, tanpa filter cabang.
`koperasi_priv.cabang_terlihat()` di `002` membatasinya ke cabang di
`koordinator_cabang`.

Ini pengetatan yang **saya pilih**, bukan cerminan sistem berjalan. Kalau
koordinator memang perlu lihat semua cabang, tabel `koordinator_cabang` harus
diisi lengkap atau helper-nya dilonggarkan. Kalau tidak, layar
`KoordinatorDaftarSemuaNasabahScreen` akan tampak kosong sebagian setelah
migrasi — dan itu akan terlihat seperti kehilangan data, padahal soal izin.

### R-07 — Privilege escalation lewat policy `app_user_ubah_diri` `TINGGI`

Sudah dicatat sebagai L-1 di `002` §11, diulang di sini karena berat:
policy itu mengizinkan user memperbarui barisnya sendiri, dan `WITH CHECK`
Postgres bekerja **per-baris, bukan per-kolom**. Seorang admin dapat menaikkan
`role`-nya sendiri menjadi `pengawas`.

**Jangan jalankan `002` apa adanya di lingkungan mana pun yang memuat data
nyata.** Penutupnya trigger `BEFORE UPDATE` yang menolak perubahan
`role`/`cabang_id` oleh non-pengawas. Sengaja saya tidak tambahkan diam-diam:
Anda perlu melihat lubangnya sebelum ia ditambal.

### R-08 — Signed URL mengubah perilaku foto offline `SEDANG`

`fotoKtpUrl` dkk. sekarang menyimpan URL permanen; rancangan `003` §4
menggantinya dengan signed URL 1 jam. Foto yang pernah dibuka tetap tampil
lewat cache Coil; yang belum pernah dibuka tidak akan tampil saat offline.
Admin lapangan bekerja di area tanpa sinyal — ini bisa terasa sebagai regresi
nyata. Perlu keputusan sebelum implementasi.

### R-09 — Riwayat approval Koordinator sudah hilang di sumbernya `RENDAH`

`dualApprovalInfo` hanya punya **satu** `koordinatorApproval`
(`DualApprovalModels.kt:108-109`) untuk dua fase (2 dan 4). Keputusan fase 2
tertimpa fase 4. Skema baru bisa menyimpan keduanya, tetapi **data historisnya
tidak bisa dipulihkan** — sudah tertimpa di RTDB. Migrasi hanya bisa mengisi
fase 4. Kerugian yang sudah terjadi, bukan yang diakibatkan migrasi.

### R-10 — `jurnal_transaksi` tidak punya rules RTDB `RENDAH`

`jurnal_transaksi` tidak muncul sama sekali di `rulesfirebase.txt` (33 node
top-level terdaftar; node ini bukan salah satunya), sehingga tunduk pada
default-deny dan hanya dapat ditulis Admin SDK. Rancangan `002` menirukan itu
(tanpa `GRANT INSERT` untuk `authenticated`). Yang belum saya verifikasi:
apakah Buku Pokok web membacanya lewat Cloud Function saja — kalau ada jalur
baca langsung, ia akan putus.

### R-11 — Nama node di CLAUDE.md ≠ nama sebenarnya `RENDAH`

CLAUDE.md §5.2 menyebut `jurnalTransaksi/{adminUid}/{pelangganId}/{timestamp}`.
Kode menulis `/jurnal_transaksi/{cabangId}/{tahun-bulan}/{autoId}`
(`jurnalTransaksi.js:6`) — nama node, tingkat partisi, dan kunci ketiganya
berbeda. Skrip ekspor yang mengikuti dokumentasi akan mengambil nol baris dan
tampak "berhasil". Ikuti kode.

### R-12 — `faktur_bu/` tanpa aturan Storage `SEDANG`

Lihat `003` T-1: web meng-upload ke `faktur_bu/...`
(`buku-pokok-web/app/kasir/page.js:1423`) sementara `rulesstorage.txt` tidak
punya `match` untuknya (default-deny). Entah upload nota kasir gagal di
produksi, entah rules ter-deploy berbeda dari repo. **Tidak bisa saya pastikan
dari repo saja** — perlu Firebase Console. Inventaris objek di `003` §5 akan
tidak lengkap kalau ini tidak dijawab dulu.

### R-13 — Tidak ada padanan sesi/tracking `RENDAH` (untuk sekarang)

`session_lock`, `force_logout`, `remote_takeover`, `device_presence`,
`location_tracking`, `user_locations`, `fcm_tokens`, dan seluruh node
notifikasi belum dimodelkan. Semuanya fitur sesi/realtime, bukan data
keuangan, dan butuh rancangan tersendiri (Supabase Realtime + presence).
Di luar lingkup Fase 1 — dicatat supaya tidak terlupakan sebagai "sudah
tercakup".

---

## 3. Yang Saya Butuhkan dari Anda

Untuk mempersempit rancangan ini dari asumsi menjadi fakta:

1. **Ekspor RTDB penuh** (atau minimal `select distinct` atas):
   `status`, `statusKhusus`, `statusPencairanSimpanan`, `statusSerahTerima`,
   `tipePinjaman`, dan `metadata/admins/*/role`.
   → menyelesaikan A-01, A-03, R-03.
2. **Jumlah baris**: total nasabah, total pembayaran, total entri jurnal.
   → menyelesaikan A-08 dan menentukan strategi impor.
3. **Jawaban atas R-12**: apakah rules Storage yang ter-deploy sama dengan
   `rulesstorage.txt`?
4. **Keputusan R-06**: koordinator global atau per-cabang?
5. **Keputusan R-08**: signed URL boleh, atau perlu jaminan foto offline?
6. **Keputusan Fase 2**: dual-write atau cutover sekali jalan? (R-01)

Nomor 1 dan 3 adalah **pemblokir**: tanpa keduanya, skrip impor apa pun yang
saya tulis akan bertumpu pada tebakan.

---

## 4. Yang TIDAK Diubah pada Fase Ini

Sesuai batasan Anda dan CLAUDE.md §10, dikonfirmasi lewat `git status`:

- Tidak ada file Android/Kotlin yang disentuh.
- Tidak ada UI, ViewModel, Room, atau WorkManager yang disentuh.
- Tidak ada logic Firebase (`functions/`) yang disentuh.
- Tidak ada rules (`rulesfirebase.txt`, `rulesstorage.txt`) yang disentuh.
- Tidak ada deploy, tidak ada instance Supabase yang dibuat.

Yang ditambahkan hanya lima berkas di `docs/migration/supabase/` — dokumen dan
SQL yang belum pernah dijalankan.
