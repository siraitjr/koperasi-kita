// =========================================================================
// EDGE FUNCTION: rekening-koran-link
// Pembuat tautan rekening koran v2 — DITANDATANGANI DI SISI SERVER.
// =========================================================================
// BELUM PERNAH DI-DEPLOY / DIJALANKAN.
//
// KENAPA ADA
// -------------------------------------------------------------------------
// Sampai sekarang tautan dibuat DI ANDROID (RekeningKoranHelper.kt:46-64)
// dengan kunci HMAC yang tertanam di APK. Dua akibatnya, dan keduanya fatal
// pada premis evakuasi:
//
//   1. Kunci ada di APK → ada di riwayat git → tautan bisa ditempa siapa pun.
//   2. Host tautannya `koperasikitagodangulu.web.app` (RekeningKoranHelper.kt:37)
//      HARDCODED. Host itu Firebase Hosting, yang ikut mati pada cutoff.
//      Artinya seluruh tautan buatan APK lama berhenti bekerja — bukan
//      karena kuncinya, melainkan karena halamannya tidak ada lagi.
//
// Fungsi ini memindahkan pembuatan tautan ke server, sehingga:
//   * kunci TIDAK PERNAH meninggalkan server — tidak di APK, tidak di bundel
//     web, tidak di localStorage;
//   * host tautan diatur satu env, bisa diganti tanpa rilis apa pun;
//   * staf web bisa membuat tautan hari ini juga, tanpa menunggu Android v2.
//
// Ini yang memutus ketergantungan pada APK lama (021 revisi §2).
//
// BEDA DENGAN `rekening-koran`
//   rekening-koran       : publik, TANPA login, MEMVERIFIKASI tautan.
//   rekening-koran-link  : WAJIB login staf, MEMBUAT tautan.
// Keduanya memakai kunci yang sama tetapi arah berlawanan, dan hanya yang
// pertama boleh terbuka ke publik.
// =========================================================================

import { createClient } from 'jsr:@supabase/supabase-js@2';

const SCHEMA = 'koperasi';

const KEY_PRIMARY  = Deno.env.get('REKENING_KORAN_HMAC_KEY') ?? '';
const BASE_URL     = Deno.env.get('REKENING_KORAN_BASE_URL') ?? '';   // host halaman rk
const TTL_DAYS     = Number(Deno.env.get('REKENING_KORAN_LINK_TTL_DAYS') ?? '30');
const SUPABASE_URL = Deno.env.get('SUPABASE_URL') ?? '';
const ANON_KEY     = Deno.env.get('SUPABASE_ANON_KEY') ?? '';
const SERVICE_KEY  = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '';

// Daftar putih, bukan '*': respons fungsi ini adalah tautan bertanda tangan.
// Memberi '*' berarti situs mana pun yang dibuka staf bisa memintanya diam-diam.
const ALLOWED_ORIGINS = [
  'https://www.koperasi-kita.com',
  'https://koperasi-kita.com',
];

function corsHeaders(origin: string | null): Record<string, string> {
  const asal = origin && ALLOWED_ORIGINS.includes(origin) ? origin : ALLOWED_ORIGINS[0];
  return {
    'Access-Control-Allow-Origin': asal,
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type, apikey',
    'Vary': 'Origin',
    'Cache-Control': 'no-store',
  };
}

const jawab = (status: number, body: unknown, origin: string | null) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders(origin), 'Content-Type': 'application/json' },
  });

