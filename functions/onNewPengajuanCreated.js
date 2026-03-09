// functions/onNewPengajuanCreated.js
// =========================================================================
// CLOUD FUNCTION: PUSH NOTIFICATION UNTUK PENGAJUAN
// =========================================================================
//
// VERSI 8 - SEQUENTIAL FIVE-PHASE APPROVAL (DENGAN KOORDINATOR)
// =========================================================================
// ALUR BARU:
// 1. Pengajuan >= 3jt dibuat → HANYA Pimpinan menerima notifikasi (Phase 1)
// 2. Pimpinan approve/reject → KOORDINATOR menerima notifikasi (Phase 2) ← BARU
// 3. Koordinator approve/reject → Pengawas menerima notifikasi (Phase 3)
// 4. Pengawas approve/reject → KOORDINATOR menerima notif finalisasi (Phase 4) ← BARU
// 5. Koordinator konfirmasi → Pimpinan menerima notif finalisasi (Phase 5)
// 6. Pimpinan finalisasi → Admin lapangan menerima notifikasi final
// =========================================================================
//
// PERUBAHAN dari v7:
// ✅ TAMBAH Phase 2: AWAITING_KOORDINATOR (setelah Pimpinan)
// ✅ TAMBAH Phase 4: AWAITING_KOORDINATOR_FINAL (setelah Pengawas)
// ✅ TAMBAH trigger onKoordinatorReviewed untuk Phase 2 → Phase 3
// ✅ UPDATE onPimpinanReviewed: Phase 1 → Phase 2 (ke Koordinator, bukan Pengawas)
// ✅ UPDATE onPengawasReviewed: Phase 3 → Phase 4 (ke Koordinator Final)
// ✅ TAMBAH trigger onKoordinatorFinalReviewed: Phase 4 → Phase 5
// =========================================================================

const functions = require("firebase-functions");
const admin = require("firebase-admin");

const db = admin.database();

const DUAL_APPROVAL_THRESHOLD = 3000000;

// Approval Phase constants - UPDATED untuk 5-phase
const ApprovalPhase = {
    AWAITING_PIMPINAN: "awaiting_pimpinan",              // Phase 1
    AWAITING_KOORDINATOR: "awaiting_koordinator",       // Phase 2 - BARU
    AWAITING_PENGAWAS: "awaiting_pengawas",             // Phase 3
    AWAITING_KOORDINATOR_FINAL: "awaiting_koordinator_final", // Phase 4 - BARU
    AWAITING_PIMPINAN_FINAL: "awaiting_pimpinan_final", // Phase 5
    COMPLETED: "completed"
};

function formatRupiah(angka) {
    return new Intl.NumberFormat('id-ID').format(angka);
}

