package com.example.koperasikitagodangulu

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.example.koperasikitagodangulu.utils.formatRupiahInput
import com.example.koperasikitagodangulu.utils.ThousandSeparatorTransformation
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// Modern Color Palette
private object PaymentColors {
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
fun InputPembayaranScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    pelangganId: String
) {
    val context = LocalContext.current
    val pelanggan = viewModel.daftarPelanggan.find { it.id == pelangganId }

    if (pelanggan == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PersonOff,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Data nasabah tidak ditemukan",
                    color = Color(0xFF64748B),
                    fontSize = 16.sp
                )
            }
        }
        return
    }

    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) PaymentColors.darkCard else PaymentColors.lightSurface
    val borderColor = if (isDark) PaymentColors.darkBorder else PaymentColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) PaymentColors.darkBackground else PaymentColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Form states
    var jumlahPembayaran by remember { mutableStateOf("") }
    val calendar = remember { Calendar.getInstance() }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")) }
    var tanggalPembayaran by remember { mutableStateOf(dateFormat.format(calendar.time)) }

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

    // Calculations
    val totalBayar = pelanggan.pembayaranList.sumOf {
        it.jumlah + it.subPembayaran.sumOf { sub -> sub.jumlah }
    }
    val sisa = (pelanggan.totalPelunasan - totalBayar).coerceAtLeast(0)
    val sudahLunas = sisa <= 0

    val jumlahCicilanReferensi = if (pelanggan.hasilSimulasiCicilan.isNotEmpty()) {
        pelanggan.hasilSimulasiCicilan.first().jumlah
    } else {
        if (pelanggan.tenor > 0) pelanggan.totalPelunasan / pelanggan.tenor else 0
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            PaymentTopBar(
                title = "Input Pembayaran",
                isDark = isDark,
                txtColor = txtColor
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Customer Info Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -30 },
                    animationSpec = tween(400)
                )
            ) {
                CustomerDetailCard(
                    pelanggan = pelanggan,
                    totalBayar = totalBayar,
                    sisa = sisa,
                    sudahLunas = sudahLunas,
                    jumlahCicilanReferensi = jumlahCicilanReferensi,
                    isDark = isDark,
                    cardColor = cardColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            // Amount Input Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                PaymentInputCard(
                    jumlahPembayaran = jumlahPembayaran,
                    onJumlahChange = { jumlahPembayaran = it.filter { c -> c.isDigit() } },
                    tanggalPembayaran = tanggalPembayaran,
                    onDateClick = { datePickerDialog.show() },
                    jumlahCicilanReferensi = jumlahCicilanReferensi,
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action Buttons
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 50 },
                    animationSpec = tween(400, delayMillis = 200)
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val jumlahInt = jumlahPembayaran.toIntOrNull() ?: 0
                    val minimalPembayaran = 5000
                    val jumlahValid = jumlahInt >= minimalPembayaran
                    val formValid = jumlahValid && tanggalPembayaran.isNotBlank() && !sudahLunas && jumlahInt <= sisa

                    PaymentGradientButton(
                        text = if (sudahLunas) "Sudah Lunas ✓" else "Simpan Pembayaran",
                        onClick = {
                            if (jumlahInt > jumlahCicilanReferensi && jumlahCicilanReferensi > 0) {
                                viewModel.tambahMultiplePembayaran(
                                    pelangganId,
                                    jumlahInt,
                                    tanggalPembayaran,
                                    jumlahCicilanReferensi
                                )
                            } else {
                                viewModel.tambahPembayaran(pelangganId, jumlahInt, tanggalPembayaran)
                            }

                            Toast.makeText(
                                context,
                                "Pembayaran berhasil disimpan.",
                                Toast.LENGTH_SHORT
                            ).show()
                            navController.popBackStack()
                        },
                        enabled = formValid,
                        gradient = if (sudahLunas) PaymentColors.tealGradient else PaymentColors.successGradient,
                        icon = if (sudahLunas) Icons.Rounded.CheckCircle else Icons.Rounded.Save
                    )

                    PaymentOutlinedButton(
                        text = "Batal",
                        onClick = { navController.popBackStack() },
                        isDark = isDark,
                        borderColor = borderColor,
                        txtColor = subtitleColor
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentTopBar(
    title: String,
    isDark: Boolean,
    txtColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) PaymentColors.darkSurface else Color.White,
        shadowElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor
            )
        }
    }
}

