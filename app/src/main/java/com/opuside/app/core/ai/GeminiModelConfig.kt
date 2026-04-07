package com.opuside.app.core.ai

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 🔷 GEMINI MODEL CONFIGURATION v1.0 (April 2026)
 *
 * All active Gemini API models (non-Live) as of 2026-04-07.
 * Pricing source: ai.google.dev/gemini-api/docs/pricing (updated 2026-04-06).
 *
 * Context caching:
 * - Cache read = 0.1× base input price
 * - Cache storage = $1.00–$4.50 per 1M tokens/hour (model-dependent)
 * - Min cacheable: 4096 tokens (all models)
 *
 * Deprecation schedule:
 * - Gemini 2.0 Flash / 2.0 Flash-Lite → June 1, 2026
 * - All Imagen models → June 24, 2026
 */
object GeminiModelConfig {

    private const val TAG = "GeminiModelConfig"

    const val ECO_OUTPUT_TOKENS = 8192
    const val CACHE_TTL_MS = 5 * 60 * 1000L   // 5 min (Google default)
    const val API_KEY_PREFIX = "AIza"

    // ═══════════════════════════════════════════════════════════════════
    // SAFETY SETTINGS
    // ═══════════════════════════════════════════════════════════════════

    enum class HarmCategory(val apiName: String, val displayName: String) {
        HARASSMENT("HARM_CATEGORY_HARASSMENT", "Harassment"),
        HATE_SPEECH("HARM_CATEGORY_HATE_SPEECH", "Hate Speech"),
        SEXUALLY_EXPLICIT("HARM_CATEGORY_SEXUALLY_EXPLICIT", "Sexually Explicit"),
        DANGEROUS_CONTENT("HARM_CATEGORY_DANGEROUS_CONTENT", "Dangerous Content"),
        CIVIC_INTEGRITY("HARM_CATEGORY_CIVIC_INTEGRITY", "Civic Integrity")
    }

    enum class SafetyThreshold(val apiName: String, val displayName: String) {
        BLOCK_NONE("BLOCK_NONE", "Off"),
        BLOCK_ONLY_HIGH("BLOCK_ONLY_HIGH", "Low"),
        BLOCK_MEDIUM_AND_ABOVE("BLOCK_MEDIUM_AND_ABOVE", "Med"),
        BLOCK_LOW_AND_ABOVE("BLOCK_LOW_AND_ABOVE", "High")
    }

    // ═══════════════════════════════════════════════════════════════════
    // THINKING LEVELS (Gemini 3.1 uses thinkingLevel, not thinkingBudget)
    // ═══════════════════════════════════════════════════════════════════

    enum class ThinkingLevel(val apiName: String, val displayName: String) {
        NONE("NONE", "Off"),
        MINIMAL("MINIMAL", "Minimal"),
        LOW("LOW", "Low"),
        MEDIUM("MEDIUM", "Medium"),
        HIGH("HIGH", "High")
    }

    // ═══════════════════════════════════════════════════════════════════
    // FINISH REASONS
    // ═══════════════════════════════════════════════════════════════════

    enum class FinishReason(val apiName: String) {
        STOP("STOP"),
        MAX_TOKENS("MAX_TOKENS"),
        SAFETY("SAFETY"),
        RECITATION("RECITATION"),
        OTHER("OTHER"),
        BLOCKLIST("BLOCKLIST"),
        PROHIBITED_CONTENT("PROHIBITED_CONTENT"),
        SPII("SPII");

