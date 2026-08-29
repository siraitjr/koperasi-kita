#!/usr/bin/env node
'use strict';
/* =========================================================================
 * MIGRASI STORAGE — backup/storage → Supabase Storage
 * =========================================================================
 * BELUM PERNAH DIJALANKAN. Prasyarat: 027_storage_buckets.sql terpasang.
 *
 * ⚠ HANYA MEMBACA backup lokal. Firebase tidak disentuh sama sekali — file
 *   sudah di-download lebih dulu, jadi skrip ini boleh dijalankan kapan saja,
 *   termasuk SESUDAH 1 September.
 *
 * PEMETAAN PATH (konvensi 003 §2)
 *   faktur_bu/{cabang}/{YYYY-MM}/{pushId}.jpg
 *       → nota-kasir/{cabang}/{YYYY-MM}/{kasir_entry_id}.jpg
 *         (nama asli dipertahankan bila pushId tidak terpetakan)
 *   ktp_images/{adminUid}/{pelangganId}/ktp_{jenis}.jpg
 *       → ktp/{nasabah_id}/{jenis}.jpg
 *   ktp_images_pending/{adminUid}/{pelangganId}/ktp_{jenis}.jpg
 *       → ktp-pending/{nasabah_id}/{pinjaman_id}/{jenis}.jpg
 *   profile_photos/{uid}/profile.jpg
 *       → profil/{user_id}/profile.jpg
 *
 * PEMAKAIAN
 *   node scripts/migration/migrate_storage.js                    # dry-run
 *   node scripts/migration/migrate_storage.js --execute          # unggah
 * ========================================================================= */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const argv = process.argv.slice(2);
const arg = (k, d = null) => {
  const h = argv.find((a) => a === `--${k}` || a.startsWith(`--${k}=`));
  if (!h) return d;
  const e = h.indexOf('=');
  return e === -1 ? true : h.slice(e + 1);
};

const CFG = {
  dir: arg('dir', 'backup/storage'),
  execute: arg('execute', false) === true,
  paralel: Math.max(1, parseInt(arg('paralel', '5'), 10)),
  report: arg('report', './storage_report.json'),
  url: process.env.SUPABASE_URL,
  key: process.env.SUPABASE_SERVICE_ROLE_KEY,
};

if (!CFG.url || !CFG.key) {
  console.error('FATAL: set SUPABASE_URL dan SUPABASE_SERVICE_ROLE_KEY.');
  console.error('       Service role key MEM-BYPASS RLS — jalankan dari mesin');
  console.error('       Anda sendiri, jangan dari CI, jangan taruh di .env.local.');
  process.exit(2);
}
if (!fs.existsSync(CFG.dir)) {
  console.error(`FATAL: folder "${CFG.dir}" tidak ada. Pakai --dir=<path>.`);
  process.exit(2);
}

let createClient;
try { ({ createClient } = require('@supabase/supabase-js')); }
catch {
  console.error('FATAL: @supabase/supabase-js belum terpasang di root repo.');
  console.error('       npm i @supabase/supabase-js   (atau jalankan dari buku-pokok-web/)');
  process.exit(2);
}

const db = createClient(CFG.url, CFG.key, {
  auth: { persistSession: false },
  db: { schema: 'koperasi' },
});

const log = (...a) => console.log(...a);

// --- uuidv5: SALINAN dari migrate.js, harus tetap identik -----------------
// Dipakai menghitung ulang id kasir_entry dari kunci RTDB-nya. Kalau rumusnya
// berbeda sedikit saja, seluruh faktur mendarat dengan nama yang salah.
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
const idKasir = (cab, bulan, kid) => uuidv5(`kasir:${cab}/${bulan}/${kid}`);

