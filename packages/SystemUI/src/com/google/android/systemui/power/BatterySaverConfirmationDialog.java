package com.google.android.systemui.power;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;

import androidx.annotation.Nullable;

import com.android.settingslib.fuelgauge.BatterySaverLogging;
import com.android.settingslib.fuelgauge.BatterySaverUtils;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Shown when Battery Saver is turned on for the first time. Lets the user choose between
 * Standard and Extreme Battery Saver.
 */
public class BatterySaverConfirmationDialog {

    private static final String ACTION_FLIPENDO_ONBOARDING =
            "android.settings.batterysaver.flipendo.onboarding";

    private final Context mApplicationContext;
    private final ActivityStarter mActivityStarter;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final SystemUIDialog.Factory mSystemUIDialogFactory;
    private final ShadeDialogContextInteractor mShadeDialogContextInteractor;
    private final Executor mBgExecutor;

    private SystemUIDialog mConfirmationDialog;
    private boolean mIsStandardMode = true;

    @Inject
    public BatterySaverConfirmationDialog(
            Context context,
            ActivityStarter activityStarter,
            DialogTransitionAnimator dialogTransitionAnimator,
            SystemUIDialog.Factory systemUIDialogFactory,
            ShadeDialogContextInteractor shadeDialogContextInteractor,
            @Background Executor bgExecutor) {
        mApplicationContext = context;
        mActivityStarter = activityStarter;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mSystemUIDialogFactory = systemUIDialogFactory;
        mShadeDialogContextInteractor = shadeDialogContextInteractor;
        mBgExecutor = bgExecutor;
    }

    /** Shows the dialog, animating it from {@code expandable} when possible. */
    public void show(@Nullable Expandable expandable) {
        if (mConfirmationDialog != null) {
            if (!mConfirmationDialog.isShowing()) {
                mConfirmationDialog.show();
            }
            return;
        }

        final Context context = mShadeDialogContextInteractor.getContext();
        final View view = LayoutInflater.from(context)
                .inflate(R.layout.battery_saver_confirmation_content, null);
        final RadioButton standardButton = view.findViewById(R.id.standard_button);
        final RadioButton extremeButton = view.findViewById(R.id.extreme_button);
        mIsStandardMode = true;
        view.findViewById(R.id.standard_option_layout).setOnClickListener(v -> {
            mIsStandardMode = true;
            standardButton.setChecked(true);
            extremeButton.setChecked(false);
        });
        view.findViewById(R.id.extreme_option_layout).setOnClickListener(v -> {
            mIsStandardMode = false;
            standardButton.setChecked(false);
            extremeButton.setChecked(true);
        });
        final Button setupButton = view.findViewById(R.id.setup_button);
        setupButton.setOnClickListener(v -> {
            final ActivityTransitionAnimator.Controller controller =
                    mDialogTransitionAnimator.createActivityTransitionController(setupButton);
            if (controller == null) {
                mConfirmationDialog.dismiss();
            }
            mActivityStarter.startActivity(new Intent(ACTION_FLIPENDO_ONBOARDING),
                    true /* dismissShade */, controller);
        });

        final SystemUIDialog dialog = mSystemUIDialogFactory.create(context);
        dialog.setTitle(R.string.saver_confirmation_dialog_title);
        dialog.setMessage(R.string.saver_confirmation_dialog_subtitle);
        dialog.setView(view);
        dialog.setShowForAllUsers(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setPositiveButton(R.string.battery_saver_confirmation_ok, (d, which) -> {
            final boolean extremeMode = !mIsStandardMode;
            mBgExecutor.execute(() -> {
                if (extremeMode) {
                    PowerUtils.applyExtremeSaverMode(mApplicationContext.getContentResolver());
                }
                BatterySaverUtils.setPowerSaveMode(mApplicationContext, true /* enable */,
                        false /* needFirstTimeWarning */,
                        BatterySaverLogging.SAVER_ENABLED_CONFIRMATION);
            });
        });
        dialog.setNeutralButton(R.string.saver_confirmation_dialog_dismiss_text, null);
        dialog.setOnDismissListener(d -> mConfirmationDialog = null);
        mConfirmationDialog = dialog;

        final DialogTransitionAnimator.Controller controller =
                expandable != null ? expandable.dialogTransitionController(null) : null;
        if (controller != null) {
            mDialogTransitionAnimator.show(dialog, controller);
        } else {
            dialog.show();
        }
    }
}
