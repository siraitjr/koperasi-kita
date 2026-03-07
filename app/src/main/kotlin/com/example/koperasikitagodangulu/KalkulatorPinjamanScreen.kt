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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.text.NumberFormat
import java.util.*
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// Modern Color Palette
private object CalculatorColors {
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
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
    val info = Color(0xFF3B82F6)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KalkulatorPinjamanScreen(
    navController: NavController,
    viewModel: PelangganViewModel
) {
    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) CalculatorColors.darkCard else CalculatorColors.lightSurface
    val borderColor = if (isDark) CalculatorColors.darkBorder else CalculatorColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) CalculatorColors.darkBackground else CalculatorColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Modern Header
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
                        elevation = 16.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = CalculatorColors.primary.copy(alpha = 0.2f)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(CalculatorColors.primaryGradient))
                ) {
                    // Decorative circles
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .offset(x = (-20).dp, y = (-20).dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 15.dp, y = 15.dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Calculate,
                                contentDescription = "Kalkulator",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Kalkulator Pinjaman",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Simulasi cicilan harian",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                initialOffsetY = { 30 },
                animationSpec = tween(400, delayMillis = 100)
            )
        ) {
            KalkulatorPinjamanContent(
                viewModel = viewModel,
                isDark = isDark,
                cardColor = cardColor,
                borderColor = borderColor,
                txtColor = txtColor,
                subtitleColor = subtitleColor
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KalkulatorPinjamanContent(
    viewModel: PelangganViewModel,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    var jumlahPinjaman by remember { mutableStateOf("") }
    var tenorHari by remember { mutableStateOf("24") }
    var hasilSimulasi by remember { mutableStateOf<List<Pembayaran>>(emptyList()) }
    var ringkasanText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Opsi tenor harian
    val opsiTenor = listOf(
        "24" to "24 Hari",
        "28" to "28 Hari",
        "30" to "30 Hari",
        "36" to "36 Hari",
        "40" to "40 Hari"
    )

    fun formatRupiah(nominal: Int): String {
        val formatter = NumberFormat.getNumberInstance(Locale("in", "ID"))
        return formatter.format(nominal)
    }

    fun pembulatanKeRibuan(nilai: Double): Int {
        return (nilai / 1000.0).roundToInt() * 1000
    }

    fun hitungSimulasi() {
        val pokok = jumlahPinjaman.replace("[^\\d]".toRegex(), "").toDoubleOrNull() ?: return
        val tenor = tenorHari.toIntOrNull() ?: return

        val totalBayar = (pokok * 1.2).toInt()
        val angsuranPerHariExact = totalBayar.toDouble() / tenor
        val angsuranPerHari = (angsuranPerHariExact / 100.0).roundToInt() * 100

        val daftar = mutableListOf<Pembayaran>()
        val builder = StringBuilder()

        val potonganAdmin = (pokok * 0.1).toInt() // 10% dari pokok
        val diterimaNasabah = pokok.toInt() - potonganAdmin

        builder.append("Pokok Pinjaman: Rp ${formatRupiah(pokok.toInt())}\n")
        builder.append("Jasa Pinjaman: Rp ${formatRupiah(totalBayar - pokok.toInt())}\n")
        builder.append("Diterima Nasabah: Rp ${formatRupiah(diterimaNasabah)}\n")
        builder.append("Total Bayar: Rp ${formatRupiah(totalBayar)}\n\n")
        builder.append("Rincian Angsuran Harian:\n")

        val totalDariAngsuran = angsuranPerHari * (tenor - 1)
        val angsuranTerakhir = totalBayar - totalDariAngsuran

        for (i in 1 until tenor) {
            daftar.add(Pembayaran(angsuranPerHari, "Hari $i"))
            builder.append("Hari $i: Rp ${formatRupiah(angsuranPerHari)}\n")
        }

        daftar.add(Pembayaran(angsuranTerakhir, "Hari $tenor"))
        builder.append("Hari $tenor: Rp ${formatRupiah(angsuranTerakhir)}\n")

        ringkasanText = builder.toString()
        hasilSimulasi = daftar
    }

    // Input Card - hilang setelah hasil simulasi ditampilkan
    AnimatedVisibility(
        visible = hasilSimulasi.isEmpty(),
        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
        exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = CalculatorColors.primary.copy(alpha = 0.1f)
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
                            .background(CalculatorColors.info.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            tint = CalculatorColors.info,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = "Input Simulasi",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = txtColor
                    )
                }

                // Input Jumlah Pinjaman
                OutlinedTextField(
                    value = jumlahPinjaman,
                    onValueChange = {
                        val clean = it.filter { ch -> ch.isDigit() }
                        jumlahPinjaman = NumberFormat.getNumberInstance(Locale.US).format(clean.toLongOrNull() ?: 0)
                    },
                    label = { Text("Jumlah Pinjaman", color = subtitleColor) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(CalculatorColors.warning.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Payments,
                                contentDescription = null,
                                tint = CalculatorColors.warning,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = cardColor,
                        unfocusedContainerColor = cardColor,
                        focusedBorderColor = CalculatorColors.primary,
                        unfocusedBorderColor = borderColor,
                        cursorColor = CalculatorColors.primary,
                        focusedTextColor = txtColor,
                        unfocusedTextColor = txtColor,
                        focusedLabelColor = CalculatorColors.primary,
                        unfocusedLabelColor = subtitleColor
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Dropdown Tenor
                var expandedTenor by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expandedTenor,
                    onExpandedChange = { expandedTenor = !expandedTenor },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = opsiTenor.firstOrNull { it.first == tenorHari }?.second ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tenor Pinjaman", color = subtitleColor) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(CalculatorColors.info.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CalendarMonth,
                                    contentDescription = null,
                                    tint = CalculatorColors.info,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedTenor) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = cardColor,
                            unfocusedContainerColor = cardColor,
                            focusedBorderColor = CalculatorColors.primary,
                            unfocusedBorderColor = borderColor,
                            cursorColor = CalculatorColors.primary,
                            focusedTextColor = txtColor,
                            unfocusedTextColor = txtColor,
                            focusedLabelColor = CalculatorColors.primary,
                            unfocusedLabelColor = subtitleColor
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = expandedTenor,
                        onDismissRequest = { expandedTenor = false }
                    ) {
                        opsiTenor.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = txtColor) },
                                onClick = {
                                    tenorHari = value
                                    expandedTenor = false
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Event,
                                        null,
                                        tint = CalculatorColors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Tombol Hitung
                Button(
                    onClick = {
                        hitungSimulasi()
                        keyboardController?.hide()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(16.dp),
                            ambientColor = CalculatorColors.primary.copy(alpha = 0.3f)
                        ),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    contentPadding = PaddingValues()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.linearGradient(CalculatorColors.primaryGradient)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Calculate,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Text(
                                "Hitung Simulasi",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    } // End AnimatedVisibility Input Card

    // Hasil Simulasi
    AnimatedVisibility(
        visible = hasilSimulasi.isNotEmpty(),
        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
        exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
    ) {
        Column {
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(CalculatorColors.success.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Insights,
                        contentDescription = null,
                        tint = CalculatorColors.success,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Hasil Simulasi",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = txtColor
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = CalculatorColors.success.copy(alpha = 0.1f)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ringkasanText.split("\n").forEachIndexed { index, line ->
                        if (line.isNotBlank()) {
                            val isHeader = line.contains("Pokok") || line.contains("Bunga") || line.contains("Diterima Nasabah") || line.contains("Total Bayar")
                            val isRincianHeader = line.contains("Rincian")

                            if (isRincianHeader) {
                                HorizontalDivider(
                                    color = borderColor,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isHeader) Modifier
                                            .background(
                                                if (line.contains("Total Bayar"))
                                                    CalculatorColors.success.copy(alpha = 0.1f)
                                                else Color.Transparent,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                        else Modifier.padding(vertical = 2.dp)
                                    ),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val parts = line.split(":")
                                if (parts.size == 2) {
                                    Text(
                                        text = parts[0].trim(),
                                        color = if (line.contains("Total Bayar")) CalculatorColors.success else subtitleColor,
                                        fontSize = if (isHeader) 14.sp else 13.sp,
                                        fontWeight = if (line.contains("Total Bayar")) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = parts[1].trim(),
                                        color = if (line.contains("Total Bayar")) CalculatorColors.success else txtColor,
                                        fontSize = if (isHeader) 14.sp else 13.sp,
                                        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                } else {
                                    Text(
                                        text = line,
                                        color = if (isRincianHeader) CalculatorColors.info else txtColor,
                                        fontSize = if (isRincianHeader) 14.sp else 13.sp,
                                        fontWeight = if (isRincianHeader) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}