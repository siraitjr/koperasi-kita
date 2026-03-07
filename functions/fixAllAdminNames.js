// functions/fixAllAdminNames.js
// =========================================================================
// CLOUD FUNCTION: FIX ALL ADMIN NAMES (Email → Nama)
// =========================================================================
//
// Fungsi ini memperbaiki SEMUA data di Firebase yang menyimpan
// email admin di field yang seharusnya berisi nama admin.
//
// YANG DIPERBAIKI:
// 1. pelanggan_status_khusus/{cabang}/{id}     → adminName & diberiTandaOleh
// 2. pelanggan/{uid}/{id}/dualApprovalInfo      → pimpinan/koordinator/pengawasApproval.by
// 3. pelanggan/{uid}/{id}/disetujuiOleh         → email → nama
// 4. nik_registry/{nik}                         → adminName
// 5. pembayaran_harian/{cabang}/{tgl}           → adminName (semua entry)
// 6. event_harian/{cabang}/{tgl}                → adminName (nasabah_baru & nasabah_lunas)
// 7. pelanggan_bermasalah/{cabang}              → adminName
//
// URL: https://us-central1-koperasikitagodangulu.cloudfunctions.net/fixAllAdminNames
//
// AMAN: Fungsi ini HANYA mengubah field nama, tidak mengubah data lain.
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

/**
 * Helper: Cek apakah string terlihat seperti email
 */
function looksLikeEmail(str) {
    if (!str || typeof str !== 'string') return false;
    return str.includes('@') && str.includes('.');
}

/**
 * Helper: Batch update dengan split otomatis (Firebase max ~1000 paths per update)
 */
async function batchUpdate(updates, label) {
    const keys = Object.keys(updates);
    if (keys.length === 0) {
        console.log(`   ⏭️ ${label}: Tidak ada yang perlu diupdate`);
        return 0;
    }

    const BATCH_SIZE = 500;
    for (let i = 0; i < keys.length; i += BATCH_SIZE) {
        const batch = {};
        keys.slice(i, i + BATCH_SIZE).forEach(key => {
            batch[key] = updates[key];
        });
        await db.ref().update(batch);
        console.log(`   ✅ ${label}: Batch ${Math.floor(i / BATCH_SIZE) + 1} updated (${Object.keys(batch).length} items)`);
    }

    return keys.length;
}

/**
 * MAIN FUNCTION
 */
