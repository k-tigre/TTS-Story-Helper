package by.tigre.speechhelper.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
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
                val bodyEl = htmlDoc.body() ?: htmlDoc.selectFirst("body")
                if (bodyEl == null) {
                    println("[EpubParser] no body in '$fullPath', skip")
                    continue
                }
                val text = extractHtmlText(bodyEl)

                println("[EpubParser] item '$idref' href='$fullPath' title='${chapterTitle.take(50)}' len=${text.length}")

                if (text.isNotBlank()) {
                    counter++
                    val name = chapterTitle.ifBlank { "Часть $counter" }
                    chapters.add(
                        ParsedChapter(
                            name = name,
                            text = chapterTextWithEmbeddedTitle(name, text),
                        ),
                    )
                }
            }

            println("[EpubParser] total chapters: ${chapters.size}")
            return ParsedBook(
                title = title.ifBlank { file.nameWithoutExtension },
                chapters = chapters,
            )
        }
    }

    /**
     * EPUB chapters are often XHTML with mixed content, e.g. `<p>…<img/>…</p>`.
     * Walking only [Element.children] drops text nodes and can yield empty "paragraphs"
     * after the first inline tag. We walk [org.jsoup.nodes.Node.childNodes] and use
     * full [Element.text] for known block tags.
     */
    private fun extractHtmlText(root: Element): String {
        val lines = mutableListOf<String>()

        fun walk(parent: Element) {
            for (node in parent.childNodes()) {
                when (node) {
                    is TextNode -> {
                        val t = node.text().trim()
                        if (t.isNotEmpty()) lines.add(t)
                    }
                    is Element -> {
                        val tag = node.tagName().lowercase()
                        when {
                            tag in setOf("script", "style", "img", "figure", "nav", "noscript") -> Unit
                            tag.matches(Regex("h[1-6]")) -> {
                                val t = node.text().trim()
                                if (t.isNotBlank()) lines.add(t)
                            }
                            tag in setOf(
                                "p",
                                "li",
                                "blockquote",
                                "pre",
                                "td",
                                "th",
                                "dt",
                                "dd",
                                "caption",
                                "figcaption",
                                "address",
                            ) -> {
                                val t =
                                    if (tag == "pre") node.wholeText().trim()
                                    else node.text().trim()
                                if (t.isNotBlank()) lines.add(t)
                            }
                            else -> walk(node)
                        }
                    }
                }
            }
        }

        walk(root)
        return lines.joinToString("\n\n").trim()
    }
}
