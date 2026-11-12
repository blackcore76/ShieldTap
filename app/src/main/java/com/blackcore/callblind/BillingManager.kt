package com.blackcore.callblind

import android.app.Activity
import android.content.Context

/**
 * Plus is free and Google Play Billing is not bundled (no BILLING / INTERNET
 * permission, no "in-app purchases" label). The paid one-time unlock lives in
 * git tag v1.0.2 (BillingManager.kt + billing dependency) if it is ever revived.
 */
class BillingManager(
    private val context: Context,
    private val onEntitlementChanged: (Boolean) -> Unit
) {
    fun start() {
        context.getSharedPreferences("shieldtap_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("plus_purchased", true).apply()
        onEntitlementChanged(true)
    }

    fun launchPurchase(activity: Activity): Boolean = false

    fun isEntitled(): Boolean = true

    companion object {
        const val PLUS_FREE = true
    }
}
