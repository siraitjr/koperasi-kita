# Panduan Skrip Migrasi — Cutover Firebase → Supabase

Fase 2. **Skrip belum pernah dijalankan.** Tidak ada Postgres, `psql`, atau
instance Supabase di environment tempat skrip ini ditulis.

Berkas: `scripts/migration/migrate.js`, `scripts/migration/validate.js`,
`scripts/migration/rollback_plan.md`.

---

## 1. Yang Berubah Setelah Membaca Data Nyata

`data/firebase_sample.json` yang Anda push mengubah beberapa hal. **Baca bagian
ini sebelum menjalankan apa pun** — ada empat titik di mana `001_schema_v2.sql`
akan menolak data Anda yang sah.

### 1.1 Sampel itu terpotong — jangan dipakai sebagai sumber

Berkas sampel memuat **261 penanda** `"...": "(N more keys)"`. Pola pemotongnya:
satu anak per node tingkat atas, lalu maksimum 10 anak + penanda di tiap level
berikutnya. Jadi sampel membuktikan **bentuk path dan penamaan key**, bukan isi.

Konsekuensi langsung: `metadata` di sampel hanya memuat `admins`, sementara
`data/rulesfirebase.txt:9,41` jelas merujuk `metadata/cabang/{id}/pimpinanUid`
dan `metadata/roles/{role}/{uid}`. Keduanya **ada** di produksi tetapi terbuang
oleh pemotong. Skrip tetap membacanya.

`migrate.js` dan `validate.js` **menolak jalan** bila menemukan penanda itu
(exit code 3), supaya sampel tidak pernah terimpor sebagai data sungguhan.

### 1.2 Empat temuan yang membatalkan asumsi di `001`

| # | Temuan | Bukti | Akibat pada `001` |
|---|---|---|---|
| T-1 | `pinjaman_ke` bisa **0** | `riwayat_pinjaman/{admin}/-OjZ8XNws7T8gEW_Y4Dy` → generasi `['0','1']` | `check (pinjaman_ke >= 1)` **menolak data sah** |
| T-2 | Arsip generasi **sparse** | generasi yang ada: `['4']`, `['2']`, `['7']`, `['4','5']` — bukan 1..N rapat | `tg_pinjaman_generasi_berurutan` **menolak seluruh impor** |
| T-3 | `simpanan` adalah **skalar**, bukan array | `pelangganToMap` → `"simpanan" to pelanggan.simpanan` (Int) | tabel ledger `simpanan` **tidak punya sumber data** |
| T-4 | `subPembayaran` **bersarang** di dalam tiap pembayaran | `pelangganToMap` → `"subPembayaran"` di dalam map `pembayaranList` | butuh kolom `parent_pembayaran_id` agar tautan induk tidak hilang |

Tambahan yang tidak membatalkan tapi mengubah bentuk kolom:

- **`kasir_entries` berbeda dari rancangan.** Field nyata: `arah`
  (`masuk`/`keluar`), `jenis`, `jumlah`, `targetAdminUid`, `createdByName`.
  Rancangan `001` memakai `nominal` tanpa `arah` dan tanpa target admin —
  arah transaksi hilang, dan itu membalik makna saldo kasir.
- **`sekretaris` nyata ada** (`metadata/admins/BDtkXfOid…` role `sekretaris`,
  **tanpa** `cabang`). Ini menutup separuh R-03: perannya ada. Tetapi CHECK
  `app_user_cabang_wajib` di `001` mensyaratkan cabang untuk semua peran selain
  pengawas/koordinator → **menolak sekretaris**.
- **`cabangId` memakai spasi**: `panti`, `payakumbuh`,
  `simpang empat unit 1`, `simpang empat unit 2`. Dipakai apa adanya sebagai PK
  teks, hanya dinormalkan spasi/kapital.
- **`nik_registry.status` huruf kecil** (`"aktif"`) sedangkan `pelanggan.status`
  huruf besar (`"Aktif"`). Dua konvensi dalam satu basis data; skrip
  menormalkan keduanya.
- **`jurnal_transaksi` mengonfirmasi R-11**: path nyata
  `jurnal_transaksi/{cabangId}/{YYYY-MM}/{autoId}` (contoh `panti/2024-07/…`),
  bukan seperti tertulis di CLAUDE.md §5.2.
