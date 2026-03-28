package com.example.koperasikitagodangulu

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PimpinanAbsensiScreen(
    navController: NavHostController,
    viewModel: PelangganViewModel
) {
    val context = LocalContext.current
    val isDark by viewModel.isDarkMode
    val bgColor = PimpinanColors.getBackground(isDark)
    val cardColor = PimpinanColors.getCard(isDark)
    val txtColor = PimpinanColors.getTextPrimary(isDark)
    val subtitleColor = PimpinanColors.getTextSecondary(isDark)
    val borderColor = PimpinanColors.getBorder(isDark)

    val systemUiController = rememberSystemUiController()
    SideEffect {
        systemUiController.setStatusBarColor(bgColor, darkIcons = !isDark)
        systemUiController.setNavigationBarColor(bgColor, darkIcons = !isDark)
    }

    val adminSummary by viewModel.adminSummary.collectAsState()
    val todayFormatted = remember {
        SimpleDateFormat("dd MMM yyyy", Locale("in", "ID")).format(Date())
    }
    val todayKey = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale("in", "ID")).format(Date())
    }

    var selectedAdminId by remember { mutableStateOf("") }
    var selectedAdminName by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var keterangan by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var absensiSaved by remember { mutableStateOf(false) }

    // Cek apakah sudah absensi hari ini
    val absensiHariIni by viewModel.absensiHariIni.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkAbsensiHariIni()
    }

    LaunchedEffect(absensiHariIni) {
        if (absensiHariIni != null) {
            absensiSaved = true
            selectedAdminName = absensiHariIni?.adminLapanganName ?: ""
            keterangan = absensiHariIni?.keterangan ?: ""
        }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            PimpinanTopBar(
                title = "Absensi",
                navController = navController,
                viewModel = viewModel,
                showRefresh = false,
                showNotifications = false
            )
        },
        bottomBar = {
            PimpinanBottomNavigation(navController, "pimpinan_absensi", viewModel)
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
                        .background(Brush.linearGradient(PimpinanColors.primaryGradient))
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
                                .background(PimpinanColors.success.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = PimpinanColors.success, modifier = Modifier.size(40.dp))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Absensi Tercatat", color = txtColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Hari ini Anda mendampingi:",
                            color = subtitleColor,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            selectedAdminName.ifBlank { absensiHariIni?.adminLapanganName ?: "-" },
                            color = PimpinanColors.primary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (keterangan.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Keterangan: $keterangan",
                                color = subtitleColor,
                                fontSize = 13.sp
                            )
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(PimpinanColors.primary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Person, contentDescription = null, tint = PimpinanColors.primary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Pilih Admin Lapangan", color = txtColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Dropdown pilih admin
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = selectedAdminName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Admin Lapangan yang Didampingi") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PimpinanColors.primary,
                                    unfocusedBorderColor = borderColor,
                                    focusedLabelColor = PimpinanColors.primary,
                                    unfocusedLabelColor = subtitleColor,
                                    focusedTextColor = txtColor,
                                    unfocusedTextColor = txtColor
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                if (adminSummary.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Belum ada data admin", color = subtitleColor) },
                                        onClick = { expanded = false }
                                    )
                                } else {
                                    adminSummary.forEach { admin ->
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
                                                expanded = false
                                            },
                                            leadingIcon = {
                                                Box(
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .background(PimpinanColors.primary.copy(alpha = 0.1f), CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        (admin.adminName.ifBlank { admin.adminEmail }).take(1).uppercase(),
                                                        color = PimpinanColors.primary,
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

                        // Keterangan (opsional)
                        OutlinedTextField(
                            value = keterangan,
                            onValueChange = { keterangan = it },
                            label = { Text("Keterangan (opsional)") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PimpinanColors.primary,
                                unfocusedBorderColor = borderColor,
                                focusedLabelColor = PimpinanColors.primary,
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
                                if (selectedAdminId.isBlank()) {
                                    Toast.makeText(context, "Pilih admin lapangan terlebih dahulu", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isLoading = true
                                viewModel.simpanAbsensi(
                                    adminLapanganId = selectedAdminId,
                                    adminLapanganName = selectedAdminName,
                                    keterangan = keterangan,
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
                            colors = ButtonDefaults.buttonColors(containerColor = PimpinanColors.primary),
                            enabled = !isLoading && selectedAdminId.isNotBlank()
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
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
