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

Sudah berdiri sendiri sebagai **`001a_schema_patch.sql`** — jalankan langsung
di SQL Editor, setelah `001` dan sebelum `migrate.js --execute`.

Berkas itu juga memuat penambahan nilai enum `pelunasan_tabungan` (temuan dry
run: 436 entri jurnal memakainya). Blok di bawah adalah salinan isinya untuk
pembacaan; **yang dijalankan adalah berkasnya**, karena `alter type … add
value` di sana sengaja ditaruh DI LUAR transaksi.

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

## 3. Runbook Dry Run (salin–tempel)

Untuk **uji coba dengan Supabase kosong dan aplikasi masih Firebase**.
Aman: tidak ada satu pun langkah di bawah yang menyentuh Firebase, dan
aplikasi produksi tidak berubah perilakunya.

### 3.0 Sekali di awal — kredensial

Ambil tiga nilai dari Supabase Dashboard:

| Nilai | Letaknya | Dipakai oleh |
|---|---|---|
| `SUPABASE_DSN` | Settings → Database → Connection string → **URI** | migrate.js, validate.js, create_auth_users.js |
| `SUPABASE_URL` | Settings → API → Project URL | create_auth_users.js |
| `SUPABASE_SERVICE_ROLE_KEY` | Settings → API → `service_role` **secret** | create_auth_users.js |

Pakai **Session mode / port 5432**, bukan pooler 6543 — pooler transaksi
tidak mendukung `alter table … disable trigger` di dalam satu transaksi
panjang, dan seluruh impor berjalan dalam satu transaksi.

```bash
# --- Linux / macOS -------------------------------------------------------
export SUPABASE_DSN="postgresql://postgres:PASSWORD@db.xxxxx.supabase.co:5432/postgres"
export SUPABASE_URL="https://xxxxx.supabase.co"
export SUPABASE_SERVICE_ROLE_KEY="eyJhbGciOi...."      # service_role, BUKAN anon
export EXPORT_JSON="$HOME/export/koperasi-rtdb.json"
```

```powershell
# --- Windows PowerShell --------------------------------------------------
$env:SUPABASE_DSN="postgresql://postgres:PASSWORD@db.xxxxx.supabase.co:5432/postgres"
$env:SUPABASE_URL="https://xxxxx.supabase.co"
$env:SUPABASE_SERVICE_ROLE_KEY="eyJhbGciOi...."
$env:EXPORT_JSON="C:\Project\export\koperasi-rtdb.json"
```

`service_role` mem-bypass RLS sepenuhnya. Jangan menaruhnya di berkas yang
ikut ter-commit, dan jangan memakainya dari aplikasi.

### 3.1 Pasang dependensi (sekali)

```bash
cd scripts/migration
npm init -y                      # kalau belum ada package.json di folder ini
npm i pg @supabase/supabase-js
```

### 3.2 DRY-RUN — tidak menyentuh database sama sekali

```bash
node --max-old-space-size=8192 migrate.js --file="$EXPORT_JSON"
```

**Dry-run adalah perilaku DEFAULT** — cukup tidak menulis `--execute`.
Skrip tidak mengenal flag `--dry-run`; menuliskannya tidak menimbulkan galat
tetapi juga tidak memberi perlindungan apa pun. Yang menentukan hanyalah ada
atau tidaknya `--execute`. Skrip mencetak `✓ DRY-RUN selesai` di akhir bila
tidak ada yang ditulis.

Keluarannya: ringkasan jumlah baris + `migration_report.json`.
**Baca laporan itu dan selesaikan semua anomali sebelum lanjut.** Anomali
yang paling perlu diputuskan: `STATUS_TIDAK_DIKENAL`, `NASABAH_TANPA_CABANG`,
`BAYAR_DILEWATI`, dan `TANGGAL_TIDAK_TERBACA`.

### 3.3 Pasang skema (Supabase SQL Editor, berurutan)

```
001_schema_v2.sql
001a_schema_patch.sql        (blok SQL di §1.3 dokumen ini)
002_rls_policies.sql         ← masih memuat lubang R-07 yang Anda tunda
007_rpc_functions.sql
009_password_reset_log.sql
```

### 3.4 Impor

```bash
node --max-old-space-size=8192 migrate.js \
     --file="$EXPORT_JSON" --dsn="$SUPABASE_DSN" --execute
```

**Mengulang impor setelah memperbaiki skrip? KOSONGKAN DULU.**
Seluruh penulisan memakai `on conflict (id) do nothing`. Itu membuat impor
idempoten, tetapi juga berarti baris yang **sudah ada tidak akan pernah
diperbarui** — perbaikan apa pun di `migrate.js` tidak berpengaruh pada
baris yang telanjur masuk, sementara skripnya tetap melaporkan sukses.

