-- =========================================================================
-- KOPERASI KITA — 032: v_target_harian + v_nasabah_bermasalah
-- RANCANGAN — BELUM PERNAH DIJALANKAN.
-- =========================================================================
--
-- Prasyarat: 001 (jadwal_cicilan, pembayaran), 015 (v_buku_pokok),
--            030 (definisi sisa_utang yang sudah dikoreksi).
-- Aman dijalankan kapan saja: hanya membuat view, tidak menyentuh data.
--
-- Dua view, satu berkas, karena keduanya menurunkan jawabannya dari bahan
-- yang sama — `jadwal_cicilan` dibandingkan `pembayaran`. Memisahkannya
-- berarti aturan yang sama ditulis dua kali di dua tempat.
--
-- =========================================================================
-- §1  v_target_harian — SUMBER TUNGGAL "TARGET HARI INI"
-- =========================================================================
-- Hari ini ditentukan SERVER dengan zona Asia/Jakarta, bukan jam perangkat.
-- Itu bukan detail: admin yang zonanya meleset (atau disetel manual) tidak
-- boleh bisa menggeser target hariannya sendiri.
--
-- Aturan H+1 tidak perlu ditulis ulang di sini. Ia sudah TERKANDUNG dalam
-- `jadwal_cicilan`: baris pertama jadwal dibuat untuk H+1 sejak pencairan,
-- bukan hari pencairan. Menambahkan syarat tanggal_pencairan < today di sini
-- akan menerapkan aturan yang sama DUA KALI dan memotong hari pertama.

begin;

create or replace view koperasi.v_target_harian
with (security_invoker = on) as
select
  n.cabang_id,
  n.admin_id,
  j.tanggal,
  sum(j.jumlah)::bigint as target,
  count(*)              as banyak_cicilan
from koperasi.jadwal_cicilan j
join koperasi.pinjaman p on p.id = j.pinjaman_id
join koperasi.nasabah  n on n.id = p.nasabah_id
where n.arsip_at is null
  -- Hanya generasi berjalan. Jadwal generasi lama tidak lagi ditagih.
  and p.pinjaman_ke = (
    select max(p2.pinjaman_ke) from koperasi.pinjaman p2 where p2.nasabah_id = n.id
  )
group by n.cabang_id, n.admin_id, j.tanggal;

grant select on koperasi.v_target_harian to authenticated;

commit;

-- Pemakaian (klien menyaring tanggalnya; view menyediakan semua hari supaya
-- laporan historis memakai sumber yang sama dengan hari berjalan):
--
--   select coalesce(sum(target), 0) from koperasi.v_target_harian
--    where tanggal = (now() at time zone 'Asia/Jakarta')::date;

-- =========================================================================
-- §2  v_nasabah_bermasalah — PENGGANTI NODE RTDB pelanggan_bermasalah
-- =========================================================================
-- 001:1139 sudah mencatat rencananya ("view berbasis jadwal vs pembayaran")
-- tetapi view-nya tidak pernah dibuat. Karena itu layar "Nasabah Bermasalah"
-- kosong: node RTDB-nya dulu diisi Cloud Function terjadwal, dan tidak ada
-- padanannya di Supabase.
--
-- ATURAN DISALIN PERSIS dari scheduledFunctions.js — BUKAN dirancang ulang.
-- Mengarang ambang baru akan mengubah siapa yang disebut "bermasalah" tanpa
-- ada yang memutuskannya:
--
--   hitungHariTunggakan (:636-661)
--     hari tunggakan = BANYAKNYA tanggal jadwal yang sudah lewat DAN tidak
--     punya pembayaran pada tanggal itu. Bukan selisih hari kalender, bukan
--     nominal — hitungan HARI yang terlewat.
--
--   ambang (:585-588)      >90 macet, >60 berat, >30 sedang, sisanya ringan
--   saringan (:582)        hanya hariTunggakan > 7 yang ditampilkan
--   lunas dilewati (:573)  total_pelunasan > 0 dan sudah terbayar penuh
--   piutang (:583)         greatest(0, total_pelunasan − total_dibayar)

begin;

