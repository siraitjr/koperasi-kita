-- =========================================================================
-- KOPERASI KITA — TAHAP B: VIEW + RLS PENGGANTI ENDPOINT WEB
-- Rancangan berdasarkan 014. BELUM PERNAH DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- Prasyarat: 001 → 001a → 002 → 007 → 009 → 011 sudah terpasang.
-- Khusus BATCH B-4: `016a_operasional_harian.sql` harus dijalankan lebih
-- dulu dan datanya sudah diimpor — rpc_sync_operasional_transport membaca
-- tabel itu. Lihat CATATAN §B-4a dan 016_operasional_harian.md.
--
-- BERKAS INI DIRANCANG UNTUK DIJALANKAN BATCH PER BATCH.
-- Tiap batch dipisah kepala komentar besar. Jalankan SATU batch, periksa
-- hasilnya, baru lanjut. Jangan tempel seluruh berkas sekaligus.
--
--   BATCH B-1   fondasi          v_pembayaran_harian, v_buku_pokok
--   BATCH B-2   turunan          v_buku_pokok_summary, v_pembayaran_hari_ini
--   BATCH B-3   berdiri sendiri  kasir (+policy ketat), jurnal, koreksi
--   BATCH VERIF pemeriksaan      security_invoker + explain analyze
--   BATCH B-4   tulis            4 RPC SECURITY DEFINER
--
-- ⚠ SETIAP VIEW WAJIB `with (security_invoker = on)`.
-- Tanpa itu view berjalan dengan hak PEMILIKNYA dan MEM-BYPASS RLS tabel di
-- bawahnya — seluruh kerja 002 batal, dan gagalnya SENYAP: tidak ada galat,
-- hanya admin lapangan yang tiba-tiba melihat nasabah cabang lain.
-- Lihat 014 §0.
-- =========================================================================


-- #########################################################################
-- #                                                                       #
-- #   BATCH B-1 — FONDASI                                                 #
-- #   v_pembayaran_harian, v_buku_pokok                                   #
-- #   Tidak bergantung pada view lain. Jalankan lebih dulu.               #
-- #                                                                       #
-- #########################################################################

begin;

-- Urutan drop = kebalikan ketergantungan, supaya batch ini aman diulang.
drop view if exists koperasi.v_buku_pokok_summary;
drop view if exists koperasi.v_pembayaran_hari_ini;
drop view if exists koperasi.v_buku_pokok;
drop view if exists koperasi.v_pembayaran_harian;

-- -------------------------------------------------------------------------
-- v_pembayaran_harian — pembayaran per (pinjaman, tanggal, jenis)
-- -------------------------------------------------------------------------
-- Menggantikan extractPembayaranPerTanggal() (bukuPokokApi.js:181).
-- Kolom storting per tanggal (PB/L1/CM/MB/ML) SENGAJA tidak di-pivot di SQL:
-- jumlah kolomnya bergantung rentang tanggal yang diminta, jadi pivot di SQL
-- menuntut SQL dinamis yang mahal dan rapuh. Web menyusunnya jadi kolom,
-- persis seperti yang sudah dilakukannya sekarang.
create view koperasi.v_pembayaran_harian
with (security_invoker = on) as
select
  b.pinjaman_id,
  p.nasabah_id,
  n.cabang_id,
  n.admin_id,
  b.tanggal,
  b.jenis,
  sum(b.jumlah)  as jumlah,
  count(*)       as banyak_transaksi
from koperasi.pembayaran b
join koperasi.pinjaman p on p.id = b.pinjaman_id
join koperasi.nasabah  n on n.id = p.nasabah_id
-- Pembayaran yang sudah dikoreksi tidak ikut dihitung. Tabel koreksi ada
-- karena `pembayaran` append-only (001 §4) — pembatalan berupa baris
-- pembalik, bukan penghapusan.
left join koperasi.pembayaran_koreksi k on k.pembayaran_id = b.id
where k.id is null
group by b.pinjaman_id, p.nasabah_id, n.cabang_id, n.admin_id, b.tanggal, b.jenis;

