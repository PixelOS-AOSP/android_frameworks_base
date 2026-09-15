/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package com.megvii.facepp.sdk preserved as requested (JNI compatibility:
 * native code expects com/megvii/facepp/sdk/Lite$LiteConfig signatures).
 * Source: com/megvii/facepp/sdk/Lite.java (CFR 0.152 + javap verification).
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 */

package com.megvii.facepp.sdk;

import android.media.Image;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import co.aospa.sense.vendor.util.UnlockEncryptor;
import com.megvii.facepp.sdk.jni.LiteApi;

import java.nio.ByteBuffer;

public class Lite {
    public static final int FEATURE_SIZE = 10000;
    public static final int IMAGE_SIZE = 40000;
    public static final int RESULT_SIZE = 20;

    private static Lite sInstance;
    private long handle = 0L;
    private final FeatureRestoreHelper mFeatureRestoreHelper = new FeatureRestoreHelper();
    private String mPath;

    public int setConfig(float f, float f2, float f3) {
        return 0;
    }

    public static Lite getInstance() {
        if (sInstance == null) {
            sInstance = new Lite();
        }
        return sInstance;
    }

    public void initHandle(String path, UnlockEncryptor encryptor) {
        initHandle(path);
        mFeatureRestoreHelper.setUnlockEncryptor(encryptor);
    }

    public void initHandle(String path) {
        if (handle == 0L) {
            handle = LiteApi.nativeInitHandle(path);
            mPath = path;
        }
    }

    public int initAll(String s1, String s2, byte[] b) {
        return (int) LiteApi.nativeInitAll(handle, s1, s2, b);
    }

    public int initAllWithPath(String s1, String s2, String s3) {
        return (int) LiteApi.nativeInitAllWithPath(handle, s1, s2, s3);
    }

    public int initLive(String s1, String s2) {
        return (int) LiteApi.nativeInitLive(handle, s1, s2);
    }

    public int initDetect(byte[] b) {
        return (int) LiteApi.nativeInitDetect(handle, b);
    }

    public int initDetectWithPath(String s) {
        return (int) LiteApi.nativeInitDetectWithPath(handle, s);
    }

    public int releaseLive() {
        return (int) LiteApi.nativeReleaseLive(handle);
    }

    public int releaseDetect() {
        return (int) LiteApi.nativeReleaseDetect(handle);
    }

    public void release() {
        LiteApi.nativeRelease(handle);
        handle = 0L;
    }

    public int compare(byte[] image, int width, int height, int angle,
            boolean bl1, boolean bl2, int[] result) {
        if (result.length < RESULT_SIZE) {
            return 1;
        }
        return LiteApi.nativeCompare(handle, image, width, height, angle, bl1, bl2, result);
    }

    public int compare(byte[] image, int width, int height, int angle, int[] result) {
        if (result.length < RESULT_SIZE) {
            return 1;
        }
        return LiteApi.nativeCompare(handle, image, width, height, angle, false, false, result);
    }

    public int compareMultiImages(MGULKImage[] images, int[] result) {
        if (result.length < RESULT_SIZE) {
            return 1;
        }
        return LiteApi.nativeCompareMultiImages(handle, images, result);
    }

    public int saveFeature(byte[] image, int width, int height, int angle,
            boolean needLiveness, byte[] feature, byte[] restoreImage) {
        return updateFeature(image, width, height, angle, needLiveness, feature, restoreImage, 0);
    }

    public int saveFeature(byte[] image, int width, int height, int angle,
            byte[] feature, byte[] restoreImage) {
        return updateFeature(image, width, height, angle, true, feature, restoreImage, 0);
    }

