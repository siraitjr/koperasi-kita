'use strict';

/* =============================================================================
 * dryRunStalePencairan.js  —  DRY RUN, READ-ONLY MAINTENANCE SCRIPT
 * =============================================================================
 *
 *  ⚠️  TIDAK MENULIS APAPUN KE RTDB. Hanya membaca (.once('value')) lalu
 *      menghasilkan file proposal proposals_report.json untuk ditinjau manual.
 *      Tidak ada satupun pemanggilan .set / .update / .remove / .push.
 *
 *  Tujuan:
 *    Memetakan nasabah "Aktif" pinjamanKe>1 yang target hariannya ter-drop oleh
 *    aturan macet >3 bulan KARENA tanggalPencairan/Pengajuan-nya basi (tahun
 *    2024/2025) padahal tanggalDaftar pinjaman berjalan sudah 2026. Untuk tiap
 *    nasabah, skrip menelusuri pembayaranList (+ riwayatPinjaman bila ada) guna
 *    menyimpulkan tanggal transaksi 2026 sebenarnya, lalu MENGUSULKAN koreksi.
 *
 *  Kategori status proposal:
 *    - "Fix Proposed"  : ada transaksi 2026 nyata → usulkan tanggal koreksi.
 *    - "True Macet"    : daftar 2026 tapi TIDAK ada aktivitas/pembayaran apapun
 *                        → bad debt nasional (genuine inactive), bukan anomali.
 *    - "Manual Review" : data kontradiktif/korup (mis. pembayaran 2025 di bawah
 *                        registrasi 2026 — kasus seperti Erni) → wajib cek manual.
 *
 *  CARA PAKAI (lokal, dengan service account Firebase Admin):
 *    SERVICE_ACCOUNT=./serviceAccountKey.json node dryRunStalePencairan.js
 *  Opsi env:
 *    SERVICE_ACCOUNT  path service account JSON (default ./serviceAccountKey.json)
 *    DATABASE_URL     URL RTDB (default project asia-southeast1)
 *    TODAY            tanggal acuan "dd MMM yyyy" (default "22 Mei 2026")
 *    OUT              path output (default ./proposals_report.json)
 * ===========================================================================*/

const path = require('path');
const fs = require('fs');
const admin = require('firebase-admin');

// ---------------------------------------------------------------------------
// Konfigurasi
// ---------------------------------------------------------------------------
const SERVICE_ACCOUNT = process.env.SERVICE_ACCOUNT || './serviceAccountKey.json';
const DATABASE_URL = process.env.DATABASE_URL ||
  'https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app';
const TODAY = process.env.TODAY || '22 Mei 2026';
const OUT = process.env.OUT || './proposals_report.json';

admin.initializeApp({
  credential: admin.credential.cert(require(path.resolve(SERVICE_ACCOUNT))),
  databaseURL: DATABASE_URL,
});
const db = admin.database();

// ---------------------------------------------------------------------------
// Helper tanggal (format lokal "dd MMM yyyy", WIB) — konsisten dgn summaryHelpers
// ---------------------------------------------------------------------------
const BULAN = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, Mei: 4, Jun: 5,
                Jul: 6, Agu: 7, Sep: 8, Okt: 9, Nov: 10, Des: 11 };
function parseTgl(s) {
  if (!s || typeof s !== 'string') return null;
  const p = s.trim().split(' ');
  if (p.length !== 3) return null;
  const m = BULAN[p[1]];
  const d = parseInt(p[0], 10);
  const y = parseInt(p[2], 10);
  if (isNaN(d) || m === undefined || isNaN(y)) return null;
  return new Date(y, m, d);
}
const yearOf = (s) => { const d = parseTgl(s); return d ? d.getFullYear() : null; };
const listOf = (x) => (!x ? [] : (Array.isArray(x) ? x : Object.values(x)));

const todayDate = parseTgl(TODAY);
if (!todayDate) { console.error(`TODAY tidak valid: "${TODAY}"`); process.exit(1); }
// Batas macet = tanggal 1, tiga bulan sebelum bulan TODAY (sama dgn Android/CF/web)
const threeMonthsAgo = new Date(todayDate.getFullYear(), todayDate.getMonth() - 3, 1);
const isOver3 = (s) => { const d = parseTgl(s); return !!(d && d < threeMonthsAgo); };

