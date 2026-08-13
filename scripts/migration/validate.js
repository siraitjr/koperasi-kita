#!/usr/bin/env node
'use strict';
/* =========================================================================
 * VALIDASI PASCA-MIGRASI — bandingkan export Firebase vs isi Postgres
 * =========================================================================
 * BELUM PERNAH DIJALANKAN.
 *
 * Prinsip: yang menentukan migrasi berhasil BUKAN "skrip selesai tanpa error",
 * melainkan angka di kedua sisi cocok. Skrip ini menghitung ulang agregat dari
 * berkas JSON sumber, menanyakan hal yang sama ke Postgres, lalu membandingkan.
 *
 * Keluar dengan kode 1 bila ADA SATU SAJA pemeriksaan yang gagal, supaya bisa
 * dipakai sebagai gerbang di runbook cutover.
 *
 * PEMAKAIAN
 *   node --max-old-space-size=8192 validate.js \
 *        --file=/path/export.json \
 *        --dsn="postgresql://postgres:PASS@db.xxx.supabase.co:5432/postgres"
 * ========================================================================= */

const fs = require('fs');

const argv = process.argv.slice(2);
const arg = (k, d = null) => {
  const h = argv.find((a) => a === `--${k}` || a.startsWith(`--${k}=`));
  if (!h) return d;
  const e = h.indexOf('=');
  return e === -1 ? true : h.slice(e + 1);
};

const FILE = arg('file');
const DSN = arg('dsn', process.env.SUPABASE_DSN);
const TOL = parseInt(arg('tolerance', '0'), 10); // toleransi selisih baris
if (!FILE || !DSN) {
  console.error('FATAL: --file dan --dsn wajib.');
  process.exit(2);
}

const SKIP = new Set(['...', '_guardPinjamanKe', '_guardStatus']);
const keys = (o) => (o && typeof o === 'object' ? Object.keys(o).filter((k) => !SKIP.has(k)) : []);
const rupiah = (v) => {
  if (v == null || v === '') return 0;
  if (typeof v === 'number') return Math.round(v);
  const n = Number(String(v).replace(/[^0-9-]/g, ''));
  return Number.isFinite(n) ? Math.round(n) : 0;
};
/* Normalisasi NIK — WAJIB identik dengan nikBersih() di migrate.js. Kalau
 * berbeda, hitungan sumber dan hitungan Postgres tidak bisa dibandingkan dan
 * validasi ini justru menghasilkan alarm palsu. */
const NIK_DUMMY = new Set([
  '0000000000000000', '0000000000000010', '1111111111111111', '1234567890123456',
]);
function nikBersih(v) {
  const t = String(v == null ? '' : v).trim();
  if (!/^\d{16}$/.test(t)) return null;
  if (NIK_DUMMY.has(t)) return null;
  if (t.startsWith('00')) return null;           // kode provinsi 11–94
  if (/^(\d)\1{15}$/.test(t)) return null;
  return t;
}

function toIndexedList(v) {
  if (v == null) return [];
  const out = [];
  if (Array.isArray(v)) { v.forEach((x, i) => x != null && out.push([i, x])); return out; }
  if (typeof v !== 'object') return [];
  for (const k of keys(v)) if (/^\d+$/.test(k) && v[k] != null) out.push([+k, v[k]]);
  return out.sort((a, b) => a[0] - b[0]);
}

// ------------------------------------------------------------ HITUNG SUMBER
console.log('▶ Membaca export …');
const RAW = fs.readFileSync(FILE, 'utf8');
if (RAW.includes('more keys')) {
  console.error('FATAL: export terpotong (penanda "more keys"). Pakai export penuh.');
  process.exit(3);
}
const DB = JSON.parse(RAW);
const node = (n) => (DB[n] && typeof DB[n] === 'object' ? DB[n] : {});

const src = {
  admins: 0, cabang: new Set(), nasabah: 0, pinjaman: 0,
  bayar: 0, bayarSum: 0, jadwal: 0, pengajuan: 0, jurnal: 0, jurnalSum: 0,
  kasir: 0, kasirSum: 0, statusCount: {}, nikUnik: new Set(), nikDuplikat: [],
  histori: 0, biayaAwal: 0, biayaAwalSum: 0, ditolak: 0,
  koreksi: 0, koreksiSum: 0, statusKhusus: 0,
  /* Kunci "admin/pelanggan/generasi" yang benar-benar jadi baris pinjaman.
   * Dipakai menghitung pengajuan dengan aturan yang SAMA seperti migrate.js
   * (yang melewati pengajuan tanpa pinjaman induk) — kalau tidak, selisihnya
   * selalu dilaporkan sebagai kegagalan padahal memang disengaja. */
  pinjamanKeys: new Set(),
};

