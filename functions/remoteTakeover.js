// functions/remoteTakeover.js
const functions = require("firebase-functions");
const admin = require("firebase-admin");
const db = admin.database();

/**
 * Helper: Cari cabang yang dimiliki Pimpinan
 * Mencoba 3 sumber data secara berurutan
 */
async function findPimpinanCabang(callerUid) {
  // 1. Cek metadata/cabang → pimpinanUid
  const cabangSnap = await db.ref("metadata/cabang").once("value");
  if (cabangSnap.exists()) {
    let found = null;
    cabangSnap.forEach((cabang) => {
      if (cabang.child("pimpinanUid").val() === callerUid) {
        found = cabang.key;
      }
    });
    if (found) {
      console.log(`✅ Pimpinan found via metadata/cabang: ${found}`);
      return { isPimpinan: true, cabangId: found };
    }
  }

  // 2. Cek metadata/admins/{uid}/role === "pimpinan"
  const adminMeta = await db.ref(`metadata/admins/${callerUid}`).once("value");
  if (adminMeta.exists() && adminMeta.child("role").val() === "pimpinan") {
    const cabangId = adminMeta.child("cabang").val();
    console.log(`✅ Pimpinan found via metadata/admins: cabang=${cabangId}`);
    return { isPimpinan: true, cabangId: cabangId };
  }

  // 3. Cek metadata/roles/pimpinan
  const pimpinanRoles = await db.ref("metadata/roles/pimpinan").once("value");
  if (pimpinanRoles.exists()) {
    let found = null;
    pimpinanRoles.forEach((child) => {
      if (child.val() === callerUid || child.key === callerUid) {
        found = child.key;
      }
    });
    if (found) {
      // Coba ambil cabang dari metadata/admins sebagai fallback
      const fallbackMeta = await db.ref(`metadata/admins/${callerUid}/cabang`).once("value");
      const cabangId = fallbackMeta.val() || found;
      console.log(`✅ Pimpinan found via metadata/roles/pimpinan: cabang=${cabangId}`);
      return { isPimpinan: true, cabangId: cabangId };
    }
  }

  return { isPimpinan: false, cabangId: null };
}

/**
 * Helper: Cari semua cabang yang dikelola Pimpinan
 * Untuk kasus Pimpinan mengelola beberapa cabang
 */
async function findAllPimpinanCabang(callerUid) {
  const cabangIds = new Set();

  // Dari metadata/cabang
  const cabangSnap = await db.ref("metadata/cabang").once("value");
  if (cabangSnap.exists()) {
    cabangSnap.forEach((cabang) => {
      if (cabang.child("pimpinanUid").val() === callerUid) {
        cabangIds.add(cabang.key);
      }
    });
  }

  // Dari metadata/admins
  const adminMeta = await db.ref(`metadata/admins/${callerUid}`).once("value");
  if (adminMeta.exists()) {
    const cabang = adminMeta.child("cabang").val();
    if (cabang) cabangIds.add(cabang);
  }

  return cabangIds;
}

/**
 * generateTakeoverToken
 * Dipanggil Pimpinan untuk mendapat custom auth token agar bisa sign-in sebagai Admin
 */
