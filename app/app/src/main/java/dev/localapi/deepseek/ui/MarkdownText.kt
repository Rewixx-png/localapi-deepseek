package dev.localapi.deepseek.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SYNTAX_TOKEN_RE =
    Regex("(//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Z][A-Za-z0-9_]*\\b|\\b[a-z_][a-zA-Z0-9_]*\\b)")
private val INLINE_TOKEN_RE = Regex(
    "`([^`\\n]+)`|\\[\\[font:([a-z-]+)\\|([\\s\\S]*?)\\]\\]|\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)|\\*\\*([^*\n]+)\\*\\*|__([^_\n]+)__|~~([^~\n]+)~~|\\*([^*\n]+)\\*|(https?://[^\\s<\\]]+)"
)

@Composable
fun MarkdownMessageContent(
    rawText: String,
    textScale: Float,
    streaming: Boolean,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onPreviewHtml: (String) -> Unit,
    viewportTopProvider: () -> Float = { 0f },
    scrollTickProvider: () -> Int = { 0 },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { KatexBitmapRenderer.warmup(context) }

    val blocks = remember(rawText) { parseMarkdown(rawText) }
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val bodySize = (15.5f * textScale).sp
    val bodyLine = (23f * textScale).sp

    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { idx, block ->
                val blockStreaming = streaming && idx == blocks.lastIndex
                val cursor = if (blockStreaming) " ▍" else ""
                when (block) {
                    is MdBlock.Paragraph -> {
                        if (!blockStreaming && containsLatexMath(block.text)) {
                            KatexRichBlock(
                                htmlBody = inlineMarkdownToSafeHtml(block.text),
                                textScale = textScale,
                                isDisplay = false,
                                baseFontSp = 15.5f,
                                onOpenUrl = onOpenUrl
                            )
                        } else {
                            Text(
                                text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, lineHeight = bodyLine)
                            )
                        }
                    }
                    is MdBlock.Heading -> {
                        val baseSp = when (block.level) {
                            1 -> 21f
                            2 -> 18.5f
                            3 -> 17f
                            else -> 15.5f
                        }
                        if (!blockStreaming && containsLatexMath(block.text)) {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                KatexRichBlock(
                                    htmlBody = inlineMarkdownToSafeHtml(block.text),
                                    textScale = textScale,
                                    isDisplay = false,
                                    baseFontSp = baseSp,
                                    isBold = true,
                                    onOpenUrl = onOpenUrl
                                )
                            }
                        } else {
                            Text(
                                text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = (baseSp * textScale).sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    is MdBlock.Quote -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        if (!blockStreaming && containsLatexMath(block.text)) {
                            Box(modifier = Modifier.weight(1f)) {
                                KatexRichBlock(
                                    htmlBody = inlineMarkdownToSafeHtml(block.text),
                                    textScale = textScale,
                                    isDisplay = false,
                                    baseFontSp = 14.5f,
                                    textColorOverride = onSurfaceVariant,
                                    onOpenUrl = onOpenUrl
                                )
                            }
                        } else {
                            Text(
                                text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = bodySize * 0.94f, lineHeight = bodyLine * 0.94f),
                                color = onSurfaceVariant
                            )
                        }
                    }
                    is MdBlock.ListItem -> Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                        Text(
                            text = block.bullet,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        if (!blockStreaming && containsLatexMath(block.text)) {
                            Box(modifier = Modifier.weight(1f)) {
                                KatexRichBlock(
                                    htmlBody = inlineMarkdownToSafeHtml(block.text),
                                    textScale = textScale,
                                    isDisplay = false,
                                    baseFontSp = 15.5f,
                                    onOpenUrl = onOpenUrl
                                )
                            }
                        } else {
                            Text(
                                text = buildInlineAnnotated(block.text + cursor, primary, onOpenUrl),
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = bodySize, lineHeight = bodyLine),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is MdBlock.Rule -> HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    is MdBlock.DisplayMath -> Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            val availWidth = maxWidth
                            if (blockStreaming) {
                                Text(
                                    text = block.expr,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = (13.5f * textScale).sp
                                    ),
                                    color = onSurfaceVariant
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    Box(
                                        modifier = Modifier.widthIn(min = availWidth),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        KatexRichBlock(
                                            htmlBody = "\\[${escapeHtml(block.expr)}\\]",
                                            textScale = textScale,
                                            isDisplay = true,
                                            availWidthDp = availWidth,
                                            baseFontSp = 15.5f,
                                            allowTouchOverride = false,
                                            onOpenUrl = onOpenUrl
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is MdBlock.Code -> CodeBlockCard(
                        lang = block.lang,
                        code = block.code,
                        textScale = textScale,
                        onCopy = { onCopy(block.code) },
                        onPreviewHtml = { onPreviewHtml(block.code) },
                        viewportTopProvider = viewportTopProvider,
                        scrollTickProvider = scrollTickProvider
                    )
                    is MdBlock.Table -> MarkdownTableCard(
                        table = block,
                        textScale = textScale,
                        streaming = blockStreaming,
                        primary = primary,
                        onCopy = onCopy,
                        onOpenUrl = onOpenUrl
                    )
                }
            }
            if (blocks.isEmpty() && streaming) {
                Text("▍", color = MaterialTheme.colorScheme.primary, fontSize = bodySize)
            }
        }
    }
}

@Composable
private fun CodeBlockCard(
    lang: String,
    code: String,
    textScale: Float,
    onCopy: () -> Unit,
    onPreviewHtml: () -> Unit,
    viewportTopProvider: () -> Float,
    scrollTickProvider: () -> Int
) {
    var expanded by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val lines = remember(code) { code.trimEnd('\n').lines() }
    val canRun = lang.equals("html", true) || lang.equals("htm", true)
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlighted = remember(code, lang, darkTheme) { highlightSyntax(code.trimEnd('\n'), lang, darkTheme) }
    val chevronRot by animateFloatAsState(if (expanded) 180f else 0f, ExpressiveSpring, label = "code_chev")
    val copyBg by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "code_copy_bg"
    )
    val copyInteraction = remember { MutableInteractionSource() }
    val runInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    ) {
        Layout(
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { expanded = !expanded }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            lang.ifEmpty { "code" }.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            lineCountLabel(lines.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        if (canRun) {
                            Surface(
                                onClick = onPreviewHtml,
                                interactionSource = runInteraction,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.springBounce(runInteraction).padding(end = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(14.dp))
                                    Text("Открыть", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                        }
                        Surface(
                            onClick = {
                                onCopy()
                                copied = true
                            },
                            interactionSource = copyInteraction,
                            shape = CircleShape,
                            color = copyBg,
                            modifier = Modifier.springBounce(copyInteraction)
                        ) {
                            AnimatedContent(
                                targetState = copied,
                                transitionSpec = {
                                    (fadeIn(tween(150)) + scaleIn(initialScale = 0.9f)) togetherWith
                                        (fadeOut(tween(110)) + scaleOut(targetScale = 0.9f))
                                },
                                label = "code_copy_anim"
                            ) { isCopied ->
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        tint = if (isCopied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        if (isCopied) "Скопировано" else "Копировать",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isCopied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).rotate(chevronRot)
                        )
                    }
                    if (expanded) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) + fadeIn(tween(150)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                ) {
                    val fontSize = (12.5f * textScale).sp
                    val lineHeight = (18.5f * textScale).sp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = (1..lines.size).joinToString("\n"),
                            softWrap = false,
                            modifier = Modifier.padding(start = 12.dp, end = 10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                textAlign = TextAlign.End
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        )
                        Text(
                            text = highlighted,
                            softWrap = false,
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                lineHeight = lineHeight
                            )
                        )
                    }
                }
            }
        ) { measurables, constraints ->
            val childConstraints = constraints.copy(minHeight = 0)
            val headerP = measurables[0].measure(childConstraints)
            val bodyP = measurables.getOrNull(1)?.measure(childConstraints)
            val bodyH = bodyP?.height ?: 0
            val width = maxOf(headerP.width, bodyP?.width ?: 0)
            val totalH = headerP.height + bodyH
            layout(width, totalH) {
                scrollTickProvider()
                bodyP?.placeRelative(0, headerP.height, zIndex = 0f)
                val coords = coordinates
                val stickyY = if (expanded && bodyH > headerP.height && coords != null && coords.isAttached) {
                    val topInWindow = coords.positionInWindow().y
                    val scrolledPast = (viewportTopProvider() - topInWindow).roundToInt()
                    val maxOffset = (totalH - headerP.height).coerceAtLeast(0)
                    scrolledPast.coerceIn(0, maxOffset)
                } else {
                    0
                }
                headerP.placeRelative(0, stickyY, zIndex = 2f)
            }
        }
    }
}

