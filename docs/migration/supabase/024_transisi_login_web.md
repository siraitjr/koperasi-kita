# 024 — Transisi Login Web ke Supabase

D-5 · 27 Agustus 2026. Bagian dari `021` §5 / LANGKAH 2.3.

Tidak ada yang saya deploy. `lib/firebase.js`, `lib/api.js`, dan halaman
login lama tidak disentuh.

---

## 1. Keputusan: urutkan fungsi lebih dulu, jangan dua sesi

Anda menawarkan dua pilihan dan meminta yang risikonya terendah. Keduanya
saya periksa terhadap kode, dan **keduanya tidak sama-sama layak.**

### Kenapa "dua sesi berdampingan" tidak bisa dipakai

Ia menuntut satu formulir menghasilkan sesi Firebase **dan** sesi Supabase
dari satu kata sandi. Itu hanya mungkin bila kata sandi kedua sistem sama —
dan **tidak sama**:

- Kata sandi Firebase adalah milik staf sejak lama, tersimpan ter-hash.
  **Tidak ada yang tahu nilainya**, termasuk Pengawas.
- Kata sandi Supabase dibangkitkan ulang oleh Pengawas (`021` §5).

Menyamakannya berarti **mereset kata sandi Firebase seluruh staf di
produksi** pada minggu terakhir sebelum cutoff — memakai
`resetUserPassword` yang justru sedang ditinggalkan. Menambah perubahan
produksi berisiko demi jembatan yang umurnya empat hari adalah tukar-tambah
yang buruk.

Alternatifnya, dua formulir kata sandi terpisah — dan itu memindahkan
kebingungan ke staf, tepat di minggu mereka paling tidak punya toleransi.

### Kenapa mengganti login hari ini merusak semuanya

`/pembukuan` mengambil peran dan daftar cabang **bukan** dari Firebase Auth,
melainkan dari Cloud Function:

```js
// app/pembukuan/page.js:84-90
const unsubscribe = onAuthStateChanged(auth, async (firebaseUser) => {
  if (firebaseUser) {
    setUser(firebaseUser);
    const result = await getSummary();          // ← Cloud Function
    setUserData(result.data.user);              // ← peran ada DI SINI
```

`getSummary()` → `lib/api.js:15` menolak bila `auth.currentUser` kosong
(*"Belum login"*). Jadi begitu login pindah ke Supabase, sesi Firebase tidak
pernah terbentuk, `getSummary()` gagal, `userData` tetap null — dan
**seluruh halaman kosong**. Hal yang sama berlaku untuk `/kasir`
(`getKasirSummary()`, `:553`) dan `/buku-perkembangan`.

### Keputusan

> **Bangun jalur Supabase sekarang sebagai rute terpisah yang bisa diuji
> penuh; balikkan login `/pembukuan` SETELAH 13 fungsi `lib/api.js` pindah
> (D-4).**

Ini membalik urutan di `021` (login D-5, fungsi D-4) — dan urutan itu memang
keliru. Ongkosnya nol: pekerjaan D-5 tetap selesai hari ini dan teruji, hanya
sakelarnya yang dibalik sehari kemudian, saat membaliknya **tidak lagi
merusak apa pun**.

Yang penting: **tidak ada keadaan setengah jadi.** Sebelum pembalikan,
produksi berjalan penuh di Firebase; sesudahnya, penuh di Supabase.

---

## 2. Berkas yang dibuat

| Berkas | Isi |
|---|---|
| `buku-pokok-web/lib/authSupabase.js` | `masuk`, `keluar`, `penggunaSekarang`, `profilSaya`, `gantiSandi`, `pantauSesi` |
| `buku-pokok-web/app/login-supabase/page.js` | rute uji `/login-supabase` |

Tidak ada berkas lama yang diubah. Tidak ada berkas lama yang mengimpor
keduanya, jadi `npm run dev` berjalan persis seperti sebelumnya.

### Peran dibaca dari database, bukan dari JWT

