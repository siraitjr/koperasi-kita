// =========================================================================
// SUMMARY REPAIR - VERSI v5 (PRODUCTION SAFE)
// =========================================================================
// 
// PERBAIKAN v5:
// ✅ TETAP: Semua fungsi repair dari v4
// ✅ TAMBAH: updateCabangAndGlobalSummaries dengan SEMUA FIELD lengkap
// ✅ TAMBAH: updateSingleCabangSummary dengan SEMUA FIELD lengkap
// ✅ TAMBAH: targetHariIni, pembayaranHariIni, nasabahMenungguPencairan
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');
const db = admin.database();

const { fullRecalculateAdminSummary } = require('./summaryHelpers');

// =========================================================================
// FUNGSI 1: SMART HEALTH CHECK (HEMAT - SHALLOW READ)
// =========================================================================
exports.smartHealthCheck = functions.pubsub
    .schedule('0 3 * * 0')  // Hanya Minggu jam 3 pagi (1x seminggu)
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log(`🏥 [AUTO] Smart health check started (shallow mode)`);
        
        try {
            const adminsWithIssues = [];
            const adminsSnap = await db.ref('metadata/admins').once('value');
            
            for (const [adminUid, adminData] of Object.entries(adminsSnap.val() || {})) {
                if (adminData.role !== 'admin') continue;
                
                const pelangganRef = db.ref(`pelanggan/${adminUid}`);
                const pelangganSnap = await pelangganRef.once('value');
                const actualCount = pelangganSnap.numChildren();
                
                const summarySnap = await db.ref(`summary/perAdmin/${adminUid}/totalNasabah`).once('value');
                const summaryCount = summarySnap.val() || 0;
                
                const diff = Math.abs(actualCount - summaryCount);
                
                // Toleransi 2 untuk menghindari false positive dari race condition
                if (diff > 2) {
                    console.warn(`⚠️ Mismatch: ${adminUid} - Summary: ${summaryCount}, Actual: ${actualCount}`);
                    adminsWithIssues.push({
                        adminUid,
                        summaryCount,
                        actualCount,
                        diff
                    });
                }
            }
            
            if (adminsWithIssues.length > 0) {
                console.log(`🔧 Found ${adminsWithIssues.length} admins with issues, repairing...`);
                
                for (const issue of adminsWithIssues) {
                    try {
                        await fullRecalculateAdminSummary(issue.adminUid);
                        console.log(`✅ Repaired: ${issue.adminUid}`);
                    } catch (e) {
                        console.error(`❌ Failed to repair ${issue.adminUid}: ${e.message}`);
                    }
                    
                    await new Promise(r => setTimeout(r, 500));
                }
                
                // v5: Update dengan semua field
                await updateCabangAndGlobalSummaries();
            } else {
                console.log(`✅ All summaries accurate, no repair needed`);
            }
            
            return null;
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            throw error;
        }
    });


