// functions/resetUserPassword.js
// =========================================================================
// CLOUD FUNCTION: RESET PASSWORD USER
// =========================================================================
// Hanya Pengawas yang bisa mengakses fungsi ini
// Digunakan untuk reset password Admin Lapangan dan Pimpinan
// =========================================================================

const functions = require("firebase-functions");
const admin = require("firebase-admin");

const db = admin.database();

/**
 * resetUserPassword - Callable Function untuk reset password user
 * 
 * @param {Object} data - { targetEmail: string, newPassword: string }
 * @param {Object} context - Firebase Auth context
 * 
 * Hanya Pengawas yang bisa mengakses fungsi ini
 */
exports.resetUserPassword = functions.https.onCall(async (data, context) => {
    // 1. Validasi autentikasi
    if (!context.auth) {
        throw new functions.https.HttpsError(
            'unauthenticated',
            'Anda harus login untuk mengakses fungsi ini.'
        );
    }

    const callerUid = context.auth.uid;
    console.log(`📋 Reset password request from UID: ${callerUid}`);

    // 2. Validasi apakah caller adalah Pengawas
    const pengawasSnap = await db.ref(`metadata/roles/pengawas/${callerUid}`).once('value');
    if (!pengawasSnap.exists() || pengawasSnap.val() !== true) {
        console.log(`❌ Caller ${callerUid} bukan Pengawas`);
        throw new functions.https.HttpsError(
            'permission-denied',
            'Hanya Pengawas yang dapat mengubah password user.'
        );
    }

    console.log(`✅ Caller ${callerUid} adalah Pengawas`);

    // 3. Validasi input
    const { targetEmail, newPassword } = data;

    if (!targetEmail || typeof targetEmail !== 'string') {
        throw new functions.https.HttpsError(
            'invalid-argument',
            'Email target harus diisi.'
        );
    }

    if (!newPassword || typeof newPassword !== 'string' || newPassword.length < 6) {
        throw new functions.https.HttpsError(
            'invalid-argument',
            'Password baru harus minimal 6 karakter.'
        );
    }

    try {
        // 4. Cari user berdasarkan email
        const userRecord = await admin.auth().getUserByEmail(targetEmail.trim().toLowerCase());
        const targetUid = userRecord.uid;

        console.log(`📍 Target user found: ${targetUid} (${targetEmail})`);

        // 5. Cek apakah target adalah Pengawas (tidak boleh reset password sesama Pengawas)
        const targetPengawasSnap = await db.ref(`metadata/roles/pengawas/${targetUid}`).once('value');
        if (targetPengawasSnap.exists() && targetPengawasSnap.val() === true) {
            throw new functions.https.HttpsError(
                'permission-denied',
                'Tidak dapat mengubah password Pengawas lainnya.'
            );
        }

        // 6. Cek apakah target adalah Admin, Pimpinan, atau Koordinator yang terdaftar
        const adminSnap = await db.ref(`metadata/admins/${targetUid}`).once('value');
        const isPimpinan = await checkIfPimpinan(targetUid);
        const isKoordinator = await checkIfKoordinator(targetUid);

        if (!adminSnap.exists() && !isPimpinan && !isKoordinator) {
            throw new functions.https.HttpsError(
                'not-found',
                'User tidak ditemukan dalam sistem koperasi.'
            );
        }

        // 7. Update password
        await admin.auth().updateUser(targetUid, {
            password: newPassword
        });

        // 8. Revoke semua refresh tokens (force logout dari semua device)
        await admin.auth().revokeRefreshTokens(targetUid);
        console.log(`🔒 Refresh tokens revoked for ${targetUid}`);

        // 9. Simpan timestamp untuk force logout di Android
        await db.ref(`force_logout/${targetUid}`).set({
            timestamp: admin.database.ServerValue.TIMESTAMP,
            reason: 'password_reset',
            resetByUid: callerUid
        });
        console.log(`📝 Force logout timestamp saved for ${targetUid}`);

        // 8. Log aktivitas
        const logRef = db.ref('password_reset_logs').push();
        await logRef.set({
            targetUid: targetUid,
            targetEmail: targetEmail,
            resetByUid: callerUid,
            resetByEmail: context.auth.token.email || 'unknown',
            timestamp: admin.database.ServerValue.TIMESTAMP,
            success: true
        });

        const targetName = adminSnap.exists() 
            ? adminSnap.child('name').val() 
            : (isPimpinan ? 'Pimpinan' : (isKoordinator ? 'Koordinator' : 'User'));
        const targetRole = adminSnap.exists() 
            ? (adminSnap.child('role').val() || 'admin') 
            : (isPimpinan ? 'pimpinan' : (isKoordinator ? 'koordinator' : 'unknown'));

        console.log(`✅ Password berhasil direset untuk ${targetEmail}`);

        return {
            success: true,
            message: `Password ${targetName} (${targetRole}) berhasil diubah.`,
            targetUid: targetUid,
            targetName: targetName,
            targetRole: targetRole
        };

    } catch (error) {
        console.error('❌ Error resetting password:', error);

        // Log error
        const logRef = db.ref('password_reset_logs').push();
        await logRef.set({
            targetEmail: targetEmail,
            resetByUid: callerUid,
            timestamp: admin.database.ServerValue.TIMESTAMP,
            success: false,
            error: error.message
        });

        if (error.code === 'auth/user-not-found') {
            throw new functions.https.HttpsError(
                'not-found',
                'Email tidak ditemukan di sistem.'
            );
        }

        if (error instanceof functions.https.HttpsError) {
            throw error;
        }

        throw new functions.https.HttpsError(
            'internal',
            'Terjadi kesalahan saat mereset password: ' + error.message
        );
    }
});

