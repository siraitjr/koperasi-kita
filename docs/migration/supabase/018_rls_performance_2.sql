-- =========================================================================
-- KOPERASI KITA — 018: RLS SET-BASED (lanjutan 017)
-- 43.603 ms → 8.334 ms (017 B-1) → 9.134 ms (017 B-2, memburuk) → target < 1 s
--
-- STATUS: SELESAI. Batch 1 dijalankan pemilik (dengan cast `::text[]`, §1
-- catatan 2). Hasil yang dilaporkan:
--
--     43.603 ms → 8.334 (017 B-1) → 9.134 (017 B-2) → 674,751 ms
--
--   §3  674,751 ms — di bawah target 1 detik. 64x lebih cepat dari baseline,
--       dan 13,5x dari keadaan 017.
--   §4  uji diferensial (a)(b)(c) LULUS tanpa selisih untuk seluruh user.
--   §4b anggun 741 baris; anggun + filter cabang payakumbuh 0 baris (isolasi
--       lintas-cabang tegak); pengawas cepat tanpa timeout.
--
-- SATU SISA YANG BELUM TERTUTUP, dan sebaiknya tidak dianggap tertutup:
-- pemeriksaan pengawas di §4b mentok limit halaman PostgREST (1000/5000),
-- jadi yang terbukti adalah "cepat dan tidak timeout" — BUKAN bahwa jumlah
-- barisnya sama persis dengan sebelum 018. Untuk menutupnya perlu
-- perbandingan yang tidak kena paginasi:
--
--     curl -s -I "$SUPA_URL/rest/v1/v_buku_pokok?select=nasabah_id" \
--       -H "apikey: $ANON" -H "Authorization: Bearer $JWT_PENGAWAS" \
--       -H "Prefer: count=exact" -H "Range: 0-0"
--     # baca header Content-Range: 0-0/<TOTAL>
--
-- Bandingkan <TOTAL> dengan angka yang sama sebelum 018. Uji (a)(b)(c) sudah
-- membuktikan kesetaraan predikatnya di tingkat SQL untuk SETIAP user
-- termasuk pengawas, jadi ini pemeriksaan penutup, bukan kecurigaan.
--
-- Yang tersisa di berkas ini (§5, §6) tidak perlu dijalankan: §5 hanya untuk
-- kasus target tidak tercapai, §6 hanya jalan pulang.
-- =========================================================================
--
-- Prasyarat: 001 → 001a → 002 → 017 BATCH 1 & 2 sudah terpasang.
-- FORCE RLS tetap menyala. Policy tulis tidak disentuh sama sekali.
--
--   BATCH 1  himpunan terlihat (SRF definer) + policy set-based   (wajib)
--   §3       verifikasi kecepatan
--   §4       uji diferensial lawan predikat ASLI 002
--   §5       kalau masih > 1 detik
--   §6       jalan pulang
--
-- =========================================================================
-- 0. KENAPA 017 BATCH 2 JUSTRU MEMPERLAMBAT
-- =========================================================================
--
-- Ini kesalahan saya di 017, dan penyebabnya satu kalimat:
--
--   PostgreSQL TIDAK BISA meng-inline fungsi SQL yang SECURITY DEFINER
--   atau yang punya klausa SET.
--
-- `inline_function()` (optimizer/util/clauses.c) menolak kandidat yang
-- `prosecdef = true` ATAU `proconfig <> null`. Seluruh fungsi `koperasi_priv`
-- kena keduanya: SECURITY DEFINER, dan `set search_path = ''`.
--
-- Akibatnya setiap panggilan bukan ekspresi yang menyatu ke dalam rencana,
-- melainkan eksekusi SPI penuh: cari rencana tersimpan, ambil snapshot,
-- tukar konteks keamanan (SetUserIdAndSecContext), jalankan, kembalikan.
-- Itu ratusan mikrodetik — cocok dengan ukuran Anda: 13.189 panggilan
-- `boleh_lihat_pinjaman` ≈ 6,9 s, berarti ±520 µs per panggilan. Untuk
-- pekerjaan yang isinya cuma dua lookup PK, 520 µs itu hampir seluruhnya
-- ONGKOS PEMANGGILAN, bukan ongkos pencarian.
--
-- 017 Batch 2 karena itu menukar tiga evaluasi bersarang yang murah dengan
-- satu panggilan buram yang mahal. Argumen kesetaraan semantiknya tetap
-- benar — yang salah adalah asumsi saya bahwa memindahkan predikat ke dalam
-- fungsi hanya memindahkan pekerjaan. Tidak: fungsinya jadi tembok yang
-- tidak bisa ditembus planner, dan planner kehilangan kemampuan mengubahnya
-- jadi join/hash. Itu persis yang Anda lihat di plan.
--
-- KESIMPULAN YANG MENENTUKAN ARAH 018:
-- Masalahnya bukan SECURITY DEFINER. Masalahnya SECURITY DEFINER
-- DIPANGGIL PER BARIS. Ongkos itu dibayar per panggilan, jadi obatnya bukan
-- membuat isinya lebih murah — melainkan menekan jumlah panggilan dari
-- 13.189 menjadi 1.
--
-- Karena itu saya ambil ARAH 1 (set-based), dan TIDAK mengambil 2 atau 3:
--
--   Arah 2 (GUC, 017 Batch 3) — salah lapisan. Ia memoisasi `role()` YANG
--   ADA DI DALAM panggilan, sedangkan yang mahal adalah pemanggilannya.
--   Optimis pun ia memangkas 520 µs jadi ~450 µs × 13.189 ≈ 5,9 s. Tidak
--   mendekati target, dan ongkosnya `parallel unsafe` untuk selamanya.
--
--   Arah 3 (denormalisasi, 017 Batch 4) — tidak diperlukan. Hitungan §2
--   menunjukkan arah 1 sudah cukup, dan arah 3 menuntut pelonggaran trigger
--   append-only `pembayaran` (001:516) yang merupakan salah satu jaminan
--   integritas terkuat di skema ini. Menukar jaminan permanen demi puluhan
--   milidetik yang tidak dibutuhkan adalah tukar-tambah yang buruk. Tetap
--   tersedia di 017 kalau §3 membuktikan saya keliru lagi.


