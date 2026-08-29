-- =========================================================================
-- KOPERASI KITA — 027: BUCKET & POLICY STORAGE yang belum terpasang
-- Melengkapi 003_storage_design.md §3.1, §3.2, §3.4.
-- RANCANGAN — BELUM PERNAH DIJALANKAN.
-- =========================================================================
--
-- SUDAH TERPASANG, tidak diulang di sini:
--   bucket `nota-kasir`, policy `dok_cabang_baca`, policy `nota_kasir_tulis`
--
-- Berkas ini IDEMPOTEN: bucket memakai `on conflict do nothing`, policy
-- memakai `drop policy if exists` lebih dulu. Aman dijalankan berulang, dan
-- aman dijalankan walau sebagian sudah ada.
--
-- Prasyarat: 002 (butuh koperasi_priv.boleh_lihat_cabang & is_pengawas),
--            018 B-1 (bentuk set-based cabang_terlihat_arr).
-- =========================================================================

begin;

-- =========================================================================
-- 1. HELPER — nasabah_id dari segmen pertama path
-- =========================================================================
-- 003 §3 mendefinisikannya, tetapi ia BELUM tentu terpasang: tiga policy yang
-- sudah ada (`dok_cabang_baca`, `nota_kasir_tulis`) tidak memakainya sama
-- sekali, jadi ketiadaannya tidak pernah terasa sampai sekarang.
--
-- `nullif(...)::uuid` sengaja: segmen pertama yang bukan uuid akan melempar
-- 22P02 saat policy dievaluasi, dan itu MENGGAGALKAN pembacaan alih-alih
-- membocorkan objeknya. Gagal-tutup.
create or replace function koperasi_priv.path_nasabah_id(p_name text)
returns uuid
language sql
immutable
parallel safe
set search_path = ''
as $$
  select nullif((string_to_array(p_name, '/'))[1], '')::uuid
$$;

revoke all on function koperasi_priv.path_nasabah_id(text) from public, anon;
grant execute on function koperasi_priv.path_nasabah_id(text) to authenticated;

-- =========================================================================
-- 2. BUCKET
-- =========================================================================
-- Semua PRIVATE (003 §2). `getDownloadURL()` Firebase menghasilkan token yang
-- berlaku selamanya — URL yang bocor dari screenshot tetap membuka foto KTP.
-- Signed URL berumur pendek menutup itu.
--
-- Batas ukuran diambil persis dari `rulesstorage.txt` supaya perilaku
-- kompresi di klien (ImagePreprocessing_Enhanced.kt) tidak perlu berubah.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
  ('ktp',         'ktp',         false, 5242880,
     array['image/jpeg','image/png']),
  ('ktp-pending', 'ktp-pending', false, 5242880,
     array['image/jpeg','image/png','image/webp']),
  ('profil',      'profil',      false, 1048576,
     array['image/jpeg','image/png','image/webp'])
on conflict (id) do nothing;

-- Bucket yang dirancang 003 tetapi belum ada isinya sekarang
-- (`serah-terima`, `bukti-bayar`) SENGAJA tidak dibuat di sini: tidak ada
-- berkas untuk dipindahkan, dan bucket kosong tanpa policy tulis hanya jadi
-- kebingungan saat audit. Buat saat fiturnya benar-benar dipakai.

-- =========================================================================
-- 3. POLICY — `ktp` (003 §3.1), paling ketat
-- =========================================================================
drop policy if exists ktp_baca on storage.objects;
create policy ktp_baca on storage.objects
  for select to authenticated
  using (
    bucket_id = 'ktp'
    and exists (
      select 1 from koperasi.nasabah n
       where n.id = koperasi_priv.path_nasabah_id(name)
         and (n.admin_id = auth.uid()
              or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
    )
  );

drop policy if exists ktp_tulis on storage.objects;
create policy ktp_tulis on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'ktp'
    and exists (
      select 1 from koperasi.nasabah n
       where n.id = koperasi_priv.path_nasabah_id(name)
         and n.admin_id = auth.uid()
    )
  );

-- Menutup T-3: di rulesstorage.txt, delete terbuka untuk SEMUA user login.
drop policy if exists ktp_hapus on storage.objects;
create policy ktp_hapus on storage.objects
  for delete to authenticated
  using (bucket_id = 'ktp' and koperasi_priv.is_pengawas());

