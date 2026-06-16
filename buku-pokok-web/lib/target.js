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

// =========================================================================
// ✅ isTanggalHistoris — gate date-aware utk snapshot rekap_harian_final
// (pimpinan 09 Jun 2026, fix "Buku Rekap baru update di 00:01").
// -------------------------------------------------------------------------
// Snapshot beku = OTORITAS hanya utk tanggal yang SUDAH LEWAT (kalender
// strictly < hari ini). Hari BERJALAN wajib live-calc agar storting/target
// real-time — entri snapshot hari-ini (mis. ditulis refreezeRekapHarian
// siang hari, atau freeze 23:59) TIDAK boleh membekukan tampilan live.
//
// Kenapa komparasi PARSED DATE `<`, bukan string equality `!== data.today`
// (gate lama yang dihapus 11bd5df): equality string rapuh terhadap semantik
// "hari Minggu disembunyikan" (tanggal tampil bisa dianggap == today →
// snapshot ter-bypass utk tanggal historis). Komparasi kalender tegas:
//   - tanggal lampau selalu < today → snapshot menang (Rule 3 utuh:
//     dilihat 10 tahun lagi tetap beku, imun mutasi retroaktif).
//   - hari ini tidak pernah < today → live calc → real-time.
//
// serverToday = data.today dari getBukuPokok (WIB server). Bila absen/
// invalid → fallback hitung today WIB dari clock client.
// =========================================================================
export function isTanggalHistoris(dateStr, serverToday) {
  const d = parseTanggalIndo(dateStr);
  if (!d) return false;
  let todayRef = parseTanggalIndo((serverToday || '').trim());
  if (!todayRef) {
    const nowWib = new Date(Date.now() + 7 * 60 * 60 * 1000);
    todayRef = new Date(nowWib.getUTCFullYear(), nowWib.getUTCMonth(), nowWib.getUTCDate());
  }
  return d < todayRef;
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

  // =========================================================================
  // POIN 0 — Hari Minggu = 0 (parity Cloud Functions, fix Drift #1 14 Jun 2026).
  // -------------------------------------------------------------------------
  // CF men-nol-kan target di hari Minggu via isHoliday() → getDay()===0
  // (summaryHelpers.js:55-57, dipanggil calculateTargetHariIni:278). Helper
  // ini SEBELUMNYA tidak mengecek Minggu — penyaringan Minggu hanya terjadi
  // di level KOLOM yang ditampilkan (kasir/page.js isHariKerja). Akibatnya
  // bila helper dipanggil untuk kolom Minggu (edge case), target numerik
  // ikut terhitung padahal seharusnya 0 → divergen dari summary CF.
  // `cur` = new Date(year, m, day) lokal (parseTanggalIndo:40), identik
  // konstruksi tanggal CF, jadi getDay() konsisten lintas timezone runtime.
  // Strictly additive: hanya menambah jalur return 0 untuk Minggu; tidak
  // mengubah perilaku hari kerja mana pun di bawahnya.
  // =========================================================================
  if (cur.getDay() === 0) return 0;

  // =========================================================================
  // POIN 4 — Cutoff arsip date-aware (fix shrinking-target historis).
  // -------------------------------------------------------------------------
  // Entry `dariArsip` adalah nasabah yang sudah DIHAPUS (cairkanSimpanan)
  // dan di-inject kembali ke nasabahList oleh bukuPokokApi.js agar storting
  // pelunasan-via-tabungan tetap tercatat di buku rekap/buku pokok.
  //
  // Aturan immutabilitas historis (audit pimpinan 05 Jun 2026):
  //   tanggalArsip >  cur → AKTIF pada kolom historis itu (saat itu masih
  //     ditagih, jadi target HARUS tetap dihitung). Tanpa cabang ini, target
  //     tanggal lampau "menyusut" saat nasabah baru di-arsip hari ini.
  //   tanggalArsip === cur → AKTIF (hari arsip = hari TERAKHIR nasabah ditagih;
  //     pelunasan via tabungan justru tercatat sebagai pembayaran hari itu).
  //     Boundary ini WAJIB parity dengan H+1 di POIN 1 (LUNAS: `=== → target`)
  //     dan POIN 2 (MP: `=== → include`).
  //
  //     ⚠️ FIX 06 Jun 2026 (penyebab "menyusut AGAIN" yang dilaporkan pimpinan):
  //     Sebelumnya boundary ini `<= cur → 0` (men-nol-kan hari arsip). Itu
  //     menyebabkan SHRINK pada hari arsip itu sendiri: SEBELUM archival,
  //     nasabah ada di `pelanggan/` live → hari D dihitung; SESUDAH archival
  //     (cairkanSimpanan) nasabah pindah ke arsip dgn tanggalArsip=D → `<=`
  //     mengembalikan 0 → target hari D anjlok sebesar kontribusi nasabah itu.
  //     Pimpinan melihatnya keesokan hari sebagai "kemarin menyusut". Dengan
  //     `< cur` (strict), hari D tetap dihitung sebelum & sesudah archival →
  //     NOL penyusutan, historis benar-benar beku, konsisten dgn LUNAS/MP.
  //   tanggalArsip <  cur → 0 (sudah berhenti ditagih SEBELUM kolom itu;
  //     tidak ada over-count untuk hari-hari setelah arsip).
  //   tanggalArsip kosong/invalid → 0 (legacy arsip tanpa metadata
  //     archivedAt; pertahankan perilaku fix 02 Jun agar tidak regresi
  //     over-count untuk nasabah lama yang tanggalLunasCicilan kosong).
  //
  // Ini menggantikan blanket pre-skip `!n.dariArsip` yang sebelumnya
  // ditaruh di kasir/page.js & pembukuan/page.js — pre-skip itu menyusutkan
  // target historis (penyebab bug yang dilaporkan pimpinan).
  // =========================================================================
  if (n.dariArsip) {
    const arsipDate = parseTanggalIndo((n.tanggalArsip || '').trim());
    if (!arsipDate) return 0;
    // STRICT `<` (bukan `<=`): hari arsip = hari terakhir aktif, tetap dihitung
    // (parity H+1 LUNAS/MP). Hanya hari SETELAH arsip yang di-nol-kan.
    if (arsipDate < cur) return 0;
    // arsipDate >= cur → pada kolom itu nasabah masih aktif (atau hari terakhir);
    // lanjut ke evaluasi normal di bawah (status, lunas, MP, 3-month, dst).
  }

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

  // =========================================================================
  // POIN 4 & 5 — acuan tanggal pencairan, dengan FALLBACK untuk legacy data.
  // -------------------------------------------------------------------------
  // MIRROR Android d9457f5 (audit forensik pimpinan 08 Jun 2026, Resort
  // Sederhana & Harmonis). Commit sebelumnya melarang fallback ke
  // tanggalPengajuan/tanggalDaftar SAMA SEKALI (Scenario 4) — benar untuk
  // mencegah leak approved-belum-cair, TAPI terlalu agresif untuk nasabah
  // LEGACY yang status="Aktif" namun tanggalPencairan kosong (workflow lama /
  // migrasi data). Buku manual MEMASUKKAN mereka; sistem membuangnya.
  //
  //   BRANCH A (tanggalPencairan ADA) → jalur asli Scenario 4 & 5 (TETAP):
  //     - === dateStr + pinjamanKe>1 + besarLama>0 → anchor pinjaman LAMA.
  //     - === dateStr + pinjaman pertama           → 0 (mulai besok).
  //     - >  dateStr                               → 0 (belum cair).
  //     - <  dateStr + 3-bulan boundary (Option A) → eligible.
  //
  //   BRANCH B (tanggalPencairan KOSONG) → legacy fallback:
  //     - Sampai sini = pre-guard live-status (line 152-153) sudah lolos →
  //       status pasti Aktif / lunas-hari-ini / MP-hari-ini / masih-aktif /
  //       belum-MP. Nasabah "Disetujui"/"Menunggu Approval" sudah DITOLAK di
  //       pre-guard → Scenario 4 tetap terjaga, TIDAK akan sampai branch ini.
  //     - Fallback acuan ke tanggalPengajuan → tanggalDaftar HANYA utk
  //       3-bulan macet check (Option A) + date-aware "belum aktif" guard.
  //     - Kedua fallback kosong → tetap dihitung (legacy aktif tanpa metadata).
  // =========================================================================
  const tglPencairan = (n.tanggalPencairan || '').trim();

  // ── BRANCH A: tanggalPencairan ADA ──
  if (tglPencairan) {
    // =====================================================================
    // POIN 5 — CAIR HARI INI = TARGET 0 (aturan pimpinan 16 Jun 2026, final).
    // ---------------------------------------------------------------------
    // BAIK pinjaman baru MAUPUN top-up: pada hari pencairan, kontribusi target
    // kolom hari itu WAJIB 0 — penagihan baru mulai H+1. Parity 1:1 dengan
    // engine summary calculateTargetHariIni (summaryHelpers.js:291,
    // `tanggalPencairan === today → return 0`) & Android.
    //
    // Branch lama "Fix C (Scenario 5)" mengembalikan
    // floor(besarPinjamanLamaSebelumTopUp × 3%) untuk top-up (pinjamanKe>1).
    // Itu menyimpang dari aturan ini DAN menjadi sumber fluktuasi target hari
    // berjalan: nilai anchor bergantung pada besarPinjamanLamaSebelumTopUp yang
    // baru terisi setelah arsip riwayat_pinjaman selesai (onPelangganWrite).
    // Selama race "arsip vs cache 10-menit CF", kontribusi nasabah top-up
    // melompat 0 ↔ anchor → target intra-day naik/turun. Strict `return 0`
    // menutup kebocoran itu untuk SEMUA admin sekaligus, tanpa pengecualian.
    // =====================================================================
    if (tglPencairan === dateStr) {
      return 0;
    }
    const acuan = parseTanggalIndo(tglPencairan);
    if (!acuan) return 0;
    if (acuan > cur) return 0;  // pencairan di masa depan relatif kolom
    // POIN 3 — Batas 3 bulan kalender (Option A, indeks bulan absolut).
    const acuanMonthIdx = acuan.getFullYear() * 12 + acuan.getMonth();
    const curMonthIdx = cur.getFullYear() * 12 + cur.getMonth();
    if (curMonthIdx - acuanMonthIdx > 3) return 0;
    return target;
  }

  // ── BRANCH B: tanggalPencairan KOSONG — legacy fallback ──
  const tglAcuanLegacy = (n.tanggalPengajuan || '').trim() || (n.tanggalDaftar || '').trim();
  const acuanLegacy = parseTanggalIndo(tglAcuanLegacy);
  if (!acuanLegacy) return target;   // legacy aktif tanpa tanggal apa pun → tetap hitung
  if (acuanLegacy > cur) return 0;   // pengajuan/daftar di masa depan kolom → belum aktif
  // POIN 3 — Batas 3 bulan kalender (acuan = pengajuan/daftar; Option A).
  const acuanLegacyIdx = acuanLegacy.getFullYear() * 12 + acuanLegacy.getMonth();
  const curLegacyIdx = cur.getFullYear() * 12 + cur.getMonth();
  if (curLegacyIdx - acuanLegacyIdx > 3) return 0;

  return target;
}