    public int saveFeature(byte[] image, int width, int height, int angle,
            boolean needLiveness, byte[] feature, byte[] restoreImage, int[] result) {
        if (new StatFs(Environment.getDataDirectory().getPath()).getAvailableBlocksLong() < 256L) {
            return 33;
        }
        if (restoreImage.length < IMAGE_SIZE || feature.length < FEATURE_SIZE) {
            return 1;
        }
        int ret = LiteApi.nativeSaveFeature(handle, image, width, height, angle,
                needLiveness ? 1 : 0, feature, restoreImage, result);
        if (ret == 0) {
            mFeatureRestoreHelper.saveRestoreImage(restoreImage, mPath, result[0]);
        }
        return ret;
    }

    public int saveFeature(byte[] image, int width, int height, int angle,
            byte[] feature, byte[] restoreImage, int[] result) {
        if (restoreImage.length < IMAGE_SIZE || feature.length < FEATURE_SIZE) {
            return 1;
        }
        int ret = LiteApi.nativeSaveFeature(handle, image, width, height, angle,
                1, feature, restoreImage, result);
        if (ret == 0) {
            mFeatureRestoreHelper.saveRestoreImage(restoreImage, mPath, result[0]);
        }
        return ret;
    }

    public int saveFeatureMultiImages(MGULKImage[] images, byte[] feature, byte[] restoreImage,
            int[] result) {
        int ret = LiteApi.nativeSaveFeatureMultiImages(handle, images, feature, restoreImage,
                result);
        if (ret == 0) {
            mFeatureRestoreHelper.saveRestoreImage(restoreImage, mPath, result[0]);
        }
        return ret;
    }

    public int updateFeature(byte[] image, int width, int height, int angle,
            boolean needLiveness, byte[] feature, byte[] restoreImage, int id) {
        if (restoreImage.length < IMAGE_SIZE || feature.length < FEATURE_SIZE) {
            return 1;
        }
        int ret = LiteApi.nativeUpdateFeature(handle, image, width, height, angle,
                needLiveness ? 1 : 0, feature, restoreImage, id);
        if (ret == 0) {
            mFeatureRestoreHelper.saveRestoreImage(restoreImage, mPath, id);
        }
        return ret;
    }

    public int deleteFeature() {
        return deleteFeature(0);
    }

    public int deleteFeature(int id) {
        int ret = LiteApi.nativeDeleteFeature(handle, id);
        mFeatureRestoreHelper.deleteRestoreImage(mPath, id);
        return ret;
    }

    public int restoreFeature() {
        return mFeatureRestoreHelper.restoreAllFeature(mPath);
    }

    public int setConfig(float f1, float f2, float f3, float f4, boolean b1, boolean b2) {
        return LiteApi.nativeSetConfig(handle, f1, f2, f3, f4, b1, b2);
    }

    public int setConfig(float f1, float f2, float f3, float f4) {
        return LiteApi.nativeSetConfig(handle, f1, f2, f3, f4, false, false);
    }

    public int setConfig(LiteConfig config) {
        if (config == null) {
            return -1;
        }
        return LiteApi.nativeSetConfigV2(handle, config);
    }

    public int reset() {
        return LiteApi.nativeReset(handle);
    }

    public int prepare(MGULKPowerMode mode) {
        int ordinal = mode.ordinal();
        int power = 2;
        if (ordinal != 1) {
            if (ordinal == 2) {
                power = 1;
            }
            return LiteApi.nativePrepareWithPower(handle, power);
        }
        power = 0;
        return LiteApi.nativePrepareWithPower(handle, power);
    }

    public int prepare() {
        MGULKPowerMode.MG_UNLOCK_POWER_HIGH.ordinal();
        return LiteApi.nativePrepare(handle);
    }

    public int setDetectArea(int left, int top, int right, int bottom) {
        return LiteApi.nativeSetDetectArea(handle, left, top, right, bottom);
    }

    public String getVersion() {
        return LiteApi.nativeGetVersion(handle);
    }

    public int getFeature(byte[] image, int width, int height, int angle, byte[] feature) {
        if (feature.length < FEATURE_SIZE) {
            return 1;
        }
        return LiteApi.nativeGetFeature(handle, image, width, height, angle, feature);
    }

