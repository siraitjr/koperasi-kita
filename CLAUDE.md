# CLAUDE.md — Koperasi Kita

> Dokumentasi konteks project untuk sesi Claude Code.
> **WAJIB dibaca di awal setiap sesi.** Lihat bagian "Aturan Kerja Claude" di akhir file.

---

## 1. Deskripsi Project

**Koperasi Kita (Godang Ulu)** adalah sistem digital simpan-pinjam multi-platform untuk operasional koperasi / credit union. Sistem ini mengelola:

- Registrasi nasabah (dengan scan KTP via ML Kit OCR)
- Pengajuan pinjaman dengan alur approval berjenjang (5 fase untuk pinjaman ≥ Rp 3.000.000)
- Pembayaran cicilan (harian/mingguan/bulanan) & simpanan
- Pembukuan (Buku Pokok), jurnal transaksi, kasir
- Laporan multi-cabang & tracking GPS admin lapangan
- Mode offline (Android) dengan sync queue otomatis

**Stack ringkas:**
- Android app (Kotlin + Jetpack Compose) → untuk admin lapangan, pimpinan, koordinator, pengawas
- Web dashboard (Next.js 14) → untuk Buku Pokok, Kasir, laporan
- Firebase Cloud Functions (Node.js 20) → business logic, approval flow, summary, API
- Firebase Realtime Database → single source of truth
- Firebase Storage → foto KTP & profile photo
- Firebase Auth + FCM → autentikasi & push notification

**Firebase project:** `koperasikitagodangulu`
**Region:** `asia-southeast1`
**Versi Android saat ini:** v4.9 (versionCode 38)

---

## 2. Struktur Folder Lengkap

```
koperasi-kita/
├── app/                                    # Android App (Kotlin + Compose)
│   ├── build.gradle.kts                    # minSdk 21, targetSdk 34, JVM 17
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml             # permissions, services, receivers
│       ├── kotlin/com/example/koperasikitagodangulu/
│       │   └── (109 file .kt)              # semua screen Compose, service,
│       │                                   # worker, repository, helper, util
│       ├── java/com/example/koperasikitagodangulu/
│       │   └── ui/theme/                   # ThemeColors, Typography
│       └── res/                            # drawable, layout, values, mipmap
│
├── buku-pokok-web/                         # Web Dashboard (Next.js 14)
│   ├── package.json                        # next ^14.2, react ^18.2, firebase ^10.12
│   ├── next.config.js
│   ├── app/                                # App Router
│   │   ├── layout.js                       # root layout
│   │   ├── page.js                         # landing + dashboard utama
│   │   ├── pembukuan/page.js               # Buku Pokok (ledger nasabah)
│   │   ├── kasir/page.js                   # kasir / cash register
│   │   └── *.css                           # globals.css, landing.css, dll
│   ├── lib/
│   │   ├── firebase.js                     # init Firebase client
│   │   ├── api.js                          # fetch ke Cloud Functions
│   │   └── format.js                       # formatter rupiah/tanggal
│   └── public/                             # asset statis web
│
├── functions/                              # Firebase Cloud Functions (Node 20)
│   ├── package.json                        # firebase-admin, firebase-functions,
│   │                                       # exceljs, googleapis
│   ├── index.js                            # entry point, export ±40 function
│   │
│   │ # --- Trigger pelanggan / pembayaran ---
│   ├── onPelangganWrite.js
│   ├── onPembayaranAdded.js                # update pembayaran_harian,
│   │                                       # event_harian, summary, jurnal
│   ├── onNikRegistry.js                    # index NIK anti-duplikat
│   ├── onNasabahIndexUpdate.js
│   │
│   │ # --- Approval flow 5 fase ---
│   ├── onNewPengajuanCreated.js            # Fase 1 → notif Pimpinan
│   ├── onAdminNotificationCreated.js       # push FCM ke admin
│   ├── onSerahTerimaCreated.js
│   ├── onBroadcastAndRequests.js           # broadcast, deletion request,
│   │                                       # tenor change request
│   │
│   │ # --- Scheduled / maintenance ---
│   ├── scheduledFunctions.js               # cron harian/mingguan
│   ├── summaryHelpers.js                   # helper hitung summary
│   ├── updateAllSummaries.js
│   ├── summaryRepair_HEMAT.js
│   ├── backfillPembayaranHarian.js
│   ├── koreksiStorting.js
│   ├── cleanupDuplicateApprovals.js
│   ├── dataIntegrityFix.js
│   ├── detectDuplicateNasabah.js
│   ├── auditDataSampah.js
│   ├── fixAllAdminNames.js
│   ├── migrasiAdmin.js
│   │
│   │ # --- API HTTP untuk Web ---
│   ├── bukuPokokApi.js                     # GET buku pokok, summary
│   ├── kasirApi.js                         # kasir CRUD
│   ├── jurnalTransaksi.js
│   ├── jurnalTransaksiApi.js
│   ├── rekeningKoranService.js
│   ├── exportExcel.js
│   │
│   │ # --- User management & session ---
│   ├── resetUserPassword.js                # Pengawas only
│   ├── remoteTakeover.js                   # Pimpinan takeover session admin
│   ├── generateAutoLoginToken.js           # Android → Web SSO
│   └── onTrackingActivated.js
│
├── public/                                 # Firebase Hosting static
│   └── rk.html                             # halaman rekening koran
│
├── firebase.json                           # hosting + functions config
├── .firebaserc                             # project id default
├── .firebaserc.txt                         # backup/duplicate
├── rulesfirebase.txt                       # RTDB security rules (23+ node)
├── rulesstorage.txt                        # Storage rules (KTP, profile)
├── JSON METADATA.json                      # metadata referensi (cabang, role, dll)
├── koperasikitagodangulu-default-rtdb-*.json  # export RTDB (~800KB, di-ignore)
│
├── build.gradle.kts                        # root Gradle (plugin versions)
├── settings.gradle.kts                     # include module :app
├── gradle.properties
├── gradle/                                 # Gradle wrapper
├── gradlew, gradlew.bat
│
├── README.md
├── CLAUDE.md                               # file ini
└── .claudeignore                           # daftar file yang di-skip Claude
```

