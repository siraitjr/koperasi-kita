# 016 — Migrasi `operasional_harian` (uang makan & transport staf)

Menutup pertanyaan terbuka `014` §8 no. 1 dan `015` §B-4a.

**Keputusan pemilik:** `syncOperasionalTransport` dipakai sehari-hari →
opsi **(a) migrasikan `operasional_harian`**. Opsi (b) — hentikan fitur,
catat transport manual — dibatalkan.

Berkas dalam paket ini:

| Berkas | Isi | Status |
|---|---|---|
| `016a_operasional_harian.sql` | DDL tabel + RLS | belum dijalankan |
| `scripts/migration/migrate_operasional_harian.js` | impor data (dry-run default) | belum dijalankan |
| `015_tahap_b_views.sql` §B-4 | RPC versi final (menggantikan stub) | belum dijalankan |

Tidak ada satu pun perintah yang saya jalankan terhadap database. Semua di
bawah ini hasil pembacaan kode dan data contoh, dikutip dengan nomor baris.

---

## 1. Apa yang sebenarnya dilakukan `syncOperasionalTransport`

Sumber: `functions/kasirApi.js:576-714`. Dibaca utuh; berikut seluruh
perilakunya, bukan ringkasannya.

### 1.1 Gerbang akses

| Baris | Pemeriksaan | Gagal → |
|---|---|---|
| `:591` | metode harus POST | 405 |
| `:596` | `verifyAuth(req)` | 401 |
| `:602` | user ada di `metadata/admins` | 403 |
| `:606` | **`user.role !== 'kasir_unit'`** | 403 |
| `:611` | user punya `cabang` | 400 |

Perhatikan `:606`: lebih ketat daripada `KASIR_ALLOWED_ROLES`
(`kasirApi.js:188`, enam peran). Menulis entri operasional **hanya** Kasir
Unit; Pimpinan dan Pengawas pun tidak. Itu dipertahankan di RPC.

Cabang **tidak pernah** diambil dari request — selalu dari profil pemanggil
(`:610 const cabangId = user.cabang`). Kasir tidak bisa menyinkronkan cabang
orang lain sekalipun ia mengirimkan id-nya.

### 1.2 Tanggal

`:616-624` menggeser `Date.now()` sebesar +7 jam lalu membaca komponen UTC —
cara manual mendapatkan hari berjalan WIB. Menghasilkan tiga bentuk:

| Variabel | Contoh | Dipakai untuk |
|---|---|---|
| `todayKey` | `2026-08-25` | path node & kunci entri |
| `bulanKey` | `2026-08` | path bulan kasir |
| `tanggalIndo` | `25 Agu 2026` | field `tanggal` entri |

Di Postgres ketiganya lenyap: `tanggal` bertipe `date`, `periode` cukup
`date_trunc('month', …)`, dan hari berjalan jadi
`(now() at time zone 'Asia/Jakarta')::date`.

### 1.3 Sumber angka

```js
// :627-629
const opsSnap = await db.ref(`operasional_harian/${cabangId}/${todayKey}`).once('value');
const opsData = opsSnap.val() || {};
```

Satu-satunya sumber. Kalau node ini kosong, seluruh fitur menghasilkan nol —
itulah sebabnya tabelnya tidak bisa dilewati.

```js
// :632-641
Object.values(opsData).forEach(rec => {
  const uangMakan = parseInt(rec.uangMakan) || 0;
  const transport = parseInt(rec.transport) || 0;
  const subtotal = uangMakan + transport;
  if (subtotal > 0) { totalOperasional += subtotal; details.push(...); }
});
```

**Dua hitungan berbeda atas himpunan yang sama, dan ini mudah salah salin:**

- yang **dijumlahkan** hanya record dengan `subtotal > 0` (`:638`);
- yang **dihitung untuk keterangan** adalah `Object.keys(opsData).length` —
  **seluruh** record, termasuk yang nol (`:675`).

Jadi bila 5 staf terdaftar dan 2 di antaranya bernilai 0, keteranganya tetap
`"Operasional 5 karyawan"`. RPC-nya meniru ini persis (`count(*)` polos,
`sum(...) filter (where … > 0)`). `details[]` sendiri dibangun tetapi tidak
pernah dipakai — tidak saya bawa.

### 1.4 Entri kasir yang dihasilkan

Kunci tetap, bukan `push()` (`:644`):

```js
const entryKey = `auto_ops_${todayKey}`;
```

Inilah yang membuatnya idempoten di RTDB. Tiga cabang keputusan:

