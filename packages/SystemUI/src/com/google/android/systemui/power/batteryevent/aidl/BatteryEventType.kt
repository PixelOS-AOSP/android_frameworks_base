package com.google.android.systemui.power.batteryevent.aidl

import android.os.Parcel
import android.os.Parcelable

enum class BatteryEventType(val typeName: String) : Parcelable {
    UNKNOWN("unknown"),
    FULL_CHARGED("full_charged"),
    BATTERY_IN_USE("battery_in_use"),
    LOW_BATTERY("low_battery"),
    EXTREME_LOW_BATTERY("extreme_low_battery"),
    SEVERE_LOW_BATTERY("severe_low_battery"),
    REGULAR_CHARGING("regular_charging"),
    FAST_CHARGING("fast_charging"),
    SLOW_CHARGING("slow_charging"),
    DISCHARGING("discharging"),
    NOT_CHARGING("not_charging"),
    TEMP_DEFEND_BATTERY("temp_defend_battery"),
    DWELL_DEFEND_BATTERY("dwell_defend_battery"),
    DOCK_DEFEND_BATTERY("dock_defend_battery"),
    WIRED_INCOMPATIBLE_CHARGING("wired_incompatible_charging"),
    OVERHEATED_BATTERY("overheated_battery"),
    COLD_BATTERY("cold_battery"),
    CHARGING_LIMIT("charging_limit"),
    SCREEN_ON("screen_on"),
    AIRPLANE_ON("airplane_on"),
    DND_ON("dnd_on");

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(typeName)
    }

    companion object {
        @JvmField
        val CREATOR =
            object : Parcelable.Creator<BatteryEventType> {
                override fun createFromParcel(parcel: Parcel): BatteryEventType {
                    val name = parcel.readString() ?: return UNKNOWN
                    return values().firstOrNull { it.typeName == name } ?: UNKNOWN
                }

                override fun newArray(size: Int): Array<BatteryEventType?> = arrayOfNulls(size)
            }
    }
}
