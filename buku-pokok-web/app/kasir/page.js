'use client';

// app/kasir/page.js
// =========================================================================
// KASIR WEB - Jurnal Kasir & Akses Buku Pokok
// =========================================================================

import { useState, useEffect, useRef } from 'react';
import { onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'firebase/auth';
import { auth, storage, database } from '../../lib/firebase';
import { ref as storageRef, uploadBytes, getDownloadURL } from 'firebase/storage';
import { ref as dbRef, update } from 'firebase/database';
import { getKasirSummary, getKasirEntries, addKasirEntry, deleteKasirEntry, getBukuPokok, syncOperasionalTransport } from '../../lib/api';
import { formatRp, formatRpFull } from '../../lib/format';

// =========================================================================
// HELPER: Compress image for upload (max 1024px, JPEG quality 0.6)
// =========================================================================
function compressImage(file, maxSize = 1024, quality = 0.6) {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      const img = new Image();
      img.onload = () => {
        const canvas = document.createElement('canvas');
        let w = img.width, h = img.height;
        if (w > h && w > maxSize) { h = Math.round(h * maxSize / w); w = maxSize; }
        else if (h > maxSize) { w = Math.round(w * maxSize / h); h = maxSize; }
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, w, h);
        canvas.toBlob((blob) => resolve(blob), 'image/jpeg', quality);
      };
      img.src = e.target.result;
    };
    reader.readAsDataURL(file);
  });
}

// =========================================================================
// CONSTANTS
// =========================================================================
const JENIS_OPTIONS = [
  { value: 'uang_kas', label: 'Kasbon Pagi' },
  { value: 'penggajian', label: 'BU' },
  { value: 'transport', label: 'Transport' },
  { value: 'suntikan_dana', label: 'Suntikan Dana' },
  { value: 'pinjaman_kas', label: 'Pinjaman Kas' },
  { value: 'sp', label: 'SP' },
  { value: 'pengembalian_kas', label: 'Pengembalian Kas' },
];

const JENIS_ARAH = {
  uang_kas: 'keluar',
  penggajian: 'keluar',
  transport: 'keluar',
  suntikan_dana: 'masuk',
  pinjaman_kas: 'masuk',
  sp: 'keluar',
  pengembalian_kas: 'keluar',
};


const BULAN_INDO = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

function getCurrentMonthKey() {
  const now = new Date();
  const jakartaOffset = 7 * 60;
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const jakarta = new Date(utc + (jakartaOffset * 60000));
  const yyyy = jakarta.getFullYear();
  const mm = String(jakarta.getMonth() + 1).padStart(2, '0');
  return `${yyyy}-${mm}`;
}

function getTodayIndo() {
  const now = new Date();
  const jakartaOffset = 7 * 60;
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const jakarta = new Date(utc + (jakartaOffset * 60000));
  const dd = String(jakarta.getDate()).padStart(2, '0');
  const mmm = BULAN_INDO[jakarta.getMonth()];
  const yyyy = jakarta.getFullYear();
  return `${dd} ${mmm} ${yyyy}`;
}

function formatBulanLabel(bulanKey) {
  const [y, m] = bulanKey.split('-');
  return `${BULAN_INDO[parseInt(m) - 1]} ${y}`;
}

// =========================================================================
// HOLIDAY UTILS — sama persis dengan HolidaysUtils.kt di Android
// Cek berdasarkan hari + bulan saja (tidak cek tahun), sesuai Kotlin
// =========================================================================
const LIBUR_NASIONAL = [
  [1, 1],   // Jan 1  — Tahun Baru
  [16, 1],  // Jan 16 — Hari pertama Ramadan (2026)
  [17, 2],  // Feb 17 — Isra Mikraj
  [19, 3],  // Mar 19 — Hari Raya Nyepi
  [21, 3],  // Mar 21 — Hari Raya Idul Fitri
  [3, 4],   // Apr 3  — Hari Raya Idul Fitri (cuti bersama)
  [1, 5],   // Mei 1  — Hari Buruh
  [14, 5],  // Mei 14 — Kenaikan Isa Almasih
  [16, 6],  // Jun 16 — Hari Raya Idul Adha
  [17, 8],  // Agu 17 — HUT RI
  [25, 8],  // Agu 25 — Maulid Nabi
  [25, 12], // Des 25 — Hari Natal
];

function isTanggalMerah(date) {
  const d = date.getDate();
  const m = date.getMonth() + 1; // 1-based, sama seperti Kotlin
  return LIBUR_NASIONAL.some(([ld, lm]) => ld === d && lm === m);
}

function isMinggu(date) {
  return date.getDay() === 0; // 0 = Sunday
}

function isHariKerja(date) {
  return !isMinggu(date) && !isTanggalMerah(date);
}

// Parse string "07 Feb 2026" ke Date object (WIB — tidak ada timezone shift)
function parseTanggalIndo(s) {
  if (!s) return null;
  const parts = s.split(' ');
  if (parts.length !== 3) return null;
  const bulanIdx = BULAN_INDO.indexOf(parts[1]);
  if (bulanIdx === -1) return null;
  return new Date(parseInt(parts[2]), bulanIdx, parseInt(parts[0]));
}

function generateBulanOptions() {
  const options = [];
  const now = new Date();
  const jakartaOffset = 7 * 60;
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const jakarta = new Date(utc + (jakartaOffset * 60000));
  for (let i = 0; i < 12; i++) {
    const d = new Date(jakarta.getFullYear(), jakarta.getMonth() - i, 1);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    const label = `${BULAN_INDO[d.getMonth()]} ${d.getFullYear()}`;
    options.push({ key, label });
  }
  return options;
}


