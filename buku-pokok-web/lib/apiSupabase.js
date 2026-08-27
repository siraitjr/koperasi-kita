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
// ⚠ `getBukuPokok` TIDAK ADA DI SINI — sengaja. Lihat 025 §3.
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

/** Padanan addKasirEntry (kasirApi.js:364) → rpc_tambah_kasir_entry (015 B-4). */
export async function addKasirEntry({
  jenis, arah, jumlah, keterangan, tanggal, targetAdminUid, clientOpId,
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
