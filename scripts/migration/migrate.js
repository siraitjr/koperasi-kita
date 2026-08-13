#!/usr/bin/env node
'use strict';
/* =========================================================================
 * KOPERASI KITA — MIGRASI FIREBASE RTDB → SUPABASE (PostgreSQL)
 * Strategi: CUTOVER (one-shot). Dijalankan MANUAL di laptop Anda.
 * =========================================================================
 *
 * BELUM PERNAH DIJALANKAN. Tidak ada Postgres/Supabase di environment tempat
 * skrip ini ditulis. Baca docs/migration/supabase/006_migration_script.md
 * sebelum menjalankan — ADA PATCH SKEMA WAJIB yang harus diterapkan dulu.
 *
 * SIFAT SKRIP
 * -----------
 *  - DRY-RUN secara default. Menulis ke DB hanya dengan flag --execute.
 *  - IDEMPOTEN. Semua primary key diturunkan deterministik (UUIDv5) dari
 *    path Firebase, dan semua INSERT memakai ON CONFLICT DO NOTHING.
 *    Menjalankan ulang skrip ini TIDAK menggandakan data.
 *  - RESUMABLE. Checkpoint per fase disimpan ke berkas.
 *  - TIDAK MENGHAPUS APA PUN, di sisi mana pun. Firebase tidak disentuh
 *    (berkas JSON dibuka read-only).
 *
 * PEMAKAIAN
 *   node --max-old-space-size=8192 migrate.js --file=/path/export.json --dry-run
 *   node --max-old-space-size=8192 migrate.js --file=/path/export.json \
 *        --dsn="postgresql://postgres:PASS@db.xxx.supabase.co:5432/postgres" --execute
 *
 * Flag: --file --dsn --execute --only=<fase,fase> --checkpoint=<path>
 *       --report=<path> --batch=<n>
 * ========================================================================= */

const fs = require('fs');
const crypto = require('crypto');

// `pg` di-require malas supaya mode --dry-run bisa jalan tanpa dependensi.
let Client = null;

// ------------------------------------------------------------------ CLI ---
const argv = process.argv.slice(2);
const arg = (k, def = null) => {
  const hit = argv.find((a) => a === `--${k}` || a.startsWith(`--${k}=`));
  if (!hit) return def;
  const eq = hit.indexOf('=');
  return eq === -1 ? true : hit.slice(eq + 1);
};

const CFG = {
  file: arg('file'),
  dsn: arg('dsn', process.env.SUPABASE_DSN),
  execute: arg('execute', false) === true,
  only: arg('only') ? String(arg('only')).split(',').map((s) => s.trim()) : null,
  checkpoint: arg('checkpoint', './migration_checkpoint.json'),
  report: arg('report', './migration_report.json'),
  batch: parseInt(arg('batch', '500'), 10),
};

if (!CFG.file) {
  console.error('FATAL: --file=<path export RTDB .json> wajib diisi.');
  process.exit(2);
}
if (CFG.execute && !CFG.dsn) {
  console.error('FATAL: --execute butuh --dsn atau env SUPABASE_DSN.');
  process.exit(2);
}

// --------------------------------------------------------------- UTIL -----
const log = (...a) => console.log(...a);
const warn = (...a) => console.warn('  ⚠ ', ...a);

/** Kumpulan anomali data. Tidak menghentikan migrasi; dilaporkan di akhir. */
const ISSUES = [];
const issue = (kind, detail) => {
  ISSUES.push({ kind, detail });
  if (ISSUES.filter((i) => i.kind === kind).length <= 5) warn(`${kind}: ${detail}`);
};

/* UUIDv5 (RFC 4122, SHA-1) — tanpa dependensi eksternal.
 * Dipakai supaya SETIAP baris punya id yang bisa dihitung ulang dari path
 * Firebase-nya. Konsekuensinya: re-run menghasilkan id yang sama persis,
 * sehingga ON CONFLICT DO NOTHING benar-benar membuat skrip idempoten, dan
 * foreign key antar fase tidak perlu tabel pemetaan sama sekali. */
