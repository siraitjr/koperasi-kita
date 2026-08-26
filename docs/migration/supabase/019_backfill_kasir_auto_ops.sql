-- =========================================================================
-- KOPERASI KITA — 019: SELARASKAN client_op_id ENTRI KASIR OTOMATIS
-- Menutup duplikasi rekap yang ditemukan saat uji rpc_sync_operasional_transport.
-- RANCANGAN — BELUM PERNAH DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- Prasyarat: 001 → 001a → 002 → 015 (B-1..B-3) → 017 B-1 → 018 B-1
--            → 016a → impor operasional_harian → 015 B-4
--
-- URUTAN CUT-OVER: berkas ini WAJIB jalan SESUDAH B-4 terpasang dan
-- SEBELUM web dialihkan memanggil RPC. Selama belum jalan, setiap panggilan
-- RPC untuk hari yang entri warisannya sudah ada akan MENAMBAH baris kedua,
-- dan rekap kasir hari itu terhitung dua kali.
--
-- =========================================================================
-- 0. SEBABNYA — terbukti di kode, bukan dugaan
-- =========================================================================
--
-- `migrate.js` memberi kedua kolom nilai yang SAMA:
--
--     migrate.js:973   id:           ID.kasir(cab, bulan, kid)
--     migrate.js:983   client_op_id: ID.kasir(cab, bulan, kid)
--
-- yaitu uuidv5 dari `kasir:{cabang}/{YYYY-MM}/{kunci RTDB}`. Untuk entri
-- otomatis, kunci RTDB-nya `auto_ops_{YYYY-MM-DD}` (kasirApi.js:644).
--
-- `rpc_sync_operasional_transport` (015 B-4) mencari barisnya lewat
--
--     md5('auto_ops:' || cabang || ':' || tanggal)::uuid
--
-- Dua rumus yang sama sekali berbeda untuk baris yang sama. RPC tidak
-- menemukan entri warisan, menyimpulkan "belum ada", lalu INSERT. Hasilnya
-- dua baris untuk satu hari — dan karena `v_kasir_entry` menjumlahkan
-- keduanya, rekapnya menggelembung.
--
-- Perbaikannya menyelaraskan kunci, BUKAN menghapus salah satu baris:
-- entri warisan memegang `created_at` asli dari RTDB, dan RPC memang
-- dirancang mempertahankan `createdAt` lama (kasirApi.js:681). Yang dibuang
-- adalah baris yang dibuat RPC saat uji, bukan catatan aslinya.
--
-- CATATAN KETERGANTUNGAN HALUS: `tanggal::text` menghasilkan `YYYY-MM-DD`
-- di bawah DateStyle ISO (bawaan Supabase). Rumus di berkas ini HARUS sama
-- persis dengan yang di B-4 — keduanya memakai `::text`. Kalau suatu hari
-- DateStyle diubah, keduanya ikut berubah bersama, tetapi kunci yang sudah
-- tertulis TIDAK. §4 menguji kesetaraannya secara langsung, jadi
-- penyimpangan apa pun ketahuan di situ.


-- =========================================================================
-- 1. CARA MENGENALI BARISNYA — dan kenapa `keterangan LIKE` saja berbahaya
-- =========================================================================
--
-- `keterangan like 'Operasional % karyawan'` adalah teks yang BISA DIKETIK
-- MANUAL oleh kasir. Kalau ada entri manual berbunyi persis begitu, memberi
-- ia kunci `auto_ops` akan membuat RPC MENIMPA entri manual itu setiap hari
-- dengan angka hitungannya sendiri — kerusakan yang senyap dan permanen.
--
-- Karena itu penyaringnya berlapis, dan lapisan pertamanya bukan teks:
--
--   (1) `client_op_id = id`  → ciri khas warisan migrate.js. Baris apa pun
--       yang lahir dari RPC atau dari `rpc_tambah_kasir_entry` TIDAK pernah
--       begini.
--   (2) `jenis = 'transport' and arah = 'keluar'` → satu-satunya bentuk yang
--       pernah ditulis fitur ini (kasirApi.js:677-678).
--   (3) `keterangan like 'Operasional % karyawan'` (kasirApi.js:675).
--   (4) `dihapus_at is null` → jangan hidupkan kembali yang sudah dihapus.
--
-- Penyaring yang LEBIH KUAT sebenarnya ada, tetapi tidak bisa dipakai:
-- RTDB menandai entri otomatis dengan `source: 'operasional_harian'`
-- (kasirApi.js:686) — penanda yang tak mungkin diketik tak sengaja.
-- `migrate.js:972-984` TIDAK membawa field itu, jadi ia hilang saat migrasi.
-- Dicatat sebagai kehilangan yang nyata; menambahkannya sekarang menuntut
-- impor ulang dan tidak sepadan.
--
-- Lapisan (1) sudah membuat salah-tangkap sangat kecil: entri manual buatan
-- kasir masuk lewat `rpc_tambah_kasir_entry` yang selalu memberi
-- `client_op_id` dari klien, tidak pernah sama dengan `id`. Yang tersisa
-- hanyalah entri manual yang IKUT DIMIGRASIKAN dari RTDB dan kebetulan
-- berbunyi sama persis. §2 menghitungnya sebelum apa pun diubah.


