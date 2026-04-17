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
