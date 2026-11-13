package com.opuside.app.feature.pipeline.data

/**
 * 🔁 FIND / REPLACE PAIRS v4.1
 *
 * Явные инструкции пользователя вида
 *
 *     Файл: app/src/main/AndroidManifest.xml
 *     Найти:
 *     ```xml
 *     <activity android:name=".X" />
 *     ```
 *     Заменить на:
 *     ```xml
 *     <activity android:name=".X" android:exported="false" />
 *     ```
 *
 * разбираются КОДОМ и применяются к файлу дословно, без модели:
 * текст «Заменить на» попадает в файл байт-в-байт. Модель подключается только
 * если фрагмент «Найти» не нашёлся (устарел / неоднозначен) или если у файла есть
 * ещё и словесные правки.
 *
 * Поддерживаемые маркеры (регистр не важен, допускаются `**жирный**`, `1.`, `-`, `###`):
 *   Найти / Найди / Ищи / Было / Find / Search / Old / Before            — что искать
 *   Заменить на / Замени на / На / Стало / Replace with / New / After  — на что заменить
 *   Заменить все (на) / везде / Replace all                              — все вхождения
 *   Удалить (после «Найти») / Удалить: <текст> / Delete / Remove         — удалить фрагмент
 *   Вставить (добавить) после / перед / Insert after / before            — вставка у якоря
 * Содержимое: блок ``` после маркера, текст в той же строке, или строки до следующего маркера.
 * Файл: строка «Файл: путь», «### путь», путь в info-строке блока (```kotlin app/X.kt)
 *       или «Найти в файле X.kt:».
 */
enum class PairKind(val label: String) {
    REPLACE("заменить"),
    REPLACE_ALL("заменить все вхождения"),
    DELETE("удалить"),
    INSERT_AFTER("вставить после"),
    INSERT_BEFORE("вставить перед")
}

data class FindReplacePair(
    val index: Int,
    val kind: PairKind,
    val find: String,
    /** REPLACE*: новый текст; INSERT_*: вставляемый текст; DELETE: "". */
    val replacement: String,
    val fileHint: String?,
    /** true — путь указан явно («Файл: …», заголовок, info-строка блока, «в файле …»). */
    val strongHint: Boolean,
    val blockIndices: Set<Int>,
    val line: Int
) {
    val findPreview: String get() = find.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80).orEmpty()
    val replacementPreview: String get() = replacement.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80).orEmpty()
    private fun lines(s: String) = if (s.isEmpty()) 0 else s.count { it == '\n' } + 1
    val findLines: Int get() = lines(find)
    val replacementLines: Int get() = lines(replacement)
}

object FindReplaceParser {

    private const val LEAD = """^\s*(?:[-*+>]+\s*|\d+[.)]\s*|#{1,6}\s*)?(?:\*\*|__)?\s*"""
    private const val CONJ = """(?:(?:и|а|затем|потом|после чего|and|then)\s+)?"""
    private const val TAIL = """([^:\n]{0,160}?)\s*(?:\*\*|__)?\s*:\s*(?:\*\*|__)?\s*(.*)$"""

    private val FIND = Regex(
        LEAD + """(найти|найди|найдите|ищи|ищем|поиск|было|старый код|исходный код|find|search for|search|old code|original code|old|before)(?![\p{L}\p{N}_])""" + TAIL,
        RegexOption.IGNORE_CASE
    )
    private val INSERT_AFTER = Regex(
        LEAD + CONJ + """(вставить после|вставь после|вставьте после|добавить после|добавь после|добавьте после|после него (?:вставить|добавить)|insert after|add after|append after)(?![\p{L}\p{N}_])""" + TAIL,
        RegexOption.IGNORE_CASE
    )
    private val INSERT_BEFORE = Regex(
        LEAD + CONJ + """(вставить перед|вставь перед|вставьте перед|добавить перед|добавь перед|добавьте перед|вставить до|перед ним (?:вставить|добавить)|insert before|add before|prepend)(?![\p{L}\p{N}_])""" + TAIL,
        RegexOption.IGNORE_CASE
    )
    private val REPLACE = Regex(
        LEAD + CONJ + """(заменить|замени|замените|заменяем|поменять|поменяй|стало|на|replace with|replace it with|replace|change to|new code|new|after)(?![\p{L}\p{N}_])""" + TAIL,
        RegexOption.IGNORE_CASE
    )
    /** «Удалить» как отдельная строка (после «Найти») или «Удалить: текст». */
    private val DELETE = Regex(
        LEAD + CONJ + """(удалить|удали|удалите|убрать|убери|delete|remove)(?![\p{L}\p{N}_])([^:\n]{0,80}?)\s*(?:\*\*|__)?\s*(?::\s*(?:\*\*|__)?\s*(.*))?$""",
        RegexOption.IGNORE_CASE
    )
    private val ALL_WORDS = Regex("""(?<![\p{L}])(все|всё|везде|всех|all|every|everywhere)(?![\p{L}])""", RegexOption.IGNORE_CASE)

