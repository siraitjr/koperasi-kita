package com.example.koperasikitagodangulu.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * =========================================================================
 * AUTO-START PERMISSION HELPER
 * =========================================================================
 *
 * HP Chinese ROM (Xiaomi, OPPO, Vivo, Realme, Huawei) secara default
 * MEMBLOKIR aplikasi pihak ketiga dari auto-start.
 * Tanpa izin ini, FCM push, WorkManager, dan foreground service
 * TIDAK BISA membangunkan app yang sudah di-kill.
 *
 * Helper ini mendeteksi merek HP dan membuka halaman pengaturan
 * auto-start yang sesuai.
 * =========================================================================
 */
object AutoStartHelper {

    private const val TAG = "AutoStartHelper"

    /**
     * Cek apakah HP ini adalah Chinese ROM yang butuh izin auto-start
     */
    fun needsAutoStartPermission(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer in listOf(
            "xiaomi", "redmi", "poco",
            "oppo", "realme", "oneplus",
            "vivo", "iqoo",
            "huawei", "honor",
            "meizu", "samsung" // Samsung juga punya "sleeping apps"
        )
    }

    /**
     * Buka halaman auto-start settings sesuai merek HP
     * @return true jika berhasil membuka, false jika tidak didukung
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()

        val intents = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"))
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")),
                Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"))
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
            )
            manufacturer.contains("oneplus") -> listOf(
                Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
            )
            manufacturer.contains("samsung") -> listOf(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"))
            )
            else -> emptyList()
        }

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    Log.d(TAG, "✅ Opened auto-start settings for $manufacturer")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to open: ${e.message}")
            }
        }

        Log.w(TAG, "⚠️ No auto-start settings found for $manufacturer")
        return false
    }

    /**
     * Dapatkan nama pengaturan sesuai merek HP (untuk ditampilkan ke user)
     */
    fun getSettingsName(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> "Autostart (Security → Manage Apps)"
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> "Auto-startup (Settings → App Management)"
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> "Background App Management (i Manager → App Management)"
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> "Startup Manager (Settings → Battery → App Launch)"
            manufacturer.contains("oneplus") -> "Auto-launch (Settings → Apps → Auto-launch)"
            manufacturer.contains("samsung") -> "Sleeping Apps (Settings → Battery → Background Usage Limits)"
            else -> "Auto-start"
        }
    }

    /**
     * Key untuk SharedPreferences — sudah pernah ditampilkan?
     */
    private const val PREF_KEY = "auto_start_prompted"

    fun hasBeenPrompted(context: Context): Boolean {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)
    }

    fun markAsPrompted(context: Context) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, true).apply()
    }
}