package com.example.koperasikitagodangulu.theme

import androidx.compose.ui.graphics.Color

data class ThemeColors(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val error: Color,
    val success: Color,
    val successVariant: Color,
    val warning: Color,
    val info: Color,
    val disabled: Color
)

fun darkThemeColors() = ThemeColors(
    primary = Color(0xFF1E88E5),
    secondary = Color(0xFFFF9800),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFCF6679),
    success = Color(0xFF4DB6AC),
    successVariant = Color(0xFF26A69A),
    warning = Color(0xFFFFC107),
    info = Color(0xFF29B6F6),
    disabled = Color(0xFF555555)
)

fun lightThemeColors() = ThemeColors(
    primary = Color(0xFF1E88E5),
    secondary = Color(0xFFFF9800),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEEEEE),
    onBackground = Color.Black,
    onSurface = Color.Black,
    error = Color(0xFFB00020),
    success = Color(0xFF00897B),
    successVariant = Color(0xFF00796B),
    warning = Color(0xFFFFA000),
    info = Color(0xFF0288D1),
    disabled = Color(0xFF777777)
)