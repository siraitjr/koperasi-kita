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

interface Profil {
  id: string;
  email: string;
  role: string;
  cabang_id: string | null;
  nama: string;
  sumber: 'app_user' | 'user_metadata';
}

type HasilPemanggil =
  | { ok: true; profil: Profil }
  | { ok: false; sebab: 'tanpa_header' | 'jwt_ditolak' | 'tanpa_peran'; detail: string };

/**
 * Identitas pemanggil dari JWT.
 *
 * VERIFIKASI TOKEN: `admin.auth.getUser(jwt)` — diverifikasi GoTrue di sisi
 * server, jadi INDEPENDEN dari algoritma penandatanganan. Project yang
 * menerbitkan ES256 (kunci ber-`kid`) sama validnya dengan HS256; tidak ada
 * pembacaan `SUPABASE_JWT_SECRET` maupun verifikasi manual di sini.
 *
 * KENAPA HASILNYA BERBEDA-BEDA, BUKAN null
 * -----------------------------------------
 * Versi pertama mengembalikan `null` untuk tiga kegagalan yang sama sekali
 * berbeda — header kosong, JWT ditolak, dan baris `app_user` tidak terbaca —
 * lalu pemanggilnya memetakan semuanya ke 401 "unauthenticated". Akibatnya
 * masalah PostgREST (schema `koperasi` belum di-expose, atau barisnya belum
 * ada) tampil sebagai "token tidak valid", dan pemilik mencari
 * penyebabnya di tempat yang salah. Itu kesalahan rancangan saya, bukan
 * kesalahan tokennya.
 */
async function pemanggil(admin: SupabaseClient, req: Request): Promise<HasilPemanggil> {
  const jwt = (req.headers.get('Authorization') ?? '').replace(/^Bearer\s+/i, '').trim();
  if (!jwt) {
    console.warn('[auth] Authorization header kosong');
    return { ok: false, sebab: 'tanpa_header', detail: 'Header Authorization tidak ada.' };
  }

  const { data, error } = await admin.auth.getUser(jwt);
  if (error || !data?.user) {
    console.warn('[auth] getUser ditolak:', error?.message ?? '(tanpa pesan)');
    return { ok: false, sebab: 'jwt_ditolak', detail: error?.message ?? 'token ditolak GoTrue' };
  }

  const u = data.user;
  const meta = (u.user_metadata ?? {}) as Record<string, unknown>;
  console.log(`[auth] JWT sah untuk ${u.email} (${u.id})`);

  // Sumber utama: koperasi.app_user (otoritatif — dipakai RLS & Edge
  // Function lain). Kegagalannya TIDAK lagi mematikan.
  const { data: profil, error: eProfil } = await admin
    .from('app_user')
    .select('id, email, role, cabang_id, nama, aktif')
    .eq('id', u.id)
    .maybeSingle();

  if (eProfil) {
    // Paling sering: schema `koperasi` belum terdaftar di
    // Settings → API → Exposed schemas, sehingga PostgREST menolak query.
    console.error('[auth] query app_user gagal:', eProfil.message);
  }

  if (profil && profil.aktif !== false) {
    return {
      ok: true,
      profil: {
        id: profil.id, email: profil.email ?? u.email ?? '',
        role: String(profil.role ?? ''), cabang_id: profil.cabang_id ?? null,
        nama: profil.nama ?? '', sumber: 'app_user',
      },
    };
  }

  if (profil && profil.aktif === false) {
    console.warn('[auth] user nonaktif di app_user');
    return { ok: false, sebab: 'tanpa_peran', detail: 'Akun Anda nonaktif.' };
  }

  // Cadangan: user_metadata pada JWT. Dipakai bila baris app_user belum ada
  // atau PostgREST tidak bisa dibaca — supaya kegagalan infrastruktur tidak
  // menyamar jadi "token tidak valid".
  const roleMeta = String(meta.role ?? '');
  if (roleMeta) {
    console.warn('[auth] app_user tidak terbaca — memakai user_metadata sebagai cadangan');
    return {
      ok: true,
      profil: {
        id: u.id, email: u.email ?? '',
        role: roleMeta,
        cabang_id: (meta.cabang as string | null) ?? (meta.cabang_id as string | null) ?? null,
        nama: String(meta.nama ?? meta.name ?? ''),
        sumber: 'user_metadata',
      },
    };
  }

  console.error('[auth] peran tidak dapat ditentukan dari app_user maupun user_metadata');
  return {
    ok: false,
    sebab: 'tanpa_peran',
    detail: 'Peran tidak dapat ditentukan. Pastikan schema `koperasi` sudah di-expose '
          + 'di Settings → API → Exposed schemas, dan baris app_user untuk akun ini ada.',
  };
}

