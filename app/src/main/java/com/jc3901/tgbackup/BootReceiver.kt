package com.jc3901.tgbackup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts live sync after a reboot / app update if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val p = Tg.prefs(c)
        if (p.getBoolean("want_running", false) && p.getBoolean("live", true)) {
            runCatching { SyncService.start(c) }
        }
    }
}
