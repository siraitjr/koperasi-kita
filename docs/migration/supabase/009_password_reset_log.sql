-- =========================================================================
-- KOPERASI KITA — TABEL AUDIT RESET PASSWORD
-- Prasyarat Edge Function `user-management` (Milestone 4).
-- RANCANGAN — BELUM PERNAH DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- Urutan jalankan: 001 → 001a → 002 → 007 → 009
--
-- Padanan node RTDB `password_reset_logs` yang ditulis Cloud Function lama
-- (functions/resetUserPassword.js:111-119 untuk jalur sukses, :142-149 untuk
-- jalur gagal). Dicatat di 006 §8.3 sebagai satu-satunya bagian audit trail
-- yang belum punya tabel.
--
-- JALANKAN SEBELUM `supabase functions deploy user-management`.
-- Tanpa tabel ini, Edge Function tetap berjalan tetapi audit log GAGAL
-- DITULIS DIAM-DIAM — catatAudit() sengaja tidak menggagalkan operasi
-- (paritas dengan perilaku Cloud Function lama), jadi kegagalannya hanya
-- muncul di log Edge, bukan di layar Pengawas.
-- =========================================================================

begin;

create table if not exists koperasi.password_reset_log (
  id             uuid primary key default gen_random_uuid(),

  -- Nullable, dan itu disengaja: percobaan yang gagal karena target tidak
  -- ditemukan tetap WAJIB tercatat, dan pada saat itu belum ada id.
  target_id      uuid references koperasi.app_user(id),
  target_email   text,

  reset_by       uuid references koperasi.app_user(id),
  reset_by_email text,

  berhasil       boolean not null default true,
  error          text,

  created_at     timestamptz not null default now()
);

comment on table koperasi.password_reset_log is
  'Audit reset password. Padanan node RTDB password_reset_logs. Ditulis '
  'HANYA oleh Edge Function user-management lewat service_role.';

create index if not exists password_reset_log_waktu_idx
  on koperasi.password_reset_log (created_at desc);

-- Berguna saat menelusuri "siapa saja yang pernah menyentuh akun ini".
create index if not exists password_reset_log_target_idx
  on koperasi.password_reset_log (target_id, created_at desc)
  where target_id is not null;

-- =========================================================================
-- RLS
-- =========================================================================
alter table koperasi.password_reset_log enable row level security;
alter table koperasi.password_reset_log force row level security;

-- Hanya Pengawas yang boleh MEMBACA.
create policy password_reset_log_baca on koperasi.password_reset_log
  for select to authenticated
  using (koperasi_priv.is_pengawas());

-- SENGAJA TIDAK ADA policy INSERT/UPDATE/DELETE, dan tidak ada GRANT tulis
-- di bawah. Penulisan hanya lewat service_role di Edge Function, yang
-- mem-bypass RLS. Kalau klien bisa menulis atau menghapus sendiri, tabel ini
-- berhenti menjadi bukti — dan bukti yang bisa diubah pelakunya bukan bukti.
grant select on koperasi.password_reset_log to authenticated;

commit;

-- =========================================================================
-- VERIFIKASI CEPAT setelah dijalankan
-- =========================================================================
-- 1) Tabel & RLS aktif:
--      select relrowsecurity, relforcerowsecurity
--        from pg_class where relname = 'password_reset_log';
--      -- keduanya harus true
--
-- 2) Tidak ada hak tulis untuk peran klien:
--      select privilege_type from information_schema.role_table_grants
--       where table_name = 'password_reset_log' and grantee = 'authenticated';
--      -- hanya SELECT
--
-- 3) Sesudah satu reset password lewat aplikasi Pengawas:
--      select created_at, target_email, berhasil, error
--        from koperasi.password_reset_log order by created_at desc limit 5;
-- =========================================================================
