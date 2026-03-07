// functions/scheduledFunctions.js
// =========================================================================
// SCHEDULED FUNCTIONS - VERSI v5 (PRODUCTION SAFE)
// =========================================================================
// 
// PERBAIKAN v5 (dari v3):
// ✅ TETAP: Semua logika v3 (skip nasabah lunas, dll)
// ✅ TAMBAH: weeklyFullRecalc aggregate SEMUA field ke perCabang
// ✅ TAMBAH: weeklyFullRecalc aggregate SEMUA field ke global
// ✅ TAMBAH: Field: targetHariIni, nasabahAktif, nasabahMenungguPencairan
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

const { fullRecalculateAdminSummary, isHoliday, getTodayIndonesia, isOverThreeMonths, calculateTotalDibayar } = require('./summaryHelpers');

// =========================================================================
// DAILY RESET (00:00 WIB) - OTOMATIS SETIAP HARI
// =========================================================================
exports.dailySummaryReset = functions.pubsub
    .schedule('0 0 * * *')
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log(`🌙 [AUTO] Daily reset started`);
        
        try {
            const updates = {};
            
            // Reset semua admin
            const adminsSnap = await db.ref('metadata/admins').once('value');
            adminsSnap.forEach(child => {
                if (child.val()?.role === 'admin') {
                    const adminUid = child.key;
                    updates[`summary/perAdmin/${adminUid}/pembayaranHariIni`] = 0;
                    updates[`summary/perAdmin/${adminUid}/nasabahBaruHariIni`] = 0;
                    updates[`summary/perAdmin/${adminUid}/nasabahLunasHariIni`] = 0;
                    updates[`summary/perAdmin/${adminUid}/targetHariIni`] = 0;
                }
            });
            
            // Reset semua cabang
            const cabangSnap = await db.ref('metadata/cabang').once('value');
            cabangSnap.forEach(child => {
                const cabangId = child.key;
                updates[`summary/perCabang/${cabangId}/pembayaranHariIni`] = 0;
                updates[`summary/perCabang/${cabangId}/nasabahBaruHariIni`] = 0;
                updates[`summary/perCabang/${cabangId}/nasabahLunasHariIni`] = 0;
                updates[`summary/perCabang/${cabangId}/targetHariIni`] = 0;
            });
            
            // Reset global
            updates['summary/global/pembayaranHariIni'] = 0;
            updates['summary/global/nasabahBaruHariIni'] = 0;
            updates['summary/global/nasabahLunasHariIni'] = 0;
            updates['summary/global/lastDailyReset'] = admin.database.ServerValue.TIMESTAMP;
            
            await db.ref().update(updates);
            
            console.log(`✅ [AUTO] Daily reset done: ${Object.keys(updates).length} fields`);
            return null;
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            throw error;
        }
    });

// =========================================================================
// ✅ PERBAIKAN v3: DAILY TARGET RECALCULATION (05:00 WIB)
// =========================================================================
// Recalculate targetHariIni untuk semua admin setiap pagi
// PENTING: Tidak menghitung target dari nasabah yang sudah lunas!
// Logika SAMA dengan RingkasanDashboardScreen.kt baris 115-119
// =========================================================================
exports.dailyTargetRecalc = functions.pubsub
    .schedule('0 5 * * *')
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log(`🎯 [AUTO] Daily target recalculation started`);
        
        const today = getTodayIndonesia();
        const hariLibur = isHoliday(today);
        
        console.log(`📅 Today: ${today}, Holiday: ${hariLibur}`);
        
        if (hariLibur) {
            console.log(`🏖️ Today is holiday, setting all targets to 0`);
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
            console.log(`✅ All targets set to 0 (holiday)`);
            return null;
        }
        
        try {
            const adminsSnap = await db.ref('metadata/admins').once('value');
            const cabangTargets = {};
            let globalTarget = 0;
            
            for (const [adminUid, adminData] of Object.entries(adminsSnap.val() || {})) {
                if (adminData.role !== 'admin') continue;
                
                const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
                let adminTarget = 0;
                
                pelangganSnap.forEach(child => {
                    const p = child.val();
                    if (!p) return;
                    
                    // ===== FILTER: SAMA PERSIS DENGAN Android RingkasanDashboardScreen.kt =====
                    
                    // 1. Hanya status Aktif/Active
                    const status = (p.status || '').toLowerCase();
                    if (status !== 'aktif' && status !== 'active') return;
                    
                    // 2. Exclude MENUNGGU_PENCAIRAN
                    const statusKhusus = (p.statusKhusus || '').toUpperCase().replace(/ /g, '_');
                    if (statusKhusus === 'MENUNGGU_PENCAIRAN') return;
                    
                    // 3. Exclude nasabah > 3 bulan (dari tanggal 1 bulan ke-3 ke belakang)
                    const tglAcuan = (p.tanggalPencairan || '').trim()
                        || (p.tanggalPengajuan || '').trim()
                        || (p.tanggalDaftar || '').trim();
                    if (isOverThreeMonths(tglAcuan)) return;
                    
                    // 4. Exclude nasabah cair hari ini (baru mulai besok)
                    const tglCair = (p.tanggalPencairan || '').trim();
                    if (tglCair === today) return;
                    
                    // 5. Belum lunas — hitung totalDibayar
                    const totalDibayar = calculateTotalDibayar(p);
                    const totalPelunasan = p.totalPelunasan || 0;
                    if (totalPelunasan > 0 && totalDibayar >= totalPelunasan) return;
                    
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
                
                console.log(`   Admin ${adminUid}: target ${adminTarget}`);
                
                // Delay untuk mencegah overload
                await new Promise(r => setTimeout(r, 100));
            }
            
            // Update cabang summaries
            for (const [cabangId, target] of Object.entries(cabangTargets)) {
                await db.ref(`summary/perCabang/${cabangId}/targetHariIni`).set(target);
            }
            
            console.log(`✅ [AUTO] Daily target recalc done. Global target: ${globalTarget}`);
            return null;
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            throw error;
        }
    });

