/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.EcCurve;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyPurpose;
import android.hardware.security.keymint.Tag;
import android.os.Process;
import android.security.KeyStore2;
import android.security.KeyStoreException;
import android.security.keymaster.KeymasterDefs;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;
import android.system.keystore2.ResponseCode;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.PropImitationHooks;
import com.android.internal.util.custom.KeyboxChainGenerator.KeyGenParameters;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public class KeyboxImitationHooks {
    private static final String TAG = "KeyboxImitationHooks";

    // KeyMint rejects longer challenges, but it never sees the challenge on the keybox path.
    private static final int MAX_ATTESTATION_CHALLENGE_LENGTH = 128;

    /**
     * Returns the arguments KeyMint should generate the key with when the keybox attests it,
     * or null to leave generation and attestation entirely with the backend.
     */
    @Nullable
    public static Collection<KeyParameter> prepareGenerateKeyParameters(
            @Nullable KeyDescriptor attestationKey, Collection<KeyParameter> args)
            throws KeyStoreException {
        if (attestationKey != null) {
            return null;
        }

        byte[] challenge = null;
        try {
            boolean canSign = false;
            int algorithm = -1;
            for (KeyParameter parameter : args) {
                switch (parameter.tag) {
                    case Tag.ATTESTATION_CHALLENGE -> challenge = parameter.value.getBlob();
                    case Tag.PURPOSE ->
                            canSign |= parameter.value.getKeyPurpose() == KeyPurpose.SIGN;
                    case Tag.ALGORITHM -> algorithm = parameter.value.getAlgorithm();
                    case Tag.EC_CURVE -> {
                        if (parameter.value.getEcCurve() == EcCurve.CURVE_25519) {
                            return null;
                        }
                    }
                    // Keystore2 checks permissions for these before KeyMint, and the keybox
                    // certificate does not carry them. Keep the backend behaviour.
                    case Tag.ATTESTATION_ID_SERIAL, Tag.ATTESTATION_ID_IMEI,
                            Tag.ATTESTATION_ID_SECOND_IMEI, Tag.ATTESTATION_ID_MEID,
                            Tag.DEVICE_UNIQUE_ATTESTATION, Tag.INCLUDE_UNIQUE_ID -> {
                        return null;
                    }
                    default -> {
                    }
                }
            }
            if (challenge == null || !canSign
                    || (algorithm != Algorithm.EC && algorithm != Algorithm.RSA)
                    || !KeyProviderManager.isKeyboxAvailable()) {
                return null;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Leaving key generation with the backend", e);
            return null;
        }

        if (challenge.length > MAX_ATTESTATION_CHALLENGE_LENGTH) {
            throw new KeyStoreException(KeymasterDefs.KM_ERROR_INVALID_INPUT_LENGTH,
                    "Attestation challenge too large");
        }

        List<KeyParameter> filtered = new ArrayList<>(args.size());
        for (KeyParameter parameter : args) {
            if (!isBackendAttestationTag(parameter.tag)) {
                filtered.add(parameter);
            }
        }
        return filtered;
    }

    public static void updateCertificateChain(KeyMetadata metadata,
            Collection<KeyParameter> args) throws KeyStoreException {
        KeyMetadata certificates = new KeyMetadata();
        try {
            KeyGenParameters params = new KeyGenParameters(args.toArray(new KeyParameter[0]));
            applyCertifiedAttestationIds(params);

            Certificate certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(metadata.certificate));
            // Keystore2 attests the application of the process that called it.
            List<Certificate> chain = KeyboxChainGenerator.generateCertChain(
                    Process.myUid(), certificate.getPublicKey(), params);
            if (chain == null || chain.isEmpty()) {
                throw new IllegalStateException("Keybox certificate chain was not generated");
            }

            KeyboxUtils.putCertificateChain(certificates, chain.toArray(new Certificate[0]));
        } catch (Exception e) {
            Log.e(TAG, "Keybox certificate preparation failed", e);
            // KeyMint generated this key without the caller's challenge, so its certificate
            // cannot answer the attestation request. Fail rather than return it as success.
            throw new KeyStoreException(ResponseCode.SYSTEM_ERROR,
                    "Keybox certificate preparation failed");
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
                || tag == Tag.ATTESTATION_ID_MANUFACTURER
                || tag == Tag.ATTESTATION_ID_MODEL;
    }

    private static void applyCertifiedAttestationIds(KeyGenParameters params) {
        if (params.brand == null && params.device == null && params.product == null
                && params.manufacturer == null && params.model == null) {
            return;
        }

        Context context = ActivityThread.currentApplication();
        if (context == null) {
            return;
        }
        // Attest the identity GMS sees through PropImitationHooks, for the requested IDs only.
        Map<String, String> props = PropImitationHooks.getCertifiedProps(context);
        params.brand = getCertifiedId(props, "BRAND", params.brand);
        params.device = getCertifiedId(props, "DEVICE", params.device);
        params.product = getCertifiedId(props, "PRODUCT", params.product);
        params.manufacturer = getCertifiedId(props, "MANUFACTURER", params.manufacturer);
        params.model = getCertifiedId(props, "MODEL", params.model);
    }

    private static byte[] getCertifiedId(Map<String, String> props, String field,
            byte[] requested) {
        if (requested == null) {
            return null;
        }
        String value = props.get(field);
        return TextUtils.isEmpty(value) ? requested : value.getBytes(StandardCharsets.UTF_8);
    }
}
