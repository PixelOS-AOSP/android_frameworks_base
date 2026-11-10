package com.google.android.systemui.power;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import javax.inject.Inject;

public class BatterySaverConfirmationDialog {

    private final Context mApplicationContext;
    private final ActivityStarter mActivityStarter;
    private final UiEventLogger mUiEventLogger;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final SystemUIDialog.Factory mSystemUIDialogFactory;
    private final ShadeDialogContextInteractor mShadeDialogContextInteractor;

    private SystemUIDialog mConfirmationDialog;
    private boolean mIsStandardMode = true;

    @Inject
    public BatterySaverConfirmationDialog(
            Context context,
            ActivityStarter activityStarter,
            UiEventLogger uiEventLogger,
            DialogTransitionAnimator dialogTransitionAnimator,
            SystemUIDialog.Factory systemUIDialogFactory,
            ShadeDialogContextInteractor shadeDialogContextInteractor) {
        mApplicationContext = context;
        mActivityStarter = activityStarter;
        mUiEventLogger = uiEventLogger;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mSystemUIDialogFactory = systemUIDialogFactory;
        mShadeDialogContextInteractor = shadeDialogContextInteractor;
    }

    public void show(@Nullable Expandable expandable) {
        if (mConfirmationDialog != null && mConfirmationDialog.isShowing()) {
            return;
        }
        if (mConfirmationDialog != null) {
            mConfirmationDialog.show();
            return;
        }

        Context context = mShadeDialogContextInteractor.getContext();
        View view =
                LayoutInflater.from(context)
                        .inflate(R.layout.battery_saver_confirmation_content, null);

        RadioButton standardButton = view.findViewById(R.id.standard_button);
        RadioButton extremeButton = view.findViewById(R.id.extreme_button);
        View standardOptionLayout = view.findViewById(R.id.standard_option_layout);
        View extremeOptionLayout = view.findViewById(R.id.extreme_option_layout);
        Button setupButton = view.findViewById(R.id.setup_button);

        mIsStandardMode = true;

        standardOptionLayout.setOnClickListener(
                v -> {
                    mIsStandardMode = true;
                    standardButton.setChecked(true);
                    extremeButton.setChecked(false);
                });

        extremeOptionLayout.setOnClickListener(
                v -> {
                    mIsStandardMode = false;
                    standardButton.setChecked(false);
                    extremeButton.setChecked(true);
                });

        setupButton.setOnClickListener(
                v -> {
                    log(BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG_SETUP);
                    ActivityTransitionAnimator.Controller controller =
                            mDialogTransitionAnimator.createActivityTransitionController(
                                    setupButton);
                    if (controller == null && mConfirmationDialog != null) {
                        mConfirmationDialog.dismiss();
                    }
                    mActivityStarter.startActivity(
                            new Intent("android.settings.batterysaver.flipendo.onboarding"),
                            true /* dismissShade */,
                            controller);
                });

        mConfirmationDialog = mSystemUIDialogFactory.create(context);
        mConfirmationDialog.setTitle(R.string.saver_confirmation_dialog_title);
        mConfirmationDialog.setMessage(R.string.saver_confirmation_dialog_subtitle);
        mConfirmationDialog.setView(view);
        SystemUIDialog.setShowForAllUsers(mConfirmationDialog, true);
        mConfirmationDialog.setCanceledOnTouchOutside(true);

        mConfirmationDialog.setPositiveButton(
                R.string.battery_saver_confirmation_ok,
                (dialog, which) -> {
                    log(BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG_TURN_ON);
                    dialog.dismiss();
                    AsyncTask.execute(
                            () -> {
                                if (!mIsStandardMode) {
                                    PowerUtils.applyExtremeSaverMode(mApplicationContext);
                                }
                                BatterySaverUtils.setPowerSaveMode(
                                        mApplicationContext, true, false, 1);
                                Settings.Secure.putInt(
                                        mApplicationContext.getContentResolver(),
                                        Settings.Secure.LOW_POWER_WARNING_ACKNOWLEDGED,
                                        1);
                                Settings.Secure.putInt(
                                        mApplicationContext.getContentResolver(),
                                        Settings.Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED,
                                        1);
                            });
                });

        mConfirmationDialog.setNeutralButton(
                R.string.saver_confirmation_dialog_dismiss_text,
                (dialog, which) -> {
                    log(BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG_CANCEL);
                    dialog.dismiss();
                },
                true);

        mConfirmationDialog.setOnDismissListener(
                dialog -> {
                    mConfirmationDialog = null;
                });

        if (expandable != null) {
            DialogTransitionAnimator.Controller controller =
                    expandable.dialogTransitionController(null);
            if (controller != null) {
                mDialogTransitionAnimator.show(mConfirmationDialog, controller, false);
            } else {
                mConfirmationDialog.show();
            }
        } else {
            mConfirmationDialog.show();
        }

        log(BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG);
    }

    public void log(BatteryMetricEvent batteryMetricEvent) {
        if (mUiEventLogger != null) {
            if (batteryMetricEvent == BatteryMetricEvent.SAVER_CONFIRMATION_DIALOG_TURN_ON) {
                mUiEventLogger.logWithPosition(
                        batteryMetricEvent, 0, (String) null, !mIsStandardMode ? 1 : 0);
            } else {
                mUiEventLogger.log(batteryMetricEvent);
            }
        }
    }

    @VisibleForTesting
    public Dialog getConfirmationDialog() {
        return mConfirmationDialog;
    }

    @VisibleForTesting
    public boolean isStandardMode() {
        return mIsStandardMode;
    }

    @VisibleForTesting
    public void setStandardMode(boolean isStandardMode) {
        mIsStandardMode = isStandardMode;
    }
}
