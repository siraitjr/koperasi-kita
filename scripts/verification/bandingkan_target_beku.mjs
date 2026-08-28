#!/usr/bin/env node
// =========================================================================
// BANDINGKAN TARGET BEKU — RPC Supabase vs summary RTDB (026 §VERIFIKASI 4)
// =========================================================================
// WAJIB DIJALANKAN SEBELUM 1 SEPTEMBER. Sesudah itu RTDB mati dan tidak ada
// pembanding lagi — selamanya.
//
// APA YANG DIPERIKSA
//   `freezeRekapHarian` (Cloud Function 23:59 WIB) membekukan `target` dari
//   `summary/perAdmin/{uid}/targetHariIni`, hasil summaryHelpers.js.
//   Penggantinya, `rpc_bekukan_rekap_harian` (026), menghitung `target` dari
//   `jadwal_cicilan` yang jatuh tempo. KEDUANYA BELUM TENTU SAMA.
//
//   Kalau berbeda, kolom Target Buku Rekap untuk hari-hari SETELAH cutoff
//   akan bergeser dari pola sebelumnya — persis kelas masalah yang 026
//   selesaikan untuk masa lalu. Lebih baik ketahuan sekarang.
//
//   `storting` jauh lebih aman (dijumlah dari pembayaran nyata) dan ikut
//   dibandingkan sebagai kontrol: kalau storting pun meleset, yang salah
//   bukan rumus target melainkan pemetaan admin atau tanggalnya.
//
// Skrip ini HANYA MEMBACA. Ia tidak menulis ke RTDB maupun Supabase, dan
// TIDAK memanggil rpc_bekukan_rekap_harian (itu menulis) — rumus target-nya
// dihitung ulang di sini dengan query baca yang setara.
//
// PEMAKAIAN
//   export FB_DB='https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app'
//   export FB_ID_TOKEN='<ID token Firebase, dari konsol browser>'
//   export SUPABASE_URL='https://<ref>.supabase.co'
//   export SUPABASE_SERVICE_ROLE_KEY='<service role key>'
//   export TANGGAL='2026-08-25,2026-08-26'    # opsional; default 5 hari terakhir
//   node scripts/verification/bandingkan_target_beku.mjs
// =========================================================================

import { createClient } from '@supabase/supabase-js';

const E = process.env;
const wajib = ['FB_DB', 'FB_ID_TOKEN', 'SUPABASE_URL', 'SUPABASE_SERVICE_ROLE_KEY'];
const hilang = wajib.filter((k) => !E[k]);
if (hilang.length) { console.error('FATAL: env belum lengkap:', hilang.join(', ')); process.exit(2); }

const db = createClient(E.SUPABASE_URL, E.SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false }, db: { schema: 'koperasi' },
});

// RTDB REST. `summary/perAdmin` adalah node yang dibaca freezeRekapHarian.js:60.
async function rtdb(path) {
  const r = await fetch(`${E.FB_DB}/${path}.json?auth=${encodeURIComponent(E.FB_ID_TOKEN)}`);
  if (!r.ok) throw new Error(`RTDB ${path} → HTTP ${r.status}`);
  return r.json();
}

function hariTerakhir(n) {
  const out = [];
  const wib = new Date(Date.now() + 7 * 3600e3);
  for (let i = 1; i <= n; i++) {           // mulai kemarin: hari ini belum beku
    const d = new Date(wib); d.setUTCDate(d.getUTCDate() - i);
    out.push(d.toISOString().slice(0, 10));
  }
  return out;
}

const TANGGAL = (E.TANGGAL ? E.TANGGAL.split(',') : hariTerakhir(5)).map((s) => s.trim());

// Pemetaan uuid ↔ UID Firebase lewat app_user.legacy_uid (kolom dari 022).
// Tanpa itu tidak ada cara mencocokkan baris Supabase dengan node RTDB.
const { data: staf, error: eStaf } = await db
  .from('app_user').select('id, nama, legacy_uid, cabang_id, aktif').eq('aktif', true);
if (eStaf) { console.error('FATAL: baca app_user:', eStaf.message); process.exit(1); }

const berlegacy = (staf || []).filter((u) => u.legacy_uid && !u.legacy_uid.startsWith('sb:'));
if (!berlegacy.length) {
  console.error('FATAL: tidak ada app_user ber-legacy_uid. Jalankan 022 lebih dulu.');
  process.exit(1);
}
console.log(`▶ ${berlegacy.length} staf terpetakan ke UID Firebase; ${TANGGAL.length} tanggal diuji`);

const BARIS = [];

