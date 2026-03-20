package com.photonlab.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.round

@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    step: Float = 1f,
    defaultValue: Float = 0f,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(104.dp),
            maxLines = 1,
        )
        StepSlider(
            value = value, valueRange = valueRange, step = step,
            onValueChange = onValueChange, modifier = Modifier.weight(1f),
        )
        Text(
            text = formatValue(value, valueRange),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
        Box(Modifier.size(20.dp)) {
            if (value != defaultValue) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).align(Alignment.Center).clickable { onValueChange(defaultValue) },
                )
            }
        }
    }
}

@Composable
private fun StepSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = valueRange.endInclusive - valueRange.start
    val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)
    val currentValue by rememberUpdatedState(value)
    var trackWidth by remember { mutableIntStateOf(1) }

    val primary        = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface      = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = modifier
            .height(36.dp)
            .onSizeChanged { trackWidth = it.width }
            .pointerInput(valueRange, step) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val startX = firstDown.position.x
                    firstDown.consume()
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            if (!dragging) {
                                val v = currentValue
                                val frac = ((v - valueRange.start) / range).coerceIn(0f, 1f)
                                val thumbX = frac * trackWidth.toFloat()
                                val delta = if (startX < thumbX) -step else step
                                onValueChange((v + delta).coerceIn(valueRange.start, valueRange.endInclusive))
                            }
                            break
                        }
                        if (!dragging && abs(change.position.x - startX) > touchSlop) dragging = true
                        if (dragging) {
                            val newFrac = (change.position.x / trackWidth.toFloat()).coerceIn(0f, 1f)
                            val raw = newFrac * range + valueRange.start
                            val stepped = (round(raw.toDouble() / step) * step).toFloat().coerceIn(valueRange.start, valueRange.endInclusive)
                            onValueChange(stepped)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val thumbR = 10.dp.toPx(); val halfH = 3.dp.toPx(); val cy = size.height / 2f
        val trackL = thumbR; val trackR = size.width - thumbR; val trackLen = trackR - trackL
        val thumbCx = trackL + fraction * trackLen

        drawRoundRect(color = surfaceVariant, topLeft = Offset(trackL, cy - halfH), size = Size(trackLen, halfH * 2), cornerRadius = CornerRadius(halfH))
        if (thumbCx > trackL) drawRoundRect(color = primary, topLeft = Offset(trackL, cy - halfH), size = Size(thumbCx - trackL, halfH * 2), cornerRadius = CornerRadius(halfH))
        if (valueRange.start < 0f && valueRange.endInclusive > 0f) {
            val zeroX = trackL + (-valueRange.start / range) * trackLen
            drawLine(color = onSurface.copy(alpha = 0.35f), start = Offset(zeroX, cy - 7.dp.toPx()), end = Offset(zeroX, cy + 7.dp.toPx()), strokeWidth = 1.5f)
        }
        drawCircle(color = primary,       radius = thumbR,              center = Offset(thumbCx, cy))
        drawCircle(color = surfaceVariant, radius = thumbR - 3.dp.toPx(), center = Offset(thumbCx, cy))
    }
}

private fun formatValue(value: Float, range: ClosedFloatingPointRange<Float>): String =
    if (range.endInclusive - range.start <= 10f) "%.1f".format(value) else "%.0f".format(value)