    public int compareFeatures(byte[] feature, float[] fArray, int n, boolean b) {
        return LiteApi.nativeCompareFeatures(handle, feature, fArray, n, b);
    }

    public int checkFeatureValid(int id) {
        return LiteApi.nativeCheckFeatureValid(handle, id);
    }

    public int getFeatureCount() {
        return LiteApi.nativeGetFeatureCount();
    }

    public long setLogLevel(int level) {
        return LiteApi.nativeSetLogLevel(level);
    }

    public LiteConfig getConfig() {
        LiteConfig config = new LiteConfig(this, this, null);
        LiteApi.nativeGetConfig(handle, config);
        return config;
    }

    public static int image2NV21(Image image, byte[] out) {
        int ret = readImageIntoBuffer(image, out);
        if (ret == 1) {
            return 1;
        }
        revertHalf(out);
        return ret;
    }

    private static int readImageIntoBuffer(Image image, byte[] out) {
        if (image == null) {
            Log.e("NULL Image", "image is null");
            return 1;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        int offset = 0;
        for (int i = 0; i < planes.length; ++i) {
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
            int w = (i == 0) ? width : width / 2;
            int h = (i == 0) ? height : height / 2;
            if (pixelStride == 1 && rowStride == w) {
                int size = w * h;
                buffer.get(out, offset, size);
                offset += size;
                continue;
            }
            byte[] row = new byte[rowStride];
            int r;
            for (r = 0; r < h - 1; ++r) {
                buffer.get(row, 0, rowStride);
                int c = 0;
                while (c < w) {
                    out[offset] = row[c * pixelStride];
                    ++c;
                    ++offset;
                }
            }
            buffer.get(row, 0, Math.min(rowStride, buffer.remaining()));
            r = 0;
            while (r < w) {
                out[offset] = row[r * pixelStride];
                ++r;
                ++offset;
            }
        }
        return 0;
    }

    private static void revertHalf(byte[] data) {
        int len = data.length;
        int half = len / 3;
        byte[] tmp = new byte[half];
        int n3 = len / 6;
        int p1 = n3 * 4;
        int p2 = n3 * 5;
        int i = 0;
        while (i < half - 1) {
            tmp[i] = data[p2];
            tmp[i + 1] = data[p1];
            i += 2;
            ++p2;
            ++p1;
        }
        int dst = half * 2;
        int copyLen = len - dst;
        if (copyLen >= 0) {
            System.arraycopy(tmp, 0, data, dst, copyLen);
        }
    }

    public class LiteConfig {
        public static final int MG_UNLOCK_BIG_CPU_CORE_HIGH = 4;
        public static final int MG_UNLOCK_BIG_CPU_CORE_LOW = 0;
        public static final int MG_UNLOCK_COMPARE_ALL = 0;
        public static final int MG_UNLOCK_COMPARE_LIVE = 1;
        public static final int MG_UNLOCK_COMP_DEVICE_CPU = 1;
        public static final int MG_UNLOCK_COMP_DEVICE_NONE = 0;
        public static final int MG_UNLOCK_COMP_DEVICE_OPENCL = 3;
        public static final int MG_UNLOCK_COMP_DEVICE_SNPE = 2;
        public static final int MG_UNLOCK_EXTRACT_APU = 4;
        public static final int MG_UNLOCK_EXTRACT_DOUBLE_CORE_NORMAL = 1;
        public static final int MG_UNLOCK_EXTRACT_DSP = 3;
        public static final int MG_UNLOCK_EXTRACT_OPENCL = 2;
        public static final int MG_UNLOCK_EXTRACT_SINALE_CORE_NORMAL = 0;
        public static final int MG_UNLOCK_STORE_DEBUG_IMAGE_NONE = 0;
        public static final int MG_UNLOCK_STORE_DEBUG_IMAGE_NV21 = 1;
        public static final int MG_UNLOCK_STORE_DEBUG_IMAGE_NV21_LANDMARK = 2;

        public float ComparePitchDownThreshold;
        public float ComparePitchTopThreshold;
        public float CompareYawLeftThreshold;
        public float CompareYawRightThreshold;
        public int bigCpuCore;
        public boolean blurness;
        public int compDeviceType;
        public boolean compareBlurness;
        public int compareType;
        public int extractConfig;
        public boolean eyeOcclusion;
        public boolean eyeStatus;
        public boolean faceIntact;
        public boolean light;
        public boolean mouthOcclusion;
        public String nativeLibraryPath;
        public String openclCachePath;
        public float pitchDownThreshold;
        public float pitchTopThreshold;
        public int rectBottom;
        public int rectLeft;
        public int rectRight;
        public int rectTop;
        public String saveImagePath;
        public String snpeCachePath;
        public int storeDebugImgMode;
        public boolean useModelToCheck3dPose;
        public float yawLeftThreshold;
        public float yawRightThreshold;

        LiteConfig(Lite lite2, Lite lite3, MGULKPowerMode mode) {
            this();
        }

        private LiteConfig() {
        }

        public String toString() {
            return "LiteConfig{compDeviceType=" + compDeviceType + ", bigCpuCore=" + bigCpuCore
                    + ", useModelToCheck3dPose=" + useModelToCheck3dPose
                    + ", eyeOcclusion=" + eyeOcclusion
                    + ", mouthOcclusion=" + mouthOcclusion
                    + ", eyeStatus=" + eyeStatus
                    + ", light=" + light
                    + ", blurness=" + blurness
                    + ", compareBlurness=" + compareBlurness
                    + ", faceIntact=" + faceIntact
                    + ", yawLeftThreshold=" + yawLeftThreshold
                    + ", yawRightThreshold=" + yawRightThreshold
                    + ", pitchTopThreshold=" + pitchTopThreshold
                    + ", pitchDownThreshold=" + pitchDownThreshold
                    + ", CompareYawLeftThreshold=" + CompareYawLeftThreshold
                    + ", CompareYawRightThreshold=" + CompareYawRightThreshold
                    + ", ComparePitchTopThreshold=" + ComparePitchTopThreshold
                    + ", ComparePitchDownThreshold=" + ComparePitchDownThreshold
                    + ", rectLeft=" + rectLeft
                    + ", rectTop=" + rectTop
                    + ", rectRight=" + rectRight
                    + ", rectBottom=" + rectBottom
                    + ", storeDebugImgMode=" + storeDebugImgMode
                    + ", saveImagePath='" + saveImagePath
                    + "', compareType=" + compareType
                    + ", extractConfig=" + extractConfig
                    + ", nativeLibraryPath='" + nativeLibraryPath
                    + "', openclCachePath='" + openclCachePath
                    + "', snpeCachePath='" + snpeCachePath + "'}";
        }
    }

    public static class MGULKImage {
        public static int MG_UNLOCK_IMG_2PD = 1;
        public static int MG_UNLOCK_IMG_BGR = 2;
        public static int MG_UNLOCK_IMG_DEPTH = 5;
        public static int MG_UNLOCK_IMG_IR = 3;
        public static int MG_UNLOCK_IMG_IR_PATTERN = 4;
        public static int MG_UNLOCK_IMG_NV21;
        int angle;
        int height;
        byte[] imageData;
        int imageSize;
        int imageType;
        int width;

        public MGULKImage(int type, byte[] data, int size, int w, int h, int angle) {
            this.imageType = type;
            this.imageData = data;
            this.imageSize = size;
            this.width = w;
            this.height = h;
            this.angle = angle;
        }
    }

    public static enum MGULKPowerMode {
        MG_UNLOCK_POWER_NONE,
        MG_UNLOCK_POWER_LOW,
        MG_UNLOCK_POWER_HIGH
    }
}
