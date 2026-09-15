/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package com.megvii.facepp.sdk preserved as requested.
 * Source: com/megvii/facepp/sdk/FeatureRestoreHelper.java (CFR 0.152 + javap).
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 */

package com.megvii.facepp.sdk;

import android.util.Log;

import co.aospa.sense.vendor.util.UnlockEncryptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class FeatureRestoreHelper {
    private static final int BUFFER_SIZE = 8192;
    private static final int RESTORE_IMAGE_SIZE = 144;
    private static final String TAG = "FeatureRestoreHelper";
    public static final byte[] sMagic = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};

    private UnlockEncryptor mEncryptor;

    public void setUnlockEncryptor(UnlockEncryptor encryptor) {
        this.mEncryptor = encryptor;
    }

    public void saveRestoreImage(byte[] data, String dir, int id) {
        Log.i(TAG, "saveRestoreImage: length: " + data.length + " id " + id);
        writeFile(getRestoreFile(dir, id).getAbsolutePath(), data);
    }

    public void deleteRestoreImage(String dir, int id) {
        Log.i(TAG, "deleteRestoreImage: id " + id);
        getRestoreFile(dir, id).delete();
    }

    public int restoreAllFeature(String dir) {
        File[] files = new File(dir).listFiles();
        if (files == null || files.length == 0) {
            return 24;
        }
        int restored = 0;
        for (File file : files) {
            int id;
            String name = file.getName();
            if (!name.startsWith("restore_") || name.length() <= 8) {
                continue;
            }
            Log.i(TAG, "restoreAllFeature: " + name);
            try {
                id = Integer.parseInt(name.substring(8));
            } catch (NumberFormatException e) {
                id = -1;
            }
            if (id == -1) {
                continue;
            }
            byte[] data = readFile(file.getAbsolutePath());
            Log.i(TAG, "restoreAllFeature: update old feature " + id);
            if (restoreFeatureAtPosition(id, data) != 0) {
                continue;
            }
            ++restored;
        }
        return restored == 0 ? 24 : 0;
    }

    private int restoreFeatureAtPosition(int id, byte[] image) {
        return Lite.getInstance().updateFeature(image, RESTORE_IMAGE_SIZE, RESTORE_IMAGE_SIZE,
                90, true, new byte[Lite.FEATURE_SIZE], new byte[Lite.IMAGE_SIZE], id);
    }

    private File getRestoreFile(String dir, int id) {
        return new File(dir, "restore_" + id);
    }

    private void writeFile(String path, byte[] data) {
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
        UnlockEncryptor encryptor = mEncryptor;
        int offset = 0;
        if (encryptor != null) {
            byte[] encrypted = encryptor.encrypt(data);
            int len = encrypted.length;
            byte[] magic = sMagic;
            byte[] out = new byte[len + magic.length];
            System.arraycopy(magic, 0, out, 0, magic.length);
            System.arraycopy(encrypted, 0, out, magic.length, encrypted.length);
            data = out;
        }
        int total = data.length;
        try {
            FileOutputStream fos = new FileOutputStream(path);
            while (total > offset) {
                int chunk = total - offset;
                if (chunk > BUFFER_SIZE) {
                    chunk = BUFFER_SIZE;
                }
                fos.write(data, offset, chunk);
                offset += chunk;
            }
        } catch (IOException e) {
            Log.e(TAG, "writeFile failed", e);
        }
    }

    private byte[] readFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        int total = (int) file.length();
        byte[] data = new byte[total];
        try {
            FileInputStream fis = new FileInputStream(path);
            for (int offset = 0; total > offset;
                    offset += fis.read(data, offset, total - offset > BUFFER_SIZE
                            ? BUFFER_SIZE : total - offset)) {
            }
            if (mEncryptor != null && startWithMagic(data)) {
                byte[] magic = sMagic;
                int len = total - magic.length;
                byte[] encrypted = new byte[len];
                System.arraycopy(data, magic.length, encrypted, 0, len);
                return mEncryptor.decrypt(encrypted);
            }
            return data;
        } catch (IOException e) {
            Log.e(TAG, "readFile failed", e);
            return data;
        }
    }

    private boolean startWithMagic(byte[] data) {
        if (data.length < sMagic.length) {
            return false;
        }
        int i = 0;
        byte[] magic;
        while (i < (magic = sMagic).length) {
            if (data[i] != magic[i]) {
                return false;
            }
            ++i;
        }
        return true;
    }
}
