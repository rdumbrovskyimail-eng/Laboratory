package com.opuside.app.feature.pipeline.data

/**
 * 📎 PROMPT CODE BLOCKS v4.0
 *
 * Извлекает fenced-блоки кода (``` / ~~~) из промпта пользователя и нумерует их.
 *
 * Зачем: планировщик НЕ перепечатывает код. Он лишь ссылается на блоки по номеру,
 * а исполнитель берёт содержимое блока из промпта байт-в-байт. Это исключает
 * «сокращение» кода моделью (именно так ломался AndroidManifest.xml).
 */
data class PromptCodeBlock(
    /** Номер блока, начиная с 1 — так он виден модели. */
    val index: Int,
    /** Info-string после ``` (язык и/или путь файла), может быть пустым. */
    val info: String,
    /** Содержимое блока без строк-ограждений. */
    val content: String,
    /** Пара строк текста перед блоком — помогает понять, к какому файлу он относится. */
    val leadIn: String,
    val startLine: Int,
    val endLine: Int
) {
    val lineCount: Int get() = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
}

/** Контекст запуска: промпт + его блоки. Общий для планировщика и всех задач. */
data class PipelineRunContext(
    val userPrompt: String,
    val blocks: List<PromptCodeBlock>,
    /** Явные пары «Найти / Заменить на», разобранные кодом (применяются дословно). */
    val pairs: List<FindReplacePair> = emptyList()
) {
    fun block(index: Int?): PromptCodeBlock? = if (index == null) null else blocks.firstOrNull { it.index == index }
    fun pair(index: Int?): FindReplacePair? = if (index == null) null else pairs.firstOrNull { it.index == index }

    /** Блоки, которые являются содержимым пар (их нельзя считать «полным файлом»). */
    val pairBlockIndices: Set<Int> get() = pairs.flatMap { it.blockIndices }.toSet()

    companion object {
        val EMPTY = PipelineRunContext("", emptyList(), emptyList())

        fun from(prompt: String): PipelineRunContext {
            val blocks = PromptBlockExtractor.extract(prompt)
            return PipelineRunContext(prompt, blocks, FindReplaceParser.parse(prompt, blocks))
        }
    }
}

object PromptBlockExtractor {

    private val FENCE_OPEN = Regex("""^\s{0,3}(`{3,}|~{3,})\s*(.*)$""")

    fun extract(prompt: String): List<PromptCodeBlock> {
        val lines = prompt.replace("\r\n", "\n").split('\n')
        val result = mutableListOf<PromptCodeBlock>()
        var i = 0
        while (i < lines.size) {
            val open = FENCE_OPEN.matchEntire(lines[i])
            if (open == null) { i++; continue }

            val fence = open.groupValues[1]
            val fenceChar = fence[0]
            val info = open.groupValues[2].trim()
            // Строка вида ```kotlin``` (открытие и закрытие в одной строке) — не блок.
            if (fenceChar == '`' && info.contains('`')) { i++; continue }

            // ` и ~ не являются метасимволами regex — экранирование не нужно.
            val closeRegex = Regex("^\\s{0,3}" + fenceChar + "{" + fence.length + ",}\\s*$")
            var j = i + 1
            while (j < lines.size && !closeRegex.matches(lines[j])) j++

            val contentLines = lines.subList(i + 1, j.coerceAtMost(lines.size))
            val leadIn = lines.subList((i - 3).coerceAtLeast(0), i)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !FENCE_OPEN.matches(it) }
                .joinToString(" / ")
                .take(240)

            result += PromptCodeBlock(
                index = result.size + 1,
                info = info,
                content = contentLines.joinToString("\n"),
                leadIn = leadIn,
                startLine = i + 1,
                endLine = (j + 1).coerceAtMost(lines.size)
            )
            i = j + 1
        }
        return result
    }

    /**
     * Версия промпта для планировщика: каждый блок помечен номером, а большие блоки
     * свёрнуты (планировщику не нужен весь код — только понимание, к чему он относится).
     */
    fun renderForPlanner(ctx: PipelineRunContext, maxBlockLinesFull: Int = 60): String {
        if (ctx.blocks.isEmpty()) return ctx.userPrompt
        val lines = ctx.userPrompt.replace("\r\n", "\n").split('\n')
        val sb = StringBuilder()
        var cursor = 0
        for (b in ctx.blocks) {
            val openIdx = b.startLine - 1
            val closeIdx = b.endLine - 1
            for (k in cursor until openIdx.coerceAtMost(lines.size)) sb.append(lines[k]).append('\n')

            val header = buildString {
                append("⟦CODE BLOCK #${b.index}")
                if (b.info.isNotBlank()) append(" · ").append(b.info)
                append(" · ${b.lineCount} lines⟧")
            }
            sb.append(header).append('\n')
            val bl = if (b.content.isEmpty()) emptyList() else b.content.split('\n')
            if (bl.size <= maxBlockLinesFull) {
                bl.forEach { sb.append("  ").append(it).append('\n') }
            } else {
                bl.take(35).forEach { sb.append("  ").append(it).append('\n') }
                sb.append("  ⋯ (${bl.size - 45} lines folded for the planner; executor gets the full block) ⋯\n")
                bl.takeLast(10).forEach { sb.append("  ").append(it).append('\n') }
            }
            sb.append("⟦END BLOCK #${b.index}⟧\n")
            cursor = closeIdx + 1
        }
        for (k in cursor until lines.size) sb.append(lines[k]).append('\n')
        return sb.toString().trimEnd()
    }

    /**
     * Версия промпта для исполнителя: блоки, не относящиеся к файлу, заменяются
     * короткой заглушкой (экономия токенов), относящиеся — остаются целиком.
     */
    fun renderForExecutor(ctx: PipelineRunContext, relevantBlocks: Set<Int>): String {
        if (ctx.blocks.isEmpty()) return ctx.userPrompt
        val lines = ctx.userPrompt.replace("\r\n", "\n").split('\n')
        val sb = StringBuilder()
        var cursor = 0
        for (b in ctx.blocks) {
            val openIdx = b.startLine - 1
            val closeIdx = b.endLine - 1
            for (k in cursor until openIdx.coerceAtMost(lines.size)) sb.append(lines[k]).append('\n')
            if (b.index in relevantBlocks) {
                for (k in openIdx..closeIdx.coerceAtMost(lines.size - 1)) sb.append(lines[k]).append('\n')
            } else {
                sb.append("[code block #${b.index}${if (b.info.isNotBlank()) " (${b.info})" else ""} — относится к другому файлу, опущен]\n")
            }
            cursor = closeIdx + 1
        }
        for (k in cursor until lines.size) sb.append(lines[k]).append('\n')
        return sb.toString().trimEnd()
    }
}