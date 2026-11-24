package io.wingpane.ui.state

import io.wingpane.net.ClientState
import io.wingpane.proto.PROTO_VERSION
import io.wingpane.proto.RejectReason

/**
 * Canonical screens from design-system/wingpane/pages/android-client.md (brief v0.3).
 */
enum class ScreenId {
    S1_WAITING,
    S3_HANDSHAKING,
    S5A_VERSION_MISMATCH,
    S5B_UNSUPPORTED_DEVICE,
    S5C_BUSY,
    S5D_HANDSHAKE_FAILED,
    S5E_CONNECTION_LOST,
    STREAM_ACTIVE,
    STREAM_RECONNECTING,
}

enum class LiveRegionPoliteness {
    NONE,
    POLITE,
    ASSERTIVE,
}

enum class StatusSeverity {
    NONE,
    INFO,
    SUCCESS,
    WARNING,
    DANGER,
}

enum class HudStatusDot {
    NONE,
    WARNING,
    DANGER,
}

data class ScreenUiModel(
    val screenId: ScreenId,
    val title: String,
    val body: String?,
    val briefCopy: String,
    val primaryActionLabel: String?,
    val secondaryActionLabel: String?,
    val tertiaryActionLabel: String? = null,
    val badgeLabel: String? = null,
    val severity: StatusSeverity = StatusSeverity.NONE,
    val liveRegion: LiveRegionPoliteness = LiveRegionPoliteness.POLITE,
    val isStreamScreen: Boolean = false,
    val isReconnecting: Boolean = false,
    val hostProtocolVersion: Int? = null,
    val appProtocolVersion: Int = PROTO_VERSION,
    val streamBitrateKbps: UInt = 0u,
    val virtualDisplayWidth: Int? = null,
    val virtualDisplayHeight: Int? = null,
)

data class SetupGuideStep(
    val number: Int,
    val title: String,
    val body: String,
    val codeBadge: String? = null,
)

data class SetupGuideUiModel(
    val title: String = "Setup guide",
    val subtitle: String = "Follow these steps to connect your tablet to your Mac via USB",
    val steps: List<SetupGuideStep> = listOf(
        SetupGuideStep(
            number = 1,
            title = "Enable Developer options and USB debugging on this tablet",
            body = "Go to Android Settings > About tablet > tap Build number 7 times, then enable USB debugging in Developer options.",
        ),
        SetupGuideStep(
            number = 2,
            title = "Accept “Allow USB debugging?” on the tablet",
            body = "Tick “Always allow from this computer” for seamless zero-config reconnection when plugged in.",
        ),
        SetupGuideStep(
            number = 3,
            title = "Run wingpane on your Mac",
            body = "Start the desktop companion app or CLI daemon via Terminal or Spotlight.",
            codeBadge = "wingpane",
        ),
    ),
    val primaryActionLabel: String = "Open Developer options",
    val secondaryActionLabel: String = "Done",
)

object ClientStateMapper {
    const val HIGH_LATENCY_THRESHOLD_MS = 35L
    const val DEFAULT_MISMATCH_HOST_VERSION = 2