-- =========================================================================
-- 1. BENTUK YANG DIPAKAI, DAN KENAPA BEDA UNTUK HIMPUNAN KECIL VS BESAR
-- =========================================================================
--
-- Kunci 018 adalah menaruh pemanggilan di tempat yang dievaluasi SEKALI,
-- lalu membuat biaya per baris jadi sekadar pencocokan.
--
-- Ada dua bentuk sekali-jalan, dan keduanya dipakai — untuk keperluan yang
-- berbeda. Salah memilih akan mengembalikan masalahnya dalam rupa lain.
--
--   (A) `x = any ((select f())::text[])`  — f mengembalikan ARRAY.
--       Sublink skalar tak berkorelasi → InitPlan, dijalankan TEPAT SEKALI.
--       Biaya per baris = pemindaian linier isi array, O(|himpunan|).
--       BAGUS untuk himpunan kecil. Untuk 5.000 elemen × 13.189 baris =
--       66 juta perbandingan — itu justru jadi hambatan baru.
--
--   (B) `x in (select f())`       — f mengembalikan SETOF.
--       Sublink tak berkorelasi → SubPlan; planner membuatnya `hashed`
--       bila keluarannya diperkirakan muat di work_mem. Tabel hash dibangun
--       SEKALI, biaya per baris = satu hash probe, O(1).
--       BAGUS untuk himpunan besar; sedikit lebih mahal di awal.
--
-- Pembagiannya:
--   cabang   → (A). Jumlahnya belasan; array linier lebih murah dari hash.
--   nasabah  → (B). Ribuan.
--   pinjaman → (B). Ribuan.
--
-- ⚠ DUA DETAIL DI (A) YANG KEDUANYA WAJIB. Bukan gaya penulisan; menghapus
--   salah satunya merusak berkas ini dengan cara yang berbeda-beda.
--
--   1. SUBLINK-nya. `= any (f())` tanpa `(select …)` membuat f() dievaluasi
--      PER BARIS — persis penyakit 017 Batch 2, hanya berpindah tempat.
--      Sublink itulah yang menjadikannya InitPlan sekali-jalan.
--
--   2. CAST `::text[]`-nya. Ini KOREKSI: versi pertama 018 menulis
--      `= any ((select f()))` tanpa cast dan GAGAL di server dengan
--          42883  operator does not exist: text = text[]
--      Sebabnya tata bahasa, bukan tipe. Pada `expr = any (…)` PostgreSQL
--      mendahulukan bentuk ANY-SUBQUERY bila isinya sublink telanjang, jadi
--      ia mencoba membandingkan `text` (satu baris) dengan `text[]` (nilai
--      yang dikembalikan fungsi) dan tidak menemukan operatornya. Cast itu
--      membuat isinya menjadi EKSPRESI, bukan sublink telanjang, sehingga
--      terpilih bentuk ANY-ARRAY yang memang dimaksud.
--
--      Cast-nya TIDAK mengubah tipe apa pun — `cabang_terlihat_arr()` sudah
--      `text[]`. Fungsinya semata memaksa cabang parser yang benar, dan
--      InitPlan-nya tetap dijalankan sekali. Jangan dihapus dengan alasan
--      "cast yang mubazir".

