'use client';

// app/kasir/page.js
// =========================================================================
// KASIR WEB - Jurnal Kasir & Akses Buku Pokok
// =========================================================================

import { useState, useEffect, useRef, useCallback } from 'react';
// BLOK 6 — halaman ini kini sepenuhnya Supabase.
// Impor firebase/auth, firebase/storage, dan firebase/database DIHAPUS karena
// tidak ada lagi yang memakainya di berkas ini: Blok 2/3 memindahkan absensi
// dan operasional, Blok 5 memindahkan nota. `lib/firebase.js` sendiri TIDAK
// dihapus — halaman lain masih mengimpornya untuk jalur SSO (024 §5, U-2).
import { pantauSesi, masuk, keluar } from '../../lib/authSupabase';
import { getKasirSummary, getKasirEntries, addKasirEntry, deleteKasirEntry, getBukuPokok, syncOperasionalTransport, getJurnalTransaksi } from '../../lib/apiSupabase';
import { formatRp, formatRpFull } from '../../lib/format';
import { isEligibleForTarget, isTanggalHistoris } from '../../lib/target';

// =========================================================================
// HELPER: Compress image for upload (max 1024px, JPEG quality 0.6)
// =========================================================================
function compressImage(file, maxSize = 1024, quality = 0.6) {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      const img = new Image();
      img.onload = () => {
        const canvas = document.createElement('canvas');
        let w = img.width, h = img.height;
        if (w > h && w > maxSize) { h = Math.round(h * maxSize / w); w = maxSize; }
        else if (h > maxSize) { w = Math.round(w * maxSize / h); h = maxSize; }
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, w, h);
        canvas.toBlob((blob) => resolve(blob), 'image/jpeg', quality);
      };
      img.src = e.target.result;
    };
    reader.readAsDataURL(file);
  });
}

// =========================================================================
// HELPER: Persist cabang aktif lintas sub-screen (shared sessionStorage).
//
// Tiap sub-screen (Jurnal, Buku Rekap, Kas Penuntun, Buku Tunai, Buku
// Ekspedisi, Ringkasan, Absensi) punya state lokal `activeCabang` sendiri.
// Saat pimpinan ganti cabang di satu screen lalu pindah screen via setScreen,
// komponen sub-screen baru remount & re-init `useState` → balik ke default
// (cabangList[0] / Cabang A). Untuk menjaga pilihan tetap "lengket", semua
// sub-screen membaca & menulis key bersama `ksp_active_cabang_id` (key yang
// sama dengan persistence parent di commit c4efa1c).
// =========================================================================
const ACTIVE_CABANG_KEY = 'ksp_active_cabang_id';

function readActiveCabangId() {
  if (typeof window === 'undefined') return null;
  try { return sessionStorage.getItem(ACTIVE_CABANG_KEY); } catch (e) { return null; }
}

function writeActiveCabangId(id) {
  if (typeof window === 'undefined' || !id) return;
  try { sessionStorage.setItem(ACTIVE_CABANG_KEY, id); } catch (e) { /* ignore */ }
}

// Resolusi cabang awal untuk sub-screen: prioritaskan pilihan tersimpan
// (sessionStorage) supaya tetap konsisten saat pindah screen via setScreen,
// fallback ke prop `cabang` dari parent, lalu default cabang pertama.
function resolveInitialCabang(cabang, cabangList) {
  const savedId = readActiveCabangId();
  if (savedId && Array.isArray(cabangList)) {
    const saved = cabangList.find(c => c.id === savedId);
    if (saved) return saved;
  }
  return cabang || (cabangList && cabangList[0]) || null;
}

// Set activeCabang lokal + persist ke sessionStorage agar semua sub-screen
// berbagi pilihan yang sama.
function selectCabangById(cabangList, id, setActiveCabang) {
  const next = (Array.isArray(cabangList) ? cabangList.find(c => c.id === id) : null) || null;
  setActiveCabang(next);
  if (next) writeActiveCabangId(next.id);
}

// =========================================================================
// CONSTANTS
// =========================================================================
const JENIS_OPTIONS = [
  { value: 'uang_kas', label: 'Kasbon Pagi' },
  { value: 'penggajian', label: 'BU' },
  { value: 'transport', label: 'Transport' },
  { value: 'suntikan_dana', label: 'Suntikan Dana' },
  { value: 'pinjaman_kas', label: 'Pinjaman Kas' },
  { value: 'saldo_awal_kas', label: 'Saldo Kas Bulan Lalu' },
  { value: 'sp', label: 'SP' },
  { value: 'pengembalian_kas', label: 'Pengembalian Kas' },
];

const JENIS_ARAH = {
  uang_kas: 'keluar',
  penggajian: 'keluar',
  transport: 'keluar',
  suntikan_dana: 'masuk',
  pinjaman_kas: 'masuk',
  sp: 'keluar',
  pengembalian_kas: 'keluar',
  saldo_awal_kas: 'masuk',
};


const BULAN_INDO = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

function getCurrentMonthKey() {
  const now = new Date();
  const jakartaOffset = 7 * 60;
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const jakarta = new Date(utc + (jakartaOffset * 60000));
  const yyyy = jakarta.getFullYear();
  const mm = String(jakarta.getMonth() + 1).padStart(2, '0');
  return `${yyyy}-${mm}`;
}

function getTodayIndo() {
  const now = new Date();
  const jakartaOffset = 7 * 60;
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const jakarta = new Date(utc + (jakartaOffset * 60000));
  const dd = String(jakarta.getDate()).padStart(2, '0');
  const mmm = BULAN_INDO[jakarta.getMonth()];
  const yyyy = jakarta.getFullYear();
  return `${dd} ${mmm} ${yyyy}`;
}

function formatBulanLabel(bulanKey) {
  const [y, m] = bulanKey.split('-');
  return `${BULAN_INDO[parseInt(m) - 1]} ${y}`;
}

// =========================================================================
// HOLIDAY UTILS — sama persis dengan HolidaysUtils.kt di Android
// Cek berdasarkan hari + bulan saja (tidak cek tahun), sesuai Kotlin
// =========================================================================
const LIBUR_NASIONAL = [
  [1, 1],   // Jan 1  — Tahun Baru
  [16, 1],  // Jan 16 — Hari pertama Ramadan (2026)
  [17, 2],  // Feb 17 — Isra Mikraj
  [19, 3],  // Mar 19 — Hari Raya Nyepi
  [21, 3],  // Mar 21 — Hari Raya Idul Fitri
  [3, 4],   // Apr 3  — Hari Raya Idul Fitri (cuti bersama)
  [1, 5],   // Mei 1  — Hari Buruh
  [14, 5],  // Mei 14 — Kenaikan Isa Almasih
  [27, 5],  // Mei 27 — Hari Raya Waisak
  [1, 6],   // Jun 1  — Hari Lahir Pancasila
  [16, 6],  // Jun 16 — Hari Raya Idul Adha
  [17, 8],  // Agu 17 — HUT RI
  [25, 8],  // Agu 25 — Maulid Nabi
  [25, 12], // Des 25 — Hari Natal
];

function isTanggalMerah(date) {
  const d = date.getDate();
  const m = date.getMonth() + 1; // 1-based, sama seperti Kotlin
  return LIBUR_NASIONAL.some(([ld, lm]) => ld === d && lm === m);
}

function isMinggu(date) {
  return date.getDay() === 0; // 0 = Sunday
}

function isHariKerja(date) {
  return !isMinggu(date) && !isTanggalMerah(date);
}

// Parse string "07 Feb 2026" ke Date object (WIB — tidak ada timezone shift)
function parseTanggalIndo(s) {
  if (!s) return null;
  const parts = s.split(' ');
  if (parts.length !== 3) return null;
  const bulanIdx = BULAN_INDO.indexOf(parts[1]);
  if (bulanIdx === -1) return null;
  return new Date(parseInt(parts[2]), bulanIdx, parseInt(parts[0]));
}

function generateBulanOptions() {
  const options = [];
  const now = new Date();
  const jakartaOffset = 7 * 60;
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const jakarta = new Date(utc + (jakartaOffset * 60000));
  for (let i = 0; i < 4; i++) {
    const d = new Date(jakarta.getFullYear(), jakarta.getMonth() - i, 1);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    const label = `${BULAN_INDO[d.getMonth()]} ${d.getFullYear()}`;
    options.push({ key, label });
  }
  return options;
}

// =========================================================================
// Tunai Pasar & Kas Pakai per tanggal — Source of Truth: Buku Rekap.
//
// Wajib dipakai Kas Penuntun, Buku Ekspedisi, dan helper saldoKasBulanLalu
// agar nilai cocok rupiah-per-rupiah dengan "Total Hari Ini" Buku Rekap.
//
// Logika identik dengan baris per-resort di BukuRekapScreen: hitung debit
// & kredit PER RESORT, ambil max(debit-kredit, 0) untuk tunaiPasar dan
// max(kredit-debit, 0) untuk kasPakai per resort, lalu jumlahkan.
//
// JANGAN pakai agregat global (storting/drop seluruh cabang lalu hitung
// selisih) — bila satu resort surplus dan resort lain defisit, agregat
// global akan saling meng-offset dan hasilnya bisa beda tanda/jumlah dari
// per-resort sum. Bug ini ada di BukuEkspedisi versi lama.
//
// Caller di-harapkan membangun `nasabahByAdmin` (object keyed by adminUid)
// satu kali di luar loop tanggal — perf-friendly untuk iterasi banyak hari.
// =========================================================================
// pencairanByAdminDate (opsional): { [tanggal]: { [adminUid]: total } } dari
// buildPencairanByAdminDate(). Bila diberikan, pencairan tabungan ikut sebagai
// KREDIT (uang keluar) → mengurangi tunaiPasar, sama persis dengan rumus
// BukuRekap (kredit = totalDrop + pencairanTabungan). Bila tidak diberikan,
// pencairan dianggap 0 (perilaku lama).
//
// orphanByDate (opsional): bukuData.orphanPaymentsByDate dari getBukuPokok.
// Pembayaran "orphan" = pembayaran_harian yang pelangganId-nya sudah tidak ada
// di pelanggan/ (mis. setelah cairkanSimpanan). BukuRekap menambahkannya ke
// storting (kasir/page.js:1944-1949) → masuk debitAsli → mempengaruhi
// kasPakai/tunaiPasar. Sebelum patch ini helper TIDAK meng-akumulasi orphan
// → divergensi vs BukuRekap di hari yang punya nasabah dihapus
// (audit pimpinan 11 Jun 2026 "Buku Tunai vs Buku Rekap"). Dengan dipass,
// helper kini cocok rupiah-per-rupiah dgn baris per-resort BukuRekap.
// Shape: { [tanggal]: Array<{adminUid, jumlah, ...}> } (baru) atau
// { [tanggal]: { [adminUid]: jumlah } } (lama, back-compat).
// =========================================================================
function computeTunaiKasPerDate(dateStr, nasabahByAdmin, admins, pencairanByAdminDate, orphanByDate, rekapBeku, serverToday) {
  let totalTunaiPasar = 0, totalKasPakai = 0;
  for (const adm of (admins || [])) {
    const resortNasabah = (nasabahByAdmin && nasabahByAdmin[adm.uid]) || [];
    let totalStorting = 0, totalDrop = 0;
    resortNasabah.forEach(n => {
      const pay = n.pembayaran?.[dateStr];
      if (pay) totalStorting += pay.total || 0;
      // Parity Buku Rekap (pimpinan 11 Jun 2026): pembayaran yang direlokasi CF
      // ke baris pinjaman lama tinggal di n.riwayatPinjaman[].pembayaran (lihat
      // computeRekapRows storting & bukuPokokApi.js L565-595). Helper ini WAJIB
      // cocok rupiah-per-rupiah dgn "Total Hari Ini" Buku Rekap (L202), jadi
      // storting di sini harus meng-sum jalur riwayat yang sama.
      (n.riwayatPinjaman || []).forEach(r => {
        const rPay = r.pembayaran?.[dateStr];
        if (rPay) totalStorting += rPay.total || 0;
      });
      if ((n.tanggalPencairan || '').trim() === dateStr) totalDrop += n.besarPinjaman || 0;
    });
    // ✅ Orphan storting (pimpinan 11 Jun 2026 "Buku Tunai vs Buku Rekap"):
    // pembayaran dari nasabah yang sudah dihapus (mis. via cairkanSimpanan).
    // BukuRekap menambahkannya ke storting per resort (lihat L1944-1949) → ikut
    // ke debitAsli & kasPakai/tunaiPasar. Helper ini sebelumnya TIDAK meng-
    // akumulasi orphan → BukuTunai/KasPenuntun/BukuEkspedisi (yang lewat helper)
    // beda dgn BukuRekap di hari ber-orphan. Patch ini mirror jalur BukuRekap
    // persis, termasuk back-compat shape lama { adminUid: total }.
    if (orphanByDate) {
      const orphanArr = orphanByDate[dateStr];
      if (orphanArr) {
        const orphanStortingAdm = Array.isArray(orphanArr)
          ? orphanArr.reduce((s, e) => (e && e.adminUid === adm.uid ? s + (e.jumlah || 0) : s), 0)
          : (orphanArr?.[adm.uid] || 0);
        totalStorting += orphanStortingAdm;
      }
    }
    // =====================================================================
    // ✅ PARITY BUKU REKAP (Rule 3) — snapshot rekap_harian_final = OTORITAS
    // untuk tanggal HISTORIS. Mirror PERSIS computeRekapRows L2016-2023:
    //   • Tanggal historis (kalender < serverToday) → storting di-override
    //     dari snapshot per-admin → debitAsli/kasPakai/tunaiPasar imun mutasi
    //     retroaktif, sama persis dengan baris Buku Rekap.
    //   • Hari berjalan → isTanggalHistoris false → tetap live recompute.
    //   • Snapshot tidak ada utk (adm,dateStr) → fallback ke live totalStorting
    //     (identik fallback Buku Rekap).
    // Strictly additive: caller lama yang TIDAK mengirim rekapBeku/serverToday
    // (mis. Kas Penuntun saldoKasBulanLalu) → blok ini dilewati → perilaku
    // 100% sama seperti sebelumnya.
    // =====================================================================
    if (rekapBeku && serverToday) {
      const bekuEntry = isTanggalHistoris(dateStr, serverToday) && rekapBeku[adm.uid]
        ? rekapBeku[adm.uid][dateStr]
        : null;
      if (bekuEntry) totalStorting = bekuEntry.storting || 0;
    }
    const adminFee = Math.round(totalDrop * 0.05);
    const tabungan = Math.round(totalDrop * 0.05);
    const debitAsli = totalStorting + adminFee + tabungan;
    const pencairan = (pencairanByAdminDate && pencairanByAdminDate[dateStr] && pencairanByAdminDate[dateStr][adm.uid]) || 0;
    const kreditVal = totalDrop + pencairan;
    totalTunaiPasar += debitAsli >= kreditVal ? debitAsli - kreditVal : 0;
    totalKasPakai += kreditVal > debitAsli ? kreditVal - debitAsli : 0;
  }
  return { tunaiPasar: totalTunaiPasar, kasPakai: totalKasPakai };
}

// =========================================================================
// Pencairan tabungan per (tanggal, admin) dari jurnal_transaksi.
// tipe yang dihitung (semua adalah uang kas keluar → kredit di Tunai Pasar):
//   - 'pelunasan_tabungan'        → sisa utang dilunasi via tabungan
//   - 'pencairan_simpanan_partial' → tarik sebagian simpanan
//   - 'tarik_tabungan'            → kelebihan kas fisik dikembalikan ke
//                                     customer setelah cairkanSimpanan
// Dipakai sebagai kredit di computeTunaiKasPerDate & kolom "Cair Tab." Buku
// Rekap. Output: { [tanggal]: { [adminUid]: total } }.
// =========================================================================
function buildPencairanByAdminDate(jurnalEntries) {
  const map = {};
  (jurnalEntries || []).forEach(e => {
    if (e.tipe !== 'pelunasan_tabungan'
        && e.tipe !== 'pencairan_simpanan_partial'
        && e.tipe !== 'tarik_tabungan') return;
    const tgl = e.tanggal;
    if (!tgl) return;
    const uid = e.adminUid || '';
    if (!map[tgl]) map[tgl] = {};
    map[tgl][uid] = (map[tgl][uid] || 0) + (e.jumlah || 0);
  });
  return map;
}

// =========================================================================
// CATATAN (16 Jun 2026): helper buildTarikTabunganByAdminDate DIHAPUS.
// Dulu menyuplai jurnal tipe 'tarik_tabungan' ke kolom "Tarik Tab." Buku Rekap.
// Itu KELIRU per aturan bisnis: 'tarik_tabungan' (kelebihan kas saat likuidasi)
// adalah event "Cair Tabungan", bukan "Tarik Tabungan" (penahanan dari pinjaman
// baru). Kolom "Tarik Tab." kini HANYA dari n.tarikTabungan pinjaman baru;
// 'tarik_tabungan' tetap masuk "Cair Tab." via buildPencairanByAdminDate.
// =========================================================================

// =========================================================================
// Dekomposisi physical cash per resort/admin (Source of Truth: BukuTunai).
//
// totalFisik = kasbonPagi + tunaiPasar - kasPakai (fisik dibawa pulang admin).
// Aturan:
//  - kasbonPagi == 0           → kembaliKasbon=0, titipan=tunaiPasar.
//  - totalFisik >= kasbonPagi  → kembaliKasbon=kasbonPagi, titipan=totalFisik-kasbonPagi.
//  - totalFisik <  kasbonPagi  → kembaliKasbon=0, titipan=totalFisik.
//
// Dipakai per-resort di BukuTunaiScreen (langsung di tabel) dan per-admin di
// BukuEkspedisiScreen (akumulasi ke total harian). HARUS pakai helper ini di
// kedua tempat agar parity rupiah-per-rupiah dengan BukuTunai dijamin.
// =========================================================================
function decomposeKembaliKasbonTitipan(kasbonPagi, tunaiPasar, kasPakai) {
  const safePagi = kasbonPagi || 0;
  const safePasar = tunaiPasar || 0;
  const safePakai = kasPakai || 0;
  const totalFisik = safePagi + safePasar - safePakai;
  if (safePagi === 0) {
    return { kembaliKasbon: 0, titipan: safePasar, totalFisik };
  }
  if (totalFisik >= safePagi) {
    return { kembaliKasbon: safePagi, titipan: totalFisik - safePagi, totalFisik };
  }
  return { kembaliKasbon: 0, titipan: totalFisik, totalFisik };
}

