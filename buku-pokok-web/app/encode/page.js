'use client';
import { useEffect, useRef, useState } from 'react';
import './encode.css';

const BASE_PATTERN = /\/c\/([A-Za-z0-9]+)/;

export default function EncodePage() {
  const [pass, setPass] = useState('');
  const [passInput, setPassInput] = useState('');
  const [stage, setStage] = useState('pass');
  const [code, setCode] = useState('');
  const [card, setCard] = useState(null);
  const [msg, setMsg] = useState('');
  const [count, setCount] = useState(0);
  const [alarm, setAlarm] = useState(null);
  const [manual, setManual] = useState('');
  const [lastDone, setLastDone] = useState('');
  const scannerRef = useRef(null);
  const fileRef = useRef(null);
  const handledRef = useRef(false);
  const nfcOk = typeof window !== 'undefined' && 'NDEFReader' in window;

  useEffect(() => {
    const saved = sessionStorage.getItem('encode_key');
    if (saved) { setPass(saved); setStage('idle'); }
  }, []);

  function stopScanner() {
    if (scannerRef.current) {
      const s = scannerRef.current;
      scannerRef.current = null;
      s.stop().catch(() => {});
      s.clear().catch(() => {});
    }
  }

  async function apiGet(c) {
    try {
      const res = await fetch('/api/encode?code=' + encodeURIComponent(c), { headers: { 'x-encode-key': pass } });
      if (res.status === 401) { sessionStorage.removeItem('encode_key'); setPass(''); setStage('pass'); setMsg('Sesi berakhir. Masukkan passcode lagi.'); return null; }
      if (res.status === 404) { setMsg('Kode ' + c + ' tidak ada di database.'); return null; }
      const json = await res.json();
      return json.card || null;
    } catch (e) {
      setMsg('Jaringan gagal saat memeriksa kode ' + c + '. Coba lagi.');
      return null;
    }
  }

  async function submitPass() {
    try {
      const res = await fetch('/api/encode?code=PING', { headers: { 'x-encode-key': passInput } });
      if (res.ok) { sessionStorage.setItem('encode_key', passInput); setPass(passInput); setStage('idle'); setMsg(''); }
      else setMsg('Passcode salah.');
    } catch (e) { setMsg('Jaringan gagal. Coba lagi.'); }
  }

  async function loadCard(c) {
    setMsg('QR terbaca: #' + c + ' — memeriksa database…');
    const cardData = await apiGet(c);
    if (cardData) { setCode(c); setCard(cardData); setStage('card'); }
    else { setStage('idle'); }
  }

  function startScan() { setMsg(''); setStage('scanning'); }

  async function onPhoto(e) {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    setMsg('Membaca foto QR…');
    try {
      const mod = await import('html5-qrcode');
      const Html5Qrcode = mod.Html5Qrcode || mod.default;
      const h = new Html5Qrcode('photo-reader');
      const text = await h.scanFile(file, false);
      const t = String(text).trim();
      const m = t.match(BASE_PATTERN);
      if (m) loadCard(m[1].toUpperCase());
      else if (/^[A-Za-z0-9]{4,10}$/.test(t)) loadCard(t.toUpperCase());
      else { setMsg('Foto ini bukan QR kartu Proyekita. Isinya: ' + t.slice(0, 60)); }
    } catch (err) {
      setMsg('QR tidak terbaca dari foto. Ambil foto lebih dekat dan terang.');
    }
    e.target.value = '';
  }

  useEffect(() => {
    if (stage !== 'scanning') return;
    let alive = true;
    (async () => {
      try {
        const mod = await import('html5-qrcode');
        const Html5Qrcode = mod.Html5Qrcode || mod.default;
        if (!alive) return;
        const h = new Html5Qrcode('encode-reader');
        scannerRef.current = h;
                await h.start({ facingMode: 'environment' }, { fps: 10 }, (text) => {
          if (!alive) return;
          alive = false;
          stopScanner();
          const t = String(text).trim();
          const m = t.match(BASE_PATTERN);
          if (m) loadCard(m[1].toUpperCase());
          else if (/^[A-Za-z0-9]{4,10}$/.test(t)) loadCard(t.toUpperCase());
          else { setStage('idle'); setMsg('QR ini bukan QR kartu Proyekita. Isinya: ' + t.slice(0, 60)); }
        }, () => {});
      } catch (e) {
        if (alive) { alive = false; setStage('idle'); setMsg('Kamera tidak bisa dibuka: ' + (e && e.message ? e.message : 'izin ditolak')); }
      }
    })();
    return () => { alive = false; stopScanner(); };
  }, [stage]);

  function parseTagUrl(message) {
    try {
      for (const record of message.records) {
        if (record.recordType === 'url' && typeof record.data === 'string') return record.data;
        let t = null;
        if (typeof record.data === 'string') t = record.data;
        else if (record.data) { try { t = new TextDecoder().decode(record.data); } catch (e) {} }
        if (t && t.indexOf('/c/') !== -1) return t;
      }
    } catch (e) {}
    return null;
  }

  async function finishOk(foundUrl) {
    try {
      const res = await fetch('/api/encode', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'x-encode-key': pass },
        body: JSON.stringify({ code, verified_url: foundUrl }),
      });
      if (res.ok) {
        setCount(c => c + 1);
        setLastDone(code);
        if (navigator.vibrate) navigator.vibrate(90);
        setStage('done');
      } else {
        setStage('card');
        setMsg('Gagal mencatat ke database (kode ' + res.status + '). Chip sudah terisi tapi belum tercatat — ulangi verifikasi.');
      }
    } catch (e) {
      setStage('card');
      setMsg('Jaringan gagal saat mencatat. Chip sudah terisi — ulangi verifikasi setelah koneksi stabil.');
    }
  }

  async function writeNfc() {
    if (!nfcOk) { setMsg('Perangkat ini tidak mendukung Web NFC. Pakai Chrome di HP Android.'); return; }
    const url = 'https://koperasi-kita.com/c/' + code;
    setStage('writing');
    setMsg('Tempelkan chip NFC di belakang HP sampai ada getaran/beep…');
    try {
      const reader = new NDEFReader();
      await reader.write({ records: [{ recordType: 'url', data: url }] });
      handledRef.current = false;
      setStage('verify');
      setMsg('Tulis berhasil. ANGKAT chip, lalu TEMPELKAN LAGI untuk verifikasi isi.');
      reader.onreading = (event) => {
        if (handledRef.current) return;
        const found = parseTagUrl(event.message);
        if (!found) return;
        handledRef.current = true;
        reader.stop().catch(() => {});
        const m = found.match(BASE_PATTERN);
        const foundCode = m ? m[1].toUpperCase() : null;
        if (foundCode === code) finishOk(found);
        else {
          setAlarm({ found: foundCode || found, expected: code });
          if (navigator.vibrate) navigator.vibrate([300, 120, 300]);
          setStage('alarm');
        }
      };
      reader.onreadingerror = () => setMsg('Chip terdeteksi tapi gagal dibaca. Tempelkan ulang lebih rapat.');
      await reader.scan();
    } catch (e) {
      setStage('card');
      setMsg('Gagal menulis: ' + (e && e.message ? e.message : 'chip terkunci atau bukan tag NFC'));
    }
  }

  return (
    <div className="en-wrap">
      <div className="en-card">
        <div className={'en-head' + (stage === 'alarm' ? ' red' : '')}>
          <div className="en-title">Proyekita • Encoder NFC</div>
          <div className="en-count">✓ {count}</div>
        </div>
        <div className="en-body">
          {stage === 'pass' && (
            <>
              <input className="en-input" type="password" placeholder="Passcode encoder" value={passInput} onChange={e => setPassInput(e.target.value)} />
              <button className="en-big" style={{ marginTop: 12 }} onClick={submitPass}>MASUK</button>
              {msg ? <div className="en-msg err">{msg}</div> : null}
            </>
          )}
          {stage === 'idle' && (
            <>
              <button className="en-big" onClick={startScan}>SCAN QR KARTU</button>
              <input className="en-input" placeholder="atau ketik kode manual, mis. PK0012" value={manual} onChange={e => setManual(e.target.value)} />
                            <button className="en-big ghost" onClick={() => { if (manual.trim()) loadCard(manual.trim().toUpperCase()); }}>PAKAI KODE MANUAL</button>
              <div id="photo-reader" style={{ display: 'none' }} />
              <input ref={fileRef} type="file" accept="image/*" capture="environment" style={{ display: 'none' }} onChange={onPhoto} />
              <button className="en-big ghost" onClick={() => { if (fileRef.current) fileRef.current.click(); }}>PAKAI FOTO QR (jika kamera rewel)</button>
              {!nfcOk ? <div className="en-msg err">Perangkat ini tidak punya Web NFC. Penulisan chip harus dari Chrome di HP Android.</div> : null}
              {msg ? <div className="en-msg err">{msg}</div> : null}
            </>
          )}
          {stage === 'scanning' && (
            <>
              <div id="encode-reader" className="en-reader" />
              <div className="en-msg">Arahkan kamera ke QR code yang tercetak di kartu…</div>
              {msg ? <div className="en-msg err">{msg}</div> : null}
              <button className="en-big ghost" onClick={() => setStage('idle')}>BATAL</button>
            </>
          )}
          {stage === 'card' && card && (
            <>
              <div className="en-code">#{code}</div>
              <div className="en-sub">https://koperasi-kita.com/c/{code}</div>
              <span className={'en-badge' + (card.encoded_at ? ' on' : '')}>{card.encoded_at ? 'SUDAH DI-ENCODE sebelumnya' : (card.status === 'active' ? 'KARTU AKTIF' : 'BELUM DI-ENCODE')}</span>
              <button className="en-big blue" disabled={!nfcOk} onClick={writeNfc}>TULIS KE CHIP NFC</button>
              <button className="en-big ghost" onClick={() => setStage('idle')}>BATAL</button>
              {msg ? <div className="en-msg err">{msg}</div> : null}
            </>
          )}
          {(stage === 'writing' || stage === 'verify') && (
            <>
              <div className="en-code">#{code}</div>
              <button className="en-big blue" disabled><span className="en-spin" />{stage === 'writing' ? 'MENULIS…' : 'VERIFIKASI…'}</button>
              <div className="en-msg">{msg}</div>
            </>
          )}
          {stage === 'done' && (
            <>
              <div className="en-check"><svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5" /></svg></div>
              <div className="en-code">#{lastDone}</div>
              <div className="en-sub">Chip terisi & terverifikasi • tercatat di database</div>
              <button className="en-big" onClick={startScan}>SCAN KARTU BERIKUTNYA</button>
              <button className="en-big ghost" onClick={() => setStage('idle')}>SELESAI DULU</button>
            </>
          )}
          {stage === 'alarm' && alarm && (
            <div className="en-alarm">
              <div className="x">!</div>
              <p><b>CHIP SALAH!</b></p>
              <p>Isi chip: <b>{alarm.found}</b><br />Seharusnya: <b>{alarm.expected}</b></p>
              <p>Pisahkan chip ini ke kotak kesalahan. Jangan ditempel ke kartu ini.</p>
              <button className="en-big" onClick={() => { setAlarm(null); setStage('idle'); }}>MENGERTI — SCAN ULANG</button>
            </div>
          )}
          <div className="en-foot">© 2026 Proyekita • alat produksi internal</div>
        </div>
      </div>
    </div>
  );
}