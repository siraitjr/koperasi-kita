// =========================================================================
// SCHEDULED CF: freezeRekapHarian — kunci ("freeze") rekap harian per admin
//                                   pada 23:59 WIB setiap hari.
// -------------------------------------------------------------------------
// LATAR (audit pimpinan 06 Jun 2026):
//   Boundary fix di lib/target.js (commit 6aa8cd3) sudah membuat jalur arsip
//   (cairkanSimpanan → riwayat_pinjaman) immutable: nilai hari D sebelum vs
//   sesudah archival = identik. Namun untuk benteng ekstra anti-penyusutan
//   dari vektor lain (mis. edit retroaktif besarPinjaman, koreksi pembayaran
//   lama, penghapusan via request), pimpinan minta snapshot harian permanen.
//
// STRATEGI HEMAT RTDB:
//   Snapshot DARI node `summary/perAdmin/{adminUid}` yang sudah agregat
//   (`targetHariIni`, `pembayaranHariIni`). NOL scan `pelanggan/*` /
//   `pembayaran_harian/*`. Cost per eksekusi:
//     - 1 query `summary/perAdmin` (snapshot kecil; sudah dipakai endpoint
//       getBukuPokok)
//     - 1 multi-path update ke `rekap_harian_final/...` (N entry, N = jumlah
//       admin aktif). Idempoten — jadwal 23:59 WIB sekali per hari.
//
// SKEMA NODE BEKU:
//   rekap_harian_final/{adminUid}/{YYYY-MM-DD}: {
//     target,       // dari summary.targetHariIni saat snapshot
//     storting,     // dari summary.pembayaranHariIni saat snapshot
//     frozenAt      // ServerValue.TIMESTAMP — audit kapan beku
//   }
//
//   Dipakai bukuPokokApi.js untuk OVERRIDE Target & Storting kolom historis
//   di Buku Rekap. Bila entri tidak ada (hari belum lewat / CF belum jalan
//   karena baru deploy), frontend fallback ke kalkulasi live — yang sudah
//   immutable via 6aa8cd3, jadi tidak ada regresi.
//
// JADWAL:
//   `'59 23 * * *'` di TZ `Asia/Jakarta`. Cloud Scheduler akan invoke pukul
//   23:59 WIB persis — menangkap nilai targetHariIni & pembayaranHariIni
//   saat akhir hari sebelum reset harian (reset di scheduledFunctions.js).
//   Region wajib `asia-southeast1` (CLAUDE.md §9.3).
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

// Kunci tanggal node beku: "YYYY-MM-DD" di TZ Asia/Jakarta. Konsisten dengan
// pola index harian lain (lihat pembayaran_harian, event_harian).
function getTodayKeyWIB() {
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(Date.now() + wibOffset);
    const y = wibDate.getUTCFullYear();
    const m = String(wibDate.getUTCMonth() + 1).padStart(2, '0');
    const d = String(wibDate.getUTCDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
}

exports.freezeRekapHarian = functions
    .region('asia-southeast1')
    .pubsub.schedule('59 23 * * *')
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        const db = admin.database();
        const dateKey = getTodayKeyWIB();

        try {
            const snap = await db.ref('summary/perAdmin').once('value');
            const all = snap.val() || {};

            const updates = {};
            let count = 0;
            const ts = admin.database.ServerValue.TIMESTAMP;

            Object.entries(all).forEach(([adminUid, s]) => {
                if (!s) return;
                const target = Number.isFinite(s.targetHariIni) ? s.targetHariIni : 0;
                const storting = Number.isFinite(s.pembayaranHariIni) ? s.pembayaranHariIni : 0;
                // Tetap snapshot meski target=0 — sinyal "hari libur / nol" tetap
                // historis yang valid; tanpa entri, fallback live mungkin compute
                // beda kemudian (lewat 3 bulan, dst). Konsistensi > selektivitas.
                updates[`rekap_harian_final/${adminUid}/${dateKey}`] = {
                    target,
                    storting,
                    frozenAt: ts
                };
                count++;
            });

            if (count > 0) {
                await db.ref().update(updates);
            }
            console.log(`✅ freezeRekapHarian: ${count} admin ter-snapshot untuk ${dateKey} (WIB)`);
            return null;
        } catch (e) {
            console.error(`❌ freezeRekapHarian gagal untuk ${dateKey}:`, e);
            // Re-throw agar Cloud Scheduler menandai run sebagai gagal (alert).
            throw e;
        }
    });