// totalDibayar ala bukuPokokApi: jumlah semua pembayaran kecuali entry "Bunga..."
function calcTotalDibayar(p) {
  let t = 0;
  listOf(p.pembayaranList).forEach((x) => {
    if (!x) return;
    if (x.tanggal && String(x.tanggal).startsWith('Bunga')) return;
    t += x.jumlah || 0;
    listOf(x.subPembayaran).forEach((s) => { t += s.jumlah || 0; });
  });
  return t;
}

// Kumpulkan semua TANGGAL transaksi yang bisa diparse (pembayaranList + sub +
// riwayatPinjaman). Termasuk entry "Pelunasan Top-Up" (penanda pencairan top-up).
// Entry "Bunga..." dilewati karena tanggalnya bukan tanggal sungguhan.
function collectTransactions(p) {
  const out = []; // { date: Date, raw: string, keterangan, jumlah, source }
  const pushEntry = (x, source) => {
    if (!x) return;
    if (x.tanggal && String(x.tanggal).startsWith('Bunga')) return;
    const d = parseTgl(x.tanggal);
    if (d) out.push({ date: d, raw: x.tanggal, keterangan: x.keterangan || '', jumlah: x.jumlah || 0, source });
    listOf(x.subPembayaran).forEach((s) => {
      const sd = parseTgl(s.tanggal);
      if (sd) out.push({ date: sd, raw: s.tanggal, keterangan: s.keterangan || '', jumlah: s.jumlah || 0, source: source + '.sub' });
    });
  };
  listOf(p.pembayaranList).forEach((x) => pushEntry(x, 'pembayaranList'));
  // riwayatPinjaman (pinjaman lama) — bila ada, dimasukkan sebagai konteks.
  listOf(p.riwayatPinjaman).forEach((r, i) => {
    listOf(r && r.pembayaranList).forEach((x) => pushEntry(x, `riwayat[${i}]`));
  });
  out.sort((a, b) => a.date - b.date);
  return out;
}

// ---------------------------------------------------------------------------
// Deteksi "affected" — IDENTIK dengan rule scan drift:
//   status Aktif & pinjamanKe>1 & belum lunas & bukan menunggu & bukan cair-hari-ini
//   & over3(acuan) === true (saat ini ter-drop) & daftar ada & !over3(daftar)
// ---------------------------------------------------------------------------
function detectAffected(p) {
  const status = (p.status || '').toLowerCase();
  if (status !== 'aktif' && status !== 'active') return null;
  if ((p.pinjamanKe || 1) <= 1) return null;
  const totalPelunasan = p.totalPelunasan || 0;
  if (totalPelunasan <= 0) return null;
  if (calcTotalDibayar(p) >= totalPelunasan) return null;        // sudah lunas
  if (p.statusKhusus === 'MENUNGGU_PENCAIRAN') return null;       // di-drop krn menunggu

  const pencairan = (p.tanggalPencairan || '').trim();
  const pengajuan = (p.tanggalPengajuan || '').trim();
  const daftar = (p.tanggalDaftar || '').trim();
  if (pencairan === TODAY) return null;                          // cair hari ini

  const acuan = pencairan || pengajuan || daftar;
  if (!isOver3(acuan)) return null;                             // tidak ter-drop macet
  if (!daftar || isOver3(daftar)) return null;                 // daftar juga lama → genuine macet lama

  const staleField = (pencairan && isOver3(pencairan)) ? 'tanggalPencairan'
    : (pengajuan && isOver3(pengajuan)) ? 'tanggalPengajuan' : 'tanggalDaftar';
  return { acuan, staleField, daftar, pencairan, pengajuan };
}

