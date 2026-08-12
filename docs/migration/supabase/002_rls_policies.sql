-- =========================================================================
-- KOPERASI KITA — ROW LEVEL SECURITY
-- Fase 1: RLS Design. RANCANGAN — BELUM DI-DEPLOY, BELUM DIJALANKAN.
-- Prasyarat: 001_schema_v2.sql
-- =========================================================================
--
-- Sumber kebenaran perilaku: rulesfirebase.txt (RTDB) + CLAUDE.md §8.
-- Setiap policy di bawah menunjuk baris rules yang ditirunya.
--
-- PRINSIP
-- -------
-- 1. DENY BY DEFAULT. RLS aktif + tidak ada policy yang cocok = tolak.
--    Berbeda dari RTDB yang mewarisi izin ke seluruh subtree — sumber
--    over-permission yang sulit diaudit.
-- 2. FORCE ROW LEVEL SECURITY. Tanpa ini, pemilik tabel melewati RLS diam-diam.
-- 3. Helper role/cabang = SECURITY DEFINER + STABLE + search_path terkunci,
--    ditaruh di `koperasi_priv` yang TIDAK di-expose PostgREST. Tanpa
--    SECURITY DEFINER, policy pada `app_user` yang membaca `app_user` akan
--    rekursi tak hingga.
-- 4. Policy dipisah PER COMMAND. `for all` menyembunyikan fakta bahwa
--    USING dipakai untuk read DAN untuk baris-lama pada UPDATE/DELETE.
-- 5. RLS mengatur BARIS MANA yang terlihat. Invarian bisnis (anti-downgrade,
--    urutan fase, idempotency) ditegakkan CONSTRAINT + TRIGGER di 001, bukan
--    di sini. Dua lapisan berbeda; jangan campur.
-- =========================================================================


-- =========================================================================
-- 0. GRANT DASAR
-- =========================================================================
revoke all on schema koperasi from public, anon;
revoke all on schema koperasi_priv from public, anon, authenticated;

grant usage on schema koperasi to authenticated;
-- `anon` tidak diberi apa pun: tidak ada data koperasi yang boleh dibaca tanpa login.
-- Cermin rulesfirebase.txt yang selalu mensyaratkan `auth != null`.

alter default privileges in schema koperasi
  revoke all on tables from public, anon;


-- =========================================================================
-- 1. HELPER (SECURITY DEFINER — melewati RLS agar tidak rekursi)
-- =========================================================================

create or replace function koperasi_priv.uid()
returns uuid language sql stable parallel safe
set search_path = ''
as $$ select auth.uid() $$;

create or replace function koperasi_priv.role()
returns koperasi.user_role
language sql stable security definer parallel safe
set search_path = ''
as $$
  select u.role from koperasi.app_user u
   where u.id = auth.uid() and u.aktif
$$;

create or replace function koperasi_priv.cabang()
returns text
language sql stable security definer parallel safe
set search_path = ''
as $$
  select u.cabang_id from koperasi.app_user u
   where u.id = auth.uid() and u.aktif
$$;

-- Pengawas = role global (CLAUDE.md §8.1 "Global", rulesfirebase.txt:8
-- metadata/roles/pengawas dicek tanpa filter cabang).
create or replace function koperasi_priv.is_pengawas()
returns boolean language sql stable parallel safe
set search_path = ''
as $$ select koperasi_priv.role() = 'pengawas' $$;

