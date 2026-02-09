package com.opuside.app.core.ai

import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 🤖 CLAUDE MODEL CONFIGURATION v3.0 (HAIKU UPDATE)
 * 
 * ✅ ОБНОВЛЕНО:
 * - Добавлена Haiku 4.5 (мгновенная, $0.80/$4)
 * - Исправлен Opus 4.6 modelId
 * - 4 модели в порядке скорости
 * 
 * Поддержка 4 моделей:
 * - Haiku 4.5 (мгновенная, $0.80/$4)
 * - Sonnet 4.5 (быстрая, $3/$15)
 * - Opus 4.5 (мощная, $5/$25)
 * - Opus 4.6 (новейшая, $5/$25)
 * 
 * Оптимизации:
 * ✅ Prompt Caching (90% экономия)
 * ✅ Auto-Haiku (85% экономия на простых задачах)
 * ✅ Batch API (50% скидка)
 * ✅ Управление сеансами
 * ✅ Предупреждение о длинном контексте
 */
object ClaudeModelConfig {
    
    private const val TAG = "ClaudeModelConfig"
    
    enum class ClaudeModel(
        val modelId: String,
        val displayName: String,
        val description: String,
        val inputPricePerM: Double,
        val outputPricePerM: Double,
        val longInputPricePerM: Double,
        val longOutputPricePerM: Double,
        val cachedInputPricePerM: Double,
        val longContextThreshold: Int,
        val maxTokens: Int,
        val speedRating: Int,
        val emoji: String
    ) {
        HAIKU_4_5(
            modelId = "claude-haiku-4-5-20251001",
            displayName = "Haiku 4.5",
            description = "Мгновенная, для простых задач",
            inputPricePerM = 0.80,
            outputPricePerM = 4.0,
            longInputPricePerM = 1.60,
            longOutputPricePerM = 6.0,
            cachedInputPricePerM = 0.08,
            longContextThreshold = 200_000,
            maxTokens = 200_000,
            speedRating = 10,
            emoji = "💨"
        ),
        
        SONNET_4_5(
            modelId = "claude-sonnet-4-5-20250514",
            displayName = "Sonnet 4.5",
            description = "Быстрая и экономичная",
            inputPricePerM = 3.0,
            outputPricePerM = 15.0,
            longInputPricePerM = 6.0,
            longOutputPricePerM = 22.5,
            cachedInputPricePerM = 0.30,
            longContextThreshold = 200_000,
            maxTokens = 1_000_000,
            speedRating = 5,
            emoji = "⚡"
        ),
        
        OPUS_4_5(
            modelId = "claude-opus-4-5-20250514",
            displayName = "Opus 4.5",
            description = "Мощная, для сложных задач",
            inputPricePerM = 5.0,
            outputPricePerM = 25.0,
            longInputPricePerM = 10.0,
            longOutputPricePerM = 37.5,
            cachedInputPricePerM = 0.50,
            longContextThreshold = 200_000,
            maxTokens = 1_000_000,
            speedRating = 3,
            emoji = "🔥"
        ),
        
        OPUS_4_6(
            modelId = "claude-opus-4-6",
            displayName = "Opus 4.6",
            description = "Новейшая, для кодирования",
            inputPricePerM = 5.0,
            outputPricePerM = 25.0,
            longInputPricePerM = 10.0,
            longOutputPricePerM = 37.5,
            cachedInputPricePerM = 0.50,
            longContextThreshold = 200_000,
            maxTokens = 1_000_000,
            speedRating = 3,
            emoji = "🚀"
        );
        
        companion object {
            /**
             * ✅ НОВОЕ: Получить модель по ID
             */
            fun fromModelId(modelId: String): ClaudeModel? {
                return entries.find { it.modelId == modelId }
            }
            
            /**
             * ✅ НОВОЕ: Список всех modelId для dropdown
             */
            fun getAllModelIds(): List<String> {
                return entries.map { it.modelId }
            }
            
            /**
             * ✅ НОВОЕ: Список с отображаемыми именами для UI
             */
            fun getAllModelsWithNames(): List<Pair<String, String>> {
                return entries.map { 
                    it.modelId to "${it.emoji} ${it.displayName}"
                }
            }
        }
        
        fun calculateCost(
            inputTokens: Int,
            outputTokens: Int,
            cachedInputTokens: Int = 0,
            usdToEur: Double = 0.92
        ): ModelCost {
            require(inputTokens >= 0) { "Input tokens cannot be negative: $inputTokens" }
            require(outputTokens >= 0) { "Output tokens cannot be negative: $outputTokens" }
            require(cachedInputTokens >= 0) { "Cached tokens cannot be negative: $cachedInputTokens" }
            require(cachedInputTokens <= inputTokens) { 
                "Cached tokens ($cachedInputTokens) cannot exceed input tokens ($inputTokens)" 
            }
            require(usdToEur > 0) { "USD to EUR rate must be positive: $usdToEur" }
            
            Log.d(TAG, "Calculating cost for ${this.displayName}: " +
                    "input=$inputTokens, output=$outputTokens, cached=$cachedInputTokens")
            
            val isLongContext = inputTokens > longContextThreshold
            
            val actualInputPrice = if (isLongContext) longInputPricePerM else inputPricePerM
            val actualOutputPrice = if (isLongContext) longOutputPricePerM else outputPricePerM
            
            val newInputTokens = inputTokens - cachedInputTokens
            
            val newInputCostUSD = (newInputTokens / 1_000_000.0) * actualInputPrice
            val cachedInputCostUSD = (cachedInputTokens / 1_000_000.0) * cachedInputPricePerM
            val outputCostUSD = (outputTokens / 1_000_000.0) * actualOutputPrice
            
            val totalCostUSD = newInputCostUSD + cachedInputCostUSD + outputCostUSD
            val totalCostEUR = totalCostUSD * usdToEur
            
            val savingsUSD = if (cachedInputTokens > 0) {
                (cachedInputTokens / 1_000_000.0) * (actualInputPrice - cachedInputPricePerM)
            } else 0.0
            
            val cost = ModelCost(
                model = this,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedInputTokens = cachedInputTokens,
                newInputTokens = newInputTokens,
                isLongContext = isLongContext,
                newInputCostUSD = newInputCostUSD,
                cachedInputCostUSD = cachedInputCostUSD,
                outputCostUSD = outputCostUSD,
                totalCostUSD = totalCostUSD,
                totalCostEUR = totalCostEUR,
                cacheSavingsUSD = savingsUSD,
                cacheSavingsEUR = savingsUSD * usdToEur
            )
            
            Log.d(TAG, "Cost calculated: $${String.format("%.4f", totalCostUSD)} " +
                    "(€${String.format("%.4f", totalCostEUR)}), " +
                    "savings: ${String.format("%.1f", cost.savingsPercentage)}%")
            
            return cost
        }
    }
    
