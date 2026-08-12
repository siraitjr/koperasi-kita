package com.example.koperasikitagodangulu.offline

import android.util.Log
import com.google.gson.Gson

/**
 * =========================================================================
 * PEMUTAR ANTREAN → SUPABASE (Milestone 3)
 * =========================================================================
 * Menerima `PendingOperation` PERSIS seperti yang sudah tersimpan di Room —
 * `operationType` + `firebasePath` + `dataJson` — lalu menerjemahkannya ke
 * operasi PostgREST/RPC.
 *
 * Kontrak antrean TIDAK BERUBAH sedikit pun (batasan Anda: Room, WorkManager,
 * dan SyncWorker tetap). Itu disengaja dan penting: perangkat admin lapangan
 * bisa sudah menyimpan operasi berhari-hari sebelum cutover, dan operasi itu
 * harus tetap bisa diputar setelah tujuan sinkronisasi berpindah.
 *
 * PERBEDAAN MENDASAR DARI JALUR RTDB
 * ----------------------------------
 * Jalur Firebase butuh transaksi + `_guardPinjamanKe` untuk mencegah operasi
 * basi mendarat di generasi pinjaman yang salah — karena satu node dipakai
 * bersama semua generasi. Di Postgres tiap generasi adalah BARIS TERSENDIRI
 * dengan id deterministik (001 §3), jadi operasi basi menyasar baris lamanya
 * sendiri dan tidak bisa menyentuh generasi berjalan. Guard tetap dibaca —
 * bukan untuk memblokir, melainkan untuk MEMILIH baris sasaran.
 * =========================================================================
 */
class SupabaseSyncHandler(private val ds: SupabaseDataSource = SupabaseDataSource.getInstance()) {

    companion object {
        private const val TAG = "SupabaseSync"
        private val gson = Gson()
    }

    /** Hasil pemutaran satu operasi, dipetakan SyncManager ke status Room. */
    sealed class Hasil {
        /** Sukses, atau sudah pernah sukses (idempoten). */
        object Sukses : Hasil()
        /** Sengaja dilewati karena server sudah lebih maju. Tetap SUCCESS. */
        data class Dilewati(val alasan: String) : Hasil()
        /** Ditolak permanen — jangan di-retry, tandai REJECTED. */
        data class Ditolak(val pesan: String) : Hasil()
        /** Gagal sementara — biarkan retry berjalan seperti biasa. */
        data class GagalSementara(val pesan: String) : Hasil()
    }

    @Suppress("UNCHECKED_CAST")
    private fun payload(op: PendingOperation): Map<String, Any?> =
        try {
            gson.fromJson(op.dataJson, Map::class.java) as? Map<String, Any?> ?: emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ dataJson tidak terbaca (${op.operationType}): ${e.message}")
            emptyMap()
        }

    private fun guardPinjamanKe(p: Map<String, Any?>): Int? =
        (p["_guardPinjamanKe"] as? Number)?.toInt()

    /**
     * Kunci guard internal TIDAK PERNAH boleh ikut ke server — persis aturan
     * yang sama dengan jalur RTDB (SyncManager.stripAddPelangganGuards).
     */
    private fun bersihkan(p: Map<String, Any?>): Map<String, Any?> =
        p.filterKeys { it !in SupabaseMappers.DIABAIKAN_GUARD }

    /**
     * Generasi sasaran operasi. Urutan sumber dipilih dari yang paling
     * dipercaya ke yang paling lemah:
     *   1. `_guardPinjamanKe` — generasi PADA SAAT operasi dibuat. Inilah
     *      niat sebenarnya dari operasi itu.
     *   2. `pinjamanKe` di payload.
     *   3. Bertanya ke server (op warisan APK lama tanpa stempel apa pun).
     * Mengembalikan null bila ketiganya gagal → operasi ditolak, bukan
     * ditebak ke generasi 1 yang bisa saja salah nasabah.
     */
    private suspend fun generasi(op: PendingOperation, p: Map<String, Any?>, adminUid: String, pid: String): Int? =
        guardPinjamanKe(p)
            ?: (p["pinjamanKe"] as? Number)?.toInt()
            ?: ds.pinjamanKeTerkini(adminUid, pid)

