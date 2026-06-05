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
// SERVER-SIDE CACHE — Mengurangi read RTDB secara drastis
// =========================================================================
// Cloud Functions instance bisa hidup beberapa menit sampai jam.
// Selama instance hidup, cache di memory tetap ada.
// Jika instance baru di-spawn, cache kosong → baca dari RTDB → cache ulang.
//
// CARA KERJA:
// 1. Request masuk → cek cache berdasarkan key (cabangId + statusFilter)
// 2. Jika cache ada DAN belum expired → return dari cache (0 RTDB reads)
// 3. Jika cache tidak ada ATAU expired → baca dari RTDB → simpan ke cache
//
// AMAN karena:
// - TTL 10 menit: data buku pokok hanya berubah saat admin input pembayaran dari Android
// - Delay 10 menit sangat wajar untuk pembukuan (bukan real-time dashboard)
// - Jika admin input pembayaran, perubahan terlihat max 10 menit kemudian di web
// - Ini sama seperti perilaku cache browser pada umumnya
// =========================================================================
const CACHE_TTL_MS = 10 * 60 * 1000;  // 10 menit
const METADATA_CACHE_TTL_MS = 15 * 60 * 1000;  // 15 menit (metadata sangat jarang berubah)

// =========================================================================
// ⚠️ BYPASS CACHE SEMENTARA — Pimpinan testing immutabilitas historis 04 Jun 2026
// -------------------------------------------------------------------------
// Setelah deploy fix lib/target.js (commit 5d7fc3b: guard masihAktifPadaTanggal),
// pimpinan masih melihat target kolom 03 Jun menyusut. Hipotesis: instance CF
// memegang respons lama di bukuPokokCache → klien menerima payload dengan
// tanggalLunasCicilan tertinggal dari pelanggan/ live, sehingga logika date-aware
// di lib/target.js tidak punya bahan yang benar.
//
// Window bypass: sampai 2026-06-04T05:59:33Z (~90 menit dari commit ini, 12:59
// WIB). Selama window: getFromCache SELALU return null + setToCache SKIP write.
// Setelah window auto-expire, cache aktif lagi tanpa edit lain. Pimpinan dapat
// menggeser/menghapus konstanta ini setelah verifikasi selesai.
//
// Trade-off (eksplisit, persetujuan pimpinan): selama window, getBukuPokok
// membaca pelanggan/, riwayat_pinjaman/, pembayaran_harian/ langsung tiap
// request → beban RTDB sementara naik. Window pendek menjaganya tetap aman.
// =========================================================================
const BYPASS_CACHE_UNTIL_MS = Date.parse('2026-06-04T05:59:33Z');

function isCacheBypassActive() {
    return Date.now() < BYPASS_CACHE_UNTIL_MS;
}

// Cache untuk getBukuPokok: key = "cabangId:statusFilter:adminUid"
const bukuPokokCache = new Map();

// Cache untuk metadata (dipakai oleh getBukuPokokSummary dan getKasirSummary)
let metadataCache = { data: null, timestamp: 0 };

function getCacheKey(cabangId, statusFilter, adminUid, bulan) {
    // bulan ikut ke cache key — agar respons orphanPaymentsByDate yang
    // berbeda per bulan tidak saling override. Bila bulan tidak dikirim,
    // fallback 'noBulan' menjaga back-compat untuk caller lama.
    return `${cabangId || 'all'}:${statusFilter}:${adminUid || 'all'}:${bulan || 'noBulan'}`;
}

function getFromCache(key) {
    // Bypass window aktif → paksa miss agar request berikutnya re-eval dari RTDB.
    if (isCacheBypassActive()) return null;
    const cached = bukuPokokCache.get(key);
    if (cached && (Date.now() - cached.timestamp) < CACHE_TTL_MS) {
        return cached.data;
    }
    // Expired atau tidak ada
    if (cached) bukuPokokCache.delete(key);
    return null;
}

function setToCache(key, data) {
    // Bypass window aktif → JANGAN populate cache. Bila menulis, request
    // berikutnya dalam window masih mungkin hit-stale bila getFromCache
    // di-by-pass tapi sumber yg ditulis ini sudah terlanjur stale di payload.
    // Skip write paling aman & paling sederhana untuk dipulihkan.
    if (isCacheBypassActive()) return;
    // Bersihkan cache lama jika terlalu banyak (max 50 entries untuk hemat memory)
    if (bukuPokokCache.size > 50) {
        const oldest = bukuPokokCache.keys().next().value;
        bukuPokokCache.delete(oldest);
    }
    bukuPokokCache.set(key, { data, timestamp: Date.now() });
}