const NS = Buffer.from('6ba7b8119dad11d180b400c04fd430c8', 'hex'); // NS_DNS
function uuidv5(name) {
  const h = crypto.createHash('sha1');
  h.update(NS);
  h.update(Buffer.from(String(name), 'utf8'));
  const b = h.digest();
  b[6] = (b[6] & 0x0f) | 0x50; // versi 5
  b[8] = (b[8] & 0x3f) | 0x80; // varian RFC
  const x = b.toString('hex');
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20, 32)}`;
}

// Turunan id per entitas. Jangan ubah string prefiks setelah migrasi jalan —
// mengubahnya berarti seluruh id berubah dan idempotensi hilang.
const ID = {
  user: (uid) => uuidv5(`user:${uid}`),
  cabang: (c) => slugCabang(c),
  nasabah: (adminUid, pid) => uuidv5(`nasabah:${adminUid}/${pid}`),
  pinjaman: (adminUid, pid, ke) => uuidv5(`pinjaman:${adminUid}/${pid}/${ke}`),
  bayar: (key) => uuidv5(`bayar:${key}`),
  jadwal: (adminUid, pid, ke, i) => uuidv5(`jadwal:${adminUid}/${pid}/${ke}/${i}`),
  pengajuan: (cab, id) => uuidv5(`pengajuan:${cab}/${id}`),
  step: (cab, id, phase) => uuidv5(`step:${cab}/${id}/${phase}`),
  jurnal: (cab, bulan, id) => uuidv5(`jurnal:${cab}/${bulan}/${id}`),
  kasir: (cab, bulan, id) => uuidv5(`kasir:${cab}/${bulan}/${id}`),
  histori: (adminUid, pid, hid) => uuidv5(`histori:${adminUid}/${pid}/${hid}`),
  ditolak: (adminUid, rid) => uuidv5(`ditolak:${adminUid}/${rid}`),
  statusKhusus: (cab, pid) => uuidv5(`statuskhusus:${cab}/${pid}`),
};

/* cabangId RTDB adalah teks bebas dengan SPASI ("simpang empat unit 1",
 * "panti", "payakumbuh" — terlihat di metadata/admins pada sampel). Dipakai
 * apa adanya sebagai PK teks di tabel `cabang`; hanya dirapikan spasi/kapital
 * agar "Panti" dan "panti " tidak jadi dua cabang. */
function slugCabang(c) {
  return String(c == null ? '' : c).trim().toLowerCase().replace(/\s+/g, ' ');
}

/* Tanggal RTDB: "12 Nov 2025" dengan singkatan bulan BAHASA INDONESIA.
 * Peta ini disalin dari functions/jurnalTransaksi.js:41-44 — perhatikan
 * Mei/Agu/Okt/Des yang berbeda dari bahasa Inggris. Memakai Date.parse()
 * di sini akan diam-diam menghasilkan Invalid Date untuk empat bulan itu. */
const BULAN = {
  jan: 1, feb: 2, mar: 3, apr: 4, mei: 5, jun: 6,
  jul: 7, agu: 8, sep: 9, okt: 10, nov: 11, des: 12,
  // toleransi data campuran yang ditulis perangkat ber-locale Inggris
  may: 5, aug: 8, oct: 10, dec: 12,
  // "Agt" — singkatan Agustus yang dipakai sebagian perangkat. Ditemukan di
  // dry run pertama: 70 entri TANGGAL_BULAN_TIDAK_DIKENAL, seluruhnya ini.
  agt: 8,
};

function parseTanggal(v) {
  if (v == null || v === '') return null;
  const s = String(v).trim();
  let m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s);          // "2026-01-19"
  if (m) return `${m[1]}-${m[2]}-${m[3]}`;
  /* Suffix jam OPSIONAL: "12 Nov 2025, 14:30" / "12 Nov 2025 14:30:07".
   * Dry run pertama melaporkan 252 TANGGAL_TIDAK_TERBACA — seluruhnya bentuk
   * ini, yang dulu ditolak semata karena anchor `$` menuntut string berakhir
   * tepat setelah tahun. Komponen jamnya memang DIBUANG: kolom tujuannya
   * bertipe `date`, dan seluruh laporan koperasi berbasis hari, bukan jam. */
  m = /^(\d{1,2})\s+([A-Za-z]{3,})\s+(\d{4})(?:,?\s+\d{1,2}:\d{2}(?::\d{2})?)?$/.exec(s);
  if (m) {
    const bln = BULAN[m[2].slice(0, 3).toLowerCase()];
    if (!bln) { issue('TANGGAL_BULAN_TIDAK_DIKENAL', s); return null; }
    return `${m[3]}-${String(bln).padStart(2, '0')}-${String(+m[1]).padStart(2, '0')}`;
  }
  if (/^\d{12,}$/.test(s)) return new Date(+s).toISOString().slice(0, 10); // epoch ms
  const iso = /^(\d{4}-\d{2}-\d{2})T/.exec(s);          // ISO createdAt
  if (iso) return iso[1];
  issue('TANGGAL_TIDAK_TERBACA', s);
  return null;
}

function ts(v) {
  if (v == null || v === '') return null;
  if (typeof v === 'number') return new Date(v).toISOString();
  if (/^\d{12,}$/.test(String(v))) return new Date(+v).toISOString();
  const d = new Date(v);
  return isNaN(d.getTime()) ? null : d.toISOString();
}

/* =========================================================================
 * NIK — normalisasi placeholder menjadi NULL
 * =========================================================================
 * Kolom `nasabah.nik` punya unique index parsial `where nik is not null`
 * (001 §2), pengganti node nik_registry. Postgres mengizinkan BANYAK baris
 * NULL pada index unik, jadi NULL adalah cara yang benar untuk menyatakan
 * "NIK tidak diketahui" — bukan string kosong, dan bukan angka isian.
 *
 * Kegagalan --execute pada tabel nasabah berasal dari sini: "0000000000000000"
 * lolos uji 16-digit sehingga diperlakukan sebagai NIK sungguhan, lalu semua
 * nasabah tanpa KTP saling bertabrakan di index yang sama.
 *
 * Perhatikan: alur cairkan–serah terima memang SENGAJA mengosongkan NIK
 * (buildCairkanCleansePayload menulis "nik" to ""), jadi NIK kosong adalah
 * keadaan normal di data ini, bukan kerusakan.
 */
/* NIK dummy yang sudah pasti bukan identitas, di luar aturan umum di bawah. */
const NIK_DUMMY = new Set([
  '0000000000000000',
  '0000000000000010',   // ditemukan dry run: dipakai LENI dan Putri
  '1111111111111111',
  '1234567890123456',
]);

function nikBersih(v) {
  const t = str(v).trim();
  if (!/^\d{16}$/.test(t)) return null;   // kosong, "-", "0", <16, ada huruf
  if (NIK_DUMMY.has(t)) return null;

  /* Dua digit pertama NIK adalah KODE PROVINSI, rentangnya 11–94. "00"
   * tidak pernah sah, jadi seluruh NIK berawalan "00" pasti isian, bukan
   * nomor KTP sungguhan. Aturan ini menangkap "0000000000000000" dan
   * "0000000000000010" sekaligus — dan juga varian berawalan nol lain yang
   * belum kita lihat, sehingga tidak perlu menunggu gagal dulu untuk
   * menambahkannya satu per satu ke daftar di atas. */
  if (t.startsWith('00')) return null;

  if (/^(\d)\1{15}$/.test(t)) return null;  // 16 digit identik
  return t;
}

/** Uang → integer rupiah. Menolak desimal & string berformat. */
function rupiah(v) {
  if (v == null || v === '') return 0;
  if (typeof v === 'number') return Math.round(v);
  const n = Number(String(v).replace(/[^0-9-]/g, ''));
  return Number.isFinite(n) ? Math.round(n) : 0;
}
const int = (v, d = 0) => {
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? n : d;
};
const str = (v) => (v == null ? '' : String(v));

/* Array RTDB bisa berupa Array asli ATAU objek ber-key numerik bercelah
 * ({"0":…,"1":…,"21":…}) akibat penghapusan di tengah. Kotlin menanganinya
 * lewat safePembayaranList (PelangganViewModel.kt:316-318) dengan MEMBUANG
 * entri null — bukan memperlakukannya sebagai nilai 0. Perilaku itu ditiru
 * persis di sini. Indeks asli dipertahankan karena ikut menyusun kunci
 * idempotensi. */
function toIndexedList(v) {
  if (v == null) return [];
  const out = [];
  if (Array.isArray(v)) {
    v.forEach((item, i) => { if (item != null) out.push([i, item]); });
    return out;
  }
  if (typeof v !== 'object') return [];
  for (const k of Object.keys(v)) {
    if (k === '...') continue;                 // penanda sampel terpotong
    if (!/^\d+$/.test(k)) continue;
    if (v[k] != null) out.push([parseInt(k, 10), v[k]]);
  }
  return out.sort((a, b) => a[0] - b[0]);
}

/** Buang key sampah yang tidak boleh ikut migrasi. */
const SKIP_KEY = new Set(['...', '_guardPinjamanKe', '_guardStatus']);
const realKeys = (o) => (o && typeof o === 'object' ? Object.keys(o).filter((k) => !SKIP_KEY.has(k)) : []);

// ------------------------------------------------------- STATUS MAPPING ---
/* Domain status pinjaman diambil dari literal di kode Android
 * (sensus grep: "Aktif", "Menunggu Approval", "Lunas", "Disetujui",
 * "Ditolak", "Tidak Aktif"). nik_registry memakai huruf kecil ("aktif") —
 * konvensi berbeda; normalisasi di bawah menyatukan keduanya. */
const STATUS_MAP = new Map([
  ['menunggu approval', 'Menunggu Approval'],
  ['disetujui', 'Disetujui'],
  ['aktif', 'Aktif'],
  ['lunas', 'Lunas'],
  ['ditolak', 'Ditolak'],
  ['tidak aktif', 'Tidak Aktif'],
]);

function normStatus(v, ctx) {
  const k = str(v).trim().toLowerCase();
  if (k === '') return 'Menunggu Approval';
  const hit = STATUS_MAP.get(k);
  if (hit) return hit;
  issue('STATUS_TIDAK_DIKENAL', `${JSON.stringify(v)} @ ${ctx}`);
  return null; // ditolak; baris dilewati agar tidak gagal di cast enum
}

const enumOrNull = (v, allowed, ctx) => {
  const s = str(v).trim();
  if (s === '') return null;
  if (allowed.includes(s)) return s;
  issue('ENUM_TIDAK_DIKENAL', `${s} @ ${ctx}`);
  return null;
};

// ================================================================= LOAD ===
log('▶ Membaca export …', CFG.file);
const t0 = Date.now();
const RAW = fs.readFileSync(CFG.file, 'utf8');
const DB = JSON.parse(RAW);
log(`  ${(RAW.length / 1048576).toFixed(1)} MB, parse ${(Date.now() - t0) / 1000}s`);

// Deteksi berkas SAMPEL yang terpotong — agar tidak pernah diimpor sebagai
// data sungguhan. data/firebase_sample.json memuat 261 penanda "(N more keys)".
if (RAW.includes('more keys')) {
  console.error('\nFATAL: berkas ini berisi penanda "(N more keys)" — export TERPOTONG,');
  console.error('bukan data lengkap. Pakai export RTDB penuh (±89 MB).');
  process.exit(3);
}

const node = (n) => (DB && DB[n] && typeof DB[n] === 'object' ? DB[n] : {});

// ============================================================== BUFFERS ===
const ROWS = {
  cabang: [], app_user: [], nasabah: [], pinjaman: [], pembayaran: [],
  jadwal_cicilan: [], pengajuan: [], approval_step: [], jurnal_transaksi: [],
  kasir_entry: [], dokumen: [],
  pinjaman_history: [], biaya_awal: [], pelanggan_ditolak: [],
  koreksi_storting: [], pelanggan_status_khusus: [],
};
const seenBayarKey = new Map(); // untuk R-05: disambiguasi client_op_id

/* Himpunan id yang BENAR-BENAR akan diinsert. Dipakai fase historis untuk
 * memeriksa induk sebelum menaut — data arsip sering menunjuk nasabah/admin
 * yang sudah tidak ada, dan FK akan menolak seluruh transaksi kalau dibiarkan. */
const userAda = new Set();
const cabangAda = new Set();
const nasabahAda = new Set();

/* Nama admin (uid → nama) untuk memperkaya laporan. Sekadar "adminUid/pid"
 * tidak cukup bagi pemilik untuk memutuskan cabang tujuan — ia perlu tahu
 * SIAPA nasabahnya dan admin mana yang memegangnya. */
const namaAdmin = new Map();

/* Cabang tiap admin (uid → cabang_id), untuk kebijakan pewarisan cabang
 * di bawah. */
const cabangAdmin = new Map();

/* Nasabah yang cabangnya DIWARISI dari admin pemiliknya. Dicatat lengkap,
 * bukan sekadar dihitung: ini keputusan yang mengubah data, jadi harus bisa
 * ditelusuri balik satu per satu bila kelak ada yang salah tempat. */
const CABANG_DIWARISI = [];

/* Cabang yang pimpinanUid-nya tidak terdaftar sebagai admin. */
const PIMPINAN_YATIM = [];

/* Nasabah yang cabangId-nya kosong. Dikumpulkan lengkap (bukan sekadar
 * dihitung) supaya bisa langsung dipakai memilih cabang tujuan. */
const PERLU_CABANG = [];

// ================================================== FASE 1: CABANG & USER ==
function faseUser() {
  const admins = node('metadata').admins || {};
  const roles = node('metadata').roles || {};
  const cabangMeta = node('metadata').cabang || {};

  const setRole = (uid, r) => {
    const u = ROWS.app_user.find((x) => x._uid === uid);
    if (u) u.role = r;
  };

  /* Himpunan uid yang benar-benar terdaftar di metadata/admins. Dipakai
   * memvalidasi pimpinanUid sebelum dijadikan FK — lihat di bawah. */
  const adminUids = new Set(realKeys(admins));

  const cabangSeen = new Map();
  for (const uid of realKeys(admins)) {
    const a = admins[uid] || {};
    const cab = slugCabang(a.cabang);
    if (cab) cabangSeen.set(cab, a.cabangName || a.cabang || cab);

    /* metadata/admins TIDAK selalu punya `cabang`: pada sampel, pengawas dan
     * sekretaris tidak memilikinya. Skema 001 memaksa cabang_id NOT NULL untuk
     * peran selain pengawas/koordinator — patch di 006 melonggarkannya agar
     * `sekretaris` tidak menggagalkan impor. */
    ROWS.app_user.push({
      _uid: uid,
      id: ID.user(uid),
      email: str(a.email) || `${uid}@migrasi.invalid`,
      nama: str(a.name),
      role: str(a.role) || 'admin',
      cabang_id: cab || null,
      foto_url: a.photoUrl || null,
      aktif: a.aktif === false ? false : true,
      legacy_uid: uid,
    });
  }

  // metadata/roles/{role}/{uid}: true — flag yang menimpa role dasar.
  for (const r of ['pengawas', 'koordinator']) {
    for (const uid of realKeys(roles[r] || {})) {
      if (roles[r][uid] === true) setRole(uid, r);
    }
  }

  for (const [id, nama] of cabangSeen) {
    ROWS.cabang.push({ id, nama: str(nama) || id, pimpinan_id: null, aktif: true });
  }
  // Lengkapi dari metadata/cabang bila ada (termasuk pimpinanUid).
  for (const c of realKeys(cabangMeta)) {
    const id = slugCabang(c);
    const m = cabangMeta[c] || {};
    let row = ROWS.cabang.find((x) => x.id === id);
    if (!row) { row = { id, nama: str(m.name) || id, pimpinan_id: null, aktif: true }; ROWS.cabang.push(row); }
    /* pimpinanUid BELUM TENTU terdaftar di metadata/admins — bisa akun lama
     * yang sudah dihapus, atau salah ketik. Kalau dipakai apa adanya, FK
     * cabang_pimpinan_fk gagal dan SELURUH impor ter-rollback gara-gara satu
     * cabang. Dilaporkan sebagai anomali dan dibiarkan NULL; pimpinannya bisa
     * diisi manual sesudah impor tanpa mengulang apa pun. */
    if (m.pimpinanUid) {
      const puid = str(m.pimpinanUid);
      if (adminUids.has(puid)) {
        row.pimpinan_id = ID.user(puid);
      } else {
        issue('PIMPINAN_TIDAK_TERDAFTAR',
          `cabang '${id}' menunjuk pimpinanUid ${puid} yang tidak ada di ` +
          `metadata/admins — pimpinan_id dibiarkan NULL`);
        PIMPINAN_YATIM.push({ cabangId: id, pimpinanUid: puid, cabangName: str(m.name) || id });
      }
    }
    if (m.name) row.nama = str(m.name);
  }

  if (!realKeys(cabangMeta).length) {
    issue('METADATA_CABANG_KOSONG',
      'metadata/cabang tidak ada di export — pimpinan_id semua cabang NULL. ' +
      'Isi manual sesudah impor, kalau tidak RLS pimpinan tidak akan berfungsi.');
  }
  ROWS.app_user.forEach((u) => {
    userAda.add(u.id);
    namaAdmin.set(u._uid, u.nama || u.email || u._uid);
    if (u.cabang_id) cabangAdmin.set(u._uid, u.cabang_id);
  });
  ROWS.cabang.forEach((c) => cabangAda.add(c.id));
  log(`  cabang=${ROWS.cabang.length} app_user=${ROWS.app_user.length}`);
}

// ============================== FASE 2: NASABAH + PINJAMAN (semua generasi) =
/* Satu node `pelanggan` memuat generasi BERJALAN; generasi lama ada di
 * `riwayat_pinjaman/{adminUid}/{pelangganId}/{N}`. Keduanya digabung lalu
 * diurutkan menaik, karena skema 001 §3.3 menolak generasi yang melompat.
 *
 * TEMUAN DATA NYATA (sampel): arsip generasinya SPARSE dan bisa dimulai dari 0
 * — terlihat ['4'], ['2'], ['7'], ['0','1']. Jadi urutan rapat TIDAK BISA
 * diasumsikan, dan pinjaman_ke=0 nyata ada. Patch skema di 006 menurunkan
 * CHECK ke >= 0 dan mematikan trigger urutan selama impor. */
function nasabahDariRecord(adminUid, pid, p) {
  return {
    id: ID.nasabah(adminUid, pid),
    legacy_pelanggan_id: pid,
    legacy_admin_uid: adminUid,
    nik: nikBersih(p.nik),
    nama_ktp: str(p.namaKtp) || str(p.namaPanggilan) || '(tanpa nama)',
    nama_panggilan: str(p.namaPanggilan),
    nomor_anggota: str(p.nomorAnggota) || null,
    nama_ktp_suami: str(p.namaKtpSuami),
    nama_ktp_istri: str(p.namaKtpIstri),
    nik_suami: nikBersih(p.nikSuami),
    nik_istri: nikBersih(p.nikIstri),
    nama_panggilan_suami: str(p.namaPanggilanSuami),
    nama_panggilan_istri: str(p.namaPanggilanIstri),
    alamat_ktp: str(p.alamatKtp),
    alamat_rumah: str(p.alamatRumah),
    detail_rumah: str(p.detailRumah),
    wilayah: str(p.wilayah),
    wilayah_normalized: str(p.wilayahNormalized),
    no_hp: str(p.noHp),
    jenis_usaha: str(p.jenisUsaha),
    cabang_id: slugCabang(p.cabangId) || null,
    admin_id: ID.user(adminUid),
    status_khusus: str(p.statusKhusus),
    catatan_status_khusus: str(p.catatanStatusKhusus),
    tanggal_status_khusus: parseTanggal(p.tanggalStatusKhusus),
    updated_at: ts(p.lastUpdated) || new Date().toISOString(),
  };
}

function pinjamanDariRecord(adminUid, pid, ke, p, ctx) {
  const st = normStatus(p.status, ctx);
  if (st === null) return null;
  return {
    id: ID.pinjaman(adminUid, pid, ke),
    nasabah_id: ID.nasabah(adminUid, pid),
    pinjaman_ke: ke,
    status: st,
    besar_pinjaman: rupiah(p.besarPinjaman),
    besar_pinjaman_diajukan: rupiah(p.besarPinjamanDiajukan),
    besar_pinjaman_disetujui: rupiah(p.besarPinjamanDisetujui),
    jasa_pinjaman: int(p.jasaPinjaman, 10),
    biaya_admin: rupiah(p.admin),           // RTDB `admin` → biaya_admin
    simpanan_awal: rupiah(p.simpanan),      // skalar, BUKAN ledger (lihat 006)
    total_diterima: rupiah(p.totalDiterima),
    total_pelunasan: rupiah(p.totalPelunasan),
    tenor: Math.max(1, int(p.tenor, 30)),
    tipe_pinjaman: str(p.tipePinjaman) || 'dibawah_3jt',
    tanggal_pengajuan: parseTanggal(p.tanggalPengajuan),
    tanggal_daftar: parseTanggal(p.tanggalDaftar),
    tanggal_pencairan: parseTanggal(p.tanggalPencairan),
    tanggal_pelunasan: parseTanggal(p.tanggalPelunasan),
    tanggal_lunas_cicilan: parseTanggal(p.tanggalLunasCicilan),
    catatan_approval: str(p.catatanApproval),
    tanggal_approval: parseTanggal(p.tanggalApproval),
    alasan_penolakan: str(p.alasanPenolakan),
    // `catatanPerubahanPinjaman` disalin agar teksnya tidak lenyap (lihat 004).
    catatan_admin: [str(p.catatan), str(p.catatanPerubahanPinjaman)].filter(Boolean).join(' | '),
    status_serah_terima: enumOrNull(p.statusSerahTerima, ['Pending', 'Selesai'], ctx),
    tanggal_serah_terima: parseTanggal(p.tanggalSerahTerima),
    tarik_tabungan: rupiah(p.tarikTabungan),
    status_pencairan_simpanan: enumOrNull(
      p.statusPencairanSimpanan, ['Menunggu Pencairan', 'Dicairkan'], ctx),
    tanggal_pencairan_simpanan: parseTanggal(p.tanggalPencairanSimpanan),
    sisa_utang_lama_sebelum_top_up: rupiah(p.sisaUtangLamaSebelumTopUp),
    total_pelunasan_lama_sebelum_top_up: rupiah(p.totalPelunasanLamaSebelumTopUp),
    besar_pinjaman_lama_sebelum_top_up: rupiah(p.besarPinjamanLamaSebelumTopUp),
  };
}

/* Kunci idempotensi pembayaran.
 * clientOpId baru diperkenalkan belakangan; baris lama bernilai "" (default
 * di PelangganViewModel.kt:112) sehingga tidak bisa dipakai apa adanya untuk
 * kolom UNIQUE NOT NULL. Turunannya dibuat dari isi baris.
 *
 * Bahaya yang disadari (R-05 di dokumen 005): dua setoran SAH dengan tanggal
 * dan jumlah identik pada pinjaman yang sama menghasilkan kunci yang sama,
 * dan indeks array RTDB bergeser bila ada penghapusan di tengah sehingga
 * tidak stabil antar-export. Penyelesaiannya: bila kunci berulang, tambahkan
 * penghitung urut (#2, #3 …). Urutan iterasi ditentukan indeks array menaik,
 * jadi hasilnya deterministik untuk export yang sama — dan tidak ada
 * pembayaran yang hilang diam-diam. */
function kunciBayar(base) {
  const n = (seenBayarKey.get(base) || 0) + 1;
  seenBayarKey.set(base, n);
  return n === 1 ? base : `${base}#${n}`;
}

