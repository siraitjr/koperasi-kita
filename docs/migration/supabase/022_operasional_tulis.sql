-- =========================================================================
-- KOPERASI KITA — 022: JALUR TULIS operasional_harian
-- Membuka penghalang 021 §3.1. RANCANGAN — BELUM PERNAH DIJALANKAN.
-- =========================================================================
--
-- Prasyarat: … → 016a → 019 (dan 018 B-1 untuk cabang_terlihat_arr).
--
-- 016a sengaja memberi klien SELECT saja. Itu benar saat itu: satu-satunya
-- penulis adalah skrip migrasi. Sekarang RTDB akan mati, dan web serta
-- Android harus bisa MENULIS ke sini — jadi jalurnya dibuat di sini, tetap
-- lewat RPC, bukan dengan melonggarkan policy tabel.
--
-- Yang digantikan: `buku-pokok-web/app/kasir/page.js:3796`
--     set(dbRefFn(db, `operasional_harian/${cabang}/${todayKey}/${uid}`), record)
-- dengan record { uid, nama, uangMakan, transport, diberikanOleh,
--                 diberikanOlehNama, timestamp }  (:3788-3794)
-- =========================================================================

begin;

-- -------------------------------------------------------------------------
-- 1. app_user.legacy_uid — jembatan identitas yang selama ini tidak ada
-- -------------------------------------------------------------------------
-- `operasional_harian` ber-PK (cabang_id, tanggal, legacy_uid), dan
-- `legacy_uid` itu UID Firebase. Sesudah Firebase mati, klien tidak lagi
-- punya UID Firebase — yang ada hanya uuid Supabase.
--
-- Tanpa kolom ini, baris yang ditulis sesudah evakuasi memakai identitas
-- yang berbeda dari baris warisan untuk ORANG YANG SAMA, dan riwayat satu
-- staf terbelah dua. Karena itu pemetaannya disimpan, bukan dikarang ulang
-- tiap kali.
alter table koperasi.app_user
  add column if not exists legacy_uid text;

create unique index if not exists app_user_legacy_uid_idx
  on koperasi.app_user (legacy_uid) where legacy_uid is not null;

-- Backfill dari dua sumber yang SUDAH memuat pasangan (uuid ↔ UID Firebase).
-- Tidak ada cara membalik uuidv5, jadi ini satu-satunya jalan.
--   (a) operasional_harian: user_id + legacy_uid berdampingan di satu baris.
update koperasi.app_user u
   set legacy_uid = o.legacy_uid
  from (
    select distinct on (user_id) user_id, legacy_uid
      from koperasi.operasional_harian
     where user_id is not null
     order by user_id, tanggal desc
  ) o
 where u.id = o.user_id
   and u.legacy_uid is null;

--   (b) nasabah: admin_id + legacy_admin_uid berdampingan.
update koperasi.app_user u
   set legacy_uid = n.legacy_admin_uid
  from (
    select distinct on (admin_id) admin_id, legacy_admin_uid
      from koperasi.nasabah
     where legacy_admin_uid is not null and legacy_admin_uid <> ''
     order by admin_id, created_at desc
  ) n
 where u.id = n.admin_id
   and u.legacy_uid is null;

-- Staf yang tidak muncul di keduanya tetap NULL. Itu bukan kegagalan:
-- RPC di bawah memakai 'sb:'||uuid untuk mereka — bentuk yang TIDAK MUNGKIN
-- bentrok dengan UID Firebase (28 karakter alfanumerik, tanpa titik dua).
-- Hitung berapa yang tersisa sebelum lanjut:
--   select count(*) from koperasi.app_user where legacy_uid is null and aktif;

