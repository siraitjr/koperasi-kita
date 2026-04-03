package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.google.accompanist.systemuicontroller.rememberSystemUiController

/**
 * =========================================================================
 * PENGAWAS USER MANAGEMENT SCREEN
 * =========================================================================
 *
 * Fitur:
 * - Melihat daftar semua user (Admin Lapangan, Pimpinan & Koordinator)
 * - Reset password user yang dipilih
 * - Tambah user baru (Admin Lapangan, Pimpinan, Koordinator)
 * - Hapus user dari sistem
 * - Hanya bisa diakses oleh Pengawas
 *
 * Catatan:
 * - Pengawas tidak bisa mengubah/menghapus sesama Pengawas
 * - Password baru minimal 6 karakter
 * - Semua aktivitas di-log ke Firebase
 * =========================================================================
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PengawasUserManagementScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val coroutineScope = rememberCoroutineScope()

    // =========================================================================
    // ✅ DARK MODE SUPPORT
    // =========================================================================
    val isDark by viewModel.isDarkMode
    val systemUiController = rememberSystemUiController()
    val bgColor = PengawasColors.getBackground(isDark)
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // State
    val users by viewModel.allUsers.collectAsState()
    val isLoading by viewModel.usersLoading.collectAsState()
    val errorMessage by viewModel.usersError.collectAsState()

    // State untuk dialog
    var showResetDialog by remember { mutableStateOf(false) }
    var selectedUser by remember { mutableStateOf<UserInfo?>(null) }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }
    var resetResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // State untuk dialog tambah user
    var showCreateDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var createEmail by remember { mutableStateOf("") }
    var createPassword by remember { mutableStateOf("") }
    var createRole by remember { mutableStateOf("admin") }
    var createCabangId by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var createResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // State untuk dialog hapus user
    var showDeleteDialog by remember { mutableStateOf(false) }
    var userToDelete by remember { mutableStateOf<UserInfo?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // Cabang list
    val cabangList by viewModel.allCabang.collectAsState()

    // Filter state
    var selectedFilter by remember { mutableStateOf("Semua") }
    val filterOptions = listOf("Semua", "Pimpinan", "Koordinator", "Admin Lapangan")

    // Load data saat pertama kali
    LaunchedEffect(Unit) {
        viewModel.loadAllUsers()
        viewModel.loadAllCabang()
    }

    // Filtered users
    val filteredUsers = remember(users, selectedFilter) {
        when (selectedFilter) {
            "Pimpinan" -> users.filter { it.role == "pimpinan" }
            "Koordinator" -> users.filter { it.role == "koordinator" }
            "Admin Lapangan" -> users.filter { it.role == "admin" }
            else -> users
        }
    }

    Scaffold(
        containerColor = PengawasColors.getBackground(isDark),
        topBar = {
            PengawasTopBar(
                title = "Kelola User",
                navController = navController,
                viewModel = viewModel,
                showNotifications = false,
                onRefresh = {
                    coroutineScope.launch {
                        viewModel.loadAllUsers()
                        viewModel.loadAllCabang()
                    }
                }
            )
        },
        bottomBar = {
            PengawasBottomNavigation(navController, "pengawas_user_management", isDark = isDark)
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    createName = ""
                    createEmail = ""
                    createPassword = ""
                    createRole = "admin"
                    createCabangId = ""
                    createResult = null
                    showCreateDialog = true
                },
                containerColor = PengawasColors.primary,
                contentColor = Color.White,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(Icons.Rounded.PersonAdd, contentDescription = "Tambah User")
            }
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Header Info Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = PengawasColors.getCard(isDark)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(PengawasColors.primaryGradient)
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ManageAccounts,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Manajemen User",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Kelola akun PDL, Pimpinan & Koordinator",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    }

                    // User count badge
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "${users.size} User",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filterOptions) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PengawasColors.primary,
                            selectedLabelColor = Color.White,
                            containerColor = PengawasColors.getCard(isDark)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = PengawasColors.getBorder(isDark),
                            selectedBorderColor = PengawasColors.primary,
                            enabled = true,
                            selected = selectedFilter == filter
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content
            when {
                isLoading && users.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = PengawasColors.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Memuat daftar user...",
                                color = PengawasColors.getTextSecondary(isDark)
                            )
                        }
                    }
                }

                errorMessage != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = PengawasColors.danger,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = errorMessage ?: "Terjadi kesalahan",
                                color = PengawasColors.getTextPrimary(isDark),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.loadAllUsers() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PengawasColors.primary
                                )
                            ) {
                                Text("Coba Lagi")
                            }
                        }
                    }
                }

                filteredUsers.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PersonOff,
                                contentDescription = null,
                                tint = PengawasColors.getTextMuted(isDark),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Tidak ada user ditemukan",
                                color = PengawasColors.getTextSecondary(isDark)
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = filteredUsers,
                            key = { it.uid }
                        ) { user ->
                            UserCard(
                                user = user,
                                onResetClick = {
                                    selectedUser = user
                                    newPassword = ""
                                    confirmPassword = ""
                                    showPassword = false
                                    resetResult = null
                                    showResetDialog = true
                                },
                                onDeleteClick = {
                                    userToDelete = user
                                    deleteResult = null
                                    showDeleteDialog = true
                                },
                                isDark = isDark
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }

    // Reset Password Dialog
    if (showResetDialog && selectedUser != null) {
        Dialog(onDismissRequest = {
            if (!isResetting) {
                showResetDialog = false
            }
        }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reset Password",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = PengawasColors.getTextPrimary(isDark)
                        )

                        if (!isResetting) {
                            IconButton(onClick = { showResetDialog = false }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Tutup",
                                    tint = PengawasColors.getTextMuted(isDark)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // User info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = PengawasColors.getBackground(isDark)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selectedUser?.role == "pimpinan")
                                            PengawasColors.success.copy(alpha = 0.2f)
                                        else
                                            PengawasColors.info.copy(alpha = 0.2f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (selectedUser?.role == "pimpinan")
                                        Icons.Rounded.SupervisorAccount
                                    else
                                        Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = if (selectedUser?.role == "pimpinan")
                                        PengawasColors.success
                                    else
                                        PengawasColors.info,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedUser?.name ?: "",
                                    fontWeight = FontWeight.SemiBold,
                                    color = PengawasColors.getTextPrimary(isDark)
                                )
                                Text(
                                    text = selectedUser?.email ?: "",
                                    fontSize = 13.sp,
                                    color = PengawasColors.getTextSecondary(isDark)
                                )
                                if (selectedUser?.cabangName?.isNotBlank() == true) {
                                    Text(
                                        text = "Cabang: ${selectedUser?.cabangName}",
                                        fontSize = 12.sp,
                                        color = PengawasColors.getTextMuted(isDark)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Password input
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("Password Baru") },
                        placeholder = { Text("Minimal 6 karakter") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isResetting,
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (showPassword) "Sembunyikan" else "Tampilkan",
                                    tint = PengawasColors.getTextMuted(isDark)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PengawasColors.primary,
                            focusedLabelColor = PengawasColors.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Confirm password
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Konfirmasi Password") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isResetting,
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = confirmPassword.isNotEmpty() && confirmPassword != newPassword,
                        supportingText = {
                            if (confirmPassword.isNotEmpty() && confirmPassword != newPassword) {
                                Text(
                                    text = "Password tidak sama",
                                    color = PengawasColors.danger
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PengawasColors.primary,
                            focusedLabelColor = PengawasColors.primary,
                            errorBorderColor = PengawasColors.danger
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Result message
                    AnimatedVisibility(visible = resetResult != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (resetResult?.first == true)
                                    PengawasColors.success.copy(alpha = 0.1f)
                                else
                                    PengawasColors.danger.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (resetResult?.first == true)
                                        Icons.Rounded.CheckCircle
                                    else
                                        Icons.Rounded.Error,
                                    contentDescription = null,
                                    tint = if (resetResult?.first == true)
                                        PengawasColors.success
                                    else
                                        PengawasColors.danger,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = resetResult?.second ?: "",
                                    color = if (resetResult?.first == true)
                                        PengawasColors.success
                                    else
                                        PengawasColors.danger,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showResetDialog = false },
                            modifier = Modifier.weight(1f),
                            enabled = !isResetting,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = PengawasColors.getTextSecondary(isDark)
                            ),
                            border = BorderStroke(1.dp, PengawasColors.getBorder(isDark)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Batal")
                        }

                        Button(
                            onClick = {
                                if (newPassword.length < 6) {
                                    resetResult = Pair(false, "Password minimal 6 karakter")
                                    return@Button
                                }
                                if (newPassword != confirmPassword) {
                                    resetResult = Pair(false, "Password tidak sama")
                                    return@Button
                                }

                                isResetting = true
                                resetResult = null

                                viewModel.resetUserPassword(
                                    targetEmail = selectedUser?.email ?: "",
                                    newPassword = newPassword,
                                    onSuccess = { message ->
                                        isResetting = false
                                        resetResult = Pair(true, message)
                                        newPassword = ""
                                        confirmPassword = ""
                                    },
                                    onFailure = { error ->
                                        isResetting = false
                                        resetResult = Pair(false, error)
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isResetting &&
                                    newPassword.isNotBlank() &&
                                    confirmPassword.isNotBlank() &&
                                    newPassword == confirmPassword &&
                                    newPassword.length >= 6,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PengawasColors.primary,
                                disabledContainerColor = PengawasColors.primary.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isResetting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.LockReset,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reset")
                            }
                        }
                    }

                    // Info text
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Password baru akan langsung aktif. User dapat login dengan password baru.",
                        fontSize = 12.sp,
                        color = PengawasColors.getTextMuted(isDark),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // =========================================================================
    // CREATE USER DIALOG
    // =========================================================================
    if (showCreateDialog) {
        Dialog(onDismissRequest = { if (!isCreating) showCreateDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tambah User Baru",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = PengawasColors.getTextPrimary(isDark)
                        )
                        if (!isCreating) {
                            IconButton(onClick = { showCreateDialog = false }) {
                                Icon(Icons.Rounded.Close, "Tutup", tint = PengawasColors.getTextMuted(isDark))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Nama
                    OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it },
                        label = { Text("Nama Lengkap") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PengawasColors.primary,
                            focusedLabelColor = PengawasColors.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Email
                    OutlinedTextField(
                        value = createEmail,
                        onValueChange = { createEmail = it },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PengawasColors.primary,
                            focusedLabelColor = PengawasColors.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Password
                    OutlinedTextField(
                        value = createPassword,
                        onValueChange = { createPassword = it },
                        label = { Text("Password") },
                        placeholder = { Text("Minimal 6 karakter") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PengawasColors.primary,
                            focusedLabelColor = PengawasColors.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Role selection
                    Text(
                        text = "Role",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = PengawasColors.getTextPrimary(isDark)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "admin" to "PDL",
                            "pimpinan" to "Pimpinan",
                            "koordinator" to "Koordinator"
                        ).forEach { (roleValue, roleLabel) ->
                            FilterChip(
                                selected = createRole == roleValue,
                                onClick = {
                                    createRole = roleValue
                                    createCabangId = ""
                                },
                                label = { Text(roleLabel, fontSize = 13.sp) },
                                enabled = !isCreating,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PengawasColors.primary,
                                    selectedLabelColor = Color.White,
                                    containerColor = PengawasColors.getBackground(isDark)
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = PengawasColors.getBorder(isDark),
                                    selectedBorderColor = PengawasColors.primary,
                                    enabled = true,
                                    selected = createRole == roleValue
                                )
                            )
                        }
                    }

                    // Cabang selection (for admin & pimpinan)
                    if (createRole == "admin" || createRole == "pimpinan") {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Cabang",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = PengawasColors.getTextPrimary(isDark)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val availableCabang = if (createRole == "pimpinan") {
                            cabangList.filter { it.pimpinanUid.isBlank() }
                        } else {
                            cabangList
                        }

                        if (availableCabang.isEmpty()) {
                            Text(
                                text = if (createRole == "pimpinan") "Semua cabang sudah memiliki Pimpinan"
                                       else "Tidak ada cabang tersedia",
                                fontSize = 13.sp,
                                color = PengawasColors.warning,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            availableCabang.forEach { cabang ->
                                FilterChip(
                                    selected = createCabangId == cabang.id,
                                    onClick = { createCabangId = cabang.id },
                                    label = { Text(cabang.name, fontSize = 13.sp) },
                                    enabled = !isCreating,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = PengawasColors.info,
                                        selectedLabelColor = Color.White,
                                        containerColor = PengawasColors.getBackground(isDark)
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = PengawasColors.getBorder(isDark),
                                        selectedBorderColor = PengawasColors.info,
                                        enabled = true,
                                        selected = createCabangId == cabang.id
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Result message
                    AnimatedVisibility(visible = createResult != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (createResult?.first == true)
                                    PengawasColors.success.copy(alpha = 0.1f)
                                else
                                    PengawasColors.danger.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (createResult?.first == true) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                                    contentDescription = null,
                                    tint = if (createResult?.first == true) PengawasColors.success else PengawasColors.danger,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = createResult?.second ?: "",
                                    color = if (createResult?.first == true) PengawasColors.success else PengawasColors.danger,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showCreateDialog = false },
                            modifier = Modifier.weight(1f),
                            enabled = !isCreating,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PengawasColors.getTextSecondary(isDark)),
                            border = BorderStroke(1.dp, PengawasColors.getBorder(isDark)),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Batal") }

                        val canCreate = createName.isNotBlank() &&
                                createEmail.contains("@") &&
                                createPassword.length >= 6 &&
                                (createRole == "koordinator" || createCabangId.isNotBlank())

                        Button(
                            onClick = {
                                isCreating = true
                                createResult = null

                                viewModel.createNewUser(
                                    email = createEmail,
                                    password = createPassword,
                                    name = createName,
                                    role = createRole,
                                    cabangId = createCabangId,
                                    onSuccess = { message ->
                                        isCreating = false
                                        createResult = Pair(true, message)
                                        createName = ""
                                        createEmail = ""
                                        createPassword = ""
                                    },
                                    onFailure = { error ->
                                        isCreating = false
                                        createResult = Pair(false, error)
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isCreating && canCreate,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PengawasColors.primary,
                                disabledContainerColor = PengawasColors.primary.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isCreating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.PersonAdd, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Buat")
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // DELETE USER DIALOG
    // =========================================================================
    if (showDeleteDialog && userToDelete != null) {
        Dialog(onDismissRequest = { if (!isDeleting) showDeleteDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // Warning icon
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(PengawasColors.danger.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.DeleteForever,
                                contentDescription = null,
                                tint = PengawasColors.danger,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Hapus Akun?",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = PengawasColors.getTextPrimary(isDark),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val roleDisplay = when (userToDelete?.role) {
                        "pimpinan" -> "Pimpinan"
                        "koordinator" -> "Koordinator"
                        else -> "Admin Lapangan"
                    }

                    Text(
                        text = "Akun $roleDisplay \"${userToDelete?.name}\" (${userToDelete?.email}) akan dihapus permanen dari sistem.",
                        fontSize = 14.sp,
                        color = PengawasColors.getTextSecondary(isDark),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (userToDelete?.cabangName?.isNotBlank() == true) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Cabang: ${userToDelete?.cabangName}",
                            fontSize = 13.sp,
                            color = PengawasColors.getTextMuted(isDark),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Result message
                    AnimatedVisibility(visible = deleteResult != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (deleteResult?.first == true)
                                    PengawasColors.success.copy(alpha = 0.1f)
                                else
                                    PengawasColors.danger.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (deleteResult?.first == true) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                                    contentDescription = null,
                                    tint = if (deleteResult?.first == true) PengawasColors.success else PengawasColors.danger,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = deleteResult?.second ?: "",
                                    color = if (deleteResult?.first == true) PengawasColors.success else PengawasColors.danger,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showDeleteDialog = false },
                            modifier = Modifier.weight(1f),
                            enabled = !isDeleting,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PengawasColors.getTextSecondary(isDark)),
                            border = BorderStroke(1.dp, PengawasColors.getBorder(isDark)),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Batal") }

                        Button(
                            onClick = {
                                isDeleting = true
                                deleteResult = null

                                viewModel.deleteExistingUser(
                                    targetUid = userToDelete?.uid ?: "",
                                    onSuccess = { message ->
                                        isDeleting = false
                                        deleteResult = Pair(true, message)
                                    },
                                    onFailure = { error ->
                                        isDeleting = false
                                        deleteResult = Pair(false, error)
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isDeleting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PengawasColors.danger,
                                disabledContainerColor = PengawasColors.danger.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isDeleting) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.DeleteForever, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Hapus")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * User Card Component
 */