    data class ModelCost(
        val model: ClaudeModel,
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedInputTokens: Int,
        val newInputTokens: Int,
        val isLongContext: Boolean,
        val newInputCostUSD: Double,
        val cachedInputCostUSD: Double,
        val outputCostUSD: Double,
        val totalCostUSD: Double,
        val totalCostEUR: Double,
        val cacheSavingsUSD: Double,
        val cacheSavingsEUR: Double
    ) {
        val totalTokens: Int = inputTokens + outputTokens
        
        val savingsPercentage: Double = if (inputTokens > 0 && cacheSavingsUSD > 0) {
            (cacheSavingsUSD / (totalCostUSD + cacheSavingsUSD)) * 100
        } else 0.0
        
        val costPerToken: Double = if (totalTokens > 0) {
            totalCostUSD / totalTokens
        } else 0.0
        
        val cacheEfficiency: Double = if (inputTokens > 0) {
            (cachedInputTokens.toDouble() / inputTokens) * 100
        } else 0.0
        
        operator fun plus(other: ModelCost): ModelCost {
            require(model == other.model) { "Cannot combine costs from different models" }
            
            return model.calculateCost(
                inputTokens = inputTokens + other.inputTokens,
                outputTokens = outputTokens + other.outputTokens,
                cachedInputTokens = cachedInputTokens + other.cachedInputTokens
            )
        }
        
        override fun toString(): String = buildString {
            append("ModelCost(")
            append("model=${model.displayName}, ")
            append("tokens=$totalTokens, ")
            append("cost=$${String.format("%.4f", totalCostUSD)}, ")
            append("savings=${String.format("%.1f", savingsPercentage)}%")
            append(")")
        }
    }
    