// =========================================================================
// TRIGGER: PENGAJUAN BARU DIBUAT (Phase 0 → Phase 1)
// =========================================================================
// TIDAK BERUBAH: Hanya kirim notifikasi ke PIMPINAN
// =========================================================================
exports.onNewPengajuanCreated = functions.database
    .ref('/pengajuan_approval/{cabangId}/{pengajuanId}')
    .onCreate(async (snapshot, context) => {
        const cabangId = context.params.cabangId;
        const pengajuanId = context.params.pengajuanId;
        const pengajuanData = snapshot.val();

        try {
            const besarPinjaman = pengajuanData.besarPinjaman || 0;
            const requiresDualApproval = besarPinjaman >= DUAL_APPROVAL_THRESHOLD;

            console.log('📨 Pengajuan baru:', {
                cabangId, pengajuanId,
                nama: pengajuanData.namaPanggilan,
                besarPinjaman, requiresDualApproval
            });

            // =========================================================================
            // Setup dual approval info jika >= 3jt
            // FIX: Hanya set jika belum ada (mencegah overwrite dari client)
            // =========================================================================
            if (requiresDualApproval) {
                const existingDualInfo = pengajuanData.dualApprovalInfo;
                const existingPhase = existingDualInfo?.approvalPhase || '';

                // Hanya initialize jika belum ada atau masih kosong
                if (!existingDualInfo || !existingPhase) {
                    await snapshot.ref.child('dualApprovalInfo').set({
                        requiresDualApproval: true,
                        approvalPhase: ApprovalPhase.AWAITING_PIMPINAN,
                        pimpinanApproval: { 
                            status: 'pending', by: '', uid: '', 
                            timestamp: 0, note: '',
                            adjustedAmount: 0, adjustedTenor: 0
                        },
                        koordinatorApproval: { 
                            status: 'pending', by: '', uid: '', 
                            timestamp: 0, note: '',
                            adjustedAmount: 0, adjustedTenor: 0
                        },
                        pengawasApproval: { 
                            status: 'pending', by: '', uid: '', 
                            timestamp: 0, note: '',
                            adjustedAmount: 0, adjustedTenor: 0
                        },
                        finalDecision: '',
                        finalDecisionBy: '',
                        finalDecisionTimestamp: 0,
                        rejectionReason: '',
                        koordinatorFinalConfirmed: false,
                        koordinatorFinalTimestamp: 0,
                        pimpinanFinalConfirmed: false,
                        pimpinanFinalTimestamp: 0
                    });
                    console.log('✅ DualApprovalInfo initialized with Phase 1 (AWAITING_PIMPINAN)');
                } else {
                    console.log(`ℹ️ DualApprovalInfo sudah ada (phase: ${existingPhase}), skip overwrite`);
                }
            }


            // Dapatkan pimpinan UID
            const cabangSnap = await db.ref(`metadata/cabang/${cabangId}`).once('value');
            if (!cabangSnap.exists()) {
                console.error('❌ Cabang tidak ditemukan:', cabangId);
                return null;
            }

            const pimpinanUid = cabangSnap.val().pimpinanUid;
            if (!pimpinanUid) {
                console.error('❌ Pimpinan UID tidak ditemukan');
                return null;
            }

            // =========================================================================
            // Simpan & kirim notifikasi HANYA ke PIMPINAN
            // =========================================================================
            const notifPimpinan = {
                type: 'NEW_PENGAJUAN',
                title: requiresDualApproval 
                    ? '📋 Pengajuan Pinjaman Besar (Tahap 1/5)'
                    : '📋 Pengajuan Pinjaman Baru',
                message: `${pengajuanData.namaPanggilan || 'Nasabah'} mengajukan pinjaman Rp ${formatRupiah(besarPinjaman)}`,
                pelangganId: pengajuanData.pelangganId || '',
                pelangganNama: pengajuanData.namaPanggilan || 'Nasabah',
                adminUid: pengajuanData.adminUid || '',
                besarPinjaman: besarPinjaman,
                cabangId: cabangId,
                pengajuanId: pengajuanId,
                requiresDualApproval: requiresDualApproval,
                approvalPhase: requiresDualApproval ? ApprovalPhase.AWAITING_PIMPINAN : '',
                timestamp: admin.database.ServerValue.TIMESTAMP,
                read: false
            };

            await db.ref(`admin_notifications/${pimpinanUid}`).push(notifPimpinan);
            console.log('✅ Notifikasi tersimpan untuk Pimpinan:', pimpinanUid);
            
            await sendPushNotification(
                pimpinanUid,
                notifPimpinan.title,
                notifPimpinan.message,
                'NEW_PENGAJUAN',
                pengajuanData, cabangId, pengajuanId, besarPinjaman,
                'pengajuan_channel'
            );

            if (requiresDualApproval) {
                console.log('ℹ️ Dual approval 5-phase - Koordinator akan dinotifikasi setelah Pimpinan review');
            }

            return { 
                success: true, 
                requiresDualApproval,
                phase: requiresDualApproval ? ApprovalPhase.AWAITING_PIMPINAN : 'single_approval'
            };

        } catch (error) {
            console.error('❌ Error:', error);
            return { success: false, error: error.message };
        }
    });

