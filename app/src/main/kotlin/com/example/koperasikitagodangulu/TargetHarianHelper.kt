package com.example.koperasikitagodangulu

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// =========================================================================
// SHARED HELPER: kontribusi target harian Android (single source of truth).
// -------------------------------------------------------------------------
// Cermin 1:1 dari buku-pokok-web/lib/target.js (isEligibleForTarget) supaya
// dashboard Android, summary lokal yang dihitung ulang, dan filter
// "Pelanggan Yang Harus Dikunjungi" TIDAK divergen dari Web/CF.
//
// Aturan operasional final pimpinan (07 Jun 2026, kombinasi 5 skenario):
//
//   POIN 1 — Lunas H+1
//     tanggalLunasCicilan == today → tetap target (hari terakhir aktif).
//     tanggalLunasCicilan <  today → 0 (sudah lunas sebelum kolom).
//     tanggalLunasCicilan >  today → fall-through (masih aktif pada kolom historis).
//
//   POIN 2 — MENUNGGU_PENCAIRAN H+1
//     tanggalStatusKhusus == today → tetap target.
//     tanggalStatusKhusus <  today → 0.
//     tanggalStatusKhusus >  today → fall-through.
//
//   POIN 3 — Batas 3 bulan kalender (Option A, aturan 04 Jun 2026 pimpinan):
//     curMonthIdx − pencairanMonthIdx > 3 → 0 ON THAT EXACT DAY (BUKAN H+1).
//     Contoh: cair 28 Feb → drop 0 mulai 1 Jun (hari itu juga).
//
//   POIN 4 — Target HANYA mulai H+1 SETELAH Cairkan (Scenario 4 fix)
//     tanggalPencairan kosong / == today / > today → 0.
//     Fallback ke tanggalDaftar/Pengajuan DILARANG (LEAK target prematur).
//
//   POIN 5 — Top-Up Cairkan TODAY (Scenario 5 fix)
//     tanggalPencairan == today & pinjamanKe > 1 & besarPinjamanLamaSebelumTopUp > 0
//     → target = 3% × besarPinjamanLamaSebelumTopUp (anchor pinjaman lama).
//     Pinjaman BARU efektif besok (jalur normal POIN 4 mengangkatnya
//     karena tanggalPencairan < today H+1).
//
// Return Long rupiah, 0 bila tidak eligible. Dipanggil dari:
//   - PelangganViewModel.calculateTargetHarian() (dashboard admin lapangan)
//   - PelangganViewModel.calculateAdminSummaryFromRawData()
//     (rebuild summary lokal dipakai triggerUpdateAllSummaries)
//   - RingkasanDashboardScreen (admin lapangan ringkasan)
//   - PelangganYangHarusDikunjungiScreen (kunjungan harian admin lapangan)
//
// CATATAN integritas:
//   nasabahAktif COUNT (di RingkasanDashboardScreen.kt:118 dan
//   calculateAdminSummaryFromRawData) sengaja TIDAK disentuh oleh fix ini —
//   itu kategori "berapa nasabah", bukan "berapa rupiah target". Helper
//   hanya menjawab pertanyaan target. Bila ke depan pimpinan minta
//   konsistensi penuh COUNT vs Web/CF, fix tersendiri tanpa mengganggu UI.
// =========================================================================

private val WIB: TimeZone = TimeZone.getTimeZone("Asia/Jakarta")

private fun dateFormatWIB(): SimpleDateFormat =
    SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).apply { timeZone = WIB }

private fun parseTanggalIndo(s: String): Date? {
    if (s.isBlank()) return null
    return try { dateFormatWIB().parse(s) } catch (_: Exception) { null }
}

/** "dd MMM yyyy" WIB hari ini — single source of truth tanggal lokal. */
fun todayIndo(): String = dateFormatWIB().format(Calendar.getInstance(WIB).time)