// =========================================================================
// Saldo Kas Bulan Lalu — helper bersama untuk Kas Penuntun & Buku Ekspedisi
// agar nilai keduanya pasti identik (rupiah-for-rupiah).
//
// Priority 1 (override manual): bila ada entry kasir di bulan berjalan dengan
//   jenis === 'saldo_awal_kas', pakai jumlah-nya langsung (input pimpinan).
// Priority 2 (carry-forward otomatis): bila tidak ada input manual, hitung
//   saldo akhir "Tunai Kas" pada hari kerja TERAKHIR bulan sebelumnya memakai
//   logika running balance IDENTIK Buku Ekspedisi:
//     Daily In  = Kembali Kasbon + Tunai Pasar + Suntikan Dana + Pinjaman Kas
//     Daily Out = Kasbon Pagi + Transport + BU + SP + Pengembalian Kas
//   Tunai Pasar sudah termasuk pencairan tabungan (jurnal bulan sebelumnya).
//   Seed replay = saldo_awal_kas manual bulan sebelumnya bila ada, else 0
//   (carry-forward dibatasi 1 bulan agar hemat RTDB; rantai lebih panjang
//   di-seed lewat input manual saldo_awal_kas pada bulan ybs).
//
// Wajib di-pass kasirEntries (bulan berjalan & sebelumnya) + jurnalEntries
// bulan sebelumnya. Bila data belum siap (bukuData/nasabah kosong), return 0.
// =========================================================================
function computeSaldoKasBulanLalu({ bukuData, currentMonthEntries, prevMonthEntries, prevMonthJurnalEntries, bulan, activeCabang }) {
  if (!bukuData?.nasabah || !bulan) return 0;

  // Priority 1: override manual (input pimpinan)
  const saldoAwalEntry = (currentMonthEntries || []).find(e => e.jenis === 'saldo_awal_kas');
  if (saldoAwalEntry) return saldoAwalEntry.jumlah || 0;

  // Priority 2: carry-forward otomatis — replay running balance bulan
  // sebelumnya dengan logika IDENTIK Buku Ekspedisi.
  const allNasabah = bukuData.nasabah;
  const admins = activeCabang?.admins || [];
  const prevEntries = prevMonthEntries || [];

  const BULAN_MAP_REV = {};
  BULAN_INDO.forEach((b, i) => { BULAN_MAP_REV[b] = i; });
  const parseDateStr = (s) => {
    if (!s) return null;
    const parts = s.split(' ');
    if (parts.length !== 3) return null;
    const m = BULAN_MAP_REV[parts[1]];
    if (m === undefined) return null;
    return new Date(parseInt(parts[2]), m, parseInt(parts[0]));
  };

  const nasabahByAdmin = {};
  admins.forEach(adm => {
    nasabahByAdmin[adm.uid] = allNasabah.filter(n => n.adminUid === adm.uid);
  });
  // Pencairan tabungan bulan sebelumnya → kredit tunaiPasar (parity Buku Ekspedisi).
  const prevPencairanByAdminDate = buildPencairanByAdminDate(prevMonthJurnalEntries);

  const [yyyy, mm] = bulan.split('-');
  const prevMonthStart = new Date(parseInt(yyyy), parseInt(mm) - 2, 1);
  const prevMonthEnd = new Date(parseInt(yyyy), parseInt(mm) - 1, 0);

  const prevDateSet = new Set();
  allNasabah.forEach(n => {
    if (n.pembayaran) {
      Object.keys(n.pembayaran).forEach(d => {
        const date = parseDateStr(d);
        if (date && date >= prevMonthStart && date <= prevMonthEnd) prevDateSet.add(d);
      });
    }
    const tglCair = (n.tanggalPencairan || '').trim();
    if (tglCair) {
      const date = parseDateStr(tglCair);
      if (date && date >= prevMonthStart && date <= prevMonthEnd) prevDateSet.add(tglCair);
    }
  });
  prevEntries.forEach(e => {
    const tgl = e.tanggal;
    if (!tgl) return;
    const date = parseDateStr(tgl);
    if (date && date >= prevMonthStart && date <= prevMonthEnd) prevDateSet.add(tgl);
  });

  const prevSortedDates = Array.from(prevDateSet)
    .filter(d => { const dt = parseDateStr(d); return dt && isHariKerja(dt); })
    .sort((a, b) => parseDateStr(a) - parseDateStr(b));

  // Kasbon per (tanggal, admin) — untuk dekomposisi kembaliKasbon per-admin.
  const kasbonByAdminPerDate = {};
  prevEntries.forEach(e => {
    if (e.jenis !== 'uang_kas' || e.arah !== 'keluar' || !e.targetAdminUid) return;
    const tgl = e.tanggal;
    if (!tgl) return;
    const date = parseDateStr(tgl);
    if (!date || date < prevMonthStart || date > prevMonthEnd) return;
    if (!kasbonByAdminPerDate[tgl]) kasbonByAdminPerDate[tgl] = {};
    kasbonByAdminPerDate[tgl][e.targetAdminUid] =
      (kasbonByAdminPerDate[tgl][e.targetAdminUid] || 0) + (e.jumlah || 0);
  });

  // Agregat jurnal kasir per tanggal (mirror Buku Ekspedisi).
  const kasbonPerDate = {}, suntikanDanaPerDate = {}, pinjamanKasPerDate = {},
        transportPerDate = {}, buPerDate = {}, pengembalianPerDate = {}, spPerDate = {};
  prevEntries.forEach(e => {
    const tgl = e.tanggal;
    if (!tgl) return;
    const date = parseDateStr(tgl);
    if (!date || date < prevMonthStart || date > prevMonthEnd) return;
    const jumlah = e.jumlah || 0;
    if (e.jenis === 'uang_kas' && e.arah === 'keluar') {
      kasbonPerDate[tgl] = (kasbonPerDate[tgl] || 0) + jumlah;
    } else if (e.jenis === 'suntikan_dana' && e.arah === 'masuk') {
      suntikanDanaPerDate[tgl] = (suntikanDanaPerDate[tgl] || 0) + jumlah;
    } else if (e.jenis === 'pinjaman_kas' && e.arah === 'masuk') {
      pinjamanKasPerDate[tgl] = (pinjamanKasPerDate[tgl] || 0) + jumlah;
    } else if (e.jenis === 'transport' && e.arah === 'keluar') {
      transportPerDate[tgl] = (transportPerDate[tgl] || 0) + jumlah;
    } else if (e.jenis === 'penggajian' && e.arah === 'keluar') {
      const buku = e.targetBuku;
      if (!buku || (Array.isArray(buku) && buku.includes('ekspedisi'))) {
        buPerDate[tgl] = (buPerDate[tgl] || 0) + jumlah;
      }
    } else if (e.jenis === 'pengembalian_kas' && e.arah === 'keluar') {
      pengembalianPerDate[tgl] = (pengembalianPerDate[tgl] || 0) + jumlah;
    } else if (e.jenis === 'sp' && e.arah === 'keluar') {
      spPerDate[tgl] = (spPerDate[tgl] || 0) + jumlah;
    }
  });

  // Seed = saldo_awal_kas manual bulan sebelumnya bila ada, else 0.
  const prevSaldoAwalEntry = prevEntries.find(e => e.jenis === 'saldo_awal_kas');
  let running = prevSaldoAwalEntry ? (prevSaldoAwalEntry.jumlah || 0) : 0;

  prevSortedDates.forEach(dateStr => {
    let dayTunaiPasar = 0, dayKembali = 0;
    for (const adm of admins) {
      const kasbonPagiAdm = kasbonByAdminPerDate[dateStr]?.[adm.uid] || 0;
      // Parity Buku Rekap (Rule 3): snapshot rekap_harian_final = otoritas utk
      // tanggal historis. Seed bulan-lalu seluruhnya historis (< serverToday) →
      // storting beku dipakai bila ada. orphanByDate dibiarkan undefined agar
      // perilaku seed lama TIDAK berubah (scope additive — hanya tambah snapshot).
      const { tunaiPasar, kasPakai } = computeTunaiKasPerDate(dateStr, nasabahByAdmin, [adm], prevPencairanByAdminDate, undefined, bukuData?.rekapBeku, bukuData?.today);
      // ✅ STRICT (parity Buku Tunai L2947): kembaliKasbon = kasbonPagi − kasPakai,
      // tanpa clamping. Seed carry-forward bulan-lalu kini full strict end-to-end
      // (pimpinan 16 Jun 2026) agar replay running-balance konsisten dgn kolom
      // strict bulan berjalan (BukuEkspedisi/KasPenuntun), tidak lagi waterfall.
      const kembaliKasbon = kasbonPagiAdm - kasPakai;
      dayTunaiPasar += tunaiPasar;
      dayKembali += kembaliKasbon;
    }
    const kasbonPagi = kasbonPerDate[dateStr] || 0;
    const suntikanDana = suntikanDanaPerDate[dateStr] || 0;
    const pinjamanKas = pinjamanKasPerDate[dateStr] || 0;
    const dropPusat = suntikanDana + pinjamanKas;
    const transport = transportPerDate[dateStr] || 0;
    const bu = buPerDate[dateStr] || 0;
    const pengembalianKas = pengembalianPerDate[dateStr] || 0;
    const sp = spPerDate[dateStr] || 0;
    // Daily In  = Kembali Kasbon + Tunai Pasar + Suntikan Dana + Pinjaman Kas
    // Daily Out = Kasbon Pagi + Transport + BU + SP + Pengembalian Kas
    const dailyIn = dayKembali + dayTunaiPasar + dropPusat;
    const dailyOut = kasbonPagi + transport + bu + sp + pengembalianKas;
    running = running + dailyIn - dailyOut;
  });

  return running;
}


// =========================================================================
// MAIN COMPONENT
// =========================================================================
export default function KasirPage() {
  const [user, setUser] = useState(null);
  const [userData, setUserData] = useState(null);
  const [screen, setScreen] = useState('loading');
  const [cabangList, setCabangList] = useState([]);
  const [summaryData, setSummaryData] = useState({});
  const [jenisLabels, setJenisLabels] = useState({});
  const [selectedCabang, setSelectedCabang] = useState(null);
  const [showLogoutModal, setShowLogoutModal] = useState(false);

  // ✅ FIX persist cabang lintas-menu: simpan id cabang terpilih ke
  // sessionStorage setiap berubah. Navigasi antar-menu (Buku Pokok ⇄ Buku
  // Rekap/Ekspedisi/Tunai/Kas Penuntun) menyeberang route /kasir ⇄
  // /pembukuan lewat window.location (full reload → remount → state lokal
  // reset). Key bersama `ksp_active_cabang_id` dibaca saat mount KEDUA
  // halaman, sehingga pilihan cabang multi-branch role (kasir_wilayah,
  // koordinator, pengawas, sekretaris, pimpinan) tidak hilang.
  useEffect(() => {
    if (selectedCabang?.id) {
      try { sessionStorage.setItem('ksp_active_cabang_id', selectedCabang.id); } catch (e) { /* ignore */ }
    }
  }, [selectedCabang]);

  // ==================== AUTH STATE ====================
  useEffect(() => {
    const unsubscribe = pantauSesi(async (pengguna) => {
      if (pengguna) {
        setUser(pengguna);
        try {
          const result = await getKasirSummary();
          if (result.success) {
            const d = result.data;
            setUserData(d.user);
            setCabangList(d.cabangList);
            setSummaryData(d.summary);
            setJenisLabels(d.jenisLabels || {});

            // ✅ FIX restore cabang lintas-menu: prioritaskan id tersimpan
            // (sessionStorage), fallback auto-select bila hanya 1 cabang.
            let initialCabang = null;
            try {
              const savedId = sessionStorage.getItem('ksp_active_cabang_id');
              if (savedId) initialCabang = d.cabangList.find(c => c.id === savedId) || null;
            } catch (e) { /* ignore */ }
            if (!initialCabang && d.cabangList.length === 1) initialCabang = d.cabangList[0];
            if (initialCabang) setSelectedCabang(initialCabang);

            // Cek parameter ?screen= dari pembukuan (untuk pimpinan/koordinator/pengawas)
            const urlParams = new URLSearchParams(window.location.search);
            const targetScreen = urlParams.get('screen');
            const validScreens = ['jurnal', 'bukuRekap', 'kasPenuntun', 'bukuTunai', 'bukuEkspedisi', 'ringkasan', 'absensi'];
            if (targetScreen && validScreens.includes(targetScreen)) {
              setScreen(targetScreen);
            } else {
              setScreen('home');
            }
          }
        } catch (err) {
          console.error('Failed to get kasir summary:', err);
          if (err.message && err.message.includes('Kasir')) {
            setScreen('forbidden');
          } else {
            setScreen('home');
          }
        }
      } else {
        setUser(null);
        setUserData(null);
        // Tidak ada login di /kasir, arahkan ke /pembukuan
        window.location.href = '/pembukuan';
        return;
      }
    });
    return () => unsubscribe();
  }, []);

  // ==================== HANDLERS ====================
  const handleLogin = async (email, password) => {
    await masuk(email, password);
  };

  const handleLogout = () => {
    // Hanya kasir_unit yang perlu absen sebelum logout
    if (userData?.role === 'kasir_unit') {
      setShowLogoutModal(true);
    } else {
      doLogout();
    }
  };

  const doLogout = async () => {
    setShowLogoutModal(false);
    await keluar();
  };

  const goToAbsensi = () => {
    setShowLogoutModal(false);
    setScreen('absensi');
  };

  // Untuk pimpinan/koordinator/pengawas yang datang dari pembukuan
  const isFromPembukuan = typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('screen');
  const VIEWER_ROLES = ['pimpinan', 'koordinator', 'pengawas', 'kasir_wilayah', 'sekretaris'];
  const isViewer = VIEWER_ROLES.includes(userData?.role);

  const handleKasirNavigate = (screenId) => {
    const kasirScreens = ['jurnal', 'bukuRekap', 'kasPenuntun', 'bukuTunai', 'bukuEkspedisi', 'ringkasan', 'absensi'];
    if (kasirScreens.includes(screenId)) {
      setScreen(screenId);
    } else if (screenId === 'bukuPerkembangan') {
      // ✅ Buku Perkembangan = halaman STANDALONE (/buku-perkembangan).
      // Bawa cabang aktif sbg query param agar halaman tujuan langsung resolve
      // tanpa picker. Prioritas: selectedCabang lokal → sessionStorage shared
      // (ACTIVE_CABANG_KEY) yang juga dibaca halaman tujuan sbg fallback.
      let cabId = selectedCabang?.id || '';
      if (!cabId) {
        try { cabId = sessionStorage.getItem(ACTIVE_CABANG_KEY) || ''; } catch (e) { /* ignore */ }
      }
      window.location.href = cabId
        ? `/buku-perkembangan?cabang=${encodeURIComponent(cabId)}`
        : '/buku-perkembangan';
    } else {
      // bukuPokok dan jurnalTransaksi ada di /pembukuan
      window.location.href = '/pembukuan';
    }
  };

  const handleBack = () => {
    if (isViewer && isFromPembukuan) {
      window.location.href = '/pembukuan';
    } else {
      setScreen('home');
    }
  };

  // ==================== RENDER ====================
  if (screen === 'loading') {
    return (
      <div className="page-container">
        <div className="loading-container">
          <div className="loading-spinner" />
          <p className="loading-text">Memuat...</p>
        </div>
      </div>
    );
  }

  if (screen === 'login') {
    return <LoginScreen onLogin={handleLogin} />;
  }

  let content;
  if (screen === 'forbidden') {
    content = <ForbiddenScreen onLogout={handleLogout} />;
  } else if (screen === 'jurnal') {
    content = (
      <JurnalScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'bukuRekap') {
    content = (
      <BukuRekapScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'kasPenuntun') {
    content = (
      <KasPenuntunScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'bukuTunai') {
    content = (
      <BukuTunaiScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'bukuEkspedisi') {
    content = (
      <BukuEkspedisiScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'ringkasan') {
    content = (
      <RingkasanScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else if (screen === 'absensi') {
    content = (
      <AbsensiScreen
        user={userData}
        cabang={selectedCabang}
        cabangList={cabangList}
        onBack={handleBack}
        onLogout={handleLogout}
        onNavigate={isViewer ? handleKasirNavigate : null}
      />
    );
  } else {
    content = (
      <HomeScreen
        user={userData}
        cabangList={cabangList}
        summaryData={summaryData}
        selectedCabang={selectedCabang}
        onSelectCabang={(c) => setSelectedCabang(c)}
        onNavigate={(s) => setScreen(s)}
        onLogout={handleLogout}
      />
    );
  }

  return (
    <>
      {content}
      {showLogoutModal && (
        <LogoutAbsensiModal
          onAbsen={goToAbsensi}
          onLogout={doLogout}
          onClose={() => setShowLogoutModal(false)}
        />
      )}
    </>
  );
}


// ============================================================
// LOGOUT ABSENSI MODAL
// ============================================================
function LogoutAbsensiModal({ onAbsen, onLogout, onClose }) {
  return (
    <div style={{
      position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
      background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center',
      justifyContent: 'center', zIndex: 9999, padding: 16
    }} onClick={onClose}>
      <div style={{
        background: '#fff', borderRadius: 16, padding: 24, maxWidth: 360,
        width: '100%', boxShadow: '0 20px 60px rgba(0,0,0,0.3)'
      }} onClick={e => e.stopPropagation()}>
        <h3 style={{ margin: '0 0 8px', fontSize: 18, fontWeight: 700, color: '#1e293b' }}>Keluar</h3>
        <p style={{ margin: '0 0 24px', color: '#64748b', fontSize: 14, lineHeight: 1.5 }}>
          Apakah Anda ingin absen terlebih dahulu sebelum keluar?
        </p>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <button onClick={onAbsen} style={{
            padding: '12px 16px', borderRadius: 10, border: 'none', cursor: 'pointer',
            background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', color: '#fff',
            fontWeight: 600, fontSize: 14, display: 'flex', alignItems: 'center',
            justifyContent: 'center', gap: 8
          }}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>
            </svg>
            Absen Dulu
          </button>
          <button onClick={onLogout} style={{
            padding: '12px 16px', borderRadius: 10, cursor: 'pointer',
            background: 'transparent', color: '#ef4444', fontWeight: 600, fontSize: 14,
            border: '1.5px solid #fca5a5', display: 'flex', alignItems: 'center',
            justifyContent: 'center', gap: 8
          }}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>
            </svg>
            Langsung Keluar
          </button>
          <button onClick={onClose} style={{
            padding: '8px 16px', borderRadius: 10, cursor: 'pointer',
            background: 'transparent', color: '#94a3b8', fontWeight: 500, fontSize: 13,
            border: 'none'
          }}>
            Batal
          </button>
        </div>
      </div>
    </div>
  );
}

// ============================================================
// KASIR TOP BAR NAVIGATION (shortcut menu di header)
// ============================================================
function KasirTopBarNav({ currentScreen, onNavigate }) {
  const menus = [
    { id: 'bukuPokok', label: 'Buku Pokok' },
    { id: 'bukuPerkembangan', label: 'Buku Perkembangan' },
    { id: 'jurnalTransaksi', label: 'Jurnal Transaksi' },
    { id: 'jurnal', label: 'Jurnal Kasir' },
    { id: 'bukuRekap', label: 'Buku Rekap' },
    { id: 'bukuTunai', label: 'Buku Tunai' },
    { id: 'kasPenuntun', label: 'Kas Penuntun' },
    { id: 'bukuEkspedisi', label: 'Buku Ekspedisi' },
    { id: 'ringkasan', label: 'Ringkasan Kas' },
    { id: 'absensi', label: 'Absensi' },
  ];

  return (
    <nav className="top-bar-nav">
      {menus.map((m) => (
        <button
          key={m.id}
          className={`top-bar-nav-item${currentScreen === m.id ? ' active' : ''}`}
          onClick={() => onNavigate(m.id)}
          disabled={currentScreen === m.id}
        >
          {m.label}
        </button>
      ))}
    </nav>
  );
}

// ============================================================
// LOGIN SCREEN (reuse pattern dari pembukuan)
// ============================================================
function LoginScreen({ onLogin }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!email || !password) { setError('Email dan password harus diisi'); return; }
    setLoading(true);
    setError('');
    try {
      await onLogin(email, password);
    } catch (err) {
      setError(
        err.code === 'auth/invalid-credential'
          ? 'Email atau password salah'
          : err.code === 'auth/too-many-requests'
          ? 'Terlalu banyak percobaan. Coba lagi nanti.'
          : 'Gagal login: ' + err.message
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-bg" />
      <div className="login-decor login-decor-1" />
      <div className="login-decor login-decor-2" />
      <div className="login-card">
        <div className="login-logo">
          <div className="login-logo-icon" style={{ background: '#000', padding: 0, overflow: 'hidden' }}>
            <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <h1>Kasir Koperasi Kita</h1>
          <p className="login-subtitle">Masuk untuk mengelola jurnal kasir</p>
        </div>

        {error && <div className="login-error"><span className="login-error-icon">!</span>{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="login-field">
            <label>Email</label>
            <div className="input-with-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/></svg>
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="kasir@godangulu.com" autoComplete="email" />
            </div>
          </div>
          <div className="login-field">
            <label>Password</label>
            <div className="input-with-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              <input type={showPassword ? 'text' : 'password'} value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" autoComplete="current-password" />
              <button type="button" onClick={() => setShowPassword(!showPassword)} className="eye-toggle">
                {showPassword ? (
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" x2="23" y1="1" y2="23"/></svg>
                ) : (
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                )}
              </button>
            </div>
          </div>
          <button type="submit" className="login-btn" disabled={loading}>
            {loading ? <span className="spinner" /> : 'Masuk'}
          </button>
        </form>
      </div>
    </div>
  );
}


// ============================================================
// FORBIDDEN SCREEN
// ============================================================
function ForbiddenScreen({ onLogout }) {
  return (
    <div className="page-container" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh' }}>
      <div style={{ textAlign: 'center', padding: 40 }}>
        <div style={{ fontSize: 48, marginBottom: 16 }}>🔒</div>
        <h2 style={{ fontSize: 20, fontWeight: 700, marginBottom: 8 }}>Akses Ditolak</h2>
        <p style={{ color: 'var(--text-muted)', marginBottom: 24 }}>Akun Anda bukan akun Kasir. Halaman ini hanya untuk Kasir Unit dan Kasir Wilayah.</p>
        <button onClick={onLogout} className="login-btn" style={{ maxWidth: 200, margin: '0 auto' }}>Keluar</button>
      </div>
    </div>
  );
}


// ============================================================
// HOME SCREEN (Dashboard Kasir)
// ============================================================
function HomeScreen({ user, cabangList, summaryData, selectedCabang, onSelectCabang, onNavigate, onLogout }) {
  const isUnit = user?.role === 'kasir_unit';
  const VIEWER_ROLES = ['pimpinan', 'koordinator', 'pengawas'];
  const isViewer = VIEWER_ROLES.includes(user?.role);
  const cabId = selectedCabang?.id || (cabangList.length === 1 && cabangList[0]?.id);
  const summary = cabId ? (summaryData[cabId] || {}) : {};

  const roleLabels = { kasir_unit: 'Kasir Unit', kasir_wilayah: 'Kasir Wilayah', sekretaris: 'Sekretaris', pimpinan: 'Pimpinan', koordinator: 'Koordinator', pengawas: 'Pengawas' };
  const roleLabel = roleLabels[user?.role] || user?.role;

  const menus = [
    {
      id: 'jurnal', name: 'Jurnal Kasir', desc: 'Catatan transaksi kas harian',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/><line x1="16" x2="8" y1="13" y2="13"/><line x1="16" x2="8" y1="17" y2="17"/><line x1="10" x2="8" y1="9" y2="9"/>
        </svg>
      ),
    },
    {
      id: 'bukuPokok', name: 'Buku Pokok', desc: 'Catatan pinjaman & pembayaran nasabah',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z"/><path d="M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"/><path d="M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"/>
          <path d="M10 6h4"/><path d="M10 10h4"/><path d="M10 14h4"/><path d="M10 18h4"/>
        </svg>
      ),
    },
        {
      id: 'bukuRekap', name: 'Buku Rekap', desc: 'Rekap harian per resort',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M3 3h18v18H3zM3 9h18M3 15h18M9 3v18M15 3v18"/>
        </svg>
      ),
    },
    {
      id: 'bukuTunai', name: 'Buku Tunai', desc: 'Rekap kasbon & tunai harian per resort',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <rect width="20" height="14" x="2" y="5" rx="2"/><line x1="2" x2="22" y1="10" y2="10"/>
          <path d="M12 15h.01M8 15h.01M16 15h.01"/>
        </svg>
      ),
    },
    {
      id: 'kasPenuntun', name: 'Kas Penuntun', desc: 'Buku kas harian penuntun',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M3 3h18v18H3zM3 9h18M3 15h18M9 3v18M15 3v18"/>
        </svg>
      ),
    },
    {
      id: 'bukuEkspedisi', name: 'Buku Ekspedisi', desc: 'Kas harian masuk & keluar ekspedisi',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" x2="8" y1="13" y2="13"/><line x1="16" x2="8" y1="17" y2="17"/><polyline points="10 9 9 9 8 9"/>
        </svg>
      ),
    },
    {
      id: 'ringkasan', name: 'Ringkasan Kas', desc: 'Rekapitulasi pemasukan & pengeluaran',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <path d="M21.21 15.89A10 10 0 1 1 8 2.83"/><path d="M22 12A10 10 0 0 0 12 2v10z"/>
        </svg>
      ),
    },
    {
      id: 'absensi', name: 'Absensi Karyawan', desc: 'Lihat & kelola absensi harian karyawan',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
          <rect width="18" height="18" x="3" y="4" rx="2" ry="2"/><line x1="16" x2="16" y1="2" y2="6"/><line x1="8" x2="8" y1="2" y2="6"/><line x1="3" x2="21" y1="10" y2="10"/>
          <path d="m9 16 2 2 4-4"/>
        </svg>
      ),
    },
  ];

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <div className="top-bar-logo" style={{ background: '#000', padding: 0, overflow: 'hidden' }}>
            <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <div>
            <h1>Koperasi Kita</h1>
            <p>Kasir — KSP Sigodang Ulu Jaya</p>
          </div>
        </div>
        <div className="top-bar-right">
          {user && (
            <div className="user-badge">
              <span className="user-name">{user.name}</span>
              <span className="user-role">{roleLabel}</span>
            </div>
          )}
          {isViewer ? (
            <button onClick={() => { window.location.href = '/pembukuan'; }} className="btn-icon" title="Kembali ke Pembukuan">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
            </button>
          ) : (
            <button onClick={onLogout} className="btn-icon" title="Keluar">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>
              </svg>
            </button>
          )}
        </div>
      </header>

      <main className="home-content fade-in">
        <div className="home-welcome">
          <div className="home-welcome-icon" style={{ background: '#000', padding: 0, overflow: 'hidden' }}>
            <img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
          </div>
          <h2>Selamat Datang, {user?.name || 'Kasir'}</h2>
          <p className="home-ksp-name">KSP Sigodang Ulu Jaya</p>
          {selectedCabang && <p className="home-user-greeting">Cabang: {selectedCabang.name}</p>}
        </div>

        {/* Kasir Wilayah: pilih cabang */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 20 }}>
            <div className="home-section-label">Pilih Cabang</div>
            <div className="cabang-grid">
              {cabangList.map((c) => (
                <button key={c.id} onClick={() => onSelectCabang(c)}
                  className="cabang-card" style={selectedCabang?.id === c.id ? { borderColor: 'var(--primary)', background: 'var(--primary-light)' } : {}}>
                  <div className="cabang-card-icon">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                      <path d="M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z"/><path d="M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"/><path d="M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"/>
                      <path d="M10 6h4"/><path d="M10 10h4"/><path d="M10 14h4"/><path d="M10 18h4"/>
                    </svg>
                  </div>
                  <div className="cabang-card-info">
                    <h3>{c.name}</h3>
                    <p>{c.admins?.length || 0} Resort</p>
                  </div>
                  {selectedCabang?.id === c.id && (
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" strokeWidth="2.5" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>
                  )}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Ringkasan cepat (hanya jika cabang sudah dipilih) */}
        {cabId && summary && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, margin: '0 0 24px' }}>
            <div style={{ background: 'var(--card)', borderRadius: 14, padding: '16px 20px', border: '1px solid var(--border)' }}>
              <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 4 }}>Total Masuk (Bulan Ini)</p>
              <p style={{ fontSize: 18, fontWeight: 700, color: 'var(--success)' }}>{formatRpFull(summary.totalMasuk || 0)}</p>
            </div>
            <div style={{ background: 'var(--card)', borderRadius: 14, padding: '16px 20px', border: '1px solid var(--border)' }}>
              <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 4 }}>Total Keluar (Bulan Ini)</p>
              <p style={{ fontSize: 18, fontWeight: 700, color: 'var(--danger)' }}>{formatRpFull(summary.totalKeluar || 0)}</p>
            </div>
          </div>
        )}

        <div className="home-section-label">Menu Kasir</div>
        <div className="book-grid">
          {menus.map((m) => (
            <button key={m.id} onClick={() => m.id === 'bukuPokok' ? (window.location.href = '/pembukuan?from=kasir') : onNavigate(m.id)} className="book-card">
              <div className="book-card-icon">{m.icon}</div>
              <div className="book-card-info">
                <h3>{m.name}</h3>
                <p>{m.desc}</p>
              </div>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><polyline points="9 18 15 12 9 6"/></svg>
            </button>
          ))}
        </div>
      </main>
    </div>
  );
}


