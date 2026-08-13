-- =========================================================================
-- KOPERASI KITA — PATCH SKEMA WAJIB SEBELUM IMPOR
-- Jalankan SETELAH 001_schema_v2.sql, SEBELUM migrate.js --execute.
-- RANCANGAN — BELUM PERNAH DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- Urutan lengkap: 001 → 001a → 002 → 007 → 009
--
-- Isinya menutup selisih antara rancangan 001 dan BENTUK DATA NYATA yang
-- baru terlihat setelah membaca export produksi. Tanpa patch ini, impor
-- ditolak pada data yang sebenarnya sah.
-- Rincian temuan & buktinya ada di 006 §1.2.
-- =========================================================================


-- =========================================================================
-- 0. ENUM BARU — HARUS DI LUAR TRANSAKSI
-- =========================================================================
-- `alter type ... add value` tidak dapat dipakai pada transaksi yang sama
-- dengan pemakaian nilainya. Ditaruh terpisah di paling atas supaya urutan
-- ini tidak bisa keliru.
--
-- Dua nilai ini ditemukan dari dry run, bukan dari dokumentasi:
--   'pelunasan_tabungan' — dry run ke-1, 436 entri
--   'tarik_tabungan'     — dry run ke-2, 15 entri
-- Keduanya TIDAK disebut di komentar functions/jurnalTransaksi.js:14-18.
-- Daftar tipe di sana ternyata tidak lengkap terhadap data yang benar-benar
-- ditulis; sumber kebenarannya adalah data, bukan komentar. Kalau kelak
-- muncul ENUM_TIDAK_DIKENAL lagi, tambahkan di sini dengan pola yang sama.
alter type koperasi.jurnal_tipe add value if not exists 'pelunasan_tabungan';
alter type koperasi.jurnal_tipe add value if not exists 'tarik_tabungan';

-- koperasi.user_role — peran nyata di metadata/admins (distinct dari export):
--   admin, kasir_unit, kasir_wilayah, koordinator, pengawas, pimpinan, sekretaris
-- 001 memuat enam yang pertama kecuali `kasir_wilayah`, yang baru muncul saat
-- --execute ke-2. Hanya nilai ini yang benar-benar kurang.
alter type koperasi.user_role add value if not exists 'kasir_wilayah';

-- CATATAN — 'pdl' dan 'kasir' SENGAJA TIDAK ditambahkan di sini.
-- ---------------------------------------------------------------------------
-- migrate.js meneruskan role APA ADANYA (`role: str(a.role) || 'admin'`);
-- tidak ada pemetaan admin→pdl maupun kasir_unit→kasir di mana pun. Jadi
-- kedua nilai itu tidak akan pernah dipakai baris hasil impor.
--
-- Menambahkannya tidak merusak apa pun, tetapi MEMETAKAN ke sana akan:
--   * mematahkan 7 policy di 002 yang mencocokkan role = 'admin', dan
--   * mematahkan 4 pemeriksaan 'kasir_unit' di 002 + 1 di Edge Function
--     user-management (daftar role yang boleh dibuat).
-- Akibatnya admin lapangan kehilangan akses ke datanya sendiri — dan itu
-- tidak akan tampak sebagai galat, hanya sebagai layar yang kosong.
--
-- Kalau penamaan `pdl`/`kasir` memang diinginkan, itu perubahan terkoordinasi
-- (enum + 002 + Edge Function + CHECK app_user_cabang_wajib), bukan sekadar
-- menambah nilai enum. Nilai 'pdl'/'kasir' yang sudah terlanjur ditambahkan
-- manual boleh dibiarkan — tidak terpakai, tidak berbahaya.


begin;

-- =========================================================================
-- 1. T-1 — generasi pinjaman 0 nyata ada
-- =========================================================================
-- Bukti: riwayat_pinjaman/{admin}/-OjZ8XNws7T8gEW_Y4Dy → generasi ['0','1'].
-- CHECK asli (>= 1) menolak data yang sah.
alter table koperasi.pinjaman drop constraint if exists pinjaman_ke_positif;
alter table koperasi.pinjaman add  constraint pinjaman_ke_positif
  check (pinjaman_ke >= 0);

