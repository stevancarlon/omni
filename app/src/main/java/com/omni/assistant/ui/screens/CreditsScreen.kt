package com.omni.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.assistant.OmniApplication
import androidx.compose.ui.text.TextStyle
import com.omni.assistant.ui.theme.OmniColors
import com.omni.assistant.ui.theme.OmniGradients
import com.omni.assistant.ui.theme.OmniShapes
import com.omni.assistant.ui.theme.OmniTextMetrics
import com.omni.assistant.ui.theme.ctaPillStyle
import com.omni.assistant.ui.theme.dockPillStyle
import kotlinx.coroutines.launch

/**
 * CreditsScreen — Figma `6 · Credits` (node 15:109).
 *
 * Balance card on top, three top-up tiers below (200 / 500 Popular / 2000 Best
 * value), primary "Buy credits" CTA. The balance number uses the iris gradient
 * brush via text+gradient approach is overkill — we render the numeral big
 * and white, as Figma does.
 */
@Composable
fun CreditsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniApplication
    val scope = rememberCoroutineScope()
    val balance by app.settingsRepository.creditsBalance.collectAsState(initial = 0)
    var selected by remember { mutableStateOf(CreditTier.Popular) }
    val onBuy: (CreditTier) -> Unit = { tier ->
        // No real billing integration yet — credit the pack locally so the
        // balance is genuinely functional rather than a display-only mock.
        scope.launch { app.settingsRepository.addCredits(tier.credits) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OmniGradients.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OmniColors.Ink)
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Credits",
                style = MaterialTheme.typography.displaySmall.copy(
                    brush = OmniGradients.SilverText,
                    fontWeight = FontWeight.Light,
                    fontSize = 40.sp,
                    letterSpacing = 0.5.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No API keys, no setup — just top up and go.",
                color = OmniColors.InkMute,
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
            )

            Spacer(Modifier.height(24.dp))

            BalanceCard(balance = balance)

            Spacer(Modifier.height(28.dp))
            Text(
                "TOP UP",
                color = OmniColors.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = OmniTextMetrics.CapsTightSp,
            )
            Spacer(Modifier.height(12.dp))

            TierRow(
                tier = CreditTier.Basic,
                selected = selected == CreditTier.Basic,
                onClick = { selected = CreditTier.Basic },
            )
            Spacer(Modifier.height(10.dp))
            TierRow(
                tier = CreditTier.Popular,
                selected = selected == CreditTier.Popular,
                onClick = { selected = CreditTier.Popular },
            )
            Spacer(Modifier.height(10.dp))
            TierRow(
                tier = CreditTier.Best,
                selected = selected == CreditTier.Best,
                onClick = { selected = CreditTier.Best },
            )

            Spacer(Modifier.weight(1f))

            BuyButton(onClick = { onBuy(selected) })
            Spacer(Modifier.height(24.dp))
        }
    }
}

enum class CreditTier(val credits: Int, val price: String, val badge: String) {
    Basic(200, "$2", "for occasional use"),
    Popular(500, "$5", "Popular"),
    Best(2000, "$15", "Best value"),
}

@Composable
private fun BalanceCard(balance: Int) {
    // DS 2.0 dark-metal card — gradient body + bevel + gray hairline.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .dockPillStyle(OmniShapes.CardLg)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            "BALANCE",
            color = OmniColors.Accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = OmniTextMetrics.CapsTightSp,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$balance",
                style = TextStyle(
                    brush = OmniGradients.SilverText,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "credits remaining",
                color = OmniColors.InkMute,
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun TierRow(tier: CreditTier, selected: Boolean, onClick: () -> Unit) {
    // DS 2.0 tier row — same dark-metal gradient body for every row. When
    // selected, swap the gray hairline for the blue CTA hairline. Badges are
    // always neutral gray (`#A7ADBC`) — no pink "Popular"/"Best value" tint
    // per Figma `6 · Credits`.
    val tierShape = RoundedCornerShape(16.dp)
    val style = if (selected) Modifier.ctaPillStyle(tierShape) else Modifier.dockPillStyle(tierShape)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(style)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Radio indicator — hairline ring; filled with brand blue when active.
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = if (selected) OmniColors.BrandBlueGlow else OmniColors.Hairline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(OmniColors.BrandBlueGlow),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${tier.credits} credits",
                color = OmniColors.Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                tier.badge,
                color = Color(0xFFA7ADBC),
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
            )
        }
        PricePill(price = tier.price)
    }
}

@Composable
private fun PricePill(price: String) {
    Box(
        modifier = Modifier
            .dockPillStyle(OmniShapes.Pill)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            price,
            color = OmniColors.Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
        )
    }
}

/**
 * Buy credits CTA — distinct from the standard `OmniButton` because Figma
 * specs the most prominent monetary action with a horizontal blue gradient
 * body (`#1E3B6E → #6CB8FF`) instead of the dark-metal body. Keeps the same
 * blue hairline + 24dp blue outer glow as every other CTA.
 */
@Composable
private fun BuyButton(onClick: () -> Unit) {
    val shape = OmniShapes.Pill
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 24.dp,
                shape = shape,
                ambientColor = Color(0xFF6CB8FF).copy(alpha = 0.35f),
                spotColor = Color(0xFF6CB8FF).copy(alpha = 0.35f),
            )
            .clip(shape)
            .background(OmniGradients.PrimaryBlue)
            .border(1.dp, Color(0xFF6CB8FF).copy(alpha = 0.55f), shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White.copy(alpha = 0.4f)),
                onClick = onClick,
            )
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Buy credits",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
    }
}
