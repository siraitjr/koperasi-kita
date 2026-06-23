// =========================================================================
// sweepRiwayatOrphan.js
// =========================================================================
// ONE-OFF MAINTENANCE: bersihkan arsip riwayat_pinjaman "hantu" akibat bug
// rollback reject top-up (sebelum fix di onPelangganWrite.js).
//
// LATAR BELAKANG:
//   Saat top-up disubmit, pinjamanKe naik (N → N+1) → CF mengarsipkan loan
//   ke-N ke riwayat_pinjaman/{adminUid}/{pId}/N. Bila top-up DITOLAK, client
//   me-rollback pelanggan ke pinjamanKe = N (loan N aktif kembali), TAPI arsip
//   riwayat_pinjaman/{pId}/N tidak ikut dihapus → Buku Pokok menampilkannya
//   sebagai pinjaman ke-N historis duplikat (seakan "Lunas tanpa riwayat").
//
// INVARIAN yang ditegakkan:
//   Nomor pinjaman yang SEDANG AKTIF di pelanggan/ TIDAK boleh ada di
//   riwayat_pinjaman/. Jadi: untuk setiap nasabah dengan pinjamanKe = N,
//   bila riwayat_pinjaman/{adminUid}/{pId}/N ADA → itu hantu → hapus.
//   Arsip pinjaman lama yang sah (ke-1 .. N-1) TIDAK disentuh.
//
// KEAMANAN:
//   - DRY-RUN secara default. Hanya men-scan & melaporkan.
//   - Penghapusan HANYA terjadi bila dipanggil dengan ?confirm=true.
//   - Filter opsional: ?adminUid=xxx atau ?cabang=panti (batasi cakupan).
//
// Endpoint: GET /sweepRiwayatOrphan
// Query params:
//   ?confirm=true   → benar-benar hapus (tanpa ini = dry-run, aman)
//   ?adminUid=xxx   → batasi ke 1 admin
//   ?cabang=panti   → batasi ke 1 cabang
//
// Response: JSON laporan (daftar hantu + jumlah, status dryRun/deleted).
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

exports.sweepRiwayatOrphan = functions
    .region('asia-southeast1')
    .runWith({ timeoutSeconds: 540, memory: '1GB' })
    .https.onRequest(async (req, res) => {

        const dryRun = req.query.confirm !== 'true';
        const filterAdminUid = req.query.adminUid || null;
        const filterCabang = req.query.cabang || null;

        console.log(`🧹 SWEEP RIWAYAT ORPHAN - MULAI (dryRun=${dryRun})`);

        try {
            // Load metadata admins (untuk filter cabang & nama) + seluruh
            // pelanggan + seluruh riwayat_pinjaman. Sekali baca (maintenance).
            const [adminsSnap, pelangganSnap, riwayatSnap] = await Promise.all([
                db.ref('metadata/admins').once('value'),
                db.ref('pelanggan').once('value'),
                db.ref('riwayat_pinjaman').once('value')
            ]);

            const adminsData = adminsSnap.val() || {};
            const pelangganData = pelangganSnap.val() || {};
            const riwayatData = riwayatSnap.val() || {};

            // Tentukan admin mana yang di-scan (mirror pola auditDataSampah).
            const adminUidsToScan = new Set();
            for (const [uid, info] of Object.entries(adminsData)) {
                if (info.role !== 'admin') continue;
                if (filterAdminUid && uid !== filterAdminUid) continue;
                if (filterCabang && (info.cabang || '').toLowerCase() !== filterCabang.toLowerCase()) continue;
                adminUidsToScan.add(uid);
            }

            const orphans = [];        // daftar hantu yang ditemukan
            let totalPelangganScanned = 0;

            // Scan pelanggan/{adminUid}/{pId}
            for (const [adminUid, pelangganMap] of Object.entries(pelangganData)) {
                // Bila ada filter & adminUid ini bukan admin valid yang difilter → skip.
                // (adminUidsToScan hanya berisi role 'admin' yang lolos filter.)
                if (!adminUidsToScan.has(adminUid)) continue;

                const adminInfo = adminsData[adminUid] || {};
                const adminName = adminInfo.name || adminInfo.email || adminUid;

                for (const [pId, p] of Object.entries(pelangganMap || {})) {
                    if (!p || typeof p !== 'object') continue;
                    totalPelangganScanned++;

                    // Nomor pinjaman AKTIF saat ini.
                    const pinjamanKeAktif = p.pinjamanKe || 1;

                    // Cek apakah ada arsip dengan key == pinjamanKe aktif → hantu.
                    const arsipNasabah = (riwayatData[adminUid] && riwayatData[adminUid][pId]) || {};
                    const arsipHantu = arsipNasabah[String(pinjamanKeAktif)] !== undefined
                        ? arsipNasabah[String(pinjamanKeAktif)]
                        : arsipNasabah[pinjamanKeAktif];

                    if (arsipHantu !== undefined && arsipHantu !== null) {
                        orphans.push({
                            adminUid,
                            adminName,
                            pelangganId: pId,
                            namaKtp: p.namaKtp || '',
                            namaPanggilan: p.namaPanggilan || '',
                            pinjamanKeAktif,
                            statusAktif: p.status || '',
                            path: `riwayat_pinjaman/${adminUid}/${pId}/${pinjamanKeAktif}`
                        });
                    }
                }
            }

            // Eksekusi penghapusan (hanya bila confirm=true).
            let deletedCount = 0;
            const deleteErrors = [];
            if (!dryRun && orphans.length > 0) {
                for (const o of orphans) {
                    try {
                        await db.ref(o.path).remove();
                        deletedCount++;
                        console.log(`🗑️ Hapus hantu: ${o.path} (${o.namaPanggilan || o.namaKtp})`);
                    } catch (e) {
                        deleteErrors.push({ path: o.path, error: e.message });
                        console.error(`❌ Gagal hapus ${o.path}: ${e.message}`);
                    }
                }
            }

            const report = {
                dryRun,
                mode: dryRun
                    ? 'DRY-RUN (tidak ada yang dihapus — tambahkan ?confirm=true untuk eksekusi)'
                    : 'EXECUTED (penghapusan dilakukan)',
                filter: { adminUid: filterAdminUid, cabang: filterCabang },
                totalPelangganScanned,
                totalAdminScanned: adminUidsToScan.size,
                orphanFound: orphans.length,
                deletedCount,
                deleteErrors,
                orphans
            };

            console.log(`✅ SWEEP SELESAI - hantu: ${orphans.length}, dihapus: ${deletedCount}, dryRun: ${dryRun}`);
            res.json(report);

        } catch (error) {
            console.error(`❌ SWEEP ERROR: ${error.message}`);
            res.status(500).json({
                success: false,
                error: error.message
            });
        }
    });