// =========================================================================
// FUNGSI 2: ONE-TIME REPAIR (HTTP - Panggil sekali saja)
// =========================================================================
// Tambahkan ?force=true untuk memaksa recalc semua admin (termasuk yang sudah akurat)
// Contoh: https://...cloudfunctions.net/repairAllSummaries?force=true
// =========================================================================
exports.repairAllSummaries = functions.https.onRequest(async (req, res) => {
    // v5: Support force parameter
    const forceRecalc = req.query.force === 'true';
    
    console.log(`🔧 One-time repair started (v5) - Force: ${forceRecalc}`);
    
    const startTime = Date.now();
    
    try {
        const adminsSnap = await db.ref('metadata/admins').once('value');
        const results = {
            total: 0,
            repaired: 0,
            skipped: 0,
            errors: 0,
            details: []
        };
        
        for (const [adminUid, adminData] of Object.entries(adminsSnap.val() || {})) {
            if (adminData.role !== 'admin') continue;
            
            results.total++;
            
            try {
                const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
                const actualCount = pelangganSnap.numChildren();
                
                const summarySnap = await db.ref(`summary/perAdmin/${adminUid}/totalNasabah`).once('value');
                const summaryCount = summarySnap.val() || 0;
                
                // v5: Jika force=true, SELALU recalc (untuk menambahkan field baru)
                if (!forceRecalc && Math.abs(actualCount - summaryCount) <= 1) {
                    results.skipped++;
                    results.details.push({
                        adminUid,
                        adminName: adminData.name || adminData.email,
                        status: 'skipped',
                        reason: 'Already accurate (use ?force=true to recalc anyway)',
                        count: actualCount
                    });
                    continue;
                }
                
                console.log(`🔄 Repairing ${adminUid}: ${summaryCount} → recalc...`);
                
                await fullRecalculateAdminSummary(adminUid);
                
                results.repaired++;
                results.details.push({
                    adminUid,
                    adminName: adminData.name || adminData.email,
                    status: forceRecalc ? 'force-recalced' : 'repaired',
                    before: summaryCount,
                    after: actualCount
                });
                
                await new Promise(r => setTimeout(r, 300));
                
            } catch (e) {
                results.errors++;
                results.details.push({
                    adminUid,
                    status: 'error',
                    error: e.message
                });
            }
        }
        
        // v5: SELALU update cabang dan global untuk sinkronisasi semua field
        console.log(`🔄 Updating cabang and global summaries...`);
        await updateCabangAndGlobalSummaries();
        
        const duration = ((Date.now() - startTime) / 1000).toFixed(2);
        
        console.log(`✅ Repair completed in ${duration}s`);
        console.log(`   Total: ${results.total}, Repaired: ${results.repaired}, Skipped: ${results.skipped}`);
        
        res.json({
            success: true,
            duration: `${duration}s`,
            forceMode: forceRecalc,
            summary: {
                total: results.total,
                repaired: results.repaired,
                skipped: results.skipped,
                errors: results.errors
            },
            details: results.details
        });
        
    } catch (error) {
        console.error(`❌ Error: ${error.message}`);
        res.status(500).json({ 
            success: false, 
            error: error.message 
        });
    }
});


// =========================================================================
// FUNGSI 3: REPAIR SINGLE ADMIN (HTTP)
// =========================================================================
exports.repairAdminSummary = functions.https.onRequest(async (req, res) => {
    const adminUid = req.query.adminUid;
    
    if (!adminUid) {
        res.status(400).json({ success: false, error: 'Missing adminUid' });
        return;
    }
    
    try {
        const adminSnap = await db.ref(`metadata/admins/${adminUid}`).once('value');
        if (!adminSnap.exists()) {
            res.status(404).json({ success: false, error: 'Admin not found' });
            return;
        }
        
        const beforeSnap = await db.ref(`summary/perAdmin/${adminUid}`).once('value');
        const before = beforeSnap.val() || {};
        
        await fullRecalculateAdminSummary(adminUid);
        
        const afterSnap = await db.ref(`summary/perAdmin/${adminUid}`).once('value');
        const after = afterSnap.val() || {};
        
        const cabangId = adminSnap.val().cabang;
        if (cabangId) {
            await updateSingleCabangSummary(cabangId);
        }
        
        // v5: Update global juga
        await updateGlobalSummaryFromCabang();
        
        res.json({
            success: true,
            adminUid,
            before: {
                totalNasabah: before.totalNasabah || 0,
                nasabahAktif: before.nasabahAktif || 0,
                targetHariIni: before.targetHariIni || 0
            },
            after: {
                totalNasabah: after.totalNasabah || 0,
                nasabahAktif: after.nasabahAktif || 0,
                targetHariIni: after.targetHariIni || 0
            }
        });
        
    } catch (error) {
        res.status(500).json({ success: false, error: error.message });
    }
});