@Composable
private fun UserCard(
    user: UserInfo,
    onResetClick: () -> Unit,
    onDeleteClick: () -> Unit,
    isDark: Boolean = false
) {
    val isPimpinan = user.role == "pimpinan"
    val isKoordinator = user.role == "koordinator"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.05f)
            ),
        colors = CardDefaults.cardColors(containerColor = PengawasColors.getCard(isDark)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.horizontalGradient(
                            when {
                                isPimpinan -> PengawasColors.successGradient
                                isKoordinator -> listOf(Color(0xFF7C3AED), Color(0xFF9333EA)) // Ungu untuk Koordinator
                                else -> PengawasColors.infoGradient
                            }
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isPimpinan -> Icons.Rounded.SupervisorAccount
                        isKoordinator -> Icons.Rounded.AdminPanelSettings
                        else -> Icons.Rounded.Person
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = user.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = PengawasColors.getTextPrimary(isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Role badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when {
                            isPimpinan -> PengawasColors.success.copy(alpha = 0.1f)
                            isKoordinator -> Color(0xFF7C3AED).copy(alpha = 0.1f)
                            else -> PengawasColors.info.copy(alpha = 0.1f)
                        }
                    ) {
                        Text(
                            text = when {
                                isPimpinan -> "Pimpinan"
                                isKoordinator -> "Koordinator"
                                else -> "PDL"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = when {
                                isPimpinan -> PengawasColors.success
                                isKoordinator -> Color(0xFF7C3AED)
                                else -> PengawasColors.info
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = user.email,
                    fontSize = 13.sp,
                    color = PengawasColors.getTextSecondary(isDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (user.cabangName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Business,
                            contentDescription = null,
                            tint = PengawasColors.getTextMuted(isDark),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = user.cabangName,
                            fontSize = 12.sp,
                            color = PengawasColors.getTextMuted(isDark)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Reset password button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(PengawasColors.primary.copy(alpha = 0.1f))
                        .clickable { onResetClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LockReset,
                        contentDescription = "Reset Password",
                        tint = PengawasColors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Delete button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(PengawasColors.danger.copy(alpha = 0.1f))
                        .clickable { onDeleteClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PersonRemove,
                        contentDescription = "Hapus User",
                        tint = PengawasColors.danger,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
