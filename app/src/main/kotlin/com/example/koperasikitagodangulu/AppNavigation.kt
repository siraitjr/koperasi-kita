package com.example.koperasikitagodangulu

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.NavType
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.example.koperasikitagodangulu.ui.screens.SplashScreen

// =========================================================================
// ✅ GLOBAL SCAFFOLD HOST (pimpinan 07 Jun 2026)
// -------------------------------------------------------------------------
// Scaffold + bottom bar di-hoist ke level NavHost agar bottom navigation
// muncul di SELURUH tab utama Admin Lapangan tanpa duplikasi per-screen.
// Visibility kondisional: bottom bar TAMPIL hanya bila currentRoute ∈
// mainTabRoutes (detail screen, dialog screen, role lain → bottom bar hilang).
//
// Dialog profil (Lihat / Ubah foto) juga di-hoist ke sini supaya bisa
// dipicu oleh:
//   1. Tab "Akun" di bottom bar (dari layar manapun di main tabs).
//   2. Klik avatar di hero card AdminHomeScreen (via callback onAvatarClick).
// Logika dialog identik dengan versi sebelumnya — hanya pindah container.
// =========================================================================
// Aturan visibility bottom nav (pimpinan 07 Jun 2026, revisi):
//   - 4 main tab Admin Lapangan: dashboard, daftarPelanggan, kalkulatorPinjaman,
//     laporanHarian.
//   - 2 tracking screen yang sering dipakai Admin Lapangan dari Beranda:
//     "ringkasan" (RingkasanDashboardScreen) & "pelangganKutip"
//     (PelangganYangHarusDikunjungiScreen).
//   - Selain itu (form input, detail, registrasi, role lain) → bottom bar
//     SEMBUNYI agar tidak mengganggu keyboard/clutter layout.
private val mainTabRoutes = setOf(
    "dashboard",
    "daftarPelanggan",
    "kalkulatorPinjaman",
    "laporanHarian",
    "ringkasan",
    "pelangganKutip"
)

