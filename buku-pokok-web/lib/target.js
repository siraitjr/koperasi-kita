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
export function isEligibleForTarget(n, dateStr) {
  const cur = parseTanggalIndo(dateStr);
  if (!cur) return 0;

  // Android RingkasanDashboardScreen.kt:151-154 — nasabah masuk target bila
  // (isStatusAktif || lunas cicilan HARI INI || ditandai MENUNGGU_PENCAIRAN hari ini).
  // Pada hari pelunasan, field `status` bisa sudah jadi "Lunas" tapi nasabah TETAP
  // dihitung sampai besok (H+1) — CF mempertahankannya via delta incremental
  // (summaryHelpers.js:560-569). Karena itu guard status tak boleh menolak status
  // non-aktif yang lunas/menunggu-pencairan tepat pada tanggal kolom ini.
  const statusLower = (n.status || '').toLowerCase();
  const isStatusAktif = statusLower === 'aktif' || statusLower === 'active';
  const lunasHariIni = (n.tanggalLunasCicilan || '').trim() === dateStr;
  const isMenungguPencairan = n.statusKhusus === 'MENUNGGU_PENCAIRAN'
    && (n.statusPencairanSimpanan || '') !== 'Dicairkan';
  const menungguHariIni = isMenungguPencairan && (n.tanggalStatusKhusus || '').trim() === dateStr;
  // ✅ IMMUTABILITAS HISTORIS: nasabah yang KINI berstatus "Lunas" tetapi tanggal
  // lunasnya (tanggalLunasCicilan) jatuh SETELAH dateStr berarti pada dateStr ia
  // MASIH AKTIF — utang belum lunas pada kolom historis itu. Guard live-status
  // lama menolaknya (field status sudah "Lunas") sebelum logika date-aware di
  // bawah sempat jalan → kolom historis menyusut saat nasabah dilunasi di hari
  // berikutnya (melanggar prinsip data historis terkunci). Izinkan lewat di sini;
  // cabang LUNAS date-aware (baris ~90: lunasDate > cur → fall-through) tetap
  // mengembalikan target penuh. Untuk kolom HARI INI tak ada efek: tanggal lunas
  // di masa depan mustahil, jadi masihAktifPadaTanggal selalu false → parity
  // CF/Android terjaga 1:1.
  const lunasDateGuard = parseTanggalIndo((n.tanggalLunasCicilan || '').trim());
  const masihAktifPadaTanggal = lunasDateGuard && lunasDateGuard > cur;
  if (!isStatusAktif && !lunasHariIni && !menungguHariIni && !masihAktifPadaTanggal) return 0;

  const target = Math.floor((n.besarPinjaman || 0) * 3 / 100);
  const totalPelunasan = n.totalPelunasan || 0;

  // --- Cabang LUNAS (match CF summaryHelpers.js:886-895) ---
  // CF pakai totalDibayar all-time (calculateTotalDibayar: termasuk "Pelunasan Top-Up",
  // exclude "Bunga") + field tanggalLunasCicilan — keduanya sudah disediakan bukuPokokApi.
  // Sengaja pakai field yang sama, BUKAN map n.pembayaran (yang sudah memfilter Top-Up),
  // agar identik dengan CF/Android. Date-aware: kolom historis tetap akurat lewat
  // perbandingan tanggalLunasCicilan vs dateStr.
  const isSudahLunas = totalPelunasan > 0 && (n.totalDibayar || 0) >= totalPelunasan;
  if (isSudahLunas) {
    const tglLunas = (n.tanggalLunasCicilan || '').trim();
    const lunasDate = parseTanggalIndo(tglLunas);
    if (lunasDate) {
      if (tglLunas === dateStr) return target; // lunas TEPAT hari itu (H+1) → tetap kena target
      if (lunasDate < cur) return 0;           // sudah lunas sebelum tanggal ini
      // lunasDate > cur → pada tanggal (historis) ini nasabah masih belum lunas → lanjut.
    } else {
      // tanggalLunasCicilan kosong/invalid: utk hari ini CF tak menambah (field ≠ today) &
      // isSudahLunas ⇒ skip belum-lunas. Fallback date-aware via map utk kolom historis.
      if (sumPembayaranSebelum(n.pembayaran, cur) >= totalPelunasan) return 0;
    }
  }

  // --- Cabang BELUM LUNAS (match CF summaryHelpers.js:896-935) ---
  // MENUNGGU_PENCAIRAN manual (simpanan belum dicairkan) — CF L843-844.
  const isMenunggu = n.statusKhusus === 'MENUNGGU_PENCAIRAN'
    && (n.statusPencairanSimpanan || '') !== 'Dicairkan';
  const isMenungguHariIni = isMenunggu && (n.tanggalStatusKhusus || '').trim() === dateStr;
  // MENUNGGU hanya dihitung pada hari ditandai; selain itu dikecualikan.
  if (isMenunggu && !isMenungguHariIni) return 0;

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
    // Exclude > 3 bulan (batas: tanggal 1, tiga bulan sebelum bulan dateStr).
    const tigaBulanLalu = new Date(cur.getFullYear(), cur.getMonth() - 3, 1);
    if (acuan < tigaBulanLalu) return 0;
  }

  return target;
}