// ---------------------------------------------------------------------------
// Inferensi proposal koreksi
// ---------------------------------------------------------------------------
function buildProposal(p, det) {
  const tx = collectTransactions(p);
  const daftarDate = parseTgl(det.daftar);
  const tx2026 = tx.filter((t) => t.date.getFullYear() === 2026);
  const latest = tx.length ? tx[tx.length - 1] : null;
  const earliest2026 = tx2026.length ? tx2026[0] : null;
  const topUp2026 = tx2026.find((t) => /top.?up/i.test(t.keterangan) || /pelunasan/i.test(t.keterangan));

  const currentDate = det.staleField === 'tanggalPencairan' ? det.pencairan
    : det.staleField === 'tanggalPengajuan' ? det.pengajuan : det.daftar;

  const evidence = {
    pinjamanKe: p.pinjamanKe || 1,
    totalPaymentEntries: tx.length,
    payments2026Count: tx2026.length,
    earliest2026Payment: earliest2026 ? earliest2026.raw : null,
    latestPaymentDate: latest ? latest.raw : null,
    paymentYears: [...new Set(tx.map((t) => t.date.getFullYear()))].sort(),
    tanggalDaftar: det.daftar,
    tanggalPencairan: det.pencairan || null,
    tanggalPengajuan: det.pengajuan || null,
  };

  // 1) KORUP / KONTRADIKTIF → Manual Review.
  //    Ada pembayaran, TAPI semua mendahului registrasi (latestPay < daftar).
  //    Mis. Erni: pembayaran 2025 di bawah daftar 2026 → timeline mustahil.
  if (latest && daftarDate && latest.date < daftarDate) {
    return {
      status: 'Manual Review', proposedField: det.staleField, currentDate, proposedDate: null,
      confidence: 'n/a',
      note: `Timeline korup: pembayaran terakhir (${latest.raw}) mendahului tanggalDaftar (${det.daftar}). pembayaranList tampaknya milik pinjaman lama. Verifikasi manual sebelum koreksi.`,
      evidence,
    };
  }

  // 2) ADA aktivitas 2026 → usulkan tanggal koreksi.
  if (tx2026.length > 0) {
    let proposedDate, confidence, note;
    if (topUp2026) {
      proposedDate = topUp2026.raw; confidence = 'high';
      note = `Diambil dari entry pelunasan/top-up 2026 (penanda pencairan): ${topUp2026.raw}.`;
    } else if (daftarDate && earliest2026 && daftarDate <= earliest2026.date) {
      proposedDate = det.daftar; confidence = 'high';
      note = `tanggalDaftar 2026 konsisten (≤ pembayaran 2026 pertama ${earliest2026.raw}).`;
    } else if (earliest2026) {
      proposedDate = earliest2026.raw; confidence = 'medium';
      note = `Disimpulkan dari pembayaran 2026 paling awal (${earliest2026.raw}); daftar=${det.daftar}.`;
    } else {
      proposedDate = det.daftar; confidence = 'medium';
      note = `Tidak ada pembayaran 2026 ter-parse; fallback ke tanggalDaftar 2026.`;
    }
    return { status: 'Fix Proposed', proposedField: det.staleField, currentDate, proposedDate, confidence, note, evidence };
  }

  // 3) TIDAK ada pembayaran sama sekali → genuine bad debt nasional.
  if (tx.length === 0) {
    return {
      status: 'True Macet', proposedField: det.staleField, currentDate, proposedDate: null, confidence: 'n/a',
      note: `Daftar ${det.daftar} (2026) tapi TIDAK ada pembayaran/aktivitas apapun. Genuine inactive (Macet Nasional) — bukan anomali data.`,
      evidence,
    };
  }

  // 4) Ada pembayaran tapi tak satupun 2026, dan tidak inversi → ambigu.
  return {
    status: 'Manual Review', proposedField: det.staleField, currentDate, proposedDate: null, confidence: 'n/a',
    note: `Tidak ada pembayaran 2026 (tahun pembayaran: ${evidence.paymentYears.join(', ') || '-'}) di bawah daftar 2026. Ambigu — verifikasi manual.`,
    evidence,
  };
}

