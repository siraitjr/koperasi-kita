// =========================================================================
// EDGE FUNCTION: user-management
// Pengganti 5 callable di functions/resetUserPassword.js (Milestone 4)
// =========================================================================
// BELUM PERNAH DI-DEPLOY / DIJALANKAN.
//
// Satu function dengan dispatcher `action`, bukan lima function terpisah:
//  - satu kali deploy, satu URL, satu jalur verifikasi wewenang;
//  - pemeriksaan "pemanggil adalah Pengawas" dan penulisan audit log ditulis
//    SEKALI dan dipakai semua aksi — lima salinan berarti lima peluang salah.
//
// KONTRAK RESPONS DIJAGA 1:1 dengan callable Firebase, karena kode Android
// yang mem-parsing hasilnya TIDAK BOLEH berubah (batasan: hanya layer
// transport yang diganti). Semua aksi mengembalikan { success: boolean, ... }
// dengan nama field yang sama persis.
//
// Deploy:
//   supabase functions deploy user-management
// Secret yang dibutuhkan (JANGAN di-commit):
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY   (otomatis tersedia di Edge)
// =========================================================================

import { createClient, SupabaseClient } from 'jsr:@supabase/supabase-js@2';

const SCHEMA = 'koperasi';

interface Ctx {
  admin: SupabaseClient;      // service_role — MEM-BYPASS RLS
  callerId: string;
  callerEmail: string;
}

// --- util respons; bentuknya meniru HttpsError agar parsing Android tetap ---
const ok = (body: Record<string, unknown>) =>
  new Response(JSON.stringify({ success: true, ...body }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });

/**
 * Kode galat memakai kosakata Firebase (`permission-denied`, `not-found`,
 * `invalid-argument`, `already-exists`) — BUKAN karena rapi, melainkan karena
 * PelangganViewModel mencocokkan string itu untuk memilih pesan Indonesia
 * (mis. :16359-16362). Mengubahnya akan membuat pesan galat di layar Pengawas
 * berubah jadi teks mentah.
 */
