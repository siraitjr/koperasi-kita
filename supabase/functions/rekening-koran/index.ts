// =========================================================================
// EDGE FUNCTION: rekening-koran
// Pengganti getRekeningKoran (functions/rekeningKoranService.js), Tahap B-5.
// =========================================================================
// BELUM PERNAH DI-DEPLOY / DIJALANKAN.
//
// ⚠ SATU-SATUNYA ENDPOINT YANG TEREKSPOS TANPA LOGIN.
// Tidak ada `auth.uid()`, jadi tidak ada RLS yang bisa menolong. Yang
// menggantikan seluruh model izin di sini hanyalah tanda tangan HMAC dan
// masa berlaku. Setiap kelalaian di berkas ini langsung berarti riwayat
// pembayaran nasabah bisa dibaca orang asing.
//
// Deploy & prosedur rotasi kunci: docs/migration/supabase/020_rekening_koran.md
//
// -------------------------------------------------------------------------
// DUA VERSI TOKEN, DAN KENAPA KEDUANYA ADA
// -------------------------------------------------------------------------
// v1 (warisan) : base64url("adminUid:pelangganId:timestamp:sig16")
//   Dibuat Android (RekeningKoranHelper.kt:46-64). Kuncinya HARDCODED di
//   APK dan di repo, jadi tidak bisa diganti tanpa rilis Android baru.
//   Aslinya TANPA masa berlaku sama sekali — rekeningKoranService.js:59
//   berkomentar "(tidak ada expiry agar link permanen)".
//
//   Di sini `timestamp` yang sudah ada di token itu AKHIRNYA DIPAKAI:
//   masa berlakunya dihitung server-side dari situ. Inilah cara memenuhi
//   syarat "wajib expiry" TANPA menyentuh app/.
//
// v2 (baru)    : base64url("v2:adminUid:pelangganId:exp:sigHexPenuh")
//   `exp` epoch detik ada DI DALAM yang ditandatangani, tanda tangannya
//   utuh 64 hex (bukan dipotong 16), kuncinya dari env.
//   Android memakainya di rilis mendatang — di luar lingkup B-5.
//
// -------------------------------------------------------------------------
// YANG HARUS JUJUR DISEBUT TENTANG v1
// -------------------------------------------------------------------------
// Kunci v1 ADA DI RIWAYAT GIT sejak e570701 (13 Apr 2026), di dua berkas
// sekaligus. Artinya siapa pun yang pernah melihat repo ini bisa MENEMPA
// token v1 untuk nasabah mana pun, dengan timestamp baru.
//
// Konsekuensinya, dan ini tidak boleh disalahpahami:
//   TTL pada v1 TIDAK menghentikan pemalsuan. Ia hanya mematikan tautan
//   lama yang bocor. Penempa cukup memakai timestamp hari ini.
//
// Satu-satunya penutup sesungguhnya adalah MEMENSIUNKAN v1 — karena itu
// ada REKENING_KORAN_V1_UNTIL, dan karena itu ia wajib diisi.
// =========================================================================

import { createClient } from 'jsr:@supabase/supabase-js@2';

const SCHEMA = 'koperasi';

// -------------------------------------------------------------------------
// ENV — dibaca sekali, tanpa satu pun nilai cadangan di kode
// -------------------------------------------------------------------------
// rekeningKoranService.js:31 memakai `functions.config().rk?.secret || '<kunci>'`.
// Pola `||` itulah cacatnya: kunci bawaan tetap hidup di kode walau config
// sudah diisi, dan kegagalan konfigurasi tidak pernah terlihat karena selalu
// ada yang menampung. Di sini TIDAK ADA `||`. Kalau env kosong, fungsi mati.
const KEY_PRIMARY = Deno.env.get('REKENING_KORAN_HMAC_KEY') ?? '';
const KEY_OLD     = Deno.env.get('REKENING_KORAN_HMAC_KEY_OLD') ?? '';   // grace window
const KEY_V1      = Deno.env.get('REKENING_KORAN_V1_KEY') ?? '';        // warisan
const V1_UNTIL    = Deno.env.get('REKENING_KORAN_V1_UNTIL') ?? '';      // ISO, wajib bila KEY_V1 diisi
const V1_TTL_DAYS = Number(Deno.env.get('REKENING_KORAN_V1_TTL_DAYS') ?? '30');
const MASK_NIK    = (Deno.env.get('REKENING_KORAN_MASK_NIK') ?? 'true') !== 'false';

