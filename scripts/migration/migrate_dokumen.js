#!/usr/bin/env node
'use strict';
/* =========================================================================
 * MIGRASI DOKUMEN — storage_report.json → koperasi.dokumen
 * =========================================================================
 * Menutup G-2 sisi DATA. Prasyarat: migrate_storage.js --execute sudah
 * selesai (6.756 objek terunggah), 027 + 028 terpasang.
 *
 * ⚠ MENGISI `dokumen` SAJA BELUM MEMBUAT FOTO MUNCUL DI WEB.
 *   `getBukuPokok` di lib/apiSupabase.js masih mengembalikan URL foto kosong
 *   secara harfiah (fotoKtpUrl: '', dst) — itu ditulis begitu saat Storage
 *   belum dimigrasikan. Skrip ini menyiapkan datanya; penyambungan di web
 *   adalah langkah terpisah. Lihat §VERIFIKASI di bawah: query pertama
 *   membuktikan datanya siap, dan itu yang bisa dibuktikan skrip ini.
 *
 * SUMBER KEBENARAN: scripts/migration/storage_report.json, hanya entri
 * `tertaut: true`. Entri arsip (ktp-yatim / profil-yatim) sengaja TIDAK
 * dicatat: `dokumen.nasabah_id` menunjuk nasabah yang tidak diketahui, dan
 * baris registri yang menunjuk entah-siapa lebih buruk daripada tidak ada
 * baris sama sekali.
 *
 * PEMAKAIAN
 *   export DB_DSN='postgresql://postgres:<pw>@<host>:5432/postgres'
 *   node scripts/migration/migrate_dokumen.js              # dry-run
 *   node scripts/migration/migrate_dokumen.js --execute    # menulis
 * ========================================================================= */

const fs = require('fs');

const argv = process.argv.slice(2);
const arg = (k, d = null) => {
  const i = argv.findIndex((a) => a === `--${k}` || a.startsWith(`--${k}=`));
  if (i === -1) return d;
  const h = argv[i];
  const e = h.indexOf('=');
  if (e !== -1) return h.slice(e + 1);
  const berikut = argv[i + 1];
  if (berikut !== undefined && !berikut.startsWith('--')) return berikut;
  return true;
};
const teks = (v) => (v === null || v === undefined ? '' : String(v).trim());

const CFG = {
  report: teks(arg('report', 'scripts/migration/storage_report.json')),
  dsn: teks(arg('dsn', process.env.DB_DSN)),
  execute: arg('execute', false) === true,
  nota: arg('nota', false) === true,
  batch: Math.max(1, parseInt(teks(arg('batch', '500')), 10) || 500),
};

