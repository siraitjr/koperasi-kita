# Baseline Checklist — Fase 0 (Stabilisasi Sebelum Migrasi Supabase)

Tanggal: 11 Agustus 2026
Branch: `claude/implement-phase-feature-xuAlW`
Commit HEAD: `ba0bbf3` — _fix(android): perbaiki 2 regresi pilot — kembalikan fast-path guarded via TRANSAKSI_
Working tree: bersih (`git status --porcelain` kosong)

Aturan Fase 0: tanpa deploy, tanpa ubah data produksi, tanpa ubah Rules produksi,
tanpa refactor besar. Dokumen ini **hanya melaporkan**, tidak mengubah kode.

---

## 1. Status Kompilasi

**Verdict: TIDAK BISA DIVERIFIKASI di environment ini. Bukan karena error kode.**

Perintah: `./gradlew :app:compileDebugKotlin --no-daemon`

Output (gagal sebelum satu baris Kotlin pun dikompilasi):

```
* Where: Build file '/home/user/koperasi-kita/build.gradle.kts' line: 1
* What went wrong:
Plugin [id: 'com.android.application', version: '8.13.1', apply: false] was not found
  Searched in: Gradle Central Plugin Repository, Google, MavenRepo, maven(https://jitpack.io)
BUILD FAILED in 50s
```

Akar masalah = **network policy environment**, bukan versi plugin yang salah:

```
curl https://dl.google.com/.../8.13.1/....pom
  → curl: (56) CONNECT tunnel failed, response 403
```

Konfirmasi dari proxy (`$HTTPS_PROXY/__agentproxy/status`):

```json
"recentRelayFailures": [
  { "kind": "connect_rejected", "host": "dl.google.com:443",
    "detail": "gateway answered 403 to CONNECT (policy denial or upstream failure)" },
  { "kind": "connect_rejected", "host": "jitpack.io:443", "...": "..." }
]
```

Faktor pemblokir tambahan (walau jaringan dibuka, build tetap gagal):

| Prasyarat | Status |
|---|---|
| Java 21 (OpenJDK) | ✅ ada |
| Gradle 8.13 wrapper | ✅ terunduh & jalan |
| Akses `dl.google.com` / `jitpack.io` | ❌ diblokir proxy (403) |
| Android SDK (`ANDROID_HOME`, `local.properties`) | ❌ tidak ada |
| `kotlinc` standalone | ❌ tidak ada |

**Konsekuensi jujur:** seluruh perubahan Kotlin pada rangkaian commit
`21ef909 → 9a908d5 → a426d61 → ba0bbf3` **belum pernah dikompilasi**, di sesi ini
maupun sebelumnya. Semua verifikasi selama ini bersifat **struktural**
(grep, penelusuran callsite, keseimbangan brace) — bukan bukti compiler.

**Tindakan yang dibutuhkan (di mesin developer, bukan di sini):**
jalankan `./gradlew :app:compileDebugKotlin` sekali di lingkungan ber-SDK
sebelum Fase 1 Supabase dimulai. Ini gerbang wajib.

### 1a. Titik Risiko Compile — Diperiksa Manual

Karena tanpa compiler, empat titik yang sebelumnya saya tandai berisiko
diperiksa ulang secara manual. Tidak ada yang tampak sebagai error:

| Titik | file:line | Temuan |
|---|---|---|
| `import Migration` | `PendingOperationDatabase.kt:5` | Dipakai di `MIGRATIONS: Array<Migration>` (L196). Valid. |
| `import SupportSQLiteDatabase` | `PendingOperationDatabase.kt:6` | Hanya muncul di komentar (L186) → **import tak terpakai = warning**, bukan error. `allWarningsAsErrors` tidak diset di `app/build.gradle.kts` (L40-42 hanya `jvmTarget = "17"`). Aman. |
| Destructuring `Triple` | `SyncManager.kt:936` vs def `:1390` | `Triple<Map<String,Any?>, Int?, String?>` di-destructure jadi 3 val. Arity & tipe cocok. |
| `dismissButton` if/else null | `SyncStatusUI.kt:664-669` | Cabang-if menghasilkan lambda `@Composable () -> Unit`, cabang-else `null`; tipe parameter AlertDialog M3 adalah `(@Composable () -> Unit)?`. Ekspektasi tipe merambat dari parameter. Secara struktural valid. |

Status keempatnya: **plausible-lolos, belum dibuktikan compiler.**

---

## 2. Triage Backlog

### A. Auth `sweepRiwayatOrphan` — RISIKO SEDANG, belum ditutup

- `functions/sweepRiwayatOrphan.js:48`
  `const EXPECTED_SECRET = process.env.SWEEP_SECRET || 'SapuBersih123';`
- Gate 403 sudah benar secara struktur: dicek **sebelum** baca DB apa pun
  (`:55-61`), berlaku juga untuk dry-run.
- Ter-export & aktif: `functions/index.js:228`.

**Masalah:** nilai fallback `'SapuBersih123'` ter-commit di repo. Siapa pun yang
bisa baca repo bisa memanggil endpoint ini bila `SWEEP_SECRET` belum diset di
environment Cloud Functions. Ini endpoint _maintenance_ yang bisa **menghapus**
data (`?confirm=true`).

**Rekomendasi (butuh persetujuan + deploy — TIDAK dikerjakan di Fase 0):**
1. Set `SWEEP_SECRET` di environment Functions, lalu
2. hapus fallback literal → tolak semua request bila env var kosong (fail-closed), atau
3. hapus endpoint sepenuhnya kalau sweep one-off-nya memang sudah selesai dipakai.

