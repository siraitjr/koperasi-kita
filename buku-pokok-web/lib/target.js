// =========================================================================
// SHARED HELPER: Perhitungan Target Harian (besarPinjaman × 3%)
// -------------------------------------------------------------------------
// Single source of truth untuk Target Harian di web. Dipakai oleh:
//   - Buku Pokok / Storting Global  (app/pembukuan/page.js)
//   - Buku Rekap                    (app/kasir/page.js)
// agar keduanya TIDAK pernah divergen lagi.
//
// Logika 1:1 dengan Cloud Function fullRecalculateAdminSummary
// (functions/summaryHelpers.js:879-935) & Android RingkasanDashboardScreen.kt
// (yang dipakai dashboard Android sebagai sumber kebenaran), termasuk H+1:
//   - Nasabah lunas TEPAT pada dateStr tetap masuk target hari itu.
//   - Nasabah ditandai MENUNGGU_PENCAIRAN TEPAT pada dateStr tetap masuk
//     target hari itu; di hari lain MENUNGGU_PENCAIRAN dikecualikan.
//
// Date-aware: benar untuk kolom hari ini MAUPUN kolom historis (Storting
// Global). Status lunas memakai field tanggalLunasCicilan + totalDibayar
// all-time (identik CF/Android), BUKAN map n.pembayaran (yang sudah memfilter
// "Pelunasan Top-Up"); baris tanggal lampau tetap akurat via tanggalLunasCicilan.
//
// Catatan: `n` adalah objek nasabah hasil getBukuPokok (bukuPokokApi.js),
// dengan `pembayaran` berupa map { "dd MMM yyyy": { total } }.
// =========================================================================

const BULAN_INDO = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun',
                    'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
const BULAN_MAP = {};
BULAN_INDO.forEach((b, i) => { BULAN_MAP[b] = i; });

// Parse "21 Mei 2026" → Date (tengah malam lokal). null kalau format salah.
export function parseTanggalIndo(s) {
  if (!s) return null;
  const parts = String(s).trim().split(' ');
  if (parts.length !== 3) return null;
  const m = BULAN_MAP[parts[1]];
  if (m === undefined) return null;
  const day = parseInt(parts[0], 10);
  const year = parseInt(parts[2], 10);
  if (Number.isNaN(day) || Number.isNaN(year)) return null;
  return new Date(year, m, day);
}

// Total pembayaran (map per-tanggal) yang jatuh STRICTLY sebelum `cur`.
function sumPembayaranSebelum(pembayaran, cur) {
  if (!pembayaran) return 0;
  let total = 0;
  for (const [tgl, data] of Object.entries(pembayaran)) {
    const d = parseTanggalIndo(tgl);
    if (d && d < cur) total += (data && data.total) || 0;
  }
  return total;
}

