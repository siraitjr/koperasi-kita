// lib/apiSupabase.js
// =========================================================================
// Pengganti lib/api.js — BLOK 1 (D-4, 021 §2).
// =========================================================================
// `lib/api.js` TIDAK diubah dan tetap ada sampai pembalikan selesai (aturan
// repo: jangan hapus yang masih mungkin dipanggil). Berkas ini menyediakan
// fungsi ber-NAMA SAMA dan ber-BENTUK KEMBALIAN SAMA, sehingga halaman cukup
// mengganti satu baris impor:
//
//     - import { getSummary, … } from '../../lib/api';
//     + import { getSummary, … } from '../../lib/apiSupabase';
//
// TRANSPORT: tidak ada lagi `Authorization: Bearer <Firebase ID token>`.
// Klien Supabase memasang access token sesi sendiri pada setiap permintaan
// PostgREST, jadi tidak ada header yang perlu disusun tangan. Yang menjaga
// datanya RLS (002, dipercepat 017/018), bukan gerbang peran di server
// aplikasi seperti pada Cloud Functions.
//
// `getBukuPokok` ada di bagian PALING BAWAH berkas ini — ditulis terakhir
// karena ia buku besar, dan tiga celahnya (G-1 rekapBeku, G-2 foto,
// G-3 riwayat/top-up) harus diselesaikan lebih dulu. Lihat 025 §3 dan 027.
// =========================================================================

import { supabase } from './supabaseClient';
import { profilSaya } from './authSupabase';

const BULAN_INDO = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun',
                    'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

// Cermin JENIS_LABELS (kasirApi.js:43-52). Disalin apa adanya — halaman
// memakainya sebagai label kolom, jadi satu huruf berbeda pun terlihat.
const JENIS_LABELS = {
  uang_kas: 'Kasbon Pagi',
  penggajian: 'BU',
  transport: 'Transport',
  suntikan_dana: 'Suntikan Dana',
  pinjaman_kas: 'Pinjaman Kas',
  sp: 'SP',
  saldo_awal_kas: 'Saldo Awal Kas',
  pengembalian_kas: 'Pengembalian Kas',
};

/** "dd MMM yyyy" — format tampilan yang dipakai seluruh sistem (CLAUDE.md §9.1). */
function tglIndo(iso) {
  if (!iso) return '';
  const [y, m, d] = String(iso).slice(0, 10).split('-');
  if (!y || !m || !d) return '';
  return `${d} ${BULAN_INDO[parseInt(m, 10) - 1]} ${y}`;
}

/** Hari ini menurut WIB, "YYYY-MM-DD". Padanan getTodayIndonesia(). */
function hariIniWIB() {
  const wib = new Date(Date.now() + 7 * 3600 * 1000);
  return wib.toISOString().slice(0, 10);
}

function lempar(error, konteks) {
  throw new Error(`${konteks}: ${error.message || 'kesalahan tidak dikenal'}`);
}

// =========================================================================
// SUMMARY / PROFIL
// =========================================================================

/**
 * Padanan getBukuPokokSummary (bukuPokokApi.js:1014).
 * Bentuk kembalian dijaga sama: { success, data: { user, cabangList, today } }.
 */
export async function getBukuPokokSummary() {
  const saya = await profilSaya();

  // `cabang` di sini memuat pimpinan_id; `app_user` memberi daftar adminnya.
  // Dua query, bukan join, supaya RLS masing-masing tabel bekerja apa adanya.
  const { data: cabang, error: e1 } = await supabase
    .from('cabang')
    .select('id, nama, pimpinan_id')
    .order('id');
  if (e1) lempar(e1, 'getBukuPokokSummary/cabang');

  const { data: staf, error: e2 } = await supabase
    .from('app_user')
    .select('id, nama, email, role, cabang_id, aktif')
    .eq('aktif', true);
  if (e2) lempar(e2, 'getBukuPokokSummary/app_user');

  const namaOleh = Object.fromEntries((staf || []).map((u) => [u.id, u.nama || u.email || u.id]));

  const cabangList = (cabang || []).map((c) => ({
    id: c.id,
    name: c.nama || c.id,
    pimpinanUid: c.pimpinan_id || '',
    pimpinanName: namaOleh[c.pimpinan_id] || '',
    admins: (staf || [])
      .filter((u) => u.cabang_id === c.id && u.role === 'admin')
      .map((u) => ({ uid: u.id, name: u.nama || u.email || u.id, email: u.email || '' })),
  }));

  // Penyaringan per peran — cermin bukuPokokApi.js:1061-1071.
  // Tidak ada penyaringan tambahan untuk kasir_wilayah, sekretaris, pengawas,
  // dan koordinator: keempatnya memang melihat semua, dan itu perilaku lama
  // yang sengaja dipertahankan (002 §1 cabang_terlihat).
  let terlihat = cabangList;
  if (saya.role === 'admin') {
    terlihat = cabangList.filter((c) => c.admins.some((a) => a.uid === saya.id));
  } else if (saya.role === 'pimpinan') {
    terlihat = cabangList.filter((c) => c.pimpinanUid === saya.id);
  } else if (saya.role === 'kasir_unit') {
    terlihat = cabangList.filter((c) => c.id === saya.cabang_id);
  }

  return {
    success: true,
    data: {
      user: { uid: saya.id, name: saya.nama, role: saya.role, cabang: saya.cabang_id },
      cabangList: terlihat,
      today: hariIniWIB(),
    },
  };
}

