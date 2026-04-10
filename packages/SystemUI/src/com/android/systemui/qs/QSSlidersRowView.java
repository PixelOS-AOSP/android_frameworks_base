/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;

import com.android.systemui.res.R;
import com.android.systemui.settings.brightness.BrightnessSliderView;
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel;

import java.util.Collections;

/** Container that hosts the QS brightness slider and the opt-in QS media volume slider. */
public class QSSlidersRowView extends LinearLayout {

    @Nullable private View mBrightnessView;
    @Nullable private ComposeView mVolumeView;
    @Nullable private AudioStreamSliderViewModel.Factory mVolumeViewModelFactory;

    private final Rect mSystemGestureExclusionRect = new Rect();

    private float mSliderScaleY = 1f;
    private int mSplitGap;
    private boolean mVolumeContentBound;

    public QSSlidersRowView(Context context) {
        this(context, null);
    }

    public QSSlidersRowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        updateResources();
    }

    public void setBrightnessView(@NonNull View view) {
        if (mBrightnessView != null) {
            removeView(mBrightnessView);
        }
        mBrightnessView = view;
        addView(view, 0, createChildLayoutParams(/* isStart= */ true));
        applySliderScale();
    }

    public void setVolumeView(
            @NonNull ComposeView view,
            @NonNull AudioStreamSliderViewModel.Factory volumeViewModelFactory
    ) {
        if (mVolumeView != null) {
            removeView(mVolumeView);
        }
        mVolumeView = view;
        mVolumeViewModelFactory = volumeViewModelFactory;
        mVolumeContentBound = false;
        addView(view, createChildLayoutParams(/* isStart= */ false));
        bindVolumeSlider();
        applySliderScale();
    }

    public void updateResources() {
        mSplitGap = getResources().getDimensionPixelSize(R.dimen.qs_split_sliders_gap);
        if (mBrightnessView != null) {
            mBrightnessView.setLayoutParams(createChildLayoutParams(/* isStart= */ true));
        }
        if (mVolumeView != null) {
            mVolumeView.setLayoutParams(createChildLayoutParams(/* isStart= */ false));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        bindVolumeSlider();
    }

    @Keep
    public void setSliderScaleY(float scale) {
        if (mSliderScaleY == scale) {
            return;
        }
        mSliderScaleY = scale;
        applySliderScale();
    }

    private void bindVolumeSlider() {
        if (mVolumeContentBound || mVolumeView == null || mVolumeViewModelFactory == null
                || !isAttachedToWindow()) {
            return;
        }
        QSMediaSliderComposableProvider.setContent(mVolumeView, mVolumeViewModelFactory);
        mVolumeContentBound = true;
    }

    private LayoutParams createChildLayoutParams(boolean isStart) {
        LayoutParams layoutParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        if (isStart) {
            layoutParams.setMarginEnd(mSplitGap / 2);
        } else {
            layoutParams.setMarginStart(mSplitGap / 2);
        }
        return layoutParams;
    }

    private void applySliderScale() {
        if (mBrightnessView instanceof BrightnessSliderView) {
            ((BrightnessSliderView) mBrightnessView).setSliderScaleY(mSliderScaleY);
        } else if (mBrightnessView != null) {
            mBrightnessView.setPivotY(mBrightnessView.getHeight() / 2f);
            mBrightnessView.setScaleY(mSliderScaleY);
        }

        if (mVolumeView != null) {
            mVolumeView.setPivotY(mVolumeView.getHeight() / 2f);
            mVolumeView.setScaleY(mSliderScaleY);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        applySliderScale();
        int horizontalMargin =
                getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        mSystemGestureExclusionRect.set(
                -horizontalMargin,
                0,
                right - left + horizontalMargin,
                bottom - top);
        setSystemGestureExclusionRects(Collections.singletonList(mSystemGestureExclusionRect));
    }
}