// Apakah nasabah `n` masuk Target Harian pada `dateStr` ("dd MMM yyyy")?
// Return kontribusi target = floor(besarPinjaman × 3%), atau 0 kalau tidak eligible.
//
// =========================================================================
// ATURAN OPERASIONAL PIMPINAN (final, immutabilitas historis 04 Jun 2026):
// -------------------------------------------------------------------------
// Data historis WAJIB beku. Lihat kalender lampau dari hari ini = harus sama
// dengan kalkulasi pada hari itu sendiri. Nol penyusutan. Implementasi:
//
// POIN 1 — Lunas Cicilan (H+1)
//   tanggalLunasCicilan > dateStr → AKTIF (utang belum lunas pada kolom itu)
//   tanggalLunasCicilan === dateStr → AKTIF (lunas tepat hari itu = hari terakhir)
//   tanggalLunasCicilan < dateStr → 0 (sudah lunas sebelum kolom itu)
//
// POIN 2 — Menunggu Pencairan (MP)
//   tanggalStatusKhusus > dateStr → AKTIF (belum di-MP pada kolom itu, pinjaman
//     berjalan normal — admin men-set MP di kemudian hari)
//   tanggalStatusKhusus === dateStr → AKTIF (MP ditandai tepat hari itu, parity
//     dengan H+1 LUNAS)
//   tanggalStatusKhusus < dateStr → 0 (sudah MP sebelum kolom itu)
//
// POIN 3 — Batas 3 bulan kalender (relatif `tanggalPencairan`)
//   Strict integer-month math, TANPA new Date() / Date.now() / state hari ini.
//   Eligible bila (curYear*12+curMonth) − (acuanYear*12+acuanMonth) ≤ 3.
//   Cair Feb 2026 → Mei masih hitung; Jun (≥ Jun 1) STOP.
//   Cair Mar 2026 → Jun masih hitung; Jul (≥ Jul 1) STOP.
//
// Catatan: parseTanggalIndo memakai konstruktor `new Date(y,m,d)` dengan
// argumen eksplisit dari string — deterministik & TIDAK menyentuh state
// hari ini. Itu sah sesuai intent direktif ("no live-state"). Hanya `new
// Date()` no-arg & `Date.now()` yang DILARANG, dan keduanya nihil di file ini.
// =========================================================================
export function isEligibleForTarget(n, dateStr) {
  const cur = parseTanggalIndo(dateStr);
  if (!cur) return 0;

  // ===== Pre-guard live-status (date-aware, semua jalur immutabilitas) =====
  const statusLower = (n.status || '').toLowerCase();
  const isStatusAktif = statusLower === 'aktif' || statusLower === 'active';
  const lunasHariIni = (n.tanggalLunasCicilan || '').trim() === dateStr;
  const isMenungguPencairan = n.statusKhusus === 'MENUNGGU_PENCAIRAN'
    && (n.statusPencairanSimpanan || '') !== 'Dicairkan';
  const menungguHariIni = isMenungguPencairan && (n.tanggalStatusKhusus || '').trim() === dateStr;

  // POIN 1: nasabah KINI 'Lunas' tapi tglLunas > dateStr → pada dateStr masih AKTIF.
  const lunasDateGuard = parseTanggalIndo((n.tanggalLunasCicilan || '').trim());
  const masihAktifPadaTanggal = lunasDateGuard && lunasDateGuard > cur;

  // POIN 2: nasabah KINI 'MENUNGGU_PENCAIRAN' tapi tglStatusKhusus > dateStr →
  // pada dateStr belum di-MP, pinjaman masih berjalan → MASIH AKTIF.
  const mpDateGuard = parseTanggalIndo((n.tanggalStatusKhusus || '').trim());
  const belumMenungguPadaTanggal = isMenungguPencairan && mpDateGuard && mpDateGuard > cur;

  if (!isStatusAktif && !lunasHariIni && !menungguHariIni
      && !masihAktifPadaTanggal && !belumMenungguPadaTanggal) return 0;

  const target = Math.floor((n.besarPinjaman || 0) * 3 / 100);
  const totalPelunasan = n.totalPelunasan || 0;

  // ===== POIN 1 — Cabang LUNAS (match CF summaryHelpers.js:886-895) =====
  // tglLunas === dateStr → return target (lunas hari itu, hari terakhir aktif).
  // tglLunas <  dateStr → return 0 (sudah lunas sebelum kolom).
  // tglLunas >  dateStr → fall-through ke pemeriksaan belum-lunas (masih aktif).
  const isSudahLunas = totalPelunasan > 0 && (n.totalDibayar || 0) >= totalPelunasan;
  if (isSudahLunas) {
    const tglLunas = (n.tanggalLunasCicilan || '').trim();
    const lunasDate = parseTanggalIndo(tglLunas);
    if (lunasDate) {
      if (tglLunas === dateStr) return target;
      if (lunasDate < cur) return 0;
      // lunasDate > cur → masih aktif pada kolom historis ini → lanjut ke check di bawah.
    } else {
      // tanggalLunasCicilan kosong/invalid: fallback date-aware via map pembayaran
      // (CF/Android: hanya skip bila sumPembayaranSebelum >= totalPelunasan).
      if (sumPembayaranSebelum(n.pembayaran, cur) >= totalPelunasan) return 0;
    }
  }

  // ===== POIN 2 — Cabang BELUM LUNAS / MENUNGGU PENCAIRAN =====
  // Aturan baru date-aware:
  //   mpDate >  cur → JANGAN exclude (belum di-MP pada kolom itu, masih aktif).
  //   mpDate === cur → JANGAN exclude (MP hari itu = hari terakhir, parity H+1).
  //   mpDate <  cur → EXCLUDE (sudah MP sebelum kolom itu).
  //   mpDate tidak parseable → fallback lama (exclude kecuali isMenungguHariIni).
  if (isMenungguPencairan) {
    if (mpDateGuard) {
      if (mpDateGuard < cur) return 0;
      // mpDateGuard >= cur → include (lanjut ke 3-month check).
    } else {
      if (!menungguHariIni) return 0;
    }
  }

  // Cair TEPAT pada tanggal ini → mulai dihitung besok (CF: pencairan === today).
  const tglPencairan = (n.tanggalPencairan || '').trim();
  if (tglPencairan && tglPencairan === dateStr) return 0;

  // Tanggal acuan: pencairan → pengajuan → daftar.
  const tglAcuan = tglPencairan
    || (n.tanggalPengajuan || '').trim()
    || (n.tanggalDaftar || '').trim();
  const acuan = parseTanggalIndo(tglAcuan);
  if (acuan) {
    // Belum aktif (acuan setelah tanggal ini) → date-aware exclude utk kolom historis.
    if (acuan > cur) return 0;

    // ===== POIN 3 — Batas 3 bulan kalender (integer-month math, no live-state) =====
    // Boundary: bulan kolom > (bulan_acuan + 3) → STOP. Diukur sebagai indeks
    // bulan absolut (year*12 + month0..11) supaya menyeberang tahun aman.
    // Contoh pimpinan:
    //   Cair Feb 2026 (idx = y*12 + 1); kolom Jun 2026 (idx = y*12 + 5)
    //     → diff = 4 → STOP (Jun 1 dst.). ✓
    //   Cair Mar 2026 (idx = y*12 + 2); kolom Jul 2026 (idx = y*12 + 6)
    //     → diff = 4 → STOP. ✓
    //   Cair Feb 2026; kolom Mei 2026 (idx = y*12 + 4) → diff = 3 → masih hitung. ✓
    const acuanMonthIdx = acuan.getFullYear() * 12 + acuan.getMonth();
    const curMonthIdx = cur.getFullYear() * 12 + cur.getMonth();
    if (curMonthIdx - acuanMonthIdx > 3) return 0;
  }

  return target;
}
