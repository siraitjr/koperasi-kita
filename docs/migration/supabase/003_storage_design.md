# Rancangan Supabase Storage — KTP, Bukti Bayar, Serah Terima

Fase 1. **Rancangan — belum dibuat, belum di-deploy.**
Prasyarat: `001_schema_v2.sql`, `002_rls_policies.sql`.

---

## 1. Keadaan Sekarang (Firebase Storage)

Sumber: `rulesstorage.txt` dan pemanggil upload di kode.

| Prefix | Ditulis oleh | Aturan (`rulesstorage.txt`) |
|---|---|---|
| `ktp_images/{adminUid}/{pelangganId}/ktp_{jenis}.jpg` | `SyncManager.kt:707`, `PelangganViewModel.kt:10346` | read: `uid == adminUid`; write: `uid == adminUid`, < 5 MB, `image/*`; **delete: siapa pun yang login** |
| `ktp_images_pending/{adminUid}/{pelangganId}/ktp_{jenis}.jpg` | `PelangganViewModel.kt:10418` | read: **semua user login**; write: pemilik; delete: siapa pun yang login |
| `profile_photos/{userId}/profile.jpg` | `PelangganViewModel.kt:17569` | read: semua user login; write: diri sendiri, < 1 MB |
| `faktur_bu/{cabangId}/{bulanKey}/{entryId}.jpg` | `buku-pokok-web/app/kasir/page.js:1423` | **tidak ada** |
| _(lainnya)_ | — | `allow read, write: if false` |

### Tiga temuan dari tabel di atas

**T-1 — `faktur_bu/` tidak punya aturan.**
Web kasir meng-upload ke `faktur_bu/...` (`kasir/page.js:1423-1424`), tetapi
`rulesstorage.txt` tidak memuat `match` untuk prefix itu, sehingga jatuh ke
default-deny di baris terakhir. Hanya ada dua kemungkinan, dan saya **tidak
bisa menentukan yang mana dari repo saja**:
(a) upload nota kasir memang gagal di produksi, atau
(b) rules yang ter-deploy berbeda dari `rulesstorage.txt` di repo.
Perlu dicek dari Firebase Console. Konsekuensi migrasi berbeda untuk keduanya.

**T-2 — Foto serah terima menumpang bucket KTP.**
`SyncManager.kt:1182-1184` memanggil `uploadFotoKtp(uri, adminUid, pelangganId,
"serah_terima")`, dan fungsi itu menyusun path `"$folder/$adminUid/$pelangganId/ktp_$jenisKtp.jpg"`
(`SyncManager.kt:707`). Jadi foto serah terima tersimpan sebagai
`ktp_images/{adminUid}/{pelangganId}/ktp_serah_terima.jpg` — dokumen bukti
setoran memakai kuota, ACL, dan retensi milik dokumen identitas. Dipisah di
rancangan ini.

**T-3 — `allow delete: if request.auth != null` pada KTP.**
Setiap user terautentikasi — termasuk admin lapangan dari cabang lain — dapat
menghapus foto KTP nasabah mana pun bila ia tahu path-nya. Path-nya tebakable
(`adminUid` + `pelangganId` keduanya muncul di data yang bisa dibaca atasan).
Ini diperketat di rancangan.

**Belum ada sama sekali: bucket bukti bayar.**
`grep -riE "bukti_?bayar|buktiBayar|bukti_transfer"` atas `app/`,
`buku-pokok-web/`, dan `functions/` → **0 hasil**. Jadi bagian "bukti bayar"
dari tugas ini adalah kebutuhan **baru**, bukan pemindahan yang sudah ada.
Asumsinya dicatat di §6.

---

## 2. Bucket yang Dirancang

Semua bucket **private** (`public = false`). Tidak ada URL permanen; akses
lewat *signed URL* berumur pendek. Di Firebase, `getDownloadURL()` menghasilkan
token yang berlaku selamanya sampai di-revoke manual — URL yang bocor dari log
atau screenshot tetap membuka foto KTP. Signed URL menutup itu.

| Bucket | Isi | Batas | Retensi |
|---|---|---|---|
| `ktp` | KTP nasabah, suami, istri, foto nasabah | 5 MB, `image/jpeg\|png` | permanen |
| `ktp-pending` | Foto pengajuan top-up yang belum final | 5 MB, `image/*` | 90 hari setelah keputusan |
| `serah-terima` | Foto bukti serah terima setoran harian | 5 MB, `image/*` | 10 tahun (bukti keuangan) |
| `bukti-bayar` | Bukti pembayaran cicilan (**baru**) | 5 MB, `image/*` + `application/pdf` | 10 tahun |
| `nota-kasir` | Faktur/nota BU, transport, kasbon | 5 MB, `image/*` + `application/pdf` | 10 tahun |
| `profil` | Foto profil staf | 1 MB, `image/*` | mengikuti masa aktif user |

