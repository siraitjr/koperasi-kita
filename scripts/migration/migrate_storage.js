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
 *   ktp_images/{adminUid}/{pushId}/ktp_{jenis}.jpg
 *       → ktp/{nasabah_id}/{jenis}.jpg
 *         pushId dicari BERLAPIS — lihat cariNasabah(). Ia tidak selalu
 *         nasabah.legacy_pelanggan_id; sebagian ada di pinjaman_history
 *         (push id generasi pinjaman) atau pelanggan_status_khusus.
 *   ktp_images_pending/{adminUid}/{pelangganId}/ktp_{jenis}.jpg
 *       → ktp-pending/{nasabah_id}/{pinjaman_id}/{jenis}.jpg
 *   profile_photos/{uid}/profile.jpg
 *       → profil/{user_id}/profile.jpg
 *
 *   YANG TIDAK PUNYA JEMBATAN PEMETAAN → bucket arsip (028), struktur asli
 *   dipertahankan supaya bisa ditelusuri manual:
 *       → ktp-yatim/{asal}/{adminUid}/{pushId}/{filename}
 *       → profil-yatim/{uid}/{filename}
 *     Dipindahkan, BUKAN dibuang: sesudah 1 September sumbernya di Firebase
 *     ikut hilang, jadi keputusan simpan-atau-hapus harus bisa diambil
 *     belakangan, bukan sekarang di bawah tenggat.
 *
 * DUA JALUR YANG BERBEDA, DAN INI YANG SEMPAT SALAH
 *   Pemetaan dibaca lewat `pg` (koneksi Postgres LANGSUNG), bukan PostgREST.
 *   Versi pertama memakai supabase.from('nasabah').select(...) dan gagal
 *   dengan "Legacy API keys are disabled": setelan proyek itu mematikan
 *   endpoint PostgREST untuk kunci lama. Storage API TIDAK ikut terblokir,
 *   jadi unggahannya tetap boleh lewat supabase-js.
 *
 *     baca DB      → pg + DB_DSN          (wajib)
 *     unggah objek → Storage API + kunci  (hanya saat --execute)
 *
 *   Karena itu DRY-RUN kini cukup dengan DSN saja — tidak perlu kunci sama
 *   sekali, dan tidak menyentuh jaringan Supabase.
 *
 * PEMAKAIAN
 *   export DB_DSN='postgresql://postgres:<pw>@<host>:5432/postgres'
 *   node scripts/migration/migrate_storage.js                    # dry-run
 *
 *   export SUPABASE_URL='https://<ref>.supabase.co'
 *   export SUPABASE_SERVICE_ROLE_KEY='<kunci secret>'
 *   node scripts/migration/migrate_storage.js --execute          # unggah
 * ========================================================================= */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const argv = process.argv.slice(2);

/**
 * Ambil nilai flag. Menerima DUA bentuk:
 *   --dsn=postgresql://…     (sama dengan)
 *   --dsn postgresql://…     (spasi)
 *
 * ⚠ VERSI PERTAMA HANYA MENERIMA BENTUK "=". Bentuk berspasi mengembalikan
 *   boolean `true`, dan itu penyebab "str.charAt is not a function": nilai
 *   `true` diteruskan ke `new Client({ connectionString: true })`, lalu
 *   pg-connection-string memanggil `str.charAt(0)` di atasnya. Galatnya
 *   muncul dari dalam pustaka, sepuluh bingkai jauh dari sebabnya, dan tidak
 *   menyebut flag mana pun — jadi tampak seperti masalah data padahal
 *   masalah pengurai argumen.
 */
const arg = (k, d = null) => {
  const i = argv.findIndex((a) => a === `--${k}` || a.startsWith(`--${k}=`));
  if (i === -1) return d;
  const h = argv[i];
  const e = h.indexOf('=');
  if (e !== -1) return h.slice(e + 1);
  // Bentuk berspasi: ambil argumen berikutnya, kecuali ia flag lain.
  const berikut = argv[i + 1];
  if (berikut !== undefined && !berikut.startsWith('--')) return berikut;
  return true;          // flag tanpa nilai — sah untuk --execute
};

