package com.opuside.app.feature.pipeline.data

import kotlinx.serialization.json.Json
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/**
 * 🛡️ PIPELINE CONTENT GUARD v4.0
 *
 * Последний рубеж перед записью файла в репозиторий. Ловит то, из-за чего
 * раньше «укорачивались» файлы:
 *  1. «Ленивые» плейсхолдеры модели: `...`, `// rest of code`, `<!-- ... -->`,
 *     `// остальной код без изменений` и т.п. (учитываются только НОВЫЕ, которых
 *     не было в оригинале);
 *  2. Сломанная структура: XML не well-formed, невалидный JSON, нарушенный баланс
 *     скобок в коде (сравнение с оригиналом тем же лексером);
 *  3. Подозрительная усадка файла;
 *  4. Неполноценная «полная замена» (фрагмент вместо целого файла).
 */
object PipelineContentGuard {

    // ═══════════════════════════════════════════════════════════════════════
    // 1. ПЛЕЙСХОЛДЕРЫ
    // ═══════════════════════════════════════════════════════════════════════

    private val ELLIPSIS_ONLY_LINE = Regex(
        """^\s*(?://+|#+|/\*+|\*+|<!--|--|;+)?\s*(?:\.{3,}|…)\s*(?:\*+/|-->)?\s*[,;]?\s*$"""
    )

    private val COMMENT_TEXT = Regex("""^\s*(?://+|#+|/\*+|\*+|<!--|--)\s*(.*?)\s*(?:\*+/|-->)?\s*$""")

    private val LAZY_PHRASES = listOf(
        Regex("""rest of (?:the )?(?:code|file|class|implementation|methods|functions|content|manifest|body|layout|xml|elements)""", RegexOption.IGNORE_CASE),
        Regex("""(?:existing|remaining|other|previous|original) (?:code|methods|functions|imports|implementation|content|declarations|properties|elements|activities|permissions|services|attributes|tags|items|entries)\b""", RegexOption.IGNORE_CASE),
        Regex("""(?:code|content|implementation|methods|functions|elements) (?:omitted|unchanged|remains? (?:the )?same|stays? the same)""", RegexOption.IGNORE_CASE),
        Regex("""same as (?:before|above|original|previous)""", RegexOption.IGNORE_CASE),
        Regex("""(?:unchanged|omitted) (?:code|part|section|content|lines)""", RegexOption.IGNORE_CASE),
        Regex("""^(?:unchanged|omitted|as before|no changes|keep existing)[^\p{L}\p{N}]*$""", RegexOption.IGNORE_CASE),
        Regex("""остальн\p{L}*\s+(?:код|часть|метод|функци|содержим|разметк|элемент|атрибут|activity|активност)""", RegexOption.IGNORE_CASE),
        Regex("""(?:код|часть|методы|функции|содержимое|разметка|остальное)\s+без\s+изменени""", RegexOption.IGNORE_CASE),
        Regex("""^(?:без изменений|как было|как раньше|аналогично|опущено|пропущено)[^\p{L}\p{N}]*$""", RegexOption.IGNORE_CASE),
        Regex("""(?:существующ\p{L}*|прежн\p{L}*|старый|исходн\p{L}*)\s+(?:код|содержимое|реализаци)""", RegexOption.IGNORE_CASE)
    )

    private fun isLazyLine(line: String): Boolean {
        if (ELLIPSIS_ONLY_LINE.matches(line)) return true
        val m = COMMENT_TEXT.matchEntire(line) ?: return false
        val text = m.groupValues[1]
        if (text.isEmpty()) return false
        if (text.startsWith("...") || text.startsWith("…")) return true
        return LAZY_PHRASES.any { it.containsMatchIn(text) }
    }

