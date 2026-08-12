-- =========================================================================
-- KOPERASI KITA (GODANG ULU) — SUPABASE / POSTGRESQL SCHEMA v2
-- Fase 1: Schema Design. RANCANGAN — BELUM DI-DEPLOY, BELUM DIJALANKAN.
-- =========================================================================
--
-- Setiap keputusan di bawah menunjuk ke bukti di repo (file:line).
--
-- PERUBAHAN STRUKTURAL TERPENTING vs RTDB
-- ---------------------------------------
-- Di RTDB, satu nasabah = SATU node `pelanggan/{adminUid}/{pelangganId}` yang
-- DITULIS ULANG setiap generasi pinjaman (top-up). Generasi lama diarsipkan ke
-- `riwayat_pinjaman/{adminUid}/{pelangganId}/{pinjamanKe}`
-- (OfflineRepository.kt:227). Karena identitas node tidak berubah antar
-- generasi, operasi offline yang tertunda dari generasi N bisa mendarat di
-- generasi N+1 — inilah akar `_guardPinjamanKe` (SyncManager.kt:114) dan
-- `statusLunasUntukPinjamanKe` (PelangganViewModel.kt:193).
--
-- Di sini: SATU BARIS PER GENERASI PINJAMAN (tabel `pinjaman`). Operasi
-- menargetkan `pinjaman_id` (surrogate key) yang unik per generasi, sehingga
-- replay lintas generasi menjadi MUSTAHIL SECARA STRUKTURAL — bukan dicegah
-- oleh guard yang harus diingat developer. Lihat §3.
--
-- KONVENSI
-- --------
-- * Uang: `bigint` rupiah bulat. RTDB memakai Int (PelangganViewModel.kt:194
--   `besarPinjaman: Int`); `Int` Kotlin = 32-bit dan akan overflow di
--   Rp 2.147.483.647. `bigint` menghapus plafon itu. TIDAK PERNAH numeric/float.
-- * Tanggal bisnis: `date`. Timestamp peristiwa: `timestamptz`.
--   Timezone acuan Asia/Jakarta (CLAUDE.md §9.1).
-- * Nama domain tetap Bahasa Indonesia (CLAUDE.md §9.1: jangan Anglicize).
-- * Semua tabel di schema `koperasi`, BUKAN `public`, agar PostgREST hanya
--   mengekspos yang sengaja di-expose.
-- =========================================================================

create schema if not exists koperasi;
create schema if not exists koperasi_priv;   -- helper RLS, TIDAK di-expose PostgREST

comment on schema koperasi_priv is
  'Helper SECURITY DEFINER untuk RLS. Jangan tambahkan ke PostgREST db-schemas.';

create extension if not exists "pgcrypto";   -- gen_random_uuid()
create extension if not exists "pg_trgm";    -- index pencarian nama di §2


-- =========================================================================
-- 1. DOMAIN & ENUM
-- =========================================================================
-- Nilai enum diambil VERBATIM dari string literal di kode Android supaya
-- migrasi data tidak perlu translasi. Bukti hitungan kemunculan:
--   "Aktif" 75x, "Menunggu Approval" 60x, "Lunas" 49x, "Disetujui" 42x,
--   "Ditolak" 17x, "Tidak Aktif" 13x  (grep app/src/main/kotlin/)

-- Status pinjaman. Domain ini PERSIS mencerminkan SyncManager.statusRank()
-- (SyncManager.kt, fungsi statusRank): 'Menunggu Approval'=0, 'Disetujui'=1,
-- 'Aktif'=2, sisanya terminal=3.
create type koperasi.pinjaman_status as enum (
  'Menunggu Approval',
  'Disetujui',
  'Aktif',
  'Lunas',
  'Ditolak',
  'Tidak Aktif'
);

-- CATATAN: 'Menunggu Pencairan' SENGAJA TIDAK ada di sini.
-- Verifikasi: `grep 'status = "Menunggu Pencairan"'` → 0 hasil. String itu
-- hanya dipakai sebagai nilai `statusPencairanSimpanan`
-- (KelolaKreditScreen.kt:1382) dan sebagai LABEL filter UI
-- (PimpinanDaftarSemuaNasabahScreen.kt:75). CLAUDE.md §5.2 menyebutnya sebagai
-- nilai `status` — itu keliru. Lihat 005_assumptions_and_risks.md (R-02).

create type koperasi.status_pencairan_simpanan as enum (
  'Menunggu Pencairan',
  'Dicairkan'
);

create type koperasi.status_serah_terima as enum ('Pending', 'Selesai');

-- ApprovalPhase — DualApprovalModels.kt:68-75 (nilai string verbatim)
create type koperasi.approval_phase as enum (
  'awaiting_pimpinan',
  'awaiting_koordinator',
  'awaiting_pengawas',
  'awaiting_koordinator_final',
  'awaiting_pimpinan_final',
  'completed'
);

-- ApprovalStatus — DualApprovalModels.kt:39-43
create type koperasi.approval_status as enum ('pending', 'approved', 'rejected');

-- Role — bukti: metadata/admins/{uid}/role dipakai di rulesfirebase.txt:16
-- ('admin'), rulesfirebase.txt:423 ('kasir_unit'); literal di Android:
-- "pimpinan" 31x, "admin" 19x, "koordinator" 18x, "pengawas" 9x, "kasir_unit" 1x.
-- 'sekretaris' disebut di CLAUDE.md §8.1 tetapi TIDAK ADA satu pun literal di
-- kode maupun rules → dimasukkan sebagai placeholder read-only. Lihat R-03.
create type koperasi.user_role as enum (
  'admin',
  'pimpinan',
  'koordinator',
  'pengawas',
  'kasir_unit',
  'sekretaris'
);

-- Tipe entri jurnal — jurnalTransaksi.js:14-18 (komentar TIPE TRANSAKSI)
create type koperasi.jurnal_tipe as enum (
  'pembayaran_cicilan',
  'tambah_bayar',
  'pencairan_pinjaman',
  'pelunasan_sisa_utang',
  'lunas'
);

-- Jenis pembayaran. RTDB memisahkan `pembayaranList[]` dan `subPembayaran[]`
-- (PelangganViewModel.kt:205, :107). Di sini disatukan jadi satu ledger dengan
-- diskriminator — lihat §4.
create type koperasi.pembayaran_jenis as enum ('cicilan', 'tambah_bayar');


-- =========================================================================
-- 2. MASTER DATA & IDENTITAS
-- =========================================================================

