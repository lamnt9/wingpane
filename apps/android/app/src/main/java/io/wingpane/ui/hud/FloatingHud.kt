package io.wingpane.ui.hud

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.wingpane.R
import io.wingpane.input.PalmRejectionFilter
import io.wingpane.ui.screen.WingpaneSecondaryButton
import io.wingpane.proto.Mode
import io.wingpane.proto.PenTarget
import io.wingpane.ui.state.ClientStateMapper
import io.wingpane.ui.state.DisconnectConfirmStateMachine
import io.wingpane.ui.state.HudStatusDot
import io.wingpane.ui.theme.WingpaneTheme
import io.wingpane.ui.util.performWingpaneHaptic
import io.wingpane.ui.util.rememberAreAnimationsEnabled
import io.wingpane.ui.util.rememberIsTalkBackEnabled
import io.wingpane.video.VideoDecoder
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Tracks external pointer touches occurring on the video SurfaceView so the HUD
 * can hide when an outside pointer approaches within 64dp and reappear 300ms after lift,
 * and optionally draw touch feedback circles without intercepting stream touches.
 */
data class StreamTouchPoint(
    val xPx: Float,
    val yPx: Float,
    val isDown: Boolean,
    val liftedAtElapsedMs: Long = 0L,
)

/**
 * Screen 2 — Floating HUD overlay & Reconnecting scrim overlay above the video SurfaceView.
 *
 * Strictly does NOT attach any pointerInput to its root container when collapsed so that
 * 100% of touches outside the 48×48dp HUD hit box pass straight through to the SurfaceView.
 */
