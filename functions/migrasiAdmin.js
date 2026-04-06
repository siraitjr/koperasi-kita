// =========================================================================
// MIGRASI ADMIN - Transfer semua nasabah dari admin A ke admin B
// =========================================================================
//
// PENGGUNAAN (developer tool — jalankan sendiri via curl/browser):
//
//   DRY RUN (cek dulu tanpa eksekusi):
//   GET https://.../migrasiAdmin?fromAdminUid=AAA&toAdminUid=BBB&secret=KKG_MIGRATE_2026
//
//   EKSEKUSI:
//   GET https://.../migrasiAdmin?fromAdminUid=AAA&toAdminUid=BBB&secret=KKG_MIGRATE_2026&confirm=yes
//
// YANG DILAKUKAN:
//   1. Pindahkan pelanggan/{fromUid}        → pelanggan/{toUid}
//   2. Pindahkan riwayat_pinjaman/{fromUid} → riwayat_pinjaman/{toUid}
//   3. Pindahkan pelanggan_ditolak/{fromUid}→ pelanggan_ditolak/{toUid}
//   4. Update adminUid di pelanggan_bermasalah
//   5. Update adminUid di pelanggan_status_khusus
//   6. Update adminUid di pengajuan_approval yang masih pending
//   7. Update adminUid di summary/nasabahIndex
//   8. Hapus semua node admin A (summary, notifikasi, session, dll)
//   9. Hapus admin A dari metadata/cabang/{cabangId}/adminList
//  10. Hapus Firebase Auth account admin A
//  11. Recalculate summary admin B
//
// YANG TIDAK DIUBAH (data historis — immutable):
//   - pembayaran_harian   (log historis per tanggal)
//   - event_harian        (log historis per tanggal)
//   - jurnal_transaksi    (audit trail permanen)
//   - kasir_entries       (per cabang, bukan per admin)
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');
const db = admin.database();

// Ganti secret ini jika perlu — hanya kamu yang tahu
const MIGRATION_SECRET = 'KKG_MIGRATE_2026';