-- =========================================================================
-- 2. PERKIRAAN BIAYA (perkiraan saya, bukan pengukuran)
-- =========================================================================
--
--   bangun array cabang            1 panggilan   ≈   1 ms
--   bangun himpunan nasabah        1 panggilan   ≈   5 ms  (3.026 baris)
--   bangun himpunan pinjaman       1 panggilan   ≈  10 ms  (hash join)
--   3.026 hash probe di nasabah                  ≈  <1 ms
--   13.189 hash probe di pembayaran              ≈   2 ms
--                                                  ────────
--   tambahan RLS                                 ≈  20 ms
--   + dasar query sebagai superuser              =  374 ms
--                                                  ────────
--   perkiraan                                    ≈ 400 ms
--
-- Marginnya terhadap target 1 detik lebar, dan itu disengaja: perkiraan
-- saya di 017 meleset, jadi §3 yang memutuskan, bukan tabel ini.
--
-- HASILNYA: 674,751 ms, bukan ~400 ms. Perkiraan ini meleset ~275 ms, yaitu
-- tambahan RLS-nya ±14x lebih mahal dari dugaan (20 ms → ~300 ms). Arahnya
-- benar dan targetnya tercapai, tetapi angkanya jangan dipakai sebagai dasar
-- perencanaan berikutnya. Dugaan saya atas selisihnya — belum diperiksa, dan
-- tidak perlu diperiksa selama masih di bawah target: pembangunan himpunan
-- `pinjaman_terlihat` terjadi per NODE pemindaian, bukan sekali per query,
-- jadi query yang menyentuh `pembayaran` di beberapa tempat membayarnya
-- berulang.


-- #########################################################################
-- #                                                                       #
-- #   BATCH 1 — HIMPUNAN TERLIHAT + POLICY SET-BASED         (WAJIB)      #
-- #                                                                       #
-- #########################################################################

begin;