for (const uid of keys(node('metadata').admins || {})) {
  src.admins++;
  const c = (node('metadata').admins[uid] || {}).cabang;
  if (c) src.cabang.add(String(c).trim().toLowerCase().replace(/\s+/g, ' '));
}

const riwayat = node('riwayat_pinjaman');
for (const a of keys(node('pelanggan'))) {
  for (const pid of keys(node('pelanggan')[a])) {
    const p = node('pelanggan')[a][pid];
    if (!p || typeof p !== 'object') continue;
    src.nasabah++;

    const st = String(p.status || '').trim();
    src.statusCount[st] = (src.statusCount[st] || 0) + 1;

    const nik = nikBersih(p.nik);
    if (nik) {
      if (src.nikUnik.has(nik)) src.nikDuplikat.push(nik);
      src.nikUnik.add(nik);
    }

    const gens = new Set();
    for (const g of keys((riwayat[a] || {})[pid] || {})) if (/^\d+$/.test(g)) gens.add(+g);
    gens.add(parseInt(p.pinjamanKe, 10) || 1);
    src.pinjaman += gens.size;
    for (const g of gens) src.pinjamanKeys.add(`${a}/${pid}/${g}`);

    // Pembayaran dihitung dari SEMUA generasi (arsip + berjalan), sama seperti
    // migrate.js — kalau tidak, angkanya tidak bisa dibandingkan.
    const semua = [];
    for (const g of keys((riwayat[a] || {})[pid] || {})) if (/^\d+$/.test(g)) semua.push((riwayat[a] || {})[pid][g]);
    semua.push(p);
    for (const rec of semua) {
      if (!rec || typeof rec !== 'object') continue;
      for (const [, b] of toIndexedList(rec.pembayaranList)) {
        if (!b || typeof b !== 'object') continue;
        const j = rupiah(b.jumlah);
        if (j > 0 && b.tanggal) { src.bayar++; src.bayarSum += j; }
        for (const [, s] of toIndexedList(b.subPembayaran)) {
          if (!s || typeof s !== 'object') continue;
          const sj = rupiah(s.jumlah);
          if (sj > 0) { src.bayar++; src.bayarSum += sj; }
        }
      }
      for (const [, s] of toIndexedList(rec.hasilSimulasiCicilan)) if (s && s.tanggal) src.jadwal++;
    }
  }
}

for (const c of keys(node('pengajuan_approval'))) {
  for (const gid of keys(node('pengajuan_approval')[c])) {
    const g = node('pengajuan_approval')[c][gid] || {};
    const a = String(g.adminUid || '');
    const pid = String(g.pelangganId || '');
    const ke = parseInt(g.pinjamanKe, 10) || 1;
    if (!a || !pid) continue;                               // tanpa referensi
    if (!src.pinjamanKeys.has(`${a}/${pid}/${ke}`)) continue; // yatim → dilewati
    src.pengajuan++;
  }
}

// --- data historis -----------------------------------------------------------
// pinjamanHistory dihitung HANYA untuk nasabah yang induknya ada, sama seperti
// migrate.js — kalau tidak, selisihnya akan selalu dilaporkan sebagai gagal.
const PH = node('pinjamanHistory');
for (const a of keys(PH)) {
  for (const pid of keys(PH[a])) {
    const punyaInduk = !!((node('pelanggan')[a] || {})[pid]);
    if (!punyaInduk) continue;
    src.histori += keys(PH[a][pid]).length;
  }
}
const BA = node('biaya_awal');
for (const a of keys(BA)) {
  for (const t of keys(BA[a])) {
    src.biayaAwal++;
    src.biayaAwalSum += rupiah((BA[a][t] || {}).jumlah);
  }
}
for (const a of keys(node('pelanggan_ditolak'))) src.ditolak += keys(node('pelanggan_ditolak')[a]).length;

// koreksi_storting/{cabang}/{admin}/{YYYY-MM}; hanya periode ber-format valid
// yang dihitung, sama seperti migrate.js.
const KS = node('koreksi_storting');
for (const c of keys(KS)) {
  for (const a of keys(KS[c])) {
    for (const b of keys(KS[c][a])) {
      if (!/^\d{4}-\d{2}$/.test(b)) continue;
      const k = KS[c][a][b] || {};
      src.koreksi++;
      src.koreksiSum += rupiah(k.cm) + rupiah(k.l1) + rupiah(k.mb) + rupiah(k.ml);
    }
  }
}
for (const c of keys(node('pelanggan_status_khusus'))) src.statusKhusus += keys(node('pelanggan_status_khusus')[c]).length;


