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
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
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

    /**
     * Re-sign a KeyMint-attested leaf with the keybox. RootOfTrust becomes locked and
     * Verified, requested device IDs use the certified profile, and OS version and patch
     * levels follow that profile. Play Integrity compares those fields with the fingerprint.
     * The rest of the attestation extension is left as KeyMint encoded it.
     */
    public static List<Certificate> rewriteAttestedLeaf(byte[] encodedCertificate,
            KeyGenParameters params) throws Exception {
        X509CertificateHolder leaf = new X509CertificateHolder(encodedCertificate);
        Extension attestation = leaf.getExtension(KEY_DESCRIPTION_OID);
        if (attestation == null) {
            throw new IllegalArgumentException("Attested leaf has no key description");
        }

        ASN1Sequence description = ASN1Sequence.getInstance(attestation.getExtnValue().getOctets());
        if (description.size() < 8) {
            throw new IllegalArgumentException("Key description is too short");
        }
        ASN1Encodable[] fields = new ASN1Encodable[description.size()];
        for (int i = 0; i < description.size(); i++) {
            fields[i] = description.getObjectAt(i);
        }
        int attestationVersion = ASN1Integer.getInstance(fields[0]).getValue().intValue();
        fields[7] = new DERSequence(rewriteTeeEnforced(
                ASN1Sequence.getInstance(fields[7]), attestationVersion, params));

        String algorithm = params.algorithm == Algorithm.EC
                ? KeyProperties.KEY_ALGORITHM_EC : KeyProperties.KEY_ALGORITHM_RSA;
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                KeyboxUtils.getCertificateHolder(algorithm).getSubject(),
                leaf.getSerialNumber(), leaf.getNotBefore(), leaf.getNotAfter(),
                leaf.getSubject(), leaf.getSubjectPublicKeyInfo());
        builder.addExtension(new Extension(KEY_DESCRIPTION_OID, attestation.isCritical(),
                new DEROctetString(new DERSequence(fields))));
        if (leaf.getExtensions() != null) {
            for (ASN1ObjectIdentifier oid : leaf.getExtensions().getExtensionOIDs()) {
                if (KEY_DESCRIPTION_OID.equals(oid) || Extension.authorityKeyIdentifier.equals(oid)) {
                    continue;
                }
                builder.addExtension(leaf.getExtension(oid));
            }
        }

        ContentSigner signer = new JcaContentSignerBuilder(
                params.algorithm == Algorithm.EC ? "SHA256withECDSA" : "SHA256withRSA")
                .build(KeyboxUtils.getPrivateKey(algorithm));
        Certificate rewritten = KeyboxUtils.getCertificateFromHolder(builder.build(signer));
        List<Certificate> chain = KeyboxUtils.getCertificateChain(algorithm);
        chain.add(0, rewritten);
        return chain;
    }

    private static final ASN1ObjectIdentifier KEY_DESCRIPTION_OID =
            new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");

    private static List<ASN1Encodable> rewriteTeeEnforced(ASN1Sequence tee, int attestationVersion,
            KeyGenParameters params) throws Exception {
        Integer osVersion = certifiedOsVersion();
        boolean replaceOsVersion = osVersion != null;
        boolean replacePatch = hasCertifiedPatch();
        List<Tagged> entries = new ArrayList<>();
        for (int i = 0; i < tee.size(); i++) {
            ASN1TaggedObject tagged = ASN1TaggedObject.getInstance(tee.getObjectAt(i));
            int tag = tagged.getTagNo();
            if (tag == 704 || attestedId(params, tag) != null
                    || (replaceOsVersion && tag == 705)
                    || (replacePatch && (tag == 706 || tag == 718 || tag == 719))) {
                continue;
            }
            entries.add(new Tagged(tag, tee.getObjectAt(i)));
        }
        entries.add(new Tagged(704, new DERTaggedObject(true, 704,
                rootOfTrust(attestationVersion))));
        if (replaceOsVersion) {
            addIntegerTag(entries, 705, osVersion);
        }
        if (replacePatch) {
            // 706 is YYYYMM. 718 and 719 are YYYYMMDD.
            addIntegerTag(entries, 706, getPatchLevel());
            addIntegerTag(entries, 718, getPatchLevelLong());
            addIntegerTag(entries, 719, getPatchLevelLong());
        }
        addAttestedId(entries, 710, params.brand);
        addAttestedId(entries, 711, params.device);
        addAttestedId(entries, 712, params.product);
        addAttestedId(entries, 716, params.manufacturer);
        addAttestedId(entries, 717, params.model);
        entries.sort((a, b) -> Integer.compare(a.tag, b.tag));

        List<ASN1Encodable> encoded = new ArrayList<>(entries.size());
        for (Tagged entry : entries) {
            encoded.add(entry.value);
        }
        return encoded;
    }

    private static void addAttestedId(List<Tagged> entries, int tag, byte[] value) {
        if (value != null) {
            entries.add(new Tagged(tag, new DERTaggedObject(true, tag, new DEROctetString(value))));
        }
    }

    private static byte[] attestedId(KeyGenParameters params, int tag) {
        return switch (tag) {
            case 710 -> params.brand;
            case 711 -> params.device;
            case 712 -> params.product;
            case 716 -> params.manufacturer;
            case 717 -> params.model;
            default -> null;
        };
    }

    private static ASN1Sequence rootOfTrust(int attestationVersion) throws Exception {
        if (attestationVersion >= 3) {
            return new DERSequence(new ASN1Encodable[] {
                    new DEROctetString(getVerifiedBootKey()),
                    ASN1Boolean.TRUE,
                    new ASN1Enumerated(0),
                    new DEROctetString(decodeHexProperty("ro.boot.vbmeta.digest"))
            });
        }
        return new DERSequence(new ASN1Encodable[] {
                new DEROctetString(getVerifiedBootKey()),
                ASN1Boolean.TRUE,
                new ASN1Enumerated(0)
        });
    }

    private static final class Tagged {
        final int tag;
        final ASN1Encodable value;

        Tagged(int tag, ASN1Encodable value) {
            this.tag = tag;
            this.value = value;
        }
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

    private static void addIntegerTag(List<Tagged> entries, int tag, int value) {
        entries.add(new Tagged(tag, new DERTaggedObject(true, tag, new ASN1Integer(value))));
    }

    @Nullable
    private static Integer certifiedOsVersion() {
        String release = Build.VERSION.RELEASE;
        if (release == null || release.isEmpty() || !Character.isDigit(release.charAt(0))) {
            return null;
        }
        try {
            return getOsVersion();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasCertifiedPatch() {
        String patch = Build.VERSION.SECURITY_PATCH;
        if (patch == null) {
            return false;
        }
        String[] parts = patch.split("-");
        if (parts.length != 3) {
            return false;
        }
        try {
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            return year >= 2010 && month >= 1 && month <= 12 && day >= 1 && day <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
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
