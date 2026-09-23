import './landing.css';

const WA_NUMBER = '628XXXXXXXXXX';
const DEMO_VIDEO_READY = false;

const wa = (text) => `https://wa.me/${WA_NUMBER}?text=${encodeURIComponent(text)}`;

export const metadata = {
  title: 'Proyekita — Kartu Ulasan Google NFC | Satu Tap, Langsung Form Ulasan',
  description: 'Kartu NFC + QR untuk usaha lokal: pelanggan tap sekali, langsung masuk form ulasan Google. Tanpa langganan, tanpa ribet, bekerja di semua HP.',
};

export default function ProyekitaLanding() {
  return (
    <div className="ld-wrap">
      <header className="ld-hero">
        <div className="ld-brand">PROYEKITA</div>
        <h1>Satu Tap, Pelanggan Langsung Kasih Ulasan Google</h1>
        <p className="ld-sub">Kartu NFC + QR untuk cafe, salon, klinik, dan usaha lokal. Tanpa langganan. Tanpa ribet. Bekerja di semua HP.</p>
        <div className="ld-cta">
          <a className="ld-btn wa" href={wa('Halo Proyekita, saya mau pesan kartu ulasan Google.')}>Pesan via WhatsApp</a>
          <a className="ld-btn ghost" href="#cara">Lihat Cara Kerja</a>
        </div>
        <p className="ld-note">Harga pelopor Rp 50.000 untuk 10 outlet pertama • setelah itu Rp 69.000</p>
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

      <section className="ld-sec">
        <h2>Harga Transparan</h2>
        <div className="ld-grid">
          <div className="ld-price"><h3>Satuan</h3><div className="ld-rp">Rp 50.000</div><p className="ld-strike">Rp 69.000</p><ul><li>1 kartu akrilik NFC + QR</li><li>Dibantu aktivasi</li><li>Support WhatsApp</li></ul><a className="ld-btn wa" href={wa('Halo Proyekita, saya mau paket Satuan.')}>Pesan</a></div>
          <div className="ld-price hot"><div className="ld-tag">Paling laku</div><h3>Warung • 3 kartu</h3><div className="ld-rp">Rp 135.000</div><p className="ld-strike">Rp 150.000</p><ul><li>3 kartu: kasir, pintu, meja</li><li>Pemasangan di lokasi</li><li>Support WhatsApp</li></ul><a className="ld-btn wa" href={wa('Halo Proyekita, saya mau paket Warung 3 kartu.')}>Pesan</a></div>
          <div className="ld-price"><h3>Outlet • 5 kartu</h3><div className="ld-rp">Rp 225.000</div><p className="ld-strike">Rp 250.000</p><ul><li>5 kartu seluruh titik</li><li>Pemasangan + prioritas support</li><li>Konsultasi profil Google</li></ul><a className="ld-btn wa" href={wa('Halo Proyekita, saya mau paket Outlet 5 kartu.')}>Pesan</a></div>
        </div>
        <p className="ld-note center">Tambah jasa klaim & optimasi Profil Bisnis Google: Rp 150.000 sekali bayar.</p>
      </section>

      <section className="ld-sec alt">
        <h2>Mau Jadi Mitra / Reseller?</h2>
        <p className="ld-lead">Batch mitra pertama dibuka setelah 10 outlet perdana kami hidup. Tinggalkan nomor Anda sekarang untuk prioritas slot kota Anda.</p>
        <a className="ld-btn wa center" href={wa('Halo Proyekita, saya mau daftar antrean mitra/reseller.')}>Daftar Antrean Mitra</a>
      </section>

      <section className="ld-sec">
        <h2>Pertanyaan Umum</h2>
        <div className="ld-faq">
          <details><summary>Apakah ada biaya bulanan?</summary><p>Tidak. Bayar sekali, kartu bekerja selamanya.</p></details>
          <details><summary>HP pelanggan saya macam-macam, apakah semua bisa?</summary><p>Bisa. Sistem kami dirancang agar bekerja di Android baru, Android lama, maupun iPhone — termasuk scan dari dalam WhatsApp dan Instagram.</p></details>
          <details><summary>Saya gaptek, apakah sulit?</summary><p>Tidak. Kami bantu aktivasi sampai kartu hidup. Setelah itu Anda tidak perlu melakukan apa-apa.</p></details>
          <details><summary>Kalau usaha saya pindah lokasi?</summary><p>Kartu bisa direlokasi ke lokasi atau usaha baru dengan jasa relokasi Rp 50.000.</p></details>
        </div>
      </section>

      <footer className="ld-foot">© 2026 Proyekita • koperasi-kita.com/proyekita</footer>
    </div>
  );
}