function tarikPembayaran(adminUid, pid, ke, p) {
  const pinjamanId = ID.pinjaman(adminUid, pid, ke);
  const adminId = ID.user(adminUid);

  for (const [i, bayar] of toIndexedList(p.pembayaranList)) {
    if (!bayar || typeof bayar !== 'object') continue;
    const jml = rupiah(bayar.jumlah);
    const tgl = parseTanggal(bayar.tanggal);
    if (jml <= 0 || !tgl) {
      /* Tetap DILEWATI (keputusan pemilik, 12 Agu 2026): entri tanpa jumlah
       * yang sah adalah sampah, bukan pembayaran. Alasannya dibedakan agar
       * terlihat di laporan mana yang benar-benar kosong dan mana yang
       * jumlahnya ada tetapi tanggalnya tidak terbaca — dua hal berbeda. */
      const sebab = (bayar.jumlah == null || bayar.jumlah === '')
        ? 'jumlah kosong/undefined (sampah)'
        : (jml <= 0 ? `jumlah tidak sah (${bayar.jumlah})` : `tanggal tidak terbaca (${bayar.tanggal})`);
      issue('BAYAR_DILEWATI', `${pid}/${ke}[${i}] — ${sebab}`);
      continue;
    }
    const cid = str(bayar.clientOpId);
    const base = cid !== '' ? `op:${cid}` : `derive:${pid}/${ke}/${tgl}/${jml}`;
    const key = kunciBayar(base);
    ROWS.pembayaran.push({
      id: ID.bayar(key), pinjaman_id: pinjamanId, jenis: 'cicilan',
      jumlah: jml, tanggal: tgl, keterangan: str(bayar.keterangan),
      client_op_id: ID.bayar(key), dicatat_oleh: adminId,
    });

    /* subPembayaran BERSARANG di dalam tiap pembayaran (bukan array sejajar
     * seperti disebut CLAUDE.md §5.2). Diratakan jadi baris tersendiri
     * berjenis 'tambah_bayar'; tautan ke induk disimpan di kolom
     * parent_pembayaran_id yang ditambahkan patch 006. */
    for (const [j, sub] of toIndexedList(bayar.subPembayaran)) {
      if (!sub || typeof sub !== 'object') continue;
      const sj = rupiah(sub.jumlah);
      const stg = parseTanggal(sub.tanggal) || tgl;
      if (sj <= 0) continue;
      const sk = kunciBayar(`sub:${pid}/${ke}/${i}/${j}/${stg}/${sj}`);
      ROWS.pembayaran.push({
        id: ID.bayar(sk), pinjaman_id: pinjamanId, jenis: 'tambah_bayar',
        jumlah: sj, tanggal: stg,
        keterangan: str(sub.keterangan) || 'Tambah Bayar',
        client_op_id: ID.bayar(sk), dicatat_oleh: adminId,
        parent_pembayaran_id: ID.bayar(key),
      });
    }
  }

  for (const [i, s] of toIndexedList(p.hasilSimulasiCicilan)) {
    if (!s || typeof s !== 'object') continue;
    const tgl = parseTanggal(s.tanggal);
    if (!tgl) continue;
    ROWS.jadwal_cicilan.push({
      pinjaman_id: pinjamanId, urutan: i, tanggal: tgl,
      jumlah: rupiah(s.jumlah),
      is_hari_kerja: s.isHariKerja !== false,
      is_completed: s.isCompleted === true,
    });
  }
}