// =========================================================================
// WEEKLY RECALCULATION (MINGGU 02:00 WIB) - OTOMATIS SETIAP MINGGU
// =========================================================================
exports.weeklyFullRecalc = functions.pubsub
    .schedule('0 2 * * 0')
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log(`🔄 [AUTO] Weekly recalculation started`);
        
        try {
            // 1. Recalculate semua admin
            const adminsSnap = await db.ref('metadata/admins').once('value');
            
            for (const [adminUid, adminData] of Object.entries(adminsSnap.val() || {})) {
                if (adminData.role === 'admin') {
                    try {
                        await fullRecalculateAdminSummary(adminUid);
                    } catch (e) {
                        console.error(`Error for admin ${adminUid}: ${e.message}`);
                    }
                    await new Promise(r => setTimeout(r, 500));
                }
            }
            
            // 2. Recalculate semua cabang
            const cabangSnap = await db.ref('metadata/cabang').once('value');
            
            // v5: Aggregate SEMUA field dari perAdmin ke perCabang
            for (const [cabangId, cabangData] of Object.entries(cabangSnap.val() || {})) {
                const adminList = cabangData.adminList || [];
                
                let totalNasabah = 0, nasabahAktif = 0, nasabahLunas = 0;
                let nasabahMenunggu = 0, nasabahMenungguPencairan = 0;  // v5: DITAMBAHKAN
                let totalPinjamanAktif = 0, totalPiutang = 0;
                let nasabahBaruHariIni = 0, nasabahLunasHariIni = 0, targetHariIni = 0;
                let pembayaranHariIni = 0;  // v5: DITAMBAHKAN
                
                for (const adminUid of adminList) {
                    const adminSummary = await db.ref(`summary/perAdmin/${adminUid}`).once('value');
                    const data = adminSummary.val();
                    
                    if (data) {
                        totalNasabah += data.totalNasabah || 0;
                        nasabahAktif += data.nasabahAktif || 0;
                        nasabahLunas += data.nasabahLunas || 0;
                        nasabahMenunggu += data.nasabahMenunggu || 0;                      // v5
                        nasabahMenungguPencairan += data.nasabahMenungguPencairan || 0;    // v5
                        totalPinjamanAktif += data.totalPinjamanAktif || 0;
                        totalPiutang += data.totalPiutang || 0;
                        nasabahBaruHariIni += data.nasabahBaruHariIni || 0;
                        nasabahLunasHariIni += data.nasabahLunasHariIni || 0;
                        targetHariIni += data.targetHariIni || 0;
                        pembayaranHariIni += data.pembayaranHariIni || 0;                  // v5
                    }
                }
                
                await db.ref(`summary/perCabang/${cabangId}`).update({
                    totalNasabah,
                    nasabahAktif,
                    nasabahLunas,
                    nasabahMenunggu,               // v5: DITAMBAHKAN
                    nasabahMenungguPencairan,      // v5: DITAMBAHKAN
                    totalPinjamanAktif,
                    totalPiutang,
                    nasabahBaruHariIni,
                    nasabahLunasHariIni,
                    targetHariIni,
                    pembayaranHariIni,             // v5: dari aggregate, bukan hardcode 0
                    adminCount: adminList.length,
                    lastUpdated: admin.database.ServerValue.TIMESTAMP
                });
            }
            
            // v5: Aggregate SEMUA field dari perCabang ke global
            const allCabangSummaries = await db.ref('summary/perCabang').once('value');
            
            let globalNasabah = 0, globalAktif = 0, globalPinjaman = 0, globalTunggakan = 0, totalCabang = 0;
            let globalNasabahBaru = 0, globalNasabahLunas = 0;
            let globalTargetHariIni = 0, globalPembayaranHariIni = 0;  // v5: DITAMBAHKAN
            let globalMenungguPencairan = 0;  // v5: DITAMBAHKAN
            
            allCabangSummaries.forEach(child => {
                const data = child.val();
                if (data) {
                    totalCabang++;
                    globalNasabah += data.totalNasabah || 0;
                    globalAktif += data.nasabahAktif || 0;                        // v5
                    globalPinjaman += data.totalPinjamanAktif || 0;
                    globalTunggakan += data.totalPiutang || 0;
                    globalNasabahBaru += data.nasabahBaruHariIni || 0;
                    globalNasabahLunas += data.nasabahLunasHariIni || 0;
                    globalTargetHariIni += data.targetHariIni || 0;               // v5
                    globalPembayaranHariIni += data.pembayaranHariIni || 0;       // v5
                    globalMenungguPencairan += data.nasabahMenungguPencairan || 0; // v5
                }
            });
            
            await db.ref('summary/global').update({
                totalNasabah: globalNasabah,
                nasabahAktif: globalAktif,                    // v5: DITAMBAHKAN
                totalPinjamanAktif: globalPinjaman,
                totalTunggakan: globalTunggakan,
                targetHariIni: globalTargetHariIni,           // v5: DITAMBAHKAN
                pembayaranHariIni: globalPembayaranHariIni,   // v5: dari aggregate, bukan hardcode 0
                nasabahBaruHariIni: globalNasabahBaru,
                nasabahLunasHariIni: globalNasabahLunas,
                nasabahMenungguPencairan: globalMenungguPencairan,  // v5: DITAMBAHKAN
                totalCabang,
                lastFullRecalc: admin.database.ServerValue.TIMESTAMP,
                lastUpdated: admin.database.ServerValue.TIMESTAMP
            });
            
            console.log(`✅ [AUTO] Weekly recalc done: ${globalNasabah} nasabah, target: ${globalTargetHariIni}`);
            return null;
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            throw error;
        }
    });

