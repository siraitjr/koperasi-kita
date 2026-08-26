-- =========================================================================
-- KOPERASI KITA — 017: BIAYA PEMERIKSAAN RLS
-- Menutup penghambat Tahap B: v_buku_pokok 43.603 ms lewat PostgREST,
-- 374 ms sebagai superuser.
-- RANCANGAN — BELUM PERNAH DIJALANKAN di instance mana pun.
-- =========================================================================
--
-- Prasyarat: 001 → 001a → 002 sudah terpasang.
-- Berkas ini TIDAK mengubah siapa boleh melihat apa. Setiap penulisan ulang
-- di bawah disertai alasan kenapa hasil booleannya identik, dan §5 memuat
-- uji diferensial yang membuktikannya baris per baris sebelum Anda percaya.
--
--   BATCH 1  boleh_lihat_cabang tanpa pemindaian penuh   (wajib)
--   BATCH 2  hentikan evaluasi RLS bersarang             (wajib)
--   BATCH 3  memoisasi identitas per transaksi           (opsional)
--   BATCH 4  denormalisasi cabang_id ke pembayaran       (cadangan)
--   LAMPIRAN identitas dari JWT                          (tidak aktif)
--
-- Jalankan SATU batch, ukur, baru lanjut. Berhenti begitu §5 sudah < 1 detik;
-- batch sisanya tidak perlu dijalankan.
--
-- =========================================================================
-- 0. DIAGNOSIS — apa yang sebenarnya mahal
-- =========================================================================
--
-- Temuan Anda benar sampai butirnya, dengan satu tambahan yang menjelaskan
-- kenapa angkanya sampai 43 detik dan bukan 4.
--
-- (a) `koperasi_priv.role()` dan `.cabang()` memang lookup tabel
--     (002:51-66). STABLE tidak berarti dimemoisasi: PostgreSQL hanya
--     menjanjikan hasil tetap dalam satu statement, ia TIDAK menyimpan
--     hasilnya antar baris. Jadi benar, satu panggilan = satu query kecil.
--
-- (b) Yang jauh lebih mahal justru `boleh_lihat_cabang` (002:102-105):
--
--       select p_cabang in (select koperasi_priv.cabang_terlihat())
--
--     `cabang_terlihat()` (002:86-99) adalah UNION tiga cabang yang
--     MEMINDAI SELURUH tabel `cabang`, dan cabang pertamanya memanggil
--     `is_pengawas()` → `role()` sebagai qualifier baris. Dengan N cabang,
--     satu panggilan `boleh_lihat_cabang` ≈ N lookup app_user + satu
--     seq scan + dedup UNION. Itulah 3 ms per evaluasi, bukan lookup
--     tunggal yang wajar (~2 µs).
--
-- (c) Tambahan yang belum ada di diagnosis Anda — dan ini pengalinya:
--     002:149 menyalakan `force row level security` di SEMUA tabel. Karena
--     itu RLS tetap berlaku di dalam subquery policy. `pembayaran_baca`
--     (002:313-320) ber-EXISTS ke `pinjaman` dan `nasabah`, dan kedua tabel
--     itu punya policy sendiri yang JUGA memanggil `boleh_lihat_cabang`.
--     Satu baris pembayaran karena itu memicu tiga evaluasi bersarang:
--
--       pembayaran_baca                                   (002:313)
--         └─ RLS pinjaman_baca      → boleh_lihat_cabang  (002:257)
--              └─ RLS nasabah_baca  → boleh_lihat_cabang  (002:210)
--         └─ boleh_lihat_cabang (predikat eksplisitnya)   (002:319)
--
--     13.165 baris × 3 evaluasi × (N lookup + seq scan) = 43 detik.
--
-- (d) `force row level security` juga menjawab kejanggalan yang tersirat di
--     laporan Anda: `v_pinjaman_saldo` (001:535) TIDAK memakai
--     security_invoker, jadi seharusnya ia berjalan sebagai pemilik dan
--     melewati RLS. Ia tidak melewatinya justru karena `force`. Ini kabar
--     baik — artinya SubPlan yang Anda lihat bukan gejala security_invoker
--     yang salah pasang, dan catatan 015 §B-4b tetap terpisah dari masalah
--     ini.
--
-- (e) Bukan masalah indeks. `pembayaran_pinjaman_idx` (001:495),
--     `pinjaman_nasabah_idx` (001:336), dan UNIQUE pada
--     `pembayaran_koreksi.pembayaran_id` (001:526) sudah ada. Menambah
--     indeks tidak akan menolong; yang mahal adalah jumlah panggilan.
--
-- Urutan perbaikannya karena itu: (b) dulu — paling besar, paling murah,
-- dan sepenuhnya setara. Lalu (c). Sisanya kemungkinan besar tidak perlu.
--
-- Catatan tentang usul Anda memakai `auth.jwt() -> 'user_metadata'`:
-- lihat LAMPIRAN. Singkatnya — sumber itu **bisa ditulis sendiri oleh
-- pengguna**, jadi memakainya sebagai dasar keputusan izin membuka lubang
-- naik-hak. Kalau jalur JWT tetap dipilih, sumbernya harus `app_metadata`.


