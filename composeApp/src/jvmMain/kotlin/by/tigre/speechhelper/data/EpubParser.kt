package by.tigre.speechhelper.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.util.zip.ZipFile

object EpubParser {

    fun parse(file: File): ParsedBook {
        ZipFile(file).use { zip ->
            // 1. Find container.xml → get path to content.opf
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: error("META-INF/container.xml not found")
            val containerXml = zip.getInputStream(containerEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            val opfPath = Jsoup.parse(containerXml).selectFirst("rootfile")
                ?.attr("full-path")
                ?: error("rootfile not found in container.xml")

            println("[EpubParser] opfPath=$opfPath")

            // 2. Parse content.opf → title + spine order
            val opfEntry = zip.getEntry(opfPath) ?: error("$opfPath not found in epub")
            val opfXml = zip.getInputStream(opfEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            val opfDoc = Jsoup.parse(opfXml)

            val title = opfDoc.selectFirst("metadata > dc\\:title, metadata > title")?.text()?.trim() ?: ""
            println("[EpubParser] title='$title'")

            // Build id→href map from manifest
            val opfDir = opfPath.substringBeforeLast("/", "").let { if (it.isEmpty()) "" else "$it/" }
            val idToHref = opfDoc.select("manifest > item").associate {
                it.attr("id") to it.attr("href")
            }

            // Get spine order (idref list)
            val spineIds = opfDoc.select("spine > itemref").map { it.attr("idref") }
            println("[EpubParser] spine items: ${spineIds.size}")

            // 3. Parse each spine item as a chapter
            val chapters = mutableListOf<ParsedChapter>()
            var counter = 0

            for (idref in spineIds) {
                val href = idToHref[idref] ?: continue
                // href may be relative, resolve against opf directory
                val fullPath = when {
                    href.startsWith("/") -> href.trimStart('/')
                    else -> "$opfDir$href"
                }.substringBefore("#") // strip anchor

                val entry = zip.getEntry(fullPath) ?: run {
                    println("[EpubParser] missing entry: $fullPath")
                    continue
                }
                val html = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val htmlDoc = Jsoup.parse(html)

                val chapterTitle = htmlDoc.selectFirst("h1, h2, h3, h4")?.text()?.trim() ?: ""
                val text = extractHtmlText(htmlDoc.body() ?: htmlDoc.selectFirst("body") ?: continue)

                println("[EpubParser] item '$idref' href='$fullPath' title='${chapterTitle.take(50)}' len=${text.length}")

                if (text.isNotBlank()) {
                    counter++
                    chapters.add(ParsedChapter(
                        name = chapterTitle.ifBlank { "Часть $counter" },
                        text = text,
                    ))
                }
            }

            println("[EpubParser] total chapters: ${chapters.size}")
            return ParsedBook(
                title = title.ifBlank { file.nameWithoutExtension },
                chapters = chapters,
            )
        }
    }

    private fun extractHtmlText(body: Element): String {
        val lines = mutableListOf<String>()
        for (child in body.children()) {
            val tag = child.tagName()
            when {
                tag in setOf("script", "style", "img", "figure", "nav") -> Unit
                tag.matches(Regex("h[1-6]")) -> {
                    val text = child.text().trim()
                    if (text.isNotBlank()) lines.add(text)
                }
                tag == "p" || tag == "div" || tag == "section" || tag == "article" -> {
                    if (child.children().isEmpty()) {
                        // Leaf block
                        val text = child.text().trim()
                        if (text.isNotBlank()) lines.add(text)
                    } else {
                        // Recurse into containers
                        val inner = extractHtmlText(child)
                        if (inner.isNotBlank()) lines.add(inner)
                    }
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
