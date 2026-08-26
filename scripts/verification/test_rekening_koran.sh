#!/usr/bin/env bash
# =========================================================================
# UJI B-5 — Edge Function rekening-koran
# =========================================================================
# Menguji tiga hal yang diminta, plus tiga yang mudah terlewat:
#
#   1. tanda tangan sah          → 200
#   2. kedaluwarsa               → 410
#   3. tanda tangan palsu        → 401
#   4. token diubah isinya       → 401   (bukan 200 dengan data nasabah lain)
#   5. token v1 sesudah pensiun  → 410
#   6. tanpa token / sampah      → 400
#
# Skrip ini TIDAK menyentuh database dan TIDAK memasang apa pun. Ia hanya
# membuat token lalu memanggil endpoint.
#
# PEMAKAIAN
#   export RK_URL='https://<ref>.supabase.co/functions/v1/rekening-koran'
#   export RK_KEY='<isi REKENING_KORAN_HMAC_KEY>'      # kunci v2
#   export RK_ADMIN_UID='<legacy_admin_uid nasabah uji>'
#   export RK_PELANGGAN_ID='<legacy_pelanggan_id nasabah uji>'
#   # opsional, untuk menguji jalur warisan:
#   export RK_V1_KEY='<isi REKENING_KORAN_V1_KEY>'
#   ./scripts/verification/test_rekening_koran.sh
#
# ⚠ Kunci diberikan lewat ENV, tidak pernah sebagai argumen: argumen
#   terlihat di `ps` dan tersimpan di riwayat shell.
# =========================================================================
set -uo pipefail

: "${RK_URL:?set RK_URL}"
: "${RK_KEY:?set RK_KEY}"
: "${RK_ADMIN_UID:?set RK_ADMIN_UID}"
: "${RK_PELANGGAN_ID:?set RK_PELANGGAN_ID}"

LULUS=0; GAGAL=0

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
hmac_hex() { printf '%s' "$1" | openssl dgst -sha256 -hmac "$2" -hex | sed 's/.*= *//'; }

token_v2() { # $1=exp
  local p="v2:${RK_ADMIN_UID}:${RK_PELANGGAN_ID}:$1"
  printf '%s:%s' "$p" "$(hmac_hex "$p" "$RK_KEY")" | b64url
}
token_v1() { # $1=timestamp ms
  local p="${RK_ADMIN_UID}:${RK_PELANGGAN_ID}:$1"
  printf '%s:%s' "$p" "$(hmac_hex "$p" "${RK_V1_KEY:-}" | cut -c1-16)" | b64url
}

periksa() { # $1=nama $2=harapan $3=token
  local kode
  kode=$(curl -s -o /tmp/rk_body.$$ -w '%{http_code}' "${RK_URL}?t=$3")
  if [ "$kode" = "$2" ]; then
    printf '  ✓ %-42s %s\n' "$1" "$kode"; LULUS=$((LULUS+1))
  else
    printf '  ✗ %-42s dapat %s, harap %s\n' "$1" "$kode" "$2"; GAGAL=$((GAGAL+1))
    sed -n '1p' /tmp/rk_body.$$ | cut -c1-160 | sed 's/^/      /'
  fi
  rm -f /tmp/rk_body.$$
}

SEKARANG=$(date +%s)
echo "▶ Menguji ${RK_URL}"
echo
echo "v2 — jalur baru"
periksa "tanda tangan sah, belum kedaluwarsa"  200 "$(token_v2 $((SEKARANG + 3600)))"
periksa "sudah kedaluwarsa"                     410 "$(token_v2 $((SEKARANG - 60)))"

# Tanda tangan palsu: payload benar, tanda tangan diacak. HARUS 401, dan
# HARUS BUKAN 410 — kalau server menjawab 410 di sini, berarti ia memeriksa
# exp SEBELUM tanda tangan, dan itu memberi tahu penempa bahwa tanda
# tangannya sudah benar.
PALSU="v2:${RK_ADMIN_UID}:${RK_PELANGGAN_ID}:$((SEKARANG + 3600))"
periksa "tanda tangan palsu" 401 \
  "$(printf '%s:%s' "$PALSU" "$(printf '0%.0s' {1..64})" | b64url)"
periksa "tanda tangan palsu + sudah kedaluwarsa" 401 \
  "$(printf 'v2:%s:%s:%s:%s' "$RK_ADMIN_UID" "$RK_PELANGGAN_ID" $((SEKARANG - 60)) \
      "$(printf '0%.0s' {1..64})" | b64url)"

# Isi diubah, tanda tangan lama dipakai ulang — uji bahwa exp dan id
# benar-benar ikut ditandatangani, bukan sekadar menempel di token.
SAH_EXP=$((SEKARANG + 3600))
SAH_SIG=$(hmac_hex "v2:${RK_ADMIN_UID}:${RK_PELANGGAN_ID}:${SAH_EXP}" "$RK_KEY")
periksa "exp diperpanjang, tanda tangan lama" 401 \
  "$(printf 'v2:%s:%s:%s:%s' "$RK_ADMIN_UID" "$RK_PELANGGAN_ID" $((SAH_EXP + 999999)) "$SAH_SIG" | b64url)"
periksa "pelangganId ditukar, tanda tangan lama" 401 \
  "$(printf 'v2:%s:%s:%s:%s' "$RK_ADMIN_UID" "nasabah-lain" "$SAH_EXP" "$SAH_SIG" | b64url)"

echo
echo "Bentuk token cacat"
periksa "bukan base64"        400 "%%%bukan-base64%%%"
periksa "base64 tapi ngawur"  400 "$(printf 'halo:dunia' | b64url)"

if [ -n "${RK_V1_KEY:-}" ]; then
  echo
  echo "v1 — jalur warisan (hanya selama masa transisi)"
  periksa "v1 sah, masih dalam TTL"  200 "$(token_v1 $(( (SEKARANG - 3600) * 1000 )))"
  periksa "v1 sah, lewat TTL"        410 "$(token_v1 $(( (SEKARANG - 400*86400) * 1000 )))"
  periksa "v1 bertanggal masa depan" 410 "$(token_v1 $(( (SEKARANG + 400*86400) * 1000 )))"
  echo "  · Sesudah REKENING_KORAN_V1_UNTIL lewat, ketiganya HARUS jadi 410."
  echo "    Ulangi skrip ini pada hari pensiun untuk membuktikannya."
fi

echo
echo "▶ lulus=${LULUS} gagal=${GAGAL}"
[ "$GAGAL" -eq 0 ] || exit 1

cat <<'CATATAN'

Yang TIDAK diuji skrip ini, dan harus diperiksa manual:
  · Isi datanya benar — bandingkan angka dengan endpoint lama untuk nasabah
    yang sama (020a §VERIFIKASI no. 4).
  · NIK tersamar, dan tidak ada kunci `alamat`/`hari`/`status` di respons.
  · Halaman public/rk.html masih tampil utuh dengan respons baru.
CATATAN