    /**
     * Maps every [ClientState] to its exact [ScreenUiModel] and copy from the approved brief v0.3.
     */
    fun map(state: ClientState, hostVersionOverride: Int? = null): ScreenUiModel = when (state) {
        is ClientState.Waiting -> ScreenUiModel(
            screenId = ScreenId.S1_WAITING,
            title = "Waiting for your Mac",
            body = "Connect the USB cable and run Wingpane on your Mac.",
            briefCopy = "Waiting for your Mac — Connect the USB cable and run Wingpane on your Mac.",
            primaryActionLabel = null,
            secondaryActionLabel = "Setup guide",
            badgeLabel = null,
            severity = StatusSeverity.INFO,
            liveRegion = LiveRegionPoliteness.POLITE,
        )

        is ClientState.Handshaking -> ScreenUiModel(
            screenId = ScreenId.S3_HANDSHAKING,
            title = "Connecting to your Mac…",
            body = null,
            briefCopy = "Connecting to your Mac…",
            primaryActionLabel = "Cancel",
            secondaryActionLabel = null,
            badgeLabel = null,
            severity = StatusSeverity.INFO,
            liveRegion = LiveRegionPoliteness.POLITE,
        )

        is ClientState.Active -> {
            val spec = state.accept.virtualDisplaySpec
            ScreenUiModel(
                screenId = ScreenId.STREAM_ACTIVE,
                title = "Connected",
                body = null,
                briefCopy = "Connected",
                primaryActionLabel = null,
                secondaryActionLabel = null,
                badgeLabel = "Connected",
                severity = StatusSeverity.SUCCESS,
                liveRegion = LiveRegionPoliteness.NONE,
                isStreamScreen = true,
                isReconnecting = false,
                streamBitrateKbps = state.accept.bitrateKbps,
                virtualDisplayWidth = spec?.first?.toInt(),
                virtualDisplayHeight = spec?.second?.toInt(),
            )
        }

        is ClientState.Reconnecting -> ScreenUiModel(
            screenId = ScreenId.STREAM_RECONNECTING,
            title = "Reconnecting…",
            body = null,
            briefCopy = "Reconnecting…",
            primaryActionLabel = "Cancel",
            secondaryActionLabel = null,
            badgeLabel = "Reconnecting…",
            severity = StatusSeverity.DANGER,
            liveRegion = LiveRegionPoliteness.POLITE,
            isStreamScreen = true,
            isReconnecting = true,
        )

        is ClientState.Rejected -> mapRejected(state, hostVersionOverride)

        is ClientState.HandshakeFailed -> s5dHandshakeFailed()

        is ClientState.Lost -> ScreenUiModel(
            screenId = ScreenId.S5E_CONNECTION_LOST,
            title = "Connection to your Mac was lost.",
            body = null,
            briefCopy = "Connection to your Mac was lost.",
            primaryActionLabel = "Try again",
            secondaryActionLabel = "Troubleshooting",
            badgeLabel = "CONNECTION LOST",
            severity = StatusSeverity.DANGER,
            liveRegion = LiveRegionPoliteness.ASSERTIVE,
        )
    }

    private fun mapRejected(rejected: ClientState.Rejected, hostVersionOverride: Int?): ScreenUiModel {
        return when (rejected.reason) {
            RejectReason.VersionMismatch -> {
                val hostVer = hostVersionOverride ?: extractHostVersion(rejected) ?: DEFAULT_MISMATCH_HOST_VERSION
                val body = "Mac uses protocol v$hostVer, this app v$PROTO_VERSION."
                val fullCopy = "Update needed — $body"
                ScreenUiModel(
                    screenId = ScreenId.S5A_VERSION_MISMATCH,
                    title = "Update needed",
                    body = body,
                    briefCopy = fullCopy,
                    primaryActionLabel = "Update app",
                    secondaryActionLabel = "Try again",
                    tertiaryActionLabel = "Troubleshooting",
                    badgeLabel = "VERSION MISMATCH",
                    severity = StatusSeverity.DANGER,
                    liveRegion = LiveRegionPoliteness.POLITE,
                    hostProtocolVersion = hostVer,
                )
            }

            RejectReason.UnsupportedCodec -> ScreenUiModel(
                screenId = ScreenId.S5B_UNSUPPORTED_DEVICE,
                title = "This tablet can't decode H.264 video.",
                body = null,
                briefCopy = "This tablet can't decode H.264 video.",
                primaryActionLabel = "Troubleshooting",
                secondaryActionLabel = null,
                badgeLabel = "UNSUPPORTED DEVICE",
                severity = StatusSeverity.DANGER,
                liveRegion = LiveRegionPoliteness.POLITE,
            )

            RejectReason.Busy -> ScreenUiModel(
                screenId = ScreenId.S5C_BUSY,
                title = "Your Mac is already connected to another device.",
                body = null,
                briefCopy = "Your Mac is already connected to another device.",
                primaryActionLabel = "Try again",
                secondaryActionLabel = null,
                badgeLabel = "BUSY",
                severity = StatusSeverity.WARNING,
                liveRegion = LiveRegionPoliteness.POLITE,
            )

            RejectReason.InvalidHello -> s5dHandshakeFailed()
        }
    }