/**
 * Apa pun → string terpangkas. Satu-satunya cara nilai dari database atau
 * dari argumen boleh masuk ke pemrosesan string.
 *
 * `.trim()` bukan kerapian: data warisan menyimpan "panti " dengan spasi di
 * belakang, dan kunci pemetaan yang berbeda satu spasi tidak akan pernah
 * cocok — seluruh berkas cabang itu jadi yatim tanpa satu pun galat.
 */
const teks = (v) => (v === null || v === undefined ? '' : String(v).trim());

const CFG = {
  dir: teks(arg('dir', 'backup/storage')),
  execute: arg('execute', false) === true,
  paralel: Math.max(1, parseInt(teks(arg('paralel', '5')), 10) || 5),
  report: teks(arg('report', './storage_report.json')),
  dsn: teks(arg('dsn', process.env.DB_DSN)),
  url: teks(process.env.SUPABASE_URL),
  key: teks(process.env.SUPABASE_SERVICE_ROLE_KEY),
};

// Flag yang WAJIB bernilai. `--dsn` tanpa nilai kini tertangkap di sini
// dengan pesan yang menyebut flag-nya, bukan meledak di dalam pg.
for (const [nama, nilai] of [['dir', CFG.dir], ['report', CFG.report]]) {
  if (nilai === 'true' || !nilai) {
    console.error(`FATAL: --${nama} butuh nilai. Contoh: --${nama}=<isi>`);
    process.exit(2);
  }
}

// DSN selalu wajib: seluruh pemetaan dibaca dari Postgres langsung.
if (!CFG.dsn || CFG.dsn === 'true' || !/^postgres(ql)?:\/\//.test(CFG.dsn)) {
  console.error('FATAL: DSN Postgres tidak ada atau bentuknya salah.');
  console.error(`       diterima: ${JSON.stringify(CFG.dsn)}`);
  console.error('       Harus diawali postgresql:// atau postgres://.');
  console.error('       export DB_DSN="postgresql://postgres:<pw>@<host>:5432/postgres"');
  console.error('       atau --dsn="…". PostgREST TIDAK dipakai lagi di sini —');
  console.error('       endpoint itu diblokir setelan "Disable legacy API keys".');
  process.exit(2);
}
// Kunci Storage hanya perlu saat benar-benar mengunggah. Dry-run tidak
// menyentuh jaringan Supabase sama sekali.
if (CFG.execute && (!CFG.url || !CFG.key)) {
  console.error('FATAL: --execute butuh SUPABASE_URL dan SUPABASE_SERVICE_ROLE_KEY.');
  console.error('       ⚠ Bila proyek sudah mematikan legacy API keys, pakai kunci');
  console.error('         SECRET yang baru (sb_secret_…), bukan JWT service_role lama —');
  console.error('         yang lama akan ditolak Storage dengan pesan yang sama.');
  console.error('       Kunci ini mem-bypass RLS: jalankan dari mesin Anda sendiri,');
  console.error('       jangan dari CI, jangan taruh di .env.local.');
  process.exit(2);
}
if (!fs.existsSync(CFG.dir)) {
  console.error(`FATAL: folder "${CFG.dir}" tidak ada. Pakai --dir=<path>.`);
  process.exit(2);
}

let Client;
try { ({ Client } = require('pg')); }
catch {
  console.error('FATAL: paket `pg` belum terpasang.');
  console.error('       cd scripts/migration && npm i');
  process.exit(2);
}

