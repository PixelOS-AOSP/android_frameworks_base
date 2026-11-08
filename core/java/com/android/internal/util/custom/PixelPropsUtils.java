/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.custom;

import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.Application;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.custom.PixelDeviceRepository;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import sun.misc.Unsafe;

/**
 * @hide
 */
public final class PixelPropsUtils {

    private static final String PACKAGE_ARCORE = "com.google.ar.core";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PACKAGE_PHOTOS = "com.google.android.apps.photos";
    private static final String PACKAGE_SI = "com.google.android.settings.intelligence";

    private static final String PI_PP_TARGETS_KEY = "pi_pp_targets";
    private static final String PI_PP_MODEL_KEY   = "pi_pp_model";

    private static final String TAG = PixelPropsUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String sDeviceFingerprint =
            SystemProperties.get("ro.product.fingerprint", Build.FINGERPRINT);

    private static final Map<String, Object> sPixelXLProps = Map.of(
            "BRAND", "google",
            "MANUFACTURER", "Google",
            "DEVICE", "marlin",
            "PRODUCT", "marlin",
            "HARDWARE", "marlin",
            "ID", "QP1A.191005.007.A3",
            "MODEL", "Pixel XL",
            "FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys"
    );

    private static final Map<String, Object> propsToChangeGeneric;
    private static final Map<String, ArrayList<String>> propsToKeep;

    // Pixel device codename ("mustang" = Pixel 10 Pro XL) used as the GMS spoof target
    // for Mosey / Quick Share — Phenotype gates key on the exact model.
    private static final String MOSEY_PIXEL_CODENAME = "mustang";

    private static volatile Set<String> mLauncherPkgs;
    private static volatile Set<String> mExemptedUidPkgs;

    // Mainline (first-party SoC) devices: Pixel 8 and above
    private static final Pattern MAINLINE_PIXEL_PATTERN =
            Pattern.compile("^Pixel (([89]|[1-9][0-9])([a-zA-Z].*)?)$");

    // DEFAULT_PP_TARGETS moved to PixelDeviceRepository.DEFAULT_PP_TARGETS — single
    // source of truth shared with PixelPropsSettings.

    private static final Set<String> customGoogleCameraPackages = new HashSet<>(Arrays.asList(
            "com.google.android.MTCL83",
            "com.google.android.UltraCVM",
            "com.google.android.apps.cameralite"
    ));

    private static volatile boolean sIsExcluded;
    private static volatile String sProcessName;

    private static final boolean sIsMainlineDevice = detectMainlinePixelDevice();

    private static volatile boolean sPhotosSpoofEnabled = true;
    private static volatile boolean sPixelPropsSpoofEnabled = true;
    private static volatile boolean sInitialized = false;
    private static volatile Set<String> sPpTargets = null;
    private static volatile String sPpModel = null;

    private static final Field OFFSET_FIELD;
    private static final Unsafe UNSAFE;