-- Cermin `metadata/cabang/{cabangId}` (CLAUDE.md §5.2).
create table koperasi.cabang (
  id            text primary key,               -- cabangId RTDB dipertahankan
  nama          text not null,
  pimpinan_id   uuid,                           -- FK ditambahkan setelah app_user
  wilayah       text,
  aktif         boolean not null default true,
  created_at    timestamptz not null default now()
);

-- Cermin `metadata/admins/{uid}` + `metadata/roles/{role}/{uid}`.
-- PK = auth.users.id supaya `auth.uid()` langsung bisa dipakai di RLS tanpa
-- lookup tambahan.
create table koperasi.app_user (
  id            uuid primary key references auth.users(id) on delete restrict,
  email         text not null unique,
  nama          text not null default '',
  role          koperasi.user_role not null,
  cabang_id     text references koperasi.cabang(id),
  foto_url      text,
  aktif         boolean not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),

  -- Admin lapangan & kasir_unit WAJIB punya cabang; pengawas global boleh null.
  -- Bukti ketergantungan cabang: rulesfirebase.txt:423 mensyaratkan
  -- metadata/admins/{uid}/cabang === $cabangId untuk kasir_unit.
  constraint app_user_cabang_wajib check (
    role in ('pengawas', 'koordinator') or cabang_id is not null
  )
);

alter table koperasi.cabang
  add constraint cabang_pimpinan_fk
  foreign key (pimpinan_id) references koperasi.app_user(id);

-- Koordinator menangani BANYAK cabang (CLAUDE.md §6.3 "lintas cabang").
-- Di RTDB relasi ini tidak pernah dimodelkan eksplisit — rules hanya memberi
-- koordinator akses GLOBAL (rulesfirebase.txt:8, :12: cek
-- metadata/roles/koordinator tanpa filter cabang). Tabel ini memungkinkan
-- pengetatan. Lihat R-06.
create table koperasi.koordinator_cabang (
  koordinator_id uuid not null references koperasi.app_user(id) on delete cascade,
  cabang_id      text not null references koperasi.cabang(id) on delete cascade,
  primary key (koordinator_id, cabang_id)
);

-- =========================================================================
-- NASABAH — identitas ORANG, stabil lintas generasi pinjaman.
-- =========================================================================
-- Di RTDB tidak ada entitas ini: identitas + pinjaman + pembayaran menyatu di
-- satu node. Pemisahan ini yang membuat `riwayat_pinjaman` tidak lagi perlu
-- sebagai node terpisah.
create table koperasi.nasabah (
  id                uuid primary key default gen_random_uuid(),

  -- Kunci penelusuran balik ke RTDB selama masa migrasi paralel.
  legacy_pelanggan_id text unique,
  legacy_admin_uid    text,

  nik               text,
  nama_ktp          text not null,
  nama_panggilan    text not null default '',
  nomor_anggota     text,

  nama_ktp_suami        text not null default '',
  nama_ktp_istri        text not null default '',
  nik_suami             text,
  nik_istri             text,
  nama_panggilan_suami  text not null default '',
  nama_panggilan_istri  text not null default '',

  alamat_ktp        text not null default '',
  alamat_rumah      text not null default '',
  detail_rumah      text not null default '',
  wilayah           text not null default '',
  wilayah_normalized text not null default '',
  no_hp             text not null default '',
  jenis_usaha       text not null default '',

  cabang_id         text not null references koperasi.cabang(id),
  admin_id          uuid not null references koperasi.app_user(id),

  -- Status khusus (penanda manual dari Pengawas/Pimpinan).
  -- PelangganViewModel.kt:223-226
  status_khusus         text not null default '',
  catatan_status_khusus text not null default '',
  tanggal_status_khusus  date,
  diberi_tanda_oleh      uuid references koperasi.app_user(id),

  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),

  -- NIK 16 digit. TIDAK di-enforce NOT NULL: data lapangan existing punya NIK
  -- kosong (parser Android men-default ke ""), dan `cairkanSimpanan` sengaja
  -- MENGHAPUS NIK saat cleanse. Lihat R-04.
  constraint nasabah_nik_format check (nik is null or nik ~ '^[0-9]{16}$')
);

-- Pengganti `nik_registry/{nik}` (CLAUDE.md §5.2, node index anti-duplikat).
-- Di RTDB butuh node terpisah + 3 trigger Cloud Function untuk menjaganya
-- konsisten (onPelangganCreatedRegisterNik / onStatusChangeUpdateNik /
-- onPelangganDeletedRemoveNik). Di Postgres cukup SATU unique index parsial:
-- tidak bisa desinkron, tidak butuh trigger, tidak butuh maintenance job.
create unique index nasabah_nik_unik
  on koperasi.nasabah (nik)
  where nik is not null;

create index nasabah_cabang_idx   on koperasi.nasabah (cabang_id);
create index nasabah_admin_idx    on koperasi.nasabah (admin_id);
create index nasabah_nomor_idx    on koperasi.nasabah (nomor_anggota);
-- Pencarian nama oleh admin lapangan (CariPelangganScreen).
-- pg_trgm dibuat di bagian atas berkas — WAJIB sebelum index ini.
create index nasabah_nama_trgm_idx on koperasi.nasabah
  using gin (lower(nama_ktp) gin_trgm_ops);


