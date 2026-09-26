package com.google.android.systemui.power;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.power.BatteryWarningEvents;
import com.android.systemui.res.R;
import com.android.systemui.util.NotificationChannels;
import com.android.systemui.util.settings.GlobalSettings;

import java.io.PrintWriter;
import java.text.NumberFormat;

/**
 * Shows the tiered low battery notifications: low, severe (suggests Extreme Battery Saver) and
 * extreme (the device is about to shut down). Each notification is shown once per discharge
 * section, and the sections are reset once the battery recovers.
 *
 * <p>Must only be used from the SystemUI background thread.
 */
public class LowPowerWarningsController {

    private static final String TAG = "LowPowerWarningsController";

    static final String ACTION_START_SAVER = "PNW.startSaver";
    static final String ACTION_DISMISSED_WARNING = "PNW.dismissedWarning";
    static final String ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING =
            "PNW.dismissSevereLowBatteryWarning";
    static final String ACTION_START_FLIPENDO = "systemui.power.action.START_FLIPENDO";

    private static final String TAG_BATTERY = "low_battery";
    // The low and severe notifications share an ID, so escalating replaces the notification.
    private static final int ID_LOW_BATTERY = SystemMessage.NOTE_POWER_LOW;
    private static final int ID_EXTREME_LOW_BATTERY =
            R.string.extreme_low_battery_notification_title;

    private static final int LOW_BATTERY_LEVEL = 20;
    private static final int SEVERE_LOW_BATTERY_LEVEL = 10;
    private static final int EXTREME_LOW_BATTERY_LEVEL = 3;
    private static final int SECTION_RESET_LEVEL = 30;

    enum BatteryEvent {
        NONE,
        LOW_BATTERY,
        SEVERE_LOW_BATTERY,
        EXTREME_LOW_BATTERY,
    }

    private final Context mContext;
    private final GlobalSettings mGlobalSettings;
    private final UiEventLogger mUiEventLogger;
    private final NotificationManager mNotificationManager;
    private final KeyguardManager mKeyguardManager;
    private final PowerManager mPowerManager;

    private Integer mPrevBatteryLevel;
    private BatteryEvent mPrevBatteryEvent = BatteryEvent.NONE;
    private boolean mLowBatterySectionEntered;
    private boolean mLowBatteryNotificationCancelled;
    private boolean mSevereLowBatterySectionEntered;
    private boolean mSevereLowBatteryNotificationCancelled;
    private boolean mExtremeLowBatterySectionEntered;
    private boolean mLowBatteryNotificationAlertedForSevereLowBattery;

    public LowPowerWarningsController(Context context, GlobalSettings globalSettings,
            UiEventLogger uiEventLogger) {
        mContext = context;
        mGlobalSettings = globalSettings;
        mUiEventLogger = uiEventLogger;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
    }

    public void onBatteryChanged(int batteryLevel, boolean plugged) {
        final BatteryEvent event;
        if (plugged) {
            event = BatteryEvent.NONE;
        } else if (batteryLevel <= EXTREME_LOW_BATTERY_LEVEL) {
            event = BatteryEvent.EXTREME_LOW_BATTERY;
        } else if (batteryLevel <= SEVERE_LOW_BATTERY_LEVEL) {
            event = BatteryEvent.SEVERE_LOW_BATTERY;
        } else if (batteryLevel <= LOW_BATTERY_LEVEL) {
            event = BatteryEvent.LOW_BATTERY;
        } else {
            event = BatteryEvent.NONE;
        }
        onBatteryEventUpdate(batteryLevel, event);
    }

    public void onUserSwitched() {
        if (mPrevBatteryLevel != null) {
            onBatteryEventUpdate(mPrevBatteryLevel, mPrevBatteryEvent);
        }
    }