// ---------------------------------------------------------------------------
// Main (READ-ONLY)
// ---------------------------------------------------------------------------
(async function main() {
  console.log(`[DRY RUN] READ-ONLY. TODAY=${TODAY} | batas macet < ${threeMonthsAgo.toDateString()}`);
  console.log('[DRY RUN] Membaca node pelanggan (sekali, .once value)…');

  const snap = await db.ref('pelanggan').once('value');
  const allAdmins = snap.val() || {};

  const adminsOut = {};
  let scannedNasabah = 0, affectedTotal = 0, droppedTotal = 0;
  const tally = { 'Fix Proposed': 0, 'True Macet': 0, 'Manual Review': 0 };

  for (const [adminUid, bucket] of Object.entries(allAdmins)) {
    if (!bucket || typeof bucket !== 'object') continue;
    // nama/email admin = nilai modal non-kosong dalam bucket (label saja)
    const names = {}, emails = {}, cabangs = {};
    for (const p of Object.values(bucket)) {
      if (!p || typeof p !== 'object') continue;
      scannedNasabah++;
      if (p.adminName) names[p.adminName] = (names[p.adminName] || 0) + 1;
      if (p.adminEmail) emails[p.adminEmail] = (emails[p.adminEmail] || 0) + 1;
      if (p.cabangId) cabangs[p.cabangId] = (cabangs[p.cabangId] || 0) + 1;
    }
    const modal = (o) => { const e = Object.entries(o).sort((a, b) => b[1] - a[1]); return e.length ? e[0][0] : ''; };

    const list = [];
    for (const [pid, p] of Object.entries(bucket)) {
      if (!p || typeof p !== 'object') continue;
      const det = detectAffected(p);
      if (!det) continue;
      const prop = buildProposal(p, det);
      const besar = p.besarPinjaman || 0;
      const droppedTarget = Math.floor(besar * 3 / 100);
      affectedTotal++; droppedTotal += droppedTarget; tally[prop.status]++;
      list.push({
        pelangganId: pid,
        path: `pelanggan/${adminUid}/${pid}`,
        nama: p.namaPanggilan || p.namaKtp || '',
        besarPinjaman: besar,
        droppedTargetPerDay: droppedTarget,
        ...prop,
      });
    }
    if (list.length) {
      list.sort((a, b) => b.droppedTargetPerDay - a.droppedTargetPerDay);
      adminsOut[adminUid] = {
        adminUid,
        adminName: modal(names) || '(no name)',
        adminEmail: modal(emails) || '',
        cabangId: modal(cabangs) || '',
        affected: list.length,
        totalDroppedPerDay: list.reduce((s, n) => s + n.droppedTargetPerDay, 0),
        statusBreakdown: list.reduce((acc, n) => { acc[n.status] = (acc[n.status] || 0) + 1; return acc; }, {}),
        nasabah: list,
      };
    }
  }

  const report = {
    generatedAt: new Date().toISOString(),
    today: TODAY,
    macetBoundary: `${threeMonthsAgo.getDate()} (bulan ${threeMonthsAgo.getMonth() + 1}) ${threeMonthsAgo.getFullYear()}`,
    readOnly: true,
    summary: {
      scannedNasabah,
      affectedTotal,
      droppedTotalPerDay: droppedTotal,
      statusTally: tally,
    },
    admins: Object.values(adminsOut).sort((a, b) => b.totalDroppedPerDay - a.totalDroppedPerDay),
  };

  fs.writeFileSync(path.resolve(OUT), JSON.stringify(report, null, 2));

  // Ringkasan konsol
  const fmt = (n) => n.toLocaleString('id-ID');
  console.log(`\n[DRY RUN] Selesai. Dipindai ${scannedNasabah} nasabah.`);
  console.log(`Affected: ${affectedTotal} | Total target ter-drop: Rp ${fmt(droppedTotal)}/hari`);
  console.log(`  → Fix Proposed : ${tally['Fix Proposed']}`);
  console.log(`  → True Macet   : ${tally['True Macet']}`);
  console.log(`  → Manual Review: ${tally['Manual Review']}`);
  console.log(`\nLaporan ditulis ke: ${path.resolve(OUT)} (tidak ada perubahan DB).`);

  await admin.app().delete();
  process.exit(0);
})().catch((err) => {
  console.error('[DRY RUN] Error:', err);
  process.exit(1);
});
