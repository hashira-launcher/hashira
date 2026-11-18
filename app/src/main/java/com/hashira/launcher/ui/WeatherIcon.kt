package com.hashira.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.hashira.launcher.weather.WeatherKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Weather icons drawn as a white outline (no fonts or images). */
@Composable
internal fun WeatherIcon(kind: WeatherKind, sizeDp: Int, color: Color = Color.White.copy(alpha = 0.92f)) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            WeatherKind.SUN -> sun(s, 0.5f, 0.5f, 0.17f, color, stroke)
            WeatherKind.CLOUD -> cloud(s, color, stroke)
            WeatherKind.PARTLY -> {
                sun(s, 0.27f, 0.27f, 0.10f, color, stroke)
                withTransform({
                    translate(left = s * 0.14f, top = s * 0.12f)
                    scale(0.8f, 0.8f, pivot = Offset.Zero)
                }) { cloud(s, color, stroke) }
            }
            WeatherKind.FOG -> {
                val ys = listOf(0.32f, 0.47f, 0.62f, 0.77f)
                val xs = listOf(0.20f to 0.80f, 0.12f to 0.68f, 0.20f to 0.80f, 0.30f to 0.72f)
                ys.forEachIndexed { i, y ->
                    drawLine(color, Offset(xs[i].first * s, y * s), Offset(xs[i].second * s, y * s),
                        strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
            WeatherKind.RAIN -> {
                withTransform({ translate(top = -0.10f * s) }) { cloud(s, color, stroke) }
                listOf(0.36f, 0.52f, 0.68f).forEach { x ->
                    drawLine(color, Offset(x * s, 0.74f * s), Offset((x - 0.05f) * s, 0.90f * s),
                        strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
            WeatherKind.SNOW -> {
                withTransform({ translate(top = -0.10f * s) }) { cloud(s, color, stroke) }
                listOf(0.34f to 0.78f, 0.50f to 0.88f, 0.66f to 0.78f).forEach { (x, y) ->
                    drawCircle(color, radius = s * 0.03f, center = Offset(x * s, y * s))
                }
            }
            WeatherKind.THUNDER -> {
                withTransform({ translate(top = -0.10f * s) }) { cloud(s, color, stroke) }
                val bolt = Path().apply {
                    moveTo(0.54f * s, 0.66f * s)
                    lineTo(0.43f * s, 0.82f * s)
                    lineTo(0.55f * s, 0.82f * s)
                    lineTo(0.46f * s, 0.97f * s)
                }
                drawPath(bolt, color, style = stroke)
            }
        }
    }
}

private fun DrawScope.sun(s: Float, cx: Float, cy: Float, r: Float, color: Color, stroke: Stroke) {
    val c = Offset(cx * s, cy * s)
    drawCircle(color, radius = r * s, center = c, style = stroke)
    for (i in 0 until 8) {
        val a = (i * PI / 4).toFloat()
        val d = Offset(cos(a), sin(a))
        drawLine(color, c + d * (r * s * 1.55f), c + d * (r * s * 2.25f),
            strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

/** Cloud outline: union of three circles and a rounded base. */
private fun DrawScope.cloud(s: Float, color: Color, stroke: Stroke) {
    var path = Path().apply {
        addRoundRect(RoundRect(Rect(0.17f * s, 0.58f * s, 0.84f * s, 0.76f * s), CornerRadius(0.09f * s)))
    }
    listOf(Triple(0.30f, 0.60f, 0.13f), Triple(0.50f, 0.47f, 0.18f), Triple(0.69f, 0.60f, 0.15f))
        .forEach { (cx, cy, r) ->
            val circle = Path().apply { addOval(Rect(Offset(cx * s, cy * s), r * s)) }
            path = Path.combine(PathOperation.Union, path, circle)
        }
    drawPath(path, color, style = stroke)
}
