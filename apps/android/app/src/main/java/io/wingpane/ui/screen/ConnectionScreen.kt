package io.wingpane.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.wingpane.R
import io.wingpane.ui.state.DisconnectConfirmStateMachine
import io.wingpane.ui.state.LiveRegionPoliteness
import io.wingpane.ui.state.ScreenId
import io.wingpane.ui.state.ScreenUiModel
import io.wingpane.ui.state.SetupGuideStep
import io.wingpane.ui.state.SetupGuideUiModel
import io.wingpane.ui.state.StatusSeverity
import io.wingpane.ui.theme.WingpaneTheme
import io.wingpane.ui.util.openAppUpdate
import io.wingpane.ui.util.openDeveloperOptions
import io.wingpane.ui.util.performWingpaneHaptic
import io.wingpane.ui.util.rememberAreAnimationsEnabled
import kotlinx.coroutines.delay

/**
 * Screen 1 — Connection UI supporting S1 Waiting, S1 Setup guide bottom sheet,
 * S3 Handshaking, and S5a–S5e error states in both tablet landscape (side-by-side)
 * and phone portrait (stacked) layouts.
 */
@Composable
fun ConnectionScreen(
    uiModel: ScreenUiModel,
    keepHudVisible: Boolean,
    onKeepHudVisibleChange: (Boolean) -> Unit,
    threeFingerTapHud: Boolean,
    onThreeFingerTapHudChange: (Boolean) -> Unit,
    onCancelHandshake: () -> Unit,
    onTryAgain: () -> Unit,
    onOpenDeveloperOptions: () -> Unit = {},
    onUpdateApp: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing

    var showSetupGuide by rememberSaveable { mutableStateOf(false) }
    var hasAutoSuggestedSetupGuide by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    // Brief: S1 Setup guide is also auto-suggested once after 30s in S1 Waiting.
    LaunchedEffect(uiModel.screenId, hasAutoSuggestedSetupGuide) {
        if (uiModel.screenId == ScreenId.S1_WAITING && !hasAutoSuggestedSetupGuide) {
            delay(DisconnectConfirmStateMachine.SETUP_GUIDE_AUTO_SUGGEST_MS)
            if (!hasAutoSuggestedSetupGuide) {
                hasAutoSuggestedSetupGuide = true
                showSetupGuide = true
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .systemBarsPadding()
            .padding(horizontal = spacing.lg, vertical = spacing.md)
            .testTag("ConnectionScreen_${uiModel.screenId.name}"),
    ) {
        val isSideBySide = maxWidth >= 640.dp && maxWidth > maxHeight

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top bar: Brand + Settings button
            ConnectionTopBar(
                onOpenSettings = {
                    performWingpaneHaptic(context, view)
                    showSettingsDialog = true
                },
            )

            // Center content: Side-by-side on tablet landscape, stacked on phone portrait
            if (isSideBySide) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xl, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectionDiagramCard(
                        uiModel = uiModel,
                        isVerticalDiagram = false,
                        modifier = Modifier
                            .weight(1.1f)
                            .widthIn(max = 560.dp)
                            .heightIn(min = 240.dp, max = 380.dp),
                    )
                    ConnectionStateCard(
                        uiModel = uiModel,
                        onOpenSetupGuide = {
                            performWingpaneHaptic(context, view)
                            hasAutoSuggestedSetupGuide = true
                            showSetupGuide = true
                        },
                        onCancelHandshake = {
                            performWingpaneHaptic(context, view)
                            onCancelHandshake()
                        },
                        onTryAgain = {
                            performWingpaneHaptic(context, view)
                            onTryAgain()
                        },
                        onUpdateApp = {
                            performWingpaneHaptic(context, view)
                            onUpdateApp()
                            openAppUpdate(context)
                        },
                        modifier = Modifier
                            .weight(0.9f)
                            .widthIn(min = 320.dp, max = 460.dp),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.lg, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ConnectionDiagramCard(
                        uiModel = uiModel,
                        isVerticalDiagram = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp),
                    )
                    ConnectionStateCard(
                        uiModel = uiModel,
                        onOpenSetupGuide = {
                            performWingpaneHaptic(context, view)
                            hasAutoSuggestedSetupGuide = true
                            showSetupGuide = true
                        },
                        onCancelHandshake = {
                            performWingpaneHaptic(context, view)
                            onCancelHandshake()
                        },
                        onTryAgain = {
                            performWingpaneHaptic(context, view)
                            onTryAgain()
                        },
                        onUpdateApp = {
                            performWingpaneHaptic(context, view)
                            onUpdateApp()
                            openAppUpdate(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Footer: app version + protocol v1 (muted)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (isSideBySide) Alignment.CenterStart else Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.footer_version),
                    style = WingpaneTheme.typography.caption,
                    color = colors.textMuted,
                )
            }
        }
    }

    if (showSetupGuide) {
        SetupGuideBottomSheet(
            model = SetupGuideUiModel(),
            onDismiss = {
                performWingpaneHaptic(context, view)
                showSetupGuide = false
            },
            onOpenDeveloperOptions = {
                performWingpaneHaptic(context, view)
                onOpenDeveloperOptions()
                openDeveloperOptions(context)
            },
        )
    }

    if (showSettingsDialog) {
        ConnectionSettingsDialog(
            keepHudVisible = keepHudVisible,
            onKeepHudVisibleChange = {
                performWingpaneHaptic(context, view)
                onKeepHudVisibleChange(it)
            },
            threeFingerTapHud = threeFingerTapHud,
            onThreeFingerTapHudChange = {
                performWingpaneHaptic(context, view)
                onThreeFingerTapHudChange(it)
            },
            onOpenSetupGuide = {
                performWingpaneHaptic(context, view)
                showSettingsDialog = false
                showSetupGuide = true
            },
            onOpenAbout = {
                performWingpaneHaptic(context, view)
                showSettingsDialog = false
                showAbout = true
            },
            onDismiss = {
                performWingpaneHaptic(context, view)
                showSettingsDialog = false
            },
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = {
            performWingpaneHaptic(context, view)
            showAbout = false
        })
    }
}

@Composable
private fun ConnectionTopBar(
    onOpenSettings: () -> Unit,
) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius
    val touch = WingpaneTheme.touch

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_wingpane_logo),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = stringResource(R.string.app_name),
                style = WingpaneTheme.typography.headingMd,
                color = colors.text,
            )
        }

        Box(
            modifier = Modifier
                .size(touch.minTarget)
                .clip(RoundedCornerShape(radius.card))
                .background(colors.surface2)
                .border(1.dp, colors.border, RoundedCornerShape(radius.card))
                .clickable(onClick = onOpenSettings)
                .testTag("SettingsButton"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.settings_cd),
                tint = colors.text,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ConnectionDiagramCard(
    uiModel: ScreenUiModel,
    isVerticalDiagram: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = WingpaneTheme.colors
    val radius = WingpaneTheme.radius
    val spacing = WingpaneTheme.spacing
    val animationsEnabled = rememberAreAnimationsEnabled()

    val infiniteTransition = rememberInfiniteTransition(label = "CablePulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (uiModel.screenId == ScreenId.S3_HANDSHAKING) 1200 else 2400,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "DotProgress",
    )

    val accentColor = when (uiModel.severity) {
        StatusSeverity.DANGER -> colors.danger
        StatusSeverity.WARNING -> colors.warning
        StatusSeverity.SUCCESS -> colors.success
        else -> colors.primary
    }

    val tabletLabel = if (uiModel.screenId == ScreenId.S5A_VERSION_MISMATCH) {
        "PROTOCOL v${uiModel.appProtocolVersion}"
    } else {
        "TABLET OLED"
    }
    val macScreenLabel = if (uiModel.screenId == ScreenId.S5A_VERSION_MISMATCH) {
        uiModel.hostProtocolVersion?.let { "PROTOCOL v$it" } ?: "PROTOCOL ?"
    } else {
        null
    }

    Surface(
        modifier = modifier.testTag("ConnectionDiagram"),
        shape = RoundedCornerShape(radius.card),
        color = colors.surface1,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            if (!isVerticalDiagram) {
                // Landscape tablet ⇄ cable ⇄ Mac diagram using VectorDrawable base + live status overlay
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_diagram_tablet_mac),
                        contentDescription = "Tablet to Mac USB connection diagram",
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Animated dots on cable during S3 Handshaking or passive pulse during S1 Waiting
                    if (uiModel.screenId == ScreenId.S3_HANDSHAKING || uiModel.screenId == ScreenId.S1_WAITING) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val startX = size.width * 0.405f
                            val endX = size.width * 0.585f
                            val startY = size.height * 0.50f
                            val endY = size.height * 0.55f
                            val dotCount = if (uiModel.screenId == ScreenId.S3_HANDSHAKING) 3 else 1
                            for (i in 0 until dotCount) {
                                val t = if (animationsEnabled) {
                                    (pulseProgress + i / dotCount.toFloat()) % 1f
                                } else {
                                    0.5f
                                }
                                val x = startX + (endX - startX) * t
                                val y = startY + (endY - startY) * t
                                drawCircle(
                                    color = colors.primary.copy(alpha = 0.35f),
                                    radius = 8.dp.toPx(),
                                    center = Offset(x, y),
                                )
                                drawCircle(
                                    color = colors.primary,
                                    radius = 4.dp.toPx(),
                                    center = Offset(x, y),
                                )
                            }
                        }
                    }

                    // Center status icon on cable for error/busy states (S5a–S5e)
                    if (uiModel.severity == StatusSeverity.DANGER || uiModel.severity == StatusSeverity.WARNING) {
                        DiagramCableStatusBadge(
                            screenId = uiModel.screenId,
                            accentColor = accentColor,
                        )
                    }

                    // Device labels positioned to match the VectorDrawable viewport
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = spacing.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = tabletLabel,
                            style = WingpaneTheme.typography.caption,
                            color = colors.textMuted,
                            modifier = Modifier.padding(start = spacing.lg),
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(end = spacing.lg),
                        ) {
                            if (macScreenLabel != null) {
                                Text(
                                    text = macScreenLabel,
                                    style = WingpaneTheme.typography.captionStrong,
                                    color = colors.danger,
                                )
                            }
                            Text(
                                text = "MacBook Pro",
                                style = WingpaneTheme.typography.caption,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            } else {
                // Phone portrait vertical diagram: Tablet (top) ⇅ cable ⇅ Mac (bottom)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerX = size.width / 2f
                        val tabletW = 72.dp.toPx()
                        val tabletH = 68.dp.toPx()
                        val topY = 8.dp.toPx()

                        // Top tablet frame
                        drawRoundRect(
                            color = colors.bg,
                            topLeft = Offset(centerX - tabletW / 2f, topY),
                            size = Size(tabletW, tabletH),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                        drawRoundRect(
                            color = colors.border,
                            topLeft = Offset(centerX - tabletW / 2f, topY),
                            size = Size(tabletW, tabletH),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx()),
                        )

                        // Bottom Mac frame
                        val macW = 128.dp.toPx()
                        val macH = 64.dp.toPx()
                        val macTopY = size.height - macH - 14.dp.toPx()
                        drawRoundRect(
                            color = colors.bg,
                            topLeft = Offset(centerX - macW / 2f, macTopY),
                            size = Size(macW, macH),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                        )
                        drawRoundRect(
                            color = colors.border,
                            topLeft = Offset(centerX - macW / 2f, macTopY),
                            size = Size(macW, macH),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                        drawLine(
                            color = colors.border,
                            start = Offset(centerX - macW * 0.6f, macTopY + macH + 4.dp.toPx()),
                            end = Offset(centerX + macW * 0.6f, macTopY + macH + 4.dp.toPx()),
                            strokeWidth = 3.dp.toPx(),
                        )

                        // Vertical dashed cable line
                        val cableStart = topY + tabletH + 4.dp.toPx()
                        val cableEnd = macTopY - 4.dp.toPx()
                        drawLine(
                            color = accentColor,
                            start = Offset(centerX, cableStart),
                            end = Offset(centerX, cableEnd),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                        )

                        if (uiModel.screenId == ScreenId.S1_WAITING || uiModel.screenId == ScreenId.S3_HANDSHAKING) {
                            val t = if (animationsEnabled) pulseProgress else 0.5f
                            val dotY = cableStart + (cableEnd - cableStart) * t
                            drawCircle(
                                color = colors.primary,
                                radius = 4.dp.toPx(),
                                center = Offset(centerX, dotY),
                            )
                        }
                    }

                    if (uiModel.severity == StatusSeverity.DANGER || uiModel.severity == StatusSeverity.WARNING) {
                        DiagramCableStatusBadge(
                            screenId = uiModel.screenId,
                            accentColor = accentColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagramCableStatusBadge(
    screenId: ScreenId,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    val colors = WingpaneTheme.colors
    val icon: ImageVector = when (screenId) {
        ScreenId.S5C_BUSY -> Icons.Rounded.Lock
        ScreenId.S5A_VERSION_MISMATCH -> Icons.Rounded.Warning
        else -> Icons.Rounded.Close
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.surface1)
            .border(1.5.dp, accentColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ConnectionStateCard(
    uiModel: ScreenUiModel,
    onOpenSetupGuide: () -> Unit,
    onCancelHandshake: () -> Unit,
    onTryAgain: () -> Unit,
    onUpdateApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius
    val animationsEnabled = rememberAreAnimationsEnabled()
    val titleFocusRequester = remember { FocusRequester() }

    // Brief: focus to card title on state change
    LaunchedEffect(uiModel.screenId) {
        runCatching { titleFocusRequester.requestFocus() }
    }

    val enterDuration = if (animationsEnabled) 200 else 0
    val exitDuration = if (animationsEnabled) 120 else 0

    AnimatedContent(
        targetState = uiModel,
        transitionSpec = {
            fadeIn(tween(enterDuration, easing = FastOutSlowInEasing)) togetherWith
                fadeOut(tween(exitDuration))
        },
        label = "ConnectionStateTransition",
        modifier = modifier,
    ) { state ->
        Surface(
            shape = RoundedCornerShape(radius.card),
            color = colors.surface1,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    when (state.liveRegion) {
                        LiveRegionPoliteness.ASSERTIVE -> liveRegion = LiveRegionMode.Assertive
                        LiveRegionPoliteness.POLITE -> liveRegion = LiveRegionMode.Polite
                        LiveRegionPoliteness.NONE -> Unit
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                // Status badge chip (never color alone: always icon + text)
                if (state.badgeLabel != null) {
                    StatusBadgeChip(
                        label = state.badgeLabel,
                        severity = state.severity,
                    )
                }

                // Title (Outfit 28/22sp 600)
                Text(
                    text = state.title,
                    style = WingpaneTheme.typography.headingLg,
                    color = colors.text,
                    modifier = Modifier
                        .focusRequester(titleFocusRequester)
                        .focusable()
                        .semantics {
                            heading()
                        }
                        .testTag("StateCardTitle"),
                )

                // Body (Work Sans 16sp/1.5)
                if (state.body != null) {
                    Text(
                        text = state.body,
                        style = WingpaneTheme.typography.body,
                        color = colors.textMuted,
                        modifier = Modifier.testTag("StateCardBody"),
                    )
                }

                Spacer(modifier = Modifier.height(spacing.xs))

                // Action buttons (Touch >=48x48dp, gap >=8dp)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(WingpaneTheme.touch.minGap),
                ) {
                    when (state.screenId) {
                        ScreenId.S1_WAITING -> {
                            WingpaneSecondaryButton(
                                label = state.secondaryActionLabel ?: stringResource(R.string.action_setup_guide),
                                icon = Icons.Rounded.Info,
                                onClick = onOpenSetupGuide,
                            )
                        }

                        ScreenId.S1_DISCONNECTED -> {
                            WingpanePrimaryButton(
                                label = state.primaryActionLabel ?: "Connect",
                                icon = Icons.Rounded.Refresh,
                                onClick = onTryAgain,
                                fillMaxWidth = true,
                            )
                        }

                        ScreenId.S3_HANDSHAKING -> {
                            WingpaneSecondaryButton(
                                label = state.primaryActionLabel ?: stringResource(R.string.action_cancel),
                                icon = Icons.Rounded.Close,
                                onClick = onCancelHandshake,
                            )
                        }

                        ScreenId.S5A_VERSION_MISMATCH -> {
                            WingpanePrimaryButton(
                                label = state.primaryActionLabel ?: stringResource(R.string.action_update_app),
                                icon = Icons.Rounded.KeyboardArrowDown,
                                onClick = onUpdateApp,
                                fillMaxWidth = true,
                            )
                            WingpaneSecondaryButton(
                                label = state.secondaryActionLabel ?: stringResource(R.string.action_try_again),
                                icon = Icons.Rounded.Refresh,
                                onClick = onTryAgain,
                                fillMaxWidth = true,
                            )
                            WingpaneSecondaryButton(
                                label = state.tertiaryActionLabel ?: stringResource(R.string.action_troubleshooting),
                                icon = Icons.Rounded.Info,
                                onClick = onOpenSetupGuide,
                                fillMaxWidth = true,
                            )
                        }

                        ScreenId.S5B_UNSUPPORTED_DEVICE -> {
                            WingpanePrimaryButton(
                                label = state.primaryActionLabel ?: stringResource(R.string.action_troubleshooting),
                                icon = Icons.Rounded.Info,
                                onClick = onOpenSetupGuide,
                                fillMaxWidth = true,
                            )
                        }

                        ScreenId.S5C_BUSY -> {
                            WingpanePrimaryButton(
                                label = state.primaryActionLabel ?: stringResource(R.string.action_try_again),
                                icon = Icons.Rounded.Refresh,
                                onClick = onTryAgain,
                                fillMaxWidth = true,
                            )
                        }

                        ScreenId.S5D_HANDSHAKE_FAILED,
                        ScreenId.S5E_CONNECTION_LOST -> {
                            WingpanePrimaryButton(
                                label = state.primaryActionLabel ?: stringResource(R.string.action_try_again),
                                icon = Icons.Rounded.Refresh,
                                onClick = onTryAgain,
                                fillMaxWidth = true,
                            )
                            if (state.secondaryActionLabel != null) {
                                WingpaneSecondaryButton(
                                    label = state.secondaryActionLabel,
                                    icon = Icons.Rounded.Info,
                                    onClick = onOpenSetupGuide,
                                    fillMaxWidth = true,
                                )
                            }
                        }

                        ScreenId.STREAM_ACTIVE,
                        ScreenId.STREAM_RECONNECTING -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadgeChip(
    label: String,
    severity: StatusSeverity,
) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius

    val accentColor = when (severity) {
        StatusSeverity.DANGER -> colors.danger
        StatusSeverity.WARNING -> colors.warning
        StatusSeverity.SUCCESS -> colors.success
        else -> colors.primary
    }
    val icon = when (severity) {
        StatusSeverity.WARNING -> Icons.Rounded.Warning
        StatusSeverity.DANGER -> Icons.Rounded.Warning
        else -> Icons.Rounded.Info
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(radius.pill))
            .background(accentColor.copy(alpha = 0.14f))
            .border(1.dp, accentColor, RoundedCornerShape(radius.pill))
            .padding(horizontal = spacing.sm + spacing.xs, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = WingpaneTheme.typography.captionStrong,
            color = accentColor,
        )
    }
}

@Composable
fun WingpanePrimaryButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
) {
    val colors = WingpaneTheme.colors
    val radius = WingpaneTheme.radius
    val touch = WingpaneTheme.touch
    val spacing = WingpaneTheme.spacing

    Button(
        onClick = onClick,
        modifier = modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = touch.minTarget),
        shape = RoundedCornerShape(radius.card),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(spacing.sm))
        }
        Text(
            text = label,
            style = WingpaneTheme.typography.bodyStrong,
            color = colors.onPrimary,
        )
    }
}

@Composable
fun WingpaneSecondaryButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
) {
    val colors = WingpaneTheme.colors
    val radius = WingpaneTheme.radius
    val touch = WingpaneTheme.touch
    val spacing = WingpaneTheme.spacing

    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = touch.minTarget),
        shape = RoundedCornerShape(radius.card),
        border = BorderStroke(1.dp, colors.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.surface2,
            contentColor = colors.text,
        ),
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(spacing.sm))
        }
        Text(
            text = label,
            style = WingpaneTheme.typography.bodyStrong,
            color = colors.text,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupGuideBottomSheet(
    model: SetupGuideUiModel,
    onDismiss: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius
    val touch = WingpaneTheme.touch
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface1,
        contentColor = colors.text,
        scrimColor = colors.bg.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = radius.card, topEnd = radius.card),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.md)
                .testTag("SetupGuideSheet"),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Text(
                        text = model.title,
                        style = WingpaneTheme.typography.headingMd,
                        color = colors.text,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = model.subtitle,
                        style = WingpaneTheme.typography.body,
                        color = colors.textMuted,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(touch.minTarget)
                        .clip(RoundedCornerShape(radius.card))
                        .background(colors.surface2)
                        .border(1.dp, colors.border, RoundedCornerShape(radius.card))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = model.secondaryActionLabel,
                        tint = colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            model.steps.forEach { step ->
                SetupStepCard(step = step)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs, bottom = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(touch.minGap, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WingpaneSecondaryButton(
                    label = model.secondaryActionLabel,
                    icon = null,
                    onClick = onDismiss,
                )
                WingpanePrimaryButton(
                    label = model.primaryActionLabel,
                    icon = Icons.Rounded.Settings,
                    onClick = onOpenDeveloperOptions,
                )
            }
        }
    }
}

@Composable
private fun SetupStepCard(step: SetupGuideStep) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius

    Surface(
        shape = RoundedCornerShape(radius.card),
        color = colors.surface1,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(spacing.sm))
                    .background(colors.surface2)
                    .border(1.dp, colors.primary, RoundedCornerShape(spacing.sm)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = step.number.toString(),
                    style = WingpaneTheme.typography.bodyStrong,
                    color = colors.primary,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = step.title,
                        style = WingpaneTheme.typography.bodyStrong,
                        color = colors.text,
                        // Title wraps; the code badge keeps its intrinsic width.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (step.codeBadge != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(spacing.xs))
                                .background(colors.surface2)
                                .border(1.dp, colors.primary, RoundedCornerShape(spacing.xs))
                                .padding(horizontal = spacing.sm, vertical = 2.dp),
                        ) {
                            Text(
                                text = step.codeBadge,
                                style = WingpaneTheme.typography.captionStrong,
                                color = colors.primary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                Text(
                    text = step.body,
                    style = WingpaneTheme.typography.caption,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun ConnectionSettingsDialog(
    keepHudVisible: Boolean,
    onKeepHudVisibleChange: (Boolean) -> Unit,
    threeFingerTapHud: Boolean,
    onThreeFingerTapHudChange: (Boolean) -> Unit,
    onOpenSetupGuide: () -> Unit,
    onOpenAbout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius
    val touch = WingpaneTheme.touch

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(radius.card),
            color = colors.surface1,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_cd),
                        style = WingpaneTheme.typography.headingMd,
                        color = colors.text,
                    )
                    Box(
                        modifier = Modifier
                            .size(touch.minTarget)
                            .clip(RoundedCornerShape(radius.card))
                            .background(colors.surface2)
                            .border(1.dp, colors.border, RoundedCornerShape(radius.card))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_done),
                            tint = colors.text,
                        )
                    }
                }

                // Brief: Accessibility setting "Keep HUD visible" disables fading
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = touch.minTarget)
                        .clickable { onKeepHudVisibleChange(!keepHudVisible) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep HUD visible",
                            style = WingpaneTheme.typography.bodyStrong,
                            color = colors.text,
                        )
                        Text(
                            text = "Disable 3s idle fading on the stream pill",
                            style = WingpaneTheme.typography.caption,
                            color = colors.textMuted,
                        )
                    }
                    Switch(
                        checked = keepHudVisible,
                        onCheckedChange = onKeepHudVisibleChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onPrimary,
                            checkedTrackColor = colors.primary,
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.surface2,
                            uncheckedBorderColor = colors.border,
                        ),
                    )
                }

                // Brief: 3-finger tap is OFF by default (opt-in in Settings)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = touch.minTarget)
                        .clickable { onThreeFingerTapHudChange(!threeFingerTapHud) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "3-finger tap opens HUD",
                            style = WingpaneTheme.typography.bodyStrong,
                            color = colors.text,
                        )
                        Text(
                            text = "Off by default to avoid clashing with Mac apps",
                            style = WingpaneTheme.typography.caption,
                            color = colors.textMuted,
                        )
                    }
                    Switch(
                        checked = threeFingerTapHud,
                        onCheckedChange = onThreeFingerTapHudChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onPrimary,
                            checkedTrackColor = colors.primary,
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.surface2,
                            uncheckedBorderColor = colors.border,
                        ),
                    )
                }

                WingpaneSecondaryButton(
                    label = stringResource(R.string.action_setup_guide),
                    icon = Icons.Rounded.Info,
                    onClick = onOpenSetupGuide,
                    fillMaxWidth = true,
                )
                WingpaneSecondaryButton(
                    label = "About & guide",
                    icon = Icons.Rounded.Info,
                    onClick = onOpenAbout,
                    fillMaxWidth = true,
                )
            }
        }
    }
}

