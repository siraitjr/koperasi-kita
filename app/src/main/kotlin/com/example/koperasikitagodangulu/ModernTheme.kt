package com.example.koperasikitagodangulu.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern Color Palette for Koperasi App
 * Consistent colors across all screens
 */
object ModernColors {
    // Gradient Palettes
    val primaryGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
    val successGradient = listOf(Color(0xFF10B981), Color(0xFF34D399))
    val warningGradient = listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
    val dangerGradient = listOf(Color(0xFFEF4444), Color(0xFFF87171))
    val infoGradient = listOf(Color(0xFF3B82F6), Color(0xFF60A5FA))
    val purpleGradient = listOf(Color(0xFF8B5CF6), Color(0xFFA78BFA))
    val tealGradient = listOf(Color(0xFF14B8A6), Color(0xFF2DD4BF))
    val roseGradient = listOf(Color(0xFFF43F5E), Color(0xFFFB7185))
    val orangeGradient = listOf(Color(0xFFF97316), Color(0xFFFB923C))
    val cyanGradient = listOf(Color(0xFF06B6D4), Color(0xFF22D3EE))

    // Solid Colors
    val primary = Color(0xFF6366F1)
    val primaryLight = Color(0xFF818CF8)
    val success = Color(0xFF10B981)
    val successLight = Color(0xFF34D399)
    val warning = Color(0xFFF59E0B)
    val warningLight = Color(0xFFFBBF24)
    val danger = Color(0xFFEF4444)
    val dangerLight = Color(0xFFF87171)
    val info = Color(0xFF3B82F6)
    val infoLight = Color(0xFF60A5FA)

    // Dark Mode Colors
    val darkBackground = Color(0xFF0F172A)
    val darkSurface = Color(0xFF1E293B)
    val darkCard = Color(0xFF334155)
    val darkBorder = Color(0xFF475569)
    val darkTextPrimary = Color(0xFFF1F5F9)
    val darkTextSecondary = Color(0xFF94A3B8)

    // Light Mode Colors
    val lightBackground = Color(0xFFF8FAFC)
    val lightSurface = Color(0xFFFFFFFF)
    val lightCard = Color(0xFFFFFFFF)
    val lightBorder = Color(0xFFE2E8F0)
    val lightTextPrimary = Color(0xFF1E293B)
    val lightTextSecondary = Color(0xFF64748B)

    // Status Colors
    val online = Color(0xFF10B981)
    val offline = Color(0xFFEF4444)
    val syncing = Color(0xFF3B82F6)
    val pending = Color(0xFFF59E0B)
}

/**
 * Theme helper functions
 */
@Composable
fun getBackgroundColor(isDark: Boolean): Color =
    if (isDark) ModernColors.darkBackground else ModernColors.lightBackground

@Composable
fun getSurfaceColor(isDark: Boolean): Color =
    if (isDark) ModernColors.darkSurface else ModernColors.lightSurface

@Composable
fun getCardColor(isDark: Boolean): Color =
    if (isDark) ModernColors.darkCard else ModernColors.lightCard

@Composable
fun getBorderColor(isDark: Boolean): Color =
    if (isDark) ModernColors.darkBorder else ModernColors.lightBorder

@Composable
fun getTextPrimaryColor(isDark: Boolean): Color =
    if (isDark) ModernColors.darkTextPrimary else ModernColors.lightTextPrimary

@Composable
fun getTextSecondaryColor(isDark: Boolean): Color =
    if (isDark) ModernColors.darkTextSecondary else ModernColors.lightTextSecondary

/**
 * Modern Top App Bar with gradient
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(
    title: String,
    subtitle: String? = null,
    isDark: Boolean,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isDark) ModernColors.darkSurface else Color.White,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (navigationIcon != null) {
                    navigationIcon()
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = getTextPrimaryColor(isDark)
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            color = getTextSecondaryColor(isDark)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
        }
    }
}

/**
 * Modern Card with gradient header option
 */
