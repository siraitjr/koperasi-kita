// =========================================================================
// MAINTENANCE CF: refreezeRekapHarian
// -------------------------------------------------------------------------
// LATAR (pimpinan 08 Jun 2026): rekap_harian_final/{adminUid}/{YYYY-MM-DD}
// di-freeze 23:59 dari summary.targetHariIni + summary.pembayaranHariIni.
// Bila kode CF target/storting terbukti DIVERGEN saat freeze terjadi (mis.
// jalur triggerTargetRecalc inline yang sudah diunifikasi di commit 49c08cc),
// snapshot beku berisi nilai SALAH dan bersifat IMMUTABLE per Rule 3.
//
// Fungsi ini = SINGLE-DATE one-shot re-freeze, override eksplisit Rule 3
// dengan AUTHORIZATION pimpinan/pengawas + AUDIT TRAIL penuh.
//
// SAFETY GUARDS:
//   1. Auth wajib + role pimpinan ATAU pengawas.
//   2. Tanggal yang di-refreeze HARUS hari ini WIB. Helper
//      calculateTargetHariIni / fullRecalculateAdminSummary memakai
//      getTodayIndonesia() internal — bila dipanggil pada tanggal berbeda,
//      hasil tidak deterministik untuk historis. Refreeze historis butuh
//      implementasi date-aware terpisah (di luar scope ini).
//   3. Sebelum overwrite, nilai LAMA snapshot disimpan ke
//      rekap_harian_final_audit/{YYYY-MM-DD}/{adminUid}/before — TIDAK ada
//      data yang hilang permanen.
//   4. Setiap admin di-refresh via fullRecalculateAdminSummary (helper
//      terunifikasi post-patch 57589b2 + 49c08cc) → summary.targetHariIni
//      dijamin akurat sebelum snapshot.
//
// USAGE (web admin / Cloud Console):
//   const fn = httpsCallable(functions, 'refreezeRekapHarian');
//   const res = await fn({ tanggal: '08 Jun 2026' });
//   // res.data.stats = [{ adminUid, target, storting, beforeTarget, beforeStorting }, ...]
// =========================================================================

const { onCall } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const {
    fullRecalculateAdminSummary,
    getTodayIndonesia
} = require('./summaryHelpers');

const db = admin.database();

// Konversi "dd MMM yyyy" (Indonesian) → "YYYY-MM-DD". Konsisten dgn
// getTodayKeyWIB() di freezeRekapHarian.js.
const BULAN_INDO_MAP = {
    'Jan': '01', 'Feb': '02', 'Mar': '03', 'Apr': '04',
    'Mei': '05', 'Jun': '06', 'Jul': '07', 'Agu': '08',
    'Sep': '09', 'Okt': '10', 'Nov': '11', 'Des': '12'
};

function convertTanggalIndoToISO(tanggal) {
    const parts = (tanggal || '').trim().split(' ');
    if (parts.length !== 3) return null;
    const d = parts[0].padStart(2, '0');
    const m = BULAN_INDO_MAP[parts[1]];
    const y = parts[2];
    if (!m || !/^\d{4}$/.test(y) || !/^\d{2}$/.test(d)) return null;
    return `${y}-${m}-${d}`;
}

