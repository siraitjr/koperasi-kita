#!/usr/bin/env node
'use strict';
/* =========================================================================
 * INVESTIGASI SELISIH MIGRASI — read-only, tanpa database
 * =========================================================================
 * BELUM PERNAH DIJALANKAN.
 *
 * Menjawab satu pertanyaan yang tidak bisa dijawab oleh angka agregat:
 * "baris mana persisnya yang tidak terbawa, dan berapa nilainya?"
 *
 * validate.js hanya melaporkan SELISIH. Skrip ini membedah selisih itu
 * per-sebab, dengan NOMINAL, langsung dari berkas export — tidak menyentuh
 * Postgres maupun Firebase sama sekali.
 *
 * PEMAKAIAN
 *   node --max-old-space-size=8192 investigate.js --file=/path/export.json
 *   node --max-old-space-size=8192 investigate.js --file=… --keluar=lapor.json
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
const KELUAR = arg('keluar', './investigasi.json');
if (!FILE) { console.error('FATAL: --file wajib.'); process.exit(2); }

// --- helper: SALINAN PERSIS dari migrate.js -------------------------------
const SKIP = new Set(['...', '_guardPinjamanKe', '_guardStatus']);
const keys = (o) => (o && typeof o === 'object' ? Object.keys(o).filter((k) => !SKIP.has(k)) : []);
const str = (v) => (v == null ? '' : String(v));
const rupiah = (v) => {
  if (v == null || v === '') return 0;
  if (typeof v === 'number') return Math.round(v);
  const n = Number(String(v).replace(/[^0-9-]/g, ''));
  return Number.isFinite(n) ? Math.round(n) : 0;
};
const int = (v, d = 0) => { const n = parseInt(v, 10); return Number.isFinite(n) ? n : d; };

const BULAN = {
  jan: 1, feb: 2, mar: 3, apr: 4, mei: 5, jun: 6, jul: 7, agu: 8,
  sep: 9, okt: 10, nov: 11, des: 12, may: 5, aug: 8, oct: 10, dec: 12, agt: 8,
};
function parseTanggal(v) {
  if (v == null || v === '') return null;
  const s = String(v).trim();
  let m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s);
  if (m) return `${m[1]}-${m[2]}-${m[3]}`;
  m = /^(\d{1,2})\s+([A-Za-z]{3,})\s+(\d{4})(?:,?\s+\d{1,2}:\d{2}(?::\d{2})?)?$/.exec(s);
  if (m) {
    const b = BULAN[m[2].slice(0, 3).toLowerCase()];
    if (!b) return null;
    return `${m[3]}-${String(b).padStart(2, '0')}-${String(+m[1]).padStart(2, '0')}`;
  }
  if (/^\d{12,}$/.test(s)) return new Date(+s).toISOString().slice(0, 10);
  const iso = /^(\d{4}-\d{2}-\d{2})T/.exec(s);
  return iso ? iso[1] : null;
}
const STATUS_OK = new Set([
  'menunggu approval', 'disetujui', 'aktif', 'lunas', 'ditolak', 'tidak aktif',
]);
function toIndexedList(v) {
  if (v == null) return [];
  const out = [];
  if (Array.isArray(v)) { v.forEach((x, i) => x != null && out.push([i, x])); return out; }
  if (typeof v !== 'object') return [];
  for (const k of keys(v)) if (/^\d+$/.test(k) && v[k] != null) out.push([+k, v[k]]);
  return out.sort((a, b) => a[0] - b[0]);
}
const slugCabang = (c) => str(c).trim().toLowerCase().replace(/\s+/g, ' ');

// ------------------------------------------------------------------ MUAT --
console.log('▶ Membaca export …');
const RAW = fs.readFileSync(FILE, 'utf8');
if (RAW.includes('more keys')) {
  console.error('FATAL: export terpotong. Pakai export penuh.');
  process.exit(3);
}
const DB = JSON.parse(RAW);
const node = (n) => (DB[n] && typeof DB[n] === 'object' ? DB[n] : {});

const adminUids = new Set(keys(node('metadata').admins || {}));

const hasil = {
  pinjaman: { total: 0, diimpor: 0, dilewati: [] },
  pembayaran: { total: 0, totalRp: 0, diimpor: 0, diimporRp: 0, dilewati: [] },
  biayaAwal: { total: 0, totalRp: 0, dilewati: [] },
  arsipLebihTinggi: [],
  arsipStatusHidup: 0,
};

// ============================================ PINJAMAN & PEMBAYARAN ========
const riwayat = node('riwayat_pinjaman');
for (const a of keys(node('pelanggan'))) {
  for (const pid of keys(node('pelanggan')[a])) {
    const p = node('pelanggan')[a][pid];
    if (!p || typeof p !== 'object') continue;

    const gens = new Map();
    const arsip = (riwayat[a] || {})[pid] || {};
    for (const g of keys(arsip)) if (/^\d+$/.test(g)) gens.set(+g, arsip[g]);
    const keSekarang = int(p.pinjamanKe, 1);
    gens.set(keSekarang, p);
    const maxGen = Math.max(...gens.keys());
    if (maxGen > keSekarang) {
      hasil.arsipLebihTinggi.push({ pelangganId: pid, adminUid: a, keSekarang, maxGen });
    }

    for (const [ke, src] of gens) {
      hasil.pinjaman.total++;
      const isArsip = ke !== keSekarang || ke < maxGen;

      if (!src || typeof src !== 'object') {
        hasil.pinjaman.dilewati.push({
          pelangganId: pid, adminUid: a, pinjamanKe: ke,
          sebab: 'node arsip bukan objek',
          nilai: JSON.stringify(src).slice(0, 60), besarPinjaman: 0,
        });
        continue;
      }
      const stRaw = str(src.status).trim().toLowerCase();
      if (stRaw !== '' && !STATUS_OK.has(stRaw)) {
        hasil.pinjaman.dilewati.push({
          pelangganId: pid, adminUid: a, pinjamanKe: ke,
          sebab: 'status tidak dikenal', status: str(src.status),
          besarPinjaman: rupiah(src.besarPinjaman),
        });
        continue;
      }
      if (isArsip && (stRaw === '' || ['menunggu approval', 'disetujui', 'aktif'].includes(stRaw))) {
        hasil.arsipStatusHidup++;
      }
      hasil.pinjaman.diimpor++;

      // --- pembayaran generasi ini ---
      for (const [i, b] of toIndexedList(src.pembayaranList)) {
        if (!b || typeof b !== 'object') continue;
        const jml = rupiah(b.jumlah);
        const tgl = parseTanggal(b.tanggal);
        hasil.pembayaran.total++; hasil.pembayaran.totalRp += jml;
        if (jml > 0 && tgl) { hasil.pembayaran.diimpor++; hasil.pembayaran.diimporRp += jml; }
        else {
          hasil.pembayaran.dilewati.push({
            pelangganId: pid, adminUid: a, pinjamanKe: ke, indeks: String(i),
            jenis: 'cicilan', jumlah: jml, jumlahMentah: b.jumlah ?? null,
            tanggalMentah: str(b.tanggal), keterangan: str(b.keterangan),
            sebab: jml <= 0 ? 'jumlah tidak sah' : 'tanggal tidak terbaca',
          });
        }
        for (const [j, sb] of toIndexedList(b.subPembayaran)) {
          if (!sb || typeof sb !== 'object') continue;
          const sj = rupiah(sb.jumlah);
          const stg = parseTanggal(sb.tanggal) || tgl;
          hasil.pembayaran.total++; hasil.pembayaran.totalRp += sj;
          if (sj > 0 && stg) { hasil.pembayaran.diimpor++; hasil.pembayaran.diimporRp += sj; }
          else {
            hasil.pembayaran.dilewati.push({
              pelangganId: pid, adminUid: a, pinjamanKe: ke, indeks: `${i}.${j}`,
              jenis: 'tambah_bayar', jumlah: sj, jumlahMentah: sb.jumlah ?? null,
              tanggalMentah: str(sb.tanggal), keterangan: str(sb.keterangan),
              sebab: sj <= 0 ? 'jumlah tidak sah' : 'tanggal tidak terbaca (sub & induk)',
            });
          }
        }
      }
    }
  }
}

// ==================================================== BIAYA AWAL ===========
const BA = node('biaya_awal');
for (const a of keys(BA)) {
  for (const t of keys(BA[a])) {
    const b = BA[a][t] || {};
    const jml = rupiah(b.jumlah);
    hasil.biayaAwal.total++; hasil.biayaAwal.totalRp += jml;
    if (!adminUids.has(a)) {
      hasil.biayaAwal.dilewati.push({
        adminUid: a, tanggal: parseTanggal(b.tanggal) || parseTanggal(t), jumlah: jml,
        sebab: 'admin tidak terdaftar di metadata/admins',
      });
    }
  }
}

// ======================================================== LAPORAN ==========
const rp = (n) => 'Rp ' + n.toLocaleString('id-ID');
const P = hasil.pinjaman, B = hasil.pembayaran, A = hasil.biayaAwal;

console.log('\n════════════════ REKONSILIASI ════════════════');
console.log(`PINJAMAN   total=${P.total}  diimpor=${P.diimpor}  dilewati=${P.dilewati.length}`);
const perSebab = P.dilewati.reduce((m, r) => ((m[r.sebab] = (m[r.sebab] || 0) + 1), m), {});
for (const [k, v] of Object.entries(perSebab)) console.log(`             · ${k}: ${v}`);

console.log(`PEMBAYARAN total=${B.total} (${rp(B.totalRp)})`);
console.log(`           diimpor=${B.diimpor} (${rp(B.diimporRp)})`);
console.log(`           dilewati=${B.dilewati.length} (${rp(B.totalRp - B.diimporRp)})`);
for (const r of B.dilewati.slice(0, 50)) {
  console.log(`             ${r.pelangganId}/${r.pinjamanKe}[${r.indeks}] ${r.jenis.padEnd(12)} ` +
    `${rp(r.jumlah).padStart(14)}  ${r.sebab}  tgl='${r.tanggalMentah}'`);
}

const aHilang = A.dilewati.reduce((x, y) => x + y.jumlah, 0);
console.log(`BIAYA_AWAL total=${A.total} (${rp(A.totalRp)})  dilewati=${A.dilewati.length} (${rp(aHilang)})`);
for (const r of A.dilewati) {
  console.log(`             ${r.adminUid} @ ${r.tanggal}  ${rp(r.jumlah)}  ${r.sebab}`);
}

console.log(`\nARSIP generasi > pinjamanKe : ${hasil.arsipLebihTinggi.length}`);
for (const r of hasil.arsipLebihTinggi.slice(0, 30)) {
  console.log(`             ${r.pelangganId}  node=ke-${r.keSekarang}  arsip tertinggi=ke-${r.maxGen}`);
}
console.log(`ARSIP berstatus hidup (akan diturunkan ke 'Lunas'): ${hasil.arsipStatusHidup}`);

fs.writeFileSync(KELUAR, JSON.stringify(hasil, null, 2));
console.log(`\n✓ Detail lengkap → ${KELUAR}`);
console.log('  Skrip ini READ-ONLY: tidak menyentuh Postgres maupun Firebase.');
