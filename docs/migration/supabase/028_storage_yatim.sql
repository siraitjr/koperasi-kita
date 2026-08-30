-- =========================================================================
-- KOPERASI KITA — 028: BUCKET ARSIP YATIM
-- Menampung berkas yang tidak punya jembatan pemetaan ke data Supabase.
-- RANCANGAN — BELUM PERNAH DIJALANKAN.
-- =========================================================================
--
-- Prasyarat: 027 sudah dijalankan. Berkas ini IDEMPOTEN.
--
-- LATAR: hasil dry-run migrate_storage.js
--   ktp terpetakan   :   759
--   yatim sejati     : 4.968   ← tidak ditemukan di lapisan mana pun
--   pemohon ditolak  :     8   ← `pelanggan_ditolak`, tidak punya nasabah_id
--   profil yatim     :     2
--
-- Lapisan `pinjaman_history` menghasilkan NOL kecocokan, jadi hipotesis
-- "pushId adalah id pinjaman" tidak terbukti. Yang tersisa memang tidak
-- punya jembatan: kemungkinan besar nasabah lama yang sudah dihapus dari
-- RTDB sebelum ekspor, sementara fotonya tertinggal di Storage.
--
-- KENAPA DIPINDAHKAN, BUKAN DIBUANG
-- -------------------------------------------------------------------------
-- Ini foto KTP. Membuangnya tidak bisa dibatalkan, dan setelah 1 September
-- sumbernya di Firebase ikut hilang. Memindahkannya lebih dulu membuat
-- keputusan "simpan atau hapus" bisa diambil belakangan dengan tenang,
-- bukan di bawah tenggat.
--
-- ⚠ TETAPI INI BUKAN KEADAAN AKHIR YANG SEHAT.
-- 4.968 foto identitas tanpa pemilik yang diketahui adalah tanggungan, bukan
-- aset: tidak bisa dipakai (tak tahu punya siapa), tidak bisa dihapus tanpa
-- diperiksa (mungkin nasabah aktif yang pemetaannya putus), dan tetap tunduk
-- kewajiban perlindungan data. Perlu keputusan pemilik SESUDAH evakuasi:
-- audit sampel untuk memastikan benar-benar nasabah lama, lalu hapus dengan
-- jadwal retensi yang tertulis. Dicatat di sini supaya tidak jadi tumpukan
-- yang terlupakan.
-- =========================================================================

begin;

-- =========================================================================
-- 1. BUCKET
-- =========================================================================
-- Batas ukuran & tipe mengikuti bucket asalnya (027): ktp 5 MB, profil 1 MB.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
  ('ktp-yatim',    'ktp-yatim',    false, 5242880,
     array['image/jpeg','image/png','image/webp']),
  ('profil-yatim', 'profil-yatim', false, 1048576,
     array['image/jpeg','image/png','image/webp'])
on conflict (id) do nothing;

-- =========================================================================
-- 2. POLICY — PENGAWAS SAJA, dan hanya BACA
-- =========================================================================
-- Berbeda dari bucket `ktp` (027 §3), yang policy-nya JOIN ke
-- `koperasi.nasabah` untuk menurunkan kepemilikan dari data. Di sini tidak
-- ada yang bisa di-JOIN — justru ketiadaan tautan itulah definisi "yatim".
--
-- Karena kepemilikannya tidak diketahui, tidak ada dasar untuk memberi akses
-- kepada admin atau pimpinan mana pun: tidak seorang pun bisa dibuktikan
-- berhak atas foto tertentu. Yang tersisa adalah peran yang memang berwenang
-- global untuk audit — Pengawas. Ini keputusan sadar untuk memilih yang
-- paling ketat, bukan yang paling mudah.
--
-- Tidak ada policy INSERT/UPDATE/DELETE untuk klien sama sekali:
--   · pengisian    → hanya service_role (skrip migrasi)
--   · penghapusan  → hanya service_role, dan hanya setelah keputusan retensi
--     tertulis. Menaruh tombol hapus di tangan klien untuk 5.000 foto
--     identitas tanpa pemilik adalah risiko tanpa imbalan.
drop policy if exists yatim_baca on storage.objects;
create policy yatim_baca on storage.objects
  for select to authenticated
  using (
    bucket_id in ('ktp-yatim', 'profil-yatim')
    and koperasi_priv.is_pengawas()
  );

commit;

-- =========================================================================
-- VERIFIKASI
-- =========================================================================
-- 1) Bucket ada dan PRIVATE:
--      select id, public, file_size_limit from storage.buckets
--       where id like '%yatim%';
--
-- 2) Hanya satu policy, hanya SELECT:
--      select policyname, cmd, roles from pg_policies
--       where schemaname = 'storage' and tablename = 'objects'
--         and policyname = 'yatim_baca';
--
-- 3) UJI AKSES SUNGGUHAN lewat REST — SQL Editor memakai service_role yang
--    melewati RLS, jadi menguji di sana tidak membuktikan apa pun:
--      curl -X POST "$SUPA_URL/storage/v1/object/sign/ktp-yatim/<path>" \
--        -H "apikey: $ANON" -H "Authorization: Bearer $JWT_PENGAWAS" \
--        -H "Content-Type: application/json" -d '{"expiresIn":60}'
--      -- 200 untuk Pengawas
--      curl … -H "Authorization: Bearer $JWT_ADMIN"
--      -- HARUS gagal. Baris kedua ini yang membuktikan policy-nya bekerja;
--      --  baris pertama saja tidak membuktikan apa-apa.
--
-- 4) Sesudah unggah, hitung isinya:
--      select bucket_id, count(*) from storage.objects
--       where bucket_id like '%yatim%' group by 1;
--      -- harapan: ktp-yatim ±4.976, profil-yatim 2
-- =========================================================================
--
-- CATATAN RETENSI — belum diputuskan, jangan dilupakan
-- Tidak ada kebijakan retensi untuk kedua bucket ini. 003 §2 memberi `ktp`
-- retensi permanen karena ia bukti identitas nasabah aktif; alasan itu TIDAK
-- berlaku untuk berkas yang pemiliknya tidak diketahui. Tetapkan jadwalnya
-- sesudah evakuasi, dan tuliskan — retensi yang tidak tertulis selalu
-- berujung "simpan selamanya".
-- =========================================================================