-- #########################################################################
-- #   2. UJI SEBELUM — jalankan dan BACA dulu, jangan langsung §3          #
-- #########################################################################

-- 2.1  Berapa kandidatnya, dan apakah angkanya masuk akal.
select count(*)                        as kandidat,
       min(tanggal)                     as tgl_awal,
       max(tanggal)                     as tgl_akhir,
       count(distinct cabang_id)        as cabang,
       sum(nominal)                     as total_nominal
  from koperasi.kasir_entry
 where client_op_id = id
   and jenis = 'transport'
   and arah  = 'keluar'
   and keterangan like 'Operasional % karyawan'
   and dihapus_at is null;

-- 2.2  PENJAGA UNIK (a): dua kandidat untuk satu (cabang, tanggal).
--      HARUS kosong. Kalau ada isinya, §3 akan menolak jalan — dan memang
--      seharusnya: keduanya akan memperebutkan satu client_op_id, dan hanya
--      manusia yang bisa memutuskan mana catatan yang benar.
select cabang_id, tanggal, count(*) as baris,
       array_agg(id) as id_bentrok,
       array_agg(nominal) as nominal
  from koperasi.kasir_entry
 where client_op_id = id
   and jenis = 'transport'
   and arah  = 'keluar'
   and keterangan like 'Operasional % karyawan'
   and dihapus_at is null
 group by 1, 2
having count(*) > 1;

-- 2.3  PENJAGA UNIK (b): kunci tujuan sudah dipakai baris LAIN.
--      Inilah baris-baris yang dibuat RPC saat Anda menguji. Kalau ada,
--      jalankan §3B lebih dulu. Kolom `selisih` memperlihatkan apakah
--      angka hasil RPC cocok dengan catatan aslinya — kalau tidak nol,
--      itu sinyal kesetiaan migrasi `operasional_harian`, bukan sekadar
--      urusan kunci.
select k.cabang_id, k.tanggal,
       k.id            as id_warisan,
       k.nominal       as nominal_warisan,
       d.id            as id_dari_rpc,
       d.nominal       as nominal_dari_rpc,
       d.nominal - k.nominal as selisih
  from koperasi.kasir_entry k
  join koperasi.kasir_entry d
    on d.client_op_id = md5('auto_ops:' || k.cabang_id || ':' || k.tanggal::text)::uuid
   and d.id <> k.id
 where k.client_op_id = k.id
   and k.jenis = 'transport'
   and k.arah  = 'keluar'
   and k.keterangan like 'Operasional % karyawan'
   and k.dihapus_at is null
 order by k.tanggal;


-- #########################################################################
-- #   3A. BACKFILL — untuk kandidat yang kunci tujuannya MASIH KOSONG      #
-- #   Kalau §2.3 ada isinya, jalankan §3B lebih dulu, lalu ulangi §3A.     #
-- #########################################################################

begin;

do $$
declare
  v_ganda   int;
  v_bentrok int;
  v_ubah    int;
