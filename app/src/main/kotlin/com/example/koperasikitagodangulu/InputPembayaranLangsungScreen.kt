package com.example.koperasikitagodangulu

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import com.example.koperasikitagodangulu.utils.ThousandSeparatorTransformation
import com.example.koperasikitagodangulu.utils.formatRupiahInput
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// Modern Color Palette
private object InputColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))

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
fun InputPembayaranLangsungScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    pelangganId: String = "0"
) {
    val context = LocalContext.current
    val pelangganList = viewModel.daftarPelanggan

    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) InputColors.darkCard else InputColors.lightSurface
    val borderColor = if (isDark) InputColors.darkBorder else InputColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) InputColors.darkBackground else InputColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Form states
    var selectedPelanggan by remember { mutableStateOf<Pelanggan?>(null) }
    var jumlahPembayaran by remember { mutableStateOf("") }
    val calendar = remember { Calendar.getInstance() }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")) }
    var tanggalPembayaran by remember { mutableStateOf(dateFormat.format(calendar.time)) }
    var queryNama by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

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

    val jumlahCicilanReferensi = remember(selectedPelanggan) {
        if (selectedPelanggan?.hasilSimulasiCicilan?.isNotEmpty() == true) {
            selectedPelanggan!!.hasilSimulasiCicilan.first().jumlah
        } else {
            selectedPelanggan?.let { pel ->
                if (pel.tenor > 0) pel.totalPelunasan / pel.tenor else 0
            } ?: 0
        }
    }

    val sisaHutang = remember(selectedPelanggan) {
        selectedPelanggan?.let { pel ->
            val totalDibayar = pel.pembayaranList.sumOf {
                it.jumlah + it.subPembayaran.sumOf { sub -> sub.jumlah }
            }
            (pel.totalPelunasan - totalDibayar).coerceAtLeast(0)
        } ?: 0
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            ModernInputTopBar(
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Selected Customer Card
            AnimatedVisibility(
                visible = selectedPelanggan != null && isVisible,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                selectedPelanggan?.let { pel ->
                    ModernCustomerInfoCard(
                        pelanggan = pel,
                        jumlahCicilanReferensi = jumlahCicilanReferensi,
                        isDark = isDark,
                        cardColor = cardColor,
                        txtColor = txtColor,
                        subtitleColor = subtitleColor
                    )
                }
            }

            // Search Customer Field
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(300, delayMillis = 100)
                )
            ) {
                ModernSearchField(
                    value = queryNama,
                    onValueChange = {
                        queryNama = it
                        expanded = it.isNotEmpty()
                    },
                    onExpandedChange = { expanded = it },
                    expanded = expanded,
                    pelangganList = pelangganList,
                    onPelangganSelected = { pelanggan ->
                        selectedPelanggan = pelanggan
                        queryNama = pelanggan.namaKtp
                        expanded = false
                    },
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            // Amount Input
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(300, delayMillis = 200)
                )
            ) {
                ModernAmountField(
                    value = jumlahPembayaran,
                    onValueChange = { jumlahPembayaran = it.filter { c -> c.isDigit() } },
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor
                )
            }

            // Validation Messages
            val jumlahInt = jumlahPembayaran.toIntOrNull() ?: 0
            val minimalPembayaran = 5000
            val jumlahValid = jumlahInt >= minimalPembayaran

            if (jumlahPembayaran.isNotBlank() && !jumlahValid) {
                ModernWarningChip(
                    text = "Minimal pembayaran Rp ${formatRupiah(minimalPembayaran)}",
                    gradient = InputColors.dangerGradient
                )
            }

            if (jumlahValid && jumlahInt > jumlahCicilanReferensi && jumlahCicilanReferensi > 0 && selectedPelanggan != null) {
                val jumlahCicilan = (jumlahInt + jumlahCicilanReferensi - 1) / jumlahCicilanReferensi
                ModernInfoChip(
                    text = "Pembayaran akan dibagi menjadi $jumlahCicilan cicilan @ Rp ${formatRupiah(jumlahCicilanReferensi)}",
                    gradient = InputColors.infoGradient
                )
            }

            // Date Picker
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300, delayMillis = 300)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(300, delayMillis = 300)
                )
            ) {
                ModernDateField(
                    tanggal = tanggalPembayaran,
                    onDateClick = { datePickerDialog.show() },
                    isDark = isDark,
                    cardColor = cardColor,
                    borderColor = borderColor,
                    txtColor = txtColor
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action Buttons
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300, delayMillis = 400)) + slideInVertically(
                    initialOffsetY = { 50 },
                    animationSpec = tween(300, delayMillis = 400)
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val pelangganIdSelected = selectedPelanggan?.id ?: ""
                    val formValid = pelangganIdSelected.isNotEmpty() && jumlahValid && tanggalPembayaran.isNotBlank() && jumlahInt <= sisaHutang

                    ModernGradientButton(
                        text = "Simpan Pembayaran",
                        onClick = {
                            if (jumlahInt > jumlahCicilanReferensi && jumlahCicilanReferensi > 0) {
                                viewModel.tambahMultiplePembayaran(
                                    pelangganIdSelected,
                                    jumlahInt,
                                    tanggalPembayaran,
                                    jumlahCicilanReferensi
                                )
                                Toast.makeText(
                                    context,
                                    "Pembayaran berhasil disimpan sebagai ${
                                        (jumlahInt + jumlahCicilanReferensi - 1) / jumlahCicilanReferensi
                                    } cicilan.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                viewModel.tambahPembayaran(pelangganIdSelected, jumlahInt, tanggalPembayaran)
                                Toast.makeText(context, "Pembayaran berhasil disimpan.", Toast.LENGTH_SHORT).show()
                            }

                            // Reset form
                            jumlahPembayaran = ""
                            tanggalPembayaran = dateFormat.format(Calendar.getInstance().time)
                            selectedPelanggan = null
                            queryNama = ""
                        },
                        enabled = formValid,
                        gradient = InputColors.successGradient,
                        icon = Icons.Rounded.Save
                    )

                    ModernOutlinedButton(
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
private fun ModernInputTopBar(
    title: String,
    isDark: Boolean,
    txtColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) InputColors.darkSurface else Color.White,
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
private fun ModernCustomerInfoCard(
    pelanggan: Pelanggan,
    jumlahCicilanReferensi: Int,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    val totalDibayar = pelanggan.pembayaranList.sumOf {
        it.jumlah + it.subPembayaran.sumOf { sub -> sub.jumlah }
    }
    val sisa = (pelanggan.totalPelunasan - totalDibayar).coerceAtLeast(0)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = InputColors.primary.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            Brush.linearGradient(InputColors.primaryGradient),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = pelanggan.namaKtp.take(2).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Column {
                    Text(
                        text = pelanggan.namaKtp,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = txtColor
                    )
                    Text(
                        text = pelanggan.wilayah,
                        fontSize = 13.sp,
                        color = subtitleColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = if (isDark) InputColors.darkBorder else InputColors.lightBorder)
            Spacer(modifier = Modifier.height(16.dp))

            // Info Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoColumn(
                    label = "Total Pelunasan",
                    value = "Rp ${formatRupiah(pelanggan.totalPelunasan)}",
                    valueColor = txtColor,
                    labelColor = subtitleColor
                )
                InfoColumn(
                    label = "Total Dibayar",
                    value = "Rp ${formatRupiah(totalDibayar)}",
                    valueColor = InputColors.success,
                    labelColor = subtitleColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoColumn(
                    label = "Sisa Utang",
                    value = "Rp ${formatRupiah(sisa)}",
                    valueColor = if (sisa > 0) InputColors.warning else InputColors.success,
                    labelColor = subtitleColor
                )
                InfoColumn(
                    label = "Cicilan Referensi",
                    value = "Rp ${formatRupiah(jumlahCicilanReferensi)}",
                    valueColor = InputColors.primary,
                    labelColor = subtitleColor
                )
            }
        }
    }
}