@Composable
private fun CustomerDetailCard(
    pelanggan: Pelanggan,
    totalBayar: Int,
    sisa: Int,
    sudahLunas: Boolean,
    jumlahCicilanReferensi: Int,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = PaymentColors.primary.copy(alpha = 0.15f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = if (sudahLunas) PaymentColors.successGradient else PaymentColors.primaryGradient
                    )
                )
        ) {
            // Decorative elements
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .offset(x = (-30).dp, y = (-30).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 20.dp, y = 20.dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                            .border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = pelanggan.namaKtp.take(2).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }

                    Column {
                        Text(
                            text = pelanggan.namaKtp,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = pelanggan.noHp,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Status Badge
                if (sudahLunas) {
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "LUNAS",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Info Grid
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PaymentInfoItem(
                        label = "Total Pelunasan",
                        value = "Rp ${formatRupiah(pelanggan.totalPelunasan)}"
                    )
                    PaymentInfoItem(
                        label = "Sudah Dibayar",
                        value = "Rp ${formatRupiah(totalBayar)}"
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PaymentInfoItem(
                        label = "Sisa Utang",
                        value = "Rp ${formatRupiah(sisa)}",
                        highlight = !sudahLunas
                    )
                    PaymentInfoItem(
                        label = "Cicilan Ref.",
                        value = "Rp ${formatRupiah(jumlahCicilanReferensi)}"
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentInfoItem(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Column {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = if (highlight) 16.sp else 14.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

@Composable
private fun PaymentInputCard(
    jumlahPembayaran: String,
    onJumlahChange: (String) -> Unit,
    tanggalPembayaran: String,
    onDateClick: () -> Unit,
    jumlahCicilanReferensi: Int,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    val jumlahInt = jumlahPembayaran.toIntOrNull() ?: 0
    val minimalPembayaran = 5000
    val jumlahValid = jumlahInt >= minimalPembayaran

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = PaymentColors.primary.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Detail Pembayaran",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor
            )

            // Amount Input
            OutlinedTextField(
                value = formatRupiahInput(jumlahPembayaran),
                onValueChange = onJumlahChange,
                label = { Text("Jumlah Pembayaran (Rp)") },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                Brush.linearGradient(PaymentColors.successGradient),
                                RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Payments,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                visualTransformation = ThousandSeparatorTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = if (isDark) PaymentColors.darkSurface else Color(0xFFF8FAFC),
                    unfocusedContainerColor = if (isDark) PaymentColors.darkSurface else Color(0xFFF8FAFC),
                    focusedBorderColor = PaymentColors.success,
                    unfocusedBorderColor = borderColor,
                    focusedLabelColor = PaymentColors.success,
                    unfocusedLabelColor = subtitleColor,
                    cursorColor = PaymentColors.success,
                    focusedTextColor = txtColor,
                    unfocusedTextColor = txtColor
                )
            )

            // Validation messages
            if (jumlahPembayaran.isNotBlank() && !jumlahValid) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PaymentColors.danger.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = PaymentColors.danger,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Minimal pembayaran Rp ${formatRupiah(minimalPembayaran)}",
                            color = PaymentColors.danger,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (jumlahValid && jumlahInt > jumlahCicilanReferensi && jumlahCicilanReferensi > 0) {
                val jumlahCicilan = (jumlahInt + jumlahCicilanReferensi - 1) / jumlahCicilanReferensi
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PaymentColors.info.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = PaymentColors.info,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Akan dibagi $jumlahCicilan cicilan @ Rp ${formatRupiah(jumlahCicilanReferensi)}",
                            color = PaymentColors.info,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Date Picker
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDateClick() },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) PaymentColors.darkSurface else Color(0xFFF8FAFC)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.linearGradient(listOf(borderColor, borderColor))
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    Brush.linearGradient(PaymentColors.infoGradient),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CalendarMonth,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Tanggal Pembayaran",
                                fontSize = 12.sp,
                                color = subtitleColor
                            )
                            Text(
                                text = tanggalPembayaran,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = txtColor
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Pilih Tanggal",
                        tint = PaymentColors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentGradientButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    gradient: List<Color>,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                elevation = if (enabled) 8.dp else 0.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = gradient.first().copy(alpha = 0.3f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color(0xFF94A3B8)
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (enabled) Brush.linearGradient(gradient)
                    else Brush.linearGradient(listOf(Color(0xFF94A3B8), Color(0xFFCBD5E1)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PaymentOutlinedButton(
    text: String,
    onClick: () -> Unit,
    isDark: Boolean,
    borderColor: Color,
    txtColor: Color
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.linearGradient(listOf(borderColor, borderColor))
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isDark) PaymentColors.darkSurface else Color.White
        )
    ) {
        Text(
            text = text,
            color = txtColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}