function faseNasabahPinjaman() {
  const pelanggan = node('pelanggan');
  const riwayat = node('riwayat_pinjaman');
  const nasabahSeen = new Set();

  for (const adminUid of realKeys(pelanggan)) {
    for (const pid of realKeys(pelanggan[adminUid])) {
      const p = pelanggan[adminUid][pid];
      if (!p || typeof p !== 'object') continue;

      if (!nasabahSeen.has(ID.nasabah(adminUid, pid))) {
        const n = nasabahDariRecord(adminUid, pid, p);
        /* KEBIJAKAN PEWARISAN CABANG (keputusan pemilik, 12 Agu 2026)
         * ---------------------------------------------------------------
         * Nasabah tanpa cabangId mewarisi cabang admin pemiliknya. Dasarnya
         * kuat: seorang admin lapangan hanya bekerja di satu cabang, jadi
         * nasabah yang dipegangnya pasti berada di cabang itu — cabangId yang
         * kosong adalah field yang tidak terisi, bukan nasabah tanpa cabang.
         *
         * Bila ADMIN-nya sendiri tanpa cabang, tidak ada yang bisa diwarisi
         * dan nasabahnya tetap masuk daftar yang perlu diputuskan manual.
         * Tidak ditebak ke cabang mana pun. */
        if (!n.cabang_id) {
          const warisan = cabangAdmin.get(adminUid);
          if (warisan) {
            n.cabang_id = warisan;
            CABANG_DIWARISI.push({
              legacyPelangganId: pid,
              legacyAdminUid: adminUid,
              adminName: namaAdmin.get(adminUid) || '(admin tidak dikenal)',
              namaKtp: n.nama_ktp,
              namaPanggilan: n.nama_panggilan,
              cabangDiwarisi: warisan,
            });
          }
        }

        if (!n.cabang_id) {
          issue('NASABAH_TANPA_CABANG',
            `${adminUid}/${pid} — ${n.nama_ktp} (admin juga tanpa cabang)`);
          PERLU_CABANG.push({
            legacyPelangganId: pid,
            legacyAdminUid: adminUid,
            adminName: namaAdmin.get(adminUid) || '(admin tidak dikenal)',
            adminEmail: str(p.adminEmail),
            namaKtp: n.nama_ktp,
            namaPanggilan: n.nama_panggilan,
            nik: n.nik || '',
            wilayah: n.wilayah,
            alamatRumah: n.alamat_rumah,
            status: str(p.status),
            pinjamanKe: int(p.pinjamanKe, 1),
            besarPinjaman: rupiah(p.besarPinjaman),
            // cabangId asli apa adanya — kadang berisi spasi/teks aneh yang
            // justru menunjukkan cabang mana yang dimaksud.
            cabangIdMentah: str(p.cabangId),
          });
        }
        ROWS.nasabah.push(n);
        nasabahSeen.add(n.id);
        nasabahAda.add(n.id);
      }

      // Generasi lama (arsip) + generasi berjalan, diurutkan menaik.
      const gens = new Map();
      const arsip = (riwayat[adminUid] || {})[pid] || {};
      for (const kStr of realKeys(arsip)) {
        if (!/^\d+$/.test(kStr)) continue;
        gens.set(parseInt(kStr, 10), arsip[kStr]);
      }
      const keSekarang = int(p.pinjamanKe, 1);
      gens.set(keSekarang, p); // generasi berjalan menang atas arsip

      for (const ke of [...gens.keys()].sort((a, b) => a - b)) {
        const src = gens.get(ke);
        if (!src || typeof src !== 'object') continue;
        const row = pinjamanDariRecord(adminUid, pid, ke, src, `${pid}/${ke}`);
        if (!row) continue;
        // Arsip mewarisi identitas nasabah dari record berjalan; hanya kolom
        // finansial yang diambil dari arsip.
        ROWS.pinjaman.push(row);
        tarikPembayaran(adminUid, pid, ke, src);
      }
    }
  }
  log(`  nasabah=${ROWS.nasabah.length} pinjaman=${ROWS.pinjaman.length} ` +
      `pembayaran=${ROWS.pembayaran.length} jadwal=${ROWS.jadwal_cicilan.length}`);
}

// ==================================================== FASE 3: APPROVAL =====
const PHASE_ORDER = [
  'awaiting_pimpinan', 'awaiting_koordinator', 'awaiting_pengawas',
  'awaiting_koordinator_final', 'awaiting_pimpinan_final', 'completed',
];
const ROLE_FASE = {
  awaiting_pimpinan: 'pimpinan',
  awaiting_koordinator: 'koordinator',
  awaiting_pengawas: 'pengawas',
  awaiting_koordinator_final: 'koordinator',
  awaiting_pimpinan_final: 'pimpinan',
};

