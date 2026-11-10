package com.google.android.systemui.power.batteryevent.common.module

import android.content.Intent
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType

abstract class BaseBatteryEventModule {
    @JvmField var lastValidation: Boolean = false

    open val intentActions: List<String> = listOf(Intent.ACTION_BATTERY_CHANGED)

    abstract val moduleType: BatteryEventType

    abstract fun validate(batteryLevel: Int, plugged: Int): Boolean
}