-- #########################################################################
-- #                                                                       #
-- #   BATCH 1 — boleh_lihat_cabang TANPA PEMINDAIAN PENUH   (WAJIB)       #
-- #   Perubahan terbesar, dan tidak menyentuh satu pun policy.            #
-- #                                                                       #
-- #########################################################################

begin;

-- Salinan versi 002 disimpan apa adanya. Dua gunanya: jadi pembanding di
-- uji diferensial §5, dan jadi jalan pulang di §6 tanpa perlu membuka 002.
create or replace function koperasi_priv.boleh_lihat_cabang_lama(p_cabang text)
returns boolean language sql stable parallel safe
set search_path = ''
as $$ select p_cabang in (select koperasi_priv.cabang_terlihat()) $$;

-- -------------------------------------------------------------------------
-- Kenapa hasilnya identik
-- -------------------------------------------------------------------------
-- `cabang_terlihat()` (002:86-99) adalah UNION tiga cabang:
--
--   1) select c.id from cabang c where is_pengawas() or role() = 'koordinator'
--   2) select c.id from cabang c where c.pimpinan_id = auth.uid()
--   3) select u.cabang_id from app_user u
--       where u.id = auth.uid() and u.cabang_id is not null
--
-- `p_cabang in (select …)` bernilai true bila p_cabang muncul di salah satu.
-- Menanyakan "apakah p_cabang muncul" tidak menuntut daftarnya dibangun
-- lebih dulu — cukup periksa p_cabang itu sendiri:
--
--   * (1) dan (2) sama-sama bersyarat `c.id = p_cabang` → satu lookup PK
--     pada `cabang`, bukan seq scan. Syarat keanggotaan tabel `cabang`
--     tetap dipertahankan (kalau p_cabang tidak ada di tabel itu, versi
--     lama pun mengembalikan false, termasuk untuk pengawas).
--   * (3) menjadi satu lookup PK pada `app_user`.
--
-- SATU HAL YANG SENGAJA DIPERTAHANKAN APA ADANYA: cabang (3) di 002 TIDAK
-- menyaring `u.aktif`, sedangkan `role()` menyaringnya. Akibatnya user
-- nonaktif tetap melihat cabangnya sendiri. Itu kemungkinan besar tidak
-- disengaja, tetapi memperbaikinya di sini akan MENGUBAH siapa melihat apa —
-- persis yang Anda larang. Dibiarkan, dan dicatat sebagai temuan terpisah
-- (lihat §7).
create or replace function koperasi_priv.boleh_lihat_cabang(p_cabang text)
returns boolean
language sql stable security definer parallel safe
set search_path = ''
as $$
  select
    exists (
      select 1 from koperasi.cabang c
       where c.id = p_cabang
         and (koperasi_priv.role() in ('pengawas', 'koordinator')
              or c.pimpinan_id = auth.uid())
    )
    or exists (
      select 1 from koperasi.app_user u
       where u.id = auth.uid()
         and u.cabang_id = p_cabang        -- NULL tidak pernah cocok = (3)
    )
$$;

-- `cabang_terlihat()` TIDAK dihapus: 002 mengekspornya ke `authenticated`
-- (002:122) dan aturan repo melarang menghapus yang masih mungkin dipanggil.
-- Ia hanya tidak lagi berada di jalur panas. Dibiarkan persis seperti
-- aslinya.

commit;

-- ---- UKUR DI SINI SEBELUM LANJUT ----------------------------------------
-- Jalankan §5. Kalau sudah < 1 detik, BERHENTI; batch 2-4 tidak perlu.


-- #########################################################################
-- #                                                                       #
-- #   BATCH 2 — HENTIKAN EVALUASI RLS BERSARANG            (WAJIB)        #
-- #                                                                       #
-- #########################################################################

begin;