    /** Возвращает строки-плейсхолдеры, которых не было в оригинале (с учётом количества). */
    fun findNewPlaceholders(original: String, updated: String): List<String> {
        fun lazyCounts(text: String): Map<String, Int> =
            text.replace("\r\n", "\n").split('\n')
                .filter { isLazyLine(it) }
                .groupingBy { it.trim() }
                .eachCount()

        val before = lazyCounts(original)
        val after = lazyCounts(updated)
        return after.flatMap { (line, cnt) ->
            val extra = cnt - (before[line] ?: 0)
            if (extra > 0) List(extra) { line } else emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. СТРУКТУРА
    // ═══════════════════════════════════════════════════════════════════════

    private enum class Lang { KOTLIN, JAVA, GROOVY, JS, CLIKE, NONE }

    private fun langOf(path: String): Lang = when (path.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> Lang.KOTLIN
        "java" -> Lang.JAVA
        "gradle", "groovy" -> Lang.GROOVY
        "js", "mjs", "cjs", "ts", "tsx", "jsx" -> Lang.JS
        "c", "h", "cpp", "hpp", "cc", "cxx", "cs", "go", "swift", "dart", "scala", "m", "mm" -> Lang.CLIKE
        else -> Lang.NONE
    }

    private fun ext(path: String) = path.substringAfterLast('.', "").lowercase()

    /**
     * @param original null — файл новый.
     * @return null если всё в порядке, иначе описание проблемы.
     */
    fun structureProblem(path: String, original: String?, updated: String): String? {
        when (ext(path)) {
            "xml", "svg", "plist" -> {
                val origOk = original == null || original.isBlank() || xmlError(original) == null
                if (origOk) {
                    xmlError(updated)?.let { return "XML не well-formed: $it" }
                }
                return null
            }
            "json" -> {
                val origOk = original == null || original.isBlank() || jsonError(original) == null
                if (origOk) jsonError(updated)?.let { return "Невалидный JSON: $it" }
                return null
            }
        }
        val lang = langOf(path)
        if (lang == Lang.NONE) return null

        val after = bracketBalance(updated, lang)
        if (original == null) {
            return after.problem?.let { "Нарушен баланс скобок: $it" }
        }
        val before = bracketBalance(original, lang)
        // Лексер мог «запутаться» на оригинале — тогда судить не можем.
        if (before.problem != null) return null
        return after.problem?.let { "Нарушен баланс скобок после правки: $it" }
    }

    private fun xmlError(text: String): String? = try {
        val factory = SAXParserFactory.newInstance()
        runCatching { factory.isNamespaceAware = false }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        factory.newSAXParser().parse(InputSource(StringReader(text)), DefaultHandler())
        null
    } catch (e: Exception) {
        e.message?.take(160) ?: e.javaClass.simpleName
    }

    private fun jsonError(text: String): String? = try {
        Json.parseToJsonElement(text); null
    } catch (e: Exception) {
        e.message?.take(160) ?: "parse error"
    }

    private data class Balance(val problem: String?)

    private enum class Mode { CODE, TEMPLATE, DQ, RAW_DQ, SQ, RAW_SQ, BACKTICK }
    private class Frame(val mode: Mode) { var depth = 0 }

    /**
     * Подсчёт скобок с пропуском строк, символов и комментариев.
     * Поддерживает шаблоны Kotlin/Groovy (`${...}`), raw-строки `"""`, JS template literals.
     */
    private fun bracketBalance(text: String, lang: Lang): Balance {
        val hasTemplates = lang == Lang.KOTLIN || lang == Lang.GROOVY
        val hasRawDq = lang == Lang.KOTLIN || lang == Lang.GROOVY || lang == Lang.JAVA ||
                lang == Lang.CLIKE
        val stack = ArrayDeque<Frame>().apply { addLast(Frame(Mode.CODE)) }
        var paren = 0; var bracket = 0; var brace = 0
        var line = 1
        var i = 0
        val n = text.length

        fun at(k: Int) = if (k < n) text[k] else '\u0000'
        fun startsWith(s: String) = text.startsWith(s, i)

        while (i < n) {
            val c = text[i]
            if (c == '\n') line++
            val frame = stack.last()
            when (frame.mode) {
                Mode.CODE, Mode.TEMPLATE -> {
                    when {
                        startsWith("//") -> {
                            while (i < n && text[i] != '\n') i++
                            continue
                        }
                        startsWith("/*") -> {
                            val end = text.indexOf("*/", i + 2)
                            val stop = if (end < 0) n else end + 2
                            for (k in i until stop) if (text[k] == '\n') line++
                            i = stop
                            continue
                        }
                        hasRawDq && startsWith("\"\"\"") -> { stack.addLast(Frame(Mode.RAW_DQ)); i += 3; continue }
                        lang == Lang.GROOVY && startsWith("'''") -> { stack.addLast(Frame(Mode.RAW_SQ)); i += 3; continue }
                        c == '"' -> stack.addLast(Frame(Mode.DQ))
                        c == '\'' -> stack.addLast(Frame(Mode.SQ))
                        c == '`' && lang == Lang.JS -> stack.addLast(Frame(Mode.BACKTICK))
                        c == '`' && lang == Lang.KOTLIN -> {
                            // `имя с пробелами` — идентификатор, пропускаем до закрывающей
                            val end = text.indexOf('`', i + 1)
                            val nl = text.indexOf('\n', i + 1).let { if (it < 0) n else it }
                            if (end in (i + 1) until nl) { i = end + 1; continue }
                        }
                        c == '(' -> paren++
                        c == ')' -> { paren--; if (paren < 0) return Balance("лишняя ')' в строке $line") }
                        c == '[' -> bracket++
                        c == ']' -> { bracket--; if (bracket < 0) return Balance("лишняя ']' в строке $line") }
                        c == '{' -> { brace++; if (frame.mode == Mode.TEMPLATE) frame.depth++ }
                        c == '}' -> {
                            if (frame.mode == Mode.TEMPLATE && frame.depth == 0) {
                                stack.removeLast()
                            } else {
                                if (frame.mode == Mode.TEMPLATE) frame.depth--
                                brace--
                                if (brace < 0) return Balance("лишняя '}' в строке $line")
                            }
                        }
                    }
                }
                Mode.DQ -> when {
                    c == '\\' -> { i += 2; continue }
                    c == '"' -> stack.removeLast()
                    c == '\n' -> stack.removeLast() // незакрытая строка — не даём ей «съесть» файл
                    hasTemplates && c == '$' && at(i + 1) == '{' -> { stack.addLast(Frame(Mode.TEMPLATE)); i += 2; continue }
                }
                Mode.SQ -> when {
                    c == '\\' -> { i += 2; continue }
                    c == '\'' || c == '\n' -> stack.removeLast()
                }
                Mode.RAW_DQ -> when {
                    startsWith("\"\"\"") -> {
                        i += 3
                        while (at(i) == '"') i++ // """" — кавычка внутри + закрытие
                        stack.removeLast()
                        continue
                    }
                    hasTemplates && c == '$' && at(i + 1) == '{' -> { stack.addLast(Frame(Mode.TEMPLATE)); i += 2; continue }
                }
                Mode.RAW_SQ -> if (startsWith("'''")) { stack.removeLast(); i += 3; continue }
                Mode.BACKTICK -> when {
                    c == '\\' -> { i += 2; continue }
                    c == '`' -> stack.removeLast()
                    c == '$' && at(i + 1) == '{' -> { stack.addLast(Frame(Mode.TEMPLATE)); i += 2; continue }
                }
            }
            i++
        }

        val problems = buildList {
            if (paren != 0) add("() ${signed(paren)}")
            if (bracket != 0) add("[] ${signed(bracket)}")
            if (brace != 0) add("{} ${signed(brace)}")
        }
        return Balance(if (problems.isEmpty()) null else problems.joinToString(", "))
    }

    private fun signed(v: Int) = if (v > 0) "+$v (не закрыто)" else "$v (лишние закрывающие)"

    // ═══════════════════════════════════════════════════════════════════════
    // 3. УСАДКА
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Файл не может «похудеть» больше чем до 40% строк, если только пользователь
     * явно не просил удалять. Это страховка от сценария «манифест из 200 строк
     * превратился в 30».
     */
    fun shrinkProblem(original: String, updated: String, instructions: String): String? {
        val origLines = original.lines().count { it.isNotBlank() }
        val newLines = updated.lines().count { it.isNotBlank() }
        if (origLines < 30) return null
        val ratio = newLines.toDouble() / origLines
        if (ratio >= 0.4) return null
        val asksRemoval = Regex("""удал|убер|убра|вырез|очист|remove|delete|strip|drop|clean ?up""", RegexOption.IGNORE_CASE)
            .containsMatchIn(instructions)
        if (asksRemoval && ratio >= 0.15) return null
        return "Файл сократился с $origLines до $newLines непустых строк (${(ratio * 100).toInt()}%) — похоже на потерю кода"
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. ПОЛНАЯ ЗАМЕНА СОДЕРЖИМЫМ ИЗ ПРОМПТА
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Можно ли заменить существующий файл блоком из промпта целиком?
     * @return null — можно; иначе причина, почему блок похож на фрагмент.
     */
    fun fullReplacementProblem(path: String, original: String, candidate: String): String? {
        if (candidate.isBlank()) return "блок пустой"
        findNewPlaceholders(original, candidate).firstOrNull()?.let {
            return "в блоке есть плейсхолдер «${it.take(60)}» — это фрагмент, а не целый файл"
        }
        structureProblem(path, original, candidate)?.let { return it }

        val origLines = original.lines().count { it.isNotBlank() }
        val candLines = candidate.lines().count { it.isNotBlank() }
        if (origLines >= 20 && candLines < origLines * 0.5) {
            return "блок ($candLines строк) намного короче файла ($origLines строк) — вероятно фрагмент"
        }

        when (ext(path)) {
            "kt", "kts", "java" -> {
                val pkgRegex = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
                val origPkg = pkgRegex.find(original)?.groupValues?.get(1)
                val candPkg = pkgRegex.find(candidate)?.groupValues?.get(1)
                if (origPkg != null && candPkg != origPkg) {
                    return "package в блоке (${candPkg ?: "нет"}) не совпадает с файлом ($origPkg)"
                }
            }
            "xml" -> {
                val rootRegex = Regex("""<\s*([A-Za-z_][\w.:-]*)""")
                fun root(s: String) = rootRegex.findAll(s)
                    .map { it.groupValues[1] }
                    .firstOrNull { !it.startsWith("?") && !it.startsWith("!") }
                val origRoot = root(original.replace(Regex("""<\?.*?\?>|<!--[\s\S]*?-->"""), ""))
                val candRoot = root(candidate.replace(Regex("""<\?.*?\?>|<!--[\s\S]*?-->"""), ""))
                if (origRoot != null && origRoot != candRoot) {
                    return "корневой тег блока <$candRoot> не совпадает с файлом <$origRoot>"
                }
                if (path.endsWith("AndroidManifest.xml") &&
                    original.contains("<application") && !candidate.contains("<application")
                ) {
                    return "в манифесте из блока нет <application>"
                }
            }
        }
        return null
    }

    /** Проверка содержимого нового файла (из блока или сгенерированного). */
    fun newFileProblem(path: String, content: String, fromUserBlock: Boolean): String? {
        if (content.isBlank()) return "пустое содержимое"
        findNewPlaceholders("", content).firstOrNull()?.let {
            return "плейсхолдер «${it.take(60)}» вместо кода"
        }
        // Код пользователя пишем как есть (даже если лексер с ним не согласен),
        // а вот сгенерированный моделью — только структурно корректный.
        if (!fromUserBlock) structureProblem(path, null, content)?.let { return it }
        return null
    }
}