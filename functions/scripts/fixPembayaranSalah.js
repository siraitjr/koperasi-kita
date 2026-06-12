// functions/scripts/fixPembayaranSalah.js
// =========================================================================
// SCRIPT RESCUE: KOREKSI NOMINAL PEMBAYARAN SALAH (SURGICAL PATCH)
// =========================================================================
//
// TUJUAN
// ------
// Field admin kadang salah input nominal pembayaran (mis. ketik 200.000
// padahal seharusnya 20.000). Mengedit manual di Firebase Console berbahaya
// karena nominal pembayaran tersebar di BANYAK node turunan yang TIDAK
// otomatis ter-sinkron saat di-edit manual (lihat catatan di bawah).
//
// Script ini menambal SEMUA node turunan sekaligus, idempotent & aman:
//   1. pelanggan/{adminUid}/{pelangganId}/pembayaranList[idx].jumlah
//   2. pembayaran_harian/{cabangId}/{tglRun}/{autoId}.jumlah
//   3. jurnal_transaksi/{cabangId}/{YYYY-MM}/{autoId}.jumlah
//   4. summary/* → DIPERBAIKI via full-recompute resmi (repairAllSummaries),
//      BUKAN dummy update (lihat penjelasan REQUIREMENT #4 di bawah).
//
// CARA PAKAI
// ----------
//   1. Siapkan service account key (lihat blok INIT di bawah).
//   2. Isi array `corrections` di bawah dengan data koreksi Anda.
//   3. Jalankan DULU dengan DRY_RUN = true (default) untuk melihat persis
//      path & nilai yang AKAN diubah, tanpa menulis apa pun:
//          node functions/scripts/fixPembayaranSalah.js
//   4. Periksa output. Kalau sudah benar, set DRY_RUN = false, jalankan lagi.
//   5. Setelah selesai, perbaiki agregat summary (lihat output akhir script).
//
// =========================================================================

const admin = require('firebase-admin');
const path = require('path');

// =========================================================================
// ⚙️  KONFIGURASI
// =========================================================================

// DRY RUN: true = HANYA console.log path + nilai lama/baru, TANPA menulis.
//          false = benar-benar menulis ke database.
// >>> SELALU jalankan true dulu, verifikasi, baru ubah ke false. <<<
const DRY_RUN = true;

// REEVALUATE_STATUS: kalau true, setelah koreksi script akan mengecek apakah
// status 'Lunas' nasabah jadi tidak konsisten (mis. nominal diturunkan
// sehingga total bayar < total pelunasan) dan MEMPERBAIKI field
// `status` + `tanggalLunasCicilan` pada node pelanggan.
//   ⚠️ Catatan: ini TIDAK menyentuh event_harian/nasabah_lunas maupun
//   pelanggan_bermasalah. Kalau ada perubahan status, script akan
//   memberi tahu node mana yang perlu Anda cek manual. Default: false
//   (script HANYA melaporkan inkonsistensi, tidak menulis status).
const REEVALUATE_STATUS = false;

// URL RTDB project (region asia-southeast1).
const DATABASE_URL =
  'https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app';

// Endpoint resmi untuk recompute summary penuh (sudah ada di Cloud Functions).
// Dipakai di akhir untuk memperbaiki totalPiutang/aggregat (lihat REQUIREMENT #4).
const REPAIR_SUMMARY_URL =
  'https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net/repairAllSummaries?force=true';

// =========================================================================
// 🔑 INIT FIREBASE ADMIN
// =========================================================================
// Pilih SALAH SATU cara autentikasi:
//
//  (A) Env var (disarankan):
//        export GOOGLE_APPLICATION_CREDENTIALS=/path/serviceAccountKey.json
//      lalu biarkan baris applicationDefault() di bawah.
//
//  (B) File key eksplisit: taruh serviceAccountKey.json di folder ini dan
//      ganti blok di bawah dengan:
//        const serviceAccount = require('./serviceAccountKey.json');
//        admin.initializeApp({
//          credential: admin.credential.cert(serviceAccount),
//          databaseURL: DATABASE_URL,
//        });
// =========================================================================
admin.initializeApp({
  credential: admin.credential.applicationDefault(),
  databaseURL: DATABASE_URL,
});

const db = admin.database();