    private val PATH = Regex(
        """(?<![\w/.-])((?:[\w.-]+/)*[\w.-]+\.(?:kt|kts|java|xml|gradle|json|toml|properties|pro|ya?ml|md|txt|html|css|js|ts|cfg|ini))(?![\w/-])""",
        RegexOption.IGNORE_CASE
    )
    private val IN_FILE = Regex("""(?:в файле|в|in file|in)\s+`?((?:[\w.-]+/)*[\w.-]+\.\w+)`?""", RegexOption.IGNORE_CASE)
    private val SECTION_BREAK = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,}|={3,})\s*$|^\s*#{1,6}\s+\S|^\s*\d+[.)]\s+\S""")

    private data class Pending(
        val find: String, val hint: String?, val strong: Boolean, val blocks: Set<Int>, val line: Int
    )

    private data class Extracted(val text: String, val next: Int, val blocks: Set<Int>, val infoPath: String?)

    fun parse(prompt: String, blocks: List<PromptCodeBlock>): List<FindReplacePair> {
        val lines = prompt.replace("\r\n", "\n").split('\n')
        val blockByStart = blocks.associateBy { it.startLine - 1 }   // 0-based строка открывающего ```
        val result = mutableListOf<FindReplacePair>()

        var hint: String? = null
        var hintStrong = false
        var pending: Pending? = null
        var i = 0

        fun add(kind: PairKind, find: String, replacement: String, p: Pending, extra: Extracted?) {
            val f = trimBlankEdges(find)
            if (f.isBlank()) return
            val infoPath = extra?.infoPath
            result += FindReplacePair(
                index = result.size + 1,
                kind = kind,
                find = f,
                replacement = trimBlankEdges(replacement),
                fileHint = infoPath ?: p.hint,
                strongHint = infoPath != null || p.strong,
                blockIndices = p.blocks + (extra?.blocks ?: emptySet()),
                line = p.line
            )
        }

        while (i < lines.size) {
            // Блок кода, не являющийся содержимым маркера: пропускаем, путь из info-строки запоминаем
            val skipBlock = blockByStart[i]
            if (skipBlock != null) {
                pathIn(skipBlock.info)?.let { hint = it; hintStrong = true }
                i = skipBlock.endLine
                continue
            }

            val line = lines[i]

            // Подсказка о файле
            val header = strongHeaderPath(line)
            if (header != null) {
                hint = header; hintStrong = true
            } else if (!isMarker(line) && !hintStrong) {
                PATH.findAll(line).lastOrNull()?.groupValues?.get(1)?.let { hint = it; hintStrong = false }
            }

            val p = pending
            if (p == null) {
                val find = findMatch(line)
                if (find != null) {
                    val inFile = IN_FILE.find(find.groupValues[2])?.groupValues?.get(1)
                    val ex = extract(lines, i, find.groupValues[3], blockByStart)
                    if (ex.text.isNotBlank()) {
                        pending = Pending(
                            find = ex.text,
                            hint = inFile ?: ex.infoPath ?: hint,
                            strong = inFile != null || ex.infoPath != null || hintStrong,
                            blocks = ex.blocks,
                            line = i + 1
                        )
                        i = ex.next
                    } else i++
                    continue
                }
                // Отдельная инструкция «Удалить: <фрагмент>» (не «Удалить файл …»)
                val del = deleteMatch(line)
                if (del != null && line.contains(':') && del.groupValues[2].isBlank()) {
                    val ex = extract(lines, i, del.groupValues[3], blockByStart)
                    if (ex.text.isNotBlank() && PATH.matchEntire(ex.text.trim()) == null) {
                        val pp = Pending(ex.text, ex.infoPath ?: hint, ex.infoPath != null || hintStrong, ex.blocks, i + 1)
                        add(PairKind.DELETE, ex.text, "", pp, ex)
                        i = ex.next
                        continue
                    }
                }
                i++
                continue
            }

            // Есть отложенный «Найти» — ждём парный маркер
            val after = markerMatch(INSERT_AFTER, line)
            val before = markerMatch(INSERT_BEFORE, line)
            val replace = replaceMatch(line)
            when {
                findMatch(line) != null -> {
                    // Новый «Найти» без пары к предыдущему — предыдущий отбрасываем и разбираем строку заново
                    pending = null
                }
                after != null || before != null -> {
                    val m = after ?: before!!
                    val ex = extract(lines, i, m.groupValues[3], blockByStart)
                    if (ex.text.isNotBlank()) {
                        add(if (after != null) PairKind.INSERT_AFTER else PairKind.INSERT_BEFORE, p.find, ex.text, p, ex)
                    }
                    pending = null
                    i = maxOf(ex.next, i + 1)
                }
                replace == null && deleteMatch(line) != null -> {
                    add(PairKind.DELETE, p.find, "", p, null)
                    pending = null
                    i++
                }
                replace != null -> {
                    val all = ALL_WORDS.containsMatchIn(replace.groupValues[1] + " " + replace.groupValues[2])
                    val ex = extract(lines, i, replace.groupValues[3], blockByStart)
                    // Пустая замена = удаление (явно написано «Заменить на:» и пустой блок)
                    add(if (all) PairKind.REPLACE_ALL else PairKind.REPLACE, p.find, ex.text, p, ex)
                    pending = null
                    i = maxOf(ex.next, i + 1)
                }
                else -> i++
            }
        }
        return result
    }

    // ── Валидация маркеров: «середина» маркера не должна быть похожа на код ──
    private val MIDDLE_WORDS = Regex("""^[\p{L}\s,«»"'\-]*$""")
    private val MIDDLE_FIND = Regex("""^[\p{L}\s,«»"'\-./_\d`]*$""")
    private val SHORT_REPLACE_WORDS = setOf("на", "стало", "new", "after")

    private fun findMatch(line: String): MatchResult? =
        FIND.matchEntire(line)?.takeIf { MIDDLE_FIND.matches(it.groupValues[2]) }

    private fun markerMatch(r: Regex, line: String): MatchResult? =
        r.matchEntire(line)?.takeIf { MIDDLE_WORDS.matches(it.groupValues[2]) }

    private fun replaceMatch(line: String): MatchResult? =
        REPLACE.matchEntire(line)?.takeIf {
            MIDDLE_WORDS.matches(it.groupValues[2]) &&
                    !(it.groupValues[1].lowercase() in SHORT_REPLACE_WORDS && it.groupValues[2].isNotBlank())
        }

    private fun deleteMatch(line: String): MatchResult? =
        DELETE.matchEntire(line)?.takeIf {
            MIDDLE_WORDS.matches(it.groupValues[2]) &&
                    !Regex("""файл|file""", RegexOption.IGNORE_CASE).containsMatchIn(it.groupValues[2])
        }

    private fun isMarker(line: String): Boolean =
        findMatch(line) != null || replaceMatch(line) != null || markerMatch(INSERT_AFTER, line) != null ||
                markerMatch(INSERT_BEFORE, line) != null || (deleteMatch(line) != null && line.trim().length < 60)

    private fun extract(
        lines: List<String>,
        i: Int,
        inline: String,
        blockByStart: Map<Int, PromptCodeBlock>
    ): Extracted {
        val inl = inline.trim()
        if (inl.isNotEmpty() && !inl.startsWith("```") && !inl.startsWith("~~~")) {
            return Extracted(stripInlineCode(inl), i + 1, emptySet(), null)
        }
        var j = i + 1
        while (j < lines.size && lines[j].isBlank()) j++
        blockByStart[j]?.let { b ->
            return Extracted(b.content, b.endLine, setOf(b.index), pathIn(b.info))
        }
        // Текст без ограждений: строки до следующего маркера / раздела / блока
        val collected = mutableListOf<String>()
        var k = i + 1
        while (k < lines.size) {
            val l = lines[k]
            if (blockByStart.containsKey(k) || isMarker(l) || SECTION_BREAK.containsMatchIn(l) || strongHeaderPath(l) != null) break
            collected += l
            k++
        }
        return Extracted(trimBlankEdges(collected.joinToString("\n")), k, emptySet(), null)
    }

    private fun stripInlineCode(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && t.startsWith("`") && t.endsWith("`") && !t.startsWith("```")) t.substring(1, t.length - 1) else t
    }

    private fun trimBlankEdges(s: String): String {
        val ls = s.replace("\r\n", "\n").split('\n')
        val first = ls.indexOfFirst { it.isNotBlank() }
        if (first < 0) return ""
        val last = ls.indexOfLast { it.isNotBlank() }
        return ls.subList(first, last + 1).joinToString("\n")
    }

    private fun pathIn(info: String): String? =
        PATH.findAll(info).lastOrNull()?.groupValues?.get(1)?.takeIf { it.contains('/') || it.contains('.') }

    /** Строка, которая целиком является указанием файла: «Файл: X», «### X», «**X**», «📄 X:». */
    fun strongHeaderPath(line: String): String? {
        var t = line.trim()
        if (t.isEmpty() || t.length > 260) return null
        t = t.replace(Regex("""^(?:[-*+>]+\s*|\d+[.)]\s*|#{1,6}\s*)"""), "")
        t = t.replace("**", "").replace("__", "").trim()
        t = t.replace(Regex("""^(?:📄|📁|🗂|✏️|➕)\s*"""), "")
        t = t.replace(Regex("""^(?:файл|file|в файле|путь|path|изменения в|changes in)\s*[:\-—]?\s*""", RegexOption.IGNORE_CASE), "")
        t = t.substringBefore(" (").substringBefore(" —").substringBefore(" - ").trim().trim('`', ':', ' ')
        return PATH.matchEntire(t)?.groupValues?.get(1)
    }
}

