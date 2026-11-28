package com.blackcore.callblind

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Asks the on-device Play Store app whether a newer version exists (no network
 * permission needed). Non-Play installs (debug/sideload) fail quietly.
 */
object AppUpdate {
    fun check(ctx: Context, done: (Int) -> Unit) {
        try {
            AppUpdateManagerFactory.create(ctx.applicationContext).appUpdateInfo
                .addOnSuccessListener { info ->
                    val avail = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    done(if (avail && info.availableVersionCode() > BuildConfig.VERSION_CODE) info.availableVersionCode() else 0)
                }
                .addOnFailureListener { done(0) }
        } catch (_: Exception) {
            done(0)
        }
    }

    fun openStore(ctx: Context) {
        val id = ctx.packageName
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
                    .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }
}
