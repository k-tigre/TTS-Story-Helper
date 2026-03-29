package by.tigre.speechhelper.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File

data class ParsedBook(val title: String, val chapters: List<ParsedChapter>)
data class ParsedChapter(val name: String, val text: String)

/** Вставляет название главы в начало текста, если его там ещё нет (FB2 не включает title в тело; EPUB часто уже даёт заголовок как h1). */
internal fun chapterTextWithEmbeddedTitle(chapterName: String, bodyText: String): String {
    val name = chapterName.trim()
    val body = bodyText.trimStart()
    if (name.isBlank()) return bodyText.trim()
    val firstNonEmpty = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    if (firstNonEmpty == name) return bodyText.trim()
    return "$name\n\n${bodyText.trimStart()}"
}

object Fb2Parser {

    fun parse(file: File): ParsedBook {
        val doc = Jsoup.parse(file, "UTF-8", "", Parser.xmlParser())

        val title = doc.selectFirst("description > title-info > book-title")?.text()?.trim() ?: ""
        val body = doc.selectFirst("body") ?: return ParsedBook(title.ifBlank { file.nameWithoutExtension }, emptyList())

        println("[Fb2Parser] title='$title'")
        println("[Fb2Parser] body direct children: ${body.children().map { it.tagName() }}")

        val chapters = mutableListOf<ParsedChapter>()
        val counter = intArrayOf(0)
        extractSections(body, chapters, counter, depth = 0)

        println("[Fb2Parser] total chapters extracted: ${chapters.size}")
        chapters.forEachIndexed { i, ch ->
            println("[Fb2Parser]   [$i] '${ch.name}' — ${ch.text.length} chars")
        }

        return ParsedBook(
            title = title.ifBlank { file.nameWithoutExtension },
            chapters = chapters,
        )
    }

    private fun extractSections(parent: Element, result: MutableList<ParsedChapter>, counter: IntArray, depth: Int) {
        val indent = "  ".repeat(depth)
        for (section in parent.children().filter { it.tagName() == "section" }) {
            val chapterName = section.selectFirst("> title")?.text()?.trim() ?: ""
            val hasNestedSections = section.children().any { it.tagName() == "section" }
            // Decide by direct <p> tags only — epigraph/poem/cite are structural, not "chapter body"
            val hasDirectParagraphs = section.children().any { it.tagName() == "p" || it.tagName() == "v" }

            println("[Fb2Parser] ${indent}section '${chapterName.take(50)}' hasP=$hasDirectParagraphs hasNested=$hasNestedSections children=${section.children().map { it.tagName() }}")

            when {
                hasDirectParagraphs -> {
                    // Has real text — add as chapter, ignore nested sections inside
                    val rawText = extractSectionText(section)
                    counter[0]++
                    val name = chapterName.ifBlank { "Часть ${counter[0]}" }
                    val text = chapterTextWithEmbeddedTitle(name, rawText)
                    result.add(ParsedChapter(name = name, text = text))
                    println("[Fb2Parser] ${indent}  → added as chapter '$name' (${text.length} chars)")
                }
                hasNestedSections -> {
                    // Container-only section — recurse
                    println("[Fb2Parser] ${indent}  → container, recursing...")
                    extractSections(section, result, counter, depth + 1)
                }
                else -> {
                    // Leaf section with no <p> and no nested sections (e.g. only image)
                    val rawText = extractSectionText(section)
                    if (rawText.isNotBlank()) {
                        counter[0]++
                        val name = chapterName.ifBlank { "Часть ${counter[0]}" }
                        val text = chapterTextWithEmbeddedTitle(name, rawText)
                        result.add(ParsedChapter(name = name, text = text))
                        println("[Fb2Parser] ${indent}  → added as leaf chapter (${text.length} chars)")
                    } else {
                        println("[Fb2Parser] ${indent}  → skipped (empty)")
                    }
                }
            }
        }
    }

    private fun extractSectionText(section: Element): String {
        val lines = mutableListOf<String>()
        for (child in section.children()) {
            when (child.tagName()) {
                "title", "section", "image", "binary" -> Unit
                "p", "subtitle", "v" -> {
                    val text = child.text().trim()
                    if (text.isNotBlank()) lines.add(text)
                }
                "empty-line" -> lines.add("")
                "poem", "cite", "epigraph" -> {
                    val text = child.select("p, subtitle, v").joinToString("\n") { it.text().trim() }.trim()
                    if (text.isNotBlank()) lines.add(text)
                }
                else -> {
                    val text = child.text().trim()
                    if (text.isNotBlank()) lines.add(text)
                }
            }
        }
        return lines.joinToString("\n\n").trim()
    }
}
