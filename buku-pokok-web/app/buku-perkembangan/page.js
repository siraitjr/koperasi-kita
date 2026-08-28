'use client';

// app/buku-perkembangan/page.js
// =========================================================================
// BUKU PERKEMBANGAN — Standalone Page (Development Ledger)
// =========================================================================
// Halaman MANDIRI (bukan sub-view Buku Pokok). Bootstrap auth + cabang +
// data-fetch sendiri (parity pola /pembukuan & /kasir), lalu render tabel
// 13-kolom agregat per resort.
//
// 13 kolom (formula final pimpinan 16 Jun 2026):
//   Saldo Awal    = Σ sisaUtang awal bulan (loan cair < monthStart)
//   Drop          = Σ besarPinjaman loan cair di bulan target
//   Jasa          = Drop × 0.2 (20%)
//   Mutasi Masuk  = 0 (placeholder)
//   Jumlah        = Saldo Awal + Drop + Jasa
//   Storting      = Σ pembayaran di bulan target (incl. riwayatPinjaman)
//   Mutasi Keluar = 0 (placeholder)
//   Saldo Akhir   = Jumlah − Storting
//   Saldo ML/MB   = snapshot Σ sisaUtang end-of-month per kategori ML/MB
//   Saldo Lancar  = Saldo Akhir − Saldo ML − Saldo MB
// =========================================================================

import { useState, useEffect } from 'react';
// BLOK 6 — penjaga halaman pindah ke Supabase. `signInWithCustomToken`
// ditahan untuk blok SSO di bawah, yang kini INERT (lihat komentar di sana).
import { signInWithCustomToken } from 'firebase/auth';
import { pantauSesi } from '../../lib/authSupabase';
import { auth } from '../../lib/firebase';
import { getSummary, getBukuPokok, getKasirSummary } from '../../lib/apiSupabase';
import { formatRp } from '../../lib/format';

// Role yang boleh melihat menu kasir di nav (parity pembukuan/page.js).
const KASIR_VIEW_ROLES = ['pimpinan', 'koordinator', 'pengawas', 'kasir_wilayah', 'sekretaris'];

const BULAN_INDO = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
const BULAN_FULL = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'];

// Parse "dd MMM yyyy" → {year, month, day} (self-contained; sama semantik
// parseTanggalIndo di pembukuan/page.js).
function parseTanggalIndo(tgl) {
  if (!tgl || typeof tgl !== 'string') return null;
  const parts = tgl.trim().split(' ');
  if (parts.length !== 3) return null;
  const day = parseInt(parts[0], 10);
  const month = BULAN_INDO.indexOf(parts[1]);
  const year = parseInt(parts[2], 10);
  if (Number.isNaN(day) || month === -1 || Number.isNaN(year)) return null;
  return { year, month, day };
}

// Kategori PB/L1/CM/MB/ML berdasarkan selisih bulan tanggalPencairan vs refDate.
// 1:1 dengan getKategoriNasabah() di pembukuan/page.js (immutabilitas historis).
function getKategoriNasabah(nasabah, refDateStr) {
  const tgl = (nasabah.tanggalPencairan || '').trim();
  const parsed = parseTanggalIndo(tgl);
  if (!parsed) return 'ML';

  let refMonth, refYear;
  const refParsed = refDateStr ? parseTanggalIndo(String(refDateStr).trim()) : null;
  if (refParsed) {
    refMonth = refParsed.month;
    refYear = refParsed.year;
  } else {
    const now = new Date();
    const wibOffset = 7 * 60 * 60 * 1000;
    const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOffset);
    refMonth = wib.getMonth();
    refYear = wib.getFullYear();
  }
  const diff = (refYear - parsed.year) * 12 + (refMonth - parsed.month);
  if (diff === 0) return 'PB';
  if (diff === 1) return 'L1';
  if (diff === 2) return 'CM';
  if (diff === 3) return 'MB';
  return 'ML';
}

// Konversi {year, month, day} → Date (lokal) untuk komparasi.
function toDate(p) {
  return p ? new Date(p.year, p.month, p.day) : null;
}

