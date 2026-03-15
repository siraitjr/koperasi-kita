// lib/api.js
// =========================================================================
// API Helper - Calls Cloud Functions with Firebase Auth Token
// =========================================================================

import { auth } from './firebase';

const BASE_URL = 'https://asia-southeast1-koperasikitagodangulu.cloudfunctions.net';

/**
 * Make authenticated API call to Cloud Function (GET)
 */
async function apiCall(endpoint, params = {}) {
  const user = auth.currentUser;
  if (!user) {
    throw new Error('Belum login');
  }

  // Get fresh ID token
  const idToken = await user.getIdToken(/* forceRefresh */ false);

  // Build URL with query params
  const url = new URL(`${BASE_URL}/${endpoint}`);
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') {
      url.searchParams.append(key, value);
    }
  });

  const response = await fetch(url.toString(), {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${idToken}`,
      'Content-Type': 'application/json',
    },
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({ error: 'Network error' }));
    throw new Error(error.error || `HTTP ${response.status}`);
  }

  return response.json();
}

/**
 * Make authenticated API call to Cloud Function (POST)
 */
async function apiPost(endpoint, body = {}) {
  const user = auth.currentUser;
  if (!user) {
    throw new Error('Belum login');
  }

  const idToken = await user.getIdToken(false);

  const response = await fetch(`${BASE_URL}/${endpoint}`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${idToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({ error: 'Network error' }));
    throw new Error(error.error || `HTTP ${response.status}`);
  }

  return response.json();
}

// =========================================================================
// BUKU POKOK API (existing)
// =========================================================================

/**
 * Get summary data (cabang list, user info)
 */
export async function getSummary() {
  return apiCall('getBukuPokokSummary');
}

/**
 * Get buku pokok data per cabang/admin
 */
export async function getBukuPokok({ cabangId, adminUid, status }) {
  return apiCall('getBukuPokok', { cabangId, adminUid, status });
}

/**
 * Get pembayaran hari ini
 */
export async function getPembayaranHariIni({ cabangId, tanggal }) {
  return apiCall('getPembayaranHariIni', { cabangId, tanggal });
}

// =========================================================================
// KASIR API (baru)
// =========================================================================

/**
 * Get kasir summary (dashboard data saat login)
 */
export async function getKasirSummary() {
  return apiCall('getKasirSummary');
}

/**
 * Get kasir entries (jurnal kasir per bulan)
 */
export async function getKasirEntries({ cabangId, bulan }) {
  return apiCall('getKasirEntries', { cabangId, bulan });
}

/**
 * Add kasir entry (POST - kasir_unit only)
 */
export async function addKasirEntry({ jenis, arah, jumlah, keterangan, tanggal, targetAdminUid, targetAdminName }) {
  return apiPost('addKasirEntry', { jenis, arah, jumlah, keterangan, tanggal, targetAdminUid, targetAdminName });
}

/**
 * Delete kasir entry (kasir_unit only)
 */
export async function deleteKasirEntry({ cabangId, bulan, entryId }) {
  return apiCall('deleteKasirEntry', { cabangId, bulan, entryId });
}

// =========================================================================
// REKAP SNAPSHOT API
// =========================================================================

/**
 * Simpan rekap snapshot harian ke RTDB (idempotent - skip jika sudah ada)
 * Dipanggil fire-and-forget dari BukuRekapScreen saat kasir membuka hari ini.
 */
export async function saveRekapSnapshot({ cabangId, tanggal, snapshot }) {
  return apiPost('saveRekapSnapshot', { cabangId, tanggal, snapshot });
}

/**
 * Baca rekap harian tersimpan (N hari terakhir)
 */
export async function getRekapHarian({ cabangId, limit }) {
  return apiCall('getRekapHarian', { cabangId, limit });
}
