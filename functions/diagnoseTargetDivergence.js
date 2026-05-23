'use strict';

/* =============================================================================
 * diagnoseTargetDivergence.js  —  DIAGNOSTIC, READ-ONLY
 * =============================================================================
 *
 *  ⚠️  TIDAK MENULIS APAPUN KE RTDB. Hanya membaca (.once('value')).
 *      Tidak ada .set / .update / .remove / .push. Aman dijalankan kapan saja.
 *
 *  TUJUAN
 *  ------
 *  Menjelaskan KENAPA "Target" di Web (Buku Rekap / Buku Pokok) lebih rendah
 *  daripada Android, padahal rumus macet 3-bulan di kedua sisi IDENTIK.
 *
 *  Temuan kode: rumus & batas macet identik (web lib/target.js,
 *  Android RingkasanDashboardScreen.kt, CF summaryHelpers.isOverThreeMonths).
 *  Maka selisih HANYA bisa berasal dari DATA acuan (tanggalPencairan dll).
 *
 *  Skrip ini mengevaluasi tiap nasabah dengan rumus Target Web (replika 1:1
 *  isEligibleForTarget) atas DATA RTDB LIVE, lalu mengklasifikasikan setiap
 *  nasabah yang DI-DROP oleh aturan macet berdasarkan AKTIVITAS PEMBAYARAN
 *  NYATA-nya:
 *
 *    - COUNTED               : web menghitungnya (target > 0). (Android pun sama.)
 *    - DIVERGEN (macet+bayar) : web DROP karena acuan > 3 bulan, TAPI nasabah
 *                               punya pembayaran terbaru (≥ batas 3 bulan).
 *                               → inilah yang ANDROID tetap hitung & web tidak.
 *                               Inilah sumber selisihnya.
 *    - MACET ASLI (no bayar)  : web DROP karena macet DAN tidak ada pembayaran
 *                               terbaru → Vivi-class. Android pun men-drop.
 *    - DROP lain              : lunas / menunggu pencairan / cair hari ini / status.
 *
 *  Lalu direkonsiliasi per resort (adminUid):
 *      targetWeb            = Σ target COUNTED
 *      selisihDivergen      = Σ target DIVERGEN
 *      targetAndroid_implied= targetWeb + selisihDivergen
 *      macetAsli (konteks)  = Σ target MACET ASLI
 *
 *  CARA PAKAI
 *  ----------
 *    SERVICE_ACCOUNT=./serviceAccountKey.json node diagnoseTargetDivergence.js
 *  Opsi env:
 *    SERVICE_ACCOUNT  path service account JSON (default ./serviceAccountKey.json)
 *    DATABASE_URL     URL RTDB (default project asia-southeast1)
 *    TODAY            tanggal acuan "dd MMM yyyy" (default "23 Mei 2026")
 *    CABANG           filter cabangId (opsional, mis. "Panti")
 *    RESORT           filter substring nama admin/resort (opsional, mis. "Mekar")
 *    ADMIN            filter adminUid persis (opsional)
 *    OUT              path output JSON (default ./divergence_report.json)
 *    OUT_CSV          path output CSV daftar DIVERGEN (default ./divergence_list.csv)
 * ===========================================================================*/

const path = require('path');
const fs = require('fs');
const admin = require('firebase-admin');

const SERVICE_ACCOUNT = process.env.SERVICE_ACCOUNT || './serviceAccountKey.json';
const DATABASE_URL = process.env.DATABASE_URL ||
  'https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app';
const TODAY = process.env.TODAY || '23 Mei 2026';
const CABANG = (process.env.CABANG || '').trim();
const RESORT = (process.env.RESORT || '').trim().toLowerCase();
const ADMIN = (process.env.ADMIN || '').trim();
const OUT = process.env.OUT || './divergence_report.json';
const OUT_CSV = process.env.OUT_CSV || './divergence_list.csv';

admin.initializeApp({
  credential: admin.credential.cert(require(path.resolve(SERVICE_ACCOUNT))),
  databaseURL: DATABASE_URL,
});
const db = admin.database();

// ---------------------------------------------------------------------------
// Helper tanggal (format lokal "dd MMM yyyy") — konsisten summaryHelpers/web
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
const listOf = (x) => (!x ? [] : (Array.isArray(x) ? x : Object.values(x)));

