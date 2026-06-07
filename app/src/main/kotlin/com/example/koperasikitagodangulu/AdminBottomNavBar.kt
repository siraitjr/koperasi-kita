package com.example.koperasikitagodangulu

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =========================================================================
// ✅ GLOBAL BOTTOM NAVIGATION — pimpinan 07 Jun 2026
// -------------------------------------------------------------------------
// Material 3 NavigationBar Admin Lapangan, di-HOIST ke level AppNavigation
// supaya muncul di SELURUH tab utama (Beranda/Nasabah/Kalkulator/Laporan/Akun)
// tanpa duplikasi per-screen. Selected tab di-track dari currentBackStackEntry
// di AppNavigation (parameter currentRoute).
//
// Aturan tab:
//   - Beranda    → route "dashboard"          (Home, ikon Home)
//   - Nasabah    → route "daftarPelanggan"    (ikon Groups)
//   - Kalkulator → route "kalkulatorPinjaman" (ikon Calculate) [RENAME dari "Transaksi"]
//   - Laporan    → route "laporanHarian"      (ikon Assessment)
//   - Akun       → action onAkunClick (dialog profil di AppNavigation; tidak
//                  punya route → tidak pernah ter-highlight; ini disengaja
//                  karena Akun = trigger aksi, bukan navigasi layar)
//
// Visibility kondisional ditangani di AppNavigation (Scaffold bottomBar
// muncul HANYA bila currentRoute ∈ mainTabRoutes). Detail screen / dialog
// screen TIDAK menampilkan bottom bar.
// =========================================================================

private data class AdminNavTab(
    val key: String,
    val route: String?,
    val label: String,
    val icon: ImageVector
)

@Composable
fun AdminBottomNavBar(
    currentRoute: String?,
    isDark: Boolean,
    onTabSelected: (route: String) -> Unit,
    onAkunClick: () -> Unit
) {
    val tabs = remember {
        listOf(
            AdminNavTab("beranda",    "dashboard",         "Beranda",    Icons.Rounded.Home),
            AdminNavTab("nasabah",    "daftarPelanggan",   "Nasabah",    Icons.Rounded.Groups),
            AdminNavTab("kalkulator", "kalkulatorPinjaman","Kalkulator", Icons.Rounded.Calculate),
            AdminNavTab("laporan",    "laporanHarian",     "Laporan",    Icons.Rounded.Assessment),
            AdminNavTab("akun",       null,                "Akun",       Icons.Rounded.Person)
        )
    }

    val containerColor = if (isDark) AdminColors.darkCard else Color(0xFFFFFFFF)
    val selectedColor = if (isDark) Color(0xFFA5B4FC) else Color(0xFF6366F1)
    val unselectedColor = Color(0xFF94A3B8)

    NavigationBar(
        containerColor = containerColor,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.route != null && tab.route == currentRoute
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (tab.route != null) onTabSelected(tab.route) else onAkunClick()
                },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(22.dp)
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = selectedColor,
                    selectedTextColor = selectedColor,
                    indicatorColor = selectedColor.copy(alpha = 0.16f),  // pill indigo
                    unselectedIconColor = unselectedColor,
                    unselectedTextColor = unselectedColor
                ),
                alwaysShowLabel = true
            )
        }
    }
}
