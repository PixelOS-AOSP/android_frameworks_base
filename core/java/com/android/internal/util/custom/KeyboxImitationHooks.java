/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.app.ActivityThread;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.android.internal.util.PropImitationHooks;

import java.security.cert.Certificate;
import java.util.Map;
import java.util.Set;

/**
 * @hide
 */
public class KeyboxImitationHooks {
    private static final String TAG = "KeyboxImitationHooks";

    // Wallet attests as itself. Play Integrity attests inside Play Services.
    private static final Set<String> ATTESTATION_PACKAGES = Set.of(
            "com.android.vending",
            "com.google.android.gsf",
            "com.google.android.gms",
            "com.google.android.contactkeys",
            "com.google.android.ims",
            "com.google.android.safetycore",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user");

    private static final ThreadLocal<Boolean> sInHack =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private KeyboxImitationHooks() {}

    /**
     * Returns a keybox chain for Play Integrity packages. The KeyMint key is unchanged.
     * Any failure returns the original chain.
     */
    public static Certificate[] hackCertificateChain(Certificate[] chain) {
        if (chain == null || chain.length == 0 || sInHack.get()) {
            return chain;
        }
        sInHack.set(Boolean.TRUE);
        try {
            if (!shouldHackCaller() || !KeyProviderManager.isKeyboxAvailable()) {
                return chain;
            }
            Context context = ActivityThread.currentApplication();
            if (context == null) {
                return chain;
            }
            Map<String, String> props = PropImitationHooks.getCertifiedProps(context);
            if (props.isEmpty()) {
                return chain;
            }
            Certificate[] hacked = KeyboxChainGenerator.hackCertificateChain(chain, props);
            return hacked != null ? hacked : chain;
        } catch (Exception e) {
            Log.e(TAG, "Failed to hack certificate chain", e);
            return chain;
        } finally {
            sInHack.set(Boolean.FALSE);
        }
    }

    private static boolean shouldHackCaller() {
        String process = ActivityThread.currentProcessName();
        if (Process.isIsolated()) {
            return process != null && process.startsWith("com.google.android.gms");
        }
        try {
            String[] packages = ActivityThread.getPackageManager().getPackagesForUid(Process.myUid());
            if (packages != null) {
                for (String pkg : packages) {
                    if (ATTESTATION_PACKAGES.contains(pkg)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve attestation packages", e);
        }
        String current = ActivityThread.currentPackageName();
        return current != null && ATTESTATION_PACKAGES.contains(current);
    }
}
