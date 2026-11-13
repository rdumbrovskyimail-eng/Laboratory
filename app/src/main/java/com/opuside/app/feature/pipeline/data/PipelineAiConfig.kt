package com.opuside.app.feature.pipeline.data

/**
 * ⚙️ PIPELINE AI CONFIG v4.0
 *
 * Единственный источник правды о модели Pipeline.
 * Вся система (планировщик, правки, создание файлов, отчёт) работает
 * ИСКЛЮЧИТЕЛЬНО на Gemini 3.1 Flash-Lite. Никаких fallback на другие модели.
 *
 * Параметры модели (Google AI, GA с 07.05.2026):
 *  - model id: gemini-3.1-flash-lite
 *  - контекст: 1M токенов, выход: до 64k токенов (thinking входит в лимит)
 *  - thinking levels: minimal / low / medium / high
 *  - цена: $0.25 / 1M input, $1.50 / 1M output (thinking тарифицируется как output)
 *  - temperature: НЕ задаём (для Gemini 3 Google рекомендует дефолт 1.0)
 */
object PipelineAiConfig {

    const val MODEL_ID = "gemini-3.1-flash-lite"
    const val DISPLAY_NAME = "Gemini 3.1 Flash-Lite"
    const val BADGE = "3.1-FL"

    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    // ── Цены ────────────────────────────────────────────────────────────
    const val INPUT_USD_PER_M = 0.25
    const val OUTPUT_USD_PER_M = 1.50
    const val USD_TO_EUR = 0.92

    // ── Лимиты ──────────────────────────────────────────────────────────
    /** Жёсткий потолок выхода модели (с запасом под 64k). */
    const val MODEL_MAX_OUTPUT = 64_000

    enum class Thinking(val apiValue: String) {
        MINIMAL("minimal"), LOW("low"), MEDIUM("medium"), HIGH("high")
    }

    // ── Планировщик: точность классификации важнее скорости ────────────
    val PLANNER_THINKING = Thinking.MEDIUM
    const val PLANNER_MAX_OUTPUT = 32_000
    const val PLANNER_READ_TIMEOUT_MS = 150_000

    // ── Правки существующих файлов ─────────────────────────────────────
    val EDIT_THINKING = Thinking.LOW
    /** На повторном проходе модель думает глубже и видит причину прошлой ошибки. */
    val EDIT_RETRY_THINKING = Thinking.MEDIUM
    const val EDIT_MAX_OUTPUT = MODEL_MAX_OUTPUT
    const val EDIT_READ_TIMEOUT_MS = 240_000

    // ── Генерация новых файлов (когда в промпте нет готового кода) ─────
    val CREATE_THINKING = Thinking.LOW
    val CREATE_RETRY_THINKING = Thinking.MEDIUM
    const val CREATE_MAX_OUTPUT = MODEL_MAX_OUTPUT
    const val CREATE_READ_TIMEOUT_MS = 240_000

    // ── Итоговый отчёт ──────────────────────────────────────────────────
    val SUMMARY_THINKING = Thinking.MINIMAL
    const val SUMMARY_MAX_OUTPUT = 4_096
    const val SUMMARY_READ_TIMEOUT_MS = 60_000

    const val CONNECT_TIMEOUT_MS = 20_000

    /** Полный промпт пользователя передаётся исполнителю, если он не больше этого размера. */
    const val FULL_PROMPT_CONTEXT_LIMIT_CHARS = 120_000

    /** Сколько путей репозитория максимум показываем планировщику. */
    const val PLANNER_MAX_PATHS = 6_000

    fun costEur(inputTokens: Int, outputTokens: Int): Double =
        (inputTokens * INPUT_USD_PER_M + outputTokens * OUTPUT_USD_PER_M) / 1_000_000.0 * USD_TO_EUR
}