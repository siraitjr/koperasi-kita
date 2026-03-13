// =========================================================================
// BUKU POKOK API - Cloud Function untuk Web Pembukuan
// =========================================================================
//
// Endpoints:
// 1. getBukuPokok - Data lengkap buku pokok per admin/cabang
// 2. getBukuPokokSummary - Ringkasan per cabang (untuk dashboard)
//
// Autentikasi: Firebase ID Token (Bearer token di header)
// Optimasi: Minimal RTDB reads, response di-compress
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

// =========================================================================
// HELPER: Verify Firebase ID Token
// =========================================================================
async function verifyAuth(req) {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return { valid: false, error: 'Token tidak ditemukan' };
    }

    try {
        const idToken = authHeader.split('Bearer ')[1];
        const decoded = await admin.auth().verifyIdToken(idToken);
        return { valid: true, uid: decoded.uid, email: decoded.email };
    } catch (error) {
        return { valid: false, error: 'Token tidak valid atau expired' };
    }
}

// =========================================================================
// HELPER: Check user role & permissions
// =========================================================================
async function getUserRole(uid) {
    const adminSnap = await db.ref(`metadata/admins/${uid}`).once('value');
    if (!adminSnap.exists()) {
        return null;
    }
    const data = adminSnap.val();
    return {
        uid: uid,
        role: data.role || 'admin',
        cabang: data.cabang || null,
        name: data.name || data.email || '',
        email: data.email || ''
    };
}