const SUPABASE_URL = Deno.env.get('SUPABASE_URL') ?? '';
const SERVICE_KEY  = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '';

// Halaman publik dibuka dari mana saja (tautan disebar lewat WhatsApp), jadi
// '*' memang tepat DI SINI — respons ini bukan kredensial, dan tidak ada
// cookie/Authorization yang bisa disalahgunakan lintas situs.
const CORS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  // Halaman ini memuat data pribadi; jangan pernah disimpan perantara.
  'Cache-Control': 'no-store',
};

function jawab(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, 'Content-Type': 'application/json' },
  });
}

// -------------------------------------------------------------------------
// Kripto
// -------------------------------------------------------------------------
const enc = new TextEncoder();

async function hmacHex(kunci: string, pesan: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(kunci), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(pesan));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

// Perbandingan waktu-tetap. `a !== b` biasa (rekeningKoranService.js:56)
// berhenti di byte pertama yang berbeda, sehingga lama pemrosesan
// membocorkan berapa banyak awalan yang sudah benar. Dengan tanda tangan v1
// yang cuma 16 hex (64 bit), oracle semacam itu bukan sekadar teori.
function samaWaktuTetap(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let beda = 0;
  for (let i = 0; i < a.length; i++) beda |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return beda === 0;
}

function b64urlDecode(s: string): string {
  const pad = s.replace(/-/g, '+').replace(/_/g, '/');
  return new TextDecoder().decode(
    Uint8Array.from(atob(pad + '='.repeat((4 - (pad.length % 4)) % 4)), (c) => c.charCodeAt(0)),
  );
}

type Hasil =
  | { ok: true; adminUid: string; pelangganId: string; versi: 'v1' | 'v2' }
  | { ok: false; status: number; error: string };

// -------------------------------------------------------------------------
// v2 — exp ikut ditandatangani
// -------------------------------------------------------------------------
async function verifikasiV2(bagian: string[]): Promise<Hasil> {
  // ["v2", adminUid, pelangganId, exp, sig]
  if (bagian.length !== 5) return { ok: false, status: 400, error: 'Format tautan tidak dikenali' };
  const [, adminUid, pelangganId, expStr, sig] = bagian;

  const exp = Number(expStr);
  if (!Number.isFinite(exp)) {
    return { ok: false, status: 400, error: 'Format tautan tidak dikenali' };
  }

  const payload = `v2:${adminUid}:${pelangganId}:${expStr}`;
  // Kunci lama diterima SELAMA masa transisi. Keduanya selalu dihitung
  // sebelum diputuskan, supaya lama pemrosesan tidak berbeda antara
  // "cocok kunci baru" dan "cocok kunci lama".
  const cocokBaru = KEY_PRIMARY ? samaWaktuTetap(sig, await hmacHex(KEY_PRIMARY, payload)) : false;
  const cocokLama = KEY_OLD     ? samaWaktuTetap(sig, await hmacHex(KEY_OLD, payload))     : false;
  if (!cocokBaru && !cocokLama) {
    return { ok: false, status: 401, error: 'Tautan tidak sah' };
  }

  // Kedaluwarsa DIPERIKSA SESUDAH tanda tangan. Urutan ini disengaja:
  // menjawab "kedaluwarsa" untuk token yang tanda tangannya palsu akan
  // memberi tahu penempa bahwa tanda tangannya sebenarnya sudah benar.
  if (Math.floor(Date.now() / 1000) > exp) {
    return { ok: false, status: 410, error: 'Tautan sudah kedaluwarsa. Minta tautan baru ke admin.' };
  }
  return { ok: true, adminUid, pelangganId, versi: 'v2' };
}

