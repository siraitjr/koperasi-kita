// lib/supabaseClient.js
// =========================================================================
// Klien Supabase untuk web — D-5 langkah 2 (021 §4).
// =========================================================================
// Ini HANYA memasang rel. Belum ada perilaku yang berubah:
// `lib/firebase.js` dan `lib/api.js` tidak disentuh, dan tidak ada satu pun
// berkas yang mengimpor modul ini sampai langkah berikutnya.
//
// Aturan 014 §6.4: URL dan kunci dibaca dari env, tidak pernah literal di
// kode. Kunci HMAC rekening koran ter-commit sejak e570701 justru karena
// pola itu dilanggar sekali.
//
// Catatan tentang ANON KEY: kunci ini memang IKUT TERBUNDEL ke browser —
// itu sifatnya, bukan kebocoran. Yang menjaga data bukan kerahasiaan kunci
// ini melainkan RLS (002, dipercepat 017/018 dan diuji diferensial tanpa
// selisih). Yang TIDAK BOLEH masuk sini adalah SERVICE ROLE KEY: ia
// mem-bypass RLS sepenuhnya. Kalau suatu saat ada nilai `service_role` di
// berkas ber-prefix NEXT_PUBLIC_, itu insiden, bukan salah ketik.
// =========================================================================

import { createClient } from '@supabase/supabase-js';

// Ditulis LENGKAP dan HARFIAH, bukan lewat variabel atau process.env[nama].
// Next.js mengganti `process.env.NEXT_PUBLIC_*` saat build dengan pencocokan
// teks; bentuk dinamis tidak ikut tergantikan dan akan jadi undefined di
// browser — gagal yang hanya muncul setelah build, bukan saat dev.
const URL = process.env.NEXT_PUBLIC_SUPABASE_URL;
const ANON_KEY = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;

if (!URL || !ANON_KEY) {
  // Sengaja dilempar saat modul dimuat, bukan saat panggilan pertama.
  // Env yang hilang akan membuat SETIAP query gagal dengan galat jaringan
  // yang membingungkan; lebih baik berhenti di satu tempat dengan sebab
  // yang jelas.
  throw new Error(
    'Konfigurasi Supabase tidak lengkap. Set NEXT_PUBLIC_SUPABASE_URL dan ' +
      'NEXT_PUBLIC_SUPABASE_ANON_KEY di buku-pokok-web/.env.local ' +
      '(dan di variabel lingkungan penyedia hosting saat deploy). ' +
      'Restart `npm run dev` setelah mengubah .env.local — Next.js hanya ' +
      'membacanya saat proses dimulai.'
  );
}

// Satu instance untuk seluruh aplikasi.
//
// Disimpan di globalThis karena hot-reload dev mengevaluasi ulang modul ini
// setiap kali berkas tersimpan. Tanpa cache, tiap reload membuat GoTrueClient
// baru yang sama-sama memantau sesi di localStorage — sumber peringatan
// "Multiple GoTrueClient instances detected" dan perilaku refresh token yang
// saling menimpa. Di produksi modul dievaluasi sekali, jadi ini murni
// pengaman dev.
const KUNCI_GLOBAL = Symbol.for('koperasi.supabase.client');

function buatKlien() {
  return createClient(URL, ANON_KEY, {
    auth: {
      // Sesi disimpan agar refresh halaman tidak memaksa login ulang.
      persistSession: true,
      autoRefreshToken: true,
      // Kunci penyimpanan dibedakan supaya tidak bertabrakan dengan sesi
      // Firebase yang MASIH AKTIF hari ini. Selama masa evakuasi kedua sesi
      // hidup berdampingan di localStorage yang sama.
      storageKey: 'koperasi-kita-auth',
      // Web ini tidak memakai OAuth/magic link di URL, dan `rk.html` justru
      // memakai `?t=` untuk keperluan lain. Membiarkannya menyala membuat
      // klien mencoba menafsirkan query string yang bukan miliknya.
      detectSessionInUrl: false,
    },
    db: { schema: 'koperasi' },
  });
}

if (!globalThis[KUNCI_GLOBAL]) {
  globalThis[KUNCI_GLOBAL] = buatKlien();
}

/** Instance Supabase tunggal untuk seluruh aplikasi. */
export const supabase = globalThis[KUNCI_GLOBAL];

export default supabase;
