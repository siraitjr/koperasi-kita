#!/usr/bin/env node
'use strict';
/* =========================================================================
 * BUAT AKUN SUPABASE AUTH UNTUK SELURUH STAF
 * Dijalankan SETELAH migrate.js --execute, SEBELUM FK app_user dipasang lagi.
 * =========================================================================
 * BELUM PERNAH DIJALANKAN.
 *
 * KEBIJAKAN PASSWORD (keputusan pemilik, 12 Agu 2026)
 * ---------------------------------------------------
 *  - PENGAWAS  : dibuat DENGAN password, agar bisa login pertama kali.
 *  - Selain itu: dibuat TANPA password. Akun ada (supaya FK ke auth.users
 *    valid) tetapi tidak bisa login sampai passwordnya di-set.
 *
 * Skrip ini TIDAK memuat password apa pun di dalam kode. Password diberikan
 * lewat --pengawas-password atau env PENGAWAS_PASSWORD. Alasannya sederhana:
 * repo ini ada di GitHub, dan password produksi yang ter-commit akan tetap
 * ada di riwayat git selamanya walau dihapus belakangan.
 *
 * ⚠ BACA 006 §3a SEBELUM MENJALANKAN. Alur "Pengawas reset password user
 *   lain via aplikasi" TIDAK berfungsi setelah cutover — fungsi itu memanggil
 *   Firebase Auth, bukan Supabase. Pakai --emit-reset-links sebagai jalan
 *   keluar sementara.
 *
 * ID akun diambil dari koperasi.app_user yang sudah diisi migrate.js — TIDAK
 * dihitung ulang di sini. Dengan begitu app_user.id dan auth.users.id pasti
 * identik dan tidak mungkin melenceng karena dua implementasi yang berbeda.
 *
 * PEMAKAIAN
 *   export SUPABASE_URL="https://xxx.supabase.co"
 *   export SUPABASE_SERVICE_ROLE_KEY="eyJ..."          # service_role, BUKAN anon
 *   export SUPABASE_DSN="postgresql://postgres:PASS@db.xxx.supabase.co:5432/postgres"
 *   export PENGAWAS_PASSWORD='...'
 *
 *   node create_auth_users.js --dry-run
 *   node create_auth_users.js --execute
 *   node create_auth_users.js --execute --emit-reset-links=./reset_links.csv
 *
 * npm i pg @supabase/supabase-js
 * ========================================================================= */

const fs = require('fs');

const argv = process.argv.slice(2);
const arg = (k, d = null) => {
  const h = argv.find((a) => a === `--${k}` || a.startsWith(`--${k}=`));
  if (!h) return d;
  const e = h.indexOf('=');
  return e === -1 ? true : h.slice(e + 1);
};

const CFG = {
  url: arg('url', process.env.SUPABASE_URL),
  key: arg('key', process.env.SUPABASE_SERVICE_ROLE_KEY),
  dsn: arg('dsn', process.env.SUPABASE_DSN),
  pengawasPassword: arg('pengawas-password', process.env.PENGAWAS_PASSWORD),
  execute: arg('execute', false) === true,
  emitLinks: arg('emit-reset-links', null),
};

if (!CFG.dsn) { console.error('FATAL: --dsn / SUPABASE_DSN wajib.'); process.exit(2); }
if (CFG.execute) {
  if (!CFG.url || !CFG.key) {
    console.error('FATAL: --execute butuh SUPABASE_URL dan SUPABASE_SERVICE_ROLE_KEY.');
    process.exit(2);
  }
  if (!CFG.pengawasPassword) {
    console.error('FATAL: --pengawas-password / PENGAWAS_PASSWORD wajib saat --execute.');
    console.error('       Password TIDAK disimpan di dalam repo — berikan saat menjalankan.');
    process.exit(2);
  }
  if (String(CFG.pengawasPassword).length < 12) {
    console.error('FATAL: password Pengawas < 12 karakter. Tolak.');
    process.exit(2);
  }
}