@Composable
fun StreamHudOverlay(
    stats: VideoDecoder.Stats,
    bitrateKbps: UInt,
    isReconnecting: Boolean,
    accessibilityMissing: Boolean,
    keepHudVisible: Boolean,
    lockAspectRatio: Boolean,
    onLockAspectRatioChange: (Boolean) -> Unit,
    showTouches: Boolean,
    onShowTouchesChange: (Boolean) -> Unit,
    palmRejectionLevel: PalmRejectionFilter.Level,
    onPalmRejectionLevelChange: (PalmRejectionFilter.Level) -> Unit,
    activeStreamTouch: StreamTouchPoint?,
    externalOpenRequestTick: Int,
    onDisconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
    mode: Mode = Mode.ExtendedDisplay,
    hostFeatures: Int = 0,
    capabilitiesReceived: Boolean = false,
    onModeChange: (Mode) -> Unit = {},
    pressureCurve: List<Float> = listOf(.25f, .25f, .75f, .75f),
    penTarget: PenTarget = PenTarget.MainDisplay,
    onPenConfigChange: (List<Float>, PenTarget) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val isTalkBackEnabled by rememberIsTalkBackEnabled()
    val animationsEnabled = rememberAreAnimationsEnabled()

    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var verticalFraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    var pillInteractionTick by remember { mutableIntStateOf(0) }
    var panelInteractionTick by remember { mutableIntStateOf(0) }
    var isPillIdle by remember { mutableStateOf(false) }
    var isProximityHidden by remember { mutableStateOf(false) }

    // Open HUD if 3-finger tap was triggered on SurfaceView
    LaunchedEffect(externalOpenRequestTick) {
        if (externalOpenRequestTick > 0) {
            isExpanded = true
            panelInteractionTick++
        }
    }

    // Throttle metrics display refresh to 2x/s (500ms) per brief
    var displayedFps by remember { mutableIntStateOf(stats.fps.roundToInt()) }
    var displayedLatencyMs by remember {
        mutableLongStateOf(ClientStateMapper.resolveLatencyMs(stats.e2eP50Us, stats.p50Us))
    }
    LaunchedEffect(stats) {
        delay(500L)
        displayedFps = stats.fps.roundToInt()
        displayedLatencyMs = ClientStateMapper.resolveLatencyMs(stats.e2eP50Us, stats.p50Us)
    }
    LaunchedEffect(Unit) {
        displayedFps = stats.fps.roundToInt()
        displayedLatencyMs = ClientStateMapper.resolveLatencyMs(stats.e2eP50Us, stats.p50Us)
    }

    // 3s idle fade to 40% for collapsed pill (disabled when TalkBack is on or "Keep HUD visible" is on)
    LaunchedEffect(isExpanded, pillInteractionTick, isTalkBackEnabled, keepHudVisible) {
        if (isExpanded || isTalkBackEnabled || keepHudVisible) {
            isPillIdle = false
        } else {
            isPillIdle = false
            delay(DisconnectConfirmStateMachine.PILL_IDLE_FADE_MS)
            isPillIdle = true
        }
    }

    // 5s auto-close timer for expanded panel (disabled when TalkBack is on)
    LaunchedEffect(isExpanded, panelInteractionTick, isTalkBackEnabled) {
        if (isExpanded && !isTalkBackEnabled) {
            delay(DisconnectConfirmStateMachine.PANEL_AUTO_CLOSE_MS)
            isExpanded = false
        }
    }

    BackHandler(enabled = isExpanded) {
        performWingpaneHaptic(context, view)
        isExpanded = false
    }

    // Combined insets: WindowInsets.systemGestures U mandatorySystemGestures U displayCutout
    val safeInsets = WindowInsets.systemGestures
        .union(WindowInsets.mandatorySystemGestures)
        .union(WindowInsets.displayCutout)

    val topInsetPx = safeInsets.getTop(density)
    val bottomInsetPx = safeInsets.getBottom(density)
    // For the right-docked pill, only offset horizontally by displayCutout so the half-pill stays edge-flush
    val rightCutoutPx = WindowInsets.displayCutout.getRight(density, layoutDirection)

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerHeightPx = constraints.maxHeight.toFloat()
        val hitSizePx = with(density) { WingpaneTheme.touch.minTarget.toPx() }
        val marginPx = with(density) { WingpaneTheme.spacing.sm.toPx() }

        val minTopPx = (topInsetPx + marginPx).coerceAtMost((containerHeightPx - hitSizePx).coerceAtLeast(0f))
        val maxTopPx = (containerHeightPx - bottomInsetPx - marginPx - hitSizePx).coerceAtLeast(minTopPx)
        val pillTopPx = (minTopPx + (maxTopPx - minTopPx) * verticalFraction.coerceIn(0f, 1f))
        val pillCenterX = containerWidthPx - rightCutoutPx - hitSizePx / 2f
        val pillCenterY = pillTopPx + hitSizePx / 2f
        val proximityRadiusPx = with(density) { DisconnectConfirmStateMachine.PROXIMITY_HIDE_RADIUS_DP.dp.toPx() }

        // Proximity hide rule: hide ONLY when an active pointer that started outside the HUD comes within 64dp;
        // reappear 300ms after that pointer lifts. No blinking on unrelated taps.
        LaunchedEffect(activeStreamTouch, pillCenterX, pillCenterY) {
            val touch = activeStreamTouch
            if (touch == null) {
                isProximityHidden = false
            } else if (touch.isDown) {
                val dist = hypot(touch.xPx - pillCenterX, touch.yPx - pillCenterY)
                if (dist <= proximityRadiusPx) {
                    isProximityHidden = true
                }
            } else if (isProximityHidden) {
                delay(DisconnectConfirmStateMachine.PROXIMITY_REAPPEAR_MS)
                isProximityHidden = false
            }
        }

        // Visual touch feedback circle when "Show touches" is enabled (pure Canvas, zero pointerInput!)
        if (showTouches && activeStreamTouch?.isDown == true) {
            val touchColor = WingpaneTheme.colors.primary
            val borderColor = WingpaneTheme.colors.text
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(activeStreamTouch.xPx, activeStreamTouch.yPx)
                drawCircle(
                    color = touchColor.copy(alpha = 0.28f),
                    radius = 22.dp.toPx(),
                    center = center,
                )
                drawCircle(
                    color = borderColor,
                    radius = 22.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        val statusDot = ClientStateMapper.resolveHudStatusDot(
            isReconnecting = isReconnecting,
            latencyMs = displayedLatencyMs,
        )

        // Collapsed HUD half-pill on the right edge (only interactive on its own 48x48dp bounds!)
        if (!isExpanded && !isProximityHidden && !isReconnecting) {
            CollapsedHudHalfPill(
                isIdle = isPillIdle,
                statusDot = statusDot,
                latencyMs = displayedLatencyMs,
                animationsEnabled = animationsEnabled,
                onTap = {
                    performWingpaneHaptic(context, view)
                    pillInteractionTick++
                    isExpanded = true
                    panelInteractionTick++
                },
                onVerticalDragDelta = { dy ->
                    pillInteractionTick++
                    val range = (maxTopPx - minTopPx).coerceAtLeast(1f)
                    verticalFraction = (verticalFraction + dy / range).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = -rightCutoutPx,
                            y = pillTopPx.roundToInt(),
                        )
                    },
            )
        }

        // Expanded HUD panel + outside-tap catcher
        val enterMs = if (animationsEnabled) 180 else 0
        val exitMs = if (animationsEnabled) 120 else 0
        AnimatedVisibility(
            visible = isExpanded && !isReconnecting,
            enter = fadeIn(tween(enterMs, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(exitMs)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            performWingpaneHaptic(context, view)
                            isExpanded = false
                        }
                    },
            ) {
                ExpandedHudPanel(
                    accessibilityMissing = accessibilityMissing,
                    mode = mode, hostFeatures = hostFeatures, capabilitiesReceived = capabilitiesReceived,
                    onModeChange = onModeChange,
                    pressureCurve = pressureCurve,
                    penTarget = penTarget,
                    onPenConfigChange = onPenConfigChange,
                    fps = displayedFps,
                    latencyMs = displayedLatencyMs,
                    bitrateMbps = bitrateKbps.toFloat() / 1000f,
                    lockAspectRatio = lockAspectRatio,
                    onLockAspectRatioChange = {
                        performWingpaneHaptic(context, view)
                        panelInteractionTick++
                        onLockAspectRatioChange(it)
                    },
                    showTouches = showTouches,
                    palmRejectionLevel = palmRejectionLevel,
                    onPalmRejectionLevelChange = onPalmRejectionLevelChange,
                    onShowTouchesChange = {
                        performWingpaneHaptic(context, view)
                        panelInteractionTick++
                        onShowTouchesChange(it)
                    },
                    isTalkBackEnabled = isTalkBackEnabled,
                    onPanelInteraction = { panelInteractionTick++ },
                    onClose = {
                        performWingpaneHaptic(context, view)
                        isExpanded = false
                    },
                    onDisconnectConfirmed = {
                        performWingpaneHaptic(context, view)
                        isExpanded = false
                        onDisconnect()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = WingpaneTheme.spacing.md,
                            bottom = WingpaneTheme.spacing.md,
                            end = WingpaneTheme.spacing.md,
                        )
                        // Prevent taps inside the panel from propagating to the outside-tap dismiss handler
                        .pointerInput(Unit) {
                            detectTapGestures {
                                panelInteractionTick++
                            }
                        },
                )
            }
        }

        // Reconnecting overlay: 60% black scrim on last frame, "Reconnecting…" + Cancel
        AnimatedVisibility(
            visible = isReconnecting,
            enter = fadeIn(tween(enterMs)),
            exit = fadeOut(tween(exitMs)),
            modifier = Modifier.fillMaxSize(),
        ) {
            ReconnectingScrimOverlay(
                onCancel = {
                    performWingpaneHaptic(context, view)
                    onCancelReconnect()
                },
            )
        }
    }
}