/** Alias — `lib/api.js:81` memetakan getSummary ke endpoint yang sama. */
export const getSummary = getBukuPokokSummary;

// =========================================================================
// PEMBAYARAN HARI INI
// =========================================================================

/** Padanan getPembayaranHariIni (bukuPokokApi.js:1099). */
export async function getPembayaranHariIni({ cabangId, tanggal }) {
  const tgl = tanggal || hariIniWIB();

  let q = supabase
    .from('v_pembayaran_harian')
    .select('pinjaman_id, nasabah_id, cabang_id, admin_id, tanggal, jenis, jumlah, banyak_transaksi')
    .eq('tanggal', tgl);
  if (cabangId) q = q.eq('cabang_id', cabangId);

  const { data, error } = await q;
  if (error) lempar(error, 'getPembayaranHariIni');

  const baris = data || [];
  const jml = (j) => baris.filter((b) => b.jenis === j).reduce((s, b) => s + Number(b.jumlah || 0), 0);

  const totalCicilan = jml('cicilan');
  const totalTambahBayar = jml('tambah_bayar');
  const totalPelunasanSisaUtang = jml('pelunasan');

  return {
    success: true,
    data: {
      tanggal: tgl,
      cabangId: cabangId || '',
      payments: baris,
      summary: {
        totalTransaksi: baris.reduce((s, b) => s + Number(b.banyak_transaksi || 0), 0),
        totalCicilan,
        totalTambahBayar,
        totalPelunasanSisaUtang,
        grandTotal: totalCicilan + totalTambahBayar + totalPelunasanSisaUtang,
      },
    },
  };
}

// =========================================================================
// KASIR
// =========================================================================

/** Padanan getKasirSummary (kasirApi.js:165). */
export async function getKasirSummary() {
  const saya = await profilSaya();

  // Gerbang peran kasirApi.js:188 — enam peran, MENOLAK `admin`.
  const BOLEH = ['kasir_unit', 'kasir_wilayah', 'sekretaris', 'pimpinan', 'koordinator', 'pengawas'];
  if (!BOLEH.includes(saya.role)) {
    throw new Error('Akses Kasir ditolak untuk peran ini');
  }

  const ringkas = await getBukuPokokSummary();
  const cabangList = ringkas.data.cabangList;
  const bulan = hariIniWIB().slice(0, 7);

  const { data: entri, error } = await supabase
    .from('v_kasir_entry')
    .select('cabang_id, arah, nominal')
    .eq('periode', `${bulan}-01`);
  if (error) lempar(error, 'getKasirSummary/entri');

  const summary = {};
  for (const c of cabangList) {
    const milik = (entri || []).filter((e) => e.cabang_id === c.id);
    const totalMasuk = milik.filter((e) => e.arah === 'masuk')
      .reduce((s, e) => s + Number(e.nominal || 0), 0);
    const totalKeluar = milik.filter((e) => e.arah === 'keluar')
      .reduce((s, e) => s + Number(e.nominal || 0), 0);
    summary[c.id] = { totalMasuk, totalKeluar, saldo: totalMasuk - totalKeluar };
  }

  return {
    success: true,
    data: {
      user: ringkas.data.user,
      cabangList,
      summary,
      today: hariIniWIB(),
      currentMonth: bulan,
      jenisLabels: JENIS_LABELS,
    },
  };
}