// =========================================================================
// CLEANUP APPROVALS (01:00 WIB) - OTOMATIS SETIAP HARI
// =========================================================================
exports.cleanupProcessedApprovals = functions.pubsub
    .schedule('0 1 * * *')
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log(`🧹 [AUTO] Cleanup started`);
        
        try {
            const cabangSnap = await db.ref('metadata/cabang').once('value');
            let totalDeleted = 0;
            
            const sevenDaysAgo = Date.now() - (7 * 24 * 60 * 60 * 1000);
            
            for (const cabangId of Object.keys(cabangSnap.val() || {})) {
                const approvalsSnap = await db.ref(`pengajuan_approval/${cabangId}`)
                    .orderByChild('timestamp')
                    .endAt(sevenDaysAgo)
                    .once('value');
                
                const deletePromises = [];
                
                approvalsSnap.forEach(child => {
                    const status = child.val()?.status;
                    if (status && status !== 'Menunggu Approval') {
                        deletePromises.push(
                            db.ref(`pengajuan_approval/${cabangId}/${child.key}`).remove()
                        );
                        totalDeleted++;
                    }
                });
                
                await Promise.all(deletePromises);
            }
            
            console.log(`✅ [AUTO] Cleanup done: ${totalDeleted} deleted`);
            return null;
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            throw error;
        }
    });

// =========================================================================
// CLEANUP OLD NOTIFICATIONS (01:30 WIB)
// =========================================================================
exports.cleanupOldNotifications = functions.pubsub
    .schedule('30 1 * * *')
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log(`🧹 [AUTO] Cleanup old notifications started`);
        
        try {
            const thirtyDaysAgo = Date.now() - (30 * 24 * 60 * 60 * 1000);
            let totalDeleted = 0;
            
            const notifSnap = await db.ref('admin_notifications').once('value');
            
            const deletePromises = [];
            
            notifSnap.forEach(userNotifs => {
                userNotifs.forEach(notif => {
                    const data = notif.val();
                    if (data.read === true && data.timestamp && data.timestamp < thirtyDaysAgo) {
                        deletePromises.push(notif.ref.remove());
                        totalDeleted++;
                    }
                });
            });
            
            await Promise.all(deletePromises);
            
            console.log(`✅ [AUTO] Cleaned up ${totalDeleted} old notifications`);
            return null;
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            throw error;
        }
    });

// =========================================================================
// HEALTH CHECK (SETIAP 6 JAM) - OTOMATIS
// =========================================================================
exports.summaryHealthCheck = functions.pubsub
    .schedule('0 */6 * * *')
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log(`🏥 [AUTO] Health check started`);
        
        try {
            const issues = [];
            
            const globalSnap = await db.ref('summary/global').once('value');
            if (!globalSnap.exists()) {
                issues.push('Global summary missing - will be auto-created on first data');
            }
            
            const cabangSnap = await db.ref('metadata/cabang').once('value');
            for (const cabangId of Object.keys(cabangSnap.val() || {})) {
                const cabangSummarySnap = await db.ref(`summary/perCabang/${cabangId}`).once('value');
                if (!cabangSummarySnap.exists()) {
                    issues.push(`Cabang ${cabangId} summary missing - will be auto-created`);
                }
            }
            
            if (issues.length > 0) {
                console.warn(`⚠️ Issues found: ${issues.length}`);
                issues.forEach(i => console.warn(`   - ${i}`));
            } else {
                console.log(`✅ Health check passed`);
            }
            
            await db.ref('summary/global/lastHealthCheck').set(admin.database.ServerValue.TIMESTAMP);
            
            return null;
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            throw error;
        }
    });