/**
 * Collapsed HUD half-pill docked to the right edge:
 * - 24×48dp visual pill
 * - 48×48dp hit area
 * - Fades fill & icon to 40% after 3s idle while keeping 1dp `border` at 100%
 * - Vertically draggable along the edge
 * - Status dot when latency > 35ms (warning) or reconnecting (danger)
 */
@Composable
private fun CollapsedHudHalfPill(
    isIdle: Boolean,
    statusDot: HudStatusDot,
    latencyMs: Long,
    animationsEnabled: Boolean,
    onTap: () -> Unit,
    onVerticalDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WingpaneTheme.colors
    val radius = WingpaneTheme.radius
    val touch = WingpaneTheme.touch
    val spacing = WingpaneTheme.spacing

    val contentAlpha by animateFloatAsState(
        targetValue = if (isIdle) 0.4f else 1.0f,
        animationSpec = tween(durationMillis = if (animationsEnabled) 200 else 0),
        label = "HudPillIdleAlpha",
    )

    val halfPillShape = RoundedCornerShape(
        topStart = radius.pill,
        bottomStart = radius.pill,
        topEnd = 0.dp,
        bottomEnd = 0.dp,
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // Optional high-latency warning chip next to the pill (prototype 10)
        if (statusDot == HudStatusDot.WARNING && !isIdle) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(radius.pill))
                    .background(colors.surface1)
                    .border(1.dp, colors.warning, RoundedCornerShape(radius.pill))
                    .clickable(onClick = onTap)
                    .padding(horizontal = spacing.sm + spacing.xs, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(R.string.hud_high_latency_format, latencyMs),
                    style = WingpaneTheme.typography.captionStrong,
                    color = colors.warning,
                )
            }
        }

        // 48×48dp hit target containing the right-flush 24×48dp visual half-pill
        Box(
            modifier = Modifier
                .size(touch.minTarget)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        onVerticalDragDelta(dragAmount)
                    }
                }
                .clickable(
                    onClick = onTap,
                    role = Role.Button,
                )
                .testTag("CollapsedHudPill"),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // 24×48dp visual half-pill: fill fades to 40%, 1dp border stays at 100%!
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(48.dp)
                    .clip(halfPillShape)
                    .background(colors.surface1.copy(alpha = contentAlpha))
                    .border(1.dp, colors.border, halfPillShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.hud_open_cd),
                    tint = colors.text.copy(alpha = contentAlpha),
                    modifier = Modifier.size(18.dp),
                )
            }

            // Status dot on top-left corner of the visual half-pill when warning or danger
            if (statusDot != HudStatusDot.NONE) {
                val dotColor = when (statusDot) {
                    HudStatusDot.DANGER -> colors.danger
                    HudStatusDot.WARNING -> colors.warning
                    HudStatusDot.NONE -> colors.success
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 16.dp, top = 2.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .border(1.5.dp, colors.bg, CircleShape)
                        .testTag("HudStatusDot_${statusDot.name}"),
                )
            }
        }
    }
}