| Kondisi | Aksi | Baris |
|---|---|---|
| total 0, entri lama tidak ada | tidak melakukan apa-apa | `:653-661` |
| total 0, entri lama ada | `entryRef.remove()` + summary dikurangi | `:662-673` |
| total > 0 | tulis/timpa entri + summary disesuaikan | `:675-697` |

Bentuk entri (`:676-687`):

| Field | Nilai | Catatan |
|---|---|---|
| `jenis` | `'transport'` | tetap |
| `arah` | `'keluar'` | tetap |
| `jumlah` | `totalOperasional` | |
| `keterangan` | `` `Operasional ${Object.keys(opsData).length} karyawan` `` | hitung semua record |
| `tanggal` | `tanggalIndo` | string lokal |
| `createdBy` / `createdByName` | `user.uid` / `user.name` | pemanggil sekarang, ditimpa tiap sync |
| `createdAt` | **`oldEntry.createdAt` bila ada**, selain itu `ServerValue.TIMESTAMP` | sengaja dipertahankan (`:681`) |
| `updatedAt` | `ServerValue.TIMESTAMP` | |
| `source` | `'operasional_harian'` | penanda entri otomatis |

`:690-694` menyesuaikan `kasir_summary`: kurangi nilai lama, tambah nilai
baru. Di Supabase penghitung tersimpan itu tidak ada — rekap kasir adalah
view beragregat (`015` B-3.3), dihitung saat dibaca. Tidak ada yang perlu
disesuaikan, dan tidak ada penghitung yang bisa melenceng.

---

## 2. Data sumbernya — ada, tidak perlu diekspor ulang

Saya cari lebih dulu sebelum menulis langkah ekspor, dan **ketemu**:
`data/firebase_sample.json` sudah memuat node `operasional_harian`.

```
operasional_harian/panti/2026-03-28/{staffUid}
```

Bentuk record nyata (satu entri, disalin apa adanya):

```json
{
  "uid":               "3B1yKQMPZbdDIbhZ6eLFz3dr8wo2",
  "nama":              "Resort Permula Panti",
  "uangMakan":         15000,
  "transport":         35000,
  "diberikanOleh":     "plclpO1gmFeskU8j3u0qKHDdYBF3",
  "diberikanOlehNama": "Kasir Unit Panti",
  "timestamp":         1774662363055
}
```

Isi berkas contoh: **1 cabang (`panti`), 11 tanggal**, contoh `2026-03-28`
berisi 5 record. Angka itu jelas bukan seluruh produksi — berkas ini memang
sampel. Skrip migrasi karena itu **menolak** berkas yang mengandung penanda
`"more keys"` (`migrate_operasional_harian.js:93`), pengaman yang sama dengan
`migrate.js`.

Yang dipakai `syncOperasionalTransport` hanya `uangMakan`, `transport`, dan
`nama`. Sisanya tetap saya migrasikan: `diberikanOleh` dan `timestamp`
merekam **siapa** yang menyerahkan uang dan **kapan**. Itu jejak uang;
membuangnya berarti kehilangan pertanggungjawabannya, dan tidak ada cara
memulihkannya nanti kalau RTDB sudah dimatikan.

### Kalau berkas ekspor penuh belum ada

Firebase Console → Realtime Database → pilih node `operasional_harian` →
menu ⋮ → **Export JSON**. Simpan sebagai berkas terpisah, lalu jalankan
skrip dengan `--file=` menunjuk ke sana. Skrip juga menerima ekspor akar
penuh (ia mencari kunci `operasional_harian` di dalamnya).

Ekspor akar penuh lebih disarankan: skrip memvalidasi FK terhadap
`metadata/admins` dan `metadata/cabang` dari **berkas yang sama**, sehingga
tidak mungkin memakai daftar staf yang lebih tua daripada data uangnya.

---

## 3. Tabel — `016a_operasional_harian.sql`

```sql
create table koperasi.operasional_harian (
  cabang_id   text not null references koperasi.cabang(id),
  tanggal     date not null,
  user_id     uuid references koperasi.app_user(id),   -- NULLABLE, lihat bawah
  legacy_uid  text not null,
  nama        text not null default '',
  uang_makan  bigint not null default 0,
  transport   bigint not null default 0,
  diberikan_oleh            uuid references koperasi.app_user(id),
  diberikan_oleh_nama       text not null default '',
  diberikan_oleh_legacy_uid text,
  recorded_at timestamptz,
  created_at  timestamptz not null default now(),
  primary key (cabang_id, tanggal, legacy_uid),
  constraint operasional_nominal_wajar check (uang_makan >= 0 and transport >= 0)
);
```

