package com.google.android.systemui.power;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;

public class LowBatteryNotification {
    final Context mContext;
    final KeyguardManager mKeyguardManager;
    final NotificationManager mNotificationManager;

    public LowBatteryNotification(Context context) {
        mContext = context;
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);
    }
}