const enc = new TextEncoder();
async function hmacHex(kunci: string, pesan: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw', enc.encode(kunci), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  );
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(pesan));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
function b64url(s: string): string {
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// Peran yang boleh membuat tautan. Cermin KASIR_ALLOWED_ROLES
// (kasirApi.js:188) DITAMBAH 'admin': admin lapangan justru yang paling
// sering membagikan rekening koran ke nasabahnya — itu memang alur aslinya
// di Android.
const PERAN_BOLEH = new Set([
  'admin', 'kasir_unit', 'kasir_wilayah', 'sekretaris',
  'pimpinan', 'koordinator', 'pengawas',
]);

Deno.serve(async (req) => {
  const origin = req.headers.get('Origin');
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: corsHeaders(origin) });
  if (req.method !== 'POST') return jawab(405, { success: false, error: 'Metode harus POST' }, origin);

  // Gagal-tutup. Tanpa kunci atau tanpa host, tautan yang dihasilkan akan
  // salah secara diam-diam — dan tautan salah baru ketahuan saat nasabah
  // mengeluh, berhari-hari kemudian.
  if (!KEY_PRIMARY || !BASE_URL || !SUPABASE_URL || !SERVICE_KEY || !ANON_KEY) {
    console.error('rekening-koran-link: env belum lengkap');
    return jawab(503, { success: false, error: 'Layanan belum siap' }, origin);
  }

  try {
    const authz = req.headers.get('Authorization') ?? '';
    if (!authz.startsWith('Bearer ')) {
      return jawab(401, { success: false, error: 'Tidak ada sesi' }, origin);
    }

    // Identitas diverifikasi Supabase, bukan dipercaya dari badan permintaan.
    const asUser = createClient(SUPABASE_URL, ANON_KEY, {
      global: { headers: { Authorization: authz } },
    });
    const { data: uinfo, error: uerr } = await asUser.auth.getUser();
    if (uerr || !uinfo?.user) {
      return jawab(401, { success: false, error: 'Sesi tidak sah' }, origin);
    }

    const db = createClient(SUPABASE_URL, SERVICE_KEY, { db: { schema: SCHEMA } });

    const { data: pemanggil, error: perr } = await db
      .from('app_user')
      .select('id, role, cabang_id, aktif')
      .eq('id', uinfo.user.id)
      .maybeSingle();
    if (perr) {
      console.error('baca app_user gagal:', perr.message);
      return jawab(500, { success: false, error: 'Terjadi kesalahan' }, origin);
    }
    if (!pemanggil || !pemanggil.aktif || !PERAN_BOLEH.has(pemanggil.role)) {
      return jawab(403, { success: false, error: 'Tidak berwenang membuat tautan' }, origin);
    }

    const body = await req.json().catch(() => ({}));
    const nasabahId = String(body?.nasabahId ?? '');
    if (!nasabahId) {
      return jawab(400, { success: false, error: 'nasabahId wajib' }, origin);
    }

    // Nasabahnya harus ada DAN boleh dilihat pemanggil. Tanpa pemeriksaan
    // ini, admin cabang A bisa membuat tautan untuk nasabah cabang B —
    // memindahkan kebocoran dari "menempa token" ke "meminta token", yang
    // sama saja buruknya.
    const { data: nsb, error: nerr } = await db
      .from('nasabah')
      .select('id, cabang_id, admin_id, legacy_admin_uid, legacy_pelanggan_id, arsip_at')
      .eq('id', nasabahId)
      .maybeSingle();
    if (nerr) {
      console.error('baca nasabah gagal:', nerr.message);
      return jawab(500, { success: false, error: 'Terjadi kesalahan' }, origin);
    }
    if (!nsb || nsb.arsip_at) {
      return jawab(404, { success: false, error: 'Nasabah tidak ditemukan' }, origin);
    }

    const global = pemanggil.role === 'pengawas' || pemanggil.role === 'koordinator';
    const boleh = global
      || nsb.admin_id === pemanggil.id
      || (pemanggil.cabang_id !== null && nsb.cabang_id === pemanggil.cabang_id);
    if (!boleh) {
      return jawab(403, { success: false, error: 'Nasabah di luar wewenang Anda' }, origin);
    }

    if (!nsb.legacy_admin_uid || !nsb.legacy_pelanggan_id) {
      // rpc_rekening_koran mencari lewat pasangan id warisan (020a). Nasabah
      // yang lahir SESUDAH migrasi belum tentu punya keduanya.
      return jawab(409, {
        success: false,
        error: 'Nasabah ini belum punya id warisan; tautan belum dapat dibuat',
      }, origin);
    }

    const exp = Math.floor(Date.now() / 1000) + TTL_DAYS * 86_400;
    const payload = `v2:${nsb.legacy_admin_uid}:${nsb.legacy_pelanggan_id}:${exp}`;
    const token = b64url(`${payload}:${await hmacHex(KEY_PRIMARY, payload)}`);

    console.log(`rekening-koran-link dibuat oleh=${pemanggil.id} nasabah=${nsb.id}`);
    return jawab(200, {
      success: true,
      data: {
        url: `${BASE_URL}?t=${encodeURIComponent(token)}`,
        expiresAt: new Date(exp * 1000).toISOString(),
        ttlDays: TTL_DAYS,
      },
    }, origin);
  } catch (e) {
    console.error('rekening-koran-link:', e instanceof Error ? e.message : e);
    return jawab(500, { success: false, error: 'Terjadi kesalahan' }, origin);
  }
});
