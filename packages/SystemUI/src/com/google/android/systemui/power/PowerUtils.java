package com.google.android.systemui.power;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public final class PowerUtils {

    private static final String TAG = "PowerUtils";

    private PowerUtils() {}

    public static boolean isFlipendoEnabled(ContentResolver contentResolver) {
        try {
            Bundle bundle =
                    contentResolver.call(
                            "com.google.android.flipendo.api",
                            "get_flipendo_state",
                            (String) null,
                            Bundle.EMPTY);
            return bundle != null && bundle.getBoolean("flipendo_state", false);
        } catch (Exception e) {
            Log.e(TAG, "isFlipendoEnabled() failed", e);
            return false;
        }
    }

    public static void enableFlipendo(Context context) {
        try {
            context.getContentResolver()
                    .call(
                            "com.google.android.flipendo.api",
                            "force_enable_flipendo_method",
                            (String) null,
                            (Bundle) null);
        } catch (Exception e) {
            Log.e(TAG, "enableFlipendo() failed", e);
        }
    }

    public static boolean isFlipendoSelected(ContentResolver contentResolver) {
        try {
            Bundle bundle =
                    contentResolver.call(
                            "com.google.android.flipendo.api",
                            "get_flipendo_state",
                            (String) null,
                            Bundle.EMPTY);
            return bundle != null && bundle.getBoolean("is_flipendo_aggressive", false);
        } catch (Exception e) {
            Log.e(TAG, "isFlipendoSelected() failed", e);
            return false;
        }
    }

    public static PendingIntent createPendingIntent(Context context, String action, Bundle bundle) {
        Intent intent =
                new Intent(action)
                        .setPackage(context.getPackageName())
                        .setFlags(
                                Intent.FLAG_RECEIVER_FOREGROUND
                                        | Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        if (bundle != null) {
            intent.putExtras(bundle);
        }
        return PendingIntent.getBroadcastAsUser(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
                        | (bundle != null ? PendingIntent.FLAG_UPDATE_CURRENT : 0),
                UserHandle.CURRENT);
    }

    public static void overrideNotificationAppName(
            Context context, NotificationCompat.Builder notificationCompatBuilder) {
        Bundle bundle = new Bundle(1);
        bundle.putString(
                Notification.EXTRA_SUBSTITUTE_APP_NAME,
                context.getString(com.android.internal.R.string.android_system_label));
        notificationCompatBuilder.addExtras(bundle);
    }

    public static void applyExtremeSaverMode(Context context) {
        try {
            Bundle bundle = new Bundle(1);
            bundle.putInt("update_flipendo_mode", 1);
            context.getContentResolver()
                    .call(
                            "com.google.android.flipendo.api",
                            "update_flipendo_mode_method",
                            (String) null,
                            bundle);
        } catch (Exception e) {
            Log.e(TAG, "applyExtremeSaverMode() failed", e);
        }
    }
}