private val aboutGuide = listOf(
    "Display" to "Your tablet is a second Mac screen. Touch works like a touchscreen; S Pen draws with pressure and tilt.",
    "Pen" to "The tablet becomes a graphics tablet for your main display. Hover moves the cursor, the side button right-clicks. Video is paused to save battery.",
    "Trackpad" to "One finger moves the cursor, tap clicks, two-finger tap right-clicks, two fingers scroll, tap-and-drag moves things. Three-finger swipe: Mission Control, App Exposé, switch Space. Pinch zooms.",
    "Keyboard" to "Switch to Keyboard / Numpad for a MacBook-style layout. Tap a modifier once for the next key, twice to lock. IME types Vietnamese through your tablet keyboard.",
    "Mac setup" to "On the Mac enable Screen Recording and Accessibility for Wingpane and wingpaned (menu bar → Permissions). Without Accessibility the Mac ignores the pen, trackpad and keyboard.",
    "HUD" to "Tap the pill at the top of the stream to switch modes, adjust pen pressure, palm rejection and disconnect.",
)

private val aboutGuideVi = listOf(
    "Display" to "Tablet thành màn hình phụ của Mac. Chạm như màn hình cảm ứng; S Pen vẽ có lực nhấn và độ nghiêng.",
    "Pen" to "Tablet thành bảng vẽ cho màn hình chính. Rê bút để di con trỏ, nút cạnh bút là chuột phải. Video tạm dừng để tiết kiệm pin.",
    "Trackpad" to "Một ngón di con trỏ, chạm là click, chạm hai ngón là chuột phải, hai ngón trượt để cuộn, chạm rồi kéo để kéo thả. Ba ngón vuốt: Mission Control, App Exposé, chuyển Space. Chụm hai ngón để zoom.",
    "Keyboard" to "Chuyển sang Keyboard / Numpad để có bàn phím kiểu MacBook. Bấm phím modifier một lần cho phím kế tiếp, hai lần để khoá. IME để gõ tiếng Việt bằng bàn phím của tablet.",
    "Cài đặt trên Mac" to "Trên Mac bật Screen Recording và Accessibility cho Wingpane và wingpaned (menu trên thanh menu → Permissions). Thiếu Accessibility thì Mac bỏ qua bút, trackpad và bàn phím.",
    "HUD" to "Chạm vào viên thuốc ở đầu màn hình stream để đổi chế độ, chỉnh lực bút, chống chạm nhầm lòng bàn tay và ngắt kết nối.",
)

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    val radius = WingpaneTheme.radius
    val context = LocalContext.current
    var vietnamese by rememberSaveable { mutableStateOf(java.util.Locale.getDefault().language == "vi") }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(radius.card),
            color = colors.surface1,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier.widthIn(min = 280.dp, max = 460.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vietnamese) "Giới thiệu Wingpane" else "About Wingpane", style = WingpaneTheme.typography.headingMd, color = colors.text)
                    WingpaneSecondaryButton(label = if (vietnamese) "English" else "Tiếng Việt", icon = null, onClick = { vietnamese = !vietnamese })
                }
                Text(
                    if (vietnamese) "Phiên bản $version\nBiến tablet Android thành màn hình phụ, bảng vẽ và trackpad cho Mac.\nhttps://github.com/lamnt9/wingpane"
                    else "Version $version\nTurn your Android tablet into a Mac secondary display, pen tablet and trackpad.\nhttps://github.com/lamnt9/wingpane",
                    style = WingpaneTheme.typography.caption,
                    color = colors.textMuted,
                )
                (if (vietnamese) aboutGuideVi else aboutGuide).forEach { (title, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = WingpaneTheme.typography.bodyStrong, color = colors.text)
                        Text(body, style = WingpaneTheme.typography.caption, color = colors.textMuted)
                    }
                }
                WingpanePrimaryButton(label = stringResource(R.string.action_done), icon = null, onClick = onDismiss, fillMaxWidth = true)
            }
        }
    }
}