**Catatan struktur Android:**
Kode Kotlin berada di `app/src/main/kotlin/com/example/koperasikitagodangulu/` (bukan `java/`). Hanya folder `ui/theme/` yang ada di path `java/`. Semua 109 file screen/service/repository berada flat di root package tersebut — **tidak ada sub-package** per fitur.

---

## 3. Tech Stack per Platform

### 3.1 Android (`app/`)

| Kategori | Detail |
|---|---|
| Bahasa | Kotlin 1.9.20 |
| UI | Jetpack Compose 1.5.4 + Material 3 |
| Min / Target SDK | 21 / 34 |
| JVM target | Java 17 |
| Arsitektur | Screen Compose + ViewModel + Repository + Room (offline) |
| Build | Gradle KTS, module tunggal `:app` |

**Library utama:**
- **Firebase:** `firebase-auth-ktx`, `firebase-database-ktx`, `firebase-functions`, `firebase-storage-ktx`, `firebase-messaging-ktx`, `firebase-firestore-ktx` (tidak dipakai sebagai primary).
- **Jetpack:** Navigation Compose 2.7+, Room 2.6.1, DataStore 1.0.0, WorkManager 2.9.0, Lifecycle/ViewModel.
- **Kamera & OCR:** CameraX 1.3.1, ML Kit Text Recognition 16.0.1, ExifInterface 1.3.6.
- **Lokasi & Peta:** Play Services Location 21.1.0, Maps Compose 4.3.0.
- **Lain:** Coil 2.4.0 (image), Coroutines 1.7.3, Gson 2.10.1, Accompanist 0.34.0.

**Komponen sistem Android:**
- Service: `SyncForegroundService`, `LocationTrackingService`, `FirebaseConnectionKeeperService`, `MyFirebaseMessagingService`.
- Worker: `SyncWorker`, `LocationCheckWorker`, `HolidayUpdateWorker`.
- Receiver: `BootReceiver` (auto-sync setelah reboot).
- Room DB: `PendingOperationDatabase` untuk antrian operasi offline.

### 3.2 Web (`buku-pokok-web/`)

| Kategori | Detail |
|---|---|
| Framework | Next.js 14.2 (App Router) |
| UI | React 18.2 + vanilla CSS (tidak pakai Tailwind) |
| Firebase SDK | firebase ^10.12 (client) |
| Deploy | Firebase Hosting |
| Auth | ID Token dari Android → custom token (auto-login SSO) |

**Struktur:**
- `app/page.js` — landing + dashboard utama (pilih cabang/admin, tab view).
- `app/pembukuan/page.js` — Buku Pokok (ledger nasabah, filter, export Excel).
- `app/kasir/page.js` — Kasir (kasbon pagi, BU, transport, upload nota).
- `lib/firebase.js` — init client Firebase.
- `lib/api.js` — wrapper `fetch()` ke Cloud Functions HTTP.
- `lib/format.js` — formatter rupiah & tanggal.

**Karakter khas web:**
- Tidak ada component library / framework UI — semua inline di page file.
- State pakai `useState` / `useEffect`, persistence via `sessionStorage` / `localStorage`.
- Semua data dibaca via HTTP ke Cloud Functions (BUKAN langsung RTDB client) untuk menjaga hak akses & hemat bandwidth.

### 3.3 Cloud Functions (`functions/`)

| Kategori | Detail |
|---|---|
| Runtime | Node.js 20 |
| SDK | `firebase-admin` ^11.8, `firebase-functions` ^4.3 |
| Region | `asia-southeast1` |
| Export | ±40 function dari `index.js` |
| Deploy | `firebase deploy --only functions` |

**Dependency tambahan:**
- `exceljs` ^4.4 — generate file Excel untuk export laporan.
- `googleapis` ^105 — integrasi Google API (Cloud Vision, dll).

**Kategori function:**
- **RTDB Trigger:** `onPelangganWrite`, `onPembayaranAdded`, `onNewPengajuanCreated`, `onAdminNotificationCreated`, `onSerahTerimaCreated`, trigger NIK registry, approval phase transition (5 trigger).
- **HTTP Callable / REST:** `getBukuPokok*`, `getKasir*`, `addKasirEntry`, `getJurnalTransaksi`, `resetUserPassword`, `generateAutoLoginToken`, `generateTakeoverToken`, maintenance endpoint.
- **Scheduled (Cloud Scheduler):** reset harian, recalc target, cleanup notif/approval lama, health check summary, update `pelanggan_bermasalah`.

