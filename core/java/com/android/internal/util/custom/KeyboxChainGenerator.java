/*
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.KeyOrigin;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.Tag;
import android.os.Build;
import android.os.SystemProperties;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.DERNull;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERSet;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import libcore.util.HexEncoding;

/**
 * @hide
 */
public final class KeyboxChainGenerator {

    private static final String TAG = "KeyboxChainGenerator";

    private static final int ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX = 0;
    private static final int ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX = 1;
    private static final int ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX = 0;
    private static final int ATTESTATION_PACKAGE_INFO_VERSION_INDEX = 1;

    public static List<Certificate> generateCertChain(int uid, byte[] encodedCertificate,
            KeyGenParameters params) throws Exception {
        X509CertificateHolder certificate = new X509CertificateHolder(encodedCertificate);
        String algorithm = params.algorithm == Algorithm.EC
                ? KeyProperties.KEY_ALGORITHM_EC : KeyProperties.KEY_ALGORITHM_RSA;
        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                KeyboxUtils.getCertificateHolder(algorithm).getSubject(),
                certificate.getSerialNumber(), certificate.getNotBefore(),
                certificate.getNotAfter(), certificate.getSubject(),
                certificate.getSubjectPublicKeyInfo());

        Extension keyUsage = certificate.getExtension(Extension.keyUsage);
        if (keyUsage != null) {
            certBuilder.addExtension(keyUsage);
        }
        certBuilder.addExtension(createExtension(params, uid));