function fasePengajuan() {
  const pa = node('pengajuan_approval');
  for (const cabRaw of realKeys(pa)) {
    const cab = slugCabang(cabRaw);
    for (const gid of realKeys(pa[cabRaw])) {
      const g = pa[cabRaw][gid];
      if (!g || typeof g !== 'object') continue;
      const d = g.dualApprovalInfo || {};
      const adminUid = str(g.adminUid);
      const pid = str(g.pelangganId);
      const ke = int(g.pinjamanKe, 1);
      if (!adminUid || !pid) { issue('PENGAJUAN_TANPA_REF', `${cab}/${gid}`); continue; }

      const pengajuanId = ID.pengajuan(cab, gid);
      const besar = rupiah(g.besarPinjaman);
      ROWS.pengajuan.push({
        id: pengajuanId,
        pinjaman_id: ID.pinjaman(adminUid, pid, ke),
        cabang_id: cab,
        requires_dual: d.requiresDualApproval === true || besar >= 3000000,
        phase: PHASE_ORDER.includes(str(d.approvalPhase)) ? str(d.approvalPhase) : 'awaiting_pimpinan',
        final_decision: ['approved', 'rejected'].includes(str(d.finalDecision)) ? str(d.finalDecision) : null,
        final_decision_at: ts(d.finalDecisionTimestamp),
        rejection_reason: str(d.rejectionReason),
        diajukan_oleh: ID.user(adminUid),
        created_at: ts(g.timestamp) || null,
      });

      const stepDari = (fase, ia) => {
        if (!ia || typeof ia !== 'object') return;
        const st = str(ia.status);
        if (st !== 'approved' && st !== 'rejected') return; // 'pending' bukan keputusan
        const uid = str(ia.uid);
        if (!uid) { issue('APPROVAL_TANPA_UID', `${cab}/${gid}/${fase}`); return; }
        ROWS.approval_step.push({
          id: ID.step(cab, gid, fase), pengajuan_id: pengajuanId, phase: fase,
          status: st, approver_id: ID.user(uid), approver_role: ROLE_FASE[fase],
          note: str(ia.note),
          adjusted_amount: ia.adjustedAmount ? rupiah(ia.adjustedAmount) : null,
          adjusted_tenor: ia.adjustedTenor ? int(ia.adjustedTenor) : null,
          decided_at: ts(ia.timestamp),
        });
      };

      stepDari('awaiting_pimpinan', d.pimpinanApproval);
      stepDari('awaiting_pengawas', d.pengawasApproval);
      /* RTDB hanya menyimpan SATU koordinatorApproval untuk dua fase
       * (DualApprovalModels.kt:108). Keputusan fase 2 sudah tertimpa fase 4 di
       * sumbernya dan tidak dapat dipulihkan — dipetakan ke fase yang sesuai
       * dengan posisi terakhir pengajuan. Lihat 005 R-09. */
      const kf = str(d.approvalPhase);
      const faseKoor = PHASE_ORDER.indexOf(kf) >= PHASE_ORDER.indexOf('awaiting_koordinator_final')
        ? 'awaiting_koordinator_final' : 'awaiting_koordinator';
      stepDari(faseKoor, d.koordinatorApproval);
    }
  }
  log(`  pengajuan=${ROWS.pengajuan.length} approval_step=${ROWS.approval_step.length}`);
}

// ============================================= FASE 4: JURNAL & KASIR ======
function faseJurnalKasir() {
  const J = node('jurnal_transaksi');
  /* 'pelunasan_tabungan' ditambahkan setelah dry run pertama: 436 entri
   * ENUM_TIDAK_DIKENAL, seluruhnya tipe ini. Nilainya ikut ditambahkan ke
   * enum koperasi.jurnal_tipe lewat 001a_schema_patch.sql — kalau hanya
   * ditambah di sini, impor akan gagal saat cast ke enum. */
  const TIPE_OK = ['pembayaran_cicilan', 'tambah_bayar', 'pencairan_pinjaman',
    'pelunasan_sisa_utang', 'lunas', 'pelunasan_tabungan', 'tarik_tabungan'];
  for (const cabRaw of realKeys(J)) {
    const cab = slugCabang(cabRaw);
    for (const bulan of realKeys(J[cabRaw])) {
      for (const jid of realKeys(J[cabRaw][bulan])) {
        const e = J[cabRaw][bulan][jid];
        if (!e || typeof e !== 'object') continue;
        const tipe = enumOrNull(e.tipe, TIPE_OK, `jurnal/${cab}/${bulan}/${jid}`);
        if (!tipe) continue;
        const adminUid = str(e.adminUid);
        const pid = str(e.pelangganId);
        ROWS.jurnal_transaksi.push({
          id: ID.jurnal(cab, bulan, jid), cabang_id: cab, tipe,
          nasabah_id: adminUid && pid ? ID.nasabah(adminUid, pid) : null,
          pinjaman_id: adminUid && pid ? ID.pinjaman(adminUid, pid, int(e.pinjamanKe, 1)) : null,
          nama_pelanggan: str(e.namaPelanggan), nama_ktp: str(e.namaKtp),
          admin_id: adminUid ? ID.user(adminUid) : null, admin_name: str(e.adminName),
          jumlah: rupiah(e.jumlah),
          tanggal: parseTanggal(e.tanggal) || parseTanggal(e.createdAt) || `${bulan}-01`,
          pinjaman_ke: int(e.pinjamanKe, null),
          sisa_utang_setelah: e.sisaUtangSetelah != null ? rupiah(e.sisaUtangSetelah) : null,
          total_pelunasan: e.totalPelunasan != null ? rupiah(e.totalPelunasan) : null,
          total_dibayar: e.totalDibayar != null ? rupiah(e.totalDibayar) : null,
          keterangan: str(e.keterangan), created_at: ts(e.createdAt),
        });
      }
    }
  }

  const K = node('kasir_entries');
  for (const cabRaw of realKeys(K)) {
    const cab = slugCabang(cabRaw);
    for (const bulan of realKeys(K[cabRaw])) {
      for (const kid of realKeys(K[cabRaw][bulan])) {
        const e = K[cabRaw][bulan][kid];
        if (!e || typeof e !== 'object') continue;
        const by = str(e.createdBy);
        ROWS.kasir_entry.push({
          id: ID.kasir(cab, bulan, kid), cabang_id: cab,
          periode: /^\d{4}-\d{2}$/.test(bulan) ? `${bulan}-01` : null,
          tanggal: parseTanggal(e.tanggal) || (/^\d{4}-\d{2}$/.test(bulan) ? `${bulan}-01` : null),
          jenis: str(e.jenis),
          arah: enumOrNull(e.arah, ['masuk', 'keluar'], `kasir/${kid}`),
          nominal: rupiah(e.jumlah),
          keterangan: str(e.keterangan),
          target_admin_id: str(e.targetAdminUid) ? ID.user(e.targetAdminUid) : null,
          dicatat_oleh: by ? ID.user(by) : null,
          dicatat_oleh_nama: str(e.createdByName),
          client_op_id: ID.kasir(cab, bulan, kid),
          created_at: ts(e.createdAt),
        });
      }
    }
  }
  log(`  jurnal=${ROWS.jurnal_transaksi.length} kasir=${ROWS.kasir_entry.length}`);
}

// ======================================== FASE 5: DATA HISTORIS ===========
/* Keputusan pemilik 12 Agu 2026: ketiganya ikut pindah.
 * Bentuk diambil dari record nyata di data/firebase_sample.json. */
