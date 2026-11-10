package com.google.android.systemui.power;

import android.app.NotificationManager;
import android.content.Context;

import com.android.internal.logging.UiEventLogger;

public class ExtremeLowBatteryNotification {
    final Context mContext;
    final NotificationManager mNotificationManager;
    final UiEventLogger mUiEventLogger;

    public ExtremeLowBatteryNotification(Context context, UiEventLogger uiEventLogger) {
        mContext = context;
        mUiEventLogger = uiEventLogger;
        mNotificationManager = context.getSystemService(NotificationManager.class);
    }
}