// =========================================================================
// AGREGATOR — 13 kolom per resort. Pure: hanya konsumsi bukuData.nasabah.
// =========================================================================
export function computeBukuPerkembanganRows(bukuData, admins, stortingMonth) {
  if (!bukuData?.nasabah || !stortingMonth || !admins?.length) return [];

  const [bpY, bpM] = stortingMonth.split('-').map(Number);
  const monthStart = new Date(bpY, bpM - 1, 1);
  const monthEnd = new Date(bpY, bpM, 0); // hari terakhir bulan
  const refDateEndOfMonth = `${String(monthEnd.getDate()).padStart(2, '0')} ${BULAN_INDO[monthEnd.getMonth()]} ${monthEnd.getFullYear()}`;

  // Sum pembayaran (incl. riwayatPinjaman) dalam window [from, to] inklusif.
  const sumPaymentsInWindow = (n, from, to) => {
    let s = 0;
    const accumulate = (paymap) => {
      if (!paymap) return;
      Object.entries(paymap).forEach(([tgl, v]) => {
        const d = toDate(parseTanggalIndo(tgl));
        if (!d) return;
        if (from && d < from) return;
        if (to && d > to) return;
        s += (v && v.total) || 0;
      });
    };
    accumulate(n.pembayaran);
    (n.riwayatPinjaman || []).forEach((r) => accumulate(r.pembayaran));
    return s;
  };

  return admins.map((adm, idx) => {
    const resortNasabah = (bukuData.nasabah || []).filter(
      (n) => n.adminUid === adm.uid && !n.isHistorical && !n.isOrphan
    );

    // Saldo Awal: Σ sisaUtang utk loan cair SEBELUM monthStart.
    let saldoAwal = 0;
    resortNasabah.forEach((n) => {
      const tglCair = toDate(parseTanggalIndo((n.tanggalPencairan || '').trim()));
      if (!tglCair || tglCair >= monthStart) return;
      const bayarSebelumBulan = sumPaymentsInWindow(n, null, new Date(monthStart.getTime() - 1));
      saldoAwal += Math.max((n.totalPelunasan || 0) - bayarSebelumBulan, 0);
    });

    // Drop: Σ besarPinjaman loan cair DI bulan target.
    const drop = resortNasabah.reduce((s, n) => {
      const tglCair = toDate(parseTanggalIndo((n.tanggalPencairan || '').trim()));
      const inMonth = tglCair && tglCair >= monthStart && tglCair <= monthEnd;
      return s + (inMonth ? (n.besarPinjaman || 0) : 0);
    }, 0);

    const jasa = Math.round(drop * 0.2);           // Jasa = Drop × 20%
    const mutasiMasuk = 0;                           // placeholder
    const jumlah = saldoAwal + drop + jasa;

    const storting = resortNasabah.reduce(
      (s, n) => s + sumPaymentsInWindow(n, monthStart, monthEnd), 0
    );

    const mutasiKeluar = 0;                          // placeholder
    const saldoAkhir = jumlah - storting;

    // Saldo ML / MB: snapshot Σ sisaUtang end-of-month per kategori.
    let saldoML = 0;
    let saldoMB = 0;
    resortNasabah.forEach((n) => {
      const tglCair = toDate(parseTanggalIndo((n.tanggalPencairan || '').trim()));
      if (!tglCair || tglCair > monthEnd) return;
      const bayarSampaiAkhir = sumPaymentsInWindow(n, null, monthEnd);
      const sisaAkhir = Math.max((n.totalPelunasan || 0) - bayarSampaiAkhir, 0);
      const kat = getKategoriNasabah(n, refDateEndOfMonth);
      if (kat === 'ML') saldoML += sisaAkhir;
      else if (kat === 'MB') saldoMB += sisaAkhir;
    });

    const saldoLancar = saldoAkhir - saldoML - saldoMB;

    return {
      nomor: idx + 1,
      resortName: adm.name,
      saldoAwal, drop, jasa, mutasiMasuk,
      jumlah, storting, mutasiKeluar,
      saldoAkhir, saldoML, saldoMB, saldoLancar,
    };
  });
}

