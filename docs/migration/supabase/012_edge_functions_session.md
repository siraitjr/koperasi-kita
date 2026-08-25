# Migrasi Sesi & Impersonasi — Cloud Functions → Edge Function

Tahap A (`010` §4). **Belum di-deploy, belum dijalankan.**

Berkas: `supabase/functions/session-management/index.ts`,
`docs/migration/supabase/011_session_takeover.sql`.

| Callable/HTTP lama | `action` | Asal |
|---|---|---|
| `generateAutoLoginToken` | `autoLogin` | `generateAutoLoginToken.js:20` |
| `generateTakeoverToken` | `takeover` | `remoteTakeover.js:82` |
| `restorePimpinanSession` | `restore` | `remoteTakeover.js:189` |

---

## 0. Catatan Urutan

Anda menyebut ini "Tahap A". Dalam `010` §4 ketiganya justru ada di **Tahap E
— paling sensitif**, karena inilah satu-satunya kemampuan di sistem yang
membuat satu orang bertindak atas nama orang lain. Saya kerjakan sesuai
perintah Anda; catatan ini hanya supaya urutan risikonya tidak hilang dari
pandangan. Konsekuensi praktis: **jangan pindahkan sakelar produksi untuk
ketiga fungsi ini sebelum §5 diuji lengkap**, termasuk uji penolakan.

Aturan tanpa-email (`008` §0) dipatuhi: tidak ada `resetPasswordForEmail`,
`inviteUserByEmail`, maupun `signInWithOtp` di berkas ini.

---

## 1. Perbedaan Mendasar: Tidak Ada `createCustomToken`

Ketiga fungsi Firebase bertumpu pada
`admin.auth().createCustomToken(uid)` + `signInWithCustomToken()`.
**Supabase Auth tidak punya padanannya.**

Penggantinya:

```
Edge Function : auth.admin.generateLink({ type:'magiclink', email })
                  → properties.hashed_token
Klien         : auth.verifyOtp({ token_hash, type:'magiclink' })
                  → sesi aktif
```

`generateLink` **membuat** token dan mengembalikannya lewat respons HTTP —
Supabase tidak mengirim surat apa pun, jadi domain email fiktif tidak jadi
masalah (`008` §0).

**Dua perbedaan sifat yang harus disadari:**

| | Firebase custom token | Supabase `token_hash` |
|---|---|---|
| Umur | 1 jam | pendek (mengikuti setelan OTP project) |
| Pemakaian | berulang | **sekali pakai** |

Sekali-pakai itu lebih ketat, tetapi berarti klien **tidak boleh menyimpan**
token ini untuk dipakai lagi. Kalau `verifyOtp` gagal, mintalah token baru —
jangan coba ulang dengan token yang sama.

---

## 2. Prasyarat SQL

Jalankan **`011_session_takeover.sql`** lebih dulu. Urutan keseluruhan:

```
001 → 001a → 002 → 007 → 009 → 011
```

Isinya tiga tabel — `session_lock`, `force_logout`, dan `takeover_log`
(tabel baru, tidak ada padanannya di RTDB). Ketiganya **tanpa hak tulis
untuk klien**: hanya Edge Function lewat `service_role`. Kalau klien bisa
menulis `session_lock`, admin mana pun bisa mengunci admin lain; kalau bisa
menghapus `force_logout` miliknya, ia bisa menolak diusir keluar.

---

## 3. Perilaku yang Dipertahankan 1:1

| Perilaku | Asal | Di Edge Function |
|---|---|---|
| Hanya Pimpinan boleh takeover | `:104` | cek `role = 'pimpinan'` |
| Target harus ada | `:110` | lookup `app_user` |
| Cabang target harus cabang pimpinan | `:124-130` | `cabang_id` + tabel `cabang.pimpinan_id` |
| Re-takeover oleh pimpinan yang SAMA diizinkan | `:136-145` | `lock.locked_by === p.id` |
| Blokir bila dikunci pimpinan LAIN | `:139-143` | `409 already-exists`, pesan menyebut namanya |
| Tulis `force_logout` saat takeover | `:167-171` | tabel `force_logout` |
| Bersihkan kunci saat restore | `:221-223` | `status='released'` + hapus `force_logout` |

Kosakata kode galat (`permission-denied`, `not-found`, `invalid-argument`,
`already-exists`, `unauthenticated`) dipertahankan sama seperti `HttpsError`,
karena Android memilih pesan Indonesia dengan mencocokkan string itu.

### 3.1 Tiga hal yang SENGAJA diperketat

