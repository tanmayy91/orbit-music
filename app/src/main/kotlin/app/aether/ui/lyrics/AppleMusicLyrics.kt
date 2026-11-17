package app.aether.ui.lyrics

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.art.SoftArt
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Sans
import app.aether.core.lyrics.LyricsLine
import app.aether.core.model.Track
import app.aether.ui.lyrics.line.LyricClock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val AppleLeadStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.W600,
    fontSize = 30.sp,
    lineHeight = 38.sp,
    letterSpacing = (-0.015).em,
)

private val AppleBackingStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.W500,
    fontSize = 21.sp,
    lineHeight = 27.sp,
)

private val AppleGlossStyle = TextStyle(
    fontFamily = Sans,
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.W400,
    fontSize = 16.sp,
    lineHeight = 22.sp,
)

private val AppleFalloffAlpha = floatArrayOf(1f, 0.52f, 0.42f, 0.34f, 0.28f)
private val AppleFalloffBlur = floatArrayOf(0f, 1.2f, 2f, 2.8f, 3.4f)
private val AppleDuetInset = 42.dp
private val AppleGutter = 28.dp
private val AppleWipeFeather = 28.dp
private const val AppleUnsungAlpha = 0.4f
private const val AppleAnchor = 0.30f

/**
 * Apple Music-inspired synced lyrics: vibrant ambient blurred album canvas, bold geometric type,
 * active-line spring focus with depth blur, word-by-word feathered fill, and breathing interlude dots.
 * When [animate] is false (disabled in Settings > Appearance), animations and moving ambient effects
 * are turned off for a calm, static reading mode.
 */
