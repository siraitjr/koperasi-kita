// =========================================================================
// DATA INTEGRITY FIX - One-time script
// =========================================================================
// Jalankan via: firebase functions:shell > dataIntegrityFix()
// Atau deploy sebagai HTTPS callable lalu panggil sekali
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');
const db = admin.database();

/**
 * Fix 1: Hapus nasabah ghost (tanpa status, tanpa nama, tanpa pelunasan)
 * Fix 2: Isi cabangId yang kosong berdasarkan metadata admin
 * Fix 3: Konversi pembayaranList dari dict ke array (3 nasabah)
 */
exports.dataIntegrityFix = functions
    .region('asia-southeast1')
    .runWith({ timeoutSeconds: 540, memory: '1GB' })
    .https.onRequest(async (req, res) => {
        // Safety: hanya bisa dipanggil sekali, harus pakai query param ?confirm=yes
        if (req.query.confirm !== 'yes') {
            res.status(200).json({
                message: 'DRY RUN - tambahkan ?confirm=yes untuk eksekusi',
                description: 'Script ini akan: (1) hapus ghost entries, (2) isi cabangId kosong, (3) fix pembayaranList dict→array'
            });
            return;
        }

        const results = { ghostDeleted: 0, cabangFixed: 0, pembayaranFixed: 0, errors: [] };

        try {
            // Build admin → cabang mapping
            const adminsSnap = await db.ref('metadata/admins').once('value');
            const adminCabang = {};
            adminsSnap.forEach(child => {
                const data = child.val();
                if (data && data.cabang) {
                    adminCabang[child.key] = data.cabang;
                }
            });

            // Read all pelanggan
            const pelangganSnap = await db.ref('pelanggan').once('value');
            const updates = {};

            pelangganSnap.forEach(adminChild => {
                const adminUid = adminChild.key;
                const cabangForAdmin = adminCabang[adminUid] || '';

                adminChild.forEach(pelChild => {
                    const pid = pelChild.key;
                    const p = pelChild.val();
                    if (!p || typeof p !== 'object') return;

                    const path = `pelanggan/${adminUid}/${pid}`;
                    const status = (p.status || '').trim();
                    const nama = (p.namaPanggilan || p.namaKtp || '').trim();
                    const totalPelunasan = p.totalPelunasan || 0;

                    // Fix 1: Ghost entries — tanpa status, tanpa nama, tanpa pelunasan
                    if (!status && !nama && totalPelunasan === 0) {
                        updates[path] = null; // Delete
                        results.ghostDeleted++;
                        return;
                    }

                    // Fix 2: Isi cabangId kosong
                    const cabangId = (p.cabangId || '').trim();
                    if (!cabangId && cabangForAdmin) {
                        updates[`${path}/cabangId`] = cabangForAdmin;
                        results.cabangFixed++;
                    }

                    // Fix 3: Konversi pembayaranList dict → array
                    if (p.pembayaranList && !Array.isArray(p.pembayaranList) && typeof p.pembayaranList === 'object') {
                        const keys = Object.keys(p.pembayaranList);
                        const isNumericKeys = keys.every(k => !isNaN(Number(k)));
                        if (isNumericKeys && keys.length > 0) {
                            // Convert to array maintaining order
                            const maxKey = Math.max(...keys.map(Number));
                            const arr = [];
                            for (let i = 0; i <= maxKey; i++) {
                                arr.push(p.pembayaranList[String(i)] || null);
                            }
                            updates[`${path}/pembayaranList`] = arr;
                            results.pembayaranFixed++;
                        }
                    }
                });
            });

            // Apply updates in batches (Firebase multi-path update limit ~256KB)
            const updateKeys = Object.keys(updates);
            console.log(`Total updates: ${updateKeys.length}`);

            if (updateKeys.length > 0) {
                // Split into batches of 100
                const batchSize = 100;
                for (let i = 0; i < updateKeys.length; i += batchSize) {
                    const batch = {};
                    updateKeys.slice(i, i + batchSize).forEach(key => {
                        batch[key] = updates[key];
                    });
                    await db.ref().update(batch);
                    console.log(`Batch ${Math.floor(i / batchSize) + 1} applied (${Object.keys(batch).length} updates)`);
                }
            }

            res.status(200).json({
                success: true,
                results: results,
                totalUpdates: updateKeys.length
            });

        } catch (error) {
            console.error('Data integrity fix error:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });
