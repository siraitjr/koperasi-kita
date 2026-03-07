package com.example.koperasikitagodangulu

import android.widget.Toast
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthException
import com.example.koperasikitagodangulu.services.LocationTrackingMonitor
import com.example.koperasikitagodangulu.services.LocationCheckWorker
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers


// =========================================================================
// Modern Color Palette for Auth Screen
// =========================================================================
object AuthColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val backgroundGradient = listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF4338CA))
    val cardBackground = Color.White
    val textPrimary = Color(0xFF1E293B)
    val textSecondary = Color(0xFF64748B)
    val textMuted = Color(0xFF94A3B8)
    val primary = Color(0xFF6366F1)
    val success = Color(0xFF10B981)
    val inputBackground = Color(0xFFF8FAFC)
    val inputBorder = Color(0xFFE2E8F0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    navController: NavController,
    viewModel: PelangganViewModel? = null
) {
    val context = LocalContext.current
    val auth = Firebase.auth
    val focusManager = LocalFocusManager.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }
    var isCheckingAuth by remember { mutableStateOf(true) }

    // ✅ Set status bar & navigation bar
    val systemUiController = rememberSystemUiController()
    SideEffect {
        if (isCheckingAuth) {
            // Saat sedang cek auth, tetap hitam (menyambung dari splash)
            systemUiController.setStatusBarColor(Color.Black, darkIcons = false)
            systemUiController.setNavigationBarColor(Color.Black, darkIcons = false)
        } else {
            // Saat form login ditampilkan, gunakan warna gradient AuthScreen
            systemUiController.setStatusBarColor(Color(0xFF1E1B4B), darkIcons = false)
            systemUiController.setNavigationBarColor(Color(0xFF4338CA), darkIcons = false)
        }
    }

    // Auto login
    LaunchedEffect(Unit) {
        android.util.Log.d("AuthScreen", "🔍 viewModel is null: ${viewModel == null}")
        val currentUser = auth.currentUser
        if (currentUser != null && currentUser.providerData.any { it.providerId == "password" }) {
            val userRole = getUserRole(context)

            // CEK FORCE LOGOUT SEBELUM AUTO-LOGIN (kecuali Pengawas)
            if (userRole != UserRole.PENGAWAS && userRole != UserRole.KOORDINATOR) {
                val shouldForceLogout = viewModel?.checkForceLogoutOnStartup() ?: false
                if (shouldForceLogout) {
                    // Password sudah direset, paksa logout
                    LocationTrackingMonitor.stopMonitoring()
                    auth.signOut()
                    Toast.makeText(
                        context,
                        "Password Anda telah diubah oleh Pengawas. Silakan login dengan password baru.",
                        Toast.LENGTH_LONG
                    ).show()
                    // ✅ Tampilkan form login
                    isCheckingAuth = false
                    isVisible = true
                    return@LaunchedEffect  // Tetap di halaman auth
                }
            }

            // ✅ CEK SESSION LOCK - akun admin sedang diambil alih pimpinan?
            if (userRole == UserRole.ADMIN_LAPANGAN || userRole == UserRole.UNKNOWN) {
                val lockedByName = viewModel?.checkSessionLock(currentUser.uid)
                if (lockedByName != null) {
                    LocationTrackingMonitor.stopMonitoring()
                    auth.signOut()
                    Toast.makeText(
                        context,
                        "Akun Anda sedang digunakan oleh $lockedByName. Silakan tunggu hingga selesai.",
                        Toast.LENGTH_LONG
                    ).show()
                    isCheckingAuth = false
                    isVisible = true
                    return@LaunchedEffect
                }
            }

            // ✅ User sudah login dan valid — langsung ke dashboard
            // isCheckingAuth tetap true (layar tetap hitam hingga navigasi selesai)
            when (userRole) {
                UserRole.PENGAWAS -> navController.navigate("pengawas_dashboard") {
                    popUpTo("auth") { inclusive = true }
                }
                UserRole.KOORDINATOR -> navController.navigate("koordinator_dashboard") {
                    popUpTo("auth") { inclusive = true }
                }
                UserRole.PIMPINAN -> navController.navigate("pimpinan_dashboard") {
                    popUpTo("auth") { inclusive = true }
                }
                UserRole.ADMIN_LAPANGAN, UserRole.UNKNOWN -> navController.navigate("dashboard") {
                    popUpTo("auth") { inclusive = true }
                }
            }
        } else {
            // ✅ User belum login — tampilkan form login
            isCheckingAuth = false
            isVisible = true
        }
    }

    if (isCheckingAuth) {
        // ✅ Layar hitam yang menyambung mulus dari splash screen
        // Ditampilkan selama pengecekan auth berlangsung (beberapa milidetik)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(AuthColors.backgroundGradient))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { focusManager.clearFocus() }
    ) {
        // Decorative elements
        Box(
            modifier = Modifier
                .size(200.dp)
                .offset(x = (-50).dp, y = (-50).dp)
                .background(Color.White.copy(alpha = 0.05f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(150.dp)
                .align(Alignment.TopEnd)
                .offset(x = 50.dp, y = 100.dp)
                .background(Color.White.copy(alpha = 0.05f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(100.dp)
                .align(Alignment.BottomStart)
                .offset(x = 30.dp, y = (-100).dp)
                .background(Color.White.copy(alpha = 0.05f), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .statusBarsPadding()
                .imePadding()
                .systemBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            // Logo/Header Section
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(600)) + slideInVertically(
                    initialOffsetY = { -50 },
                    animationSpec = tween(600)
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Logo Container - menggunakan logo kustom
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = RoundedCornerShape(24.dp),
                                ambientColor = AuthColors.primary.copy(alpha = 0.3f)
                            )
                            .clip(RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.logo_splash),
                            contentDescription = "Logo KoperasiKita",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Koperasi Kita",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Selamat datang \nKSP Sigodang Ulu Jaya",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Login Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(600, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 50 },
                    animationSpec = tween(600, delayMillis = 200)
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 16.dp,
                            shape = RoundedCornerShape(28.dp),
                            ambientColor = Color.Black.copy(alpha = 0.2f)
                        ),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = AuthColors.cardBackground)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Masuk ke Akun",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuthColors.textPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Silakan masukkan kredensial Anda",
                            fontSize = 14.sp,
                            color = AuthColors.textSecondary,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        // Email Field
                        ModernAuthTextField(
                            value = email,
                            onValueChange = { email = it.trim() },
                            label = "Email",
                            placeholder = "Masukkan email Anda",
                            leadingIcon = Icons.Rounded.Email,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.clearFocus() }
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Password Field
                        ModernAuthTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "Password",
                            placeholder = "Masukkan password",
                            leadingIcon = Icons.Rounded.Lock,
                            isPassword = true,
                            passwordVisible = passwordVisible,
                            onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { focusManager.clearFocus() }
                            )
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        // Login Button
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                if (email.isBlank() || password.isBlank()) {
                                    Toast.makeText(context, "Email dan password harus diisi", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                isLoading = true
                                auth.signInWithEmailAndPassword(email, password)
                                    .addOnCompleteListener { task ->
                                        if (task.isSuccessful) {
                                            val loggedInUid = auth.currentUser?.uid
                                            val userEmail = email.trim()
                                            val userRole = determineUserRole(userEmail)

                                            // CEK SESSION LOCK (hanya untuk admin lapangan)
                                            if (loggedInUid != null && userRole == UserRole.ADMIN_LAPANGAN) {
                                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                                    val lockedByName = viewModel?.checkSessionLock(loggedInUid)
                                                    if (lockedByName != null) {
                                                        // Akun sedang digunakan pimpinan
                                                        auth.signOut()
                                                        isLoading = false
                                                        Toast.makeText(
                                                            context,
                                                            "Akun Anda sedang digunakan oleh $lockedByName. Silakan tunggu hingga selesai.",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                        return@launch
                                                    }
                                                    // Session tidak di-lock, lanjut login normal
                                                    proceedWithLogin(context, auth, navController, viewModel, userEmail, userRole, loggedInUid)
                                                }
                                            } else {
                                                // Bukan admin lapangan, langsung lanjut
                                                proceedWithLogin(context, auth, navController, viewModel, userEmail, userRole, loggedInUid)
                                            }
                                        } else {
                                            isLoading = false
                                            val errorMessage = when (val exception = task.exception) {
                                                is com.google.firebase.auth.FirebaseAuthInvalidUserException,
                                                is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> {
                                                    "Email atau password yang Anda masukkan salah"
                                                }
                                                is com.google.firebase.FirebaseNetworkException -> {
                                                    "Tidak ada koneksi internet. Periksa jaringan Anda lalu coba lagi."
                                                }
                                                is com.google.firebase.auth.FirebaseAuthException -> {
                                                    when (exception.errorCode) {
                                                        "ERROR_USER_NOT_FOUND",
                                                        "ERROR_WRONG_PASSWORD",
                                                        "ERROR_INVALID_EMAIL",
                                                        "ERROR_INVALID_CREDENTIAL" -> "Email atau password yang Anda masukkan salah"
                                                        "ERROR_USER_DISABLED" -> "Akun Anda telah dinonaktifkan. Hubungi admin."
                                                        "ERROR_TOO_MANY_REQUESTS" -> "Terlalu banyak percobaan login. Coba lagi nanti."
                                                        else -> "Login gagal. Silakan coba lagi."
                                                    }
                                                }
                                                else -> {
                                                    if (exception?.message?.contains("network", ignoreCase = true) == true ||
                                                        exception?.message?.contains("internet", ignoreCase = true) == true ||
                                                        exception?.message?.contains("connection", ignoreCase = true) == true) {
                                                        "Tidak ada koneksi internet. Periksa jaringan Anda lalu coba lagi."
                                                    } else {
                                                        "Login gagal. Silakan coba lagi."
                                                    }
                                                }
                                            }
                                            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                                        }
                                    }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(),
                            enabled = !isLoading
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            if (isLoading) listOf(
                                                AuthColors.textMuted,
                                                AuthColors.textMuted
                                            ) else AuthColors.primaryGradient
                                        ),
                                        RoundedCornerShape(16.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Login,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                        Text(
                                            text = "Masuk",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Footer
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(600, delayMillis = 400))
            ) {
                Text(
                    text = "© 2026 Koperasi Kita",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernAuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onPasswordVisibilityChange: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = AuthColors.textPrimary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = placeholder,
                    color = AuthColors.textMuted
                )
            },
            leadingIcon = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            AuthColors.primary.copy(alpha = 0.1f),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = AuthColors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { onPasswordVisibilityChange?.invoke() }) {
                        Icon(
                            imageVector = if (passwordVisible)
                                Icons.Rounded.Visibility
                            else
                                Icons.Rounded.VisibilityOff,
                            contentDescription = if (passwordVisible) "Sembunyikan password" else "Tampilkan password",
                            tint = AuthColors.textMuted
                        )
                    }
                }
            } else null,
            singleLine = true,
            visualTransformation = if (isPassword && !passwordVisible)
                PasswordVisualTransformation()
            else
                VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AuthColors.primary,
                unfocusedBorderColor = AuthColors.inputBorder,
                focusedContainerColor = AuthColors.inputBackground,
                unfocusedContainerColor = AuthColors.inputBackground,
                cursorColor = AuthColors.primary
            )
        )
    }
}

