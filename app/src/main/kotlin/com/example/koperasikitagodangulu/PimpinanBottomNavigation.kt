package com.example.koperasikitagodangulu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Approval
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.accompanist.systemuicontroller.rememberSystemUiController


// =========================================================================
// Modern Color Palette for Pimpinan Screens
// =========================================================================
object PimpinanColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))
    val purpleGradient = listOf(Color(0xFF8B5CF6), Color(0xFFA78BFA))
    val orangeGradient = listOf(Color(0xFFF97316), Color(0xFFFB923C))
    val grayGradient = listOf(Color(0xFF64748B), Color(0xFF94A3B8))

    val darkBackground = Color(0xFF0F172A)
    val darkSurface = Color(0xFF1E293B)
    val darkCard = Color(0xFF334155)
    val darkBorder = Color(0xFF475569)
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFE2E8F0)

    val primary = Color(0xFF6366F1)
    val success = Color(0xFF10B981)
    val warning = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)
    val info = Color(0xFF3B82F6)
    val teal = Color(0xFF14B8A6)
    val purple = Color(0xFF8B5CF6)

    val textPrimary = Color(0xFF1E293B)
    val textSecondary = Color(0xFF64748B)
    val textMuted = Color(0xFF94A3B8)

    // =========================================================================
    // ✅ BARU: Dark Mode Helper Functions
    // Fungsi-fungsi ini mengembalikan warna yang sesuai berdasarkan mode tema
    // =========================================================================
    fun getBackground(isDark: Boolean): Color =
        if (isDark) darkBackground else lightBackground

    fun getSurface(isDark: Boolean): Color =
        if (isDark) darkSurface else lightSurface

    fun getCard(isDark: Boolean): Color =
        if (isDark) darkCard else lightSurface

    fun getBorder(isDark: Boolean): Color =
        if (isDark) darkBorder else lightBorder

    fun getTextPrimary(isDark: Boolean): Color =
        if (isDark) Color(0xFFF1F5F9) else textPrimary

    fun getTextSecondary(isDark: Boolean): Color =
        if (isDark) Color(0xFF94A3B8) else textSecondary

    fun getTextMuted(isDark: Boolean): Color =
        if (isDark) Color(0xFF64748B) else textMuted
}

// =========================================================================
// ✅ BARU: Overload function dengan parameter viewModel untuk dark mode
// Function lama tetap ada untuk backward compatibility
// =========================================================================
@Composable
fun PimpinanBottomNavigation(
    navController: NavHostController,
    currentRoute: String? = null,
    viewModel: PelangganViewModel? = null
) {
    // ✅ BARU: Ambil state dark mode dari viewModel (default false jika null)
    val isDark = viewModel?.isDarkMode?.value ?: false
    // ✅ Set status bar & navigation bar sesuai tema dashboard
    val systemUiController = rememberSystemUiController()
    val bgColor = PimpinanColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val items = listOf(
        BottomNavItem(
            route = "pimpinan_dashboard",
            icon = Icons.Rounded.Home,
            label = "Dashboard"
        ),
        BottomNavItem(
            route = "pimpinan_approvals",
            icon = Icons.Rounded.Approval,
            label = "Approvals"
        ),
        BottomNavItem(
            route = "pimpinan_reports",
            icon = Icons.Rounded.BarChart,
            label = "Reports"
        ),
        BottomNavItem(
            route = "pimpinan_absensi",
            icon = Icons.Rounded.Fingerprint,
            label = "Absensi"
        ),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ambientColor = Color.Black.copy(alpha = 0.1f)
            ),
        color = PimpinanColors.getCard(isDark), // ✅ UBAH: Dinamis berdasarkan tema
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
                    targetValue = if (isSelected) PimpinanColors.primary.copy(alpha = 0.1f) else Color.Transparent,
                    animationSpec = tween(300),
                    label = "bgColor"
                )

                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) PimpinanColors.primary else PimpinanColors.getTextMuted(isDark), // ✅ UBAH: Dinamis
                    animationSpec = tween(300),
                    label = "contentColor"
                )

                Surface(
                    onClick = {
                        if (currentRoute != item.route) {
                            navController.navigate(item.route) {
                                launchSingleTop = true
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                            }
                        }
                    },
                    color = backgroundColor,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = contentColor,
                            modifier = Modifier.size(24.dp)
                        )
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

// =========================================================================
// ✅ TETAP ADA: Function lama untuk backward compatibility
// Tidak dihapus agar kode lain yang belum diupdate tetap berfungsi
// =========================================================================
@Composable
fun PimpinanBottomNavigation(
    navController: NavHostController,
    currentRoute: String?
) {
    PimpinanBottomNavigation(
        navController = navController,
        currentRoute = currentRoute,
        viewModel = null
    )
}

data class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String
)