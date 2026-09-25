/*
 * Copyright (C) 2022-2024 Paranoid Android
 *           (C) 2023 ArrowOS
 *           (C) 2023 The LibreMobileOS Foundation
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

package com.android.internal.util;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Binder;
import android.os.Environment;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sun.misc.Unsafe;

/**
 * @hide
 */
public class PropImitationHooks {

    private static final String TAG = "PropImitationHooks";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final Boolean sDisableGmsProps = SystemProperties.getBoolean(
            "persist.sys.pihooks.disable.gms_props", false);

    private static final String DATA_FILE = "gms_certified_props.json";

    // Same fields inject_s writes into Build / Build.VERSION. Short names stay
    // under the 32-character property limit so an isolated DroidGuard process
    // can read them without Settings.
    private static final String[] PUBLISHED_FIELDS = {
            "FINGERPRINT", "MANUFACTURER", "MODEL", "BRAND", "PRODUCT", "DEVICE",
            "VERSION.RELEASE", "ID", "VERSION.INCREMENTAL", "TYPE", "TAGS",
            "VERSION.SECURITY_PATCH"
    };
    private static final String[] FINGERPRINT_FIELDS = {
            "BRAND", "PRODUCT", "DEVICE", "VERSION.RELEASE", "ID",
            "VERSION.INCREMENTAL", "TYPE", "TAGS"
    };

    private static final String PACKAGE_ARCORE = "com.google.ar.core";
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";
    private static final String PACKAGE_NETFLIX = "com.netflix.mediaclient";
    private static final String PACKAGE_GPHOTOS = "com.google.android.apps.photos";

    private static final Set<String> sSkippedCertifiedProps = Set.of(
            "VERSION.DEVICE_INITIAL_SDK_INT",
            "DEVICE_INITIAL_SDK_INT",
            "VERSION.SDK_INT",
            "SDK_INT",
            "spoofBuild",
            "spoofProps",
            "spoofProvider",
            "spoofSignature",
            "spoofVendingBuild",
            "spoofVendingSdk",
            "DEBUG"
    );

    private static final String FEATURE_NEXUS_PRELOAD =
            "com.google.android.apps.photos.NEXUS_PRELOAD";

    private static final Map<String, String> sPixelOneProps = Map.of(
        "PRODUCT", "sailfish",
        "DEVICE", "sailfish",
        "MANUFACTURER", "Google",
        "BRAND", "google",
        "MODEL", "Pixel",
        "FINGERPRINT", "google/sailfish/sailfish:10/QP1A.191005.007.A3/5972272:user/release-keys"
    );

    private static final Set<String> sPixelFeatures = Set.of(
        "PIXEL_2017_EXPERIENCE",
        "PIXEL_2017_PRELOAD",
        "PIXEL_2018_EXPERIENCE",
        "PIXEL_2018_PRELOAD",
        "PIXEL_2019_EXPERIENCE",
        "PIXEL_2019_MIDYEAR_EXPERIENCE",
        "PIXEL_2019_MIDYEAR_PRELOAD",
        "PIXEL_2019_PRELOAD",
        "PIXEL_2020_EXPERIENCE",
        "PIXEL_2020_MIDYEAR_EXPERIENCE",
        "PIXEL_2021_MIDYEAR_EXPERIENCE"
    );

    private static final Set<String> sTensorFeatures = Set.of(
        "PIXEL_2021_EXPERIENCE",
        "PIXEL_2022_EXPERIENCE",
        "PIXEL_2022_MIDYEAR_EXPERIENCE",
        "PIXEL_2023_EXPERIENCE",
        "PIXEL_2023_MIDYEAR_EXPERIENCE",
        "PIXEL_2024_EXPERIENCE",
        "PIXEL_2024_MIDYEAR_EXPERIENCE",
        "PIXEL_2025_EXPERIENCE",
        "PIXEL_2025_MIDYEAR_EXPERIENCE"
    );

    private static volatile String sStockFp, sNetflixModel;

    private static volatile String sProcessName;
    private static volatile boolean sIsGms, sIsFinsky, sIsPhotos;

    private static final Field OFFSET_FIELD;
    private static final Unsafe UNSAFE;

    static {
        Unsafe unsafe = null;
        Field offsetField = null;

        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
            field.setAccessible(false);

            offsetField = Field.class.getDeclaredField("offset");
            offsetField.setAccessible(true);
        } catch (Exception e) {
            Log.e(TAG, "Unable to initialize Unsafe", e);
        }