---

## 4. Gambaran Arsitektur Sistem

```
┌─────────────────────────┐        ┌──────────────────────────┐
│  Android App (Kotlin)   │        │  Web Dashboard (Next.js) │
│  - Admin Lapangan       │        │  - Buku Pokok            │
│  - Pimpinan             │        │  - Kasir                 │
│  - Koordinator          │        │  - Laporan               │
│  - Pengawas             │        │                          │
│                         │        │                          │
│  Room DB (offline queue)│        │                          │
└──────────┬──────────────┘        └────────────┬─────────────┘
           │                                    │
           │ RTDB SDK (read/write langsung)     │ HTTP fetch
           │ FCM push                           │ (Cloud Functions API)
           │                                    │
           ▼                                    ▼
┌──────────────────────────────────────────────────────────────┐
│             Firebase Cloud Functions (Node 20)               │
│  ┌──────────────────────────────────────────────────────┐    │
│  │ RTDB Triggers  │  Scheduled Jobs  │  HTTP Endpoints  │    │
│  └──────────────────────────────────────────────────────┘    │
│   - approval phase transition (5 fase)                       │
│   - update summary (global / perCabang / perAdmin)           │
│   - index pembayaran_harian & event_harian                   │
│   - jurnal transaksi, rekening koran                         │
│   - user management, remote takeover, auto-login             │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│     Firebase Realtime Database (single source of truth)      │
│     Region: asia-southeast1                                  │
│                                                              │
│   pelanggan/  pengajuan_approval/  summary/  metadata/       │
│   pembayaran_harian/  event_harian/  jurnalTransaksi/        │
│   nik_registry/  kasir_entries/  notifications/  ...         │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────┐    ┌───────────────────────────┐
│  Firebase Storage        │    │  Firebase Auth + FCM      │
│  - ktp_images/           │    │  - email/password login   │
│  - profile_photos/       │    │  - custom token (SSO)     │
└──────────────────────────┘    │  - push notif per role    │
                                └───────────────────────────┘
```

**Prinsip arsitektur yang dipegang project ini:**

1. **RTDB = single source of truth.** Semua data operasional hidup di RTDB. Tidak ada database relasional, tidak ada Firestore aktif.
2. **Android langsung ke RTDB** untuk operasi CRUD nasabah/pembayaran (pakai SDK + security rules). **Web via HTTP Cloud Functions** agar role & filter terkontrol di server.
3. **Cloud Functions = business logic server-side.** Semua perhitungan turunan (summary, index harian, jurnal, pelanggan bermasalah, transisi fase approval) dijalankan trigger RTDB, bukan client. Client tidak boleh menduplikasi logic ini.
4. **Denormalized index untuk query cepat.** `pembayaran_harian`, `event_harian`, `nik_registry`, `nasabah_index` adalah node index hasil turunan — dimaintain otomatis oleh trigger.
5. **Offline-first di Android.** Semua tulis dari Admin Lapangan boleh terjadi tanpa internet; `SyncManager` + Room queue menyinkronkan saat online. Konflik diselesaikan last-write-wins berbasis timestamp.
6. **Approval berjenjang async via state machine.** 5 fase approval disimpan di `pengajuan_approval/{cabangId}/{pengajuanId}/dualApprovalInfo`; tiap transisi fase adalah trigger Cloud Function yang mengirim notif ke role berikutnya.
7. **FCM dipakai sebagai sinyal, bukan payload.** Data real tetap diambil dari RTDB; notifikasi push hanya memicu user membuka layar terkait.

---

## 5. Struktur Database Firebase RTDB

**Region:** `asia-southeast1`
**URL:** `https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app`
**Security rules:** `rulesfirebase.txt` (23+ node, role-based)
**Bahasa key:** sebagian campuran Bahasa Indonesia (`pelanggan`, `pembayaran`, `simpanan`) dan Inggris (`summary`, `metadata`).

### 5.1 Peta Top-Level Node