-- =========================================================================
-- 3. PINJAMAN — SATU BARIS PER GENERASI
-- =========================================================================
-- Ini inti perbaikan struktural. `pinjaman_ke` bukan lagi field yang di-mutate
-- pada node yang sama, melainkan bagian dari IDENTITAS baris.
-- =========================================================================
create table koperasi.pinjaman (
  id                uuid primary key default gen_random_uuid(),
  nasabah_id        uuid not null references koperasi.nasabah(id) on delete restrict,
  pinjaman_ke       integer not null,

  status            koperasi.pinjaman_status not null default 'Menunggu Approval',

  -- Nominal (bigint rupiah)
  besar_pinjaman           bigint not null default 0,
  besar_pinjaman_diajukan  bigint not null default 0,
  besar_pinjaman_disetujui bigint not null default 0,
  jasa_pinjaman            integer not null default 10,   -- persen
  biaya_admin              bigint not null default 0,     -- RTDB: `admin`
  simpanan_awal            bigint not null default 0,     -- RTDB: `simpanan`
  total_diterima           bigint not null default 0,
  total_pelunasan          bigint not null default 0,
  tenor                    integer not null default 30,
  tipe_pinjaman            text not null default 'dibawah_3jt',

  -- Tanggal bisnis
  tanggal_pengajuan   date,
  tanggal_daftar      date,
  tanggal_pencairan   date,
  tanggal_pelunasan   date,
  tanggal_lunas_cicilan date,

  -- Jejak approval ringkas (denormalisasi sengaja untuk tampilan detail;
  -- sumber kebenaran tetap tabel `approval_step`).
  catatan_approval    text not null default '',
  tanggal_approval    date,
  disetujui_oleh      uuid references koperasi.app_user(id),
  ditolak_oleh        uuid references koperasi.app_user(id),
  alasan_penolakan    text not null default '',
  catatan_admin       text not null default '',   -- RTDB: `catatan`

  -- Serah terima (PelangganViewModel.kt:245-246)
  status_serah_terima   koperasi.status_serah_terima,
  tanggal_serah_terima  date,

  -- Pencairan simpanan (PelangganViewModel.kt:254-259)
  tarik_tabungan             bigint not null default 0,
  status_pencairan_simpanan  koperasi.status_pencairan_simpanan,
  tanggal_pencairan_simpanan date,
  dicairkan_oleh             uuid references koperasi.app_user(id),

  -- Snapshot anchor target hari-H saat top-up (PelangganViewModel.kt:253).
  besar_pinjaman_lama_sebelum_top_up bigint not null default 0,
  sisa_utang_lama_sebelum_top_up     bigint not null default 0,
  total_pelunasan_lama_sebelum_top_up bigint not null default 0,

  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),

  constraint pinjaman_ke_positif check (pinjaman_ke >= 1),
  constraint pinjaman_nominal_wajar check (
    besar_pinjaman >= 0 and total_diterima >= 0 and total_pelunasan >= 0
  ),
  constraint pinjaman_tenor_positif check (tenor > 0),

  -- ✅ ANTI-REPLAY #1: generasi unik per nasabah. Menulis ulang generasi yang
  -- sudah ada akan gagal dengan unique violation, bukan menimpa diam-diam.
  constraint pinjaman_generasi_unik unique (nasabah_id, pinjaman_ke)
);

create index pinjaman_nasabah_idx on koperasi.pinjaman (nasabah_id);
create index pinjaman_status_idx  on koperasi.pinjaman (status);
-- Menggantikan `.indexOn` di rulesfirebase.txt:15.
create index pinjaman_cair_idx    on koperasi.pinjaman (tanggal_pencairan)
  where tanggal_pencairan is not null;

-- -------------------------------------------------------------------------
-- 3.1 ANTI-REPLAY #2 — hanya SATU pinjaman hidup per nasabah
-- -------------------------------------------------------------------------
-- Di RTDB, bug top-up yang ditolak bisa meninggalkan DUA "pinjaman ke-2"
-- (satu di node pelanggan, satu hantu di riwayat_pinjaman) — persis yang
-- dibersihkan functions/sweepRiwayatOrphan.js. Constraint ini membuat kondisi
-- itu tidak representable.
create unique index pinjaman_satu_aktif_per_nasabah
  on koperasi.pinjaman (nasabah_id)
  where status in ('Menunggu Approval', 'Disetujui', 'Aktif');

-- -------------------------------------------------------------------------
-- 3.2 ANTI-DOWNGRADE — rank status tidak boleh turun
-- -------------------------------------------------------------------------
-- Cermin langsung SyncManager.statusRank(). Di Android ini cuma dipakai
-- sebagai HINT untuk memutuskan skip; di sini jadi INVARIAN yang ditegakkan
-- database, sehingga replay tertunda tidak bisa menurunkan status apa pun
-- jalur penulisannya (REST, RPC, dashboard, job).
create or replace function koperasi.status_rank(s koperasi.pinjaman_status)
returns integer
language sql immutable parallel safe
as $$
  select case s
    when 'Menunggu Approval' then 0
    when 'Disetujui'         then 1
    when 'Aktif'             then 2
    else 3                                  -- Lunas / Ditolak / Tidak Aktif
  end;
$$;

create or replace function koperasi.tg_pinjaman_no_downgrade()
returns trigger
language plpgsql
as $$
begin
  if koperasi.status_rank(new.status) < koperasi.status_rank(old.status) then
    raise exception
      'Penurunan status ditolak: % (rank %) → % (rank %) pada pinjaman %',
      old.status, koperasi.status_rank(old.status),
      new.status, koperasi.status_rank(new.status), old.id
      using errcode = 'check_violation',
            hint = 'Kemungkinan replay operasi offline yang sudah usang.';
  end if;

  -- Generasi pinjaman TIDAK BOLEH berubah setelah baris dibuat. Ini yang
  -- menghapus kebutuhan `_guardPinjamanKe`: top-up = INSERT baris baru,
  -- bukan increment kolom.
  if new.pinjaman_ke <> old.pinjaman_ke then
    raise exception 'pinjaman_ke immutable (% → %) pada pinjaman %',
      old.pinjaman_ke, new.pinjaman_ke, old.id
      using errcode = 'check_violation';
  end if;

  if new.nasabah_id <> old.nasabah_id then
    raise exception 'nasabah_id immutable pada pinjaman %', old.id
      using errcode = 'check_violation';
  end if;

  new.updated_at := now();
  return new;
end;
$$;

create trigger pinjaman_no_downgrade
  before update on koperasi.pinjaman
  for each row execute function koperasi.tg_pinjaman_no_downgrade();

-- -------------------------------------------------------------------------
-- 3.3 ANTI-REPLAY #3 — top-up hanya boleh lahir dari generasi yang tuntas
-- -------------------------------------------------------------------------
create or replace function koperasi.tg_pinjaman_generasi_berurutan()
returns trigger
language plpgsql
as $$
declare
  v_prev_status koperasi.pinjaman_status;
  v_max_ke      integer;
begin
  if new.pinjaman_ke = 1 then
    return new;
  end if;

  select max(pinjaman_ke) into v_max_ke
    from koperasi.pinjaman where nasabah_id = new.nasabah_id;

  -- Tidak boleh melompat generasi (mis. langsung ke-3 padahal baru ada ke-1).
  if v_max_ke is null or new.pinjaman_ke <> v_max_ke + 1 then
    raise exception
      'Generasi pinjaman tidak berurutan untuk nasabah %: minta ke-%, generasi tertinggi %',
      new.nasabah_id, new.pinjaman_ke, coalesce(v_max_ke, 0)
      using errcode = 'check_violation';
  end if;

  select status into v_prev_status
    from koperasi.pinjaman
   where nasabah_id = new.nasabah_id and pinjaman_ke = new.pinjaman_ke - 1;

  -- Generasi sebelumnya harus sudah terminal. Mencegah dua pinjaman hidup
  -- bersamaan lewat jalur INSERT (pelengkap index parsial di §3.1).
  if koperasi.status_rank(v_prev_status) < 3 then
    raise exception
      'Generasi ke-% belum tuntas (status %); top-up ke-% ditolak',
      new.pinjaman_ke - 1, v_prev_status, new.pinjaman_ke
      using errcode = 'check_violation';
  end if;

  return new;
