package dev.okumura.orcaremote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import java.net.URI
import java.net.URISyntaxException

private val markdownParser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

internal fun parseMarkdown(text: String): Node = markdownParser.parse(text)

internal fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

internal val FencedCodeBlock.language: String
    get() = info.orEmpty().trim().takeWhile { !it.isWhitespace() }

internal val FencedCodeBlock.isMermaid: Boolean
    get() = language.equals("mermaid", ignoreCase = true)

internal fun isWebLink(url: String): Boolean = try {
    val uri = URI(url)
    (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrEmpty()
} catch (_: URISyntaxException) {
    false
}

internal fun markdownInline(
    node: Node,
    codeBackground: Color = Color.Unspecified,
    accent: Color = Color.Unspecified,
    onLink: LinkInteractionListener? = null,
): AnnotatedString = buildAnnotatedString {
    fun render(part: Node) {
        fun contents() = part.children().forEach(::render)
        when (part) {
            is Text -> append(part.literal)
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(part.literal)
            }
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { contents() }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { contents() }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { contents() }
            is Link -> if (isWebLink(part.destination)) {
                val style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                withLink(LinkAnnotation.Url(part.destination, TextLinkStyles(style), onLink)) { contents() }
            } else {
                contents()
            }
            is HtmlInline -> append(part.literal)
            is HtmlBlock -> append(part.literal.trimEnd('\n'))
            else -> contents()
        }
    }
    render(node)
}

// Parse only as far as the first visible line, even for a long conversation or summary.
internal fun markdownPreview(text: String?): String? = text?.lineSequence()
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.map { markdownInline(parseMarkdown(it)).text.trim() }
    ?.firstOrNull(String::isNotEmpty)