// =========================================================================
// HELPER: Hitung total dibayar dari pembayaranList
// =========================================================================
function calculateTotalDibayar(pembayaranList) {
    let total = 0;
    if (!pembayaranList) return 0;

    const list = Array.isArray(pembayaranList)
        ? pembayaranList
        : Object.values(pembayaranList || {});

    list.forEach(p => {
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
// HELPER: Extract pembayaran per tanggal
// =========================================================================
function extractPembayaranPerTanggal(pembayaranList) {
    const perTanggal = {};
    if (!pembayaranList) return perTanggal;

    const list = Array.isArray(pembayaranList)
        ? pembayaranList
        : Object.values(pembayaranList || {});

    list.forEach((p, index) => {
        if (p && p.tanggal && !p.tanggal.startsWith('Bunga')) {
            const tgl = p.tanggal;
            if (!perTanggal[tgl]) {
                perTanggal[tgl] = { total: 0, entries: [] };
            }
            perTanggal[tgl].total += p.jumlah || 0;
            perTanggal[tgl].entries.push({
                jumlah: p.jumlah || 0,
                index: index,
                type: 'cicilan'
            });

            // Sub pembayaran
            if (p.subPembayaran) {
                const subList = Array.isArray(p.subPembayaran)
                    ? p.subPembayaran
                    : Object.values(p.subPembayaran || {});
                subList.forEach(sub => {
                    const subTgl = sub.tanggal || tgl;
                    if (!perTanggal[subTgl]) {
                        perTanggal[subTgl] = { total: 0, entries: [] };
                    }
                    perTanggal[subTgl].total += sub.jumlah || 0;
                    perTanggal[subTgl].entries.push({
                        jumlah: sub.jumlah || 0,
                        keterangan: sub.keterangan || 'Tambah Bayar',
                        type: 'sub'
                    });
                });
            }
        }
    });

    return perTanggal;
}

// =========================================================================
// HELPER: Get tanggal format Indonesia (sama dengan summaryHelpers.js)
// Format: "27 Feb 2025" — WAJIB SAMA dengan format di Android app
// =========================================================================
const BULAN_INDO = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun',
                    'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

function getTodayIndonesia() {
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(now.getTime() + wibOffset);
    const day = wibDate.getUTCDate().toString().padStart(2, '0');
    const month = BULAN_INDO[wibDate.getUTCMonth()];
    const year = wibDate.getUTCFullYear();
    return `${day} ${month} ${year}`;
}

// =========================================================================
// HELPER: Generate hari kerja berurutan (Senin-Sabtu, skip Minggu)
// Format: "27 Feb 2025" — sama dengan format pembayaran di RTDB
// =========================================================================
function generateHariKerja(jumlahHari) {
    const dates = [];
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(now.getTime() + wibOffset);

    let current = new Date(wibDate);
    while (dates.length < jumlahHari) {
        // Skip Minggu (0 = Sunday)
        if (current.getUTCDay() !== 0) {
            const dd = current.getUTCDate().toString().padStart(2, '0');
            const mmm = BULAN_INDO[current.getUTCMonth()];
            const yyyy = current.getUTCFullYear();
            dates.push(`${dd} ${mmm} ${yyyy}`);
        }
        current = new Date(current.getTime() - 24 * 60 * 60 * 1000);
    }
    return dates; // Urut dari hari ini mundur ke belakang
}

// =========================================================================
// HELPER: CORS headers
// =========================================================================
function setCorsHeaders(res) {
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    res.set('Access-Control-Max-Age', '3600');
}

// =========================================================================
// API 1: GET BUKU POKOK
// =========================================================================
// Query params:
//   - cabangId (required for pimpinan, optional for pengawas/koordinator)
//   - adminUid (optional, filter per admin)
//   - status (optional, default 'aktif' = Aktif + Disetujui)
//
// Response: Array of nasabah with pembayaran per tanggal
// =========================================================================
exports.getBukuPokok = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);

        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }

        try {
            // 1. Verify auth
            const auth = await verifyAuth(req);
            if (!auth.valid) {
                res.status(401).json({ success: false, error: auth.error });
                return;
            }

            // 2. Get user role
            const user = await getUserRole(auth.uid);
            if (!user) {
                res.status(403).json({ success: false, error: 'User tidak terdaftar' });
                return;
            }

            // 3. Parse parameters
            const { cabangId, adminUid, status } = req.query;
            const statusFilter = (status || 'aktif').toLowerCase();

            // 4. Determine which admins to fetch
            let adminUids = [];

            if (adminUid) {
                // Specific admin requested
                adminUids = [adminUid];
            } else if (user.role === 'admin') {
                // Admin can only see their own
                adminUids = [user.uid];
            } else if (user.role === 'pimpinan') {
                // Pimpinan sees all admins in their cabang
                const targetCabang = cabangId || user.cabang;
                if (!targetCabang) {
                    res.status(400).json({ success: false, error: 'cabangId diperlukan' });
                    return;
                }
                const cabangSnap = await db.ref(`metadata/cabang/${targetCabang}/adminList`).once('value');
                adminUids = cabangSnap.val() || [];
                } else if (user.role === 'kasir_unit') {
                // Kasir unit sees all admins in their cabang (seperti pimpinan)
                const targetCabang = cabangId || user.cabang;
                if (!targetCabang) {
                    res.status(400).json({ success: false, error: 'cabangId diperlukan' });
                    return;
                }
                const cabangSnap = await db.ref(`metadata/cabang/${targetCabang}/adminList`).once('value');
                adminUids = cabangSnap.val() || [];
            } else if (user.role === 'kasir_wilayah') {
                // Kasir wilayah sees all (seperti pengawas/koordinator)
                if (cabangId) {
                    const cabangSnap = await db.ref(`metadata/cabang/${cabangId}/adminList`).once('value');
                    adminUids = cabangSnap.val() || [];
                } else {
                    const cabangSnap = await db.ref('metadata/cabang').once('value');
                    const cabangData = cabangSnap.val() || {};
                    const cabangList = Object.entries(cabangData).map(([id, data]) => ({
                        id,
                        name: data.name || id,
                        adminCount: (data.adminList || []).length
                    }));
                    res.status(200).json({ 
                        success: true, 
                        type: 'cabang_selection',
                        data: cabangList 
                    });
                    return;
                }
            } else if (user.role === 'pengawas' || user.role === 'koordinator') {
                // Pengawas/Koordinator sees all, but must specify cabang or admin
                if (cabangId) {
                    const cabangSnap = await db.ref(`metadata/cabang/${cabangId}/adminList`).once('value');
                    adminUids = cabangSnap.val() || [];
                } else {
                    // Return all cabang list for selection
                    const cabangSnap = await db.ref('metadata/cabang').once('value');
                    const cabangData = cabangSnap.val() || {};
                    const cabangList = Object.entries(cabangData).map(([id, data]) => ({
                        id,
                        name: data.name || id,
                        adminCount: (data.adminList || []).length
                    }));
                    res.status(200).json({ 
                        success: true, 
                        type: 'cabang_selection',
                        data: cabangList 
                    });
                    return;
                }
            }

            // 5. Generate tanggal hari kerja berurutan (seperti buku pokok fisik)
            const hariKerja = generateHariKerja(60); // 60 hari kerja ke belakang

            // 6. Fetch pelanggan data per admin (1 read per admin node)
            const todayStr = getTodayIndonesia();
            let nasabahList = [];
            const adminNames = {};

            for (const aUid of adminUids) {
                // Get admin name
                const adminMeta = await db.ref(`metadata/admins/${aUid}`).once('value');
                const adminData = adminMeta.val();
                adminNames[aUid] = adminData ? (adminData.name || adminData.email || aUid) : aUid;

                // ✅ OPTIMASI: Satu kali read per admin node
                const [pelangganSnap, pinjamanHistorySnap] = await Promise.all([
                    db.ref(`pelanggan/${aUid}`).once('value'),
                    db.ref(`pinjamanHistory/${aUid}`).once('value'),
                ]);
                const pelangganData = pelangganSnap.val();
                const pinjamanHistoryData = pinjamanHistorySnap.val() || {};

                if (!pelangganData) continue;

                Object.entries(pelangganData).forEach(([pId, p]) => {
                    // Filter by status
                    const pStatus = (p.status || '').toLowerCase();
                    if (statusFilter === 'aktif' && pStatus !== 'aktif' && pStatus !== 'disetujui') {
                        // Exception: nasabah lunas HARI INI tetap masuk (keluar besok)
                        if (pStatus !== 'lunas' || (p.tanggalLunasCicilan || '').trim() !== todayStr) return;
                    }
                    if (statusFilter === 'lunas' && pStatus !== 'lunas') return;
                    if (statusFilter === 'semua' && pStatus === 'menunggu approval' && pStatus === 'ditolak') return;

                    const totalDibayar = calculateTotalDibayar(p.pembayaranList);
                    const totalPelunasan = p.totalPelunasan || 0;
                    const sisaUtang = Math.max(0, totalPelunasan - totalDibayar);
                    const pembayaranPerTanggal = extractPembayaranPerTanggal(p.pembayaranList);

                    nasabahList.push({
                        id: pId,
                        namaKtp: p.namaKtp || '',
                        namaPanggilan: p.namaPanggilan || '',
                        nik: p.nik || '',
                        nomorAnggota: p.nomorAnggota || '',
                        pinjamanKe: p.pinjamanKe || 1,
                        besarPinjaman: p.besarPinjaman || 0,
                        totalPelunasan: totalPelunasan,
                        totalDibayar: totalDibayar,
                        sisaUtang: sisaUtang,
                        tenor: p.tenor || 0,
                        status: p.status || '',
                        statusKhusus: p.statusKhusus || '',
                        tanggalDaftar: p.tanggalDaftar || p.tanggalPengajuan || '',
                        tanggalPencairan: p.tanggalPencairan || '',
                        tanggalPengajuan: p.tanggalPengajuan || '',
                        tanggalLunasCicilan: p.tanggalLunasCicilan || '',
                        adminUid: aUid,
                        adminName: adminNames[aUid],
                        cabangId: p.cabangId || '',
                        wilayah: p.wilayah || '',
                        fotoKtpUrl: p.fotoKtpUrl || '',
                        fotoNasabahUrl: p.fotoNasabahUrl || '',
                        simpanan: p.simpanan || 0,
                        totalDiterima: p.totalDiterima || 0,
                        pembayaran: pembayaranPerTanggal,
                        pinjamanHistory: pinjamanHistoryData[pId] || null,
                    });
                });
            }

            // 6b. Hitung target harian + pembayaranHariIni dari summary nodes
            let totalTargetHarian = 0;
            let totalPembayaranHariIni = 0;
            for (const aUid of adminUids) {
                const summarySnap = await db.ref(`summary/perAdmin/${aUid}`).once('value');
                const summaryData = summarySnap.val() || {};
                totalTargetHarian += summaryData.targetHariIni || 0;
                totalPembayaranHariIni += summaryData.pembayaranHariIni || 0;
            }

            // 6c. Apply Android-style filters (hanya untuk status 'aktif')
            // Konsisten dengan RingkasanDashboardScreen.kt
            if (statusFilter === 'aktif') {
                const nowWib = new Date(new Date().getTime() + 7 * 60 * 60 * 1000);
                const threeMonthsAgo = new Date(nowWib.getUTCFullYear(), nowWib.getUTCMonth() - 3, 1);

                const parseTglIndo = (dateStr) => {
                    if (!dateStr) return null;
                    const BLN = {'Jan':0,'Feb':1,'Mar':2,'Apr':3,'Mei':4,'Jun':5,'Jul':6,'Agu':7,'Sep':8,'Okt':9,'Nov':10,'Des':11};
                    const parts = dateStr.trim().split(' ');
                    if (parts.length !== 3) return null;
                    const m = BLN[parts[1]];
                    if (m === undefined) return null;
                    return new Date(parseInt(parts[2]), m, parseInt(parts[0]));
                };

                nasabahList = nasabahList.filter(n => {
                    // Exclude MENUNGGU_PENCAIRAN
                    const sk = (n.statusKhusus || '').toUpperCase().replace(/ /g, '_');
                    if (sk === 'MENUNGGU_PENCAIRAN') return false;

                    // Exclude > 3 bulan
                    const tglAcuan = (n.tanggalPencairan || '').trim() || (n.tanggalPengajuan || '').trim() || (n.tanggalDaftar || '').trim();
                    const acuanDate = parseTglIndo(tglAcuan);
                    if (acuanDate && acuanDate < threeMonthsAgo) return false;

                    // Exclude cair hari ini
                    const tglCair = (n.tanggalPencairan || '').trim();
                    if (tglCair === todayStr) return false;

                    // Exclude sudah lunas cicilan — kecuali lunas HARI INI (keluar besok)
                    if (n.totalPelunasan > 0 && n.sisaUtang <= 0 && n.tanggalLunasCicilan !== todayStr) return false;

                    return true;
                });
            }

            // 7. Sort nasabah by adminName, then nomorAnggota
            nasabahList.sort((a, b) => {
                const adminCompare = a.adminName.localeCompare(b.adminName);
                if (adminCompare !== 0) return adminCompare;
                return (a.nomorAnggota || '').localeCompare(b.nomorAnggota || '');
            });

            res.status(200).json({
                success: true,
                type: 'buku_pokok',
                data: {
                    nasabah: nasabahList,
                    tanggalList: hariKerja,
                    adminNames: adminNames,
                    today: getTodayIndonesia(),
                    totalNasabah: nasabahList.length,
                    totalSisaUtang: nasabahList.reduce((sum, n) => sum + n.sisaUtang, 0),
                    totalPinjaman: nasabahList.reduce((sum, n) => sum + (n.besarPinjaman || 0), 0),
                    pembayaranHariIni: totalPembayaranHariIni,
                    targetHarianHariIni: totalTargetHarian
                }
            });

        } catch (error) {
            console.error('getBukuPokok error:', error);
            res.status(500).json({ success: false, error: 'Terjadi kesalahan server' });
        }
    });

