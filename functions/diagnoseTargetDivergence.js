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
 *  Menemukan SECARA PASTI kenapa Target Harian Web < Android dgn menjalankan
 *  KEDUA aturan eligibility (replika 1:1) atas DATA RTDB YANG SAMA, lalu
 *  membandingkan verdict per nasabah:
 *
 *    - webEval()     : replika persis buku-pokok-web/lib/target.js
 *                      (isEligibleForTarget).
 *    - androidEval() : replika persis RingkasanDashboardScreen.kt:115-189
 *                      (filter nasabahAktif → target = besarPinjaman×3%).
 *
 *  Kedua fungsi memakai data MENTAH RTDB yang identik. Kalau hasil keduanya
 *  BERBEDA untuk seorang nasabah, perbedaannya 100% berasal dari ATURAN
 *  (gate) yang berbeda — bukan asumsi. Skrip mencatat tiap ketidaksepakatan
 *  beserta gate mana yang membalik (reason).
 *
 *  Interpretasi hasil:
 *    - Jika targetAndroid (atas RTDB) == angka di app (mis. 1.527.000) →
 *      selisih BERSIFAT ATURAN; lihat daftar ANDROID_ONLY + reason-nya.
 *    - Jika targetAndroid (atas RTDB) == targetWeb (mis. 1.425.000) tapi app
 *      menampilkan angka lain → selisih BERSIFAT DATA/SYNC (data in-memory /
 *      antrian offline app beda dgn RTDB), bukan aturan.
 *
 *  Bucket ketidaksepakatan:
 *    - ANDROID_ONLY : androidEval hitung, webEval drop. (Web meng-undercount.)
 *    - WEB_ONLY     : webEval hitung, androidEval drop.
 *
 *  CARA PAKAI
 *  ----------
 *    SERVICE_ACCOUNT=./serviceAccountKey.json node diagnoseTargetDivergence.js
 *  Opsi env:
 *    SERVICE_ACCOUNT  path service account JSON (default ./serviceAccountKey.json)
 *    DATABASE_URL     URL RTDB (default project asia-southeast1)
 *    TODAY            tanggal acuan "dd MMM yyyy" (default "23 Mei 2026")
 *    CABANG           filter cabang: key/nama tampilan (opsional, mis. "Panti")
 *    RESORT           filter substring nama resort/admin (opsional, mis. "Mekar")
 *    ADMIN            filter adminUid persis (opsional)
 *    OUT              path output JSON (default ./divergence_report.json)
 *    OUT_CSV          path output CSV ketidaksepakatan (default ./divergence_list.csv)
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
// Helper tanggal (format lokal "dd MMM yyyy") — konsisten target.js/Android
// ---------------------------------------------------------------------------
const BULAN_INDO = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun',
                    'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
const BULAN = {};
BULAN_INDO.forEach((b, i) => { BULAN[b] = i; });
function parseTgl(s) {
  if (!s || typeof s !== 'string') return null;
  const p = s.trim().split(' ');
  if (p.length !== 3) return null;
  const m = BULAN[p[1]];
  if (m === undefined) return null;
  const d = parseInt(p[0], 10);
  const y = parseInt(p[2], 10);
  if (isNaN(d) || isNaN(y)) return null;
  return new Date(y, m, d);
}
const listOf = (x) => (!x ? [] : (Array.isArray(x) ? x : Object.values(x)));

const todayDate = parseTgl(TODAY);
if (!todayDate) { console.error(`TODAY tidak valid: "${TODAY}"`); process.exit(1); }
// Batas macet = tanggal 1, tiga bulan sebelum bulan TODAY (identik web & Android)
const threeMonthsAgo = new Date(todayDate.getFullYear(), todayDate.getMonth() - 3, 1);

// totalDibayar ala bukuPokokApi.calculateTotalDibayar: exclude entry "Bunga...",
// INCLUDE "Pelunasan Top-Up". (Dipakai sisi WEB.)
function totalDibayarWeb(p) {
  let t = 0;
  listOf(p.pembayaranList).forEach((x) => {
    if (!x) return;
    if (x.tanggal && String(x.tanggal).startsWith('Bunga')) return;
    t += x.jumlah || 0;
    listOf(x.subPembayaran).forEach((s) => { t += (s && s.jumlah) || 0; });
  });
  return t;
}