let Client, createClient;
try { ({ Client } = require('pg')); }
catch { console.error('FATAL: `pg` belum terpasang. npm i pg'); process.exit(4); }
if (CFG.execute) {
  try { ({ createClient } = require('@supabase/supabase-js')); }
  catch { console.error('FATAL: `@supabase/supabase-js` belum terpasang. npm i @supabase/supabase-js'); process.exit(4); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const pg = new Client({ connectionString: CFG.dsn });
  await pg.connect();

  const { rows: users } = await pg.query(
    `select id, email, nama, role::text as role, cabang_id, legacy_uid
       from koperasi.app_user
      order by (role = 'pengawas') desc, email`
  );
  await pg.end();

  if (!users.length) {
    console.error('FATAL: koperasi.app_user kosong. Jalankan migrate.js --execute dulu.');
    process.exit(5);
  }

  const pengawas = users.filter((u) => u.role === 'pengawas');
  const lainnya = users.filter((u) => u.role !== 'pengawas');
  const emailPalsu = users.filter((u) => /@migrasi\.invalid$/.test(u.email));

  console.log(`▶ Akun yang akan dibuat: ${users.length}`);
  console.log(`   pengawas (DENGAN password) : ${pengawas.length}`);
  pengawas.forEach((u) => console.log(`     - ${u.email}  [${u.id}]`));
  console.log(`   lainnya  (TANPA password)  : ${lainnya.length}`);
  const perRole = lainnya.reduce((m, u) => ((m[u.role] = (m[u.role] || 0) + 1), m), {});
  console.log(`     ${JSON.stringify(perRole)}`);

  if (!pengawas.length) {
    console.error('\nFATAL: tidak ada user ber-role pengawas. Tidak akan ada yang bisa login.');
    console.error('Periksa metadata/roles/pengawas di export — flag role mungkin tidak terbaca.');
    process.exit(6);
  }
  if (emailPalsu.length) {
    console.warn(`\n⚠  ${emailPalsu.length} user tidak punya email asli (…@migrasi.invalid).`);
    console.warn('   Akun tetap dibuat agar FK valid, tetapi TIDAK akan bisa dipulihkan');
    console.warn('   lewat email. Perbaiki emailnya di app_user sebelum lanjut kalau perlu.');
    emailPalsu.slice(0, 10).forEach((u) => console.warn(`     - ${u.legacy_uid}`));
  }

  if (!CFG.execute) {
    console.log('\n✓ DRY-RUN. Tidak ada akun yang dibuat.');
    console.log('  Jalankan dengan --execute (beserta SUPABASE_URL + SERVICE_ROLE_KEY');
    console.log('  + PENGAWAS_PASSWORD) untuk benar-benar membuat akun.');
    return;
  }

  const sb = createClient(CFG.url, CFG.key, { auth: { persistSession: false } });
  const hasil = { dibuat: 0, sudahAda: 0, gagal: 0 };
  const gagalDetail = [];
  const links = [];

  for (const u of users) {
    const isPengawas = u.role === 'pengawas';
    const payload = {
      id: u.id,                     // id dari app_user — bukan dihitung ulang
      email: u.email,
      email_confirm: true,          // tanpa ini user harus verifikasi email dulu
      user_metadata: { nama: u.nama, role: u.role, cabang: u.cabang_id, legacy_uid: u.legacy_uid },
    };
    if (isPengawas) payload.password = CFG.pengawasPassword;

    const { error } = await sb.auth.admin.createUser(payload);

    if (error) {
      const sudah = /already|exists|duplicate/i.test(error.message || '');
      if (sudah) { hasil.sudahAda++; }
      else {
        hasil.gagal++;
        gagalDetail.push({ email: u.email, role: u.role, error: error.message });
        console.error(`  ✗ ${u.email}: ${error.message}`);
      }
    } else {
      hasil.dibuat++;
      process.stdout.write(`\r  dibuat ${hasil.dibuat}/${users.length}`);
    }

    // Tautan pemulihan untuk user non-pengawas (jalan keluar bila fitur reset
    // in-app belum tersedia di Supabase — lihat 006 §3a).
    if (CFG.emitLinks && !isPengawas && !/@migrasi\.invalid$/.test(u.email)) {
      const { data, error: e2 } = await sb.auth.admin.generateLink({
        type: 'recovery', email: u.email,
      });
      if (!e2 && data?.properties?.action_link) {
        links.push({ email: u.email, nama: u.nama, role: u.role,
                     cabang: u.cabang_id || '', link: data.properties.action_link });
      }
    }
    await sleep(60); // jangan memicu rate limit Admin API
  }
  process.stdout.write('\n');

  if (CFG.emitLinks && links.length) {
    const csv = ['email,nama,role,cabang,reset_link']
      .concat(links.map((l) => [l.email, l.nama, l.role, l.cabang, l.link]
        .map((v) => `"${String(v).replace(/"/g, '""')}"`).join(',')))
      .join('\n');
    fs.writeFileSync(CFG.emitLinks, csv);
    console.log(`\n▶ ${links.length} tautan reset ditulis ke ${CFG.emitLinks}`);
    console.log('  ⚠ BERKAS INI SETARA PASSWORD. Jangan commit, jangan kirim lewat');
    console.log('    grup chat. Bagikan per orang, lalu hapus berkasnya.');
  }

  console.log('\n▶ Ringkasan');
  console.log(`   dibuat    : ${hasil.dibuat}`);
  console.log(`   sudah ada : ${hasil.sudahAda}`);
  console.log(`   gagal     : ${hasil.gagal}`);
  if (gagalDetail.length) {
    console.log('\n✗ Ada kegagalan. JANGAN pasang FK sebelum semuanya beres:');
    gagalDetail.forEach((g) => console.log(`   - ${g.email} (${g.role}): ${g.error}`));
    process.exitCode = 1;
    return;
  }

  console.log('\n✓ Selesai. Langkah berikutnya (SQL Editor):');
  console.log('    alter table koperasi.app_user');
  console.log('      add constraint app_user_id_fkey');
  console.log('      foreign key (id) references auth.users(id);');
  console.log('\n  Lalu verifikasi tidak ada app_user yang menggantung:');
  console.log('    select count(*) from koperasi.app_user u');
  console.log('     left join auth.users a on a.id = u.id where a.id is null;   -- harus 0');
  console.log('\n  Setelah Pengawas login: GANTI passwordnya. Password yang dipakai');
  console.log('  saat migrasi sudah melewati shell history dan berkas env.');
})().catch((e) => { console.error('FATAL:', e.message); process.exit(1); });
