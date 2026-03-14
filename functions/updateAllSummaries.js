// functions/updateAllSummaries.js
// =========================================================================
// MANUAL UPDATE ALL SUMMARIES - VERSI v5 (PRODUCTION SAFE)
// =========================================================================
// 
// PERBAIKAN v5:
// ✅ TETAP: Semua logika sebelumnya
// ✅ TAMBAH: nasabahMenungguPencairan di cabang aggregation
// ✅ TAMBAH: targetHariIni, nasabahAktif, nasabahMenungguPencairan di global
// =========================================================================

const { onCall } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const { fullRecalculateAdminSummary, getTodayIndonesia, isHoliday, isOverThreeMonths, calculateTotalDibayar, aggregateCabangSummary, aggregateGlobalSummary } = require('./summaryHelpers');
const functions = require('firebase-functions');

const db = admin.database();

// =========================================================================
// FUNGSI 1: UPDATE ALL SUMMARIES (Full Recalculation)
// =========================================================================
exports.updateAllSummaries = onCall(async (request) => {
    const uid = request.auth?.uid;
    
    if (!uid) {
        throw new Error("Not authenticated");
    }
    
    // Cek role pengawas atau pimpinan
    const isPengawas = await db.ref(`metadata/roles/pengawas/${uid}`)
        .once("value")
        .then((s) => !!s.val())
        .catch(() => false);
    
    const isPimpinan = await db.ref(`metadata/roles/pimpinan/${uid}`)
        .once("value")
        .then((s) => !!s.val())
        .catch(() => false);
    
    if (!isPengawas && !isPimpinan) {
        throw new Error("Only pengawas or pimpinan can trigger this");
    }
    
    console.log(`ðŸ”„ Manual update triggered by: ${uid}`);
    
    try {
        // Update semua admin
        const adminsSnap = await db.ref('metadata/admins').once('value');
        const adminUpdates = [];
        
        for (const [adminUid, adminData] of Object.entries(adminsSnap.val() || {})) {
            if (adminData.role === 'admin') {
                adminUpdates.push(fullRecalculateAdminSummary(adminUid));
            }
        }
        
        // Jalankan semua update admin secara parallel
        await Promise.all(adminUpdates);
        console.log(`âœ… Updated ${adminUpdates.length} admin summaries`);
        
        // v5: Update cabang summary dengan SEMUA field lengkap
        const cabangSnap = await db.ref('metadata/cabang').once('value');
        
        for (const [cabangId, cabangData] of Object.entries(cabangSnap.val() || {})) {
            const adminList = cabangData.adminList || [];
            
            let totalNasabah = 0;
            let nasabahAktif = 0;
            let nasabahLunas = 0;
            let nasabahMenunggu = 0;
            let nasabahMenungguPencairan = 0;  // v5: DITAMBAHKAN
            let nasabahBaruHariIni = 0;
            let nasabahLunasHariIni = 0;
            let targetHariIni = 0;
            let totalPinjamanAktif = 0;
            let totalPiutang = 0;
            let pembayaranHariIni = 0;
            
            for (const adminUid of adminList) {
                const adminSummary = await db.ref(`summary/perAdmin/${adminUid}`).once('value');
                const data = adminSummary.val();
                if (data) {
                    totalNasabah += data.totalNasabah || 0;
                    nasabahAktif += data.nasabahAktif || 0;
                    nasabahLunas += data.nasabahLunas || 0;
                    nasabahMenunggu += data.nasabahMenunggu || 0;
                    nasabahMenungguPencairan += data.nasabahMenungguPencairan || 0;  // v5
                    nasabahBaruHariIni += data.nasabahBaruHariIni || 0;
                    nasabahLunasHariIni += data.nasabahLunasHariIni || 0;
                    targetHariIni += data.targetHariIni || 0;
                    totalPinjamanAktif += data.totalPinjamanAktif || 0;
                    totalPiutang += data.totalPiutang || 0;
                    pembayaranHariIni += data.pembayaranHariIni || 0;
                }
            }
            
            await db.ref(`summary/perCabang/${cabangId}`).update({
                totalNasabah,
                nasabahAktif,
                nasabahLunas,
                nasabahMenunggu,
                nasabahMenungguPencairan,  // v5: DITAMBAHKAN
                nasabahBaruHariIni,
                nasabahLunasHariIni,
                targetHariIni,
                totalPinjamanAktif,
                totalPiutang,
                pembayaranHariIni,
                adminCount: adminList.length,
                lastUpdated: admin.database.ServerValue.TIMESTAMP
            });
            
            console.log(`✅ Updated cabang ${cabangId}: target=${targetHariIni}, baru=${nasabahBaruHariIni}`);
        }
        
        // v5: Update global summary dengan SEMUA field
        const allCabang = await db.ref('summary/perCabang').once('value');
        
        let globalNasabah = 0;
        let globalAktif = 0;              // v5: DITAMBAHKAN
        let globalPinjamanAktif = 0;
        let globalTunggakan = 0;
        let globalTargetHariIni = 0;      // v5: DITAMBAHKAN
        let globalPembayaranHariIni = 0;
        let globalNasabahBaruHariIni = 0;
        let globalNasabahLunasHariIni = 0;
        let globalMenungguPencairan = 0;  // v5: DITAMBAHKAN
        let totalCabang = 0;
        
        allCabang.forEach(child => {
            const d = child.val();
            if (d) {
                totalCabang++;
                globalNasabah += d.totalNasabah || 0;
                globalAktif += d.nasabahAktif || 0;                        // v5
                globalPinjamanAktif += d.totalPinjamanAktif || 0;
                globalTunggakan += d.totalPiutang || 0;
                globalTargetHariIni += d.targetHariIni || 0;               // v5
                globalPembayaranHariIni += d.pembayaranHariIni || 0;
                globalNasabahBaruHariIni += d.nasabahBaruHariIni || 0;
                globalNasabahLunasHariIni += d.nasabahLunasHariIni || 0;
                globalMenungguPencairan += d.nasabahMenungguPencairan || 0; // v5
            }
        });
        
        await db.ref('summary/global').update({
            totalNasabah: globalNasabah,
            nasabahAktif: globalAktif,                        // v5: DITAMBAHKAN
            totalPinjamanAktif: globalPinjamanAktif,
            totalTunggakan: globalTunggakan,
            targetHariIni: globalTargetHariIni,               // v5: DITAMBAHKAN
            pembayaranHariIni: globalPembayaranHariIni,
            nasabahBaruHariIni: globalNasabahBaruHariIni,
            nasabahLunasHariIni: globalNasabahLunasHariIni,
            nasabahMenungguPencairan: globalMenungguPencairan, // v5: DITAMBAHKAN
            totalCabang: totalCabang,
            lastUpdated: admin.database.ServerValue.TIMESTAMP,
            lastManualUpdate: admin.database.ServerValue.TIMESTAMP
        });
        
        console.log(`✅ Updated global summary: target=${globalTargetHariIni}`);
        
        return { 
            success: true, 
            message: 'All summaries updated',
            stats: {
                admins: adminUpdates.length,
                cabang: Object.keys(cabangSnap.val() || {}).length
            }
        };
        
    } catch (error) {
        console.error(`âŒ Error: ${error.message}`);
        throw new Error(`Failed to update summaries: ${error.message}`);
    }
});

