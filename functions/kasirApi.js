// =========================================================================
// KASIR API - Cloud Function untuk Web Kasir
// =========================================================================
//
// Endpoints:
// 1. getKasirSummary - Data awal saat login (info user, cabang, ringkasan)
// 2. getKasirEntries - Jurnal kasir per bulan per cabang
// 3. addKasirEntry - Tambah transaksi kasir (kasir_unit only)
// 4. deleteKasirEntry - Hapus transaksi kasir (kasir_unit only)
//
// Autentikasi: Firebase ID Token (Bearer token di header)
// Optimasi: 1 read per bulan per cabang, summary node untuk dashboard
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

// =========================================================================
// CONSTANTS
// =========================================================================
const JENIS_VALID = ['uang_kas', 'penggajian', 'transport', 'suntikan_dana', 'pinjaman_kas', 'sp'];
const ARAH_VALID = ['masuk', 'keluar'];

const JENIS_LABELS = {
    uang_kas: 'Uang Kas',
    penggajian: 'BU/Penggajian',
    transport: 'Transport',
    suntikan_dana: 'Suntikan Dana',
    pinjaman_kas: 'Pinjaman Kas',
    sp: 'SP'
};

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
// HELPER: CORS headers
// =========================================================================
function setCorsHeaders(res) {
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    res.set('Access-Control-Max-Age', '3600');
}

// =========================================================================
// HELPER: Get tanggal format Indonesia
// Format: "03 Mar 2026" — WAJIB SAMA dengan format di Android app
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

function getCurrentMonthKey() {
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(now.getTime() + wibOffset);
    const year = wibDate.getUTCFullYear();
    const month = (wibDate.getUTCMonth() + 1).toString().padStart(2, '0');
    return `${year}-${month}`;
}

// =========================================================================
// HELPER: Update kasir_summary secara atomic via transaction
// =========================================================================
async function updateKasirSummary(cabangId, bulan, jenis, arah, jumlah, isDelete) {
    const summaryRef = db.ref(`kasir_summary/${cabangId}/${bulan}`);
    const delta = isDelete ? -jumlah : jumlah;

    await summaryRef.transaction((current) => {
        if (!current) {
            current = {
                totalMasuk: 0,
                totalKeluar: 0,
                saldo: 0,
                perJenis: {},
                lastUpdated: Date.now()
            };
        }

        if (arah === 'masuk') {
            current.totalMasuk = (current.totalMasuk || 0) + delta;
            current.saldo = (current.saldo || 0) + delta;
        } else {
            current.totalKeluar = (current.totalKeluar || 0) + delta;
            current.saldo = (current.saldo || 0) - delta;
        }

        if (!current.perJenis) current.perJenis = {};
        if (!current.perJenis[jenis]) current.perJenis[jenis] = {};
        current.perJenis[jenis][arah] = (current.perJenis[jenis][arah] || 0) + delta;

        current.lastUpdated = Date.now();

        return current;
    });
}

// =========================================================================
// API 1: GET KASIR SUMMARY (Dashboard data saat login)
// =========================================================================
exports.getKasirSummary = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);

        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }

        try {
            const authResult = await verifyAuth(req);
            if (!authResult.valid) {
                res.status(401).json({ success: false, error: authResult.error });
                return;
            }

            const user = await getUserRole(authResult.uid);
            if (!user) {
                res.status(403).json({ success: false, error: 'User tidak terdaftar' });
                return;
            }

            if (user.role !== 'kasir_unit' && user.role !== 'kasir_wilayah') {
                res.status(403).json({ success: false, error: 'Akses hanya untuk Kasir' });
                return;
            }

            // Get metadata
            const metadataSnap = await db.ref('metadata').once('value');
            const metadata = metadataSnap.val() || {};
            const cabangData = metadata.cabang || {};
            const adminsData = metadata.admins || {};



            // Build cabang list
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

            // Filter berdasarkan role
            let visibleCabang = cabangList;
            if (user.role === 'kasir_unit') {
                visibleCabang = cabangList.filter(c => c.id === user.cabang);
            }

            // Get kasir summary bulan ini
            const currentMonth = getCurrentMonthKey();
            const summaryData = {};

            for (const cab of visibleCabang) {
                const summarySnap = await db.ref(`kasir_summary/${cab.id}/${currentMonth}`).once('value');
                summaryData[cab.id] = summarySnap.val() || {
                    totalMasuk: 0,
                    totalKeluar: 0,
                    saldo: 0,
                    perJenis: {}
                };
            }

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
                    summary: summaryData,
                    today: getTodayIndonesia(),
                    currentMonth: currentMonth,
                    jenisLabels: JENIS_LABELS
                }
            });

        } catch (error) {
            console.error('getKasirSummary error:', error);
            res.status(500).json({ success: false, error: 'Terjadi kesalahan server' });
        }
    });

