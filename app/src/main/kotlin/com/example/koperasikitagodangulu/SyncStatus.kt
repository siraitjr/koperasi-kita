package com.example.koperasikitagodangulu.offline

/**
 * Status sync untuk UI
 */
enum class SyncStatus {
    IDLE,      // Tidak ada aktivitas
    SYNCING,   // Sedang sync
    SUCCESS,   // Berhasil
    PARTIAL,   // Sebagian berhasil
    ERROR      // Error
}