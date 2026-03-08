'use client';

// app/kasir/page.js
// =========================================================================
// KASIR WEB - Jurnal Kasir & Akses Buku Pokok
// =========================================================================

import { useState, useEffect } from 'react';
import { onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'firebase/auth';
import { auth } from '../../lib/firebase';
import { getKasirSummary, getKasirEntries, addKasirEntry, deleteKasirEntry, getBukuPokok } from '../../lib/api';
import { formatRp, formatRpFull } from '../../lib/format';

// =========================================================================
// CONSTANTS
// =========================================================================
const JENIS_OPTIONS = [
  { value: 'uang_kas', label: 'Uang Kas' },
  { value: 'penggajian', label: 'BU/Penggajian' },
  { value: 'transport', label: 'Transport' },
  { value: 'suntikan_dana', label: 'Suntikan Dana' },
  { value: 'pinjaman_kas', label: 'Pinjaman Kas' },
  { value: 'sp', label: 'SP' },
];

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

            // Kasir unit langsung set cabang
            if (d.user.role === 'kasir_unit' && d.cabangList.length === 1) {
              setSelectedCabang(d.cabangList[0]);
            }
            setScreen('home');
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

  const handleLogout = async () => {
    await signOut(auth);
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

  if (screen === 'forbidden') {
    return <ForbiddenScreen onLogout={handleLogout} />;
  }

  if (screen === 'jurnal') {
    return (
      <JurnalScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={() => setScreen('home')}
        onLogout={handleLogout}
      />
    );
  }

    if (screen === 'bukuRekap') {
    return (
      <BukuRekapScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={() => setScreen('home')}
        onLogout={handleLogout}
      />
    );
  }

  if (screen === 'ringkasan') {
    return (
      <RingkasanScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={() => setScreen('home')}
        onLogout={handleLogout}
      />
    );
  }

  return (
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
  const cabId = selectedCabang?.id || (isUnit && cabangList[0]?.id);
  const summary = cabId ? (summaryData[cabId] || {}) : {};

  const roleLabel = isUnit ? 'Kasir Unit' : 'Kasir Wilayah';

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
      id: 'ringkasan', name: 'Ringkasan Kas', desc: 'Rekapitulasi pemasukan & pengeluaran',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M21.21 15.89A10 10 0 1 1 8 2.83"/><path d="M22 12A10 10 0 0 0 12 2v10z"/>
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
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>
            </svg>
          </button>
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
            <div style={{ background: 'var(--card)', borderRadius: 14, padding: '16px 20px', border: '1px solid var(--border)' }}>
              <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 4 }}>Saldo Kas (Bulan Ini)</p>
              <p style={{ fontSize: 18, fontWeight: 700, color: 'var(--primary)' }}>{formatRpFull(summary.saldo || 0)}</p>
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
function JurnalScreen({ user, cabang, cabangList, onBack, onLogout }) {
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
    // Sort descending by date
    return entries.findIndex(e => e.tanggal === b) - entries.findIndex(e => e.tanggal === a);
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
function FormModal({ cabangAdmins, onClose, onSuccess }) {
  const [jenis, setJenis] = useState('uang_kas');
  const [arah, setArah] = useState('masuk');
  const [jumlah, setJumlah] = useState('');
  const [keterangan, setKeterangan] = useState('');
  const [tanggal] = useState(getTodayIndo());
  const [targetAdmin, setTargetAdmin] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async () => {
    const nominal = parseInt(jumlah.replace(/\D/g, ''), 10);
    if (!nominal || nominal <= 0) { setError('Jumlah harus diisi'); return; }
    if (jenis === 'uang_kas' && !targetAdmin) { setError('Pilih admin lapangan yang dituju'); return; }
    setLoading(true);
    setError('');
    try {
      const selectedAdm = cabangAdmins.find(a => a.uid === targetAdmin);
      await addKasirEntry({
        jenis, arah, jumlah: nominal, keterangan, tanggal,
        targetAdminUid: jenis === 'uang_kas' ? targetAdmin : '',
        targetAdminName: jenis === 'uang_kas' ? (selectedAdm?.name || '') : '',
      });
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
          <select value={jenis} onChange={(e) => { setJenis(e.target.value); setTargetAdmin(''); }}
            style={{ width: '100%', padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 14, background: 'var(--card)' }}>
            {JENIS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>

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

        {/* Arah */}
        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Arah</label>
          <div style={{ display: 'flex', gap: 10 }}>
            {[{ val: 'masuk', label: '↑ Masuk', color: 'var(--success)', bg: '#e8f8f0' },
              { val: 'keluar', label: '↓ Keluar', color: 'var(--danger)', bg: '#fef2f0' }].map(opt => (
              <button key={opt.val} onClick={() => setArah(opt.val)}
                style={{
                  flex: 1, padding: '10px 0', borderRadius: 10, fontSize: 14, fontWeight: 600,
                  border: arah === opt.val ? `2px solid ${opt.color}` : '1px solid var(--border)',
                  background: arah === opt.val ? opt.bg : 'var(--card)',
                  color: arah === opt.val ? opt.color : 'var(--text-muted)',
                  transition: 'all 0.2s'
                }}>
                {opt.label}
              </button>
            ))}
          </div>
        </div>

        {/* Jumlah */}
        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Jumlah (Rp)</label>
          <input type="text" value={jumlah} onChange={handleJumlahChange} placeholder="0" inputMode="numeric"
            style={{ width: '100%', padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 18, fontWeight: 700, fontFamily: "'DM Mono', monospace", boxSizing: 'border-box' }} />
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
function BukuRekapScreen({ user, cabang, cabangList, onBack, onLogout }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(cabang || cabangList[0] || null);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
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

  // ==================== COMPUTE REKAP PER RESORT ====================
  const rekapRows = (() => {
    if (!data?.nasabah) return [];

    const allNasabah = data.nasabah;
    const admins = activeCabang?.admins || [];
    const todayStr = data.today || getTodayIndo();

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

      // Debit = Storting + Admin + Tabungan
      const debit = storting + adminFee + tabungan;

      // Pencairan Tabungan
      const pencairanTabungan = 0;

      // Kredit = Total Drop + Pencairan Tabungan
      const kredit = totalDrop + pencairanTabungan;

      // Kas Pakai
      const kasPakai = 0;

      // Tunai Pasar = Debit - Kredit
      const tunaiPasar = debit - kredit;

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
  })();

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
            <p>{activeCabang?.name || 'Pilih Cabang'} — {data?.today || getTodayIndo()}</p>
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
            <select value={activeCabang?.id || ''} onChange={(e) => setActiveCabang(cabangList.find(c => c.id === e.target.value))}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
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
                  {/* TOTAL ROW */}
                  <tr style={{ borderTop: '2px solid var(--border)', background: '#f8f9fa', fontWeight: 800 }}>
                    <td style={{ ...tdNameStyle, fontWeight: 800 }}>TOTAL</td>
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
// RINGKASAN SCREEN
// ============================================================
function RingkasanScreen({ user, cabang, cabangList, onBack, onLogout }) {
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

            {/* Saldo card */}
            <div style={{
              marginTop: 20, background: 'var(--primary-light)', borderRadius: 16,
              padding: '20px 24px', textAlign: 'center', border: '1px solid var(--primary)'
            }}>
              <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 4 }}>Saldo Kas {formatBulanLabel(bulan)}</p>
              <p style={{ fontSize: 28, fontWeight: 800, color: 'var(--primary)', fontFamily: "'DM Mono', monospace" }}>
                {formatRpFull(summary?.saldo || 0)}
              </p>
            </div>
          </>
        )}
      </main>
    </div>
  );
}