/** Terjemahan kegagalan identitas → respons, dengan kosakata kode yang sama. */
function tolakPemanggil(
  h: Extract<HasilPemanggil, { ok: false }>,
  origin: string | null,
): Response {
  if (h.sebab === 'tanpa_peran') {
    // BUKAN 401: tokennya sah, yang kurang adalah profil/peran. Membedakan
    // keduanya adalah seluruh inti perbaikan ini.
    return err('permission-denied', h.detail, 403, origin);
  }
  return err('unauthenticated', 'Token tidak valid atau sudah kadaluarsa.', 401, origin);
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
  const h = await pemanggil(admin, req);
  if (!h.ok) return tolakPemanggil(h, origin);
  const p = h.profil;

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
  const h = await pemanggil(admin, req);
  if (!h.ok) return tolakPemanggil(h, origin);
  const p = h.profil;

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
  const h = await pemanggil(admin, req);
  if (!h.ok) return tolakPemanggil(h, origin);
  const p = h.profil;

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
// 4. diag — melihat apa yang DILIHAT fungsi ini
// =========================================================================
// Ditambahkan setelah 401 yang menyesatkan: tanpa ini, satu-satunya
// informasi yang dimiliki pemilik adalah kode galat, dan itu sempat
// menunjuk ke arah yang salah (algoritma token) padahal masalahnya di
// lapisan lain. Aksi ini menjawab "sebenarnya fungsi ini melihat apa?".
//
// Tidak membocorkan apa pun yang belum dimiliki pemanggil: ia hanya
// menceritakan kembali JWT milik pemanggil sendiri + apakah tabel terbaca.
async function diag(admin: SupabaseClient, req: Request, origin: string | null) {
  const jwt = (req.headers.get('Authorization') ?? '').replace(/^Bearer\s+/i, '').trim();
  const laporan: Record<string, unknown> = {
    adaHeaderAuthorization: jwt.length > 0,
    panjangJwt: jwt.length,
    schemaDipakai: SCHEMA,
  };

  if (jwt) {
    const { data, error } = await admin.auth.getUser(jwt);
    laporan.jwtSah = !error && !!data?.user;
    laporan.jwtPesanGalat = error?.message ?? null;
    if (data?.user) {
      laporan.uid = data.user.id;
      laporan.email = data.user.email;
      laporan.userMetadata = data.user.user_metadata ?? {};
    }
  }

  // Apakah PostgREST bisa membaca schema `koperasi`?
  const { error: eTabel, count } = await admin
    .from('app_user').select('id', { count: 'exact', head: true });
  laporan.appUserTerbaca = !eTabel;
  laporan.appUserGalat = eTabel?.message ?? null;
  laporan.appUserJumlah = count ?? null;
  if (eTabel) {
    laporan.saran = 'Schema `koperasi` kemungkinan belum terdaftar di '
      + 'Settings → API → Exposed schemas.';
  }

  const h = await pemanggil(admin, req);
  laporan.identitas = h.ok
    ? { role: h.profil.role, cabang_id: h.profil.cabang_id, sumber: h.profil.sumber }
    : { gagal: h.sebab, detail: h.detail };

  console.log('[diag]', JSON.stringify(laporan));
  return ok({ diag: laporan }, origin);
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
  // Log per-request. Ketiadaannya membuat Dashboard hanya menampilkan
  // booted/shutdown saat 401 kemarin — tidak ada apa pun untuk ditelusuri.
  console.log(`[req] action=${action} origin=${origin ?? '-'}`);

  try {
    switch (action) {
      case 'autoLogin': return await autoLogin(admin, req, origin);
      case 'takeover': return await takeover(admin, req, body, origin);
      case 'restore': return await restore(admin, req, body, origin);
      case 'diag': return await diag(admin, req, origin);
      default: return err('invalid-argument', `Aksi tidak dikenal: ${action}`, 400, origin);
    }
  } catch (e) {
    console.error('❌ error tak tertangani:', e);
    return err('internal', (e as Error).message ?? 'Kesalahan internal', 500, origin);
  }
});
