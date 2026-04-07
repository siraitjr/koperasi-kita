// =========================================================================
// KOREKSI STORTING API
// Endpoint untuk membaca dan menyimpan koreksi storting bulanan per resort.
// Hanya pengawas yang dapat menyimpan koreksi.
//
// GET  getKoreksiStorting?cabangId=X&bulan=YYYY-MM
//      → { success, data: { adminUid: { l1, cm, mb, ml } } }
//
// POST setKoreksiStorting
//      body: { cabangId, adminUid, bulan, l1, cm, mb, ml }
//      → { success }
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

async function verifyAuth(req) {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return { valid: false, error: 'Token tidak ditemukan' };
    }
    try {
        const idToken = authHeader.split('Bearer ')[1];
        const decoded = await admin.auth().verifyIdToken(idToken);
        return { valid: true, uid: decoded.uid };
    } catch (e) {
        return { valid: false, error: 'Token tidak valid atau expired' };
    }
}

async function getUserRole(uid) {
    const snap = await db.ref(`metadata/admins/${uid}`).once('value');
    if (!snap.exists()) return null;
    const d = snap.val();
    return { uid, role: d.role || 'admin', cabang: d.cabang || null };
}

// =========================================================================
// GET: Ambil semua koreksi untuk satu cabang + bulan
// =========================================================================
exports.getKoreksiStorting = functions.region('asia-southeast1').https.onRequest(async (req, res) => {
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Methods', 'GET, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    if (req.method === 'OPTIONS') { res.status(204).send(''); return; }

    const auth = await verifyAuth(req);
    if (!auth.valid) { res.status(401).json({ success: false, error: auth.error }); return; }

    const userRole = await getUserRole(auth.uid);
    if (!userRole) { res.status(403).json({ success: false, error: 'User tidak ditemukan' }); return; }

    const { cabangId, bulan } = req.query;
    if (!cabangId || !bulan) {
        res.status(400).json({ success: false, error: 'cabangId dan bulan wajib' });
        return;
    }

    const allowedRoles = ['pimpinan', 'koordinator', 'pengawas', 'kasir_wilayah', 'sekretaris'];
    if (!allowedRoles.includes(userRole.role)) {
        res.status(403).json({ success: false, error: 'Akses ditolak' });
        return;
    }

    const snap = await db.ref(`koreksi_storting/${cabangId}`).once('value');
    if (!snap.exists()) { res.json({ success: true, data: {} }); return; }

    const all = snap.val();
    const result = {};
    Object.entries(all).forEach(([adminUid, months]) => {
        if (months?.[bulan]) {
            const { l1 = 0, cm = 0, mb = 0, ml = 0 } = months[bulan];
            result[adminUid] = { l1, cm, mb, ml };
        }
    });

    res.json({ success: true, data: result });
});

// =========================================================================
// POST: Simpan koreksi untuk satu resort + bulan (hanya pengawas)
// =========================================================================
exports.setKoreksiStorting = functions.region('asia-southeast1').https.onRequest(async (req, res) => {
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
    if (req.method === 'OPTIONS') { res.status(204).send(''); return; }
    if (req.method !== 'POST') { res.status(405).json({ success: false, error: 'Method not allowed' }); return; }

    const auth = await verifyAuth(req);
    if (!auth.valid) { res.status(401).json({ success: false, error: auth.error }); return; }

    const userRole = await getUserRole(auth.uid);
    if (!userRole) { res.status(403).json({ success: false, error: 'User tidak ditemukan' }); return; }

    if (userRole.role !== 'pengawas') {
        res.status(403).json({ success: false, error: 'Hanya pengawas yang dapat menginput koreksi' });
        return;
    }

    const { cabangId, adminUid, bulan, l1, cm, mb, ml } = req.body;
    if (!cabangId || !adminUid || !bulan) {
        res.status(400).json({ success: false, error: 'cabangId, adminUid, dan bulan wajib' });
        return;
    }

    await db.ref(`koreksi_storting/${cabangId}/${adminUid}/${bulan}`).set({
        l1: parseInt(l1) || 0,
        cm: parseInt(cm) || 0,
        mb: parseInt(mb) || 0,
        ml: parseInt(ml) || 0,
        updatedAt: Date.now(),
        updatedBy: auth.uid,
    });

    res.json({ success: true });
});
