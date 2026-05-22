'use strict';

/* =============================================================================
 * applyStalePencairanFix.js  —  APPLY koreksi tanggal (GATED WRITE)
 * =============================================================================
 *
 *  DRY RUN secara default. Hanya MENULIS ke RTDB bila dijalankan dengan --commit.
 *
 *  Apa yang dilakukan:
 *    Membaca proposals_report.json (yang sudah Anda review/koreksi manual), lalu
 *    untuk tiap baris ber-status "Fix Proposed", menulis proposedDate ke field
 *    yang ditunjuk (tanggalPencairan ATAU tanggalPengajuan) pada record nasabah.
 *
 *  Yang TIDAK disentuh:
 *    - Baris status "True Macet" & "Manual Review" → dilewati total.
 *    - Field lain apapun (pembayaranList, totalPelunasan, status, dll) → TIDAK.
 *      Hanya SATU field tanggal per record yang di-update.
 *
 *  Pagar pengaman (per record, sebelum menulis):
 *    1. proposedField wajib tanggalPencairan / tanggalPengajuan.
 *    2. proposedDate wajib valid "dd MMM yyyy".
 *    3. Record di-fetch live; harus ada.
 *    4. status live masih Aktif & pinjamanKe>1 (kalau berubah → SKIP).
 *    5. Nilai live field == currentDate di report (anti-clobber kalau ada yang
 *       sudah meng-edit di lapangan) → kalau beda, SKIP "drifted".
 *    6. Kalau nilai live sudah == proposedDate → SKIP "already applied" (idempoten).
 *    Setiap aksi (WRITTEN/SKIP/ERROR) dicatat di audit log lokal yang menyimpan
 *    nilai LAMA → reversibel.
 *
 *  CARA PAKAI:
 *    1) DRY RUN dulu (wajib, lihat rencana perubahan):
 *         SERVICE_ACCOUNT=./serviceAccountKey.json node applyStalePencairanFix.js
 *    2) Tinjau apply_log_dryrun_*.json. Bila sudah yakin, EKSEKUSI:
 *         SERVICE_ACCOUNT=./serviceAccountKey.json node applyStalePencairanFix.js --commit
 *       → menghasilkan apply_log_commit_*.json (menyimpan nilai LAMA tiap write).
 *
 *  ROLLBACK (undo) — balikkan semua write ke nilai lama dari log commit:
 *    a) DRY RUN rollback (lihat apa yang akan dibalikkan):
 *         SERVICE_ACCOUNT=./serviceAccountKey.json \
 *           node applyStalePencairanFix.js --rollback apply_log_commit_XXXX.json
 *    b) EKSEKUSI rollback:
 *         SERVICE_ACCOUNT=./serviceAccountKey.json \
 *           node applyStalePencairanFix.js --rollback apply_log_commit_XXXX.json --commit
 *
 *  Opsi env:
 *    SERVICE_ACCOUNT  path service account (default ./serviceAccountKey.json)
 *    DATABASE_URL     URL RTDB (default project asia-southeast1)
 *    IN               path proposals_report.json (default ./proposals_report.json)
 *    APPLY_STATUSES   status yang diproses, pisah '|' (default "Fix Proposed")
 * ===========================================================================*/

const path = require('path');
const fs = require('fs');
const admin = require('firebase-admin');

const SERVICE_ACCOUNT = process.env.SERVICE_ACCOUNT || './serviceAccountKey.json';
const DATABASE_URL = process.env.DATABASE_URL ||
  'https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app';
const IN = process.env.IN || './proposals_report.json';
const COMMIT = process.argv.includes('--commit');
const APPLY_STATUSES = (process.env.APPLY_STATUSES || 'Fix Proposed').split('|');
// --rollback <apply_log_commit_*.json>  → balikkan field ke nilai LAMA dari log.
const ROLLBACK_IDX = process.argv.indexOf('--rollback');
const ROLLBACK_FILE = ROLLBACK_IDX >= 0 ? process.argv[ROLLBACK_IDX + 1] : null;

admin.initializeApp({
  credential: admin.credential.cert(require(path.resolve(SERVICE_ACCOUNT))),
  databaseURL: DATABASE_URL,
});
const db = admin.database();

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
const VALID_FIELDS = new Set(['tanggalPencairan', 'tanggalPengajuan']);
const isAktif = (s) => { const x = (s || '').toLowerCase(); return x === 'aktif' || x === 'active'; };

