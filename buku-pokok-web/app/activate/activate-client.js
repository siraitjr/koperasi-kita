'use client';
import { useRef, useState } from 'react';
import './activate.css';

export default function ActivateClient({ code, initialStatus, businessName }) {
  const [query, setQuery] = useState('');
  const [places, setPlaces] = useState([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState(null);
  const [state, setState] = useState(initialStatus === 'active' ? 'active' : 'idle');
  const [reviewUrl, setReviewUrl] = useState('');
  const [errorMsg, setErrorMsg] = useState('');
  const timerRef = useRef(null);

  async function search(q) {
    if (q.trim().length < 3) { setPlaces([]); setSearching(false); return; }
    try {
      const res = await fetch('/api/places/autocomplete?q=' + encodeURIComponent(q));
      const json = await res.json();
      setPlaces(json.places || []);
    } catch (e) {
      setPlaces([]);
      setErrorMsg('Gagal mencari tempat. Periksa koneksi, lalu coba lagi.');
    } finally {
      setSearching(false);
    }
  }

  function onChange(e) {
    const q = e.target.value;
    setQuery(q);
    setSelected(null);
    setErrorMsg('');
    clearTimeout(timerRef.current);
    if (q.trim().length < 3) { setPlaces([]); setSearching(false); return; }
    setSearching(true);
    timerRef.current = setTimeout(() => search(q), 450);
  }

  async function activate() {
    if (!selected || state === 'saving') return;
    setState('saving');
    setErrorMsg('');
    try {
      const res = await fetch('/api/activate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, place_id: selected.id, business_name: selected.name }),
      });
      const json = await res.json();
      if (res.ok && json.ok) {
        setReviewUrl(json.review_url || '');
        setState('done');
      } else if (res.status === 409) {
        setState('active');
      } else {
        setState('idle');
        setErrorMsg('Aktivasi gagal (' + (json.error || 'tidak diketahui') + '). Silakan coba lagi.');
      }
    } catch (e) {
      setState('idle');
      setErrorMsg('Jaringan bermasalah. Silakan coba lagi.');
    }
  }

  return (
    <div className="pk-wrap">
      <div className="pk-card">
        <div className="pk-head">
          <div className="pk-brand">Proyekita</div>
          <div className="pk-title">Aktivasi Kartu Ulasan</div>
          <span className="pk-code">#{code}</span>
        </div>
        <div className="pk-body">
          {state === 'active' ? (
            <div className="pk-info">
              <b>Kartu ini sudah aktif.</b>
              <p>Terhubung ke: {businessName || 'tempat yang sudah dipilih'}. Hubungi dukungan Proyekita bila perlu perubahan.</p>
            </div>
          ) : state === 'done' ? (
            <div className="pk-ok">
              <div className="pk-check">
                <svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5" /></svg>
              </div>
              <div className="pk-okname">{selected ? selected.name : businessName}</div>
              <p className="pk-note">Kartu #{code} aktif. Mulai sekarang setiap tap NFC atau scan QR langsung membuka form ulasan Google usaha ini.</p>
              {reviewUrl ? (
                <a className="pk-link" href={reviewUrl} target="_blank" rel="noopener noreferrer">Tes Link Review</a>
              ) : null}
            </div>
          ) : (
            <>
              <div className="pk-label">Cari nama usaha sebagaimana terdaftar di Google Maps</div>
              <div className="pk-inputwrap">
                <input className="pk-input" placeholder="Contoh: Kopi Kenangan Bukittinggi" value={query} onChange={onChange} />
                {searching ? <span className="pk-spin" /> : null}
              </div>
              {places.length > 0 ? (
                <div className="pk-list">
                  {places.map(p => (
                    <button key={p.id} type="button" className={'pk-item' + (selected && selected.id === p.id ? ' sel' : '')} onClick={() => setSelected(p)}>
                      <span className="pk-dot" />
                      <span>{p.name}</span>
                    </button>
                  ))}
                </div>
              ) : (!searching && query.trim().length >= 3 ? (
                <p className="pk-note" style={{ marginTop: 10 }}>Tidak ditemukan. Coba ejaan atau kata kunci lain.</p>
              ) : null)}
              {errorMsg ? <div className="pk-err">{errorMsg}</div> : null}
              <button className="pk-btn" type="button" disabled={!selected || state === 'saving'} onClick={activate}>
                {state === 'saving' ? (<><span className="pk-btnspin" />Mengaktivasi…</>) : ('Aktifkan Kartu Ini')}
              </button>
            </>
          )}
          <div className="pk-foot">© 2026 Proyekita • koperasi-kita.com</div>
        </div>
      </div>
    </div>
  );
}