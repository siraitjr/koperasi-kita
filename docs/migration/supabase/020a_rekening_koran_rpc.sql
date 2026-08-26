-- =========================================================================
-- KOPERASI KITA — 020a: RPC REKENING KORAN (sumber data B-5)
-- RANCANGAN — BELUM PERNAH DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- Prasyarat: 001 → 001a → 002 → 007 → 015 (B-1..B-4) → 017 B-1 → 018 B-1
--            → 016a → 019
--
-- Dipanggil HANYA oleh Edge Function `rekening-koran` dengan service_role,
-- SESUDAH tanda tangan HMAC-nya sah. Fungsi ini sendiri tidak memeriksa
-- tanda tangan — itu tugas Edge Function.
--
-- =========================================================================
-- KENAPA RPC, BUKAN QUERY DI DALAM EDGE FUNCTION
-- =========================================================================
-- Ini satu-satunya jalur yang mengirim data nasabah ke pihak TANPA LOGIN.
-- Membiarkan Edge Function menyusun sendiri query-nya berarti daftar kolom
-- yang boleh keluar tersebar di TypeScript, dan bertambah satu kolom cukup
-- dengan satu baris `select` yang lolos review.
--
-- Dengan RPC, daftar itu ada di SATU tempat, di bawah kendali migrasi SQL,
-- dan Edge Function secara struktural TIDAK BISA meminta lebih. `service_role`
-- memang mem-bypass RLS — justru karena itu batasnya harus di sini.
--
-- =========================================================================
-- KOLOM YANG SENGAJA TIDAK DIKIRIM
-- =========================================================================
-- 014 §3.1 menuntut pembatasan kolom untuk halaman publik. Diperiksa apa
-- yang benar-benar dipakai `public/rk.html` (satu-satunya konsumen):
--
--   dipakai : nama, nomorAnggota, nik, pinjamanKe, tanggalDaftar, tenor,
--             wilayah, isLunas, besarPinjaman, totalPelunasan, totalDibayar,
--             sisaHutang, sisaTenor, simpanan, riwayatPembayaran,
--             referensiCicilan, generatedAt
--   TIDAK   : alamat, hari, status
--
-- `rekeningKoranService.js:151-155` mengirim `alamat`, `hari`, dan `status`,
-- padahal halamannya tidak pernah menampilkannya. Ketiganya DIHAPUS di sini:
-- alamat rumah nasabah terkirim ke pihak tak berlogin tanpa ada yang
-- membacanya. Menghapusnya tidak mengubah tampilan sama sekali.
--
-- `nik` DIPAKAI halaman, jadi tidak bisa dihapus — tetapi DISAMARKAN jadi
-- 4 digit terakhir. Halaman tetap menampilkan baris NIK, hanya isinya tidak
-- lagi utuh. Perilaku ini bisa dimatikan lewat p_mask_nik := false kalau
-- pemilik memutuskan lain, TETAPI baca dulu 020 §6 sebelum melakukannya.
--
-- Catatan kecil yang perlu diluruskan: komentar
-- `RekeningKoranHelper.kt:28` berbunyi "Data sensitif (NIK) tidak
-- ditampilkan di rekening koran". Itu TIDAK BENAR sejak
-- `rekeningKoranService.js:141` menambahkan `nik`. Komentarnya usang, dan
-- selama ini menutupi kenyataan bahwa NIK memang terkirim.
-- =========================================================================

begin;