@Composable
private fun MarkdownTableCard(
    table: MdBlock.Table,
    textScale: Float,
    streaming: Boolean,
    primary: Color,
    onCopy: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val chevronRot by animateFloatAsState(if (expanded) 180f else 0f, ExpressiveSpring, label = "table_chev")
    val copyBg by animateColorAsState(
        if (copied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "table_copy_bg"
    )
    val copyInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }

    val cellFontSp = 13.5f
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availWidth = maxWidth
        val colCount = table.headers.size
        val colWidths = remember(table, textScale, availWidth) {
            val minCol = (64f * textScale).coerceIn(52f, 128f)
            val maxCol = (260f * textScale).coerceIn(180f, 440f)
            val natural = (0 until colCount).map { col ->
                val headerVis = estimateVisibleTextLen(table.headers[col])
                val headerWidth = minOf(headerVis, 30) * 9.6f * textScale + 26f
                val cellWidth = table.rows.maxOfOrNull { row ->
                    val cell = row.getOrElse(col) { "" }
                    val vis = estimateVisibleTextLen(cell)
                    val longestWord = cell.split(SPLIT_WS_RE).maxOfOrNull { estimateVisibleTextLen(it) } ?: 0
                    val sampleLen = maxOf(minOf(vis, 30), minOf(longestWord, 34))
                    sampleLen * 8.6f * textScale + 26f
                } ?: 0f
                maxOf(headerWidth, cellWidth).coerceIn(minCol, maxCol).dp
            }
            val total = natural.fold(0.dp) { acc, w -> acc + w }
            if (total < availWidth && total.value > 0f) {
                val factor = availWidth / total
                natural.map { it * factor }
            } else {
                natural
            }
        }
        val totalWidth = remember(colWidths, availWidth) {
            colWidths.fold(0.dp) { acc, w -> acc + w }.coerceAtLeast(availWidth)
        }
        val scrollState = rememberScrollState()

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(spring(dampingRatio = 0.78f, stiffness = 420f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ТАБЛИЦА",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${table.rows.size} × ${table.headers.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        onClick = {
                            onCopy(tableToMarkdown(table))
                            copied = true
                        },
                        interactionSource = copyInteraction,
                        shape = CircleShape,
                        color = copyBg,
                        modifier = Modifier.springBounce(copyInteraction)
                    ) {
                        AnimatedContent(
                            targetState = copied,
                            transitionSpec = {
                                (fadeIn(tween(150)) + scaleIn(initialScale = 0.9f)) togetherWith
                                    (fadeOut(tween(110)) + scaleOut(targetScale = 0.9f))
                            },
                            label = "table_copy_anim"
                        ) { isCopied ->
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = if (isCopied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    if (isCopied) "Скопировано" else "Копировать",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCopied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp).rotate(chevronRot)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(scrollState)
                        ) {
                            Column(modifier = Modifier.width(totalWidth)) {
                                Row(
                                    modifier = Modifier
                                        .width(totalWidth)
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    table.headers.forEachIndexed { idx, h ->
                                        TableCellContent(
                                            text = h,
                                            width = colWidths[idx],
                                            align = table.aligns.getOrElse(idx) { TextAlign.Start },
                                            fontSp = cellFontSp,
                                            textScale = textScale,
                                            isHeader = true,
                                            streaming = streaming,
                                            primary = primary,
                                            onOpenUrl = onOpenUrl
                                        )
                                    }
                                }
                                table.rows.forEachIndexed { rIdx, row ->
                                    HorizontalDivider(
                                        modifier = Modifier.width(totalWidth),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                    )
                                    Row(
                                        modifier = Modifier
                                            .width(totalWidth)
                                            .background(
                                                if (rIdx % 2 == 1) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
                                                else Color.Transparent
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        row.forEachIndexed { idx, cell ->
                                            TableCellContent(
                                                text = cell,
                                                width = colWidths[idx],
                                                align = table.aligns.getOrElse(idx) { TextAlign.Start },
                                                fontSp = cellFontSp,
                                                textScale = textScale,
                                                isHeader = false,
                                                streaming = streaming,
                                                primary = primary,
                                                onOpenUrl = onOpenUrl
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCellContent(
    text: String,
    width: Dp,
    align: TextAlign,
    fontSp: Float,
    textScale: Float,
    isHeader: Boolean,
    streaming: Boolean,
    primary: Color,
    onOpenUrl: (String) -> Unit
) {
    val cellPadH = 12.dp
    val cellPadV = 9.dp
    if (!streaming && containsLatexMath(text)) {
        Box(
            modifier = Modifier
                .width(width)
                .padding(horizontal = cellPadH, vertical = cellPadV)
        ) {
            KatexRichBlock(
                htmlBody = inlineMarkdownToSafeHtml(text),
                textScale = textScale,
                isDisplay = false,
                availWidthDp = (width - cellPadH * 2).coerceAtLeast(36.dp),
                baseFontSp = fontSp,
                textAlign = align,
                isBold = isHeader,
                allowTouchOverride = false,
                onOpenUrl = onOpenUrl
            )
        }
    } else {
        Text(
            text = buildInlineAnnotated(text, primary, onOpenUrl),
            modifier = Modifier
                .width(width)
                .padding(horizontal = cellPadH, vertical = cellPadV),
            style = if (isHeader) {
                MaterialTheme.typography.labelMedium.copy(
                    fontSize = (fontSp * textScale).sp,
                    lineHeight = ((fontSp + 5.5f) * textScale).sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                MaterialTheme.typography.bodySmall.copy(
                    fontSize = (fontSp * textScale).sp,
                    lineHeight = ((fontSp + 6f) * textScale).sp
                )
            },
            textAlign = align
        )
    }
}

private fun lineCountLabel(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "$n строка"
        m10 in 2..4 && m100 !in 12..14 -> "$n строки"
        else -> "$n строк"
    }
}

private fun buildInlineAnnotated(
    text: String,
    accentColor: Color,
    onOpenUrl: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
    var idx = 0
    for (m in INLINE_TOKEN_RE.findAll(text)) {
        if (m.range.first > idx) append(text.substring(idx, m.range.first))
        val v = m.groupValues
        when {
            v[1].isNotEmpty() -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accentColor, fontWeight = FontWeight.Medium))
                append(v[1])
                pop()
            }
            v[2].isNotEmpty() -> {
                val style = when (v[2].lowercase()) {
                    "serif" -> SpanStyle(fontFamily = FontFamily.Serif)
                    "sans" -> SpanStyle(fontFamily = FontFamily.SansSerif)
                    "mono" -> SpanStyle(fontFamily = FontFamily.Monospace)
                    "cursive" -> SpanStyle(fontFamily = FontFamily.Cursive)
                    "light" -> SpanStyle(fontWeight = FontWeight.Light)
                    "black" -> SpanStyle(fontWeight = FontWeight.Black)
                    else -> SpanStyle(fontWeight = FontWeight.Medium)
                }
                pushStyle(style); append(v[3]); pop()
            }
            v[4].isNotEmpty() && v[5].isNotEmpty() -> {
                val url = v[5]
                pushLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline))) { onOpenUrl(url) })
                append(v[4])
                pop()
            }
            v[6].isNotEmpty() || v[7].isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(v[6].ifEmpty { v[7] })
                pop()
            }
            v[8].isNotEmpty() -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(v[8])
                pop()
            }
            v[9].isNotEmpty() -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(v[9])
                pop()
            }
            v[10].isNotEmpty() -> {
                val clean = v[10].trimEnd('.', ',', ';', ':', '!', '?', ')')
                val tail = v[10].removePrefix(clean)
                pushLink(LinkAnnotation.Url(clean, TextLinkStyles(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline))) { onOpenUrl(clean) })
                append(clean)
                pop()
                append(tail)
            }
            else -> append(m.value)
        }
        idx = m.range.last + 1
    }
    if (idx < text.length) append(text.substring(idx))
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "fn", "let", "mut", "pub", "impl", "struct", "enum", "trait", "match",
    "if", "else", "for", "while", "loop", "return", "break", "continue", "import", "package",
    "use", "mod", "crate", "async", "await", "class", "interface", "object", "data", "sealed",
    "def", "lambda", "from", "as", "with", "try", "catch", "finally", "throw", "throws",
    "const", "function", "export", "default", "true", "false", "null", "None", "self", "this",
    "public", "private", "static", "final", "void", "new", "override", "suspend", "when"
)

