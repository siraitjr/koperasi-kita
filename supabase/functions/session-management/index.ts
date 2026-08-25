// =========================================================================
// EDGE FUNCTION: session-management
// Pengganti 3 fungsi sesi/impersonasi Firebase (Tahap A, 010 §4)
// =========================================================================
// BELUM PERNAH DI-DEPLOY / DIJALANKAN.
//
//   generateAutoLoginToken  (generateAutoLoginToken.js)  → autoLogin
//   generateTakeoverToken   (remoteTakeover.js:82)       → takeover
//   restorePimpinanSession  (remoteTakeover.js:189)      → restore
//
// ⚠ PERBEDAAN MENDASAR: SUPABASE TIDAK PUNYA createCustomToken()
// -------------------------------------------------------------------------
// Ketiga fungsi Firebase bekerja dengan `admin.auth().createCustomToken(uid)`
// lalu klien `signInWithCustomToken()`. Supabase Auth tidak menyediakan
// padanan itu sama sekali.
//
// Penggantinya: `auth.admin.generateLink({ type: 'magiclink', email })`
// mengembalikan `properties.hashed_token`, dan klien menukarnya jadi sesi
// lewat `auth.verifyOtp({ token_hash, type: 'magiclink' })`.
//
// Ini PATUH pada aturan tanpa-email (008 §0 / 010 §0): `generateLink`
// MEMBUAT tautan dan mengembalikannya ke pemanggil — Supabase tidak
// mengirim apa pun. Token diteruskan lewat respons HTTP, tidak pernah lewat
// kotak surat. Yang dilarang adalah `resetPasswordForEmail` dan
// `inviteUserByEmail`, dan keduanya tidak dipakai di sini.
//
// Konsekuensi yang harus disadari: token hasil generateLink berumur pendek
// dan SEKALI PAKAI. Itu justru lebih ketat daripada custom token Firebase
// (berlaku 1 jam, bisa dipakai berulang).
//
// Deploy & uji: docs/migration/supabase/012_edge_functions_session.md
// =========================================================================

import { createClient, SupabaseClient } from 'jsr:@supabase/supabase-js@2';

const SCHEMA = 'koperasi';

// Origin web yang boleh memanggil autoLogin. Cermin ALLOWED_ORIGIN di
// generateAutoLoginToken.js:17 — daftar putih, bukan '*', karena respons
// fungsi ini adalah kredensial.
const ALLOWED_ORIGINS = [
  'https://www.koperasi-kita.com',
  'https://koperasi-kita.com',
];

function corsHeaders(origin: string | null): Record<string, string> {
  const asal = origin && ALLOWED_ORIGINS.includes(origin) ? origin : ALLOWED_ORIGINS[0];
  return {
    'Access-Control-Allow-Origin': asal,
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Vary': 'Origin',
  };
}

const ok = (body: Record<string, unknown>, origin: string | null) =>
  new Response(JSON.stringify({ success: true, ...body }), {
    status: 200,
    headers: { 'Content-Type': 'application/json', ...corsHeaders(origin) },
  });

/** Kosakata kode dipertahankan sama dengan HttpsError Firebase. */
const err = (code: string, message: string, status: number, origin: string | null) =>
  new Response(
    JSON.stringify({ success: false, code, message, error: `${code}: ${message}` }),
    { status, headers: { 'Content-Type': 'application/json', ...corsHeaders(origin) } },
  );

function adminClient(): SupabaseClient {
  return createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    { auth: { persistSession: false }, db: { schema: SCHEMA } },
  );
}

/**
 * Menukar identitas user menjadi token sesi sekali-pakai.
 * Inilah pengganti createCustomToken(). Lihat catatan di kepala berkas.
 */
async function buatTokenSesi(
  admin: SupabaseClient,
  email: string,
): Promise<{ token_hash: string } | { gagal: string }> {
  const { data, error } = await admin.auth.admin.generateLink({
    type: 'magiclink',
    email,
  });
  if (error || !data?.properties?.hashed_token) {
    return { gagal: error?.message ?? 'gagal membuat token sesi' };
  }
  return { token_hash: data.properties.hashed_token };
}

/** Profil pemanggil dari JWT. null = tidak sah. */
async function pemanggil(
  admin: SupabaseClient,
  req: Request,
): Promise<{ id: string; email: string; role: string; cabang_id: string | null; nama: string } | null> {
  const jwt = (req.headers.get('Authorization') ?? '').replace(/^Bearer\s+/i, '').trim();
  if (!jwt) return null;
  const { data, error } = await admin.auth.getUser(jwt);
  if (error || !data?.user) return null;

  const { data: profil } = await admin
    .from('app_user')
    .select('id, email, role, cabang_id, nama, aktif')
    .eq('id', data.user.id)
    .single();

  if (!profil || profil.aktif === false) return null;
  return profil as never;
}