Tiga keputusan yang perlu alasannya:

**PK memakai `legacy_uid`, bukan `user_id`.** `user_id` boleh NULL, dan NULL
tidak bisa menjadi bagian primary key. `legacy_uid` (UID Firebase, = kunci
record di RTDB) selalu ada. PK ini sekaligus yang membuat impor idempoten:
`on conflict (cabang_id, tanggal, legacy_uid) do nothing`.

**Kedua FK ke `app_user` NULLABLE.** Staf yang sudah keluar bisa saja tidak
ada lagi di `metadata/admins`. Kalau FK-nya wajib, seluruh transaksi impor
gagal karena satu orang yang berhenti tahun lalu — dan catatan uangnya
hilang. Skrip meng-NULL-kan tautannya, menyimpan `legacy_uid`/`nama` apa
adanya, lalu melaporkan jumlahnya.

**RLS: baca saja, tanpa policy tulis.** Enam peran kasir (`kasirApi.js:188`)
+ `boleh_lihat_cabang`, ditambah pemilik barisnya sendiri — wajar seseorang
boleh melihat uang makannya sendiri. Penulisan hanya lewat `service_role`
(skrip migrasi) atau RPC `SECURITY DEFINER`. `grant` ke `authenticated`
hanya `SELECT`, sejalan dengan tabel uang lain di skema ini.

---

## 4. Impor — `scripts/migration/migrate_operasional_harian.js`

Pola sama persis dengan `migrate.js`:

```bash
# 1) DRY-RUN — ini yang default, tidak menulis apa pun
node --max-old-space-size=8192 scripts/migration/migrate_operasional_harian.js \
     --file=/path/export.json

# 2) Setelah laporannya dibaca dan wajar:
node --max-old-space-size=8192 scripts/migration/migrate_operasional_harian.js \
     --file=/path/export.json \
     --dsn="postgresql://postgres:<pw>@<host>:5432/postgres" \
     --execute
```

- **Dry-run adalah default.** Menulis hanya dengan `--execute`, dan
  `--execute` tanpa `--dsn` ditolak (`:49`).
- **Satu transaksi.** Gagal di tengah → `rollback`, tidak ada yang tersimpan.
- **Idempoten.** Diulang berapa kali pun tidak menggandakan baris.
- **`idUser()` wajib identik dengan `migrate.js`** (`:86`). Kalau berbeda,
  seluruh FK ke `app_user` meleset dan baris-baris ini kehilangan tautannya.
  Itu sebabnya fungsinya disalin, bukan ditulis ulang.
- **Cabang tak dikenal dilewati, bukan menggagalkan.** `cabang_id` NOT NULL +
  FK; tidak ada cara menyimpannya. Dilaporkan sebagai
  `OPS_CABANG_TIDAK_DIKENAL` beserta jumlah tanggal yang terlewat.
- Laporan JSON ditulis ke `./operasional_report.json`.

Yang harus dibaca di keluaran dry-run sebelum `--execute`:

| Baris keluaran | Artinya kalau angkanya besar |
|---|---|
| `user_id NULL (staf tidak terdaftar)` | banyak staf tidak ada di `metadata/admins` — periksa apakah ekspornya sezaman |
| `diberikan_oleh NULL` | kasir pemberinya sudah tidak terdaftar |
| `dilewati karena cabang tidak dikenal` | **berhenti.** Ini kehilangan data, bukan sekadar peringatan |
| `total nominal` | bandingkan dengan rekap kasir bulan berjalan |

### Yang sudah saya uji sendiri (dan yang tidak)

`node --check` lulus. Dijalankan langsung atas `data/firebase_sample.json`,
skrip **berhenti di penjaga truncation** — benar, karena berkas contoh itu
memang terpotong. Untuk menguji jalur transformasinya saya buat salinan
lokal tanpa penanda terpotong (di luar repo, tidak di-commit); hasilnya:

```
baris 56 · Rp 2.404.000 · 1 cabang · 2026-03-28 … 2026-04-10
⚠ user_id NULL 37 · diberikan_oleh NULL 56
```

Dua angka NULL itu **artefak sampel**, bukan cacat skrip: `metadata/admins`
di berkas contoh ikut terpotong (10 entri), dan `metadata/cabang` tidak ada
sama sekali — cabang `panti` tetap dikenali lewat jalur cadangan, yaitu field
`cabang` milik admin. Pada ekspor penuh keduanya harus mendekati nol; kalau
tidak, itu temuan nyata dan harus ditelusuri sebelum `--execute`.