Ini penyimpangan dari perilaku lama. Disebut di sini supaya tidak dikira
kelalaian.

**(a) Sumber wewenang tunggal.** `findPimpinanCabang` (`:10-52`) mencoba
**tiga** sumber berurutan: `metadata/cabang` → `metadata/admins/role` →
`metadata/roles/pimpinan`. Tiga sumber ada karena RTDB tidak punya tempat
yang otoritatif. Di Postgres cukup `app_user.role` + `cabang.pimpinan_id`.

**(b) Pimpinan tanpa cabang tidak lagi bisa takeover siapa pun.**
`remoteTakeover.js:126` berbunyi `if (!isSameCabang && allPimpinanCabang.size > 0)`
— artinya kalau pimpinan **tidak punya cabang sama sekali**, pemeriksaan
cabang dilewati dan ia bisa mengambil alih **admin mana pun di seluruh
koperasi**. Kelonggaran itu tidak dibawa: tanpa cabang, tidak ada wewenang.

**(c) `restore` tidak lagi memercayai `pimpinanUid` dari pemanggil.**
Versi lama menerimanya sebagai parameter (`:197`) lalu membuat token untuk
uid itu, dengan satu-satunya pemeriksaan `lockedBy === pimpinanUid`.
Masalahnya: saat `restore` dipanggil, pemanggil sedang masuk **sebagai
admin**. Siapa pun yang memegang sesi admin bisa menebak uid pimpinan dan
memperoleh sesinya. Di sini pimpinan diambil **dari `session_lock`**, bukan
dari body — satu-satunya sumber yang sah.

Satu akibat dari (c): kalau `session_lock` tidak ada (mis. sudah terlanjur
dihapus manual), `restore` sekarang **gagal** dengan `not-found`, sedangkan
versi lama tetap melanjutkan (`:206-209`). Pemulihannya: Pimpinan login
biasa dengan akunnya sendiri. Itu lebih baik daripada menyediakan jalur yang
bisa disalahgunakan.

### 3.2 Tambahan: jejak permanen

`session_lock` dihapus saat sesi dikembalikan, sehingga di RTDB **tidak ada
jejak** bahwa takeover pernah terjadi — tidak ada cara menjawab "siapa
pernah masuk sebagai admin X, kapan, berapa lama". Untuk kemampuan sesensitif
ini itu kekurangan nyata, jadi ditambahkan `takeover_log` (append-only,
tidak bisa diubah/dihapus pelakunya).

---

## 4. CARA DEPLOY

Semua dijalankan **di laptop Anda**. Saya tidak bisa melakukannya dari sisi
saya — tidak ada Supabase CLI maupun akses ke project Anda.

### 4.1 Pasang Supabase CLI (sekali saja)

```bash
# --- macOS / Linux (Homebrew) ---
brew install supabase/tap/supabase

# --- Windows (Scoop) ---
scoop bucket add supabase https://github.com/supabase/scoop-bucket.git
scoop install supabase

# --- alternatif lintas-OS, tanpa install global ---
npx supabase --version
```

> `npm install -g supabase` **tidak didukung** oleh Supabase. Pakai salah
> satu cara di atas.

Verifikasi:

```bash
supabase --version
```

### 4.2 Login & hubungkan project (sekali saja)

```bash
supabase login
# membuka browser → tempel access token dari
# https://supabase.com/dashboard/account/tokens

cd /path/ke/koperasi-kita       # folder yang memuat supabase/functions/
supabase link --project-ref <PROJECT_REF>
```

`<PROJECT_REF>` adalah bagian subdomain URL project Anda:
`https://`**`abcdefghijklmno`**`.supabase.co` → `abcdefghijklmno`.
Bisa juga dilihat di Dashboard → Settings → General → Reference ID.

### 4.3 Jalankan SQL prasyarat

Dashboard → SQL Editor → tempel isi **`011_session_takeover.sql`** → Run.
Lalu jalankan blok VERIFIKASI di bagian bawah berkas itu.

### 4.4 Deploy

```bash
supabase functions deploy session-management
```

`SUPABASE_URL` dan `SUPABASE_SERVICE_ROLE_KEY` **otomatis tersedia** di
runtime Edge Function — tidak perlu di-set manual, dan jangan pernah
menaruhnya di berkas yang ikut ter-commit.

Cek fungsi sudah naik:

```bash
supabase functions list
```

---

## 5. CARA MENGUJI SETELAH DEPLOY

Siapkan dua nilai:

```bash
export SUPA_URL="https://<PROJECT_REF>.supabase.co"
export ANON="<anon key — Dashboard → Settings → API>"
```

