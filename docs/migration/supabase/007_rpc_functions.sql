-- =========================================================================
-- RPC PENDAMPING SYNC ENGINE (Milestone 3)
-- Prasyarat: 001_schema_v2.sql, 001a_schema_patch.sql, 002_rls_policies.sql
-- RANCANGAN — BELUM DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- Dua operasi antrean offline tidak punya padanan langsung di PostgREST dan
-- di Milestone 2 sengaja dibiarkan gagal-keras. Di sinilah keduanya
-- diselesaikan — lewat SECURITY DEFINER yang sempit dan auditable, BUKAN
-- dengan melonggarkan RLS.
--
--   RTDB `removeValue()` pelanggan  → koperasi.rpc_arsipkan_nasabah()
--   RTDB tulis jurnal_transaksi     → koperasi.rpc_catat_jurnal()
--
-- Keduanya WAJIB idempoten: antrean Room bisa memutar ulang operasi yang
-- sama setelah jaringan putus di tengah jalan.
-- =========================================================================

begin;

-- =========================================================================
-- 1. SOFT DELETE untuk nasabah
-- =========================================================================
-- RTDB memakai removeValue() pada alur cairkanSimpanan (nasabah lunas total).
-- Menghapus baris di Postgres TIDAK BOLEH: `pembayaran` dan `jurnal_transaksi`
-- menunjuk ke sana, dan riwayat keuangan tidak boleh lenyap (002 §5 bahkan
-- tidak memberi policy DELETE pada `pinjaman`).
--
-- Jadi: penanda arsip, bukan penghapusan.
alter table koperasi.nasabah
  add column if not exists arsip_at    timestamptz,
  add column if not exists alasan_arsip text not null default '';

create index if not exists nasabah_aktif_idx
  on koperasi.nasabah (cabang_id) where arsip_at is null;

comment on column koperasi.nasabah.arsip_at is
  'Terisi = nasabah diarsipkan (padanan removeValue() RTDB). Baris TIDAK dihapus '
  'agar pembayaran & jurnal yang menunjuknya tetap utuh.';

-- View kenyamanan. Policy RLS di 002 SENGAJA tidak diubah — menyaring baris
-- lewat policy akan membuat data "hilang" bagi pemanggil lain tanpa jejak.
-- Penyaringan adalah urusan query, bukan izin.
create or replace view koperasi.v_nasabah_aktif as
  select * from koperasi.nasabah where arsip_at is null;

