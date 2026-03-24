package com.example.koperasikitagodangulu

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.NavType
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.example.koperasikitagodangulu.ui.screens.SplashScreen

@Composable
fun AppNavigation(navController: NavHostController, viewModel: PelangganViewModel) {
    NavHost(
        navController = navController,
        startDestination = "splash"
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
            AdminHomeScreen(navController, viewModel)
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

        composable("pengawas_approvals") {
            PengawasApprovalScreen(navController, viewModel)
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

        composable("daftarPimpinanPelangganStatusKhususSemuaAdmin") {
            PimpinanDaftarStatusKhususScreen(navController, viewModel)
        }

        // ✅ BARU: Route untuk Menunggu Pencairan
        composable("daftarPimpinanMenungguPencairan") {
            PimpinanDaftarMenungguPencairanScreen(navController, viewModel)
        }

        // ✅ BARU: Route untuk Nasabah Lunas Total
        composable("pimpinanDaftarNasabahLunasTotal") {
            PimpinanDaftarNasabahLunasAllScreen(navController, viewModel)
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
    }
}