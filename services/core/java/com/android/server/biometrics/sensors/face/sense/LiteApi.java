/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package com.megvii.facepp.sdk.jni preserved as requested.
 * Source: com/megvii/facepp/sdk/jni/LiteApi.java.
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 */

package com.megvii.facepp.sdk.jni;

import com.megvii.facepp.sdk.Lite;

public class LiteApi {
    public static native int nativeCheckFeatureValid(long handle, int id);

    public static native int nativeCompare(long handle, byte[] image, int width, int height,
            int angle, boolean bl1, boolean bl2, int[] result);

    public static native int nativeCompareFeatures(long handle, byte[] feature, float[] fArray,
            int n, boolean b);

    public static native int nativeCompareMultiImages(long handle, Lite.MGULKImage[] images,
            int[] result);

    public static native int nativeDeleteFeature(long handle, int id);

    public static native long nativeGetConfig(long handle, Lite.LiteConfig config);

    public static native int nativeGetFeature(long handle, byte[] image, int width, int height,
            int angle, byte[] feature);

    public static native int nativeGetFeatureCount();

    public static native String nativeGetVersion(long handle);

    public static native long nativeInitAll(long handle, String s1, String s2, byte[] b);

    public static native long nativeInitAllWithPath(long handle, String s1, String s2, String s3);

    public static native long nativeInitDetect(long handle, byte[] b);

    public static native long nativeInitDetectWithPath(long handle, String path);

    public static native long nativeInitHandle(String path);

    public static native long nativeInitLive(long handle, String s1, String s2);

    public static native int nativePrepare(long handle);

    public static native int nativePrepareWithPower(long handle, int power);

    public static native long nativeRelease(long handle);

    public static native long nativeReleaseDetect(long handle);

    public static native long nativeReleaseLive(long handle);

    public static native int nativeReset(long handle);

    public static native int nativeSaveFeature(long handle, byte[] image, int width, int height,
            int angle, int needLiveness, byte[] feature, byte[] restoreImage, int[] result);

    public static native int nativeSaveFeatureMultiImages(long handle, Lite.MGULKImage[] images,
            byte[] feature, byte[] restoreImage, int[] result);

    public static native int nativeSetConfig(long handle, float f1, float f2, float f3, float f4,
            boolean b1, boolean b2);

    public static native int nativeSetConfigV2(long handle, Lite.LiteConfig config);

    public static native int nativeSetDetectArea(long handle, int left, int top, int right,
            int bottom);

    public static native long nativeSetLogLevel(int level);

    public static native int nativeUpdateFeature(long handle, byte[] image, int width, int height,
            int angle, int needLiveness, byte[] feature, byte[] restoreImage, int id);

    static {
        System.loadLibrary("MegviiUnlock-jni-1.2");
    }
}