// =========================================================================
// CLEANUP OLD EVENT HARIAN (02:00 WIB)
// =========================================================================
exports.cleanupOldEventHarian = functions.pubsub
    .schedule('0 2 * * *')
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log(`🧹 [AUTO] Cleanup old event_harian started`);
        
        try {
            const sevenDaysAgo = new Date();
            sevenDaysAgo.setDate(sevenDaysAgo.getDate() - 7);
            
            const months = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 
                            'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
            
            const cabangSnap = await db.ref('metadata/cabang').once('value');
            let totalDeleted = 0;
            
            for (const cabangId of Object.keys(cabangSnap.val() || {})) {
                // Cleanup pembayaran_harian
                const pembayaranSnap = await db.ref(`pembayaran_harian/${cabangId}`).once('value');
                
                pembayaranSnap.forEach(dateChild => {
                    const tanggal = dateChild.key;
                    
                    const parts = tanggal.split(' ');
                    if (parts.length !== 3) return;
                    
                    const day = parseInt(parts[0]);
                    const monthIndex = months.indexOf(parts[1]);
                    const year = parseInt(parts[2]);
                    
                    if (monthIndex === -1 || isNaN(day) || isNaN(year)) return;
                    
                    const recordDate = new Date(year, monthIndex, day);
                    
                    if (recordDate < sevenDaysAgo) {
                        db.ref(`pembayaran_harian/${cabangId}/${tanggal}`).remove();
                        totalDeleted++;
                    }
                });
                
                // Cleanup event_harian
                const eventSnap = await db.ref(`event_harian/${cabangId}`).once('value');
                
                eventSnap.forEach(dateChild => {
                    const tanggal = dateChild.key;
                    
                    const parts = tanggal.split(' ');
                    if (parts.length !== 3) return;
                    
                    const day = parseInt(parts[0]);
                    const monthIndex = months.indexOf(parts[1]);
                    const year = parseInt(parts[2]);
                    
                    if (monthIndex === -1 || isNaN(day) || isNaN(year)) return;
                    
                    const recordDate = new Date(year, monthIndex, day);
                    
                    if (recordDate < sevenDaysAgo) {
                        db.ref(`event_harian/${cabangId}/${tanggal}`).remove();
                        totalDeleted++;
                    }
                });
            }
            
            console.log(`✅ [AUTO] Cleanup done: ${totalDeleted} records deleted`);
            return null;
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            throw error;
        }
    });

// =========================================================================
// ✅ PERBAIKAN v3: DAILY UPDATE PELANGGAN BERMASALAH (06:00 WIB)
// =========================================================================
// Skip nasabah yang sudah lunas berdasarkan pembayaran
// =========================================================================
exports.dailyUpdatePelangganBermasalah = functions.pubsub
    .schedule('0 6 * * *')
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log('🔄 [AUTO] Daily update pelanggan_bermasalah started');
        
        const today = getTodayIndonesia();
        
        try {
            const adminsSnap = await db.ref('metadata/admins').once('value');
            let totalBermasalah = 0;
            
            for (const [adminUid, adminData] of Object.entries(adminsSnap.val() || {})) {
                if (adminData.role !== 'admin') continue;
                
                const cabangId = adminData.cabang;
                if (!cabangId) continue;
                
                const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
                
                pelangganSnap.forEach(child => {
                    const p = child.val();
                    if (!p) return;
                    
                    const status = (p.status || '').toLowerCase();
                    if (status !== 'aktif' && status !== 'active') return;
                    
                    // ✅ PERBAIKAN v3: Cek apakah sudah lunas
                    let totalDibayar = 0;
                    if (p.pembayaranList) {
                        const pembayaranList = Array.isArray(p.pembayaranList)
                            ? p.pembayaranList
                            : Object.values(p.pembayaranList || {});
                        pembayaranList.forEach(pay => {
                            totalDibayar += pay.jumlah || 0;
                            if (pay.subPembayaran) {
                                const subList = Array.isArray(pay.subPembayaran)
                                    ? pay.subPembayaran
                                    : Object.values(pay.subPembayaran || {});
                                subList.forEach(sub => totalDibayar += sub.jumlah || 0);
                            }
                        });
                    }
                    
                    const totalPelunasan = p.totalPelunasan || 0;
                    
                    // ✅ PERBAIKAN v3: Skip jika sudah lunas
                    if (totalPelunasan > 0 && totalDibayar >= totalPelunasan) {
                        // Sudah lunas, hapus dari bermasalah jika ada
                        db.ref(`pelanggan_bermasalah/${cabangId}/${child.key}`).remove();
                        return;
                    }
                    
                    // Hitung hari tunggakan
                    const hariTunggakan = hitungHariTunggakan(p, today);
                    
                    if (hariTunggakan > 7) {
                        const totalPiutang = Math.max(0, totalPelunasan - totalDibayar);
                        
                        let kategori = 'ringan';
                        if (hariTunggakan > 90) kategori = 'macet';
                        else if (hariTunggakan > 60) kategori = 'berat';
                        else if (hariTunggakan > 30) kategori = 'sedang';
                        
                        let lastPaymentDate = '';
                        if (p.pembayaranList) {
                            const pembayaranList = Array.isArray(p.pembayaranList)
                                ? p.pembayaranList
                                : Object.values(p.pembayaranList || {});
                            const lastPay = pembayaranList[pembayaranList.length - 1];
                            if (lastPay) {
                                lastPaymentDate = lastPay.tanggal || '';
                            }
                        }
                        
                        const bermasalahData = {
                            namaPanggilan: p.namaPanggilan || '',
                            namaKtp: p.namaKtp || '',
                            adminUid: adminUid,
                            adminName: adminData.name || adminData.email || '',
                            totalPiutang: totalPiutang,
                            totalPinjaman: totalPelunasan,
                            hariTunggakan: hariTunggakan,
                            kategori: kategori,
                            wilayah: p.wilayah || '',
                            noHp: p.noHp || '',
                            lastPaymentDate: lastPaymentDate,
                            timestamp: admin.database.ServerValue.TIMESTAMP
                        };
                        
                        db.ref(`pelanggan_bermasalah/${cabangId}/${child.key}`).set(bermasalahData);
                        totalBermasalah++;
                    } else {
                        db.ref(`pelanggan_bermasalah/${cabangId}/${child.key}`).remove();
                    }
                });
            }
            
            console.log(`✅ [AUTO] Updated ${totalBermasalah} pelanggan_bermasalah`);
            return null;
            
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            throw error;
        }
    });