function faseHistoris() {
  // --- pinjamanHistory/{adminUid}/{pelangganId}/{pushId} -------------------
  const PH = node('pinjamanHistory');
  let phYatim = 0;
  for (const adminUid of realKeys(PH)) {
    for (const pid of realKeys(PH[adminUid])) {
      for (const hid of realKeys(PH[adminUid][pid])) {
        const h = PH[adminUid][pid][hid];
        if (!h || typeof h !== 'object') continue;
        /* FK ke nasabah: kalau nasabahnya sudah dihapus dari /pelanggan,
         * baris ini tidak punya induk dan akan ditolak FK. Dilewati dengan
         * laporan, BUKAN dipaksa masuk dengan nasabah_id palsu. */
        if (!nasabahAda.has(ID.nasabah(adminUid, pid))) { phYatim++; continue; }
        ROWS.pinjaman_history.push({
          id: ID.histori(adminUid, pid, hid),
          nasabah_id: ID.nasabah(adminUid, pid),
          berlaku_sampai: parseTanggal(h.berlakuSampai),
          besar_pinjaman: rupiah(h.besarPinjaman),
          legacy_push_id: hid,
          legacy_admin_uid: adminUid,
        });
      }
    }
  }
  if (phYatim) issue('PINJAMAN_HISTORY_YATIM', `${phYatim} entri tanpa nasabah induk — dilewati`);

  // --- biaya_awal/{adminUid}/{YYYY-MM-DD} ---------------------------------
  const BA = node('biaya_awal');
  for (const adminUid of realKeys(BA)) {
    for (const tgl of realKeys(BA[adminUid])) {
      const b = BA[adminUid][tgl];
      if (!b || typeof b !== 'object') continue;
      const tanggal = parseTanggal(b.tanggal) || parseTanggal(tgl);
      if (!tanggal) { issue('BIAYA_AWAL_TANPA_TANGGAL', `${adminUid}/${tgl}`); continue; }
      if (!userAda.has(ID.user(adminUid))) {
        issue('BIAYA_AWAL_ADMIN_TIDAK_DIKENAL', `${adminUid} — dilewati`);
        continue;
      }
      ROWS.biaya_awal.push({
        admin_id: ID.user(adminUid),
        tanggal,
        jumlah: rupiah(b.jumlah),
        recorded_at: ts(b.timestamp),
        legacy_admin_uid: adminUid,
      });
    }
  }

  // --- pelanggan_ditolak/{adminUid}/{pushId} ------------------------------
  const PD = node('pelanggan_ditolak');
  for (const adminUid of realKeys(PD)) {
    for (const rid of realKeys(PD[adminUid])) {
      const r = PD[adminUid][rid];
      if (!r || typeof r !== 'object') continue;
      const snap = (r.pelanggan && typeof r.pelanggan === 'object') ? r.pelanggan : {};
      const bersih = {};
      for (const k of realKeys(snap)) bersih[k] = snap[k]; // buang '...' & kunci guard
      const nik = str(snap.nik);
      const by = str(r.ditolakOleh);
      ROWS.pelanggan_ditolak.push({
        id: ID.ditolak(adminUid, rid),
        alasan_penolakan: str(r.alasanPenolakan),
        ditolak_oleh: by && userAda.has(ID.user(by)) ? ID.user(by) : null,
        tanggal_penolakan: parseTanggal(r.tanggalPenolakan),
        rejected_at: ts(r.timestamp),
        nama_ktp: str(snap.namaKtp),
        nama_panggilan: str(snap.namaPanggilan),
        nik: nikBersih(nik),
        besar_pinjaman: rupiah(snap.besarPinjaman),
        cabang_id: cabangAda.has(slugCabang(snap.cabangId)) ? slugCabang(snap.cabangId) : null,
        admin_id: userAda.has(ID.user(adminUid)) ? ID.user(adminUid) : null,
        // Snapshot disimpan UTUH sebagai bukti audit — lihat 001 §10b.3.
        snapshot: JSON.stringify(bersih),
        legacy_push_id: rid,
        legacy_admin_uid: adminUid,
      });
    }
  }

  // --- koreksi_storting/{cabangId}/{adminUid}/{YYYY-MM} -------------------
  const KS = node('koreksi_storting');
  for (const cabRaw of realKeys(KS)) {
    const cab = slugCabang(cabRaw);
    if (!cabangAda.has(cab)) { issue('KOREKSI_CABANG_TIDAK_DIKENAL', cab); continue; }
    for (const adminUid of realKeys(KS[cabRaw])) {
      if (!userAda.has(ID.user(adminUid))) {
        issue('KOREKSI_ADMIN_TIDAK_DIKENAL', `${cab}/${adminUid} — dilewati`);
        continue;
      }
      for (const bulan of realKeys(KS[cabRaw][adminUid])) {
        const k = KS[cabRaw][adminUid][bulan];
        if (!k || typeof k !== 'object') continue;
        if (!/^\d{4}-\d{2}$/.test(bulan)) {
          issue('KOREKSI_PERIODE_TIDAK_VALID', `${cab}/${adminUid}/${bulan}`);
          continue;
        }
        const by = str(k.updatedBy);
        ROWS.koreksi_storting.push({
          cabang_id: cab,
          admin_id: ID.user(adminUid),
          periode: `${bulan}-01`,
          cm: rupiah(k.cm), l1: rupiah(k.l1),
          mb: rupiah(k.mb), ml: rupiah(k.ml),
          updated_by: by && userAda.has(ID.user(by)) ? ID.user(by) : null,
          updated_at: ts(k.updatedAt),
          legacy_admin_uid: adminUid,
        });
      }
    }
  }

  // --- pelanggan_status_khusus/{cabangId}/{pelangganId} -------------------
  const SK = node('pelanggan_status_khusus');
  for (const cabRaw of realKeys(SK)) {
    const cab = slugCabang(cabRaw);
    if (!cabangAda.has(cab)) { issue('STATUS_KHUSUS_CABANG_TIDAK_DIKENAL', cab); continue; }
    for (const pid of realKeys(SK[cabRaw])) {
      const s = SK[cabRaw][pid];
      if (!s || typeof s !== 'object') continue;
      const adminUid = str(s.adminUid);
      /* nasabah_id nullable: yang ditandai bisa sudah dihapus dari
       * /pelanggan. Barisnya TETAP disimpan — ia membawa nama, no HP, dan
       * besar pinjaman pada saat penandaan, yang tidak bisa dipulihkan dari
       * tabel `nasabah` setelah datanya berubah. */
      const nid = adminUid ? ID.nasabah(adminUid, pid) : null;
      const bersih = {};
      for (const k of realKeys(s)) bersih[k] = s[k];
      ROWS.pelanggan_status_khusus.push({
        id: ID.statusKhusus(cab, pid),
        cabang_id: cab,
        nasabah_id: nid && nasabahAda.has(nid) ? nid : null,
        status_khusus: str(s.statusKhusus),
        catatan: str(s.catatanStatusKhusus),
        tanggal: parseTanggal(s.tanggalStatusKhusus),
        // Teks apa adanya: kadang nama, kadang email — lihat 001 §10b.5.
        diberi_tanda_oleh: str(s.diberiTandaOleh),
        admin_id: adminUid && userAda.has(ID.user(adminUid)) ? ID.user(adminUid) : null,
        admin_name: str(s.adminName),
        nama_ktp: str(s.namaKtp),
        nama_panggilan: str(s.namaPanggilan),
        no_hp: str(s.noHp),
        besar_pinjaman: rupiah(s.besarPinjaman),
        snapshot: JSON.stringify(bersih),
        legacy_pelanggan_id: pid,
        legacy_admin_uid: adminUid || null,
      });
    }
  }

  log(`  pinjaman_history=${ROWS.pinjaman_history.length} ` +
      `biaya_awal=${ROWS.biaya_awal.length} ` +
      `pelanggan_ditolak=${ROWS.pelanggan_ditolak.length} ` +
      `koreksi_storting=${ROWS.koreksi_storting.length} ` +
      `status_khusus=${ROWS.pelanggan_status_khusus.length}`);
}

// ================================================================ WRITE ===
function sqlInsert(table, rows, conflictCols) {
  const cols = [...new Set(rows.flatMap((r) => Object.keys(r)))]
    .filter((c) => !c.startsWith('_'));
  const text = (vals) =>
    `insert into koperasi.${table} (${cols.map((c) => `"${c}"`).join(',')}) values ${vals}` +
    ` on conflict (${conflictCols}) do nothing`;
  return { cols, text };
}

async function writeTable(client, table, rows, conflictCols) {
  if (!rows.length) return 0;
  const { cols, text } = sqlInsert(table, rows, conflictCols);
  let done = 0;
  for (let i = 0; i < rows.length; i += CFG.batch) {
    const chunk = rows.slice(i, i + CFG.batch);
    const params = [];
    const tuples = chunk.map((r) => {
      const ph = cols.map((c) => { params.push(r[c] === undefined ? null : r[c]); return `$${params.length}`; });
      return `(${ph.join(',')})`;
    }).join(',');
    await client.query(text(tuples), params);
    done += chunk.length;
    process.stdout.write(`\r    ${table}: ${done}/${rows.length}`);
  }
  process.stdout.write('\n');
  return done;
}

/**
 * Fase-3 dari penulisan cabang: mengisi pimpinan_id SETELAH app_user ada.
 *
 * FK antara cabang dan app_user MELINGKAR:
 *   app_user.cabang_id  → cabang.id
 *   cabang.pimpinan_id  → app_user.id
 * Jadi tidak ada urutan insert yang bisa memuaskan keduanya sekaligus.
 * Pemecahannya (sudah tertulis di 004 §8, tetapi belum diterapkan skrip ini
 * sampai kegagalan --execute pertama): cabang masuk dulu TANPA pimpinan,
 * app_user menyusul, baru pimpinan_id di-update.
 */
async function updatePimpinanCabang(client, rows) {
  const perlu = rows.filter((c) => c.pimpinan_id);
  if (!perlu.length) return 0;
  for (const c of perlu) {
    await client.query(
      'update koperasi.cabang set pimpinan_id = $1 where id = $2',
      [c.pimpinan_id, c.id]
    );
  }
  log(`    cabang.pimpinan_id: ${perlu.length}/${rows.length}`);
  return perlu.length;
}

/* =========================================================================
 * PRE-CHECK ENUM — gagal cepat SEBELUM menulis apa pun
 * =========================================================================
 * Kelas masalah yang berulang: satu nilai enum yang belum terdaftar membuat
 * seluruh impor 89 MB ter-rollback, dan barunya ketahuan SETELAH menulis
 * ribuan baris. Yang lebih buruk, kegagalannya datang satu per satu — sekali
 * jalan hanya menyingkap SATU nilai yang kurang, jadi perbaikannya jadi
 * siklus coba-gagal yang panjang.
 *
 * Pemeriksaan ini membalik urutannya: kumpulkan SELURUH nilai enum yang
 * benar-benar dihasilkan transformasi, bandingkan sekali dengan pg_enum, lalu
 * laporkan SEMUA yang kurang beserta SQL siap tempel. Nol baris ditulis.
 * ========================================================================= */