**Belum diuji:** jalur `--execute`. Tidak ada PostgreSQL di sisi penulis, jadi
SQL `insert`-nya belum pernah menyentuh server mana pun.

---

## 5. RPC final — perubahan pada `015` §B-4

`015` **belum dijalankan pemilik**, jadi berkasnya disunting di tempat alih-alih
ditambal berkas baru. Dua perubahan:

1. **`rpc_sync_operasional_transport` diganti dari stub gagal-keras
   (`raise … errcode '0A000'`) menjadi versi penuh.** Stub itu ada karena
   saat `015` ditulis tabel sumbernya belum diputuskan; sekarang sudah.
2. **Catatan §B-4a diperbarui** dari "dua hal yang harus diputuskan" menjadi
   keputusan yang sudah diambil, beserta urutan jalannya. Kepala berkas juga
   menyebut prasyarat baru B-4.

Tanda tangan fungsinya tetap `(text, date)` sehingga baris `revoke`/`grant`
di bawahnya tidak berubah; `p_cabang_id` kini ber-default `null`.

### Kesetaraan dengan aslinya

| Perilaku asli | Baris JS | Di RPC |
|---|---|---|
| hanya `kasir_unit` | `:606` | `if v_role <> 'kasir_unit' then raise 42501` |
| cabang dari profil, bukan request | `:610` | dibaca dari `app_user`; parameter hanya boleh menegaskan, beda → 42501 |
| hari berjalan WIB | `:616-624` | `(now() at time zone 'Asia/Jakarta')::date` |
| jumlahkan hanya subtotal > 0 | `:638` | `sum(...) filter (where uang_makan + transport > 0)` |
| keterangan hitung semua record | `:675` | `count(*)` |
| kunci tetap `auto_ops_{tanggal}` | `:644` | `client_op_id = md5('auto_ops:'‖cabang‖':'‖tanggal)::uuid` |
| total 0 & tak ada entri → no-op | `:653` | `return null` |
| total 0 & ada entri → hapus | `:662` | **soft delete** (lihat bawah) |
| `createdAt` lama dipertahankan | `:681` | `created_at` tidak disentuh saat update |

`md5(...)::uuid` dipakai karena PostgreSQL tidak punya UUIDv5 bawaan (skrip
Node punya). Sifat yang dibutuhkan hanya satu — deterministik dari
(cabang, tanggal) — dan md5 memenuhinya. Ini bukan pemakaian kriptografis.

### Dua penyimpangan yang disengaja

**(a) Penghapusan jadi soft delete.** Aslinya `entryRef.remove()`. Di sini
`dihapus_at = now()` (kolom dari `015` B-3.0). Alasannya sama dengan
`rpc_hapus_kasir_entry`: entri kasir adalah catatan uang, dan
`v_kasir_entry` sudah menyaring `dihapus_at is null` sehingga secara tampilan
hasilnya identik. Bedanya, kalau esoknya angka operasional muncul lagi, baris
yang **sama** dihidupkan kembali (`dihapus_at = null`) alih-alih membuat baris
baru — sehingga satu hari tetap satu baris, dan jejak hapus-hidupnya utuh.

**(b) Tidak ada penyesuaian `kasir_summary`.** Aslinya harus menambah dan
mengurangi penghitung tersimpan secara manual (`:690-694`), dan itu kelas bug
tersendiri: satu kegagalan di tengah membuat rekap melenceng permanen. Di
Supabase rekapnya view beragregat — tidak ada yang perlu disesuaikan.

### Urutan jalan yang benar

```
018 BATCH 1                          (sudah terpasang — prasyarat baru)
  → 016a_operasional_harian.sql      (buat tabel + RLS)
    → migrate_operasional_harian.js  (dry-run, lalu --execute)
      → 015 BATCH B-4                (RPC)
        → 019_backfill_kasir_auto_ops.sql
          → baru web dialihkan memanggil RPC
```

**Langkah 019 wajib, dan ditemukan lewat pengujian pemilik, bukan lewat
perancangan.** `migrate.js:973/983` memberi `id` dan `client_op_id` nilai
yang sama (uuidv5 dari kunci RTDB), sedangkan B-4 mencari barisnya lewat
`md5('auto_ops:'||cabang||':'||tanggal)`. Dua rumus berbeda untuk baris yang
sama → RPC tidak mengenali entri warisan, menyimpulkan "belum ada", lalu
INSERT. Hasilnya dua baris untuk satu hari dan rekap kasir yang menggelembung.
019 menyelaraskan kuncinya. Rinciannya di berkas itu.

