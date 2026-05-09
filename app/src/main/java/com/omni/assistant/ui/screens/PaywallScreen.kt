package com.omni.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.assistant.OmniApplication
import com.omni.assistant.billing.PlayBillingRepository
import com.omni.assistant.billing.PurchaseVerificationException
import com.omni.assistant.billing.StoreProduct
import com.omni.assistant.ui.theme.OmniColors
import com.omni.assistant.ui.theme.OmniGradients
import com.omni.assistant.ui.theme.OmniShapes
import com.omni.assistant.ui.theme.OmniTextMetrics
import com.omni.assistant.ui.theme.ctaPillStyle
import com.omni.assistant.ui.theme.dockPillStyle

import kotlinx.coroutines.launch

@Composable
fun PaywallScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniApplication
    val scope = rememberCoroutineScope()
    val activity = context as? android.app.Activity
    val billingRepository = remember { PlayBillingRepository(context, app) }
    var selectedPlan by remember { mutableStateOf(SubscriptionPlan.Pro) }
    var products by remember { mutableStateOf<List<StoreProduct>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var errorDialog by remember { mutableStateOf<PaymentError?>(null) }

    LaunchedEffect(Unit) {
        runCatching { billingRepository.products(SubscriptionPlan.entries.map { it.productId }) }
            .onSuccess { products = it }
            .onFailure { errorDialog = it.toPaymentError() }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = OmniColors.Ink,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Unlock Omni",
                style = MaterialTheme.typography.displaySmall.copy(
                    brush = OmniGradients.SilverText,
                    fontWeight = FontWeight.Light,
                    fontSize = 40.sp,
                    letterSpacing = 0.5.sp,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Choose the assistant power that matches your day.",
                color = Color(0xFFA7ADBC),
                fontSize = 13.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(24.dp))
            ValueCard()

            Spacer(Modifier.height(26.dp))
            Text(
                "CHOOSE YOUR PLAN",
                color = OmniColors.BrandBlue,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(12.dp))

            PlanCard(
                plan = SubscriptionPlan.Pro,
                selected = selectedPlan == SubscriptionPlan.Pro,
                onClick = { selectedPlan = SubscriptionPlan.Pro },
            )
            Spacer(Modifier.height(12.dp))
            PlanCard(
                plan = SubscriptionPlan.Max,
                selected = selectedPlan == SubscriptionPlan.Max,
                onClick = { selectedPlan = SubscriptionPlan.Max },
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "7-day free trial - monthly billing - cancel anytime",
                color = OmniColors.InkMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(Modifier.height(24.dp))
            PaywallButton(
                selectedPlan = selectedPlan,
                loading = loading,
                onClick = {
                    val selectedProduct = products.firstOrNull { it.productId == selectedPlan.productId }
                    if (activity == null) {
                        errorDialog = PaymentError(
                            title = "Purchase unavailable",
                            message = "Please reopen this screen and try again.",
                        )
                    } else if (selectedProduct == null) {
                        errorDialog = PaymentError(
                            title = "Plan unavailable",
                            message = "This subscription is not available from Google Play yet.",
                        )
                    } else {
                        scope.launch {
                            loading = true
                            errorDialog = null
                            runCatching { billingRepository.purchase(activity, selectedProduct) }
                                .onSuccess { onBack() }
                                .onFailure { errorDialog = it.toPaymentError() }
                            loading = false
                        }
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Restore purchase",
                color = OmniColors.InkMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable {
                        scope.launch {
                            loading = true
                            errorDialog = null
                            runCatching { billingRepository.restore() }
                                .onSuccess { onBack() }
                                .onFailure { errorDialog = it.toPaymentError() }
                            loading = false
                        }
                    },
            )
            Spacer(Modifier.height(28.dp))
        }

        errorDialog?.let { error ->
            PaymentErrorDialog(
                error = error,
                onDismiss = { errorDialog = null },
            )
        }
    }
}

private data class PaymentError(
    val title: String,
    val message: String,
)

private fun Throwable.toPaymentError(): PaymentError {
    val message = message.orEmpty()

    return when {
        this is PurchaseVerificationException -> PaymentError(
            title = "Payment verification issue",
            message = message.ifBlank {
                "Payment completed, but Omni could not verify it. Please try Restore purchase."
            },
        )

        message.contains("cancel", ignoreCase = true) -> PaymentError(
            title = "Purchase cancelled",
            message = "No changes were made to your plan.",
        )

        message.contains("No active subscription", ignoreCase = true) -> PaymentError(
            title = "No subscription found",
            message = "Google Play did not return an active Omni subscription for this account.",
        )

        else -> PaymentError(
            title = "Payment unavailable",
            message = message.takeIf { it.isNotBlank() }
                ?: "Google Play could not complete this request. Please try again.",
        )
    }
}

@Composable
private fun PaymentErrorDialog(
    error: PaymentError,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101114),
        title = {
            Text(error.title, color = OmniColors.Ink, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Text(
                error.message,
                color = OmniColors.InkMute,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        },
        confirmButton = {
            Text(
                "Done",
                color = OmniColors.BrandBlueGlow,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(12.dp),
            )
        },
    )
}

private enum class SubscriptionPlan(
    val title: String,
    val price: String,
    val subtitle: String,
    val badge: String,
    val productId: String,
    val features: List<String>,
) {
    Pro(
        title = "Pro",
        price = "$9",
        subtitle = "For daily hands-free help",
        badge = "BEST START",
        productId = "omni_pro_monthly",
        features = listOf("60 agent runs / day", "Fast models + wake word"),
    ),
    Max(
        title = "Max",
        price = "$19",
        subtitle = "For power users and long tasks",
        badge = "MOST CAPABLE",
        productId = "omni_unlimited_monthly",
        features = listOf(
            "200 agent runs / day",
            "Best models + priority speed",
            "Longer memory for repeat workflows",
        ),
    ),
}

@Composable
private fun ValueCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = OmniShapes.Card,
                ambientColor = Color.Black.copy(alpha = 0.65f),
                spotColor = Color.Black.copy(alpha = 0.65f),
            )
            .dockPillStyle(OmniShapes.Card)
            .padding(24.dp),
    ) {
        Text(
            "BUILT FOR ACTION",
            color = OmniColors.BrandBlueGlow,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = OmniTextMetrics.CapsTightSp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Do more with one voice command.",
            color = OmniColors.Ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "No API keys. Cancel anytime.",
            color = OmniColors.InkMute,
            fontSize = 12.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun PlanCard(
    plan: SubscriptionPlan,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val style = if (selected) Modifier.ctaPillStyle(shape) else Modifier.dockPillStyle(shape)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.shadow(
                        elevation = 18.dp,
                        shape = shape,
                        ambientColor = OmniColors.BrandBlueGlow.copy(alpha = 0.45f),
                        spotColor = OmniColors.BrandBlueGlow.copy(alpha = 0.45f),
                    )
                } else {
                    Modifier
                }
            )
            .then(style)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = OmniColors.BrandBlueGlow.copy(alpha = 0.35f)),
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioDot(selected = selected, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    plan.title,
                    color = OmniColors.Ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    plan.price,
                    color = OmniColors.Ink,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "/mo",
                    color = OmniColors.InkMute,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                plan.subtitle,
                color = OmniColors.InkMute,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(14.dp))
            PlanBadge(text = plan.badge, selected = selected)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                plan.features.forEach { FeatureRow(it) }
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(if (selected) 22.dp else 18.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.dp else 1.3.dp,
                color = if (selected) OmniColors.BrandBlueGlow else Color(0xFFA7ADBC),
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
}

