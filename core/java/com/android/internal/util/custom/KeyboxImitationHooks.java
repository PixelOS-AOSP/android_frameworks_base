/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyPurpose;
import android.os.Binder;
import android.security.KeyStore2;
import android.security.KeyStoreException;
import android.system.keystore2.KeyMetadata;
import android.util.Log;

import com.android.internal.util.custom.KeyboxChainGenerator.KeyGenParameters;

import java.io.ByteArrayInputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.List;

/**
 * @hide
 */
public class KeyboxImitationHooks {
    private static final String TAG = "KeyboxImitationHooks";

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
}
