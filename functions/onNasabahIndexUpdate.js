// =========================================================================
// NASABAH INDEX UPDATER - Maintain index ringan untuk Pimpinan
// =========================================================================
// 
// File ini membuat dan memelihara index nasabah yang ringan (~150 bytes per nasabah)
// agar Pimpinan bisa melihat daftar semua nasabah tanpa load data lengkap (~3KB per nasabah)
//
// Struktur index:
// summary/nasabahIndex/{cabangId}/{pelangganId}
//   - id
//   - nama
//   - namaKtp
//   - status
//   - adminUid
//   - adminName
//   - wilayah
//   - besarPinjaman
//   - totalPelunasan
//   - sisaHutang
//   - isLunas
//   - tanggalDaftar
//   - pinjamanKe
//   - lastUpdated
//
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

/**
 * Trigger ketika ada perubahan di pelanggan
 * Update index nasabah untuk Pimpinan
 */
exports.updateNasabahIndex = functions
    .region('asia-southeast1')
    .database.ref('/pelanggan/{adminUid}/{pelangganId}')
    .onWrite(async (change, context) => {
        const { adminUid, pelangganId } = context.params;
        
        try {
            // Jika data dihapus
            if (!change.after.exists()) {
                // Ambil cabangId dari before data
                const beforeData = change.before.val();
                if (beforeData && beforeData.cabangId) {
                    await db.ref(`summary/nasabahIndex/${beforeData.cabangId}/${pelangganId}`).remove();
                    console.log(`✅ Removed index for ${pelangganId}`);
                }
                return null;
            }
            
            const data = change.after.val();
            
            // Skip jika status masih menunggu approval atau ditolak
            if (data.status === 'Menunggu Approval' || data.status === 'Ditolak') {
                // Hapus dari index jika sebelumnya ada
                if (change.before.exists()) {
                    const beforeData = change.before.val();
                    if (beforeData && beforeData.cabangId) {
                        await db.ref(`summary/nasabahIndex/${beforeData.cabangId}/${pelangganId}`).remove();
                        console.log(`✅ Removed rejected/pending from index: ${pelangganId}`);
                    }
                }
                return null;
            }
            
            const cabangId = data.cabangId;
            if (!cabangId) {
                console.log(`⚠️ No cabangId for ${pelangganId}`);
                return null;
            }
            
            // Hitung sisa hutang
            let totalDibayar = 0;
            const pembayaranList = data.pembayaranList || [];
            const pembayaranArray = Array.isArray(pembayaranList) 
                ? pembayaranList 
                : Object.values(pembayaranList);
            
            pembayaranArray.forEach(pay => {
                if (pay && pay.tanggal && !pay.tanggal.startsWith('Bunga')) {
                    totalDibayar += (pay.jumlah || 0);
                    if (pay.subPembayaran && Array.isArray(pay.subPembayaran)) {
                        pay.subPembayaran.forEach(sub => {
                            totalDibayar += (sub.jumlah || 0);
                        });
                    }
                }
            });
            
            const totalPelunasan = data.totalPelunasan || 0;
            const sisaHutang = Math.max(0, totalPelunasan - totalDibayar);
            
            // ✅ BARU: Ambil status khusus dan pencairan simpanan
            const statusKhusus = (data.statusKhusus || '').toUpperCase().replace(/ /g, '_');
            const statusPencairanSimpanan = (data.statusPencairanSimpanan || '').trim();
            
            const status = (data.status || '').toLowerCase();
            const isSudahLunas = totalPelunasan > 0 && totalDibayar >= totalPelunasan;
            const isStatusLunas = status === 'lunas';
            const isMenungguPencairanManual = statusKhusus === 'MENUNGGU_PENCAIRAN' && 
                                              statusPencairanSimpanan !== 'Dicairkan';
            const isLunasOtomatis = (isSudahLunas || isStatusLunas) && statusPencairanSimpanan !== 'Dicairkan';
            const isMenungguPencairan = isMenungguPencairanManual || isLunasOtomatis;
            const isLunas = (isSudahLunas || isStatusLunas) && statusPencairanSimpanan === 'Dicairkan';
            
            // ✅ PERBAIKAN: Lookup adminName dari metadata jika kosong
            let adminName = data.adminName;
            if (!adminName || adminName.trim() === '' || adminName === '-') {
                try {
                    const adminMetaSnap = await db.ref(`metadata/admins/${adminUid}/name`).get();
                    if (adminMetaSnap.exists()) {
                        adminName = adminMetaSnap.val();
                        console.log(`✅ Looked up adminName for ${adminUid}: ${adminName}`);
                    } else {
                        adminName = '-';
                    }
                } catch (lookupError) {
                    console.log(`⚠️ Could not lookup adminName: ${lookupError.message}`);
                    adminName = '-';
                }
            }
            
            // Data minimal untuk index
            const indexData = {
                id: pelangganId,
                nama: data.namaPanggilan || data.namaKtp || '-',
                namaKtp: data.namaKtp || '-',
                status: data.status || '-',
                adminUid: adminUid,
                adminName: adminName,
                wilayah: data.wilayah || '-',
                besarPinjaman: data.besarPinjaman || 0,
                totalPelunasan: totalPelunasan,
                sisaHutang: sisaHutang,
                simpanan: data.simpanan || 0,
                isLunas: isLunas,
                isMenungguPencairan: isMenungguPencairan, // ✅ BARU
                statusKhusus: statusKhusus, // ✅ BARU
                statusPencairanSimpanan: statusPencairanSimpanan, // ✅ BARU
                tanggalDaftar: data.tanggalDaftar || data.tanggalPengajuan || '-',
                pinjamanKe: data.pinjamanKe || 1,
                lastUpdated: admin.database.ServerValue.TIMESTAMP
            };
            
            // Update index
            await db.ref(`summary/nasabahIndex/${cabangId}/${pelangganId}`).set(indexData);
            console.log(`✅ Updated index for ${pelangganId} in cabang ${cabangId} (PDL: ${adminName})`);
            
            return null;
            
        } catch (error) {
            console.error('❌ Error updating index:', error);
            return null;
        }
    });

