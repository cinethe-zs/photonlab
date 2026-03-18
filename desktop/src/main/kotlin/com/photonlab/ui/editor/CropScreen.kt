package com.photonlab.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.photonlab.domain.model.NormalizedRect
import com.photonlab.platform.DesktopBitmap
import kotlin.math.abs
import kotlin.math.min

private enum class CropHandle { NONE, TL, TR, BL, BR, MOVE }

@Composable
fun CropScreen(
    bitmap: DesktopBitmap,
    initialCrop: NormalizedRect?,
    onConfirm: (NormalizedRect?) -> Unit,
    onCancel: () -> Unit,
) {
    val imgW = bitmap.width
    val imgH = bitmap.height

    var crop by remember { mutableStateOf(initialCrop ?: NormalizedRect.FULL) }
    var pixelAspect by remember { mutableStateOf(0f) }

    fun applyAspect(newCrop: NormalizedRect): NormalizedRect =
        if (pixelAspect > 0f) constrainAspect(newCrop, pixelAspect, imgW, imgH) else newCrop

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CropCanvas(
            bitmap = bitmap,
            crop = crop,
            onCropChange = { newRect -> crop = applyAspect(newRect) },
            modifier = Modifier.fillMaxSize().padding(top = 56.dp, bottom = 64.dp),
        )

        val ratioPairs = remember {
            listOf(1f to 1f, 4f/3f to 3f/4f, 3f/2f to 2f/3f, 16f/9f to 9f/16f)
        }
        val ratioLabels = remember {
            listOf(listOf("1:1"), listOf("4:3", "3:4"), listOf("3:2", "2:3"), listOf("16:9", "9:16"))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()).align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.Center,
        ) {
            @Composable
            fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
                FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp))
            }
            Chip("Free", pixelAspect == 0f) { pixelAspect = 0f }
            ratioPairs.forEachIndexed { idx, (landscape, portrait) ->
                val labels = ratioLabels[idx]
                val isLandscape = abs(pixelAspect - landscape) < 0.01f
                val isPortrait  = abs(pixelAspect - portrait)  < 0.01f
                val label = when { isPortrait -> labels.getOrElse(1) { labels[0] }; else -> labels[0] }
                Chip(label, isLandscape || isPortrait) {
                    val newAspect = when {
                        isLandscape && landscape != portrait -> portrait
                        isPortrait  -> landscape
                        else        -> landscape
                    }
                    pixelAspect = newAspect
                    crop = applyAspect(crop)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = {
                    val isFullImage = crop.left < 0.002f && crop.top < 0.002f &&
                            crop.right > 0.998f && crop.bottom > 0.998f
                    onConfirm(if (isFullImage) null else crop)
                },
                modifier = Modifier.weight(1f),
            ) { Text("Apply") }
        }
    }
}