/**
 * Kontribusi target harian dari satu nasabah pada `today`. 0 bila tidak eligible.
 *
 * Aturan lengkap di header file. Aman dipanggil dari hot path (Compose recomposition
 * & summary recalc) — parser tanggal lokal per pemanggilan, tanpa state global.
 */
fun calculateTargetContribution(p: Pelanggan, today: String): Long {
    val curDate = parseTanggalIndo(today) ?: return 0L

    // ── Pre-guard live-status (paritas lib/target.js L135-153) ─────────────
    val statusLower = p.status.lowercase()
    val isStatusAktif = statusLower == "aktif" || statusLower == "active"
    val lunasHariIni = p.tanggalLunasCicilan == today
    val isMP = p.statusKhusus == "MENUNGGU_PENCAIRAN"
        && p.statusPencairanSimpanan != "Dicairkan"
    val mpHariIni = isMP && p.tanggalStatusKhusus == today

    val lunasDateGuard = parseTanggalIndo(p.tanggalLunasCicilan)
    val masihAktifPadaTanggal = lunasDateGuard != null && lunasDateGuard.after(curDate)

    val mpDateGuard = parseTanggalIndo(p.tanggalStatusKhusus)
    val belumMPPadaTanggal = isMP && mpDateGuard != null && mpDateGuard.after(curDate)

    if (!isStatusAktif && !lunasHariIni && !mpHariIni
        && !masihAktifPadaTanggal && !belumMPPadaTanggal) return 0L

    val targetFlat = p.besarPinjaman.toLong() * 3L / 100L
    val totalDibayar = p.pembayaranList.sumOf { pay ->
        pay.jumlah.toLong() + pay.subPembayaran.sumOf { sub -> sub.jumlah.toLong() }
    }
    val totalPelunasanL = p.totalPelunasan.toLong()
    val isSudahLunas = totalPelunasanL > 0L && totalDibayar >= totalPelunasanL

    // ── POIN 1: Cabang LUNAS ───────────────────────────────────────────────
    if (isSudahLunas) {
        if (p.tanggalLunasCicilan == today) return targetFlat
        if (lunasDateGuard != null && lunasDateGuard.before(curDate)) return 0L
        // lunasDate > cur → fall through (masih aktif pada kolom historis)
    }

    // ── POIN 2: Cabang MENUNGGU_PENCAIRAN ──────────────────────────────────
    if (isMP) {
        if (mpDateGuard != null) {
            if (mpDateGuard.before(curDate)) return 0L
            // ≥ cur → include
        } else {
            if (!mpHariIni) return 0L
        }
    }

    // =====================================================================
    // ── POIN 4 & 5: acuan tanggal pencairan, dgn FALLBACK utk legacy data ──
    // ---------------------------------------------------------------------
    // Audit pimpinan 08 Jun 2026 (Resort Sederhana & Harmonis): commit
    // sebelumnya (Scenario 4) melarang fallback ke tglPengajuan/tglDaftar
    // SAMA SEKALI. Itu BENAR untuk mencegah leak nasabah approved-belum-cair,
    // TAPI terlalu agresif untuk nasabah LEGACY yang sudah `status="Aktif"`
    // namun field `tanggalPencairan` kosong (workflow lama / migrasi data).
    //
    // Bukti dari audit forensik:
    //   - Resort Harmonis (file 69d60d8c): 7 nasabah Aktif tglPencairan=""
    //     (Tasni, Rodiah, Tata, SANTI, MEMO, TRI, SUPRIANTO) → total target
    //     hilang Rp 144.000 → persis match gap manual book.
    //   - Resort Sederhana (file 4842194b): 1 nasabah Aktif tglPencairan=""
    //     (Irawati, besar 1.000.000) → target hilang Rp 30.000 → persis match
    //     gap manual book.
    //
    // ATURAN BARU:
    //   BRANCH A (tglPencairan ADA) → jalur Scenario 4/5 asli (DIPERTAHANKAN):
    //     - tglPencairan == today + pinjamanKe>1 + besarLama>0 → anchor lama.
    //     - tglPencairan == today + pinjaman pertama          → 0 (besok mulai).
    //     - tglPencairan > today                              → 0 (belum cair).
    //     - tglPencairan < today + 3-bulan boundary check     → eligible.
    //
    //   BRANCH B (tglPencairan KOSONG):
    //     - Sampai sini berarti pre-guard live status lolos → nasabah pasti
    //       status="Aktif" / lunas-hari-ini / MP-hari-ini / masih-aktif-pada-
    //       tanggal / belum-MP-pada-tanggal. JADI Scenario 4 (reject approved-
    //       belum-cair) tetap terjaga: nasabah dgn status "Disetujui" /
    //       "Menunggu Approval" sudah ditolak di pre-guard (line 73-78).
    //     - Fallback acuan ke tglPengajuan → tglDaftar utk 3-bulan check
    //       (legacy data sering hanya punya tanggal pengajuan/daftar).
    //     - TIDAK menjalankan gate "cair hari ini" (tidak ada tanggal pencairan).
    //     - Bila kedua fallback juga kosong → tetap dihitung (legacy nasabah
    //       aktif tanpa tanggal apapun = buku manual tetap menagih mereka).
    // =====================================================================
    val tglPencairan = p.tanggalPencairan

    // BRANCH A: jalur normal — tanggalPencairan ADA
    if (tglPencairan.isNotEmpty()) {
        if (tglPencairan == today) {
            // POIN 5: Top-up Cairkan today → anchor pinjaman LAMA.
            if (p.pinjamanKe > 1 && p.besarPinjamanLamaSebelumTopUp > 0) {
                return p.besarPinjamanLamaSebelumTopUp.toLong() * 3L / 100L
            }
            // Pinjaman pertama Cairkan today → mulai besok.
            return 0L
        }
        val pencairanDate = parseTanggalIndo(tglPencairan) ?: return 0L
        if (pencairanDate.after(curDate)) return 0L
        // POIN 3: 3-bulan kalender Option A.
        val acuanCalA = Calendar.getInstance(WIB).apply { time = pencairanDate }
        val curCalA = Calendar.getInstance(WIB).apply { time = curDate }
        val acuanIdxA = acuanCalA.get(Calendar.YEAR) * 12 + acuanCalA.get(Calendar.MONTH)
        val curIdxA = curCalA.get(Calendar.YEAR) * 12 + curCalA.get(Calendar.MONTH)
        if (curIdxA - acuanIdxA > 3) return 0L
        return targetFlat
    }

    // BRANCH B: legacy fallback — tanggalPencairan KOSONG, status=Aktif (sudah
    // lolos pre-guard). Pakai tglPengajuan → tglDaftar utk 3-bulan check saja.
    val tglAcuan = p.tanggalPengajuan.takeIf { it.isNotBlank() } ?: p.tanggalDaftar
    val acuanDate = parseTanggalIndo(tglAcuan)
    if (acuanDate == null) {
        // Legacy aktif tanpa tanggal apapun → tetap dihitung (rule pimpinan
        // 08 Jun 2026: buku manual menagih nasabah aktif walau metadata cacat).
        return targetFlat
    }
    if (acuanDate.after(curDate)) return 0L  // pengajuan di masa depan → safety
    val acuanCalB = Calendar.getInstance(WIB).apply { time = acuanDate }
    val curCalB = Calendar.getInstance(WIB).apply { time = curDate }
    val acuanIdxB = acuanCalB.get(Calendar.YEAR) * 12 + acuanCalB.get(Calendar.MONTH)
    val curIdxB = curCalB.get(Calendar.YEAR) * 12 + curCalB.get(Calendar.MONTH)
    if (curIdxB - acuanIdxB > 3) return 0L

    return targetFlat
}
