-- =========================================================================
-- KOPERASI KITA — 026: REKAP HARIAN BEKU (benteng anti-shrink historis)
-- Menutup G-1 (025 §3). RANCANGAN — BELUM PERNAH DIJALANKAN.
-- =========================================================================
--
-- Prasyarat: … → 018 B-1 → 022 (butuh app_user.legacy_uid).
--
-- KEPUTUSAN PEMILIK: impor nilainya, JANGAN hitung ulang. Angka Buku Rekap
-- historis harus tetap identik dengan cetakan lama.
--
-- Node asal (freezeRekapHarian.js:22-26, ditulis 23:59 WIB tiap hari):
--   rekap_harian_final/{adminUid}/{YYYY-MM-DD} = {
--     target,    -- dari summary.targetHariIni saat snapshot
--     storting,  -- dari summary.pembayaranHariIni saat snapshot
--     frozenAt   -- ServerValue.TIMESTAMP
--   }
--
-- Dibaca bukuPokokApi.js:460, dirakit :951-975 jadi
-- `rekapBeku[adminUid]["dd MMM yyyy"] = {target, storting}`, lalu dipakai
-- frontend sebagai OVERRIDE kolom Target & Storting historis di Buku Rekap.
--
-- KENAPA INI TIDAK BOLEH DIHITUNG ULANG
-- -------------------------------------------------------------------------
-- Justru itu gunanya node ini. Komentar aslinya (freezeRekapHarian.js:76-78):
-- "Tetap snapshot meski target=0 … tanpa entri, fallback live mungkin compute
-- beda kemudian (lewat 3 bulan, dst)." Angka beku adalah angka yang sudah
-- dicetak dan ditandatangani; menghitungnya ulang dari data hari ini akan
-- menghasilkan nilai lain dan membuat laporan lama tidak bisa direkonsiliasi.
-- =========================================================================

begin;

create table if not exists koperasi.rekap_harian_beku (
  legacy_admin_uid text not null,
  tanggal          date not null,

  -- Nilai BEKU. Diimpor apa adanya dari RTDB; tidak pernah dihitung ulang.
  target           bigint not null default 0,
  storting         bigint not null default 0,

  -- FK nullable, pola sama dengan operasional_harian/absensi: admin yang
  -- sudah keluar bisa tidak lagi ada di app_user, dan rekap hariannya tetap
  -- harus terbawa — justru baris merekalah yang paling historis.
  admin_id         uuid references koperasi.app_user(id),
  cabang_id        text references koperasi.cabang(id),

  frozen_at        timestamptz,
  sumber           text not null default 'rtdb',   -- 'rtdb' | 'supabase'
  created_at       timestamptz not null default now(),

  primary key (legacy_admin_uid, tanggal),
  constraint rekap_beku_nominal_wajar check (target >= 0 and storting >= 0)
);

create index if not exists rekap_beku_admin_idx
  on koperasi.rekap_harian_beku (admin_id, tanggal desc);
create index if not exists rekap_beku_cabang_idx
  on koperasi.rekap_harian_beku (cabang_id, tanggal desc);

comment on table koperasi.rekap_harian_beku is
  'Snapshot Target & Storting harian per admin. Nilai BEKU — override kolom '
  'historis Buku Rekap. Jangan pernah dihitung ulang dari data berjalan.';

-- =========================================================================
-- VIEW — bentuk yang langsung dipakai web
-- =========================================================================
-- Menyediakan `tanggal_indo` ("dd MMM yyyy") supaya frontend bisa lookup
-- dengan kunci yang SAMA dengan kolom Buku Rekap, tanpa konversi tambahan —
-- persis alasan `isoKeyToTanggalIndo` ada di bukuPokokApi.js:265-268.
create or replace view koperasi.v_rekap_harian_beku
with (security_invoker = on) as
select
  r.legacy_admin_uid,
  r.admin_id,
  r.cabang_id,
  r.tanggal,
  to_char(r.tanggal, 'DD ') ||
    (array['Jan','Feb','Mar','Apr','Mei','Jun',
           'Jul','Agu','Sep','Okt','Nov','Des'])[extract(month from r.tanggal)::int] ||
    to_char(r.tanggal, ' YYYY')                       as tanggal_indo,
  r.target,
  r.storting,
  r.frozen_at,
  r.sumber
