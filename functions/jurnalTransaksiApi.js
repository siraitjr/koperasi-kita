// functions/jurnalTransaksiApi.js
// =========================================================================
// JURNAL TRANSAKSI API - Endpoint untuk Web Pembukuan
// =========================================================================
//
// Endpoints:
// 1. getJurnalTransaksi - Baca jurnal per cabang/bulan
// 2. backfillJurnalTransaksi - Migrasi data existing ke jurnal (one-time)
//
// Autentikasi: Firebase ID Token (Bearer token di header)
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');
const db = admin.database();

const { writeJurnalEntry, calculateTotalDibayar, getYearMonthWIB } = require('./jurnalTransaksi');
const { getTodayIndonesia } = require('./summaryHelpers');

// =========================================================================
// HELPER: Verify Firebase ID Token (sama dengan bukuPokokApi)
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
// HELPER: Check user role
// =========================================================================
async function getUserRole(uid) {
    const adminSnap = await db.ref(`metadata/admins/${uid}`).once('value');
    if (!adminSnap.exists()) return null;
    const data = adminSnap.val();
    return {
        uid,
        role: data.role || 'admin',
        cabang: data.cabang || null,
        name: data.name || data.email || '',
        email: data.email || ''
    };
}

// =========================================================================
// HELPER: CORS
// =========================================================================
function setCorsHeaders(res) {
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    res.set('Access-Control-Max-Age', '3600');
}

// =========================================================================
// API: GET JURNAL TRANSAKSI
// =========================================================================
// Query params:
//   - cabangId (required)
//   - bulan (optional, format "2026-03", default: bulan ini)
//   - tipe (optional, filter: 'pembayaran_cicilan', 'tambah_bayar', dll)
//   - adminUid (optional, filter per admin)
//
// Response: Array of jurnal entries sorted by timestamp
// =========================================================================
exports.getJurnalTransaksi = functions
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

            // 3. Only pimpinan, pengawas, koordinator, kasir_wilayah, sekretaris can view
            const allowedRoles = ['pimpinan', 'pengawas', 'koordinator', 'kasir_wilayah', 'kasir_unit', 'sekretaris'];
            if (!allowedRoles.includes(user.role)) {
                res.status(403).json({ success: false, error: 'Tidak memiliki akses ke jurnal transaksi' });
                return;
            }

            // 4. Parse params
            const { cabangId, bulan, tipe, adminUid } = req.query;

            if (!cabangId) {
                res.status(400).json({ success: false, error: 'cabangId diperlukan' });
                return;
            }

            const targetBulan = bulan || getYearMonthWIB();

            // 5. Read jurnal
            const jurnalSnap = await db.ref(`jurnal_transaksi/${cabangId}/${targetBulan}`).once('value');
            const jurnalData = jurnalSnap.val() || {};

            // 6. Transform & filter
            let entries = Object.entries(jurnalData).map(([id, entry]) => ({
                id,
                ...entry
            }));

            // Filter by tipe
            if (tipe) {
                entries = entries.filter(e => e.tipe === tipe);
            }

            // Filter by adminUid
            if (adminUid) {
                entries = entries.filter(e => e.adminUid === adminUid);
            }

            // Sort by timestamp descending (terbaru di atas)
            entries.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));

            // 7. Hitung ringkasan
            const ringkasan = {
                totalPembayaranCicilan: 0,
                totalTambahBayar: 0,
                totalPencairan: 0,
                totalPelunasanSisaUtang: 0,
                jumlahTransaksi: entries.length,
                jumlahNasabahLunas: 0
            };

            entries.forEach(e => {
                switch (e.tipe) {
                    case 'pembayaran_cicilan':
                        ringkasan.totalPembayaranCicilan += e.jumlah || 0;
                        break;
                    case 'tambah_bayar':
                        ringkasan.totalTambahBayar += e.jumlah || 0;
                        break;
                    case 'pencairan_pinjaman':
                        ringkasan.totalPencairan += e.jumlah || 0;
                        break;
                    case 'pelunasan_sisa_utang':
                        ringkasan.totalPelunasanSisaUtang += e.jumlah || 0;
                        break;
                    case 'lunas':
                        ringkasan.jumlahNasabahLunas += 1;
                        break;
                }
            });

            res.status(200).json({
                success: true,
                data: {
                    cabangId,
                    bulan: targetBulan,
                    entries,
                    ringkasan,
                    today: getTodayIndonesia()
                }
            });

        } catch (error) {
            console.error('getJurnalTransaksi error:', error);
            res.status(500).json({ success: false, error: 'Terjadi kesalahan server' });
        }
    });

