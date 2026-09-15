/*
 * Reverse-engineered from vendorImplPrebuilt.jar.
 * Original package co.aospa.sense.vendor preserved as requested.
 * Source: co/aospa/sense/vendor/Vendor.java (CFR 0.152 + javap verification).
 * Placed in-tree under services/core/.../sensors/face/sense/ for system_server build.
 */

package co.aospa.sense.vendor;

public abstract class Vendor {
    public abstract int compare(byte[] image, int width, int height, int angle,
            boolean needLiveness, boolean bl2, int[] result);

    public abstract void compareStart();

    public abstract void compareStop();

    public abstract void deleteFeature(int id);

    public abstract int getFeatureCount();

    public abstract void init();

    public abstract void release();

    public abstract int saveFeature(byte[] image, int width, int height, int angle,
            boolean needLiveness, byte[] feature, byte[] restoreImage, int[] result);

    public abstract void saveFeatureStart();

    public abstract void saveFeatureStop();

    public abstract void setDetectArea(int left, int top, int right, int bottom);
}
