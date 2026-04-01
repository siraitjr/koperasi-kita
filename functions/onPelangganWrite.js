// functions/onPelangganWrite.js
// =========================================================================
// CLOUD FUNCTION: ON PELANGGAN WRITE (100% OTOMATIS)
// =========================================================================

// functions/onPelangganWrite.js
const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();
const { processPelangganChange } = require('./summaryHelpers');

// Debounce mechanism
const recentlyProcessed = new Map();
const DEBOUNCE_MS = 2000;

function shouldProcess(key) {
    const lastProcessed = recentlyProcessed.get(key);
    const now = Date.now();
    
    if (lastProcessed && (now - lastProcessed) < DEBOUNCE_MS) {
        return false;
    }
    
    recentlyProcessed.set(key, now);
    
    if (recentlyProcessed.size > 1000) {
        const cutoff = now - DEBOUNCE_MS * 2;
        for (const [k, v] of recentlyProcessed.entries()) {
            if (v < cutoff) recentlyProcessed.delete(k);
        }
    }
    
    return true;
}

exports.onPelangganWrite = functions.database
    .ref('/pelanggan/{adminUid}/{pelangganId}')
    .onWrite(async (change, context) => {
        const adminUid = context.params.adminUid;
        const pelangganId = context.params.pelangganId;
        
        const debounceKey = `${adminUid}/${pelangganId}`;
        if (!shouldProcess(debounceKey)) {
            console.log(`⏭️ Skipping (debounced): ${debounceKey}`);
            return null;
        }
        
        console.log(`📝 Pelanggan write: ${adminUid}/${pelangganId}`);
        
        const beforeData = change.before.val();
        const afterData = change.after.val();
        
        try {
            // Summary OTOMATIS dibuat jika belum ada
            await processPelangganChange(adminUid, beforeData, afterData);

            // =========================================================
            // AUTO-ARSIP: Simpan riwayat pinjaman lama saat lanjut pinjaman
            // Deteksi: pinjamanKe naik = nasabah lanjut ke pinjaman baru
            // Data lama diambil dari beforeData (masih utuh sebelum di-overwrite)
            // =========================================================
            if (beforeData && afterData &&
                (afterData.pinjamanKe || 1) > (beforeData.pinjamanKe || 1)) {
                await archiveRiwayatPinjaman(adminUid, pelangganId, beforeData);
            }

            // =========================================================
            // SAFETY NET: Auto-create pengajuan_approval jika belum ada
            // =========================================================
            if (afterData && afterData.status === 'Menunggu Approval') {
                await ensurePengajuanApprovalExists(adminUid, pelangganId, afterData);
            }

            console.log(`✅ Done`);
            return null;
        } catch (error) {
            console.error(`❌ Error: ${error.message}`);
            return null;
        }
    });

/**
 * Safety net: pastikan pengajuan_approval ada untuk setiap pelanggan "Menunggu Approval"
 * 
 * STRATEGI ANTI-RACE-CONDITION:
 * - Jika entry ADA dan FRESH → skip (client sudah buat)
 * - Jika entry ADA tapi BASI → hapus semua + push baru (trigger onCreate)
 * - Jika entry TIDAK ADA → tunggu 5 detik, cek lagi:
 *   - Jika sekarang ADA → client sudah buat, skip
 *   - Jika masih TIDAK ADA → client gagal, buat sebagai backup
 */
