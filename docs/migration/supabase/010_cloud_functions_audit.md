# Audit Cloud Functions → Rencana Migrasi Edge Functions

Fase 3. **Audit dan rencana — belum ada kode yang ditulis atau di-deploy.**

Sumber: `functions/index.js` (80 export dari 37 berkas), disilangkan dengan
seluruh callsite di `app/src/main/kotlin/` dan `buku-pokok-web/`.

---

## 0. ATURAN MENGIKAT: TIDAK ADA ALUR YANG MENGANDALKAN EMAIL

Seluruh email staf berdomain `@godangulu.com` yang **fiktif** — domainnya
tidak ada, jadi tidak ada surat yang pernah sampai.

> **Aturan untuk SEMUA Edge Function user-management:**
> dilarang memakai alur yang menuntut pengguna membuka email. Password
> di-set langsung oleh Pengawas, atau tautan dibuat lewat *admin
> generate-link* lalu **ditampilkan di aplikasi** untuk dibacakan/disalin.

**Sistem berjalan sudah mematuhi ini, dan itu terverifikasi.** Pencarian
`nodemailer|sendMail|sendgrid|mailgun|smtp|generatePasswordResetLink|sendPasswordResetEmail|generateSignInWithEmailLink`
atas ke-37 berkas `functions/` → **nol hasil**. Reset password bekerja dengan
`admin.auth().updateUser(targetUid, { password })`
(`resetUserPassword.js:94`): Pengawas mengetik password baru di aplikasi,
tidak ada email sama sekali.

Konsekuensi untuk pekerjaan yang sudah ada:

| Komponen | Status terhadap aturan ini |
|---|---|
| Edge Function `user-management` (M4) | **Sudah patuh.** `resetUserPassword` memakai `auth.admin.updateUserById(id, { password })` — set langsung, tanpa email. |
| `create_auth_users.js --emit-reset-links` | **Patuh, tetapi perlu dipahami.** `generateLink()` **membuat** tautan dan mengembalikannya ke pemanggil; Supabase **tidak mengirimnya**. Tautan ditulis ke CSV untuk dibagikan manual. Yang harus dihindari adalah `resetPasswordForEmail()` / `inviteUserByEmail()`, yang benar-benar menyurati. |
| `008` §3a opsi A ("tautan pemulihan") | **Tetap sah** dengan alasan di atas — tautan dibagikan tangan-ke-tangan, bukan lewat email. Kalimatnya diperjelas di `008`. |

**Yang TIDAK BOLEH dipakai di Edge Function mana pun:**
`supabase.auth.resetPasswordForEmail()`, `inviteUserByEmail()`, dan
`signInWithOtp({ email })` — ketiganya menyurati dan akan gagal senyap.

---

## 1. Inventaris (80 export)

| Kategori | Jumlah | Dipanggil langsung oleh aplikasi |
|---|---|---|
| RTDB trigger | 25 | — (dipicu penulisan data) |
| Scheduled (cron) | 10 | — |
| Callable (`onCall`) | 11 | 9 dari Android |
| HTTP (`onRequest`) | 34 | 14 dari Web |
| **Total** | **80** | **26 by name** |

Angka 26 itu hasil pencarian nama fungsi di seluruh `app/` dan
`buku-pokok-web/`. Sisanya bukan berarti mati — trigger dan cron memang tidak
pernah dipanggil namanya.

### 1.1 RTDB Trigger (25)

`onPelangganWrite`, `onPembayaranAdded`, `onSubPembayaranAdded`,
`onPelangganApproved`, `onPelangganCreatedRegisterNik`,
`onPembayaranUpdateNikStatus`, `onSubPembayaranUpdateNikStatus`,
`onStatusChangeUpdateNik`, `onPelangganDeletedRemoveNik`,
`onNikClearedRemoveRegistry`, `onNikSuamiClearedRemoveRegistry`,
`onNikIstriClearedRemoveRegistry`, `updateNasabahIndex`,
`onNewPengajuanCreated`, `onPimpinanReviewed`, `onKoordinatorReviewed`,
`onPengawasReviewed`, `onKoordinatorFinalReviewed`, `onDualApprovalComplete`,
`onAdminNotificationCreated`, `onSerahTerimaCreated`, `onBroadcastCreated`,
`onTenorChangeRequestCreated`, `onDeletionRequestCreated`,
`onTrackingActivated`