const ENUM_SPEC = [
  { rows: 'app_user',         kolom: 'role',                      tipe: 'user_role' },
  { rows: 'approval_step',    kolom: 'approver_role',              tipe: 'user_role' },
  { rows: 'pinjaman',         kolom: 'status',                     tipe: 'pinjaman_status' },
  { rows: 'pinjaman',         kolom: 'status_serah_terima',        tipe: 'status_serah_terima' },
  { rows: 'pinjaman',         kolom: 'status_pencairan_simpanan',  tipe: 'status_pencairan_simpanan' },
  { rows: 'pengajuan',        kolom: 'phase',                      tipe: 'approval_phase' },
  { rows: 'pengajuan',        kolom: 'final_decision',             tipe: 'approval_status' },
  { rows: 'approval_step',    kolom: 'phase',                      tipe: 'approval_phase' },
  { rows: 'approval_step',    kolom: 'status',                     tipe: 'approval_status' },
  { rows: 'jurnal_transaksi', kolom: 'tipe',                       tipe: 'jurnal_tipe' },
  { rows: 'pembayaran',       kolom: 'jenis',                      tipe: 'pembayaran_jenis' },
];

async function precheckEnum(client) {
  const { rows: labels } = await client.query(
    `select t.typname, e.enumlabel
       from pg_enum e
       join pg_type t on t.oid = e.enumtypid
       join pg_namespace n on n.oid = t.typnamespace
      where n.nspname = 'koperasi'`
  );

  const tersedia = new Map();
  for (const r of labels) {
    if (!tersedia.has(r.typname)) tersedia.set(r.typname, new Set());
    tersedia.get(r.typname).add(r.enumlabel);
  }

  const kurang = new Map();
  const tipeHilang = new Set();

  for (const spec of ENUM_SPEC) {
    const dipakai = new Set();
    for (const row of ROWS[spec.rows] || []) {
      const v = row[spec.kolom];
      if (v !== null && v !== undefined && v !== '') dipakai.add(String(v));
    }
    if (!dipakai.size) continue;
    if (!tersedia.has(spec.tipe)) { tipeHilang.add(spec.tipe); continue; }
    for (const v of dipakai) {
      if (!tersedia.get(spec.tipe).has(v)) {
        if (!kurang.has(spec.tipe)) kurang.set(spec.tipe, new Set());
        kurang.get(spec.tipe).add(v);
      }
    }
  }

  if (!kurang.size && !tipeHilang.size) {
    log(`  ✓ pre-check enum lulus (${ENUM_SPEC.length} kolom diperiksa)`);
    return true;
  }

  console.error('\n✗ PRE-CHECK ENUM GAGAL — tidak ada satu baris pun ditulis.\n');
  if (tipeHilang.size) {
    console.error('  Tipe enum tidak ditemukan di schema koperasi:');
    for (const t of tipeHilang) console.error(`    - koperasi.${t}`);
    console.error('  → 001_schema_v2.sql tampaknya belum dijalankan.\n');
  }
  if (kurang.size) {
    console.error('  Nilai yang dipakai data tetapi belum ada di enum:');
    for (const [tipe, nilai] of kurang) {
      for (const v of nilai) console.error(`    - koperasi.${tipe}.${v}`);
    }
    console.error('\n  Jalankan SQL berikut (di LUAR transaksi), lalu ulangi:');
    console.error('  ' + '-'.repeat(66));
    for (const [tipe, nilai] of kurang) {
      for (const v of nilai) {
        console.error(`  alter type koperasi.${tipe} add value if not exists '${v}';`);
      }
    }
    console.error('  ' + '-'.repeat(66));
    console.error('\n  Tambahkan juga ke 001a_schema_patch.sql agar instalasi');
    console.error('  berikutnya tidak mengulang kegagalan yang sama.');
  }
  return false;
}

/* =========================================================================
 * PRE-CHECK NIK KEMBAR — dijalankan pada hasil transformasi, sebelum menulis
 * =========================================================================
 * Berjalan juga pada mode dry-run, supaya daftarnya bisa diperiksa TANPA
 * menyentuh basis data sama sekali.
 *
 * Setelah nikBersih() mengubah seluruh isian placeholder menjadi NULL, NIK
 * yang masih kembar berarti dua nasabah BERBEDA memakai nomor KTP yang sama.
 * Itu bukan sesuatu yang boleh diputuskan skrip: bisa jadi satu orang
 * terdaftar dua kali (perlu digabung), bisa jadi salah ketik pada salah
 * satunya (perlu diperbaiki). Keduanya menghasilkan tindakan berbeda, jadi
 * prosesnya berhenti dan menyerahkan keputusan ke pemilik data.
 * ========================================================================= */
function precheckNikKembar() {
  const peta = new Map();
  for (const n of ROWS.nasabah) {
    if (!n.nik) continue;                       // NULL tidak pernah bentrok
    if (!peta.has(n.nik)) peta.set(n.nik, []);
    peta.get(n.nik).push({
      nik: n.nik,
      namaKtp: n.nama_ktp,
      namaPanggilan: n.nama_panggilan,
      legacyPelangganId: n.legacy_pelanggan_id,
      legacyAdminUid: n.legacy_admin_uid,
      adminName: namaAdmin.get(n.legacy_admin_uid) || '(admin tidak dikenal)',
      cabangId: n.cabang_id || '',
    });
  }

  const kembar = [...peta.values()].filter((v) => v.length > 1);
  if (!kembar.length) {
    log(`  ✓ pre-check NIK lulus (${peta.size} NIK unik, sisanya NULL)`);
    return { kembar: [], baris: [] };
  }

  const baris = kembar.flat();
  log('');
  log('╔' + '═'.repeat(70) + '╗');
  log(`  ⛔ ${kembar.length} NIK dipakai lebih dari satu nasabah (${baris.length} baris)`);
  log('╚' + '═'.repeat(70) + '╝');
  for (const grup of kembar) {
    log(`  NIK ${grup[0].nik}  — ${grup.length} nasabah:`);
    for (const r of grup) {
      log(`     • ${(r.namaKtp || '(tanpa nama)').padEnd(30)} ` +
          `${(r.namaPanggilan || '-').padEnd(14)} ` +
          `admin=${r.adminName}`);
      log(`       pelangganId=${r.legacyPelangganId}  cabang=${r.cabangId || '-'}`);
    }
  }
  log('');
  log('  Ini NIK yang LOLOS normalisasi — bukan isian dummy, melainkan nomor');
  log('  16 digit sah yang terpakai berulang. Pola yang terlihat di dry run:');
  log('  satu orang terdaftar di DUA resort berbeda. Itu keadaan data nyata');
  log('  di Firebase, bukan cacat impor.');
  log('');
  log('  KEPUTUSAN PEMILIK (12 Agu 2026): impor DILANJUTKAN. Constraint UNIQUE');
  log('  pada nasabah.nik diturunkan menjadi index biasa supaya seluruh baris');
  log('  terbawa utuh; penggabungan dikerjakan belakangan sebagai cleanup.');
  log('  Daftar di bawah tersimpan di laporan → nikPerluCleanup.');
  log('');
  log('  ⚠ Konsekuensi yang harus disadari: selama duplikat ini ada, satu');
  log('    orang punya DUA baris nasabah dengan riwayat pinjaman terpisah.');
  log('    Laporan "jumlah nasabah" akan menghitungnya dua kali.');
  log('');
  return { kembar, baris };
}

