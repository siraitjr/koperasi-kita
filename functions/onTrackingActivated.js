const functions = require('firebase-functions');
const admin = require('firebase-admin');

/**
 * =========================================================================
 * TRACKING ACTIVATION TRIGGER
 * =========================================================================
 * 
 * Trigger saat Pengawas mengaktifkan/mematikan tracking.
 * Mengirim silent FCM push ke device target sebagai backup
 * (jika app target sedang ter-kill).
 * =========================================================================
 */
exports.onTrackingActivated = functions.database
    .ref('/location_tracking/{targetUid}/active')
    .onWrite(async (change, context) => {
        const targetUid = context.params.targetUid;
        const isActive = change.after.val();
        const wasPreviouslyActive = change.before.val();

        // Skip jika tidak ada perubahan
        if (isActive === wasPreviouslyActive) return null;

        console.log(`🔍 Tracking ${isActive ? 'ACTIVATED' : 'DEACTIVATED'} for ${targetUid}`);

        try {
            // Ambil FCM token target
            const tokenSnapshot = await admin.database()
                .ref(`fcm_tokens/${targetUid}`)
                .once('value');
            
            const tokenData = tokenSnapshot.val();
            if (!tokenData) {
                console.log(`⚠️ No FCM token for ${targetUid}`);
                return null;
            }

            // Token bisa berupa string langsung atau object dengan field 'token'
            const token = typeof tokenData === 'string' 
                ? tokenData 
                : tokenData.token || tokenData;

            if (!token || typeof token !== 'string') {
                console.log(`⚠️ Invalid token format for ${targetUid}`);
                return null;
            }

            // Kirim SILENT push (data-only, tanpa notification)
            const message = {
                token: token,
                data: {
                    type: isActive ? 'TRACKING_ACTIVATE' : 'TRACKING_DEACTIVATE',
                    targetUid: targetUid,
                    timestamp: Date.now().toString()
                },
                android: {
                    priority: 'high',  // High priority agar bisa wake up device
                    ttl: 0             // Langsung, tidak di-delay
                }
            };

            await admin.messaging().send(message);
            console.log(`✅ Silent push sent to ${targetUid}`);

        } catch (error) {
            console.error(`❌ Error sending tracking push: ${error.message}`);
        }

        return null;
    });