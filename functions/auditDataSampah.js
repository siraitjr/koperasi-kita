// =========================================================================
// auditDataSampah.js
// =========================================================================
// Cloud Function untuk AUDIT data RTDB PER ADMIN LAPANGAN.
//
// PENTING: Pengecekan duplikat hanya dalam 1 admin yang sama.
// Nasabah yang sama di beda admin/resort dalam 1 cabang itu NORMAL.
//
// Mendeteksi:
//   1. Duplikat NIK dalam 1 admin yang sama
//   2. Nasabah tanpa nama / nama hanya titik/karakter sampah
//   3. Data rusak / ghost data (field sangat sedikit, bukan data nasabah)
//   4. NIK dummy/palsu (000..., 111..., pola berulang)
//   5. NIK tidak valid (bukan 16 digit angka, atau berisi 2 NIK)
//   6. NIK sama tapi nama berbeda dalam 1 admin
//   7. Nasabah aktif tapi besarPinjaman = 0 / tanpa status
//
// Endpoint: GET /auditDataSampah
// Query params (optional):
//   ?adminUid=xxx  → audit 1 admin saja
//   ?cabang=panti  → audit semua admin di 1 cabang
//   (tanpa param)  → audit semua admin
//
// Response: JSON laporan lengkap per admin
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

// Helper: normalisasi nama untuk perbandingan
function normalizeName(name) {
    return (name || '').trim().toUpperCase().replace(/\s+/g, ' ');
}

// Helper: cek apakah string adalah "sampah" (hanya titik, dash, koma, spasi, 1-2 huruf)
function isGarbageString(val) {
    if (!val) return false;
    const trimmed = val.trim();
    if (!trimmed) return true;
    // Hanya titik, dash, koma, spasi, underscore
    if (/^[.\-,_\s]+$/.test(trimmed)) return true;
    // Hanya 1-2 karakter yang bukan kata bermakna
    const lettersOnly = trimmed.replace(/[^a-zA-Z]/g, '');
    if (lettersOnly.length <= 1) return true;
    return false;
}

// Helper: cek apakah NIK dummy/palsu
function isDummyNik(nik) {
    if (!nik) return false;
    const cleaned = nik.replace(/\s/g, '');
    if (!cleaned) return false;
    // Semua angka sama atau hanya 2 jenis digit (0000..., 1111..., 1212...)
    if (new Set(cleaned).size <= 2) return true;
    // Dimulai dengan 000000
    if (cleaned.startsWith('000000')) return true;
    // Pola berulang (12121212... , 64646464...)
    if (cleaned.length === 16) {
        const half = cleaned.substring(0, 8);
        if (cleaned === half + half) return true;
        const quarter = cleaned.substring(0, 4);
        if (cleaned === quarter.repeat(4)) return true;
    }
    return false;
}

// Helper: cek apakah NIK valid (16 digit angka)
function isValidNik(nik) {
    if (!nik) return false;
    return /^\d{16}$/.test(nik.trim());
}

// Helper: cek apakah field berisi 2 NIK (pola "NIK1 & NIK2")
function containsMultipleNik(nik) {
    if (!nik) return false;
    return nik.includes('&') || (nik.trim().length > 20 && /\d{16}/.test(nik));
}

// Helper: cek apakah data ini "ghost" / rusak (field sangat sedikit)
function isGhostData(nasabah) {
    const essentialFields = [
        'namaKtp', 'namaPanggilan', 'nik', 'alamatKtp',
        'besarPinjaman', 'tenor', 'tanggalPengajuan'
    ];
    const presentCount = essentialFields.filter(f => {
        const val = nasabah[f];
        return val !== undefined && val !== null && val !== '' && val !== 0;
    }).length;
    // Kalau kurang dari 3 field esensial terisi, ini ghost data
    return presentCount < 3;
}