-- -------------------------------------------------------------------------
-- Kenapa hasilnya identik — ini bagian yang paling perlu diyakinkan
-- -------------------------------------------------------------------------
-- Empat policy baca memakai predikat yang SAMA PERSIS, hanya beda titik
-- masuk:
--
--   nasabah_baca   (002:210)  admin_id = auth.uid() or boleh_lihat_cabang(cabang_id)
--   pinjaman_baca  (002:257)  exists(nasabah n : n.id = pinjaman.nasabah_id  and ⟨predikat di atas⟩)
--   pembayaran_baca(002:313)  exists(pinjaman⋈nasabah : p.id = pembayaran.pinjaman_id and ⟨…⟩)
--   simpanan_baca  (002:356)  exists(nasabah n : n.id = simpanan.nasabah_id   and ⟨…⟩)
--   jadwal_baca    (002:371)  exists(pinjaman⋈nasabah : p.id = jadwal.pinjaman_id and ⟨…⟩)
--
-- Karena `force row level security` aktif, subquery di dalam
-- `pembayaran_baca` ikut disaring `pinjaman_baca` lalu `nasabah_baca`.
-- Tetapi keduanya menyaring dengan predikat yang SAMA dengan yang sudah
-- ditulis eksplisit di dalam EXISTS itu. Menyaring dua kali dengan
-- predikat identik menghasilkan himpunan yang sama dengan menyaring sekali:
--
--   ⟨P⟩ ∧ ⟨P⟩ ∧ ⟨P⟩  ≡  ⟨P⟩
--
-- Jadi memindahkan EXISTS ke fungsi SECURITY DEFINER — sehingga RLS tidak
-- masuk lagi ke dalamnya — menghapus dua evaluasi yang mubazir, bukan
-- melonggarkan satu pun izin. Yang hilang hanya pekerjaan, bukan pembatasan.
--
-- Syarat kebenaran argumen ini: predikat ketiga policy itu harus tetap
-- identik selamanya. Kalau suatu hari `nasabah_baca` diperketat sendirian,
-- fungsi di bawah TIDAK ikut ketat. §5.2 menguji tepat hal itu, dan
-- sebaiknya dijalankan ulang setiap kali 002 disunting.

