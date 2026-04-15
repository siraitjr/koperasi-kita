'use client';

// app/page.js
// =========================================================================
// BUKU POKOK - Main Page (Client Component)
// =========================================================================

import { useState, useEffect, useRef } from 'react';
import { onAuthStateChanged, signInWithEmailAndPassword, signOut, signInWithCustomToken } from 'firebase/auth';
import { auth } from '../../lib/firebase';
import { getSummary, getBukuPokok, getKasirSummary, getJurnalTransaksi, getKoreksiStorting, setKoreksiStorting } from '../../lib/api';
import { formatRp, formatRpFull, formatRpShort } from '../../lib/format';

const KASIR_VIEW_ROLES = ['pimpinan', 'koordinator', 'pengawas', 'kasir_wilayah', 'sekretaris'];
const GLOBAL_VIEW_ROLES = ['koordinator', 'pengawas', 'kasir_wilayah', 'sekretaris'];

export default function Home() {
  const [user, setUser] = useState(null);
  const [userData, setUserData] = useState(null);
  const [screen, setScreen] = useState('loading');
  const [cabangList, setCabangList] = useState([]);
  const [selectedCabang, setSelectedCabang] = useState(null);
  const [selectedAdmin, setSelectedAdmin] = useState(null);
  const [kasirCabangList, setKasirCabangList] = useState([]);
  const [showLogoutModal, setShowLogoutModal] = useState(false);

  // ==================== AUTO-LOGIN dari Android App ====================
  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const idToken = urlParams.get('idToken');
    if (!idToken) return;

    // Segera hapus token dari URL (keamanan - tidak tersimpan di history browser)
    window.history.replaceState({}, '', window.location.pathname);

    // Tukar ID Token dengan Custom Token via Cloud Function
    fetch('https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net/generateAutoLoginToken', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken }),
    })
      .then((res) => res.json())
      .then((data) => {
        if (data.customToken) {
          return signInWithCustomToken(auth, data.customToken);
        }
      })
      .catch((err) => console.error('Auto-login gagal:', err));
  }, []);

  // ==================== HELPERS: Save/Restore navigation ====================
  const saveNav = (scr, cabang, admin) => {
    try {
      const nav = { screen: scr };
      if (cabang) nav.cabang = cabang;
      if (admin) nav.admin = admin;
      sessionStorage.setItem('ksp_nav', JSON.stringify(nav));
    } catch (e) { /* ignore */ }
  };

  const restoreNav = () => {
    try {
      const raw = sessionStorage.getItem('ksp_nav');
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  };

  const clearNav = () => {
    try { sessionStorage.removeItem('ksp_nav'); } catch (e) { /* ignore */ }
  };

  // ==================== AUTH STATE ====================
  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (firebaseUser) => {
      if (firebaseUser) {
        setUser(firebaseUser);
        try {
          const result = await getSummary();
          if (result.success) {
            setUserData(result.data.user);
            setCabangList(result.data.cabangList);

            // Redirect kasir ke halaman /kasir (kecuali jika dari /kasir untuk lihat Buku Pokok)
            const userRole = result.data.user.role;
            const urlParams = new URLSearchParams(window.location.search);
            const fromKasir = urlParams.get('from') === 'kasir';

            if (userRole === 'kasir_unit' && !fromKasir) {
              window.location.href = '/kasir';
              return;
            }

            // Kasir unit dari /kasir: langsung ke Buku Pokok, skip home
            if (fromKasir && userRole === 'kasir_unit') {
              if (result.data.cabangList.length === 1) {
                setSelectedCabang(result.data.cabangList[0]);
                setScreen('bukuPokok');
              } else {
                setScreen('dashboard');
              }
              return;
            }

            // Fetch kasir cabang list untuk pimpinan/koordinator/pengawas
            if (KASIR_VIEW_ROLES.includes(userRole)) {
              try {
                const kasirResult = await getKasirSummary();
                if (kasirResult.success) {
                  setKasirCabangList(kasirResult.data.cabangList || []);
                }
              } catch (e) {
                // Kasir data optional, tidak blocking
                console.error('Failed to get kasir summary:', e);
              }
            }

            // Restore previous screen after refresh
            const saved = restoreNav();
            if (saved && saved.screen) {
              if (saved.screen === 'bukuPokok' && saved.cabang) {
                setSelectedCabang(saved.cabang);
                setSelectedAdmin(saved.admin || null);
                setScreen('bukuPokok');
              } else if (saved.screen === 'jurnalTransaksi' && saved.cabang) {
                setSelectedCabang(saved.cabang);
                setScreen('jurnalTransaksi');
              } else if (saved.screen === 'jurnalDashboard') {
                setScreen('jurnalDashboard');
              } else if (saved.screen === 'home' && saved.cabang && GLOBAL_VIEW_ROLES.includes(userRole)) {
                setSelectedCabang(saved.cabang);
                setScreen('home');
              } else if (saved.screen === 'dashboard') {
                setScreen('dashboard');
              } else {
                if (GLOBAL_VIEW_ROLES.includes(userRole)) {
                  setScreen('dashboard');
                } else {
                  setScreen('home');
                }
              }
            } else {
              if (GLOBAL_VIEW_ROLES.includes(userRole)) {
                setScreen('dashboard');
              } else {
                setScreen('home');
              }
            }
          }
        } catch (err) {
          console.error('Failed to get summary:', err);
          setScreen('home');
        }
      } else {
        setUser(null);
        setUserData(null);
        clearNav();
        setScreen('login');
      }
    });
    return () => unsubscribe();
  }, []);

  // ==================== HANDLERS ====================
  const handleLogin = async (email, password) => {
    await signInWithEmailAndPassword(auth, email, password);
  };

  const handleLogout = () => {
    // Hanya kasir_unit yang perlu absen sebelum logout
    if (userData?.role === 'kasir_unit') {
      setShowLogoutModal(true);
    } else {
      doLogout();
    }
  };

  const doLogout = async () => {
    setShowLogoutModal(false);
    clearNav();
    await signOut(auth);
  };

  const goToAbsensi = () => {
    setShowLogoutModal(false);
    // Navigasi ke halaman kasir/absensi
    window.location.href = '/kasir?screen=absensi';
  };

  const handleSelectBook = (book) => {
    const isGlobal = GLOBAL_VIEW_ROLES.includes(userData?.role);
    if (book === 'bukuPokok') {
      if (isGlobal && selectedCabang) {
        setScreen('bukuPokok');
        saveNav('bukuPokok', selectedCabang, null);
      } else {
        setScreen('dashboard');
        saveNav('dashboard', null, null);
      }
    } else if (book === 'jurnalTransaksi') {
      if (isGlobal && selectedCabang) {
        setScreen('jurnalTransaksi');
        saveNav('jurnalTransaksi', selectedCabang, null);
      } else {
        setScreen('jurnalDashboard');
        saveNav('jurnalDashboard', null, null);
      }
    } else if (['jurnalKasir', 'bukuRekap', 'kasPenuntun', 'bukuTunai', 'bukuEkspedisi', 'ringkasanKas', 'absensiKaryawan'].includes(book)) {
      // Pimpinan/koordinator/pengawas: buka halaman kasir langsung (read-only)
      const kasirScreenMap = {
        jurnalKasir: 'jurnal',
        bukuRekap: 'bukuRekap',
        kasPenuntun: 'kasPenuntun',
        bukuTunai: 'bukuTunai',
        bukuEkspedisi: 'bukuEkspedisi',
        ringkasanKas: 'ringkasan',
        absensiKaryawan: 'absensi',
      };
      window.location.href = `/kasir?screen=${kasirScreenMap[book]}`;
    }
  };

  const handleSelectCabang = (cabang) => {
    setSelectedCabang(cabang);
    setSelectedAdmin(null);
    if (GLOBAL_VIEW_ROLES.includes(userData?.role)) {
      setScreen('home');
      saveNav('home', cabang, null);
    } else {
      setScreen('bukuPokok');
      saveNav('bukuPokok', cabang, null);
    }
  };

  const handleSelectAdmin = (admin) => {
    setSelectedAdmin(admin);
    saveNav('bukuPokok', selectedCabang, admin);
  };

  const handleBackToDashboard = () => {
    if (userData?.role === 'kasir_unit') {
      window.location.href = '/kasir';
      return;
    }
    if (GLOBAL_VIEW_ROLES.includes(userData?.role)) {
      setScreen('home');
      setSelectedAdmin(null);
      saveNav('home', selectedCabang, null);
    } else {
      setScreen('dashboard');
      setSelectedCabang(null);
      setSelectedAdmin(null);
      saveNav('dashboard', null, null);
    }
  };

  const handleSelectJurnalCabang = (cabang) => {
    setSelectedCabang(cabang);
    setScreen('jurnalTransaksi');
    saveNav('jurnalTransaksi', cabang, null);
  };

  const handleBackFromJurnal = () => {
    if (GLOBAL_VIEW_ROLES.includes(userData?.role)) {
      setScreen('home');
      saveNav('home', selectedCabang, null);
    } else {
      setScreen('jurnalDashboard');
      setSelectedCabang(null);
      saveNav('jurnalDashboard', null, null);
    }
  };

  const handleBackToHome = () => {
    if (userData?.role === 'kasir_unit') {
      window.location.href = '/kasir';
      return;
    }
    if (GLOBAL_VIEW_ROLES.includes(userData?.role)) {
      setScreen('dashboard');
      setSelectedCabang(null);
      setSelectedAdmin(null);
      saveNav('dashboard', null, null);
    } else {
      setScreen('home');
      setSelectedCabang(null);
      setSelectedAdmin(null);
      saveNav('home', null, null);
    }
  };

  // ==================== RENDER ====================
  if (screen === 'loading') {
    return (
      <div className="page-container">
        <div className="loading-container">
          <div className="loading-spinner" />
          <p className="loading-text">Memuat...</p>
        </div>
      </div>
    );
  }

  if (screen === 'login') {
    return <LoginScreen onLogin={handleLogin} />;
  }

  let content;
  if (screen === 'home') {
    content = (
      <HomeScreen
        user={userData}
        kasirCabangList={kasirCabangList}
        selectedCabang={selectedCabang}
        onSelectBook={handleSelectBook}
        onBackToDashboard={GLOBAL_VIEW_ROLES.includes(userData?.role) ? handleBackToHome : null}
        onLogout={handleLogout}
      />
    );
  } else if (screen === 'jurnalDashboard') {
    content = (
      <DashboardScreen
        user={userData}
        cabangList={cabangList}
        onSelectCabang={handleSelectJurnalCabang}
        onBack={handleBackToHome}
        onLogout={handleLogout}
        title="Jurnal Transaksi"
        subtitle="Pilih cabang untuk melihat jurnal transaksi"
      />
    );
  } else if (screen === 'jurnalTransaksi' && selectedCabang) {
    content = (
      <JurnalTransaksiScreen
        user={userData}
        cabang={selectedCabang}
        onBack={handleBackFromJurnal}
        onLogout={handleLogout}
        onSelectBook={handleSelectBook}
        showKasirMenus={KASIR_VIEW_ROLES.includes(userData?.role) && kasirCabangList?.length > 0}
      />
    );
  } else if (screen === 'bukuPokok' && selectedCabang) {
    content = (
      <BukuPokokScreen
        user={userData}
        cabang={selectedCabang}
        selectedAdmin={selectedAdmin}
        onSelectAdmin={handleSelectAdmin}
        onBack={handleBackToDashboard}
        onLogout={handleLogout}
        onSelectBook={handleSelectBook}
        showKasirMenus={KASIR_VIEW_ROLES.includes(userData?.role) && kasirCabangList?.length > 0}
      />
    );
  } else {
    content = (
      <DashboardScreen
        user={userData}
        cabangList={cabangList}
        onSelectCabang={handleSelectCabang}
        onBack={handleBackToHome}
        onLogout={handleLogout}
      />
    );
  }

  return (
    <>
      {content}
      {showLogoutModal && (
        <LogoutAbsensiModal
          onAbsen={goToAbsensi}
          onLogout={doLogout}
          onClose={() => setShowLogoutModal(false)}
        />
      )}
    </>
  );
}