@Composable
private fun ExpandedHudPanel(
    accessibilityMissing: Boolean,
    mode: Mode,
    hostFeatures: Int,
    capabilitiesReceived: Boolean,
    onModeChange: (Mode) -> Unit,
    pressureCurve: List<Float>,
    penTarget: PenTarget,
    onPenConfigChange: (List<Float>, PenTarget) -> Unit,
    fps: Int,
    latencyMs: Long,
    bitrateMbps: Float,
    lockAspectRatio: Boolean,
    onLockAspectRatioChange: (Boolean) -> Unit,
    showTouches: Boolean,
    onShowTouchesChange: (Boolean) -> Unit,
    palmRejectionLevel: PalmRejectionFilter.Level,
    onPalmRejectionLevelChange: (PalmRejectionFilter.Level) -> Unit,
    isTalkBackEnabled: Boolean,
    onPanelInteraction: () -> Unit,
    onClose: () -> Unit,
    onDisconnectConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius
    val touch = WingpaneTheme.touch
    val fontScale = LocalDensity.current.fontScale
    val isLargeFontScale = fontScale > 1.3f

    val disconnectMachine = remember { DisconnectConfirmStateMachine() }
    var isConfirmingDisconnect by remember { mutableStateOf(false) }

    // 3s disconnect confirm countdown (disabled when TalkBack is on per brief)
    LaunchedEffect(isConfirmingDisconnect, isTalkBackEnabled) {
        if (isConfirmingDisconnect && !isTalkBackEnabled) {
            delay(DisconnectConfirmStateMachine.CONFIRM_TIMEOUT_MS)
            disconnectMachine.reset()
            isConfirmingDisconnect = false
        }
    }

    val bitrateFormatted = remember(bitrateMbps) {
        String.format(Locale.US, "%.1f", bitrateMbps)
    }

    Surface(
        modifier = modifier
            .widthIn(min = 280.dp, max = 420.dp)
            .testTag("ExpandedHudPanel"),
        shape = RoundedCornerShape(radius.card),
        color = colors.surface1,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (accessibilityMissing) {
                Text(
                    text = stringResource(R.string.hud_accessibility_missing),
                    style = WingpaneTheme.typography.captionStrong,
                    color = colors.text,
                    modifier = Modifier.fillMaxWidth().background(colors.surface2).padding(spacing.sm),
                )
            }
            // Header: Filled Connected status chip (#3DDC97 fill, #000 text) + Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(radius.pill))
                        .background(colors.success)
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = colors.onSuccess,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.hud_connected),
                        style = WingpaneTheme.typography.captionStrong,
                        color = colors.onSuccess,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(touch.minTarget)
                        .clip(RoundedCornerShape(radius.card))
                        .background(colors.surface2)
                        .border(1.dp, colors.border, RoundedCornerShape(radius.card))
                        .clickable(onClick = onClose)
                        .testTag("CloseHudButton"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.hud_close_cd),
                        tint = colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 1. Metrics: FPS · latency ms · bitrate Mbps
            // Tabular numerals, NOT a live region; TalkBack reads one summary on focus.
            // At fontScale > 1.3 stack vertically.
            val metricsSummary = "$fps frames per second, $latencyMs milliseconds latency, $bitrateFormatted megabits per second"
            if (!isLargeFontScale) {
                Surface(
                    shape = RoundedCornerShape(radius.card),
                    color = colors.surface2,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = metricsSummary
                        }
                        .testTag("HudMetricsHorizontal"),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = spacing.md, horizontal = spacing.sm),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HudMetricColumn(
                            value = "$fps",
                            unit = null,
                            label = stringResource(R.string.hud_metric_fps_label),
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(32.dp)
                                .background(colors.border),
                        )
                        HudMetricColumn(
                            value = "$latencyMs",
                            unit = "ms",
                            label = stringResource(R.string.hud_metric_latency_label),
                            isWarning = latencyMs > ClientStateMapper.HIGH_LATENCY_THRESHOLD_MS,
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(32.dp)
                                .background(colors.border),
                        )
                        HudMetricColumn(
                            value = bitrateFormatted,
                            unit = null,
                            label = stringResource(R.string.hud_metric_mbps_label),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = metricsSummary
                        }
                        .testTag("HudMetricsVertical"),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.hud_live_diagnostics),
                        style = WingpaneTheme.typography.captionStrong,
                        color = colors.textMuted,
                    )
                    HudMetricStackedCard(
                        value = "$fps",
                        unit = stringResource(R.string.hud_metric_fps_label),
                        description = stringResource(R.string.hud_metric_fps_desc),
                    )
                    HudMetricStackedCard(
                        value = "$latencyMs",
                        unit = "ms",
                        description = stringResource(R.string.hud_metric_latency_desc),
                    )
                    HudMetricStackedCard(
                        value = bitrateFormatted,
                        unit = "Mbps",
                        description = stringResource(R.string.hud_metric_mbps_desc),
                    )
                }
            }

            // Mode buttons are capability-gated; controls keep the specified 48dp target.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(stringResource(R.string.hud_stream_mode), style = WingpaneTheme.typography.captionStrong, color = colors.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), modifier = Modifier.fillMaxWidth()) {
                    listOf(Mode.ExtendedDisplay to stringResource(R.string.hud_mode_display), Mode.GraphicsTablet to stringResource(R.string.hud_mode_pen), Mode.TrackpadKeyboard to stringResource(R.string.hud_mode_trackpad)).forEach { (candidate, title) ->
                        val enabled = candidate == Mode.ExtendedDisplay || capabilitiesReceived && (hostFeatures and if (candidate == Mode.GraphicsTablet) 1 else 2) != 0
                        val selected = candidate == mode
                        Box(modifier = Modifier.weight(1f).heightIn(min = touch.minTarget).clip(RoundedCornerShape(spacing.sm)).background(if (selected) colors.primary else colors.surface2).border(1.dp, colors.border, RoundedCornerShape(spacing.sm)).clickable(enabled = enabled, role = Role.Button) { onPanelInteraction(); onModeChange(candidate) }.padding(horizontal = spacing.xs, vertical = spacing.xs), contentAlignment = Alignment.Center) {
                            Text(title, style = WingpaneTheme.typography.captionStrong, color = if (selected) colors.onPrimary else if (enabled) colors.text else colors.textMuted, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            if (mode == Mode.GraphicsTablet && capabilitiesReceived && (hostFeatures and (1 shl 5)) != 0) {
                PressureCurveEditor(
                    curve = pressureCurve,
                    target = penTarget,
                    onChange = { curve, target -> onPanelInteraction(); onPenConfigChange(curve, target) },
                )
            }

            // 3. Toggle: Lock aspect ratio
            HudToggleRow(
                title = stringResource(R.string.hud_lock_aspect_ratio),
                subtitle = stringResource(R.string.hud_lock_aspect_ratio_desc),
                checked = lockAspectRatio,
                onCheckedChange = onLockAspectRatioChange,
            )

            val levels = PalmRejectionFilter.Level.entries
            val nextPalm = levels[(levels.indexOf(palmRejectionLevel) + 1) % levels.size]
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = touch.minTarget)
                    .clip(RoundedCornerShape(radius.card)).background(colors.surface2)
                    .clickable(role = Role.Button) { onPanelInteraction(); onPalmRejectionLevelChange(nextPalm) }
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.hud_palm_rejection, stringResource(when (palmRejectionLevel) {
                        PalmRejectionFilter.Level.Off -> R.string.hud_palm_off
                        PalmRejectionFilter.Level.Standard -> R.string.hud_palm_standard
                        PalmRejectionFilter.Level.Strict -> R.string.hud_palm_strict
                    })),
                    color = colors.text,
                    style = WingpaneTheme.typography.body,
                )
            }

            // 4. Toggle: Show touches
            HudToggleRow(
                title = stringResource(R.string.hud_show_touches),
                subtitle = stringResource(R.string.hud_show_touches_desc),
                checked = showTouches,
                onCheckedChange = onShowTouchesChange,
            )

            // 5. Disconnect (danger): first tap turns row into "Tap again to disconnect" for 3s; no modal
            val disconnectLabel = if (isConfirmingDisconnect) {
                stringResource(R.string.hud_disconnect_confirm)
            } else {
                stringResource(R.string.hud_disconnect)
            }
            val disconnectBg = if (isConfirmingDisconnect) colors.danger else colors.surface2
            val disconnectFg = if (isConfirmingDisconnect) colors.onDanger else colors.danger
            val disconnectBorder = if (isConfirmingDisconnect) colors.danger else colors.border

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = touch.minTarget)
                    .clip(RoundedCornerShape(radius.card))
                    .background(disconnectBg)
                    .border(1.dp, disconnectBorder, RoundedCornerShape(radius.card))
                    .clickable(
                        role = Role.Button,
                        onClick = {
                            onPanelInteraction()
                            val shouldDisconnect = disconnectMachine.onTap(
                                nowMs = System.currentTimeMillis(),
                                talkBackEnabled = isTalkBackEnabled,
                            )
                            isConfirmingDisconnect = disconnectMachine.isConfirming
                            if (shouldDisconnect) {
                                onDisconnectConfirmed()
                            }
                        },
                    )
                    .padding(horizontal = spacing.md, vertical = spacing.sm)
                    .testTag("HudDisconnectButton"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = disconnectFg,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = disconnectLabel,
                        style = WingpaneTheme.typography.bodyStrong,
                        color = disconnectFg,
                    )
                }
            }

            // Footer: Protocol v1
            Text(
                text = stringResource(R.string.protocol_v1),
                style = WingpaneTheme.typography.caption,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HudMetricColumn(
    value: String,
    unit: String?,
    label: String,
    isWarning: Boolean = false,
) {
    val colors = WingpaneTheme.colors
    val valueColor = if (isWarning) colors.warning else colors.text
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = value,
                style = WingpaneTheme.typography.metricValue,
                color = valueColor,
            )
            if (unit != null) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = unit,
                    style = WingpaneTheme.typography.captionStrong,
                    color = colors.primary,
                )
            }
        }
        Text(
            text = label,
            style = WingpaneTheme.typography.caption,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun HudMetricStackedCard(
    value: String,
    unit: String,
    description: String,
) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius

    Surface(
        shape = RoundedCornerShape(radius.card),
        color = colors.surface2,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = value,
                    style = WingpaneTheme.typography.metricValue,
                    color = colors.text,
                )
                Text(
                    text = unit,
                    style = WingpaneTheme.typography.bodyStrong,
                    color = colors.primary,
                )
            }
            Text(
                text = description,
                style = WingpaneTheme.typography.caption,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun HudDisabledModeSegment(
    title: String,
    badge: String,
    modifier: Modifier = Modifier,
) {
    val colors = WingpaneTheme.colors
    val touch = WingpaneTheme.touch
    val spacing = WingpaneTheme.spacing

    Column(
        modifier = modifier
            .heightIn(min = touch.minTarget)
            .padding(horizontal = spacing.xs, vertical = spacing.xs)
            .semantics(mergeDescendants = true) {
                stateDescription = badge
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = WingpaneTheme.typography.captionStrong,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
        Text(
            text = badge,
            style = WingpaneTheme.typography.caption,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HudDisabledModeRow(
    title: String,
    subtitle: String,
    badge: String,
) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius
    val touch = WingpaneTheme.touch

    Surface(
        shape = RoundedCornerShape(radius.card),
        color = colors.surface2,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = touch.minTarget),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = WingpaneTheme.typography.bodyStrong,
                    color = colors.textMuted,
                )
                Text(
                    text = subtitle,
                    style = WingpaneTheme.typography.caption,
                    color = colors.textMuted,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(spacing.xs))
                    .border(1.dp, colors.border, RoundedCornerShape(spacing.xs))
                    .padding(horizontal = spacing.sm, vertical = 2.dp),
            ) {
                Text(
                    text = badge,
                    style = WingpaneTheme.typography.caption,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun HudToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = WingpaneTheme.colors
    val touch = WingpaneTheme.touch
    val spacing = WingpaneTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = touch.minTarget)
            .clickable(
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(vertical = spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = spacing.sm),
        ) {
            Text(
                text = title,
                style = WingpaneTheme.typography.bodyStrong,
                color = colors.text,
            )
            Text(
                text = subtitle,
                style = WingpaneTheme.typography.caption,
                color = colors.textMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.surface2,
                uncheckedBorderColor = colors.border,
            ),
        )
    }
}

/**
 * Reconnecting overlay: 60% black scrim on last frame, "Reconnecting…" + Cancel;
 * after 10s WingpaneClient transitions to Lost -> S5e "Connection lost".
 */
@Composable
private fun ReconnectingScrimOverlay(
    onCancel: () -> Unit,
) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius

    var elapsedSeconds by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        for (sec in 1..10) {
            elapsedSeconds = sec
            delay(1_000L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = 0.6f))
            .pointerInput(Unit) {
                detectTapGestures { /* Consume touches while reconnecting scrim is active */ }
            }
            .testTag("ReconnectingOverlay"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(radius.card),
            color = colors.surface1,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier
                .widthIn(min = 280.dp, max = 400.dp)
                .padding(spacing.lg),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(colors.surface2)
                        .border(2.dp, colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.reconnecting_title),
                    style = WingpaneTheme.typography.headingMd,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )

                Text(
                    text = stringResource(R.string.reconnecting_body),
                    style = WingpaneTheme.typography.caption,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )

                Surface(
                    shape = RoundedCornerShape(spacing.sm),
                    color = colors.surface2,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.sm + spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Reconnecting",
                                style = WingpaneTheme.typography.captionStrong,
                                color = colors.primary,
                            )
                            Text(
                                text = "${elapsedSeconds}s / 10s",
                                style = WingpaneTheme.typography.captionStrong,
                                color = colors.primary,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (elapsedSeconds / 10f).coerceIn(0.1f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(radius.pill)),
                            color = colors.primary,
                            trackColor = colors.surface1,
                            strokeCap = StrokeCap.Round,
                        )
                    }
                }

                WingpaneSecondaryButton(
                    label = stringResource(R.string.action_cancel),
                    icon = Icons.Rounded.Close,
                    onClick = onCancel,
                    fillMaxWidth = true,
                )
            }
        }
    }
}