### 5.1 Ambil JWT user sungguhan

Password Pengawas sudah Anda ganti manual, jadi:

```bash
curl -s -X POST "$SUPA_URL/auth/v1/token?grant_type=password" \
  -H "apikey: $ANON" -H "Content-Type: application/json" \
  -d '{"email":"pengawas@godangulu.com","password":"<password-pengawas>"}'
```

Ambil `access_token` dari respons:

```bash
export JWT_PENGAWAS="<access_token>"
```

Untuk uji takeover Anda butuh **JWT Pimpinan**, bukan Pengawas — ulangi
langkah di atas dengan akun pimpinan (set dulu passwordnya lewat Edge
Function `user-management`, atau lewat Dashboard).

```bash
export JWT_PIMPINAN="<access_token pimpinan>"
```

### 5.2 autoLogin

```bash
curl -s -X POST "$SUPA_URL/functions/v1/session-management" \
  -H "apikey: $ANON" \
  -H "Authorization: Bearer $JWT_PENGAWAS" \
  -H "Content-Type: application/json" \
  -d '{"action":"autoLogin"}'
```

Harapan: `{"success":true,"token_hash":"...","email":"...","uid":"..."}`

### 5.3 takeover (pakai JWT Pimpinan)

```bash
curl -s -X POST "$SUPA_URL/functions/v1/session-management" \
  -H "apikey: $ANON" \
  -H "Authorization: Bearer $JWT_PIMPINAN" \
  -H "Content-Type: application/json" \
  -d '{"action":"takeover","targetAdminUid":"<uuid admin lapangan di cabang yang sama>"}'
```

Harapan: `{"success":true,"token_hash":"...","adminName":"..."}`

Ambil uuid admin-nya dari SQL Editor:

```sql
select u.id, u.nama, u.email, u.cabang_id
  from koperasi.app_user u
 where u.role = 'admin' and u.cabang_id = '<cabang pimpinan>';
```

### 5.4 UJI PENOLAKAN — jangan dilewati

Fungsi ini berjalan dengan `service_role` yang **mem-bypass RLS
sepenuhnya**. Satu-satunya yang menahan penyalahgunaan adalah pemeriksaan
di dalam badan fungsi. Kalau pemeriksaan itu salah, tidak ada lapisan kedua.

```bash
# (a) JWT admin biasa mencoba takeover → harus 403 permission-denied
curl -s -X POST "$SUPA_URL/functions/v1/session-management" \
  -H "apikey: $ANON" -H "Authorization: Bearer $JWT_ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"action":"takeover","targetAdminUid":"<uuid admin lain>"}'

# (b) Pimpinan mengambil admin CABANG LAIN → harus 403
#     "Admin bukan bagian dari cabang Anda."

# (c) Tanpa Authorization → harus 401 unauthenticated
curl -s -X POST "$SUPA_URL/functions/v1/session-management" \
  -H "apikey: $ANON" -H "Content-Type: application/json" \
  -d '{"action":"autoLogin"}'

# (d) Takeover kedua oleh pimpinan LAIN atas admin yang sama → harus 409
#     "Akun admin sedang digunakan oleh <nama>."
```

Keempatnya **harus** ditolak. Kalau ada satu saja yang lolos, hentikan dan
laporkan — jangan lanjut ke penggantian transport di aplikasi.

### 5.5 Verifikasi efek samping di database

```sql
select * from koperasi.session_lock;                      -- status 'active'
select * from koperasi.force_logout;                      -- ada baris target
select * from koperasi.takeover_log order by created_at desc limit 5;
```

### 5.6 restore

```bash
curl -s -X POST "$SUPA_URL/functions/v1/session-management" \
  -H "apikey: $ANON" -H "Authorization: Bearer $JWT_PIMPINAN" \
  -H "Content-Type: application/json" \
  -d '{"action":"restore","adminUid":"<uuid admin tadi>"}'
```

Sesudahnya: `session_lock.status = 'released'`, baris `force_logout` hilang,
dan `takeover_log` bertambah satu baris `aksi='restore'`.

### 5.7 Menukar `token_hash` jadi sesi (yang dilakukan klien)

```bash
curl -s -X POST "$SUPA_URL/auth/v1/verify" \
  -H "apikey: $ANON" -H "Content-Type: application/json" \
  -d '{"type":"magiclink","token_hash":"<token_hash dari respons>"}'
```

Respons berisi `access_token` + `refresh_token` — itulah sesinya. Ingat:
**sekali pakai**.

### 5.7b Kalau dapat 401/403 — pakai aksi `diag` lebih dulu