async function runApply() {
  const report = JSON.parse(fs.readFileSync(path.resolve(IN), 'utf8'));
  const rows = [];
  (report.admins || []).forEach((a) => (a.nasabah || []).forEach((n) => {
    if (APPLY_STATUSES.includes(n.status)) rows.push({ ...n, adminUid: a.adminUid, adminEmail: a.adminEmail });
  }));

  console.log('============================================================');
  console.log(`  MODE   : ${COMMIT ? '🔴 COMMIT (MENULIS ke RTDB)' : '🟢 DRY RUN (tidak menulis)'}`);
  console.log(`  INPUT  : ${path.resolve(IN)}`);
  console.log(`  STATUS : ${APPLY_STATUSES.join(', ')}`);
  console.log(`  TARGET : ${rows.length} record kandidat`);
  console.log('============================================================\n');

  const audit = [];
  let written = 0, skipped = 0, errors = 0;

  for (const n of rows) {
    const field = n.proposedField;
    const base = { path: n.path, nama: n.nama, field, oldFromReport: n.currentDate, newProposed: n.proposedDate };
    try {
      if (!VALID_FIELDS.has(field)) { skipped++; audit.push({ ...base, action: 'SKIP', reason: 'field tidak valid' }); continue; }
      if (!parseTgl(n.proposedDate)) { skipped++; audit.push({ ...base, action: 'SKIP', reason: 'proposedDate tidak valid' }); continue; }
      const m = /^pelanggan\/([^/]+)\/([^/]+)$/.exec(n.path || '');
      if (!m) { skipped++; audit.push({ ...base, action: 'SKIP', reason: 'path tidak valid' }); continue; }
      const [, adminUid, pid] = m;
      const ref = db.ref(`pelanggan/${adminUid}/${pid}`);
      const live = (await ref.once('value')).val();
      if (!live) { skipped++; audit.push({ ...base, action: 'SKIP', reason: 'record tidak ditemukan' }); continue; }

      const liveVal = live[field] || '';
      if (!isAktif(live.status)) { skipped++; audit.push({ ...base, liveValue: liveVal, action: 'SKIP', reason: `status berubah → "${live.status}"` }); continue; }
      if ((live.pinjamanKe || 1) <= 1) { skipped++; audit.push({ ...base, liveValue: liveVal, action: 'SKIP', reason: 'pinjamanKe<=1 sekarang' }); continue; }
      if (liveVal === n.proposedDate) { skipped++; audit.push({ ...base, liveValue: liveVal, action: 'SKIP', reason: 'sudah ter-apply (idempoten)' }); continue; }
      if (liveVal !== n.currentDate) { skipped++; audit.push({ ...base, liveValue: liveVal, action: 'SKIP', reason: 'nilai live ≠ report (drift) — cek manual' }); continue; }

      if (COMMIT) {
        await ref.update({ [field]: n.proposedDate });   // HANYA satu field
        written++; audit.push({ ...base, liveValue: liveVal, action: 'WRITTEN', ts: new Date().toISOString() });
        console.log(`  ✔ ${n.nama}: ${field} "${liveVal}" → "${n.proposedDate}"`);
      } else {
        written++; audit.push({ ...base, liveValue: liveVal, action: 'WOULD-WRITE' });
        console.log(`  [dry] ${n.nama}: ${field} "${liveVal}" → "${n.proposedDate}"`);
      }
    } catch (e) {
      errors++; audit.push({ ...base, action: 'ERROR', reason: String((e && e.message) || e) });
      console.error(`  ✖ ${n.nama}: ${(e && e.message) || e}`);
    }
  }

  const logName = `apply_log_${COMMIT ? 'commit' : 'dryrun'}_${Date.now()}.json`;
  fs.writeFileSync(path.resolve(logName), JSON.stringify({
    mode: COMMIT ? 'commit' : 'dryrun',
    generatedAt: new Date().toISOString(),
    input: path.resolve(IN),
    applyStatuses: APPLY_STATUSES,
    counts: { candidates: rows.length, [COMMIT ? 'written' : 'wouldWrite']: written, skipped, errors },
    audit,
  }, null, 2));

  console.log(`\n${COMMIT ? 'WRITTEN' : 'WOULD-WRITE'}: ${written} | SKIPPED: ${skipped} | ERRORS: ${errors}`);
  console.log(`Audit log (menyimpan nilai lama, reversibel): ${logName}`);
  if (!COMMIT) console.log('\n🟢 DRY RUN — tidak ada perubahan DB. Jalankan ulang dengan --commit untuk eksekusi.');
  return errors ? 1 : 0;
}