// ============================================================
// JURNAL SCREEN
// ============================================================
function JurnalScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(() => resolveInitialCabang(cabang, cabangList));
  const [bulan, setBulan] = useState(getCurrentMonthKey());
  const [entries, setEntries] = useState([]);
  const [summary, setSummary] = useState({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  const bulanOptions = generateBulanOptions();

  const fetchEntries = async () => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    try {
      const result = await getKasirEntries({ cabangId: activeCabang.id, bulan });
      if (result.success) {
        setEntries(result.data.entries || []);
        setSummary(result.data.summary || {});
      }
    } catch (err) {
      setError('Gagal memuat data: ' + err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchEntries(); }, [activeCabang?.id, bulan]);

  const handleEntryAdded = () => {
    setShowForm(false);
    fetchEntries();
  };

  const handleDelete = async (entry) => {
    setDeleteLoading(true);
    try {
      await deleteKasirEntry({ cabangId: activeCabang.id, bulan, entryId: entry.id });
      setDeleteConfirm(null);
      fetchEntries();
    } catch (err) {
      alert('Gagal menghapus: ' + err.message);
    } finally {
      setDeleteLoading(false);
    }
  };

  // Group entries by tanggal
  const grouped = {};
  entries.forEach(e => {
    const tgl = e.tanggal || 'Tanpa Tanggal';
    if (!grouped[tgl]) grouped[tgl] = [];
    grouped[tgl].push(e);
  });
  const sortedDates = Object.keys(grouped).sort((a, b) => {
    // Sort descending by date (terbaru ke terlama)
    const pa = a.split(' '), pb = b.split(' ');
    const da = new Date(parseInt(pa[2]), BULAN_INDO.indexOf(pa[1]), parseInt(pa[0]));
    const db = new Date(parseInt(pb[2]), BULAN_INDO.indexOf(pb[1]), parseInt(pb[0]));
    return db - da;
  });

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Jurnal Kasir</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="jurnal" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          {isUnit && (
            <button onClick={() => setShowForm(true)} style={{
              background: 'var(--primary)', color: '#fff', padding: '8px 16px', borderRadius: 10,
              fontSize: 13, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6,
              transition: 'all 0.2s'
            }}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><line x1="12" x2="12" y1="5" y2="19"/><line x1="5" x2="19" y1="12" y2="12"/></svg>
              Tambah
            </button>
          )}
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: '20px 24px', maxWidth: 900, margin: '0 auto' }} className="fade-in">
        {/* Filters */}
        <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center' }}>
          {!isUnit && cabangList.length > 1 && (
            <select value={activeCabang?.id || ''} onChange={(e) => selectCabangById(cabangList, e.target.value, setActiveCabang)}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          <select value={bulan} onChange={(e) => setBulan(e.target.value)}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
        </div>

        {/* Summary cards */}
        {(() => {
          const saldoAwalAdj = entries.filter(e => e.jenis === 'saldo_awal_kas').reduce((s, e) => s + (e.jumlah || 0), 0);
          return (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 10, marginBottom: 20 }}>
              <div style={{ background: '#e8f8f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Total Masuk</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--success)' }}>{formatRpFull((summary.totalMasuk || 0) - saldoAwalAdj)}</p>
              </div>
              <div style={{ background: '#fef2f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Total Keluar</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--danger)' }}>{formatRpFull(summary.totalKeluar || 0)}</p>
              </div>
            </div>
          );
        })()}

        {/* Content */}
        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat jurnal...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--danger)', fontSize: 14 }}>{error}</p>
            <button onClick={fetchEntries} style={{ marginTop: 12, padding: '8px 20px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>Coba Lagi</button>
          </div>
        ) : entries.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ fontSize: 40, marginBottom: 8 }}>📋</p>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Belum ada transaksi bulan {formatBulanLabel(bulan)}</p>
          </div>
        ) : (
          <div>
            {sortedDates.map(tgl => (
              <div key={tgl} style={{ marginBottom: 20 }}>
                <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-muted)', padding: '8px 0', borderBottom: '1px solid var(--border-light)', marginBottom: 8 }}>
                  {tgl}
                </div>
                {grouped[tgl].map(entry => (
                  <div key={entry.id} style={{
                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                    padding: '12px 16px', background: 'var(--card)', borderRadius: 12,
                    border: '1px solid var(--border)', marginBottom: 6, gap: 12,
                  }}>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 2 }}>
                        <span style={{
                          fontSize: 11, fontWeight: 700, padding: '2px 8px', borderRadius: 6,
                          background: entry.arah === 'masuk' ? '#e8f8f0' : '#fef2f0',
                          color: entry.arah === 'masuk' ? 'var(--success)' : 'var(--danger)',
                        }}>{entry.arah === 'masuk' ? '↑ Masuk' : '↓ Keluar'}</span>
                        <span style={{ fontSize: 13, fontWeight: 600 }}>{entry.jenisLabel || entry.jenis}</span>
                        {entry.source === 'operasional_harian' && <span style={{ fontSize: 10, fontWeight: 600, padding: '1px 6px', borderRadius: 4, background: '#dbeafe', color: '#2563eb' }}>Auto</span>}
                      </div>
                      {entry.keterangan && <p style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>{entry.keterangan}</p>}
                    </div>
                    <div style={{ textAlign: 'right', flexShrink: 0 }}>
                      <p style={{ fontSize: 15, fontWeight: 700, color: entry.arah === 'masuk' ? 'var(--success)' : 'var(--danger)' }}>
                        {formatRpFull(entry.jumlah)}

                      </p>
                    </div>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
      </main>

      {/* Form Modal */}
      {showForm && (
        <FormModal
          cabangAdmins={activeCabang?.admins || []}
          cabangId={activeCabang?.id || ''}
          bulan={bulan}
          onClose={() => setShowForm(false)}
          onSuccess={handleEntryAdded}
        />
      )}

      {/* Delete Confirm */}
      {deleteConfirm && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200 }}
          onClick={() => setDeleteConfirm(null)}>
          <div style={{ background: '#fff', borderRadius: 20, padding: 28, maxWidth: 380, width: '90%', textAlign: 'center' }} onClick={e => e.stopPropagation()}>
            <p style={{ fontSize: 36, marginBottom: 12 }}>🗑️</p>
            <h3 style={{ fontSize: 16, fontWeight: 700, marginBottom: 8 }}>Hapus Transaksi?</h3>
            <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 20 }}>
              {deleteConfirm.jenisLabel} — {formatRpFull(deleteConfirm.jumlah)}<br />
              Tindakan ini tidak dapat dibatalkan.
            </p>
            <div style={{ display: 'flex', gap: 10 }}>
              <button onClick={() => setDeleteConfirm(null)} style={{ flex: 1, padding: '10px 0', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, fontWeight: 600 }}>Batal</button>
              <button onClick={() => handleDelete(deleteConfirm)} disabled={deleteLoading}
                style={{ flex: 1, padding: '10px 0', borderRadius: 10, background: 'var(--danger)', color: '#fff', fontSize: 13, fontWeight: 600, opacity: deleteLoading ? 0.6 : 1 }}>
                {deleteLoading ? 'Menghapus...' : 'Hapus'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}


// ============================================================
// FORM MODAL (Input transaksi kasir)
// ============================================================
function FormModal({ cabangAdmins, cabangId, bulan, onClose, onSuccess }) {
  const [jenis, setJenis] = useState('uang_kas');
  const [arah, setArah] = useState('keluar');
  const [jumlah, setJumlah] = useState('');
  const [keterangan, setKeterangan] = useState('');
  const [tanggal] = useState(getTodayIndo());
  const [targetAdmin, setTargetAdmin] = useState('');
  const [targetBuku, setTargetBuku] = useState(['kas_penuntun', 'ekspedisi']);
  const [fakturFile, setFakturFile] = useState(null);
  const [fakturPreview, setFakturPreview] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const fakturInputRef = useRef(null);

  const handleFakturCapture = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setFakturFile(file);
    const reader = new FileReader();
    reader.onload = (ev) => setFakturPreview(ev.target.result);
    reader.readAsDataURL(file);
  };

  const handleSubmit = async () => {
    const nominal = parseInt(jumlah.replace(/\D/g, ''), 10);
    if (!nominal || nominal <= 0) { setError('Jumlah harus diisi'); return; }
    if (jenis === 'uang_kas' && !targetAdmin) { setError('Pilih Admin Lapangan terlebih dahulu!'); return; }
    if (jenis === 'penggajian' && targetBuku.length === 0) { setError('Pilih minimal satu buku tujuan BU'); return; }
    setLoading(true);
    setError('');
    try {
      const selectedAdm = cabangAdmins.find(a => a.uid === targetAdmin);

      // ── BLOK 5 (W-9): nota ke Supabase Storage ──────────────────────────
      // URUTAN DIBALIK dari alur lama. Dulu: buat entri → unggah → tempel
      // URL-nya lewat update RTDB. Di Supabase `kasir_entry` TIDAK punya
      // jalur UPDATE sama sekali (015 B-4 hanya insert + soft delete), jadi
      // menempel belakangan mustahil tanpa RPC baru. Nota diunggah lebih
      // dulu, lalu path-nya ikut saat entri dibuat.
      //
      // `clientOpId` dibangkitkan di sini dan dipakai DUA KALI: sebagai nama
      // berkas dan sebagai kunci idempotensi entri. Ia harus tetap sama bila
      // pengguna menekan Simpan dua kali — itu yang mencegah entri kas ganda.
      const clientOpId = crypto.randomUUID();
      const bulanKey = (tanggal || new Date().toISOString().slice(0, 10)).slice(0, 7);

      // ── KEBIJAKAN: unggah gagal ⇒ ENTRI DIBATALKAN ──────────────────────
      // Versi sebelumnya menelan galat unggah dan tetap membuat entri, meniru
      // perilaku lama yang "tidak blocking". Itu keliru di sini, dan bukan
      // sekadar kurang rapi: pengguna melihat pesan SUKSES, mengira fotonya
      // terlampir, lalu tidak pernah memotret ulang. Entri BU tercatat seolah
      // ada buktinya padahal tidak — persis kelas kegagalan yang paling mahal
      // di migrasi ini, dan tidak ada yang menyadarinya sampai audit.
      //
      // Sekarang galatnya dibiarkan naik ke catch luar dan tampil apa adanya.
      // Mengulang aman: `clientOpId` dibangkitkan sekali di atas dan dipakai
      // ulang, jadi menekan Simpan lagi TIDAK menggandakan entri kas.
      //
      // Melampirkan foto tetap OPSIONAL. Kalau pengguna memang tidak memilih
      // berkas, entri dibuat tanpa nota seperti biasa. Yang dibatalkan hanya
      // kasus "sudah memilih foto, tetapi unggahannya gagal".
      let notaPath = null;
      if (fakturFile && jenis === 'penggajian' && cabangId) {
        const { unggahNota } = await import('../../lib/apiSupabase');
        const compressed = await compressImage(fakturFile);
        notaPath = await unggahNota({
          file: compressed, cabangId, periodeBulan: bulanKey, clientOpId,
        });
      }

      await addKasirEntry({
        jenis, arah, jumlah: nominal, keterangan, tanggal,
        targetAdminUid: jenis === 'uang_kas' ? targetAdmin : '',
        targetAdminName: jenis === 'uang_kas' ? (selectedAdm?.name || '') : '',
        targetBuku: jenis === 'penggajian' ? targetBuku : [],
        clientOpId, notaPath,
      });

      onSuccess();
    } catch (err) {
      setError('Gagal menyimpan: ' + err.message);
    } finally {
      setLoading(false);
    }
  };

  const handleJumlahChange = (e) => {
    const raw = e.target.value.replace(/\D/g, '');
    if (raw === '') { setJumlah(''); return; }
    setJumlah(new Intl.NumberFormat('id-ID').format(parseInt(raw)));
  };

  // Anti-orphan guard: uang_kas WAJIB punya Admin Lapangan tujuan.
  // handleSubmit di atas juga punya pengecekan yang sama sebagai belt-and-suspenders.
  const uangKasMissingAdmin = jenis === 'uang_kas' && !targetAdmin;
  const submitDisabled = loading || uangKasMissingAdmin;

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200, padding: 20 }}
      onClick={onClose}>
      <div style={{ background: '#fff', borderRadius: 20, padding: 28, maxWidth: 440, width: '100%' }}
        onClick={e => e.stopPropagation()} className="slide-up">
        <h3 style={{ fontSize: 18, fontWeight: 700, marginBottom: 20 }}>Tambah Transaksi Kasir</h3>

        {error && <div style={{ background: '#fef2f0', color: 'var(--danger)', padding: '10px 14px', borderRadius: 10, fontSize: 13, marginBottom: 16 }}>{error}</div>}

        {/* Jenis */}
        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Jenis Transaksi</label>
            <select value={jenis} onChange={(e) => { const v = e.target.value; setJenis(v); setArah(JENIS_ARAH[v] || 'keluar'); setTargetAdmin(''); if (v === 'penggajian') setTargetBuku(['kas_penuntun', 'ekspedisi']); }}
            style={{ width: '100%', padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 14, background: 'var(--card)' }}>
            {JENIS_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>

        {/* Pilihan Buku Tujuan — hanya untuk BU */}
        {jenis === 'penggajian' && (
          <div style={{ marginBottom: 16 }}>
            <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Buku Tujuan BU</label>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, cursor: 'pointer', padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', background: targetBuku.includes('kas_penuntun') ? '#e8f8f0' : 'var(--card)' }}>
                <input type="checkbox" checked={targetBuku.includes('kas_penuntun')}
                  onChange={(e) => {
                    if (e.target.checked) setTargetBuku(prev => [...prev, 'kas_penuntun']);
                    else setTargetBuku(prev => prev.filter(b => b !== 'kas_penuntun'));
                  }}
                  style={{ width: 18, height: 18, accentColor: 'var(--primary)' }} />
                Buku Kas Penuntun
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 14, cursor: 'pointer', padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', background: targetBuku.includes('ekspedisi') ? '#e8f8f0' : 'var(--card)' }}>
                <input type="checkbox" checked={targetBuku.includes('ekspedisi')}
                  onChange={(e) => {
                    if (e.target.checked) setTargetBuku(prev => [...prev, 'ekspedisi']);
                    else setTargetBuku(prev => prev.filter(b => b !== 'ekspedisi'));
                  }}
                  style={{ width: 18, height: 18, accentColor: 'var(--primary)' }} />
                Buku Ekspedisi
              </label>
            </div>
          </div>
        )}

        {/* Admin Lapangan yang dituju — hanya untuk Uang Kas */}
        {jenis === 'uang_kas' && cabangAdmins.length > 0 && (
          <div style={{ marginBottom: 16 }}>
            <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Admin Lapangan yang Dituju</label>
            <select value={targetAdmin}
              onChange={(e) => { setTargetAdmin(e.target.value); if (error) setError(''); }}
              style={{ width: '100%', padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 14, background: 'var(--card)' }}>
              <option value="">-- Pilih Admin --</option>
              {cabangAdmins.map(a => <option key={a.uid} value={a.uid}>{a.name}</option>)}
            </select>
          </div>
        )}

        {/* Jumlah */}
        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Jumlah (Rp)</label>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input type="text" value={jumlah} onChange={handleJumlahChange} placeholder="0" inputMode="numeric"
              style={{ flex: 1, padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 18, fontWeight: 700, fontFamily: "'DM Mono', monospace", boxSizing: 'border-box' }} />
            {jenis === 'penggajian' && (
              <button type="button" onClick={() => fakturInputRef.current?.click()}
                style={{ width: 44, height: 44, borderRadius: 10, border: fakturPreview ? '2px solid var(--primary)' : '1px solid var(--border)', background: fakturPreview ? '#e8f8f0' : 'var(--card)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}
                title="Foto Faktur BU">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={fakturPreview ? 'var(--primary)' : '#666'} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/>
                  <circle cx="12" cy="13" r="4"/>
                </svg>
              </button>
            )}
            <input ref={fakturInputRef} type="file" accept="image/*" capture="environment"
              style={{ display: 'none' }} onChange={handleFakturCapture} />
          </div>
          {/* Faktur preview */}
          {jenis === 'penggajian' && fakturPreview && (
            <div style={{ marginTop: 8, position: 'relative', display: 'inline-block' }}>
              <img src={fakturPreview} alt="Faktur" style={{ width: 80, height: 80, objectFit: 'cover', borderRadius: 8, border: '1px solid var(--border)' }} />
              <button type="button" onClick={() => { setFakturFile(null); setFakturPreview(null); if (fakturInputRef.current) fakturInputRef.current.value = ''; }}
                style={{ position: 'absolute', top: -6, right: -6, width: 20, height: 20, borderRadius: '50%', background: 'var(--danger)', color: '#fff', border: 'none', fontSize: 12, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', lineHeight: 1 }}>×</button>
            </div>
          )}
        </div>

        {/* Tanggal */}
        <div style={{ marginBottom: 16 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Tanggal</label>
          <div style={{ padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 14, background: '#f8f9fa', color: 'var(--text-muted)' }}>{tanggal}</div>
        </div>

        {/* Keterangan */}
        <div style={{ marginBottom: 24 }}>
          <label style={{ fontSize: 13, fontWeight: 600, marginBottom: 6, display: 'block' }}>Keterangan <span style={{ fontWeight: 400, color: 'var(--text-light)' }}>(opsional)</span></label>
          <input type="text" value={keterangan} onChange={(e) => setKeterangan(e.target.value)} placeholder="Catatan tambahan..."
            style={{ width: '100%', padding: '10px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 14, boxSizing: 'border-box' }} />
        </div>

        {/* Actions */}
        <div style={{ display: 'flex', gap: 10 }}>
          <button onClick={onClose} style={{ flex: 1, padding: '12px 0', borderRadius: 12, border: '1px solid var(--border)', fontSize: 14, fontWeight: 600 }}>Batal</button>
          <button onClick={handleSubmit} disabled={submitDisabled}
            title={uangKasMissingAdmin ? 'Pilih Admin Lapangan terlebih dahulu' : ''}
            style={{ flex: 1, padding: '12px 0', borderRadius: 12, background: 'var(--primary)', color: '#fff', fontSize: 14, fontWeight: 600, opacity: submitDisabled ? 0.6 : 1, cursor: submitDisabled ? 'not-allowed' : 'pointer', transition: 'all 0.2s' }}>
            {loading ? 'Menyimpan...' : 'Simpan'}
          </button>
        </div>
      </div>
    </div>
  );
}


// ============================================================
// FAKTUR PHOTO MODAL (View faktur BU with zoom)
// ============================================================
function FakturModal({ fakturList, onClose }) {
  const [zoomedUrl, setZoomedUrl] = useState(null);

  if (!fakturList || fakturList.length === 0) return null;

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 300, padding: 20 }}
      onClick={onClose}>
      <div style={{ background: '#fff', borderRadius: 16, padding: 20, maxWidth: 480, width: '100%', maxHeight: '80vh', overflow: 'auto' }}
        onClick={e => e.stopPropagation()} className="slide-up">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <h3 style={{ fontSize: 16, fontWeight: 700 }}>Faktur BU</h3>
          <button onClick={onClose} style={{ width: 32, height: 32, borderRadius: '50%', border: '1px solid var(--border)', background: 'var(--card)', cursor: 'pointer', fontSize: 16, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>×</button>
        </div>
        {fakturList.map((item, idx) => (
          <div key={idx} style={{ marginBottom: 16, borderBottom: idx < fakturList.length - 1 ? '1px solid var(--border-light)' : 'none', paddingBottom: 12 }}>
            <p style={{ fontSize: 13, fontWeight: 600, marginBottom: 8, color: 'var(--text-muted)' }}>
              {formatRpFull(item.jumlah)} {item.keterangan ? `— ${item.keterangan}` : ''}
            </p>
            {item.fakturUrl ? (
              <img src={item.fakturUrl} alt="Faktur BU"
                onClick={() => setZoomedUrl(item.fakturUrl)}
                style={{ width: '100%', maxWidth: 400, borderRadius: 10, border: '1px solid var(--border)', cursor: 'pointer' }} />
            ) : (
              <p style={{ fontSize: 12, color: 'var(--text-light)', fontStyle: 'italic' }}>Tidak ada foto faktur</p>
            )}
          </div>
        ))}
      </div>

      {/* Zoomed view */}
      {zoomedUrl && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.85)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 400, cursor: 'pointer' }}
          onClick={(e) => { e.stopPropagation(); setZoomedUrl(null); }}>
          <img src={zoomedUrl} alt="Faktur BU (zoom)"
            style={{ maxWidth: '95vw', maxHeight: '95vh', objectFit: 'contain', borderRadius: 8 }} />
          <button onClick={(e) => { e.stopPropagation(); setZoomedUrl(null); }}
            style={{ position: 'absolute', top: 20, right: 20, width: 40, height: 40, borderRadius: '50%', background: 'rgba(255,255,255,0.2)', border: 'none', color: '#fff', fontSize: 24, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>×</button>
        </div>
      )}
    </div>
  );
}


// ============================================================
// BUKU POKOK ACCESS SCREEN
// ============================================================
function BukuPokokAccessScreen({ user, cabang, cabangList, onBack, onLogout }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(() => resolveInitialCabang(cabang, cabangList));
  const [selectedAdmin, setSelectedAdmin] = useState(null);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [visibleDateCount, setVisibleDateCount] = useState(7);
  const [search, setSearch] = useState('');

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    getBukuPokok({
      cabangId: activeCabang.id,
      adminUid: selectedAdmin?.uid || '',
      status: 'aktif',
    }).then(result => {
      if (result.success && result.type === 'buku_pokok') {
        setData(result.data);
      }
    }).catch(err => {
      setError('Gagal memuat data: ' + err.message);
    }).finally(() => {
      setLoading(false);
    });
  }, [activeCabang?.id, selectedAdmin?.uid]);

  const filtered = data?.nasabah?.filter(n => {
    if (!search) return true;
    const q = search.toLowerCase();
    return n.namaKtp.toLowerCase().includes(q) || n.namaPanggilan.toLowerCase().includes(q) || n.nomorAnggota.includes(q);
  }) || [];

  const dates = data?.tanggalList || [];
  const visibleDates = dates.slice(0, visibleDateCount);
  const admins = activeCabang?.admins || [];

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Buku Pokok</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}</p>
          </div>
        </div>
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        {/* Cabang selector for kasir_wilayah */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 16 }}>
            <select value={activeCabang?.id || ''} onChange={(e) => { selectCabangById(cabangList, e.target.value, setActiveCabang); setSelectedAdmin(null); }}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>
        )}

        {/* Admin chips */}
        {admins.length > 1 && (
          <div className="admin-chips-wrapper" style={{ marginBottom: 16 }}>
            <button onClick={() => setSelectedAdmin(null)} className={`admin-chip ${!selectedAdmin ? 'active' : ''}`}>Semua</button>
            {admins.map(a => (
              <button key={a.uid} onClick={() => setSelectedAdmin(a)} className={`admin-chip ${selectedAdmin?.uid === a.uid ? 'active' : ''}`}>{a.name}</button>
            ))}
          </div>
        )}

        {/* Search */}
        <div style={{ marginBottom: 16 }}>
          <input type="text" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Cari nama / no anggota..."
            style={{ width: '100%', maxWidth: 400, padding: '8px 14px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, boxSizing: 'border-box' }} />
        </div>

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Buku Pokok...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : (
          <>
            {/* Stats */}
            <div style={{ display: 'flex', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>Total: <strong>{filtered.length}</strong> nasabah</span>
              <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>Piutang: <strong>{formatRpFull(filtered.reduce((s, n) => s + n.sisaUtang, 0))}</strong></span>
            </div>

            {/* Table */}
            <div className="buku-pokok-table-wrapper" style={{ overflow: 'auto', maxHeight: 'calc(100vh - 280px)', border: '1px solid var(--border)', borderRadius: 12 }}>
              <table style={{ width: '100%', minWidth: 800, fontSize: 12 }}>
                <thead>
                  <tr style={{ background: '#f8f9fa' }}>
                    <th style={{ padding: '10px 8px', textAlign: 'left', fontWeight: 700, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2 }}>No</th>
                    <th style={{ padding: '10px 8px', textAlign: 'left', fontWeight: 700, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2 }}>Nama</th>
                    <th style={{ padding: '10px 8px', textAlign: 'right', fontWeight: 700, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2 }}>Pinjaman</th>
                    <th style={{ padding: '10px 8px', textAlign: 'right', fontWeight: 700, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2 }}>Sisa</th>
                    {visibleDates.map(d => (
                      <th key={d} style={{ padding: '10px 4px', textAlign: 'center', fontWeight: 600, position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2, whiteSpace: 'nowrap', fontSize: 11 }}>{d.slice(0, 6)}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((n, idx) => (
                    <tr key={n.id} style={{ borderBottom: '1px solid var(--border-light)' }}>
                      <td style={{ padding: '8px', color: 'var(--text-muted)', fontSize: 11 }}>{idx + 1}</td>
                      <td style={{ padding: '8px' }}>
                        <div style={{ fontWeight: 600, fontSize: 12 }}>{n.namaPanggilan || n.namaKtp}</div>
                        <div style={{ fontSize: 10, color: 'var(--text-muted)' }}>{n.nomorAnggota} • Ke-{n.pinjamanKe}</div>
                      </td>
                      <td style={{ padding: '8px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11 }}>{formatRp(n.totalPelunasan)}</td>
                      <td style={{ padding: '8px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11, fontWeight: 600, color: n.sisaUtang > 0 ? 'var(--danger)' : 'var(--success)' }}>{formatRp(n.sisaUtang)}</td>
                      {visibleDates.map(d => {
                        const p = n.pembayaran?.[d];
                        return (
                          <td key={d} style={{ padding: '4px', textAlign: 'center', fontFamily: "'DM Mono', monospace", fontSize: 10, color: p ? 'var(--success)' : 'var(--text-light)' }}>
                            {p ? formatRp(p.total) : '-'}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Load more dates */}
            {visibleDateCount < dates.length && (
              <div style={{ textAlign: 'center', marginTop: 16 }}>
                <button onClick={() => setVisibleDateCount(prev => prev + 7)}
                  style={{ padding: '8px 24px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
                  Tampilkan Hari Sebelumnya
                </button>
              </div>
            )}
          </>
        )}
      </main>
    </div>
  );
}

// ============================================================
// BUKU REKAP SCREEN (Rekap harian per resort)
// ============================================================
function BukuRekapScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(() => resolveInitialCabang(cabang, cabangList));
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedDate, setSelectedDate] = useState(null);
  const bulanOptions = generateBulanOptions();
  const [selectedBulan, setSelectedBulan] = useState(bulanOptions[0]?.key || '');
  // Pencairan tabungan (jurnal_transaksi) untuk kolom "Cair Tab." + kredit tunaiPasar.
  const [jurnalEntries, setJurnalEntries] = useState([]);

  // =====================================================================
  // ✅ OPTIMISTIC UPDATE (pimpinan 08 Jun 2026, SAFE BUDGET MODE)
  // ---------------------------------------------------------------------
  // Tujuan: Storting di Buku Rekap update INSTAN saat pembayaran/cicilan
  // berhasil di-submit, tanpa menunggu CF triggers selesai meng-agregat
  // summary node ATAU menunggu cache TTL (10 menit) habis.
  //
  // Cara kerja:
  //   1. Helper `applyOptimisticPembayaran` MUTASI data.nasabah langsung di
  //      memori (immutable update): tambah jumlah ke pembayaran[tanggal].total
  //      pada nasabah pelangganId. computeRekapRows otomatis recompute →
  //      storting UI bertambah instan.
  //   2. Cross-component trigger via window CustomEvent
  //      'bukuRekap:optimisticPembayaran' — page/komponen manapun yang
  //      berhasil menulis cicilan ke RTDB tinggal dispatch event ini setelah
  //      mutasi sukses.
  //   3. Ketika getBukuPokok fetch berikutnya (cache miss / refresh manual),
  //      setData(result.data) menimpa state → optimistic patch otomatis
  //      bersih (tidak ada double-count permanen).
  //
  // Constraint TAAT pimpinan:
  //   - Cache TTL getBukuPokok TIDAK disentuh (tetap hemat RTDB).
  //   - Hanya WRITE MUTATION sukses yang trigger update (dispatcher hanya
  //     dispatch setelah RTDB write berhasil).
  //   - TIDAK ada listener real-time RTDB baru.
  //
  // Contract event:
  //   window.dispatchEvent(new CustomEvent('bukuRekap:optimisticPembayaran', {
  //     detail: { pelangganId: string, jumlah: number, tanggal: string }
  //   }))
  //   tanggal format: 'dd MMM yyyy' (mis. '08 Jun 2026').
  // =====================================================================
  const applyOptimisticPembayaran = useCallback((pelangganId, jumlah, tanggal) => {
    if (!pelangganId || !Number.isFinite(jumlah) || jumlah <= 0 || !tanggal) return;
    setData(prev => {
      if (!prev || !Array.isArray(prev.nasabah)) return prev;
      const idx = prev.nasabah.findIndex(n => n && n.id === pelangganId);
      if (idx === -1) return prev;
      const n = prev.nasabah[idx];
      const prevPay = (n.pembayaran && n.pembayaran[tanggal]) || { total: 0, entries: [] };
      const newPembayaran = {
        ...(n.pembayaran || {}),
        [tanggal]: {
          ...prevPay,
          total: (prevPay.total || 0) + jumlah
        }
      };
      const newNasabah = prev.nasabah.slice();
      newNasabah[idx] = { ...n, pembayaran: newPembayaran };
      return { ...prev, nasabah: newNasabah };
    });
  }, []);

  useEffect(() => {
    const handler = (e) => {
      const d = e.detail || {};
      applyOptimisticPembayaran(d.pelangganId, Number(d.jumlah), d.tanggal);
    };
    window.addEventListener('bukuRekap:optimisticPembayaran', handler);
    return () => window.removeEventListener('bukuRekap:optimisticPembayaran', handler);
  }, [applyOptimisticPembayaran]);

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    setSelectedDate(null);
    // ✅ status 'semua' (bukan 'aktif'): perlu nasabah MENUNGGU_PENCAIRAN & lunas-hari-ini
    // agar Target Harian match Buku Pokok / CF / Android (aturan H+1). Filter 'aktif'
    // men-drop record tsb di server sehingga Target undercount. Tanpa tambahan read RTDB.
    // `bulan` di-pass agar CF ikut sertakan orphanPaymentsByDate (pembayaran
    // dari nasabah yang sudah dihapus, mis. setelah cairkanSimpanan). Tanpa
    // ini, Storting BukuRekap akan miss pembayaran tsb sementara Android
    // (LaporanHarian / RingkasanDashboard) sudah menyertakannya — parity break.
    getBukuPokok({
      cabangId: activeCabang.id,
      adminUid: '',
      status: 'semua',
      bulan: selectedBulan,
    }).then(result => {
      if (result.success && result.type === 'buku_pokok') {
        setData(result.data);
      }
    }).catch(err => {
      setError('Gagal memuat data: ' + err.message);
    }).finally(() => {
      setLoading(false);
    });
  }, [activeCabang?.id, selectedBulan]);

  // Fetch jurnal_transaksi terpisah (cabang + bulan terpilih) — sumber "Cair Tab.".
  // Terpisah dari getBukuPokok agar re-fetch hanya saat cabang/bulan berubah.
  useEffect(() => {
    if (!activeCabang || !selectedBulan) { setJurnalEntries([]); return; }
    getJurnalTransaksi({ cabangId: activeCabang.id, bulan: selectedBulan })
      .then(result => {
        setJurnalEntries(result?.success && result.data?.entries ? result.data.entries : []);
      })
      .catch(() => setJurnalEntries([]));
  }, [activeCabang?.id, selectedBulan]);

  // Tanggal hari kerja pada bulan yang dipilih
  const [selBulanYear, selBulanMonth] = selectedBulan.split('-').map(Number);
  const dates = (data?.tanggalList || [])
    .filter(d => {
      const parts = d.split(' ');
      if (parts.length < 3) return false;
      const dMonth = BULAN_INDO.indexOf(parts[1]) + 1;
      const dYear = parseInt(parts[2]);
      if (dYear !== selBulanYear || dMonth !== selBulanMonth) return false;
      const dateObj = parseTanggalIndo(d);
      return dateObj && isHariKerja(dateObj);
    });
  const currentDate = selectedDate || dates[0] || null;

  // Pencairan tabungan per (tanggal, admin) dari jurnal_transaksi.
  // Mencakup pelunasan_tabungan + pencairan_simpanan_partial + tarik_tabungan
  // → semua dipakai sebagai kredit di Tunai Pasar (lihat helper definition).
  const pencairanByAdminDate = buildPencairanByAdminDate(jurnalEntries);

  // Orphan payments dari CF (pembayaran_harian entry yang pelangganId-nya tidak
  // ada lagi di pelanggan/, mis. setelah cairkanSimpanan). Per SOP, ini tetap
  // masuk Storting agar Web match Android (LaporanHarian/RingkasanDashboard).
  const orphanPaymentsByDate = data?.orphanPaymentsByDate || {};

  // ==================== COMPUTE REKAP PER RESORT ====================
  const computeRekapRows = (dateStr) => {
    if (!data?.nasabah) return [];

    const allNasabah = data.nasabah;
    const admins = activeCabang?.admins || [];
    const todayStr = dateStr || data.today || getTodayIndo();

    // Helper: is nasabah "baru" (pinjaman ke-1) or "lama" (lanjut)
    const isDropBaru = (n) => (n.pinjamanKe || 1) <= 1;

    // Group per admin (resort)
    const rows = [];

    for (const adm of admins) {
      const resortNasabah = allNasabah.filter(n => n.adminUid === adm.uid);

      // Nasabah yang dicairkan hari ini (drop hari ini)
      const droppedToday = resortNasabah.filter(n => {
        const tglCair = (n.tanggalPencairan || '').trim();
        return tglCair === todayStr;
      });

      // Pisahkan drop baru vs lama
      const dropBaruList = droppedToday.filter(n => isDropBaru(n));
      const dropLamaList = droppedToday.filter(n => !isDropBaru(n));

      const dropBaruCount = dropBaruList.length;
      const dropLamaCount = dropLamaList.length;

      // Nominal drop
      const nominalDropBaru = dropBaruList.reduce((s, n) => s + (n.besarPinjaman || 0), 0);
      const nominalDropLama = dropLamaList.reduce((s, n) => s + (n.besarPinjaman || 0), 0);
      const totalDrop = nominalDropBaru + nominalDropLama;

      // Target = besarPinjaman × 3% untuk nasabah eligible pada todayStr.
      // Dihitung lewat shared helper (lib/target.js) — single source of truth yang
      // identik dengan Buku Pokok, CF summaryHelpers.js, & Android (termasuk H+1).
      //
      // ✅ FIX shrinking-target historis (audit pimpinan 05 Jun 2026):
      // Pre-skip `!n.dariArsip` DIHAPUS — sebelumnya entry arsip (nasabah yang
      // dihapus via cairkanSimpanan) di-skip TUNTAS, menyebabkan target tanggal
      // LAMPAU menyusut saat nasabah baru diarsip hari ini. Pengaturan cutoff
      // sekarang dipindah ke helper (lib/target.js POIN 4) yang menerapkan
      // logic date-aware via field `tanggalArsip`:
      //   tanggalArsip ≥ cur → target tetap dihitung (hari arsip = hari terakhir
      //     aktif; parity H+1 LUNAS/MP — fix shrink 06 Jun 2026, dulu keliru ≤)
      //   tanggalArsip < cur → 0 (sudah berhenti ditagih SEBELUM kolom)
      // isHistorical/isOrphan tetap di-skip — itu flag client-only (lihat
      // pembukuan/page.js:1084,1142), bukan dari arsip CF.
      let target = 0;
      resortNasabah.forEach(n => {
        if (!n.isHistorical && !n.isOrphan) {
          target += isEligibleForTarget(n, todayStr);
        }
      });

      // Storting = total pembayaran hari ini
      let storting = 0;
      resortNasabah.forEach(n => {
        const pay = n.pembayaran?.[todayStr];
        if (pay) {
          storting += pay.total || 0;
        }
        // ✅ ROOT CAUSE FIX "Buku Rekap 1jt vs Buku Pokok 2jt hari berjalan"
        // (pimpinan 11 Jun 2026). Pembayaran yang DIRELOKASI CF ke baris
        // pinjaman LAMA tinggal di n.riwayatPinjaman[].pembayaran, BUKAN di
        // n.pembayaran pinjaman baru — yaitu pelunasan sisa utang lama top-up
        // dan cicilan pinjaman lama (lihat bukuPokokApi.js relokasi L565-595:
        // amount ditulis ke lastRiwayat.pembayaran[tglPelunasan]). Buku Pokok
        // sudah meng-sum jalur ini (pembukuan/page.js:1320-1332); Buku Rekap
        // SEBELUMNYA tidak pernah meng-iterasi riwayatPinjaman → storting hari
        // berjalan under-count persis sebesar nominal yang berpindah ke riwayat.
        // Loop ini = mirror 1:1 Buku Pokok agar kedua menu match real-time.
        (n.riwayatPinjaman || []).forEach(r => {
          const rPay = r.pembayaran?.[todayStr];
          if (rPay) storting += rPay.total || 0;
        });
      });
      // Tambahkan orphan storting (pembayaran dari nasabah yang sudah dihapus
      // — mis. setelah cairkanSimpanan). Sumber: pembayaran_harian via CF.
      // SHAPE baru: array per-entry { adminUid, jumlah, ... } → kita reduce
      // untuk admin/tanggal ini. Sebelumnya { adminUid: jumlah } (sudah agregat).
      const orphanArr = orphanPaymentsByDate[todayStr] || [];
      const orphanStortingAdm = Array.isArray(orphanArr)
        ? orphanArr.reduce((s, e) => (e && e.adminUid === adm.uid ? s + (e.jumlah || 0) : s), 0)
        : (orphanArr?.[adm.uid] || 0); // back-compat shape lama bila CF belum di-deploy
      storting += orphanStortingAdm;

      // ✅ CATATAN (pimpinan 11 Jun 2026): blok lama `pelunasanTopUpAdm` —
      // yang menambah `sisaUtangLamaSebelumTopUp` secara manual — DIHAPUS,
      // digantikan loop riwayatPinjaman di atas yang mirror Buku Pokok 1:1.
      // Mempertahankannya = DOUBLE-COUNT: CF sudah menulis nominal pelunasan
      // top-up itu ke riwayat[last].pembayaran[tglPelunasan] (bukuPokokApi.js
      // L582-590), atau ke n.pembayaran[tglPencairan] pada cabang fallback
      // (L600-609). Kedua jalur kini SUDAH tertangkap loop di atas, sehingga
      // penjumlahan terpisah hanya akan menggandakan nilai pada kolom yang
      // tglPelunasan-nya == hari ini, dan menggeser nilai pada kolom yang
      // tglPelunasan-nya tanggal lampau (divergensi vs Buku Pokok).

      // =====================================================================
      // ✅ BENTENG ANTI-SHRINK: snapshot rekap_harian_final ADALAH OTORITAS.
      // ---------------------------------------------------------------------
      // Aturan pimpinan 07 Jun 2026 (final): "Jika target pada 06 Jun adalah
      // 1.450.000, dia HARUS tetap 1.450.000 selamanya — bahkan dilihat 10
      // tahun kemudian." Sebelumnya kode ini memakai gate `!isToday`
      // (todayStr !== data.today) untuk memutuskan apakah snapshot dipakai.
      // Gate itu CACAT: pada Minggu/hari libur web menyembunyikan tanggal
      // tersebut sehingga internal logic bisa menyamakan tanggal yang dilihat
      // dengan data.today → snapshot dilewati → live calc → target menyusut.
      //
      // ✅ REVISI pimpinan 09 Jun 2026 (fix "Buku Rekap baru update di 00:01"):
      //   • Tanggal HISTORIS (kalender < hari ini) → snapshot OTORITAS ABSOLUT
      //     (Rule 3 utuh: imun mutasi retroaktif, dilihat 10 tahun lagi tetap).
      //   • Tanggal HARI BERJALAN → SELALU live calc, walau entri snapshot
      //     hari-ini ADA (mis. ditulis refreezeRekapHarian siang hari, atau
      //     freeze 23:59 — keduanya tidak boleh membekukan tampilan live).
      //     Tanpa gate ini: storting baru tidak tampil sampai tanggal berganti
      //     00:01 — persis gejala yang pimpinan laporkan.
      //   Gate pakai isTanggalHistoris (komparasi PARSED DATE `<` terhadap
      //   data.today server-WIB) — BUKAN string equality `!== data.today`
      //   yang dulu cacat pada kasus Minggu-tersembunyi (lihat lib/target.js).
      //
      // Live calc TIDAK pernah "mengoreksi" snapshot historis — inti benteng.
      // UI/layout TIDAK berubah — hanya sumber nilai numerik Target & Storting.
      // =====================================================================
      const bekuEntry = (isTanggalHistoris(todayStr, data.today)
          && data.rekapBeku && data.rekapBeku[adm.uid])
        ? data.rekapBeku[adm.uid][todayStr]
        : null;
      if (bekuEntry) {
        target = bekuEntry.target;
        storting = bekuEntry.storting;
      }

      // Persen = storting / target * 100
      const persen = target > 0 ? Math.round(storting / target * 100) : 0;

      // Admin = 5% dari total besar pinjaman hari ini (drop hari ini)
      const adminFee = Math.round(totalDrop * 0.05);

      // Tabungan = 5% dari total besar pinjaman hari ini
      const tabungan = Math.round(totalDrop * 0.05);

      // ✅ FIX (16 Jun 2026 — aturan bisnis pimpinan): "Tarik Tabungan" = HANYA
      // nominal yang ditahan dari pinjaman BARU saat approval (Pimpinan <3jt /
      // Koordinator >=3jt) lalu disetor ke simpanan. Ini event approval-pinjaman,
      // BUKAN likuidasi tabungan. Sebelumnya kolom ini KELIRU menambahkan jurnal
      // tipe 'tarik_tabungan' (kelebihan kas saat cairkanSimpanan) — itu adalah
      // event "Cair Tabungan", bukan "Tarik Tabungan". 'tarik_tabungan' tetap
      // masuk kolom "Cair Tab." via pencairanByAdminDate (lihat di bawah), jadi
      // tidak ada data yang hilang — hanya tidak lagi double-masuk ke Tarik Tab.
      const tarikTabunganTotal = droppedToday.reduce((s, n) => s + (n.tarikTabungan || 0), 0);

      // Debit asli = Storting + Admin + Tabungan
      const debitAsli = storting + adminFee + tabungan;

      // Pencairan Tabungan = jurnal pelunasan_tabungan + pencairan_simpanan_partial
      // untuk admin & tanggal ini (uang kas keluar via tabungan).
      const pencairanTabungan = (pencairanByAdminDate[todayStr] && pencairanByAdminDate[todayStr][adm.uid]) || 0;

      // Kredit = Total Drop + Pencairan Tabungan
      const kredit = totalDrop + pencairanTabungan;

      // Kas Pakai & Tunai Pasar (memakai debitAsli — Source of Truth lintas layar):
      // Jika kredit > debitAsli, kas pakai = selisih, tunai pasar = 0.
      // Jika debitAsli >= kredit, kas pakai = 0, tunai pasar = debitAsli - kredit.
      const kasPakai = kredit > debitAsli ? kredit - debitAsli : 0;
      // Debit (kolom tampilan Buku Rekap) = Storting + Admin + Tabungan + Tarik Tabungan.
      const debit = storting + adminFee + tabungan + tarikTabunganTotal;
      const tunaiPasar = debitAsli >= kredit ? debitAsli - kredit : 0;

      rows.push({
        resortName: adm.name,
        dropBaru: dropBaruCount,
        dropLama: dropLamaCount,
        target,
        kasPakai,
        storting,
        persen,
        adminFee,
        tarikTabungan: tarikTabunganTotal,
        tabungan,
        debit,
        nominalDropBaru,
        nominalDropLama,
        totalDrop,
        pencairanTabungan,
        kredit,
        tunaiPasar,
      });
    }

    return rows;
  };

  const rekapRows = computeRekapRows(currentDate);

  // Totals
  const totals = rekapRows.reduce((acc, r) => ({
    dropBaru: acc.dropBaru + r.dropBaru,
    dropLama: acc.dropLama + r.dropLama,
    target: acc.target + r.target,
    kasPakai: acc.kasPakai + r.kasPakai,
    storting: acc.storting + r.storting,
    adminFee: acc.adminFee + r.adminFee,
    tarikTabungan: acc.tarikTabungan + r.tarikTabungan,
    tabungan: acc.tabungan + r.tabungan,
    debit: acc.debit + r.debit,
    nominalDropBaru: acc.nominalDropBaru + r.nominalDropBaru,
    nominalDropLama: acc.nominalDropLama + r.nominalDropLama,
    totalDrop: acc.totalDrop + r.totalDrop,
    pencairanTabungan: acc.pencairanTabungan + r.pencairanTabungan,
    kredit: acc.kredit + r.kredit,
    tunaiPasar: acc.tunaiPasar + r.tunaiPasar,
  }), {
    dropBaru: 0, dropLama: 0, target: 0, kasPakai: 0, storting: 0,
    adminFee: 0, tarikTabungan: 0, tabungan: 0, debit: 0, nominalDropBaru: 0, nominalDropLama: 0,
    totalDrop: 0, pencairanTabungan: 0, kredit: 0, tunaiPasar: 0,
  });
  const totalPersen = totals.target > 0 ? Math.round(totals.storting / totals.target * 100) : 0;

  // ==================== TOTAL KEMARIN & GABUNGAN ====================
  // ✅ FIX (pimpinan 13 Jun 2026): "Total Kemarin" = SALDO BERJALAN month-to-date.
  // Sebelumnya hanya menghitung 1 hari sebelumnya, sehingga ledger reset tiap hari
  // dan "Total" (Hari Ini + Kemarin) cuma mewakili 2 hari. Semantik benar: Kemarin
  // = akumulasi SEMUA hari kerja sebelum currentDate di bulan yg sama; Total =
  // month-to-date inklusif currentDate. `dates` sudah descending dan sudah difilter
  // ke bulan terpilih (lihat L1862-1871), jadi semua hari kerja sebelum currentDate
  // = dates.slice(idx+1). showKemarin = ada minimal 1 hari kerja sebelumnya.
  const currentDateIdx = dates.indexOf(currentDate);
  const priorDates = (currentDateIdx >= 0 && currentDateIdx < dates.length - 1)
    ? dates.slice(currentDateIdx + 1)
    : [];
  const showKemarin = priorDates.length > 0;

  const rekapRowsKemarin = priorDates.flatMap(d => computeRekapRows(d));
  const totalsKemarin = rekapRowsKemarin.reduce((acc, r) => ({
    dropBaru: acc.dropBaru + r.dropBaru,
    dropLama: acc.dropLama + r.dropLama,
    target: acc.target + r.target,
    kasPakai: acc.kasPakai + r.kasPakai,
    storting: acc.storting + r.storting,
    adminFee: acc.adminFee + r.adminFee,
    tarikTabungan: acc.tarikTabungan + r.tarikTabungan,
    tabungan: acc.tabungan + r.tabungan,
    debit: acc.debit + r.debit,
    nominalDropBaru: acc.nominalDropBaru + r.nominalDropBaru,
    nominalDropLama: acc.nominalDropLama + r.nominalDropLama,
    totalDrop: acc.totalDrop + r.totalDrop,
    pencairanTabungan: acc.pencairanTabungan + r.pencairanTabungan,
    kredit: acc.kredit + r.kredit,
    tunaiPasar: acc.tunaiPasar + r.tunaiPasar,
  }), {
    dropBaru: 0, dropLama: 0, target: 0, kasPakai: 0, storting: 0,
    adminFee: 0, tarikTabungan: 0, tabungan: 0, debit: 0, nominalDropBaru: 0, nominalDropLama: 0,
    totalDrop: 0, pencairanTabungan: 0, kredit: 0, tunaiPasar: 0,
  });
  const totalPersenKemarin = totalsKemarin.target > 0 ? Math.round(totalsKemarin.storting / totalsKemarin.target * 100) : 0;

  const totalsGabungan = {
    dropBaru: totals.dropBaru + totalsKemarin.dropBaru,
    dropLama: totals.dropLama + totalsKemarin.dropLama,
    target: totals.target + totalsKemarin.target,
    kasPakai: totals.kasPakai + totalsKemarin.kasPakai,
    storting: totals.storting + totalsKemarin.storting,
    adminFee: totals.adminFee + totalsKemarin.adminFee,
    tarikTabungan: totals.tarikTabungan + totalsKemarin.tarikTabungan,
    tabungan: totals.tabungan + totalsKemarin.tabungan,
    debit: totals.debit + totalsKemarin.debit,
    nominalDropBaru: totals.nominalDropBaru + totalsKemarin.nominalDropBaru,
    nominalDropLama: totals.nominalDropLama + totalsKemarin.nominalDropLama,
    totalDrop: totals.totalDrop + totalsKemarin.totalDrop,
    pencairanTabungan: totals.pencairanTabungan + totalsKemarin.pencairanTabungan,
    kredit: totals.kredit + totalsKemarin.kredit,
    tunaiPasar: totals.tunaiPasar + totalsKemarin.tunaiPasar,
  };
  const totalPersenGabungan = totalsGabungan.target > 0 ? Math.round(totalsGabungan.storting / totalsGabungan.target * 100) : 0;

  const thStyle = { padding: '8px 6px', textAlign: 'center', fontWeight: 700, fontSize: 10, whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2, borderBottom: '2px solid var(--border)' };
  const tdStyle = { padding: '6px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11 };
  const tdNameStyle = { padding: '6px 8px', textAlign: 'left', fontWeight: 600, fontSize: 12, whiteSpace: 'nowrap' };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Buku Rekap</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}{currentDate ? ` — ${currentDate}` : ''}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="bukuRekap" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        {/* Cabang selector for kasir_wilayah */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 16 }}>
            <select value={activeCabang?.id || ''} onChange={(e) => selectCabangById(cabangList, e.target.value, setActiveCabang)}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>
        )}

        {/* Pilih bulan */}
        <div style={{ marginBottom: 12 }}>
          <select
            value={selectedBulan}
            onChange={(e) => { setSelectedBulan(e.target.value); setSelectedDate(null); }}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)', color: 'var(--text)' }}
          >
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
        </div>

        {/* Tab tanggal — hari kerja pada bulan terpilih */}
        {!loading && dates.length > 0 && (
          <div style={{ display: 'flex', gap: 6, marginBottom: 16, overflowX: 'auto', paddingBottom: 4 }}>
            {dates.map(d => {
              const isActive = currentDate === d;
              return (
                <button key={d} onClick={() => setSelectedDate(d)} style={{
                  padding: '6px 14px', borderRadius: 20, fontSize: 12, fontWeight: 600,
                  whiteSpace: 'nowrap', cursor: 'pointer',
                  border: `1px solid ${isActive ? 'var(--primary)' : 'var(--border)'}`,
                  background: isActive ? 'var(--primary)' : 'var(--card)',
                  color: isActive ? '#fff' : 'var(--text)',
                  transition: 'all 0.15s',
                }}>
                  {d.slice(0, 6)}
                </button>
              );
            })}
          </div>
        )}

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Buku Rekap...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : rekapRows.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Tidak ada data resort</p>
          </div>
        ) : (
          <>
            {/* Summary cards */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 10, marginBottom: 20 }}>
              <div style={{ background: '#e8f8f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Total Storting</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--success)' }}>{formatRpFull(totals.storting)}</p>
              </div>
              <div style={{ background: 'var(--primary-light)', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Target</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--primary)' }}>{formatRpFull(totals.target)}</p>
              </div>
              <div style={{ background: '#fef2f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Total Drop</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: 'var(--danger)' }}>{formatRpFull(totals.totalDrop)}</p>
              </div>
              <div style={{ background: '#f0f4ff', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Tunai Pasar</p>
                <p style={{ fontSize: 16, fontWeight: 700, color: totals.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRpFull(totals.tunaiPasar)}</p>
              </div>
            </div>

            {/* Table */}
            <div style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--card)' }}>
              <table style={{ width: '100%', minWidth: 1200, fontSize: 12, borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ background: '#f8f9fa' }}>
                    <th style={{ ...thStyle, textAlign: 'left', paddingLeft: 10 }}>Resort</th>
                    <th style={{ ...thStyle, background: '#e8f8f0' }}>Drop Baru</th>
                    <th style={{ ...thStyle, background: '#e8f8f0' }}>Drop Lama</th>
                    <th style={thStyle}>Target</th>
                    <th style={thStyle}>Kas Pakai</th>
                    <th style={{ ...thStyle, background: '#dff5eb' }}>Storting</th>
                    <th style={{ ...thStyle, background: '#dff5eb' }}>%</th>
                    <th style={thStyle}>Admin</th>
                    <th style={thStyle}>Tarik Tab.</th>
                    <th style={thStyle}>Tabungan</th>
                    <th style={{ ...thStyle, background: '#e0ecff' }}>Debit</th>
                    <th style={{ ...thStyle, background: '#fef2f0' }}>Drop Baru (Rp)</th>
                    <th style={{ ...thStyle, background: '#fef2f0' }}>Drop Lama (Rp)</th>
                    <th style={{ ...thStyle, background: '#fef2f0' }}>Total Drop</th>
                    <th style={thStyle}>Cair Tab.</th>
                    <th style={{ ...thStyle, background: '#fff3e0' }}>Kredit</th>
                    <th style={{ ...thStyle, background: '#f3e8ff' }}>Tunai Pasar</th>
                  </tr>
                </thead>
                <tbody>
                  {rekapRows.map((row, idx) => (
                    <tr key={idx} style={{ borderBottom: '1px solid var(--border-light)' }}>
                      <td style={tdNameStyle}>{row.resortName}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 600 }}>{row.dropBaru || '-'}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 600 }}>{row.dropLama || '-'}</td>
                      <td style={tdStyle}>{row.target > 0 ? formatRp(row.target) : '-'}</td>
                      <td style={tdStyle}>{row.kasPakai > 0 ? formatRp(row.kasPakai) : '-'}</td>
                      <td style={{ ...tdStyle, color: 'var(--success)', fontWeight: 600 }}>{row.storting > 0 ? formatRp(row.storting) : '-'}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 700, color: row.persen >= 100 ? 'var(--success)' : row.persen >= 50 ? '#b8860b' : 'var(--danger)' }}>{row.persen > 0 ? `${row.persen}%` : '-'}</td>
                      <td style={tdStyle}>{row.adminFee > 0 ? formatRp(row.adminFee) : '-'}</td>
                      <td style={tdStyle}>{row.tarikTabungan > 0 ? formatRp(row.tarikTabungan) : '-'}</td>
                      <td style={tdStyle}>{row.tabungan > 0 ? formatRp(row.tabungan) : '-'}</td>
                      <td style={{ ...tdStyle, color: '#1a56db', fontWeight: 600 }}>{row.debit > 0 ? formatRp(row.debit) : '-'}</td>
                      <td style={{ ...tdStyle, color: 'var(--danger)' }}>{row.nominalDropBaru > 0 ? formatRp(row.nominalDropBaru) : '-'}</td>
                      <td style={{ ...tdStyle, color: 'var(--danger)' }}>{row.nominalDropLama > 0 ? formatRp(row.nominalDropLama) : '-'}</td>
                      <td style={{ ...tdStyle, color: 'var(--danger)', fontWeight: 600 }}>{row.totalDrop > 0 ? formatRp(row.totalDrop) : '-'}</td>
                      <td style={tdStyle}>{row.pencairanTabungan > 0 ? formatRp(row.pencairanTabungan) : '-'}</td>
                      <td style={{ ...tdStyle, color: '#d97706', fontWeight: 600 }}>{row.kredit > 0 ? formatRp(row.kredit) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 700, color: row.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{row.tunaiPasar !== 0 ? formatRp(row.tunaiPasar) : '-'}</td>
                    </tr>
                  ))}
                  {/* TOTAL HARI INI ROW */}
                  <tr style={{ borderTop: '2px solid var(--border)', background: '#eff6ff', fontWeight: 800 }}>
                    <td style={{ ...tdNameStyle, fontWeight: 800, color: '#1a56db' }}>Total Hari Ini</td>
                    <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totals.dropBaru}</td>
                    <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totals.dropLama}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totals.target)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{totals.kasPakai > 0 ? formatRp(totals.kasPakai) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--success)' }}>{formatRp(totals.storting)}</td>
                    <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalPersen}%</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totals.adminFee)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{totals.tarikTabungan > 0 ? formatRp(totals.tarikTabungan) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totals.tabungan)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: '#1a56db' }}>{formatRp(totals.debit)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totals.nominalDropBaru)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totals.nominalDropLama)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totals.totalDrop)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{totals.pencairanTabungan > 0 ? formatRp(totals.pencairanTabungan) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: '#d97706' }}>{formatRp(totals.kredit)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: totals.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totals.tunaiPasar)}</td>
                  </tr>
                  {/* TOTAL KEMARIN ROW — hanya jika tanggal sebelumnya masih dalam bulan yang sama */}
                  {showKemarin && (
                    <tr style={{ borderTop: '1px solid var(--border-light)', background: '#f8f9fa', fontWeight: 800 }}>
                      <td style={{ ...tdNameStyle, fontWeight: 800, color: '#1e293b' }}>Total Kemarin</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalsKemarin.dropBaru}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalsKemarin.dropLama}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsKemarin.target)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsKemarin.kasPakai > 0 ? formatRp(totalsKemarin.kasPakai) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--success)' }}>{formatRp(totalsKemarin.storting)}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalPersenKemarin}%</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsKemarin.adminFee)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsKemarin.tarikTabungan > 0 ? formatRp(totalsKemarin.tarikTabungan) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsKemarin.tabungan)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: '#1a56db' }}>{formatRp(totalsKemarin.debit)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsKemarin.nominalDropBaru)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsKemarin.nominalDropLama)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsKemarin.totalDrop)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsKemarin.pencairanTabungan > 0 ? formatRp(totalsKemarin.pencairanTabungan) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: '#d97706' }}>{formatRp(totalsKemarin.kredit)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: totalsKemarin.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totalsKemarin.tunaiPasar)}</td>
                    </tr>
                  )}
                  {/* TOTAL (HARI INI + KEMARIN) ROW */}
                  {showKemarin && (
                    <tr style={{ borderTop: '2px solid #fca5a5', background: '#fff1f1', fontWeight: 800 }}>
                      <td style={{ ...tdNameStyle, fontWeight: 800, color: 'var(--danger)' }}>Total</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalsGabungan.dropBaru}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalsGabungan.dropLama}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsGabungan.target)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsGabungan.kasPakai > 0 ? formatRp(totalsGabungan.kasPakai) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--success)' }}>{formatRp(totalsGabungan.storting)}</td>
                      <td style={{ ...tdStyle, textAlign: 'center', fontWeight: 800 }}>{totalPersenGabungan}%</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsGabungan.adminFee)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsGabungan.tarikTabungan > 0 ? formatRp(totalsGabungan.tarikTabungan) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{formatRp(totalsGabungan.tabungan)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: '#1a56db' }}>{formatRp(totalsGabungan.debit)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsGabungan.nominalDropBaru)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsGabungan.nominalDropLama)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--danger)' }}>{formatRp(totalsGabungan.totalDrop)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800 }}>{totalsGabungan.pencairanTabungan > 0 ? formatRp(totalsGabungan.pencairanTabungan) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: '#d97706' }}>{formatRp(totalsGabungan.kredit)}</td>
                      <td style={{ ...tdStyle, fontWeight: 800, color: totalsGabungan.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totalsGabungan.tunaiPasar)}</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </>
        )}
      </main>
    </div>
  );
}