```
/
├── pelanggan/                          # data nasabah (source of truth)
├── pelanggan_ditolak/                  # pengajuan yang ditolak (arsip)
├── pelanggan_bermasalah/               # nasabah telat bayar (index per cabang)
├── pelanggan_status_khusus/            # nasabah dengan status khusus
├── nik_registry/                       # index NIK anti-duplikat global
├── nasabah_index/                      # index pencarian cepat untuk Pimpinan
│
├── pengajuan_approval/                 # pengajuan pinjaman ≥ 3jt (5 fase)
├── deletion_requests/                  # permintaan hapus nasabah
├── payment_deletion_requests/          # permintaan hapus pembayaran
├── tenor_change_requests/              # permintaan ubah tenor
├── pencairan_simpanan_requests/        # permintaan tarik simpanan
│
├── pembayaran_harian/                  # index pembayaran per cabang/tanggal
├── event_harian/                       # event (nasabah baru, lunas) per cabang/tgl
├── biaya_awal/                         # biaya admin awal saat pencairan
├── jurnalTransaksi/                    # jurnal akuntansi (audit trail)
├── koreksi_storting/                   # koreksi storting / adjustment
├── rekening_koran/                     # data rekening koran (generated)
│
├── kasir_entries/                      # entri kasir per cabang/bulan
├── kasir_summary/                      # rekap kasir (computed)
│
├── summary/                            # agregat statistik (global/cabang/admin)
│
├── metadata/                           # master data sistem
│   ├── admins/{uid}                    # user profile
│   ├── roles/{role}/{uid}              # mapping role
│   └── cabang/{cabangId}               # master cabang
│
├── admin_notifications/                # notif ke admin lapangan
├── pengawas_notifications/             # notif ke pengawas
├── koordinator_notifications/          # notif ke koordinator
├── pimpinan_final_notifications/       # notif fase 5 ke pimpinan
├── koordinator_final_notifications/    # notif fase 4 ke koordinator
├── serah_terima_notifications/         # notif serah terima
├── pengawas_serah_terima_notifications/
├── broadcast_messages/                 # pesan broadcast dari Pengawas
│
├── fcm_tokens/                         # FCM device token per user
├── device_presence/                    # online/offline status user
├── session_lock/                       # lock sesi admin (anti double login)
├── remote_takeover/                    # session takeover Pimpinan → Admin
├── force_logout/                       # trigger logout paksa
├── password_reset_logs/                # audit log reset password (Pengawas)
│
├── location_tracking/                  # GPS target (yang di-track)
└── user_locations/                     # GPS submission dari user
```

### 5.2 Penjelasan Node Penting

#### `pelanggan/{adminUid}/{pelangganId}`
Node inti — semua data nasabah, pinjaman, pembayaran, simpanan bersarang di sini. Partisi by `adminUid` agar security rule simpel (admin hanya akses miliknya).

**Field utama:**
- Identitas: `namaKtp`, `namaPanggilan`, `nik`, `nikSuami`/`nikIstri`, `nomorAnggota`, `alamatKtp`, `alamatRumah`, `wilayah`, `detailRumah`, `noHpAdmin`, `noHpKeluarga`.
- Status: `status` (`aktif` | `lunas` | `menunggu_pencairan` | `ditolak`), `pinjamanKe`, `cabangId`.
- Pinjaman: `besarPinjamanDiajukan`, `besarPinjamanDisetujui`, `besarPinjaman`, `totalDiterima`, `admin` (biaya awal), `tenor`.
- Tanggal: `tanggalDaftar`, `tanggalCair`, `tanggalLunas` (format `"dd MMM yyyy"` string).
- Approval legacy: `approvalPimpinan`, `approvalPengawas` (boolean — dipakai untuk pinjaman &lt; 3jt atau rute single approval).
- Approval baru: `dualApprovalInfo` (object berisi 5 fase, lihat §6.1).
- Array pembayaran:
  - `pembayaran[]` — cicilan pokok per transaksi: `{jumlah, tanggal, keterangan}`.
  - `subPembayaran[]` — tambah bayar / pelunasan tambahan.
  - `simpanan[]` — entri simpanan: `{tanggal, jumlah, jenis}` (`cicilan` / `tambah_bayar`).

> **Penting:** array disimpan sebagai array numerik RTDB (bukan push-key). Penambahan elemen **harus** lewat transaction agar tidak overwrite — logic ada di `PelangganViewModel.kt` dan trigger server di `onPembayaranAdded.js`.

#### `nik_registry/{nik}`
Index global NIK ke `{pelangganId, adminUid, status}`. Dipakai untuk cek duplikat sebelum registrasi baru. Dimaintain oleh trigger `onPelangganCreatedRegisterNik`, `onStatusChangeUpdateNik`, `onPelangganDeletedRemoveNik`.

#### `nasabah_index/{cabangId}/{pelangganId}`
Index ringan (nama, status, adminUid, tanggal) untuk listing cepat di Pimpinan/Koordinator/Pengawas tanpa harus baca node `pelanggan` penuh. Dimaintain `onNasabahIndexUpdate`.

#### `pengajuan_approval/{cabangId}/{pengajuanId}`
Pengajuan pinjaman ≥ Rp 3.000.000. Berisi snapshot data pelanggan + `dualApprovalInfo` (state machine 5 fase). Setelah `finalDecision: approved` dan `pimpinanFinalConfirmed: true`, data pelanggan di-commit ke `pelanggan/…`.

**Sub-field `dualApprovalInfo`:**
```
dualApprovalInfo/
├── requiresDualApproval: true
├── approvalPhase: "AWAITING_PIMPINAN" | "AWAITING_KOORDINATOR" |
│                  "AWAITING_PENGAWAS" | "AWAITING_KOORDINATOR_FINAL" |
│                  "AWAITING_PIMPINAN_FINAL" | "completed"
├── pimpinanApproval:       {status, uid, timestamp, note, adjustedAmount?, adjustedTenor?}
├── koordinatorApproval:    {status, uid, timestamp, note, ...}
├── pengawasApproval:       {status, uid, timestamp, note, ...}
├── koordinatorFinalApproval: {...}
├── pimpinanFinalApproval:  {...}
├── finalDecision: "approved" | "rejected"
└── pimpinanFinalConfirmed: bool
```