`profilSaya()` membaca `koperasi.app_user` (`select … where id = auth.uid()`),
bukan `auth.jwt() -> user_metadata`. Alasannya sama dengan yang menutup usul
JWT di `017` LAMPIRAN: `user_metadata` **dapat ditulis sendiri oleh
penggunanya** lewat `auth.updateUser({data})`, jadi peran dari sana bisa
dikarang. Baris `app_user` hanya dapat diubah Pengawas.

RLS `app_user_baca` (`002:170`) mengizinkan `id = auth.uid()`, jadi
pembacaan ini sah tanpa hak istimewa.

Dua keadaan ditolak eksplisit, karena keduanya menghasilkan halaman yang
tampak "kosong" alih-alih "tidak berhak":

- **Ada di Auth, tidak ada di `app_user`** — terjadi bila akun dibuat langsung
  di dasbor Supabase, bukan lewat Edge Function `user-management`. Tanpa
  baris itu tidak ada peran dan tidak ada cabang, jadi seluruh RLS
  menyembunyikan semuanya.
- **`aktif = false`.**

---

## 3. Cara menguji di localhost

```bash
cd buku-pokok-web
npm run dev
# buka http://localhost:3000/login-supabase
```

Masuk dengan `kaspan@godangulu.com`. Kata sandinya **tidak ditulis di repo
mana pun** — yang ter-commit tetap ada di riwayat git selamanya (`006` §3.6).

| Uji | Harapan |
|---|---|
| email + sandi benar | tampil nama, **peran `kasir_unit`**, cabang, `auth.uid()` |
| sandi salah | *"Email atau kata sandi salah."* |
| email tidak terdaftar | pesan **sama persis** — lihat catatan di bawah |
| muat ulang halaman | tetap masuk (sesi bertahan) |
| tombol Keluar | kembali ke formulir |
| `/pembukuan` di tab lain | **tetap berjalan normal dengan Firebase** |

Baris terakhir itu yang paling penting hari ini: ia membuktikan kedua sesi
berdampingan tanpa saling ganggu — `storageKey: 'koperasi-kita-auth'`
(`lib/supabaseClient.js`) memisahkan penyimpanannya dari Firebase.

> Pesan untuk "sandi salah" dan "email tidak terdaftar" **sengaja sama**.
> GoTrue mengembalikan *Invalid login credentials* untuk keduanya agar tidak
> membocorkan email mana yang terdaftar. Jangan dipecah jadi dua pesan
> berbeda "demi kejelasan".

### Sebelum menguji, pastikan tiga hal

1. `.env.local` terisi dan `npm run dev` **di-restart** sesudahnya — Next.js
   hanya membacanya saat proses dimulai.
2. `@supabase/supabase-js` ada di `package.json` **dan ter-commit**. ✅ Sudah
   — `5b9a4c4` menambahkannya (`^2.112.4`) beserta `package-lock.json`.
   Celah yang saya tandai sebelumnya sudah tertutup.
3. Schema `koperasi` ada di **Settings → API → Exposed schemas** (`013` §3).
   Tanpa itu `profilSaya()` gagal walau login berhasil — gejalanya login
   sukses lalu langsung ter-logout dengan pesan profil.

---

## 4. Pembalikan (D-4, SETELAH 13 fungsi pindah)

Satu berkas, empat suntingan. Jangan dikerjakan sebelum
`getSummary()`/`getKasirSummary()` benar-benar berjalan di Supabase.

```diff
  // app/pembukuan/page.js:9
- import { onAuthStateChanged, signInWithEmailAndPassword, signOut, signInWithCustomToken } from 'firebase/auth';
+ import { pantauSesi, masuk, keluar } from '../../lib/authSupabase';

  // :84
- const unsubscribe = onAuthStateChanged(auth, async (firebaseUser) => {
-   if (firebaseUser) {
-     setUser(firebaseUser);
+ const unsubscribe = pantauSesi(async (pengguna) => {
+   if (pengguna) {
+     setUser(pengguna);

  // :194
- await signInWithEmailAndPassword(auth, email, password);
+ await masuk(email, password);

  // :209
- await signOut(auth);
+ await keluar();
```

