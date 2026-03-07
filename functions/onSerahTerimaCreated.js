// functions/onSerahTerimaCreated.js
// =========================================================================
// CLOUD FUNCTION: KIRIM PUSH NOTIFICATION SAAT ADA SERAH TERIMA BARU
// =========================================================================
//
// FUNGSI INI MENGIRIM PUSH NOTIFICATION KE PIMPINAN SAAT:
// - Admin lapangan upload foto serah terima uang
// - Data tersimpan ke /serah_terima_notifications/{pimpinanUid}/{notificationId}
//
// Flow:
// 1. Admin upload foto serah terima → Android simpan ke serah_terima_notifications
// 2. Cloud Function ini trigger
// 3. Ambil FCM token pimpinan
// 4. Kirim push notification
// 5. HP Pimpinan berbunyi! 🔔
// =========================================================================

const functions = require("firebase-functions");
const admin = require("firebase-admin");

const db = admin.database();

/**
 * Format angka ke Rupiah
 */
function formatRupiah(angka) {
    return new Intl.NumberFormat('id-ID').format(angka);
}

/**
 * Trigger saat ada serah terima baru di /serah_terima_notifications/{pimpinanUid}/{notificationId}
 */
exports.onSerahTerimaCreated = functions.database
    .ref('/serah_terima_notifications/{pimpinanUid}/{notificationId}')
    .onCreate(async (snapshot, context) => {
        const pimpinanUid = context.params.pimpinanUid;
        const notificationId = context.params.notificationId;
        const notificationData = snapshot.val();

        // Skip jika data tidak valid
        if (!notificationData) {
            console.log('⚠️ Skip: Invalid notification data');
            return null;
        }

        try {
            console.log('📸 =====================================');
            console.log('📸 SERAH TERIMA NOTIFICATION');
            console.log('📸 =====================================');
            console.log('📸 Pimpinan UID:', pimpinanUid);
            console.log('📸 Notification ID:', notificationId);
            console.log('📸 Pelanggan:', notificationData.pelangganNama);
            console.log('📸 Admin:', notificationData.adminName);
            console.log('📸 Pinjaman:', formatRupiah(notificationData.besarPinjaman || 0));

            // =========================================================================
            // 1. DAPATKAN FCM TOKEN PIMPINAN
            // =========================================================================
            const tokenSnap = await db.ref(`fcm_tokens/${pimpinanUid}`).once('value');

            if (!tokenSnap.exists()) {
                console.warn('⚠️ FCM token tidak ditemukan untuk pimpinan:', pimpinanUid);
                return { success: true, pushSent: false, reason: 'no_fcm_token' };
            }

            const fcmData = tokenSnap.val();
            const fcmToken = fcmData.token;

            if (!fcmToken) {
                console.warn('⚠️ FCM token kosong');
                return { success: true, pushSent: false, reason: 'empty_token' };
            }

            console.log('🔑 FCM Token ditemukan untuk pimpinan');

            // =========================================================================
            // 2. SIAPKAN NOTIFICATION PAYLOAD
            // =========================================================================
            const pelangganNama = notificationData.pelangganNama || 'Nasabah';
            const adminName = notificationData.adminName || 'Admin';
            const besarPinjaman = notificationData.besarPinjaman || 0;

            const notificationTitle = '📸 Bukti Serah Terima Uang';
            const notificationBody = `${adminName} telah menyerahkan uang Rp ${formatRupiah(besarPinjaman)} kepada ${pelangganNama}`;

            // =========================================================================
            // 3. KIRIM PUSH NOTIFICATION
            // =========================================================================
            const message = {
                token: fcmToken,

                // ✅ DATA PAYLOAD - ini yang akan diteruskan ke MainActivity
                data: {
                    type: 'SERAH_TERIMA',
                    title: notificationTitle,
                    message: notificationBody,
                    notificationId: notificationId,
                    pelangganId: notificationData.pelangganId || '',
                    pelangganNama: pelangganNama,
                    adminUid: notificationData.adminUid || '',
                    adminName: adminName,
                    besarPinjaman: String(besarPinjaman),
                    tenor: String(notificationData.tenor || 0),
                    fotoSerahTerimaUrl: notificationData.fotoSerahTerimaUrl || '',
                    tanggalSerahTerima: notificationData.tanggalSerahTerima || '',
                    timestamp: String(Date.now()),
                    click_action: 'OPEN_SERAH_TERIMA'
                },

                // ✅ ANDROID CONFIG
                android: {
                    priority: 'high',
                    notification: {
                        channelId: 'serah_terima_channel',
                        title: notificationTitle,
                        body: notificationBody,
                        icon: 'ic_notification',
                        color: '#4CAF50',  // Hijau untuk serah terima sukses
                        sound: 'default',
                        defaultVibrateTimings: true,
                        defaultSound: true
                    }
                },

                // APNs config untuk iOS (jika ada)
                apns: {
                    payload: {
                        aps: {
                            sound: 'default',
                            badge: 1,
                            'content-available': 1
                        }
                    },
                    headers: {
                        'apns-priority': '10'
                    }
                }
            };

            const response = await admin.messaging().send(message);
            console.log('✅ Push notification berhasil dikirim ke pimpinan:', response);

            return { 
                success: true, 
                pushSent: true, 
                messageId: response,
                pimpinanUid: pimpinanUid,
                pelangganNama: pelangganNama
            };

        } catch (error) {
            console.error('❌ Error sending push notification:', error);

            // Handle invalid token
            if (error.code === 'messaging/invalid-registration-token' ||
                error.code === 'messaging/registration-token-not-registered') {
                console.log('🗑️ Removing invalid FCM token for pimpinan:', pimpinanUid);
                
                try {
                    await db.ref(`fcm_tokens/${pimpinanUid}`).remove();
                    console.log('✅ Invalid token removed');
                } catch (removeError) {
                    console.error('Error removing token:', removeError);
                }
            }

            return { success: false, error: error.message };
        }
    });