-- Tidak ada policy UPDATE, dan itu disengaja (003 §3.1): foto KTP tidak bisa
-- ditimpa diam-diam. Ganti foto = objek baru + catat di koperasi.dokumen;
-- versi lama tetap ada sebagai bukti.
--
-- ⚠ Konsekuensi untuk skrip migrasi: unggahan HARUS `upsert: false`.
--   Dengan service_role RLS memang dilewati, tetapi memakai upsert akan
--   menimpa objek yang sudah dipindahkan pada jalan-ulang — dan itu
--   membatalkan sifat idempoten yang justru diandalkan.

-- =========================================================================
-- 4. POLICY — `ktp-pending` (003 §3.2)
-- =========================================================================
-- rulesstorage.txt:31 memberi `allow read: if request.auth != null` — SEMUA
-- user terautentikasi, lintas cabang, karena approver perlu melihat foto.
-- Kebutuhan itu tetap terpenuhi lewat boleh_lihat_cabang() tanpa membuka ke
-- seluruh organisasi.
drop policy if exists ktp_pending_baca on storage.objects;
create policy ktp_pending_baca on storage.objects
  for select to authenticated
  using (
    bucket_id = 'ktp-pending'
    and exists (
      select 1 from koperasi.nasabah n
       where n.id = koperasi_priv.path_nasabah_id(name)
         and (n.admin_id = auth.uid()
              or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
    )
  );

drop policy if exists ktp_pending_tulis on storage.objects;
create policy ktp_pending_tulis on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'ktp-pending'
    and exists (
      select 1 from koperasi.nasabah n
       where n.id = koperasi_priv.path_nasabah_id(name)
         and n.admin_id = auth.uid()
    )
  );

-- Pending BOLEH dihapus — retensinya 90 hari setelah keputusan (003 §2), jadi
-- pembersihan berkala harus mungkin. Dibatasi Pengawas, sama seperti `ktp`.
drop policy if exists ktp_pending_hapus on storage.objects;
create policy ktp_pending_hapus on storage.objects
  for delete to authenticated
  using (bucket_id = 'ktp-pending' and koperasi_priv.is_pengawas());

-- =========================================================================
-- 5. POLICY — `profil` (003 §3.4)
-- =========================================================================
-- Setara rulesstorage.txt:50-57: foto profil terlihat semua staf (dipakai di
-- daftar admin, approval, serah terima), tetapi hanya pemiliknya yang menulis.
drop policy if exists profil_baca on storage.objects;
create policy profil_baca on storage.objects
  for select to authenticated
  using (bucket_id = 'profil');

drop policy if exists profil_tulis on storage.objects;
create policy profil_tulis on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'profil'
    and (string_to_array(name, '/'))[1] = auth.uid()::text
  );

-- Foto profil boleh diganti — beda dari KTP, ia bukan bukti apa pun.
drop policy if exists profil_ubah on storage.objects;
create policy profil_ubah on storage.objects
  for update to authenticated
  using (
    bucket_id = 'profil'
    and (string_to_array(name, '/'))[1] = auth.uid()::text
  );

commit;

-- =========================================================================
-- VERIFIKASI
-- =========================================================================
-- 1) Bucket ada dan PRIVATE:
--      select id, public, file_size_limit, allowed_mime_types
--        from storage.buckets order by id;
--      -- ktp / ktp-pending / nota-kasir / profil, public = false semuanya
--
-- 2) Policy terpasang:
--      select policyname, cmd from pg_policies
--       where schemaname = 'storage' and tablename = 'objects'
--       order by policyname;
--      -- harapan: dok_cabang_baca, ktp_baca, ktp_hapus, ktp_pending_baca,
--      --          ktp_pending_hapus, ktp_pending_tulis, ktp_tulis,
--      --          nota_kasir_tulis, profil_baca, profil_tulis, profil_ubah
--
-- 3) Helper bekerja:
--      select koperasi_priv.path_nasabah_id(
--        '11111111-1111-1111-1111-111111111111/ktp.jpg');
--
-- 4) UJI RLS SUNGGUHAN — lewat REST dengan JWT admin lapangan, BUKAN SQL
--    Editor (service_role melewati RLS sehingga selalu tampak benar):
--      curl "$SUPA_URL/storage/v1/object/sign/ktp/<nasabah_id>/ktp.jpg" \
--        -X POST -H "apikey: $ANON" -H "Authorization: Bearer $JWT_ADMIN" \
--        -H "Content-Type: application/json" -d '{"expiresIn":60}'
--    Harapan: 200 untuk nasabah MILIKNYA, 400/404 untuk nasabah admin lain.
--    Baris kedua itu yang membuktikan policy-nya bekerja; baris pertama saja
--    tidak membuktikan apa-apa.
-- =========================================================================
