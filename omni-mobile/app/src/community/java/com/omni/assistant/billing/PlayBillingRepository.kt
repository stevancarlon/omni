package com.omni.assistant.billing

import android.app.Activity
import android.content.Context
import com.omni.assistant.OmniApplication
import java.io.IOException

/** Billing is intentionally absent from the self-hosted community distribution. */
class PlayBillingRepository(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") app: OmniApplication,
) {
    @Suppress("UNUSED_PARAMETER")
    suspend fun products(productIds: List<String>): List<StoreProduct> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    suspend fun purchase(activity: Activity, product: StoreProduct): Nothing {
        throw IOException("Store billing is not available in the community build")
    }

    suspend fun restore(): Nothing {
        throw IOException("Store billing is not available in the community build")
    }
}
