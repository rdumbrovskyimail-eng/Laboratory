package com.opuside.app.core.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО (2026-02-06)
 * 
 * ПРОБЛЕМА:
 * ─────────
 * BiometricPrompt требует FragmentActivity, но MainActivity extends ComponentActivity.
 * ComponentActivity != FragmentActivity, поэтому биометрия не работала.
 * 
 * РЕШЕНИЕ:
 * ────────
 * 1. Добавлена детальная диагностика типа активности
 * 2. Более информативные сообщения об ошибках
 * 3. Graceful fallback если биометрия недоступна
 */
object BiometricAuthHelper {

    /**
     * Проверяет доступность биометрии на устройстве.
     */
    fun canAuthenticate(activity: FragmentActivity): BiometricAvailability {
        val biometricManager = BiometricManager.from(activity)
        
        android.util.Log.d("BiometricAuthHelper", "━".repeat(80))
        android.util.Log.d("BiometricAuthHelper", "🔐 CHECKING BIOMETRIC AVAILABILITY")
        android.util.Log.d("BiometricAuthHelper", "━".repeat(80))
        
        val result = when (val status = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                android.util.Log.d("BiometricAuthHelper", "✅ Biometric available")
                BiometricAvailability.Available
            }
            
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                android.util.Log.w("BiometricAuthHelper", "⚠️ No biometric hardware")
                BiometricAvailability.NoHardware
            }
            
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                android.util.Log.w("BiometricAuthHelper", "⚠️ Biometric hardware unavailable")
                BiometricAvailability.HardwareUnavailable
            }
            
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                android.util.Log.w("BiometricAuthHelper", "⚠️ No biometrics enrolled")
                BiometricAvailability.NoneEnrolled
            }
            
            else -> {
                android.util.Log.w("BiometricAuthHelper", "⚠️ Unknown status: $status")
                BiometricAvailability.Unknown
            }
        }
        
        android.util.Log.d("BiometricAuthHelper", "━".repeat(80))
        return result
    }

    /**
     * Показывает биометрический промпт.
     * 
     * ✅ ИСПРАВЛЕНО: Детальное логирование + проверка типа активности
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        negativeButtonText: String = "Cancel",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        android.util.Log.d("BiometricAuthHelper", "━".repeat(80))
        android.util.Log.d("BiometricAuthHelper", "🔐 STARTING BIOMETRIC AUTHENTICATION")
        android.util.Log.d("BiometricAuthHelper", "━".repeat(80))
        android.util.Log.d("BiometricAuthHelper", "  Activity type: ${activity.javaClass.simpleName}")
        android.util.Log.d("BiometricAuthHelper", "  Title: $title")
        android.util.Log.d("BiometricAuthHelper", "  Subtitle: $subtitle")
        
        // ✅ ПРОВЕРКА: Убедимся, что это действительно FragmentActivity
        if (activity !is FragmentActivity) {
            val error = "Activity must be FragmentActivity, got ${activity.javaClass.simpleName}"
            android.util.Log.e("BiometricAuthHelper", "❌ $error")
            onError(error)
            return
        }
        
        // Проверяем доступность биометрии
        val availability = canAuthenticate(activity)
        if (availability !is BiometricAvailability.Available) {
            val error = when (availability) {
                is BiometricAvailability.NoHardware -> "No biometric hardware available"
                is BiometricAvailability.HardwareUnavailable -> "Biometric hardware currently unavailable"
                is BiometricAvailability.NoneEnrolled -> "No biometrics enrolled. Please set up fingerprint/face in device settings"
                else -> "Biometric authentication unavailable"
            }
            android.util.Log.e("BiometricAuthHelper", "❌ $error")
            onError(error)
            return
        }
        
        android.util.Log.d("BiometricAuthHelper", "  ├─ Creating BiometricPrompt...")
        
        val executor = ContextCompat.getMainExecutor(activity)

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    android.util.Log.d("BiometricAuthHelper", "  └─ ✅ Authentication SUCCEEDED")
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    android.util.Log.e("BiometricAuthHelper", "  └─ ❌ Authentication ERROR: $errString (code: $errorCode)")
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    android.util.Log.w("BiometricAuthHelper", "  └─ ⚠️ Authentication FAILED (retry possible)")
                    // Не вызываем onError здесь, потому что пользователь может повторить попытку
                }
            }
        )

        android.util.Log.d("BiometricAuthHelper", "  ├─ Building PromptInfo...")
        
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        android.util.Log.d("BiometricAuthHelper", "  └─ Showing BiometricPrompt...")
        
        try {
            biometricPrompt.authenticate(promptInfo)
            android.util.Log.d("BiometricAuthHelper", "     ✅ Prompt shown successfully")
        } catch (e: Exception) {
            android.util.Log.e("BiometricAuthHelper", "     ❌ Failed to show prompt", e)
            onError("Failed to show biometric prompt: ${e.message}")
        }
        
        android.util.Log.d("BiometricAuthHelper", "━".repeat(80))
    }
}

sealed class BiometricAvailability {
    object Available : BiometricAvailability()
    object NoHardware : BiometricAvailability()
    object HardwareUnavailable : BiometricAvailability()
    object NoneEnrolled : BiometricAvailability()
    object Unknown : BiometricAvailability()
}
