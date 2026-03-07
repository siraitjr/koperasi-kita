// lib/format.js
// =========================================================================
// Format helpers for Rupiah and dates
// =========================================================================

export function formatRp(n) {
  if (n === 0 || n === undefined || n === null) return '-';
  return new Intl.NumberFormat('id-ID').format(n);
}

export function formatRpFull(n) {
  return 'Rp ' + new Intl.NumberFormat('id-ID').format(n || 0);
}

export function formatRpShort(n) {
  if (!n) return 'Rp 0';
  if (n >= 1000000000) return `${(n / 1000000000).toFixed(1)}M`;
  if (n >= 1000000) return `${(n / 1000000).toFixed(1)}Jt`;
  if (n >= 1000) return `${(n / 1000).toFixed(0)}Rb`;
  return String(n);
}

export function formatDate(dateStr) {
  if (!dateStr) return '-';
  // Format: dd-mm-yyyy
  const parts = dateStr.split('-');
  if (parts.length !== 3) return dateStr;
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Ags', 'Sep', 'Okt', 'Nov', 'Des'];
  return `${parts[0]} ${months[parseInt(parts[1]) - 1]} ${parts[2]}`;
}

export function getToday() {
  const now = new Date();
  const jakartaOffset = 7 * 60;
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const jakarta = new Date(utc + (jakartaOffset * 60000));
  const dd = String(jakarta.getDate()).padStart(2, '0');
  const mm = String(jakarta.getMonth() + 1).padStart(2, '0');
  const yyyy = jakarta.getFullYear();
  return `${dd}-${mm}-${yyyy}`;
}