/** Padanan getKasirEntries (kasirApi.js:266). */
export async function getKasirEntries({ cabangId, bulan }) {
  const periode = `${bulan || hariIniWIB().slice(0, 7)}-01`;

  const { data, error } = await supabase
    .from('v_kasir_entry')
    .select('*')
    .eq('cabang_id', cabangId)
    .eq('periode', periode)
    .order('tanggal', { ascending: true });
  if (error) lempar(error, 'getKasirEntries');

  // Nama field dikembalikan ke bentuk lama supaya halaman tidak perlu diubah.
  // `jumlah` (RTDB) = `nominal` (Postgres); `source` menandai entri otomatis
  // dan dipakai kasir/page.js:1320 untuk lencana "Auto".
  const entries = (data || []).map((e) => ({
    id: e.id,
    jenis: e.jenis,
    arah: e.arah,
    jumlah: Number(e.nominal || 0),
    keterangan: e.keterangan || '',
    tanggal: tglIndo(e.tanggal),
    createdBy: e.dicatat_oleh || '',
    createdByName: e.dicatat_oleh_nama || '',
    targetAdminUid: e.target_admin_id || '',
    notaUrl: e.nota_path || '',
    source: e.keterangan?.startsWith('Operasional ') ? 'operasional_harian' : '',
  }));

  const totalMasuk = entries.filter((e) => e.arah === 'masuk').reduce((s, e) => s + e.jumlah, 0);
  const totalKeluar = entries.filter((e) => e.arah === 'keluar').reduce((s, e) => s + e.jumlah, 0);

  return {
    success: true,
    data: {
      cabangId,
      bulan: bulan || hariIniWIB().slice(0, 7),
      entries,
      summary: { totalMasuk, totalKeluar, saldo: totalMasuk - totalKeluar },
      totalEntries: entries.length,
      jenisLabels: JENIS_LABELS,
    },
  };
}

/**
 * Unggah nota/faktur ke bucket `nota-kasir` (003 §3.3) dan kembalikan PATH-nya.
 *
 * Dipanggil SEBELUM addKasirEntry, bukan sesudah — beda dari alur lama yang
 * membuat entri dulu lalu menempelkan URL-nya. Alasannya: `kasir_entry` tidak
 * punya jalur UPDATE sama sekali (015 B-4 hanya menyediakan insert dan soft
 * delete), jadi menempel belakangan mustahil tanpa RPC baru. Menaruh unggahan
 * di depan juga menghilangkan keadaan "entri ada, notanya menyusul".
 *
 * Ongkosnya: kalau insert entri gagal sesudah unggahan sukses, berkasnya
 * jadi yatim. Itu tukar-tambah yang disengaja — berkas yatim murah, catatan
 * uang tanpa nota tidak.
 */
export async function unggahNota({ file, cabangId, periodeBulan, clientOpId }) {
  // 003 §3.1 menamai berkas dengan kasir_entry_id. Di sini dipakai
  // client_op_id karena entrinya belum ada saat unggahan terjadi — dan
  // client_op_id memang kunci unik entri itu nantinya.
  const path = `${cabangId}/${periodeBulan}/${clientOpId}.jpg`;
  const { error } = await supabase.storage
    .from('nota-kasir')
    .upload(path, file, { contentType: 'image/jpeg', upsert: true });
  if (error) lempar(error, 'unggahNota');
  return path;
}

/**
 * URL bertanda tangan untuk menampilkan nota.
 *
 * Bucket `nota-kasir` PRIVATE (003 §2), jadi tidak ada URL permanen seperti
 * `getDownloadURL` Firebase. Setiap penayangan perlu URL bertanda tangan yang
 * kedaluwarsa — itu pengetatan yang disengaja: URL Firebase lama berlaku
 * selamanya bagi siapa pun yang pernah menyalinnya.
 */
export async function urlNota(path, detik = 3600) {
  if (!path) return '';
  const { data, error } = await supabase.storage
    .from('nota-kasir').createSignedUrl(path, detik);
  if (error) lempar(error, 'urlNota');
  return data?.signedUrl || '';
}

