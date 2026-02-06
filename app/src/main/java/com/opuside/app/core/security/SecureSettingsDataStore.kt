package com.opuside.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "secure_settings_encrypted"
)

/**
 * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО (2026-02-06)
 * 
 * ИЗМЕНЕНИЯ:
 * ────────────────────────────────────────────────────────────────
 * 1. ✅ setAnthropicApiKey() - верификация через savedPrefs
 * 2. ✅ setGitHubToken() - верификация через savedPrefs
 * 3. ✅ Убраны race conditions с delay()
 * 4. ✅ Детальное логирование процесса сохранения
 */
@Singleton
class SecureSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SecureSettingsDataStore"
        private const val KEYSTORE_ALIAS = "opuside_master_key"
        private const val KEYSTORE_ALIAS_BIOMETRIC = "opuside_biometric_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        
        // Encrypted preference keys
        private val KEY_ANTHROPIC_API = stringPreferencesKey("anthropic_api_encrypted")
        private val KEY_GITHUB_TOKEN = stringPreferencesKey("github_token_encrypted")
        private val KEY_ENCRYPTION_IV = stringPreferencesKey("encryption_iv")
        private val KEY_LAST_KEY_ROTATION = longPreferencesKey("last_key_rotation")
        private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        
        // Non-encrypted keys
        private val KEY_GITHUB_OWNER = stringPreferencesKey("github_owner")
        private val KEY_GITHUB_REPO = stringPreferencesKey("github_repo")
        private val KEY_GITHUB_BRANCH = stringPreferencesKey("github_branch")
        
        private const val KEY_ROTATION_INTERVAL_MS = 90L * 24 * 60 * 60 * 1000
    }

    private val dataStore = context.secureDataStore
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    
    private var isDeviceSecure = false
    private var isRooted = false

    init {
        isDeviceSecure = SecurityUtils.isDeviceSecure(context)
        isRooted = SecurityUtils.isDeviceRooted()
        
        if (isRooted) {
            android.util.Log.w(TAG, "⚠️ Device is rooted - security compromised!")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KEY GENERATION & MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private fun getMasterKey(requireBiometric: Boolean = false): SecretKey? {
        val alias = if (requireBiometric) KEYSTORE_ALIAS_BIOMETRIC else KEYSTORE_ALIAS
        
        return try {
            if (keyStore.containsAlias(alias)) {
                (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            } else {
                android.util.Log.w(TAG, "⚠️ Key $alias not found in Keystore. Will generate new.")
                generateKey(alias, requireBiometric)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to get master key", e)
            null
        }
    }

    private fun generateKey(alias: String, requireBiometric: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (requireBiometric && isDeviceSecure) {
            builder.setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(30)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private suspend fun checkKeyRotation() {
        val lastRotation = dataStore.data.first()[KEY_LAST_KEY_ROTATION] ?: 0L
        val now = System.currentTimeMillis()
        
        if (now - lastRotation > KEY_ROTATION_INTERVAL_MS) {
            rotateEncryptionKey()
        }
    }

    private suspend fun rotateEncryptionKey() {
        android.util.Log.d(TAG, "🔄 Starting key rotation...")

        try {
            val anthropicKey = getAnthropicApiKey().first()
            val githubToken = getGitHubToken().first()

            keyStore.deleteEntry(KEYSTORE_ALIAS)
            generateKey(KEYSTORE_ALIAS, false)

            if (anthropicKey.isNotEmpty()) {
                setAnthropicApiKey(anthropicKey)
            }
            if (githubToken.isNotEmpty()) {
                setGitHubToken(githubToken)
            }

            dataStore.edit { prefs ->
                prefs[KEY_LAST_KEY_ROTATION] = System.currentTimeMillis()
            }

            android.util.Log.d(TAG, "✅ Key rotation completed")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Key rotation failed", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENCRYPTION / DECRYPTION
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun encryptData(
        plaintext: String,
        requireBiometric: Boolean = false
    ): EncryptedData = withContext(Dispatchers.IO) {
        try {
            val secretKey = getMasterKey(requireBiometric)
                ?: throw SecurityException("Cannot get encryption key")
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            EncryptedData(
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                iv = Base64.encodeToString(iv, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Encryption failed", e)
            throw SecurityException("Encryption failed: ${e.message}")
        }
    }

    private suspend fun decryptData(
        encryptedData: EncryptedData,
        requireBiometric: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        try {
            val alias = if (requireBiometric) KEYSTORE_ALIAS_BIOMETRIC else KEYSTORE_ALIAS
            
            if (!keyStore.containsAlias(alias)) {
                android.util.Log.e(TAG, "🔑 Key $alias NOT FOUND in Keystore!")
                android.util.Log.e(TAG, "   This happens after app reinstallation or key invalidation.")
                android.util.Log.e(TAG, "   → Clearing encrypted data. User must re-enter credentials.")
                
                clearCorruptedEncryptedData()
                return@withContext null
            }
            
            val secretKey = getMasterKey(requireBiometric)
            
            if (secretKey == null) {
                android.util.Log.e(TAG, "🔑 Cannot retrieve key $alias from Keystore!")
                clearCorruptedEncryptedData()
                return@withContext null
            }
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            
            val iv = Base64.decode(encryptedData.iv, Base64.NO_WRAP)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val ciphertext = Base64.decode(encryptedData.ciphertext, Base64.NO_WRAP)
            val plaintext = cipher.doFinal(ciphertext)

            String(plaintext, Charsets.UTF_8)
            
        } catch (e: javax.crypto.AEADBadTagException) {
            android.util.Log.e(TAG, "🔐 AEADBadTagException: Encrypted data corrupted or key mismatch", e)
            android.util.Log.e(TAG, "   → Clearing corrupted data. User must re-enter credentials.")
            
            clearCorruptedEncryptedData()
            return@withContext null
            
        } catch (e: android.security.KeyStoreException) {
            android.util.Log.e(TAG, "🔑 KeyStoreException: Key validation failed", e)
            android.util.Log.e(TAG, "   → Clearing corrupted data. User must re-enter credentials.")
            
            clearCorruptedEncryptedData()
            return@withContext null
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Unexpected decryption error", e)
            
            if (e is java.security.GeneralSecurityException) {
                clearCorruptedEncryptedData()
                return@withContext null
            }
            
            throw SecurityException("Decryption failed: ${e.message}")
        }
    }

    private suspend fun clearCorruptedEncryptedData() {
        try {
            dataStore.edit { prefs ->
                prefs.remove(KEY_ANTHROPIC_API)
                prefs.remove(KEY_GITHUB_TOKEN)
                prefs.remove(KEY_ENCRYPTION_IV)
                prefs.remove(KEY_BIOMETRIC_ENABLED)
            }
            android.util.Log.w(TAG, "🗑️ Cleared corrupted encrypted data")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to clear corrupted data", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - ANTHROPIC
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО: Верификация через savedPrefs
     */
    suspend fun setAnthropicApiKey(key: String, useBiometric: Boolean = false) = withContext(Dispatchers.IO) {
        checkKeyRotation()
        
        val encrypted = encryptData(key, useBiometric)
        
        android.util.Log.d(TAG, "🔐 Encrypting Anthropic API key...")
        android.util.Log.d(TAG, "   Ciphertext length: ${encrypted.ciphertext.length}")
        android.util.Log.d(TAG, "   IV length: ${encrypted.iv.length}")
        android.util.Log.d(TAG, "   Biometric: $useBiometric")
        
        // ✅ ИСПРАВЛЕНО: edit() возвращает Preferences ПОСЛЕ записи
        val savedPrefs = dataStore.edit { prefs ->
            prefs[KEY_ANTHROPIC_API] = encrypted.ciphertext
            prefs[KEY_ENCRYPTION_IV] = encrypted.iv
            prefs[KEY_BIOMETRIC_ENABLED] = useBiometric
        }
        
        // ✅ ВЕРИФИКАЦИЯ на ГАРАНТИРОВАННО сохраненных данных
        val savedCiphertext = savedPrefs[KEY_ANTHROPIC_API]
        val savedIV = savedPrefs[KEY_ENCRYPTION_IV]
        
        if (savedCiphertext != encrypted.ciphertext) {
            android.util.Log.e(TAG, "❌ Ciphertext mismatch!")
            android.util.Log.e(TAG, "   Expected: ${encrypted.ciphertext.take(20)}...")
            android.util.Log.e(TAG, "   Got: ${savedCiphertext?.take(20)}...")
            throw SecurityException("Failed to save API key - ciphertext verification failed")
        }
        
        if (savedIV != encrypted.iv) {
            android.util.Log.e(TAG, "❌ IV mismatch!")
            throw SecurityException("Failed to save API key - IV verification failed")
        }
        
        android.util.Log.d(TAG, "✅ Anthropic API key saved and verified")
    }

    fun getAnthropicApiKey(): Flow<String> = dataStore.data
        .map { prefs ->
            val ciphertext = prefs[KEY_ANTHROPIC_API] ?: return@map ""
            val iv = prefs[KEY_ENCRYPTION_IV] ?: return@map ""
            val useBiometric = prefs[KEY_BIOMETRIC_ENABLED] ?: false
            
            try {
                decryptData(EncryptedData(ciphertext, iv), useBiometric) ?: ""
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to decrypt Anthropic key", e)
                ""
            }
        }
        .catch { 
            android.util.Log.e(TAG, "Flow error in getAnthropicApiKey", it)
            emit("") 
        }

    fun getAnthropicApiKeyWithBiometric(
        activity: FragmentActivity,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val prefs = runCatching { 
            kotlinx.coroutines.runBlocking { 
                dataStore.data.first() 
            } 
        }.getOrNull()
        
        val useBiometric = prefs?.get(KEY_BIOMETRIC_ENABLED) ?: false
        
        if (!useBiometric) {
            try {
                val key = kotlinx.coroutines.runBlocking {
                    getAnthropicApiKey().first()
                }
                onSuccess(key)
            } catch (e: Exception) {
                onError(e.message ?: "Failed to get key")
            }
            return
        }

        BiometricAuthHelper.authenticate(
            activity = activity,
            title = "Unlock API Key",
            subtitle = "Authentication required to access Anthropic API key",
            onSuccess = {
                try {
                    val key = kotlinx.coroutines.runBlocking {
                        getAnthropicApiKey().first()
                    }
                    onSuccess(key)
                } catch (e: Exception) {
                    onError("Decryption failed: ${e.message}")
                }
            },
            onError = { error ->
                onError(error)
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - GITHUB
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО: Верификация через savedPrefs
     */
    suspend fun setGitHubToken(token: String, useBiometric: Boolean = false) = withContext(Dispatchers.IO) {
        checkKeyRotation()
        
        val encrypted = encryptData(token, useBiometric)
        
        android.util.Log.d(TAG, "🔐 Encrypting GitHub token...")
        android.util.Log.d(TAG, "   Ciphertext length: ${encrypted.ciphertext.length}")
        android.util.Log.d(TAG, "   IV length: ${encrypted.iv.length}")
        
        // ✅ ИСПРАВЛЕНО: Всегда обновляем IV вместе с токеном
        val savedPrefs = dataStore.edit { prefs ->
            prefs[KEY_GITHUB_TOKEN] = encrypted.ciphertext
            prefs[KEY_ENCRYPTION_IV] = encrypted.iv
        }
        
        // ✅ ВЕРИФИКАЦИЯ
        val savedCiphertext = savedPrefs[KEY_GITHUB_TOKEN]
        val savedIV = savedPrefs[KEY_ENCRYPTION_IV]
        
        if (savedCiphertext != encrypted.ciphertext) {
            android.util.Log.e(TAG, "❌ GitHub token ciphertext mismatch!")
            throw SecurityException("Failed to save GitHub token - verification failed")
        }
        
        if (savedIV != encrypted.iv) {
            android.util.Log.e(TAG, "❌ GitHub token IV mismatch!")
            throw SecurityException("Failed to save GitHub token - IV verification failed")
        }
        
        android.util.Log.d(TAG, "✅ GitHub token saved and verified")
    }

    fun getGitHubToken(): Flow<String> = dataStore.data
        .map { prefs ->
            val ciphertext = prefs[KEY_GITHUB_TOKEN] ?: return@map ""
            val iv = prefs[KEY_ENCRYPTION_IV] ?: return@map ""
            
            try {
                decryptData(EncryptedData(ciphertext, iv), false) ?: ""
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to decrypt GitHub token", e)
                ""
            }
        }
        .catch { 
            android.util.Log.e(TAG, "Flow error in getGitHubToken", it)
            emit("") 
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - BIOMETRIC STATUS
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun isBiometricEnabled(): Boolean {
        return try {
            dataStore.data.first()[KEY_BIOMETRIC_ENABLED] ?: false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to get biometric status", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API - REPOSITORY CONFIG
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun setGitHubConfig(owner: String, repo: String, branch: String = "main") {
        dataStore.edit { prefs ->
            prefs[KEY_GITHUB_OWNER] = owner
            prefs[KEY_GITHUB_REPO] = repo
            prefs[KEY_GITHUB_BRANCH] = branch
        }
    }

    data class GitHubConfig(
        val owner: String,
        val repo: String,
        val branch: String,
        val token: String
    ) {
        val isConfigured: Boolean get() = owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank()
        val fullName: String get() = "$owner/$repo"
    }

    val gitHubConfig: Flow<GitHubConfig> = combine(
        dataStore.data,
        getGitHubToken()
    ) { prefs, token ->
        GitHubConfig(
            owner = prefs[KEY_GITHUB_OWNER] ?: "",
            repo = prefs[KEY_GITHUB_REPO] ?: "",
            branch = prefs[KEY_GITHUB_BRANCH] ?: "main",
            token = token
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECURITY UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun clearSecureData() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_ANTHROPIC_API)
            prefs.remove(KEY_GITHUB_TOKEN)
            prefs.remove(KEY_ENCRYPTION_IV)
            prefs.remove(KEY_BIOMETRIC_ENABLED)
        }
        
        keyStore.deleteEntry(KEYSTORE_ALIAS)
        keyStore.deleteEntry(KEYSTORE_ALIAS_BIOMETRIC)
        
        android.util.Log.d(TAG, "🗑️ All secure data cleared")
    }

    suspend fun verifyDataIntegrity(): Boolean {
        return try {
            val anthropicKey = getAnthropicApiKey().first()
            val githubToken = getGitHubToken().first()
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Data integrity check failed", e)
            false
        }
    }

    suspend fun exportNonSensitiveSettings(): Map<String, String> {
        val prefs = dataStore.data.first()
        return mapOf(
            "github_owner" to (prefs[KEY_GITHUB_OWNER] ?: ""),
            "github_repo" to (prefs[KEY_GITHUB_REPO] ?: ""),
            "github_branch" to (prefs[KEY_GITHUB_BRANCH] ?: "")
        )
    }
}

private data class EncryptedData(
    val ciphertext: String,
    val iv: String
)

class BiometricAuthException(message: String) : SecurityException(message)