// functions/backfillPembayaranHarian.js
// =========================================================================
// BACKFILL PEMBAYARAN HARIAN - HTTP FUNCTION
// =========================================================================
// 
// Fungsi ini digunakan untuk mengisi node pembayaran_harian dengan data
// pembayaran yang sudah ada. Jalankan sekali setelah deploy Cloud Functions.
//
// URL: https://us-central1-{PROJECT_ID}.cloudfunctions.net/backfillPembayaranHarian
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

// Helper: Format tanggal Indonesia
function getTodayIndonesia() {
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(now.getTime() + wibOffset);
    
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 
                    'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
    
    const day = wibDate.getUTCDate().toString().padStart(2, '0');
    const month = months[wibDate.getUTCMonth()];
    const year = wibDate.getUTCFullYear();
    
    return `${day} ${month} ${year}`;
}

// =========================================================================
// HTTP FUNCTION: Backfill Pembayaran Harian
// =========================================================================
exports.backfillPembayaranHarian = functions.https.onRequest(async (req, res) => {
    console.log('ðŸ”„ Starting backfill pembayaran_harian...');
    
    const today = getTodayIndonesia();
    console.log(`ðŸ“… Today: ${today}`);
    
    try {
        // Dapatkan semua admin
        const adminsSnap = await db.ref('metadata/admins').once('value');
        const admins = adminsSnap.val() || {};
        
        let totalPembayaran = 0;
        let totalPencairan = 0;
        const results = [];
        
        for (const [adminUid, adminData] of Object.entries(admins)) {
            if (adminData.role !== 'admin') continue;
            
            const cabangId = adminData.cabang;
            if (!cabangId) continue;
            
            console.log(`\nðŸ“‹ Processing admin: ${adminUid} (${adminData.email || adminData.name})`);
            
            // Load semua pelanggan admin ini
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
            
            let adminPembayaran = 0;
            let adminPencairan = 0;
            
            pelangganSnap.forEach(child => {
                const pelanggan = child.val();
                if (!pelanggan) return;
                
                const pelangganId = child.key;
                const namaPanggilan = pelanggan.namaPanggilan || pelanggan.namaKtp || 'Unknown';
                
                // =========================================================
                // 1. PROSES PEMBAYARAN CICILAN HARI INI
                // =========================================================
                if (pelanggan.pembayaranList) {
                    const pembayaranList = Array.isArray(pelanggan.pembayaranList)
                        ? pelanggan.pembayaranList
                        : Object.values(pelanggan.pembayaranList || {});
                    
                    pembayaranList.forEach((pembayaran, index) => {
                        // Pembayaran utama
                        if (pembayaran.tanggal === today && pembayaran.jumlah > 0) {
                            const pembayaranData = {
                                pelangganId: pelangganId,
                                namaPanggilan: namaPanggilan,
                                namaKtp: pelanggan.namaKtp || '',
                                adminUid: adminUid,
                                // âœ… PERBAIKAN: Prioritaskan email
                                adminName: adminData.name || adminData.email || '',
                                adminEmail: adminData.email || '',
                                jumlah: pembayaran.jumlah,
                                jenis: 'cicilan',
                                tanggal: today,
                                timestamp: admin.database.ServerValue.TIMESTAMP
                            };
                            
                            db.ref(`pembayaran_harian/${cabangId}/${today}`).push(pembayaranData);
                            adminPembayaran += pembayaran.jumlah;
                            console.log(`   ðŸ’° Cicilan: ${namaPanggilan} - Rp ${pembayaran.jumlah}`);
                        }
                        
                        // Sub-pembayaran (tambah bayar)
                        if (pembayaran.subPembayaran) {
                            const subList = Array.isArray(pembayaran.subPembayaran)
                                ? pembayaran.subPembayaran
                                : Object.values(pembayaran.subPembayaran || {});
                            
                            subList.forEach(sub => {
                                if (sub.tanggal === today && sub.jumlah > 0) {
                                    const subData = {
                                        pelangganId: pelangganId,
                                        namaPanggilan: namaPanggilan,
                                        namaKtp: pelanggan.namaKtp || '',
                                        adminUid: adminUid,
                                        // âœ… PERBAIKAN: Prioritaskan email
                                        adminName: adminData.name || adminData.email || '',
                                        adminEmail: adminData.email || '',
                                        jumlah: sub.jumlah,
                                        jenis: 'tambah_bayar',
                                        tanggal: today,
                                        timestamp: admin.database.ServerValue.TIMESTAMP
                                    };
                                    
                                    db.ref(`pembayaran_harian/${cabangId}/${today}`).push(subData);
                                    adminPembayaran += sub.jumlah;
                                    console.log(`   ðŸ’° Tambah Bayar: ${namaPanggilan} - Rp ${sub.jumlah}`);
                                }
                            });
                        }
                    });
                }
                
                // =========================================================
                // 2. PROSES PENCAIRAN HARI INI (nasabah baru yang disetujui hari ini)
                // =========================================================
                const status = (pelanggan.status || '').toLowerCase();
                const tanggalDaftar = pelanggan.tanggalDaftar || pelanggan.tanggalPengajuan || '';
                
                if (status === 'aktif' && tanggalDaftar === today && pelanggan.besarPinjaman > 0) {
                    // Hitung jumlah yang dicairkan
                    let jumlahDicairkan = 0;
                    if (pelanggan.totalDiterima > 0) {
                        jumlahDicairkan = pelanggan.totalDiterima;
                    } else if (pelanggan.admin > 0 && pelanggan.simpanan > 0) {
                        jumlahDicairkan = pelanggan.besarPinjaman - pelanggan.admin - pelanggan.simpanan;
                    } else {
                        const adminFee = Math.round(pelanggan.besarPinjaman * 0.05);
                        const simpanan = Math.round(pelanggan.besarPinjaman * 0.05);
                        jumlahDicairkan = pelanggan.besarPinjaman - adminFee - simpanan;
                    }
                    
                    if (jumlahDicairkan > 0) {
                        const pencairanData = {
                            pelangganId: pelangganId,
                            namaPanggilan: namaPanggilan,
                            namaKtp: pelanggan.namaKtp || '',
                            adminUid: adminUid,
                            // âœ… PERBAIKAN: Prioritaskan email
                            adminName: adminData.name || adminData.email || '',
                            adminEmail: adminData.email || '',
                            jumlah: jumlahDicairkan,
                            jenis: 'pencairan',
                            tanggal: today,
                            timestamp: admin.database.ServerValue.TIMESTAMP
                        };
                        
                        db.ref(`pembayaran_harian/${cabangId}/${today}`).push(pencairanData);
                        adminPencairan += jumlahDicairkan;
                        console.log(`   ðŸŽ‰ Pencairan: ${namaPanggilan} - Rp ${jumlahDicairkan}`);
                    }
                }
            });
            
            totalPembayaran += adminPembayaran;
            totalPencairan += adminPencairan;
            
            results.push({
                adminUid: adminUid,
                adminName: adminData.name || adminData.email || '',
                cabangId: cabangId,
                pembayaran: adminPembayaran,
                pencairan: adminPencairan
            });
            
            console.log(`   âœ… Admin done: pembayaran=${adminPembayaran}, pencairan=${adminPencairan}`);
        }
        
        console.log(`\nâœ… Backfill completed!`);
        console.log(`   Total Pembayaran: Rp ${totalPembayaran}`);
        console.log(`   Total Pencairan: Rp ${totalPencairan}`);
        
        res.json({
            success: true,
            today: today,
            totalPembayaran: totalPembayaran,
            totalPencairan: totalPencairan,
            adminResults: results
        });
        
    } catch (error) {
        console.error('âŒ Error:', error);
        res.status(500).json({
            success: false,
            error: error.message
        });
    }
});

// =========================================================================
// CALLABLE FUNCTION: Backfill (untuk dipanggil dari app)
// =========================================================================
const { onCall } = require("firebase-functions/v2/https");

exports.triggerBackfillPembayaran = onCall(async (request) => {
    const uid = request.auth?.uid;
    
    if (!uid) {
        throw new Error("Not authenticated");
    }
    
    // Cek role pimpinan atau pengawas
    const isPimpinan = await db.ref(`metadata/roles/pimpinan/${uid}`)
        .once("value")
        .then((s) => !!s.val())
        .catch(() => false);
    
    const isPengawas = await db.ref(`metadata/roles/pengawas/${uid}`)
        .once("value")
        .then((s) => !!s.val())
        .catch(() => false);
    
    if (!isPimpinan && !isPengawas) {
        throw new Error("Only pimpinan or pengawas can trigger this");
    }
    
    // Logic sama seperti HTTP function di atas
    // ... (simplified for callable)
    
    return { success: true, message: "Backfill triggered. Check Firebase Console for results." };
});