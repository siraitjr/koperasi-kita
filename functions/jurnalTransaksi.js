// functions/jurnalTransaksi.js
// =========================================================================
// JURNAL TRANSAKSI - Pencatatan Permanen untuk Pembukuan Profesional
// =========================================================================
//
// Node Firebase: /jurnal_transaksi/{cabangId}/{tahun-bulan}/{autoId}
//
// PRINSIP:
// 1. IMMUTABLE - Sekali ditulis, tidak boleh dihapus/diubah
// 2. ADDITIVE - Hanya menambah, tidak mengganggu sistem existing
// 3. AUDIT TRAIL - Setiap transaksi tercatat dengan timestamp & admin info
//
// TIPE TRANSAKSI:
// - pembayaran_cicilan   : Bayar cicilan harian
// - tambah_bayar         : Sub-pembayaran (tambah bayar)
// - pencairan_pinjaman   : Pencairan pinjaman baru/lanjut
// - pelunasan_sisa_utang : Pelunasan sisa utang dari top-up
// - lunas                : Marker saat nasabah lunas cicilan
// =========================================================================

const admin = require('firebase-admin');
const db = admin.database();

const { getTodayIndonesia } = require('./summaryHelpers');

// =========================================================================
// HELPER: Format bulan untuk path jurnal (contoh: "2026-03")
// =========================================================================
function getYearMonthWIB() {
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(now.getTime() + wibOffset);
    const year = wibDate.getUTCFullYear();
    const month = (wibDate.getUTCMonth() + 1).toString().padStart(2, '0');
    return `${year}-${month}`;
}

// =========================================================================
// CORE: Tulis satu entry jurnal transaksi
// =========================================================================
// Path: /jurnal_transaksi/{cabangId}/{tahun-bulan}/{autoId}
//
// Fungsi ini TIDAK melempar error ke caller — jika gagal, hanya log.
// Ini agar kegagalan jurnal TIDAK mengganggu proses pembayaran utama.
// =========================================================================
async function writeJurnalEntry(params) {
    const {
        cabangId,
        tipe,              // 'pembayaran_cicilan' | 'tambah_bayar' | 'pencairan_pinjaman' | 'pelunasan_sisa_utang' | 'lunas'
        pelangganId,
        namaPelanggan,
        namaKtp,
        adminUid,
        adminName,
        jumlah,
        tanggal,           // Format: "25 Mar 2026"
        pinjamanKe,
        sisaUtangSetelah,  // Sisa utang setelah transaksi ini
        totalPelunasan,    // Total pelunasan (utang keseluruhan)
        totalDibayar,      // Total yang sudah dibayar sampai saat ini
        keterangan,        // Keterangan tambahan (opsional)
    } = params;

    try {
        const yearMonth = getYearMonthWIB();

        const entry = {
            tipe: tipe,
            pelangganId: pelangganId || '',
            namaPelanggan: namaPelanggan || '',
            namaKtp: namaKtp || '',
            adminUid: adminUid || '',
            adminName: adminName || '',
            jumlah: jumlah || 0,
            tanggal: tanggal || getTodayIndonesia(),
            pinjamanKe: pinjamanKe || 1,
            sisaUtangSetelah: sisaUtangSetelah || 0,
            totalPelunasan: totalPelunasan || 0,
            totalDibayar: totalDibayar || 0,
            keterangan: keterangan || '',
            timestamp: admin.database.ServerValue.TIMESTAMP,
            createdAt: new Date().toISOString()
        };

        await db.ref(`jurnal_transaksi/${cabangId}/${yearMonth}`).push(entry);

        console.log(`[JURNAL] ${tipe}: ${namaPelanggan} - Rp ${jumlah} (${cabangId}/${yearMonth})`);

    } catch (error) {
        // PENTING: Hanya log, JANGAN throw — agar tidak mengganggu proses utama
        console.error(`[JURNAL] ERROR writing entry: ${error.message}`, {
            tipe, pelangganId, jumlah, cabangId
        });
    }
}

