package com.google.android.systemui.power;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

public final class PowerUtils {

    private static final String TAG = "PowerUtils";

    private static final String FLIPENDO_AUTHORITY = "com.google.android.flipendo.api";

    private PowerUtils() {
    }

    public static PendingIntent createPendingIntent(Context context, String action) {
        final Intent intent = new Intent(action)
                .setPackage(context.getPackageName())
                .setFlags(Intent.FLAG_RECEIVER_FOREGROUND
                        | Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        return PendingIntent.getBroadcastAsUser(context, 0 /* requestCode */, intent,
                PendingIntent.FLAG_IMMUTABLE, UserHandle.CURRENT);
    }

    public static void overrideNotificationAppName(Context context, Notification.Builder builder) {
        final Bundle extras = new Bundle(1);
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                context.getString(com.android.internal.R.string.android_system_label));
        builder.addExtras(extras);
    }

    // Flipendo (Extreme Battery Saver) is a separate, optional app: its provider may be missing,
    // disabled or throw, and none of that may take SystemUI down.

    public static boolean isFlipendoEnabled(ContentResolver resolver) {
        try {
            final Bundle state = resolver.call(FLIPENDO_AUTHORITY, "get_flipendo_state",
                    null /* arg */, Bundle.EMPTY);
            return state != null && state.getBoolean("flipendo_state", false);
        } catch (RuntimeException e) {
            Log.e(TAG, "isFlipendoEnabled() failed", e);
            return false;
        }
    }

    public static boolean isFlipendoAggressive(ContentResolver resolver) {
        try {
            final Bundle state = resolver.call(FLIPENDO_AUTHORITY, "get_flipendo_state",
                    null /* arg */, null /* extras */);
            if (state == null) {
                Log.w(TAG, "get_flipendo_state returned null");
                return false;
            }
            return state.getBoolean("is_flipendo_aggressive", false);
        } catch (RuntimeException e) {
            Log.e(TAG, "isFlipendoAggressive() failed", e);
            return false;
        }
    }

    public static void enableFlipendo(ContentResolver resolver) {
        try {
            resolver.call(FLIPENDO_AUTHORITY, "force_enable_flipendo_method", null /* arg */,
                    null /* extras */);
        } catch (RuntimeException e) {
            Log.e(TAG, "enableFlipendo() failed", e);
        }
    }
}