// =========================================================================
// v5: HELPER UPDATE CABANG DAN GLOBAL - SEMUA FIELD LENGKAP
// =========================================================================
async function updateCabangAndGlobalSummaries() {
    console.log(`🔄 Updating cabang and global summaries (v5 - complete fields)...`);
    
    const cabangSnap = await db.ref('metadata/cabang').once('value');
    
    // Global aggregates
    let globalNasabah = 0;
    let globalAktif = 0;
    let globalLunas = 0;
    let globalPinjaman = 0;
    let globalPiutang = 0;
    let globalTargetHariIni = 0;
    let globalPembayaranHariIni = 0;
    let globalNasabahBaruHariIni = 0;
    let globalNasabahLunasHariIni = 0;
    let globalMenungguPencairan = 0;
    
    for (const [cabangId, cabangData] of Object.entries(cabangSnap.val() || {})) {
        const adminList = cabangData.adminList || [];
        
        // Cabang aggregates
        let cabangNasabah = 0;
        let cabangAktif = 0;
        let cabangLunas = 0;
        let cabangMenunggu = 0;
        let cabangMenungguPencairan = 0;
        let cabangPinjaman = 0;
        let cabangPiutang = 0;
        let cabangTargetHariIni = 0;
        let cabangPembayaranHariIni = 0;
        let cabangNasabahBaruHariIni = 0;
        let cabangNasabahLunasHariIni = 0;
        
        for (const adminUid of adminList) {
            const summarySnap = await db.ref(`summary/perAdmin/${adminUid}`).once('value');
            const data = summarySnap.val();
            
            if (data) {
                cabangNasabah += data.totalNasabah || 0;
                cabangAktif += data.nasabahAktif || 0;
                cabangLunas += data.nasabahLunas || 0;
                cabangMenunggu += data.nasabahMenunggu || 0;
                cabangMenungguPencairan += data.nasabahMenungguPencairan || 0;   // v5
                cabangPinjaman += data.totalPinjamanAktif || 0;
                cabangPiutang += data.totalPiutang || 0;
                cabangTargetHariIni += data.targetHariIni || 0;                  // v5
                cabangPembayaranHariIni += data.pembayaranHariIni || 0;          // v5
                cabangNasabahBaruHariIni += data.nasabahBaruHariIni || 0;        // v5
                cabangNasabahLunasHariIni += data.nasabahLunasHariIni || 0;      // v5
            }
        }
        
        // v5: Update cabang dengan SEMUA FIELD
        await db.ref(`summary/perCabang/${cabangId}`).update({
            totalNasabah: cabangNasabah,
            nasabahAktif: cabangAktif,
            nasabahLunas: cabangLunas,
            nasabahMenunggu: cabangMenunggu,
            nasabahMenungguPencairan: cabangMenungguPencairan,  // v5
            totalPinjamanAktif: cabangPinjaman,
            totalPiutang: cabangPiutang,
            targetHariIni: cabangTargetHariIni,                 // v5
            pembayaranHariIni: cabangPembayaranHariIni,         // v5
            nasabahBaruHariIni: cabangNasabahBaruHariIni,       // v5
            nasabahLunasHariIni: cabangNasabahLunasHariIni,     // v5
            adminCount: adminList.length,
            lastUpdated: admin.database.ServerValue.TIMESTAMP
        });
        
        console.log(`   ✓ Cabang ${cabangId}: ${cabangNasabah} nasabah, target: ${cabangTargetHariIni}`);
        
        // Aggregate ke global
        globalNasabah += cabangNasabah;
        globalAktif += cabangAktif;
        globalLunas += cabangLunas;
        globalPinjaman += cabangPinjaman;
        globalPiutang += cabangPiutang;
        globalTargetHariIni += cabangTargetHariIni;
        globalPembayaranHariIni += cabangPembayaranHariIni;
        globalNasabahBaruHariIni += cabangNasabahBaruHariIni;
        globalNasabahLunasHariIni += cabangNasabahLunasHariIni;
        globalMenungguPencairan += cabangMenungguPencairan;
    }
    
    // v5: Update global dengan SEMUA FIELD
    await db.ref('summary/global').update({
        totalNasabah: globalNasabah,
        nasabahAktif: globalAktif,                              // v5
        totalPinjamanAktif: globalPinjaman,
        totalTunggakan: globalPiutang,
        targetHariIni: globalTargetHariIni,                     // v5
        pembayaranHariIni: globalPembayaranHariIni,             // v5
        nasabahBaruHariIni: globalNasabahBaruHariIni,           // v5
        nasabahLunasHariIni: globalNasabahLunasHariIni,         // v5
        nasabahMenungguPencairan: globalMenungguPencairan,      // v5
        totalCabang: Object.keys(cabangSnap.val() || {}).length,
        lastUpdated: admin.database.ServerValue.TIMESTAMP
    });
    
    console.log(`✅ Cabang and global summaries updated`);
    console.log(`   Global: ${globalNasabah} nasabah, ${globalAktif} aktif, target: ${globalTargetHariIni}`);
}