@Composable
internal fun AppleMusicLyrics(
    track: Track,
    lines: List<LyricsLine>,
    glosses: List<String?>,
    positionNow: () -> Long,
    playing: Boolean,
    animate: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(lines) { VLine.of(lines) }
    val clock = rememberAppleClock(rows, positionNow, playing)
    val duet = remember(rows) { rows.any { it.alignEnd } }
    val depthBlur = animate && Build.VERSION.SDK_INT >= 31
    val active by remember(rows) { derivedStateOf { activeRows(rows, clock.longValue) } }
    val currentLine = active.firstOrNull() ?: -1

    val list = remember(rows) { LazyListState() }
    var browsing by remember(rows) { mutableStateOf(false) }
    var placed by remember(rows) { mutableStateOf(false) }

    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) browsing = true }
    }
    val currentTarget by rememberUpdatedState(currentLine)
    val onScreen by remember(list) { derivedStateOf { list.layoutInfo.visibleItemsInfo.any { it.index == currentTarget } } }
    LaunchedEffect(browsing, onScreen, list.isScrollInProgress, playing) {
        if (playing && browsing && !list.isScrollInProgress) {
            delay(if (onScreen) 750L else 4_500L)
            browsing = false
        }
    }
    LaunchedEffect(currentLine, browsing, rows, animate) {
        if (browsing || currentLine !in rows.indices) return@LaunchedEffect
        snapshotFlow { list.layoutInfo }.first { it.viewportSize.height > 0 }
        if (!placed || !animate) {
            list.scrollToItem(currentLine)
            placed = true
        } else {
            list.animateScrollToItem(currentLine)
        }
    }

    Box(modifier.fillMaxSize().background(Ink.Black).testTag("apple_music_lyrics_view")) {
        if (animate && !track.artwork.isEmpty) {
            var drift by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(playing) {
                if (!playing) return@LaunchedEffect
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    drift += ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                    last = now
                }
            }
            SoftArt(
                track.artwork,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val pulse = 1.24f + 0.08f * sin(drift * 0.45f)
                        scaleX = pulse
                        scaleY = pulse
                        translationX = size.width * 0.04f * sin(drift * 0.31f)
                        translationY = size.height * 0.04f * sin(drift * 0.23f + 1.2f)
                        alpha = 0.56f
                    },
                px = 64,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Ink.Black.copy(alpha = 0.78f),
                            0.28f to Ink.Black.copy(alpha = 0.40f),
                            0.72f to Ink.Black.copy(alpha = 0.48f),
                            1f to Ink.Black.copy(alpha = 0.88f),
                        ),
                    ),
            )
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(top = 44.dp),
        ) {
            val anchor = (maxHeight * AppleAnchor).coerceAtLeast(16.dp)
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = anchor, bottom = maxHeight * 0.72f, start = AppleGutter, end = AppleGutter),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(rows, key = { i, _ -> i }) { i, line ->
                    val offset = if (currentLine < 0) 0 else i - currentLine
                    val step = min(abs(offset), AppleFalloffAlpha.lastIndex)
                    val isActive = i in active
                    val targetAlpha = when {
                        isActive -> 1f
                        browsing -> 0.8f
                        else -> AppleFalloffAlpha[step]
                    }
                    val alpha by if (animate) {
                        animateFloatAsState(targetAlpha, Motion.fluid(0.42f, 1f, 0.002f), label = "appleAlpha")
                    } else {
                        rememberUpdatedState(targetAlpha)
                    }
                    val targetBlur = if (!depthBlur || browsing || isActive) 0f else AppleFalloffBlur[min(step, AppleFalloffBlur.lastIndex)]
                    val blur by if (animate) {
                        animateFloatAsState(targetBlur, Motion.fluid(0.42f, 1f, 0.01f), label = "appleBlur")
                    } else {
                        rememberUpdatedState(0f)
                    }
                    val defocus = if (blur > 0.05f) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier

                    if (line.isGap) {
                        AppleGapRow(
                            line = line,
                            until = rows.getOrNull(i + 1)?.timeMs ?: line.endMs,
                            isActive = isActive,
                            animate = animate,
                            clock = clock,
                            alpha = { alpha },
                            defocus = defocus,
                            onSeek = onSeek,
                        )
                    } else {
                        val glossText = glosses.getOrNull(line.origin)?.takeIf { it.isNotBlank() }
                        AppleLineRow(
                            line = line,
                            glossText = glossText,
                            clock = clock,
                            isActive = isActive,
                            sung = offset < 0,
                            duet = duet,
                            animate = animate,
                            browsing = browsing,
                            alpha = { alpha },
                            defocus = defocus,
                            onSeek = onSeek,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberAppleClock(key: Any, positionNow: () -> Long, playing: Boolean): MutableLongState {
    val clock = remember(key) { mutableLongStateOf(positionNow()) }
    val engine = remember(key) { LyricClock() }
    LaunchedEffect(engine, playing) {
        engine.reset()
        if (!playing) {
            while (true) {
                clock.longValue = positionNow()
                delay(120L)
            }
        }
        while (true) {
            withFrameNanos { nanos -> clock.longValue = engine.update(nanos, positionNow(), true).toLong() }
        }
    }
    return clock
}

@Composable
private fun AppleLineRow(
    line: VLine,
    glossText: String?,
    clock: MutableLongState,
    isActive: Boolean,
    sung: Boolean,
    duet: Boolean,
    animate: Boolean,
    browsing: Boolean,
    alpha: () -> Float,
    defocus: Modifier,
    onSeek: (Long) -> Unit,
) {
    val alignEnd = duet && line.alignEnd
    val textAlign = if (alignEnd) TextAlign.Right else TextAlign.Left
    var pressed by remember { mutableStateOf(false) }
    val targetScale = when {
        !animate -> 1f
        pressed -> 0.96f
        isActive -> 1.03f
        else -> 0.94f
    }
    val scale by if (animate) {
        animateFloatAsState(targetScale, Motion.fluid(if (pressed) 0.22f else 0.44f, 0.86f, 0.001f), label = "appleScale")
    } else {
        rememberUpdatedState(1f)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (duet && alignEnd) AppleDuetInset else 0.dp, end = if (duet && !alignEnd) AppleDuetInset else 0.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                this.alpha = alpha()
            }
            .then(defocus)
            .pointerInput(line) {
                detectTapGestures(
                    onPress = {
                        coroutineScope {
                            val dip = launch {
                                delay(80L)
                                pressed = true
                            }
                            tryAwaitRelease()
                            dip.cancel()
                            pressed = false
                        }
                    },
                    onTap = { onSeek(line.timeMs) },
                )
            },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        AppleSweptText(
            line = line,
            clock = clock,
            style = AppleLeadStyle.copy(textAlign = textAlign),
            isActive = isActive,
            sung = sung,
            animate = animate,
            feather = animate && isActive && !browsing,
            alignEnd = alignEnd,
            modifier = Modifier.fillMaxWidth(),
        )
        if (glossText != null) {
            BasicText(
                glossText,
                style = AppleGlossStyle.copy(textAlign = textAlign),
                color = { Ink.White.copy(alpha = if (isActive) 0.85f else 0.65f) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        line.background?.let { backing ->
            val backingVisible by remember(backing, clock, isActive, animate, sung) {
                derivedStateOf {
                    if (!animate) isActive || sung
                    else {
                        val now = clock.longValue
                        if (backing.isWordSynced) now >= backing.timeMs - 220L && now < backing.endMs + 350L else isActive
                    }
                }
            }
            val openAnim = remember(backing) { Animatable(if (backingVisible) 1f else 0f) }
            LaunchedEffect(backingVisible, animate) {
                if (!animate) openAnim.snapTo(if (backingVisible) 1f else 0f)
                else openAnim.animateTo(if (backingVisible) 1f else 0f, Motion.fluid(0.36f, 1f))
            }
            if (openAnim.value > 0.01f) {
                AppleSweptText(
                    line = backing,
                    clock = clock,
                    style = AppleBackingStyle.copy(textAlign = textAlign),
                    isActive = isActive,
                    sung = sung,
                    animate = animate,
                    feather = animate && isActive && !browsing,
                    alignEnd = alignEnd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            val o = openAnim.value.coerceIn(0f, 1f)
                            layout(p.width, (p.height * o).roundToInt()) {
                                p.placeWithLayer(0, 0) { this.alpha = o * 0.76f }
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun AppleSweptText(
    line: VLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    sung: Boolean,
    animate: Boolean,
    feather: Boolean,
    alignEnd: Boolean,
    modifier: Modifier,
) {
    if (!animate || !line.isWordSynced) {
        val litAlpha = if (sung || isActive) 1f else AppleUnsungAlpha
        val animatedAlpha by if (animate) {
            animateFloatAsState(litAlpha, Motion.fluid(0.36f, 1f), label = "appleLineLit")
        } else {
            rememberUpdatedState(litAlpha)
        }
        BasicText(
            line.text,
            style = style,
            color = { Ink.White.copy(alpha = animatedAlpha) },
            modifier = modifier,
        )
        return
    }

    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    val tailAlpha by animateFloatAsState(if (sung) 1f else AppleUnsungAlpha, Motion.fluid(0.38f, 1f), label = "appleTail")
    val phase = remember(line, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now < line.animatesFromMs -> 0
                now > line.animatesUntilMs -> 2
                else -> 1
            }
        }
    }
    val positionAt: () -> Long = {
        when (phase.value) {
            1 -> clock.longValue
            0 -> line.animatesFromMs - 1
            else -> line.animatesUntilMs + 1
        }
    }

    Box(modifier, contentAlignment = if (alignEnd) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft) {
        BasicText(
            line.text,
            style = style,
            color = { Ink.White.copy(alpha = tailAlpha) },
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            line.text,
            style = style,
            color = { Ink.White },
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = if (feather) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                .drawWithContent {
                    val pos = positionAt()
                    when {
                        pos >= line.endMs -> drawContent()
                        pos <= line.timeMs -> Unit
                        else -> layout?.let { appleSweep(it, line.revealedChars(pos), line.sweepSpans, feather, AppleWipeFeather.toPx()) }
                    }
                },
        )
    }
}

private fun ContentDrawScope.appleSweep(
    layout: TextLayoutResult,
    revealedChars: Float,
    spans: List<IntRange>,
    feather: Boolean,
    featherWidthPx: Float,
) {
    if (revealedChars <= 0f) return
    val textLength = layout.layoutInput.text.length
    if (revealedChars >= textLength) {
        drawContent()
        return
    }
    val first = layout.getLineStart(0).coerceIn(0, (textLength - 1).coerceAtLeast(0))
    val rtl = layout.getParagraphDirection(first) == ResolvedTextDirection.Rtl
    for (row in 0 until layout.lineCount) {
        val rowStart = layout.getLineStart(row)
        val rowEndVisible = layout.getLineEnd(row, visibleEnd = true)
        val rowEndAll = layout.getLineEnd(row, visibleEnd = false)
        if (revealedChars <= rowStart) return
        val cut = revealedChars < rowEndAll
        val left = layout.getLineLeft(row)
        val right = layout.getLineRight(row)
        val x = if (cut) {
            appleHorizontalAt(layout, revealedChars, row, rowStart, rowEndVisible, rowEndAll, spans).coerceIn(left, right)
        } else {
            if (rtl) left else right
        }
        val fPx = if (feather && cut) minOf(featherWidthPx, if (rtl) right - x else x - left) else 0f
        val lead = if (rtl) (x - fPx).coerceAtLeast(left) else (x + fPx).coerceAtMost(right)
        val clipLeft = if (rtl) lead else left
        val clipRight = if (rtl) right else lead
        if (clipLeft >= clipRight) continue
        val top = layout.getLineTop(row)
        val bottom = layout.getLineBottom(row)
        clipRect(left = clipLeft, top = top, right = clipRight, bottom = bottom) { this@appleSweep.drawContent() }
        if (fPx >= 0.5f) {
            clipRect(top = top, bottom = bottom) {
                drawRect(
                    brush = Brush.horizontalGradient(0f to Color.White, 1f to Color.Transparent, startX = x, endX = if (rtl) x - fPx else x + fPx),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

private fun appleHorizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    row: Int,
    rowStart: Int,
    rowEndVisible: Int,
    rowEndAll: Int,
    spans: List<IntRange>,
): Float {
    val piece = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (piece != null && piece.first >= rowStart && piece.last + 1 <= rowEndVisible) {
        val from = layout.appleXOn(piece.first, row)
        val to = layout.appleXOn(piece.last + 1, row)
        return from + (to - from) * (chars - piece.first) / (piece.last + 1 - piece.first)
    }
    val idx = chars.toInt().coerceIn(rowStart, rowEndAll)
    val here = layout.appleXOn(idx, row)
    val nextIdx = (idx + 1).coerceAtMost(rowEndVisible)
    val next = layout.appleXOn(nextIdx, row)
    return here + (next - here) * (chars - idx)
}

private fun TextLayoutResult.appleXOn(offset: Int, row: Int): Float {
    val start = getLineStart(row)
    val end = getLineEnd(row, visibleEnd = true)
    val left = getLineLeft(row)
    val right = getLineRight(row)
    val rtl = getParagraphDirection(start.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) == ResolvedTextDirection.Rtl
    return when {
        offset <= start -> if (rtl) right else left
        offset >= end -> if (rtl) left else right
        else -> getHorizontalPosition(offset, usePrimaryDirection = true).coerceIn(left, right)
    }
}

@Composable
private fun AppleGapRow(
    line: VLine,
    until: Long,
    isActive: Boolean,
    animate: Boolean,
    clock: MutableLongState,
    alpha: () -> Float,
    defocus: Modifier,
    onSeek: (Long) -> Unit,
) {
    val swell by if (animate) {
        animateFloatAsState(if (isActive) 1f else 0f, Motion.fluid(if (isActive) 0.42f else 0.32f, 1f), label = "appleGap")
    } else {
        rememberUpdatedState(if (isActive) 1f else 0f)
    }
    Box(
        Modifier
            .height(44.dp * swell.coerceIn(0f, 1f))
            .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (swell > 0.01f) {
            Row(
                Modifier
                    .then(defocus)
                    .pointerInput(line) { detectTapGestures { onSeek(line.timeMs) } }
                    .padding(vertical = 8.dp)
                    .graphicsLayer { this.alpha = alpha() * swell },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(width = 58.dp, height = 16.dp)) {
                    val span = (until - line.timeMs).coerceAtLeast(1L)
                    val through = ((clock.longValue - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    val baseR = 5.5.dp.toPx()
                    val stride = 19.dp.toPx()
                    repeat(3) { i ->
                        val lit = (through * 3f - i).coerceIn(0f, 1f)
                        val wave = if (animate) 1f + 0.25f * sin(through * 10f - i * 1.1f) else 1f
                        drawCircle(
                            Ink.White.copy(alpha = 0.28f + 0.72f * lit),
                            radius = baseR * (0.85f + 0.2f * lit) * wave,
                            center = Offset(baseR + i * stride, size.height / 2f),
                        )
                    }
                }
            }
        }
    }
}
