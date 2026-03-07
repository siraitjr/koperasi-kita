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
 * KOORDINATOR BOTTOM NAVIGATION
 * =========================================================================
 *
 * Bottom Navigation untuk aplikasi Koordinator dengan 3 menu:
 * - Dashboard: Ringkasan data semua cabang
 * - Approval: Persetujuan pinjaman >= 3jt
 * - Laporan: Laporan detail
 *
 * NOTE: Koordinator TIDAK memiliki menu "Kelola User"
 */
@Composable
fun KoordinatorBottomNavigation(
    navController: NavHostController,
    currentRoute: String? = null,
    viewModel: PelangganViewModel? = null,
    isDark: Boolean = false  // ✅ DARK MODE SUPPORT
) {
    val pendingCount = viewModel?.pendingApprovalsPengawas?.collectAsState()?.value?.size ?: 0

    // ✅ HANYA 3 MENU (tanpa Kelola User)
    val items = listOf(
        KoordinatorNavItem(
            route = "koordinator_dashboard",
            icon = Icons.Rounded.Dashboard,
            label = "Dashboard",
            badgeCount = 0
        ),
        KoordinatorNavItem(
            route = "koordinator_approvals",
            icon = Icons.Rounded.Assignment,
            label = "Approval",
            badgeCount = pendingCount
        ),
        KoordinatorNavItem(
            route = "koordinator_reports",
            icon = Icons.Rounded.Assessment,
            label = "Laporan",
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
        color = KoordinatorColors.getCard(isDark),
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
                    targetValue = if (isSelected) KoordinatorColors.primary.copy(alpha = 0.1f) else Color.Transparent,
                    animationSpec = tween(300),
                    label = "bgColor"
                )

                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) KoordinatorColors.primary else KoordinatorColors.getTextMuted(isDark),
                    animationSpec = tween(300),
                    label = "contentColor"
                )

                Surface(
                    onClick = {
                        if (currentRoute != item.route) {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
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
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = contentColor,
                                modifier = Modifier.size(24.dp)
                            )

                            if (item.badgeCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = 4.dp, y = (-4).dp)
                                        .size(16.dp)
                                        .background(KoordinatorColors.danger, CircleShape),
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

@Composable
fun KoordinatorBottomNavigation(
    navController: NavHostController,
    currentRoute: String?
) {
    KoordinatorBottomNavigation(
        navController = navController,
        currentRoute = currentRoute,
        viewModel = null,
        isDark = false
    )
}

data class KoordinatorNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String,
    val badgeCount: Int = 0
)

/**
 * Warna khusus untuk Koordinator (Teal theme - berbeda dengan Pengawas yang ungu)
 */
object KoordinatorColors {
    val primaryGradient = listOf(
        Color(0xFF3B82F6), // Biru terang
        Color(0xFF1D4ED8)  // Biru gelap
    )
    val primary = Color(0xFF00897B)        // Teal 600
    val primaryDark = Color(0xFF00695C)    // Teal 800
    val primaryLight = Color(0xFF4DB6AC)   // Teal 300
    val gradientStart = Color(0xFF00897B)
    val gradientEnd = Color(0xFF26A69A)
    val success = Color(0xFF4CAF50)
    val warning = Color(0xFFFF9800)
    val danger = Color(0xFFE53935)
    val info = Color(0xFF2196F3)
    val textPrimary = Color(0xFF212121)
    val textSecondary = Color(0xFF757575)
    val textMuted = Color(0xFFBDBDBD)
    val surface = Color.White
    val surfaceVariant = Color(0xFFF5F5F5)
    val cardBackground = Color.White

    // =========================================================================
    // DARK MODE COLORS
    // =========================================================================
    val darkBackground = Color(0xFF0F172A)
    val darkSurface = Color(0xFF1E293B)
    val darkCard = Color(0xFF334155)
    val darkBorder = Color(0xFF475569)
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFE2E8F0)

    // =========================================================================
    // DARK MODE HELPER FUNCTIONS
    // =========================================================================
    fun getBackground(isDark: Boolean): Color = if (isDark) darkBackground else lightBackground
    fun getCard(isDark: Boolean): Color = if (isDark) darkCard else lightSurface
    fun getSurface(isDark: Boolean): Color = if (isDark) darkSurface else lightSurface
    fun getBorder(isDark: Boolean): Color = if (isDark) darkBorder else lightBorder
    fun getTextPrimary(isDark: Boolean): Color = if (isDark) Color.White else textPrimary
    fun getTextSecondary(isDark: Boolean): Color = if (isDark) Color(0xFFCBD5E1) else textSecondary
    fun getTextMuted(isDark: Boolean): Color = if (isDark) Color(0xFF94A3B8) else textMuted
}