/** Padanan addKasirEntry (kasirApi.js:364) → rpc_tambah_kasir_entry (015 B-4). */
export async function addKasirEntry({
  jenis, arah, jumlah, keterangan, tanggal, targetAdminUid, clientOpId, notaPath,
}) {
  const saya = await profilSaya();

  // `client_op_id` WAJIB dan harus BERTAHAN saat percobaan ulang — itu yang
  // membuat idempotensinya bekerja (015 B-4). Membangkitkan ulang saat retry
  // akan menggandakan entri kas, jadi pemanggil sebaiknya menyimpannya.
  const opId = clientOpId || crypto.randomUUID();

  const { data, error } = await supabase.rpc('rpc_tambah_kasir_entry', {
    p: {
      cabang_id: saya.cabang_id,
      jenis,
      arah,
      nominal: Number(jumlah || 0),
      keterangan: keterangan || '',
      tanggal: tanggal || hariIniWIB(),
      target_admin_id: targetAdminUid || null,
      nota_path: notaPath || null,
      dicatat_oleh_nama: saya.nama || '',
      client_op_id: opId,
    },
  });
  if (error) lempar(error, 'addKasirEntry');
  return { success: true, data: { id: data, clientOpId: opId } };
}

/** Padanan deleteKasirEntry (kasirApi.js:489) → rpc_hapus_kasir_entry (SOFT DELETE). */
export async function deleteKasirEntry({ entryId, alasan }) {
  const { error } = await supabase.rpc('rpc_hapus_kasir_entry', {
    p_entry_id: entryId,
    p_alasan: alasan || '',
  });
  if (error) lempar(error, 'deleteKasirEntry');
  return { success: true };
}

/** Padanan syncOperasionalTransport (kasirApi.js:576) → RPC 015 B-4. */
export async function syncOperasionalTransport(tanggal) {
  const { data, error } = await supabase.rpc('rpc_sync_operasional_transport', {
    p_tanggal: tanggal || null,
  });
  if (error) lempar(error, 'syncOperasionalTransport');
  return { success: true, data: { id: data } };
}

// =========================================================================
// JURNAL & KOREKSI STORTING
// =========================================================================

/** Padanan getJurnalTransaksi (jurnalTransaksiApi.js:75). */
export async function getJurnalTransaksi({ cabangId, bulan, tipe, adminUid }) {
  const awal = `${bulan}-01`;
  const [y, m] = bulan.split('-').map(Number);
  const akhir = new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10);

  let q = supabase
    .from('v_jurnal_transaksi')
    .select('*')
    .gte('tanggal', awal)
    .lte('tanggal', akhir)
    .order('tanggal', { ascending: true });

  if (cabangId) q = q.eq('cabang_id', cabangId);
  if (tipe) q = q.eq('tipe', tipe);
  if (adminUid) q = q.eq('admin_id', adminUid);

  const { data, error } = await q;
  if (error) lempar(error, 'getJurnalTransaksi');

  return {
    success: true,
    data: { cabangId, bulan, entries: data || [], totalEntries: (data || []).length },
  };
}

// backfillJurnalTransaksi SENGAJA TIDAK ADA DI SINI.
// Keputusan pemilik (021 §2): alat sesekali, bukan fitur harian. Tombolnya
// dihapus dari web dan dicatat di CHANGELOG sebagai ditinggalkan dengan
// sengaja — supaya enam bulan lagi tidak dikira hilang karena kelalaian
// migrasi. Kalau nanti dibutuhkan, buat RPC baru, jangan hidupkan jalur
// Cloud Function.

/** Padanan getKoreksiStorting (koreksiStorting.js:43). */
export async function getKoreksiStorting({ cabangId, bulan }) {
  const { data, error } = await supabase
    .from('v_koreksi_storting')
    .select('*')
    .eq('cabang_id', cabangId)
    .eq('periode', `${bulan}-01`);
  if (error) lempar(error, 'getKoreksiStorting');

  // Bentuk lama: peta adminUid → {cm,l1,mb,ml}.
  const koreksi = {};
  for (const r of data || []) {
    koreksi[r.admin_id] = {
      cm: Number(r.cm || 0), l1: Number(r.l1 || 0),
      mb: Number(r.mb || 0), ml: Number(r.ml || 0),
    };
  }
  return { success: true, data: { cabangId, bulan, koreksi } };
}