// =========================================================================
// HELPER: Hitung hari tunggakan
// =========================================================================
function hitungHariTunggakan(pelanggan, today) {
    if (!pelanggan.hasilSimulasiCicilan) return 0;
    
    const simulasiList = Array.isArray(pelanggan.hasilSimulasiCicilan)
        ? pelanggan.hasilSimulasiCicilan
        : Object.values(pelanggan.hasilSimulasiCicilan || {});
    
    const pembayaranDone = new Set();
    if (pelanggan.pembayaranList) {
        const pembayaranList = Array.isArray(pelanggan.pembayaranList)
            ? pelanggan.pembayaranList
            : Object.values(pelanggan.pembayaranList || {});
        pembayaranList.forEach(p => {
            if (p.tanggal) pembayaranDone.add(p.tanggal);
        });
    }
    
    const todayDate = parseTanggalIndonesia(today);
    if (!todayDate) return 0;
    
    let hariTertunggak = 0;
    
    simulasiList.forEach(cicilan => {
        if (!cicilan.tanggal) return;
        if (pembayaranDone.has(cicilan.tanggal)) return;
        
        const cicilanDate = parseTanggalIndonesia(cicilan.tanggal);
        if (!cicilanDate) return;
        
        if (cicilanDate < todayDate) {
            const diffTime = Math.abs(todayDate - cicilanDate);
            const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
            if (diffDays > hariTertunggak) {
                hariTertunggak = diffDays;
            }
        }
    });
    
    return hariTertunggak;
}

// =========================================================================
// HELPER: Parse tanggal Indonesia
// =========================================================================
function parseTanggalIndonesia(dateStr) {
    if (!dateStr || typeof dateStr !== 'string') return null;
    
    const months = {
        'Jan': 0, 'Feb': 1, 'Mar': 2, 'Apr': 3, 'Mei': 4, 'Jun': 5,
        'Jul': 6, 'Agu': 7, 'Sep': 8, 'Okt': 9, 'Nov': 10, 'Des': 11
    };
    
    const parts = dateStr.trim().split(' ');
    if (parts.length !== 3) return null;
    
    const day = parseInt(parts[0]);
    const month = months[parts[1]];
    const year = parseInt(parts[2]);
    
    if (isNaN(day) || month === undefined || isNaN(year)) return null;
    
    return new Date(year, month, day);
}

// =========================================================================
// HTTP FUNCTIONS - Backfill Manual
// =========================================================================

// Helper: Format tanggal Indonesia
function getTodayIndonesiaLocal() {
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(now.getTime() + wibOffset);
    
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 
                    'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
    
    const day = wibDate.getUTCDate().toString().padStart(2, '0');
    const month = months[wibDate.getUTCMonth()];
    const year = wibDate.getUTCFullYear();
    
    return `${day} ${month} ${year}`;
}

