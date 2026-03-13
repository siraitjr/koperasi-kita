// functions/summaryHelpers.js
// =========================================================================
// SUMMARY HELPERS - VERSI v8 (TRANSACTION-BASED ATOMIC INCREMENT)
// =========================================================================
// 
// PERBAIKAN v8 (dari v7):
// ✅ FIX #1: Gunakan Firebase TRANSACTION untuk atomic increment (no race condition!)
// ✅ FIX #2: HAPUS pembayaranHariIniChange dari calculateDelta() (no double update!)
// ✅ FIX #3: TAMBAH piutangChange ke processPembayaranBaru() (piutang berkurang saat bayar!)
// ✅ FIX #4: Improve logging untuk debugging
// =========================================================================

const admin = require('firebase-admin');
const db = admin.database();

// =========================================================================
// HELPER: FORMAT TANGGAL INDONESIA
// =========================================================================
function getTodayIndonesia() {
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
// CEK HARI LIBUR
// =========================================================================
function isHoliday(dateStr) {
    if (!dateStr || typeof dateStr !== 'string') return false;
    
    const months = {
        'Jan': 0, 'Feb': 1, 'Mar': 2, 'Apr': 3, 'Mei': 4, 'Jun': 5,
        'Jul': 6, 'Agu': 7, 'Sep': 8, 'Okt': 9, 'Nov': 10, 'Des': 11
    };
    
    const parts = dateStr.trim().split(' ');
    if (parts.length !== 3) return false;
    
    const day = parseInt(parts[0]);
    const month = months[parts[1]];
    const year = parseInt(parts[2]);
    
    if (isNaN(day) || month === undefined || isNaN(year)) return false;
    
    const date = new Date(year, month, day);
    const dayOfWeek = date.getDay();
    
    if (dayOfWeek === 0) return true;
    
    const holidays2024 = [
        '01 Jan 2024', '08 Feb 2024', '09 Feb 2024', '10 Feb 2024',
        '11 Mar 2024', '29 Mar 2024', '31 Mar 2024', '08 Apr 2024',
        '09 Apr 2024', '10 Apr 2024', '11 Apr 2024', '12 Apr 2024',
        '01 Mei 2024', '09 Mei 2024', '23 Mei 2024', '01 Jun 2024',
        '17 Jun 2024', '18 Jun 2024', '17 Agu 2024', '16 Sep 2024',
        '25 Des 2024'
    ];
    
    const holidays2025 = [
        '01 Jan 2025', '27 Jan 2025', '28 Jan 2025', '29 Jan 2025',
        '30 Jan 2025', '31 Jan 2025', '28 Feb 2025', '29 Mar 2025',
        '30 Mar 2025', '31 Mar 2025', '01 Apr 2025', '02 Apr 2025',
        '03 Apr 2025', '04 Apr 2025', '05 Apr 2025', '06 Apr 2025',
        '07 Apr 2025', '18 Apr 2025', '01 Mei 2025', '12 Mei 2025',
        '29 Mei 2025', '01 Jun 2025', '06 Jun 2025', '07 Jun 2025',
        '17 Agu 2025', '05 Sep 2025', '25 Des 2025'
    ];
    
    const holidays2026 = [
        '01 Jan 2026', '16 Jan 2026',
        '17 Feb 2026',
        '19 Mar 2026', '21 Mar 2026',
        '03 Apr 2026',
        '01 Mei 2026', '14 Mei 2026',
        '16 Jun 2026',
        '17 Agu 2026', '25 Agu 2026',
        '25 Des 2026'
    ];
    
    return holidays2024.includes(dateStr) || holidays2025.includes(dateStr) || holidays2026.includes(dateStr);
}

// =========================================================================
// HELPER: Hitung total dibayar dari pembayaranList
// =========================================================================
function calculateTotalDibayar(pelanggan) {
    let total = 0;
    
    if (!pelanggan || !pelanggan.pembayaranList) return 0;
    
    const pembayaranList = Array.isArray(pelanggan.pembayaranList)
        ? pelanggan.pembayaranList
        : Object.values(pelanggan.pembayaranList || {});
    
    pembayaranList.forEach(p => {
        // ✅ PERBAIKAN: Exclude pembayaran dengan tanggal 'Bunga...' (konsisten dengan nasabahIndex)
        if (p && p.tanggal && !p.tanggal.startsWith('Bunga')) {
            total += p.jumlah || 0;
            
            if (p.subPembayaran) {
                const subList = Array.isArray(p.subPembayaran)
                    ? p.subPembayaran
                    : Object.values(p.subPembayaran || {});
                subList.forEach(sub => {
                    total += sub.jumlah || 0;
                });
            }
        }
    });
    
    return total;
}

// =========================================================================
// HELPER: Cek apakah ada pembayaran pada tanggal tertentu
// =========================================================================
function adaPembayaranPadaTanggal(pelanggan, tanggal) {
    if (!pelanggan || !pelanggan.pembayaranList) return false;
    
    const pembayaranList = Array.isArray(pelanggan.pembayaranList)
        ? pelanggan.pembayaranList
        : Object.values(pelanggan.pembayaranList || {});
    
    for (const p of pembayaranList) {
        if (p.tanggal === tanggal) return true;
        
        if (p.subPembayaran) {
            const subList = Array.isArray(p.subPembayaran)
                ? p.subPembayaran
                : Object.values(p.subPembayaran || {});
            for (const sub of subList) {
                if (sub.tanggal === tanggal) return true;
            }
        }
    }
    
    return false;
}

// =========================================================================
// HELPER: Hitung TOTAL pembayaran pada tanggal tertentu
// =========================================================================
function hitungPembayaranPadaTanggal(pelanggan, tanggal) {
    if (!pelanggan || !pelanggan.pembayaranList) return 0;
    
    let total = 0;
    const pembayaranList = Array.isArray(pelanggan.pembayaranList)
        ? pelanggan.pembayaranList
        : Object.values(pelanggan.pembayaranList || {});
    
    for (const p of pembayaranList) {
        if (p.tanggal === tanggal) {
            total += p.jumlah || 0;
        }
        
        if (p.subPembayaran) {
            const subList = Array.isArray(p.subPembayaran)
                ? p.subPembayaran
                : Object.values(p.subPembayaran || {});
            for (const sub of subList) {
                if (sub.tanggal === tanggal) {
                    total += sub.jumlah || 0;
                }
            }
        }
    }
    
    return total;
}

// =========================================================================
// CEK NASABAH MACET (> 3 BULAN DARI TANGGAL PENCAIRAN/PENGAJUAN)
// =========================================================================
// Konsisten dengan Android RingkasanDashboardScreen.kt:
// Calendar.MONTH -3, DAY_OF_MONTH = 1
// =========================================================================
function isOverThreeMonths(dateStr) {
    if (!dateStr || typeof dateStr !== 'string') return false;
    
    const months = {
        'Jan': 0, 'Feb': 1, 'Mar': 2, 'Apr': 3, 'Mei': 4, 'Jun': 5,
        'Jul': 6, 'Agu': 7, 'Sep': 8, 'Okt': 9, 'Nov': 10, 'Des': 11
    };
    
    const parts = dateStr.trim().split(' ');
    if (parts.length !== 3) return false;
    
    const day = parseInt(parts[0]);
    const month = months[parts[1]];
    const year = parseInt(parts[2]);
    
    if (isNaN(day) || month === undefined || isNaN(year)) return false;
    
    const acuanDate = new Date(year, month, day);
    
    // 3 bulan yang lalu dari tanggal 1 bulan itu (SAMA dengan Android)
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(now.getTime() + wibOffset);
    const threeMonthsAgo = new Date(wibDate.getUTCFullYear(), wibDate.getUTCMonth() - 3, 1);
    
    return acuanDate < threeMonthsAgo;
}

// Backward compatibility alias
function isOverFourMonths(dateStr) {
    return isOverThreeMonths(dateStr);
}

// =========================================================================
// HELPER: Hitung target hari ini
// =========================================================================
// Konsisten dengan Android RingkasanDashboardScreen.kt:
// targetHarian = besarPinjaman × 3%
// =========================================================================
function calculateTargetHariIni(pelanggan) {
    const today = getTodayIndonesia();
    
    if (isHoliday(today)) return 0;
    if (!pelanggan) return 0;
    
    // Exclude nasabah macet (> 3 bulan) — konsisten dengan Android
    const tglAcuan = (pelanggan.tanggalPencairan || '').trim()
        || (pelanggan.tanggalPengajuan || '').trim()
        || (pelanggan.tanggalDaftar || '').trim();
    if (isOverThreeMonths(tglAcuan)) return 0;
    
    // Exclude nasabah cair hari ini (Android: baru mulai besok)
    const tanggalPencairan = (pelanggan.tanggalPencairan || '').trim();
    if (tanggalPencairan === today) return 0;
    
    // Flat 3% dari besarPinjaman — SAMA dengan Android
    return Math.floor((pelanggan.besarPinjaman || 0) * 3 / 100);
}

// =========================================================================
// ENSURE SUMMARY EXISTS
// =========================================================================
async function ensureAdminSummaryExists(adminUid) {
    const summaryRef = db.ref(`summary/perAdmin/${adminUid}`);
    const snapshot = await summaryRef.once('value');
    
    if (!snapshot.exists()) {
        const defaultSummary = {
            totalNasabah: 0, nasabahAktif: 0, nasabahLunas: 0,
            nasabahMenunggu: 0, nasabahMenungguPencairan: 0,
            nasabahBaruHariIni: 0, nasabahLunasHariIni: 0, targetHariIni: 0,
            totalPinjamanAktif: 0, totalPiutang: 0, pembayaranHariIni: 0,
            lastUpdated: admin.database.ServerValue.TIMESTAMP
        };
        await summaryRef.set(defaultSummary);
    }
}

async function ensureCabangSummaryExists(cabangId) {
    const summaryRef = db.ref(`summary/perCabang/${cabangId}`);
    const snapshot = await summaryRef.once('value');
    
    if (!snapshot.exists()) {
        const defaultSummary = {
            adminCount: 0, totalNasabah: 0, nasabahAktif: 0, nasabahLunas: 0,
            nasabahMenunggu: 0, nasabahMenungguPencairan: 0,
            nasabahBaruHariIni: 0, nasabahLunasHariIni: 0, targetHariIni: 0,
            totalPinjamanAktif: 0, totalPiutang: 0, pembayaranHariIni: 0,
            lastUpdated: admin.database.ServerValue.TIMESTAMP
        };
        await summaryRef.set(defaultSummary);
    }
}

async function ensureGlobalSummaryExists() {
    const summaryRef = db.ref('summary/global');
    const snapshot = await summaryRef.once('value');
    
    if (!snapshot.exists()) {
        const defaultSummary = {
            totalNasabah: 0, nasabahAktif: 0, nasabahLunas: 0,
            nasabahMenunggu: 0, nasabahMenungguPencairan: 0,
            nasabahBaruHariIni: 0, nasabahLunasHariIni: 0, targetHariIni: 0,
            totalPinjamanAktif: 0, totalPiutang: 0, pembayaranHariIni: 0,
            lastUpdated: admin.database.ServerValue.TIMESTAMP
        };
        await summaryRef.set(defaultSummary);
    }
}

// =========================================================================
// ✅ v8 FIX #1: ATOMIC INCREMENT USING FIREBASE TRANSACTION
// =========================================================================
// Solusi untuk RACE CONDITION saat multiple pembayaran sync bersamaan
// Transaction menjamin atomic read-modify-write
// =========================================================================

async function atomicIncrement(ref, field, delta) {
    if (delta === 0) return;
    
    return ref.child(field).transaction(currentValue => {
        const current = currentValue || 0;
        return Math.max(0, current + delta);
    });
}

async function incrementAdminSummary(adminUid, delta) {
    await ensureAdminSummaryExists(adminUid);
    
    const summaryRef = db.ref(`summary/perAdmin/${adminUid}`);
    
    // ✅ v8: Gunakan TRANSACTION untuk setiap field yang berubah
    const promises = [];
    
    if (delta.nasabahChange) promises.push(atomicIncrement(summaryRef, 'totalNasabah', delta.nasabahChange));
    if (delta.aktifChange) promises.push(atomicIncrement(summaryRef, 'nasabahAktif', delta.aktifChange));
    if (delta.lunasChange) promises.push(atomicIncrement(summaryRef, 'nasabahLunas', delta.lunasChange));
    if (delta.menungguChange) promises.push(atomicIncrement(summaryRef, 'nasabahMenunggu', delta.menungguChange));
    if (delta.nasabahMenungguPencairanChange) promises.push(atomicIncrement(summaryRef, 'nasabahMenungguPencairan', delta.nasabahMenungguPencairanChange));
    if (delta.nasabahBaruHariIniChange) promises.push(atomicIncrement(summaryRef, 'nasabahBaruHariIni', delta.nasabahBaruHariIniChange));
    if (delta.nasabahLunasHariIniChange) promises.push(atomicIncrement(summaryRef, 'nasabahLunasHariIni', delta.nasabahLunasHariIniChange));
    if (delta.targetHariIniChange) promises.push(atomicIncrement(summaryRef, 'targetHariIni', delta.targetHariIniChange));
    if (delta.pinjamanAktifChange) promises.push(atomicIncrement(summaryRef, 'totalPinjamanAktif', delta.pinjamanAktifChange));
    if (delta.piutangChange) promises.push(atomicIncrement(summaryRef, 'totalPiutang', delta.piutangChange));
    if (delta.pembayaranHariIniChange) promises.push(atomicIncrement(summaryRef, 'pembayaranHariIni', delta.pembayaranHariIniChange));
    
    // Update timestamp
    promises.push(summaryRef.child('lastUpdated').set(admin.database.ServerValue.TIMESTAMP));
    
    await Promise.all(promises);
    
    console.log(`✅ Admin ${adminUid} summary updated (atomic transaction)`);
}

async function incrementCabangSummary(cabangId, delta) {
    await ensureCabangSummaryExists(cabangId);
    
    const summaryRef = db.ref(`summary/perCabang/${cabangId}`);
    
    // ✅ v8: Gunakan TRANSACTION untuk setiap field yang berubah
    const promises = [];
    
    if (delta.nasabahChange) promises.push(atomicIncrement(summaryRef, 'totalNasabah', delta.nasabahChange));
    if (delta.aktifChange) promises.push(atomicIncrement(summaryRef, 'nasabahAktif', delta.aktifChange));
    if (delta.lunasChange) promises.push(atomicIncrement(summaryRef, 'nasabahLunas', delta.lunasChange));
    if (delta.menungguChange) promises.push(atomicIncrement(summaryRef, 'nasabahMenunggu', delta.menungguChange));
    if (delta.nasabahMenungguPencairanChange) promises.push(atomicIncrement(summaryRef, 'nasabahMenungguPencairan', delta.nasabahMenungguPencairanChange));
    if (delta.nasabahBaruHariIniChange) promises.push(atomicIncrement(summaryRef, 'nasabahBaruHariIni', delta.nasabahBaruHariIniChange));
    if (delta.nasabahLunasHariIniChange) promises.push(atomicIncrement(summaryRef, 'nasabahLunasHariIni', delta.nasabahLunasHariIniChange));
    if (delta.targetHariIniChange) promises.push(atomicIncrement(summaryRef, 'targetHariIni', delta.targetHariIniChange));
    if (delta.pinjamanAktifChange) promises.push(atomicIncrement(summaryRef, 'totalPinjamanAktif', delta.pinjamanAktifChange));
    if (delta.piutangChange) promises.push(atomicIncrement(summaryRef, 'totalPiutang', delta.piutangChange));
    if (delta.pembayaranHariIniChange) promises.push(atomicIncrement(summaryRef, 'pembayaranHariIni', delta.pembayaranHariIniChange));
    
    // Update timestamp
    promises.push(summaryRef.child('lastUpdated').set(admin.database.ServerValue.TIMESTAMP));
    
    await Promise.all(promises);
    
    console.log(`✅ Cabang ${cabangId} summary updated (atomic transaction)`);
}

async function incrementGlobalSummary(delta) {
    await ensureGlobalSummaryExists();
    
    const summaryRef = db.ref('summary/global');
    
    // ✅ v8: Gunakan TRANSACTION untuk setiap field yang berubah
    const promises = [];
    
    if (delta.nasabahChange) promises.push(atomicIncrement(summaryRef, 'totalNasabah', delta.nasabahChange));
    if (delta.aktifChange) promises.push(atomicIncrement(summaryRef, 'nasabahAktif', delta.aktifChange));
    if (delta.lunasChange) promises.push(atomicIncrement(summaryRef, 'nasabahLunas', delta.lunasChange));
    if (delta.menungguChange) promises.push(atomicIncrement(summaryRef, 'nasabahMenunggu', delta.menungguChange));
    if (delta.nasabahMenungguPencairanChange) promises.push(atomicIncrement(summaryRef, 'nasabahMenungguPencairan', delta.nasabahMenungguPencairanChange));
    if (delta.nasabahBaruHariIniChange) promises.push(atomicIncrement(summaryRef, 'nasabahBaruHariIni', delta.nasabahBaruHariIniChange));
    if (delta.nasabahLunasHariIniChange) promises.push(atomicIncrement(summaryRef, 'nasabahLunasHariIni', delta.nasabahLunasHariIniChange));
    if (delta.targetHariIniChange) promises.push(atomicIncrement(summaryRef, 'targetHariIni', delta.targetHariIniChange));
    if (delta.pinjamanAktifChange) promises.push(atomicIncrement(summaryRef, 'totalPinjamanAktif', delta.pinjamanAktifChange));
    if (delta.piutangChange) promises.push(atomicIncrement(summaryRef, 'totalPiutang', delta.piutangChange));
    if (delta.pembayaranHariIniChange) promises.push(atomicIncrement(summaryRef, 'pembayaranHariIni', delta.pembayaranHariIniChange));
    
    // Update timestamp
    promises.push(summaryRef.child('lastUpdated').set(admin.database.ServerValue.TIMESTAMP));
    
    await Promise.all(promises);
    
    console.log(`✅ Global summary updated (atomic transaction)`);
}

// =========================================================================
// ✅ v8 FIX #2: CALCULATE DELTA - TIDAK HITUNG pembayaranHariIniChange!
// =========================================================================
// pembayaranHariIni HANYA di-update oleh processPembayaranBaru()
// Ini mencegah DOUBLE UPDATE
// =========================================================================
function calculateDelta(before, after) {
    const today = getTodayIndonesia();
    
    const delta = {
        nasabahChange: 0, aktifChange: 0, lunasChange: 0, menungguChange: 0,
        nasabahMenungguPencairanChange: 0, pinjamanAktifChange: 0, piutangChange: 0,
        pembayaranHariIniChange: 0, // ✅ v8: SELALU 0! Di-handle oleh processPembayaranBaru
        nasabahBaruHariIniChange: 0,
        nasabahLunasHariIniChange: 0, targetHariIniChange: 0
    };
    
    // =========================================================================
    // HELPER: Tentukan "kategori efektif" nasabah
    // =========================================================================
    function getEffectiveCategory(data) {
        if (!data) return 'none';
        
        const status = (data.status || '').toLowerCase();
        if (status === 'ditolak') return 'ditolak';
        if (status === 'menunggu approval') return 'menunggu';
        if (status === 'disetujui') return 'disetujui';
        if (status === 'tidak aktif') return 'tidakAktif';
        
        const totalPelunasan = data.totalPelunasan || 0;
        const totalDibayar = calculateTotalDibayar(data);
        const isSudahLunasCicilan = totalPelunasan > 0 && totalDibayar >= totalPelunasan;
        const statusPencairanSimpanan = (data.statusPencairanSimpanan || '').trim();
        const statusKhusus = (data.statusKhusus || '').toUpperCase().replace(/ /g, '_');
        
        if (status === 'aktif' || status === 'active') {
            if (statusKhusus === 'MENUNGGU_PENCAIRAN' && statusPencairanSimpanan !== 'Dicairkan') {
                return 'menungguPencairan';
            }
            
            if (isSudahLunasCicilan) {
                if (statusPencairanSimpanan === 'Dicairkan') {
                    return 'lunas';
                } else {
                    return 'menungguPencairan';
                }
            } else {
                return 'aktif';
            }
        } else if (status === 'lunas') {
            if (statusPencairanSimpanan === 'Dicairkan') {
                return 'lunas';
            } else {
                return 'menungguPencairan';
            }
        } else if (status === 'menunggu pencairan') {
            return 'menungguPencairan';
        }
        
        return 'aktif';
    }
    
    // Case 1: Nasabah baru ditambahkan
    if (!before && after) {
        const category = getEffectiveCategory(after);
        if (category === 'ditolak') return delta;
        
        delta.nasabahChange = 1;
        const tanggalDaftar = after.tanggalDaftar || after.tanggalPengajuan || '';
        const totalPelunasan = after.totalPelunasan || 0;
        const totalDibayar = calculateTotalDibayar(after);
        
        if (category === 'aktif') {
            delta.aktifChange = 1;
            delta.pinjamanAktifChange = totalPelunasan;
            delta.piutangChange = Math.max(0, totalPelunasan - totalDibayar);
            delta.targetHariIniChange = calculateTargetHariIni(after);
            if (tanggalDaftar === today) delta.nasabahBaruHariIniChange = 1;
        } else if (category === 'lunas') {
            delta.lunasChange = 1;
        } else if (category === 'menungguPencairan') {
            delta.nasabahMenungguPencairanChange = 1;
        } else if (category === 'menunggu') {
            delta.menungguChange = 1;
        }
        
        // ✅ v8: TIDAK hitung pembayaranHariIni di sini!
        
        console.log(`📊 New nasabah: category=${category}`);
        return delta;
    }
    
    // Case 2: Nasabah dihapus
    if (before && !after) {
        const category = getEffectiveCategory(before);
        if (category === 'ditolak') return delta;
        
        delta.nasabahChange = -1;
        const totalPelunasan = before.totalPelunasan || 0;
        const totalDibayar = calculateTotalDibayar(before);
        
        if (category === 'aktif') {
            delta.aktifChange = -1;
            delta.pinjamanAktifChange = -totalPelunasan;
            delta.piutangChange = -Math.max(0, totalPelunasan - totalDibayar);
            delta.targetHariIniChange = -calculateTargetHariIni(before);
        } else if (category === 'lunas') {
            delta.lunasChange = -1;
        } else if (category === 'menungguPencairan') {
            delta.nasabahMenungguPencairanChange = -1;
        } else if (category === 'menunggu') {
            delta.menungguChange = -1;
        }
        
        // ✅ v8: TIDAK hitung pembayaranHariIni di sini!
        
        console.log(`📊 Deleted nasabah: category=${category}`);
        return delta;
    }
    
    // Case 3: Update data pelanggan
    if (before && after) {
        const beforeCategory = getEffectiveCategory(before);
        const afterCategory = getEffectiveCategory(after);
        
        const beforePelunasan = before.totalPelunasan || 0;
        const afterPelunasan = after.totalPelunasan || 0;
        const beforeDibayar = calculateTotalDibayar(before);
        const afterDibayar = calculateTotalDibayar(after);
        const tanggalDaftar = after.tanggalDaftar || after.tanggalPengajuan || '';
        
        console.log(`📊 Update: ${beforeCategory} → ${afterCategory}, dibayar: ${beforeDibayar} → ${afterDibayar}`);
        
        // Deteksi perubahan kategori
        if (beforeCategory !== afterCategory) {
            // Kurangi dari kategori sebelumnya
            if (beforeCategory === 'aktif') {
                delta.aktifChange -= 1;
                delta.pinjamanAktifChange -= beforePelunasan;
                delta.piutangChange -= Math.max(0, beforePelunasan - beforeDibayar);
                // Jika lunas HARI INI → target tetap ada untuk hari ini (buku: lunas hari ini masih dihitung)
                const tglLunasCicilanAfter = (after.tanggalLunasCicilan || '').trim();
                if (tglLunasCicilanAfter !== today) {
                    delta.targetHariIniChange -= calculateTargetHariIni(before);
                }
            } else if (beforeCategory === 'lunas') {
                delta.lunasChange -= 1;
            } else if (beforeCategory === 'menungguPencairan') {
                delta.nasabahMenungguPencairanChange -= 1;
            } else if (beforeCategory === 'menunggu') {
                delta.menungguChange -= 1;
            }
            
            // Tambah ke kategori sesudahnya
            if (afterCategory === 'aktif') {
                delta.aktifChange += 1;
                delta.pinjamanAktifChange += afterPelunasan;
                delta.piutangChange += Math.max(0, afterPelunasan - afterDibayar);
                delta.targetHariIniChange += calculateTargetHariIni(after);

                // Drop hari ini: cek tanggalPencairan untuk flow baru, fallback ke tanggalDaftar
                const tanggalPencairan = after.tanggalPencairan || '';
                if (beforeCategory === 'disetujui' && tanggalPencairan === today) {
                    delta.nasabahBaruHariIniChange = 1;
                } else if (beforeCategory === 'menunggu' && tanggalDaftar === today) {
                    // Backward compatibility: flow lama tanpa pencairan
                    delta.nasabahBaruHariIniChange = 1;
                }
            } else if (afterCategory === 'lunas') {
                delta.lunasChange += 1;
            } else if (afterCategory === 'menungguPencairan') {
                delta.nasabahMenungguPencairanChange += 1;
                if (beforeCategory === 'aktif' && adaPembayaranPadaTanggal(after, today)) {
                    delta.nasabahLunasHariIniChange = 1;
                }
            } else if (afterCategory === 'menunggu') {
                delta.menungguChange += 1;
            }
            
            console.log(`✅ Category changed: ${beforeCategory} → ${afterCategory}`);
        } else {
            // Kategori sama, cek perubahan piutang (tapi TANPA pembayaran - itu di processPembayaranBaru)
            if (afterCategory === 'aktif') {
                // Hanya update jika totalPelunasan berubah (misal edit pinjaman)
                if (beforePelunasan !== afterPelunasan) {
                    delta.pinjamanAktifChange = afterPelunasan - beforePelunasan;
                    
                    // Piutang berubah karena pelunasan berubah, bukan karena pembayaran
                    const beforePiutang = Math.max(0, beforePelunasan - beforeDibayar);
                    const afterPiutang = Math.max(0, afterPelunasan - afterDibayar);
                    delta.piutangChange = afterPiutang - beforePiutang;
                }
                
                delta.targetHariIniChange = calculateTargetHariIni(after) - calculateTargetHariIni(before);
            }
        }
        
        // =========================================================================
        // ✅ v8 FIX: TIDAK HITUNG pembayaranHariIniChange dan piutangChange dari pembayaran!
        // =========================================================================
        // Pembayaran SUDAH di-handle oleh:
        // - onPembayaranAdded.onCreate() → processPembayaranBaru()
        // - onSubPembayaranAdded.onCreate() → processPembayaranBaru()
        // =========================================================================
    }
    
    return delta;
}

// =========================================================================
// PROCESS PELANGGAN CHANGE
// =========================================================================
async function processPelangganChange(adminUid, beforeData, afterData) {
    console.log(`🔍 Processing pelanggan change for admin: ${adminUid}`);
    
    try {
        const delta = calculateDelta(beforeData, afterData);
        console.log(`📊 Delta:`, JSON.stringify(delta));
        
        const hasChanges = Object.values(delta).some(v => v !== 0);
        if (!hasChanges) {
            console.log(`⭐️ No significant changes, skipping`);
            return;
        }
        
        await incrementAdminSummary(adminUid, delta);
        
        const adminMeta = await db.ref(`metadata/admins/${adminUid}`).once('value');
        const cabangId = adminMeta.val()?.cabang;
        
        if (cabangId) {
            await incrementCabangSummary(cabangId, delta);
        }
        
        await incrementGlobalSummary(delta);
        
        console.log(`✅ All summaries updated (via processPelangganChange)`);
        
    } catch (error) {
        console.error(`❌ Error: ${error.message}`);
        throw error;
    }
}

// =========================================================================
// ✅ v8 FIX #3: PROCESS PEMBAYARAN BARU - SEKARANG UPDATE piutangChange!
// =========================================================================
// INI ADALAH SATU-SATUNYA TEMPAT yang update pembayaranHariIni dan piutang!
// =========================================================================
async function processPembayaranBaru(adminUid, pelangganId, pembayaran) {
    const jumlahPembayaran = pembayaran.jumlah || 0;
    
    console.log(`💰 Processing payment: Rp ${jumlahPembayaran} for ${pelangganId}`);
    
    try {
        if (jumlahPembayaran <= 0) {
            console.log(`⚠️ Skipping - jumlah pembayaran <= 0`);
            return;
        }
        
        // ✅ v8 FIX: Sekarang JUGA update piutangChange!
        // Saat pembayaran masuk, piutang berkurang
        const delta = {
            nasabahChange: 0, 
            aktifChange: 0, 
            lunasChange: 0, 
            menungguChange: 0,
            nasabahMenungguPencairanChange: 0, 
            pinjamanAktifChange: 0, 
            piutangChange: -jumlahPembayaran, // ✅ v8: KURANGI piutang!
            pembayaranHariIniChange: jumlahPembayaran, // ✅ TAMBAH pembayaran hari ini
            nasabahBaruHariIniChange: 0,
            nasabahLunasHariIniChange: 0, 
            targetHariIniChange: 0
        };
        
        console.log(`📊 Payment delta:`, JSON.stringify(delta));
        
        await incrementAdminSummary(adminUid, delta);
        
        const adminMeta = await db.ref(`metadata/admins/${adminUid}`).once('value');
        const cabangId = adminMeta.val()?.cabang;
        
        if (cabangId) {
            await incrementCabangSummary(cabangId, delta);
            console.log(`✅ Cabang ${cabangId} updated`);
        } else {
            console.warn(`⚠️ Cabang not found for admin ${adminUid}`);
        }
        
        await incrementGlobalSummary(delta);
        
        console.log(`✅ Payment processed: +Rp ${jumlahPembayaran} pembayaran, -Rp ${jumlahPembayaran} piutang`);
        
    } catch (error) {
        console.error(`❌ Error processPembayaranBaru: ${error.message}`);
        throw error;
    }
}

// =========================================================================
// NASABAH BARU
// =========================================================================
async function processNasabahBaru(adminUid, pelangganId, pelanggan) {
    console.log(`👤 New customer: ${pelanggan.namaPanggilan || pelanggan.namaKtp}`);
    
    try {
        const besarPinjaman = pelanggan.totalPelunasan || pelanggan.besarPinjaman || 0;
        
        const delta = {
            nasabahChange: 1, aktifChange: 1, lunasChange: 0, menungguChange: 0,
            nasabahMenungguPencairanChange: 0, pinjamanAktifChange: besarPinjaman,
            piutangChange: besarPinjaman, pembayaranHariIniChange: 0,
            nasabahBaruHariIniChange: 1, nasabahLunasHariIniChange: 0, targetHariIniChange: 0
        };
        
        await incrementAdminSummary(adminUid, delta);
        
        const adminMeta = await db.ref(`metadata/admins/${adminUid}`).once('value');
        const cabangId = adminMeta.val()?.cabang;
        if (cabangId) await incrementCabangSummary(cabangId, delta);
        
        await incrementGlobalSummary(delta);
        console.log(`✅ Nasabah baru processed`);
        
    } catch (error) {
        console.error(`❌ Error: ${error.message}`);
        throw error;
    }
}

// =========================================================================
// FULL RECALCULATE ADMIN SUMMARY
// =========================================================================
async function fullRecalculateAdminSummary(adminUid) {
    console.log(`🔄 Full recalculation for admin: ${adminUid}`);
    
    const today = getTodayIndonesia();
    const isHariLibur = isHoliday(today);
    
    const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
    
    let totalNasabah = 0, nasabahAktif = 0, nasabahLunas = 0, nasabahMenunggu = 0;
    let nasabahMenungguPencairan = 0, totalPinjamanAktif = 0, totalPiutang = 0;
    let targetHariIni = 0, nasabahBaruHariIni = 0, nasabahLunasHariIni = 0, pembayaranHariIni = 0;
    
    pelangganSnap.forEach(child => {
        const p = child.val();
        if (!p) return;
        
        const status = (p.status || '').toLowerCase();
        if (status === 'ditolak') return;
        
        totalNasabah++;
        
        const tanggalDaftar = p.tanggalDaftar || '';
        const tanggalPengajuan = p.tanggalPengajuan || '';
        const isBaruAtauTopUpHariIni = tanggalDaftar === today || tanggalPengajuan === today;
        const totalDibayar = calculateTotalDibayar(p);
        const totalPelunasan = p.totalPelunasan || 0;
        const isSudahLunas = totalPelunasan > 0 && totalDibayar >= totalPelunasan;
        
        const statusPencairanSimpanan = (p.statusPencairanSimpanan || '').trim();
        const statusKhusus = (p.statusKhusus || '').toUpperCase().replace(/ /g, '_');
        
        const isMenungguPencairanManual = statusKhusus === 'MENUNGGU_PENCAIRAN' && 
                                          statusPencairanSimpanan !== 'Dicairkan';
        const isStatusLunas = status === 'lunas';
        const isLunasOtomatis = (isSudahLunas || isStatusLunas) && statusPencairanSimpanan !== 'Dicairkan';
        
        if (isMenungguPencairanManual || isLunasOtomatis) {
            nasabahMenungguPencairan++;
        }
        
        // Hitung pembayaran hari ini
        if (p.pembayaranList) {
            const pembayaranList = Array.isArray(p.pembayaranList)
                ? p.pembayaranList : Object.values(p.pembayaranList || {});
            
            pembayaranList.forEach(pay => {
                if (pay.tanggal === today) pembayaranHariIni += pay.jumlah || 0;
                if (pay.subPembayaran) {
                    const subList = Array.isArray(pay.subPembayaran)
                        ? pay.subPembayaran : Object.values(pay.subPembayaran || {});
                    subList.forEach(sub => {
                        if (sub.tanggal === today) pembayaranHariIni += sub.jumlah || 0;
                    });
                }
            });
        }
        
        switch (status) {
            case 'aktif':
            case 'active':
                // ✅ PERBAIKAN: Hitung nasabahBaruHariIni di LUAR kondisi isSudahLunas
                // Agar nasabah top up yang tanggalDaftar = today tetap terhitung
                if (isBaruAtauTopUpHariIni) nasabahBaruHariIni++;
                
                if (isSudahLunas) {
                    if (statusPencairanSimpanan === 'Dicairkan') {
                        nasabahLunas++;
                        if (adaPembayaranPadaTanggal(p, today)) nasabahLunasHariIni++;
                    }
                } else {
                    if (!isMenungguPencairanManual) {
                        nasabahAktif++;
                        totalPinjamanAktif += totalPelunasan;
                        totalPiutang += Math.max(0, totalPelunasan - totalDibayar);
                        
                        if (!isHariLibur) {
                            // ✅ BARU: Exclude nasabah macet (> 4 bulan)
                            const tglAcuan = (p.tanggalPencairan || '').trim()
                                || (p.tanggalPengajuan || '').trim()
                                || (p.tanggalDaftar || '').trim();
                            if (!isOverThreeMonths(tglAcuan)) {
                                // Exclude nasabah cair hari ini (konsisten Android)
                                const tglCair = (p.tanggalPencairan || '').trim();
                                if (tglCair !== today) {
                                    // Flat 3% — konsisten dengan Android
                                    targetHariIni += Math.floor((p.besarPinjaman || 0) * 3 / 100);
                                }
                            }
                        }
                    }
                }
                break;
                
            case 'lunas':
                if (statusPencairanSimpanan === 'Dicairkan') {
                    nasabahLunas++;
                    if (adaPembayaranPadaTanggal(p, today)) nasabahLunasHariIni++;
                }
                // Lunas HARI INI → tetap masuk target hari ini (sama dengan buku fisik)
                if (!isHariLibur) {
                    const tglLunasCicilan = (p.tanggalLunasCicilan || '').trim();
                    if (tglLunasCicilan === today) {
                        const tglAcuan = (p.tanggalPencairan || '').trim()
                            || (p.tanggalPengajuan || '').trim()
                            || (p.tanggalDaftar || '').trim();
                        if (!isOverThreeMonths(tglAcuan)) {
                            targetHariIni += Math.floor((p.besarPinjaman || 0) * 3 / 100);
                        }
                    }
                }
                break;
                
            case 'menunggu approval':
                nasabahMenunggu++;
                if (isBaruAtauTopUpHariIni) nasabahBaruHariIni++;
                break;

            case 'disetujui':
                // Status transisional - tidak dihitung di counter manapun
                // Tetap dihitung di totalNasabah (sudah di-increment di line 692)
                break;

            case 'tidak aktif':
                // Pinjaman dibatalkan - tidak dihitung di counter manapun
                break;
        }
    });
    
    const summaryData = {
        totalNasabah, nasabahAktif, nasabahLunas, nasabahMenunggu, nasabahMenungguPencairan,
        nasabahBaruHariIni, nasabahLunasHariIni, targetHariIni, totalPinjamanAktif,
        totalPiutang, pembayaranHariIni, lastUpdated: admin.database.ServerValue.TIMESTAMP
    };
    
    await db.ref(`summary/perAdmin/${adminUid}`).set(summaryData);
    
    console.log(`✅ Recalculation done: ${totalNasabah} nasabah, ${nasabahAktif} aktif, menungguPencairan: ${nasabahMenungguPencairan}, lunas: ${nasabahLunas}, pembayaranHariIni: ${pembayaranHariIni}, totalPiutang: ${totalPiutang}`);
    
    return summaryData;
}

// =========================================================================
// AGGREGATE CABANG SUMMARY
// =========================================================================
async function aggregateCabangSummary(cabangId) {
    console.log(`📊 Aggregating cabang summary: ${cabangId}`);
    
    try {
        const adminsSnap = await db.ref('metadata/admins')
            .orderByChild('cabang').equalTo(cabangId).once('value');
        
        let totals = {
            adminCount: 0, totalNasabah: 0, nasabahAktif: 0, nasabahLunas: 0,
            nasabahMenunggu: 0, nasabahMenungguPencairan: 0, nasabahBaruHariIni: 0,
            nasabahLunasHariIni: 0, targetHariIni: 0, totalPinjamanAktif: 0,
            totalPiutang: 0, pembayaranHariIni: 0
        };
        
        const adminPromises = [];
        adminsSnap.forEach(adminSnap => {
            const adminData = adminSnap.val();
            if (adminData.role === 'admin') {
                totals.adminCount++;
                adminPromises.push(db.ref(`summary/perAdmin/${adminSnap.key}`).once('value'));
            }
        });
        
        const adminSummaries = await Promise.all(adminPromises);
        adminSummaries.forEach(summarySnap => {
            const summary = summarySnap.val();
            if (summary) {
                totals.totalNasabah += summary.totalNasabah || 0;
                totals.nasabahAktif += summary.nasabahAktif || 0;
                totals.nasabahLunas += summary.nasabahLunas || 0;
                totals.nasabahMenunggu += summary.nasabahMenunggu || 0;
                totals.nasabahMenungguPencairan += summary.nasabahMenungguPencairan || 0;
                totals.nasabahBaruHariIni += summary.nasabahBaruHariIni || 0;
                totals.nasabahLunasHariIni += summary.nasabahLunasHariIni || 0;
                totals.targetHariIni += summary.targetHariIni || 0;
                totals.totalPinjamanAktif += summary.totalPinjamanAktif || 0;
                totals.totalPiutang += summary.totalPiutang || 0;
                totals.pembayaranHariIni += summary.pembayaranHariIni || 0;
            }
        });
        
        totals.lastUpdated = admin.database.ServerValue.TIMESTAMP;
        await db.ref(`summary/perCabang/${cabangId}`).set(totals);
        
        console.log(`✅ Cabang ${cabangId} aggregated: ${totals.totalNasabah} nasabah, pembayaranHariIni: ${totals.pembayaranHariIni}`);
        return totals;
        
    } catch (error) {
        console.error(`❌ Error: ${error.message}`);
        throw error;
    }
}

// =========================================================================
// AGGREGATE GLOBAL SUMMARY
// =========================================================================
async function aggregateGlobalSummary() {
    console.log(`🌍 Aggregating global summary`);
    
    try {
        const cabangSnap = await db.ref('summary/perCabang').once('value');
        
        let totals = {
            totalNasabah: 0, nasabahAktif: 0, nasabahLunas: 0, nasabahMenunggu: 0,
            nasabahMenungguPencairan: 0, nasabahBaruHariIni: 0, nasabahLunasHariIni: 0,
            targetHariIni: 0, totalPinjamanAktif: 0, totalPiutang: 0, pembayaranHariIni: 0
        };
        
        cabangSnap.forEach(child => {
            const summary = child.val();
            if (summary) {
                totals.totalNasabah += summary.totalNasabah || 0;
                totals.nasabahAktif += summary.nasabahAktif || 0;
                totals.nasabahLunas += summary.nasabahLunas || 0;
                totals.nasabahMenunggu += summary.nasabahMenunggu || 0;
                totals.nasabahMenungguPencairan += summary.nasabahMenungguPencairan || 0;
                totals.nasabahBaruHariIni += summary.nasabahBaruHariIni || 0;
                totals.nasabahLunasHariIni += summary.nasabahLunasHariIni || 0;
                totals.targetHariIni += summary.targetHariIni || 0;
                totals.totalPinjamanAktif += summary.totalPinjamanAktif || 0;
                totals.totalPiutang += summary.totalPiutang || 0;
                totals.pembayaranHariIni += summary.pembayaranHariIni || 0;
            }
        });
        
        totals.lastUpdated = admin.database.ServerValue.TIMESTAMP;
        await db.ref('summary/global').set(totals);
        
        console.log(`✅ Global aggregated: ${totals.totalNasabah} nasabah, pembayaranHariIni: ${totals.pembayaranHariIni}`);
        return totals;
        
    } catch (error) {
        console.error(`❌ Error: ${error.message}`);
        throw error;
    }
}

// =========================================================================
// EXPORT
// =========================================================================
module.exports = {
    getTodayIndonesia,
    isHoliday,
    calculateTotalDibayar,
    adaPembayaranPadaTanggal,
    hitungPembayaranPadaTanggal,
    calculateTargetHariIni,
    isOverThreeMonths,
    isOverFourMonths,
    ensureAdminSummaryExists,
    ensureCabangSummaryExists,
    ensureGlobalSummaryExists,
    incrementAdminSummary,
    incrementCabangSummary,
    incrementGlobalSummary,
    calculateDelta,
    processPelangganChange,
    processNasabahBaru,
    processPembayaranBaru,
    fullRecalculateAdminSummary,
    aggregateCabangSummary,
    aggregateGlobalSummary
};