/**
 * Применение одной пары к тексту файла. Чистая логика, без сети.
 */
object FindReplaceApplier {

    sealed class Result {
        data class Applied(val newContent: String, val occurrences: Int, val note: String?) : Result()
        object AlreadyApplied : Result()
        data class Unresolved(val reason: String) : Result()
    }

    fun apply(content: String, pair: FindReplacePair): Result {
        return when (pair.kind) {
            PairKind.REPLACE -> single(content, pair.find, pair.replacement)
            PairKind.DELETE -> single(content, pair.find, "")
            PairKind.INSERT_AFTER -> {
                val combined = pair.find + "\n" + pair.replacement
                if (AnchoredEditApplier.countMatches(content, combined) > 0) Result.AlreadyApplied
                else single(content, pair.find, combined, checkAlready = false)
            }
            PairKind.INSERT_BEFORE -> {
                val combined = pair.replacement + "\n" + pair.find
                if (AnchoredEditApplier.countMatches(content, combined) > 0) Result.AlreadyApplied
                else single(content, pair.find, combined, checkAlready = false)
            }
            PairKind.REPLACE_ALL -> replaceAll(content, pair)
        }
    }

    private fun single(content: String, find: String, replacement: String, checkAlready: Boolean = true): Result {
        val count = AnchoredEditApplier.countMatches(content, find)
        if (count == 0) {
            if (checkAlready && replacement.isNotBlank() && AnchoredEditApplier.countMatches(content, replacement) > 0) {
                return Result.AlreadyApplied
            }
            return Result.Unresolved("фрагмент «Найти» не найден в файле")
        }
        if (count > 1) {
            return Result.Unresolved("фрагмент «Найти» встречается $count раз — неясно, какое вхождение менять")
        }
        return when (val o = AnchoredEditApplier.apply(content, listOf(AnchoredEdit(0, 0, find, replacement)))) {
            is ApplyOutcome.Applied ->
                if (o.appliedCount == 0) Result.AlreadyApplied
                else Result.Applied(o.newContent, 1, if (o.fuzzyCount > 0) "найдено без учёта отступов, отступы выровнены" else null)
            is ApplyOutcome.Failed -> Result.Unresolved(o.reasons.joinToString("; "))
        }
    }