const err = (code: string, message: string, status = 400) =>
  new Response(JSON.stringify({ success: false, code, message, error: `${code}: ${message}` }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

// =========================================================================
// WEWENANG
// =========================================================================
/**
 * Memverifikasi JWT pemanggil lalu memastikan perannya `pengawas`.
 *
 * Cermin functions/resetUserPassword.js:37-44, dengan satu perbedaan sumber:
 * di sana wewenang dibaca dari RTDB `metadata/roles/pengawas/{uid}`; di sini
 * dari `koperasi.app_user.role`. Sumbernya berbeda karena RTDB tidak lagi
 * jadi sumber kebenaran, tetapi keputusannya identik.
 */
async function wewenangPengawas(req: Request): Promise<Ctx | Response> {
  const authHeader = req.headers.get('Authorization') ?? '';
  const jwt = authHeader.replace(/^Bearer\s+/i, '').trim();
  if (!jwt) {
    return err('unauthenticated', 'Anda harus login untuk mengakses fungsi ini.', 401);
  }

  const url = Deno.env.get('SUPABASE_URL')!;
  const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;

  const admin = createClient(url, serviceKey, {
    auth: { persistSession: false },
    db: { schema: SCHEMA },
  });

  const { data: userData, error: userErr } = await admin.auth.getUser(jwt);
  if (userErr || !userData?.user) {
    return err('unauthenticated', 'Sesi tidak valid. Silakan login ulang.', 401);
  }

  const callerId = userData.user.id;
  const { data: profil } = await admin
    .from('app_user').select('role, aktif').eq('id', callerId).single();

  if (!profil || profil.aktif === false || profil.role !== 'pengawas') {
    return err('permission-denied', 'Hanya Pengawas yang dapat mengakses fungsi ini.', 403);
  }

  return { admin, callerId, callerEmail: userData.user.email ?? 'unknown' };
}

// =========================================================================
// AUDIT
// =========================================================================
/**
 * Cermin password_reset_logs di RTDB (resetUserPassword.js:111-119 untuk
 * sukses, :142-149 untuk gagal). Ditulis pada KEDUA jalur — mencatat hanya
 * yang berhasil membuat percobaan yang ditolak jadi tak terlihat, padahal
 * itu justru yang menarik saat audit.
 *
 * Kegagalan menulis log TIDAK menggagalkan operasi (paritas dengan perilaku
 * lama), tetapi tetap dicetak ke log Edge.
 */
async function catatAudit(
  admin: SupabaseClient,
  row: Record<string, unknown>,
): Promise<void> {
  const { error } = await admin.from('password_reset_log').insert(row);
  if (error) console.error('⚠️ gagal menulis audit log:', error.message);
}

// =========================================================================
// AKSI
// =========================================================================

// 1. resetUserPassword — resetUserPassword.js:22-167
async function resetUserPassword(ctx: Ctx, body: Record<string, unknown>) {
  const targetEmail = String(body.targetEmail ?? '').trim().toLowerCase();
  const newPassword = String(body.newPassword ?? '');

  if (!targetEmail) return err('invalid-argument', 'Email target harus diisi.');
  if (newPassword.length < 6) {
    return err('invalid-argument', 'Password baru harus minimal 6 karakter.');
  }

  const { data: target } = await ctx.admin
    .from('app_user').select('id, nama, role').eq('email', targetEmail).maybeSingle();

  if (!target) {
    await catatAudit(ctx.admin, {
      target_email: targetEmail, reset_by: ctx.callerId,
      berhasil: false, error: 'target tidak ditemukan',
    });
    return err('not-found', 'User tidak ditemukan dalam sistem koperasi.', 404);
  }

  // ✅ PERILAKU WAJIB (resetUserPassword.js:73-79): Pengawas TIDAK BOLEH
  // mereset password Pengawas lain. Tanpa ini, satu pengawas bisa mengunci
  // pengawas lain keluar dari sistem.
  if (target.role === 'pengawas') {
    await catatAudit(ctx.admin, {
      target_id: target.id, target_email: targetEmail, reset_by: ctx.callerId,
      berhasil: false, error: 'target adalah pengawas',
    });
    return err('permission-denied', 'Tidak dapat mengubah password Pengawas lainnya.', 403);
  }

  const { error: upErr } = await ctx.admin.auth.admin.updateUserById(target.id, {
    password: newPassword,
  });
  if (upErr) {
    await catatAudit(ctx.admin, {
      target_id: target.id, target_email: targetEmail, reset_by: ctx.callerId,
      berhasil: false, error: upErr.message,
    });
    return err('internal', 'Terjadi kesalahan saat mereset password: ' + upErr.message, 500);
  }

  // ✅ PERILAKU WAJIB (resetUserPassword.js:99): putuskan seluruh sesi target.
  // Padanan revokeRefreshTokens() Firebase. Tanpa ini, pemilik lama tetap bisa
  // memakai token yang masih hidup meski passwordnya sudah diganti.
  const { error: soErr } = await ctx.admin.auth.admin.signOut(target.id, 'global');
  if (soErr) console.error('⚠️ signOut global gagal:', soErr.message);

  await catatAudit(ctx.admin, {
    target_id: target.id, target_email: targetEmail,
    reset_by: ctx.callerId, reset_by_email: ctx.callerEmail,
    berhasil: true,
  });

  return ok({
    message: `Password ${target.nama} (${target.role}) berhasil diubah.`,
    targetUid: target.id,
    targetName: target.nama,
    targetRole: target.role,
  });
}

// 2. getAllUsers — resetUserPassword.js:174+
async function getAllUsers(ctx: Ctx) {
  const { data: rows, error } = await ctx.admin
    .from('app_user')
    .select('id, email, nama, role, cabang_id, aktif, cabang:cabang_id (nama)')
    .order('role');

  if (error) return err('internal', error.message, 500);

  // Bentuk objek dipertahankan persis: uid/email/name/role/cabang/cabangName/type
  // (dibaca UserInfo di PelangganViewModel.kt:16100-16107).
  const users = (rows ?? []).map((u: Record<string, any>) => ({
    uid: u.id,
    email: u.email ?? '',
    name: u.nama || 'Tidak ada nama',
    role: u.role ?? 'admin',
    cabang: u.cabang_id ?? '',
    cabangName: u.cabang?.nama ?? (u.role === 'koordinator' ? 'Semua Cabang' : ''),
    type: u.role ?? '',
  }));

  return ok({ users, count: users.length });
}

// 3. createNewUser — resetUserPassword.js:343+
async function createNewUser(ctx: Ctx, body: Record<string, unknown>) {
  const email = String(body.email ?? '').trim().toLowerCase();
  const password = String(body.password ?? '');
  const nama = String(body.name ?? '').trim();
  const role = String(body.role ?? '').trim();
  const cabangId = String(body.cabangId ?? '').trim();

  if (!email) return err('invalid-argument', 'Email harus diisi.');
  if (password.length < 6) return err('invalid-argument', 'Password minimal 6 karakter.');
  if (!nama) return err('invalid-argument', 'Nama harus diisi.');
  if (!['admin', 'pimpinan', 'koordinator', 'kasir_unit', 'sekretaris'].includes(role)) {
    return err('invalid-argument', `Role tidak dikenal: ${role}`);
  }
  // Pengawas tidak dibuat lewat jalur ini — sengaja. Menambah pengawas berarti
  // menambah orang yang bisa mereset seluruh staf; itu keputusan di luar layar
  // User Management.
  if (role === 'pengawas') {
    return err('permission-denied', 'Pembuatan akun Pengawas tidak lewat fungsi ini.', 403);
  }

  const { data: sudah } = await ctx.admin
    .from('app_user').select('id').eq('email', email).maybeSingle();
  if (sudah) return err('already-exists', 'Email sudah terdaftar di sistem.', 409);

  const { data: created, error: cErr } = await ctx.admin.auth.admin.createUser({
    email, password, email_confirm: true,
    user_metadata: { nama, role, cabang: cabangId },
  });
  if (cErr || !created?.user) {
    const pesan = cErr?.message ?? 'gagal membuat akun';
    if (/already/i.test(pesan)) return err('already-exists', 'Email sudah terdaftar di sistem.', 409);
    return err('internal', pesan, 500);
  }

  const { error: insErr } = await ctx.admin.from('app_user').insert({
    id: created.user.id, email, nama, role,
    cabang_id: cabangId || null, aktif: true,
  });
  if (insErr) {
    // Kompensasi: akun Auth sudah terlanjur dibuat tetapi profilnya gagal.
    // Dibiarkan menggantung akan membuat email itu tidak bisa dipakai lagi
    // sekaligus tidak muncul di daftar user — dua-duanya membingungkan.
    await ctx.admin.auth.admin.deleteUser(created.user.id);
    return err('internal', 'Gagal menyimpan profil user: ' + insErr.message, 500);
  }

  let cabangName = '';
  if (cabangId) {
    const { data: cb } = await ctx.admin
      .from('cabang').select('nama').eq('id', cabangId).maybeSingle();
    cabangName = cb?.nama ?? cabangId;
  }

  return ok({
    message: `${role} "${nama}" berhasil dibuat.`,
    user: {
      uid: created.user.id, email, name: nama, role,
      cabang: cabangId, cabangName, type: role,
    },
  });
}

// 4. deleteExistingUser — resetUserPassword.js:520+
async function deleteExistingUser(ctx: Ctx, body: Record<string, unknown>) {
  const targetUid = String(body.targetUid ?? '').trim();
  if (!targetUid) return err('invalid-argument', 'targetUid harus diisi.');
  if (targetUid === ctx.callerId) {
    return err('permission-denied', 'Tidak dapat menghapus akun Anda sendiri.', 403);
  }

  const { data: target } = await ctx.admin
    .from('app_user').select('id, nama, role').eq('id', targetUid).maybeSingle();
  if (!target) return err('not-found', 'User tidak ditemukan.', 404);
  if (target.role === 'pengawas') {
    return err('permission-denied', 'Tidak dapat menghapus Pengawas lainnya.', 403);
  }

  /* NONAKTIFKAN, BUKAN HAPUS BARIS.
   * `nasabah.admin_id`, `pembayaran.dicatat_oleh`, dan `jurnal_transaksi`
   * menunjuk ke app_user. Menghapus barisnya akan memutus atribusi seluruh
   * riwayat keuangan yang pernah dicatat orang ini — persis alasan yang sama
   * dengan soft delete nasabah di 007. Akun Auth-nya dihapus supaya tidak bisa
   * login lagi, jadi efek praktisnya sama dengan penghapusan. */
  const { error: updErr } = await ctx.admin
    .from('app_user').update({ aktif: false }).eq('id', targetUid);
  if (updErr) return err('internal', updErr.message, 500);

  const { error: delErr } = await ctx.admin.auth.admin.deleteUser(targetUid);
  if (delErr) console.error('⚠️ gagal menghapus akun Auth:', delErr.message);

  await catatAudit(ctx.admin, {
    target_id: targetUid, reset_by: ctx.callerId, reset_by_email: ctx.callerEmail,
    berhasil: true, error: 'user dinonaktifkan & akun auth dihapus',
  });

  return ok({ message: `User "${target.nama}" berhasil dihapus.` });
}

// 5. getAllCabang — resetUserPassword.js:668+
async function getAllCabang(ctx: Ctx) {
  const { data: rows, error } = await ctx.admin
    .from('cabang')
    .select('id, nama, pimpinan_id, pimpinan:pimpinan_id (nama)')
    .order('nama');
  if (error) return err('internal', error.message, 500);

  const cabangList = (rows ?? []).map((c: Record<string, any>) => ({
    id: c.id,
    name: c.nama ?? c.id,
    pimpinanUid: c.pimpinan_id ?? '',
    pimpinanName: c.pimpinan?.nama ?? '',
  }));
  return ok({ cabangList });
}

// =========================================================================
// DISPATCHER
// =========================================================================
Deno.serve(async (req) => {
  if (req.method !== 'POST') return err('invalid-argument', 'Hanya POST.', 405);

  const ctxOrRes = await wewenangPengawas(req);
  if (ctxOrRes instanceof Response) return ctxOrRes;
  const ctx = ctxOrRes;

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return err('invalid-argument', 'Body bukan JSON.');
  }

  const action = String(body.action ?? '');
  try {
    switch (action) {
      case 'resetUserPassword': return await resetUserPassword(ctx, body);
      case 'getAllUsers': return await getAllUsers(ctx);
      case 'createNewUser': return await createNewUser(ctx, body);
      case 'deleteExistingUser': return await deleteExistingUser(ctx, body);
      case 'getAllCabang': return await getAllCabang(ctx);
      default: return err('invalid-argument', `Aksi tidak dikenal: ${action}`);
    }
  } catch (e) {
    console.error('❌ error tak tertangani:', e);
    return err('internal', (e as Error).message ?? 'Kesalahan internal', 500);
  }
});