create or replace function koperasi.rpc_rekening_koran(
  p_legacy_admin_uid    text,
  p_legacy_pelanggan_id text,
  p_mask_nik            boolean default true
) returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
with n as (
  -- Dicocokkan dengan KEDUA id warisan, bukan hanya pelanggan_id (yang
  -- sudah UNIQUE sendiri). Token membawa keduanya; memakai keduanya berarti
  -- token yang adminUid-nya diubah tidak lagi menemukan baris apa pun.
  select *
    from koperasi.nasabah
   where legacy_admin_uid    = p_legacy_admin_uid
     and legacy_pelanggan_id = p_legacy_pelanggan_id
     and arsip_at is null
   limit 1
),
-- Generasi BERJALAN saja. Rekening koran adalah lembar pinjaman yang
-- sedang jalan; generasi lama punya lembarnya sendiri.
p as (
  select pj.*
    from koperasi.pinjaman pj join n on n.id = pj.nasabah_id
   order by pj.pinjaman_ke desc
   limit 1
),
-- Pembayaran yang SUDAH DIKOREKSI tidak dihitung — sama seperti
-- v_pinjaman_saldo (001:542): pembatalan berupa baris koreksi, bukan hapus.
bayar as (
  select b.*
    from koperasi.pembayaran b
    join p on p.id = b.pinjaman_id
   where not exists (
     select 1 from koperasi.pembayaran_koreksi k where k.pembayaran_id = b.id
   )
),
induk as (
  select b.*, row_number() over (order by b.tanggal, b.id) as no
    from bayar b
   where b.parent_pembayaran_id is null
),
anak as (
  select a.parent_pembayaran_id,
         jsonb_agg(jsonb_build_object(
           'tanggal',    to_char(a.tanggal, 'DD Mon YYYY'),
           'jumlah',     a.jumlah,
           'keterangan', coalesce(nullif(a.keterangan, ''), 'Tambah Bayar')
         ) order by a.tanggal, a.id) as items
    from bayar a
   where a.parent_pembayaran_id is not null
   group by a.parent_pembayaran_id
),
total as (
  -- Induk DAN anak dijumlahkan, persis rekeningKoranService.js:88+97.
  select coalesce(sum(b.jumlah), 0)::bigint as total_dibayar,
         (select count(*) from induk)::int  as banyak_induk
    from bayar b
)
select case when (select count(*) from n) = 0 then null else
  jsonb_build_object(
    'nama',           coalesce(nullif((select nama_panggilan from n), ''),
                               (select nama_ktp from n), '-'),
    -- Disamarkan: hanya 4 digit terakhir. NIK utuh tidak pernah punya alasan
    -- meninggalkan server lewat jalur tanpa login.
    'nik',            case
                        when (select nik from n) is null
                          or length((select nik from n)) < 4 then '-'
                        when p_mask_nik then
                          repeat('•', greatest(length((select nik from n)) - 4, 0))
                          || right((select nik from n), 4)
                        else (select nik from n)
                      end,
    'nomorAnggota',   coalesce((select nomor_anggota from n), '-'),
    'wilayah',        coalesce(nullif((select wilayah from n), ''), '-'),

    'besarPinjaman',  coalesce((select besar_pinjaman  from p), 0),
    'totalPelunasan', coalesce((select total_pelunasan from p), 0),
    'tanggalDaftar',  coalesce(to_char((select tanggal_daftar from p), 'DD Mon YYYY'), '-'),
    'tenor',          coalesce((select tenor from p), 0),
    'pinjamanKe',     coalesce((select pinjaman_ke from p), 1),

    'simpanan',       coalesce((select sum(s.jumlah) from koperasi.simpanan s
                                 join n on n.id = s.nasabah_id), 0),

    'totalDibayar',   (select total_dibayar from total),
    'sisaHutang',     greatest(coalesce((select total_pelunasan from p), 0)
                               - (select total_dibayar from total), 0),
    'isLunas',        (coalesce((select total_pelunasan from p), 0)
                        - (select total_dibayar from total)) <= 0
                      and coalesce((select total_pelunasan from p), 0) > 0,
    'sisaTenor',      greatest(coalesce((select tenor from p), 0)
                               - (select banyak_induk from total), 0),

    'riwayatPembayaran', coalesce((
      select jsonb_agg(jsonb_build_object(
               'no',            i.no,
               'tanggal',       to_char(i.tanggal, 'DD Mon YYYY'),
               'jumlah',        i.jumlah,
               'subPembayaran', coalesce(a.items, '[]'::jsonb)
             ) order by i.no)
        from induk i left join anak a on a.parent_pembayaran_id = i.id
    ), '[]'::jsonb),

    'referensiCicilan', coalesce((
      select jsonb_agg(jsonb_build_object(
               'no',          j.urutan,
               'tanggal',     to_char(j.tanggal, 'DD Mon YYYY'),
               'jumlah',      j.jumlah,
               'isCompleted', j.is_completed
             ) order by j.urutan)
        from koperasi.jadwal_cicilan j join p on p.id = j.pinjaman_id
    ), '[]'::jsonb),

    'generatedAt', to_char(now() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
  )
end
$$;

-- -------------------------------------------------------------------------
-- HAK AKSES — paling ketat dari seluruh RPC di repo ini
-- -------------------------------------------------------------------------
-- HANYA service_role. Bukan `authenticated`, dan sudah pasti bukan `anon`.
-- Kalau `anon` bisa memanggilnya, seluruh gunanya HMAC lenyap: siapa pun
-- cukup menebak/mengumpulkan legacy id dan memanggil RPC langsung lewat
-- PostgREST tanpa tanda tangan apa pun.
revoke all on function koperasi.rpc_rekening_koran(text, text, boolean)
  from public, anon, authenticated;
grant execute on function koperasi.rpc_rekening_koran(text, text, boolean)
  to service_role;

commit;

-- =========================================================================
-- VERIFIKASI
-- =========================================================================
-- 1) Hak akses persis seperti di atas — `anon` dan `authenticated` HARUS
--    tidak muncul:
--      select r.rolname, has_function_privilege(
--               r.rolname,
--               'koperasi.rpc_rekening_koran(text,text,boolean)', 'execute')
--        from pg_roles r
--       where r.rolname in ('anon','authenticated','service_role');
--      -- harapan: anon=false, authenticated=false, service_role=true
--
-- 2) Isi keluaran untuk satu nasabah nyata (jalankan sebagai service_role):
--      select jsonb_pretty(koperasi.rpc_rekening_koran('<adminUid>','<pelangganId>'));
--
--    Yang diperiksa:
--      * `nik` tersamar (hanya 4 digit terakhir terbaca);
--      * TIDAK ada kunci `alamat`, `hari`, `status`;
--      * `totalDibayar` = jumlah induk + anak;
--      * `sisaTenor` = tenor − banyak pembayaran induk.
--
-- 3) Id yang tidak ada → NULL (bukan galat), supaya Edge Function bisa
--    menjawab 404 tanpa membedakan "tidak ada" dari "tidak berhak":
--      select koperasi.rpc_rekening_koran('tidak','ada') is null;  -- t
--
-- 4) Bandingkan dengan endpoint lama untuk nasabah yang sama. Angka
--    besarPinjaman/totalPelunasan/totalDibayar/sisaHutang HARUS sama.
--    Kalau berbeda, itu temuan kesetiaan migrasi — bukan urusan B-5, tetapi
--    jangan diteruskan ke cut-over sebelum dijelaskan.
-- =========================================================================