Batas 5 MB dan 1 MB diambil persis dari `rulesstorage.txt` agar perilaku
kompresi di klien (`ImagePreprocessing_Enhanced.kt`) tidak perlu berubah.

### Konvensi path

```
ktp/          {nasabah_id}/{jenis}.jpg          jenis: ktp|ktp_suami|ktp_istri|foto_nasabah
ktp-pending/  {nasabah_id}/{pinjaman_id}/{jenis}.jpg
serah-terima/ {cabang_id}/{pinjaman_id}/{uuid}.jpg
bukti-bayar/  {cabang_id}/{pinjaman_id}/{pembayaran_id}.jpg
nota-kasir/   {cabang_id}/{YYYY-MM}/{kasir_entry_id}.jpg
profil/       {user_id}/profile.jpg
```

**Perubahan penting: `adminUid` hilang dari path.**
Di Firebase, path dimulai `{adminUid}` karena rules Storage hanya bisa
mencocokkan segmen path — tidak bisa query database. Akibatnya, ketika nasabah
dipindah ke admin lain, path lama jadi menyesatkan (masih menyebut admin lama)
dan izinnya ikut salah. Supabase Storage menyimpan objek di
`storage.objects`, tabel Postgres biasa, sehingga policy-nya bisa **JOIN ke
`koperasi.nasabah`**. Kepemilikan jadi diturunkan dari data, bukan dibekukan di
nama file.

---

## 3. Policy Storage

`storage.objects` adalah tabel biasa, jadi RLS-nya memakai helper yang sama
dengan `002_rls_policies.sql`.

```sql
-- Helper: nasabah_id dari segmen pertama object path.
create or replace function koperasi_priv.path_nasabah_id(p_name text)
returns uuid language sql immutable parallel safe
set search_path = ''
as $$
  select nullif((string_to_array(p_name, '/'))[1], '')::uuid
$$;
```

### 3.1 `ktp` — paling ketat

```sql
-- BACA: admin pemilik, atau atasan yang berwenang atas cabang nasabah.
create policy ktp_baca on storage.objects
  for select to authenticated
  using (
    bucket_id = 'ktp'
    and exists (
      select 1 from koperasi.nasabah n
       where n.id = koperasi_priv.path_nasabah_id(name)
         and (n.admin_id = auth.uid()
              or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
    )
  );

-- TULIS: hanya admin pemilik nasabah.
create policy ktp_tulis on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'ktp'
    and exists (
      select 1 from koperasi.nasabah n
       where n.id = koperasi_priv.path_nasabah_id(name)
         and n.admin_id = auth.uid()
    )
  );

-- HAPUS: hanya Pengawas.
-- Menutup T-3: di rulesstorage.txt, delete terbuka untuk SEMUA user login.
create policy ktp_hapus on storage.objects
  for delete to authenticated
  using (bucket_id = 'ktp' and koperasi_priv.is_pengawas());
```

Tidak ada policy UPDATE → foto KTP tidak bisa ditimpa diam-diam. Ganti foto =
upload objek baru + catat di `koperasi.dokumen`; versi lama tetap ada sebagai
bukti.

### 3.2 `ktp-pending`

```sql
create policy ktp_pending_baca on storage.objects
  for select to authenticated
  using (
    bucket_id = 'ktp-pending'
    and exists (
      select 1 from koperasi.nasabah n
       where n.id = koperasi_priv.path_nasabah_id(name)
         and (n.admin_id = auth.uid()
              or koperasi_priv.boleh_lihat_cabang(n.cabang_id))
    )
  );
```

Di `rulesstorage.txt:31` bucket pending ini `allow read: if request.auth != null`
— **semua** user terautentikasi, lintas cabang. Alasannya tercatat di komentar
rules: approver perlu melihat foto. Di sini kebutuhan itu tetap terpenuhi lewat
`boleh_lihat_cabang()` tanpa membuka ke seluruh organisasi.

### 3.3 `serah-terima`, `bukti-bayar`, `nota-kasir`

Ketiganya berbasis `cabang_id` di segmen pertama:

