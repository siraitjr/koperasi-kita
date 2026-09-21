import { NextResponse } from 'next/server';
import { proyekita } from '../../../lib/proyekitaSupabase';

export const dynamic = 'force-dynamic';
export const revalidate = 0;

const IN_APP_UA = [/; wv\)/i, /FBAN|FBAV/i, /Instagram/i, /Line\//i, /WhatsApp/i, /TikTok|musical_ly/i];

function escapeHtml(targetUrl) {
  const safe = String(targetUrl).replace(/"/g, '%22');
  return `<!doctype html>
<html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Membuka form ulasan…</title>
<style>body{font-family:system-ui,sans-serif;text-align:center;padding:48px 20px;background:#f5f7fa}
.btn{display:inline-block;margin-top:18px;padding:14px 26px;background:#1a73e8;color:#fff;border-radius:10px;text-decoration:none;font-weight:600;font-size:16px}</style>
</head><body>
<p>Membuka form ulasan Google…</p>
<a class="btn" href="${safe}" target="_blank" rel="noopener">Buka Form Ulasan</a>
<script>window.location.href = "${safe}";</script>
</body></html>`;
}

export async function GET(request, { params }) {
  const code = String(params.code || '').trim();
  const ua = request.headers.get('user-agent') || '';
  const isInApp = IN_APP_UA.some(p => p.test(ua));

  const { data: card } = await proyekita
    .from('cards')
    .select('code,status,write_review_uri,direct_review_url,google_review_url,scans_count')
    .eq('code', code)
    .single();

  if (!card) {
    return new NextResponse('Kode tidak ditemukan', { status: 404 });
  }

  try {
    await proyekita
      .from('cards')
      .update({ scans_count: (card.scans_count || 0) + 1 })
      .eq('code', code);
  } catch (e) {}

  const noCache = { 'Cache-Control': 'no-store, no-cache, must-revalidate, max-age=0' };

  // Prioritas: write_review_uri (App Link) > direct_review_url (legacy) > google_review_url (maps)
  const target = card.write_review_uri || card.direct_review_url || card.google_review_url;

  if (card.status !== 'active' || !target) {
    return NextResponse.redirect(new URL('/activate/' + code, request.url), { status: 307, headers: noCache });
  }

  if (isInApp) {
    return new NextResponse(escapeHtml(target), {
      status: 200,
      headers: { 'Content-Type': 'text/html; charset=utf-8', ...noCache },
    });
  }

  return NextResponse.redirect(target, { status: 307, headers: noCache });
}