// ================================================================= MAIN ===
(async () => {
  const FASE = [
    ['user', faseUser],
    ['nasabah', faseNasabahPinjaman],
    ['pengajuan', fasePengajuan],
    ['jurnal', faseJurnalKasir],
    ['historis', faseHistoris],   // butuh userAda/cabangAda/nasabahAda terisi
  ];
  log('\n▶ Transformasi');
  for (const [nama, fn] of FASE) {
    if (CFG.only && !CFG.only.includes(nama)) { log(`  (lewati ${nama})`); continue; }
    log(` fase ${nama}:`);
    fn();
  }

  log('\n▶ Pre-check NIK kembar');
  const nikCek = precheckNikKembar();

  const summary = Object.fromEntries(Object.entries(ROWS).map(([k, v]) => [k, v.length]));
  const kindCount = ISSUES.reduce((m, i) => ((m[i.kind] = (m[i.kind] || 0) + 1), m), {});
  const report = {
    generatedAt: new Date().toISOString(),
    sourceFile: CFG.file, executed: CFG.execute,
    rows: summary, issueCounts: kindCount, issues: ISSUES.slice(0, 2000),

    /* Kebijakan yang MENGUBAH data, ditulis eksplisit di laporan supaya
     * keputusannya ikut terarsip bersama hasilnya — bukan hanya hidup di
     * kepala orang yang menjalankan skrip. */
    kebijakan: {
      pewarisanCabang:
        'Nasabah tanpa cabangId mewarisi cabang_id dari admin pemiliknya ' +
        '(app_user.cabang_id). Dasarnya: satu admin lapangan bekerja di satu ' +
        'cabang, jadi cabangId kosong berarti field tidak terisi, bukan ' +
        'nasabah tanpa cabang. Bila admin juga tanpa cabang, nasabah TIDAK ' +
        'ditebak dan masuk daftar perluCabang.',
      pembayaranTanpaJumlah:
        'Entri pembayaran tanpa jumlah yang sah DILEWATI (dianggap sampah).',
      riwayatYatim:
        'Entri pinjaman_history yang nasabah induknya tidak ada DILEWATI.',
    },

    /* Jejak audit pewarisan cabang: siapa mewarisi apa, dari admin mana. */
    pimpinanYatim: PIMPINAN_YATIM,
    /* NIK yang dipakai lebih dari satu nasabah SETELAH placeholder
     * dinormalkan jadi NULL. TIDAK menghentikan impor (UNIQUE sudah
     * diturunkan jadi index biasa) — ini daftar kerja cleanup. */
    nikPerluCleanup: {
      catatan:
        'Satu orang terdaftar di lebih dari satu resort. Semua baris tetap ' +
        'diimpor utuh. Gabungkan belakangan: pilih satu nasabah_id sebagai ' +
        'induk, pindahkan pinjaman/pembayaran ke sana, lalu arsipkan sisanya ' +
        'lewat koperasi.rpc_arsipkan_nasabah (007).',
      jumlahNik: nikCek.kembar.length,
      jumlahBaris: nikCek.baris.length,
      grup: nikCek.kembar,
    },
    cabangDiwarisi: CABANG_DIWARISI.sort(
      (x, y) => (x.adminName + x.namaKtp).localeCompare(y.adminName + y.namaKtp)
    ),
    /* Daftar kerja untuk pemilik: nasabah yang perlu ditentukan cabangnya
     * sebelum impor. Diurutkan per admin supaya bisa ditanyakan sekaligus. */
    perluCabang: PERLU_CABANG.sort(
      (x, y) => (x.adminName + x.namaKtp).localeCompare(y.adminName + y.namaKtp)
    ),
  };
  fs.writeFileSync(CFG.report, JSON.stringify(report, null, 2));

  log('\n▶ Ringkasan baris');
  for (const [k, v] of Object.entries(summary)) log(`   ${k.padEnd(18)} ${v}`);
  if (ISSUES.length) {
    log('\n▶ Anomali (rincian di ' + CFG.report + ')');
    for (const [k, v] of Object.entries(kindCount)) log(`   ${k.padEnd(30)} ${v}`);
  }

  if (PIMPINAN_YATIM.length) {
    log(`\n▶ ${PIMPINAN_YATIM.length} cabang dengan pimpinanUid tidak terdaftar`);
    for (const r of PIMPINAN_YATIM) log(`   ${r.cabangId.padEnd(26)} ${r.pimpinanUid}`);
    log('   → pimpinan_id dibiarkan NULL; isi manual sesudah impor.');
    log('   ⚠ RLS pimpinan untuk cabang ini TIDAK akan berfungsi sampai diisi.');
  }

  if (CABANG_DIWARISI.length) {
    log(`\n▶ ${CABANG_DIWARISI.length} nasabah mewarisi cabang dari adminnya`);
    const perCabang = CABANG_DIWARISI.reduce(
      (m, r) => ((m[r.cabangDiwarisi] = (m[r.cabangDiwarisi] || 0) + 1), m), {}
    );
    for (const [cab, n] of Object.entries(perCabang)) log(`   ${cab.padEnd(28)} ${n}`);
    log('   (rincian per nasabah di ' + CFG.report + ' → cabangDiwarisi)');
  }

  if (PERLU_CABANG.length) {
    log(`\n▶ ${PERLU_CABANG.length} nasabah TANPA cabang DAN adminnya juga tanpa cabang`);
    log('   → tidak bisa diwarisi; tentukan cabang tujuannya manual');
    log('   (daftar lengkap di ' + CFG.report + ' → perluCabang)');
    let adminSekarang = null;
    for (const r of report.perluCabang) {
      if (r.adminName !== adminSekarang) {
        adminSekarang = r.adminName;
        log(`   ── ${adminSekarang}`);
      }
      log(`      ${r.namaKtp || '(tanpa nama)'}`.padEnd(38) +
          `${r.namaPanggilan || '-'}`.padEnd(16) +
          `wilayah=${r.wilayah || '-'}`);
    }
  }

  /* Dulu gerbang keras (exit 6). Diturunkan menjadi PERINGATAN setelah
   * constraint UNIQUE pada nasabah.nik diganti index biasa (001a §9):
   * duplikat tidak lagi menggagalkan impor, jadi menghentikan proses hanya
   * akan menahan migrasi tanpa memperbaiki apa pun. Daftarnya tetap dicetak
   * dan tersimpan supaya cleanup-nya tidak terlupakan. */
  if (CFG.execute && nikCek.kembar.length) {
    log(`⚠ Melanjutkan dengan ${nikCek.kembar.length} NIK duplikat ` +
        `(${nikCek.baris.length} baris) — perlu cleanup manual nanti.`);
    log(`  Daftar lengkap: ${CFG.report} → nikPerluCleanup`);
  }

  if (!CFG.execute) {
    log('\n✓ DRY-RUN selesai. Tidak ada yang ditulis ke database.');
    log('  Tambahkan --execute (beserta --dsn) untuk menulis.');
    return;
  }

  try { ({ Client } = require('pg')); }
  catch { console.error('FATAL: paket `pg` belum terpasang. Jalankan: npm i pg'); process.exit(4); }

  const client = new Client({ connectionString: CFG.dsn });
  await client.connect();

  // Gerbang: seluruh nilai enum diperiksa SEBELUM transaksi dibuka.
  log('\n▶ Pre-check enum');
  if (!(await precheckEnum(client))) {
    await client.end();
    process.exitCode = 5;
    return;
  }

  log('\n▶ Menulis ke Postgres (satu transaksi)');
  try {
    await client.query('begin');
    // Trigger urutan generasi & anti-downgrade dimatikan SELAMA impor —
    // data historis wajar tidak monoton. Lihat 006 §5; wajib diaktifkan lagi.
    await client.query('alter table koperasi.pinjaman disable trigger pinjaman_generasi_berurutan');
    await client.query('alter table koperasi.pinjaman disable trigger pinjaman_no_downgrade');
    await client.query('alter table koperasi.approval_step disable trigger approval_urutan');
    await client.query('alter table koperasi.approval_step disable trigger approval_advance');

    /* Urutan WAJIB (lihat updatePimpinanCabang untuk alasannya):
     *   1. cabang TANPA pimpinan_id
     *   2. app_user  (butuh cabang.id sudah ada)
     *   3. update cabang.pimpinan_id  (butuh app_user.id sudah ada)
     * Menulis cabang lengkap di langkah 1 adalah penyebab kegagalan
     * "violates foreign key constraint cabang_pimpinan_fk". */
    const cabangTanpaPimpinan = ROWS.cabang.map((c) => {
      const { pimpinan_id, ...sisa } = c;
      return sisa;
    });
    await writeTable(client, 'cabang', cabangTanpaPimpinan, 'id');
    await writeTable(client, 'app_user', ROWS.app_user, 'id');
    await updatePimpinanCabang(client, ROWS.cabang);
    await writeTable(client, 'nasabah', ROWS.nasabah, 'id');
    await writeTable(client, 'pinjaman', ROWS.pinjaman, 'id');
    await writeTable(client, 'pembayaran', ROWS.pembayaran, 'id');
    await writeTable(client, 'jadwal_cicilan', ROWS.jadwal_cicilan, 'pinjaman_id,urutan');
    await writeTable(client, 'pengajuan', ROWS.pengajuan, 'id');
    await writeTable(client, 'approval_step', ROWS.approval_step, 'id');
    await writeTable(client, 'jurnal_transaksi', ROWS.jurnal_transaksi, 'id');
    await writeTable(client, 'kasir_entry', ROWS.kasir_entry, 'id');
    await writeTable(client, 'pinjaman_history', ROWS.pinjaman_history, 'id');
    await writeTable(client, 'biaya_awal', ROWS.biaya_awal, 'admin_id,tanggal');
    await writeTable(client, 'pelanggan_ditolak', ROWS.pelanggan_ditolak, 'id');
    await writeTable(client, 'koreksi_storting', ROWS.koreksi_storting, 'cabang_id,admin_id,periode');
    await writeTable(client, 'pelanggan_status_khusus', ROWS.pelanggan_status_khusus, 'id');

    await client.query('alter table koperasi.approval_step enable trigger approval_advance');
    await client.query('alter table koperasi.approval_step enable trigger approval_urutan');
    await client.query('alter table koperasi.pinjaman enable trigger pinjaman_no_downgrade');
    await client.query('alter table koperasi.pinjaman enable trigger pinjaman_generasi_berurutan');
    await client.query('commit');
    fs.writeFileSync(CFG.checkpoint, JSON.stringify({ done: true, at: new Date().toISOString(), rows: summary }, null, 2));
    log('\n✓ COMMIT. Jalankan validate.js sekarang.');
  } catch (e) {
    await client.query('rollback').catch(() => {});
    console.error('\n✗ ROLLBACK — tidak ada data yang tersimpan.\n', e.message);
    process.exitCode = 1;
  } finally {
    await client.end();
  }
})();
