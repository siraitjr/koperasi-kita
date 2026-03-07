package com.example.koperasikitagodangulu

import android.content.Context
import android.content.SharedPreferences

enum class UserRole {
    ADMIN_LAPANGAN,
    PIMPINAN,
    PENGAWAS,
    KOORDINATOR,
    UNKNOWN
}

// Fungsi untuk menyimpan role
fun saveUserRole(context: Context, role: UserRole) {
    val sharedPref = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    val editor = sharedPref.edit()
    editor.putString("user_role", role.name)
    editor.apply()
}

fun getUserRole(context: Context): UserRole {
    val sharedPref = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    val roleName = sharedPref.getString("user_role", UserRole.ADMIN_LAPANGAN.name)
    return try {
        UserRole.valueOf(roleName ?: UserRole.ADMIN_LAPANGAN.name)
    } catch (e: Exception) {
        UserRole.ADMIN_LAPANGAN
    }
}