exports.refreezeRekapHarian = onCall({ region: 'asia-southeast1' }, async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new Error("Not authenticated");

    // Role gate: pimpinan ATAU pengawas.
    const [isPimpinan, isPengawas] = await Promise.all([
        db.ref(`metadata/roles/pimpinan/${uid}`).once("value").then((s) => !!s.val()).catch(() => false),
        db.ref(`metadata/roles/pengawas/${uid}`).once("value").then((s) => !!s.val()).catch(() => false)
    ]);
    if (!isPimpinan && !isPengawas) {
        throw new Error("Only pimpinan or pengawas can refreeze");
    }

    const tanggal = (request.data?.tanggal || '').trim();
    if (!/^\d{2}\s\w{3}\s\d{4}$/.test(tanggal)) {
        throw new Error("Parameter 'tanggal' wajib format 'dd MMM yyyy' (mis. '08 Jun 2026')");
    }

    // SAFETY GATE: helper memakai getTodayIndonesia internal — refreeze HANYA
    // valid bila tanggal yang diminta == hari ini WIB. Refreeze historis butuh
    // varian helper date-aware terpisah.
    const today = getTodayIndonesia();
    if (tanggal !== today) {
        throw new Error(
            `Refreeze hanya untuk hari ini WIB. Hari ini: '${today}', diminta: '${tanggal}'. ` +
            `Refreeze historis butuh implementasi date-aware terpisah — minta pimpinan terlebih dulu.`
        );
    }

    const dateKey = convertTanggalIndoToISO(tanggal);
    if (!dateKey) {
        throw new Error(`Gagal konversi tanggal '${tanggal}' ke YYYY-MM-DD`);
    }

    console.log(`🔓 refreezeRekapHarian: tanggal=${tanggal}, dateKey=${dateKey}, by=${uid}`);

    // Step 1: full-recalc semua admin summaries dengan helper terunifikasi
    // (post-patch 57589b2 + 49c08cc). summary.targetHariIni + summary.pembayaranHariIni
    // dijamin akurat di RTDB setelah step ini.
    const adminsSnap = await db.ref('metadata/admins').once('value');
    const adminEntries = Object.entries(adminsSnap.val() || {})
        .filter(([_, d]) => d?.role === 'admin');

    if (adminEntries.length === 0) {
        return { success: true, tanggal, dateKey, refreezedBy: uid, adminCount: 0, stats: [] };
    }

    const recalcResults = await Promise.allSettled(
        adminEntries.map(([adminUid]) => fullRecalculateAdminSummary(adminUid))
    );
    let recalcFailed = 0;
    recalcResults.forEach((r, i) => {
        if (r.status === 'rejected') {
            recalcFailed++;
            console.error(`❌ fullRecalc failed for ${adminEntries[i][0]}: ${r.reason?.message || r.reason}`);
        }
    });
    console.log(`✅ Full-recalculated ${adminEntries.length - recalcFailed}/${adminEntries.length} admin summaries`);

    // Step 2: baca summary terbaru + snapshot ke rekap_harian_final.
    // Audit: simpan nilai LAMA (kalau ada) ke rekap_harian_final_audit.
    const ts = admin.database.ServerValue.TIMESTAMP;
    const updates = {};
    const stats = [];

    await Promise.all(adminEntries.map(async ([adminUid]) => {
        const [summarySnap, existingSnap] = await Promise.all([
            db.ref(`summary/perAdmin/${adminUid}`).once('value'),
            db.ref(`rekap_harian_final/${adminUid}/${dateKey}`).once('value')
        ]);
        const s = summarySnap.val() || {};
        const target = Number.isFinite(s.targetHariIni) ? s.targetHariIni : 0;
        const storting = Number.isFinite(s.pembayaranHariIni) ? s.pembayaranHariIni : 0;

        const existing = existingSnap.val();
        if (existing) {
            // Audit trail: nilai LAMA permanen di node terpisah agar bisa dirujuk
            // bila ada pertanyaan downstream / dispute audit. Tidak menghapus
            // riwayat — hanya menambah lapisan trace.
            updates[`rekap_harian_final_audit/${dateKey}/${adminUid}/before`] = existing;
            updates[`rekap_harian_final_audit/${dateKey}/${adminUid}/refreezedBy`] = uid;
            updates[`rekap_harian_final_audit/${dateKey}/${adminUid}/refreezedAt`] = ts;
        }

        updates[`rekap_harian_final/${adminUid}/${dateKey}`] = {
            target,
            storting,
            frozenAt: ts
        };
        stats.push({
            adminUid,
            target,
            storting,
            beforeTarget: existing ? (existing.target || 0) : null,
            beforeStorting: existing ? (existing.storting || 0) : null,
            deltaTarget: existing ? (target - (existing.target || 0)) : null,
            deltaStorting: existing ? (storting - (existing.storting || 0)) : null
        });
    }));

    await db.ref().update(updates);

    console.log(`✅ refreezeRekapHarian: ${stats.length} admin re-frozen untuk ${dateKey}`);

    return {
        success: true,
        tanggal,
        dateKey,
        refreezedBy: uid,
        refreezedAt: new Date().toISOString(),
        adminCount: stats.length,
        recalcFailed,
        stats
    };
});