// ============================================================== PEMETAAN ===
// Semua diambil dari DATABASE, bukan ditebak dari nama berkas.
//
// ⚠ NAMA KOLOM DIPERIKSA DI SKEMA, dan dua di antaranya berbeda dari yang
//   disebut di brief:
//     · nasabah TIDAK punya `legacy_uid`. Yang ada `legacy_admin_uid` +
//       `legacy_pelanggan_id` (001:193-194). Pasangan keduanya yang menjadi
//       kunci, sama seperti yang dipakai rpc_rekening_koran (020a).
//     · `pinjaman` TIDAK punya kolom legacy sama sekali. Lihat §ktp-pending.
async function muatPemetaan() {
  log('▶ Memuat pemetaan dari database …');

  const semua = async (tabel, kolom) => {
    const out = [];
    for (let dari = 0; ; dari += 1000) {
      const { data, error } = await db.from(tabel).select(kolom)
        .range(dari, dari + 999);
      if (error) throw new Error(`baca ${tabel}: ${error.message}`);
      out.push(...(data || []));
      if (!data || data.length < 1000) break;
    }
    return out;
  };

  const nasabah = await semua('nasabah', 'id, legacy_admin_uid, legacy_pelanggan_id');
  const appUser = await semua('app_user', 'id, legacy_uid');
  const pinjaman = await semua('pinjaman', 'id, nasabah_id, pinjaman_ke');
  const kasir = await semua('kasir_entry', 'id');

  // kunci: "{adminUid}/{pelangganId}" → nasabah_id
  const perNasabah = new Map();
  // cadangan: pelangganId saja → nasabah_id (legacy_pelanggan_id UNIQUE di
  // 001:193, jadi ini tetap tidak ambigu). Menyelamatkan berkas yang adminnya
  // sudah dipindah sejak foto diunggah.
  const perPelanggan = new Map();
  for (const n of nasabah) {
    if (n.legacy_admin_uid && n.legacy_pelanggan_id) {
      perNasabah.set(`${n.legacy_admin_uid}/${n.legacy_pelanggan_id}`, n.id);
    }
    if (n.legacy_pelanggan_id) perPelanggan.set(n.legacy_pelanggan_id, n.id);
  }

  const perUser = new Map();
  for (const u of appUser) if (u.legacy_uid) perUser.set(u.legacy_uid, u.id);

  // Generasi TERTINGGI per nasabah — lihat alasannya di petaKtpPending().
  const pinjamanTerbaru = new Map();
  for (const p of pinjaman) {
    const ada = pinjamanTerbaru.get(p.nasabah_id);
    if (!ada || Number(p.pinjaman_ke) > ada.ke) {
      pinjamanTerbaru.set(p.nasabah_id, { id: p.id, ke: Number(p.pinjaman_ke) });
    }
  }

  const idKasirAda = new Set(kasir.map((k) => k.id));

  log(`   nasabah ${perNasabah.size} · staf ${perUser.size} ` +
      `· pinjaman ${pinjamanTerbaru.size} · kasir_entry ${idKasirAda.size}`);
  return { perNasabah, perPelanggan, perUser, pinjamanTerbaru, idKasirAda };
}

// ================================================================= WALK ===
function telusuri(akar) {
  const out = [];
  const jalan = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) jalan(p);
      else if (e.isFile() && !e.name.startsWith('.')) out.push(p);
    }
  };
  jalan(akar);
  return out;
}

// ============================================================== PEMETAAN ===
const JENIS_KTP = {
  ktp_ktp: 'ktp',
  ktp_nasabah: 'foto_nasabah',
  ktp_suami: 'ktp_suami',
  ktp_istri: 'ktp_istri',
};

/** Segmen path relatif, dinormalisasi ke pemisah '/'. */
const segmen = (rel) => rel.split(path.sep).join('/').split('/');

function petaFakturBu(seg, M) {
  // faktur_bu/{cabang}/{YYYY-MM}/{pushId}.jpg
  if (seg.length !== 4) return { yatim: 'bentuk path faktur_bu tidak dikenali' };
  const [, cabang, bulan, berkas] = seg;
  const pushId = berkas.replace(/\.[^.]+$/, '');
  const id = idKasir(cabang, bulan, pushId);

  // Nama asli dipertahankan bila entri kasirnya tidak ada di database —
  // berkasnya tetap dipindahkan, hanya tidak bisa ditautkan. Membuangnya
  // berarti kehilangan bukti sebuah pengeluaran.
  const nama = M.idKasirAda.has(id) ? `${id}.jpg` : berkas;
  return {
    bucket: 'nota-kasir',
    tujuan: `${cabang}/${bulan}/${nama}`,
    tertaut: M.idKasirAda.has(id),
    catatan: M.idKasirAda.has(id) ? null : `kasir_entry ${id} tidak ada — nama asli dipertahankan`,
  };
}