-- -------------------------------------------------------------------------
-- 1.1  Array cabang yang terlihat
-- -------------------------------------------------------------------------
-- Setara persis dengan UNION `cabang_terlihat()` (002:86-99):
--
--   1) seluruh c.id           bila role ∈ {pengawas, koordinator}
--   2) c.id                   bila c.pimpinan_id = auth.uid()
--   3) u.cabang_id            dari app_user milik sendiri, TANPA syarat
--                             keanggotaan di tabel `cabang` dan TANPA
--                             filter `aktif`
--
-- Cabang (1) dan (2) digabung karena keduanya `select c.id from cabang`.
-- `is_pengawas()` = `role() = 'pengawas'` (002:74), jadi keduanya menjadi
-- satu `role() in (...)`.
--
-- Cabang (3) DIPERTAHANKAN APA ADANYA, termasuk ketiadaan filter `aktif`
-- (temuan P-01 di 017 §7). Memperbaikinya di sini akan mengubah siapa
-- melihat apa, dan itu di luar izin tugas ini.
create or replace function koperasi_priv.cabang_terlihat_arr()
returns text[]
language sql stable security definer parallel safe
set search_path = ''
as $$
  select coalesce(array_agg(distinct t.id), '{}'::text[])
    from (
      select c.id
        from koperasi.cabang c
       where koperasi_priv.role() in ('pengawas', 'koordinator')
          or c.pimpinan_id = auth.uid()
      union
      select u.cabang_id
        from koperasi.app_user u
       where u.id = auth.uid()
         and u.cabang_id is not null
    ) t
$$;

-- -------------------------------------------------------------------------
-- 1.2  Himpunan nasabah yang terlihat
-- -------------------------------------------------------------------------
-- Isinya predikat `nasabah_baca` 002:210-215 apa adanya, hanya berbentuk
-- himpunan alih-alih diuji per baris.
--
-- `rows 5000` diberikan supaya planner punya perkiraan yang masuk akal;
-- tanpa itu ia memakai 1000 dan bisa salah memilih bentuk SubPlan. Angka
-- ini boleh disesuaikan dengan jumlah nasabah sesungguhnya — lihat §5.
create or replace function koperasi_priv.nasabah_terlihat()
returns setof uuid
language sql stable security definer parallel safe
rows 5000
set search_path = ''
as $$
  select n.id
    from koperasi.nasabah n
   where n.admin_id = auth.uid()
      or n.cabang_id = any ((select koperasi_priv.cabang_terlihat_arr())::text[])
$$;

-- -------------------------------------------------------------------------
-- 1.3  Himpunan pinjaman yang terlihat
-- -------------------------------------------------------------------------
-- Join biasa, BUKAN pemanggilan `nasabah_terlihat()`. Di dalam fungsi
-- definer, join ini terlihat penuh oleh planner dan menjadi hash join;
-- memanggil fungsi lain justru memasang tembok buram yang sama seperti 017.
create or replace function koperasi_priv.pinjaman_terlihat()
returns setof uuid
language sql stable security definer parallel safe
rows 5000
set search_path = ''
as $$
  select p.id
    from koperasi.pinjaman p
    join koperasi.nasabah  n on n.id = p.nasabah_id
   where n.admin_id = auth.uid()
      or n.cabang_id = any ((select koperasi_priv.cabang_terlihat_arr())::text[])
$$;

revoke all on function
  koperasi_priv.cabang_terlihat_arr(),
  koperasi_priv.nasabah_terlihat(),
  koperasi_priv.pinjaman_terlihat()
from public, anon;

grant execute on function
  koperasi_priv.cabang_terlihat_arr(),
  koperasi_priv.nasabah_terlihat(),
  koperasi_priv.pinjaman_terlihat()
to authenticated;

-- -------------------------------------------------------------------------
-- 1.4  Policy SELECT — set-based
-- -------------------------------------------------------------------------
-- Policy tulis TIDAK disentuh, sama seperti 017: satu baris per permintaan,
-- bukan 13.189, jadi tidak ada yang bisa dimenangkan di sana dan setiap
-- suntingan di jalur tulis mempertaruhkan gerbang izin demi nol.

