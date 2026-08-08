package com.example.koperasikitagodangulu.offline

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// =========================================================================
// ENTITY: Pending Operation
// =========================================================================
// Menyimpan semua operasi yang perlu di-sync ke Firebase
// =========================================================================

@Entity(tableName = "pending_operations")
data class PendingOperation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Tipe operasi: "ADD_PELANGGAN", "UPDATE_PELANGGAN", "ADD_PEMBAYARAN", "ADD_SUB_PEMBAYARAN",
    //               "SERAH_TERIMA" (upload foto serah terima offline + notifikasi atasan), dll.
    val operationType: String,

    // Path di Firebase (e.g., "pelanggan/{adminUid}/{pelangganId}")
    val firebasePath: String,

    // Data JSON yang akan dikirim
    val dataJson: String,

    // Admin UID pemilik data
    val adminUid: String,

    // Pelanggan ID (untuk referensi)
    val pelangganId: String? = null,

    // Timestamp saat operasi dibuat
    val createdAt: Long = System.currentTimeMillis(),

    // Jumlah retry yang sudah dilakukan
    val retryCount: Int = 0,

    // Status: "PENDING", "SYNCING", "FAILED", "SUCCESS"
    val status: String = "PENDING",

    // Error message jika gagal
    val errorMessage: String? = null
)

// =========================================================================
// DAO: Data Access Object
// =========================================================================

@Dao
interface PendingOperationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: PendingOperation): Long

    @Update
    suspend fun update(operation: PendingOperation)

    @Delete
    suspend fun delete(operation: PendingOperation)

    @Query("DELETE FROM pending_operations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM pending_operations WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY createdAt ASC")
    suspend fun getPendingOperations(): List<PendingOperation>

    @Query("SELECT * FROM pending_operations WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingOperationsLimited(limit: Int): List<PendingOperation>

    @Query("SELECT * FROM pending_operations WHERE adminUid = :adminUid ORDER BY createdAt ASC")
    suspend fun getOperationsByAdmin(adminUid: String): List<PendingOperation>

    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING' OR status = 'FAILED' OR status = 'SYNCING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING' OR status = 'FAILED' OR status = 'SYNCING'")
    fun getPendingCountFlow(): Flow<Int>

    // Hitungan terpisah untuk pemisahan UI: yang masih dalam budget retry
    // (PENDING/SYNCING) vs yang sudah habis budget retry (FAILED). Dipakai
    // SyncStatusUI agar admin lapangan bisa membedakan "menunggu sinkronisasi"
    // dan "gagal dan butuh perhatian".
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING' OR status = 'SYNCING'")
    suspend fun getPendingOnlyCount(): Int

    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING' OR status = 'SYNCING'")
    fun getPendingOnlyCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'FAILED'")
    suspend fun getFailedCount(): Int

    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'FAILED'")
    fun getFailedCountFlow(): Flow<Int>

    @Query("SELECT * FROM pending_operations WHERE status = 'FAILED' ORDER BY createdAt ASC")
    suspend fun getFailedOperations(): List<PendingOperation>

    // =====================================================================
    // ✅ REJECTED = status TERMINAL (25 Jul 2026). Op yang DITOLAK server
    // secara permanen (Permission denied / .validate) — retry tidak akan
    // pernah berhasil. Dipisah dari FAILED (yang transient: jaringan, timeout)
    // supaya "Coba Lagi" tidak looping buta & admin dapat pesan yang jelas.
    // TIDAK ada migrasi Room: kolom `status` sudah String bebas.
    // resetFailedToRetry() hanya menyentuh 'FAILED' → REJECTED aman.
    // =====================================================================
    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'REJECTED'")
    suspend fun getRejectedCount(): Int

    @Query("SELECT COUNT(*) FROM pending_operations WHERE status = 'REJECTED'")
    fun getRejectedCountFlow(): Flow<Int>

    @Query("SELECT * FROM pending_operations WHERE status = 'REJECTED' ORDER BY createdAt ASC")
    suspend fun getRejectedOperations(): List<PendingOperation>

    // Buang permanen op yang ditolak server (aksi sadar admin/pimpinan).
    @Query("DELETE FROM pending_operations WHERE status = 'REJECTED'")
    suspend fun discardRejected(): Int

    // Reset semua entry FAILED ke PENDING + retryCount=0 + errorMessage=null,
    // memberi budget retry segar saat user menekan "Coba Lagi". Berbeda dari
    // updateStatus() yang masih meng-increment retryCount via SQL CASE-WHEN.
    @Query("UPDATE pending_operations SET status = 'PENDING', retryCount = 0, errorMessage = NULL WHERE status = 'FAILED'")
    suspend fun resetFailedToRetry(): Int

    // retryCount hanya di-increment untuk status re-schedule (PENDING) atau gagal permanen (FAILED).
    // Transisi SYNCING / SUCCESS tidak boleh mengonsumsi retry budget.
    @Query("UPDATE pending_operations SET status = :status, errorMessage = :errorMessage, retryCount = retryCount + CASE WHEN :status IN ('PENDING','FAILED') THEN 1 ELSE 0 END WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, errorMessage: String? = null)

    // Recovery: row yang stuck di SYNCING karena proses sebelumnya crash/dibunuh OS
    // tidak terlihat oleh getPendingOperations() (hanya ambil PENDING/FAILED).
    // Reset ke PENDING agar bisa di-retry di siklus berikutnya.
    @Query("UPDATE pending_operations SET status = 'PENDING' WHERE status = 'SYNCING'")
    suspend fun resetStuckSyncing(): Int

    @Query("DELETE FROM pending_operations WHERE status = 'SUCCESS'")
    suspend fun deleteSuccessful()

    @Query("DELETE FROM pending_operations WHERE status = 'SUCCESS' AND createdAt < :beforeTimestamp")
    suspend fun deleteOldSuccessful(beforeTimestamp: Long)

    // Untuk debugging
    @Query("SELECT * FROM pending_operations ORDER BY createdAt DESC")
    suspend fun getAllOperations(): List<PendingOperation>
}

// =========================================================================
// DATABASE
// =========================================================================

@Database(
    entities = [PendingOperation::class],
    version = 1,
    exportSchema = false
)
abstract class PendingOperationDatabase : RoomDatabase() {

    abstract fun pendingOperationDao(): PendingOperationDao

    companion object {
        @Volatile
        private var INSTANCE: PendingOperationDatabase? = null

        fun getInstance(context: Context): PendingOperationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PendingOperationDatabase::class.java,
                    "pending_operations_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}