import { NextResponse } from 'next/server';
import { proyekita } from '../../../lib/proyekitaSupabase';

export const dynamic = 'force-dynamic';

function authed(request) {
  const pass = process.env.ENCODE_PASSCODE || '';
  const got = request.headers.get('x-encode-key') || '';
  return pass !== '' && got === pass;
}

export async function GET(request) {
  if (!authed(request)) return NextResponse.json({ error: 'unauthorized' }, { status: 401 });
  const code = new URL(request.url).searchParams.get('code') || '';
  if (code === 'PING') return NextResponse.json({ ok: true, ping: true });
  const { data } = await proyekita
    .from('cards')
    .select('code,status,encoded_at')
    .eq('code', code)
    .single();
  if (!data) return NextResponse.json({ error: 'unknown_code' }, { status: 404 });
  return NextResponse.json({ ok: true, card: data });
}

export async function POST(request) {
  if (!authed(request)) return NextResponse.json({ error: 'unauthorized' }, { status: 401 });
  let body;
  try { body = await request.json(); }
  catch { return NextResponse.json({ error: 'bad_json' }, { status: 400 }); }
  const { code, verified_url } = body || {};
  if (!code || !verified_url) return NextResponse.json({ error: 'incomplete' }, { status: 400 });
  const m = String(verified_url).match(/\/c\/([A-Za-z0-9]+)/);
  if (!m || m[1].toUpperCase() !== String(code).toUpperCase()) {
    return NextResponse.json({ error: 'url_code_mismatch' }, { status: 400 });
  }
  const { data, error } = await proyekita
    .from('cards')
    .update({ encoded_at: new Date().toISOString() })
    .eq('code', code)
    .select('code,encoded_at');
  if (error) return NextResponse.json({ error: 'db_error' }, { status: 500 });
  if (!data || data.length === 0) return NextResponse.json({ error: 'unknown_code' }, { status: 404 });
  return NextResponse.json({ ok: true, encoded_at: data[0].encoded_at });
}