drop policy if exists nasabah_baca on koperasi.nasabah;
create policy nasabah_baca on koperasi.nasabah
  for select to authenticated
  using (
    admin_id = auth.uid()
    or cabang_id = any ((select koperasi_priv.cabang_terlihat_arr())::text[])
  );

drop policy if exists pinjaman_baca on koperasi.pinjaman;
create policy pinjaman_baca on koperasi.pinjaman
  for select to authenticated
  using (nasabah_id in (select koperasi_priv.nasabah_terlihat()));

drop policy if exists pembayaran_baca on koperasi.pembayaran;
create policy pembayaran_baca on koperasi.pembayaran
  for select to authenticated
  using (pinjaman_id in (select koperasi_priv.pinjaman_terlihat()));

drop policy if exists simpanan_baca on koperasi.simpanan;
create policy simpanan_baca on koperasi.simpanan
  for select to authenticated
  using (nasabah_id in (select koperasi_priv.nasabah_terlihat()));

drop policy if exists jadwal_baca on koperasi.jadwal_cicilan;
create policy jadwal_baca on koperasi.jadwal_cicilan
  for select to authenticated
  using (pinjaman_id in (select koperasi_priv.pinjaman_terlihat()));

-- `boleh_lihat_nasabah()` / `boleh_lihat_pinjaman()` dari 017 TIDAK dihapus.
-- Keduanya sudah di-grant ke `authenticated` dan aturan repo melarang
-- membuang yang masih mungkin dipanggil. Keduanya hanya tidak lagi berada
-- di jalur panas. `boleh_lihat_cabang()` juga tetap: policy TULIS dan
-- `pengajuan_baca` masih memakainya, dan tidak satu pun disentuh 018.

commit;


-- #########################################################################
-- #   2b. PRASYARAT KEPEMILIKAN FUNGSI — periksa, jangan diasumsikan      #
-- #########################################################################
--
-- FORCE RLS membuat PEMILIK TABEL pun tunduk pada policy. Fungsi definer di
-- atas hanya kebal kalau pemiliknya punya BYPASSRLS (di Supabase: `postgres`
-- / `service_role`).
--
--   select p.proname, r.rolname, r.rolbypassrls
--     from pg_proc p join pg_roles r on r.oid = p.proowner
--    where p.pronamespace = 'koperasi_priv'::regnamespace
--    order by 1;
--
-- Harapan: `rolbypassrls = true` untuk semuanya.
--
-- Kalau ternyata false, ini TIDAK menghasilkan galat dan TIDAK mengubah
-- hasil — policy di 018 semuanya predikat kolom/himpunan yang tidak
-- menyebut ulang tabelnya sendiri, jadi tidak ada rekursi tak hingga;
-- pemindaian di dalam fungsi hanya membayar policy sekali lagi. Yang
-- terjadi cuma lebih lambat, dan §3 akan memperlihatkannya. Saya sebut ini
-- karena tidak bisa memeriksanya sendiri — bukan karena ada bukti ia salah.


-- #########################################################################
-- #   3. VERIFIKASI KECEPATAN — target < 1 detik                          #
-- #########################################################################
--
-- Jangan jalankan apa adanya di SQL Editor tanpa blok simulasi: SQL Editor
-- memakai `service_role` yang melewati RLS, sehingga selalu tampak cepat.
-- Pakai uid admin `anggun` yang sama seperti tiga pengukuran sebelumnya.

/*
begin;
  set local role authenticated;
  set local request.jwt.claim.sub = '<UID_ADMIN_ANGGUN>';
  set local request.jwt.claims    = '{"sub":"<UID_ADMIN_ANGGUN>","role":"authenticated"}';

  explain (analyze, buffers, verbose)
  select nasabah_id from koperasi.v_buku_pokok;
rollback;
*/

