package com.google.android.systemui.power;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

public enum BatteryMetricEvent implements UiEventLogger.UiEventEnum {
    @UiEvent(doc = "Extreme low battery notification displayed")
    EXTREME_LOW_BATTERY_NOTIFICATION(1351),

    @UiEvent(doc = "Battery saver enabled")
    BATTERY_SAVER_ENABLED(1359),

    @UiEvent(doc = "Battery saver enabled reason")
    BATTERY_SAVER_ENABLED_REASON(1360),

    @UiEvent(doc = "Battery saver disabled")
    BATTERY_SAVER_DISABLED(1372),

    @UiEvent(doc = "Battery saver disabled reason")
    BATTERY_SAVER_DISABLED_REASON(1373),

    @UiEvent(doc = "Severe low battery notification turn on extreme battery saver displayed")
    SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS(1834),

    @UiEvent(doc = "Severe low battery notification switch to extreme battery saver displayed")
    SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS(1835),

    @UiEvent(doc = "Severe low battery notification turn on EBS positive button clicked")
    SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS_CLICK_TURN_ON(1836),

    @UiEvent(doc = "Severe low battery notification switch to EBS positive button clicked")
    SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS_CLICK_SWITCH(1837),

    @UiEvent(doc = "Severe low battery notification turn on EBS dismissed")
    SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS_DISMISS(1838),

    @UiEvent(doc = "Severe low battery notification switch to EBS dismissed")
    SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS_DISMISS(1839);

    private final int mId;

    BatteryMetricEvent(int id) {
        mId = id;
    }

    @Override
    public int getId() {
        return mId;
    }
}
