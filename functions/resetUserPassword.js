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

// =========================================================================
// CLOUD FUNCTION: CREATE NEW USER
// =========================================================================
// Hanya Pengawas yang bisa mengakses fungsi ini
// Membuat akun baru di Firebase Auth + metadata di RTDB
// =========================================================================

/**
 * createNewUser - Callable Function untuk membuat user baru
 *
 * @param {Object} data - { email, password, name, role, cabangId }
 *   role: 'admin' | 'pimpinan' | 'koordinator'
 *   cabangId: required for admin & pimpinan, optional for koordinator
 */
exports.createNewUser = functions.https.onCall(async (data, context) => {
    // 1. Validasi autentikasi
    if (!context.auth) {
        throw new functions.https.HttpsError(
            'unauthenticated',
            'Anda harus login untuk mengakses fungsi ini.'
        );
    }

    const callerUid = context.auth.uid;
    console.log(`📋 Create user request from UID: ${callerUid}`);

    // 2. Validasi apakah caller adalah Pengawas
    const pengawasSnap = await db.ref(`metadata/roles/pengawas/${callerUid}`).once('value');
    if (!pengawasSnap.exists() || pengawasSnap.val() !== true) {
        throw new functions.https.HttpsError(
            'permission-denied',
            'Hanya Pengawas yang dapat membuat akun baru.'
        );
    }

    // 3. Validasi input
    const { email, password, name, role, cabangId } = data;

    if (!email || typeof email !== 'string' || !email.includes('@')) {
        throw new functions.https.HttpsError('invalid-argument', 'Email tidak valid.');
    }

    if (!password || typeof password !== 'string' || password.length < 6) {
        throw new functions.https.HttpsError('invalid-argument', 'Password harus minimal 6 karakter.');
    }

    if (!name || typeof name !== 'string' || name.trim().length === 0) {
        throw new functions.https.HttpsError('invalid-argument', 'Nama harus diisi.');
    }

    const validRoles = ['admin', 'pimpinan', 'koordinator'];
    if (!role || !validRoles.includes(role)) {
        throw new functions.https.HttpsError('invalid-argument', 'Role tidak valid. Pilih: admin, pimpinan, atau koordinator.');
    }

    if ((role === 'admin' || role === 'pimpinan') && (!cabangId || typeof cabangId !== 'string')) {
        throw new functions.https.HttpsError('invalid-argument', 'Cabang harus dipilih untuk role ini.');
    }

    try {
        const normalizedEmail = email.trim().toLowerCase();

        // 4. Untuk pimpinan, cek apakah cabang sudah punya pimpinan
        if (role === 'pimpinan') {
            const cabangSnap = await db.ref(`metadata/cabang/${cabangId}`).once('value');
            if (cabangSnap.exists()) {
                const existingPimpinanUid = cabangSnap.child('pimpinanUid').val();
                if (existingPimpinanUid) {
                    // Cek apakah pimpinan lama masih ada di Auth
                    try {
                        await admin.auth().getUser(existingPimpinanUid);
                        const cabangName = cabangSnap.child('name').val() || cabangId;
                        throw new functions.https.HttpsError(
                            'already-exists',
                            `Cabang ${cabangName} sudah memiliki Pimpinan. Hapus Pimpinan lama terlebih dahulu.`
                        );
                    } catch (authErr) {
                        if (authErr.code !== 'auth/user-not-found') {
                            throw authErr;
                        }
                        // Pimpinan lama tidak ada di Auth, lanjut buat baru
                        console.log(`⚠️ Old pimpinan ${existingPimpinanUid} not found in Auth, replacing`);
                    }
                }
            }
        }

        // 5. Buat user di Firebase Auth
        const userRecord = await admin.auth().createUser({
            email: normalizedEmail,
            password: password,
            displayName: name.trim()
        });

        const newUid = userRecord.uid;
        console.log(`✅ User created in Auth: ${newUid} (${normalizedEmail})`);

        // 6. Set metadata di RTDB berdasarkan role
        const updates = {};

        if (role === 'admin') {
            updates[`metadata/admins/${newUid}`] = {
                name: name.trim(),
                email: normalizedEmail,
                cabang: cabangId,
                role: 'admin'
            };
        } else if (role === 'pimpinan') {
            // Set di metadata/admins juga agar muncul di getAllUsers
            updates[`metadata/admins/${newUid}`] = {
                name: name.trim(),
                email: normalizedEmail,
                cabang: cabangId,
                role: 'pimpinan'
            };
            // Set pimpinan di cabang
            updates[`metadata/cabang/${cabangId}/pimpinanUid`] = newUid;
            updates[`metadata/cabang/${cabangId}/pimpinanName`] = name.trim();
        } else if (role === 'koordinator') {
            updates[`metadata/roles/koordinator/${newUid}`] = true;
            updates[`metadata/admins/${newUid}`] = {
                name: name.trim(),
                email: normalizedEmail,
                cabang: cabangId || '',
                role: 'koordinator'
            };
        }

        await db.ref().update(updates);
        console.log(`✅ Metadata saved for ${role}: ${newUid}`);

        // 7. Log aktivitas
        const logRef = db.ref('user_management_logs').push();
        await logRef.set({
            action: 'create_user',
            targetUid: newUid,
            targetEmail: normalizedEmail,
            targetName: name.trim(),
            targetRole: role,
            cabangId: cabangId || '',
            createdByUid: callerUid,
            createdByEmail: context.auth.token.email || 'unknown',
            timestamp: admin.database.ServerValue.TIMESTAMP
        });

        const cabangName = cabangId ? await getCabangName(cabangId) : '';
        const roleDisplay = role === 'admin' ? 'Admin Lapangan' : (role === 'pimpinan' ? 'Pimpinan' : 'Koordinator');

        return {
            success: true,
            message: `${roleDisplay} "${name.trim()}" berhasil dibuat.`,
            user: {
                uid: newUid,
                email: normalizedEmail,
                name: name.trim(),
                role: role,
                cabang: cabangId || '',
                cabangName: cabangName,
                type: role
            }
        };

    } catch (error) {
        console.error('❌ Error creating user:', error);

        if (error.code === 'auth/email-already-exists') {
            throw new functions.https.HttpsError('already-exists', 'Email sudah terdaftar di sistem.');
        }

        if (error instanceof functions.https.HttpsError) {
            throw error;
        }

        throw new functions.https.HttpsError('internal', 'Gagal membuat akun: ' + error.message);
    }
});