// ============================================================
// LOGOUT ABSENSI MODAL
// ============================================================
function LogoutAbsensiModal({ onAbsen, onLogout, onClose }) {
  return (
    <div style={{
      position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
      background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center',
      justifyContent: 'center', zIndex: 9999, padding: 16
    }} onClick={onClose}>
      <div style={{
        background: '#fff', borderRadius: 16, padding: 24, maxWidth: 360,
        width: '100%', boxShadow: '0 20px 60px rgba(0,0,0,0.3)'
      }} onClick={e => e.stopPropagation()}>
        <h3 style={{ margin: '0 0 8px', fontSize: 18, fontWeight: 700, color: '#1e293b' }}>Keluar</h3>
        <p style={{ margin: '0 0 24px', color: '#64748b', fontSize: 14, lineHeight: 1.5 }}>
          Apakah Anda ingin absen terlebih dahulu sebelum keluar?
        </p>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <button onClick={onAbsen} style={{
            padding: '12px 16px', borderRadius: 10, border: 'none', cursor: 'pointer',
            background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', color: '#fff',
            fontWeight: 600, fontSize: 14, display: 'flex', alignItems: 'center',
            justifyContent: 'center', gap: 8
          }}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>
            </svg>
            Absen Dulu
          </button>
          <button onClick={onLogout} style={{
            padding: '12px 16px', borderRadius: 10, cursor: 'pointer',
            background: 'transparent', color: '#ef4444', fontWeight: 600, fontSize: 14,
            border: '1.5px solid #fca5a5', display: 'flex', alignItems: 'center',
            justifyContent: 'center', gap: 8
          }}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>
            </svg>
            Langsung Keluar
          </button>
          <button onClick={onClose} style={{
            padding: '8px 16px', borderRadius: 10, cursor: 'pointer',
            background: 'transparent', color: '#94a3b8', fontWeight: 500, fontSize: 13,
            border: 'none'
          }}>
            Batal
          </button>
        </div>
      </div>
    </div>
  );
}

// ============================================================
// TOP BAR NAVIGATION (menu cepat di header)
// ============================================================
function TopBarNav({ currentScreen, onSelectBook, showKasirMenus }) {
  const menus = [
    { id: 'bukuPokok', label: 'Buku Pokok' },
    { id: 'jurnalTransaksi', label: 'Jurnal Transaksi' },
  ];

  const kasirMenus = showKasirMenus ? [
    { id: 'jurnalKasir', label: 'Jurnal Kasir' },
    { id: 'bukuRekap', label: 'Buku Rekap' },
    { id: 'bukuTunai', label: 'Buku Tunai' },
    { id: 'kasPenuntun', label: 'Kas Penuntun' },
    { id: 'bukuEkspedisi', label: 'Buku Ekspedisi' },
    { id: 'ringkasanKas', label: 'Ringkasan Kas' },
    { id: 'absensiKaryawan', label: 'Absensi' },
  ] : [];

  const allMenus = [...menus, ...kasirMenus];

  return (
    <nav className="top-bar-nav">
      {allMenus.map((m) => (
        <button
          key={m.id}
          className={`top-bar-nav-item${currentScreen === m.id ? ' active' : ''}`}
          onClick={() => onSelectBook(m.id)}
          disabled={currentScreen === m.id}
        >
          {m.label}
        </button>
      ))}
    </nav>
  );
}

// ============================================================
// LOGIN SCREEN
// ============================================================
function LoginScreen({ onLogin }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!email || !password) {
      setError('Email dan password harus diisi');
      return;
    }
    setLoading(true);
    setError('');
    try {
      await onLogin(email, password);
    } catch (err) {
      setError(
        err.code === 'auth/invalid-credential'
          ? 'Email atau password salah'
          : err.code === 'auth/user-not-found'
          ? 'Akun tidak ditemukan'
          : 'Terjadi kesalahan, coba lagi'
      );
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-bg" />
      {/* Decorative shapes */}
      <div className="login-decor login-decor-1" />
      <div className="login-decor login-decor-2" />
      <div className="login-decor login-decor-3" />

      <div className="login-card">
        {/* Logo / Brand */}
        <div className="login-logo">
          <div className="login-logo-icon" style={{ background: '#000000', padding: 0, overflow: 'hidden' }}>
            <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <h1>Koperasi Kita</h1>
          <p className="login-subtitle">Sistem Pembukuan Digital</p>
        </div>

        <div className="login-form" >
          {error && <div className="error-msg">{error}</div>}

          <div className="input-group">
            <label>Email</label>
            <div className="input-wrapper">
              <span className="input-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/></svg>
              </span>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="nama@email.com"
                onKeyDown={(e) => e.key === 'Enter' && handleSubmit(e)}
              />
            </div>
          </div>

          <div className="input-group">
            <label>Password</label>
            <div className="input-wrapper">
              <span className="input-icon">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              </span>
              <input
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Masukkan password"
                onKeyDown={(e) => e.key === 'Enter' && handleSubmit(e)}
              />
              <button
                type="button"
                className="btn-eye"
                onClick={() => setShowPassword(!showPassword)}
                tabIndex={-1}
              >
                {showPassword ? (
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/>
                    <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/>
                    <path d="m1 1 22 22"/>
                    <path d="M14.12 14.12a3 3 0 1 1-4.24-4.24"/>
                  </svg>
                ) : (
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                    <circle cx="12" cy="12" r="3"/>
                  </svg>
                )}
              </button>
            </div>
          </div>

          <button type="button" className="btn-login" disabled={loading} onClick={handleSubmit}>
            {loading ? <span className="spinner" /> : 'Masuk'}
          </button>
        </div>

        <p className="login-footer">Gunakan akun yang terdaftar di aplikasi</p>
      </div>

      <p className="login-domain">koperasi-kita.com</p>
    </div>
  );
}


// ============================================================
// HOME SCREEN (Menu Buku)
// ============================================================
function HomeScreen({ user, kasirCabangList, selectedCabang, onSelectBook, onBackToDashboard, onLogout }) {
  const showKasirMenus = KASIR_VIEW_ROLES.includes(user?.role) && kasirCabangList?.length > 0;

  const books = [
    {
      id: 'bukuPokok',
      name: 'Buku Pokok',
      desc: 'Catatan pinjaman & pembayaran harian nasabah',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z"/>
          <path d="M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"/>
          <path d="M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"/>
          <path d="M10 6h4"/><path d="M10 10h4"/><path d="M10 14h4"/><path d="M10 18h4"/>
        </svg>
      ),
      ready: true,
    },
    {
      id: 'jurnalTransaksi',
      name: 'Jurnal Transaksi',
      desc: 'Catatan permanen setiap pembayaran & pencairan',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
          <polyline points="14 2 14 8 20 8"/>
          <line x1="16" x2="8" y1="13" y2="13"/>
          <line x1="16" x2="8" y1="17" y2="17"/>
          <line x1="10" x2="8" y1="9" y2="9"/>
        </svg>
      ),
      ready: true,
    },
  ];

  const kasirMenus = showKasirMenus ? [
    {
      id: 'jurnalKasir', name: 'Jurnal Kasir', desc: 'Catatan transaksi kas harian',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/><line x1="16" x2="8" y1="13" y2="13"/><line x1="16" x2="8" y1="17" y2="17"/><line x1="10" x2="8" y1="9" y2="9"/>
        </svg>
      ),
      ready: true,
    },
    {
      id: 'bukuRekap', name: 'Buku Rekap', desc: 'Rekap harian per resort',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M3 3h18v18H3zM3 9h18M3 15h18M9 3v18M15 3v18"/>
        </svg>
      ),
      ready: true,
    },
    {
      id: 'bukuTunai', name: 'Buku Tunai', desc: 'Rekap kasbon & tunai harian per resort',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <rect width="20" height="14" x="2" y="5" rx="2"/><line x1="2" x2="22" y1="10" y2="10"/>
          <path d="M12 15h.01M8 15h.01M16 15h.01"/>
        </svg>
      ),
      ready: true,
    },
    {
      id: 'kasPenuntun', name: 'Kas Penuntun', desc: 'Buku kas harian penuntun',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M3 3h18v18H3zM3 9h18M3 15h18M9 3v18M15 3v18"/>
        </svg>
      ),
      ready: true,
    },
    {
      id: 'bukuEkspedisi', name: 'Buku Ekspedisi', desc: 'Kas harian masuk & keluar ekspedisi',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" x2="8" y1="13" y2="13"/><line x1="16" x2="8" y1="17" y2="17"/><polyline points="10 9 9 9 8 9"/>
        </svg>
      ),
      ready: true,
    },
    {
      id: 'ringkasanKas', name: 'Ringkasan Kas', desc: 'Rekapitulasi pemasukan & pengeluaran',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M21.21 15.89A10 10 0 1 1 8 2.83"/><path d="M22 12A10 10 0 0 0 12 2v10z"/>
        </svg>
      ),
      ready: true,
    },
    {
      id: 'absensiKaryawan', name: 'Absensi Karyawan', desc: 'Lihat absensi harian karyawan',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <rect width="18" height="18" x="3" y="4" rx="2" ry="2"/><line x1="16" x2="16" y1="2" y2="6"/><line x1="8" x2="8" y1="2" y2="6"/><line x1="3" x2="21" y1="10" y2="10"/>
          <path d="m9 16 2 2 4-4"/>
        </svg>
      ),
      ready: true,
    },
  ] : [];

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          {onBackToDashboard ? (
            <>
              <button onClick={onBackToDashboard} className="btn-back">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                  <path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>
                </svg>
              </button>
              <div>
                <h1>{selectedCabang?.name || 'Pilih Cabang'}</h1>
                <p>KSP Sigodang Ulu Jaya</p>
              </div>
            </>
          ) : (
            <>
              <div className="top-bar-logo" style={{ background: '#000000', padding: 0, overflow: 'hidden' }}>
                <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
              </div>
              <div>
                <h1>Koperasi Kita</h1>
                <p>Sistem Pembukuan Digital</p>
              </div>
            </>
          )}
        </div>
        <div className="top-bar-right">
          {user && (
            <div className="user-badge">
              <span className="user-name">{user.name}</span>
              <span className="user-role">{user.role}</span>
            </div>
          )}
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
              <polyline points="16 17 21 12 16 7"/>
              <line x1="21" x2="9" y1="12" y2="12"/>
            </svg>
          </button>
        </div>
      </header>

      <main className="home-content fade-in">
        <div className="home-welcome">
          <div className="home-welcome-icon" style={{ background: '#000000', padding: 0, overflow: 'hidden' }}>
            <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <h2>Selamat Datang di Pembukuan Digital</h2>
          <p className="home-ksp-name">KSP Sigodang Ulu Jaya</p>
          {user && <p className="home-user-greeting">Halo, {user.name}</p>}
        </div>

        <div className="home-section-label">Pilih Menu Pembukuan</div>

        <div className="book-grid">
          {books.map((book) => (
            <button
              key={book.id}
              onClick={() => book.ready && onSelectBook(book.id)}
              className={`book-card ${!book.ready ? 'book-card-disabled' : ''}`}
              disabled={!book.ready}
            >
              <div className="book-card-icon">{book.icon}</div>
              <div className="book-card-info">
                <h3>{book.name}</h3>
                <p>{book.desc}</p>
              </div>
              {book.ready ? (
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><polyline points="9 18 15 12 9 6"/></svg>
              ) : (
                <span className="book-coming-soon">Segera</span>
              )}
            </button>
          ))}
        </div>

        {/* Kasir menus untuk pimpinan/koordinator/pengawas */}
        {kasirMenus.length > 0 && (
          <>
            <div className="home-section-label" style={{ marginTop: 24 }}>Menu Kasir</div>
            <div className="book-grid">
              {kasirMenus.map((menu) => (
                <button
                  key={menu.id}
                  onClick={() => onSelectBook(menu.id)}
                  className="book-card"
                >
                  <div className="book-card-icon">{menu.icon}</div>
                  <div className="book-card-info">
                    <h3>{menu.name}</h3>
                    <p>{menu.desc}</p>
                  </div>
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><polyline points="9 18 15 12 9 6"/></svg>
                </button>
              ))}
            </div>
          </>
        )}
      </main>
    </div>
  );
}


