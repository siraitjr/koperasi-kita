import { notFound } from 'next/navigation';
import { proyekita } from '../../../lib/proyekitaSupabase';
import ActivateClient from '../activate-client';

export const dynamic = 'force-dynamic';

export default async function ActivatePage({ params }) {
  const code = String(params.code || '').trim();

  const { data: card } = await proyekita
    .from('cards')
    .select('code,status,business_name')
    .eq('code', code)
    .single();

  if (!card) notFound();

  return (
    <ActivateClient
      code={card.code}
      initialStatus={card.status}
      businessName={card.business_name || ''}
    />
  );
}