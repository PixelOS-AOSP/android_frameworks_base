package com.google.android.systemui.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.power.BatteryStateSnapshot;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.settings.GlobalSettings;

import com.google.android.systemui.power.batteryevent.aidl.BatteryEventType;
import com.google.android.systemui.power.batteryevent.common.module.BaseBatteryEventModule;
import com.google.android.systemui.power.batteryevent.common.module.ExtremeLowBatteryEventModule;
import com.google.android.systemui.power.batteryevent.common.module.LowBatteryEventModule;
import com.google.android.systemui.power.batteryevent.common.module.SevereLowBatteryEventModule;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

@SysUISingleton
public class PowerNotificationWarningsGoogleImpl extends PowerNotificationWarnings {

    private static final String TAG = "PowerNotificationWarningsGoogleImpl";

    private final Context mContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final GlobalSettings mGlobalSettings;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final Provider<SevereLowBatteryNotification> mSevereLowBatteryNotificationProvider;

    private SevereLowBatteryNotification mSevereLowBatteryNotification;
    private LowPowerWarningsController mLowPowerWarningsController;

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getAction() == null) {
                        return;
                    }
                    String action = intent.getAction();
                    Log.d(TAG, "onReceive: " + action);
                    switch (action) {
                        case "systemui.power.action.START_FLIPENDO":
                            mExecutor.execute(
                                    () -> {
                                        PowerUtils.enableFlipendo(mContext);
                                        mSevereLowBatteryNotification.logStartFlipendo(
                                                intent.getStringExtra(
                                                        "extra_severe_low_battery_notification"));
                                    });
                            return;
                        case "PNW.dismissSevereLowBatteryWarning":
                            mSevereLowBatteryNotification.dismiss(
                                    intent.getStringExtra("extra_severe_low_battery_notification"));
                            dismissLowBatteryWarning();
                            return;
                    }
                    mLowPowerWarningsController.dispatchIntent(intent);
                    if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                        handleBatteryChanged(intent);
                    }
                }
            };

    @Inject
    public PowerNotificationWarningsGoogleImpl(
            Context context,
            ActivityStarter activityStarter,
            BroadcastDispatcher broadcastDispatcher,
            BroadcastSender broadcastSender,
            UiEventLogger uiEventLogger,
            GlobalSettings globalSettings,
            Lazy<BatteryController> batteryControllerLazy,
            DialogTransitionAnimator dialogTransitionAnimator,
            UserTracker userTracker,
            SystemUIDialog.Factory systemUIDialogFactory,
            @Background Executor backgroundExecutor,
            @Main Handler mainHandler,
            Provider<SevereLowBatteryNotification> severeLowBatteryNotificationProvider) {
        super(
                context,
                activityStarter,
                broadcastSender,
                batteryControllerLazy,
                dialogTransitionAnimator,
                uiEventLogger,
                userTracker,
                systemUIDialogFactory);
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mGlobalSettings = globalSettings;
        mExecutor = backgroundExecutor;
        mHandler = mainHandler;
        mSevereLowBatteryNotificationProvider = severeLowBatteryNotificationProvider;
        mainHandler.post(() -> init(context, uiEventLogger));
    }

    private void init(Context context, UiEventLogger uiEventLogger) {
        mSevereLowBatteryNotification = mSevereLowBatteryNotificationProvider.get();
        mLowPowerWarningsController =
                new LowPowerWarningsController(
                        context,
                        mExecutor,
                        mGlobalSettings,
                        uiEventLogger,
                        mSevereLowBatteryNotification);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction("com.android.settingslib.fuelgauge.ACTION_SAVER_STATE_MANUAL_UPDATE");
        filter.addAction("systemui.power.action.START_FLIPENDO");
        filter.addAction("PNW.dismissSevereLowBatteryWarning");
        mBroadcastDispatcher.registerReceiverWithHandler(
                mBroadcastReceiver, filter, mHandler, UserHandle.ALL);
        Intent batteryChanged =
                context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryChanged != null) {
            mBroadcastReceiver.onReceive(context, batteryChanged);
        }
    }

    private void handleBatteryChanged(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int batteryLevel = BatteryStatus.getBatteryLevel(level, scale);
        updateBatteryEvent(batteryLevel, plugged);
    }

    private void updateBatteryEvent(int batteryLevel, int plugged) {
        List<BatteryEventType> events = evaluateBatteryEvents(batteryLevel, plugged);
        if (mLowPowerWarningsController != null) {
            mLowPowerWarningsController.onBatteryEventUpdate(batteryLevel, events);
        }
    }

    private final List<BaseBatteryEventModule> mBatteryEventModules =
            List.of(
                    new ExtremeLowBatteryEventModule(),
                    new SevereLowBatteryEventModule(),
                    new LowBatteryEventModule());

    private List<BatteryEventType> evaluateBatteryEvents(int batteryLevel, int plugged) {
        for (BaseBatteryEventModule module : mBatteryEventModules) {
            if (module.validate(batteryLevel, plugged)) {
                return Collections.singletonList(module.getModuleType());
            }
        }
        return Collections.emptyList();
    }

    @Override
    public void updateSnapshot(BatteryStateSnapshot snapshot) {
        super.updateSnapshot(snapshot);
        int plugged = snapshot.getPlugged() ? BatteryManager.BATTERY_PLUGGED_AC : 0;
        updateBatteryEvent(snapshot.getBatteryLevel(), plugged);
    }

    @Override
    public void showLowBatteryWarning(boolean playSound) {
        // Handled by LowPowerWarningsController
    }

    @Override
    protected void showWarningNotification() {
        // Handled by LowPowerWarningsController
    }

    @Override
    public void updateLowBatteryWarning() {
        // Handled by LowPowerWarningsController
    }

    @Override
    public void dismissLowBatteryWarning() {
        if (mLowPowerWarningsController != null) {
            mLowPowerWarningsController.cancelNotification();
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        if (mLowPowerWarningsController != null) {
            pw.println("\tdump LowPowerWarningsController states");
            pw.println("\t\tprevBatteryLevel: " + mLowPowerWarningsController.prevBatteryLevel);
            pw.println(
                    "\t\tprevBatteryEventType: "
                            + mLowPowerWarningsController.prevBatteryEventTypes);
            pw.println(
                    "\t\tisBatterySaverReminderDisabled: "
                            + (mGlobalSettings.getInt("low_power_mode_reminder_enabled", 1) == 0));
            pw.println(
                    "\t\tisScheduledByPercentage: "
                            + mLowPowerWarningsController.isScheduledByPercentage());
            pw.println(
                    "\t\tlowBatteryNotificationCancelled: "
                            + mLowPowerWarningsController.lowBatteryNotificationCancelled);
            pw.println(
                    "\t\tsevereLowBatteryNotificationCancelled: "
                            + mLowPowerWarningsController.severeLowBatteryNotificationCancelled);
        }
    }

    @Override
    public void userSwitched() {
        if (mLowPowerWarningsController != null
                && mLowPowerWarningsController.prevBatteryLevel != null) {
            mLowPowerWarningsController.onBatteryEventUpdate(
                    mLowPowerWarningsController.prevBatteryLevel,
                    mLowPowerWarningsController.prevBatteryEventTypes);
        }
    }
}
