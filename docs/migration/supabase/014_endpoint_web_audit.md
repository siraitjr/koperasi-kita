# Audit 14 Endpoint Web → View + RLS

Tahap B (`010` §4). **Analisis dan rancangan — belum ada SQL yang dijalankan.**

Sumber: `functions/bukuPokokApi.js` (1179 baris), `kasirApi.js` (714),
`jurnalTransaksiApi.js` (458), `koreksiStorting.js` (119),
`rekeningKoranService.js` (239), disilangkan dengan `buku-pokok-web/lib/api.js`
dan `public/rk.html`.

---

## 0. PERINGATAN TEKNIS YANG MENENTUKAN SELURUH TAHAP INI

**View di PostgreSQL secara default MEM-BYPASS RLS tabel di bawahnya.**

View dieksekusi dengan hak pemiliknya, bukan hak pemanggil. Jadi view biasa
di atas `koperasi.nasabah` akan menampilkan **seluruh baris** kepada siapa
pun yang boleh membacanya — seluruh kerja RLS di `002` menjadi sia-sia, dan
kegagalannya **senyap**: tidak ada galat, hanya admin lapangan yang tiba-tiba
melihat nasabah cabang lain.

Postgres 15+ (yang dipakai Supabase) menyediakan penawarnya:

```sql
create view koperasi.v_buku_pokok
with (security_invoker = on) as
select ...
```

**Setiap view di dokumen ini WAJIB memakai `security_invoker = on`.**
Tanpa itu, memindahkan endpoint ke view bukan penyederhanaan melainkan
pembukaan akses. Ini satu-satunya hal di Tahap B yang kalau salah, salahnya
tidak akan terlihat sampai ada yang melapor.

Verifikasi setelah dibuat:

```sql
select c.relname, c.reloptions
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'koperasi' and c.relkind = 'v';
-- setiap baris harus memuat security_invoker=on
```

---

## 1. Klasifikasi 14 Endpoint

| # | Endpoint | Sifat | Putusan |
|---|---|---|---|
| 1 | `getBukuPokok` | baca | **View** (+ pivot di klien) |
| 2 | `getBukuPokokSummary` | baca | **View** |
| 3 | `getPembayaranHariIni` | baca | **View** |
| 4 | `getKasirSummary` | baca | **View** |
| 5 | `getKasirEntries` | baca | **View** |
| 6 | `getJurnalTransaksi` | baca | **View** |
| 7 | `getKoreksiStorting` | baca | **View** |
| 8 | `getRekeningKoran` | baca | **Edge Function** — auth HMAC, tanpa login |
| 9 | `addKasirEntry` | tulis | **RPC** |
| 10 | `deleteKasirEntry` | tulis | **RPC** |
| 11 | `setKoreksiStorting` | tulis | **RPC** |
| 12 | `syncOperasionalTransport` | tulis | **RPC** |
| 13 | `backfillJurnalTransaksi` | tulis | **Tidak dimigrasikan** — alat sekali-jalan |
| 14 | `generateAutoLoginToken` | — | **Selesai** di Tahap A (`012`) |

Jadi Tahap B sesungguhnya: **7 view + 4 RPC**, satu Edge Function
(`getRekeningKoran`), satu dibuang, satu sudah selesai.

### Peran yang boleh mengakses

Dari `kasirApi.js:188`:

```js
const KASIR_ALLOWED_ROLES = ['kasir_unit','kasir_wilayah','sekretaris',
                             'pimpinan','koordinator','pengawas'];
```

Ini **menjawab R-03** (`005`): `kasir_wilayah` dan `sekretaris` bukan sekadar
tercantum di CLAUDE.md — keduanya dipakai sebagai gate akses nyata. Enum
`user_role` di `001` sudah memuat keduanya, jadi tidak ada perubahan skema.

Keempat berkas baca memakai `verifyIdToken` + `getUserRole()` yang membaca
`metadata/admins/{uid}`. Di Postgres seluruhnya digantikan
`koperasi_priv.role()` dan `boleh_lihat_cabang()` yang sudah ada di `002`.

---

## 2. Rancangan View

Semua memakai helper `002`: `koperasi_priv.role()`,
`koperasi_priv.is_pengawas()`, `koperasi_priv.boleh_lihat_cabang()`.

### 2.1 `v_buku_pokok` — inti Tahap B

`getBukuPokok` adalah 850 baris, tetapi **sebagian besar bukan logika bisnis**
— melainkan siasat terhadap bentuk RTDB: menelusuri `riwayat_pinjaman` untuk
generasi lama, meratakan array `pembayaranList` bercelah, dan memindahkan
pelunasan top-up ke baris historis (`:551`). Di Postgres ketiganya lenyap:
**generasi sudah berupa baris** (`001` §3), dan pembayaran sudah berupa
tabel.

