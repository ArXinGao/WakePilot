package com.wakepilot.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wakepilot.app.data.AppRole
import com.wakepilot.app.data.ConfigStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = ConfigStore(context)
        if (store.role == AppRole.SENTINEL && store.getOrInitSentinel().serviceEnabled) {
            SentinelForegroundService.start(context)
        }
    }
}