for (const c of keys(node('jurnal_transaksi')))
  for (const b of keys(node('jurnal_transaksi')[c]))
    for (const id of keys(node('jurnal_transaksi')[c][b])) {
      src.jurnal++; src.jurnalSum += rupiah(node('jurnal_transaksi')[c][b][id].jumlah);
    }
for (const c of keys(node('kasir_entries')))
  for (const b of keys(node('kasir_entries')[c]))
    for (const id of keys(node('kasir_entries')[c][b])) {
      src.kasir++; src.kasirSum += rupiah(node('kasir_entries')[c][b][id].jumlah);
    }

console.log('  sumber:', JSON.stringify({
  nasabah: src.nasabah, pinjaman: src.pinjaman, bayar: src.bayar,
  bayarSum: src.bayarSum, jurnal: src.jurnal, kasir: src.kasir,
}));

// ------------------------------------------------------------------ PERIKSA
let Client;
try { ({ Client } = require('pg')); }
catch { console.error('FATAL: `pg` belum terpasang. npm i pg'); process.exit(4); }

const hasil = [];
const cek = (nama, ok, detail) => {
  hasil.push({ nama, ok, detail });
  console.log(`  ${ok ? '✓' : '✗'} ${nama}${detail ? ' — ' + detail : ''}`);
};

