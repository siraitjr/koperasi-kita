-- =========================================================================
-- KOPERASI KITA — 033: v_target_harian sesuai SPESIFIKASI PEMILIK
-- MENGGANTIKAN rumus di 032 §1. RANCANGAN — BELUM PERNAH DIJALANKAN.
-- =========================================================================
--
-- Prasyarat: 001, 015, 030. Menggantikan `koperasi.v_target_harian` yang
-- dibuat 032 §1 — bukan menambah view baru di sebelahnya.
--
-- =========================================================================
-- §0  APA YANG BERUBAH DARI 032, DAN KENAPA TOTAL
-- =========================================================================
-- 032 menjumlahkan `jadwal_cicilan` untuk tanggal berjalan. Itu SALAH menurut
-- aturan bisnis: target bukan "berapa cicilan jatuh tempo hari ini",
-- melainkan 3% dari besar pinjaman untuk setiap nasabah yang masuk populasi
-- hari itu. Dua besaran yang berbeda sama sekali — bukan penyesuaian kecil,
-- jadi rumus lama diganti, bukan ditambal.
--
-- `jadwal_cicilan` tidak lagi menjadi sumber target. Ia tetap dipakai
-- `v_nasabah_bermasalah` (032 §2) dan layar referensi cicilan.
--
-- =========================================================================
-- §1  ATURAN — DISALIN APA ADANYA DARI SPESIFIKASI
-- =========================================================================
--   1. Kontribusi per nasabah = besar_pinjaman × 3%   (pinjaman berjalan)
--   2. Populasi hari D        = nasabah yang tanggal aktifnya jatuh di
--                               bulan(D) atau 3 bulan sebelumnya
--   3. Menunggu approval      = belum aktif = tidak dihitung
--   4. Seluruh peralihan T+1  = aktif X masuk X+1; status khusus X keluar
--                               X+1; lunas X keluar X+1
--
--   Tes inklusi hari D:
--     tanggal_aktif <= D-1
--     AND bulan(tanggal_aktif) dalam jendela 4 bulan
--     AND (tanggal_status_khusus null OR >= D)
--     AND (tanggal_lunas null OR >= D)
--
-- PEMETAAN KE KOLOM — TIDAK ADA KOLOM BARU YANG PERLU DITAMBAHKAN
-- -------------------------------------------------------------------------
-- Ketiganya sudah ada; saya periksa sebelum menambah apa pun:
--
--   tanggal aktif        → pinjaman.tanggal_pencairan
--                          (RTDB `tanggalPencairan`, migrate.js:535)
--   tanggal status khusus→ nasabah.tanggal_status_khusus      (001:223)
--   tanggal lunas        → coalesce(pinjaman.tanggal_lunas_cicilan,
--                                   pinjaman.tanggal_pelunasan)
--                          (migrate.js:536-537)
--
-- `tanggal_lunas_cicilan` didahulukan: ia menandai cicilan selesai, yang
-- sesuai maksud "lunas" pada aturan 4. `tanggal_pelunasan` dipakai sebagai
-- cadangan supaya baris warisan yang hanya mengisi satu di antaranya tetap
-- keluar dari populasi.
--
-- Butir 3 (menunggu approval tidak dihitung) tidak perlu syarat status
-- tersendiri: pinjaman yang belum cair belum punya `tanggal_pencairan`,
-- sehingga tes `tanggal_aktif <= D-1` sudah menggugurkannya. Menambahkan
-- `status <> 'Menunggu Approval'` hanya akan menyatakan hal yang sama dua
-- kali — dan bila keduanya suatu saat tidak sepakat, tidak jelas mana yang
-- menang.

-- =========================================================================
-- §2  FUNGSI + VIEW
-- =========================================================================
-- Fungsi menerima tanggal supaya laporan historis memakai aturan yang sama
-- persis dengan hari berjalan. View adalah fungsi itu untuk HARI INI, supaya
-- klien cukup melakukan SELECT biasa lewat PostgREST.
--
-- Sengaja BUKAN `security definer`: tanpa itu fungsi berjalan dengan hak
-- pemanggil sehingga RLS tetap berlaku — Pimpinan hanya melihat cabangnya.
-- (018 §0 juga mencatat definer/SET menghalangi inlining PostgreSQL.)

begin;

drop view if exists koperasi.v_target_harian;

create or replace function koperasi.target_harian(p_tanggal date)
returns table (
  cabang_id       text,
  admin_id        uuid,
  tanggal         date,
  target          bigint,
  banyak_nasabah  bigint
)
language sql
stable
as $$
  with berjalan as (
    select
      n.cabang_id,
      n.admin_id,
      p.besar_pinjaman,
      p.tanggal_pencairan                                   as tanggal_aktif,
      n.tanggal_status_khusus,
      coalesce(p.tanggal_lunas_cicilan, p.tanggal_pelunasan) as tanggal_lunas
    from koperasi.pinjaman p
    join koperasi.nasabah n on n.id = p.nasabah_id
    where n.arsip_at is null
      -- Hanya generasi berjalan (aturan 1: "pinjaman berjalan").
      and p.pinjaman_ke = (
        select max(p2.pinjaman_ke) from koperasi.pinjaman p2 where p2.nasabah_id = n.id
      )
  )
  select
    b.cabang_id,
    b.admin_id,
    p_tanggal as tanggal,
    -- 3% dibulatkan ke rupiah bulat: seluruh nominal di skema ini bigint,
    -- dan koperasi tidak menagih pecahan rupiah.
    coalesce(sum(round(b.besar_pinjaman * 0.03)), 0)::bigint as target,
    count(*)::bigint                                        as banyak_nasabah
  from berjalan b
  where b.tanggal_aktif is not null
    -- T+1: aktif hari X baru dihitung mulai X+1.
    and b.tanggal_aktif <= p_tanggal - 1
    -- Jendela 4 bulan: bulan(D) dan 3 bulan sebelumnya.
    -- Dibandingkan pada AWAL BULAN, bukan selisih hari — "3 bulan sebelumnya"
    -- di spesifikasi berarti bulan kalender (Sept → Sept, Agu, Jul, Jun),
    -- bukan 90 hari.
    and date_trunc('month', b.tanggal_aktif)
        between date_trunc('month', p_tanggal) - interval '3 months'
            and date_trunc('month', p_tanggal)
    -- T+1: status khusus hari X masih dihitung pada X, keluar mulai X+1.
    and (b.tanggal_status_khusus is null or b.tanggal_status_khusus >= p_tanggal)
    -- T+1: lunas hari X masih dihitung pada X, keluar mulai X+1.
    and (b.tanggal_lunas is null or b.tanggal_lunas >= p_tanggal)
  group by b.cabang_id, b.admin_id;
