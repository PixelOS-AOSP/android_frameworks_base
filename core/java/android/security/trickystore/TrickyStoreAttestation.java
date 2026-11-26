/*
 * Copyright (C) 2026 The PixelOS Project
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

package android.security.trickystore;

/**
 * @hide
 */
public final class TrickyStoreAttestation {
    /** Settings.System key written by the Attestation switch. */
    public static final String ENABLED_SETTING = "spoof_trickystore_enabled";

    /**
     * One user's Settings.System value. Throw when the lookup itself fails.
     * @hide
     */
    public interface SettingReader {
        String get(int userId);
    }

    private TrickyStoreAttestation() {}

    /**
     * Unset stays enabled. Explicit {@code 0} is off. A failed lookup is off.
     * @hide
     */
    public static boolean isEnabled(String rawValue, boolean lookupSucceeded) {
        if (!lookupSucceeded) {
            return false;
        }
        if (rawValue == null) {
            return true;
        }
        return !"0".equals(rawValue);
    }

    /** @hide */
    public static boolean isEnabled(SettingReader reader, int userId) {
        if (reader == null) {
            return false;
        }
        try {
            return isEnabled(reader.get(userId), true);
        } catch (Exception e) {
            return false;
        }
    }
}
