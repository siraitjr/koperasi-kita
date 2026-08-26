-- =========================================================================
-- KOPERASI KITA — 023: ABSENSI
-- RANCANGAN — BELUM PERNAH DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- ⚠ ASUMSI YANG SAYA AMBIL, DAN ALASANNYA
-- -------------------------------------------------------------------------
-- Keputusan absensi di brief masih berupa penanda kosong
-- `[MIGRASIKAN_SEKARANG / TANGGUHKAN]`. Saya kerjakan dengan asumsi
-- MIGRASIKAN, karena pada premis baru "tangguhkan" tidak lagi berarti
-- "kerjakan nanti":
--
--   RTDB mati pada cutoff. Absensi HANYA hidup di RTDB (tidak pernah masuk
--   lingkup migrasi mana pun — 021 §1.4). Menangguhkan = fitur absensi
--   BERHENTI pada hari cutoff, dan seluruh riwayatnya HILANG PERMANEN.
--
-- Kalau itu memang yang dipilih, berkas ini tinggal tidak dijalankan — tidak
-- ada ongkosnya. Yang tidak bisa diperbaiki belakangan adalah kebalikannya.
-- Ekspor `absensi/` dan `user_absensi_today/` SEBELUM cutoff tetap wajib,
-- apa pun keputusannya: ekspor bisa dibuang, RTDB yang mati tidak bisa
-- dihidupkan.
--
-- Prasyarat: 001 → 001a → 002 → 018 B-1 → 022 (butuh app_user.legacy_uid).
--
-- Bentuk asal (data nyata, data/firebase_sample.json):
--   absensi/{cabangId}/{YYYY-MM-DD}/{uid} = {
--     uid, nama, role, cabangId, cabangNama, jam:"11:18",
--     tanggal:"2026-03-27", timestamp:1774585111795 }
--   user_absensi_today/{uid} = cermin baris terakhir orang itu
--
-- Penulis hari ini: buku-pokok-web/app/kasir/page.js:3758-3759 (dua tulisan
-- terpisah), dan Android.
-- =========================================================================

begin;

create table if not exists koperasi.absensi (
  cabang_id   text not null references koperasi.cabang(id),
  tanggal     date not null,
  user_id     uuid references koperasi.app_user(id),
  legacy_uid  text not null,

  nama        text not null default '',
  role        text not null default '',
  cabang_nama text not null default '',
  jam         text not null default '',        -- "HH:MM" WIB, seperti aslinya
  recorded_at timestamptz,
  created_at  timestamptz not null default now(),

  -- Sama seperti operasional_harian: PK memakai legacy_uid karena user_id
  -- boleh NULL, dan NULL tidak bisa jadi bagian primary key. PK ini juga
  -- yang menegakkan "satu orang satu absen per hari" — di RTDB dijamin oleh
  -- bentuk path-nya, di sini harus dinyatakan.
  primary key (cabang_id, tanggal, legacy_uid)
);

create index if not exists absensi_tanggal_idx on koperasi.absensi (tanggal desc);
create index if not exists absensi_user_idx    on koperasi.absensi (user_id, tanggal desc);

comment on table koperasi.absensi is
  'Absensi harian staf. Menggantikan node RTDB absensi/{cabang}/{tanggal}/{uid}.';

-- -------------------------------------------------------------------------
-- Pengganti `user_absensi_today` — VIEW, bukan tabel
-- -------------------------------------------------------------------------
-- Di RTDB ia tabel kedua yang ditulis terpisah (kasir/page.js:3759), jadi
-- bisa melenceng dari sumbernya kalau tulisan kedua gagal. Di sini ia
-- diturunkan, sehingga mustahil tidak sinkron.
create or replace view koperasi.v_absensi_hari_ini
with (security_invoker = on) as
select a.*
  from koperasi.absensi a
 where a.tanggal = (now() at time zone 'Asia/Jakarta')::date;

-- -------------------------------------------------------------------------
-- RLS
-- -------------------------------------------------------------------------
alter table koperasi.absensi enable row level security;
alter table koperasi.absensi force  row level security;

-- Absensi sendiri selalu terlihat; atasan melihat cabang yang boleh
-- dilihatnya. Bentuk set-based mengikuti 018 §1 (sublink + cast wajib).
create policy absensi_baca on koperasi.absensi
  for select to authenticated
  using (
    user_id = auth.uid()
    or cabang_id = any ((select koperasi_priv.cabang_terlihat_arr())::text[])
  );