        ContentSigner contentSigner = new JcaContentSignerBuilder(
                params.algorithm == Algorithm.EC ? "SHA256withECDSA" : "SHA256withRSA")
                .build(KeyboxUtils.getPrivateKey(algorithm));
        Certificate leaf = KeyboxUtils.getCertificateFromHolder(certBuilder.build(contentSigner));
        List<Certificate> chain = KeyboxUtils.getCertificateChain(algorithm);
        chain.add(0, leaf);
        return chain;
    }

    private static ASN1Encodable[] fromIntList(List<Integer> list) {
        ASN1Encodable[] result = new ASN1Encodable[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = new ASN1Integer(list.get(i));
        }
        return result;
    }

    private static Extension createExtension(KeyGenParameters params, int uid) throws Exception {
        ASN1Encodable[] rootOfTrustEncodables = {
                new DEROctetString(getVerifiedBootKey()),
                ASN1Boolean.TRUE,
                new ASN1Enumerated(0),
                new DEROctetString(decodeHexProperty("ro.boot.vbmeta.digest"))
        };

        ASN1Sequence rootOfTrustSeq = new DERSequence(rootOfTrustEncodables);

        // AuthorizationList is a SEQUENCE, so entries must follow schema order.
        List<ASN1Encodable> teeEnforced = new ArrayList<>();
        if (!params.purpose.isEmpty()) {
            teeEnforced.add(new DERTaggedObject(true, 1, new DERSet(fromIntList(params.purpose))));
        }
        teeEnforced.add(new DERTaggedObject(true, 2, new ASN1Integer(params.algorithm)));
        teeEnforced.add(new DERTaggedObject(true, 3, new ASN1Integer(params.keySize)));
        if (!params.digest.isEmpty()) {
            teeEnforced.add(new DERTaggedObject(true, 5, new DERSet(fromIntList(params.digest))));
        }
        if (!params.padding.isEmpty()) {
            teeEnforced.add(new DERTaggedObject(true, 6, new DERSet(fromIntList(params.padding))));
        }
        if (params.algorithm == Algorithm.EC) {
            teeEnforced.add(new DERTaggedObject(true, 10, new ASN1Integer(params.ecCurve)));
        } else if (params.algorithm == Algorithm.RSA) {
            teeEnforced.add(new DERTaggedObject(true, 200,
                    new ASN1Integer(params.rsaPublicExponent)));
        }
        if (params.noAuthRequired) {
            teeEnforced.add(new DERTaggedObject(true, 503, DERNull.INSTANCE));
        }
        teeEnforced.add(new DERTaggedObject(true, 702, new ASN1Integer(KeyOrigin.GENERATED)));
        teeEnforced.add(new DERTaggedObject(true, 704, rootOfTrustSeq));
        teeEnforced.add(new DERTaggedObject(true, 705, new ASN1Integer(getOsVersion())));
        teeEnforced.add(new DERTaggedObject(true, 706, new ASN1Integer(getPatchLevel())));
        addAttestationId(teeEnforced, 710, params.brand);
        addAttestationId(teeEnforced, 711, params.device);
        addAttestationId(teeEnforced, 712, params.product);
        addAttestationId(teeEnforced, 716, params.manufacturer);
        addAttestationId(teeEnforced, 717, params.model);
        teeEnforced.add(new DERTaggedObject(true, 718, new ASN1Integer(getPatchLevelLong())));
        teeEnforced.add(new DERTaggedObject(true, 719, new ASN1Integer(getPatchLevelLong())));

        ASN1Encodable[] softwareEnforced = {
                new DERTaggedObject(true, 701, new ASN1Integer(System.currentTimeMillis())),
                new DERTaggedObject(true, 709, createApplicationId(uid))
        };

        ASN1OctetString keyDescriptionOctetStr = getAsn1OctetString(
                teeEnforced.toArray(new ASN1Encodable[0]), softwareEnforced, params);

        return new Extension(new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"), false, keyDescriptionOctetStr);
    }

    private static int getOsVersion() {
        String release = Build.VERSION.RELEASE;
        int major = 0, minor = 0, patch = 0;

        String[] parts = release.split("\\.");
        if (parts.length > 0) major = Integer.parseInt(parts[0]);
        if (parts.length > 1) minor = Integer.parseInt(parts[1]);
        if (parts.length > 2) patch = Integer.parseInt(parts[2]);

        return major * 10000 + minor * 100 + patch;
    }

    private static int getPatchLevel() {
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, false);
    }

    private static int getPatchLevelLong() {
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, true);
    }

    private static byte[] getVerifiedBootKey() throws Exception {
        byte[] fromProp = decodeHexProperty("ro.boot.vbmeta.public_key_digest");
        if (fromProp.length > 0) {
            return fromProp;
        }

        // Keep the fallback stable across processes and boots.
        String algorithm = KeyProviderManager.isKeyboxAvailable(Algorithm.EC)
                ? KeyProperties.KEY_ALGORITHM_EC : KeyProperties.KEY_ALGORITHM_RSA;
        Certificate issuer = KeyboxUtils.getCertificateChain(algorithm).get(0);
        return MessageDigest.getInstance("SHA-256").digest(issuer.getEncoded());
    }

    private static void addAttestationId(List<ASN1Encodable> list, int tag, byte[] value) {
        if (value != null) {
            list.add(new DERTaggedObject(true, tag, new DEROctetString(value)));
        }
    }

    private static byte[] decodeHexProperty(String name) {
        String value = SystemProperties.get(name, "");
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        try {
            return HexEncoding.decode(value, false);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static int convertPatchLevel(String patchLevel, boolean longFormat) {
        try {
            String[] parts = patchLevel.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            if (longFormat) {
                int day = Integer.parseInt(parts[2]);
                return year * 10000 + month * 100 + day;
            } else {
                return year * 100 + month;
            }
        } catch (Exception e) {
            Log.e(TAG, "Invalid patch level: " + patchLevel, e);
            return 202404;
        }
    }

    private static ASN1OctetString getAsn1OctetString(ASN1Encodable[] teeEnforcedEncodables, ASN1Encodable[] softwareEnforcedEncodables, KeyGenParameters params) throws IOException {
        ASN1Integer attestationVersion = new ASN1Integer(100);
        ASN1Enumerated attestationSecurityLevel = new ASN1Enumerated(1);
        ASN1Integer keymasterVersion = new ASN1Integer(100);
        ASN1Enumerated keymasterSecurityLevel = new ASN1Enumerated(1);
        ASN1OctetString attestationChallenge = new DEROctetString(params.attestationChallenge);
        ASN1OctetString uniqueId = new DEROctetString(new byte[0]);
        ASN1Encodable softwareEnforced = new DERSequence(softwareEnforcedEncodables);
        ASN1Sequence teeEnforced = new DERSequence(teeEnforcedEncodables);

        ASN1Encodable[] keyDescriptionEncodables = {attestationVersion, attestationSecurityLevel, keymasterVersion,
                keymasterSecurityLevel, attestationChallenge, uniqueId, softwareEnforced, teeEnforced};

        ASN1Sequence keyDescriptionHackSeq = new DERSequence(keyDescriptionEncodables);

        return new DEROctetString(keyDescriptionHackSeq);
    }

    private static DEROctetString createApplicationId(int uid) throws Exception {
        Context context = ActivityThread.currentApplication();
        if (context == null) {
            throw new IllegalStateException("createApplicationId: context not available from ActivityThread!");
        }

        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            throw new IllegalStateException("createApplicationId: PackageManager not found!");
        }

        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            throw new IllegalStateException("No packages found for UID: " + uid);
        }

        int size = packages.length;
        ASN1Encodable[] packageInfoAA = new ASN1Encodable[size];
        Set<Digest> signatures = new HashSet<>();
        MessageDigest dg = MessageDigest.getInstance("SHA-256");

        for (int i = 0; i < size; i++) {
            String name = packages[i];
            PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
            ASN1Encodable[] arr = new ASN1Encodable[2];
            arr[ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX] =
                    new DEROctetString(name.getBytes(StandardCharsets.UTF_8));
            arr[ATTESTATION_PACKAGE_INFO_VERSION_INDEX] =
                    new ASN1Integer(info.getLongVersionCode());
            packageInfoAA[i] = new DERSequence(arr);

            if (info.signatures != null) {
                for (Signature s : info.signatures) {
                    if (s != null) signatures.add(new Digest(dg.digest(s.toByteArray())));
                }
            }
        }

        ASN1Encodable[] signaturesAA = new ASN1Encodable[signatures.size()];
        int i = 0;
        for (Digest d : signatures) {
            signaturesAA[i++] = new DEROctetString(d.digest);
        }

        ASN1Encodable[] applicationIdAA = new ASN1Encodable[2];
        applicationIdAA[ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX] =
                new DERSet(packageInfoAA);
        applicationIdAA[ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX] =
                new DERSet(signaturesAA);

        return new DEROctetString(new DERSequence(applicationIdAA).getEncoded());
    }

    record Digest(byte[] digest) {
        @Override
        public boolean equals(@Nullable Object o) {
            if (o instanceof Digest d)
                return Arrays.equals(digest, d.digest);
            return false;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(digest);
        }
    }

    public static class KeyGenParameters {
        public int keySize;
        public int algorithm;
        public BigInteger rsaPublicExponent = BigInteger.valueOf(65537);
        public int ecCurve;

        public List<Integer> purpose = new ArrayList<>();
        public List<Integer> digest = new ArrayList<>();
        public List<Integer> padding = new ArrayList<>();
        public boolean noAuthRequired;

        public byte[] attestationChallenge;
        public byte[] brand;
        public byte[] device;
        public byte[] product;
        public byte[] manufacturer;
        public byte[] model;

        public KeyGenParameters(KeyParameter[] params) {
            for (KeyParameter kp : params) {
                switch (kp.tag) {
                    case Tag.KEY_SIZE -> keySize = kp.value.getInteger();
                    case Tag.ALGORITHM -> algorithm = kp.value.getAlgorithm();
                    case Tag.RSA_PUBLIC_EXPONENT -> rsaPublicExponent = BigInteger.valueOf(kp.value.getLongInteger());
                    case Tag.EC_CURVE -> ecCurve = kp.value.getEcCurve();
                    case Tag.PURPOSE -> purpose.add(kp.value.getKeyPurpose());
                    case Tag.DIGEST -> digest.add(kp.value.getDigest());
                    case Tag.PADDING -> padding.add(kp.value.getPaddingMode());
                    case Tag.NO_AUTH_REQUIRED -> noAuthRequired = true;
                    case Tag.ATTESTATION_CHALLENGE -> attestationChallenge = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_BRAND -> brand = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_DEVICE -> device = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_PRODUCT -> product = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_MANUFACTURER -> manufacturer = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_MODEL -> model = kp.value.getBlob();
                }
            }
        }
    }
}