// supabase-js HANYA untuk Storage, dan hanya dimuat saat --execute.
let storage = null;
if (CFG.execute) {
  let createClient;
  try { ({ createClient } = require('@supabase/supabase-js')); }
  catch {
    console.error('FATAL: @supabase/supabase-js belum terpasang.');
    console.error('       cd scripts/migration && npm i');
    process.exit(2);
  }
  storage = createClient(CFG.url, CFG.key, { auth: { persistSession: false } }).storage;
}

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
  log('▶ Memuat pemetaan dari Postgres (pg, bukan PostgREST) …');

  const klien = new Client({ connectionString: CFG.dsn });
  await klien.connect();
  try {
    const q = async (sql) => (await klien.query(sql)).rows;

    const nasabah = await q(
      'select id, legacy_admin_uid, legacy_pelanggan_id from koperasi.nasabah');
    const appUser = await q(
      'select id, legacy_uid from koperasi.app_user where legacy_uid is not null');
    const pinjaman = await q(
      'select distinct on (nasabah_id) nasabah_id, id, pinjaman_ke ' +
      '  from koperasi.pinjaman order by nasabah_id, pinjaman_ke desc');
    const kasir = await q('select id from koperasi.kasir_entry');

    // ── TIGA SUMBER PUSH ID LAIN ─────────────────────────────────────────
    // `pinjaman` TIDAK punya kolom legacy sama sekali (diperiksa di
    // 001_schema_v2.sql), jadi "cari pushId di tabel pinjaman" tidak bisa
    // dilakukan apa adanya. Yang menyimpan push id generasi pinjaman adalah
    // `pinjaman_history` — padanan node RTDB `riwayat_pinjaman` — dan ia
    // PUNYA nasabah_id, jadi bisa dipetakan.
    const riwayat = await q(
      'select nasabah_id, legacy_push_id, legacy_admin_uid ' +
      '  from koperasi.pinjaman_history where legacy_push_id is not null');
    const statusKhusus = await q(
      'select nasabah_id, legacy_pelanggan_id, legacy_admin_uid ' +
      '  from koperasi.pelanggan_status_khusus ' +
      ' where nasabah_id is not null and legacy_pelanggan_id is not null');
    // `pelanggan_ditolak` TIDAK punya nasabah_id — mereka memang tidak pernah
    // jadi nasabah. Fotonya tidak punya rumah di bucket `ktp`, yang path-nya
    // menuntut nasabah_id. Tetap dimuat supaya bisa DILAPORKAN sebagai
    // kategori tersendiri, bukan hilang di tumpukan "yatim".
    const ditolak = await q(
      'select legacy_push_id from koperasi.pelanggan_ditolak ' +
      ' where legacy_push_id is not null');

    let spasiLiar = 0;
    const bersih = (v) => {
      const t = teks(v);
      if (t !== String(v ?? '')) spasiLiar++;
      return t;
    };

    // Lapisan pencarian, diurut dari yang paling pasti ke paling longgar.
    // Setiap lapisan dicatat namanya supaya laporan bisa menjawab pertanyaan
    // "sebenarnya pushId itu id apa?" dengan bukti, bukan dugaan.
    const L = {
      nasabahPasangan: new Map(),   // adminUid/pelangganId → nasabah_id
      nasabahPelanggan: new Map(),  // pelangganId          → nasabah_id
      riwayatPasangan: new Map(),   // adminUid/pushId      → nasabah_id
      riwayatPush: new Map(),       // pushId               → nasabah_id
      statusKhusus: new Map(),      // pelangganId          → nasabah_id
      ditolak: new Set(),           // pushId (tanpa nasabah_id)
    };

    for (const n of nasabah) {
      const adm = bersih(n.legacy_admin_uid);
      const pel = bersih(n.legacy_pelanggan_id);
      const id = teks(n.id);
      if (adm && pel && id) L.nasabahPasangan.set(`${adm}/${pel}`, id);
      if (pel && id) L.nasabahPelanggan.set(pel, id);
    }
    for (const r of riwayat) {
      const adm = bersih(r.legacy_admin_uid);
      const push = bersih(r.legacy_push_id);
      const id = teks(r.nasabah_id);
      if (adm && push && id) L.riwayatPasangan.set(`${adm}/${push}`, id);
      if (push && id) L.riwayatPush.set(push, id);
    }
    for (const sk of statusKhusus) {
      const pel = bersih(sk.legacy_pelanggan_id);
      const id = teks(sk.nasabah_id);
      if (pel && id) L.statusKhusus.set(pel, id);
    }
    for (const d of ditolak) {
      const push = bersih(d.legacy_push_id);
      if (push) L.ditolak.add(push);
    }

    const perUser = new Map();
    for (const u of appUser) {
      const lu = bersih(u.legacy_uid);
      const id = teks(u.id);
      if (lu && id) perUser.set(lu, id);
    }

    const pinjamanTerbaru = new Map();
    for (const p of pinjaman) {
      const nid = teks(p.nasabah_id);
      const pid = teks(p.id);
      if (nid && pid) pinjamanTerbaru.set(nid, { id: pid, ke: Number(p.pinjaman_ke) || 0 });
    }

    const idKasirAda = new Set(kasir.map((k) => teks(k.id)).filter(Boolean));

    log(`   nasabah ${L.nasabahPasangan.size} · riwayat_pinjaman ${L.riwayatPush.size}` +
        ` · status_khusus ${L.statusKhusus.size} · ditolak ${L.ditolak.size}`);
    log(`   staf ${perUser.size} · pinjaman ${pinjamanTerbaru.size} · kasir_entry ${idKasirAda.size}`);
    if (spasiLiar) log(`   ⚠ ${spasiLiar} nilai legacy punya spasi liar — sudah dipangkas`);

    if (!L.nasabahPasangan.size) {
      throw new Error(
        'Tidak ada nasabah ber-legacy id. DSN-nya menunjuk database yang benar? ' +
        'Tanpa pemetaan ini SELURUH berkas ktp akan jadi yatim.');
    }
    return { L, perUser, pinjamanTerbaru, idKasirAda };
  } finally {
    await klien.end();
  }
}