    public void cancelNotification() {
        if (mLowBatterySectionEntered) {
            Log.d(TAG, "cancelNotification->lowBatterySection");
            mNotificationManager.cancelAsUser(TAG_BATTERY, ID_LOW_BATTERY, UserHandle.ALL);
            mLowBatteryNotificationCancelled = true;
        }
        if (mSevereLowBatterySectionEntered) {
            Log.d(TAG, "cancelNotification->severeLowBatterySection");
            mNotificationManager.cancelAsUser(TAG_BATTERY, ID_LOW_BATTERY, UserHandle.ALL);
            mSevereLowBatteryNotificationCancelled = true;
        }
        if (mExtremeLowBatterySectionEntered) {
            Log.d(TAG, "cancelNotification->extremeLowBatterySection");
            mNotificationManager.cancelAsUser(TAG_BATTERY, ID_EXTREME_LOW_BATTERY,
                    UserHandle.ALL);
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("\tdump LowPowerWarningsController states");
        pw.println("\t\tprevBatteryLevel: " + mPrevBatteryLevel);
        pw.println("\t\tprevBatteryEventType: " + mPrevBatteryEvent);
        pw.println("\t\tisBatterySaverReminderDisabled: " + !isReminderEnabled());
        pw.println("\t\tisScheduledByPercentage: " + isScheduledByPercentage());
        pw.println("\t\tlowBatteryNotificationCancelled: " + mLowBatteryNotificationCancelled);
        pw.println("\t\tsevereLowBatteryNotificationCancelled: "
                + mSevereLowBatteryNotificationCancelled);
    }

    private void onBatteryEventUpdate(int batteryLevel, BatteryEvent event) {
        mPrevBatteryLevel = batteryLevel;
        mPrevBatteryEvent = event;

        if ((mLowBatterySectionEntered || mLowBatteryNotificationCancelled
                || mSevereLowBatterySectionEntered || mSevereLowBatteryNotificationCancelled)
                && batteryLevel >= SECTION_RESET_LEVEL) {
            Log.d(TAG, "reset section guard for low/severe low. batteryLevel:" + batteryLevel);
            mLowBatterySectionEntered = false;
            mLowBatteryNotificationCancelled = false;
            mSevereLowBatterySectionEntered = false;
            mSevereLowBatteryNotificationCancelled = false;
            mLowBatteryNotificationAlertedForSevereLowBattery = false;
        }
        if (mExtremeLowBatterySectionEntered && batteryLevel > EXTREME_LOW_BATTERY_LEVEL) {
            Log.d(TAG, "reset section guard for extreme low. batteryLevel:" + batteryLevel);
            mExtremeLowBatterySectionEntered = false;
            mNotificationManager.cancelAsUser(TAG_BATTERY, ID_EXTREME_LOW_BATTERY,
                    UserHandle.ALL);
        }

        switch (event) {
            case LOW_BATTERY:
                onLowBatteryEvent(batteryLevel, false /* isSevere */);
                break;
            case SEVERE_LOW_BATTERY:
                onSevereLowBatteryEvent(batteryLevel);
                break;
            case EXTREME_LOW_BATTERY:
                onExtremeLowBatteryEvent();
                break;
            case NONE:
                break;
        }
    }

    private void onLowBatteryEvent(int batteryLevel, boolean isSevere) {
        if (mLowBatteryNotificationCancelled) {
            Log.d(TAG, "not showing notification -> notificationCanceled: true");
            return;
        }
        if (!isReminderEnabled()) {
            Log.d(TAG, "not showing notification -> isBatterySaverReminderDisabled: true");
            return;
        }
        if (isScheduledByPercentage()) {
            Log.d(TAG, "not showing notification -> isScheduledByPercentage: true");
            return;
        }
        if (mPowerManager.isPowerSaveMode()) {
            Log.d(TAG, "not showing notification -> isPowerSaveMode: true");
            return;
        }

        boolean alert = false;
        if (!mLowBatterySectionEntered) {
            mLowBatterySectionEntered = true;
            mUiEventLogger.log(
                    BatteryWarningEvents.LowBatteryWarningEvent.LOW_BATTERY_NOTIFICATION);
            alert = true;
        }
        if (isSevere && !mLowBatteryNotificationAlertedForSevereLowBattery) {
            mLowBatteryNotificationAlertedForSevereLowBattery = true;
            alert = true;
        }

        final boolean flipendoAggressive =
                PowerUtils.isFlipendoAggressive(mContext.getContentResolver());
        final String title = mContext.getString(R.string.low_battery_notification_title,
                formatPercentage(batteryLevel));
        final String text = mContext.getString(flipendoAggressive
                ? R.string.low_battery_notification_text_ebs
                : R.string.low_battery_notification_text);
        final Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.BATTERY)
                        .setSmallIcon(R.drawable.ic_power_saver)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setOnlyAlertOnce(!alert)
                        .setDeleteIntent(PowerUtils.createPendingIntent(mContext,
                                ACTION_DISMISSED_WARNING))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setLocalOnly(true);
        if (flipendoAggressive && mKeyguardManager.isDeviceLocked()) {
            builder.setContentIntent(createBatterySaverSettingsPendingIntent());
        } else {
            builder.addAction(0 /* icon */,
                    mContext.getString(R.string.battery_saver_start_action),
                    PowerUtils.createPendingIntent(mContext, ACTION_START_SAVER));
        }
        PowerUtils.overrideNotificationAppName(mContext, builder);
        mNotificationManager.notifyAsUser(TAG_BATTERY, ID_LOW_BATTERY, builder.build(),
                UserHandle.ALL);
    }