// =========================================================================
// TRIGGER: PIMPINAN SELESAI REVIEW (Phase 1 → Phase 2: KOORDINATOR)
// =========================================================================
// UPDATED: Sekarang kirim ke KOORDINATOR, bukan Pengawas
// =========================================================================
exports.onPimpinanReviewed = functions.database
    .ref('/pengajuan_approval/{cabangId}/{pengajuanId}/dualApprovalInfo/approvalPhase')
    .onUpdate(async (change, context) => {
        const newPhase = change.after.val();
        const oldPhase = change.before.val();

        // ✅ UPDATED: Trigger saat pindah ke AWAITING_KOORDINATOR (bukan AWAITING_PENGAWAS)
        if (newPhase !== ApprovalPhase.AWAITING_KOORDINATOR || oldPhase === newPhase) {
            return null;
        }

        const cabangId = context.params.cabangId;
        const pengajuanId = context.params.pengajuanId;

        try {
            console.log('🔄 Phase changed to AWAITING_KOORDINATOR - notifying Koordinator...');

            // Ambil data pengajuan
            const pengajuanSnap = await db.ref(`pengajuan_approval/${cabangId}/${pengajuanId}`).once('value');
            const pengajuanData = pengajuanSnap.val();

            if (!pengajuanData) {
                console.error('❌ Pengajuan tidak ditemukan');
                return null;
            }

            const dualInfo = pengajuanData.dualApprovalInfo;
            const pimpinanStatus = dualInfo?.pimpinanApproval?.status || 'pending';
            const pimpinanNote = dualInfo?.pimpinanApproval?.note || '';
            const pimpinanBy = dualInfo?.pimpinanApproval?.by || 'Pimpinan';

            // ✅ UPDATED: Dapatkan semua KOORDINATOR (bukan Pengawas)
            const koordinatorSnap = await db.ref('metadata/roles/koordinator').once('value');
            
            if (!koordinatorSnap.exists()) {
                console.warn('⚠️ Tidak ada koordinator terdaftar');
                return null;
            }

            const koordinatorUids = Object.keys(koordinatorSnap.val());
            console.log(`📤 Mengirim notifikasi ke ${koordinatorUids.length} koordinator`);

            const statusText = pimpinanStatus === 'approved' ? 'DISETUJUI' : 'DITOLAK';
            
            for (const koordinatorUid of koordinatorUids) {
                const notifKoordinator = {
                    type: 'PIMPINAN_REVIEWED',
                    title: `📋 Review Pinjaman - Pimpinan ${statusText} (Tahap 2/5)`,
                    message: `${pengajuanData.namaPanggilan} - Rp ${formatRupiah(pengajuanData.besarPinjaman)}. ` +
                             `Pimpinan (${pimpinanBy}): ${statusText}. Menunggu keputusan Anda.`,
                    pelangganId: pengajuanData.pelangganId || '',
                    pelangganNama: pengajuanData.namaPanggilan || 'Nasabah',
                    adminUid: pengajuanData.adminUid || '',
                    adminName: pengajuanData.adminName || '',
                    besarPinjaman: pengajuanData.besarPinjaman || 0,
                    cabangId: cabangId,
                    pengajuanId: pengajuanId,
                    pimpinanApprovalStatus: pimpinanStatus,
                    pimpinanNote: pimpinanNote,
                    approvalPhase: ApprovalPhase.AWAITING_KOORDINATOR,
                    timestamp: admin.database.ServerValue.TIMESTAMP,
                    read: false
                };

                // ✅ UPDATED: Simpan ke koordinator_notifications
                await db.ref(`koordinator_notifications/${koordinatorUid}`).push(notifKoordinator);
                
                await sendPushNotification(
                    koordinatorUid,
                    notifKoordinator.title,
                    notifKoordinator.message,
                    'PIMPINAN_REVIEWED',
                    pengajuanData, cabangId, pengajuanId, pengajuanData.besarPinjaman,
                    'koordinator_channel'
                );

                console.log(`✅ Notifikasi terkirim ke koordinator: ${koordinatorUid}`);
            }

            return { success: true, notifiedKoordinator: koordinatorUids.length };

        } catch (error) {
            console.error('❌ Error onPimpinanReviewed:', error);
            return { success: false, error: error.message };
        }
    });