Sampai salah satu dikerjakan: **anggap endpoint ini terbuka.**

### B. `exportSchema` Room — RISIKO RENDAH, tapi menghalangi migrasi aman

- `PendingOperationDatabase.kt:158` → `exportSchema = false`, `version = 1`.
- `app/build.gradle.kts` memakai `kapt` (L5) + `room-compiler:2.6.1` (L152),
  **tanpa** argumen `room.schemaLocation` (0 kecocokan).

Fix B-1 sebelumnya sudah benar dan tetap berlaku: `fallbackToDestructiveMigration()`
sudah dihapus, diganti `addMigrations(*MIGRATIONS)` dengan `MIGRATIONS = emptyArray()`
(L196, L205). Artinya menaikkan `version` tanpa mendaftarkan Migration akan
**gagal keras** saat DB dibuka — bukan diam-diam menghapus antrean pembayaran.
Itu perilaku yang diinginkan.

Yang belum: tanpa `exportSchema = true`, tidak ada JSON skema ter-versioning,
sehingga migrasi mendatang tidak bisa di-review lewat diff dan tidak ada
`MigrationTestHelper`.

**Rekomendasi (butuh ubah `build.gradle.kts` — TIDAK dikerjakan di Fase 0):**
tambahkan `kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }`
lalu set `exportSchema = true`. Sebaiknya dilakukan **bersamaan** dengan kenaikan
`version` pertama, bukan sekarang, agar skema v1 ter-rekam tepat.

Relevansi Supabase: antrean offline Room ini adalah satu-satunya penyimpan
transaksi keuangan yang belum ter-sync. Skema-nya harus stabil & terdokumentasi
**sebelum** ada perubahan lapisan sync.

### C. `dualApprovalInfo` di `pelangganToMap` — BUKAN REGRESI, dampak terbatas

**Fakta terverifikasi:**

| Bukti | Lokasi |
|---|---|
| `dualApprovalInfo` **ada** di model `Pelanggan` | `PelangganViewModel.kt:260` |
| **Di-parse** dari snapshot | `:408-410`, di-assign `:504` |
| **TIDAK ADA** di `pelangganToMap` (body `:2241`+) | 0 kecocokan di dalam body |
| `pelangganToMap` hanya punya **1 callsite** | `:2113`, di dalam `simpanPelangganKeFirebase` (`:2040`) |
| Callsite itu menulis whole-node ke `pelanggan/{adminUid}/{id}` | via `offlineRepo.savePelanggan` (`:2116-2120`) → op `ADD_PELANGGAN` |

**State machine 5 fase TIDAK terdampak.** Sumber kebenaran approval adalah
`pengajuan_approval/{cabangId}`, bukan node `pelanggan`. Terbukti:
- `loadPendingApprovalsOptimized` membaca `database.child("pengajuan_approval").child(cabangId)`
- `setupRealtimePendingApprovals` idem
- `PimpinanApprovalScreen.kt:141` mengonsumsi `viewModel.pendingApprovals` dari situ

Jadi Pimpinan/Koordinator/Pengawas tetap melihat `dualApprovalInfo` yang benar.

**Sisa risiko (kosmetik, pre-existing):** `simpanPelangganKeFirebase` menulis
whole-node, sehingga `dualApprovalInfo` yang pernah ditulis ke node `pelanggan`
oleh jalur lain (mis. map rollback `:4881`) akan **terhapus** pada penyimpanan
berikutnya. Pembaca yang memakai `parsePelangganFromSnapshot`
(`SmartFirebaseLoader.kt:205`, `PelangganViewModel.kt:10282`) lalu mendapat
`null`. Konsumen yang terlihat terdampak hanya tampilan:
`DetailNotifikasiScreen.kt:650` (`DualApprovalResultSection`) — riwayat approval
bisa tidak tampil di layar detail notifikasi.

**Verdict: perilaku lama, bukan efek samping perubahan sync/guard.** Tidak
mendesak. **Tidak saya ubah** — menambah field ke `pelangganToMap` berarti
menulis node turunan tambahan tiap simpan (beban RTDB naik) dan menyentuh
jalur simpan inti; butuh keputusan Anda dulu.

---

## 3. Ringkasan Gerbang Menuju Fase 1

| # | Item | Status |
|---|---|---|
| 1 | Branch & commit terkonfirmasi | ✅ `claude/implement-phase-feature-xuAlW` @ `ba0bbf3`, tree bersih |
| 2 | `:app:compileDebugKotlin` hijau | ❌ **BELUM** — terblokir environment; wajib dijalankan di mesin ber-SDK |
| 3 | Uji ulang pilot fix `ba0bbf3` di perangkat `harmonisspg4` | ⏳ belum |
| 4 | `sweepRiwayatOrphan` di-fail-closed / dihapus | ⏳ belum (butuh persetujuan + deploy) |
| 5 | Room `exportSchema` + `schemaLocation` | ⏳ ditunda ke kenaikan `version` berikutnya |
| 6 | `dualApprovalInfo` di `pelangganToMap` | ➖ sengaja dibiarkan; pre-existing, bukan pemblokir |

**Data lapangan yang masih saya minta** (untuk menutup audit top-up):
- `pelanggan/C79qv2GmCAMUOqjEM4yiCBOBIPQ2/-OmJdHQ3lB3qrGad_oGv`
- `pelanggan/C79qv2GmCAMUOqjEM4yiCBOBIPQ2/-Onaxa0rU6YNnhQLk7af`

**Gerbang #2 belum hijau, jadi baseline belum bisa disebut stabil.**
Tidak ada klaim "aman" yang dibuat di dokumen ini tanpa bukti file:line atau output.