-- -------------------------------------------------------------------------
-- v_buku_pokok — satu baris per GENERASI pinjaman
-- -------------------------------------------------------------------------
-- Menggantikan inti getBukuPokok (bukuPokokApi.js:327-1000, ±850 baris).
-- Sebagian besar berkas itu bukan logika bisnis melainkan siasat terhadap
-- bentuk RTDB: menelusuri riwayat_pinjaman untuk generasi lama, meratakan
-- array pembayaranList bercelah, dan memindahkan pelunasan top-up ke baris
-- historis (:551). Ketiganya lenyap di sini karena generasi SUDAH berupa
-- baris (001 §3) dan pembayaran sudah berupa tabel.
create view koperasi.v_buku_pokok
with (security_invoker = on) as
select
  n.id                as nasabah_id,
  n.cabang_id,
  n.admin_id,
  u.nama              as admin_nama,
  n.nomor_anggota,
  n.nama_ktp,
  n.nama_panggilan,
  n.wilayah,
  n.status_khusus,
  p.id                as pinjaman_id,
  p.pinjaman_ke,
  p.status,
  p.besar_pinjaman,
  p.total_pelunasan,
  p.total_diterima,
  p.biaya_admin,
  p.simpanan_awal,
  p.tarik_tabungan,
  p.tenor,
  p.jasa_pinjaman,
  p.tanggal_pencairan,
  p.tanggal_daftar,
  p.tanggal_lunas_cicilan,
  s.total_dibayar,
  s.sisa_utang,

  -- Baris HISTORIS = generasi yang BUKAN tertinggi milik nasabah ini.
  -- Menggantikan seluruh blok relokasi pelunasan top-up: di sini cukup
  -- perbandingan nomor generasi.
  (p.pinjaman_ke < max(p.pinjaman_ke) over (partition by n.id)) as is_historis,

  -- Turunan status; cermin bukuPokokApi.js:61-63.
  (n.status_khusus = 'MENUNGGU_PENCAIRAN')                      as is_sisa_tabungan,
  (n.status_khusus <> 'MENUNGGU_PENCAIRAN'
     and s.sisa_utang <= 0 and p.total_pelunasan > 0)           as is_lunas,
  (n.status_khusus <> 'MENUNGGU_PENCAIRAN'
     and not (s.sisa_utang <= 0 and p.total_pelunasan > 0)
     and p.status in ('Aktif','Disetujui'))                     as is_aktif
from koperasi.nasabah n
join koperasi.pinjaman p         on p.nasabah_id = n.id
join koperasi.v_pinjaman_saldo s on s.pinjaman_id = p.id
left join koperasi.app_user u    on u.id = n.admin_id
-- Nasabah yang diarsipkan (padanan removeValue RTDB, 007 §1) tidak tampil.
where n.arsip_at is null;

grant select on koperasi.v_pembayaran_harian, koperasi.v_buku_pokok
  to authenticated;

commit;

-- Tidak perlu policy baru: security_invoker membuat kedua view tunduk pada
-- `nasabah_baca` (002 §4) dan `pembayaran_baca` (002 §6) yang sudah ada.
-- Admin melihat nasabahnya sendiri; atasan melihat cabangnya.


-- #########################################################################
-- #                                                                       #
-- #   BATCH B-2 — TURUNAN                                                 #
-- #   Bergantung pada B-1. Jangan jalankan sebelum B-1 sukses.            #
-- #                                                                       #
-- #########################################################################

begin;

drop view if exists koperasi.v_buku_pokok_summary;
drop view if exists koperasi.v_pembayaran_hari_ini;

-- Menggantikan getBukuPokokSummary. `where not is_historis` penting:
-- tanpanya generasi lama ikut terhitung dan angkanya menggelembung.
create view koperasi.v_buku_pokok_summary
with (security_invoker = on) as
select
  cabang_id,
  count(*) filter (where is_aktif)             as nasabah_aktif,
  count(*) filter (where is_lunas)             as nasabah_lunas,
  count(*) filter (where is_sisa_tabungan)     as nasabah_sisa_tabungan,
  count(*)                                     as total_baris,
  coalesce(sum(besar_pinjaman) filter (where is_aktif), 0) as total_pinjaman_aktif,
  coalesce(sum(sisa_utang)     filter (where is_aktif), 0) as total_piutang,
  coalesce(sum(total_dibayar), 0)                          as total_dibayar
