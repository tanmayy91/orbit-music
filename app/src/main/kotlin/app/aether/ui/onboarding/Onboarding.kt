@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.onboarding

import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.aether.R
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.drawLightCross
import app.aether.core.designsystem.glass.GlassHost
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.ui.LocalArrival
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * The welcome's wordmark: Aether, signed in the script, the size the boot film's resolves to, with
 * a faint halo of its own light so it stands forward of the dark water.
 */
val MastheadStyle = Type.Inscription.copy(fontSize = 68.sp, lineHeight = 76.sp, shadow = Shadow(Color.White.copy(alpha = 0.24f), blurRadius = 38f))

/**
 * The first visit: dark water at night, moving slowly, light catching its ripples in silver (a
 * filmed sea, in black and white, looping with no seam). The wordmark the boot film handed over sits
 * in the middle, where the film left it, a line of small capitals beneath. At the foot, Enter.
 * YouTube Music, timed lyrics and offline permissions are configured later in Profile.
 */
@Composable
fun Onboarding(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val arrival = LocalArrival.current
    val reduce = LocalReduceMotion.current
    val words = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            delay(900)
            words.animateTo(1f, Motion.fluid(1.1f, 1f))
        }
    }

    GlassHost(
        modifier.fillMaxSize(),
        content = { Water(Modifier.fillMaxSize()) },
        overlay = {
            // A pool of shadow on the water where the wordmark and hello sit, so the type reads cleanly.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val c = Offset(size.width / 2f, size.height / 2f - 48.dp.toPx())
                    scale(1.85f, 1.15f, pivot = c) {
                        drawCircle(
                            Brush.radialGradient(0f to Color.Black.copy(alpha = 0.66f), 0.58f to Color.Black.copy(alpha = 0.34f), 1f to Color.Transparent, center = c, radius = 152.dp.toPx()),
                            radius = 152.dp.toPx(),
                            center = c,
                        )
                    }
                },
            )
            Column(
                Modifier.align(Alignment.Center).offset(y = (-54).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(
                    "Aether",
                    MastheadStyle,
                    modifier = Modifier
                        .onGloballyPositioned { arrival.masthead = it.boundsInRoot() }
                        .graphicsLayer { alpha = arrival.mastheadAlpha },
                )
                Spacer(Modifier.height(10.dp))
                Txt(
                    "A private room for music",
                    Type.Label.copy(fontSize = 9.5.sp, letterSpacing = 0.34.em),
                    color = Ink.Grey,
                    upper = true,
                    modifier = Modifier.graphicsLayer { alpha = words.value },
                )
                Spacer(Modifier.height(28.dp))
                AppleHelloGreeting(
                    reduce = reduce,
                    modifier = Modifier
                        .graphicsLayer { alpha = words.value }
                        .testTag("onboarding_hello_animation"),
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(horizontal = Space.edge)
                    .padding(bottom = 20.dp)
                    .graphicsLayer {
                        alpha = words.value
                        translationY = (1f - words.value) * 24.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Txt(
                    "No tracking. Set up YouTube Music, lyrics and offline anytime in Profile.",
                    Type.Meta,
                    color = Ink.White.copy(alpha = 0.74f),
                    align = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                GlassButton(
                    "Enter",
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_enter_button"),
                    lit = true,
                )
            }
        },
    )
}

/**
 * Apple-style continuous cursive "hello": writes itself smoothly stroke by stroke with a silver
 * nib glint and soft bloom, pauses, and breathes gently with multilingual greetings beneath.
 */
@Composable
private fun AppleHelloGreeting(reduce: Boolean, modifier: Modifier = Modifier) {
    val stroke = remember { Animatable(if (reduce) 1f else 0f) }
    val glow = remember { Animatable(if (reduce) 1f else 0f) }
    val whispers = remember { listOf("Hello", "नमस्ते", "Bonjour", "Hola", "こんにちは", "Ciao") }
    var whisperIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(reduce) {
        if (reduce) {
            stroke.snapTo(1f)
            glow.snapTo(1f)
            return@LaunchedEffect
        }
        delay(850)
        while (true) {
            glow.snapTo(1f)
            stroke.snapTo(0f)
            stroke.animateTo(1f, tween(durationMillis = 2_400, easing = FastOutSlowInEasing))
            delay(2_200)
            whisperIndex = (whisperIndex + 1) % whispers.size
            glow.animateTo(0f, Motion.fluid(0.55f, 1f))
            delay(260)
        }
    }

    val basePath = remember { buildHelloCursivePath() }
    val scaledPath = remember { Path() }
    val drawnPath = remember { Path() }
    val measure = remember { PathMeasure() }
    val matrix = remember { Matrix() }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .fillMaxWidth(0.72f)
                .widthIn(max = 280.dp)
                .aspectRatio(2.8f),
        ) {
            val sx = size.width / HelloCanvasWidth
            val sy = size.height / HelloCanvasHeight
            scaledPath.reset()
            scaledPath.addPath(basePath)
            matrix.reset()
            matrix.scale(sx, sy)
            scaledPath.transform(matrix)

            measure.setPath(scaledPath, false)
            val total = measure.length
            val progress = stroke.value.coerceIn(0f, 1f)
            val fade = glow.value.coerceIn(0f, 1f)
            if (total <= 0f || progress <= 0.002f || fade <= 0.002f) return@Canvas

            val stop = total * progress
            drawnPath.reset()
            measure.getSegment(0f, stop, drawnPath, true)

            val w = 3.2.dp.toPx()
            val strokeGradient = Brush.horizontalGradient(
                0f to Ink.White.copy(alpha = 0.88f * fade),
                0.5f to Ink.White.copy(alpha = fade),
                1f to Color(0xFFD8E0EA).copy(alpha = 0.94f * fade),
            )
            // Soft silver bloom beneath the calligraphic stroke
            drawPath(
                drawnPath,
                color = Ink.White.copy(alpha = 0.12f * fade),
                style = Stroke(width = w * 3.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                drawnPath,
                color = Ink.White.copy(alpha = 0.24f * fade),
                style = Stroke(width = w * 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                drawnPath,
                brush = strokeGradient,
                style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            if (!reduce && progress in 0.02f..0.985f) {
                val nib = measure.getPosition(stop)
                drawLightCross(intensity = 0.95f * fade, centre = nib, extent = 28.dp.toPx())
            }
        }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(
            targetState = whispers[whisperIndex],
            transitionSpec = {
                fadeIn(tween(450)) togetherWith fadeOut(tween(450))
            },
            label = "helloWhisper",
        ) { greeting ->
            Txt(
                greeting,
                Type.Aside.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = Ink.White.copy(alpha = 0.68f),
                align = TextAlign.Center,
            )
        }
    }
}

private const val HelloCanvasWidth = 280f
private const val HelloCanvasHeight = 100f

/** Single-stroke continuous calligraphic Bézier curve spelling "hello". */
private fun buildHelloCursivePath(): Path = Path().apply {
    // Entry lead-in into cursive 'h'
    moveTo(16f, 66f)
    cubicTo(28f, 62f, 48f, 34f, 46f, 16f)
    cubicTo(44f, 10f, 34f, 12f, 32f, 30f)
    cubicTo(30f, 48f, 29f, 64f, 28f, 76f)
    cubicTo(31f, 60f, 39f, 46f, 50f, 48f)
    cubicTo(58f, 49f, 57f, 68f, 62f, 75f)
    cubicTo(66f, 78f, 74f, 74f, 82f, 66f)
    // Smooth loop into 'e'
    cubicTo(94f, 58f, 104f, 51f, 98f, 48f)
    cubicTo(90f, 46f, 82f, 62f, 88f, 73f)
    cubicTo(94f, 80f, 108f, 75f, 120f, 64f)
    // First tall cursive 'l'
    cubicTo(134f, 50f, 148f, 26f, 144f, 14f)
    cubicTo(140f, 8f, 131f, 14f, 130f, 34f)
    cubicTo(129f, 54f, 128f, 72f, 136f, 76f)
    cubicTo(144f, 79f, 152f, 72f, 158f, 65f)
    // Second tall cursive 'l'
    cubicTo(172f, 50f, 186f, 26f, 182f, 14f)
    cubicTo(178f, 8f, 169f, 14f, 168f, 34f)
    cubicTo(167f, 54f, 166f, 72f, 174f, 76f)
    cubicTo(182f, 79f, 190f, 72f, 198f, 64f)
    // Rounded cursive 'o' and graceful flourish tail
    cubicTo(208f, 54f, 218f, 46f, 228f, 50f)
    cubicTo(222f, 46f, 206f, 52f, 206f, 66f)
    cubicTo(206f, 78f, 224f, 80f, 234f, 70f)
    cubicTo(240f, 63f, 238f, 50f, 228f, 50f)
    cubicTo(234f, 56f, 246f, 60f, 264f, 50f)
}

/**
 * Water at night (after the reference recording): a calm sea, slowed to half speed, graded to black
 * and white with the light kept as silver glints. A 16-second clip (Pexels, free licence) whose end
 * dissolves into its start, so it loops with no seam; muted, cropped to fill as a photograph is, and
 * faded in on its first frame so nothing else ever shows first. With reduced motion it's a still.
 * The room's darkness gathers at the corners over it.
 */
@Composable
private fun Water(modifier: Modifier) {
    val context = LocalContext.current
    val reduce = LocalReduceMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val shown = remember { Animatable(0f) }
    var firstFrame by remember { mutableStateOf(false) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("android.resource://${context.packageName}/${R.raw.welcome_water}"))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(lifecycle, reduce) {
        player.playWhenReady = !reduce && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.playWhenReady = !reduce
                Lifecycle.Event.ON_STOP -> player.playWhenReady = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(firstFrame) { if (firstFrame) shown.animateTo(1f, Motion.fluid(1.2f, 1f)) }
    BoxWithConstraints(modifier.background(Ink.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        // Cropped to fill: the clip is 9:16, the screen taller, so it fills the height and loses its sides.
        val scale = max(maxWidth.value / ClipWidth, maxHeight.value / ClipHeight)
        AndroidView(
            factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
            modifier = Modifier
                .requiredSize((ClipWidth * scale).dp, (ClipHeight * scale).dp)
                .graphicsLayer { alpha = shown.value },
        )
        // The water held well back: dimmed all over, and darker still toward the corners, so the
        // wordmark and the keys stand forward of it.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)))
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.26f),
                        1f to Color.Black.copy(alpha = 0.88f),
                        center = Offset(size.width * 0.5f, size.height * 0.44f),
                        radius = size.maxDimension * 0.7f,
                    ),
                )
            },
        )
    }
}

private const val ClipWidth = 720f
private const val ClipHeight = 1280f