```sql
create view koperasi.v_buku_pokok
with (security_invoker = on) as
select
  n.id                          as nasabah_id,
  n.cabang_id,
  n.admin_id,
  u.nama                        as admin_nama,
  n.nomor_anggota,
  n.nama_ktp,
  n.nama_panggilan,
  n.wilayah,
  n.status_khusus,
  p.id                          as pinjaman_id,
  p.pinjaman_ke,
  p.status,
  p.besar_pinjaman,
  p.total_pelunasan,
  p.total_diterima,
  p.biaya_admin,
  p.simpanan_awal,
  p.tenor,
  p.jasa_pinjaman,
  p.tanggal_pencairan,
  s.total_dibayar,
  s.sisa_utang,
  -- Baris HISTORIS = generasi yang bukan tertinggi. Menggantikan seluruh
  -- blok "relokasi pelunasan top-up" (bukuPokokApi.js:551) — di sini cukup
  -- perbandingan nomor generasi.
  (p.pinjaman_ke < max(p.pinjaman_ke) over (partition by n.id)) as is_historis,
  -- Turunan status; cermin bukuPokokApi.js:61-63.
  (n.status_khusus = 'MENUNGGU_PENCAIRAN')                        as is_sisa_tabungan,
  (n.status_khusus <> 'MENUNGGU_PENCAIRAN'
     and s.sisa_utang <= 0 and p.total_pelunasan > 0)             as is_lunas,
  (n.status_khusus <> 'MENUNGGU_PENCAIRAN'
     and not (s.sisa_utang <= 0 and p.total_pelunasan > 0)
     and p.status in ('Aktif','Disetujui'))                        as is_aktif
from koperasi.nasabah n
join koperasi.pinjaman p        on p.nasabah_id = n.id
join koperasi.v_pinjaman_saldo s on s.pinjaman_id = p.id
left join koperasi.app_user u   on u.id = n.admin_id
where n.arsip_at is null;
```

**Kolom storting per tanggal (PB/L1/CM/MB/ML) sengaja TIDAK di-pivot di SQL.**
Jumlah kolomnya bergantung tanggal yang diminta, jadi pivot di SQL menuntut
SQL dinamis — mahal dan rapuh. Pembayarannya diambil terpisah:

```sql
create view koperasi.v_pembayaran_harian
with (security_invoker = on) as
select
  b.pinjaman_id, p.nasabah_id, n.cabang_id, n.admin_id,
  b.tanggal, b.jenis,
  sum(b.jumlah) as jumlah,
  count(*)      as banyak_transaksi
from koperasi.pembayaran b
join koperasi.pinjaman p on p.id = b.pinjaman_id
join koperasi.nasabah  n on n.id = p.nasabah_id
left join koperasi.pembayaran_koreksi k on k.pembayaran_id = b.id
where k.id is null                       -- pembayaran terkoreksi tidak dihitung
group by b.pinjaman_id, p.nasabah_id, n.cabang_id, n.admin_id, b.tanggal, b.jenis;
```

Web menyusunnya jadi kolom — pekerjaan yang **sudah dilakukannya sekarang**
(`extractPembayaranPerTanggal`, `bukuPokokApi.js:181` hanya menyiapkan
datanya; penyusunan kolom terjadi di sisi klien).

**RLS:** tidak perlu policy baru. `security_invoker` membuat view tunduk pada
policy `nasabah_baca` dan `pembayaran_baca` yang sudah ada di `002` §4 dan §6
— admin melihat nasabahnya, atasan melihat cabangnya.

### 2.2 `v_buku_pokok_summary`

```sql
create view koperasi.v_buku_pokok_summary
with (security_invoker = on) as
select
  cabang_id,
  count(*) filter (where is_aktif)          as nasabah_aktif,
  count(*) filter (where is_lunas)          as nasabah_lunas,
  count(*) filter (where is_sisa_tabungan)  as nasabah_sisa_tabungan,
  sum(besar_pinjaman) filter (where is_aktif) as total_pinjaman_aktif,
  sum(sisa_utang)     filter (where is_aktif) as total_piutang
from koperasi.v_buku_pokok
where not is_historis
group by cabang_id;
```

Bertumpu pada 2.1 — **inilah satu-satunya ketergantungan antar-view**, dan
itu menentukan urutan eksekusi di §5.

### 2.3 `v_pembayaran_hari_ini`

