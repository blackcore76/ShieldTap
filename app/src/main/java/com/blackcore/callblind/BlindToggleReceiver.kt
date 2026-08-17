package com.blackcore.callblind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BlindToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BlindAccessibilityService.instance?.toggleBlind()
    }
}