create or replace function koperasi_priv.boleh_lihat_nasabah(p_nasabah uuid)
returns boolean
language sql stable security definer parallel safe
set search_path = ''
as $$
  select exists (
    select 1 from koperasi.nasabah n
     where n.id = p_nasabah
       and (n.admin_id = auth.uid()
            or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
  )
$$;

create or replace function koperasi_priv.boleh_lihat_pinjaman(p_pinjaman uuid)
returns boolean
language sql stable security definer parallel safe
set search_path = ''
as $$
  select exists (
    select 1
      from koperasi.pinjaman p
      join koperasi.nasabah  n on n.id = p.nasabah_id
     where p.id = p_pinjaman
       and (n.admin_id = auth.uid()
            or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
  )
$$;

revoke all on function
  koperasi_priv.boleh_lihat_nasabah(uuid),
  koperasi_priv.boleh_lihat_pinjaman(uuid),
  koperasi_priv.boleh_lihat_cabang_lama(text)
from public, anon;

grant execute on function
  koperasi_priv.boleh_lihat_nasabah(uuid),
  koperasi_priv.boleh_lihat_pinjaman(uuid)
to authenticated;

-- -------------------------------------------------------------------------
-- Policy ditulis ulang — HANYA yang SELECT
-- -------------------------------------------------------------------------
-- Policy INSERT/UPDATE sengaja TIDAK disentuh. Jalur tulis bukan penyebab
-- lambatnya (satu baris per permintaan, bukan 13.165), dan menyentuhnya
-- berarti mempertaruhkan gerbang tulis demi keuntungan nol.

drop policy if exists pinjaman_baca on koperasi.pinjaman;
create policy pinjaman_baca on koperasi.pinjaman
  for select to authenticated
  using (koperasi_priv.boleh_lihat_nasabah(nasabah_id));

drop policy if exists pembayaran_baca on koperasi.pembayaran;
create policy pembayaran_baca on koperasi.pembayaran
  for select to authenticated
  using (koperasi_priv.boleh_lihat_pinjaman(pinjaman_id));

drop policy if exists simpanan_baca on koperasi.simpanan;
create policy simpanan_baca on koperasi.simpanan
  for select to authenticated
  using (koperasi_priv.boleh_lihat_nasabah(nasabah_id));

drop policy if exists jadwal_baca on koperasi.jadwal_cicilan;
create policy jadwal_baca on koperasi.jadwal_cicilan
  for select to authenticated
  using (koperasi_priv.boleh_lihat_pinjaman(pinjaman_id));

-- `nasabah_baca` TIDAK diubah. Ia sudah predikat kolom polos tanpa EXISTS,
-- jadi tidak ada sarang yang bisa dihapus — dan ia yang menjadi acuan
-- kebenaran bagi keempat fungsi di atas.

commit;

-- ---- UKUR LAGI ----------------------------------------------------------
-- Jalankan §5. Hampir pasti sudah jauh di bawah 1 detik di titik ini.


-- #########################################################################
-- #                                                                       #
-- #   BATCH 3 — MEMOISASI IDENTITAS PER TRANSAKSI        (OPSIONAL)       #
-- #   Jalankan HANYA bila §5 masih di atas 1 detik setelah batch 2.       #
-- #                                                                       #
-- #########################################################################
--
-- Setelah batch 1-2, `role()` masih dipanggil sekali per baris — tetapi
-- kini berupa lookup PK tunggal (~2 µs), jadi 13.165 baris ≈ 30 ms. Itu
-- sudah tidak berarti. Batch ini hanya untuk kasus tabel jauh lebih besar.
--
-- Cara kerjanya: simpan hasil lookup pertama di GUC ber-lingkup TRANSAKSI.
-- PostgREST membungkus setiap permintaan dalam satu transaksi, jadi cache
-- hidup persis selama satu permintaan lalu hilang dengan sendirinya.
--
-- ⚠ DUA HAL YANG HARUS DISADARI SEBELUM MENJALANKAN INI:
--
--   1. `is_local = true` WAJIB. Dengan `false`, nilainya menempel pada
--      KONEKSI. Supabase memakai connection pooling, jadi identitas satu
--      pengguna akan bocor ke permintaan pengguna berikutnya yang kebagian
--      koneksi itu — kebocoran data lintas-pengguna, bukan sekadar bug.
--      Jangan pernah mengubah argumen ketiga itu.
--
--   2. `parallel unsafe` WAJIB, dan ini biayanya. `set_config` tidak boleh
--      dijalankan di worker paralel (gagal dengan "cannot set parameters
--      during a parallel operation"). Menandainya begitu berarti setiap
--      query yang menyentuh predikat ini KEHILANGAN rencana paralel.
--      Untuk 13.165 baris itu tidak masalah; untuk agregat berjuta baris,
--      batch ini bisa membuat lebih lambat, bukan lebih cepat. Ukur.

/*  SENGAJA DIKOMENTARI. Lepas komentar hanya bila §5 masih > 1 detik.

begin;

create or replace function koperasi_priv.role()
returns koperasi.user_role
language plpgsql stable security definer parallel unsafe
set search_path = ''
as $$
declare
  v text := nullif(current_setting('koperasi.cache_role', true), '');
begin
  if v is null then
    select u.role::text into v
      from koperasi.app_user u
     where u.id = auth.uid() and u.aktif;
    -- '∅' membedakan "sudah dicari, hasilnya tidak ada" dari "belum dicari".
    -- Tanpa penanda ini, user tanpa baris app_user akan dicari ulang tiap
    -- baris dan batch ini justru tidak menolong siapa pun.
    perform set_config('koperasi.cache_role', coalesce(v, '∅'), true);
    v := coalesce(v, '∅');
  end if;
  return nullif(v, '∅')::koperasi.user_role;
end;
$$;

create or replace function koperasi_priv.cabang()
returns text
language plpgsql stable security definer parallel unsafe
set search_path = ''
as $$
declare
  v text := nullif(current_setting('koperasi.cache_cabang', true), '');
begin
  if v is null then
    select u.cabang_id into v
      from koperasi.app_user u
     where u.id = auth.uid() and u.aktif;
    perform set_config('koperasi.cache_cabang', coalesce(v, '∅'), true);
    v := coalesce(v, '∅');
  end if;
  return nullif(v, '∅');
end;
$$;

commit;

*/


-- #########################################################################
-- #                                                                       #
-- #   BATCH 4 — DENORMALISASI cabang_id KE pembayaran    (CADANGAN)       #
-- #   Jalankan HANYA bila batch 1-3 belum cukup.                          #
-- #                                                                       #
-- #########################################################################
--
-- Ini opsi cadangan yang Anda sebut sendiri. Ditaruh paling belakang karena
-- ia satu-satunya yang MENAMBAH DATA, dan data turunan adalah data yang bisa
-- melenceng. Batch 1-2 mengubah cara bertanya; batch ini mengubah apa yang
-- disimpan, dan itu selalu lebih mahal untuk dipulihkan kalau keliru.
--
-- Sesudahnya `pembayaran_baca` tidak perlu join sama sekali:
--   using (admin_id = auth.uid() or koperasi_priv.boleh_lihat_cabang(cabang_id))
--
-- Prasyarat kebenarannya: nilai turunan harus TIDAK PERNAH tertinggal.
-- `pembayaran` append-only (001:516) sehingga barisnya sendiri tidak
-- berubah — tetapi `nasabah.cabang_id` dan `nasabah.admin_id` BISA berubah
-- (mutasi cabang, pindah admin). Karena itu triggernya ada DUA: satu mengisi
-- saat insert, satu merambatkan saat nasabah pindah. Melewatkan yang kedua
-- membuat pembayaran lama tetap terlihat oleh cabang lama — kebocoran yang
-- senyap.

/*  SENGAJA DIKOMENTARI. Lepas komentar hanya bila batch 1-3 belum cukup.

begin;

alter table koperasi.pembayaran
  add column if not exists cabang_id text references koperasi.cabang(id),
  add column if not exists admin_id  uuid references koperasi.app_user(id);

-- Isi awal. Jalankan SEBELUM policy diganti; selama kolomnya masih NULL,
-- policy baru akan menyembunyikan semua baris.
update koperasi.pembayaran b
   set cabang_id = n.cabang_id,
       admin_id  = n.admin_id
  from koperasi.pinjaman p
  join koperasi.nasabah  n on n.id = p.nasabah_id
 where p.id = b.pinjaman_id
   and (b.cabang_id is distinct from n.cabang_id
        or b.admin_id is distinct from n.admin_id);

create index if not exists pembayaran_cabang_idx on koperasi.pembayaran (cabang_id);
create index if not exists pembayaran_admin_idx  on koperasi.pembayaran (admin_id);

-- (1) isi saat baris pembayaran dibuat
create or replace function koperasi.tg_pembayaran_isi_cabang()
returns trigger language plpgsql security definer
set search_path = ''
as $$
begin
  select n.cabang_id, n.admin_id into new.cabang_id, new.admin_id
    from koperasi.pinjaman p
    join koperasi.nasabah  n on n.id = p.nasabah_id
   where p.id = new.pinjaman_id;
  return new;
end;
$$;

drop trigger if exists pembayaran_isi_cabang on koperasi.pembayaran;
create trigger pembayaran_isi_cabang
  before insert on koperasi.pembayaran
  for each row execute function koperasi.tg_pembayaran_isi_cabang();

-- (2) rambatkan saat nasabah pindah cabang / ganti admin
create or replace function koperasi.tg_nasabah_rambat_cabang()
returns trigger language plpgsql security definer
set search_path = ''
as $$
begin
  update koperasi.pembayaran b
     set cabang_id = new.cabang_id,
         admin_id  = new.admin_id
    from koperasi.pinjaman p
   where p.id = b.pinjaman_id
     and p.nasabah_id = new.id;
  return new;
end;
$$;

drop trigger if exists nasabah_rambat_cabang on koperasi.nasabah;
create trigger nasabah_rambat_cabang
  after update of cabang_id, admin_id on koperasi.nasabah
  for each row
  when (old.cabang_id is distinct from new.cabang_id
        or old.admin_id is distinct from new.admin_id)
  execute function koperasi.tg_nasabah_rambat_cabang();

-- ⚠ Trigger (2) menulis ke `pembayaran`, yang punya trigger append-only
-- `pembayaran_append_only` (001:516) yang MENOLAK setiap UPDATE. Jadi
-- SEBELUM batch ini bisa dipakai, trigger itu harus diizinkan melewatkan
-- perubahan yang hanya menyentuh kolom turunan ini — dan itu melonggarkan
-- jaminan append-only yang sengaja dipasang di 001 §4.
--
-- Inilah alasan sesungguhnya batch ini ditaruh terakhir: ongkosnya bukan
-- ruang penyimpanan, melainkan salah satu jaminan integritas yang paling
-- berharga di skema ini. JANGAN jalankan sebelum §5 membuktikan batch 1-2
-- benar-benar tidak cukup.

commit;

*/


-- #########################################################################
-- #   5. VERIFIKASI                                                       #
-- #########################################################################
--
-- 5.1 dan 5.2 memakai simulasi peran. Menjalankannya di SQL Editor apa
-- adanya TIDAK menguji apa pun: SQL Editor memakai `service_role`, yang
-- melewati RLS sehingga semuanya selalu tampak benar dan cepat.

-- -------------------------------------------------------------------------
-- 5.1  KECEPATAN — target < 1 detik
-- -------------------------------------------------------------------------
-- Ganti <UID_ADMIN> dengan uuid admin lapangan yang sama seperti pada
-- pengukuran 43.603 ms, supaya angkanya bisa dibandingkan.

/*
begin;
  set local role authenticated;
  set local request.jwt.claim.sub = '<UID_ADMIN>';
  set local request.jwt.claims    = '{"sub":"<UID_ADMIN>","role":"authenticated"}';

  explain (analyze, buffers, verbose)
  select nasabah_id from koperasi.v_buku_pokok;
rollback;
*/

-- Yang dibaca di keluarannya, bukan cuma angka totalnya:
--   * `Execution Time` < 1000 ms                                  ← target
--   * TIDAK ada lagi `SubPlan` dengan `loops=13165`
--   * `Seq Scan on cabang` HILANG dari rencana (itu gejala (b) di §0)
--   * `Function Scan on cabang_terlihat` HILANG
-- Kalau `Execution Time` turun tetapi `loops` masih puluhan ribu, batch 1
-- bekerja dan batch 2 belum dijalankan.

-- -------------------------------------------------------------------------
-- 5.2  SEMANTIK — WAJIB, dan jalankan SEBELUM percaya 5.1
-- -------------------------------------------------------------------------
-- Kecepatan tanpa bukti kesetaraan tidak ada gunanya: policy yang salah pun
-- cepat. Blok ini membandingkan predikat LAMA dan BARU untuk setiap
-- pasangan (pengguna, baris). Harapannya: NOL selisih.

/*
-- (a) boleh_lihat_cabang lama vs baru, untuk SETIAP user × SETIAP cabang.
--     Dijalankan sebagai service_role — di sini kita menguji fungsinya,
--     bukan policy-nya, jadi bypass RLS memang yang diinginkan.
do $$
declare
  r record;
  n_beda int := 0;
begin
  for r in select id from koperasi.app_user loop
    perform set_config('request.jwt.claim.sub', r.id::text, true);
    perform set_config('request.jwt.claims',
      json_build_object('sub', r.id, 'role', 'authenticated')::text, true);

    -- Penjaga wajib: kalau auth.uid() ternyata NULL, kedua fungsi sama-sama
    -- mengembalikan false untuk semua cabang dan uji ini LULUS tanpa menguji
    -- apa pun. Kegagalan senyap semacam itu lebih buruk daripada tidak
    -- menguji sama sekali.
    if auth.uid() is distinct from r.id then
      raise exception 'Simulasi identitas gagal: auth.uid()=% diharapkan %',
        auth.uid(), r.id;
    end if;

    select count(*) into n_beda
      from koperasi.cabang c
     where koperasi_priv.boleh_lihat_cabang(c.id)
           is distinct from
           koperasi_priv.boleh_lihat_cabang_lama(c.id);

    if n_beda > 0 then
      raise exception 'SELISIH SEMANTIK: user % pada % cabang', r.id, n_beda;
    end if;
  end loop;
  raise notice 'boleh_lihat_cabang: setara untuk seluruh user × cabang';
end;
$$;

-- (b) Predikat ketiga policy masih identik? Argumen ⟨P⟩∧⟨P⟩≡⟨P⟩ di batch 2
--     hanya sah selama ketiganya sama. Bandingkan hasil fungsi baru dengan
--     predikat nasabah_baca yang ditulis apa adanya.
do $$
declare
  r record;
  n_beda int;
begin
  for r in select id from koperasi.app_user loop
    -- Keduanya diset: versi `auth.uid()` yang berbeda membaca sumber yang
    -- berbeda (`request.jwt.claims` vs `request.jwt.claim.sub`). Menyetel
    -- satu saja bisa membuat auth.uid() NULL — dan uji yang membandingkan
    -- dua fungsi yang sama-sama melihat NULL akan LULUS tanpa menguji apa pun.
    perform set_config('request.jwt.claim.sub', r.id::text, true);
    perform set_config('request.jwt.claims',
      json_build_object('sub', r.id, 'role', 'authenticated')::text, true);

    select count(*) into n_beda
      from koperasi.nasabah n
     where koperasi_priv.boleh_lihat_nasabah(n.id)
           is distinct from
           (n.admin_id = r.id or koperasi_priv.boleh_lihat_cabang(n.cabang_id));

    if n_beda > 0 then
      raise exception 'boleh_lihat_nasabah menyimpang: user %, % baris', r.id, n_beda;
    end if;
  end loop;
  raise notice 'boleh_lihat_nasabah: setara dengan predikat nasabah_baca';
end;
$$;
*/

-- -------------------------------------------------------------------------
-- 5.3  UJI SUNGGUHAN LEWAT REST — penutup, jangan dilewati
-- -------------------------------------------------------------------------
-- Simulasi `set local role` tidak persis sama dengan permintaan PostgREST
-- nyata. Ulangi perbandingan jumlah baris lewat HTTP, dengan JWT admin yang
-- sama seperti sebelum 017 dijalankan:
--
--   curl -s "$SUPA_URL/rest/v1/v_buku_pokok?select=nasabah_id" \
--     -H "apikey: $ANON" -H "Authorization: Bearer $JWT_ADMIN" | jq 'length'
--
-- Jumlah barisnya HARUS sama persis dengan sebelum 017. Berbeda sedikit pun
-- berarti izin berubah — kembalikan lewat §6 dan laporkan selisihnya.
-- Ulangi untuk satu JWT pimpinan dan satu JWT pengawas.


-- #########################################################################
-- #   6. JALAN PULANG                                                     #
-- #########################################################################
--
-- Semua yang di atas `create or replace` / `drop policy` + `create policy`,
-- jadi pemulihannya adalah menjalankan ulang bagian yang bersangkutan dari
-- 002 — tidak ada data yang hilang dan tidak ada kolom yang ditambah
-- (selama batch 4 tidak dijalankan).
--
--   Batch 1 → jalankan ulang 002:102-105, atau:
--       create or replace function koperasi_priv.boleh_lihat_cabang(p_cabang text)
--       returns boolean language sql stable parallel safe set search_path = ''
--       as $x$ select p_cabang in (select koperasi_priv.cabang_terlihat()) $x$;
--
--   Batch 2 → jalankan ulang 002:257-263, :313-320, :356-362, :371-377
--             (keempat policy SELECT itu apa adanya).
--
--   Batch 3 → jalankan ulang 002:51-66.
--
--   Batch 4 → satu-satunya yang meninggalkan jejak (dua kolom, dua indeks,
--             dua trigger, dan pelonggaran append-only). Pemulihannya
--             manual dan itu disengaja: supaya tidak dijalankan
--             sembarangan.


-- #########################################################################
-- #   7. TEMUAN SAMPINGAN — TIDAK DIPERBAIKI DI SINI                      #
-- #########################################################################
--
-- Ditemukan saat membaca 002 untuk berkas ini. Tidak satu pun disentuh,
-- karena semuanya MENGUBAH siapa melihat apa dan itu di luar izin tugas ini.
--
-- P-01  `cabang_terlihat()` cabang (3) (002:96-98) tidak menyaring
--       `u.aktif`, sedangkan `role()` menyaringnya. User yang dinonaktifkan
--       tetap bisa melihat cabangnya sendiri. Dipertahankan apa adanya di
--       batch 1. Perlu keputusan terpisah.
--
-- P-02  `pembayaran_koreksi_baca` (002:344) memakai `using (true)` — setiap
--       pengguna terautentikasi melihat SELURUH koreksi pembayaran lintas
--       cabang, termasuk `alasan`-nya. Ini lebih longgar daripada tabel
--       yang dikoreksinya. Tidak berkaitan dengan performa; dicatat agar
--       tidak hilang.
--
-- P-03  R-07 (002 §3 `app_user_ubah_diri`) masih terbuka dan Anda tunda.
--       Relevan di sini karena LAMPIRAN di bawah punya bentuk lubang yang
--       sama: keputusan izin yang bersandar pada data yang bisa ditulis
--       sendiri oleh subjeknya.


-- #########################################################################
-- #   LAMPIRAN — IDENTITAS DARI JWT (TIDAK AKTIF)                         #
-- #########################################################################
--
-- Anda meminta opsi ini sebagai jalur utama. Saya tidak menjadikannya jalur
-- utama, karena dua alasan — satu keamanan, satu karena tidak diperlukan.
--
-- ── (1) `user_metadata` TIDAK BOLEH dipakai untuk keputusan izin ──────────
--
-- Di Supabase, `user_metadata` DAPAT DITULIS OLEH PENGGUNANYA SENDIRI:
--
--     await supabase.auth.updateUser({ data: { role: 'pengawas' } })
--
-- Panggilan itu sah untuk pengguna biasa dan langsung tercermin di JWT
-- berikutnya. Kalau `koperasi_priv.role()` membacanya, seorang admin
-- lapangan bisa mengangkat dirinya sendiri jadi Pengawas dan membaca
-- seluruh cabang — tanpa menyentuh database. Itu bukan pengetatan yang
-- meleset, itu penghapusan seluruh model izin, dan bentuknya persis sama
-- dengan R-07 yang sudah Anda catat.
--
-- Yang aman adalah `app_metadata`: hanya dapat ditulis lewat admin API
-- (`auth.admin.updateUserById`) atau service_role, tidak lewat sesi
-- pengguna. Kalau jalur JWT tetap dipilih, HARUS lewat sana.
--
-- ── (2) Setelah batch 1-2, jalur JWT tidak lagi dibutuhkan ───────────────
--
-- Batch 1 menghapus seq scan; batch 2 menghapus dua evaluasi bersarang.
-- Sisanya lookup PK tunggal. JWT akan menghemat ~30 ms dari total di bawah
-- 1 detik, dengan ongkos yang tidak sebanding:
--
--   * Metadata jadi BASI sampai token disegarkan. Pengguna yang dimutasi
--     cabang, diturunkan perannya, atau DINONAKTIFKAN tetap memegang hak
--     lamanya sampai ia login ulang (Supabase: sampai refresh token
--     dipakai, lazimnya sejam). Pada sistem yang punya `force_logout` dan
--     `session_lock` (011) justru karena pencabutan akses harus SEGERA
--     berlaku, itu langkah mundur.
--   * `app_metadata` harus di-backfill untuk seluruh pengguna hasil migrasi
--     dan dijaga tetap sinkron dari DUA sumber: `app_user.role/cabang_id`
--     DAN `cabang.pimpinan_id`. Dua sumber kebenaran untuk satu fakta izin.
--
-- Kalau setelah §5 ternyata masih perlu, bentuknya seperti di bawah —
-- sengaja tidak dijalankan, dan `role()` tetap tidak dipakai sebagai satu-
-- satunya sumber: JWT hanya jalan pintas, dengan lookup tabel sebagai
-- penengah bila klaimnya tidak ada.

/*  RANCANGAN — JANGAN JALANKAN TANPA KEPUTUSAN TERPISAH.

-- a) Backfill + penjaga sinkron (dijalankan sebagai service_role).
create or replace function koperasi_priv.sinkron_klaim(p_user uuid)
returns void language plpgsql security definer
set search_path = ''
as $$
begin
  update auth.users a
     set raw_app_meta_data =
           coalesce(a.raw_app_meta_data, '{}'::jsonb)
           || jsonb_build_object(
                'koperasi_role',   u.role::text,
                'koperasi_cabang', u.cabang_id)
    from koperasi.app_user u
   where u.id = p_user and a.id = u.id;
end;
$$;

-- b) Pembacaan: JWT dulu, tabel sebagai penengah. Tanpa penengah ini,
--    pengguna yang tokennya terbit sebelum backfill kehilangan seluruh
--    aksesnya sampai login ulang.
create or replace function koperasi_priv.role()
returns koperasi.user_role
language sql stable security definer parallel safe
set search_path = ''
as $$
  select coalesce(
    nullif(auth.jwt() -> 'app_metadata' ->> 'koperasi_role', '')::koperasi.user_role,
    (select u.role from koperasi.app_user u where u.id = auth.uid() and u.aktif)
  )
$$;

-- ⚠ `aktif` TIDAK ADA di klaim, dan sengaja: menaruhnya di JWT berarti
--    penonaktifan baru berlaku setelah token kedaluwarsa. Selama cabang
--    JWT dipakai, pengguna nonaktif tetap dianggap aktif. Itu perubahan
--    semantik yang nyata — dan alasan terakhir kenapa lampiran ini tidak
--    saya aktifkan.

*/


-- #########################################################################
-- #   CATATAN                                                             #
-- #########################################################################
-- Berkas ini BELUM PERNAH DIJALANKAN. Tidak ada PostgreSQL di sisi penulis,
-- jadi sintaksnya belum divalidasi server dan tidak ada satu pun angka di
-- sini yang saya ukur sendiri — 43.603 ms dan 374 ms adalah pengukuran
-- Anda; §0 adalah penjelasan atasnya berdasarkan pembacaan 001/002, bukan
-- pengukuran tandingan.
-- #########################################################################
