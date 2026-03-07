// =========================================================================
// CLOUD FUNCTIONS: BROADCAST & REQUEST NOTIFICATIONS
// =========================================================================
// 
// Functions:
// 1. onBroadcastCreated - Push notification ke semua karyawan saat broadcast
// 2. onTenorChangeRequestCreated - Push notification ke Pimpinan
// 3. onDeletionRequestCreated - Push notification ke Pimpinan
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

// =========================================================================
// 1. BROADCAST NOTIFICATION
// Trigger: Saat Pengawas membuat broadcast baru
// Target: Semua karyawan (Admin, Pimpinan, Koordinator)
// =========================================================================
exports.onBroadcastCreated = functions.database
    .ref('/broadcast_messages/{messageId}')
    .onCreate(async (snapshot, context) => {
        const broadcast = snapshot.val();
        const messageId = context.params.messageId;
        
        console.log(`📢 New broadcast created: ${broadcast.title}`);
        
        try {
            // Ambil semua FCM tokens
            const tokensSnapshot = await admin.database().ref('fcm_tokens').once('value');
            const tokens = [];
            
            tokensSnapshot.forEach(child => {
                const tokenData = child.val();
                const token = tokenData?.token;
                // Jangan kirim ke pengirim sendiri
                if (token && child.key !== broadcast.senderUid) {
                    tokens.push(token);
                }
            });
            
            if (tokens.length === 0) {
                console.log('⚠️ No tokens found');
                return null;
            }
            
            console.log(`📱 Sending broadcast to ${tokens.length} devices`);
            
            // Kirim notifikasi
            const message = {
                notification: {
                    title: `📢 ${broadcast.title}`,
                    body: broadcast.message
                },
                data: {
                    type: 'BROADCAST',
                    messageId: messageId,
                    title: broadcast.title,
                    message: broadcast.message,
                    senderName: broadcast.senderName || 'Pengawas',
                    channelId: 'broadcast_channel',
                    click_action: 'FLUTTER_NOTIFICATION_CLICK'
                },
                android: {
                    priority: 'high',
                    notification: {
                        channelId: 'broadcast_channel',
                        priority: 'high',
                        defaultSound: true,
                        defaultVibrateTimings: true,
                        color: '#FF9800'
                    }
                }
            };
            
            // Kirim dalam batch (max 500 per batch)
            const batchSize = 500;
            let successCount = 0;
            let failureCount = 0;
            
            for (let i = 0; i < tokens.length; i += batchSize) {
                const batch = tokens.slice(i, i + batchSize);
                const response = await admin.messaging().sendEachForMulticast({
                    ...message,
                    tokens: batch
                });
                successCount += response.successCount;
                failureCount += response.failureCount;
            }
            
            console.log(`✅ Broadcast sent: ${successCount} success, ${failureCount} failed`);
            return null;
            
        } catch (error) {
            console.error('❌ Error sending broadcast:', error);
            return null;
        }
    });

// =========================================================================
// 2. TENOR CHANGE REQUEST NOTIFICATION
// Trigger: Saat Admin Lapangan membuat request perubahan tenor
// Target: Pimpinan cabang yang bersangkutan
// =========================================================================
exports.onTenorChangeRequestCreated = functions.database
    .ref('/tenor_change_requests/{cabangId}/{requestId}')
    .onCreate(async (snapshot, context) => {
        const request = snapshot.val();
        const { cabangId, requestId } = context.params;
        
        console.log(`📋 New tenor change request: ${request.pelangganNama}`);
        
        try {
            // Cari Pimpinan cabang ini
            const cabangSnapshot = await admin.database()
                .ref(`metadata/cabang/${cabangId}`)
                .once('value');
            
            const pimpinanUid = cabangSnapshot.val()?.pimpinanUid;
            
            if (!pimpinanUid) {
                console.log(`⚠️ No pimpinan found for cabang: ${cabangId}`);
                return null;
            }
            
            // Ambil FCM token Pimpinan
            const tokenSnapshot = await admin.database()
                .ref(`fcm_tokens/${pimpinanUid}`)
                .once('value');
            
            const token = tokenSnapshot.val()?.token;
            
            if (!token) {
                console.log(`⚠️ No FCM token for pimpinan: ${pimpinanUid}`);
                return null;
            }
            
            console.log(`📱 Sending tenor change notification to Pimpinan`);
            
            // Kirim notifikasi
            const message = {
                token: token,
                notification: {
                    title: '📋 Pengajuan Perubahan Tenor',
                    body: `${request.pelangganNama} - Tenor ${request.tenorLama} → ${request.tenorBaru} hari`
                },
                data: {
                    type: 'TENOR_CHANGE_REQUEST',
                    requestId: requestId,
                    pelangganId: request.pelangganId || '',
                    pelangganNama: request.pelangganNama || '',
                    tenorLama: String(request.tenorLama),
                    tenorBaru: String(request.tenorBaru),
                    requestedByName: request.requestedByName || '',
                    channelId: 'pengajuan_channel',
                    click_action: 'FLUTTER_NOTIFICATION_CLICK'
                },
                android: {
                    priority: 'high',
                    notification: {
                        channelId: 'pengajuan_channel',
                        priority: 'high',
                        defaultSound: true,
                        defaultVibrateTimings: true,
                        color: '#2196F3'
                    }
                }
            };
            
            await admin.messaging().send(message);
            console.log(`✅ Tenor change notification sent to Pimpinan`);
            
            return null;
            
        } catch (error) {
            console.error('❌ Error sending tenor change notification:', error);
            return null;
        }
    });