### 1.2 Scheduled (10)

`dailySummaryReset`, `dailyTargetRecalc`, `dailyUpdatePelangganBermasalah`,
`weeklyFullRecalc`, `cleanupProcessedApprovals`, `cleanupOldNotifications`,
`cleanupOldEventHarian`, `cleanupExpiredBroadcasts`, `summaryHealthCheck`,
`freezeRekapHarian`

### 1.3 Callable (11)

Dipakai Android: `resetUserPassword`, `getAllUsers`, `createNewUser`,
`deleteExistingUser`, `getAllCabang`, `generateTakeoverToken`,
`restorePimpinanSession`, `updateAllSummaries`, `triggerTargetRecalc`.
Tidak terpanggil: `searchNikGlobal`, `refreezeRekapHarian` (yang dipakai web
adalah nama yang sama di jalur HTTP).

### 1.4 HTTP (34)

**Dipakai Web (14):** `getBukuPokok`, `getBukuPokokSummary`,
`getPembayaranHariIni`, `getKasirSummary`, `getKasirEntries`,
`addKasirEntry`, `deleteKasirEntry`, `syncOperasionalTransport`,
`getJurnalTransaksi`, `backfillJurnalTransaksi`, `getKoreksiStorting`,
`setKoreksiStorting`, `getRekeningKoran`, `generateAutoLoginToken`.

**Tidak terpanggil dari mana pun (20)** — lihat §2.

---

## 2. Legacy / Mati (20 HTTP)

Tidak ada satu pun referensi di `app/` maupun `buku-pokok-web/`. Semuanya
endpoint maintenance sekali-jalan yang dipanggil manual lewat browser/curl:

`auditDataSampah`, `hapusDataSampah`, `restoreDataSampah`,
`backfillEventHarian`, `backfillPembayaranHarian`, `backfillNasabahIndex`,
`clearNasabahIndex`, `getNasabahIndexStats`, `migrateNikToRegistry`,
`migrasiAdmin`, `fixAllAdminNames`, `dataIntegrityFix`,
`scanDuplicateNasabah`, `cleanupDuplicateNasabah`,
`cleanupDuplicateApprovals`, `sweepRiwayatOrphan`, `repairAllSummaries`,
`repairAdminSummary`, `recalculateNow`, `updatePelangganBermasalah`

**Rekomendasi: JANGAN dimigrasikan.** Seluruhnya memperbaiki kerusakan yang
khas RTDB — index turunan yang desinkron, duplikat yang tidak tertahan
constraint, summary yang meleset. Di Postgres kerusakan itu tidak bisa
terjadi: `summary`/`nasabah_index`/`pembayaran_harian` menjadi view dan
query (`004` §6), bukan salinan kedua yang bisa salah.

`cleanupDuplicateApprovals.js` bahkan menuliskannya sendiri di komentar:
*"Jalankan ini SEKALI saja, lalu hapus/disable"*.

Dua catatan:
- `sweepRiwayatOrphan` masih memuat secret `'SapuBersih123'` di repo
  (baseline checklist §2A). Selama Firebase belum dimatikan, endpoint ini
  tetap terbuka.
- Berkas yang tidak pernah di-export sama sekali — `applyStalePencairanFix.js`,
  `dryRunStalePencairan.js`, `diagnoseTargetDivergence.js`,
  `exportExcel.js` — adalah kode mati di repo. `exportExcel` menarik
  dependensi `exceljs`; kalau memang tidak dipakai, itu berat yang percuma.