    private fun s5dHandshakeFailed(): ScreenUiModel = ScreenUiModel(
        screenId = ScreenId.S5D_HANDSHAKE_FAILED,
        title = "Couldn't connect to your Mac.",
        body = null,
        briefCopy = "Couldn't connect to your Mac.",
        primaryActionLabel = "Try again",
        secondaryActionLabel = "Troubleshooting",
        badgeLabel = "CONNECTION FAILED",
        severity = StatusSeverity.DANGER,
        liveRegion = LiveRegionPoliteness.POLITE,
    )

    /**
     * Probes for an optional `hostVersion` field on [ClientState.Rejected] or its reason if present.
     */
    private fun extractHostVersion(rejected: ClientState.Rejected): Int? {
        for (target in listOf<Any>(rejected, rejected.reason)) {
            try {
                val field = target.javaClass.declaredFields.firstOrNull { it.name == "hostVersion" } ?: continue
                field.isAccessible = true
                when (val raw = field.get(target)) {
                    is UShort -> return raw.toInt()
                    is Number -> return raw.toInt()
                }
            } catch (_: Throwable) {
                // Ignore reflection errors and fall back to default
            }
        }
        return null
    }

    /**
     * Status dot rule on collapsed HUD pill:
     * - Danger when reconnecting
     * - Warning when latency > 35 ms
     * - None otherwise
     */
    fun resolveHudStatusDot(isReconnecting: Boolean, latencyMs: Long): HudStatusDot = when {
        isReconnecting -> HudStatusDot.DANGER
        latencyMs > HIGH_LATENCY_THRESHOLD_MS -> HudStatusDot.WARNING
        else -> HudStatusDot.NONE
    }

    /**
     * Resolves display latency in milliseconds from VideoDecoder stats (prefers e2eP50Us if available).
     */
    fun resolveLatencyMs(e2eP50Us: Long, decodeP50Us: Long): Long {
        val chosenUs = if (e2eP50Us > 0L) e2eP50Us else decodeP50Us
        return (chosenUs.coerceAtLeast(0L) + 500L) / 1_000L
    }
}

/**
 * Pure state machine for the HUD two-tap disconnect confirmation (3s window, disabled when TalkBack is active)
 * and HUD idle timers (3s pill fade, 5s panel auto-close, 300ms proximity reappear).
 */
class DisconnectConfirmStateMachine(
    val confirmDurationMs: Long = CONFIRM_TIMEOUT_MS,
) {
    var isConfirming: Boolean = false
        private set
    private var confirmStartedAtMs: Long = 0L

    /**
     * Processes a tap on the Disconnect row at [nowMs].
     * @return `true` if disconnect should execute immediately (second tap within confirmation window),
     *         `false` if this tap armed the confirmation prompt ("Tap again to disconnect").
     */
    fun onTap(nowMs: Long, talkBackEnabled: Boolean = false): Boolean {
        updateTime(nowMs, talkBackEnabled)
        return if (!isConfirming) {
            isConfirming = true
            confirmStartedAtMs = nowMs
            false
        } else {
            isConfirming = false
            confirmStartedAtMs = 0L
            true
        }
    }

    /**
     * Advances the clock to [nowMs]. When [talkBackEnabled] is true, confirmation never expires automatically.
     */
    fun updateTime(nowMs: Long, talkBackEnabled: Boolean = false): Boolean {
        if (isConfirming && !talkBackEnabled && nowMs - confirmStartedAtMs >= confirmDurationMs) {
            isConfirming = false
            confirmStartedAtMs = 0L
        }
        return isConfirming
    }

    fun reset() {
        isConfirming = false
        confirmStartedAtMs = 0L
    }

    fun label(): String = if (isConfirming) "Tap again to disconnect" else "Disconnect"

    companion object {
        const val CONFIRM_TIMEOUT_MS = 3_000L
        const val PILL_IDLE_FADE_MS = 3_000L
        const val PANEL_AUTO_CLOSE_MS = 5_000L
        const val PROXIMITY_REAPPEAR_MS = 300L
        const val PROXIMITY_HIDE_RADIUS_DP = 64f
        const val SETUP_GUIDE_AUTO_SUGGEST_MS = 30_000L
    }
}