for (const tgl of TANGGAL) {
  // ---- sisi RTDB: nilai yang SUDAH dibekukan Cloud Function ---------------
  let beku = {};
  try { beku = (await rtdb(`rekap_harian_final`)) || {}; }
  catch (e) { console.error(`  ✗ ${tgl}: ${e.message}`); continue; }

  // ---- sisi Supabase: target menurut jadwal_cicilan -----------------------
  const { data: jadwal, error: eJ } = await db
    .from('jadwal_cicilan')
    .select('jumlah, pinjaman:pinjaman_id(status, nasabah:nasabah_id(admin_id))')
    .eq('tanggal', tgl);
  if (eJ) { console.error(`  ✗ ${tgl} jadwal: ${eJ.message}`); continue; }

  const targetSb = {};
  for (const j of jadwal || []) {
    const st = j.pinjaman?.status;
    if (st !== 'Aktif' && st !== 'Disetujui') continue;   // cermin 026
    const aid = j.pinjaman?.nasabah?.admin_id;
    if (!aid) continue;
    targetSb[aid] = (targetSb[aid] || 0) + Number(j.jumlah || 0);
  }

  // ---- sisi Supabase: storting dari pembayaran nyata (kontrol) ------------
  const { data: bayar, error: eB } = await db
    .from('pembayaran')
    .select('jumlah, pinjaman:pinjaman_id(nasabah:nasabah_id(admin_id))')
    .eq('tanggal', tgl);
  if (eB) { console.error(`  ✗ ${tgl} pembayaran: ${eB.message}`); continue; }

  const stortingSb = {};
  for (const b of bayar || []) {
    const aid = b.pinjaman?.nasabah?.admin_id;
    if (!aid) continue;
    stortingSb[aid] = (stortingSb[aid] || 0) + Number(b.jumlah || 0);
  }

  for (const u of berlegacy) {
    const entri = beku?.[u.legacy_uid]?.[tgl];
    if (!entri) continue;                    // tidak dibekukan hari itu — lewati
    BARIS.push({
      tgl,
      nama: u.nama,
      tRtdb: Number(entri.target || 0),
      tSb: Number(targetSb[u.id] || 0),
      sRtdb: Number(entri.storting || 0),
      sSb: Number(stortingSb[u.id] || 0),
    });
  }
}

if (!BARIS.length) {
  console.error('\n✗ Tidak ada pasangan (admin, tanggal) yang bisa dibandingkan.');
  console.error('  Periksa: rekap_harian_final berisi tanggal itu? legacy_uid terisi?');
  process.exit(1);
}

const rp = (n) => Number(n).toLocaleString('id-ID');
console.log('\n| Tanggal    | Admin                | Target RTDB | Target SB   | Δ        | Storting RTDB | Storting SB   | Δ        |');
let bedaT = 0, bedaS = 0;
for (const b of BARIS) {
  const dT = b.tSb - b.tRtdb, dS = b.sSb - b.sRtdb;
  if (dT !== 0) bedaT++;
  if (dS !== 0) bedaS++;
  console.log(`| ${b.tgl} | ${String(b.nama).slice(0, 20).padEnd(20)} | ${rp(b.tRtdb).padStart(11)} | ${rp(b.tSb).padStart(11)} | ${(dT ? rp(dT) : '·').padStart(8)} | ${rp(b.sRtdb).padStart(13)} | ${rp(b.sSb).padStart(13)} | ${(dS ? rp(dS) : '·').padStart(8)} |`);
}

console.log(`\nDibandingkan: ${BARIS.length} pasangan · target BEDA=${bedaT} · storting BEDA=${bedaS}`);

if (bedaS > 0) {
  console.log('\n✗ STORTING meleset. Ini bukan soal rumus target — tersangkanya');
  console.log('  pemetaan admin (legacy_uid) atau tanggal. Bereskan ini dulu;');
  console.log('  selisih target tidak berarti apa-apa selama storting belum cocok.');
  process.exit(1);
}
if (bedaT > 0) {
  console.log('\n⚠ TARGET meleset, storting cocok. Berarti pemetaannya benar dan');
  console.log('  yang berbeda memang RUMUSNYA: summaryHelpers.js vs jadwal_cicilan.');
  console.log('  Perbaiki rumus di 026 rpc_bekukan_rekap_harian SEBELUM 1 September,');
  console.log('  atau putuskan sadar bahwa Target pasca-cutoff memakai definisi baru');
  console.log('  dan umumkan. Jangan biarkan tergeser diam-diam.');
  process.exit(1);
}
console.log('\n✓ Target dan storting cocok pada seluruh pasangan yang diuji.');
console.log('  Ini bukan bukti untuk SELURUH tanggal — hanya yang di atas.');
