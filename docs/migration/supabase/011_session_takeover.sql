-- =========================================================================
-- KOPERASI KITA — SESI, TAKEOVER, & FORCE LOGOUT
-- Prasyarat Edge Function `session-management`.
-- RANCANGAN — BELUM PERNAH DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- Urutan jalankan: 001 → 001a → 002 → 007 → 009 → 011
--
-- Padanan tiga node RTDB yang dicatat sebagai belum termigrasi di
-- 002 §11 L-4 dan 006 §6:
--     session_lock/{adminUid}      → koperasi.session_lock
--     remote_takeover/{adminUid}   → digabung ke session_lock (lihat §1)
--     force_logout/{uid}           → koperasi.force_logout
--
-- Ketiganya dipakai alur Remote Takeover: Pimpinan mengambil alih sesi Admin
-- Lapangan untuk mengoreksi data langsung. Ini kemampuan paling sensitif di
-- sistem — satu orang bertindak atas nama orang lain — jadi jejaknya dibuat
-- permanen dan tidak bisa dihapus pelakunya.
-- =========================================================================

begin;

-- =========================================================================
-- 1. session_lock — satu admin, satu sesi
-- =========================================================================
-- RTDB memisahkan `session_lock` (siapa mengunci) dan `remote_takeover`
-- (status aktif/selesai). Keduanya selalu ditulis dan dihapus bersamaan
-- (remoteTakeover.js:152-165 dan :221-223), jadi memisahkannya hanya
-- menciptakan peluang keduanya tidak sinkron. Disatukan di sini.
create table koperasi.session_lock (
  admin_id        uuid primary key references koperasi.app_user(id) on delete cascade,
  locked_by       uuid not null references koperasi.app_user(id),
  pimpinan_name   text not null default '',
  status          text not null default 'active' check (status in ('active','released')),
  locked_at       timestamptz not null default now(),
  released_at     timestamptz
);

create index session_lock_by_idx on koperasi.session_lock (locked_by);

comment on table koperasi.session_lock is
  'Kunci sesi admin saat Remote Takeover. admin_id = PK, jadi satu admin '
  'tidak mungkin terkunci dua pimpinan sekaligus — invarian yang di RTDB '
  'hanya dijaga oleh pemeriksaan aplikasi.';

-- =========================================================================
-- 2. force_logout — sinyal keluar paksa
-- =========================================================================
-- Ditulis saat takeover dimulai dan saat password direset. Android
-- mendengarkannya untuk melempar pengguna keluar dari layar yang terbuka.
create table koperasi.force_logout (
  user_id     uuid primary key references koperasi.app_user(id) on delete cascade,
  reason      text not null,             -- 'takeover' | 'password_reset'
  by_user     uuid references koperasi.app_user(id),
  created_at  timestamptz not null default now()
);

-- =========================================================================
-- 3. takeover_log — jejak permanen (BARU, tidak ada di RTDB)
-- =========================================================================
-- session_lock dihapus saat sesi dikembalikan, sehingga di RTDB TIDAK ADA
-- jejak bahwa takeover pernah terjadi. Untuk kemampuan sesensitif ini,
-- ketiadaan jejak adalah kekurangan nyata: tidak ada cara menjawab
-- "siapa yang pernah masuk sebagai admin X, kapan, dan berapa lama".
--
-- Tabel ini append-only dan tidak ikut terhapus saat sesi dikembalikan.
create table koperasi.takeover_log (
  id           uuid primary key default gen_random_uuid(),
  admin_id     uuid not null references koperasi.app_user(id),
  pimpinan_id  uuid not null references koperasi.app_user(id),
  aksi         text not null check (aksi in ('takeover','restore')),
  cabang_id    text references koperasi.cabang(id),
  keterangan   text not null default '',
  created_at   timestamptz not null default now()
);

create index takeover_log_admin_idx on koperasi.takeover_log (admin_id, created_at desc);
create index takeover_log_pimpinan_idx on koperasi.takeover_log (pimpinan_id, created_at desc);

create trigger takeover_log_immutable
  before update or delete on koperasi.takeover_log
  for each row execute function koperasi.tg_tolak_mutasi();

-- =========================================================================
-- 4. RLS
-- =========================================================================
alter table koperasi.session_lock  enable row level security;
alter table koperasi.session_lock  force  row level security;
alter table koperasi.force_logout  enable row level security;
alter table koperasi.force_logout  force  row level security;
alter table koperasi.takeover_log  enable row level security;
alter table koperasi.takeover_log  force  row level security;

-- Admin perlu TAHU dirinya sedang dikunci (untuk menampilkan status di
-- layar); pimpinan & pengawas perlu melihat kunci yang mereka pegang.
create policy session_lock_baca on koperasi.session_lock
  for select to authenticated
  using (
    admin_id = auth.uid()
    or locked_by = auth.uid()
    or koperasi_priv.is_pengawas()
  );

-- User harus bisa membaca sinyal logout-nya sendiri — itu seluruh gunanya.
create policy force_logout_baca on koperasi.force_logout
  for select to authenticated
  using (user_id = auth.uid() or koperasi_priv.is_pengawas());

create policy takeover_log_baca on koperasi.takeover_log
  for select to authenticated
  using (
    admin_id = auth.uid()
    or pimpinan_id = auth.uid()
    or koperasi_priv.is_pengawas()
  );

-- TIDAK ADA policy tulis, dan TIDAK ADA GRANT tulis di bawah.
-- Ketiga tabel hanya boleh ditulis Edge Function lewat service_role.
-- Kalau klien bisa menulis session_lock sendiri, admin mana pun bisa
-- mengunci admin lain; kalau bisa menghapus force_logout-nya sendiri, ia
-- bisa menolak diusir keluar.
grant select on koperasi.session_lock, koperasi.force_logout,
                koperasi.takeover_log
to authenticated;

commit;

-- =========================================================================
-- VERIFIKASI CEPAT
-- =========================================================================
-- 1) RLS aktif & dipaksa:
--      select relname, relrowsecurity, relforcerowsecurity from pg_class
--       where relname in ('session_lock','force_logout','takeover_log');
--      -- ketiganya true/true
--
-- 2) Klien TIDAK punya hak tulis:
--      select table_name, privilege_type from information_schema.role_table_grants
--       where grantee='authenticated'
--         and table_name in ('session_lock','force_logout','takeover_log');
--      -- hanya SELECT
--
-- 3) Sesudah satu takeover lewat aplikasi:
--      select * from koperasi.takeover_log order by created_at desc limit 5;
-- =========================================================================