// =========================================================================
// 📝 ARRAY KOREKSI — ISI DATA ANDA DI SINI
// =========================================================================
//
// Pemetaan istilah (PENTING — beda dari tebakan awal):
//   • adminUid   = UID admin pemilik node pelanggan
//                  (di contoh Anda disebut "pelangganUid").
//   • pelangganId= ID nasabah/pinjaman
//                  (di contoh Anda disebut "pinjamanId").
//   • cabangId   = KEY node cabang yang asli (slug huruf kecil, mis. "panti"),
//                  BUKAN nama tampilan "Simpang Empat Unit 2".
//                  Cek di metadata/admins/{adminUid}/cabang bila ragu.
//   • tanggalPembayaran = format ISO "YYYY-MM-DD" (script auto-konversi
//                  ke "dd MMM yyyy" untuk matching).
//   • nominalSalah = nominal LAMA yang SALAH (WAJIB — dipakai untuk
//                  mengidentifikasi baris yang tepat secara aman).
//   • nominalBenar = nominal BARU yang benar.
//   • index      = (OPSIONAL) index array pembayaranList yang persis,
//                  diisi HANYA bila pada tanggal+nominalSalah yang sama
//                  ada >1 entri (script akan minta ini bila ambigu).
//
const corrections = [
  {
    cabangId: 'panti',                 // KEY cabang asli (huruf kecil)
    adminUid: '5MPnLCgJ7SVpwvrpOFcUyWDUNuh1',
    pelangganId: '-OfwF0O-7sRO3kV4-587',
    tanggalPembayaran: '2026-06-11',   // ISO YYYY-MM-DD
    nominalSalah: 200000,              // nilai LAMA yang salah (WAJIB)
    nominalBenar: 20000,               // nilai BARU yang benar
    index: null,                       // opsional, isi bila ambigu
  },
  // ... tambahkan koreksi lain di sini ...
];

// =========================================================================
// 🛠️  HELPERS
// =========================================================================

// Abbreviasi bulan PERSIS seperti yang dipakai DB (Mei/Agu/Okt/Des).
const BULAN_INDO = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun',
                    'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

// "2026-06-11" -> "11 Jun 2026"  (format `tanggal` di seluruh node).
function isoToTanggalIndo(iso) {
  if (!iso || typeof iso !== 'string') return null;
  const m = iso.trim().match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!m) return null;
  const [, yyyy, mm, dd] = m;
  const monthIdx = parseInt(mm, 10) - 1;
  if (monthIdx < 0 || monthIdx > 11) return null;
  return `${dd} ${BULAN_INDO[monthIdx]} ${yyyy}`;
}

// "2026-06-11" -> "2026-06"  (path bulan jurnal_transaksi).
function isoToYearMonth(iso) {
  const m = (iso || '').match(/^(\d{4})-(\d{2})-\d{2}$/);
  return m ? `${m[1]}-${m[2]}` : null;
}

// Bandingkan nominal sebagai integer rupiah (hindari quirk float).
function sameMoney(a, b) {
  return Math.round(Number(a) || 0) === Math.round(Number(b) || 0);
}

// Cache pembayaran_harian per cabang agar tidak baca berulang.
const pembayaranHarianCache = new Map();
async function loadPembayaranHarianCabang(cabangId) {
  if (pembayaranHarianCache.has(cabangId)) {
    return pembayaranHarianCache.get(cabangId);
  }
  const snap = await db.ref(`pembayaran_harian/${cabangId}`).once('value');
  const val = snap.val() || {};
  pembayaranHarianCache.set(cabangId, val);
  return val;
}

// Hitung total dibayar dari pembayaranList (paritas dgn server:
// exclude entry 'Bunga...', ikut subPembayaran).
function calculateTotalDibayar(pembayaranList) {
  let total = 0;
  if (!pembayaranList) return 0;
  const list = Array.isArray(pembayaranList)
    ? pembayaranList
    : Object.values(pembayaranList);
  list.forEach((p) => {
    if (!p) return;
    if (p.tanggal && p.tanggal.startsWith && p.tanggal.startsWith('Bunga')) return;
    total += p.jumlah || 0;
    if (p.subPembayaran) {
      const subs = Array.isArray(p.subPembayaran)
        ? p.subPembayaran
        : Object.values(p.subPembayaran);
      subs.forEach((s) => { total += (s && s.jumlah) || 0; });
    }
  });
  return total;
}

// Pencatat write: di DRY_RUN cuma log; selain itu benar-benar update.
const plannedWrites = []; // untuk ringkasan akhir
async function applyUpdate(refPath, fieldUpdates, label) {
  const detail = Object.entries(fieldUpdates)
    .map(([k, v]) => `${k}: ${v.old} -> ${v.new}`)
    .join(', ');
  console.log(`   ${DRY_RUN ? '🔎 [DRY] WOULD UPDATE' : '✍️  UPDATE'} ${label}`);
  console.log(`        path : ${refPath}`);
  console.log(`        ${detail}`);
  plannedWrites.push({ refPath, detail, applied: !DRY_RUN });
  if (DRY_RUN) return;
  const payload = {};
  Object.entries(fieldUpdates).forEach(([k, v]) => { payload[k] = v.new; });
  await db.ref(refPath).update(payload);
}