    private void onSevereLowBatteryEvent(int batteryLevel) {
        if (!mContext.getResources().getBoolean(
                R.bool.config_show_extreme_battery_saver_reminder)) {
            onLowBatteryEvent(batteryLevel, true /* isSevere */);
            return;
        }
        if (mSevereLowBatteryNotificationCancelled) {
            Log.d(TAG, "notification has been canceled, skip showing notification");
            return;
        }
        if (!isReminderEnabled()) {
            Log.d(TAG, "battery saver reminder has been disabled, skip showing notification");
            return;
        }
        if (PowerUtils.isFlipendoEnabled(mContext.getContentResolver())) {
            Log.d(TAG, "EBS has been enabled, skip showing notification");
            return;
        }

        final boolean subsequentEvent = mSevereLowBatterySectionEntered;
        if (!subsequentEvent) {
            mSevereLowBatterySectionEntered = true;
            mNotificationManager.cancelAsUser(TAG_BATTERY, ID_LOW_BATTERY, UserHandle.ALL);
            mLowBatteryNotificationCancelled = true;
        }

        final boolean scheduled = isScheduledByPercentage() || mPowerManager.isPowerSaveMode();
        Log.d(TAG, "show severe low battery notification. batteryLevel:" + batteryLevel
                + ", scheduled:" + scheduled + ", subsequentEvent:" + subsequentEvent);
        final String title = mContext.getString(R.string.severe_battery_notification_title,
                formatPercentage(batteryLevel));
        final String text = mContext.getString(scheduled
                ? R.string.severe_battery_notification_switch_text
                : R.string.severe_battery_notification_text);
        final Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.BATTERY)
                        .setSmallIcon(R.drawable.ic_power_saver)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setDeleteIntent(PowerUtils.createPendingIntent(mContext,
                                ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setLocalOnly(true)
                        .setOnlyAlertOnce(subsequentEvent);
        if (mKeyguardManager.isDeviceLocked()) {
            builder.setContentIntent(createBatterySaverSettingsPendingIntent());
        } else {
            builder.addAction(0 /* icon */,
                    mContext.getString(scheduled
                            ? R.string.severe_low_battery_dialog_switch_action_text
                            : R.string.battery_saver_start_action),
                    PowerUtils.createPendingIntent(mContext, ACTION_START_FLIPENDO));
        }
        PowerUtils.overrideNotificationAppName(mContext, builder);
        mNotificationManager.notifyAsUser(TAG_BATTERY, ID_LOW_BATTERY, builder.build(),
                UserHandle.ALL);
    }

    private void onExtremeLowBatteryEvent() {
        if (mGlobalSettings.getInt("extreme_low_power_mode_reminder_enabled", 1) == 0) {
            Log.d(TAG, "onExtremeLowBatteryEvent: reminder is disabled");
            return;
        }
        if (mExtremeLowBatterySectionEntered) {
            return;
        }
        mExtremeLowBatterySectionEntered = true;
        mNotificationManager.cancelAsUser(TAG_BATTERY, ID_LOW_BATTERY, UserHandle.ALL);
        mLowBatteryNotificationCancelled = true;
        mSevereLowBatteryNotificationCancelled = true;

        final String title = mContext.getString(R.string.extreme_low_battery_notification_title);
        final String text = mContext.getString(R.string.extreme_low_battery_notification_text);
        final Notification.Builder builder =
                new Notification.Builder(mContext, NotificationChannels.BATTERY)
                        .setSmallIcon(R.drawable.ic_battery_extreme_low)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setVisibility(Notification.VISIBILITY_PUBLIC);
        PowerUtils.overrideNotificationAppName(mContext, builder);
        mNotificationManager.notifyAsUser(TAG_BATTERY, ID_EXTREME_LOW_BATTERY, builder.build(),
                UserHandle.ALL);
    }

    private boolean isReminderEnabled() {
        return mGlobalSettings.getInt(Settings.Global.LOW_POWER_MODE_REMINDER_ENABLED, 1) != 0;
    }

    private boolean isScheduledByPercentage() {
        return mGlobalSettings.getInt(Settings.Global.AUTOMATIC_POWER_SAVE_MODE, 0)
                == PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE
                && mGlobalSettings.getInt(Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0) > 0;
    }

    private PendingIntent createBatterySaverSettingsPendingIntent() {
        final Intent intent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(mContext, 0 /* requestCode */, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String formatPercentage(int batteryLevel) {
        return NumberFormat.getPercentInstance().format(batteryLevel / 100.0);
    }
}
