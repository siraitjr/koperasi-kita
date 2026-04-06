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
        if (!p) return;
        // ✅ FIX: Hanya skip entry 'Bunga...', JANGAN skip entry tanpa tanggal
        if (p.tanggal && p.tanggal.startsWith('Bunga')) return;
        total += p.jumlah || 0;
        if (p.subPembayaran) {
            const subList = Array.isArray(p.subPembayaran)
                ? p.subPembayaran
                : Object.values(p.subPembayaran || {});
            subList.forEach(sub => {
                total += sub.jumlah || 0;
            });
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
        if (!p) return;
        // ✅ FIX: Hanya skip entry 'Bunga...', entry tanpa tanggal tetap dihitung
        if (p.tanggal && p.tanggal.startsWith('Bunga')) return;
        {
            const tgl = p.tanggal || 'Tanpa Tanggal';
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
            console.log(`[getBukuPokok] VERSION=2026-03-30-v5-tabs, statusFilter=${statusFilter}, cabangId=${cabangId}`);

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
            } else if (user.role === 'kasir_wilayah' || user.role === 'sekretaris') {
                // Kasir wilayah & Sekretaris sees all (seperti pengawas/koordinator)
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
                // Pengawas/Koordinator/Sekretaris sees all, but must specify cabang or admin
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

            // 6. Fetch pelanggan data per admin — ✅ OPTIMASI: Parallel reads
            let nasabahList = [];
            const adminNames = {};

            // Batch read: admin metadata + pelanggan data + summary + riwayat — semua paralel
            const [adminMetaResults, pelangganResults, summaryResults, riwayatResults] = await Promise.all([
                Promise.all(adminUids.map(aUid => db.ref(`metadata/admins/${aUid}`).once('value'))),
                Promise.all(adminUids.map(aUid => db.ref(`pelanggan/${aUid}`).once('value'))),
                Promise.all(adminUids.map(aUid => db.ref(`summary/perAdmin/${aUid}`).once('value'))),
                Promise.all(adminUids.map(aUid => db.ref(`riwayat_pinjaman/${aUid}`).once('value')))
            ]);

            // Process admin names
            adminUids.forEach((aUid, i) => {
                const adminData = adminMetaResults[i].val();
                adminNames[aUid] = adminData ? (adminData.name || adminData.email || aUid) : aUid;
            });

            // Build riwayat lookup: { pelangganId: [ {pinjamanKe, ...}, ... ] }
            const riwayatLookup = {};
            // Juga simpan data arsip lengkap untuk nasabah yang sudah dihapus dari pelanggan
            // Key: pelangganId, Value: { adminUid, pinjaman terakhir (pinjamanKe terbesar) }
            const riwayatArsipLengkap = {};
            adminUids.forEach((aUid, i) => {
                const riwayatData = riwayatResults[i].val();
                if (!riwayatData) return;
                Object.entries(riwayatData).forEach(([pId, pinjamanMap]) => {
                    if (!riwayatLookup[pId]) riwayatLookup[pId] = [];
                    Object.entries(pinjamanMap).forEach(([pinjamanKe, data]) => {
                        const rTotalDibayar = calculateTotalDibayar(data.pembayaranList);
                        const rTotalPelunasan = data.totalPelunasan || 0;
                        riwayatLookup[pId].push({
                            pinjamanKe: parseInt(pinjamanKe),
                            besarPinjaman: data.besarPinjaman || 0,
                            totalPelunasan: rTotalPelunasan,
                            totalDibayar: rTotalDibayar,
                            sisaUtang: Math.max(0, rTotalPelunasan - rTotalDibayar),
                            tenor: data.tenor || 0,
                            tanggalPengajuan: data.tanggalPengajuan || '',
                            tanggalPencairan: data.tanggalPencairan || '',
                            tanggalLunasCicilan: data.tanggalLunasCicilan || '',
                            status: data.status || '',
                            pembayaran: extractPembayaranPerTanggal(data.pembayaranList)
                        });

                        // Simpan data arsip lengkap — ambil pinjaman terakhir (pinjamanKe terbesar)
                        const pk = parseInt(pinjamanKe);
                        if (!riwayatArsipLengkap[pId] || pk > riwayatArsipLengkap[pId].pinjamanKe) {
                            riwayatArsipLengkap[pId] = {
                                adminUid: aUid,
                                pinjamanKe: pk,
                                data: data
                            };
                        }
                    });
                    // Sort by pinjamanKe ascending
                    riwayatLookup[pId].sort((a, b) => a.pinjamanKe - b.pinjamanKe);
                });
            });

            // Process pelanggan data
            adminUids.forEach((aUid, i) => {
                const pelangganData = pelangganResults[i].val();
                if (!pelangganData) return;

                Object.entries(pelangganData).forEach(([pId, p]) => {
                    const pStatus = (p.status || '').toLowerCase();
                    const pStatusKhusus = p.statusKhusus || '';

                    // Skip menunggu approval dan ditolak untuk semua filter
                    if (pStatus === 'menunggu approval' || pStatus === 'ditolak') return;

                    // Hitung sisa utang (diperlukan untuk filter aktif & nasabah_lunas)
                    const totalDibayar = calculateTotalDibayar(p.pembayaranList);
                    const totalPelunasan = p.totalPelunasan || 0;
                    const sisaUtang = Math.max(0, totalPelunasan - totalDibayar);

                    // Tentukan kategori nasabah (eksklusif, tidak boleh overlap)
                    // isSisaTabungan: cerminkan logika Android — hanya jika simpanan belum dicairkan
                    const isSisaTabungan = pStatusKhusus === 'MENUNGGU_PENCAIRAN' && (p.statusPencairanSimpanan || '') !== 'Dicairkan';
                    const isNasabahLunas = !isSisaTabungan && sisaUtang <= 0 && totalPelunasan > 0;
                    const isAktif = !isSisaTabungan && !isNasabahLunas && (pStatus === 'aktif' || pStatus === 'disetujui');

                    // Filter berdasarkan tab yang dipilih
                    if (statusFilter === 'aktif' && !isAktif) return;
                    if (statusFilter === 'sisa_tabungan' && !isSisaTabungan) return;
                    if (statusFilter === 'nasabah_lunas' && !isNasabahLunas) return;
                    // Legacy filters (backward compatibility)
                    if (statusFilter === 'lunas' && pStatus !== 'lunas') return;
                    if (statusFilter === 'semua') { /* show all except menunggu approval/ditolak */ }
                    const pembayaranPerTanggal = extractPembayaranPerTanggal(p.pembayaranList);

                    // Riwayat pinjaman lama (jika ada)
                    const riwayat = riwayatLookup[pId] || [];

                    // Hitung total sisa utang dari pinjaman lama yang belum lunas
                    const sisaUtangLama = riwayat.reduce((sum, r) => sum + (r.sisaUtang || 0), 0);

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
                        adminUid: aUid,
                        adminName: adminNames[aUid],
                        cabangId: p.cabangId || '',
                        wilayah: p.wilayah || '',
                        simpanan: p.simpanan || 0,
                        totalDiterima: p.totalDiterima || 0,
                        pembayaran: pembayaranPerTanggal,
                        // Foto nasabah & KTP (sudah ada di data pelanggan, tanpa tambahan RTDB read)
                        fotoKtpUrl: p.fotoKtpUrl || '',
                        fotoKtpSuamiUrl: p.fotoKtpSuamiUrl || '',
                        fotoKtpIstriUrl: p.fotoKtpIstriUrl || '',
                        fotoNasabahUrl: p.fotoNasabahUrl || '',
                        // Riwayat pinjaman lama
                        sisaUtangLama: sisaUtangLama,
                        sisaUtangLamaSebelumTopUp: p.sisaUtangLamaSebelumTopUp || 0,
                        riwayatPinjaman: riwayat
                    });
                });
            });

            // 6a-b. Untuk tab "Nasabah Lunas": tambahkan nasabah dari riwayat_pinjaman
            // yang sudah dihapus dari pelanggan (setelah cairkan simpanan).
            // Ini agar pembayaran pelunasan via tabungan tetap tercatat permanen di buku pokok,
            // seperti pada buku pokok fisik dimana nama dicoret tapi pembayaran tetap ditulis.
            if (statusFilter === 'nasabah_lunas') {
                const activePelangganIds = new Set(nasabahList.map(n => n.id));
                Object.entries(riwayatArsipLengkap).forEach(([pId, arsip]) => {
                    // Skip jika nasabah masih ada di pelanggan aktif (sudah diproses di atas)
                    if (activePelangganIds.has(pId)) return;

                    const d = arsip.data;
                    const aUid = arsip.adminUid;
                    const rTotalDibayar = calculateTotalDibayar(d.pembayaranList);
                    const rTotalPelunasan = d.totalPelunasan || 0;
                    const pembayaranPerTanggal = extractPembayaranPerTanggal(d.pembayaranList);

                    // Riwayat pinjaman lama (exclude pinjaman terakhir yg sudah jadi entry utama)
                    const riwayat = (riwayatLookup[pId] || []).filter(r => r.pinjamanKe !== arsip.pinjamanKe);
                    const sisaUtangLama = riwayat.reduce((sum, r) => sum + (r.sisaUtang || 0), 0);

                    nasabahList.push({
                        id: pId,
                        namaKtp: d.namaKtp || '',
                        namaPanggilan: d.namaPanggilan || '',
                        nik: '',
                        nomorAnggota: d.nomorAnggota || '',
                        pinjamanKe: d.pinjamanKe || arsip.pinjamanKe,
                        besarPinjaman: d.besarPinjaman || 0,
                        totalPelunasan: rTotalPelunasan,
                        totalDibayar: rTotalDibayar,
                        sisaUtang: Math.max(0, rTotalPelunasan - rTotalDibayar),
                        tenor: d.tenor || 0,
                        status: d.status || '',
                        statusKhusus: d.statusKhusus || '',
                        tanggalDaftar: d.tanggalPengajuan || '',
                        tanggalPencairan: d.tanggalPencairan || '',
                        tanggalPengajuan: d.tanggalPengajuan || '',
                        adminUid: aUid,
                        adminName: adminNames[aUid] || aUid,
                        cabangId: '',
                        wilayah: '',
                        simpanan: d.simpanan || 0,
                        totalDiterima: d.totalDiterima || 0,
                        pembayaran: pembayaranPerTanggal,
                        fotoKtpUrl: '',
                        fotoKtpSuamiUrl: '',
                        fotoKtpIstriUrl: '',
                        fotoNasabahUrl: '',
                        sisaUtangLama: sisaUtangLama,
                        sisaUtangLamaSebelumTopUp: 0,
                        riwayatPinjaman: riwayat,
                        // Penanda bahwa ini nasabah dari arsip (sudah dicairkan tabungannya)
                        dariArsip: true
                    });
                });
            }

            // 6b. Hitung target harian + pembayaranHariIni dari summary nodes (sudah dibaca paralel di atas)
            let totalTargetHarian = 0;
            let totalPembayaranHariIni = 0;
            summaryResults.forEach(snap => {
                const summaryData = snap.val() || {};
                totalTargetHarian += summaryData.targetHariIni || 0;
                totalPembayaranHariIni += summaryData.pembayaranHariIni || 0;
            });

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
            // kasir_wilayah, sekretaris, pengawas & koordinator see all

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
            const totalPelunasanSisaUtang = payments
                .filter(p => p.jenis === 'pelunasan_sisa_utang')
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
                        totalPelunasanSisaUtang: totalPelunasanSisaUtang,
                        grandTotal: totalCicilan + totalTambahBayar + totalPelunasanSisaUtang
                    }
                }
            });

        } catch (error) {
            console.error('getPembayaranHariIni error:', error);
            res.status(500).json({ success: false, error: 'Terjadi kesalahan server' });
        }
    });