#### `pembayaran_harian/{cabangId}/{YYYY-MM-DD}/{paymentId}`
Index pembayaran per tanggal per cabang — untuk dashboard Pimpinan/Pengawas agar bisa query pembayaran hari ini tanpa scan semua pelanggan. Ditulis oleh trigger `onPembayaranAdded`. **Jangan ditulis dari client.**

#### `event_harian/{cabangId}/{YYYY-MM-DD}/{nasabah_baru|nasabah_lunas|...}/`
Index event bermilestone (pencairan, lunas, dll) per hari per cabang. Dipakai untuk laporan harian & notifikasi.

#### `jurnalTransaksi/{adminUid}/{pelangganId}/{timestamp}`
Audit trail akuntansi permanen — setiap pembayaran / pencairan / koreksi dicatat di sini. Digunakan Buku Pokok, rekening koran, dan export Excel.

#### `pelanggan_bermasalah/{cabangId}/{pelangganId}`
Nasabah telat bayar, dikategori `ringan` / `sedang` / `berat`. Di-refresh harian oleh scheduled function `dailyUpdatePelangganBermasalah` dan trigger pembayaran (dikeluarkan otomatis saat kembali on-track).

#### `summary/{global|perCabang/{cabangId}|perAdmin/{adminUid}}`
Rekap agregat yang dipakai dashboard:
- `totalNasabah`, `nasabahAktif`, `nasabahLunas`
- `totalPiutang`, `totalSimpanan`
- `pembayaranHariIni`, `targetHariIni`
- `lastUpdated`

Dihitung incremental oleh `summaryHelpers.js` pada setiap trigger write, plus full recalc mingguan (`weeklyFullRecalc`). **Client read-only.**

#### `metadata/`
Master data sistem:
- `metadata/admins/{uid}` — `{name, email, cabang, role, photoUrl}`.
- `metadata/roles/pengawas/{uid}: true` — flag role.
- `metadata/roles/koordinator/{uid}: true`.
- `metadata/cabang/{cabangId}` — `{name, pimpinanUid, adminList[]}`.

#### `kasir_entries/{cabangId}/{YYYY-MM}/{entryId}`
Entri kasir per bulan per cabang: kasbon pagi, BU (biaya umum), transport, dll. Ditulis via `addKasirEntry` HTTP function (bukan client langsung).

#### Request nodes (`deletion_requests`, `payment_deletion_requests`, `tenor_change_requests`, `pencairan_simpanan_requests`)
Workflow request-approval untuk operasi destruktif. Dibuat oleh Admin/Pimpinan, diapprove oleh role berwenang (Pengawas/Pimpinan). Trigger `onBroadcastAndRequests.js` mengirim notif.

#### Notification nodes
Satu node per role target: `admin_notifications/{uid}/{notifId}`, `pengawas_notifications/…`, `koordinator_notifications/…`, `pimpinan_final_notifications/…`, `koordinator_final_notifications/…`, `serah_terima_notifications/…`. Trigger `onAdminNotificationCreated` (dan saudaranya) akan fan-out ke FCM lewat `fcm_tokens/{uid}`.

#### Session & tracking
- `fcm_tokens/{uid}/{token}` — token device untuk push.
- `device_presence/{uid}` — presence online/offline (heartbeat).
- `session_lock/{adminUid}` — lock agar 1 user tidak login di 2 device sekaligus.
- `remote_takeover/{adminUid}` — Pimpinan ambil alih sesi admin.
- `force_logout/{uid}` — ditulis Pengawas untuk paksa logout.
- `location_tracking/{targetUid}` + `user_locations/{targetUid}` — GPS tracking admin lapangan.

### 5.3 Pola Read / Write Data

**Aturan umum:**
- **Client Android:**
  - Baca/tulis langsung ke `pelanggan/{adminUid}/…`, `pengajuan_approval/…`, `kasir` (readonly), `fcm_tokens`, `device_presence`, `user_locations`, `admin_notifications/{uidMilikSendiri}`, `metadata/` (readonly).
  - **Tidak boleh tulis** ke: `summary/*`, `pembayaran_harian/*`, `event_harian/*`, `jurnalTransaksi/*`, `nik_registry/*`, `nasabah_index/*`, `pelanggan_bermasalah/*`. Node ini derived oleh Cloud Function.
- **Client Web (Next.js):** hanya membaca via HTTP Cloud Function. Tidak pakai RTDB client SDK untuk data bisnis.
- **Cloud Functions:** satu-satunya pihak yang menulis ke derived node + transisi fase approval + jurnal.

**Pola penulisan penting:**
1. **Multi-path atomic update.** Operasi yang menyentuh beberapa node (mis. tambah pembayaran → update `pelanggan`, index harian, summary) dikerjakan via `ref.update({"path1": …, "path2": …})` supaya atomic. Lihat contoh di `onPembayaranAdded.js`.
2. **Transaction untuk counter.** Array `pembayaran[]` / `simpanan[]` dan increment counter di `summary/*` memakai `runTransaction()` agar aman dari race condition.
3. **Denormalisasi disengaja.** Data nasabah sebagian digandakan di `nasabah_index`, `pembayaran_harian`, `event_harian`. Jangan rewrite jadi query on-demand — node ini ada justru untuk menghindari scan besar di mobile.
4. **Soft delete via status.** Hapus permanen hanya melalui request-approval (lihat `deletion_requests`). Penanda `status: "ditolak"` / arsip `pelanggan_ditolak` lebih sering dipakai daripada delete langsung.
5. **`.indexOn` di rules.** Query by child (mis. `orderByChild("tanggalCair")`) butuh `.indexOn` di `rulesfirebase.txt`. Tambahkan di rules sebelum bikin query baru, kalau tidak akan jadi full-scan.