create or replace view koperasi.v_nasabah_bermasalah
with (security_invoker = on) as
with berjalan as (
  select p.id as pinjaman_id, p.nasabah_id, p.total_pelunasan, n.cabang_id,
         n.admin_id, n.nama_ktp, n.nama_panggilan, n.wilayah, n.no_hp,
         n.legacy_pelanggan_id, n.legacy_admin_uid, p.besar_pinjaman
  from koperasi.pinjaman p
  join koperasi.nasabah n on n.id = p.nasabah_id
  where n.arsip_at is null
    and p.pinjaman_ke = (
      select max(p2.pinjaman_ke) from koperasi.pinjaman p2 where p2.nasabah_id = n.id
    )
),
tunggakan as (
  select b.pinjaman_id,
         count(*) filter (
           where j.tanggal <= (now() at time zone 'Asia/Jakarta')::date
             and not exists (
               select 1 from koperasi.pembayaran bay
               where bay.pinjaman_id = b.pinjaman_id
                 and bay.tanggal = j.tanggal
             )
         ) as hari_tunggakan
  from berjalan b
  join koperasi.jadwal_cicilan j on j.pinjaman_id = b.pinjaman_id
  group by b.pinjaman_id
)
select
  b.legacy_pelanggan_id                       as pelanggan_id,
  b.nasabah_id,
  b.cabang_id,
  b.admin_id,
  b.legacy_admin_uid                          as admin_uid,
  u.nama                                      as admin_nama,
  b.nama_ktp,
  b.nama_panggilan,
  b.wilayah,
  b.no_hp,
  b.besar_pinjaman                            as total_pinjaman,
  greatest(0, b.total_pelunasan - s.total_dibayar)::bigint as total_piutang,
  t.hari_tunggakan,
  case
    when t.hari_tunggakan > 90 then 'macet'
    when t.hari_tunggakan > 60 then 'berat'
    when t.hari_tunggakan > 30 then 'sedang'
    else 'ringan'
  end                                         as kategori,
  (select max(bay.tanggal) from koperasi.pembayaran bay
    where bay.pinjaman_id = b.pinjaman_id)    as tanggal_bayar_terakhir
from berjalan b
join tunggakan t                 on t.pinjaman_id = b.pinjaman_id
join koperasi.v_pinjaman_saldo s on s.pinjaman_id = b.pinjaman_id
left join koperasi.app_user u    on u.id = b.admin_id
where t.hari_tunggakan > 7
  -- Sudah lunas tidak pernah "bermasalah", berapa pun hari terlewatnya.
  and not (b.total_pelunasan > 0 and s.total_dibayar >= b.total_pelunasan);

grant select on koperasi.v_nasabah_bermasalah to authenticated;

commit;

-- =========================================================================
-- §3  VERIFIKASI
-- =========================================================================
-- (a) Target hari ini per cabang — bandingkan dengan angka di aplikasi admin
--     lapangan yang Anda nyatakan BENAR:
--
--       select cabang_id, sum(target) as target_hari_ini
--       from koperasi.v_target_harian
--       where tanggal = (now() at time zone 'Asia/Jakarta')::date
--       group by cabang_id order by 1;
--
--     Kalau meleset, periksa dulu apakah `jadwal_cicilan` untuk generasi
--     berjalan memang lengkap — view ini menjumlahkan apa adanya, ia tidak
--     bisa memperbaiki jadwal yang bolong.
--
-- (b) Bermasalah — sebaran kategori:
--
--       select kategori, count(*), sum(total_piutang)
--       from koperasi.v_nasabah_bermasalah group by kategori order by 1;
--
--     ⚠ Angkanya BELUM TENTU sama persis dengan node RTDB lama. Node itu
--     di-refresh terjadwal (harian), jadi isinya bisa basi sampai sehari;
--     view ini dihitung saat dibaca. Selisih kecil karena itu WAJAR — yang
--     perlu dicurigai adalah selisih besar, dan itu berarti `jadwal_cicilan`
--     tidak selengkap `hasilSimulasiCicilan` di RTDB.
--
-- (c) Bandingkan satu nasabah yang Anda tahu menunggak, hari per hari:
--
--       select j.tanggal, j.jumlah,
--              exists (select 1 from koperasi.pembayaran b
--                       where b.pinjaman_id = j.pinjaman_id
--                         and b.tanggal = j.tanggal) as ada_bayar
--       from koperasi.jadwal_cicilan j
--       where j.pinjaman_id = '<pinjaman_id>'
--       order by j.tanggal;
-- =========================================================================