const todayDate = parseTgl(TODAY);
if (!todayDate) { console.error(`TODAY tidak valid: "${TODAY}"`); process.exit(1); }
// Batas macet = tanggal 1, tiga bulan sebelum bulan TODAY (identik web/Android/CF)
const threeMonthsAgo = new Date(todayDate.getFullYear(), todayDate.getMonth() - 3, 1);

// totalDibayar ala bukuPokokApi: semua pembayaran kecuali entry "Bunga..."
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

// Tanggal pembayaran terbaru dari siklus berjalan (pembayaranList), exclude "Bunga".
function lastPaymentDate(p) {
  let max = null, maxRaw = null;
  const consider = (raw) => {
    if (raw && String(raw).startsWith('Bunga')) return;
    const d = parseTgl(raw);
    if (d && (!max || d > max)) { max = d; maxRaw = raw; }
  };
  listOf(p.pembayaranList).forEach((x) => {
    if (!x) return;
    consider(x.tanggal);
    listOf(x.subPembayaran).forEach((s) => consider(s && s.tanggal));
  });
  return { date: max, raw: maxRaw };
}

// ---------------------------------------------------------------------------
// Replika 1:1 isEligibleForTarget (buku-pokok-web/lib/target.js) untuk TODAY.
// Mengembalikan { target, reason } dengan reason menjelaskan kenapa di-drop.
//   reason ∈ COUNTED | status | lunas | menunggu | cair_today | not_yet | macet
// ---------------------------------------------------------------------------
function webEval(p, dateStr) {
  const cur = parseTgl(dateStr);
  if (!cur) return { target: 0, reason: 'bad_date' };

  const statusLower = (p.status || '').toLowerCase();
  const isStatusAktif = statusLower === 'aktif' || statusLower === 'active';
  const lunasHariIni = (p.tanggalLunasCicilan || '').trim() === dateStr;
  const isMenungguPencairan = p.statusKhusus === 'MENUNGGU_PENCAIRAN'
    && (p.statusPencairanSimpanan || '') !== 'Dicairkan';
  const menungguHariIni = isMenungguPencairan && (p.tanggalStatusKhusus || '').trim() === dateStr;
  if (!isStatusAktif && !lunasHariIni && !menungguHariIni) return { target: 0, reason: 'status' };

  const target = Math.floor((p.besarPinjaman || 0) * 3 / 100);
  const totalPelunasan = p.totalPelunasan || 0;
  const totalDibayar = calcTotalDibayar(p);

  const isSudahLunas = totalPelunasan > 0 && totalDibayar >= totalPelunasan;
  if (isSudahLunas) {
    const tglLunas = (p.tanggalLunasCicilan || '').trim();
    const lunasDate = parseTgl(tglLunas);
    if (lunasDate) {
      if (tglLunas === dateStr) return { target, reason: 'COUNTED' }; // lunas hari ini (H+1)
      if (lunasDate < cur) return { target: 0, reason: 'lunas' };
      // lunasDate > cur (historis) → masih belum lunas pd tanggal ini → lanjut
    } else {
      return { target: 0, reason: 'lunas' };
    }
  }

  if (isMenungguPencairan && !menungguHariIni) return { target: 0, reason: 'menunggu' };

  const tglPencairan = (p.tanggalPencairan || '').trim();
  if (tglPencairan && tglPencairan === dateStr) return { target: 0, reason: 'cair_today' };

  const tglAcuan = tglPencairan
    || (p.tanggalPengajuan || '').trim()
    || (p.tanggalDaftar || '').trim();
  const acuan = parseTgl(tglAcuan);
  if (acuan) {
    if (acuan > cur) return { target: 0, reason: 'not_yet' };
    if (acuan < threeMonthsAgo) return { target: 0, reason: 'macet' };
  }
  return { target, reason: 'COUNTED' };
}