// =========================================================================
// MAIN COMPONENT
// =========================================================================
export default function KasirPage() {
  const [user, setUser] = useState(null);
  const [userData, setUserData] = useState(null);
  const [screen, setScreen] = useState('loading');
  const [cabangList, setCabangList] = useState([]);
  const [summaryData, setSummaryData] = useState({});
  const [jenisLabels, setJenisLabels] = useState({});
  const [selectedCabang, setSelectedCabang] = useState(null);
  const [showLogoutModal, setShowLogoutModal] = useState(false);

  // ==================== AUTH STATE ====================
  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (firebaseUser) => {
      if (firebaseUser) {
        setUser(firebaseUser);
        try {
          const result = await getKasirSummary();
          if (result.success) {
            const d = result.data;
            setUserData(d.user);
            setCabangList(d.cabangList);
            setSummaryData(d.summary);
            setJenisLabels(d.jenisLabels || {});

            // Auto-select cabang jika hanya ada 1
            if (d.cabangList.length === 1) {
              setSelectedCabang(d.cabangList[0]);
            }

            // Cek parameter ?screen= dari pembukuan (untuk pimpinan/koordinator/pengawas)
            const urlParams = new URLSearchParams(window.location.search);
            const targetScreen = urlParams.get('screen');
            const validScreens = ['jurnal', 'bukuRekap', 'kasPenuntun', 'bukuTunai', 'bukuEkspedisi', 'ringkasan', 'absensi'];
            if (targetScreen && validScreens.includes(targetScreen)) {
              setScreen(targetScreen);
            } else {
              setScreen('home');
            }
          }
        } catch (err) {
          console.error('Failed to get kasir summary:', err);
          if (err.message && err.message.includes('Kasir')) {
            setScreen('forbidden');
          } else {
            setScreen('home');
          }
        }
      } else {
        setUser(null);
        setUserData(null);
        // Tidak ada login di /kasir, arahkan ke /pembukuan
        window.location.href = '/pembukuan';
        return;
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
    await signOut(auth);
  };

  const goToAbsensi = () => {
    setShowLogoutModal(false);
    setScreen('absensi');
  };

  // Untuk pimpinan/koordinator/pengawas yang datang dari pembukuan
  const isFromPembukuan = typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('screen');
  const VIEWER_ROLES = ['pimpinan', 'koordinator', 'pengawas', 'kasir_wilayah', 'sekretaris'];
  const isViewer = VIEWER_ROLES.includes(userData?.role);

  const handleKasirNavigate = (screenId) => {
    const kasirScreens = ['jurnal', 'bukuRekap', 'kasPenuntun', 'bukuTunai', 'bukuEkspedisi', 'ringkasan', 'absensi'];
    if (kasirScreens.includes(screenId)) {
      setScreen(screenId);
    } else {
      // bukuPokok dan jurnalTransaksi ada di /pembukuan
      window.location.href = '/pembukuan';
    }
  };

  const handleBack = () => {
    if (isViewer && isFromPembukuan) {
      window.location.href = '/pembukuan';
    } else {
      setScreen('home');
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
  if (screen === 'forbidden') {
    content = <ForbiddenScreen onLogout={handleLogout} />;
  } else if (screen === 'jurnal') {
    content = (
      <JurnalScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'bukuRekap') {
    content = (
      <BukuRekapScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'kasPenuntun') {
    content = (
      <KasPenuntunScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'bukuTunai') {
    content = (
      <BukuTunaiScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'bukuEkspedisi') {
    content = (
      <BukuEkspedisiScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'ringkasan') {
    content = (
      <RingkasanScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'absensi') {
    content = (
      <AbsensiScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else {
    content = (
      <HomeScreen
        user={userData}
        cabangList={cabangList}
        summaryData={summaryData}
        selectedCabang={selectedCabang}
        onSelectCabang={(c) => setSelectedCabang(c)}
        onNavigate={(s) => setScreen(s)}
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
// KASIR TOP BAR NAVIGATION (shortcut menu di header)
// ============================================================
function KasirTopBarNav({ currentScreen, onNavigate }) {
  const menus = [
    { id: 'bukuPokok', label: 'Buku Pokok' },
    { id: 'jurnalTransaksi', label: 'Jurnal Transaksi' },
    { id: 'jurnal', label: 'Jurnal Kasir' },
    { id: 'bukuRekap', label: 'Buku Rekap' },
    { id: 'bukuTunai', label: 'Buku Tunai' },
    { id: 'kasPenuntun', label: 'Kas Penuntun' },
    { id: 'bukuEkspedisi', label: 'Buku Ekspedisi' },
    { id: 'ringkasan', label: 'Ringkasan Kas' },
    { id: 'absensi', label: 'Absensi' },
  ];

  return (
    <nav className="top-bar-nav">
      {menus.map((m) => (
        <button
          key={m.id}
          className={`top-bar-nav-item${currentScreen === m.id ? ' active' : ''}`}
          onClick={() => onNavigate(m.id)}
          disabled={currentScreen === m.id}
        >
          {m.label}
        </button>
      ))}
    </nav>
  );
}

// ============================================================
// LOGIN SCREEN (reuse pattern dari pembukuan)
// ============================================================
function LoginScreen({ onLogin }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!email || !password) { setError('Email dan password harus diisi'); return; }
    setLoading(true);
    setError('');
    try {
      await onLogin(email, password);
    } catch (err) {
      setError(
        err.code === 'auth/invalid-credential'
          ? 'Email atau password salah'
          : err.code === 'auth/too-many-requests'
          ? 'Terlalu banyak percobaan. Coba lagi nanti.'
          : 'Gagal login: ' + err.message
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-bg" />
      <div className="login-decor login-decor-1" />
      <div className="login-decor login-decor-2" />
      <div className="login-card">
        <div className="login-logo">
          <div className="login-logo-icon" style={{ background: '#000', padding: 0, overflow: 'hidden' }}>
            <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <h1>Kasir Koperasi Kita</h1>
          <p className="login-subtitle">Masuk untuk mengelola jurnal kasir</p>
        </div>

        {error && <div className="login-error"><span className="login-error-icon">!</span>{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="login-field">
            <label>Email</label>
            <div className="input-with-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/></svg>
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="kasir@godangulu.com" autoComplete="email" />
            </div>
          </div>
          <div className="login-field">
            <label>Password</label>
            <div className="input-with-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              <input type={showPassword ? 'text' : 'password'} value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" autoComplete="current-password" />
              <button type="button" onClick={() => setShowPassword(!showPassword)} className="eye-toggle">
                {showPassword ? (
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" x2="23" y1="1" y2="23"/></svg>
                ) : (
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                )}
              </button>
            </div>
          </div>
          <button type="submit" className="login-btn" disabled={loading}>
            {loading ? <span className="spinner" /> : 'Masuk'}
          </button>
        </form>
      </div>
    </div>
  );
}


// ============================================================
// FORBIDDEN SCREEN
// ============================================================
function ForbiddenScreen({ onLogout }) {
  return (
    <div className="page-container" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh' }}>
      <div style={{ textAlign: 'center', padding: 40 }}>
        <div style={{ fontSize: 48, marginBottom: 16 }}>🔒</div>
        <h2 style={{ fontSize: 20, fontWeight: 700, marginBottom: 8 }}>Akses Ditolak</h2>
        <p style={{ color: 'var(--text-muted)', marginBottom: 24 }}>Akun Anda bukan akun Kasir. Halaman ini hanya untuk Kasir Unit dan Kasir Wilayah.</p>
        <button onClick={onLogout} className="login-btn" style={{ maxWidth: 200, margin: '0 auto' }}>Keluar</button>
      </div>
    </div>
  );
}


// ============================================================
// HOME SCREEN (Dashboard Kasir)
// ============================================================
function HomeScreen({ user, cabangList, summaryData, selectedCabang, onSelectCabang, onNavigate, onLogout }) {
  const isUnit = user?.role === 'kasir_unit';
  const VIEWER_ROLES = ['pimpinan', 'koordinator', 'pengawas'];
  const isViewer = VIEWER_ROLES.includes(user?.role);
  const cabId = selectedCabang?.id || (cabangList.length === 1 && cabangList[0]?.id);
  const summary = cabId ? (summaryData[cabId] || {}) : {};

  const roleLabels = { kasir_unit: 'Kasir Unit', kasir_wilayah: 'Kasir Wilayah', sekretaris: 'Sekretaris', pimpinan: 'Pimpinan', koordinator: 'Koordinator', pengawas: 'Pengawas' };
  const roleLabel = roleLabels[user?.role] || user?.role;

  const menus = [
    {
      id: 'jurnal', name: 'Jurnal Kasir', desc: 'Catatan transaksi kas harian',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/><line x1="16" x2="8" y1="13" y2="13"/><line x1="16" x2="8" y1="17" y2="17"/><line x1="10" x2="8" y1="9" y2="9"/>
        </svg>
      ),
    },
    {
      id: 'bukuPokok', name: 'Buku Pokok', desc: 'Catatan pinjaman & pembayaran nasabah',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z"/><path d="M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"/><path d="M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"/>
          <path d="M10 6h4"/><path d="M10 10h4"/><path d="M10 14h4"/><path d="M10 18h4"/>
        </svg>
      ),
    },
        {
      id: 'bukuRekap', name: 'Buku Rekap', desc: 'Rekap harian per resort',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M3 3h18v18H3zM3 9h18M3 15h18M9 3v18M15 3v18"/>
        </svg>
      ),
    },
    {
      id: 'bukuTunai', name: 'Buku Tunai', desc: 'Rekap kasbon & tunai harian per resort',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <rect width="20" height="14" x="2" y="5" rx="2"/><line x1="2" x2="22" y1="10" y2="10"/>
          <path d="M12 15h.01M8 15h.01M16 15h.01"/>
        </svg>
      ),
    },
    {
      id: 'kasPenuntun', name: 'Kas Penuntun', desc: 'Buku kas harian penuntun',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M3 3h18v18H3zM3 9h18M3 15h18M9 3v18M15 3v18"/>
        </svg>
      ),
    },
    {
      id: 'bukuEkspedisi', name: 'Buku Ekspedisi', desc: 'Kas harian masuk & keluar ekspedisi',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" x2="8" y1="13" y2="13"/><line x1="16" x2="8" y1="17" y2="17"/><polyline points="10 9 9 9 8 9"/>
        </svg>
      ),
    },
    {
      id: 'ringkasan', name: 'Ringkasan Kas', desc: 'Rekapitulasi pemasukan & pengeluaran',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M21.21 15.89A10 10 0 1 1 8 2.83"/><path d="M22 12A10 10 0 0 0 12 2v10z"/>
        </svg>
      ),
    },
    {
      id: 'absensi', name: 'Absensi Karyawan', desc: 'Lihat & kelola absensi harian karyawan',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <rect width="18" height="18" x="3" y="4" rx="2" ry="2"/><line x1="16" x2="16" y1="2" y2="6"/><line x1="8" x2="8" y1="2" y2="6"/><line x1="3" x2="21" y1="10" y2="10"/>
          <path d="m9 16 2 2 4-4"/>
        </svg>
      ),
    },
  ];

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <div className="top-bar-logo" style={{ background: '#000', padding: 0, overflow: 'hidden' }}>
            <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <div>
            <h1>Koperasi Kita</h1>
            <p>Kasir — KSP Sigodang Ulu Jaya</p>
          </div>
        </div>
        <div className="top-bar-right">
          {user && (
            <div className="user-badge">
              <span className="user-name">{user.name}</span>
              <span className="user-role">{roleLabel}</span>
            </div>
          )}
          {isViewer ? (
            <button onClick={() => { window.location.href = '/pembukuan'; }} className="btn-icon" title="Kembali ke Pembukuan">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
            </button>
          ) : (
            <button onClick={onLogout} className="btn-icon" title="Keluar">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>
              </svg>
            </button>
          )}
        </div>
      </header>

      <main className="home-content fade-in">
        <div className="home-welcome">
          <div className="home-welcome-icon" style={{ background: '#000', padding: 0, overflow: 'hidden' }}>
            <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <h2>Selamat Datang, {user?.name || 'Kasir'}</h2>
          <p className="home-ksp-name">KSP Sigodang Ulu Jaya</p>
          {selectedCabang && <p className="home-user-greeting">Cabang: {selectedCabang.name}</p>}
        </div>

        {/* Kasir Wilayah: pilih cabang */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 20 }}>
            <div className="home-section-label">Pilih Cabang</div>
            <div className="cabang-grid">
              {cabangList.map((c) => (
                <button key={c.id} onClick={() => onSelectCabang(c)}
                  className="cabang-card" style={selectedCabang?.id === c.id ? { borderColor: 'var(--primary)', background: 'var(--primary-light)' } : {}}>
                  <div className="cabang-card-icon">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                      <path d="M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z"/><path d="M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"/><path d="M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"/>
                      <path d="M10 6h4"/><path d="M10 10h4"/><path d="M10 14h4"/><path d="M10 18h4"/>
                    </svg>
                  </div>
                  <div className="cabang-card-info">
                    <h3>{c.name}</h3>
                    <p>{c.admins?.length || 0} Resort</p>
                  </div>
                  {selectedCabang?.id === c.id && (
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" strokeWidth="2.5" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>
                  )}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Ringkasan cepat (hanya jika cabang sudah dipilih) */}
        {cabId && summary && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, margin: '0 0 24px' }}>
            <div style={{ background: 'var(--card)', borderRadius: 14, padding: '16px 20px', border: '1px solid var(--border)' }}>
              <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 4 }}>Total Masuk (Bulan Ini)</p>
              <p style={{ fontSize: 18, fontWeight: 700, color: 'var(--success)' }}>{formatRpFull(summary.totalMasuk || 0)}</p>
            </div>
            <div style={{ background: 'var(--card)', borderRadius: 14, padding: '16px 20px', border: '1px solid var(--border)' }}>
              <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 4 }}>Total Keluar (Bulan Ini)</p>
              <p style={{ fontSize: 18, fontWeight: 700, color: 'var(--danger)' }}>{formatRpFull(summary.totalKeluar || 0)}</p>
            </div>
          </div>
        )}

        <div className="home-section-label">Menu Kasir</div>
        <div className="book-grid">
          {menus.map((m) => (
            <button key={m.id} onClick={() => m.id === 'bukuPokok' ? (window.location.href = '/pembukuan?from=kasir') : onNavigate(m.id)} className="book-card">
              <div className="book-card-icon">{m.icon}</div>
              <div className="book-card-info">
                <h3>{m.name}</h3>
                <p>{m.desc}</p>
              </div>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><polyline points="9 18 15 12 9 6"/></svg>
            </button>
          ))}
        </div>
      </main>
    </div>
  );
}


// ============================================================
// JURNAL SCREEN
// ============================================================
function JurnalScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(cabang || cabangList[0] || null);
  const [bulan, setBulan] = useState(getCurrentMonthKey());
  const [entries, setEntries] = useState([]);
  const [summary, setSummary] = useState({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  const bulanOptions = generateBulanOptions();

  const fetchEntries = async () => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    try {
      const result = await getKasirEntries({ cabangId: activeCabang.id, bulan });
      if (result.success) {
        setEntries(result.data.entries || []);
        setSummary(result.data.summary || {});
      }
    } catch (err) {
      setError('Gagal memuat data: ' + err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchEntries(); }, [activeCabang?.id, bulan]);

  const handleEntryAdded = () => {
    setShowForm(false);
    fetchEntries();
  };

  const handleDelete = async (entry) => {
    setDeleteLoading(true);
    try {
      await deleteKasirEntry({ cabangId: activeCabang.id, bulan, entryId: entry.id });
      setDeleteConfirm(null);
      fetchEntries();
    } catch (err) {
      alert('Gagal menghapus: ' + err.message);
    } finally {
      setDeleteLoading(false);
    }
  };

  // Group entries by tanggal
  const grouped = {};
  entries.forEach(e => {
    const tgl = e.tanggal || 'Tanpa Tanggal';
    if (!grouped[tgl]) grouped[tgl] = [];
    grouped[tgl].push(e);
  });
  const sortedDates = Object.keys(grouped).sort((a, b) => {
    // Sort descending by date (terbaru ke terlama)
    const pa = a.split(' '), pb = b.split(' ');
    const da = new Date(parseInt(pa[2]), BULAN_INDO.indexOf(pa[1]), parseInt(pa[0]));
    const db = new Date(parseInt(pb[2]), BULAN_INDO.indexOf(pb[1]), parseInt(pb[0]));
    return db - da;
  });

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Jurnal Kasir</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="jurnal" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          {isUnit && (
            <button onClick={() => setShowForm(true)} style={{
              background: 'var(--primary)', color: '#fff', padding: '8px 16px', borderRadius: 10,
              fontSize: 13, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6,
              transition: 'all 0.2s'
            }}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><line x1="12" x2="12" y1="5" y2="19"/><line x1="5" x2="19" y1="12" y2="12"/></svg>
              Tambah
            </button>
          )}
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: '20px 24px', maxWidth: 900, margin: '0 auto' }} className="fade-in">
        {/* Filters */}
        <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center' }}>
          {!isUnit && cabangList.length > 1 && (
            <select value={activeCabang?.id || ''} onChange={(e) => setActiveCabang(cabangList.find(c => c.id === e.target.value))}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          <select value={bulan} onChange={(e) => setBulan(e.target.value)}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
        </div>

        {/* Summary cards */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 10, marginBottom: 20 }}>
          <div style={{ background: '#e8f8f0', borderRadius: 12, padding: '12px 16px' }}>
            <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Total Masuk</p>
            <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--success)' }}>{formatRpFull(summary.totalMasuk || 0)}</p>
          </div>
          <div style={{ background: '#fef2f0', borderRadius: 12, padding: '12px 16px' }}>
            <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Total Keluar</p>
            <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--danger)' }}>{formatRpFull(summary.totalKeluar || 0)}</p>
          </div>
        </div>

        {/* Content */}
        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat jurnal...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--danger)', fontSize: 14 }}>{error}</p>
            <button onClick={fetchEntries} style={{ marginTop: 12, padding: '8px 20px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>Coba Lagi</button>
          </div>
        ) : entries.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ fontSize: 40, marginBottom: 8 }}>📋</p>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Belum ada transaksi bulan {formatBulanLabel(bulan)}</p>
          </div>
        ) : (
          <div>
            {sortedDates.map(tgl => (
              <div key={tgl} style={{ marginBottom: 20 }}>
                <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-muted)', padding: '8px 0', borderBottom: '1px solid var(--border-light)', marginBottom: 8 }}>
                  {tgl}
                </div>
                {grouped[tgl].map(entry => (
                  <div key={entry.id} style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    padding: '12px 16px', background: 'var(--card)', borderRadius: 12,
                    border: '1px solid var(--border)', marginBottom: 6, gap: 12,
                  }}>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 2 }}>
                        <span style={{
                          fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 6,
                          background: entry.arah === 'masuk' ? '#e8f8f0' : '#fef2f0',
                          color: entry.arah === 'masuk' ? 'var(--success)' : 'var(--danger)',
                        }}>{entry.arah === 'masuk' ? '↑ Masuk' : '↓ Keluar'}</span>
                        <span style={{ fontSize: 13, fontWeight: 600 }}>{entry.jenisLabel || entry.jenis}</span>
                        {entry.source === 'operasional_harian' && <span style={{ fontSize: 10, fontWeight: 600, padding: '1px 6px', borderRadius: 4, background: '#dbeafe', color: '#2563eb' }}>Auto</span>}
                      </div>
                      {entry.keterangan && <p style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>{entry.keterangan}</p>}
                    </div>
                    <div style={{ textAlign: 'right', flexShrink: 0 }}>
                      <p style={{ fontSize: 15, fontWeight: 700, color: entry.arah === 'masuk' ? 'var(--success)' : 'var(--danger)' }}>
                        {formatRpFull(entry.jumlah)}

                      </p>
                    </div>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
      </main>

      {/* Form Modal */}
      {showForm && (
        <FormModal
          cabangAdmins={activeCabang?.admins || []}
          cabangId={activeCabang?.id || ''}
          onClose={() => setShowForm(false)}
          onSuccess={handleEntryAdded}
        />
      )}

      {/* Delete Confirm */}
      {deleteConfirm && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}
          onClick={() => setDeleteConfirm(null)}>
          <div style={{ background: '#fff', borderRadius: 20, padding: 28, maxWidth: 380, width: '90%', textAlign: 'center' }} onClick={e => e.stopPropagation()}>
            <p style={{ fontSize: 36, marginBottom: 12 }}>🗑️</p>
            <h3 style={{ fontSize: 16, fontWeight: 700, marginBottom: 8 }}>Hapus Transaksi?</h3>
            <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 20 }}>
              {deleteConfirm.jenisLabel} — {formatRpFull(deleteConfirm.jumlah)}<br />
              Tindakan ini tidak dapat dibatalkan.
            </p>
            <div style={{ display: 'flex', gap: 10 }}>
              <button onClick={() => setDeleteConfirm(null)} style={{ flex: 1, padding: '10px 0', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, fontWeight: 600 }}>Batal</button>
              <button onClick={() => handleDelete(deleteConfirm)} disabled={deleteLoading}
                style={{ flex: 1, padding: '10px 0', borderRadius: 10, background: 'var(--danger)', color: '#fff', fontSize: 13, fontWeight: 600, opacity: deleteLoading ? 0.6 : 1 }}>
                {deleteLoading ? 'Menghapus...' : 'Hapus'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}