// ============================================================
// DASHBOARD SCREEN (Pilih Cabang)
// ============================================================
function DashboardScreen({ user, cabangList, onSelectCabang, onBack, onLogout, title, subtitle }) {
  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>
            </svg>
          </button>
          <div>
            <h1>{title || 'Buku Pokok'}</h1>
            <p>KSP Sigodang Ulu Jaya</p>
          </div>
        </div>
        <div className="top-bar-right">
          {user && (
            <div className="user-badge">
              <span className="user-name">{user.name}</span>
              <span className="user-role">{user.role}</span>
            </div>
          )}
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
              <polyline points="16 17 21 12 16 7"/>
              <line x1="21" x2="9" y1="12" y2="12"/>
            </svg>
          </button>
        </div>
      </header>

      <main className="dash-content fade-in">
        <div className="dash-header">
          <h2>Pilih Cabang</h2>
          <p>{subtitle || 'Pilih cabang untuk melihat Buku Pokok nasabah'}</p>
        </div>

        <div className="cabang-grid">
          {cabangList.map((cab) => (
            <button
              key={cab.id}
              onClick={() => onSelectCabang(cab)}
              className="cabang-card"
            >
              <div className="cabang-card-icon">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                  <path d="M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z"/>
                  <path d="M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"/>
                  <path d="M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"/>
                  <path d="M10 6h4"/><path d="M10 10h4"/><path d="M10 14h4"/><path d="M10 18h4"/>
                </svg>
              </div>
              <div className="cabang-card-info">
                <h3>{cab.name}</h3>
                <p>{cab.admins?.length || 0} Resort</p>
              </div>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="m9 18 6-6-6-6"/>
              </svg>
            </button>
          ))}
        </div>
      </main>
    </div>
  );
}