```bash
curl -s -X POST "$SUPA_URL/functions/v1/session-management" \
  -H "apikey: $ANON" -H "Authorization: Bearer $JWT_PENGAWAS" \
  -H "Content-Type: application/json" \
  -d '{"action":"diag"}'
```

Balasannya menjawab pertanyaan yang tidak bisa dijawab kode galat saja:
apakah JWT-nya sah menurut GoTrue, apakah `koperasi.app_user` **terbaca**,
berapa barisnya, dan dari mana peran pemanggil diambil
(`app_user` atau `user_metadata`).

Membaca hasilnya:

| Gejala | Artinya |
|---|---|
| `jwtSah:false` | Token memang ditolak GoTrue — ambil ulang lewat `/auth/v1/token`. |
| `jwtSah:true`, `appUserTerbaca:false` | **Paling sering.** Schema `koperasi` belum terdaftar di Dashboard → Settings → API → **Exposed schemas**. Tambahkan `koperasi`, simpan, ulangi. |
| `jwtSah:true`, `appUserTerbaca:true`, `identitas.sumber:"user_metadata"` | Barisnya belum ada untuk uid ini — periksa `select * from koperasi.app_user where id='<uid>'`. |
| `identitas.gagal:"tanpa_peran"` | Peran tidak ada di `app_user` maupun `user_metadata`. |

Sejak perbaikan ini, **403 `permission-denied` ≠ 401 `unauthenticated`**:
401 berarti tokennya benar-benar ditolak, 403 berarti tokennya sah tetapi
peran/profilnya tidak terbaca. Versi pertama menyatukan keduanya jadi 401,
dan itu menyesatkan.

### 5.8 Melihat log

```bash
supabase functions logs session-management
```

Atau Dashboard → Edge Functions → `session-management` → Logs.

---

## 6. Rollback

Cloud Functions lama **tidak dihapus** dan tetap ter-deploy. Aplikasi juga
belum diubah — `app/` tidak disentuh pada tahap ini, jadi Android masih
memanggil `getHttpsCallable("generateTakeoverToken")` seperti biasa.

Artinya: **men-deploy Edge Function ini tidak mengubah apa pun di produksi.**
Ia hanya ada dan menunggu. Rollback = tidak melakukan apa-apa; kalau ingin
bersih, `supabase functions delete session-management`.

---

## 6b. Catatan Perbaikan (13 Agu 2026)

Deploy pertama mengembalikan 401 untuk JWT yang sah. **Bukan** karena
verifikasi manual HS256 — fungsi ini sejak awal memakai
`admin.auth.getUser(jwt)`, yang diverifikasi GoTrue di sisi server dan
karena itu independen dari algoritma (ES256 maupun HS256 sama saja).

Penyebabnya rancangan `pemanggil()` yang lama: ia mengembalikan `null` untuk
**tiga** kegagalan berbeda — header kosong, JWT ditolak, dan baris
`app_user` tidak terbaca — dan pemanggilnya memetakan ketiganya ke 401
`unauthenticated`. Jadi kegagalan PostgREST (kemungkinan besar schema
`koperasi` belum di-expose) tampil sebagai "token tidak valid".

Yang diperbaiki:
- ketiga sebab dibedakan; `tanpa_peran` kini **403**, bukan 401;
- `user_metadata` dipakai sebagai cadangan bila `app_user` tidak terbaca,
  sehingga kegagalan infrastruktur tidak menyamar jadi kegagalan token;
- `console.log/warn/error` di tiap langkah — sebelumnya tidak ada satu pun
  log, itulah kenapa Dashboard hanya menampilkan booted/shutdown;
- aksi `diag` (§5.7b).

Seluruh pemeriksaan §3 dan §3.1 tidak berubah, begitu juga kosakata kode
galat.

---

## 7. Batas Jujur

- Edge Function ini **belum pernah di-deploy maupun dijalankan**. Tidak ada
  Deno, Supabase CLI, atau akses project di environment tempat ia ditulis.
- `011_session_takeover.sql` **belum pernah dijalankan**.
- Bentuk `properties.hashed_token` dari `generateLink` saya ambil dari
  kontrak `@supabase/supabase-js@2`; belum diverifikasi terhadap respons
  sungguhan. Kalau nama fieldnya berbeda pada versi yang ter-deploy,
  `buatTokenSesi()` adalah satu-satunya tempat yang perlu disesuaikan —
  sengaja dipusatkan di sana.
- Sisi Android belum ada: `verifyOtp` menggantikan `signInWithCustomToken`,
  dan itu perubahan `app/` yang belum dikerjakan.