$$;

create view koperasi.v_target_harian
with (security_invoker = on) as
select * from koperasi.target_harian((now() at time zone 'Asia/Jakarta')::date);

grant execute on function koperasi.target_harian(date) to authenticated;
grant select on koperasi.v_target_harian to authenticated;

commit;

-- =========================================================================
-- §3  PERBANDINGAN WAJIB — JALANKAN SEBELUM KLIEN DIALIHKAN
-- =========================================================================
-- Anda memintanya, dan saya tidak bisa menjalankannya: saya tidak punya
-- akses ke database Anda. Ini kuerinya; klien JANGAN dialihkan sampai
-- angkanya cocok di SEMUA cabang.
--
--   select cabang_id, sum(target) as target_view, sum(banyak_nasabah) as nasabah
--   from koperasi.v_target_harian
--   group by cabang_id
--   order by cabang_id;
--
-- Bandingkan `target_view` dengan angka "Target Hari Ini" di aplikasi admin
-- lapangan untuk cabang yang sama, pada hari yang sama.
--
-- =========================================================================
-- §4  BILA ANGKANYA MELESET — PERIKSA INI DULU, BUKAN RUMUSNYA
-- =========================================================================
-- Rumusnya sederhana; yang paling mungkin meleset adalah kelengkapan data.
--
-- (a) Pinjaman berjalan TANPA tanggal_pencairan tidak akan pernah masuk
--     populasi. Bila jumlahnya besar, target akan selalu terlalu kecil:
--
--       select count(*) from koperasi.pinjaman p
--       join koperasi.nasabah n on n.id = p.nasabah_id
--       where n.arsip_at is null and p.tanggal_pencairan is null
--         and p.status in ('Aktif','Disetujui');
--
-- (b) ⚠ KEBALIKANNYA LEBIH BERBAHAYA: nasabah yang sudah lunas tetapi
--     KEDUA kolom tanggal lunasnya kosong tidak akan pernah keluar dari
--     populasi, sehingga target terlalu besar SELAMANYA dan tidak ada
--     gejala yang menunjukkannya:
--
--       select count(*) from koperasi.pinjaman p
--       join koperasi.nasabah n        on n.id = p.nasabah_id
--       join koperasi.v_pinjaman_saldo s on s.pinjaman_id = p.id
--       where n.arsip_at is null
--         and p.total_pelunasan > 0 and s.total_dibayar >= p.total_pelunasan
--         and p.tanggal_lunas_cicilan is null and p.tanggal_pelunasan is null;
--
--     Bila (b) > 0, itu backfill yang perlu dilakukan — dan tanggalnya harus
--     diambil dari pembayaran terakhir, bukan ditebak:
--
--       -- TINJAU dulu hasilnya sebelum menjalankan UPDATE.
--       -- update koperasi.pinjaman p
--       --    set tanggal_lunas_cicilan = x.tgl
--       --   from (select b.pinjaman_id, max(b.tanggal) as tgl
--       --           from koperasi.pembayaran b group by b.pinjaman_id) x
--       --  where x.pinjaman_id = p.id
--       --    and p.tanggal_lunas_cicilan is null and p.tanggal_pelunasan is null
--       --    and p.total_pelunasan > 0;
--
-- (c) Selisih tepat satu hari di seluruh cabang = tanda aturan T+1 diterapkan
--     dua kali (sekali di view, sekali di klien) — periksa sisi klien, bukan
--     view ini.
--
-- =========================================================================
-- §5  CATATAN UNTUK BUG 6
-- =========================================================================
-- Aturan T+1 yang sama berlaku untuk layar Lunas Hari Ini, Nasabah Lunas,
-- dan Sisa Tabungan. Kolom penentunya sama dengan yang dipakai di sini:
--
--   Lunas Hari Ini  : coalesce(tanggal_lunas_cicilan, tanggal_pelunasan) = D
--                     (hari X MASIH tampil pada X — keluar dari populasi
--                      target baru pada X+1, bukan hilang dari layar)
--   Nasabah Lunas   : tanggal lunas tidak null
--   Sisa Tabungan   : nasabah.status_khusus = 'MENUNGGU_PENCAIRAN'
--                     (v_buku_pokok.is_sisa_tabungan sudah menghitungnya)
--
-- Ditulis di sini supaya ketiga layar itu memakai definisi yang sama dengan
-- target, bukan definisi sendiri-sendiri.
-- =========================================================================
