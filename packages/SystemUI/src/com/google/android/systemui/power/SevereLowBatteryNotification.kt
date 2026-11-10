package com.google.android.systemui.power

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.UserHandle
import android.util.Log
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class SevereLowBatteryNotification
@Inject
constructor(
    val context: Context,
    val uiEventLogger: UiEventLogger,
    val keyguardManager: KeyguardManager,
) {
    val notificationManager: NotificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)
    }

    fun logEvent(event: BatteryMetricEvent) {
        uiEventLogger.log(event)
        Log.d(TAG, "logEvent $event")
    }

    fun cancel() {
        Log.d(TAG, "cancel()")
        notificationManager.cancelAsUser("low_battery", 3, UserHandle.ALL)
    }

    fun dismiss(source: String?) {
        Log.d(TAG, "dismiss(), source: $source")
        when (source) {
            "low_battery_notification_turn_on_ebs" ->
                logEvent(BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS_DISMISS)
            "low_battery_notification_switch_to_ebs" ->
                logEvent(BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS_DISMISS)
        }
        cancel()
    }

    fun logStartFlipendo(source: String?) {
        Log.d(TAG, "logStartFlipendo() from $source")
        when (source) {
            "low_battery_notification_turn_on_ebs" ->
                logEvent(
                    BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS_CLICK_TURN_ON
                )
            "low_battery_notification_switch_to_ebs" ->
                logEvent(
                    BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS_CLICK_SWITCH
                )
        }
    }

    private companion object {
        private const val TAG = "SevereLowBatteryNotification"
    }
}
