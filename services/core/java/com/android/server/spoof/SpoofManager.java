/*
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

package com.android.server.spoof;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.pif.PlayIntegritySpoofService;
import android.security.trickystore.TrickyStoreService;
import android.util.Log;

import com.android.internal.util.custom.PixelDeviceRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;

public class SpoofManager {
    private static final String TAG = "SpoofManager";

    private static final String[] WATCHED_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
    };

    // Spoofing stays off until the user opens Settings for the first time after
    // setup. The flag is persisted, so later boots start spoofing straight away;
    // a clean flash wipes /data and the flag with it.
    private static final String ACTIVATED_KEY = "spoof_activated";
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String VENDING_PACKAGE = "com.android.vending";
    private static final String[] GMS_FAMILY = {
            VENDING_PACKAGE,
            "com.google.android.gms.unstable",
            "com.google.android.gms",
            "com.google.android.gms.persistent",
            "com.google.android.rkpdapp",
            "com.google.android.gsf",
            "com.google.android.contactkeys",
            "com.google.android.safetycore",
            "com.google.android.googlequicksearchbox",
    };

    // Staging props for the values DroidGuard gets in place of the matching
    // read-only props (ro.product.*, the *.build.id, *.security_patch and
    // *api_level keys). publishAppcompatOverrides() mirrors them onto
    // ro.appcompat_override.<name>, which bionic serves in place of <name> only in
    // processes zygote turned the appcompat sysprop overrides on for. ProcessList
    // does that for com.google.android.gms.unstable alone, so every other process
    // keeps seeing the real values.
    private static final String PIF_PRODUCT_PROP_PREFIX = "persist.sys.pif.product.";
    private static final String[] PIF_PRODUCT_PROP_NAMES = {
            "manufacturer", "brand", "model", "device", "name",
    };
    private static final String PIF_BUILD_ID_PROP = "persist.sys.pif.build.id";
    private static final String PIF_SECURITY_PATCH_PROP = "persist.sys.pif.security_patch";
    private static final String PIF_API_LEVEL_PROP = "persist.sys.pif.api_level";
    private static final String PIF_BUILD_TYPE_PROP = "persist.sys.pif.build.type";
    private static final String PIF_BUILD_TAGS_PROP = "persist.sys.pif.build.tags";
    private static final String PIF_BUILD_DESCRIPTION_PROP = "persist.sys.pif.build.description";
    private static final String PIF_BUILD_FLAVOR_PROP = "persist.sys.pif.build.flavor";
    // Config entries the fixed props above don't cover. Slot i holds "name=value", or
    // "*suffix=value" for a leading-* wildcard; the count says how many slots are live.
    private static final String PIF_CUSTOM_COUNT_PROP = "persist.sys.pif.x.count";
    private static final String PIF_CUSTOM_SLOT_PREFIX = "persist.sys.pif.x.";
    private static final int PIF_CUSTOM_SLOT_MAX = 32;
    private static final int PROP_VALUE_MAX_CHARS = 91;

    private static final String DROIDGUARD_PROCESS = "com.google.android.gms.unstable";
    private static final String APPCOMPAT_OVERRIDE_PREFIX = "ro.appcompat_override.";
    // Set while the overrides hold the staged values. Not persisted, the
    // overrides themselves are gone after a reboot until they're published again.
    private static final String PIF_OVERRIDES_ACTIVE_PROP = "sys.pif.overrides";
    private static final String[] PIF_PARTITIONS = {
            "bootimage", "odm", "odm_dlkm", "product", "system", "system_dlkm",
            "system_ext", "vendor", "vendor_dlkm",
    };

    private BroadcastReceiver mPackageAddedReceiver;
    // Nothing spoof-related runs until the user first opens Settings (after setup):
    // the config getters below return null and GMS is left alone. Set once by
    // enableSpoofing() and persisted in ACTIVATED_KEY.
    private volatile boolean mActivated;
    private TaskStackListener mTaskListener;

    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private volatile Map<String, TrickyStoreService.Mode> mTargets = Collections.emptyMap();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private final Context mContext;
    private ContentResolver mResolver;
    private ContentObserver mObserver;
    private volatile boolean mReady = false;

    public SpoofManager(Context context) {
        mContext = context;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    public void systemReady() {
        mResolver = mContext.getContentResolver();

        for (String key : WATCHED_KEYS) {
            refreshKey(key);
        }

        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) return;
                final String last = uri.getLastPathSegment();
                if (last == null) return;
                refreshKey(last);
                if (Settings.Secure.SPOOF_PIF_CONFIG.equals(last)) {
                    onPifConfigChanged();
                }
                Log.i(TAG, "Spoof config refreshed: " + last);
            }
        };
        for (String key : WATCHED_KEYS) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }

        mActivated = Settings.Secure.getIntForUser(
                mResolver, ACTIVATED_KEY, 0, UserHandle.USER_SYSTEM) != 0;
        if (mActivated) {
            seedDefaultTargetsIfUnset();
            stagePifPropOverrides(getCached(Settings.Secure.SPOOF_PIF_CONFIG));
        } else {
            Log.i(TAG, "Spoofing off until Settings is opened for the first time");
            registerSettingsWatcher();
        }

        mPackageAddedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                // An update also broadcasts PACKAGE_ADDED; only new installs get targeted,
                // so an app the user removed from the list isn't re-added on every update.
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                Uri data = intent.getData();
                String pkg = data != null ? data.getSchemeSpecificPart() : null;
                if (pkg != null) {
                    mHandler.post(() -> onPackageAdded(pkg));
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(mPackageAddedReceiver, UserHandle.ALL, filter, null, mHandler);

        mReady = true;
        Log.i(TAG, "SpoofManager ready");
    }

    /**
     * Watches for the first time the user brings Settings to the front. Only
     * counts once setup is finished: Setup Wizard opens Settings screens (lock
     * screen, fingerprint) and must not trigger the GMS restart.
     */
    private void registerSettingsWatcher() {
        mTaskListener = new TaskStackListener() {
            @Override
            public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
                if (mActivated || taskInfo == null) return;
                final String pkg = taskInfo.topActivity != null
                        ? taskInfo.topActivity.getPackageName()
                        : (taskInfo.baseActivity != null
                                ? taskInfo.baseActivity.getPackageName() : null);
                if (!SETTINGS_PACKAGE.equals(pkg)) return;
                mHandler.post(() -> {
                    if (!mActivated && isDeviceProvisioned()) enableSpoofing();
                });
            }
        };
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to watch for Settings launch, spoofing stays off", e);
            mTaskListener = null;
        }
    }

    private void unregisterSettingsWatcher() {
        if (mTaskListener == null) return;
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(mTaskListener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister Settings watcher", e);
        }
        mTaskListener = null;
    }

    /**
     * Flips spoofing on, reloads the configs system_server's own PIF and TrickyStore
     * instances cached empty while off, and restarts the GMS family, whose
     * processes were started without a config.
     */
    private void enableSpoofing() {
        if (mActivated) return;
        mActivated = true;
        Settings.Secure.putIntForUser(mResolver, ACTIVATED_KEY, 1, UserHandle.USER_SYSTEM);
        unregisterSettingsWatcher();
        Log.i(TAG, "Settings opened, enabling spoofing");
        PlayIntegritySpoofService.getInstance().loadConfig();
        TrickyStoreService.getInstance().initialize();
        seedDefaultTargetsIfUnset();
        stagePifPropOverrides(getCached(Settings.Secure.SPOOF_PIF_CONFIG));
        killGmsFamily();
    }

    private void refreshKey(String key) {
        if (mResolver == null) return;
        final String value = Settings.Secure.getStringForUser(
                mResolver, key, UserHandle.USER_SYSTEM);
        if (value == null) {
            mCache.remove(key);
        } else {
            mCache.put(key, value);
        }
        if (Settings.Secure.SPOOF_TRICKYSTORE_TARGET.equals(key)) {
            mTargets = parseTargets(value);
        }
    }

    private static Map<String, TrickyStoreService.Mode> parseTargets(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return TrickyStoreService.parseTargets(value.trim());
        } catch (Exception e) {
            Log.w(TAG, "Unreadable TrickyStore targets", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Called after SPOOF_PIF_CONFIG changes. If the config was cleared (reset
     * to defaults, or the user deleted it), drop the staged PIF props so
     * DroidGuard stops getting the old device identity for the overridden reads, and
     * stop the GMS family so it stops running on the old fingerprint. A non-empty
     * config is staged as it stands, so a manual import or a Settings edit reaches
     * DroidGuard.
     */
    private void onPifConfigChanged() {
        final String config = getCached(Settings.Secure.SPOOF_PIF_CONFIG);
        if (config != null && !config.trim().isEmpty()) {
            if (mActivated) stagePifPropOverrides(config);
            return;
        }

        clearPifPropOverrides();
        killGmsFamily();
        Log.i(TAG, "PIF config cleared, dropped staged PIF props");
    }

    /**
     * Empties the staged PIF props and puts the real values back into the
     * overrides. A property can't be deleted once set, and an empty staging
     * prop counts as unset.
     */
    private void clearPifPropOverrides() {
        final Set<String> published = publishedCandidates();
        for (String name : PIF_PRODUCT_PROP_NAMES) {
            setStagedProp(PIF_PRODUCT_PROP_PREFIX + name, null);
        }
        setStagedProp(PIF_BUILD_ID_PROP, null);
        setStagedProp(PIF_SECURITY_PATCH_PROP, null);
        setStagedProp(PIF_API_LEVEL_PROP, null);
        setStagedProp(PIF_BUILD_TYPE_PROP, null);
        setStagedProp(PIF_BUILD_TAGS_PROP, null);
        setStagedProp(PIF_BUILD_DESCRIPTION_PROP, null);
        setStagedProp(PIF_BUILD_FLAVOR_PROP, null);
        stageCustomProps(null);
        publishAppcompatOverrides(published, false);
    }

    /**
     * Writes the default target list into a target setting that was never set,
     * so a fresh install attests GMS and friends without anyone opening Settings
     * (TrickyStoreService ignores any package that isn't listed). Only a null
     * value is seeded: an empty string means the user cleared the list on
     * purpose and is left alone.
     */
    private void seedDefaultTargetsIfUnset() {
        String current = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, UserHandle.USER_SYSTEM);
        if (current != null) return;

        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                TrickyStoreService.DEFAULT_TARGET_LIST, UserHandle.USER_SYSTEM);
        Log.i(TAG, "Seeded default TrickyStore targets");
    }

    /**
     * Adds a newly-installed package to the TrickyStore target list in AUTO
     * mode, unless it's already present or is a known Xposed/LSPosed manager
     * (attesting through a hooked process breaks STRONG). Mirrors AlwaysStrong's
     * inotify-driven auto-target, sourced from a live PACKAGE_ADDED broadcast
     * instead of a native watcher since this already runs in system_server.
     *
     * Does nothing while the list is empty: Settings seeds the default targets
     * (GMS and friends) only when the list is empty, so writing a lone package
     * here would leave those defaults unseeded. A JSON list can't be appended
     * to as text either.
     */
    private void onPackageAdded(String pkg) {
        if (!mActivated) return;
        if (mResolver == null || TrickyStoreService.XPOSED_PACKAGES.contains(pkg)) return;

        String current = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, UserHandle.USER_SYSTEM);
        String existing = current != null ? current : "";
        String trimmed = existing.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("[") || trimmed.startsWith("{")) return;

        for (String raw : existing.split("\n")) {
            String line = raw.trim();
            String bare = line.endsWith("!") || line.endsWith("?") || line.endsWith("-")
                    ? line.substring(0, line.length() - 1).trim() : line;
            if (pkg.equals(bare)) return; // already targeted, in whatever mode
        }

        String updated = existing.endsWith("\n") ? existing + pkg : existing + "\n" + pkg;
        Settings.Secure.putStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, updated, UserHandle.USER_SYSTEM);
        Log.i(TAG, "Auto-targeted newly installed package: " + pkg);
    }

    private void killGmsFamily() {
        // Never touch GMS/GSF before spoofing is activated (first Settings launch),
        // so Setup Wizard is never disturbed.
        if (!mActivated) {
            Log.i(TAG, "Spoofing not activated, skipping GMS restart");
            return;
        }
        try {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            for (String pkg : GMS_FAMILY) {
                am.forceStopPackage(pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to restart GMS family after refresh", e);
        }
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(mResolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0
                && Settings.Secure.getIntForUser(mResolver,
                        Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_SYSTEM) != 0;
    }

    /**
     * Stages the spoofed product-identity, build id, security patch and initial
     * API level from the PIF config as mutable persist props, then publishes
     * them as appcompat overrides for DroidGuard in place of the real read-only
     * values. That closes the gap where DroidGuard reading properties natively
     * (rather than through the Build class) would see the real device.
     *
     * The config is the source of truth whichever path wrote it (auto-refresh,
     * manual import, or Settings). A field the config doesn't carry is staged empty,
     * which counts as unset, so it never keeps a value from an older
     * config. Like PIFork, an explicit *.build.id, *.security_patch or
     * *api_level entry wins over the matching Build field; the build id falls
     * back to the one inside FINGERPRINT.
     *
     * spoofProps=0 turns the prop overrides off, as it does in PIFork: nothing
     * is staged, so DroidGuard reads the real values.
     */
    private void stagePifPropOverrides(String config) {
        final Map<String, String> fields = parsePifConfig(config);
        if (!isSpoofPropsEnabled(fields)) {
            clearPifPropOverrides();
            return;
        }
        final Set<String> published = publishedCandidates();
        // A config in PIF's default shape only has FINGERPRINT, so fall back to the
        // brand/product/device inside it, as PlayIntegritySpoofService does.
        final String[] head = fingerprintHead(fields.get("FINGERPRINT"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "manufacturer", fields.get("MANUFACTURER"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "brand", firstNonEmpty(
                fields.get("BRAND"), head != null ? head[0] : null));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "model", fields.get("MODEL"));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "device", firstNonEmpty(
                fields.get("DEVICE"), head != null ? head[2] : null));
        setStagedProp(PIF_PRODUCT_PROP_PREFIX + "name", firstNonEmpty(
                fields.get("PRODUCT"), head != null ? head[1] : null));
        final String[] tail = fingerprintTail(fields.get("FINGERPRINT"));
        final String buildId = firstNonEmpty(
                fields.get("*.build.id"), fields.get("ID"),
                buildIdFromFingerprint(fields.get("FINGERPRINT")));
        setStagedProp(PIF_BUILD_ID_PROP, buildId);
        // ro.*.build.type/tags for every partition and ro.build.description/flavor, so they
        // agree with the spoofed fingerprint. An unofficial ROM reports test-keys on the
        // partitions init doesn't pin, and its own description everywhere.
        final String type = firstNonEmpty(fields.get("*.build.type"), fields.get("TYPE"),
                tail != null ? tail[3] : null);
        final String tags = firstNonEmpty(fields.get("*.build.tags"), fields.get("TAGS"),
                tail != null ? tail[4] : null);
        setStagedProp(PIF_BUILD_TYPE_PROP, type);
        setStagedProp(PIF_BUILD_TAGS_PROP, tags);
        final String product = firstNonEmpty(
                fields.get("PRODUCT"), head != null ? head[1] : null);
        String release = firstNonEmpty(
                fields.get("RELEASE"), tail != null ? tail[0] : null);
        if (release != null && "CANARY".equalsIgnoreCase(release)) {
            final String own = Build.VERSION.RELEASE;
            release = (own == null || own.isEmpty()) ? null : own;
        }
        final String incremental = firstNonEmpty(
                fields.get("INCREMENTAL"), tail != null ? tail[2] : null);
        final boolean haveDescription = product != null && type != null && release != null
                && buildId != null && incremental != null && tags != null;
        // Same layout as a stock build: product-type release id incremental tags
        setStagedProp(PIF_BUILD_DESCRIPTION_PROP, haveDescription
                ? product + "-" + type + " " + release + " " + buildId + " "
                        + incremental + " " + tags
                : null);
        setStagedProp(PIF_BUILD_FLAVOR_PROP,
                product != null && type != null ? product + "-" + type : null);
        setStagedProp(PIF_SECURITY_PATCH_PROP, firstNonEmpty(
                fields.get("*.security_patch"), fields.get("SECURITY_PATCH"),
                PixelDeviceRepository.securityPatchFromBuildId(buildId)));
        setStagedProp(PIF_API_LEVEL_PROP, firstNonEmpty(
                fields.get("*api_level"),
                PixelDeviceRepository.resolveInitialSdkInt(
                        firstNonEmpty(fields.get("DEVICE"), head != null ? head[2] : null),
                        fields.get("DEVICE_INITIAL_SDK_INT"))));
        stageCustomProps(fields);
        publishAppcompatOverrides(published, true);
    }

    /**
     * Stages every other property entry of the config for DroidGuard, like PIFork's
     * jsonProps: a key containing '.' or '*' is a property name (exact, or a leading-*
     * suffix wildcard), not a Build field. Entries are sorted by key, which is the
     * order PIFork walks its wildcards in, and an exact match is served before any
     * wildcard. The three wildcards handled above are skipped here.
     */
    private static void stageCustomProps(Map<String, String> fields) {
        final java.util.TreeMap<String, String> entries = new java.util.TreeMap<>();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                final String key = e.getKey();
                final String value = e.getValue();
                if (key.indexOf('.') < 0 && key.indexOf('*') < 0) continue;
                if (key.equals("*") || key.startsWith("persist.sys.pif.")) continue;
                if (key.equals("*.build.id") || key.equals("*.security_patch")
                        || key.equals("*api_level") || key.equals("*.build.type")
                        || key.equals("*.build.tags")) continue;
                if (value == null || value.isEmpty()) continue;
                final String entry = key + "=" + value;
                if (entry.length() > PROP_VALUE_MAX_CHARS) {
                    Log.w(TAG, "PIF property " + key + " is too long to stage, skipping");
                    continue;
                }
                entries.put(key, entry);
            }
        }
        final int previous = Math.min(
                android.os.SystemProperties.getInt(PIF_CUSTOM_COUNT_PROP, 0),
                PIF_CUSTOM_SLOT_MAX);
        int count = 0;
        for (String entry : entries.values()) {
            if (count >= PIF_CUSTOM_SLOT_MAX) {
                Log.w(TAG, "More than " + PIF_CUSTOM_SLOT_MAX
                        + " custom PIF properties, ignoring the rest");
                break;
            }
            setStagedProp(PIF_CUSTOM_SLOT_PREFIX + count, entry);
            count++;
        }
        // Slots first when growing, count first when shrinking, so a reader never sees a
        // count that points at a stale or empty slot.
        setStagedProp(PIF_CUSTOM_COUNT_PROP, count > 0 ? Integer.toString(count) : null);
        for (int i = count; i < previous; i++) {
            setStagedProp(PIF_CUSTOM_SLOT_PREFIX + i, null);
        }
    }

    /**
     * Same rule as PlayIntegritySpoofService: on unless the config sets spoofProps
     * to something other than 1 or true.
     */
    private static boolean isSpoofPropsEnabled(Map<String, String> fields) {
        final String value = fields.get("spoofProps");
        return value == null || "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /**
     * Sets one staging prop, or empties it when there is no value. Skipped when
     * it already holds the value, since this runs on every config change and
     * persist props are written to disk.
     */
    private static void setStagedProp(String name, String value) {
        final String target = value != null ? value : "";
        try {
            if (target.equals(android.os.SystemProperties.get(name, ""))) return;
            android.os.SystemProperties.set(name, target);
        } catch (Exception e) {
            Log.w(TAG, "Failed to stage " + name, e);
        }
    }

    /**
     * Whether zygote should turn on the appcompat sysprop overrides for a new
     * process. Only DroidGuard gets them, and only while they hold the staged
     * values.
     */
    public static boolean shouldOverrideSysprops(String processName) {
        if (!DROIDGUARD_PROCESS.equals(processName)) {
            return false;
        }
        return android.os.SystemProperties.getBoolean(PIF_OVERRIDES_ACTIVE_PROP, false);
    }

    /**
     * Writes ro.appcompat_override.<name> for every real prop a staging prop
     * replaces, in the same order the staging props were always served in. An
     * exact custom entry wins, then the fixed staging props, then the adbd and
     * USB state, then a suffix entry. Only props the device has get an override.
     * A name that had one and has no staged value anymore gets its real value
     * back, since an override can't be deleted either.
     */
    private static void publishAppcompatOverrides(Set<String> previous, boolean active) {
        if (!active) {
            setStagedProp(PIF_OVERRIDES_ACTIVE_PROP, null);
        }
        final List<String[]> exact = new ArrayList<>();
        final List<String[]> suffix = new ArrayList<>();
        readCustomSlots(exact, suffix);

        final Set<String> names = overrideCandidates(exact, suffix);
        names.addAll(previous);
        for (String name : names) {
            final String real = android.os.SystemProperties.get(name, "");
            final String current = android.os.SystemProperties.get(
                    APPCOMPAT_OVERRIDE_PREFIX + name, "");
            String target = active ? overrideValue(name, exact, suffix) : null;
            if (real.isEmpty()) {
                target = current.isEmpty() ? null : "";
            } else if (target == null) {
                target = current.isEmpty() ? null : real;
            }
            if (target == null || target.equals(current)) {
                continue;
            }
            // Setting an existing override crashes init (bionic's Update() looks
            // it up by the prefixed name), so it only changes on the next boot.
            if (android.os.SystemProperties.find(APPCOMPAT_OVERRIDE_PREFIX + name) != null) {
                continue;
            }
            try {
                android.os.SystemProperties.set(APPCOMPAT_OVERRIDE_PREFIX + name, target);
            } catch (Exception e) {
                Log.w(TAG, "Failed to override " + name, e);
            }
        }

        if (active) {
            setStagedProp(PIF_OVERRIDES_ACTIVE_PROP, "1");
        }
    }

    private static String overrideValue(String name, List<String[]> exact,
            List<String[]> suffix) {
        for (String[] rule : exact) {
            if (rule[0].equals(name)) {
                return rule[1];
            }
        }
        final String staging = stagingPropFor(name);
        if (staging != null) {
            final String value = android.os.SystemProperties.get(staging, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        if (name.equals("init.svc.adbd")) {
            return "stopped";
        }
        if (name.equals("sys.usb.state")) {
            return "mtp";
        }
        for (String[] rule : suffix) {
            if (name.endsWith(rule[0])) {
                return rule[1];
            }
        }
        return null;
    }

    private static String stagingPropFor(String name) {
        if (name.equals("ro.build.product")) {
            return PIF_PRODUCT_PROP_PREFIX + "device";
        }
        if (name.equals("ro.build.description")) {
            return PIF_BUILD_DESCRIPTION_PROP;
        }
        if (name.equals("ro.build.flavor")) {
            return PIF_BUILD_FLAVOR_PROP;
        }
        if (name.startsWith("ro.product.")) {
            final String rest = name.substring("ro.product.".length());
            final int dot = rest.indexOf('.');
            final String field = dot < 0 ? rest : rest.substring(dot + 1);
            if (dot != 0 && field.indexOf('.') < 0) {
                for (String product : PIF_PRODUCT_PROP_NAMES) {
                    if (product.equals(field)) {
                        return PIF_PRODUCT_PROP_PREFIX + field;
                    }
                }
            }
        }
        if (!name.startsWith("ro.")) {
            return null;
        }
        if (name.endsWith(".build.id")) {
            return PIF_BUILD_ID_PROP;
        }
        if (name.endsWith(".security_patch") || name.endsWith(".real_security_patch")) {
            return PIF_SECURITY_PATCH_PROP;
        }
        if (name.endsWith("api_level")) {
            return PIF_API_LEVEL_PROP;
        }
        if (name.endsWith(".build.type")) {
            return PIF_BUILD_TYPE_PROP;
        }
        if (name.endsWith(".build.tags")) {
            return PIF_BUILD_TAGS_PROP;
        }
        return null;
    }

    /**
     * Every real prop that can get an override. There's no way to list props
     * from here, so a suffix entry is expanded over the same names a stock
     * build uses for it.
     */
    private static Set<String> overrideCandidates(List<String[]> exact, List<String[]> suffix) {
        final Set<String> names = new LinkedHashSet<>();
        for (String field : PIF_PRODUCT_PROP_NAMES) {
            names.add("ro.product." + field);
            for (String part : PIF_PARTITIONS) {
                names.add("ro.product." + part + "." + field);
            }
        }
        names.add("ro.build.id");
        names.add("ro.build.type");
        names.add("ro.build.tags");
        names.add("ro.build.product");
        names.add("ro.build.description");
        names.add("ro.build.flavor");
        names.add("ro.build.version.security_patch");
        names.add("ro.build.version.real_security_patch");
        names.add("ro.product.first_api_level");
        names.add("ro.board.first_api_level");
        names.add("ro.board.api_level");
        names.add("ro.vendor.api_level");
        for (String part : PIF_PARTITIONS) {
            final String prefix = "ro." + part + ".build.";
            names.add(prefix + "id");
            names.add(prefix + "type");
            names.add(prefix + "tags");
            names.add(prefix + "security_patch");
            names.add(prefix + "version.security_patch");
            names.add(prefix + "version.real_security_patch");
        }
        for (String[] rule : exact) {
            names.add(rule[0]);
        }
        for (String[] rule : suffix) {
            if (rule[0].startsWith(".")) {
                names.add("ro" + rule[0]);
                names.add("ro.build" + rule[0]);
                for (String part : PIF_PARTITIONS) {
                    names.add("ro." + part + ".build" + rule[0]);
                }
            } else {
                names.add("ro." + rule[0]);
                names.add("ro.product." + rule[0]);
                names.add("ro.board." + rule[0]);
            }
        }
        names.add("init.svc.adbd");
        names.add("sys.usb.state");
        return names;
    }

    /** Splits the live custom slots into exact and suffix {name, value} pairs. */
    private static void readCustomSlots(List<String[]> exact, List<String[]> suffix) {
        final int count = Math.min(
                android.os.SystemProperties.getInt(PIF_CUSTOM_COUNT_PROP, 0),
                PIF_CUSTOM_SLOT_MAX);
        for (int i = 0; i < count; i++) {
            final String entry = android.os.SystemProperties.get(PIF_CUSTOM_SLOT_PREFIX + i, "");
            final int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String name = entry.substring(0, eq);
            final String value = entry.substring(eq + 1);
            if (name.startsWith("*")) {
                if (name.length() > 1) {
                    suffix.add(new String[] {name.substring(1), value});
                }
            } else {
                exact.add(new String[] {name, value});
            }
        }
    }

    /** The names the current custom slots can have overrides on. */
    private static Set<String> publishedCandidates() {
        final List<String[]> exact = new ArrayList<>();
        final List<String[]> suffix = new ArrayList<>();
        readCustomSlots(exact, suffix);
        return overrideCandidates(exact, suffix);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    /** {brand, product, device} from brand/product/device:release/id/incremental:type/tags. */
    private static String[] fingerprintHead(String fingerprint) {
        if (fingerprint == null) return null;
        final int colon = fingerprint.indexOf(':');
        if (colon < 0) return null;
        final String[] head = fingerprint.substring(0, colon).split("/");
        return head.length == 3 ? head : null;
    }

    /** {release, id, incremental, type, tags} from brand/product/device:release/id/incremental:type/tags. */
    private static String[] fingerprintTail(String fingerprint) {
        if (fingerprint == null) return null;
        final int first = fingerprint.indexOf(':');
        final int second = first < 0 ? -1 : fingerprint.indexOf(':', first + 1);
        if (second < 0) return null;
        final String[] mid = fingerprint.substring(first + 1, second).split("/");
        final String[] end = fingerprint.substring(second + 1).split("/");
        if (mid.length != 3 || end.length != 2) return null;
        return new String[] {mid[0], mid[1], mid[2], end[0], end[1]};
    }

    /** Format: brand/product/device:release/id/incremental:type/tags */
    private static String buildIdFromFingerprint(String fingerprint) {
        if (fingerprint == null) return null;
        final int colon = fingerprint.indexOf(':');
        if (colon < 0) return null;
        final String[] rest = fingerprint.substring(colon + 1).split("/");
        return rest.length >= 4 ? rest[1] : null;
    }

    /**
     * Reads the flat key/value view of a PIF config, in either of the two formats
     * PlayIntegritySpoofService accepts: JSON, or PIF's key=value lines with
     * # comments.
     */
    private static Map<String, String> parsePifConfig(String config) {
        final Map<String, String> out = new java.util.HashMap<>();
        if (config == null || config.trim().isEmpty()) return out;
        try {
            if (config.trim().startsWith("{")) {
                final JSONObject json = new JSONObject(config);
                final java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    if (!json.isNull(key)) out.put(key, json.get(key).toString());
                }
            } else {
                for (String line : config.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    final int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String value = line.substring(eq + 1);
                    final int comment = value.indexOf('#');
                    if (comment >= 0) value = value.substring(0, comment);
                    value = value.trim();
                    if (value.isEmpty()) continue; // the service skips these too
                    out.put(line.substring(0, eq).trim(), value);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't read PIF config for staging", e);
        }
        return out;
    }

    private String getCached(String key) {
        return mCache.get(key);
    }

    public String getPifConfig(int callingUid) {
        if (!mActivated || !isPifCaller(callingUid)) {
            return null;
        }
        return getCached(Settings.Secure.SPOOF_PIF_CONFIG);
    }

    public String getTrickyStoreTarget(int callingUid) {
        if (!mActivated || !isTrickyStoreCaller(callingUid)) {
            return null;
        }
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
    }

    public String getTrickyStoreKeyBox(int callingUid) {
        if (!mActivated || !isTrickyStoreCaller(callingUid)) {
            return null;
        }
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
    }

    public String getTrickyStorePatch(int callingUid) {
        if (!mActivated || !isTrickyStoreCaller(callingUid)) {
            return null;
        }
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_PATCH);
    }

    private boolean isPifCaller(int callingUid) {
        if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return true;
        }
        final String[] packages = getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (GMS_PACKAGE.equals(pkg) || VENDING_PACKAGE.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTrickyStoreCaller(int callingUid) {
        if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return true;
        }
        final String[] packages = getPackagesForUid(callingUid);
        if (packages == null) {
            return false;
        }
        final Map<String, TrickyStoreService.Mode> targets = mTargets;
        for (String pkg : packages) {
            if (TrickyStoreService.XPOSED_PACKAGES.contains(pkg)) {
                continue;
            }
            final TrickyStoreService.Mode mode = targets.get(pkg);
            if (mode != null && mode != TrickyStoreService.Mode.SKIP) {
                return true;
            }
        }
        return false;
    }

    private String[] getPackagesForUid(int uid) {
        final long token = Binder.clearCallingIdentity();
        try {
            return mContext.getPackageManager().getPackagesForUid(uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
