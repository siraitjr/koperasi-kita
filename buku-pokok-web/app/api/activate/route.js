import { NextResponse } from 'next/server';
import { proyekita } from '../../../lib/proyekitaSupabase';

export const dynamic = 'force-dynamic';

const DIRECT_FORMAT_LEGACY = 'https://search.google.com/local/writereview?placeid=';

async function fetchWriteReviewUri(placeId) {
  try {
    const key = process.env.GOOGLE_PLACES_KEY;
    if (!key) return null;
    const res = await fetch(`https://places.googleapis.com/v1/places/${placeId}`, {
      headers: {
        'X-Goog-Api-Key': key,
        'X-Goog-FieldMask': 'googleMapsLinks.writeAReviewUri',
      },
      cache: 'no-store',
    });
    if (!res.ok) return null;
    const data = await res.json();
    return data?.googleMapsLinks?.writeAReviewUri || null;
  } catch (e) {
    return null;
  }
}

export async function POST(request) {
  let body;
  try { body = await request.json(); }
  catch { return NextResponse.json({ error: 'bad_json' }, { status: 400 }); }

  const { code, place_id, business_name } = body || {};
  if (!code || !place_id) return NextResponse.json({ error: 'incomplete' }, { status: 400 });

  const pid = String(place_id);
  const writeUri = await fetchWriteReviewUri(pid);
  const legacyUri = DIRECT_FORMAT_LEGACY + encodeURIComponent(pid);
  const mapsUrl = 'https://www.google.com/maps/place/?q=place_id:' + encodeURIComponent(pid);

  const { data, error } = await proyekita
    .from('cards')
    .update({
      status: 'active',
      place_id: pid,
      business_name: String(business_name || '').slice(0, 200),
      write_review_uri: writeUri,
      direct_review_url: legacyUri,
      google_review_url: mapsUrl,
    })
    .eq('code', code)
    .eq('status', 'inactive')
    .select('code,status,write_review_uri,direct_review_url,google_review_url');

  if (error) return NextResponse.json({ error: 'db_error' }, { status: 500 });
  if (!data || data.length === 0) {
    return NextResponse.json({ error: 'already_active_or_unknown' }, { status: 409 });
  }

  const review_url = writeUri || legacyUri || mapsUrl;
  return NextResponse.json({ ok: true, review_url });
}