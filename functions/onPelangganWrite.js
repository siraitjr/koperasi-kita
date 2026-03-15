// functions/onPelangganWrite.js
// =========================================================================
// CLOUD FUNCTION: ON PELANGGAN WRITE (100% OTOMATIS)
// =========================================================================

// functions/onPelangganWrite.js
const functions = require('firebase-functions');
const admin = require('firebase-admin');

const db = admin.database();
const { processPelangganChange, getTodayIndonesia } = require('./summaryHelpers');

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
            // PINJAMAN HISTORY: Simpan riwayat besarPinjaman saat lanjut pinjaman
            // =========================================================
            // Disimpan di node terpisah (bukan di bawah pelanggan) agar tidak
            // memicu infinite loop pada trigger ini.
            // Format: pinjamanHistory/{adminUid}/{pelangganId}/{autoId}
            //   { besarPinjaman: <nilai lama>, berlakuSampai: "dd Mmm yyyy" }
            // Makna berlakuSampai: hari TERAKHIR besarPinjaman lama berlaku
            // (hari lanjut pinjaman itu sendiri).
            // =========================================================
            if (beforeData && afterData) {
                const oldBesar = beforeData.besarPinjaman || 0;
                const newBesar = afterData.besarPinjaman || 0;
                if (oldBesar > 0 && newBesar !== oldBesar) {
                    const today = getTodayIndonesia();
                    await db.ref(`pinjamanHistory/${adminUid}/${pelangganId}`).push({
                        besarPinjaman: oldBesar,
                        berlakuSampai: today,
                        tanggalPencairan: beforeData.tanggalPencairan || '',
                    });
                    console.log(`📝 pinjamanHistory saved: ${oldBesar} → ${newBesar} (berlakuSampai: ${today}, tglCairLama: ${beforeData.tanggalPencairan || ''})`);
                }
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
 */
async function ensurePengajuanApprovalExists(adminUid, pelangganId, pelangganData) {
    try {
        const adminMetaSnap = await db.ref(`metadata/admins/${adminUid}/cabang`).once('value');
        const cabangId = adminMetaSnap.val();
        
        if (!cabangId) {
            console.warn(`⚠️ Safety net: cabangId tidak ditemukan untuk admin ${adminUid}`);
            return;
        }
        
        const existingSnap = await db.ref(`pengajuan_approval/${cabangId}`)
            .orderByChild('pelangganId')
            .equalTo(pelangganId)
            .once('value');
        
        const besarPinjaman = pelangganData.besarPinjaman || 0;
        
        if (existingSnap.exists()) {
            // ✅ PERBAIKAN: Cek apakah entry yang ada BASI (sudah completed/bukan Phase 1)
            let needsReset = false;
            let existingKey = null;
            
            existingSnap.forEach(child => {
                const data = child.val();
                const dualInfo = data.dualApprovalInfo;
                
                if (dualInfo) {
                    const phase = dualInfo.approvalPhase || '';
                    const pimpinanStatus = dualInfo.pimpinanApproval?.status || 'pending';

                    // Phase yang sedang aktif dalam proses multi-approval - JANGAN direset!
                    // Mereset entry ini akan merusak alur approval yang sedang berjalan.
                    const activeMultiPhases = [
                        'awaiting_koordinator',
                        'awaiting_pengawas',
                        'awaiting_koordinator_final',
                        'awaiting_pimpinan_final'
                    ];

                    if (activeMultiPhases.includes(phase)) {
                        // Sedang dalam proses approval multi-phase - jangan direset
                        if (!existingKey) existingKey = child.key;
                    } else if (phase === 'completed') {
                        // Siklus approval sudah selesai tapi pelanggan masih Menunggu Approval
                        // (kemungkinan pengajuan ulang) - reset untuk pengajuan baru
                        needsReset = true;
                        existingKey = child.key;
                    } else if (pimpinanStatus !== 'pending') {
                        // Phase awaiting_pimpinan tapi pimpinan sudah aksi - state tidak konsisten
                        needsReset = true;
                        existingKey = child.key;
                    }
                    // Phase awaiting_pimpinan dengan pimpinan pending = fresh entry, biarkan
                }
                
                if (!existingKey) {
                    existingKey = child.key;
                }
            });
            
            if (needsReset && existingKey) {
                console.log(`🔧 Safety net: RESET entry basi untuk ${pelangganData.namaPanggilan || pelangganId}`);
                
                const resetData = {
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
                };
                
                // Gunakan setValue (bukan update) agar dualApprovalInfo lama TERHAPUS
                await db.ref(`pengajuan_approval/${cabangId}/${existingKey}`).set(resetData);
                console.log(`✅ Safety net: entry direset ke Phase 1`);
                
                // Hapus entry duplikat jika ada
                let isFirst = true;
                existingSnap.forEach(child => {
                    if (isFirst) { isFirst = false; return; }
                    child.ref.remove();
                    console.log(`🗑️ Safety net: hapus duplikat ${child.key}`);
                });
            } else {
                // Entry masih fresh (Phase 1, pending), tidak perlu diubah
                return;
            }
        } else {
            // BELUM ADA → buat entry baru
            console.log(`🔧 Safety net: membuat pengajuan_approval untuk ${pelangganData.namaPanggilan || pelangganId}`);
            
            const pengajuanData = {
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
            };
            
            await db.ref(`pengajuan_approval/${cabangId}`).push(pengajuanData);
            console.log(`✅ Safety net: pengajuan_approval dibuat untuk cabang ${cabangId}`);
        }
        
    } catch (error) {
        console.error(`❌ Safety net error: ${error.message}`);
    }
}