-- Yang dibaca di plan, berurutan menurut kepentingan:
--
--   1. `Execution Time` < 1000 ms.
--
--   2. Cari kata **`hashed SubPlan`** pada node `pembayaran` dan
--      `pinjaman`. Kalau tertulis `SubPlan` TANPA `hashed`, himpunannya
--      tidak di-hash dan dijalankan ulang — itu penyebabnya kalau masih
--      lambat. Obatnya di §5.
--
--   3. `InitPlan` untuk `cabang_terlihat_arr` muncul TEPAT SEKALI, dan
--      `Function Scan on cabang_terlihat_arr` TIDAK punya `loops` ribuan.
--      Kalau `loops` besar, sublink `(select …)` di §1 hilang.
--
--   4. TIDAK ada lagi `Function Scan on boleh_lihat_pinjaman` /
--      `boleh_lihat_nasabah` dengan `loops` 13.189 / 3.026.
--
-- Catat juga angka totalnya untuk dibandingkan: 43.603 → 8.334 → 9.134 → ?


-- #########################################################################
-- #   4. UJI DIFERENSIAL — WAJIB, jalankan SEBELUM percaya §3             #
-- #########################################################################
--
-- Pembandingnya adalah predikat ASLI 002, bukan versi 017 — supaya dua
-- lapis perubahan (017 lalu 018) diuji sekaligus terhadap titik awal. Itu
-- mungkin karena 017 Batch 1 menyimpan `boleh_lihat_cabang_lama()`
-- (017:92-95) yang isinya `cabang_terlihat()` 002 apa adanya.
--
-- Dijalankan sebagai service_role: di sini yang diuji adalah FUNGSInya,
-- jadi melewati RLS memang yang diinginkan.

/*
-- (a) cabang: array baru vs keanggotaan lama, untuk SETIAP user × cabang.
do $$
declare r record; n_beda int;
begin
  for r in select id from koperasi.app_user loop
    perform set_config('request.jwt.claim.sub', r.id::text, true);
    perform set_config('request.jwt.claims',
      json_build_object('sub', r.id, 'role', 'authenticated')::text, true);

    -- Penjaga wajib. Tanpa ini, auth.uid() NULL membuat kedua sisi
    -- sama-sama kosong dan uji LULUS tanpa menguji apa pun.
    if auth.uid() is distinct from r.id then
      raise exception 'Simulasi identitas gagal: auth.uid()=% diharapkan %',
        auth.uid(), r.id;
    end if;

    select count(*) into n_beda
      from koperasi.cabang c
     where (c.id = any ((select koperasi_priv.cabang_terlihat_arr())::text[]))
           is distinct from
           koperasi_priv.boleh_lihat_cabang_lama(c.id);

    if n_beda > 0 then
      raise exception 'SELISIH cabang: user % pada % cabang', r.id, n_beda;
    end if;
  end loop;
  raise notice 'cabang_terlihat_arr: setara 002 untuk seluruh user x cabang';
end;
$$;

-- (b) nasabah: himpunan baru vs predikat nasabah_baca 002:210-215.
do $$
declare r record; n_beda int;
begin
  for r in select id from koperasi.app_user loop
    perform set_config('request.jwt.claim.sub', r.id::text, true);
    perform set_config('request.jwt.claims',
      json_build_object('sub', r.id, 'role', 'authenticated')::text, true);
    if auth.uid() is distinct from r.id then
      raise exception 'Simulasi identitas gagal untuk %', r.id;
    end if;

    select count(*) into n_beda
      from koperasi.nasabah n
     where (n.id in (select koperasi_priv.nasabah_terlihat()))
           is distinct from
           (n.admin_id = r.id
            or koperasi_priv.boleh_lihat_cabang_lama(n.cabang_id));

    if n_beda > 0 then
      raise exception 'SELISIH nasabah: user %, % baris', r.id, n_beda;
    end if;
  end loop;
  raise notice 'nasabah_terlihat: setara predikat 002 nasabah_baca';
end;
$$;

-- (c) pinjaman: himpunan baru vs predikat pinjaman_baca 002:257-263.
--     Paling lambat dari ketiganya (user x pinjaman). Sekali jalan saja;
--     kalau perlu, batasi `for r in ... limit 20` untuk sampel dulu, lalu
--     jalankan penuh sebelum dianggap selesai.
do $$
declare r record; n_beda int;
begin
  for r in select id from koperasi.app_user loop
    perform set_config('request.jwt.claim.sub', r.id::text, true);
    perform set_config('request.jwt.claims',
      json_build_object('sub', r.id, 'role', 'authenticated')::text, true);
    if auth.uid() is distinct from r.id then
      raise exception 'Simulasi identitas gagal untuk %', r.id;
    end if;

    select count(*) into n_beda
      from koperasi.pinjaman p
     where (p.id in (select koperasi_priv.pinjaman_terlihat()))
           is distinct from
           exists (
             select 1 from koperasi.nasabah n
              where n.id = p.nasabah_id
                and (n.admin_id = r.id
                     or koperasi_priv.boleh_lihat_cabang_lama(n.cabang_id))
           );

    if n_beda > 0 then
      raise exception 'SELISIH pinjaman: user %, % baris', r.id, n_beda;
    end if;
  end loop;
  raise notice 'pinjaman_terlihat: setara predikat 002 pinjaman_baca';
end;
$$;
*/

