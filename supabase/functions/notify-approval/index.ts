// =========================================================================
// notify-approval — FCM untuk perpindahan fase approval  (BUG 7b)
// =========================================================================
// Dipanggil pemicu `koperasi.tg_notifikasi_approval` (031 §3) setiap kali
// `pengajuan.phase` berubah atau baris pengajuan dibuat.
//
// BELUM PERNAH DIJALANKAN. Saya tidak punya akses ke project Supabase
// maupun kredensial FCM Anda, jadi berkas ini belum teruji terhadap server
// sungguhan — lihat "UJI" di bawah sebelum menggantungkan operasional
// padanya.
//
// KENAPA FCM HTTP v1, BUKAN API LAMA
// -------------------------------------------------------------------------
// Endpoint legacy (`fcm.googleapis.com/fcm/send` dengan server key) sudah
// dihentikan Google. v1 menuntut OAuth2 dari service account, karena itu ada
// penandatanganan JWT di bawah — bukan kerumitan yang dibuat-buat.
//
// RAHASIA — SEMUANYA DARI ENV, TIDAK ADA YANG DITULIS DI BERKAS INI:
//   FIREBASE_SERVICE_ACCOUNT  JSON service account (satu baris)
//   SUPABASE_URL              disediakan runtime
//   SUPABASE_SERVICE_ROLE_KEY disediakan runtime
// =========================================================================

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

/** Judul & isi notifikasi per fase. Satu tempat, supaya bunyinya konsisten. */
const PESAN: Record<string, { judul: string; isi: string }> = {
  awaiting_pimpinan: {
    judul: 'Pengajuan baru menunggu persetujuan',
    isi: 'Ada pengajuan pinjaman baru dari admin lapangan.',
  },
  awaiting_koordinator: {
    judul: 'Pengajuan menunggu Koordinator',
    isi: 'Pimpinan sudah menyetujui. Menunggu tinjauan Koordinator.',
  },
  awaiting_pengawas: {
    judul: 'Pengajuan menunggu Pengawas',
    isi: 'Koordinator sudah meneruskan. Menunggu keputusan Pengawas.',
  },
  awaiting_koordinator_final: {
    judul: 'Konfirmasi akhir Koordinator',
    isi: 'Pengawas sudah memutuskan. Menunggu konfirmasi Koordinator.',
  },
  awaiting_pimpinan_final: {
    judul: 'Finalisasi Pimpinan',
    isi: 'Menunggu finalisasi Pimpinan sebelum pencairan.',
  },
};

function b64url(data: Uint8Array | string): string {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** OAuth2 access token dari service account (RS256 JWT bearer). */
async function tokenAkses(sa: { client_email: string; private_key: string }): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claim = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: FCM_SCOPE,
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
  }));

  const pem = sa.private_key
    .replace(/-----BEGIN PRIVATE KEY-----/, '')
    .replace(/-----END PRIVATE KEY-----/, '')
    .replace(/\s+/g, '');
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    'pkcs8', der,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false, ['sign'],
  );
  const sig = new Uint8Array(await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${claim}`),
  ));

  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${header}.${claim}.${b64url(sig)}`,
    }),
  });
  if (!res.ok) throw new Error(`oauth ${res.status}: ${await res.text()}`);
  return (await res.json()).access_token;
}