    data class ChatSession(
        val sessionId: String,
        val model: ClaudeModel,
        val startTime: Instant,
        var endTime: Instant? = null,
        var totalInputTokens: Int = 0,
        var totalOutputTokens: Int = 0,
        var totalCachedInputTokens: Int = 0,
        var messageCount: Int = 0,
        var isActive: Boolean = true
    ) {
        var cacheHitRate: Double = 0.0
            private set
        
        var averageCostPerMessage: Double = 0.0
            private set
        
        var averageTokensPerMessage: Int = 0
            private set
        
        val duration: Long
            get() = (endTime ?: Instant.now()).epochSecond - startTime.epochSecond
        
        val durationFormatted: String
            get() {
                val seconds = duration
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60
                return when {
                    hours > 0 -> "${hours}ч ${minutes}м"
                    minutes > 0 -> "${minutes}м ${secs}с"
                    else -> "${secs}с"
                }
            }
        
        val startTimeFormatted: String
            get() = formatInstant(startTime)
        
        val endTimeFormatted: String?
            get() = endTime?.let { formatInstant(it) }
        
        val currentCost: ModelCost
            get() = model.calculateCost(
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                cachedInputTokens = totalCachedInputTokens
            )
        
        val isApproachingLongContext: Boolean
            get() = totalInputTokens > (model.longContextThreshold * 0.8)
        
        val isLongContext: Boolean
            get() = totalInputTokens > model.longContextThreshold
        
        val remainingTokensBeforeLongContext: Int
            get() = (model.longContextThreshold - totalInputTokens).coerceAtLeast(0)
        
        fun addMessage(inputTokens: Int, outputTokens: Int, cachedInputTokens: Int = 0) {
            require(inputTokens >= 0) { "Input tokens cannot be negative" }
            require(outputTokens >= 0) { "Output tokens cannot be negative" }
            require(cachedInputTokens >= 0) { "Cached tokens cannot be negative" }
            
            totalInputTokens += inputTokens
            totalOutputTokens += outputTokens
            totalCachedInputTokens += cachedInputTokens
            messageCount++
            
            updateMetrics()
            
            Log.d(TAG, "Message added to session $sessionId: " +
                    "input=$inputTokens, output=$outputTokens, cached=$cachedInputTokens, " +
                    "total messages=$messageCount")
        }
        
        fun end() {
            isActive = false
            endTime = Instant.now()
            
            Log.i(TAG, "Session $sessionId ended: " +
                    "duration=$durationFormatted, messages=$messageCount, " +
                    "cost=${currentCost.totalCostEUR}€")
        }
        
        private fun updateMetrics() {
            cacheHitRate = if (totalInputTokens > 0) {
                (totalCachedInputTokens.toDouble() / totalInputTokens) * 100
            } else 0.0
            
            averageCostPerMessage = if (messageCount > 0) {
                currentCost.totalCostEUR / messageCount
            } else 0.0
            
            averageTokensPerMessage = if (messageCount > 0) {
                (totalInputTokens + totalOutputTokens) / messageCount
            } else 0
        }
        
        private fun formatInstant(instant: Instant): String {
            val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
                .withZone(ZoneId.systemDefault())
            return formatter.format(instant)
        }
        
        fun getDetailedStats(): String = buildString {
            appendLine("📊 Session Statistics")
            appendLine()
            appendLine("**Session ID:** ${sessionId.take(8)}...")
            appendLine("**Model:** ${model.displayName} ${model.emoji}")
            appendLine("**Started:** $startTimeFormatted")
            if (endTime != null) {
                appendLine("**Ended:** $endTimeFormatted")
            }
            appendLine("**Duration:** $durationFormatted")
            appendLine()
            appendLine("**Messages:** $messageCount")
            appendLine("**Total Tokens:** ${"%,d".format(totalInputTokens + totalOutputTokens)}")
            appendLine("**Input Tokens:** ${"%,d".format(totalInputTokens)}")
            appendLine("**Output Tokens:** ${"%,d".format(totalOutputTokens)}")
            appendLine("**Cached Tokens:** ${"%,d".format(totalCachedInputTokens)}")
            appendLine()
            appendLine("**Cache Hit Rate:** ${String.format("%.1f", cacheHitRate)}%")
            appendLine("**Avg Tokens/Msg:** ${"%,d".format(averageTokensPerMessage)}")
            appendLine("**Avg Cost/Msg:** €${String.format("%.4f", averageCostPerMessage)}")
            appendLine()
            appendLine("**Total Cost:** €${String.format("%.4f", currentCost.totalCostEUR)}")
            if (currentCost.savingsPercentage > 0) {
                appendLine("**Savings:** ${String.format("%.1f", currentCost.savingsPercentage)}% " +
                        "(€${String.format("%.4f", currentCost.cacheSavingsEUR)})")
            }
        }
    }
    