-- -------------------------------------------------------------------------
-- 4b. UJI LEWAT REST — penutup, jangan dilewati
-- -------------------------------------------------------------------------
-- Simulasi `set local role` tidak identik dengan permintaan PostgREST nyata.
-- Bandingkan JUMLAH BARIS, bukan hanya kecepatan, dengan angka sebelum 018:
--
--   curl -s "$SUPA_URL/rest/v1/v_buku_pokok?select=nasabah_id" \
--     -H "apikey: $ANON" -H "Authorization: Bearer $JWT" | jq 'length'
--
-- Ulangi untuk JWT admin, pimpinan, dan pengawas. Ketiganya HARUS sama
-- persis dengan sebelum 018. Beda satu baris pun berarti izin berubah —
-- kembalikan lewat §6 dan laporkan selisihnya.


-- #########################################################################
-- #   5. KALAU §3 MASIH DI ATAS 1 DETIK                                   #
-- #########################################################################
--
-- Berurutan, paling murah dulu. Ukur ulang §3 di antara setiap langkah.
--
-- 5.1  Plan menyebut `SubPlan` tanpa `hashed`.
--      Planner memutuskan himpunannya tidak muat di work_mem. Naikkan untuk
--      peran API saja, bukan seluruh server:
--          alter role authenticated set work_mem = '16MB';
--          notify pgrst, 'reload config';
--      Lalu sesuaikan `rows` pada kedua SRF ke jumlah baris sesungguhnya:
--          select count(*) from koperasi.nasabah;    -- → rows N
--          select count(*) from koperasi.pinjaman;   -- → rows N
--      Perkiraan yang terlalu tinggi membuat planner menolak hashing;
--      terlalu rendah membuatnya salah memilih bentuk join.
--
-- 5.2  `Function Scan on cabang_terlihat_arr` ber-`loops` ribuan.
--      Tanda kurung ganda hilang saat menyunting. Kembalikan bentuk
--      `= any ((select …)::text[])` — lihat peringatan di §1,
--      TERMASUK cast-nya: tanpa cast bukan lambat, tapi gagal 42883.
--
-- 5.3  Pemilik fungsi tidak punya BYPASSRLS (§2b).
--      Pindahkan kepemilikan ke pemilik skema:
--          alter function koperasi_priv.nasabah_terlihat()  owner to postgres;
--          alter function koperasi_priv.pinjaman_terlihat() owner to postgres;
--          alter function koperasi_priv.cabang_terlihat_arr() owner to postgres;
--
-- 5.4  Baru setelah 5.1-5.3 habis: 017 BATCH 4 (denormalisasi).
--      Kalau sampai ke sini, kompensasinya harus disebut terang-terangan.
--      Batch 4 menulis ke `pembayaran` lewat trigger perambatan, sedangkan
--      `pembayaran_append_only` (001:516) menolak SEMUA update. Melonggarkan
--      trigger itu berarti melepas jaminan "riwayat pembayaran tidak pernah
--      berubah" — jaminan yang dipasang justru untuk menutup kelas bug
--      RTDB yang butuh `summaryRepair_HEMAT.js` untuk ditambal.
--
--      Kompensasi minimum yang harus menyertainya, bukan opsional:
--        (a) trigger append-only diubah menjadi menolak update pada SEMUA
--            kolom KECUALI `cabang_id`/`admin_id` — bukan dimatikan.
--            Bandingkan `to_jsonb(old) - 'cabang_id' - 'admin_id'` dengan
--            versi `new`-nya dan tolak kalau berbeda. Dengan begitu
--            `jumlah` dan `tanggal` tetap mustahil diubah.
--        (b) perambatan mencatat jejaknya (siapa, kapan, dari cabang mana
--            ke mana), karena ia memindahkan uang antar-cabang secara
--            pembukuan.
--        (c) uji: coba `update koperasi.pembayaran set jumlah = jumlah + 1`
--            HARUS tetap ditolak setelah (a).
--      Tanpa ketiganya, jangan jalankan Batch 4.