// =========================================================================
// HTTP FUNCTION: Backfill Pembayaran Harian
// =========================================================================
exports.backfillPembayaranHarian = functions.https.onRequest(async (req, res) => {
    console.log('🔄 Starting backfill pembayaran_harian...');
    
    const today = getTodayIndonesiaLocal();
    console.log(`📅 Today: ${today}`);
    
    try {
        const adminsSnap = await db.ref('metadata/admins').once('value');
        const admins = adminsSnap.val() || {};
        
        let totalPembayaran = 0;
        let totalPencairan = 0;
        const results = [];
        
        for (const [adminUid, adminData] of Object.entries(admins)) {
            if (adminData.role !== 'admin') continue;
            
            const cabangId = adminData.cabang;
            if (!cabangId) continue;
            
            console.log(`\n📋 Processing admin: ${adminUid} (${adminData.email || adminData.name})`);
            
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
            
            let adminPembayaran = 0;
            let adminPencairan = 0;
            
            pelangganSnap.forEach(child => {
                const pelanggan = child.val();
                if (!pelanggan) return;
                
                const pelangganId = child.key;
                const namaPanggilan = pelanggan.namaPanggilan || pelanggan.namaKtp || 'Unknown';
                
                // 1. PROSES PEMBAYARAN CICILAN HARI INI
                if (pelanggan.pembayaranList) {
                    const pembayaranList = Array.isArray(pelanggan.pembayaranList)
                        ? pelanggan.pembayaranList
                        : Object.values(pelanggan.pembayaranList || {});
                    
                    pembayaranList.forEach((pembayaran, index) => {
                        if (pembayaran.tanggal === today && pembayaran.jumlah > 0) {
                            const pembayaranData = {
                                pelangganId: pelangganId,
                                namaPanggilan: namaPanggilan,
                                namaKtp: pelanggan.namaKtp || '',
                                adminUid: adminUid,
                                adminName: adminData.name || adminData.email || '',
                                adminEmail: adminData.email || '',
                                jumlah: pembayaran.jumlah,
                                jenis: 'cicilan',
                                tanggal: today,
                                timestamp: admin.database.ServerValue.TIMESTAMP
                            };
                            
                            db.ref(`pembayaran_harian/${cabangId}/${today}`).push(pembayaranData);
                            adminPembayaran += pembayaran.jumlah;
                            console.log(`   💰 Cicilan: ${namaPanggilan} - Rp ${pembayaran.jumlah}`);
                        }
                        
                        if (pembayaran.subPembayaran) {
                            const subList = Array.isArray(pembayaran.subPembayaran)
                                ? pembayaran.subPembayaran
                                : Object.values(pembayaran.subPembayaran || {});
                            
                            subList.forEach(sub => {
                                if (sub.tanggal === today && sub.jumlah > 0) {
                                    const subData = {
                                        pelangganId: pelangganId,
                                        namaPanggilan: namaPanggilan,
                                        namaKtp: pelanggan.namaKtp || '',
                                        adminUid: adminUid,
                                        adminName: adminData.name || adminData.email || '',
                                        adminEmail: adminData.email || '',
                                        jumlah: sub.jumlah,
                                        jenis: 'tambah_bayar',
                                        tanggal: today,
                                        timestamp: admin.database.ServerValue.TIMESTAMP
                                    };
                                    
                                    db.ref(`pembayaran_harian/${cabangId}/${today}`).push(subData);
                                    adminPembayaran += sub.jumlah;
                                    console.log(`   💰 Tambah Bayar: ${namaPanggilan} - Rp ${sub.jumlah}`);
                                }
                            });
                        }
                    });
                }
                
                // 2. PROSES PENCAIRAN HARI INI
                const status = (pelanggan.status || '').toLowerCase();
                const tanggalDaftar = pelanggan.tanggalDaftar || pelanggan.tanggalPengajuan || '';
                
                if (status === 'aktif' && tanggalDaftar === today && pelanggan.besarPinjaman > 0) {
                    let jumlahDicairkan = 0;
                    if (pelanggan.totalDiterima > 0) {
                        jumlahDicairkan = pelanggan.totalDiterima;
                    } else if (pelanggan.admin > 0 && pelanggan.simpanan > 0) {
                        jumlahDicairkan = pelanggan.besarPinjaman - pelanggan.admin - pelanggan.simpanan;
                    } else {
                        const adminFee = Math.round(pelanggan.besarPinjaman * 0.05);
                        const simpanan = Math.round(pelanggan.besarPinjaman * 0.05);
                        jumlahDicairkan = pelanggan.besarPinjaman - adminFee - simpanan;
                    }
                    
                    if (jumlahDicairkan > 0) {
                        const pencairanData = {
                            pelangganId: pelangganId,
                            namaPanggilan: namaPanggilan,
                            namaKtp: pelanggan.namaKtp || '',
                            adminUid: adminUid,
                            adminName: adminData.name || adminData.email || '',
                            adminEmail: adminData.email || '',
                            jumlah: jumlahDicairkan,
                            jenis: 'pencairan',
                            tanggal: today,
                            timestamp: admin.database.ServerValue.TIMESTAMP
                        };
                        
                        db.ref(`pembayaran_harian/${cabangId}/${today}`).push(pencairanData);
                        adminPencairan += jumlahDicairkan;
                        console.log(`   🎉 Pencairan: ${namaPanggilan} - Rp ${jumlahDicairkan}`);
                    }
                }
            });
            
            totalPembayaran += adminPembayaran;
            totalPencairan += adminPencairan;
            
            results.push({
                adminUid: adminUid,
                adminName: adminData.name || adminData.email || '',
                cabangId: cabangId,
                pembayaran: adminPembayaran,
                pencairan: adminPencairan
            });
            
            console.log(`   ✅ Admin done: pembayaran=${adminPembayaran}, pencairan=${adminPencairan}`);
        }
        
        console.log(`\n✅ Backfill completed!`);
        console.log(`   Total Pembayaran: Rp ${totalPembayaran}`);
        console.log(`   Total Pencairan: Rp ${totalPencairan}`);
        
        res.json({
            success: true,
            today: today,
            totalPembayaran: totalPembayaran,
            totalPencairan: totalPencairan,
            adminResults: results
        });
        
    } catch (error) {
        console.error('❌ Error:', error);
        res.status(500).json({
            success: false,
            error: error.message
        });
    }
});

