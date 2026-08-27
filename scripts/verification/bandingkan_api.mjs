#!/usr/bin/env node
// =========================================================================
// BANDINGKAN ANGKA: Cloud Functions lama vs Supabase — BLOK 1 (025 §4)
// =========================================================================
// Ini yang menghasilkan tabel perbandingan. HARUS dijalankan selagi KEDUA
// sisi masih hidup; sesudah 1 September tidak ada pembanding lagi.
//
// Skrip ini hanya MEMBACA. Ia tidak menulis ke Firebase maupun Supabase.
//
// PEMAKAIAN
//   export CF_BASE='https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net'
//   export FB_ID_TOKEN='<ID token Firebase>'      # DevTools → Application → IndexedDB,
//                                                 # atau await auth.currentUser.getIdToken()
//   export SUPABASE_URL='https://<ref>.supabase.co'
//   export SUPABASE_ANON_KEY='sb_publishable_...'
//   export SB_ACCESS_TOKEN='<access_token Supabase>'   # dari localStorage
//                                                      # kunci 'koperasi-kita-auth'
//   export CABANG='panti'
//   export BULAN='2026-08'
//   node scripts/verification/bandingkan_api.mjs
//
// ⚠ Kedua token itu KREDENSIAL. Berikan lewat env, jangan sebagai argumen
//   (argumen terlihat di `ps` dan tersimpan di riwayat shell), dan jangan
//   sekali-kali menempelkannya ke berkas di repo.
//
// PEMBACAAN HASIL
//   SAMA    → angkanya identik.
//   BEDA    → berhenti. Ini justru alasan skrip ini ada.
//   LEWAT   → salah satu sisi tidak menyediakannya (mis. getBukuPokok, 025 §3).
// =========================================================================

const E = process.env;
const wajib = ['CF_BASE', 'FB_ID_TOKEN', 'SUPABASE_URL', 'SUPABASE_ANON_KEY', 'SB_ACCESS_TOKEN'];
const hilang = wajib.filter((k) => !E[k]);
if (hilang.length) {
  console.error('FATAL: env belum lengkap:', hilang.join(', '));
  process.exit(2);
}
const CABANG = E.CABANG || '';
const BULAN = E.BULAN || new Date().toISOString().slice(0, 7);

const HASIL = [];
const catat = (fungsi, medan, lama, baru) => {
  const norm = (v) => (typeof v === 'number' ? v : v == null ? null : String(v));
  const status = lama === undefined || baru === undefined
    ? 'LEWAT'
    : JSON.stringify(norm(lama)) === JSON.stringify(norm(baru)) ? 'SAMA' : 'BEDA';
  HASIL.push({ fungsi, medan, lama, baru, status });
};

async function cf(nama, params = {}) {
  const url = new URL(`${E.CF_BASE}/${nama}`);
  for (const [k, v] of Object.entries(params)) if (v) url.searchParams.set(k, v);
  const r = await fetch(url, { headers: { Authorization: `Bearer ${E.FB_ID_TOKEN}` } });
  if (!r.ok) throw new Error(`CF ${nama} → HTTP ${r.status}`);
  return r.json();
}

// PostgREST langsung. `Prefer: count=exact` dipakai supaya jumlah baris dibaca
// dari header Content-Range, bukan dari panjang array — array selalu terpotong
// batas halaman PostgREST (1000), dan itu yang membuat pemeriksaan pengawas di
// 018 §4b tidak tuntas.
async function sb(path, { count = false } = {}) {
  const r = await fetch(`${E.SUPABASE_URL}/rest/v1/${path}`, {
    headers: {
      apikey: E.SUPABASE_ANON_KEY,
      Authorization: `Bearer ${E.SB_ACCESS_TOKEN}`,
      'Accept-Profile': 'koperasi',
      ...(count ? { Prefer: 'count=exact', Range: '0-0' } : {}),
    },
  });
  if (!r.ok) throw new Error(`SB ${path} → HTTP ${r.status} ${await r.text()}`);
  if (count) {
    const cr = r.headers.get('content-range') || '';      // "0-0/1234"
    return { total: Number(cr.split('/')[1] ?? NaN) };
  }
  return r.json();
}

async function rpc(nama, body) {
  const r = await fetch(`${E.SUPABASE_URL}/rest/v1/rpc/${nama}`, {
    method: 'POST',
    headers: {
      apikey: E.SUPABASE_ANON_KEY,
      Authorization: `Bearer ${E.SB_ACCESS_TOKEN}`,
      'Content-Profile': 'koperasi',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body ?? {}),
  });
  if (!r.ok) throw new Error(`RPC ${nama} → HTTP ${r.status} ${await r.text()}`);
  return r.json();
}

const jml = (arr, f) => (arr || []).reduce((s, x) => s + Number(f(x) || 0), 0);

async function aman(nama, fn) {
  try { await fn(); }
  catch (e) { HASIL.push({ fungsi: nama, medan: '(gagal)', lama: '', baru: e.message, status: 'BEDA' }); }
}

// ------------------------------------------------------------------ uji ---
await aman('getBukuPokokSummary', async () => {
  const a = await cf('getBukuPokokSummary');
  const cabang = await sb('cabang?select=id');
  catat('getBukuPokokSummary', 'user.role', a.data.user.role, undefined);
  catat('getBukuPokokSummary', 'jumlah cabang terlihat', a.data.cabangList.length, cabang.length);
  console.log(`  · peran CF = ${a.data.user.role}; cabang terlihat CF = ${a.data.cabangList.length}`);
  console.log('    (bandingkan manual dengan hasil getBukuPokokSummary() di apiSupabase.js —');
  console.log('     penyaringan per peran ada di sisi klien, bukan di PostgREST)');
});

