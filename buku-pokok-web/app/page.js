'use client';

// app/page.js
// =========================================================================
// LANDING PAGE - Koperasi Kita (www.koperasi-kita.com)
// =========================================================================

import { useEffect } from 'react';
import './landing.css';

export default function LandingPage() {

  useEffect(() => {
    // Navbar scroll effect
    const navbar = document.getElementById('lp-navbar');
    const handleScroll = () => {
      if (navbar) navbar.classList.toggle('scrolled', window.scrollY > 20);
    };
    window.addEventListener('scroll', handleScroll);

    // Fade-up on scroll (Intersection Observer)
    const observer = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.15, rootMargin: '0px 0px -40px 0px' });

    document.querySelectorAll('.lp-fade-up').forEach(el => observer.observe(el));

    return () => {
      window.removeEventListener('scroll', handleScroll);
      observer.disconnect();
    };
  }, []);

  const scrollTo = (e, id) => {
    e.preventDefault();
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: 'smooth' });
  };

  const toggleMobileMenu = () => {
    const menu = document.getElementById('lp-mobile-menu');
    if (menu) {
      menu.classList.toggle('open');
      document.body.style.overflow = menu.classList.contains('open') ? 'hidden' : '';
    }
  };

  const closeMobileMenu = () => {
    const menu = document.getElementById('lp-mobile-menu');
    if (menu) {
      menu.classList.remove('open');
      document.body.style.overflow = '';
    }
  };

  // ==================== ICONS ====================
  const IconHome = ({ size = 20 }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>
      <polyline points="9 22 9 12 15 12 15 22"/>
    </svg>
  );

  const IconLogin = () => (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><polyline points="10 17 15 12 10 7"/><line x1="15" x2="3" y1="12" y2="12"/>
    </svg>
  );

  const IconArrow = () => (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="m5 12h14"/><path d="m12 5 7 7-7 7"/>
    </svg>
  );

  const IconCheck = () => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <polyline points="20 6 9 17 4 12"/>
    </svg>
  );

  const IconShield = ({ size = 18 }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
    </svg>
  );

  const IconCheckCircle = ({ size = 18 }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>
    </svg>
  );

  const IconClock = ({ size = 18 }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
    </svg>
  );

  const IconClose = () => (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M18 6 6 18"/><path d="m6 6 12 12"/>
    </svg>
  );

  const IconMenu = () => (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <line x1="3" x2="21" y1="6" y2="6"/><line x1="3" x2="21" y1="12" y2="12"/><line x1="3" x2="21" y1="18" y2="18"/>
    </svg>
  );

  const IconPhone = () => (
    <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
      <rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" x2="12.01" y1="18" y2="18"/>
    </svg>
  );

  const IconMonitor = () => (
    <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
      <rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/>
    </svg>
  );

  // ==================== RENDER ====================
  return (
    <div className="lp-page">

      {/* ========== NAVBAR ========== */}
      <nav className="lp-navbar" id="lp-navbar">
        <div className="lp-navbar-inner">
          <a href="#" className="lp-nav-brand" onClick={(e) => { e.preventDefault(); window.scrollTo({ top: 0, behavior: 'smooth' }); }}>
            <div className="lp-nav-logo" style={{ background: '#000000', padding: 0, overflow: 'hidden' }}><img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} /></div>
            <div className="lp-nav-brand-text">Koperasi <span>Kita</span></div>
          </a>

          <div className="lp-nav-links">
            <a href="#tentang" onClick={(e) => scrollTo(e, 'tentang')}>Tentang</a>
            <a href="#solusi" onClick={(e) => scrollTo(e, 'solusi')}>Solusi</a>
            <a href="#keunggulan" onClick={(e) => scrollTo(e, 'keunggulan')}>Keunggulan</a>
            <a href="#cara-kerja" onClick={(e) => scrollTo(e, 'cara-kerja')}>Cara Kerja</a>
            <a href="/pembukuan" className="lp-btn-login-nav">
              <IconLogin /> Login Klien
            </a>
          </div>

          <button className="lp-nav-mobile-toggle" onClick={toggleMobileMenu} aria-label="Menu">
            <IconMenu />
          </button>
        </div>
      </nav>

      {/* ========== MOBILE MENU ========== */}
      <div className="lp-mobile-menu" id="lp-mobile-menu" onClick={closeMobileMenu}>
        <div className="lp-mobile-menu-inner" onClick={(e) => e.stopPropagation()}>
          <button className="lp-mobile-menu-close" onClick={closeMobileMenu}><IconClose /></button>
          <div className="lp-mobile-menu-links">
            <a href="#tentang" onClick={(e) => { scrollTo(e, 'tentang'); closeMobileMenu(); }}>Tentang</a>
            <a href="#solusi" onClick={(e) => { scrollTo(e, 'solusi'); closeMobileMenu(); }}>Solusi</a>
            <a href="#keunggulan" onClick={(e) => { scrollTo(e, 'keunggulan'); closeMobileMenu(); }}>Keunggulan</a>
            <a href="#cara-kerja" onClick={(e) => { scrollTo(e, 'cara-kerja'); closeMobileMenu(); }}>Cara Kerja</a>
            <a href="/pembukuan" className="lp-btn-login-mobile">
              <IconLogin /> Login Klien
            </a>
          </div>
        </div>
      </div>


      {/* ========== HERO ========== */}
      <section className="lp-hero">
        <div className="lp-hero-grid-pattern" />
        <div className="lp-container">
          <div className="lp-hero-content">
            <div className="lp-hero-badge">
              <span className="lp-hero-badge-dot" />
              Platform Digital Koperasi Indonesia
            </div>

            <h1 className="lp-hero-title">
              Kelola Koperasi dengan <em>Sistem</em> yang Mengikuti <em>Alur Anda</em>
            </h1>

            <p className="lp-hero-subtitle">
              Koperasi Kita menyediakan aplikasi Android dan sistem pembukuan web
              yang dirancang khusus untuk kebutuhan koperasi simpan pinjam —
              tanpa memaksa Anda mengubah cara kerja.
            </p>

            <div className="lp-hero-actions">
              <a href="#kontak" onClick={(e) => scrollTo(e, 'kontak')} className="lp-btn-primary">
                Konsultasi Gratis <IconArrow />
              </a>
              <a href="#solusi" onClick={(e) => scrollTo(e, 'solusi')} className="lp-btn-secondary">
                Pelajari Selengkapnya
              </a>
            </div>

            <div className="lp-hero-trust">
              <div className="lp-hero-trust-label">Dipercaya oleh pengurus koperasi</div>
              <div className="lp-hero-trust-items">
                <div className="lp-hero-trust-item"><IconShield /> Data Terenkripsi &amp; Aman</div>
                <div className="lp-hero-trust-item"><IconCheckCircle /> Berjalan di Lapangan</div>
                <div className="lp-hero-trust-item"><IconClock /> Sinkronisasi Real-time</div>
              </div>
            </div>
          </div>
        </div>
      </section>


      {/* ========== ABOUT ========== */}
      <section className="lp-about" id="tentang">
        <div className="lp-container">
          <div className="lp-about-grid">
            <div className="lp-about-text lp-fade-up">
              <div className="lp-section-label">Tentang Kami</div>
              <h2 className="lp-section-title">
                Kami Membangun Sistem, <em>Bukan Memaksakan</em> Sistem
              </h2>
              <p className="lp-section-desc">
                Koperasi Kita lahir dari pengalaman nyata di lapangan. Kami memahami bahwa
                setiap koperasi memiliki alur kerja, struktur organisasi, dan kebutuhan yang unik.
                Sistem kami dirancang untuk menyesuaikan diri dengan cara kerja koperasi Anda — bukan sebaliknya.
              </p>
              <p className="lp-section-desc" style={{ marginBottom: 32 }}>
                Dari pendaftaran nasabah, pencairan pinjaman, pengumpulan angsuran harian, mingguan dan bulanan di lapangan,
                hingga pembukuan dan laporan keuangan — seluruh proses dikelola dalam satu ekosistem
                digital yang rapi, aman, dan dapat diandalkan.
              </p>

              <div className="lp-about-principle">
                <p>&ldquo;Aplikasi mengikuti alur koperasi, bukan koperasi yang dipaksa mengikuti aplikasi.&rdquo;</p>
                <cite>— Prinsip Utama Koperasi Kita</cite>
              </div>
            </div>

            <div className="lp-about-visual lp-fade-up lp-d2">
              <div className="lp-about-card-stack">
                <div className="lp-about-card lp-about-card-1">
                  <div className="lp-about-card-icon">
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                      <rect width="18" height="18" x="3" y="3" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/>
                    </svg>
                  </div>
                  <h4>Buku Pokok Digital</h4>
                  <p>Catatan pinjaman dan pembayaran harian nasabah, lengkap dengan riwayat dan saldo real-time.</p>
                </div>
                <div className="lp-about-card lp-about-card-2">
                  <div className="lp-about-card-icon" style={{ background: '#fef3e2', color: '#c8933a' }}>
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                      <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/>
                      <polyline points="14 2 14 8 20 8"/><line x1="16" x2="8" y1="13" y2="13"/><line x1="16" x2="8" y1="17" y2="17"/><line x1="10" x2="8" y1="9" y2="9"/>
                    </svg>
                  </div>
                  <h4>Laporan Keuangan</h4>
                  <p>Neraca, Laba Rugi, dan laporan lainnya tersusun rapi secara otomatis.</p>
                </div>
                <div className="lp-about-card lp-about-card-3">
                  <div className="lp-about-card-icon" style={{ background: '#eef2ff', color: '#4f5fcf' }}>
                    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                      <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>
                      <path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>
                    </svg>
                  </div>
                  <h4>Multi-Peran &amp; Multi-Cabang</h4>
                  <p>Dukungan untuk admin lapangan, supervisor, koordinator, kasir, hingga pimpinan.</p>
                </div>
              </div>
            </div>
          </div>

          <div className="lp-about-stats lp-fade-up">
            <div className="lp-about-stat">
              <div className="lp-about-stat-number">100%</div>
              <div className="lp-about-stat-label">Disesuaikan dengan Alur Koperasi</div>
            </div>
            <div className="lp-about-stat">
              <div className="lp-about-stat-number">24/7</div>
              <div className="lp-about-stat-label">Akses Data Kapan Saja</div>
            </div>
            <div className="lp-about-stat">
              <div className="lp-about-stat-number">Real-time</div>
              <div className="lp-about-stat-label">Sinkronisasi Online &amp; Offline</div>
            </div>
          </div>
        </div>
      </section>


      {/* ========== SOLUTIONS ========== */}
      <section className="lp-solutions" id="solusi">
        <div className="lp-container">
          <div className="lp-solutions-header lp-fade-up">
            <div className="lp-section-label">Solusi Kami</div>
            <h2 className="lp-section-title">Dua Platform, <em>Satu Ekosistem</em></h2>
            <p className="lp-section-desc">
              Operasional di lapangan melalui aplikasi Android, pengawasan dan pembukuan
              melalui web — semuanya terhubung dalam satu sistem terpadu.
            </p>
          </div>

          <div className="lp-solutions-grid">
            {/* Android */}
            <div className="lp-solution-card lp-fade-up lp-d1">
              <div className="lp-solution-icon android"><IconPhone /></div>
              <h3>Aplikasi Android</h3>
              <p className="lp-solution-subtitle">Untuk operasional harian di lapangan</p>
              <p className="lp-solution-desc">
                Aplikasi yang dirancang untuk admin lapangan (PDL), supervisor (Pimpinan), koordinator, Pengawas dan Owner Koperasi.
                Bekerja dengan lancar meskipun koneksi internet tidak stabil — data tetap
                tersimpan dan tersinkronisasi saat online.
              </p>
              <div className="lp-solution-features">
                <div className="lp-solution-feature"><IconCheck /><span>Pendaftaran nasabah baru dengan scan KTP otomatis (OCR)</span></div>
                <div className="lp-solution-feature"><IconCheck /><span>Pencatatan angsuran harian langsung dari lapangan</span></div>
                <div className="lp-solution-feature"><IconCheck /><span>Sistem persetujuan pinjaman bertingkat (dual approval)</span></div>
                <div className="lp-solution-feature"><IconCheck /><span>Mode offline — tetap berjalan tanpa internet</span></div>
                <div className="lp-solution-feature"><IconCheck /><span>Notifikasi real-time untuk setiap aktivitas penting</span></div>
              </div>
            </div>

            {/* Web */}
            <div className="lp-solution-card lp-fade-up lp-d2">
              <div className="lp-solution-icon web"><IconMonitor /></div>
              <h3>Web Pembukuan</h3>
              <p className="lp-solution-subtitle">Untuk pengawasan dan pelaporan keuangan</p>
              <p className="lp-solution-desc">
                Dashboard web yang menyajikan data pembukuan secara lengkap dan terstruktur.
                Akses dari mana saja menggunakan browser — tanpa perlu instalasi.
              </p>
              <div className="lp-solution-features">
                <div className="lp-solution-feature"><IconCheck /><span>Buku Pokok digital dengan data pembayaran harian</span></div>
                <div className="lp-solution-feature"><IconCheck /><span>Filter berdasarkan cabang, admin, dan status nasabah</span></div>
                <div className="lp-solution-feature"><IconCheck /><span>Laporan keuangan otomatis: neraca, laba rugi, SHU</span></div>
                <div className="lp-solution-feature"><IconCheck /><span>Dukungan multi-cabang dalam satu akun</span></div>
                <div className="lp-solution-feature"><IconCheck /><span>Ekspor data ke Excel untuk analisis lebih lanjut</span></div>
              </div>
            </div>
          </div>
        </div>
      </section>


      {/* ========== FEATURES / USP ========== */}
      <section className="lp-features" id="keunggulan">
        <div className="lp-container">
          <div className="lp-features-header lp-fade-up">
            <div className="lp-section-label">Keunggulan</div>
            <h2 className="lp-section-title">Mengapa Memilih <em>Koperasi Kita</em></h2>
            <p className="lp-section-desc">
              Kami tidak hanya menyediakan aplikasi. Kami menyediakan solusi yang
              benar-benar memahami dunia koperasi.
            </p>
          </div>

          <div className="lp-features-grid">
            {[
              {
                title: 'Alur yang Fleksibel',
                desc: 'Setiap koperasi punya cara kerja sendiri. Sistem kami dapat disesuaikan dengan alur operasional dan kebutuhan spesifik koperasi Anda.',
                icon: <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><path d="M12 22c5.523 0 10-4.477 10-10S17.523 2 12 2 2 6.477 2 12s4.477 10 10 10z"/><path d="m9 12 2 2 4-4"/></svg>,
              },
              {
                title: 'Keamanan Data',
                desc: 'Seluruh data disimpan dan dikelola dengan autentikasi berlapis, role-based access control, dan enkripsi. Hanya pihak berwenang yang dapat mengakses.',
                icon: <IconShield size={24} />,
              },
              {
                title: 'Dukungan Multi-Cabang',
                desc: 'Kelola beberapa cabang koperasi dalam satu sistem terpusat. Pimpinan dapat memantau seluruh cabang secara real-time dari satu dashboard.',
                icon: <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><path d="M5 12h14"/><path d="M12 5v14"/><circle cx="12" cy="12" r="10"/></svg>,
              },
              {
                title: 'Dibangun dari Lapangan',
                desc: 'Sistem ini lahir dari pengalaman langsung di operasional koperasi — bukan teori. Setiap fitur dibuat untuk menyelesaikan masalah nyata.',
                icon: <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><path d="M2 20h20"/><path d="M5 20V9l7-5 7 5v11"/><path d="M9 20v-5h6v5"/></svg>,
              },
              {
                title: 'Ringan & Cepat',
                desc: 'Aplikasi Android berjalan lancar di berbagai perangkat, termasuk HP entry-level. Mode offline memastikan pekerjaan tidak terhambat oleh koneksi.',
                icon: <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><path d="M9.59 4.59A2 2 0 1 1 11 8H2m10.59 11.41A2 2 0 1 0 14 16H2m15.73-8.27A2.5 2.5 0 1 1 19.5 12H2"/></svg>,
              },
              {
                title: 'Pendampingan Penuh',
                desc: 'Kami tidak hanya menjual aplikasi lalu pergi. Kami mendampingi proses implementasi, pelatihan, dan penyesuaian hingga tim Anda siap beroperasi.',
                icon: <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="19" x2="19" y1="8" y2="14"/><line x1="22" x2="16" y1="11" y2="11"/></svg>,
              },
            ].map((f, i) => (
              <div key={i} className={`lp-feature-card lp-fade-up lp-d${(i % 3) + 1}`}>
                <div className="lp-feature-icon">{f.icon}</div>
                <h3>{f.title}</h3>
                <p>{f.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>


      {/* ========== HOW IT WORKS ========== */}
      <section className="lp-how-it-works" id="cara-kerja">
        <div className="lp-container">
          <div className="lp-how-header lp-fade-up">
            <div className="lp-section-label">Cara Kerja</div>
            <h2 className="lp-section-title">Empat Langkah Menuju <em>Koperasi Digital</em></h2>
            <p className="lp-section-desc">Proses implementasi yang sederhana, tanpa kerumitan teknis.</p>
          </div>

          <div className="lp-how-steps">
            {[
              { n: '1', title: 'Konsultasi', desc: 'Kami mendengarkan alur kerja dan kebutuhan spesifik koperasi Anda secara mendetail.' },
              { n: '2', title: 'Penyesuaian', desc: 'Sistem disesuaikan agar selaras dengan struktur organisasi dan operasional koperasi Anda.' },
              { n: '3', title: 'Implementasi', desc: 'Tim kami mendampingi proses pemasangan, migrasi data, dan pelatihan untuk seluruh pengguna.' },
              { n: '4', title: 'Pendampingan', desc: 'Dukungan teknis berkelanjutan untuk memastikan sistem berjalan optimal setiap hari.' },
            ].map((s, i) => (
              <div key={i} className={`lp-how-step lp-fade-up lp-d${i + 1}`}>
                <div className="lp-how-step-number">{s.n}</div>
                <h4>{s.title}</h4>
                <p>{s.desc}</p>
              </div>
            ))}
          </div>
        </div>
      </section>


      {/* ========== CTA ========== */}
      <section className="lp-cta-section" id="kontak">
        <div className="lp-container">
          <div className="lp-cta-box lp-fade-up">
            <div className="lp-section-label" style={{ justifyContent: 'center' }}>Mulai Sekarang</div>
            <h2 className="lp-section-title">Siap Membawa Koperasi Anda ke <em>Era Digital?</em></h2>
            <p className="lp-section-desc">
              Konsultasikan kebutuhan koperasi Anda tanpa biaya. Kami akan membantu menemukan
              solusi terbaik yang sesuai dengan cara kerja tim Anda.
            </p>
            <div className="lp-cta-actions">
              <a href="https://wa.me/6285210221082" target="_blank" rel="noopener noreferrer" className="lp-btn-wa">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51-.173-.008-.371-.01-.57-.01-.198 0-.52.074-.792.372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709.306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289.173-1.413-.074-.124-.272-.198-.57-.347m-5.421 7.403h-.004a9.87 9.87 0 01-5.031-1.378l-.361-.214-3.741.982.998-3.648-.235-.374a9.86 9.86 0 01-1.51-5.26c.001-5.45 4.436-9.884 9.888-9.884 2.64 0 5.122 1.03 6.988 2.898a9.825 9.825 0 012.893 6.994c-.003 5.45-4.437 9.884-9.885 9.884m8.413-18.297A11.815 11.815 0 0012.05 0C5.495 0 .16 5.335.157 11.892c0 2.096.547 4.142 1.588 5.945L.057 24l6.305-1.654a11.882 11.882 0 005.683 1.448h.005c6.554 0 11.89-5.335 11.893-11.893a11.821 11.821 0 00-3.48-8.413z"/>
                </svg>
                Hubungi via WhatsApp
              </a>
              <a href="/pembukuan" className="lp-btn-secondary">
                Login Klien
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="m5 12h14"/><path d="m12 5 7 7-7 7"/></svg>
              </a>
            </div>
            <p className="lp-cta-note">Konsultasi gratis — tanpa kewajiban berlangganan</p>
          </div>
        </div>
      </section>


      {/* ========== FOOTER ========== */}
      <footer className="lp-footer">
        <div className="lp-container">
          <div className="lp-footer-top">
            <div className="lp-footer-brand">
              <div className="lp-nav-brand" style={{ marginBottom: 16 }}>
                <div className="lp-nav-logo" style={{ background: '#000000', padding: 0, overflow: 'hidden' }}><img src="/logo.png" alt="Koperasi Kita" style={{ width: '100%', height: '100%', objectFit: 'cover' }} /></div>
                <div className="lp-nav-brand-text" style={{ color: '#fff' }}>Koperasi <span>Kita</span></div>
              </div>
              <p>Sistem digital untuk koperasi simpan pinjam yang mengutamakan kemudahan, keamanan, dan kesesuaian dengan cara kerja koperasi Anda.</p>
            </div>

            <div className="lp-footer-links-group">
              <h5>Navigasi</h5>
              <a href="#tentang" onClick={(e) => scrollTo(e, 'tentang')}>Tentang</a>
              <a href="#solusi" onClick={(e) => scrollTo(e, 'solusi')}>Solusi</a>
              <a href="#keunggulan" onClick={(e) => scrollTo(e, 'keunggulan')}>Keunggulan</a>
              <a href="#cara-kerja" onClick={(e) => scrollTo(e, 'cara-kerja')}>Cara Kerja</a>
            </div>

            <div className="lp-footer-links-group">
              <h5>Platform</h5>
              <a href="/pembukuan">Login Klien</a>
              <a href="https://wa.me/6285210221082" target="_blank" rel="noopener noreferrer">Hubungi Kami</a>
            </div>
          </div>

          <div className="lp-footer-bottom">
            <span>&copy; 2026 Koperasi Kita. Hak cipta dilindungi.</span>
            <span>koperasi-kita.com</span>
          </div>
        </div>
      </footer>

    </div>
  );
}
