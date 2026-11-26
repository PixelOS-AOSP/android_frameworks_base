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

import org.junit.Test;

/**
 * Host-side coverage for the Attestation switch. The keystore hooks and
 * SpoofManager both call {@link TrickyStoreAttestation} on every request.
 */
public final class TrickyStoreAttestationTest {
    public static void main(String[] args) {
        TrickyStoreAttestationTest test = new TrickyStoreAttestationTest();
        test.offColdStart();
        test.onToOffInRunningReader();
        test.failedLookup();
        test.unsetStaysEnabled();
        test.readsTheRequestedUser();
        System.out.println("TrickyStoreAttestationTest ok");
    }

    @Test
    public void offColdStart() {
        TrickyStoreAttestation.SettingReader reader = userId -> {
            if (userId != 0) {
                throw new AssertionError("cold start user");
            }
            return "0";
        };
        expect(!TrickyStoreAttestation.isEnabled(reader, 0), "off cold start");
        expect(!TrickyStoreAttestation.isEnabled("0", true), "off raw value");
    }

    @Test
    public void onToOffInRunningReader() {
        String[] value = new String[] {"1"};
        TrickyStoreAttestation.SettingReader reader = userId -> value[0];
        expect(TrickyStoreAttestation.isEnabled(reader, 0), "on before toggle");
        value[0] = "0";
        expect(!TrickyStoreAttestation.isEnabled(reader, 0), "off after toggle");
    }

    @Test
    public void failedLookup() {
        TrickyStoreAttestation.SettingReader reader = userId -> {
            throw new SecurityException("settings unavailable");
        };
        expect(!TrickyStoreAttestation.isEnabled(reader, 0), "reader threw");
        expect(!TrickyStoreAttestation.isEnabled(
                (TrickyStoreAttestation.SettingReader) null, 0), "no reader");
        expect(!TrickyStoreAttestation.isEnabled("1", false), "lookup failed");
    }

    @Test
    public void unsetStaysEnabled() {
        expect(TrickyStoreAttestation.isEnabled(
                (TrickyStoreAttestation.SettingReader) userId -> null, 0), "unset");
        expect(TrickyStoreAttestation.isEnabled("1", true), "explicit on");
    }

    @Test
    public void readsTheRequestedUser() {
        int[] seen = new int[] {-1};
        TrickyStoreAttestation.SettingReader reader = userId -> {
            seen[0] = userId;
            return "0";
        };
        expect(!TrickyStoreAttestation.isEnabled(reader, 10), "user 10 off");
        expect(seen[0] == 10, "reader saw user 10");
    }

    private static void expect(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
    }
}
