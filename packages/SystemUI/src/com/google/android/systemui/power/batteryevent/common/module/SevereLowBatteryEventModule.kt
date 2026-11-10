package com.google.android.systemui.power.batteryevent.common.module

import com.android.settingslib.fuelgauge.BatteryStatus
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType

class SevereLowBatteryEventModule : BaseBatteryEventModule() {
    override val moduleType: BatteryEventType = BatteryEventType.SEVERE_LOW_BATTERY

    override fun validate(batteryLevel: Int, plugged: Int): Boolean {
        lastValidation =
            batteryLevel <= 10 && batteryLevel > 3 && !BatteryStatus.isPluggedIn(plugged)
        return lastValidation
    }
}
