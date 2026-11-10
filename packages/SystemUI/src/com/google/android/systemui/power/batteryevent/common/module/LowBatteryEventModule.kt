package com.google.android.systemui.power.batteryevent.common.module

import com.android.settingslib.fuelgauge.BatteryStatus
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType

class LowBatteryEventModule : BaseBatteryEventModule() {
    override val moduleType: BatteryEventType = BatteryEventType.LOW_BATTERY

    override fun validate(batteryLevel: Int, plugged: Int): Boolean {
        lastValidation =
            batteryLevel <= 20 &&
                !BatteryStatus.isPluggedIn(plugged) &&
                batteryLevel > 10 &&
                batteryLevel > 3
        return lastValidation
    }
}