// totalBayar ala RingkasanDashboardScreen.kt:119-121: SUM SEMUA pembayaranList
// (TERMASUK "Bunga", termasuk subPembayaran). (Dipakai sisi ANDROID.)
function totalBayarAndroid(p) {
  let t = 0;
  listOf(p.pembayaranList).forEach((x) => {
    if (!x) return;
    t += x.jumlah || 0;
    listOf(x.subPembayaran).forEach((s) => { t += (s && s.jumlah) || 0; });
  });
  return t;
}

// Apakah ada entry "Bunga..." di pembayaranList? (utk diagnosis selisih lunas.)
function hasBunga(p) {
  return listOf(p.pembayaranList).some((x) => x && x.tanggal && String(x.tanggal).startsWith('Bunga'));
}

// Tanggal pembayaran terbaru (exclude "Bunga") — konteks saja.
function lastPaymentRaw(p) {
  let max = null, raw = null;
  const consider = (r) => {
    if (r && String(r).startsWith('Bunga')) return;
    const d = parseTgl(r);
    if (d && (!max || d > max)) { max = d; raw = r; }
  };
  listOf(p.pembayaranList).forEach((x) => {
    if (!x) return;
    consider(x.tanggal);
    listOf(x.subPembayaran).forEach((s) => consider(s && s.tanggal));
  });
  return raw;
}

// ---------------------------------------------------------------------------
// webEval — replika 1:1 buku-pokok-web/lib/target.js isEligibleForTarget(today)
//   return { included, target, reason }
// ---------------------------------------------------------------------------
function webEval(p, dateStr) {
  const cur = parseTgl(dateStr);
  if (!cur) return { included: false, target: 0, reason: 'bad_date' };

  const statusLower = (p.status || '').toLowerCase();
  const isStatusAktif = statusLower === 'aktif' || statusLower === 'active';
  const lunasHariIni = (p.tanggalLunasCicilan || '').trim() === dateStr;
  const isMenungguPencairan = p.statusKhusus === 'MENUNGGU_PENCAIRAN'
    && (p.statusPencairanSimpanan || '') !== 'Dicairkan';
  const menungguHariIni = isMenungguPencairan && (p.tanggalStatusKhusus || '').trim() === dateStr;
  if (!isStatusAktif && !lunasHariIni && !menungguHariIni) return { included: false, target: 0, reason: 'status' };

  const target = Math.floor((p.besarPinjaman || 0) * 3 / 100);
  const totalPelunasan = p.totalPelunasan || 0;
  const totDibayar = totalDibayarWeb(p);

  const isSudahLunas = totalPelunasan > 0 && totDibayar >= totalPelunasan;
  if (isSudahLunas) {
    const tglLunas = (p.tanggalLunasCicilan || '').trim();
    const lunasDate = parseTgl(tglLunas);
    if (lunasDate) {
      if (tglLunas === dateStr) return { included: true, target, reason: 'COUNTED' };
      if (lunasDate < cur) return { included: false, target: 0, reason: 'lunas' };
      // lunasDate > cur → pd tanggal ini belum lunas → lanjut
    } else {
      // tanpa tanggalLunasCicilan valid → untuk "hari ini" anggap lunas
      return { included: false, target: 0, reason: 'lunas' };
    }
  }

  if (isMenungguPencairan && !menungguHariIni) return { included: false, target: 0, reason: 'menunggu' };

  const tglPencairan = (p.tanggalPencairan || '').trim();
  if (tglPencairan && tglPencairan === dateStr) return { included: false, target: 0, reason: 'cair_today' };

  const tglAcuan = tglPencairan
    || (p.tanggalPengajuan || '').trim()
    || (p.tanggalDaftar || '').trim();
  const acuan = parseTgl(tglAcuan);
  if (acuan) {
    if (acuan > cur) return { included: false, target: 0, reason: 'not_yet_future_acuan' };
    if (acuan < threeMonthsAgo) return { included: false, target: 0, reason: 'macet' };
  }
  return { included: true, target, reason: 'COUNTED' };
}