`app/kasir/page.js` mengikuti pola yang sama (`:549`, `:602`, `:616`).
Catatan: `/kasir` **tidak punya formulir login** — `:588-593` mengalihkan ke
`/pembukuan`. Jadi `handleLogin` di `:601` tidak pernah terpanggil; biarkan
saja (aturan repo: jangan hapus yang tampak tak terpakai).

**Belum tercakup pembalikan ini:** `signInWithCustomToken` di
`pembukuan/page.js:9` dan `buku-perkembangan/page.js:25` — jalur SSO
Android→Web. Penggantinya `verifyOtp` lewat Edge Function
`session-management` (`012` §1), dan itu pekerjaan tersendiri di D-4.
Selama Android v2 belum ada, jalur SSO ini tetap Firebase — dan **ikut mati
pada D-0**. Sesudah itu staf masuk lewat formulir, bukan lewat aplikasi.

Setelah pembalikan berhasil, `app/login-supabase/` boleh dihapus.

---

## 5. Utang yang TIDAK saya improvisasi

### U-1 · Paksa ganti sandi di pemakaian pertama — MEKANISMENYA BELUM ADA

Diperiksa, dan hasilnya:

- `supabase/functions/user-management/index.ts:341-346` punya lima aksi:
  `resetUserPassword`, `getAllUsers`, `createNewUser`,
  `deleteExistingUser`, `getAllCabang`. **Tidak ada** aksi ganti sandi
  mandiri, dan tidak ada penanda "wajib ganti".
- `koperasi.app_user` (`001:138-155` + `001a`) **tidak punya kolom** semacam
  `harus_ganti_sandi`.

Jadi yang ada dan yang tidak ada harus dipisahkan supaya tidak salah kira:

| | Status |
|---|---|
| Staf **bisa** mengganti sandinya sendiri | **ADA** — `gantiSandi()` di `lib/authSupabase.js`, memakai `auth.updateUser()` bawaan GoTrue, tanpa Edge Function |
| Sistem **memaksa** ganti di pemakaian pertama | **BELUM ADA** |

Memaksanya menuntut satu kolom penanda + tempat mengesetnya saat Pengawas
membangkitkan sandi + penjaga di setiap halaman. Itu perubahan skema di
minggu terakhir, dan saya tidak menambahkannya diam-diam.

**Penambal sementara sampai U-1 dikerjakan:** Pengawas menyerahkan sandi awal
secara langsung dan meminta staf menggantinya di pemakaian pertama —
prosedur, bukan penegakan. Lemah, dan disebut lemah. Yang membuatnya masih
dapat diterima: sandinya sekali pakai, diserahkan langsung, dan `013` §4
sudah menjadi peringatan bahwa sandi bersama tidak boleh dipakai untuk
seluruh staf produksi.

Kalau U-1 dikerjakan, bentuk termurahnya: kolom `harus_ganti_sandi boolean
not null default false`, diset `true` oleh `resetUserPassword`, diperiksa
`profilSaya()`, dan dibersihkan oleh `gantiSandi()` lewat satu RPC kecil.
Perkiraan setengah hari — muat di D-4 **bila** 13 fungsi selesai lebih cepat.

### U-2 · SSO Android→Web (§4)

`signInWithCustomToken` belum diganti. Ikut mati pada D-0.

---

## 6. Catatan kejujuran

- Kedua berkas web baru **belum saya jalankan** — tidak ada `npm run dev` di
  sisi saya. Yang saya periksa: sintaksnya, dan bahwa tidak ada berkas lain
  yang mengimpornya (jadi `npm run dev` tidak mungkin terganggu).
- Seluruh nomor baris dari pembacaan berkas di repo ini.
- Saya belum pernah memanggil Supabase dari web ini; bahwa `profilSaya()`
  berhasil membaca `app_user` adalah kesimpulan dari policy `002:170`,
  **bukan** hasil pengujian. §3 langkah 3 ada justru karena itu bagian yang
  paling mungkin meleset.