// =========================================================================
// 1. autoLogin — Android → Web SSO
// =========================================================================
// Firebase: Android mengirim ID Token, fungsi menukarnya jadi custom token
// untuk user YANG SAMA (generateAutoLoginToken.js:44-47).
//
// Di sini identitasnya sudah ada di JWT itu sendiri, jadi tidak ada
// parameter uid — mustahil meminta token untuk orang lain. Versi Firebase
// pun begitu; dipertahankan.
async function autoLogin(admin: SupabaseClient, req: Request, origin: string | null) {
  const p = await pemanggil(admin, req);
  if (!p) return err('unauthenticated', 'Token tidak valid atau sudah kadaluarsa.', 401, origin);

  const hasil = await buatTokenSesi(admin, p.email);
  if ('gagal' in hasil) return err('internal', hasil.gagal, 500, origin);

  return ok({ token_hash: hasil.token_hash, email: p.email, uid: p.id }, origin);
}

// =========================================================================
// 2. takeover — Pimpinan mengambil alih sesi Admin
// =========================================================================
// Cermin remoteTakeover.js:82-186, dengan seluruh pemeriksaannya.
async function takeover(
  admin: SupabaseClient,
  req: Request,
  body: Record<string, unknown>,
  origin: string | null,
) {
  const p = await pemanggil(admin, req);
  if (!p) return err('unauthenticated', 'Harus login.', 401, origin);

  const targetId = String(body.targetAdminUid ?? '').trim();
  if (!targetId) return err('invalid-argument', 'targetAdminUid wajib diisi.', 400, origin);

  // (1) Pemanggil harus Pimpinan.
  //
  // Firebase mencarinya lewat TIGA sumber berturut-turut
  // (findPimpinanCabang, remoteTakeover.js:10-52): metadata/cabang →
  // metadata/admins/role → metadata/roles/pimpinan. Tiga sumber itu ada
  // karena RTDB tidak punya satu tempat yang otoritatif.
  // Di Postgres cukup satu: app_user.role.
  if (p.role !== 'pimpinan') {
    return err('permission-denied', 'Hanya Pimpinan yang bisa melakukan takeover.', 403, origin);
  }

  // (2) Target harus ada.
  const { data: target } = await admin
    .from('app_user').select('id, nama, role, cabang_id, email, aktif')
    .eq('id', targetId).maybeSingle();
  if (!target) return err('not-found', 'Admin tidak ditemukan.', 404, origin);

  // (3) Target harus admin lapangan. Firebase tidak memeriksa ini sama
  //     sekali — ia hanya memastikan targetnya ada di metadata/admins,
  //     sehingga Pimpinan bisa mengambil alih sesama Pimpinan bahkan
  //     Pengawas selama cabangnya cocok. Ditutup di sini.
  if (target.role !== 'admin') {
    return err('permission-denied',
      `Takeover hanya untuk Admin Lapangan, bukan ${target.role}.`, 403, origin);
  }

  // (4) Cabang harus sama.
  //     Firebase melonggarkan ini bila pimpinan tidak punya cabang yang
  //     jelas (remoteTakeover.js:126 — `&& allPimpinanCabang.size > 0`),
  //     yang berarti pimpinan tanpa cabang bisa mengambil alih SIAPA PUN.
  //     Kelonggaran itu tidak dibawa: tanpa cabang, tidak ada wewenang.
  const cabangPimpinan = new Set<string>();
  if (p.cabang_id) cabangPimpinan.add(p.cabang_id);
  const { data: cabangDipimpin } = await admin
    .from('cabang').select('id').eq('pimpinan_id', p.id);
  for (const c of cabangDipimpin ?? []) cabangPimpinan.add((c as { id: string }).id);

  if (cabangPimpinan.size === 0) {
    return err('permission-denied',
      'Anda tidak terdaftar sebagai pimpinan cabang mana pun.', 403, origin);
  }
  if (!target.cabang_id || !cabangPimpinan.has(target.cabang_id)) {
    return err('permission-denied', 'Admin bukan bagian dari cabang Anda.', 403, origin);
  }

  // (5) Kunci sesi. Re-takeover oleh pimpinan yang SAMA diizinkan
  //     (remoteTakeover.js:136-145) — pimpinan yang aplikasinya tertutup
  //     tidak boleh terkunci dari sesinya sendiri.
  const { data: lock } = await admin
    .from('session_lock').select('locked_by, pimpinan_name, status')
    .eq('admin_id', targetId).maybeSingle();

  if (lock && lock.status === 'active' && lock.locked_by !== p.id) {
    return err('already-exists',
      `Akun admin sedang digunakan oleh ${lock.pimpinan_name || 'Pimpinan lain'}.`,
      409, origin);
  }

  // (6) Token sesi untuk TARGET.
  const hasil = await buatTokenSesi(admin, target.email);
  if ('gagal' in hasil) return err('internal', 'Gagal membuat token: ' + hasil.gagal, 500, origin);

  // (7) Pasang kunci + sinyal logout + jejak permanen.
  await admin.from('session_lock').upsert({
    admin_id: targetId, locked_by: p.id,
    pimpinan_name: p.nama || p.email, status: 'active',
    locked_at: new Date().toISOString(), released_at: null,
  }, { onConflict: 'admin_id' });

  await admin.from('force_logout').upsert({
    user_id: targetId, reason: 'takeover', by_user: p.id,
    created_at: new Date().toISOString(),
  }, { onConflict: 'user_id' });

  await admin.from('takeover_log').insert({
    admin_id: targetId, pimpinan_id: p.id, aksi: 'takeover',
    cabang_id: target.cabang_id,
    keterangan: `${p.nama || p.email} mengambil alih sesi ${target.nama}`,
  });

  return ok({
    token_hash: hasil.token_hash,
    email: target.email,
    adminName: target.nama || 'Admin',
  }, origin);
}