// =========================================================================
// BARU: TRIGGER KOORDINATOR SELESAI REVIEW (Phase 2 → Phase 3: PENGAWAS)
// =========================================================================
exports.onKoordinatorReviewed = functions.database
    .ref('/pengajuan_approval/{cabangId}/{pengajuanId}/dualApprovalInfo/approvalPhase')
    .onUpdate(async (change, context) => {
        const newPhase = change.after.val();
        const oldPhase = change.before.val();

        // Hanya proses jika berpindah ke AWAITING_PENGAWAS
        if (newPhase !== ApprovalPhase.AWAITING_PENGAWAS || oldPhase === newPhase) {
            return null;
        }

        const cabangId = context.params.cabangId;
        const pengajuanId = context.params.pengajuanId;

        try {
            console.log('🔄 Phase changed to AWAITING_PENGAWAS - notifying Pengawas...');

            // Ambil data pengajuan
            const pengajuanSnap = await db.ref(`pengajuan_approval/${cabangId}/${pengajuanId}`).once('value');
            const pengajuanData = pengajuanSnap.val();

            if (!pengajuanData) {
                console.error('❌ Pengajuan tidak ditemukan');
                return null;
            }

            const dualInfo = pengajuanData.dualApprovalInfo;
            const koordinatorStatus = dualInfo?.koordinatorApproval?.status || 'pending';
            const koordinatorNote = dualInfo?.koordinatorApproval?.note || '';
            const koordinatorBy = dualInfo?.koordinatorApproval?.by || 'Koordinator';
            const pimpinanStatus = dualInfo?.pimpinanApproval?.status || 'pending';
            const pimpinanBy = dualInfo?.pimpinanApproval?.by || 'Pimpinan';

            // Dapatkan semua Pengawas
            const pengawasSnap = await db.ref('metadata/roles/pengawas').once('value');
            
            if (!pengawasSnap.exists()) {
                console.warn('⚠️ Tidak ada pengawas terdaftar');
                return null;
            }

            const pengawasUids = Object.keys(pengawasSnap.val());
            console.log(`📤 Mengirim notifikasi ke ${pengawasUids.length} pengawas`);

            const koordinatorStatusText = koordinatorStatus === 'approved' ? 'DISETUJUI' : 'DITOLAK';
            const pimpinanStatusText = pimpinanStatus === 'approved' ? 'Setuju' : 'Tolak';
            
            for (const pengawasUid of pengawasUids) {
                const notifPengawas = {
                    type: 'KOORDINATOR_REVIEWED',
                    title: `📋 Review Pinjaman - Koordinator ${koordinatorStatusText} (Tahap 3/5)`,
                    message: `${pengajuanData.namaPanggilan} - Rp ${formatRupiah(pengajuanData.besarPinjaman)}. ` +
                            `Pimpinan: ${pimpinanStatusText}, Koordinator (${koordinatorBy}): ${koordinatorStatusText}. Menunggu keputusan Anda.`,
                    pelangganId: pengajuanData.pelangganId || '',
                    pelangganNama: pengajuanData.namaPanggilan || 'Nasabah',
                    adminUid: pengajuanData.adminUid || '',
                    adminName: pengajuanData.adminName || '',
                    besarPinjaman: pengajuanData.besarPinjaman || 0,
                    cabangId: cabangId,
                    pengajuanId: pengajuanId,
                    pimpinanApprovalStatus: pimpinanStatus,
                    pimpinanNote: dualInfo?.pimpinanApproval?.note || '',         // ✅ PASTIKAN ADA
                    koordinatorApprovalStatus: koordinatorStatus,
                    koordinatorNote: koordinatorNote,
                    approvalPhase: ApprovalPhase.AWAITING_PENGAWAS,
                    timestamp: admin.database.ServerValue.TIMESTAMP,
                    read: false
                };

                await db.ref(`pengawas_notifications/${pengawasUid}`).push(notifPengawas);
                
                await sendPushNotification(
                    pengawasUid,
                    notifPengawas.title,
                    notifPengawas.message,
                    'KOORDINATOR_REVIEWED',
                    pengajuanData, cabangId, pengajuanId, pengajuanData.besarPinjaman,
                    'pengawas_channel'
                );

                console.log(`✅ Notifikasi terkirim ke pengawas: ${pengawasUid}`);
            }

            return { success: true, notifiedPengawas: pengawasUids.length };

        } catch (error) {
            console.error('❌ Error onKoordinatorReviewed:', error);
            return { success: false, error: error.message };
        }
    });

