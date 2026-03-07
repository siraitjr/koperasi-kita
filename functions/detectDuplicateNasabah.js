// =========================================================================
// detectDuplicateNasabah.js
// =========================================================================
// Cloud Function SEKALI PAKAI untuk:
// 1. SCAN: Mendeteksi nasabah duplikat berdasarkan NIK
// 2. CLEANUP: Menghapus duplikat (setelah review)
//
// PENTING: 
// - Jalankan SCAN dulu, review hasilnya
// - Baru jalankan CLEANUP setelah yakin mana yang harus dihapus
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();

// =========================================================================
// FUNGSI 1: SCAN DUPLIKAT (AMAN - TIDAK MENGUBAH DATA)
// =========================================================================
// URL: https://<region>-<project>.cloudfunctions.net/scanDuplicateNasabah
//
// Response: Daftar semua NIK yang memiliki lebih dari 1 entry aktif
// =========================================================================

exports.scanDuplicateNasabah = functions
    .runWith({ timeoutSeconds: 300, memory: '512MB' })
    .https.onRequest(async (req, res) => {
    
    console.log('🔍 =====================================');
    console.log('🔍 SCAN DUPLIKAT NASABAH');
    console.log('🔍 =====================================');
    
    try {
        // 1. Ambil semua admin
        const adminsSnap = await db.ref('metadata/admins').once('value');
        const admins = adminsSnap.val() || {};
        
        // 2. Kumpulkan SEMUA nasabah dari SEMUA admin
        // Key: NIK → Array of entries
        const nikMap = {};
        let totalScanned = 0;
        let totalAdmins = 0;
        
        for (const [adminUid, adminInfo] of Object.entries(admins)) {
            const role = adminInfo.role || '';
            if (role !== 'admin') continue;
            
            totalAdmins++;
            const adminName = adminInfo.name || adminInfo.displayName || adminInfo.email || 'Admin';
            const cabang = adminInfo.cabang || '';
            
            // Load semua pelanggan admin ini
            const pelangganSnap = await db.ref(`pelanggan/${adminUid}`).once('value');
            const pelangganData = pelangganSnap.val() || {};
            
            for (const [pelangganId, pelanggan] of Object.entries(pelangganData)) {
                totalScanned++;
                
                // Skip jika tidak ada NIK
                const nik = (pelanggan.nik || '').trim();
                if (!nik || nik.length < 10) continue; // NIK minimal harus cukup panjang
                
                // Hitung total dibayar
                let totalDibayar = 0;
                if (pelanggan.pembayaranList) {
                    const pList = Array.isArray(pelanggan.pembayaranList)
                        ? pelanggan.pembayaranList
                        : Object.values(pelanggan.pembayaranList || {});
                    pList.forEach(p => {
                        totalDibayar += p.jumlah || 0;
                        if (p.subPembayaran) {
                            const subList = Array.isArray(p.subPembayaran)
                                ? p.subPembayaran
                                : Object.values(p.subPembayaran || {});
                            subList.forEach(sub => totalDibayar += sub.jumlah || 0);
                        }
                    });
                }
                
                const totalPelunasan = pelanggan.totalPelunasan || 0;
                const isLunas = totalDibayar >= totalPelunasan && totalPelunasan > 0;
                const status = pelanggan.status || 'unknown';
                
                // Hitung jumlah pembayaran
                const pembayaranCount = pelanggan.pembayaranList
                    ? (Array.isArray(pelanggan.pembayaranList) 
                        ? pelanggan.pembayaranList.length 
                        : Object.keys(pelanggan.pembayaranList).length)
                    : 0;
                
                const entry = {
                    adminUid: adminUid,
                    adminName: adminName,
                    cabang: cabang,
                    pelangganId: pelangganId,
                    namaKtp: pelanggan.namaKtp || '',
                    namaPanggilan: pelanggan.namaPanggilan || '',
                    nik: nik,
                    status: status,
                    isLunas: isLunas,
                    besarPinjaman: pelanggan.besarPinjaman || 0,
                    totalPelunasan: totalPelunasan,
                    totalDibayar: totalDibayar,
                    sisaUtang: Math.max(0, totalPelunasan - totalDibayar),
                    pembayaranCount: pembayaranCount,
                    tanggalPengajuan: pelanggan.tanggalPengajuan || '',
                    pinjamanKe: pelanggan.pinjamanKe || 1,
                    firebasePath: `pelanggan/${adminUid}/${pelangganId}`
                };
                
                if (!nikMap[nik]) {
                    nikMap[nik] = [];
                }
                nikMap[nik].push(entry);
            }
        }
        
        // 3. Filter: hanya ambil NIK yang punya > 1 entry DAN minimal 2 yang AKTIF
        const duplicates = [];
        let totalDuplicateNiks = 0;
        let totalDuplicateEntries = 0;
        
        for (const [nik, entries] of Object.entries(nikMap)) {
            if (entries.length <= 1) continue;
            
            // Cek apakah ada minimal 2 entry yang AKTIF (bukan lunas)
            const activeEntries = entries.filter(e => {
                const s = (e.status || '').toLowerCase();
                return s === 'aktif' || s === 'active' || 
                       s === 'menunggu approval' || s === 'disetujui';
            });
            
            if (activeEntries.length >= 2) {
                totalDuplicateNiks++;
                totalDuplicateEntries += entries.length;
                
                // Tentukan mana yang ASLI (yang punya lebih banyak pembayaran / lebih lama)
                const sorted = [...entries].sort((a, b) => {
                    // Prioritas 1: Yang punya lebih banyak pembayaran = lebih asli
                    if (b.pembayaranCount !== a.pembayaranCount) {
                        return b.pembayaranCount - a.pembayaranCount;
                    }
                    // Prioritas 2: Yang pinjamanKe lebih tinggi = lebih lama
                    if (b.pinjamanKe !== a.pinjamanKe) {
                        return b.pinjamanKe - a.pinjamanKe;
                    }
                    // Prioritas 3: Yang totalDibayar lebih banyak
                    return b.totalDibayar - a.totalDibayar;
                });
                
                duplicates.push({
                    nik: nik,
                    totalEntries: entries.length,
                    activeCount: activeEntries.length,
                    recommendation: {
                        keep: {
                            pelangganId: sorted[0].pelangganId,
                            namaKtp: sorted[0].namaKtp,
                            namaPanggilan: sorted[0].namaPanggilan,
                            adminName: sorted[0].adminName,
                            status: sorted[0].status,
                            pembayaranCount: sorted[0].pembayaranCount,
                            totalDibayar: sorted[0].totalDibayar,
                            sisaUtang: sorted[0].sisaUtang,
                            reason: 'Lebih banyak pembayaran / lebih senior'
                        },
                        remove: sorted.slice(1).map(e => ({
                            pelangganId: e.pelangganId,
                            namaKtp: e.namaKtp,
                            namaPanggilan: e.namaPanggilan,
                            adminName: e.adminName,
                            adminUid: e.adminUid,
                            status: e.status,
                            pembayaranCount: e.pembayaranCount,
                            totalDibayar: e.totalDibayar,
                            sisaUtang: e.sisaUtang,
                            firebasePath: e.firebasePath
                        }))
                    },
                    allEntries: entries
                });
            }
        }
        
        console.log(`✅ Scan selesai:`);
        console.log(`   Total admin: ${totalAdmins}`);
        console.log(`   Total nasabah di-scan: ${totalScanned}`);
        console.log(`   NIK duplikat aktif: ${totalDuplicateNiks}`);
        console.log(`   Total entry duplikat: ${totalDuplicateEntries}`);
        
        res.json({
            success: true,
            summary: {
                totalAdmins: totalAdmins,
                totalNasabahScanned: totalScanned,
                totalDuplicateNiks: totalDuplicateNiks,
                totalDuplicateEntries: totalDuplicateEntries
            },
            duplicates: duplicates,
            instruction: 'Review daftar di atas. Jika sudah yakin, panggil endpoint /cleanupDuplicateNasabah dengan body JSON: { "removals": [{ "firebasePath": "pelanggan/xxx/yyy" }] }'
        });
        
    } catch (error) {
        console.error('❌ Scan error:', error);
        res.status(500).json({
            success: false,
            error: error.message
        });
    }
});