-- Tidak ada policy tulis: penulisan hanya lewat RPC di bawah. Absensi adalah
-- catatan kehadiran yang dipakai menilai orang; membiarkan klien menulis
-- barisnya sendiri berarti jam dan tanggalnya bisa dikarang.
grant select on koperasi.absensi, koperasi.v_absensi_hari_ini to authenticated;

-- -------------------------------------------------------------------------
-- rpc_catat_absensi
-- -------------------------------------------------------------------------
-- Selalu untuk DIRI SENDIRI dan untuk HARI INI. Keduanya diambil server,
-- tidak diterima dari klien — cermin kasir/page.js:3738 (`uid` dari sesi)
-- dan :3742-3746 (jam dihitung di klien).
--
-- Beda yang disengaja: jam diambil dari jam SERVER, bukan jam perangkat.
-- Di RTDB `jam` dihitung dari `new Date()` di browser, jadi seseorang cukup
-- memundurkan jam laptopnya untuk absen "tepat waktu". Di sini tidak bisa.
create or replace function koperasi.rpc_catat_absensi()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role   koperasi.user_role;
  v_cabang text;
  v_nama   text;
  v_legacy text;
  v_cnama  text;
  v_now    timestamptz := now();
  v_tgl    date;
  v_jam    text;
begin
  select u.role, u.cabang_id, u.nama, coalesce(u.legacy_uid, 'sb:' || u.id::text)
    into v_role, v_cabang, v_nama, v_legacy
    from koperasi.app_user u where u.id = auth.uid() and u.aktif;
  if v_role is null then
    raise exception 'Pemanggil tidak dikenal atau nonaktif' using errcode = '42501';
  end if;
  if v_cabang is null or v_cabang = '' then
    raise exception 'User tidak memiliki cabang' using errcode = '23514';
  end if;

  select c.nama into v_cnama from koperasi.cabang c where c.id = v_cabang;

  v_tgl := (v_now at time zone 'Asia/Jakarta')::date;
  v_jam := to_char(v_now at time zone 'Asia/Jakarta', 'HH24:MI');

  -- `do nothing`, BUKAN `do update`: absen kedua di hari yang sama tidak
  -- boleh menimpa jam yang pertama. Jam pertama itulah datanya.
  insert into koperasi.absensi (
    cabang_id, tanggal, user_id, legacy_uid, nama, role, cabang_nama,
    jam, recorded_at
  ) values (
    v_cabang, v_tgl, auth.uid(), v_legacy, coalesce(v_nama, ''),
    v_role::text, coalesce(v_cnama, v_cabang), v_jam, v_now
  )
  on conflict (cabang_id, tanggal, legacy_uid) do nothing;

  -- Selalu mengembalikan baris yang BERLAKU, entah baru dibuat atau sudah
  -- ada. Klien butuh jam-nya untuk ditampilkan, dan tidak perlu tahu mana
  -- di antara keduanya yang terjadi.
  return (
    select to_jsonb(a) from koperasi.absensi a
     where a.cabang_id = v_cabang and a.tanggal = v_tgl and a.legacy_uid = v_legacy
  );
end;
$$;

revoke all on function koperasi.rpc_catat_absensi() from public, anon;
grant execute on function koperasi.rpc_catat_absensi() to authenticated;

commit;

-- =========================================================================
-- VERIFIKASI
-- =========================================================================
-- Lewat REST dengan JWT staf sungguhan:
--   curl -X POST "$SUPA_URL/rest/v1/rpc/rpc_catat_absensi" \
--     -H "apikey: $ANON" -H "Authorization: Bearer $JWT" \
--     -H "Content-Type: application/json" -d '{}'
--
-- | Uji                          | Harapan                                  |
-- |------------------------------|------------------------------------------|
-- | panggilan pertama hari ini   | jsonb berisi jam server                   |
-- | panggilan KEDUA hari ini     | jsonb SAMA — jam pertama tidak berubah    |
-- | tanpa Authorization          | 401                                       |
-- | baca v_absensi_hari_ini      | hanya diri sendiri + cabang yang berhak   |
-- | JWT cabang lain              | baris itu TIDAK terlihat                  |
--
-- Impor riwayat (opsional, TETAPI hanya mungkin sebelum RTDB mati):
--   Polanya identik dengan scripts/migration/migrate_operasional_harian.js —
--   node `absensi`, kunci {cabang}/{tanggal}/{uid}, FK di-NULL-kan untuk staf
--   yang sudah keluar, idempoten lewat PK. `user_absensi_today` TIDAK perlu
--   diimpor: ia turunan, dan sudah digantikan v_absensi_hari_ini.
-- =========================================================================