-- =========================================================================
-- 2. T-2 — arsip generasi bercelah (sparse)
-- =========================================================================
-- Generasi yang benar-benar ada di arsip: ['4'], ['2'], ['7'], ['4','5'] —
-- bukan 1..N rapat. Pemeriksaan "harus tepat +1" akan menolak SELURUH impor.
-- Trigger tetap dipertahankan untuk data BARU, tetapi kini hanya menjaga
-- satu hal: generasi tidak boleh menimpa/mundur dari yang sudah ada.
create or replace function koperasi.tg_pinjaman_generasi_berurutan()
returns trigger language plpgsql as $$
declare v_max integer;
begin
  if new.pinjaman_ke = 0 then return new; end if;
  select max(pinjaman_ke) into v_max
    from koperasi.pinjaman where nasabah_id = new.nasabah_id;
  if v_max is not null and new.pinjaman_ke <= v_max then
    raise exception 'Generasi ke-% sudah/pernah ada untuk nasabah % (tertinggi %)',
      new.pinjaman_ke, new.nasabah_id, v_max using errcode = 'check_violation';
  end if;
  return new;
end; $$;

-- =========================================================================
-- 3. T-3 — `simpanan` adalah skalar, bukan ledger
-- =========================================================================
-- pelangganToMap menulis "simpanan" to pelanggan.simpanan (Int), jadi tabel
-- ledger `simpanan` tidak punya sumber data sama sekali. Dibiarkan ada
-- (dipakai fitur mendatang), tetapi ditandai agar tidak dikira gagal impor.
comment on table koperasi.simpanan is
  'KOSONG setelah migrasi: RTDB menyimpan simpanan sebagai skalar per '
  'pinjaman (pinjaman.simpanan_awal), bukan sebagai ledger.';

-- =========================================================================
-- 4. T-4 — subPembayaran bersarang di dalam tiap pembayaran
-- =========================================================================
alter table koperasi.pembayaran
  add column if not exists parent_pembayaran_id uuid references koperasi.pembayaran(id);
create index if not exists pembayaran_parent_idx
  on koperasi.pembayaran (parent_pembayaran_id) where parent_pembayaran_id is not null;

-- =========================================================================
-- 5. kasir_entry — bentuk nyata
-- =========================================================================
-- Field sebenarnya: arah (masuk/keluar), jenis, jumlah, targetAdminUid,
-- createdByName. Tanpa `arah`, makna saldo kasir terbalik.
alter table koperasi.kasir_entry
  add column if not exists arah text check (arah in ('masuk','keluar')),
  add column if not exists target_admin_id uuid references koperasi.app_user(id),
  add column if not exists dicatat_oleh_nama text not null default '';
alter table koperasi.kasir_entry alter column dicatat_oleh drop not null;
alter table koperasi.kasir_entry alter column client_op_id drop not null;

-- =========================================================================
-- 6. Peran tanpa cabang
-- =========================================================================
-- `sekretaris` nyata ada di metadata/admins dan TIDAK punya cabang; CHECK
-- asli menolaknya.
alter table koperasi.app_user drop constraint if exists app_user_cabang_wajib;
alter table koperasi.app_user add  constraint app_user_cabang_wajib
  check (role in ('pengawas','koordinator','sekretaris') or cabang_id is not null);

-- =========================================================================
-- 7. FK auth.users dilepas SELAMA impor
-- =========================================================================
-- Impor mendahului pembuatan akun Supabase Auth (create_auth_users.js baru
-- dijalankan sesudahnya), jadi FK ini harus dilepas dulu — kalau tidak,
-- impor gagal pada baris app_user pertama.
--
-- ⚠ PASANG KEMBALI setelah create_auth_users.js selesai:
--     alter table koperasi.app_user
--       add constraint app_user_id_fkey foreign key (id) references auth.users(id);
--   Lihat 006 §3.6.
alter table koperasi.app_user drop constraint if exists app_user_id_fkey;
alter table koperasi.app_user add column if not exists legacy_uid text unique;

