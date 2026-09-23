import './landing.css';

const WA_NUMBER = '628XXXXXXXXXX';
const DEMO_VIDEO_READY = false;
const IG_HANDLE = 'proyekita';

const wa = (text) => `https://wa.me/${WA_NUMBER}?text=${encodeURIComponent(text)}`;

export const metadata = {
  title: 'Proyekita — Kartu Ulasan Google NFC | Satu Tap, Langsung Form Ulasan',
  description: 'Kartu NFC + QR untuk usaha lokal: pelanggan tap sekali, langsung masuk form ulasan Google. Tanpa langganan, tanpa ribet, bekerja di semua HP.',
};

export default function ProyekitaLanding() {
  return (
    <div className="ld-wrap">
      <div className="ld-bar">Harga pelopor Rp 50.000 untuk 10 outlet pertama — setelah itu Rp 69.000</div>

      <header className="ld-hero">
        <div className="ld-brand">PROYEKITA</div>
        <h1>Satu Tap, Pelanggan Langsung Kasih Ulasan Google</h1>
        <p className="ld-sub">Kartu NFC + QR untuk cafe, salon, klinik, dan usaha lokal. Tanpa langganan, tanpa ribet, bekerja di semua HP.</p>
        <div className="ld-cta">
          <a className="ld-btn wa" href={wa('Halo Proyekita, saya mau pesan kartu ulasan Google.')}>Pesan via WhatsApp</a>
          <a className="ld-btn ghost" href="#pesan">Lihat Cara Pemesanan</a>
        </div>
      </header>

      <section className="ld-sec" id="cara">
        <h2>Cara Kerjanya — 3 Langkah</h2>
        <div className="ld-grid">
          <div className="ld-card"><div className="ld-num">1</div><h3>Tempel di meja kasir</h3><p>Kartu akrilik premium dengan chip NFC dan QR code. Elegan dilihat pelanggan.</p></div>
          <div className="ld-card"><div className="ld-num">2</div><h3>Pelanggan tap atau scan</h3><p>Satu tap di HP Android atau iPhone, atau scan QR — langsung terbuka form ulasan Google usaha Anda.</p></div>
          <div className="ld-card"><div className="ld-num">3</div><h3>Rating naik, pelanggan baru datang</h3><p>Ulasan masuk otomatis setiap hari. Posisi usaha Anda naik di Google Maps.</p></div>
        </div>
        {DEMO_VIDEO_READY ? (
          <video className="ld-video" controls src="/demo-tap.mp4" />
        ) : (
          <a className="ld-btn wa center" href={wa('Halo Proyekita, kirim video demo kartu ulasan ya.')}>Minta Video Demo via WA</a>
        )}
      </section>

      <section className="ld-sec alt">
        <h2>Kenapa Bukan Kartu NFC Pasaran?</h2>
        <div className="ld-grid two">
          <div className="ld-card good"><h3>Proyekita</h3><ul>
            <li>Langsung ke form bintang — tanpa klik tambahan</li>
            <li>Bekerja di HP Android lama maupun iPhone</li>
            <li>Tidak error saat scan dari WhatsApp / Instagram</li>
            <li>Akrilik premium + stiker anti air</li>
            <li>Dibantu aktivasi sampai kartu hidup</li>
            <li>Support lokal via WhatsApp</li>
          </ul></div>
          <div className="ld-card bad"><h3>Kartu pasaran</h3><ul>
            <li>Mampir dulu ke halaman Maps, pelanggan bingung</li>
            <li>Sering gagal di HP Android lama</li>
            <li>Error saat scan dari WhatsApp / Instagram</li>
            <li>Stiker tipis mudah mengelupas</li>
            <li>Pasang sendiri tanpa panduan</li>
            <li>Tidak ada yang bisa dihubungi saat rusak</li>
          </ul></div>
        </div>
      </section>

      <section className="ld-sec" id="harga">
        <h2>Harga Sederhana — Tanpa Paket-paket</h2>
        <div className="ld-single">
          <div className="ld-rp">Rp 50.000 <span>/ kartu</span></div>
          <p className="ld-strike">harga normal Rp 69.000</p>
          <ul>
            <li>Kartu akrilik premium + stiker anti air</li>
            <li>Chip NFC + QR code terisi link ulasan usaha Anda</li>
            <li>Dibantu aktivasi sampai kartu hidup</li>
            <li>Garansi ganti 30 hari untuk cacat produksi</li>
            <li>Support WhatsApp, tanpa biaya bulanan</li>
          </ul>
          <a className="ld-btn wa" href={wa('Halo Proyekita, saya mau pesan kartu ulasan Google.')}>Pesan Sekarang</a>
        </div>
      </section>

      <section className="ld-sec alt" id="pesan">
        <h2>Cara Pemesanan — 4 Langkah</h2>
        <div className="ld-steps">
          <div className="ld-step"><div className="ld-num">1</div><h3>Chat WhatsApp</h3><p>Kabari kami nama usaha Anda sebagaimana terdaftar di Google Maps.</p></div>
          <div className="ld-step"><div className="ld-num">2</div><h3>Konfirmasi & pembayaran</h3><p>Kami kirim total tagihan dan rekening (transfer bank atau QRIS). Kartu masuk antrean produksi.</p></div>
          <div className="ld-step"><div className="ld-num">3</div><h3>Produksi & encoding</h3><p>Kartu dicetak, dirakit, dan diisi link ulasan usaha Anda. Siap dalam 2–3 hari kerja.</p></div>
          <div className="ld-step"><div className="ld-num">4</div><h3>Pemasangan & kartu hidup</h3><p>Kami pasang di lokasi Anda, atau kirim dengan panduan aktivasi 1 menit. Hari itu juga kartu bekerja.</p></div>
        </div>
      </section>

      <section className="ld-sec" id="faq">
        <h2>Pertanyaan yang Sering Ditanyakan</h2>
        <div className="ld-faq">
          <details><summary>Apakah ada biaya bulanan?</summary><p>Tidak. Bayar sekali per kartu, dan kartu bekerja selamanya.</p></details>
          <details><summary>HP pelanggan saya macam-macam, apakah semua bisa?</summary><p>Bisa. Sistem kami dirancang agar bekerja di Android baru, Android lama, maupun iPhone — termasuk scan dari dalam WhatsApp dan Instagram.</p></details>
          <details><summary>Berapa lama dari pesan sampai kartu hidup?</summary><p>Produksi 2–3 hari kerja. Pemasangan di lokasi atau pengiriman menambah 1–3 hari tergantung jarak. Aktivasi sendiri hanya sekitar 1 menit.</p></details>
          <details><summary>Metode pembayarannya apa?</summary><p>Transfer bank dan QRIS. Setelah pembayaran masuk, kartu langsung masuk antrean produksi.</p></details>
          <details><summary>Saya gaptek, apakah sulit?</summary><p>Tidak. Kami bantu aktivasi sampai kartu hidup. Setelah itu Anda tidak perlu melakukan apa-apa.</p></details>
          <details><summary>Kalau usaha saya pindah lokasi?</summary><p>Kartu bisa direlokasi ke lokasi atau usaha baru dengan jasa relokasi Rp 50.000.</p></details>
          <details><summary>Bisa pesan dari luar kota?</summary><p>Bisa. Kami kirim ke seluruh Indonesia, dan aktivasi didampingi lewat WhatsApp sampai kartu hidup.</p></details>
          <details><summary>Bagaimana kalau kartu rusak?</summary><p>Cacat produksi (chip atau cetakan) dalam 30 hari kami ganti baru tanpa biaya. Kerusakan fisik seperti patah atau terendam di luar garansi, tapi bisa pesan ganti dengan harga khusus.</p></details>
        </div>
      </section>

      <section className="ld-sec alt" id="kontak">
        <h2>Kontak</h2>
        <div className="ld-contact">
          <div className="ld-crow"><b>WhatsApp</b><span>Satu kanal untuk pesan, tanya, dan dukungan — respon cepat di jam kerja.</span></div>
          <div className="ld-crow"><b>Jam layanan</b><span>Senin–Sabtu, 09.00–18.00 WIB</span></div>
          <div className="ld-crow"><b>Area pemasangan</b><span>Lubuk Sikaping & sekitarnya datang langsung; seluruh Indonesia via pengiriman.</span></div>
          <div className="ld-crow"><b>Instagram</b><span>@{IG_HANDLE}</span></div>
        </div>
        <a className="ld-btn wa center" href={wa('Halo Proyekita, saya mau tanya-tanya dulu.')}>Chat WhatsApp Sekarang</a>
      </section>

      <footer className="ld-foot">© 2026 Proyekita • koperasi-kita.com/proyekita</footer>
    </div>
  );
}