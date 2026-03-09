package com.example.koperasikitagodangulu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.koperasikitagodangulu.utils.formatRupiah
import android.widget.Toast
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import android.app.DatePickerDialog
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.foundation.clickable

// Modern Color Palette
private object EditCustomerColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPelangganScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    pelangganId: String?
) {
    val context = LocalContext.current
    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) EditCustomerColors.darkCard else EditCustomerColors.lightSurface
    val borderColor = if (isDark) EditCustomerColors.darkBorder else EditCustomerColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) EditCustomerColors.darkBackground else EditCustomerColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Cari pelanggan berdasarkan ID
    val pelanggan = viewModel.daftarPelanggan.find { it.id == pelangganId }

    // State untuk form editing
    var namaKtp by remember { mutableStateOf(pelanggan?.namaKtp ?: "") }
    var nik by remember { mutableStateOf(pelanggan?.nik ?: "") }
    var namaPanggilan by remember { mutableStateOf(pelanggan?.namaPanggilan ?: "") }
    var nomorAnggota by remember { mutableStateOf(pelanggan?.nomorAnggota ?: "") }
    var alamatKtp by remember { mutableStateOf(pelanggan?.alamatKtp ?: "") }
    var alamatRumah by remember { mutableStateOf(pelanggan?.alamatRumah ?: "") }
    var detailRumah by remember { mutableStateOf(pelanggan?.detailRumah ?: "") }
    var wilayah by remember { mutableStateOf(pelanggan?.wilayah ?: "") }
    var noHp by remember { mutableStateOf(pelanggan?.noHp ?: "") }
    var jenisUsaha by remember { mutableStateOf(pelanggan?.jenisUsaha ?: "") }
    var isLoading by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernEditTopBar(
                title = "Edit Nasabah",
                isDark = isDark,
                txtColor = txtColor,
                onBackClick = { navController.popBackStack() }
            )
        },
        bottomBar = {
            ModernEditBottomBar(
                isLoading = isLoading,
                onCancel = { navController.popBackStack() },
                onSave = {
                    // Validasi input
                    if (namaKtp.isBlank() || nik.isBlank()) {
                        showError = true
                        errorMessage = "Nama KTP dan NIK wajib diisi"
                        return@ModernEditBottomBar
                    }

                    if (nomorAnggota.length != 6) {
                        showError = true
                        errorMessage = "Nomor Anggota harus 6 digit"
                        return@ModernEditBottomBar
                    }

                    if (!viewModel.validatePelangganDataEdit(namaKtp, nik, nomorAnggota)) {
                        showError = true
                        errorMessage = "Data tidak valid"
                        return@ModernEditBottomBar
                    }

                    isLoading = true
                    viewModel.updatePelangganDataEdit(
                        pelangganId = pelangganId!!,
                        namaKtp = namaKtp,
                        nik = nik,
                        namaPanggilan = namaPanggilan,
                        nomorAnggota = nomorAnggota,
                        alamatKtp = alamatKtp,
                        alamatRumah = alamatRumah,
                        detailRumah = detailRumah,
                        wilayah = wilayah,
                        noHp = noHp,
                        jenisUsaha = jenisUsaha,
                        onSuccess = {
                            isLoading = false
                            Toast.makeText(context, "Nasabah berhasil diupdate", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        },
                        onFailure = { e ->
                            isLoading = false
                            showError = true
                            errorMessage = e.message ?: "Gagal mengupdate nasabah"
                        }
                    )
                },
                isDark = isDark
            )
        }
    ) { innerPadding ->
        if (pelanggan == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                ModernEmptyState(
                    icon = Icons.Rounded.PersonOff,
                    title = "Nasabah Tidak Ditemukan",
                    message = "Data nasabah tidak tersedia",
                    color = EditCustomerColors.warning
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Info Pinjaman Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -30 },
                    animationSpec = tween(400)
                )
            ) {
                val totalBayar = pelanggan.pembayaranList.sumOf { it.jumlah }
                val sisa = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)

                ModernInfoCard(
                    title = "Informasi Pinjaman",
                    icon = Icons.Rounded.AccountBalance,
                    gradient = EditCustomerColors.infoGradient,
                    isDark = isDark,
                    cardColor = cardColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                ) {
                    ModernInfoRowEdit("Status", pelanggan.status, subtitleColor)
                    ModernInfoRowEdit("Total Dibayar", "Rp ${formatRupiah(totalBayar)}", subtitleColor)
                    ModernInfoRowEdit("Sisa Hutang", "Rp ${formatRupiah(sisa)}", if (sisa > 0) EditCustomerColors.warning else EditCustomerColors.success)
                    ModernInfoRowEdit("Pinjaman Ke", pelanggan.pinjamanKe.toString(), subtitleColor)
                    ModernInfoRowEdit("Besar Pinjaman", "Rp ${formatRupiah(pelanggan.besarPinjaman)}", subtitleColor)
                    ModernInfoRowEdit("Tenor", "${pelanggan.tenor} hari", subtitleColor)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Data Pribadi Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = EditCustomerColors.primary.copy(alpha = 0.1f)
                        ),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(EditCustomerColors.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = EditCustomerColors.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                "Data Pribadi",
                                color = txtColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        // Form Fields
                        ModernTextField(
                            value = namaKtp,
                            onValueChange = { namaKtp = it },
                            label = "Nama KTP",
                            icon = Icons.Rounded.Badge,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ModernTextField(
                            value = nik,
                            onValueChange = { nik = it.filter { char -> char.isDigit() } },
                            label = "NIK",
                            icon = Icons.Rounded.CreditCard,
                            keyboardType = KeyboardType.Number,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ModernTextField(
                            value = nomorAnggota,
                            onValueChange = { nomorAnggota = it.filter { char -> char.isDigit() }.take(6) },
                            label = "Nomor Anggota (6 digit)",
                            icon = Icons.Rounded.Numbers,
                            keyboardType = KeyboardType.Number,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ModernTextField(
                            value = namaPanggilan,
                            onValueChange = { namaPanggilan = it },
                            label = "Nama Panggilan",
                            icon = Icons.Rounded.Face,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Alamat & Kontak Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 200)
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = EditCustomerColors.primary.copy(alpha = 0.1f)
                        ),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(EditCustomerColors.success.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LocationOn,
                                    contentDescription = null,
                                    tint = EditCustomerColors.success,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                "Alamat & Kontak",
                                color = txtColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }

                        ModernTextField(
                            value = alamatKtp,
                            onValueChange = { alamatKtp = it },
                            label = "Alamat KTP",
                            icon = Icons.Rounded.Home,
                            singleLine = false,
                            maxLines = 3,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ModernTextField(
                            value = alamatRumah,
                            onValueChange = { alamatRumah = it },
                            label = "Alamat Rumah",
                            icon = Icons.Rounded.House,
                            singleLine = false,
                            maxLines = 3,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ModernTextField(
                            value = detailRumah,
                            onValueChange = { detailRumah = it },
                            label = "Detail Rumah",
                            icon = Icons.Rounded.Info,
                            singleLine = false,
                            maxLines = 3,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ModernTextField(
                            value = wilayah,
                            onValueChange = { wilayah = it },
                            label = "Wilayah",
                            icon = Icons.Rounded.Map,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ModernTextField(
                            value = noHp,
                            onValueChange = { noHp = it.filter { char -> char.isDigit() } },
                            label = "No HP",
                            icon = Icons.Rounded.Phone,
                            keyboardType = KeyboardType.Phone,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ModernTextField(
                            value = jenisUsaha,
                            onValueChange = { jenisUsaha = it },
                            label = "Jenis Usaha",
                            icon = Icons.Rounded.Work,
                            isDark = isDark,
                            cardColor = cardColor,
                            borderColor = borderColor,
                            txtColor = txtColor,
                            subtitleColor = subtitleColor
                        )
                    }
                }
            }

            // Error message
            AnimatedVisibility(visible = showError) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = EditCustomerColors.danger.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Error,
                            contentDescription = null,
                            tint = EditCustomerColors.danger
                        )
                        Text(
                            text = errorMessage,
                            color = EditCustomerColors.danger,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
private fun ModernEditTopBar(
    title: String,
    isDark: Boolean,
    txtColor: Color,
    onBackClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) EditCustomerColors.darkSurface else Color.White,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Kembali",
                    tint = txtColor
                )
            }

            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor
            )

            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}

@Composable
private fun ModernEditBottomBar(
    isLoading: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    isDark: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) EditCustomerColors.darkSurface else Color.White,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isDark) Color.White else Color(0xFF64748B)
                )
            ) {
                Text("Batal", fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = onSave,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                contentPadding = PaddingValues(),
                enabled = !isLoading
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(EditCustomerColors.successGradient)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Save,
                                contentDescription = "Save",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Simpan",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernInfoCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradient: List<Color>,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = gradient.first().copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(gradient.first().copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = gradient.first(),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    title,
                    color = txtColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            content()
        }
    }
}

@Composable
private fun ModernInfoRowEdit(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label:", color = color.copy(alpha = 0.7f), fontSize = 14.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = subtitleColor) },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(EditCustomerColors.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = EditCustomerColors.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = cardColor,
            unfocusedContainerColor = cardColor,
            focusedBorderColor = EditCustomerColors.primary,
            unfocusedBorderColor = borderColor,
            cursorColor = EditCustomerColors.primary,
            focusedTextColor = txtColor,
            unfocusedTextColor = txtColor,
            focusedLabelColor = EditCustomerColors.primary,
            unfocusedLabelColor = subtitleColor
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ModernEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(color.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            fontSize = 14.sp,
            color = Color.Gray
        )
    }
}

@Composable
fun InfoRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$label:", color = color, fontSize = 14.sp)
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
    Spacer(modifier = Modifier.height(2.dp))
}