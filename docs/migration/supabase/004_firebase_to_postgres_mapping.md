# Pemetaan Firebase RTDB → PostgreSQL

Fase 1. **Rancangan — belum ada data yang dipindahkan.**
Pendamping: `001_schema_v2.sql`, `002_rls_policies.sql`.

Semua nama field RTDB di bawah diambil dari `data class Pelanggan`
(`PelangganViewModel.kt:160-271`) dan `pelangganToMap` (`:2241`).

---

## 1. Ringkasan Bentuk

| RTDB | PostgreSQL | Sifat perubahan |
|---|---|---|
| `pelanggan/{adminUid}/{pelangganId}` | `nasabah` **+** `pinjaman` **+** `pembayaran` **+** `simpanan` **+** `jadwal_cicilan` | 1 node → 5 tabel |
| `riwayat_pinjaman/{adminUid}/{pid}/{N}` | baris `pinjaman` dengan `pinjaman_ke = N` | arsip → baris biasa |
| `pengajuan_approval/{cabangId}/{id}` | `pengajuan` + `approval_step` | 1 objek → 1 + N baris |
| `jurnal_transaksi/{cabang}/{YYYY-MM}/{id}` | `jurnal_transaksi` | 1:1 |
| `kasir_entries/{cabang}/{YYYY-MM}/{id}` | `kasir_entry` | 1:1 |
| `metadata/admins`, `metadata/roles/*` | `app_user` | 3 sumber → 1 tabel |
| `metadata/cabang` | `cabang` | 1:1 |
| 8 node turunan (lihat §6) | view / index | **tidak dimigrasikan** |

Pemecahan `pelanggan` adalah inti migrasi. Node itu mencampur identitas orang,
kontrak pinjaman, ledger pembayaran, jadwal, dan state approval dalam satu
dokumen yang ditulis ulang tiap top-up.

---

## 2. `pelanggan` → `nasabah`

Field yang melekat pada **orang**, tidak berubah antar generasi pinjaman.

| RTDB | Kolom | Catatan |
|---|---|---|
| _key node_ | `legacy_pelanggan_id` | dipertahankan untuk penelusuran balik |
| `adminUid` | `admin_id` (uuid) | teks UID → FK `app_user` |
| `nik` | `nik` | + unique index parsial, menggantikan `nik_registry` |
| `namaKtp`, `namaPanggilan`, `nomorAnggota` | idem | |
| `namaKtpSuami`/`Istri`, `nikSuami`/`Istri`, `namaPanggilanSuami`/`Istri` | idem | |
| `alamatKtp`, `alamatRumah`, `detailRumah`, `wilayah`, `wilayahNormalized` | idem | |
| `noHp`, `jenisUsaha` | idem | |
| `cabangId` | `cabang_id` | FK `cabang` |
| `statusKhusus`, `catatanStatusKhusus`, `tanggalStatusKhusus`, `diberiTandaOleh` | idem | |
| `lastUpdated` (string) | `updated_at` (timestamptz) | di-parse saat migrasi |
| `isSynced` | — | **dibuang**: penanda antrean lokal Android, bukan data bisnis (`PelangganViewModel.kt:265`) |

## 3. `pelanggan` → `pinjaman` (satu baris per generasi)

| RTDB | Kolom |
|---|---|
| `pinjamanKe` | `pinjaman_ke` (bagian identitas, immutable) |
| `status` | `status` (enum) |
| `besarPinjaman`, `besarPinjamanDiajukan`, `besarPinjamanDisetujui` | idem, `bigint` |
| `admin` | `biaya_admin` — di-rename: `admin` bentrok dengan konsep user |
| `simpanan` | `simpanan_awal` — dipisah dari tabel `simpanan` (ledger) |
| `jasaPinjaman`, `tenor`, `tipePinjaman`, `totalDiterima`, `totalPelunasan` | idem |
| `tanggalPengajuan`/`Daftar`/`Pelunasan`/`Pencairan`/`LunasCicilan` | `date` |
| `catatanApproval`, `tanggalApproval`, `disetujuiOleh`, `ditolakOleh`, `alasanPenolakan` | idem |
| `catatan` | `catatan_admin` — dipisah tegas dari `catatan_approval` (`PelangganViewModel.kt:266-270`) |
| `statusSerahTerima`, `tanggalSerahTerima` | idem (enum) |
| `tarikTabungan`, `statusPencairanSimpanan`, `tanggalPencairanSimpanan`, `dicairkanOleh` | idem |
| `sisaUtangLamaSebelumTopUp`, `totalPelunasanLamaSebelumTopUp`, `besarPinjamanLamaSebelumTopUp` | idem |

### Field yang sengaja TIDAK dibawa