// =========================================================================
// BACKFILL: Migrasi data pembayaranList existing ke jurnal_transaksi
// =========================================================================
// HTTP Function (one-time use)
// Membaca semua pelanggan dan menulis ulang pembayaran mereka ke jurnal
//
// PENTING: Ini aman dipanggil berulang kali — setiap panggilan push()
// entry baru, sehingga jika dijalankan 2x akan ada duplikat. Tapi karena
// ini one-time backfill, cukup jalankan sekali saja.
//
// Query param: ?cabangId=xxx (wajib, backfill per cabang)
// =========================================================================
exports.backfillJurnalTransaksi = functions
    .region('asia-southeast1')
    .runWith({ timeoutSeconds: 540, memory: '1GB' })
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);

        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }

        try {
            // Verify auth
            const auth = await verifyAuth(req);
            if (!auth.valid) {
                res.status(401).json({ success: false, error: auth.error });
                return;
            }

            const user = await getUserRole(auth.uid);
            if (!user || !['pengawas', 'koordinator', 'pimpinan'].includes(user.role)) {
                res.status(403).json({ success: false, error: 'Hanya pengawas/koordinator/pimpinan yang bisa backfill' });
                return;
            }

            const { cabangId } = req.query;
            if (!cabangId) {
                res.status(400).json({ success: false, error: 'cabangId diperlukan' });
                return;
            }

            // Get admin list for this cabang
            const cabangSnap = await db.ref(`metadata/cabang/${cabangId}/adminList`).once('value');
            const adminUids = cabangSnap.val() || [];

            if (adminUids.length === 0) {
                res.status(200).json({ success: true, message: 'Tidak ada admin di cabang ini', stats: { totalEntries: 0 } });
                return;
            }

            // Read admin metadata in parallel
            const adminMetaResults = await Promise.all(
                adminUids.map(uid => db.ref(`metadata/admins/${uid}`).once('value'))
            );

            const adminDataMap = {};
            adminUids.forEach((uid, i) => {
                adminDataMap[uid] = adminMetaResults[i].val() || {};
            });

            // Read all pelanggan data in parallel
            const pelangganResults = await Promise.all(
                adminUids.map(uid => db.ref(`pelanggan/${uid}`).once('value'))
            );

            let totalEntries = 0;
            let totalNasabah = 0;
            const batchWrites = {};

            adminUids.forEach((adminUid, i) => {
                const pelangganData = pelangganResults[i].val();
                if (!pelangganData) return;

                const adminData = adminDataMap[adminUid] || {};

                Object.entries(pelangganData).forEach(([pelangganId, p]) => {
                    if (!p || !p.pembayaranList) return;

                    totalNasabah++;

                    const pembayaranList = Array.isArray(p.pembayaranList)
                        ? p.pembayaranList
                        : Object.values(p.pembayaranList || {});

                    let runningTotal = 0;
                    const totalPelunasan = p.totalPelunasan || 0;

                    pembayaranList.forEach((pay, payIndex) => {
                        if (!pay) return;
                        if (pay.tanggal && pay.tanggal.startsWith('Bunga')) return;

                        // Parse tanggal untuk menentukan yearMonth
                        const yearMonth = parseToYearMonth(pay.tanggal);

                        runningTotal += pay.jumlah || 0;

                        // Entry cicilan utama
                        const entryKey = db.ref(`jurnal_transaksi/${cabangId}/${yearMonth}`).push().key;
                        const path = `jurnal_transaksi/${cabangId}/${yearMonth}/${entryKey}`;

                        batchWrites[path] = {
                            tipe: 'pembayaran_cicilan',
                            pelangganId,
                            namaPelanggan: p.namaPanggilan || '',
                            namaKtp: p.namaKtp || '',
                            adminUid,
                            adminName: adminData.name || adminData.email || '',
                            jumlah: pay.jumlah || 0,
                            tanggal: pay.tanggal || '',
                            pinjamanKe: p.pinjamanKe || 1,
                            sisaUtangSetelah: Math.max(0, totalPelunasan - runningTotal),
                            totalPelunasan,
                            totalDibayar: runningTotal,
                            keterangan: `Backfill - Cicilan ke-${payIndex + 1}`,
                            timestamp: admin.database.ServerValue.TIMESTAMP,
                            createdAt: new Date().toISOString(),
                            isBackfill: true
                        };
                        totalEntries++;

                        // Sub-pembayaran
                        if (pay.subPembayaran) {
                            const subList = Array.isArray(pay.subPembayaran)
                                ? pay.subPembayaran
                                : Object.values(pay.subPembayaran || {});

                            subList.forEach(sub => {
                                if (!sub) return;
                                runningTotal += sub.jumlah || 0;

                                const subYearMonth = parseToYearMonth(sub.tanggal || pay.tanggal);
                                const subKey = db.ref(`jurnal_transaksi/${cabangId}/${subYearMonth}`).push().key;
                                const subPath = `jurnal_transaksi/${cabangId}/${subYearMonth}/${subKey}`;

                                batchWrites[subPath] = {
                                    tipe: 'tambah_bayar',
                                    pelangganId,
                                    namaPelanggan: p.namaPanggilan || '',
                                    namaKtp: p.namaKtp || '',
                                    adminUid,
                                    adminName: adminData.name || adminData.email || '',
                                    jumlah: sub.jumlah || 0,
                                    tanggal: sub.tanggal || pay.tanggal || '',
                                    pinjamanKe: p.pinjamanKe || 1,
                                    sisaUtangSetelah: Math.max(0, totalPelunasan - runningTotal),
                                    totalPelunasan,
                                    totalDibayar: runningTotal,
                                    keterangan: 'Backfill - Tambah bayar',
                                    timestamp: admin.database.ServerValue.TIMESTAMP,
                                    createdAt: new Date().toISOString(),
                                    isBackfill: true
                                };
                                totalEntries++;
                            });
                        }
                    });

                    // Jika nasabah sudah lunas, catat marker lunas
                    const totalDibayarFinal = calculateTotalDibayar(p.pembayaranList);
                    if (totalDibayarFinal >= totalPelunasan && totalPelunasan > 0) {
                        const lunasYearMonth = parseToYearMonth(p.tanggalLunasCicilan || getTodayIndonesia());
                        const lunasKey = db.ref(`jurnal_transaksi/${cabangId}/${lunasYearMonth}`).push().key;
                        const lunasPath = `jurnal_transaksi/${cabangId}/${lunasYearMonth}/${lunasKey}`;

                        batchWrites[lunasPath] = {
                            tipe: 'lunas',
                            pelangganId,
                            namaPelanggan: p.namaPanggilan || '',
                            namaKtp: p.namaKtp || '',
                            adminUid,
                            adminName: adminData.name || adminData.email || '',
                            jumlah: 0,
                            tanggal: p.tanggalLunasCicilan || '',
                            pinjamanKe: p.pinjamanKe || 1,
                            sisaUtangSetelah: 0,
                            totalPelunasan,
                            totalDibayar: totalDibayarFinal,
                            keterangan: `Backfill - Lunas pinjaman ke-${p.pinjamanKe || 1}`,
                            timestamp: admin.database.ServerValue.TIMESTAMP,
                            createdAt: new Date().toISOString(),
                            isBackfill: true
                        };
                        totalEntries++;
                    }
                });
            });

            // Write in batches (Firebase multi-path update max ~500 per batch)
            const batchKeys = Object.keys(batchWrites);
            const BATCH_SIZE = 400;

            for (let i = 0; i < batchKeys.length; i += BATCH_SIZE) {
                const batch = {};
                batchKeys.slice(i, i + BATCH_SIZE).forEach(key => {
                    batch[key] = batchWrites[key];
                });
                await db.ref().update(batch);
                console.log(`[BACKFILL] Batch ${Math.floor(i / BATCH_SIZE) + 1}: ${Object.keys(batch).length} entries written`);
            }

            res.status(200).json({
                success: true,
                message: `Backfill selesai untuk cabang ${cabangId}`,
                stats: {
                    totalNasabah,
                    totalEntries,
                    adminCount: adminUids.length
                }
            });

        } catch (error) {
            console.error('backfillJurnalTransaksi error:', error);
            res.status(500).json({ success: false, error: error.message });
        }
    });

// =========================================================================
// HELPER: Parse tanggal Indonesia ("25 Mar 2026") ke yearMonth ("2026-03")
// =========================================================================
const BULAN_MAP = {
    'Jan': '01', 'Feb': '02', 'Mar': '03', 'Apr': '04',
    'Mei': '05', 'Jun': '06', 'Jul': '07', 'Agu': '08',
    'Sep': '09', 'Okt': '10', 'Nov': '11', 'Des': '12'
};

function parseToYearMonth(tanggal) {
    if (!tanggal || typeof tanggal !== 'string') {
        return getYearMonthWIB(); // fallback ke bulan ini
    }

    const parts = tanggal.trim().split(' ');
    if (parts.length !== 3) {
        return getYearMonthWIB();
    }

    const month = BULAN_MAP[parts[1]];
    const year = parts[2];

    if (!month || !year) {
        return getYearMonthWIB();
    }

    return `${year}-${month}`;
}