// =========================================================================
// UPDATED: TRIGGER PENGAWAS SELESAI REVIEW (Phase 3 → Phase 4: KOORDINATOR FINAL)
// =========================================================================
// PERUBAHAN: Sekarang kirim ke KOORDINATOR untuk finalisasi, bukan Pimpinan
// =========================================================================
exports.onPengawasReviewed = functions.database
    .ref('/pengajuan_approval/{cabangId}/{pengajuanId}/dualApprovalInfo/approvalPhase')
    .onUpdate(async (change, context) => {
        const newPhase = change.after.val();
        const oldPhase = change.before.val();

        // ✅ UPDATED: Trigger ke AWAITING_KOORDINATOR_FINAL (bukan AWAITING_PIMPINAN_FINAL)
        if (newPhase !== ApprovalPhase.AWAITING_KOORDINATOR_FINAL || oldPhase === newPhase) {
            return null;
        }

        const cabangId = context.params.cabangId;
        const pengajuanId = context.params.pengajuanId;

        try {
            console.log('🔄 Phase changed to AWAITING_KOORDINATOR_FINAL - notifying Koordinator for confirmation...');

            // Ambil data pengajuan
            const pengajuanSnap = await db.ref(`pengajuan_approval/${cabangId}/${pengajuanId}`).once('value');
            const pengajuanData = pengajuanSnap.val();

            if (!pengajuanData) {
                console.error('❌ Pengajuan tidak ditemukan');
                return null;
            }

            const dualInfo = pengajuanData.dualApprovalInfo;
            const pengawasStatus = dualInfo?.pengawasApproval?.status || 'pending';
            const pengawasNote = dualInfo?.pengawasApproval?.note || '';
            const pengawasBy = dualInfo?.pengawasApproval?.by || 'Pengawas';
            const adjustedAmount = dualInfo?.pengawasApproval?.adjustedAmount || 0;

            // ✅ UPDATED: Dapatkan semua KOORDINATOR
            const koordinatorSnap = await db.ref('metadata/roles/koordinator').once('value');
            if (!koordinatorSnap.exists()) {
                console.warn('⚠️ Tidak ada koordinator terdaftar');
                return null;
            }

            const koordinatorUids = Object.keys(koordinatorSnap.val());

            const statusText = pengawasStatus === 'approved' ? 'DISETUJUI' : 'DITOLAK';
            const amountText = adjustedAmount > 0 && adjustedAmount !== pengajuanData.besarPinjaman
                ? ` (Penyesuaian: Rp ${formatRupiah(adjustedAmount)})`
                : '';

            for (const koordinatorUid of koordinatorUids) {
                const notifKoordinator = {
                    type: 'PENGAWAS_REVIEWED',
                    title: `✅ Pengawas Selesai Review - ${statusText} (Tahap 4/5)`,
                    message: `${pengajuanData.namaPanggilan} - Pengawas (${pengawasBy}): ${statusText}${amountText}. ` +
                            `Silakan lakukan konfirmasi.`,
                    pelangganId: pengajuanData.pelangganId || '',
                    pelangganNama: pengajuanData.namaPanggilan || 'Nasabah',
                    adminUid: pengajuanData.adminUid || '',
                    besarPinjaman: pengajuanData.besarPinjaman || 0,
                    besarPinjamanDisetujui: adjustedAmount > 0 ? adjustedAmount : pengajuanData.besarPinjaman,
                    cabangId: cabangId,
                    pengajuanId: pengajuanId,
                    // Status dari semua approver
                    pimpinanApprovalStatus: dualInfo?.pimpinanApproval?.status || '',
                    pimpinanNote: dualInfo?.pimpinanApproval?.note || '',         // ✅ TAMBAH
                    koordinatorApprovalStatus: dualInfo?.koordinatorApproval?.status || '',
                    koordinatorNote: dualInfo?.koordinatorApproval?.note || '',   // ✅ TAMBAH
                    pengawasApprovalStatus: pengawasStatus,
                    pengawasNote: pengawasNote,
                    approvalPhase: ApprovalPhase.AWAITING_KOORDINATOR_FINAL,
                    timestamp: admin.database.ServerValue.TIMESTAMP,
                    read: false
                };

                // Simpan ke koordinator_notifications untuk tab finalisasi
                await db.ref(`koordinator_notifications/${koordinatorUid}`).push(notifKoordinator);

                await sendPushNotification(
                    koordinatorUid,
                    notifKoordinator.title,
                    notifKoordinator.message,
                    'PENGAWAS_REVIEWED',
                    pengajuanData, cabangId, pengajuanId, pengajuanData.besarPinjaman,
                    'koordinator_channel'
                );

                console.log(`✅ Notifikasi konfirmasi terkirim ke koordinator: ${koordinatorUid}`);
            }

            return { success: true };

        } catch (error) {
            console.error('❌ Error onPengawasReviewed:', error);
            return { success: false, error: error.message };
        }
    });

