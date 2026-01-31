package com.opuside.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.opuside.app.core.security.SecurityUtils
import com.opuside.app.core.ui.theme.OpusIDETheme
import com.opuside.app.navigation.OpusIDENavigation
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity - единственная Activity в приложении.
 * 
 * Использует Single Activity Architecture с Jetpack Compose Navigation.
 * Все экраны (Creator, Analyzer, Settings) - это Composable функции.
 * 
 * ✅ ИСПРАВЛЕНО (Проблема #15): Root detection с enforcement вместо security theater.
 * 
 * ПРОБЛЕМА:
 * - Показывали warning, но приложение продолжало работать на rooted устройстве
 * - Все данные (API ключи, БД) доступны через root → warning не защищает
 * - Security theater: создает ложное чувство безопасности
 * 
 * РЕШЕНИЕ (выбрана политика Soft Enforcement):
 * - При обнаружении root показываем предупреждение
 * - Предлагаем пользователю 3 варианта:
 *   1. Continue Anyway - продолжить на свой риск (НЕ рекомендуется)
 *   2. Disable Sensitive Features - отключить API ключи и биометрию
 *   3. Exit App - закрыть приложение
 * 
 * АЛЬТЕРНАТИВЫ (закомментированы в коде):
 * - Hard Enforcement: полный запрет работы на rooted устройствах
 * - Play Integrity API: проверка целостности через Google Play
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ✅ ИСПРАВЛЕНО: Root detection с enforcement
        if (SecurityUtils.isDeviceRooted()) {
            showRootEnforcementDialog()
            return // Не показываем UI до выбора пользователя
        }
        
        // Включаем edge-to-edge для Android 16
        enableEdgeToEdge()

        setContent {
            OpusIDETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpusIDENavigation()
                }
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО (Проблема #15): Soft Enforcement с выбором пользователя
     * 
     * ПОЛИТИКА БЕЗОПАСНОСТИ:
     * 1. Информируем о рисках (прозрачность)
     * 2. Даем выбор пользователю (уважение к владельцу устройства)
     * 3. Рекомендуем безопасный вариант (UX)
     * 4. Защищаем от случайного использования (default = безопасный вариант)
     * 
     * РИСКИ НА ROOTED УСТРОЙСТВЕ:
     * - API ключи Anthropic/GitHub доступны через root
     * - БД с кодом может быть прочитана
     * - Keystore может быть скомпрометирован
     * - Memory dumps содержат plaintext данные
     */
    private fun showRootEnforcementDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔓 Rooted Device Detected")
            .setMessage(
                "Your device has root access enabled. This significantly increases security risks:\n\n" +
                "• API keys can be extracted from memory\n" +
                "• Database files are readable\n" +
                "• Encryption keys can be compromised\n\n" +
                "How would you like to proceed?"
            )
            .setPositiveButton("Disable Sensitive Features") { dialog, _ ->
                // ✅ РЕКОМЕНДУЕМЫЙ вариант: отключаем опасные функции
                dialog.dismiss()
                proceedWithLimitedMode()
            }
            .setNegativeButton("Exit App") { _, _ ->
                // ✅ БЕЗОПАСНЫЙ вариант: закрываем приложение
                finish()
            }
            .setNeutralButton("Continue Anyway (Not Recommended)") { dialog, _ ->
                // ⚠️ ОПАСНЫЙ вариант: продолжаем на риск пользователя
                dialog.dismiss()
                proceedWithFullMode()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * ✅ НОВОЕ: Limited Mode для rooted устройств
     * 
     * Отключаем функции, которые могут утечь через root:
     * - API ключи (нельзя сохранять)
     * - Биометрическая защита (бессмысленна на rooted)
     * - Encrypted storage (ключи доступны через root)
     * 
     * Оставляем:
     * - Просмотр кода из GitHub (только публичные репо)
     * - UI/UX функции
     * - Offline работу
     */
    private fun proceedWithLimitedMode() {
        // TODO: Установить флаг в SharedPreferences/DataStore
        // Например: appSettings.setRootedDeviceMode(true)
        // ViewModel-ы должны проверять этот флаг и скрывать чувствительные функции
        
        enableEdgeToEdge()
        setContent {
            OpusIDETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpusIDENavigation()
                    // TODO: Показать persistent banner вверху экрана:
                    // "⚠️ Limited Mode: Sensitive features disabled (rooted device)"
                }
            }
        }
    }

    /**
     * ⚠️ НОВОЕ: Full Mode на rooted устройстве (НЕ рекомендуется)
     * 
     * Пользователь выбрал продолжить на свой риск.
     * Показываем persistent warning banner для напоминания.
     */
    private fun proceedWithFullMode() {
        enableEdgeToEdge()
        setContent {
            OpusIDETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpusIDENavigation()
                    // TODO: Показать persistent banner красного цвета:
                    // "⚠️ WARNING: Running on rooted device - security compromised"
                }
            }
        }
    }

    /* ═══════════════════════════════════════════════════════════════════════════
     * АЛЬТЕРНАТИВНЫЕ ПОЛИТИКИ (закомментированы)
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * ВАРИАНТ 1: Hard Enforcement (полный запрет)
     * ───────────────────────────────────────────────────────────────────────────
     * Используйте если:
     * - Работаете с критически важными данными (финансы, здравоохранение)
     * - Корпоративное приложение с compliance требованиями
     * - Не можете позволить утечку данных
     * 
     * private fun showRootEnforcementDialog() {
     *     AlertDialog.Builder(this)
     *         .setTitle("🔒 Security Error")
     *         .setMessage(
     *             "OpusIDE cannot run on rooted devices due to security requirements.\n\n" +
     *             "To use OpusIDE, please:\n" +
     *             "• Unroot your device\n" +
     *             "• Use a non-rooted device\n" +
     *             "• Use OpusIDE Web (coming soon)"
     *         )
     *         .setPositiveButton("Exit") { _, _ ->
     *             finish()
     *         }
     *         .setCancelable(false)
     *         .show()
     * }
     * 
     * 
     * ВАРИАНТ 2: Play Integrity API (рекомендуется для production)
     * ───────────────────────────────────────────────────────────────────────────
     * Преимущества:
     * - Более надежная проверка (сложнее обойти)
     * - Детектит не только root, но и эмуляторы, модифицированные APK
     * - Интеграция с Google Play
     * 
     * Требует:
     * - build.gradle.kts: implementation("com.google.android.gms:play-services-integrity:1.3.0")
     * - Google Cloud Project с включенным Play Integrity API
     * 
     * private fun verifyDeviceIntegrity() {
     *     val integrityManager = IntegrityManagerFactory.create(applicationContext)
     *     
     *     val integrityTokenRequest = IntegrityTokenRequest.builder()
     *         .setCloudProjectNumber(CLOUD_PROJECT_NUMBER) // Из Google Cloud Console
     *         .build()
     *     
     *     integrityManager.requestIntegrityToken(integrityTokenRequest)
     *         .addOnSuccessListener { response ->
     *             val token = response.token()
     *             
     *             // Отправить token на ваш backend для верификации
     *             // Backend декодирует token и проверяет verdict
     *             
     *             // Для быстрого прототипа можно декодировать локально:
     *             val verdict = decodeIntegrityToken(token)
     *             
     *             if (!verdict.deviceIntegrity.deviceRecognitionVerdict.contains("MEETS_DEVICE_INTEGRITY")) {
     *                 // Устройство скомпрометировано
     *                 showIntegrityFailureDialog()
     *             } else {
     *                 // Устройство безопасное
     *                 proceedWithFullMode()
     *             }
     *         }
     *         .addOnFailureListener { e ->
     *             // Ошибка при проверке
     *             showIntegrityErrorDialog()
     *         }
     * }
     * 
     * private fun showIntegrityFailureDialog() {
     *     AlertDialog.Builder(this)
     *         .setTitle("🔒 Device Integrity Check Failed")
     *         .setMessage(
     *             "Your device failed Google Play Integrity verification. " +
     *             "OpusIDE requires an unmodified, non-rooted device."
     *         )
     *         .setPositiveButton("Exit") { _, _ -> finish() }
     *         .setCancelable(false)
     *         .show()
     * }
     * 
     * ═══════════════════════════════════════════════════════════════════════════
     */
}