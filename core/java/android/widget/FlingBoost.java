/*
 * Copyright (C) 2026 The PixelOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package android.widget;

import android.content.Context;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;

/**
 * Tells the device's fling boost service that an {@link OverScroller} fling
 * started or ended. The service decides whether and how to boost; this side
 * only reports the fling and its duration. Devices opt in with
 * config_flingBoost; on the others OverScroller never calls in here.
 *
 * Calls are oneway and cheap. The service lookup is cached, and retried with
 * a growing delay while the service is missing.
 */
final class FlingBoost {
    private static final String SERVICE = "flingboost";
    private static final String DESCRIPTOR = "android.widget.IFlingBoost";

    private static final int TRANSACTION_FLING_START = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_FLING_END = IBinder.FIRST_CALL_TRANSACTION + 1;

    // Don't report a fling that ends within this long of the one already
    // reported, as happens when flywheel adds to a running fling.
    private static final long MIN_EXTENSION_MS = 50;

    private static final long RETRY_MIN_MS = 1000;
    private static final long RETRY_MAX_MS = 60000;

    private static final Object sLock = new Object();
    private static IBinder sService;
    private static long sNextLookup;
    private static long sRetryDelay = RETRY_MIN_MS;
    private static long sBoostEnd;

    // 0: not read yet, 1: enabled, -1: disabled
    private static volatile int sEnabled;

    private FlingBoost() {}

    /** Reads config_flingBoost once per process; the context includes device overlays. */
    static boolean isEnabled(Context context) {
        int enabled = sEnabled;
        if (enabled == 0) {
            boolean value = false;
            try {
                value = context != null && context.getResources().getBoolean(
                        com.android.internal.R.bool.config_flingBoost);
            } catch (Resources.NotFoundException e) {
                // Leave it off.
            }
            enabled = value ? 1 : -1;
            sEnabled = enabled;
        }
        return enabled > 0;
    }

    static void onFlingStart(int durationMs, boolean vertical) {
        if (durationMs <= 0) return;
        final long now = SystemClock.uptimeMillis();
        synchronized (sLock) {
            if (now + durationMs < sBoostEnd + MIN_EXTENSION_MS) return;
            if (send(TRANSACTION_FLING_START, durationMs, vertical ? 1 : 2, now)) {
                sBoostEnd = now + durationMs;
            }
        }
    }

    static void onFlingEnd() {
        final long now = SystemClock.uptimeMillis();
        synchronized (sLock) {
            if (sBoostEnd <= now) return;
            sBoostEnd = 0;
            send(TRANSACTION_FLING_END, 0, 0, now);
        }
    }

    private static boolean send(int code, int durationMs, int type, long now) {
        final IBinder service = getServiceLocked(now);
        if (service == null) return false;
        final Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(durationMs);
            data.writeInt(type);
            service.transact(code, data, null, IBinder.FLAG_ONEWAY);
            return true;
        } catch (RemoteException e) {
            sService = null;
            return false;
        } finally {
            data.recycle();
        }
    }

    private static IBinder getServiceLocked(long now) {
        if (sService != null && sService.isBinderAlive()) return sService;
        sService = null;
        if (now < sNextLookup) return null;
        try {
            sService = ServiceManager.checkService(SERVICE);
        } catch (SecurityException e) {
            sService = null;
        }
        if (sService == null) {
            sNextLookup = now + sRetryDelay;
            sRetryDelay = Math.min(sRetryDelay * 2, RETRY_MAX_MS);
        } else {
            sRetryDelay = RETRY_MIN_MS;
        }
        return sService;
    }
}
