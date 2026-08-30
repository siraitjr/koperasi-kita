-- =========================================================================
-- KOPERASI KITA — 029: dokumen_baca bentuk set-based
-- Mencegah regresi 018 kembali lewat pintu belakang.
-- RANCANGAN — BELUM PERNAH DIJALANKAN.
-- =========================================================================
--
-- Prasyarat: 002, 018 BATCH 1 (butuh koperasi_priv.cabang_terlihat_arr).
-- Jalankan SEBELUM migrate_dokumen.js --execute.
--
-- KENAPA SEKARANG, DAN KENAPA INI BUKAN OPTIMASI SPEKULATIF
-- -------------------------------------------------------------------------
-- `dokumen_baca` (002:516-527) memakai bentuk yang persis sama dengan yang
-- 018 buktikan mahal:
--
--     exists (select 1 from koperasi.nasabah n
--              where n.id = dokumen.nasabah_id
--                and (… or koperasi_priv.boleh_lihat_cabang(n.cabang_id)))
--
-- Itu panggilan SECURITY DEFINER per baris. 018 §0 mengukurnya ±520 µs per
-- panggilan — fungsi definer/ber-klausa SET tidak bisa di-inline PostgreSQL,
-- jadi tiap panggilan adalah eksekusi SPI penuh.
--
-- Sampai hari ini itu tidak terasa karena `dokumen` KOSONG. Begitu
-- migrate_dokumen.js mengisi ±6.700 baris, `getBukuPokok` — yang kini
-- membaca `dokumen` untuk URL foto — akan membayar ribuan panggilan itu.
-- Perkiraan kasar: 6.700 × 520 µs ≈ 3,5 detik ditambahkan ke halaman yang
-- baru saja susah payah dibawa dari 43 detik ke 674 ms.
--
-- Mengubahnya SEKARANG risikonya nol: tidak ada satu baris pun yang bisa
-- berubah visibilitasnya, karena belum ada barisnya. Menunggu sampai data
-- masuk berarti mengubah policy pada tabel yang sudah dipakai.
--
-- SEMANTIK TIDAK BERUBAH. `boleh_lihat_cabang(c)` ≡ `c = any(cabang_terlihat_arr())`,
-- sudah diuji diferensial di 018 §4(a) untuk seluruh user × cabang tanpa
-- selisih. Yang berubah hanya bentuk pertanyaannya, bukan jawabannya.
-- =========================================================================

begin;

drop policy if exists dokumen_baca on koperasi.dokumen;

create policy dokumen_baca on koperasi.dokumen
  for select to authenticated
  using (
    uploaded_by = auth.uid()
    or user_id = auth.uid()
    -- `nasabah_terlihat()` (018 §1.2) dikonsumsi sebagai sublink tak
    -- berkorelasi → hashed SubPlan, dibangun SEKALI, lalu satu hash probe
    -- per baris. Bandingkan dengan EXISTS berkorelasi di atas yang
    -- memanggil fungsi definer untuk SETIAP baris dokumen.
    or nasabah_id in (select koperasi_priv.nasabah_terlihat())
  );

-- `dokumen_tulis` (002:529) TIDAK disentuh: ia `uploaded_by = auth.uid()`,
-- predikat kolom polos tanpa panggilan fungsi apa pun, dan jalur tulis
-- memang satu baris per permintaan.

commit;

-- =========================================================================
-- VERIFIKASI
-- =========================================================================
-- 1) Policy terpasang dan hanya satu:
--      select policyname, cmd from pg_policies
--       where schemaname='koperasi' and tablename='dokumen' order by 1;
--      -- harapan: dokumen_baca (SELECT), dokumen_tulis (INSERT)
--
-- 2) SESUDAH migrate_dokumen.js --execute, ukur dengan simulasi peran —
--    bukan di SQL Editor apa adanya (service_role melewati RLS):
--
--      begin;
--        set local role authenticated;
--        set local request.jwt.claim.sub = '<UID_ADMIN>';
--        set local request.jwt.claims    = '{"sub":"<UID_ADMIN>","role":"authenticated"}';
--        explain (analyze, buffers)
--        select nasabah_id, jenis, object_path from koperasi.dokumen;
--      rollback;
--
--    Yang dibaca: cari `hashed SubPlan`. Kalau tertulis `SubPlan` TANPA
--    `hashed`, atau muncul `Function Scan on boleh_lihat_cabang` dengan
--    loops ribuan, bentuk lamanya masih terpasang.
--
-- 3) SEMANTIK — jumlah baris yang terlihat per peran harus masuk akal:
--      -- sebagai admin: hanya dokumen nasabah miliknya
--      -- sebagai pimpinan: hanya cabangnya
--    Kalau admin melihat dokumen nasabah admin lain, itu RLS yang salah,
--    bukan tampilan.
-- =========================================================================