// ============================================================
// KAS PENUNTUN SCREEN
// ============================================================
function KasPenuntunScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(() => resolveInitialCabang(cabang, cabangList));
  const [bulan, setBulan] = useState(getCurrentMonthKey());
  const [bukuData, setBukuData] = useState(null);
  const [kasirEntries, setKasirEntries] = useState([]);
  const [prevKasirEntries, setPrevKasirEntries] = useState([]);
  const [jurnalEntries, setJurnalEntries] = useState([]);
  const [prevJurnalEntries, setPrevJurnalEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showFaktur, setShowFaktur] = useState(null);

  const bulanOptions = generateBulanOptions();

  // Compute previous month key for Saldo Kas Bulan Lalu
  const prevBulan = (() => {
    const [y, m] = bulan.split('-');
    const d = new Date(parseInt(y), parseInt(m) - 2, 1);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  })();

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    Promise.all([
      // status 'semua' (bukan 'aktif'): nasabah lunas-hari-ini & MENUNGGU_PENCAIRAN
      // tetap menyumbang storting/drop pada tanggalnya. Konsisten dengan
      // BukuRekapScreen (line 1530) & BukuEkspedisiScreen (line 2673) — wajib
      // agar computeTunaiKasPerDate menghasilkan nilai identik di tiga layar.
      getBukuPokok({ cabangId: activeCabang.id, adminUid: '', status: 'semua' }),
      getKasirEntries({ cabangId: activeCabang.id, bulan }),
      getKasirEntries({ cabangId: activeCabang.id, bulan: prevBulan }),
      getJurnalTransaksi({ cabangId: activeCabang.id, bulan }),
      getJurnalTransaksi({ cabangId: activeCabang.id, bulan: prevBulan }),
    ]).then(([bukuResult, kasirResult, prevKasirResult, jurnalResult, prevJurnalResult]) => {
      if (bukuResult.success && bukuResult.type === 'buku_pokok') {
        setBukuData(bukuResult.data);
      }
      if (kasirResult.success) {
        setKasirEntries(kasirResult.data.entries || []);
      }
      if (prevKasirResult.success) {
        setPrevKasirEntries(prevKasirResult.data.entries || []);
      } else {
        setPrevKasirEntries([]);
      }
      setJurnalEntries(jurnalResult?.success && jurnalResult.data?.entries ? jurnalResult.data.entries : []);
      setPrevJurnalEntries(prevJurnalResult?.success && prevJurnalResult.data?.entries ? prevJurnalResult.data.entries : []);
    }).catch(err => {
      setError('Gagal memuat data: ' + err.message);
    }).finally(() => setLoading(false));
  }, [activeCabang?.id, bulan]);

  // ==================== COMPUTE KAS PENUNTUN ROWS ====================
  const penuntunRows = (() => {
    if (!bukuData?.nasabah) return [];

    const allNasabah = bukuData.nasabah;

    const BULAN_MAP_REV = {};
    BULAN_INDO.forEach((b, i) => { BULAN_MAP_REV[b] = i; });
    const parseDateStr = (s) => {
      if (!s) return null;
      const parts = s.split(' ');
      if (parts.length !== 3) return null;
      const m = BULAN_MAP_REV[parts[1]];
      if (m === undefined) return null;
      return new Date(parseInt(parts[2]), m, parseInt(parts[0]));
    };

    // Index nasabah per resort sekali, dipakai computeTunaiKasPerDate (helper
    // top-level) di loop tanggal di bawah agar tidak re-filter per hari.
    const admins = activeCabang?.admins || [];
    const nasabahByAdmin = {};
    admins.forEach(adm => {
      nasabahByAdmin[adm.uid] = allNasabah.filter(n => n.adminUid === adm.uid);
    });
    // Pencairan tabungan per (tanggal, admin) — kredit tambahan untuk tunaiPasar.
    const pencairanByAdminDate = buildPencairanByAdminDate(jurnalEntries);

    // ===== Compute Saldo Kas Bulan Lalu (helper bersama dengan Buku Ekspedisi) =====
    const saldoKasBulanLalu = computeSaldoKasBulanLalu({
      bukuData,
      currentMonthEntries: kasirEntries,
      prevMonthEntries: prevKasirEntries,
      prevMonthJurnalEntries: prevJurnalEntries,
      bulan,
      activeCabang,
    });

    // ===== Data bulan ini =====
    const [yyyy, mm] = bulan.split('-');
    const monthStart = new Date(parseInt(yyyy), parseInt(mm) - 1, 1);
    const monthEnd = new Date(parseInt(yyyy), parseInt(mm), 0);

    // Batas: tidak boleh lebih dari hari ini (WIB)
    const nowKP = new Date();
    const wibOffKP = 7 * 60 * 60 * 1000;
    const wibKP = new Date(nowKP.getTime() + (nowKP.getTimezoneOffset() * 60000) + wibOffKP);
    const todayLimit = new Date(wibKP.getFullYear(), wibKP.getMonth(), wibKP.getDate());
    const effectiveEnd = monthEnd <= todayLimit ? monthEnd : todayLimit;

    const dateSet = new Set();
    allNasabah.forEach(n => {
      if (n.pembayaran) {
        Object.keys(n.pembayaran).forEach(d => {
          const date = parseDateStr(d);
          if (date && date >= monthStart && date <= effectiveEnd) dateSet.add(d);
        });
      }
      const tglCair = (n.tanggalPencairan || '').trim();
      if (tglCair) {
        const date = parseDateStr(tglCair);
        if (date && date >= monthStart && date <= effectiveEnd) dateSet.add(tglCair);
      }
    });

    kasirEntries.forEach(e => {
      const tgl = e.tanggal;
      if (!tgl) return;
      const date = parseDateStr(tgl);
      if (date && date >= monthStart && date <= effectiveEnd) dateSet.add(tgl);
    });

    const sortedDates = Array.from(dateSet)
      .filter(d => { const dt = parseDateStr(d); return dt && isHariKerja(dt); })
      .sort((a, b) => parseDateStr(a) - parseDateStr(b));

    // Hitung tunaiPasar & kasPakai per tanggal — via helper top-level
    // (sumber kebenaran: Buku Rekap "Total Hari Ini").
    const tunaiPasarPerDate = {};
    const kasPakaiPerDate = {};
    // Orphan dilewatkan agar storting helper match BukuRekap (pimpinan 11 Jun 2026).
    const orphanByDate = bukuData?.orphanPaymentsByDate || {};
    sortedDates.forEach(dateStr => {
      // rekapBeku + serverToday → parity Buku Rekap utk Kas Pakai/Tunai Pasar
      // tanggal historis (snapshot otoritas; hari berjalan tetap live).
      const { tunaiPasar, kasPakai } = computeTunaiKasPerDate(dateStr, nasabahByAdmin, admins, pencairanByAdminDate, orphanByDate, bukuData?.rekapBeku, bukuData?.today);
      tunaiPasarPerDate[dateStr] = tunaiPasar;
      kasPakaiPerDate[dateStr] = kasPakai;
    });

    // Kasir entries: suntikan, pinjaman, BU per tanggal
    const suntikanDanaPerDate = {};
    const pinjamanKasPerDate = {};
    const buPerDate = {};
    const buFakturPerDate = {};
    kasirEntries.forEach(e => {
      const tgl = e.tanggal;
      if (!tgl) return;
      if (e.jenis === 'suntikan_dana' && e.arah === 'masuk') {
        suntikanDanaPerDate[tgl] = (suntikanDanaPerDate[tgl] || 0) + (e.jumlah || 0);
      }
      if (e.jenis === 'pinjaman_kas' && e.arah === 'masuk') {
        pinjamanKasPerDate[tgl] = (pinjamanKasPerDate[tgl] || 0) + (e.jumlah || 0);
      }
      if (e.jenis === 'penggajian' && e.arah === 'keluar') {
        // Hanya hitung BU yang targetnya kas_penuntun, atau entry lama tanpa targetBuku
        const buku = e.targetBuku;
        if (!buku || (Array.isArray(buku) && buku.includes('kas_penuntun'))) {
          buPerDate[tgl] = (buPerDate[tgl] || 0) + (e.jumlah || 0);
          if (!buFakturPerDate[tgl]) buFakturPerDate[tgl] = [];
          buFakturPerDate[tgl].push({ jumlah: e.jumlah || 0, fakturUrl: e.fakturUrl || null, keterangan: e.keterangan || '' });
        }
      }
    });

    // ===== Build result dengan 3-baris untuk Tunai Pasar, Debit, Kas Pakai, Kredit, Saldo Kas =====
    const result = [];
    let prevTunaiPasarTotal = 0;
    let prevDebitTotal = 0;
    let prevKasPakaiTotal = 0;

    sortedDates.forEach((dateStr, idx) => {
      const tunaiPasarHariIni = tunaiPasarPerDate[dateStr] || 0;
      const suntikanDana = suntikanDanaPerDate[dateStr] || 0;
      const pinjamanKas = pinjamanKasPerDate[dateStr] || 0;
      const kasPakaiHariIni = kasPakaiPerDate[dateStr] || 0;
      const bu = buPerDate[dateStr] || 0;
      const buFaktur = buFakturPerDate[dateStr] || [];

      let tunaiPasarR1, tunaiPasarR2, tunaiPasarR3;
      let debitR1, debitR2, debitR3;
      let kasPakaiR1, kasPakaiR2, kasPakaiR3;
      let kreditR1, kreditR2, kreditR3;
      let saldoKasR1, saldoKasR2, saldoKasR3;

      if (idx === 0) {
        // Tanggal pertama bulan ini
        tunaiPasarR1 = tunaiPasarHariIni;
        tunaiPasarR2 = null;
        tunaiPasarR3 = null;

        debitR1 = tunaiPasarHariIni + saldoKasBulanLalu;
        debitR2 = null;
        debitR3 = null;

        kasPakaiR1 = kasPakaiHariIni;
        kasPakaiR2 = null;
        kasPakaiR3 = null;

        kreditR1 = kasPakaiR1 + bu;
        kreditR2 = null;
        kreditR3 = null;

        saldoKasR1 = debitR1 - kreditR1;
        saldoKasR2 = null;
        saldoKasR3 = null;

        prevTunaiPasarTotal = tunaiPasarR1;
        prevDebitTotal = debitR1;
        prevKasPakaiTotal = kasPakaiR1;
      } else {
        // Tanggal berikutnya
        tunaiPasarR1 = tunaiPasarHariIni;
        tunaiPasarR2 = prevTunaiPasarTotal;
        tunaiPasarR3 = tunaiPasarR1 + tunaiPasarR2;

        debitR1 = tunaiPasarHariIni;
        debitR2 = prevDebitTotal;
        debitR3 = debitR1 + debitR2;

        kasPakaiR1 = kasPakaiHariIni;
        kasPakaiR2 = prevKasPakaiTotal;
        kasPakaiR3 = kasPakaiR1 + kasPakaiR2;

        kreditR1 = kasPakaiR1 + bu;
        kreditR2 = kasPakaiR2;
        kreditR3 = kreditR1 + kreditR2;

        saldoKasR1 = debitR1 - kreditR1;
        saldoKasR2 = debitR2 - kreditR2;
        saldoKasR3 = saldoKasR1 + saldoKasR2;

        prevTunaiPasarTotal = tunaiPasarR3;
        prevDebitTotal = debitR3;
        prevKasPakaiTotal = kasPakaiR3;
      }

      result.push({
        tanggal: dateStr,
        tunaiPasarR1, tunaiPasarR2, tunaiPasarR3,
        suntikanDana, pinjamanKas,
        saldoKasBulanLalu,
        debitR1, debitR2, debitR3,
        kasPakaiR1, kasPakaiR2, kasPakaiR3,
        shu: 0, bu, buFaktur,
        kreditR1, kreditR2, kreditR3,
        saldoKasR1, saldoKasR2, saldoKasR3,
      });
    });

    return result;
  })();

  const thS = { padding: '7px 6px', textAlign: 'center', fontWeight: 700, fontSize: 10, whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2, borderBottom: '2px solid var(--border)', borderRight: '1px solid var(--border)' };
  const tdR = { padding: '5px 6px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11, borderRight: '1px solid var(--border-light)' };
  const rowBorderBottom = { borderBottom: '2px solid var(--border)' };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Kas Penuntun</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'} — {formatBulanLabel(bulan)}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="kasPenuntun" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap' }}>
          {!isUnit && cabangList.length > 1 && (
            <select value={activeCabang?.id || ''} onChange={(e) => selectCabangById(cabangList, e.target.value, setActiveCabang)}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          <select value={bulan} onChange={(e) => setBulan(e.target.value)}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
        </div>

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Kas Penuntun...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : penuntunRows.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Tidak ada data untuk bulan {formatBulanLabel(bulan)}</p>
          </div>
        ) : (
          <div style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--card)' }}>
            <table style={{ width: '100%', minWidth: 1100, fontSize: 12, borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ background: '#f8f9fa' }}>
                  <th style={{ ...thS, textAlign: 'left', paddingLeft: 10 }} rowSpan={2}>Tanggal</th>
                  <th style={{ ...thS, background: '#e8f8f0' }} colSpan={1}>Tunai Pasar</th>
                  <th style={{ ...thS, background: '#e0f0ff' }} rowSpan={2}>Suntikan Dana</th>
                  <th style={{ ...thS, background: '#e0f0ff' }} rowSpan={2}>Pinjaman Kas</th>
                  <th style={{ ...thS }} rowSpan={2}>Saldo Kas Bulan Lalu</th>
                  <th style={{ ...thS, background: '#dbeafe' }} colSpan={1}>Debit</th>
                  <th style={{ ...thS, background: '#fef9c3' }} colSpan={1}>Kas Pakai</th>
                  <th style={{ ...thS }} rowSpan={2}>SHU/SP</th>
                  <th style={{ ...thS, background: '#ffe4e6' }} rowSpan={2}>BU</th>
                  <th style={{ ...thS, background: '#fde8c8' }} colSpan={1}>Kredit</th>
                  <th style={{ ...thS, background: '#f3e8ff' }} colSpan={1}>Saldo Kas</th>
                </tr>
                <tr style={{ background: '#f8f9fa' }}>
                  <th style={{ ...thS, background: '#e8f8f0', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                  <th style={{ ...thS, background: '#dbeafe', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                  <th style={{ ...thS, background: '#fef9c3', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                  <th style={{ ...thS, background: '#fde8c8', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                  <th style={{ ...thS, background: '#f3e8ff', fontSize: 9, fontWeight: 500 }}>Hari Ini / Kemarin / Total</th>
                </tr>
              </thead>
              <tbody>
                {penuntunRows.map((row) => (
                  <>
                    <tr key={`${row.tanggal}-r1`} style={{ borderTop: '2px solid var(--border)' }}>
                      <td rowSpan={3} style={{ padding: '6px 10px', fontWeight: 700, fontSize: 12, verticalAlign: 'middle', borderRight: '1px solid var(--border)', whiteSpace: 'nowrap', background: '#fafafa' }}>
                        {row.tanggal.slice(0, 6)}
                      </td>
                      <td style={{ ...tdR, background: '#f0fdf4', color: row.tunaiPasarR1 >= 0 ? '#166534' : 'var(--danger)', fontWeight: 600 }}>
                        {row.tunaiPasarR1 !== 0 ? formatRp(row.tunaiPasarR1) : '-'}
                      </td>
                      <td rowSpan={3} style={{ ...tdR, background: '#eff6ff' }}>{row.suntikanDana > 0 ? formatRp(row.suntikanDana) : '-'}</td>
                      <td rowSpan={3} style={{ ...tdR, background: '#eff6ff' }}>{row.pinjamanKas > 0 ? formatRp(row.pinjamanKas) : '-'}</td>
                      <td rowSpan={3} style={{ ...tdR }}>{row.saldoKasBulanLalu !== 0 ? formatRp(row.saldoKasBulanLalu) : '-'}</td>
                      <td style={{ ...tdR, background: '#eff6ff', fontWeight: 600, color: '#1d4ed8' }}>
                        {row.debitR1 !== 0 ? formatRp(row.debitR1) : '-'}
                      </td>
                      <td style={{ ...tdR, background: '#fefce8' }}>
                        {row.kasPakaiR1 > 0 ? formatRp(row.kasPakaiR1) : '-'}
                      </td>
                      <td rowSpan={3} style={{ ...tdR }}>-</td>
                      <td rowSpan={3} style={{ ...tdR, background: '#fff1f2', cursor: row.bu > 0 ? 'pointer' : 'default', textDecoration: row.bu > 0 ? 'underline' : 'none' }}
                        onClick={() => { if (row.bu > 0 && row.buFaktur?.length > 0) setShowFaktur(row.buFaktur); }}
                      >{row.bu > 0 ? formatRp(row.bu) : '-'}</td>
                      <td style={{ ...tdR, background: '#fff7ed', color: '#b45309', fontWeight: 600 }}>
                        {row.kreditR1 > 0 ? formatRp(row.kreditR1) : '-'}
                      </td>
                      <td style={{ ...tdR, background: '#faf5ff', fontWeight: 700, color: row.saldoKasR1 >= 0 ? '#7e22ce' : 'var(--danger)' }}>
                        {row.saldoKasR1 !== 0 ? formatRp(row.saldoKasR1) : '-'}
                      </td>
                    </tr>
                    <tr key={`${row.tanggal}-r2`}>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.tunaiPasarR2 != null && row.tunaiPasarR2 !== 0 ? formatRp(row.tunaiPasarR2) : '-'}
                      </td>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.debitR2 != null ? formatRp(row.debitR2) : '-'}
                      </td>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.kasPakaiR2 != null && row.kasPakaiR2 > 0 ? formatRp(row.kasPakaiR2) : '-'}
                      </td>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.kreditR2 != null && row.kreditR2 > 0 ? formatRp(row.kreditR2) : '-'}
                      </td>
                      <td style={{ ...tdR, color: 'var(--text-muted)', fontSize: 10 }}>
                        {row.saldoKasR2 != null ? formatRp(row.saldoKasR2) : '-'}
                      </td>
                    </tr>
                    <tr key={`${row.tanggal}-r3`} style={rowBorderBottom}>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)' }}>
                        {row.tunaiPasarR3 != null ? formatRp(row.tunaiPasarR3) : '-'}
                      </td>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)', color: '#1d4ed8' }}>
                        {row.debitR3 != null ? formatRp(row.debitR3) : '-'}
                      </td>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)' }}>
                        {row.kasPakaiR3 != null && row.kasPakaiR3 > 0 ? formatRp(row.kasPakaiR3) : '-'}
                      </td>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)', color: '#b45309' }}>
                        {row.kreditR3 != null && row.kreditR3 > 0 ? formatRp(row.kreditR3) : '-'}
                      </td>
                      <td style={{ ...tdR, fontWeight: 800, borderTop: '1px solid var(--border-light)', color: row.saldoKasR3 != null && row.saldoKasR3 >= 0 ? '#7e22ce' : 'var(--danger)' }}>
                        {row.saldoKasR3 != null ? formatRp(row.saldoKasR3) : '-'}
                      </td>
                    </tr>
                  </>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </main>

      {/* Faktur Modal */}
      {showFaktur && <FakturModal fakturList={showFaktur} onClose={() => setShowFaktur(null)} />}
    </div>
  );
}


// ============================================================
// BUKU TUNAI SCREEN
// Kolom: Nama Resort | Kasbon Pagi | Kas Pakai | Kembali Kasbon | Tunai Pasar | Titipan | +/-
// Data: getBukuPokok (nasabah) + getKasirEntries (uang_kas per admin)
// ============================================================
function BukuTunaiScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(() => resolveInitialCabang(cabang, cabangList));
  const [bukuData, setBukuData] = useState(null);
  const [kasirEntries, setKasirEntries] = useState([]);
  const [jurnalEntries, setJurnalEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedDate, setSelectedDate] = useState(null);
  const bulanOptions = generateBulanOptions();
  const [selectedBulan, setSelectedBulan] = useState(bulanOptions[0]?.key || '');

  // Fetch buku pokok (hanya saat activeCabang berubah)
  useEffect(() => {
    if (!activeCabang) return;
    setBukuData(null);
    setSelectedDate(null);
    // status 'semua' (bukan 'aktif'): nasabah lunas-hari-ini & MENUNGGU_PENCAIRAN
    // tetap menyumbang storting/drop pada tanggalnya. Konsisten dengan
    // BukuRekapScreen (line 1530), BukuEkspedisiScreen (line 2673), dan
    // KasPenuntunScreen (line 1993) — wajib agar Tunai Pasar per resort di
    // Buku Tunai cocok dengan baris per resort Buku Rekap untuk tanggal sama.
    getBukuPokok({ cabangId: activeCabang.id, adminUid: '', status: 'semua' })
      .then(result => {
        if (result.success && result.type === 'buku_pokok') {
          setBukuData(result.data);
        }
      })
      .catch(() => {});
  }, [activeCabang?.id]);

  // Fetch kasir entries + jurnal_transaksi (saat activeCabang atau selectedBulan berubah)
  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    setKasirEntries([]);
    setJurnalEntries([]);
    setSelectedDate(null);
    Promise.all([
      getKasirEntries({ cabangId: activeCabang.id, bulan: selectedBulan }),
      getJurnalTransaksi({ cabangId: activeCabang.id, bulan: selectedBulan }),
    ])
      .then(([kasirResult, jurnalResult]) => {
        setKasirEntries(kasirResult?.success ? kasirResult.data.entries || [] : []);
        setJurnalEntries(jurnalResult?.success && jurnalResult.data?.entries ? jurnalResult.data.entries : []);
      })
      .catch(err => setError('Gagal memuat data: ' + err.message))
      .finally(() => setLoading(false));
  }, [activeCabang?.id, selectedBulan]);

  // Tanggal dalam bulan yang dipilih (skip Minggu & tanggal merah, sama
  // dengan Buku Pokok / Buku Rekap)
  const [selBulanYear, selBulanMonth] = selectedBulan.split('-').map(Number);
  const dates = (bukuData?.tanggalList || []).filter(d => {
    const parts = d.split(' ');
    if (parts.length < 3) return false;
    const dMonth = BULAN_INDO.indexOf(parts[1]) + 1;
    const dYear = parseInt(parts[2]);
    if (dYear !== selBulanYear || dMonth !== selBulanMonth) return false;
    const dateObj = parseTanggalIndo(d);
    return dateObj && isHariKerja(dateObj);
  });
  const currentDate = selectedDate || dates[0] || null;

  // Hitung baris per resort untuk tanggal terpilih
  const tunaiRows = (() => {
    if (!bukuData?.nasabah || !currentDate) return [];
    const allNasabah = bukuData.nasabah;
    const admins = activeCabang?.admins || [];
    const dateStr = currentDate;

    // Bangun peta kasbon: { adminUid: totalKasbon } untuk tanggal ini
    const kasbonMap = {};
    kasirEntries.forEach(e => {
      if (e.jenis === 'uang_kas' && e.tanggal === dateStr && e.targetAdminUid) {
        kasbonMap[e.targetAdminUid] = (kasbonMap[e.targetAdminUid] || 0) + (e.jumlah || 0);
      }
    });

    const isDropBaru = (n) => (n.pinjamanKe || 1) <= 1;

    // Pre-build nasabahByAdmin sekali; computeTunaiKasPerDate dipanggil
    // per resort dengan admins=[adm] agar dapat per-resort tunaiPasar/kasPakai
    // dari helper yang sama dengan totals Buku Rekap & Buku Ekspedisi.
    const nasabahByAdmin = {};
    admins.forEach(adm => {
      nasabahByAdmin[adm.uid] = allNasabah.filter(n => n.adminUid === adm.uid);
    });
    // Pencairan tabungan per (tanggal, admin) — kredit tambahan untuk tunaiPasar
    // (parity dengan BukuRekap / KasPenuntun / BukuEkspedisi).
    const pencairanByAdminDate = buildPencairanByAdminDate(jurnalEntries);
    // Orphan storting per tanggal — wajib di-pass agar helper match BukuRekap
    // (pimpinan 11 Jun 2026 "Buku Tunai vs Buku Rekap").
    const orphanByDate = bukuData?.orphanPaymentsByDate || {};

    const rows = [];
    for (const adm of admins) {
      // Kasbon Pagi = total uang_kas yang dikirim kasir ke admin ini pada tanggal ini
      const kasbonPagi = kasbonMap[adm.uid] || 0;

      // Tunai Pasar & Kas Pakai per resort — via helper top-level
      // computeTunaiKasPerDate (Source of Truth: Buku Rekap baris per resort).
      // rekapBeku + serverToday → parity Buku Rekap utk tanggal historis.
      const { tunaiPasar, kasPakai } = computeTunaiKasPerDate(dateStr, nasabahByAdmin, [adm], pencairanByAdminDate, orphanByDate, bukuData?.rekapBeku, bukuData?.today);

      // Titipan & +/- (totalFisik) dari helper bersama (kembaliKasbon helper di-ignore;
      // diganti rumus strict pimpinan di bawah).
      const { titipan, totalFisik } =
        decomposeKembaliKasbonTitipan(kasbonPagi, tunaiPasar, kasPakai);

      // ✅ Kembali Kasbon — rumus STRICT pimpinan 11 Jun 2026:
      //   kembaliKasbon = kasbonPagi - kasPakai
      // Sebelumnya helper memakai "waterfall" (kembaliKasbon=kasbonPagi bila
      // totalFisik mencukupi, else 0) yang tidak sesuai aturan pimpinan baru.
      // Override hanya di kolom BukuTunai (per scope pimpinan); helper tetap
      // dipakai BukuEkspedisi & saldoKasBulanLalu apa adanya.
      // CATATAN: bila kasPakai > kasbonPagi, hasil bisa negatif (Kas Pakai
      // melebihi kasbon → admin "berhutang" ke kas). Mengikuti instruksi
      // "strictly" pimpinan — tidak di-clamp ke 0.
      const kembaliKasbon = kasbonPagi - kasPakai;

      // +/- tetap totalFisik (fisik dibawa pulang admin = kasbonPagi + tunaiPasar - kasPakai).
      // CATATAN setelah perubahan Kembali Kasbon strict: kembaliKasbon + titipan
      // tidak lagi selalu == +/- (karena titipan masih dari rumus helper waterfall).
      // Pimpinan eksplisit minta Titipan tidak diubah pada batch ini (point #4 =
      // EXPLAIN, bukan FIX); +/- tetap menampilkan total fisik aktual.
      const plusMinus = totalFisik;

      rows.push({ resortName: adm.name, kasbonPagi, kasPakai, kembaliKasbon, tunaiPasar, titipan, plusMinus });
    }
    return rows;
  })();

  // Total semua resort
  const totals = tunaiRows.reduce((acc, r) => ({
    kasbonPagi: acc.kasbonPagi + r.kasbonPagi,
    kasPakai: acc.kasPakai + r.kasPakai,
    kembaliKasbon: acc.kembaliKasbon + r.kembaliKasbon,
    tunaiPasar: acc.tunaiPasar + r.tunaiPasar,
    titipan: acc.titipan + r.titipan,
    plusMinus: acc.plusMinus + r.plusMinus,
  }), { kasbonPagi: 0, kasPakai: 0, kembaliKasbon: 0, tunaiPasar: 0, titipan: 0, plusMinus: 0 });

  const thStyle = { padding: '8px 6px', textAlign: 'center', fontWeight: 700, fontSize: 11, whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#f8f9fa', zIndex: 2, borderBottom: '2px solid var(--border)' };
  const tdStyle = { padding: '8px 6px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 12 };
  const tdNameStyle = { padding: '8px', textAlign: 'left', fontWeight: 600, fontSize: 12, whiteSpace: 'nowrap' };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Buku Tunai</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}{currentDate ? ` — ${currentDate}` : ''}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="bukuTunai" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        {/* Cabang selector untuk kasir_wilayah */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 16 }}>
            <select value={activeCabang?.id || ''} onChange={(e) => { selectCabangById(cabangList, e.target.value, setActiveCabang); }}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </div>
        )}

        {/* Pilih bulan */}
        <div style={{ marginBottom: 12 }}>
          <select
            value={selectedBulan}
            onChange={(e) => setSelectedBulan(e.target.value)}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)', color: 'var(--text)' }}
          >
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
        </div>

        {/* Tab tanggal — hari kerja pada bulan terpilih */}
        {!loading && dates.length > 0 && (
          <div style={{ display: 'flex', gap: 6, marginBottom: 16, overflowX: 'auto', paddingBottom: 4 }}>
            {dates.map(d => {
              const isActive = currentDate === d;
              return (
                <button key={d} onClick={() => setSelectedDate(d)} style={{
                  padding: '6px 14px', borderRadius: 20, fontSize: 12, fontWeight: 600,
                  whiteSpace: 'nowrap', cursor: 'pointer',
                  border: `1px solid ${isActive ? 'var(--primary)' : 'var(--border)'}`,
                  background: isActive ? 'var(--primary)' : 'var(--card)',
                  color: isActive ? '#fff' : 'var(--text)',
                  transition: 'all 0.15s',
                }}>
                  {d.slice(0, 6)}
                </button>
              );
            })}
          </div>
        )}

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Buku Tunai...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : tunaiRows.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Tidak ada data resort</p>
          </div>
        ) : (
          <>
            {/* Kartu ringkasan */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 10, marginBottom: 20 }}>
              <div style={{ background: 'var(--primary-light)', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Kasbon Pagi</p>
                <p style={{ fontSize: 15, fontWeight: 700, color: 'var(--primary)' }}>{formatRpFull(totals.kasbonPagi)}</p>
              </div>
              <div style={{ background: '#e8f8f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Kembali Kasbon</p>
                <p style={{ fontSize: 15, fontWeight: 700, color: 'var(--success)' }}>{formatRpFull(totals.kembaliKasbon)}</p>
              </div>
              <div style={{ background: '#f0f4ff', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>Tunai Pasar</p>
                <p style={{ fontSize: 15, fontWeight: 700, color: totals.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRpFull(totals.tunaiPasar)}</p>
              </div>
              <div style={{ background: totals.plusMinus >= 0 ? '#e8f8f0' : '#fef2f0', borderRadius: 12, padding: '12px 16px' }}>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>+/-</p>
                <p style={{ fontSize: 15, fontWeight: 700, color: totals.plusMinus >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRpFull(totals.plusMinus)}</p>
              </div>
            </div>

            {/* Tabel */}
            <div style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--card)' }}>
              <table style={{ width: '100%', minWidth: 680, fontSize: 12, borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ background: '#f8f9fa' }}>
                    <th style={{ ...thStyle, textAlign: 'left', paddingLeft: 10 }}>Nama Resort</th>
                    <th style={{ ...thStyle, background: 'var(--primary-light)' }}>Kasbon Pagi</th>
                    <th style={thStyle}>Kas Pakai</th>
                    <th style={{ ...thStyle, background: '#e8f8f0' }}>Kembali Kasbon</th>
                    <th style={{ ...thStyle, background: '#f0f4ff' }}>Tunai Pasar</th>
                    <th style={thStyle}>Titipan</th>
                    <th style={{ ...thStyle, background: '#f3e8ff' }}>+/-</th>
                  </tr>
                </thead>
                <tbody>
                  {tunaiRows.map((row, idx) => (
                    <tr key={idx} style={{ borderBottom: '1px solid var(--border-light)' }}>
                      <td style={tdNameStyle}>{row.resortName}</td>
                      <td style={{ ...tdStyle, color: 'var(--primary)', fontWeight: 600 }}>{row.kasbonPagi > 0 ? formatRp(row.kasbonPagi) : '-'}</td>
                      <td style={tdStyle}>{row.kasPakai > 0 ? formatRp(row.kasPakai) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 600, color: row.kembaliKasbon >= 0 ? 'var(--success)' : 'var(--danger)' }}>{row.kembaliKasbon !== 0 ? formatRp(row.kembaliKasbon) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 700, color: row.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{row.tunaiPasar !== 0 ? formatRp(row.tunaiPasar) : '-'}</td>
                      <td style={tdStyle}>{row.titipan > 0 ? formatRp(row.titipan) : '-'}</td>
                      <td style={{ ...tdStyle, fontWeight: 700, color: row.plusMinus >= 0 ? 'var(--success)' : 'var(--danger)' }}>{row.plusMinus !== 0 ? formatRp(row.plusMinus) : '-'}</td>
                    </tr>
                  ))}
                  {/* Baris total */}
                  <tr style={{ borderTop: '2px solid var(--border)', background: '#f8f9fa' }}>
                    <td style={{ ...tdNameStyle, fontWeight: 800 }}>TOTAL</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: 'var(--primary)' }}>{totals.kasbonPagi > 0 ? formatRp(totals.kasbonPagi) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{totals.kasPakai > 0 ? formatRp(totals.kasPakai) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: totals.kembaliKasbon >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totals.kembaliKasbon)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: totals.tunaiPasar >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totals.tunaiPasar)}</td>
                    <td style={{ ...tdStyle, fontWeight: 800 }}>{totals.titipan > 0 ? formatRp(totals.titipan) : '-'}</td>
                    <td style={{ ...tdStyle, fontWeight: 800, color: totals.plusMinus >= 0 ? 'var(--success)' : 'var(--danger)' }}>{formatRp(totals.plusMinus)}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </>
        )}
      </main>
    </div>
  );
}


