// functions/onAdminNotificationCreated.js
// =========================================================================
// CLOUD FUNCTION: KIRIM PUSH NOTIFICATION SAAT ADA NOTIFIKASI BARU
// =========================================================================
//
// FUNGSI INI HANYA MENGIRIM PUSH NOTIFICATION, TIDAK MEMBUAT NOTIFIKASI BARU
//
// Flow:
// 1. Android simpan notifikasi ke /admin_notifications/{uid}/{notifId}
// 2. Cloud Function ini trigger
// 3. Ambil FCM token
// 4. Kirim push notification
// 5. Admin dapat popup di HP!
// =========================================================================

const functions = require("firebase-functions");
const admin = require("firebase-admin");

const db = admin.database();

/**
 * Trigger saat ada notifikasi baru di /admin_notifications/{adminUid}/{notificationId}
 */
exports.onAdminNotificationCreated = functions.database
    .ref('/admin_notifications/{adminUid}/{notificationId}')
    .onCreate(async (snapshot, context) => {
        const adminUid = context.params.adminUid;
        const notificationId = context.params.notificationId;
        const notificationData = snapshot.val();

        // Skip jika data tidak valid
        if (!notificationData || !notificationData.type) {
            console.log('âš ï¸ Skip: Invalid notification data');
            return null;
        }

        // Skip jika notifikasi ini untuk Pimpinan (type NEW_PENGAJUAN)
        // Karena sudah di-handle oleh onNewPengajuanCreated
        if (notificationData.type === 'NEW_PENGAJUAN') {
            console.log('âš ï¸ Skip: NEW_PENGAJUAN handled by onNewPengajuanCreated');
            return null;
        }

        try {
            console.log('ðŸ“¬ New admin notification:', {
                adminUid: adminUid,
                notificationId: notificationId,
                type: notificationData.type,
                title: notificationData.title
            });

            // =========================================================================
            // 1. DAPATKAN FCM TOKEN ADMIN
            // =========================================================================
            const tokenSnap = await db.ref(`fcm_tokens/${adminUid}`).once('value');

            if (!tokenSnap.exists()) {
                console.warn('âš ï¸ FCM token tidak ditemukan untuk admin:', adminUid);
                return { success: true, pushSent: false, reason: 'no_fcm_token' };
            }

            const fcmData = tokenSnap.val();
            const fcmToken = fcmData.token;

            if (!fcmToken) {
                console.warn('âš ï¸ FCM token kosong');
                return { success: true, pushSent: false, reason: 'empty_token' };
            }

            console.log('ðŸ”‘ FCM Token ditemukan untuk admin');

            // =========================================================================
            // 2. SIAPKAN NOTIFICATION PAYLOAD
            // =========================================================================
            const isApproval = notificationData.type === 'APPROVAL';
            const isRejection = notificationData.type === 'REJECTION';

            let emoji = 'ðŸ“‹';
            let channelId = 'general_channel';
            let color = '#2196F3';
            
            if (isApproval) {
                emoji = 'âœ…';
                channelId = 'approval_channel';
                color = '#4CAF50';
            } else if (isRejection) {
                emoji = 'âŒ';
                channelId = 'approval_channel';
                color = '#F44336';
            }

            const notificationTitle = `${emoji} ${notificationData.title || 'Notifikasi'}`;
            const notificationBody = notificationData.message || 'Ada notifikasi baru';

            // =========================================================================
            // 3. KIRIM PUSH NOTIFICATION
            // =========================================================================
            const message = {
                token: fcmToken,

                // âœ… DATA PAYLOAD - ini yang akan diteruskan ke MainActivity
                data: {
                    type: notificationData.type || '',
                    title: notificationTitle,
                    message: notificationBody,
                    pelangganId: notificationData.pelangganId || '',
                    pelangganNama: notificationData.pelangganNama || '',
                    notificationId: notificationId,
                    pinjamanDiajukan: String(notificationData.pinjamanDiajukan || 0),
                    pinjamanDisetujui: String(notificationData.pinjamanDisetujui || 0),
                    tenorDiajukan: String(notificationData.tenorDiajukan || 0),
                    tenorDisetujui: String(notificationData.tenorDisetujui || 0),
                    isPinjamanDiubah: String(notificationData.isPinjamanDiubah || false),
                    timestamp: String(Date.now())
                },

                // âœ… ANDROID CONFIG - TANPA clickAction!
                android: {
                    priority: 'high',
                    notification: {
                        channelId: channelId,
                        title: notificationTitle,
                        body: notificationBody,
                        icon: 'ic_notification',
                        color: color,
                        sound: 'default',
                        defaultVibrateTimings: true,
                        defaultSound: true
                        // âŒ HAPUS clickAction - biarkan Android buka default launcher
                    }
                },

                // APNs config untuk iOS
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
            console.log('âœ… Push notification berhasil dikirim ke admin:', response);

            return { 
                success: true, 
                pushSent: true, 
                messageId: response,
                type: notificationData.type
            };

        } catch (error) {
            console.error('âŒ Error sending push notification:', error);

            if (error.code === 'messaging/invalid-registration-token' ||
                error.code === 'messaging/registration-token-not-registered') {
                console.log('ðŸ—‘ï¸ Removing invalid FCM token for admin:', adminUid);
                
                try {
                    await db.ref(`fcm_tokens/${adminUid}`).remove();
                    console.log('âœ… Invalid token removed');
                } catch (removeError) {
                    console.error('Error removing token:', removeError);
                }
            }

            return { success: false, error: error.message };
        }
    });