end;
$$;

create trigger pinjaman_generasi_berurutan
  before insert on koperasi.pinjaman
  for each row execute function koperasi.tg_pinjaman_generasi_berurutan();

-- CATATAN: `statusLunasUntukPinjamanKe` (PelangganViewModel.kt:193) TIDAK
-- dibawa ke skema ini. Field itu ada semata-mata karena RTDB tidak bisa
-- membedakan "lunas generasi mana" pada node yang dipakai bersama. Dengan
-- satu baris per generasi, `pinjaman.status = 'Lunas'` SUDAH spesifik per
-- generasi. Marker jadi mubazir — dan satu kelas bug (marker terhapus oleh
-- full-node setValue) hilang bersamanya.


-- =========================================================================
-- 4. LEDGER PEMBAYARAN — APPEND-ONLY + IDEMPOTENT
-- =========================================================================
-- Di RTDB, pembayaran adalah ARRAY numerik di dalam node pelanggan
-- (PelangganViewModel.kt:205), yang wajib ditulis via runTransaction agar
-- tidak saling menimpa (CLAUDE.md §5.3). Array itu juga sumber "array gaps"
-- (PelangganViewModel.kt:316-318 `safePembayaranList`).
-- Di sini: baris terpisah. Tidak ada array, tidak ada gap, tidak ada
-- read-modify-write.
create table koperasi.pembayaran (
  id            uuid primary key default gen_random_uuid(),
  pinjaman_id   uuid not null references koperasi.pinjaman(id) on delete restrict,

  jenis         koperasi.pembayaran_jenis not null default 'cicilan',
  jumlah        bigint not null,
  tanggal       date not null,
  keterangan    text not null default '',

  -- ✅ IDEMPOTENCY. Cermin `Pembayaran.clientOpId` (PelangganViewModel.kt:112),
  -- yang di RTDB harus di-dedup manual di dalam transaction
  -- (SyncManager.kt:982 `payload["clientOpId"]`). Di sini constraint DB yang
  -- menjamin: replay operasi yang sama → unique violation, bukan dobel uang.
  client_op_id  uuid not null,

  dicatat_oleh  uuid not null references koperasi.app_user(id),
  created_at    timestamptz not null default now(),

  constraint pembayaran_jumlah_positif check (jumlah > 0),
  constraint pembayaran_client_op_unik unique (client_op_id)
);

create index pembayaran_pinjaman_idx on koperasi.pembayaran (pinjaman_id);
-- Menggantikan index turunan `pembayaran_harian/{cabangId}/{YYYY-MM-DD}`.
-- Di RTDB node itu ditulis Cloud Function dan harus dijaga sinkron; di sini
-- cukup index — tidak ada data turunan yang bisa desinkron.
create index pembayaran_tanggal_idx  on koperasi.pembayaran (tanggal);

-- Append-only: UPDATE dan DELETE ditolak di level tabel. Koreksi dilakukan
-- dengan entri pembalik (lihat `pembayaran_koreksi`), meniru prinsip
-- IMMUTABLE pada jurnalTransaksi.js:9.
create or replace function koperasi.tg_tolak_mutasi()
returns trigger
language plpgsql
as $$
begin
  raise exception 'Tabel % bersifat append-only; % ditolak',
    tg_table_name, tg_op
    using errcode = 'check_violation',
          hint = 'Gunakan entri koreksi/pembalik, jangan ubah riwayat.';
end;
$$;

create trigger pembayaran_append_only
  before update or delete on koperasi.pembayaran
  for each row execute function koperasi.tg_tolak_mutasi();

-- Pembatalan pembayaran = baris koreksi, bukan penghapusan.
-- Cermin alur `payment_deletion_requests` (CLAUDE.md §7.6): admin mengajukan,
-- Pimpinan menyetujui. Di sini approval-nya tetap di tabel `permintaan`,
-- efeknya berupa baris koreksi yang auditable.
create table koperasi.pembayaran_koreksi (
  id              uuid primary key default gen_random_uuid(),
  pembayaran_id   uuid not null unique references koperasi.pembayaran(id),
  alasan          text not null,
  disetujui_oleh  uuid not null references koperasi.app_user(id),
  created_at      timestamptz not null default now()
);

-- Saldo terbayar dihitung, tidak disimpan. Menghapus seluruh kelas bug
-- "summary desinkron" yang di RTDB butuh `summaryHelpers.js` +
-- `weeklyFullRecalc` + `summaryRepair_HEMAT.js` untuk ditambal.
create or replace view koperasi.v_pinjaman_saldo as
select
  p.id                                   as pinjaman_id,
  p.nasabah_id,
  p.pinjaman_ke,
  p.status,
  p.besar_pinjaman,
  coalesce(sum(b.jumlah) filter (where k.id is null), 0) as total_dibayar,
  p.besar_pinjaman
    - coalesce(sum(b.jumlah) filter (where k.id is null), 0) as sisa_utang
from koperasi.pinjaman p
left join koperasi.pembayaran b on b.pinjaman_id = p.id
left join koperasi.pembayaran_koreksi k on k.pembayaran_id = b.id
group by p.id;

-- Simpanan nasabah (RTDB: array `simpanan[]`, CLAUDE.md §5.2).
create table koperasi.simpanan (
  id            uuid primary key default gen_random_uuid(),
  nasabah_id    uuid not null references koperasi.nasabah(id) on delete restrict,
  pinjaman_id   uuid references koperasi.pinjaman(id),
  jenis         text not null default 'cicilan',   -- 'cicilan' | 'tambah_bayar'
  jumlah        bigint not null,
  tanggal       date not null,
  client_op_id  uuid not null unique,
  created_at    timestamptz not null default now()
);
create index simpanan_nasabah_idx on koperasi.simpanan (nasabah_id);

-- Jadwal cicilan hasil simulasi (RTDB: `hasilSimulasiCicilan[]`,
-- PelangganViewModel.kt:142-156).
create table koperasi.jadwal_cicilan (
  pinjaman_id   uuid not null references koperasi.pinjaman(id) on delete cascade,
  urutan        integer not null,
  tanggal       date not null,
  jumlah        bigint not null,
  is_hari_kerja boolean not null default true,
  is_completed  boolean not null default false,
  primary key (pinjaman_id, urutan)
);


