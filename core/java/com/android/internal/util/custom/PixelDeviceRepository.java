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
    private static final String META_KEY = "pi_pixel_catalog_meta";
    private static final int CATALOG_MAX_BYTES = 1024 * 1024;
    private static final int CATALOG_MAX_PROFILES = 256;
    private static final int CATALOG_MAX_NAMES = 64;

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

    /**
     * Launch SDK carried on the catalog profile. Null when the catalog did not set one.
     */
    public static String sdkForProfile(PixelProfile profile) {
        if (profile != null && profile.initialSdk != null
                && profile.initialSdk.matches("\\d{1,3}")) {
            return profile.initialSdk;
        }
        return null;
    }

    /**
     * Launch SDK for DEVICE_INITIAL_SDK_INT. The catalog's initial_sdk map wins over a
     * stored value, because a stored one can belong to a different device. A codename
     * the catalog does not list keeps [configured].
     */
    public static String resolveInitialSdkInt(Context context, String device, String configured) {
        Catalog table = readTable(context);
        if (table != null && device != null) {
            String known = table.initialSdk.get(device);
            if (known != null) return known;
        }
        if (configured == null) return null;
        String trimmed = configured.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
     * Codename the fingerprint picker should mark. [preferred] is the catalog's
     * default_codename. Otherwise the first generation_order entry present in
     * [available], then the first profile.
     */
    public static String getDefaultPifCodename(List<PixelProfile> available, String preferred,
            List<String> generationOrder) {
        if (available == null || available.isEmpty()) return null;
        if (containsCodename(available, preferred)) return preferred;
        if (generationOrder != null) {
            for (String codename : generationOrder) {
                if (containsCodename(available, codename)) return codename;
            }
        }
        return available.get(0).codename;
    }

    /** Marketing name from the last fetched catalog, or null. */
    public static String modelName(Context context, String codename) {
        Catalog table = readTable(context);
        if (table == null || codename == null) return null;
        return table.models.get(codename);
    }

    /** Picker order from the last fetched catalog. Empty until the user fetches. */
    public static List<String> pickerOrder(Context context) {
        Catalog table = readTable(context);
        if (table == null) return Collections.emptyList();
        return table.pickerOrder;
    }

    /** Tablet codename from the last fetched catalog, or null. */
    public static String tabletCodename(Context context) {
        Catalog table = readTable(context);
        if (table == null || table.tabletCodename == null || table.tabletCodename.isEmpty()) {
            return null;
        }
        return table.tabletCodename;
    }

    private static boolean containsCodename(List<PixelProfile> available, String codename) {
        if (codename == null) return false;
        for (PixelProfile profile : available) {
            if (codename.equals(profile.codename)) return true;
        }
        return false;
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
     * First generation_order codename from the last fetched catalog, or null when the
     * user has not fetched.
     */
    public static String getDefaultPhoneCodename(Context context) {
        Catalog table = readTable(context);
        if (table == null || table.generationOrder.isEmpty()) return null;
        return table.generationOrder.get(0);
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
     * Returns an empty list when the download fails and the cache is empty.
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
                Catalog catalog = fetchCatalog(DEFAULT_CATALOG_REPO);
                remember(context, catalog);
                fresh = preferCanary(catalog.profiles);
            } catch (Exception e) {
                Log.w(TAG, "Catalog fetch failed: " + e.getMessage());
            }

            if (!fresh.isEmpty()) {
                writeCache(context, fresh);
                return fresh;
            }
            return cached;
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
                Catalog catalog = fetchCatalog(DEFAULT_CATALOG_REPO);
                remember(context, catalog);
                fresh = stableOnly(catalog.profiles);
            } catch (Exception e) {
                Log.w(TAG, "Stable catalog fetch failed: " + e.getMessage());
            }

            if (!fresh.isEmpty()) {
                sStableMemoryCache = fresh;
                writeCache(context, fresh, STABLE_CACHE_KEY);
                return fresh;
            }
            return cached;
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
     * Uses the catalog's phone or tablet codename when [codename] is missing.
     * Null when nothing has been fetched.
     */
    public static PixelProfile getProfileByCodename(Context context, String codename,
            boolean isTablet) {
        List<PixelProfile> cached = sMemoryCache;
        if (cached == null) {
            cached = readCache(context);
            sMemoryCache = cached;
        }
        return findProfile(cached, codename, isTablet ? tabletCodename(context)
                : getDefaultPhoneCodename(context));
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
        return findProfile(cached, codename, isTablet ? tabletCodename(context)
                : getDefaultPhoneCodename(context));
    }

    private static PixelProfile findProfile(List<PixelProfile> cached, String codename,
            String fallbackCodename) {
        PixelProfile result = findByCodename(cached, codename);
        if (result == null) result = findByCodename(cached, fallbackCodename);
        return result;
    }

    private static PixelProfile findByCodename(List<PixelProfile> list, String codename) {
        if (list == null || codename == null) return null;
        for (PixelProfile p : list) {
            if (codename.equals(p.codename)) return p;
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
    public static Catalog fetchPifCatalog(Context context, String repo) throws Exception {
        Catalog catalog = fetchCatalog(repo);
        if (context != null) {
            remember(context, catalog);
            List<PixelProfile> stable = stableOnly(catalog.profiles);
            if (!stable.isEmpty()) {
                sStableMemoryCache = stable;
                writeCache(context, stable, STABLE_CACHE_KEY);
            }
        }
        return catalog.withProfiles(preferCanary(catalog.profiles));
    }

    /** Saves the catalog's device table so later lookups do not need another download. */
    public static void remember(Context context, Catalog catalog) {
        if (context == null || catalog == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("default_codename",
                    catalog.defaultCodename == null ? "" : catalog.defaultCodename);
            obj.put("default_initial_sdk",
                    catalog.defaultInitialSdk == null ? "" : catalog.defaultInitialSdk);
            obj.put("tablet_codename",
                    catalog.tabletCodename == null ? "" : catalog.tabletCodename);
            obj.put("generation_order", new JSONArray(catalog.generationOrder));
            obj.put("picker_order", new JSONArray(catalog.pickerOrder));
            obj.put("models", new JSONObject(catalog.models));
            obj.put("initial_sdk", new JSONObject(catalog.initialSdk));
            Settings.Secure.putString(context.getContentResolver(), META_KEY, obj.toString());
        } catch (Exception e) {
            Log.w(TAG, "Catalog table write failed: " + e.getMessage());
        }
    }

    private static Catalog readTable(Context context) {
        if (context == null) return null;
        try {
            String json = Settings.Secure.getString(context.getContentResolver(), META_KEY);
            if (json == null || json.isEmpty()) return null;
            return tableFrom(new JSONObject(json), Collections.emptyList());
        } catch (Exception e) {
            Log.w(TAG, "Catalog table read failed: " + e.getMessage());
            return null;
        }
    }

    public static final class Catalog {
        public final String defaultCodename;
        public final String defaultInitialSdk;
        public final String tabletCodename;
        public final List<String> generationOrder;
        public final List<String> pickerOrder;
        public final Map<String, String> models;
        public final Map<String, String> initialSdk;
        public final List<PixelProfile> profiles;

        public Catalog(String defaultCodename, String defaultInitialSdk, String tabletCodename,
                List<String> generationOrder, List<String> pickerOrder,
                Map<String, String> models, Map<String, String> initialSdk,
                List<PixelProfile> profiles) {
            this.defaultCodename = defaultCodename;
            this.defaultInitialSdk = defaultInitialSdk;
            this.tabletCodename = tabletCodename;
            this.generationOrder = generationOrder;
            this.pickerOrder = pickerOrder;
            this.models = models;
            this.initialSdk = initialSdk;
            this.profiles = profiles;
        }

        Catalog withProfiles(List<PixelProfile> profiles) {
            return new Catalog(defaultCodename, defaultInitialSdk, tabletCodename,
                    generationOrder, pickerOrder, models, initialSdk, profiles);
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
        Catalog table = tableFrom(root, Collections.emptyList());
        List<PixelProfile> profiles = new ArrayList<>();
        addCatalogEntries(profiles, canary, now, table);
        addCatalogEntries(profiles, stable, now, table);
        if (profiles.isEmpty()) throw new IllegalArgumentException("catalog has no profiles");
        String defaultCodename = null;
        if (table.defaultCodename != null) {
            for (PixelProfile profile : profiles) {
                if (table.defaultCodename.equals(profile.codename)) {
                    defaultCodename = table.defaultCodename;
                    break;
                }
            }
        }
        return new Catalog(defaultCodename, table.defaultInitialSdk, table.tabletCodename,
                table.generationOrder, table.pickerOrder, table.models, table.initialSdk,
                profiles);
    }

    private static Catalog tableFrom(JSONObject root, List<PixelProfile> profiles) {
        String defaultInitial = root.optString("default_initial_sdk", "").trim();
        if (!defaultInitial.matches("\\d{1,3}")) defaultInitial = null;
        String tablet = codenameOrNull(root.optString("tablet_codename", ""));
        String preferred = codenameOrNull(root.optString("default_codename", ""));
        return new Catalog(preferred, defaultInitial, tablet,
                readCodenameList(root, "generation_order"),
                readCodenameList(root, "picker_order"),
                readStringMap(root, "models", false),
                readStringMap(root, "initial_sdk", true),
                profiles);
    }

    private static String codenameOrNull(String raw) {
        String name = raw == null ? "" : raw.trim();
        return isCodename(name) ? name : null;
    }

    private static List<String> readCodenameList(JSONObject root, String key) {
        JSONArray arr = root.optJSONArray(key);
        if (arr == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.length() && out.size() < CATALOG_MAX_NAMES; i++) {
            String name = codenameOrNull(arr.optString(i, ""));
            if (name != null) out.add(name);
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, String> readStringMap(JSONObject root, String key, boolean sdk) {
        JSONObject obj = root.optJSONObject(key);
        if (obj == null) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        JSONArray names = obj.names();
        if (names == null) return Collections.emptyMap();
        for (int i = 0; i < names.length() && out.size() < CATALOG_MAX_NAMES; i++) {
            String name = codenameOrNull(names.optString(i, ""));
            if (name == null) continue;
            String value = obj.optString(name, "").trim();
            if (sdk) {
                if (value.matches("\\d{1,3}")) out.put(name, value);
            } else if (!value.isEmpty() && value.length() <= 40) {
                out.put(name, value);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static boolean isCodename(String name) {
        if (name == null || name.isEmpty() || name.length() > 40) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private static void addCatalogEntries(List<PixelProfile> profiles, JSONArray arr, long now,
            Catalog table) {
        if (arr == null) return;
        for (int i = 0; i < arr.length() && profiles.size() < CATALOG_MAX_PROFILES; i++) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null) continue;
            PixelProfile profile = profileFromCatalog(entry, now, table);
            if (profile != null) profiles.add(profile);
        }
    }

    private static PixelProfile profileFromCatalog(JSONObject entry, long now, Catalog table) {
        String codename = entry.optString("codename", "").trim();
        String fingerprint = entry.optString("fingerprint", "").trim();
        if (codename.isEmpty() || !isValidFingerprint(fingerprint)) return null;
        String model = entry.optString("model", "").trim();
        if (model.isEmpty()) {
            String named = table.models.get(codename);
            model = named == null ? codename : named;
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
        if (!initialSdk.matches("\\d{1,3}")) initialSdk = table.initialSdk.get(device);
        if (initialSdk == null) initialSdk = table.defaultInitialSdk;
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
