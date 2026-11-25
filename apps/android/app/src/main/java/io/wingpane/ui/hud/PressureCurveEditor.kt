package io.wingpane.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.wingpane.proto.PenTarget
import io.wingpane.ui.theme.WingpaneTheme
import kotlin.math.hypot

/** Pure preset definitions shared by the editor and its unit tests. */
object PressureCurvePresets {
    val Soft = listOf(.12f, .35f, .55f, .95f)
    val Linear = listOf(.25f, .25f, .75f, .75f)
    val Hard = listOf(.45f, .20f, .88f, .60f)

    fun named(name: String) = when (name) {
        "Soft" -> Soft
        "Hard" -> Hard
        else -> Linear
    }

    fun normalized(curve: List<Float>, index: Int, value: Float): List<Float> =
        curve.toMutableList().also { it[index] = value.coerceIn(0f, 1f) }
}

@Composable
fun PressureCurveEditor(curve: List<Float>, target: PenTarget, onChange: (List<Float>, PenTarget) -> Unit) {
    val colors = WingpaneTheme.colors
    val spacing = WingpaneTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.surface2, RoundedCornerShape(WingpaneTheme.radius.card)).padding(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text("Pressure response", color = colors.text)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            listOf("Soft", "Linear", "Hard").forEach { label ->
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).heightIn(min = WingpaneTheme.touch.minTarget)
                        .background(if (curve == PressureCurvePresets.named(label)) colors.primary else colors.bg, RoundedCornerShape(8.dp))
                        .clickable { onChange(PressureCurvePresets.named(label), target) },
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { Text(label, color = if (curve == PressureCurvePresets.named(label)) colors.onPrimary else colors.text) }
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(104.dp).pointerInput(curve) {
                detectDragGestures { change, _ ->
                    val p = Offset(change.position.x.coerceIn(0f, size.width.toFloat()), change.position.y.coerceIn(0f, size.height.toFloat()))
                    val values = curve.toMutableList()
                    val point1 = Offset(values[0] * size.width, (1f - values[1]) * size.height)
                    val point2 = Offset(values[2] * size.width, (1f - values[3]) * size.height)
                    val i = if (hypot(p.x - point1.x, p.y - point1.y) <= hypot(p.x - point2.x, p.y - point2.y)) 0 else 2
                    values[i] = (p.x / size.width).coerceIn(0f, 1f)
                    values[i + 1] = (1f - p.y / size.height).coerceIn(0f, 1f)
                    onChange(values, target)
                }
            },
        ) {
            val p1 = Offset(curve[0] * size.width, (1f - curve[1]) * size.height)
            val p2 = Offset(curve[2] * size.width, (1f - curve[3]) * size.height)
            val start = Offset(0f, size.height); val end = Offset(size.width, 0f)
            val path = Path().apply { moveTo(start.x, start.y); cubicTo(p1.x, p1.y, p2.x, p2.y, end.x, end.y) }
            drawLine(colors.border, Offset(0f, 0f), start, 1.dp.toPx())
            drawLine(colors.border, start, end, 1.dp.toPx())
            drawPath(path, colors.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            drawCircle(colors.text, 7.dp.toPx(), p1)
            drawCircle(colors.text, 7.dp.toPx(), p2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            PenTarget.entries.forEach { candidate ->
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(1f).heightIn(min = WingpaneTheme.touch.minTarget)
                        .background(if (candidate == target) colors.primary else colors.bg, RoundedCornerShape(8.dp))
                        .clickable { onChange(curve, candidate) },
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { Text(if (candidate == PenTarget.MainDisplay) "Main display" else "Virtual display", color = if (candidate == target) colors.onPrimary else colors.text) }
            }
        }
    }
}