from koperasi.v_buku_pokok
where not is_historis
group by cabang_id;

-- Menggantikan getPembayaranHariIni.
-- Zona waktu DITULIS EKSPLISIT. getTodayIndonesia() (bukuPokokApi.js:236)
-- ada justru karena ini pernah salah; `current_date` server bukan WIB.
create view koperasi.v_pembayaran_hari_ini
with (security_invoker = on) as
select
  cabang_id,
  admin_id,
  tanggal,
  sum(jumlah)            as total,
  sum(banyak_transaksi)  as banyak
from koperasi.v_pembayaran_harian
where tanggal = (now() at time zone 'Asia/Jakarta')::date
group by cabang_id, admin_id, tanggal;

grant select on koperasi.v_buku_pokok_summary, koperasi.v_pembayaran_hari_ini
  to authenticated;

commit;


-- #########################################################################
-- #                                                                       #
-- #   BATCH B-3 — BERDIRI SENDIRI                                         #
-- #   Kasir (+ policy ketat), jurnal, koreksi storting.                   #
-- #   Tidak bergantung pada B-1/B-2.                                      #
-- #                                                                       #
-- #########################################################################

begin;

-- -------------------------------------------------------------------------
-- B-3.0  Kolom soft delete kasir — HARUS SEBELUM view dibuat
-- -------------------------------------------------------------------------
-- Keputusan pemilik: deleteKasirEntry memakai SOFT DELETE, sejalan 007.
-- Kolomnya ditambahkan DI SINI, bukan di B-4 bersama RPC-nya, karena
-- v_kasir_entry di bawah harus bisa menyaring baris terhapus. Kalau
-- kolomnya baru ada di B-4, view-nya terlanjur dibuat tanpa filter dan
-- entri terhapus akan tetap tampil.
alter table koperasi.kasir_entry
  add column if not exists dihapus_at    timestamptz,
  add column if not exists dihapus_oleh  uuid references koperasi.app_user(id),
  add column if not exists alasan_hapus  text not null default '';

create index if not exists kasir_entry_aktif_idx
  on koperasi.kasir_entry (cabang_id, periode) where dihapus_at is null;

-- -------------------------------------------------------------------------
-- B-3.1  Policy kasir KETAT (keputusan pemilik)
-- -------------------------------------------------------------------------
-- 002 §9 `kasir_baca` mengizinkan SEMUA pengguna terautentikasi. Itu lebih
-- longgar daripada sistem berjalan: kasirApi.js:188 membatasi ke enam peran
-- dan MENOLAK `admin`.
--
--   const KASIR_ALLOWED_ROLES = ['kasir_unit','kasir_wilayah','sekretaris',
--                                'pimpinan','koordinator','pengawas'];
--
-- Diganti mengikuti perilaku berjalan — yang lebih ketat.
-- Catatan: daftar ini juga bukti bahwa `kasir_wilayah` dan `sekretaris`
-- adalah peran nyata yang dipakai sebagai gate akses (menutup R-03 di 005).
drop policy if exists kasir_baca on koperasi.kasir_entry;

create policy kasir_baca on koperasi.kasir_entry
  for select to authenticated
  using (
    koperasi_priv.role() in (
      'kasir_unit','kasir_wilayah','sekretaris','pimpinan','koordinator','pengawas'
    )
  );

-- -------------------------------------------------------------------------
-- B-3.2  View kasir
-- -------------------------------------------------------------------------
drop view if exists koperasi.v_kasir_summary;
drop view if exists koperasi.v_kasir_entry;

create view koperasi.v_kasir_entry
with (security_invoker = on) as
select
  k.id, k.cabang_id, k.periode, k.tanggal, k.jenis, k.arah,
  k.nominal, k.keterangan, k.nota_path,
  k.target_admin_id, t.nama as target_admin_nama,
  k.dicatat_oleh,   d.nama as dicatat_oleh_nama_user,
  k.dicatat_oleh_nama,
  k.client_op_id, k.created_at
from koperasi.kasir_entry k
left join koperasi.app_user t on t.id = k.target_admin_id
left join koperasi.app_user d on d.id = k.dicatat_oleh
where k.dihapus_at is null;