    suspend fun putar(op: PendingOperation): Hasil {
        val p = payload(op)

        return when (op.operationType) {

            // ---------------------------------------------------------- ADD
            "ADD_PELANGGAN" -> {
                val ref = SyncTargets.parsePelanggan(op.firebasePath)
                    ?: return Hasil.Ditolak("path bukan pelanggan/: ${op.firebasePath}")
                ds.upsertPelanggan(ref.adminUid, ref.pelangganId, bersihkan(p)).ke()
            }

            "UPDATE_PELANGGAN" -> {
                val ref = SyncTargets.parsePelanggan(op.firebasePath)
                    ?: return Hasil.Ditolak("path bukan pelanggan/: ${op.firebasePath}")
                val ke = generasi(op, p, ref.adminUid, ref.pelangganId)
                    ?: return Hasil.Ditolak("generasi pinjaman tidak dapat ditentukan")
                ds.updatePelanggan(ref.adminUid, ref.pelangganId, ke, bersihkan(p)).ke()
            }

            "ADD_PEMBAYARAN", "ADD_SUB_PEMBAYARAN" -> {
                val ref = SyncTargets.parsePelanggan(op.firebasePath)
                    ?: return Hasil.Ditolak("path bukan pelanggan/: ${op.firebasePath}")
                val ke = generasi(op, p, ref.adminUid, ref.pelangganId)
                    ?: return Hasil.Ditolak("generasi pinjaman tidak dapat ditentukan")
                /* clientOpId adalah kunci idempotensi (UNIQUE di 001 §4).
                 * Tanpa itu, replay bisa menggandakan UANG — jadi lebih baik
                 * ditolak jelas daripada diterima diam-diam. */
                if ((p["clientOpId"]?.toString()).isNullOrBlank()) {
                    return Hasil.Ditolak("clientOpId kosong — replay bisa menggandakan pembayaran")
                }
                ds.insertPembayaran(
                    ref.adminUid, ref.pelangganId, ke, bersihkan(p),
                    isSub = op.operationType == "ADD_SUB_PEMBAYARAN"
                ).ke()
            }

            // ------------------------------------------------------- REMOVE
            "REMOVE_STATUS_KHUSUS", "REMOVE_PELANGGAN_STATUS_KHUSUS" -> {
                val ref = SyncTargets.parseStatusKhusus(op.firebasePath)
                    ?: return Hasil.Ditolak("path bukan pelanggan_status_khusus/: ${op.firebasePath}")
                ds.hapusStatusKhusus(ref.cabangId, ref.pelangganId).ke()
            }

            "REMOVE_PELANGGAN" -> {
                // Padanan removeValue() = SOFT DELETE lewat RPC (007).
                // Baris tidak dihapus: pembayaran & jurnal menunjuk ke sana.
                val ref = SyncTargets.parsePelanggan(op.firebasePath)
                    ?: return Hasil.Ditolak("path bukan pelanggan/: ${op.firebasePath}")
                ds.arsipkanNasabah(ref.adminUid, ref.pelangganId).ke()
            }

            // -------------------------------------------------------- JURNAL
            "ADD_JURNAL_TRANSAKSI" -> {
                val baris = SupabaseMappers.barisJurnalDariRtdb(op.firebasePath, p)
                    ?: return Hasil.Ditolak("jurnal tidak dapat dipetakan: ${op.firebasePath}")
                ds.catatJurnal(baris).ke()
            }

            // ------------------------------------------------- ARSIP GENERASI
            "ADD_RIWAYAT_PINJAMAN" -> {
                val ref = SyncTargets.parseRiwayatPinjaman(op.firebasePath)
                    ?: return Hasil.Ditolak("path bukan riwayat_pinjaman/: ${op.firebasePath}")
                ds.upsertGenerasiPinjaman(
                    ref.adminUid, ref.pelangganId, ref.pinjamanKe, bersihkan(p)
                ).ke()
            }

            // -------------------------------------------------- SERAH TERIMA
            "SERAH_TERIMA" -> {
                /* BELUM DIPINDAHKAN — sengaja.
                 * Operasi ini bukan sekadar tulis data: ia meng-upload foto ke
                 * Storage LALU menyebar notifikasi FCM ke Pimpinan/Pengawas/
                 * Koordinator lewat node RTDB (SyncManager.handleSerahTerimaSync).
                 * Sisi notifikasinya masih sepenuhnya Firebase dan belum punya
                 * padanan Supabase, jadi memindahkan setengahnya akan membuat
                 * foto masuk ke Supabase sementara atasan tidak pernah
                 * diberitahu — lebih buruk daripada tidak dipindahkan.
                 * Ditangani di milestone berikutnya bersama FCM/Realtime. */
                Hasil.Ditolak(
                    "SERAH_TERIMA belum didukung jalur Supabase (notifikasi masih Firebase). " +
                        "Kosongkan antrean SERAH_TERIMA sebelum memindahkan sakelar."
                )
            }

            else -> Hasil.Ditolak("operationType tidak dikenal: ${op.operationType}")
        }
    }

    /** Terjemahan hasil data source → hasil pemutaran antrean. */
    private fun SupabaseDataSource.Hasil.ke(): Hasil = when (this) {
        is SupabaseDataSource.Hasil.Sukses -> Hasil.Sukses
        is SupabaseDataSource.Hasil.Ditolak -> Hasil.Ditolak(pesan)
        is SupabaseDataSource.Hasil.GagalSementara -> Hasil.GagalSementara(pesan)
    }
}
