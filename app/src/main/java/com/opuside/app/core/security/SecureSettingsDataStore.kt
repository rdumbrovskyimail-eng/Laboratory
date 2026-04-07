package com.opuside.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricPrompt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "secure_settings_encrypted"
)

@Singleton
class SecureSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SecureSettings"
        private const val KEYSTORE_ALIAS = "opuside_master_key"
        private const val KEYSTORE_ALIAS_BIOMETRIC = "opuside_biometric_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        private val KEY_PERSISTENT_ENCRYPTION_KEY = stringPreferencesKey("persistent_encryption_key_v2")
        private val KEY_PERSISTENT_KEY_IV = stringPreferencesKey("persistent_key_iv_v2")

        private val KEY_ANTHROPIC_API = stringPreferencesKey("anthropic_api_encrypted_v2")
        private val KEY_ANTHROPIC_IV = stringPreferencesKey("anthropic_iv_v2")

        private val KEY_GITHUB_TOKEN = stringPreferencesKey("github_token_encrypted_v2")
        private val KEY_GITHUB_IV = stringPreferencesKey("github_token_iv_v2")

        private val KEY_GEMINI_API = stringPreferencesKey("gemini_api_encrypted_v1")
        private val KEY_GEMINI_IV = stringPreferencesKey("gemini_api_iv_v1")

        private val KEY_DEEPSEEK_API = stringPreferencesKey("deepseek_api_encrypted_v1")
        private val KEY_DEEPSEEK_IV = stringPreferencesKey("deepseek_api_iv_v1")

        // ✅ Gemini
        private val KEY_GEMINI_API = stringPreferencesKey("gemini_api_encrypted_v1")
        private val KEY_GEMINI_IV = stringPreferencesKey("gemini_api_iv_v1")

        private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")

        private val KEY_GITHUB_OWNER = stringPreferencesKey("github_owner")
        private val KEY_GITHUB_REPO = stringPreferencesKey("github_repo")
        private val KEY_GITHUB_BRANCH = stringPreferencesKey("github_branch")
    }

    private val dataStore = context.secureDataStore
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private var isDeviceSecure = false
    private var isRooted = false

    private var cachedEncryptionKey: SecretKey? = null

    init {
        isDeviceSecure = SecurityUtils.isDeviceSecure(context)
        isRooted = SecurityUtils.isDeviceRooted()

        if (isRooted) {
            android.util.Log.w(TAG, "⚠️ Device is rooted - security compromised!")
        }

        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "🔐 SecureSettingsDataStore INITIALIZED")
        android.util.Log.d(TAG, "   Device Secure: $isDeviceSecure")
        android.util.Log.d(TAG, "   Rooted: $isRooted")
        android.util.Log.d(TAG, "━".repeat(80))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENT ENCRYPTION KEY
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun getPersistentEncryptionKey(): SecretKey = withContext(Dispatchers.IO) {
        cachedEncryptionKey?.let { return@withContext it }

        android.util.Log.d(TAG, "🔑 Loading persistent encryption key...")

        val prefs = dataStore.data.first()
        val storedKey = prefs[KEY_PERSISTENT_ENCRYPTION_KEY]
        val storedIV = prefs[KEY_PERSISTENT_KEY_IV]

        if (storedKey != null && storedIV != null) {
            android.util.Log.d(TAG, "   Found stored key, decrypting...")
            try {
                val keystoreKey = getKeystoreKey(false)
                if (keystoreKey != null) {
                    val decryptedKeyBytes = decryptWithKeystoreKey(storedKey, storedIV, keystoreKey)
                    if (decryptedKeyBytes != null) {
                        val key = SecretKeySpec(decryptedKeyBytes, "AES")
                        cachedEncryptionKey = key
                        android.util.Log.d(TAG, "   ✅ Persistent key loaded successfully")
                        return@withContext key
                    }
                }
                android.util.Log.w(TAG, "   ⚠️ Failed to decrypt stored key, generating new one")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "   ❌ Error loading stored key", e)
            }
        }

        android.util.Log.d(TAG, "   Generating new persistent key...")
        val newKey = generatePersistentKey()

        try {
            val keystoreKey = getKeystoreKey(false)
            if (keystoreKey != null) {
                val encrypted = encryptWithKeystoreKey(newKey.encoded, keystoreKey)
                dataStore.edit { prefs ->
                    prefs[KEY_PERSISTENT_ENCRYPTION_KEY] = encrypted.ciphertext
                    prefs[KEY_PERSISTENT_KEY_IV] = encrypted.iv
                }
                android.util.Log.d(TAG, "   ✅ New persistent key saved")
            } else {
                android.util.Log.e(TAG, "   ❌ Cannot save persistent key - Keystore unavailable")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "   ❌ Failed to save persistent key", e)
        }

        cachedEncryptionKey = newKey
        newKey
    }

    private fun generatePersistentKey(): SecretKey {
        val keyGen = javax.crypto.KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    private fun getKeystoreKey(requireBiometric: Boolean): SecretKey? {
        val alias = if (requireBiometric) KEYSTORE_ALIAS_BIOMETRIC else KEYSTORE_ALIAS
        return try {
            if (keyStore.containsAlias(alias)) {
                (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            } else {
                android.util.Log.d(TAG, "   Generating Keystore key: $alias")
                generateKeystoreKey(alias, requireBiometric)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Failed to get Keystore key", e)
            null
        }
    }

    private fun generateKeystoreKey(alias: String, requireBiometric: Boolean): SecretKey {
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

    // ═══════════════════════════════════════════════════════════════════════════
    // ENCRYPTION / DECRYPTION
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun encryptData(plaintext: String): EncryptedData = withContext(Dispatchers.IO) {
        try {
            val secretKey = getPersistentEncryptionKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val encrypted = EncryptedData(
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                iv = Base64.encodeToString(iv, Base64.NO_WRAP)
            )
            android.util.Log.d(TAG, "🔐 Data encrypted (${plaintext.length} bytes → ${encrypted.ciphertext.length} chars)")
            encrypted
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Encryption failed", e)
            throw SecurityException("Encryption failed: ${e.message}")
        }
    }

    private suspend fun decryptData(encryptedData: EncryptedData): String? = withContext(Dispatchers.IO) {
        try {
            val secretKey = getPersistentEncryptionKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(encryptedData.iv, Base64.NO_WRAP)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val ciphertext = Base64.decode(encryptedData.ciphertext, Base64.NO_WRAP)
            val plaintext = cipher.doFinal(ciphertext)
            val decrypted = String(plaintext, Charsets.UTF_8)
            android.util.Log.d(TAG, "🔓 Data decrypted (${decrypted.length} bytes)")
            decrypted
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Decryption failed", e)
            null
        }
    }

    private fun encryptWithKeystoreKey(data: ByteArray, key: SecretKey): EncryptedData {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)
        return EncryptedData(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    private fun decryptWithKeystoreKey(ciphertext: String, iv: String, key: SecretKey): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
            val spec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val ciphertextBytes = Base64.decode(ciphertext, Base64.NO_WRAP)
            cipher.doFinal(ciphertextBytes)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to decrypt with Keystore key", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL SAVE HELPER — устраняет дублирование кода
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun saveEncryptedKey(
        label: String,
        value: String,
        ciphertextPref: Preferences.Key<String>,
        ivPref: Preferences.Key<String>
    ) = withContext(Dispatchers.IO) {
        android.util.Log.d(TAG, "━".repeat(60))
        android.util.Log.d(TAG, "💾 SAVING $label (length: ${value.length})")

        val encrypted = encryptData(value)

        dataStore.edit { prefs ->
            prefs[ciphertextPref] = encrypted.ciphertext
            prefs[ivPref] = encrypted.iv
        }

        val verification = dataStore.data.first()
        if (verification[ciphertextPref] == encrypted.ciphertext) {
            android.util.Log.d(TAG, "  └─ ✅ $label SAVED AND VERIFIED")
        } else {
            android.util.Log.e(TAG, "  └─ ❌ $label VERIFICATION FAILED!")
            throw SecurityException("Failed to save $label")
        }
    }

    private fun getDecryptedKeyFlow(
        label: String,
        ciphertextPref: Preferences.Key<String>,
        ivPref: Preferences.Key<String>
    ): Flow<String> = dataStore.data
        .map { prefs ->
            val ciphertext = prefs[ciphertextPref]
            val iv = prefs[ivPref]
            android.util.Log.d(TAG, "🔍 Loading $label... has=${ciphertext != null}")
            if (ciphertext == null || iv == null) return@map ""
            decryptData(EncryptedData(ciphertext, iv)) ?: ""
        }
        .catch {
            android.util.Log.e(TAG, "Flow error in get$label", it)
            emit("")
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — ANTHROPIC
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun setAnthropicApiKey(key: String, useBiometric: Boolean = false) {
        saveEncryptedKey("ANTHROPIC API KEY", key, KEY_ANTHROPIC_API, KEY_ANTHROPIC_IV)
        dataStore.edit { prefs -> prefs[KEY_BIOMETRIC_ENABLED] = useBiometric }
    }

    fun getAnthropicApiKey(): Flow<String> =
        getDecryptedKeyFlow("AnthropicApiKey", KEY_ANTHROPIC_API, KEY_ANTHROPIC_IV)

    fun getAnthropicApiKeyWithBiometric(
        activity: FragmentActivity,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        android.util.Log.d(TAG, "🔐 Biometric auth requested for Anthropic API key")

        val prefs = runCatching {
            kotlinx.coroutines.runBlocking { dataStore.data.first() }
        }.getOrNull()

        val useBiometric = prefs?.get(KEY_BIOMETRIC_ENABLED) ?: false

        if (!useBiometric) {
            try {
                val key = kotlinx.coroutines.runBlocking { getAnthropicApiKey().first() }
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
                android.util.Log.d(TAG, "✅ Biometric auth successful")
                try {
                    val key = kotlinx.coroutines.runBlocking { getAnthropicApiKey().first() }
                    onSuccess(key)
                } catch (e: Exception) {
                    onError("Decryption failed: ${e.message}")
                }
            },
            onError = { error ->
                android.util.Log.e(TAG, "❌ Biometric auth failed: $error")
                onError(error)
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — DEEPSEEK
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun setDeepSeekApiKey(key: String) =
        saveEncryptedKey("DEEPSEEK API KEY", key, KEY_DEEPSEEK_API, KEY_DEEPSEEK_IV)

    fun getDeepSeekApiKey(): Flow<String> =
        getDecryptedKeyFlow("DeepSeekApiKey", KEY_DEEPSEEK_API, KEY_DEEPSEEK_IV)

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — GEMINI
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun setGeminiApiKey(key: String, useBiometric: Boolean = false) {
        saveEncryptedKey("GEMINI API KEY", key, KEY_GEMINI_API, KEY_GEMINI_IV)
        dataStore.edit { prefs -> prefs[KEY_BIOMETRIC_ENABLED] = useBiometric }
    }

    fun getGeminiApiKey(): Flow<String> =
        getDecryptedKeyFlow("GeminiApiKey", KEY_GEMINI_API, KEY_GEMINI_IV)

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — GITHUB TOKEN
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun setGitHubToken(token: String, useBiometric: Boolean = false) =
        saveEncryptedKey("GITHUB TOKEN", token, KEY_GITHUB_TOKEN, KEY_GITHUB_IV)

    fun getGitHubToken(): Flow<String> =
        getDecryptedKeyFlow("GitHubToken", KEY_GITHUB_TOKEN, KEY_GITHUB_IV)

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API — GITHUB CONFIG
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun setGitHubConfig(owner: String, repo: String, branch: String = "main") =
        withContext(Dispatchers.IO) {
            android.util.Log.d(TAG, "💾 Saving GitHub config: $owner/$repo ($branch)")
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
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun isBiometricEnabled(): Boolean {
        return try {
            dataStore.data.first()[KEY_BIOMETRIC_ENABLED] ?: false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun clearSecureData() {
        dataStore.edit { it.clear() }
        keyStore.deleteEntry(KEYSTORE_ALIAS)
        keyStore.deleteEntry(KEYSTORE_ALIAS_BIOMETRIC)
        cachedEncryptionKey = null
        android.util.Log.d(TAG, "🗑️ All secure data cleared")
    }

    suspend fun verifyDataIntegrity(): Boolean {
        return try {
            getAnthropicApiKey().first()
            getGitHubToken().first()
            getDeepSeekApiKey().first()
            getGeminiApiKey().first()
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