async function ensurePengajuanApprovalExists(adminUid, pelangganId, pelangganData) {
    try {
        const adminMetaSnap = await db.ref(`metadata/admins/${adminUid}/cabang`).once('value');
        const cabangId = adminMetaSnap.val();
        
        if (!cabangId) {
            console.warn(`⚠️ Safety net: cabangId tidak ditemukan untuk admin ${adminUid}`);
            return;
        }
        
        const approvalPath = `pengajuan_approval/${cabangId}`;
        const besarPinjaman = pelangganData.besarPinjaman || 0;
        
        // Helper: query entry berdasarkan pelangganId
        const queryEntries = async () => {
            return await db.ref(approvalPath)
                .orderByChild('pelangganId')
                .equalTo(pelangganId)
                .once('value');
        };
        
        // Helper: buat data pengajuan baru
        const buildPengajuanData = () => ({
            adminUid: adminUid,
            nama: pelangganData.namaKtp || '',
            namaPanggilan: pelangganData.namaPanggilan || '',
            tanggalPengajuan: pelangganData.tanggalPengajuan || '',
            status: 'Menunggu Approval',
            jenisPinjaman: besarPinjaman >= 3000000 ? 'diatas_3jt' : 'dibawah_3jt',
            besarPinjaman: besarPinjaman,
            tenor: pelangganData.tenor || 24,
            pinjamanKe: pelangganData.pinjamanKe || 1,
            pelangganId: pelangganId,
            timestamp: admin.database.ServerValue.TIMESTAMP
        });
        
        // =============================================
        // CEK PERTAMA
        // =============================================
        const existingSnap = await queryEntries();
        
        if (existingSnap.exists()) {
            // Entry ditemukan — cek apakah masih fresh atau basi
            let needsReset = false;
            
            existingSnap.forEach(child => {
                const data = child.val();
                const dualInfo = data.dualApprovalInfo;

                if (dualInfo) {
                    const phase = dualInfo.approvalPhase || '';

                    // Entry BASI hanya jika approval sudah SELESAI (completed)
                    // Fase aktif (awaiting_pimpinan, awaiting_koordinator, awaiting_pengawas,
                    // awaiting_koordinator_final, awaiting_pimpinan_final) = sedang berjalan, JANGAN dihapus
                    if (phase === 'completed') {
                        needsReset = true;
                    }
                    // Jika phase kosong tapi dualInfo ada, ini entry rusak → reset
                    else if (!phase) {
                        needsReset = true;
                    }
                    // Semua fase aktif lainnya → JANGAN reset (approval sedang berjalan)
                }
            });
            
            if (needsReset) {
                // BASI → hapus SEMUA entry, lalu buat baru dengan push()
                console.log(`🔧 Safety net: entry BASI untuk ${pelangganData.namaPanggilan || pelangganId}, reset...`);
                
                const deletePromises = [];
                existingSnap.forEach(child => {
                    deletePromises.push(child.ref.remove());
                    console.log(`🗑️ Safety net: hapus ${child.key}`);
                });
                await Promise.all(deletePromises);
                
                // Buat baru — push() = trigger onCreate = notifikasi terkirim
                await db.ref(approvalPath).push(buildPengajuanData());
                console.log(`✅ Safety net: entry baru dibuat (onCreate trigger)`);
            } else {
                // FRESH → tidak perlu diubah
                console.log(`✅ Safety net: entry sudah ada dan masih fresh, skip`);
                return;
            }
            
        } else {
            // =================================================
            // ENTRY TIDAK ADA → Mungkin client sedang membuat
            // Tunggu 5 detik, lalu cek lagi
            // =================================================
            console.log(`⏳ Safety net: entry belum ada, tunggu client 5 detik...`);
            await new Promise(resolve => setTimeout(resolve, 5000));
            
            // CEK KEDUA
            const recheckSnap = await queryEntries();
            
            if (recheckSnap.exists()) {
                // Client sudah membuat → skip
                console.log(`✅ Safety net: client sudah membuat entry, skip`);
                return;
            }
            
            // Masih tidak ada setelah 5 detik → client gagal, buat sebagai backup
            console.log(`🔧 Safety net: client gagal membuat entry, buat backup`);
            await db.ref(approvalPath).push(buildPengajuanData());
            console.log(`✅ Safety net: backup entry dibuat (onCreate trigger)`);
        }
        
    } catch (error) {
        console.error(`❌ Safety net error: ${error.message}`);
    }
}