**Pola pembacaan penting:**
- Pimpinan/Koordinator/Pengawas **tidak** iterate seluruh `pelanggan/` — mereka baca `nasabah_index`, `summary/perCabang`, `pembayaran_harian/{cabangId}/{today}`, `event_harian/…`. Menambah beban dengan scan `pelanggan/` = **dilarang** (lihat aturan kerja di bagian akhir).
- Admin lapangan hanya baca `pelanggan/{uidSendiri}`; tidak perlu index.
- Dashboard web memakai endpoint `getBukuPokok`, `getBukuPokokSummary`, `getPembayaranHariIni` — logic filter ada di server.

**Catatan format tanggal:**
Banyak field tanggal disimpan sebagai **string lokal** (`"12 Nov 2025"`) alih-alih ISO/timestamp. Index harian memakai format `YYYY-MM-DD`. Timezone acuan: **Asia/Jakarta (WIB)**, helper `getTodayIndonesia()` di `summaryHelpers.js`. Jangan campur format — breaking untuk query dan laporan historis.

---

## 6. Fitur per Platform

### 6.1 Android — Admin Lapangan

Operator yang bertemu nasabah langsung di lapangan.

- **Registrasi nasabah** (`TambahPelangganScreen.kt`, 3.4k lines): form identitas + scan KTP kamera → OCR ML Kit → auto-fill (`KtpScanner.kt`, `KtpParser.kt`, `ImagePreprocessing_Enhanced.kt`). Upload foto KTP ke `ktp_images/{adminUid}/{pelangganId}/`.
- **Pengajuan pinjaman:** input besar pinjaman, tenor, jenis pembayaran. Kalau ≥ 3jt → tulis ke `pengajuan_approval/…` (bukan langsung `pelanggan/`) untuk masuk flow 5 fase.
- **Cari & kelola pelanggan** (`CariPelangganScreen`, `DaftarPelangganScreen`, `DetailPelangganScreen`, `EditPelangganScreen`, `KelolaKreditScreen`): list, filter, edit data.
- **Input pembayaran** (`InputPembayaranScreen`, `InputPembayaranLangsungScreen`, `EditPembayaranScreen`): catat cicilan harian, tambah bayar, pelunasan. Mendukung split payment dan simpanan.
- **Riwayat pembayaran** (`RiwayatPembayaranScreen`): cicilan + sub-pembayaran + simpanan nasabah.
- **Mode offline** lengkap: semua tulis antre di Room (`PendingOperationDatabase`) → `SyncWorker` & `SyncForegroundService` sync saat online. Indikator di `SyncStatusUI.kt`.
- **Absensi** (`AbsensiScreen.kt`) + tracking GPS (`LocationTrackingService`) saat ditugaskan Pengawas.
- **Notifikasi:** FCM (`MyFirebaseMessagingService`) menerima hasil approval, broadcast, serah terima.

### 6.2 Android — Pimpinan (Branch Manager)

- **Dashboard cabang** (`PimpinanDashboardScreen`): target vs realisasi pembayaran hari ini, jumlah nasabah aktif/lunas/bermasalah (baca `summary/perCabang/{cabangId}`).
- **Listing nasabah** per status: `PimpinanDaftarSemuaNasabahScreen`, `PimpinanDaftarNasabahBaruScreen`, `PimpinanDaftarNasabahLunasScreen`, `PimpinanDaftarBermasalahScreen`, `PimpinanDaftarMenungguPencairanScreen`. Semua baca `nasabah_index` + `event_harian` + `pelanggan_bermasalah` (**bukan** scan `pelanggan/`).
- **Approval fase 1 & fase 5** (`PimpinanApprovalScreen.kt`, 3.7k lines): review pengajuan ≥ 3jt, approve/reject/adjust amount & tenor. Fase 5 = final confirm setelah keputusan Pengawas.
- **Serah terima** hari tutup buku: terima setoran harian dari admin.
- **Tenor change request** & **payment deletion request**: approve permintaan dari admin.
- **Remote takeover:** ambil alih sesi admin untuk koreksi langsung di device admin (lewat `generateTakeoverToken`).
- **Laporan cabang** (`PimpinanReportsScreen`).

### 6.3 Android — Koordinator

Jembatan antara Pimpinan (per cabang) dan Pengawas (global).

- **Dashboard koordinator** (`KoordinatorDashboardScreen`, 1.2k lines): agregat lintas cabang yang dikoordinatori.
- **Approval fase 2 & fase 4** (`KoordinatorApprovalScreen`, 2.6k lines): review hasil Pimpinan lalu teruskan ke Pengawas (fase 2); konfirmasi hasil Pengawas sebelum diteruskan ke Pimpinan Final (fase 4).
- **Listing nasabah lintas cabang** (`KoordinatorDaftarSemuaNasabahScreen`, `KoordinatorDaftarMenungguPencairanScreen`).
- **Laporan** (`KoordinatorReportsScreen`, 1.8k lines).

