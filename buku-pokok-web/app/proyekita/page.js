import './landing.css';

const WA_NUMBER = '6285210221082';
const wa = (text) => `https://wa.me/${WA_NUMBER}?text=${encodeURIComponent(text)}`;

export const metadata = {
  title: 'Proyekita Partner — Jadilah Reseller Kartu NFC Review Google',
  description: 'Program reseller resmi Kartu NFC Review Google Proyekita. Modal kecil, margin tinggi, produk terbukti laku di bisnis lokal seluruh Indonesia.',
};

export default function ProyekitaReseller() {
  return (
    <div className="ld-wrap">
      <div className="ld-bar">
        <span className="ld-dot" /> Pendaftaran Reseller Batch 1 — Slot Kota Terbatas
      </div>

      <header className="ld-hero">
        <div className="ld-hero-glow" />
        <div className="ld-hero-inner">
          <span className="ld-pill">Program Reseller Resmi</span>
          <h1>Jual Kartu Review Google.<br /><span className="ld-grad">Modal Kecil. Untung Besar.</span></h1>
          <p className="ld-sub">Bantu bisnis lokal naik rating Google-nya. Produk premium, sistem anti-error, margin Rp 40.000–Rp 115.000 per kartu.</p>
          <div className="ld-cta">
            <a className="ld-btn primary" href={wa('Halo Proyekita, saya mau daftar jadi reseller.')}>Daftar Jadi Reseller</a>
            <a className="ld-btn ghost" href="#cuan">Lihat Potensi Cuan</a>
          </div>
        </div>
      </header>

      <section className="ld-stats">
        <div className="ld-stat">
          <div className="ld-stat-num">100%</div>
          <div className="ld-stat-lbl">Anti-Error di WhatsApp / IG / HP Lama</div>
        </div>
        <div className="ld-stat">
          <div className="ld-stat-num">Akrilik</div>
          <div className="ld-stat-lbl">Bahan Premium, Bukan Stiker Murahan</div>
        </div>
        <div className="ld-stat">
          <div className="ld-stat-num">Rp 0</div>
          <div className="ld-stat-lbl">Biaya Bulanan untuk Klien</div>
        </div>
        <div className="ld-stat">
          <div className="ld-stat-num">24 jam</div>
          <div className="ld-stat-lbl">Support via WhatsApp</div>
        </div>
      </section>

      <section className="ld-sec" id="mengapa">
        <span className="ld-eyebrow">Mengapa Proyekita</span>
        <h2>Bukan Produk Pasaran yang Bikin Anda Kena Komplain</h2>
        <p className="ld-lead">Reseller kami tidak pernah dikomplain karena kartu gagal di-aktivasi atau link error di WhatsApp. Sistem kami dibangun dengan standar yang tidak dimiliki supplier kartu NFC murah manapun.</p>
        <div className="ld-grid">
          <div className="ld-feat">
            <div className="ld-feat-ico">⚡</div>
            <h3>App Link Resmi Google</h3>
            <p>Pelanggan tap — langsung form bintang. Tidak mampir ke halaman Maps, tidak ada klik tambahan.</p>
          </div>
          <div className="ld-feat">
            <div className="ld-feat-ico">🛡️</div>
            <h3>Perisai In-App Browser</h3>
            <p>Scan dari WhatsApp, Instagram, atau TikTok tetap bekerja — bukan cuma dari Chrome biasa.</p>
          </div>
          <div className="ld-feat">
            <div className="ld-feat-ico">📱</div>
            <h3>Android Baru, Lama, iPhone</h3>
            <p>Satu kartu untuk semua HP pelanggan. Tidak perlu tanya-tanya "HP-nya apa dulu?"</p>
          </div>
          <div className="ld-feat">
            <div className="ld-feat-ico">🏛️</div>
            <h3>Akrilik + Stiker Anti-Air</h3>
            <p>Bahan tahan lama, tidak mengelupas, elegan di meja kasir. Klien bangga memajang.</p>
          </div>
          <div className="ld-feat">
            <div className="ld-feat-ico">🔧</div>
            <h3>Alat Encode Gratis</h3>
            <p>Anda dapat akses ke sistem encoder web kami. Satu tap di HP Anda, chip langsung terisi.</p>
          </div>
          <div className="ld-feat">
            <div className="ld-feat-ico">📚</div>
            <h3>Training & Script Jualan</h3>
            <p>Panduan lengkap dari cara pendekatan owner cafe sampai handling keberatan "kemahalan".</p>
          </div>
        </div>
      </section>

      <section className="ld-sec dark" id="cuan">
        <span className="ld-eyebrow light">Potensi Cuan</span>
        <h2 className="light">Kalkulator Pendapatan Anda</h2>
        <p className="ld-lead light">Berapa yang bisa Anda hasilkan sebagai reseller Proyekita?</p>
        <div className="ld-calc">
          <div className="ld-calc-row">
            <div className="ld-calc-label">Modal per kartu</div>
            <div className="ld-calc-value">Rp 35.000</div>
          </div>
          <div className="ld-calc-row">
            <div className="ld-calc-label">Harga jual ke klien</div>
            <div className="ld-calc-value">Rp 75.000 – Rp 150.000</div>
          </div>
          <div className="ld-calc-row">
            <div className="ld-calc-label">Margin bersih per kartu</div>
            <div className="ld-calc-value accent">Rp 40.000 – Rp 115.000</div>
          </div>
        </div>
        <div className="ld-scenarios">
          <div className="ld-scenario">
            <div className="ld-scenario-head">Part-Time</div>
            <div className="ld-scenario-num">5 kartu / minggu</div>
            <div className="ld-scenario-money">Rp 1.000.000 – Rp 2.300.000 / bulan</div>
          </div>
          <div className="ld-scenario hot">
            <div className="ld-scenario-tag">Umum dicapai</div>
            <div className="ld-scenario-head">Serius</div>
            <div className="ld-scenario-num">3 kartu / hari</div>
            <div className="ld-scenario-money">Rp 3.600.000 – Rp 10.350.000 / bulan</div>
          </div>
          <div className="ld-scenario">
            <div className="ld-scenario-head">Full-Time</div>
            <div className="ld-scenario-num">5 kartu / hari</div>
            <div className="ld-scenario-money">Rp 6.000.000 – Rp 17.250.000 / bulan</div>
          </div>
        </div>
      </section>

      <section className="ld-sec" id="starter">
        <span className="ld-eyebrow">Yang Anda Dapat</span>
        <h2>Starter Kit Reseller Proyekita</h2>
        <div className="ld-kit">
          <div className="ld-kit-item">
            <div className="ld-kit-ico">📦</div>
            <h3>Stok Kartu Fisik</h3>
            <p>10 kartu akrilik NFC+QR siap jual dalam setiap batch awal. Anda tidak perlu urus supplier sendiri.</p>
          </div>
          <div className="ld-kit-item">
            <div className="ld-kit-ico">🔑</div>
            <h3>Akses Sistem Encode</h3>
            <p>Passcode pribadi untuk alat encoder web kami. Satu scan QR + satu tap = kartu siap dijual.</p>
          </div>
          <div className="ld-kit-item">
            <div className="ld-kit-ico">🎯</div>
            <h3>Script Jualan Lengkap</h3>
            <p>Cara pendekatan owner cafe, jawaban keberatan "kemahalan", dan teknik closing di tempat.</p>
          </div>
          <div className="ld-kit-item">
            <div className="ld-kit-ico">🤝</div>
            <h3>Group Mitra Eksklusif</h3>
            <p>Komunitas privat sesama reseller untuk berbagi tips, tanya jawab, dan update produk.</p>
          </div>
        </div>
      </section>

      <section className="ld-sec alt" id="alur">
        <span className="ld-eyebrow">Alur Pendaftaran</span>
        <h2>4 Langkah Jadi Reseller</h2>
        <div className="ld-steps">
          <div className="ld-step"><div className="ld-num">1</div><h3>Daftar Antrean</h3><p>Chat WhatsApp, isi nama dan kota Anda. Kami kunci wilayah kota Anda.</p></div>
          <div className="ld-step"><div className="ld-num">2</div><h3>Briefing Singkat</h3><p>Call 15 menit dengan tim Proyekita untuk memastikan visi dan ekspektasi Anda.</p></div>
          <div className="ld-step"><div className="ld-num">3</div><h3>Ambil Starter Kit</h3><p>Pembayaran dan pengiriman 10 kartu pertama + akses sistem + materi training.</p></div>
          <div className="ld-step"><div className="ld-num">4</div><h3>Mulai Jual</h3><p>Datang ke bisnis lokal di kota Anda, demo, closing. Kami dampingi sampai klien pertama Anda puas.</p></div>
        </div>
      </section>

      <section className="ld-sec" id="faq">
        <span className="ld-eyebrow">FAQ</span>
        <h2>Pertanyaan Calon Reseller</h2>
        <div className="ld-faq">
          <details><summary>Apakah ada wilayah eksklusif per kota?</summary><p>Ya. Kami batasi jumlah reseller per kota agar tidak saling berebut klien. Kota yang sudah penuh tidak akan dibuka lagi sampai kapasitasnya bertambah.</p></details>
          <details><summary>Berapa minimum order pertama?</summary><p>Starter Kit berisi 10 kartu. Setelah itu pemesanan bebas — tidak ada minimum bulanan.</p></details>
          <details><summary>Saya belum pernah jualan, apakah bisa?</summary><p>Bisa. Kami sediakan training dari nol sampai closing. Banyak reseller kami awalnya karyawan kantoran dan mahasiswa.</p></details>
          <details><summary>Apakah saya harus cetak stiker sendiri?</summary><p>Tidak. Stiker sudah kami cetak. Anda hanya perlu aktivasi saat ingin closing.</p></details>
          <details><summary>Kalau klien saya komplain, siapa yang handle?</summary><p>Anda sebagai reseller adalah kontak pertama. Untuk masalah teknis yang dalam, tim Proyekita mendukung Anda via WhatsApp agar klien Anda puas.</p></details>
          <details><summary>Bolehkah saya jual online lewat marketplace?</summary><p>Tidak direkomendasikan. Produk ini paling efektif dijual tatap muka karena butuh demo. Marketplace hanya akan menyeret Anda ke perang harga.</p></details>
          <details><summary>Berapa lama sampai bisa closing pertama?</summary><p>Rata-rata reseller kami closing klien pertama dalam 3–7 hari setelah mulai mendekati bisnis lokal.</p></details>
          <details><summary>Apakah ada biaya bulanan sebagai reseller?</summary><p>Tidak. Anda hanya membayar saat pesan stok kartu baru. Tidak ada royalti, tidak ada langganan.</p></details>
        </div>
      </section>

      <section className="ld-sec cta-sec">
        <div className="ld-cta-card">
          <h2>Siap Jadi Reseller Proyekita?</h2>
          <p>Slot Batch 1 terbatas. Kota besar sudah mulai terisi. Amankan wilayah Anda sekarang sebelum ditutup.</p>
          <a className="ld-btn primary big" href={wa('Halo Proyekita, saya mau daftar jadi reseller Batch 1.')}>Daftar Antrean Sekarang</a>
          <p className="ld-cta-note">Gratis konsultasi awal • Tidak ada biaya pendaftaran • Bayar hanya saat ambil Starter Kit</p>
        </div>
      </section>

      <footer className="ld-foot">
        <div className="ld-foot-inner">
          <div className="ld-brand">PROYEKITA</div>
          <div className="ld-foot-note">Program Reseller Resmi</div>
          <div className="ld-foot-copy">© 2026 Proyekita • koperasi-kita.com/proyekita</div>
        </div>
      </footer>
    </div>
  );
}