@Composable
private fun CropCanvas(
    bitmap: DesktopBitmap,
    crop: NormalizedRect,
    onCropChange: (NormalizedRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val imgAspect = bitmap.width.toFloat() / bitmap.height
    val cropState by rememberUpdatedState(crop)
    val imgAspectState by rememberUpdatedState(imgAspect)

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val handleTouchPx = 36.dp.toPx()
            val minCropFrac = 0.05f

            fun computeDisplay(canvasW: Float, canvasH: Float): FloatArray {
                val ia = imgAspectState; val ca = canvasW / canvasH
                return if (ia > ca) {
                    val dw = canvasW; val dh = canvasW / ia
                    floatArrayOf((canvasW - dw) / 2f, (canvasH - dh) / 2f, dw, dh)
                } else {
                    val dh = canvasH; val dw = canvasH * ia
                    floatArrayOf((canvasW - dw) / 2f, (canvasH - dh) / 2f, dw, dh)
                }
            }

            fun handleOffset(h: CropHandle, r: NormalizedRect, d: FloatArray): Offset {
                val (dX, dY, dW, dH) = d.toList().map { it }
                val cL = dX + r.left * dW; val cR = dX + r.right * dW
                val cT = dY + r.top * dH;  val cB = dY + r.bottom * dH
                return when (h) {
                    CropHandle.TL -> Offset(cL, cT); CropHandle.TR -> Offset(cR, cT)
                    CropHandle.BL -> Offset(cL, cB); CropHandle.BR -> Offset(cR, cB)
                    else -> Offset.Zero
                }
            }

            awaitEachGesture {
                val firstEvent = awaitPointerEvent()
                val pos = firstEvent.changes.firstOrNull()?.position ?: return@awaitEachGesture
                firstEvent.changes.forEach { it.consume() }
                val d = computeDisplay(size.width.toFloat(), size.height.toFloat())
                val dX = d[0]; val dY = d[1]; val dW = d[2]; val dH = d[3]
                val r = cropState

                fun dist(h: CropHandle): Float {
                    val off = pos - handleOffset(h, r, d)
                    return kotlin.math.sqrt(off.x * off.x + off.y * off.y)
                }

                val corners = listOf(CropHandle.TL, CropHandle.TR, CropHandle.BL, CropHandle.BR)
                val nearHandle = corners.minByOrNull { h: CropHandle -> dist(h) }
                    ?.takeIf { h: CropHandle -> dist(h) < handleTouchPx }
                val normX = (pos.x - dX) / dW; val normY = (pos.y - dY) / dH
                val insideCrop = normX in r.left..r.right && normY in r.top..r.bottom
                val target = nearHandle ?: if (insideCrop) CropHandle.MOVE else CropHandle.NONE
                val startRect = r; val startPos = pos

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    if (target == CropHandle.NONE) continue
                    val dx = (change.position.x - startPos.x) / dW
                    val dy = (change.position.y - startPos.y) / dH
                    val newRect = when (target) {
                        CropHandle.TL -> NormalizedRect((startRect.left + dx).coerceIn(0f, startRect.right - minCropFrac), (startRect.top + dy).coerceIn(0f, startRect.bottom - minCropFrac), startRect.right, startRect.bottom)
                        CropHandle.TR -> NormalizedRect(startRect.left, (startRect.top + dy).coerceIn(0f, startRect.bottom - minCropFrac), (startRect.right + dx).coerceIn(startRect.left + minCropFrac, 1f), startRect.bottom)
                        CropHandle.BL -> NormalizedRect((startRect.left + dx).coerceIn(0f, startRect.right - minCropFrac), startRect.top, startRect.right, (startRect.bottom + dy).coerceIn(startRect.top + minCropFrac, 1f))
                        CropHandle.BR -> NormalizedRect(startRect.left, startRect.top, (startRect.right + dx).coerceIn(startRect.left + minCropFrac, 1f), (startRect.bottom + dy).coerceIn(startRect.top + minCropFrac, 1f))
                        CropHandle.MOVE -> { val w = startRect.right - startRect.left; val h = startRect.bottom - startRect.top; val nL = (startRect.left + dx).coerceIn(0f, 1f - w); val nT = (startRect.top + dy).coerceIn(0f, 1f - h); NormalizedRect(nL, nT, nL + w, nT + h) }
                        CropHandle.NONE -> startRect
                    }
                    onCropChange(newRect)
                    change.consume()
                }
            }
        }
    ) {
        val canvasW = size.width; val canvasH = size.height
        val ca = canvasW / canvasH; val ia = imgAspect
        val dW: Float; val dH: Float; val dX: Float; val dY: Float
        if (ia > ca) { dW = canvasW; dH = canvasW / ia } else { dH = canvasH; dW = canvasH * ia }
        dX = (canvasW - dW) / 2f; dY = (canvasH - dH) / 2f

        drawImage(image = imageBitmap, dstOffset = IntOffset(dX.toInt(), dY.toInt()), dstSize = IntSize(dW.toInt(), dH.toInt()))

        val cL = dX + crop.left * dW; val cT = dY + crop.top * dH
        val cR = dX + crop.right * dW; val cB = dY + crop.bottom * dH
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, Offset(0f, 0f), Size(canvasW, cT)); drawRect(dim, Offset(0f, cB), Size(canvasW, canvasH - cB))
        drawRect(dim, Offset(0f, cT), Size(cL, cB - cT)); drawRect(dim, Offset(cR, cT), Size(canvasW - cR, cB - cT))
        drawRect(color = Color.White, topLeft = Offset(cL, cT), size = Size(cR - cL, cB - cT), style = Stroke(width = 2.dp.toPx()))

        val g = Color.White.copy(alpha = 0.35f); val sw = 1.dp.toPx()
        val tw = (cR - cL) / 3f; val th = (cB - cT) / 3f
        drawLine(g, Offset(cL + tw, cT), Offset(cL + tw, cB), sw)
        drawLine(g, Offset(cL + 2*tw, cT), Offset(cL + 2*tw, cB), sw)
        drawLine(g, Offset(cL, cT + th), Offset(cR, cT + th), sw)
        drawLine(g, Offset(cL, cT + 2*th), Offset(cR, cT + 2*th), sw)

        val bl = 22.dp.toPx(); val bw = 3.dp.toPx()
        drawLine(Color.White, Offset(cL, cT), Offset(cL + bl, cT), bw); drawLine(Color.White, Offset(cL, cT), Offset(cL, cT + bl), bw)
        drawLine(Color.White, Offset(cR - bl, cT), Offset(cR, cT), bw); drawLine(Color.White, Offset(cR, cT), Offset(cR, cT + bl), bw)
        drawLine(Color.White, Offset(cL, cB - bl), Offset(cL, cB), bw); drawLine(Color.White, Offset(cL, cB), Offset(cL + bl, cB), bw)
        drawLine(Color.White, Offset(cR, cB - bl), Offset(cR, cB), bw); drawLine(Color.White, Offset(cR - bl, cB), Offset(cR, cB), bw)

        val hr = 8.dp.toPx()
        drawCircle(Color.White, hr, Offset(cL, cT)); drawCircle(Color.White, hr, Offset(cR, cT))
        drawCircle(Color.White, hr, Offset(cL, cB)); drawCircle(Color.White, hr, Offset(cR, cB))
    }
}

private fun constrainAspect(rect: NormalizedRect, pixelAspect: Float, imageW: Int, imageH: Int): NormalizedRect {
    val normAspect = pixelAspect * imageH.toFloat() / imageW.toFloat()
    val cx = (rect.left + rect.right) / 2f; val cy = (rect.top + rect.bottom) / 2f
    val currentW = rect.right - rect.left
    var newW = currentW; var newH = newW / normAspect
    if (newH > 1f) { newH = 1f; newW = newH * normAspect }
    if (newW > 1f) { newW = 1f; newH = newW / normAspect }
    val maxW = min(newW, 1f); val maxH = min(newH, 1f)
    val nL = (cx - maxW / 2f).coerceIn(0f, 1f - maxW); val nT = (cy - maxH / 2f).coerceIn(0f, 1f - maxH)
    return NormalizedRect(nL, nT, nL + maxW, nT + maxH)
}
