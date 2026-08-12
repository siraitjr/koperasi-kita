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
-- 2) FK auth.users memang sedang LEPAS (wajib sebelum impor):
--      select conname from pg_constraint where conname = 'app_user_id_fkey';
--      -- harus KOSONG sekarang, dan ADA lagi setelah create_auth_users.js
--
-- 3) pinjaman_ke = 0 diterima:
--      select pg_get_constraintdef(oid) from pg_constraint
--       where conname = 'pinjaman_ke_positif';
--      -- harus (pinjaman_ke >= 0)
-- =========================================================================