// ---------------------------------------------------------------------------
// ROLLBACK: balikkan setiap field yang sebelumnya WRITTEN ke nilai LAMA-nya.
// Membaca apply_log_commit_*.json. Guard: hanya revert kalau nilai live SAAT INI
// masih == nilai yang dulu kita tulis (newProposed) — kalau sudah berubah, SKIP.
// DRY RUN default; menulis hanya dengan --commit.
// ---------------------------------------------------------------------------
async function runRollback() {
  const log = JSON.parse(fs.readFileSync(path.resolve(ROLLBACK_FILE), 'utf8'));
  const written = (log.audit || []).filter((a) => a.action === 'WRITTEN');

  console.log('============================================================');
  console.log(`  MODE     : ${COMMIT ? '🔴 ROLLBACK COMMIT (MENULIS)' : '🟢 ROLLBACK DRY RUN (tidak menulis)'}`);
  console.log(`  LOG      : ${path.resolve(ROLLBACK_FILE)}`);
  console.log(`  TO REVERT: ${written.length} record ber-aksi WRITTEN`);
  console.log('============================================================\n');

  const audit = [];
  let reverted = 0, skipped = 0, errors = 0;

  for (const a of written) {
    const field = a.field;
    const restoreTo = a.oldFromReport;            // nilai sebelum write
    const wroteValue = a.newProposed;             // nilai yang dulu kita set
    const base = { path: a.path, nama: a.nama, field, revertFrom: wroteValue, revertTo: restoreTo };
    try {
      if (!VALID_FIELDS.has(field)) { skipped++; audit.push({ ...base, action: 'SKIP', reason: 'field tidak valid' }); continue; }
      if (restoreTo === undefined || restoreTo === null) { skipped++; audit.push({ ...base, action: 'SKIP', reason: 'nilai lama tak tersedia di log' }); continue; }
      const m = /^pelanggan\/([^/]+)\/([^/]+)$/.exec(a.path || '');
      if (!m) { skipped++; audit.push({ ...base, action: 'SKIP', reason: 'path tidak valid' }); continue; }
      const [, adminUid, pid] = m;
      const ref = db.ref(`pelanggan/${adminUid}/${pid}`);
      const live = (await ref.once('value')).val();
      if (!live) { skipped++; audit.push({ ...base, action: 'SKIP', reason: 'record tidak ditemukan' }); continue; }

      const liveVal = live[field] || '';
      if (liveVal === restoreTo) { skipped++; audit.push({ ...base, liveValue: liveVal, action: 'SKIP', reason: 'sudah di nilai lama (idempoten)' }); continue; }
      if (liveVal !== wroteValue) { skipped++; audit.push({ ...base, liveValue: liveVal, action: 'SKIP', reason: 'nilai live ≠ yang kita tulis (berubah sejak apply) — cek manual' }); continue; }

      if (COMMIT) {
        await ref.update({ [field]: restoreTo });   // HANYA satu field
        reverted++; audit.push({ ...base, liveValue: liveVal, action: 'REVERTED', ts: new Date().toISOString() });
        console.log(`  ↩ ${a.nama}: ${field} "${liveVal}" → "${restoreTo}"`);
      } else {
        reverted++; audit.push({ ...base, liveValue: liveVal, action: 'WOULD-REVERT' });
        console.log(`  [dry] ${a.nama}: ${field} "${liveVal}" → "${restoreTo}"`);
      }
    } catch (e) {
      errors++; audit.push({ ...base, action: 'ERROR', reason: String((e && e.message) || e) });
      console.error(`  ✖ ${a.nama}: ${(e && e.message) || e}`);
    }
  }

  const logName = `rollback_log_${COMMIT ? 'commit' : 'dryrun'}_${Date.now()}.json`;
  fs.writeFileSync(path.resolve(logName), JSON.stringify({
    mode: COMMIT ? 'commit' : 'dryrun',
    generatedAt: new Date().toISOString(),
    sourceLog: path.resolve(ROLLBACK_FILE),
    counts: { candidates: written.length, [COMMIT ? 'reverted' : 'wouldRevert']: reverted, skipped, errors },
    audit,
  }, null, 2));

  console.log(`\n${COMMIT ? 'REVERTED' : 'WOULD-REVERT'}: ${reverted} | SKIPPED: ${skipped} | ERRORS: ${errors}`);
  console.log(`Rollback log: ${logName}`);
  if (!COMMIT) console.log('\n🟢 ROLLBACK DRY RUN — tidak ada perubahan DB. Tambahkan --commit untuk eksekusi.');
  return errors ? 1 : 0;
}

(async function main() {
  let code = 0;
  try {
    code = ROLLBACK_FILE ? await runRollback() : await runApply();
  } catch (err) {
    console.error('[FATAL]', err);
    code = 1;
  }
  await admin.app().delete();
  process.exit(code);
})();