begin
  -- Penjaga (a) — dua kandidat satu hari. Menolak jalan, bukan menebak.
  select count(*) into v_ganda from (
    select 1 from koperasi.kasir_entry
     where client_op_id = id and jenis = 'transport' and arah = 'keluar'
       and keterangan like 'Operasional % karyawan' and dihapus_at is null
       and cabang_id is not null and tanggal is not null
     group by cabang_id, tanggal having count(*) > 1
  ) t;
  if v_ganda > 0 then
    raise exception
      'Ada % pasang (cabang,tanggal) dengan lebih dari satu kandidat. '
      'Jalankan §2.2, tentukan mana yang benar, baru ulangi.', v_ganda;
  end if;

  -- Penjaga (b) — kunci tujuan sudah dipakai baris lain.
  select count(*) into v_bentrok
    from koperasi.kasir_entry k
    join koperasi.kasir_entry d
      on d.client_op_id = md5('auto_ops:' || k.cabang_id || ':' || k.tanggal::text)::uuid
     and d.id <> k.id
   where k.client_op_id = k.id and k.jenis = 'transport' and k.arah = 'keluar'
     and k.keterangan like 'Operasional % karyawan' and k.dihapus_at is null;
  if v_bentrok > 0 then
    raise exception
      'Kunci tujuan sudah dipakai % baris lain (kemungkinan hasil uji RPC). '
      'Jalankan §3B lebih dulu.', v_bentrok;
  end if;

  -- Baru menulis. `cabang_id`/`tanggal` keduanya NOT NULL di skema, tetapi
  -- syaratnya tetap ditulis: kalau salah satunya NULL, `||` menghasilkan
  -- NULL dan client_op_id-nya jadi kosong tanpa satu pun galat.
  update koperasi.kasir_entry k
     set client_op_id = md5('auto_ops:' || k.cabang_id || ':' || k.tanggal::text)::uuid
   where k.client_op_id = k.id
     and k.jenis = 'transport'
     and k.arah  = 'keluar'
     and k.keterangan like 'Operasional % karyawan'
     and k.dihapus_at is null
     and k.cabang_id is not null
     and k.tanggal   is not null;

  get diagnostics v_ubah = row_count;
  raise notice '019: % baris diselaraskan kuncinya.', v_ubah;
end;
$$;

commit;


-- #########################################################################
-- #   3B. RESOLUSI DUPLIKAT — hanya bila §2.3 ada isinya                   #
-- #   Baris hasil uji RPC dilepas kuncinya dan di-soft-delete.             #
-- #########################################################################
--
-- Yang DIPERTAHANKAN adalah baris warisan, bukan baris RPC. Alasannya
-- `created_at`: baris warisan membawa waktu pencatatan asli dari RTDB,
-- sedangkan baris RPC bertanggal saat pengujian. RPC sendiri dirancang
-- mempertahankan `createdAt` lama (kasirApi.js:681), jadi membuang baris
-- warisan justru melawan perilaku yang ditiru.
--
-- `client_op_id` di-NULL-kan karena soft delete TIDAK membebaskan UNIQUE —
-- barisnya masih ada, jadi kuncinya masih terpakai. Kolomnya nullable
-- (001a:118), jadi ini sah. Barisnya sendiri TETAP ADA sebagai jejak.

/*  SENGAJA DIKOMENTARI. Lepas komentar hanya bila §2.3 ada isinya.

begin;

do $$
declare v_hapus int;
begin
  with korban as (
    select d.id, k.nominal as nominal_warisan, d.nominal as nominal_rpc
      from koperasi.kasir_entry k
      join koperasi.kasir_entry d
        on d.client_op_id = md5('auto_ops:' || k.cabang_id || ':' || k.tanggal::text)::uuid
       and d.id <> k.id
     where k.client_op_id = k.id and k.jenis = 'transport' and k.arah = 'keluar'
       and k.keterangan like 'Operasional % karyawan' and k.dihapus_at is null
  )
  update koperasi.kasir_entry e
     set dihapus_at   = now(),
         -- NULL, bukan auth.uid(): ini migrasi yang dijalankan service_role,
         -- bukan tindakan seseorang. Mengisinya dengan auth.uid() (yang di
         -- SQL Editor memang NULL) hanya menyamarkan asal-usulnya.
         dihapus_oleh = null,
         -- Angka aslinya ikut dicatat: kalau nanti ternyata baris RPC yang
         -- benar, jejaknya masih terbaca tanpa membongkar backup.
         alasan_hapus = format(
           '019: duplikat hasil uji rpc_sync_operasional_transport; '
           'baris warisan dipertahankan (nominal warisan %s, nominal RPC %s)',
           korban.nominal_warisan, korban.nominal_rpc),
         client_op_id = null
    from korban
   where e.id = korban.id
     and e.dihapus_at is null;

  get diagnostics v_hapus = row_count;
  raise notice '019B: % baris hasil uji RPC dilepas kuncinya dan dihapus lunak.', v_hapus;
end;
$$;

commit;

-- Lalu ULANGI §3A.

*/


-- #########################################################################
-- #   4. UJI SESUDAH                                                       #
-- #########################################################################

-- 4.1  Tidak ada lagi kandidat tersisa. HARUS 0.
select count(*) as sisa_kandidat
  from koperasi.kasir_entry
 where client_op_id = id
   and jenis = 'transport' and arah = 'keluar'
   and keterangan like 'Operasional % karyawan'
   and dihapus_at is null;