// =========================================================================
// 3. restore — kembali ke akun Pimpinan
// =========================================================================
// Cermin remoteTakeover.js:189-236.
async function restore(
  admin: SupabaseClient,
  req: Request,
  body: Record<string, unknown>,
  origin: string | null,
) {
  const p = await pemanggil(admin, req);
  if (!p) return err('unauthenticated', 'Harus login.', 401, origin);

  const adminId = String(body.adminUid ?? '').trim();
  // pimpinanUid TIDAK diambil dari body.
  //
  // Firebase menerimanya sebagai parameter (remoteTakeover.js:197) lalu
  // membuat token untuk uid itu. Karena saat restore pemanggil sedang
  // masuk SEBAGAI ADMIN, siapa pun yang memegang sesi admin bisa meminta
  // token untuk uid pimpinan mana pun yang ia tebak. Di sini pimpinan
  // diambil dari session_lock — satu-satunya sumber yang sah.
  if (!adminId) return err('invalid-argument', 'adminUid wajib diisi.', 400, origin);

  const { data: lock } = await admin
    .from('session_lock').select('locked_by, status')
    .eq('admin_id', adminId).maybeSingle();

  if (!lock) {
    // Firebase tetap melanjutkan agar pimpinan tidak terjebak
    // (remoteTakeover.js:206-209). Di sini tidak bisa: tanpa lock, tidak
    // ada cara mengetahui pimpinan mana yang berhak dipulihkan tanpa
    // memercayai input pemanggil — dan itu justru lubangnya.
    return err('not-found',
      'Sesi takeover tidak ditemukan. Login ulang sebagai Pimpinan.', 404, origin);
  }

  // Pemanggil harus si pimpinan pemegang kunci, ATAU admin yang diambil
  // alih (aplikasi memanggil restore saat masih memegang sesi admin).
  if (p.id !== lock.locked_by && p.id !== adminId) {
    return err('permission-denied', 'Sesi takeover bukan milik Anda.', 403, origin);
  }

  const { data: pimpinan } = await admin
    .from('app_user').select('id, email, nama').eq('id', lock.locked_by).maybeSingle();
  if (!pimpinan) return err('not-found', 'Akun Pimpinan tidak ditemukan.', 404, origin);

  const hasil = await buatTokenSesi(admin, pimpinan.email);
  if ('gagal' in hasil) {
    return err('internal', 'Gagal mengembalikan sesi: ' + hasil.gagal, 500, origin);
  }

  // Lepas kunci. Barisnya TIDAK dihapus — statusnya diubah, supaya
  // "pernah terjadi takeover" tetap terbaca. Penghapusan jejak adalah
  // justru yang membuat RTDB sulit diaudit.
  await admin.from('session_lock')
    .update({ status: 'released', released_at: new Date().toISOString() })
    .eq('admin_id', adminId);

  await admin.from('force_logout').delete().eq('user_id', adminId);

  await admin.from('takeover_log').insert({
    admin_id: adminId, pimpinan_id: pimpinan.id, aksi: 'restore',
    keterangan: `Sesi dikembalikan ke ${pimpinan.nama || pimpinan.email}`,
  });

  return ok({ token_hash: hasil.token_hash, email: pimpinan.email }, origin);
}

// =========================================================================
// DISPATCHER
// =========================================================================
Deno.serve(async (req) => {
  const origin = req.headers.get('Origin');

  if (req.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders(origin) });
  }
  if (req.method !== 'POST') {
    return err('invalid-argument', 'Hanya POST.', 405, origin);
  }

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return err('invalid-argument', 'Body bukan JSON.', 400, origin);
  }

  const admin = adminClient();
  const action = String(body.action ?? '');

  try {
    switch (action) {
      case 'autoLogin': return await autoLogin(admin, req, origin);
      case 'takeover': return await takeover(admin, req, body, origin);
      case 'restore': return await restore(admin, req, body, origin);
      default: return err('invalid-argument', `Aksi tidak dikenal: ${action}`, 400, origin);
    }
  } catch (e) {
    console.error('❌ error tak tertangani:', e);
    return err('internal', (e as Error).message ?? 'Kesalahan internal', 500, origin);
  }
});