-- =========================================================================
-- 5. APPROVAL 5 FASE — STATE MACHINE SEBAGAI BARIS
-- =========================================================================
-- Di RTDB seluruh state machine adalah SATU objek `dualApprovalInfo` yang
-- di-overwrite tiap fase (DualApprovalModels.kt:97-139). Akibatnya: riwayat
-- per-fase mudah tertimpa, dan tidak ada penegakan urutan fase.
-- Di sini: satu baris per fase, urutan ditegakkan trigger.
create table koperasi.pengajuan (
  id                uuid primary key default gen_random_uuid(),
  pinjaman_id       uuid not null unique references koperasi.pinjaman(id) on delete cascade,
  cabang_id         text not null references koperasi.cabang(id),

  requires_dual     boolean not null,
  phase             koperasi.approval_phase not null default 'awaiting_pimpinan',
  final_decision    koperasi.approval_status,
  final_decision_by uuid references koperasi.app_user(id),
  final_decision_at timestamptz,
  rejection_reason  text not null default '',

  diajukan_oleh     uuid not null references koperasi.app_user(id),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

create index pengajuan_cabang_phase_idx on koperasi.pengajuan (cabang_id, phase);

-- Threshold Rp 3.000.000 — DualApprovalModels.kt:290
-- `const val MINIMUM_AMOUNT = 3_000_000`.
-- Ditegakkan DB, bukan cuma dicek client, sehingga pengajuan ≥3jt tidak bisa
-- lolos jalur single-approval lewat jalur penulisan mana pun.
create or replace function koperasi.tg_pengajuan_threshold()
returns trigger
language plpgsql
as $$
declare
  v_besar bigint;
begin
  select greatest(besar_pinjaman, besar_pinjaman_diajukan)
    into v_besar
    from koperasi.pinjaman where id = new.pinjaman_id;

  if (v_besar >= 3000000) and not new.requires_dual then
    raise exception
      'Pinjaman Rp % wajib dual approval (ambang Rp 3.000.000)', v_besar
      using errcode = 'check_violation';
  end if;
  return new;
end;
$$;

create trigger pengajuan_threshold
  before insert or update on koperasi.pengajuan
  for each row execute function koperasi.tg_pengajuan_threshold();

-- Satu baris per fase per pengajuan. Immutable setelah ditulis.
create table koperasi.approval_step (
  id              uuid primary key default gen_random_uuid(),
  pengajuan_id    uuid not null references koperasi.pengajuan(id) on delete cascade,
  phase           koperasi.approval_phase not null,
  status          koperasi.approval_status not null,
  approver_id     uuid not null references koperasi.app_user(id),
  approver_role   koperasi.user_role not null,
  note            text not null default '',
  adjusted_amount bigint,
  adjusted_tenor  integer,
  decided_at      timestamptz not null default now(),

  -- ✅ IDEMPOTENCY + anti double-approve: satu fase hanya bisa diputuskan
  -- sekali. Tap ganda / retry jaringan → unique violation, bukan dua entri.
  constraint approval_step_sekali unique (pengajuan_id, phase)
);

-- Urutan fase ditegakkan; fase tidak bisa dilompati.
create or replace function koperasi.tg_approval_urutan()
returns trigger
language plpgsql
as $$
declare
  v_phase_now koperasi.approval_phase;
  v_role_wajib koperasi.user_role;
begin
  select phase into v_phase_now
    from koperasi.pengajuan where id = new.pengajuan_id for update;

  if new.phase <> v_phase_now then
    raise exception 'Fase tidak sesuai: pengajuan sedang di %, dikirim %',
      v_phase_now, new.phase
      using errcode = 'check_violation';
  end if;

  -- Role yang berwenang per fase — CLAUDE.md §7.1 + DualApprovalModels.kt:60-65
  v_role_wajib := case new.phase
    when 'awaiting_pimpinan'          then 'pimpinan'
    when 'awaiting_koordinator'       then 'koordinator'
    when 'awaiting_pengawas'          then 'pengawas'
    when 'awaiting_koordinator_final' then 'koordinator'
    when 'awaiting_pimpinan_final'    then 'pimpinan'
    else null
  end::koperasi.user_role;

  if v_role_wajib is null then
    raise exception 'Fase % tidak menerima keputusan', new.phase
      using errcode = 'check_violation';
  end if;

  if new.approver_role <> v_role_wajib then
    raise exception 'Fase % harus diputuskan oleh %, bukan %',
      new.phase, v_role_wajib, new.approver_role
      using errcode = 'insufficient_privilege';
  end if;

  return new;
end;
$$;

create trigger approval_urutan
  before insert on koperasi.approval_step
  for each row execute function koperasi.tg_approval_urutan();

create trigger approval_step_immutable
  before update or delete on koperasi.approval_step
  for each row execute function koperasi.tg_tolak_mutasi();

-- Transisi fase setelah keputusan. Reject di fase mana pun = terminal.
create or replace function koperasi.tg_approval_advance()
returns trigger
language plpgsql
as $$
declare
  v_next koperasi.approval_phase;
  v_dual boolean;
begin
  select requires_dual into v_dual
    from koperasi.pengajuan where id = new.pengajuan_id;

  if new.status = 'rejected' then
    update koperasi.pengajuan
       set phase = 'completed', final_decision = 'rejected',
           final_decision_by = new.approver_id, final_decision_at = now(),
           updated_at = now()
     where id = new.pengajuan_id;
    return new;
  end if;

  -- Jalur < Rp 3jt: cukup Pimpinan (CLAUDE.md §7.1).
  if not v_dual then
    update koperasi.pengajuan
       set phase = 'completed', final_decision = 'approved',
           final_decision_by = new.approver_id, final_decision_at = now(),
           updated_at = now()
     where id = new.pengajuan_id;
    return new;
  end if;

  v_next := case new.phase
    when 'awaiting_pimpinan'          then 'awaiting_koordinator'
    when 'awaiting_koordinator'       then 'awaiting_pengawas'
    when 'awaiting_pengawas'          then 'awaiting_koordinator_final'
    when 'awaiting_koordinator_final' then 'awaiting_pimpinan_final'
    when 'awaiting_pimpinan_final'    then 'completed'
  end::koperasi.approval_phase;

  update koperasi.pengajuan
     set phase = v_next,
         final_decision = case when v_next = 'completed' then 'approved'::koperasi.approval_status else final_decision end,
         final_decision_by = case when v_next = 'completed' then new.approver_id else final_decision_by end,
         final_decision_at = case when v_next = 'completed' then now() else final_decision_at end,
         updated_at = now()
   where id = new.pengajuan_id;

  return new;
end;
$$;

create trigger approval_advance
  after insert on koperasi.approval_step
  for each row execute function koperasi.tg_approval_advance();


-- =========================================================================
-- 6. PERMINTAAN DESTRUKTIF (request–approval)
-- =========================================================================
-- Menyatukan 4 node RTDB: deletion_requests, payment_deletion_requests,
-- tenor_change_requests, pencairan_simpanan_requests
-- (rulesfirebase.txt:59, :91, :115, :83). Bentuknya identik, jadi satu tabel
-- dengan diskriminator lebih murah dirawat daripada empat.
create type koperasi.permintaan_tipe as enum (
  'hapus_nasabah', 'hapus_pembayaran', 'ubah_tenor', 'pencairan_simpanan'
);

create table koperasi.permintaan (
  id            uuid primary key default gen_random_uuid(),
  tipe          koperasi.permintaan_tipe not null,
  nasabah_id    uuid references koperasi.nasabah(id),
  pinjaman_id   uuid references koperasi.pinjaman(id),
  pembayaran_id uuid references koperasi.pembayaran(id),
  payload       jsonb not null default '{}'::jsonb,
  alasan        text not null default '',

  status        koperasi.approval_status not null default 'pending',
  diminta_oleh  uuid not null references koperasi.app_user(id),
  diputus_oleh  uuid references koperasi.app_user(id),
  catatan_keputusan text not null default '',
  created_at    timestamptz not null default now(),
  decided_at    timestamptz,

  constraint permintaan_keputusan_lengkap check (
    (status = 'pending' and diputus_oleh is null and decided_at is null)
    or (status <> 'pending' and diputus_oleh is not null and decided_at is not null)
  )
);

create index permintaan_status_idx on koperasi.permintaan (status, tipe);


-- =========================================================================
-- 7. JURNAL TRANSAKSI — IMMUTABLE AUDIT TRAIL
-- =========================================================================
-- Cermin /jurnal_transaksi/{cabangId}/{tahun-bulan}/{autoId}
-- (jurnalTransaksi.js:6). Field diambil dari jurnalTransaksi.js:84-98.
create table koperasi.jurnal_transaksi (
  id                 uuid primary key default gen_random_uuid(),
  cabang_id          text not null references koperasi.cabang(id),
  tipe               koperasi.jurnal_tipe not null,

  nasabah_id         uuid references koperasi.nasabah(id),
  pinjaman_id        uuid references koperasi.pinjaman(id),
  pembayaran_id      uuid references koperasi.pembayaran(id),

  nama_pelanggan     text not null default '',
  nama_ktp           text not null default '',
  admin_id           uuid references koperasi.app_user(id),
  admin_name         text not null default '',

  jumlah             bigint not null,
  tanggal            date not null,
  pinjaman_ke        integer,
  sisa_utang_setelah bigint,
  total_pelunasan    bigint,
  total_dibayar      bigint,
  keterangan         text not null default '',

  client_op_id       uuid unique,   -- idempotency untuk replay offline
  created_at         timestamptz not null default now()
);

-- (cabang_id, tanggal) sudah melayani filter per-bulan lewat range scan;
-- sengaja TIDAK memakai index ekspresi date_trunc agar tidak bergantung pada
-- properti immutability yang belum saya verifikasi di server.
create index jurnal_cabang_tanggal_idx
  on koperasi.jurnal_transaksi (cabang_id, tanggal);
create index jurnal_nasabah_idx on koperasi.jurnal_transaksi (nasabah_id);

create trigger jurnal_immutable
  before update or delete on koperasi.jurnal_transaksi
  for each row execute function koperasi.tg_tolak_mutasi();


-- =========================================================================
-- 8. KASIR
-- =========================================================================
-- Cermin kasir_entries/{cabangId}/{YYYY-MM}/{entryId} (rulesfirebase.txt:418).
create table koperasi.kasir_entry (
  id           uuid primary key default gen_random_uuid(),
  cabang_id    text not null references koperasi.cabang(id),
  periode      date not null,          -- selalu tanggal 1 bulan ybs
  tanggal      date not null,
  jenis        text not null,          -- kasbon_pagi | bu | transport | ...
  nominal      bigint not null,
  keterangan   text not null default '',
  nota_path    text,                   -- object key di bucket nota-kasir
  dicatat_oleh uuid not null references koperasi.app_user(id),
  client_op_id uuid not null unique,
  created_at   timestamptz not null default now()
);

create index kasir_cabang_periode_idx on koperasi.kasir_entry (cabang_id, periode);


-- =========================================================================
-- 9. IDEMPOTENCY LEDGER (antrean offline Android)
-- =========================================================================
-- Room `pending_operations` (PendingOperationDatabase.kt:15) tetap ada di
-- device dan TIDAK diubah pada fase ini. Tabel ini adalah sisi SERVER-nya:
-- setiap operasi yang di-replay mencatat kunci uniknya, sehingga percobaan
-- kedua dikenali sebagai duplikat alih-alih dieksekusi ulang.
--
-- Ini menutup kelas "ghost-write" yang di RTDB tidak bisa ditutup: penulisan
-- yang await()-nya gagal tetap tersimpan di persistence SDK dan dikirim ulang
-- saat reconnect, melewati guard check-then-write apa pun.
create table koperasi.sync_inbox (
  client_op_id  uuid primary key,
  device_id     text,
  user_id       uuid not null references koperasi.app_user(id),
  operation     text not null,          -- ADD_PELANGGAN | ADD_PEMBAYARAN | ...
  request_hash  text,
  result        jsonb,
  status        text not null default 'applied',  -- applied | rejected
  error_message text,
  created_at    timestamptz not null default now()
);

create index sync_inbox_user_idx on koperasi.sync_inbox (user_id, created_at desc);


-- =========================================================================
-- 10. METADATA OBJEK STORAGE
-- =========================================================================
-- Detail bucket & policy ada di 003_storage_design.md. Tabel ini menautkan
-- object key ke entitas bisnis supaya orphan file bisa dideteksi dengan JOIN,
-- bukan dengan sweep script seperti functions/sweepRiwayatOrphan.js.
create type koperasi.dokumen_jenis as enum (
  'ktp', 'ktp_suami', 'ktp_istri', 'foto_nasabah',
  'serah_terima', 'bukti_bayar', 'nota_kasir', 'profil'
);

create table koperasi.dokumen (
  id           uuid primary key default gen_random_uuid(),
  bucket_id    text not null,
  object_path  text not null,
  jenis        koperasi.dokumen_jenis not null,

  nasabah_id   uuid references koperasi.nasabah(id) on delete cascade,
  pinjaman_id  uuid references koperasi.pinjaman(id) on delete cascade,
  pembayaran_id uuid references koperasi.pembayaran(id),
  kasir_entry_id uuid references koperasi.kasir_entry(id),
  user_id      uuid references koperasi.app_user(id),

  -- Foto pengajuan yang belum final (RTDB: pendingFoto*Url,
  -- PelangganViewModel.kt:235-238 + bucket ktp_images_pending).
  is_pending   boolean not null default false,

  uploaded_by  uuid not null references koperasi.app_user(id),
  uploaded_at  timestamptz not null default now(),
  bytes        bigint,
  content_type text,

  constraint dokumen_object_unik unique (bucket_id, object_path)
);

create index dokumen_nasabah_idx on koperasi.dokumen (nasabah_id);
create index dokumen_pinjaman_idx on koperasi.dokumen (pinjaman_id);


-- =========================================================================
-- 10b. DATA HISTORIS (keputusan pemilik 12 Agu 2026: "pindah semua")
-- =========================================================================
-- Tiga node RTDB yang semula di luar lingkup kini ikut dimigrasikan. Bentuk
-- kolom diturunkan dari record NYATA di data/firebase_sample.json, bukan dari
-- dokumentasi.
-- =========================================================================

-- -------------------------------------------------------------------------
-- 10b.1 pinjamanHistory/{adminUid}/{pelangganId}/{pushId}
--       → {berlakuSampai: "14 Mar 2026", besarPinjaman: 1000000}
-- -------------------------------------------------------------------------
-- Riwayat besaran pinjaman ber-masa-berlaku. Dipakai Buku Pokok web untuk
-- baris historis ("coretan merah" pinjaman lama). TIDAK dapat diturunkan dari
-- tabel `pinjaman`: `berlakuSampai` adalah batas waktu tampil, bukan tanggal
-- pelunasan, dan satu generasi bisa punya beberapa entri.
create table koperasi.pinjaman_history (
  id              uuid primary key default gen_random_uuid(),
  nasabah_id      uuid not null references koperasi.nasabah(id) on delete cascade,

  berlaku_sampai  date,
  besar_pinjaman  bigint not null default 0,

  legacy_push_id  text,
  legacy_admin_uid text,
  created_at      timestamptz not null default now(),

  constraint pinjaman_history_unik unique (nasabah_id, legacy_push_id)
);

create index pinjaman_history_nasabah_idx
  on koperasi.pinjaman_history (nasabah_id, berlaku_sampai desc);

-- Append-only: riwayat tampilan pembukuan tidak boleh diubah belakangan.
create trigger pinjaman_history_immutable
  before update or delete on koperasi.pinjaman_history
  for each row execute function koperasi.tg_tolak_mutasi();

-- -------------------------------------------------------------------------
-- 10b.2 biaya_awal/{adminUid}/{YYYY-MM-DD}
--       → {adminUid, jumlah, tanggal, timestamp}
-- -------------------------------------------------------------------------
-- Rekap biaya administrasi awal per admin per HARI (satu entri per tanggal —
-- key node-nya memang tanggal, jadi tidak mungkin ada dua entri sehari).
-- Berpengaruh ke pembukuan kasir, sehingga ikut dipindah.
create table koperasi.biaya_awal (
  admin_id    uuid not null references koperasi.app_user(id),
  tanggal     date not null,
  jumlah      bigint not null default 0,
  recorded_at timestamptz,

  legacy_admin_uid text,

  primary key (admin_id, tanggal)
);

create index biaya_awal_tanggal_idx on koperasi.biaya_awal (tanggal);

-- -------------------------------------------------------------------------
-- 10b.3 pelanggan_ditolak/{adminUid}/{pushId}
--       → {alasanPenolakan, ditolakOleh, tanggalPenolakan, timestamp,
--          pelanggan: { ...snapshot lengkap ±70 field... }}
-- -------------------------------------------------------------------------
-- Arsip pengajuan yang DITOLAK. Snapshot `pelanggan` disimpan sebagai `jsonb`
-- apa adanya, BUKAN dipecah ke kolom. Alasannya:
--   (a) nasabahnya sering tidak pernah ada di tabel `nasabah` — ditolak
--       sebelum sempat jadi nasabah, jadi tidak ada baris induk untuk ditaut;
--   (b) ini bukti audit: bentuknya harus persis seperti saat ditolak, tidak
--       boleh ikut berubah kalau skema `nasabah` berkembang;
--   (c) tidak ada query operasional yang menyaring isinya — hanya dibaca utuh.
-- Kolom yang sering dicari tetap diekstrak agar bisa di-index.
create table koperasi.pelanggan_ditolak (
  id                uuid primary key default gen_random_uuid(),

  alasan_penolakan  text not null default '',
  ditolak_oleh      uuid references koperasi.app_user(id),
  tanggal_penolakan date,
  rejected_at       timestamptz,

  -- Denormalisasi ringan untuk pencarian tanpa membongkar jsonb.
  nama_ktp          text not null default '',
  nama_panggilan    text not null default '',
  nik               text,
  besar_pinjaman    bigint not null default 0,
  cabang_id         text references koperasi.cabang(id),
  admin_id          uuid references koperasi.app_user(id),

  snapshot          jsonb not null default '{}'::jsonb,

  legacy_push_id    text,
  legacy_admin_uid  text,
  created_at        timestamptz not null default now(),

  constraint pelanggan_ditolak_unik unique (legacy_admin_uid, legacy_push_id)
);

create index pelanggan_ditolak_cabang_idx on koperasi.pelanggan_ditolak (cabang_id, tanggal_penolakan desc);
create index pelanggan_ditolak_nik_idx    on koperasi.pelanggan_ditolak (nik) where nik is not null;
create index pelanggan_ditolak_snapshot_idx on koperasi.pelanggan_ditolak using gin (snapshot);

create trigger pelanggan_ditolak_immutable
  before update or delete on koperasi.pelanggan_ditolak
  for each row execute function koperasi.tg_tolak_mutasi();


-- -------------------------------------------------------------------------
-- 10b.4 koreksi_storting/{cabangId}/{adminUid}/{YYYY-MM}
--       → {cm, l1, mb, ml, updatedAt, updatedBy}
-- -------------------------------------------------------------------------
-- Penyesuaian manual kolom storting Buku Pokok (PB/L1/CM/MB/ML — CLAUDE.md
-- §6.5) per admin per BULAN. Angka ini MENGUBAH hasil pembukuan, jadi tidak
-- boleh hilang: tanpanya laporan bulanan pasca-cutover akan berbeda dari
-- laporan yang sudah pernah dicetak dan ditandatangani.
--
-- Nilai disimpan apa adanya sebagai bigint rupiah. Tidak diturunkan dari
-- `pembayaran` — justru sebaliknya, ini koreksi TERHADAP hasil hitungan.
create table koperasi.koreksi_storting (
  cabang_id   text not null references koperasi.cabang(id),
  admin_id    uuid not null references koperasi.app_user(id),
  periode     date not null,                 -- selalu tanggal 1 bulan ybs

  cm          bigint not null default 0,
  l1          bigint not null default 0,
  mb          bigint not null default 0,
  ml          bigint not null default 0,

  updated_by  uuid references koperasi.app_user(id),
  updated_at  timestamptz,

  legacy_admin_uid text,
  created_at  timestamptz not null default now(),

  primary key (cabang_id, admin_id, periode)
);

create index koreksi_storting_periode_idx on koperasi.koreksi_storting (periode);

-- -------------------------------------------------------------------------
-- 10b.5 pelanggan_status_khusus/{cabangId}/{pelangganId}
--       → {statusKhusus, catatanStatusKhusus, tanggalStatusKhusus,
--          diberiTandaOleh, adminUid, adminName, namaKtp, namaPanggilan,
--          noHp, besarPinjaman, …}
-- -------------------------------------------------------------------------
-- Index per-cabang nasabah bertanda khusus. Sebagian isinya memang tumpang
-- tindih dengan kolom `nasabah.status_khusus`, TETAPI node ini menyimpan
-- SNAPSHOT saat penandaan (nama, besar pinjaman, no HP pada saat itu) yang
-- tidak dapat dipulihkan dari `nasabah` setelah datanya berubah. Karena itu
-- dipindah utuh, bukan dianggap duplikat.
create table koperasi.pelanggan_status_khusus (
  id            uuid primary key default gen_random_uuid(),
  cabang_id     text not null references koperasi.cabang(id),

  -- Nullable: nasabah yang ditandai bisa sudah dihapus dari /pelanggan.
  -- Barisnya tetap disimpan karena membawa datanya sendiri.
  nasabah_id    uuid references koperasi.nasabah(id) on delete set null,

  status_khusus text not null default '',    -- mis. 'MENUNGGU_PENCAIRAN'
  catatan       text not null default '',
  tanggal       date,

  /* `diberiTandaOleh` TIDAK konsisten di data nyata: kadang nama tampilan
   * ("Resort Idaman Panti"), kadang email ("permula@godangulu.com") —
   * BUKAN UID. Karena itu disimpan sebagai teks apa adanya dan tidak
   * di-FK-kan ke app_user; memaksakan FK akan menggagalkan impor. */
  diberi_tanda_oleh text not null default '',

  admin_id      uuid references koperasi.app_user(id),
  admin_name    text not null default '',
  nama_ktp      text not null default '',
  nama_panggilan text not null default '',
  no_hp         text not null default '',
  besar_pinjaman bigint not null default 0,

  -- Field lain dipertahankan utuh; export sampel memotong sebagian key,
  -- jadi kolom eksplisit di atas belum tentu mencakup semuanya.
  snapshot      jsonb not null default '{}'::jsonb,

  legacy_pelanggan_id text,
  legacy_admin_uid    text,
  created_at    timestamptz not null default now(),

  constraint pelanggan_status_khusus_unik unique (cabang_id, legacy_pelanggan_id)
);

create index pelanggan_status_khusus_nasabah_idx
  on koperasi.pelanggan_status_khusus (nasabah_id) where nasabah_id is not null;
create index pelanggan_status_khusus_status_idx
  on koperasi.pelanggan_status_khusus (cabang_id, status_khusus);


-- =========================================================================
-- 11. updated_at otomatis
-- =========================================================================
create or replace function koperasi.tg_touch_updated_at()
returns trigger language plpgsql as $$
begin new.updated_at := now(); return new; end;
$$;

create trigger nasabah_touch   before update on koperasi.nasabah
  for each row execute function koperasi.tg_touch_updated_at();
create trigger app_user_touch  before update on koperasi.app_user
  for each row execute function koperasi.tg_touch_updated_at();
create trigger pengajuan_touch before update on koperasi.pengajuan
  for each row execute function koperasi.tg_touch_updated_at();
-- pinjaman: updated_at di-set di dalam tg_pinjaman_no_downgrade.


-- =========================================================================
-- 12. TABEL YANG SENGAJA TIDAK DIBUAT
-- =========================================================================
-- Node RTDB berikut adalah DATA TURUNAN yang di RTDB harus dijaga oleh
-- Cloud Function dan bisa desinkron. Di Postgres semuanya menjadi
-- view/query — tidak ada state kedua yang bisa salah:
--
--   summary/{global,perCabang,perAdmin}  → view agregat
--   pembayaran_harian/{cabang}/{tgl}     → index pembayaran_tanggal_idx
--   event_harian/{cabang}/{tgl}          → query atas created_at
--   nasabah_index/{cabang}               → tabel nasabah itu sendiri
--   nik_registry/{nik}                   → unique index nasabah_nik_unik
--   pelanggan_bermasalah/{cabang}        → view berbasis jadwal vs pembayaran
--   riwayat_pinjaman/{admin}/{pid}/{N}   → baris pinjaman generasi lama
--   kasir_summary                        → view agregat kasir_entry
--
-- Konsekuensi: ±8 Cloud Function pemelihara index + job perbaikan
-- (summaryRepair_HEMAT.js, backfillPembayaranHarian.js, updateAllSummaries.js)
-- tidak punya padanan dan tidak perlu dimigrasikan. Lihat
-- 004_firebase_to_postgres_mapping.md §6.
--
-- Contoh view pengganti summary/perCabang:
create or replace view koperasi.v_summary_cabang as
select
  n.cabang_id,
  count(distinct n.id)                                          as total_nasabah,
  count(distinct n.id) filter (where p.status = 'Aktif')         as nasabah_aktif,
  count(distinct n.id) filter (where p.status = 'Lunas')         as nasabah_lunas,
  coalesce(sum(s.sisa_utang) filter (where p.status = 'Aktif'), 0) as total_piutang
from koperasi.nasabah n
left join koperasi.pinjaman p on p.nasabah_id = n.id
left join koperasi.v_pinjaman_saldo s on s.pinjaman_id = p.id
group by n.cabang_id;

-- =========================================================================
-- CATATAN VERIFIKASI
-- =========================================================================
-- Skrip ini BELUM PERNAH dijalankan terhadap instance PostgreSQL mana pun.
-- Tidak ada instance Supabase pada environment ini dan tidak ada `psql`.
-- Status: RANCANGAN yang belum tervalidasi syntax oleh server.
-- Gerbang berikutnya: jalankan di project Supabase staging (bukan produksi),
-- lalu `psql -f` dan catat outputnya.
-- =========================================================================