```sql
create view koperasi.v_pembayaran_hari_ini
with (security_invoker = on) as
select cabang_id, admin_id, tanggal,
       sum(jumlah) as total, count(*) as banyak
from koperasi.v_pembayaran_harian
where tanggal = (now() at time zone 'Asia/Jakarta')::date
group by cabang_id, admin_id, tanggal;
```

Zona waktu ditulis eksplisit — `getTodayIndonesia()`
(`bukuPokokApi.js:236`) ada justru karena ini pernah salah.

### 2.4 `v_kasir_entry` & `v_kasir_summary`

```sql
create view koperasi.v_kasir_entry
with (security_invoker = on) as
select k.*, u.nama as target_admin_nama
from koperasi.kasir_entry k
left join koperasi.app_user u on u.id = k.target_admin_id;

create view koperasi.v_kasir_summary
with (security_invoker = on) as
select cabang_id, periode, jenis,
       sum(nominal) filter (where arah = 'masuk')  as masuk,
       sum(nominal) filter (where arah = 'keluar') as keluar,
       sum(case when arah = 'masuk' then nominal else -nominal end) as saldo
from koperasi.kasir_entry
group by cabang_id, periode, jenis;
```

Kolom `arah` inilah yang di `001` asli tidak ada dan ditambahkan `001a` §5 —
tanpanya `saldo` terbalik.

**RLS:** `002` §9 memberi `kasir_baca` untuk **semua** `authenticated`.
Itu lebih longgar daripada `KASIR_ALLOWED_ROLES` di `kasirApi.js:188`, yang
menolak `admin`. Perlu diputuskan (§6).

### 2.5 `v_jurnal_transaksi`

```sql
create view koperasi.v_jurnal_transaksi
with (security_invoker = on) as
select j.*, n.nama_ktp as nasabah_nama_ktp, u.nama as admin_nama
from koperasi.jurnal_transaksi j
left join koperasi.nasabah  n on n.id = j.nasabah_id
left join koperasi.app_user u on u.id = j.admin_id;
```

Policy `jurnal_baca` (`002` §9) sudah membatasi per cabang.

### 2.6 `v_koreksi_storting`

```sql
create view koperasi.v_koreksi_storting
with (security_invoker = on) as
select k.*, u.nama as admin_nama, w.nama as diubah_oleh_nama
from koperasi.koreksi_storting k
left join koperasi.app_user u on u.id = k.admin_id
left join koperasi.app_user w on w.id = k.updated_by;
```

---

## 3. Yang TIDAK Bisa Jadi View

### 3.1 `getRekeningKoran` → tetap Edge Function

`rekeningKoranService.js` memakai **HMAC** (`createHmac`, 3 kemunculan) dan
**tidak memakai `verifyIdToken` sama sekali**. Halaman `public/rk.html` dibuka
dari tautan bertanda tangan — nasabah/pihak luar yang **tidak punya akun**.

RLS bekerja atas `auth.uid()`. Tanpa pengguna yang login, tidak ada identitas
untuk disaring, jadi view + RLS secara struktural tidak bisa menggantikannya.

**Spesifikasi:**
| | |
|---|---|
| Aksi | `GET /functions/v1/rekening-koran?pid=…&sig=…&exp=…` |
| Input | id nasabah, kedaluwarsa, tanda tangan HMAC |
| Proses | verifikasi HMAC + `exp`, lalu baca dengan `service_role` |
| Output | identitas ringkas + riwayat pembayaran |
| Rahasia | kunci HMAC di secret Edge Function, **bukan** di repo |

Dua hal yang harus ikut dibawa: **masa berlaku tautan** (tanpa `exp`, tautan
bocor berlaku selamanya) dan **pembatasan kolom** — jangan kirim NIK/alamat
ke halaman publik.

### 3.2 Empat operasi tulis → RPC `SECURITY DEFINER`

Bukan karena rumit, melainkan karena semuanya **menulis**, dan `002` sengaja
tidak memberi hak tulis luas kepada klien. Pola sama seperti `007`.

| RPC | Input | Wewenang | Catatan |
|---|---|---|---|
| `rpc_tambah_kasir_entry` | cabang, tanggal, jenis, arah, nominal, keterangan, target_admin, client_op_id | `kasir_unit` di cabangnya | `client_op_id` UNIQUE → idempoten |
| `rpc_hapus_kasir_entry` | entry_id | pembuatnya atau `pengawas` | Sebaiknya **soft delete**, sejalan `007` |
| `rpc_set_koreksi_storting` | cabang, admin, periode, cm/l1/mb/ml | `pimpinan`/`pengawas` | **Mengubah angka pembukuan** — catat `updated_by` |
| `rpc_sync_operasional_transport` | cabang, periode | `kasir_unit`/`pengawas` | Perlu dibaca ulang; kemungkinan bisa jadi view |