exports.fixAllAdminNames = functions
    .runWith({
        timeoutSeconds: 540,
        memory: '1GB'
    })
    .https.onRequest(async (req, res) => {
        // CORS
        res.set('Access-Control-Allow-Origin', '*');
        if (req.method === 'OPTIONS') {
            res.set('Access-Control-Allow-Methods', 'GET');
            res.set('Access-Control-Allow-Headers', 'Content-Type');
            res.status(204).send('');
            return;
        }

        console.log('🔧 =====================================');
        console.log('🔧 FIX ALL ADMIN NAMES');
        console.log('🔧 =====================================');
        console.log(`🕐 Start: ${new Date().toISOString()}`);

        const stats = {
            statusKhusus: { checked: 0, fixed: 0 },
            approvalBy: { checked: 0, fixed: 0 },
            disetujuiOleh: { checked: 0, fixed: 0 },
            nikRegistry: { checked: 0, fixed: 0 },
            pembayaranHarian: { checked: 0, fixed: 0 },
            eventHarian: { checked: 0, fixed: 0 },
            pelangganBermasalah: { checked: 0, fixed: 0 }
        };

        try {
            // =================================================================
            // STEP 0: Build lookup maps
            // =================================================================
            console.log('\n📋 STEP 0: Building admin lookup maps...');

            const adminsSnap = await db.ref('metadata/admins').once('value');
            const adminsData = adminsSnap.val() || {};

            // Map UID → name
            const uidToName = {};
            // Map email → name (untuk fix approval.by yang berisi email)
            const emailToName = {};
            // Map UID → email
            const uidToEmail = {};

            for (const [uid, data] of Object.entries(adminsData)) {
                const name = data.name || data.nama || '';
                const email = data.email || '';

                if (name) {
                    uidToName[uid] = name;
                }
                if (email) {
                    uidToEmail[uid] = email;
                    if (name) {
                        emailToName[email.toLowerCase()] = name;
                    }
                }
            }

            console.log(`   ✅ ${Object.keys(uidToName).length} UID→Name mappings`);
            console.log(`   ✅ ${Object.keys(emailToName).length} Email→Name mappings`);

            // =================================================================
            // STEP 1: Fix pelanggan_status_khusus
            // =================================================================
            console.log('\n📋 STEP 1: Fixing pelanggan_status_khusus...');

            const statusKhususSnap = await db.ref('pelanggan_status_khusus').once('value');

            if (statusKhususSnap.exists()) {
                const updates = {};

                statusKhususSnap.forEach(cabangSnap => {
                    const cabangId = cabangSnap.key;

                    cabangSnap.forEach(itemSnap => {
                        const itemId = itemSnap.key;
                        const data = itemSnap.val();
                        stats.statusKhusus.checked++;

                        const adminUid = data.adminUid || '';
                        const currentAdminName = data.adminName || '';
                        const currentDiberiTandaOleh = data.diberiTandaOleh || '';
                        const basePath = `pelanggan_status_khusus/${cabangId}/${itemId}`;

                        // Fix adminName jika kosong atau berisi email
                        if ((!currentAdminName || looksLikeEmail(currentAdminName)) && adminUid && uidToName[adminUid]) {
                            updates[`${basePath}/adminName`] = uidToName[adminUid];
                            stats.statusKhusus.fixed++;
                        }

                        // Fix diberiTandaOleh jika berisi email
                        if (looksLikeEmail(currentDiberiTandaOleh)) {
                            const resolvedName = emailToName[currentDiberiTandaOleh.toLowerCase()];
                            if (resolvedName) {
                                updates[`${basePath}/diberiTandaOleh`] = resolvedName;
                            }
                        }
                    });
                });

                await batchUpdate(updates, 'pelanggan_status_khusus');
            }

            console.log(`   📊 Checked: ${stats.statusKhusus.checked}, Fixed: ${stats.statusKhusus.fixed}`);

            // =================================================================
            // STEP 2: Fix dualApprovalInfo.*.by & disetujuiOleh di pelanggan
            // =================================================================
            console.log('\n📋 STEP 2: Fixing pelanggan approval fields...');

            const pelangganSnap = await db.ref('pelanggan').once('value');

            if (pelangganSnap.exists()) {
                const updates = {};

                pelangganSnap.forEach(adminSnap => {
                    const adminUid = adminSnap.key;

                    adminSnap.forEach(pelSnap => {
                        const pelId = pelSnap.key;
                        const data = pelSnap.val();
                        const basePath = `pelanggan/${adminUid}/${pelId}`;

                        // --- Fix disetujuiOleh ---
                        const disetujuiOleh = data.disetujuiOleh || '';
                        if (looksLikeEmail(disetujuiOleh)) {
                            stats.disetujuiOleh.checked++;
                            const resolvedName = emailToName[disetujuiOleh.toLowerCase()];
                            if (resolvedName) {
                                updates[`${basePath}/disetujuiOleh`] = resolvedName;
                                stats.disetujuiOleh.fixed++;
                            }
                        }

                        // --- Fix dualApprovalInfo ---
                        const dualInfo = data.dualApprovalInfo;
                        if (!dualInfo) return;

                        // Fix pimpinanApproval.by
                        const pimpinanBy = dualInfo.pimpinanApproval?.by || '';
                        if (looksLikeEmail(pimpinanBy)) {
                            stats.approvalBy.checked++;
                            // Coba resolve via UID dulu, lalu email
                            const pimpinanUid = dualInfo.pimpinanApproval?.uid || '';
                            const resolvedName = (pimpinanUid && uidToName[pimpinanUid])
                                ? uidToName[pimpinanUid]
                                : emailToName[pimpinanBy.toLowerCase()];

                            if (resolvedName) {
                                updates[`${basePath}/dualApprovalInfo/pimpinanApproval/by`] = resolvedName;
                                stats.approvalBy.fixed++;
                            }
                        }

                        // Fix koordinatorApproval.by
                        const koordinatorBy = dualInfo.koordinatorApproval?.by || '';
                        if (looksLikeEmail(koordinatorBy)) {
                            stats.approvalBy.checked++;
                            const koordinatorUid = dualInfo.koordinatorApproval?.uid || '';
                            const resolvedName = (koordinatorUid && uidToName[koordinatorUid])
                                ? uidToName[koordinatorUid]
                                : emailToName[koordinatorBy.toLowerCase()];

                            if (resolvedName) {
                                updates[`${basePath}/dualApprovalInfo/koordinatorApproval/by`] = resolvedName;
                                stats.approvalBy.fixed++;
                            }
                        }

                        // Fix pengawasApproval.by
                        const pengawasBy = dualInfo.pengawasApproval?.by || '';
                        if (looksLikeEmail(pengawasBy)) {
                            stats.approvalBy.checked++;
                            const pengawasUid = dualInfo.pengawasApproval?.uid || '';
                            const resolvedName = (pengawasUid && uidToName[pengawasUid])
                                ? uidToName[pengawasUid]
                                : emailToName[pengawasBy.toLowerCase()];

                            if (resolvedName) {
                                updates[`${basePath}/dualApprovalInfo/pengawasApproval/by`] = resolvedName;
                                stats.approvalBy.fixed++;
                            }
                        }
                    });
                });

                await batchUpdate(updates, 'pelanggan approval');
            }

            console.log(`   📊 approval.by - Checked: ${stats.approvalBy.checked}, Fixed: ${stats.approvalBy.fixed}`);
            console.log(`   📊 disetujuiOleh - Checked: ${stats.disetujuiOleh.checked}, Fixed: ${stats.disetujuiOleh.fixed}`);

            // =================================================================
            // STEP 3: Fix nik_registry
            // =================================================================
            console.log('\n📋 STEP 3: Fixing nik_registry...');

            const nikSnap = await db.ref('nik_registry').once('value');

            if (nikSnap.exists()) {
                const updates = {};

                nikSnap.forEach(nikEntry => {
                    const nik = nikEntry.key;
                    const data = nikEntry.val();
                    stats.nikRegistry.checked++;

                    const currentAdminName = data.adminName || '';
                    const adminUid = data.adminUid || '';

                    if (looksLikeEmail(currentAdminName)) {
                        // Coba resolve via UID dulu, lalu via email
                        const resolvedName = (adminUid && uidToName[adminUid])
                            ? uidToName[adminUid]
                            : emailToName[currentAdminName.toLowerCase()];

                        if (resolvedName) {
                            updates[`nik_registry/${nik}/adminName`] = resolvedName;
                            stats.nikRegistry.fixed++;
                        }
                    }
                });

                await batchUpdate(updates, 'nik_registry');
            }

            console.log(`   📊 Checked: ${stats.nikRegistry.checked}, Fixed: ${stats.nikRegistry.fixed}`);

            // =================================================================
            // STEP 4: Fix pembayaran_harian
            // =================================================================
            console.log('\n📋 STEP 4: Fixing pembayaran_harian...');

            const pembayaranSnap = await db.ref('pembayaran_harian').once('value');

            if (pembayaranSnap.exists()) {
                const updates = {};

                pembayaranSnap.forEach(cabangSnap => {
                    const cabangId = cabangSnap.key;

                    cabangSnap.forEach(dateSnap => {
                        const date = dateSnap.key;
                        const dayData = dateSnap.val();

                        // Fix adminName di top-level pembayaran entries
                        if (dayData && typeof dayData === 'object') {
                            for (const [entryKey, entry] of Object.entries(dayData)) {
                                if (!entry || typeof entry !== 'object') continue;

                                // Skip non-data keys
                                if (entryKey === 'totalHarian' || entryKey === 'timestamp') continue;

                                stats.pembayaranHarian.checked++;

                                const currentName = entry.adminName || '';
                                const adminUid = entry.adminUid || '';

                                if (looksLikeEmail(currentName)) {
                                    const resolvedName = (adminUid && uidToName[adminUid])
                                        ? uidToName[adminUid]
                                        : emailToName[currentName.toLowerCase()];

                                    if (resolvedName) {
                                        updates[`pembayaran_harian/${cabangId}/${date}/${entryKey}/adminName`] = resolvedName;
                                        stats.pembayaranHarian.fixed++;
                                    }
                                }

                                // Fix sub-pembayaran juga
                                if (entry.subPembayaran && typeof entry.subPembayaran === 'object') {
                                    for (const [subKey, sub] of Object.entries(entry.subPembayaran)) {
                                        if (sub && sub.adminName && looksLikeEmail(sub.adminName)) {
                                            const subAdminUid = sub.adminUid || adminUid;
                                            const resolvedName = (subAdminUid && uidToName[subAdminUid])
                                                ? uidToName[subAdminUid]
                                                : emailToName[sub.adminName.toLowerCase()];

                                            if (resolvedName) {
                                                updates[`pembayaran_harian/${cabangId}/${date}/${entryKey}/subPembayaran/${subKey}/adminName`] = resolvedName;
                                                stats.pembayaranHarian.fixed++;
                                            }
                                        }
                                    }
                                }

                                // Fix pencairan entries
                                if (entry.pencairanSimpanan && typeof entry.pencairanSimpanan === 'object') {
                                    for (const [pcKey, pc] of Object.entries(entry.pencairanSimpanan)) {
                                        if (pc && pc.adminName && looksLikeEmail(pc.adminName)) {
                                            const pcAdminUid = pc.adminUid || adminUid;
                                            const resolvedName = (pcAdminUid && uidToName[pcAdminUid])
                                                ? uidToName[pcAdminUid]
                                                : emailToName[pc.adminName.toLowerCase()];

                                            if (resolvedName) {
                                                updates[`pembayaran_harian/${cabangId}/${date}/${entryKey}/pencairanSimpanan/${pcKey}/adminName`] = resolvedName;
                                                stats.pembayaranHarian.fixed++;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    });
                });

                await batchUpdate(updates, 'pembayaran_harian');
            }

            console.log(`   📊 Checked: ${stats.pembayaranHarian.checked}, Fixed: ${stats.pembayaranHarian.fixed}`);

            // =================================================================
            // STEP 5: Fix event_harian (nasabah_baru & nasabah_lunas)
            // =================================================================
            console.log('\n📋 STEP 5: Fixing event_harian...');

            const eventSnap = await db.ref('event_harian').once('value');

            if (eventSnap.exists()) {
                const updates = {};

                eventSnap.forEach(cabangSnap => {
                    const cabangId = cabangSnap.key;

                    cabangSnap.forEach(dateSnap => {
                        const date = dateSnap.key;
                        const dayData = dateSnap.val();

                        // Fix nasabah_baru
                        const nasabahBaru = dayData?.nasabah_baru;
                        if (nasabahBaru && typeof nasabahBaru === 'object') {
                            for (const [key, entry] of Object.entries(nasabahBaru)) {
                                if (!entry || typeof entry !== 'object') continue;
                                stats.eventHarian.checked++;

                                const currentName = entry.adminName || '';
                                const adminUid = entry.adminUid || '';

                                if (looksLikeEmail(currentName)) {
                                    const resolvedName = (adminUid && uidToName[adminUid])
                                        ? uidToName[adminUid]
                                        : emailToName[currentName.toLowerCase()];

                                    if (resolvedName) {
                                        updates[`event_harian/${cabangId}/${date}/nasabah_baru/${key}/adminName`] = resolvedName;
                                        stats.eventHarian.fixed++;
                                    }
                                }
                            }
                        }

                        // Fix nasabah_lunas
                        const nasabahLunas = dayData?.nasabah_lunas;
                        if (nasabahLunas && typeof nasabahLunas === 'object') {
                            for (const [key, entry] of Object.entries(nasabahLunas)) {
                                if (!entry || typeof entry !== 'object') continue;
                                stats.eventHarian.checked++;

                                const currentName = entry.adminName || '';
                                const adminUid = entry.adminUid || '';

                                if (looksLikeEmail(currentName)) {
                                    const resolvedName = (adminUid && uidToName[adminUid])
                                        ? uidToName[adminUid]
                                        : emailToName[currentName.toLowerCase()];

                                    if (resolvedName) {
                                        updates[`event_harian/${cabangId}/${date}/nasabah_lunas/${key}/adminName`] = resolvedName;
                                        stats.eventHarian.fixed++;
                                    }
                                }
                            }
                        }
                    });
                });

                await batchUpdate(updates, 'event_harian');
            }

            console.log(`   📊 Checked: ${stats.eventHarian.checked}, Fixed: ${stats.eventHarian.fixed}`);

            // =================================================================
            // STEP 6: Fix pelanggan_bermasalah
            // =================================================================
            console.log('\n📋 STEP 6: Fixing pelanggan_bermasalah...');

            const bermasalahSnap = await db.ref('pelanggan_bermasalah').once('value');

            if (bermasalahSnap.exists()) {
                const updates = {};

                bermasalahSnap.forEach(cabangSnap => {
                    const cabangId = cabangSnap.key;

                    cabangSnap.forEach(itemSnap => {
                        const itemId = itemSnap.key;
                        const data = itemSnap.val();
                        stats.pelangganBermasalah.checked++;

                        const currentName = data.adminName || '';
                        const adminUid = data.adminUid || '';

                        if (looksLikeEmail(currentName)) {
                            const resolvedName = (adminUid && uidToName[adminUid])
                                ? uidToName[adminUid]
                                : emailToName[currentName.toLowerCase()];

                            if (resolvedName) {
                                updates[`pelanggan_bermasalah/${cabangId}/${itemId}/adminName`] = resolvedName;
                                stats.pelangganBermasalah.fixed++;
                            }
                        }
                    });
                });

                await batchUpdate(updates, 'pelanggan_bermasalah');
            }

            console.log(`   📊 Checked: ${stats.pelangganBermasalah.checked}, Fixed: ${stats.pelangganBermasalah.fixed}`);

            // =================================================================
            // DONE
            // =================================================================
            const totalFixed = stats.statusKhusus.fixed +
                stats.approvalBy.fixed +
                stats.disetujuiOleh.fixed +
                stats.nikRegistry.fixed +
                stats.pembayaranHarian.fixed +
                stats.eventHarian.fixed +
                stats.pelangganBermasalah.fixed;

            console.log('\n🎉 =====================================');
            console.log('🎉 FIX COMPLETE!');
            console.log('🎉 =====================================');
            console.log(`📊 Total fixed: ${totalFixed}`);
            console.log(`🕐 End: ${new Date().toISOString()}`);

            res.json({
                success: true,
                message: `Fixed ${totalFixed} admin name fields across all data`,
                stats: stats,
                adminMappings: {
                    uidToName: Object.keys(uidToName).length,
                    emailToName: Object.keys(emailToName).length
                }
            });

        } catch (error) {
            console.error('❌ Error:', error);
            res.status(500).json({
                success: false,
                error: error.message,
                stats: stats
            });
        }
    });