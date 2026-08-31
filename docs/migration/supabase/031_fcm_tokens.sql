-- =========================================================================
-- KOPERASI KITA — 031: fcm_tokens + pemicu notifikasi approval
-- RANCANGAN — BELUM PERNAH DIJALANKAN.
-- =========================================================================
--
-- Prasyarat: 001 (app_user, pengajuan), 002 (RLS).
-- BATCH 1 (§1-§2) berdiri sendiri dan aman dijalankan sekarang.
-- BATCH 2 (§3) BUTUH Edge Function `notify-approval` sudah ter-deploy dan
--   `app.settings.*` sudah diisi — lihat §4. Jangan jalankan sebelum itu.
--
-- =========================================================================
-- §1  TABEL TOKEN
-- =========================================================================
-- Pengganti node RTDB `fcm_tokens/{uid}`.
--
-- SATU BARIS PER (pengguna, token), bukan satu baris per pengguna. RTDB
-- menyimpan satu token per uid sehingga login di perangkat kedua diam-diam
-- membuang token perangkat pertama — dan perangkat itu berhenti menerima
-- notifikasi tanpa gejala apa pun. Di sini keduanya hidup berdampingan;
-- pembersihan dilakukan saat token ditolak FCM (§4), bukan dengan menebak.

begin;

create table if not exists koperasi.fcm_token (
  user_id     uuid not null references koperasi.app_user(id) on delete cascade,
  token       text not null,
  platform    text not null default 'android',
  updated_at  timestamptz not null default now(),
  primary key (user_id, token)
);

create index if not exists fcm_token_user_idx on koperasi.fcm_token (user_id);

alter table koperasi.fcm_token enable row level security;
alter table koperasi.fcm_token force row level security;

-- Pengguna hanya menyentuh tokennya sendiri. Tidak ada peran yang boleh
-- membaca token orang lain: token adalah alamat kirim, dan siapa pun yang
-- memilikinya bisa dikirimi notifikasi atas nama koperasi.
drop policy if exists fcm_token_sendiri on koperasi.fcm_token;
create policy fcm_token_sendiri on koperasi.fcm_token
  for all to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid());

grant select, insert, update, delete on koperasi.fcm_token to authenticated;

commit;

-- =========================================================================
-- §2  SIAPA YANG HARUS DIBERI TAHU
-- =========================================================================
-- Dipisah dari pemicunya supaya aturan "siapa menangani fase apa" hidup di
-- SATU tempat, dan Android membaca aturan yang sama (SupabaseBaca.fasePeran).

begin;

create or replace function koperasi.penerima_fase(
  p_cabang text,
  p_fase   koperasi.approval_phase
)
returns table (user_id uuid)
language sql
stable
security definer
set search_path = koperasi, public
as $$
  select u.id
  from koperasi.app_user u
  where u.aktif
    and case p_fase
      -- Pimpinan dipanggil DUA kali: pembuka dan penutup rantai.
      when 'awaiting_pimpinan'        then u.role = 'pimpinan'    and u.cabang_id = p_cabang
      when 'awaiting_pimpinan_final'  then u.role = 'pimpinan'    and u.cabang_id = p_cabang
      -- Koordinator & Pengawas lintas cabang.
      when 'awaiting_koordinator'       then u.role = 'koordinator'
      when 'awaiting_koordinator_final' then u.role = 'koordinator'
      when 'awaiting_pengawas'          then u.role = 'pengawas'
      else false
    end;
$$;

commit;

-- =========================================================================
-- §3  PEMICU → EDGE FUNCTION   ⚠ JANGAN JALANKAN SEBELUM §4 SELESAI
-- =========================================================================
-- Memakai pg_net (tersedia di Supabase) untuk memanggil Edge Function secara
-- asinkron. ASINKRON itu disengaja: bila pemanggilan dibuat sinkron dan FCM
-- sedang lambat atau mati, INSERT pengajuannya ikut gagal — pengajuan hilang
-- gara-gara notifikasi. Notifikasi yang telat jauh lebih baik daripada
-- pengajuan yang tidak tersimpan.
--
--   create extension if not exists pg_net with schema extensions;
--
--   create or replace function koperasi.tg_notifikasi_approval()
--   returns trigger
--   language plpgsql
--   security definer
--   set search_path = koperasi, public, extensions
--   as $$
--   declare
--     v_url text := current_setting('app.settings.edge_url', true);
--     v_key text := current_setting('app.settings.service_key', true);
--   begin
--     -- Hanya kirim bila fase BERUBAH (atau baris baru). Tanpa penjagaan ini
--     -- setiap UPDATE apa pun pada pengajuan akan membanjiri notifikasi.
--     if tg_op = 'UPDATE' and new.phase is not distinct from old.phase then
--       return new;
--     end if;
--     if v_url is null or v_key is null then
--       raise warning 'app.settings.edge_url/service_key belum diisi — notifikasi dilewati';
--       return new;
--     end if;
--
--     perform extensions.net_http_post(
--       url     := v_url || '/functions/v1/notify-approval',
--       headers := jsonb_build_object(
--                    'Content-Type',  'application/json',
--                    'Authorization', 'Bearer ' || v_key),
--       body    := jsonb_build_object(
--                    'pengajuan_id', new.id,
--                    'pinjaman_id',  new.pinjaman_id,
--                    'cabang_id',    new.cabang_id,
--                    'phase',        new.phase,
--                    'final_decision', new.final_decision)
--     );
--     return new;
--   end;
--   $$;
--
--   drop trigger if exists pengajuan_notifikasi on koperasi.pengajuan;
--   create trigger pengajuan_notifikasi
--     after insert or update of phase on koperasi.pengajuan
--     for each row execute function koperasi.tg_notifikasi_approval();
--
-- =========================================================================
-- §4  YANG HARUS DISIAPKAN SEBELUM §3 — DAN KENAPA
-- =========================================================================
-- (1) Deploy Edge Function `notify-approval` (berkas terpisah, lihat
--     supabase/functions/notify-approval/index.ts di repo ini).
--
-- (2) Isi setelan yang dibaca pemicu. Keduanya rahasia; JANGAN menaruhnya
--     di berkas yang ikut ter-commit:
--
--       alter database postgres set app.settings.edge_url    = 'https://<ref>.supabase.co';
--       alter database postgres set app.settings.service_key = '<service_role_key>';
--
--     ⚠ `service_role` di sini AMAN karena tidak pernah meninggalkan server:
--     ia dipakai Postgres untuk memanggil Edge Function-nya sendiri. Ini
--     BERBEDA dengan menaruhnya di APK, yang memberi akses penuh ke siapa pun
--     yang membongkar aplikasi.
--
-- (3) Edge Function butuh kredensial FCM. Firebase Cloud Messaging TIDAK ikut
--     mati bersama RTDB — yang berhenti dibayar adalah paket Blaze untuk
--     Functions/RTDB. Kalau FCM juga hilang, notifikasi harus pindah ke
--     penyedia lain dan itu pekerjaan tersendiri.
--
-- =========================================================================
-- §5  VERIFIKASI
-- =========================================================================
-- Token tersimpan?
--   select user_id, platform, updated_at from koperasi.fcm_token;
--
-- Penerima fase benar? (ganti cabang sesuai data Anda)
--   select * from koperasi.penerima_fase('panti', 'awaiting_pimpinan');
--   -- harapan: berisi uid Pimpinan cabang itu, bukan admin lapangan.
--
-- Sesudah §3 dijalankan, pemicu terpasang?
--   select tgname from pg_trigger where tgrelid = 'koperasi.pengajuan'::regclass;
-- =========================================================================