// =========================================================================
// HTTP FUNCTION: Backfill Event Harian
// =========================================================================
exports.backfillEventHarian = functions.https.onRequest(async (req, res) => {
    console.log('🔄 Starting backfill event_harian...');
    
    const today = getTodayIndonesiaLocal();
    console.log(`📅 Today: ${today}`);
    
    try {
        const adminsSnap = await db.ref('metadata/admins').once('value');
        let totalNasabahBaru = 0;
        let totalNasabahLunas = 0;
        
        for (const [adminUid, adminData] of Object.entries(adminsSnap.val() || {})) {
            if (adminData.role !== 'admin') continue;
            
            const cabangId = adminData.cabang;
            if (!cabangId) continue;
            
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
            
            pelangganSnap.forEach(child => {
                const p = child.val();
                if (!p) return;
                
                const pelangganId = child.key;
                const status = (p.status || '').toLowerCase();
                const tanggalDaftar = p.tanggalDaftar || '';
                const tanggalPengajuan = p.tanggalPengajuan || '';
                const isBaruAtauTopUpHariIni = tanggalDaftar === today || tanggalPengajuan === today;
                
                // 1. Nasabah Baru Hari Ini (termasuk top up yang baru diajukan)
                // ✅ PERBAIKAN v2: Tambahkan 'menunggu approval' untuk top up yang belum di-approve
                const isStatusValid = status === 'aktif' || status === 'active' || status === 'menunggu approval';
                
                if (isStatusValid && isBaruAtauTopUpHariIni) {
                    const nasabahBaruData = {
                        namaPanggilan: p.namaPanggilan || '',
                        namaKtp: p.namaKtp || '',
                        adminUid: adminUid,
                        adminName: adminData.name || adminData.email || '',
                        besarPinjaman: p.besarPinjaman || 0,
                        totalDiterima: p.totalDiterima || 0,
                        wilayah: p.wilayah || '',
                        tanggalDaftar: tanggalDaftar || today,
                        tanggalPengajuan: tanggalPengajuan || today,
                        pinjamanKe: p.pinjamanKe || 1,
                        status: p.status || '',
                        timestamp: admin.database.ServerValue.TIMESTAMP
                    };
                    
                    db.ref(`event_harian/${cabangId}/${today}/nasabah_baru/${pelangganId}`).set(nasabahBaruData);
                    totalNasabahBaru++;
                    console.log(`   🆕 Nasabah: ${p.namaPanggilan} (pinjamanKe: ${p.pinjamanKe || 1}, status: ${p.status})`);
                }
                
                // 2. Nasabah Lunas Hari Ini
                // ✅ PERBAIKAN v3: Cek lunas berdasarkan pembayaran
                let totalDibayar = 0;
                if (p.pembayaranList) {
                    const pembayaranList = Array.isArray(p.pembayaranList)
                        ? p.pembayaranList
                        : Object.values(p.pembayaranList || {});
                    pembayaranList.forEach(pay => {
                        totalDibayar += pay.jumlah || 0;
                        if (pay.subPembayaran) {
                            const subList = Array.isArray(pay.subPembayaran)
                                ? pay.subPembayaran
                                : Object.values(pay.subPembayaran || {});
                            subList.forEach(sub => totalDibayar += sub.jumlah || 0);
                        }
                    });
                }
                
                const totalPelunasan = p.totalPelunasan || 0;
                const isLunas = totalPelunasan > 0 && totalDibayar >= totalPelunasan;
                
                if (isLunas) {
                    // Cek apakah ada pembayaran hari ini
                    let adaPembayaranHariIni = false;
                    if (p.pembayaranList) {
                        const pembayaranList = Array.isArray(p.pembayaranList)
                            ? p.pembayaranList
                            : Object.values(p.pembayaranList || {});
                        adaPembayaranHariIni = pembayaranList.some(pay => {
                            if (pay.tanggal === today) return true;
                            if (pay.subPembayaran) {
                                const subList = Array.isArray(pay.subPembayaran)
                                    ? pay.subPembayaran
                                    : Object.values(pay.subPembayaran || {});
                                return subList.some(sub => sub.tanggal === today);
                            }
                            return false;
                        });
                    }
                    
                    if (adaPembayaranHariIni) {
                        const nasabahLunasData = {
                            namaPanggilan: p.namaPanggilan || '',
                            namaKtp: p.namaKtp || '',
                            adminUid: adminUid,
                            adminName: adminData.name || adminData.email || '',
                            totalPinjaman: totalPelunasan,
                            totalDibayar: totalDibayar,
                            wilayah: p.wilayah || '',
                            tanggalLunas: today,
                            timestamp: admin.database.ServerValue.TIMESTAMP
                        };
                        
                        db.ref(`event_harian/${cabangId}/${today}/nasabah_lunas/${pelangganId}`).set(nasabahLunasData);
                        totalNasabahLunas++;
                        console.log(`   ✅ Nasabah lunas: ${p.namaPanggilan}`);
                    }
                }
            });
        }
        
        console.log(`✅ Backfill completed!`);
        console.log(`   Total Nasabah Baru: ${totalNasabahBaru}`);
        console.log(`   Total Nasabah Lunas: ${totalNasabahLunas}`);
        
        res.json({
            success: true,
            today: today,
            totalNasabahBaru: totalNasabahBaru,
            totalNasabahLunas: totalNasabahLunas
        });
        
    } catch (error) {
        console.error('❌ Error:', error);
        res.status(500).json({
            success: false,
            error: error.message
        });
    }
});