### 3.3 `backfillJurnalTransaksi` → dibuang

Alat sekali-jalan untuk mengisi jurnal yang tertinggal — kelas yang sama
dengan 20 endpoint legacy di `010` §2. Di Postgres jurnal ditulis lewat
`rpc_catat_jurnal` (`007`) pada saat transaksi terjadi, jadi tidak ada yang
perlu di-backfill.

Tetapi `lib/api.js` **masih memanggilnya**, jadi pemanggilnya harus dihapus
saat web dikerjakan — bukan dibiarkan menunjuk endpoint yang hilang.

---

## 4. Yang Hilang Bersamaan

`bukuPokokApi.js` punya cache dalam memori 10 menit (`getFromCache`,
`setToCache`, `isCacheBypassActive`) — ada karena tiap permintaan harus
memindai `pelanggan/`, `riwayat_pinjaman/`, dan `pembayaran_harian/` di RTDB.
View tidak punya cache; ia menghitung saat dibaca.

Untuk ukuran data Anda (±3.000 pinjaman, ±51.000 pembayaran) itu ringan bagi
Postgres. **Tetapi belum saya ukur** — tidak ada instance di sisi saya. Ukur
`explain analyze` pada `v_buku_pokok` untuk satu cabang sebelum web
dipindahkan. Kalau lambat, penawarnya index atau materialized view, bukan
kembali ke cache aplikasi.

---

## 5. Urutan Eksekusi

Diurutkan menaik menurut risiko dan ketergantungan.

**B-1 — fondasi, tanpa ketergantungan**
1. `v_pembayaran_harian` — dipakai B-2 dan B-3.
2. `v_buku_pokok` — dipakai `v_buku_pokok_summary`.

**B-2 — turunan**
3. `v_buku_pokok_summary` (butuh 2)
4. `v_pembayaran_hari_ini` (butuh 1)

**B-3 — berdiri sendiri, tanpa risiko silang**
5. `v_kasir_entry`, `v_kasir_summary`
6. `v_jurnal_transaksi`
7. `v_koreksi_storting`

**B-4 — tulis**
8. Empat RPC (§3.2).

**B-5 — terakhir**
9. `getRekeningKoran` → Edge Function. Ditaruh paling akhir karena
   satu-satunya yang **terekspos tanpa login**; kesalahan di sini terlihat
   oleh pihak luar, bukan hanya staf.

Setelah tiap langkah, **bandingkan keluaran view dengan endpoint Firebase
untuk cabang yang sama** sebelum lanjut. Angka yang meleset lebih mudah
ditemukan satu view sekaligus daripada tujuh.

---

## 6. Yang Perlu Diputuskan

1. **Cakupan baca kasir.** `002` §9 `kasir_baca` mengizinkan **semua**
   pengguna terautentikasi; `kasirApi.js:188` menolak `admin`. Mana yang
   dipakai? Rekomendasi: ikuti daftar `KASIR_ALLOWED_ROLES` — lebih ketat,
   dan itu perilaku yang berjalan sekarang.
2. **`deleteKasirEntry`: hapus permanen atau soft delete?** Sejalan `007`
   saya sarankan soft delete; entri kasir adalah catatan uang.
3. **`syncOperasionalTransport`** belum saya baca isinya — kalau ternyata
   hanya menghitung dan menyalin, ia bisa jadi view, bukan RPC.
4. **Kunci HMAC rekening koran** — nilainya sekarang ada di
   `rekeningKoranService.js`. Perlu dicek apakah ter-commit; kalau ya, harus
   diganti saat pindah, bukan disalin.

---

## 7. Batas Jujur

- Tidak ada SQL di dokumen ini yang pernah dijalankan. Tidak ada Postgres di
  sisi saya.
- Rancangan `v_buku_pokok` diturunkan dari membaca `bukuPokokApi.js`, bukan
  dari membandingkan keluarannya dengan data sungguhan. **Kolom storting dan
  aturan baris historis adalah bagian yang paling mungkin meleset** — di sana
  logika aslinya paling padat, dan hasilnya paling terlihat oleh pengguna.
- `syncOperasionalTransport` dan `getKasirSummary` belum dibaca baris demi
  baris; klasifikasinya berdasarkan nama, tanda tangan, dan pola tulis/baca.
- Perbandingan angka view vs RTDB (`010` §4 Tahap A no. 2) dinyatakan "cukup
  kuat" oleh pemilik; saya tidak melihat hasilnya. Kalau perbandingan itu
  hanya menyentuh `summary` global, `v_buku_pokok` tetap perlu diuji sendiri
  — ia jauh lebih rinci.