(async () => {
  const c = new Client({ connectionString: DSN });
  await c.connect();
  const one = async (q, p) => (await c.query(q, p)).rows[0];
  const all = async (q, p) => (await c.query(q, p)).rows;

  console.log('\n▶ A. Jumlah baris');
  const pairs = [
    ['nasabah', 'koperasi.nasabah', src.nasabah],
    ['pinjaman', 'koperasi.pinjaman', src.pinjaman],
    ['pembayaran', 'koperasi.pembayaran', src.bayar],
    ['jadwal_cicilan', 'koperasi.jadwal_cicilan', src.jadwal],
    ['pengajuan', 'koperasi.pengajuan', src.pengajuan],
    ['jurnal_transaksi', 'koperasi.jurnal_transaksi', src.jurnal],
    ['kasir_entry', 'koperasi.kasir_entry', src.kasir],
    ['app_user', 'koperasi.app_user', src.admins],
    ['pinjaman_history', 'koperasi.pinjaman_history', src.histori],
    ['biaya_awal', 'koperasi.biaya_awal', src.biayaAwal],
    ['pelanggan_ditolak', 'koperasi.pelanggan_ditolak', src.ditolak],
    ['koreksi_storting', 'koperasi.koreksi_storting', src.koreksi],
    ['pelanggan_status_khusus', 'koperasi.pelanggan_status_khusus', src.statusKhusus],
  ];
  for (const [nama, tabel, expected] of pairs) {
    const { count } = await one(`select count(*)::int as count from ${tabel}`);
    const delta = count - expected;
    cek(`${nama}: db=${count} sumber=${expected}`, Math.abs(delta) <= TOL, delta === 0 ? '' : `selisih ${delta > 0 ? '+' : ''}${delta}`);
  }

  console.log('\n▶ B. Nilai uang (uang tidak boleh berubah nominalnya)');
  const b1 = await one('select coalesce(sum(jumlah),0)::bigint as s from koperasi.pembayaran');
  cek(`total pembayaran: db=${b1.s} sumber=${src.bayarSum}`, String(b1.s) === String(src.bayarSum),
    String(b1.s) === String(src.bayarSum) ? '' : `selisih ${Number(b1.s) - src.bayarSum}`);
  const j1 = await one('select coalesce(sum(jumlah),0)::bigint as s from koperasi.jurnal_transaksi');
  cek(`total jurnal: db=${j1.s} sumber=${src.jurnalSum}`, String(j1.s) === String(src.jurnalSum));
  const k1 = await one('select coalesce(sum(nominal),0)::bigint as s from koperasi.kasir_entry');
  cek(`total kasir: db=${k1.s} sumber=${src.kasirSum}`, String(k1.s) === String(src.kasirSum));
  const ba = await one('select coalesce(sum(jumlah),0)::bigint as s from koperasi.biaya_awal');
  cek(`total biaya_awal: db=${ba.s} sumber=${src.biayaAwalSum}`, String(ba.s) === String(src.biayaAwalSum));

  // Koreksi storting mengubah angka pembukuan: totalnya harus sama persis,
  // kalau tidak laporan bulanan pasca-cutover akan berbeda dari yang sudah
  // pernah dicetak.
  const ks = await one(
    'select coalesce(sum(cm+l1+mb+ml),0)::bigint as s from koperasi.koreksi_storting');
  cek(`total koreksi storting: db=${ks.s} sumber=${src.koreksiSum}`,
    String(ks.s) === String(src.koreksiSum),
    String(ks.s) === String(src.koreksiSum) ? '' : `selisih ${Number(ks.s) - src.koreksiSum}`);

  // Snapshot penolakan wajib utuh — kalau kosong, bukti auditnya hilang.
  const snapKosong = await one(
    `select count(*)::int as n from koperasi.pelanggan_ditolak
      where snapshot is null or snapshot = '{}'::jsonb`);
  cek('snapshot pelanggan_ditolak terisi', snapKosong.n === 0,
    snapKosong.n ? `${snapKosong.n} baris tanpa snapshot` : '');

  console.log('\n▶ C. Integritas referensial');
  const yatim = await all(`
    select 'pinjaman→nasabah' as rel, count(*)::int as n from koperasi.pinjaman p
      left join koperasi.nasabah n on n.id = p.nasabah_id where n.id is null
    union all select 'pembayaran→pinjaman', count(*)::int from koperasi.pembayaran b
      left join koperasi.pinjaman p on p.id = b.pinjaman_id where p.id is null
    union all select 'nasabah→app_user', count(*)::int from koperasi.nasabah n
      left join koperasi.app_user u on u.id = n.admin_id where u.id is null
    union all select 'nasabah→cabang', count(*)::int from koperasi.nasabah n
      left join koperasi.cabang cb on cb.id = n.cabang_id where cb.id is null`);
  for (const r of yatim) cek(`tanpa induk ${r.rel}`, r.n === 0, r.n ? `${r.n} baris` : '');

  console.log('\n▶ D. Invarian bisnis');
  /* Batas 1 pinjaman aktif per nasabah DILONGGARKAN (001a §10) untuk
   * mengakomodasi data legacy, jadi ini BUKAN kegagalan — hanya dilaporkan.
   *
   * Yang tetap diperiksa keras: tidak boleh ada generasi ARSIP yang
   * berstatus hidup. Itu bukan keadaan legacy melainkan cacat transformasi
   * (snapshot arsip tanpa `status` sempat jatuh ke 'Menunggu Approval'), dan
   * kalau lolos akan memunculkan pengajuan hantu di layar approval. */
  const dup = await one(`select count(*)::int as n from (
      select nasabah_id from koperasi.pinjaman
       where status in ('Menunggu Approval','Disetujui','Aktif')
       group by nasabah_id having count(*) > 1) t`);
  console.log(`  · nasabah dengan >1 pinjaman hidup: ${dup.n} (dilonggarkan, bukan galat)`);

  const arsipHidup = await one(`
    select count(*)::int as n
      from koperasi.pinjaman p
     where p.status in ('Menunggu Approval','Disetujui','Aktif')
       and exists (select 1 from koperasi.pinjaman q
                    where q.nasabah_id = p.nasabah_id
                      and q.pinjaman_ke > p.pinjaman_ke)`);
  cek('tidak ada generasi lama yang masih berstatus hidup', arsipHidup.n === 0,
    arsipHidup.n ? `${arsipHidup.n} baris — akan tampil sebagai pengajuan hantu` : '');

  /* NIK duplikat BUKAN kegagalan sejak 12 Agu 2026: constraint UNIQUE
   * diturunkan jadi index biasa karena 74 orang memang terdaftar di dua
   * resort (001a §9). Yang diperiksa sekarang bukan "harus nol", melainkan
   * "jumlahnya sama dengan sumber" — itulah yang membuktikan tidak ada baris
   * yang diam-diam hilang saat impor. */
  const nikDup = await one(`select count(*)::int as n from (
      select nik from koperasi.nasabah where nik is not null
       group by nik having count(*) > 1) t`);
  const sumberDupUnik = new Set(src.nikDuplikat).size;
  cek(`NIK duplikat: db=${nikDup.n} sumber=${sumberDupUnik} (diketahui, perlu cleanup)`,
    nikDup.n === sumberDupUnik,
    nikDup.n === sumberDupUnik ? '' : 'SELISIH → ada baris nasabah yang hilang saat impor');

  /* pengajuan.pinjaman_id sengaja TIDAK unik lagi (001a §11) — duplikat
   * approval adalah keadaan legacy yang diterima. Dilaporkan saja. */
  const pengGanda = await one(`select count(*)::int as n from (
      select pinjaman_id from koperasi.pengajuan
       group by pinjaman_id having count(*) > 1) t`);
  console.log(`  · pinjaman dengan >1 pengajuan: ${pengGanda.n} (dilonggarkan, bukan galat)`);

  const opDup = await one(`select count(*)::int as n from (
      select client_op_id from koperasi.pembayaran group by client_op_id having count(*)>1) t`);
  cek('client_op_id unik', opDup.n === 0);

  // Status khusus yang kehilangan tautan nasabah masih sah (nasabahnya bisa
  // sudah dihapus), tapi jumlahnya perlu terlihat — kalau hampir semuanya
  // null, kemungkinan besar pemetaan adminUid→nasabah yang salah.
  const skNull = await one(
    `select count(*)::int as n from koperasi.pelanggan_status_khusus where nasabah_id is null`);
  const skAll = await one('select count(*)::int as n from koperasi.pelanggan_status_khusus');
  cek(`status_khusus tertaut nasabah (${skAll.n - skNull.n}/${skAll.n})`,
    skAll.n === 0 || skNull.n <= skAll.n / 2,
    skNull.n ? `${skNull.n} tanpa tautan` : '');

  const neg = await one(`select count(*)::int as n from koperasi.pembayaran where jumlah <= 0`);
  cek('tidak ada pembayaran <= 0', neg.n === 0);

  console.log('\n▶ E. Trigger sudah diaktifkan kembali');
  const trg = await all(`
    select t.tgname, t.tgenabled from pg_trigger t
     join pg_class c on c.oid = t.tgrelid
     join pg_namespace ns on ns.oid = c.relnamespace
    where ns.nspname='koperasi' and not t.tgisinternal`);
  const mati = trg.filter((t) => t.tgenabled === 'D').map((t) => t.tgname);
  cek('semua trigger aktif', mati.length === 0, mati.length ? `MATI: ${mati.join(', ')}` : '');

  console.log('\n▶ F. RLS aktif di semua tabel');
  const rls = await all(`
    select c.relname, c.relrowsecurity, c.relforcerowsecurity
      from pg_class c join pg_namespace n on n.oid=c.relnamespace
     where n.nspname='koperasi' and c.relkind='r'`);
  const tanpaRls = rls.filter((r) => !r.relrowsecurity).map((r) => r.relname);
  cek('RLS enabled', tanpaRls.length === 0, tanpaRls.length ? `tanpa RLS: ${tanpaRls.join(', ')}` : '');
  const tanpaForce = rls.filter((r) => r.relrowsecurity && !r.relforcerowsecurity).map((r) => r.relname);
  cek('RLS forced', tanpaForce.length === 0, tanpaForce.length ? `tanpa FORCE: ${tanpaForce.join(', ')}` : '');

  console.log('\n▶ G. Sebaran status (bandingkan manual dengan sumber)');
  const sb = await all('select status::text, count(*)::int as n from koperasi.pinjaman group by 1 order by 2 desc');
  console.log('   Postgres:', JSON.stringify(Object.fromEntries(sb.map((r) => [r.status, r.n]))));
  console.log('   Sumber  :', JSON.stringify(src.statusCount));

  await c.end();

  const gagal = hasil.filter((h) => !h.ok);
  console.log(`\n${'='.repeat(60)}`);
  if (gagal.length === 0) {
    console.log(`✓ SEMUA ${hasil.length} PEMERIKSAAN LULUS.`);
    console.log('  Catatan: ini membuktikan jumlah & nilai cocok, BUKAN bahwa');
    console.log('  aturan akses sudah benar. Uji RLS per peran secara terpisah.');
  } else {
    console.log(`✗ ${gagal.length} DARI ${hasil.length} PEMERIKSAAN GAGAL:`);
    gagal.forEach((g) => console.log('   - ' + g.nama + (g.detail ? ' — ' + g.detail : '')));
    console.log('\n  JANGAN cutover. Lihat rollback_plan.md.');
    process.exitCode = 1;
  }
})().catch((e) => { console.error('FATAL:', e.message); process.exit(1); });
