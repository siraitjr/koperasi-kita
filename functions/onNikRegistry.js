// functions/onNikRegistry.js
// =========================================================================
// CLOUD FUNCTION: NIK REGISTRY - OTOMATIS SYNC
// =========================================================================
//
// FUNGSI INI MENGELOLA NODE nik_registry SECARA OTOMATIS:
// ✅ Register NIK saat nasabah baru ditambahkan
// ✅ Update status saat nasabah lunas
// ✅ Hapus NIK saat nasabah dihapus
// ✅ HTTP Function untuk migrasi data existing (SEKALI PAKAI)
//
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

// Import helper dari summaryHelpers
const { getTodayIndonesia } = require('./summaryHelpers');

// =========================================================================
// HELPER: Cek apakah nasabah sudah lunas
// =========================================================================
function isNasabahLunas(pelanggan) {
    const totalPelunasan = pelanggan.totalPelunasan || 0;
    if (totalPelunasan <= 0) return false;
    
    let totalDibayar = 0;
    if (pelanggan.pembayaranList) {
        const pembayaranList = Array.isArray(pelanggan.pembayaranList)
            ? pelanggan.pembayaranList
            : Object.values(pelanggan.pembayaranList || {});
        pembayaranList.forEach(p => {
            totalDibayar += p.jumlah || 0;
            if (p.subPembayaran) {
                const subList = Array.isArray(p.subPembayaran)
                    ? p.subPembayaran
                    : Object.values(p.subPembayaran || {});
                subList.forEach(sub => totalDibayar += sub.jumlah || 0);
            }
        });
    }
    
    return totalDibayar >= totalPelunasan;
}

// =========================================================================
// HELPER: Register NIK ke Registry
// =========================================================================
async function registerNikToRegistry(nik, nikType, adminUid, adminData, pelangganId, pelanggan, cabangData) {
    if (!nik || nik.length !== 16) return;
    
    try {
        // Tentukan nama berdasarkan nikType
        let nama = '';
        if (nikType === 'nik') {
            nama = pelanggan.namaPanggilan || pelanggan.namaKtp || '';
        } else if (nikType === 'nikSuami') {
            nama = pelanggan.namaPanggilanSuami || pelanggan.namaKtpSuami || '';
        } else if (nikType === 'nikIstri') {
            nama = pelanggan.namaPanggilanIstri || pelanggan.namaKtpIstri || '';
        }
        
        const entry = {
            adminUid: adminUid,
            adminName: adminData.name || adminData.displayName || adminData.email || 'Admin',
            pelangganId: pelangganId,
            nama: nama,
            cabangId: adminData.cabang || '',
            cabangName: cabangData?.name || adminData.cabang || '',
            status: pelanggan.status || 'aktif',
            tipePinjaman: pelanggan.tipePinjaman || 'dibawah_3jt',
            nikType: nikType,
            lastUpdated: admin.database.ServerValue.TIMESTAMP
        };
        
        await db.ref(`nik_registry/${nik}`).set(entry);
        console.log(`✅ NIK ${nik} registered (${nikType}) - ${nama}`);
        
    } catch (error) {
        console.error(`❌ Error registering NIK ${nik}:`, error.message);
    }
}

// =========================================================================
// HELPER: Update Status NIK di Registry
// =========================================================================
async function updateNikStatus(nik, newStatus) {
    if (!nik || nik.length !== 16) return;
    
    try {
        // Cek apakah NIK ada di registry
        const nikSnap = await db.ref(`nik_registry/${nik}`).once('value');
        if (!nikSnap.exists()) return;
        
        await db.ref(`nik_registry/${nik}`).update({
            status: newStatus,
            lastUpdated: admin.database.ServerValue.TIMESTAMP
        });
        
        console.log(`✅ NIK ${nik} status updated to: ${newStatus}`);
        
    } catch (error) {
        console.error(`❌ Error updating NIK ${nik}:`, error.message);
    }
}