await aman('getPembayaranHariIni', async () => {
  const tgl = new Date(Date.now() + 7 * 3600e3).toISOString().slice(0, 10);
  const a = await cf('getPembayaranHariIni', { cabangId: CABANG, tanggal: tgl });
  const b = await sb(`v_pembayaran_harian?tanggal=eq.${tgl}` +
    (CABANG ? `&cabang_id=eq.${CABANG}` : '') + '&select=jenis,jumlah,banyak_transaksi');
  catat('getPembayaranHariIni', 'grandTotal', a.data.summary.grandTotal, jml(b, (x) => x.jumlah));
  catat('getPembayaranHariIni', 'totalTransaksi', a.data.summary.totalTransaksi,
    jml(b, (x) => x.banyak_transaksi));
});

await aman('getKasirEntries', async () => {
  const a = await cf('getKasirEntries', { cabangId: CABANG, bulan: BULAN });
  const b = await sb(`v_kasir_entry?cabang_id=eq.${CABANG}&periode=eq.${BULAN}-01` +
    '&select=arah,nominal');
  const masuk = jml(b.filter((x) => x.arah === 'masuk'), (x) => x.nominal);
  const keluar = jml(b.filter((x) => x.arah === 'keluar'), (x) => x.nominal);
  catat('getKasirEntries', 'totalEntries', a.data.totalEntries, b.length);
  catat('getKasirEntries', 'summary.totalMasuk', a.data.summary?.totalMasuk, masuk);
  catat('getKasirEntries', 'summary.totalKeluar', a.data.summary?.totalKeluar, keluar);
  catat('getKasirEntries', 'saldo', (a.data.summary?.totalMasuk ?? 0) - (a.data.summary?.totalKeluar ?? 0),
    masuk - keluar);
});

await aman('getJurnalTransaksi', async () => {
  const a = await cf('getJurnalTransaksi', { cabangId: CABANG, bulan: BULAN });
  const akhir = new Date(Date.UTC(+BULAN.slice(0, 4), +BULAN.slice(5, 7), 0))
    .toISOString().slice(0, 10);
  const b = await sb(`v_jurnal_transaksi?cabang_id=eq.${CABANG}` +
    `&tanggal=gte.${BULAN}-01&tanggal=lte.${akhir}&select=id`, { count: true });
  catat('getJurnalTransaksi', 'totalEntries',
    a.data.totalEntries ?? a.data.entries?.length, b.total);
});

await aman('getKoreksiStorting', async () => {
  const a = await cf('getKoreksiStorting', { cabangId: CABANG, bulan: BULAN });
  const b = await sb(`v_koreksi_storting?cabang_id=eq.${CABANG}&periode=eq.${BULAN}-01` +
    '&select=admin_id,cm,l1,mb,ml');
  catat('getKoreksiStorting', 'jumlah admin dikoreksi',
    Object.keys(a.data.koreksi || {}).length, b.length);
  for (const r of b) {
    const lama = (a.data.koreksi || {})[r.admin_id];
    catat('getKoreksiStorting', `admin ${r.admin_id.slice(0, 8)} cm+l1+mb+ml`,
      lama ? lama.cm + lama.l1 + lama.mb + lama.ml : undefined,
      Number(r.cm) + Number(r.l1) + Number(r.mb) + Number(r.ml));
  }
});

// getBukuPokok sengaja TIDAK dibandingkan — padanannya belum ada (025 §3).
HASIL.push({
  fungsi: 'getBukuPokok', medan: '(belum dipindahkan)',
  lama: '', baru: 'lihat 025 §3', status: 'LEWAT',
});

// --------------------------------------------------------------- keluar ---
const lebar = (k, min) => Math.max(min, ...HASIL.map((h) => String(h[k]).length));
const w = { fungsi: lebar('fungsi', 8), medan: lebar('medan', 6) };
console.log('\n| ' + 'Fungsi'.padEnd(w.fungsi) + ' | ' + 'Medan'.padEnd(w.medan) +
  ' | Cloud Function |      Supabase | Status |');
for (const h of HASIL) {
  console.log('| ' + String(h.fungsi).padEnd(w.fungsi) + ' | ' + String(h.medan).padEnd(w.medan) +
    ' | ' + String(h.lama ?? '').padStart(14) + ' | ' + String(h.baru ?? '').padStart(13) +
    ' | ' + h.status.padEnd(6) + ' |');
}

const beda = HASIL.filter((h) => h.status === 'BEDA');
console.log(`\nSAMA=${HASIL.filter((h) => h.status === 'SAMA').length} ` +
  `BEDA=${beda.length} LEWAT=${HASIL.filter((h) => h.status === 'LEWAT').length}`);
if (beda.length) {
  console.log('\n✗ Ada selisih. JANGAN alihkan halaman sebelum tiap barisnya dijelaskan.');
  process.exit(1);
}
console.log('\n✓ Tidak ada selisih pada medan yang diperiksa.');
console.log('  Ini BUKAN bukti seluruh datanya sama — hanya medan di atas yang diuji.');
