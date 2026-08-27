'use client';

// =========================================================================
// /login-supabase — halaman uji login Supabase (D-5, 021 §5)
// =========================================================================
// RUTE TERPISAH, SENGAJA. Halaman ini TIDAK menggantikan `/pembukuan` hari
// ini, dan tidak satu pun halaman lama mengimpornya.
//
// Alasannya di 024 §3: `/pembukuan` mengambil peran dan daftar cabang dari
// `getSummary()` — Cloud Function yang butuh token Firebase. Mengganti
// login-nya SEKARANG membuat seluruh halaman kosong sampai 13 fungsi
// `lib/api.js` dipindahkan. Rute ini membuat alur Supabase bisa diuji penuh
// hari ini tanpa membuat keadaan rusak.
//
// Setelah 13 fungsi pindah, `handleLogin` di `/pembukuan` ditukar satu baris
// (024 §4) dan halaman ini boleh dihapus.
// =========================================================================

import { useEffect, useState } from 'react';
import { masuk, keluar, profilSaya, pantauSesi } from '../../lib/authSupabase';

export default function LoginSupabasePage() {
  const [email, setEmail] = useState('');
  const [sandi, setSandi] = useState('');
  const [sibuk, setSibuk] = useState(false);
  const [galat, setGalat] = useState('');
  const [profil, setProfil] = useState(null);
  const [siapPantau, setSiapPantau] = useState(false);

  // Memulihkan sesi yang sudah ada saat halaman dimuat ulang.
  useEffect(() => {
    const berhenti = pantauSesi(async (pengguna) => {
      setSiapPantau(true);
      if (!pengguna) { setProfil(null); return; }
      try {
        setProfil(await profilSaya());
        setGalat('');
      } catch (e) {
        // Sesi ada tetapi profilnya bermasalah (tidak terdaftar / nonaktif).
        // Sesi dibuang supaya tidak tersangkut di keadaan setengah masuk.
        setProfil(null);
        setGalat(e.message);
        await keluar().catch(() => {});
      }
    });
    return berhenti;
  }, []);

  async function kirim(e) {
    e.preventDefault();
    setGalat('');
    setSibuk(true);
    try {
      await masuk(email, sandi);
      setSandi('');
      // Profil diisi oleh pantauSesi di atas.
    } catch (err) {
      setGalat(err.message);
    } finally {
      setSibuk(false);
    }
  }

  const kotak = {
    maxWidth: 420, margin: '48px auto', padding: 24,
    fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif',
    border: '1px solid #e5e7eb', borderRadius: 12,
  };
  const input = {
    width: '100%', padding: '10px 12px', marginTop: 4, marginBottom: 12,
    border: '1px solid #d1d5db', borderRadius: 8, fontSize: 15,
  };
  const tombol = {
    width: '100%', padding: '10px 12px', borderRadius: 8, border: 0,
    background: sibuk ? '#9ca3af' : '#2563eb', color: '#fff',
    fontSize: 15, fontWeight: 600, cursor: sibuk ? 'default' : 'pointer',
  };

  if (profil) {
    return (
      <div style={kotak}>
        <h2 style={{ marginTop: 0 }}>Masuk berhasil</h2>
        <p style={{ color: '#6b7280', fontSize: 13, marginTop: -8 }}>
          Sesi Supabase aktif. Sesi Firebase tidak disentuh.
        </p>
        <table style={{ fontSize: 14, borderSpacing: 0, width: '100%' }}>
          <tbody>
            {[
              ['Nama', profil.nama],
              ['Email', profil.email],
              ['Peran', profil.role],
              ['Cabang', profil.cabang_id ?? '(tanpa cabang)'],
              ['Aktif', String(profil.aktif)],
              ['auth.uid()', profil.id],
            ].map(([k, v]) => (
              <tr key={k}>
                <td style={{ padding: '4px 8px 4px 0', color: '#6b7280' }}>{k}</td>
                <td style={{ padding: '4px 0', fontFamily: 'ui-monospace, monospace' }}>{v}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p style={{ fontSize: 12, color: '#6b7280' }}>
          Peran dibaca dari <code>koperasi.app_user</code>, bukan dari metadata
          JWT — lihat <code>lib/authSupabase.js</code>.
        </p>
        <button style={{ ...tombol, background: '#dc2626' }} onClick={() => keluar()}>
          Keluar
        </button>
      </div>
    );
  }

  return (
    <div style={kotak}>
      <h2 style={{ marginTop: 0 }}>Masuk (Supabase)</h2>
      <p style={{ color: '#6b7280', fontSize: 13, marginTop: -8 }}>
        Halaman uji. Belum menggantikan login di /pembukuan.
      </p>
      <form onSubmit={kirim}>
        <label style={{ fontSize: 13, color: '#374151' }}>Email</label>
        <input
          style={input} type="email" value={email} autoComplete="username"
          onChange={(e) => setEmail(e.target.value)} required
          placeholder="nama@godangulu.com"
        />
        <label style={{ fontSize: 13, color: '#374151' }}>Kata sandi</label>
        <input
          style={input} type="password" value={sandi} autoComplete="current-password"
          onChange={(e) => setSandi(e.target.value)} required
        />
        <button style={tombol} type="submit" disabled={sibuk || !siapPantau}>
          {sibuk ? 'Memproses…' : 'Masuk'}
        </button>
      </form>
      {galat && (
        <p style={{ color: '#b91c1c', fontSize: 14, marginBottom: 0 }}>{galat}</p>
      )}
    </div>
  );
}
