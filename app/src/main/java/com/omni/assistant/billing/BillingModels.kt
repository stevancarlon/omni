package com.omni.assistant.billing

import java.io.IOException

data class StoreProduct(
    val productId: String,
    val title: String,
    val formattedPrice: String,
    val details: Any,
)

class PurchaseVerificationException(
    userMessage: String,
    val technicalDetail: String? = null,
) : IOException(userMessage)