/**
 * Backfill index untuk nasabah yang sudah ada
 * Panggil sekali untuk migrasi awal
 * 
 * URL: https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net/backfillNasabahIndex
 */
exports.backfillNasabahIndex = functions
    .region('asia-southeast1')
    .runWith({
        timeoutSeconds: 540,  // 9 menit
        memory: '1GB'
    })
    .https.onRequest(async (req, res) => {
        // CORS
        res.set('Access-Control-Allow-Origin', '*');
        
        try {
            console.log('🔄 Starting backfill nasabah index...');
            
            // ✅ PERBAIKAN: Load semua metadata admin dulu untuk lookup nama
            const adminMetaSnap = await db.ref('metadata/admins').get();
            const adminNameMap = {};
            if (adminMetaSnap.exists()) {
                adminMetaSnap.forEach(adminSnap => {
                    const adminData = adminSnap.val();
                    adminNameMap[adminSnap.key] = adminData.name || adminData.nama || '-';
                });
            }
            console.log(`✅ Loaded ${Object.keys(adminNameMap).length} admin names for lookup`);
            
            const pelangganSnap = await db.ref('pelanggan').get();
            
            if (!pelangganSnap.exists()) {
                res.json({ success: false, message: 'No pelanggan data' });
                return;
            }
            
            let count = 0;
            let skipped = 0;
            let updatedAdminName = 0;
            const updates = {};
            
            pelangganSnap.forEach(adminSnap => {
                const adminUid = adminSnap.key;
                
                adminSnap.forEach(pelangganSnap => {
                    const pelangganId = pelangganSnap.key;
                    const data = pelangganSnap.val();
                    
                    // Skip jika tidak punya cabangId atau status tidak valid
                    if (!data.cabangId || data.status === 'Menunggu Approval' || data.status === 'Ditolak') {
                        skipped++;
                        return;
                    }
                    
                    // Hitung sisa hutang
                    let totalDibayar = 0;
                    const pembayaranList = data.pembayaranList || [];
                    const pembayaranArray = Array.isArray(pembayaranList) 
                        ? pembayaranList 
                        : Object.values(pembayaranList);
                    
                    pembayaranArray.forEach(pay => {
                        if (pay && pay.tanggal && !pay.tanggal.startsWith('Bunga')) {
                            totalDibayar += (pay.jumlah || 0);
                            if (pay.subPembayaran && Array.isArray(pay.subPembayaran)) {
                                pay.subPembayaran.forEach(sub => {
                                    totalDibayar += (sub.jumlah || 0);
                                });
                            }
                        }
                    });
                    
                    const totalPelunasan = data.totalPelunasan || 0;
                    const sisaHutang = Math.max(0, totalPelunasan - totalDibayar);
                    
                    // ✅ BARU: Status pencairan
                    const statusKhusus = (data.statusKhusus || '').toUpperCase().replace(/ /g, '_');
                    const statusPencairanSimpanan = (data.statusPencairanSimpanan || '').trim();
                    
                    const isSudahLunas = totalPelunasan > 0 && totalDibayar >= totalPelunasan;
                    const status = (data.status || '').toLowerCase();
                    const isStatusLunas = status === 'lunas';
                    const isMenungguPencairanManual = statusKhusus === 'MENUNGGU_PENCAIRAN' && 
                                                    statusPencairanSimpanan !== 'Dicairkan';
                    const isLunasOtomatis = (isSudahLunas || isStatusLunas) && statusPencairanSimpanan !== 'Dicairkan';
                    const isMenungguPencairan = isMenungguPencairanManual || isLunasOtomatis;
                    const isLunas = (isSudahLunas || isStatusLunas) && statusPencairanSimpanan === 'Dicairkan';
                    
                    // ✅ PERBAIKAN: Lookup adminName jika kosong
                    let adminName = data.adminName;
                    if (!adminName || adminName.trim() === '' || adminName === '-') {
                        adminName = adminNameMap[adminUid] || '-';
                    }
                    
                    const indexData = {
                        id: pelangganId,
                        nama: data.namaPanggilan || data.namaKtp || '-',
                        namaKtp: data.namaKtp || '-',
                        status: data.status || '-',
                        adminUid: adminUid,
                        adminName: adminName,
                        wilayah: data.wilayah || '-',
                        besarPinjaman: data.besarPinjaman || 0,
                        totalPelunasan: totalPelunasan,
                        sisaHutang: sisaHutang,
                        simpanan: data.simpanan || 0,
                        isLunas: isLunas,
                        isMenungguPencairan: isMenungguPencairan, // ✅ BARU
                        statusKhusus: statusKhusus, // ✅ BARU
                        statusPencairanSimpanan: statusPencairanSimpanan, // ✅ BARU
                        tanggalDaftar: data.tanggalDaftar || data.tanggalPengajuan || '-',
                        pinjamanKe: data.pinjamanKe || 1,
                        lastUpdated: Date.now()
                    };
                    
                    updates[`summary/nasabahIndex/${data.cabangId}/${pelangganId}`] = indexData;
                    count++;
                });
            });
            
            // Batch update (split jika terlalu besar)
            const updateKeys = Object.keys(updates);
            const batchSize = 500;
            
            for (let i = 0; i < updateKeys.length; i += batchSize) {
                const batch = {};
                updateKeys.slice(i, i + batchSize).forEach(key => {
                    batch[key] = updates[key];
                });
                await db.ref().update(batch);
                console.log(`✅ Batch ${Math.floor(i/batchSize) + 1} updated (${Object.keys(batch).length} items)`);
            }
            
            console.log(`✅ Backfill complete: ${count} indexed, ${skipped} skipped, ${updatedAdminName} adminName updated`);
            
            res.json({ 
                success: true, 
                message: `Backfilled ${count} nasabah indexes, ${skipped} skipped, ${updatedAdminName} adminName updated from metadata` 
            });
            
        } catch (error) {
            console.error('❌ Backfill error:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });

/**
 * Hapus semua index nasabah (untuk reset/cleanup)
 * 
 * URL: https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net/clearNasabahIndex?cabangId=xxx
 */
exports.clearNasabahIndex = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        res.set('Access-Control-Allow-Origin', '*');
        
        try {
            const cabangId = req.query.cabangId;
            
            if (cabangId) {
                // Clear specific cabang
                await db.ref(`summary/nasabahIndex/${cabangId}`).remove();
                res.json({ success: true, message: `Cleared index for cabang: ${cabangId}` });
            } else {
                // Clear all
                await db.ref('summary/nasabahIndex').remove();
                res.json({ success: true, message: 'Cleared all nasabah indexes' });
            }
            
        } catch (error) {
            console.error('Clear error:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });

/**
 * Get statistics of nasabah index
 * 
 * URL: https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net/getNasabahIndexStats
 */
exports.getNasabahIndexStats = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        res.set('Access-Control-Allow-Origin', '*');
        
        try {
            const indexSnap = await db.ref('summary/nasabahIndex').get();
            
            if (!indexSnap.exists()) {
                res.json({ 
                    success: true, 
                    data: { totalCabang: 0, totalNasabah: 0, perCabang: {} } 
                });
                return;
            }
            
            const stats = {
                totalCabang: 0,
                totalNasabah: 0,
                totalAktif: 0,
                totalLunas: 0,
                perCabang: {}
            };
            
            indexSnap.forEach(cabangSnap => {
                const cabangId = cabangSnap.key;
                const count = cabangSnap.numChildren();
                
                let aktif = 0;
                let lunas = 0;
                
                cabangSnap.forEach(nasabahSnap => {
                    const data = nasabahSnap.val();
                    if (data.isLunas) {
                        lunas++;
                    } else {
                        aktif++;
                    }
                });
                
                stats.perCabang[cabangId] = { total: count, aktif, lunas };
                stats.totalCabang++;
                stats.totalNasabah += count;
                stats.totalAktif += aktif;
                stats.totalLunas += lunas;
            });
            
            res.json({ success: true, data: stats });
            
        } catch (error) {
            console.error('Stats error:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });