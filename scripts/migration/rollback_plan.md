# Rencana Rollback — Cutover Firebase → Supabase

**Belum pernah dijalankan/diuji.** Menyertai `migrate.js`, `validate.js`, dan
`docs/migration/supabase/006_migration_script.md`.

---

## 0. Prinsip

**Firebase tetap utuh sepanjang proses.** `migrate.js` membuka export JSON
read-only dan tidak pernah menyentuh RTDB, Storage, maupun Cloud Functions.
Karena itu rollback teknisnya murah: hentikan pemakaian Supabase, kembalikan
aplikasi ke Firebase.

Yang **mahal** bukan mengembalikan data, melainkan **transaksi yang terjadi
setelah cutover**. Setiap pembayaran yang dicatat admin lapangan ke Supabase
setelah jam-H tidak ada di Firebase. Itulah yang menentukan apakah rollback
masih mungkin — bukan keadaan database.

**Aturan tunggal:** selama jendela beku (§2), jangan ada satu pun transaksi
masuk. Kalau ada, rollback berubah dari "ganti konfigurasi" menjadi
"rekonsiliasi manual".

---

## 1. Titik Keputusan

| Kapan | Gerbang | Kalau gagal |
|---|---|---|
| G1 | `migrate.js --dry-run` bersih dari anomali fatal | perbaiki data/skrip. Belum ada apa pun yang berubah. |
| G2 | `001` + `001a` + `002` terpasang tanpa error | perbaiki SQL. Belum ada data. |
| G3 | `migrate.js --execute` `commit` sukses | otomatis `rollback`; lihat §3.1 |
| G4 | `validate.js` keluar dengan kode 0 | **JANGAN cutover**; lihat §3.2 |
| G5 | Uji asap aplikasi lolos | rollback aplikasi; lihat §3.3 |
| G6 | 72 jam pertama stabil | rollback dengan rekonsiliasi; lihat §3.4 |

Rollback makin mahal dari G3 ke G6. Biaya melompati gerbang jauh lebih besar
daripada biaya menunggu satu siklus lagi.

---

## 2. Jendela Beku (mutlak)

Sebelum `migrate.js --execute`:

1. Umumkan ke seluruh admin lapangan, pimpinan, koordinator, pengawas, kasir.
2. Pastikan **antrean offline setiap perangkat sudah kosong**. Ini yang paling
   mudah terlewat: Room menyimpan operasi yang belum ter-sync di perangkat,
   dan operasi itu akan mendarat di Firebase **setelah** export diambil.
   Verifikasi lewat layar Status Sinkronisasi tiap perangkat — angka
   "menunggu" **harus 0**, bukan "kecil".
3. Baru setelah itu ambil export RTDB. Export sebelum antrean kosong = data
   hilang diam-diam.
4. Jangan ada yang membuka aplikasi sampai G5 selesai.

Kalau langkah 2 tidak bisa dijamin, cutover harus ditunda. Tidak ada mitigasi
teknis untuk transaksi yang belum sampai ke server saat snapshot diambil.

---

## 3. Prosedur per Titik Gagal

### 3.1 Gagal saat impor (G3)

Tidak ada tindakan data. Seluruh impor berjalan dalam satu transaksi; kegagalan
memicu `rollback` otomatis dan database tetap kosong.

Bila proses terbunuh sebelum `commit` (mati listrik, koneksi putus), transaksi
dibatalkan server saat sesi berakhir. Pastikan:

```sql
select count(*) from koperasi.nasabah;   -- harus 0
```

Kalau ternyata tidak 0, jalankan §3.2.

### 3.2 Gagal validasi (G4) — bersihkan lalu ulangi

Database sudah terisi tetapi angkanya tidak cocok. Karena `migrate.js`
idempoten, pilihan paling bersih adalah mengosongkan lalu mengimpor ulang.

```sql
begin;
truncate table
  koperasi.approval_step, koperasi.pengajuan,
  koperasi.pembayaran_koreksi, koperasi.pembayaran,
  koperasi.jadwal_cicilan, koperasi.simpanan,
  koperasi.jurnal_transaksi, koperasi.kasir_entry,
  koperasi.permintaan, koperasi.dokumen, koperasi.sync_inbox,
  koperasi.pinjaman_history, koperasi.biaya_awal, koperasi.pelanggan_ditolak,
  koperasi.pinjaman, koperasi.nasabah
  restart identity cascade;
commit;
```

`pinjaman_history` dan `pelanggan_ditolak` memakai trigger append-only, tetapi
`TRUNCATE` **tidak** memicu trigger BEFORE DELETE — jadi perintah di atas
tetap berjalan. Itu memang yang diinginkan di sini (mengulang impor), dan
sekaligus pengingat bahwa append-only melindungi dari `DELETE`, bukan dari
`TRUNCATE`. Hanya jalankan selama Firebase masih memegang kebenaran.

`cabang` dan `app_user` sengaja **tidak** di-truncate: keduanya bertaut ke
`auth.users`, dan menghapusnya memutus akun yang mungkin sudah dibuat.
Bila memang perlu, hapus akun Auth-nya lebih dulu.

Alternatif yang lebih aman untuk lingkungan yang sudah berisi akun: buang
seluruh schema dan pasang ulang dari nol.