@Composable
private fun PlanBadge(text: String, selected: Boolean) {
    Box(
        modifier = Modifier
            .clip(OmniShapes.Pill)
            .background(
                if (selected) Color(0xFF162032)
                else Color(0xFF1E3B6E).copy(alpha = 0.55f)
            )
            .border(
                width = 1.dp,
                color = if (selected) OmniColors.Hairline else OmniColors.BrandBlueGlow.copy(alpha = 0.75f),
                shape = OmniShapes.Pill,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) OmniColors.BrandBlueGlow else OmniColors.Ink,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.1.sp,
        )
    }
}

@Composable
private fun FeatureRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(OmniColors.BrandBlueGlow)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = Color(0xFFA7ADBC),
            fontSize = 12.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun PaywallButton(
    selectedPlan: SubscriptionPlan,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val shape = OmniShapes.Pill
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 24.dp,
                shape = shape,
                ambientColor = OmniColors.BrandBlueGlow.copy(alpha = 0.35f),
                spotColor = OmniColors.BrandBlueGlow.copy(alpha = 0.35f),
            )
            .clip(shape)
            .background(OmniGradients.PrimaryBlue)
            .border(1.dp, OmniColors.BrandBlueGlow.copy(alpha = 0.55f), shape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White.copy(alpha = 0.4f)),
                enabled = !loading,
                onClick = onClick,
            )
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (loading) "Opening Google Play..." else "Upgrade to ${selectedPlan.title}",
            style = TextStyle(
                brush = OmniGradients.SilverText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            ),
        )
    }
}
