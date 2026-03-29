package com.example.koperasikitagodangulu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.example.koperasikitagodangulu.ui.theme.KreditKitaTheme
import com.google.firebase.database.FirebaseDatabase
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.util.Log
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.example.koperasikitagodangulu.workers.HolidayUpdateWorker
import android.content.Context
import android.widget.Toast
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.tasks.await
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.koperasikitagodangulu.services.NotificationHelper
import com.example.koperasikitagodangulu.services.LocationTrackingMonitor
import com.example.koperasikitagodangulu.services.LocationCheckWorker
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.PowerManager
import com.example.koperasikitagodangulu.services.FirebaseConnectionKeeperService
import com.example.koperasikitagodangulu.services.AutoStartHelper

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: PelangganViewModel by viewModels()
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var isInitialized = false

    // ✅ State untuk navigation dari notification (reactive)
    private var pendingNavigationRoute by mutableStateOf<String?>(null)
    private var pendingPelangganId by mutableStateOf<String?>(null)
    private var pendingTab by mutableStateOf(0)  // ✅ State untuk tab index
    private var shouldForceLogout by mutableStateOf(false)
    // ✅ State untuk gate izin lokasi "Selalu Izinkan"
    private var isLocationFullyGranted by mutableStateOf(false)
    private var showAutoStartDialog by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "✅ Notification permission granted")
            NotificationHelper.fetchAndSaveToken()
        } else {
            Log.w(TAG, "⚠️ Notification permission denied")
            Toast.makeText(
                this,
                "Notifikasi dinonaktifkan. Anda tidak akan menerima pemberitahuan.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ✅ Background location permission launcher (untuk tracking dari background)
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "✅ Background location permission granted")
            isLocationFullyGranted = true  // ✅ Langsung update state
        } else {
            Log.w(TAG, "⚠️ Background location permission denied")
            isLocationFullyGranted = false
        }
    }

    // ✅ Location permission launcher (untuk tracking)
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            Log.d(TAG, "✅ Location permission granted")
            // ✅ Untuk Android < 10, fine location sudah cukup
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                isLocationFullyGranted = true
            }
            // Setelah fine/coarse granted, minta background location (terpisah, wajib Android 10+)
            requestBackgroundLocationPermission()
        } else {
            Log.w(TAG, "⚠️ Location permission denied")
            isLocationFullyGranted = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // ✅ Cek izin lokasi "Selalu Izinkan"
        isLocationFullyGranted = isBackgroundLocationGranted(this)

        Log.d(TAG, "🚀 =====================================")
        Log.d(TAG, "🚀 onCreate called")
        Log.d(TAG, "🚀 =====================================")

//        try {
//            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
//        } catch (ignore: Exception) {
//            Log.d(TAG, "Offline persistence sudah diaktifkan sebelumnya.")
//        }

        NotificationHelper.initialize(this)
        processNotificationIntent(intent)

        // Delay operasi non-kritis agar tidak membebani startup
        window.decorView.post {
            requestNotificationPermission()
            requestLocationPermission()
            requestBatteryOptimizationExemption()
            setupHolidayUpdateWork()
            runHolidayUpdateOnAppStart()
            setupNetworkMonitoring()
        }

        //viewModel.startRoleDetectionAndInit()

        setContent {
            val vm: PelangganViewModel = viewModel()
            val isDarkMode by vm.isDarkMode
            val currentRole by vm.currentUserRole.collectAsState()

            KreditKitaTheme(darkTheme = vm.isDarkMode.value) {
                val navController = rememberNavController()

                // Role initialization
                LaunchedEffect(currentRole) {
                    if (currentRole == UserRole.UNKNOWN || isInitialized) {
                        Log.d(TAG, "⏳ Role: $currentRole, Initialized: $isInitialized")
                        return@LaunchedEffect
                    }

                    Log.d(TAG, "🎯 Role ready: $currentRole")
                    isInitialized = true

                    try {
                        NotificationHelper.fetchAndSaveToken()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error fetching token: ${e.message}")
                    }

                    try {
                        withContext(Dispatchers.IO) {
                            val uid = Firebase.auth.currentUser?.uid
                            if (!uid.isNullOrBlank()) {
                                Log.d(TAG, "📄 Loading data for UID: $uid")
                                vm.muatDariLokal(uid)
                                withContext(Dispatchers.Main) {
                                    vm.listenPelangganRealtime()
                                    vm.debugFirebaseStructure()
                                }
                                delay(2000)
                                vm.syncOfflineData()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error loading data: ${e.message}")
                    }

                    // ✅ Start location tracking untuk non-Pengawas
                    if (currentRole != UserRole.PENGAWAS && currentRole != UserRole.UNKNOWN) {
                        try {
                            // ✅ UTAMA: Persistent service yang menjaga koneksi Firebase
                            FirebaseConnectionKeeperService.start(this@MainActivity.applicationContext)

                            // Tetap jalankan sebagai backup layer
                            LocationTrackingMonitor.startMonitoring(this@MainActivity.applicationContext)
                            LocationCheckWorker.schedule(this@MainActivity.applicationContext)
                            Log.d(TAG, "📍 Location tracking started for role: $currentRole")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error starting location tracking: ${e.message}")
                        }

                        // ✅ Pastikan background location sudah di-grant
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                requestBackgroundLocationPermission()
                            }
                        }

                        // ✅ BARU: Tampilkan prompt auto-start untuk Chinese ROM (sekali saja)
                        if (AutoStartHelper.needsAutoStartPermission() && !AutoStartHelper.hasBeenPrompted(this@MainActivity)) {
                            showAutoStartDialog = true
                        }
                    }
                }

                // ✅ Handle pending navigation - REACTIVE ke state changes
                // ✅ Handle pending navigation - REACTIVE ke state changes
                LaunchedEffect(pendingNavigationRoute) {
                    val route = pendingNavigationRoute
                    val pelangganId = pendingPelangganId
                    val tab = pendingTab

                    if (route != null) {
                        Log.d(TAG, "🚀 =====================================")
                        Log.d(TAG, "🚀 PROCESSING NAVIGATION")
                        Log.d(TAG, "🚀 Route: $route, Tab: $tab")
                        Log.d(TAG, "🚀 PelangganId: $pelangganId")
                        Log.d(TAG, "🚀 =====================================")

                        // Tunggu navigation graph ready
                        delay(1000)

                        try {
                            when (route) {
                                "pimpinan_approvals" -> {
                                    Log.d(TAG, "📍 Navigating to: pimpinan_approvals?tab=$tab")
                                    navController.navigate("pimpinan_approvals?tab=$tab") {
                                        popUpTo("auth") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                    Log.d(TAG, "✅ Navigation to pimpinan_approvals SUCCESS")
                                }
                                "koordinator_approvals" -> {
                                    Log.d(TAG, "📍 Navigating to: koordinator_approvals?tab=$tab")
                                    navController.navigate("koordinator_approvals?tab=$tab") {
                                        popUpTo("auth") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                    Log.d(TAG, "✅ Navigation to koordinator_approvals SUCCESS")
                                }
                                "pengawas_approvals" -> {
                                    Log.d(TAG, "📍 Navigating to: pengawas_approvals?tab=$tab")
                                    navController.navigate("pengawas_approvals?tab=$tab") {
                                        popUpTo("auth") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                    Log.d(TAG, "✅ Navigation to pengawas_approvals SUCCESS")
                                }
                                "serah_terima_notification" -> {
                                    // Navigasi berdasarkan role user
                                    val role = vm.currentUserRole.value
                                    Log.d(TAG, "📍 Serah terima notification, role: $role")
                                    when (role) {
                                        UserRole.PIMPINAN -> {
                                            navController.navigate("pimpinan_approvals?tab=2") {
                                                popUpTo("auth") { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        }
                                        UserRole.KOORDINATOR -> {
                                            navController.navigate("koordinator_approvals?tab=2") {
                                                popUpTo("auth") { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        }
                                        UserRole.PENGAWAS -> {
                                            navController.navigate("pengawas_approvals?tab=1") {
                                                popUpTo("auth") { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        }
                                        else -> {
                                            // Admin lapangan - ke notifikasi
                                            navController.navigate("notifikasi") {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                }
                                "notifikasi" -> {
                                    Log.d(TAG, "📍 Navigating to: notifikasi")
                                    navController.navigate("notifikasi") {
                                        popUpTo("auth") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                    Log.d(TAG, "✅ Navigation to notifikasi SUCCESS")
                                }
                                "detail_pelanggan" -> {
                                    if (!pelangganId.isNullOrBlank()) {
                                        Log.d(TAG, "📍 Navigating to: detail_pelanggan/$pelangganId")
                                        navController.navigate("detail_pelanggan/$pelangganId") {
                                            launchSingleTop = true
                                        }
                                        Log.d(TAG, "✅ Navigation to detail_pelanggan SUCCESS")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Navigation ERROR: ${e.message}")
                            e.printStackTrace()
                        }

                        // Clear pending navigation
                        pendingNavigationRoute = null
                        pendingPelangganId = null
                        pendingTab = 0
                    }
                }

                // Handle force logout
                LaunchedEffect(shouldForceLogout) {
                    Log.d(TAG, "🚪 LaunchedEffect(shouldForceLogout) triggered, value: $shouldForceLogout")
                    if (shouldForceLogout) {
                        Log.d(TAG, "🚪🚪🚪 FORCE LOGOUT - NAVIGATING TO AUTH 🚪🚪🚪")
                        try {
                            navController.navigate("auth") {
                                popUpTo(0) { inclusive = true }
                            }
                            Log.d(TAG, "✅ Navigation to auth SUCCESS")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Navigation to auth FAILED: ${e.message}")
                            e.printStackTrace()
                        }
                        shouldForceLogout = false
                        Log.d(TAG, "✅ shouldForceLogout reset to FALSE")
                    }
                }

                // ✅ Gate: Paksa "Selalu Izinkan" sebelum bisa masuk aplikasi
                if (isLocationFullyGranted) {
                    AppNavigation(
                        navController = navController,
                        viewModel = vm
                    )
                } else {
                    LocationPermissionGateScreen()
                }

                // ✅ BARU: Dialog Auto-Start Permission
                if (showAutoStartDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            showAutoStartDialog = false
                            AutoStartHelper.markAsPrompted(this@MainActivity)
                        },
                        title = {
                            androidx.compose.material3.Text("Izin Diperlukan")
                        },
                        text = {
                            androidx.compose.material3.Text(
                                "Agar aplikasi dapat berjalan dengan baik di latar belakang " +
                                        "(sinkronisasi data, notifikasi, dan pencatatan otomatis), " +
                                        "mohon aktifkan izin \"${AutoStartHelper.getSettingsName()}\" " +
                                        "untuk aplikasi KoperasiKita.\n\n" +
                                        "Tanpa izin ini, beberapa fitur mungkin tidak berfungsi saat " +
                                        "aplikasi tidak sedang dibuka."
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    showAutoStartDialog = false
                                    AutoStartHelper.markAsPrompted(this@MainActivity)
                                    AutoStartHelper.openAutoStartSettings(this@MainActivity)
                                }
                            ) {
                                androidx.compose.material3.Text("Buka Pengaturan")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    showAutoStartDialog = false
                                    AutoStartHelper.markAsPrompted(this@MainActivity)
                                }
                            ) {
                                androidx.compose.material3.Text("Nanti Saja")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "📬 onNewIntent called")
        processNotificationIntent(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "✅ Notification permission already granted")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    /**
     * ✅ Request location permission untuk tracking
     * Diminta ke SEMUA role agar tidak mencurigakan
     */
    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /**
     * ✅ Request background location permission (wajib untuk tracking saat app tidak dibuka)
     * Android 10+: Harus diminta TERPISAH dari fine/coarse
     */
    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    /**
     * ✅ Request battery optimization exemption agar tracking service tidak di-kill sistem
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Log.d(TAG, "📋 Battery optimization exemption requested")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error requesting battery exemption: ${e.message}")
                }
            }
        }
    }

    /**
     * ✅ PERBAIKAN UTAMA: Process notification intent
     *
     * Handle SEMUA skenario:
     * 1. App FOREGROUND: dari PendingIntent kita (from_notification=true, notification_type=X)
     * 2. App BACKGROUND/KILLED: dari FCM data payload (type=X, pelangganId=Y)
     */
    private fun processNotificationIntent(intent: Intent?) {
        if (intent == null) {
            Log.d(TAG, "📩 Intent is null, skipping")
            return
        }

        Log.d(TAG, "📩 =====================================")
        Log.d(TAG, "📩 PROCESSING NOTIFICATION INTENT")
        Log.d(TAG, "📩 =====================================")

        // Log ALL extras untuk debugging
        val extras = intent.extras
        if (extras != null) {
            Log.d(TAG, "📦 Intent extras (${extras.keySet().size} items):")
            for (key in extras.keySet()) {
                Log.d(TAG, "   $key = ${extras.get(key)}")
            }
        } else {
            Log.d(TAG, "📦 No extras in intent")
            return
        }

        // =====================================================================
        // CEK DARI MANA INTENT INI BERASAL
        // =====================================================================

        // Skenario 1: Dari PendingIntent kita (app foreground)
        val fromNotification = intent.getBooleanExtra("from_notification", false)
        val notificationTypeFromPending = intent.getStringExtra("notification_type") ?: ""

        // Skenario 2: Dari FCM data payload (app background/killed)
        // Saat app di background, Android SISTEM langsung pass data payload ke extras
        val typeFromFCM = intent.getStringExtra("type") ?: ""

        // Ambil pelangganId dari manapun
        val pelangganId = intent.getStringExtra("pelangganId") ?: ""

        Log.d(TAG, "📊 Parsed values:")
        Log.d(TAG, "   fromNotification (PendingIntent): $fromNotification")
        Log.d(TAG, "   notificationTypeFromPending: '$notificationTypeFromPending'")
        Log.d(TAG, "   typeFromFCM (data payload): '$typeFromFCM'")
        Log.d(TAG, "   pelangganId: '$pelangganId'")

        // =====================================================================
        // TENTUKAN NOTIFICATION TYPE
        // Prioritas: FCM data payload > PendingIntent
        // =====================================================================
        val notificationType = when {
            typeFromFCM.isNotEmpty() -> {
                Log.d(TAG, "✅ Using type from FCM data payload: $typeFromFCM")
                typeFromFCM
            }
            notificationTypeFromPending.isNotEmpty() -> {
                Log.d(TAG, "✅ Using type from PendingIntent: $notificationTypeFromPending")
                notificationTypeFromPending
            }
            else -> {
                Log.d(TAG, "⚠️ No notification type found")
                ""
            }
        }

        if (notificationType.isEmpty()) {
            Log.d(TAG, "⚠️ Notification type is empty, skipping navigation")
            return
        }

        // =====================================================================
        // SET PENDING NAVIGATION (akan trigger LaunchedEffect)
        // =====================================================================
        Log.d(TAG, "🎯 Notification type: $notificationType")

        when (notificationType) {
            // =========================================================================
            // NOTIFIKASI UNTUK PIMPINAN - TAB PENGAJUAN (tab 0)
            // =========================================================================
            "NEW_PENGAJUAN", "TOPUP_APPROVAL" -> {
                Log.d(TAG, "💾 Setting pendingNavigationRoute = pimpinan_approvals (tab 0)")
                pendingPelangganId = pelangganId
                pendingTab = 0  // Tab Pengajuan
                pendingNavigationRoute = "pimpinan_approvals"
            }

            // =========================================================================
            // NOTIFIKASI UNTUK PIMPINAN - TAB FINALISASI (tab 1)
            // =========================================================================
            "KOORDINATOR_FINAL_REVIEWED" -> {
                Log.d(TAG, "💾 Setting pendingNavigationRoute = pimpinan_approvals (tab 1 - finalisasi)")
                pendingPelangganId = pelangganId
                pendingTab = 1  // Tab Finalisasi
                pendingNavigationRoute = "pimpinan_approvals"
            }

            // =========================================================================
            // NOTIFIKASI UNTUK KOORDINATOR - TAB PENGAJUAN (tab 0)
            // =========================================================================
            "PIMPINAN_REVIEWED" -> {
                Log.d(TAG, "💾 Setting pendingNavigationRoute = koordinator_approvals (tab 0)")
                pendingPelangganId = pelangganId
                pendingTab = 0  // Tab Pengajuan
                pendingNavigationRoute = "koordinator_approvals"
            }

            // =========================================================================
            // NOTIFIKASI UNTUK KOORDINATOR - TAB FINALISASI (tab 1)
            // =========================================================================
            "PENGAWAS_REVIEWED" -> {
                Log.d(TAG, "💾 Setting pendingNavigationRoute = koordinator_approvals (tab 1 - finalisasi)")
                pendingPelangganId = pelangganId
                pendingTab = 1  // Tab Finalisasi
                pendingNavigationRoute = "koordinator_approvals"
            }

            // =========================================================================
            // NOTIFIKASI UNTUK PENGAWAS - TAB PENGAJUAN (tab 0)
            // =========================================================================
            "KOORDINATOR_REVIEWED", "NEW_PENGAJUAN_DUAL" -> {
                Log.d(TAG, "💾 Setting pendingNavigationRoute = pengawas_approvals (tab 0)")
                pendingPelangganId = pelangganId
                pendingTab = 0  // Tab Pengajuan
                pendingNavigationRoute = "pengawas_approvals"
            }

            // =========================================================================
            // NOTIFIKASI SERAH TERIMA - TAB SERAH TERIMA
            // =========================================================================
            "SERAH_TERIMA" -> {
                Log.d(TAG, "💾 Setting pendingNavigationRoute based on role")
                pendingPelangganId = pelangganId
                // Tab serah terima: Pimpinan=2, Koordinator=2, Pengawas=1
                pendingTab = 2  // Default untuk serah terima
                pendingNavigationRoute = "serah_terima_notification"
            }

            // =========================================================================
            // NOTIFIKASI HASIL APPROVAL (untuk Admin Lapangan)
            // =========================================================================
            "APPROVAL", "REJECTION", "DUAL_APPROVAL_APPROVED", "DUAL_APPROVAL_REJECTED" -> {
                Log.d(TAG, "💾 Setting pendingNavigationRoute = notifikasi")
                pendingPelangganId = pelangganId
                pendingTab = 0
                pendingNavigationRoute = "notifikasi"
            }

            else -> {
                Log.d(TAG, "⚠️ Unknown notification type: $notificationType")
            }
        }

        // Clear intent extras untuk mencegah re-processing
        intent.replaceExtras(Bundle())
    }

    private fun setupNetworkMonitoring() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d("Network", "🌐 Koneksi tersedia")

                if (!isInitialized) {
                    Log.d("Network", "⏳ Waiting for initialization")
                    return
                }

                CoroutineScope(Dispatchers.IO).launch {
                    delay(3000)
                    if (viewModel.currentUserRole.value != UserRole.UNKNOWN) {
                        Log.d("Network", "🔄 Starting offline sync...")
                        viewModel.syncOfflineData()
                    }
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d("Network", "📵 Koneksi terputus")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "🔄 onResume CALLED")
        // ✅ Re-check izin lokasi setiap kali user kembali dari Settings
        isLocationFullyGranted = isBackgroundLocationGranted(this)

        // HANYA cek force logout jika:
        // 1. Ada user yang login
        // 2. Ada koneksi internet
        // 3. Belum pernah dicek dalam session ini

        val currentUser = Firebase.auth.currentUser
        currentUser?.getIdToken(false)?.addOnFailureListener {
            Log.w(TAG, "⚠️ Token refresh failed: ${it.message}")
        }
        if (currentUser == null) {
            Log.d(TAG, "🔄 No user logged in, skipping force logout check")
            return
        }

        // Cek apakah sudah pernah dicek dalam session ini
        val sharedPrefs = getSharedPreferences("force_logout_prefs", Context.MODE_PRIVATE)
        val lastCheckTime = sharedPrefs.getLong("last_check_${currentUser.uid}", 0L)
        val currentTime = System.currentTimeMillis()

        // Hanya cek jika sudah lebih dari 5 detik sejak pengecekan terakhir
        // Ini mencegah pengecekan berulang saat navigasi antar screen
        if (currentTime - lastCheckTime < 60000) {
            Log.d(TAG, "🔄 Skipping force logout check (checked recently)")
            return
        }

        // Cek koneksi internet (compatible dengan API 21+)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isOnline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            networkCapabilities != null
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            networkInfo != null && networkInfo.isConnected
        }

        if (!isOnline) {
            Log.d(TAG, "🔄 Offline, skipping force logout check")
            return
        }

        val uid = currentUser.uid
        Log.d(TAG, "🔄 Checking force_logout for UID: $uid")

        // Simpan waktu pengecekan
        sharedPrefs.edit().putLong("last_check_${uid}", currentTime).apply()

        // Query dengan timeout - gunakan .get().await() untuk SELALU ambil data fresh dari server
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    FirebaseDatabase.getInstance("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app")
                        .getReference("force_logout")
                        .child(uid)
                        .get()
                        .await()
                }

                if (snapshot == null) {
                    Log.d(TAG, "🔄 Timeout checking force_logout, skipping")
                    return@launch
                }

                if (snapshot.exists()) {
                    val forceLogoutTimestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                    val lastLogin = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        .getLong("last_login_timestamp", 0L)

                    if (lastLogin > 0L && forceLogoutTimestamp <= lastLogin) {
                        Log.d(TAG, "🧹 onResume: stale force_logout (ts=$forceLogoutTimestamp <= lastLogin=$lastLogin), ignoring stale node")
                        // JANGAN coba removeValue — jika Permission denied akan memicu loop di ValueEventListener
                        // Biarkan saja, node stale ini tidak berbahaya selama sudah di-ignore
                    } else {
                        // Node valid — password benar-benar direset SETELAH login terakhir
                        Log.w(TAG, "⚠️ FORCE LOGOUT DETECTED! (ts=$forceLogoutTimestamp > lastLogin=$lastLogin)")

                        // Hapus node DULU sebelum signOut
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(3000L) {
                                snapshot.ref.removeValue().await()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to remove force_logout node: ${e.message}")
                        }

                        // Clear cache untuk uid ini
                        sharedPrefs.edit().remove("last_check_${uid}").apply()

                        // Sign out
                        Firebase.auth.signOut()

                        // Stop location tracking sebelum logout
                        FirebaseConnectionKeeperService.stop(this@MainActivity)
                        LocationTrackingMonitor.stopMonitoring()
                        LocationCheckWorker.cancel(this@MainActivity)

                        Log.d(TAG, "✅ Firebase.auth.signOut() called")

                        withContext(Dispatchers.Main) {
                            shouldForceLogout = true
                            Toast.makeText(
                                this@MainActivity,
                                "Password Anda telah diubah. Silakan login kembali.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    Log.d(TAG, "🔄 No force_logout node found, user can continue")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking force_logout: ${e.message}")
                // Jangan logout jika query gagal
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e("Network", "Error unregistering callback: ${e.message}")
        }
    }

    private fun setupHolidayUpdateWork() {
        val holidayUpdateRequest = PeriodicWorkRequestBuilder<HolidayUpdateWorker>(
            30, TimeUnit.DAYS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "holidayUpdateWork",
            ExistingPeriodicWorkPolicy.KEEP,
            holidayUpdateRequest
        )
    }

    private fun runHolidayUpdateOnAppStart() {
        val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lastUpdateTime = sharedPrefs.getLong("last_holiday_update", 0)
        val currentTime = System.currentTimeMillis()
        val oneDayInMillis = 24 * 60 * 60 * 1000

        if (currentTime - lastUpdateTime > oneDayInMillis) {
            val oneTimeRequest = OneTimeWorkRequestBuilder<HolidayUpdateWorker>().build()
            WorkManager.getInstance(this).enqueue(oneTimeRequest)
            sharedPrefs.edit().putLong("last_holiday_update", currentTime).apply()
            Log.d(TAG, "Holiday update dijalankan")
            Toast.makeText(this, "Memperbarui jadwal cicilan...", Toast.LENGTH_SHORT).show()
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    KreditKitaTheme {
        Text("Preview KoperasiKitaGodangUlu")
    }
}