package com.example.koperasikitagodangulu

import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KoordinatorAbsensiScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val context = LocalContext.current
    val isDark by viewModel.isDarkMode
    val bgColor = KoordinatorColors.getBackground(isDark)
    val cardColor = KoordinatorColors.getCard(isDark)
    val txtColor = KoordinatorColors.getTextPrimary(isDark)
    val subtitleColor = KoordinatorColors.getTextSecondary(isDark)
    val borderColor = KoordinatorColors.getBorder(isDark)

    val systemUiController = rememberSystemUiController()
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val allCabangSummaries by viewModel.allCabangSummaries.collectAsState()
    val allAdminSummaries by viewModel.allAdminSummaries.collectAsState()

    val todayFormatted = remember {
        SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).format(Date())
    }

    var selectedCabangId by remember { mutableStateOf("") }
    var selectedCabangName by remember { mutableStateOf("") }
    var cabangExpanded by remember { mutableStateOf(false) }

    var selectedAdminId by remember { mutableStateOf("") }
    var selectedAdminName by remember { mutableStateOf("") }
    var adminExpanded by remember { mutableStateOf(false) }

    var keterangan by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var absensiSaved by remember { mutableStateOf(false) }

    // Filter admin berdasarkan cabang yang dipilih
    val filteredAdmins = remember(selectedCabangId, allAdminSummaries) {
        if (selectedCabangId.isBlank()) emptyList()
        else allAdminSummaries.values.filter { it.cabang == selectedCabangId }.toList()
    }

    // Cek apakah sudah absensi hari ini
    val absensiHariIni by viewModel.absensiHariIni.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkAbsensiHariIni()
    }

    LaunchedEffect(absensiHariIni) {
        if (absensiHariIni != null) {
            absensiSaved = true
            selectedAdminName = absensiHariIni?.adminLapanganName ?: ""
            selectedCabangName = absensiHariIni?.cabangName ?: ""
            keterangan = absensiHariIni?.keterangan ?: ""
        }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
                color = Color.Transparent,
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(KoordinatorColors.primaryGradient),
                            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .statusBarsPadding()
                ) {
                    Text(
                        "Absensi",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        bottomBar = {
            KoordinatorBottomNavigation(navController, "koordinator_absensi", viewModel, isDark)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header tanggal
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(KoordinatorColors.primaryGradient))
                        .padding(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.CalendarToday, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Absensi Pendampingan", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(todayFormatted, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (absensiSaved) {
                // Sudah absensi hari ini
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(KoordinatorColors.success.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = KoordinatorColors.success, modifier = Modifier.size(40.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Absensi Tercatat", color = txtColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (selectedCabangName.isNotBlank()) {
                            Text("Cabang: $selectedCabangName", color = subtitleColor, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text("Hari ini Anda mendampingi:", color = subtitleColor, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            selectedAdminName.ifBlank { absensiHariIni?.adminLapanganName ?: "-" },
                            color = KoordinatorColors.primary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (keterangan.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Keterangan: $keterangan", color = subtitleColor, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                // Form absensi
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        // Section: Pilih Cabang
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(KoordinatorColors.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Business, contentDescription = null, tint = KoordinatorColors.primary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Pilih Cabang", color = txtColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        ExposedDropdownMenuBox(
                            expanded = cabangExpanded,
                            onExpandedChange = { cabangExpanded = !cabangExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedCabangName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Cabang") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cabangExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = KoordinatorColors.primary,
                                    unfocusedBorderColor = borderColor,
                                    focusedLabelColor = KoordinatorColors.primary,
                                    unfocusedLabelColor = subtitleColor,
                                    focusedTextColor = txtColor,
                                    unfocusedTextColor = txtColor
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            ExposedDropdownMenu(
                                expanded = cabangExpanded,
                                onDismissRequest = { cabangExpanded = false }
                            ) {
                                if (allCabangSummaries.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Belum ada data cabang", color = subtitleColor) },
                                        onClick = { cabangExpanded = false }
                                    )
                                } else {
                                    allCabangSummaries.forEach { (cabangId, cabangSummary) ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    cabangSummary.cabangName.ifBlank { cabangId },
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 14.sp
                                                )
                                            },
                                            onClick = {
                                                selectedCabangId = cabangId
                                                selectedCabangName = cabangSummary.cabangName.ifBlank { cabangId }
                                                // Reset admin selection saat cabang berubah
                                                selectedAdminId = ""
                                                selectedAdminName = ""
                                                cabangExpanded = false
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Business, contentDescription = null, tint = KoordinatorColors.primary, modifier = Modifier.size(20.dp))
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Section: Pilih Admin Lapangan
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(KoordinatorColors.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Person, contentDescription = null, tint = KoordinatorColors.primary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Pilih Admin Lapangan", color = txtColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        ExposedDropdownMenuBox(
                            expanded = adminExpanded,
                            onExpandedChange = {
                                if (selectedCabangId.isNotBlank()) {
                                    adminExpanded = !adminExpanded
                                } else {
                                    Toast.makeText(context, "Pilih cabang terlebih dahulu", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            OutlinedTextField(
                                value = selectedAdminName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Admin Lapangan yang Didampingi") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = adminExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                enabled = selectedCabangId.isNotBlank(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = KoordinatorColors.primary,
                                    unfocusedBorderColor = borderColor,
                                    focusedLabelColor = KoordinatorColors.primary,
                                    unfocusedLabelColor = subtitleColor,
                                    focusedTextColor = txtColor,
                                    unfocusedTextColor = txtColor,
                                    disabledBorderColor = borderColor.copy(alpha = 0.5f),
                                    disabledLabelColor = subtitleColor.copy(alpha = 0.5f),
                                    disabledTextColor = txtColor.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            ExposedDropdownMenu(
                                expanded = adminExpanded,
                                onDismissRequest = { adminExpanded = false }
                            ) {
                                if (filteredAdmins.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Tidak ada admin di cabang ini", color = subtitleColor) },
                                        onClick = { adminExpanded = false }
                                    )
                                } else {
                                    filteredAdmins.forEach { admin ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        admin.adminName.ifBlank { admin.adminEmail },
                                                        fontWeight = FontWeight.Medium,
                                                        fontSize = 14.sp
                                                    )
                                                    Text(
                                                        "${admin.nasabahAktif} nasabah aktif",
                                                        fontSize = 12.sp,
                                                        color = subtitleColor
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedAdminId = admin.adminId
                                                selectedAdminName = admin.adminName.ifBlank { admin.adminEmail }
                                                adminExpanded = false
                                            },
                                            leadingIcon = {
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .background(KoordinatorColors.primary.copy(alpha = 0.1f), CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        (admin.adminName.ifBlank { admin.adminEmail }).take(1).uppercase(),
                                                        color = KoordinatorColors.primary,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Keterangan
                        OutlinedTextField(
                            value = keterangan,
                            onValueChange = { keterangan = it },
                            label = { Text("Keterangan (opsional)") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = KoordinatorColors.primary,
                                unfocusedBorderColor = borderColor,
                                focusedLabelColor = KoordinatorColors.primary,
                                unfocusedLabelColor = subtitleColor,
                                focusedTextColor = txtColor,
                                unfocusedTextColor = txtColor
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Tombol Absensi
                        Button(
                            onClick = {
                                if (selectedCabangId.isBlank()) {
                                    Toast.makeText(context, "Pilih cabang terlebih dahulu", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (selectedAdminId.isBlank()) {
                                    Toast.makeText(context, "Pilih admin lapangan terlebih dahulu", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isLoading = true
                                viewModel.simpanAbsensi(
                                    adminLapanganId = selectedAdminId,
                                    adminLapanganName = selectedAdminName,
                                    keterangan = keterangan,
                                    cabangId = selectedCabangId,
                                    cabangName = selectedCabangName,
                                    onSuccess = {
                                        isLoading = false
                                        absensiSaved = true
                                        Toast.makeText(context, "Absensi berhasil dicatat", Toast.LENGTH_SHORT).show()
                                    },
                                    onFailure = { e ->
                                        isLoading = false
                                        Toast.makeText(context, "Gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = KoordinatorColors.primary),
                            enabled = !isLoading && selectedAdminId.isNotBlank() && selectedCabangId.isNotBlank()
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Absensi Sekarang", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