/** Padanan setKoreksiStorting (koreksiStorting.js:85) → rpc_set_koreksi_storting. */
export async function setKoreksiStorting({ cabangId, adminUid, bulan, l1, cm, mb, ml }) {
  const { error } = await supabase.rpc('rpc_set_koreksi_storting', {
    p: {
      cabang_id: cabangId,
      admin_id: adminUid,
      periode: `${bulan}-01`,
      cm: Number(cm || 0), l1: Number(l1 || 0),
      mb: Number(mb || 0), ml: Number(ml || 0),
    },
  });
  if (error) lempar(error, 'setKoreksiStorting');
  return { success: true };
}

// =========================================================================
// BUKU POKOK — bagian tersulit, ditulis paling akhir (025 §3)
// =========================================================================
// Padanan getBukuPokok (bukuPokokApi.js:325-1006, ±850 baris).
//
// Sebagian besar berkas aslinya BUKAN logika bisnis, melainkan siasat
// terhadap bentuk RTDB: menelusuri `riwayat_pinjaman` untuk generasi lama,
// meratakan `pembayaranList` bercelah, dan memindahkan pelunasan top-up ke
// baris historis (:551-611). Ketiganya lenyap di sini karena generasi SUDAH
// berupa baris (001 §3) — itu memang inti perubahan skemanya.
//
// Yang TIDAK lenyap dan harus dibawa apa adanya: `rekapBeku`. Lihat §G-1.

/** generateHariKerja (bukuPokokApi.js:285) — lewati Minggu, N hari ke belakang. */
function generateHariKerja(jumlahHari = 60) {
  const out = [];
  const wib = new Date(Date.now() + 7 * 3600 * 1000);
  const cur = new Date(wib);
  while (out.length < jumlahHari) {
    if (cur.getUTCDay() !== 0) {          // 0 = Minggu
      const dd = String(cur.getUTCDate()).padStart(2, '0');
      out.push(`${dd} ${BULAN_INDO[cur.getUTCMonth()]} ${cur.getUTCFullYear()}`);
    }
    cur.setUTCDate(cur.getUTCDate() - 1);
  }
  return out;
}

/**
 * Padanan getBukuPokok. Bentuk kembalian dijaga sama persis:
 *   { success, type:'buku_pokok', data:{ nasabah[], tanggalList, adminNames,
 *     today, totalNasabah, totalSisaUtang, totalPinjaman, pembayaranHariIni,
 *     targetHarianHariIni, orphanPaymentsByDate, rekapBeku } }
 */
