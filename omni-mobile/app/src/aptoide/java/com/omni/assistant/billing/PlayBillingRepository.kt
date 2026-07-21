package com.omni.assistant.billing

import android.app.Activity
import android.content.Context
import com.aptoide.sdk.billing.AptoideBillingClient
import com.aptoide.sdk.billing.AptoideBillingClient.BillingResponseCode
import com.aptoide.sdk.billing.AptoideBillingClient.ProductType
import com.aptoide.sdk.billing.BillingFlowParams
import com.aptoide.sdk.billing.BillingResult
import com.aptoide.sdk.billing.ProductDetails
import com.aptoide.sdk.billing.Purchase
import com.aptoide.sdk.billing.PurchasesUpdatedListener
import com.aptoide.sdk.billing.QueryProductDetailsParams
import com.aptoide.sdk.billing.QueryPurchasesParams
import com.aptoide.sdk.billing.listeners.AptoideBillingClientStateListener
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.omni.assistant.BuildConfig
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

    private val billingClient = AptoideBillingClient.newBuilder(context)
        .setListener(this)
        .setPublicKey(BuildConfig.APTOIDE_PUBLIC_KEY)
        .build()

    suspend fun products(productIds: List<String>): List<StoreProduct> {
        ensureConnected()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(ProductType.INAPP)
                        .build()
                }
            )
            .build()

        val result = suspendCancellableCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsResult ->
                cont.resume(billingResult to productDetailsResult)
            }
        }
        val billingResult = result.first
        val productDetailsResult = result.second
        if (billingResult.responseCode != BillingResponseCode.OK) {
            throw IOException(billingResult.debugMessage)
        }

        return productDetailsResult.productDetailsList.orEmpty().map { details ->
            StoreProduct(
                productId = details.productId,
                title = details.title,
                formattedPrice = aptoidePrice(details),
                details = details,
            )
        }
    }

    suspend fun purchase(activity: Activity, product: StoreProduct) {
        ensureConnected()
        val details = product.details as? ProductDetails
            ?: throw IOException("Product details unavailable")
        val deferred = CompletableDeferred<Result<Purchase>>()
        purchaseResult = deferred

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .setObfuscatedAccountId(app.settingsRepository.authToken.first().take(64))
            .build()

        val launchResult = withContext(Dispatchers.IO) {
            billingClient.launchBillingFlow(activity, params)
        }
        if (launchResult.responseCode != BillingResponseCode.OK) {
            purchaseResult = null
            throw IOException(launchResult.debugMessage)
        }

        val purchase = deferred.await().getOrThrow()
        verifyWithBackend(purchaseProductId(purchase, product.productId), purchaseToken(purchase))
        consume(purchase)
    }

    suspend fun restore() {
        refreshBackendSubscription()
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>) {
        val deferred = purchaseResult ?: return
        purchaseResult = null
        when (billingResult.responseCode) {
            BillingResponseCode.OK -> {
                val purchase = purchases.firstOrNull()
                if (purchase != null) deferred.complete(Result.success(purchase))
                else deferred.complete(Result.failure(IOException("Purchase completed without receipt")))
            }
            BillingResponseCode.USER_CANCELED ->
                deferred.complete(Result.failure(IOException("Purchase cancelled")))
            else ->
                deferred.complete(Result.failure(IOException(billingResult.debugMessage)))
        }
    }

    private suspend fun ensureConnected() {
        if (billingClient.isReady) return
        suspendCancellableCoroutine { cont ->
            billingClient.startConnection(object : AptoideBillingClientStateListener {
                override fun onBillingServiceDisconnected() = Unit

                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingResponseCode.OK) {
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
            .url("$backendUrl/api/billing/aptoide/verify")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $authToken")
            .header("Content-Type", "application/json")
            .build()
        val response = http.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw purchaseVerificationError(response.code, responseBody)
        }
        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val subscription = json.getAsJsonObject("subscription")
        app.settingsRepository.setSubscriptionState(
            status = subscription?.get("status")?.asString ?: "inactive",
            plan = subscription?.get("plan")?.asString ?: "free",
        )
    }

    private fun purchaseVerificationError(responseCode: Int, responseBody: String): IOException {
        val detail = runCatching {
            gson.fromJson(responseBody, JsonObject::class.java)
                ?.get("detail")
                ?.asString
        }.getOrNull()
        val technicalDetail = detail ?: responseBody.take(300)

        val message = when {
            responseCode in listOf(401, 403) ||
                technicalDetail.contains("aptoide", ignoreCase = true) ||
                technicalDetail.contains("catappult", ignoreCase = true) ->
                "Payment completed, but Omni could not verify it with Aptoide yet. Please try Restore purchase after the billing setup is fixed."

            responseCode >= 500 ->
                "Payment completed, but Omni's server could not verify it right now. Please try Restore purchase in a few minutes."

            else ->
                "Payment completed, but Omni could not verify it. Please try Restore purchase."
        }

        return PurchaseVerificationException(message, technicalDetail)
    }

    private suspend fun consume(purchase: Purchase) {
        val token = purchaseToken(purchase)
        suspendCancellableCoroutine { cont ->
            billingClient.consumeAsync(
                com.aptoide.sdk.billing.ConsumeParams.newBuilder()
                    .setPurchaseToken(token)
                    .build()
            ) { result, _ ->
                if (result.responseCode == BillingResponseCode.OK) cont.resume(Unit)
                else cont.cancel(IOException(result.debugMessage))
            }
        }
    }

    private fun aptoidePrice(details: ProductDetails): String {
        return details.oneTimePurchaseOfferDetails?.formattedPrice
            ?: details.subscriptionOfferDetails
                ?.firstOrNull()
                ?.pricingPhases
                ?.pricingPhaseList
                ?.firstOrNull()
                ?.formattedPrice
            ?: ""
    }

    private suspend fun refreshBackendSubscription() = withContext(Dispatchers.IO) {
        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        val authToken = app.settingsRepository.authToken.first()
        val request = Request.Builder()
            .url("$backendUrl/api/billing/status")
            .get()
            .header("Authorization", "Bearer $authToken")
            .build()
        val response = http.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException(responseBody.takeIf { it.isNotBlank() } ?: "No active purchase found")
        }
        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val subscription = json.getAsJsonObject("subscription")
        val status = subscription?.get("status")?.asString ?: "inactive"
        val plan = subscription?.get("plan")?.asString ?: "free"
        app.settingsRepository.setSubscriptionState(status = status, plan = plan)
        if (status != "active" || plan == "free") {
            throw IOException("No active purchase found")
        }
    }

    private fun purchaseProductId(purchase: Purchase, fallback: String): String {
        return purchase.products.firstOrNull()
            ?: fallback
    }

    private fun purchaseToken(purchase: Purchase): String {
        return purchase.purchaseToken
    }
}