    object SessionManager {
        private const val TAG = "SessionManager"
        private val sessions = ConcurrentHashMap<String, ChatSession>()
        
        fun createSession(
            sessionId: String,
            model: ClaudeModel
        ): ChatSession {
            if (sessions.containsKey(sessionId)) {
                Log.w(TAG, "Session $sessionId already exists, returning existing")
                return sessions[sessionId]!!
            }
            
            val session = ChatSession(
                sessionId = sessionId,
                model = model,
                startTime = Instant.now()
            )
            sessions[sessionId] = session
            
            Log.i(TAG, "Created new session: $sessionId with model ${model.displayName}")
            return session
        }
        
        fun getSession(sessionId: String): ChatSession? {
            return sessions[sessionId]
        }
        
        fun endSession(sessionId: String): ChatSession? {
            return sessions[sessionId]?.apply {
                end()
                sessions.remove(sessionId)
            }
        }
        
        fun getAllActiveSessions(): List<ChatSession> {
            return sessions.values.filter { it.isActive }
        }
        
        fun getAllSessions(): List<ChatSession> {
            return sessions.values.toList()
        }
        
        fun shouldStartNewSession(sessionId: String): Boolean {
            val session = sessions[sessionId] ?: return false
            return session.isApproachingLongContext
        }
        
        fun cleanupOldSessions(maxAge: Duration = Duration.ofDays(1)): Int {
            val now = Instant.now()
            var cleaned = 0
            
            sessions.values.toList().forEach { session ->
                if (!session.isActive) {
                    val endTime = session.endTime ?: now
                    val age = Duration.between(endTime, now)
                    
                    if (age > maxAge) {
                        sessions.remove(session.sessionId)
                        cleaned++
                        Log.d(TAG, "Cleaned up old session: ${session.sessionId} (age: ${age.toHours()}h)")
                    }
                }
            }
            
            if (cleaned > 0) {
                Log.i(TAG, "Cleaned up $cleaned old sessions")
            }
            
            return cleaned
        }
        
        fun getTotalCost(): ModelCost? {
            val allSessions = sessions.values.toList()
            if (allSessions.isEmpty()) return null
            
            return allSessions
                .map { it.currentCost }
                .reduce { acc, cost -> acc + cost }
        }
        
        fun clear() {
            val count = sessions.size
            sessions.clear()
            Log.i(TAG, "Cleared all sessions: $count")
        }
    }
}