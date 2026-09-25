package com.opuside.app.core.ai

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 🔷 GEMINI MODEL CONFIGURATION v3.2 (Speed & Stability Optimized)
 */
object GeminiModelConfig {

    private const val TAG = "GeminiModelConfig"

    const val OFFICIAL_CONTEXT_WINDOW = 1_048_576 // 1M tokens
    const val OFFICIAL_MAX_OUTPUT = 65_536        // 64K tokens
    const val ECO_OUTPUT_TOKENS = 16_384
    const val CACHE_TTL_MS = 5 * 60 * 1000L
    const val API_KEY_PREFIX = "AIza"

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

    enum class ThinkingLevel(val apiName: String, val displayName: String) {
        NONE("NONE", "Off"),
        LOW("LOW", "Low"),
        MEDIUM("MEDIUM", "Medium"),
        HIGH("HIGH", "High")
    }

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
        val forcedThinkingLevel: ThinkingLevel,
        val supportsGrounding: Boolean,
        val supportsCodeExecution: Boolean,
        val supportsFunctionCalling: Boolean,
        val supportsJsonMode: Boolean,
        val supportsSystemInstruction: Boolean,
        val supportsPresencePenalty: Boolean,
        val supportsFrequencyPenalty: Boolean,
        val supportsSeed: Boolean,
        val supportsResponseMimeType: Boolean,
        val supportsCaching: Boolean,
        val defaultThinkingLevel: ThinkingLevel,
        val speedRating: Int,
        val emoji: String
    ) {
        GEMINI_3_5_FLASH_LITE(
            modelId = "gemini-3.5-flash-lite",
            displayName = "3.5 Flash-Lite",
            description = "Быстрая агентская модель, 1M ввод, 64K вывод, LOW thinking",
            contextWindow = OFFICIAL_CONTEXT_WINDOW,
            maxOutputTokens = OFFICIAL_MAX_OUTPUT,
            inputPricePerM = 0.30,
            outputPricePerM = 2.50,
            longInputPricePerM = 0.30,
            longOutputPricePerM = 2.50,
            longContextThreshold = Int.MAX_VALUE,
            cacheReadPricePerM = 0.030,
            cacheStoragePricePerMPerHour = 1.00,
            supportsThinking = true,
            thinkingOutputPricePerM = 2.50,
            forcedThinkingLevel = ThinkingLevel.LOW,
            supportsGrounding = true,
            supportsCodeExecution = true,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            supportsPresencePenalty = false,
            supportsFrequencyPenalty = false,
            supportsSeed = true,
            supportsResponseMimeType = true,
            supportsCaching = true,
            defaultThinkingLevel = ThinkingLevel.LOW,
            speedRating = 10,
            emoji = "⚡"
        ),

        GEMINI_3_1_FLASH_LITE(
            modelId = "gemini-3.1-flash-lite",
            displayName = "3.1 Flash-Lite",
            description = "Ультра-бюджетная модель, 1M ввод, 64K вывод, LOW thinking (быстрый отклик)",
            contextWindow = OFFICIAL_CONTEXT_WINDOW,
            maxOutputTokens = OFFICIAL_MAX_OUTPUT,
            inputPricePerM = 0.25,
            outputPricePerM = 1.50,
            longInputPricePerM = 0.25,
            longOutputPricePerM = 1.50,
            longContextThreshold = Int.MAX_VALUE,
            cacheReadPricePerM = 0.025,
            cacheStoragePricePerMPerHour = 1.00,
            supportsThinking = true,
            thinkingOutputPricePerM = 1.50,
            forcedThinkingLevel = ThinkingLevel.LOW, // Оптимизировано: LOW устраняет задержки
            supportsGrounding = true,
            supportsCodeExecution = false,
            supportsFunctionCalling = true,
            supportsJsonMode = true,
            supportsSystemInstruction = true,
            supportsPresencePenalty = false,
            supportsFrequencyPenalty = false,
            supportsSeed = true,
            supportsResponseMimeType = true,
            supportsCaching = true,
            defaultThinkingLevel = ThinkingLevel.LOW,
            speedRating = 9,
            emoji = "💨"
        );

        fun getEffectiveOutputTokens(ecoMode: Boolean): Int =
            if (ecoMode) minOf(ECO_OUTPUT_TOKENS, maxOutputTokens) else maxOutputTokens

        fun getMaxInputTokens(ecoMode: Boolean): Int =
            contextWindow - getEffectiveOutputTokens(ecoMode)

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
            val savingsEUR = savingsUSD * usdToEur

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
                cacheSavingsEUR = savingsEUR
            )
        }

        companion object {
            fun fromModelId(modelId: String): GeminiModel? {
                val clean = modelId.trim().lowercase()
                return entries.find { it.modelId.equals(clean, ignoreCase = true) }
                    ?: when {
                        clean.contains("3.1") -> GEMINI_3_1_FLASH_LITE
                        clean.contains("3.5") -> GEMINI_3_5_FLASH_LITE
                        else -> null
                    }
            }

            fun getDefault(): GeminiModel = GEMINI_3_5_FLASH_LITE
            fun getActiveModels(): List<GeminiModel> = entries.toList()
        }
    }

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
    }

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

        val duration: Long get() = (endTime ?: Instant.now()).epochSecond - startTime.epochSecond

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
        fun addMessage(inputTokens: Int, outputTokens: Int, thinkingTokens: Int = 0, cachedReadTokens: Int = 0) {
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
            appendLine("Model: ${model.displayName} ${model.emoji}")
            appendLine("Duration: $durationFormatted")
            appendLine("Messages: $messageCount")
            appendLine("Total Tokens: ${"%,d".format(totalInputTokens + totalOutputTokens + totalThinkingTokens)}")
            appendLine("Total Cost: €${String.format(java.util.Locale.US, "%.4f", currentCost.totalCostEUR)}")
        }
    }

    object SessionManager {
        private const val TAG = "GeminiSessionMgr"
        private val sessions = ConcurrentHashMap<String, GeminiSession>()

        fun createSession(sessionId: String, model: GeminiModel): GeminiSession =
            sessions.getOrPut(sessionId) {
                GeminiSession(sessionId = sessionId, model = model, startTime = Instant.now())
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
            return cleaned
        }
    }

    data class GenerationConfig(
        val temperature: Float? = null, // Оптимизировано: null = дефолт Gemini 3.x
        val topP: Float = 0.95f,
        val topK: Int = 40,
        val maxOutputTokens: Int = OFFICIAL_MAX_OUTPUT,
        val stopSequences: List<String> = emptyList(),
        val responseMimeType: String? = null,
        val responseSchema: String? = null,
        val presencePenalty: Float = 0f,
        val frequencyPenalty: Float = 0f,
        val seed: Int? = null,
        val thinkingLevel: ThinkingLevel = ThinkingLevel.LOW,
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

            val FULL = GenerationConfig(maxOutputTokens = OFFICIAL_MAX_OUTPUT)
            val MAX = GenerationConfig(maxOutputTokens = OFFICIAL_MAX_OUTPUT)
            val ECO = GenerationConfig(maxOutputTokens = ECO_OUTPUT_TOKENS)
            val CODE = GenerationConfig(maxOutputTokens = OFFICIAL_MAX_OUTPUT, topP = 0.8f)
            val CREATIVE = GenerationConfig(maxOutputTokens = OFFICIAL_MAX_OUTPUT, topP = 0.95f, topK = 64)
            val JSON = GenerationConfig(maxOutputTokens = OFFICIAL_MAX_OUTPUT, responseMimeType = "application/json")
        }
    }
}