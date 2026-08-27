// lib/authSupabase.js
// =========================================================================
// Lapisan auth Supabase untuk web — D-5 (021 §5).
// =========================================================================
// TIDAK dipakai halaman mana pun sampai pembalikan di 024 §4 dilakukan.
// `lib/firebase.js` dan `lib/api.js` tidak disentuh; halaman `/pembukuan`
// masih memakai Firebase Auth hari ini.
//
// Yang disediakan di sini persis empat hal yang dibutuhkan halaman login:
// masuk, keluar, membaca sesi, dan membaca profil (peran + cabang) dari
// `koperasi.app_user`.
// =========================================================================

import { supabase } from './supabaseClient';

/**
 * Masuk dengan email + kata sandi.
 * Melempar Error berpesan Indonesia agar bisa langsung ditampilkan di form.
 */
export async function masuk(email, password) {
  const { data, error } = await supabase.auth.signInWithPassword({
    email: String(email ?? '').trim().toLowerCase(),
    password: String(password ?? ''),
  });

  if (error) {
    // Pesan asli GoTrue berbahasa Inggris dan menyebut "Invalid login
    // credentials" untuk email salah MAUPUN sandi salah — memang disengaja
    // agar tidak membocorkan email mana yang terdaftar. Dipertahankan:
    // jangan dipecah jadi dua pesan berbeda "demi kejelasan".
    if (error.message?.includes('Invalid login credentials')) {
      throw new Error('Email atau kata sandi salah.');
    }
    if (error.message?.includes('Email not confirmed')) {
      // Domain @godangulu.com fiktif (008 §0) — tidak ada surel konfirmasi
      // yang bisa sampai. Kalau ini muncul, akunnya dibuat tanpa
      // email_confirm: true dan harus diperbaiki Pengawas, bukan oleh staf.
      throw new Error(
        'Akun belum aktif. Hubungi Pengawas — akun ini perlu diaktifkan dari sisi admin.'
      );
    }
    throw new Error(error.message || 'Gagal masuk.');
  }
  return data;
}

/** Keluar dan bersihkan sesi Supabase. Sesi Firebase TIDAK disentuh. */
export async function keluar() {
  const { error } = await supabase.auth.signOut();
  if (error) throw new Error(error.message || 'Gagal keluar.');
}

/** Pengguna Supabase yang sedang masuk, atau null. */
export async function penggunaSekarang() {
  const { data, error } = await supabase.auth.getUser();
  if (error) return null;
  return data?.user ?? null;
}

/**
 * Profil dari `koperasi.app_user` untuk penjaga halaman.
 *
 * Perannya diambil DARI DATABASE, bukan dari metadata JWT. Alasannya sama
 * dengan yang menutup usul JWT di 017 LAMPIRAN: `user_metadata` dapat
 * ditulis sendiri oleh penggunanya lewat `auth.updateUser({data})`, jadi
 * peran yang dibaca dari sana bisa dikarang. Baris `app_user` hanya dapat
 * diubah Pengawas.
 *
 * RLS `app_user_baca` (002:170) mengizinkan `id = auth.uid()`, jadi
 * pembacaan ini sah tanpa hak istimewa apa pun.
 */
export async function profilSaya() {
  const pengguna = await penggunaSekarang();
  if (!pengguna) return null;

  const { data, error } = await supabase
    .from('app_user')
    .select('id, nama, email, role, cabang_id, aktif')
    .eq('id', pengguna.id)
    .maybeSingle();

  if (error) throw new Error(`Gagal membaca profil: ${error.message}`);

  if (!data) {
    // Bisa masuk ke Auth tetapi tidak punya baris app_user. Ini terjadi bila
    // akun dibuat langsung di dasbor Supabase alih-alih lewat Edge Function
    // `user-management`. Ditolak di sini, karena tanpa baris itu tidak ada
    // peran dan tidak ada cabang — seluruh RLS akan menyembunyikan semuanya
    // dan halamannya tampak "kosong" alih-alih "tidak berhak".
    throw new Error(
      'Akun ini belum terdaftar sebagai staf. Hubungi Pengawas.'
    );
  }
  if (!data.aktif) {
    throw new Error('Akun ini dinonaktifkan. Hubungi Pengawas.');
  }
  return data;
}

/**
 * Ganti kata sandi sendiri.
 *
 * Tersedia langsung dari GoTrue — tidak butuh Edge Function. Yang BELUM ada
 * adalah pemaksaannya di pemakaian pertama; lihat 024 §5 (utang U-1).
 */
export async function gantiSandi(sandiBaru) {
  const { error } = await supabase.auth.updateUser({ password: String(sandiBaru ?? '') });
  if (error) throw new Error(error.message || 'Gagal mengganti kata sandi.');
}

/** Pantau perubahan sesi. Padanan onAuthStateChanged Firebase. */
export function pantauSesi(callback) {
  const { data } = supabase.auth.onAuthStateChange((_event, session) => {
    callback(session?.user ?? null);
  });
  return () => data?.subscription?.unsubscribe();
}