@Composable
private fun InfoColumn(
    label: String,
    value: String,
    valueColor: Color,
    labelColor: Color
) {
    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            color = labelColor
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    expanded: Boolean,
    pelangganList: List<Pelanggan>,
    onPelangganSelected: (Pelanggan) -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    val filteredList = pelangganList.filter { pel ->
        val totalBayar = pel.pembayaranList.mapNotNull { it }.sumOf { it.jumlah + it.subPembayaran.mapNotNull { it }.sumOf { sub -> sub.jumlah } }
        val sisa = (pel.totalPelunasan - totalBayar).coerceAtLeast(0)
        sisa > 0 && pel.namaKtp.contains(value, ignoreCase = true) &&
                (pel.status == "Aktif" || pel.status.equals("aktif", ignoreCase = true))
    }

    ExposedDropdownMenuBox(
        expanded = expanded && filteredList.isNotEmpty(),
        onExpandedChange = onExpandedChange
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Cari Nama Nasabah") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.PersonSearch,
                    contentDescription = null,
                    tint = InputColors.primary
                )
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = cardColor,
                unfocusedContainerColor = cardColor,
                focusedBorderColor = InputColors.primary,
                unfocusedBorderColor = borderColor,
                focusedLabelColor = InputColors.primary,
                unfocusedLabelColor = subtitleColor,
                cursorColor = InputColors.primary,
                focusedTextColor = txtColor,
                unfocusedTextColor = txtColor
            )
        )

        ExposedDropdownMenu(
            expanded = expanded && filteredList.isNotEmpty(),
            onDismissRequest = { onExpandedChange(false) }
        ) {
            filteredList.take(5).forEach { pelanggan ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                pelanggan.namaKtp,
                                fontWeight = FontWeight.Medium,
                                color = txtColor
                            )
                            Text(
                                pelanggan.wilayah,
                                fontSize = 12.sp,
                                color = subtitleColor
                            )
                        }
                    },
                    onClick = { onPelangganSelected(pelanggan) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    Brush.linearGradient(InputColors.primaryGradient),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pelanggan.namaKtp.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModernAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color
) {
    OutlinedTextField(
        value = formatRupiahInput(value),
        onValueChange = onValueChange,
        label = { Text("Jumlah Pembayaran (Rp)") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Payments,
                contentDescription = null,
                tint = InputColors.success
            )
        },
        visualTransformation = ThousandSeparatorTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = cardColor,
            unfocusedContainerColor = cardColor,
            focusedBorderColor = InputColors.success,
            unfocusedBorderColor = borderColor,
            focusedLabelColor = InputColors.success,
            unfocusedLabelColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            cursorColor = InputColors.success,
            focusedTextColor = txtColor,
            unfocusedTextColor = txtColor
        )
    )
}

@Composable
private fun ModernDateField(
    tanggal: String,
    onDateClick: () -> Unit,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDateClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
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
                            Brush.linearGradient(InputColors.infoGradient),
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
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
                    Text(
                        text = tanggal,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = txtColor
                    )
                }
            }

            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "Pilih Tanggal",
                tint = InputColors.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ModernWarningChip(text: String, gradient: List<Color>) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = gradient.first().copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = gradient.first(),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                color = gradient.first(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ModernInfoChip(text: String, gradient: List<Color>) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = gradient.first().copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = gradient.first(),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                color = gradient.first(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ModernGradientButton(
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
            .height(54.dp)
            .shadow(
                elevation = if (enabled) 8.dp else 0.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = gradient.first().copy(alpha = 0.3f)
            ),
        shape = RoundedCornerShape(14.dp),
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
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ModernOutlinedButton(
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
            .height(54.dp),
        shape = RoundedCornerShape(14.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.linearGradient(listOf(borderColor, borderColor))
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isDark) InputColors.darkSurface else Color.White
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