-- `arah` inilah kolom yang tidak ada di 001 asli dan ditambahkan 001a §5.
-- Tanpanya tanda saldo terbalik — masuk dan keluar tidak terbedakan.
create view koperasi.v_kasir_summary
with (security_invoker = on) as
select
  cabang_id,
  periode,
  jenis,
  coalesce(sum(nominal) filter (where arah = 'masuk'),  0) as masuk,
  coalesce(sum(nominal) filter (where arah = 'keluar'), 0) as keluar,
  coalesce(sum(case when arah = 'masuk' then nominal else -nominal end), 0) as saldo,
  count(*) as banyak_entri
from koperasi.kasir_entry
where dihapus_at is null
group by cabang_id, periode, jenis;

-- -------------------------------------------------------------------------
-- B-3.3  Jurnal & koreksi storting
-- -------------------------------------------------------------------------
drop view if exists koperasi.v_jurnal_transaksi;
drop view if exists koperasi.v_koreksi_storting;

create view koperasi.v_jurnal_transaksi
with (security_invoker = on) as
select
  j.id, j.cabang_id, j.tipe, j.tanggal, j.jumlah,
  j.nasabah_id, n.nama_ktp as nasabah_nama_ktp, n.nama_panggilan as nasabah_nama_panggilan,
  j.pinjaman_id, j.pinjaman_ke,
  j.admin_id, coalesce(u.nama, j.admin_name) as admin_nama,
  j.nama_pelanggan, j.nama_ktp,
  j.sisa_utang_setelah, j.total_pelunasan, j.total_dibayar,
  j.keterangan, j.created_at
from koperasi.jurnal_transaksi j
left join koperasi.nasabah  n on n.id = j.nasabah_id
left join koperasi.app_user u on u.id = j.admin_id;

create view koperasi.v_koreksi_storting
with (security_invoker = on) as
select
  k.cabang_id, k.admin_id, u.nama as admin_nama,
  k.periode, k.cm, k.l1, k.mb, k.ml,
  k.updated_by, w.nama as diubah_oleh_nama, k.updated_at
from koperasi.koreksi_storting k
left join koperasi.app_user u on u.id = k.admin_id
left join koperasi.app_user w on w.id = k.updated_by;

grant select on
  koperasi.v_kasir_entry, koperasi.v_kasir_summary,
  koperasi.v_jurnal_transaksi, koperasi.v_koreksi_storting
to authenticated;

commit;


-- #########################################################################
-- #                                                                       #
-- #   BATCH VERIFIKASI — jalankan setelah B-1..B-3                        #
-- #   Jangan lanjut ke B-4 sebelum kedua pemeriksaan ini bersih.          #
-- #                                                                       #
-- #########################################################################

-- (1) SETIAP view HARUS memuat security_invoker=on.
--     Baris yang reloptions-nya NULL adalah lubang keamanan, bukan sekadar
--     ketidakrapian: view itu mem-bypass RLS.
select c.relname, c.reloptions
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'koperasi' and c.relkind = 'v'
 order by c.relname;
-- Harapan: kedelapan view v_* memuat {security_invoker=on}.
-- (v_pinjaman_saldo, v_summary_cabang, v_nasabah_aktif dari 001/007 belum
--  memakainya — lihat CATATAN di akhir berkas.)

-- (2) Performa v_buku_pokok untuk SATU cabang.
--     Ganti '<CABANG>' dengan cabang sungguhan, mis. 'panti'.
explain analyze
select * from koperasi.v_buku_pokok
 where cabang_id = '<CABANG>' and not is_historis;
-- Yang diperhatikan: Execution Time, dan apakah ada Seq Scan pada
-- `pembayaran` (tabel terbesar, ±51.000 baris). Cache 10 menit milik
-- bukuPokokApi (getFromCache, :76) TIDAK punya padanan di sini — view
-- menghitung saat dibaca. Kalau lambat, penawarnya index atau materialized
-- view, BUKAN kembali ke cache aplikasi.