// =========================================================================
// API 2: GET KASIR ENTRIES (Jurnal kasir per bulan)
// =========================================================================
exports.getKasirEntries = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);

        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }

        try {
            const authResult = await verifyAuth(req);
            if (!authResult.valid) {
                res.status(401).json({ success: false, error: authResult.error });
                return;
            }

            const user = await getUserRole(authResult.uid);
            if (!user) {
                res.status(403).json({ success: false, error: 'User tidak terdaftar' });
                return;
            }

            if (user.role !== 'kasir_unit' && user.role !== 'kasir_wilayah') {
                res.status(403).json({ success: false, error: 'Akses hanya untuk Kasir' });
                return;
            }

            const { cabangId, bulan } = req.query;
            if (!cabangId || !bulan) {
                res.status(400).json({ success: false, error: 'cabangId dan bulan diperlukan' });
                return;
            }

            if (user.role === 'kasir_unit' && user.cabang !== cabangId) {
                res.status(403).json({ success: false, error: 'Tidak memiliki akses ke cabang ini' });
                return;
            }

            if (!/^\d{4}-\d{2}$/.test(bulan)) {
                res.status(400).json({ success: false, error: 'Format bulan harus YYYY-MM' });
                return;
            }

            // 1 READ per bulan per cabang
            const entriesSnap = await db.ref(`kasir_entries/${cabangId}/${bulan}`).once('value');
            const entriesData = entriesSnap.val() || {};

            const entries = Object.entries(entriesData).map(([id, entry]) => ({
                id,
                ...entry,
                jenisLabel: JENIS_LABELS[entry.jenis] || entry.jenis
            }));

            // Sort by createdAt descending
            entries.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));

            // Get summary
            const summarySnap = await db.ref(`kasir_summary/${cabangId}/${bulan}`).once('value');
            const summary = summarySnap.val() || {
                totalMasuk: 0,
                totalKeluar: 0,
                saldo: 0,
                perJenis: {}
            };

            res.status(200).json({
                success: true,
                data: {
                    cabangId,
                    bulan,
                    entries,
                    summary,
                    totalEntries: entries.length,
                    jenisLabels: JENIS_LABELS
                }
            });

        } catch (error) {
            console.error('getKasirEntries error:', error);
            res.status(500).json({ success: false, error: 'Terjadi kesalahan server' });
        }
    });

