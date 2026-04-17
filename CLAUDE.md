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
