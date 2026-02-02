package com.opuside.app.core.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import java.io.File

/**
 * Утилиты для проверки безопасности устройства.
 */
object SecurityUtils {

    /**
     * Проверяет, установлен ли экран блокировки.
     */
    fun isDeviceSecure(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        
        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL or 
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Проверяет наличие root на устройстве.
     * 
     * ВНИМАНИЕ: Это базовая проверка. Продвинутый root может её обойти.
     * Для production используйте SafetyNet Attestation API.
     */
    fun isDeviceRooted(): Boolean {
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3()
    }

    private fun checkRootMethod1(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkRootMethod2(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkRootMethod3(): Boolean {
        return try {
            Runtime.getRuntime().exec("su")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Проверяет, запущено ли приложение в эмуляторе.
     */
    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || "google_sdk" == Build.PRODUCT)
    }

    /**
     * Проверяет, включен ли режим разработчика.
     */
    fun isDeveloperModeEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
    }

    /**
     * Проверяет, активна ли USB отладка.
     */
    fun isAdbEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED,
            0
        ) == 1
    }
}