// =========================================================================
// âœ… FUNGSI 2: TRIGGER TARGET RECALC (Manual, tanpa perlu tunggu jam 05:00)
// =========================================================================
// Fungsi ini bisa dipanggil oleh Pimpinan untuk trigger recalculation target
// Berguna setelah deploy functions baru
// =========================================================================
exports.triggerTargetRecalc = onCall(async (request) => {
    const uid = request.auth?.uid;
    
    if (!uid) {
        throw new Error("Not authenticated");
    }
    
    // Cek role pimpinan atau pengawas
    const isPimpinan = await db.ref(`metadata/roles/pimpinan/${uid}`)
        .once("value")
        .then((s) => !!s.val())
        .catch(() => false);
    
    const isPengawas = await db.ref(`metadata/roles/pengawas/${uid}`)
        .once("value")
        .then((s) => !!s.val())
        .catch(() => false);
    
    if (!isPimpinan && !isPengawas) {
        throw new Error("Only pimpinan or pengawas can trigger this");
    }
    
    console.log(`ðŸŽ¯ Manual target recalc triggered by: ${uid}`);
    
    const today = getTodayIndonesia();
    const hariLibur = isHoliday(today);
    
    console.log(`ðŸ“… Today: ${today}, Holiday: ${hariLibur}`);
    
    if (hariLibur) {
        console.log(`ðŸ–ï¸ Today is holiday, setting all targets to 0`);
        // Set semua target ke 0
        const updates = {};
        
        const adminsSnap = await db.ref('metadata/admins').once('value');
        adminsSnap.forEach(child => {
            if (child.val()?.role === 'admin') {
                updates[`summary/perAdmin/${child.key}/targetHariIni`] = 0;
            }
        });
        
        const cabangSnap = await db.ref('metadata/cabang').once('value');
        cabangSnap.forEach(child => {
            updates[`summary/perCabang/${child.key}/targetHariIni`] = 0;
        });
        
        await db.ref().update(updates);
        
        return { 
            success: true, 
            message: 'Today is holiday, all targets set to 0',
            today: today,
            isHoliday: true
        };
    }
    
    try {
        // Recalculate target untuk setiap admin
        const adminsSnap = await db.ref('metadata/admins').once('value');
        const cabangTargets = {};
        let globalTarget = 0;
        let totalAdmins = 0;
        
        for (const [adminUid, adminData] of Object.entries(adminsSnap.val() || {})) {
            if (adminData.role !== 'admin') continue;
            
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
            let adminTarget = 0;
            
            pelangganSnap.forEach(child => {
                const p = child.val();
                if (!p) return;
                
                // ===== FILTER: konsisten dengan fullRecalculateAdminSummary =====

                // 1. Hanya status Aktif/Active atau Lunas
                const status = (p.status || '').toLowerCase();
                const isStatusAktif = status === 'aktif' || status === 'active';
                const isStatusLunas = status === 'lunas';
                if (!isStatusAktif && !isStatusLunas) return;

                // 2. Exclude jika simpanan sudah dicairkan (sepenuhnya selesai)
                const statusPencairanSimpanan = (p.statusPencairanSimpanan || '').trim();
                if (statusPencairanSimpanan === 'Dicairkan') return;

                // 3. Exclude nasabah > 3 bulan
                const tglAcuan = (p.tanggalPencairan || '').trim()
                    || (p.tanggalPengajuan || '').trim()
                    || (p.tanggalDaftar || '').trim();
                if (isOverThreeMonths(tglAcuan)) return;

                // 4. Exclude nasabah cair hari ini (hanya untuk yang masih aktif, belum lunas)
                const totalDibayar = calculateTotalDibayar(p);
                const totalPelunasan = p.totalPelunasan || 0;
                const isSudahLunas = totalPelunasan > 0 && totalDibayar >= totalPelunasan;
                if (!isSudahLunas) {
                    const tglCair = (p.tanggalPencairan || '').trim();
                    if (tglCair === today) return;
                }
                
                // ===== HITUNG TARGET: Flat 3% dari besarPinjaman =====
                adminTarget += Math.floor((p.besarPinjaman || 0) * 3 / 100);
            });
            
            // Update admin summary
            await db.ref(`summary/perAdmin/${adminUid}/targetHariIni`).set(adminTarget);
            
            // Accumulate ke cabang
            const cabangId = adminData.cabang;
            if (cabangId) {
                cabangTargets[cabangId] = (cabangTargets[cabangId] || 0) + adminTarget;
            }
            
            globalTarget += adminTarget;
            totalAdmins++;
            
            console.log(`   Admin ${adminUid}: target ${adminTarget}`);
        }
        
        // Update cabang summaries
        for (const [cabangId, target] of Object.entries(cabangTargets)) {
            await db.ref(`summary/perCabang/${cabangId}/targetHariIni`).set(target);
            console.log(`   Cabang ${cabangId}: target ${target}`);
        }
        
        console.log(`âœ… Target recalc done. Global target: ${globalTarget}`);
        
        return { 
            success: true, 
            message: 'Target recalculation completed',
            today: today,
            isHoliday: false,
            stats: {
                totalAdmins: totalAdmins,
                globalTarget: globalTarget,
                cabangTargets: cabangTargets
            }
        };
        
    } catch (error) {
        console.error(`âŒ Error: ${error.message}`);
        throw new Error(`Failed to recalculate targets: ${error.message}`);
    }
});
// =========================================================================
// FUNGSI 3: HTTP ENDPOINT - RECALCULATE NOW (panggil dari browser)
// =========================================================================
// Panggil via browser:
// https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net/recalculateNow
// =========================================================================
exports.recalculateNow = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        res.set('Access-Control-Allow-Origin', '*');
        
        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }
        
        console.log('🔄 [HTTP] Full recalculation triggered from browser');
        
        try {
            const results = { admins: [], cabang: [], global: null };
            
            // 1. Recalculate SEMUA admin summary
            const adminsSnap = await db.ref('metadata/admins').once('value');
            const adminEntries = Object.entries(adminsSnap.val() || {});
            
            for (const [adminUid, adminData] of adminEntries) {
                if (adminData.role !== 'admin') continue;
                
                try {
                    const summary = await fullRecalculateAdminSummary(adminUid);
                    results.admins.push({
                        uid: adminUid,
                        name: adminData.name || adminData.email || adminUid,
                        cabang: adminData.cabang || '',
                        nasabahAktif: summary.nasabahAktif,
                        targetHariIni: summary.targetHariIni,
                        pembayaranHariIni: summary.pembayaranHariIni,
                        totalPiutang: summary.totalPiutang
                    });
                    console.log(`   ✅ Admin ${adminData.name || adminUid}: target=${summary.targetHariIni}`);
                } catch (e) {
                    console.error(`   ❌ Admin ${adminUid}: ${e.message}`);
                    results.admins.push({ uid: adminUid, error: e.message });
                }
                
                // Delay untuk mencegah overload
                await new Promise(r => setTimeout(r, 200));
            }
            
            // 2. Aggregate SEMUA cabang
            const cabangSnap = await db.ref('metadata/cabang').once('value');
            for (const [cabangId, cabangData] of Object.entries(cabangSnap.val() || {})) {
                const adminList = cabangData.adminList || [];
                
                let totals = {
                    totalNasabah: 0, nasabahAktif: 0, nasabahLunas: 0,
                    nasabahMenunggu: 0, nasabahMenungguPencairan: 0,
                    nasabahBaruHariIni: 0, nasabahLunasHariIni: 0,
                    targetHariIni: 0, totalPinjamanAktif: 0,
                    totalPiutang: 0, pembayaranHariIni: 0
                };
                
                for (const aUid of adminList) {
                    const adminSummary = await db.ref(`summary/perAdmin/${aUid}`).once('value');
                    const d = adminSummary.val();
                    if (d) {
                        totals.totalNasabah += d.totalNasabah || 0;
                        totals.nasabahAktif += d.nasabahAktif || 0;
                        totals.nasabahLunas += d.nasabahLunas || 0;
                        totals.nasabahMenunggu += d.nasabahMenunggu || 0;
                        totals.nasabahMenungguPencairan += d.nasabahMenungguPencairan || 0;
                        totals.nasabahBaruHariIni += d.nasabahBaruHariIni || 0;
                        totals.nasabahLunasHariIni += d.nasabahLunasHariIni || 0;
                        totals.targetHariIni += d.targetHariIni || 0;
                        totals.totalPinjamanAktif += d.totalPinjamanAktif || 0;
                        totals.totalPiutang += d.totalPiutang || 0;
                        totals.pembayaranHariIni += d.pembayaranHariIni || 0;
                    }
                }
                
                totals.adminCount = adminList.length;
                totals.lastUpdated = admin.database.ServerValue.TIMESTAMP;
                
                await db.ref(`summary/perCabang/${cabangId}`).update(totals);
                results.cabang.push({ id: cabangId, name: cabangData.name, target: totals.targetHariIni });
                console.log(`   ✅ Cabang ${cabangData.name}: target=${totals.targetHariIni}`);
            }
            
            // 3. Aggregate global
            const allCabang = await db.ref('summary/perCabang').once('value');
            let g = {
                totalNasabah: 0, nasabahAktif: 0, totalPinjamanAktif: 0,
                totalTunggakan: 0, targetHariIni: 0, pembayaranHariIni: 0,
                nasabahBaruHariIni: 0, nasabahLunasHariIni: 0,
                nasabahMenungguPencairan: 0, totalCabang: 0
            };
            
            allCabang.forEach(child => {
                const d = child.val();
                if (d) {
                    g.totalCabang++;
                    g.totalNasabah += d.totalNasabah || 0;
                    g.nasabahAktif += d.nasabahAktif || 0;
                    g.totalPinjamanAktif += d.totalPinjamanAktif || 0;
                    g.totalTunggakan += d.totalPiutang || 0;
                    g.targetHariIni += d.targetHariIni || 0;
                    g.pembayaranHariIni += d.pembayaranHariIni || 0;
                    g.nasabahBaruHariIni += d.nasabahBaruHariIni || 0;
                    g.nasabahLunasHariIni += d.nasabahLunasHariIni || 0;
                    g.nasabahMenungguPencairan += d.nasabahMenungguPencairan || 0;
                }
            });
            
            g.lastUpdated = admin.database.ServerValue.TIMESTAMP;
            g.lastManualRecalc = admin.database.ServerValue.TIMESTAMP;
            
            await db.ref('summary/global').update(g);
            results.global = { target: g.targetHariIni, nasabahAktif: g.nasabahAktif };
            
            console.log(`✅ [HTTP] Full recalculation done! Global target: ${g.targetHariIni}`);
            
            res.status(200).json({
                success: true,
                message: 'Full recalculation completed',
                today: getTodayIndonesia(),
                summary: {
                    totalAdmins: results.admins.filter(a => !a.error).length,
                    totalCabang: results.cabang.length,
                    globalTarget: g.targetHariIni,
                    globalNasabahAktif: g.nasabahAktif,
                    globalPembayaran: g.pembayaranHariIni
                },
                details: results
            });
            
        } catch (error) {
            console.error(`❌ [HTTP] Error: ${error.message}`);
            res.status(500).json({ success: false, error: error.message });
        }
    });