from koperasi.rekap_harian_beku r;

-- `to_char(…, 'Mon')` sengaja TIDAK dipakai: keluarannya bergantung
-- lc_time server dan akan menghasilkan "May"/"Aug"/"Oct"/"Dec", bukan
-- "Mei"/"Agu"/"Okt"/"Des". Frontend mencocokkan string ini persis, jadi satu
-- huruf berbeda = kolom historis kosong tanpa satu pun galat.

-- =========================================================================
-- RLS
-- =========================================================================
alter table koperasi.rekap_harian_beku enable row level security;
alter table koperasi.rekap_harian_beku force  row level security;

create policy rekap_beku_baca on koperasi.rekap_harian_beku
  for select to authenticated
  using (
    admin_id = auth.uid()
    or cabang_id = any ((select koperasi_priv.cabang_terlihat_arr())::text[])
    -- Baris warisan yang cabang_id-nya gagal dipetakan tetap terlihat oleh
    -- pengawas; tanpa ini angka historis mereka lenyap dari Buku Rekap.
    or (cabang_id is null and koperasi_priv.is_pengawas())
  );

-- Tanpa policy tulis: hanya service_role (skrip impor) dan RPC di bawah.
grant select on koperasi.rekap_harian_beku, koperasi.v_rekap_harian_beku
  to authenticated;

-- =========================================================================
-- RPC — pembekuan harian PENGGANTI freezeRekapHarian.js
-- =========================================================================
-- ⚠ SETELAH 1 SEPTEMBER TIDAK ADA LAGI YANG MEMBEKUKAN.
-- `freezeRekapHarian` adalah Cloud Function terjadwal 23:59 WIB. Ia mati
-- bersama Firebase. Tanpa pengganti, rekap harian berhenti dibekukan pada
-- hari cutoff — dan enam bulan lagi kolom historis September ke atas akan
-- ikut bergeser, persis masalah yang 026 ini selesaikan untuk masa lalu.
--
-- Jadwalkan dengan pg_cron (Supabase: Database → Extensions → pg_cron):
--   select cron.schedule('bekukan-rekap-harian', '59 16 * * *',
--          $$ select koperasi.rpc_bekukan_rekap_harian(); $$);
--   -- 16:59 UTC = 23:59 WIB. pg_cron memakai UTC; kalau ditulis '59 23'
--   -- ia akan berjalan pukul 06:59 WIB keesokan harinya dan membekukan
--   -- hari yang salah.
create or replace function koperasi.rpc_bekukan_rekap_harian(
  p_tanggal date default null
) returns int
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_tgl   date := coalesce(p_tanggal, (now() at time zone 'Asia/Jakarta')::date);
  v_baris int;
