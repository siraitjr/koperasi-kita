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

    // ── POIN 4 & 5: acuan = HANYA tanggalPencairan (no fallback) ───────────
    val tglPencairan = p.tanggalPencairan
    if (tglPencairan.isNotEmpty() && tglPencairan == today) {
        // POIN 5: Top-up Cairkan today → anchor pinjaman LAMA.
        if (p.pinjamanKe > 1 && p.besarPinjamanLamaSebelumTopUp > 0) {
            return p.besarPinjamanLamaSebelumTopUp.toLong() * 3L / 100L
        }
        // Pinjaman pertama Cairkan today → mulai besok.
        return 0L
    }
    val pencairanDate = parseTanggalIndo(tglPencairan) ?: return 0L
    // Cairkan di masa depan relatif kolom → belum aktif.
    if (pencairanDate.after(curDate)) return 0L

    // ── POIN 3: Batas 3 bulan kalender (Option A: drop di hari boundary) ───
    val acuanCal = Calendar.getInstance(WIB).apply { time = pencairanDate }
    val curCal = Calendar.getInstance(WIB).apply { time = curDate }
    val acuanIdx = acuanCal.get(Calendar.YEAR) * 12 + acuanCal.get(Calendar.MONTH)
    val curIdx = curCal.get(Calendar.YEAR) * 12 + curCal.get(Calendar.MONTH)
    if (curIdx - acuanIdx > 3) return 0L

    return targetFlat
}