// =========================================================================
// BARU: TRIGGER KOORDINATOR FINAL CONFIRMED (Phase 4 → Phase 5: PIMPINAN FINAL)
// =========================================================================
exports.onKoordinatorFinalReviewed = functions.database
    .ref('/pengajuan_approval/{cabangId}/{pengajuanId}/dualApprovalInfo/approvalPhase')
    .onUpdate(async (change, context) => {
        const newPhase = change.after.val();
        const oldPhase = change.before.val();

        // Hanya proses jika berpindah ke AWAITING_PIMPINAN_FINAL
        if (newPhase !== ApprovalPhase.AWAITING_PIMPINAN_FINAL || oldPhase === newPhase) {
            return null;
        }

        const cabangId = context.params.cabangId;
        const pengajuanId = context.params.pengajuanId;

        try {
            console.log('🔄 Phase changed to AWAITING_PIMPINAN_FINAL - notifying Pimpinan for final action...');

            // Ambil data pengajuan
            const pengajuanSnap = await db.ref(`pengajuan_approval/${cabangId}/${pengajuanId}`).once('value');
            const pengajuanData = pengajuanSnap.val();

            if (!pengajuanData) {
                console.error('❌ Pengajuan tidak ditemukan');
                return null;
            }

            const dualInfo = pengajuanData.dualApprovalInfo;
            const pengawasStatus = dualInfo?.pengawasApproval?.status || 'pending';
            const pengawasBy = dualInfo?.pengawasApproval?.by || 'Pengawas';
            const koordinatorBy = dualInfo?.koordinatorApproval?.by || 'Koordinator';
            const adjustedAmount = dualInfo?.pengawasApproval?.adjustedAmount || 0;

            // Dapatkan Pimpinan UID
            const cabangSnap = await db.ref(`metadata/cabang/${cabangId}`).once('value');
            if (!cabangSnap.exists()) {
                console.error('❌ Cabang tidak ditemukan:', cabangId);
                return null;
            }

            const pimpinanUid = cabangSnap.val().pimpinanUid;
            if (!pimpinanUid) {
                console.error('❌ Pimpinan UID tidak ditemukan');
                return null;
            }

            const statusText = pengawasStatus === 'approved' ? 'DISETUJUI' : 'DITOLAK';
            const amountText = adjustedAmount > 0 && adjustedAmount !== pengajuanData.besarPinjaman
                ? ` (Penyesuaian: Rp ${formatRupiah(adjustedAmount)})`
                : '';

            const notifPimpinan = {
                type: 'KOORDINATOR_FINAL_REVIEWED',
                title: `✅ Koordinator Konfirmasi - ${statusText} (Tahap 5/5)`,
                message: `${pengajuanData.namaPanggilan} - Pengawas: ${statusText}${amountText}. ` +
                        `Koordinator sudah konfirmasi. Silakan lakukan finalisasi akhir.`,
                pelangganId: pengajuanData.pelangganId || '',
                pelangganNama: pengajuanData.namaPanggilan || 'Nasabah',
                adminUid: pengajuanData.adminUid || '',
                besarPinjaman: pengajuanData.besarPinjaman || 0,
                besarPinjamanDisetujui: adjustedAmount > 0 ? adjustedAmount : pengajuanData.besarPinjaman,
                cabangId: cabangId,
                pengajuanId: pengajuanId,
                // Status dari semua approver
                pimpinanApprovalStatus: dualInfo?.pimpinanApproval?.status || '',
                pimpinanNote: dualInfo?.pimpinanApproval?.note || '',         // ✅ TAMBAH
                koordinatorApprovalStatus: dualInfo?.koordinatorApproval?.status || '',
                koordinatorNote: dualInfo?.koordinatorApproval?.note || '',   // ✅ TAMBAH
                pengawasApprovalStatus: pengawasStatus,
                pengawasBy: pengawasBy,
                pengawasNote: dualInfo?.pengawasApproval?.note || '',         // ✅ TAMBAH
                koordinatorBy: koordinatorBy,
                approvalPhase: ApprovalPhase.AWAITING_PIMPINAN_FINAL,
                timestamp: admin.database.ServerValue.TIMESTAMP,
                read: false
            };

            // Simpan ke pimpinan_final_notifications
            await db.ref(`pimpinan_final_notifications/${pimpinanUid}`).push(notifPimpinan);
            
            // Juga simpan ke admin_notifications
            await db.ref(`admin_notifications/${pimpinanUid}`).push(notifPimpinan);

            await sendPushNotification(
                pimpinanUid,
                notifPimpinan.title,
                notifPimpinan.message,
                'KOORDINATOR_FINAL_REVIEWED',
                pengajuanData, cabangId, pengajuanId, pengajuanData.besarPinjaman,
                'pengajuan_channel'
            );

            console.log(`✅ Notifikasi finalisasi terkirim ke pimpinan: ${pimpinanUid}`);

            return { success: true };

        } catch (error) {
            console.error('❌ Error onKoordinatorFinalReviewed:', error);
            return { success: false, error: error.message };
        }
    });