| RTDB | Alasan |
|---|---|
| `statusLunasUntukPinjamanKe` | Ada semata karena RTDB tak bisa membedakan "lunas generasi mana" pada node bersama (`PelangganViewModel.kt:183-192`). Dengan satu baris per generasi, `pinjaman.status` sudah spesifik. |
| `_guardPinjamanKe`, `_guardStatus` | Kunci internal antrean offline, **tidak pernah** ditulis ke RTDB (`SyncManager.stripAddPelangganGuards`). Digantikan `pinjaman_id` sebagai target operasi. |
| `approvalPimpinan`, `approvalPengawas` (boolean) | Jalur approval lama; digantikan `approval_step`. Nilainya tetap direkam saat migrasi sebagai `approval_step` sintetis agar riwayat tidak hilang. |
| `isPinjamanDiubah`, `catatanPerubahanPinjaman` | Turunan: `isPinjamanDiubah` = `besar_disetujui <> besar_diajukan`. `catatanPerubahanPinjaman` adalah string yang dulu di-*parse* untuk rollback — sudah digantikan `backupSebelumTopUp` (`PelangganViewModel.kt:261-264`), dan di skema baru rollback = generasi lama masih utuh sebagai baris. Teks aslinya tetap disalin ke `catatan_admin` agar tidak lenyap. |
| `backupSebelumTopUp` (`BackupTopUpData`, 25 field) | Seluruh isinya adalah **snapshot generasi sebelumnya**. Di skema baru generasi sebelumnya masih ada sebagai baris `pinjaman` — snapshot jadi mubazir. Ini penghapusan 25 kolom sekaligus penghapusan satu kelas bug rollback. |
| `pendingFoto*Uri` (5 field) | URI file lokal Android (`content://`), tidak bermakna di server. |
| `hasilSimulasiCicilan[]` | → tabel `jadwal_cicilan`. |
| `pembayaranList[]`, `subPembayaran[]` | → tabel `pembayaran`. |
| `fotoKtpUrl` dkk. | → `koperasi.dokumen` + Storage (lihat `003`). |

## 4. Array → Tabel

### `pembayaranList[]` + `subPembayaran[]` → `pembayaran`

| RTDB | Kolom |
|---|---|
| `pembayaran[i].jumlah` | `jumlah` (bigint) |
| `pembayaran[i].tanggal` (`"12 Nov 2025"`) | `tanggal` (date) — konversi §7 |
| `pembayaran[i].keterangan` | `keterangan` |
| `pembayaran[i].clientOpId` | `client_op_id` (**unique**) |
| _posisi array_ | — dibuang; urutan dari `tanggal` + `created_at` |

`subPembayaran` masuk tabel yang sama dengan `jenis = 'tambah_bayar'`.
Dasar penyatuan: keduanya `{jumlah, tanggal, keterangan}` yang identik
(`PelangganViewModel.kt:103-115` vs `:134-140`) dan sama-sama menambah uang
masuk.

**Masalah migrasi yang harus diantisipasi:** `clientOpId` baru diperkenalkan
belakangan, jadi baris lama punya `clientOpId = ""` (default di `:112`).
Constraint `unique not null` akan menolaknya. Rencana: isi UUID v5
deterministik dari `(legacy_pelanggan_id, pinjaman_ke, index, tanggal, jumlah)`
sehingga re-run migrasi menghasilkan UUID sama dan idempoten. Lihat R-05.

**Array bercelah:** `safePembayaranList` (`PelangganViewModel.kt:316-318`)
membuang entri null yang muncul dari gap array RTDB. Skrip migrasi harus
melakukan hal yang sama, bukan memperlakukan gap sebagai pembayaran Rp 0.

### `simpanan[]` → `simpanan`; `hasilSimulasiCicilan[]` → `jadwal_cicilan`

`SimulasiCicilan` (`:142-156`) → `(pinjaman_id, urutan, tanggal, jumlah,
is_hari_kerja, is_completed)`. Field `version` dan `lastUpdated` dibuang —
artefak sinkronisasi array.

## 5. Approval

`dualApprovalInfo` (`DualApprovalModels.kt:97-139`) satu objek → satu baris
`pengajuan` + hingga lima baris `approval_step`.

| RTDB | Tujuan |
|---|---|
| `requiresDualApproval` | `pengajuan.requires_dual` |
| `approvalPhase` | `pengajuan.phase` (enum, nilai string sama persis) |
| `pimpinanApproval` | `approval_step` phase `awaiting_pimpinan` |
| `koordinatorApproval` | `approval_step` phase `awaiting_koordinator` |
| `pengawasApproval` | `approval_step` phase `awaiting_pengawas` |
| `koordinatorFinalConfirmed` + `koordinatorFinalTimestamp` | `approval_step` phase `awaiting_koordinator_final` |
| `pimpinanFinalConfirmed` + `pimpinanFinalTimestamp` | `approval_step` phase `awaiting_pimpinan_final` |
| `finalDecision`, `finalDecisionBy`, `finalDecisionTimestamp`, `rejectionReason` | kolom `pengajuan` senama |
| `IndividualApproval{status,by,uid,timestamp,note,adjustedAmount,adjustedTenor}` (`:80-88`) | kolom `approval_step` senama |