if (CFG.execute && (!CFG.dsn || CFG.dsn === 'true' || !/^postgres(ql)?:\/\//.test(CFG.dsn))) {
  console.error('FATAL: --execute butuh DSN Postgres yang sah.');
  console.error(`       diterima: ${JSON.stringify(CFG.dsn)}`);
  console.error('       export DB_DSN="postgresql://postgres:<pw>@<host>:5432/postgres"');
  console.error('       PostgREST tidak dipakai — diblokir "Disable legacy API keys".');
  process.exit(2);
}
if (!fs.existsSync(CFG.report)) {
  console.error(`FATAL: laporan "${CFG.report}" tidak ada. Jalankan migrate_storage.js dulu.`);
  process.exit(2);
}

// `pg` dimuat HANYA saat --execute. Dry-run cuma membaca berkas laporan —
// tidak menyentuh database sama sekali, jadi memaksanya butuh `pg` hanya
// menghalangi orang memeriksa pemetaan sebelum menulis apa pun.
let Client = null;
if (CFG.execute) {
  try { ({ Client } = require('pg')); }
  catch {
    console.error('FATAL: paket `pg` belum terpasang. cd scripts/migration && npm i');
    process.exit(2);
  }
}

const log = (...a) => console.log(...a);

// ============================================================== PEMETAAN ===
// Nama berkas di bucket → nilai enum koperasi.dokumen_jenis (001:887-890).
// Enum-nya tetap: ktp | ktp_suami | ktp_istri | foto_nasabah | serah_terima |
// bukti_bayar | nota_kasir | profil. Apa pun di luar itu ditolak Postgres,
// jadi dicegat di sini dengan pesan yang jelas.
const JENIS_SAH = new Set([
  'ktp', 'ktp_suami', 'ktp_istri', 'foto_nasabah',
  'serah_terima', 'bukti_bayar', 'nota_kasir', 'profil',
]);

const namaTanpaExt = (p) => teks(p).split('/').pop().replace(/\.[^.]+$/, '');

/**
 * Satu entri laporan → satu calon baris `dokumen`, atau null bila dilewati.
 *
 * PATH-nya yang jadi sumber, bukan nama berkas aslinya: migrate_storage.js
 * sudah menormalkan nama saat mengunggah, jadi yang ada di bucket sudah
 * berupa `{jenis}.jpg`. Membaca ulang dari path menjaga keduanya tidak
 * pernah berbeda.
 */
function petakan(e) {
  const bucket = teks(e.bucket);
  const p = teks(e.tujuan);
  const seg = p.split('/');

  if (bucket === 'ktp') {
    // ktp/{nasabah_id}/{jenis}.jpg
    if (seg.length !== 2) return { lewat: `bentuk path ktp tidak dikenali: ${p}` };
    const jenis = namaTanpaExt(seg[1]);
    if (!JENIS_SAH.has(jenis)) return { lewat: `jenis di luar enum: ${jenis}` };
    return { bucket, object_path: p, jenis, nasabah_id: seg[0], is_pending: false };
  }

  if (bucket === 'ktp-pending') {
    // ktp-pending/{nasabah_id}/{pinjaman_id}/{jenis}.jpg
    //
    // `dokumen` PUNYA kolom pinjaman_id (001:899), jadi diisi — tidak perlu
    // siasat apa pun. Ditambah `is_pending = true` (001:905-907), yang
    // memang dibuat untuk membedakan foto pengajuan yang belum final dari
    // foto KTP resmi. Tanpa penanda itu, kedua jenis foto akan tampak sama
    // di registri dan bisa tertukar saat ditampilkan.
    if (seg.length !== 3) return { lewat: `bentuk path ktp-pending tidak dikenali: ${p}` };
    const jenis = namaTanpaExt(seg[2]);
    if (!JENIS_SAH.has(jenis)) return { lewat: `jenis di luar enum: ${jenis}` };
    return {
      bucket, object_path: p, jenis,
      nasabah_id: seg[0], pinjaman_id: seg[1], is_pending: true,
    };
  }

  if (bucket === 'profil') {
    // profil/{user_id}/profile.jpg
    if (seg.length !== 2) return { lewat: `bentuk path profil tidak dikenali: ${p}` };
    return { bucket, object_path: p, jenis: 'profil', user_id: seg[0], is_pending: false };
  }

  if (bucket === 'nota-kasir') {
    // Dilewati SECARA BAWAAN, dan ini keputusan, bukan kelalaian:
    // `kasir_entry.nota_path` sudah menjadi sumber kebenaran untuk nota
    // (Blok 5), dan `getKasirEntries` membacanya dari sana — bukan dari
    // `dokumen`. Mencatatnya di dua tempat berarti dua tempat yang bisa
    // saling bertentangan. Pakai --nota bila registrinya memang ingin
    // dilengkapi untuk keperluan audit.
    if (!CFG.nota) return { lewat: 'nota-kasir (pakai --nota bila ingin dicatat)' };
    if (seg.length !== 3) return { lewat: `bentuk path nota-kasir tidak dikenali: ${p}` };
    const kasirId = namaTanpaExt(seg[2]);
    // Nama berkas yang bukan uuid = faktur yang entri kasirnya tidak ada
    // (migrate_storage.js mempertahankan nama aslinya). Tidak bisa ditautkan.
    if (!/^[0-9a-f-]{36}$/i.test(kasirId)) {
      return { lewat: `nota tanpa kasir_entry: ${p}` };
    }
    return { bucket, object_path: p, jenis: 'nota_kasir', kasir_entry_id: kasirId, is_pending: false };
  }

  // ktp-yatim / profil-yatim — lihat catatan di kepala berkas.
  return { lewat: `bucket arsip, tidak dicatat: ${bucket}` };
}

// ================================================================== MAIN ===
(async () => {
  log(`▶ Membaca ${CFG.report}`);
  const laporan = JSON.parse(fs.readFileSync(CFG.report, 'utf8'));
  const semua = Array.isArray(laporan.rencana) ? laporan.rencana : [];
  if (!semua.length) {
    console.error('FATAL: laporan tidak memuat `rencana`. Berkas yang benar?');
    process.exit(3);
  }

  const tertaut = semua.filter((e) => e.tertaut === true);
  log(`   ${semua.length} entri, ${tertaut.length} tertaut=true`);

  const baris = [];
  const dilewati = {};
  for (const e of tertaut) {
    const h = petakan(e);
    if (h.lewat) {
      const k = h.lewat.replace(/:.*$/, '');
      dilewati[k] = (dilewati[k] || 0) + 1;
      continue;
    }
    baris.push(h);
  }

  const perJenis = {};
  for (const b of baris) {
    const k = `${b.bucket} / ${b.jenis}${b.is_pending ? ' (pending)' : ''}`;
    perJenis[k] = (perJenis[k] || 0) + 1;
  }

  log(`\n▶ Calon baris dokumen`);
  for (const [k, n] of Object.entries(perJenis).sort((a, b) => b[1] - a[1])) {
    log(`   ${String(n).padStart(6)}  ${k}`);
  }
  if (Object.keys(dilewati).length) {
    log(`\n▶ Dilewati`);
    for (const [k, n] of Object.entries(dilewati).sort((a, b) => b[1] - a[1])) {
      log(`   ${String(n).padStart(6)}  ${k}`);
    }
  }

  if (!CFG.execute) {
    log(`\n✓ DRY-RUN. Tidak ada yang ditulis.`);
    log('  Tambahkan --execute untuk menulis ke koperasi.dokumen.');
    return;
  }

  // ================================================================ TULIS ===
  const klien = new Client({ connectionString: CFG.dsn });
  await klien.connect();
  log('\n▶ Menulis ke koperasi.dokumen (satu transaksi)');

  let masuk = 0, lewatKonflik = 0;
  try {
    await klien.query('begin');

    for (let i = 0; i < baris.length; i += CFG.batch) {
      const potong = baris.slice(i, i + CFG.batch);
      const nilai = [];
      const params = [];
      for (const b of potong) {
        const dasar = params.length;
        params.push(
          b.bucket, b.object_path, b.jenis,
          b.nasabah_id ?? null, b.pinjaman_id ?? null,
          b.kasir_entry_id ?? null, b.user_id ?? null,
          b.is_pending,
        );
        nilai.push(
          `($${dasar + 1}, $${dasar + 2}, $${dasar + 3}::koperasi.dokumen_jenis, ` +
          `$${dasar + 4}::uuid, $${dasar + 5}::uuid, $${dasar + 6}::uuid, $${dasar + 7}::uuid, ` +
          `$${dasar + 8}::boolean)`
        );
      }

      // `uploaded_by` NOT NULL (001:908) dan migrasi tidak punya pengunggah
      // sungguhan. Diisi dari pemilik data yang paling jujur tersedia:
      //   · ktp / ktp-pending → nasabah.admin_id (admin lapangan yang memotret)
      //   · profil            → penggunanya sendiri
      //   · nota-kasir        → kasir_entry.dicatat_oleh
      // Bukan satu "akun sistem": itu akan menghapus jejak siapa yang
      // sebenarnya mengumpulkan berkas, dan jejak itu justru gunanya registri.
      const { rowCount } = await klien.query(
        `insert into koperasi.dokumen
           (bucket_id, object_path, jenis, nasabah_id, pinjaman_id,
            kasir_entry_id, user_id, is_pending, uploaded_by)
         select v.bucket_id, v.object_path, v.jenis, v.nasabah_id, v.pinjaman_id,
                v.kasir_entry_id, v.user_id, v.is_pending,
                coalesce(n.admin_id, v.user_id, k.dicatat_oleh)
           from (values ${nilai.join(',')})
                as v(bucket_id, object_path, jenis, nasabah_id, pinjaman_id,
                     kasir_entry_id, user_id, is_pending)
           left join koperasi.nasabah     n on n.id = v.nasabah_id
           left join koperasi.kasir_entry k on k.id = v.kasir_entry_id
          where coalesce(n.admin_id, v.user_id, k.dicatat_oleh) is not null
         on conflict (bucket_id, object_path) do nothing`,
        params,
      );
      masuk += rowCount;
      lewatKonflik += potong.length - rowCount;
      process.stdout.write(`\r    ${Math.min(i + CFG.batch, baris.length)}/${baris.length}`);
    }
    process.stdout.write('\n');
    await klien.query('commit');
    log('✓ COMMIT.');

    const { rows } = await klien.query(
      `select bucket_id, jenis::text, is_pending, count(*)::int n
         from koperasi.dokumen group by 1,2,3 order by 4 desc`);
    log(`\n▶ Isi koperasi.dokumen sekarang`);
    for (const r of rows) {
      log(`   ${String(r.n).padStart(6)}  ${r.bucket_id} / ${r.jenis}${r.is_pending ? ' (pending)' : ''}`);
    }
    log(`\n   baris baru : ${masuk}`);
    log(`   dilewati   : ${lewatKonflik}   (sudah ada / tanpa uploaded_by — idempoten)`);
  } catch (e) {
    await klien.query('rollback').catch(() => {});
    console.error('\n✗ ROLLBACK — tidak ada yang tersimpan.\n', e.message);
    process.exitCode = 1;
  } finally {
    await klien.end();
  }
})().catch((e) => {
  console.error('\n✗ FATAL:', e.message);
  process.exit(1);
});

/* =========================================================================
 * VERIFIKASI
 * =========================================================================
 * 1) DATA SIAP — ini yang dibuktikan skrip ini.
 *
 *    select d.jenis, count(*) baris, count(distinct d.nasabah_id) nasabah
 *      from koperasi.dokumen d
 *     where d.bucket_id in ('ktp','ktp-pending')
 *     group by 1 order by 2 desc;
 *
 *    -- Berapa nasabah aktif yang KTP-nya sudah punya baris registri:
 *    select count(*) filter (where ada) punya_foto,
 *           count(*) filter (where not ada) tanpa_foto
 *      from (
 *        select n.id, exists (
 *                 select 1 from koperasi.dokumen d
 *                  where d.nasabah_id = n.id and d.jenis = 'ktp'
 *               ) ada
 *          from koperasi.nasabah n where n.arsip_at is null
 *      ) t;
 *
 *    -- Sanity: tidak ada baris menunjuk objek yang tidak ada di Storage.
 *    select count(*) as dokumen_tanpa_objek
 *      from koperasi.dokumen d
 *      left join storage.objects o
 *             on o.bucket_id = d.bucket_id and o.name = d.object_path
 *     where o.id is null;
 *    -- harapan: 0
 *
 * 2) FOTO MUNCUL DI WEB — BELUM, dan bukan karena skrip ini.
 *
 *    `lib/apiSupabase.js` getBukuPokok masih menulis fotoKtpUrl: '' secara
 *    harfiah (G-2). Selama baris itu belum diganti, `dokumen` boleh terisi
 *    penuh dan halaman tetap menampilkan placeholder.
 *
 *    Yang dibutuhkan di sana: baca `dokumen` untuk nasabah yang tampil, lalu
 *    createSignedUrls('ktp', paths, 3600) — pola yang sama persis dengan
 *    yang sudah dipakai getKasirEntries untuk nota.
 *
 *    Query untuk menyiapkan/menguji bentuk datanya:
 *      select d.nasabah_id, d.jenis::text, d.bucket_id, d.object_path
 *        from koperasi.dokumen d
 *       where d.nasabah_id = '<nasabah_id>' and d.is_pending = false
 *       order by d.jenis;
 * ========================================================================= */