// =========================================================================
// TRIGGER: APPROVAL SELESAI (NOTIFY ADMIN LAPANGAN)
// =========================================================================
// TIDAK BERUBAH: Hanya kirim ke admin setelah pimpinanFinalConfirmed = true
// =========================================================================
exports.onDualApprovalComplete = functions.database
    .ref('/pengajuan_approval/{cabangId}/{pengajuanId}/dualApprovalInfo/approvalPhase')
    .onUpdate(async (change, context) => {
        const newPhase = change.after.val();
        const oldPhase = change.before.val();

        // Hanya proses jika berpindah ke COMPLETED
        if (newPhase !== ApprovalPhase.COMPLETED || oldPhase === newPhase) {
            return null;
        }

        const cabangId = context.params.cabangId;
        const pengajuanId = context.params.pengajuanId;

        try {
            console.log(`🏁 Dual approval COMPLETED`);

            const pengajuanSnap = await db.ref(`pengajuan_approval/${cabangId}/${pengajuanId}`).once('value');
            const pengajuanData = pengajuanSnap.val();

            if (!pengajuanData) {
                console.error('❌ Pengajuan tidak ditemukan');
                return null;
            }

            const adminUid = pengajuanData.adminUid;
            const dualInfo = pengajuanData.dualApprovalInfo;

            // Validasi bahwa pimpinan sudah konfirmasi
            if (!dualInfo?.pimpinanFinalConfirmed) {
                console.log('⏭️ Skip - pimpinan belum konfirmasi final');
                return null;
            }

            const pengawasStatus = dualInfo?.pengawasApproval?.status || 'pending';
            const isApproved = pengawasStatus === 'approved';
            const pengawasBy = dualInfo?.pengawasApproval?.by || 'Pengawas';
            const rejectionReason = dualInfo?.pengawasApproval?.note || dualInfo?.rejectionReason || '';

            const notifType = isApproved ? 'DUAL_APPROVAL_APPROVED' : 'DUAL_APPROVAL_REJECTED';
            
            const notifData = {
                type: notifType,
                title: isApproved ? '✅ Pengajuan Disetujui' : '❌ Pengajuan Ditolak',
                message: isApproved 
                    ? `Pengajuan ${pengajuanData.namaPanggilan} telah DISETUJUI. Silakan proses pencairan.`
                    : `Pengajuan ${pengajuanData.namaPanggilan} DITOLAK oleh Pengawas. Alasan: ${rejectionReason}`,
                pelangganId: pengajuanData.pelangganId,
                pelangganNama: pengajuanData.namaPanggilan,
                besarPinjaman: pengajuanData.besarPinjaman,
                decision: isApproved ? 'approved' : 'rejected',
                // Status dari semua approver
                pimpinanStatus: dualInfo?.pimpinanApproval?.status || '',
                pimpinanBy: dualInfo?.pimpinanApproval?.by || '',
                pimpinanNote: dualInfo?.pimpinanApproval?.note || '',         // ✅ TAMBAH
                koordinatorStatus: dualInfo?.koordinatorApproval?.status || '',
                koordinatorBy: dualInfo?.koordinatorApproval?.by || '',
                koordinatorNote: dualInfo?.koordinatorApproval?.note || '',   // ✅ TAMBAH
                pengawasStatus: pengawasStatus,
                pengawasBy: pengawasBy,
                pengawasNote: dualInfo?.pengawasApproval?.note || '',         // ✅ TAMBAH
                timestamp: admin.database.ServerValue.TIMESTAMP,
                read: false
            };

            // Simpan notifikasi ke database admin
            await db.ref(`admin_notifications/${adminUid}`).push(notifData);
            console.log('✅ Notifikasi tersimpan untuk admin lapangan:', adminUid);

            // Kirim push notification ke admin
            await sendPushNotification(
                adminUid,
                notifData.title,
                notifData.message,
                notifType,
                pengajuanData, cabangId, pengajuanId,
                pengajuanData.besarPinjaman,
                'approval_channel'
            );

            return { success: true };

        } catch (error) {
            console.error('❌ Error:', error);
            return { success: false, error: error.message };
        }
    });

