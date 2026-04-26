package com.omni.assistant.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.omni.assistant.OmniApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class StoreProduct(
    val productId: String,
    val title: String,
    val formattedPrice: String,
    val details: ProductDetails,
)

class PlayBillingRepository(
    private val context: Context,
    private val app: OmniApplication,
) : PurchasesUpdatedListener {

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var purchaseResult: CompletableDeferred<Result<Purchase>>? = null

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    suspend fun products(productIds: List<String>): List<StoreProduct> {
        ensureConnected()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
            )
            .build()

        val result = suspendCancellableCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                cont.resume(billingResult to productDetailsList)
            }
        }
        val billingResult = result.first
        val productDetailsList = result.second
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            throw IOException(billingResult.debugMessage)
        }

        return productDetailsList.orEmpty().map { details ->
            StoreProduct(
                productId = details.productId,
                title = details.title,
                formattedPrice = details.subscriptionOfferDetails
                    ?.firstOrNull()
                    ?.pricingPhases
                    ?.pricingPhaseList
                    ?.firstOrNull()
                    ?.formattedPrice
                    ?: "",
                details = details,
            )
        }
    }

    suspend fun purchase(activity: Activity, product: StoreProduct) {
        ensureConnected()
        val offerToken = product.details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: throw IOException("Subscription offer unavailable")
        val deferred = CompletableDeferred<Result<Purchase>>()
        purchaseResult = deferred

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product.details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()

        val launchResult = billingClient.launchBillingFlow(activity, params)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            purchaseResult = null
            throw IOException(launchResult.debugMessage)
        }

        val purchase = deferred.await().getOrThrow()
        verifyWithBackend(purchase.products.firstOrNull() ?: product.productId, purchase.purchaseToken)
        acknowledgeIfNeeded(purchase)
    }

    suspend fun restore() {
        ensureConnected()
        val result = suspendCancellableCoroutine { cont ->
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            ) { billingResult, purchases ->
                cont.resume(billingResult to purchases)
            }
        }
        val billingResult = result.first
        val purchases = result.second
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            throw IOException(billingResult.debugMessage)
        }
        val purchase = purchases.firstOrNull()
            ?: throw IOException("No active subscription found")
        verifyWithBackend(purchase.products.firstOrNull().orEmpty(), purchase.purchaseToken)
        acknowledgeIfNeeded(purchase)
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        val deferred = purchaseResult ?: return
        purchaseResult = null
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase = purchases?.firstOrNull()
                if (purchase != null) deferred.complete(Result.success(purchase))
                else deferred.complete(Result.failure(IOException("Purchase completed without receipt")))
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                deferred.complete(Result.failure(IOException("Purchase cancelled")))
            else ->
                deferred.complete(Result.failure(IOException(billingResult.debugMessage)))
        }
    }

    private suspend fun ensureConnected() {
        if (billingClient.isReady) return
        suspendCancellableCoroutine { cont ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() = Unit

                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        cont.resume(Unit)
                    } else {
                        cont.cancel(IOException(result.debugMessage))
                    }
                }
            })
        }
    }

    private suspend fun verifyWithBackend(productId: String, purchaseToken: String) = withContext(Dispatchers.IO) {
        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        val authToken = app.settingsRepository.authToken.first()
        val body = gson.toJson(
            mapOf(
                "product_id" to productId,
                "purchase_token" to purchaseToken,
            )
        )
        val request = Request.Builder()
            .url("$backendUrl/api/billing/google/verify")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $authToken")
            .header("Content-Type", "application/json")
            .build()
        val response = http.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException("Purchase verification failed (${response.code}): ${responseBody.take(200)}")
        }
        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val subscription = json.getAsJsonObject("subscription")
        app.settingsRepository.setSubscriptionState(
            status = subscription?.get("status")?.asString ?: "inactive",
            plan = subscription?.get("plan")?.asString ?: "free",
        )
    }

    private suspend fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        suspendCancellableCoroutine { cont ->
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) cont.resume(Unit)
                else cont.cancel(IOException(result.debugMessage))
            }
        }
    }
}