Sejak versi ini `migrate.js` **menolak jalan** bila tabel tujuan sudah
berisi (exit 7) dan mencetak perintah `truncate`-nya. Paksa dengan
`--izinkan-tabel-terisi` hanya bila memang sengaja menambahkan.

Seluruhnya satu transaksi: gagal di mana pun → `rollback` otomatis, database
kembali kosong. Aman diulang — semua id deterministik dan memakai
`on conflict do nothing`.

### 3.5 Validasi — gerbang, bukan formalitas

```bash
node --max-old-space-size=8192 validate.js \
     --file="$EXPORT_JSON" --dsn="$SUPABASE_DSN"
echo "exit code: $?"        # 0 = lulus, 1 = ADA yang gagal
```

Exit code 1 berarti **jangan lanjut**. Yang paling penting diperhatikan:
grup **B (jumlah uang)** — total pembayaran/jurnal/kasir harus sama persis
dengan sumber; dan grup **E**, memastikan tidak ada trigger yang tertinggal
mati setelah impor.

### 3.6 Buat akun Auth

```bash
# Lihat dulu apa yang akan dibuat — tidak membuat akun apa pun.
# (sama seperti migrate.js: cukup TANPA --execute)
node create_auth_users.js

# Buat akun. Pengawas DENGAN password, peran lain tanpa password.
PENGAWAS_PASSWORD='<password-pengawas-anda>' \
  node create_auth_users.js --execute --emit-reset-links=./reset_links.csv
```

Lalu pasang kembali FK yang dilepas patch `001a`:

```sql
alter table koperasi.app_user
  add constraint app_user_id_fkey foreign key (id) references auth.users(id);

-- harus 0:
select count(*) from koperasi.app_user u
 left join auth.users a on a.id = u.id where a.id is null;
```

`reset_links.csv` **setara password**. Bagikan per orang, lalu hapus
berkasnya. Jangan di-commit.

### 3.7 Kalau ingin mengulang dari nol

Selama Firebase masih memegang kebenaran, mengosongkan Supabase aman:

```sql
drop schema if exists koperasi cascade;
drop schema if exists koperasi_priv cascade;
-- lalu ulangi §3.3
```

Akun Auth yang sudah dibuat **tidak** ikut terhapus; itu disengaja
(`rollback_plan.md` §5). `create_auth_users.js` akan melaporkannya sebagai
"sudah ada" pada percobaan berikutnya.

---

## 3A. Memindahkan Sakelar Backend di Satu Perangkat Test

`SyncBackend` default `FIREBASE`. Sakelarnya dipindah lewat **broadcast ADB**,
bukan menu tersembunyi di dalam aplikasi.

Alasannya: aplikasi ini dipakai admin lapangan setiap hari. Gerakan rahasia
di layar bisa teraktivasi tanpa sengaja, dan akibatnya bukan tampilan aneh —
seluruh tulisan admin itu akan menuju Supabase yang masih kosong sementara ia
mengira datanya tersimpan. Sakelar yang menuntut akses fisik/USB menutup
kemungkinan itu sepenuhnya.

Receiver-nya didaftarkan **hanya** di `app/src/debug/AndroidManifest.xml`,
jadi pada APK release komponennya tidak ada sama sekali.

```bash
# Pasang APK debug ke perangkat test
./gradlew :app:installDebug

# 1. Lihat keadaan sekarang (backend aktif, konfigurasi, sisa antrean)
adb shell am broadcast \
  -a com.example.koperasikitagodangulu.DEV_SYNC_BACKEND \
  --es cmd status \
  -n com.example.koperasikitagodangulu/.offline.SyncBackendDevReceiver

adb logcat -d -s DevSyncBackend

# 2. Pindah ke Supabase
adb shell am broadcast \
  -a com.example.koperasikitagodangulu.DEV_SYNC_BACKEND \
  --es cmd set --es backend SUPABASE \
  -n com.example.koperasikitagodangulu/.offline.SyncBackendDevReceiver

# 3. Kembali ke Firebase (rollback, tanpa rilis APK)
adb shell am broadcast \
  -a com.example.koperasikitagodangulu.DEV_SYNC_BACKEND \
  --es cmd set --es backend FIREBASE \
  -n com.example.koperasikitagodangulu/.offline.SyncBackendDevReceiver
```

Prasyarat yang ditegakkan sendiri oleh receiver:

