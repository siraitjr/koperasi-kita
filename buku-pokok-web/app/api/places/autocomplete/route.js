import { NextResponse } from 'next/server';

export const dynamic = 'force-dynamic';

export async function GET(request) {
  const q = new URL(request.url).searchParams.get('q') || '';
  if (q.trim().length < 3) return NextResponse.json({ places: [] });

  const key = process.env.GOOGLE_PLACES_KEY;
  if (!key) return NextResponse.json({ error: 'key_missing' }, { status: 500 });

  const res = await fetch('https://places.googleapis.com/v1/places:autocomplete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': key,
      'X-Goog-FieldMask': 'suggestions.placePrediction.placeId,suggestions.placePrediction.text',
    },
    body: JSON.stringify({ input: q, languageCode: 'id' }),
    cache: 'no-store',
  });

  if (!res.ok) return NextResponse.json({ error: 'places_error' }, { status: 502 });

  const json = await res.json();
  const places = (json.suggestions || [])
    .map(s => s.placePrediction)
    .filter(Boolean)
    .map(p => ({ id: p.placeId, name: (p.text && p.text.text) || '' }))
    .slice(0, 6);

  return NextResponse.json({ places });
}