Deno.serve(async (req) => {
  try {
    const { cabang_id, phase, pengajuan_id, pinjaman_id, final_decision } = await req.json();

    // Fase `completed` tidak punya penerima berikutnya. Bukan galat —
    // rantainya memang selesai.
    const pesan = PESAN[phase];
    if (!pesan) {
      return Response.json({ ok: true, dilewati: `fase ${phase} tanpa penerima` });
    }

    const sa = JSON.parse(Deno.env.get('FIREBASE_SERVICE_ACCOUNT') ?? '{}');
    if (!sa.client_email || !sa.private_key || !sa.project_id) {
      // Gagal dengan jelas. Mengembalikan 200 diam-diam akan membuat pemicu
      // mengira notifikasi terkirim padahal tidak pernah ada.
      return Response.json(
        { ok: false, error: 'FIREBASE_SERVICE_ACCOUNT belum diisi/di-format salah' },
        { status: 500 },
      );
    }

    const db = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    );

    // Penerima ditentukan SERVER lewat penerima_fase (031 §2) — aturan yang
    // sama dengan yang dibaca Android, bukan disalin ulang di sini.
    const { data: penerima, error: e1 } = await db
      .schema('koperasi').rpc('penerima_fase', { p_cabang: cabang_id, p_fase: phase });
    if (e1) throw new Error(`penerima_fase: ${e1.message}`);

    const uids = (penerima ?? []).map((r: { user_id: string }) => r.user_id);
    if (!uids.length) {
      return Response.json({ ok: true, dilewati: 'tidak ada penerima aktif' });
    }

    const { data: baris, error: e2 } = await db
      .schema('koperasi').from('fcm_token').select('token').in('user_id', uids);
    if (e2) throw new Error(`fcm_token: ${e2.message}`);

    const tokens: string[] = (baris ?? []).map((r: { token: string }) => r.token);
    if (!tokens.length) {
      return Response.json({ ok: true, dilewati: 'penerima belum punya token' });
    }

    const akses = await tokenAkses(sa);
    const url = `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`;

    let terkirim = 0;
    const basi: string[] = [];

    for (const token of tokens) {
      const r = await fetch(url, {
        method: 'POST',
        headers: { Authorization: `Bearer ${akses}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: {
            token,
            notification: { title: pesan.judul, body: pesan.isi },
            // Data payload dipakai aplikasi untuk membuka layar yang tepat.
            // Semua nilai WAJIB string di FCM v1.
            data: {
              type: 'approval',
              phase: String(phase),
              pengajuan_id: String(pengajuan_id ?? ''),
              pinjaman_id: String(pinjaman_id ?? ''),
              final_decision: String(final_decision ?? ''),
            },
            android: { priority: 'HIGH' },
          },
        }),
      });

      if (r.ok) { terkirim++; continue; }

      // 404 UNREGISTERED / 400 INVALID_ARGUMENT = token mati. Dibersihkan di
      // sini, bukan ditebak dari waktu: itu satu-satunya sinyal yang benar
      // bahwa perangkat sudah tidak ada.
      if (r.status === 404 || r.status === 400) basi.push(token);
      console.error(`fcm ${r.status} untuk token …${token.slice(-8)}: ${await r.text()}`);
    }

    if (basi.length) {
      await db.schema('koperasi').from('fcm_token').delete().in('token', basi);
    }

    return Response.json({ ok: true, terkirim, dibuang: basi.length, penerima: uids.length });
  } catch (e) {
    console.error('notify-approval gagal:', e);
    return Response.json({ ok: false, error: String(e) }, { status: 500 });
  }
});

// =========================================================================
// DEPLOY & UJI
// =========================================================================
//   supabase secrets set FIREBASE_SERVICE_ACCOUNT="$(cat service-account.json | tr -d '\n')"
//   supabase functions deploy notify-approval
//
// Uji TANPA menyentuh pengajuan sungguhan (pastikan ada token tersimpan):
//   curl -X POST "https://<ref>.supabase.co/functions/v1/notify-approval" \
//     -H "Authorization: Bearer <service_role>" \
//     -H "Content-Type: application/json" \
//     -d '{"cabang_id":"panti","phase":"awaiting_pimpinan","pengajuan_id":"uji"}'
//
// Balasan {"ok":true,"terkirim":N} dengan N>0 berarti rantai lengkap.
// {"dilewati":"penerima belum punya token"} berarti Android belum sempat
// menyimpan token — login ulang di perangkat Pimpinan lalu ulangi.
//
// BARU SESUDAH ITU jalankan 031 §3 (pemicunya).
// =========================================================================