- **Antrean harus 0.** Kalau masih ada operasi tertunda, perpindahan
  dibatalkan dan dilaporkan di logcat. Operasi yang dibuat saat Firebase lalu
  diputar ke Supabase memang idempoten di kedua sisi, tetapi khusus
  `SERAH_TERIMA` belum didukung jalur Supabase dan akan langsung `REJECTED`.
  Paksa dengan `--ez force true` hanya bila memang disengaja.
- **`SUPABASE_URL`/`ANON_KEY` harus terisi** di `~/.gradle/gradle.properties`
  saat build. Kalau kosong, `SyncBackend` otomatis jatuh kembali ke Firebase
  dan mencatatnya di log — bukan gagal senyap.

Untuk dry run yang Anda rencanakan sekarang (Supabase kosong, aplikasi masih
Firebase), **§3A tidak perlu dijalankan sama sekali**. Cukup §3.0–§3.6.

### 3a. ⚠ Alur reset password TIDAK berfungsi setelah cutover

Rencana Anda — *Pengawas login dulu, lalu reset password user lain via
aplikasi* — **tidak akan jalan apa adanya**, dan ini perlu diputuskan sebelum
hari-H.

Buktinya di `functions/resetUserPassword.js`:

```js
:67   const userRecord = await admin.auth().getUserByEmail(...)
:94   await admin.auth().updateUser(targetUid, { password: ... })
:99   await admin.auth().revokeRefreshTokens(targetUid)
:37   const pengawasSnap = await db.ref(`metadata/roles/pengawas/${callerUid}`)…
```

`admin.auth()` adalah **Firebase Auth**, dan cek wewenangnya membaca
**RTDB**. Setelah cutover, akun hidup di Supabase Auth — fungsi ini akan
mengubah password di Firebase yang sudah tidak dipakai, lalu tampak
"berhasil" di layar. Hasilnya: **hanya Pengawas yang bisa login, dan tidak ada
cara memasukkan user lain.**

Tiga pilihan, tanpa menyentuh kode Android:

| Opsi | Cara | Catatan |
|---|---|---|
| **A. Tautan pemulihan** (paling cepat) | `create_auth_users.js --emit-reset-links=./reset.csv` | Menghasilkan CSV berisi tautan reset per user. Bagikan **per orang**. Berkasnya setara password — jangan di-commit, hapus setelah dipakai. |
| **B. Edge Function pengganti** | Tulis Edge Function Supabase yang meniru `resetUserPassword`, dipanggil di endpoint yang sama | Perlu perubahan endpoint di klien → **melanggar batasan "jangan ubah kode Android"** pada fase ini |
| **C. Set password sementara untuk semua** | Beri password awal ke semua user saat pembuatan akun | Menyimpang dari keputusan Anda; dan password seragam untuk 1 organisasi adalah risiko nyata |

**Keputusan pemilik (12 Agu 2026): opsi A untuk hari-H, opsi B sebagai solusi
permanen — dikerjakan di fase berikutnya, bukan sekarang.** Rencananya di §8.

### Membuat akun Supabase Auth (langkah manual, setelah impor)

Dikerjakan `scripts/migration/create_auth_users.js`. Skrip itu **membaca
`koperasi.app_user` dari Postgres**, bukan menghitung ulang id dari UID
Firebase — jadi tidak ada dua implementasi turunan id yang bisa melenceng.

Kebijakan sesuai keputusan Anda:

| Peran | Password saat migrasi |
|---|---|
| Pengawas | **diberi**, lewat `PENGAWAS_PASSWORD` |
| PDL, Pimpinan, Koordinator, Sekretaris, Kasir | **tidak diberi** — akun dibuat tanpa password |

```bash
export SUPABASE_URL="https://xxx.supabase.co"
export SUPABASE_SERVICE_ROLE_KEY="eyJ…"        # service_role, BUKAN anon
export SUPABASE_DSN="postgresql://…:5432/postgres"
export PENGAWAS_PASSWORD='…'                   # password yang Anda tentukan

node create_auth_users.js                 # dry-run = default (tanpa --execute)
node create_auth_users.js --execute --emit-reset-links=./reset_links.csv
```

Lalu pasang kembali FK dan verifikasi:

```sql
alter table koperasi.app_user
  add constraint app_user_id_fkey foreign key (id) references auth.users(id);

select count(*) from koperasi.app_user u
 left join auth.users a on a.id = u.id where a.id is null;   -- harus 0
```

