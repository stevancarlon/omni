package com.buddy.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.buddy.assistant.data.AgentStatus
import kotlin.math.*

@Composable
fun BuddyOrb(status: AgentStatus, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing)
        ),
        label = "rotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val (primaryColor, secondaryColor) = when (status) {
        is AgentStatus.Idle -> Pair(Color(0xFF6C63FF), Color(0xFF3ECFCF))
        is AgentStatus.WakeWordListening -> Pair(Color(0xFF6C63FF), Color(0xFF9B59B6))
        is AgentStatus.VoiceListening -> Pair(Color(0xFFFF6B6B), Color(0xFFFFD93D))
        is AgentStatus.Processing -> Pair(Color(0xFF3ECFCF), Color(0xFF6C63FF))
        is AgentStatus.Executing -> Pair(Color(0xFF6BCB77), Color(0xFF3ECFCF))
        is AgentStatus.Speaking -> Pair(Color(0xFFFFD93D), Color(0xFFFF6B6B))
        is AgentStatus.Done -> if ((status as? AgentStatus.Done)?.success == true)
            Pair(Color(0xFF6BCB77), Color(0xFF3ECFCF))
        else
            Pair(Color(0xFFFF6B6B), Color(0xFFFF9F45))
        is AgentStatus.Error -> Pair(Color(0xFFFF6B6B), Color(0xFFFF9F45))
        else -> Pair(Color(0xFF6C63FF), Color(0xFF3ECFCF))
    }

    val animatedPrimary by animateColorAsState(primaryColor, label = "primaryColor")
    val animatedSecondary by animateColorAsState(secondaryColor, label = "secondaryColor")

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(200.dp)) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val radius = size.minDimension / 2

            // Glow layers
            for (i in 4 downTo 1) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            animatedPrimary.copy(alpha = glowAlpha * 0.1f * i),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = radius * (1f + i * 0.15f)
                    ),
                    radius = radius * (1f + i * 0.15f),
                    center = Offset(centerX, centerY)
                )
            }

            // Main orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedSecondary.copy(alpha = 0.9f),
                        animatedPrimary.copy(alpha = 0.7f),
                        animatedPrimary.copy(alpha = 0.9f)
                    ),
                    center = Offset(centerX * 0.8f, centerY * 0.7f),
                    radius = radius * pulseScale
                ),
                radius = radius * 0.72f * pulseScale,
                center = Offset(centerX, centerY)
            )

            // Rotating arc rings
            rotate(rotation, pivot = Offset(centerX, centerY)) {
                drawOrbitRing(
                    center = Offset(centerX, centerY),
                    radius = radius * 0.85f,
                    color = animatedPrimary.copy(alpha = 0.6f),
                    strokeWidth = 2f,
                    dashLength = 30f,
                    gapLength = 20f
                )
            }
            rotate(-rotation * 0.7f, pivot = Offset(centerX, centerY)) {
                drawOrbitRing(
                    center = Offset(centerX, centerY),
                    radius = radius * 0.92f,
                    color = animatedSecondary.copy(alpha = 0.4f),
                    strokeWidth = 1.5f,
                    dashLength = 15f,
                    gapLength = 35f
                )
            }

            // Inner highlight
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    center = Offset(centerX - radius * 0.2f, centerY - radius * 0.25f),
                    radius = radius * 0.3f
                ),
                radius = radius * 0.3f,
                center = Offset(centerX - radius * 0.2f, centerY - radius * 0.25f)
            )
        }
    }
}

private fun DrawScope.drawOrbitRing(
    center: Offset,
    radius: Float,
    color: Color,
    strokeWidth: Float,
    dashLength: Float,
    gapLength: Float
) {
    val totalCircumference = 2 * PI * radius
    val dashCount = (totalCircumference / (dashLength + gapLength)).toInt()
    val anglePerUnit = (2 * PI / dashCount).toFloat()

    for (i in 0 until dashCount) {
        val startAngle = i * anglePerUnit
        val endAngle = startAngle + anglePerUnit * (dashLength / (dashLength + gapLength))
        val startX = center.x + radius * cos(startAngle)
        val startY = center.y + radius * sin(startAngle)
        val endX = center.x + radius * cos(endAngle)
        val endY = center.y + radius * sin(endAngle)
        drawLine(
            color = color,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = strokeWidth
        )
    }
}