// =========================================================================
// HELPER: KIRIM PUSH NOTIFICATION
// =========================================================================
async function sendPushNotification(
    userUid, title, body, 
    type,
    pengajuanData, cabangId, pengajuanId, besarPinjaman,
    channelId = 'pengajuan_channel'
) {
    try {
        const tokenSnap = await db.ref(`fcm_tokens/${userUid}`).once('value');
        if (!tokenSnap.exists()) {
            console.warn(`⚠️ FCM token tidak ditemukan: ${userUid}`);
            return { success: false, reason: 'no_fcm_token' };
        }

        const fcmToken = tokenSnap.val().token;
        if (!fcmToken) {
            console.warn(`⚠️ FCM token kosong: ${userUid}`);
            return { success: false, reason: 'empty_token' };
        }

        const message = {
            token: fcmToken,
            data: {
                type: type,
                title: title,
                message: body,
                pelangganId: pengajuanData.pelangganId || '',
                pelangganNama: pengajuanData.namaPanggilan || '',
                adminUid: pengajuanData.adminUid || '',
                cabangId: cabangId,
                pengajuanId: pengajuanId,
                besarPinjaman: String(besarPinjaman),
                channelId: channelId,
                timestamp: String(Date.now())
            },
            android: {
                priority: 'high',
                notification: {
                    channelId: channelId,
                    title: title,
                    body: body,
                    icon: 'ic_notification',
                    color: type.includes('KOORDINATOR') ? '#10B981' : // Green for koordinator
                           type.includes('PENGAWAS') ? '#7C3AED' :    // Purple for pengawas
                           '#2196F3',                                  // Blue for others
                    sound: 'default',
                    defaultVibrateTimings: true,
                    defaultSound: true
                }
            },
            apns: {
                payload: { aps: { sound: 'default', badge: 1, 'content-available': 1 } },
                headers: { 'apns-priority': '10' }
            }
        };

        console.log(`📤 Sending push to ${userUid} with type: ${type}`);
        const response = await admin.messaging().send(message);
        console.log(`✅ Push sent to ${userUid}:`, response);
        return { success: true, messageId: response };

    } catch (error) {
        console.error(`❌ Error sending push to ${userUid}:`, error);
        if (error.code === 'messaging/invalid-registration-token' ||
            error.code === 'messaging/registration-token-not-registered') {
            await db.ref(`fcm_tokens/${userUid}`).remove();
        }
        return { success: false, error: error.message };
    }
}