        UNSAFE = unsafe;
        OFFSET_FIELD = offsetField;
    }

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();

        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(processName)) {
            Log.e(TAG, "Null package or process name");
            return;
        }

        final Resources res = context.getResources();
        if (res == null) {
            Log.e(TAG, "Null resources");
            return;
        }

        sStockFp = res.getString(R.string.config_stockFingerprint);
        sNetflixModel = res.getString(R.string.config_netflixSpoofModel);

        sProcessName = processName;
        sIsGms = packageName.equals(PACKAGE_GMS) && processName.equals(PROCESS_GMS_UNSTABLE);
        sIsFinsky = packageName.equals(PACKAGE_FINSKY);
        sIsPhotos = packageName.equals(PACKAGE_GPHOTOS);

        /* Set Certified Properties for GMSCore
         * Set Stock Fingerprint for ARCore
         * Set custom model for Netflix
         * Set Pixel XL for Google Photos
         */
        if (sIsGms || sIsFinsky) {
            setPlayIntegrityProps(context);
        } else if (!sStockFp.isEmpty() && packageName.equals(PACKAGE_ARCORE)) {
            dlog("Setting stock fingerprint for: " + packageName);
            setPropValue("FINGERPRINT", sStockFp);
        } else if (sIsPhotos) {
            dlog("Spoofing Pixel 1 for Google Photos");
            sPixelOneProps.forEach((PropImitationHooks::setPropValue));
        } else if (!sNetflixModel.isEmpty() && packageName.equals(PACKAGE_NETFLIX)) {
            dlog("Setting model to " + sNetflixModel + " for Netflix");
            setPropValue("MODEL", sNetflixModel);
        }
    }

    private static void setPropValue(String key, String value) {
        if (UNSAFE == null || OFFSET_FIELD == null) {
            Log.e(TAG, "Unsafe is unavailable", new IllegalStateException());
            return;
        }

        try {
            dlog("Setting prop " + key + " to " + value.toString());
            Class clazz = Build.class;
            if (key.startsWith("VERSION.")) {
                clazz = Build.VERSION.class;
                key = key.substring(8);
            }
            Field field = clazz.getDeclaredField(key);
            field.setAccessible(true);
            // Cast the value to int if it's an integer field, otherwise string.
            long offset = OFFSET_FIELD.getInt(field);
            if (field.getType().equals(Integer.TYPE)) {
                UNSAFE.putInt(field.getDeclaringClass(), offset, Integer.parseInt(value));
            } else {
                UNSAFE.putObject(field.getDeclaringClass(), offset, value);
            }
            field.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void setPlayIntegrityProps(Context context) {
        if (sDisableGmsProps) {
            dlog("GMS prop imitation is disabled by user");
            return;
        }

        final Map<String, String> certifiedProps = getCertifiedProps(context);
        if (certifiedProps.isEmpty()) {
            dlog("Certified props are not set");
            return;
        }

        dlog("Spoofing build for " + (sIsFinsky ? "Play Store" : "GMS"));
        setCertifiedProps(certifiedProps);
    }

    /**
     * Copies the active profile into properties an isolated DroidGuard process can read.
     * Called from the settings provider, which can write persist properties.
     */
    public static void publishPifProps(Context context) {
        if (context == null || sDisableGmsProps) {
            return;
        }
        Map<String, String> props = new LinkedHashMap<>();
        if (!readSettingsProfile(context, props) && props.isEmpty()) {
            readOverlayProfile(context, props);
        }
        normalizeCertifiedProps(props);
        for (String field : PUBLISHED_FIELDS) {
            String name = publishedPropName(field);
            String value = props.get(field);
            try {
                SystemProperties.set(name, value == null ? "" : value);
            } catch (Exception e) {
                Log.w(TAG, "Unable to publish " + name, e);
            }
        }
    }

    /**
     * Returns the certified profile as FIELD to value, taken from the user PIF, the fetched PIF
     * or the overlay, in that order. Empty when GMS prop imitation is disabled.
     */
    public static Map<String, String> getCertifiedProps(Context context) {
        final Map<String, String> props = new LinkedHashMap<>();
        if (sDisableGmsProps || context == null) {
            return props;
        }

        if (!Process.isIsolated()) {
            readSettingsProfile(context, props);
        }
        if (props.isEmpty() && Process.isIsolated()) {
            readPublishedProps(props);
        }
        if (props.isEmpty()) {
            dlog("Parsing props locally");
            readOverlayProfile(context, props);
        }
        normalizeCertifiedProps(props);
        return props;
    }

    private static boolean readSettingsProfile(Context context, Map<String, String> props) {
        String savedProps = null;
        try {
            savedProps = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.PIF_DATA);
            if (TextUtils.isEmpty(savedProps)) {
                savedProps = Settings.Secure.getString(context.getContentResolver(),
                        Settings.Secure.FETCHED_PIF);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Unable to read PIF settings", e);
            return false;
        }
        if (TextUtils.isEmpty(savedProps)) {
            return false;
        }
        dlog("Parsing props fetched / provided by user");
        try {
            JSONObject parsedProps = new JSONObject(savedProps);
            Iterator<String> keys = parsedProps.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                props.put(key, parsedProps.getString(key));
            }
            return true;
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON data", e);
            props.clear();
            return false;
        }
    }

    private static void readOverlayProfile(Context context, Map<String, String> props) {
        if (context.getResources() == null) {
            return;
        }
        for (String entry : context.getResources().getStringArray(
                R.array.config_certifiedBuildProperties)) {
            final String[] fieldAndProp = entry.split(":", 2);
            if (fieldAndProp.length != 2) {
                Log.e(TAG, "Invalid entry in certified props: " + entry);
                continue;
            }
            props.put(fieldAndProp[0], fieldAndProp[1]);
        }
    }

    private static void readPublishedProps(Map<String, String> props) {
        for (String field : PUBLISHED_FIELDS) {
            String value = SystemProperties.get(publishedPropName(field), "");
            if (!value.isEmpty()) {
                props.put(field, value);
            }
        }
    }

    private static String publishedPropName(String field) {
        return switch (field) {
            case "FINGERPRINT" -> "persist.sys.pif.fp";
            case "MANUFACTURER" -> "persist.sys.pif.man";
            case "MODEL" -> "persist.sys.pif.model";
            case "BRAND" -> "persist.sys.pif.brand";
            case "PRODUCT" -> "persist.sys.pif.prod";
            case "DEVICE" -> "persist.sys.pif.dev";
            case "VERSION.RELEASE" -> "persist.sys.pif.rel";
            case "ID" -> "persist.sys.pif.id";
            case "VERSION.INCREMENTAL" -> "persist.sys.pif.inc";
            case "TYPE" -> "persist.sys.pif.type";
            case "TAGS" -> "persist.sys.pif.tags";
            case "VERSION.SECURITY_PATCH" -> "persist.sys.pif.patch";
            default -> "persist.sys.pif.x";
        };
    }

    private static void normalizeCertifiedProps(Map<String, String> props) {
        moveProp(props, "RELEASE", "VERSION.RELEASE");
        moveProp(props, "INCREMENTAL", "VERSION.INCREMENTAL");
        moveProp(props, "SECURITY_PATCH", "VERSION.SECURITY_PATCH");
        String fingerprint = props.get("FINGERPRINT");
        if (TextUtils.isEmpty(fingerprint)) {
            return;
        }
        String[] parts = fingerprint.split("[/:]");
        for (int i = 0; i < FINGERPRINT_FIELDS.length && i < parts.length; i++) {
            props.put(FINGERPRINT_FIELDS[i], parts[i]);
        }
    }

    private static void moveProp(Map<String, String> props, String from, String to) {
        if (!props.containsKey(from)) {
            return;
        }
        if (!props.containsKey(to)) {
            props.put(to, props.get(from));
        }
        props.remove(from);
    }

    private static void setCertifiedProps(Map<String, String> certifiedProps) {
        certifiedProps.forEach((field, value) -> {
            if (sSkippedCertifiedProps.contains(field) || TextUtils.isEmpty(value)) {
                return;
            }
            setPropValue(field, value);
        });
    }

    private static String readFromFile(File file) {
        StringBuilder content = new StringBuilder();

        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading from file", e);
            }
        }
        return content.toString();
    }

    public static boolean shouldBypassTaskPermission(Context context) {
        if (sDisableGmsProps) {
            return false;
        }

        // GMS doesn't have MANAGE_ACTIVITY_TASKS permission
        final int callingUid = Binder.getCallingUid();
        final int gmsUid;
        try {
            gmsUid = context.getPackageManager().getApplicationInfo(PACKAGE_GMS, 0).uid;
            dlog("shouldBypassTaskPermission: gmsUid:" + gmsUid + " callingUid:" + callingUid);
        } catch (Exception e) {
            Log.e(TAG, "shouldBypassTaskPermission: unable to get gms uid", e);
            return false;
        }
        return gmsUid == callingUid;
    }

    public static boolean hasSystemFeature(String name, boolean has) {
        if (sIsPhotos) {
            if (has && (sPixelFeatures.stream().anyMatch(name::contains)
                    || sTensorFeatures.stream().anyMatch(name::contains))) {
                dlog("Blocked system feature " + name + " for Google Photos");
                has = false;
            } else if (!has && name.equalsIgnoreCase(FEATURE_NEXUS_PRELOAD)) {
                dlog("Enabled system feature " + name + " for Google Photos");
                has = true;
            }
        }
        return has;
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, "[" + sProcessName + "] " + msg);
    }
}
