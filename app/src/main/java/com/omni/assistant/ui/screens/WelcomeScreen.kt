package com.omni.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.assistant.data.AgentStatus
import com.omni.assistant.ui.components.OmniOrb
import com.omni.assistant.ui.theme.OmniColors
import com.omni.assistant.ui.theme.OmniGradients
import com.omni.assistant.ui.theme.OmniButton

/**
 * WelcomeScreen — Figma `1 · Welcome` (node 15:5).
 *
 * Iris orb + "omni" silver wordmark + two-line description, pill CTA
 * "Get started". No Sign in link per DS 2.0.
 */
@Composable
fun WelcomeScreen(
    onContinueWithGoogle: () -> Unit,
    onContinueWithCommunity: () -> Unit,
    communityBuild: Boolean = false,
    signingIn: Boolean = false,
    errorMessage: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OmniGradients.Background)
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
                OmniOrb(status = AgentStatus.Idle, modifier = Modifier.size(260.dp))
            }

            Spacer(Modifier.weight(0.4f))

            Text(
                "omni",
                style = TextStyle(
                    brush = OmniGradients.SilverText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 52.sp,
                    letterSpacing = 4.sp,
                ),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Sign in once.\nUse Omni everywhere.",
                color = OmniColors.InkDim,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (communityBuild) {
                    "Connect to your self-hosted backend.\nNo store subscription required."
                } else {
                    "Your subscription is tied to Google.\nNo API keys, no credit packs."
                },
                color = OmniColors.InkMute,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Light,
            )
            if (errorMessage != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    errorMessage,
                    color = OmniColors.Error,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Light,
                )
            }

            Spacer(Modifier.weight(1f))

            if (communityBuild) {
                CommunityButton(onClick = onContinueWithCommunity, signingIn = signingIn)
            } else {
                ContinueWithGoogleButton(
                    onClick = onContinueWithGoogle,
                    signingIn = signingIn,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CommunityButton(onClick: () -> Unit, signingIn: Boolean) {
    OmniButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        fillMaxWidth = true,
        contentPadding = PaddingValues(vertical = 18.dp),
        enabled = !signingIn,
    ) {
        if (signingIn) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        } else {
            Text(
                "Connect to community backend",
                style = TextStyle(
                    brush = OmniGradients.SilverText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                ),
            )
        }
    }
}

@Composable
private fun ContinueWithGoogleButton(onClick: () -> Unit, signingIn: Boolean) {
    OmniButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        fillMaxWidth = true,
        contentPadding = PaddingValues(vertical = 18.dp),
        enabled = !signingIn,
    ) {
        if (signingIn) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "G",
                    color = Color(0xFF4285F4),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Continue with Google",
                    style = TextStyle(
                        brush = OmniGradients.SilverText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        letterSpacing = 0.4.sp,
                    ),
                )
            }
        }
    }
}
