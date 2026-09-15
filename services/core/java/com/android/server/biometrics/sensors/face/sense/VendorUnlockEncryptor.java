/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package co.aospa.sense.vendor.util preserved as requested.
 * Source: co/aospa/sense/vendor/util/VendorUnlockEncryptor.java (CFR 0.152 + javap).
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 */

package co.aospa.sense.vendor.util;

import android.security.keystore.KeyProtection;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class VendorUnlockEncryptor implements UnlockEncryptor {
    private static final String TAG = "VendorUnlockEncryptor";
    public static final String AKS_PROVIDER = "AndroidKeyStore";
    private static final int PROFILE_KEY_IV_SIZE = 12;
    public static final String SEED_ALIAS = "seed_faceunlock";

    public VendorUnlockEncryptor() {
        saveSeed();
    }

    private boolean saveSeed() {
        try {
            KeyStore ks = KeyStore.getInstance(AKS_PROVIDER);
            ks.load(null);
            if (ks.containsAlias(SEED_ALIAS)) {
                Log.i(TAG, "key is already created");
                return true;
            }
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(new SecureRandom());
            ks.setEntry(SEED_ALIAS,
                    new KeyStore.SecretKeyEntry(kg.generateKey()),
                    new KeyProtection.Builder(1)
                            .setBlockModes(new String[]{"GCM"})
                            .setUserAuthenticationRequired(false)
                            .setEncryptionPaddings(new String[]{"NoPadding"})
                            .build());
            Log.i(TAG, "create key successfully");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Exception in store. " + e.toString());
            return false;
        }
    }

    private byte[] encryptData(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            SecretKey key;
            KeyStore ks = KeyStore.getInstance(AKS_PROVIDER);
            ks.load(null);
            if (ks.containsAlias(SEED_ALIAS)) {
                key = (SecretKey) ks.getKey(SEED_ALIAS, null);
            } else {
                Log.i(TAG, "key not exist, create key!");
                saveSeed();
                key = (SecretKey) ks.getKey(SEED_ALIAS, null);
            }
            if (key != null) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key);
                byte[] encrypted = cipher.doFinal(data);
                byte[] iv = cipher.getIV();
                if (iv.length == PROFILE_KEY_IV_SIZE) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bos.write(iv);
                    bos.write(encrypted);
                    return bos.toByteArray();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Exception in encrypt. " + e.toString());
        }
        return new byte[0];
    }

    private byte[] decryptData(byte[] data) {
        SecretKey key = null;
        if (data == null) {
            return null;
        }
        try {
            KeyStore ks = KeyStore.getInstance(AKS_PROVIDER);
            ks.load(null);
            if (ks.containsAlias(SEED_ALIAS)) {
                key = (SecretKey) ks.getKey(SEED_ALIAS, null);
            } else {
                Log.e(TAG, "key not exist, something is wrong!");
            }
            if (key != null) {
                byte[] iv = Arrays.copyOfRange(data, 0, PROFILE_KEY_IV_SIZE);
                byte[] encrypted = Arrays.copyOfRange(data, PROFILE_KEY_IV_SIZE, data.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, (Key) key, new GCMParameterSpec(128, iv));
                return cipher.doFinal(encrypted);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Exception in decrypt. " + e.toString());
        }
        return new byte[0];
    }

    @Override
    public byte[] encrypt(byte[] data) {
        return encryptData(data);
    }

    @Override
    public byte[] decrypt(byte[] data) {
        return decryptData(data);
    }
}