-- -------------------------------------------------------------------------
-- 2. rpc_catat_operasional_harian
-- -------------------------------------------------------------------------
-- Gerbangnya CERMIN dari siapa yang menulis hari ini: halaman kasir
-- (kasir/page.js:3784-3796) hanya dapat dibuka kasir_unit, dan
-- `diberikanOleh` selalu uid kasir yang sedang login. Jadi: kasir_unit saja,
-- dan hanya untuk cabangnya sendiri.
--
-- Idempoten lewat PK. Menyimpan dua kali untuk staf & hari yang sama adalah
-- KOREKSI (kasir membetulkan angka), bukan penggandaan — maka `do update`,
-- bukan `do nothing`. Ini sesuai perilaku RTDB `set()` yang menimpa.
create or replace function koperasi.rpc_catat_operasional_harian(
  p_user_id    uuid,      -- staf penerima
  p_uang_makan bigint,
  p_transport  bigint,
  p_tanggal    date default null
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role     koperasi.user_role;
  v_cabang   text;
  v_nama     text;
  v_tgl      date;
  v_t_nama   text;
  v_t_cabang text;
  v_t_legacy text;
begin
  select role, cabang_id, nama into v_role, v_cabang, v_nama
    from koperasi.app_user where id = auth.uid() and aktif;
  if v_role is null then
    raise exception 'Pemanggil tidak dikenal atau nonaktif' using errcode = '42501';
  end if;
  if v_role <> 'kasir_unit' then
    raise exception 'Hanya Kasir Unit yang dapat mencatat operasional harian'
      using errcode = '42501';
  end if;
  if v_cabang is null or v_cabang = '' then
    raise exception 'User tidak memiliki cabang' using errcode = '23514';
  end if;

  if p_uang_makan < 0 or p_transport < 0 then
    raise exception 'Nominal tidak boleh negatif' using errcode = '23514';
  end if;

  select nama, cabang_id, coalesce(legacy_uid, 'sb:' || id::text)
    into v_t_nama, v_t_cabang, v_t_legacy
    from koperasi.app_user where id = p_user_id and aktif;
  if v_t_nama is null then
    raise exception 'Staf tidak ditemukan atau nonaktif' using errcode = '23503';
  end if;
  -- Kasir tidak boleh mencatat uang untuk staf cabang lain. Di RTDB
  -- pembatasan ini TIDAK ADA — path-nya memakai cabang si kasir, tetapi
  -- {uid} di ujungnya tidak pernah diperiksa. Pengetatan yang disengaja.
  if v_t_cabang is distinct from v_cabang then
    raise exception 'Staf tersebut bukan di cabang Anda' using errcode = '42501';
  end if;

  v_tgl := coalesce(p_tanggal, (now() at time zone 'Asia/Jakarta')::date);

  insert into koperasi.operasional_harian (
    cabang_id, tanggal, user_id, legacy_uid, nama,
    uang_makan, transport,
    diberikan_oleh, diberikan_oleh_nama, diberikan_oleh_legacy_uid,
    recorded_at
  ) values (
    v_cabang, v_tgl, p_user_id, v_t_legacy, coalesce(v_t_nama, ''),
    p_uang_makan, p_transport,
    auth.uid(), coalesce(v_nama, ''),
    (select legacy_uid from koperasi.app_user where id = auth.uid()),
    now()
  )
  on conflict (cabang_id, tanggal, legacy_uid) do update
     set uang_makan  = excluded.uang_makan,
         transport   = excluded.transport,
         nama        = excluded.nama,
         user_id     = excluded.user_id,
         diberikan_oleh      = excluded.diberikan_oleh,
         diberikan_oleh_nama = excluded.diberikan_oleh_nama,
         diberikan_oleh_legacy_uid = excluded.diberikan_oleh_legacy_uid,
         recorded_at = excluded.recorded_at;
end;
$$;

revoke all on function koperasi.rpc_catat_operasional_harian(uuid, bigint, bigint, date)
  from public, anon;
grant execute on function koperasi.rpc_catat_operasional_harian(uuid, bigint, bigint, date)
  to authenticated;

commit;

-- =========================================================================
-- VERIFIKASI
-- =========================================================================
-- Lewat REST dengan JWT kasir_unit — BUKAN SQL Editor (auth.uid() NULL di
-- sana, dan seluruh gerbang perannya tidak teruji).
--
--   curl -X POST "$SUPA_URL/rest/v1/rpc/rpc_catat_operasional_harian" \
--     -H "apikey: $ANON" -H "Authorization: Bearer $JWT_KASIR" \
--     -H "Content-Type: application/json" \
--     -d '{"p_user_id":"<uuid staf>","p_uang_makan":15000,"p_transport":35000}'
--
-- | Uji                                   | Harapan                        |
-- |---------------------------------------|--------------------------------|
-- | kasir_unit, staf secabang             | 204; satu baris baru           |
-- | dipanggil DUA KALI angka berbeda      | tetap SATU baris, angka terbaru|
-- | staf cabang lain                      | 42501                          |
-- | nominal negatif                       | 23514                          |
-- | JWT admin lapangan                    | 42501                          |
-- | tanpa Authorization                   | 401                            |
--
-- Lalu rantai penuhnya — inilah yang sebenarnya diuji:
--   1) rpc_catat_operasional_harian (di atas)
--   2) rpc_sync_operasional_transport
--   3) periksa entri kasir hari itu: satu baris, nominal = jumlah operasional
--   4) ubah angka staf jadi 0 lewat (1), ulangi (2)
--      → entri kasir hari itu HARUS terhapus lunak (015 B-4 cabang total=0)
--
-- Backfill legacy_uid:
--   select count(*) filter (where legacy_uid is not null) as terpetakan,
--          count(*) filter (where legacy_uid is null)     as belum
--     from koperasi.app_user where aktif;
-- =========================================================================
