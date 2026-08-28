#!/usr/bin/env node
'use strict';
/* =========================================================================
 * MIGRASI rekap_harian_final — RTDB → koperasi.rekap_harian_beku
 * =========================================================================
 * BELUM PERNAH DIJALANKAN. Prasyarat: 026_rekap_harian_final.sql terpasang.
 *
 * ⚠ HANYA MUNGKIN SEBELUM 1 SEPTEMBER 2026.
 *
 * Node ini adalah "benteng anti-shrink historis" (bukuPokokApi.js:951) —
 * nilai Target & Storting yang SUDAH DIBEKUKAN dan sudah tercetak di laporan
 * lama. Skrip ini MEMINDAHKAN nilainya apa adanya. Ia tidak menghitung
 * apa pun; menghitung ulang justru hal yang node ini dibuat untuk mencegah.
 *
 * Struktur asal (freezeRekapHarian.js:22-26):
 *   rekap_harian_final/{adminUid}/{YYYY-MM-DD} = { target, storting, frozenAt }
 *
 * PEMAKAIAN
 *   node --max-old-space-size=8192 scripts/migration/migrate_rekap_harian_final.js \
 *        --file=/path/export.json
 *   node --max-old-space-size=8192 scripts/migration/migrate_rekap_harian_final.js \
 *        --file=/path/export.json --dsn="postgresql://…:5432/postgres" --execute
 * ========================================================================= */

const fs = require('fs');
const crypto = require('crypto');

let Client = null;

const argv = process.argv.slice(2);
const arg = (k, d = null) => {
  const h = argv.find((a) => a === `--${k}` || a.startsWith(`--${k}=`));
  if (!h) return d;
  const e = h.indexOf('=');
  return e === -1 ? true : h.slice(e + 1);
};

const CFG = {
  file: arg('file'),
  dsn: arg('dsn', process.env.SUPABASE_DSN),
  execute: arg('execute', false) === true,
  batch: parseInt(arg('batch', '1000'), 10),
  report: arg('report', './rekap_beku_report.json'),
};

if (!CFG.file) { console.error('FATAL: --file wajib.'); process.exit(2); }
if (CFG.execute && !CFG.dsn) {
  console.error('FATAL: --execute butuh --dsn atau env SUPABASE_DSN.');
  process.exit(2);
}

const log = (...a) => console.log(...a);
const ISSUES = [];
const issue = (kind, detail) => {
  ISSUES.push({ kind, detail });
  if (ISSUES.filter((i) => i.kind === kind).length <= 5) console.warn('  ⚠ ', `${kind}: ${detail}`);
};

// --- helper: SALINAN dari migrate.js, harus tetap identik -----------------
const SKIP = new Set(['...', '_guardPinjamanKe', '_guardStatus']);
const realKeys = (o) => (o && typeof o === 'object' ? Object.keys(o).filter((k) => !SKIP.has(k)) : []);
const str = (v) => (v == null ? '' : String(v));
const slugCabang = (c) => str(c).trim().toLowerCase().replace(/\s+/g, ' ');
const rupiah = (v) => {
  if (v == null || v === '') return 0;
  if (typeof v === 'number') return Number.isFinite(v) ? Math.round(v) : 0;
  const n = Number(String(v).replace(/[^0-9-]/g, ''));
  return Number.isFinite(n) ? Math.round(n) : 0;
};

