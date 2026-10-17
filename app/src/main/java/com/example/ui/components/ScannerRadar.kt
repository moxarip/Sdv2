package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanAccentLight
import com.example.ui.theme.EmeraldRescue
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ScannerRadar(
    isScanning: Boolean,
    modifier: Modifier = Modifier,
    centerContent: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarTransition")

    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarAngle"
    )

    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseRadius"
    )

    Box(
        modifier = modifier.size(190.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f

            // Concentric radar rings
            drawCircle(
                color = CyanAccent.copy(alpha = 0.15f),
                radius = maxRadius,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = CyanAccent.copy(alpha = 0.25f),
                radius = maxRadius * 0.70f,
                style = Stroke(width = 1.2.dp.toPx())
            )
            drawCircle(
                color = CyanAccent.copy(alpha = 0.35f),
                radius = maxRadius * 0.40f,
                style = Stroke(width = 1.dp.toPx())
            )

            // Crosshairs
            drawLine(
                color = CyanAccent.copy(alpha = 0.2f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = CyanAccent.copy(alpha = 0.2f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1.dp.toPx()
            )

            if (isScanning) {
                // Expanding pulse wave
                drawCircle(
                    color = EmeraldRescue.copy(alpha = (1.0f - pulseRadius) * 0.5f),
                    radius = maxRadius * pulseRadius,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Rotating radar beam
                val rad = Math.toRadians(angle.toDouble())
                val beamEnd = Offset(
                    x = (center.x + maxRadius * cos(rad)).toFloat(),
                    y = (center.y + maxRadius * sin(rad)).toFloat()
                )

                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(CyanAccentLight, Color.Transparent),
                        start = center,
                        end = beamEnd
                    ),
                    start = center,
                    end = beamEnd,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        centerContent()
    }
}
