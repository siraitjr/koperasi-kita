import { NextResponse } from 'next/server';
import { proyekita } from '../../../lib/proyekitaSupabase';

export const dynamic = 'force-dynamic';
export const revalidate = 0;

export async function GET(request, { params }) {
  const code = String(params.code || '').trim();

  const { data: card } = await proyekita
    .from('cards')
    .select('code,status,google_review_url,direct_review_url,scans_count')
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

  const target = card.direct_review_url || card.google_review_url;

  if (card.status === 'active' && target) {
    return NextResponse.redirect(target, {
      status: 307,
      headers: { 'Cache-Control': 'no-store, no-cache, must-revalidate, max-age=0' }
    });
  }
  return NextResponse.redirect(new URL('/activate/' + code, request.url), {
    status: 307,
    headers: { 'Cache-Control': 'no-store, no-cache, must-revalidate, max-age=0' }
  });
}