-- =========================================================================
-- 2. rpc_arsipkan_nasabah — padanan REMOVE_PELANGGAN
-- =========================================================================
create or replace function koperasi.rpc_arsipkan_nasabah(
  p_nasabah_id uuid,
  p_alasan     text default ''
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role   koperasi.user_role;
  v_admin  uuid;
  v_hidup  integer;
begin
  -- Wewenang dicek eksplisit: SECURITY DEFINER mem-bypass RLS, jadi fungsi
  -- ini WAJIB menegakkan sendiri apa yang biasanya ditegakkan policy.
  select role into v_role
    from koperasi.app_user where id = auth.uid() and aktif;
  if v_role is null then
    raise exception 'Pemanggil tidak dikenal atau nonaktif'
      using errcode = '42501';
  end if;

  select admin_id into v_admin
    from koperasi.nasabah where id = p_nasabah_id;

  -- IDEMPOTEN: nasabah tidak ada / sudah lenyap → sukses diam.
  -- Antrean offline boleh memutar ulang operasi ini tanpa jadi error.
  if v_admin is null then
    return;
  end if;

  if v_role not in ('pengawas', 'pimpinan') and v_admin <> auth.uid() then
    raise exception 'Nasabah ini bukan milik Anda'
      using errcode = '42501';
  end if;

  -- Pengaman uang: jangan arsipkan nasabah yang masih punya pinjaman hidup.
  -- Di RTDB tidak ada pemeriksaan ini sama sekali — removeValue() menghapus
  -- apa pun keadaannya.
  select count(*) into v_hidup
    from koperasi.pinjaman
   where nasabah_id = p_nasabah_id
     and status in ('Menunggu Approval', 'Disetujui', 'Aktif');
  if v_hidup > 0 then
    raise exception 'Masih ada % pinjaman hidup; nasabah tidak diarsipkan', v_hidup
      using errcode = '23514';
  end if;

  -- coalesce → pemutaran ulang tidak menggeser stempel waktu aslinya.
  update koperasi.nasabah
     set arsip_at     = coalesce(arsip_at, now()),
         alasan_arsip = case when alasan_arsip = '' then coalesce(p_alasan, '') else alasan_arsip end
   where id = p_nasabah_id;
end;
$$;

-- =========================================================================
-- 3. rpc_catat_jurnal — padanan ADD_JURNAL_TRANSAKSI
-- =========================================================================
-- `jurnal_transaksi` tidak punya GRANT INSERT untuk `authenticated`
-- (002 §10) karena audit trail hanya boleh ditulis kode server. Fungsi ini
-- adalah satu-satunya pintunya, dan tetap memvalidasi pemanggil.
create or replace function koperasi.rpc_catat_jurnal(p jsonb)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role      koperasi.user_role;
  v_cabang    text;
  v_op        uuid;
  v_id        uuid;
  v_tipe      koperasi.jurnal_tipe;
begin
  select role, cabang_id into v_role, v_cabang
    from koperasi.app_user where id = auth.uid() and aktif;
  if v_role is null then
    raise exception 'Pemanggil tidak dikenal atau nonaktif' using errcode = '42501';
  end if;

  v_op := nullif(p->>'client_op_id', '')::uuid;
  if v_op is null then
    raise exception 'client_op_id wajib (kunci idempotensi)' using errcode = '23514';
  end if;

  -- Sudah pernah tercatat → kembalikan id lama. Ini yang membuat replay
  -- antrean aman: pemanggil menerima sukses, bukan duplicate-key error.
  select id into v_id from koperasi.jurnal_transaksi where client_op_id = v_op;
  if v_id is not null then
    return v_id;
  end if;

  v_tipe := (p->>'tipe')::koperasi.jurnal_tipe;   -- gagal keras bila tak dikenal

  -- Admin lapangan hanya boleh mencatat untuk cabangnya sendiri.
  if v_role = 'admin' and coalesce(p->>'cabang_id', '') <> coalesce(v_cabang, '') then
    raise exception 'Cabang pada jurnal tidak sesuai cabang Anda' using errcode = '42501';
  end if;

  insert into koperasi.jurnal_transaksi (
    id, cabang_id, tipe, nasabah_id, pinjaman_id,
    nama_pelanggan, nama_ktp, admin_id, admin_name,
    jumlah, tanggal, pinjaman_ke, sisa_utang_setelah,
    total_pelunasan, total_dibayar, keterangan, client_op_id
  ) values (
    coalesce(nullif(p->>'id','')::uuid, gen_random_uuid()),
    p->>'cabang_id',
    v_tipe,
    nullif(p->>'nasabah_id','')::uuid,
    nullif(p->>'pinjaman_id','')::uuid,
    coalesce(p->>'nama_pelanggan',''),
    coalesce(p->>'nama_ktp',''),
    coalesce(nullif(p->>'admin_id','')::uuid, auth.uid()),
    coalesce(p->>'admin_name',''),
    coalesce((p->>'jumlah')::bigint, 0),
    coalesce((p->>'tanggal')::date, current_date),
    nullif(p->>'pinjaman_ke','')::integer,
    nullif(p->>'sisa_utang_setelah','')::bigint,
    nullif(p->>'total_pelunasan','')::bigint,
    nullif(p->>'total_dibayar','')::bigint,
    coalesce(p->>'keterangan',''),
    v_op
  )
  on conflict (client_op_id) do nothing
  returning id into v_id;

  -- Balapan dua perangkat: baris keburu masuk di antara SELECT dan INSERT.
  if v_id is null then
    select id into v_id from koperasi.jurnal_transaksi where client_op_id = v_op;
  end if;
  return v_id;
end;
$$;

-- =========================================================================
-- 4. GRANT
-- =========================================================================
revoke all on function koperasi.rpc_arsipkan_nasabah(uuid, text) from public, anon;
revoke all on function koperasi.rpc_catat_jurnal(jsonb) from public, anon;

grant execute on function koperasi.rpc_arsipkan_nasabah(uuid, text) to authenticated;
grant execute on function koperasi.rpc_catat_jurnal(jsonb) to authenticated;

grant select on koperasi.v_nasabah_aktif to authenticated;

commit;

-- =========================================================================
-- CATATAN
-- =========================================================================
-- Kedua fungsi SECURITY DEFINER dan karena itu MEM-BYPASS RLS. Keamanannya
-- bergantung sepenuhnya pada pemeriksaan wewenang di dalam badan fungsi —
-- bukan pada policy. Setiap perubahan di sini harus dibaca dengan asumsi itu.
--
-- Belum diuji. Tidak ada PostgreSQL di environment tempat berkas ini ditulis.
-- =========================================================================