// =============================================
// Audit 1 admin lapangan
// =============================================
function auditSatuAdmin(adminUid, adminName, cabang, nasabahMap) {
    const entries = Object.entries(nasabahMap || {});
    const issues = [];

    // Kumpulkan NIK map untuk admin ini saja
    const nikMapLocal = {};

    for (const [nasabahId, nas] of entries) {
        if (!nas || typeof nas !== 'object') {
            issues.push({
                kategori: 'data_rusak',
                path: `pelanggan/${adminUid}/${nasabahId}`,
                masalah: `Data bukan object (tipe: ${typeof nas})`,
                severity: 'tinggi'
            });
            continue;
        }

        const namaKtp = (nas.namaKtp || '').trim();
        const namaPanggilan = (nas.namaPanggilan || '').trim();
        const nik = (nas.nik || '').trim();
        const status = (nas.status || '').trim();
        const path = `pelanggan/${adminUid}/${nasabahId}`;

        // --- Ghost data / data rusak (sangat sedikit field) ---
        if (isGhostData(nas)) {
            const fieldKeys = Object.keys(nas).filter(k => !k.startsWith('_'));
            issues.push({
                kategori: 'ghost_data',
                path,
                namaKtp: namaKtp || null,
                status: status || null,
                masalah: `Hanya ${fieldKeys.length} field. Data tidak lengkap seperti nasabah lain`,
                fieldYangAda: fieldKeys,
                severity: 'tinggi'
            });
            continue; // Ghost data sudah pasti bermasalah, skip cek lain
        }

        // --- Tanpa nama atau nama sampah ---
        if (!namaKtp && !namaPanggilan) {
            issues.push({
                kategori: 'tanpa_nama',
                path,
                nik: nik || null,
                status: status || null,
                masalah: 'Tidak punya namaKtp maupun namaPanggilan',
                severity: 'tinggi'
            });
        } else if (isGarbageString(namaKtp) && isGarbageString(namaPanggilan)) {
            issues.push({
                kategori: 'nama_sampah',
                path,
                namaKtp: namaKtp || null,
                namaPanggilan: namaPanggilan || null,
                nik: nik || null,
                status: status || null,
                masalah: 'Nama hanya berisi titik/karakter tidak bermakna',
                severity: 'tinggi'
            });
        }

        // --- NIK: dummy, tidak valid, atau berisi 2 NIK ---
        if (!nik) {
            issues.push({
                kategori: 'tanpa_nik',
                path,
                namaKtp: namaKtp || namaPanggilan || null,
                status: status || null,
                masalah: 'Tidak memiliki NIK',
                severity: 'sedang'
            });
        } else if (containsMultipleNik(nik)) {
            issues.push({
                kategori: 'nik_ganda',
                path,
                nik,
                namaKtp: namaKtp || null,
                status: status || null,
                masalah: 'Field NIK berisi 2 NIK (kemungkinan suami & istri digabung)',
                severity: 'sedang'
            });
        } else if (isDummyNik(nik)) {
            issues.push({
                kategori: 'nik_dummy',
                path,
                nik,
                namaKtp: namaKtp || null,
                status: status || null,
                masalah: 'NIK palsu/dummy (pola angka tidak wajar)',
                severity: 'sedang'
            });
        } else if (!isValidNik(nik)) {
            issues.push({
                kategori: 'nik_tidak_valid',
                path,
                nik,
                namaKtp: namaKtp || null,
                status: status || null,
                masalah: `NIK ${nik.length} digit (seharusnya 16 digit angka)`,
                severity: 'sedang'
            });
        }

        // --- Tanpa status ---
        if (!status) {
            issues.push({
                kategori: 'tanpa_status',
                path,
                namaKtp: namaKtp || namaPanggilan || null,
                masalah: 'Tidak memiliki field status',
                severity: 'sedang'
            });
        }

        // --- Aktif tapi pinjaman 0 ---
        if (status.toLowerCase() === 'aktif' && (!nas.besarPinjaman || nas.besarPinjaman === 0)) {
            issues.push({
                kategori: 'aktif_tanpa_pinjaman',
                path,
                namaKtp: namaKtp || namaPanggilan || null,
                masalah: 'Status Aktif tapi besarPinjaman = 0',
                severity: 'sedang'
            });
        }

        // --- Kumpulkan untuk cek duplikat NIK ---
        if (nik && nik.length >= 10 && !containsMultipleNik(nik) && !isDummyNik(nik)) {
            if (!nikMapLocal[nik]) nikMapLocal[nik] = [];
            nikMapLocal[nik].push({ nasabahId, namaKtp, namaPanggilan, status, path, nas });
        }
    }

    // --- Duplikat NIK dalam admin ini ---
    for (const [nik, nikEntries] of Object.entries(nikMapLocal)) {
        if (nikEntries.length <= 1) continue;

        // Cek apakah nama juga berbeda
        const uniqueNames = new Set(nikEntries.map(e => normalizeName(e.namaKtp)).filter(n => n));
        const namaBerbeda = uniqueNames.size > 1;

        // Hitung info perbandingan untuk bantu user memutuskan mana yang dihapus
        const perbandingan = nikEntries.map(e => {
            // Hitung total pembayaran
            let totalDibayar = 0;
            let jumlahPembayaran = 0;
            if (e.nas.pembayaranList) {
                const pList = Array.isArray(e.nas.pembayaranList)
                    ? e.nas.pembayaranList
                    : Object.values(e.nas.pembayaranList || {});
                jumlahPembayaran = pList.length;
                pList.forEach(p => {
                    totalDibayar += p.jumlah || 0;
                    if (p.subPembayaran) {
                        const subList = Array.isArray(p.subPembayaran)
                            ? p.subPembayaran
                            : Object.values(p.subPembayaran || {});
                        subList.forEach(sub => { totalDibayar += sub.jumlah || 0; });
                    }
                });
            }

            return {
                path: e.path,
                nasabahId: e.nasabahId,
                namaKtp: e.namaKtp || '',
                namaPanggilan: e.namaPanggilan || '',
                status: e.status || '',
                besarPinjaman: e.nas.besarPinjaman || 0,
                totalPelunasan: e.nas.totalPelunasan || 0,
                totalDibayar,
                sisaUtang: Math.max(0, (e.nas.totalPelunasan || 0) - totalDibayar),
                jumlahPembayaran,
                pinjamanKe: e.nas.pinjamanKe || 1,
                tanggalPengajuan: e.nas.tanggalPengajuan || '',
                alamatKtp: e.nas.alamatKtp || '',
                noHp: e.nas.noHp || ''
            };
        });

        issues.push({
            kategori: namaBerbeda ? 'duplikat_nik_nama_beda' : 'duplikat_nik',
            nik,
            masalah: namaBerbeda
                ? `NIK sama (${nikEntries.length}x) tapi nama berbeda: ${[...uniqueNames].join(' vs ')}`
                : `NIK sama muncul ${nikEntries.length}x dalam admin ini`,
            severity: namaBerbeda ? 'tinggi' : 'sedang',
            catatan: 'Silakan bandingkan data di bawah, lalu hapus yang salah secara manual',
            perbandingan
        });
    }

    return {
        adminUid,
        adminName,
        cabang,
        totalNasabah: entries.length,
        totalMasalah: issues.length,
        masalahTinggi: issues.filter(i => i.severity === 'tinggi').length,
        masalahSedang: issues.filter(i => i.severity === 'sedang').length,
        issues
    };
}