// ---------------------------------------------------------------------------
// androidEval — replika 1:1 RingkasanDashboardScreen.kt:115-189
//   return { included, target, reason }
// ---------------------------------------------------------------------------
function androidEval(p, dateStr) {
  const status = p.status || '';
  if (status === 'Disetujui' || status === 'Tidak Aktif') {
    return { included: false, target: 0, reason: 'status_disetujui/tidakaktif' };
  }

  const totalPelunasan = p.totalPelunasan || 0;
  const totBayar = totalBayarAndroid(p);            // INCLUDING Bunga
  const isBelumLunas = totBayar < totalPelunasan;

  const isMenungguPencairan = p.statusKhusus === 'MENUNGGU_PENCAIRAN'; // TANPA cek Dicairkan
  const tglAcuan = (p.tanggalPencairan || '').trim()
    || (p.tanggalPengajuan || '').trim()
    || (p.tanggalDaftar || '').trim();
  const acuan = parseTgl(tglAcuan);
  const isOverThreeMonths = !!(acuan && acuan < threeMonthsAgo); // parse gagal → false

  const tglPencairan = (p.tanggalPencairan || '').trim();
  const isCairHariIni = tglPencairan !== '' && tglPencairan === dateStr;
  const isLunasHariIni = !isBelumLunas && (p.tanggalLunasCicilan || '').trim() === dateStr;
  const isMenungguPencairanHariIni = isMenungguPencairan && (p.tanggalStatusKhusus || '').trim() === dateStr;
  const sl = status.toLowerCase();
  const isStatusAktif = status === 'Aktif' || sl === 'aktif' || status === 'Active';

  const target = Math.floor((p.besarPinjaman || 0) * 3 / 100);

  // Gate berurutan (utk reason yg presisi)
  if (!(isBelumLunas || isLunasHariIni)) return { included: false, target: 0, reason: 'lunas' };
  if (!(!isMenungguPencairan || isMenungguPencairanHariIni)) return { included: false, target: 0, reason: 'menunggu' };
  if (isOverThreeMonths) return { included: false, target: 0, reason: 'macet' };
  if (isCairHariIni) return { included: false, target: 0, reason: 'cair_today' };
  if (!(isStatusAktif || isLunasHariIni || isMenungguPencairanHariIni)) return { included: false, target: 0, reason: 'status' };
  return { included: true, target, reason: 'COUNTED' };
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
  console.log('[DIAG] Membaca pelanggan + metadata (.once value)…\n');

  const [snap, adminsSnap, cabangSnap] = await Promise.all([
    db.ref('pelanggan').once('value'),
    db.ref('metadata/admins').once('value'),
    db.ref('metadata/cabang').once('value'),
  ]);
  const allAdmins = snap.val() || {};
  const adminMeta = adminsSnap.val() || {};
  const cabangMeta = cabangSnap.val() || {};
  const cabangDisplayOf = (key) => (key && cabangMeta[key] && cabangMeta[key].name) || key || '';

  const CABANG_L = CABANG.toLowerCase();

  const resorts = {};
  const disagree = []; // flat list ANDROID_ONLY / WEB_ONLY
  let scanned = 0;

  for (const [adminUid, bucket] of Object.entries(allAdmins)) {
    if (!bucket || typeof bucket !== 'object') continue;
    if (ADMIN && adminUid !== ADMIN) continue;

    const meta = adminMeta[adminUid] || {};
    const resort = meta.name || meta.email || adminUid;
    const cabangKey = (meta.cabang || '').trim();
    const cabangDisplay = cabangDisplayOf(cabangKey);

    if (CABANG_L && !(cabangKey.toLowerCase().includes(CABANG_L)
        || cabangDisplay.toLowerCase().includes(CABANG_L))) continue;
    if (RESORT && !resort.toLowerCase().includes(RESORT)) continue;

    let targetWeb = 0, targetAndroid = 0;
    let nWeb = 0, nAndroid = 0, nAndroidOnly = 0, nWebOnly = 0;

    for (const [pid, p] of Object.entries(bucket)) {
      if (!p || typeof p !== 'object') continue;
      scanned++;
      const w = webEval(p, TODAY);
      const a = androidEval(p, TODAY);
      if (w.included) { targetWeb += w.target; nWeb++; }
      if (a.included) { targetAndroid += a.target; nAndroid++; }

      if (w.included === a.included) continue; // sepakat

      const bucketName = a.included ? 'ANDROID_ONLY' : 'WEB_ONLY';
      if (a.included) nAndroidOnly++; else nWebOnly++;
      disagree.push({
        bucket: bucketName,
        path: `pelanggan/${adminUid}/${pid}`,
        resort, cabang: cabangDisplay,
        nama: p.namaPanggilan || p.namaKtp || '',
        pinjamanKe: p.pinjamanKe || 1,
        besarPinjaman: p.besarPinjaman || 0,
        target: Math.floor((p.besarPinjaman || 0) * 3 / 100),
        webIncluded: w.included, webReason: w.reason,
        androidIncluded: a.included, androidReason: a.reason,
        status: p.status || '',
        statusKhusus: p.statusKhusus || '',
        statusPencairanSimpanan: p.statusPencairanSimpanan || '',
        totalPelunasan: p.totalPelunasan || 0,
        totalDibayarWeb: totalDibayarWeb(p),
        totalBayarAndroid: totalBayarAndroid(p),
        hasBunga: hasBunga(p),
        tanggalPencairan: (p.tanggalPencairan || '').trim() || null,
        tanggalPengajuan: (p.tanggalPengajuan || '').trim() || null,
        tanggalDaftar: (p.tanggalDaftar || '').trim() || null,
        tanggalLunasCicilan: (p.tanggalLunasCicilan || '').trim() || null,
        tanggalStatusKhusus: (p.tanggalStatusKhusus || '').trim() || null,
        lastPayment: lastPaymentRaw(p),
      });
    }

    if (nWeb + nAndroid + nAndroidOnly + nWebOnly === 0) continue;
    resorts[adminUid] = {
      adminUid, resort, cabang: cabangDisplay,
      targetWeb, targetAndroid,
      diff: targetAndroid - targetWeb,
      counts: { web: nWeb, android: nAndroid, androidOnly: nAndroidOnly, webOnly: nWebOnly },
    };
  }

  // Urutkan disagree: ANDROID_ONLY dulu, target terbesar
  disagree.sort((x, y) => (x.bucket === y.bucket ? y.target - x.target : (x.bucket === 'ANDROID_ONLY' ? -1 : 1)));

  // Tally reason untuk ANDROID_ONLY (kenapa web men-drop) & WEB_ONLY
  const reasonTally = { ANDROID_ONLY: {}, WEB_ONLY: {} };
  const reasonAmt = { ANDROID_ONLY: {}, WEB_ONLY: {} };
  for (const d of disagree) {
    const key = d.bucket === 'ANDROID_ONLY' ? d.webReason : d.androidReason;
    reasonTally[d.bucket][key] = (reasonTally[d.bucket][key] || 0) + 1;
    reasonAmt[d.bucket][key] = (reasonAmt[d.bucket][key] || 0) + d.target;
  }

  const resortArr = Object.values(resorts).sort((a, b) => Math.abs(b.diff) - Math.abs(a.diff));
  const totals = resortArr.reduce((s, r) => {
    s.targetWeb += r.targetWeb; s.targetAndroid += r.targetAndroid; return s;
  }, { targetWeb: 0, targetAndroid: 0 });

  const report = {
    generatedAt: new Date().toISOString(),
    today: TODAY,
    macetBoundary: `< ${threeMonthsAgo.getDate()} ${BULAN_INDO[threeMonthsAgo.getMonth()]} ${threeMonthsAgo.getFullYear()}`,
    readOnly: true,
    filter: { cabang: CABANG || null, resort: RESORT || null, admin: ADMIN || null },
    method: 'webEval(target.js) vs androidEval(RingkasanDashboardScreen) atas data RTDB yg sama',
    totals: {
      targetWeb: totals.targetWeb,
      targetAndroid: totals.targetAndroid,
      diff: totals.targetAndroid - totals.targetWeb,
    },
    reasonTally, reasonAmt,
    resorts: resortArr,
    disagreements: disagree,
  };
  fs.writeFileSync(path.resolve(OUT), JSON.stringify(report, null, 2));

  // CSV ketidaksepakatan
  const cols = ['bucket','cabang','resort','nama','pinjamanKe','besarPinjaman','target',
    'webIncluded','webReason','androidIncluded','androidReason','status','statusKhusus',
    'statusPencairanSimpanan','totalPelunasan','totalDibayarWeb','totalBayarAndroid','hasBunga',
    'tanggalPencairan','tanggalPengajuan','tanggalDaftar','tanggalLunasCicilan','tanggalStatusKhusus','lastPayment','path'];
  const esc = (v) => { const s = (v == null ? '' : String(v)); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
  const csv = [cols.join(',')].concat(disagree.map((d) => cols.map((c) => esc(d[c])).join(','))).join('\n') + '\n';
  fs.writeFileSync(path.resolve(OUT_CSV), csv);

  // Ringkasan konsol
  const fmt = (n) => n.toLocaleString('id-ID');
  console.log(`[DIAG] Dipindai ${scanned} nasabah.\n`);
  console.log('Per resort (urut |diff| terbesar):');
  console.log('  ' + 'RESORT'.padEnd(26) + 'targetWeb'.padStart(13) + 'targetAndroid'.padStart(15) + 'diff'.padStart(11) + '  (web/and/+A/+W)');
  for (const r of resortArr) {
    console.log('  ' + r.resort.slice(0, 25).padEnd(26)
      + fmt(r.targetWeb).padStart(13)
      + fmt(r.targetAndroid).padStart(15)
      + fmt(r.diff).padStart(11)
      + `   ${r.counts.web}/${r.counts.android}/${r.counts.androidOnly}/${r.counts.webOnly}`);
  }
  console.log('\nTOTAL:');
  console.log(`  targetWeb     : Rp ${fmt(totals.targetWeb)}`);
  console.log(`  targetAndroid : Rp ${fmt(totals.targetAndroid)}   (replika aturan Android atas RTDB)`);
  console.log(`  diff          : Rp ${fmt(totals.targetAndroid - totals.targetWeb)}`);

  const printReasons = (bucket, label) => {
    const t = reasonTally[bucket], amt = reasonAmt[bucket];
    const keys = Object.keys(t);
    if (!keys.length) return;
    console.log(`\n${label}:`);
    keys.sort((a, b) => amt[b] - amt[a]).forEach((k) => {
      console.log(`  ${k.padEnd(28)} ${String(t[k]).padStart(3)} nasabah  Rp ${fmt(amt[k])}`);
    });
  };
  printReasons('ANDROID_ONLY', 'ANDROID_ONLY (Android hitung, Web DROP) — alasan Web men-drop');
  printReasons('WEB_ONLY', 'WEB_ONLY (Web hitung, Android DROP) — alasan Android men-drop');

  console.log(`\nDetail ketidaksepakatan: ${disagree.length} nasabah → ${path.resolve(OUT_CSV)}`);
  console.log(`Laporan lengkap → ${path.resolve(OUT)}`);
  console.log('\n→ Bandingkan targetAndroid di atas dgn angka di app.');
  console.log('  Sama  → selisih bersifat ATURAN (lihat ANDROID_ONLY reason).');
  console.log('  Beda  → selisih bersifat DATA/SYNC (in-memory app ≠ RTDB).');

  await admin.app().delete();
  process.exit(0);
})().catch((err) => {
  console.error('[DIAG] Error:', err);
  process.exit(1);
});
