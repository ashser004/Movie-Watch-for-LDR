package com.ash.kandaloo.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors battery percentage and charging state via Intent.ACTION_BATTERY_CHANGED.
 * Debounces low battery alerts so callbacks are triggered only once when dropping below
 * 20% and 10% thresholds while discharging.
 */
class BatteryMonitor(private val context: Context) {

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private var hasAlertedLow = false
    private var hasAlertedCritical = false

    private var onLowBatteryCallback: ((Int) -> Unit)? = null
    private var onCriticalBatteryCallback: ((Int) -> Unit)? = null

    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) {
                ((level.toFloat() / scale.toFloat()) * 100).toInt()
            } else {
                -1
            }

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            if (pct >= 0) {
                _batteryLevel.value = pct
            }
            _isCharging.value = charging

            // Reset debounce flags when phone recharges above 25%
            if (pct >= 25) {
                hasAlertedLow = false
                hasAlertedCritical = false
            }

            // Only alert when actively discharging
            if (!charging && pct in 1..99) {
                if (pct < 10 && !hasAlertedCritical) {
                    hasAlertedCritical = true
                    hasAlertedLow = true // Prevent firing low after critical
                    onCriticalBatteryCallback?.invoke(pct)
                } else if (pct < 20 && !hasAlertedLow && !hasAlertedCritical) {
                    hasAlertedLow = true
                    onLowBatteryCallback?.invoke(pct)
                }
            }
        }
    }

    fun start(onLowBattery: (Int) -> Unit, onCriticalBattery: (Int) -> Unit) {
        if (isRegistered) return
        this.onLowBatteryCallback = onLowBattery
        this.onCriticalBatteryCallback = onCriticalBattery

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        isRegistered = true
    }

    fun stop() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        isRegistered = false
        onLowBatteryCallback = null
        onCriticalBatteryCallback = null
    }
}
