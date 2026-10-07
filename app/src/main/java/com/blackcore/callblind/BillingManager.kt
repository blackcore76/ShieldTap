package com.blackcore.callblind

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play Billing for the one-time "Plus" unlock (non-consumable).
 * Entitlement is cached in SharedPreferences and re-verified on every start
 * via queryPurchases (handles reinstall / new device).
 */
class BillingManager(
    private val context: Context,
    private val onEntitlementChanged: (Boolean) -> Unit
) {
    private val prefs = context.getSharedPreferences("shieldtap_prefs", Context.MODE_PRIVATE)
    private var productDetails: ProductDetails? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handlePurchase(it) }
        }
    }

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun start() {
        // Debug builds unlock Plus for free so the auto on/off logic can be
        // tested locally (Play billing does not work on sideloaded debug APKs).
        if (BuildConfig.DEBUG) {
            setEntitled(true)
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    queryPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()
        billingClient.queryProductDetailsAsync(params) { _, result ->
            productDetails = result.productDetailsList.firstOrNull()
        }
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { _, purchases ->
            val owned = purchases.any {
                it.products.contains(PRODUCT_ID) &&
                    it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            setEntitled(owned)
            purchases.forEach { handlePurchase(it) }
        }
    }

    fun launchPurchase(activity: Activity): Boolean {
        val pd = productDetails ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd)
                        .build()
                )
            ).build()
        billingClient.launchBillingFlow(activity, params)
        return true
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.products.contains(PRODUCT_ID)) return
        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(ackParams) { }
        }
        setEntitled(true)
    }

    private fun setEntitled(entitled: Boolean) {
        prefs.edit().putBoolean("plus_purchased", entitled).apply()
        onEntitlementChanged(entitled)
    }

    fun isEntitled(): Boolean =
        BuildConfig.DEBUG || prefs.getBoolean("plus_purchased", false)

    companion object {
        const val PRODUCT_ID = "plus_unlock"
    }
}