function petaKtp(seg, M, pending) {
  // ktp_images[_pending]/{adminUid}/{pelangganId}/ktp_{jenis}.jpg
  if (seg.length !== 4) return { yatim: 'bentuk path ktp tidak dikenali' };
  const [, adminUid, pelangganId, berkas] = seg;

  const dasar = berkas.replace(/\.[^.]+$/, '');
  const jenis = JENIS_KTP[dasar] || dasar.replace(/^ktp_/, '');

  const nasabahId = M.perNasabah.get(`${adminUid}/${pelangganId}`)
    || M.perPelanggan.get(pelangganId);
  if (!nasabahId) {
    return { yatim: `nasabah tidak terpetakan: ${adminUid}/${pelangganId}` };
  }

  if (!pending) return { bucket: 'ktp', tujuan: `${nasabahId}/${jenis}.jpg`, tertaut: true };

  // ── ktp-pending: satu-satunya tempat yang butuh keputusan ──────────────
  // 003 §2 menuntut `{nasabah_id}/{pinjaman_id}/{jenis}.jpg`, tetapi path
  // Firebase-nya TIDAK memuat id pinjaman — hanya pelangganId — dan tabel
  // `pinjaman` tidak punya kolom legacy apa pun untuk dicocokkan.
  //
  // Dipakai generasi TERTINGGI nasabah itu. Foto pending adalah lampiran
  // pengajuan top-up, dan pengajuan selalu untuk generasi terbaru. Ini
  // ASUMSI, bukan pemetaan pasti: untuk nasabah yang punya dua pengajuan
  // pending berturut-turut, foto lama bisa menempel ke generasi yang salah.
  // Dicatat di laporan supaya bisa diperiksa, bukan disembunyikan.
  const pj = M.pinjamanTerbaru.get(nasabahId);
  if (!pj) return { yatim: `nasabah ${nasabahId} belum punya baris pinjaman` };
  return {
    bucket: 'ktp-pending',
    tujuan: `${nasabahId}/${pj.id}/${jenis}.jpg`,
    tertaut: true,
    catatan: 'pinjaman_id = generasi tertinggi (asumsi, lihat komentar)',
  };
}

function petaProfil(seg, M) {
  // profile_photos/{uid}/profile.jpg
  if (seg.length !== 3) return { yatim: 'bentuk path profile_photos tidak dikenali' };
  const [, uid, berkas] = seg;
  const userId = M.perUser.get(uid);
  if (!userId) return { yatim: `app_user.legacy_uid tidak terpetakan: ${uid}` };
  const ext = (berkas.match(/\.[^.]+$/) || ['.jpg'])[0];
  return { bucket: 'profil', tujuan: `${userId}/profile${ext}`, tertaut: true };
}

function petakan(rel, M) {
  const seg = segmen(rel);
  switch (seg[0]) {
    case 'faktur_bu':          return petaFakturBu(seg, M);
    case 'ktp_images':         return petaKtp(seg, M, false);
    case 'ktp_images_pending': return petaKtp(seg, M, true);
    case 'profile_photos':     return petaProfil(seg, M);
    default:                   return { yatim: `folder akar tidak dikenali: ${seg[0]}` };
  }
}

const TIPE = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp' };