// =========================================================================
// HTTP FUNCTION: Update Pelanggan Bermasalah Manual
// =========================================================================
exports.updatePelangganBermasalah = functions.https.onRequest(async (req, res) => {
    console.log('🔄 Starting update pelanggan_bermasalah...');
    
    const today = getTodayIndonesiaLocal();
    
    try {
        const adminsSnap = await db.ref('metadata/admins').once('value');
        let totalBermasalah = 0;
        
        for (const [adminUid, adminData] of Object.entries(adminsSnap.val() || {})) {
            if (adminData.role !== 'admin') continue;
            
            const cabangId = adminData.cabang;
            if (!cabangId) continue;
            
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
            
            pelangganSnap.forEach(child => {
                const p = child.val();
                if (!p) return;
                
                const status = (p.status || '').toLowerCase();
                if (status !== 'aktif' && status !== 'active') return;
                
                // ✅ PERBAIKAN v3: Cek apakah sudah lunas
                let totalDibayar = 0;
                if (p.pembayaranList) {
                    const pembayaranList = Array.isArray(p.pembayaranList)
                        ? p.pembayaranList
                        : Object.values(p.pembayaranList || {});
                    pembayaranList.forEach(pay => {
                        totalDibayar += pay.jumlah || 0;
                        if (pay.subPembayaran) {
                            const subList = Array.isArray(pay.subPembayaran)
                                ? pay.subPembayaran
                                : Object.values(pay.subPembayaran || {});
                            subList.forEach(sub => totalDibayar += sub.jumlah || 0);
                        }
                    });
                }
                
                const totalPelunasan = p.totalPelunasan || 0;
                
                // Skip jika sudah lunas
                if (totalPelunasan > 0 && totalDibayar >= totalPelunasan) {
                    db.ref(`pelanggan_bermasalah/${cabangId}/${child.key}`).remove();
                    return;
                }
                
                const hariTunggakan = hitungHariTunggakan(p, today);
                
                if (hariTunggakan > 7) {
                    const totalPiutang = Math.max(0, totalPelunasan - totalDibayar);
                    
                    let kategori = 'ringan';
                    if (hariTunggakan > 90) kategori = 'macet';
                    else if (hariTunggakan > 60) kategori = 'berat';
                    else if (hariTunggakan > 30) kategori = 'sedang';
                    
                    let lastPaymentDate = '';
                    if (p.pembayaranList) {
                        const pembayaranList = Array.isArray(p.pembayaranList)
                            ? p.pembayaranList
                            : Object.values(p.pembayaranList || {});
                        const lastPay = pembayaranList[pembayaranList.length - 1];
                        if (lastPay) lastPaymentDate = lastPay.tanggal || '';
                    }
                    
                    const bermasalahData = {
                        namaPanggilan: p.namaPanggilan || '',
                        namaKtp: p.namaKtp || '',
                        adminUid: adminUid,
                        adminName: adminData.name || adminData.email || '',
                        totalPiutang: totalPiutang,
                        totalPinjaman: totalPelunasan,
                        hariTunggakan: hariTunggakan,
                        kategori: kategori,
                        wilayah: p.wilayah || '',
                        noHp: p.noHp || '',
                        lastPaymentDate: lastPaymentDate,
                        timestamp: admin.database.ServerValue.TIMESTAMP
                    };
                    
                    db.ref(`pelanggan_bermasalah/${cabangId}/${child.key}`).set(bermasalahData);
                    totalBermasalah++;
                    console.log(`   ⚠️ Bermasalah: ${p.namaPanggilan} (${hariTunggakan} hari)`);
                }
            });
        }
        
        console.log(`✅ Update completed! Total: ${totalBermasalah}`);
        
        res.json({
            success: true,
            today: today,
            totalBermasalah: totalBermasalah
        });
        
    } catch (error) {
        console.error('❌ Error:', error);
        res.status(500).json({
            success: false,
            error: error.message
        });
    }
});

// =========================================================================
// CLEANUP EXPIRED BROADCASTS (Setiap jam)
// =========================================================================
exports.cleanupExpiredBroadcasts = functions.pubsub
    .schedule('0 * * * *')  // Setiap jam di menit ke-0
    .timeZone('Asia/Jakarta')
    .onRun(async (context) => {
        console.log('🧹 Cleaning up expired broadcasts...');
        
        try {
            const now = Date.now();
            const broadcastsRef = admin.database().ref('broadcast_messages');
            const snapshot = await broadcastsRef.once('value');
            
            const deletePromises = [];
            
            snapshot.forEach(child => {
                const broadcast = child.val();
                const expiresAt = broadcast.expiresAt || 0;
                
                // Hapus jika sudah expired DAN expiresAt sudah di-set
                if (expiresAt > 0 && expiresAt < now) {
                    console.log(`🗑️ Deleting expired broadcast: ${child.key}`);
                    deletePromises.push(broadcastsRef.child(child.key).remove());
                }
            });
            
            if (deletePromises.length > 0) {
                await Promise.all(deletePromises);
                console.log(`✅ Deleted ${deletePromises.length} expired broadcasts`);
            } else {
                console.log('✅ No expired broadcasts to clean');
            }
            
            return null;
        } catch (error) {
            console.error('❌ Error cleaning broadcasts:', error);
            return null;
        }
    });