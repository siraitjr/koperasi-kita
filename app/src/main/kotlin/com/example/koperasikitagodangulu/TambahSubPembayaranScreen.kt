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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import com.example.koperasikitagodangulu.utils.formatRupiah
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// Modern Color Palette
private object SubPaymentColors {
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
fun TambahSubPembayaranScreen(
    navController: NavController,
    viewModel: PelangganViewModel,
    pelangganId: String,
    pembayaranIndex: Int
) {
    val context = LocalContext.current

    val isDark by viewModel.isDarkMode
    val cardColor = if (isDark) SubPaymentColors.darkCard else SubPaymentColors.lightSurface
    val borderColor = if (isDark) SubPaymentColors.darkBorder else SubPaymentColors.lightBorder
    val txtColor = if (isDark) Color.White else Color(0xFF1E293B)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val systemUiController = rememberSystemUiController()
    val bgColor = if (isDark) SubPaymentColors.darkBackground else SubPaymentColors.lightBackground
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val pelanggan = viewModel.daftarPelanggan.find { it.id == pelangganId }
    val pembayaran = pelanggan?.pembayaranList?.getOrNull(pembayaranIndex)

    if (pelanggan == null || pembayaran == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = null,
                    tint = SubPaymentColors.danger,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Data tidak ditemukan",
                    color = subtitleColor,
                    fontSize = 16.sp
                )
            }
        }
        return
    }

    // Animation state
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Form states
    var jumlahSubPembayaran by remember { mutableStateOf("") }
    var tanggalSubPembayaran by remember { mutableStateOf("") }

    val calendar = remember { Calendar.getInstance() }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")) }

    LaunchedEffect(Unit) {
        tanggalSubPembayaran = dateFormat.format(calendar.time)
    }

    val datePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            calendar.set(year, month, dayOfMonth)
            tanggalSubPembayaran = dateFormat.format(calendar.time)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    fun formatRibuan(input: String): String {
        return try {
            val cleanString = input.replace("[^\\d]".toRegex(), "")
            if (cleanString.isBlank()) "" else {
                val number = cleanString.toLong()
                DecimalFormat("#,###").format(number)
            }
        } catch (e: Exception) {
            input
        }
    }

    fun parseFormattedNumber(formatted: String): Int {
        return try {
            formatted.replace("[^\\d]".toRegex(), "").toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    val jumlahNumerik = remember(jumlahSubPembayaran) {
        parseFormattedNumber(jumlahSubPembayaran)
    }

    val isSimpanEnabled = remember(jumlahNumerik, tanggalSubPembayaran) {
        jumlahNumerik >= 1000 && tanggalSubPembayaran.isNotBlank()
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            SubPaymentTopBar(
                title = "Tambah Sub Pembayaran",
                isDark = isDark,
                txtColor = txtColor,
                onBackClick = { navController.popBackStack() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Original Payment Info Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(
                    initialOffsetY = { -30 },
                    animationSpec = tween(400)
                )
            ) {
                OriginalPaymentCard(
                    pembayaran = pembayaran,
                    isDark = isDark,
                    cardColor = cardColor,
                    txtColor = txtColor,
                    subtitleColor = subtitleColor
                )
            }

            // Input Form Card
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 30 },
                    animationSpec = tween(400, delayMillis = 100)
                )
            ) {
                SubPaymentFormCard(
                    jumlahSubPembayaran = jumlahSubPembayaran,
                    onJumlahChange = { jumlahSubPembayaran = formatRibuan(it) },
                    tanggalSubPembayaran = tanggalSubPembayaran,
                    onDateClick = { datePickerDialog.show() },
                    jumlahNumerik = jumlahNumerik,
                    pembayaran = pembayaran,
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
                    SubPaymentGradientButton(
                        text = if (isSimpanEnabled) "Simpan Sub Pembayaran"
                        else if (jumlahNumerik > 0 && jumlahNumerik < 1000) "Minimum Rp 1.000"
                        else "Simpan Sub Pembayaran",
                        onClick = {
                            if (jumlahNumerik < 1000) {
                                Toast.makeText(context, "Minimum pembayaran adalah Rp 1.000", Toast.LENGTH_SHORT).show()
                                return@SubPaymentGradientButton
                            }
                            if (tanggalSubPembayaran.isEmpty()) {
                                Toast.makeText(context, "Tanggal harus diisi", Toast.LENGTH_SHORT).show()
                                return@SubPaymentGradientButton
                            }
                            viewModel.tambahSubPembayaran(pelangganId, pembayaranIndex, jumlahNumerik, tanggalSubPembayaran)
                            Toast.makeText(context, "Sub pembayaran berhasil ditambahkan", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        },
                        enabled = isSimpanEnabled,
                        gradient = SubPaymentColors.successGradient,
                        icon = Icons.Rounded.Add
                    )

                    SubPaymentOutlinedButton(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPaymentTopBar(
    title: String,
    isDark: Boolean,
    txtColor: Color,
    onBackClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) SubPaymentColors.darkSurface else Color.White,
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
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )

            // Spacer for balance
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}

@Composable
private fun OriginalPaymentCard(
    pembayaran: Pembayaran,
    isDark: Boolean,
    cardColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    val totalSubSebelumnya = pembayaran.subPembayaran.sumOf { it.jumlah }
    val totalSemua = pembayaran.jumlah + totalSubSebelumnya

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = SubPaymentColors.primary.copy(alpha = 0.15f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(SubPaymentColors.primaryGradient))
        ) {
            // Decorative elements
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(x = (-20).dp, y = (-20).dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Receipt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "Pembayaran Utama",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Rp ${formatRupiah(pembayaran.jumlah)}",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Tanggal",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                        Text(
                            text = pembayaran.tanggal,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Sub Pembayaran",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Rp ${formatRupiah(totalSubSebelumnya)}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Saat Ini",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Rp ${formatRupiah(totalSemua)}",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SubPaymentFormCard(
    jumlahSubPembayaran: String,
    onJumlahChange: (String) -> Unit,
    tanggalSubPembayaran: String,
    onDateClick: () -> Unit,
    jumlahNumerik: Int,
    pembayaran: Pembayaran,
    isDark: Boolean,
    cardColor: Color,
    borderColor: Color,
    txtColor: Color,
    subtitleColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = SubPaymentColors.primary.copy(alpha = 0.1f)
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
                text = "Detail Sub Pembayaran",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor
            )

            // Amount Input
            OutlinedTextField(
                value = jumlahSubPembayaran,
                onValueChange = onJumlahChange,
                label = { Text("Jumlah Sub Pembayaran (Rp)") },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                Brush.linearGradient(SubPaymentColors.successGradient),
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = if (isDark) SubPaymentColors.darkSurface else Color(0xFFF8FAFC),
                    unfocusedContainerColor = if (isDark) SubPaymentColors.darkSurface else Color(0xFFF8FAFC),
                    focusedBorderColor = SubPaymentColors.success,
                    unfocusedBorderColor = borderColor,
                    focusedLabelColor = SubPaymentColors.success,
                    unfocusedLabelColor = subtitleColor,
                    cursorColor = SubPaymentColors.success,
                    focusedTextColor = txtColor,
                    unfocusedTextColor = txtColor
                )
            )

            // Validation message
            if (jumlahNumerik > 0 && jumlahNumerik < 1000) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SubPaymentColors.danger.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = SubPaymentColors.danger,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Minimum pembayaran Rp 1.000",
                            color = SubPaymentColors.danger,
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
                    containerColor = if (isDark) SubPaymentColors.darkSurface else Color(0xFFF8FAFC)
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
                                    Brush.linearGradient(SubPaymentColors.infoGradient),
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
                                text = "Tanggal Sub Pembayaran",
                                fontSize = 12.sp,
                                color = subtitleColor
                            )
                            Text(
                                text = tanggalSubPembayaran,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = txtColor
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Pilih Tanggal",
                        tint = SubPaymentColors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Preview Card
            if (jumlahNumerik > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = SubPaymentColors.success.copy(alpha = 0.1f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.TrendingUp,
                                contentDescription = null,
                                tint = SubPaymentColors.success,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Preview Setelah Tambah",
                                fontWeight = FontWeight.Bold,
                                color = SubPaymentColors.success,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val totalSubSebelumnya = pembayaran.subPembayaran.sumOf { it.jumlah }
                        val totalSubSetelah = totalSubSebelumnya + jumlahNumerik
                        val totalSemuaPembayaran = pembayaran.jumlah + totalSubSetelah

                        PreviewRow(
                            label = "Sub Pembayaran",
                            value = "Rp ${formatRupiah(totalSubSetelah)}",
                            color = SubPaymentColors.success
                        )
                        PreviewRow(
                            label = "Total Pembayaran",
                            value = "Rp ${formatRupiah(totalSemuaPembayaran)}",
                            color = SubPaymentColors.info
                        )
                        PreviewRow(
                            label = "Ditambahkan",
                            value = "+ Rp ${formatRupiah(jumlahNumerik)}",
                            color = SubPaymentColors.warning
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color.Gray
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun SubPaymentGradientButton(
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
private fun SubPaymentOutlinedButton(
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
            containerColor = if (isDark) SubPaymentColors.darkSurface else Color.White
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