export async function getBukuPokok({ cabangId, adminUid, status, bulan }) {
  const today = hariIniWIB();

  // -------------------------------------------------------------- baris ---
  // `v_buku_pokok` sudah satu baris per GENERASI pinjaman, sudah menyaring
  // nasabah terarsip, dan sudah menghitung is_historis/is_lunas/is_aktif.
  let q = supabase.from('v_buku_pokok').select('*');
  if (cabangId) q = q.eq('cabang_id', cabangId);
  if (adminUid) q = q.eq('admin_id', adminUid);
  if (status === 'aktif') q = q.eq('is_aktif', true);
  else if (status === 'lunas') q = q.eq('is_lunas', true);
  else if (status === 'menunggu_pencairan') q = q.eq('is_sisa_tabungan', true);

  const { data: baris, error: e1 } = await q;
  if (e1) lempar(e1, 'getBukuPokok/v_buku_pokok');
  const rows = baris || [];

  // Kolom top-up ada di `pinjaman` (001:318-320), tidak diekspos view.
  const pinjamanIds = rows.map((r) => r.pinjaman_id);
  let topUp = {};
  if (pinjamanIds.length) {
    const { data: pj, error: e2 } = await supabase
      .from('pinjaman')
      .select('id, sisa_utang_lama_sebelum_top_up, besar_pinjaman_lama_sebelum_top_up')
      .in('id', pinjamanIds);
    if (e2) lempar(e2, 'getBukuPokok/pinjaman');
    topUp = Object.fromEntries((pj || []).map((p) => [p.id, p]));
  }

  // --------------------------------------------------------- pembayaran ---
  // Diambil dari tabel, bukan v_pembayaran_harian: yang terakhir sudah
  // teragregat per (pinjaman, tanggal, jenis) sehingga `keterangan` per
  // transaksi hilang — padahal itulah yang muncul di kolom rincian.
  let bayar = [];
  if (pinjamanIds.length) {
    const { data, error } = await supabase
      .from('pembayaran')
      .select('pinjaman_id, tanggal, jumlah, jenis, keterangan')
      .in('pinjaman_id', pinjamanIds)
      .order('tanggal', { ascending: true });
    if (error) lempar(error, 'getBukuPokok/pembayaran');
    bayar = data || [];
  }

  const bayarPer = {};   // pinjaman_id → { "dd MMM yyyy": {total, entries[]} }
  for (const b of bayar) {
    const tgl = tglIndo(b.tanggal);
    const m = (bayarPer[b.pinjaman_id] ||= {});
    (m[tgl] ||= { total: 0, entries: [] });
    m[tgl].total += Number(b.jumlah || 0);
    m[tgl].entries.push({
      jumlah: Number(b.jumlah || 0),
      keterangan: b.keterangan || '',
      type: b.jenis || 'cicilan',
    });
  }

  // ------------------------------------------------------------- G-1 -----
  // rekapBeku — nilai BEKU dari 026. Bukan hiasan: ia meng-OVERRIDE kolom
  // Target & Storting historis di Buku Rekap. Tanpa ini angka historis
  // dihitung ulang dan bergeser dari cetakan lama (bukuPokokApi.js:951).
  const adminIds = [...new Set(rows.map((r) => r.admin_id).filter(Boolean))];
  const rekapBeku = {};
  if (adminIds.length) {
    const { data: beku, error } = await supabase
      .from('v_rekap_harian_beku')
      .select('admin_id, tanggal_indo, target, storting')
      .in('admin_id', adminIds);
    if (error) lempar(error, 'getBukuPokok/rekapBeku');
    for (const r of beku || []) {
      (rekapBeku[r.admin_id] ||= {})[r.tanggal_indo] = {
        target: Number(r.target || 0),
        storting: Number(r.storting || 0),
      };
    }
  }

  // ------------------------------------------------------- susun nasabah ---
  // Generasi TERTINGGI per nasabah = baris berjalan; sisanya historis.
  const maxKe = {};
  for (const r of rows) {
    maxKe[r.nasabah_id] = Math.max(maxKe[r.nasabah_id] ?? -1, Number(r.pinjaman_ke || 0));
  }
  const perNasabah = {};
  for (const r of rows) (perNasabah[r.nasabah_id] ||= []).push(r);
  for (const k of Object.keys(perNasabah)) {
    perNasabah[k].sort((a, b) => Number(a.pinjaman_ke) - Number(b.pinjaman_ke));
  }

  const adminNames = {};
  const nasabahList = [];

  for (const r of rows) {
    if (r.is_historis) continue;                 // hanya generasi berjalan
    if (r.admin_id) adminNames[r.admin_id] = r.admin_nama || '';

    const semua = perNasabah[r.nasabah_id] || [];
    const lama = semua.filter((x) => Number(x.pinjaman_ke) < Number(r.pinjaman_ke));
    const tu = topUp[r.pinjaman_id] || {};
    const sisaUtangLamaTopUp = Number(tu.sisa_utang_lama_sebelum_top_up || 0);

    const pembayaran = { ...(bayarPer[r.pinjaman_id] || {}) };

    // Relokasi pelunasan top-up (bukuPokokApi.js:566-611).
    // Di RTDB ini rumit karena generasi lama hanya ada sebagai arsip. Di sini
    // generasi lama adalah BARIS, jadi entrinya ditaruh di baris itu — persis
    // paritas buku fisik yang dikejar komentar aslinya: pelunasan tercatat di
    // halaman pinjaman LAMA, bukan halaman pinjaman baru.
    if (sisaUtangLamaTopUp > 0 && Number(r.pinjaman_ke) > 1 && lama.length) {
      const terakhir = lama[lama.length - 1];
      const tglPelunasan = tglIndo(terakhir.tanggal_lunas_cicilan) || tglIndo(r.tanggal_pencairan);
      if (tglPelunasan) {
        const m = (bayarPer[terakhir.pinjaman_id] ||= {});
        (m[tglPelunasan] ||= { total: 0, entries: [] });
        m[tglPelunasan].total += sisaUtangLamaTopUp;
        m[tglPelunasan].entries.push({
          jumlah: sisaUtangLamaTopUp,
          keterangan: 'Pelunasan sisa utang (top-up)',
          type: 'pelunasan_sisa_utang',
        });
      }
    }

    // Anchor target hari Cairkan top-up (bukuPokokApi.js:625-635).
    let besarPinjamanLamaSebelumTopUp = 0;
    if (tglIndo(r.tanggal_pencairan) === tglIndo(today) && Number(r.pinjaman_ke) > 1) {
      besarPinjamanLamaSebelumTopUp =
        Number(tu.besar_pinjaman_lama_sebelum_top_up || 0) ||
        Number(lama.length ? lama[lama.length - 1].besar_pinjaman : 0);
    }

    nasabahList.push({
      id: r.nasabah_id,
      namaKtp: r.nama_ktp || '',
      namaPanggilan: r.nama_panggilan || '',
      nik: '',                                   // tidak diekspos view (020a)
      nomorAnggota: r.nomor_anggota || '',
      pinjamanKe: Number(r.pinjaman_ke || 1),
      besarPinjaman: Number(r.besar_pinjaman || 0),
      totalPelunasan: Number(r.total_pelunasan || 0),
      totalDibayar: Number(r.total_dibayar || 0),
      sisaUtang: Number(r.sisa_utang || 0),
      tenor: Number(r.tenor || 0),
      status: r.status || '',
      statusKhusus: r.status_khusus || '',
      statusPencairanSimpanan: '',
      tanggalStatusKhusus: '',
      tanggalLunasCicilan: tglIndo(r.tanggal_lunas_cicilan),
      tanggalDaftar: tglIndo(r.tanggal_daftar),
      tanggalPencairan: tglIndo(r.tanggal_pencairan),
      tanggalPengajuan: tglIndo(r.tanggal_daftar),
      adminUid: r.admin_id || '',
      adminName: r.admin_nama || '',
      cabangId: r.cabang_id || '',
      wilayah: r.wilayah || '',
      simpanan: 0,                               // diisi di bawah
      tarikTabungan: Number(r.tarik_tabungan || 0),
      totalDiterima: Number(r.total_diterima || 0),
      pembayaran,

      // G-2 — UTANG. Migrasi Firebase Storage belum selesai (021 §0), jadi
      // seluruh URL foto dikosongkan. Halaman menampilkan placeholder, bukan
      // gambar rusak. JANGAN diisi dengan URL Firebase: host itu ikut mati.
      fotoKtpUrl: '', fotoKtpSuamiUrl: '', fotoKtpIstriUrl: '',
      fotoNasabahUrl: '', fotoSerahTerimaUrl: '',

      // G-3 — dari baris generasi lama, bukan dari `pinjaman_history`.
      // `riwayat_pinjaman` di RTDB ada JUSTRU karena satu nasabah = satu
      // record; di sini generasi lama sudah berupa baris tersendiri.
      sisaUtangLama: lama.reduce((s, x) => s + Number(x.sisa_utang || 0), 0),
      sisaUtangLamaSebelumTopUp: sisaUtangLamaTopUp,
      besarPinjamanLamaSebelumTopUp,
      riwayatPinjaman: lama.map((x) => ({
        pinjamanKe: Number(x.pinjaman_ke || 0),
        besarPinjaman: Number(x.besar_pinjaman || 0),
        totalPelunasan: Number(x.total_pelunasan || 0),
        totalDibayar: Number(x.total_dibayar || 0),
        sisaUtang: Number(x.sisa_utang || 0),
        tenor: Number(x.tenor || 0),
        tanggalPencairan: tglIndo(x.tanggal_pencairan),
        tanggalLunasCicilan: tglIndo(x.tanggal_lunas_cicilan),
        pembayaran: bayarPer[x.pinjaman_id] || {},
      })),
    });
  }

  // Simpanan per nasabah — satu query, bukan N.
  const nasabahIds = nasabahList.map((n) => n.id);
  if (nasabahIds.length) {
    const { data: simp, error } = await supabase
      .from('simpanan').select('nasabah_id, jumlah').in('nasabah_id', nasabahIds);
    if (error) lempar(error, 'getBukuPokok/simpanan');
    const per = {};
    for (const s of simp || []) per[s.nasabah_id] = (per[s.nasabah_id] || 0) + Number(s.jumlah || 0);
    for (const n of nasabahList) n.simpanan = per[n.id] || 0;
  }

  // ------------------------------------------------------------- total ---
  const todayIndo = tglIndo(today);
  const pembayaranHariIni = nasabahList.reduce(
    (s, n) => s + (n.pembayaran[todayIndo]?.total || 0), 0);

  // Target hari ini dari jadwal cicilan yang jatuh tempo. Di RTDB angka ini
  // datang dari `summary.targetHariIni` (summaryHelpers.js); keduanya belum
  // tentu identik — lihat 026 §VERIFIKASI no. 4.
  let targetHarianHariIni = 0;
  if (pinjamanIds.length) {
    const { data: jd, error } = await supabase
      .from('jadwal_cicilan').select('jumlah')
      .in('pinjaman_id', pinjamanIds).eq('tanggal', today);
    if (error) lempar(error, 'getBukuPokok/jadwal');
    targetHarianHariIni = (jd || []).reduce((s, x) => s + Number(x.jumlah || 0), 0);
  }

  // orphanPaymentsByDate — pembayaran milik nasabah yang sudah DIARSIPKAN
  // (padanan pelanggan terhapus di RTDB, mis. setelah cairkanSimpanan).
  // Hanya dihitung bila `bulan` diminta, sama seperti CF (lib/api.js:88-92:
  // cuma BukuRekap yang mengirim parameter ini).
  const orphanPaymentsByDate = {};
  if (bulan) {
    const [yy, mm] = bulan.split('-').map(Number);
    const akhir = new Date(Date.UTC(yy, mm, 0)).toISOString().slice(0, 10);
    let qa = supabase.from('nasabah').select('id').not('arsip_at', 'is', null);
    if (cabangId) qa = qa.eq('cabang_id', cabangId);
    const { data: arsip, error } = await qa;
    if (error) lempar(error, 'getBukuPokok/orphan');
    if (arsip?.length) {
      const { data: op } = await supabase
        .from('v_pembayaran_harian')
        .select('tanggal, jumlah, nasabah_id')
        .in('nasabah_id', arsip.map((a) => a.id))
        .gte('tanggal', `${bulan}-01`).lte('tanggal', akhir);
      for (const p of op || []) {
        const t = tglIndo(p.tanggal);
        orphanPaymentsByDate[t] = (orphanPaymentsByDate[t] || 0) + Number(p.jumlah || 0);
      }
    }
  }

  return {
    success: true,
    type: 'buku_pokok',
    data: {
      nasabah: nasabahList,
      tanggalList: generateHariKerja(60),
      adminNames,
      today,
      totalNasabah: nasabahList.length,
      totalSisaUtang: nasabahList.reduce((s, n) => s + n.sisaUtang, 0),
      totalPinjaman: nasabahList.reduce((s, n) => s + n.besarPinjaman, 0),
      pembayaranHariIni,
      targetHarianHariIni,
      orphanPaymentsByDate,
      rekapBeku,
    },
  };
}

