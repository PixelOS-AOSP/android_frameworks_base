/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyPurpose;
import android.hardware.security.keymint.Tag;
import android.os.Binder;
import android.security.KeyStore2;
import android.security.KeyStoreException;
import android.system.keystore2.KeyMetadata;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.custom.KeyboxChainGenerator.KeyGenParameters;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @hide
 */
public class KeyboxImitationHooks {
    private static final String TAG = "KeyboxImitationHooks";

    public static Collection<KeyParameter> prepareGenerateKeyParameters(
            Collection<KeyParameter> args) {
        if (!KeyProviderManager.isKeyboxAvailable()) {
            return args;
        }

        List<KeyParameter> filtered = new ArrayList<>(args.size());
        boolean stripped = false;
        for (KeyParameter parameter : args) {
            if (isBackendAttestationTag(parameter.tag)) {
                stripped = true;
                continue;
            }
            filtered.add(parameter);
        }
        if (stripped) {
            Log.d(TAG, "Stripping attestation tags from backend generateKey");
            return filtered;
        }
        return args;
    }

    public static void updateCertificateChain(KeyMetadata metadata,
            Collection<KeyParameter> args) throws KeyStoreException {
        KeyMetadata certificates = new KeyMetadata();
        try {
            if (!KeyProviderManager.isKeyboxAvailable()) {
                return;
            }

            KeyGenParameters params = new KeyGenParameters(args.toArray(new KeyParameter[0]));
            if (!params.purpose.contains(KeyPurpose.SIGN)
                    || (params.algorithm != Algorithm.EC && params.algorithm != Algorithm.RSA)) {
                return;
            }

            applyCertifiedAttestationIds(params);

            Certificate certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(metadata.certificate));
            List<Certificate> chain = KeyboxChainGenerator.generateCertChain(
                    Binder.getCallingUid(), certificate.getPublicKey(), params);
            if (chain == null || chain.isEmpty()) {
                return;
            }

            KeyboxUtils.putCertificateChain(certificates, chain.toArray(new Certificate[0]));
        } catch (Exception e) {
            Log.w(TAG, "Keeping backend certificates after chain preparation failed", e);
            return;
        }

        // Use the backend key ID, not an alias that may have been rebound. Propagate storage
        // failures so the caller can clean up the failed generation instead of using stale data.
        KeyStore2.getInstance().updateSubcomponents(metadata.key,
                certificates.certificate, certificates.certificateChain);
        metadata.certificate = certificates.certificate;
        metadata.certificateChain = certificates.certificateChain;
    }

    private static boolean isBackendAttestationTag(int tag) {
        return tag == Tag.ATTESTATION_CHALLENGE
                || tag == Tag.ATTESTATION_APPLICATION_ID
                || tag == Tag.ATTESTATION_ID_BRAND
                || tag == Tag.ATTESTATION_ID_DEVICE
                || tag == Tag.ATTESTATION_ID_PRODUCT
                || tag == Tag.ATTESTATION_ID_SERIAL
                || tag == Tag.ATTESTATION_ID_IMEI
                || tag == Tag.ATTESTATION_ID_MEID
                || tag == Tag.ATTESTATION_ID_MANUFACTURER
                || tag == Tag.ATTESTATION_ID_MODEL
                || tag == Tag.ATTESTATION_ID_SECOND_IMEI
                || tag == Tag.DEVICE_UNIQUE_ATTESTATION
                || tag == Tag.INCLUDE_UNIQUE_ID;
    }

    private static void applyCertifiedAttestationIds(KeyGenParameters params) {
        if (params.brand == null && params.device == null && params.model == null
                && params.product == null && params.manufacturer == null) {
            return;
        }

        try {
            Context context = ActivityThread.currentApplication();
            if (context == null) {
                return;
            }
            Resources res = context.getResources();
            String fingerprint = res.getString(R.string.cert_fp);
            if (fingerprint == null || fingerprint.isEmpty()) {
                return;
            }
            String[] sections = fingerprint.split("/");
            if (sections.length < 2) {
                return;
            }
            String device = res.getString(R.string.cert_device);
            String model = res.getString(R.string.cert_model);
            String manufacturer = res.getString(R.string.cert_manufacturer);
            params.brand = sections[0].getBytes(StandardCharsets.UTF_8);
            params.product = sections[1].getBytes(StandardCharsets.UTF_8);
            params.device = device.getBytes(StandardCharsets.UTF_8);
            params.model = model.getBytes(StandardCharsets.UTF_8);
            params.manufacturer = manufacturer.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Unable to apply certified attestation IDs", e);
        }
    }
}