```sql
create policy dok_cabang_baca on storage.objects
  for select to authenticated
  using (
    bucket_id in ('serah-terima', 'bukti-bayar', 'nota-kasir')
    and koperasi_priv.boleh_lihat_cabang((string_to_array(name, '/'))[1])
  );

create policy nota_kasir_tulis on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'nota-kasir'
    and koperasi_priv.role() = 'kasir_unit'
    and (string_to_array(name, '/'))[1] = koperasi_priv.cabang()
  );
```

`nota_kasir_tulis` menirukan `rulesfirebase.txt:423` (role `kasir_unit` +
cabang cocok) — aturan yang di Firebase hanya berlaku pada **RTDB**, tidak pada
Storage, karena `faktur_bu/` tak punya rules sama sekali (T-1). Migrasi
sekaligus menutup celah itu.

### 3.4 `profil`

```sql
create policy profil_baca on storage.objects
  for select to authenticated using (bucket_id = 'profil');

create policy profil_tulis on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'profil'
    and (string_to_array(name, '/'))[1] = auth.uid()::text
  );
```

Setara `rulesstorage.txt:50-57`.

---

## 4. Akses Baca: Signed URL

`fotoKtpUrl` dan kerabatnya (`PelangganViewModel.kt:227-231`) menyimpan URL
`getDownloadURL()` **permanen** di database. Pola itu tidak dibawa.

Rancangan: kolom di `koperasi.dokumen` menyimpan `object_path`, bukan URL.
Klien meminta signed URL saat akan menampilkan:

```
createSignedUrl(bucket, path, 3600)   // 1 jam
```

Konsekuensi yang harus disadari: **layar detail nasabah offline tidak bisa lagi
menampilkan foto dari URL tersimpan** setelah signed URL kedaluwarsa. Android
sudah punya Coil (CLAUDE.md §3.1) yang meng-cache bitmap di disk, jadi foto
yang pernah dibuka tetap tampil offline; yang belum pernah dibuka tidak.
Ini **perubahan perilaku nyata** — dicatat sebagai R-08 di `005`.

---

## 5. Rencana Pemindahan Objek

Belum dieksekusi; ini urutan yang dirancang.

1. Inventaris objek Firebase Storage → CSV (`path`, `size`, `contentType`, `md5`).
2. Petakan `{adminUid}/{pelangganId}` → `nasabah_id` lewat
   `nasabah.legacy_pelanggan_id` (`001` §2).
3. Salin dengan mempertahankan `md5`; verifikasi checksum per objek.
4. Pecah `ktp_serah_terima.jpg` keluar dari bucket `ktp` → bucket
   `serah-terima` (temuan T-2).
5. Isi `koperasi.dokumen` satu baris per objek.
6. Rekonsiliasi: hitung objek sumber vs tujuan vs baris `dokumen`; tiga angka
   harus sama. Selisih apa pun = berhenti, jangan lanjut.
7. Objek yatim (tidak punya nasabah induk) **jangan dihapus** — pindahkan ke
   `_orphan/` untuk ditinjau manual. Alasan: CLAUDE.md §10 melarang menghapus
   hal yang tampak tak terpakai, dan preseden `sweepRiwayatOrphan.js`
   menunjukkan "yatim" kadang berarti "bug di tempat lain", bukan "sampah".

---

## 6. Asumsi Khusus Storage

| # | Asumsi | Kalau salah |
|---|---|---|
| S-1 | `bukti-bayar` adalah fitur baru (0 hasil grep) | Kalau ternyata sudah ada di jalur yang tak ter-grep, konvensi path harus mengikuti yang lama |
| S-2 | `faktur_bu/` di produksi memang gagal atau rules-nya beda dari repo | Kalau ada rules lain yang ter-deploy, inventaris §5 akan melewatkan objeknya |
| S-3 | Retensi 10 tahun cukup untuk bukti keuangan koperasi | Perlu konfirmasi kebutuhan audit/regulator — saya tidak punya dasar untuk menetapkannya |
| S-4 | Klien sanggup memakai signed URL berumur 1 jam | Kalau UX offline jadi rusak, umur URL perlu dinaikkan atau foto di-cache eksplisit |

---

## Catatan verifikasi

Tidak ada bucket yang dibuat, tidak ada policy yang dijalankan, tidak ada objek
yang dipindahkan. Semua SQL di dokumen ini **belum pernah dieksekusi** — tidak
ada instance Supabase pada environment ini. Angka batas ukuran dan nama prefix
Firebase diambil langsung dari `rulesstorage.txt` dan callsite yang dikutip;
selain itu, isinya rancangan yang masih harus diuji.