    private fun replaceAll(content: String, pair: FindReplacePair): Result {
        val crlf = content.contains("\r\n")
        val text = content.replace("\r\n", "\n")
        val find = pair.find.replace("\r\n", "\n")
        val repl = pair.replacement.replace("\r\n", "\n")
        val n = countOccurrences(text, find)
        if (n == 0) {
            // Точного совпадения нет — пробуем построчно без учёта отступов (по одному вхождению)
            var current = content
            var applied = 0
            while (applied < 500) {
                if (AnchoredEditApplier.countMatches(current, pair.find) == 0) break
                val o = AnchoredEditApplier.apply(current, listOf(AnchoredEdit(0, 0, pair.find, pair.replacement)))
                if (o !is ApplyOutcome.Applied || o.appliedCount == 0) break
                // Если замена содержит искомое — защита от бесконечного цикла
                if (AnchoredEditApplier.countMatches(o.newContent, pair.find) >= AnchoredEditApplier.countMatches(current, pair.find)) {
                    current = o.newContent; applied++; break
                }
                current = o.newContent
                applied++
            }
            if (applied > 0) return Result.Applied(current, applied, "без учёта отступов")
            if (repl.isNotBlank() && text.contains(repl)) return Result.AlreadyApplied
            return Result.Unresolved("фрагмент «Найти» не найден в файле")
        }
        var out = text.replace(find, repl)
        if (crlf) out = out.replace("\n", "\r\n")
        return Result.Applied(out, n, null)
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val idx = text.indexOf(needle, from)
            if (idx < 0) break
            count++
            from = idx + needle.length
        }
        return count
    }
}