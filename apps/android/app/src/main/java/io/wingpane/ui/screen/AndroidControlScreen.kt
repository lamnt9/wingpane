package io.wingpane.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.wingpane.R
import io.wingpane.proto.UcEdge
import io.wingpane.ui.theme.WingpaneTheme

@Composable
fun AndroidControlScreen(
    serviceConnected: Boolean,
    fgsRunning: Boolean,
    edge: UcEdge?,
    onDisplay: () -> Unit,
    onAccessibilitySettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("uc", Context.MODE_PRIVATE) }
    var gain by remember { mutableFloatStateOf(prefs.getFloat("gain", 1f)) }
    var reverse by remember { mutableStateOf(prefs.getBoolean("reverse_scroll", false)) }
    var oldTone by remember { mutableStateOf(prefs.getBoolean("old_tone", false)) }
    val spacing = WingpaneTheme.spacing
    val colors = WingpaneTheme.colors
    Column(Modifier.fillMaxSize().background(colors.bg).padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(stringResource(R.string.uc_screen_title), style = WingpaneTheme.typography.headingMd, color = colors.text)
        Text(stringResource(if (serviceConnected) R.string.uc_service_on else R.string.uc_service_off), color = colors.text)
        Text(stringResource(if (fgsRunning) R.string.uc_fgs_on else R.string.uc_fgs_off), color = colors.text)
        Text(stringResource(R.string.uc_edge, edge?.name ?: "—"), color = colors.textMuted)
        Button(onClick = onDisplay, modifier = Modifier.fillMaxWidth().heightIn(min = WingpaneTheme.touch.minTarget)) {
            Text(stringResource(R.string.uc_action_display))
        }
        Button(onClick = onAccessibilitySettings, modifier = Modifier.fillMaxWidth().heightIn(min = WingpaneTheme.touch.minTarget)) {
            Text(stringResource(R.string.uc_open_accessibility))
        }
        Text(stringResource(R.string.uc_gain, gain), color = colors.text)
        Slider(value = gain, onValueChange = { gain = it; prefs.edit().putFloat("gain", it).apply() }, valueRange = 0.5f..3f,
            modifier = Modifier.fillMaxWidth().heightIn(min = WingpaneTheme.touch.minTarget))
        Row(Modifier.fillMaxWidth().heightIn(min = WingpaneTheme.touch.minTarget), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.uc_reverse_scroll), color = colors.text)
            Switch(checked = reverse, onCheckedChange = { reverse = it; prefs.edit().putBoolean("reverse_scroll", it).apply() })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = WingpaneTheme.touch.minTarget), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.uc_old_tone), color = colors.text)
            Switch(checked = oldTone, onCheckedChange = { oldTone = it; prefs.edit().putBoolean("old_tone", it).apply() })
        }
        Text(stringResource(R.string.uc_limitations), color = colors.textMuted)
    }
}
