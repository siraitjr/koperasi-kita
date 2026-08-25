-- =========================================================================
-- KOPERASI KITA — OPERASIONAL HARIAN (uang makan & transport staf)
-- Prasyarat RPC koperasi.rpc_sync_operasional_transport (015 batch B-4).
-- RANCANGAN — BELUM PERNAH DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- Urutan: 001 → 001a → 002 → 007 → 009 → 011 → 016a → 015 (B-1..B-4)
--
-- Node asal: operasional_harian/{cabangId}/{YYYY-MM-DD}/{staffUid}
-- Bentuk record (dari data nyata di data/firebase_sample.json):
--   {
--     uid                : "3B1yKQMPZbdDIbhZ6eLFz3dr8wo2",   -- = kunci record
--     nama               : "Resort Permula Panti",
--     uangMakan          : 15000,
--     transport          : 35000,
--     diberikanOleh      : "plclpO1gmFeskU8j3u0qKHDdYBF3",   -- kasir unit
--     diberikanOlehNama  : "Kasir Unit Panti",
--     timestamp          : 1774662363055
--   }
--
-- Dibaca kasirApi.js:627-641; yang dipakai hanya `uangMakan`, `transport`,
-- dan `nama`. Sisanya tetap dimigrasikan karena merekam SIAPA yang memberi
-- dan KAPAN — itu jejak uang, dan membuangnya berarti kehilangan
-- pertanggungjawabannya.
-- =========================================================================

begin;

create table if not exists koperasi.operasional_harian (
  cabang_id   text not null references koperasi.cabang(id),
  tanggal     date not null,

  -- Kunci record di RTDB adalah UID staf. FK dibiarkan NULLABLE: staf yang
  -- sudah keluar bisa saja tidak lagi ada di app_user, dan catatan uangnya
  -- tetap harus terbawa.
  user_id     uuid references koperasi.app_user(id),
  legacy_uid  text not null,

  nama        text not null default '',
  uang_makan  bigint not null default 0,
  transport   bigint not null default 0,

  diberikan_oleh       uuid references koperasi.app_user(id),
  diberikan_oleh_nama  text not null default '',
  diberikan_oleh_legacy_uid text,

  recorded_at timestamptz,
  created_at  timestamptz not null default now(),

  -- PK memakai legacy_uid, BUKAN user_id: user_id bisa NULL untuk staf yang
  -- sudah tidak terdaftar, dan NULL tidak bisa jadi bagian primary key.
  primary key (cabang_id, tanggal, legacy_uid),

  constraint operasional_nominal_wajar
    check (uang_makan >= 0 and transport >= 0)
);

create index if not exists operasional_harian_tanggal_idx
  on koperasi.operasional_harian (cabang_id, tanggal);

comment on table koperasi.operasional_harian is
  'Uang makan & transport harian per staf. Sumber angka untuk '
  'rpc_sync_operasional_transport, yang meringkasnya jadi satu entri kasir '
  'bertanda auto_ops_{tanggal}.';

-- =========================================================================
-- RLS
-- =========================================================================
alter table koperasi.operasional_harian enable row level security;
alter table koperasi.operasional_harian force  row level security;

-- Peran yang sama dengan pembacaan kasir (kasirApi.js:188), ditambah staf
-- yang bersangkutan — wajar seseorang boleh melihat uang makannya sendiri.
create policy operasional_harian_baca on koperasi.operasional_harian
  for select to authenticated
  using (
    user_id = auth.uid()
    or (
      koperasi_priv.role() in (
        'kasir_unit','kasir_wilayah','sekretaris','pimpinan','koordinator','pengawas'
      )
      and koperasi_priv.boleh_lihat_cabang(cabang_id)
    )
  );

-- Penulisan hanya lewat service_role (skrip migrasi) atau RPC. Tidak ada
-- policy tulis di sini, dan GRANT di bawah hanya SELECT — sejalan dengan
-- tabel uang lain di skema ini.
grant select on koperasi.operasional_harian to authenticated;

commit;

-- =========================================================================
-- VERIFIKASI
-- =========================================================================
-- 1) RLS aktif & dipaksa:
--      select relrowsecurity, relforcerowsecurity from pg_class
--       where relname = 'operasional_harian';   -- true/true
--
-- 2) Klien tidak punya hak tulis:
--      select privilege_type from information_schema.role_table_grants
--       where table_name='operasional_harian' and grantee='authenticated';
--      -- hanya SELECT
--
-- 3) Sesudah migrasi data:
--      select cabang_id, tanggal, count(*) baris,
--             sum(uang_makan + transport) total
--        from koperasi.operasional_harian
--       group by 1,2 order by tanggal desc limit 10;
-- =========================================================================