-- 4.2  Satu hari = satu baris hidup. HARUS kosong.
select cabang_id, tanggal, count(*)
  from koperasi.kasir_entry
 where jenis = 'transport' and arah = 'keluar'
   and keterangan like 'Operasional % karyawan'
   and dihapus_at is null
 group by 1, 2 having count(*) > 1;

-- 4.3  Kunci setiap baris hidup COCOK dengan rumus B-4. HARUS 0.
--      Ini yang menguji ketergantungan DateStyle di §0: kalau `::text`
--      menghasilkan bentuk lain, baris ini yang memperlihatkannya.
select count(*) as kunci_tidak_cocok
  from koperasi.kasir_entry
 where jenis = 'transport' and arah = 'keluar'
   and keterangan like 'Operasional % karyawan'
   and dihapus_at is null
   and client_op_id is distinct from
       md5('auto_ops:' || cabang_id || ':' || tanggal::text)::uuid;

-- 4.4  UJI PERILAKU — ini penutupnya, jangan dilewati.
--      Panggil RPC untuk hari yang entri warisannya SUDAH ADA, lewat REST
--      dengan JWT kasir_unit (bukan SQL Editor — di sana auth.uid() NULL
--      dan gerbang perannya tidak teruji):
--
--        curl -X POST "$SUPA_URL/rest/v1/rpc/rpc_sync_operasional_transport" \
--          -H "apikey: $ANON" -H "Authorization: Bearer $JWT_KASIR_UNIT" \
--          -H "Content-Type: application/json" \
--          -d '{"p_tanggal":"<TANGGAL_YANG_SUDAH_ADA>"}'
--
--      Harapan:
--        * uuid yang dikembalikan = `id` baris WARISAN (bukan uuid baru);
--        * jumlah baris hidup untuk hari itu TETAP 1 (ulangi §4.2);
--        * `created_at` baris itu TIDAK berubah;
--        * panggilan KEDUA mengembalikan uuid yang sama persis.
--
--      Bandingkan uuid-nya:
--        select id, created_at, nominal from koperasi.kasir_entry
--         where client_op_id = md5('auto_ops:<CABANG>:<TANGGAL>')::uuid;


-- #########################################################################
-- #   5. TEMPAT DI URUTAN CUT-OVER                                         #
-- #########################################################################
--
--   018 B-1  (terpasang)
--     → 016a_operasional_harian.sql
--       → migrate_operasional_harian.js  (dry-run, lalu --execute)
--         → 015 BATCH B-4                (RPC)
--           → 019 §2 → §3A (dan §3B bila perlu) → §4     ← BERKAS INI
--             → baru web dialihkan memanggil RPC
--
-- Menjalankan 019 lebih awal tidak mungkin: kunci tujuannya mengikuti rumus
-- yang baru ada di B-4. Menjalankannya lebih lambat — yaitu setelah web
-- dialihkan — berarti setiap hari kerja menambah satu baris ganda, dan
-- rekap kasir hari-hari itu harus dibersihkan belakangan satu per satu.


-- #########################################################################
-- #   6. JALAN PULANG                                                      #
-- #########################################################################
--
-- §3A dapat dibalik selama `id`-nya belum berubah (dan id tidak pernah
-- berubah):
--
--     update koperasi.kasir_entry
--        set client_op_id = id
--      where jenis = 'transport' and arah = 'keluar'
--        and keterangan like 'Operasional % karyawan'
--        and dihapus_at is null
--        and client_op_id = md5('auto_ops:' || cabang_id || ':' || tanggal::text)::uuid;
--
-- §3B dapat dibalik dengan mengosongkan `dihapus_at`/`dihapus_oleh`/
-- `alasan_hapus` pada baris yang `alasan_hapus`-nya diawali '019:' — TETAPI
-- `client_op_id`-nya sudah NULL dan tidak bisa dikembalikan dari baris itu
-- sendiri. Kalau perlu memulihkannya, nilainya = `id` baris tersebut hanya
-- bila ia warisan migrate.js; baris yang dibuat RPC tidak punya nilai lama
-- yang bermakna. Karena itu §3B dipisah dan dikomentari.


-- #########################################################################
-- #   CATATAN                                                              #
-- #########################################################################
-- Berkas ini BELUM PERNAH DIJALANKAN. Tidak ada PostgreSQL di sisi penulis;
-- sintaksnya belum divalidasi server. Temuan duplikasinya adalah pengujian
-- ANDA — §0 adalah penelusuran sebabnya di kode (migrate.js:973/983 vs
-- 015 B-4), bukan reproduksi tandingan.
-- #########################################################################
