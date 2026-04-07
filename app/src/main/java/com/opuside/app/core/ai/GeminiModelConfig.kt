package com.opuside.app.core.ai

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 🔷 GEMINI MODEL CONFIGURATION v2.0 (April 2026)
 *
 * All active Gemini API models (non-Live) as of 2026-04-07.
 * Pricing source: ai.google.dev/gemini-api/docs/pricing (updated 2026-04-06).
 * Specs source: ai.google.dev/gemini-api/docs/gemini-3 (Gemini 3 Developer Guide)
 *
 * v2.0 CHANGES:
 * - Fixed 3.1 Flash-Lite pricing ($0.25/$1.50, was $0.10/$0.40)
 * - Fixed 2.5 Flash pricing ($0.30/$2.50 flat, was $0.15/$0.60 tiered)
 * - Fixed 3 Flash thinking price ($3.00, was $3.50)
 * - Enabled thinking for 3.1 Flash-Lite and 2.5 Flash-Lite
 * - Added per-model capability flags for UI validation
 * - Added supportsPresencePenalty, supportsFrequencyPenalty, supportsSeed
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
    // THINKING LEVELS
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
    // Pricing verified: ai.google.dev/gemini-api/docs/pricing (2026-04-06)
    // ═══════════════════════════════════════════════════════════════════

    enum class GeminiModel(
        val modelId: String,
        val displayName: String,
        val description: String,
        val contextWindow: Int,
        val maxOutputTokens: Int,
        val inputPricePerM: Double,
        val outputPricePerM: Double,
        val longInputPricePerM: Double,
        val longOutputPricePerM: Double,
        val longContextThreshold: Int,
        val cacheReadPricePerM: Double,
        val cacheStoragePricePerMPerHour: Double,
        val supportsThinking: Boolean,
        val thinkingOutputPricePerM: Double,
        val supportsGrounding: Boolean,
        val supportsCodeExecution: Boolean,
        val supportsFunctionCalling: Boolean,
        val supportsJsonMode: Boolean,
        val supportsSystemInstruction: Boolean,
        // ── Per-model capability flags for UI validation ─────────────
        val supportsPresencePenalty: Boolean,
        val supportsFrequencyPenalty: Boolean,
        val supportsSeed: Boolean,
        val supportsResponseMimeType: Boolean,
        val supportsCaching: Boolean,
        val defaultThinkingLevel: ThinkingLevel,
        val speedRating: Int,
        val emoji: String,
        val deprecated: Boolean = false,
        val deprecationDate: String? = null
    ) {
        // ── Gemini 3.1 Pro Preview ──────────────────────────────────
        // Pricing: $2/$12 (≤200K), $4/$18 (>200K)
        // Output includes thinking at same rate
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
            supportsPresencePenalty = true,
            supportsFrequencyPenalty = true,
            supportsSeed = true,
            supportsResponseMimeType = true,
            supportsCaching = true,
            defaultThinkingLevel = ThinkingLevel.HIGH,
            speedRating = 4,
            emoji = "🧠"
        ),

        // ── Gemini 3.1 Pro Custom Tools ─────────────────────────────
        // Same pricing as 3.1 Pro, optimized for custom tool priority
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
            supportsPresencePenalty = true,
            supportsFrequencyPenalty = true,
            supportsSeed = true,
            supportsResponseMimeType = true,
            supportsCaching = true,
            defaultThinkingLevel = ThinkingLevel.HIGH,
            speedRating = 4,
            emoji = "🔧"
        ),

        // ── Gemini 3 Flash Preview ──────────────────────────────────
        // Pricing: $0.50/$3.00 FLAT (no long context tier)
        // Output includes thinking at $3.00
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
            thinkingOutputPricePerM = 3.00,
            supportsGrounding = true,
            supportsCodeExecution = true,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            supportsPresencePenalty = true,
            supportsFrequencyPenalty = true,
            supportsSeed = true,
            supportsResponseMimeType = true,
            supportsCaching = true,
            defaultThinkingLevel = ThinkingLevel.HIGH,
            speedRating = 7,
            emoji = "⚡"
        ),

        // ── Gemini 3.1 Flash-Lite Preview ───────────────────────────
        // Pricing: $0.25/$1.50 FLAT (verified 2026-04-06)
        // Supports thinking: minimal(default), low, medium, high
        GEMINI_3_1_FLASH_LITE(
            modelId = "gemini-3.1-flash-lite-preview",
            displayName = "3.1 Flash-Lite",
            description = "Cost-efficient, high volume, flat pricing",
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            inputPricePerM = 0.25,
            outputPricePerM = 1.50,
            longInputPricePerM = 0.25,
            longOutputPricePerM = 1.50,
            longContextThreshold = Int.MAX_VALUE,
            cacheReadPricePerM = 0.025,
            cacheStoragePricePerMPerHour = 1.00,
            supportsThinking = true,
            thinkingOutputPricePerM = 1.50,
            supportsGrounding = true,
            supportsCodeExecution = false,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            supportsPresencePenalty = false,
            supportsFrequencyPenalty = false,
            supportsSeed = false,
            supportsResponseMimeType = true,
            supportsCaching = true,
            defaultThinkingLevel = ThinkingLevel.MINIMAL,
            speedRating = 9,
            emoji = "💨"
        ),

        // ── Gemini 2.5 Pro (auto-updated alias) ────────────────────
        // Pricing: $1.25/$10 (≤200K), $2.50/$15 (>200K)
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
            supportsPresencePenalty = true,
            supportsFrequencyPenalty = true,
            supportsSeed = true,
            supportsResponseMimeType = true,
            supportsCaching = true,
            defaultThinkingLevel = ThinkingLevel.HIGH,
            speedRating = 5,
            emoji = "🏆"
        ),

        // ── Gemini 2.5 Flash (auto-updated alias) ──────────────────
        // Pricing: $0.30/$2.50 FLAT (verified 2026-04-06, was $0.15/$0.60 tiered)
        // Output price includes thinking tokens at same rate
        GEMINI_2_5_FLASH(
            modelId = "gemini-2.5-flash",
            displayName = "2.5 Flash",
            description = "Hybrid reasoning, fast, versatile",
            contextWindow = 1_000_000,
            maxOutputTokens = 65_536,
            inputPricePerM = 0.30,
            outputPricePerM = 2.50,
            longInputPricePerM = 0.30,
            longOutputPricePerM = 2.50,
            longContextThreshold = Int.MAX_VALUE,
            cacheReadPricePerM = 0.03,
            cacheStoragePricePerMPerHour = 1.00,
            supportsThinking = true,
            thinkingOutputPricePerM = 2.50,
            supportsGrounding = true,
            supportsCodeExecution = true,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            supportsPresencePenalty = true,
            supportsFrequencyPenalty = true,
            supportsSeed = true,
            supportsResponseMimeType = true,
            supportsCaching = true,
            defaultThinkingLevel = ThinkingLevel.HIGH,
            speedRating = 8,
            emoji = "⚡"
        ),

        // ── Gemini 2.5 Flash-Lite (auto-updated alias) ─────────────
        // Pricing: $0.10/$0.40 FLAT (verified 2026-04-06)
        // Supports thinking with controllable budgets (per Google blog)
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
            supportsThinking = true,
            thinkingOutputPricePerM = 0.40,
            supportsGrounding = true,
            supportsCodeExecution = false,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            supportsPresencePenalty = false,
            supportsFrequencyPenalty = false,
            supportsSeed = false,
            supportsResponseMimeType = true,
            supportsCaching = true,
            defaultThinkingLevel = ThinkingLevel.MINIMAL,
            speedRating = 10,
            emoji = "🪶"
        );

        fun getEffectiveOutputTokens(ecoMode: Boolean): Int =
            if (ecoMode) minOf(ECO_OUTPUT_TOKENS, maxOutputTokens) else maxOutputTokens

        fun getMaxInputTokens(ecoMode: Boolean): Int =
            contextWindow - getEffectiveOutputTokens(ecoMode)

        /**
         * Validate a GenerationConfig against this model's capabilities.
         * Returns list of warning messages (empty = all OK).
         */
        fun validateConfig(config: GenerationConfig): List<String> {
            val warnings = mutableListOf<String>()

            if (config.thinkingLevel != ThinkingLevel.NONE && !supportsThinking) {
                warnings.add("$displayName does not support Thinking")
            }

            if (config.presencePenalty != 0f && !supportsPresencePenalty) {
                warnings.add("$displayName does not support Presence Penalty")
            }

            if (config.frequencyPenalty != 0f && !supportsFrequencyPenalty) {
                warnings.add("$displayName does not support Frequency Penalty")
            }

            if (config.seed != null && !supportsSeed) {
                warnings.add("$displayName does not support Seed")
            }

            if (config.responseMimeType != null && !supportsResponseMimeType) {
                warnings.add("$displayName does not support Response Format selection")
            }

            if (config.maxOutputTokens > maxOutputTokens) {
                warnings.add("Max output ${config.maxOutputTokens} exceeds model limit $maxOutputTokens")
            }

            return warnings
        }

        /**
         * Get supported thinking levels for this model.
         * Returns only levels this model actually supports.
         */
        fun getSupportedThinkingLevels(): List<ThinkingLevel> {
            if (!supportsThinking) return listOf(ThinkingLevel.NONE)
            return ThinkingLevel.entries.toList()
        }

        /**
         * Calculate cost for a single API call.
         * For models with flat output pricing (output includes thinking),
         * thinkingOutputPricePerM == outputPricePerM.
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
        val responseMimeType: String? = null,
        val responseSchema: String? = null,
        val presencePenalty: Float = 0f,
        val frequencyPenalty: Float = 0f,
        val seed: Int? = null,
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
