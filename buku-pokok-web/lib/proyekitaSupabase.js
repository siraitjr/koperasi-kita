import { createClient } from '@supabase/supabase-js';

// Client khusus database Proyekita (tabel cards).
// HANYA boleh di-import dari file server (route handler / server component).
// Jangan pernah di-import dari komponen client.
export const proyekita = createClient(
  process.env.PROYEKITA_SUPABASE_URL,
  process.env.PROYEKITA_SUPABASE_SERVICE_KEY
);