// ============================================================
// FORM MODAL (Input transaksi kasir)
// ============================================================
function FormModal({ cabangAdmins, cabangId, onClose, onSuccess }) {
  const [jenis, setJenis] = useState('uang_kas');
  const [arah, setArah] = useState('keluar');
  const [jumlah, setJumlah] = useState('');
  const [keterangan, setKeterangan] = useState('');
  const [tanggal] = useState(getTodayIndo());
  const [targetAdmin, setTargetAdmin] = useState('');
  const [targetBuku, setTargetBuku] = useState(['kas_penuntun', 'ekspedisi']);
  const [fakturFile, setFakturFile] = useState(null);
  const [fakturPreview, setFakturPreview] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const fakturInputRef = useRef(null);

  const handleFakturCapture = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setFakturFile(file);
    const reader = new FileReader();
    reader.onload = (ev) => setFakturPreview(ev.target.result);
    reader.readAsDataURL(file);
  };

  const handleSubmit = async () => {
    const nominal = parseInt(jumlah.replace(/\D/g, ''), 10);
    if (!nominal || nominal <= 0) { setError('Jumlah harus diisi'); return; }
    if (jenis === 'uang_kas' && !targetAdmin) { setError('Pilih admin lapangan yang dituju'); return; }
    if (jenis === 'penggajian' && targetBuku.length === 0) { setError('Pilih minimal satu buku tujuan BU'); return; }
    setLoading(true);
    setError('');
    try {
      const selectedAdm = cabangAdmins.find(a => a.uid === targetAdmin);
      const result = await addKasirEntry({
        jenis, arah, jumlah: nominal, keterangan, tanggal,
        targetAdminUid: jenis === 'uang_kas' ? targetAdmin : '',
        targetAdminName: jenis === 'uang_kas' ? (selectedAdm?.name || '') : '',
        targetBuku: jenis === 'penggajian' ? targetBuku : [],
      });

      // Upload faktur photo if exists (BU only)
      if (fakturFile && jenis === 'penggajian' && result?.data?.id && cabangId) {
        try {
          const entryId = result.data.id;
          const bulanKey = result.data.bulan;
          const compressed = await compressImage(fakturFile);
          const photoRef = storageRef(storage, `faktur_bu/${cabangId}/${bulanKey}/${entryId}.jpg`);
          await uploadBytes(photoRef, compressed, { contentType: 'image/jpeg' });
          const downloadUrl = await getDownloadURL(photoRef);
          // Update RTDB entry with fakturUrl
          const entryDbRef = dbRef(database, `kasir_entries/${cabangId}/${bulanKey}/${entryId}/fakturUrl`);
          await update(dbRef(database, `kasir_entries/${cabangId}/${bulanKey}/${entryId}`), { fakturUrl: downloadUrl });
        } catch (uploadErr) {
          console.error('Gagal upload faktur:', uploadErr);
          // Entry sudah tersimpan, foto gagal — tidak blocking
        }
      }

      onSuccess();
    } catch (err) {
      setError('Gagal menyimpan: ' + err.message);
    } finally {
      setLoading(false);
    }
  };

  const handleJumlahChange = (e) => {
    const raw = e.target.value.replace(/\D/g, '');
    if (raw === '') { setJumlah(''); return; }
    setJumlah(new Intl.NumberFormat('id-ID').format(parseInt(raw)));
  };

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200, padding: 20 }}
      onClick={onClose}>
      <div style={{ background: '#fff', borderRadius: 20, padding: 28, maxWidth: 440, width: '100%' }}
        onClick={e => e.stopPropagation()} className="slide-up">
        <h3 style={{ fontSize: 18, fontWeight: 700, marginBottom: 20 }}>Tambah Transaksi Kasir</h3>

        {error && <div style={{ background: '#fef2f0', color: 'var(--danger)', padding: '10px 14px', borderRadius: 10, fontSize: 13, marginBottom: 16 }}>{error}</div>}

        {/* Jenis */}
        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Jenis Transaksi</label>
            <select value={jenis} onChange={(e) => { const v = e.target.value; setJenis(v); setArah(JENIS_ARAH[v] || 'keluar'); setTargetAdmin(''); if (v === 'penggajian') setTargetBuku(['kas_penuntun', 'ekspedisi']); }}
            style={{ width: '100%', padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 14, background: 'var(--card)' }}>
            {JENIS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>

        {/* Pilihan Buku Tujuan — hanya untuk BU */}
        {jenis === 'penggajian' && (
          <div style={{ marginBottom: 16 }}>
            <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Buku Tujuan BU</label>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, cursor: 'pointer', padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', background: targetBuku.includes('kas_penuntun') ? '#e8f8f0' : 'var(--card)' }}>
                <input type="checkbox" checked={targetBuku.includes('kas_penuntun')}
                  onChange={(e) => {
                    if (e.target.checked) setTargetBuku(prev => [...prev, 'kas_penuntun']);
                    else setTargetBuku(prev => prev.filter(b => b !== 'kas_penuntun'));
                  }}
                  style={{ width: 18, height: 18, accentColor: 'var(--primary)' }} />
                Buku Kas Penuntun
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, cursor: 'pointer', padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', background: targetBuku.includes('ekspedisi') ? '#e8f8f0' : 'var(--card)' }}>
                <input type="checkbox" checked={targetBuku.includes('ekspedisi')}
                  onChange={(e) => {
                    if (e.target.checked) setTargetBuku(prev => [...prev, 'ekspedisi']);
                    else setTargetBuku(prev => prev.filter(b => b !== 'ekspedisi'));
                  }}
                  style={{ width: 18, height: 18, accentColor: 'var(--primary)' }} />
                Buku Ekspedisi
              </label>
            </div>
          </div>
        )}

        {/* Admin Lapangan yang dituju — hanya untuk Uang Kas */}
        {jenis === 'uang_kas' && cabangAdmins.length > 0 && (
          <div style={{ marginBottom: 16 }}>
            <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Admin Lapangan yang Dituju</label>
            <select value={targetAdmin} onChange={(e) => setTargetAdmin(e.target.value)}
              style={{ width: '100%', padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 14, background: 'var(--card)' }}>
              <option value="">-- Pilih Admin --</option>
              {cabangAdmins.map(a => <option key={a.uid} value={a.uid}>{a.name}</option>)}
            </select>
          </div>
        )}

        {/* Jumlah */}
        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Jumlah (Rp)</label>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input type="text" value={jumlah} onChange={handleJumlahChange} placeholder="0" inputMode="numeric"
              style={{ flex: 1, padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 18, fontWeight: 700, fontFamily: "'DM Mono', monospace", boxSizing: 'border-box' }} />
            {jenis === 'penggajian' && (
              <button type="button" onClick={() => fakturInputRef.current?.click()}
                style={{ width: 44, height: 44, borderRadius: 10, border: fakturPreview ? '2px solid var(--primary)' : '1px solid var(--border)', background: fakturPreview ? '#e8f8f0' : 'var(--card)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}
                title="Foto Faktur BU">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={fakturPreview ? 'var(--primary)' : '#666'} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/>
                  <circle cx="12" cy="13" r="4"/>
                </svg>
              </button>
            )}
            <input ref={fakturInputRef} type="file" accept="image/*" capture="environment"
              style={{ display: 'none' }} onChange={handleFakturCapture} />
          </div>
          {/* Faktur preview */}
          {jenis === 'penggajian' && fakturPreview && (
            <div style={{ marginTop: 8, position: 'relative', display: 'inline-block' }}>
              <img src={fakturPreview} alt="Faktur" style={{ width: 80, height: 80, objectFit: 'cover', borderRadius: 8, border: '1px solid var(--border)' }} />
              <button type="button" onClick={() => { setFakturFile(null); setFakturPreview(null); if (fakturInputRef.current) fakturInputRef.current.value = ''; }}
                style={{ position: 'absolute', top: -6, right: -6, width: 20, height: 20, borderRadius: '50%', background: 'var(--danger)', color: '#fff', border: 'none', fontSize: 12, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1 }}>×</button>
            </div>
          )}
        </div>

        {/* Tanggal */}
        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Tanggal</label>
          <div style={{ padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 14, background: '#f8f9fa', color: 'var(--text-muted)' }}>{tanggal}</div>
        </div>

        {/* Keterangan */}
        <div style={{ marginBottom: 24 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Keterangan <span style={{ fontWeight: 400, color: 'var(--text-light)' }}>(opsional)</span></label>
          <input type="text" value={keterangan} onChange={(e) => setKeterangan(e.target.value)} placeholder="Catatan tambahan..."
            style={{ width: '100%', padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 14, boxSizing: 'border-box' }} />
        </div>

        {/* Actions */}
        <div style={{ display: 'flex', gap: 10 }}>
          <button onClick={onClose} style={{ flex: 1, padding: '12px 0', borderRadius: 12, border: '1px solid var(--border)', fontSize: 14, fontWeight: 600 }}>Batal</button>
          <button onClick={handleSubmit} disabled={loading}
            style={{ flex: 1, padding: '12px 0', borderRadius: 12, background: 'var(--primary)', color: '#fff', fontSize: 14, fontWeight: 600, opacity: loading ? 0.6 : 1, transition: 'all 0.2s' }}>
            {loading ? 'Menyimpan...' : 'Simpan'}
          </button>
        </div>
      </div>
    </div>
  );
}


// ============================================================
// FAKTUR PHOTO MODAL (View faktur BU with zoom)
// ============================================================
function FakturModal({ fakturList, onClose }) {
  const [zoomedUrl, setZoomedUrl] = useState(null);

  if (!fakturList || fakturList.length === 0) return null;

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 300, padding: 20 }}
      onClick={onClose}>
      <div style={{ background: '#fff', borderRadius: 16, padding: 20, maxWidth: 480, width: '100%', maxHeight: '80vh', overflow: 'auto' }}
        onClick={e => e.stopPropagation()} className="slide-up">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <h3 style={{ fontSize: 16, fontWeight: 700 }}>Faktur BU</h3>
          <button onClick={onClose} style={{ width: 32, height: 32, borderRadius: '50%', border: '1px solid var(--border)', background: 'var(--card)', cursor: 'pointer', fontSize: 16, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>×</button>
        </div>
        {fakturList.map((item, idx) => (
          <div key={idx} style={{ marginBottom: 16, borderBottom: idx < fakturList.length - 1 ? '1px solid var(--border-light)' : 'none', paddingBottom: 12 }}>
            <p style={{ fontSize: 13, fontWeight: 600, marginBottom: 8, color: 'var(--text-muted)' }}>
              {formatRpFull(item.jumlah)} {item.keterangan ? `— ${item.keterangan}` : ''}
            </p>
            {item.fakturUrl ? (
              <img src={item.fakturUrl} alt="Faktur BU"
                onClick={() => setZoomedUrl(item.fakturUrl)}
                style={{ width: '100%', maxWidth: 400, borderRadius: 10, border: '1px solid var(--border)', cursor: 'pointer' }} />
            ) : (
              <p style={{ fontSize: 12, color: 'var(--text-light)', fontStyle: 'italic' }}>Tidak ada foto faktur</p>
            )}
          </div>
        ))}
      </div>

      {/* Zoomed view */}
      {zoomedUrl && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.85)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 400, cursor: 'pointer' }}
          onClick={(e) => { e.stopPropagation(); setZoomedUrl(null); }}>
          <img src={zoomedUrl} alt="Faktur BU (zoom)"
            style={{ maxWidth: '95vw', maxHeight: '95vh', objectFit: 'contain', borderRadius: 8 }} />
          <button onClick={(e) => { e.stopPropagation(); setZoomedUrl(null); }}
            style={{ position: 'absolute', top: 20, right: 20, width: 40, height: 40, borderRadius: '50%', background: 'rgba(255,255,255,0.2)', border: 'none', color: '#fff', fontSize: 24, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>×</button>
        </div>
      )}
    </div>
  );
}


