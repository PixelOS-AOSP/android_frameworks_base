/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-FileCopyrightText: 2025 Neoteric OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.os.Build;
import android.os.SystemProperties;
import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.asn1.x509.Time;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @hide
 */
public class KeyboxImitationHooks {

    private static final String TAG = "KeyboxImitationHooks";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final ASN1ObjectIdentifier KEY_DESCRIPTION_OID =
            new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");

    public static KeyMetadata onGeneratedKey(KeyMetadata metadata) {
        return hackMetadata(metadata);
    }

    public static KeyEntryResponse onGetKeyEntry(KeyEntryResponse response) {
        if (response == null) {
            return null;
        }
        response.metadata = hackMetadata(response.metadata);
        return response;
    }

    private static KeyMetadata hackMetadata(KeyMetadata metadata) {
        if (metadata == null || metadata.certificate == null) {
            return metadata;
        }

        if (!KeyProviderManager.isKeyboxAvailable()) {
            return metadata;
        }

        try {
            Certificate[] original = getCertificateChain(metadata);
            Certificate[] hacked = hackCertificateChain(original);
            if (hacked != null && hacked.length > 0) {
                KeyboxUtils.putCertificateChain(metadata, hacked);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply leaf attestation hack", t);
        }

        return metadata;
    }

    private static Certificate[] getCertificateChain(KeyMetadata metadata) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<Certificate> chain = new ArrayList<>();

        chain.add(factory.generateCertificate(new ByteArrayInputStream(metadata.certificate)));

        if (metadata.certificateChain != null && metadata.certificateChain.length > 0) {
            Collection<? extends Certificate> certificates =
                    factory.generateCertificates(new ByteArrayInputStream(metadata.certificateChain));
            chain.addAll(certificates);
        }

        return chain.toArray(new Certificate[0]);
    }

    private static Certificate[] hackCertificateChain(Certificate[] chain) {
        if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            return chain;
        }

        try {
            X509Certificate leaf = (X509Certificate) chain[0];
            X509CertificateHolder leafHolder = new X509CertificateHolder(leaf.getEncoded());
            Extension attestationExtension = leafHolder.getExtension(KEY_DESCRIPTION_OID);

            if (attestationExtension == null) {
                return chain;
            }

            ASN1Sequence keyDescription =
                    ASN1Sequence.getInstance(attestationExtension.getExtnValue().getOctets());
            if (keyDescription.size() < 8) {
                return chain;
            }

            ASN1Encodable[] keyDescriptionValues = new ASN1Encodable[keyDescription.size()];
            for (int i = 0; i < keyDescription.size(); i++) {
                keyDescriptionValues[i] = keyDescription.getObjectAt(i);
            }

            int attestationVersion =
                    ASN1Integer.getInstance(keyDescriptionValues[0]).getValue().intValue();
            ASN1Sequence teeEnforced = ASN1Sequence.getInstance(keyDescriptionValues[7]);
            ASN1EncodableVector teeValues = new ASN1EncodableVector();
            boolean rootOfTrustReplaced = false;

            for (int i = 0; i < teeEnforced.size(); i++) {
                ASN1Encodable value = teeEnforced.getObjectAt(i);
                ASN1TaggedObject tagged = ASN1TaggedObject.getInstance(value);

                if (tagged.getTagNo() == 704) {
                    byte[] verifiedBootKey = null;
                    byte[] verifiedBootHash = null;

                    try {
                        ASN1Sequence originalRoot = ASN1Sequence.getInstance(tagged.getObject());
                        if (originalRoot.size() >= 1) {
                            verifiedBootKey =
                                    ASN1OctetString.getInstance(originalRoot.getObjectAt(0)).getOctets();
                        }
                        if (originalRoot.size() >= 4) {
                            verifiedBootHash =
                                    ASN1OctetString.getInstance(originalRoot.getObjectAt(3)).getOctets();
                        }
                    } catch (Throwable t) {
                        dlog("Failed to parse original RootOfTrust");
                    }

                    if (verifiedBootHash == null || verifiedBootHash.length == 0) {
                        verifiedBootHash = getVerifiedBootHash();
                    }

                    if (verifiedBootKey == null || verifiedBootKey.length == 0
                            || isAllZero(verifiedBootKey)) {
                        verifiedBootKey = deriveVerifiedBootKey(verifiedBootHash);
                    }

                    teeValues.add(new DERTaggedObject(true, 704,
                            createRootOfTrust(attestationVersion, verifiedBootKey,
                                    verifiedBootHash)));
                    rootOfTrustReplaced = true;
                } else {
                    teeValues.add(value);
                }
            }

            if (!rootOfTrustReplaced) {
                return chain;
            }

            String signerAlgorithm = chooseSignerAlgorithm(leaf.getPublicKey().getAlgorithm());
            if (signerAlgorithm == null) {
                Log.w(TAG, "No usable keybox signer found");
                return chain;
            }

            List<Certificate> keyboxChain = KeyboxUtils.getCertificateChain(signerAlgorithm);
            if (keyboxChain == null || keyboxChain.isEmpty()) {
                Log.w(TAG, "Empty keybox chain for " + signerAlgorithm);
                return chain;
            }

            X509CertificateHolder issuerHolder =
                    new X509CertificateHolder(keyboxChain.get(0).getEncoded());

            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    issuerHolder.getSubject(),
                    leafHolder.getSerialNumber(),
                    new Time(leaf.getNotBefore()),
                    new Time(leaf.getNotAfter()),
                    leafHolder.getSubject(),
                    leafHolder.getSubjectPublicKeyInfo()
            );

