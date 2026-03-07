// app/layout.js
import './globals.css';

export const metadata = {
  title: 'Koperasi Kita — Sistem Pembukuan Digital',
  description: 'Sistem Pembukuan Digital Koperasi Kita Godang Ulu',
  icons: {
    icon: '/logo.png',
  },
};

export default function RootLayout({ children }) {
  return (
    <html lang="id">
      <head>
        <link
          href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@300;400;500;600;700;800&family=DM+Mono:wght@400;500&family=Instrument+Serif:ital@0;1&display=swap"
          rel="stylesheet"
        />
      </head>
      <body>{children}</body>
    </html>
  );
}