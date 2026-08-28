#!/usr/bin/env node
// =========================================================================
// SANDI AKUN UJI — tiga peran untuk memeriksa RLS di localhost (025 §6)
// =========================================================================
// Menyetel kata sandi Supabase untuk beberapa akun uji dan MENAMPILKANNYA DI
// LAYAR. Domain @godangulu.com fiktif (008 §0), jadi tidak ada surel yang
// bisa dikirim — pola yang sama dengan `resetUserPassword` di Edge Function
// `user-management`.
//
// ⚠ SANDI TIDAK PERNAH DITULIS KE BERKAS. Tidak ke repo, tidak ke laporan,
//   tidak ke argumen perintah (argumen terlihat di `ps` dan tersimpan di
//   riwayat shell). Ia hanya dicetak ke stdout. Salin ke pengelola kata
//   sandi, lalu bersihkan layar.
//
// ⚠ SERVICE ROLE KEY MEM-BYPASS SELURUH RLS. Jalankan ini hanya dari mesin
//   Anda sendiri, jangan dari CI, dan jangan menaruh kuncinya di .env.local
//   (berkas itu ikut ter-bundle ke browser lewat NEXT_PUBLIC_*).
//
// PEMAKAIAN
//   export SUPABASE_URL='https://<ref>.supabase.co'
//   export SUPABASE_SERVICE_ROLE_KEY='<service role key>'
//   node scripts/verification/sandi_akun_uji.mjs kaspan@godangulu.com \
//        admin.contoh@godangulu.com pimpinan.contoh@godangulu.com
//
//   Tanpa argumen, ia HANYA MENDAFTAR kandidat per peran dan tidak mengubah
//   apa pun — jalankan begitu dulu untuk memilih akunnya.
// =========================================================================

import { createClient } from '@supabase/supabase-js';
import { randomBytes } from 'node:crypto';

const URL = process.env.SUPABASE_URL;
const KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;
if (!URL || !KEY) {
  console.error('FATAL: set SUPABASE_URL dan SUPABASE_SERVICE_ROLE_KEY.');
  process.exit(2);
}

const db = createClient(URL, KEY, {
  auth: { persistSession: false },
  db: { schema: 'koperasi' },
});

// Alfabet tanpa karakter yang mudah tertukar saat dibacakan lewat telepon:
// 0/O, 1/l/I. Sandi ini akan diucapkan ke orang, bukan cuma disalin-tempel.
const ALFABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789';
function sandiAcak(panjang = 16) {
  const b = randomBytes(panjang);
  return [...b].map((x) => ALFABET[x % ALFABET.length]).join('');
}

const target = process.argv.slice(2);

// ------------------------------------------------------- daftar kandidat ---
const PERAN_UJI = ['kasir_unit', 'admin', 'pimpinan', 'koordinator'];
const { data: staf, error } = await db
  .from('app_user')
  .select('id, email, nama, role, cabang_id, aktif')
  .in('role', PERAN_UJI)
  .eq('aktif', true)
  .order('role');
if (error) { console.error('FATAL: gagal membaca app_user:', error.message); process.exit(1); }

if (!target.length) {
  console.log('\n▶ Kandidat akun uji per peran (tidak ada yang diubah)\n');
  for (const p of PERAN_UJI) {
    const milik = (staf || []).filter((u) => u.role === p);
    console.log(`  ${p}  (${milik.length})`);
    for (const u of milik.slice(0, 5)) {
      console.log(`     ${u.email.padEnd(34)} ${(u.cabang_id || '(tanpa cabang)').padEnd(14)} ${u.nama}`);
    }
    if (milik.length > 5) console.log(`     … ${milik.length - 5} lagi`);
  }
  console.log('\n  Jalankan ulang dengan email yang dipilih sebagai argumen.');
  console.log('  Sertakan KOORDINATOR bila ingin menguji absensi: hanya peran itu');
  console.log('  yang memakai p_cabang_id, dan cacatnya tidak muncul di akun lain.\n');
  process.exit(0);
}

// ------------------------------------------------------------- setel sandi ---
const hasil = [];
for (const email of target) {
  const u = (staf || []).find((x) => x.email?.toLowerCase() === email.toLowerCase());
  if (!u) { hasil.push({ email, status: 'TIDAK DITEMUKAN / nonaktif' }); continue; }

  const sandi = sandiAcak();
  const { error: e } = await db.auth.admin.updateUserById(u.id, {
    password: sandi,
    // Domain fiktif — tidak ada kotak surat yang bisa mengonfirmasi. Tanpa
    // ini akun bisa tertahan di "Email not confirmed" dan gagal login tanpa
    // sebab yang jelas (lihat authSupabase.js).
    email_confirm: true,
  });
  hasil.push(e
    ? { email, status: `GAGAL: ${e.message}` }
    : { email, role: u.role, cabang: u.cabang_id || '-', nama: u.nama, sandi, status: 'OK' });
}

console.log('\n▶ Sandi akun uji — SALIN SEKARANG, tidak disimpan di mana pun\n');
for (const h of hasil) {
  if (h.status !== 'OK') { console.log(`  ✗ ${h.email.padEnd(34)} ${h.status}`); continue; }
  console.log(`  ✓ ${h.email}`);
  console.log(`      peran  : ${h.role}   cabang: ${h.cabang}   nama: ${h.nama}`);
  console.log(`      sandi  : ${h.sandi}\n`);
}

console.log('Selanjutnya, di http://localhost:3000/pembukuan :');
console.log('  · kasir_unit  → dialihkan ke /kasir; hanya cabangnya sendiri');
console.log('  · admin       → HANYA nasabahnya sendiri di Buku Pokok');
console.log('  · pimpinan    → HANYA cabangnya');
console.log('  · koordinator → semua cabang; pemilih cabang muncul di Absensi');
console.log('\nBandingkan jumlah barisnya antar peran. Kalau admin melihat baris');
console.log('milik admin lain, RLS-nya yang salah — bukan tampilannya.');
console.log('\n⚠ Sandi di atas hanya untuk pengujian. Ganti sebelum akun dipakai');
console.log('  staf sungguhan, dan jangan pakai satu sandi untuk banyak orang');
console.log('  (021 §5).\n');