            keyDescriptionValues[7] = new DERSequence(teeValues);
            ASN1Sequence hackedDescription = new DERSequence(keyDescriptionValues);
            Extension hackedAttestationExtension = new Extension(
                    KEY_DESCRIPTION_OID,
                    attestationExtension.isCritical(),
                    new DEROctetString(hackedDescription)
            );
            builder.addExtension(hackedAttestationExtension);

            if (leafHolder.getExtensions() != null) {
                for (ASN1ObjectIdentifier oid : leafHolder.getExtensions().getExtensionOIDs()) {
                    if (KEY_DESCRIPTION_OID.equals(oid)
                            || Extension.authorityKeyIdentifier.equals(oid)) {
                        continue;
                    }
                    builder.addExtension(leafHolder.getExtension(oid));
                }
            }

            PrivateKey signerKey = KeyboxUtils.getPrivateKey(signerAlgorithm);
            ContentSigner signer = new JcaContentSignerBuilder(
                    KeyProperties.KEY_ALGORITHM_EC.equals(signerAlgorithm)
                            ? "SHA256withECDSA"
                            : "SHA256withRSA"
            ).build(signerKey);

            Certificate hackedLeaf =
                    KeyboxUtils.getCertificateFromHolder(builder.build(signer));

            List<Certificate> result = new ArrayList<>(keyboxChain.size() + 1);
            result.add(hackedLeaf);
            result.addAll(keyboxChain);

            dlog("Leaf attestation spoofed with real AndroidKeyStore key");
            return result.toArray(new Certificate[0]);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hack certificate chain", t);
            return chain;
        }
    }

    private static ASN1Sequence createRootOfTrust(int attestationVersion,
            byte[] verifiedBootKey, byte[] verifiedBootHash) {
        if (attestationVersion >= 3) {
            return new DERSequence(new ASN1Encodable[] {
                    new DEROctetString(verifiedBootKey),
                    ASN1Boolean.TRUE,
                    new ASN1Enumerated(0),
                    new DEROctetString(verifiedBootHash)
            });
        }

        return new DERSequence(new ASN1Encodable[] {
                new DEROctetString(verifiedBootKey),
                ASN1Boolean.TRUE,
                new ASN1Enumerated(0)
        });
    }

    private static String chooseSignerAlgorithm(String leafAlgorithm) {
        IKeyboxProvider provider = KeyProviderManager.getProvider();

        String[] ecChain = provider.getEcCertificateChain();
        String[] rsaChain = provider.getRsaCertificateChain();

        boolean hasEc = provider.getEcPrivateKey() != null
                && ecChain != null && ecChain.length > 0;
        boolean hasRsa = provider.getRsaPrivateKey() != null
                && rsaChain != null && rsaChain.length > 0;

        if (("EC".equalsIgnoreCase(leafAlgorithm)
                || "ECDSA".equalsIgnoreCase(leafAlgorithm)) && hasEc) {
            return KeyProperties.KEY_ALGORITHM_EC;
        }

        if ("RSA".equalsIgnoreCase(leafAlgorithm) && hasRsa) {
            return KeyProperties.KEY_ALGORITHM_RSA;
        }

        if (hasEc) {
            return KeyProperties.KEY_ALGORITHM_EC;
        }

        if (hasRsa) {
            return KeyProperties.KEY_ALGORITHM_RSA;
        }

        return null;
    }

    private static byte[] getVerifiedBootHash() {
        String digest = SystemProperties.get("ro.boot.vbmeta.digest", "");
        byte[] parsed = hexToBytes(digest);
        if (parsed != null && parsed.length > 0) {
            return parsed;
        }

        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    Build.FINGERPRINT.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new byte[32];
        }
    }

    private static byte[] deriveVerifiedBootKey(byte[] verifiedBootHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("PixelOS-FakeLock".getBytes(StandardCharsets.UTF_8));
            digest.update(verifiedBootHash);
            return digest.digest();
        } catch (Throwable t) {
            return verifiedBootHash;
        }
    }

    private static byte[] hexToBytes(String value) {
        if (value == null || value.isEmpty() || (value.length() & 1) != 0) {
            return null;
        }

        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < value.length(); i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            result[i / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static boolean isAllZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