B-4 dijalankan lebih dulu tidak merusak apa pun — fungsinya tetap tercipta —
tetapi panggilan pertamanya gagal `42P01 relation does not exist`.

**Prasyarat 018 itu baru, ditambahkan setelah 016a ditulis.** Policy di 016a
kini memakai `koperasi_priv.cabang_terlihat_arr()` yang lahir di 018 Batch 1;
menjalankan 016a tanpa 018 gagal `42883 function does not exist`. Karena 018
sudah terpasang di server, ini tidak menambah langkah — hanya menetapkan
urutannya.

### Perubahan bentuk RLS di 016a (setelah 018)

016a ditulis sebelum 017/018 ada, jadi policy-nya memakai pola lama:
`koperasi_priv.role() in (…) and boleh_lihat_cabang(cabang_id)` — panggilan
SECURITY DEFINER **per baris**, yang 018 §0 buktikan mahal karena fungsi
definer (dan fungsi ber-klausa `SET`) tidak bisa di-inline PostgreSQL.

Diubah ke bentuk 018: `(select koperasi_priv.role())` dan
`cabang_id = any ((select koperasi_priv.cabang_terlihat_arr())::text[])`.

Alasannya **bukan** kecepatan — `operasional_harian` cuma berisi beberapa
baris per cabang per hari, pola lama pun tidak akan terasa. Alasannya supaya
tidak ada contoh pola lama yang tersisa di repo untuk disalin ke tabel
berikutnya yang tidak kecil. Semantiknya tidak berubah:
`boleh_lihat_cabang(c)` ≡ `c = any(cabang_terlihat_arr())`, sudah diuji
diferensial di 018 §4(a) untuk seluruh user × cabang tanpa selisih.

Ini aman dilakukan karena 016a **belum pernah dijalankan**. Berkas yang sudah
Anda jalankan (015 B-1..B-3) sengaja TIDAK disentuh, walau `kasir_baca` di
B-3.1 memakai pola lama yang sama — mengubah berkas yang sudah berjalan
menuntut siklus ukur-dan-uji tersendiri, dan `kasir_entry` juga kecil.

---

## 6. Verifikasi setelah semuanya terpasang

```sql
-- (1) data masuk
select cabang_id, tanggal, count(*) baris, sum(uang_makan + transport) total
  from koperasi.operasional_harian
 group by 1,2 order by tanggal desc limit 10;

-- (2) klien tidak punya hak tulis
select privilege_type from information_schema.role_table_grants
 where table_name = 'operasional_harian' and grantee = 'authenticated';
-- harapan: hanya SELECT
```

Uji RPC **wajib lewat REST dengan JWT kasir unit**, bukan SQL Editor — SQL
Editor memakai `service_role`, `auth.uid()` NULL, dan gerbang perannya tidak
pernah teruji:

```bash
curl -X POST "$SUPA_URL/rest/v1/rpc/rpc_sync_operasional_transport" \
  -H "apikey: $ANON" -H "Authorization: Bearer $JWT_KASIR_UNIT" \
  -H "Content-Type: application/json" \
  -d '{"p_tanggal":"2026-03-28"}'
```

| Uji | Harapan |
|---|---|
| kasir unit, hari berisi data | mengembalikan uuid; entri muncul di `v_kasir_entry` |
| dipanggil **dua kali** | uuid yang **sama**, tetap satu baris |
| JWT admin lapangan | 42501 (403) |
| tanpa `Authorization` | 401 |
| hari yang seluruh nominalnya 0, entri sebelumnya ada | entri hilang dari `v_kasir_entry`, barisnya masih ada dengan `dihapus_at` terisi |
| hari itu diisi lagi | baris yang sama hidup kembali, bukan baris kedua |

Bandingkan `nominal` hasil RPC dengan `jumlah` entri `auto_ops_{tanggal}` di
RTDB untuk hari yang sama. Harus sama persis; kalau berbeda, tersangka
pertamanya adalah beda hitungan §1.3 (subtotal > 0 vs seluruh record).

---

## 7. Yang belum berubah

- `app/` tidak disentuh. Fitur ini web-only; Android tidak memanggilnya.
- `functions/kasirApi.js` tidak disentuh — Cloud Function lama tetap hidup
  agar rollback mungkin.
- Web masih memanggil endpoint lama lewat `lib/api.js`; pemindahan
  transportnya menyusul bersama endpoint Tahap B lainnya.
- `016a`, skrip impor, dan `015` B-4 **belum pernah dijalankan**. Tidak ada
  PostgreSQL di sisi penulis, jadi sintaksnya belum divalidasi server.