// =========================================================================
// HELPER: Hapus NIK dari Registry
// =========================================================================
async function removeNikFromRegistry(nik) {
    if (!nik || nik.length !== 16) return;
    
    try {
        await db.ref(`nik_registry/${nik}`).remove();
        console.log(`✅ NIK ${nik} removed from registry`);
        
    } catch (error) {
        console.error(`❌ Error removing NIK ${nik}:`, error.message);
    }
}

// =========================================================================
// TRIGGER 1: Saat Pelanggan BARU Ditambahkan
// =========================================================================
exports.onPelangganCreatedRegisterNik = functions.database
    .ref('/pelanggan/{adminUid}/{pelangganId}')
    .onCreate(async (snapshot, context) => {
        const adminUid = context.params.adminUid;
        const pelangganId = context.params.pelangganId;
        const pelanggan = snapshot.val();
        
        if (!pelanggan) return null;
        
        console.log(`📝 [NIK Registry] New pelanggan: ${pelangganId}`);
        
        try {
            // Dapatkan data admin dan cabang
            const adminSnap = await db.ref(`metadata/admins/${adminUid}`).once('value');
            const adminData = adminSnap.val() || {};
            
            let cabangData = {};
            if (adminData.cabang) {
                const cabangSnap = await db.ref(`metadata/cabang/${adminData.cabang}`).once('value');
                cabangData = cabangSnap.val() || {};
            }
            
            // Register NIK utama
            if (pelanggan.nik && pelanggan.nik.length === 16) {
                await registerNikToRegistry(
                    pelanggan.nik, 'nik', adminUid, adminData, pelangganId, pelanggan, cabangData
                );
            }
            
            // Register NIK Suami (untuk pinjaman >= 3jt)
            if (pelanggan.nikSuami && pelanggan.nikSuami.length === 16) {
                await registerNikToRegistry(
                    pelanggan.nikSuami, 'nikSuami', adminUid, adminData, pelangganId, pelanggan, cabangData
                );
            }
            
            // Register NIK Istri
            if (pelanggan.nikIstri && pelanggan.nikIstri.length === 16) {
                await registerNikToRegistry(
                    pelanggan.nikIstri, 'nikIstri', adminUid, adminData, pelangganId, pelanggan, cabangData
                );
            }
            
            return null;
            
        } catch (error) {
            console.error(`❌ [NIK Registry] Error: ${error.message}`);
            return null;
        }
    });

// =========================================================================
// TRIGGER 2: Saat Pembayaran Ditambahkan (Cek Lunas → Update NIK Status)
// =========================================================================
exports.onPembayaranUpdateNikStatus = functions.database
    .ref('/pelanggan/{adminUid}/{pelangganId}/pembayaranList/{index}')
    .onCreate(async (snapshot, context) => {
        const adminUid = context.params.adminUid;
        const pelangganId = context.params.pelangganId;
        
        try {
            // Dapatkan data pelanggan terbaru
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}/${pelangganId}`).once('value');
            const pelanggan = pelangganSnap.val();
            
            if (!pelanggan) return null;
            
            // Cek apakah sudah lunas
            if (isNasabahLunas(pelanggan)) {
                console.log(`🎉 [NIK Registry] Pelanggan ${pelangganId} LUNAS!`);
                
                if (pelanggan.nik) await updateNikStatus(pelanggan.nik, 'lunas');
                if (pelanggan.nikSuami) await updateNikStatus(pelanggan.nikSuami, 'lunas');
                if (pelanggan.nikIstri) await updateNikStatus(pelanggan.nikIstri, 'lunas');
            }
            
            return null;
            
        } catch (error) {
            console.error(`❌ [NIK Registry] Error: ${error.message}`);
            return null;
        }
    });

// =========================================================================
// TRIGGER 3: Saat Sub-Pembayaran Ditambahkan (Cek Lunas)
// =========================================================================
exports.onSubPembayaranUpdateNikStatus = functions.database
    .ref('/pelanggan/{adminUid}/{pelangganId}/pembayaranList/{index}/subPembayaran/{subIndex}')
    .onCreate(async (snapshot, context) => {
        const adminUid = context.params.adminUid;
        const pelangganId = context.params.pelangganId;
        
        try {
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}/${pelangganId}`).once('value');
            const pelanggan = pelangganSnap.val();
            
            if (!pelanggan) return null;
            
            if (isNasabahLunas(pelanggan)) {
                console.log(`🎉 [NIK Registry] Pelanggan ${pelangganId} LUNAS! (sub-payment)`);
                
                if (pelanggan.nik) await updateNikStatus(pelanggan.nik, 'lunas');
                if (pelanggan.nikSuami) await updateNikStatus(pelanggan.nikSuami, 'lunas');
                if (pelanggan.nikIstri) await updateNikStatus(pelanggan.nikIstri, 'lunas');
            }
            
            return null;
            
        } catch (error) {
            console.error(`❌ [NIK Registry] Error: ${error.message}`);
            return null;
        }
    });

