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
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.Tag;
import android.os.Binder;
import android.os.Build;
import android.os.SystemProperties;
import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyDescriptor;
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
import com.android.internal.org.bouncycastle.asn1.x500.X500Name;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.asn1.x509.KeyUsage;
import com.android.internal.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import com.android.internal.org.bouncycastle.asn1.x509.Time;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.jce.provider.BouncyCastleProvider;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

/**
 * @hide
 */
public final class KeyboxChainGenerator {

    private static final String TAG = "KeyboxChainGenerator";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX = 0;
    private static final int ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX = 1;
    private static final int ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX = 0;
    private static final int ATTESTATION_PACKAGE_INFO_VERSION_INDEX = 1;

    private static final ASN1ObjectIdentifier KEY_DESCRIPTION_OID =
            new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");

    /**
     * Re-sign a KeyMint leaf with the keybox. The challenge, application id, and
     * attestation version stay as KeyMint wrote them. OS version follows the
     * certified release, because that is what Play Services is shown. Patch levels
     * follow the platform security patch, which prop imitation does not replace.
     * Serial and IMEI are removed. Returns null when the leaf cannot be re-signed.
     */
    @Nullable
    public static Certificate[] hackCertificateChain(Certificate[] chain, Map<String, String> props)
            throws Exception {
        if (chain == null || chain.length == 0 || props == null || props.isEmpty()) {
            return null;
        }
        X509CertificateHolder leaf = new X509CertificateHolder(chain[0].getEncoded());
        Extension attestation = leaf.getExtension(KEY_DESCRIPTION_OID);
        if (attestation == null) {
            return null;
        }
        ASN1Sequence description = ASN1Sequence.getInstance(attestation.getExtnValue().getOctets());
        if (description.size() < 8) {
            return null;
        }
        ASN1Encodable[] fields = new ASN1Encodable[description.size()];
        for (int i = 0; i < description.size(); i++) {
            fields[i] = description.getObjectAt(i);
        }
        int attestationVersion = ASN1Integer.getInstance(fields[0]).getValue().intValue();
        String algorithm = certificateAlgorithm(leaf);
        if (algorithm == null) {
            return null;
        }

        Integer osVersion = osVersionFromRelease(props.get("VERSION.RELEASE"));
        boolean replacePatch = hasPlatformPatch();
        ASN1Sequence tee = ASN1Sequence.getInstance(fields[7]);
        ASN1Encodable originalRoot = null;
        List<Tagged> entries = new ArrayList<>();
        for (int i = 0; i < tee.size(); i++) {
            ASN1TaggedObject tagged = ASN1TaggedObject.getInstance(tee.getObjectAt(i));
            int tag = tagged.getTagNo();
            if (tag == 704) {
                originalRoot = tagged.getBaseObject();
            }
            if (tag == 704
                    || (osVersion != null && tag == 705)
                    || (replacePatch && (tag == 706 || tag == 718 || tag == 719))
                    || isHardwareIdTag(tag)) {
                continue;
            }
            entries.add(new Tagged(tag, tee.getObjectAt(i)));
        }

        byte[] bootKey = verifiedBootKey(originalRoot);
        byte[] bootHash = bootHash(originalRoot);
        if (bootKey.length != 32 || (attestationVersion >= 3 && bootHash.length != 32)) {
            return null;
        }
        ASN1Encodable[] rootElements = attestationVersion >= 3
                ? new ASN1Encodable[] {
                        new DEROctetString(bootKey),
                        ASN1Boolean.TRUE,
                        new ASN1Enumerated(0),
                        new DEROctetString(bootHash)}
                : new ASN1Encodable[] {
                        new DEROctetString(bootKey),
                        ASN1Boolean.TRUE,
                        new ASN1Enumerated(0)};
        entries.add(new Tagged(704, new DERTaggedObject(true, 704, new DERSequence(rootElements))));
        if (osVersion != null) {
            entries.add(new Tagged(705, new DERTaggedObject(true, 705, new ASN1Integer(osVersion))));
        }
        if (replacePatch) {
            int patch = getPatchLevel();
            int patchLong = getPatchLevelLong();
            entries.add(new Tagged(706, new DERTaggedObject(true, 706, new ASN1Integer(patch))));
            entries.add(new Tagged(718, new DERTaggedObject(true, 718, new ASN1Integer(patchLong))));
            entries.add(new Tagged(719, new DERTaggedObject(true, 719, new ASN1Integer(patchLong))));
        }
        addProfileId(entries, 710, props.get("BRAND"));
        addProfileId(entries, 711, props.get("DEVICE"));
        addProfileId(entries, 712, props.get("PRODUCT"));
        addProfileId(entries, 716, props.get("MANUFACTURER"));
        addProfileId(entries, 717, props.get("MODEL"));
        entries.sort((left, right) -> Integer.compare(left.tag, right.tag));

        List<ASN1Encodable> encoded = new ArrayList<>(entries.size());
        for (Tagged entry : entries) {
            encoded.add(entry.value);
        }
        fields[7] = new DERSequence(encoded.toArray(new ASN1Encodable[0]));

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
                KeyProperties.KEY_ALGORITHM_EC.equals(algorithm) ? "SHA256withECDSA" : "SHA256withRSA")
                .build(KeyboxUtils.getPrivateKey(algorithm));
        Certificate rewritten = KeyboxUtils.getCertificateFromHolder(builder.build(signer));
        List<Certificate> keyboxChain = KeyboxUtils.getCertificateChain(algorithm);
        Certificate[] result = new Certificate[keyboxChain.size() + 1];
        result[0] = rewritten;
        for (int i = 0; i < keyboxChain.size(); i++) {
            result[i + 1] = keyboxChain.get(i);
        }
        return result;
    }

    /**
     * Re-sign a KeyMint-attested leaf with the keybox. The challenge, application id,
     * and attestation version stay as KeyMint wrote them. Throws when the certificate
     * has no attestation extension, so the caller can build a leaf instead.
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
        List<ASN1Encodable> teeEnforced = rewriteTeeEnforced(
                ASN1Sequence.getInstance(fields[7]), attestationVersion, params);
        fields[7] = new DERSequence(teeEnforced.toArray(new ASN1Encodable[0]));

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

    /**
     * Build a keybox leaf for a key KeyMint already created. The public key is taken
     * from that certificate, so the private key KeyMint holds can sign the challenge.
     */
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
        certBuilder.addExtension(createHardwareAttestation(params, uid));

        ContentSigner contentSigner = new JcaContentSignerBuilder(
                params.algorithm == Algorithm.EC ? "SHA256withECDSA" : "SHA256withRSA")
                .build(KeyboxUtils.getPrivateKey(algorithm));
        Certificate leaf = KeyboxUtils.getCertificateFromHolder(certBuilder.build(contentSigner));
        List<Certificate> chain = KeyboxUtils.getCertificateChain(algorithm);
        chain.add(0, leaf);
        return chain;
    }

    private static List<ASN1Encodable> rewriteTeeEnforced(ASN1Sequence tee, int attestationVersion,
            KeyGenParameters params) throws Exception {
        boolean replacePatch = hasPlatformPatch();
        List<Tagged> entries = new ArrayList<>();
        for (int i = 0; i < tee.size(); i++) {
            ASN1TaggedObject tagged = ASN1TaggedObject.getInstance(tee.getObjectAt(i));
            int tag = tagged.getTagNo();
            if (tag == 704 || tag == 705 || attestedId(params, tag) != null
                    || (replacePatch && (tag == 706 || tag == 718 || tag == 719))) {
                continue;
            }
            entries.add(new Tagged(tag, tee.getObjectAt(i)));
        }
        entries.add(new Tagged(704, new DERTaggedObject(true, 704,
                rootOfTrust(attestationVersion))));
        // 705 is MMmmss, or 0 when the release is a codename. Canary attests 0.
        addIntegerTag(entries, 705, getOsVersion());
        if (replacePatch) {
            addIntegerTag(entries, 706, getPatchLevel());
            addIntegerTag(entries, 718, getPatchLevelLong());
            addIntegerTag(entries, 719, getPatchLevelLong());
        }
        addAttestedId(entries, 710, params.brand);
        addAttestedId(entries, 711, params.device);
        addAttestedId(entries, 712, params.product);
        addAttestedId(entries, 716, params.manufacturer);
        addAttestedId(entries, 717, params.model);
        entries.sort((left, right) -> Integer.compare(left.tag, right.tag));

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
                    new DEROctetString(bootHash())
            });
        }
        return new DERSequence(new ASN1Encodable[] {
                new DEROctetString(getVerifiedBootKey()),
                ASN1Boolean.TRUE,
                new ASN1Enumerated(0)
        });
    }

    private static void addIntegerTag(List<Tagged> entries, int tag, int value) {
        entries.add(new Tagged(tag, new DERTaggedObject(true, tag, new ASN1Integer(value))));
    }

    private static Extension createHardwareAttestation(KeyGenParameters params, int uid)
            throws Exception {
        ASN1Sequence rootOfTrustSeq = rootOfTrust(4);

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
        } else if (params.algorithm == Algorithm.RSA && params.rsaPublicExponent != null) {
            teeEnforced.add(new DERTaggedObject(true, 200,
                    new ASN1Integer(params.rsaPublicExponent)));
        }
        if (params.noAuthRequired) {
            teeEnforced.add(new DERTaggedObject(true, 503, DERNull.INSTANCE));
        }
        // KeyOrigin.GENERATED
        teeEnforced.add(new DERTaggedObject(true, 702, new ASN1Integer(0)));
        teeEnforced.add(new DERTaggedObject(true, 704, rootOfTrustSeq));
        teeEnforced.add(new DERTaggedObject(true, 705, new ASN1Integer(getOsVersion())));
        teeEnforced.add(new DERTaggedObject(true, 706, new ASN1Integer(getPatchLevel())));
        addAsn1Id(teeEnforced, 710, params.brand);
        addAsn1Id(teeEnforced, 711, params.device);
        addAsn1Id(teeEnforced, 712, params.product);
        addAsn1Id(teeEnforced, 716, params.manufacturer);
        addAsn1Id(teeEnforced, 717, params.model);
        teeEnforced.add(new DERTaggedObject(true, 718, new ASN1Integer(getPatchLevelLong())));
        teeEnforced.add(new DERTaggedObject(true, 719, new ASN1Integer(getPatchLevelLong())));

        ASN1Encodable[] softwareEnforced = {
                new DERTaggedObject(true, 701, new ASN1Integer(System.currentTimeMillis())),
                new DERTaggedObject(true, 709, createApplicationId(uid))
        };
        return new Extension(KEY_DESCRIPTION_OID, false,
                getAsn1OctetString(teeEnforced.toArray(new ASN1Encodable[0]), softwareEnforced,
                        params));
    }

    private static void addAsn1Id(List<ASN1Encodable> list, int tag, byte[] value) {
        if (value != null) {
            list.add(new DERTaggedObject(true, tag, new DEROctetString(value)));
        }
    }

    private static byte[] getVerifiedBootKey() throws Exception {
        byte[] fromProp = decodeHexProperty("ro.boot.vbmeta.public_key_digest");
        if (fromProp.length == 32) {
            return fromProp;
        }
        String algorithm = KeyProviderManager.isKeyboxAvailable(Algorithm.EC)
                ? KeyProperties.KEY_ALGORITHM_EC : KeyProperties.KEY_ALGORITHM_RSA;
        Certificate issuer = KeyboxUtils.getCertificateChain(algorithm).get(0);
        return MessageDigest.getInstance("SHA-256").digest(issuer.getEncoded());
    }

    private static byte[] bootHash() {
        byte[] fromProp = decodeHexProperty("ro.boot.vbmeta.digest");
        if (fromProp.length == 32) {
            return fromProp;
        }
        return new byte[32];
    }

    private static boolean isHardwareIdTag(int tag) {
        return tag == 710 || tag == 711 || tag == 712 || tag == 713 || tag == 714
                || tag == 715 || tag == 716 || tag == 717 || tag == 723;
    }

    private static void addProfileId(List<Tagged> entries, int tag, String value) {
        if (value != null && !value.isEmpty()) {
            entries.add(new Tagged(tag, new DERTaggedObject(true, tag,
                    new DEROctetString(value.getBytes(StandardCharsets.UTF_8)))));
        }
    }

    // KeyMint uses 0 when the release is a codename, including CANARY.
    private static Integer osVersionFromRelease(String release) {
        if (release == null || release.isEmpty()) {
            return null;
        }
        if (!Character.isDigit(release.charAt(0))) {
            return 0;
        }
        try {
            int major = 0;
            int minor = 0;
            int patch = 0;
            String[] parts = release.split("\\.");
            if (parts.length > 0) major = Integer.parseInt(parts[0]);
            if (parts.length > 1) minor = Integer.parseInt(parts[1]);
            if (parts.length > 2) patch = Integer.parseInt(parts[2]);
            return major * 10000 + minor * 100 + patch;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String certificateAlgorithm(X509CertificateHolder leaf) {
        String oid = leaf.getSubjectPublicKeyInfo().getAlgorithm().getAlgorithm().getId();
        IKeyboxProvider provider = KeyProviderManager.getProvider();
        if ("1.2.840.10045.2.1".equals(oid)) {
            String[] chain = provider.getEcCertificateChain();
            if (provider.getEcPrivateKey() != null && chain != null && chain.length > 0) {
                return KeyProperties.KEY_ALGORITHM_EC;
            }
            return null;
        }
        if (oid != null && oid.startsWith("1.2.840.113549.1.1")) {
            String[] chain = provider.getRsaCertificateChain();
            if (provider.getRsaPrivateKey() != null && chain != null && chain.length > 0) {
                return KeyProperties.KEY_ALGORITHM_RSA;
            }
        }
        return null;
    }

    private static byte[] verifiedBootKey(ASN1Encodable originalRoot) {
        byte[] fromProp = decodeHexProperty("ro.boot.vbmeta.public_key_digest");
        if (fromProp.length == 32) {
            return fromProp;
        }
        byte[] fromLeaf = sequenceOctet(originalRoot, 0);
        return fromLeaf != null ? fromLeaf : new byte[0];
    }

    private static byte[] bootHash(ASN1Encodable originalRoot) {
        byte[] fromProp = decodeHexProperty("ro.boot.vbmeta.digest");
        if (fromProp.length == 32) {
            return fromProp;
        }
        byte[] fromLeaf = sequenceOctet(originalRoot, 3);
        return fromLeaf != null ? fromLeaf : new byte[0];
    }

    private static byte[] sequenceOctet(ASN1Encodable root, int index) {
        if (root == null) {
            return null;
        }
        ASN1Encodable primitive = root.toASN1Primitive();
        if (primitive instanceof ASN1TaggedObject) {
            primitive = ((ASN1TaggedObject) primitive).getBaseObject().toASN1Primitive();
        }
        if (!(primitive instanceof ASN1Sequence)) {
            return null;
        }
        ASN1Sequence sequence = (ASN1Sequence) primitive;
        if (sequence.size() <= index) {
            return null;
        }
        ASN1Encodable element = sequence.getObjectAt(index).toASN1Primitive();
        if (element instanceof ASN1OctetString) {
            return ((ASN1OctetString) element).getOctets();
        }
        return null;
    }

    private static byte[] decodeHexProperty(String name) {
        String value = SystemProperties.get(name, "");
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if ((value.length() & 1) != 0) {
            return new byte[0];
        }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return new byte[0];
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }

    private static final class Tagged {
        final int tag;
        final ASN1Encodable value;

        Tagged(int tag, ASN1Encodable value) {
            this.tag = tag;
            this.value = value;
        }
    }

    public static List<Certificate> generateCertChain(int uid, KeyDescriptor descriptor, KeyGenParameters params) {
        dlog("Requested KeyPair with alias: " + descriptor.alias);
        int size = params.keySize;
        KeyPair kp;
        try {
            if (Objects.equals(params.algorithm, Algorithm.EC)) {
                dlog("Generating EC keypair of size " + size);
                kp = buildECKeyPair(params);
            } else if (Objects.equals(params.algorithm, Algorithm.RSA)) {
                dlog("Generating RSA keypair of size " + size);
                kp = buildRSAKeyPair(params);
            } else {
                dlog("Unsupported algorithm");
                return null;
            }

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
                            ASN1Sequence.getInstance(kp.getPublic().getEncoded())
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
            dlog("Successfully generated X500 Cert for alias: " + descriptor.alias);
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
            SecureRandom random = new SecureRandom();

            byte[] bytes1 = new byte[32];
            byte[] bytes2 = new byte[32];

            random.nextBytes(bytes1);
            random.nextBytes(bytes2);

            ASN1Encodable[] rootOfTrustEncodables = {new DEROctetString(bytes1), ASN1Boolean.TRUE,
                    new ASN1Enumerated(0), new DEROctetString(bytes2)};

            ASN1Sequence rootOfTrustSeq = new DERSequence(rootOfTrustEncodables);

            var Apurpose = new DERSet(fromIntList(params.purpose));
            var Aalgorithm = new ASN1Integer(params.algorithm);
            var AkeySize = new ASN1Integer(params.keySize);
            var Adigest = new DERSet(fromIntList(params.digest));
            var AecCurve = new ASN1Integer(params.ecCurve);
            var AnoAuthRequired = DERNull.INSTANCE;

            // To be loaded
            var AosVersion = new ASN1Integer(getOsVersion());
            var AosPatchLevel = new ASN1Integer(getPatchLevel());

            var AapplicationID = createApplicationId(uid);
            var AbootPatchlevel = new ASN1Integer(getPatchLevelLong());
            var AvendorPatchLevel = new ASN1Integer(getPatchLevelLong());

            var AcreationDateTime = new ASN1Integer(System.currentTimeMillis());
            var Aorigin = new ASN1Integer(0);

            var purpose = new DERTaggedObject(true, 1, Apurpose);
            var algorithm = new DERTaggedObject(true, 2, Aalgorithm);
            var keySize = new DERTaggedObject(true, 3, AkeySize);
            var digest = new DERTaggedObject(true, 5, Adigest);
            var ecCurve = new DERTaggedObject(true, 10, AecCurve);
            var noAuthRequired = new DERTaggedObject(true, 503, AnoAuthRequired);
            var creationDateTime = new DERTaggedObject(true, 701, AcreationDateTime);
            var origin = new DERTaggedObject(true, 702, Aorigin);
            var rootOfTrust = new DERTaggedObject(true, 704, rootOfTrustSeq);
            var osVersion = new DERTaggedObject(true, 705, AosVersion);
            var osPatchLevel = new DERTaggedObject(true, 706, AosPatchLevel);
            var applicationID = new DERTaggedObject(true, 709, AapplicationID);
            var vendorPatchLevel = new DERTaggedObject(true, 718, AvendorPatchLevel);
            var bootPatchLevel = new DERTaggedObject(true, 719, AbootPatchlevel);

            ASN1Encodable[] teeEnforcedEncodables;

            // Support device properties attestation
            if (params.brand != null) {
                var Abrand = new DEROctetString(params.brand);
                var Adevice = new DEROctetString(params.device);
                var Aproduct = new DEROctetString(params.product);
                var Amanufacturer = new DEROctetString(params.manufacturer);
                var Amodel = new DEROctetString(params.model);
                var brand = new DERTaggedObject(true, 710, Abrand);
                var device = new DERTaggedObject(true, 711, Adevice);
                var product = new DERTaggedObject(true, 712, Aproduct);
                var manufacturer = new DERTaggedObject(true, 716, Amanufacturer);
                var model = new DERTaggedObject(true, 717, Amodel);

                teeEnforcedEncodables = new ASN1Encodable[]{purpose, algorithm, keySize, digest, ecCurve,
                        noAuthRequired, origin, rootOfTrust, osVersion, osPatchLevel, vendorPatchLevel,
                        bootPatchLevel, brand, device, product, manufacturer, model};
            } else {
                teeEnforcedEncodables = new ASN1Encodable[]{purpose, algorithm, keySize, digest, ecCurve,
                        noAuthRequired, origin, rootOfTrust, osVersion, osPatchLevel, vendorPatchLevel,
                        bootPatchLevel};
            }

            ASN1Encodable[] softwareEnforced = {applicationID, creationDateTime};

            ASN1OctetString keyDescriptionOctetStr = getAsn1OctetString(teeEnforcedEncodables, softwareEnforced, params);

            return new Extension(new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"), false, keyDescriptionOctetStr);
        } catch (Throwable t) {
            Log.e(TAG, Log.getStackTraceString(t));
        }
        return null;
    }

    private static int getOsVersion() {
        String release = Build.VERSION.RELEASE;
        // Same rule as KeyMint getOsVersion(): a codename release, including CANARY, is 0.
        if (release == null || release.isEmpty() || !Character.isDigit(release.charAt(0))) {
            return 0;
        }
        try {
            int major = 0, minor = 0, patch = 0;
            String[] parts = release.split("\\.");
            if (parts.length > 0) major = Integer.parseInt(parts[0]);
            if (parts.length > 1) minor = Integer.parseInt(parts[1]);
            if (parts.length > 2) patch = Integer.parseInt(parts[2]);
            return major * 10000 + minor * 100 + patch;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasPlatformPatch() {
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

    private static int getPatchLevel() {
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, false);
    }

    private static int getPatchLevelLong() {
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, true);
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
        ASN1OctetString uniqueId = new DEROctetString("".getBytes());
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

            for (Signature s : info.signatures) {
                signatures.add(new Digest(dg.digest(s.toByteArray())));
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

    private static KeyPair buildECKeyPair(KeyGenParameters params) throws Exception {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.addProvider(new BouncyCastleProvider());
        ECGenParameterSpec spec = new ECGenParameterSpec(params.ecCurveName);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }

    private static KeyPair buildRSAKeyPair(KeyGenParameters params) throws Exception {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.addProvider(new BouncyCastleProvider());
        RSAKeyGenParameterSpec spec = new RSAKeyGenParameterSpec(
                params.keySize, params.rsaPublicExponent);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }

    public static class KeyGenParameters {
        public int keySize;
        public int algorithm;
        public BigInteger certificateSerial;
        public Date certificateNotBefore;
        public Date certificateNotAfter;
        public X500Name certificateSubject;

        public BigInteger rsaPublicExponent;
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
                    case Tag.CERTIFICATE_SUBJECT -> certificateSubject =
                            new X500Name(new X500Principal(kp.value.getBlob()).getName());
                    case Tag.RSA_PUBLIC_EXPONENT -> rsaPublicExponent = BigInteger.valueOf(kp.value.getLongInteger());
                    case Tag.EC_CURVE -> {
                        ecCurve = kp.value.getEcCurve();
                        ecCurveName = getEcCurveName(ecCurve);
                    }
                    case Tag.PURPOSE -> {
                        purpose.add(kp.value.getKeyPurpose());
                    }
                    case Tag.DIGEST -> {
                        digest.add(kp.value.getDigest());
                    }
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
            String res;
            switch (curve) {
                case EcCurve.CURVE_25519 -> res = "CURVE_25519";
                case EcCurve.P_224 -> res = "secp224r1";
                case EcCurve.P_256 -> res = "secp256r1";
                case EcCurve.P_384 -> res = "secp384r1";
                case EcCurve.P_521 -> res = "secp521r1";
                default -> throw new IllegalArgumentException("unknown curve");
            }
            return res;
        }
    }
}