// =========================================================================
// API 3: ADD KASIR ENTRY (POST - kasir_unit only)
// =========================================================================
exports.addKasirEntry = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);

        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }

        if (req.method !== 'POST') {
            res.status(405).json({ success: false, error: 'Method harus POST' });
            return;
        }

        try {
            const authResult = await verifyAuth(req);
            if (!authResult.valid) {
                res.status(401).json({ success: false, error: authResult.error });
                return;
            }

            const user = await getUserRole(authResult.uid);
            if (!user) {
                res.status(403).json({ success: false, error: 'User tidak terdaftar' });
                return;
            }

            if (user.role !== 'kasir_unit') {
                res.status(403).json({ success: false, error: 'Hanya Kasir Unit yang dapat menambah transaksi' });
                return;
            }

            // Parse body
            const { jenis, arah, jumlah, keterangan, tanggal, targetAdminUid, targetAdminName } = req.body || {};

            // Validasi
            if (!jenis || !JENIS_VALID.includes(jenis)) {
                res.status(400).json({ success: false, error: `Jenis tidak valid. Pilih: ${JENIS_VALID.join(', ')}` });
                return;
            }
            if (!arah || !ARAH_VALID.includes(arah)) {
                res.status(400).json({ success: false, error: 'Arah harus "masuk" atau "keluar"' });
                return;
            }
            if (!jumlah || typeof jumlah !== 'number' || jumlah <= 0) {
                res.status(400).json({ success: false, error: 'Jumlah harus angka lebih dari 0' });
                return;
            }
            if (jumlah > 999999999) {
                res.status(400).json({ success: false, error: 'Jumlah terlalu besar' });
                return;
            }

            // Tentukan tanggal & bulan key
            const entryTanggal = tanggal || getTodayIndonesia();
            const bulanMap = {
                'Jan': '01', 'Feb': '02', 'Mar': '03', 'Apr': '04',
                'Mei': '05', 'Jun': '06', 'Jul': '07', 'Agu': '08',
                'Sep': '09', 'Okt': '10', 'Nov': '11', 'Des': '12'
            };
            const tglParts = entryTanggal.split(' ');
            if (tglParts.length !== 3 || !bulanMap[tglParts[1]]) {
                res.status(400).json({ success: false, error: 'Format tanggal tidak valid (harus: DD MMM YYYY)' });
                return;
            }
            const bulanKey = `${tglParts[2]}-${bulanMap[tglParts[1]]}`;

            // Cabang dari user
            const cabangId = user.cabang;
            if (!cabangId) {
                res.status(400).json({ success: false, error: 'User tidak memiliki cabang' });
                return;
            }

            // Buat entry
            const newEntry = {
                jenis,
                arah,
                jumlah,
                keterangan: keterangan || '',
                tanggal: entryTanggal,
                createdBy: user.uid,
                createdByName: user.name,
                createdAt: admin.database.ServerValue.TIMESTAMP
            };

            // Simpan target admin untuk uang_kas (dipakai LaporanHarianScreen)
            if (jenis === 'uang_kas' && targetAdminUid) {
                newEntry.targetAdminUid = targetAdminUid;
                newEntry.targetAdminName = targetAdminName || '';
            }

            // Push ke RTDB
            const entryRef = await db.ref(`kasir_entries/${cabangId}/${bulanKey}`).push(newEntry);

            // Update summary
            await updateKasirSummary(cabangId, bulanKey, jenis, arah, jumlah, false);

            res.status(201).json({
                success: true,
                data: {
                    id: entryRef.key,
                    ...newEntry,
                    createdAt: Date.now(),
                    jenisLabel: JENIS_LABELS[jenis],
                    cabangId,
                    bulan: bulanKey
                }
            });

        } catch (error) {
            console.error('addKasirEntry error:', error);
            res.status(500).json({ success: false, error: 'Terjadi kesalahan server' });
        }
    });

// =========================================================================
// API 4: DELETE KASIR ENTRY (kasir_unit only, own entries only)
// =========================================================================
exports.deleteKasirEntry = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);

        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }

        try {
            const authResult = await verifyAuth(req);
            if (!authResult.valid) {
                res.status(401).json({ success: false, error: authResult.error });
                return;
            }

            const user = await getUserRole(authResult.uid);
            if (!user) {
                res.status(403).json({ success: false, error: 'User tidak terdaftar' });
                return;
            }

            if (user.role !== 'kasir_unit') {
                res.status(403).json({ success: false, error: 'Hanya Kasir Unit yang dapat menghapus transaksi' });
                return;
            }

            const { cabangId, bulan, entryId } = req.query;
            if (!cabangId || !bulan || !entryId) {
                res.status(400).json({ success: false, error: 'cabangId, bulan, dan entryId diperlukan' });
                return;
            }

            if (user.cabang !== cabangId) {
                res.status(403).json({ success: false, error: 'Tidak memiliki akses ke cabang ini' });
                return;
            }

            // Ambil entry
            const entryRef = db.ref(`kasir_entries/${cabangId}/${bulan}/${entryId}`);
            const entrySnap = await entryRef.once('value');

            if (!entrySnap.exists()) {
                res.status(404).json({ success: false, error: 'Transaksi tidak ditemukan' });
                return;
            }

            const entry = entrySnap.val();

            if (entry.createdBy !== user.uid) {
                res.status(403).json({ success: false, error: 'Hanya bisa menghapus transaksi yang Anda buat' });
                return;
            }

            // Hapus entry
            await entryRef.remove();

            // Update summary (kurangi)
            await updateKasirSummary(cabangId, bulan, entry.jenis, entry.arah, entry.jumlah, true);

            res.status(200).json({
                success: true,
                message: 'Transaksi berhasil dihapus',
                data: { id: entryId, jenis: entry.jenis, jumlah: entry.jumlah }
            });

        } catch (error) {
            console.error('deleteKasirEntry error:', error);
            res.status(500).json({ success: false, error: 'Terjadi kesalahan server' });
        }
    });