// =========================================================================
// API 2: GET BUKU POKOK SUMMARY (Dashboard)
// =========================================================================
// Lightweight endpoint for dashboard stats
// Uses summary nodes to minimize RTDB reads
// =========================================================================
exports.getBukuPokokSummary = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);

        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }

        try {
            const auth = await verifyAuth(req);
            if (!auth.valid) {
                res.status(401).json({ success: false, error: auth.error });
                return;
            }

            const user = await getUserRole(auth.uid);
            if (!user) {
                res.status(403).json({ success: false, error: 'User tidak terdaftar' });
                return;
            }

            // Get metadata for cabang list
            const metadataSnap = await db.ref('metadata').once('value');
            const metadata = metadataSnap.val() || {};
            const cabangData = metadata.cabang || {};
            const adminsData = metadata.admins || {};



            const cabangList = [];

            for (const [cabangId, cabang] of Object.entries(cabangData)) {
                const adminList = cabang.adminList || [];
                const admins = adminList.map(aUid => ({
                    uid: aUid,
                    name: adminsData[aUid]?.name || adminsData[aUid]?.email || aUid,
                    email: adminsData[aUid]?.email || ''
                }));

                cabangList.push({
                    id: cabangId,
                    name: cabang.name || cabangId,
                    pimpinanUid: cabang.pimpinanUid || '',
                    pimpinanName: adminsData[cabang.pimpinanUid]?.name || '',
                    admins: admins
                });
            }

            // Return data based on role
            let visibleCabang = cabangList;
            if (user.role === 'admin') {
                visibleCabang = cabangList.filter(c => 
                    c.admins.some(a => a.uid === user.uid)
                );
            } else if (user.role === 'pimpinan') {
                visibleCabang = cabangList.filter(c => c.pimpinanUid === user.uid);
            } else if (user.role === 'kasir_unit') {
                visibleCabang = cabangList.filter(c => c.id === user.cabang);
            }
            // kasir_wilayah, pengawas & koordinator see all

            res.status(200).json({
                success: true,
                data: {
                    user: {
                        uid: user.uid,
                        name: user.name,
                        role: user.role,
                        cabang: user.cabang
                    },
                    cabangList: visibleCabang,
                    today: getTodayIndonesia()
                }
            });

        } catch (error) {
            console.error('getBukuPokokSummary error:', error);
            res.status(500).json({ success: false, error: 'Terjadi kesalahan server' });
        }
    });

