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
import android.hardware.security.keymint.EcCurve;
import android.hardware.security.keymint.KeyOrigin;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.Tag;
import android.os.Binder;
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
import com.android.internal.org.bouncycastle.asn1.x500.X500Name;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.asn1.x509.KeyUsage;
import com.android.internal.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import com.android.internal.org.bouncycastle.asn1.x509.Time;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

/**
 * @hide
 */
public final class KeyboxChainGenerator {

    private static final String TAG = "KeyboxChainGenerator";

    private static final int ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX = 0;
    private static final int ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX = 1;
    private static final int ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX = 0;
    private static final int ATTESTATION_PACKAGE_INFO_VERSION_INDEX = 1;

    public static List<Certificate> generateCertChain(int uid, PublicKey publicKey,
            KeyGenParameters params) {
        try {
            X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                    KeyboxUtils.getCertificateHolder(
                            Objects.equals(params.algorithm, Algorithm.EC)
                                    ? KeyProperties.KEY_ALGORITHM_EC
                                    : KeyProperties.KEY_ALGORITHM_RSA
                    ).getSubject(),
                    params.certificateSerial,
                    new Time(params.certificateNotBefore),
                    new Time(params.certificateNotAfter),
                    params.certificateSubject,
                    SubjectPublicKeyInfo.getInstance(
                            ASN1Sequence.getInstance(publicKey.getEncoded())
                    )
            );

            KeyUsage keyUsage = new KeyUsage(KeyUsage.keyCertSign);
            certBuilder.addExtension(Extension.keyUsage, true, keyUsage);
            certBuilder.addExtension(createExtension(params, uid));

            ContentSigner contentSigner;
            if (Objects.equals(params.algorithm, Algorithm.EC)) {
                contentSigner = new JcaContentSignerBuilder("SHA256withECDSA").build(KeyboxUtils.getPrivateKey(KeyProperties.KEY_ALGORITHM_EC));
            } else {
                contentSigner = new JcaContentSignerBuilder("SHA256withRSA").build(KeyboxUtils.getPrivateKey(KeyProperties.KEY_ALGORITHM_RSA));
            }
            X509CertificateHolder certHolder = certBuilder.build(contentSigner);
            Certificate leaf = KeyboxUtils.getCertificateFromHolder(certHolder);
            List<Certificate> chain = KeyboxUtils.getCertificateChain(leaf.getPublicKey().getAlgorithm());
            chain.add(0, leaf);
            return chain;
        } catch (Throwable t) {
            Log.e(TAG, Log.getStackTraceString(t));
        }
        return null;
    }

    private static ASN1Encodable[] fromIntList(List<Integer> list) {
        ASN1Encodable[] result = new ASN1Encodable[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = new ASN1Integer(list.get(i));
        }
        return result;
    }

    private static Extension createExtension(KeyGenParameters params, int uid) {
        try {
            ASN1Encodable[] rootOfTrustEncodables = {
                    new DEROctetString(getVerifiedBootKey()),
                    ASN1Boolean.TRUE,
                    new ASN1Enumerated(0),
                    new DEROctetString(getVerifiedBootHash())
            };

            ASN1Sequence rootOfTrustSeq = new DERSequence(rootOfTrustEncodables);

            // AuthorizationList entries follow the tag order of the attestation schema, and only
            // describe what this key actually has.
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

            var AcreationDateTime = new ASN1Integer(System.currentTimeMillis());
            var AapplicationID = createApplicationId(uid);

            var creationDateTime = new DERTaggedObject(true, 701, AcreationDateTime);
            var applicationID = new DERTaggedObject(true, 709, AapplicationID);

            ASN1Encodable[] softwareEnforced = {creationDateTime, applicationID};

            ASN1OctetString keyDescriptionOctetStr = getAsn1OctetString(
                    teeEnforced.toArray(new ASN1Encodable[0]), softwareEnforced, params);

            return new Extension(new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"), false, keyDescriptionOctetStr);
        } catch (Throwable t) {
            Log.e(TAG, Log.getStackTraceString(t));
        }
        return null;
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

    private static byte[] getVerifiedBootHash() {
        // Duck Detector compares this OCTET STRING to ro.boot.vbmeta.digest.
        // Inventing a random hash while the property is empty is a hard mismatch.
        return decodeHexProperty("ro.boot.vbmeta.digest");
    }

    private static byte[] getVerifiedBootKey() throws Exception {
        byte[] fromProp = decodeHexProperty("ro.boot.vbmeta.public_key_digest");
        if (fromProp.length > 0) {
            return fromProp;
        }

        // Derive a stable value from the keybox so every app and boot attests the same key.
        // Apps cannot persist a random one: writing secure settings needs a system permission.
        Certificate issuer = KeyboxUtils.getCertificateChain(KeyProperties.KEY_ALGORITHM_EC).get(0);
        return MessageDigest.getInstance("SHA-256").digest(issuer.getEncoded());
    }

    private static void addAttestationId(List<ASN1Encodable> list, int tag, byte[] value) {
        if (value != null) {
            list.add(new DERTaggedObject(true, tag, new DEROctetString(value)));
        }
    }

    private static byte[] decodeHexProperty(String name) {
        String value = SystemProperties.get(name, "");
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if ((value.length() & 1) != 0) {
            return new byte[0];
        }
        try {
            byte[] out = new byte[value.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (NumberFormatException e) {
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
        byte[] challenge = params.attestationChallenge != null
                ? params.attestationChallenge : new byte[0];
        ASN1OctetString attestationChallenge = new DEROctetString(challenge);
        ASN1OctetString uniqueId = new DEROctetString(new byte[0]);
        ASN1Encodable softwareEnforced = new DERSequence(softwareEnforcedEncodables);
        ASN1Sequence teeEnforced = new DERSequence(teeEnforcedEncodables);

        ASN1Encodable[] keyDescriptionEncodables = {attestationVersion, attestationSecurityLevel, keymasterVersion,
                keymasterSecurityLevel, attestationChallenge, uniqueId, softwareEnforced, teeEnforced};

        ASN1Sequence keyDescriptionHackSeq = new DERSequence(keyDescriptionEncodables);

        return new DEROctetString(keyDescriptionHackSeq);
    }

    private static DEROctetString createApplicationId(int uid) throws Throwable {
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

            if (info != null && info.signatures != null) {
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
        // Keystore2 and KeyMint defaults for requests that leave these out.
        public BigInteger certificateSerial = BigInteger.ONE;
        public Date certificateNotBefore = new Date(0);
        public Date certificateNotAfter = new Date(253402300799000L);
        public X500Name certificateSubject = new X500Name("CN=Android Keystore Key");

        public BigInteger rsaPublicExponent = BigInteger.valueOf(65537);
        public int ecCurve;
        public String ecCurveName;

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

        public int securityLevel;

        public KeyGenParameters(KeyParameter[] params) {
            for (KeyParameter kp : params) {
                switch (kp.tag) {
                    case Tag.KEY_SIZE -> keySize = kp.value.getInteger();
                    case Tag.ALGORITHM -> algorithm = kp.value.getAlgorithm();
                    case Tag.CERTIFICATE_SERIAL -> certificateSerial = new BigInteger(kp.value.getBlob());
                    case Tag.CERTIFICATE_NOT_BEFORE -> certificateNotBefore = new Date(kp.value.getDateTime());
                    case Tag.CERTIFICATE_NOT_AFTER -> certificateNotAfter = new Date(kp.value.getDateTime());
                    case Tag.CERTIFICATE_SUBJECT ->
                            certificateSubject = new X500Name(new X500Principal(kp.value.getBlob()).getName());
                    case Tag.RSA_PUBLIC_EXPONENT -> rsaPublicExponent = BigInteger.valueOf(kp.value.getLongInteger());
                    case Tag.EC_CURVE -> {
                        ecCurve = kp.value.getEcCurve();
                        ecCurveName = getEcCurveName(ecCurve);
                    }
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
                    case Tag.HARDWARE_TYPE -> securityLevel = kp.value.getSecurityLevel();
                }
            }
        }

        private static String getEcCurveName(int curve) {
            return switch (curve) {
                case EcCurve.CURVE_25519 -> "CURVE_25519";
                case EcCurve.P_224 -> "secp224r1";
                case EcCurve.P_256 -> "secp256r1";
                case EcCurve.P_384 -> "secp384r1";
                case EcCurve.P_521 -> "secp521r1";
                default -> throw new IllegalArgumentException("unknown curve");
            };
        }
    }
}
