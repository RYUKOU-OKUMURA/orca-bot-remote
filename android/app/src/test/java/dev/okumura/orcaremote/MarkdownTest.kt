package dev.okumura.orcaremote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test
    fun headingsPreserveSixLevelsAndParagraphBreaks() {
        for (level in 1..6) {
            val heading = parseMarkdown("#".repeat(level) + " Title").firstChild as Heading
            assertEquals(level, heading.level)
            assertEquals("Title", markdownInline(heading).text)
        }
        val parts = parseMarkdown("one\ntwo  \nthree\n\n> quote\n\n---").children().toList()
        assertEquals("one\ntwo\nthree", markdownInline(parts.first()).text)
        assertTrue(parts[1] is BlockQuote)
        assertTrue(parts[2] is ThematicBreak)
    }

    @Test
    fun inlineSpansCoverOnlyStyledText() {
        val text = markdownInline(parseMarkdown("**bold** *italic* ~~gone~~ `code`"), codeBackground = Color.Gray)
        assertEquals("bold italic gone code", text.text)
        val spans = text.spanStyles.associate { text.text.substring(it.start, it.end) to it.item }
        assertEquals(FontWeight.Bold, spans["bold"]?.fontWeight)
        assertEquals(FontStyle.Italic, spans["italic"]?.fontStyle)
        assertEquals(TextDecoration.LineThrough, spans["gone"]?.textDecoration)
        assertEquals(FontFamily.Monospace, spans["code"]?.fontFamily)
        assertEquals(Color.Gray, spans["code"]?.background)
    }

    @Test
    fun onlyAbsoluteHttpLinksAreInteractive() {
        val allowed = listOf("https://example.org", "HTTP://example.org/a?q=1#jump")
        val blocked = listOf("javascript:noop", "FiLe:///tmp/file", "data:text/plain,test", "intent://host",
            "//example.org", "/relative", "https:opaque", "https://", "http://bad host")
        for (url in allowed + blocked) {
            assertEquals(url, url in allowed, isWebLink(url))
        }
        val text = markdownInline(parseMarkdown("[**go**](https://example.org) [file](file:///tmp) [bad](javascript:noop)"))
        assertEquals("go file bad", text.text)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("go", text.text.substring(link.start, link.end))
        assertEquals("https://example.org", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun referenceDefinitionOccupiesNoBlockAndStillResolvesTheLink() {
        val blocks = buildList {
            parseMarkdown("[docs]: https://example.org/docs\n\nSee [docs].").forEachVisibleBlock { add(it) }
        }
        val paragraph = markdownInline(blocks.single() as Paragraph)
        assertEquals("See docs.", paragraph.text)
        val link = paragraph.getLinkAnnotations(0, paragraph.length).single()
        assertEquals("https://example.org/docs", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun bareWebUrlsAreLinkedOutsideCodeAndExistingLinks() {
        val text = markdownInline(parseMarkdown(
            "See https://example.org/path, http://example.net. `https://code.org` " +
                "[https://label.org](https://target.org) ftp://files.org file:///tmp",
        ))
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("https://example.org/path", "http://example.net", "https://target.org"),
            links.map { (it.item as LinkAnnotation.Url).url })
        assertEquals("https://label.org", text.text.substring(links.last().start, links.last().end))
    }

    @Test
    fun numberedListsKeepStartNumberAndNestedBulletItems() {
        val list = parseMarkdown("4. parent\n   - one\n   - two\n5. next").firstChild as OrderedList
        assertEquals(4, list.markerStartNumber)
        val items = list.children().toList()
        assertEquals(2, items.size)
        val nested = items.first().lastChild as BulletList
        assertEquals(listOf("one", "two"), nested.children().map { markdownInline(it).text }.toList())
    }

    @Test
    fun tableKeepsHeadersAlignmentAndInlineCellContent() {
        val table = parseMarkdown("| Name | State |\n|---|---:|\n| **job** | [`ok`](https://example.org) |")
            .firstChild as TableBlock
        val header = table.firstChild.firstChild.firstChild as TableCell
        assertTrue(header.isHeader)
        val row = table.lastChild.firstChild
        val name = markdownInline(row.firstChild)
        assertEquals(FontWeight.Bold, name.spanStyles.single().item.fontWeight)
        val status = row.lastChild as TableCell
        assertFalse(status.isHeader)
        assertEquals(TableCell.Alignment.RIGHT, status.alignment)
        val text = markdownInline(status)
        assertEquals("ok", text.text)
        assertEquals(FontFamily.Monospace, text.spanStyles.single().item.fontFamily)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }

    @Test
    fun mermaidFenceIsSeparatedWithoutChangingItsSourceOrOtherCode() {
        val document = parseMarkdown("before\n\n```Mermaid\nflowchart LR\n  A --> B\n```\n\n~~~kotlin title\nval x = 1\n~~~\n\nafter")
        val blocks = document.children().toList()
        assertEquals(4, blocks.size)
        val diagram = blocks[1] as FencedCodeBlock
        assertTrue(diagram.isMermaid)
        assertEquals("flowchart LR\n  A --> B\n", diagram.literal)
        val code = blocks[2] as FencedCodeBlock
        assertFalse(code.isMermaid)
        assertEquals("kotlin", code.language)
    }

    @Test
    fun previewUsesFirstVisibleLineAndPreservesLiteralPunctuation() {
        assertEquals("今日のまとめ", markdownPreview("\n---\n## **今日**の`まとめ`\nnext"))
        assertEquals("issue #24", markdownPreview("- [issue](https://example.org) #24\n- next"))
        assertEquals("a_b * b", markdownPreview("a_b * b"))
        assertEquals("Heading", markdownPreview("> ### Heading"))
        assertNull(markdownPreview(" \n---\n"))
        assertNull(markdownPreview(null))
    }
}