// ================================================================== MAIN ===
(async () => {
  const M = await muatPemetaan();

  log(`\n▶ Menelusuri ${CFG.dir} …`);
  const berkas = telusuri(CFG.dir);
  log(`   ${berkas.length} berkas ditemukan`);

  const rencana = [];
  const yatim = [];
  const perBucket = {};

  for (const abs of berkas) {
    const rel = path.relative(CFG.dir, abs);
    const hasil = petakan(rel, M);
    if (hasil.yatim) {
      yatim.push({ sumber: rel, alasan: hasil.yatim });
      continue;
    }
    perBucket[hasil.bucket] = (perBucket[hasil.bucket] || 0) + 1;
    rencana.push({ abs, rel, ...hasil });
  }

  log(`\n▶ Rencana`);
  for (const [b, n] of Object.entries(perBucket).sort()) log(`   ${b.padEnd(12)} ${n}`);
  log(`   ${'YATIM'.padEnd(12)} ${yatim.length}`);
  const takTertaut = rencana.filter((r) => r.tertaut === false).length;
  if (takTertaut) log(`   ⚠ faktur tanpa kasir_entry (nama asli) : ${takTertaut}`);

  const tulisLaporan = (ringkas) => {
    fs.writeFileSync(CFG.report, JSON.stringify({
      generatedAt: new Date().toISOString(),
      dir: CFG.dir, executed: CFG.execute,
      ...ringkas,
      perBucket,
      // Peta lengkap sumber→tujuan. Ini juga bahan mentah untuk mengisi
      // `koperasi.dokumen` nanti (003 §4) — belum dikerjakan skrip ini.
      rencana: rencana.map((r) => ({
        sumber: r.rel, bucket: r.bucket, tujuan: r.tujuan,
        tertaut: r.tertaut !== false, catatan: r.catatan || null,
      })),
      yatim,
    }, null, 2));
  };

  if (!CFG.execute) {
    tulisLaporan({ status: 'dry-run' });
    log(`\n✓ DRY-RUN. Tidak ada yang diunggah. Laporan → ${CFG.report}`);
    log('  Periksa daftar YATIM dulu, baru tambahkan --execute.');
    return;
  }

  // ================================================================ UNGGAH ===
  log(`\n▶ Mengunggah (paralel ${CFG.paralel}) …`);
  let naik = 0, lewat = 0, gagal = 0, selesai = 0;
  const galat = [];

  const kerjakan = async (r) => {
    const ext = path.extname(r.tujuan).toLowerCase();
    const { error } = await db.storage.from(r.bucket).upload(
      r.tujuan, fs.readFileSync(r.abs),
      // ⚠ upsert:false WAJIB. Inilah yang membuat skrip ini idempoten dan
      //   bisa dihentikan lalu dilanjutkan: objek yang sudah ada ditolak
      //   dengan 409 dan kita lewati. Dengan upsert:true, jalan-ulang akan
      //   mengunggah ulang 2 GB dan menimpa objek yang sudah benar.
      { contentType: TIPE[ext] || 'application/octet-stream', upsert: false },
    );

    if (!error) { naik++; return; }
    const pesan = String(error.message || '');
    if (/exists|duplicate/i.test(pesan) || error.statusCode === '409') { lewat++; return; }
    gagal++;
    // Satu berkas gagal tidak menghentikan 6.755 lainnya — dicatat, lanjut.
    galat.push({ sumber: r.rel, tujuan: `${r.bucket}/${r.tujuan}`, pesan });
  };

  for (let i = 0; i < rencana.length; i += CFG.paralel) {
    await Promise.all(rencana.slice(i, i + CFG.paralel).map(kerjakan));
    selesai = Math.min(i + CFG.paralel, rencana.length);
    if (selesai % 100 < CFG.paralel || selesai === rencana.length) {
      process.stdout.write(
        `\r   ${selesai}/${rencana.length}  naik=${naik} lewat=${lewat} gagal=${gagal}`);
    }
  }
  process.stdout.write('\n');

  tulisLaporan({ status: 'executed', naik, lewat, gagal, galat: galat.slice(0, 500) });

  log(`\n▶ Selesai`);
  log(`   terunggah : ${naik}`);
  log(`   dilewati  : ${lewat}   (sudah ada — aman, ini sifat idempotennya)`);
  log(`   gagal     : ${gagal}`);
  log(`   yatim     : ${yatim.length}   (tidak diunggah, lihat laporan)`);
  log(`\n   Laporan → ${CFG.report}`);
  if (gagal) {
    log('\n   ⚠ Ada kegagalan. Jalankan ulang perintah yang SAMA — yang sudah');
    log('     naik akan dilewati, jadi hanya sisanya yang dicoba lagi.');
    process.exitCode = 1;
  }
})().catch((e) => {
  console.error('\n✗ FATAL:', e.message);
  process.exit(1);
});
