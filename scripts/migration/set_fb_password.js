// set_fb_password.js — set password Firebase akun uji (Admin SDK)
const keyPath = process.env.FB_KEY;
if (!keyPath) { console.error('FB_KEY wajib'); process.exit(1); }
const serviceAccount = require(keyPath);

// Node 24 bisa memuat firebase-admin sebagai ESM; normalkan bentuk modul.
function norm(mod) {
  if (!mod) return mod;
  if (mod.initializeApp || mod.cert || mod.getAuth) return mod;
  if (mod.default) return norm(mod.default);
  return mod;
}

(async () => {
  let auth;
  try {
    const appMod = norm(require('firebase-admin/app'));
    const authMod = norm(require('firebase-admin/auth'));
    const app = appMod.initializeApp({ credential: appMod.cert(serviceAccount) });
    auth = authMod.getAuth(app);
  } catch (e) {
    const admin = norm(require('firebase-admin'));
    if (!admin.credential) {
      console.error('Bentuk module tidak dikenal. Keys:', Object.keys(require('firebase-admin')));
      process.exit(1);
    }
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    auth = admin.auth();
  }
  const u = await auth.getUserByEmail('kaspan@godangulu.com');
  await auth.updateUser(u.uid, { password: 'Kasir2026!' });
  console.log('OK: password Firebase kaspan diset.');
  process.exit(0);
})().catch((e) => { console.error('GAGAL:', e.message); process.exit(1); });