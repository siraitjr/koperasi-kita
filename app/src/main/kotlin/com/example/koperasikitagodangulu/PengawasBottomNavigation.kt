package com.example.koperasikitagodangulu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

/**
 * =========================================================================
 * PENGAWAS BOTTOM NAVIGATION - UPDATED VERSION
 * =========================================================================
 *
 * Bottom Navigation untuk aplikasi Pengawas dengan 3 menu:
 * - Dashboard: Ringkasan data semua cabang
 * - Approval: Persetujuan pinjaman >= 3jt (BARU!)
 * - Laporan: Laporan detail
 *
 * UPDATE: Menambahkan tab Approval untuk dual approval system
 */
@Composable
fun PengawasBottomNavigation(
    navController: NavHostController,
    currentRoute: String? = null,
    viewModel: PelangganViewModel? = null,  // Optional, untuk badge count
    isDark: Boolean = false  // ✅ DARK MODE SUPPORT
) {
    // Get pending approvals count untuk badge
    val pendingCount = viewModel?.pendingApprovalsPengawas?.collectAsState()?.value?.size ?: 0

    val items = listOf(
        PengawasNavItem(
            route = "pengawas_dashboard",
            icon = Icons.Rounded.Dashboard,
            label = "Dashboard",
            badgeCount = 0
        ),
        PengawasNavItem(
            route = "pengawas_approvals",
            icon = Icons.Rounded.Assignment,
            label = "Approval",
            badgeCount = pendingCount  // Tampilkan badge jika ada pending
        ),
        PengawasNavItem(
            route = "pengawas_reports",
            icon = Icons.Rounded.Assessment,
            label = "Laporan",
            badgeCount = 0
        ),
        PengawasNavItem(
            route = "pengawas_user_management",
            icon = Icons.Rounded.ManageAccounts,
            label = "Kelola User",
            badgeCount = 0
        )
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ambientColor = Color.Black.copy(alpha = 0.1f)
            ),
        color = PengawasColors.getCard(isDark),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = currentRoute == item.route

                val backgroundColor by animateColorAsState(
                    targetValue = if (isSelected) PengawasColors.primary.copy(alpha = 0.1f) else Color.Transparent,
                    animationSpec = tween(300),
                    label = "bgColor"
                )

                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) PengawasColors.primary else PengawasColors.getTextMuted(isDark),
                    animationSpec = tween(300),
                    label = "contentColor"
                )

                Surface(
                    onClick = {
                        if (currentRoute != item.route) {
                            try {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PengawasNav", "Navigation error: ${e.message}")
                            }
                        }
                    },
                    color = backgroundColor,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Icon with badge
                        Box(
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = contentColor,
                                modifier = Modifier.size(24.dp)
                            )

                            // Badge untuk pending count
                            if (item.badgeCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = 4.dp, y = (-4).dp)
                                        .size(16.dp)
                                        .background(PengawasColors.danger, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (item.badgeCount > 9) "9+" else item.badgeCount.toString(),
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Text(
                            text = item.label,
                            color = contentColor,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

/**
 * Overload tanpa viewModel (backward compatible)
 */
@Composable
fun PengawasBottomNavigation(
    navController: NavHostController,
    currentRoute: String?
) {
    PengawasBottomNavigation(
        navController = navController,
        currentRoute = currentRoute,
        viewModel = null
    )
}

data class PengawasNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
    val badgeCount: Int = 0
)