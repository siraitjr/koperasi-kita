// functions/index.js
// =========================================================================
// FIREBASE CLOUD FUNCTIONS - ENTRY POINT (v7 - SEQUENTIAL APPROVAL)
// =========================================================================
//
// PERUBAHAN v7 - SEQUENTIAL THREE-PHASE APPROVAL:
// ✅ onNewPengajuanCreated: Hanya notif ke Pimpinan
// ✅ onPimpinanReviewed: Notif ke Pengawas setelah Pimpinan aksi (Phase 1→2)
// ✅ onPengawasReviewed: Notif ke Pimpinan setelah Pengawas aksi (Phase 2→3)
// ✅ onDualApprovalComplete: Notif ke Admin setelah Pimpinan finalisasi (Phase 3→Complete)
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');
admin.initializeApp({
    databaseURL: "https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app"
});

// Import Cloud Functions
const onPelangganWrite = require('./onPelangganWrite');
const onPembayaranAdded = require('./onPembayaranAdded');
const onNewPengajuanCreated = require('./onNewPengajuanCreated');
const onAdminNotificationCreated = require('./onAdminNotificationCreated');
const scheduledFunctions = require('./scheduledFunctions');
const updateAllSummaries = require('./updateAllSummaries');
const onSerahTerimaCreated = require('./onSerahTerimaCreated');
const onNikRegistry = require('./onNikRegistry');
const rekeningKoran = require('./rekeningKoranService');
const summaryRepair = require('./summaryRepair_HEMAT');
const resetUserPassword = require('./resetUserPassword');
const broadcastAndRequests = require('./onBroadcastAndRequests');
const nasabahIndex = require('./onNasabahIndexUpdate');
const fixAdminNames = require('./fixAllAdminNames');
const trackingFunctions = require('./onTrackingActivated');
const bukuPokokApi = require('./bukuPokokApi');
const remoteTakeover = require('./remoteTakeover');
const kasirApi = require('./kasirApi');
const detectDuplicate = require('./detectDuplicateNasabah');
const { cleanupDuplicateApprovals } = require('./cleanupDuplicateApprovals');

// =========================================================================
// PELANGGAN TRIGGERS
// =========================================================================
exports.onPelangganWrite = onPelangganWrite.onPelangganWrite;

// =========================================================================
// PEMBAYARAN TRIGGERS
// =========================================================================
exports.onPembayaranAdded = onPembayaranAdded.onPembayaranAdded;
exports.onSubPembayaranAdded = onPembayaranAdded.onSubPembayaranAdded;
exports.onPelangganApproved = onPembayaranAdded.onPelangganApproved;

// =========================================================================
// NIK REGISTRY TRIGGERS
// =========================================================================
exports.onPelangganCreatedRegisterNik = onNikRegistry.onPelangganCreatedRegisterNik;
exports.onPembayaranUpdateNikStatus = onNikRegistry.onPembayaranUpdateNikStatus;
exports.onSubPembayaranUpdateNikStatus = onNikRegistry.onSubPembayaranUpdateNikStatus;
exports.onStatusChangeUpdateNik = onNikRegistry.onStatusChangeUpdateNik;
exports.onPelangganDeletedRemoveNik = onNikRegistry.onPelangganDeletedRemoveNik;

// =========================================================================
// PUSH NOTIFICATION TRIGGERS
// =========================================================================
exports.onNewPengajuanCreated = onNewPengajuanCreated.onNewPengajuanCreated;
exports.onAdminNotificationCreated = onAdminNotificationCreated.onAdminNotificationCreated;
exports.onSerahTerimaCreated = onSerahTerimaCreated.onSerahTerimaCreated;

// =========================================================================
// SEQUENTIAL DUAL APPROVAL TRIGGERS (v8 - FIVE-PHASE)
// =========================================================================
// ALUR:
// 1. onNewPengajuanCreated: Pengajuan >= 3jt → Notif ke Pimpinan (Phase 1)
// 2. onPimpinanReviewed: Pimpinan aksi → Notif ke Koordinator (Phase 1→2)
// 3. onKoordinatorReviewed: Koordinator aksi → Notif ke Pengawas (Phase 2→3)
// 4. onPengawasReviewed: Pengawas aksi → Notif ke Koordinator Final (Phase 3→4)
// 5. onKoordinatorFinalReviewed: Koordinator konfirmasi → Notif ke Pimpinan Final (Phase 4→5)
// 6. onDualApprovalComplete: Pimpinan finalisasi → Notif ke Admin (Phase 5→Complete)
// =========================================================================

// ✅ BARU: Trigger saat Pimpinan selesai review (Phase 1 → Phase 2)
// Mengirim notifikasi ke Pengawas
exports.onPimpinanReviewed = onNewPengajuanCreated.onPimpinanReviewed;

// ✅ BARU: Trigger saat Pengawas selesai review (Phase 2 → Phase 3)
// Mengirim notifikasi ke Pimpinan untuk finalisasi
exports.onPengawasReviewed = onNewPengajuanCreated.onPengawasReviewed;

// ✅ UPDATED: Trigger saat approval selesai (Phase 3 → Complete)
// Mengirim notifikasi hasil akhir ke Admin Lapangan
exports.onDualApprovalComplete = onNewPengajuanCreated.onDualApprovalComplete;

// ✅ BARU: Trigger saat Koordinator selesai review (Phase 2 → Phase 3: PENGAWAS)
exports.onKoordinatorReviewed = onNewPengajuanCreated.onKoordinatorReviewed;

