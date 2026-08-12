package com.example.koperasikitagodangulu.offline

import java.security.MessageDigest
import java.util.Locale

/**
 * =========================================================================
 * DERIVASI ID DETERMINISTIK (UUIDv5) — CERMIN scripts/migration/migrate.js
 * =========================================================================
 *
 * KENAPA INI ADA
 * --------------
 * RTDB mengidentifikasi nasabah dengan pasangan (adminUid, pelangganId).
 * Postgres memakai UUID. Skrip migrasi menurunkan UUID itu secara
 * deterministik dari path Firebase-nya, bukan mengacaknya:
 *
 *     migrate.js →  ID.nasabah = uuidv5("nasabah:" + adminUid + "/" + pid)
 *
 * Klien HARUS memakai rumus yang PERSIS SAMA. Kalau tidak, pembayaran yang
 * dikirim aplikasi setelah cutover akan menunjuk pinjaman_id yang tidak ada,
 * dan setiap operasi gagal dengan foreign key violation — untuk nasabah yang
 * datanya jelas-jelas sudah ada di server.
 *
 * ⚠ JANGAN mengubah string prefiks ("nasabah:", "pinjaman:", …) maupun
 *   namespace di bawah. Mengubahnya = seluruh id berubah = seluruh tautan
 *   ke data hasil migrasi putus. Kalau berubah di sini, WAJIB berubah juga
 *   di migrate.js, dan sebaliknya.
 *
 * Algoritme: RFC 4122 versi 5 (SHA-1), namespace DNS.
 * =========================================================================
 */
object SupabaseIds {

    /** Namespace DNS RFC 4122 — identik dengan konstanta NS di migrate.js. */
    private val NAMESPACE_DNS: ByteArray = byteArrayOf(
        0x6b.toByte(), 0xa7.toByte(), 0xb8.toByte(), 0x11.toByte(),
        0x9d.toByte(), 0xad.toByte(), 0x11.toByte(), 0xd1.toByte(),
        0x80.toByte(), 0xb4.toByte(), 0x00.toByte(), 0xc0.toByte(),
        0x4f.toByte(), 0xd4.toByte(), 0x30.toByte(), 0xc8.toByte()
    )

    fun uuidV5(name: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(NAMESPACE_DNS)
        md.update(name.toByteArray(Charsets.UTF_8))
        val b = md.digest()

        // versi 5 + varian RFC 4122 — urutan operasi sama dengan migrate.js
        b[6] = ((b[6].toInt() and 0x0f) or 0x50).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()

        val hex = StringBuilder(32)
        for (i in 0 until 16) hex.append(String.format(Locale.US, "%02x", b[i]))
        val h = hex.toString()
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-" +
                "${h.substring(16, 20)}-${h.substring(20, 32)}"
    }

    // --- Turunan per entitas. Cermin objek ID di migrate.js ----------------

    fun user(firebaseUid: String): String = uuidV5("user:$firebaseUid")

    fun nasabah(adminUid: String, pelangganId: String): String =
        uuidV5("nasabah:$adminUid/$pelangganId")

    fun pinjaman(adminUid: String, pelangganId: String, pinjamanKe: Int): String =
        uuidV5("pinjaman:$adminUid/$pelangganId/$pinjamanKe")

    /**
     * Pembayaran BARU dari aplikasi selalu membawa clientOpId (UUID acak yang
     * distempel PelangganViewModel). Jalur "derive:" hanya dipakai skrip
     * migrasi untuk baris lama yang clientOpId-nya kosong — TIDAK dipakai di
     * sini, dan memang tidak boleh: menurunkan id dari (tanggal, jumlah) pada
     * klien akan menabrakkan dua setoran sah yang kebetulan sama.
     */
    fun pembayaranDariOpId(clientOpId: String): String = uuidV5("bayar:op:$clientOpId")

    fun jadwal(adminUid: String, pelangganId: String, pinjamanKe: Int, urutan: Int): String =
        uuidV5("jadwal:$adminUid/$pelangganId/$pinjamanKe/$urutan")

    fun statusKhusus(cabangId: String, pelangganId: String): String =
        uuidV5("statuskhusus:${normalisasiCabang(cabangId)}/$pelangganId")

    /**
     * Entri jurnal. Cermin ID.jurnal di migrate.js:
     *     uuidv5("jurnal:" + slugCabang(cabang) + "/" + bulan + "/" + pushKey)
     * Dipakai sekaligus sebagai `client_op_id`, sehingga entri hasil migrasi
     * dan entri baru dari aplikasi tidak mungkin terduplikasi.
     */
    fun jurnal(cabangId: String, yearMonth: String, pushKey: String): String =
        uuidV5("jurnal:${normalisasiCabang(cabangId)}/$yearMonth/$pushKey")

    /**
     * cabangId RTDB adalah teks bebas dengan spasi ("simpang empat unit 1").
     * Normalisasi HARUS identik dengan slugCabang() di migrate.js, kalau tidak
     * baris klien akan menunjuk cabang yang berbeda dari hasil migrasi.
     */
    fun normalisasiCabang(cabangId: String?): String =
        (cabangId ?: "").trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
