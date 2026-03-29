// =========================================================================
// auditDataSampah.js
// =========================================================================
// Cloud Function untuk AUDIT data RTDB secara menyeluruh.
// Mendeteksi:
//   1. Nasabah duplikat (NIK sama, aktif >1)
//   2. Nasabah tanpa nama
//   3. Nasabah dengan data tidak lengkap
//   4. NIK dummy/palsu (000..., 111..., pola berulang)
//   5. NIK tidak valid (bukan 16 digit angka, atau berisi 2 NIK)
//   6. NIK sama tapi nama berbeda (kemungkinan salah input)
//   7. Nasabah aktif tapi besarPinjaman = 0
//   8. Nasabah tanpa status
//   9. Nasabah aktif di >1 admin berbeda (cross-resort)
//  10. NIK Registry inkonsisten (orphan / nama beda dengan pelanggan)
//
// Endpoint: GET /auditDataSampah
// Response: JSON laporan lengkap semua temuan
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

// Helper: normalisasi nama untuk perbandingan
function normalizeName(name) {
    return (name || '').trim().toUpperCase().replace(/\s+/g, ' ');
}

// Helper: cek apakah NIK dummy/palsu
function isDummyNik(nik) {
    if (!nik) return false;
    const cleaned = nik.replace(/\s/g, '');
    // Semua angka sama (0000..., 1111..., dll)
    if (new Set(cleaned).size <= 2) return true;
    // Dimulai dengan 000000
    if (cleaned.startsWith('000000')) return true;
    // Pola berulang sederhana (1212121212121212, 6464664646464664, dll)
    if (cleaned.length === 16) {
        const half = cleaned.substring(0, 8);
        if (cleaned === half + half) return true;
    }
    return false;
}

// Helper: cek apakah NIK valid (16 digit angka)
function isValidNik(nik) {
    if (!nik) return false;
    const cleaned = nik.trim();
    return /^\d{16}$/.test(cleaned);
}

// Helper: cek apakah field berisi 2 NIK (pola "NIK1 & NIK2")
function containsMultipleNik(nik) {
    if (!nik) return false;
    return nik.includes('&') || nik.includes('/') || nik.trim().length > 20;
}