exports.generateTakeoverToken = functions
  .region("asia-southeast1")
  .https.onCall(async (data, context) => {
    if (!context.auth) {
      throw new functions.https.HttpsError("unauthenticated", "Harus login.");
    }

    const callerUid = context.auth.uid;
    const { targetAdminUid } = data;

    if (!targetAdminUid) {
      throw new functions.https.HttpsError("invalid-argument", "targetAdminUid wajib diisi.");
    }

    console.log(`🔍 Takeover request: ${callerUid} → ${targetAdminUid}`);

    // 1. Validasi caller adalah Pimpinan
    const { isPimpinan, cabangId: callerCabangId } = await findPimpinanCabang(callerUid);

    if (!isPimpinan) {
      console.error(`❌ Caller ${callerUid} bukan Pimpinan`);
      throw new functions.https.HttpsError("permission-denied", "Hanya Pimpinan yang bisa melakukan takeover.");
    }

    // 2. Validasi target adalah Admin Lapangan
    const targetMeta = await db.ref(`metadata/admins/${targetAdminUid}`).once("value");
    if (!targetMeta.exists()) {
      throw new functions.https.HttpsError("not-found", "Admin tidak ditemukan.");
    }

    // 3. Validasi cabang — cek apakah admin termasuk dalam cabang pimpinan
    const targetCabang = targetMeta.child("cabang").val();
    console.log(`🔍 Caller cabang: ${callerCabangId}, Target cabang: ${targetCabang}`);

    // Ambil SEMUA cabang pimpinan untuk validasi yang lebih fleksibel
    const allPimpinanCabang = await findAllPimpinanCabang(callerUid);
    if (callerCabangId) allPimpinanCabang.add(callerCabangId);

    const isSameCabang = allPimpinanCabang.has(targetCabang);

    if (!isSameCabang && allPimpinanCabang.size > 0) {
      // Hanya blokir jika pimpinan punya cabang yang jelas tapi tidak cocok
      console.error(`❌ Cabang mismatch: pimpinan=[${[...allPimpinanCabang]}], target=${targetCabang}`);
      throw new functions.https.HttpsError("permission-denied", "Admin bukan bagian dari cabang Anda.");
    }

    // 4. Cek session_lock — izinkan re-takeover oleh pimpinan yang SAMA
    const existingLock = await db.ref(`session_lock/${targetAdminUid}`).once("value");
    if (existingLock.exists()) {
      const lockedBy = existingLock.child("lockedBy").val();
      if (lockedBy !== callerUid) {
        // Di-lock oleh pimpinan LAIN → blokir
        const lockedByName = existingLock.child("pimpinanName").val() || "Pimpinan lain";
        throw new functions.https.HttpsError("already-exists", `Akun admin sedang digunakan oleh ${lockedByName}.`);
      }
      // Di-lock oleh pimpinan SAMA → izinkan re-takeover
      console.log(`🔄 Re-takeover oleh pimpinan yang sama: ${callerUid}`);
    }

    // 5. Generate custom token
    try {
      const customToken = await admin.auth().createCustomToken(targetAdminUid, {
        takeoverBy: callerUid,
        isTakeover: true,
      });

      // 6. Set/Update session_lock
      await db.ref(`session_lock/${targetAdminUid}`).set({
        lockedBy: callerUid,
        pimpinanName: context.auth.token.name || context.auth.token.email || "Pimpinan",
        lockedAt: admin.database.ServerValue.TIMESTAMP,
      });

      // 7. Update status takeover
      await db.ref(`remote_takeover/${targetAdminUid}`).update({
        status: "active",
      });

      // 8. Tulis force_logout untuk admin
      // Ini memastikan admin ter-logout bahkan jika:
      // - App di background (startForceLogoutListener masih aktif)
      // - App mati lalu dibuka (checkForceLogoutOnStartup menangkap)
      // Ditulis dari Cloud Function karena rules force_logout ".write: false" untuk client
      await db.ref(`force_logout/${targetAdminUid}`).set({
        timestamp: admin.database.ServerValue.TIMESTAMP,
        reason: "takeover",
        by: callerUid,
      });

      console.log(`✅ Takeover token generated: ${callerUid} → ${targetAdminUid}`);

      return {
        success: true,
        token: customToken,
        adminName: targetMeta.child("name").val() || "Admin",
      };
    } catch (error) {
      console.error("❌ Error generating takeover token:", error);
      throw new functions.https.HttpsError("internal", "Gagal membuat token: " + error.message);
    }
  });

/**
 * restorePimpinanSession
 * Dipanggil setelah Pimpinan selesai menggunakan akun Admin, untuk kembali ke akun Pimpinan
 */
exports.restorePimpinanSession = functions
  .region("asia-southeast1")
  .https.onCall(async (data, context) => {
    if (!context.auth) {
      throw new functions.https.HttpsError("unauthenticated", "Harus login.");
    }

    const { pimpinanUid, adminUid } = data;

    if (!pimpinanUid || !adminUid) {
      throw new functions.https.HttpsError("invalid-argument", "pimpinanUid dan adminUid wajib diisi.");
    }

    // 1. Validasi: session_lock harus ada dan milik pimpinan ini
    const lockSnap = await db.ref(`session_lock/${adminUid}`).once("value");
    if (!lockSnap.exists()) {
      // Lock sudah tidak ada — mungkin sudah dihapus.
      // Tetap izinkan restore agar pimpinan tidak stuck.
      console.log(`⚠️ Session lock not found for ${adminUid}, proceeding anyway`);
    } else {
      const lockedBy = lockSnap.child("lockedBy").val();
      if (lockedBy !== pimpinanUid) {
        throw new functions.https.HttpsError("permission-denied", "Sesi takeover bukan milik Anda.");
      }
    }

    try {
      // 2. Generate custom token untuk Pimpinan
      const customToken = await admin.auth().createCustomToken(pimpinanUid);

      // 3. Hapus session_lock, remote_takeover, DAN force_logout
      await db.ref(`session_lock/${adminUid}`).remove();
      await db.ref(`remote_takeover/${adminUid}`).remove();
      await db.ref(`force_logout/${adminUid}`).remove();

      console.log(`✅ Pimpinan session restored: ${pimpinanUid}, admin ${adminUid} unlocked`);

      return {
        success: true,
        token: customToken,
      };
    } catch (error) {
      console.error("❌ Error restoring session:", error);
      throw new functions.https.HttpsError("internal", "Gagal mengembalikan sesi: " + error.message);
    }
  });