@Composable
fun AppNavigation(navController: NavHostController, viewModel: PelangganViewModel) {
    val isDark by viewModel.isDarkMode

    // Track current route untuk highlight tab aktif + conditional visibility.
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in mainTabRoutes

    // ── State dialog profil (hoisted dari AdminHomeScreen) ──────────────
    val context = LocalContext.current
    val adminPhotoUrl by viewModel.adminPhotoUrl.collectAsState()
    var showPhotoOptionsDialog by remember { mutableStateOf(false) }
    var showFullPhotoDialog by remember { mutableStateOf(false) }
    var isUploadingPhoto by remember { mutableStateOf(false) }

    // Pre-load foto profil sekali agar tap "Akun" sebelum user pernah ke
    // Beranda tetap punya data foto. Idempoten (ViewModel guard internal).
    LaunchedEffect(Unit) {
        try { viewModel.loadAdminPhotoUrl() } catch (_: Exception) {}
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isUploadingPhoto = true
            viewModel.uploadAdminPhoto(
                imageUri = it,
                onSuccess = { _ ->
                    isUploadingPhoto = false
                    Toast.makeText(context, "Foto profil berhasil diupload", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    isUploadingPhoto = false
                    Toast.makeText(context, "Gagal upload: $error", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    val onProfileAction: () -> Unit = {
        if (!adminPhotoUrl.isNullOrBlank()) showPhotoOptionsDialog = true
        else photoPickerLauncher.launch("image/*")
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                AdminBottomNavBar(
                    currentRoute = currentRoute,
                    isDark = isDark,
                    onTabSelected = { route ->
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                popUpTo("dashboard") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onAkunClick = onProfileAction
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "splash",
            modifier = Modifier.padding(innerPadding)
        ) {

        // ✅ Splash Screen
        composable("splash") {
            SplashScreen(
                onSplashFinished = {
                    navController.navigate("auth") {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            )
        }

        composable("auth") {
            AuthScreen(navController, viewModel)
        }

        composable("dashboard") {
            // onAvatarClick: avatar di hero card AdminHomeScreen → dialog profil
            // di-host di AppNavigation (di-share dengan tab Akun). isUploadingPhoto
            // diteruskan agar spinner avatar hero & dialog konsisten.
            AdminHomeScreen(
                navController = navController,
                viewModel = viewModel,
                onAvatarClick = onProfileAction,
                isUploadingPhoto = isUploadingPhoto
            )
        }

        // ✅ MODIFIKASI: TambahPelanggan dengan support prefill dari CariPelangganScreen
        composable(
            route = "tambahPelanggan?prefillNama={prefillNama}&prefillNik={prefillNik}&prefillAlamat={prefillAlamat}",
            arguments = listOf(
                navArgument("prefillNama") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
                navArgument("prefillNik") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
                navArgument("prefillAlamat") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                }
            )
        ) { backStackEntry ->
            val prefillNama = try {
                URLDecoder.decode(
                    backStackEntry.arguments?.getString("prefillNama") ?: "",
                    StandardCharsets.UTF_8.toString()
                )
            } catch (e: Exception) { "" }

            val prefillNik = backStackEntry.arguments?.getString("prefillNik") ?: ""

            val prefillAlamat = try {
                URLDecoder.decode(
                    backStackEntry.arguments?.getString("prefillAlamat") ?: "",
                    StandardCharsets.UTF_8.toString()
                )
            } catch (e: Exception) { "" }

            TambahPelangganScreen(
                navController = navController,
                viewModel = viewModel,
                prefillNama = prefillNama,
                prefillNik = prefillNik,
                prefillAlamat = prefillAlamat
            )
        }

        composable("daftarPelanggan") {
            DaftarPelangganScreen(navController, viewModel)
        }

        composable("inputPembayaran/{pelangganId}") { backStackEntry ->
            val pelangganId = backStackEntry.arguments?.getString("pelangganId") ?: ""
            InputPembayaranScreen(navController, viewModel, pelangganId)
        }

        composable("inputPembayaran") {
            InputPembayaranLangsungScreen(navController, viewModel)
        }

        composable("inputPembayaranLangsung/{pelangganId}") { backStackEntry ->
            val pelangganId = backStackEntry.arguments?.getString("pelangganId") ?: "0"
            InputPembayaranLangsungScreen(navController, viewModel, pelangganId)
        }

        composable("riwayat/{pelangganId}") { backStackEntry ->
            val pelangganId = backStackEntry.arguments?.getString("pelangganId") ?: return@composable
            RiwayatPembayaranScreen(navController, viewModel, pelangganId)
        }

        composable("pelangganKutip") {
            PelangganYangHarusDikunjungiScreen(navController = navController, viewModel = viewModel)
        }

        composable("daftarPelangganLunas") {
            DaftarPelangganLunasScreen(navController = navController, viewModel = viewModel)
        }

        composable("ringkasan") {
            RingkasanDashboardScreen(navController, viewModel)
        }

        composable("daftarPelangganMacet") {
            DaftarPelangganMacetScreen(navController, viewModel)
        }

        composable("kalkulatorPinjaman") {
            KalkulatorPinjamanScreen(navController, viewModel)
        }

        composable("laporanHarian") {
            LaporanHarianScreen(navController = navController, viewModel = viewModel)
        }

        // ✅ Route CariPelangganScreen (sudah ada, tetap dipertahankan)
        composable("cari_pelanggan") {
            CariPelangganScreen(
                navController = navController,
                viewModel = viewModel
            )
        }

        composable("pimpinan_dashboard") {
            PimpinanDashboardScreen(navController, viewModel)
        }

        composable(
            route = "pimpinan_approvals?tab={tab}",
            arguments = listOf(
                navArgument("tab") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            val initialTab = backStackEntry.arguments?.getInt("tab") ?: 0
            PimpinanApprovalScreen(navController, viewModel, initialTab = initialTab)
        }

        composable("pimpinan_reports") {
            PimpinanReportsScreen(navController, viewModel)
        }

        composable("notifikasi") {
            RiwayatPenolakanScreen(navController = navController, viewModel = viewModel)
        }

        composable("detailNotifikasi/{notificationId}") { backStackEntry ->
            val notificationId = backStackEntry.arguments?.getString("notificationId")
            DetailNotifikasiScreen(
                navController = navController,
                viewModel = viewModel,
                notificationId = notificationId
            )
        }

        composable("daftarPelangganPerAdmin") {
            PelangganPerAdminScreen(navController = navController, viewModel = viewModel)
        }

        composable("detail_pelanggan/{pelangganId}") { backStackEntry ->
            val pelangganId = backStackEntry.arguments?.getString("pelangganId") ?: ""
            DetailPelangganScreen(
                navController = navController,
                viewModel = viewModel,
                pelangganId = pelangganId
            )
        }

        composable("payment_summary") {
            PaymentSummaryScreen(navController = navController, viewModel = viewModel)
        }

        composable("admin_payment_detail/{adminId}") { backStackEntry ->
            val adminId = backStackEntry.arguments?.getString("adminId")
            AdminPaymentDetailScreen(
                navController = navController,
                viewModel = viewModel,
                adminId = adminId
            )
        }

        composable("admin_list") {
            AdminListScreen(
                navController = navController,
                viewModel = viewModel
            )
        }

        composable("admin_pelanggan_detail/{adminId}") { backStackEntry ->
            val adminId = backStackEntry.arguments?.getString("adminId")
            AdminPelangganDetailScreen(
                navController = navController,
                viewModel = viewModel,
                adminId = adminId
            )
        }

        composable("edit/{pelangganId}") { backStackEntry ->
            val pelangganId = backStackEntry.arguments?.getString("pelangganId")
            EditPelangganScreen(
                navController = navController,
                viewModel = viewModel,
                pelangganId = pelangganId
            )
        }

        composable(
            route = "kelolaKredit/{pelangganId}",
            arguments = listOf(navArgument("pelangganId") { type = NavType.StringType })
        ) { backStackEntry ->
            val pelangganId = backStackEntry.arguments?.getString("pelangganId")
            KelolaKreditScreen(
                navController = navController,
                viewModel = viewModel,
                pelangganId = pelangganId
            )
        }

        composable("editPembayaran/{pelangganId}/{index}") { backStackEntry ->
            val pelangganId = backStackEntry.arguments?.getString("pelangganId") ?: ""
            val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: 0

            EditPembayaranScreen(
                navController = navController,
                viewModel = viewModel,
                pelangganId = pelangganId,
                index = index
            )
        }

        composable("tambahSubPembayaran/{pelangganId}/{pembayaranIndex}") { backStackEntry ->
            val pelangganId = backStackEntry.arguments?.getString("pelangganId") ?: ""
            val pembayaranIndex = backStackEntry.arguments?.getString("pembayaranIndex")?.toIntOrNull() ?: 0
            TambahSubPembayaranScreen(
                navController = navController,
                viewModel = viewModel,
                pelangganId = pelangganId,
                pembayaranIndex = pembayaranIndex
            )
        }

        composable("editPinjaman/{pelangganId}") { backStackEntry ->
            val pelangganId = backStackEntry.arguments?.getString("pelangganId")
            EditPinjamanScreen(navController, viewModel, pelangganId)
        }

        composable("daftarNasabahBaruHariIni") {
            DaftarNasabahBaruHariIniScreen(navController = navController, viewModel = viewModel)
        }

        composable("daftarNasabahLunasHariIni") {
            DaftarNasabahLunasHariIniScreen(navController = navController, viewModel = viewModel)
        }

        composable("pengawas_dashboard") {
            PengawasDashboardScreen(navController, viewModel)
        }

        composable(
            route = "pengawas_approvals?tab={tab}",
            arguments = listOf(
                navArgument("tab") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            val initialTab = backStackEntry.arguments?.getInt("tab") ?: 0
            PengawasApprovalScreen(navController, viewModel, initialTab = initialTab)
        }

        composable("pengawas_reports") {
            PengawasReportsScreen(navController = navController, viewModel = viewModel)
        }

        composable("daftarMenungguPencairan") {
            DaftarMenungguPencairanScreen(navController, viewModel)
        }

        composable("detail_serah_terima/{notificationId}") { backStackEntry ->
            val notificationId = backStackEntry.arguments?.getString("notificationId")
            DetailSerahTerimaScreen(
                navController = navController,
                viewModel = viewModel,
                notificationId = notificationId
            )
        }

        composable("pengawas_detail_serah_terima/{notificationId}") { backStackEntry ->
            val notificationId = backStackEntry.arguments?.getString("notificationId")
            PengawasDetailSerahTerimaScreen(
                navController = navController,
                viewModel = viewModel,
                notificationId = notificationId
            )
        }

        composable(
            route = "laporan_harian_admin/{adminId}",
            arguments = listOf(navArgument("adminId") { type = NavType.StringType })
        ) { backStackEntry ->
            val adminId = backStackEntry.arguments?.getString("adminId") ?: ""

            LaporanHarianPimpinanScreen(
                navController = navController,
                viewModel = viewModel,
                targetAdminId = adminId
            )
        }

        composable("daftarPimpinanPelangganStatusKhususSemuaAdmin") {
            PimpinanDaftarStatusKhususScreen(navController, viewModel)
        }

        composable("daftarPimpinanNasabahBaruHariIni") {
            PimpinanDaftarNasabahBaruScreen(navController, viewModel)
        }

        composable("daftarPimpinanNasabahLunasHariIni") {
            PimpinanDaftarNasabahLunasScreen(navController, viewModel)
        }

        composable("daftarPimpinanPelangganBermasalah") {
            PimpinanDaftarBermasalahScreen(navController, viewModel)
        }

        // ✅ BARU: Route untuk Menunggu Pencairan
        composable("daftarPimpinanMenungguPencairan") {
            PimpinanDaftarMenungguPencairanScreen(navController, viewModel)
        }

        composable("pengawasDaftarStatusKhusus") {
            PengawasDaftarStatusKhususScreen(navController, viewModel)
        }

        composable("pengawasDaftarMenungguPencairan") {
            PengawasDaftarMenungguPencairanScreen(navController, viewModel)
        }

        // =========================================================================
        // PENGAWAS USER MANAGEMENT - RESET PASSWORD
        // =========================================================================
        composable("pengawas_user_management") {
            PengawasUserManagementScreen(navController, viewModel)
        }

        // =========================================================================
        // KOORDINATOR ROUTES
        // =========================================================================
        composable("koordinator_dashboard") {
            KoordinatorDashboardScreen(navController, viewModel)
        }

        composable(
            route = "koordinator_approvals?tab={tab}",
            arguments = listOf(
                navArgument("tab") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            val initialTab = backStackEntry.arguments?.getInt("tab") ?: 0
            KoordinatorApprovalScreen(navController, viewModel, initialTab = initialTab)
        }

        composable("koordinator_reports") {
            KoordinatorReportsScreen(navController = navController, viewModel = viewModel)
        }

        composable("koordinatorDaftarStatusKhusus") {
            KoordinatorDaftarStatusKhususScreen(navController, viewModel)
        }

        composable("koordinatorDaftarMenungguPencairan") {
            KoordinatorDaftarMenungguPencairanScreen(navController, viewModel)
        }

        composable("koordinator_detail_serah_terima/{notificationId}") { backStackEntry ->
            val notificationId = backStackEntry.arguments?.getString("notificationId")
            KoordinatorDetailSerahTerimaScreen(
                navController = navController,
                viewModel = viewModel,
                notificationId = notificationId
            )
        }

        // ✅ BARU: Route untuk Daftar Semua Nasabah (Pimpinan)
        composable("pimpinan_daftar_semua_nasabah") {
            PimpinanDaftarSemuaNasabahScreen(navController, viewModel)
        }

        // ✅ BARU: Route untuk Daftar Semua Nasabah (Koordinator)
        composable("koordinator_daftar_semua_nasabah") {
            KoordinatorDaftarSemuaNasabahScreen(navController, viewModel)
        }

        // =========================================================================
        // PENGAWAS LOCATION TRACKING
        // =========================================================================
        composable("pengawas_tracking") {
            PengawasTrackingScreen(navController, viewModel)
        }

        // =========================================================================
        // ABSENSI KARYAWAN
        // =========================================================================
        composable("absensi") {
            AbsensiScreen(navController = navController, viewModel = viewModel)
        }
        }  // end NavHost
    }      // end Scaffold

    // =====================================================================
    // DIALOG PROFIL (hoisted dari AdminHomeScreen) — dipicu oleh tab "Akun"
    // di bottom bar maupun klik avatar di hero card AdminHomeScreen.
    // Logic identik dgn versi sebelumnya, hanya pindah container ke parent.
    // =====================================================================
    if (showPhotoOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoOptionsDialog = false },
            title = { Text(text = "Foto Profil", fontWeight = FontWeight.Bold) },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            showPhotoOptionsDialog = false
                            showFullPhotoDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lihat Foto")
                    }
                    Button(
                        onClick = {
                            showPhotoOptionsDialog = false
                            photoPickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                    ) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ubah Foto")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPhotoOptionsDialog = false }) { Text("Batal") }
            }
        )
    }

    if (showFullPhotoDialog && !adminPhotoUrl.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showFullPhotoDialog = false },
            title = { Text(text = "Foto Profil", fontWeight = FontWeight.Bold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(adminPhotoUrl).crossfade(true).build(),
                        contentDescription = "Foto Profil",
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFullPhotoDialog = false
                        photoPickerLauncher.launch("image/*")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ubah Foto")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullPhotoDialog = false }) { Text("Tutup") }
            }
        )
    }
}