exports.migrasiAdmin = functions
    .region('asia-southeast1')
    .runWith({ timeoutSeconds: 540, memory: '1GB' })
    .https.onRequest(async (req, res) => {
        res.set('Access-Control-Allow-Origin', '*');
        if (req.method === 'OPTIONS') { res.status(204).send(''); return; }

        const { fromAdminUid, toAdminUid, secret, confirm } = req.query;
        const isDryRun = confirm !== 'yes';

        // ── Validasi secret ──────────────────────────────────────────────
        if (secret !== MIGRATION_SECRET) {
            res.status(403).json({ success: false, error: 'Secret key salah' });
            return;
        }

        // ── Validasi parameter ───────────────────────────────────────────
        if (!fromAdminUid || !toAdminUid) {
            res.status(400).json({ success: false, error: 'fromAdminUid dan toAdminUid diperlukan' });
            return;
        }
        if (fromAdminUid === toAdminUid) {
            res.status(400).json({ success: false, error: 'fromAdminUid dan toAdminUid tidak boleh sama' });
            return;
        }

        const log = [];
        const stats = {
            pelanggan: 0,
            riwayat: 0,
            pelangganDitolak: 0,
            pelangganBermasalah: 0,
            statusKhusus: 0,
            pengajuanPending: 0,
            indexUpdated: 0,
        };

        try {
            // ── Validasi kedua admin ada dan di cabang yang sama ─────────
            const [fromSnap, toSnap] = await Promise.all([
                db.ref(`metadata/admins/${fromAdminUid}`).once('value'),
                db.ref(`metadata/admins/${toAdminUid}`).once('value'),
            ]);

            if (!fromSnap.exists()) {
                res.status(404).json({ success: false, error: `Admin asal (${fromAdminUid}) tidak ditemukan di metadata` });
                return;
            }
            if (!toSnap.exists()) {
                res.status(404).json({ success: false, error: `Admin tujuan (${toAdminUid}) tidak ditemukan di metadata` });
                return;
            }

            const fromData = fromSnap.val();
            const toData = toSnap.val();
            const cabangId = fromData.cabang;

            if (!cabangId) {
                res.status(400).json({ success: false, error: 'Admin asal tidak memiliki cabangId di metadata' });
                return;
            }
            if (fromData.cabang !== toData.cabang) {
                res.status(400).json({ success: false, error: `Admin berbeda cabang: ${fromData.cabang} vs ${toData.cabang}` });
                return;
            }

            const toName = toData.name || toData.email || toAdminUid;
            log.push(`👤 Dari : ${fromData.name || fromAdminUid} (${fromAdminUid})`);
            log.push(`👤 Ke   : ${toName} (${toAdminUid})`);
            log.push(`🏢 Cabang: ${cabangId}`);
            log.push('');

            // ================================================================
            // STEP 1 — Pindahkan pelanggan
            // ================================================================
            const pelangganSnap = await db.ref(`pelanggan/${fromAdminUid}`).once('value');
            if (pelangganSnap.exists()) {
                const pelangganData = pelangganSnap.val();
                stats.pelanggan = Object.keys(pelangganData).length;
                log.push(`📦 [1] Pelanggan aktif: ${stats.pelanggan} nasabah`);

                if (!isDryRun) {
                    for (const [pId, pData] of Object.entries(pelangganData)) {
                        await db.ref(`pelanggan/${toAdminUid}/${pId}`).set(pData);
                        await db.ref(`pelanggan/${fromAdminUid}/${pId}`).remove();
                    }
                    log.push(`    ✅ Dipindahkan ke pelanggan/${toAdminUid}`);
                }
            } else {
                log.push(`📦 [1] Tidak ada pelanggan aktif`);
            }

            // ================================================================
            // STEP 2 — Pindahkan riwayat_pinjaman
            // ================================================================
            const riwayatSnap = await db.ref(`riwayat_pinjaman/${fromAdminUid}`).once('value');
            if (riwayatSnap.exists()) {
                const riwayatData = riwayatSnap.val();
                stats.riwayat = Object.keys(riwayatData).length;
                log.push(`📦 [2] Riwayat pinjaman: ${stats.riwayat} nasabah`);

                if (!isDryRun) {
                    for (const [pId, pinjamanMap] of Object.entries(riwayatData)) {
                        await db.ref(`riwayat_pinjaman/${toAdminUid}/${pId}`).set(pinjamanMap);
                        await db.ref(`riwayat_pinjaman/${fromAdminUid}/${pId}`).remove();
                    }
                    log.push(`    ✅ Dipindahkan ke riwayat_pinjaman/${toAdminUid}`);
                }
            } else {
                log.push(`📦 [2] Tidak ada riwayat pinjaman`);
            }

            // ================================================================
            // STEP 3 — Pindahkan pelanggan_ditolak
            // ================================================================
            const ditolakSnap = await db.ref(`pelanggan_ditolak/${fromAdminUid}`).once('value');
            if (ditolakSnap.exists()) {
                const ditolakData = ditolakSnap.val();
                stats.pelangganDitolak = Object.keys(ditolakData).length;
                log.push(`📦 [3] Pelanggan ditolak: ${stats.pelangganDitolak} nasabah`);

                if (!isDryRun) {
                    for (const [pId, pData] of Object.entries(ditolakData)) {
                        await db.ref(`pelanggan_ditolak/${toAdminUid}/${pId}`).set(pData);
                        await db.ref(`pelanggan_ditolak/${fromAdminUid}/${pId}`).remove();
                    }
                    log.push(`    ✅ Dipindahkan ke pelanggan_ditolak/${toAdminUid}`);
                }
            } else {
                log.push(`📦 [3] Tidak ada pelanggan ditolak`);
            }

            // ================================================================
            // STEP 4 — Update adminUid di pelanggan_bermasalah
            // ================================================================
            const bermasalahSnap = await db.ref(`pelanggan_bermasalah/${cabangId}`).once('value');
            if (bermasalahSnap.exists()) {
                const updates = {};
                bermasalahSnap.forEach(child => {
                    const d = child.val();
                    if (d && d.adminUid === fromAdminUid) {
                        stats.pelangganBermasalah++;
                        if (!isDryRun) {
                            updates[`pelanggan_bermasalah/${cabangId}/${child.key}/adminUid`] = toAdminUid;
                            updates[`pelanggan_bermasalah/${cabangId}/${child.key}/adminName`] = toName;
                        }
                    }
                });
                log.push(`📦 [4] Pelanggan bermasalah: ${stats.pelangganBermasalah} record`);
                if (!isDryRun && Object.keys(updates).length > 0) {
                    await db.ref().update(updates);
                    log.push(`    ✅ adminUid diupdate`);
                }
            } else {
                log.push(`📦 [4] Tidak ada pelanggan bermasalah`);
            }

            // ================================================================
            // STEP 5 — Update adminUid di pelanggan_status_khusus
            // ================================================================
            const skSnap = await db.ref(`pelanggan_status_khusus/${cabangId}`).once('value');
            if (skSnap.exists()) {
                const updates = {};
                skSnap.forEach(child => {
                    const d = child.val();
                    if (d && d.adminUid === fromAdminUid) {
                        stats.statusKhusus++;
                        if (!isDryRun) {
                            updates[`pelanggan_status_khusus/${cabangId}/${child.key}/adminUid`] = toAdminUid;
                        }
                    }
                });
                log.push(`📦 [5] Status khusus: ${stats.statusKhusus} record`);
                if (!isDryRun && Object.keys(updates).length > 0) {
                    await db.ref().update(updates);
                    log.push(`    ✅ adminUid diupdate`);
                }
            } else {
                log.push(`📦 [5] Tidak ada status khusus`);
            }

            // ================================================================
            // STEP 6 — Update adminUid di pengajuan_approval yang masih pending
            // ================================================================
            const pengajuanSnap = await db.ref(`pengajuan_approval/${cabangId}`).once('value');
            if (pengajuanSnap.exists()) {
                const updates = {};
                pengajuanSnap.forEach(child => {
                    const d = child.val();
                    const st = (d && d.status || '').toLowerCase();
                    if (d && d.adminUid === fromAdminUid && st !== 'selesai' && st !== 'ditolak') {
                        stats.pengajuanPending++;
                        if (!isDryRun) {
                            updates[`pengajuan_approval/${cabangId}/${child.key}/adminUid`] = toAdminUid;
                        }
                    }
                });
                log.push(`📦 [6] Pengajuan approval pending: ${stats.pengajuanPending}`);
                if (stats.pengajuanPending > 0) {
                    log.push(`    ⚠️  Pengajuan pending akan dialihkan ke admin tujuan`);
                }
                if (!isDryRun && Object.keys(updates).length > 0) {
                    await db.ref().update(updates);
                    log.push(`    ✅ adminUid diupdate`);
                }
            } else {
                log.push(`📦 [6] Tidak ada pengajuan approval`);
            }

            // ================================================================
            // STEP 7 — Update adminUid di summary/nasabahIndex
            // ================================================================
            const indexSnap = await db.ref(`summary/nasabahIndex/${cabangId}`).once('value');
            if (indexSnap.exists()) {
                const updates = {};
                indexSnap.forEach(child => {
                    const d = child.val();
                    if (d && d.adminUid === fromAdminUid) {
                        stats.indexUpdated++;
                        if (!isDryRun) {
                            updates[`summary/nasabahIndex/${cabangId}/${child.key}/adminUid`] = toAdminUid;
                        }
                    }
                });
                log.push(`📦 [7] Nasabah index: ${stats.indexUpdated} record`);
                if (!isDryRun && Object.keys(updates).length > 0) {
                    await db.ref().update(updates);
                    log.push(`    ✅ adminUid diupdate`);
                }
            } else {
                log.push(`📦 [7] Tidak ada nasabah index`);
            }

            // ── Hentikan di sini jika dry run ────────────────────────────
            if (isDryRun) {
                log.push('');
                log.push('🔍 DRY RUN selesai — tidak ada data yang diubah.');
                log.push('   Tambahkan &confirm=yes untuk eksekusi.');
                res.status(200).json({ success: true, dryRun: true, stats, log });
                return;
            }

            // ================================================================
            // STEP 8 — Hapus node-node admin A
            // ================================================================
            log.push(`🗑️  [8] Membersihkan node admin A...`);
            const cleanupUpdates = {};
            cleanupUpdates[`summary/perAdmin/${fromAdminUid}`]  = null;
            cleanupUpdates[`admin_notifications/${fromAdminUid}`] = null;
            cleanupUpdates[`location_tracking/${fromAdminUid}`]   = null;
            cleanupUpdates[`session_lock/${fromAdminUid}`]        = null;
            cleanupUpdates[`force_logout/${fromAdminUid}`]        = null;
            cleanupUpdates[`remote_takeover/${fromAdminUid}`]     = null;
            cleanupUpdates[`device_presence/${fromAdminUid}`]     = null;
            cleanupUpdates[`data_updates/${fromAdminUid}`]        = null;
            cleanupUpdates[`user_absensi_today/${fromAdminUid}`]  = null;
            cleanupUpdates[`biaya_awal/${fromAdminUid}`]          = null;
            cleanupUpdates[`metadata/admins/${fromAdminUid}`]     = null;
            await db.ref().update(cleanupUpdates);
            log.push(`    ✅ Semua node admin A dihapus`);

            // ================================================================
            // STEP 9 — Hapus fromAdminUid dari adminList cabang
            // ================================================================
            log.push(`🗑️  [9] Update adminList cabang...`);
            const adminListSnap = await db.ref(`metadata/cabang/${cabangId}/adminList`).once('value');
            if (adminListSnap.exists()) {
                const currentList = adminListSnap.val() || [];
                const newList = currentList.filter(uid => uid !== fromAdminUid);
                await db.ref(`metadata/cabang/${cabangId}/adminList`).set(newList);
                log.push(`    ✅ ${fromAdminUid} dihapus dari adminList`);
            }

            // ================================================================
            // STEP 10 — Hapus Firebase Auth account admin A
            // ================================================================
            log.push(`🗑️  [10] Menghapus Firebase Auth account...`);
            try {
                await admin.auth().deleteUser(fromAdminUid);
                log.push(`    ✅ Auth account dihapus`);
            } catch (authErr) {
                if (authErr.code === 'auth/user-not-found') {
                    log.push(`    ℹ️  Auth account sudah tidak ada`);
                } else {
                    log.push(`    ⚠️  Gagal hapus Auth: ${authErr.message}`);
                }
            }

            // ================================================================
            // STEP 11 — Recalculate summary admin B
            // ================================================================
            log.push(`🔄 [11] Recalculate summary admin B...`);
            try {
                const { fullRecalculateAdminSummary } = require('./summaryHelpers');
                await fullRecalculateAdminSummary(toAdminUid);
                log.push(`    ✅ Summary admin B selesai direcalculate`);
            } catch (sumErr) {
                log.push(`    ⚠️  Gagal recalculate summary: ${sumErr.message}`);
            }

            log.push('');
            log.push('✅ MIGRASI SELESAI — admin A sudah dihapus dari sistem.');

            res.status(200).json({
                success: true,
                dryRun: false,
                from: { uid: fromAdminUid, name: fromData.name },
                to: { uid: toAdminUid, name: toName },
                cabangId,
                stats,
                log,
            });

        } catch (err) {
            console.error('[migrasiAdmin] Error:', err);
            log.push(`❌ Error: ${err.message}`);
            res.status(500).json({ success: false, error: err.message, log });
        }
    });
