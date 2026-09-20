import { NextResponse } from 'next/server';
import { proyekita } from '../../../lib/proyekitaSupabase';

export const dynamic = 'force-dynamic';

const DIRECT_FORMAT = 'https://search.google.com/local/writereview?placeid=';

async function probeDirect(placeId) {
  try {
    const res = await fetch(DIRECT_FORMAT + encodeURIComponent(placeId), {
      method: 'GET',
      redirect: 'manual',
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' },
      cache: 'no-store',
    });
    return res.status !== 400;
  } catch (e) {
    return false;
  }
}

export async function POST(request) {
  let body;
  try { body = await request.json(); }
  catch { return NextResponse.json({ error: 'bad_json' }, { status: 400 }); }

  const { code, place_id, business_name, direct_review_url } = body || {};
  if (!code || !place_id) return NextResponse.json({ error: 'incomplete' }, { status: 400 });

  let direct = null;
  if (typeof direct_review_url === 'string' && direct_review_url.trim() !== '') {
    const t = direct_review_url.trim();
    if (t.startsWith('https://g.page/r/') && t.endsWith('/review')) direct = t;
    else return NextResponse.json({ error: 'bad_direct_link' }, { status: 400 });
  }

  const pid = String(place_id);
  const maps_url = 'https://www.google.com/maps/place/?q=place_id:' + encodeURIComponent(pid);
  const directOk = direct ? false : await probeDirect(pid);
  const review_url = directOk ? DIRECT_FORMAT + encodeURIComponent(pid) : maps_url;

  const { data, error } = await proyekita
    .from('cards')
    .update({
      status: 'active',
      place_id: pid,
      business_name: String(business_name || '').slice(0, 200),
      google_review_url: review_url,
      direct_review_url: direct,
    })
    .eq('code', code)
    .eq('status', 'inactive')
    .select('code,status,google_review_url,direct_review_url');

  if (error) return NextResponse.json({ error: 'db_error' }, { status: 500 });
  if (!data || data.length === 0) {
    return NextResponse.json({ error: 'already_active_or_unknown' }, { status: 409 });
  }
  return NextResponse.json({
    ok: true,
    review_url: direct || review_url,
    mode: direct || directOk ? 'direct' : 'maps',
  });
}