const NS = Buffer.from('6ba7b8119dad11d180b400c04fd430c8', 'hex');
function uuidv5(name) {
  const h = crypto.createHash('sha1');
  h.update(NS);
  h.update(Buffer.from(String(name), 'utf8'));
  const b = h.digest();
  b[6] = (b[6] & 0x0f) | 0x50;
  b[8] = (b[8] & 0x3f) | 0x80;
  const x = b.toString('hex');
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20, 32)}`;
}
const idUser = (uid) => uuidv5(`user:${uid}`);

const RE_ISO = /^(\d{4})-(\d{2})-(\d{2})$/;

// ================================================================= LOAD ===
log('▶ Membaca export …', CFG.file);
const RAW = fs.readFileSync(CFG.file, 'utf8');
if (RAW.includes('more keys')) {
  console.error('FATAL: export terpotong ("N more keys"). Pakai export penuh.');
  process.exit(3);
}
const DB = JSON.parse(RAW);
const node = (n) => (DB && DB[n] && typeof DB[n] === 'object' ? DB[n] : {});

const RF = node('rekap_harian_final');
if (!realKeys(RF).length) {
  console.error('FATAL: node `rekap_harian_final` tidak ada / kosong di export ini.');
  console.error('       Ekspor dari Firebase Console → RTDB → rekap_harian_final → Export JSON.');
  process.exit(4);
}

const meta = node('metadata');
const adminMeta = meta.admins || {};
const adminUids = new Set(realKeys(adminMeta));
const cabangDikenal = new Set();
for (const uid of adminUids) {
  const c = slugCabang((adminMeta[uid] || {}).cabang);
  if (c) cabangDikenal.add(c);
}
for (const c of realKeys(meta.cabang || {})) cabangDikenal.add(slugCabang(c));

// ============================================================ TRANSFORM ===
const ROWS = [];
const perAdmin = {};          // ringkasan yang diminta: admin_uid → jumlah baris
let gagal = 0;
let adminYatim = 0;
let cabangKosong = 0;

for (const adminUid of realKeys(RF)) {
  const tanggalMap = RF[adminUid];
  if (!tanggalMap || typeof tanggalMap !== 'object') {
    issue('REKAP_ADMIN_BUKAN_OBJEK', adminUid); gagal++; continue;
  }

  const kenal = adminUids.has(adminUid);
  if (!kenal) adminYatim++;

  // cabang_id dari metadata admin. Kalau adminnya sudah tidak terdaftar,
  // cabangnya tidak diketahui — dibiarkan NULL, BUKAN ditebak. Baris tetap
  // masuk: justru admin lama yang paling historis.
  let cab = kenal ? slugCabang((adminMeta[adminUid] || {}).cabang) : '';
  if (cab && !cabangDikenal.has(cab)) { issue('REKAP_CABANG_TIDAK_DIKENAL', `${adminUid} → ${cab}`); cab = ''; }
  if (!cab) cabangKosong++;

  let n = 0;
  for (const tgl of realKeys(tanggalMap)) {
    if (!RE_ISO.test(tgl)) { issue('REKAP_TANGGAL_TIDAK_VALID', `${adminUid}/${tgl}`); gagal++; continue; }
    const e = tanggalMap[tgl];
    if (!e || typeof e !== 'object') { issue('REKAP_ENTRI_BUKAN_OBJEK', `${adminUid}/${tgl}`); gagal++; continue; }

    // Nilai diambil APA ADANYA. `target: 0` adalah data yang sah — hari libur
    // atau nol memang dibekukan dengan sengaja (freezeRekapHarian.js:74-76),
    // jadi jangan disaring sebagai "kosong".
    ROWS.push({
      legacy_admin_uid: adminUid,
      tanggal: tgl,
      target: rupiah(e.target),
      storting: rupiah(e.storting),
      admin_id: kenal ? idUser(adminUid) : null,
      cabang_id: cab || null,
      frozen_at: e.frozenAt ? new Date(Number(e.frozenAt)).toISOString() : null,
      sumber: 'rtdb',
    });
    n++;
  }
  perAdmin[adminUid] = { baris: n, terdaftar: kenal, cabang: cab || null };
}

const tglUrut = ROWS.map((r) => r.tanggal).sort();
log(`\n▶ Ringkasan`);
log(`   baris diimpor    : ${ROWS.length}`);
log(`   gagal            : ${gagal}`);
log(`   admin berbeda    : ${Object.keys(perAdmin).length}`);
log(`   rentang tanggal  : ${tglUrut[0] || '-'} … ${tglUrut[tglUrut.length - 1] || '-'}`);
log(`   total target     : Rp ${ROWS.reduce((a, b) => a + b.target, 0).toLocaleString('id-ID')}`);
log(`   total storting   : Rp ${ROWS.reduce((a, b) => a + b.storting, 0).toLocaleString('id-ID')}`);
if (adminYatim)   log(`   ⚠ admin_id NULL (tidak terdaftar lagi) : ${adminYatim}`);
if (cabangKosong) log(`   ⚠ cabang_id NULL                      : ${cabangKosong}`);

log(`\n▶ Per admin_uid`);
for (const [uid, v] of Object.entries(perAdmin).sort((a, b) => b[1].baris - a[1].baris)) {
  const nama = (adminMeta[uid] || {}).name || (v.terdaftar ? '' : '(tidak terdaftar)');
  log(`   ${uid}  ${String(v.baris).padStart(5)} baris  ${(v.cabang || '-').padEnd(12)} ${nama}`);
}

const kindCount = ISSUES.reduce((m, i) => ((m[i.kind] = (m[i.kind] || 0) + 1), m), {});
fs.writeFileSync(CFG.report, JSON.stringify({
  generatedAt: new Date().toISOString(),
  sourceFile: CFG.file, executed: CFG.execute,
  baris: ROWS.length, gagal, adminYatim, cabangKosong,
  perAdmin, issueCounts: kindCount, issues: ISSUES.slice(0, 1000),
}, null, 2));

if (!CFG.execute) {
  log(`\n✓ DRY-RUN selesai. Tidak ada yang ditulis. Laporan → ${CFG.report}`);
  log('  Tambahkan --execute (beserta --dsn) untuk menulis.');
  return;
}

// ================================================================ WRITE ===
(async () => {
  try { ({ Client } = require('pg')); }
  catch { console.error('FATAL: paket `pg` belum terpasang. npm i pg'); process.exit(5); }

  const client = new Client({ connectionString: CFG.dsn });
  await client.connect();
  log('\n▶ Menulis ke Postgres (satu transaksi)');

  const cols = ['legacy_admin_uid', 'tanggal', 'target', 'storting',
                'admin_id', 'cabang_id', 'frozen_at', 'sumber'];
  try {
    await client.query('begin');
    let done = 0;
    for (let i = 0; i < ROWS.length; i += CFG.batch) {
      const chunk = ROWS.slice(i, i + CFG.batch);
      const params = [];
      const tuples = chunk.map((r) => {
        const ph = cols.map((c) => { params.push(r[c] === undefined ? null : r[c]); return `$${params.length}`; });
        return `(${ph.join(',')})`;
      }).join(',');
      // `do nothing`: sekali beku tetap beku. Jalan-ulang tidak menimpa.
      await client.query(
        `insert into koperasi.rekap_harian_beku (${cols.map((c) => `"${c}"`).join(',')}) ` +
        `values ${tuples} on conflict (legacy_admin_uid, tanggal) do nothing`,
        params
      );
      done += chunk.length;
      process.stdout.write(`\r    ${done}/${ROWS.length}`);
    }
    process.stdout.write('\n');
    await client.query('commit');
    log('✓ COMMIT.');

    const { rows } = await client.query(
      'select count(*)::int n, count(distinct legacy_admin_uid)::int admin, ' +
      'min(tanggal) awal, max(tanggal) akhir, ' +
      "count(*) filter (where admin_id is null)::int yatim " +
      'from koperasi.rekap_harian_beku');
    log(`  di database: ${rows[0].n} baris, ${rows[0].admin} admin, ` +
        `${rows[0].awal} … ${rows[0].akhir}, admin_id NULL: ${rows[0].yatim}`);
    log(`  dari sumber: ${ROWS.length} baris`);
    if (rows[0].n !== ROWS.length) {
      log('  ⚠ Selisih baris. Kalau ini bukan jalan-ulang, periksa laporan.');
    }
    log('\n  Berikutnya: 026 §VERIFIKASI no. 2 (format tanggal_indo) dan no. 3');
    log('  (bandingkan beberapa (admin, tanggal) acak dengan RTDB selagi hidup).');
  } catch (e) {
    await client.query('rollback').catch(() => {});
    console.error('\n✗ ROLLBACK — tidak ada data yang tersimpan.\n', e.message);
    process.exitCode = 1;
  } finally {
    await client.end();
  }
})();