// ============================================================
// BUKU POKOK SCREEN
// ============================================================
function BukuPokokScreen({ user, cabang, selectedAdmin, onSelectAdmin, onBack, onLogout, onSelectBook, showKasirMenus }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  // ✅ Tab Aktif/Sisa Tabungan/Nasabah Lunas dihilangkan — semua nasabah tampil gabungan
  // di tabel PB/L1/CM/MB/ML; nasabah sisa tabungan & lunas ditandai nama warna merah
  // (lihat logika `namaColor` di baris tabel).
  const [statusFilter, setStatusFilter] = useState('semua');
  const [showDetail, setShowDetail] = useState(null);
  const [visibleDateCount, setVisibleDateCount] = useState(7);
  const [tabelFilter, setTabelFilter] = useState('semua');
  const [stortingMonth, setStortingMonth] = useState(null); // format: '2026-02' (auto-set saat pilih stortingGlobal)
  const [koreksiSGMap, setKoreksiSGMap] = useState({}); // { adminUid: { l1, cm, mb, ml } } untuk bulan lalu
  const [showKoreksiModal, setShowKoreksiModal] = useState(false);
  const [koreksiInput, setKoreksiInput] = useState({ l1: '', cm: '', mb: '', ml: '' });
  const [savingKoreksi, setSavingKoreksi] = useState(false);
  const [koreksiError, setKoreksiError] = useState('');
  const tableRef = useRef(null);
  const [stickyOffsets, setStickyOffsets] = useState({ nama: 55, panggilan: 210 });

  useEffect(() => {
    const table = tableRef.current;
    if (!table) return;
    requestAnimationFrame(() => {
      const ths = table.querySelectorAll('thead > tr > th');
      if (ths.length >= 6) {
        const keW = ths[3].offsetWidth;
        const namaW = ths[4].offsetWidth;
        setStickyOffsets({ nama: keW, panggilan: keW + namaW });
      }
    });
  }, [data, tabelFilter, selectedAdmin, statusFilter]);

  // ==================== HELPER: Kategori Tabel Nasabah ====================
const BULAN_MAP = { 'Jan': 0, 'Feb': 1, 'Mar': 2, 'Apr': 3, 'Mei': 4, 'Jun': 5, 'Jul': 6, 'Agu': 7, 'Sep': 8, 'Okt': 9, 'Nov': 10, 'Des': 11 };

function parseTanggalIndo(tgl) {
  if (!tgl) return null;
  const parts = tgl.split(' ');
  if (parts.length !== 3) return null;
  const month = BULAN_MAP[parts[1]];
  if (month === undefined) return null;
  return { month, year: parseInt(parts[2]) };
}

function getKategoriNasabah(nasabah) {
  const tgl = nasabah.tanggalPencairan || nasabah.tanggalPengajuan || nasabah.tanggalDaftar || '';
  const parsed = parseTanggalIndo(tgl);
  if (!parsed) return 'ML';

  const now = new Date();
  const wibOffset = 7 * 60 * 60 * 1000;
  const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOffset);
  const nowMonth = wib.getMonth();
  const nowYear = wib.getFullYear();

  // Hitung selisih bulan
  const diff = (nowYear - parsed.year) * 12 + (nowMonth - parsed.month);

  if (diff === 0) return 'PB';
  if (diff === 1) return 'L1';
  if (diff === 2) return 'CM';
  if (diff === 3) return 'MB';
  return 'ML'; // 4 bulan atau lebih
}

  // ==================== FETCH DATA ====================
  // Storting Global butuh SEMUA nasabah (termasuk lunas) agar target historis akurat
  const effectiveStatus = tabelFilter === 'stortingGlobal' ? 'semua' : statusFilter;
  useEffect(() => {
    async function fetchData() {
      setLoading(true);
      setError('');
      try {
        const result = await getBukuPokok({
          cabangId: cabang.id,
          adminUid: selectedAdmin?.uid || '',
          status: effectiveStatus,
        });
        if (result.success && result.type === 'buku_pokok') {
          setData(result.data);
        }
      } catch (err) {
        setError('Gagal memuat data: ' + err.message);
      } finally {
        setLoading(false);
      }
    }
    fetchData();
  }, [cabang.id, selectedAdmin?.uid, effectiveStatus]);

  // ==================== FETCH KOREKSI STORTING ====================
  useEffect(() => {
    if (tabelFilter !== 'stortingGlobal' || !stortingMonth) { setKoreksiSGMap({}); return; }
    const [smY, smM] = stortingMonth.split('-').map(Number);
    const prev = new Date(smY, smM - 2, 1);
    const prevBulan = `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`;
    getKoreksiStorting({ cabangId: cabang.id, bulan: prevBulan })
      .then(result => { if (result.success) setKoreksiSGMap(result.data || {}); })
      .catch(() => {});
  }, [tabelFilter, stortingMonth, cabang.id]);

  // ==================== EXPAND RIWAYAT PINJAMAN → BARIS HISTORIS ====================
  // ✅ Saat nasabah top-up/lanjut pinjaman, pinjaman lama diarsipkan ke
  // `riwayat_pinjaman/`. Agar pinjaman lama TETAP tampil di tabel PB/L1/CM/MB/ML
  // sesuai tanggal pencairan-nya (historis), kita expand tiap entry `riwayatPinjaman`
  // menjadi baris virtual dengan flag `isHistorical: true` (nama ditandai merah).
  // 0 RTDB read tambahan: `riwayatPinjaman` sudah ikut di response bukuPokokApi.
  // Tidak menyentuh stortingGlobalData / prevMonthSGTotals (keduanya pakai data.nasabah asli).
  const nasabahExpanded = (() => {
    const list = data?.nasabah || [];
    const result = [];
    list.forEach((n) => {
      result.push(n);
      (n.riwayatPinjaman || []).forEach((r) => {
        result.push({
          ...n,
          id: `${n.id}__r${r.pinjamanKe}`,
          pinjamanKe: r.pinjamanKe,
          besarPinjaman: r.besarPinjaman || 0,
          totalPelunasan: r.totalPelunasan || 0,
          totalDibayar: r.totalDibayar || 0,
          sisaUtang: r.sisaUtang || 0,
          tenor: r.tenor || 0,
          tanggalDaftar: r.tanggalPengajuan || '',
          tanggalPengajuan: r.tanggalPengajuan || '',
          tanggalPencairan: r.tanggalPencairan || '',
          tanggalLunasCicilan: r.tanggalLunasCicilan || '',
          status: r.status || '',
          statusKhusus: '', // entry historis bukan MENUNGGU_PENCAIRAN
          tanggalStatusKhusus: '',
          pembayaran: r.pembayaran || {},
          simpanan: 0,
          tarikTabungan: 0,
          totalDiterima: 0,
          sisaUtangLama: 0,
          sisaUtangLamaSebelumTopUp: 0,
          riwayatPinjaman: [],
          isHistorical: true,
        });
      });
    });
    return result;
  })();

  // ==================== FILTER ====================
  const filtered = (nasabahExpanded.filter((n) => {
    // Filter tabel (PB/L1/CM/MB/ML) — skip for stortingGlobal
    if (tabelFilter !== 'semua' && tabelFilter !== 'stortingGlobal') {
      if (getKategoriNasabah(n) !== tabelFilter) return false;
    }
    // Filter pencarian
    if (!search) return true;
    const q = search.toLowerCase();
    return (
      n.namaKtp.toLowerCase().includes(q) ||
      n.namaPanggilan.toLowerCase().includes(q) ||
      n.nik.includes(q) ||
      n.nomorAnggota.includes(q)
    );
  }) || []).sort((a, b) => {
    // Urutkan berdasarkan tanggal pencairan dari awal ke akhir
    const BULAN_MAP = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, Mei: 4, Jun: 5, Jul: 6, Agu: 7, Sep: 8, Okt: 9, Nov: 10, Des: 11 };
    const parseDate = (s) => {
      if (!s) return null;
      const p = s.split(' ');
      if (p.length !== 3) return null;
      return new Date(parseInt(p[2]), BULAN_MAP[p[1]] ?? 0, parseInt(p[0]));
    };
    const tglA = a.tanggalPencairan || a.tanggalPengajuan || a.tanggalDaftar || '';
    const tglB = b.tanggalPencairan || b.tanggalPengajuan || b.tanggalDaftar || '';
    const dateA = parseDate(tglA);
    const dateB = parseDate(tglB);
    if (!dateA && !dateB) return 0;
    if (!dateA) return 1;
    if (!dateB) return -1;
    return dateA - dateB;
  });

  const dates = data?.tanggalList || [];
  const visibleDates = dates.slice(0, visibleDateCount);
  const admins = cabang.admins || [];

  // ==================== TABEL MODE (PB/L1/CM/MB/ML) ====================
  const isTabelMode = tabelFilter !== 'semua' && tabelFilter !== 'stortingGlobal';
  const isStortingGlobalMode = tabelFilter === 'stortingGlobal';

  // ==================== STORTING GLOBAL COMPUTATION ====================
  const stortingGlobalData = (() => {
    if (!isStortingGlobalMode || !data?.nasabah || !stortingMonth) return [];
    const allNasabah = data.nasabah;
    const BULAN_INDO_ARR = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

    // Parse stortingMonth "2026-02" → year=2026, month=1 (0-indexed)
    const [smYear, smMonth] = stortingMonth.split('-').map(Number);
    const selectedYear = smYear;
    const selectedMonth = smMonth - 1; // 0-indexed

    // Hari ini WIB
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOffset);
    const todayDate = new Date(wib.getFullYear(), wib.getMonth(), wib.getDate());

    // Tentukan batas akhir: jika bulan yang dipilih = bulan ini → sampai hari ini, jika bulan lalu → sampai akhir bulan
    const isCurrentMonth = (selectedYear === wib.getFullYear() && selectedMonth === wib.getMonth());
    const endDate = isCurrentMonth
      ? todayDate
      : new Date(selectedYear, selectedMonth + 1, 0); // Hari terakhir bulan itu

    // Generate hari kerja bulan yang dipilih (skip Minggu & tanggal merah)
    const LIBUR_NASIONAL_SG = [[1,1],[16,1],[17,2],[19,3],[21,3],[3,4],[1,5],[14,5],[16,6],[17,8],[25,8],[25,12]];
    const isHariKerjaSG = (d) => {
      if (d.getDay() === 0) return false;
      const dd = d.getDate(), mm = d.getMonth() + 1;
      return !LIBUR_NASIONAL_SG.some(([ld, lm]) => ld === dd && lm === mm);
    };
    const workingDates = [];
    const cur = new Date(selectedYear, selectedMonth, 1);
    while (cur <= endDate) {
      if (isHariKerjaSG(cur)) {
        const dd = String(cur.getDate()).padStart(2, '0');
        const mmm = BULAN_INDO_ARR[cur.getMonth()];
        const yyyy = cur.getFullYear();
        workingDates.push(`${dd} ${mmm} ${yyyy}`);
      }
      cur.setDate(cur.getDate() + 1);
    }

    // Helper: parse "07 Feb 2026" ke Date object
    const BULAN_MAP_REV = {};
    BULAN_INDO_ARR.forEach((b, i) => { BULAN_MAP_REV[b] = i; });
    const parseDateStr = (s) => {
      if (!s) return null;
      const parts = s.split(' ');
      if (parts.length !== 3) return null;
      const m = BULAN_MAP_REV[parts[1]];
      if (m === undefined) return null;
      return new Date(parseInt(parts[2]), m, parseInt(parts[0]));
    };

    // Hitung per tanggal
    let dropBerjalan = 0;
    let targetBerjalan = 0;
    let stortingBerjalan = 0;
    const rows = [];

    for (const dateStr of workingDates) {
      let dropKini = 0;
      let stortingKini = 0;
      let targetKini = 0;
      let pbKini = 0, l1Kini = 0, cmKini = 0, mbKini = 0, mlKini = 0;

      const currentDate = parseDateStr(dateStr);
      // Batas 3 bulan: tanggal 1, 3 bulan sebelum tanggal ini
      const threeMonthsAgo = currentDate ? new Date(currentDate.getFullYear(), currentDate.getMonth() - 3, 1) : null;

      allNasabah.forEach(n => {
        // Drop Kini: total uang yang dicairkan pada tanggal ini
        const tglCair = n.tanggalPencairan || '';
        if (tglCair === dateStr) {
          dropKini += n.besarPinjaman || 0;
        }

        // Storting Kini: total cicilan/pembayaran pada tanggal ini
        const pay = n.pembayaran?.[dateStr];
        if (pay) {
          const total = pay.total || 0;
          stortingKini += total;
          // Breakdown per kategori
          const kat = getKategoriNasabah(n);
          if (kat === 'PB') pbKini += total;
          else if (kat === 'L1') l1Kini += total;
          else if (kat === 'CM') cmKini += total;
          else if (kat === 'MB') mbKini += total;
          else mlKini += total;
        }

        // Target Kini: besarPinjaman × 3% untuk nasabah yang eligible pada tanggal ini
        // ✅ FIX H+1: Konsisten dengan Android RingkasanDashboardScreen.kt & fullRecalculateAdminSummary:
        // - Nasabah yang lunas TEPAT pada dateStr tetap masuk target hari itu (H+1 rule)
        //   → gunakan pembayaran SEBELUM dateStr (pd < currentDate, bukan <=)
        // - Nasabah yang baru MENUNGGU_PENCAIRAN pada dateStr tetap masuk target hari itu
        //   → cek tanggalStatusKhusus: hanya exclude jika berlaku sebelum dateStr
        // - Tidak filter berdasarkan status saat ini (n.status) karena status nasabah
        //   bisa berubah setelah dateStr (mis. sekarang Lunas tapi dulu Aktif)
        if (currentDate && threeMonthsAgo) {
          const totalPelunasan = n.totalPelunasan || 0;
          if (totalPelunasan > 0) {
            const tglAcuan = tglCair || n.tanggalPengajuan || n.tanggalDaftar || '';
            if (tglAcuan) {
              const acuanDate = parseDateStr(tglAcuan);
              if (acuanDate && acuanDate < currentDate && acuanDate >= threeMonthsAgo) {
                // H+1: hitung pembayaran SEBELUM dateStr (strictly before)
                let totalBayarSebelumTanggal = 0;
                if (n.pembayaran) {
                  for (const [payDate, payData] of Object.entries(n.pembayaran)) {
                    const pd = parseDateStr(payDate);
                    if (pd && pd < currentDate) {
                      totalBayarSebelumTanggal += payData.total || 0;
                    }
                  }
                }
                if (totalBayarSebelumTanggal < totalPelunasan) {
                  // H+1: MENUNGGU_PENCAIRAN hanya dikecualikan jika sudah berlaku sebelum dateStr
                  const tglStatusKhusus = n.tanggalStatusKhusus ? parseDateStr(n.tanggalStatusKhusus) : null;
                  const isMenungguSebelumTanggal = n.statusKhusus === 'MENUNGGU_PENCAIRAN'
                    && tglStatusKhusus && tglStatusKhusus < currentDate;
                  if (!isMenungguSebelumTanggal) {
                    targetKini += Math.floor((n.besarPinjaman || 0) * 3 / 100);
                  }
                }
              }
            }
          }
        }
      });

      dropBerjalan += dropKini;
      targetBerjalan += targetKini;
      stortingBerjalan += stortingKini;

      const persentase = targetKini > 0 ? Math.round(stortingKini / targetKini * 100) : 0;

      rows.push({
        tanggal: dateStr,
        dropKini,
        dropBerjalan,
        targetKini,
        targetBerjalan,
        stortingKini,
        stortingBerjalan,
        persentase,
        pbKini,
        l1Kini,
        cmKini,
        mbKini,
        mlKini,
      });
    }
    return rows;
  })();

  // Total storting (PB/L1/CM/MB/ML) dari bulan sebelumnya
  // Jika koreksi tersedia untuk suatu resort, gunakan nilai koreksi; else pakai computed
  const prevMonthSGTotals = (() => {
    if (!isStortingGlobalMode || !data?.nasabah || !stortingMonth) return null;
    const [smYear, smMonth] = stortingMonth.split('-').map(Number);
    const prevDate = new Date(smYear, smMonth - 2, 1);
    const prevYear = prevDate.getFullYear();
    const prevMonthIdx = prevDate.getMonth();
    const BULAN_INDO_ARR = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
    const BULAN_FULL = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'];
    const prevMonthLabel = `${BULAN_FULL[prevMonthIdx]} ${prevYear}`;
    const prevBulan = `${prevYear}-${String(prevMonthIdx + 1).padStart(2, '0')}`;

    const LIBUR_PREV = [[1,1],[16,1],[17,2],[19,3],[21,3],[3,4],[1,5],[14,5],[16,6],[17,8],[25,8],[25,12]];
    const isHKPrev = (d) => {
      if (d.getDay() === 0) return false;
      const dd = d.getDate(), mm = d.getMonth() + 1;
      return !LIBUR_PREV.some(([ld, lm]) => ld === dd && lm === mm);
    };

    // Hitung per-admin dari data digital
    const adminComputed = {};
    const endOfPrev = new Date(prevYear, prevMonthIdx + 1, 0);
    const cur = new Date(prevYear, prevMonthIdx, 1);
    while (cur <= endOfPrev) {
      if (isHKPrev(cur)) {
        const dateStr = `${String(cur.getDate()).padStart(2, '0')} ${BULAN_INDO_ARR[cur.getMonth()]} ${cur.getFullYear()}`;
        data.nasabah.forEach(n => {
          const pay = n.pembayaran?.[dateStr];
          if (pay) {
            const auid = n.adminUid || '_';
            if (!adminComputed[auid]) adminComputed[auid] = { pb:0, l1:0, cm:0, mb:0, ml:0 };
            const total = pay.total || 0;
            const kat = getKategoriNasabah(n);
            if (kat === 'PB') adminComputed[auid].pb += total;
            else if (kat === 'L1') adminComputed[auid].l1 += total;
            else if (kat === 'CM') adminComputed[auid].cm += total;
            else if (kat === 'MB') adminComputed[auid].mb += total;
            else adminComputed[auid].ml += total;
          }
        });
      }
      cur.setDate(cur.getDate() + 1);
    }

    // Gabungkan: koreksi override computed per-admin
    const targetUids = selectedAdmin
      ? [selectedAdmin.uid]
      : [...new Set([...Object.keys(adminComputed), ...Object.keys(koreksiSGMap)])];

    let pb = 0, l1 = 0, cm = 0, mb = 0, ml = 0;
    targetUids.forEach(auid => {
      const koreksi = koreksiSGMap[auid];
      const computed = adminComputed[auid] || { pb:0, l1:0, cm:0, mb:0, ml:0 };
      const vals = koreksi || computed;
      pb += vals.pb || 0;
      l1 += vals.l1 || 0;
      cm += vals.cm || 0;
      mb += vals.mb || 0;
      ml += vals.ml || 0;
    });

    const hasKoreksi = selectedAdmin
      ? !!koreksiSGMap[selectedAdmin.uid]
      : Object.keys(koreksiSGMap).length > 0;

    return { pb, l1, cm, mb, ml, label: prevMonthLabel, prevBulan, hasKoreksi };
  })();

  // PB Saldo Awal di baris terkoreksi (Storting Global):
  // = dropBerjalan terakhir × 120%. Tumbuh harian seiring drop kini.
  // Perhitungan murni dari stortingGlobalData (sudah ada di memory) — 0 RTDB read tambahan.
  const pbSaldoAwalDariDrop = (() => {
    if (!isStortingGlobalMode || stortingGlobalData.length === 0) return 0;
    const last = stortingGlobalData[stortingGlobalData.length - 1];
    const dropBerjalan = last?.dropBerjalan || 0;
    return Math.round((dropBerjalan * 120) / 100);
  })();

  const tabelDates = (() => {
    if (!isTabelMode || filtered.length === 0) return [];
    const BULAN_INDO_ARR = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

    // Hari ini WIB
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOffset);
    const today = new Date(wib.getFullYear(), wib.getMonth(), wib.getDate());

    // Mulai dari tanggal 1 bulan ini (hanya tampilkan bulan berjalan)
    const startOfMonth = new Date(wib.getFullYear(), wib.getMonth(), 1);

    // Generate hari kerja (Senin-Sabtu) dari tanggal 1 bulan ini sampai hari ini
    const allDates = [];
    const cur = new Date(startOfMonth);
    while (cur <= today) {
      if (cur.getDay() !== 0) { // Skip Minggu
        const dd = String(cur.getDate()).padStart(2, '0');
        const mmm = BULAN_INDO_ARR[cur.getMonth()];
        const yyyy = cur.getFullYear();
        allDates.push(`${dd} ${mmm} ${yyyy}`);
      }
      cur.setDate(cur.getDate() + 1);
    }
    return allDates; // Kronologis: tanggal 1 bulan ini di kiri → hari ini di kanan
  })();

  // Tanggal yang ditampilkan: tabelDates untuk mode tabel, visibleDates untuk mode semua
  const displayDates = isTabelMode ? tabelDates : visibleDates;
  const todayStr = dates[0] || '';

  // Stats — hitung dari data.nasabah, exclude ML (4+ bulan)
  const allNasabahList = (data?.nasabah || []).filter(n => getKategoriNasabah(n) !== 'ML');
  const stats = {
    totalNasabah: allNasabahList.length,
    totalSisaUtang: allNasabahList.reduce((s, n) => s + (n.sisaUtang || 0), 0),
    totalPinjaman: allNasabahList.reduce((s, n) => s + (n.besarPinjaman || 0), 0),
    bayarHariIni: data?.pembayaranHariIni || 0,
  };

  return (
    <div className="page-container">
      {/* Top Bar */}
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>
            </svg>
          </button>
          <div>
            <h1>Buku Pokok — {cabang.name}</h1>
            <p>{selectedAdmin ? selectedAdmin.name : 'Semua Resort'}</p>
          </div>
        </div>
        {onSelectBook && (
          <TopBarNav currentScreen="bukuPokok" onSelectBook={onSelectBook} showKasirMenus={showKasirMenus} />
        )}
        <div className="top-bar-right">
          {user && (
            <div className="user-badge">
              <span className="user-name">{user.name}</span>
              <span className="user-role">{user.role}</span>
            </div>
          )}
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
              <polyline points="16 17 21 12 16 7"/>
              <line x1="21" x2="9" y1="12" y2="12"/>
            </svg>
          </button>
        </div>
      </header>

      <main className="buku-content">
        {/* Stats */}
        <div className="stats-row">
          <div className="stat-card" style={{ borderLeftColor: '#0f6b54' }}>
            <div className="stat-icon" style={{ color: '#0f6b54', backgroundColor: '#0f6b5412' }}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
            </div>
            <div>
              <p className="stat-label">Nasabah</p>
              <p className="stat-value">{stats.totalNasabah}</p>
            </div>
          </div>
          <div className="stat-card" style={{ borderLeftColor: '#2d7dd2' }}>
            <div className="stat-icon" style={{ color: '#2d7dd2', backgroundColor: '#2d7dd212' }}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M21 12V7H5a2 2 0 0 1 0-4h14v4"/><path d="M3 5v14a2 2 0 0 0 2 2h16v-5"/><path d="M18 12a2 2 0 0 0 0 4h4v-4Z"/></svg>
            </div>
            <div>
              <p className="stat-label">Total Pinjaman</p>
              <p className="stat-value">{formatRpFull(stats.totalPinjaman)}</p>
            </div>
          </div>
          <div className="stat-card" style={{ borderLeftColor: '#e85d3a' }}>
            <div className="stat-icon" style={{ color: '#e85d3a', backgroundColor: '#e85d3a12' }}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="m22 7-8.5 8.5-5-5L2 17"/><path d="M16 7h6v6"/></svg>
            </div>
            <div>
              <p className="stat-label">Total Saldo</p>
              <p className="stat-value">{formatRpFull(stats.totalSisaUtang)}</p>
            </div>
          </div>
          <div className="stat-card" style={{ borderLeftColor: '#7c3aed' }}>
            <div className="stat-icon" style={{ color: '#7c3aed', backgroundColor: '#7c3aed12' }}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><rect width="18" height="18" x="3" y="4" rx="2" ry="2"/><line x1="16" x2="16" y1="2" y2="6"/><line x1="8" x2="8" y1="2" y2="6"/><line x1="3" x2="21" y1="10" y2="10"/></svg>
            </div>
            <div>
              <p className="stat-label">Bayar Hari Ini</p>
              <p className="stat-value">{formatRpFull(stats.bayarHariIni)}</p>
            </div>
          </div>
        </div>

        {/* Admin Chips */}
        <div className="admin-chips">
          <button
            onClick={() => onSelectAdmin(null)}
            className={`admin-chip ${selectedAdmin === null ? 'active' : ''}`}
          >
            Semua Resort
          </button>
          {admins.map((adm) => (
            <button
              key={adm.uid}
              onClick={() => onSelectAdmin(adm)}
              className={`admin-chip ${selectedAdmin?.uid === adm.uid ? 'active' : ''}`}
            >
              {adm.name.replace('Resort ', '')}
            </button>
          ))}
        </div>

        {/* Toolbar */}
        <div className="toolbar">
          <div className="search-box">
            <select
              value={tabelFilter}
              onChange={(e) => {
                const val = e.target.value;
                setTabelFilter(val);
                if (val === 'stortingGlobal' && !stortingMonth) {
                  // Auto-set ke bulan ini saat pertama kali pilih
                  const now = new Date();
                  const wibOff = 7 * 60 * 60 * 1000;
                  const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOff);
                  setStortingMonth(`${wib.getFullYear()}-${String(wib.getMonth() + 1).padStart(2, '0')}`);
                }
              }}
              style={{
                border: 'none',
                background: 'transparent',
                fontSize: 13,
                fontWeight: 700,
                color: tabelFilter === 'semua' ? 'var(--text-muted)' : 'var(--primary)',
                cursor: 'pointer',
                outline: 'none',
                paddingRight: 4,
                minWidth: 70,
                fontFamily: 'inherit',
              }}
            >
              <option value="semua">Semua</option>
              <option value="PB">PB</option>
              <option value="L1">L1</option>
              <option value="CM">CM</option>
              <option value="MB">MB</option>
              <option value="ML">ML</option>
              <option value="stortingGlobal">Storting Global</option>
            </select>
            <div style={{ width: 1, height: 20, background: 'var(--border)', flexShrink: 0 }} />
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Cari nama, NIK, atau No. Anggota..."
            />
            {search && (
              <button onClick={() => setSearch('')} className="btn-icon" style={{ width: 28, height: 28 }}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
              </button>
            )}
          </div>
          <div className="toolbar-right">
            <span className="result-count">{filtered.length} nasabah</span>
          </div>
        </div>

        {/* Loading */}
        {loading && (
          <div className="loading-container">
            <div className="loading-spinner" />
            <p className="loading-text">Memuat data Buku Pokok...</p>
          </div>
        )}

        {/* Error */}
        {error && <div className="error-msg" style={{ marginBottom: 16 }}>{error}</div>}

        {/* Table */}
        {!loading && !error && (
          <>
            {/* ==================== STORTING GLOBAL TABLE ==================== */}
            {isStortingGlobalMode ? (
              <>
              {/* Month Selector */}
              <div className="sg-month-selector">
                <button
                  className="sg-month-nav"
                  onClick={() => {
                    if (!stortingMonth) return;
                    const [y, m] = stortingMonth.split('-').map(Number);
                    const prev = new Date(y, m - 2, 1);
                    // Batas: 3 bulan ke belakang
                    const now = new Date();
                    const wibOff = 7 * 60 * 60 * 1000;
                    const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOff);
                    const minDate = new Date(wib.getFullYear(), wib.getMonth() - 3, 1);
                    if (prev >= minDate) {
                      setStortingMonth(`${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`);
                    }
                  }}
                  title="Bulan sebelumnya"
                >
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m15 18-6-6 6-6"/></svg>
                </button>
                <span className="sg-month-label" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 }}>
                  <span>
                    {(() => {
                      if (!stortingMonth) return '';
                      const BULAN_FULL = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'];
                      const [y, m] = stortingMonth.split('-').map(Number);
                      return `${BULAN_FULL[m - 1]} ${y}`;
                    })()}
                  </span>
                  {prevMonthSGTotals && (() => {
                    // Total saldo awal koperasi: HANYA L1 + CM + MB + ML (kolom kotak 2).
                    // PB dikeluarkan dari total karena PB adalah pinjaman bulan berjalan
                    // (saldo awal-nya berasal dari drop, bukan saldo akhir bulan lalu).
                    const total = prevMonthSGTotals.l1 + prevMonthSGTotals.cm + prevMonthSGTotals.mb + prevMonthSGTotals.ml;
                    return total > 0 ? (
                      <span style={{ fontSize: 14, color: '#7c3aed', fontWeight: 700, fontFamily: "'DM Mono', monospace" }}>
                        {formatRp(total)}
                      </span>
                    ) : null;
                  })()}
                </span>
                <button
                  className="sg-month-nav"
                  onClick={() => {
                    if (!stortingMonth) return;
                    const [y, m] = stortingMonth.split('-').map(Number);
                    const next = new Date(y, m, 1);
                    const now = new Date();
                    const wibOff = 7 * 60 * 60 * 1000;
                    const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOff);
                    const maxDate = new Date(wib.getFullYear(), wib.getMonth(), 1);
                    if (next <= maxDate) {
                      setStortingMonth(`${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}`);
                    }
                  }}
                  title="Bulan berikutnya"
                >
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m9 18 6-6-6-6"/></svg>
                </button>
              </div>
              <div className="table-wrapper">
                <table className="buku-table storting-global-table">
                  <thead>
                    <tr>
                      <th rowSpan={2} className="sg-th-tanggal" style={{ position: 'sticky', left: 0, zIndex: 12, minWidth: 60, textAlign: 'center', verticalAlign: 'middle' }}>Tgl</th>
                      <th colSpan={2} className="sg-th-group sg-th-drop" style={{ textAlign: 'center', borderBottom: 'none', color: '#2d7dd2' }}>DROP</th>
                      <th colSpan={2} className="sg-th-group sg-th-target" style={{ textAlign: 'center', borderBottom: 'none', color: '#e85d3a' }}>TARGET</th>
                      <th colSpan={2} className="sg-th-group sg-th-storting" style={{ textAlign: 'center', borderBottom: 'none', color: '#0f6b54' }}>STORTING</th>
                      <th rowSpan={2} className="sg-th-persen" style={{ textAlign: 'center', verticalAlign: 'middle', minWidth: 55 }}>%</th>
                      <th rowSpan={2} className="sg-th-kat sg-th-kat-pb" style={{ textAlign: 'center', verticalAlign: 'middle', minWidth: 80 }}>PB</th>
                      <th rowSpan={2} className="sg-th-kat sg-th-kat-l1" style={{ textAlign: 'center', verticalAlign: 'middle', minWidth: 80 }}>L1</th>
                      <th rowSpan={2} className="sg-th-kat sg-th-kat-cm" style={{ textAlign: 'center', verticalAlign: 'middle', minWidth: 80 }}>CM</th>
                      <th rowSpan={2} className="sg-th-kat sg-th-kat-mb" style={{ textAlign: 'center', verticalAlign: 'middle', minWidth: 80 }}>MB</th>
                      <th rowSpan={2} className="sg-th-kat sg-th-kat-ml" style={{ textAlign: 'center', verticalAlign: 'middle', minWidth: 80 }}>ML</th>
                    </tr>
                    <tr>
                      <th className="sg-th-sub" style={{ textAlign: 'center', color: '#e85d3a', minWidth: 120, fontSize: 10 }}>Berjalan</th>
                      <th className="sg-th-sub" style={{ textAlign: 'center', minWidth: 120, fontSize: 10 }}>Kini</th>
                      <th className="sg-th-sub" style={{ textAlign: 'center', color: '#e85d3a', minWidth: 120, fontSize: 10 }}>Berjalan</th>
                      <th className="sg-th-sub" style={{ textAlign: 'center', minWidth: 120, fontSize: 10 }}>Kini</th>
                      <th className="sg-th-sub" style={{ textAlign: 'center', color: '#e85d3a', minWidth: 120, fontSize: 10 }}>Berjalan</th>
                      <th className="sg-th-sub" style={{ textAlign: 'center', minWidth: 120, fontSize: 10 }}>Kini</th>
                    </tr>
                  </thead>
                  <tbody>
                    {/* Baris total storting bulan sebelumnya */}
                    {prevMonthSGTotals && (
                      <tr style={{ background: '#f5f3ff', borderBottom: '2px solid #c4b5fd' }}>
                        <td style={{ position: 'sticky', left: 0, zIndex: 5, background: '#f5f3ff' }} />
                        <td colSpan={7} style={{ textAlign: 'right', paddingRight: 8 }}>
                          {/* Tombol koreksi: hanya pengawas + resort tertentu */}
                          {user?.role === 'pengawas' && selectedAdmin && (
                            <button
                              onClick={() => {
                                const existing = koreksiSGMap[selectedAdmin.uid];
                                setKoreksiInput({
                                  l1: existing ? String(existing.l1) : '',
                                  cm: existing ? String(existing.cm) : '',
                                  mb: existing ? String(existing.mb) : '',
                                  ml: existing ? String(existing.ml) : '',
                                });
                                setKoreksiError('');
                                setShowKoreksiModal(true);
                              }}
                              title={prevMonthSGTotals.hasKoreksi ? 'Edit koreksi bulan lalu' : 'Input koreksi bulan lalu'}
                              style={{ background: 'none', border: '1px solid #c4b5fd', borderRadius: 6, padding: '2px 8px', cursor: 'pointer', fontSize: 11, color: '#7c3aed' }}
                            >
                              {prevMonthSGTotals.hasKoreksi ? '✏️ Edit' : '✏️ Koreksi'}
                            </button>
                          )}
                          {prevMonthSGTotals.hasKoreksi && (
                            <span style={{ fontSize: 10, color: '#9ca3af', marginLeft: 6, fontStyle: 'italic' }}>terkoreksi</span>
                          )}
                        </td>
                        <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12, fontWeight: 700, color: '#7c3aed' }}>
                          {pbSaldoAwalDariDrop > 0 ? formatRp(pbSaldoAwalDariDrop) : '-'}
                        </td>
                        <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12, fontWeight: 700, color: '#7c3aed' }}>
                          {prevMonthSGTotals.l1 > 0 ? formatRp(prevMonthSGTotals.l1) : '-'}
                        </td>
                        <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12, fontWeight: 700, color: '#7c3aed' }}>
                          {prevMonthSGTotals.cm > 0 ? formatRp(prevMonthSGTotals.cm) : '-'}
                        </td>
                        <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12, fontWeight: 700, color: '#7c3aed' }}>
                          {prevMonthSGTotals.mb > 0 ? formatRp(prevMonthSGTotals.mb) : '-'}
                        </td>
                        <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12, fontWeight: 700, color: '#7c3aed' }}>
                          {prevMonthSGTotals.ml > 0 ? formatRp(prevMonthSGTotals.ml) : '-'}
                        </td>
                      </tr>
                    )}
                    {stortingGlobalData.length === 0 ? (
                      <tr>
                        <td colSpan={13} className="empty-cell">Tidak ada data untuk bulan ini</td>
                      </tr>
                    ) : (
                      stortingGlobalData.map((row, idx) => {
                        const dayParts = row.tanggal.split(' ');
                        const isToday = row.tanggal === (dates[0] || '');
                        return (
                          <tr key={row.tanggal} className={isToday ? 'sg-row-today' : ''}>
                            <td style={{ position: 'sticky', left: 0, zIndex: 5, background: isToday ? '#edf7f2' : (idx % 2 === 0 ? '#fff' : '#fafbfc'), textAlign: 'center', fontWeight: 700, fontSize: 13 }}>
                              <span style={{ fontSize: 14 }}>{dayParts[0]}</span>
                              <span style={{ fontSize: 9, color: 'var(--text-muted)', display: 'block' }}>{dayParts[1]}</span>
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", color: '#e85d3a', fontWeight: 600 }}>
                              {row.dropBerjalan > 0 ? formatRp(row.dropBerjalan) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace" }}>
                              {row.dropKini > 0 ? formatRp(row.dropKini) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", color: '#e85d3a', fontWeight: 600 }}>
                              {row.targetBerjalan > 0 ? formatRp(row.targetBerjalan) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace" }}>
                              {row.targetKini > 0 ? formatRp(row.targetKini) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", color: '#e85d3a', fontWeight: 600 }}>
                              {row.stortingBerjalan > 0 ? formatRp(row.stortingBerjalan) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace" }}>
                              {row.stortingKini > 0 ? formatRp(row.stortingKini) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontWeight: 700, fontSize: 12 }}>
                              <span className={`sg-persen-value ${row.persentase >= 100 ? 'sg-persen-ok' : row.persentase >= 70 ? 'sg-persen-warn' : 'sg-persen-low'}`}>
                                {row.persentase > 0 ? `${row.persentase}%` : '-'}
                              </span>
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                              {row.pbKini > 0 ? formatRp(row.pbKini) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                              {row.l1Kini > 0 ? formatRp(row.l1Kini) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                              {row.cmKini > 0 ? formatRp(row.cmKini) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                              {row.mbKini > 0 ? formatRp(row.mbKini) : '-'}
                            </td>
                            <td style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                              {row.mlKini > 0 ? formatRp(row.mlKini) : '-'}
                            </td>
                          </tr>
                        );
                      })
                    )}
                  </tbody>
                </table>
              </div>
              </>
            ) : (
            <div className="table-wrapper">
              <table className="buku-table" ref={tableRef}>
                <thead>
                  <tr>
                    <th style={{ minWidth: 36, textAlign: 'center' }}>No</th>
                    <th style={{ minWidth: 90 }}>Tgl Cair</th>
                    <th style={{ minWidth: 70, textAlign: 'center' }}>No. Ang</th>
                    <th className="sticky-col" style={{ left: 0, minWidth: 40, textAlign: 'center' }}>Ke</th>
                    <th className="sticky-col" style={{ left: stickyOffsets.nama, minWidth: 150 }}>Nama KTP</th>
                    <th className="sticky-col" style={{ left: stickyOffsets.panggilan, minWidth: 100 }}>Panggilan</th>
                    <th style={{ minWidth: 140 }}>NIK</th>
                    <th style={{ minWidth: 110, textAlign: 'right' }}>Pinjaman</th>
                    {isTabelMode && (
                      <th style={{ minWidth: 110, textAlign: 'right' }}>Tabungan</th>
                    )}
                    <th style={{ minWidth: 110, textAlign: 'right', borderRight: '2px solid #7c3aed' }}>{isTabelMode ? 'Saldo Awal' : 'Saldo Akhir'}</th>
                    {displayDates.map((d) => (
                      <th
                        key={d}
                        className={`date-col ${d === todayStr ? 'today' : ''}`}
                      >
                        <span className="date-day">{d.split(' ')[0]}</span>
                        <span className="date-month">/{d.split(' ')[1]}</span>
                      </th>
                    ))}
                    {isTabelMode && (
                      <th style={{ minWidth: 100, textAlign: 'right', background: '#f0f7f4', color: '#0f6b54', borderLeft: '2px solid #0f6b54' }}>Total</th>
                    )}
                    {isTabelMode && (
                      <th style={{ minWidth: 120, textAlign: 'right', background: '#fff7f0', color: '#c05621', borderLeft: '2px solid #c05621' }}>Saldo Akhir</th>
                    )}
                  </tr>
                </thead>
                <tbody>
                  {filtered.length === 0 ? (
                    <tr>
                      <td colSpan={9 + displayDates.length + (isTabelMode ? 3 : 0)} className="empty-cell">
                        Tidak ada data nasabah
                      </td>
                    </tr>
                  ) : (
                    filtered.map((n, idx) => {
                      const rowTotal = isTabelMode
                        ? displayDates.reduce((s, d) => s + (n.pembayaran?.[d]?.total || 0), 0)
                        : 0;
                      // Saldo Awal rules:
                      // - Tabel PB: besar pinjaman × 120% (= totalPelunasan), konsisten
                      //   untuk nasabah baru maupun lanjut pinjaman.
                      // - Tabel L1/CM/MB/ML: sisa utang per awal bulan berjalan
                      //   (sisaUtang + rowTotal).
                      const saldoAwalTabel = tabelFilter === 'PB'
                        ? (n.totalPelunasan || 0)
                        : (n.sisaUtang + rowTotal);
                      // Nama bewarna merah untuk: Sisa Tabungan, Nasabah Lunas, ML,
                      // atau entri historis (pinjaman lama yang sudah diarsipkan karena top-up).
                      const isSisaTabungan = n.statusKhusus === 'MENUNGGU_PENCAIRAN';
                      const isLunasCicilan = n.sisaUtang <= 0 && n.totalPelunasan > 0;
                      const isML = getKategoriNasabah(n) === 'ML';
                      const namaColor = (n.isHistorical || isSisaTabungan || isLunasCicilan || isML) ? '#e53e3e' : undefined;
                      // Coretan garis horizontal merah untuk baris historis (pinjaman lama),
                      // diaplikasikan dari kolom Nama hingga kolom Saldo Awal.
                      const strikeStyle = n.isHistorical
                        ? { textDecoration: 'line-through', textDecorationColor: '#e53e3e', textDecorationThickness: 2 }
                        : null;
                      return (
                        <tr key={n.id} onClick={() => setShowDetail(n)}>
                          <td style={{ textAlign: 'center', color: '#8a9ba8', fontSize: 12 }}>{idx + 1}</td>
                          <td style={{ fontSize: 11, whiteSpace: 'nowrap' }}>
                            {n.tanggalPencairan || n.tanggalPengajuan || n.tanggalDaftar || '-'}
                          </td>
                          <td style={{ textAlign: 'center', fontWeight: 600 }}>{n.nomorAnggota}</td>
                          <td className="sticky-col" style={{ left: 0, textAlign: 'center' }}>
                            <span className="pinjaman-ke-badge">{n.pinjamanKe}</span>
                          </td>
                          <td className="sticky-col" style={{ left: stickyOffsets.nama, fontWeight: 600, color: namaColor, ...strikeStyle }}>
                            {n.namaKtp}
                            {!selectedAdmin && <div className="admin-tag">{n.adminName?.replace('Resort ', '')}</div>}
                          </td>
                          <td className="sticky-col" style={{ left: stickyOffsets.panggilan, color: namaColor || '#5a6b7c', ...strikeStyle }}>{n.namaPanggilan}</td>
                          <td className="nik-cell" style={strikeStyle || undefined}>{n.nik}</td>
                          <td style={{ textAlign: 'right', fontFamily: "'DM Mono', monospace", ...strikeStyle }}>{formatRp(n.besarPinjaman)}</td>
                          {isTabelMode && (
                            <td style={{ textAlign: 'right', fontFamily: "'DM Mono', monospace", color: '#2d7dd2', ...strikeStyle }}>{formatRp(n.simpanan || 0)}</td>
                          )}
                          <td style={{ textAlign: 'right', borderRight: '2px solid #7c3aed', ...strikeStyle }}>
                            {n.status === 'Lunas' ? (
                              <span className="lunas-badge">LUNAS</span>
                            ) : isTabelMode ? (
                              <span style={{ fontFamily: "'DM Mono', monospace" }}>{formatRp(saldoAwalTabel)}</span>
                            ) : (
                              <span className={`saldo-amount ${n.sisaUtang > 0 ? 'saldo-utang' : 'saldo-lunas'}`}>
                                {formatRp(n.sisaUtang)}
                              </span>
                            )}
                          </td>
                          {displayDates.map((d) => {
                            const pay = n.pembayaran?.[d];
                            return (
                              <td key={d} className={`date-col ${d === todayStr ? 'today' : ''}`}>
                                {pay ? (
                                  <span className="pay-amount">{formatRp(pay.total)}</span>
                                ) : (
                                  <span className="pay-empty">-</span>
                                )}
                              </td>
                            );
                          })}
                          {isTabelMode && (
                            <td style={{ textAlign: 'right', fontWeight: 700, fontFamily: "'DM Mono', monospace", background: '#f0f7f4', color: '#0f6b54', borderLeft: '2px solid #0f6b54' }}>
                              {rowTotal > 0 ? formatRp(rowTotal) : '-'}
                            </td>
                          )}
                          {isTabelMode && (
                            <td style={{ textAlign: 'right', fontWeight: 700, fontFamily: "'DM Mono', monospace", background: '#fff7f0', color: '#c05621', borderLeft: '2px solid #c05621' }}>
                              {formatRp(n.sisaUtang)}
                            </td>
                          )}
                        </tr>
                      );
                    })
                  )}
                </tbody>
                {filtered.length > 0 && (
                  <tfoot>
                    <tr>
                      <td colSpan={7} style={{ fontWeight: 700 }}>TOTAL</td>
                      <td style={{ textAlign: 'right', fontWeight: 700, fontFamily: "'DM Mono', monospace" }}>
                        {formatRp(filtered.reduce((s, n) => s + (n.besarPinjaman || 0), 0))}
                      </td>
                      {isTabelMode && (
                        <td style={{ textAlign: 'right', fontWeight: 700, fontFamily: "'DM Mono', monospace", color: '#2d7dd2' }}>
                          {formatRp(filtered.reduce((s, n) => s + (n.simpanan || 0), 0))}
                        </td>
                      )}
                      <td style={{ textAlign: 'right', fontWeight: 700, fontFamily: "'DM Mono', monospace", borderRight: '2px solid #7c3aed' }}>
                        {isTabelMode
                          ? (tabelFilter === 'PB'
                              ? formatRp(filtered.reduce((s, n) => s + (n.totalPelunasan || 0), 0))
                              : formatRp(filtered.reduce((s, n) => {
                                  const rt = displayDates.reduce((sd, d) => sd + (n.pembayaran?.[d]?.total || 0), 0);
                                  return s + (n.sisaUtang || 0) + rt;
                                }, 0)))
                          : formatRp(filtered.reduce((s, n) => s + (n.sisaUtang || 0), 0))
                        }
                      </td>
                      {displayDates.map((d) => {
                        const dayTotal = filtered.reduce((s, n) => s + (n.pembayaran?.[d]?.total || 0), 0);
                        return (
                          <td key={d} style={{ textAlign: 'center', fontFamily: "'DM Mono', monospace", fontWeight: 600 }}>
                            {dayTotal > 0 ? formatRp(dayTotal) : '-'}
                          </td>
                        );
                      })}
                      {isTabelMode && (
                        <td style={{ textAlign: 'right', fontWeight: 700, fontFamily: "'DM Mono', monospace", background: '#f0f7f4', color: '#0f6b54', borderLeft: '2px solid #0f6b54' }}>
                          {formatRp(filtered.reduce((s, n) => s + displayDates.reduce((sd, d) => sd + (n.pembayaran?.[d]?.total || 0), 0), 0))}
                        </td>
                      )}
                      {isTabelMode && (
                        <td style={{ textAlign: 'right', fontWeight: 700, fontFamily: "'DM Mono', monospace", background: '#fff7f0', color: '#c05621', borderLeft: '2px solid #c05621' }}>
                          {formatRp(filtered.reduce((s, n) => s + (n.sisaUtang || 0), 0))}
                        </td>
                      )}
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
            )}

            {!isTabelMode && !isStortingGlobalMode && visibleDateCount < dates.length && (
              <div className="load-more-row">
                <button
                  onClick={() => setVisibleDateCount((v) => Math.min(v + 7, dates.length))}
                  className="btn-load-more"
                >
                  Tampilkan hari sebelumnya →
                </button>
              </div>
            )}
          </>
        )}
      </main>

      {/* Detail Modal */}
      {showDetail && (
        <DetailModal nasabah={showDetail} onClose={() => setShowDetail(null)} />
      )}

      {/* ===== MODAL KOREKSI STORTING BULAN LALU ===== */}
      {showKoreksiModal && selectedAdmin && prevMonthSGTotals && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999, padding: 16 }}>
          <div style={{ background: '#fff', borderRadius: 16, padding: 24, width: '100%', maxWidth: 420, boxShadow: '0 20px 60px rgba(0,0,0,0.3)' }}>
            <h3 style={{ margin: '0 0 4px', fontSize: 16, fontWeight: 700, color: '#1f2937' }}>Koreksi Storting Bulan Lalu</h3>
            <p style={{ margin: '0 0 16px', fontSize: 13, color: '#6b7280' }}>
              {selectedAdmin.name} — {prevMonthSGTotals.label}
            </p>
            <p style={{ margin: '0 0 16px', fontSize: 12, color: '#9ca3af', lineHeight: 1.5 }}>
              Masukkan total storting dari buku manual. Nilai ini akan menggantikan data digital untuk bulan tersebut.
            </p>
            {[['L1', 'l1'], ['CM', 'cm'], ['MB', 'mb'], ['ML', 'ml']].map(([label, key]) => (
              <div key={key} style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 4 }}>{label}</label>
                <input
                  type="number"
                  min="0"
                  value={koreksiInput[key]}
                  onChange={(e) => setKoreksiInput(prev => ({ ...prev, [key]: e.target.value }))}
                  placeholder="0"
                  style={{ width: '100%', padding: '8px 12px', borderRadius: 8, border: '1px solid #d1d5db', fontSize: 13, boxSizing: 'border-box' }}
                />
              </div>
            ))}
            {koreksiError && <p style={{ color: '#ef4444', fontSize: 12, margin: '0 0 12px' }}>{koreksiError}</p>}
            <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              <button
                onClick={() => setShowKoreksiModal(false)}
                style={{ flex: 1, padding: '10px', borderRadius: 8, border: '1px solid #d1d5db', background: '#fff', fontSize: 13, cursor: 'pointer' }}
              >
                Batal
              </button>
              <button
                disabled={savingKoreksi}
                onClick={async () => {
                  setSavingKoreksi(true);
                  setKoreksiError('');
                  try {
                    const result = await setKoreksiStorting({
                      cabangId: cabang.id,
                      adminUid: selectedAdmin.uid,
                      bulan: prevMonthSGTotals.prevBulan,
                      l1: parseInt(koreksiInput.l1) || 0,
                      cm: parseInt(koreksiInput.cm) || 0,
                      mb: parseInt(koreksiInput.mb) || 0,
                      ml: parseInt(koreksiInput.ml) || 0,
                    });
                    if (result.success) {
                      setKoreksiSGMap(prev => ({
                        ...prev,
                        [selectedAdmin.uid]: {
                          l1: parseInt(koreksiInput.l1) || 0,
                          cm: parseInt(koreksiInput.cm) || 0,
                          mb: parseInt(koreksiInput.mb) || 0,
                          ml: parseInt(koreksiInput.ml) || 0,
                        },
                      }));
                      setShowKoreksiModal(false);
                    } else {
                      setKoreksiError('Gagal menyimpan. Coba lagi.');
                    }
                  } catch (e) {
                    setKoreksiError(e.message || 'Gagal menyimpan.');
                  } finally {
                    setSavingKoreksi(false);
                  }
                }}
                style={{ flex: 1, padding: '10px', borderRadius: 8, border: 'none', background: savingKoreksi ? '#a78bfa' : '#7c3aed', color: '#fff', fontSize: 13, fontWeight: 600, cursor: savingKoreksi ? 'not-allowed' : 'pointer' }}
              >
                {savingKoreksi ? 'Menyimpan...' : 'Simpan'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}


// ============================================================
// DETAIL MODAL COMPONENT
// ============================================================
function DetailModal({ nasabah, onClose }) {
  const [zoomImage, setZoomImage] = useState(null);

  const paidDates = Object.entries(nasabah.pembayaran || {})
    .filter(([_, v]) => v.total > 0)
    .sort((a, b) => {
      const [dA, mA, yA] = a[0].split('-').map(Number);
      const [dB, mB, yB] = b[0].split('-').map(Number);
      return new Date(yB, mB - 1, dB) - new Date(yA, mA - 1, dA);
    });

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div>
            <h3 className="modal-title">{nasabah.namaKtp}</h3>
            <p className="modal-subtitle">{nasabah.namaPanggilan} — {nasabah.adminName}</p>
          </div>
          <button onClick={onClose} className="modal-close">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
          </button>
        </div>

        <div className="modal-body">
          {/* Foto KTP & Foto Nasabah */}
          {(nasabah.fotoKtpUrl || nasabah.fotoKtpSuamiUrl || nasabah.fotoKtpIstriUrl || nasabah.fotoNasabahUrl) && (
            <div className="detail-photos">
              {nasabah.fotoNasabahUrl && (
                <div className="detail-photo-card" onClick={() => setZoomImage(nasabah.fotoNasabahUrl)}>
                  <img src={nasabah.fotoNasabahUrl} alt="Foto Nasabah" loading="lazy" />
                  <span className="detail-photo-label">Foto Nasabah</span>
                </div>
              )}
              {nasabah.fotoKtpUrl && (
                <div className="detail-photo-card" onClick={() => setZoomImage(nasabah.fotoKtpUrl)}>
                  <img src={nasabah.fotoKtpUrl} alt="Foto KTP" loading="lazy" />
                  <span className="detail-photo-label">Foto KTP</span>
                </div>
              )}
              {nasabah.fotoKtpSuamiUrl && (
                <div className="detail-photo-card" onClick={() => setZoomImage(nasabah.fotoKtpSuamiUrl)}>
                  <img src={nasabah.fotoKtpSuamiUrl} alt="Foto KTP Suami" loading="lazy" />
                  <span className="detail-photo-label">KTP Suami</span>
                </div>
              )}
              {nasabah.fotoKtpIstriUrl && (
                <div className="detail-photo-card" onClick={() => setZoomImage(nasabah.fotoKtpIstriUrl)}>
                  <img src={nasabah.fotoKtpIstriUrl} alt="Foto KTP Istri" loading="lazy" />
                  <span className="detail-photo-label">KTP Istri</span>
                </div>
              )}
            </div>
          )}

          <div className="detail-grid">
            <div className="detail-item">
              <span className="detail-label">NIK</span>
              <span className="detail-value">{nasabah.nik}</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">No. Anggota</span>
              <span className="detail-value">{nasabah.nomorAnggota}</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Pinjaman Ke</span>
              <span className="detail-value">{nasabah.pinjamanKe}</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Besar Pinjaman</span>
              <span className="detail-value">{formatRpFull(nasabah.besarPinjaman)}</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Total Pelunasan</span>
              <span className="detail-value">{formatRpFull(nasabah.totalPelunasan)}</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Tenor</span>
              <span className="detail-value">{nasabah.tenor} hari</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Total Dibayar</span>
              <span className="detail-value" style={{ color: '#0f8a5f', fontWeight: 700 }}>
                {formatRpFull(nasabah.totalDibayar)}
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Sisa Utang</span>
              <span className="detail-value" style={{ color: nasabah.sisaUtang > 0 ? '#cf4a3a' : '#0f8a5f', fontWeight: 700 }}>
                {nasabah.status === 'Lunas' ? 'LUNAS' : formatRpFull(nasabah.sisaUtang)}
              </span>
            </div>
          </div>

          <div>
            <h4 className="pay-history-title">Riwayat Pembayaran</h4>
            {paidDates.length === 0 ? (
              <p style={{ color: '#8a9ba8', fontSize: 13, fontStyle: 'italic' }}>Belum ada pembayaran</p>
            ) : (
              <div className="pay-history-list">
                {paidDates.map(([tgl, data]) => (
                  <div key={tgl} className="pay-history-item">
                    <span className="pay-history-date">{tgl}</span>
                    <span className="pay-history-amount">{formatRpFull(data.total)}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Zoom Lightbox */}
      {zoomImage && (
        <div className="zoom-overlay" onClick={(e) => { e.stopPropagation(); setZoomImage(null); }}>
          <button className="zoom-close" onClick={(e) => { e.stopPropagation(); setZoomImage(null); }}>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
          </button>
          <img src={zoomImage} alt="Foto diperbesar" className="zoom-image" onClick={(e) => e.stopPropagation()} />
        </div>
      )}
    </div>
  );
}


// ============================================================
// JURNAL TRANSAKSI SCREEN
// ============================================================
function JurnalTransaksiScreen({ user, cabang, onBack, onLogout, onSelectBook, showKasirMenus }) {
  const [entries, setEntries] = useState([]);
  const [ringkasan, setRingkasan] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [bulan, setBulan] = useState(() => {
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wibDate = new Date(now.getTime() + wibOffset);
    const y = wibDate.getUTCFullYear();
    const m = (wibDate.getUTCMonth() + 1).toString().padStart(2, '0');
    return `${y}-${m}`;
  });
  const [tipeFilter, setTipeFilter] = useState('');
  const [search, setSearch] = useState('');
  const fetchData = async () => {
    setLoading(true);
    setError('');
    try {
      const result = await getJurnalTransaksi({
        cabangId: cabang.id,
        bulan,
        tipe: tipeFilter || undefined,
      });
      if (result.success) {
        setEntries(result.data.entries || []);
        setRingkasan(result.data.ringkasan || null);
      } else {
        setError(result.error || 'Gagal memuat data');
      }
    } catch (err) {
      setError(err.message || 'Gagal memuat data');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [cabang.id, bulan, tipeFilter]);

  const TIPE_LABELS = {
    pembayaran_cicilan: 'Cicilan',
    tambah_bayar: 'Tambah Bayar',
    pencairan_pinjaman: 'Pencairan',
    pelunasan_sisa_utang: 'Pelunasan Sisa Utang',
    pelunasan_tabungan: 'Pelunasan via Tabungan',
    lunas: 'Lunas',
  };

  const TIPE_COLORS = {
    pembayaran_cicilan: '#0f8a5f',
    tambah_bayar: '#2563eb',
    pencairan_pinjaman: '#d97706',
    pelunasan_sisa_utang: '#7c3aed',
    pelunasan_tabungan: '#9333ea',
    lunas: '#059669',
  };

  const filteredEntries = search
    ? entries.filter(e =>
        (e.namaPelanggan || '').toLowerCase().includes(search.toLowerCase()) ||
        (e.namaKtp || '').toLowerCase().includes(search.toLowerCase()) ||
        (e.adminName || '').toLowerCase().includes(search.toLowerCase())
      )
    : entries;

  // Format bulan untuk display
  const BULAN_NAMES = ['', 'Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'];
  const bulanParts = bulan.split('-');
  const bulanDisplay = `${BULAN_NAMES[parseInt(bulanParts[1])]} ${bulanParts[0]}`;

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>
            </svg>
          </button>
          <div>
            <h1>Jurnal Transaksi</h1>
            <p>{cabang.name}</p>
          </div>
        </div>
        {onSelectBook && (
          <TopBarNav currentScreen="jurnalTransaksi" onSelectBook={onSelectBook} showKasirMenus={showKasirMenus} />
        )}
        <div className="top-bar-right">
          {user && (
            <div className="user-badge">
              <span className="user-name">{user.name}</span>
              <span className="user-role">{user.role}</span>
            </div>
          )}
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
              <polyline points="16 17 21 12 16 7"/>
              <line x1="21" x2="9" y1="12" y2="12"/>
            </svg>
          </button>
        </div>
      </header>

      <main className="dash-content fade-in" style={{ padding: '16px' }}>
        {/* Controls */}
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', marginBottom: '16px', alignItems: 'center' }}>
          <input
            type="month"
            value={bulan}
            min={(() => {
              const now = new Date();
              const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + 7 * 60 * 60 * 1000);
              const d = new Date(wib.getUTCFullYear(), wib.getUTCMonth() - 3, 1);
              return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
            })()}
            max={(() => {
              const now = new Date();
              const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + 7 * 60 * 60 * 1000);
              return `${wib.getUTCFullYear()}-${String(wib.getUTCMonth() + 1).padStart(2, '0')}`;
            })()}
            onChange={(e) => setBulan(e.target.value)}
            style={{
              padding: '8px 12px', borderRadius: '8px', border: '1px solid #d1d5db',
              fontSize: '14px', background: '#fff', color: '#1f2937'
            }}
          />
          <select
            value={tipeFilter}
            onChange={(e) => setTipeFilter(e.target.value)}
            style={{
              padding: '8px 12px', borderRadius: '8px', border: '1px solid #d1d5db',
              fontSize: '14px', background: '#fff', color: '#1f2937'
            }}
          >
            <option value="">Semua Tipe</option>
            <option value="pembayaran_cicilan">Cicilan</option>
            <option value="tambah_bayar">Tambah Bayar</option>
            <option value="pencairan_pinjaman">Pencairan</option>
            <option value="pelunasan_sisa_utang">Pelunasan Sisa Utang</option>
            <option value="pelunasan_tabungan">Pelunasan via Tabungan</option>
            <option value="lunas">Lunas</option>
          </select>
          <input
            type="text"
            placeholder="Cari nasabah/admin..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            style={{
              padding: '8px 12px', borderRadius: '8px', border: '1px solid #d1d5db',
              fontSize: '14px', flex: 1, minWidth: '150px'
            }}
          />
        </div>

        {/* Ringkasan */}
        {ringkasan && !loading && (
          <div style={{
            display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
            gap: '10px', marginBottom: '16px'
          }}>
            <div style={{ background: '#ecfdf5', borderRadius: '10px', padding: '12px', textAlign: 'center' }}>
              <div style={{ fontSize: '11px', color: '#065f46', fontWeight: 600 }}>Total Cicilan</div>
              <div style={{ fontSize: '16px', color: '#059669', fontWeight: 700 }}>{formatRpFull(ringkasan.totalPembayaranCicilan)}</div>
            </div>
            <div style={{ background: '#eff6ff', borderRadius: '10px', padding: '12px', textAlign: 'center' }}>
              <div style={{ fontSize: '11px', color: '#1e40af', fontWeight: 600 }}>Tambah Bayar</div>
              <div style={{ fontSize: '16px', color: '#2563eb', fontWeight: 700 }}>{formatRpFull(ringkasan.totalTambahBayar)}</div>
            </div>
            <div style={{ background: '#fffbeb', borderRadius: '10px', padding: '12px', textAlign: 'center' }}>
              <div style={{ fontSize: '11px', color: '#92400e', fontWeight: 600 }}>Pencairan</div>
              <div style={{ fontSize: '16px', color: '#d97706', fontWeight: 700 }}>{formatRpFull(ringkasan.totalPencairan)}</div>
            </div>
            {ringkasan.totalPelunasanSisaUtang > 0 && (
              <div style={{ background: '#fef3c7', borderRadius: '10px', padding: '12px', textAlign: 'center' }}>
                <div style={{ fontSize: '11px', color: '#92400e', fontWeight: 600 }}>Pelunasan Sisa Utang</div>
                <div style={{ fontSize: '16px', color: '#b45309', fontWeight: 700 }}>{formatRpFull(ringkasan.totalPelunasanSisaUtang)}</div>
              </div>
            )}
            <div style={{ background: '#f0fdf4', borderRadius: '10px', padding: '12px', textAlign: 'center' }}>
              <div style={{ fontSize: '11px', color: '#065f46', fontWeight: 600 }}>Nasabah Lunas</div>
              <div style={{ fontSize: '16px', color: '#059669', fontWeight: 700 }}>{ringkasan.jumlahNasabahLunas}</div>
            </div>
          </div>
        )}

        {/* Header info */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
          <div style={{ fontSize: '14px', color: '#6b7280' }}>
            {bulanDisplay} &mdash; {filteredEntries.length} transaksi
          </div>
        </div>

        {/* Content */}
        {loading ? (
          <div style={{ textAlign: 'center', padding: '40px', color: '#9ca3af' }}>
            <div className="loading-spinner" />
            <p>Memuat jurnal...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: '40px', color: '#ef4444' }}>{error}</div>
        ) : filteredEntries.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '40px', color: '#9ca3af' }}>
            <p>Belum ada transaksi untuk {bulanDisplay}</p>
            <p style={{ fontSize: '13px', marginTop: '8px' }}>Jurnal akan terisi otomatis saat ada pembayaran baru.</p>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            {filteredEntries.map((entry) => (
              <div
                key={entry.id}
                style={{
                  background: '#fff', borderRadius: '10px', padding: '12px 14px',
                  border: '1px solid #e5e7eb', display: 'flex', alignItems: 'center', gap: '12px'
                }}
              >
                {/* Tipe badge */}
                <div style={{
                  background: (TIPE_COLORS[entry.tipe] || '#6b7280') + '15',
                  color: TIPE_COLORS[entry.tipe] || '#6b7280',
                  padding: '4px 10px', borderRadius: '6px', fontSize: '11px',
                  fontWeight: 600, whiteSpace: 'nowrap', minWidth: '70px', textAlign: 'center'
                }}>
                  {TIPE_LABELS[entry.tipe] || entry.tipe}
                </div>

                {/* Info */}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: '14px', fontWeight: 600, color: '#1f2937', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {entry.namaPelanggan || entry.namaKtp || '-'}
                  </div>
                  <div style={{ fontSize: '12px', color: '#6b7280' }}>
                    {entry.tanggal} &middot; {entry.adminName} &middot; Pinjaman ke-{entry.pinjamanKe}
                  </div>
                  {entry.keterangan && (
                    <div style={{ fontSize: '11px', color: '#9ca3af', fontStyle: 'italic' }}>{entry.keterangan}</div>
                  )}
                </div>

                {/* Amount */}
                <div style={{ textAlign: 'right', flexShrink: 0 }}>
                  {entry.tipe !== 'lunas' ? (
                    <>
                      <div style={{
                        fontSize: '14px', fontWeight: 700,
                        color: entry.tipe === 'pencairan_pinjaman' ? '#d97706' : '#059669'
                      }}>
                        {entry.tipe === 'pencairan_pinjaman' ? '-' : '+'}{formatRpFull(entry.jumlah)}
                      </div>
                      <div style={{ fontSize: '11px', color: '#9ca3af' }}>
                        Sisa: {formatRpFull(entry.sisaUtangSetelah)}
                      </div>
                    </>
                  ) : (
                    <div style={{ fontSize: '13px', fontWeight: 700, color: '#059669' }}>LUNAS</div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}