// ---------------------------------------------------------------------------
// Main (READ-ONLY)
// ---------------------------------------------------------------------------
(async function main() {
  console.log('[DIAG] READ-ONLY. Tidak menulis ke RTDB.');
  console.log(`[DIAG] TODAY=${TODAY} | batas macet < ${threeMonthsAgo.toDateString()}`);
  const filt = [CABANG && `cabang=${CABANG}`, RESORT && `resort~${RESORT}`, ADMIN && `admin=${ADMIN}`]
    .filter(Boolean).join(' | ');
  console.log(`[DIAG] Filter: ${filt || '(semua)'}`);
  console.log('[DIAG] Membaca node pelanggan (.once value)…\n');

  const snap = await db.ref('pelanggan').once('value');
  const allAdmins = snap.val() || {};

  const resorts = {};      // adminUid → ringkasan resort
  const divergenList = []; // flat, untuk CSV + verifikasi manual di app
  let scanned = 0;

  for (const [adminUid, bucket] of Object.entries(allAdmins)) {
    if (!bucket || typeof bucket !== 'object') continue;
    if (ADMIN && adminUid !== ADMIN) continue;

    // label resort (modal value)
    const names = {}, cabangs = {};
    for (const p of Object.values(bucket)) {
      if (!p || typeof p !== 'object') continue;
      if (p.adminName) names[p.adminName] = (names[p.adminName] || 0) + 1;
      if (p.cabangId) cabangs[p.cabangId] = (cabangs[p.cabangId] || 0) + 1;
    }
    const modal = (o) => { const e = Object.entries(o).sort((a, b) => b[1] - a[1]); return e.length ? e[0][0] : ''; };
    const adminName = modal(names) || '(no name)';
    const cabangId = modal(cabangs) || '';
    if (CABANG && cabangId !== CABANG) continue;
    if (RESORT && !adminName.toLowerCase().includes(RESORT)) continue;

    let targetWeb = 0, selisihDivergen = 0, macetAsli = 0;
    let nCounted = 0, nDivergen = 0, nMacetAsli = 0;

    for (const [pid, p] of Object.entries(bucket)) {
      if (!p || typeof p !== 'object') continue;
      scanned++;
      const { target, reason } = webEval(p, TODAY);

      if (reason === 'COUNTED') { targetWeb += target; nCounted++; continue; }
      if (reason !== 'macet') continue; // drop non-macet (lunas/menunggu/dll) → bukan sumber selisih ini

      // Di-drop karena MACET menurut data RTDB. Cek aktivitas pembayaran nyata.
      const lp = lastPaymentDate(p);
      const recent = !!(lp.date && lp.date >= threeMonthsAgo);
      const tgt = Math.floor((p.besarPinjaman || 0) * 3 / 100);

      if (recent) {
        selisihDivergen += tgt; nDivergen++;
        divergenList.push({
          path: `pelanggan/${adminUid}/${pid}`,
          resort: adminName, cabangId,
          nama: p.namaPanggilan || p.namaKtp || '',
          pinjamanKe: p.pinjamanKe || 1,
          besarPinjaman: p.besarPinjaman || 0,
          targetPerDay: tgt,
          tanggalPencairan: (p.tanggalPencairan || '').trim() || null,
          tanggalPengajuan: (p.tanggalPengajuan || '').trim() || null,
          tanggalDaftar: (p.tanggalDaftar || '').trim() || null,
          acuanDipakaiWeb: (p.tanggalPencairan || '').trim()
            || (p.tanggalPengajuan || '').trim() || (p.tanggalDaftar || '').trim() || null,
          lastPayment: lp.raw,
        });
      } else {
        macetAsli += tgt; nMacetAsli++;
      }
    }

    // hanya catat resort yg relevan (punya sesuatu)
    if (nCounted + nDivergen + nMacetAsli === 0) continue;
    resorts[adminUid] = {
      adminUid, resort: adminName, cabangId,
      targetWeb,
      selisihDivergen,
      targetAndroidImplied: targetWeb + selisihDivergen,
      macetAsliContext: macetAsli,
      counts: { counted: nCounted, divergen: nDivergen, macetAsli: nMacetAsli },
    };
  }

  divergenList.sort((a, b) => b.targetPerDay - a.targetPerDay);

  const resortArr = Object.values(resorts)
    .sort((a, b) => b.selisihDivergen - a.selisihDivergen);
  const totals = resortArr.reduce((s, r) => {
    s.targetWeb += r.targetWeb;
    s.selisihDivergen += r.selisihDivergen;
    s.macetAsli += r.macetAsliContext;
    return s;
  }, { targetWeb: 0, selisihDivergen: 0, macetAsli: 0 });

  const report = {
    generatedAt: new Date().toISOString(),
    today: TODAY,
    macetBoundary: `< ${threeMonthsAgo.getDate()} ${Object.keys(BULAN)[threeMonthsAgo.getMonth()]} ${threeMonthsAgo.getFullYear()}`,
    readOnly: true,
    filter: { cabang: CABANG || null, resort: RESORT || null, admin: ADMIN || null },
    explanation:
      'targetWeb = angka yang dihitung Web. selisihDivergen = nasabah yang Web DROP ' +
      'sebagai macet tapi PUNYA pembayaran terbaru (Android tetap hitung). ' +
      'targetAndroidImplied = targetWeb + selisihDivergen — harus mendekati angka Android. ' +
      'macetAsliContext = nasabah macet TANPA pembayaran terbaru (Vivi-class; Android pun drop).',
    totals: {
      targetWeb: totals.targetWeb,
      selisihDivergen: totals.selisihDivergen,
      targetAndroidImplied: totals.targetWeb + totals.selisihDivergen,
      macetAsliContext: totals.macetAsli,
    },
    resorts: resortArr,
    divergenNasabah: divergenList,
  };
  fs.writeFileSync(path.resolve(OUT), JSON.stringify(report, null, 2));

  // CSV daftar DIVERGEN (mudah dicocokkan dengan tampilan app)
  const csvHead = 'resort,nama,pinjamanKe,besarPinjaman,targetPerDay,tanggalPencairan,tanggalPengajuan,tanggalDaftar,acuanDipakaiWeb,lastPayment,path';
  const esc = (v) => { const s = (v == null ? '' : String(v)); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
  const csvRows = divergenList.map((d) => [
    d.resort, d.nama, d.pinjamanKe, d.besarPinjaman, d.targetPerDay,
    d.tanggalPencairan, d.tanggalPengajuan, d.tanggalDaftar, d.acuanDipakaiWeb, d.lastPayment, d.path,
  ].map(esc).join(','));
  fs.writeFileSync(path.resolve(OUT_CSV), [csvHead, ...csvRows].join('\n') + '\n');

  // Ringkasan konsol
  const fmt = (n) => n.toLocaleString('id-ID');
  console.log(`[DIAG] Dipindai ${scanned} nasabah.\n`);
  console.log('Per resort (urut selisih terbesar):');
  console.log('  ' + 'RESORT'.padEnd(26) + 'targetWeb'.padStart(13) + 'selisih'.padStart(12) + 'androidImpl'.padStart(14) + '  (cnt/div/macet)');
  for (const r of resortArr) {
    console.log('  ' + r.resort.slice(0, 25).padEnd(26)
      + fmt(r.targetWeb).padStart(13)
      + fmt(r.selisihDivergen).padStart(12)
      + fmt(r.targetAndroidImplied).padStart(14)
      + `   ${r.counts.counted}/${r.counts.divergen}/${r.counts.macetAsli}`);
  }
  console.log('\nTOTAL:');
  console.log(`  targetWeb            : Rp ${fmt(totals.targetWeb)}`);
  console.log(`  + selisihDivergen    : Rp ${fmt(totals.selisihDivergen)}  (Web drop, Android hitung)`);
  console.log(`  = targetAndroidImpl  : Rp ${fmt(totals.targetWeb + totals.selisihDivergen)}`);
  console.log(`  (konteks) macetAsli  : Rp ${fmt(totals.macetAsli)}  (Vivi-class; kedua sisi drop)`);
  console.log(`\nDaftar DIVERGEN: ${divergenList.length} nasabah → ${path.resolve(OUT_CSV)}`);
  console.log(`Laporan lengkap     → ${path.resolve(OUT)}`);
  console.log('\n→ Verifikasi: nasabah di CSV ini seharusnya MUNCUL sebagai aktif di Android,');
  console.log('  TAPI hilang di Target Web. Cocokkan beberapa nama dengan tampilan app.');

  await admin.app().delete();
  process.exit(0);
})().catch((err) => {
  console.error('[DIAG] Error:', err);
  process.exit(1);
});