// =========================================================================
// API 3: GET PEMBAYARAN HARI INI (Quick view)
// =========================================================================
// Reads from pembayaran_harian node (already optimized in existing system)
// =========================================================================
exports.getPembayaranHariIni = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);

        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }

        try {
            const auth = await verifyAuth(req);
            if (!auth.valid) {
                res.status(401).json({ success: false, error: auth.error });
                return;
            }

            const user = await getUserRole(auth.uid);
            if (!user) {
                res.status(403).json({ success: false, error: 'User tidak terdaftar' });
                return;
            }

            const { cabangId, tanggal } = req.query;
            const targetDate = tanggal || getTodayIndonesia();

            if (!cabangId) {
                res.status(400).json({ success: false, error: 'cabangId diperlukan' });
                return;
            }

            // ✅ Reads from pembayaran_harian (sudah ada di sistem)
            const harianSnap = await db.ref(`pembayaran_harian/${cabangId}/${targetDate}`).once('value');
            const harianData = harianSnap.val() || {};

            const payments = Object.values(harianData).map(p => ({
                pelangganId: p.pelangganId || '',
                namaPanggilan: p.namaPanggilan || '',
                namaKtp: p.namaKtp || '',
                adminName: p.adminName || '',
                adminUid: p.adminUid || '',
                jumlah: p.jumlah || 0,
                jenis: p.jenis || 'cicilan',
                tanggal: p.tanggal || targetDate,
                timestamp: p.timestamp || 0
            }));

            // Sort by timestamp desc
            payments.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));

            const totalCicilan = payments
                .filter(p => p.jenis === 'cicilan')
                .reduce((sum, p) => sum + p.jumlah, 0);
            const totalTambahBayar = payments
                .filter(p => p.jenis === 'tambah_bayar')
                .reduce((sum, p) => sum + p.jumlah, 0);

            res.status(200).json({
                success: true,
                data: {
                    tanggal: targetDate,
                    cabangId: cabangId,
                    payments: payments,
                    summary: {
                        totalTransaksi: payments.length,
                        totalCicilan: totalCicilan,
                        totalTambahBayar: totalTambahBayar,
                        grandTotal: totalCicilan + totalTambahBayar
                    }
                }
            });

        } catch (error) {
            console.error('getPembayaranHariIni error:', error);
            res.status(500).json({ success: false, error: 'Terjadi kesalahan server' });
        }
    });