// =========================================================================
// AUTO-ARSIP: Simpan riwayat pinjaman lama ke riwayat_pinjaman/
// =========================================================================
// Dipanggil otomatis saat pinjamanKe naik (lanjut pinjaman)
// Menyimpan SELURUH data pinjaman lama termasuk pembayaranList
// sehingga pembukuan tetap tercatat permanen
// =========================================================================
async function archiveRiwayatPinjaman(adminUid, pelangganId, beforeData) {
    try {
        const pinjamanKeLama = beforeData.pinjamanKe || 1;

        // Cek apakah sudah pernah diarsip (hindari duplikat)
        const existingSnap = await db.ref(
            `riwayat_pinjaman/${adminUid}/${pelangganId}/${pinjamanKeLama}`
        ).once('value');

        if (existingSnap.exists()) {
            console.log(`📦 Riwayat pinjaman ke-${pinjamanKeLama} sudah ada, skip arsip`);
            return;
        }

        // Hitung total dibayar dari pembayaranList lama
        let totalDibayar = 0;
        const pembayaranList = beforeData.pembayaranList || [];
        const listArray = Array.isArray(pembayaranList)
            ? pembayaranList
            : Object.values(pembayaranList);

        listArray.forEach(p => {
            if (!p) return;
            if (p.tanggal && p.tanggal.startsWith && p.tanggal.startsWith('Bunga')) return;
            totalDibayar += p.jumlah || 0;
            const subList = p.subPembayaran
                ? (Array.isArray(p.subPembayaran) ? p.subPembayaran : Object.values(p.subPembayaran))
                : [];
            subList.forEach(sub => {
                totalDibayar += sub.jumlah || 0;
            });
        });

        const totalPelunasan = beforeData.totalPelunasan || 0;
        const sisaUtang = Math.max(0, totalPelunasan - totalDibayar);

        // Simpan arsip lengkap
        const arsipData = {
            // Identitas
            namaKtp: beforeData.namaKtp || '',
            namaPanggilan: beforeData.namaPanggilan || '',
            nomorAnggota: beforeData.nomorAnggota || '',

            // Data pinjaman
            pinjamanKe: pinjamanKeLama,
            besarPinjaman: beforeData.besarPinjaman || 0,
            totalPelunasan: totalPelunasan,
            totalDibayar: totalDibayar,
            sisaUtang: sisaUtang,
            tenor: beforeData.tenor || 0,
            jasaPinjaman: beforeData.jasaPinjaman || 0,
            admin: beforeData.admin || 0,
            simpanan: beforeData.simpanan || 0,
            totalDiterima: beforeData.totalDiterima || 0,

            // Tanggal
            tanggalPengajuan: beforeData.tanggalPengajuan || '',
            tanggalPencairan: beforeData.tanggalPencairan || '',
            tanggalLunasCicilan: beforeData.tanggalLunasCicilan || '',

            // Status terakhir
            status: beforeData.status || '',
            statusKhusus: beforeData.statusKhusus || '',

            // Riwayat pembayaran lengkap (DATA UTAMA yang harus permanen)
            pembayaranList: beforeData.pembayaranList || [],

            // Metadata arsip
            archivedAt: admin.database.ServerValue.TIMESTAMP,
            adminUid: adminUid
        };

        await db.ref(
            `riwayat_pinjaman/${adminUid}/${pelangganId}/${pinjamanKeLama}`
        ).set(arsipData);

        console.log(`📦 Riwayat pinjaman ke-${pinjamanKeLama} berhasil diarsip untuk ${beforeData.namaKtp || pelangganId}`);
        console.log(`   💰 Pinjaman: Rp ${beforeData.besarPinjaman}, Dibayar: Rp ${totalDibayar}, Sisa: Rp ${sisaUtang}`);

    } catch (error) {
        console.error(`❌ Gagal arsip riwayat pinjaman: ${error.message}`);
    }
}