---

## 3. Padanan Teknologi di Supabase

### 3.1 Yang HILANG, bukan bermigrasi (±17 trigger + 6 cron)

Ini temuan terpenting audit: **mayoritas trigger RTDB tidak punya padanan
karena pekerjaannya tidak ada lagi.**

| Fungsi lama | Padanan | Kenapa |
|---|---|---|
| `onPembayaranAdded`, `onSubPembayaranAdded`, `onPelangganApproved` | — | Menulis `summary/*`, `pembayaran_harian`, `event_harian`, jurnal. Semuanya jadi view/index (`001` §12). |
| 8 trigger `onNik*` | — | `nik_registry` digantikan index pada `nasabah.nik`. |
| `updateNasabahIndex` | — | `nasabah_index` adalah tabel `nasabah` itu sendiri. |
| `dailySummaryReset`, `dailyTargetRecalc`, `weeklyFullRecalc`, `summaryHealthCheck`, `dailyUpdatePelangganBermasalah` | — | Tidak ada agregat tersimpan yang perlu di-reset atau diperbaiki. |
| 5 trigger fase approval | **Sudah ada** | Digantikan trigger Postgres `approval_advance` + `approval_urutan` (`001` §5) — sudah ditulis di Fase 1. |

### 3.2 Yang HARUS dimigrasikan

| Fungsi | Padanan Supabase | Catatan |
|---|---|---|
| `onAdminNotificationCreated`, `onSerahTerimaCreated`, `onBroadcastCreated`, `onTenorChangeRequestCreated`, `onDeletionRequestCreated`, `onTrackingActivated` | **Database Webhook → Edge Function** | Ini pekerjaan nyata: fan-out FCM. Postgres webhook pada INSERT tabel notifikasi memanggil Edge Function yang membaca `fcm_tokens` dan menembak FCM. |
| `onPelangganWrite` (bagian `ensurePengajuanApprovalExists`) | **Database Webhook** atau trigger SQL | Backstop server-side saat status `Menunggu Approval` (`onPelangganWrite.js:89`). Penting: inilah yang menjamin Pimpinan tetap dinotifikasi walau klien gagal membuat pengajuan. |
| 5 callable user-management | **Edge Function `user-management`** | **Sudah ditulis** (M4). Tunduk aturan §0. |
| 14 HTTP Web (Buku Pokok, Kasir, Jurnal, Koreksi Storting, Rekening Koran) | **PostgREST langsung + RLS**, sisanya Edge Function | Sebagian besar hanya SELECT beragregat — tidak butuh function sama sekali, cukup view + RLS. `addKasirEntry`/`deleteKasirEntry`/`setKoreksiStorting` jadi Edge Function atau RPC. |
| `generateAutoLoginToken` | **Edge Function** | Android → Web SSO. Supabase punya sesi yang bisa dioper; perlu rancangan tersendiri. |
| `generateTakeoverToken`, `restorePimpinanSession` | **Edge Function** | Butuh `auth.admin` (impersonasi). Paling sensitif — Pimpinan mengambil alih sesi admin. |
| `updateAllSummaries`, `triggerTargetRecalc` | **Kemungkinan besar dihapus** | Keduanya me-recalc agregat tersimpan. Kalau dashboard membaca view, tombolnya kehilangan makna. Perlu dicek ke layar yang memanggilnya. |
| `freezeRekapHarian`, `refreezeRekapHarian` | **pg_cron + Edge Function** | Rekap beku memang snapshot yang disengaja — tetap perlu. |
| `cleanupOldNotifications`, `cleanupOldEventHarian`, `cleanupExpiredBroadcasts`, `cleanupProcessedApprovals` | **pg_cron** | Retensi baris; cukup `delete … where created_at < now() - interval`. |

---

## 4. Urutan Migrasi yang Diusulkan

Diurutkan dari risiko terendah + dampak operasional harian tertinggi.