// =========================================================================
// FUNGSI 2: CLEANUP DUPLIKAT (HATI-HATI - MENGHAPUS DATA!)
// =========================================================================
// URL: https://<region>-<project>.cloudfunctions.net/cleanupDuplicateNasabah
// Method: POST
// Body: { "removals": [{ "firebasePath": "pelanggan/adminUid/pelangganId" }] }
//
// PENTING: 
// - HANYA jalankan setelah SCAN dan review manual
// - Fungsi ini MEMBACKUP data sebelum menghapus
// - Backup disimpan di node "deleted_duplicates" di Firebase
// =========================================================================

exports.cleanupDuplicateNasabah = functions
    .runWith({ timeoutSeconds: 120, memory: '256MB' })
    .https.onRequest(async (req, res) => {
    
    if (req.method !== 'POST') {
        return res.status(405).json({ error: 'Method not allowed. Use POST.' });
    }
    
    const { removals } = req.body;
    
    if (!removals || !Array.isArray(removals) || removals.length === 0) {
        return res.status(400).json({ 
            error: 'Body harus berisi: { "removals": [{ "firebasePath": "pelanggan/xxx/yyy" }] }' 
        });
    }
    
    console.log('🗑️ =====================================');
    console.log(`🗑️ CLEANUP ${removals.length} DUPLIKAT`);
    console.log('🗑️ =====================================');
    
    const results = [];
    let successCount = 0;
    let failCount = 0;
    
    for (const item of removals) {
        const path = item.firebasePath;
        
        if (!path || !path.startsWith('pelanggan/')) {
            results.push({ path, status: 'SKIPPED', reason: 'Path tidak valid' });
            failCount++;
            continue;
        }
        
        try {
            // STEP 1: Baca data yang akan dihapus
            const snap = await db.ref(path).once('value');
            if (!snap.exists()) {
                results.push({ path, status: 'SKIPPED', reason: 'Data tidak ditemukan' });
                continue;
            }
            
            const data = snap.val();
            
            // STEP 2: BACKUP ke node deleted_duplicates
            const backupId = `backup_${Date.now()}_${path.replace(/\//g, '_')}`;
            await db.ref(`deleted_duplicates/${backupId}`).set({
                originalPath: path,
                data: data,
                deletedAt: admin.database.ServerValue.TIMESTAMP,
                reason: 'Duplicate NIK cleanup'
            });
            
            console.log(`💾 Backup created: ${backupId}`);
            
            // STEP 3: Hapus dari path asli
            await db.ref(path).remove();
            
            // STEP 4: Update nik_registry jika perlu
            // (nik_registry akan otomatis ter-update oleh trigger onPelangganDeleted)
            
            console.log(`✅ Deleted: ${path} (${data.namaKtp || data.namaPanggilan})`);
            results.push({ 
                path, 
                status: 'DELETED', 
                nama: data.namaKtp || data.namaPanggilan,
                backupId: backupId
            });
            successCount++;
            
        } catch (error) {
            console.error(`❌ Error deleting ${path}: ${error.message}`);
            results.push({ path, status: 'ERROR', reason: error.message });
            failCount++;
        }
    }
    
    console.log(`✅ Cleanup selesai: ${successCount} deleted, ${failCount} failed`);
    
    res.json({
        success: true,
        summary: {
            total: removals.length,
            deleted: successCount,
            failed: failCount
        },
        results: results,
        note: 'Data yang dihapus sudah di-backup di node "deleted_duplicates". Jika ada kesalahan, data bisa di-restore dari sana.'
    });
});