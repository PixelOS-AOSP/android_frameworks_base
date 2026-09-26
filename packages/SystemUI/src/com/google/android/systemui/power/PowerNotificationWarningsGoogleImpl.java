package com.google.android.systemui.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.power.PowerNotificationWarnings;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import dagger.Lazy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@SysUISingleton
public class PowerNotificationWarningsGoogleImpl extends PowerNotificationWarnings {

    private static final String TAG = "PowerNotificationWarningsGoogleImpl";

    private static final String ACTION_SHOW_START_SAVER_CONFIRMATION =
            "PNW.startSaverConfirmation";
    private static final String ACTION_FLIPENDO_SHOW_START_SAVER_CONFIRMATION =
            "FLIPENDO.startSaverConfirmation";

    private final Context mContext;
    private final Lazy<BatteryController> mBatteryControllerLazy;
    private final Lazy<BatterySaverConfirmationDialog> mBatterySaverConfirmationDialogLazy;
    private final Executor mMainExecutor;
    private final Executor mBgExecutor;
    private final LowPowerWarningsController mLowPowerWarningsController;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                return;
            }
            Log.d(TAG, "onReceive: " + action);
            switch (action) {
                case Intent.ACTION_BATTERY_CHANGED:
                    onBatteryChanged(intent);
                    break;
                case Intent.ACTION_POWER_CONNECTED:
                    mLowPowerWarningsController.cancelNotification();
                    break;
                case LowPowerWarningsController.ACTION_START_FLIPENDO:
                    PowerUtils.enableFlipendo(mContext.getContentResolver());
                    break;
                case LowPowerWarningsController.ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING:
                    mLowPowerWarningsController.cancelNotification();
                    break;
                case ACTION_SHOW_START_SAVER_CONFIRMATION:
                case ACTION_FLIPENDO_SHOW_START_SAVER_CONFIRMATION:
                    mLowPowerWarningsController.cancelNotification();
                    if (mContext.getResources().getBoolean(
                            R.bool.config_extra_battery_saver_confirmation)) {
                        mMainExecutor.execute(() -> showBatterySaverConfirmation());
                    }
                    break;
            }
        }
    };

    @Inject
    public PowerNotificationWarningsGoogleImpl(
            Context context,
            ActivityStarter activityStarter,
            BroadcastSender broadcastSender,
            Lazy<BatteryController> batteryControllerLazy,
            DialogTransitionAnimator dialogTransitionAnimator,
            UiEventLogger uiEventLogger,
            UserTracker userTracker,
            SystemUIDialog.Factory systemUIDialogFactory,
            BroadcastDispatcher broadcastDispatcher,
            GlobalSettings globalSettings,
            SecureSettings secureSettings,
            Lazy<BatterySaverConfirmationDialog> batterySaverConfirmationDialogLazy,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor) {
        super(context, activityStarter, broadcastSender, batteryControllerLazy,
                dialogTransitionAnimator, uiEventLogger, userTracker, systemUIDialogFactory);
        mContext = context;
        mBatteryControllerLazy = batteryControllerLazy;
        mBatterySaverConfirmationDialogLazy = batterySaverConfirmationDialogLazy;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;
        mLowPowerWarningsController =
                new LowPowerWarningsController(context, globalSettings, uiEventLogger);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(LowPowerWarningsController.ACTION_START_FLIPENDO);
        filter.addAction(LowPowerWarningsController.ACTION_DISMISS_SEVERE_LOW_BATTERY_WARNING);
        filter.addAction(ACTION_SHOW_START_SAVER_CONFIRMATION);
        filter.addAction(ACTION_FLIPENDO_SHOW_START_SAVER_CONFIRMATION);
        broadcastDispatcher.registerReceiver(mBroadcastReceiver, filter, bgExecutor);

        bgExecutor.execute(() -> {
            // The tiered low battery notifications replace the auto saver suggestion, and
            // EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED alone gates the first-time Battery Saver
            // confirmation.
            secureSettings.putInt(Settings.Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, 1);
            secureSettings.putInt(Settings.Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1);
            // The sticky broadcast is not replayed if another receiver already registered for it.
            final Intent batteryIntent = context.registerReceiver(null /* receiver */,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                onBatteryChanged(batteryIntent);
            }
        });
    }

    private void onBatteryChanged(Intent intent) {
        mLowPowerWarningsController.onBatteryChanged(BatteryStatus.getBatteryLevel(intent),
                BatteryStatus.isPluggedIn(intent));
    }

    private void showBatterySaverConfirmation() {
        final WeakReference<Expandable> ref =
                mBatteryControllerLazy.get().getLastPowerSaverStartExpandable();
        mBatterySaverConfirmationDialogLazy.get().show(ref != null ? ref.get() : null);
    }

    @Override
    public void showLowBatteryWarning(boolean playSound) {
        // Low battery notifications are driven by LowPowerWarningsController instead.
    }

    @Override
    public void updateLowBatteryWarning() {
        // Low battery notifications are driven by LowPowerWarningsController instead.
    }

    @Override
    public void dismissLowBatteryWarning() {
        mBgExecutor.execute(mLowPowerWarningsController::cancelNotification);
    }

    @Override
    public void userSwitched() {
        mBgExecutor.execute(mLowPowerWarningsController::onUserSwitched);
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        mLowPowerWarningsController.dump(pw);
    }
}