-- #########################################################################
-- #   6. JALAN PULANG                                                     #
-- #########################################################################
--
-- 018 hanya `create or replace function` + `drop/create policy`. Tidak ada
-- kolom, data, atau trigger yang berubah, jadi pemulihannya bersih.
--
--   Kembali ke 017 (keadaan 9.134 ms) — DUA langkah, bukan satu:
--     (i)  jalankan ulang blok policy 017 BATCH 2 apa adanya (017:236-254);
--     (ii) kembalikan `nasabah_baca` ke bentuk 002:210-215. 017 TIDAK
--          menyentuh policy itu — 018 yang pertama mengubahnya — jadi
--          melewatkan (ii) meninggalkan `nasabah` dalam bentuk 018 dan
--          rollback-nya hanya separuh:
--            drop policy if exists nasabah_baca on koperasi.nasabah;
--            create policy nasabah_baca on koperasi.nasabah
--              for select to authenticated
--              using (admin_id = auth.uid()
--                     or koperasi_priv.boleh_lihat_cabang(cabang_id));
--
--   Kembali ke 002 sepenuhnya:
--     jalankan ulang 002:102-105, :210-215, :257-263, :313-320,
--     :356-362, :371-377.
--
--   Fungsi baru 018 boleh ditinggal — tanpa policy yang memanggilnya, ia
--   tidak berpengaruh apa pun. Jangan dihapus (aturan repo).


-- #########################################################################
-- #   CATATAN                                                             #
-- #########################################################################
-- BATCH 1 sudah dijalankan pemilik, dengan satu suntingan di server: cast
-- `::text[]` pada tiga kemunculan. Suntingan itu kini masuk ke berkas ini,
-- ditambah satu kemunculan keempat di §4(a) yang belum dijalankan dan akan
-- kena galat 42883 yang sama. Sisa berkas (§3-§6) belum dijalankan, dan
-- tidak ada PostgreSQL di sisi penulis.
-- Angka 43.603 / 8.334 / 9.134 ms dan hitungan loop 13.189 / 3.026 adalah
-- pengukuran ANDA; §0 adalah penjelasan atasnya dari pembacaan kode, dan §2
-- adalah perkiraan — bukan pengukuran tandingan. Perkiraan saya di 017
-- sudah sekali meleset; §3 yang memutuskan.
-- #########################################################################
