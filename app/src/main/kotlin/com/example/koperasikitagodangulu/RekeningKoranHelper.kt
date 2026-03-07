package com.example.koperasikitagodangulu.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import com.example.koperasikitagodangulu.Pelanggan  // ← TAMBAHKAN INI!

/**
 * Helper untuk generate secure link rekening koran
 *
 * CARA KERJA:
 * 1. Token di-generate di Android (instant, tidak perlu internet)
 * 2. Token berisi: adminUid:pelangganId:timestamp:signature
 * 3. Signature menggunakan HMAC-SHA256 dengan secret key
 * 4. Nasabah buka link → Cloud Function verify → return data
 *
 * KEAMANAN:
 * - Token tidak bisa dipalsukan tanpa secret key
 * - Token tidak bisa dimodifikasi (signature akan invalid)
 * - Data sensitif (NIK) tidak ditampilkan di rekening koran
 */
object RekeningKoranLinkHelper {

    // =========================================================================
    // SECRET KEY - HARUS SAMA DENGAN DI CLOUD FUNCTION!
    // =========================================================================
    private const val SECRET_KEY = "K0p3r4s1K1t4G0d4ngUluS3cur3Key!"

    // Base URL halaman rekening koran
    private const val BASE_URL = "https://koperasikitagodangulu.web.app/rk.html"

    /**
     * Generate secure link untuk rekening koran
     *
     * @param adminUid UID admin (dari Firebase Auth)
     * @param pelangganId ID pelanggan
     * @return URL lengkap untuk rekening koran
     */
    fun generateLink(adminUid: String, pelangganId: String): String {
        val timestamp = System.currentTimeMillis().toString()

        // Payload untuk signature
        val payload = "$adminUid:$pelangganId:$timestamp"

        // Generate HMAC-SHA256 signature
        val signature = generateHmacSignature(payload)

        // Gabungkan semua dan encode ke base64url
        val tokenData = "$payload:$signature"
        val token = Base64.encodeToString(
            tokenData.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        return "$BASE_URL?t=$token"
    }

    /**
     * Generate HMAC-SHA256 signature (16 karakter pertama)
     */
    private fun generateHmacSignature(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(SECRET_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKeySpec)

        val hmacBytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val hexString = hmacBytes.joinToString("") { "%02x".format(it) }

        // Ambil 16 karakter pertama saja
        return hexString.take(16)
    }

    /**
     * Copy link ke clipboard
     */
    fun copyToClipboard(context: Context, adminUid: String, pelangganId: String, namaDisplay: String) {
        val url = generateLink(adminUid, pelangganId)

        val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
        val clip = ClipData.newPlainText("Rekening Koran Link", url)
        clipboard?.setPrimaryClip(clip)

        Toast.makeText(context, "Link rekening koran $namaDisplay disalin!", Toast.LENGTH_SHORT).show()
    }

    /**
     * Share link via WhatsApp
     */
    fun shareViaWhatsApp(
        context: Context,
        phoneNumber: String,
        adminUid: String,
        pelangganId: String,
        namaNasabah: String
    ) {
        val url = generateLink(adminUid, pelangganId)

        // Normalize phone number
        var normalizedPhone = phoneNumber.replace(Regex("[^0-9]"), "")
        if (normalizedPhone.startsWith("0")) {
            normalizedPhone = "62" + normalizedPhone.substring(1)
        }
        if (!normalizedPhone.startsWith("62")) {
            normalizedPhone = "62$normalizedPhone"
        }

        val message = """
Halo $namaNasabah,

Berikut link Rekening Koran pinjaman Anda:
$url

Anda dapat melihat:
✅ Riwayat pembayaran
✅ Sisa hutang  
✅ Jadwal cicilan

Terima kasih,
Sirait Kredit dan Gadai
        """.trimIndent()

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(
                    "https://api.whatsapp.com/send?phone=$normalizedPhone&text=${Uri.encode(message)}"
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback ke share biasa
            shareLink(context, adminUid, pelangganId, namaNasabah)
        }
    }

    /**
     * Share link via system share dialog
     */
    fun shareLink(
        context: Context,
        adminUid: String,
        pelangganId: String,
        namaNasabah: String
    ) {
        val url = generateLink(adminUid, pelangganId)

        val message = """
Rekening Koran - $namaNasabah

$url

Sirait Kredit dan Gadai
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Rekening Koran - $namaNasabah")
            putExtra(Intent.EXTRA_TEXT, message)
        }

        context.startActivity(Intent.createChooser(intent, "Bagikan via"))
    }
}

fun Pelanggan.getRekeningKoranLink(): String {
    return RekeningKoranLinkHelper.generateLink(
        adminUid = this.adminUid,
        pelangganId = this.id
    )
}

/**
 * Copy rekening koran link ke clipboard
 */
fun Pelanggan.copyRekeningKoranLink(context: Context) {
    RekeningKoranLinkHelper.copyToClipboard(
        context = context,
        adminUid = this.adminUid,
        pelangganId = this.id,
        namaDisplay = this.namaPanggilan.ifBlank { this.namaKtp }
    )
}

/**
 * Share rekening koran via WhatsApp
 */
fun Pelanggan.shareRekeningKoranWhatsApp(context: Context) {
    RekeningKoranLinkHelper.shareViaWhatsApp(
        context = context,
        phoneNumber = this.noHp,
        adminUid = this.adminUid,
        pelangganId = this.id,
        namaNasabah = this.namaPanggilan.ifBlank { this.namaKtp }
    )
}