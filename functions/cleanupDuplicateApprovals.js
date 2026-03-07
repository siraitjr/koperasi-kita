const admin = require('firebase-admin');

// Jalankan ini SEKALI saja, lalu hapus/disable
exports.cleanupDuplicateApprovals = async (req, res) => {
    const db = admin.database();
    const approvalRef = db.ref('pengajuan_approval');
    
    const snapshot = await approvalRef.once('value');
    let totalRemoved = 0;
    let totalKept = 0;
    
    const updates = {};
    
    snapshot.forEach(cabangSnap => {
        const cabangId = cabangSnap.key;
        const seenPelangganIds = {};
        
        cabangSnap.forEach(child => {
            const data = child.val();
            const pelangganId = data.pelangganId;
            const phase = data.dualApprovalInfo?.approvalPhase || '';
            
            if (!pelangganId) return;
            
            // Hapus entry yang phase-nya sudah COMPLETED (zombie)
            if (phase === 'completed') {
                updates[`pengajuan_approval/${cabangId}/${child.key}`] = null;
                totalRemoved++;
                return;
            }
            
            // Deduplikasi: simpan yang pertama, hapus sisanya
            if (seenPelangganIds[pelangganId]) {
                updates[`pengajuan_approval/${cabangId}/${child.key}`] = null;
                totalRemoved++;
            } else {
                seenPelangganIds[pelangganId] = child.key;
                totalKept++;
            }
        });
    });
    
    if (Object.keys(updates).length > 0) {
        await db.ref().update(updates);
    }
    
    console.log(`Cleanup selesai: ${totalRemoved} dihapus, ${totalKept} dipertahankan`);
    res.json({ removed: totalRemoved, kept: totalKept });
};