exports.auditDataSampah = functions
    .runWith({ timeoutSeconds: 540, memory: '1GB' })
    .https.onRequest(async (req, res) => {

    console.log('🔍 =====================================');
    console.log('🔍 AUDIT DATA SAMPAH - MULAI');
    console.log('🔍 =====================================');

    try {
        // =============================================
        // STEP 1: Load semua data yang dibutuhkan
        // =============================================
        const [adminsSnap, pelangganSnap, nikRegistrySnap] = await Promise.all([
            db.ref('metadata/admins').once('value'),
            db.ref('pelanggan').once('value'),
            db.ref('nik_registry').once('value')
        ]);

        const adminsData = adminsSnap.val() || {};
        const pelangganData = pelangganSnap.val() || {};
        const nikRegistry = nikRegistrySnap.val() || {};

        // Kumpulkan semua nasabah ke flat array
        const allNasabah = [];
        for (const [adminUid, nasabahMap] of Object.entries(pelangganData)) {
            if (!nasabahMap || typeof nasabahMap !== 'object') continue;
            const adminInfo = adminsData[adminUid] || {};
            const adminName = adminInfo.name || adminInfo.email || adminUid;
            const cabang = adminInfo.cabang || '';

            for (const [nasabahId, nasabah] of Object.entries(nasabahMap)) {
                if (!nasabah || typeof nasabah !== 'object') continue;
                allNasabah.push({
                    ...nasabah,
                    _adminUid: adminUid,
                    _adminName: adminName,
                    _cabang: cabang,
                    _nasabahId: nasabahId,
                    _path: `pelanggan/${adminUid}/${nasabahId}`
                });
            }
        }

        console.log(`📊 Total nasabah loaded: ${allNasabah.length}`);

        // Buat lookup by nasabahId
        const nasabahById = {};
        for (const n of allNasabah) {
            nasabahById[n._nasabahId] = n;
        }

        // =============================================
        // AUDIT 1: Duplikat NIK (>1 entry aktif)
        // =============================================
        const nikMap = {};
        for (const n of allNasabah) {
            const nik = (n.nik || '').trim();
            if (nik && nik.length >= 10 && !containsMultipleNik(nik)) {
                if (!nikMap[nik]) nikMap[nik] = [];
                nikMap[nik].push(n);
            }
        }

        const duplikatNik = [];
        for (const [nik, entries] of Object.entries(nikMap)) {
            if (entries.length <= 1) continue;
            const aktif = entries.filter(e => {
                const s = (e.status || '').toLowerCase();
                return s === 'aktif' || s === 'disetujui' || s === 'menunggu approval';
            });

            duplikatNik.push({
                nik,
                totalEntries: entries.length,
                aktifCount: aktif.length,
                entries: entries.map(e => ({
                    path: e._path,
                    namaKtp: e.namaKtp || '',
                    namaPanggilan: e.namaPanggilan || '',
                    status: e.status || '',
                    admin: e._adminName,
                    cabang: e._cabang,
                    besarPinjaman: e.besarPinjaman || 0,
                    pinjamanKe: e.pinjamanKe || 1,
                    tanggalPengajuan: e.tanggalPengajuan || ''
                }))
            });
        }

        // =============================================
        // AUDIT 2: Nasabah tanpa nama
        // =============================================
        const tanpaNama = allNasabah
            .filter(n => !((n.namaKtp || '').trim()) && !((n.namaPanggilan || '').trim()))
            .map(n => ({
                path: n._path,
                nik: n.nik || '',
                status: n.status || '',
                admin: n._adminName,
                besarPinjaman: n.besarPinjaman || 0
            }));

        // =============================================
        // AUDIT 3: Data tidak lengkap
        // =============================================
        const requiredFields = [
            'namaKtp', 'nik', 'alamatKtp', 'cabangId',
            'besarPinjaman', 'status', 'tanggalPengajuan', 'tenor'
        ];

        const dataTidakLengkap = [];
        for (const n of allNasabah) {
            const missingFields = [];
            for (const field of requiredFields) {
                const val = n[field];
                if (val === undefined || val === null || val === '') {
                    missingFields.push(field);
                } else if ((field === 'besarPinjaman' || field === 'tenor') && val === 0) {
                    missingFields.push(field);
                }
            }
            if (missingFields.length > 0) {
                dataTidakLengkap.push({
                    path: n._path,
                    namaKtp: n.namaKtp || '',
                    status: n.status || '',
                    admin: n._adminName,
                    missingFields
                });
            }
        }

        // =============================================
        // AUDIT 4: NIK dummy/palsu
        // =============================================
        const nikDummy = allNasabah
            .filter(n => isDummyNik((n.nik || '').trim()))
            .map(n => ({
                path: n._path,
                nik: n.nik || '',
                namaKtp: n.namaKtp || '',
                status: n.status || '',
                admin: n._adminName,
                cabang: n._cabang
            }));

        // =============================================
        // AUDIT 5: NIK tidak valid (bukan 16 digit / berisi 2 NIK)
        // =============================================
        const nikTidakValid = allNasabah
            .filter(n => {
                const nik = (n.nik || '').trim();
                if (!nik) return false; // sudah ditangani di "tidak lengkap"
                return !isValidNik(nik);
            })
            .map(n => ({
                path: n._path,
                nik: n.nik || '',
                namaKtp: n.namaKtp || '',
                status: n.status || '',
                admin: n._adminName,
                masalah: containsMultipleNik((n.nik || '').trim())
                    ? 'Berisi 2 NIK (suami & istri?)'
                    : `Panjang ${(n.nik || '').trim().length} digit (harus 16)`
            }));

        // =============================================
        // AUDIT 6: NIK sama tapi nama berbeda
        // =============================================
        const nikNamaBerbeda = [];
        for (const [nik, entries] of Object.entries(nikMap)) {
            if (entries.length <= 1) continue;
            const uniqueNames = new Set();
            for (const e of entries) {
                const name = normalizeName(e.namaKtp);
                if (name) uniqueNames.add(name);
            }
            if (uniqueNames.size > 1) {
                nikNamaBerbeda.push({
                    nik,
                    namaBerbeda: [...uniqueNames],
                    entries: entries.map(e => ({
                        path: e._path,
                        namaKtp: e.namaKtp || '',
                        status: e.status || '',
                        admin: e._adminName
                    }))
                });
            }
        }

        // =============================================
        // AUDIT 7: Aktif tapi besarPinjaman = 0
        // =============================================
        const aktifTanpaPinjaman = allNasabah
            .filter(n => (n.status || '').toLowerCase() === 'aktif' && (!n.besarPinjaman || n.besarPinjaman === 0))
            .map(n => ({
                path: n._path,
                namaKtp: n.namaKtp || '',
                admin: n._adminName
            }));

        // =============================================
        // AUDIT 8: Nasabah tanpa status
        // =============================================
        const tanpaStatus = allNasabah
            .filter(n => !(n.status || '').trim())
            .map(n => ({
                path: n._path,
                namaKtp: n.namaKtp || '',
                nik: n.nik || '',
                admin: n._adminName
            }));

        // =============================================
        // AUDIT 9: Nasabah aktif di >1 admin berbeda
        // =============================================
        const crossAdmin = [];
        for (const [nik, entries] of Object.entries(nikMap)) {
            const aktif = entries.filter(e => (e.status || '').toLowerCase() === 'aktif');
            if (aktif.length < 2) continue;
            const uniqueAdmins = new Set(aktif.map(e => e._adminUid));
            if (uniqueAdmins.size >= 2) {
                crossAdmin.push({
                    nik,
                    namaKtp: aktif[0].namaKtp || '',
                    jumlahAdmin: uniqueAdmins.size,
                    entries: aktif.map(e => ({
                        path: e._path,
                        admin: e._adminName,
                        cabang: e._cabang,
                        besarPinjaman: e.besarPinjaman || 0,
                        pinjamanKe: e.pinjamanKe || 1
                    }))
                });
            }
        }

        // =============================================
        // AUDIT 10: NIK Registry inkonsisten
        // =============================================
        let registryOrphan = 0;
        let registryNamaBeda = 0;
        const registryOrphanList = [];
        const registryNamaBedaList = [];

        for (const [nik, reg] of Object.entries(nikRegistry)) {
            if (!reg || typeof reg !== 'object') continue;
            const pid = reg.pelangganId || '';
            const regNama = normalizeName(reg.nama);

            if (pid && !nasabahById[pid]) {
                registryOrphan++;
                if (registryOrphanList.length < 20) {
                    registryOrphanList.push({
                        nik,
                        pelangganId: pid,
                        namaRegistry: reg.nama || '',
                        adminName: reg.adminName || ''
                    });
                }
            } else if (pid && nasabahById[pid]) {
                const pel = nasabahById[pid];
                const pelNama = normalizeName(pel.namaKtp || pel.namaPanggilan);
                if (regNama && pelNama && regNama !== pelNama) {
                    registryNamaBeda++;
                    if (registryNamaBedaList.length < 20) {
                        registryNamaBedaList.push({
                            nik,
                            pelangganId: pid,
                            namaRegistry: reg.nama || '',
                            namaPelanggan: pel.namaKtp || pel.namaPanggilan || '',
                            path: pel._path
                        });
                    }
                }
            }
        }

        // =============================================
        // COMPILE REPORT
        // =============================================
        const report = {
            success: true,
            timestamp: new Date().toISOString(),
            totalNasabah: allNasabah.length,
            ringkasan: {
                duplikatNik: duplikatNik.length,
                tanpaNama: tanpaNama.length,
                dataTidakLengkap: dataTidakLengkap.length,
                nikDummy: nikDummy.length,
                nikTidakValid: nikTidakValid.length,
                nikNamaBerbeda: nikNamaBerbeda.length,
                aktifTanpaPinjaman: aktifTanpaPinjaman.length,
                tanpaStatus: tanpaStatus.length,
                crossAdmin: crossAdmin.length,
                registryOrphan,
                registryNamaBeda,
                totalMasalah: duplikatNik.length + tanpaNama.length +
                    dataTidakLengkap.length + nikDummy.length +
                    nikTidakValid.length + nikNamaBerbeda.length +
                    aktifTanpaPinjaman.length + tanpaStatus.length +
                    crossAdmin.length + registryOrphan + registryNamaBeda
            },
            detail: {
                '1_duplikatNik': {
                    deskripsi: 'NIK yang muncul lebih dari 1x di database',
                    total: duplikatNik.length,
                    data: duplikatNik
                },
                '2_tanpaNama': {
                    deskripsi: 'Nasabah tanpa namaKtp dan namaPanggilan',
                    total: tanpaNama.length,
                    data: tanpaNama
                },
                '3_dataTidakLengkap': {
                    deskripsi: 'Nasabah dengan field wajib kosong',
                    total: dataTidakLengkap.length,
                    data: dataTidakLengkap
                },
                '4_nikDummy': {
                    deskripsi: 'NIK palsu/dummy (000..., 111..., pola berulang)',
                    total: nikDummy.length,
                    data: nikDummy
                },
                '5_nikTidakValid': {
                    deskripsi: 'NIK bukan 16 digit angka (termasuk yang berisi 2 NIK)',
                    total: nikTidakValid.length,
                    data: nikTidakValid
                },
                '6_nikNamaBerbeda': {
                    deskripsi: 'NIK sama tapi nama nasabah berbeda',
                    total: nikNamaBerbeda.length,
                    data: nikNamaBerbeda
                },
                '7_aktifTanpaPinjaman': {
                    deskripsi: 'Status Aktif tapi besarPinjaman = 0',
                    total: aktifTanpaPinjaman.length,
                    data: aktifTanpaPinjaman
                },
                '8_tanpaStatus': {
                    deskripsi: 'Nasabah tanpa field status',
                    total: tanpaStatus.length,
                    data: tanpaStatus
                },
                '9_crossAdmin': {
                    deskripsi: 'Nasabah aktif di lebih dari 1 admin/resort',
                    total: crossAdmin.length,
                    data: crossAdmin
                },
                '10_registryInkonsisten': {
                    deskripsi: 'NIK Registry tidak sinkron dengan data pelanggan',
                    orphan: {
                        total: registryOrphan,
                        deskripsi: 'Registry menunjuk ke pelanggan yang tidak ada',
                        sample: registryOrphanList
                    },
                    namaBeda: {
                        total: registryNamaBeda,
                        deskripsi: 'Nama di registry berbeda dengan nama di pelanggan',
                        sample: registryNamaBedaList
                    }
                }
            }
        };

        console.log('✅ Audit selesai');
        console.log(`📊 Total masalah ditemukan: ${report.ringkasan.totalMasalah}`);

        res.json(report);

    } catch (error) {
        console.error('❌ Audit error:', error);
        res.status(500).json({
            success: false,
            error: error.message,
            stack: error.stack
        });
    }
});