// =========================================================================
// 🔧  PROSES SATU KOREKSI
// =========================================================================
async function processCorrection(c, i) {
  const tag = `[#${i + 1}]`;
  console.log(`\n${'='.repeat(70)}`);
  console.log(`${tag} pelangganId=${c.pelangganId} | cabang=${c.cabangId}`);
  console.log(`     tanggal=${c.tanggalPembayaran} | ${c.nominalSalah} -> ${c.nominalBenar}`);

  // --- Validasi input dasar ---
  const tglIndo = isoToTanggalIndo(c.tanggalPembayaran);
  const yearMonth = isoToYearMonth(c.tanggalPembayaran);
  if (!c.adminUid || !c.pelangganId || !c.cabangId) {
    console.warn(`${tag} ⚠️  SKIP: adminUid/pelangganId/cabangId wajib diisi.`);
    return { ok: false };
  }
  if (!tglIndo || !yearMonth) {
    console.warn(`${tag} ⚠️  SKIP: tanggalPembayaran harus format YYYY-MM-DD.`);
    return { ok: false };
  }
  if (c.nominalSalah == null) {
    console.warn(`${tag} ⚠️  SKIP: nominalSalah WAJIB diisi (untuk matching aman).`);
    return { ok: false };
  }
  console.log(`     → match tanggal "${tglIndo}", jurnal bulan "${yearMonth}"`);

  let touched = 0;

  // -----------------------------------------------------------------
  // NODE 1: pelanggan/{adminUid}/{pelangganId}/pembayaranList
  // -----------------------------------------------------------------
  try {
    const pelRef = db.ref(`pelanggan/${c.adminUid}/${c.pelangganId}`);
    const pelSnap = await pelRef.once('value');
    const pelanggan = pelSnap.val();
    if (!pelanggan) {
      console.warn(`${tag} ⚠️  NODE1 SKIP: pelanggan tidak ditemukan.`);
    } else {
      const listRaw = pelanggan.pembayaranList || [];
      // Normalisasi ke pasangan [index, entry] (array numerik RTDB).
      const entries = Array.isArray(listRaw)
        ? listRaw.map((e, idx) => [idx, e])
        : Object.entries(listRaw);

      // Cari kandidat: tanggal cocok DAN jumlah == nominalSalah.
      let candidates = entries.filter(
        ([, e]) => e && e.tanggal === tglIndo && sameMoney(e.jumlah, c.nominalSalah)
      );

      // Disambiguasi via index eksplisit bila diberikan.
      if (c.index != null) {
        candidates = candidates.filter(([idx]) => String(idx) === String(c.index));
      }

      if (candidates.length === 0) {
        console.warn(`${tag} ⚠️  NODE1: tidak ada entry pembayaranList ` +
          `tanggal "${tglIndo}" senilai ${c.nominalSalah}. (sudah benar?)`);
      } else if (candidates.length > 1) {
        const idxs = candidates.map(([idx]) => idx).join(', ');
        console.warn(`${tag} ⛔ NODE1 AMBIGU: ${candidates.length} entry cocok ` +
          `(index: ${idxs}). Isi field "index" pada koreksi ini lalu ulangi. ` +
          `TIDAK ada yang diubah untuk item ini demi keamanan.`);
        return { ok: false, ambiguous: true };
      } else {
        const [idx, entry] = candidates[0];
        await applyUpdate(
          `pelanggan/${c.adminUid}/${c.pelangganId}/pembayaranList/${idx}`,
          { jumlah: { old: entry.jumlah, new: c.nominalBenar } },
          'NODE1 pelanggan.pembayaranList'
        );
        touched++;
      }
    }
  } catch (e) {
    console.error(`${tag} ❌ NODE1 error: ${e.message}`);
  }

  // -----------------------------------------------------------------
  // NODE 2: pembayaran_harian/{cabangId}/{tglRun}/{autoId}
  //   Key tanggal node = HARI FUNGSI BERJALAN (bukan tanggal bayar),
  //   jadi kita scan semua date-node lalu match by field `tanggal`
  //   + pelangganId + jumlah lama.
  // -----------------------------------------------------------------
  try {
    const byDate = await loadPembayaranHarianCabang(c.cabangId);
    const hits = [];
    for (const [dateKey, entriesObj] of Object.entries(byDate)) {
      if (!entriesObj || typeof entriesObj !== 'object') continue;
      for (const [autoId, entry] of Object.entries(entriesObj)) {
        if (!entry) continue;
        if (entry.pelangganId === c.pelangganId &&
            entry.tanggal === tglIndo &&
            sameMoney(entry.jumlah, c.nominalSalah)) {
          hits.push([dateKey, autoId, entry]);
        }
      }
    }
    if (hits.length === 0) {
      console.warn(`${tag} ⚠️  NODE2: tidak ada entry pembayaran_harian cocok ` +
        `(pelangganId+tanggal "${tglIndo}"+${c.nominalSalah}).`);
    } else {
      for (const [dateKey, autoId, entry] of hits) {
        await applyUpdate(
          `pembayaran_harian/${c.cabangId}/${dateKey}/${autoId}`,
          { jumlah: { old: entry.jumlah, new: c.nominalBenar } },
          `NODE2 pembayaran_harian (${hits.length} match)`
        );
        touched++;
      }
    }
  } catch (e) {
    console.error(`${tag} ❌ NODE2 error: ${e.message}`);
  }

  // -----------------------------------------------------------------
  // NODE 3: jurnal_transaksi/{cabangId}/{YYYY-MM}/{autoId}
  //   Match by pelangganId + tanggal + jumlah lama, tipe cicilan/tambah.
  //   Catatan: field snapshot sisaUtangSetelah/totalDibayar pada entry
  //   jurnal SENGAJA tidak ikut diubah (itu jejak historis); nilai
  //   saldo otoritatif tetap dihitung live + via repair summary.
  // -----------------------------------------------------------------
  try {
    const jurRef = db.ref(`jurnal_transaksi/${c.cabangId}/${yearMonth}`);
    const jurSnap = await jurRef.once('value');
    const jurVal = jurSnap.val() || {};
    const jHits = Object.entries(jurVal).filter(([, e]) =>
      e && e.pelangganId === c.pelangganId &&
      e.tanggal === tglIndo &&
      sameMoney(e.jumlah, c.nominalSalah) &&
      (e.tipe === 'pembayaran_cicilan' || e.tipe === 'tambah_bayar')
    );
    if (jHits.length === 0) {
      console.warn(`${tag} ⚠️  NODE3: tidak ada entry jurnal_transaksi cocok ` +
        `di bulan ${yearMonth} (pelangganId+tanggal+${c.nominalSalah}).`);
    } else {
      for (const [autoId, entry] of jHits) {
        await applyUpdate(
          `jurnal_transaksi/${c.cabangId}/${yearMonth}/${autoId}`,
          { jumlah: { old: entry.jumlah, new: c.nominalBenar } },
          `NODE3 jurnal_transaksi (${jHits.length} match)`
        );
        touched++;
      }
    }
  } catch (e) {
    console.error(`${tag} ❌ NODE3 error: ${e.message}`);
  }

  // -----------------------------------------------------------------
  // RE-EVALUASI STATUS LUNAS (opsional)
  // -----------------------------------------------------------------
  try {
    const pelSnap = await db.ref(`pelanggan/${c.adminUid}/${c.pelangganId}`).once('value');
    const pelanggan = pelSnap.val();
    if (pelanggan) {
      // Hitung total dibayar dgn ASUMSI koreksi sudah diterapkan.
      // (Di DRY_RUN node belum berubah, jadi kita simulasikan delta.)
      let totalDibayar = calculateTotalDibayar(pelanggan.pembayaranList);
      if (DRY_RUN) {
        totalDibayar += (c.nominalBenar - c.nominalSalah);
      }
      const totalPelunasan = pelanggan.totalPelunasan || 0;
      const seharusnyaLunas = totalPelunasan > 0 && totalDibayar >= totalPelunasan;
      const statusSekarang = (pelanggan.status || '').toLowerCase();
      const ditandaiLunas = statusSekarang === 'lunas' ||
        !!(pelanggan.tanggalLunasCicilan && String(pelanggan.tanggalLunasCicilan).trim());

      if (ditandaiLunas !== seharusnyaLunas) {
        console.log(`${tag} 🔁 STATUS INKONSISTEN setelah koreksi:`);
        console.log(`        totalPelunasan=${totalPelunasan}, totalDibayar(≈)=${totalDibayar}`);
        console.log(`        ditandaiLunas=${ditandaiLunas} → seharusnya=${seharusnyaLunas}`);
        if (REEVALUATE_STATUS) {
          const upd = seharusnyaLunas
            ? { status: { old: pelanggan.status, new: 'Lunas' },
                tanggalLunasCicilan: { old: pelanggan.tanggalLunasCicilan || '', new: tglIndo } }
            : { status: { old: pelanggan.status, new: 'Aktif' },
                tanggalLunasCicilan: { old: pelanggan.tanggalLunasCicilan || '', new: '' } };
          await applyUpdate(`pelanggan/${c.adminUid}/${c.pelangganId}`, upd, 'STATUS pelanggan');
          console.log(`        ⚠️  Cek manual: event_harian/${c.cabangId}/.../nasabah_lunas/` +
            `${c.pelangganId} dan pelanggan_bermasalah/${c.cabangId}/${c.pelangganId}`);
        } else {
          console.log(`        ℹ️  REEVALUATE_STATUS=false → hanya dilaporkan, status TIDAK diubah.`);
        }
      }
    }
  } catch (e) {
    console.error(`${tag} ❌ Status re-eval error: ${e.message}`);
  }

  console.log(`${tag} selesai — ${touched} node ${DRY_RUN ? 'AKAN' : ''} diubah.`);
  return { ok: touched > 0, touched };
}

