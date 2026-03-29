// =========================================================================
// REKENING KORAN SERVICE - SIMPLE SECURE LINK
// =========================================================================
// 
// Cara Kerja:
// 1. Admin klik "Salin Link" di app Android
// 2. Android generate token lokal: base64(adminUid:pelangganId:timestamp:signature)
// 3. Nasabah buka link → Cloud Function verify token → return data
// 
// Keunggulan:
// - Tidak perlu call Cloud Function saat generate link (instant!)
// - Aman dengan HMAC signature
// - RTDB tidak bisa diakses langsung oleh publik
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');
const crypto = require('crypto');

// Jika belum di-init di index.js
// admin.initializeApp();

const db = admin.database();

// =========================================================================
// SECRET KEY - WAJIB SAMA DI ANDROID DAN CLOUD FUNCTION
// =========================================================================
// Set via: firebase functions:config:set rk.secret="your-32-char-secret-key"
const getSecretKey = () => {
    try {
        return functions.config().rk?.secret || 'K0p3r4s1K1t4G0d4ngUluS3cur3Key!';
    } catch (e) {
        return 'K0p3r4s1K1t4G0d4ngUluS3cur3Key!';
    }
};

// =========================================================================
// VERIFY TOKEN & GET DATA
// =========================================================================
function verifyToken(token) {
    try {
        // Decode base64url
        const decoded = Buffer.from(token, 'base64url').toString('utf8');
        const parts = decoded.split(':');
        
        if (parts.length !== 4) {
            return { valid: false, error: 'Format token tidak valid' };
        }
        
        const [adminUid, pelangganId, timestamp, signature] = parts;
        
        // Verify signature
        const payload = `${adminUid}:${pelangganId}:${timestamp}`;
        const hmac = crypto.createHmac('sha256', getSecretKey());
        hmac.update(payload);
        const expectedSignature = hmac.digest('hex').substring(0, 16);
        
        if (signature !== expectedSignature) {
            return { valid: false, error: 'Token tidak valid' };
        }
        
        // Token valid! (tidak ada expiry agar link permanen)
        return { valid: true, adminUid, pelangganId };
        
    } catch (error) {
        console.error('Token verification error:', error);
        return { valid: false, error: 'Token tidak dapat dibaca' };
    }
}

// =========================================================================
// GENERATE REKENING KORAN DATA
// =========================================================================
async function generateRekeningKoranData(adminUid, pelangganId) {
    const snap = await db.ref(`pelanggan/${adminUid}/${pelangganId}`).get();
    
    if (!snap.exists()) {
        throw new Error('Data tidak ditemukan');
    }
    
    const p = snap.val();
    
    // Hitung pembayaran
    const pembayaranList = p.pembayaranList || [];
    const pembayaranArray = Array.isArray(pembayaranList) 
        ? pembayaranList 
        : Object.values(pembayaranList);
    
    let totalDibayar = 0;
    const riwayatPembayaran = [];
    
    pembayaranArray.forEach((pay) => {
        if (!pay.tanggal || pay.tanggal.startsWith('Bunga')) return;

        const jumlah = pay.jumlah || 0;
        totalDibayar += jumlah;

        // Sub-pembayaran sebagai array di dalam pembayaran parent
        const subItems = [];
        if (pay.subPembayaran && Array.isArray(pay.subPembayaran)) {
            pay.subPembayaran.forEach(sub => {
                const subJumlah = sub.jumlah || 0;
                totalDibayar += subJumlah;
                subItems.push({
                    tanggal: sub.tanggal,
                    jumlah: subJumlah,
                    keterangan: sub.keterangan || 'Tambah Bayar'
                });
            });
        }

        riwayatPembayaran.push({
            no: riwayatPembayaran.length + 1,
            tanggal: pay.tanggal,
            jumlah: jumlah,
            subPembayaran: subItems
        });
    });
    
    // Hitung sisa
    const totalPelunasan = p.totalPelunasan || 0;
    const sisaHutang = Math.max(0, totalPelunasan - totalDibayar);
    const isLunas = sisaHutang <= 0;
    
    // Sisa tenor
    const tenor = p.tenor || 0;
    const dibayarCount = pembayaranArray.filter(pay => 
        pay.tanggal && !pay.tanggal.startsWith('Bunga')
    ).length;
    const sisaTenor = Math.max(0, tenor - dibayarCount);
    
    // Simulasi cicilan
    const hasilSimulasi = p.hasilSimulasiCicilan || [];
    const simulasiArray = Array.isArray(hasilSimulasi) 
        ? hasilSimulasi 
        : Object.values(hasilSimulasi);
    
    const referensiCicilan = simulasiArray.map((sim, idx) => ({
        no: idx + 1,
        tanggal: sim.tanggal,
        jumlah: sim.jumlah,
        isCompleted: sim.isCompleted || false
    }));
    
    return {
        // Identitas
        nama: p.namaPanggilan || p.namaKtp || '-',
        nik: p.nik || '-',                          // ✅ TAMBAH NIK
        alamat: p.alamatRumah || p.alamatKtp || '-',
        nomorAnggota: p.nomorAnggota || '-',
        wilayah: p.wilayah || '-',
        hari: p.hari || '-',
        
        // Info Pinjaman
        besarPinjaman: p.besarPinjaman || 0,
        totalPelunasan: totalPelunasan,
        tanggalDaftar: p.tanggalDaftar || p.tanggalPengajuan || '-',
        tenor: tenor,
        pinjamanKe: p.pinjamanKe || 1,
        
        // Simpanan
        simpanan: p.simpanan || 0,                  // ✅ TAMBAH SIMPANAN
        
        // Status
        status: p.status || '-',
        isLunas: isLunas,
        
        // Keuangan
        totalDibayar: totalDibayar,
        sisaHutang: sisaHutang,
        sisaTenor: sisaTenor,
        
        // Riwayat
        riwayatPembayaran: riwayatPembayaran,
        referensiCicilan: referensiCicilan,
        
        // Meta
        generatedAt: new Date().toISOString()
    };
}

// =========================================================================
// HTTP ENDPOINT - GET REKENING KORAN
// =========================================================================
// URL: https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net/getRekeningKoran?t=TOKEN
// =========================================================================
exports.getRekeningKoran = functions
    .region('asia-southeast1')
    .https.onRequest(async (req, res) => {
        // CORS
        res.set('Access-Control-Allow-Origin', '*');
        res.set('Access-Control-Allow-Methods', 'GET, OPTIONS');
        res.set('Access-Control-Allow-Headers', 'Content-Type');
        
        if (req.method === 'OPTIONS') {
            res.status(204).send('');
            return;
        }
        
        try {
            const token = req.query.t;
            
            if (!token) {
                res.status(400).json({
                    success: false,
                    error: 'Token tidak ditemukan'
                });
                return;
            }
            
            // Verify token
            const verification = verifyToken(token);
            
            if (!verification.valid) {
                res.status(401).json({
                    success: false,
                    error: verification.error
                });
                return;
            }
            
            // Get data
            const data = await generateRekeningKoranData(
                verification.adminUid,
                verification.pelangganId
            );
            
            res.status(200).json({
                success: true,
                data: data
            });
            
        } catch (error) {
            console.error('Error:', error);
            res.status(500).json({
                success: false,
                error: 'Terjadi kesalahan'
            });
        }
    });