private fun highlightSyntax(code: String, lang: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
    val cComment = if (dark) Color(0xFF7F848E) else Color(0xFF6B7280)
    val cKeyword = if (dark) Color(0xFFC792EA) else Color(0xFF7C3AED)
    val cString = if (dark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
    val cNumber = if (dark) Color(0xFFFFCC80) else Color(0xFFB45309)
    val cType = if (dark) Color(0xFF82AAFF) else Color(0xFF1D4ED8)
    var idx = 0
    for (m in SYNTAX_TOKEN_RE.findAll(code)) {
        if (m.range.first > idx) append(code.substring(idx, m.range.first))
        val tok = m.value
        val color = when {
            tok.startsWith("//") || tok.startsWith("/*") || (tok.startsWith("#") && !lang.equals("c", true) && !lang.equals("cpp", true)) -> cComment
            tok.startsWith("\"") || tok.startsWith("'") -> cString
            tok.first().isDigit() -> cNumber
            tok in KEYWORDS -> cKeyword
            tok.first().isUpperCase() -> cType
            else -> Color.Unspecified
        }
        if (color != Color.Unspecified) {
            pushStyle(SpanStyle(color = color, fontStyle = if (color == cComment) FontStyle.Italic else FontStyle.Normal))
            append(tok)
            pop()
        } else {
            append(tok)
        }
        idx = m.range.last + 1
    }
    if (idx < code.length) append(code.substring(idx))
}
