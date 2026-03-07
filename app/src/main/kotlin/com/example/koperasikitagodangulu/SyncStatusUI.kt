package com.example.koperasikitagodangulu.offline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when (syncStatus) {
            SyncStatus.IDLE -> if (pendingCount > 0) Color(0xFFFF9800) else Color(0xFF4CAF50)
            SyncStatus.SYNCING -> Color(0xFF2196F3)
            SyncStatus.SUCCESS -> Color(0xFF4CAF50)
            SyncStatus.PARTIAL -> Color(0xFFFF9800)
            SyncStatus.ERROR -> Color(0xFFF44336)
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
                if (pendingCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = Color.Red
                            ) {
                                Text(
                                    text = if (pendingCount > 99) "99+" else pendingCount.toString(),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = "Pending sync",
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
    modifier: Modifier = Modifier
) {
    // Hanya tampilkan jika ada pending atau sedang syncing
    if (pendingCount == 0 && syncStatus == SyncStatus.IDLE) return

    val backgroundColor by animateColorAsState(
        targetValue = when (syncStatus) {
            SyncStatus.IDLE -> Color(0xFFFFF3E0) // Orange light
            SyncStatus.SYNCING -> Color(0xFFE3F2FD) // Blue light
            SyncStatus.SUCCESS -> Color(0xFFE8F5E9) // Green light
            SyncStatus.PARTIAL -> Color(0xFFFFF3E0) // Orange light
            SyncStatus.ERROR -> Color(0xFFFFEBEE) // Red light
        },
        label = "barColor"
    )

    val textColor by animateColorAsState(
        targetValue = when (syncStatus) {
            SyncStatus.IDLE -> Color(0xFFE65100)
            SyncStatus.SYNCING -> Color(0xFF1565C0)
            SyncStatus.SUCCESS -> Color(0xFF2E7D32)
            SyncStatus.PARTIAL -> Color(0xFFE65100)
            SyncStatus.ERROR -> Color(0xFFC62828)
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
                imageVector = when (syncStatus) {
                    SyncStatus.SYNCING -> Icons.Default.Sync
                    SyncStatus.SUCCESS -> Icons.Default.CloudDone
                    SyncStatus.ERROR -> Icons.Default.CloudOff
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

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (syncStatus) {
                        SyncStatus.IDLE -> "📤 $pendingCount data menunggu sinkronisasi"
                        SyncStatus.SYNCING -> "🔄 Menyinkronkan data..."
                        SyncStatus.SUCCESS -> "✅ Semua data tersinkronisasi"
                        SyncStatus.PARTIAL -> "⚠️ Sebagian data gagal sync"
                        SyncStatus.ERROR -> "❌ Sinkronisasi gagal"
                    },
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                if (pendingCount > 0 && syncStatus != SyncStatus.SYNCING) {
                    Text(
                        text = "Data akan otomatis tersinkronisasi saat online",
                        color = textColor.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }

            // Sync button
            if (syncStatus != SyncStatus.SYNCING && pendingCount > 0) {
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
    modifier: Modifier = Modifier
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

            // Status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Data Pending",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = pendingCount.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (pendingCount > 0)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
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

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    Text("Retry Failed")
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