// =========================================================================
// HELPER: Hitung total dibayar dari pembayaranList
// (Duplikasi dari bukuPokokApi — sengaja agar modul ini independen)
// =========================================================================
function calculateTotalDibayar(pembayaranList) {
    let total = 0;
    if (!pembayaranList) return 0;

    const list = Array.isArray(pembayaranList)
        ? pembayaranList
        : Object.values(pembayaranList || {});

    list.forEach(p => {
        if (!p) return;
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
// PUBLIC: Catat pembayaran cicilan ke jurnal
// Dipanggil dari onPembayaranAdded.js
// =========================================================================
async function catatPembayaranCicilan(adminUid, pelangganId, pembayaran, pelanggan, adminData) {
    const cabangId = adminData?.cabang;
    if (!cabangId) return;

    const totalDibayar = calculateTotalDibayar(pelanggan.pembayaranList);
    const totalPelunasan = pelanggan.totalPelunasan || 0;
    const sisaUtang = Math.max(0, totalPelunasan - totalDibayar);

    await writeJurnalEntry({
        cabangId,
        tipe: 'pembayaran_cicilan',
        pelangganId,
        namaPelanggan: pelanggan.namaPanggilan || '',
        namaKtp: pelanggan.namaKtp || '',
        adminUid,
        adminName: adminData.name || adminData.email || '',
        jumlah: pembayaran.jumlah || 0,
        tanggal: pembayaran.tanggal || getTodayIndonesia(),
        pinjamanKe: pelanggan.pinjamanKe || 1,
        sisaUtangSetelah: sisaUtang,
        totalPelunasan,
        totalDibayar,
        keterangan: `Cicilan harian`
    });
}

// =========================================================================
// PUBLIC: Catat sub-pembayaran (tambah bayar) ke jurnal
// =========================================================================
async function catatTambahBayar(adminUid, pelangganId, subPembayaran, pelanggan, adminData) {
    const cabangId = adminData?.cabang;
    if (!cabangId) return;

    const totalDibayar = calculateTotalDibayar(pelanggan.pembayaranList);
    const totalPelunasan = pelanggan.totalPelunasan || 0;
    const sisaUtang = Math.max(0, totalPelunasan - totalDibayar);

    await writeJurnalEntry({
        cabangId,
        tipe: 'tambah_bayar',
        pelangganId,
        namaPelanggan: pelanggan.namaPanggilan || '',
        namaKtp: pelanggan.namaKtp || '',
        adminUid,
        adminName: adminData.name || adminData.email || '',
        jumlah: subPembayaran.jumlah || 0,
        tanggal: subPembayaran.tanggal || getTodayIndonesia(),
        pinjamanKe: pelanggan.pinjamanKe || 1,
        sisaUtangSetelah: sisaUtang,
        totalPelunasan,
        totalDibayar,
        keterangan: 'Tambah bayar'
    });
}

// =========================================================================
// PUBLIC: Catat pencairan pinjaman ke jurnal
// =========================================================================
async function catatPencairanPinjaman(adminUid, pelangganId, pelanggan, adminData, jumlahDicairkan) {
    const cabangId = adminData?.cabang;
    if (!cabangId) return;

    const totalPelunasan = pelanggan.totalPelunasan || 0;

    await writeJurnalEntry({
        cabangId,
        tipe: 'pencairan_pinjaman',
        pelangganId,
        namaPelanggan: pelanggan.namaPanggilan || '',
        namaKtp: pelanggan.namaKtp || '',
        adminUid,
        adminName: adminData.name || adminData.email || '',
        jumlah: jumlahDicairkan,
        tanggal: getTodayIndonesia(),
        pinjamanKe: pelanggan.pinjamanKe || 1,
        sisaUtangSetelah: totalPelunasan,
        totalPelunasan,
        totalDibayar: 0,
        keterangan: pelanggan.pinjamanKe > 1
            ? `Pencairan pinjaman ke-${pelanggan.pinjamanKe} (lanjut)`
            : 'Pencairan pinjaman baru'
    });
}

// =========================================================================
// PUBLIC: Catat pelunasan sisa utang lama (dari top-up)
// =========================================================================
async function catatPelunasanSisaUtang(adminUid, pelangganId, pelanggan, adminData, sisaUtangLama) {
    const cabangId = adminData?.cabang;
    if (!cabangId) return;

    await writeJurnalEntry({
        cabangId,
        tipe: 'pelunasan_sisa_utang',
        pelangganId,
        namaPelanggan: pelanggan.namaPanggilan || '',
        namaKtp: pelanggan.namaKtp || '',
        adminUid,
        adminName: adminData.name || adminData.email || '',
        jumlah: sisaUtangLama,
        tanggal: getTodayIndonesia(),
        pinjamanKe: pelanggan.pinjamanKe || 1,
        sisaUtangSetelah: 0,
        totalPelunasan: pelanggan.totalPelunasan || 0,
        totalDibayar: sisaUtangLama,
        keterangan: `Pelunasan sisa utang pinjaman sebelumnya`
    });
}

// =========================================================================
// PUBLIC: Catat marker lunas
// =========================================================================
async function catatLunas(adminUid, pelangganId, pelanggan, adminData) {
    const cabangId = adminData?.cabang;
    if (!cabangId) return;

    const totalDibayar = calculateTotalDibayar(pelanggan.pembayaranList);
    const totalPelunasan = pelanggan.totalPelunasan || 0;

    await writeJurnalEntry({
        cabangId,
        tipe: 'lunas',
        pelangganId,
        namaPelanggan: pelanggan.namaPanggilan || '',
        namaKtp: pelanggan.namaKtp || '',
        adminUid,
        adminName: adminData.name || adminData.email || '',
        jumlah: 0,
        tanggal: getTodayIndonesia(),
        pinjamanKe: pelanggan.pinjamanKe || 1,
        sisaUtangSetelah: 0,
        totalPelunasan,
        totalDibayar,
        keterangan: `Nasabah lunas - pinjaman ke-${pelanggan.pinjamanKe || 1}`
    });
}

// =========================================================================
// EXPORTS
// =========================================================================
module.exports = {
    writeJurnalEntry,
    catatPembayaranCicilan,
    catatTambahBayar,
    catatPencairanPinjaman,
    catatPelunasanSisaUtang,
    catatLunas,
    calculateTotalDibayar,
    getYearMonthWIB
};