// =========================================================================
// 🚀  MAIN
// =========================================================================
async function main() {
  console.log('='.repeat(70));
  console.log('  SCRIPT KOREKSI NOMINAL PEMBAYARAN — SURGICAL PATCH');
  console.log(`  MODE: ${DRY_RUN ? '🔎 DRY RUN (tidak menulis)' : '✍️  LIVE (menulis ke DB)'}`);
  console.log(`  REEVALUATE_STATUS: ${REEVALUATE_STATUS}`);
  console.log(`  Jumlah koreksi: ${corrections.length}`);
  console.log('='.repeat(70));

  let ok = 0, fail = 0, ambiguous = 0;
  for (let i = 0; i < corrections.length; i++) {
    try {
      const r = await processCorrection(corrections[i], i);
      if (r.ok) ok++;
      else if (r.ambiguous) ambiguous++;
      else fail++;
    } catch (e) {
      // Jangan hentikan batch — lanjut ke item berikutnya.
      console.error(`[#${i + 1}] ❌ FATAL item error: ${e.message}`);
      fail++;
    }
  }

  console.log(`\n${'='.repeat(70)}`);
  console.log('  RINGKASAN');
  console.log(`  Berhasil (ada perubahan) : ${ok}`);
  console.log(`  Tanpa perubahan / gagal  : ${fail}`);
  console.log(`  Ambigu (perlu index)     : ${ambiguous}`);
  console.log(`  Total write ${DRY_RUN ? 'TERENCANA' : 'DITERAPKAN'}    : ${plannedWrites.length}`);
  console.log('='.repeat(70));

  // -----------------------------------------------------------------
  // REQUIREMENT #4 — PERBAIKAN SUMMARY (cara yang BENAR)
  // -----------------------------------------------------------------
  // Catatan penting: "dummy update" ke node pelanggan TIDAK akan memicu
  // recalc piutang. Trigger onPelangganWrite memanggil calculateDelta(
  // before, after); karena pembayaranList di before == after (sudah
  // dikoreksi), delta = 0 dan summary di-skip. Jadi cara itu no-op.
  //
  // Yang BENAR: panggil endpoint full-recompute resmi yang sudah ada,
  // yang menghitung ulang summary dari scan pelanggan (idempotent):
  if (!DRY_RUN) {
    console.log('\n🔄 Memicu recompute summary penuh (repairAllSummaries?force=true)...');
    try {
      // Node 18+ punya global fetch. (Project ini Node 20.)
      const resp = await fetch(REPAIR_SUMMARY_URL);
      const body = await resp.json().catch(() => ({}));
      console.log('   ✅ repairAllSummaries:', JSON.stringify(body.summary || body));
    } catch (e) {
      console.warn(`   ⚠️  Gagal auto-call repairAllSummaries: ${e.message}`);
      console.warn('   → Jalankan manual di browser/curl:');
      console.warn(`     ${REPAIR_SUMMARY_URL}`);
    }
  } else {
    console.log('\nℹ️  (DRY RUN) Setelah LIVE, perbaiki summary dengan membuka:');
    console.log(`     ${REPAIR_SUMMARY_URL}`);
  }

  console.log('\nSelesai.');
  process.exit(0);
}

main().catch((e) => {
  console.error('FATAL:', e);
  process.exit(1);
});