/**
 * Cari nasabah_id untuk pasangan (adminUid, pushId), berlapis.
 *
 * ⚠ INI JAWABAN ATAS 73% YATIM. Versi sebelumnya hanya mencoba lapisan 1-2,
 *   yaitu `nasabah.legacy_pelanggan_id`. Bukti bahwa itu tidak cukup ada di
 *   angka dry-run sendiri: ktp_images_pending memakai BENTUK PATH YANG SAMA
 *   dan pencarian yang SAMA, tetapi kena 90% sementara ktp_images hanya 13%.
 *   Pencariannya bekerja; yang berbeda adalah RUANG ID yang dipakai kedua
 *   folder itu.
 *
 *   Karena itu setiap lapisan diberi NAMA dan dihitung. Laporan dry-run akan
 *   memberi tahu ruang id mana yang sebenarnya dipakai `ktp_images/` —
 *   dengan bukti, bukan dugaan.
 */
function cariNasabah(M, adminUid, pushId) {
  const c = [
    ['nasabah_pasangan',  M.L.nasabahPasangan.get(`${adminUid}/${pushId}`)],
    ['nasabah_pelanggan', M.L.nasabahPelanggan.get(pushId)],
    ['riwayat_pasangan',  M.L.riwayatPasangan.get(`${adminUid}/${pushId}`)],
    ['riwayat_push',      M.L.riwayatPush.get(pushId)],
    ['status_khusus',     M.L.statusKhusus.get(pushId)],
  ];
  for (const [lapisan, id] of c) if (id) return { id, lapisan };
  if (M.L.ditolak.has(pushId)) return { id: null, lapisan: 'pelanggan_ditolak' };
  return { id: null, lapisan: null };
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

/**
 * Segmen path relatif, dinormalisasi ke pemisah '/', tiap segmen dipangkas.
 *
 * Pemangkasan di SINI penting: nama folder dari Firebase bisa membawa spasi
 * (kita sendiri sempat membuat "28 Agu " lewat bug slice() di Blok 5), dan
 * segmen yang tidak dipangkas tidak akan cocok dengan kunci pemetaan yang
 * sudah dipangkas — hasilnya berkas jadi yatim tanpa sebab yang terlihat.
 */
const segmen = (rel) => teks(rel).split(path.sep).join('/').split('/').map(teks);

function petaFakturBu(seg, M) {
  // faktur_bu/{cabang}/{YYYY-MM}/{pushId}.jpg
  if (seg.length !== 4) return { yatim: 'bentuk path faktur_bu tidak dikenali' };
  const [, cabang, bulan, berkas] = seg;
  if (!cabang || !bulan || !berkas) return { yatim: 'segmen faktur_bu kosong' };
  const pushId = teks(berkas).replace(/\.[^.]+$/, '');
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
  const [asal, adminUid, pelangganId, berkas] = seg;
  if (!adminUid || !pelangganId || !berkas) return { yatim: 'segmen ktp kosong' };

  const dasar = teks(berkas).replace(/\.[^.]+$/, '');
  const jenis = JENIS_KTP[dasar] || dasar.replace(/^ktp_/, '');
  if (!jenis) return { yatim: `nama berkas tidak dikenali: ${berkas}` };

  const { id: nasabahId, lapisan } = cariNasabah(M, adminUid, pelangganId);
  if (!nasabahId) {
    // ── ARSIP YATIM (028) ────────────────────────────────────────────────
    // Tidak dibuang. Ini foto KTP, dan setelah 1 September sumbernya di
    // Firebase ikut hilang — jadi memindahkannya lebih dulu membuat
    // keputusan "simpan atau hapus" bisa diambil belakangan dengan tenang.
    //
    // ⚠ SEGMEN `asal` DITAMBAHKAN, dan ini menyimpang sedikit dari path yang
    //   diminta (`ktp-yatim/{adminUid}/{pushId}/{filename}`). Alasannya
    //   konkret: `ktp_images/ADM/PEL/ktp_ktp.jpg` dan
    //   `ktp_images_pending/ADM/PEL/ktp_ktp.jpg` bisa ada bersamaan, dan
    //   tanpa segmen pembeda keduanya menghasilkan path tujuan yang SAMA.
    //   Yang kedua akan ditolak 409 lalu dihitung "dilewati" — tampak wajar,
    //   padahal satu berkas hilang. Segmen ini juga yang membuat strukturnya
    //   benar-benar utuh untuk ditelusuri manual, sesuai tujuannya.
    return {
      bucket: 'ktp-yatim',
      tujuan: `${asal}/${adminUid}/${pelangganId}/${berkas}`,
      tertaut: false,
      lapisan,
      kategori: lapisan === 'pelanggan_ditolak' ? 'pemohon_ditolak' : 'yatim_sejati',
      catatan: lapisan === 'pelanggan_ditolak'
        // Pemohon yang DITOLAK tidak pernah jadi nasabah, jadi tidak ada
        // nasabah_id — dan path bucket `ktp` menuntutnya.
        ? 'pemohon ditolak (tidak punya nasabah_id)'
        : 'tidak ditemukan di lapisan mana pun',
    };
  }

  if (!pending) {
    return { bucket: 'ktp', tujuan: `${nasabahId}/${jenis}.jpg`, tertaut: true, lapisan };
  }

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
  if (!pj) {
    // Nasabahnya ketemu, tetapi belum punya satu pun baris pinjaman — path
    // ktp-pending menuntut pinjaman_id, jadi tidak ada tempat yang benar.
    // Diarsipkan juga, dengan alasannya sendiri supaya bisa dibedakan.
    return {
      bucket: 'ktp-yatim',
      tujuan: `${asal}/${adminUid}/${pelangganId}/${berkas}`,
      tertaut: false,
      lapisan,
      kategori: 'tanpa_pinjaman',
      catatan: `nasabah ${nasabahId} belum punya baris pinjaman`,
    };
  }
  return {
    bucket: 'ktp-pending',
    tujuan: `${nasabahId}/${pj.id}/${jenis}.jpg`,
    tertaut: true,
    lapisan,
    catatan: 'pinjaman_id = generasi tertinggi (asumsi, lihat komentar)',
  };
}

function petaProfil(seg, M) {
  // profile_photos/{uid}/profile.jpg
  if (seg.length !== 3) return { yatim: 'bentuk path profile_photos tidak dikenali' };
  const [, uid, berkas] = seg;
  if (!uid || !berkas) return { yatim: 'segmen profile_photos kosong' };
  const userId = M.perUser.get(uid);
  if (!userId) {
    return {
      bucket: 'profil-yatim',
      tujuan: `${uid}/${berkas}`,
      tertaut: false,
      kategori: 'profil_yatim',
      catatan: `app_user.legacy_uid tidak terpetakan: ${uid}`,
    };
  }
  const ext = (teks(berkas).match(/\.[^.]+$/) || ['.jpg'])[0];
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
  // Berapa berkas yang cocok lewat lapisan mana. INI yang menjawab
  // "pushId itu sebenarnya id apa" — dengan hitungan, bukan hipotesis.
  const perLapisan = {};
  const perLapisanBucket = {};
  const contohYatim = {};
  // Rincian isi bucket arsip (028), per alasan ia sampai ke sana.
  const perKategori = {};

  for (const abs of berkas) {
    const rel = path.relative(CFG.dir, abs);
    const hasil = petakan(rel, M);
    if (hasil.yatim) {
      yatim.push({ sumber: rel, alasan: hasil.yatim, lapisan: hasil.lapisan || null });
      // Simpan beberapa contoh per alasan supaya bisa diperiksa manual tanpa
      // membuka laporan 5.000 baris.
      const kunci = teks(hasil.yatim).replace(/:.*$/, '');
      (contohYatim[kunci] ||= []);
      if (contohYatim[kunci].length < 5) contohYatim[kunci].push(rel);
      continue;
    }
    perBucket[hasil.bucket] = (perBucket[hasil.bucket] || 0) + 1;
    if (hasil.kategori) {
      const kk = `${hasil.bucket} / ${hasil.kategori}`;
      perKategori[kk] = (perKategori[kk] || 0) + 1;
      // Contoh disimpan juga untuk yang diarsipkan — bukan cuma yang gagal.
      (contohYatim[kk] ||= []);
      if (contohYatim[kk].length < 5) contohYatim[kk].push(rel);
    }
    if (hasil.lapisan) {
      perLapisan[hasil.lapisan] = (perLapisan[hasil.lapisan] || 0) + 1;
      const kb = `${hasil.bucket} ← ${hasil.lapisan}`;
      perLapisanBucket[kb] = (perLapisanBucket[kb] || 0) + 1;
    }
    rencana.push({ abs, rel, ...hasil });
  }

  log(`\n▶ Rencana`);
  for (const [b, n] of Object.entries(perBucket).sort()) log(`   ${b.padEnd(12)} ${n}`);
  log(`   ${'YATIM'.padEnd(12)} ${yatim.length}`);
  const takTertaut = rencana.filter((r) => r.tertaut === false).length;
  if (takTertaut) log(`   ⚠ faktur tanpa kasir_entry (nama asli) : ${takTertaut}`);

  if (Object.keys(perLapisanBucket).length) {
    log(`\n▶ Lapisan mana yang mencocokkan — ini bukti ruang id yang dipakai`);
    for (const [k, n] of Object.entries(perLapisanBucket).sort((a, b) => b[1] - a[1])) {
      log(`   ${String(n).padStart(6)}  ${k}`);
    }
  }
  if (Object.keys(perKategori).length) {
    log(`\n▶ Arsip yatim (028) — dipindahkan, TIDAK dibuang`);
    for (const [k, n] of Object.entries(perKategori).sort((a, b) => b[1] - a[1])) {
      log(`   ${String(n).padStart(6)}  ${k}`);
    }
  }
  if (Object.keys(contohYatim).length) {
    log(`\n▶ Contoh per kategori (maksimal 5)`);
    for (const [alasan, contoh] of Object.entries(contohYatim)) {
      for (const c of contoh) log(`   ${alasan.padEnd(34)} ${c}`);
    }
  }

  const tulisLaporan = (ringkas) => {
    fs.writeFileSync(CFG.report, JSON.stringify({
      generatedAt: new Date().toISOString(),
      dir: CFG.dir, executed: CFG.execute,
      ...ringkas,
      perBucket,
      perLapisan,
      perLapisanBucket,
      perKategori,
      contohYatim,
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
    const ext = path.extname(teks(r.tujuan)).toLowerCase();
    const { error } = await storage.from(r.bucket).upload(
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
  log(`   dilompati : ${yatim.length}   (bentuk path tidak dikenali, lihat laporan)`);

  log(`\n▶ Per bucket (rencana; yang sudah ada terhitung "dilewati")`);
  for (const [b, n] of Object.entries(perBucket).sort()) {
    log(`   ${b.padEnd(14)} ${String(n).padStart(6)}`);
  }
  if (Object.keys(perKategori).length) {
    log(`\n▶ Rincian arsip yatim`);
    for (const [k, n] of Object.entries(perKategori).sort((a, b) => b[1] - a[1])) {
      log(`   ${String(n).padStart(6)}  ${k}`);
    }
    log(`\n   Bucket arsip hanya bisa dibaca Pengawas (028). Retensinya BELUM`);
    log(`   diputuskan — foto identitas tanpa pemilik adalah tanggungan, bukan`);
    log(`   aset. Tetapkan jadwalnya sesudah evakuasi.`);
  }
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