// -------------------------------------------------------------------------
// v1 — masa berlaku dihitung dari timestamp yang SUDAH ada di token
// -------------------------------------------------------------------------
async function verifikasiV1(bagian: string[]): Promise<Hasil> {
  // [adminUid, pelangganId, timestampMs, sig16]
  if (bagian.length !== 4) return { ok: false, status: 400, error: 'Format tautan tidak dikenali' };
  if (!KEY_V1) return { ok: false, status: 401, error: 'Tautan tidak sah' };

  // Pensiun keras. Sesudah tanggal ini v1 ditolak apa pun isinya — inilah
  // yang benar-benar menutup pemalsuan, bukan TTL di bawah.
  if (!V1_UNTIL || new Date() > new Date(V1_UNTIL)) {
    return {
      ok: false,
      status: 410,
      error: 'Tautan versi lama sudah tidak berlaku. Minta tautan baru ke admin.',
    };
  }

  const [adminUid, pelangganId, tsStr, sig] = bagian;
  const payload = `${adminUid}:${pelangganId}:${tsStr}`;
  const penuh = await hmacHex(KEY_V1, payload);
  // Android memotong 16 hex pertama (RekeningKoranHelper.kt:77) — ditiru
  // apa adanya supaya APK yang beredar tetap jalan selama masa transisi.
  if (!samaWaktuTetap(sig, penuh.substring(0, 16))) {
    return { ok: false, status: 401, error: 'Tautan tidak sah' };
  }

  const ts = Number(tsStr);
  if (!Number.isFinite(ts)) return { ok: false, status: 400, error: 'Format tautan tidak dikenali' };
  const umurHari = (Date.now() - ts) / 86_400_000;
  // Termasuk timestamp MASA DEPAN: token bertanggal besok adalah tanda
  // jam yang salah atau tangan yang mengarang, keduanya bukan tautan sah.
  if (umurHari > V1_TTL_DAYS || umurHari < -1) {
    return { ok: false, status: 410, error: 'Tautan sudah kedaluwarsa. Minta tautan baru ke admin.' };
  }
  return { ok: true, adminUid, pelangganId, versi: 'v1' };
}

// -------------------------------------------------------------------------
// Handler
// -------------------------------------------------------------------------
Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
  if (req.method !== 'GET') return jawab(405, { success: false, error: 'Metode harus GET' });

  // Gagal-tutup. Tanpa kunci, satu-satunya perilaku yang benar adalah mati —
  // BUKAN jatuh ke kunci bawaan seperti perilaku lama.
  if (!KEY_PRIMARY || !SUPABASE_URL || !SERVICE_KEY) {
    console.error('rekening-koran: env belum lengkap; menolak semua permintaan');
    return jawab(503, { success: false, error: 'Layanan belum siap' });
  }

  try {
    const token = new URL(req.url).searchParams.get('t');
    if (!token) return jawab(400, { success: false, error: 'Tautan tidak lengkap' });

    let bagian: string[];
    try {
      bagian = b64urlDecode(token).split(':');
    } catch {
      return jawab(400, { success: false, error: 'Format tautan tidak dikenali' });
    }

    const hasil = bagian[0] === 'v2' ? await verifikasiV2(bagian) : await verifikasiV1(bagian);
    if (!hasil.ok) return jawab(hasil.status, { success: false, error: hasil.error });

    const db = createClient(SUPABASE_URL, SERVICE_KEY, { db: { schema: SCHEMA } });
    const { data, error } = await db.rpc('rpc_rekening_koran', {
      p_legacy_admin_uid: hasil.adminUid,
      p_legacy_pelanggan_id: hasil.pelangganId,
      p_mask_nik: MASK_NIK,
    });

    if (error) {
      // Pesan galat database tidak pernah diteruskan ke halaman publik:
      // isinya nama tabel/kolom, dan itu peta gratis buat penyerang.
      console.error('rpc_rekening_koran gagal:', error.message);
      return jawab(500, { success: false, error: 'Terjadi kesalahan' });
    }
    if (!data) return jawab(404, { success: false, error: 'Data tidak ditemukan' });

    console.log(`rekening-koran ok versi=${hasil.versi}`);
    return jawab(200, { success: true, data });
  } catch (e) {
    console.error('rekening-koran:', e instanceof Error ? e.message : e);
    return jawab(500, { success: false, error: 'Terjadi kesalahan' });
  }
});
