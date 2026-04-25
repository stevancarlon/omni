package com.buddy.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buddy.assistant.data.AgentStatus
import com.buddy.assistant.ui.components.BuddyOrb
import com.buddy.assistant.ui.theme.BuddyColors
import com.buddy.assistant.ui.theme.BuddyGradients
import com.buddy.assistant.ui.theme.OmniButton

/**
 * WelcomeScreen — Figma `1 · Welcome` (node 15:5).
 *
 * Iris orb + "omni" silver wordmark + two-line description, pill CTA
 * "Get started". No Sign in link per DS 2.0.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BuddyGradients.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.weight(0.6f))

            // Orb — Figma sizes it at 300dp for 412dp frame (~73%). Keep same ratio.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                BuddyOrb(status = AgentStatus.Idle, modifier = Modifier.size(260.dp))
            }

            Spacer(Modifier.weight(0.4f))

            Text(
                "omni",
                style = TextStyle(
                    brush = BuddyGradients.SilverText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 52.sp,
                    letterSpacing = 4.sp,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Your voice-powered\nAndroid companion.",
                color = BuddyColors.InkDim,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Say \u201CHey Omni\u201D to get things\ndone \u2014 hands-free, eyes-free.",
                color = BuddyColors.InkMute,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Light,
            )

            Spacer(Modifier.weight(1f))

            GetStartedButton(onClick = onGetStarted)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GetStartedButton(onClick: () -> Unit) {
    OmniButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        fillMaxWidth = true,
        contentPadding = PaddingValues(vertical = 18.dp),
    ) {
        Text(
            "Get started",
            style = TextStyle(
                brush = BuddyGradients.SilverText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        )
    }
}

