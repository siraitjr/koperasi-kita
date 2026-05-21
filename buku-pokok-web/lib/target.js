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
// Global) — "sudah lunas?" dihitung dari pembayaran SEBELUM dateStr, bukan
// total all-time, sehingga baris tanggal lampau tetap akurat.
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
export function isEligibleForTarget(n, dateStr) {
  const cur = parseTanggalIndo(dateStr);
  if (!cur) return 0;

  // CF hanya memproses status aktif/active untuk target (switch case 'aktif'/'active').
  // status 'lunas' (string) TIDAK menambah target di CF — lunas-tepat-hari-ini
  // ditangkap lewat cabang lunas di bawah (saat status masih 'aktif').
  const statusLower = (n.status || '').toLowerCase();
  if (statusLower !== 'aktif' && statusLower !== 'active') return 0;

  const totalPelunasan = n.totalPelunasan || 0;
  if (totalPelunasan <= 0) return 0;

  // Status owing as-of awal hari (date-aware pengganti totalDibayar all-time CF).
  const paidBefore = sumPembayaranSebelum(n.pembayaran, cur);
  const paidHariIni = (n.pembayaran && n.pembayaran[dateStr] && n.pembayaran[dateStr].total) || 0;
  const sudahLunasSebelum = paidBefore >= totalPelunasan;
  const lunasTepatHariIni = !sudahLunasSebelum && (paidBefore + paidHariIni) >= totalPelunasan;

  // --- Cabang LUNAS (match CF summaryHelpers.js:886-895) ---
  // Hanya dihitung kalau lunas TEPAT hari ini (H+1). Tanpa gate 3-bulan / cair-hari-ini.
  if (sudahLunasSebelum || lunasTepatHariIni) {
    return lunasTepatHariIni ? Math.floor((n.besarPinjaman || 0) * 3 / 100) : 0;
  }

  // --- Cabang BELUM LUNAS (match CF summaryHelpers.js:896-935) ---
  // MENUNGGU_PENCAIRAN manual (simpanan belum dicairkan) — CF L843-844.
  const isMenunggu = n.statusKhusus === 'MENUNGGU_PENCAIRAN'
    && (n.statusPencairanSimpanan || '') !== 'Dicairkan';
  const isMenungguHariIni = isMenunggu && (n.tanggalStatusKhusus || '').trim() === dateStr;
  // MENUNGGU hanya dihitung pada hari ditandai; selain itu dikecualikan.
  if (isMenunggu && !isMenungguHariIni) return 0;

  // Tanggal acuan: pencairan → pengajuan → daftar.
  const tglAcuan = (n.tanggalPencairan || '').trim()
    || (n.tanggalPengajuan || '').trim()
    || (n.tanggalDaftar || '').trim();
  const acuan = parseTanggalIndo(tglAcuan);
  if (acuan) {
    // Belum cair / cair hari ini → mulai dihitung besok (exclude acuan >= dateStr).
    if (acuan >= cur) return 0;
    // Exclude > 3 bulan (batas: tanggal 1, tiga bulan sebelum bulan dateStr).
    const tigaBulanLalu = new Date(cur.getFullYear(), cur.getMonth() - 3, 1);
    if (acuan < tigaBulanLalu) return 0;
  }

  return Math.floor((n.besarPinjaman || 0) * 3 / 100);
}