    static {
        propsToKeep = new HashMap<>();
        propsToKeep.put(PACKAGE_SI, new ArrayList<>(Collections.singletonList("FINGERPRINT")));
        propsToChangeGeneric = new HashMap<>();
        propsToChangeGeneric.put("TYPE", "user");
        propsToChangeGeneric.put("TAGS", "release-keys");

        Unsafe unsafe = null;
        Field offsetField = null;
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
            field.setAccessible(false);

            offsetField = Field.class.getDeclaredField("offset");
            offsetField.setAccessible(true);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to initialize Unsafe, falling back to reflection", t);
        }
        UNSAFE = unsafe;
        OFFSET_FIELD = offsetField;
    }

    public static void init(Context context) {
        if (sInitialized || Process.isIsolated() || context == null) return;
        sInitialized = true;
        registerSpoofSettingsObserver(context);
    }

    private static void registerSpoofSettingsObserver(Context context) {
        final ContentResolver cr = context.getContentResolver();
        final Runnable refresh = () -> {
            try {
                sPhotosSpoofEnabled = Settings.Secure.getInt(
                        cr, Settings.Secure.PI_PHOTOS_SPOOF, 1) == 1;
                sPixelPropsSpoofEnabled = Settings.Secure.getInt(
                        cr, Settings.Secure.PI_PP_SPOOF, 1) == 1;
                final String rawTargets = Settings.Secure.getString(cr, PI_PP_TARGETS_KEY);
                sPpTargets = (rawTargets == null || rawTargets.isEmpty())
                        ? new ArraySet<>(PixelDeviceRepository.DEFAULT_PP_TARGETS)
                        : new ArraySet<>(Arrays.asList(rawTargets.split(",")));
                sPpModel = Settings.Secure.getString(cr, PI_PP_MODEL_KEY);
            } catch (Throwable t) {
                // Settings provider not ready yet; cache stays at safe defaults
            }
        };
        try {
            final android.database.ContentObserver observer =
                    new android.database.ContentObserver(null) {
                @Override
                public void onChange(boolean selfChange) { refresh.run(); }
            };
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PI_PHOTOS_SPOOF), false, observer);
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.PI_PP_SPOOF), false, observer);
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(PI_PP_TARGETS_KEY), false, observer);
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(PI_PP_MODEL_KEY), false, observer);
        } catch (Throwable t) {
            // Observer registration failed; cached defaults remain
        }
        refresh.run();
    }

    private static boolean isGoogleCameraPackage(String packageName) {
        return packageName.contains("GoogleCamera")
                || customGoogleCameraPackages.contains(packageName);
    }

    private static void applyAppSpecificProps(String packageName) {
        if (packageName.equals(PACKAGE_PHOTOS)) {
            if (sPhotosSpoofEnabled) {
                sPixelXLProps.forEach(PixelPropsUtils::setPropValue);
            }
            return;
        }
    }

    public static void setProps(Context context) {
        if (Process.isIsolated()) {
            if (DEBUG) Log.d(TAG, "Skipping setProps in isolated process");
            return;
        }

        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();

        if (packageName == null || processName == null || packageName.isEmpty()) {
            return;
        }

        sProcessName = processName;

        init(context);

        propsToChangeGeneric.forEach((k, v) -> setPropValue(k, v));

        sIsExcluded = isGoogleCameraPackage(packageName);

        final Set<String> ppTargets = sPpTargets != null
                ? sPpTargets : PixelDeviceRepository.DEFAULT_PP_TARGETS;
        if (!sIsExcluded
                && ppTargets.contains(packageName)
                && !sIsMainlineDevice
                && sPixelPropsSpoofEnabled) {
            applyPixelProfile(context, packageName, ppTargets);
        }

        // Set proper indexing fingerprint
        if (packageName.equals(PACKAGE_SI)) {
            setPropValue("FINGERPRINT", String.valueOf(Build.TIME));
            return;
        }
        if (packageName.equals(PACKAGE_ARCORE)) {
            setPropValue("FINGERPRINT", sDeviceFingerprint);
            return;
        }
        applyAppSpecificProps(packageName);
    }

    private static void applyPixelProfile(Context context, String packageName,
            Set<String> ppTargets) {
        final boolean isTablet = isDeviceTablet(context);
        final String codename;
        if (packageName.equals(PACKAGE_GMS) && ppTargets.contains("com.google.android.mosey")) {
            // Quick Share Phenotype gates key on the exact model
            codename = MOSEY_PIXEL_CODENAME;
        } else if (sPpModel != null && !sPpModel.isEmpty()) {
            codename = sPpModel;
        } else if (isTablet) {
            codename = "tangorpro";
        } else {
            codename = PixelDeviceRepository.getDefaultPhoneCodename();
        }

        final PixelDeviceRepository.PixelProfile profile =
                PixelDeviceRepository.getStableProfileByCodename(context, codename, isTablet);
        if (profile == null) {
            return;
        }

        final Map<String, Object> props = new HashMap<>();
        props.put("BRAND", "google");
        props.put("MANUFACTURER", "Google");
        props.put("BOARD", profile.getDevice());
        props.put("DEVICE", profile.getDevice());
        props.put("PRODUCT", profile.getProduct());
        props.put("HARDWARE", profile.getDevice());
        props.put("MODEL", profile.getModel());
        props.put("ID", profile.getBuildId());
        props.put("FINGERPRINT", profile.getFingerprint());

        final ArrayList<String> keep = propsToKeep.get(packageName);
        dlog("Defining props for: " + packageName);
        for (Map.Entry<String, Object> prop : props.entrySet()) {
            if (keep != null && keep.contains(prop.getKey())) {
                dlog("Not defining " + prop.getKey() + " prop for: " + packageName);
                continue;
            }
            setPropValue(prop.getKey(), prop.getValue());
        }
    }

    private static boolean isDeviceTablet(Context context) {
        if (context == null) {
            return false;
        }
        Configuration config = context.getResources().getConfiguration();
        if (config == null) return false;
        return config.smallestScreenWidthDp >= 600;
    }

    public static void setPropValue(String key, Object value) {
        Field field = null;
        try {
            field = getBuildClassField(key);
            if (field == null) {
                Log.e(TAG, "Field " + key + " not found in Build or Build.VERSION classes");
                return;
            }
            field.setAccessible(true);

            Object coerced;
            if (field.getType() == int.class) {
                coerced = (value instanceof String)
                        ? Integer.parseInt((String) value)
                        : (Integer) value;
            } else if (field.getType() == long.class) {
                coerced = (value instanceof String)
                        ? Long.parseLong((String) value)
                        : (Long) value;
            } else {
                coerced = value.toString();
            }

            if (UNSAFE != null && OFFSET_FIELD != null) {
                try {
                    int offset = OFFSET_FIELD.getInt(field);
                    if (field.getType() == int.class) {
                        UNSAFE.putInt(field.getDeclaringClass(), offset, (Integer) coerced);
                    } else if (field.getType() == long.class) {
                        UNSAFE.putLong(field.getDeclaringClass(), offset, (Long) coerced);
                    } else {
                        UNSAFE.putObject(field.getDeclaringClass(), offset, coerced);
                    }
                    dlog("Set prop " + key + " to " + value + " via Unsafe");
                    return;
                } catch (Throwable t) {
                    dlog("Unsafe set failed for " + key + ", falling back to reflection: " + t.getMessage());
                }
            }

            field.set(null, coerced);
            dlog("Set prop " + key + " to " + value + " via reflection");
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        } finally {
            if (field != null) {
                try {
                    field.setAccessible(false);
                } catch (Exception ignored) {}
            }
        }
    }

    private static Field getBuildClassField(String key) throws NoSuchFieldException {
        try {
            Field field = Build.class.getDeclaredField(key);
            dlog("Field " + key + " found in Build.class");
            return field;
        } catch (NoSuchFieldException e) {
            Field field = Build.VERSION.class.getDeclaredField(key);
            dlog("Field " + key + " found in Build.VERSION.class");
            return field;
        }
    }

    private static String[] getStringArrayResSafely(int resId) {
        String[] strArr = Resources.getSystem().getStringArray(resId);
        if (strArr == null) strArr = new String[0];
        return strArr;
    }

    private static Set<String> getLauncherPkgs() {
        synchronized (PixelPropsUtils.class) {
            if (mLauncherPkgs == null || mLauncherPkgs.isEmpty()) {
                mLauncherPkgs =
                        new HashSet<>(
                                Arrays.asList(
                                        getStringArrayResSafely(R.array.config_launcherPackages)));
            }
            return mLauncherPkgs;
        }
    }

    private static Set<String> getExemptedUidPkgs() {
        synchronized (PixelPropsUtils.class) {
            if (mExemptedUidPkgs == null || mExemptedUidPkgs.isEmpty()) {
                mExemptedUidPkgs = new HashSet<>();
                mExemptedUidPkgs.add(PACKAGE_GMS);
                mExemptedUidPkgs.addAll(getLauncherPkgs());
            }
            return mExemptedUidPkgs;
        }
    }

    public static boolean isSystemLauncher(int callingUid) {
        return isSystemPackageUid(getLauncherPkgs(), callingUid);
    }

    public static boolean shouldBypassTaskPermission(int callingUid) {
        return isSystemPackageUid(getExemptedUidPkgs(), callingUid);
    }

    public static boolean shouldBypassManageActivityTaskPermission(Context context) {
        return shouldBypassTaskPermission(Binder.getCallingUid());
    }

    public static boolean shouldBypassMonitorInputPermission(Context context) {
        return shouldBypassTaskPermission(Binder.getCallingUid());
    }

    private static boolean isSystemPackageUid(Set<String> pkgs, int callingUid) {
        for (String pkg : pkgs) {
            try {
                ApplicationInfo appInfo =
                        ActivityThread.getPackageManager()
                                .getApplicationInfo(pkg, 0, UserHandle.getUserId(callingUid));
                if (appInfo != null && appInfo.uid == callingUid && appInfo.isSystemApp()) {
                    return true;
                }
            } catch (Exception e) {
                dlog("isSystemPackageUid: failed to get appInfo for " + pkg + ": " + e.getMessage());
            }
        }
        return false;
    }

    private static boolean detectMainlinePixelDevice() {
        String model = SystemProperties.get("ro.product.model", "").trim();
        boolean isPixelSoC = "Google".equalsIgnoreCase(
                SystemProperties.get("ro.soc.manufacturer"));
        return isPixelSoC && MAINLINE_PIXEL_PATTERN.matcher(model).matches();
    }

    public static boolean isMainlinePixelDevice() {
        return sIsMainlineDevice;
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, "[" + sProcessName + "] " + msg);
    }
}