// =========================================================================
// REKENING KORAN — pembuat tautan v2 (BLOK 4)
// =========================================================================

/**
 * Minta tautan rekening koran bertanda tangan v2 dari Edge Function
 * `rekening-koran-link` (020).
 *
 * Kunci HMAC TIDAK PERNAH masuk bundel web — server yang menandatangani.
 * Itu sekaligus memutus ketergantungan pada APK lama, yang selama ini jadi
 * satu-satunya pembuat tautan dan menanam kuncinya di dalam APK
 * (RekeningKoranHelper.kt:34).
 *
 * ⚠ CORS: Edge Function memakai daftar putih origin
 * (`https://www.koperasi-kita.com`, `https://koperasi-kita.com`).
 * `http://localhost:3000` dan URL preview `*.vercel.app` TIDAK ada di
 * daftar itu, jadi pemanggilan dari sana akan ditolak browser. Tambahkan
 * origin-nya di ALLOWED_ORIGINS sebelum menguji di luar production.
 */
export async function buatTautanRekeningKoran(nasabahId) {
  const { data: sesi } = await supabase.auth.getSession();
  const token = sesi?.session?.access_token;
  if (!token) throw new Error('Belum login');

  const base = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const res = await fetch(`${base}/functions/v1/rekening-koran-link`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      apikey: process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ nasabahId }),
  });

  const body = await res.json().catch(() => ({}));
  if (!res.ok || !body?.success) {
    throw new Error(body?.error || `Gagal membuat tautan (HTTP ${res.status})`);
  }
  return body.data;   // { url, expiresAt, ttlDays }
}
