/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.custom;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PixelDeviceRepository {

    private static final String TAG = "PixelDeviceRepository";
    public static final String CACHE_KEY = "pi_pixel_device_cache";
    public static final String STABLE_CACHE_KEY = "pi_pixel_stable_device_cache";
    private static final int CACHE_VERSION = 2;
    public static final String DEFAULT_CATALOG_REPO = "PixelOS-17/pixel_catalog";
    private static final int CATALOG_MAX_BYTES = 1024 * 1024;
    private static final int CATALOG_MAX_PROFILES = 256;

    private static volatile List<PixelProfile> sMemoryCache = null;
    private static volatile List<PixelProfile> sStableMemoryCache = null;
    private static final Object sFetchLock = new Object();
    private static final Object sStableFetchLock = new Object();

    public static final class PixelProfile {
        public final String codename;
        public final String model;
        public final String brand;
        public final String device;
        public final String product;
        public final String fingerprint;
        public final String buildId;
        public final String securityPatch;
        public final long fetchedAt;
        // Precise factory image publish date (yyyy-MM-dd), from the image's
        // Last-Modified header. Null when unavailable — callers fall back to
        // estimating from the canary month instead.
        public final String releaseDate;
        // True when the catalog entry is a canary or beta build.
        public final boolean isCanary;
        // Launch SDK from the catalog. Null when the entry omits it.
        public final String initialSdk;

        public PixelProfile(String codename, String model, String brand, String device,
                String product, String fingerprint, String buildId,
                String securityPatch, long fetchedAt) {
            this(codename, model, brand, device, product, fingerprint, buildId,
                    securityPatch, fetchedAt, null, true);
        }

        public PixelProfile(String codename, String model, String brand, String device,
                String product, String fingerprint, String buildId,
                String securityPatch, long fetchedAt, String releaseDate) {
            this(codename, model, brand, device, product, fingerprint, buildId,
                    securityPatch, fetchedAt, releaseDate, true);
        }

        public PixelProfile(String codename, String model, String brand, String device,
                String product, String fingerprint, String buildId,
                String securityPatch, long fetchedAt, String releaseDate, boolean isCanary) {
            this(codename, model, brand, device, product, fingerprint, buildId,
                    securityPatch, fetchedAt, releaseDate, isCanary, null);
        }

        public PixelProfile(String codename, String model, String brand, String device,
                String product, String fingerprint, String buildId,
                String securityPatch, long fetchedAt, String releaseDate, boolean isCanary,
                String initialSdk) {
            this.codename      = codename;
            this.model         = model;
            this.brand         = brand;
            this.device        = device;
            this.product       = product;
            this.fingerprint   = fingerprint;
            this.buildId       = buildId;
            this.securityPatch = securityPatch;
            this.fetchedAt     = fetchedAt;
            this.releaseDate   = releaseDate;
            this.isCanary      = isCanary;
            this.initialSdk    = initialSdk == null || initialSdk.isEmpty() ? null : initialSdk;
        }

        // Kotlin-style getters for compatibility with existing call sites
        public String getCodename()      { return codename; }
        public String getModel()         { return model; }
        public String getBrand()         { return brand; }
        public String getDevice()        { return device; }
        public String getProduct()       { return product; }
        public String getFingerprint()   { return fingerprint; }
        public String getBuildId()       { return buildId; }
        public String getSecurityPatch() { return securityPatch; }
        public long   getFetchedAt()     { return fetchedAt; }
        public String getReleaseDate()   { return releaseDate; }
        public boolean isCanary()        { return isCanary; }
        public String getInitialSdk()    { return initialSdk; }

        /** This profile's canary month (YYYY-MM), derived from securityPatch. */
        public String getCanaryMonth() {
            return securityPatch != null && securityPatch.length() >= 7
                    ? securityPatch.substring(0, 7) : null;
        }
    }

    public static final Map<String, String> DEVICE_MODEL_MAP;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("oriole",    "Pixel 6");
        m.put("raven",     "Pixel 6 Pro");
        m.put("bluejay",   "Pixel 6a");
        m.put("panther",   "Pixel 7");
        m.put("cheetah",   "Pixel 7 Pro");
        m.put("lynx",      "Pixel 7a");
        m.put("felix",     "Pixel Fold");
        m.put("shiba",     "Pixel 8");
        m.put("husky",     "Pixel 8 Pro");
        m.put("akita",     "Pixel 8a");
        m.put("tangorpro", "Pixel Tablet");
        m.put("tokay",     "Pixel 9");
        m.put("caiman",    "Pixel 9 Pro");
        m.put("komodo",    "Pixel 9 Pro XL");
        m.put("comet",     "Pixel 9 Pro Fold");
        m.put("tegu",      "Pixel 9a");
        m.put("frankel",   "Pixel 10");
        m.put("blazer",    "Pixel 10 Pro");
        m.put("mustang",   "Pixel 10 Pro XL");
        m.put("rango",     "Pixel 10 Pro Fold");
        m.put("stallion",  "Pixel 10a");
        m.put("cubs",      "Pixel 11");
        m.put("grizzly",   "Pixel 11 Pro");
        m.put("kodiak",    "Pixel 11 Pro XL");
        m.put("yogi",      "Pixel 11 Pro Fold");
        m.put("formosan",  "Pixel 11a");
        m.put("galago",    "Pixel 12");
        m.put("sasquatch", "Pixel 12 Pro");
        m.put("silverback","Pixel 12 Pro XL");
        m.put("capuchin",  "Pixel 12 Pro Fold");
        DEVICE_MODEL_MAP = Collections.unmodifiableMap(m);
    }

    // Must be updated when a codename's *tier* (Pro XL vs Pro vs base vs a-series) is
    // confirmed — used only to pick the default codename, never to filter the catalog.
    // Newest-first; unranked/unknown codenames are simply not
    // eligible to become the default until added here.
    public static final List<String> GENERATION_ORDER = Collections.unmodifiableList(
            Arrays.asList(
                    // Pixel 11 series (current default tier: Pro XL)
                    "kodiak", "grizzly", "yogi", "cubs",
                    // Pixel 10 series
                    "mustang", "rango", "blazer", "frankel", "stallion",
                    // Pixel 9 series
                    "komodo", "caiman", "comet", "tokay", "tegu",
                    // Tablet
                    "tangorpro"
            ));

    // Device picker order, newest generation first (Pro XL, Pro, Fold, base, then a-series
    // within a generation). Display order only: it never filters the catalog,
    // and a codename missing here is simply listed after the ranked ones.
    public static final List<String> PICKER_ORDER = Collections.unmodifiableList(
            Arrays.asList(
                    // Pixel 12 series
                    "silverback", "sasquatch", "capuchin", "galago",
                    // Pixel 11 series
                    "kodiak", "grizzly", "yogi", "cubs", "formosan",
                    // Pixel 10 series
                    "mustang", "rango", "blazer", "frankel", "stallion",
                    // Pixel 9 series
                    "komodo", "caiman", "comet", "tokay", "tegu",
                    // Tablet
                    "tangorpro",
                    // Pixel 8 series and Fold
                    "husky", "felix", "shiba", "akita",
                    // Pixel 7 series
                    "cheetah", "panther", "lynx",
                    // Pixel 6 series
                    "raven", "oriole", "bluejay"
            ));

    // The one device Play Integrity spoofing targets by default (and the only one
    // marked as recommended). The Pixel 6a canary print is reported to keep
    // RCS working, unlike the flagship canary prints. This is deliberately separate
    // from GENERATION_ORDER, which still drives the Pixel props spoof for other apps.
    public static final String PIF_DEFAULT_CODENAME = "bluejay";

    /** DEVICE_INITIAL_SDK_INT used when a device's launch SDK isn't known. */
    public static final String DEFAULT_INITIAL_SDK = "32";

    /**
     * SDK level a Pixel launched with, for DEVICE_INITIAL_SDK_INT and
     * ro.product.first_api_level. Keeps the first API level consistent with the
     * spoofed device instead of claiming the Pixel 6a's for every codename.
     */
    public static String getInitialSdkInt(String device) {
        final String known = getKnownInitialSdkInt(device);
        return known != null ? known : DEFAULT_INITIAL_SDK;
    }

    /**
     * Launch SDK for a catalog profile. A codename in the launch table keeps that
     * value. An unknown codename uses the catalog's initialSdk when it is set.
     */
    public static String sdkForProfile(PixelProfile profile) {
        if (profile == null) return DEFAULT_INITIAL_SDK;
        final String known = getKnownInitialSdkInt(profile.device);
        if (known != null) return known;
        if (profile.initialSdk != null && profile.initialSdk.matches("\\d{1,3}")) {
            return profile.initialSdk;
        }
        return DEFAULT_INITIAL_SDK;
    }

    /**
     * Launch SDK for the config's DEVICE_INITIAL_SDK_INT. A known codename always gets
     * its table value, because a configured one can be stale for the profile it came
     * with (a Pixel 10 Pro XL "mustang" config carrying the Pixel 6a's 32 fails the
     * fingerprint cross-check). Only a codename missing from the table falls back to
     * [configured], which may be null or empty if the config has none.
     */
    public static String resolveInitialSdkInt(String device, String configured) {
        final String known = getKnownInitialSdkInt(device);
        if (known != null) return known;
        return configured == null || configured.trim().isEmpty() ? null : configured.trim();
    }

    /** Launch SDK of a codename in the table, or null for an unknown or missing one. */
    private static String getKnownInitialSdkInt(String device) {
        if (device == null) return null;
        switch (device) {
            case "oriole":
            case "raven":
                return "31";
            case "bluejay":
                return "32";
            case "panther":
            case "cheetah":
            case "lynx":
            case "felix":
            case "tangorpro":
                return "33";
            case "shiba":
            case "husky":
            case "akita":
            case "tokay":
            case "caiman":
            case "komodo":
            case "comet":
                return "34";
            case "tegu":
                return "35";
            case "frankel":
            case "blazer":
            case "mustang":
            case "rango":
            case "stallion":
                return "36";
            case "cubs":
            case "grizzly":
            case "kodiak":
            case "yogi":
                return "37";
            default:
                return null;
        }
    }

    /**
     * Security patch ("YYYY-MM-DD") encoded in a Pixel build ID: CP3A.260905.009 is
     * 2026-09-05. For configs that carry no SECURITY_PATCH, so the device's own patch
     * date doesn't show next to a spoofed fingerprint. Null if [buildId] has no
     * well-formed YYMMDD segment.
     */
    public static String securityPatchFromBuildId(String buildId) {
        if (buildId == null) return null;
        final int firstDot = buildId.indexOf('.');
        final int secondDot = firstDot < 0 ? -1 : buildId.indexOf('.', firstDot + 1);
        if (firstDot < 0 || secondDot != firstDot + 7) return null;
        final String yymmdd = buildId.substring(firstDot + 1, secondDot);
        for (int i = 0; i < yymmdd.length(); i++) {
            final char c = yymmdd.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        final int month = Integer.parseInt(yymmdd.substring(2, 4));
        final int day = Integer.parseInt(yymmdd.substring(4, 6));
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return "20" + yymmdd.substring(0, 2) + "-" + yymmdd.substring(2, 4) + "-"
                + yymmdd.substring(4, 6);
    }

    /**
     * Returns the codename Play Integrity spoofing should use: the Pixel 6a when it
     * is in [available], otherwise the newest ranked phone per GENERATION_ORDER.
     */
    public static String getDefaultPifCodename(List<PixelProfile> available) {
        for (PixelProfile p : available) {
            if (PIF_DEFAULT_CODENAME.equals(p.codename)) return PIF_DEFAULT_CODENAME;
        }
        return getDefaultPhoneCodename(available);
    }

    // Shared default spoof target packages — single source of truth used by both
    // PixelPropsUtils (runtime) and PixelPropsSettings (UI), rather than three
    // separately-maintained copies.
    public static final Set<String> DEFAULT_PP_TARGETS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.amazon.avod.thirdpartyclient",
                    "com.android.chrome",
                    "com.breel.wallpapers20",
                    "com.disney.disneyplus",
                    "com.google.android.aicore",
                    "com.google.android.apps.accessibility.magnifier",
                    "com.google.android.apps.ai.icons",
                    "com.google.android.apps.aiwallpapers",
                    "com.google.android.apps.bard",
                    "com.google.android.apps.customization.pixel",
                    "com.google.android.apps.emojiwallpaper",
                    "com.google.android.apps.pixel.agent",
                    "com.google.android.apps.pixel.creativeassistant",
                    "com.google.android.apps.pixel.customizationbundle",
                    "com.google.android.apps.pixel.nowplaying",
                    "com.google.android.apps.pixel.psi",
                    "com.google.android.apps.pixel.subzero",
                    "com.google.android.apps.pixel.support",
                    "com.google.android.apps.privacy.wildlife",
                    "com.google.android.apps.subscriptions.red",
                    "com.google.android.apps.wallpaper",
                    "com.google.android.apps.wallpaper.pixel",
                    "com.google.android.apps.weather",
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.pcs",
                    "com.google.android.wallpaper.effects",
                    "com.google.pixel.livewallpaper",
                    "com.microsoft.android.smsorganizer",
                    "com.nhs.online.nhsonline",
                    "com.nothing.smartcenter",
                    "com.realme.link",
                    "in.startv.hotstar",
                    "jp.id_credit_sp2.android"
            )));

    /**
     * Returns the newest ranked codename present in [available], per GENERATION_ORDER.
     * Unranked/unrecognized codenames (e.g. a not-yet-confirmed Pixel 11 tier) are
     * never auto-selected as default — add them to GENERATION_ORDER once confirmed.
     */
    public static String getDefaultPhoneCodename(List<PixelProfile> available) {
        Set<String> present = new HashSet<>();
        for (PixelProfile p : available) present.add(p.codename);
        for (String c : GENERATION_ORDER) {
            if (present.contains(c)) return c;
        }
        return "kodiak";
    }

    public static String getDefaultPhoneCodename() {
        return getDefaultPhoneCodename(FALLBACK_PROFILES);
    }

    // Hardcoded fallback profiles — only used when network fails AND cache is empty
    public static final List<PixelProfile> FALLBACK_PROFILES;
    static {
        List<PixelProfile> f = new ArrayList<>();
        f.add(new PixelProfile("kodiak",    "Pixel 11 Pro XL",   "google", "kodiak",    "kodiak",
                "google/kodiak/kodiak:17/CD1A.261005.003/16262255:user/release-keys",
                "CD1A.261005.003", "2026-10-05", 0L, null, false));
        f.add(new PixelProfile("mustang",   "Pixel 10 Pro XL",   "google", "mustang",   "mustang",
                "google/mustang/mustang:17/CP3A.261005.005/16271449:user/release-keys",
                "CP3A.261005.005", "2026-10-05", 0L, null, false));
        f.add(new PixelProfile("tangorpro", "Pixel Tablet",      "google", "tangorpro", "tangorpro",
                "google/tangorpro/tangorpro:17/CP3A.261005.002.A1/16269273:user/release-keys",
                "CP3A.261005.002.A1", "2026-10-05", 0L, null, false));
        f.add(new PixelProfile("bluejay",   "Pixel 6a",          "google", "bluejay",   "bluejay_beta",
                "google/bluejay_beta/bluejay:CANARY/ZP11.260918.007/16484274:user/release-keys",
                "ZP11.260918.007", "2026-10-05", 0L, null, true));
        FALLBACK_PROFILES = Collections.unmodifiableList(f);
    }

    /**
     * True if both timestamps fall in the same calendar year+month, using the
     * device's default timezone. Used to gate re-fetching to once per month
     * rather than a rolling window, matching canary's actual release cadence.
     */
    private static boolean isSameMonth(long a, long b) {
        java.util.Calendar ca = java.util.Calendar.getInstance();
        ca.setTimeInMillis(a);
        java.util.Calendar cb = java.util.Calendar.getInstance();
        cb.setTimeInMillis(b);
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR)
                && ca.get(java.util.Calendar.MONTH) == cb.get(java.util.Calendar.MONTH);
    }

    private PixelDeviceRepository() {}

    /**
     * Returns cached profiles if fresh, otherwise downloads the fingerprint catalog.
     * One profile per device, preferring a canary entry over a stable one.
     * Must be called from a background thread.
     * Falls back to hardcoded profiles if the download fails and the cache is empty.
     */
    public static List<PixelProfile> getProfiles(Context context, boolean forceRefresh) {
        synchronized (sFetchLock) {
            List<PixelProfile> cached = readCache(context);
            long fetchedAt = cached.isEmpty() ? 0L : cached.get(0).fetchedAt;
            // Canary profiles turn over roughly once a month; re-fetching within the
            // same calendar month just re-downloads the same builds. Only treat the
            // cache as stale once the wall-clock month has actually rolled over.
            boolean stale = cached.isEmpty() || !isSameMonth(fetchedAt, System.currentTimeMillis());

            if (!forceRefresh && !stale) return cached;

            List<PixelProfile> fresh = Collections.emptyList();
            try {
                fresh = preferCanary(fetchCatalog(DEFAULT_CATALOG_REPO).profiles);
            } catch (Exception e) {
                Log.w(TAG, "Catalog fetch failed: " + e.getMessage());
            }

            if (!fresh.isEmpty()) {
                writeCache(context, fresh);
                return fresh;
            } else {
                return cached.isEmpty() ? FALLBACK_PROFILES : cached;
            }
        }
    }

    public static List<PixelProfile> getProfiles(Context context) {
        return getProfiles(context, false);
    }

    /**
     * Same caching contract as getProfiles(), but returns stable catalog entries.
     * Canary profiles stay on the Play Integrity fetch.
     */
    public static List<PixelProfile> getStableProfiles(Context context, boolean forceRefresh) {
        synchronized (sStableFetchLock) {
            List<PixelProfile> cached = readCache(context, STABLE_CACHE_KEY);
            long fetchedAt = cached.isEmpty() ? 0L : cached.get(0).fetchedAt;
            boolean stale = cached.isEmpty() || !isSameMonth(fetchedAt, System.currentTimeMillis());

            if (!forceRefresh && !stale) return cached;

            List<PixelProfile> fresh = Collections.emptyList();
            try {
                fresh = stableOnly(fetchCatalog(DEFAULT_CATALOG_REPO).profiles);
            } catch (Exception e) {
                Log.w(TAG, "Stable catalog fetch failed: " + e.getMessage());
            }

            if (!fresh.isEmpty()) {
                sStableMemoryCache = fresh;
                writeCache(context, fresh, STABLE_CACHE_KEY);
                return fresh;
            } else {
                return cached.isEmpty() ? FALLBACK_PROFILES : cached;
            }
        }
    }

    public static List<PixelProfile> getStableProfiles(Context context) {
        return getStableProfiles(context, false);
    }

    /**
     * Validates that a fingerprint string matches the expected Android format:
     * brand/product/device:VERSION/ID/INCREMENTAL:TYPE/KEYS
     */
    public static boolean isValidFingerprint(String fp) {
        if (fp == null) return false;
        return fp.matches("^[^/]+/[^/]+/[^:]+:[^/]+/[^/]+/[^:]+:[^/]+/[^:]+$");
    }

    /**
     * Returns days elapsed since a YYYY-MM-DD security patch date, or null if
     * unparseable.
     */
    public static Long getPatchAgeDays(String patch) {
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Date d = sdf.parse(patch);
            if (d == null) return null;
            return (System.currentTimeMillis() - d.getTime()) / (1000L * 60 * 60 * 24);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns days remaining until a canary profile's estimated expiry (~6 weeks
     * from its release date, falling back to the 1st of its canary month), or
     * null if unparseable. Negative once expired.
     */
    public static Long getDaysUntilExpiry(String canaryMonth, String releaseDate) {
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            String base = (releaseDate != null && !releaseDate.isEmpty())
                    ? releaseDate : canaryMonth + "-01";
            java.util.Date parsed = sdf.parse(base);
            if (parsed == null) return null;
            long expiry = parsed.getTime() + 42L * 24 * 60 * 60 * 1000;
            return (expiry - System.currentTimeMillis()) / (1000L * 60 * 60 * 24);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns a single profile by codename from cache.
     * Falls back to kodiak (mobile) or tangorpro (tablet) if not found.
     * Safe to call from any thread.
     */
    public static PixelProfile getProfileByCodename(Context context, String codename,
            boolean isTablet) {
        List<PixelProfile> cached = sMemoryCache;
        if (cached == null) {
            cached = readCache(context);
            sMemoryCache = cached;
        }
        return findProfile(cached, codename, isTablet);
    }

    /**
     * Same as getProfileByCodename(), but looks in the stable profiles
     * PixelPropsSettings offers.
     */
    public static PixelProfile getStableProfileByCodename(Context context, String codename,
            boolean isTablet) {
        List<PixelProfile> cached = sStableMemoryCache;
        if (cached == null) {
            cached = readCache(context, STABLE_CACHE_KEY);
            sStableMemoryCache = cached;
        }
        return findProfile(cached, codename, isTablet);
    }

    private static PixelProfile findProfile(List<PixelProfile> cached, String codename,
            boolean isTablet) {
        try {
            String defaultCodename = isTablet ? "tangorpro" : "kodiak";
            PixelProfile result = findByCodename(cached, codename);
            if (result == null) result = findByCodename(cached, defaultCodename);
            if (result == null) result = findByCodename(FALLBACK_PROFILES, codename);
            if (result == null) result = findByCodename(FALLBACK_PROFILES, defaultCodename);
            return result;
        } catch (Exception e) {
            Log.w(TAG, "getProfileByCodename failed, using fallback: " + e.getMessage());
            return findByCodename(FALLBACK_PROFILES, isTablet ? "tangorpro" : "kodiak");
        }
    }

    private static PixelProfile findByCodename(List<PixelProfile> list, String codename) {
        for (PixelProfile p : list) {
            if (p.codename.equals(codename)) return p;
        }
        return null;
    }

    public static List<PixelProfile> readCache(Context context) {
        return readCache(context, CACHE_KEY);
    }

    public static List<PixelProfile> readCache(Context context, String key) {
        try {
            String json = Settings.Secure.getString(context.getContentResolver(), key);
            if (json == null || json.isEmpty()) return Collections.emptyList();
            JSONArray arr = new JSONArray(json);
            List<PixelProfile> result = new ArrayList<>();
            if (arr.length() == 0) return Collections.emptyList();
            if (arr.getJSONObject(0).optInt("cacheVersion", 1) != CACHE_VERSION)
                return Collections.emptyList();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    result.add(new PixelProfile(
                            o.getString("codename"),
                            o.getString("model"),
                            o.getString("brand"),
                            o.getString("device"),
                            o.getString("product"),
                            o.getString("fingerprint"),
                            o.getString("buildId"),
                            o.getString("securityPatch"),
                            o.getLong("fetchedAt"),
                            o.optString("releaseDate", null),
                            o.optBoolean("isCanary", true), // legacy cache entries were canary-only
                            o.optString("initialSdk", "")
                    ));
                } catch (Exception ignored) {}
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "Cache read failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public static void writeCache(Context context, List<PixelProfile> profiles) {
        sMemoryCache = profiles;
        writeCache(context, profiles, CACHE_KEY);
    }

    public static void writeCache(Context context, List<PixelProfile> profiles, String key) {
        try {
            JSONArray arr = new JSONArray();
            for (PixelProfile p : profiles) {
                JSONObject o = new JSONObject();
                o.put("cacheVersion",  CACHE_VERSION);
                o.put("codename",      p.codename);
                o.put("model",         p.model);
                o.put("brand",         p.brand);
                o.put("device",        p.device);
                o.put("product",       p.product);
                o.put("fingerprint",   p.fingerprint);
                o.put("buildId",       p.buildId);
                o.put("securityPatch", p.securityPatch);
                o.put("fetchedAt",     p.fetchedAt);
                if (p.releaseDate != null) o.put("releaseDate", p.releaseDate);
                o.put("isCanary", p.isCanary);
                if (p.initialSdk != null) o.put("initialSdk", p.initialSdk);
                arr.put(o);
            }
            String json = arr.toString();
            if (json.length() > 7500) {
                Log.w(TAG, "Cache JSON too large (" + json.length() + " chars), trimming to newest entries");
                // Drop oldest entries (tail of list) until it fits
                while (arr.length() > 1) {
                    arr.remove(arr.length() - 1);
                    json = arr.toString();
                    if (json.length() <= 7500) break;
                }
            }
            Settings.Secure.putString(context.getContentResolver(), key, json);
        } catch (Exception e) {
            Log.w(TAG, "Cache write failed: " + e.getMessage());
        }
    }

    /**
     * owner/name, or the default catalog when repo is null or blank.
     * The download host stays raw.githubusercontent.com. A full URL is rejected.
     */
    public static String normalizeCatalogRepo(String repo) {
        String trimmed = repo == null ? "" : repo.trim();
        if (trimmed.isEmpty()) return DEFAULT_CATALOG_REPO;
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash != trimmed.lastIndexOf('/')
                || !isGitHubName(trimmed.substring(0, slash), 39)
                || !isGitHubName(trimmed.substring(slash + 1), 100)) {
            throw new IllegalArgumentException("invalid repo");
        }
        return trimmed;
    }

    private static boolean isGitHubName(String name, int max) {
        if (name.isEmpty() || name.length() > max) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        char first = name.charAt(0);
        return (first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z')
                || (first >= '0' && first <= '9');
    }

    /**
     * Downloads catalog.json from owner/name on branch main.
     * Must be called from a background thread.
     */
    public static Catalog fetchCatalog(String repo) throws Exception {
        String normalized = normalizeCatalogRepo(repo);
        String url = "https://raw.githubusercontent.com/" + normalized + "/main/catalog.json";
        return parseCatalog(readCatalog(url));
    }

    /**
     * Catalog profiles for the Play Integrity picker: one entry per codename,
     * with a canary entry preferred over the stable entry for that codename.
     */
    public static Catalog fetchPifCatalog(String repo) throws Exception {
        Catalog catalog = fetchCatalog(repo);
        return new Catalog(catalog.defaultCodename, preferCanary(catalog.profiles));
    }

    public static final class Catalog {
        public final String defaultCodename;
        public final List<PixelProfile> profiles;

        public Catalog(String defaultCodename, List<PixelProfile> profiles) {
            this.defaultCodename = defaultCodename;
            this.profiles = profiles;
        }
    }

    private static String readCatalog(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "PixelOS");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream input = conn.getInputStream();
            try {
                byte[] bytes = input.readNBytes(CATALOG_MAX_BYTES + 1);
                if (bytes.length > CATALOG_MAX_BYTES) {
                    throw new IOException("catalog too large");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            } finally {
                input.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static Catalog parseCatalog(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray canary = root.optJSONArray("profiles");
        JSONArray stable = root.optJSONArray("stable");
        if (canary == null && stable == null) {
            throw new IllegalArgumentException("catalog has no profiles");
        }
        long now = System.currentTimeMillis();
        List<PixelProfile> profiles = new ArrayList<>();
        addCatalogEntries(profiles, canary, now);
        addCatalogEntries(profiles, stable, now);
        if (profiles.isEmpty()) throw new IllegalArgumentException("catalog has no profiles");
        String requested = root.optString("default_codename", "").trim();
        String defaultCodename = null;
        if (!requested.isEmpty()) {
            for (PixelProfile profile : profiles) {
                if (requested.equals(profile.codename)) {
                    defaultCodename = requested;
                    break;
                }
            }
        }
        return new Catalog(defaultCodename, profiles);
    }

    private static void addCatalogEntries(List<PixelProfile> profiles, JSONArray arr, long now) {
        if (arr == null) return;
        for (int i = 0; i < arr.length() && profiles.size() < CATALOG_MAX_PROFILES; i++) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null) continue;
            PixelProfile profile = profileFromCatalog(entry, now);
            if (profile != null) profiles.add(profile);
        }
    }

    private static PixelProfile profileFromCatalog(JSONObject entry, long now) {
        String codename = entry.optString("codename", "").trim();
        String fingerprint = entry.optString("fingerprint", "").trim();
        if (codename.isEmpty() || !isValidFingerprint(fingerprint)) return null;
        String model = entry.optString("model", "").trim();
        if (model.isEmpty()) {
            model = DEVICE_MODEL_MAP.getOrDefault(codename, codename);
        }
        String brand = entry.optString("brand", "").trim();
        if (brand.isEmpty()) brand = "google";
        String device = entry.optString("device", "").trim();
        if (device.isEmpty()) device = codename;
        String product = entry.optString("product", "").trim();
        if (product.isEmpty()) product = device;
        String buildId = entry.optString("buildId", "").trim();
        String securityPatch = entry.optString("securityPatch", "").trim();
        String releaseDate = entry.optString("releaseDate", "").trim();
        if (releaseDate.isEmpty()) releaseDate = null;
        String initialSdk = entry.optString("initialSdk", "").trim();
        if (!initialSdk.matches("\\d{1,3}")) initialSdk = null;
        return new PixelProfile(codename, model, brand, device, product, fingerprint,
                buildId, securityPatch, now, releaseDate, entry.optBoolean("isCanary", false),
                initialSdk);
    }

    private static List<PixelProfile> preferCanary(List<PixelProfile> all) {
        Map<String, PixelProfile> chosen = new LinkedHashMap<>();
        for (PixelProfile profile : all) {
            PixelProfile existing = chosen.get(profile.codename);
            if (existing == null || (profile.isCanary && !existing.isCanary)) {
                chosen.put(profile.codename, profile);
            }
        }
        return new ArrayList<>(chosen.values());
    }

    private static List<PixelProfile> stableOnly(List<PixelProfile> all) {
        List<PixelProfile> stable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PixelProfile profile : all) {
            if (profile.isCanary || !seen.add(profile.codename)) continue;
            stable.add(profile);
        }
        return stable;
    }
}