- **Node yang belum pernah dibahas**: `pinjamanHistory`
  (`{adminUid}/{pelangganId}/{pushId}` → `{berlakuSampai, besarPinjaman}`),
  `biaya_awal`, `absensi`, `rekap_harian`, `rekap_harian_final`,
  `operasional_harian`, `deleted_sampah`, `koreksi_storting`,
  `user_management_logs`, `jurnal_transaksi_meta`. **Tidak satu pun
  dimigrasikan** oleh skrip ini — lihat §6.

### 1.3 Patch skema WAJIB

Jalankan ini **setelah** `001` dan `002`, **sebelum** `migrate.js`.
Simpan sebagai `001a_schema_patch.sql`.

```sql
begin;

-- T-1: generasi pinjaman 0 nyata ada di riwayat_pinjaman.
alter table koperasi.pinjaman drop constraint if exists pinjaman_ke_positif;
alter table koperasi.pinjaman add  constraint pinjaman_ke_positif
  check (pinjaman_ke >= 0);

-- T-2: arsip generasi sparse. Urutan rapat tidak bisa ditegakkan atas data
-- historis. Trigger dipertahankan untuk data BARU, tetapi hanya memeriksa
-- "tidak menimpa generasi yang sudah ada" — pemeriksaan +1 dilepas.
create or replace function koperasi.tg_pinjaman_generasi_berurutan()
returns trigger language plpgsql as $$
declare v_max integer;
begin
  if new.pinjaman_ke = 0 then return new; end if;
  select max(pinjaman_ke) into v_max
    from koperasi.pinjaman where nasabah_id = new.nasabah_id;
  if v_max is not null and new.pinjaman_ke <= v_max then
    raise exception 'Generasi ke-% sudah/pernah ada untuk nasabah % (tertinggi %)',
      new.pinjaman_ke, new.nasabah_id, v_max using errcode='check_violation';
  end if;
  return new;
end; $$;

-- T-3: `simpanan` adalah skalar per pinjaman (sudah ada sebagai
-- pinjaman.simpanan_awal). Ledger simpanan tidak punya sumber data.
comment on table koperasi.simpanan is
  'KOSONG setelah migrasi: RTDB menyimpan simpanan sebagai skalar, bukan ledger.';

-- T-4: tautan sub-pembayaran ke pembayaran induk.
alter table koperasi.pembayaran
  add column if not exists parent_pembayaran_id uuid references koperasi.pembayaran(id);
create index if not exists pembayaran_parent_idx
  on koperasi.pembayaran (parent_pembayaran_id) where parent_pembayaran_id is not null;

-- kasir_entry: bentuk nyata.
alter table koperasi.kasir_entry
  add column if not exists arah text check (arah in ('masuk','keluar')),
  add column if not exists target_admin_id uuid references koperasi.app_user(id),
  add column if not exists dicatat_oleh_nama text not null default '';
alter table koperasi.kasir_entry alter column dicatat_oleh drop not null;
alter table koperasi.kasir_entry alter column client_op_id drop not null;

-- sekretaris & pengawas tidak punya cabang.
alter table koperasi.app_user drop constraint if exists app_user_cabang_wajib;
alter table koperasi.app_user add  constraint app_user_cabang_wajib
  check (role in ('pengawas','koordinator','sekretaris') or cabang_id is not null);

-- Impor membawa UID Firebase, bukan auth.users. Lihat §3.
alter table koperasi.app_user drop constraint if exists app_user_id_fkey;
alter table koperasi.app_user add column if not exists legacy_uid text unique;

-- Kolom jejak asal.
alter table koperasi.nasabah  add column if not exists legacy_admin_uid text;
alter table koperasi.pengajuan add column if not exists created_at timestamptz;

commit;
```

Butir terakhir perlu penjelasan: `001` mendefinisikan
`app_user.id references auth.users(id)`. Impor **mendahului** pembuatan akun
Supabase Auth, jadi FK itu harus dilepas dulu dan dipasang kembali setelah
akun dibuat (§3). Menjalankan `migrate.js` dengan FK masih terpasang akan gagal
di baris `app_user` pertama.

---

## 2. Prasyarat

```
Node.js >= 18       (skrip memakai CommonJS + crypto bawaan)
npm i pg            (hanya diperlukan saat --execute)
Export RTDB penuh   (±89 MB, .json)
RAM >= 8 GB         (JSON 89 MB → ±1 GB objek JS)
```