Kehilangan yang perlu diakui: RTDB hanya menyimpan **satu** `koordinatorApproval`
untuk dua fase koordinator (fase 2 dan fase 4). Kalau koordinator memutuskan
dua kali, nilai fase 2 sudah tertimpa fase 4 dan **tidak bisa dipulihkan**.
Migrasi akan menghasilkan `approval_step` fase 4 saja untuk kasus itu. Lihat R-09.

## 6. Node Turunan — Tidak Dimigrasikan

Node berikut adalah **hasil hitungan**, bukan fakta. Memindahkannya berarti
memindahkan juga peluangnya untuk desinkron.

| Node RTDB | Pengganti | Cloud Function yang jadi tidak perlu |
|---|---|---|
| `summary/{global,perCabang,perAdmin}` | `v_summary_cabang` dkk. | `summaryHelpers.js`, `updateAllSummaries.js`, `summaryRepair_HEMAT.js` |
| `pembayaran_harian/{cabang}/{tgl}` | index `pembayaran_tanggal_idx` | bagian `onPembayaranAdded.js`, `backfillPembayaranHarian.js` |
| `event_harian/{cabang}/{tgl}` | query atas `created_at` | bagian `onPembayaranAdded.js` |
| `nasabah_index/{cabang}` | tabel `nasabah` | `onNasabahIndexUpdate.js` |
| `nik_registry/{nik}` | unique index `nasabah_nik_unik` | `onNikRegistry.js` (3 trigger) |
| `pelanggan_bermasalah/{cabang}` | view `jadwal_cicilan` vs `pembayaran` | `dailyUpdatePelangganBermasalah` |
| `riwayat_pinjaman/{admin}/{pid}/{N}` | baris `pinjaman` | `sweepRiwayatOrphan.js` |
| `kasir_summary` | view atas `kasir_entry` | bagian `kasirApi.js` |

Konsekuensi: kelas bug "index turunan tidak sinkron dengan sumber" — yang
melahirkan `summaryRepair_HEMAT.js`, `backfillPembayaranHarian.js`,
`dataIntegrityFix.js`, `auditDataSampah.js`, dan `sweepRiwayatOrphan.js` —
tidak punya padanan di skema baru, karena tidak ada salinan kedua yang bisa
salah.

## 7. Konversi Tipe

| Aspek | RTDB | PostgreSQL | Risiko |
|---|---|---|---|
| Uang | `Int` (32-bit) | `bigint` | Plafon Rp 2,1 M hilang. Tidak ada kehilangan presisi. |
| Tanggal bisnis | String `"12 Nov 2025"` | `date` | **Bulan Bahasa Indonesia**: `Mei`, `Agu`, `Okt`, `Des` (`jurnalTransaksi.js:41-44`). Parser wajib memakai peta itu, bukan locale. |
| Tanggal index | String `"YYYY-MM-DD"` | `date` | aman |
| Timestamp | `Long` epoch ms | `timestamptz` | perlu `to_timestamp(ms/1000)` **dan** penegasan zona `Asia/Jakarta` |
| Boolean | `Boolean` | `boolean` | aman |
| Array | array numerik | baris | gap harus dibuang, bukan jadi baris kosong |
| UID | `text` (Firebase UID) | `uuid` (`auth.users.id`) | **bukan konversi** — UID Firebase bukan UUID; butuh tabel pemetaan saat impor user |

## 8. Urutan Impor

Ditentukan oleh foreign key:

```
1. cabang                (tanpa pimpinan_id)
2. auth.users + app_user (pemetaan UID Firebase → uuid)
3. cabang.pimpinan_id    (update)
4. koordinator_cabang
5. nasabah
6. pinjaman              generasi ASC (1,2,3…) — trigger urutan mensyaratkan
7. jadwal_cicilan, pembayaran, simpanan
8. pengajuan → approval_step   (urut fase)
9. jurnal_transaksi, kasir_entry
10. dokumen (setelah objek Storage pindah)
```

Langkah 6 harus urut generasi karena `tg_pinjaman_generasi_berurutan`
(`001` §3.3) menolak lompatan. Impor perlu menggabungkan
`riwayat_pinjaman/{...}/{N}` (generasi lama) dengan node `pelanggan` (generasi
berjalan) lalu mengurutkannya — bukan mengimpor dua sumber secara terpisah.

**Trigger anti-downgrade harus dinonaktifkan selama impor** dan diaktifkan
kembali sesudahnya; data historis wajar mengandung urutan yang tidak monoton
bila diimpor per-field. Pengaktifan kembali adalah langkah yang tidak boleh
terlewat — catat sebagai gate eksplisit di runbook Fase 2.