// =========================================================================
// TRIGGER 4: Saat Status Pelanggan Berubah ke AKTIF (Pinjaman Baru)
// =========================================================================
exports.onStatusChangeUpdateNik = functions.database
    .ref('/pelanggan/{adminUid}/{pelangganId}/status')
    .onUpdate(async (change, context) => {
        const adminUid = context.params.adminUid;
        const pelangganId = context.params.pelangganId;
        const beforeStatus = (change.before.val() || '').toLowerCase();
        const afterStatus = (change.after.val() || '').toLowerCase();
        
        // Skip jika status tidak berubah
        if (beforeStatus === afterStatus) return null;
        
        console.log(`🔄 [NIK Registry] Status: ${pelangganId} - ${beforeStatus} → ${afterStatus}`);
        
        try {
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}/${pelangganId}`).once('value');
            const pelanggan = pelangganSnap.val();
            
            if (!pelanggan) return null;
            
            // Jika status berubah ke aktif (pinjaman baru setelah lunas)
            if (afterStatus === 'aktif' || afterStatus === 'active') {
                if (pelanggan.nik) await updateNikStatus(pelanggan.nik, 'aktif');
                if (pelanggan.nikSuami) await updateNikStatus(pelanggan.nikSuami, 'aktif');
                if (pelanggan.nikIstri) await updateNikStatus(pelanggan.nikIstri, 'aktif');
            }
            
            // Jika status berubah ke lunas
            if (afterStatus === 'lunas') {
                if (pelanggan.nik) await updateNikStatus(pelanggan.nik, 'lunas');
                if (pelanggan.nikSuami) await updateNikStatus(pelanggan.nikSuami, 'lunas');
                if (pelanggan.nikIstri) await updateNikStatus(pelanggan.nikIstri, 'lunas');
            }
            
            return null;
            
        } catch (error) {
            console.error(`❌ [NIK Registry] Error: ${error.message}`);
            return null;
        }
    });

// =========================================================================
// TRIGGER 5: Saat Pelanggan DIHAPUS
// =========================================================================
exports.onPelangganDeletedRemoveNik = functions.database
    .ref('/pelanggan/{adminUid}/{pelangganId}')
    .onDelete(async (snapshot, context) => {
        const pelangganId = context.params.pelangganId;
        const pelanggan = snapshot.val();
        
        if (!pelanggan) return null;
        
        console.log(`🗑️ [NIK Registry] Pelanggan deleted: ${pelangganId}`);
        
        try {
            if (pelanggan.nik) await removeNikFromRegistry(pelanggan.nik);
            if (pelanggan.nikSuami) await removeNikFromRegistry(pelanggan.nikSuami);
            if (pelanggan.nikIstri) await removeNikFromRegistry(pelanggan.nikIstri);
            
            return null;
            
        } catch (error) {
            console.error(`❌ [NIK Registry] Error: ${error.message}`);
            return null;
        }
    });

// =========================================================================
// HTTP FUNCTION: Migrasi NIK (SEKALI PAKAI)
// =========================================================================
// URL: https://us-central1-koperasikitagodangulu.cloudfunctions.net/migrateNikToRegistry
// =========================================================================
exports.migrateNikToRegistry = functions.https.onRequest(async (req, res) => {
    console.log('🚀 =====================================');
    console.log('🚀 MIGRASI NIK KE NIK_REGISTRY');
    console.log('🚀 =====================================');
    
    let totalMigrated = 0;
    const errors = [];
    const results = [];
    
    try {
        // 1. Ambil semua admin
        console.log('📂 Fetching admin list...');
        const adminsSnap = await db.ref('metadata/admins').once('value');
        const admins = adminsSnap.val() || {};
        
        // 2. Ambil info cabang
        const cabangSnap = await db.ref('metadata/cabang').once('value');
        const cabangData = cabangSnap.val() || {};
        
        console.log(`Found ${Object.keys(admins).length} admins`);
        
        // 3. Loop setiap admin
        for (const [adminUid, adminInfo] of Object.entries(admins)) {
            const role = adminInfo.role || '';
            
            // Skip non-admin
            if (role !== 'admin' && role !== 'pimpinan') continue;
            
            const adminName = adminInfo.name || adminInfo.displayName || adminInfo.email || 'Admin';
            const cabangId = adminInfo.cabang || '';
            const cabangName = cabangData[cabangId]?.name || cabangId;
            
            console.log(`\n👤 Processing: ${adminName} (${cabangName})`);
            
            // Ambil pelanggan admin ini
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
            const pelangganList = pelangganSnap.val() || {};
            
            let adminMigrated = 0;
            
            for (const [pelangganId, pelanggan] of Object.entries(pelangganList)) {
                const tipePinjaman = pelanggan.tipePinjaman || 'dibawah_3jt';
                
                // Tentukan status
                let status = (pelanggan.status || 'aktif').toLowerCase();
                if (isNasabahLunas(pelanggan)) {
                    status = 'lunas';
                }
                
                // Register NIK utama
                if (pelanggan.nik && pelanggan.nik.length === 16) {
                    try {
                        await db.ref(`nik_registry/${pelanggan.nik}`).set({
                            adminUid: adminUid,
                            adminName: adminName,
                            pelangganId: pelangganId,
                            nama: pelanggan.namaPanggilan || pelanggan.namaKtp || '',
                            cabangId: cabangId,
                            cabangName: cabangName,
                            status: status,
                            tipePinjaman: tipePinjaman,
                            nikType: 'nik',
                            lastUpdated: Date.now()
                        });
                        adminMigrated++;
                        totalMigrated++;
                    } catch (e) {
                        errors.push(`NIK ${pelanggan.nik}: ${e.message}`);
                    }
                }
                
                // Register NIK Suami
                if (pelanggan.nikSuami && pelanggan.nikSuami.length === 16) {
                    try {
                        await db.ref(`nik_registry/${pelanggan.nikSuami}`).set({
                            adminUid: adminUid,
                            adminName: adminName,
                            pelangganId: pelangganId,
                            nama: pelanggan.namaPanggilanSuami || pelanggan.namaKtpSuami || '',
                            cabangId: cabangId,
                            cabangName: cabangName,
                            status: status,
                            tipePinjaman: tipePinjaman,
                            nikType: 'nikSuami',
                            lastUpdated: Date.now()
                        });
                        adminMigrated++;
                        totalMigrated++;
                    } catch (e) {
                        errors.push(`NIK Suami ${pelanggan.nikSuami}: ${e.message}`);
                    }
                }
                
                // Register NIK Istri
                if (pelanggan.nikIstri && pelanggan.nikIstri.length === 16) {
                    try {
                        await db.ref(`nik_registry/${pelanggan.nikIstri}`).set({
                            adminUid: adminUid,
                            adminName: adminName,
                            pelangganId: pelangganId,
                            nama: pelanggan.namaPanggilanIstri || pelanggan.namaKtpIstri || '',
                            cabangId: cabangId,
                            cabangName: cabangName,
                            status: status,
                            tipePinjaman: tipePinjaman,
                            nikType: 'nikIstri',
                            lastUpdated: Date.now()
                        });
                        adminMigrated++;
                        totalMigrated++;
                    } catch (e) {
                        errors.push(`NIK Istri ${pelanggan.nikIstri}: ${e.message}`);
                    }
                }
            }
            
            results.push({
                adminUid: adminUid,
                adminName: adminName,
                cabangName: cabangName,
                migrated: adminMigrated
            });
            
            console.log(`   ✅ ${adminMigrated} NIK migrated`);
        }
        
        console.log('\n========================================');
        console.log(`✅ MIGRATION COMPLETED`);
        console.log(`   Total NIK migrated: ${totalMigrated}`);
        console.log(`   Errors: ${errors.length}`);
        console.log('========================================');
        
        res.json({
            success: true,
            message: 'Migrasi NIK selesai! Jalankan SEKALI saja.',
            totalMigrated: totalMigrated,
            errors: errors.length > 0 ? errors : undefined,
            adminResults: results
        });
        
    } catch (error) {
        console.error('❌ Migration error:', error);
        res.status(500).json({
            success: false,
            error: error.message
        });
    }
});

// =========================================================================
// CALLABLE FUNCTION: Search NIK Global (untuk Android app) - OPTIONAL
// =========================================================================
// Jika ingin search via Cloud Function (lebih aman, tapi ada latency)
// Jika tidak, Android bisa query langsung ke nik_registry
// =========================================================================
const { onCall } = require("firebase-functions/v2/https");

exports.searchNikGlobal = onCall(async (request) => {
    const uid = request.auth?.uid;
    
    if (!uid) {
        throw new Error("Not authenticated");
    }
    
    const nik = request.data.nik;
    
    if (!nik || nik.length !== 16) {
        return {
            found: false,
            status: 'NOT_FOUND',
            message: 'NIK tidak valid. Pastikan 16 digit.'
        };
    }
    
    try {
        const nikSnap = await db.ref(`nik_registry/${nik}`).once('value');
        
        if (!nikSnap.exists()) {
            return {
                found: false,
                status: 'NOT_FOUND',
                message: 'Data calon nasabah ini belum pernah menjadi anggota di KSP Si Godang Ulu Jaya. Silakan lanjut untuk mendaftarkan nasabah baru.'
            };
        }
        
        const entry = nikSnap.val();
        const isCurrentAdmin = entry.adminUid === uid;
        const statusLower = (entry.status || '').toLowerCase();
        
        let searchStatus, message;
        
        if (statusLower === 'aktif' || statusLower === 'active' || 
            statusLower === 'menunggu approval' || statusLower === 'menunggu_approval') {
            
            if (isCurrentAdmin) {
                searchStatus = 'ACTIVE_SELF';
                message = `NIK ini masih memiliki pinjaman aktif pada Anda.`;
            } else {
                searchStatus = 'ACTIVE_OTHER_ADMIN';
                message = `NIK ini masih memiliki pinjaman aktif pada ${entry.adminName} (Cabang ${entry.cabangName}). Nasabah tidak dapat mengajukan pinjaman baru.`;
            }
            
        } else if (statusLower === 'lunas') {
            if (isCurrentAdmin) {
                searchStatus = 'LUNAS_SELF';
                message = 'Nasabah ini sudah LUNAS di data Anda. Silakan lanjut untuk pinjaman baru.';
            } else {
                searchStatus = 'LUNAS_OTHER';
                message = `Nasabah ini sudah LUNAS di ${entry.adminName} (Cabang ${entry.cabangName}). Anda dapat mendaftarkan ulang.`;
            }
        } else {
            return {
                found: false,
                status: 'NOT_FOUND',
                message: 'Silakan lanjut untuk mendaftarkan nasabah baru.'
            };
        }
        
        return {
            found: true,
            status: searchStatus,
            adminName: entry.adminName,
            adminUid: entry.adminUid,
            cabangName: entry.cabangName,
            pelangganId: entry.pelangganId,
            nama: entry.nama,
            nikType: entry.nikType,
            message: message
        };
        
    } catch (error) {
        console.error('Error searching NIK:', error);
        throw new Error(`Gagal mencari NIK: ${error.message}`);
    }
});