// Bulan ini (WIB) → "YYYY-MM".
function currentMonthWIB() {
  const now = new Date();
  const wibOff = 7 * 60 * 60 * 1000;
  const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOff);
  return `${wib.getFullYear()}-${String(wib.getMonth() + 1).padStart(2, '0')}`;
}

// =========================================================================
// TOP BAR NAVIGATION — copy lokal (parity pembukuan/page.js TopBarNav).
// -------------------------------------------------------------------------
// Mengikuti konvensi project: tiap halaman punya copy nav sendiri (lihat
// KasirTopBarNav di kasir/page.js) — TopBarNav di pembukuan tidak di-export.
// Struktur menu & id IDENTIK dgn pembukuan agar konsisten visual & routing.
// =========================================================================
function TopBarNav({ currentScreen, onSelectBook, showKasirMenus }) {
  const menus = [
    { id: 'bukuPokok', label: 'Buku Pokok' },
    { id: 'bukuPerkembangan', label: 'Buku Perkembangan' },
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

// =========================================================================
// PAGE COMPONENT
// =========================================================================
export default function BukuPerkembanganPage() {
  const [screen, setScreen] = useState('loading'); // loading | login | picker | ledger
  const [userData, setUserData] = useState(null);
  const [cabangList, setCabangList] = useState([]);
  const [kasirCabangList, setKasirCabangList] = useState([]);
  const [activeCabang, setActiveCabang] = useState(null);
  const [bukuData, setBukuData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [stortingMonth, setStortingMonth] = useState(currentMonthWIB());

  // ---- Auto-login dari Android (idToken di URL) ----
  useEffect(() => {
    const urlParams = new URLSearchParams(window.location.search);
    const idToken = urlParams.get('idToken');
    if (!idToken) return;
    window.history.replaceState({}, '', window.location.pathname + window.location.search.replace(/([?&])idToken=[^&]*/, '').replace(/^&/, '?'));
    // ⚠ INERT SEJAK BLOK 6 — membuat sesi Firebase yang tidak dibaca lagi.
    // Lihat catatan sama di pembukuan/page.js. Utang U-2, 024 §5.
    fetch('https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net/generateAutoLoginToken', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken }),
    })
      .then((res) => res.json())
      .then((data) => { if (data.customToken) return signInWithCustomToken(auth, data.customToken); })
      .catch((err) => console.error('Auto-login gagal:', err));
  }, []);

  // ---- Auth + cabang resolution ----
  useEffect(() => {
    const unsub = pantauSesi(async (pengguna) => {
      if (!pengguna) {
        // Tidak login → arahkan ke halaman utama untuk login.
        window.location.href = '/pembukuan';
        return;
      }
      try {
        const result = await getSummary();
        if (!result.success) { setScreen('login'); return; }
        setUserData(result.data.user);
        const list = result.data.cabangList || [];
        setCabangList(list);

        // ✅ Kasir cabang list utk showKasirMenus (parity pembukuan TopBarNav).
        // Optional & non-blocking — gagal fetch tidak menghentikan halaman.
        if (KASIR_VIEW_ROLES.includes(result.data.user?.role)) {
          try {
            const kasirResult = await getKasirSummary();
            if (kasirResult.success) setKasirCabangList(kasirResult.data.cabangList || []);
          } catch (e) { /* optional */ }
        }

        // Resolve active cabang: URL ?cabang= > shared sessionStorage > single auto.
        const urlParams = new URLSearchParams(window.location.search);
        const urlCabang = urlParams.get('cabang');
        let resolved = null;
        if (urlCabang) resolved = list.find((c) => c.id === urlCabang) || null;
        if (!resolved) {
          try {
            const sharedId = sessionStorage.getItem('ksp_active_cabang_id');
            if (sharedId) resolved = list.find((c) => c.id === sharedId) || null;
          } catch (e) { /* ignore */ }
        }
        if (!resolved && list.length === 1) resolved = list[0];

        if (resolved) {
          setActiveCabang(resolved);
          setScreen('ledger');
        } else {
          setScreen('picker');
        }
      } catch (e) {
        setError('Gagal memuat data: ' + e.message);
        setScreen('login');
      }
    });
    return () => unsub();
  }, []);

  // ---- Persist active cabang (shared lintas-menu) ----
  useEffect(() => {
    if (activeCabang?.id) {
      try { sessionStorage.setItem('ksp_active_cabang_id', activeCabang.id); } catch (e) { /* ignore */ }
    }
  }, [activeCabang]);

  // ---- Fetch bukuData untuk cabang aktif (4-bulan window, parity Buku Pokok) ----
  useEffect(() => {
    if (!activeCabang?.id) return;
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError('');
      try {
        const _now = new Date();
        const _months = [];
        for (let i = 0; i < 4; i++) {
          const d = new Date(_now.getFullYear(), _now.getMonth() - i, 1);
          _months.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`);
        }
        const result = await getBukuPokok({
          cabangId: activeCabang.id,
          adminUid: '',
          status: 'semua',          // butuh semua nasabah utk saldo & kategori historis
          bulan: _months.join(','),
        });
        if (!cancelled && result.success && result.type === 'buku_pokok') {
          setBukuData(result.data);
        }
      } catch (err) {
        if (!cancelled) setError('Gagal memuat data: ' + err.message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [activeCabang?.id]);

  const admins = activeCabang?.admins || [];
  const rows = computeBukuPerkembanganRows(bukuData, admins, stortingMonth);

  const totals = rows.reduce((a, r) => ({
    saldoAwal: a.saldoAwal + r.saldoAwal, drop: a.drop + r.drop, jasa: a.jasa + r.jasa,
    mutasiMasuk: a.mutasiMasuk + r.mutasiMasuk, jumlah: a.jumlah + r.jumlah,
    storting: a.storting + r.storting, mutasiKeluar: a.mutasiKeluar + r.mutasiKeluar,
    saldoAkhir: a.saldoAkhir + r.saldoAkhir, saldoML: a.saldoML + r.saldoML,
    saldoMB: a.saldoMB + r.saldoMB, saldoLancar: a.saldoLancar + r.saldoLancar,
  }), { saldoAwal: 0, drop: 0, jasa: 0, mutasiMasuk: 0, jumlah: 0, storting: 0, mutasiKeluar: 0, saldoAkhir: 0, saldoML: 0, saldoMB: 0, saldoLancar: 0 });

  // ✅ showKasirMenus identik kondisi pembukuan/page.js (role + ada kasir cabang).
  const showKasirMenus = KASIR_VIEW_ROLES.includes(userData?.role) && kasirCabangList.length > 0;

  // ✅ Cross-page nav dari TopBarNav. Pola sama dgn kasir handleKasirNavigate:
  // halaman lain di-route via window.location (full route), halaman ini = active.
  const handleSelectBook = (book) => {
    if (book === 'bukuPerkembangan') return; // halaman saat ini (pill active/disabled)
    if (book === 'bukuPokok' || book === 'jurnalTransaksi') {
      // Keduanya berada di /pembukuan (sama dgn kasir handleKasirNavigate).
      window.location.href = '/pembukuan';
      return;
    }
    // Menu kasir → /kasir?screen=<mapped> (mapping identik handleSelectBook pembukuan).
    const kasirScreenMap = {
      jurnalKasir: 'jurnal',
      bukuRekap: 'bukuRekap',
      kasPenuntun: 'kasPenuntun',
      bukuTunai: 'bukuTunai',
      bukuEkspedisi: 'bukuEkspedisi',
      ringkasanKas: 'ringkasan',
      absensiKaryawan: 'absensi',
    };
    if (kasirScreenMap[book]) {
      window.location.href = `/kasir?screen=${kasirScreenMap[book]}`;
    }
  };

  const shiftMonth = (dir) => {
    const [y, m] = stortingMonth.split('-').map(Number);
    const target = new Date(y, m - 1 + dir, 1);
    const now = new Date();
    const wibOff = 7 * 60 * 60 * 1000;
    const wib = new Date(now.getTime() + (now.getTimezoneOffset() * 60000) + wibOff);
    const maxDate = new Date(wib.getFullYear(), wib.getMonth(), 1);
    const minDate = new Date(wib.getFullYear(), wib.getMonth() - 3, 1);
    if (target >= minDate && target <= maxDate) {
      setStortingMonth(`${target.getFullYear()}-${String(target.getMonth() + 1).padStart(2, '0')}`);
    }
  };

  // ---- RENDER ----
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

  const [smY, smM] = stortingMonth.split('-').map(Number);
  const monthLabel = `${BULAN_FULL[smM - 1]} ${smY}`;

  const cellRight = { textAlign: 'right', fontFamily: "'DM Mono', monospace" };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={() => { window.location.href = '/pembukuan'; }} className="btn-back" title="Kembali ke Buku Pokok">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="m12 19-7-7 7-7" /><path d="M19 12H5" />
            </svg>
          </button>
          <div>
            <h1>Buku Perkembangan{activeCabang ? ` — ${activeCabang.name}` : ''}</h1>
            <p>Perkembangan per Resort</p>
          </div>
        </div>
        <TopBarNav currentScreen="bukuPerkembangan" onSelectBook={handleSelectBook} showKasirMenus={showKasirMenus} />
        <div className="top-bar-right">
          {userData && (
            <div className="user-badge">
              <span className="user-name">{userData.name}</span>
              <span className="user-role">{userData.role}</span>
            </div>
          )}
        </div>
      </header>

      <main className="buku-content">
        {screen === 'picker' ? (
          <div className="table-wrapper" style={{ padding: 24 }}>
            <h2 style={{ fontSize: 16, fontWeight: 700, marginBottom: 12 }}>Pilih Cabang</h2>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {cabangList.map((c) => (
                <button
                  key={c.id}
                  className="top-bar-nav-item"
                  onClick={() => { setActiveCabang(c); setScreen('ledger'); }}
                >
                  {c.name}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <>
            {/* Month selector */}
            <div className="sg-month-selector">
              <button className="sg-month-nav" onClick={() => shiftMonth(-1)} title="Bulan sebelumnya">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m15 18-6-6 6-6" /></svg>
              </button>
              <span className="sg-month-label">Buku Perkembangan — {monthLabel}</span>
              <button className="sg-month-nav" onClick={() => shiftMonth(1)} title="Bulan berikutnya">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m9 18 6-6-6-6" /></svg>
              </button>
            </div>

            {error && <div className="table-wrapper" style={{ padding: 16, color: 'var(--danger)' }}>{error}</div>}

            <div className="table-wrapper">
              <table className="buku-table">
                <thead>
                  <tr>
                    <th style={{ minWidth: 50, textAlign: 'center' }}>No</th>
                    <th style={{ minWidth: 140, textAlign: 'left' }}>Nama Resort</th>
                    <th style={{ minWidth: 110, textAlign: 'right' }}>Saldo Awal</th>
                    <th style={{ minWidth: 110, textAlign: 'right', color: '#2d7dd2' }}>Drop</th>
                    <th style={{ minWidth: 100, textAlign: 'right', color: '#7c3aed' }}>Jasa 20%</th>
                    <th style={{ minWidth: 100, textAlign: 'right' }}>Mutasi Masuk</th>
                    <th style={{ minWidth: 120, textAlign: 'right', background: '#f3f4f6', fontWeight: 800 }}>Jumlah</th>
                    <th style={{ minWidth: 110, textAlign: 'right', color: '#0f6b54' }}>Storting</th>
                    <th style={{ minWidth: 100, textAlign: 'right' }}>Mutasi Keluar</th>
                    <th style={{ minWidth: 120, textAlign: 'right', background: '#fef9c3', fontWeight: 800 }}>Saldo Akhir</th>
                    <th style={{ minWidth: 100, textAlign: 'right', color: '#dc2626' }}>Saldo ML</th>
                    <th style={{ minWidth: 100, textAlign: 'right', color: '#ea580c' }}>Saldo MB</th>
                    <th style={{ minWidth: 110, textAlign: 'right', color: '#0f6b54', fontWeight: 800 }}>Saldo Lancar</th>
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr><td colSpan={13} className="empty-cell">Memuat data...</td></tr>
                  ) : rows.length === 0 ? (
                    <tr><td colSpan={13} className="empty-cell">Tidak ada data resort untuk bulan ini</td></tr>
                  ) : (
                    rows.map((r) => (
                      <tr key={r.resortName}>
                        <td style={{ textAlign: 'center', fontWeight: 700 }}>{r.nomor}</td>
                        <td style={{ fontWeight: 600 }}>{r.resortName}</td>
                        <td style={cellRight}>{r.saldoAwal > 0 ? formatRp(r.saldoAwal) : '-'}</td>
                        <td style={{ ...cellRight, color: '#2d7dd2' }}>{r.drop > 0 ? formatRp(r.drop) : '-'}</td>
                        <td style={{ ...cellRight, color: '#7c3aed' }}>{r.jasa > 0 ? formatRp(r.jasa) : '-'}</td>
                        <td style={cellRight}>{r.mutasiMasuk > 0 ? formatRp(r.mutasiMasuk) : '-'}</td>
                        <td style={{ ...cellRight, background: '#f3f4f6', fontWeight: 700 }}>{r.jumlah > 0 ? formatRp(r.jumlah) : '-'}</td>
                        <td style={{ ...cellRight, color: '#0f6b54' }}>{r.storting > 0 ? formatRp(r.storting) : '-'}</td>
                        <td style={cellRight}>{r.mutasiKeluar > 0 ? formatRp(r.mutasiKeluar) : '-'}</td>
                        <td style={{ ...cellRight, background: '#fef9c3', fontWeight: 700 }}>{r.saldoAkhir !== 0 ? formatRp(r.saldoAkhir) : '-'}</td>
                        <td style={{ ...cellRight, color: '#dc2626' }}>{r.saldoML > 0 ? formatRp(r.saldoML) : '-'}</td>
                        <td style={{ ...cellRight, color: '#ea580c' }}>{r.saldoMB > 0 ? formatRp(r.saldoMB) : '-'}</td>
                        <td style={{ ...cellRight, color: '#0f6b54', fontWeight: 700 }}>{r.saldoLancar !== 0 ? formatRp(r.saldoLancar) : '-'}</td>
                      </tr>
                    ))
                  )}
                </tbody>
                {!loading && rows.length > 0 && (
                  <tfoot>
                    <tr style={{ borderTop: '2px solid #7c3aed', background: '#f8f9fa' }}>
                      <td colSpan={2} style={{ textAlign: 'right', fontWeight: 800, padding: '10px 12px' }}>TOTAL</td>
                      <td style={{ ...cellRight, fontWeight: 800, padding: '10px 12px' }}>{formatRp(totals.saldoAwal)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, color: '#2d7dd2', padding: '10px 12px' }}>{formatRp(totals.drop)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, color: '#7c3aed', padding: '10px 12px' }}>{formatRp(totals.jasa)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, padding: '10px 12px' }}>{formatRp(totals.mutasiMasuk)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, background: '#e5e7eb', padding: '10px 12px' }}>{formatRp(totals.jumlah)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, color: '#0f6b54', padding: '10px 12px' }}>{formatRp(totals.storting)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, padding: '10px 12px' }}>{formatRp(totals.mutasiKeluar)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, background: '#fde68a', padding: '10px 12px' }}>{formatRp(totals.saldoAkhir)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, color: '#dc2626', padding: '10px 12px' }}>{formatRp(totals.saldoML)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, color: '#ea580c', padding: '10px 12px' }}>{formatRp(totals.saldoMB)}</td>
                      <td style={{ ...cellRight, fontWeight: 800, color: '#0f6b54', padding: '10px 12px' }}>{formatRp(totals.saldoLancar)}</td>
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
          </>
        )}
      </main>
    </div>
  );
}