// =========================================================================
// 3. DELETION REQUEST NOTIFICATION
// Trigger: Saat Admin Lapangan membuat request penghapusan nasabah
// Target: Pimpinan cabang yang bersangkutan
// =========================================================================
exports.onDeletionRequestCreated = functions.database
    .ref('/deletion_requests/{requestId}')
    .onCreate(async (snapshot, context) => {
        const request = snapshot.val();
        const requestId = context.params.requestId;
        
        console.log(`🗑️ New deletion request: ${request.pelangganNama}`);
        
        try {
            const cabangId = request.cabangId;
            
            if (!cabangId) {
                console.log(`⚠️ No cabangId in request`);
                return null;
            }
            
            // Cari Pimpinan cabang ini
            const cabangSnapshot = await admin.database()
                .ref(`metadata/cabang/${cabangId}`)
                .once('value');
            
            const pimpinanUid = cabangSnapshot.val()?.pimpinanUid;
            
            if (!pimpinanUid) {
                console.log(`⚠️ No pimpinan found for cabang: ${cabangId}`);
                return null;
            }
            
            // Ambil FCM token Pimpinan
            const tokenSnapshot = await admin.database()
                .ref(`fcm_tokens/${pimpinanUid}`)
                .once('value');
            
            const token = tokenSnapshot.val()?.token;
            
            if (!token) {
                console.log(`⚠️ No FCM token for pimpinan: ${pimpinanUid}`);
                return null;
            }
            
            console.log(`📱 Sending deletion request notification to Pimpinan`);
            
            // Format sisa utang
            const sisaUtang = request.sisaUtang || 0;
            const sisaUtangFormatted = new Intl.NumberFormat('id-ID').format(sisaUtang);
            
            // Kirim notifikasi
            const message = {
                token: token,
                notification: {
                    title: '🗑️ Pengajuan Penghapusan Nasabah',
                    body: `${request.pelangganNama} - Sisa utang: Rp ${sisaUtangFormatted}`
                },
                data: {
                    type: 'DELETION_REQUEST',
                    requestId: requestId,
                    pelangganId: request.pelangganId || '',
                    pelangganNama: request.pelangganNama || '',
                    alasanPenghapusan: request.alasanPenghapusan || '',
                    requestedByName: request.requestedByName || '',
                    sisaUtang: String(sisaUtang),
                    channelId: 'pengajuan_channel',
                    click_action: 'FLUTTER_NOTIFICATION_CLICK'
                },
                android: {
                    priority: 'high',
                    notification: {
                        channelId: 'pengajuan_channel',
                        priority: 'high',
                        defaultSound: true,
                        defaultVibrateTimings: true,
                        color: '#FF9800'
                    }
                }
            };
            
            await admin.messaging().send(message);
            console.log(`✅ Deletion request notification sent to Pimpinan`);
            
            return null;
            
        } catch (error) {
            console.error('❌ Error sending deletion notification:', error);
            return null;
        }
    });