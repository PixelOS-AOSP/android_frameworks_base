package com.google.android.systemui.power.batteryevent.common.module

import com.android.settingslib.fuelgauge.BatteryStatus
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType

class ExtremeLowBatteryEventModule : BaseBatteryEventModule() {
    override val moduleType: BatteryEventType = BatteryEventType.EXTREME_LOW_BATTERY

    override fun validate(batteryLevel: Int, plugged: Int): Boolean {
        lastValidation = batteryLevel <= 3 && !BatteryStatus.isPluggedIn(plugged)
        return lastValidation
    }
}