// =========================================================================
// CLOUD FUNCTION: DELETE USER
// =========================================================================
// Hanya Pengawas yang bisa mengakses fungsi ini
// Menghapus akun dari Firebase Auth + metadata dari RTDB
// =========================================================================

/**
 * deleteExistingUser - Callable Function untuk menghapus user
 *
 * @param {Object} data - { targetUid: string }
 */
exports.deleteExistingUser = functions.https.onCall(async (data, context) => {
    // 1. Validasi autentikasi
    if (!context.auth) {
        throw new functions.https.HttpsError(
            'unauthenticated',
            'Anda harus login untuk mengakses fungsi ini.'
        );
    }

    const callerUid = context.auth.uid;
    console.log(`📋 Delete user request from UID: ${callerUid}`);

    // 2. Validasi apakah caller adalah Pengawas
    const pengawasSnap = await db.ref(`metadata/roles/pengawas/${callerUid}`).once('value');
    if (!pengawasSnap.exists() || pengawasSnap.val() !== true) {
        throw new functions.https.HttpsError(
            'permission-denied',
            'Hanya Pengawas yang dapat menghapus akun.'
        );
    }

    // 3. Validasi input
    const { targetUid } = data;

    if (!targetUid || typeof targetUid !== 'string') {
        throw new functions.https.HttpsError('invalid-argument', 'UID target harus diisi.');
    }

    // 4. Tidak boleh hapus diri sendiri
    if (targetUid === callerUid) {
        throw new functions.https.HttpsError('permission-denied', 'Tidak dapat menghapus akun sendiri.');
    }

    // 5. Tidak boleh hapus Pengawas
    const targetPengawasSnap = await db.ref(`metadata/roles/pengawas/${targetUid}`).once('value');
    if (targetPengawasSnap.exists() && targetPengawasSnap.val() === true) {
        throw new functions.https.HttpsError('permission-denied', 'Tidak dapat menghapus akun Pengawas.');
    }

    try {
        // 6. Ambil info user sebelum dihapus (untuk logging)
        let targetEmail = '';
        let targetName = '';
        let targetRole = '';
        let targetCabang = '';

        try {
            const userRecord = await admin.auth().getUser(targetUid);
            targetEmail = userRecord.email || '';
        } catch (e) {
            console.log(`User ${targetUid} not found in Auth`);
        }

        const adminSnap = await db.ref(`metadata/admins/${targetUid}`).once('value');
        if (adminSnap.exists()) {
            targetName = adminSnap.child('name').val() || '';
            targetRole = adminSnap.child('role').val() || 'admin';
            targetCabang = adminSnap.child('cabang').val() || '';
        }

        const isPimpinan = await checkIfPimpinan(targetUid);
        const isKoordinator = await checkIfKoordinator(targetUid);

        if (isPimpinan) targetRole = 'pimpinan';
        if (isKoordinator) targetRole = 'koordinator';

        if (!adminSnap.exists() && !isPimpinan && !isKoordinator) {
            throw new functions.https.HttpsError('not-found', 'User tidak ditemukan dalam sistem koperasi.');
        }

        // 7. Hapus metadata dari RTDB
        const deletions = {};
        deletions[`metadata/admins/${targetUid}`] = null;
        deletions[`metadata/roles/koordinator/${targetUid}`] = null;
        deletions[`force_logout/${targetUid}`] = null;
        deletions[`device_presence/${targetUid}`] = null;

        // Hapus pimpinanUid dari cabang jika dia pimpinan
        if (isPimpinan) {
            const cabangSnap = await db.ref('metadata/cabang').once('value');
            if (cabangSnap.exists()) {
                for (const [cabangId, cabangData] of Object.entries(cabangSnap.val())) {
                    if (cabangData.pimpinanUid === targetUid || cabangData.pimpinan === targetUid) {
                        deletions[`metadata/cabang/${cabangId}/pimpinanUid`] = null;
                        deletions[`metadata/cabang/${cabangId}/pimpinanName`] = null;
                        break;
                    }
                }
            }
        }

        await db.ref().update(deletions);
        console.log(`✅ Metadata deleted for ${targetUid}`);

        // 8. Hapus user dari Firebase Auth
        try {
            await admin.auth().deleteUser(targetUid);
            console.log(`✅ User deleted from Auth: ${targetUid}`);
        } catch (authError) {
            if (authError.code !== 'auth/user-not-found') {
                throw authError;
            }
            console.log(`⚠️ User ${targetUid} already deleted from Auth`);
        }

        // 9. Log aktivitas
        const logRef = db.ref('user_management_logs').push();
        await logRef.set({
            action: 'delete_user',
            targetUid: targetUid,
            targetEmail: targetEmail,
            targetName: targetName,
            targetRole: targetRole,
            targetCabang: targetCabang,
            deletedByUid: callerUid,
            deletedByEmail: context.auth.token.email || 'unknown',
            timestamp: admin.database.ServerValue.TIMESTAMP
        });

        const roleDisplay = targetRole === 'admin' ? 'Admin Lapangan' :
                            (targetRole === 'pimpinan' ? 'Pimpinan' : 'Koordinator');

        return {
            success: true,
            message: `${roleDisplay} "${targetName || targetEmail}" berhasil dihapus.`
        };

    } catch (error) {
        console.error('❌ Error deleting user:', error);

        if (error instanceof functions.https.HttpsError) {
            throw error;
        }

        throw new functions.https.HttpsError('internal', 'Gagal menghapus akun: ' + error.message);
    }
});

