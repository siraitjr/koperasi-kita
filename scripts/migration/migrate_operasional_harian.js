#!/usr/bin/env node
'use strict';
/* =========================================================================
 * MIGRASI operasional_harian — RTDB → koperasi.operasional_harian
 * =========================================================================
 * BELUM PERNAH DIJALANKAN.
 *
 * Node ini tertinggal dari migrasi utama (006 §6 mendaftarkannya sebagai
 * "di luar lingkup") karena saat itu belum ada yang memakainya. Ternyata
 * `syncOperasionalTransport` — yang dipakai kasir SETIAP HARI — membacanya
 * sebagai satu-satunya sumber angka.
 *
 * Pola sama persis dengan migrate.js:
 *   - DRY-RUN default; menulis hanya dengan --execute
 *   - idempoten: PK (cabang_id, tanggal, legacy_uid) + on conflict do nothing
 *   - menolak export terpotong
 *   - FK divalidasi lebih dulu; yang induknya hilang di-NULL-kan, bukan
 *     membuat seluruh transaksi gagal
 *
 * PEMAKAIAN
 *   node --max-old-space-size=8192 migrate_operasional_harian.js \
 *        --file=/path/export.json
 *   node --max-old-space-size=8192 migrate_operasional_harian.js \
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
  batch: parseInt(arg('batch', '500'), 10),
  report: arg('report', './operasional_report.json'),
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

// --- helper: SALINAN dari migrate.js, harus tetap identik ----------------
const SKIP = new Set(['...', '_guardPinjamanKe', '_guardStatus']);
const realKeys = (o) => (o && typeof o === 'object' ? Object.keys(o).filter((k) => !SKIP.has(k)) : []);
const str = (v) => (v == null ? '' : String(v));
const rupiah = (v) => {
  if (v == null || v === '') return 0;
  if (typeof v === 'number') return Math.round(v);
  const n = Number(String(v).replace(/[^0-9-]/g, ''));
  return Number.isFinite(n) ? Math.round(n) : 0;
};
const slugCabang = (c) => str(c).trim().toLowerCase().replace(/\s+/g, ' ');

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
// WAJIB identik dengan ID.user di migrate.js — kalau berbeda, seluruh FK
// ke app_user meleset dan baris ini kehilangan tautannya.
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

const OH = node('operasional_harian');
if (!realKeys(OH).length) {
  console.error('FATAL: node `operasional_harian` tidak ada / kosong di export ini.');
  console.error('       Lihat 016 §2 untuk langkah export dari Firebase Console.');
  process.exit(4);
}

// Himpunan referensi — dibangun dari export yang sama supaya konsisten.
const adminUids = new Set(realKeys(node('metadata').admins || {}));
const cabangDikenal = new Set();
for (const uid of adminUids) {
  const c = slugCabang((node('metadata').admins[uid] || {}).cabang);
  if (c) cabangDikenal.add(c);
}
for (const c of realKeys(node('metadata').cabang || {})) cabangDikenal.add(slugCabang(c));

// ============================================================ TRANSFORM ===
const ROWS = [];
let dilewatiCabang = 0;
let userYatim = 0;
let pemberiYatim = 0;

for (const cabRaw of realKeys(OH)) {
  const cab = slugCabang(cabRaw);
  if (!cabangDikenal.has(cab)) {
    // cabang_id NOT NULL + FK — tidak ada cara menyimpannya.
    issue('OPS_CABANG_TIDAK_DIKENAL', `${cabRaw} — seluruh tanggalnya dilewati`);
    dilewatiCabang += realKeys(OH[cabRaw]).length;
    continue;
  }

  for (const tgl of realKeys(OH[cabRaw])) {
    if (!RE_ISO.test(tgl)) {
      issue('OPS_TANGGAL_TIDAK_VALID', `${cab}/${tgl}`);
      continue;
    }

    for (const uid of realKeys(OH[cabRaw][tgl])) {
      const r = OH[cabRaw][tgl][uid];
      if (!r || typeof r !== 'object') continue;

      const uidAsli = str(r.uid) || uid;   // kunci record = UID staf
      const punyaUser = adminUids.has(uidAsli);
      if (!punyaUser) userYatim++;

      const pemberi = str(r.diberikanOleh);
      const punyaPemberi = pemberi && adminUids.has(pemberi);
      if (pemberi && !punyaPemberi) pemberiYatim++;

      ROWS.push({
        cabang_id: cab,
        tanggal: tgl,
        // FK nullable: staf yang sudah keluar tetap terbawa catatannya.
        user_id: punyaUser ? idUser(uidAsli) : null,
        legacy_uid: uidAsli,
        nama: str(r.nama),
        uang_makan: rupiah(r.uangMakan),
        transport: rupiah(r.transport),
        diberikan_oleh: punyaPemberi ? idUser(pemberi) : null,
        diberikan_oleh_nama: str(r.diberikanOlehNama),
        diberikan_oleh_legacy_uid: pemberi || null,
        recorded_at: r.timestamp ? new Date(Number(r.timestamp)).toISOString() : null,
      });
    }
  }
}

const totalRp = ROWS.reduce((a, b) => a + b.uang_makan + b.transport, 0);
log(`\n▶ Ringkasan`);
log(`   baris            : ${ROWS.length}`);
log(`   total nominal    : Rp ${totalRp.toLocaleString('id-ID')}`);
log(`   cabang           : ${new Set(ROWS.map((r) => r.cabang_id)).size}`);
log(`   rentang tanggal  : ${ROWS.length ? ROWS.map((r) => r.tanggal).sort()[0] : '-'}`
  + ` … ${ROWS.length ? ROWS.map((r) => r.tanggal).sort().slice(-1)[0] : '-'}`);
if (userYatim)   log(`   ⚠ user_id NULL (staf tidak terdaftar)      : ${userYatim}`);
if (pemberiYatim) log(`   ⚠ diberikan_oleh NULL (pemberi tidak ada) : ${pemberiYatim}`);
if (dilewatiCabang) log(`   ⚠ dilewati karena cabang tidak dikenal    : ${dilewatiCabang}`);

const kindCount = ISSUES.reduce((m, i) => ((m[i.kind] = (m[i.kind] || 0) + 1), m), {});
fs.writeFileSync(CFG.report, JSON.stringify({
  generatedAt: new Date().toISOString(),
  sourceFile: CFG.file, executed: CFG.execute,
  baris: ROWS.length, totalRupiah: totalRp,
  userYatim, pemberiYatim, dilewatiCabang,
  issueCounts: kindCount, issues: ISSUES.slice(0, 1000),
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

  const cols = ['cabang_id','tanggal','user_id','legacy_uid','nama','uang_makan',
                'transport','diberikan_oleh','diberikan_oleh_nama',
                'diberikan_oleh_legacy_uid','recorded_at'];
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
      await client.query(
        `insert into koperasi.operasional_harian (${cols.map((c) => `"${c}"`).join(',')}) ` +
        `values ${tuples} on conflict (cabang_id, tanggal, legacy_uid) do nothing`,
        params
      );
      done += chunk.length;
      process.stdout.write(`\r    ${done}/${ROWS.length}`);
    }
    process.stdout.write('\n');
    await client.query('commit');
    log('✓ COMMIT.');

    const { rows } = await client.query(
      'select count(*)::int as n, coalesce(sum(uang_makan + transport),0)::bigint as rp ' +
      'from koperasi.operasional_harian');
    log(`  di database: ${rows[0].n} baris, Rp ${Number(rows[0].rp).toLocaleString('id-ID')}`);
    log(`  dari sumber: ${ROWS.length} baris, Rp ${totalRp.toLocaleString('id-ID')}`);
    if (rows[0].n !== ROWS.length) {
      log('  ⚠ Selisih baris. Kalau ini bukan jalan-ulang, periksa laporan.');
    }
  } catch (e) {
    await client.query('rollback').catch(() => {});
    console.error('\n✗ ROLLBACK — tidak ada data yang tersimpan.\n', e.message);
    process.exitCode = 1;
  } finally {
    await client.end();
  }
})();