/**
 * getAllUsers - Callable Function untuk mendapatkan daftar semua user
 * 
 * Hanya Pengawas yang bisa mengakses fungsi ini
 */
exports.getAllUsers = functions.https.onCall(async (data, context) => {
    // 1. Validasi autentikasi
    if (!context.auth) {
        throw new functions.https.HttpsError(
            'unauthenticated',
            'Anda harus login untuk mengakses fungsi ini.'
        );
    }

    const callerUid = context.auth.uid;

    // 2. Validasi apakah caller adalah Pengawas
    const pengawasSnap = await db.ref(`metadata/roles/pengawas/${callerUid}`).once('value');
    if (!pengawasSnap.exists() || pengawasSnap.val() !== true) {
        throw new functions.https.HttpsError(
            'permission-denied',
            'Hanya Pengawas yang dapat mengakses daftar user.'
        );
    }

    try {
        const users = [];

        // 3. Ambil semua admin dari metadata/admins
        const adminsSnap = await db.ref('metadata/admins').once('value');
        if (adminsSnap.exists()) {
            for (const [uid, adminData] of Object.entries(adminsSnap.val())) {
                try {
                    const userRecord = await admin.auth().getUser(uid);
                    users.push({
                        uid: uid,
                        email: userRecord.email || '',
                        name: adminData.name || 'Tidak ada nama',
                        role: adminData.role || 'admin',
                        cabang: adminData.cabang || '',
                        cabangName: await getCabangName(adminData.cabang),
                        type: 'admin'
                    });
                } catch (authError) {
                    console.log(`User ${uid} tidak ditemukan di Auth:`, authError.message);
                }
            }
        }

        // 4. Ambil semua pimpinan dari metadata/cabang
        const cabangSnap = await db.ref('metadata/cabang').once('value');
        if (cabangSnap.exists()) {
            for (const [cabangId, cabangData] of Object.entries(cabangSnap.val())) {
                const pimpinanUid = cabangData.pimpinanUid || cabangData.pimpinan;
                if (pimpinanUid) {
                    // Cek apakah sudah ada di list (mungkin terdaftar sebagai admin juga)
                    const exists = users.find(u => u.uid === pimpinanUid);
                    if (!exists) {
                        try {
                            const userRecord = await admin.auth().getUser(pimpinanUid);
                            users.push({
                                uid: pimpinanUid,
                                email: userRecord.email || '',
                                name: cabangData.pimpinanName || 'Pimpinan',
                                role: 'pimpinan',
                                cabang: cabangId,
                                cabangName: cabangData.name || cabangId,
                                type: 'pimpinan'
                            });
                        } catch (authError) {
                            console.log(`Pimpinan ${pimpinanUid} tidak ditemukan di Auth`);
                        }
                    } else {
                        // Update role jika sudah ada
                        exists.role = 'pimpinan';
                        exists.type = 'pimpinan';
                    }
                }
            }
        }
        // 5. Ambil semua koordinator dari metadata/roles/koordinator
        const koordinatorSnap = await db.ref('metadata/roles/koordinator').once('value');
        if (koordinatorSnap.exists()) {
            for (const [uid, isKoordinator] of Object.entries(koordinatorSnap.val())) {
                if (isKoordinator === true) {
                    // Cek apakah sudah ada di list
                    const exists = users.find(u => u.uid === uid);
                    if (!exists) {
                        try {
                            const userRecord = await admin.auth().getUser(uid);
                            // Coba ambil nama dari metadata/admins jika ada
                            const adminData = adminsSnap.exists() ? adminsSnap.val()[uid] : null;
                            users.push({
                                uid: uid,
                                email: userRecord.email || '',
                                name: adminData?.name || userRecord.displayName || 'Koordinator',
                                role: 'koordinator',
                                cabang: adminData?.cabang || '',
                                cabangName: adminData?.cabang ? await getCabangName(adminData.cabang) : 'Semua Cabang',
                                type: 'koordinator'
                            });
                        } catch (authError) {
                            console.log(`Koordinator ${uid} tidak ditemukan di Auth:`, authError.message);
                        }
                    } else {
                        // Update role jika sudah ada tapi belum ditandai koordinator
                        if (exists.role !== 'pimpinan') {
                            exists.role = 'koordinator';
                            exists.type = 'koordinator';
                        }
                    }
                }
            }
        }

        console.log(`✅ Ditemukan ${users.length} users`);

        return {
            success: true,
            users: users,
            count: users.length
        };

    } catch (error) {
        console.error('❌ Error getting users:', error);
        throw new functions.https.HttpsError(
            'internal',
            'Terjadi kesalahan saat mengambil daftar user.'
        );
    }
});

// Helper function untuk cek apakah user adalah pimpinan
async function checkIfPimpinan(uid) {
    const cabangSnap = await db.ref('metadata/cabang').once('value');
    if (!cabangSnap.exists()) return false;

    for (const [cabangId, cabangData] of Object.entries(cabangSnap.val())) {
        if (cabangData.pimpinanUid === uid || cabangData.pimpinan === uid) {
            return true;
        }
    }
    return false;
}

// Helper function untuk cek apakah user adalah koordinator
async function checkIfKoordinator(uid) {
    const koordinatorSnap = await db.ref(`metadata/roles/koordinator/${uid}`).once('value');
    return koordinatorSnap.exists() && koordinatorSnap.val() === true;
}

// Helper function untuk mendapatkan nama cabang
async function getCabangName(cabangId) {
    if (!cabangId) return '';
    const snap = await db.ref(`metadata/cabang/${cabangId}/name`).once('value');
    return snap.exists() ? snap.val() : cabangId;
}