// =========================================================================
// CLOUD FUNCTION: GET ALL CABANG
// =========================================================================

/**
 * getAllCabang - Callable Function untuk mendapatkan daftar cabang
 * Hanya Pengawas yang bisa mengakses
 */
exports.getAllCabang = functions.https.onCall(async (data, context) => {
    if (!context.auth) {
        throw new functions.https.HttpsError('unauthenticated', 'Anda harus login.');
    }

    const callerUid = context.auth.uid;
    const pengawasSnap = await db.ref(`metadata/roles/pengawas/${callerUid}`).once('value');
    if (!pengawasSnap.exists() || pengawasSnap.val() !== true) {
        throw new functions.https.HttpsError('permission-denied', 'Hanya Pengawas yang dapat mengakses.');
    }

    try {
        const cabangSnap = await db.ref('metadata/cabang').once('value');
        const cabangList = [];

        if (cabangSnap.exists()) {
            for (const [cabangId, cabangData] of Object.entries(cabangSnap.val())) {
                cabangList.push({
                    id: cabangId,
                    name: cabangData.name || cabangId,
                    pimpinanUid: cabangData.pimpinanUid || '',
                    pimpinanName: cabangData.pimpinanName || ''
                });
            }
        }

        return {
            success: true,
            cabangList: cabangList.sort((a, b) => a.name.localeCompare(b.name))
        };
    } catch (error) {
        console.error('❌ Error getting cabang:', error);
        throw new functions.https.HttpsError('internal', 'Gagal mengambil daftar cabang.');
    }
});