// ============================================================
// BUKU EKSPEDISI SCREEN
// Kolom: Tanggal | Kembali Kasbon | Tunai Pasar | Drop Pusat |
//        Kasbon Pagi | Transport | BU | Pengembalian Kas+SP | Tunai Kas
// ============================================================
function BukuEkspedisiScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(() => resolveInitialCabang(cabang, cabangList));
  const [bulan, setBulan] = useState(getCurrentMonthKey());
  const [bukuData, setBukuData] = useState(null);
  const [kasirEntries, setKasirEntries] = useState([]);
  const [prevKasirEntries, setPrevKasirEntries] = useState([]);
  const [jurnalEntries, setJurnalEntries] = useState([]);
  const [prevJurnalEntries, setPrevJurnalEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showFaktur, setShowFaktur] = useState(null);

  const bulanOptions = generateBulanOptions();

  // prevBulan = bulan sebelumnya (YYYY-MM) — dibutuhkan helper saldoKasBulanLalu
  // untuk Path B (replay ledger bulan sebelumnya).
  const prevBulan = (() => {
    const [y, m] = bulan.split('-');
    const d = new Date(parseInt(y), parseInt(m) - 2, 1);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  })();

  // Saldo Kas Bulan Lalu — pakai helper bersama agar identik dengan kolom
  // "Saldo Kas Bulan Lalu" di Kas Penuntun (Path A override + Path B replay).
  const saldoKasBulanLalu = computeSaldoKasBulanLalu({
    bukuData,
    currentMonthEntries: kasirEntries,
    prevMonthEntries: prevKasirEntries,
    prevMonthJurnalEntries: prevJurnalEntries,
    bulan,
    activeCabang,
  });

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    Promise.all([
      getBukuPokok({ cabangId: activeCabang.id, adminUid: '', status: 'semua' }),
      getKasirEntries({ cabangId: activeCabang.id, bulan }),
      getKasirEntries({ cabangId: activeCabang.id, bulan: prevBulan }),
      getJurnalTransaksi({ cabangId: activeCabang.id, bulan }),
      getJurnalTransaksi({ cabangId: activeCabang.id, bulan: prevBulan }),
    ]).then(([bukuResult, kasirResult, prevKasirResult, jurnalResult, prevJurnalResult]) => {
      if (bukuResult.success && bukuResult.type === 'buku_pokok') {
        setBukuData(bukuResult.data);
      }
      if (kasirResult.success) {
        setKasirEntries(kasirResult.data.entries || []);
      }
      if (prevKasirResult.success) {
        setPrevKasirEntries(prevKasirResult.data.entries || []);
      } else {
        setPrevKasirEntries([]);
      }
      setJurnalEntries(jurnalResult?.success && jurnalResult.data?.entries ? jurnalResult.data.entries : []);
      setPrevJurnalEntries(prevJurnalResult?.success && prevJurnalResult.data?.entries ? prevJurnalResult.data.entries : []);
    }).catch(err => {
      setError('Gagal memuat data: ' + err.message);
    }).finally(() => setLoading(false));
  }, [activeCabang?.id, bulan]);

  // ==================== COMPUTE BUKU EKSPEDISI ROWS ====================
  const ekspedisiRows = (() => {
    if (!bukuData?.nasabah) return [];

    const allNasabah = bukuData.nasabah;

    const BULAN_MAP_REV = {};
    BULAN_INDO.forEach((b, i) => { BULAN_MAP_REV[b] = i; });
    const parseDateStr = (s) => {
      if (!s) return null;
      const parts = s.split(' ');
      if (parts.length !== 3) return null;
      const m = BULAN_MAP_REV[parts[1]];
      if (m === undefined) return null;
      return new Date(parseInt(parts[2]), m, parseInt(parts[0]));
    };

    const [yyyy, mm] = bulan.split('-');
    const monthStart = new Date(parseInt(yyyy), parseInt(mm) - 1, 1);
    const monthEnd = new Date(parseInt(yyyy), parseInt(mm), 0);

    // Batas: tidak boleh lebih dari hari ini (WIB)
    const nowEK = new Date();
    const wibOffEK = 7 * 60 * 60 * 1000;
    const wibEK = new Date(nowEK.getTime() + (nowEK.getTimezoneOffset() * 60000) + wibOffEK);
    const todayLimitEK = new Date(wibEK.getFullYear(), wibEK.getMonth(), wibEK.getDate());
    const effectiveEndEK = monthEnd <= todayLimitEK ? monthEnd : todayLimitEK;

    // Kumpulkan semua tanggal aktif dalam bulan ini (sampai hari ini)
    const dateSet = new Set();
    allNasabah.forEach(n => {
      if (n.pembayaran) {
        Object.keys(n.pembayaran).forEach(d => {
          const date = parseDateStr(d);
          if (date && date >= monthStart && date <= effectiveEndEK) dateSet.add(d);
        });
      }
      const tglCair = (n.tanggalPencairan || '').trim();
      if (tglCair) {
        const date = parseDateStr(tglCair);
        if (date && date >= monthStart && date <= effectiveEndEK) dateSet.add(tglCair);
      }
    });
    kasirEntries.forEach(e => {
      const tgl = e.tanggal;
      if (!tgl) return;
      const date = parseDateStr(tgl);
      if (date && date >= monthStart && date <= effectiveEndEK) dateSet.add(tgl);
    });

    // Skip Minggu & tanggal merah agar konsisten dengan Buku Pokok / Buku Rekap
    // / Kas Penuntun. Saldo berjalan Tunai Kas hanya mengalir antar hari kerja
    // (baris non-hari-kerja memang tidak dibuat), sesuai aturan menu ini.
    const sortedDates = Array.from(dateSet)
      .filter(d => { const dt = parseDateStr(d); return dt && isHariKerja(dt); })
      .sort((a, b) => parseDateStr(a) - parseDateStr(b));

    // Hitung Tunai Pasar per tanggal — via helper top-level computeTunaiKasPerDate
    // (sumber kebenaran: Buku Rekap "Total Hari Ini" baris tunaiPasar).
    // Versi lama memakai agregat global (storting+5%+5% - drop) yang bisa
    // berbeda tanda/jumlah dari per-resort sum bila ada resort surplus &
    // resort lain defisit. Sekarang persis sama dengan Buku Rekap.
    const admins = activeCabang?.admins || [];
    const nasabahByAdmin = {};
    admins.forEach(adm => {
      nasabahByAdmin[adm.uid] = allNasabah.filter(n => n.adminUid === adm.uid);
    });
    // Pencairan tabungan per (tanggal, admin) — kredit tambahan untuk tunaiPasar
    // (parity dengan BukuRekap & KasPenuntun).
    const pencairanByAdminDate = buildPencairanByAdminDate(jurnalEntries);
    // Orphan storting per tanggal — wajib di-pass agar helper match BukuRekap
    // (pimpinan 11 Jun 2026 "Buku Tunai vs Buku Rekap").
    const orphanByDate = bukuData?.orphanPaymentsByDate || {};
    // Kasbon per (tanggal, admin) — untuk dekomposisi per-admin kembaliKasbon.
    // Filter sama persis dengan BukuTunai: uang_kas keluar yang punya targetAdminUid.
    const kasbonByAdminPerDate = {};
    kasirEntries.forEach(e => {
      if (e.jenis !== 'uang_kas' || e.arah !== 'keluar' || !e.targetAdminUid) return;
      const tgl = e.tanggal;
      if (!tgl) return;
      const date = parseDateStr(tgl);
      if (!date || date < monthStart || date > monthEnd) return;
      if (!kasbonByAdminPerDate[tgl]) kasbonByAdminPerDate[tgl] = {};
      kasbonByAdminPerDate[tgl][e.targetAdminUid] =
        (kasbonByAdminPerDate[tgl][e.targetAdminUid] || 0) + (e.jumlah || 0);
    });

    // Per tanggal: hitung tunaiPasar + kembaliKasbon PER ADMIN lalu dijumlah.
    // kembaliKasbon kini STRICT (kasbonPagi − kasPakai, parity Buku Tunai L2947).
    // Rumus strict bersifat DISTRIBUTIF (Σ(a−b) = Σa − Σb) → total harian di sini
    // == Σ kasbonPagi − Σ kasPakai = total kolom Kembali Kasbon Buku Tunai untuk
    // tanggal itu (aturan pimpinan 16 Jun 2026). Catatan non-distributif waterfall
    // lama tidak berlaku lagi; loop per-admin dipertahankan untuk keterbacaan &
    // parity tunaiPasar per resort.
    const tunaiPasarPerDate = {};
    const kembaliKasbonPerDate = {};
    sortedDates.forEach(dateStr => {
      let dayTunaiPasar = 0, dayKembali = 0;
      for (const adm of admins) {
        const kasbonPagiAdm = kasbonByAdminPerDate[dateStr]?.[adm.uid] || 0;
        // Parity Buku Rekap (Rule 3): snapshot otoritas utk tanggal historis;
        // hari berjalan tetap live (isTanggalHistoris false).
        const { tunaiPasar, kasPakai } = computeTunaiKasPerDate(dateStr, nasabahByAdmin, [adm], pencairanByAdminDate, orphanByDate, bukuData?.rekapBeku, bukuData?.today);
        // ✅ STRICT (parity Buku Tunai L2947): kembaliKasbon = kasbonPagi − kasPakai,
        // tanpa clamping (pimpinan 16 Jun 2026). Menggantikan waterfall helper yang
        // mengembalikan kasbonPagi PENUH (tanpa kurangi kasPakai) → bikin total Buku
        // Ekspedisi membengkak vs Buku Tunai. Strict = distributif → Σ per-admin =
        // Σ kasbonPagi − Σ kasPakai = total kolom Buku Tunai untuk tanggal ini.
        const kembaliKasbon = kasbonPagiAdm - kasPakai;
        dayTunaiPasar += tunaiPasar;
        dayKembali += kembaliKasbon;
      }
      tunaiPasarPerDate[dateStr] = dayTunaiPasar;
      kembaliKasbonPerDate[dateStr] = dayKembali;
    });

    // Hitung nilai dari jurnal kasir per tanggal
    const kasbonPerDate = {};       // uang_kas keluar
    const suntikanDanaPerDate = {}; // suntikan_dana masuk
    const pinjamanKasPerDate = {};  // pinjaman_kas masuk
    const transportPerDate = {};    // transport keluar
    const buPerDate = {};           // penggajian keluar
    const buFakturPerDate = {};     // faktur data for BU entries
    const pengembalianPerDate = {}; // pengembalian_kas keluar
    const spPerDate = {};           // sp keluar

    kasirEntries.forEach(e => {
      const tgl = e.tanggal;
      if (!tgl) return;
      const date = parseDateStr(tgl);
      if (!date || date < monthStart || date > monthEnd) return;
      const jumlah = e.jumlah || 0;
      if (e.jenis === 'uang_kas' && e.arah === 'keluar') {
        kasbonPerDate[tgl] = (kasbonPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'suntikan_dana' && e.arah === 'masuk') {
        suntikanDanaPerDate[tgl] = (suntikanDanaPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'pinjaman_kas' && e.arah === 'masuk') {
        pinjamanKasPerDate[tgl] = (pinjamanKasPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'transport' && e.arah === 'keluar') {
        transportPerDate[tgl] = (transportPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'penggajian' && e.arah === 'keluar') {
        // Hanya hitung BU yang targetnya ekspedisi, atau entry lama tanpa targetBuku
        const buku = e.targetBuku;
        if (!buku || (Array.isArray(buku) && buku.includes('ekspedisi'))) {
          buPerDate[tgl] = (buPerDate[tgl] || 0) + jumlah;
          if (!buFakturPerDate[tgl]) buFakturPerDate[tgl] = [];
          buFakturPerDate[tgl].push({ jumlah, fakturUrl: e.fakturUrl || null, keterangan: e.keterangan || '' });
        }
      } else if (e.jenis === 'pengembalian_kas' && e.arah === 'keluar') {
        pengembalianPerDate[tgl] = (pengembalianPerDate[tgl] || 0) + jumlah;
      } else if (e.jenis === 'sp' && e.arah === 'keluar') {
        spPerDate[tgl] = (spPerDate[tgl] || 0) + jumlah;
      }
    });

    // Tunai Kas = saldo kas berjalan (running balance) hari-per-hari. Di-seed
    // dari Saldo Kas Bulan Lalu pada hari kerja PERTAMA, lalu mengalir ke hari
    // kerja berikutnya (akumulatif). sortedDates sudah difilter hari kerja
    // (skip Minggu & tanggal merah) dan terurut menaik, sehingga cascade benar.
    let runningTunaiKas = saldoKasBulanLalu;
    return sortedDates.map(dateStr => {
      const tunaiPasar = tunaiPasarPerDate[dateStr] || 0;
      const kasbonPagi = kasbonPerDate[dateStr] || 0;
      const kembaliKasbon = kembaliKasbonPerDate[dateStr] || 0;
      const suntikanDana = suntikanDanaPerDate[dateStr] || 0;
      const pinjamanKas = pinjamanKasPerDate[dateStr] || 0;
      const dropPusat = suntikanDana + pinjamanKas;
      const transport = transportPerDate[dateStr] || 0;
      const bu = buPerDate[dateStr] || 0;
      const buFaktur = buFakturPerDate[dateStr] || [];
      const pengembalianKas = pengembalianPerDate[dateStr] || 0;
      const sp = spPerDate[dateStr] || 0;
      // Daily In  = Kembali Kasbon + Tunai Pasar + Suntikan Dana + Pinjaman Kas
      // Daily Out = Kasbon Pagi + Transport + BU + SP + Pengembalian Kas
      const dailyIn = kembaliKasbon + tunaiPasar + dropPusat;
      const dailyOut = kasbonPagi + transport + bu + sp + pengembalianKas;
      runningTunaiKas = runningTunaiKas + dailyIn - dailyOut;
      const tunaiKas = runningTunaiKas;
      return { tanggal: dateStr, kembaliKasbon, tunaiPasar, suntikanDana, pinjamanKas, dropPusat, kasbonPagi, transport, bu, buFaktur, pengembalianKas, sp, tunaiKas };
    });
  })();

  const thS = {
    padding: '7px 6px', textAlign: 'center', fontWeight: 700, fontSize: 10,
    whiteSpace: 'nowrap', position: 'sticky', top: 0, background: '#f8f9fa',
    zIndex: 2, borderBottom: '2px solid var(--border)', borderRight: '1px solid var(--border)',
  };
  const tdR = { padding: '6px 7px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontSize: 11, borderRight: '1px solid var(--border-light)', borderBottom: '1px solid var(--border-light)' };
  const tdRBold = { ...tdR, fontWeight: 700 };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Buku Ekspedisi</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'} — {formatBulanLabel(bulan)}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="bukuEkspedisi" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: 20 }} className="fade-in">
        {/* Filter */}
        <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center' }}>
          {!isUnit && cabangList.length > 1 && (
            <select value={activeCabang?.id || ''} onChange={(e) => selectCabangById(cabangList, e.target.value, setActiveCabang)}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          <select value={bulan} onChange={(e) => setBulan(e.target.value)}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
          {/* Saldo Kas Bulan Lalu — dorong ke kanan dengan marginLeft:auto agar
              tidak mengganggu alignment dropdown filter di kiri */}
          <div style={{
            marginLeft: 'auto',
            display: 'flex', flexDirection: 'column', alignItems: 'flex-end',
            padding: '6px 14px', borderRadius: 10,
            border: '1px solid var(--border)', background: 'var(--card)',
            minWidth: 180,
          }}>
            <span style={{
              fontSize: 11, fontWeight: 600, color: 'var(--text-muted)',
              textTransform: 'uppercase', letterSpacing: 0.4,
            }}>
              Saldo Kas Bulan Lalu
            </span>
            <span style={{
              fontSize: 16, fontWeight: 700,
              fontFamily: "'DM Mono', monospace",
              color: 'var(--text)',
            }}>
              {formatRp(saldoKasBulanLalu)}
            </span>
          </div>
        </div>

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>Memuat Buku Ekspedisi...</p>
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : ekspedisiRows.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Tidak ada data untuk bulan {formatBulanLabel(bulan)}</p>
          </div>
        ) : (
          <div style={{ overflow: 'auto', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--card)' }}>
            <table style={{ width: '100%', minWidth: 1100, fontSize: 12, borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ background: '#f8f9fa' }}>
                  <th style={{ ...thS, textAlign: 'left', paddingLeft: 10 }}>Tanggal</th>
                  <th style={{ ...thS, background: '#e8f8f0' }}>Kembali Kasbon</th>
                  <th style={{ ...thS, background: '#e8f8f0' }}>Tunai Pasar</th>
                  <th style={{ ...thS, background: '#e0f0ff' }}>Suntikan Dana</th>
                  <th style={{ ...thS, background: '#e0f0ff' }}>Pinjaman Kas</th>
                  <th style={{ ...thS, background: '#fef9c3' }}>Kasbon Pagi</th>
                  <th style={{ ...thS, background: '#fef9c3' }}>Transport</th>
                  <th style={{ ...thS, background: '#ffe4e6' }}>BU</th>
                  <th style={{ ...thS, background: '#ffe4e6' }}>SP</th>
                  <th style={{ ...thS, background: '#ffe4e6' }}>Pengembalian Kas</th>
                  <th style={{ ...thS, background: '#f3e8ff' }}>Tunai Kas</th>
                </tr>
              </thead>
              <tbody>
                {ekspedisiRows.map((row) => (
                  <tr key={row.tanggal}>
                    <td style={{ padding: '6px 10px', fontWeight: 700, fontSize: 12, borderRight: '1px solid var(--border)', borderBottom: '1px solid var(--border-light)', whiteSpace: 'nowrap', background: '#fafafa' }}>
                      {row.tanggal.slice(0, 6)}
                    </td>
                    <td style={{ ...tdR, background: '#f0fdf4', color: row.kembaliKasbon >= 0 ? '#166534' : 'var(--danger)' }}>
                      {row.kembaliKasbon !== 0 ? formatRp(row.kembaliKasbon) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#f0fdf4', color: row.tunaiPasar >= 0 ? '#166534' : 'var(--danger)', fontWeight: 600 }}>
                      {row.tunaiPasar !== 0 ? formatRp(row.tunaiPasar) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#eff6ff' }}>
                      {row.suntikanDana > 0 ? formatRp(row.suntikanDana) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#eff6ff' }}>
                      {row.pinjamanKas > 0 ? formatRp(row.pinjamanKas) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fefce8' }}>
                      {row.kasbonPagi > 0 ? formatRp(row.kasbonPagi) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fefce8' }}>
                      {row.transport > 0 ? formatRp(row.transport) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fff1f2', cursor: row.bu > 0 ? 'pointer' : 'default', textDecoration: row.bu > 0 ? 'underline' : 'none' }}
                      onClick={() => { if (row.bu > 0 && row.buFaktur?.length > 0) setShowFaktur(row.buFaktur); }}>
                      {row.bu > 0 ? formatRp(row.bu) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fff1f2' }}>
                      {row.sp > 0 ? formatRp(row.sp) : '-'}
                    </td>
                    <td style={{ ...tdR, background: '#fff1f2' }}>
                      {row.pengembalianKas > 0 ? formatRp(row.pengembalianKas) : '-'}
                    </td>
                    <td style={{ ...tdRBold, background: '#faf5ff', color: row.tunaiKas >= 0 ? '#7e22ce' : 'var(--danger)' }}>
                      {formatRp(row.tunaiKas)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </main>

      {/* Faktur Modal */}
      {showFaktur && <FakturModal fakturList={showFaktur} onClose={() => setShowFaktur(null)} />}
    </div>
  );
}


// ============================================================
// RINGKASAN SCREEN
// ============================================================
function RingkasanScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(() => resolveInitialCabang(cabang, cabangList));
  const [bulan, setBulan] = useState(getCurrentMonthKey());
  const [summary, setSummary] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const bulanOptions = generateBulanOptions();

  useEffect(() => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    getKasirEntries({ cabangId: activeCabang.id, bulan }).then(result => {
      if (result.success) {
        setSummary(result.data.summary || {});
      }
    }).catch(err => {
      setError('Gagal memuat: ' + err.message);
    }).finally(() => setLoading(false));
  }, [activeCabang?.id, bulan]);

  const perJenis = summary?.perJenis || {};

  const rows = JENIS_OPTIONS.map(j => {
    const d = perJenis[j.value] || {};
    return { label: j.label, masuk: d.masuk || 0, keluar: d.keluar || 0 };
  });

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-back">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Ringkasan Kas</h1>
            <p>{activeCabang?.name || 'Pilih Cabang'}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="ringkasan" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/></svg>
          </button>
        </div>
      </header>

      <main style={{ padding: '20px 24px', maxWidth: 700, margin: '0 auto' }} className="fade-in">
        {/* Filters */}
        <div style={{ display: 'flex', gap: 12, marginBottom: 24, flexWrap: 'wrap' }}>
          {!isUnit && cabangList.length > 1 && (
            <select value={activeCabang?.id || ''} onChange={(e) => selectCabangById(cabangList, e.target.value, setActiveCabang)}
              style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
              {cabangList.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          <select value={bulan} onChange={(e) => setBulan(e.target.value)}
            style={{ padding: '8px 12px', borderRadius: 10, border: '1px solid var(--border)', fontSize: 13, background: 'var(--card)' }}>
            {bulanOptions.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
          </select>
        </div>

        {loading ? (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <div className="loading-spinner" />
          </div>
        ) : error ? (
          <div style={{ textAlign: 'center', padding: 60, color: 'var(--danger)', fontSize: 14 }}>{error}</div>
        ) : (
          <>
            {/* Table */}
            <div style={{ background: 'var(--card)', borderRadius: 16, border: '1px solid var(--border)', overflow: 'hidden' }}>
              <table style={{ width: '100%', fontSize: 14 }}>
                <thead>
                  <tr style={{ background: '#f8f9fa' }}>
                    <th style={{ padding: '14px 16px', textAlign: 'left', fontWeight: 700 }}>Jenis</th>
                    <th style={{ padding: '14px 16px', textAlign: 'right', fontWeight: 700, color: 'var(--success)' }}>Masuk</th>
                    <th style={{ padding: '14px 16px', textAlign: 'right', fontWeight: 700, color: 'var(--danger)' }}>Keluar</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r, i) => (
                    <tr key={i} style={{ borderTop: '1px solid var(--border-light)' }}>
                      <td style={{ padding: '12px 16px', fontWeight: 500 }}>{r.label}</td>
                      <td style={{ padding: '12px 16px', textAlign: 'right', fontFamily: "'DM Mono', monospace", color: r.masuk > 0 ? 'var(--success)' : 'var(--text-light)' }}>
                        {r.masuk > 0 ? formatRpFull(r.masuk) : '-'}
                      </td>
                      <td style={{ padding: '12px 16px', textAlign: 'right', fontFamily: "'DM Mono', monospace", color: r.keluar > 0 ? 'var(--danger)' : 'var(--text-light)' }}>
                        {r.keluar > 0 ? formatRpFull(r.keluar) : '-'}
                      </td>
                    </tr>
                  ))}
                  {/* Total row */}
                  <tr style={{ borderTop: '2px solid var(--border)', background: '#f8f9fa' }}>
                    <td style={{ padding: '14px 16px', fontWeight: 800 }}>TOTAL</td>
                    <td style={{ padding: '14px 16px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontWeight: 800, color: 'var(--success)' }}>
                      {formatRpFull(summary?.totalMasuk || 0)}
                    </td>
                    <td style={{ padding: '14px 16px', textAlign: 'right', fontFamily: "'DM Mono', monospace", fontWeight: 800, color: 'var(--danger)' }}>
                      {formatRpFull(summary?.totalKeluar || 0)}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

          </>
        )}
      </main>
    </div>
  );
}

// ============================================================
// ABSENSI SCREEN (Kasir - Lihat & kelola absensi harian)
// ============================================================
function AbsensiScreen({ user, cabang, cabangList, onBack, onLogout, onNavigate }) {
  const isUnit = user?.role === 'kasir_unit';
  const [activeCabang, setActiveCabang] = useState(() => resolveInitialCabang(cabang, cabangList));
  const [absensiList, setAbsensiList] = useState([]);
  const [operasionalMap, setOperasionalMap] = useState({});
  const [loading, setLoading] = useState(true);
  const [sudahAbsen, setSudahAbsen] = useState(false);
  const [absensiSendiri, setAbsensiSendiri] = useState(null);
  const [submittingAbsensi, setSubmittingAbsensi] = useState(false);
  const [savingMap, setSavingMap] = useState({});
  const [inputMap, setInputMap] = useState({});
  const [error, setError] = useState('');
  const [successMsg, setSuccessMsg] = useState('');

  const todayKey = (() => {
    const now = new Date();
    const jakartaOffset = 7 * 60;
    const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
    const jakarta = new Date(utc + (jakartaOffset * 60000));
    const yyyy = jakarta.getFullYear();
    const mm = String(jakarta.getMonth() + 1).padStart(2, '0');
    const dd = String(jakarta.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  })();

  const todayDisplay = getTodayIndo();

  const ROLE_LABELS = {
    admin: 'PDL', koordinator: 'Koordinator', pimpinan: 'Pimpinan',
    kasir_unit: 'Kasir', kasir_wilayah: 'Kasir Wilayah'
  };

  // Load absensi data
  const loadAbsensi = async () => {
    if (!activeCabang) return;
    setLoading(true);
    setError('');
    try {
      // ── BLOK 2 (W-6) + BLOK 3 (W-8): baca dari Supabase, bukan RTDB ──────
      // `user_absensi_today` tidak dibaca lagi: ia tabel kedua yang ditulis
      // terpisah di RTDB dan bisa melenceng dari sumbernya. Di Supabase
      // status "sudah absen" diturunkan dari baris absensi itu sendiri (023).
      const { supabase } = await import('../../lib/supabaseClient');

      const { data: absen, error: eAbsen } = await supabase
        .from('absensi')
        .select('user_id, legacy_uid, nama, role, jam, recorded_at')
        .eq('cabang_id', activeCabang.id)
        .eq('tanggal', todayKey)
        .order('recorded_at', { ascending: true });
      if (eAbsen) throw new Error(eAbsen.message);

      // Bentuk lama dipertahankan supaya JSX di bawah tidak perlu diubah:
      // `uid` kini uuid Supabase — itu juga yang diterima
      // rpc_catat_operasional_harian, jadi kunci peta tetap satu macam.
      const list = (absen || []).map((a) => ({
        uid: a.user_id || a.legacy_uid,
        nama: a.nama,
        role: a.role,
        jam: a.jam,
        timestamp: a.recorded_at ? new Date(a.recorded_at).getTime() : 0,
      }));
      setAbsensiList(list);

      const { data: sesi } = await supabase.auth.getUser();
      const uid = sesi?.user?.id;
      if (uid) {
        const sendiri = list.find((a) => a.uid === uid) || null;
        setSudahAbsen(!!sendiri);
        setAbsensiSendiri(sendiri);
      }

      // Operasional harian — RLS 016a membolehkan SELECT untuk peran kasir.
      const { data: ops, error: eOps } = await supabase
        .from('operasional_harian')
        .select('user_id, legacy_uid, nama, uang_makan, transport')
        .eq('cabang_id', activeCabang.id)
        .eq('tanggal', todayKey);
      if (eOps) throw new Error(eOps.message);

      // Peta tetap berbentuk { [uid]: {uangMakan, transport, nama} } supaya
      // pemakaian di JSX tidak berubah.
      const opsData = {};
      for (const o of ops || []) {
        opsData[o.user_id || o.legacy_uid] = {
          uid: o.user_id || o.legacy_uid,
          nama: o.nama,
          uangMakan: Number(o.uang_makan || 0),
          transport: Number(o.transport || 0),
        };
      }
      setOperasionalMap(opsData);

      // Initialize input map (format ribuan untuk tampilan)
      const initInput = {};
      list.forEach(a => {
        const um = opsData[a.uid]?.uangMakan;
        const tr = opsData[a.uid]?.transport;
        initInput[a.uid] = {
          uangMakan: um ? parseInt(um).toLocaleString('id-ID') : '',
          transport: tr ? parseInt(tr).toLocaleString('id-ID') : '',
        };
      });
      setInputMap(initInput);

    } catch (err) {
      setError('Gagal memuat data absensi: ' + err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadAbsensi(); }, [activeCabang?.id]);

  // Absen sendiri — SEMUA PERAN, bukan hanya kasir_unit.
  // ── BLOK 3 (W-7) ────────────────────────────────────────────────────────
  // Gerbang `!isUnit` DIHAPUS. Sebelumnya hanya kasir_unit yang bisa absen di
  // web; admin/pimpinan/koordinator absen lewat APK. APK itu mati 1 September,
  // jadi mereka harus bisa absen di sini (021 §4, D-4).
  const handleAbsenSendiri = async () => {
    if (!activeCabang) return;
    setSubmittingAbsensi(true);
    setError('');
    try {
      const { supabase } = await import('../../lib/supabaseClient');

      // Jam dan tanggal diambil SERVER, bukan perangkat. Di RTDB `jam`
      // dihitung dari new Date() di browser, jadi memundurkan jam laptop
      // cukup untuk absen "tepat waktu". rpc_catat_absensi menutup itu.
      //
      // `p_cabang_id` hanya berarti bagi peran tanpa cabang tetap
      // (koordinator/pengawas/sekretaris — 001a:127); bagi yang punya cabang
      // ia hanya menegaskan, dan RPC menolak kalau berbeda. Di sini
      // activeCabang SUDAH menjadi pemilih cabangnya.
      const { data, error } = await supabase.rpc('rpc_catat_absensi', {
        p_cabang_id: activeCabang.id,
      });
      if (error) throw new Error(error.message);

      const jam = data?.jam || '';
      const record = {
        uid: data?.user_id,
        nama: data?.nama,
        role: data?.role,
        cabangId: data?.cabang_id,
        cabangNama: data?.cabang_nama,
        jam,
        tanggal: data?.tanggal,
        timestamp: data?.recorded_at ? new Date(data.recorded_at).getTime() : Date.now(),
      };

      setSudahAbsen(true);
      setAbsensiSendiri(record);
      setSuccessMsg(`Absensi berhasil dicatat pukul ${jam}`);
      setTimeout(() => setSuccessMsg(''), 3000);
      await loadAbsensi();
    } catch (err) {
      setError('Gagal absen: ' + err.message);
    } finally {
      setSubmittingAbsensi(false);
    }
  };

  // Simpan operasional karyawan
  const handleSaveOperasional = async (uid, nama) => {
    if (!activeCabang || !isUnit) return;
    const input = inputMap[uid] || {};
    const uangMakan = parseInt(String(input.uangMakan).replace(/\./g, '')) || 0;
    const transport = parseInt(String(input.transport).replace(/\./g, '')) || 0;
    if (uangMakan < 0 || transport < 0) { setError('Nominal tidak boleh negatif'); return; }

    setSavingMap(m => ({ ...m, [uid]: true }));
    setError('');
    try {
      // ── BLOK 2 (W-5): tulis ke Supabase, PINDAH SEKALIGUS ────────────────
      // Keputusan pemilik: tanpa tulis-ganda. RTDB mati 1 September, jadi
      // menulis ke dua tempat hanya menunda pekerjaan dan menciptakan dua
      // sumber kebenaran selama beberapa hari.
      //
      // URUTAN INI MENGIKAT: tulis dulu, baru sync. Dibalik, RPC sync membaca
      // tabel yang belum menerima entri hari ini → total 0 → cabang
      // "total 0 dengan entri lama" MENGHAPUS LUNAK entri kasir hari itu
      // (015 B-4, cermin kasirApi.js:662). Lihat 021 §3.1.
      const { supabase } = await import('../../lib/supabaseClient');

      const { error: eTulis } = await supabase.rpc('rpc_catat_operasional_harian', {
        p_user_id: uid,
        p_uang_makan: uangMakan,
        p_transport: transport,
      });
      if (eTulis) throw new Error(eTulis.message);

      setOperasionalMap(m => ({
        ...m,
        [uid]: { uid, nama, uangMakan, transport },
      }));

      // Sync total operasional ke jurnal kasir sebagai entry Transport.
      // Tetap non-blocking seperti perilaku lama: operasionalnya sudah
      // tersimpan, dan sync bisa diulang kapan saja karena idempoten.
      try {
        await supabase.rpc('rpc_sync_operasional_transport', { p_tanggal: null });
      } catch (syncErr) {
        console.error('Sync operasional ke jurnal gagal:', syncErr);
      }

      setSuccessMsg(`Operasional ${nama} berhasil disimpan`);
      setTimeout(() => setSuccessMsg(''), 3000);
    } catch (err) {
      setError('Gagal menyimpan: ' + err.message);
    } finally {
      setSavingMap(m => ({ ...m, [uid]: false }));
    }
  };

  return (
    <div className="page-container">
      <header className="top-bar">
        <div className="top-bar-left">
          <button onClick={onBack} className="btn-icon" title="Kembali">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m12 19-7-7 7-7"/><path d="M19 12H5"/></svg>
          </button>
          <div>
            <h1>Absensi Karyawan</h1>
            <p>{todayDisplay}</p>
          </div>
        </div>
        {onNavigate && <KasirTopBarNav currentScreen="absensi" onNavigate={onNavigate} />}
        <div className="top-bar-right">
          <button onClick={onLogout} className="btn-icon" title="Keluar">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>
            </svg>
          </button>
        </div>
      </header>

      <main className="home-content fade-in" style={{ maxWidth: 800, margin: '0 auto' }}>
        {/* Cabang selector for kasir wilayah */}
        {!isUnit && cabangList.length > 1 && (
          <div style={{ marginBottom: 20 }}>
            <div className="home-section-label">Pilih Cabang</div>
            <div className="cabang-grid">
              {cabangList.map(c => (
                <button key={c.id} onClick={() => { setActiveCabang(c); writeActiveCabangId(c.id); }}
                  className="cabang-card"
                  style={activeCabang?.id === c.id ? { borderColor: 'var(--primary)', background: 'var(--primary-light)' } : {}}>
                  <div className="cabang-card-info"><h3>{c.name}</h3></div>
                  {activeCabang?.id === c.id && <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" strokeWidth="2.5" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>}
                </button>
              ))}
            </div>
          </div>
        )}

        {error && (
          <div style={{ background: 'var(--danger-light,#fef2f2)', border: '1px solid var(--danger,#ef4444)', borderRadius: 10, padding: '10px 16px', marginBottom: 16, color: 'var(--danger,#ef4444)', fontSize: 14 }}>
            {error}
          </div>
        )}

        {successMsg && (
          <div style={{ background: '#f0fdf4', border: '1px solid #22c55e', borderRadius: 10, padding: '10px 16px', marginBottom: 16, color: '#16a34a', fontSize: 14 }}>
            ✓ {successMsg}
          </div>
        )}

        {/* Absen sendiri — SEMUA PERAN (BLOK 3).
            Gerbang `isUnit` dihapus: admin/pimpinan/koordinator yang selama
            ini absen lewat APK harus bisa absen di sini, karena APK-nya mati
            1 September. Pemilih cabang di atas (`!isUnit && cabangList.length
            > 1`) sudah menjadi pemilih cabang untuk koordinator — ia yang
            mengisi `activeCabang`, dan `activeCabang` itulah yang dikirim ke
            rpc_catat_absensi sebagai p_cabang_id. */}
        {activeCabang && (
          <div style={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 14, padding: 20, marginBottom: 20 }}>
            <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-muted)', marginBottom: 12, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              Absensi Saya
            </div>
            {sudahAbsen ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div style={{ width: 40, height: 40, borderRadius: '50%', background: '#f0fdf4', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#16a34a" strokeWidth="2.5" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>
                </div>
                <div>
                  <p style={{ fontWeight: 600, color: '#16a34a', marginBottom: 2 }}>Sudah Absen</p>
                  <p style={{ fontSize: 13, color: 'var(--text-muted)' }}>
                    Pukul {absensiSendiri?.jam} · {activeCabang?.name}
                  </p>
                </div>
              </div>
            ) : (
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div>
                  <p style={{ fontWeight: 600, color: 'var(--text-primary)', marginBottom: 4 }}>{user?.name}</p>
                  <p style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 12 }}>Belum absen hari ini · {activeCabang?.name}</p>
                </div>
                <button
                  onClick={handleAbsenSendiri}
                  disabled={submittingAbsensi}
                  style={{ marginLeft: 'auto', padding: '8px 20px', background: 'var(--primary,#6366f1)', color: '#fff', border: 'none', borderRadius: 8, fontWeight: 600, cursor: 'pointer', opacity: submittingAbsensi ? 0.6 : 1 }}>
                  {submittingAbsensi ? 'Menyimpan...' : 'Absen Sekarang'}
                </button>
              </div>
            )}
          </div>
        )}

        {/* Daftar absensi */}
        {activeCabang && (
          <div>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
              <div className="home-section-label" style={{ marginBottom: 0 }}>
                Daftar Absensi Hari Ini {activeCabang && `— ${activeCabang.name}`}
              </div>
              <button onClick={loadAbsensi} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--primary)', fontSize: 13, fontWeight: 600 }}>
                Refresh
              </button>
            </div>

            {loading ? (
              <div style={{ textAlign: 'center', padding: 40 }}>
                <div className="loading-spinner" style={{ margin: '0 auto 12px' }} />
                <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Memuat data...</p>
              </div>
            ) : absensiList.length === 0 ? (
              <div style={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 14, padding: 32, textAlign: 'center' }}>
                <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>Belum ada karyawan yang absen hari ini</p>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {absensiList.map((a, i) => {
                  const ops = operasionalMap[a.uid] || {};
                  const inp = inputMap[a.uid] || {};
                  const sudahDiisi = ops.uangMakan !== undefined || ops.transport !== undefined;
                  return (
                    <div key={a.uid} style={{ background: 'var(--card)', border: '1px solid var(--border)', borderRadius: 14, padding: '14px 16px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: sudahDiisi || isUnit ? 12 : 0 }}>
                        <div style={{ width: 36, height: 36, borderRadius: '50%', background: 'var(--primary-light,#ede9fe)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 15, fontWeight: 700, color: 'var(--primary,#6366f1)', flexShrink: 0 }}>
                          {(a.nama || '?')[0].toUpperCase()}
                        </div>
                        <div style={{ flex: 1 }}>
                          <p style={{ fontWeight: 600, fontSize: 14, marginBottom: 2 }}>{a.nama}</p>
                          <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                            {ROLE_LABELS[a.role] || a.role} · {a.cabangNama} · {a.jam}
                          </p>
                        </div>
                        {sudahDiisi && (
                          <div style={{ textAlign: 'right', fontSize: 12 }}>
                            <p style={{ color: 'var(--text-muted)' }}>Makan: <b style={{ color: 'var(--text-primary)' }}>{(ops.uangMakan || 0).toLocaleString('id-ID')}</b></p>
                            <p style={{ color: 'var(--text-muted)' }}>Transport: <b style={{ color: 'var(--text-primary)' }}>{(ops.transport || 0).toLocaleString('id-ID')}</b></p>
                          </div>
                        )}
                      </div>

                      {/* Input operasional (kasir_unit only) */}
                      {isUnit && (
                        <div style={{ borderTop: '1px solid var(--border)', paddingTop: 12, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                          <div style={{ flex: 1, minWidth: 120 }}>
                            <label style={{ fontSize: 11, color: 'var(--text-muted)', display: 'block', marginBottom: 4 }}>Uang Makan (Rp)</label>
                            <input
                              type="text" inputMode="numeric"
                              value={inp.uangMakan ?? ''}
                              onChange={e => {
                                const raw = e.target.value.replace(/\D/g, '');
                                const formatted = raw ? parseInt(raw).toLocaleString('id-ID') : '';
                                setInputMap(m => ({ ...m, [a.uid]: { ...m[a.uid], uangMakan: formatted } }));
                              }}
                              placeholder="0"
                              style={{ width: '100%', padding: '6px 10px', border: '1px solid var(--border)', borderRadius: 8, fontSize: 14, background: 'var(--background)', color: 'var(--text-primary)', boxSizing: 'border-box' }}
                            />
                          </div>
                          <div style={{ flex: 1, minWidth: 120 }}>
                            <label style={{ fontSize: 11, color: 'var(--text-muted)', display: 'block', marginBottom: 4 }}>Transport (Rp)</label>
                            <input
                              type="text" inputMode="numeric"
                              value={inp.transport ?? ''}
                              onChange={e => {
                                const raw = e.target.value.replace(/\D/g, '');
                                const formatted = raw ? parseInt(raw).toLocaleString('id-ID') : '';
                                setInputMap(m => ({ ...m, [a.uid]: { ...m[a.uid], transport: formatted } }));
                              }}
                              placeholder="0"
                              style={{ width: '100%', padding: '6px 10px', border: '1px solid var(--border)', borderRadius: 8, fontSize: 14, background: 'var(--background)', color: 'var(--text-primary)', boxSizing: 'border-box' }}
                            />
                          </div>
                          <button
                            onClick={() => handleSaveOperasional(a.uid, a.nama)}
                            disabled={savingMap[a.uid]}
                            style={{ padding: '8px 16px', background: 'var(--primary,#6366f1)', color: '#fff', border: 'none', borderRadius: 8, fontWeight: 600, cursor: 'pointer', fontSize: 13, whiteSpace: 'nowrap', marginTop: 16, opacity: savingMap[a.uid] ? 0.6 : 1 }}>
                            {savingMap[a.uid] ? 'Menyimpan...' : 'Simpan'}
                          </button>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </main>
    </div>
  );
}