Skrip **tidak** memakai `@supabase/supabase-js`. Migrasi satu kali lebih baik
lewat koneksi Postgres langsung (`pg`): melewati PostgREST, tidak tunduk RLS,
dan mendukung satu transaksi besar.

DSN ada di Supabase → Project Settings → Database → Connection string (URI).
Gunakan **Session mode / port 5432**, bukan pooler 6543 — pooler transaksi
tidak mendukung `alter table … disable trigger` dalam satu transaksi panjang.

---

## 3. Urutan Menjalankan

```bash
cd scripts/migration

# 1. DRY-RUN. Tidak menyentuh database sama sekali.
node --max-old-space-size=8192 migrate.js \
     --file=~/export/koperasi-rtdb.json --dry-run

# → baca migration_report.json. Perbaiki/putuskan semua anomali DULU.

# 2. Terapkan skema (di Supabase SQL Editor, urut):
#    001_schema_v2.sql → 001a_schema_patch.sql → 002_rls_policies.sql
#    (002 masih memuat lubang R-07 yang Anda tunda — sadari saat menjalankan.)

# 3. Impor.
node --max-old-space-size=8192 migrate.js \
     --file=~/export/koperasi-rtdb.json \
     --dsn="postgresql://postgres:PASS@db.xxx.supabase.co:5432/postgres" \
     --execute

# 4. Validasi. Kode keluar 1 = jangan cutover.
node --max-old-space-size=8192 validate.js \
     --file=~/export/koperasi-rtdb.json --dsn="postgresql://…"
```

### Membuat akun Supabase Auth (langkah manual, setelah impor)

`migrate.js` mengisi `app_user` memakai UUID **deterministik** yang diturunkan
dari UID Firebase (`uuidv5('user:'+uid)`). Akun Auth belum ada. Setelah impor:

1. Untuk tiap baris `app_user`, buat akun lewat Admin API dengan **id yang
   sudah ditentukan** (`supabase.auth.admin.createUser({ id, email, … })`),
   sehingga `app_user.id` dan `auth.users.id` cocok tanpa pemetaan.
2. Pasang kembali FK:
   ```sql
   alter table koperasi.app_user
     add constraint app_user_id_fkey foreign key (id) references auth.users(id);
   ```
3. Kirim undangan reset password. **Password lama tidak ikut** — hash Firebase
   tidak dapat diimpor ke Supabase Auth. Semua staf harus set ulang password.
   Ini konsekuensi cutover yang perlu diumumkan sebelum hari-H.

---

## 4. Cara Skrip Menjaga Kebenaran

**Idempoten.** Semua primary key adalah UUIDv5 dari path Firebase-nya
(`uuidv5('nasabah:'+adminUid+'/'+pelangganId)` dst.), dan setiap INSERT memakai
`ON CONFLICT DO NOTHING`. Menjalankan ulang skrip tidak menggandakan apa pun.
**Jangan pernah mengubah string prefiks id** setelah impor pertama — semua id
akan berubah dan idempotensi hilang.

**Satu transaksi.** Seluruh impor `begin … commit`. Kegagalan di tabel mana pun
me-`rollback` semuanya; tidak ada keadaan setengah jadi.

**Trigger dimatikan selama impor.** `pinjaman_generasi_berurutan`,
`pinjaman_no_downgrade`, `approval_urutan`, dan `approval_advance` dimatikan di
dalam transaksi dan dinyalakan lagi sebelum `commit`. `validate.js` §E memeriksa
tidak ada trigger yang tertinggal mati — kalau terlewat, invarian yang jadi
alasan seluruh rancangan ini akan diam-diam tidak berlaku.

**Celah bercelah.** Array RTDB bisa berupa objek numerik bercelah akibat
penghapusan di tengah. Skrip membuang entri null (meniru `safePembayaranList`,
`PelangganViewModel.kt:316-318`), bukan mengubahnya jadi pembayaran Rp 0.

**Tanggal Bahasa Indonesia.** Peta bulan menyertakan `Mei/Agu/Okt/Des`.
`Date.parse("12 Agu 2025")` menghasilkan Invalid Date — empat bulan itu akan
hilang diam-diam kalau memakai parser bawaan.

