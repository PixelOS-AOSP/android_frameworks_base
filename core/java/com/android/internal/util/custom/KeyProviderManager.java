/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.app.ActivityThread;
import android.content.Context;
import android.hardware.security.keymint.Algorithm;
import android.provider.Settings;
import android.util.Log;
import android.util.Xml;

import com.android.internal.R;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manager class for handling keybox providers.
 * @hide
 */
public final class KeyProviderManager {
    private static final String TAG = "KeyProviderManager";

    private KeyProviderManager() {}

    public static IKeyboxProvider getProvider() {
        return new DefaultKeyboxProvider();
    }

    public static boolean isKeyboxAvailable() {
        return getProvider().hasKeybox();
    }

    public static boolean isKeyboxAvailable(int algorithm) {
        IKeyboxProvider provider = getProvider();
        return switch (algorithm) {
            case Algorithm.EC -> provider.getEcPrivateKey() != null
                    && provider.getEcCertificateChain().length > 0;
            case Algorithm.RSA -> provider.getRsaPrivateKey() != null
                    && provider.getRsaCertificateChain().length > 0;
            default -> false;
        };
    }

    public static boolean isValidKeyboxXml(String xml) {
        try {
            return !parseKeyboxXml(xml).isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "Invalid keybox XML", e);
            return false;
        }
    }

    private static Map<String, String> parseKeyboxXml(String xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        Map<String, String> keys = new HashMap<>();
        Map<String, String> currentKey = new HashMap<>();
        String algorithm = null;
        int certificateCount = 0;
        int declaredKeyboxes = -1;
        int keyboxCount = 0;

        for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT;
                event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case "NumberOfKeyboxes":
                        declaredKeyboxes = Integer.parseInt(parser.nextText().trim());
                        break;
                    case "Keybox":
                        keyboxCount++;
                        break;
                    case "Key":
                        String value = parser.getAttributeValue(null, "algorithm");
                        algorithm = "ecdsa".equalsIgnoreCase(value) ? "EC"
                                : "rsa".equalsIgnoreCase(value) ? "RSA" : null;
                        currentKey.clear();
                        certificateCount = 0;
                        break;
                    case "PrivateKey":
                    case "Certificate":
                        if (algorithm == null) {
                            break;
                        }
                        if (!"pem".equalsIgnoreCase(parser.getAttributeValue(null, "format"))) {
                            throw new IllegalArgumentException("Unsupported keybox format");
                        }
                        String suffix = "PrivateKey".equals(parser.getName())
                                ? ".PRIV" : ".CERT_" + ++certificateCount;
                        currentKey.put(algorithm + suffix, parser.nextText().trim());
                        break;
                }
            } else if (event == XmlPullParser.END_TAG && "Key".equals(parser.getName())) {
                // Keep each private key and its chain together; the first complete entry wins.
                if (algorithm != null && hasKey(currentKey, algorithm)
                        && currentKey.values().stream().noneMatch(String::isEmpty)
                        && !hasKey(keys, algorithm)) {
                    keys.putAll(currentKey);
                }
                algorithm = null;
            }
        }
        if (declaredKeyboxes < 1 || declaredKeyboxes != keyboxCount) {
            throw new IllegalArgumentException("Invalid NumberOfKeyboxes");
        }
        return keys;
    }

    private static boolean hasKey(Map<String, String> keys, String algorithm) {
        return keys.containsKey(algorithm + ".PRIV")
                && keys.containsKey(algorithm + ".CERT_1");
    }

    private static class DefaultKeyboxProvider implements IKeyboxProvider {
        private final Map<String, String> keyboxData = new HashMap<>();

        private DefaultKeyboxProvider() {
            Context context = getApplicationContext();
            if (context == null) {
                Log.e(TAG, "Failed to get application context");
                return;
            }

            if (!loadFromXmlSetting(context)) {
                loadFromConfigArray(context);
            }
        }

        private boolean loadFromXmlSetting(Context ctx) {
            try {
                String xml = Settings.Secure.getString(ctx.getContentResolver(),
                        Settings.Secure.KEYBOX_DATA);
                if (xml == null || xml.trim().isEmpty()) {
                    return false;
                }
                keyboxData.putAll(parseKeyboxXml(xml));
                if (!hasKeybox()) {
                    return false;
                }
                Log.i(TAG, "Loaded keybox from XML setting");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "XML keybox load failed", e);
                return false;
            }
        }

        private void loadFromConfigArray(Context ctx) {
            for (String entry : ctx.getResources().getStringArray(R.array.config_certifiedKeybox)) {
                String[] parts = entry.split(":", 2);
                if (parts.length == 2) {
                    keyboxData.put(parts[0], parts[1]);
                }
            }

            if (!hasKeybox()) {
                Log.w(TAG, "Incomplete keybox provided by overlays");
            }
        }

        private static Context getApplicationContext() {
            try {
                return ActivityThread.currentApplication().getApplicationContext();
            } catch (Exception e) {
                Log.e(TAG, "Error getting application context", e);
                return null;
            }
        }

        @Override
        public boolean hasKeybox() {
            return hasKey(keyboxData, "EC") || hasKey(keyboxData, "RSA");
        }

        @Override
        public String getEcPrivateKey() {
            return keyboxData.get("EC.PRIV");
        }

        @Override
        public String getRsaPrivateKey() {
            return keyboxData.get("RSA.PRIV");
        }

        @Override
        public String[] getEcCertificateChain() {
            return getCertificateChain("EC");
        }

        @Override
        public String[] getRsaCertificateChain() {
            return getCertificateChain("RSA");
        }

        private String[] getCertificateChain(String prefix) {
            String certificatePrefix = prefix + ".CERT_";
            List<String> certificateKeys = new ArrayList<>();
            for (String key : keyboxData.keySet()) {
                if (key.startsWith(certificatePrefix)) {
                    certificateKeys.add(key);
                }
            }
            certificateKeys.sort(Comparator.comparingInt(
                    key -> Integer.parseInt(key.substring(certificatePrefix.length()))));
            return certificateKeys.stream().map(keyboxData::get).toArray(String[]::new);
        }
    }
}