// ============================================================
// BUKU POKOK ACCESS SCREEN
// ============================================================
function BukuPokokAccessScreen({ user, cabang, cabangList, onBack, onLogout }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(cabang || cabangList[0] || null);
  const [selectedAdmin, setSelectedAdmin] = useState(null);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [visibleDateCount, setVisibleDateCount] = useState(7);
  const [search, setSearch] = useState('');

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    getBukuPokok({
      cabangId: activeCabang.id,
      adminUid: selectedAdmin?.uid || '',
      status: 'aktif',
    }).then(result => {
      if (result.success && result.type === 'buku_pokok') {
        setData(result.data);
      }
    }).catch(err => {
      setError('Gagal memuat data: ' + err.message);
    }).finally(() => {
      setLoading(false);
    });
  }, [activeCabang?.id, selectedAdmin?.uid]);

  const filtered = data?.nasabah?.filter(n => {
    if (!search) return true;
    const q = search.toLowerCase();
    return n.namaKtp.toLowerCase().includes(q) || n.namaPanggilan.toLowerCase().includes(q) || n.nomorAnggota.includes(q);
  }) || [];

  const dates = data?.tanggalList || [];
  const visibleDates = dates.slice(0, visibleDateCount);
  const admins = activeCabang?.admins || [];

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Buku Pokok</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}</p>
          </div>
        </div>
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        {/* Cabang selector for kasir_wilayah */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 16 }}>
            <select value={activeCabang?.id || ''} onChange={(e) => { setActiveCabang(cabangList.find(c => c.id === e.target.value)); setSelectedAdmin(null); }}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>
        )}

        {/* Admin chips */}
        {admins.length > 1 && (
          <div className="admin-chips-wrapper" style={{ marginBottom: 16 }}>
            <button onClick={() => setSelectedAdmin(null)} className={`admin-chip ${!selectedAdmin ? 'active' : ''}`}>Semua</button>
            {admins.map(a => (
              <button key={a.uid} onClick={() => setSelectedAdmin(a)} className={`admin-chip ${selectedAdmin?.uid === a.uid ? 'active' : ''}`}>{a.name}</button>
            ))}
          </div>
        )}

        {/* Search */}
        <div style={{ marginBottom: 16 }}>
          <input type="text" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Cari nama / no anggota..."
            style={{ width: '100%', maxWidth: 400, padding: '8px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, boxSizing: 'border-box' }} />
        </div>

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Buku Pokok...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : (
          <>
            {/* Stats */}
            <div style={{ display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>Total: <strong>{filtered.length}</strong> nasabah</span>
              <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>Piutang: <strong>{formatRpFull(filtered.reduce((s, n) => s + n.sisaUtang, 0))}</strong></span>
            </div>

            {/* Table */}
            <div className="buku-pokok-table-wrapper" style={{ overflow: 'auto', maxHeight: 'calc(100vh - 280px)', border: '1px solid var(--border)', borderRadius: 12 }}>
              <table style={{ width: '100%', minWidth: 800, fontSize: 12 }}>
                <thead>
                  <tr style={{ background: '#f8f9fa' }}>
                    <th style={{ padding: '10px 8px', textAlign: 'left', fontWeight: 700, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2 }}>No</th>
                    <th style={{ padding: '10px 8px', textAlign: 'left', fontWeight: 700, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2 }}>Nama</th>
                    <th style={{ padding: '10px 8px', textAlign: 'right', fontWeight: 700, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2 }}>Pinjaman</th>
                    <th style={{ padding: '10px 8px', textAlign: 'right', fontWeight: 700, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2 }}>Sisa</th>
                    {visibleDates.map(d => (
                      <th key={d} style={{ padding: '10px 4px', textAlign: 'center', fontWeight: 600, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2, whiteSpace: 'nowrap', fontSize: 11 }}>{d.slice(0, 6)}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((n, idx) => (
                    <tr key={n.id} style={{ borderBottom: '1px solid var(--border-light)' }}>
                      <td style={{ padding: '8px', color: 'var(--text-muted)', fontSize: 11 }}>{idx + 1}</td>
                      <td style={{ padding: '8px' }}>
                        <div style={{ fontWeight: 600, fontSize: 12 }}>{n.namaPanggilan || n.namaKtp}</div>
                        <div style={{ fontSize: 10, color: 'var(--text-muted)' }}>{n.nomorAnggota} • Ke-{n.pinjamanKe}</div>
                      </td>
                      <td style={{ padding: '8px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11 }}>{formatRp(n.totalPelunasan)}</td>
                      <td style={{ padding: '8px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11, fontWeight: 600, color: n.sisaUtang > 0 ? 'var(--danger)' : 'var(--success)' }}>{formatRp(n.sisaUtang)}</td>
                      {visibleDates.map(d => {
                        const p = n.pembayaran?.[d];
                        return (
                          <td key={d} style={{ padding: '4px', textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 10, color: p ? 'var(--success)' : 'var(--text-light)' }}>
                            {p ? formatRp(p.total) : '-'}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Load more dates */}
            {visibleDateCount < dates.length && (
              <div style={{ textAlign: 'center', marginTop: 16 }}>
                <button onClick={() => setVisibleDateCount(prev => prev + 7)}
                  style={{ padding: '8px 24px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
                  Tampilkan Hari Sebelumnya
                </button>
              </div>
            )}
          </>
        )}
      </main>
    </div>
  );
}

// ============================================================
// BUKU REKAP SCREEN (Rekap harian per resort)
// ============================================================
function BukuRekapScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(cabang || cabangList[0] || null);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedDate, setSelectedDate] = useState(null);

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    setSelectedDate(null);
    getBukuPokok({
      cabangId: activeCabang.id,
      adminUid: '',
      status: 'aktif',
    }).then(result => {
      if (result.success && result.type === 'buku_pokok') {
        setData(result.data);
      }
    }).catch(err => {
      setError('Gagal memuat data: ' + err.message);
    }).finally(() => {
      setLoading(false);
    });
  }, [activeCabang?.id]);

  // 7 tanggal terakhir dari buku pokok, skip hari Minggu dan tanggal merah
  // (logika sama dengan HolidaysUtils.kt di Android)
  const dates = (data?.tanggalList || [])
    .filter(d => {
      const dateObj = parseTanggalIndo(d);
      return dateObj && isHariKerja(dateObj);
    })
    .slice(0, 7);
  const currentDate = selectedDate || dates[0] || null;

  // ==================== COMPUTE REKAP PER RESORT ====================
  const computeRekapRows = (dateStr) => {
    if (!data?.nasabah) return [];

    const allNasabah = data.nasabah;
    const admins = activeCabang?.admins || [];
    const todayStr = dateStr || data.today || getTodayIndo();

    // Helper: parse "07 Feb 2026" ke Date object
    const BULAN_MAP_REV = {};
    BULAN_INDO.forEach((b, i) => { BULAN_MAP_REV[b] = i; });
    const parseDateStr = (s) => {
      if (!s) return null;
      const parts = s.split(' ');
      if (parts.length !== 3) return null;
      const m = BULAN_MAP_REV[parts[1]];
      if (m === undefined) return null;
      return new Date(parseInt(parts[2]), m, parseInt(parts[0]));
    };

    // Helper: is nasabah "baru" (pinjaman ke-1) or "lama" (lanjut)
    const isDropBaru = (n) => (n.pinjamanKe || 1) <= 1;

    // Filter active nasabah (same as storting global)
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOffset);
    const threeMonthsAgo = new Date(wib.getFullYear(), wib.getMonth() - 3, 1);

    // Group per admin (resort)
    const rows = [];

    for (const adm of admins) {
      const resortNasabah = allNasabah.filter(n => n.adminUid === adm.uid);

      // Nasabah yang dicairkan hari ini (drop hari ini)
      const droppedToday = resortNasabah.filter(n => {
        const tglCair = (n.tanggalPencairan || '').trim();
        return tglCair === todayStr;
      });

      // Pisahkan drop baru vs lama
      const dropBaruList = droppedToday.filter(n => isDropBaru(n));
      const dropLamaList = droppedToday.filter(n => !isDropBaru(n));

      const dropBaruCount = dropBaruList.length;
      const dropLamaCount = dropLamaList.length;

      // Nominal drop
      const nominalDropBaru = dropBaruList.reduce((s, n) => s + (n.besarPinjaman || 0), 0);
      const nominalDropLama = dropLamaList.reduce((s, n) => s + (n.besarPinjaman || 0), 0);
      const totalDrop = nominalDropBaru + nominalDropLama;

      // Target = sum of besarPinjaman * 3% for eligible nasabah
      let target = 0;
      resortNasabah.forEach(n => {
        // Exclude menunggu pencairan
        const sk = (n.statusKhusus || '').toUpperCase().replace(/ /g, '_');
        if (sk === 'MENUNGGU_PENCAIRAN') return;
        // Exclude > 3 bulan
        const tglAcuan = (n.tanggalPencairan || '').trim() || (n.tanggalPengajuan || '').trim() || (n.tanggalDaftar || '').trim();
        const acuanDate = parseDateStr(tglAcuan);
        if (acuanDate && acuanDate < threeMonthsAgo) return;
        // Exclude cair hari ini
        if ((n.tanggalPencairan || '').trim() === todayStr) return;
        // Exclude sudah lunas
        if (n.totalPelunasan > 0 && n.sisaUtang <= 0) return;

        target += Math.round((n.besarPinjaman || 0) * 0.03);
      });

      // Storting = total pembayaran hari ini
      let storting = 0;
      resortNasabah.forEach(n => {
        const pay = n.pembayaran?.[todayStr];
        if (pay) {
          storting += pay.total || 0;
        }
      });

      // Persen = storting / target * 100
      const persen = target > 0 ? Math.round(storting / target * 100) : 0;

      // Admin = 5% dari total besar pinjaman hari ini (drop hari ini)
      const adminFee = Math.round(totalDrop * 0.05);

      // Tabungan = 5% dari total besar pinjaman hari ini
      const tabungan = Math.round(totalDrop * 0.05);

      // Debit asli = Storting + Admin + Tabungan
      const debitAsli = storting + adminFee + tabungan;

      // Pencairan Tabungan
      const pencairanTabungan = 0;

      // Kredit = Total Drop + Pencairan Tabungan
      const kredit = totalDrop + pencairanTabungan;

      // Kas Pakai & Tunai Pasar:
      // Jika kredit > debit asli, kas pakai = selisih, debit disamakan dengan kredit, tunai pasar = 0
      // Jika debit asli >= kredit, kas pakai = 0, debit tetap, tunai pasar = debit - kredit
      const kasPakai = kredit > debitAsli ? kredit - debitAsli : 0;
      const debit = kredit > debitAsli ? kredit : debitAsli;
      const tunaiPasar = debitAsli >= kredit ? debitAsli - kredit : 0;

      rows.push({
        resortName: adm.name,
        dropBaru: dropBaruCount,
        dropLama: dropLamaCount,
        target,
        kasPakai,
        storting,
        persen,
        adminFee,
        tabungan,
        debit,
        nominalDropBaru,
        nominalDropLama,
        totalDrop,
        pencairanTabungan,
        kredit,
        tunaiPasar,
      });
    }

    return rows;
  };

  const rekapRows = computeRekapRows(currentDate);

  // Totals
  const totals = rekapRows.reduce((acc, r) => ({
    dropBaru: acc.dropBaru + r.dropBaru,
    dropLama: acc.dropLama + r.dropLama,
    target: acc.target + r.target,
    kasPakai: acc.kasPakai + r.kasPakai,
    storting: acc.storting + r.storting,
    adminFee: acc.adminFee + r.adminFee,
    tabungan: acc.tabungan + r.tabungan,
    debit: acc.debit + r.debit,
    nominalDropBaru: acc.nominalDropBaru + r.nominalDropBaru,
    nominalDropLama: acc.nominalDropLama + r.nominalDropLama,
    totalDrop: acc.totalDrop + r.totalDrop,
    pencairanTabungan: acc.pencairanTabungan + r.pencairanTabungan,
    kredit: acc.kredit + r.kredit,
    tunaiPasar: acc.tunaiPasar + r.tunaiPasar,
  }), {
    dropBaru: 0, dropLama: 0, target: 0, kasPakai: 0, storting: 0,
    adminFee: 0, tabungan: 0, debit: 0, nominalDropBaru: 0, nominalDropLama: 0,
    totalDrop: 0, pencairanTabungan: 0, kredit: 0, tunaiPasar: 0,
  });
  const totalPersen = totals.target > 0 ? Math.round(totals.storting / totals.target * 100) : 0;

  // ==================== TOTAL KEMARIN & GABUNGAN ====================
  // previousDate = tanggal sebelumnya di daftar, hanya jika bulan sama (awal bulan = tidak ada kemarin)
  const currentDateIdx = dates.indexOf(currentDate);
  const previousDate = (currentDateIdx >= 0 && currentDateIdx < dates.length - 1)
    ? dates[currentDateIdx + 1]
    : null;
  const showKemarin = (() => {
    if (!previousDate || !currentDate) return false;
    const p1 = currentDate.split(' ');   // ["04", "Apr", "2026"]
    const p2 = previousDate.split(' ');  // ["03", "Apr", "2026"]
    return p1.length === 3 && p2.length === 3 && p1[1] === p2[1] && p1[2] === p2[2];
  })();

  const rekapRowsKemarin = showKemarin ? computeRekapRows(previousDate) : [];
  const totalsKemarin = rekapRowsKemarin.reduce((acc, r) => ({
    dropBaru: acc.dropBaru + r.dropBaru,
    dropLama: acc.dropLama + r.dropLama,
    target: acc.target + r.target,
    kasPakai: acc.kasPakai + r.kasPakai,
    storting: acc.storting + r.storting,
    adminFee: acc.adminFee + r.adminFee,
    tabungan: acc.tabungan + r.tabungan,
    debit: acc.debit + r.debit,
    nominalDropBaru: acc.nominalDropBaru + r.nominalDropBaru,
    nominalDropLama: acc.nominalDropLama + r.nominalDropLama,
    totalDrop: acc.totalDrop + r.totalDrop,
    pencairanTabungan: acc.pencairanTabungan + r.pencairanTabungan,
    kredit: acc.kredit + r.kredit,
    tunaiPasar: acc.tunaiPasar + r.tunaiPasar,
  }), {
    dropBaru: 0, dropLama: 0, target: 0, kasPakai: 0, storting: 0,
    adminFee: 0, tabungan: 0, debit: 0, nominalDropBaru: 0, nominalDropLama: 0,
    totalDrop: 0, pencairanTabungan: 0, kredit: 0, tunaiPasar: 0,
  });
  const totalPersenKemarin = totalsKemarin.target > 0 ? Math.round(totalsKemarin.storting / totalsKemarin.target * 100) : 0;

  const totalsGabungan = {
    dropBaru: totals.dropBaru + totalsKemarin.dropBaru,
    dropLama: totals.dropLama + totalsKemarin.dropLama,
    target: totals.target + totalsKemarin.target,
    kasPakai: totals.kasPakai + totalsKemarin.kasPakai,
    storting: totals.storting + totalsKemarin.storting,
    adminFee: totals.adminFee + totalsKemarin.adminFee,
    tabungan: totals.tabungan + totalsKemarin.tabungan,
    debit: totals.debit + totalsKemarin.debit,
    nominalDropBaru: totals.nominalDropBaru + totalsKemarin.nominalDropBaru,
    nominalDropLama: totals.nominalDropLama + totalsKemarin.nominalDropLama,
    totalDrop: totals.totalDrop + totalsKemarin.totalDrop,
    pencairanTabungan: totals.pencairanTabungan + totalsKemarin.pencairanTabungan,
    kredit: totals.kredit + totalsKemarin.kredit,
    tunaiPasar: totals.tunaiPasar + totalsKemarin.tunaiPasar,
  };
  const totalPersenGabungan = totalsGabungan.target > 0 ? Math.round(totalsGabungan.storting / totalsGabungan.target * 100) : 0;

  const thStyle = { padding: '8px 6px', textAlign: 'center', fontWeight: 700, fontSize: 10, whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2, borderBottom: '2px solid var(--border)' };
  const tdStyle = { padding: '6px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11 };
  const tdNameStyle = { padding: '6px 8px', textAlign: 'left', fontWeight: 600, fontSize: 12, whiteSpace: 'nowrap' };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Buku Rekap</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}{currentDate ? ` — ${currentDate}` : ''}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="bukuRekap" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        {/* Cabang selector for kasir_wilayah */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 16 }}>
            <select value={activeCabang?.id || ''} onChange={(e) => setActiveCabang(cabangList.find(c => c.id === e.target.value))}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>
        )}

        {/* Tab tanggal — 7 hari terakhir */}
        {!loading && dates.length > 0 && (
          <div style={{ display: 'flex', gap: 6, marginBottom: 16, overflowX: 'auto', paddingBottom: 4 }}>
            {dates.map(d => {
              const isActive = currentDate === d;
              return (
                <button key={d} onClick={() => setSelectedDate(d)} style={{
                  padding: '6px 14px', borderRadius: 20, fontSize: 12, fontWeight: 600,
                  whiteSpace: 'nowrap', cursor: 'pointer',
                  border: `1px solid ${isActive ? 'var(--primary)' : 'var(--border)'}`,
                  background: isActive ? 'var(--primary)' : 'var(--card)',
                  color: isActive ? '#fff' : 'var(--text)',
                  transition: 'all 0.15s',
                }}>
                  {d.slice(0, 6)}
                </button>
              );
            })}
          </div>
        )}

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Buku Rekap...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : rekapRows.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Tidak ada data resort</p>
          </div>
        ) : (
          <>
            {/* Summary cards */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 10, marginBottom: 20 }}>
              <div style={{ background: '#e8f8f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Total Storting</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--success)' }}>{formatRpFull(totals.storting)}</p>
              </div>
              <div style={{ background: 'var(--primary-light)', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Target</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--primary)' }}>{formatRpFull(totals.target)}</p>
              </div>
              <div style={{ background: '#fef2f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Total Drop</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--danger)' }}>{formatRpFull(totals.totalDrop)}</p>
              </div>
              <div style={{ background: '#f0f4ff', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Tunai Pasar</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: totals.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRpFull(totals.tunaiPasar)}</p>
              </div>
            </div>

            {/* Table */}
            <div style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--card)' }}>
              <table style={{ width: '100%', minWidth: 1200, fontSize: 12, borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ background: '#f8f9fa' }}>
                    <th style={{ ...thStyle, textAlign: 'left', paddingLeft: 10 }}>Resort</th>
                    <th style={{ ...thStyle, background: '#e8f8f0' }}>Drop Baru</th>
                    <th style={{ ...thStyle, background: '#e8f8f0' }}>Drop Lama</th>
                    <th style={thStyle}>Target</th>
                    <th style={thStyle}>Kas Pakai</th>
                    <th style={{ ...thStyle, background: '#dff5eb' }}>Storting</th>
                    <th style={{ ...thStyle, background: '#dff5eb' }}>%</th>
                    <th style={thStyle}>Admin</th>
                    <th style={thStyle}>Tabungan</th>
                    <th style={{ ...thStyle, background: '#e0ecff' }}>Debit</th>
                    <th style={{ ...thStyle, background: '#fef2f0' }}>Drop Baru (Rp)</th>
                    <th style={{ ...thStyle, background: '#fef2f0' }}>Drop Lama (Rp)</th>
                    <th style={{ ...thStyle, background: '#fef2f0' }}>Total Drop</th>
                    <th style={thStyle}>Cair Tab.</th>
                    <th style={{ ...thStyle, background: '#fff3e0' }}>Kredit</th>
                    <th style={{ ...thStyle, background: '#f3e8ff' }}>Tunai Pasar</th>
                  </tr>
                </thead>
                <tbody>
                  {rekapRows.map((row, idx) => (
                    <tr key={idx} style={{ borderBottom: '1px solid var(--border-light)' }}>
                      <td style={tdNameStyle}>{row.resortName}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 600 }}>{row.dropBaru || '-'}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 600 }}>{row.dropLama || '-'}</td>
                      <td style={tdStyle}>{row.target > 0 ? formatRp(row.target) : '-'}</td>
                      <td style={tdStyle}>{row.kasPakai > 0 ? formatRp(row.kasPakai) : '-'}</td>
                      <td style={{ ...tdStyle, color: 'var(--success)', fontWeight: 600 }}>{row.storting > 0 ? formatRp(row.storting) : '-'}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 700, color: row.persen >= 100 ? 'var(--success)' : row.persen >= 50 ? '#b8860b' : 'var(--danger)' }}>{row.persen > 0 ? `${row.persen}%` : '-'}</td>
                      <td style={tdStyle}>{row.adminFee > 0 ? formatRp(row.adminFee) : '-'}</td>
                      <td style={tdStyle}>{row.tabungan > 0 ? formatRp(row.tabungan) : '-'}</td>
                      <td style={{ ...tdStyle, color: '#1a56db', fontWeight: 600 }}>{row.debit > 0 ? formatRp(row.debit) : '-'}</td>
                      <td style={{ ...tdStyle, color: 'var(--danger)' }}>{row.nominalDropBaru > 0 ? formatRp(row.nominalDropBaru) : '-'}</td>
                      <td style={{ ...tdStyle, color: 'var(--danger)' }}>{row.nominalDropLama > 0 ? formatRp(row.nominalDropLama) : '-'}</td>
                      <td style={{ ...tdStyle, color: 'var(--danger)', fontWeight: 600 }}>{row.totalDrop > 0 ? formatRp(row.totalDrop) : '-'}</td>
                      <td style={tdStyle}>{row.pencairanTabungan > 0 ? formatRp(row.pencairanTabungan) : '-'}</td>
                      <td style={{ ...tdStyle, color: '#d97706', fontWeight: 600 }}>{row.kredit > 0 ? formatRp(row.kredit) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 700, color: row.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{row.tunaiPasar !== 0 ? formatRp(row.tunaiPasar) : '-'}</td>
                    </tr>
                  ))}
                  {/* TOTAL HARI INI ROW */}
                  <tr style={{ borderTop: '2px solid var(--border)', background: '#eff6ff', fontWeight: 800 }}>
                    <td style={{ ...tdNameStyle, fontWeight: 800, color: '#1a56db' }}>Total Hari Ini</td>
                    <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totals.dropBaru}</td>
                    <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totals.dropLama}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totals.target)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{totals.kasPakai > 0 ? formatRp(totals.kasPakai) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--success)' }}>{formatRp(totals.storting)}</td>
                    <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalPersen}%</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totals.adminFee)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totals.tabungan)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: '#1a56db' }}>{formatRp(totals.debit)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totals.nominalDropBaru)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totals.nominalDropLama)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totals.totalDrop)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{totals.pencairanTabungan > 0 ? formatRp(totals.pencairanTabungan) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: '#d97706' }}>{formatRp(totals.kredit)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: totals.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totals.tunaiPasar)}</td>
                  </tr>
                  {/* TOTAL KEMARIN ROW — hanya jika tanggal sebelumnya masih dalam bulan yang sama */}
                  {showKemarin && (
                    <tr style={{ borderTop: '1px solid var(--border-light)', background: '#f8f9fa', fontWeight: 800 }}>
                      <td style={{ ...tdNameStyle, fontWeight: 800, color: '#1e293b' }}>Total Kemarin</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalsKemarin.dropBaru}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalsKemarin.dropLama}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsKemarin.target)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsKemarin.kasPakai > 0 ? formatRp(totalsKemarin.kasPakai) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--success)' }}>{formatRp(totalsKemarin.storting)}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalPersenKemarin}%</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsKemarin.adminFee)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsKemarin.tabungan)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: '#1a56db' }}>{formatRp(totalsKemarin.debit)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsKemarin.nominalDropBaru)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsKemarin.nominalDropLama)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsKemarin.totalDrop)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsKemarin.pencairanTabungan > 0 ? formatRp(totalsKemarin.pencairanTabungan) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: '#d97706' }}>{formatRp(totalsKemarin.kredit)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: totalsKemarin.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totalsKemarin.tunaiPasar)}</td>
                    </tr>
                  )}
                  {/* TOTAL (HARI INI + KEMARIN) ROW */}
                  {showKemarin && (
                    <tr style={{ borderTop: '2px solid #fca5a5', background: '#fff1f1', fontWeight: 800 }}>
                      <td style={{ ...tdNameStyle, fontWeight: 800, color: 'var(--danger)' }}>Total</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalsGabungan.dropBaru}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalsGabungan.dropLama}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsGabungan.target)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsGabungan.kasPakai > 0 ? formatRp(totalsGabungan.kasPakai) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--success)' }}>{formatRp(totalsGabungan.storting)}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalPersenGabungan}%</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsGabungan.adminFee)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsGabungan.tabungan)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: '#1a56db' }}>{formatRp(totalsGabungan.debit)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsGabungan.nominalDropBaru)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsGabungan.nominalDropLama)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsGabungan.totalDrop)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsGabungan.pencairanTabungan > 0 ? formatRp(totalsGabungan.pencairanTabungan) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: '#d97706' }}>{formatRp(totalsGabungan.kredit)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: totalsGabungan.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totalsGabungan.tunaiPasar)}</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </>
        )}
      </main>
    </div>
  );
}