**R-05 (`clientOpId` kosong) — sekarang tertutup.** Baris lama memakai kunci
turunan `derive:{pid}/{ke}/{tanggal}/{jumlah}`. Bila dua setoran sah bertabrakan
(nominal & tanggal sama), skrip menambahkan penghitung urut (`#2`, `#3`) alih-alih
menjatuhkan salah satunya. Urutan iterasi mengikuti indeks array menaik sehingga
deterministik untuk export yang sama. **Batasnya jujur:** kalau Anda mengekspor
ulang setelah ada penghapusan pembayaran di tengah, indeks bergeser dan id bisa
berbeda — jadi impor final harus memakai **satu berkas export yang sama**.

---

## 5. Yang Diperiksa `validate.js`

| Grup | Isi |
|---|---|
| A | Jumlah baris per tabel vs hitungan ulang dari JSON |
| B | **Jumlah uang**: total pembayaran, jurnal, kasir harus sama persis |
| C | Referensi yatim (pinjaman→nasabah, pembayaran→pinjaman, nasabah→cabang/user) |
| D | Invarian: satu pinjaman hidup per nasabah, NIK unik, `client_op_id` unik, tidak ada pembayaran ≤ 0 |
| E | Semua trigger kembali aktif |
| F | RLS `enabled` **dan** `forced` di semua tabel |
| G | Sebaran status, dicetak berdampingan untuk perbandingan mata |

Grup D juga melaporkan bila **NIK duplikat sudah ada di sumber** — dalam kasus
itu impor pasti kehilangan sebagian baris karena unique index, dan itu keputusan
data, bukan bug skrip.

Keluar dengan kode 1 bila ada satu pemeriksaan gagal, sehingga bisa dipakai
sebagai gerbang otomatis.

---

## 6. Yang TIDAK Dimigrasikan

**Node turunan** (dihitung ulang di Postgres, lihat `004` §6): `summary`,
`pembayaran_harian`, `event_harian`, `nasabah_index`, `nik_registry`,
`pelanggan_bermasalah`, `kasir_summary`.

**Node di luar lingkup** — tidak ada tabel tujuannya di `001`:
`absensi`, `user_absensi_today`, `rekap_harian`, `rekap_harian_final`,
`operasional_harian`, `biaya_awal`, `pinjamanHistory`, `koreksi_storting`,
`deleted_sampah`, `pelanggan_ditolak`, `pelanggan_status_khusus`,
`deletion_requests`, `payment_deletion_requests`,
`pengajuan_pencairan_simpanan`, seluruh node notifikasi, `fcm_tokens`,
`device_presence`, `force_logout`, `location_tracking`, `user_locations`,
`password_reset_logs`, `user_management_logs`, `jurnal_transaksi_meta`.

Ini **bukan** daftar yang bisa diabaikan. Tiga di antaranya berdampak bisnis
langsung dan perlu keputusan Anda sebelum cutover:

- **`pinjamanHistory`** — `{berlakuSampai, besarPinjaman}` per pelanggan.
  Kalau Buku Pokok web memakainya untuk baris historis, laporan akan berubah
  setelah cutover.
- **`biaya_awal`** — rekap biaya administrasi harian per admin. Berpengaruh ke
  pembukuan kasir.
- **`pelanggan_ditolak`** — arsip pengajuan yang ditolak. Hilang berarti
  kehilangan jejak audit penolakan.

Saya tidak menambahkannya diam-diam ke skrip: memodelkannya butuh tabel baru di
`001`, dan itu keputusan Anda, bukan asumsi saya.

---

## 7. Batas Jujur

- Skrip **belum pernah dieksekusi**. Tidak ada Node runtime yang menjalankannya
  terhadap data nyata, dan tidak ada Postgres untuk memvalidasi SQL-nya di sini.
  Kesalahan sintaks/runtime masih mungkin ada.
- Angka baris, waktu jalan, dan kebutuhan memori **belum terukur** — saya tidak
  punya satu pun angka dari export 89 MB Anda.
- `002_rls_policies.sql` masih memuat **R-07** (eskalasi privilese lewat
  `app_user_ubah_diri`) yang Anda tunda. Selama itu belum ditambal, jangan
  buka akses aplikasi ke publik.
- Foto/Storage belum disentuh sama sekali. `003_storage_design.md` masih
  rancangan; tidak ada skrip pemindah objek.
- Password tidak ikut bermigrasi (§3).
