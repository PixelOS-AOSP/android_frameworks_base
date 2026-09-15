/*
 * Stub created during reverse-engineering of vendorImplPrebuilt.jar.
 * Original class co.aospa.sense.util.PreferenceHelper was NOT inside the prebuilt jar
 * (it lives in the co.aospa.sense app), but co.aospa.sense.vendor.impl.FacePPImpl
 * requires it:
 *   - new PreferenceHelper(Context)
 *   - String getStringValueByKey(String key)   // used for "sdk_version"
 *   - void saveStringValue(String key, String value)
 *
 * This SharedPreferences-backed implementation preserves that contract so the
 * reverse-engineered FacePPImpl compiles and behaves identically (empty default
 * forces first-run upgrade/restore, matching the original SDK_VERSION="1" check).
 * Original package co.aospa.sense.util preserved as requested.
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 */

package co.aospa.sense.util;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceHelper {
    private static final String PREFS_NAME = "faceunlock_prefs";

    private final SharedPreferences mPrefs;

    public PreferenceHelper(Context context) {
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getStringValueByKey(String key) {
        return mPrefs.getString(key, "");
    }

    public void saveStringValue(String key, String value) {
        mPrefs.edit().putString(key, value).apply();
    }
}
