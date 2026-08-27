#!/usr/bin/env node
'use strict';
/* =========================================================================
 * MIGRASI absensi — RTDB → koperasi.absensi
 * =========================================================================
 * BELUM PERNAH DIJALANKAN. Prasyarat: 023_absensi.sql sudah terpasang.
 *
 * ⚠ HANYA MUNGKIN SEBELUM 1 SEPTEMBER 2026. Absensi tidak pernah masuk
 *   lingkup migrasi mana pun (021 §1.4); ia hidup HANYA di RTDB dan hilang
 *   permanen saat suspend. Ekspor node-nya lebih dulu, apa pun keputusan
 *   soal fiturnya.
 *
 * Pola sama persis dengan migrate_operasional_harian.js:
 *   - DRY-RUN default; menulis hanya dengan --execute
 *   - idempoten: PK (cabang_id, tanggal, legacy_uid) + on conflict do nothing
 *   - menolak ekspor terpotong
 *   - FK yatim di-NULL-kan, bukan menggagalkan seluruh transaksi
 *
 * `user_absensi_today` TIDAK diimpor: ia turunan (cermin baris terakhir) dan
 * sudah digantikan view `koperasi.v_absensi_hari_ini` (023).
 *
 * PEMAKAIAN
 *   node --max-old-space-size=8192 scripts/migration/migrate_absensi.js \
 *        --file=/path/export.json
 *   node --max-old-space-size=8192 scripts/migration/migrate_absensi.js \
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
  report: arg('report', './absensi_report.json'),
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
// WAJIB identik dengan ID.user di migrate.js — kalau berbeda, seluruh FK ke
// app_user meleset dan baris ini kehilangan tautannya.
const idUser = (uid) => uuidv5(`user:${uid}`);

const RE_ISO = /^(\d{4})-(\d{2})-(\d{2})$/;
const RE_JAM = /^([01]\d|2[0-3]):([0-5]\d)$/;

// ================================================================= LOAD ===
log('▶ Membaca export …', CFG.file);
const RAW = fs.readFileSync(CFG.file, 'utf8');
if (RAW.includes('more keys')) {
  console.error('FATAL: export terpotong ("N more keys"). Pakai export penuh.');
  process.exit(3);
}
const DB = JSON.parse(RAW);
const node = (n) => (DB && DB[n] && typeof DB[n] === 'object' ? DB[n] : {});

const AB = node('absensi');
if (!realKeys(AB).length) {
  console.error('FATAL: node `absensi` tidak ada / kosong di export ini.');
  process.exit(4);
}

const meta = node('metadata');
const adminUids = new Set(realKeys(meta.admins || {}));
const cabangDikenal = new Set();
for (const uid of adminUids) {
  const c = slugCabang((meta.admins[uid] || {}).cabang);
  if (c) cabangDikenal.add(c);
}
for (const c of realKeys(meta.cabang || {})) cabangDikenal.add(slugCabang(c));

// ============================================================ TRANSFORM ===
const ROWS = [];
let dilewatiCabang = 0;
let userYatim = 0;
let jamTidakValid = 0;

for (const cabRaw of realKeys(AB)) {
  const cab = slugCabang(cabRaw);
  if (!cabangDikenal.has(cab)) {
    // cabang_id NOT NULL + FK — tidak ada cara menyimpannya.
    issue('ABSENSI_CABANG_TIDAK_DIKENAL', `${cabRaw} — seluruh tanggalnya dilewati`);
    dilewatiCabang += realKeys(AB[cabRaw]).length;
    continue;
  }

  for (const tgl of realKeys(AB[cabRaw])) {
    if (!RE_ISO.test(tgl)) { issue('ABSENSI_TANGGAL_TIDAK_VALID', `${cab}/${tgl}`); continue; }

    for (const uid of realKeys(AB[cabRaw][tgl])) {
      const r = AB[cabRaw][tgl][uid];
      if (!r || typeof r !== 'object') continue;

      const uidAsli = str(r.uid) || uid;      // kunci record = UID staf
      const punyaUser = adminUids.has(uidAsli);
      if (!punyaUser) userYatim++;

      // `jam` disimpan apa adanya ("HH:MM"). Yang tidak berbentuk itu
      // dikosongkan, bukan ditebak: jam absen adalah data yang dipakai
      // menilai orang, dan menebaknya lebih buruk daripada mengosongkannya.
      let jam = str(r.jam);
      if (jam && !RE_JAM.test(jam)) { jamTidakValid++; issue('ABSENSI_JAM_TIDAK_VALID', `${cab}/${tgl}/${uidAsli}: "${jam}"`); jam = ''; }

      ROWS.push({
        cabang_id: cab,
        tanggal: tgl,
        user_id: punyaUser ? idUser(uidAsli) : null,   // FK nullable: staf yang keluar
        legacy_uid: uidAsli,
        nama: str(r.nama),
        role: str(r.role),
        cabang_nama: str(r.cabangNama) || cab,
        jam,
        recorded_at: r.timestamp ? new Date(Number(r.timestamp)).toISOString() : null,
      });
    }
  }
}

const tglUrut = ROWS.map((r) => r.tanggal).sort();
log(`\n▶ Ringkasan`);
log(`   baris            : ${ROWS.length}`);
log(`   cabang           : ${new Set(ROWS.map((r) => r.cabang_id)).size}`);
log(`   staf berbeda     : ${new Set(ROWS.map((r) => r.legacy_uid)).size}`);
log(`   rentang tanggal  : ${tglUrut[0] || '-'} … ${tglUrut[tglUrut.length - 1] || '-'}`);
if (userYatim)      log(`   ⚠ user_id NULL (staf tidak terdaftar)  : ${userYatim}`);
if (jamTidakValid)  log(`   ⚠ jam dikosongkan (bentuk tidak valid) : ${jamTidakValid}`);
if (dilewatiCabang) log(`   ⚠ dilewati karena cabang tidak dikenal : ${dilewatiCabang}`);

const kindCount = ISSUES.reduce((m, i) => ((m[i.kind] = (m[i.kind] || 0) + 1), m), {});
fs.writeFileSync(CFG.report, JSON.stringify({
  generatedAt: new Date().toISOString(),
  sourceFile: CFG.file, executed: CFG.execute,
  baris: ROWS.length, userYatim, jamTidakValid, dilewatiCabang,
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

  const cols = ['cabang_id', 'tanggal', 'user_id', 'legacy_uid', 'nama',
                'role', 'cabang_nama', 'jam', 'recorded_at'];
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
        `insert into koperasi.absensi (${cols.map((c) => `"${c}"`).join(',')}) ` +
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
      'select count(*)::int as n, min(tanggal) as awal, max(tanggal) as akhir ' +
      'from koperasi.absensi');
    log(`  di database: ${rows[0].n} baris, ${rows[0].awal} … ${rows[0].akhir}`);
    log(`  dari sumber: ${ROWS.length} baris`);
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
