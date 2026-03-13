'use client';

// app/page.js
// =========================================================================
// BUKU POKOK - Main Page (Client Component)
// =========================================================================

import { useState, useEffect, useRef } from 'react';
import { onAuthStateChanged, signInWithEmailAndPassword, signOut } from 'firebase/auth';
import { auth } from '../../lib/firebase';
import { getSummary, getBukuPokok } from '../../lib/api';
import { formatRp, formatRpFull, formatRpShort } from '../../lib/format';

export default function Home() {
  const [user, setUser] = useState(null);
  const [userData, setUserData] = useState(null);
  const [screen, setScreen] = useState('loading');
  const [cabangList, setCabangList] = useState([]);
  const [selectedCabang, setSelectedCabang] = useState(null);
  const [selectedAdmin, setSelectedAdmin] = useState(null);

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

            if ((userRole === 'kasir_unit' || userRole === 'kasir_wilayah') && !fromKasir) {
              window.location.href = '/kasir';
              return;
            }

            // Kasir dari /kasir: langsung ke Buku Pokok, skip home
            if (fromKasir && (userRole === 'kasir_unit' || userRole === 'kasir_wilayah')) {
              if (userRole === 'kasir_unit' && result.data.cabangList.length === 1) {
                setSelectedCabang(result.data.cabangList[0]);
                setScreen('bukuPokok');
              } else {
                setScreen('dashboard');
              }
              return;
            }

            // Restore previous screen after refresh
            const saved = restoreNav();
            if (saved && saved.screen) {
              if (saved.screen === 'bukuPokok' && saved.cabang) {
                setSelectedCabang(saved.cabang);
                setSelectedAdmin(saved.admin || null);
                setScreen('bukuPokok');
              } else if (saved.screen === 'dashboard') {
                setScreen('dashboard');
              } else {
                setScreen('home');
              }
            } else {
              setScreen('home');
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

  const handleLogout = async () => {
    clearNav();
    await signOut(auth);
  };

  const handleSelectBook = (book) => {
    if (book === 'bukuPokok') {
      setScreen('dashboard');
      saveNav('dashboard', null, null);
    }
  };

  const handleSelectCabang = (cabang) => {
    setSelectedCabang(cabang);
    setSelectedAdmin(null);
    setScreen('bukuPokok');
    saveNav('bukuPokok', cabang, null);
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
    setScreen('dashboard');
    setSelectedCabang(null);
    setSelectedAdmin(null);
    saveNav('dashboard', null, null);
  };

  const handleBackToHome = () => {
    if (userData?.role === 'kasir_unit' || userData?.role === 'kasir_wilayah') {
      window.location.href = '/kasir';
      return;
    }
    setScreen('home');
    setSelectedCabang(null);
    setSelectedAdmin(null);
    saveNav('home', null, null);
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

  if (screen === 'home') {
    return (
      <HomeScreen
        user={userData}
        onSelectBook={handleSelectBook}
        onLogout={handleLogout}
      />
    );
  }

  if (screen === 'bukuPokok' && selectedCabang) {
    return (
      <BukuPokokScreen
        user={userData}
        cabang={selectedCabang}
        selectedAdmin={selectedAdmin}
        onSelectAdmin={handleSelectAdmin}
        onBack={handleBackToDashboard}
        onLogout={handleLogout}
      />
    );
  }

  return (
    <DashboardScreen
      user={userData}
      cabangList={cabangList}
      onSelectCabang={handleSelectCabang}
      onBack={handleBackToHome}
      onLogout={handleLogout}
    />
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
function HomeScreen({ user, onSelectBook, onLogout }) {
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
    // Buku-buku lain nanti ditambahkan di sini:
    // { id: 'bukuTaksasi', name: 'Buku Taksasi', desc: '...', icon: ..., ready: false },
    // { id: 'neraca', name: 'Neraca', desc: '...', icon: ..., ready: false },
  ];

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <div className="top-bar-logo" style={{ background: '#000000', padding: 0, overflow: 'hidden' }}>
            <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <div>
            <h1>Koperasi Kita</h1>
            <p>Sistem Pembukuan Digital</p>
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
      </main>
    </div>
  );
}


// ============================================================
// DASHBOARD SCREEN (Pilih Cabang)
// ============================================================
function DashboardScreen({ user, cabangList, onSelectCabang, onBack, onLogout }) {
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
            <h1>Buku Pokok</h1>
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
          <p>Pilih cabang untuk melihat Buku Pokok nasabah</p>
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
function BukuPokokScreen({ user, cabang, selectedAdmin, onSelectAdmin, onBack, onLogout }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('aktif');
  const [showDetail, setShowDetail] = useState(null);
  const [visibleDateCount, setVisibleDateCount] = useState(7);
  const [tabelFilter, setTabelFilter] = useState('semua');
  const [stortingMonth, setStortingMonth] = useState(null); // format: '2026-02' (auto-set saat pilih stortingGlobal)
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
  useEffect(() => {
    async function fetchData() {
      setLoading(true);
      setError('');
      try {
        const result = await getBukuPokok({
          cabangId: cabang.id,
          adminUid: selectedAdmin?.uid || '',
          status: statusFilter,
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
  }, [cabang.id, selectedAdmin?.uid, statusFilter]);

  // ==================== FILTER ====================
  const filtered = data?.nasabah?.filter((n) => {
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
  }) || [];

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

    // Generate hari kerja bulan yang dipilih (tanggal 1 s/d endDate, skip Minggu)
    const workingDates = [];
    const cur = new Date(selectedYear, selectedMonth, 1);
    while (cur <= endDate) {
      if (cur.getDay() !== 0) { // Skip Minggu
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

    // String hari ini dalam format "dd Mmm yyyy" (WIB)
    const todayDateStr = `${String(todayDate.getDate()).padStart(2, '0')} ${BULAN_INDO_ARR[todayDate.getMonth()]} ${todayDate.getFullYear()}`;

    // Helper: cari besarPinjaman yang berlaku pada targetDate berdasarkan pinjamanHistory.
    // pinjamanHistory menyimpan nilai LAMA beserta berlakuSampai (hari terakhir nilai itu valid).
    // Algoritma: cari entry dengan berlakuSampai >= targetDate yang paling awal.
    //   - Jika ada → gunakan besarPinjaman dari entry itu (nilai lama yang masih berlaku)
    //   - Jika tidak ada → gunakan n.besarPinjaman saat ini (nilai paling baru)
    // Contoh lanjut pinjaman 500k→1M pada 13 Mar:
    //   history = { besarPinjaman: 500k, berlakuSampai: "13 Mar 2026" }
    //   Untuk targetDate 13 Mar: berlakuSampai >= 13 Mar → gunakan 500k ✓
    //   Untuk targetDate 14 Mar: tidak ada history >= 14 Mar → gunakan current 1M ✓
    const getEffectiveBesarPinjaman = (n, targetDate) => {
      if (!n.pinjamanHistory) return n.besarPinjaman || 0;
      const entries = Object.values(n.pinjamanHistory)
        .map(h => ({ besarPinjaman: h.besarPinjaman, berlakuSampaiDate: parseDateStr(h.berlakuSampai) }))
        .filter(h => h.berlakuSampaiDate && h.berlakuSampaiDate >= targetDate)
        .sort((a, b) => a.berlakuSampaiDate - b.berlakuSampaiDate);
      return entries.length > 0 ? entries[0].besarPinjaman : (n.besarPinjaman || 0);
    };

    // Helper: hitung target aktif pada tanggal tertentu dari data nasabah (tanpa RTDB tambahan).
    // Aturan konsisten dengan Android RingkasanDashboardScreen & summaryHelpers.js:
    //  - getEffectiveBesarPinjaman × 3% (besarPinjaman sesuai tanggal, dari pinjamanHistory)
    //  - Exclude nasabah belum cair / cair pada tanggal itu sendiri
    //  - Exclude nasabah > 3 bulan dari tanggalPencairan pada tanggal tsb
    //  - Exclude nasabah yang sudah lunas SEBELUM tanggal tsb (payDate < targetDate)
    const calculateTargetForDate = (targetDateStr) => {
      const targetDate = parseDateStr(targetDateStr);
      if (!targetDate) return 0;

      // Batas 3 bulan sebelum targetDate
      const threeMonthsBefore = new Date(targetDate);
      threeMonthsBefore.setMonth(threeMonthsBefore.getMonth() - 3);

      let total = 0;
      allNasabah.forEach(n => {
        const status = (n.status || '').toLowerCase();
        // Exclude status non-aktif
        if (['ditolak', 'tidak aktif', 'disetujui', 'menunggu approval'].includes(status)) return;

        // Harus sudah ada tanggalPencairan (sudah dicairkan)
        const tglCair = (n.tanggalPencairan || '').trim();
        if (!tglCair) return;

        const caiDate = parseDateStr(tglCair);
        if (!caiDate) return;

        // Nasabah baru cair pada targetDate → belum dihitung hari itu (mulai besok)
        // Nasabah belum cair sama sekali pada targetDate → skip
        if (caiDate >= targetDate) return;

        // Sudah > 3 bulan dari tanggalPencairan pada targetDate → exclude
        if (caiDate < threeMonthsBefore) return;

        // Hitung total dibayar nasabah ini SEBELUM targetDate (bukan pada hari itu)
        // Tujuan: nasabah yang baru lunas PADA targetDate masih masuk target hari itu
        let totalDibayarSampaiD = 0;
        if (n.pembayaran) {
          Object.entries(n.pembayaran).forEach(([payDateStr, payData]) => {
            const payDate = parseDateStr(payDateStr);
            if (payDate && payDate < targetDate) {
              totalDibayarSampaiD += payData.total || 0;
            }
          });
        }

        // Sudah lunas sebelum targetDate → exclude
        const totalPelunasan = n.totalPelunasan || 0;
        if (totalPelunasan > 0 && totalDibayarSampaiD >= totalPelunasan) return;

        // Gunakan besarPinjaman yang berlaku pada targetDate (bukan nilai terkini)
        total += Math.floor(getEffectiveBesarPinjaman(n, targetDate) * 3 / 100);
      });
      return total;
    };

    // Hitung per tanggal
    let dropBerjalan = 0;
    let targetBerjalan = 0;
    let stortingBerjalan = 0;
    const rows = [];

    for (const dateStr of workingDates) {
      let dropKini = 0;
      let stortingKini = 0;
      let pbKini = 0, l1Kini = 0, cmKini = 0, mbKini = 0, mlKini = 0;

      allNasabah.forEach(n => {
        // Drop Kini: total uang yang dicairkan pada tanggal ini
        const tglCair = n.tanggalPencairan || '';
        if (tglCair === dateStr) {
          dropKini += n.totalDiterima || n.besarPinjaman || 0;
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
      });

      // Target Kini per tanggal — dihitung dari data nasabah untuk semua tanggal.
      // Dengan payDate < targetDate, nasabah yang baru lunas/lanjut pinjaman HARI INI
      // masih dihitung dalam target hari ini (konsisten dengan buku manual koperasi).
      const targetKini = calculateTargetForDate(dateStr);

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

  // Stats
  const stats = {
    totalNasabah: data?.totalNasabah || 0,
    totalSisaUtang: data?.totalSisaUtang || 0,
    totalPinjaman: data?.totalPinjaman || 0,
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
            <div className="status-tabs">
              {[
                { key: 'aktif', label: 'Aktif' },
                { key: 'lunas', label: 'Lunas' },
                { key: 'semua', label: 'Semua' },
              ].map((tab) => (
                <button
                  key={tab.key}
                  onClick={() => setStatusFilter(tab.key)}
                  className={`status-tab ${statusFilter === tab.key ? 'active' : ''}`}
                >
                  {tab.label}
                </button>
              ))}
            </div>
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
                    // Batas: 12 bulan ke belakang
                    const now = new Date();
                    const wibOff = 7 * 60 * 60 * 1000;
                    const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOff);
                    const minDate = new Date(wib.getFullYear() - 1, wib.getMonth(), 1);
                    if (prev >= minDate) {
                      setStortingMonth(`${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`);
                    }
                  }}
                  title="Bulan sebelumnya"
                >
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m15 18-6-6 6-6"/></svg>
                </button>
                <span className="sg-month-label">
                  {(() => {
                    if (!stortingMonth) return '';
                    const BULAN_FULL = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'];
                    const [y, m] = stortingMonth.split('-').map(Number);
                    return `${BULAN_FULL[m - 1]} ${y}`;
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
                  {stortingGlobalData.length > 0 && (
                    <tfoot>
                      <tr>
                        <td style={{ fontWeight: 700, textAlign: 'center' }}>TOTAL</td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace" }}>
                          {formatRp(stortingGlobalData[stortingGlobalData.length - 1]?.dropBerjalan || 0)}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace" }}>
                          {formatRp(stortingGlobalData.reduce((s, r) => s + r.dropKini, 0))}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace" }}>
                          {formatRp(stortingGlobalData[stortingGlobalData.length - 1]?.targetBerjalan || 0)}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace" }}>
                          {formatRp(stortingGlobalData.reduce((s, r) => s + r.targetKini, 0))}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace" }}>
                          {formatRp(stortingGlobalData[stortingGlobalData.length - 1]?.stortingBerjalan || 0)}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace" }}>
                          {formatRp(stortingGlobalData.reduce((s, r) => s + r.stortingKini, 0))}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontSize: 12 }}>
                          {(() => {
                            const totalStorting = stortingGlobalData.reduce((s, r) => s + r.stortingKini, 0);
                            const totalTarget = stortingGlobalData.reduce((s, r) => s + r.targetKini, 0);
                            const avg = totalTarget > 0 ? Math.round(totalStorting / totalTarget * 100) : 0;
                            return avg > 0 ? `${avg}%` : '-';
                          })()}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                          {formatRp(stortingGlobalData.reduce((s, r) => s + r.pbKini, 0))}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                          {formatRp(stortingGlobalData.reduce((s, r) => s + r.l1Kini, 0))}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                          {formatRp(stortingGlobalData.reduce((s, r) => s + r.cmKini, 0))}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                          {formatRp(stortingGlobalData.reduce((s, r) => s + r.mbKini, 0))}
                        </td>
                        <td style={{ textAlign: 'center', fontWeight: 700, fontFamily: "'DM Mono', monospace", fontSize: 12 }}>
                          {formatRp(stortingGlobalData.reduce((s, r) => s + r.mlKini, 0))}
                        </td>
                      </tr>
                    </tfoot>
                  )}
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
                          <td className="sticky-col" style={{ left: stickyOffsets.nama, fontWeight: 600 }}>
                            {n.namaKtp}
                            {!selectedAdmin && <div className="admin-tag">{n.adminName?.replace('Resort ', '')}</div>}
                          </td>
                          <td className="sticky-col" style={{ left: stickyOffsets.panggilan, color: '#5a6b7c' }}>{n.namaPanggilan}</td>
                          <td className="nik-cell">{n.nik}</td>
                          <td style={{ textAlign: 'right', fontFamily: "'DM Mono', monospace" }}>{formatRp(n.besarPinjaman)}</td>
                          {isTabelMode && (
                            <td style={{ textAlign: 'right', fontFamily: "'DM Mono', monospace", color: '#2d7dd2' }}>{formatRp(n.simpanan || 0)}</td>
                          )}
                          <td style={{ textAlign: 'right', borderRight: '2px solid #7c3aed' }}>
                            {n.status === 'Lunas' ? (
                              <span className="lunas-badge">LUNAS</span>
                            ) : isTabelMode ? (
                              <span style={{ fontFamily: "'DM Mono', monospace" }}>{formatRp(n.sisaUtang + rowTotal)}</span>
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
                      <td colSpan={8} style={{ fontWeight: 700 }}>TOTAL</td>
                      {isTabelMode && (
                        <td style={{ textAlign: 'right', fontWeight: 700, fontFamily: "'DM Mono', monospace", color: '#2d7dd2' }}>
                          {formatRp(filtered.reduce((s, n) => s + (n.simpanan || 0), 0))}
                        </td>
                      )}
                      <td style={{ textAlign: 'right', fontWeight: 700, fontFamily: "'DM Mono', monospace", borderRight: '2px solid #7c3aed' }}>
                        {isTabelMode
                          ? formatRp(filtered.reduce((s, n) => {
                              const rt = displayDates.reduce((sd, d) => sd + (n.pembayaran?.[d]?.total || 0), 0);
                              return s + (n.sisaUtang || 0) + rt;
                            }, 0))
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
          {(nasabah.fotoKtpUrl || nasabah.fotoNasabahUrl) && (
            <div className="detail-photos">
              {nasabah.fotoKtpUrl && (
                <div className="detail-photo-card" onClick={() => setZoomImage(nasabah.fotoKtpUrl)}>
                  <img src={nasabah.fotoKtpUrl} alt="Foto KTP" loading="lazy" />
                  <span className="detail-photo-label">Foto KTP</span>
                </div>
              )}
              {nasabah.fotoNasabahUrl && (
                <div className="detail-photo-card" onClick={() => setZoomImage(nasabah.fotoNasabahUrl)}>
                  <img src={nasabah.fotoNasabahUrl} alt="Foto Nasabah" loading="lazy" />
                  <span className="detail-photo-label">Foto Nasabah</span>
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