-- (3) Uji RLS sungguhan — paling penting, dan paling mudah terlewat.
--     Jalankan sebagai admin lapangan, bukan lewat SQL Editor (yang memakai
--     service_role dan MEM-BYPASS RLS sehingga selalu tampak benar):
--       curl "$SUPA_URL/rest/v1/v_buku_pokok?select=nasabah_id,cabang_id" \
--         -H "apikey: $ANON" -H "Authorization: Bearer $JWT_ADMIN"
--     Harapan: HANYA nasabah milik admin itu. Kalau muncul cabang lain,
--     security_invoker tidak bekerja — HENTIKAN dan periksa (1).


-- #########################################################################
-- #                                                                       #
-- #   BATCH B-4 — RPC TULIS (SECURITY DEFINER)                            #
-- #   Jalankan paling akhir.                                              #
-- #                                                                       #
-- #########################################################################

begin;

-- -------------------------------------------------------------------------
-- rpc_tambah_kasir_entry — menggantikan addKasirEntry
-- -------------------------------------------------------------------------
-- Idempoten lewat client_op_id UNIQUE (001 §8): memutar ulang permintaan
-- yang sama mengembalikan id lama alih-alih menggandakan entri kas.
create or replace function koperasi.rpc_tambah_kasir_entry(p jsonb)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role   koperasi.user_role;
  v_cabang text;
  v_op     uuid;
  v_id     uuid;
  v_arah   text;
begin
  select role, cabang_id into v_role, v_cabang
    from koperasi.app_user where id = auth.uid() and aktif;
  if v_role is null then
    raise exception 'Pemanggil tidak dikenal atau nonaktif' using errcode = '42501';
  end if;

  -- Cermin rules RTDB (rulesfirebase.txt:423): hanya kasir_unit di cabangnya.
  if v_role <> 'kasir_unit' then
    raise exception 'Hanya Kasir Unit yang dapat menambah entri kas'
      using errcode = '42501';
  end if;
  if coalesce(p->>'cabang_id','') <> coalesce(v_cabang,'') then
    raise exception 'Cabang tidak sesuai cabang Anda' using errcode = '42501';
  end if;

  v_op := nullif(p->>'client_op_id','')::uuid;
  if v_op is null then
    raise exception 'client_op_id wajib (kunci idempotensi)' using errcode = '23514';
  end if;

  select id into v_id from koperasi.kasir_entry where client_op_id = v_op;
  if v_id is not null then
    return v_id;                      -- sudah pernah masuk; bukan kegagalan
  end if;

  v_arah := p->>'arah';
  if v_arah not in ('masuk','keluar') then
    raise exception 'arah harus masuk atau keluar' using errcode = '23514';
  end if;

  insert into koperasi.kasir_entry (
    cabang_id, periode, tanggal, jenis, arah, nominal, keterangan,
    nota_path, target_admin_id, dicatat_oleh, dicatat_oleh_nama, client_op_id
  ) values (
    p->>'cabang_id',
    date_trunc('month', coalesce((p->>'tanggal')::date, current_date))::date,
    coalesce((p->>'tanggal')::date, current_date),
    coalesce(p->>'jenis',''),
    v_arah,
    coalesce((p->>'nominal')::bigint, 0),
    coalesce(p->>'keterangan',''),
    nullif(p->>'nota_path',''),
    nullif(p->>'target_admin_id','')::uuid,
    auth.uid(),
    coalesce(p->>'dicatat_oleh_nama',''),
    v_op
  )
  on conflict (client_op_id) do nothing
  returning id into v_id;

  if v_id is null then                -- balapan dua perangkat
    select id into v_id from koperasi.kasir_entry where client_op_id = v_op;
  end if;
  return v_id;
end;
$$;

