package com.example.koperasikitagodangulu.offline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * =========================================================================
 * SYNC STATUS INDICATOR - Composable UI Components
 * =========================================================================
 * Tampilkan status sync kepada user agar mereka tahu data sudah tersimpan
 * =========================================================================
 */

// -------------------------------------------------------------------------
// COMPACT SYNC BADGE (untuk AppBar atau FloatingActionButton)
// -------------------------------------------------------------------------

/**
 * Badge kecil yang menampilkan jumlah pending sync
 * Tampilkan di pojok AppBar atau sebagai FAB
 */
@Composable
fun SyncBadge(
    pendingCount: Int,
    syncStatus: SyncStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Hitungan FAILED dipisah agar badge bisa menampilkan tanda merah berbeda
    // saat ada entri yang habis budget retry. Default 0 untuk back-compat.
    failedCount: Int = 0
) {
    // Jika ada FAILED, badge MERAH (prioritas tampilan) — admin harus diberi tahu
    // bahwa ada data yang gagal sync, bahkan saat sedang syncing entri lain.
    val backgroundColor by animateColorAsState(
        targetValue = when {
            failedCount > 0 -> Color(0xFFF44336) // Merah: ada entri FAILED
            syncStatus == SyncStatus.SYNCING -> Color(0xFF2196F3)
            syncStatus == SyncStatus.ERROR -> Color(0xFFF44336)
            syncStatus == SyncStatus.PARTIAL -> Color(0xFFFF9800)
            syncStatus == SyncStatus.SUCCESS -> Color(0xFF4CAF50)
            pendingCount > 0 -> Color(0xFFFF9800) // Oranye: PENDING normal
            else -> Color(0xFF4CAF50) // Hijau: semua sync
        },
        label = "badgeColor"
    )

    // Rotation animation untuk syncing
    val infiniteTransition = rememberInfiniteTransition(label = "syncRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        when (syncStatus) {
            SyncStatus.SYNCING -> {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Syncing",
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(rotation)
                )
            }
            else -> {
                // Total badge = pending + failed; ikon pakai CloudOff bila ada FAILED
                // (mengindikasikan butuh aksi), CloudUpload untuk PENDING normal,
                // CloudDone bila tidak ada apa-apa.
                val totalBadge = pendingCount + failedCount
                if (totalBadge > 0) {
                    BadgedBox(
                        badge = {
                            Badge(containerColor = if (failedCount > 0) Color(0xFFB71C1C) else Color.Red) {
                                Text(
                                    text = if (totalBadge > 99) "99+" else totalBadge.toString(),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (failedCount > 0) Icons.Default.CloudOff else Icons.Default.CloudUpload,
                            contentDescription = if (failedCount > 0) "Sync gagal" else "Pending sync",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = "Synced",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// SYNC STATUS BAR (untuk ditampilkan di atas layar)
// -------------------------------------------------------------------------

/**
 * Bar yang menampilkan status sync detail
 * Tampilkan di atas layar saat ada pending operations
 */
@Composable
fun SyncStatusBar(
    pendingCount: Int,
    syncStatus: SyncStatus,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier,
    // FAILED dipisah dari pending agar admin lapangan tahu ada entri yang habis
    // budget retry & butuh aksi manual "Coba Lagi". Default 0 untuk back-compat.
    failedCount: Int = 0,
    onRetryFailedClick: (() -> Unit)? = null
) {
    // Tampilkan jika ada pending, failed, atau sedang sync.
    if (pendingCount == 0 && failedCount == 0 && syncStatus == SyncStatus.IDLE) return

    // FAILED override warna jadi merah meski status enum-nya IDLE — visibilitas
    // tinggi untuk mendorong admin lapangan menekan "Coba Lagi".
    val backgroundColor by animateColorAsState(
        targetValue = when {
            failedCount > 0 -> Color(0xFFFFEBEE)
            syncStatus == SyncStatus.SYNCING -> Color(0xFFE3F2FD)
            syncStatus == SyncStatus.SUCCESS -> Color(0xFFE8F5E9)
            syncStatus == SyncStatus.ERROR -> Color(0xFFFFEBEE)
            else -> Color(0xFFFFF3E0) // IDLE/PARTIAL pending
        },
        label = "barColor"
    )

    val textColor by animateColorAsState(
        targetValue = when {
            failedCount > 0 -> Color(0xFFC62828)
            syncStatus == SyncStatus.SYNCING -> Color(0xFF1565C0)
            syncStatus == SyncStatus.SUCCESS -> Color(0xFF2E7D32)
            syncStatus == SyncStatus.ERROR -> Color(0xFFC62828)
            else -> Color(0xFFE65100)
        },
        label = "textColor"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            val infiniteTransition = rememberInfiniteTransition(label = "syncBarRotation")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotation"
            )

            Icon(
                imageVector = when {
                    syncStatus == SyncStatus.SYNCING -> Icons.Default.Sync
                    failedCount > 0 -> Icons.Default.CloudOff
                    syncStatus == SyncStatus.SUCCESS -> Icons.Default.CloudDone
                    syncStatus == SyncStatus.ERROR -> Icons.Default.CloudOff
                    else -> Icons.Default.CloudUpload
                },
                contentDescription = null,
                tint = textColor,
                modifier = Modifier
                    .size(20.dp)
                    .then(
                        if (syncStatus == SyncStatus.SYNCING)
                            Modifier.rotate(rotation)
                        else
                            Modifier
                    )
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Text — FAILED mendapat prioritas pesan; PENDING ditampilkan sebagai
            // sub-baris. Ini agar admin lapangan tahu mana yang butuh aksi manual
            // (Coba Lagi) vs mana yang akan auto-sync.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        failedCount > 0 -> "❌ $failedCount data GAGAL sync — tekan Coba Lagi"
                        syncStatus == SyncStatus.SYNCING -> "🔄 Menyinkronkan data..."
                        syncStatus == SyncStatus.SUCCESS -> "✅ Semua data tersinkronisasi"
                        syncStatus == SyncStatus.PARTIAL -> "⚠️ Sebagian data gagal sync"
                        syncStatus == SyncStatus.ERROR -> "❌ Sinkronisasi gagal"
                        else -> "📤 $pendingCount data menunggu sinkronisasi"
                    },
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                if (failedCount > 0 && pendingCount > 0) {
                    Text(
                        text = "($pendingCount lagi menunggu sinkronisasi otomatis)",
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                } else if (pendingCount > 0 && syncStatus != SyncStatus.SYNCING && failedCount == 0) {
                    Text(
                        text = "Data akan otomatis tersinkronisasi saat online",
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }

            // Tombol: Coba Lagi (untuk FAILED) lebih prioritas dari Sync biasa.
            if (syncStatus != SyncStatus.SYNCING && failedCount > 0 && onRetryFailedClick != null) {
                TextButton(onClick = onRetryFailedClick) {
                    Text(
                        text = "Coba Lagi",
                        color = textColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (syncStatus != SyncStatus.SYNCING && pendingCount > 0) {
                TextButton(onClick = onSyncClick) {
                    Text(
                        text = "Sync",
                        color = textColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// SYNC INFO CARD (untuk Settings atau Debug screen)
// -------------------------------------------------------------------------

/**
 * Card detail yang menampilkan info lengkap tentang sync
 */
@Composable
fun SyncInfoCard(
    pendingCount: Int,
    syncStatus: SyncStatus,
    onSyncClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
    // FAILED count + list dipisah agar admin lapangan bisa lihat ITEM apa yang
    // gagal sync + pesan error-nya, lalu tekan "Coba Lagi" untuk reset budget
    // retry. Default kosong → back-compat untuk caller lama.
    failedCount: Int = 0,
    failedOperations: List<PendingOperation> = emptyList()
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Status Sinkronisasi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tiga kolom: Pending (oranye), Gagal (merah/tebal bila >0), Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Menunggu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = pendingCount.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (pendingCount > 0) Color(0xFFE65100) else MaterialTheme.colorScheme.primary
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Gagal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = failedCount.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (failedCount > 0) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when (syncStatus) {
                            SyncStatus.IDLE -> "Idle"
                            SyncStatus.SYNCING -> "Syncing..."
                            SyncStatus.SUCCESS -> "Synced ✓"
                            SyncStatus.PARTIAL -> "Partial"
                            SyncStatus.ERROR -> "Error"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = when (syncStatus) {
                            SyncStatus.SUCCESS -> Color(0xFF4CAF50)
                            SyncStatus.ERROR -> Color(0xFFF44336)
                            SyncStatus.SYNCING -> Color(0xFF2196F3)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Daftar entry FAILED dengan errorMessage — hanya muncul bila ada.
            // Memberi admin lapangan visibilitas EKSAK ke item apa yang gagal
            // (yang sebelumnya tersembunyi di Logcat saja).
            if (failedOperations.isNotEmpty()) {
                Text(
                    text = "Data yang gagal sync (perlu Coba Lagi):",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFC62828)
                )
                Spacer(modifier = Modifier.height(8.dp))
                val dateFmt = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale("in", "ID")) }
                // maxHeight: maksimum ~240dp; LazyColumn scroll bila banyak.
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp)
                ) {
                    items(failedOperations) { op ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = Color(0xFFFFEBEE),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = op.operationType,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFC62828)
                                )
                                Text(
                                    text = op.firebasePath,
                                    fontSize = 10.sp,
                                    color = Color(0xFF666666)
                                )
                                Text(
                                    text = "Dibuat: ${dateFmt.format(Date(op.createdAt))}  •  ${op.retryCount}× retry",
                                    fontSize = 10.sp,
                                    color = Color(0xFF666666)
                                )
                                if (!op.errorMessage.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "⚠ ${op.errorMessage}",
                                        fontSize = 11.sp,
                                        color = Color(0xFFC62828),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Action buttons — "Coba Lagi" jadi tombol utama bila ada FAILED.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (failedCount > 0) {
                    Button(
                        onClick = onRetryClick,
                        modifier = Modifier.weight(1f),
                        enabled = syncStatus != SyncStatus.SYNCING,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Coba Lagi ($failedCount)")
                    }
                } else {
                    OutlinedButton(
                        onClick = onRetryClick,
                        modifier = Modifier.weight(1f),
                        enabled = syncStatus != SyncStatus.SYNCING
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Coba Lagi")
                    }
                }

                Button(
                    onClick = onSyncClick,
                    modifier = Modifier.weight(1f),
                    enabled = syncStatus != SyncStatus.SYNCING && pendingCount > 0
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sync Now")
                }
            }

            // Info text
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "💡 Data tersimpan lokal dan akan otomatis tersinkronisasi " +
                        "ke server saat koneksi internet tersedia, bahkan jika aplikasi tidak dibuka.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// -------------------------------------------------------------------------
// HELPER COMPOSABLES
// -------------------------------------------------------------------------

/**
 * Wrapper untuk observe LiveData dengan default value
 */
@Composable
fun <T> LiveData<T>.observeAsStateWithDefault(default: T): State<T> {
    return this.observeAsState(initial = default)
}