@Composable
fun ModernCard(
    modifier: Modifier = Modifier,
    isDark: Boolean,
    gradient: List<Color>? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = (gradient?.firstOrNull() ?: ModernColors.primary).copy(alpha = 0.1f),
                spotColor = (gradient?.firstOrNull() ?: ModernColors.primary).copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (gradient != null) Color.Transparent else getCardColor(isDark)
        )
    ) {
        if (gradient != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = gradient,
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                        )
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    content = content
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content
            )
        }
    }
}

/**
 * Modern Stat Card for dashboard items
 */
@Composable
fun ModernStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = gradient.first().copy(alpha = 0.3f),
                spotColor = gradient.first().copy(alpha = 0.3f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        onClick = { onClick?.invoke() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradient))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = value,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            Color.White.copy(alpha = 0.2f),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

/**
 * Modern Info Row for displaying label-value pairs
 */
@Composable
fun ModernInfoRow(
    label: String,
    value: String,
    isDark: Boolean,
    valueColor: Color? = null,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = getTextSecondaryColor(isDark),
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = label,
                color = getTextSecondaryColor(isDark),
                fontSize = 14.sp
            )
        }
        Text(
            text = value,
            color = valueColor ?: getTextPrimaryColor(isDark),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Modern Button with gradient
 */
@Composable
fun ModernButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = ModernColors.primaryGradient,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .shadow(
                elevation = if (enabled) 8.dp else 0.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = gradient.first().copy(alpha = 0.3f),
                spotColor = gradient.first().copy(alpha = 0.3f)
            ),
        enabled = enabled,
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
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
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

/**
 * Modern Outlined Button
 */
@Composable
fun ModernOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.linearGradient(
                listOf(
                    getBorderColor(isDark),
                    getBorderColor(isDark)
                )
            )
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = getSurfaceColor(isDark)
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = getTextSecondaryColor(isDark),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = getTextSecondaryColor(isDark),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Modern TextField
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    errorMessage: String? = null
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            leadingIcon = if (leadingIcon != null) {
                {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = if (isError) ModernColors.danger else ModernColors.primary
                    )
                }
            } else null,
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            readOnly = readOnly,
            isError = isError,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = getCardColor(isDark),
                unfocusedContainerColor = getCardColor(isDark),
                focusedBorderColor = ModernColors.primary,
                unfocusedBorderColor = getBorderColor(isDark),
                errorBorderColor = ModernColors.danger,
                focusedLabelColor = ModernColors.primary,
                unfocusedLabelColor = getTextSecondaryColor(isDark),
                cursorColor = ModernColors.primary,
                focusedTextColor = getTextPrimaryColor(isDark),
                unfocusedTextColor = getTextPrimaryColor(isDark)
            )
        )

        if (isError && errorMessage != null) {
            Text(
                text = errorMessage,
                color = ModernColors.danger,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
    }
}

/**
 * Modern Avatar with initials
 */
@Composable
fun ModernAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    gradient: List<Color> = ModernColors.primaryGradient,
    borderColor: Color? = null
) {
    val initials = name
        .trim()
        .split(" ")
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (borderColor != null) {
                    Modifier.border(2.dp, borderColor, CircleShape)
                } else Modifier
            )
            .background(
                Brush.linearGradient(gradient),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value / 2.5).sp
        )
    }
}

/**
 * Modern Badge
 */
@Composable
fun ModernBadge(
    text: String,
    gradient: List<Color>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(gradient),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Modern Status Chip
 */
@Composable
fun ModernStatusChip(
    text: String,
    color: Color,
    isDark: Boolean
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = if (isDark) 0.2f else 0.1f)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Modern Empty State
 */
@Composable
fun ModernEmptyState(
    title: String,
    message: String,
    icon: ImageVector,
    isDark: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    Brush.linearGradient(ModernColors.primaryGradient.map { it.copy(alpha = 0.2f) }),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ModernColors.primary,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = getTextPrimaryColor(isDark),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            fontSize = 14.sp,
            color = getTextSecondaryColor(isDark),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Modern Loading State
 */
@Composable
fun ModernLoadingState(
    message: String = "Memuat data...",
    isDark: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = ModernColors.primary,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            fontSize = 14.sp,
            color = getTextSecondaryColor(isDark)
        )
    }
}