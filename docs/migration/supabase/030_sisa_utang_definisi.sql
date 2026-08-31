-- =========================================================================
-- KOPERASI KITA — 030: perbaiki DEFINISI sisa utang
-- RANCANGAN — JALANKAN AKAN MENGUBAH ANGKA YANG TAMPIL. Baca §2 dulu.
-- =========================================================================
--
-- Prasyarat: 001 (v_pinjaman_saldo), 015 (v_buku_pokok memakainya).
-- Jalankan di SQL Editor Supabase. Tidak ada perubahan data — hanya view.
--
-- =========================================================================
-- §1  MASALAHNYA: BUKAN DATA, MELAINKAN RUMUS
-- =========================================================================
-- `v_pinjaman_saldo` (001:535-548) menghitung:
--
--     sisa_utang = besar_pinjaman − Σ pembayaran
--
-- `besar_pinjaman` adalah POKOK. Yang wajib dilunasi nasabah adalah pokok
-- DITAMBAH jasa, dan nilai itu sudah tersimpan di `total_pelunasan`.
--
-- Akibatnya sisa utang versi server selalu lebih kecil daripada yang
-- sebenarnya terutang, sebesar jasa pinjaman. Android menghitungnya dengan
-- benar (total_pelunasan − Σ bayar), sehingga kedua sisi menampilkan angka
-- berbeda untuk nasabah yang sama meskipun keduanya membaca 12 pembayaran
-- yang sama persis. Yang salah sisi server.
--
-- Perbaikannya di view, BUKAN di web: `sisa_utang` dipakai web
-- (apiSupabase.js:818), `v_buku_pokok_summary.total_piutang`, dan penanda
-- `is_lunas`. Menambalnya di web hanya akan membuat tiga pembaca punya tiga
-- jawaban.
--
-- =========================================================================
-- §2  YANG AKAN BERUBAH DI LAYAR — HARAP DIBACA SEBELUM MENJALANKAN
-- =========================================================================
-- (a) Sisa utang tiap nasabah NAIK sebesar jasa yang belum dibayar.
--     Ini memang koreksi, tetapi angkanya akan terlihat "naik mendadak"
--     bagi siapa pun yang tidak tahu penyebabnya.
--
-- (b) `total_piutang` di dasbor Pimpinan/Koordinator/Pengawas ikut naik.
--
-- (c) ⚠ JUMLAH NASABAH LUNAS BISA BERKURANG. `v_buku_pokok.is_lunas`
--     (015:119-120) berbunyi `sisa_utang <= 0 and total_pelunasan > 0`.
--     Dengan rumus lama yang terlalu kecil, sebagian nasabah tercatat LUNAS
--     padahal jasanya belum tuntas. Sesudah perbaikan mereka kembali AKTIF.
--
--     Ini bukan efek samping yang perlu dihindari — justru itulah yang
--     seharusnya. Tetapi tetap harus disengaja: hitung dulu berapa banyak
--     yang terpengaruh dengan §4 SEBELUM menjalankan §3, supaya tidak ada
--     kejutan saat staf membuka aplikasi.
--
-- =========================================================================
-- §3  PERUBAHAN
-- =========================================================================
-- `create or replace` mempertahankan nama, tipe, dan URUTAN kolom yang sama,
-- jadi `v_buku_pokok` dan `v_buku_pokok_summary` yang bergantung padanya
-- tetap sah tanpa perlu dibuat ulang.

begin;

