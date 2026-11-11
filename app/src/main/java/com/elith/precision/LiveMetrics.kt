package com.elith.precision

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import android.view.WindowManager

class LiveMetrics(private val context: Context) {
    private val traffic=TrafficMeter()
    private var sampledAt=Long.MIN_VALUE
    var network: TrafficRate?=null; private set
    var refreshHz: Float?=null; private set
    fun sample(networkEnabled: Boolean) {
        val now=SystemClock.elapsedRealtime()
        if(sampledAt!=Long.MIN_VALUE && now-sampledAt<1000) return
        sampledAt=now
        @Suppress("DEPRECATION")
        val display=context.getSystemService(WindowManager::class.java).defaultDisplay
        refreshHz=display?.refreshRate?.takeIf { it>0 }
        if(networkEnabled) {
            network=try { traffic.sample(TrafficStats.getTotalRxBytes(),TrafficStats.getTotalTxBytes(),now) } catch(_:SecurityException) { null }
        }
    }
}