```sql
drop schema if exists koperasi cascade;
drop schema if exists koperasi_priv cascade;
-- lalu jalankan lagi 001 → 001a → 002
```

Ini menghapus **seluruh** data koperasi di Postgres. Aman hanya karena Firebase
masih memegang kebenaran. Jangan jalankan setelah G6.

### 3.3 Gagal uji asap (G5) — kembalikan aplikasi ke Firebase

Belum ada transaksi baru, jadi ini murni pengembalian konfigurasi:

1. Kembalikan konfigurasi klien ke Firebase (Android dan web).
2. Buka kembali akses untuk staf.
3. Biarkan schema `koperasi` apa adanya — tidak mengganggu apa pun, dan
   berguna untuk diagnosis. Jangan buru-buru menghapusnya.
4. Catat penyebab kegagalan sebelum mengulang siklus.

Waktu pemulihan ≈ selama deploy konfigurasi. Tidak ada data yang hilang.

### 3.4 Gagal setelah beroperasi (G6) — rollback dengan rekonsiliasi

Ini kasus mahal. Sudah ada transaksi yang hanya hidup di Supabase.

**Jangan** langsung mengembalikan aplikasi ke Firebase — transaksi pasca-cutover
akan lenyap dari pandangan pengguna tanpa jejak.

Urutan:

1. **Bekukan lagi.** Hentikan seluruh input. Tanpa ini, selisihnya terus tumbuh.
2. **Ekstrak delta** — semua baris yang lahir setelah jam cutover:
   ```sql
   select * from koperasi.pembayaran where created_at >= :cutover_ts order by created_at;
   select * from koperasi.pinjaman   where created_at >= :cutover_ts order by created_at;
   select * from koperasi.nasabah    where created_at >= :cutover_ts order by created_at;
   select * from koperasi.approval_step where decided_at >= :cutover_ts order by decided_at;
   ```
3. **Putuskan arah.** Bila delta kecil (puluhan baris): masukkan manual ke
   Firebase lewat Console/Admin SDK, lalu kembalikan aplikasi. Bila besar
   (ratusan+): memperbaiki maju di Supabase biasanya lebih murah dan lebih
   aman daripada rollback — rollback dengan rekonsiliasi manual adalah sumber
   kesalahan pembukuan yang baru.
4. **Rekonsiliasi wajib** sebelum membuka kembali: total pembayaran per cabang
   per hari di kedua sistem harus sama. Bandingkan dengan
   `jurnal_transaksi` sebagai wasit — node itu immutable di kedua sisi.
5. Simpan dump Supabase sebelum apa pun dihapus:
   `pg_dump --schema=koperasi` disimpan sebagai bukti audit.

**Ambang keputusan yang disarankan:** setelah 72 jam beroperasi normal, anggap
rollback tidak lagi tersedia dan perbaiki maju. Nyatakan ambang ini sebelum
cutover, bukan saat panik.

---

## 4. Cadangan yang Harus Ada Sebelum Mulai

| Cadangan | Cara | Kapan |
|---|---|---|
| Export RTDB penuh | Firebase Console → Realtime Database → Export JSON | saat jendela beku, setelah antrean kosong |
| Salinan kedua export | checksum `sha256sum` disimpan terpisah | sebelum impor |
| Rules RTDB & Storage | sudah di repo (`data/rulesfirebase.txt`, `data/rulesstorage.txt`) | sudah ada |
| Objek Storage | belum ada skrip — Firebase Storage **tidak** disentuh migrasi ini | — |
| Snapshot Supabase pra-impor | Supabase → Database → Backups | sebelum `--execute` |

Simpan `sha256sum` export dan pakai **berkas yang sama persis** untuk impor dan
validasi. Export ulang menghasilkan indeks array yang bisa bergeser, dan itu
mengubah `client_op_id` turunan (lihat `006` §4).

---

## 5. Yang Tidak Ditangani Rencana Ini

- **Objek Firebase Storage.** Tidak ada yang dipindahkan, jadi tidak ada yang
  perlu di-rollback. Foto tetap di Firebase Storage.
- **Password.** Tidak ikut migrasi. Hanya Pengawas yang diberi password saat
  migrasi; sisanya dibuat tanpa password. Rollback ke Firebase mengembalikan
  password lama semua orang — jadi bila sebagian staf sudah terlanjur set
  ulang lewat Supabase, mereka akan memakai password yang salah. Sertakan ini
  dalam pengumuman rollback.

- **Akun Supabase Auth.** `create_auth_users.js` membuat akun, dan rollback
  tidak menghapusnya. Itu disengaja: akun kosong tidak berbahaya, sedangkan
  menghapusnya memutus FK `app_user.id` dan mempersulit percobaan berikutnya.
  Hapus manual hanya kalau migrasi dibatalkan permanen.

- **Berkas `reset_links.csv`** (bila memakai `--emit-reset-links`) setara
  password. Hapus segera setelah dibagikan, dan jangan pernah di-commit.
- **Cloud Functions.** Tetap hidup dan tetap menulis ke RTDB selama tidak
  dimatikan. Selama masa paralel, Firebase justru tetap konsisten dengan
  sendirinya — itu yang membuat rollback G3–G5 murah.
- **R-07** (eskalasi privilese di `002`) masih terbuka atas permintaan Anda.
  Selama itu belum ditambal, jangan buka akses aplikasi ke publik meski cutover
  berhasil.
