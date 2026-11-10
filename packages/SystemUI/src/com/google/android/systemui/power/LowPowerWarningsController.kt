package com.google.android.systemui.power

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.internal.logging.UiEventLogger
import com.android.systemui.power.BatteryWarningEvents.LowBatteryWarningEvent
import com.android.systemui.res.R
import com.android.systemui.util.NotificationChannels
import com.android.systemui.util.settings.GlobalSettings
import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType
import java.text.NumberFormat
import java.util.concurrent.Executor

class LowPowerWarningsController(
    val context: Context,
    val executor: Executor,
    val globalSettings: GlobalSettings,
    val uiEventLogger: UiEventLogger,
    val severeLowBatteryNotification: SevereLowBatteryNotification,
) {
    val lowBatteryNotification = LowBatteryNotification(context)
    val extremeLowNotification = ExtremeLowBatteryNotification(context, uiEventLogger)
    val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)

    @JvmField var prevBatteryEventTypes: List<BatteryEventType> = emptyList()
    @JvmField var prevBatteryLevel: Int? = null
    @JvmField var prevPowerSaveEnabledAsync: Boolean? = null

    @JvmField var lowBatterySectionEntered: Boolean = false
    @JvmField var lowBatteryNotificationCancelled: Boolean = false
    @JvmField var severeLowBatterySectionEntered: Boolean = false
    @JvmField var severeLowBatteryNotificationCancelled: Boolean = false
    @JvmField var extremeLowBatterySectionEntered: Boolean = false
    @JvmField var lowBatteryNotificationAlertedForSevereLowBattery: Boolean = false

    init {
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION,
            1,
        )
        Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.LOW_POWER_WARNING_ACKNOWLEDGED,
            1,
        )
    }

    fun cancelNotification() {
        if (lowBatterySectionEntered) {
            Log.d(TAG, "cancelNotification->lowBatterySection")
            lowBatteryNotification.mNotificationManager.cancelAsUser(
                "low_battery",
                3,
                UserHandle.ALL,
            )
            lowBatteryNotificationCancelled = true
        }
        if (severeLowBatterySectionEntered) {
            Log.d(TAG, "cancelNotification->severeLowBatterySection")
            severeLowBatteryNotification.cancel()
            severeLowBatteryNotificationCancelled = true
        }
        if (extremeLowBatterySectionEntered) {
            Log.d(TAG, "cancelNotification->extremeLowBatterySection")
            extremeLowNotification.mNotificationManager.cancelAsUser(
                "low_battery",
                R.string.extreme_low_battery_notification_title,
                UserHandle.ALL,
            )
        }
    }

    fun isScheduledByPercentage(): Boolean {
        return globalSettings.getInt("automatic_power_save_mode", 0) == 0 &&
            globalSettings.getInt("low_power_trigger_level", 0) > 0
    }

    fun onBatteryEventUpdate(batteryLevel: Int, eventList: List<BatteryEventType>) {
        prevBatteryLevel = batteryLevel
        prevBatteryEventTypes = eventList

        if (
            (lowBatterySectionEntered ||
                lowBatteryNotificationCancelled ||
                severeLowBatterySectionEntered ||
                severeLowBatteryNotificationCancelled) && batteryLevel >= 30
        ) {
            Log.d(TAG, "reset section guard for low/severe low. batteryLevel: $batteryLevel")
            lowBatterySectionEntered = false
            lowBatteryNotificationCancelled = false
            severeLowBatterySectionEntered = false
            severeLowBatteryNotificationCancelled = false
            lowBatteryNotificationAlertedForSevereLowBattery = false
        }

        if (extremeLowBatterySectionEntered && batteryLevel >= 4) {
            Log.d(TAG, "reset section guard for extreme low. batteryLevel: $batteryLevel")
            extremeLowBatterySectionEntered = false
            extremeLowNotification.mNotificationManager.cancelAsUser(
                "low_battery",
                R.string.extreme_low_battery_notification_title,
                UserHandle.ALL,
            )
        }

        if (eventList.isEmpty()) {
            return
        }

        if (eventList.contains(BatteryEventType.LOW_BATTERY)) {
            onLowBatteryEvent(batteryLevel, false)
            return
        }

        if (!eventList.contains(BatteryEventType.SEVERE_LOW_BATTERY)) {
            if (eventList.contains(BatteryEventType.EXTREME_LOW_BATTERY)) {
                if (globalSettings.getInt("extreme_low_power_mode_reminder_enabled", 1) == 0) {
                    Log.d(TAG, "onExtremeLowBatteryEvent: reminder is disable")
                    return
                }
                if (extremeLowBatterySectionEntered) {
                    return
                }
                extremeLowBatterySectionEntered = true
                lowBatteryNotification.mNotificationManager.cancelAsUser(
                    "low_battery",
                    3,
                    UserHandle.ALL,
                )
                lowBatteryNotificationCancelled = true
                severeLowBatteryNotification.cancel()
                severeLowBatteryNotificationCancelled = true

                val title = context.getString(R.string.extreme_low_battery_notification_title)
                val text = context.getString(R.string.extreme_low_battery_notification_text)
                val builder =
                    NotificationCompat.Builder(context, NotificationChannels.BATTERY).apply {
                        setSmallIcon(R.drawable.ic_battery_extreme_low)
                        setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        setContentTitle(title)
                        setContentText(text)
                        setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    }
                PowerUtils.overrideNotificationAppName(context, builder)
                extremeLowNotification.mNotificationManager.notifyAsUser(
                    "low_battery",
                    R.string.extreme_low_battery_notification_title,
                    builder.build(),
                    UserHandle.ALL,
                )
                extremeLowNotification.mUiEventLogger?.log(
                    BatteryMetricEvent.EXTREME_LOW_BATTERY_NOTIFICATION
                )
            }
            return
        }

        if (!context.resources.getBoolean(R.bool.config_show_extreme_battery_saver_reminder)) {
            onLowBatteryEvent(batteryLevel, true)
            return
        }
        if (severeLowBatteryNotificationCancelled) {
            Log.d(TAG, "notification has been canceled, skip showing notification")
            return
        }
        if (globalSettings.getInt("low_power_mode_reminder_enabled", 1) == 0) {
            Log.d(TAG, "battery saver reminder has been disabled, skip showing notification")
            return
        }
        if (PowerUtils.isFlipendoEnabled(context.contentResolver)) {
            Log.d(TAG, "EBS has been enabled, skip showing notification")
            return
        }

        val subsequentEvent: Boolean
        if (severeLowBatterySectionEntered) {
            subsequentEvent = true
        } else {
            severeLowBatterySectionEntered = true
            lowBatteryNotification.mNotificationManager.cancelAsUser(
                "low_battery",
                3,
                UserHandle.ALL,
            )
            lowBatteryNotificationCancelled = true
            subsequentEvent = false
        }

        val isScheduled =
            isScheduledByPercentage() || (powerManager != null && powerManager.isPowerSaveMode)
        Log.d(
            TAG,
            "show() batteryLevel:$batteryLevel, scheduled:$isScheduled, subsequenceEvent:$subsequentEvent",
        )

        val title =
            context.getString(
                R.string.severe_battery_notification_title,
                NumberFormat.getPercentInstance().format(batteryLevel.toDouble() * 0.01),
            )
        val text =
            context.getString(
                if (isScheduled) R.string.severe_battery_notification_switch_text
                else R.string.severe_battery_notification_text
            )
        val bundle =
            Bundle(1).apply {
                putString(
                    "extra_severe_low_battery_notification",
                    if (isScheduled) "low_battery_notification_switch_to_ebs"
                    else "low_battery_notification_turn_on_ebs",
                )
            }
        val deleteIntent =
            PowerUtils.createPendingIntent(context, "PNW.dismissSevereLowBatteryWarning", bundle)

        val builder =
            NotificationCompat.Builder(context, NotificationChannels.BATTERY).apply {
                setSmallIcon(R.drawable.ic_power_saver)
                setContentTitle(title)
                setContentText(text)
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
                setDeleteIntent(deleteIntent)
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setLocalOnly(true)

                if (severeLowBatteryNotification.keyguardManager.isDeviceLocked) {
                    setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent("android.settings.BATTERY_SAVER_SETTINGS").apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            },
                            PendingIntent.FLAG_IMMUTABLE,
                        )
                    )
                } else {
                    val actionText =
                        if (isScheduled) {
                            context.getString(R.string.severe_low_battery_dialog_switch_action_text)
                        } else {
                            context.getString(R.string.battery_saver_start_action)
                        }
                    addAction(
                        0,
                        actionText,
                        PowerUtils.createPendingIntent(
                            context,
                            "systemui.power.action.START_FLIPENDO",
                            bundle,
                        ),
                    )
                }
                if (subsequentEvent) {
                    setOnlyAlertOnce(true)
                }
            }
        PowerUtils.overrideNotificationAppName(context, builder)
        severeLowBatteryNotification.notificationManager.notifyAsUser(
            "low_battery",
            3,
            builder.build(),
            UserHandle.ALL,
        )
        severeLowBatteryNotification.logEvent(
            if (isScheduled) BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_SWITCH_TO_EBS
            else BatteryMetricEvent.SEVERE_LOW_BATTERY_NOTIFICATION_TURN_ON_EBS
        )
    }

    fun onLowBatteryEvent(batteryLevel: Int, isSevere: Boolean) {
        if (lowBatteryNotificationCancelled) {
            Log.d(TAG, "not showing notification -> notificationCanceled: true")
            return
        }
        if (globalSettings.getInt("low_power_mode_reminder_enabled", 1) == 0) {
            Log.d(TAG, "not showing notification -> isBatterySaverReminderDisabled: true")
            return
        }
        if (isScheduledByPercentage()) {
            Log.d(TAG, "not showing notification -> isScheduledByPercentage: true")
            return
        }
        if (powerManager != null && powerManager.isPowerSaveMode) {
            Log.d(TAG, "not showing notification -> isPowerSaveMode: true")
            return
        }

        var alert = false
        if (lowBatterySectionEntered) {
            alert = false
        } else {
            lowBatterySectionEntered = true
            uiEventLogger.log(LowBatteryWarningEvent.LOW_BATTERY_NOTIFICATION)
            alert = true
        }

        if (!lowBatteryNotificationAlertedForSevereLowBattery && isSevere) {
            lowBatteryNotificationAlertedForSevereLowBattery = true
            alert = true
        }

        val isFlipendoAggressive = PowerUtils.isFlipendoSelected(context.contentResolver)
        val title =
            context.getString(
                R.string.low_battery_notification_title,
                NumberFormat.getPercentInstance().format(batteryLevel.toDouble() * 0.01),
            )
        val text =
            context.getString(
                if (isFlipendoAggressive) R.string.low_battery_notification_text_ebs
                else R.string.low_battery_notification_text
            )

        val builder =
            NotificationCompat.Builder(context, NotificationChannels.BATTERY).apply {
                setSmallIcon(R.drawable.ic_power_saver)
                setContentTitle(title)
                setContentText(text)
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
                setOnlyAlertOnce(!alert)
                setDeleteIntent(
                    PowerUtils.createPendingIntent(context, "PNW.dismissedWarning", null)
                )
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setLocalOnly(true)

                if (
                    isFlipendoAggressive && lowBatteryNotification.mKeyguardManager.isDeviceLocked
                ) {
                    setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent("android.settings.BATTERY_SAVER_SETTINGS").apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            },
                            PendingIntent.FLAG_IMMUTABLE,
                        )
                    )
                } else {
                    addAction(
                        0,
                        context.getString(R.string.battery_saver_start_action),
                        PowerUtils.createPendingIntent(context, "PNW.startSaver", null),
                    )
                }
            }
        PowerUtils.overrideNotificationAppName(context, builder)
        lowBatteryNotification.mNotificationManager.notifyAsUser(
            "low_battery",
            3,
            builder.build(),
            UserHandle.ALL,
        )
    }

    fun dispatchIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        executor.execute {
            when (action) {
                Intent.ACTION_POWER_CONNECTED -> cancelNotification()
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val isPowerSave = powerManager != null && powerManager.isPowerSaveMode
                    if (prevPowerSaveEnabledAsync != isPowerSave) {
                        uiEventLogger.log(
                            if (isPowerSave) BatteryMetricEvent.BATTERY_SAVER_ENABLED
                            else BatteryMetricEvent.BATTERY_SAVER_DISABLED
                        )
                        prevPowerSaveEnabledAsync = isPowerSave
                    }
                }
                "com.android.settingslib.fuelgauge.ACTION_SAVER_STATE_MANUAL_UPDATE" -> {
                    val enabled =
                        intent.getBooleanExtra("extra_power_save_mode_manual_enabled", false)
                    val reason =
                        intent.getIntExtra("extra_power_save_mode_manual_enabled_reason", 0)
                    uiEventLogger.logWithPosition(
                        if (enabled) BatteryMetricEvent.BATTERY_SAVER_ENABLED_REASON
                        else BatteryMetricEvent.BATTERY_SAVER_DISABLED_REASON,
                        0,
                        null,
                        reason,
                    )
                }
            }
        }
    }

    private companion object {
        private const val TAG = "LowPowerWarningsController"
    }
}
