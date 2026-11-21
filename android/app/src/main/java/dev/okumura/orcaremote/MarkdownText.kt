package dev.okumura.orcaremote

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.*

@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val document = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { MarkdownChildren(document) }
}

@Composable
private fun InlineText(node: Node, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val context = LocalContext.current
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val accent = orcaAccentText
    val annotated = remember(node, background, accent, context) {
        val openLink = LinkInteractionListener { annotation ->
            val uri = Uri.parse((annotation as LinkAnnotation.Url).url).normalizeScheme()
            val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "リンクを開けませんでした", Toast.LENGTH_SHORT).show()
            }
        }
        markdownInline(node, background, accent, openLink)
    }
    SelectionContainer { Text(annotated, modifier, style = style) }
}

internal inline fun Node.forEachVisibleBlock(render: (Node) -> Unit) {
    for (block in children()) {
        when (block) {
            is LinkReferenceDefinition -> Unit
            else -> render(block)
        }
    }
}

@Composable
private fun MarkdownChildren(parent: Node) {
    val colors = MaterialTheme.colorScheme
    parent.forEachVisibleBlock { block ->
        when (block) {
            is Heading -> {
                val size = listOf(32, 28, 25, 22, 20, 18)[block.level - 1]
                InlineText(block, Modifier.padding(top = 6.dp, bottom = 2.dp).semantics { heading() },
                    MaterialTheme.typography.bodyLarge.copy(
                        fontSize = size.sp, lineHeight = (size + 8).sp, fontWeight = FontWeight.Bold,
                    ))
            }
            is ListBlock -> {
                val start = (block as? OrderedList)?.markerStartNumber
                val digits = start?.let { (it + block.children().count() - 1).toString().length } ?: 0
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((index, item) in block.children().withIndex()) {
                        val marker = start?.let { (it + index).toString().padStart(digits, '\u2007') + "." } ?: "•"
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(marker, Modifier.widthIn(min = 18.dp),
                                textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                MarkdownChildren(item)
                            }
                        }
                    }
                }
            }
            is BlockQuote -> {
                Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow).drawBehind {
                    drawRect(colors.outline, size = Size(3.dp.toPx(), size.height))
                }.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarkdownChildren(block)
                }
            }
            is ThematicBreak -> HorizontalDivider(color = colors.outlineVariant)
            is FencedCodeBlock -> if (block.isMermaid) MermaidDiagram(block.literal) else CodeBlock(block.literal, block.language)
            is IndentedCodeBlock -> CodeBlock(block.literal, "")
            is TableBlock -> MarkdownTable(block)
            else -> InlineText(block)
        }
    }
}

@Composable
internal fun CodeBlock(source: String, language: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainer, shape)
        .border(1.dp, colors.outlineVariant, shape).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(language, Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OrcaTextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(language, source))) }
            }) { Text("コピー") }
        }
        SelectionContainer {
            Text(source.removeSuffix("\n"), Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), softWrap = false)
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        for (section in table.children()) {
            for (row in section.children()) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (cell in row.children().filterIsInstance<TableCell>()) {
                        val style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (cell.isHeader) FontWeight.Bold else FontWeight.Normal,
                            textAlign = when (cell.alignment) {
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            },
                        )
                        Box(Modifier.width(152.dp).fillMaxHeight()
                            .background(if (cell.isHeader) colors.surfaceContainerHigh else colors.surface)
                            .border(0.5.dp, colors.outlineVariant).padding(8.dp)) {
                            InlineText(cell, Modifier.fillMaxWidth(), style)
                        }
                    }
                }
            }
        }
    }
}