-- Cabang yang boleh dilihat user saat ini.
--  * pengawas    → semua cabang
--  * koordinator → SEMUA cabang (keputusan pemilik, 12 Agu 2026: "sesuai kode
--    existing"). Ini memang cerminan RTDB: data/rulesfirebase.txt:7 dan :12
--    mengecek metadata/roles/koordinator TANPA filter cabang apa pun.
--    R-06 ditutup dengan mempertahankan perilaku lama, bukan mengetatkannya.
--    Tabel koordinator_cabang tetap ada untuk pelaporan/penugasan, tetapi
--    TIDAK lagi membatasi visibilitas.
--  * pimpinan    → cabang tempat ia terdaftar sebagai pimpinan_id
--  * lainnya     → cabang sendiri
create or replace function koperasi_priv.cabang_terlihat()
returns setof text
language sql stable security definer parallel safe
set search_path = ''
as $$
  select c.id from koperasi.cabang c
   where koperasi_priv.is_pengawas()
      or koperasi_priv.role() = 'koordinator'
  union
  select c.id from koperasi.cabang c
   where c.pimpinan_id = auth.uid()
  union
  select u.cabang_id from koperasi.app_user u
   where u.id = auth.uid() and u.cabang_id is not null
$$;

create or replace function koperasi_priv.boleh_lihat_cabang(p_cabang text)
returns boolean language sql stable parallel safe
set search_path = ''
as $$ select p_cabang in (select koperasi_priv.cabang_terlihat()) $$;

-- Pimpinan cabang tertentu — cermin rulesfirebase.txt:9
-- metadata/cabang/{cabangId}/pimpinanUid === auth.uid
create or replace function koperasi_priv.is_pimpinan_cabang(p_cabang text)
returns boolean
language sql stable security definer parallel safe
set search_path = ''
as $$
  select exists (
    select 1 from koperasi.cabang c
     where c.id = p_cabang and c.pimpinan_id = auth.uid()
  )
$$;

revoke all on function
  koperasi_priv.role(), koperasi_priv.cabang(), koperasi_priv.is_pengawas(),
  koperasi_priv.cabang_terlihat(), koperasi_priv.boleh_lihat_cabang(text),
  koperasi_priv.is_pimpinan_cabang(text)
from public, anon;

grant execute on function
  koperasi_priv.role(), koperasi_priv.cabang(), koperasi_priv.is_pengawas(),
  koperasi_priv.cabang_terlihat(), koperasi_priv.boleh_lihat_cabang(text),
  koperasi_priv.is_pimpinan_cabang(text)
to authenticated;


-- =========================================================================
-- 2. AKTIFKAN RLS DI SEMUA TABEL
-- =========================================================================
do $$
declare t text;
begin
  foreach t in array array[
    'cabang','app_user','koordinator_cabang','nasabah','pinjaman',
    'pembayaran','pembayaran_koreksi','simpanan','jadwal_cicilan',
    'pengajuan','approval_step','permintaan','jurnal_transaksi',
    'kasir_entry','sync_inbox','dokumen'
  ]
  loop
    execute format('alter table koperasi.%I enable row level security', t);
    execute format('alter table koperasi.%I force row level security', t);
  end loop;
end $$;


-- =========================================================================
-- 3. MASTER DATA
-- =========================================================================

-- metadata/cabang & metadata/admins di RTDB dibaca semua user terautentikasi
-- (CLAUDE.md §8.1 kolom "Baca utama": metadata readonly).
create policy cabang_baca on koperasi.cabang
  for select to authenticated using (true);

create policy cabang_kelola on koperasi.cabang
  for all to authenticated
  using (koperasi_priv.is_pengawas())
  with check (koperasi_priv.is_pengawas());

-- User bisa melihat dirinya sendiri + rekan satu cabang (untuk tampilan nama
-- approver, daftar admin di dashboard Pimpinan).
create policy app_user_baca on koperasi.app_user
  for select to authenticated
  using (
    id = auth.uid()
    or koperasi_priv.is_pengawas()
    or koperasi_priv.boleh_lihat_cabang(cabang_id)
  );

-- Hanya Pengawas yang mengelola user — CLAUDE.md §8.1 "User management",
-- PengawasUserManagementScreen.kt.
create policy app_user_kelola on koperasi.app_user
  for all to authenticated
  using (koperasi_priv.is_pengawas())
  with check (koperasi_priv.is_pengawas());

-- User boleh memperbarui foto profilnya sendiri (profile_photos di
-- rulesstorage.txt:52 mengizinkan self-write). Kolom lain dijaga trigger
-- terpisah — lihat catatan di akhir file.
create policy app_user_ubah_diri on koperasi.app_user
  for update to authenticated
  using (id = auth.uid())
  with check (id = auth.uid());

create policy koordinator_cabang_baca on koperasi.koordinator_cabang
  for select to authenticated using (true);

create policy koordinator_cabang_kelola on koperasi.koordinator_cabang
  for all to authenticated
  using (koperasi_priv.is_pengawas())
  with check (koperasi_priv.is_pengawas());


-- =========================================================================
-- 4. NASABAH
-- =========================================================================
-- Cermin rulesfirebase.txt:5-13 pada pelanggan/{adminUid}:
--   read/write bila auth.uid === adminUid
--              ATAU pengawas
--              ATAU koordinator
--              ATAU pimpinan dari cabang si admin
create policy nasabah_baca on koperasi.nasabah
  for select to authenticated
  using (
    admin_id = auth.uid()
    or koperasi_priv.boleh_lihat_cabang(cabang_id)
  );

-- Admin lapangan hanya boleh MEMBUAT nasabah miliknya sendiri, di cabangnya.
-- Di RTDB pembatasan cabang ini TIDAK ADA pada node pelanggan — admin bisa
-- menulis apa pun ke path miliknya termasuk cabangId cabang lain.
-- Pengetatan yang disengaja.
create policy nasabah_admin_buat on koperasi.nasabah
  for insert to authenticated
  with check (
    koperasi_priv.role() = 'admin'
    and admin_id = auth.uid()
    and cabang_id = koperasi_priv.cabang()
  );

create policy nasabah_admin_ubah on koperasi.nasabah
  for update to authenticated
  using (admin_id = auth.uid() and koperasi_priv.role() = 'admin')
  with check (admin_id = auth.uid() and cabang_id = koperasi_priv.cabang());

create policy nasabah_atasan_ubah on koperasi.nasabah
  for update to authenticated
  using (
    koperasi_priv.is_pengawas()
    or koperasi_priv.is_pimpinan_cabang(cabang_id)
    or (koperasi_priv.role() = 'koordinator' and koperasi_priv.boleh_lihat_cabang(cabang_id))
  )
  with check (
    koperasi_priv.is_pengawas()
    or koperasi_priv.is_pimpinan_cabang(cabang_id)
    or (koperasi_priv.role() = 'koordinator' and koperasi_priv.boleh_lihat_cabang(cabang_id))
  );

-- DELETE hanya Pengawas, dan alur normalnya lewat tabel `permintaan`
-- (CLAUDE.md §7.6: deletion_requests → approver Pengawas).
create policy nasabah_hapus on koperasi.nasabah
  for delete to authenticated
  using (koperasi_priv.is_pengawas());


-- =========================================================================
-- 5. PINJAMAN
-- =========================================================================
create policy pinjaman_baca on koperasi.pinjaman
  for select to authenticated
  using (exists (
    select 1 from koperasi.nasabah n
     where n.id = pinjaman.nasabah_id
       and (n.admin_id = auth.uid() or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
  ));

create policy pinjaman_admin_ajukan on koperasi.pinjaman
  for insert to authenticated
  with check (
    koperasi_priv.role() = 'admin'
    and status = 'Menunggu Approval'          -- admin TIDAK bisa membuat pinjaman langsung aktif
    and exists (
      select 1 from koperasi.nasabah n
       where n.id = pinjaman.nasabah_id and n.admin_id = auth.uid()
    )
  );

-- Cermin .validate rulesfirebase.txt:17: baris baru dari role 'admin' wajib
-- berstatus 'Menunggu Approval'. Di sana ditegakkan lewat ekspresi rules;
-- di sini lewat WITH CHECK — lebih sulit salah baca.

create policy pinjaman_admin_ubah on koperasi.pinjaman
  for update to authenticated
  using (
    koperasi_priv.role() = 'admin'
    and exists (
      select 1 from koperasi.nasabah n
       where n.id = pinjaman.nasabah_id and n.admin_id = auth.uid()
    )
  )
  with check (
    -- Admin tidak boleh menyetujui pinjamannya sendiri.
    status <> 'Disetujui'
  );

create policy pinjaman_atasan_ubah on koperasi.pinjaman
  for update to authenticated
  using (exists (
    select 1 from koperasi.nasabah n
     where n.id = pinjaman.nasabah_id
       and (koperasi_priv.is_pengawas()
            or koperasi_priv.is_pimpinan_cabang(n.cabang_id)
            or (koperasi_priv.role() = 'koordinator'
                and koperasi_priv.boleh_lihat_cabang(n.cabang_id)))
  ))
  with check (true);

-- Tidak ada policy DELETE untuk `pinjaman` → penghapusan generasi pinjaman
-- MUSTAHIL lewat API, oleh siapa pun. Riwayat kredit tidak boleh lenyap.


-- =========================================================================
-- 6. PEMBAYARAN & SIMPANAN
-- =========================================================================
create policy pembayaran_baca on koperasi.pembayaran
  for select to authenticated
  using (exists (
    select 1
      from koperasi.pinjaman p join koperasi.nasabah n on n.id = p.nasabah_id
     where p.id = pembayaran.pinjaman_id
       and (n.admin_id = auth.uid() or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
  ));

-- Hanya admin pemilik nasabah yang mencatat pembayaran, dan hanya pada
-- pinjaman yang BERSTATUS AKTIF. Ini penutup terakhir replay lintas generasi
-- di lapisan izin: operasi tertunda yang menyebut pinjaman_id generasi lama
-- akan ditolak karena generasi itu sudah tidak 'Aktif'.
create policy pembayaran_catat on koperasi.pembayaran
  for insert to authenticated
  with check (
    dicatat_oleh = auth.uid()
    and exists (
      select 1
        from koperasi.pinjaman p join koperasi.nasabah n on n.id = p.nasabah_id
       where p.id = pembayaran.pinjaman_id
         and p.status = 'Aktif'
         and (n.admin_id = auth.uid()
              or koperasi_priv.is_pimpinan_cabang(n.cabang_id)
              or koperasi_priv.is_pengawas())
    )
  );

-- UPDATE/DELETE tidak diberi policy sama sekali — selaras dengan trigger
-- append-only di 001 §4. Dua lapisan, sengaja.

create policy pembayaran_koreksi_baca on koperasi.pembayaran_koreksi
  for select to authenticated using (true);

-- Koreksi pembayaran = wewenang Pimpinan (CLAUDE.md §7.6:
-- payment_deletion_requests → approver Pimpinan).
create policy pembayaran_koreksi_buat on koperasi.pembayaran_koreksi
  for insert to authenticated
  with check (
    disetujui_oleh = auth.uid()
    and koperasi_priv.role() in ('pimpinan', 'pengawas')
  );

create policy simpanan_baca on koperasi.simpanan
  for select to authenticated
  using (exists (
    select 1 from koperasi.nasabah n
     where n.id = simpanan.nasabah_id
       and (n.admin_id = auth.uid() or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
  ));

create policy simpanan_catat on koperasi.simpanan
  for insert to authenticated
  with check (exists (
    select 1 from koperasi.nasabah n
     where n.id = simpanan.nasabah_id and n.admin_id = auth.uid()
  ));

create policy jadwal_baca on koperasi.jadwal_cicilan
  for select to authenticated
  using (exists (
    select 1 from koperasi.pinjaman p join koperasi.nasabah n on n.id = p.nasabah_id
     where p.id = jadwal_cicilan.pinjaman_id
       and (n.admin_id = auth.uid() or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
  ));

create policy jadwal_tulis on koperasi.jadwal_cicilan
  for all to authenticated
  using (exists (
    select 1 from koperasi.pinjaman p join koperasi.nasabah n on n.id = p.nasabah_id
     where p.id = jadwal_cicilan.pinjaman_id and n.admin_id = auth.uid()
  ))
  with check (exists (
    select 1 from koperasi.pinjaman p join koperasi.nasabah n on n.id = p.nasabah_id
     where p.id = jadwal_cicilan.pinjaman_id and n.admin_id = auth.uid()
  ));


-- =========================================================================
-- 7. APPROVAL
-- =========================================================================
-- Cermin rulesfirebase.txt:39-51 pada pengajuan_approval/{cabangId}:
--   read : pimpinan cabang | pengawas | koordinator
--   write: admin cabang (role 'admin') | pimpinan cabang | pengawas | koordinator
create policy pengajuan_baca on koperasi.pengajuan
  for select to authenticated
  using (
    koperasi_priv.boleh_lihat_cabang(cabang_id)
    or diajukan_oleh = auth.uid()
  );

create policy pengajuan_admin_buat on koperasi.pengajuan
  for insert to authenticated
  with check (
    koperasi_priv.role() = 'admin'
    and diajukan_oleh = auth.uid()
    and cabang_id = koperasi_priv.cabang()
    and phase = 'awaiting_pimpinan'      -- selalu mulai dari fase 1
  );

-- Transisi fase HANYA dilakukan trigger `approval_advance` (001 §5), yang
-- berjalan sebagai pemilik fungsi. Tidak ada policy UPDATE untuk klien →
-- tidak ada jalan memaksa fase dari luar.

create policy approval_step_baca on koperasi.approval_step
  for select to authenticated
  using (exists (
    select 1 from koperasi.pengajuan g
     where g.id = approval_step.pengajuan_id
       and koperasi_priv.boleh_lihat_cabang(g.cabang_id)
  ));

-- Approver hanya boleh memutuskan atas namanya sendiri, dengan role aslinya,
-- dan pada cabang yang menjadi wewenangnya. Kecocokan role↔fase ditegakkan
-- trigger tg_approval_urutan (001 §5) — di sini yang dijaga adalah IDENTITAS.
create policy approval_step_putuskan on koperasi.approval_step
  for insert to authenticated
  with check (
    approver_id = auth.uid()
    and approver_role = koperasi_priv.role()
    and exists (
      select 1 from koperasi.pengajuan g
       where g.id = approval_step.pengajuan_id
         and (
           koperasi_priv.is_pengawas()
           or (approver_role = 'pimpinan'    and koperasi_priv.is_pimpinan_cabang(g.cabang_id))
           or (approver_role = 'koordinator' and koperasi_priv.boleh_lihat_cabang(g.cabang_id))
         )
    )
  );


-- =========================================================================
-- 8. PERMINTAAN DESTRUKTIF
-- =========================================================================
create policy permintaan_baca on koperasi.permintaan
  for select to authenticated
  using (
    diminta_oleh = auth.uid()
    or koperasi_priv.is_pengawas()
    or koperasi_priv.role() in ('pimpinan', 'koordinator')
  );

create policy permintaan_ajukan on koperasi.permintaan
  for insert to authenticated
  with check (diminta_oleh = auth.uid() and status = 'pending');

-- Approver per tipe — CLAUDE.md §7.6 tabel:
--   hapus_nasabah      → Pengawas
--   hapus_pembayaran   → Pimpinan
--   ubah_tenor         → Pimpinan
--   pencairan_simpanan → Pimpinan
create policy permintaan_putuskan on koperasi.permintaan
  for update to authenticated
  using (
    status = 'pending'
    and case tipe
      when 'hapus_nasabah' then koperasi_priv.is_pengawas()
      else koperasi_priv.role() in ('pimpinan', 'pengawas')
    end
  )
  with check (diputus_oleh = auth.uid());


-- =========================================================================
-- 9. JURNAL, KASIR, SYNC, DOKUMEN
-- =========================================================================

-- Jurnal: read sesuai cabang; TIDAK ADA policy INSERT/UPDATE/DELETE untuk
-- klien. Penulisan hanya lewat trigger/RPC SECURITY DEFINER, meniru
-- jurnalTransaksi.js yang cuma dipanggil dari Cloud Function.
create policy jurnal_baca on koperasi.jurnal_transaksi
  for select to authenticated
  using (koperasi_priv.boleh_lihat_cabang(cabang_id));

-- Kasir — cermin rulesfirebase.txt:421-423:
--   read : semua user terautentikasi
--   write: role 'kasir_unit' DAN cabang sama
create policy kasir_baca on koperasi.kasir_entry
  for select to authenticated using (true);

create policy kasir_tulis on koperasi.kasir_entry
  for insert to authenticated
  with check (
    koperasi_priv.role() = 'kasir_unit'
    and cabang_id = koperasi_priv.cabang()
    and dicatat_oleh = auth.uid()
  );

create policy kasir_ubah on koperasi.kasir_entry
  for update to authenticated
  using (koperasi_priv.role() = 'kasir_unit' and cabang_id = koperasi_priv.cabang())
  with check (cabang_id = koperasi_priv.cabang());

-- Sync inbox: setiap user hanya melihat jejak operasinya sendiri.
create policy sync_inbox_baca on koperasi.sync_inbox
  for select to authenticated
  using (user_id = auth.uid() or koperasi_priv.is_pengawas());

create policy sync_inbox_tulis on koperasi.sync_inbox
  for insert to authenticated
  with check (user_id = auth.uid());

-- Dokumen: mengikuti visibilitas nasabah induknya.
create policy dokumen_baca on koperasi.dokumen
  for select to authenticated
  using (
    uploaded_by = auth.uid()
    or user_id = auth.uid()
    or exists (
      select 1 from koperasi.nasabah n
       where n.id = dokumen.nasabah_id
         and (n.admin_id = auth.uid() or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
    )
  );

create policy dokumen_tulis on koperasi.dokumen
  for insert to authenticated
  with check (uploaded_by = auth.uid());


-- =========================================================================
-- 10. GRANT TABEL
-- =========================================================================
-- Grant tetap diperlukan: RLS menyaring BARIS, GRANT menyaring PERINTAH.
-- Tanpa GRANT, policy tidak pernah dievaluasi.
grant select on all tables in schema koperasi to authenticated;

grant insert, update on
  koperasi.nasabah, koperasi.pinjaman, koperasi.pengajuan,
  koperasi.kasir_entry, koperasi.permintaan, koperasi.app_user,
  koperasi.jadwal_cicilan
to authenticated;

grant insert on
  koperasi.pembayaran, koperasi.simpanan, koperasi.approval_step,
  koperasi.pembayaran_koreksi, koperasi.sync_inbox, koperasi.dokumen
to authenticated;

grant delete on koperasi.nasabah, koperasi.jadwal_cicilan to authenticated;

-- Tidak ada GRANT INSERT/UPDATE/DELETE pada koperasi.jurnal_transaksi:
-- audit trail hanya bisa ditulis kode server.
-- Tidak ada GRANT DELETE pada pembayaran / pinjaman / approval_step:
-- riwayat keuangan & approval tidak boleh dihapus lewat API.


-- =========================================================================
-- 11. LUBANG YANG BELUM DITUTUP (jujur, bukan daftar keinginan)
-- =========================================================================
-- L-1  Policy `app_user_ubah_diri` (§3) mengizinkan user memperbarui BARISNYA
--      SENDIRI, termasuk kolom `role` dan `cabang_id`. Itu privilege
--      escalation: admin bisa menjadikan dirinya pengawas. WITH CHECK di
--      Postgres bekerja per-baris, bukan per-kolom, jadi ini TIDAK bisa
--      ditutup dari policy saja. Penutupnya salah satu dari:
--        (a) trigger BEFORE UPDATE yang menolak perubahan role/cabang_id bila
--            koperasi_priv.role() <> 'pengawas'; atau
--        (b) hapus policy ini, ubah foto profil lewat RPC SECURITY DEFINER.
--      Rekomendasi: (a) — belum ditulis, sengaja tidak diam-diam ditambahkan.
--      Lihat 005 R-07.
--
-- L-2  `pinjaman_atasan_ubah` memakai `with check (true)`. Atasan bisa
--      memindahkan pinjaman ke nasabah lain lewat UPDATE kolom nasabah_id —
--      kecuali dicegah trigger. Trigger tg_pinjaman_no_downgrade (001 §3.2)
--      MEMANG sudah menolak perubahan nasabah_id & pinjaman_ke, jadi celah
--      ini tertutup DI LAPISAN TRIGGER, bukan di policy. Dicatat supaya
--      penghapusan trigger itu kelak tidak membuka lubang tanpa disadari.
--
-- L-3  `sekretaris` NYATA ADA di produksi (metadata/admins → role
--      'sekretaris', TANPA cabang — terlihat di data/firebase_sample.json).
--      Karena cabang_id-nya null, `boleh_lihat_cabang()` tidak pernah cocok
--      dan sekretaris praktis tidak bisa membaca apa pun. Kalau peran itu
--      memang perlu akses laporan, tambahkan cabangnya atau beri klausa
--      khusus. Belum ditentukan — butuh keputusan Anda.
--
-- L-4  Belum ada padanan `session_lock` / `force_logout` / `remote_takeover`
--      (rulesfirebase.txt:320, :367, :374). Ketiganya fitur sesi, bukan data,
--      dan butuh rancangan tersendiri di fase berikutnya.
--
-- =========================================================================
-- CATATAN VERIFIKASI
-- =========================================================================
-- Skrip ini BELUM PERNAH dijalankan dan policy-nya BELUM PERNAH diuji.
-- Tidak ada instance PostgreSQL/Supabase di environment ini.
-- Gerbang berikutnya: jalankan di staging lalu uji dengan matriks peran
-- (6 role × operasi CRUD × lintas cabang) memakai `set local role` +
-- `request.jwt.claims`, dan catat hasilnya. Tanpa itu, TIDAK ADA klaim aman
-- yang boleh dibuat atas file ini.
-- =========================================================================