**Password tidak disimpan di repo — disengaja.** Skrip menolak jalan tanpa
`PENGAWAS_PASSWORD` dan tidak memuat nilai default apa pun. Repo ini ada di
GitHub; kredensial produksi yang ter-commit akan tetap ada di riwayat git
selamanya walau berkasnya dihapus. Preseden yang sama sudah tercatat pada
`SWEEP_SECRET` di `sweepRiwayatOrphan.js` (lihat checklist baseline §2A).

Ganti password Pengawas setelah login pertama — nilai yang dipakai saat
migrasi sudah melewati shell history dan berkas environment.

**Password lama tidak ikut bermigrasi**: hash Firebase tidak dapat diimpor ke
Supabase Auth. Umumkan ini sebelum hari-H.

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

**Sudah ikut dimigrasikan** (keputusan pemilik 12 Agu 2026, tabel baru di
`001` §10b): `pinjamanHistory` → `pinjaman_history`, `biaya_awal` →
`biaya_awal`, `pelanggan_ditolak` → `pelanggan_ditolak`.

Catatan bentuk untuk ketiganya:

- `pinjaman_history` — `{berlakuSampai, besarPinjaman}` per push-id. Entri yang
  nasabah induknya sudah tidak ada di `/pelanggan` **dilewati** dan dilaporkan
  sebagai `PINJAMAN_HISTORY_YATIM`; dipaksa masuk berarti FK gagal dan seluruh
  transaksi impor batal.
- `biaya_awal` — PK `(admin_id, tanggal)`; key node RTDB-nya memang tanggal,
  jadi tidak mungkin ada dua entri sehari.
- `pelanggan_ditolak` — snapshot `pelanggan` (±70 field) disimpan **utuh
  sebagai `jsonb`**, bukan dipecah ke kolom. Nasabahnya sering tidak pernah ada
  di tabel `nasabah` (ditolak sebelum jadi nasabah), dan sebagai bukti audit
  bentuknya harus tetap seperti saat ditolak. Kolom yang dicari (`nik`,
  `nama_ktp`, `cabang_id`, `besar_pinjaman`) diekstrak agar bisa di-index.

Ditambahkan pada putaran kedua (`001` §10b.4–10b.5):

- **`koreksi_storting`** → `koreksi_storting`, PK `(cabang_id, admin_id,
  periode)`. Kolom `cm/l1/mb/ml` adalah penyesuaian manual kolom storting Buku
  Pokok per bulan. Angka ini **mengubah hasil pembukuan** — tanpanya laporan
  bulanan pasca-cutover akan berbeda dari laporan yang sudah dicetak dan
  ditandatangani. `validate.js` membandingkan totalnya persis.
- **`pelanggan_status_khusus`** → `pelanggan_status_khusus`. Sebagian isinya
  tumpang tindih dengan `nasabah.status_khusus`, tetapi node ini menyimpan
  **snapshot saat penandaan** (nama, no HP, besar pinjaman waktu itu) yang
  tidak bisa dipulihkan dari `nasabah` setelah datanya berubah — jadi dipindah
  utuh, bukan dianggap duplikat. `nasabah_id` **nullable**: yang ditandai bisa
  sudah dihapus dari `/pelanggan`, dan barisnya tetap disimpan karena membawa
  datanya sendiri.

Catatan bentuk yang perlu diketahui: `diberiTandaOleh` **tidak konsisten** di
data nyata — kadang nama tampilan (`"Resort Idaman Panti"`), kadang email
(`"permula@godangulu.com"`), **bukan UID**. Kolomnya karena itu bertipe `text`
tanpa FK; memaksakan FK ke `app_user` akan menggagalkan impor.

**Masih di luar lingkup** — tidak ada tabel tujuannya:
`absensi`, `user_absensi_today`, `rekap_harian`, `rekap_harian_final`,
`operasional_harian`, `deleted_sampah`, `deletion_requests`,
`payment_deletion_requests`, `pengajuan_pencairan_simpanan`, seluruh node
notifikasi, `fcm_tokens`, `device_presence`, `force_logout`,
`location_tracking`, `user_locations`, `password_reset_logs`,
`user_management_logs`, `jurnal_transaksi_meta`.

`password_reset_logs` akan diperlukan begitu Edge Function reset password
dibangun (§8.3) — belum ada tabelnya.

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

---

## 8. Roadmap Fase Berikutnya — Edge Function Pengganti User Management

**Belum dikerjakan. Butuh perubahan kode Android, jadi di luar lingkup fase
ini** sesuai batasan Anda. Bagian ini merekam rencananya supaya tidak hilang.

### 8.1 Kebutuhan

> "Kalau PDL A resign, Pengawas harus bisa reset password PDL A supaya
> karyawan baru bisa login."