async function getCachedMetadata() {
    if (metadataCache.data && (Date.now() - metadataCache.timestamp) < METADATA_CACHE_TTL_MS) {
        return metadataCache.data;
    }
    // Baca dari RTDB dan cache
    const metadataSnap = await db.ref('metadata').once('value');
    const metadata = metadataSnap.val() || {};
    metadataCache = { data: metadata, timestamp: Date.now() };
    return metadata;
}

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
// HELPER: Format epoch millis → "dd MMM yyyy" di TZ Asia/Jakarta (WIB).
// -------------------------------------------------------------------------
// Dipakai untuk men-derive `tanggalArsip` dari `archivedAt` (ServerValue.
// TIMESTAMP / System.currentTimeMillis()) di branch arsip — agar helper
// target client (lib/target.js) bisa apply cutoff date-aware dan mencegah
// "shrinking target" historis saat nasabah dihapus via cairkanSimpanan.
// Tidak ada RTDB read tambahan — semua dari data yang sudah dibaca.
// =========================================================================
function formatEpochToTanggalIndo(epochMs) {
    if (!epochMs || typeof epochMs !== 'number' || !Number.isFinite(epochMs)) return '';
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(epochMs + wibOffset);
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
            const { cabangId, adminUid, status, bulan } = req.query;
            const statusFilter = (status || 'aktif').toLowerCase();
            console.log(`[getBukuPokok] VERSION=2026-03-30-v5-tabs, statusFilter=${statusFilter}, cabangId=${cabangId}`);

            // 4. STEP 1 — Tentukan SCOPE admin yang BOLEH user akses berdasar role.
            //    Hindari short-circuit `if (adminUid)` yang dulu meng-bypass role check
            //    dan jadi sumber cross-data leakage (admin/pimpinan kasih adminUid lain
            //    → dapat data admin/cabang lain).
            let adminUids = [];
            const isCrossCabangRole = ['pengawas', 'koordinator', 'kasir_wilayah', 'sekretaris'].includes(user.role);

            if (user.role === 'admin') {
                // Admin LOCKED ke UID sendiri (abaikan cabangId/adminUid query)
                adminUids = [user.uid];
            } else if (user.role === 'pimpinan' || user.role === 'kasir_unit') {
                // Pimpinan/Kasir Unit LOCKED ke cabang sendiri
                const targetCabang = cabangId || user.cabang;
                if (!targetCabang) {
                    res.status(400).json({ success: false, error: 'cabangId diperlukan' });
                    return;
                }
                if (targetCabang !== user.cabang) {
                    res.status(403).json({ success: false, error: 'Akses cabang lain ditolak' });
                    return;
                }
                const cabangSnap = await db.ref(`metadata/cabang/${targetCabang}/adminList`).once('value');
                adminUids = cabangSnap.val() || [];
            } else if (isCrossCabangRole) {
                // Pengawas/Koordinator/Kasir Wilayah/Sekretaris bebas pilih cabang.
                if (cabangId) {
                    const cabangSnap = await db.ref(`metadata/cabang/${cabangId}/adminList`).once('value');
                    adminUids = cabangSnap.val() || [];
                } else if (adminUid) {
                    // adminUid tanpa cabangId — verifikasi admin valid via 1 metadata fetch.
                    // Hemat: tidak perlu fetch cabang adminList untuk single-admin view.
                    const adminMetaSnap = await db.ref(`metadata/admins/${adminUid}`).once('value');
                    if (!adminMetaSnap.exists()) {
                        res.status(403).json({ success: false, error: 'Admin tidak ditemukan' });
                        return;
                    }
                    adminUids = [adminUid];
                } else {
                    // Tidak ada cabangId & tidak ada adminUid → return cabang selection
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
            } else {
                res.status(403).json({ success: false, error: 'Role tidak dikenal' });
                return;
            }

            // 5. STEP 2 — Narrow ke single adminUid jika diminta, WAJIB ada di scope STEP 1.
            //    Mencegah admin/pimpinan/role lintas-cabang pass adminUid di luar scope mereka.
            if (adminUid) {
                if (!adminUids.includes(adminUid)) {
                    res.status(403).json({ success: false, error: 'Akses ke admin tersebut ditolak' });
                    return;
                }
                adminUids = [adminUid];
            }

            // 5. Cek cache dulu sebelum baca RTDB
            const cacheKey = getCacheKey(cabangId, statusFilter, adminUid, bulan);
            const cachedResponse = getFromCache(cacheKey);
            if (cachedResponse) {
                console.log(`[getBukuPokok] ✅ CACHE HIT: ${cacheKey}`);
                res.status(200).json(cachedResponse);
                return;
            }
            console.log(`[getBukuPokok] 📖 CACHE MISS: ${cacheKey}, reading from RTDB...`);

            // 6. Generate tanggal hari kerja berurutan (seperti buku pokok fisik)
            const hariKerja = generateHariKerja(60); // 60 hari kerja ke belakang

            // 7. Fetch pelanggan data per admin — ✅ OPTIMASI: Parallel reads
            let nasabahList = [];
            const adminNames = {};

            // ✅ OPTIMASI: riwayat_pinjaman HANYA dibaca jika tab "nasabah_lunas"
            // Tab lain tidak membutuhkan data riwayat dari RTDB
            // SEBELUMNYA: SELALU baca riwayat_pinjaman (boros bandwidth)
            // SEKARANG: hanya baca jika statusFilter membutuhkan
            // Selalu load riwayat_pinjaman: dibutuhkan agar pelunasan utang lama (top-up)
            // bisa direlokasi ke baris historis L1/CM/MB/ML, bukan ke tabel PB pinjaman baru.
            // Cost: +1 RTDB read per admin per request, dimitigasi cache 10 menit (lihat L42-43).
            const needsRiwayat = true;

            const readPromises = [
                Promise.all(adminUids.map(aUid => db.ref(`metadata/admins/${aUid}`).once('value'))),
                Promise.all(adminUids.map(aUid => db.ref(`pelanggan/${aUid}`).once('value'))),
                Promise.all(adminUids.map(aUid => db.ref(`summary/perAdmin/${aUid}`).once('value')))
            ];

            // Hanya baca riwayat_pinjaman jika dibutuhkan
            if (needsRiwayat) {
                readPromises.push(
                    Promise.all(adminUids.map(aUid => db.ref(`riwayat_pinjaman/${aUid}`).once('value')))
                );
            }

            const results = await Promise.all(readPromises);
            const [adminMetaResults, pelangganResults, summaryResults] = results;
            const riwayatResults = needsRiwayat ? results[3] : [];

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
            if (needsRiwayat) adminUids.forEach((aUid, i) => {
                const riwayatData = riwayatResults[i] ? riwayatResults[i].val() : null;
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
                    const isSisaTabungan = pStatusKhusus === 'MENUNGGU_PENCAIRAN';
                    const isNasabahLunas = !isSisaTabungan && sisaUtang <= 0 && totalPelunasan > 0;
                    const isAktif = !isSisaTabungan && !isNasabahLunas && (pStatus === 'aktif' || pStatus === 'disetujui');

                    // Filter berdasarkan tab yang dipilih
                    if (statusFilter === 'aktif' && !isAktif) return;
                    if (statusFilter === 'sisa_tabungan' && !isSisaTabungan) return;
                    if (statusFilter === 'nasabah_lunas' && !isNasabahLunas) return;
                    // Legacy filters (backward compatibility)
                    if (statusFilter === 'lunas' && pStatus !== 'lunas') return;
                    if (statusFilter === 'semua') { /* show all except menunggu approval/ditolak */ }
                    // Entry "Pelunasan Top-Up" yang ditulis Android (PelangganViewModel.kt:4025-4036)
                    // ke pembayaranList pinjaman BARU mewakili pelunasan utang LAMA, bukan cicilan
                    // pinjaman baru. Filter dari tampilan PB; akan direlokasi ke baris historis di blok
                    // di bawah. totalDibayar (L455) tetap dihitung dari pembayaranList asli agar
                    // sisaUtang pinjaman baru akurat — Android sengaja memasukkannya ke totalDibayar
                    // new loan untuk mengoffset principal absorbed dari old loan.
                    const pembayaranListForDisplay = (() => {
                        const raw = p.pembayaranList;
                        if (!raw) return raw;
                        const list = Array.isArray(raw) ? raw : Object.values(raw || {});
                        return list.filter(x => !(x && x.keterangan === 'Pelunasan Top-Up'));
                    })();
                    const pembayaranPerTanggal = extractPembayaranPerTanggal(pembayaranListForDisplay);

                    // Relokasi pelunasan utang lama → ke entry riwayat (pinjaman lama yang baru
                    // diarsipkan). Match paritas buku fisik: pelunasan tercatat di halaman pinjaman
                    // LAMA, bukan di halaman pinjaman baru.
                    // Fallback (riwayat tidak ada / arsip gagal): inject ke pembayaran pinjaman baru
                    // seperti perilaku lama, agar pelunasan tidak hilang dari view.
                    const sisaUtangLamaTopUp = p.sisaUtangLamaSebelumTopUp || 0;
                    const pinjamanKeN = p.pinjamanKe || 1;
                    if (sisaUtangLamaTopUp > 0 && pinjamanKeN > 1) {
                        const riwayatForPid = riwayatLookup[pId] || [];
                        const lastRiwayat = riwayatForPid.length > 0
                            ? riwayatForPid[riwayatForPid.length - 1] // sudah sort ASC di L438
                            : null;
                        if (lastRiwayat) {
                            // Tanggal pelunasan: tanggalLunasCicilan pinjaman lama (kalau ada),
                            // fallback ke tanggalPencairan pinjaman baru (= tanggal settle di RTDB).
                            const tglPelunasan = (lastRiwayat.tanggalLunasCicilan || p.tanggalPencairan || '').trim();
                            if (tglPelunasan) {
                                if (!lastRiwayat.pembayaran[tglPelunasan]) {
                                    lastRiwayat.pembayaran[tglPelunasan] = { total: 0, entries: [] };
                                }
                                lastRiwayat.pembayaran[tglPelunasan].total += sisaUtangLamaTopUp;
                                lastRiwayat.pembayaran[tglPelunasan].entries.push({
                                    jumlah: sisaUtangLamaTopUp,
                                    keterangan: 'Pelunasan sisa utang (top-up)',
                                    type: 'pelunasan_sisa_utang'
                                });
                                // Sinkronkan totalDibayar / sisaUtang riwayat → baris historis di web
                                // menunjukkan pinjaman lama lunas via pelunasan ini.
                                lastRiwayat.totalDibayar = (lastRiwayat.totalDibayar || 0) + sisaUtangLamaTopUp;
                                lastRiwayat.sisaUtang = Math.max(0, (lastRiwayat.totalPelunasan || 0) - lastRiwayat.totalDibayar);
                            }
                        } else {
                            // Fallback: arsip riwayat tidak ada → tampilkan di pembayaran pinjaman
                            // baru (perilaku lama) agar pelunasan tetap kelihatan di buku pokok.
                            const tglPencairan = (p.tanggalPencairan || '').trim();
                            if (tglPencairan) {
                                if (!pembayaranPerTanggal[tglPencairan]) {
                                    pembayaranPerTanggal[tglPencairan] = { total: 0, entries: [] };
                                }
                                pembayaranPerTanggal[tglPencairan].total += sisaUtangLamaTopUp;
                                pembayaranPerTanggal[tglPencairan].entries.push({
                                    jumlah: sisaUtangLamaTopUp,
                                    keterangan: 'Pelunasan sisa utang (top-up)',
                                    type: 'pelunasan_sisa_utang'
                                });
                            }
                        }
                    }

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
                        statusPencairanSimpanan: p.statusPencairanSimpanan || '',
                        tanggalStatusKhusus: p.tanggalStatusKhusus || '',
                        tanggalLunasCicilan: p.tanggalLunasCicilan || '',
                        tanggalDaftar: p.tanggalDaftar || p.tanggalPengajuan || '',
                        tanggalPencairan: p.tanggalPencairan || '',
                        tanggalPengajuan: p.tanggalPengajuan || '',
                        adminUid: aUid,
                        adminName: adminNames[aUid],
                        cabangId: p.cabangId || '',
                        wilayah: p.wilayah || '',
                        simpanan: p.simpanan || 0,
                        tarikTabungan: p.tarikTabungan || 0,
                        totalDiterima: p.totalDiterima || 0,
                        pembayaran: pembayaranPerTanggal,
                        // Foto nasabah & KTP (sudah ada di data pelanggan, tanpa tambahan RTDB read)
                        fotoKtpUrl: p.fotoKtpUrl || '',
                        fotoKtpSuamiUrl: p.fotoKtpSuamiUrl || '',
                        fotoKtpIstriUrl: p.fotoKtpIstriUrl || '',
                        fotoNasabahUrl: p.fotoNasabahUrl || '',
                        // ✅ Setelah soft-removal NIK/Foto KTP/Foto Nasabah, foto serah
                        // terima jadi pengganti visual utama "Foto Nasabah" di web (fallback
                        // bila fotoNasabahUrl kosong). Wajib di-expose CF agar render web bisa
                        // pakai dengan elegan tanpa tambahan RTDB read.
                        fotoSerahTerimaUrl: p.fotoSerahTerimaUrl || '',
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
            // ✅ 'semua' juga butuh arsip nasabah lunas (tabungan sudah dicairkan)
            // agar tampil di tabel gabungan PB/L1/CM/MB/ML pada Buku Pokok web.
            if (statusFilter === 'nasabah_lunas' || statusFilter === 'semua') {
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
                        statusPencairanSimpanan: d.statusPencairanSimpanan || '',
                        tanggalStatusKhusus: d.tanggalStatusKhusus || '',
                        tanggalLunasCicilan: d.tanggalLunasCicilan || '',
                        tanggalDaftar: d.tanggalPengajuan || '',
                        tanggalPencairan: d.tanggalPencairan || '',
                        tanggalPengajuan: d.tanggalPengajuan || '',
                        adminUid: aUid,
                        adminName: adminNames[aUid] || aUid,
                        cabangId: '',
                        wilayah: '',
                        simpanan: d.simpanan || 0,
                        tarikTabungan: d.tarikTabungan || 0,
                        totalDiterima: d.totalDiterima || 0,
                        pembayaran: pembayaranPerTanggal,
                        fotoKtpUrl: '',
                        fotoKtpSuamiUrl: '',
                        fotoKtpIstriUrl: '',
                        fotoNasabahUrl: '',
                        fotoSerahTerimaUrl: '',
                        sisaUtangLama: sisaUtangLama,
                        sisaUtangLamaSebelumTopUp: 0,
                        riwayatPinjaman: riwayat,
                        // Penanda bahwa ini nasabah dari arsip (sudah dicairkan tabungannya)
                        dariArsip: true,
                        // ✅ Tanggal nasabah berhenti menagih (= waktu cairkanSimpanan).
                        // Dipakai helper isEligibleForTarget sebagai CUTOFF date-aware:
                        //   cur >= tanggalArsip → 0 (sudah berhenti ditagih pada/sebelum kolom)
                        //   cur <  tanggalArsip → evaluasi normal (saat itu masih aktif)
                        // Tanpa cutoff ini, fix lama men-skip TUNTAS `dariArsip` →
                        // menyebabkan target tanggal lampau MENYUSUT saat nasabah baru
                        // diarsip hari ini. archivedAt di-set ServerValue.TIMESTAMP oleh
                        // CF onPelangganWrite (top-up) & System.currentTimeMillis() oleh
                        // Android cairkanSimpanan — selalu ada untuk arsip baru. Arsip
                        // lama tanpa archivedAt → "" → helper terapkan skip (preserve
                        // fix 02 Jun untuk hindari over-count regresi).
                        tanggalArsip: formatEpochToTanggalIndo(d.archivedAt)
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

            // =================================================================
            // 8. ORPHAN PAYMENTS — pembayaran_harian entries milik pelanggan
            //    yang sudah tidak ada lagi di pelanggan/ (mis. setelah
            //    cairkanSimpanan menghapus nasabah). Per SOP koperasi,
            //    payment-nya tetap masuk Storting walaupun nasabah-nya tidak
            //    ada lagi.
            //
            //    SHAPE BARU (commit ini): per-entry array, bukan sum agregat.
            //      { "dd MMM yyyy": [ { pelangganId, namaPanggilan, namaKtp,
            //          adminUid, adminName, jumlah, jenis, tanggal,
            //          pinjamanKe, tanggalPencairan, tanggalPengajuan,
            //          status }, ... ] }
            //    Sebelumnya hanya { date: { adminUid: jumlah } }. Web Buku
            //    Pokok butuh per-entry untuk slot ke kategori PB/L1/CM/MB/LM;
            //    Web Buku Rekap tetap berjalan dengan agregasi client-side.
            //
            //    RETROACTIVE JOIN: untuk entry legacy (tanpa pinjamanKe /
            //    tanggalPencairan — ditulis sebelum schema extension), batch-
            //    lookup riwayat_pinjaman/{adminUid}/{pelangganId} → pakai
            //    archive dengan pinjamanKe TERTINGGI sebagai sumber kategori.
            //
            //    Hanya aktif bila param `bulan` (YYYY-MM) dikirim caller.
            //    Path key pembayaran_harian = "dd MMM yyyy" (Indonesian),
            //    lihat getTodayIndonesia() di summaryHelpers.js.
            // =================================================================
            let orphanPaymentsByDate = {};
            if (bulan && cabangId) {
                try {
                    const currentPelangganIds = new Set();
                    nasabahList.forEach(n => { if (n.id) currentPelangganIds.add(n.id); });

                    // `bulan` boleh single ("2026-05") ATAU multi-month
                    // comma-separated ("2026-05,2026-04,2026-03,2026-02").
                    // Buku Pokok pass multi-month karena rolling window 60 hari
                    // bisa menyentuh ~4 bulan & background calculations
                    // (carry-over saldo awal, stortingGlobal prev-month) butuh
                    // kontinuitas orphan lintas semua bulan tersebut. BukuRekap
                    // tetap pass single bulan → di-handle sama (list of 1).
                    const bulanList = String(bulan)
                        .split(',')
                        .map(s => s.trim())
                        .filter(s => /^\d{4}-\d{2}$/.test(s));

                    if (bulanList.length > 0) {
                        const dateKeys = [];
                        bulanList.forEach(b => {
                            const [bYyyy, bMm] = b.split('-').map(Number);
                            if (!bYyyy || !bMm) return;
                            const daysInMonth = new Date(bYyyy, bMm, 0).getDate();
                            const monthAbbr = BULAN_INDO[bMm - 1];
                            for (let d = 1; d <= daysInMonth; d++) {
                                dateKeys.push(`${String(d).padStart(2, '0')} ${monthAbbr} ${bYyyy}`);
                            }
                        });

                        // Parallel read tiap tanggal — path key pembayaran_harian
                        // = "dd MMM yyyy" (lihat getTodayIndonesia).
                        const phSnaps = await Promise.all(dateKeys.map(dateKey =>
                            db.ref(`pembayaran_harian/${cabangId}/${dateKey}`).once('value')
                        ));

                        // Pass 1: kumpulkan semua orphan entry sebagai per-entry detail.
                        // Tandai yang butuh retro-join (field kategori kosong/null).
                        const needsJoin = []; // entries yang butuh riwayat lookup
                        phSnaps.forEach((snap, idx) => {
                            if (!snap.exists()) return;
                            const dateKey = dateKeys[idx];
                            snap.forEach(entrySnap => {
                                const e = entrySnap.val();
                                if (!e || !e.pelangganId) return;
                                // Skip bila pelanggan masih ada — sudah dihitung
                                // via n.pembayaran[dateKey] di client.
                                if (currentPelangganIds.has(e.pelangganId)) return;
                                // ✅ FIX cross-admin leak server-side: bila caller request
                                // admin spesifik, orphan dari admin lain HARUS di-skip.
                                // pembayaran_harian/{cabangId}/{date} di-baca cabang-wide,
                                // jadi tanpa guard ini orphan customer Admin B/C (yang
                                // dihapus via cairkanSimpanan) tetap masuk response saat
                                // pimpinan view Admin A — bocor lewat orphanPaymentsByDate
                                // ke nasabahExpanded client (commit 4d6e304 menutupnya di
                                // sisi tampilan; ini menutupnya di kabel + cache CF).
                                // Saat adminUid kosong ("Semua Admin"), guard pass-through.
                                if (adminUid && e.adminUid !== adminUid) return;
                                const detail = {
                                    pelangganId: e.pelangganId,
                                    namaPanggilan: e.namaPanggilan || '',
                                    namaKtp: e.namaKtp || '',
                                    adminUid: e.adminUid || '',
                                    adminName: e.adminName || '',
                                    jumlah: e.jumlah || 0,
                                    jenis: e.jenis || '',
                                    tanggal: e.tanggal || dateKey,
                                    pinjamanKe: (typeof e.pinjamanKe === 'number') ? e.pinjamanKe : null,
                                    tanggalPencairan: e.tanggalPencairan || '',
                                    tanggalPengajuan: e.tanggalPengajuan || '',
                                    status: e.status || ''
                                };
                                if (!detail.tanggalPencairan || !detail.pinjamanKe) {
                                    needsJoin.push(detail);
                                }
                                if (!orphanPaymentsByDate[dateKey]) orphanPaymentsByDate[dateKey] = [];
                                orphanPaymentsByDate[dateKey].push(detail);
                            });
                        });

                        // Pass 2: retroactive join via riwayat_pinjaman.
                        // Dedup per (adminUid, pelangganId) — multiple orphan
                        // payments dari customer yang sama share 1 lookup.
                        if (needsJoin.length > 0) {
                            const uniqueLookups = {};
                            needsJoin.forEach(d => {
                                // ✅ FIX defensive: guard ulang scope admin di sini.
                                // needsJoin sudah dipopulate via Pass 1 yang punya filter
                                // sama, jadi cek ini secara teknis no-op untuk request
                                // admin-spesifik (tidak akan ada entry foreign-admin yang
                                // sampai ke Pass 2). TAPI Pass 2 membaca riwayat_pinjaman/
                                // {d.adminUid}/{d.pelangganId} — bila suatu saat Pass 1
                                // filter berubah / dipindah, guard di sini mencegah read
                                // node admin lain (information disclosure + read waste).
                                if (adminUid && d.adminUid !== adminUid) return;
                                const k = `${d.adminUid}:${d.pelangganId}`;
                                if (!uniqueLookups[k]) {
                                    uniqueLookups[k] = { adminUid: d.adminUid, pelangganId: d.pelangganId };
                                }
                            });
                            const lookupResults = await Promise.all(
                                Object.values(uniqueLookups).map(async ({ adminUid: aU, pelangganId: pId }) => {
                                    try {
                                        const rSnap = await db.ref(`riwayat_pinjaman/${aU}/${pId}`).once('value');
                                        if (!rSnap.exists()) return [`${aU}:${pId}`, null];
                                        // Pilih archive dengan pinjamanKe TERTINGGI
                                        // (= loan paling terakhir customer ini punya).
                                        let latest = null;
                                        let maxPK = -1;
                                        rSnap.forEach(child => {
                                            const pk = parseInt(child.key, 10);
                                            if (!isNaN(pk) && pk > maxPK) {
                                                maxPK = pk;
                                                latest = child.val();
                                            }
                                        });
                                        if (latest && maxPK >= 0) {
                                            latest._archivedPinjamanKe = maxPK;
                                        }
                                        return [`${aU}:${pId}`, latest];
                                    } catch (_) {
                                        return [`${aU}:${pId}`, null];
                                    }
                                })
                            );
                            const archiveCache = Object.fromEntries(lookupResults);
                            needsJoin.forEach(d => {
                                const archive = archiveCache[`${d.adminUid}:${d.pelangganId}`];
                                if (!archive) return;
                                if (!d.tanggalPencairan) d.tanggalPencairan = archive.tanggalPencairan || '';
                                if (!d.tanggalPengajuan) d.tanggalPengajuan = archive.tanggalPengajuan || '';
                                if (!d.pinjamanKe) {
                                    d.pinjamanKe = archive._archivedPinjamanKe || archive.pinjamanKe || null;
                                }
                                if (!d.status) d.status = archive.status || 'lunas';
                                // Fallback nama bila entry pembayaran_harian
                                // legacy tidak punya namaPanggilan/namaKtp.
                                if (!d.namaPanggilan) d.namaPanggilan = archive.namaPanggilan || '';
                                if (!d.namaKtp) d.namaKtp = archive.namaKtp || '';
                            });
                        }
                    }
                } catch (e) {
                    console.error('⚠ Orphan payments aggregation failed:', e.message);
                    orphanPaymentsByDate = {};
                }
            }

            const responseBody = {
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
                    targetHarianHariIni: totalTargetHarian,
                    orphanPaymentsByDate: orphanPaymentsByDate
                }
            };

            // Simpan ke cache untuk request berikutnya
            setToCache(cacheKey, responseBody);
            console.log(`[getBukuPokok] 💾 Cached: ${cacheKey} (${nasabahList.length} nasabah)`);

            res.status(200).json(responseBody);

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

            // Get metadata for cabang list — ✅ OPTIMASI: gunakan cache
            const metadata = await getCachedMetadata();
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