// =========================================================================
// HELPER FUNCTIONS
// =========================================================================
private fun determineUserRole(email: String): UserRole {
    return when {
        email.contains("pengawas", ignoreCase = true) ||
                email.endsWith("@pengawas.koperasi.id", ignoreCase = true) -> UserRole.PENGAWAS
        email.contains("koordinator", ignoreCase = true) ||
                email.endsWith("@koordinator.koperasi.id", ignoreCase = true) -> UserRole.KOORDINATOR
        email.contains("pimpinan", ignoreCase = true) ||
                email.endsWith("@pimpinan.koperasi.id", ignoreCase = true) -> UserRole.PIMPINAN
        email.contains("admin", ignoreCase = true) ||
                email.endsWith("@admin.koperasi.id", ignoreCase = true) -> UserRole.ADMIN_LAPANGAN
        email.contains("direktur", ignoreCase = true) ||
                email.contains("manager", ignoreCase = true) -> UserRole.PIMPINAN
        else -> UserRole.ADMIN_LAPANGAN
    }
}

private fun proceedWithLogin(
    context: Context,
    auth: com.google.firebase.auth.FirebaseAuth,
    navController: NavController,
    viewModel: PelangganViewModel?,
    userEmail: String,
    userRole: UserRole,
    loggedInUid: String?
) {
    saveUserRole(context, userRole)

    // Hapus node force_logout yang basi
    if (loggedInUid != null) {
        com.google.firebase.database.FirebaseDatabase.getInstance("https://koperasikitagodangulu-default-rtdb.asia-southeast1.firebasedatabase.app")
            .getReference("force_logout")
            .child(loggedInUid)
            .removeValue()

        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_login_timestamp", System.currentTimeMillis())
            .apply()
    }

    // Start force logout listener
    if (userRole != UserRole.PENGAWAS) {
        viewModel?.startForceLogoutListener {
            com.example.koperasikitagodangulu.services.LocationTrackingMonitor.stopMonitoring()
            com.example.koperasikitagodangulu.services.LocationCheckWorker.cancel(context)
            auth.signOut()
            android.widget.Toast.makeText(
                context,
                "Password Anda telah diubah. Silakan login kembali.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            navController.navigate("auth") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Start remote takeover listener (hanya admin lapangan)
    if (userRole == UserRole.ADMIN_LAPANGAN) {
        viewModel?.startRemoteTakeoverListener {
            com.example.koperasikitagodangulu.services.LocationTrackingMonitor.stopMonitoring()
            com.example.koperasikitagodangulu.services.LocationCheckWorker.cancel(context)
            auth.signOut()
            android.widget.Toast.makeText(
                context,
                "Pimpinan mengambil alih akun Anda. Anda akan logout otomatis.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            navController.navigate("auth") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    viewModel?.startRoleDetectionAndInit()

    android.widget.Toast.makeText(
        context,
        "Login sukses sebagai ${userRole.name}",
        android.widget.Toast.LENGTH_SHORT
    ).show()

    when (userRole) {
        UserRole.PENGAWAS -> navController.navigate("pengawas_dashboard") {
            popUpTo("auth") { inclusive = true }
        }
        UserRole.KOORDINATOR -> navController.navigate("koordinator_dashboard") {
            popUpTo("auth") { inclusive = true }
        }
        UserRole.PIMPINAN -> navController.navigate("pimpinan_dashboard") {
            popUpTo("auth") { inclusive = true }
        }
        UserRole.ADMIN_LAPANGAN, UserRole.UNKNOWN -> navController.navigate("dashboard") {
            popUpTo("auth") { inclusive = true }
        }
    }
}