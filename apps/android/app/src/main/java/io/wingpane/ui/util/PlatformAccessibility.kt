package io.wingpane.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Observes whether TalkBack / touch exploration is enabled.
 * Per brief: when TalkBack is on, all HUD timers are disabled.
 */
@Composable
fun rememberIsTalkBackEnabled(): State<Boolean> {
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    val state = remember(accessibilityManager) {
        mutableStateOf(accessibilityManager?.isTouchExplorationEnabled == true)
    }
    DisposableEffect(accessibilityManager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            state.value = enabled
        }
        accessibilityManager?.addTouchExplorationStateChangeListener(listener)
        state.value = accessibilityManager?.isTouchExplorationEnabled == true
        onDispose {
            accessibilityManager?.removeTouchExplorationStateChangeListener(listener)
        }
    }
    return state
}

/**
 * Checks whether system animations are enabled ("Remove animations" off).
 * Per brief: when system "Remove animations" is enabled, use fades only.
 */
@Composable
fun rememberAreAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val animScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            val transitionScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            )
            animScale > 0f && transitionScale > 0f
        }.getOrDefault(true)
    }
}

/**
 * Triggers a 15ms haptic pulse on HUD and toolbar taps per docs/03 §1 & brief v0.3.
 */
fun performWingpaneHaptic(context: Context, view: View? = null) {
    val vibrated = runCatching {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE))
            true
        } else {
            false
        }
    }.getOrDefault(false)

    if (!vibrated) {
        runCatching {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }
}

/**
 * Opens Android Developer options (Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
 * with a fallback to main Settings if unavailable.
 */
fun openDeveloperOptions(context: Context) {
    val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val launched = runCatching {
        context.startActivity(devIntent)
        true
    }.getOrDefault(false)
    if (!launched) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}

/**
 * Attempts to open the app store listing for updating the app.
 */
fun openAppUpdate(context: Context) {
    val pkg = context.packageName
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(marketIntent) }
}