begin
  with storting as (
    -- Storting = yang benar-benar dibayar hari itu, per admin.
    select n.admin_id, n.cabang_id, sum(b.jumlah)::bigint as jml
      from koperasi.pembayaran b
      join koperasi.pinjaman  p on p.id = b.pinjaman_id
      join koperasi.nasabah   n on n.id = p.nasabah_id
     where b.tanggal = v_tgl
       and not exists (select 1 from koperasi.pembayaran_koreksi k
                        where k.pembayaran_id = b.id)
     group by n.admin_id, n.cabang_id
  ),
  target as (
    -- Target = cicilan yang JATUH TEMPO hari itu menurut jadwal.
    select n.admin_id, n.cabang_id, sum(j.jumlah)::bigint as jml
      from koperasi.jadwal_cicilan j
      join koperasi.pinjaman p on p.id = j.pinjaman_id
      join koperasi.nasabah  n on n.id = p.nasabah_id
     where j.tanggal = v_tgl
       and p.status in ('Aktif', 'Disetujui')
     group by n.admin_id, n.cabang_id
  ),
  gabung as (
    select coalesce(s.admin_id, t.admin_id)   as admin_id,
           coalesce(s.cabang_id, t.cabang_id) as cabang_id,
           coalesce(t.jml, 0)                 as target,
           coalesce(s.jml, 0)                 as storting
      from storting s full outer join target t
        on t.admin_id = s.admin_id
  )
  insert into koperasi.rekap_harian_beku (
    legacy_admin_uid, tanggal, target, storting,
    admin_id, cabang_id, frozen_at, sumber
  )
  select coalesce(u.legacy_uid, 'sb:' || g.admin_id::text),
         v_tgl, g.target, g.storting,
         g.admin_id, g.cabang_id, now(), 'supabase'
    from gabung g
    join koperasi.app_user u on u.id = g.admin_id
  -- `do nothing`, BUKAN `do update`: sekali beku tetap beku. Menjalankan
  -- ulang tidak boleh menimpa angka yang sudah dicetak — itu justru yang
  -- membuatnya "beku". Untuk mengubah dengan sengaja, tiru
  -- refreezeRekapHarian.js: simpan nilai lama ke tabel audit lebih dulu.
  on conflict (legacy_admin_uid, tanggal) do nothing;

  get diagnostics v_baris = row_count;
  return v_baris;
end;
$$;

revoke all on function koperasi.rpc_bekukan_rekap_harian(date) from public, anon, authenticated;
-- Hanya penjadwal. Tidak ada alasan klien memicunya.
grant execute on function koperasi.rpc_bekukan_rekap_harian(date) to service_role;

commit;

-- =========================================================================
-- VERIFIKASI
-- =========================================================================
-- 1) Sesudah impor (skrip A):
--      select count(*) baris, count(distinct legacy_admin_uid) admin,
--             min(tanggal) awal, max(tanggal) akhir,
--             count(*) filter (where admin_id is null) as admin_yatim
--        from koperasi.rekap_harian_beku;
--
-- 2) Format tanggal_indo — WAJIB, ini yang paling mudah salah senyap:
--      select distinct tanggal, tanggal_indo from koperasi.v_rekap_harian_beku
--       where extract(month from tanggal) in (5,8,10,12) limit 8;
--      -- harus "Mei"/"Agu"/"Okt"/"Des", BUKAN "May"/"Aug"/"Oct"/"Dec"
--
-- 3) Bandingkan dengan RTDB untuk beberapa (admin, tanggal) acak SELAGI
--    Firebase masih hidup. Angkanya harus identik — kalau tidak, impornya
--    yang salah, dan itu harus dibereskan sebelum 1 September.
--
-- 4) UJI RPC PENGGANTI sebelum mengandalkannya. Jalankan untuk tanggal yang
--    SUDAH dibekukan RTDB, ke tabel salinan, lalu bandingkan:
--      create table koperasi.rekap_uji (like koperasi.rekap_harian_beku);
--      -- arahkan RPC ke sana secara manual, atau bandingkan hasil query
--      -- storting/target-nya dengan nilai beku yang sudah ada.
--
--    ⚠ `target` di RPC dihitung dari `jadwal_cicilan`, sedangkan RTDB
--      memakai `summary.targetHariIni` hasil `summaryHelpers.js`. Keduanya
--      BELUM tentu identik. Storting jauh lebih aman (dijumlah dari
--      pembayaran nyata). Kalau target meleset, perbaiki rumusnya SEBELUM
--      D-0 — sesudah itu tidak ada lagi pembanding.
--
-- 5) Jadwalkan pg_cron (lihat komentar di atas RPC) dan pastikan menyala:
--      select jobname, schedule, active from cron.job;
-- =========================================================================
--
-- YANG TIDAK DIBAWA: `rekap_harian_final_audit/` (refreezeRekapHarian.js:139)
-- — jejak siapa pernah membekukan ulang dan nilai sebelumnya. Kecil, tetapi
-- ia audit trail dan ikut mati 1 September. Kalau ada riwayat refreeze yang
-- penting, ekspor node itu juga; strukturnya
-- {YYYY-MM-DD}/{adminUid}/{before, refreezedBy, refreezedAt}.
-- =========================================================================