### 6.4 Android — Pengawas (Auditor / Supervisor)

Role tertinggi, visibilitas penuh lintas cabang.

- **Dashboard multi-cabang** (`PengawasDashboardScreen`, 1.5k lines): baca `summary/global` dan `summary/perCabang/*`.
- **Approval fase 3** (`PengawasApprovalScreen`, 2k lines): final decision maker pada pengajuan ≥ 3jt, dapat override amount/tenor.
- **User management** (`PengawasUserManagementScreen`, 1.3k lines): buat user, reset password (`resetUserPassword` Cloud Function), nonaktifkan, pindah cabang, edit role. Audit log di `password_reset_logs`.
- **Tracking GPS** (`PengawasTrackingScreen`): lihat posisi admin di peta real-time lewat `location_tracking/…`.
- **Broadcast** pesan ke seluruh staf → node `broadcast_messages`.
- **Laporan lintas cabang** (`PengawasReportsScreen`, 1.7k lines) + export Excel.
- **Approve deletion request** nasabah / pembayaran.
- **Status khusus** (`PengawasDaftarStatusKhususScreen`).

### 6.5 Web — Dashboard Buku Pokok & Kasir

Diakses oleh Pimpinan / Koordinator / Pengawas / Kasir Wilayah / Sekretaris.

- **Landing page** (`app/page.js`) — halaman marketing publik + login.
- **Auto-login SSO** dari Android: URL param `?idToken=…` → `generateAutoLoginToken` → custom token → signIn.
- **Buku Pokok** (`app/pembukuan/page.js`): ledger lengkap nasabah (PB / L1 / CM / MB / ML kolom storting), saldo awal, historical row (coretan merah untuk pinjaman lama), filter cabang/admin/status, export Excel via `exportExcel.js`.
- **Kasir** (`app/kasir/page.js`): entri kas bulanan (kasbon pagi, BU, transport, dll), upload nota ke Storage, rekap saldo.
- **Jurnal transaksi & koreksi storting** (via API `jurnalTransaksiApi.js`, `koreksiStorting.js`).
- **Rekening koran** (`public/rk.html` + `rekeningKoranService.js`) — halaman standalone.

### 6.6 Cloud Functions — Fitur Server-Side

Sudah dirinci di §3.3. Highlight fungsional:

- **Transisi fase approval otomatis:** `onNewPengajuanCreated` + trigger review per fase → fan-out notifikasi.
- **Index harian & summary realtime:** `onPembayaranAdded` update `pembayaran_harian`, `event_harian`, `summary/*`, `jurnalTransaksi` dalam satu multi-path write.
- **Anti-duplikat NIK:** `onNikRegistry.js` menjaga `nik_registry` konsisten dengan status pelanggan.
- **Scheduled maintenance:** reset target harian, recalc mingguan, cleanup notif lama, health check summary.
- **Endpoint HTTP untuk Web:** Buku Pokok, Kasir, Jurnal, user management.
- **Auto-login SSO & Remote Takeover:** exchange ID token → custom token.

---

## 7. Alur Bisnis Penting

### 7.1 Pengajuan Pinjaman & Approval 5 Fase

**Threshold:**
- Pinjaman **&lt; Rp 3.000.000** → rute sederhana: Admin input → `pelanggan/…` dengan `approvalPimpinan` / `approvalPengawas` flag, approval oleh Pimpinan saja.
- Pinjaman **≥ Rp 3.000.000** → rute dual approval 5 fase via `pengajuan_approval/{cabangId}/{pengajuanId}`.

**State machine dualApprovalInfo:**

```
[Admin submit]
      │
      ▼
AWAITING_PIMPINAN         ── Pimpinan review ─▶ approve/reject/adjust
      │
      ▼
AWAITING_KOORDINATOR      ── Koordinator review ─▶ approve/reject
      │
      ▼
AWAITING_PENGAWAS         ── Pengawas review ─▶ approve/reject/adjust  (keputusan utama)
      │
      ▼
AWAITING_KOORDINATOR_FINAL── Koordinator konfirmasi
      │
      ▼
AWAITING_PIMPINAN_FINAL   ── Pimpinan finalisasi ─▶ pimpinanFinalConfirmed=true
      │
      ▼
completed                 ── commit ke pelanggan/, notif ke Admin
```

- Setiap transisi ditulis Cloud Function (`onPimpinanReviewed`, `onKoordinatorReviewed`, `onPengawasReviewed`, `onKoordinatorFinalReviewed`, `onDualApprovalComplete`), yang sekaligus fan-out notif ke role berikutnya.
- **Reject** di fase manapun → full rollback, status `ditolak`, data dipindah ke `pelanggan_ditolak` (kalau sudah commit) atau dihapus dari `pengajuan_approval`.
- **Adjustment** (ubah amount/tenor) disimpan per-fase di `IndividualApproval.adjustedAmount` / `adjustedTenor` agar auditable.
- Pinjaman lanjutan ("follow-up loan") yang ditolak → rollback lengkap ke data pinjaman sebelumnya (lihat commit `006b693`).

### 7.2 Alur Pembayaran & Update Turunan