        companion object {
            fun fromApi(value: String?): FinishReason? =
                value?.let { v -> entries.find { it.apiName == v } }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODELS — all active non-Live models as of April 2026
    // ═══════════════════════════════════════════════════════════════════

    enum class GeminiModel(
        val modelId: String,
        val displayName: String,
        val description: String,
        val contextWindow: Int,
        val maxOutputTokens: Int,
        val inputPricePerM: Double,        // USD per 1M input tokens (≤200K)
        val outputPricePerM: Double,       // USD per 1M output tokens (≤200K)
        val longInputPricePerM: Double,    // USD per 1M input tokens (>200K)
        val longOutputPricePerM: Double,   // USD per 1M output tokens (>200K)
        val longContextThreshold: Int,     // Int.MAX_VALUE = flat pricing (no tier)
        val cacheReadPricePerM: Double,    // 0.1× base input
        val cacheStoragePricePerMPerHour: Double,
        val supportsThinking: Boolean,
        val thinkingOutputPricePerM: Double,
        val supportsGrounding: Boolean,
        val supportsCodeExecution: Boolean,
        val supportsFunctionCalling: Boolean,
        val supportsJsonMode: Boolean,
        val supportsSystemInstruction: Boolean,
        val speedRating: Int,              // 1–10, 10=fastest
        val emoji: String,
        val deprecated: Boolean = false,
        val deprecationDate: String? = null
    ) {
        // ── Gemini 3.1 Pro Preview ──────────────────────────────────
        GEMINI_3_1_PRO(
            modelId = "gemini-3.1-pro-preview",
            displayName = "3.1 Pro Preview",
            description = "Flagship reasoning, 1M context, best quality",
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            inputPricePerM = 2.00,
            outputPricePerM = 12.00,
            longInputPricePerM = 4.00,
            longOutputPricePerM = 18.00,
            longContextThreshold = 200_000,
            cacheReadPricePerM = 0.20,
            cacheStoragePricePerMPerHour = 4.50,
            supportsThinking = true,
            thinkingOutputPricePerM = 12.00,
            supportsGrounding = true,
            supportsCodeExecution = true,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            speedRating = 4,
            emoji = "🧠"
        ),

        // ── Gemini 3.1 Pro Custom Tools ─────────────────────────────
        GEMINI_3_1_PRO_CUSTOMTOOLS(
            modelId = "gemini-3.1-pro-preview-customtools",
            displayName = "3.1 Pro CustomTools",
            description = "Optimized for custom tool priority",
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            inputPricePerM = 2.00,
            outputPricePerM = 12.00,
            longInputPricePerM = 4.00,
            longOutputPricePerM = 18.00,
            longContextThreshold = 200_000,
            cacheReadPricePerM = 0.20,
            cacheStoragePricePerMPerHour = 4.50,
            supportsThinking = true,
            thinkingOutputPricePerM = 12.00,
            supportsGrounding = true,
            supportsCodeExecution = true,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            speedRating = 4,
            emoji = "🔧"
        ),

        // ── Gemini 3 Flash Preview ──────────────────────────────────
        GEMINI_3_FLASH(
            modelId = "gemini-3-flash-preview",
            displayName = "3 Flash Preview",
            description = "Frontier speed + intelligence, flat pricing",
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            inputPricePerM = 0.50,
            outputPricePerM = 3.00,
            longInputPricePerM = 0.50,
            longOutputPricePerM = 3.00,
            longContextThreshold = Int.MAX_VALUE,
            cacheReadPricePerM = 0.05,
            cacheStoragePricePerMPerHour = 1.00,
            supportsThinking = true,
            thinkingOutputPricePerM = 3.50,
            supportsGrounding = true,
            supportsCodeExecution = true,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            speedRating = 7,
            emoji = "⚡"
        ),

        // ── Gemini 3.1 Flash-Lite Preview ───────────────────────────
        GEMINI_3_1_FLASH_LITE(
            modelId = "gemini-3.1-flash-lite-preview",
            displayName = "3.1 Flash-Lite",
            description = "Budget-friendly, high volume, flat pricing",
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            inputPricePerM = 0.10,
            outputPricePerM = 0.40,
            longInputPricePerM = 0.10,
            longOutputPricePerM = 0.40,
            longContextThreshold = Int.MAX_VALUE,
            cacheReadPricePerM = 0.01,
            cacheStoragePricePerMPerHour = 0.25,
            supportsThinking = false,
            thinkingOutputPricePerM = 0.0,
            supportsGrounding = true,
            supportsCodeExecution = false,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            speedRating = 9,
            emoji = "💨"
        ),

        // ── Gemini 2.5 Pro (auto-updated alias) ────────────────────
        GEMINI_2_5_PRO(
            modelId = "gemini-2.5-pro",
            displayName = "2.5 Pro",
            description = "Best for coding, proven quality, 1M context",
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            inputPricePerM = 1.25,
            outputPricePerM = 10.00,
            longInputPricePerM = 2.50,
            longOutputPricePerM = 15.00,
            longContextThreshold = 200_000,
            cacheReadPricePerM = 0.125,
            cacheStoragePricePerMPerHour = 4.50,
            supportsThinking = true,
            thinkingOutputPricePerM = 10.00,
            supportsGrounding = true,
            supportsCodeExecution = true,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            speedRating = 5,
            emoji = "🏆"
        ),

        // ── Gemini 2.5 Flash (auto-updated alias) ──────────────────
        GEMINI_2_5_FLASH(
            modelId = "gemini-2.5-flash",
            displayName = "2.5 Flash",
            description = "Hybrid reasoning, fast, versatile",
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            inputPricePerM = 0.15,
            outputPricePerM = 0.60,
            longInputPricePerM = 0.30,
            longOutputPricePerM = 1.20,
            longContextThreshold = 200_000,
            cacheReadPricePerM = 0.015,
            cacheStoragePricePerMPerHour = 1.00,
            supportsThinking = true,
            thinkingOutputPricePerM = 3.50,
            supportsGrounding = true,
            supportsCodeExecution = true,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            speedRating = 8,
            emoji = "⚡"
        ),

        // ── Gemini 2.5 Flash-Lite (auto-updated alias) ─────────────
        GEMINI_2_5_FLASH_LITE(
            modelId = "gemini-2.5-flash-lite",
            displayName = "2.5 Flash-Lite",
            description = "Most affordable, highest speed",
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            inputPricePerM = 0.10,
            outputPricePerM = 0.40,
            longInputPricePerM = 0.10,
            longOutputPricePerM = 0.40,
            longContextThreshold = Int.MAX_VALUE,
            cacheReadPricePerM = 0.01,
            cacheStoragePricePerMPerHour = 0.25,
            supportsThinking = false,
            thinkingOutputPricePerM = 0.0,
            supportsGrounding = true,
            supportsCodeExecution = false,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            speedRating = 10,
            emoji = "🪶"
        );

        fun getEffectiveOutputTokens(ecoMode: Boolean): Int =
            if (ecoMode) minOf(ECO_OUTPUT_TOKENS, maxOutputTokens) else maxOutputTokens

        fun getMaxInputTokens(ecoMode: Boolean): Int =
            contextWindow - getEffectiveOutputTokens(ecoMode)

        /**
         * Calculate cost for a single API call.
         * Gemini charges thinking tokens at thinkingOutputPricePerM.
         */
        fun calculateCost(
            inputTokens: Int,
            outputTokens: Int,
            thinkingTokens: Int = 0,
            cachedReadTokens: Int = 0,
            usdToEur: Double = 0.92
        ): GeminiCost {
            val isLong = inputTokens > longContextThreshold
            val actualInputPrice = if (isLong) longInputPricePerM else inputPricePerM
            val actualOutputPrice = if (isLong) longOutputPricePerM else outputPricePerM

            val regularInputTokens = (inputTokens - cachedReadTokens).coerceAtLeast(0)
            val regularInputCostUSD = (regularInputTokens / 1_000_000.0) * actualInputPrice
            val cacheReadCostUSD = (cachedReadTokens / 1_000_000.0) * cacheReadPricePerM
            val outputCostUSD = (outputTokens / 1_000_000.0) * actualOutputPrice
            val thinkingCostUSD = (thinkingTokens / 1_000_000.0) * thinkingOutputPricePerM

            val totalCostUSD = regularInputCostUSD + cacheReadCostUSD + outputCostUSD + thinkingCostUSD

            val withoutCacheCostUSD = if (cachedReadTokens > 0)
                (cachedReadTokens / 1_000_000.0) * actualInputPrice else 0.0
            val savingsUSD = withoutCacheCostUSD - cacheReadCostUSD

            return GeminiCost(
                model = this,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                thinkingTokens = thinkingTokens,
                cachedReadTokens = cachedReadTokens,
                isLongContext = isLong,
                totalCostUSD = totalCostUSD,
                totalCostEUR = totalCostUSD * usdToEur,
                cacheSavingsUSD = savingsUSD,
                cacheSavingsEUR = savingsUSD * usdToEur
            )
        }

        companion object {
            fun fromModelId(modelId: String): GeminiModel? =
                entries.find { it.modelId == modelId }

            fun getDefault(): GeminiModel = GEMINI_2_5_FLASH

            fun getActiveModels(): List<GeminiModel> =
                entries.filter { !it.deprecated }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // COST DATA CLASS
    // ═══════════════════════════════════════════════════════════════════

    data class GeminiCost(
        val model: GeminiModel,
        val inputTokens: Int,
        val outputTokens: Int,
        val thinkingTokens: Int,
        val cachedReadTokens: Int,
        val isLongContext: Boolean,
        val totalCostUSD: Double,
        val totalCostEUR: Double,
        val cacheSavingsUSD: Double,
        val cacheSavingsEUR: Double
    ) {
        val totalTokens: Int = inputTokens + outputTokens + thinkingTokens

        operator fun plus(other: GeminiCost): GeminiCost = GeminiCost(
            model = model,
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            thinkingTokens = thinkingTokens + other.thinkingTokens,
            cachedReadTokens = cachedReadTokens + other.cachedReadTokens,
            isLongContext = isLongContext || other.isLongContext,
            totalCostUSD = totalCostUSD + other.totalCostUSD,
            totalCostEUR = totalCostEUR + other.totalCostEUR,
            cacheSavingsUSD = cacheSavingsUSD + other.cacheSavingsUSD,
            cacheSavingsEUR = cacheSavingsEUR + other.cacheSavingsEUR
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // SESSION
    // ═══════════════════════════════════════════════════════════════════

    data class GeminiSession(
        val sessionId: String,
        val model: GeminiModel,
        val startTime: Instant,
        var endTime: Instant? = null,
        var totalInputTokens: Int = 0,
        var totalOutputTokens: Int = 0,
        var totalThinkingTokens: Int = 0,
        var totalCachedReadTokens: Int = 0,
        var messageCount: Int = 0,
        var isActive: Boolean = true
    ) {
        private var _cachedCost: GeminiCost? = null
        private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        val duration: Long get() =
            (endTime ?: Instant.now()).epochSecond - startTime.epochSecond

        val durationFormatted: String get() {
            val s = duration
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m ${sec}s"
                else -> "${sec}s"
            }
        }

        val currentCost: GeminiCost
            get() = _cachedCost ?: model.calculateCost(
                totalInputTokens, totalOutputTokens, totalThinkingTokens, totalCachedReadTokens
            ).also { _cachedCost = it }

        @Synchronized
        fun addMessage(
            inputTokens: Int,
            outputTokens: Int,
            thinkingTokens: Int = 0,
            cachedReadTokens: Int = 0
        ) {
            totalInputTokens += inputTokens
            totalOutputTokens += outputTokens
            totalThinkingTokens += thinkingTokens
            totalCachedReadTokens += cachedReadTokens
            messageCount++
            _cachedCost = null
        }

        @Synchronized
        fun end() {
            isActive = false
            endTime = Instant.now()
        }

        fun getDetailedStats(): String = buildString {
            appendLine("📊 Gemini Session Statistics")
            appendLine()
            appendLine("Model: ${model.displayName} ${model.emoji}")
            appendLine("Duration: $durationFormatted")
            appendLine("Messages: $messageCount")
            appendLine("Total Tokens: ${"%,d".format(totalInputTokens + totalOutputTokens + totalThinkingTokens)}")
            appendLine("  Input: ${"%,d".format(totalInputTokens)}")
            appendLine("  Output: ${"%,d".format(totalOutputTokens)}")
            if (totalThinkingTokens > 0)
                appendLine("  Thinking: ${"%,d".format(totalThinkingTokens)}")
            if (totalCachedReadTokens > 0)
                appendLine("  Cache Read: ${"%,d".format(totalCachedReadTokens)}")
            appendLine()
            appendLine("Total Cost: €${String.format("%.4f", currentCost.totalCostEUR)}")
            if (currentCost.cacheSavingsEUR > 0)
                appendLine("Savings: €${String.format("%.4f", currentCost.cacheSavingsEUR)}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SESSION MANAGER
    // ═══════════════════════════════════════════════════════════════════

    object SessionManager {
        private const val TAG = "GeminiSessionMgr"
        private val sessions = ConcurrentHashMap<String, GeminiSession>()

        fun createSession(sessionId: String, model: GeminiModel): GeminiSession =
            sessions.getOrPut(sessionId) {
                GeminiSession(sessionId = sessionId, model = model, startTime = Instant.now()).also {
                    Log.i(TAG, "Created Gemini session: $sessionId [${model.displayName}]")
                }
            }

        fun getSession(sessionId: String): GeminiSession? = sessions[sessionId]

        fun endSession(sessionId: String): GeminiSession? {
            val s = sessions.remove(sessionId)
            s?.end()
            return s
        }

        fun cleanupOldSessions(maxAge: Duration = Duration.ofDays(1)): Int {
            val now = Instant.now()
            var cleaned = 0
            sessions.values.toList().forEach { s ->
                val shouldClean = if (!s.isActive)
                    Duration.between(s.endTime ?: now, now) > maxAge
                else
                    Duration.between(s.startTime, now) > Duration.ofHours(24)
                if (shouldClean) {
                    if (s.isActive) s.end()
                    sessions.remove(s.sessionId)
                    cleaned++
                }
            }
            if (cleaned > 0) Log.i(TAG, "Cleaned $cleaned old Gemini sessions")
            return cleaned
        }

        fun clear() { sessions.clear() }
    }

    // ═══════════════════════════════════════════════════════════════════
    // GENERATION CONFIG (AI Studio-compatible parameters)
    // ═══════════════════════════════════════════════════════════════════

    data class GenerationConfig(
        val temperature: Float = 1.0f,
        val topP: Float = 0.95f,
        val topK: Int = 40,
        val maxOutputTokens: Int = 8192,
        val stopSequences: List<String> = emptyList(),
        val responseMimeType: String? = null,       // "application/json", "text/plain", null=auto
        val responseSchema: String? = null,          // JSON schema string (for JSON mode)
        val presencePenalty: Float = 0f,             // -2.0 to 2.0
        val frequencyPenalty: Float = 0f,            // -2.0 to 2.0
        val seed: Int? = null,                       // deterministic output if set
        val thinkingLevel: ThinkingLevel = ThinkingLevel.NONE,
        val safetySettings: Map<HarmCategory, SafetyThreshold> = defaultSafetySettings()
    ) {
        companion object {
            fun defaultSafetySettings(): Map<HarmCategory, SafetyThreshold> = mapOf(
                HarmCategory.HARASSMENT to SafetyThreshold.BLOCK_MEDIUM_AND_ABOVE,
                HarmCategory.HATE_SPEECH to SafetyThreshold.BLOCK_MEDIUM_AND_ABOVE,
                HarmCategory.SEXUALLY_EXPLICIT to SafetyThreshold.BLOCK_MEDIUM_AND_ABOVE,
                HarmCategory.DANGEROUS_CONTENT to SafetyThreshold.BLOCK_MEDIUM_AND_ABOVE,
                HarmCategory.CIVIC_INTEGRITY to SafetyThreshold.BLOCK_MEDIUM_AND_ABOVE
            )

            val ECO = GenerationConfig(maxOutputTokens = 8192, temperature = 0.7f)
            val MAX = GenerationConfig(maxOutputTokens = 65_536, temperature = 1.0f)
            val CODE = GenerationConfig(maxOutputTokens = 65_536, temperature = 0.2f, topP = 0.8f)
            val CREATIVE = GenerationConfig(
                maxOutputTokens = 65_536,
                temperature = 1.5f,
                topP = 0.95f,
                topK = 64
            )
            val JSON = GenerationConfig(
                maxOutputTokens = 8192,
                temperature = 0.0f,
                responseMimeType = "application/json"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════

    fun isValidApiKey(key: String): Boolean =
        key.isNotBlank() && key.startsWith(API_KEY_PREFIX) && key.length > 20

    fun maskApiKey(key: String): String =
        if (key.isBlank()) "❌ Not set"
        else if (key.length > 12) "✅ ${key.take(8)}...${key.takeLast(4)}"
        else "✅ ${key.take(4)}..."
}