// ✅ BARU: Trigger saat Koordinator final confirmed (Phase 4 → Phase 5: PIMPINAN FINAL)
exports.onKoordinatorFinalReviewed = onNewPengajuanCreated.onKoordinatorFinalReviewed;

// =========================================================================
// SCHEDULED FUNCTIONS
// =========================================================================
exports.dailySummaryReset = scheduledFunctions.dailySummaryReset;
exports.dailyTargetRecalc = scheduledFunctions.dailyTargetRecalc;
exports.dailyUpdatePelangganBermasalah = scheduledFunctions.dailyUpdatePelangganBermasalah;
exports.weeklyFullRecalc = scheduledFunctions.weeklyFullRecalc;
exports.cleanupProcessedApprovals = scheduledFunctions.cleanupProcessedApprovals;
exports.cleanupOldNotifications = scheduledFunctions.cleanupOldNotifications;
exports.cleanupOldEventHarian = scheduledFunctions.cleanupOldEventHarian;
exports.summaryHealthCheck = summaryRepair.smartHealthCheck;

// =========================================================================
// MANUAL TRIGGERS
// =========================================================================
exports.updateAllSummaries = updateAllSummaries.updateAllSummaries;
exports.triggerTargetRecalc = updateAllSummaries.triggerTargetRecalc;
exports.recalculateNow = updateAllSummaries.recalculateNow;
exports.searchNikGlobal = onNikRegistry.searchNikGlobal;

// =========================================================================
// HTTP FUNCTIONS
// =========================================================================
exports.backfillPembayaranHarian = scheduledFunctions.backfillPembayaranHarian;
exports.backfillEventHarian = scheduledFunctions.backfillEventHarian;
exports.updatePelangganBermasalah = scheduledFunctions.updatePelangganBermasalah;
exports.cleanupExpiredBroadcasts = scheduledFunctions.cleanupExpiredBroadcasts;
exports.migrateNikToRegistry = onNikRegistry.migrateNikToRegistry;
exports.getRekeningKoran = rekeningKoran.getRekeningKoran;
exports.repairAllSummaries = summaryRepair.repairAllSummaries;
exports.repairAdminSummary = summaryRepair.repairAdminSummary;


// =========================================================================
// EXPORT EXCEL HARIAN (PRODUCTION)
// =========================================================================
const { exportHarian } = require("./exportExcel");

exports.exportExcelHarian = functions.pubsub
  .schedule("0 23 * * *")
  .timeZone("Asia/Jakarta")
  .onRun(async () => {
    await exportHarian();
    return null;
  });

// =========================================================================
// USER MANAGEMENT FUNCTIONS (PENGAWAS ONLY)
// =========================================================================
exports.resetUserPassword = resetUserPassword.resetUserPassword;
exports.getAllUsers = resetUserPassword.getAllUsers;

// =========================================================================
// ✅ BARU: BROADCAST & REQUEST NOTIFICATIONS
// =========================================================================
exports.onBroadcastCreated = broadcastAndRequests.onBroadcastCreated;
exports.onTenorChangeRequestCreated = broadcastAndRequests.onTenorChangeRequestCreated;
exports.onDeletionRequestCreated = broadcastAndRequests.onDeletionRequestCreated;

// =========================================================================
// NASABAH INDEX UNTUK PIMPINAN
// =========================================================================
exports.updateNasabahIndex = nasabahIndex.updateNasabahIndex;
exports.backfillNasabahIndex = nasabahIndex.backfillNasabahIndex;
exports.clearNasabahIndex = nasabahIndex.clearNasabahIndex;
exports.getNasabahIndexStats = nasabahIndex.getNasabahIndexStats;

// =========================================================================
// FIX ADMIN NAMES (Email → Nama) - ONE-TIME BACKFILL
// =========================================================================
exports.fixAllAdminNames = fixAdminNames.fixAllAdminNames;

// =========================================================================
// ✅ BARU: LOCATION TRACKING (PENGAWAS ONLY)
// =========================================================================
exports.onTrackingActivated = trackingFunctions.onTrackingActivated;

// =========================================================================
// BUKU POKOK WEB API
// =========================================================================
exports.getBukuPokok = bukuPokokApi.getBukuPokok;
exports.getBukuPokokSummary = bukuPokokApi.getBukuPokokSummary;
exports.getPembayaranHariIni = bukuPokokApi.getPembayaranHariIni;

// =========================================================================
// REMOTE TAKEOVER (PIMPINAN → ADMIN LAPANGAN)
// =========================================================================
exports.generateTakeoverToken = remoteTakeover.generateTakeoverToken;
exports.restorePimpinanSession = remoteTakeover.restorePimpinanSession;

// =========================================================================
// KASIR WEB API
// =========================================================================
exports.getKasirSummary = kasirApi.getKasirSummary;
exports.getKasirEntries = kasirApi.getKasirEntries;
exports.addKasirEntry = kasirApi.addKasirEntry;
exports.deleteKasirEntry = kasirApi.deleteKasirEntry;


exports.scanDuplicateNasabah = detectDuplicate.scanDuplicateNasabah;
exports.cleanupDuplicateNasabah = detectDuplicate.cleanupDuplicateNasabah;

exports.cleanupDuplicateApprovals = functions.https.onRequest(cleanupDuplicateApprovals);