```
Admin tekan "Simpan Pembayaran"
   │
   ▼
[ONLINE]                                [OFFLINE]
   │                                        │
   ▼                                        ▼
RTDB write pelanggan/{uid}/{id}/     Room: antrian pending
pembayaran[] via transaction              │
   │                                       ▼
   │                                   Wait online
   │                                       │
   │◀──────────────────────────────────────┘
   ▼
Trigger: onPembayaranAdded
   │
   ├─▶ update simpanan[], totalDibayar
   ├─▶ cek status lunas → set tanggalLunas, status
   ├─▶ write pembayaran_harian/{cabang}/{tgl}/{id}
   ├─▶ write event_harian (nasabah_lunas kalau selesai)
   ├─▶ remove dari pelanggan_bermasalah kalau on-track
   ├─▶ increment summary/perAdmin, perCabang, global
   └─▶ append jurnalTransaksi/{admin}/{pelanggan}/{ts}
```

- Semua update turunan dilakukan via **multi-path update** agar konsisten.
- Admin tidak perlu (dan tidak boleh) menulis sendiri ke node turunan.
- H+1 rule: target harian diukur berdasarkan logika "jatuh tempo H+1 dari pencairan" — konsistensi rule ada di Android, Web, dan Functions (lihat commit `de4d912`).

### 7.3 Sync Offline → Online (Android)

```
User action (tulis)
   │
   ▼
OfflineRepository.save()
   ├─▶ Room: tabel pending_operations (type, path, payload, timestamp, retry_count)
   └─▶ Optimistic UI update
   │
   ▼
Trigger sync:
   - SyncWorker (periodic, ~15m)
   - SyncForegroundService (continuous saat app foreground)
   - ConnectivityManager callback (saat jaringan kembali)
   - Manual pull-to-refresh
   │
   ▼
SyncManager.flush()
   - Order by timestamp (FIFO)
   - Per entry: RTDB write
   - Success → delete dari Room
   - Failure → increment retry_count, exponential backoff
   │
   ▼
SyncStatus broadcast ke UI (IDLE/SYNCING/SUCCESS/PARTIAL/ERROR)
```

- **Konflik:** last-write-wins berbasis timestamp; tidak ada merge client-side.
- **BootReceiver** men-trigger sync setelah device reboot.
- **FirebaseConnectionKeeperService** menjaga koneksi RTDB tetap hidup agar listener tidak terputus.

### 7.4 Registrasi NIK & Anti-Duplikat

1. Admin input/scan KTP → client cek `nik_registry/{nik}` untuk warning duplikat.
2. Simpan pelanggan → trigger `onPelangganCreatedRegisterNik` menulis `nik_registry/{nik}: {pelangganId, adminUid, status}`.
3. Perubahan status (`lunas`, `ditolak`) → trigger `onStatusChangeUpdateNik` update field status.
4. Hapus pelanggan → `onPelangganDeletedRemoveNik` remove entry.
5. Fungsi maintenance `scanDuplicateNasabah` / `cleanupDuplicateNasabah` untuk audit massal.

### 7.5 Serah Terima Harian

Akhir hari, Admin Lapangan menyerahkan setoran ke Pimpinan:

1. Admin tutup hari → write `serah_terima_notifications/{pimpinanUid}/{notifId}`.
2. Trigger `onSerahTerimaCreated` → FCM push ke Pimpinan.
3. Pimpinan verifikasi, tanda tangan digital / konfirmasi.
4. Pengawas juga dapat notif paralel di `pengawas_serah_terima_notifications`.
5. Jurnal serah terima tercatat di `jurnalTransaksi`.

### 7.6 Request Destruktif (Deletion / Tenor Change / Pencairan Simpanan)

Operasi yang berdampak finansial atau integritas data tidak boleh langsung — harus lewat request:

| Request node | Requester | Approver | Aksi sukses |
|---|---|---|---|
| `deletion_requests` | Admin/Pimpinan | Pengawas | pindah ke `pelanggan_ditolak` + cleanup `nik_registry` |
| `payment_deletion_requests` | Admin | Pimpinan | rollback `pembayaran[]`, summary, jurnal |
| `tenor_change_requests` | Admin | Pimpinan | update `tenor`, recalc cicilan |
| `pencairan_simpanan_requests` | Admin | Pimpinan | buat entri simpanan keluar + jurnal |

Semua request di-handle trigger `onBroadcastAndRequests.js`.

### 7.7 Remote Takeover & Auto-Login Web

- **Takeover:** Pimpinan pilih admin di aplikasi → call `generateTakeoverToken` → dapat custom token admin → login sebagai admin (session admin ter-lock via `session_lock`). Restore via `restorePimpinanSession`.
- **Auto-login Web:** Android generate ID token → buka URL web dengan `?idToken=…` → Web call `generateAutoLoginToken` → dapat custom token → `signInWithCustomToken`. Tidak perlu input password di web.

### 7.8 Tracking GPS Admin Lapangan

- Pengawas aktifkan tracking → write `location_tracking/{targetUid}: {active:true, activatedBy, since}`.
- Trigger `onTrackingActivated` → FCM ke admin → admin device start `LocationTrackingService` (foreground service).
- Admin kirim posisi periodik ke `user_locations/{targetUid}`.
- Pengawas lihat di `PengawasTrackingScreen` (Maps Compose) dengan listener realtime.
- `LocationCheckWorker` mem-verifikasi service masih hidup.