create or replace view koperasi.v_pinjaman_saldo as
select
  p.id                                   as pinjaman_id,
  p.nasabah_id,
  p.pinjaman_ke,
  p.status,
  p.besar_pinjaman,
  coalesce(sum(b.jumlah) filter (where k.id is null), 0) as total_dibayar,

  -- Kewajiban = total_pelunasan bila terisi.
  --
  -- Cadangan dipakai HANYA bila total_pelunasan = 0 (kolomnya NOT NULL
  -- DEFAULT 0, jadi tidak pernah NULL — 0 berarti "belum dihitung", bukan
  -- "tidak ada kewajiban"). `jasa_pinjaman` adalah PERSEN (001:283), jadi
  -- cadangannya pokok + persen×pokok, bukan pokok + angka persennya.
  --
  -- Pembulatan ke bilangan bulat rupiah: seluruh nominal di skema ini
  -- bigint, dan koperasi tidak menagih pecahan rupiah.
  coalesce(
    nullif(p.total_pelunasan, 0),
    p.besar_pinjaman + round(p.besar_pinjaman * p.jasa_pinjaman / 100.0)
  )::bigint
    - coalesce(sum(b.jumlah) filter (where k.id is null), 0) as sisa_utang

from koperasi.pinjaman p
left join koperasi.pembayaran b         on b.pinjaman_id = p.id
left join koperasi.pembayaran_koreksi k on k.pembayaran_id = b.id
group by p.id;

commit;

-- =========================================================================
-- §4  HITUNG DAMPAK — JALANKAN SEBELUM §3 (dan ulangi sesudahnya)
-- =========================================================================
-- Berapa nasabah yang status lunasnya berubah, dan berapa selisih piutang:
--
--   select
--     count(*) filter (where lama <= 0 and baru > 0) as jadi_belum_lunas,
--     sum(baru - lama)                               as tambahan_piutang
--   from (
--     select
--       p.besar_pinjaman
--         - coalesce(sum(b.jumlah) filter (where k.id is null), 0) as lama,
--       coalesce(
--         nullif(p.total_pelunasan, 0),
--         p.besar_pinjaman + round(p.besar_pinjaman * p.jasa_pinjaman / 100.0)
--       )::bigint
--         - coalesce(sum(b.jumlah) filter (where k.id is null), 0) as baru
--     from koperasi.pinjaman p
--     left join koperasi.pembayaran b         on b.pinjaman_id = p.id
--     left join koperasi.pembayaran_koreksi k on k.pembayaran_id = b.id
--     group by p.id
--   ) t;
--
-- Berapa baris yang memakai cadangan (total_pelunasan = 0)? Kalau jumlahnya
-- besar, rumus cadangan itu yang menentukan angka banyak nasabah — periksa
-- beberapa di antaranya secara manual sebelum mempercayainya:
--
--   select count(*) from koperasi.pinjaman where total_pelunasan = 0;
--
-- =========================================================================
-- §5  VERIFIKASI SESUDAH DIJALANKAN
-- =========================================================================
-- Cocokkan satu nasabah dengan angka di Android (contoh dari UAT: Yulmi):
--
--   select p.pinjaman_ke, p.besar_pinjaman, p.total_pelunasan,
--          s.total_dibayar, s.sisa_utang
--   from koperasi.pinjaman p
--   join koperasi.v_pinjaman_saldo s on s.pinjaman_id = p.id
--   join koperasi.nasabah n on n.id = p.nasabah_id
--   where n.legacy_pelanggan_id = '-OtJSUEvO10JfG_y_OLk'
--   order by p.pinjaman_ke;
--
-- Harapan: `sisa_utang` = `total_pelunasan` − `total_dibayar`, dan angkanya
-- sama dengan yang ditampilkan Android untuk nasabah itu.
--
-- =========================================================================
-- §6  MEMBATALKAN
-- =========================================================================
-- Kembalikan rumus lama (hanya bila diputuskan menunda):
--
--   create or replace view koperasi.v_pinjaman_saldo as
--   select p.id as pinjaman_id, p.nasabah_id, p.pinjaman_ke, p.status,
--          p.besar_pinjaman,
--          coalesce(sum(b.jumlah) filter (where k.id is null), 0) as total_dibayar,
--          p.besar_pinjaman
--            - coalesce(sum(b.jumlah) filter (where k.id is null), 0) as sisa_utang
--   from koperasi.pinjaman p
--   left join koperasi.pembayaran b         on b.pinjaman_id = p.id
--   left join koperasi.pembayaran_koreksi k on k.pembayaran_id = b.id
--   group by p.id;
-- =========================================================================
