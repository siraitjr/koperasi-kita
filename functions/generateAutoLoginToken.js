// functions/generateAutoLoginToken.js
// =========================================================================
// AUTO-LOGIN TOKEN EXCHANGE
// Digunakan oleh Android app untuk login otomatis ke buku-pokok-web
// tanpa harus input ulang email/password di browser.
//
// Alur:
// 1. Android ambil Firebase ID Token user yang sedang login
// 2. Kirim ke URL web dengan ?idToken=<token>
// 3. Web panggil endpoint ini untuk tukar ID Token → Custom Token
// 4. Web signInWithCustomToken → langsung masuk
// =========================================================================

const functions = require('firebase-functions');
const admin = require('firebase-admin');

const ALLOWED_ORIGIN = 'https://www.koperasi-kita.com';

exports.generateAutoLoginToken = functions
  .region('asia-southeast1')
  .https.onRequest(async (req, res) => {
    res.set('Access-Control-Allow-Origin', ALLOWED_ORIGIN);
    res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
    res.set('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') {
      res.status(204).send('');
      return;
    }

    if (req.method !== 'POST') {
      res.status(405).json({ error: 'Method not allowed' });
      return;
    }

    const { idToken } = req.body;
    if (!idToken) {
      res.status(400).json({ error: 'idToken required' });
      return;
    }

    try {
      // Verifikasi ID Token dari Android
      const decoded = await admin.auth().verifyIdToken(idToken);

      // Buat Custom Token untuk web sign-in
      const customToken = await admin.auth().createCustomToken(decoded.uid);

      res.json({ customToken });
    } catch (e) {
      console.error('generateAutoLoginToken error:', e.message);
      res.status(401).json({ error: 'Token tidak valid atau sudah kadaluarsa' });
    }
  });