-- =========================================================================
-- 8. Kolom jejak asal
-- =========================================================================
alter table koperasi.nasabah   add column if not exists legacy_admin_uid text;
alter table koperasi.pengajuan add column if not exists created_at timestamptz;

-- =========================================================================
-- 9. nasabah.nik — UNIQUE diturunkan menjadi index biasa
-- =========================================================================
-- Keputusan pemilik, 12 Agu 2026, setelah pre-check menemukan 75 NIK
-- duplikat (153 baris):
--   * 1 NIK dummy ("0000000000000010") → ditangani nikBersih() jadi NULL
--   * 74 sisanya adalah ORANG YANG SAMA terdaftar di DUA resort berbeda,
--     mis. "RIKI KISWANTO HARAHAP" di Resort Permula Panti dan Resort
--     Anggun Panti. Itu keadaan data nyata di Firebase, bukan cacat impor.
--
-- Pilihannya: menahan migrasi sampai 74 kasus dibereskan manual, atau
-- membawa seluruh baris utuh dan membereskannya belakangan. Dipilih yang
-- kedua supaya migrasi tidak tersandera pekerjaan pembersihan data.
--
-- ⚠ YANG HILANG: jaminan basis data bahwa satu NIK = satu nasabah.
--   Pencegahan duplikat kembali bergantung penuh pada pemeriksaan di
--   aplikasi sebelum registrasi — sama seperti keadaan di RTDB sekarang,
--   jadi ini bukan kemunduran dari sistem berjalan, tetapi juga bukan
--   perbaikan yang tadinya dijanjikan 004 §6.
--
-- ⚠ EFEK KE LAPORAN: selama duplikat ada, satu orang punya DUA baris
--   nasabah dengan riwayat pinjaman terpisah, dan "jumlah nasabah" akan
--   menghitungnya dua kali.
--
-- Daftar lengkapnya ada di migration_report.json → nikPerluCleanup.
-- Setelah dibersihkan, index bisa dinaikkan lagi jadi UNIQUE (lihat 001).
drop index if exists koperasi.nasabah_nik_unik;
create index if not exists nasabah_nik_idx
  on koperasi.nasabah (nik) where nik is not null;

commit;


-- =========================================================================
-- VERIFIKASI CEPAT
-- =========================================================================
-- 1) Nilai enum baru sudah masuk:
--      select enumlabel from pg_enum e
--        join pg_type t on t.oid = e.enumtypid
--       where t.typname = 'jurnal_tipe' order by e.enumsortorder;
--      -- harus memuat 'pelunasan_tabungan' DAN 'tarik_tabungan'
--
-- 1b) Peran lengkap:
--      select enumlabel from pg_enum e
--        join pg_type t on t.oid = e.enumtypid
--       where t.typname = 'user_role' order by e.enumsortorder;
--      -- harus memuat ketujuh peran nyata, termasuk 'kasir_wilayah'
--
--   Cara tercepat memastikan TIDAK ADA yang kurang: jalankan migrate.js
--   dengan --dsn tetapi TANPA --execute? Tidak — pre-check hanya berjalan
--   pada jalur --execute. Jalankan --execute; bila ada enum yang kurang,
--   skrip berhenti di pre-check dan mencetak SQL siap tempel SEBELUM
--   menulis satu baris pun.
--
-- 2) FK auth.users memang sedang LEPAS (wajib sebelum impor):
--      select conname from pg_constraint where conname = 'app_user_id_fkey';
--      -- harus KOSONG sekarang, dan ADA lagi setelah create_auth_users.js
--
-- 3) pinjaman_ke = 0 diterima:
--      select pg_get_constraintdef(oid) from pg_constraint
--       where conname = 'pinjaman_ke_positif';
--      -- harus (pinjaman_ke >= 0)
-- =========================================================================