Solusi tautan pemulihan (§3a opsi A) hanya menyelesaikan hari-H — ia berbasis
email, sedangkan kebutuhan ini adalah Pengawas menetapkan password baru
langsung dari aplikasi, tanpa akses ke email PDL yang sudah resign.

### 8.2 Lingkupnya lebih luas dari satu fungsi

Ini yang perlu diketahui sebelum memperkirakan usaha. `functions/resetUserPassword.js`
mengekspor **lima** callable, dan Android memanggil semuanya lewat
`functions.getHttpsCallable(...)` (`PelangganViewModel.kt:16340`):

| Callable | Baris | Fungsi |
|---|---|---|
| `resetUserPassword` | `:22` | set password user lain |
| `getAllUsers` | `:174` | daftar user untuk layar User Management |
| `createNewUser` | `:343` | buat user baru |
| `deleteExistingUser` | `:520` | hapus user |
| `getAllCabang` | `:668` | daftar cabang |

Semuanya bergantung pada dua hal yang hilang setelah cutover: `admin.auth()`
(Firebase Auth) dan pengecekan wewenang lewat `metadata/roles/pengawas` di
RTDB (`:37`, `:188`). **Mengganti `resetUserPassword` saja tidak cukup** —
layar `PengawasUserManagementScreen.kt` akan tetap rusak karena `getAllUsers`
tidak mengembalikan apa pun.

Android juga memanggil `generateTakeoverToken`, `restorePimpinanSession`,
`triggerTargetRecalc`, dan `updateAllSummaries`. Empat itu di luar user
management, tetapi mengalami masalah yang sama dan perlu didata terpisah.

### 8.3 Rancangan pengganti

Lima Edge Function Supabase (Deno), satu per callable, memakai
`SUPABASE_SERVICE_ROLE_KEY` dari environment — **tidak pernah** dikirim ke
klien.

Pola wewenang, menggantikan cek RTDB:

```ts
// Verifikasi pemanggil dari JWT, lalu cek perannya di koperasi.app_user.
const { data: { user } } = await admin.auth.getUser(jwtDariHeader);
const { data: profil } = await admin
  .from('app_user').select('role').eq('id', user.id).single();
if (profil?.role !== 'pengawas') return json(403, { error: 'Bukan pengawas' });
```

Isi `resetUserPassword` menjadi:

```ts
await admin.auth.admin.updateUserById(targetId, { password: baru });
// revokeRefreshTokens Firebase (:99) → Supabase memutus sesi lewat:
await admin.auth.admin.signOut(targetId, 'global');
await admin.from('password_reset_log').insert({ ... });   // audit trail
```

Yang harus dipertahankan 1:1 dari perilaku sekarang:

- **Larangan pengawas mereset pengawas lain** (`:73` memeriksa target juga
  pengawas). Tanpa ini, satu pengawas bisa mengunci pengawas lain.
- **Pemutusan sesi** setelah reset (`:99`) — kalau tidak, pemilik lama tetap
  bisa memakai token yang masih hidup.
- **Audit log** (`:104`, `:117`, `:146`) — di RTDB ke `password_reset_logs`;
  perlu tabel padanannya. Belum ada di `001`.

### 8.4 Perubahan sisi Android (fase berikutnya)

Minimal, dan terpusat di satu tempat: ganti `functions.getHttpsCallable("…")`
(`PelangganViewModel.kt:16340` dan sekitarnya) menjadi pemanggilan HTTPS ke
URL Edge Function. Bentuk request/response dibuat **identik** dengan callable
Firebase (`{ data: { … } }`) supaya parsing di layar tidak ikut berubah.

### 8.5 Urutan yang disarankan

1. Tambahkan tabel `password_reset_log` ke skema (belum ada).
2. Tulis kelima Edge Function; uji dengan `curl` memakai JWT pengawas asli.
3. Ubah lapisan pemanggil di Android di **satu** tempat; rilis versi baru.
4. Baru setelah versi itu terpasang merata, matikan callable Firebase-nya.

Langkah 4 harus terakhir: perangkat yang belum di-update masih memanggil
endpoint lama, dan mematikannya lebih awal membuat User Management mati di
perangkat tersebut tanpa pesan yang jelas.

### 8.6 Selama Edge Function belum ada

Pengawas tetap bisa mereset password lewat **Supabase Dashboard →
Authentication → Users → Reset password**, atau menjalankan ulang
`create_auth_users.js --emit-reset-links`. Kikuk, tetapi tidak ada yang
terkunci — dan itu cukup sebagai jaring pengaman sampai fase berikutnya
selesai.