**Tahap A — nol risiko, tidak menyentuh apa pun yang berjalan**
1. Tandai 20 HTTP legacy sebagai tidak-dimigrasikan (§2). Keputusan, bukan kode.
2. Bangun view pengganti `summary`, `pembayaran_harian`, `event_harian`,
   `pelanggan_bermasalah`, lalu bandingkan angkanya dengan RTDB **sebelum**
   apa pun dipindah. Kalau angkanya tidak cocok, seluruh rencana ini perlu
   ditinjau ulang.

**Tahap B — baca-saja, dampak harian tertinggi**
3. Web Buku Pokok + Kasir → PostgREST/view + RLS. Hanya SELECT, dan web
   sudah biasa memanggil lewat `lib/api.js` sehingga transportnya terpusat
   di satu berkas. Gagal pun tidak merusak data.

**Tahap C — tulis, masih terbatas**
4. `addKasirEntry`, `deleteKasirEntry`, `setKoreksiStorting`,
   `syncOperasionalTransport` → RPC/Edge Function.
5. `freezeRekapHarian` + `refreezeRekapHarian` → pg_cron + Edge Function.
6. Empat cron retensi → pg_cron.

**Tahap D — notifikasi (pekerjaan terberat yang tersisa)**
7. Enam trigger FCM → Database Webhook + Edge Function.
   Diletakkan di sini, bukan lebih awal, karena butuh `fcm_tokens` sudah
   pindah dan perangkat sudah memakai backend baru. Selama tahap A–C,
   notifikasi tetap dilayani Firebase.
8. `onPelangganWrite` backstop approval.

**Tahap E — sesi & impersonasi, paling sensitif**
9. `generateAutoLoginToken` (SSO web).
10. `generateTakeoverToken` + `restorePimpinanSession`.

**Sudah selesai di luar urutan ini:** 5 callable user-management (M4), dan
5 trigger fase approval (sudah jadi trigger SQL di `001` §5).

### Kenapa notifikasi ditaruh belakangan

Godaannya memindahkan notifikasi lebih dulu karena terasa paling "terlihat".
Tetapi FCM adalah satu-satunya bagian yang kegagalannya **senyap**: kalau
webhook tidak jalan, tidak ada yang error — Pimpinan hanya tidak pernah tahu
ada pengajuan masuk, dan itu baru ketahuan berhari-hari kemudian lewat
keluhan. Semua tahap sebelumnya gagal dengan berisik.

---

## 5. Yang Belum Dijawab

- **`updateAllSummaries` / `triggerTargetRecalc` dipanggil dari layar mana?**
  Ada 4 callsite di `PelangganViewModel.kt` (`:9689`, `:11360`, `:11400`,
  `:14627`). Kalau tombolnya ada di layar Pengawas/Pimpinan, perlu diputuskan
  apakah tombol itu dihapus atau diganti "refresh view".
- **`searchNikGlobal`** ter-export tetapi tidak terpanggil. Fitur yang belum
  selesai, atau dipanggil dinamis?
- **`exportExcel.js`** tidak pernah di-export. Ekspor Excel di web memakai
  apa? Perlu ditelusuri sebelum `exceljs` dianggap tidak perlu.
- **Retensi `jurnal_transaksi_meta`, `rekap_harian_final`** dan node lain yang
  belum dimigrasikan (`006` §6) — belum masuk rencana mana pun.

---

## 6. Batas Jujur

- Audit ini berbasis pencarian statis nama fungsi. Pemanggilan yang dirakit
  dinamis (string disusun saat runtime) **tidak akan terlihat**. Sebelum
  mematikan satu pun Cloud Function, periksa log invocation-nya di Firebase
  Console — itu satu-satunya bukti pemakaian yang sebenarnya.
- Belum ada kode Edge Function baru yang ditulis pada fase ini.
- Tidak ada yang di-deploy, tidak ada `app/` yang disentuh.
