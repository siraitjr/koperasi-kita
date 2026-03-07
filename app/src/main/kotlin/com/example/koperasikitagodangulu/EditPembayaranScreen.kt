package com.example.koperasikitagodangulu

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.formatRupiah

// Modern Color Palette
private object EditPaymentColors {
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
    val info = Color(0xFF3B82F6)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPembayaranScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    pelangganId: String,
    index: Int
) {
    val context = LocalContext.current

    val isDark by viewModel.isDarkMode
    val bgColor = if (isDark) EditPaymentColors.darkBackground else EditPaymentColors.lightBackground
    val cardColor = if (isDark) EditPaymentColors.darkCard else EditPaymentColors.lightSurface
    val borderColor = if (isDark) EditPaymentColors.darkBorder else EditPaymentColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Cari pelanggan berdasarkan ID
    val pelanggan = viewModel.daftarPelanggan.find { it.id == pelangganId }

    if (pelanggan == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            ModernEmptyStatePayment(
                icon = Icons.Rounded.PersonOff,
                title = "Data Tidak Ditemukan",
                message = "Data nasabah tidak ditemukan",
                color = EditPaymentColors.warning
            )
        }
        return
    }

    // Validasi index
    if (index < 0 || index >= pelanggan.pembayaranList.size) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            ModernEmptyStatePayment(
                icon = Icons.Rounded.ErrorOutline,
                title = "Pembayaran Tidak Ditemukan",
                message = "Data pembayaran tidak ditemukan",
                color = EditPaymentColors.danger
            )
        }
        return
    }

    val pembayaran = pelanggan.pembayaranList[index]

    // Fungsi untuk format ribuan dengan titik
    fun formatRibuan(input: String): String {
        return try {
            val cleanString = input.replace("[^\\d]".toRegex(), "")
            if (cleanString.isBlank()) {
                ""
            } else {
                val number = cleanString.toLong()
                val formatter = DecimalFormat("#,###")
                formatter.format(number)
            }
        } catch (e: Exception) {
            input
        }
    }

    // Fungsi untuk parse string berformat ke integer
    fun parseFormattedNumber(formatted: String): Int {
        return try {
            val cleanString = formatted.replace("[^\\d]".toRegex(), "")
            cleanString.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    // State untuk form edit
    var jumlahPembayaran by remember {
        mutableStateOf(formatRibuan(pembayaran.jumlah.toString()))
    }
    var tanggalPembayaran by remember { mutableStateOf(pembayaran.tanggal) }
    var showDatePicker by remember { mutableStateOf(false) }

    val jumlahNumerik = remember(jumlahPembayaran) {
        parseFormattedNumber(jumlahPembayaran)
    }

    val isAdaPerubahan = remember(
        jumlahNumerik,
        tanggalPembayaran,
        pembayaran.jumlah,
        pembayaran.tanggal
    ) {
        jumlahNumerik != pembayaran.jumlah || tanggalPembayaran != pembayaran.tanggal
    }

    // Setup Calendar & DatePickerDialog
    val calendar = remember { Calendar.getInstance() }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")) }

    LaunchedEffect(pembayaran.tanggal) {
        try {
            val parsed = dateFormat.parse(pembayaran.tanggal)
            if (parsed != null) {
                calendar.time = parsed
            }
        } catch (_: Exception) { }
    }

    val datePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            calendar.set(year, month, dayOfMonth)
            tanggalPembayaran = dateFormat.format(calendar.time)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    Scaffold(
        containerColor = bgColor,
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isDark) EditPaymentColors.darkSurface else Color.White,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Kembali",
                            tint = txtColor
                        )
                    }

                    Text(
                        text = "Edit Pembayaran",
                        modifier = Modifier.weight(1f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = txtColor
                    )

                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Informasi Pelanggan Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -30 },
                    animationSpec = tween(400)
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(24.dp),
                            ambientColor = EditPaymentColors.primary.copy(alpha = 0.2f)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.linearGradient(EditPaymentColors.primaryGradient))
                    ) {
                        // Decorative circles
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .offset(x = (-20).dp, y = (-20).dp)
                                .background(Color.White.copy(alpha = 0.1f), CircleShape)
                        )

                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = pelanggan.namaPanggilan.take(2).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }
                                Column {
                                    Text(
                                        text = pelanggan.namaPanggilan,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = pelanggan.noHp,
                                        fontSize = 14.sp,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = pelanggan.alamatRumah,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Form Edit Pembayaran
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
                            ambientColor = EditPaymentColors.primary.copy(alpha = 0.1f)
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
                                    .background(EditPaymentColors.info.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = null,
                                    tint = EditPaymentColors.info,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                text = "Edit Data Pembayaran",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = txtColor
                            )
                        }

                        // Input Jumlah Pembayaran
                        OutlinedTextField(
                            value = jumlahPembayaran,
                            onValueChange = { newValue ->
                                val formatted = formatRibuan(newValue)
                                jumlahPembayaran = formatted
                            },
                            label = { Text("Jumlah Pembayaran", color = subtitleColor) },
                            placeholder = { Text("Contoh: 10.000", color = subtitleColor.copy(alpha = 0.5f)) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(EditPaymentColors.warning.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Payments,
                                        contentDescription = null,
                                        tint = EditPaymentColors.warning,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = cardColor,
                                unfocusedContainerColor = cardColor,
                                focusedBorderColor = EditPaymentColors.primary,
                                unfocusedBorderColor = borderColor,
                                cursorColor = EditPaymentColors.primary,
                                focusedTextColor = txtColor,
                                unfocusedTextColor = txtColor,
                                focusedLabelColor = EditPaymentColors.primary,
                                unfocusedLabelColor = subtitleColor
                            )
                        )

                        // Info nilai sebelumnya
                        Text(
                            text = "Nilai sebelumnya: Rp ${formatRupiah(pembayaran.jumlah)}",
                            fontSize = 12.sp,
                            color = subtitleColor,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Input Tanggal Pembayaran
                        OutlinedTextField(
                            value = tanggalPembayaran,
                            onValueChange = { },
                            label = { Text("Tanggal Pembayaran", color = subtitleColor) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(EditPaymentColors.info.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CalendarToday,
                                        contentDescription = null,
                                        tint = EditPaymentColors.info,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            trailingIcon = {
                                IconButton(onClick = { datePickerDialog.show() }) {
                                    Icon(
                                        imageVector = Icons.Rounded.DateRange,
                                        contentDescription = "Pilih Tanggal",
                                        tint = EditPaymentColors.primary
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { datePickerDialog.show() },
                            shape = RoundedCornerShape(14.dp),
                            singleLine = true,
                            readOnly = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = cardColor,
                                unfocusedContainerColor = cardColor,
                                focusedBorderColor = EditPaymentColors.primary,
                                unfocusedBorderColor = borderColor,
                                cursorColor = EditPaymentColors.primary,
                                focusedTextColor = txtColor,
                                unfocusedTextColor = txtColor,
                                focusedLabelColor = EditPaymentColors.primary,
                                unfocusedLabelColor = subtitleColor
                            )
                        )

                        // Preview Perubahan
                        AnimatedVisibility(
                            visible = isAdaPerubahan,
                            enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                            exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = EditPaymentColors.success.copy(alpha = 0.1f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Visibility,
                                            contentDescription = null,
                                            tint = EditPaymentColors.success,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Preview Perubahan",
                                            fontWeight = FontWeight.Bold,
                                            color = EditPaymentColors.success,
                                            fontSize = 14.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Sebelum:", color = txtColor, fontSize = 13.sp)
                                        Text(
                                            "Rp ${formatRupiah(pembayaran.jumlah)}",
                                            color = subtitleColor,
                                            fontSize = 13.sp
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Sesudah:", color = txtColor, fontSize = 13.sp)
                                        Text(
                                            "Rp ${formatRupiah(jumlahNumerik)}",
                                            color = EditPaymentColors.success,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (tanggalPembayaran != pembayaran.tanggal) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Tanggal baru:", color = txtColor, fontSize = 13.sp)
                                            Text(
                                                tanggalPembayaran,
                                                color = EditPaymentColors.info,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Tombol Simpan
                        Button(
                            onClick = {
                                if (jumlahNumerik <= 0) {
                                    Toast.makeText(context, "Jumlah pembayaran tidak valid", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                if (tanggalPembayaran.isEmpty()) {
                                    Toast.makeText(context, "Tanggal pembayaran harus diisi", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                viewModel.editPembayaran(pelangganId, index, jumlahNumerik, tanggalPembayaran)

                                Toast.makeText(context, "Perubahan berhasil disimpan", Toast.LENGTH_SHORT).show()
                                navController.popBackStack()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                disabledContainerColor = borderColor
                            ),
                            contentPadding = PaddingValues(),
                            enabled = isAdaPerubahan
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        if (isAdaPerubahan)
                                            Brush.linearGradient(EditPaymentColors.successGradient)
                                        else
                                            Brush.linearGradient(listOf(borderColor, borderColor))
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = null,
                                        tint = if (isAdaPerubahan) Color.White else subtitleColor
                                    )
                                    Text(
                                        text = if (isAdaPerubahan) "Simpan Perubahan" else "Tidak Ada Perubahan",
                                        color = if (isAdaPerubahan) Color.White else subtitleColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tombol Batal
            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = subtitleColor
                )
            ) {
                Text(
                    text = "Batal",
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Warning Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 200)
                )
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = EditPaymentColors.danger.copy(alpha = 0.1f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = EditPaymentColors.danger,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Perhatian",
                                fontWeight = FontWeight.Bold,
                                color = EditPaymentColors.danger,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Edit Pembayaran digunakan untuk koreksi cicilan yang salah.",
                            color = EditPaymentColors.danger.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Untuk cicilan tambahan, gunakan fitur 'Tambah Pembayaran'.",
                            color = EditPaymentColors.danger.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernEmptyStatePayment(
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