// ============================================================
// KAS PENUNTUN SCREEN
// ============================================================
function KasPenuntunScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(cabang || cabangList[0] || null);
  const [bulan, setBulan] = useState(getCurrentMonthKey());
  const [bukuData, setBukuData] = useState(null);
  const [kasirEntries, setKasirEntries] = useState([]);
  const [prevKasirEntries, setPrevKasirEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showFaktur, setShowFaktur] = useState(null);

  const bulanOptions = generateBulanOptions();

  // Compute previous month key for Saldo Kas Bulan Lalu
  const prevBulan = (() => {
    const [y, m] = bulan.split('-');
    const d = new Date(parseInt(y), parseInt(m) - 2, 1);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  })();

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    Promise.all([
      getBukuPokok({ cabangId: activeCabang.id, adminUid: '', status: 'semua' }),
      getKasirEntries({ cabangId: activeCabang.id, bulan }),
      getKasirEntries({ cabangId: activeCabang.id, bulan: prevBulan }),
    ]).then(([bukuResult, kasirResult, prevKasirResult]) => {
      if (bukuResult.success && bukuResult.type === 'buku_pokok') {
        setBukuData(bukuResult.data);
      }
      if (kasirResult.success) {
        setKasirEntries(kasirResult.data.entries || []);
      }
      if (prevKasirResult.success) {
        setPrevKasirEntries(prevKasirResult.data.entries || []);
      } else {
        setPrevKasirEntries([]);
      }
    }).catch(err => {
      setError('Gagal memuat data: ' + err.message);
    }).finally(() => setLoading(false));
  }, [activeCabang?.id, bulan]);

  // ==================== COMPUTE KAS PENUNTUN ROWS ====================
  const penuntunRows = (() => {
    if (!bukuData?.nasabah) return [];

    const allNasabah = bukuData.nasabah;

    const BULAN_MAP_REV = {};
    BULAN_INDO.forEach((b, i) => { BULAN_MAP_REV[b] = i; });
    const parseDateStr = (s) => {
      if (!s) return null;
      const parts = s.split(' ');
      if (parts.length !== 3) return null;
      const m = BULAN_MAP_REV[parts[1]];
      if (m === undefined) return null;
      return new Date(parseInt(parts[2]), m, parseInt(parts[0]));
    };

    // Helper: hitung tunaiPasar & kasPakai per tanggal dari data nasabah
    const computeTunaiKasPerDate = (dateStr) => {
      let totalStorting = 0, totalDrop = 0;
      allNasabah.forEach(n => {
        const pay = n.pembayaran?.[dateStr];
        if (pay) totalStorting += pay.total || 0;
        if ((n.tanggalPencairan || '').trim() === dateStr) totalDrop += n.besarPinjaman || 0;
      });
      const adminFee = Math.round(totalDrop * 0.05);
      const tabungan = Math.round(totalDrop * 0.05);
      const debitAsli = totalStorting + adminFee + tabungan;
      const kreditVal = totalDrop;
      return {
        tunaiPasar: debitAsli >= kreditVal ? debitAsli - kreditVal : 0,
        kasPakai: kreditVal > debitAsli ? kreditVal - debitAsli : 0,
      };
    };

    // ===== Compute Saldo Kas Bulan Lalu (dari data bulan sebelumnya) =====
    const [yyyy, mm] = bulan.split('-');
    const prevMonthStart = new Date(parseInt(yyyy), parseInt(mm) - 2, 1);
    const prevMonthEnd = new Date(parseInt(yyyy), parseInt(mm) - 1, 0);

    let saldoKasBulanLalu = 0;
    {
      // Kumpulkan tanggal-tanggal bulan sebelumnya
      const prevDateSet = new Set();
      allNasabah.forEach(n => {
        if (n.pembayaran) {
          Object.keys(n.pembayaran).forEach(d => {
            const date = parseDateStr(d);
            if (date && date >= prevMonthStart && date <= prevMonthEnd) prevDateSet.add(d);
          });
        }
        const tglCair = (n.tanggalPencairan || '').trim();
        if (tglCair) {
          const date = parseDateStr(tglCair);
          if (date && date >= prevMonthStart && date <= prevMonthEnd) prevDateSet.add(tglCair);
        }
      });
      prevKasirEntries.forEach(e => {
        const tgl = e.tanggal;
        if (!tgl) return;
        const date = parseDateStr(tgl);
        if (date && date >= prevMonthStart && date <= prevMonthEnd) prevDateSet.add(tgl);
      });

      const prevSortedDates = Array.from(prevDateSet)
        .filter(d => { const dt = parseDateStr(d); return dt && isHariKerja(dt); })
        .sort((a, b) => parseDateStr(a) - parseDateStr(b));

      // Hitung saldo bulan lalu menggunakan logika akumulasi yang sama
      let prevRunning = 0;
      let prevTunaiAccum = 0;
      prevSortedDates.forEach(dateStr => {
        const { tunaiPasar, kasPakai } = computeTunaiKasPerDate(dateStr);

        let suntikan = 0, pinjaman = 0, buVal = 0;
        prevKasirEntries.forEach(e => {
          if (e.tanggal !== dateStr) return;
          if (e.jenis === 'suntikan_dana' && e.arah === 'masuk') suntikan += e.jumlah || 0;
          if (e.jenis === 'pinjaman_kas' && e.arah === 'masuk') pinjaman += e.jumlah || 0;
          if (e.jenis === 'penggajian' && e.arah === 'keluar') {
            const buku = e.targetBuku;
            if (!buku || (Array.isArray(buku) && buku.includes('kas_penuntun'))) buVal += e.jumlah || 0;
          }
        });

        const dropPusat = suntikan + pinjaman;
        const saldoKas = prevRunning + dropPusat;
        const tunaiPasarTotal = tunaiPasar + prevTunaiAccum;
        const debit = tunaiPasarTotal + saldoKas;
        const kredit = kasPakai + buVal;
        const saldoAkhir = debit - kredit;

        prevRunning = saldoAkhir;
        prevTunaiAccum = tunaiPasarTotal;
      });

      saldoKasBulanLalu = prevRunning;
    }

    // ===== Data bulan ini =====
    const monthStart = new Date(parseInt(yyyy), parseInt(mm) - 1, 1);
    const monthEnd = new Date(parseInt(yyyy), parseInt(mm), 0);

    // Batas: tidak boleh lebih dari hari ini (WIB)
    const nowKP = new Date();
    const wibOffKP = 7 * 60 * 60 * 1000;
    const wibKP = new Date(nowKP.getTime() + (nowKP.getTimezoneOffset() * 60000) + wibOffKP);
    const todayLimit = new Date(wibKP.getFullYear(), wibKP.getMonth(), wibKP.getDate());
    const effectiveEnd = monthEnd <= todayLimit ? monthEnd : todayLimit;

    const dateSet = new Set();
    allNasabah.forEach(n => {
      if (n.pembayaran) {
        Object.keys(n.pembayaran).forEach(d => {
          const date = parseDateStr(d);
          if (date && date >= monthStart && date <= effectiveEnd) dateSet.add(d);
        });
      }
      const tglCair = (n.tanggalPencairan || '').trim();
      if (tglCair) {
        const date = parseDateStr(tglCair);
        if (date && date >= monthStart && date <= effectiveEnd) dateSet.add(tglCair);
      }
    });

    kasirEntries.forEach(e => {
      const tgl = e.tanggal;
      if (!tgl) return;
      const date = parseDateStr(tgl);
      if (date && date >= monthStart && date <= effectiveEnd) dateSet.add(tgl);
    });

    const sortedDates = Array.from(dateSet)
      .filter(d => { const dt = parseDateStr(d); return dt && isHariKerja(dt); })
      .sort((a, b) => parseDateStr(a) - parseDateStr(b));

    // Hitung tunaiPasar & kasPakai per tanggal
    const tunaiPasarPerDate = {};
    const kasPakaiPerDate = {};
    sortedDates.forEach(dateStr => {
      const { tunaiPasar, kasPakai } = computeTunaiKasPerDate(dateStr);
      tunaiPasarPerDate[dateStr] = tunaiPasar;
      kasPakaiPerDate[dateStr] = kasPakai;
    });

    // Kasir entries: suntikan, pinjaman, BU per tanggal
    const suntikanDanaPerDate = {};
    const pinjamanKasPerDate = {};
    const buPerDate = {};
    const buFakturPerDate = {};
    kasirEntries.forEach(e => {
      const tgl = e.tanggal;
      if (!tgl) return;
      if (e.jenis === 'suntikan_dana' && e.arah === 'masuk') {
        suntikanDanaPerDate[tgl] = (suntikanDanaPerDate[tgl] || 0) + (e.jumlah || 0);
      }
      if (e.jenis === 'pinjaman_kas' && e.arah === 'masuk') {
        pinjamanKasPerDate[tgl] = (pinjamanKasPerDate[tgl] || 0) + (e.jumlah || 0);
      }
      if (e.jenis === 'penggajian' && e.arah === 'keluar') {
        // Hanya hitung BU yang targetnya kas_penuntun, atau entry lama tanpa targetBuku
        const buku = e.targetBuku;
        if (!buku || (Array.isArray(buku) && buku.includes('kas_penuntun'))) {
          buPerDate[tgl] = (buPerDate[tgl] || 0) + (e.jumlah || 0);
          if (!buFakturPerDate[tgl]) buFakturPerDate[tgl] = [];
          buFakturPerDate[tgl].push({ jumlah: e.jumlah || 0, fakturUrl: e.fakturUrl || null, keterangan: e.keterangan || '' });
        }
      }
    });

    // ===== Build result dengan 3-baris untuk Debit, Kas Pakai, Kredit, Saldo Kas =====
    const result = [];
    let tunaiPasarAccum = 0;
    let prevDebitTotal = 0;
    let prevKasPakaiTotal = 0;

    sortedDates.forEach((dateStr, idx) => {
      const tunaiPasarHariIni = tunaiPasarPerDate[dateStr] || 0;
      const suntikanDana = suntikanDanaPerDate[dateStr] || 0;
      const pinjamanKas = pinjamanKasPerDate[dateStr] || 0;
      const kasPakaiHariIni = kasPakaiPerDate[dateStr] || 0;
      const bu = buPerDate[dateStr] || 0;
      const buFaktur = buFakturPerDate[dateStr] || [];

      const tunaiPasarKemarin = tunaiPasarAccum;
      const tunaiPasarTotal = tunaiPasarHariIni + tunaiPasarKemarin;

      let debitR1, debitR2, debitR3;
      let kasPakaiR1, kasPakaiR2, kasPakaiR3;
      let kreditR1, kreditR2, kreditR3;
      let saldoKasR1, saldoKasR2, saldoKasR3;

      if (idx === 0) {
        // Tanggal pertama bulan ini
        debitR1 = tunaiPasarHariIni + saldoKasBulanLalu;
        debitR2 = null;
        debitR3 = null;

        kasPakaiR1 = kasPakaiHariIni;
        kasPakaiR2 = null;
        kasPakaiR3 = null;

        kreditR1 = kasPakaiR1 + bu;
        kreditR2 = null;
        kreditR3 = null;

        saldoKasR1 = debitR1 - kreditR1;
        saldoKasR2 = null;
        saldoKasR3 = null;

        prevDebitTotal = debitR1;
        prevKasPakaiTotal = kasPakaiR1;
      } else {
        // Tanggal berikutnya
        debitR1 = tunaiPasarHariIni;
        debitR2 = prevDebitTotal;
        debitR3 = debitR1 + debitR2;

        kasPakaiR1 = kasPakaiHariIni;
        kasPakaiR2 = prevKasPakaiTotal;
        kasPakaiR3 = kasPakaiR1 + kasPakaiR2;

        kreditR1 = kasPakaiR1 + bu;
        kreditR2 = kasPakaiR2;
        kreditR3 = kreditR1 + kreditR2;

        saldoKasR1 = debitR1 - kreditR1;
        saldoKasR2 = debitR2 - kreditR2;
        saldoKasR3 = saldoKasR1 + saldoKasR2;

        prevDebitTotal = debitR3;
        prevKasPakaiTotal = kasPakaiR3;
      }

      result.push({
        tanggal: dateStr,
        tunaiPasarHariIni, tunaiPasarKemarin, tunaiPasarTotal,
        suntikanDana, pinjamanKas,
        saldoKasBulanLalu,
        debitR1, debitR2, debitR3,
        kasPakaiR1, kasPakaiR2, kasPakaiR3,
        shu: 0, bu, buFaktur,
        kreditR1, kreditR2, kreditR3,
        saldoKasR1, saldoKasR2, saldoKasR3,
      });

      tunaiPasarAccum = tunaiPasarTotal;
    });

    return result;
  })();

  const thS = { padding: '7px 6px', textAlign: 'center', fontWeight: 700, fontSize: 10, whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2, borderBottom: '2px solid var(--border)', borderRight: '1px solid var(--border)' };
  const tdR = { padding: '5px 6px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11, borderRight: '1px solid var(--border-light)' };
  const rowBorderBottom = { borderBottom: '2px solid var(--border)' };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Kas Penuntun</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'} — {formatBulanLabel(bulan)}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="kasPenuntun" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
          {!isUnit && cabangList.length > 1 && (
            <select value={activeCabang?.id || ''} onChange={(e) => setActiveCabang(cabangList.find(c => c.id === e.target.value))}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          <select value={bulan} onChange={(e) => setBulan(e.target.value)}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
        </div>

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Kas Penuntun...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : penuntunRows.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Tidak ada data untuk bulan {formatBulanLabel(bulan)}</p>
          </div>
        ) : (
          <div style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--card)' }}>
            <table style={{ width: '100%', minWidth: 1100, fontSize: 12, borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ background: '#f8f9fa' }}>
                  <th style={{ ...thS, textAlign: 'left', paddingLeft: 10 }} rowSpan={2}>Tanggal</th>
                  <th style={{ ...thS, background: '#e8f8f0' }} colSpan={1}>Tunai Pasar</th>
                  <th style={{ ...thS, background: '#e0f0ff' }} rowSpan={2}>Suntikan Dana</th>
                  <th style={{ ...thS, background: '#e0f0ff' }} rowSpan={2}>Pinjaman Kas</th>
                  <th style={{ ...thS }} rowSpan={2}>Saldo Kas Bulan Lalu</th>
                  <th style={{ ...thS, background: '#dbeafe' }} colSpan={1}>Debit</th>
                  <th style={{ ...thS, background: '#fef9c3' }} colSpan={1}>Kas Pakai</th>
                  <th style={{ ...thS }} rowSpan={2}>SHU/SP</th>
                  <th style={{ ...thS, background: '#ffe4e6' }} rowSpan={2}>BU</th>
                  <th style={{ ...thS, background: '#fde8c8' }} colSpan={1}>Kredit</th>
                  <th style={{ ...thS, background: '#f3e8ff' }} colSpan={1}>Saldo Kas</th>
                </tr>
                <tr style={{ background: '#f8f9fa' }}>
                  <th style={{ ...thS, background: '#e8f8f0', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                  <th style={{ ...thS, background: '#dbeafe', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                  <th style={{ ...thS, background: '#fef9c3', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                  <th style={{ ...thS, background: '#fde8c8', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                  <th style={{ ...thS, background: '#f3e8ff', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                </tr>
              </thead>
              <tbody>
                {penuntunRows.map((row) => (
                  <>
                    <tr key={`${row.tanggal}-r1`} style={{ borderTop: '2px solid var(--border)' }}>
                      <td rowSpan={3} style={{ padding: '6px 10px', fontWeight: 700, fontSize: 12, verticalAlign: 'middle', borderRight: '1px solid var(--border)', whiteSpace: 'nowrap', background: '#fafafa' }}>
                        {row.tanggal.slice(0, 6)}
                      </td>
                      <td style={{ ...tdR, background: '#f0fdf4', color: row.tunaiPasarHariIni >= 0 ? '#166534' : 'var(--danger)', fontWeight: 600 }}>
                        {row.tunaiPasarHariIni !== 0 ? formatRp(row.tunaiPasarHariIni) : '-'}
                      </td>
                      <td rowSpan={3} style={{ ...tdR, background: '#eff6ff' }}>{row.suntikanDana > 0 ? formatRp(row.suntikanDana) : '-'}</td>
                      <td rowSpan={3} style={{ ...tdR, background: '#eff6ff' }}>{row.pinjamanKas > 0 ? formatRp(row.pinjamanKas) : '-'}</td>
                      <td rowSpan={3} style={{ ...tdR }}>{row.saldoKasBulanLalu !== 0 ? formatRp(row.saldoKasBulanLalu) : '-'}</td>
                      <td style={{ ...tdR, background: '#eff6ff', fontWeight: 600, color: '#1d4ed8' }}>
                        {row.debitR1 !== 0 ? formatRp(row.debitR1) : '-'}
                      </td>
                      <td style={{ ...tdR, background: '#fefce8' }}>
                        {row.kasPakaiR1 > 0 ? formatRp(row.kasPakaiR1) : '-'}
                      </td>
                      <td rowSpan={3} style={{ ...tdR }}>-</td>
                      <td rowSpan={3} style={{ ...tdR, background: '#fff1f2', cursor: row.bu > 0 ? 'pointer' : 'default', textDecoration: row.bu > 0 ? 'underline' : 'none' }}
                        onClick={() => { if (row.bu > 0 && row.buFaktur?.length > 0) setShowFaktur(row.buFaktur); }}
                      >{row.bu > 0 ? formatRp(row.bu) : '-'}</td>
                      <td style={{ ...tdR, background: '#fff7ed', color: '#b45309', fontWeight: 600 }}>
                        {row.kreditR1 > 0 ? formatRp(row.kreditR1) : '-'}
                      </td>
                      <td style={{ ...tdR, background: '#faf5ff', fontWeight: 700, color: row.saldoKasR1 >= 0 ? '#7e22ce' : 'var(--danger)' }}>
                        {row.saldoKasR1 !== 0 ? formatRp(row.saldoKasR1) : '-'}
                      </td>
                    </tr>
                    <tr key={`${row.tanggal}-r2`}>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.tunaiPasarKemarin !== 0 ? formatRp(row.tunaiPasarKemarin) : '-'}
                      </td>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.debitR2 != null ? formatRp(row.debitR2) : '-'}
                      </td>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.kasPakaiR2 != null && row.kasPakaiR2 > 0 ? formatRp(row.kasPakaiR2) : '-'}
                      </td>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.kreditR2 != null && row.kreditR2 > 0 ? formatRp(row.kreditR2) : '-'}
                      </td>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.saldoKasR2 != null ? formatRp(row.saldoKasR2) : '-'}
                      </td>
                    </tr>
                    <tr key={`${row.tanggal}-r3`} style={rowBorderBottom}>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)' }}>
                        {formatRp(row.tunaiPasarTotal)}
                      </td>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)', color: '#1d4ed8' }}>
                        {row.debitR3 != null ? formatRp(row.debitR3) : '-'}
                      </td>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)' }}>
                        {row.kasPakaiR3 != null && row.kasPakaiR3 > 0 ? formatRp(row.kasPakaiR3) : '-'}
                      </td>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)', color: '#b45309' }}>
                        {row.kreditR3 != null && row.kreditR3 > 0 ? formatRp(row.kreditR3) : '-'}
                      </td>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)', color: row.saldoKasR3 != null && row.saldoKasR3 >= 0 ? '#7e22ce' : 'var(--danger)' }}>
                        {row.saldoKasR3 != null ? formatRp(row.saldoKasR3) : '-'}
                      </td>
                    </tr>
                  </>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </main>

      {/* Faktur Modal */}
      {showFaktur && <FakturModal fakturList={showFaktur} onClose={() => setShowFaktur(null)} />}
    </div>
  );
}


// ============================================================
// BUKU TUNAI SCREEN
// Kolom: Nama Resort | Kasbon Pagi | Kas Pakai | Kembali Kasbon | Tunai Pasar | Titipan | +/-
// Data: getBukuPokok (nasabah) + getKasirEntries (uang_kas per admin)
// ============================================================
function BukuTunaiScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(cabang || cabangList[0] || null);
  const [bukuData, setBukuData] = useState(null);
  const [kasirEntries, setKasirEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedDate, setSelectedDate] = useState(null);

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    setBukuData(null);
    setKasirEntries([]);
    setSelectedDate(null);

    // Tentukan bulan-bulan yang perlu di-fetch untuk kasir entries
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
    const wib = new Date(utc + wibOffset);
    const bulanCurrent = `${wib.getFullYear()}-${String(wib.getMonth() + 1).padStart(2, '0')}`;
    // Fetch bulan sebelumnya hanya jika tanggal <= 10 (7 hari kerja bisa lintas bulan)
    const needPrevMonth = wib.getDate() <= 10;
    const bulanPrev = (() => {
      const d = new Date(wib.getFullYear(), wib.getMonth() - 1, 1);
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    })();

    const promises = [
      getBukuPokok({ cabangId: activeCabang.id, adminUid: '', status: 'aktif' }),
      getKasirEntries({ cabangId: activeCabang.id, bulan: bulanCurrent }),
    ];
    if (needPrevMonth) {
      promises.push(getKasirEntries({ cabangId: activeCabang.id, bulan: bulanPrev }));
    }

    Promise.all(promises)
      .then(([bukuResult, kasirCurrent, kasirPrev]) => {
        if (bukuResult.success && bukuResult.type === 'buku_pokok') {
          setBukuData(bukuResult.data);
        }
        const allEntries = [
          ...(kasirCurrent?.success ? kasirCurrent.data.entries || [] : []),
          ...(kasirPrev?.success ? kasirPrev?.data?.entries || [] : []),
        ];
        setKasirEntries(allEntries);
      })
      .catch(err => setError('Gagal memuat data: ' + err.message))
      .finally(() => setLoading(false));
  }, [activeCabang?.id]);

  // 7 tanggal terakhir dari buku pokok
  const dates = (bukuData?.tanggalList || []).slice(0, 7);
  const currentDate = selectedDate || dates[0] || null;

  // Helper parse tanggal "07 Feb 2026"
  const BULAN_MAP_REV_T = {};
  BULAN_INDO.forEach((b, i) => { BULAN_MAP_REV_T[b] = i; });

  // Hitung baris per resort untuk tanggal terpilih
  const tunaiRows = (() => {
    if (!bukuData?.nasabah || !currentDate) return [];
    const allNasabah = bukuData.nasabah;
    const admins = activeCabang?.admins || [];
    const dateStr = currentDate;

    // Bangun peta kasbon: { adminUid: totalKasbon } untuk tanggal ini
    const kasbonMap = {};
    kasirEntries.forEach(e => {
      if (e.jenis === 'uang_kas' && e.tanggal === dateStr && e.targetAdminUid) {
        kasbonMap[e.targetAdminUid] = (kasbonMap[e.targetAdminUid] || 0) + (e.jumlah || 0);
      }
    });

    const isDropBaru = (n) => (n.pinjamanKe || 1) <= 1;

    const rows = [];
    for (const adm of admins) {
      const resortNasabah = allNasabah.filter(n => n.adminUid === adm.uid);

      // Kasbon Pagi = total uang_kas yang dikirim kasir ke admin ini pada tanggal ini
      const kasbonPagi = kasbonMap[adm.uid] || 0;

      // Hitung Tunai Pasar & Kas Pakai menggunakan rumus yang sama dengan Buku Rekap untuk tanggal ini
      const droppedOnDate = resortNasabah.filter(n => (n.tanggalPencairan || '').trim() === dateStr);
      const totalDrop = droppedOnDate.reduce((s, n) => s + (n.besarPinjaman || 0), 0);
      const adminFee = Math.round(totalDrop * 0.05);
      const tabungan = Math.round(totalDrop * 0.05);
      let storting = 0;
      resortNasabah.forEach(n => {
        const pay = n.pembayaran?.[dateStr];
        if (pay) storting += pay.total || 0;
      });
      const debitAsli = storting + adminFee + tabungan;
      const kredit = totalDrop; // pencairanTabungan = 0
      // Jika kredit > debit asli, kas pakai = selisih, tunai pasar = 0
      // Jika debit asli >= kredit, kas pakai = 0, tunai pasar = debit - kredit
      const kasPakai = kredit > debitAsli ? kredit - debitAsli : 0;
      const tunaiPasar = debitAsli >= kredit ? debitAsli - kredit : 0;

      // Kembali Kasbon = Kasbon Pagi - Kas Pakai
      const kembaliKasbon = kasbonPagi - kasPakai;

      // Titipan (belum diimplementasikan, placeholder = 0)
      const titipan = 0;

      // +/- = Kembali Kasbon + Tunai Pasar + Titipan
      const plusMinus = kembaliKasbon + tunaiPasar + titipan;

      rows.push({ resortName: adm.name, kasbonPagi, kasPakai, kembaliKasbon, tunaiPasar, titipan, plusMinus });
    }
    return rows;
  })();

  // Total semua resort
  const totals = tunaiRows.reduce((acc, r) => ({
    kasbonPagi: acc.kasbonPagi + r.kasbonPagi,
    kasPakai: acc.kasPakai + r.kasPakai,
    kembaliKasbon: acc.kembaliKasbon + r.kembaliKasbon,
    tunaiPasar: acc.tunaiPasar + r.tunaiPasar,
    titipan: acc.titipan + r.titipan,
    plusMinus: acc.plusMinus + r.plusMinus,
  }), { kasbonPagi: 0, kasPakai: 0, kembaliKasbon: 0, tunaiPasar: 0, titipan: 0, plusMinus: 0 });

  const thStyle = { padding: '8px 6px', textAlign: 'center', fontWeight: 700, fontSize: 11, whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2, borderBottom: '2px solid var(--border)' };
  const tdStyle = { padding: '8px 6px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 12 };
  const tdNameStyle = { padding: '8px', textAlign: 'left', fontWeight: 600, fontSize: 12, whiteSpace: 'nowrap' };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Buku Tunai</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}{currentDate ? ` — ${currentDate}` : ''}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="bukuTunai" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        {/* Cabang selector untuk kasir_wilayah */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 16 }}>
            <select value={activeCabang?.id || ''} onChange={(e) => { setActiveCabang(cabangList.find(c => c.id === e.target.value)); }}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>
        )}

        {/* Tab tanggal — 7 hari terakhir */}
        {!loading && dates.length > 0 && (
          <div style={{ display: 'flex', gap: 6, marginBottom: 16, overflowX: 'auto', paddingBottom: 4 }}>
            {dates.map(d => {
              const isActive = currentDate === d;
              return (
                <button key={d} onClick={() => setSelectedDate(d)} style={{
                  padding: '6px 14px', borderRadius: 20, fontSize: 12, fontWeight: 600,
                  whiteSpace: 'nowrap', cursor: 'pointer',
                  border: `1px solid ${isActive ? 'var(--primary)' : 'var(--border)'}`,
                  background: isActive ? 'var(--primary)' : 'var(--card)',
                  color: isActive ? '#fff' : 'var(--text)',
                  transition: 'all 0.15s',
                }}>
                  {d.slice(0, 6)}
                </button>
              );
            })}
          </div>
        )}

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Buku Tunai...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : tunaiRows.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Tidak ada data resort</p>
          </div>
        ) : (
          <>
            {/* Kartu ringkasan */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 10, marginBottom: 20 }}>
              <div style={{ background: 'var(--primary-light)', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Kasbon Pagi</p>
                <p style={{ fontSize: 15, fontWeight: 700, color: 'var(--primary)' }}>{formatRpFull(totals.kasbonPagi)}</p>
              </div>
              <div style={{ background: '#e8f8f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Kembali Kasbon</p>
                <p style={{ fontSize: 15, fontWeight: 700, color: 'var(--success)' }}>{formatRpFull(totals.kembaliKasbon)}</p>
              </div>
              <div style={{ background: '#f0f4ff', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Tunai Pasar</p>
                <p style={{ fontSize: 15, fontWeight: 700, color: totals.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRpFull(totals.tunaiPasar)}</p>
              </div>
              <div style={{ background: totals.plusMinus >= 0 ? '#e8f8f0' : '#fef2f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>+/-</p>
                <p style={{ fontSize: 15, fontWeight: 700, color: totals.plusMinus >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRpFull(totals.plusMinus)}</p>
              </div>
            </div>

            {/* Tabel */}
            <div style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--card)' }}>
              <table style={{ width: '100%', minWidth: 680, fontSize: 12, borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ background: '#f8f9fa' }}>
                    <th style={{ ...thStyle, textAlign: 'left', paddingLeft: 10 }}>Nama Resort</th>
                    <th style={{ ...thStyle, background: 'var(--primary-light)' }}>Kasbon Pagi</th>
                    <th style={thStyle}>Kas Pakai</th>
                    <th style={{ ...thStyle, background: '#e8f8f0' }}>Kembali Kasbon</th>
                    <th style={{ ...thStyle, background: '#f0f4ff' }}>Tunai Pasar</th>
                    <th style={thStyle}>Titipan</th>
                    <th style={{ ...thStyle, background: '#f3e8ff' }}>+/-</th>
                  </tr>
                </thead>
                <tbody>
                  {tunaiRows.map((row, idx) => (
                    <tr key={idx} style={{ borderBottom: '1px solid var(--border-light)' }}>
                      <td style={tdNameStyle}>{row.resortName}</td>
                      <td style={{ ...tdStyle, color: 'var(--primary)', fontWeight: 600 }}>{row.kasbonPagi > 0 ? formatRp(row.kasbonPagi) : '-'}</td>
                      <td style={tdStyle}>{row.kasPakai > 0 ? formatRp(row.kasPakai) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 600, color: row.kembaliKasbon >= 0 ? 'var(--success)' : 'var(--danger)' }}>{row.kembaliKasbon !== 0 ? formatRp(row.kembaliKasbon) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 700, color: row.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{row.tunaiPasar !== 0 ? formatRp(row.tunaiPasar) : '-'}</td>
                      <td style={tdStyle}>{row.titipan > 0 ? formatRp(row.titipan) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 700, color: row.plusMinus >= 0 ? 'var(--success)' : 'var(--danger)' }}>{row.plusMinus !== 0 ? formatRp(row.plusMinus) : '-'}</td>
                    </tr>
                  ))}
                  {/* Baris total */}
                  <tr style={{ borderTop: '2px solid var(--border)', background: '#f8f9fa' }}>
                    <td style={{ ...tdNameStyle, fontWeight: 800 }}>TOTAL</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--primary)' }}>{totals.kasbonPagi > 0 ? formatRp(totals.kasbonPagi) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{totals.kasPakai > 0 ? formatRp(totals.kasPakai) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: totals.kembaliKasbon >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totals.kembaliKasbon)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: totals.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totals.tunaiPasar)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{totals.titipan > 0 ? formatRp(totals.titipan) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: totals.plusMinus >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totals.plusMinus)}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </>
        )}
      </main>
    </div>
  );
}


// ============================================================
// BUKU EKSPEDISI SCREEN
// Kolom: Tanggal | Kembali Kasbon | Tunai Pasar | Drop Pusat |
//        Kasbon Pagi | Transport | BU | Pengembalian Kas+SP | Tunai Kas
// ============================================================
function BukuEkspedisiScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(cabang || cabangList[0] || null);
  const [bulan, setBulan] = useState(getCurrentMonthKey());
  const [bukuData, setBukuData] = useState(null);
  const [kasirEntries, setKasirEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showFaktur, setShowFaktur] = useState(null);

  const bulanOptions = generateBulanOptions();

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    Promise.all([
      getBukuPokok({ cabangId: activeCabang.id, adminUid: '', status: 'semua' }),
      getKasirEntries({ cabangId: activeCabang.id, bulan }),
    ]).then(([bukuResult, kasirResult]) => {
      if (bukuResult.success && bukuResult.type === 'buku_pokok') {
        setBukuData(bukuResult.data);
      }
      if (kasirResult.success) {
        setKasirEntries(kasirResult.data.entries || []);
      }
    }).catch(err => {
      setError('Gagal memuat data: ' + err.message);
    }).finally(() => setLoading(false));
  }, [activeCabang?.id, bulan]);

  // ==================== COMPUTE BUKU EKSPEDISI ROWS ====================
  const ekspedisiRows = (() => {
    if (!bukuData?.nasabah) return [];

    const allNasabah = bukuData.nasabah;

    const BULAN_MAP_REV = {};
    BULAN_INDO.forEach((b, i) => { BULAN_MAP_REV[b] = i; });
    const parseDateStr = (s) => {
      if (!s) return null;
      const parts = s.split(' ');
      if (parts.length !== 3) return null;
      const m = BULAN_MAP_REV[parts[1]];
      if (m === undefined) return null;
      return new Date(parseInt(parts[2]), m, parseInt(parts[0]));
    };

    const [yyyy, mm] = bulan.split('-');
    const monthStart = new Date(parseInt(yyyy), parseInt(mm) - 1, 1);
    const monthEnd = new Date(parseInt(yyyy), parseInt(mm), 0);

    // Batas: tidak boleh lebih dari hari ini (WIB)
    const nowEK = new Date();
    const wibOffEK = 7 * 60 * 60 * 1000;
    const wibEK = new Date(nowEK.getTime() + (nowEK.getTimezoneOffset() * 60000) + wibOffEK);
    const todayLimitEK = new Date(wibEK.getFullYear(), wibEK.getMonth(), wibEK.getDate());
    const effectiveEndEK = monthEnd <= todayLimitEK ? monthEnd : todayLimitEK;

    // Kumpulkan semua tanggal aktif dalam bulan ini (sampai hari ini)
    const dateSet = new Set();
    allNasabah.forEach(n => {
      if (n.pembayaran) {
        Object.keys(n.pembayaran).forEach(d => {
          const date = parseDateStr(d);
          if (date && date >= monthStart && date <= effectiveEndEK) dateSet.add(d);
        });
      }
      const tglCair = (n.tanggalPencairan || '').trim();
      if (tglCair) {
        const date = parseDateStr(tglCair);
        if (date && date >= monthStart && date <= effectiveEndEK) dateSet.add(tglCair);
      }
    });
    kasirEntries.forEach(e => {
      const tgl = e.tanggal;
      if (!tgl) return;
      const date = parseDateStr(tgl);
      if (date && date >= monthStart && date <= effectiveEndEK) dateSet.add(tgl);
    });

    const sortedDates = Array.from(dateSet).sort((a, b) => parseDateStr(a) - parseDateStr(b));

    // Hitung Tunai Pasar per tanggal (agregat semua resort)
    const tunaiPasarPerDate = {};
    sortedDates.forEach(dateStr => {
      let totalStorting = 0, totalDrop = 0;
      allNasabah.forEach(n => {
        const pay = n.pembayaran?.[dateStr];
        if (pay) totalStorting += pay.total || 0;
        if ((n.tanggalPencairan || '').trim() === dateStr) totalDrop += n.besarPinjaman || 0;
      });
      const adminFee = Math.round(totalDrop * 0.05);
      const tabungan = Math.round(totalDrop * 0.05);
      tunaiPasarPerDate[dateStr] = (totalStorting + adminFee + tabungan) - totalDrop;
    });

    // Hitung nilai dari jurnal kasir per tanggal
    const kasbonPerDate = {};       // uang_kas keluar
    const suntikanDanaPerDate = {}; // suntikan_dana masuk
    const pinjamanKasPerDate = {};  // pinjaman_kas masuk
    const transportPerDate = {};    // transport keluar
    const buPerDate = {};           // penggajian keluar
    const buFakturPerDate = {};     // faktur data for BU entries
    const pengembalianPerDate = {}; // pengembalian_kas keluar
    const spPerDate = {};           // sp keluar

    kasirEntries.forEach(e => {
      const tgl = e.tanggal;
      if (!tgl) return;
      const date = parseDateStr(tgl);
      if (!date || date < monthStart || date > monthEnd) return;
      const jumlah = e.jumlah || 0;
      if (e.jenis === 'uang_kas' && e.arah === 'keluar') {
        kasbonPerDate[tgl] = (kasbonPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'suntikan_dana' && e.arah === 'masuk') {
        suntikanDanaPerDate[tgl] = (suntikanDanaPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'pinjaman_kas' && e.arah === 'masuk') {
        pinjamanKasPerDate[tgl] = (pinjamanKasPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'transport' && e.arah === 'keluar') {
        transportPerDate[tgl] = (transportPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'penggajian' && e.arah === 'keluar') {
        // Hanya hitung BU yang targetnya ekspedisi, atau entry lama tanpa targetBuku
        const buku = e.targetBuku;
        if (!buku || (Array.isArray(buku) && buku.includes('ekspedisi'))) {
          buPerDate[tgl] = (buPerDate[tgl] || 0) + jumlah;
          if (!buFakturPerDate[tgl]) buFakturPerDate[tgl] = [];
          buFakturPerDate[tgl].push({ jumlah, fakturUrl: e.fakturUrl || null, keterangan: e.keterangan || '' });
        }
      } else if (e.jenis === 'pengembalian_kas' && e.arah === 'keluar') {
        pengembalianPerDate[tgl] = (pengembalianPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'sp' && e.arah === 'keluar') {
        spPerDate[tgl] = (spPerDate[tgl] || 0) + jumlah;
      }
    });

    return sortedDates.map(dateStr => {
      const tunaiPasar = tunaiPasarPerDate[dateStr] || 0;
      const kasbonPagi = kasbonPerDate[dateStr] || 0;
      const kembaliKasbon = kasbonPagi; // kasPakai = 0
      const suntikanDana = suntikanDanaPerDate[dateStr] || 0;
      const pinjamanKas = pinjamanKasPerDate[dateStr] || 0;
      const dropPusat = suntikanDana + pinjamanKas;
      const transport = transportPerDate[dateStr] || 0;
      const bu = buPerDate[dateStr] || 0;
      const buFaktur = buFakturPerDate[dateStr] || [];
      const pengembalianKas = pengembalianPerDate[dateStr] || 0;
      const sp = spPerDate[dateStr] || 0;
      const tunaiKas = (kembaliKasbon + tunaiPasar + dropPusat) - (kasbonPagi + transport + bu + pengembalianKas + sp);
      return { tanggal: dateStr, kembaliKasbon, tunaiPasar, suntikanDana, pinjamanKas, dropPusat, kasbonPagi, transport, bu, buFaktur, pengembalianKas, sp, tunaiKas };
    });
  })();

  // Total baris
  const totals = ekspedisiRows.reduce((acc, r) => ({
    kembaliKasbon: acc.kembaliKasbon + r.kembaliKasbon,
    tunaiPasar: acc.tunaiPasar + r.tunaiPasar,
    suntikanDana: acc.suntikanDana + r.suntikanDana,
    pinjamanKas: acc.pinjamanKas + r.pinjamanKas,
    kasbonPagi: acc.kasbonPagi + r.kasbonPagi,
    transport: acc.transport + r.transport,
    bu: acc.bu + r.bu,
    sp: acc.sp + r.sp,
    pengembalianKas: acc.pengembalianKas + r.pengembalianKas,
    tunaiKas: acc.tunaiKas + r.tunaiKas,
  }), { kembaliKasbon: 0, tunaiPasar: 0, suntikanDana: 0, pinjamanKas: 0, kasbonPagi: 0, transport: 0, bu: 0, sp: 0, pengembalianKas: 0, tunaiKas: 0 });

  const thS = {
    padding: '7px 6px', textAlign: 'center', fontWeight: 700, fontSize: 10,
    whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#f8f9fa',
    zIndex: 2, borderBottom: '2px solid var(--border)', borderRight: '1px solid var(--border)',
  };
  const tdR = { padding: '6px 7px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11, borderRight: '1px solid var(--border-light)', borderBottom: '1px solid var(--border-light)' };
  const tdRBold = { ...tdR, fontWeight: 700 };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Buku Ekspedisi</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'} — {formatBulanLabel(bulan)}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="bukuEkspedisi" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        {/* Filter */}
        <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
          {!isUnit && cabangList.length > 1 && (
            <select value={activeCabang?.id || ''} onChange={(e) => setActiveCabang(cabangList.find(c => c.id === e.target.value))}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          <select value={bulan} onChange={(e) => setBulan(e.target.value)}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
        </div>

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Buku Ekspedisi...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : ekspedisiRows.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Tidak ada data untuk bulan {formatBulanLabel(bulan)}</p>
          </div>
        ) : (
          <div style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--card)' }}>
            <table style={{ width: '100%', minWidth: 1100, fontSize: 12, borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ background: '#f8f9fa' }}>
                  <th style={{ ...thS, textAlign: 'left', paddingLeft: 10 }}>Tanggal</th>
                  <th style={{ ...thS, background: '#e8f8f0' }}>Kembali Kasbon</th>
                  <th style={{ ...thS, background: '#e8f8f0' }}>Tunai Pasar</th>
                  <th style={{ ...thS, background: '#e0f0ff' }}>Suntikan Dana</th>
                  <th style={{ ...thS, background: '#e0f0ff' }}>Pinjaman Kas</th>
                  <th style={{ ...thS, background: '#fef9c3' }}>Kasbon Pagi</th>
                  <th style={{ ...thS, background: '#fef9c3' }}>Transport</th>
                  <th style={{ ...thS, background: '#ffe4e6' }}>BU</th>
                  <th style={{ ...thS, background: '#ffe4e6' }}>SP</th>
                  <th style={{ ...thS, background: '#ffe4e6' }}>Pengembalian Kas</th>
                  <th style={{ ...thS, background: '#f3e8ff' }}>Tunai Kas</th>
                </tr>
              </thead>
              <tbody>
                {ekspedisiRows.map((row) => (
                  <tr key={row.tanggal}>
                    <td style={{ padding: '6px 10px', fontWeight: 700, fontSize: 12, borderRight: '1px solid var(--border)', borderBottom: '1px solid var(--border-light)', whiteSpace: 'nowrap', background: '#fafafa' }}>
                      {row.tanggal.slice(0, 6)}
                    </td>
                    <td style={{ ...tdR, background: '#f0fdf4', color: '#166534' }}>
                      {row.kembaliKasbon > 0 ? formatRp(row.kembaliKasbon) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#f0fdf4', color: row.tunaiPasar >= 0 ? '#166534' : 'var(--danger)', fontWeight: 600 }}>
                      {row.tunaiPasar !== 0 ? formatRp(row.tunaiPasar) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#eff6ff' }}>
                      {row.suntikanDana > 0 ? formatRp(row.suntikanDana) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#eff6ff' }}>
                      {row.pinjamanKas > 0 ? formatRp(row.pinjamanKas) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fefce8' }}>
                      {row.kasbonPagi > 0 ? formatRp(row.kasbonPagi) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fefce8' }}>
                      {row.transport > 0 ? formatRp(row.transport) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fff1f2', cursor: row.bu > 0 ? 'pointer' : 'default', textDecoration: row.bu > 0 ? 'underline' : 'none' }}
                      onClick={() => { if (row.bu > 0 && row.buFaktur?.length > 0) setShowFaktur(row.buFaktur); }}>
                      {row.bu > 0 ? formatRp(row.bu) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fff1f2' }}>
                      {row.sp > 0 ? formatRp(row.sp) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fff1f2' }}>
                      {row.pengembalianKas > 0 ? formatRp(row.pengembalianKas) : '-'}
                    </td>
                    <td style={{ ...tdRBold, background: '#faf5ff', color: row.tunaiKas >= 0 ? '#7e22ce' : 'var(--danger)' }}>
                      {formatRp(row.tunaiKas)}
                    </td>
                  </tr>
                ))}
                {/* Total row */}
                <tr style={{ borderTop: '2px solid var(--border)', background: '#f8f9fa', fontWeight: 800 }}>
                  <td style={{ padding: '8px 10px', fontWeight: 800, fontSize: 12, borderRight: '1px solid var(--border)' }}>TOTAL</td>
                  <td style={{ ...tdRBold, background: '#e8f8f0', color: '#166534' }}>{totals.kembaliKasbon > 0 ? formatRp(totals.kembaliKasbon) : '-'}</td>
                  <td style={{ ...tdRBold, background: '#e8f8f0', color: totals.tunaiPasar >= 0 ? '#166534' : 'var(--danger)' }}>{totals.tunaiPasar !== 0 ? formatRp(totals.tunaiPasar) : '-'}</td>
                  <td style={{ ...tdRBold, background: '#dbeafe' }}>{totals.suntikanDana > 0 ? formatRp(totals.suntikanDana) : '-'}</td>
                  <td style={{ ...tdRBold, background: '#dbeafe' }}>{totals.pinjamanKas > 0 ? formatRp(totals.pinjamanKas) : '-'}</td>
                  <td style={{ ...tdRBold, background: '#fef9c3' }}>{totals.kasbonPagi > 0 ? formatRp(totals.kasbonPagi) : '-'}</td>
                  <td style={{ ...tdRBold, background: '#fef9c3' }}>{totals.transport > 0 ? formatRp(totals.transport) : '-'}</td>
                  <td style={{ ...tdRBold, background: '#ffe4e6' }}>{totals.bu > 0 ? formatRp(totals.bu) : '-'}</td>
                  <td style={{ ...tdRBold, background: '#ffe4e6' }}>{totals.sp > 0 ? formatRp(totals.sp) : '-'}</td>
                  <td style={{ ...tdRBold, background: '#ffe4e6' }}>{totals.pengembalianKas > 0 ? formatRp(totals.pengembalianKas) : '-'}</td>
                  <td style={{ ...tdRBold, background: '#f3e8ff', color: totals.tunaiKas >= 0 ? '#7e22ce' : 'var(--danger)' }}>{formatRp(totals.tunaiKas)}</td>
                </tr>
              </tbody>
            </table>
          </div>
        )}
      </main>

      {/* Faktur Modal */}
      {showFaktur && <FakturModal fakturList={showFaktur} onClose={() => setShowFaktur(null)} />}
    </div>
  );
}


// ============================================================
// RINGKASAN SCREEN
// ============================================================
function RingkasanScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(cabang || cabangList[0] || null);
  const [bulan, setBulan] = useState(getCurrentMonthKey());
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const bulanOptions = generateBulanOptions();

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    getKasirEntries({ cabangId: activeCabang.id, bulan }).then(result => {
      if (result.success) {
        setSummary(result.data.summary || {});
      }
    }).catch(err => {
      setError('Gagal memuat: ' + err.message);
    }).finally(() => setLoading(false));
  }, [activeCabang?.id, bulan]);

  const perJenis = summary?.perJenis || {};

  const rows = JENIS_OPTIONS.map(j => {
    const d = perJenis[j.value] || {};
    return { label: j.label, masuk: d.masuk || 0, keluar: d.keluar || 0 };
  });

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Ringkasan Kas</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="ringkasan" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: '20px 24px', maxWidth: 700, margin: '0 auto' }} className="fade-in">
        {/* Filters */}
        <div style={{ display: 'flex', gap: 12, marginBottom: 24, flexWrap: 'wrap' }}>
          {!isUnit && cabangList.length > 1 && (
            <select value={activeCabang?.id || ''} onChange={(e) => setActiveCabang(cabangList.find(c => c.id === e.target.value))}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          <select value={bulan} onChange={(e) => setBulan(e.target.value)}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
        </div>

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : (
          <>
            {/* Table */}
            <div style={{ background: 'var(--card)', borderRadius: 16, border: '1px solid var(--border)', overflow: 'hidden' }}>
              <table style={{ width: '100%', fontSize: 14 }}>
                <thead>
                  <tr style={{ background: '#f8f9fa' }}>
                    <th style={{ padding: '14px 16px', textAlign: 'left', fontWeight: 700 }}>Jenis</th>
                    <th style={{ padding: '14px 16px', textAlign: 'right', fontWeight: 700, color: 'var(--success)' }}>Masuk</th>
                    <th style={{ padding: '14px 16px', textAlign: 'right', fontWeight: 700, color: 'var(--danger)' }}>Keluar</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r, i) => (
                    <tr key={i} style={{ borderTop: '1px solid var(--border-light)' }}>
                      <td style={{ padding: '12px 16px', fontWeight: 500 }}>{r.label}</td>
                      <td style={{ padding: '12px 16px', textAlign: 'right', fontFamily: "'DM Mono', monospace", color: r.masuk > 0 ? 'var(--success)' : 'var(--text-light)' }}>
                        {r.masuk > 0 ? formatRpFull(r.masuk) : '-'}
                      </td>
                      <td style={{ padding: '12px 16px', textAlign: 'right', fontFamily: "'DM Mono', monospace", color: r.keluar > 0 ? 'var(--danger)' : 'var(--text-light)' }}>
                        {r.keluar > 0 ? formatRpFull(r.keluar) : '-'}
                      </td>
                    </tr>
                  ))}
                  {/* Total row */}
                  <tr style={{ borderTop: '2px solid var(--border)', background: '#f8f9fa' }}>
                    <td style={{ padding: '14px 16px', fontWeight: 800 }}>TOTAL</td>
                    <td style={{ padding: '14px 16px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontWeight: 800, color: 'var(--success)' }}>
                      {formatRpFull(summary?.totalMasuk || 0)}
                    </td>
                    <td style={{ padding: '14px 16px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontWeight: 800, color: 'var(--danger)' }}>
                      {formatRpFull(summary?.totalKeluar || 0)}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

          </>
        )}
      </main>
    </div>
  );
}

// ============================================================
// ABSENSI SCREEN (Kasir - Lihat & kelola absensi harian)
// ============================================================
function AbsensiScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(cabang || cabangList[0] || null);
  const [absensiList, setAbsensiList] = useState([]);
  const [operasionalMap, setOperasionalMap] = useState({});
  const [loading, setLoading] = useState(true);
  const [sudahAbsen, setSudahAbsen] = useState(false);
  const [absensiSendiri, setAbsensiSendiri] = useState(null);
  const [submittingAbsensi, setSubmittingAbsensi] = useState(false);
  const [savingMap, setSavingMap] = useState({});
  const [inputMap, setInputMap] = useState({});
  const [error, setError] = useState('');
  const [successMsg, setSuccessMsg] = useState('');

  const todayKey = (() => {
    const now = new Date();
    const jakartaOffset = 7 * 60;
    const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
    const jakarta = new Date(utc + (jakartaOffset * 60000));
    const yyyy = jakarta.getFullYear();
    const mm = String(jakarta.getMonth() + 1).padStart(2, '0');
    const dd = String(jakarta.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  })();

  const todayDisplay = getTodayIndo();

  const ROLE_LABELS = {
    admin: 'PDL', koordinator: 'Koordinator', pimpinan: 'Pimpinan',
    kasir_unit: 'Kasir', kasir_wilayah: 'Kasir Wilayah'
  };

  // Load absensi data
  const loadAbsensi = async () => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    try {
      const { ref: dbRefFn, get } = await import('firebase/database');
      const { database: db } = await import('../../lib/firebase');

      const snap = await get(dbRefFn(db, `absensi/${activeCabang.id}/${todayKey}`));
      const data = snap.val() || {};
      const list = Object.values(data).sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0));
      setAbsensiList(list);

      // Check if kasir already absented (using their uid)
      const uid = (await import('firebase/auth')).getAuth().currentUser?.uid;
      if (uid) {
        const selfSnap = await get(dbRefFn(db, `user_absensi_today/${uid}`));
        const selfData = selfSnap.val();
        if (selfData && selfData.tanggal === todayKey) {
          setSudahAbsen(true);
          setAbsensiSendiri(selfData);
        } else {
          setSudahAbsen(false);
          setAbsensiSendiri(null);
        }
      }

      // Load operasional harian
      const opsSnap = await get(dbRefFn(db, `operasional_harian/${activeCabang.id}/${todayKey}`));
      const opsData = opsSnap.val() || {};
      setOperasionalMap(opsData);

      // Initialize input map (format ribuan untuk tampilan)
      const initInput = {};
      list.forEach(a => {
        const um = opsData[a.uid]?.uangMakan;
        const tr = opsData[a.uid]?.transport;
        initInput[a.uid] = {
          uangMakan: um ? parseInt(um).toLocaleString('id-ID') : '',
          transport: tr ? parseInt(tr).toLocaleString('id-ID') : '',
        };
      });
      setInputMap(initInput);

    } catch (err) {
      setError('Gagal memuat data absensi: ' + err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadAbsensi(); }, [activeCabang?.id]);

  // Kasir absen sendiri
  const handleAbsenSendiri = async () => {
    if (!activeCabang || !isUnit) return;
    setSubmittingAbsensi(true);
    setError('');
    try {
      const { ref: dbRefFn, set, serverTimestamp } = await import('firebase/database');
      const { database: db } = await import('../../lib/firebase');
      const { getAuth } = await import('firebase/auth');

      const auth = getAuth();
      const uid = auth.currentUser?.uid;
      if (!uid) throw new Error('Tidak ada sesi login');

      const now = new Date();
      const jakartaOffset = 7 * 60;
      const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
      const jakarta = new Date(utc + (jakartaOffset * 60000));
      const jam = `${String(jakarta.getHours()).padStart(2, '0')}:${String(jakarta.getMinutes()).padStart(2, '0')}`;

      const record = {
        uid,
        nama: user.name,
        role: user.role,
        cabangId: activeCabang.id,
        cabangNama: activeCabang.name,
        jam,
        tanggal: todayKey,
        timestamp: Date.now(),
      };

      await set(dbRefFn(db, `absensi/${activeCabang.id}/${todayKey}/${uid}`), record);
      await set(dbRefFn(db, `user_absensi_today/${uid}`), record);

      setSudahAbsen(true);
      setAbsensiSendiri(record);
      setSuccessMsg(`Absensi berhasil dicatat pukul ${jam}`);
      setTimeout(() => setSuccessMsg(''), 3000);
      await loadAbsensi();
    } catch (err) {
      setError('Gagal absen: ' + err.message);
    } finally {
      setSubmittingAbsensi(false);
    }
  };

  // Simpan operasional karyawan
  const handleSaveOperasional = async (uid, nama) => {
    if (!activeCabang || !isUnit) return;
    const input = inputMap[uid] || {};
    const uangMakan = parseInt(String(input.uangMakan).replace(/\./g, '')) || 0;
    const transport = parseInt(String(input.transport).replace(/\./g, '')) || 0;
    if (uangMakan < 0 || transport < 0) { setError('Nominal tidak boleh negatif'); return; }

    setSavingMap(m => ({ ...m, [uid]: true }));
    setError('');
    try {
      const { ref: dbRefFn, set } = await import('firebase/database');
      const { database: db } = await import('../../lib/firebase');
      const { getAuth } = await import('firebase/auth');
      const kasirUid = getAuth().currentUser?.uid;

      const record = {
        uid, nama, uangMakan, transport,
        diberikanOleh: kasirUid,
        diberikanOlehNama: user.name,
        timestamp: Date.now(),
      };

      await set(dbRefFn(db, `operasional_harian/${activeCabang.id}/${todayKey}/${uid}`), record);
      setOperasionalMap(m => ({ ...m, [uid]: record }));

      // Sync total operasional ke jurnal kasir sebagai entry Transport
      try {
        await syncOperasionalTransport();
      } catch (syncErr) {
        console.error('Sync operasional ke jurnal gagal:', syncErr);
        // Tidak blocking — operasional tetap tersimpan
      }

      setSuccessMsg(`Operasional ${nama} berhasil disimpan`);
      setTimeout(() => setSuccessMsg(''), 3000);
    } catch (err) {
      setError('Gagal menyimpan: ' + err.message);
    } finally {
      setSavingMap(m => ({ ...m, [uid]: false }));
    }
  };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-icon" title="Kembali">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Absensi Karyawan</h1>
            <p>{todayDisplay}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="absensi" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>
            </svg>
          </button>
        </div>
      </header>

      <main className="home-content fade-in" style={{ maxWidth: 800, margin: '0 auto' }}>
        {/* Cabang selector for kasir wilayah */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 20 }}>
            <div className="home-section-label">Pilih Cabang</div>
            <div className="cabang-grid">
              {cabangList.map(c => (
                <button key={c.id} onClick={() => setActiveCabang(c)}
                  className="cabang-card"
                  style={activeCabang?.id === c.id ? { borderColor: 'var(--primary)', background: 'var(--primary-light)' } : {}}>
                  <div className="cabang-card-info"><h3>{c.name}</h3></div>
                  {activeCabang?.id === c.id && <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" strokeWidth="2.5" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>}
                </button>
              ))}
            </div>
          </div>
        )}

        {error && (
          <div style={{ background: 'var(--danger-light,#fef2f2)', border: '1px solid var(--danger,#ef4444)', borderRadius: 10, padding: '10px 16px', marginBottom: 16, color: 'var(--danger,#ef4444)', fontSize: 14 }}>
            {error}
          </div>
        )}

        {successMsg && (
          <div style={{ background: '#f0fdf4', border: '1px solid #22c55e', borderRadius: 10, padding: '10px 16px', marginBottom: 16, color: '#16a34a', fontSize: 14 }}>
            ✓ {successMsg}
          </div>
        )}

        {/* Kasir absen sendiri */}
        {isUnit && activeCabang && (
          <div style={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 14, padding: 20, marginBottom: 20 }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-muted)', marginBottom: 12, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              Absensi Saya
            </div>
            {sudahAbsen ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div style={{ width: 40, height: 40, borderRadius: '50%', background: '#f0fdf4', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#16a34a" strokeWidth="2.5" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>
                </div>
                <div>
                  <p style={{ fontWeight: 600, color: '#16a34a', marginBottom: 2 }}>Sudah Absen</p>
                  <p style={{ fontSize: 13, color: 'var(--text-muted)' }}>
                    Pukul {absensiSendiri?.jam} · {activeCabang?.name}
                  </p>
                </div>
              </div>
            ) : (
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div>
                  <p style={{ fontWeight: 600, color: 'var(--text-primary)', marginBottom: 4 }}>{user?.name}</p>
                  <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 12 }}>Belum absen hari ini · {activeCabang?.name}</p>
                </div>
                <button
                  onClick={handleAbsenSendiri}
                  disabled={submittingAbsensi}
                  style={{ marginLeft: 'auto', padding: '8px 20px', background: 'var(--primary,#6366f1)', color: '#fff', border: 'none', borderRadius: 8, fontWeight: 600, cursor: 'pointer', opacity: submittingAbsensi ? 0.6 : 1 }}>
                  {submittingAbsensi ? 'Menyimpan...' : 'Absen Sekarang'}
                </button>
              </div>
            )}
          </div>
        )}

        {/* Daftar absensi */}
        {activeCabang && (
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
              <div className="home-section-label" style={{ marginBottom: 0 }}>
                Daftar Absensi Hari Ini {activeCabang && `— ${activeCabang.name}`}
              </div>
              <button onClick={loadAbsensi} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--primary)', fontSize: 13, fontWeight: 600 }}>
                Refresh
              </button>
            </div>

            {loading ? (
              <div style={{ textAlign: 'center', padding: 40 }}>
                <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
                <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Memuat data...</p>
              </div>
            ) : absensiList.length === 0 ? (
              <div style={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 14, padding: 32, textAlign: 'center' }}>
                <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Belum ada karyawan yang absen hari ini</p>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {absensiList.map((a, i) => {
                  const ops = operasionalMap[a.uid] || {};
                  const inp = inputMap[a.uid] || {};
                  const sudahDiisi = ops.uangMakan !== undefined || ops.transport !== undefined;
                  return (
                    <div key={a.uid} style={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 14, padding: '14px 16px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: sudahDiisi || isUnit ? 12 : 0 }}>
                        <div style={{ width: 36, height: 36, borderRadius: '50%', background: 'var(--primary-light,#ede9fe)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 15, fontWeight: 700, color: 'var(--primary,#6366f1)', flexShrink: 0 }}>
                          {(a.nama || '?')[0].toUpperCase()}
                        </div>
                        <div style={{ flex: 1 }}>
                          <p style={{ fontWeight: 600, fontSize: 14, marginBottom: 2 }}>{a.nama}</p>
                          <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                            {ROLE_LABELS[a.role] || a.role} · {a.cabangNama} · {a.jam}
                          </p>
                        </div>
                        {sudahDiisi && (
                          <div style={{ textAlign: 'right', fontSize: 12 }}>
                            <p style={{ color: 'var(--text-muted)' }}>Makan: <b style={{ color: 'var(--text-primary)' }}>{(ops.uangMakan || 0).toLocaleString('id-ID')}</b></p>
                            <p style={{ color: 'var(--text-muted)' }}>Transport: <b style={{ color: 'var(--text-primary)' }}>{(ops.transport || 0).toLocaleString('id-ID')}</b></p>
                          </div>
                        )}
                      </div>

                      {/* Input operasional (kasir_unit only) */}
                      {isUnit && (
                        <div style={{ borderTop: '1px solid var(--border)', paddingTop: 12, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                          <div style={{ flex: 1, minWidth: 120 }}>
                            <label style={{ fontSize: 11, color: 'var(--text-muted)', display: 'block', marginBottom: 4 }}>Uang Makan (Rp)</label>
                            <input
                              type="text" inputMode="numeric"
                              value={inp.uangMakan ?? ''}
                              onChange={e => {
                                const raw = e.target.value.replace(/\D/g, '');
                                const formatted = raw ? parseInt(raw).toLocaleString('id-ID') : '';
                                setInputMap(m => ({ ...m, [a.uid]: { ...m[a.uid], uangMakan: formatted } }));
                              }}
                              placeholder="0"
                              style={{ width: '100%', padding: '6px 10px', border: '1px solid var(--border)', borderRadius: 8, fontSize: 14, background: 'var(--background)', color: 'var(--text-primary)', boxSizing: 'border-box' }}
                            />
                          </div>
                          <div style={{ flex: 1, minWidth: 120 }}>
                            <label style={{ fontSize: 11, color: 'var(--text-muted)', display: 'block', marginBottom: 4 }}>Transport (Rp)</label>
                            <input
                              type="text" inputMode="numeric"
                              value={inp.transport ?? ''}
                              onChange={e => {
                                const raw = e.target.value.replace(/\D/g, '');
                                const formatted = raw ? parseInt(raw).toLocaleString('id-ID') : '';
                                setInputMap(m => ({ ...m, [a.uid]: { ...m[a.uid], transport: formatted } }));
                              }}
                              placeholder="0"
                              style={{ width: '100%', padding: '6px 10px', border: '1px solid var(--border)', borderRadius: 8, fontSize: 14, background: 'var(--background)', color: 'var(--text-primary)', boxSizing: 'border-box' }}
                            />
                          </div>
                          <button
                            onClick={() => handleSaveOperasional(a.uid, a.nama)}
                            disabled={savingMap[a.uid]}
                            style={{ padding: '8px 16px', background: 'var(--primary,#6366f1)', color: '#fff', border: 'none', borderRadius: 8, fontWeight: 600, cursor: 'pointer', fontSize: 13, whiteSpace: 'nowrap', marginTop: 16, opacity: savingMap[a.uid] ? 0.6 : 1 }}>
                            {savingMap[a.uid] ? 'Menyimpan...' : 'Simpan'}
                          </button>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </main>
    </div>
  );
}
