package com.cid0980.connectwin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        BridgeState.init(context)
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (BridgeState.isAutoBoot(context)) {
                    BridgeState.log("Auto-starting BridgeService after boot")
                    BridgeService.start(context)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (BridgeState.wasRunning(context)) {
                    BridgeState.log("Auto-restarting BridgeService after APK update")
                    BridgeService.start(context)
                }
            }
        }
    }
}
