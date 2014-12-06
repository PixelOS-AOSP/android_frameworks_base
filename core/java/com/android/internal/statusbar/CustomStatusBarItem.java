/*
 * SPDX-FileCopyrightText: 2018 The LineageOS project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.statusbar;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;

import java.util.ArrayList;

public class CustomStatusBarItem {
    public interface Manager {
        public void addDarkReceiver(DarkReceiver darkReceiver);
        public void addVisibilityReceiver(VisibilityReceiver visibilityReceiver);
    }

    public interface DarkReceiver {
        public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint);
        public void setFillColors(int darkColor, int lightColor);
    }

    public interface VisibilityReceiver {
        public void onVisibilityChanged(boolean isVisible);
    }

    // Locate parent LineageStatusBarItem.Manager
    public static Manager findManager(View v) {
        ViewParent parent = v.getParent();
        if (parent == null) {
            return null;
        } else if (parent instanceof Manager) {
            return (Manager) parent;
        } else if (parent instanceof View) {
            return findManager((View) parent);
        } else {
            return null;
        }
    }
}