// =============================================
// MAIN ENDPOINT
// =============================================
exports.auditDataSampah = functions
    .runWith({ timeoutSeconds: 540, memory: '1GB' })
    .https.onRequest(async (req, res) => {

    console.log('🔍 AUDIT DATA SAMPAH - MULAI');

    try {
        const filterAdminUid = req.query.adminUid || null;
        const filterCabang = req.query.cabang || null;

        // Load data
        const [adminsSnap, pelangganSnap] = await Promise.all([
            db.ref('metadata/admins').once('value'),
            db.ref('pelanggan').once('value')
        ]);

        const adminsData = adminsSnap.val() || {};
        const pelangganData = pelangganSnap.val() || {};

        // Tentukan admin mana yang diaudit
        const adminUidsToAudit = [];
        for (const [uid, info] of Object.entries(adminsData)) {
            if (info.role !== 'admin') continue;
            if (filterAdminUid && uid !== filterAdminUid) continue;
            if (filterCabang && (info.cabang || '').toLowerCase() !== filterCabang.toLowerCase()) continue;
            adminUidsToAudit.push(uid);
        }

        // Jalankan audit per admin
        const hasilPerAdmin = [];
        let grandTotalNasabah = 0;
        let grandTotalMasalah = 0;

        // Ringkasan per kategori
        const ringkasanKategori = {};

        for (const adminUid of adminUidsToAudit) {
            const adminInfo = adminsData[adminUid] || {};
            const adminName = adminInfo.name || adminInfo.email || adminUid;
            const cabang = adminInfo.cabang || '';
            const nasabahMap = pelangganData[adminUid] || {};

            const hasil = auditSatuAdmin(adminUid, adminName, cabang, nasabahMap);
            hasilPerAdmin.push(hasil);

            grandTotalNasabah += hasil.totalNasabah;
            grandTotalMasalah += hasil.totalMasalah;

            // Hitung per kategori
            for (const issue of hasil.issues) {
                const kat = issue.kategori;
                ringkasanKategori[kat] = (ringkasanKategori[kat] || 0) + 1;
            }
        }

        // Sort: admin dengan masalah terbanyak di atas
        hasilPerAdmin.sort((a, b) => b.totalMasalah - a.totalMasalah);

        const report = {
            success: true,
            timestamp: new Date().toISOString(),
            filter: {
                adminUid: filterAdminUid || 'semua',
                cabang: filterCabang || 'semua'
            },
            ringkasan: {
                totalAdminDiaudit: adminUidsToAudit.length,
                totalNasabah: grandTotalNasabah,
                totalMasalah: grandTotalMasalah,
                perKategori: ringkasanKategori
            },
            hasilPerAdmin
        };

        console.log(`✅ Audit selesai: ${grandTotalMasalah} masalah dari ${grandTotalNasabah} nasabah`);
        res.json(report);

    } catch (error) {
        console.error('❌ Audit error:', error);
        res.status(500).json({
            success: false,
            error: error.message
        });
    }
});