-- -------------------------------------------------------------------------
-- rpc_hapus_kasir_entry — menggantikan deleteKasirEntry (SOFT DELETE)
-- -------------------------------------------------------------------------
-- Keputusan pemilik: soft delete. Entri kasir adalah catatan uang; menghapus
-- barisnya menghilangkan jejak bahwa uang itu pernah dicatat, dan rekap
-- bulan berjalan jadi tidak bisa direkonsiliasi dengan cetakan sebelumnya.
create or replace function koperasi.rpc_hapus_kasir_entry(
  p_entry_id uuid,
  p_alasan   text default ''
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role    koperasi.user_role;
  v_pencatat uuid;
  v_sudah   timestamptz;
begin
  select role into v_role
    from koperasi.app_user where id = auth.uid() and aktif;
  if v_role is null then
    raise exception 'Pemanggil tidak dikenal atau nonaktif' using errcode = '42501';
  end if;

  select dicatat_oleh, dihapus_at into v_pencatat, v_sudah
    from koperasi.kasir_entry where id = p_entry_id;

  if v_pencatat is null and v_sudah is null then
    return;                            -- tidak ada; idempoten
  end if;
  if v_sudah is not null then
    return;                            -- sudah dihapus; idempoten
  end if;

  if v_role <> 'pengawas' and v_pencatat is distinct from auth.uid() then
    raise exception 'Hanya pencatatnya atau Pengawas yang dapat menghapus entri ini'
      using errcode = '42501';
  end if;

  update koperasi.kasir_entry
     set dihapus_at   = now(),
         dihapus_oleh = auth.uid(),
         alasan_hapus = coalesce(p_alasan,'')
   where id = p_entry_id;
end;
$$;

-- -------------------------------------------------------------------------
-- rpc_set_koreksi_storting — menggantikan setKoreksiStorting
-- -------------------------------------------------------------------------
-- Ini MENGUBAH ANGKA PEMBUKUAN yang tampil di Buku Pokok, jadi pelakunya
-- selalu dicatat di updated_by.
create or replace function koperasi.rpc_set_koreksi_storting(p jsonb)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role koperasi.user_role;
begin
  select role into v_role
    from koperasi.app_user where id = auth.uid() and aktif;
  if v_role not in ('pimpinan','pengawas') then
    raise exception 'Hanya Pimpinan atau Pengawas yang dapat mengubah koreksi storting'
      using errcode = '42501';
  end if;

  insert into koperasi.koreksi_storting (
    cabang_id, admin_id, periode, cm, l1, mb, ml, updated_by, updated_at
  ) values (
    p->>'cabang_id',
    (p->>'admin_id')::uuid,
    date_trunc('month', (p->>'periode')::date)::date,
    coalesce((p->>'cm')::bigint, 0),
    coalesce((p->>'l1')::bigint, 0),
    coalesce((p->>'mb')::bigint, 0),
    coalesce((p->>'ml')::bigint, 0),
    auth.uid(), now()
  )
  on conflict (cabang_id, admin_id, periode) do update
     set cm = excluded.cm, l1 = excluded.l1,
         mb = excluded.mb, ml = excluded.ml,
         updated_by = excluded.updated_by, updated_at = excluded.updated_at;
end;
$$;

-- -------------------------------------------------------------------------
-- rpc_sync_operasional_transport — menggantikan syncOperasionalTransport
-- -------------------------------------------------------------------------
-- PRASYARAT: `016a_operasional_harian.sql` sudah dijalankan, dan datanya
-- sudah diimpor dengan `scripts/migration/migrate_operasional_harian.js`.
-- Tanpa tabel itu fungsi ini gagal dengan 42P01 (relation does not exist) —
-- galat yang jelas, bukan entri kas Rp 0 yang diam-diam tertulis.
--
-- Versi sebelumnya di berkas ini sengaja gagal-keras karena
-- `operasional_harian` belum dimigrasikan (006 §6). Pemilik memutuskan
-- fiturnya dipakai sehari-hari, jadi sumbernya dimigrasikan dan badan fungsi
-- ini diisi penuh. Riwayat keputusannya di `016_operasional_harian.md` §5.
--
-- Setara 1:1 dengan kasirApi.js:576-714:
--   * gate `kasir_unit` saja                       (:606)
--   * cabang diambil dari profil pemanggil          (:610)
--   * tanggal default = hari ini WIB                (:616-624)
--   * jumlahkan uangMakan+transport, hanya subtotal > 0   (:632-641)
--   * keterangan menghitung SELURUH record, termasuk yang nol  (:675)
--   * satu entri ber-kunci tetap `auto_ops_{tanggal}` → upsert (:644)
--   * total 0 tanpa entri lama  → tidak melakukan apa-apa      (:653)
--   * total 0 dengan entri lama → entri lama dihapus           (:662)
--
-- Dua penyimpangan yang disengaja, keduanya lebih aman dari aslinya:
--   1. Penghapusan memakai SOFT DELETE (kolom dari B-3.0), bukan `remove()`.
--      Alasannya sama dengan rpc_hapus_kasir_entry: entri kas adalah catatan
--      uang. Kalau esoknya angkanya muncul lagi, baris yang sama dihidupkan
--      kembali — bukan baris baru — sehingga jejaknya utuh.
--   2. Tidak ada penyesuaian `kasir_summary`. Rekap kasir di Supabase adalah
--      view beragregat (B-3.3), dihitung saat dibaca; tidak ada penghitung
--      tersimpan yang bisa melenceng seperti di RTDB.
--
-- Idempotensi: `client_op_id` deterministik dari (cabang, tanggal), sehingga
-- dipanggil sepuluh kali sehari pun tetap satu baris.
create or replace function koperasi.rpc_sync_operasional_transport(
  p_cabang_id text default null,
  p_tanggal   date default null
) returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role    koperasi.user_role;
  v_cabang  text;
  v_nama    text;
  v_tgl     date;
  v_total   bigint;
  v_jumlah_record int;
  v_op      uuid;
  v_id      uuid;
  v_dihapus timestamptz;
begin
  select role, cabang_id, nama into v_role, v_cabang, v_nama
    from koperasi.app_user where id = auth.uid() and aktif;
  if v_role is null then
    raise exception 'Pemanggil tidak dikenal atau nonaktif' using errcode = '42501';
  end if;

  -- kasirApi.js:606 — hanya Kasir Unit.
  if v_role <> 'kasir_unit' then
    raise exception 'Hanya Kasir Unit yang dapat sync operasional'
      using errcode = '42501';
  end if;
  if v_cabang is null or v_cabang = '' then
    raise exception 'User tidak memiliki cabang' using errcode = '23514';
  end if;
  -- Cabang TIDAK diambil dari parameter (aslinya pun tidak). Parameter hanya
  -- boleh menegaskan cabang sendiri; kalau berbeda, itu salah panggil.
  if p_cabang_id is not null and p_cabang_id <> v_cabang then
    raise exception 'Cabang tidak sesuai cabang Anda' using errcode = '42501';
  end if;

  -- kasirApi.js:616-624 — hari berjalan menurut WIB, bukan UTC.
  v_tgl := coalesce(p_tanggal, (now() at time zone 'Asia/Jakarta')::date);

  -- Hanya subtotal > 0 yang dijumlahkan, tetapi SEMUA record dihitung untuk
  -- keterangan — persis seperti aslinya (:641 vs :675).
  select coalesce(sum(o.uang_makan + o.transport)
                    filter (where o.uang_makan + o.transport > 0), 0),
         count(*)
    into v_total, v_jumlah_record
    from koperasi.operasional_harian o
   where o.cabang_id = v_cabang
     and o.tanggal   = v_tgl;

  v_op := md5('auto_ops:' || v_cabang || ':' || v_tgl::text)::uuid;

  select id, dihapus_at into v_id, v_dihapus
    from koperasi.kasir_entry where client_op_id = v_op;

  -- (a) tidak ada operasional, tidak ada entri lama → tidak ada yang dikerjakan
  if v_total = 0 and (v_id is null or v_dihapus is not null) then
    return null;
  end if;

  -- (b) operasional dinolkan/dihapus tetapi entri lama masih hidup
  if v_total = 0 then
    update koperasi.kasir_entry
       set dihapus_at   = now(),
           dihapus_oleh = auth.uid(),
           alasan_hapus = 'Sinkron operasional: total hari ini 0'
     where id = v_id;
    return v_id;
  end if;

  -- (c) upsert entri
  if v_id is null then
    insert into koperasi.kasir_entry (
      cabang_id, periode, tanggal, jenis, arah, nominal, keterangan,
      dicatat_oleh, dicatat_oleh_nama, client_op_id
    ) values (
      v_cabang,
      date_trunc('month', v_tgl)::date,
      v_tgl,
      'transport',
      'keluar',
      v_total,
      'Operasional ' || v_jumlah_record || ' karyawan',
      auth.uid(),
      coalesce(v_nama, ''),
      v_op
    )
    on conflict (client_op_id) do nothing
    returning id into v_id;

    if v_id is null then                -- balapan dua perangkat
      select id into v_id from koperasi.kasir_entry where client_op_id = v_op;
    end if;
  else
    -- `created_at` sengaja TIDAK disentuh (aslinya juga mempertahankan
    -- createdAt lama, :681). `dihapus_at` dinolkan supaya entri yang kemarin
    -- terhapus karena total 0 hidup lagi tanpa menggandakan baris.
    update koperasi.kasir_entry
       set nominal      = v_total,
           keterangan   = 'Operasional ' || v_jumlah_record || ' karyawan',
           tanggal      = v_tgl,
           periode      = date_trunc('month', v_tgl)::date,
           jenis        = 'transport',
           arah         = 'keluar',
           dihapus_at   = null,
           dihapus_oleh = null,
           alasan_hapus = ''
     where id = v_id;
  end if;

  return v_id;
end;
$$;

-- -------------------------------------------------------------------------
-- GRANT
-- -------------------------------------------------------------------------
revoke all on function koperasi.rpc_tambah_kasir_entry(jsonb)          from public, anon;
revoke all on function koperasi.rpc_hapus_kasir_entry(uuid, text)      from public, anon;
revoke all on function koperasi.rpc_set_koreksi_storting(jsonb)        from public, anon;
revoke all on function koperasi.rpc_sync_operasional_transport(text, date) from public, anon;

grant execute on function koperasi.rpc_tambah_kasir_entry(jsonb)          to authenticated;
grant execute on function koperasi.rpc_hapus_kasir_entry(uuid, text)      to authenticated;
grant execute on function koperasi.rpc_set_koreksi_storting(jsonb)        to authenticated;
grant execute on function koperasi.rpc_sync_operasional_transport(text, date) to authenticated;

commit;


-- #########################################################################
-- #   CATATAN                                                             #
-- #########################################################################
--
-- B-4a  syncOperasionalTransport — SUDAH DIPUTUSKAN (opsi a)
--       Ia MENULIS (kasirApi.js:644-686: entryKey `auto_ops_{tanggal}`,
--       entryRef.once lalu tulis), jadi tidak bisa jadi view — RPC.
--       Versi pertama berkas ini gagal-keras karena sumbernya,
--       `operasional_harian`, tidak ikut dimigrasikan (006 §6).
--       Pemilik memutuskan fiturnya dipakai sehari-hari, maka:
--         * DDL sumbernya  → 016a_operasional_harian.sql
--         * impor datanya  → scripts/migration/migrate_operasional_harian.js
--         * RPC-nya di atas sudah versi penuh, bukan lagi stub.
--       Urutan jalan: 016a → impor data → baru B-4 ini.
--       Kalau B-4 dijalankan lebih dulu, fungsinya tetap tercipta tetapi
--       gagal 42P01 saat dipanggil. Rincian di 016_operasional_harian.md.
--
-- B-4b  View bawaan 001/007 BELUM memakai security_invoker:
--         koperasi.v_pinjaman_saldo   (001 §4)
--         koperasi.v_summary_cabang   (001 §12)
--         koperasi.v_nasabah_aktif    (007 §1)
--       v_pinjaman_saldo dipakai v_buku_pokok, jadi ini BUKAN soal kerapian:
--       selama ia tanpa security_invoker, ia berpotensi melebarkan baris
--       yang terlihat lewat v_buku_pokok. Perlu diperbaiki terpisah:
--         alter view koperasi.v_pinjaman_saldo set (security_invoker = on);
--         alter view koperasi.v_summary_cabang set (security_invoker = on);
--         alter view koperasi.v_nasabah_aktif  set (security_invoker = on);
--       SENGAJA tidak dimasukkan ke batch mana pun di atas: ketiganya sudah
--       Anda jalankan, dan mengubah perilaku view yang sudah dipakai perlu
--       keputusan sadar, bukan efek samping batch Tahap B.
--
-- B-4c  Berkas ini belum pernah dijalankan. Tidak ada PostgreSQL di sisi
--       penulis, jadi sintaksnya belum pernah divalidasi server.
-- #########################################################################
