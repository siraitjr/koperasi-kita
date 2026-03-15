// =========================================================================
// REKAP SNAPSHOT - Simpan & Baca rekap harian per cabang
// =========================================================================
//
// Endpoints:
// 1. saveRekapSnapshot (POST) - Terima computed snapshot dari frontend, simpan ke RTDB
//    Node: rekap_harian/{cabangId}/{YYYY-MM-DD}/
//    Idempotent: jika hari ini sudah tersimpan, skip (tidak overwrite).
//
// 2. getRekapHarian (GET) - Baca N snapshot terakhir per cabang
//
// Biaya RTDB:
//   - Write: 1x per cabang per hari (~2-3KB) — sangat murah
//   - Read:  baca node kecil rekap_harian, bukan seluruh nasabah
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

// -------------------------------------------------------------------------
// CORS helper
// -------------------------------------------------------------------------
function setCorsHeaders(res) {
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    res.set('Access-Control-Max-Age', '3600');
}

// -------------------------------------------------------------------------
// Auth helper
// -------------------------------------------------------------------------
async function verifyAuth(req) {
    const h = req.headers.authorization;
    if (!h || !h.startsWith('Bearer ')) return { valid: false };
    try {
        const decoded = await admin.auth().verifyIdToken(h.split('Bearer ')[1]);
        return { valid: true, uid: decoded.uid };
    } catch {
        return { valid: false };
    }
}

// -------------------------------------------------------------------------
// Konversi "15 Mar 2026" → "2026-03-15" (RTDB key)
// -------------------------------------------------------------------------
function tglToDateKey(tgl) {
    const BLN = {
        Jan: '01', Feb: '02', Mar: '03', Apr: '04', Mei: '05', Jun: '06',
        Jul: '07', Agu: '08', Sep: '09', Okt: '10', Nov: '11', Des: '12'
    };
    const p = (tgl || '').trim().split(' ');
    if (p.length !== 3) return null;
    const m = BLN[p[1]];
    if (!m) return null;
    return `${p[2]}-${m}-${p[0].padStart(2, '0')}`;
}

// =========================================================================
// ENDPOINT 1: saveRekapSnapshot (POST)
// Body: { cabangId, tanggal, snapshot: { rows, totals } }
//
// - Hanya menyimpan jika hari itu belum pernah disimpan (idempotent)
// - Data dikirim dari frontend setelah selesai menghitung rekapRows
// =========================================================================
exports.saveRekapSnapshot = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);
        if (req.method === 'OPTIONS') { res.status(204).send(''); return; }

        try {
            const auth = await verifyAuth(req);
            if (!auth.valid) {
                res.status(401).json({ success: false, error: 'Unauthorized' });
                return;
            }

            // Role check
            const userSnap = await db.ref(`metadata/admins/${auth.uid}`).once('value');
            const user = userSnap.val();
            const ALLOWED_ROLES = ['kasir_unit', 'kasir_wilayah', 'pimpinan', 'koordinator', 'pengawas', 'sekretaris'];
            if (!user || !ALLOWED_ROLES.includes(user.role)) {
                res.status(403).json({ success: false, error: 'Akses ditolak' });
                return;
            }

            const { cabangId, tanggal, snapshot } = req.body || {};
            if (!cabangId || !tanggal || !snapshot) {
                res.status(400).json({ success: false, error: 'cabangId, tanggal, snapshot wajib diisi' });
                return;
            }

            const dateKey = tglToDateKey(tanggal);
            if (!dateKey) {
                res.status(400).json({ success: false, error: 'Format tanggal tidak valid (contoh: "15 Mar 2026")' });
                return;
            }

            // Idempotent: jika sudah ada, kembalikan data yang tersimpan
            const existSnap = await db.ref(`rekap_harian/${cabangId}/${dateKey}`).once('value');
            if (existSnap.exists()) {
                res.status(200).json({ success: true, alreadySaved: true });
                return;
            }

            // Simpan snapshot kompak
            await db.ref(`rekap_harian/${cabangId}/${dateKey}`).set({
                tgl: tanggal,
                savedAt: Date.now(),
                savedBy: auth.uid,
                rows: snapshot.rows || [],
                totals: snapshot.totals || {}
            });

            res.status(200).json({ success: true, alreadySaved: false });

        } catch (err) {
            console.error('saveRekapSnapshot error:', err);
            res.status(500).json({ success: false, error: err.message });
        }
    });


// =========================================================================
// ENDPOINT 2: getRekapHarian (GET)
// Query: { cabangId, limit? }
//
// Membaca N snapshot terakhir dari rekap_harian/{cabangId}/
// Diurutkan dari terbaru ke terlama.
// =========================================================================
exports.getRekapHarian = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        setCorsHeaders(res);
        if (req.method === 'OPTIONS') { res.status(204).send(''); return; }

        try {
            const auth = await verifyAuth(req);
            if (!auth.valid) {
                res.status(401).json({ success: false, error: 'Unauthorized' });
                return;
            }

            const { cabangId, limit: limitStr } = req.query;
            if (!cabangId) {
                res.status(400).json({ success: false, error: 'cabangId diperlukan' });
                return;
            }

            const limit = Math.min(parseInt(limitStr) || 7, 30);

            // Baca rekap_harian/{cabangId}/, ambil N terbaru (key YYYY-MM-DD, sort ascending → limitToLast)
            const snap = await db.ref(`rekap_harian/${cabangId}`)
                .orderByKey()
                .limitToLast(limit)
                .once('value');

            const data = snap.val() || {};

            // Sort terbaru dulu
            const sorted = Object.entries(data)
                .sort(([a], [b]) => b.localeCompare(a))
                .map(([dateKey, val]) => ({ dateKey, ...val }));

            res.status(200).json({ success: true, data: sorted });

        } catch (err) {
            console.error('getRekapHarian error:', err);
            res.status(500).json({ success: false, error: err.message });
        }
    });