// =========================================================================
// v5: HELPER UPDATE SINGLE CABANG - SEMUA FIELD LENGKAP
// =========================================================================
async function updateSingleCabangSummary(cabangId) {
    const cabangSnap = await db.ref(`metadata/cabang/${cabangId}`).once('value');
    const adminList = cabangSnap.val()?.adminList || [];
    
    let totalNasabah = 0;
    let nasabahAktif = 0;
    let nasabahLunas = 0;
    let nasabahMenunggu = 0;
    let nasabahMenungguPencairan = 0;
    let totalPinjaman = 0;
    let totalPiutang = 0;
    let targetHariIni = 0;
    let pembayaranHariIni = 0;
    let nasabahBaruHariIni = 0;
    let nasabahLunasHariIni = 0;
    
    for (const adminUid of adminList) {
        const summarySnap = await db.ref(`summary/perAdmin/${adminUid}`).once('value');
        const data = summarySnap.val();
        
        if (data) {
            totalNasabah += data.totalNasabah || 0;
            nasabahAktif += data.nasabahAktif || 0;
            nasabahLunas += data.nasabahLunas || 0;
            nasabahMenunggu += data.nasabahMenunggu || 0;
            nasabahMenungguPencairan += data.nasabahMenungguPencairan || 0;
            totalPinjaman += data.totalPinjamanAktif || 0;
            totalPiutang += data.totalPiutang || 0;
            targetHariIni += data.targetHariIni || 0;
            pembayaranHariIni += data.pembayaranHariIni || 0;
            nasabahBaruHariIni += data.nasabahBaruHariIni || 0;
            nasabahLunasHariIni += data.nasabahLunasHariIni || 0;
        }
    }
    
    // v5: Update dengan SEMUA FIELD
    await db.ref(`summary/perCabang/${cabangId}`).update({
        totalNasabah,
        nasabahAktif,
        nasabahLunas,
        nasabahMenunggu,
        nasabahMenungguPencairan,
        totalPinjamanAktif: totalPinjaman,
        totalPiutang,
        targetHariIni,
        pembayaranHariIni,
        nasabahBaruHariIni,
        nasabahLunasHariIni,
        lastUpdated: admin.database.ServerValue.TIMESTAMP
    });
    
    console.log(`✅ Cabang ${cabangId} updated: ${totalNasabah} nasabah, target: ${targetHariIni}`);
}


// =========================================================================
// v5: HELPER UPDATE GLOBAL FROM CABANG
// =========================================================================
async function updateGlobalSummaryFromCabang() {
    const cabangSnap = await db.ref('summary/perCabang').once('value');
    
    let globalNasabah = 0;
    let globalAktif = 0;
    let globalPinjaman = 0;
    let globalPiutang = 0;
    let globalTargetHariIni = 0;
    let globalPembayaranHariIni = 0;
    let globalNasabahBaruHariIni = 0;
    let globalNasabahLunasHariIni = 0;
    let globalMenungguPencairan = 0;
    
    cabangSnap.forEach(child => {
        const data = child.val();
        if (data) {
            globalNasabah += data.totalNasabah || 0;
            globalAktif += data.nasabahAktif || 0;
            globalPinjaman += data.totalPinjamanAktif || 0;
            globalPiutang += data.totalPiutang || 0;
            globalTargetHariIni += data.targetHariIni || 0;
            globalPembayaranHariIni += data.pembayaranHariIni || 0;
            globalNasabahBaruHariIni += data.nasabahBaruHariIni || 0;
            globalNasabahLunasHariIni += data.nasabahLunasHariIni || 0;
            globalMenungguPencairan += data.nasabahMenungguPencairan || 0;
        }
    });
    
    await db.ref('summary/global').update({
        totalNasabah: globalNasabah,
        nasabahAktif: globalAktif,
        totalPinjamanAktif: globalPinjaman,
        totalTunggakan: globalPiutang,
        targetHariIni: globalTargetHariIni,
        pembayaranHariIni: globalPembayaranHariIni,
        nasabahBaruHariIni: globalNasabahBaruHariIni,
        nasabahLunasHariIni: globalNasabahLunasHariIni,
        nasabahMenungguPencairan: globalMenungguPencairan,
        totalCabang: cabangSnap.numChildren(),
        lastUpdated: admin.database.ServerValue.TIMESTAMP
    });
    
    console.log(`✅ Global summary updated from cabang data`);
}